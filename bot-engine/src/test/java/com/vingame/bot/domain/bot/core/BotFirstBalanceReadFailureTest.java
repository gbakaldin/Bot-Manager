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
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void setUp() {
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
        bot.setConfiguration(BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("rikzz87").password("pw").fingerprint("fp").build())
                .environmentId("env-114").botGroupId("group-zz").botIndex(87)
                .game(Game.builder().id("g1").name("ZicZac").pluginName("Plugin")
                        .gameType(GameType.BETTING_MINI).offset(13000).numberOfOptions(6).build())
                .zoneName("MiniGame").timeoutMillis(1000L).watchdogTimeoutSeconds(120L)
                .build());
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

        assertThatThrownBy(bot::start).isInstanceOf(GatewayCircuitOpenException.class);

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
