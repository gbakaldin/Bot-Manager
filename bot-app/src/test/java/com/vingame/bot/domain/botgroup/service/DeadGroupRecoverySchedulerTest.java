package com.vingame.bot.domain.botgroup.service;

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

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

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
        lenient().when(environmentService.findById("env-1")).thenReturn(Environment.builder()
                .id("env-1").name("TIP staging").productCode(ProductCode.P_116).build());
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
        when(behaviorService.startForRecovery("g1")).thenReturn(true);
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
        when(behaviorService.startForRecovery(anyString())).thenReturn(false);
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
        when(behaviorService.startForRecovery(anyString())).thenReturn(false);
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
        when(behaviorService.startForRecovery("g1")).thenReturn(false);
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
        when(behaviorService.startForRecovery("g1")).thenReturn(false);
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
        when(behaviorService.startForRecovery("g1")).thenReturn(true);
        when(behaviorService.getRunningBotCountForGroup("g1")).thenReturn(19);

        scheduler(true, 6, 1, 2, 5).reconcileAll(T0);

        assertThat(lines(Level.INFO)).hasSize(2);
        assertThat(lines(Level.INFO).get(0)).contains(
                "group g1 (group-g1): auto-recovery attempt 1/6",
                "Multiple bot disconnections detected");
        assertThat(lines(Level.INFO).get(1))
                .contains("auto-recovery succeeded — 19/20 bots up");

        // AD-13: group-scoped tags from MDC, exactly like group_dead_seconds_total.
        var counter = registry.find(BotMetrics.GROUP_RECOVERY_ATTEMPTS_TOTAL)
                .tags("outcome", "success", "botGroupId", "g1",
                        "environmentId", "env-1", "product", "116")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1d);
    }

    @Test
    @DisplayName("a thrown start is charged as outcome=error, logged with the stack trace, and backed off")
    void exceptionIsChargedAsError() {
        persistedDead(deadGroup("g1"));
        envIsHealthy();
        when(behaviorService.startForRecovery("g1"))
                .thenThrow(new IllegalStateException("auth gateway returned 503"));

        scheduler(true, 6, 1, 2, 5).reconcileAll(T0);

        assertThat(attempts(DeadGroupRecoveryScheduler.OUTCOME_ERROR)).isEqualTo(1d);
        LogEvent error = appender.events.stream()
                .filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow();
        assertThat(error.getMessage().getFormattedMessage())
                .contains("auto-recovery attempt 1/6 failed", "auth gateway returned 503");
        assertThat(error.getThrown()).isInstanceOf(IllegalStateException.class);
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
        when(behaviorService.startForRecovery("g1")).thenReturn(true);
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
}
