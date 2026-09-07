package com.vingame.bot.infrastructure.probe;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.model.RecoveryEligibility;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Observe-only reachability probe for the environments that own DEAD bot groups
 * (DEAD_GROUP_AUTO_RECOVERY Phase 1). <b>It never starts, stops or restarts
 * anything</b> — it answers one question for the Phase 3 reconciler: is the origin
 * behind this environment serving again?
 *
 * <p>Each tick it selects the recovery candidates exactly as the future reconciler
 * will ({@link RecoveryEligibility} over the <em>persisted</em>
 * {@code targetStatus == DEAD}, unioned with the in-memory DEAD runtimes — AD-4),
 * resolves their environments, de-duplicates by {@code webSocketMiniUrl} (the only
 * WebSocket URL any bot uses, whatever its game type) and probes each URL once.
 *
 * <ul>
 *   <li><b>No candidates ⇒ no probe traffic at all.</b> That is the normal state of
 *       a healthy fleet, and it is why this can run unconditionally.</li>
 *   <li><b>A live sibling short-circuits the probe</b> (AD-10): if any running group
 *       on the same environment currently holds an open WebSocket, the environment is
 *       healthy on stronger evidence than a probe could produce, at zero network
 *       cost. Those ticks are counted under {@code outcome="live_sibling"}.</li>
 *   <li><b>Health is a streak</b> (AD-12): {@link #isHealthy(String)} is true only
 *       after {@code bot.recovery.probe.healthy-streak} consecutive healthy results,
 *       so one 200 from a flapping origin is not evidence.</li>
 * </ul>
 *
 * <p>Logging (AD-14): every individual probe result is DEBUG; INFO is emitted only
 * on a <em>transition</em> between healthy and unhealthy, so the rate is a function
 * of incidents, not of fleet size. Metrics carry explicit
 * {@code {environmentId, product}} tags read from the environment record — never
 * from MDC, because this thread's MDC belongs to whatever ran on it last (AD-13).
 *
 * <p>This scheduler runs independently of {@code bot.recovery.enabled}: that flag
 * gates the reconciler, not the observation. It is also deliberately separate from
 * the Phase 3 scheduler, whose tick blocks for the duration of a group start.
 */
@Slf4j
@Component
public class EnvironmentProbeScheduler {

    /** Counter: one increment per environment per tick, tagged with the outcome. */
    static final String ENV_WS_PROBE_TOTAL = "env_ws_probe_total";

    /** Gauge: 1 when the environment's healthy streak has reached the threshold. */
    static final String ENV_WS_PROBE_HEALTHY = "env_ws_probe_healthy";

    /** Outcome tag for AD-10's short-circuit — healthy, with no network call made. */
    static final String OUTCOME_LIVE_SIBLING = "live_sibling";

    private final BotGroupRepository botGroupRepository;
    private final BotGroupBehaviorService behaviorService;
    private final EnvironmentService environmentService;
    private final EnvironmentWsProbe probe;
    private final MeterRegistry registry;
    private final MultiGauge healthyGauge;

    /** Business wall-clock zone the activation windows are interpreted in. */
    private final ZoneId zone;

    private final long tickSeconds;

    /** Consecutive healthy probes required before an environment counts as healthy. */
    private final int healthyStreak;

    /** wsUrl → last observed state. Written only by the probe thread. */
    private final ConcurrentHashMap<String, ProbeState> states = new ConcurrentHashMap<>();

    /** environmentId → the wsUrl it was probed under, so {@link #isHealthy} can resolve. */
    private final ConcurrentHashMap<String, String> envUrls = new ConcurrentHashMap<>();

    private ScheduledExecutorService prober;

    public EnvironmentProbeScheduler(BotGroupRepository botGroupRepository,
                                     @Lazy BotGroupBehaviorService behaviorService,
                                     EnvironmentService environmentService,
                                     EnvironmentWsProbe probe,
                                     MeterRegistry registry,
                                     @Value("${bot.activation.zone:Asia/Ho_Chi_Minh}") String zone,
                                     @Value("${bot.recovery.probe.tick-seconds:60}") long tickSeconds,
                                     @Value("${bot.recovery.probe.healthy-streak:2}") int healthyStreak) {
        this.botGroupRepository = botGroupRepository;
        this.behaviorService = behaviorService;
        this.environmentService = environmentService;
        this.probe = probe;
        this.registry = registry;
        this.zone = ZoneId.of(zone);
        this.tickSeconds = tickSeconds;
        this.healthyStreak = healthyStreak;
        this.healthyGauge = MultiGauge.builder(ENV_WS_PROBE_HEALTHY)
                .description("1 when an environment's WebSocket origin has answered for "
                        + "healthy-streak consecutive probes, 0 otherwise. Rows exist only "
                        + "for environments that currently own a recovery-candidate group")
                .register(registry);
    }

    @PostConstruct
    public void start() {
        prober = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("env-ws-probe").factory()
        );
        // First tick one full period in, so a boot that auto-starts groups is not
        // probed mid-startup.
        prober.scheduleAtFixedRate(this::probeAllQuietly,
                tickSeconds * 1000L, tickSeconds * 1000L, TimeUnit.MILLISECONDS);

        log.info("Environment ws probe scheduler started (tick: {}s, healthy-streak: {}, zone: {})",
                tickSeconds, healthyStreak, zone);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down environment ws probe scheduler...");
        if (prober != null) {
            prober.shutdownNow();
        }
    }

    /**
     * Whether {@code environmentId} has answered for {@link #healthyStreak}
     * consecutive probes (AD-12). Consumed by the Phase 3 reconciler; nothing in
     * Phase 1 acts on it.
     * <p>
     * False for an environment with no recovery candidate — it is not being probed,
     * so there is no evidence either way, and "no evidence" must never authorise a
     * recovery attempt.
     */
    public boolean isHealthy(String environmentId) {
        String url = envUrls.get(environmentId);
        if (url == null) {
            return false;
        }
        ProbeState state = states.get(url);
        return state != null && state.consecutiveHealthy() >= healthyStreak;
    }

    private void probeAllQuietly() {
        try {
            probeAll();
        } catch (Exception e) {
            log.error("Environment ws probe tick failed: {}", e.getMessage(), e);
        }
    }

    /**
     * One probe pass. Package-private so tests drive it deterministically without
     * scheduling.
     */
    void probeAll() {
        Instant now = Instant.now();
        Map<String, Target> targets = buildTargets(now);

        // Only ask the runtime for live siblings when there is something to decide.
        Map<BotGroupBehaviorService.EnvKey, Integer> openWsByEnv =
                targets.isEmpty() ? Map.of() : behaviorService.countOpenWsByEnv();

        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        Map<String, String> freshEnvUrls = new HashMap<>();

        for (Target target : targets.values()) {
            try {
                rows.addAll(probeTarget(target, openWsByEnv));
                for (String envId : target.envs.keySet()) {
                    freshEnvUrls.put(envId, target.url);
                }
            } catch (Exception e) {
                log.error("Environment ws probe error for {}: {}", target.url, e.getMessage(), e);
            }
        }

        // Forget URLs and environments that no longer own a candidate: a stale streak
        // must never authorise a later recovery, and the maps must not grow.
        states.keySet().retainAll(targets.keySet());
        envUrls.putAll(freshEnvUrls);
        envUrls.keySet().retainAll(freshEnvUrls.keySet());

        // Re-register the row set every tick (overwrite=true) so values track the
        // live state and rows for departed environments disappear.
        healthyGauge.register(rows, true);
    }

    /**
     * Probe (or short-circuit) one URL and fold the result into the per-URL streak,
     * the per-environment counter and the gauge rows.
     */
    private List<MultiGauge.Row<?>> probeTarget(Target target,
                                                Map<BotGroupBehaviorService.EnvKey, Integer> openWsByEnv) {
        boolean liveSibling = hasLiveSibling(target, openWsByEnv);

        EnvironmentWsProbe.ProbeResult result = liveSibling
                ? null
                : probe.probe(target.url, target.headers);

        boolean healthy = liveSibling || result.healthy();
        String outcome = liveSibling ? OUTCOME_LIVE_SIBLING : result.outcome().tag();
        long latencyMillis = liveSibling ? 0L : result.latencyMillis();
        String detail = liveSibling ? "live sibling holds an open socket" : result.detail();

        ProbeState previous = states.get(target.url);
        int streak = healthy ? (previous == null ? 0 : previous.consecutiveHealthy()) + 1 : 0;
        states.put(target.url, new ProbeState(streak, healthy));
        boolean transition = previous == null || previous.lastHealthy() != healthy;

        List<MultiGauge.Row<?>> rows = new ArrayList<>(target.envs.size());
        for (EnvRef env : target.envs.values()) {
            registry.counter(ENV_WS_PROBE_TOTAL,
                    "environmentId", nullSafe(env.environmentId()),
                    "product", nullSafe(env.product()),
                    "outcome", outcome).increment();

            rows.add(MultiGauge.Row.of(
                    Tags.of("environmentId", nullSafe(env.environmentId()),
                            "product", nullSafe(env.product())),
                    streak >= healthyStreak ? 1 : 0));

            logResult(env, target, healthy, transition, outcome, detail, latencyMillis, streak);
        }
        return rows;
    }

    /**
     * Every result at DEBUG, a transition at INFO (AD-14). The INFO lines have to
     * stand alone in Grafana — DEBUG never reaches track 1 — so they name the
     * environment, the outcome and how many dead groups are waiting on it.
     */
    private void logResult(EnvRef env, Target target, boolean healthy, boolean transition,
                           String outcome, String detail, long latencyMillis, int streak) {
        BotMdc.setGroupContext(null, env.environmentId(), env.product());
        try {
            log.debug("env {} ({}): ws probe {} in {}ms ({}) — healthy={}, streak={}/{}",
                    env.environmentId(), env.environmentName(), outcome, latencyMillis,
                    detail, healthy, streak, healthyStreak);
            if (!transition) {
                return;
            }
            if (healthy) {
                log.info("env {} ({}): ws probe healthy ({} in {}ms) — {} dead group(s) eligible",
                        env.environmentId(), env.environmentName(), outcome, latencyMillis,
                        target.eligibleGroups);
            } else {
                log.info("env {} ({}): ws probe unhealthy ({}: {} in {}ms) — {} dead group(s) eligible",
                        env.environmentId(), env.environmentName(), outcome, detail,
                        latencyMillis, target.eligibleGroups);
            }
        } finally {
            BotMdc.clear();
        }
    }

    /**
     * AD-10: any running group on the same environment with at least one open
     * WebSocket is stronger evidence than the probe could gather, so the network
     * call is skipped entirely.
     */
    private boolean hasLiveSibling(Target target,
                                   Map<BotGroupBehaviorService.EnvKey, Integer> openWsByEnv) {
        for (Map.Entry<BotGroupBehaviorService.EnvKey, Integer> entry : openWsByEnv.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0
                    && target.envs.containsKey(entry.getKey().environmentId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The probe targets for this tick: the recovery candidates (AD-3/AD-4), resolved
     * to their environments and de-duplicated by {@code webSocketMiniUrl}.
     * Package-private for tests.
     */
    Map<String, Target> buildTargets(Instant now) {
        Set<String> deadRuntimeIds = new HashSet<>(behaviorService.listDeadRuntimeGroupIds());

        // Persisted DEAD is the primary source: it is what makes a group that died
        // before this JVM started visible at all (AD-4).
        Map<String, BotGroup> byId = new LinkedHashMap<>();
        for (BotGroup group : botGroupRepository.findByTargetStatus(BotGroupStatus.DEAD)) {
            if (group != null && group.getId() != null) {
                byId.put(group.getId(), group);
            }
        }
        for (String id : deadRuntimeIds) {
            if (id != null && !byId.containsKey(id)) {
                botGroupRepository.findById(id).ifPresent(g -> byId.put(id, g));
            }
        }

        Map<String, Target> targets = new LinkedHashMap<>();
        Map<String, Environment> envCache = new HashMap<>();

        for (BotGroup group : byId.values()) {
            String id = group.getId();
            BotGroupStatus runtimeStatus = null;
            if (deadRuntimeIds.contains(id)) {
                runtimeStatus = BotGroupStatus.DEAD;
            } else if (behaviorService.isGroupRunning(id)) {
                runtimeStatus = BotGroupStatus.ACTIVE;
            }

            boolean candidate = RecoveryEligibility.isCandidate(
                    group.getTargetStatus(), group.getActivationMode(), group.getActivationWindow(),
                    group.getBotCount(), runtimeStatus, runtimeStatus == BotGroupStatus.DEAD,
                    now, zone);
            if (!candidate) {
                continue;
            }

            String envId = group.getEnvironmentId();
            if (envId == null) {
                log.debug("Recovery candidate {} has no environment — not probing", id);
                continue;
            }
            Environment environment = resolveEnvironment(envCache, envId);
            if (environment == null) {
                continue;
            }
            String url = environment.getWebSocketMiniUrl();
            if (url == null || url.isBlank()) {
                log.debug("Environment {} has no webSocketMiniUrl — not probing", envId);
                continue;
            }

            Target target = targets.computeIfAbsent(url, u -> new Target(u, environment.getHeaders()));
            target.envs.putIfAbsent(envId, new EnvRef(envId, environment.getName(),
                    environment.getProductCode() != null ? environment.getProductCode().getCode() : null));
            target.eligibleGroups++;
        }
        return targets;
    }

    /**
     * Resolve (and per-tick cache) an environment. A deleted or unreadable
     * environment is not an error worth an ERROR line: the group simply stays DEAD
     * and is never probed, which the Open Items call the intended behaviour.
     */
    private Environment resolveEnvironment(Map<String, Environment> cache, String envId) {
        if (cache.containsKey(envId)) {
            return cache.get(envId);
        }
        Environment environment = null;
        try {
            environment = environmentService.findById(envId);
        } catch (Exception e) {
            log.debug("Cannot resolve environment {} for ws probe: {}", envId, e.getMessage());
        }
        cache.put(envId, environment);
        return environment;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** One de-duplicated probe target: a URL plus the environments that share it. */
    static final class Target {
        final String url;
        final Map<String, String> headers;
        final Map<String, EnvRef> envs = new LinkedHashMap<>();
        int eligibleGroups;

        Target(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    /** The environment identity carried onto the metrics and the log lines. */
    record EnvRef(String environmentId, String environmentName, String product) {
    }

    /**
     * Per-URL probe state (AD-11: in memory only, nothing persisted). Immutable and
     * replaced wholesale so the Phase 3 reader never sees a half-updated streak.
     */
    record ProbeState(int consecutiveHealthy, boolean lastHealthy) {
    }
}
