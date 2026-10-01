package com.vingame.bot.domain.bot.core;

import com.vingame.bot.common.exception.GatewayCircuitOpenException;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.auth.TokensProvider;
import com.vingame.websocketparser.scenario.Scenario;
import com.vingame.bot.common.exception.GatewayRequestCancelledException;
import com.vingame.bot.common.exception.UpstreamLoginException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GATEWAY_REQUEST_BUDGET A33 — a bot whose first balance read fails must not end up
 * <b>connected but idle</b> (staging anomaly A1, second half).
 * <p>
 * On staging, five Zic Zac bots lost their first {@code verifytoken} read to A1's transport error.
 * {@code onStart} reads the balance before it installs the scenario, so each was left with an
 * open, authenticated socket and nothing on it: {@code /health} counted it connected, its status
 * stayed {@code AUTHENTICATING_CONNECTION}, {@code monitorHealth} (which counts DEAD and
 * RECONNECTING) saw nothing, and the watchdog — armed by the scenario — never existed. Nothing
 * recovered them.
 * <p>
 * The fixture bot's {@code onStart} does what {@code BettingMiniGameBot}'s and
 * {@code SlotMachineBot}'s do: {@code checkBalance()} first, then "install the scenario". Each
 * WebSocket client it is given is a mock whose {@code isOpen()} follows {@code connect()} and
 * {@code close()}, so {@link Bot#isConnected()} answers what {@code /health} would.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("Bot — a failed first balance read is retried, then handed to the reconnect loop (A33)")
class BotFirstBalanceReadFailureTest {

    private ApiGatewayClient apiGatewayClient;
    private ClientFactory clientFactory;
    private final List<VingameWebSocketClient> sockets = new CopyOnWriteArrayList<>();
    private SetupBot bot;
    private final java.util.concurrent.CountDownLatch reloginReleased = new java.util.concurrent.CountDownLatch(1);

    /** Every event the com.vingame.bot logger tree emits while a test runs. */
    private static final class Capture extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        Capture() {
            super("a33-start-capture", null, PatternLayout.createDefaultLayout(), true, null);
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLoggerName().startsWith(Bot.class.getName())) {
                events.add(event.toImmutable());
            }
        }

        List<LogEvent> at(Level level) {
            return events.stream().filter(e -> e.getLevel() == level).toList();
        }

        void clear() {
            events.clear();
        }
    }

    private Capture capture;
    private LoggerConfig loggerConfig;

    @AfterEach
    void detachCapture() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().removeLogger(Bot.class.getName());
        ctx.updateLoggers();
        capture.stop();
    }

    @BeforeEach
    void setUp() {
        capture = new Capture();
        capture.start();
        // A logger of its own for Bot, at INFO, not additive: WARN/ERROR/INFO are what these tests
        // assert, and touching the shared config's level would flood the build output with DEBUG.
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = new LoggerConfig(Bot.class.getName(), Level.INFO, false);
        loggerConfig.addAppender(capture, Level.INFO, null);
        ctx.getConfiguration().addLogger(Bot.class.getName(), loggerConfig);
        ctx.updateLoggers();

        apiGatewayClient = mock(ApiGatewayClient.class);
        clientFactory = mock(ClientFactory.class);
        TokensProvider tokens = mock(TokensProvider.class);
        when(tokens.getAgencyToken()).thenReturn("18-aaaaaaaaaaaaaaaa");
        when(tokens.getAuthToken()).thenReturn("session-bbbbbbbbbb");
        when(apiGatewayClient.getApiGateway()).thenReturn("http://gateway.test");
        when(apiGatewayClient.authenticate(any(), any(), any())).thenAnswer(inv -> {
            // The reconnect loop's re-login can be held, so the state the failed start left
            // behind is asserted before the recovery moves it on.
            if (inv.getArgument(1) == RequestTier.PRIORITIZED) {
                reloginReleased.await(20, TimeUnit.SECONDS);
            }
            return tokens;
        });
        when(clientFactory.newClient(any(), anyString())).thenAnswer(inv -> socket());

        bot = new SetupBot();
        bot.setClients(apiGatewayClient, mock(GameMsClient.class), clientFactory);
        bot.setConfiguration(config(null));
    }

    private static BotConfiguration config(BooleanSupplier startCancelled) {
        return BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("rikzz87").password("pw").fingerprint("fp").build())
                .environmentId("env-114").botGroupId("group-zz").botIndex(87)
                .game(Game.builder().id("g1").name("ZicZac").pluginName("Plugin")
                        .gameType(GameType.BETTING_MINI).offset(13000).numberOfOptions(6).build())
                .zoneName("MiniGame").timeoutMillis(1000L).watchdogTimeoutSeconds(120L)
                .startCancelled(startCancelled)
                .build();
    }

    /** A WS client whose isOpen() is true between connect() and close(), like the real one. */
    private VingameWebSocketClient socket() throws Exception {
        VingameWebSocketClient ws = mock(VingameWebSocketClient.class);
        AtomicBoolean open = new AtomicBoolean();
        doAnswer(inv -> {
            open.set(true);
            return null;
        }).when(ws).connect();
        doAnswer(inv -> {
            open.set(false);
            return null;
        }).when(ws).close();
        when(ws.isOpen()).thenAnswer(inv -> open.get());
        when(ws.getAuthToken()).thenReturn("session-bbbbbbbbbb");
        sockets.add(ws);
        return ws;
    }

    /** A1's failure exactly as ApiGatewayClient.readBalance surfaces it. */
    private static RuntimeException a1() {
        return new RuntimeException("Failed to fetch balance for user: rikzz87",
                new IOException("too many concurrent streams"));
    }

    /** First {@code failures} first-reads throw A1, every later one answers. */
    private void firstReadFails(int failures) {
        AtomicInteger calls = new AtomicInteger();
        when(apiGatewayClient.getBalance(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    if (calls.incrementAndGet() <= failures) {
                        throw a1();
                    }
                    return 1_000_000L;
                });
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as("condition within 20 s").isTrue();
    }

    @Test
    @DisplayName("a transient failure is retried in place, through the budget, at ESSENTIAL — and the bot plays")
    void aTransientFailureIsRetried() {
        // A literal 1, not ATTEMPTS - 1: with ATTEMPTS lowered to 1 that would be zero failures
        // and the test would pass on a bot that does not retry at all (mutation check).
        firstReadFails(1);
        bot.initialize();

        bot.start();

        assertThat(bot.getStatus()).isEqualTo(BotStatus.STARTED);
        assertThat(bot.scenarioInstalled).isTrue();
        assertThat(bot.getLastFetchedBalance()).isEqualTo(1_000_000L);
        // Every attempt is an ordinary admitted request at the first read's own tier.
        verify(apiGatewayClient, times(2))
                .getBalance(anyString(), anyString(), anyString(), eq(RequestTier.ESSENTIAL), any());
        // No reconnect was needed: the socket the bot started on is the one it plays on.
        assertThat(sockets).hasSize(1);
        assertThat(bot.isConnected()).isTrue();
        verify(apiGatewayClient, times(1)).authenticate(any(), any(), any());
    }

    @Test
    @DisplayName("a failure that outlasts the retries leaves the bot RECONNECTING, not connected-but-idle, and it recovers")
    void aPersistentFailureIsHandedToTheReconnectLoop() throws Exception {
        // Fails every in-place attempt of the first start, then the gateway is fine again.
        firstReadFails(Bot.INITIAL_BALANCE_READ_ATTEMPTS);
        bot.initialize();
        VingameWebSocketClient first = sockets.get(0);
        assertThat(bot.isConnected()).as("precondition: the socket is up, as on staging").isTrue();

        assertThatThrownBy(bot::start)
                .as("the caller still sees the failure it always saw")
                .hasMessageContaining("Failed to fetch balance");

        // The A1 zombie was: scenario not installed, socket open, status not DEAD/RECONNECTING.
        assertThat(bot.scenarioInstalled).isFalse();
        assertThat(bot.getStatus())
                .as("in the reconnect loop: RECONNECTING, or AUTHENTICATING for its re-login — "
                        + "the same states a watchdog-triggered reconnect passes through")
                .isIn(BotStatus.RECONNECTING, BotStatus.AUTHENTICATING);
        verify(first, atLeastOnce()).close();
        assertThat(bot.isConnected()).as("the idle socket is closed, not left counted as connected")
                .isFalse();

        // And the existing recovery finishes the job: re-login at PRIORITIZED, a fresh socket,
        // start() again — which now reads the balance and installs the scenario.
        reloginReleased.countDown();
        await(() -> bot.getStatus() == BotStatus.STARTED);
        assertThat(bot.scenarioInstalled).isTrue();
        assertThat(bot.isConnected()).isTrue();
        verify(apiGatewayClient).authenticate(any(), eq(RequestTier.PRIORITIZED), any());
        // The start path tried at ESSENTIAL, in place; the loop's read is one PRIORITIZED attempt.
        verify(apiGatewayClient, times(Bot.INITIAL_BALANCE_READ_ATTEMPTS))
                .getBalance(anyString(), anyString(), anyString(), eq(RequestTier.ESSENTIAL), any());
        verify(apiGatewayClient, times(1))
                .getBalance(anyString(), anyString(), anyString(), eq(RequestTier.PRIORITIZED), any());
    }

    @Test
    @DisplayName("a first read that never succeeds ends DEAD through the reconnect cap — never connected-but-idle")
    void aFirstReadThatNeverSucceedsEndsDead() throws Exception {
        firstReadFails(Integer.MAX_VALUE);
        reloginReleased.countDown();   // nothing to hold here: let the loop run to its cap
        bot.initialize();

        assertThatThrownBy(bot::start).hasMessageContaining("Failed to fetch balance");

        await(() -> bot.getStatus() == BotStatus.DEAD);
        assertThat(bot.isConnected()).isFalse();
        assertThat(sockets).allMatch(ws -> !ws.isOpen());
        assertThat(bot.scenarioInstalled).isFalse();
    }

    @Test
    @DisplayName("a budget outcome on the first read is not retried in place — it is the budget's call (AD-9)")
    void aBudgetOutcomeIsNotRetriedInPlace() {
        when(apiGatewayClient.getBalance(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new GatewayCircuitOpenException("env-114", "cf-ray-1", Duration.ofMinutes(2)));
        bot.initialize();

        assertThatThrownBy(bot::start)
                .isInstanceOf(SessionSetupHandedOffException.class)
                .hasCauseInstanceOf(GatewayCircuitOpenException.class);

        verify(apiGatewayClient, times(1))
                .getBalance(anyString(), anyString(), anyString(), any(), any());
        assertThat(bot.getStatus()).isIn(BotStatus.RECONNECTING, BotStatus.AUTHENTICATING);
        assertThat(bot.isConnected()).isFalse();
        bot.cleanup();
        reloginReleased.countDown();
    }

    @Test
    @DisplayName("a stopped bot is not handed to the reconnect loop")
    void aStoppedBotIsNotReconnected() {
        firstReadFails(Integer.MAX_VALUE);
        bot.initialize();
        bot.cleanup();   // sets `stopped`, as BotGroupRuntime.stopAllBots does

        assertThatThrownBy(bot::start).hasMessageContaining("Failed to fetch balance");

        verify(apiGatewayClient, never()).authenticate(any(), eq(RequestTier.PRIORITIZED), any());
        verify(apiGatewayClient, times(1))
                .getBalance(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a start called off by /stop (GatewayRequestCancelledException, stopped still false) is not a reconnect")
    void aCancelledStartIsNotHandedToTheReconnectLoop() {
        // cancelStartInFlight -> budget.cancelScope completes every queued waiter BEFORE cleanup()
        // sets `stopped` (A33 review): this is the state each queued first read wakes up in.
        when(apiGatewayClient.getBalance(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new GatewayRequestCancelledException("env-114", "group-zz/rikzz87"));
        reloginReleased.countDown();
        bot.initialize();
        BotStatus before = bot.getStatus();

        assertThatThrownBy(bot::start)
                .as("the original exception, unwrapped: the caller logs a called-off start at DEBUG")
                .isInstanceOf(GatewayRequestCancelledException.class);

        verify(apiGatewayClient, never()).authenticate(any(), eq(RequestTier.PRIORITIZED), any());
        assertThat(bot.getStatus()).isEqualTo(before).isNotEqualTo(BotStatus.RECONNECTING);
        assertThat(sockets).hasSize(1);
        assertThat(capture.at(Level.WARN)).as("no reconnect WARN for an operator's stop").isEmpty();
    }

    @Test
    @DisplayName("a start whose group start was cancelled is not handed over either, whatever the read failed with")
    void aStartOfACancelledGroupIsNotHandedOver() {
        AtomicBoolean startCancelled = new AtomicBoolean();
        bot.setConfiguration(config(startCancelled::get));
        when(apiGatewayClient.getBalance(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> {
                    startCancelled.set(true);   // the /stop lands while the read is in flight
                    throw a1();
                });
        reloginReleased.countDown();
        bot.initialize();

        assertThatThrownBy(bot::start).hasMessageContaining("Failed to fetch balance");

        verify(apiGatewayClient, never()).authenticate(any(), eq(RequestTier.PRIORITIZED), any());
        assertThat(bot.getStatus()).isNotEqualTo(BotStatus.RECONNECTING);
    }

    @Test
    @DisplayName("inside the reconnect loop the first read is ONE attempt at PRIORITIZED, and its failure is DEBUG only")
    void insideTheLoopTheFirstReadIsOnePrioritizedAttempt() {
        firstReadFails(Integer.MAX_VALUE);
        bot.initialize();
        ((AtomicBoolean) ReflectionTestUtils.getField(bot, Bot.class, "reconnecting")).set(true);
        capture.clear();

        assertThatThrownBy(bot::start)
                .as("rethrown unchanged: tryReconnectWs counts it as this attempt's failure")
                .isNotInstanceOf(SessionSetupHandedOffException.class)
                .hasMessageContaining("Failed to fetch balance");

        verify(apiGatewayClient, times(1))
                .getBalance(anyString(), anyString(), anyString(), eq(RequestTier.PRIORITIZED), any());
        verify(apiGatewayClient, never())
                .getBalance(anyString(), anyString(), anyString(), eq(RequestTier.ESSENTIAL), any());
        assertThat(capture.at(Level.WARN)).isEmpty();
        assertThat(capture.at(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("a hand-off logs one WARN without a stack trace, and no ERROR, and the caller can tell it is not final")
    void aHandOffLogsOneWarnAndNoError() {
        firstReadFails(Bot.INITIAL_BALANCE_READ_ATTEMPTS);
        bot.initialize();
        capture.clear();

        assertThatThrownBy(bot::start).isInstanceOf(SessionSetupHandedOffException.class);

        assertThat(capture.at(Level.ERROR)).isEmpty();
        assertThat(capture.at(Level.WARN)).hasSize(1)
                .allSatisfy(e -> {
                    assertThat(e.getMessage().getFormattedMessage()).contains("session-setup failed");
                    assertThat(e.getThrown()).isNull();
                });
        assertThat(capture.at(Level.INFO)).as("per-bot lines never at INFO").isEmpty();
        reloginReleased.countDown();
    }

    @Test
    @DisplayName("FR-3: a re-login that never left the JVM (no free stream) is retryable, not DEAD")
    void aReloginWithNoFreeStreamIsRetryable() throws Exception {
        bot.initialize();
        java.lang.reflect.Method performReauth = Bot.class.getDeclaredMethod("performReauth");
        performReauth.setAccessible(true);

        org.mockito.Mockito.doThrow(new UpstreamLoginException(
                "Login failed for user 'rikzz87': no free gateway stream",
                new ApiGatewayClient.StreamWaitTimeoutException("no free gateway stream within PT10S")))
                .when(apiGatewayClient).authenticate(any(), any(), any());
        assertThat(performReauth.invoke(bot)).hasToString("RETRYABLE");
        assertThat(bot.getStatus()).isEqualTo(BotStatus.RECONNECTING);

        // Contrast: a real refusal from the gateway is still terminal, exactly as before.
        org.mockito.Mockito.doThrow(new UpstreamLoginException(
                "Login failed for user 'rikzz87': wrong password (HTTP 200, status: INVALID)", null))
                .when(apiGatewayClient).authenticate(any(), any(), any());
        assertThat(performReauth.invoke(bot)).hasToString("TERMINAL");
        assertThat(bot.getStatus()).isEqualTo(BotStatus.DEAD);
    }

    /** onStart shaped like BettingMiniGameBot's: first session, then the scenario. */
    static class SetupBot extends Bot {
        volatile boolean scenarioInstalled;

        @Override protected void initializeSubclass() {}
        @Override protected Scenario botBehaviorScenario() { return null; }
        @Override protected void sleep(long millis) {}

        @Override
        protected void onStart() {
            checkBalance();
            scenarioInstalled = true;
        }
    }
}
