package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.ActivationWindow;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.probe.EnvironmentProbeScheduler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit cover for the recovery reconciler (DEAD_GROUP_AUTO_RECOVERY Phase 3).
 *
 * <p>The pure predicate is exercised exhaustively by {@code RecoveryEligibilityTest};
 * what is asserted here is everything that only exists once the pieces are joined,
 * and in particular the four properties that make this component safe to ship:
 * <ul>
 *   <li><b>inert at the shipped default</b> — {@code bot.recovery.enabled=false}
 *       starts nothing and registers no {@code group_recovery_*} series;</li>
 *   <li><b>{@code STOPPED} is never auto-recovered</b> (AD-5) — the opt-out, and the
 *       invariant an operator's intent depends on;</li>
 *   <li><b>an unhealthy environment costs no budget</b> (AD-8/AD-12);</li>
 *   <li><b>the budget is finite and hands off exactly once</b> (AD-8).</li>
 * </ul>
 * The clock is passed into {@code reconcileAll(Instant)} so the backoff and settle
 * arithmetic is asserted without sleeping.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeadGroupRecoveryScheduler.reconcileAll")
class DeadGroupRecoverySchedulerTest {

    private static final String ZONE = "Asia/Ho_Chi_Minh";
    private static final ZoneId ZONE_ID = ZoneId.of(ZONE);
    private static final Instant T0 = Instant.parse("2026-09-07T10:00:00Z");

    @Mock
    private BotGroupRepository repository;

    @Mock
    private BotGroupBehaviorService behaviorService;

    @Mock
    private EnvironmentService environmentService;

    @Mock
    private EnvironmentProbeScheduler probeScheduler;

    private SimpleMeterRegistry registry;
    private BotMetrics botMetrics;

    private CapturingAppender appender;
    private LoggerContext ctx;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("dead-group-recovery-capture", null, PatternLayout.createDefaultLayout(), true, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        botMetrics = new BotMetrics(registry);

        appender = new CapturingAppender();
        appender.start();
        ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = ctx.getConfiguration()
                .getLoggerConfig(DeadGroupRecoveryScheduler.class.getName());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.setLevel(Level.INFO);
        loggerConfig.addAppender(appender, Level.INFO, null);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        loggerConfig.removeAppender("dead-group-recovery-capture");
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    /**
     * @param backoff minutes between attempts; {@code 0} keeps a group due on every
     *                tick so budget arithmetic can be driven without a clock.
     */
    private DeadGroupRecoveryScheduler scheduler(boolean enabled, int maxAttempts,
                                                 int maxPerTick, int... backoff) {
        // Do NOT call @PostConstruct start() — that would spin a real reconciler
        // thread. reconcileAll(now) is driven directly.
        return new DeadGroupRecoveryScheduler(repository, behaviorService, environmentService,
                probeScheduler, botMetrics, enabled, ZONE, 60L, 30, maxAttempts, backoff,
                maxPerTick, 10);
    }

    private static BotGroup deadGroup(String id) {
        return BotGroup.builder()
                .id(id)
                .name("group-" + id)
                .environmentId("env-1")
                .botCount(20)
                .targetStatus(BotGroupStatus.DEAD)
                .lastFailureReason("Multiple bot disconnections detected")
                .build();
    }

    /** The steady state of every test: one persisted-DEAD group, no in-memory runtimes. */
    private void persistedDead(BotGroup... groups) {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD)).thenReturn(List.of(groups));
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of());
    }

    private void envIsHealthy() {
        lenient().when(probeScheduler.isHealthy("env-1")).thenReturn(true);
        lenient().when(probeScheduler.healthyStreak("env-1")).thenReturn(2);
        lenient().when(environmentService.findById("env-1")).thenReturn(Environment.builder()
                .id("env-1").name("TIP staging").productCode(ProductCode.P_116).build());
    }

    /**
     * A {@code startForRecovery} stub that <b>clears this thread's MDC</b> before it
     * returns, because the real one does: every path through {@code startLocked}
     * calls {@code BotMdc.clear()} on the caller thread — the reclaim path's
     * {@code teardownRuntimeMemory}, the build path's {@code createBotsInParallel}
     * result loop, and the failure path's outer {@code finally}. A stub that leaves
     * the MDC alone cannot see AD-13's tags going missing, which is exactly how the
     * first version of {@code successIsLoggedAndCounted} passed against a scheduler
     * that registered both counters with an empty {@code mdcTags()}.
     *
     * @param results the value for each successive call; the last one repeats
     */
    private static Answer<Boolean> startsClearingMdc(boolean... results) {
        AtomicInteger call = new AtomicInteger();
        return invocation -> {
            BotMdc.clear();
            return results[Math.min(call.getAndIncrement(), results.length - 1)];
        };
    }

    /**
     * The throwing counterpart. {@code startLocked} clears the MDC on its failure
     * path too whenever a runtime was registered, so the {@code outcome="error"}
     * series has the same tagging hazard as the other two.
     */
    private static Answer<Boolean> throwsClearingMdc(RuntimeException error) {
        return invocation -> {
            BotMdc.clear();
            throw error;
        };
    }

    /** The AD-13 tag set every {@code group_recovery_*} series must carry. */
    private io.micrometer.core.instrument.Counter taggedAttempt(String outcome) {
        return registry.find(BotMetrics.GROUP_RECOVERY_ATTEMPTS_TOTAL)
                .tags("outcome", outcome, "botGroupId", "g1",
                        "environmentId", "env-1", "product", "116")
                .counter();
    }

    private List<String> lines(Level level) {
        return appender.events.stream()
                .filter(e -> e.getLevel() == level)
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    private double attempts(String outcome) {
        var c = registry.find(BotMetrics.GROUP_RECOVERY_ATTEMPTS_TOTAL)
                .tags("outcome", outcome).counter();
        return c == null ? 0d : c.count();
    }

    /* ---------------- shipped inert ---------------- */

    @Test
    @DisplayName("bot.recovery.enabled=false: nothing is read, nothing is started, no series exists")
    void disabledIsCompletelyInert() {
        DeadGroupRecoveryScheduler scheduler = scheduler(false, 6, 1, 2, 5);

        scheduler.reconcileAll(T0);

        // Not "no start" — no interaction at all, which is the strongest statement
        // that Phase 3 changes nothing until somebody flips the flag.
        verifyNoInteractions(repository, behaviorService, environmentService, probeScheduler);
        assertThat(registry.find(BotMetrics.GROUP_RECOVERY_ATTEMPTS_TOTAL).counters()).isEmpty();
        assertThat(registry.find(BotMetrics.GROUP_RECOVERY_EXHAUSTED_TOTAL).counters()).isEmpty();
        assertThat(lines(Level.INFO)).isEmpty();
    }

    /* ---------------- the opt-out (AD-5) ---------------- */

    @Test
    @DisplayName("a STOPPED group is never auto-recovered — the opt-out is permanent (AD-5)")
    void stoppedGroupIsNeverAttempted() {
        BotGroup stopped = deadGroup("g1").toBuilder()
                .targetStatus(BotGroupStatus.STOPPED)
                .build();
        // Returned by the DEAD query defensively: even if a STOPPED row reached the
        // selector, the predicate must reject it.
        persistedDead(stopped);
        DeadGroupRecoveryScheduler scheduler = scheduler(true, 6, 1, 2, 5);

        for (int i = 0; i < 5; i++) {
            scheduler.reconcileAll(T0.plusSeconds(i * 3600L));
        }

        verify(behaviorService, never()).startForRecovery(anyString());
        assertThat(attempts(DeadGroupRecoveryScheduler.OUTCOME_SUCCESS)).isZero();
        assertThat(lines(Level.INFO)).isEmpty();
    }

    /**
     * The other route to a {@code STOPPED} candidate, and the one that actually
     * produces it: not the DEAD query — a {@code STOPPED} row is not in it — but the
     * AD-4 union over the in-memory DEAD runtimes, which re-reads the row by id and
     * evaluates it with {@code runtimeStatus = DEAD}. Before {@code RecoveryEligibility}
     * gained its explicit condition-2a veto this passed the disjunction, and
     * {@code startForRecovery} re-asserts the same predicate, so there was no second
     * gate behind it.
     */
    @Test
    @DisplayName("a STOPPED row that enters through the dead-runtime union is never attempted either (AD-5)")
    void stoppedGroupReachedThroughTheDeadRuntimeUnionIsNeverAttempted() {
        BotGroup stopped = deadGroup("g1").toBuilder()
                .targetStatus(BotGroupStatus.STOPPED)
                .build();
        when(repository.findByTargetStatus(BotGroupStatus.DEAD)).thenReturn(List.of());
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of("g1"));
        when(repository.findById("g1")).thenReturn(java.util.Optional.of(stopped));
        DeadGroupRecoveryScheduler scheduler = scheduler(true, 6, 1, 2, 5);

        for (int i = 0; i < 5; i++) {
            scheduler.reconcileAll(T0.plusSeconds(i * 3600L));
        }

        verify(behaviorService, never()).startForRecovery(anyString());
        assertThat(registry.find(BotMetrics.GROUP_RECOVERY_ATTEMPTS_TOTAL).counters()).isEmpty();
        assertThat(lines(Level.INFO)).isEmpty();
    }

    @Test
    @DisplayName("a MANUAL_OFF group is never attempted")
    void manualOffIsNeverAttempted() {
        persistedDead(deadGroup("g1").toBuilder()
                .activationMode(ActivationMode.MANUAL_OFF).build());

        scheduler(true, 6, 1, 2, 5).reconcileAll(T0);

        verify(behaviorService, never()).startForRecovery(anyString());
    }

    @Test
    @DisplayName("a SCHEDULED group whose window is closed is never resurrected into it")
    void scheduledGroupOutsideItsWindowIsNeverAttempted() {
        LocalTime now = ZonedDateTime.now(ZONE_ID).toLocalTime();
        persistedDead(deadGroup("g1").toBuilder()
                .activationMode(ActivationMode.SCHEDULED)
                .activationWindow(ActivationWindow.builder()
                        .from(now.plusHours(1)).to(now.plusHours(2)).days(Set.of()).build())
                .build());

        scheduler(true, 6, 1, 2, 5).reconcileAll(Instant.now());

        verify(behaviorService, never()).startForRecovery(anyString());
    }

    /* ---------------- the probe gate (AD-12) ---------------- */

    @Test
    @DisplayName("an environment that is not probe-healthy is never attempted and costs no budget")
    void unhealthyEnvironmentIsNotAttempted() {
        persistedDead(deadGroup("g1"));
        when(probeScheduler.isHealthy("env-1")).thenReturn(false);
        DeadGroupRecoveryScheduler scheduler = scheduler(true, 6, 1, 2, 5);

        for (int i = 0; i < 10; i++) {
            scheduler.reconcileAll(T0.plusSeconds(i * 60L));
        }

        verify(behaviorService, never()).startForRecovery(anyString());
        assertThat(registry.find(BotMetrics.GROUP_RECOVERY_ATTEMPTS_TOTAL).counters()).isEmpty();

        // The budget is intact: the moment the origin comes back, attempt 1 of 6 runs.
        when(probeScheduler.isHealthy("env-1")).thenReturn(true);
        envIsHealthy();
        when(behaviorService.startForRecovery("g1")).thenAnswer(startsClearingMdc(true));
        when(behaviorService.getRunningBotCountForGroup("g1")).thenReturn(20);

        scheduler.reconcileAll(T0.plusSeconds(600));

        assertThat(lines(Level.INFO)).anyMatch(l -> l.contains("auto-recovery attempt 1/6"));
    }

    /* ---------------- one per tick, earliest-due first (AD-9) ---------------- */

    @Test
    @DisplayName("at most max-per-tick attempts happen in one tick, and the rest wait")
    void atMostMaxPerTickPerTick() {
        persistedDead(deadGroup("g1"), deadGroup("g2"), deadGroup("g3"));
        envIsHealthy();
        when(behaviorService.startForRecovery(anyString())).thenAnswer(startsClearingMdc(false));
        lenient().when(behaviorService.getActualStatus(anyString())).thenReturn(BotGroupStatus.STOPPED);

        scheduler(true, 6, 1, 2, 5).reconcileAll(T0);

        verify(behaviorService, times(1)).startForRecovery(anyString());
        assertThat(attempts(DeadGroupRecoveryScheduler.OUTCOME_FAILED)).isEqualTo(1d);
    }

    @Test
    @DisplayName("earliest-due first rotates: a failing group cannot starve its siblings")
    void earliestDueOrderingRotates() {
        persistedDead(deadGroup("g1"), deadGroup("g2"));
        envIsHealthy();
        when(behaviorService.startForRecovery(anyString())).thenAnswer(startsClearingMdc(false));
        lenient().when(behaviorService.getActualStatus(anyString())).thenReturn(BotGroupStatus.STOPPED);
        DeadGroupRecoveryScheduler scheduler = scheduler(true, 6, 1, 2, 5);

        scheduler.reconcileAll(T0);              // g1 (tie broken by id), now due at +2m
        scheduler.reconcileAll(T0.plusSeconds(30));  // g1 not due → g2

        verify(behaviorService).startForRecovery("g1");
        verify(behaviorService).startForRecovery("g2");
    }

    /* ---------------- backoff (AD-8) ---------------- */

    @Test
    @DisplayName("backoff advances only on failure and is honoured to the second")
    void backoffAdvancesOnFailure() {
        persistedDead(deadGroup("g1"));
        envIsHealthy();
        when(behaviorService.startForRecovery("g1")).thenAnswer(startsClearingMdc(false));
        lenient().when(behaviorService.getActualStatus("g1")).thenReturn(BotGroupStatus.STOPPED);
        DeadGroupRecoveryScheduler scheduler = scheduler(true, 6, 1, 2, 5);

        scheduler.reconcileAll(T0);                       // attempt 1, next due +2m
        scheduler.reconcileAll(T0.plusSeconds(119));      // too early
        verify(behaviorService, times(1)).startForRecovery("g1");

        scheduler.reconcileAll(T0.plusSeconds(120));      // attempt 2, next due +5m
        verify(behaviorService, times(2)).startForRecovery("g1");

        scheduler.reconcileAll(T0.plusSeconds(300));      // still inside the 5m gap
        verify(behaviorService, times(2)).startForRecovery("g1");

        scheduler.reconcileAll(T0.plusSeconds(420));      // attempt 3
        verify(behaviorService, times(3)).startForRecovery("g1");

        assertThat(lines(Level.WARN)).hasSize(3);
        assertThat(lines(Level.WARN).get(0))
                .contains("auto-recovery attempt 1/6 failed", "next attempt in 2m");
        assertThat(lines(Level.WARN).get(1)).contains("next attempt in 5m");
    }

    /* ---------------- exhaustion (AD-8) ---------------- */

    @Test
    @DisplayName("the budget is finite: max attempts, one ERROR + one counter, then it stops")
    void budgetExhaustsOnceAndThenStops() {
        persistedDead(deadGroup("g1"));
        envIsHealthy();
        when(behaviorService.startForRecovery("g1")).thenAnswer(startsClearingMdc(false));
        lenient().when(behaviorService.getActualStatus("g1")).thenReturn(BotGroupStatus.STOPPED);
        // backoff 0 ⇒ due on every tick, so the whole episode runs without a clock.
        DeadGroupRecoveryScheduler scheduler = scheduler(true, 3, 1, 0);

        for (int i = 0; i < 12; i++) {
            scheduler.reconcileAll(T0.plusSeconds(i * 60L));
        }

        verify(behaviorService, times(3)).startForRecovery("g1");
        assertThat(attempts(DeadGroupRecoveryScheduler.OUTCOME_FAILED)).isEqualTo(3d);

        List<String> errors = lines(Level.ERROR);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains(
                "auto-recovery exhausted after 3 attempts — operator action required",
                "/api/v1/bot-group/g1/restart");
        assertThat(registry.find(BotMetrics.GROUP_RECOVERY_EXHAUSTED_TOTAL).counter().count())
                .isEqualTo(1d);
    }

    /* ---------------- success (AD-13 tags, AD-14 lines) ---------------- */

    @Test
    @DisplayName("a successful recovery logs the pair of INFO lines and counts one success, MDC-tagged")
    void successIsLoggedAndCounted() {
        persistedDead(deadGroup("g1"));
        envIsHealthy();
        when(behaviorService.startForRecovery("g1")).thenAnswer(startsClearingMdc(true));
        when(behaviorService.getRunningBotCountForGroup("g1")).thenReturn(19);

        scheduler(true, 6, 1, 2, 5).reconcileAll(T0);

        assertThat(lines(Level.INFO)).hasSize(2);
        assertThat(lines(Level.INFO).get(0)).contains(
                "group g1 (group-g1): auto-recovery attempt 1/6",
                "Multiple bot disconnections detected");
        assertThat(lines(Level.INFO).get(1))
                .contains("auto-recovery succeeded — 19/20 bots up");

        // AD-13: group-scoped tags from MDC, exactly like group_dead_seconds_total —
        // and asserted against a stub that clears the MDC the way startLocked does,
        // so the re-assert in attempt() is what makes this pass.
        assertThat(taggedAttempt("success"))
                .as("the success counter must survive startLocked clearing the MDC")
                .isNotNull();
        assertThat(taggedAttempt("success").count()).isEqualTo(1d);
    }

    @Test
    @DisplayName("a thrown start is charged as outcome=error, logged with the stack trace, and backed off")
    void exceptionIsChargedAsError() {
        persistedDead(deadGroup("g1"));
        envIsHealthy();
        when(behaviorService.startForRecovery("g1"))
                .thenAnswer(throwsClearingMdc(new IllegalStateException("auth gateway returned 503")));

        scheduler(true, 6, 1, 2, 5).reconcileAll(T0);

        assertThat(attempts(DeadGroupRecoveryScheduler.OUTCOME_ERROR)).isEqualTo(1d);
        LogEvent error = appender.events.stream()
                .filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow();
        assertThat(error.getMessage().getFormattedMessage())
                .contains("auto-recovery attempt 1/6 failed", "auth gateway returned 503");
        assertThat(error.getThrown()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the failed, error and exhausted series carry the AD-13 tags too, not just success")
    void everyRecoverySeriesIsMdcTagged() {
        // The MDC loss was not uniform: startLocked clears it on the reclaim and
        // build paths and on the failure path once a runtime was registered, but a
        // throw before runningGroups.put never reaches a clear(). One counter name
        // with two series shapes is worse than one wrong shape, so all three
        // outcomes plus the exhaustion counter are asserted with the full tag set.
        persistedDead(deadGroup("g1"));
        envIsHealthy();
        when(behaviorService.startForRecovery("g1")).thenAnswer(startsClearingMdc(false));
        lenient().when(behaviorService.getActualStatus("g1")).thenReturn(BotGroupStatus.STOPPED);
        DeadGroupRecoveryScheduler failing = scheduler(true, 2, 1, 0);

        failing.reconcileAll(T0);
        failing.reconcileAll(T0.plusSeconds(60));

        assertThat(taggedAttempt(DeadGroupRecoveryScheduler.OUTCOME_FAILED)).isNotNull();
        assertThat(taggedAttempt(DeadGroupRecoveryScheduler.OUTCOME_FAILED).count()).isEqualTo(2d);
        assertThat(registry.find(BotMetrics.GROUP_RECOVERY_EXHAUSTED_TOTAL)
                .tags("botGroupId", "g1", "environmentId", "env-1", "product", "116").counter())
                .as("the hand-off counter is what Phase 4's alert routes on — it needs the product tag")
                .isNotNull();
    }

    @Test
    @DisplayName("a thrown start still registers outcome=error with the group tags")
    void errorSeriesIsMdcTagged() {
        persistedDead(deadGroup("g1"));
        envIsHealthy();
        when(behaviorService.startForRecovery("g1"))
                .thenAnswer(throwsClearingMdc(new IllegalStateException("auth gateway returned 503")));

        scheduler(true, 6, 1, 2, 5).reconcileAll(T0);

        assertThat(taggedAttempt(DeadGroupRecoveryScheduler.OUTCOME_ERROR)).isNotNull();
        assertThat(taggedAttempt(DeadGroupRecoveryScheduler.OUTCOME_ERROR).count()).isEqualTo(1d);
    }

    /* ---------------- budget reset (AD-8 / settle) ---------------- */

    @Test
    @DisplayName("the budget resets only after a recovery has held for settle-minutes")
    void budgetResetsAfterTheSettleWindow() {
        // Tick 1: dead → attempt 1 succeeds. Ticks 2..n: the group is no longer a
        // candidate. The state must survive the settle window and be dropped after it.
        when(repository.findByTargetStatus(BotGroupStatus.DEAD)).thenReturn(
                List.of(deadGroup("g1")),   // t0        — attempt 1, succeeds
                List.of(deadGroup("g1")),   // t0 + 5m   — re-died inside the settle window
                List.of(),                  // t0 + 20m  — healthy, settle elapsed → reset
                List.of(deadGroup("g1")));  // t0 + 25m  — dies again, fresh budget
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of());
        envIsHealthy();
        when(behaviorService.startForRecovery("g1")).thenAnswer(startsClearingMdc(true));
        when(behaviorService.getRunningBotCountForGroup("g1")).thenReturn(20);
        DeadGroupRecoveryScheduler scheduler = scheduler(true, 6, 1, 2, 5);

        scheduler.reconcileAll(T0);
        scheduler.reconcileAll(T0.plusSeconds(300));
        scheduler.reconcileAll(T0.plusSeconds(1200));
        scheduler.reconcileAll(T0.plusSeconds(1500));

        List<String> attemptLines = lines(Level.INFO).stream()
                .filter(l -> l.contains("auto-recovery attempt")).toList();
        assertThat(attemptLines).hasSize(3);
        // A flap inside the settle window keeps spending the same budget...
        assertThat(attemptLines.get(0)).contains("attempt 1/6");
        assertThat(attemptLines.get(1)).contains("attempt 2/6");
        // ...and only a recovery that actually held earns a fresh one.
        assertThat(attemptLines.get(2)).contains("attempt 1/6");
    }

    /* ---------------- QA additions: backoff arithmetic, tie-break, budget edge ---------------- */

    @Test
    @DisplayName("backoff index is backoff[min(n-1, len-1)] — the last entry is reused once the table runs out")
    void backoffIndexClampsToTheLastEntry() {
        persistedDead(deadGroup("g1"));
        envIsHealthy();
        when(behaviorService.startForRecovery("g1")).thenAnswer(startsClearingMdc(false));
        lenient().when(behaviorService.getActualStatus("g1")).thenReturn(BotGroupStatus.STOPPED);
        // Four attempts against a two-entry table: the clamp must supply the 3rd and 4th.
        DeadGroupRecoveryScheduler scheduler = scheduler(true, 4, 1, 1, 2);

        scheduler.reconcileAll(T0);                  // attempt 1 → wait backoff[0] = 1m
        scheduler.reconcileAll(T0.plusSeconds(60));  // attempt 2 → wait backoff[1] = 2m
        scheduler.reconcileAll(T0.plusSeconds(180)); // attempt 3 → clamped to backoff[1]
        scheduler.reconcileAll(T0.plusSeconds(300)); // attempt 4 → clamped to backoff[1]

        verify(behaviorService, times(4)).startForRecovery("g1");
        assertThat(lines(Level.WARN)).hasSize(4);
        assertThat(lines(Level.WARN).get(0)).contains("next attempt in 1m");
        assertThat(lines(Level.WARN).get(1)).contains("next attempt in 2m");
        assertThat(lines(Level.WARN).get(2)).contains("next attempt in 2m");
        assertThat(lines(Level.WARN).get(3)).contains("next attempt in 2m");
    }

    /**
     * The earliest-due tie-break. Every fresh candidate carries {@code nextDue =
     * EPOCH}, so on the first tick after a mass death the tie-break <em>is</em> the
     * ordering, and it is {@code lastStoppedAt ?: lastStartedAt}, oldest first.
     * <p>
     * <b>Note what it measures:</b> there is no persisted died-at stamp, and
     * {@code handleBotGroupDeath} writes neither timestamp, while every successful
     * start nulls {@code lastStoppedAt}. So for a group that died in flight this
     * resolves to {@code lastStartedAt} — "started longest ago", not "dead longest".
     * The two orders are not the same one, and {@code deadSince}'s javadoc now says
     * so rather than claiming the second.
     */
    @Test
    @DisplayName("the earliest-due tie-break is lastStoppedAt ?: lastStartedAt, oldest first — it beats the id tie-break")
    void tieBreakPrefersTheOlderDeadSinceStamp() {
        LocalDateTime base = LocalDateTime.of(2026, 9, 7, 9, 0);
        // "g-a" sorts first by id but was started most recently; "g-z" must win.
        persistedDead(
                deadGroup("g-a").toBuilder().lastStartedAt(base.plusHours(3)).build(),
                deadGroup("g-z").toBuilder().lastStartedAt(base).build());
        envIsHealthy();
        when(behaviorService.startForRecovery(anyString())).thenAnswer(startsClearingMdc(false));
        lenient().when(behaviorService.getActualStatus(anyString())).thenReturn(BotGroupStatus.STOPPED);

        scheduler(true, 6, 1, 2, 5).reconcileAll(T0);

        verify(behaviorService).startForRecovery("g-z");
        verify(behaviorService, never()).startForRecovery("g-a");
    }

    @Test
    @DisplayName("lastStoppedAt takes precedence over lastStartedAt, and a group with neither sorts last")
    void tieBreakPrefersLastStoppedAtAndSortsUnknownLast() {
        LocalDateTime base = LocalDateTime.of(2026, 9, 7, 9, 0);
        persistedDead(
                deadGroup("g-none"),          // no stamps at all → sorts last
                deadGroup("g-stopped").toBuilder()
                        .lastStoppedAt(base).lastStartedAt(base.plusHours(5)).build());
        envIsHealthy();
        when(behaviorService.startForRecovery(anyString())).thenAnswer(startsClearingMdc(false));
        lenient().when(behaviorService.getActualStatus(anyString())).thenReturn(BotGroupStatus.STOPPED);

        scheduler(true, 6, 1, 2, 5).reconcileAll(T0);

        verify(behaviorService).startForRecovery("g-stopped");
        verify(behaviorService, never()).startForRecovery("g-none");
    }

    /**
     * The budget can be spent by a <em>success</em>: an attempt that works still
     * charges the attempt (deliberate anti-flap — see {@code attempt()}), so a group
     * whose <em>final</em> budgeted attempt succeeds and which then re-dies inside
     * the settle window arrives at the next tick with the budget gone and
     * {@code recordFailure} never having run.
     * <p>
     * That used to leave it skipped forever on a DEBUG line — which, since
     * LOG_VOLUME_TIERING Phase 4, never reaches Loki — with no
     * {@code group_recovery_exhausted_total} and no ERROR, so Phase 4's
     * {@code EnvironmentGroupRecoveryExhausted} rule could not fire for the one case
     * AD-8's budget exists to bound. The hand-off is now reported from the skip
     * branch as well, still exactly once.
     */
    @Test
    @DisplayName("a success on the final attempt, then a re-death, still hands off exactly once (AD-8)")
    void successOnTheFinalAttemptThenReDeathStillReportsExhaustion() {
        persistedDead(deadGroup("g1"));
        envIsHealthy();
        when(behaviorService.startForRecovery("g1")).thenAnswer(startsClearingMdc(false, true));
        lenient().when(behaviorService.getActualStatus("g1")).thenReturn(BotGroupStatus.STOPPED);
        lenient().when(behaviorService.getRunningBotCountForGroup("g1")).thenReturn(20);
        // backoff 0 ⇒ due on every tick; budget 2 ⇒ attempt 2 is the last one.
        DeadGroupRecoveryScheduler scheduler = scheduler(true, 2, 1, 0);

        // Tick 1 fails, tick 2 succeeds (the final budgeted attempt), and the group
        // is still reported DEAD afterwards — it flapped straight back down.
        for (int i = 0; i < 8; i++) {
            scheduler.reconcileAll(T0.plusSeconds(i * 60L));
        }

        verify(behaviorService, times(2)).startForRecovery("g1");
        assertThat(attempts(DeadGroupRecoveryScheduler.OUTCOME_SUCCESS)).isEqualTo(1d);
        assertThat(lines(Level.ERROR))
                .as("one hand-off, however the budget was spent — six ticks later, still one")
                .hasSize(1);
        assertThat(lines(Level.ERROR).get(0))
                .contains("auto-recovery exhausted after 2 attempts — operator action required");
        assertThat(registry.find(BotMetrics.GROUP_RECOVERY_EXHAUSTED_TOTAL)
                .tags("botGroupId", "g1", "environmentId", "env-1", "product", "116")
                .counter().count())
                .as("the Phase 4 alert routes on this series, so it needs the tags too")
                .isEqualTo(1d);
    }

    @Test
    @DisplayName("a success charges the attempt budget — a flap inside the settle window cannot mint a new one")
    void aSuccessChargesTheBudget() {
        // The judgement call, asserted so it is a decision rather than an artefact:
        // refunding a successful attempt would restart the whole table on every flap,
        // which is the group-scale version of the reconnect hot loop AD-8 bounds.
        // Only a recovery that holds for settle-minutes earns a fresh budget, and
        // that is covered by budgetResetsAfterTheSettleWindow.
        persistedDead(deadGroup("g1"));
        envIsHealthy();
        when(behaviorService.startForRecovery("g1")).thenAnswer(startsClearingMdc(true));
        lenient().when(behaviorService.getRunningBotCountForGroup("g1")).thenReturn(20);
        DeadGroupRecoveryScheduler scheduler = scheduler(true, 6, 1, 0);

        scheduler.reconcileAll(T0);
        scheduler.reconcileAll(T0.plusSeconds(60));

        List<String> attemptLines = lines(Level.INFO).stream()
                .filter(l -> l.contains("auto-recovery attempt")).toList();
        assertThat(attemptLines).hasSize(2);
        assertThat(attemptLines.get(0)).contains("attempt 1/6");
        assertThat(attemptLines.get(1))
                .as("the second attempt is 2/6, not 1/6 — the success was charged")
                .contains("attempt 2/6");
    }

    /* ---------------- the series have to exist before they move (round-3 finding 1) ---------------- */

    /**
     * The counters an alert reads must be <b>materialised at zero</b> by the first
     * attempt, not registered lazily by their first increment.
     *
     * <p>This is a Prometheus fact rather than a tidiness one.
     * {@code EnvironmentGroupRecoveryExhausted} is
     * {@code increase(group_recovery_exhausted_total[15m]) > 0}, and
     * {@code group_recovery_exhausted_total} moves at most once per death episode. A
     * series registered at increment time is therefore absent, then {@code 1}, then
     * {@code 1} forever: {@code increase()} is {@code last - first == 0}, and the
     * counter-start extrapolation that would otherwise rescue it is gated on
     * {@code resultValue > 0}. The rule reads {@code 0} for ever and the hand-off —
     * the one signal that says "auto-recovery has given up, a human must act" — is
     * never delivered on a group's <em>first</em> exhaustion, which, with the budget
     * held in memory, is the only one that normally happens.
     * {@code EnvironmentGroupRecoveryFlapping}'s {@code >= 3} had the same defect one
     * degree milder: from a series first seen at {@code 1} it needed a fourth
     * self-heal.
     *
     * <p>So: one successful attempt, and all four series must already be on the
     * registry — three of them still at zero.
     */
    @Test
    @DisplayName("the first attempt materialises every group_recovery_* series at zero, so increase() can see the first increment")
    void everyRecoverySeriesIsMaterialisedAtZeroByTheFirstAttempt() {
        persistedDead(deadGroup("g1"));
        envIsHealthy();
        when(behaviorService.startForRecovery("g1")).thenAnswer(startsClearingMdc(true));
        when(behaviorService.getRunningBotCountForGroup("g1")).thenReturn(20);

        scheduler(true, 6, 1, 2, 5).reconcileAll(T0);

        io.micrometer.core.instrument.Counter exhausted =
                registry.find(BotMetrics.GROUP_RECOVERY_EXHAUSTED_TOTAL)
                        .tags("botGroupId", "g1", "environmentId", "env-1", "product", "116")
                        .counter();
        assertThat(exhausted)
                .as("group_recovery_exhausted_total must exist from the first attempt — a "
                        + "counter whose first scraped sample is 1 is invisible to increase()")
                .isNotNull();
        assertThat(exhausted.count()).isZero();

        // The outcomes that did not happen exist too, so the flapping rule's >= 3
        // counts from 0 rather than from the first success.
        assertThat(taggedAttempt(DeadGroupRecoveryScheduler.OUTCOME_FAILED)).isNotNull();
        assertThat(taggedAttempt(DeadGroupRecoveryScheduler.OUTCOME_FAILED).count()).isZero();
        assertThat(taggedAttempt(DeadGroupRecoveryScheduler.OUTCOME_ERROR)).isNotNull();
        assertThat(taggedAttempt(DeadGroupRecoveryScheduler.OUTCOME_ERROR).count()).isZero();
        assertThat(taggedAttempt(DeadGroupRecoveryScheduler.OUTCOME_SUCCESS).count()).isEqualTo(1d);
    }

    /**
     * The pre-registration is worthless if it lands on a different label set: two
     * series named {@code group_recovery_exhausted_total} differing in one tag are
     * two series to Prometheus, and the one the alert watches is still the one that
     * appears at {@code 1}. So the zero-valued series and the incremented one must
     * carry <b>identical</b> tags, not merely overlapping ones.
     */
    @Test
    @DisplayName("the pre-registered series carries exactly the tags the real increment carries")
    void thePreRegisteredSeriesSharesTheIncrementsTagSet() {
        persistedDead(deadGroup("g1"));
        envIsHealthy();
        when(behaviorService.startForRecovery("g1")).thenAnswer(startsClearingMdc(false));
        lenient().when(behaviorService.getActualStatus("g1")).thenReturn(BotGroupStatus.STOPPED);
        // budget 1 ⇒ the single attempt fails and exhausts, so the same series is
        // first pre-registered and then incremented.
        scheduler(true, 1, 1, 0).reconcileAll(T0);

        io.micrometer.core.instrument.Counter exhausted =
                registry.find(BotMetrics.GROUP_RECOVERY_EXHAUSTED_TOTAL).counter();
        io.micrometer.core.instrument.Counter succeeded =
                registry.find(BotMetrics.GROUP_RECOVERY_ATTEMPTS_TOTAL)
                        .tags("outcome", DeadGroupRecoveryScheduler.OUTCOME_SUCCESS).counter();

        assertThat(registry.find(BotMetrics.GROUP_RECOVERY_EXHAUSTED_TOTAL).counters())
                .as("one exhaustion series, not one pre-registered and one incremented")
                .hasSize(1);
        assertThat(exhausted.count()).isEqualTo(1d);
        // The success series was only ever pre-registered; it must be tagged like the
        // failure that really happened, minus the outcome.
        assertThat(succeeded.getId().getTags())
                .containsExactlyInAnyOrderElementsOf(
                        taggedAttempt(DeadGroupRecoveryScheduler.OUTCOME_FAILED).getId().getTags()
                                .stream()
                                .map(t -> "outcome".equals(t.getKey())
                                        ? io.micrometer.core.instrument.Tag.of("outcome",
                                                DeadGroupRecoveryScheduler.OUTCOME_SUCCESS)
                                        : t)
                                .toList());
        assertThat(exhausted.getId().getTags())
                .containsExactlyInAnyOrderElementsOf(
                        taggedAttempt(DeadGroupRecoveryScheduler.OUTCOME_FAILED).getId().getTags()
                                .stream().filter(t -> !"outcome".equals(t.getKey())).toList());
    }

    /* ---------------- one bad row must not disable the fleet (round-3 finding 3) ---------------- */

    /**
     * A candidate with no {@code environmentId} used to take the whole tick down —
     * every tick, for as long as the row existed.
     *
     * <p>{@code EnvironmentProbeScheduler.isHealthy} does {@code envUrls.get(id)} on a
     * {@code ConcurrentHashMap}, which throws on a null key, and the candidate loop had
     * no per-group try/catch: the NPE escaped to {@code reconcileQuietly}, which logged
     * one ERROR naming nothing and returned, so <b>no</b> group was attempted — the
     * healthy ones included. The row is reachable: {@code @NotBlank} on
     * {@code BotGroupDTO.environmentId} is {@code OnCreate}-only, and the sibling probe
     * scheduler already guards exactly this input.
     *
     * <p>The probe mock is stubbed to throw <em>because the real one does</em>; that is
     * pinned by {@code EnvironmentProbeSchedulerTest.isHealthyThrowsOnANullEnvironmentId},
     * so this stub cannot quietly stop modelling reality.
     */
    @Test
    @DisplayName("a candidate with no environmentId is skipped, and the rest of the tick still runs")
    void aCandidateWithNoEnvironmentDoesNotAbortTheTick() {
        BotGroup broken = deadGroup("g-broken").toBuilder().environmentId(null).build();
        persistedDead(broken, deadGroup("g-ok"));
        envIsHealthy();
        // lenient(): with the guard in place this stub is never reached, and that is
        // precisely the assertion. Remove the guard and it fires, taking the tick.
        lenient().when(probeScheduler.isHealthy(null)).thenThrow(
                new NullPointerException("ConcurrentHashMap forbids a null key"));
        when(behaviorService.startForRecovery("g-ok")).thenAnswer(startsClearingMdc(true));
        when(behaviorService.getRunningBotCountForGroup("g-ok")).thenReturn(20);

        scheduler(true, 6, 1, 2, 5).reconcileAll(T0);

        verify(behaviorService).startForRecovery("g-ok");
        verify(behaviorService, never()).startForRecovery("g-broken");
        assertThat(lines(Level.ERROR))
                .as("skipping a malformed row is not an error the operator must act on")
                .isEmpty();
    }

    /**
     * The general form of the same property: whatever a candidate's evaluation throws,
     * it costs that candidate and nothing else. The null environment is one instance;
     * the guard exists so the next one is not another fleet-wide outage.
     */
    @Test
    @DisplayName("an evaluation that throws for one group is isolated to that group")
    void anEvaluationThrowIsIsolatedToItsGroup() {
        persistedDead(deadGroup("g-boom"), deadGroup("g-ok").toBuilder()
                .environmentId("env-2").build());
        lenient().when(probeScheduler.isHealthy("env-1"))
                .thenThrow(new IllegalStateException("probe registry is mid-rebuild"));
        lenient().when(probeScheduler.isHealthy("env-2")).thenReturn(true);
        lenient().when(probeScheduler.healthyStreak("env-2")).thenReturn(2);
        lenient().when(environmentService.findById("env-2")).thenReturn(Environment.builder()
                .id("env-2").name("TIP staging").productCode(ProductCode.P_116).build());
        when(behaviorService.startForRecovery("g-ok")).thenAnswer(startsClearingMdc(true));
        when(behaviorService.getRunningBotCountForGroup("g-ok")).thenReturn(20);

        scheduler(true, 6, 1, 2, 5).reconcileAll(T0);

        verify(behaviorService).startForRecovery("g-ok");
        verify(behaviorService, never()).startForRecovery("g-boom");
        assertThat(lines(Level.ERROR))
                .as("the failing group is named, which the old tick-level ERROR never was")
                .anyMatch(l -> l.contains("skipping group g-boom"));
    }
}
