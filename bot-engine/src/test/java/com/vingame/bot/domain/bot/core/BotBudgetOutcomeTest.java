package com.vingame.bot.domain.bot.core;

import com.vingame.bot.common.exception.GatewayBudgetExhaustedException;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.scenario.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>A budget outcome is never terminal for a bot, and never moves money on a stale figure</b>
 * (GATEWAY_REQUEST_BUDGET AD-9, AD-10).
 * <p>
 * These two rules are the reason enforcement can be switched on at all. Without AD-9, pacing a
 * fleet's re-auths — which is what the budget <em>does</em> during a large group start, by design
 * — would kill the bots it was pacing, and the operator would see a brand-wide auth outage that
 * the app itself caused. Without AD-10, the deferral that keeps a drift balance read off a
 * ws-parser message-processor thread would quietly become a reason to top a bot up against a
 * number the budget stopped us from refreshing.
 * <p>
 * No socket and no budget object here: the budget's refusal is stubbed at the
 * {@code ApiGatewayClient} boundary, which is exactly where a real one surfaces.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@DisplayName("Bot — budget outcomes are non-terminal, and never fund a deposit blind")
class BotBudgetOutcomeTest {

    private static final long DEPOSIT = 5_000_000L;

    private ApiGatewayClient apiGatewayClient;
    private VingameWebSocketClient wsClient;
    private BotMetrics metrics;
    private BudgetBot bot;

    @BeforeEach
    void setUp() {
        apiGatewayClient = mock(ApiGatewayClient.class);
        wsClient = mock(VingameWebSocketClient.class);
        metrics = mock(BotMetrics.class);

        when(apiGatewayClient.getApiGateway()).thenReturn("http://gateway.test");
        when(wsClient.getAuthToken()).thenReturn("tok");

        bot = new BudgetBot();
        bot.setClients(apiGatewayClient, mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("botuser1").password("pw").fingerprint("fp-1").build())
                .environmentId("env-1")
                .botGroupId("group-1")
                .botIndex(1)
                .game(Game.builder().id("g1").name("BauCua").gameType(GameType.BETTING_MINI)
                        .pluginName("BauCua").offset(2000).numberOfOptions(6).build())
                .behaviorConfig(BotBehaviorConfig.builder()
                        .autoDepositEnabled(true).depositAmount(DEPOSIT).build())
                .zoneName("MiniGame3")
                .timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .build());
        bot.setMetrics(metrics);
        bot.client = wsClient;
    }

    private static GatewayBudgetExhaustedException refused(RequestTier tier) {
        return new GatewayBudgetExhaustedException(tier, "env-1", Duration.ofSeconds(30));
    }

    private void setAnchor(long lastFetched, long expected) throws Exception {
        Field f = Bot.class.getDeclaredField("lastFetchedBalance");
        f.setAccessible(true);
        f.setLong(bot, lastFetched);
        Field e = Bot.class.getDeclaredField("expectedCurrentBalance");
        e.setAccessible(true);
        ((AtomicLong) e.get(bot)).set(expected);
    }

    @Test
    @DisplayName("a deferred drift read returns the local estimate and changes no anchor")
    void aDeferredDriftReadReturnsTheLocalEstimate() throws Exception {
        setAnchor(DEPOSIT, DEPOSIT - 400_000L);   // drift 400k, well past the 50k sync band
        when(apiGatewayClient.getBalanceIfAdmitted(anyString(), anyString(), anyString(), any()))
                .thenReturn(OptionalLong.empty());

        long observed = bot.checkBalanceExposed();

        assertThat(observed)
                .as("the bot plays on for another round rather than stalling a library thread")
                .isEqualTo(DEPOSIT - 400_000L);
        assertThat(bot.getLastFetchedBalance())
                .as("the server anchor must NOT move on a read that never happened — moving it "
                        + "would make bot_money_drained_total accrue against a figure we invented")
                .isEqualTo(DEPOSIT);
        verify(apiGatewayClient, never()).getBalance(anyString(), anyString(), anyString(),
                eq(RequestTier.DEFAULT), any());
    }

    @Test
    @DisplayName("after a deferral, a deposit first re-reads at PRIORITIZED and is suppressed if the fresh figure is fine")
    void aDeferralForcesAPreDepositRefreshThatCanSuppressTheDeposit() throws Exception {
        // The shape AD-10 exists for. Local estimate says "below minimum, deposit"; the server
        // says otherwise, and the server is right — the local figure is an estimate the budget
        // stopped us refreshing.
        setAnchor(DEPOSIT, 100_000L);             // local estimate below the 10%-of-deposit minimum
        when(apiGatewayClient.getBalanceIfAdmitted(anyString(), anyString(), anyString(), any()))
                .thenReturn(OptionalLong.empty());
        bot.checkBalanceExposed();                // marks the figure stale

        when(apiGatewayClient.getBalance(anyString(), anyString(), anyString(),
                eq(RequestTier.PRIORITIZED), any(), any())).thenReturn(DEPOSIT);

        assertThat(bot.depositIsWarrantedExposed(100_000L))
                .as("the fresh figure is above the minimum, so no money moves")
                .isFalse();
        verify(apiGatewayClient).getBalance(anyString(), anyString(), anyString(),
                eq(RequestTier.PRIORITIZED), any(), any());
        assertThat(bot.getExpectedBalance())
                .as("and the refresh corrected the local model on the way past")
                .isEqualTo(DEPOSIT);
    }

    @Test
    @DisplayName("after a deferral, a deposit still happens when the fresh figure confirms it")
    void aDeferralDoesNotBlockALegitimateDeposit() throws Exception {
        setAnchor(DEPOSIT, 100_000L);
        when(apiGatewayClient.getBalanceIfAdmitted(anyString(), anyString(), anyString(), any()))
                .thenReturn(OptionalLong.empty());
        bot.checkBalanceExposed();

        when(apiGatewayClient.getBalance(anyString(), anyString(), anyString(),
                eq(RequestTier.PRIORITIZED), any())).thenReturn(50_000L);

        assertThat(bot.depositIsWarrantedExposed(100_000L))
                .as("the server agrees the bot is broke — AD-10 delays the decision, it does not "
                        + "veto it, or an auto-deposit group would quietly stop topping up")
                .isTrue();
    }

    @Test
    @DisplayName("with no deferral, the deposit decision costs no extra request at all")
    void aFreshFigureNeedsNoRefresh() throws Exception {
        setAnchor(100_000L, 100_000L);            // read this round, inside the sync band

        assertThat(bot.depositIsWarrantedExposed(100_000L)).isTrue();

        verify(apiGatewayClient, never()).getBalance(anyString(), anyString(), anyString(),
                eq(RequestTier.PRIORITIZED), any(), any());
    }

    @Test
    @DisplayName("a refused pre-deposit refresh means no deposit this round, and no failure")
    void aRefusedPreDepositRefreshSkipsTheRound() throws Exception {
        setAnchor(DEPOSIT, 100_000L);
        when(apiGatewayClient.getBalanceIfAdmitted(anyString(), anyString(), anyString(), any()))
                .thenReturn(OptionalLong.empty());
        bot.checkBalanceExposed();

        when(apiGatewayClient.getBalance(anyString(), anyString(), anyString(),
                eq(RequestTier.PRIORITIZED), any(), any())).thenThrow(refused(RequestTier.PRIORITIZED));

        assertThat(bot.depositIsWarrantedExposed(100_000L))
                .as("no deposit against a figure we could not refresh. Nothing is lost: the local "
                        + "balance is still below the minimum, so the next round re-enters here.")
                .isFalse();
        verify(metrics, never()).incBotAutoDeposit(anyBoolean());
    }

    @Test
    @DisplayName("a refused deposit is skipped, not counted as a failed deposit")
    void aRefusedDepositIsNotAFailedDeposit() throws Exception {
        setAnchor(100_000L, 100_000L);
        when(apiGatewayClient.deposit(anyString(), anyLong(), any(), any(), any()))
                .thenThrow(refused(RequestTier.PRIORITIZED));

        bot.deposit();

        verify(metrics, never()).incBotAutoDeposit(anyBoolean());
        assertThat(bot.getExpectedBalance())
                .as("nothing moved, so the local model must not pretend otherwise")
                .isEqualTo(100_000L);
    }

    @Test
    @DisplayName("a successful deposit whose confirming read is refused marks the figure stale")
    void aRefusedConfirmingReadMarksTheFigureStale() throws Exception {
        setAnchor(100_000L, 100_000L);
        when(apiGatewayClient.deposit(anyString(), anyLong(), any(), any(), any())).thenReturn(true);
        when(apiGatewayClient.getBalance(anyString(), anyString(), anyString(),
                eq(RequestTier.PRIORITIZED), any(), any())).thenThrow(refused(RequestTier.PRIORITIZED));

        bot.deposit();

        // The money moved, so the deposit is a success and must be counted as one — throwing out
        // of a successful deposit is what would make the group's auto-deposit aggregate lie.
        verify(metrics).incBotAutoDeposit(true);
        assertThat(bot.getLastFetchedBalance())
                .as("...but the anchor is still the pre-deposit figure, which is now known to be "
                        + "wrong. The staleness flag is what stops it funding a SECOND top-up.")
                .isEqualTo(100_000L);
        assertThat(bot.depositIsWarrantedExposed(100_000L))
                .as("the next round's decision therefore re-reads rather than trusting it")
                .isFalse();   // the stubbed refresh still throws, so: no deposit this round
    }

    @Test
    @DisplayName("all three session-path PRIORITIZED calls carry the watchdog-bounded wait (F1)")
    void everySessionPathCallIsBounded() throws Exception {
        // Review F1. The bound being computed correctly is one thing (BotSessionBudgetWaitTest);
        // it reaching all three call sites is the other, and a missed one is invisible — the code
        // still works, it just parks for ten minutes on a thread watched at three.
        //
        // The fixture's watchdog is 120 s, so the derived bound is 30 s.
        Duration expected = Duration.ofSeconds(30);
        assertThat(bot.sessionBudgetWaitExposed()).isEqualTo(expected);

        setAnchor(DEPOSIT, 100_000L);
        when(apiGatewayClient.getBalanceIfAdmitted(anyString(), anyString(), anyString(), any()))
                .thenReturn(OptionalLong.empty());
        bot.checkBalanceExposed();                       // marks the figure stale
        when(apiGatewayClient.getBalance(anyString(), anyString(), anyString(),
                eq(RequestTier.PRIORITIZED), any(), any())).thenReturn(50_000L);
        when(apiGatewayClient.deposit(anyString(), anyLong(), any(), any(), any())).thenReturn(true);

        assertThat(bot.depositIsWarrantedExposed(100_000L)).isTrue();
        // 1. the pre-deposit refresh
        verify(apiGatewayClient).getBalance(anyString(), anyString(), anyString(),
                eq(RequestTier.PRIORITIZED), any(), eq(expected));

        bot.deposit();
        // 2. the deposit itself and 3. the confirming read
        verify(apiGatewayClient).deposit(anyString(), anyLong(), eq(RequestTier.PRIORITIZED),
                any(), eq(expected));
        verify(apiGatewayClient, org.mockito.Mockito.times(2)).getBalance(anyString(), anyString(),
                anyString(), eq(RequestTier.PRIORITIZED), any(), eq(expected));

        // And nothing on this path may reach the unbounded overload, which is what "the tier's own
        // ten minutes" would look like.
        verify(apiGatewayClient, never()).getBalance(anyString(), anyString(), anyString(),
                eq(RequestTier.PRIORITIZED), any());
        verify(apiGatewayClient, never()).deposit(anyString(), anyLong(),
                eq(RequestTier.PRIORITIZED), any());
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }

    /** Minimal concrete bot: no scenario, no game messages, no threads. */
    static class BudgetBot extends Bot {
        @Override protected void initializeSubclass() {}
        @Override protected Scenario botBehaviorScenario() { return null; }
        @Override protected void onStart() {}

        long checkBalanceExposed() { return checkBalance(); }

        boolean depositIsWarrantedExposed(long local) { return depositIsWarranted(local); }

        Duration sessionBudgetWaitExposed() { return sessionBudgetWait(); }
    }
}
