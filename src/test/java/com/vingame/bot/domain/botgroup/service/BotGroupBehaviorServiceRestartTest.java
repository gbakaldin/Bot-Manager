package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.infrastructure.observability.BotMdcTagsMeterFilter;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

    @InjectMocks
    private BotGroupBehaviorService service;

    @BeforeEach
    void initConfigFields() {
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

    private static Bot stubBot(String username) {
        Bot b = mock(Bot.class);
        lenient().when(b.getUserName()).thenReturn(username);
        lenient().when(b.getTotalBetsPlaced()).thenReturn(new AtomicLong(0));
        lenient().when(b.getTotalBetAmount()).thenReturn(new AtomicLong(0));
        return b;
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
                sessionAggregationService);
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
