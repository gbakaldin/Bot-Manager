package com.vingame.bot.infrastructure.probe;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.RecoveryCandidateSelector;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Each tick it selects the recovery candidates through the shared
 * {@link RecoveryCandidateSelector} — the same call the Phase 3 reconciler makes
 * (the predicate over the <em>persisted</em>
 * {@code targetStatus == DEAD}, unioned with the in-memory DEAD runtimes — AD-4),
 * resolves their environments, de-duplicates by {@code webSocketMiniUrl} (the only
 * WebSocket URL any bot uses, whatever its game type) and probes each URL once.
 *
 * <ul>
 *   <li><b>No candidates ⇒ no probe traffic at all.</b> That is the normal state of
 *       a healthy fleet, and it is why this can run unconditionally.</li>
 *   <li><b>A live sibling short-circuits the probe</b> (AD-10): if an <b>ACTIVE</b>
 *       group on the same environment currently holds an open WebSocket, the
 *       environment is healthy at zero network cost. Those ticks are counted under
 *       {@code outcome="live_sibling"}. ACTIVE is the whole point — a DEAD runtime's
 *       surviving bots are the dying group's own sockets, not a sibling's, and the
 *       gate exists to require independent evidence.</li>
 *   <li><b>An open gateway circuit suppresses the probe</b> (GATEWAY_REQUEST_BUDGET A15.4,
 *       A29.2): while a Cloudflare edge block holds an environment's circuit open the target is
 *       unhealthy under {@code outcome="circuit_open"} and nothing is sent or stamped.</li>
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

    /**
     * Outcome tag for a probe <b>not sent</b> because an environment on the target has an open
     * gateway circuit (GATEWAY_REQUEST_BUDGET A15.4, A29.2) — unhealthy, no network call, no stamp.
     */
    static final String OUTCOME_CIRCUIT_OPEN = "circuit_open";

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

    /**
     * The per-environment gateway budgets, so a probe that really went out is stamped into
     * the window it spent (GATEWAY_REQUEST_BUDGET AD-3).
     */
    private final GatewayBudgetRegistry gatewayBudgetRegistry;

    public EnvironmentProbeScheduler(BotGroupRepository botGroupRepository,
                                     @Lazy BotGroupBehaviorService behaviorService,
                                     EnvironmentService environmentService,
                                     EnvironmentWsProbe probe,
                                     MeterRegistry registry,
                                     GatewayBudgetRegistry gatewayBudgetRegistry,
                                     @Value("${bot.activation.zone:Asia/Ho_Chi_Minh}") String zone,
                                     @Value("${bot.recovery.probe.tick-seconds:60}") long tickSeconds,
                                     @Value("${bot.recovery.probe.healthy-streak:2}") int healthyStreak) {
        this.botGroupRepository = botGroupRepository;
        this.behaviorService = behaviorService;
        this.environmentService = environmentService;
        this.probe = probe;
        this.registry = registry;
        this.gatewayBudgetRegistry = gatewayBudgetRegistry;
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
        // First tick one full period in: there is nothing useful to probe in the
        // first seconds of a JVM. This is not a guarantee that startup is over —
        // onStartup auto-starts every ACTIVE group serially and a multi-group fleet
        // takes minutes, not one tick. It does not need to be: probing during
        // startup costs nothing and the reconciler is separately gated.
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

    /**
     * How many consecutive healthy probes {@code environmentId} currently has to its
     * name, or {@code 0} if it is not being probed. {@link #isHealthy(String)} is
     * this value against the configured threshold; the raw count exists so the Phase
     * 3 attempt line can report the evidence it is acting on, which is what the plan
     * specifies (<em>"env &lt;envId&gt; healthy for &lt;k&gt; probes"</em>) and what
     * makes the INFO line stand alone in Grafana.
     */
    public int healthyStreak(String environmentId) {
        String url = envUrls.get(environmentId);
        if (url == null) {
            return 0;
        }
        ProbeState state = states.get(url);
        return state == null ? 0 : state.consecutiveHealthy();
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
        // ACTIVE runtimes only (AD-10): a DEAD runtime's surviving minority of bots
        // is the dying group's own evidence, not a sibling's — see the accessor.
        Map<BotGroupBehaviorService.EnvKey, Integer> openWsByEnv =
                targets.isEmpty() ? Map.of() : behaviorService.countOpenWsByEnvForActiveRuntimes();

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
        // Suppressed while the edge is blocking this host (A15.4, A29.2). The probe's stamps buy no
        // information then — the circuit's own clearance probe is what notices the block lifting —
        // and since A15 they are counted against the very window the block was caused by. And the
        // answer it would get, a well-formed Cloudflare 403, is one this class used to read as
        // healthy. Checked BEFORE the live-sibling short-circuit: a sibling whose socket is still
        // open from before the block is not evidence that a NEW connection would be admitted.
        boolean circuitOpen = anyCircuitOpen(target);
        boolean liveSibling = !circuitOpen && hasLiveSibling(target, openWsByEnv);
        boolean probed = !circuitOpen && !liveSibling;

        EnvironmentWsProbe.ProbeResult result = probed ? probe.probe(target.url, target.headers) : null;

        boolean healthy = liveSibling || (probed && result.healthy());
        String outcome = circuitOpen ? OUTCOME_CIRCUIT_OPEN
                : liveSibling ? OUTCOME_LIVE_SIBLING : result.outcome().tag();
        long latencyMillis = probed ? result.latencyMillis() : 0L;
        String detail = circuitOpen ? "gateway circuit open (Cloudflare edge block) — not probed"
                : liveSibling ? "live sibling holds an open socket" : result.detail();

        ProbeState previous = states.get(target.url);
        int streak = healthy ? (previous == null ? 0 : previous.consecutiveHealthy()) + 1 : 0;
        states.put(target.url, new ProbeState(streak, healthy));
        boolean transition = previous == null || previous.lastHealthy() != healthy;

        List<MultiGauge.Row<?>> rows = new ArrayList<>(target.envs.size());
        for (EnvRef env : target.envs.values()) {
            // GATEWAY_REQUEST_BUDGET AD-3: a probe COUNTS but is never queued. count() stamps
            // the window without asking for admission, which is the only correct shape for
            // it — a probe that waited behind a group start could not answer the one question
            // it exists to answer ("is the edge serving again?").
            //
            // Charged to EVERY environment on this target, not just one. Probe targets are
            // de-duplicated by webSocketMiniUrl, and two environments sharing a socket URL
            // may still sit behind different API gateway hosts, so there is no single budget
            // that is the right one. Over-counting is the safe direction: it makes the window
            // read fuller than it is, never emptier.
            //
            // A live-sibling short-circuit sends nothing and is therefore not counted; neither does
            // a probe suppressed by an open circuit.
            if (probed) {
                // countWsUpgrade, not count: the probe IS a WebSocket upgrade, so whether it
                // costs the edge a request is the same question `count-ws-upgrades` answers for
                // the three connect() sites (A5.3 / reviewer F2). With `count` here, answering
                // Open Item 1 "no" would have silenced the bots' upgrades and left the probe
                // stamping anyway — one flag governing a decision in two halves. The answer is
                // now "yes" (A15), so this stamps; the flag survives as a kill switch.
                gatewayBudgetRegistry
                        .forEnvironment(env.environmentId(), env.environmentName(), env.product())
                        .countWsUpgrade("ws-probe");
            }
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

    /** Whether any environment on this target has its gateway circuit open — read, never created. */
    private boolean anyCircuitOpen(Target target) {
        for (String envId : target.envs.keySet()) {
            if (gatewayBudgetRegistry.isCircuitOpen(envId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * AD-10: any <b>ACTIVE</b> group on the same environment with at least one open
     * WebSocket is evidence the probe could not improve on, so the network call is
     * skipped entirely.
     * <p>
     * "ACTIVE" is load-bearing and is enforced by the accessor, not here.
     * {@code countOpenWsByEnv} counts DEAD runtimes too, and
     * {@code handleBotGroupDeath} does not stop a dead group's bots — at
     * {@code dead.threshold=0.80} up to 20% of them are still connected — so the
     * common in-JVM death would otherwise have let a group's own surviving minority
     * declare its environment healthy and skip the probe entirely. Restricting to
     * ACTIVE excludes every candidate structurally, since eligibility condition 3
     * rejects any group whose runtime is ACTIVE.
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
        Map<String, Target> targets = new LinkedHashMap<>();
        Map<String, Environment> envCache = new HashMap<>();

        // Candidate selection is shared verbatim with the Phase 3 reconciler
        // (RecoveryCandidateSelector): the set we probe and the set we recover must
        // not be allowed to drift apart.
        for (BotGroup group : RecoveryCandidateSelector.select(
                botGroupRepository, behaviorService, now, zone)) {
            String id = group.getId();
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
