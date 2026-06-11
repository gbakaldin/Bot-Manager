package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.auth.TokensProvider;
import com.vingame.websocketparser.scenario.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Bot (core)")
class BotTest {

    private ApiGatewayClient apiGatewayClient;
    private GameMsClient gameMsClient;
    private ClientFactory clientFactory;
    private VingameWebSocketClient wsClient;
    private TokensProvider tokens;
    private BotMetrics metrics;

    private TestBot bot;

    @BeforeEach
    void setUp() {
        apiGatewayClient = mock(ApiGatewayClient.class);
        gameMsClient = mock(GameMsClient.class);
        clientFactory = mock(ClientFactory.class);
        wsClient = mock(VingameWebSocketClient.class);
        tokens = mock(TokensProvider.class);
        metrics = mock(BotMetrics.class);

        when(apiGatewayClient.getApiGateway()).thenReturn("http://gateway.test");

        BotCredentials credentials = BotCredentials.builder()
                .username("botuser1")
                .password("pw")
                .fingerprint("fp-1")
                .build();

        Game game = Game.builder()
                .id("g1")
                .name("BauCua")
                .pluginName("BauCua")
                .offset(2000)
                .numberOfOptions(6)
                .build();

        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(credentials)
                .environmentId("env-1")
                .botGroupId("group-1")
                .botIndex(1)
                .game(game)
                .zoneName("MiniGame3")
                .timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .build();

        bot = new TestBot();
        bot.setClients(apiGatewayClient, gameMsClient, clientFactory);
        bot.setConfiguration(cfg);
        bot.setMetrics(metrics);
    }

    /* ----- creditBalance ----- */

    @Nested
    @DisplayName("creditBalance")
    class CreditBalanceTests {

        @Test
        @DisplayName("Decrements expectedCurrentBalance and bumps totalBets and totalBetAmount")
        void shouldDecrementAndIncrement() {
            long initialExpected = bot.getExpectedBalance();

            bot.creditBalance(500);

            assertThat(bot.getExpectedBalance()).isEqualTo(initialExpected - 500);
            assertThat(bot.getTotalBetsPlaced().get()).isEqualTo(1);
            assertThat(bot.getTotalBetAmount().get()).isEqualTo(500);
            // ENDGAME_METRICS Phase B/C: bet-counter metrics moved to onEndGame's
            // HasBetTotals branch — creditBalance must not touch BotMetrics.
            verify(metrics, never()).incBetsPlaced(anyInt(), anyLong());
        }

        @Test
        @DisplayName("Accumulates correctly over multiple credit calls")
        void shouldAccumulate() {
            long initialExpected = bot.getExpectedBalance();

            bot.creditBalance(100);
            bot.creditBalance(250);

            assertThat(bot.getExpectedBalance()).isEqualTo(initialExpected - 350);
            assertThat(bot.getTotalBetsPlaced().get()).isEqualTo(2);
            assertThat(bot.getTotalBetAmount().get()).isEqualTo(350);
            verify(metrics, never()).incBetsPlaced(anyInt(), anyLong());
        }

        @Test
        @DisplayName("creditBalance touches no BotMetrics counter (Phase B contract)")
        void shouldNotEmitMetricsCounter() {
            bot.creditBalance(500);
            // Strict contract: zero interactions on the metrics mock. Local
            // accumulator semantics asserted in the tests above.
            verifyNoInteractions(metrics);
        }
    }

    /* ----- checkBalance cache ----- */

    @Nested
    @DisplayName("checkBalance")
    class CheckBalanceTests {

        @Test
        @DisplayName("Returns cached expectedCurrentBalance when delta is <= 1M")
        void shouldUseCacheWhenDeltaSmall() throws Exception {
            // lastFetchedBalance == expectedCurrentBalance == 1_000_000 -> delta 0
            setLong(bot, "lastFetchedBalance", 1_000_000L);
            ((AtomicLong) getField(bot, "expectedCurrentBalance")).set(1_000_000L);

            long result = bot.checkBalanceExposed();

            assertThat(result).isEqualTo(1_000_000L);
            verify(apiGatewayClient, never()).getBalance(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Refetches when delta exceeds 1M and updates both cached and expected balances")
        void shouldRefetchWhenDeltaLarge() throws Exception {
            setLong(bot, "lastFetchedBalance", 1_000_000L);
            ((AtomicLong) getField(bot, "expectedCurrentBalance")).set(-2_000_000L); // delta = 3M

            bot.client = wsClient;
            when(wsClient.getAuthToken()).thenReturn("auth-token-xyz");
            when(apiGatewayClient.getBalance("auth-token-xyz", "fp-1", "botuser1")).thenReturn(7_500_000L);

            long result = bot.checkBalanceExposed();

            assertThat(result).isEqualTo(7_500_000L);
            assertThat(bot.getLastFetchedBalance()).isEqualTo(7_500_000L);
            assertThat(bot.getExpectedBalance()).isEqualTo(7_500_000L);
            verify(apiGatewayClient).getBalance("auth-token-xyz", "fp-1", "botuser1");
        }

        @Test
        @DisplayName("On first call with no cached value (lastFetched=-1, expected=-100M), refetches")
        void shouldRefetchOnInitial() {
            // Defaults: lastFetchedBalance=-1, expectedCurrentBalance=-100_000_000L. delta = ~100M -> refetch.
            bot.client = wsClient;
            when(wsClient.getAuthToken()).thenReturn("auth-token");
            when(apiGatewayClient.getBalance("auth-token", "fp-1", "botuser1")).thenReturn(50_000_000L);

            long result = bot.checkBalanceExposed();

            assertThat(result).isEqualTo(50_000_000L);
            verify(apiGatewayClient).getBalance("auth-token", "fp-1", "botuser1");
        }
    }

    /* ----- deposit ----- */

    @Nested
    @DisplayName("deposit")
    class DepositTests {

        @Test
        @DisplayName("Early-returns when lastFetchedBalance < 0 — does not invoke gameMsClient")
        void shouldEarlyReturnWhenNotFetched() {
            // default lastFetchedBalance = -1
            bot.deposit();

            verify(gameMsClient, never()).deposit(anyString(), anyLong(), any());
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("On success callback, refetches balance and updates expectedCurrentBalance")
        void shouldUpdateBalanceOnSuccessCallback() throws Exception {
            setLong(bot, "lastFetchedBalance", 1_000_000L);
            ((AtomicLong) getField(bot, "expectedCurrentBalance")).set(1_000_000L);

            bot.client = wsClient;
            when(wsClient.getAgencyToken()).thenReturn("agency-tok");
            when(wsClient.getAuthToken()).thenReturn("auth-tok");
            when(apiGatewayClient.getBalance("auth-tok", "fp-1", "botuser1")).thenReturn(999_999_999L);

            doAnswer(inv -> {
                Consumer<Boolean> cb = inv.getArgument(2);
                cb.accept(true);
                return null;
            }).when(gameMsClient).deposit(eq("agency-tok"), eq(1_000_000_000L), any(Consumer.class));

            bot.deposit();

            assertThat(bot.getLastFetchedBalance()).isEqualTo(999_999_999L);
            assertThat(bot.getExpectedBalance()).isEqualTo(999_999_999L);
            verify(apiGatewayClient).getBalance("auth-tok", "fp-1", "botuser1");
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("On failure callback, balance is NOT updated")
        void shouldNotUpdateBalanceOnFailureCallback() throws Exception {
            setLong(bot, "lastFetchedBalance", 1_000_000L);
            ((AtomicLong) getField(bot, "expectedCurrentBalance")).set(1_000_000L);

            bot.client = wsClient;
            when(wsClient.getAgencyToken()).thenReturn("agency-tok");

            doAnswer(inv -> {
                Consumer<Boolean> cb = inv.getArgument(2);
                cb.accept(false);
                return null;
            }).when(gameMsClient).deposit(eq("agency-tok"), eq(1_000_000_000L), any(Consumer.class));

            bot.deposit();

            assertThat(bot.getLastFetchedBalance()).isEqualTo(1_000_000L);
            assertThat(bot.getExpectedBalance()).isEqualTo(1_000_000L);
            verify(apiGatewayClient, never()).getBalance(anyString(), anyString(), anyString());
        }
    }

    /* ----- initialize / status transitions ----- */

    @Nested
    @DisplayName("initialize / status transitions")
    class StatusTests {

        @Test
        @DisplayName("Initial status is AUTHENTICATING")
        void initialStatus() {
            assertThat(bot.getStatus()).isEqualTo(BotStatus.AUTHENTICATING);
        }

        @Test
        @DisplayName("After initialize(), status reaches CONNECTING (start() is not called)")
        void shouldTransitionToConnecting() {
            when(apiGatewayClient.authenticate(any())).thenReturn(tokens);
            when(tokens.getAgencyToken()).thenReturn("agency1234567890");
            when(tokens.getAuthToken()).thenReturn("auth1234567890abc");
            when(clientFactory.newClient(eq(tokens), eq("botuser1"))).thenReturn(wsClient);

            bot.initialize();

            assertThat(bot.getStatus()).isEqualTo(BotStatus.CONNECTING);
            verify(wsClient).connect();
        }

        @Test
        @DisplayName("markConnectionAuthenticated transitions to CONNECTION_AUTHENTICATED from STARTED")
        void shouldTransitionToConnectionAuthenticated() {
            // Move bot to STARTED first via start()
            bot.client = wsClient;
            bot.start();
            assertThat(bot.getStatus()).isEqualTo(BotStatus.STARTED);

            bot.markConnectionAuthenticatedExposed();

            assertThat(bot.getStatus()).isEqualTo(BotStatus.CONNECTION_AUTHENTICATED);
        }

        @Test
        @DisplayName("markConnectionAuthenticated is a no-op when already CONNECTION_AUTHENTICATED")
        void shouldNoOpWhenAlreadyAtTarget() throws Exception {
            // Force status to CONNECTION_AUTHENTICATED
            Field f = Bot.class.getDeclaredField("status");
            f.setAccessible(true);
            f.set(bot, BotStatus.CONNECTION_AUTHENTICATED);

            bot.markConnectionAuthenticatedExposed();

            assertThat(bot.getStatus()).isEqualTo(BotStatus.CONNECTION_AUTHENTICATED);
        }
    }

    /* ----- isConnected / isStopped ----- */

    @Nested
    @DisplayName("isConnected / isStopped")
    class ConnectionFlagsTests {

        @Test
        @DisplayName("isConnected returns false when client is null")
        void isConnectedFalseWhenNullClient() {
            assertThat(bot.isConnected()).isFalse();
        }

        @Test
        @DisplayName("isConnected returns true when client.isOpen() is true")
        void isConnectedTrueWhenOpen() {
            bot.client = wsClient;
            when(wsClient.isOpen()).thenReturn(true);

            assertThat(bot.isConnected()).isTrue();
        }

        @Test
        @DisplayName("isConnected returns false when client.isOpen() is false")
        void isConnectedFalseWhenClosed() {
            bot.client = wsClient;
            when(wsClient.isOpen()).thenReturn(false);

            assertThat(bot.isConnected()).isFalse();
        }

        @Test
        @DisplayName("isStopped is false initially, true after cleanup()")
        void isStoppedTransitions() {
            assertThat(bot.isStopped()).isFalse();

            // cleanup() may call client.close() if client.isOpen() -> false; safe
            bot.cleanup();

            assertThat(bot.isStopped()).isTrue();
        }
    }

    /* ----- helpers ----- */

    private static Object getField(Object target, String name) throws Exception {
        Field f = Bot.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setLong(Object target, String name, long value) throws Exception {
        Field f = Bot.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setLong(target, value);
    }

    /** Minimal Bot subclass with no-op abstract implementations. */
    static class TestBot extends Bot {
        @Override protected void initializeSubclass() {}
        @Override protected boolean shouldBet() { return false; }
        @Override protected long resolveBetAmount() { return 0L; }
        @Override protected Supplier<Boolean> resolveBetCondition() { return () -> false; }
        @Override protected Scenario botBehaviorScenario() { return null; }
        @Override protected void onStart() {}

        /** Expose checkBalance for tests. */
        long checkBalanceExposed() { return checkBalance(); }

        /** Expose markConnectionAuthenticated for tests. */
        void markConnectionAuthenticatedExposed() { markConnectionAuthenticated(); }
    }
}
