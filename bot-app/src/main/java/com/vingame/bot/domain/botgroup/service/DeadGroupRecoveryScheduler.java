package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.probe.EnvironmentProbeScheduler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The dead-group auto-recovery reconciler (DEAD_GROUP_AUTO_RECOVERY Phase 3). It
 * closes the gap where a transient upstream outage longer than the per-bot reconnect
 * budget (~51 minutes) turns into an indefinite outage only a human can end: when the
 * origin comes back, the groups that died during the outage restart themselves.
 *
 * <h2>What it does not do</h2>
 * It adds <b>no lifecycle code</b>. Every attempt goes through the single narrow
 * entry point {@link BotGroupBehaviorService#startForRecovery(String)}, which takes
 * the existing per-group lock and calls the existing {@code startLocked} reclaim
 * path — the same one a manual {@code /restart} uses and the same one that ended the
 * incident in ~60 seconds. That path <b>re-authenticates existing accounts</b>: it
 * never registers a user, never deposits, never recreates the Mongo group. A bug in
 * that direction would spend real money, which is why the boundary is one method and
 * not a second lifecycle.
 *
 * <h2>The five gates before anything starts</h2>
 * <ol>
 *   <li>{@code bot.recovery.enabled} — ships {@code false} (AD-6). While it is false
 *       this class does nothing at all and no {@code group_recovery_*} series ever
 *       comes into existence.</li>
 *   <li>{@link RecoveryCandidateSelector} — the shared AD-3 predicate, driven by the
 *       <em>persisted</em> {@code targetStatus} so a group that died before this JVM
 *       started is visible (AD-4). <b>{@code STOPPED} is the opt-out and it is
 *       permanent</b> (AD-5): a group an operator stopped fails the predicate
 *       forever, and there is no other way to express "leave it down". That is
 *       enforced by {@code RecoveryEligibility}'s explicit condition-2a veto rather
 *       than inferred from condition 2's disjunction, which only asks "not DEAD" —
 *       see the note on that class.</li>
 *   <li>{@link EnvironmentProbeScheduler#isHealthy(String)} — positive evidence that
 *       the environment is serving again, and only after
 *       {@code bot.recovery.probe.healthy-streak} consecutive healthy probes (AD-12).
 *       An environment that is simply down consumes <b>zero</b> budget and waits.</li>
 *   <li>The attempt budget and its backoff (AD-8) — {@code max-attempts} per death
 *       episode, then one ERROR and hands off to a human.</li>
 *   <li>{@code startForRecovery} re-asserts the whole predicate again under the group
 *       lock (AD-1), because an operator may have pressed Stop between this tick's
 *       decision and its action.</li>
 * </ol>
 *
 * <h2>Staggering is structural (AD-9)</h2>
 * One single-threaded reconciler, at most {@code bot.recovery.max-per-tick} (default
 * 1) attempt per tick: ten dead groups recover over ten minutes with no rate limiter,
 * no semaphore and no thread pool. <b>The tick blocks for the duration of one group
 * start</b> — that is the serialisation mechanism, not a defect, and it is why the
 * probe lives on its own scheduler and why no time-sensitive work may be added here.
 * Candidate order is earliest-due first, so one permanently failing group cannot
 * starve the others.
 *
 * <p>State is in memory only (AD-11): a JVM restart grants a fresh budget, which is
 * correct — a restart is new information, and the operator has by definition just
 * touched the box.
 *
 * <p>Logging is tier-1 admissible throughout (AD-14): its rate is a function of
 * incidents, never of bot count, round rate or message rate.
 */
@Slf4j
@Component
public class DeadGroupRecoveryScheduler {

    /** The group came back up: ACTIVE runtime with at least one running bot. */
    static final String OUTCOME_SUCCESS = "success";

    /** The start path ran and the group is still not up. */
    static final String OUTCOME_FAILED = "failed";

    /** The start path threw. */
    static final String OUTCOME_ERROR = "error";

    private final BotGroupRepository botGroupRepository;
    private final BotGroupBehaviorService behaviorService;
    private final EnvironmentService environmentService;
    private final EnvironmentProbeScheduler probeScheduler;
    private final BotMetrics botMetrics;

    /** AD-6 master switch. False ⇒ every tick returns before reading anything. */
    private final boolean enabled;

    /** Business wall-clock zone the activation windows are interpreted in. */
    private final ZoneId zone;

    private final long tickSeconds;
    private final int jitterSeconds;
    private final int maxAttempts;
    private final int[] backoffMinutes;
    private final int maxPerTick;
    private final int settleMinutes;

    /** groupId → recovery state (AD-11: in memory, never persisted). */
    private final ConcurrentHashMap<String, RecoveryState> states = new ConcurrentHashMap<>();

    private ScheduledExecutorService reconciler;

    public DeadGroupRecoveryScheduler(BotGroupRepository botGroupRepository,
                                      @Lazy BotGroupBehaviorService behaviorService,
                                      EnvironmentService environmentService,
                                      EnvironmentProbeScheduler probeScheduler,
                                      BotMetrics botMetrics,
                                      @Value("${bot.recovery.enabled:false}") boolean enabled,
                                      @Value("${bot.activation.zone:Asia/Ho_Chi_Minh}") String zone,
                                      @Value("${bot.recovery.tick-seconds:60}") long tickSeconds,
                                      @Value("${bot.recovery.jitter-seconds:30}") int jitterSeconds,
                                      @Value("${bot.recovery.max-attempts:6}") int maxAttempts,
                                      @Value("${bot.recovery.backoff-minutes:2,5,15,30,60,60}") int[] backoffMinutes,
                                      @Value("${bot.recovery.max-per-tick:1}") int maxPerTick,
                                      @Value("${bot.recovery.settle-minutes:10}") int settleMinutes) {
        this.botGroupRepository = botGroupRepository;
        this.behaviorService = behaviorService;
        this.environmentService = environmentService;
        this.probeScheduler = probeScheduler;
        this.botMetrics = botMetrics;
        this.enabled = enabled;
        this.zone = ZoneId.of(zone);
        this.tickSeconds = tickSeconds;
        this.jitterSeconds = Math.max(0, jitterSeconds);
        this.maxAttempts = maxAttempts;
        this.backoffMinutes = backoffMinutes == null || backoffMinutes.length == 0
                ? new int[]{5} : backoffMinutes.clone();
        this.maxPerTick = Math.max(1, maxPerTick);
        this.settleMinutes = settleMinutes;
    }

    @PostConstruct
    public void start() {
        reconciler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("dead-group-recovery").factory()
        );

        // Jitter the first tick (AD-9) so three app instances sharing an upstream do
        // not align their recovery attempts on the same second after a common outage.
        long initialDelayMillis = tickSeconds * 1000L
                + (jitterSeconds == 0 ? 0L
                        : ThreadLocalRandom.current().nextInt(jitterSeconds + 1) * 1000L);

        reconciler.scheduleAtFixedRate(this::reconcileQuietly,
                initialDelayMillis, tickSeconds * 1000L, TimeUnit.MILLISECONDS);

        // V7 greps this line for enabled=<flag>: it is the only proof, short of
        // manufacturing a DEAD group, that the phase shipped inert.
        log.info("Dead-group recovery scheduler started (enabled={}, tick={}s, first tick in {}ms, "
                        + "max-attempts={}, backoff={}min, max-per-tick={}, settle={}m, zone={})",
                enabled, tickSeconds, initialDelayMillis, maxAttempts,
                Arrays.toString(backoffMinutes), maxPerTick, settleMinutes, zone);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down dead-group recovery scheduler...");
        if (reconciler != null) {
            reconciler.shutdownNow();
        }
    }

    private void reconcileQuietly() {
        try {
            reconcileAll();
        } catch (Exception e) {
            log.error("Dead-group recovery tick failed: {}", e.getMessage(), e);
        }
    }

    /** One reconcile pass against the real clock. Package-private for tests. */
    void reconcileAll() {
        reconcileAll(Instant.now());
    }

    /**
     * One reconcile pass at {@code now}. The clock is a parameter so the backoff and
     * settle arithmetic can be tested without sleeping.
     */
    void reconcileAll(Instant now) {
        // Gate 1 (AD-6). Deliberately the very first statement: while the flag is
        // false this component reads no collaborator, touches no group and registers
        // no meter.
        if (!enabled) {
            return;
        }

        List<BotGroup> candidates =
                RecoveryCandidateSelector.select(botGroupRepository, behaviorService, now, zone);
        Set<String> candidateIds = new HashSet<>();
        for (BotGroup group : candidates) {
            candidateIds.add(group.getId());
        }
        expireStates(candidateIds, now);

        List<Due> due = new ArrayList<>();
        for (BotGroup group : candidates) {
            RecoveryState state = states.get(group.getId());

            if (state != null && state.attempts >= maxAttempts) {
                // Exhausted (AD-8): already reported, already handed to a human.
                log.debug("Recovery: group {} has spent its {}-attempt budget — waiting for an operator",
                        group.getId(), maxAttempts);
                continue;
            }
            if (!probeScheduler.isHealthy(group.getEnvironmentId())) {
                // No positive evidence the origin is back. Costs no budget (AD-8).
                log.debug("Recovery: group {} skipped — env {} is not probe-healthy",
                        group.getId(), group.getEnvironmentId());
                continue;
            }
            Instant nextDue = state == null ? Instant.EPOCH : state.nextDue;
            if (nextDue.isAfter(now)) {
                log.debug("Recovery: group {} skipped — next attempt due {}", group.getId(), nextDue);
                continue;
            }
            due.add(new Due(group, nextDue, deadSince(group)));
        }

        // Earliest-due first (AD-9). The tie-break is how long the group has been
        // down, oldest first; the id is the final tie-break purely so the order is
        // deterministic. Ordering by due time is what makes the rotation fair: a
        // group that just failed carries the largest deadline and goes last.
        due.sort(Comparator.comparing((Due d) -> d.nextDue)
                .thenComparing(d -> d.deadSince)
                .thenComparing(d -> d.group.getId()));

        int limit = Math.min(maxPerTick, due.size());
        for (int i = 0; i < limit; i++) {
            attempt(due.get(i).group, now);
        }
    }

    /**
     * One recovery attempt on one group, fully isolated: the caller's tick must
     * survive any failure of this group (mirrors {@code ActivationScheduler}).
     * Group MDC is set around the whole attempt so every line and both counters
     * carry {@code botGroupId} / {@code environmentId} / {@code product}.
     *
     * <p><b>The MDC has to be re-asserted after {@code startForRecovery} returns</b>,
     * and that is not defensive tidying. Every path through {@code startLocked} calls
     * {@link BotMdc#clear()} on <em>this</em> thread before returning — the reclaim
     * path's {@code teardownRuntimeMemory}, the normal build path's
     * {@code createBotsInParallel} result loop, and the failure path's outer
     * {@code finally} — and {@code clear()} removes the keys outright, with no
     * save/restore. Without the re-assert, {@code incGroupRecoveryAttempt} and
     * {@code incGroupRecoveryExhausted} register through an empty {@code mdcTags()},
     * so AD-13's {@code botGroupId} / {@code environmentId} / {@code product} never
     * land; {@code BotMdcTagsMeterFilter} is no safety net either, because it only
     * touches {@code bot_}-prefixed names. Worse, the loss is not uniform — a
     * {@code startLocked} that throws before {@code runningGroups.put} never reaches
     * a {@code clear()} — so one counter name would carry two different series
     * shapes.
     */
    private void attempt(BotGroup group, Instant now) {
        String id = group.getId();
        RecoveryState state = states.computeIfAbsent(id, k -> new RecoveryState());
        int attemptNumber = state.attempts + 1;
        String product = resolveProduct(group.getEnvironmentId());

        BotMdc.setGroupContext(id, group.getEnvironmentId(), product);
        long startedAtNanos = System.nanoTime();
        try {
            log.info("group {} ({}): auto-recovery attempt {}/{} — env {} healthy for {} probe(s), "
                            + "dead since {}, reason \"{}\"",
                    id, group.getName(), attemptNumber, maxAttempts, group.getEnvironmentId(),
                    probeScheduler.healthyStreak(group.getEnvironmentId()),
                    deadSinceText(group), nullSafe(group.getLastFailureReason()));

            boolean up;
            try {
                up = behaviorService.startForRecovery(id);
            } finally {
                // See the javadoc: startLocked cleared our MDC on the way out, on
                // every path including the one that throws.
                BotMdc.setGroupContext(id, group.getEnvironmentId(), product);
            }
            long seconds = Duration.ofNanos(System.nanoTime() - startedAtNanos).toSeconds();

            if (up) {
                state.attempts = attemptNumber;
                state.lastSuccess = now;
                state.nextDue = now.plus(backoffAfter(attemptNumber));
                botMetrics.incGroupRecoveryAttempt(OUTCOME_SUCCESS);
                log.info("group {} ({}): auto-recovery succeeded — {}/{} bots up in {}s",
                        id, group.getName(), behaviorService.getRunningBotCountForGroup(id),
                        group.getBotCount(), seconds);
            } else {
                recordFailure(state, group, attemptNumber, now, OUTCOME_FAILED,
                        "actualStatus=" + behaviorService.getActualStatus(id)
                                + ", " + behaviorService.getRunningBotCountForGroup(id) + "/"
                                + group.getBotCount() + " bots up after " + seconds + "s",
                        null);
            }
        } catch (Exception e) {
            recordFailure(state, group, attemptNumber, now, OUTCOME_ERROR, String.valueOf(e), e);
        } finally {
            BotMdc.clear();
        }
    }

    /**
     * Charge one attempt to the budget, advance the backoff, and — if that was the
     * last attempt — emit the single hand-off ERROR (AD-8).
     */
    private void recordFailure(RecoveryState state, BotGroup group, int attemptNumber, Instant now,
                               String outcome, String detail, Throwable error) {
        state.attempts = attemptNumber;
        Duration wait = backoffAfter(attemptNumber);
        state.nextDue = now.plus(wait);
        botMetrics.incGroupRecoveryAttempt(outcome);

        if (error != null) {
            log.error("group {} ({}): auto-recovery attempt {}/{} failed ({}) — next attempt in {}m",
                    group.getId(), group.getName(), attemptNumber, maxAttempts, detail,
                    wait.toMinutes(), error);
        } else {
            log.warn("group {} ({}): auto-recovery attempt {}/{} failed ({}) — next attempt in {}m",
                    group.getId(), group.getName(), attemptNumber, maxAttempts, detail,
                    wait.toMinutes());
        }

        if (attemptNumber >= maxAttempts && !state.exhaustedReported) {
            state.exhaustedReported = true;
            botMetrics.incGroupRecoveryExhausted();
            log.error("group {} ({}): auto-recovery exhausted after {} attempts — operator action "
                            + "required (POST /api/v1/bot-group/{}/restart)",
                    group.getId(), group.getName(), maxAttempts, group.getId());
        }
    }

    /**
     * Budget reset and map hygiene, in one rule: a group that is no longer a
     * candidate has been resolved by somebody — a successful recovery, a manual
     * {@code /start} or {@code /restart}, or an operator parking it {@code STOPPED} —
     * so its state is dropped and the next death episode starts with a fresh budget.
     * <p>
     * The one exception is the settle window (AD-8's {@code settle-minutes}): for
     * that long after a success the state is retained, so a group that "recovers"
     * and immediately dies again resumes the <em>same</em> budget instead of getting
     * a new one every flap. Only a recovery that actually held for the settle window
     * earns the reset.
     */
    private void expireStates(Set<String> candidateIds, Instant now) {
        states.entrySet().removeIf(entry -> {
            if (candidateIds.contains(entry.getKey())) {
                return false;
            }
            RecoveryState state = entry.getValue();
            if (state.lastSuccess != null
                    && now.isBefore(state.lastSuccess.plus(Duration.ofMinutes(settleMinutes)))) {
                return false;
            }
            log.debug("Recovery: dropping state for group {} ({} attempt(s) spent) — "
                    + "no longer a candidate", entry.getKey(), state.attempts);
            return true;
        });
    }

    /**
     * The wait after the {@code n}-th attempt of an episode: {@code backoff[n-1]},
     * clamped to the last configured entry, so the shipped {@code 2,5,15,30,60,60}
     * spaces six attempts over roughly two hours before the hand-off.
     */
    private Duration backoffAfter(int attemptNumber) {
        int index = Math.min(Math.max(attemptNumber - 1, 0), backoffMinutes.length - 1);
        return Duration.ofMinutes(backoffMinutes[index]);
    }

    /**
     * How long the group has been down, as a tie-break only. There is no persisted
     * "died at" stamp — {@code handleBotGroupDeath} writes {@code targetStatus} and
     * {@code lastFailureReason} and nothing else — so this is the best available
     * proxy, and an unknown value sorts last rather than pretending to be old.
     */
    private static LocalDateTime deadSince(BotGroup group) {
        if (group.getLastStoppedAt() != null) {
            return group.getLastStoppedAt();
        }
        return group.getLastStartedAt() != null ? group.getLastStartedAt() : LocalDateTime.MAX;
    }

    private static String deadSinceText(BotGroup group) {
        LocalDateTime since = deadSince(group);
        return since == LocalDateTime.MAX ? "unknown" : since.toString();
    }

    /**
     * The product label for this group's meters and MDC. Resolved from the
     * environment, like every other {@code product} label in the app; a missing or
     * unreadable environment simply drops the label rather than failing the attempt
     * (the attempt itself will fail on its own, loudly, inside {@code startLocked}).
     */
    private String resolveProduct(String environmentId) {
        if (environmentId == null) {
            return null;
        }
        try {
            Environment environment = environmentService.findById(environmentId);
            return environment != null && environment.getProductCode() != null
                    ? environment.getProductCode().getCode() : null;
        } catch (Exception e) {
            log.debug("Cannot resolve environment {} for recovery MDC: {}", environmentId, e.getMessage());
            return null;
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** A candidate that is due, with its sort keys. */
    private record Due(BotGroup group, Instant nextDue, LocalDateTime deadSince) {
    }

    /**
     * Per-group recovery state for one death episode (AD-11). Mutable and confined
     * to the single reconciler thread; nothing else reads it.
     */
    static final class RecoveryState {
        /** Attempts charged to the budget in this episode. */
        int attempts;

        /** Earliest instant the next attempt may run. EPOCH = due immediately. */
        Instant nextDue = Instant.EPOCH;

        /** When the last successful recovery happened, for the settle window. */
        Instant lastSuccess;

        /** Whether the one hand-off ERROR + counter has already been emitted. */
        boolean exhaustedReported;
    }
}
