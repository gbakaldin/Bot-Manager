package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.GatewayBudgetExhaustedException;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator;
import com.vingame.bot.infrastructure.observability.ScopedDebugEscalator;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import com.vingame.bot.infrastructure.runtime.BotGroupRuntime;
import com.vingame.websocketparser.VingameWebSocketClient;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>A periodic logout the budget refuses is not a failure</b> (GATEWAY_REQUEST_BUDGET AD-9,
 * commit {@code ad2ae5b}).
 * <p>
 * <b>Why QA is adding this.</b> This is the last path in the diff that still reported a budget
 * outcome as an upstream failure, and it shipped with no test. The shape matters more than the log
 * level: {@code Bot.restart()}'s WebSocket upgrade is {@code DEFAULT} — one bot per group per hour,
 * nothing broken, nothing starting — so it is the <b>first</b> thing a saturated window refuses,
 * and during a large group start on the same environment it is <em>expected</em> to be refused. On
 * the {@code catch (Exception)} arm that would be one {@code log.error} per group per logout
 * interval, on every environment in the fleet, for the feature working as designed: page-on-ERROR
 * is documented as reasonable in CLAUDE.md, so this is the difference between enforcement being
 * deployable and enforcement waking someone every hour.
 * <p>
 * The arm's <b>position</b> is what is really under test. {@code GatewayBudgetException} is a
 * {@code RuntimeException}, so an arm placed after {@code catch (Exception e)} would never be
 * reached and would compile — and on the ERROR path the message also carries
 * {@code e.getMessage()}, which for this hierarchy names a tier, a duration and an environment id.
 * <p>
 * No socket, no Spring context, no real sleep: {@code reconnectDelaySeconds} is set to 0.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@DisplayName("Periodic logout — a budget refusal is DEBUG and self-healing, never an ERROR")
class PeriodicLogoutBudgetOutcomeTest {

    @Mock private BotGroupService botGroupService;
    @Mock private EnvironmentService environmentService;
    @Mock private GameService gameService;
    @Mock private BotFactory botFactory;
    @Mock private BotMetrics botMetrics;
    @Mock private SessionAggregationService sessionAggregationService;
    @Mock private GroupLifecycleAggregator groupLifecycleAggregator;
    @Mock private ScopedDebugEscalator scopedDebugEscalator;
    @Mock private GatewayBudgetRegistry gatewayBudgetRegistry;
    /** PLUGIN_HOT_RELOAD_3_4 D-15: answers null, so bots fall back to `builtin` as before. */
    @Mock private com.vingame.bot.common.plugin.PluginVersionResolver pluginVersionResolver;

    @InjectMocks
    private BotGroupBehaviorService service;

    @BeforeEach
    void initConfigFields() {
        ReflectionTestUtils.setField(service, "deadBotGroupThreshold", 0.80);
        ReflectionTestUtils.setField(service, "botCreationParallelism", 10);
        ReflectionTestUtils.setField(service, "watchdogTimeoutSeconds", 180L);
        ReflectionTestUtils.setField(service, "periodicLogoutEnabled", false);
        ReflectionTestUtils.setField(service, "periodicLogoutIntervalMinutes", 60);
        // 0, so the logout's own Thread.sleep(reconnectDelaySeconds * 1000) costs the suite
        // nothing. The delay is not what is under test; what happens to the exception from
        // restart() afterwards is.
        ReflectionTestUtils.setField(service, "reconnectDelaySeconds", 0);
    }

    @AfterEach
    void shutdownExecutors() {
        try {
            service.shutdown();
        } catch (Exception ignored) {
            // the fixture never started anything
        }
    }

    /** An ACTIVE runtime holding one connected bot, which is what the logout path requires. */
    private BotGroupRuntime runtimeWith(Bot bot) {
        BotGroupRuntime runtime = new BotGroupRuntime("g-1", 1, "env-1", "Staging", "Group", null);
        runtime.setActualStatus(BotGroupStatus.ACTIVE);
        @SuppressWarnings("unchecked")
        List<Bot> bots = (List<Bot>) ReflectionTestUtils.getField(runtime, "botInstances");
        // Injected directly rather than through startBot(), which would submit the mock to the
        // runtime's executor and call start() on it — irrelevant here and a source of races.
        bots.add(bot);
        return runtime;
    }

    private Bot connectedBot() {
        Bot bot = mock(Bot.class);
        VingameWebSocketClient client = mock(VingameWebSocketClient.class);
        when(client.isOpen()).thenReturn(true);
        when(bot.getClient()).thenReturn(client);
        when(bot.getUserName()).thenReturn("authtestws1");
        return bot;
    }

    private void performPeriodicLogout(BotGroupRuntime runtime) throws Exception {
        Method m = BotGroupBehaviorService.class
                .getDeclaredMethod("performPeriodicLogout", BotGroupRuntime.class);
        m.setAccessible(true);
        m.invoke(service, runtime);
    }

    @Test
    @DisplayName("a refused reconnect logs DEBUG and nothing at ERROR or WARN")
    void aRefusedReconnectIsNotAnError() throws Exception {
        Bot bot = connectedBot();
        doThrow(new GatewayBudgetExhaustedException(RequestTier.DEFAULT, "env-1",
                Duration.ofSeconds(28))).when(bot).restart();
        BotGroupRuntime runtime = runtimeWith(bot);

        List<LogEvent> events = capture(() -> {
            try {
                performPeriodicLogout(runtime);
            } catch (Exception e) {
                throw new AssertionError("the refusal must not propagate out of the scheduler tick "
                        + "— the periodic-logout executor would otherwise lose its task", e);
            }
        });

        assertThat(formattedAt(events, Level.ERROR))
                .as("one ERROR per group per logout interval, on every environment, for the budget "
                        + "working — page-on-ERROR is documented as reasonable, so this is the "
                        + "difference between enforcement being deployable and it waking someone")
                .isEmpty();
        assertThat(formattedAt(events, Level.WARN)).isEmpty();
        assertThat(formattedAt(events, Level.DEBUG))
                .anySatisfy(line -> assertThat(line)
                        .contains("refused by the gateway budget")
                        .contains("authtestws1"));
        // The logout itself DID happen — which is why the outcome is recoverable rather than
        // silent: Bot.logout() closes the socket without setting `stopped`, so the library's
        // channelInactive arms the bot's own reconnect loop at PRIORITIZED, a tier the same
        // window is far less likely to be refusing.
        verify(bot).logout();
        verify(bot).restart();
    }

    @Test
    @DisplayName("a genuine failure is still an ERROR — the arm must not swallow the rest")
    void aGenuineFailureIsStillAnError() throws Exception {
        // The counterweight. An arm that caught too much would make this class's real failures
        // (a dead client factory, a ValidationException from the restart path — the known
        // RESTART_LIFECYCLE bug) invisible at the same time.
        Bot bot = connectedBot();
        doThrow(new IllegalStateException("Authentication configuration is required"))
                .when(bot).restart();
        BotGroupRuntime runtime = runtimeWith(bot);

        List<LogEvent> events = capture(() -> {
            try {
                performPeriodicLogout(runtime);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertThat(formattedAt(events, Level.ERROR))
                .anySatisfy(line -> assertThat(line).contains("Periodic logout failed"));
    }

    // ------------------------------------------------------------------ log capture

    private static List<LogEvent> capture(Runnable action) {
        CapturingAppender appender = new CapturingAppender("Capturing-logout-" + System.nanoTime());
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

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name) {
            super(name, null, PatternLayout.createDefaultLayout(), true, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> events() {
            return new ArrayList<>(events);
        }
    }
}
