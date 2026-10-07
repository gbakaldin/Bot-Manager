package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.model.StartOrigin;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.infrastructure.observability.BotMdcTagsMeterFilter;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator;
import com.vingame.bot.infrastructure.observability.ScopedDebugEscalator;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import com.vingame.bot.infrastructure.runtime.BotGroupRuntime;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Restart-lifecycle tests for {@link BotGroupBehaviorService#restart(String)}.
 * <p>
 * Locks in two regressions:
 * <ol>
 *   <li>Restart must recreate bots after stop — symmetry between initial start and post-restart start.</li>
 *   <li>Restart must fail loudly when {@code start} produces zero bots while {@code botCount > 0}
 *       (RESTART_LIFECYCLE_FIX Architecture Decision 6). The original symptom — 18/18 bots auto-started
 *       cleanly, all 18 silently fail on restart — must surface as an exception, not a silent zero-bot
 *       runtime with {@code targetStatus=ACTIVE}.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotGroupBehaviorService - restart lifecycle")
class BotGroupBehaviorServiceRestartTest {

    @Mock
    private BotGroupService botGroupService;

    @Mock
    private EnvironmentService environmentService;

    @Mock
    private GameService gameService;

    @Mock
    private BotFactory botFactory;

    @Mock
    private BotMetrics botMetrics;

    @Mock
    private SessionAggregationService sessionAggregationService;

    @Mock
    private GroupLifecycleAggregator groupLifecycleAggregator;

    @Mock
    private ScopedDebugEscalator scopedDebugEscalator;

    @Mock
    private com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry gatewayBudgetRegistry;

    /** PLUGIN_HOT_RELOAD_3_4 D-15: answers null, so bots fall back to `builtin` as before. */
    @Mock
    private com.vingame.bot.common.plugin.PluginVersionResolver pluginVersionResolver;

    @InjectMocks
    private BotGroupBehaviorService service;

    @BeforeEach
    void initConfigFields() {
        // GATEWAY_REQUEST_BUDGET Phase 3: startLocked declares its demand up front
        // (reserve(ESSENTIAL, botCount x 3)) and the registry is mocked here, so without this an
        // unstubbed mock returns null and every start NPEs. UNLIMITED is the honest fixture
        // budget: it runs every call immediately and its reservation is a no-op, which is the
        // right shape for tests that are about the lifecycle rather than about pacing. The
        // reservation itself is asserted against a recording budget in
        // BotGroupBehaviorServiceReservationTest.
        //
        // lenient(), because most tests in this class never start a group and STRICT_STUBS would
        // fail them for an unnecessary stubbing.
        org.mockito.Mockito.lenient().when(gatewayBudgetRegistry.forEnvironment(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.vingame.bot.infrastructure.gateway.GatewayBudget.UNLIMITED);
        ReflectionTestUtils.setField(service, "deadBotGroupThreshold", 0.80);
        ReflectionTestUtils.setField(service, "botCreationParallelism", 10);
        ReflectionTestUtils.setField(service, "watchdogTimeoutSeconds", 180L);
        ReflectionTestUtils.setField(service, "periodicLogoutEnabled", false);
        ReflectionTestUtils.setField(service, "periodicLogoutIntervalMinutes", 60);
        ReflectionTestUtils.setField(service, "reconnectDelaySeconds", 5);
    }

    @AfterEach
    void shutdownExecutors() {
        try {
            service.shutdown();
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("startForRecovery refuses to build while another start is in flight (R7)")
    void startForRecovery_respectsAnAttemptAlreadyInFlight() {
        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(3).namePrefix("bot").password("pass")
                .targetStatus(BotGroupStatus.DEAD).build();
        when(botGroupService.findById("g-1")).thenReturn(group);

        BotGroupRuntime dead = new BotGroupRuntime("g-1", 3, "env-1");
        dead.markAsDead();
        runningGroups(service).put("g-1", dead);

        // Someone else's start is open — an operator /restart of the same DEAD group, which is
        // exactly what the exhaustion ERROR tells them to do.
        startAttempts(service).begin("g-1", StartOrigin.REST);

        try {
            boolean up = service.startForRecovery("g-1");

            assertThat(up)
                    .as("recovery has nothing to do while someone else is starting the group")
                    .isFalse();
            // The registry is keyed on group id, not on attempt identity, so a build here would
            // have credited its botUp/botFailed counts to the foreign attempt while recovery's
            // own failure went unrecorded.
            verify(botFactory, never()).createBot(anyString(), any(BotConfiguration.class));
            verify(botGroupService, never()).save(any(BotGroup.class));
            assertThat(runningGroups(service).get("g-1")).isSameAs(dead);
        } finally {
            startAttempts(service).finish("g-1", null);
            dead.getExecutor().shutdownNow();
            runningGroups(service).remove("g-1");
        }
    }

    @Test
    @DisplayName("stopAndLogout takes the group lock, so a DELETE cannot interleave with a build (R6)")
    void stopAndLogout_takesTheGroupLock() throws Exception {
        // The defect this pins is not observable from stopAndLogout's own result: it is that a
        // teardown running CONCURRENTLY with a build made the build's finally see a null runtime
        // (so stopAllBots never ran and every bot authenticated after the remove leaked its
        // client, scheduler and threads for the life of the JVM), and that a build winning the
        // race to its ACTIVE persist re-inserted the document deleteById had just removed. What
        // makes both impossible is taking the lock, so that is what is asserted.
        ReentrantLock lock = new ReentrantLock();
        groupLocks(service).put("g-1", lock);
        BotGroupRuntime runtime = new BotGroupRuntime("g-1", 1, "env-1");
        runtime.setActualStatus(BotGroupStatus.ACTIVE);
        runningGroups(service).put("g-1", runtime);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                locked.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "test-lock-holder");
        holder.start();
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CountDownLatch done = new CountDownLatch(1);
        Thread deleter = new Thread(() -> {
            service.stopAndLogout("g-1");
            done.countDown();
        }, "test-deleter");
        deleter.start();

        try {
            assertThat(done.await(300, TimeUnit.MILLISECONDS))
                    .as("stopAndLogout must wait for the build that holds this group's lock; "
                            + "without the lock it tears the runtime down underneath it")
                    .isFalse();
        } finally {
            release.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertThat(done.await(10, TimeUnit.SECONDS))
                .as("and it proceeds as soon as the lock is free")
                .isTrue();
        assertThat(runningGroups(service)).doesNotContainKey("g-1");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ReentrantLock> groupLocks(BotGroupBehaviorService svc) {
        try {
            Field f = BotGroupBehaviorService.class.getDeclaredField("groupLocks");
            f.setAccessible(true);
            return (Map<String, ReentrantLock>) f.get(svc);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StartAttemptRegistry startAttempts(BotGroupBehaviorService svc) {
        try {
            Field f = BotGroupBehaviorService.class.getDeclaredField("startAttempts");
            f.setAccessible(true);
            return (StartAttemptRegistry) f.get(svc);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Bot stubBot(String username) {
        Bot b = mock(Bot.class);
        lenient().when(b.getUserName()).thenReturn(username);
        lenient().when(b.getTotalBetsPlaced()).thenReturn(new AtomicLong(0));
        lenient().when(b.getTotalBetAmount()).thenReturn(new AtomicLong(0));
        return b;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, BotGroupRuntime> runningGroups(BotGroupBehaviorService svc) {
        try {
            Field f = BotGroupBehaviorService.class.getDeclaredField("runningGroups");
            f.setAccessible(true);
            return (Map<String, BotGroupRuntime>) f.get(svc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("restart() recreates bots after stop — second start invokes the factory again for every bot")
    void restart_recreatesBotsAfterStop() {
        BotGroup group = BotGroup.builder()
                .id("g-1")
                .name("Group")
                .environmentId("env-1")
                .gameId("game-1")
                .botCount(3)
                .namePrefix("bot")
                .password("pass")
                .build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        // Each createBot call returns a fresh mock bot — total 6 calls over start + restart's start.
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        // First start
        service.start("g-1");
        verify(botFactory, times(3)).createBot(anyString(), any(BotConfiguration.class));

        // Restart → stop, then start again. Second start should drive 3 more factory calls.
        service.restart("g-1");
        verify(botFactory, atLeast(6)).createBot(anyString(), any(BotConfiguration.class));
    }

    @Test
    @DisplayName("restart() throws IllegalStateException when zero bots are produced for a non-zero botCount")
    void restart_failsLoudlyWhenZeroBotsCreated() {
        BotGroup group = BotGroup.builder()
                .id("g-1")
                .name("Group")
                .environmentId("env-1")
                .gameId("game-1")
                .botCount(3)
                .namePrefix("bot")
                .password("pass")
                .build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);

        // First start succeeds (3 bots). Then on restart, every createBot throws.
        AtomicLong callCount = new AtomicLong(0);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> {
                    long n = callCount.incrementAndGet();
                    if (n <= 3) {
                        return stubBot("bot-" + n);
                    }
                    throw new RuntimeException("auth failed for bot " + n);
                });

        service.start("g-1");

        // restart() must throw — silent zero-bot completion is the bug we are fixing.
        assertThatThrownBy(() -> service.restart("g-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0/3");
    }

    /* ----- Per-failure metric + log assertions (Architecture Decisions 5) ----- */

    @Test
    @DisplayName("createBotsInParallel increments bot_creation_failures_total with reason=\"validation\" for IllegalStateException")
    void start_classifiesIllegalStateExceptionAsValidationReason() {
        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(2).namePrefix("bot").password("pass").build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenThrow(new IllegalStateException("resolved zoneName is null/blank"));

        service.start("g-1");

        // Both bots failed with IllegalStateException → both must be classified as "validation".
        verify(botMetrics, times(2)).incBotCreationFailure(eq("validation"));
    }

    @Test
    @DisplayName("createBotsInParallel increments bot_creation_failures_total with reason=\"auth\" for auth-flavoured exceptions")
    void start_classifiesAuthMessageAsAuthReason() {
        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(1).namePrefix("bot").password("pass").build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        // Message contains "auth" — classifier route via message-substring match.
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenThrow(new RuntimeException("auth failed: upstream 401"));

        service.start("g-1");

        verify(botMetrics).incBotCreationFailure(eq("auth"));
    }

    @Test
    @DisplayName("createBotsInParallel classifies BadRequestException as reason=\"validation\" (API_ERROR_FORWARDING new arm)")
    void start_classifiesBadRequestExceptionAsValidationReason() {
        // API_ERROR_FORWARDING Phase B introduced an explicit
        // BadRequestException → "validation" arm in classifyCreationFailure.
        // The arm sits above the message-substring heuristic, so even if the
        // exception's message contains "auth" it must still resolve to
        // "validation".
        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(1).namePrefix("bot").password("pass").build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        // Message intentionally contains "auth" — the type-based arm must win
        // over the message-substring fallback.
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenThrow(new com.vingame.bot.common.exception.BadRequestException(
                        "username too long for product P_116 (auth gateway cap)"));

        service.start("g-1");

        verify(botMetrics).incBotCreationFailure(eq("validation"));
    }

    @Test
    @DisplayName("createBotsInParallel classifies UpstreamLoginException as reason=\"auth\" (API_ERROR_FORWARDING new arm)")
    void start_classifiesUpstreamLoginExceptionAsAuthReason() {
        // API_ERROR_FORWARDING Phase B added an explicit
        // UpstreamLoginException → "auth" arm. Type-based classification
        // means future library upgrades that change the exception message
        // wording (the websocket-parser's "No data in response" carry) don't
        // silently drift this counter into "unknown".
        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(1).namePrefix("bot").password("pass").build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        // Message intentionally lacks the auth/login/token keywords so the
        // type-based arm is the only path that resolves "auth".
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenThrow(new com.vingame.bot.common.exception.UpstreamLoginException(
                        "No data in response"));

        service.start("g-1");

        verify(botMetrics).incBotCreationFailure(eq("auth"));
    }

    @Test
    @DisplayName("createBotsInParallel classifies a GatewayBudgetException as reason=\"budget\" (GATEWAY_REQUEST_BUDGET AD-9)")
    void start_classifiesGatewayBudgetExceptionAsBudgetReason() {
        // A new bounded label value, and the arm has to sit ABOVE the message-substring
        // heuristic: "budget" means this JVM chose not to send the request, so the gateway was
        // never asked. Counting it as "auth" would put our own pacing decision in the same
        // bucket as a rejected credential and make EnvironmentLoginFailing fire on throttling
        // we did deliberately. The arm's position matters because these messages can carry the
        // substring the heuristic matches on ("token") — the typed check has to win.
        //
        // Inert in Phase 1 (observe mode never refuses), which is precisely why it needs a test:
        // there is no staging signal that would reveal it wired to the wrong label.
        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(1).namePrefix("bot").password("pass").build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenThrow(new com.vingame.bot.common.exception.GatewayBudgetExhaustedException(
                        com.vingame.bot.common.gateway.RequestTier.ESSENTIAL, "env-1",
                        java.time.Duration.ofSeconds(12)));

        service.start("g-1");

        verify(botMetrics).incBotCreationFailure(eq("budget"));
    }

    @Test
    @DisplayName("createBotsInParallel increments bot_creation_failures_total with reason=\"unknown\" for non-classified exceptions")
    void start_classifiesGenericRuntimeAsUnknownReason() {
        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(1).namePrefix("bot").password("pass").build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        // Neither class name nor message hits the auth heuristic.
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenThrow(new RuntimeException("network unreachable"));

        service.start("g-1");

        verify(botMetrics).incBotCreationFailure(eq("unknown"));
    }

    @Test
    @DisplayName("Per-bot failure is logged at ERROR with bot index, group id, env id, and the cause class/message")
    void createBotsInParallel_logsErrorWithFullContextOnEveryFailure() {
        BotGroup group = BotGroup.builder()
                .id("group-9").name("Group").environmentId("env-9").gameId("game-1")
                .botCount(1).namePrefix("bot").password("pass").build();
        Environment env = Environment.builder().id("env-9").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("group-9")).thenReturn(group);
        when(environmentService.findById("env-9")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenThrow(new RuntimeException("upstream 500 from auth gateway"));

        // Attach an in-memory log4j2 appender to BotGroupBehaviorService's logger.
        CapturingAppender appender = new CapturingAppender("CapturingAppender-restart-test");
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        String loggerName = BotGroupBehaviorService.class.getName();
        LoggerConfig loggerConfig = ctx.getConfiguration().getLoggerConfig(loggerName);
        Level prev = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.ALL);
        ctx.updateLoggers();
        try {
            service.start("group-9");
        } finally {
            loggerConfig.removeAppender(appender.getName());
            loggerConfig.setLevel(prev);
            ctx.updateLoggers();
            appender.stop();
        }

        List<LogEvent> errors = appender.events().stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .toList();
        assertThat(errors).as("expected at least one ERROR log line for bot-creation failure").isNotEmpty();

        boolean matched = errors.stream().anyMatch(e -> {
            String fm = e.getMessage().getFormattedMessage();
            return fm.contains("Failed to create bot")
                    && fm.contains("group-9")
                    && fm.contains("env-9")
                    && fm.contains("upstream 500 from auth gateway");
        });
        assertThat(matched)
                .as("expected ERROR line containing group id, env id, and root cause message; saw: "
                        + errors.stream().map(e -> e.getMessage().getFormattedMessage()).toList())
                .isTrue();
    }

    @Test
    @DisplayName("bot_creation_failures_total carries botGroupId + environmentId tags " +
            "(MDC must be set on the result-collection loop's caller thread, not just the worker thread)")
    void incBotCreationFailure_seriesIsTaggedWithBotGroupIdAndEnvironmentId() {
        // Reviewer finding: createBotsInParallel's result-collection loop runs on
        // the caller thread of start(), not on the per-bot virtual thread where
        // MDC was set inside the lambda and cleared in finally. Without an
        // explicit setGroupContext on the caller thread, the counter is
        // registered without botGroupId/environmentId tags — defeating the
        // per-group cardinality goal of Architecture Decision 5.
        //
        // This test wires a real MeterRegistry + BotMetrics through the service
        // so we can assert on the actual registered series, not just on a mock
        // invocation of incBotCreationFailure(reason).
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new BotMdcTagsMeterFilter());
        BotMetrics realMetrics = new BotMetrics(registry);

        BotGroupBehaviorService realMetricsService = new BotGroupBehaviorService(
                botGroupService, environmentService, gameService, botFactory, realMetrics,
                sessionAggregationService, groupLifecycleAggregator, scopedDebugEscalator,
                gatewayBudgetRegistry, pluginVersionResolver);
        ReflectionTestUtils.setField(realMetricsService, "deadBotGroupThreshold", 0.80);
        ReflectionTestUtils.setField(realMetricsService, "botCreationParallelism", 10);
        ReflectionTestUtils.setField(realMetricsService, "watchdogTimeoutSeconds", 180L);
        ReflectionTestUtils.setField(realMetricsService, "periodicLogoutEnabled", false);
        ReflectionTestUtils.setField(realMetricsService, "periodicLogoutIntervalMinutes", 60);
        ReflectionTestUtils.setField(realMetricsService, "reconnectDelaySeconds", 5);

        BotGroup group = BotGroup.builder()
                .id("group-tagged").name("Group").environmentId("env-tagged").gameId("game-1")
                .botCount(2).namePrefix("bot").password("pass").build();
        Environment env = Environment.builder().id("env-tagged").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("group-tagged")).thenReturn(group);
        when(environmentService.findById("env-tagged")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenThrow(new RuntimeException("network unreachable"));

        try {
            realMetricsService.start("group-tagged");

            Counter tagged = registry.find(BotMetrics.BOT_CREATION_FAILURES_TOTAL)
                    .tag("reason", "unknown")
                    .tag(BotMdc.BOT_GROUP_ID, "group-tagged")
                    .tag(BotMdc.ENVIRONMENT_ID, "env-tagged")
                    .counter();
            assertThat(tagged)
                    .as("bot_creation_failures_total must carry botGroupId + environmentId " +
                            "tags so Prometheus can slice by group/env (Architecture Decision 5)")
                    .isNotNull();
            assertThat(tagged.count()).isEqualTo(2.0);

            // Defense-in-depth: assert no series with the same name+reason was registered
            // *without* the botGroupId tag. If the MDC wasn't set on the caller thread,
            // we would see exactly that — an untagged "reason=unknown" series.
            long untagged = registry.find(BotMetrics.BOT_CREATION_FAILURES_TOTAL)
                    .tag("reason", "unknown")
                    .counters()
                    .stream()
                    .filter(c -> c.getId().getTags().stream()
                            .noneMatch(t -> BotMdc.BOT_GROUP_ID.equals(t.getKey())))
                    .count();
            assertThat(untagged)
                    .as("no untagged bot_creation_failures_total series should exist — " +
                            "the result-collection loop must set MDC on the caller thread")
                    .isZero();
        } finally {
            try {
                realMetricsService.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("start() failure log carries the cause type and message (API_ERROR_FORWARDING reviewer fix)")
    void start_failureLogCarriesCauseTypeAndMessage() {
        // API_ERROR_FORWARDING reviewer finding: Phase B's refactor swapped
        // the outer catch(Exception) for try/finally + boolean started,
        // losing the `e` reference in the failure log line. The fix
        // captures the in-flight exception into a Throwable variable so
        // operators grepping for "Failed to start bot group" see the cause
        // inline — critical for the auto-start path (PostConstruct) where
        // no RestExceptionHandler logs the exception elsewhere.
        BotGroup group = BotGroup.builder()
                .id("group-failurelog").name("Group-failurelog")
                .environmentId("env-failurelog").gameId("game-1")
                .botCount(1).namePrefix("bot").password("pass").build();

        when(botGroupService.findById("group-failurelog")).thenReturn(group);
        // Force the overall failure by making environmentService throw — that
        // bubbles straight out of the try-block with a distinctive type and
        // message so we can assert the failure-log carries them.
        RuntimeException upstreamFailure = new RuntimeException(
                "auth gateway returned 503 — circuit breaker open");
        when(environmentService.findById("env-failurelog")).thenThrow(upstreamFailure);

        CapturingAppender appender = new CapturingAppender(
                "CapturingAppender-failure-log");
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        String loggerName = BotGroupBehaviorService.class.getName();
        LoggerConfig loggerConfig = ctx.getConfiguration().getLoggerConfig(loggerName);
        Level prev = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.ALL);
        ctx.updateLoggers();
        try {
            assertThatThrownBy(() -> service.start("group-failurelog"))
                    .isSameAs(upstreamFailure);
        } finally {
            loggerConfig.removeAppender(appender.getName());
            loggerConfig.setLevel(prev);
            ctx.updateLoggers();
            appender.stop();
        }

        List<LogEvent> errors = appender.events().stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .filter(e -> e.getMessage().getFormattedMessage()
                        .contains("Failed to start bot group"))
                .toList();

        assertThat(errors)
                .as("expected an ERROR log line for the start() failure")
                .isNotEmpty();

        LogEvent failureLog = errors.get(0);
        String formatted = failureLog.getMessage().getFormattedMessage();
        assertThat(formatted)
                .as("failure log must carry the group name")
                .contains("Group-failurelog");
        assertThat(formatted)
                .as("failure log must carry the cause type")
                .contains("RuntimeException");
        assertThat(formatted)
                .as("failure log must carry the cause message")
                .contains("auth gateway returned 503");
        assertThat(failureLog.getThrown())
                .as("failure log must attach the throwable so SLF4J can render the stacktrace")
                .isSameAs(upstreamFailure);
    }

    /* ----- DEAD_GROUP_RESTART: reclaim-on-DEAD in start() (AD-1..AD-5) ----- */

    @Test
    @DisplayName("start() on a group whose runtime is DEAD reclaims the old runtime and rebuilds — " +
            "NOT a no-op (primary regression: fails on pre-fix code)")
    void start_reclaimsAndRebuildsWhenRuntimeIsDead() {
        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(3).namePrefix("bot").password("pass").build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        // Seed a lingering DEAD runtime, exactly as the health-monitor death path
        // or the zero-bot start path leaves it in runningGroups.
        BotGroupRuntime dead = new BotGroupRuntime("g-1", 3, "env-1");
        dead.markAsDead();
        runningGroups(service).put("g-1", dead);

        try {
            service.start("g-1");

            // Rebuild happened: the factory was invoked 3 times (a no-op would be 0).
            verify(botFactory, times(3)).createBot(anyString(), any(BotConfiguration.class));

            // The map now holds a NEW runtime (the DEAD one was reclaimed/replaced),
            // and it is ACTIVE.
            BotGroupRuntime rebuilt = runningGroups(service).get("g-1");
            assertThat(rebuilt).as("rebuilt runtime is a fresh instance").isNotSameAs(dead);
            assertThat(rebuilt.getActualStatus()).isEqualTo(BotGroupStatus.ACTIVE);

            // targetStatus flipped DEAD → ACTIVE and persisted.
            ArgumentCaptor<BotGroup> saved = ArgumentCaptor.forClass(BotGroup.class);
            verify(botGroupService).save(saved.capture());
            assertThat(saved.getValue().getTargetStatus()).isEqualTo(BotGroupStatus.ACTIVE);
        } finally {
            service.stop("g-1");
        }
    }

    @Test
    @DisplayName("start() is a no-op when the existing runtime is ACTIVE — mirrors shouldNoOpWhenAlreadyRunning (AD-2)")
    void start_isNoOpWhenRuntimeIsActive() {
        // Default-constructed runtime is ACTIVE (BotGroupRuntime constructor).
        BotGroupRuntime active = new BotGroupRuntime("g-1", 0, "env-1");
        runningGroups(service).put("g-1", active);
        try {
            service.start("g-1");

            // Early no-op: findById never reached, factory never invoked, same instance retained.
            verify(botGroupService, never()).findById(anyString());
            verify(botFactory, never()).createBot(anyString(), any(BotConfiguration.class));
            assertThat(runningGroups(service).get("g-1")).isSameAs(active);
        } finally {
            active.getExecutor().shutdownNow();
            runningGroups(service).remove("g-1");
        }
    }

    @Test
    @DisplayName("reclaim credits the open group-DEAD window exactly once and clears the stamp (AD-4)")
    void start_reclaimCreditsGroupDeadSecondsExactlyOnce() {
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new BotMdcTagsMeterFilter());
        BotMetrics realMetrics = new BotMetrics(registry);

        BotGroupBehaviorService svc = new BotGroupBehaviorService(
                botGroupService, environmentService, gameService, botFactory, realMetrics,
                sessionAggregationService, groupLifecycleAggregator, scopedDebugEscalator,
                gatewayBudgetRegistry, pluginVersionResolver);
        ReflectionTestUtils.setField(svc, "deadBotGroupThreshold", 0.80);
        ReflectionTestUtils.setField(svc, "botCreationParallelism", 10);
        ReflectionTestUtils.setField(svc, "watchdogTimeoutSeconds", 180L);
        ReflectionTestUtils.setField(svc, "periodicLogoutEnabled", false);
        ReflectionTestUtils.setField(svc, "periodicLogoutIntervalMinutes", 60);
        ReflectionTestUtils.setField(svc, "reconnectDelaySeconds", 5);

        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(3).namePrefix("bot").password("pass").build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        BotGroupRuntime dead = new BotGroupRuntime("g-1", 3, "env-1");
        dead.markAsDead();
        // Backdate the DEAD window so the credited elapsed seconds are > 0.
        ReflectionTestUtils.setField(dead, "groupDeadSince", Instant.now().minusSeconds(30));
        runningGroups(svc).put("g-1", dead);

        try {
            svc.start("g-1");

            // The open DEAD window was credited exactly once (a single positive series).
            double total = registry.find(BotMetrics.GROUP_DEAD_SECONDS_TOTAL).counters()
                    .stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
            assertThat(total)
                    .as("group_dead_seconds_total credited once for the reclaimed window")
                    .isGreaterThan(0.0);

            // The old runtime's stamp was cleared — no window left open to be
            // credited again (no double-credit, no leak).
            assertThat(dead.getGroupDeadSince())
                    .as("reclaim cleared groupDeadSince on the old runtime")
                    .isNull();
            // The rebuilt runtime starts with a fresh (null) window.
            assertThat(runningGroups(svc).get("g-1").getGroupDeadSince()).isNull();
        } finally {
            svc.stop("g-1");
            try {
                svc.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("reclaim shuts the old runtime's health monitor + logout scheduler — no orphaned threads (AD-6 / thread-leak trap)")
    void start_reclaimShutsOldMonitorAndLogoutScheduler() {
        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(3).namePrefix("bot").password("pass").build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        // Seed a DEAD runtime that still owns live schedulers — the exact
        // thread-leak the reclaim must recover.
        BotGroupRuntime dead = new BotGroupRuntime("g-1", 3, "env-1");
        dead.markAsDead();
        ScheduledExecutorService oldMonitor = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService oldLogout = Executors.newSingleThreadScheduledExecutor();
        dead.setHealthMonitor(oldMonitor);
        dead.setLogoutScheduler(oldLogout);
        runningGroups(service).put("g-1", dead);

        try {
            service.start("g-1");

            assertThat(oldMonitor.isShutdown())
                    .as("old health monitor shut by reclaim").isTrue();
            assertThat(oldLogout.isShutdown())
                    .as("old logout scheduler shut by reclaim").isTrue();
        } finally {
            oldMonitor.shutdownNow();
            oldLogout.shutdownNow();
            service.stop("g-1");
        }
    }

    @Test
    @DisplayName("reclaim rebuilds by re-authenticating existing accounts — NO registration / deposit (AD-8)")
    void start_reclaimDoesNotRegisterOrDeposit() {
        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(3).namePrefix("bot").password("pass").build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        BotGroupRuntime dead = new BotGroupRuntime("g-1", 3, "env-1");
        dead.markAsDead();
        runningGroups(service).put("g-1", dead);

        // Capture the rebuild's logs so we can assert NO registration/deposit line
        // is emitted on the start path (belt-and-suspenders for AD-8).
        CapturingAppender appender = new CapturingAppender("CapturingAppender-noregister");
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        String loggerName = BotGroupBehaviorService.class.getName();
        LoggerConfig loggerConfig = ctx.getConfiguration().getLoggerConfig(loggerName);
        Level prev = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.ALL);
        ctx.updateLoggers();
        try {
            service.start("g-1");

            // The only account-facing collaborator invoked is botFactory.createBot
            // (the re-authentication path) — once per existing account.
            verify(botFactory, times(3)).createBot(anyString(), any(BotConfiguration.class));

            // No registration/deposit log line on the start path.
            boolean sawRegistrationOrDeposit = appender.events().stream()
                    .map(e -> e.getMessage().getFormattedMessage().toLowerCase())
                    .anyMatch(m -> m.contains("registr") || m.contains("deposit"));
            assertThat(sawRegistrationOrDeposit)
                    .as("start()/reclaim must not register or deposit — re-auth only")
                    .isFalse();
        } finally {
            loggerConfig.removeAppender(appender.getName());
            loggerConfig.setLevel(prev);
            ctx.updateLoggers();
            appender.stop();
            service.stop("g-1");
        }
    }

    @Test
    @DisplayName("two rapid start() calls on a DEAD group do not double-build — the per-group lock serializes reclaim + build (AD-5)")
    void start_concurrentStartsDoNotDoubleBuild() throws Exception {
        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(3).namePrefix("bot").password("pass").build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        lenient().when(botGroupService.findById("g-1")).thenReturn(group);
        lenient().when(environmentService.findById("env-1")).thenReturn(env);
        lenient().when(gameService.findById("game-1")).thenReturn(game);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        BotGroupRuntime dead = new BotGroupRuntime("g-1", 3, "env-1");
        dead.markAsDead();
        runningGroups(service).put("g-1", dead);

        try {
            Runnable startTask = () -> service.start("g-1");
            Thread t1 = new Thread(startTask);
            Thread t2 = new Thread(startTask);
            t1.start();
            t2.start();
            t1.join();
            t2.join();

            // Exactly one rebuild: the winning start reclaimed + built 3 bots; the
            // loser observed the fresh ACTIVE runtime and no-opped. If the lock were
            // missing, both could build → 6 createBot calls and a leaked runtime.
            verify(botFactory, times(3)).createBot(anyString(), any(BotConfiguration.class));
            assertThat(runningGroups(service).get("g-1").getActualStatus())
                    .isEqualTo(BotGroupStatus.ACTIVE);
        } finally {
            service.stop("g-1");
        }
    }

    @Test
    @DisplayName("stop() still tears down the runtime and persists STOPPED after the teardownRuntimeMemory refactor (AD-3 behavior-preservation)")
    void stop_tearsDownRuntimeAndPersistsStopped() {
        // AD-3 extracted stop()'s inline stopAllBots+evictGroup+remove into the
        // shared teardownRuntimeMemory helper and wrapped the method in the
        // per-group lock. This pins that the observable stop() contract is
        // unchanged: the runtime leaves runningGroups, session state is evicted,
        // and the entity is saved with targetStatus=STOPPED + a lastStoppedAt
        // stamp. No direct stop() unit test existed before this fix.
        BotGroup group = BotGroup.builder()
                .id("g-stop").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(0).namePrefix("bot").password("pass").build();
        when(botGroupService.findById("g-stop")).thenReturn(group);

        BotGroupRuntime active = new BotGroupRuntime("g-stop", 0, "env-1");
        runningGroups(service).put("g-stop", active);

        service.stop("g-stop");

        // Runtime dropped from the map and its executor shut down.
        assertThat(runningGroups(service).get("g-stop"))
                .as("stop() removes the runtime from runningGroups").isNull();
        assertThat(active.getExecutor().isShutdown())
                .as("stop() shuts the runtime's executor via teardownRuntimeMemory").isTrue();
        // Aggregated session state evicted for the group.
        verify(sessionAggregationService).evictGroup("g-stop");
        // Entity persisted STOPPED with a lastStoppedAt stamp.
        ArgumentCaptor<BotGroup> saved = ArgumentCaptor.forClass(BotGroup.class);
        verify(botGroupService).save(saved.capture());
        assertThat(saved.getValue().getTargetStatus()).isEqualTo(BotGroupStatus.STOPPED);
        assertThat(saved.getValue().getLastStoppedAt())
                .as("stop() stamps lastStoppedAt").isNotNull();
    }

    // ------------------------------------------------------------------
    // DEAD_GROUP_AUTO_RECOVERY Phase 2 — stop() on a runtime-less group.
    //
    // Before this phase, stop() early-returned with a bare WARN whenever
    // runningGroups held no runtime, persisting nothing. A group that died and
    // then outlived its runtime (the ordinary shape after an app restart, since
    // onStartup rebuilds only targetStatus=ACTIVE groups) was therefore stuck at
    // targetStatus=DEAD forever: POST /stop was a no-op on it. AD-5 makes STOPPED
    // the only opt-out from auto-recovery, so an unreachable STOPPED is an
    // unusable opt-out — hence these two tests guard a prerequisite, not a
    // cosmetic fix.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("stop() persists STOPPED for a runtime-less DEAD group (AD-5 opt-out prerequisite)")
    void stop_persistsStoppedWhenNoRuntimeAndDead() {
        BotGroup group = BotGroup.builder()
                .id("g-noruntime").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(3).namePrefix("bot").password("pass")
                .targetStatus(BotGroupStatus.DEAD).build();
        when(botGroupService.findById("g-noruntime")).thenReturn(group);

        // Deliberately no entry in runningGroups: this is a group that died before
        // the current JVM started.
        assertThat(runningGroups(service).get("g-noruntime")).isNull();

        List<LogEvent> events = captureBehaviorServiceLogs(() -> service.stop("g-noruntime"));

        ArgumentCaptor<BotGroup> saved = ArgumentCaptor.forClass(BotGroup.class);
        verify(botGroupService).save(saved.capture());
        assertThat(saved.getValue().getTargetStatus())
                .as("a runtime-less stop() must still park the group STOPPED")
                .isEqualTo(BotGroupStatus.STOPPED);
        assertThat(saved.getValue().getLastStoppedAt())
                .as("a runtime-less stop() stamps lastStoppedAt like the normal path")
                .isNotNull();

        // Nothing was torn down, because there was nothing to tear down. If this
        // ever starts evicting, the runtime-less branch has grown a side effect it
        // has no runtime to justify.
        verify(sessionAggregationService, never()).evictGroup(anyString());
        verify(groupLifecycleAggregator, never()).evictGroup(anyString());

        assertThat(formattedAt(events, Level.INFO))
                .as("the runtime-less park is announced at INFO, naming the prior status")
                .anyMatch(m -> m.contains("Bot group g-noruntime has no runtime")
                        && m.contains("persisting STOPPED")
                        && m.contains("DEAD"));
    }

    @Test
    @DisplayName("stop() on a runtime-less group that is already STOPPED writes nothing and keeps the WARN")
    void stop_isNoOpWhenNoRuntimeAndAlreadyStopped() {
        BotGroup group = BotGroup.builder()
                .id("g-parked").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(3).namePrefix("bot").password("pass")
                .targetStatus(BotGroupStatus.STOPPED).build();
        when(botGroupService.findById("g-parked")).thenReturn(group);

        List<LogEvent> events = captureBehaviorServiceLogs(() -> service.stop("g-parked"));

        // Idempotent: no second write, so a repeated /stop cannot keep re-stamping
        // lastStoppedAt and rewriting an unchanged document.
        verify(botGroupService, never()).save(any(BotGroup.class));
        assertThat(formattedAt(events, Level.WARN))
                .as("the historical WARN is preserved for the already-parked case")
                .anyMatch(m -> m.contains("Bot group g-parked is not running"));
        assertThat(formattedAt(events, Level.INFO))
                .as("no STOPPED-persisted line when nothing was persisted")
                .noneMatch(m -> m.contains("persisting STOPPED"));
    }

    @Test
    @DisplayName("stop() with a live runtime is unchanged by Phase 2 — teardown path, one findById, no runtime-less line")
    void stop_withRuntimeIsUnchangedByTheRuntimeLessBranch() {
        BotGroup group = BotGroup.builder()
                .id("g-live").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(0).namePrefix("bot").password("pass")
                .targetStatus(BotGroupStatus.ACTIVE).build();
        when(botGroupService.findById("g-live")).thenReturn(group);

        BotGroupRuntime active = new BotGroupRuntime("g-live", 0, "env-1");
        runningGroups(service).put("g-live", active);

        List<LogEvent> events = captureBehaviorServiceLogs(() -> service.stop("g-live"));

        // The with-runtime path still loads the group exactly once (after teardown)
        // — the new branch must not add a second read for a group that has a runtime.
        verify(botGroupService, times(1)).findById("g-live");
        verify(sessionAggregationService).evictGroup("g-live");
        assertThat(runningGroups(service).get("g-live")).isNull();

        List<String> infos = formattedAt(events, Level.INFO);
        assertThat(infos)
                .as("the with-runtime path keeps its original success line")
                .anyMatch(m -> m.contains("Bot group g-live stopped successfully"));
        assertThat(infos)
                .as("the with-runtime path must never take the runtime-less branch")
                .noneMatch(m -> m.contains("has no runtime"));
    }

    /* ----- DEAD_GROUP_AUTO_RECOVERY: a failed restart must not opt a group out ----- */

    /**
     * Phase 2 made {@code stop()} persist {@code STOPPED} for a runtime-less group,
     * and AD-5 made {@code STOPPED} a permanent opt-out from auto-recovery. Since
     * {@code restart} is {@code stop} then {@code start}, a {@code /restart} whose
     * start half throws would have stranded the group at {@code STOPPED} where it
     * used to be left {@code DEAD} — silently disabling recovery for it, removing it
     * from {@code findByTargetStatus(DEAD)}, and doing so on exactly the endpoint the
     * exhaustion ERROR tells the operator to press.
     */
    @Test
    @DisplayName("a failed restart of a runtime-less DEAD group never parks it STOPPED (AD-5)")
    void restart_doesNotParkARuntimelessDeadGroupAsStopped() {
        BotGroup group = BotGroup.builder()
                .id("g-dead").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(3).namePrefix("bot").password("pass")
                .targetStatus(BotGroupStatus.DEAD).build();
        when(botGroupService.findById("g-dead")).thenReturn(group);
        // The start half fails the way it does when the gateway is still sick.
        when(environmentService.findById("env-1"))
                .thenThrow(new IllegalStateException("auth gateway returned 503"));

        assertThatThrownBy(() -> service.restart("g-dead"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(group.getTargetStatus())
                .as("the group stays DEAD, so auto-recovery can still see it")
                .isEqualTo(BotGroupStatus.DEAD);
        verify(botGroupService, never()).save(any(BotGroup.class));
    }

    @Test
    @DisplayName("a failed restart restores the status its own stop overwrote — no silent opt-out (AD-5)")
    void restart_restoresThePriorStatusWhenTheStartHalfFails() {
        BotGroup group = BotGroup.builder()
                .id("g-live").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(0).namePrefix("bot").password("pass")
                .targetStatus(BotGroupStatus.ACTIVE).build();
        when(botGroupService.findById("g-live")).thenReturn(group);
        when(environmentService.findById("env-1"))
                .thenThrow(new IllegalStateException("auth gateway returned 503"));

        // With a live runtime the internal stop takes the (unchanged) teardown path,
        // which does persist STOPPED. The restore is what undoes it.
        runningGroups(service).put("g-live", new BotGroupRuntime("g-live", 0, "env-1"));

        assertThatThrownBy(() -> service.restart("g-live"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(group.getTargetStatus())
                .as("a failed restart is a no-op on persisted intent")
                .isEqualTo(BotGroupStatus.ACTIVE);
        // Two writes: the stop's STOPPED and the restore. The point is the last one.
        verify(botGroupService, times(2)).save(any(BotGroup.class));
    }

    @Test
    @DisplayName("a failed restart of a group the operator had already parked leaves it STOPPED")
    void restart_leavesAnAlreadyStoppedGroupAlone() {
        // The restore must not manufacture intent: STOPPED before ⇒ STOPPED after.
        BotGroup group = BotGroup.builder()
                .id("g-parked").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(3).namePrefix("bot").password("pass")
                .targetStatus(BotGroupStatus.STOPPED).build();
        when(botGroupService.findById("g-parked")).thenReturn(group);
        when(environmentService.findById("env-1"))
                .thenThrow(new IllegalStateException("auth gateway returned 503"));

        assertThatThrownBy(() -> service.restart("g-parked"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(group.getTargetStatus()).isEqualTo(BotGroupStatus.STOPPED);
        verify(botGroupService, never()).save(any(BotGroup.class));
    }

    /* ----- DEAD_GROUP_AUTO_RECOVERY Phase 3: startForRecovery (AD-1, AD-5) ----- */

    @Test
    @DisplayName("startForRecovery() refuses a STOPPED group — the AD-5 opt-out is re-asserted under the lock")
    void startForRecovery_reAssertsEligibilityUnderLock() {
        // The reconciler decided this group was a candidate on a previous tick; by the
        // time the lock is taken an operator has pressed Stop. Nothing may start.
        BotGroup parked = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(3).namePrefix("bot").password("pass")
                .targetStatus(BotGroupStatus.STOPPED).build();
        when(botGroupService.findById("g-1")).thenReturn(parked);

        boolean up = service.startForRecovery("g-1");

        assertThat(up).isFalse();
        verify(botFactory, never()).createBot(anyString(), any(BotConfiguration.class));
        verify(botGroupService, never()).save(any(BotGroup.class));
        assertThat(runningGroups(service).get("g-1")).isNull();
    }

    @Test
    @DisplayName("startForRecovery() on a DEAD group runs the existing reclaim + rebuild and reports it came up")
    void startForRecovery_rebuildsThroughTheExistingStartPath() throws Exception {
        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(3).namePrefix("bot").password("pass")
                .targetStatus(BotGroupStatus.DEAD).build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        // Hold every bot task inside the runtime executor so getRunningBotCount() is
        // deterministic: the return value of startForRecovery is what the reconciler
        // classifies as success vs failure, so it cannot be asserted against a race.
        CountDownLatch hold = new CountDownLatch(1);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> {
                    Bot b = stubBot("bot" + System.nanoTime());
                    lenient().when(b.getConfiguration()).thenAnswer(c -> {
                        hold.await();
                        return null;
                    });
                    return b;
                });

        BotGroupRuntime dead = new BotGroupRuntime("g-1", 3, "env-1");
        dead.markAsDead();
        runningGroups(service).put("g-1", dead);

        try {
            boolean up = service.startForRecovery("g-1");

            assertThat(up).as("ACTIVE runtime with live bots").isTrue();
            verify(botFactory, times(3)).createBot(anyString(), any(BotConfiguration.class));
            BotGroupRuntime rebuilt = runningGroups(service).get("g-1");
            assertThat(rebuilt).isNotSameAs(dead);
            assertThat(rebuilt.getActualStatus()).isEqualTo(BotGroupStatus.ACTIVE);

            ArgumentCaptor<BotGroup> saved = ArgumentCaptor.forClass(BotGroup.class);
            verify(botGroupService).save(saved.capture());
            assertThat(saved.getValue().getTargetStatus()).isEqualTo(BotGroupStatus.ACTIVE);
        } finally {
            hold.countDown();
            service.stop("g-1");
        }
    }

    /**
     * The money invariant, and what it actually is.
     * <p>
     * <b>Registration is proved structurally.</b> The only thing that can start a registration is
     * {@code BotGroupService.save}'s {@code if (isNewGroup)} branch (which sets
     * {@code REGISTRATION_PENDING} and enqueues the worker), and {@code isNewGroup} is "id is null
     * or empty". Every save captured here carries {@code "g-1"}, so the branch is provably not
     * taken. (This paragraph named {@code registerUsers} until review T3; that method was deleted
     * in Phase 4 along with the synchronous fan-out.)
     * <p>
     * <b>Deposits are a different claim and the old assertion did not make it.</b>
     * The test used to scan captured log lines for "registr"/"deposit" — an
     * assertion that could never have failed: the appender attaches to
     * {@code BotGroupBehaviorService}'s logger, and registration lines come from
     * {@code BotGroupService} and deposit lines from {@code Bot}, both of which are
     * mocks here and log nothing at all. What replaces it is the fact that decides
     * whether a recovered bot can move money: the {@code BotBehaviorConfig} handed to
     * every bot carries the group's own {@code autoDepositEnabled}, unchanged and
     * unforced. Recovery adds no deposit a manual {@code /restart} would not make —
     * which is the honest form of "never deposits", and is what
     * {@code startForRecovery}'s javadoc now says.
     */
    @Test
    @DisplayName("startForRecovery() re-authenticates existing accounts — NO registration, NO new group, deposit behaviour unchanged")
    void startForRecovery_neverRegistersOrDeposits() {
        // The money invariant. Recovery reuses the accounts the group already owns;
        // registering or depositing again would spend real money on every recovery.
        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(3).namePrefix("bot").password("pass")
                .targetStatus(BotGroupStatus.DEAD).build();
        Environment env = Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
        Game game = Game.builder().id("game-1").name("BauCua").build();

        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(env);
        when(gameService.findById("game-1")).thenReturn(game);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        BotGroupRuntime dead = new BotGroupRuntime("g-1", 3, "env-1");
        dead.markAsDead();
        runningGroups(service).put("g-1", dead);

        try {
            service.startForRecovery("g-1");

            // The only account-facing collaborator is createBot — the re-auth path,
            // once per already-existing account.
            ArgumentCaptor<BotConfiguration> configs =
                    ArgumentCaptor.forClass(BotConfiguration.class);
            verify(botFactory, times(3)).createBot(anyString(), configs.capture());

            // Registration is structurally unreachable: BotGroupService.save only
            // registers users when the group's id is null (a NEW group). Every save on
            // this path carries the existing id, so no save can create accounts.
            ArgumentCaptor<BotGroup> saved = ArgumentCaptor.forClass(BotGroup.class);
            verify(botGroupService, atLeast(1)).save(saved.capture());
            assertThat(saved.getAllValues()).allSatisfy(g ->
                    assertThat(g.getId()).as("recovery must never save a new (id-less) group")
                            .isEqualTo("g-1"));

            // Deposit behaviour is the group's own, neither forced on nor altered:
            // autoDepositEnabled defaults false on this group, so these bots cannot
            // top up at all. (A group with the flag on would top up exactly as after
            // a manual /restart — see the javadoc; that is the invariant, not
            // "recovery cannot move money".)
            assertThat(configs.getAllValues()).allSatisfy(c ->
                    assertThat(c.getBehaviorConfig().isAutoDepositEnabled())
                            .as("recovery passes through the group's own auto-deposit setting")
                            .isEqualTo(group.isAutoDepositEnabled()));
        } finally {
            service.stop("g-1");
        }
    }

    @Test
    @DisplayName("stop() on an unknown id now propagates ResourceNotFoundException — Phase 2 replaced the WARN + return")
    void stop_propagatesNotFoundForAnUnknownIdWhenThereIsNoRuntime() {
        // Behaviour change worth pinning: before Phase 2 this path logged
        // "Bot group {} is not running" and returned normally for ANY id, existing
        // or not. It now loads the group in order to persist the operator's intent,
        // so an unknown id surfaces as a 404 through RestExceptionHandler.
        //
        // The REST surface is unchanged, because BotGroupController.runWithManualOverride
        // already calls service.findById(id) before delegating — an unknown id was
        // always a 404 there. The change is visible only to a direct in-process
        // caller, which is why it is asserted against the service and not the API.
        when(botGroupService.findById("ghost"))
                .thenThrow(new com.vingame.bot.common.exception.ResourceNotFoundException("BotGroup not found"));

        assertThatThrownBy(() -> service.stop("ghost"))
                .isInstanceOf(com.vingame.bot.common.exception.ResourceNotFoundException.class);
        verify(botGroupService, never()).save(any(BotGroup.class));
    }

    @Test
    @DisplayName("startForRecovery() refuses a MANUAL_OFF group — the second opt-out, re-asserted under the lock")
    void startForRecovery_refusesManualOffUnderTheLock() {
        // A timed group that an operator stopped is parked MANUAL_OFF by
        // BotGroupController.runWithManualOverride, and AD-3 condition 4 must reject
        // it even though its persisted status is DEAD (e.g. it was stopped while
        // already dead, or the health monitor re-stamped DEAD afterwards).
        BotGroup parked = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(3).namePrefix("bot").password("pass")
                .targetStatus(BotGroupStatus.DEAD)
                .activationMode(com.vingame.bot.domain.botgroup.model.ActivationMode.MANUAL_OFF)
                .build();
        when(botGroupService.findById("g-1")).thenReturn(parked);

        assertThat(service.startForRecovery("g-1")).isFalse();
        verify(botFactory, never()).createBot(anyString(), any(BotConfiguration.class));
        verify(botGroupService, never()).save(any(BotGroup.class));
        assertThat(runningGroups(service).get("g-1")).isNull();
    }

    /**
     * Run {@code action} with a {@link CapturingAppender} attached to
     * {@link BotGroupBehaviorService}'s logger at {@code ALL}, and return the events
     * it emitted. The appender is always detached and the prior level restored, so
     * one test cannot leak log configuration into the next.
     */
    private static List<LogEvent> captureBehaviorServiceLogs(Runnable action) {
        CapturingAppender appender = new CapturingAppender(
                "CapturingAppender-stop-" + System.nanoTime());
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        String loggerName = BotGroupBehaviorService.class.getName();
        LoggerConfig loggerConfig = ctx.getConfiguration().getLoggerConfig(loggerName);
        Level prev = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.ALL);
        ctx.updateLoggers();
        try {
            action.run();
        } finally {
            loggerConfig.removeAppender(appender.getName());
            loggerConfig.setLevel(prev);
            ctx.updateLoggers();
            appender.stop();
        }
        return appender.events();
    }

    private static List<String> formattedAt(List<LogEvent> events, Level level) {
        return events.stream()
                .filter(e -> e.getLevel() == level)
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    /**
     * Minimal in-memory log4j2 appender so we can assert on emitted log events.
     * Lives as a static nested class to keep the test file self-contained.
     */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender(String name) {
            super(name, null, PatternLayout.createDefaultLayout(), false, null);
        }

        @Override
        public void append(LogEvent event) {
            // toImmutable() so the event survives outside the logger's reusable buffer.
            events.add(event.toImmutable());
        }

        List<LogEvent> events() {
            return new ArrayList<>(events);
        }
    }
}
