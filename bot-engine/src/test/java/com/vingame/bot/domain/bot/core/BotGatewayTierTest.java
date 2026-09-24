package com.vingame.bot.domain.bot.core;

import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.gateway.RecordingGatewayBudget;
import com.vingame.bot.infrastructure.gateway.RecordingGatewayBudget.Submission;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.auth.TokensProvider;
import com.vingame.websocketparser.scenario.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which tier each of a bot's own gateway interactions declares (GATEWAY_REQUEST_BUDGET AD-3).
 * <p>
 * The HTTP tiers are captured on the {@code ApiGatewayClient} mock's arguments; the
 * <b>WebSocket upgrade</b> tiers are captured on a {@link RecordingGatewayBudget}, because
 * {@code Bot.connectUnderBudget} is the only thing that knows them and there is no other way
 * to observe its choice. Nothing here opens a socket: the WS client is a mock, so the recorded
 * "upgrade" is a no-op call on it.
 * <p>
 * The three upgrade tiers are the part most likely to be got wrong later, because all three go
 * through one method and the difference between them is which caller reached it:
 * {@code initialize} is ESSENTIAL (a bot that never connects does not exist),
 * {@code tryReconnectWs} is PRIORITIZED (an existing bot trying to get back in),
 * {@code restart} is DEFAULT (periodic logout — nothing is wrong and nothing is starting).
 */
@DisplayName("Bot — the tier of every gateway interaction")
class BotGatewayTierTest {

    private ApiGatewayClient apiGatewayClient;
    private ClientFactory clientFactory;
    private VingameWebSocketClient wsClient;
    private RecordingGatewayBudget budget;
    private TierBot bot;

    @BeforeEach
    void setUp() {
        apiGatewayClient = mock(ApiGatewayClient.class);
        clientFactory = mock(ClientFactory.class);
        wsClient = mock(VingameWebSocketClient.class);
        TokensProvider tokens = mock(TokensProvider.class);
        budget = new RecordingGatewayBudget();

        when(apiGatewayClient.getApiGateway()).thenReturn("http://gateway.test");
        when(apiGatewayClient.authenticate(any(), any(), any())).thenReturn(tokens);
        when(tokens.getAgencyToken()).thenReturn("18-aaaaaaaaaaaaaaaa");
        when(tokens.getAuthToken()).thenReturn("session-bbbbbbbbbb");
        when(clientFactory.newClient(any(), anyString())).thenReturn(wsClient);
        when(wsClient.getAuthToken()).thenReturn("session-bbbbbbbbbb");

        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("authtestws1").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(Game.builder().id("g1").name("BauCua").pluginName("Plugin")
                        .gameType(com.vingame.bot.domain.game.model.GameType.BETTING_MINI)
                        .offset(2000).numberOfOptions(6).build())
                .zoneName("MiniGame").timeoutMillis(1000L).watchdogTimeoutSeconds(120L)
                .build();

        bot = new TierBot();
        bot.setClients(apiGatewayClient, mock(GameMsClient.class), clientFactory);
        bot.setGatewayBudget(budget);
        bot.setConfiguration(cfg);
    }

    private List<Submission> upgrades() {
        return budget.submissions().stream().filter(Submission::wsUpgrade).toList();
    }

    @Test
    @DisplayName("initialize: login and WS upgrade are both ESSENTIAL")
    void initializeIsEssential() {
        bot.initialize();

        org.mockito.Mockito.verify(apiGatewayClient)
                .authenticate(any(), org.mockito.ArgumentMatchers.eq(RequestTier.ESSENTIAL), any());
        assertThat(upgrades()).hasSize(1);
        assertThat(upgrades().get(0).tier()).isEqualTo(RequestTier.ESSENTIAL);
        // The scope is the bot's, so a /stop on this group can cancel a queued start (Phase 3).
        assertThat(upgrades().get(0).scope().botGroupId()).isEqualTo("group-1");
        assertThat(upgrades().get(0).scope().botId()).isEqualTo("authtestws1");
    }

    @Test
    @DisplayName("the first balance read is ESSENTIAL, every later one is DEFAULT")
    void theFirstBalanceReadIsEssential() {
        bot.initialize();
        bot.client = wsClient;

        // lastFetchedBalance is -1 until the first successful read, and expectedCurrentBalance
        // is seeded at -100M, so the very first checkBalance() always fetches. That read is on
        // the START path (BettingMiniGameBot.onStart calls onNewSession before installing its
        // scenario), which is why it is ESSENTIAL and not DEFAULT.
        when(apiGatewayClient.getBalance(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(1_000_000_000L);
        bot.checkBalanceExposed();
        org.mockito.Mockito.verify(apiGatewayClient).getBalance(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(RequestTier.ESSENTIAL), any());

        // Now drift the local estimate past the sync threshold and read again.
        bot.driftBalance(500_000_000L);
        bot.checkBalanceExposed();
        org.mockito.Mockito.verify(apiGatewayClient).getBalance(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(RequestTier.DEFAULT), any());
    }

    @Test
    @DisplayName("deposit and its confirming read are both PRIORITIZED")
    void depositIsPrioritized() {
        bot.initialize();
        bot.client = wsClient;
        when(apiGatewayClient.getBalance(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(1_000_000L);
        bot.checkBalanceExposed();   // seeds lastFetchedBalance so deposit() is not a no-op
        when(apiGatewayClient.deposit(anyString(), org.mockito.ArgumentMatchers.anyLong(), any(), any()))
                .thenReturn(true);

        bot.deposit();

        org.mockito.Mockito.verify(apiGatewayClient).deposit(anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(RequestTier.PRIORITIZED), any());
        org.mockito.Mockito.verify(apiGatewayClient).getBalance(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(RequestTier.PRIORITIZED), any());
    }

    @Test
    @DisplayName("restart (periodic logout) upgrades at DEFAULT")
    void restartIsDefault() {
        bot.initialize();
        budget.clear();

        bot.restart();

        assertThat(upgrades()).hasSize(1);
        assertThat(upgrades().get(0).tier())
                .as("periodic logout reuses existing tokens: nothing is broken and nothing is "
                        + "starting, so it yields to both other tiers")
                .isEqualTo(RequestTier.DEFAULT);
    }

    @Test
    @DisplayName("the reconnect path re-authenticates and upgrades at PRIORITIZED")
    void theReconnectPathIsPrioritized() throws Exception {
        bot.initialize();
        budget.clear();

        // Both halves of the reconnect are private and are reached here directly rather than
        // through triggerFullReconnect, which spawns a virtual thread and a backoff loop — the
        // same reflection seam BotReconnectTest uses for the same reason.
        Method performReauth = Bot.class.getDeclaredMethod("performReauth");
        performReauth.setAccessible(true);
        assertThat((boolean) performReauth.invoke(bot)).isTrue();

        Method tryReconnectWs = Bot.class.getDeclaredMethod("tryReconnectWs");
        tryReconnectWs.setAccessible(true);
        assertThat((boolean) tryReconnectWs.invoke(bot)).isTrue();

        org.mockito.Mockito.verify(apiGatewayClient)
                .authenticate(any(), org.mockito.ArgumentMatchers.eq(RequestTier.PRIORITIZED), any());
        assertThat(upgrades()).hasSize(1);
        assertThat(upgrades().get(0).tier()).isEqualTo(RequestTier.PRIORITIZED);
    }

    @Test
    @DisplayName("a bot with no budget wired behaves exactly as it did before this feature")
    void aBotWithoutABudgetStillConnects() {
        TierBot bare = new TierBot();
        bare.setClients(apiGatewayClient, mock(GameMsClient.class), clientFactory);
        bare.setGatewayBudget(null);
        bare.setConfiguration(bot.getConfiguration());

        // UNLIMITED, not a NullPointerException: an uncounted WS upgrade in a fixture is a
        // missing metric, a null budget on a bot thread is a bot that never comes up.
        bare.initialize();

        org.mockito.Mockito.verify(wsClient, org.mockito.Mockito.atLeastOnce()).connect();
    }

    @Test
    @DisplayName("the scope is cancelled when the bot is stopped OR its group's start was cancelled")
    void theScopeCarriesBothHalvesOfCancellation() {
        // GATEWAY_REQUEST_BUDGET AD-8. The second half is what lets a /stop unwind a paced start:
        // without it, every one of a 3,000-bot group's queued requests would still be sent after
        // the operator called the start off, because none of those bots is individually stopped.
        java.util.concurrent.atomic.AtomicBoolean startCancelled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        TierBot scoped = new TierBot();
        scoped.setClients(apiGatewayClient, mock(GameMsClient.class), clientFactory);
        scoped.setGatewayBudget(budget);
        scoped.setConfiguration(BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("authtestws9").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(9)
                .game(Game.builder().id("g1").name("BauCua").pluginName("Plugin")
                        .gameType(com.vingame.bot.domain.game.model.GameType.BETTING_MINI)
                        .offset(2000).numberOfOptions(6).build())
                .zoneName("MiniGame").timeoutMillis(1000L).watchdogTimeoutSeconds(120L)
                .startCancelled(startCancelled::get)
                .build());

        scoped.initialize();
        var scope = upgrades().get(upgrades().size() - 1).scope();
        assertThat(scope.isCancelled()).as("nothing has been called off yet").isFalse();

        startCancelled.set(true);

        assertThat(scope.isCancelled())
                .as("the scope asks the supplier every time — a group-level cancel reaches a bot "
                        + "that is perfectly healthy and not individually stopped")
                .isTrue();
    }

    @Test
    @DisplayName("a configuration with no startCancelled supplier falls back to isStopped alone")
    void aBotWithNoStartCancelledSupplierIsStillCancellableByStop() {
        bot.initialize();
        var scope = upgrades().get(upgrades().size() - 1).scope();

        assertThat(scope.isCancelled()).isFalse();

        // cleanup(), not stop(): stop() closes the socket without setting `stopped` — that
        // asymmetry is what makes Bot.logout() spawn a spurious reconnect (plan Open Item 10).
        bot.cleanup();

        assertThat(scope.isCancelled())
                .as("null supplier must not break the pre-existing half")
                .isTrue();
    }

    /** Minimal concrete bot; no scenarios, no sleeping. */
    static class TierBot extends Bot {
        @Override protected void initializeSubclass() {}
        @Override protected Scenario botBehaviorScenario() { return null; }
        @Override protected void onStart() {}
        @Override protected void sleep(long millis) {}

        long checkBalanceExposed() {
            return checkBalance();
        }

        /** Move the local estimate away from the server figure, as placing bets would. */
        void driftBalance(long staked) {
            creditBalance(staked);
        }
    }
}
