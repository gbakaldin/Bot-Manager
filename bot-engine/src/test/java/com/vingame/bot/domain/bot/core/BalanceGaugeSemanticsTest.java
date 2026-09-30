package com.vingame.bot.domain.bot.core;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VIPTALK_ALERTING_V2 AD-V12 — does {@code group_balance_ratio} mean what
 * {@code GroupBalanceLow} claims it means?
 * <p>
 * Phase 4's gauge is
 * {@code avg(Bot.getExpectedBalance() over connected bots) / depositAmount}, and the
 * rule reads {@code group_balance_ratio < 0.10} as "this group is running out of
 * money". AD-V12 justifies using {@code expectedCurrentBalance} rather than
 * {@code lastFetchedBalance} on the grounds that {@code checkBalance()} re-reads the
 * server whenever the two diverge by more than 1% of the deposit, so the number can
 * never be far from server truth. The tests here check that justification against the
 * source rather than restating it — separately from the gauge plumbing, which
 * {@code BotGroupBehaviorServiceTest} / {@code InfoGaugeRefresherTest} cover with mocks
 * and therefore cannot tell you what a <em>real</em> {@code Bot} reports.
 * <p>
 * The bound holds once a server read has happened. Before that there is none, and the
 * gauge excludes the bot on the strength of the sentinel pairing pinned by
 * {@link #expectedBalanceIsANegativeSentinelBeforeTheFirstServerRead()}.
 */
@DisplayName("group_balance_ratio numerator — Bot.getExpectedBalance() semantics (AD-V12)")
class BalanceGaugeSemanticsTest {

    /**
     * The value {@code Bot.expectedCurrentBalance} is constructed with. Not a named
     * constant in production, so it is pinned here: Phase 4's gauge divides it by the
     * deposit amount, which makes it load-bearing for an alert threshold.
     */
    private static final long UNINITIALISED_EXPECTED_BALANCE = -100_000_000L;

    private static final long DEPOSIT = 5_000_000L;

    private ApiGatewayClient apiGatewayClient;
    private VingameWebSocketClient wsClient;
    private TestBot bot;

    @BeforeEach
    void setUp() {
        apiGatewayClient = mock(ApiGatewayClient.class);
        wsClient = mock(VingameWebSocketClient.class);
        when(apiGatewayClient.getApiGateway()).thenReturn("http://gateway.test");

        bot = new TestBot();
        bot.setClients(apiGatewayClient, mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(configuration(DEPOSIT));
        bot.setMetrics(mock(BotMetrics.class));
        bot.client = wsClient;
        when(wsClient.getAuthToken()).thenReturn("tok");
    }

    /**
     * The {@code Bot}-side fact that Phase 4's gauge has to cope with, and the marker it
     * now copes with it <em>by</em>.
     * <p>
     * A bot that has connected but has not yet completed a round reports
     * {@code -100,000,000} — the constructor sentinel — because
     * {@code expectedCurrentBalance} is only ever written from a server read inside
     * {@code checkBalance()} / {@code deposit(any(), any())}, and both of those run from
     * {@code onNewSession()} (betting/Tai Xiu) or the spin path (slot). Until the first
     * round arrives there has been no server read.
     * <p>
     * {@code listGroupBalances()} filters on {@code isConnected()}, which is true well
     * before the first round, so the sentinel used to reach the gauge:
     * {@code -100,000,000 / 5,000,000 = -20}, and {@code group_balance_ratio < 0.10}
     * fired after its 5 m {@code for:} window — out of contract by the plan's own Phase 4
     * verification, which expects each value in {@code (0, ~1.0]}. Normally harmless
     * (rounds arrive every 30–60 s, well inside {@code for: 5m}); it bit in exactly the
     * case that matters, a game delivering no rounds at all (the tx7 "0 sessions ever"
     * shape), where every connected bot holds the sentinel forever and
     * {@code GroupBalanceLow} fires alongside {@code GameNoRounds}, publishing "average
     * balance at -2,000% of its deposit" into a product room.
     * <p>
     * {@code listGroupBalances()} now counts only bots that have completed a server read,
     * and {@code lastFetchedBalance == -1} is the marker it reads. That pairing is the
     * contract this test pins: <b>a negative {@code expectedBalance} implies a negative
     * {@code lastFetchedBalance}</b>. If a future change ever set one without the other,
     * the filter would silently stop working and the sentinel would be back on the gauge.
     */
    /**
     * Stub <b>both</b> server-balance entry points with one value.
     * <p>
     * Since GATEWAY_REQUEST_BUDGET Phase 3 (AD-10) the <b>first</b> read goes through the
     * blocking {@code getBalance} (ESSENTIAL, on the start path) while every <b>drift</b> re-sync
     * goes through {@code getBalanceIfAdmitted} (DEFAULT, never waits). The 200-round loop below
     * exercises the second one exclusively, so stubbing only the first would have made it measure
     * drift against an unstubbed 0.
     */
    private void stubServerBalance(long value) {
        // Both overloads — see BotTest.stubServerBalance (review F1).
        when(apiGatewayClient.getBalance(eq("tok"), eq("fp-1"), eq("botuser1"), any(), any()))
                .thenReturn(value);
        when(apiGatewayClient.getBalance(eq("tok"), eq("fp-1"), eq("botuser1"), any(), any(), any()))
                .thenReturn(value);
        when(apiGatewayClient.getBalanceIfAdmitted(eq("tok"), eq("fp-1"), eq("botuser1"), any()))
                .thenReturn(java.util.OptionalLong.of(value));
    }

    @Test
    @DisplayName("before the first server read: expected is the negative sentinel AND lastFetched is -1")
    void expectedBalanceIsANegativeSentinelBeforeTheFirstServerRead() {
        assertThat(bot.getExpectedBalance())
                .as("this is the numerator Phase 4's group_balance_ratio divides by the deposit")
                .isEqualTo(UNINITIALISED_EXPECTED_BALANCE)
                .isNegative();

        assertThat(bot.getLastFetchedBalance())
                .as("the marker listGroupBalances() uses to exclude this bot from the average")
                .isNegative();

        // No server read has happened: the group is eligible for the gauge as soon as the
        // socket is up, which is long before the first round.
        verify(apiGatewayClient, never()).getBalance(anyString(), anyString(), anyString(), any(), any());
        verify(apiGatewayClient, never())
                .getBalanceIfAdmitted(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("the first checkBalance() replaces the sentinel with the authoritative server figure")
    void firstRoundSyncsExpectedToTheServer() {
        stubServerBalance(DEPOSIT);

        long balance = bot.checkBalanceExposed();

        // lastFetchedBalance starts at -1, so the very first call is always over the
        // threshold and always fetches — which is why the sentinel window is bounded by
        // the first round rather than lasting forever.
        verify(apiGatewayClient).getBalance(eq("tok"), eq("fp-1"), eq("botuser1"), any(), any());
        assertThat(balance).isEqualTo(DEPOSIT);
        assertThat(bot.getExpectedBalance()).isEqualTo(DEPOSIT);
    }

    /**
     * AD-V12's actual claim, exercised rather than asserted: with {@code checkBalance()}
     * running once per round, the local figure the gauge reads can never drift more than
     * one 1%-of-deposit band away from what the server last said — even in the pathological
     * case where the server settles <em>nothing</em> (the IP-not-whitelisted shape from
     * CLAUDE.md, where bots stake thousands and the balance never moves).
     * <p>
     * That bound, 50,000 on a 5,000,000 deposit, is 1% of the deposit against a 10% alert
     * threshold — immaterial, exactly as AD-V12 argues. So the ratio is trustworthy
     * <em>once the first read has happened</em>.
     */
    @Test
    @DisplayName("after the first read, expected stays within 1% of the deposit of server truth")
    void expectedNeverDriftsMoreThanOneSyncBandFromTheServer() {
        stubServerBalance(DEPOSIT);
        bot.checkBalanceExposed();

        long syncBand = DEPOSIT / 100; // BALANCE_SYNC_PERCENT_OF_DEPOSIT = 1
        long worstDrift = 0;

        // 200 rounds of a bot staking 10,000 a round against a server that never debits.
        for (int round = 0; round < 200; round++) {
            bot.creditBalance(10_000L);
            long observed = bot.checkBalanceExposed();
            worstDrift = Math.max(worstDrift, Math.abs(DEPOSIT - observed));
        }

        assertThat(worstDrift)
                .as("the gauge's numerator vs the server's own figure, worst case over 200 rounds")
                .isLessThanOrEqualTo(syncBand);
        assertThat((double) worstDrift / DEPOSIT)
                .as("expressed as the ratio the 0.10 alert threshold is compared against")
                .isLessThanOrEqualTo(0.01d);
    }

    /**
     * The one non-covered case AD-V12 names explicitly, pinned so the hand-off between
     * the two rules is a tested fact and not a comment: a bot that stops receiving rounds
     * never calls {@code checkBalance()}, so both balance figures freeze at whatever they
     * last were. {@code group_balance_ratio} then reports a stale value indefinitely, and
     * the alert that is supposed to catch it is {@code GameNoRounds}, not this one.
     */
    @Test
    @DisplayName("a bot that stops receiving rounds freezes the number the gauge reads (GameNoRounds' job)")
    void expectedFreezesWhenRoundsStop() {
        stubServerBalance(DEPOSIT);
        bot.checkBalanceExposed();
        assertThat(bot.getExpectedBalance()).isEqualTo(DEPOSIT);

        // Rounds stop: no onNewSession, so no checkBalance, so no further server read —
        // whatever the server does to this account from here is invisible to the gauge.
        assertThat(bot.getExpectedBalance()).isEqualTo(DEPOSIT);
        verify(apiGatewayClient).getBalance(eq("tok"), eq("fp-1"), eq("botuser1"), any(), any());
    }

    @Test
    @DisplayName("the deposit denominator falls back to DEFAULT_DEPOSIT_AMOUNT when unconfigured")
    void depositDenominatorMatchesTheBotsOwnResolution() {
        // The gauge computes its denominator in BotGroupBehaviorService.listGroupBalances
        // by re-implementing Bot.resolveDepositAmount (which is private). The two must
        // agree or the ratio is measured against a different figure than the bot tops up
        // by: 1% of the configured amount is the sync band above, and 10% of it is the
        // bot's own auto-deposit trigger.
        bot.setConfiguration(configuration(0L));
        assertThat(bot.getMinBalanceExposed())
                .as("getMinBalance() = 10% of the resolved deposit amount, unset ⇒ the default")
                .isEqualTo(Bot.DEFAULT_DEPOSIT_AMOUNT * 10 / 100);

        bot.setConfiguration(configuration(DEPOSIT));
        assertThat(bot.getMinBalanceExposed())
                .as("configured ⇒ 10% of the configured amount, the same figure the "
                        + "GroupBalanceLow threshold of 0.10 is expressed in")
                .isEqualTo(DEPOSIT * 10 / 100);
    }

    private static BotConfiguration configuration(long depositAmount) {
        return BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("botuser1").password("pw").fingerprint("fp-1").build())
                .environmentId("env-1")
                .botGroupId("group-1")
                .botIndex(1)
                .game(Game.builder()
                        .id("g1").name("BauCua").gameType(GameType.BETTING_MINI)
                        .pluginName("BauCua").offset(2000).numberOfOptions(6).build())
                .behaviorConfig(BotBehaviorConfig.builder().depositAmount(depositAmount).build())
                .zoneName("MiniGame3")
                .timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .build();
    }

    /** Minimal concrete Bot, mirroring {@code BotTest.TestBot}. */
    static class TestBot extends Bot {
        @Override protected void initializeSubclass() {}
        @Override protected Scenario botBehaviorScenario() { return null; }
        @Override protected void onStart() {}

        long checkBalanceExposed() { return checkBalance(); }

        long getMinBalanceExposed() { return getMinBalance(); }
    }
}
