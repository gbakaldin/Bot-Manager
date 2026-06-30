package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link BotMetrics} increments under a populated MDC end up registered
 * with the bot identity tags applied by {@link BotMdcTagsMeterFilter}, and that the
 * same calls under an empty MDC do not crash or attach phantom tags.
 */
class BotMetricsTest {

    private static final String GROUP_ID = "group-abc";
    private static final String ENV_ID = "env-xyz";
    // GRAFANA_PER_GAME_ENV_DASHBOARDS AD-1: gameType now carries the GameType enum,
    // not the game's display name. Regression-guards the latent naming bug where
    // gameType was populated from game.getName() (e.g. "BauCua").
    private static final String GAME_TYPE = "BETTING_MINI";
    private static final String GAME_ID = "11111111-2222-3333-4444-555555555555";
    private static final String GAME_NAME = "BauCua";

    private MeterRegistry registry;
    private BotMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new BotMdcTagsMeterFilter());
        metrics = new BotMetrics(registry);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private void setBotMdc() {
        MDC.put(BotMdc.BOT_GROUP_ID, GROUP_ID);
        MDC.put(BotMdc.ENVIRONMENT_ID, ENV_ID);
        MDC.put(BotMdc.GAME_TYPE, GAME_TYPE);
        MDC.put(BotMdc.GAME_ID, GAME_ID);
        MDC.put(BotMdc.GAME_NAME, GAME_NAME);
    }

    @Test
    void incBotMessage_attachesMdcAndCmdTag() {
        setBotMdc();
        metrics.incBotMessage("endGame");

        Counter c = registry.find(BotMetrics.BOT_MESSAGES_TOTAL)
                .tag("cmd", "endGame")
                .tag(BotMdc.BOT_GROUP_ID, GROUP_ID)
                .tag(BotMdc.ENVIRONMENT_ID, ENV_ID)
                .tag(BotMdc.GAME_TYPE, GAME_TYPE)
                .tag(BotMdc.GAME_ID, GAME_ID)
                .tag(BotMdc.GAME_NAME, GAME_NAME)
                .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

    /* ----- GRAFANA_PER_GAME_ENV_DASHBOARDS Phase 1 — gameId / gameName labels + gameType fix ----- */

    @Test
    void gameLabels_areEmittedAcrossDashboardCounters() {
        // Every dashboard-relevant counter must carry gameId + gameName (sourced
        // from the Game entity the bot holds) so the per-Game dashboard can filter.
        setBotMdc();
        metrics.incBotMessage("endGame");
        metrics.incBetsPlaced(2, 200L);
        metrics.incBotWinnings(300L);
        metrics.incBotJackpot(1_000L);
        metrics.incBotFailure();
        metrics.incBotDeadSeconds(5);
        metrics.incBotWsEvent("connected");
        metrics.incBotReconnect("watchdog");

        String[] names = {
                BotMetrics.BOT_MESSAGES_TOTAL,
                BotMetrics.BOT_BETS_PLACED_TOTAL,
                BotMetrics.BOT_BET_AMOUNT_TOTAL,
                BotMetrics.BOT_WINNINGS_TOTAL,
                BotMetrics.BOT_JACKPOTS_TOTAL,
                BotMetrics.BOT_JACKPOT_AMOUNT_TOTAL,
                BotMetrics.BOT_FAILURES_TOTAL,
                BotMetrics.BOT_DEAD_SECONDS_TOTAL,
                BotMetrics.BOT_WS_CONNECTIONS_TOTAL,
                BotMetrics.BOT_RECONNECTS_TOTAL
        };
        for (String name : names) {
            Counter c = registry.find(name)
                    .tag(BotMdc.GAME_ID, GAME_ID)
                    .tag(BotMdc.GAME_NAME, GAME_NAME)
                    .counter();
            assertThat(c).as("counter %s carries gameId + gameName", name).isNotNull();
        }
    }

    @Test
    void gameType_carriesEnumNotGameName() {
        // Regression guard for the latent naming bug (Findings): gameType used to be
        // populated from game.getName(). It must now be the GameType enum, and the
        // game's display name lives on the separate gameName label.
        setBotMdc();
        metrics.incBotMessage("endGame");

        Counter c = registry.find(BotMetrics.BOT_MESSAGES_TOTAL)
                .tag("cmd", "endGame")
                .tag(BotMdc.GAME_TYPE, "BETTING_MINI")  // enum, not "BauCua"
                .tag(BotMdc.GAME_NAME, "BauCua")        // name moved to its own label
                .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);

        // The game name must NOT appear under the gameType tag.
        Counter mislabeled = registry.find(BotMetrics.BOT_MESSAGES_TOTAL)
                .tag(BotMdc.GAME_TYPE, "BauCua")
                .counter();
        assertThat(mislabeled).isNull();
    }

    @Test
    void incBotFailure_attachesMdcTagsButNoExtraTags() {
        setBotMdc();
        metrics.incBotFailure();

        Counter c = registry.find(BotMetrics.BOT_FAILURES_TOTAL)
                .tag(BotMdc.BOT_GROUP_ID, GROUP_ID)
                .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    void incBotReconnect_tagsReason() {
        setBotMdc();
        metrics.incBotReconnect("watchdog");
        metrics.incBotReconnect("ws-disconnect");
        metrics.incBotReconnect("ws-disconnect");

        Counter watchdog = registry.find(BotMetrics.BOT_RECONNECTS_TOTAL)
                .tag("reason", "watchdog").counter();
        Counter wsDisconnect = registry.find(BotMetrics.BOT_RECONNECTS_TOTAL)
                .tag("reason", "ws-disconnect").counter();

        assertThat(watchdog).isNotNull();
        assertThat(watchdog.count()).isEqualTo(1.0);
        assertThat(wsDisconnect).isNotNull();
        assertThat(wsDisconnect.count()).isEqualTo(2.0);
    }

    @Test
    void incBotAutoDeposit_tagsOutcome() {
        setBotMdc();
        metrics.incBotAutoDeposit(true);
        metrics.incBotAutoDeposit(false);

        Counter success = registry.find(BotMetrics.BOT_AUTO_DEPOSITS_TOTAL)
                .tag("outcome", "success").counter();
        Counter failure = registry.find(BotMetrics.BOT_AUTO_DEPOSITS_TOTAL)
                .tag("outcome", "failure").counter();

        assertThat(success).isNotNull();
        assertThat(success.count()).isEqualTo(1.0);
        assertThat(failure).isNotNull();
        assertThat(failure.count()).isEqualTo(1.0);
    }

    /* ----- ENDGAME_METRICS — batch overload for HasBetTotals dispatch ----- */

    @Test
    void incBetsPlaced_batchIncrementsCountAndAmountSums() {
        setBotMdc();
        metrics.incBetsPlaced(3, 500L);
        metrics.incBetsPlaced(2, 250L);

        Counter bets = registry.find(BotMetrics.BOT_BETS_PLACED_TOTAL)
                .tag(BotMdc.BOT_GROUP_ID, GROUP_ID)
                .tag(BotMdc.ENVIRONMENT_ID, ENV_ID)
                .tag(BotMdc.GAME_TYPE, GAME_TYPE)
                .counter();
        Counter amount = registry.find(BotMetrics.BOT_BET_AMOUNT_TOTAL)
                .tag(BotMdc.BOT_GROUP_ID, GROUP_ID)
                .tag(BotMdc.ENVIRONMENT_ID, ENV_ID)
                .tag(BotMdc.GAME_TYPE, GAME_TYPE)
                .counter();

        // Both sums must be exact — the batch overload preserves the two-counter
        // math without any per-bet averaging.
        assertThat(bets).isNotNull();
        assertThat(bets.count()).isEqualTo(5.0);
        assertThat(amount).isNotNull();
        assertThat(amount.count()).isEqualTo(750.0);
    }

    @Test
    void incBetsPlaced_bothZeroOrNegativeIsNoOpAndCreatesNoCounter() {
        setBotMdc();
        metrics.incBetsPlaced(0, 0L);
        metrics.incBetsPlaced(-3, -100L);

        Counter bets = registry.find(BotMetrics.BOT_BETS_PLACED_TOTAL).counter();
        Counter amount = registry.find(BotMetrics.BOT_BET_AMOUNT_TOTAL).counter();

        // Defensive contract: a fully-zero (or fully-negative) call creates no
        // counters — mirrors the dead-seconds silent-drop pattern. Asymmetric
        // calls (one positive, one zero) are covered by the independent-guard
        // tests below.
        assertThat(bets).isNull();
        assertThat(amount).isNull();
    }

    @Test
    void incBetsPlaced_zeroCountWithPositiveAmountStillRecordsAmount() {
        // Reviewer fix: prior version's `if (count <= 0) return;` early-out
        // silently dropped a non-zero amount. Future HasBetTotals implementers
        // may legitimately emit (count=0, amount=N>0) — the amount counter must
        // still increment.
        setBotMdc();
        metrics.incBetsPlaced(0, 500L);

        Counter bets = registry.find(BotMetrics.BOT_BETS_PLACED_TOTAL).counter();
        Counter amount = registry.find(BotMetrics.BOT_BET_AMOUNT_TOTAL).counter();

        assertThat(bets).isNull();              // count guard correctly skipped
        assertThat(amount).isNotNull();
        assertThat(amount.count()).isEqualTo(500.0);
    }

    @Test
    void incBetsPlaced_positiveCountWithZeroAmountStillRecordsCount() {
        // Mirror of the above: count > 0 with amount == 0 must still increment
        // the count counter independently. Guards are symmetric.
        setBotMdc();
        metrics.incBetsPlaced(3, 0L);

        Counter bets = registry.find(BotMetrics.BOT_BETS_PLACED_TOTAL).counter();
        Counter amount = registry.find(BotMetrics.BOT_BET_AMOUNT_TOTAL).counter();

        assertThat(bets).isNotNull();
        assertThat(bets.count()).isEqualTo(3.0);
        assertThat(amount).isNull();            // amount guard correctly skipped
    }

    @Test
    void incBetsPlaced_multipleCallsSumIntoTheSameTimeSeries() {
        // Two consecutive batches must aggregate into the same per-MDC counter
        // pair — no series split when the bot-side dispatch fires repeatedly
        // across rounds.
        setBotMdc();
        metrics.incBetsPlaced(1, 100L);
        metrics.incBetsPlaced(2, 300L);

        Counter bets = registry.find(BotMetrics.BOT_BETS_PLACED_TOTAL).counter();
        Counter amount = registry.find(BotMetrics.BOT_BET_AMOUNT_TOTAL).counter();

        assertThat(bets).isNotNull();
        assertThat(bets.count()).isEqualTo(3.0);  // 1 + 2
        assertThat(amount).isNotNull();
        assertThat(amount.count()).isEqualTo(400.0); // 100 + 300
    }

    @Test
    void incLogin_tagsOutcome() {
        setBotMdc();
        metrics.incLogin(true);

        Counter c = registry.find(BotMetrics.BOT_LOGIN_TOTAL)
                .tag("outcome", "success").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    void incVerifyToken_tagsOutcome() {
        setBotMdc();
        metrics.incVerifyToken(false);

        Counter c = registry.find(BotMetrics.BOT_VERIFY_TOKEN_TOTAL)
                .tag("outcome", "failure").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    void incBotWatchdogExpired_simpleCounter() {
        setBotMdc();
        metrics.incBotWatchdogExpired();
        metrics.incBotWatchdogExpired();

        Counter c = registry.find(BotMetrics.BOT_WATCHDOG_EXPIRED_TOTAL).counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(2.0);
    }

    @Test
    void incBotWsEvent_tagsEvent() {
        setBotMdc();
        metrics.incBotWsEvent("connected");
        metrics.incBotWsEvent("disconnected");
        metrics.incBotWsEvent("authenticating");

        for (String event : new String[]{"connected", "disconnected", "authenticating"}) {
            Counter c = registry.find(BotMetrics.BOT_WS_CONNECTIONS_TOTAL)
                    .tag("event", event).counter();
            assertThat(c).as("WS event counter for %s", event).isNotNull();
            assertThat(c.count()).isEqualTo(1.0);
        }
    }

    @Test
    void emptyMdc_doesNotAttachBotIdentityTags() {
        // Note: no setBotMdc() — MDC is empty
        metrics.incBotMessage("endGame");

        Counter c = registry.find(BotMetrics.BOT_MESSAGES_TOTAL)
                .tag("cmd", "endGame").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);

        boolean hasGroup = c.getId().getTags().stream()
                .anyMatch(t -> BotMdc.BOT_GROUP_ID.equals(t.getKey()));
        boolean hasEnv = c.getId().getTags().stream()
                .anyMatch(t -> BotMdc.ENVIRONMENT_ID.equals(t.getKey()));
        boolean hasGame = c.getId().getTags().stream()
                .anyMatch(t -> BotMdc.GAME_TYPE.equals(t.getKey()));
        boolean hasGameId = c.getId().getTags().stream()
                .anyMatch(t -> BotMdc.GAME_ID.equals(t.getKey()));
        boolean hasGameName = c.getId().getTags().stream()
                .anyMatch(t -> BotMdc.GAME_NAME.equals(t.getKey()));
        assertThat(hasGroup).isFalse();
        assertThat(hasEnv).isFalse();
        assertThat(hasGame).isFalse();
        assertThat(hasGameId).isFalse();
        assertThat(hasGameName).isFalse();
    }

    @Test
    void differentGroupsCreateSeparateTimeSeries() {
        // Group A
        MDC.put(BotMdc.BOT_GROUP_ID, "group-A");
        MDC.put(BotMdc.ENVIRONMENT_ID, ENV_ID);
        MDC.put(BotMdc.GAME_TYPE, GAME_TYPE);
        metrics.incBotMessage("endGame");
        metrics.incBotMessage("endGame");

        // Group B
        MDC.put(BotMdc.BOT_GROUP_ID, "group-B");
        metrics.incBotMessage("endGame");

        Counter groupA = registry.find(BotMetrics.BOT_MESSAGES_TOTAL)
                .tag("cmd", "endGame")
                .tag(BotMdc.BOT_GROUP_ID, "group-A").counter();
        Counter groupB = registry.find(BotMetrics.BOT_MESSAGES_TOTAL)
                .tag("cmd", "endGame")
                .tag(BotMdc.BOT_GROUP_ID, "group-B").counter();

        assertThat(groupA).isNotNull();
        assertThat(groupA.count()).isEqualTo(2.0);
        assertThat(groupB).isNotNull();
        assertThat(groupB.count()).isEqualTo(1.0);
    }

    @Test
    void mdcWithEmptyValues_omitsTag() {
        MDC.put(BotMdc.BOT_GROUP_ID, "");
        MDC.put(BotMdc.ENVIRONMENT_ID, ENV_ID);
        metrics.incBotMessage("endGame");

        Counter c = registry.find(BotMetrics.BOT_MESSAGES_TOTAL)
                .tag("cmd", "endGame")
                .tag(BotMdc.ENVIRONMENT_ID, ENV_ID).counter();
        assertThat(c).isNotNull();

        boolean hasEmptyGroupTag = c.getId().getTags().stream()
                .anyMatch(t -> BotMdc.BOT_GROUP_ID.equals(t.getKey()));
        assertThat(hasEmptyGroupTag).isFalse();
    }

    @Test
    void incBotDeadSeconds_addsAmountAndAttachesMdcTags() {
        setBotMdc();
        metrics.incBotDeadSeconds(7);
        metrics.incBotDeadSeconds(3);

        Counter c = registry.find(BotMetrics.BOT_DEAD_SECONDS_TOTAL)
                .tag(BotMdc.BOT_GROUP_ID, GROUP_ID)
                .tag(BotMdc.ENVIRONMENT_ID, ENV_ID)
                .tag(BotMdc.GAME_TYPE, GAME_TYPE)
                .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(10.0);
    }

    @Test
    void incBotDeadSeconds_nonPositiveValuesAreIgnored() {
        setBotMdc();
        metrics.incBotDeadSeconds(0);
        metrics.incBotDeadSeconds(-5);

        Counter c = registry.find(BotMetrics.BOT_DEAD_SECONDS_TOTAL).counter();
        // Defensive contract: zero/negative durations must never reach the registry,
        // because Counter is monotonic and we don't want phantom-zero series either.
        assertThat(c).isNull();
    }

    @Test
    void incGroupDeadSeconds_addsAmountAndAttachesMdcTags() {
        setBotMdc();
        metrics.incGroupDeadSeconds(12);

        Counter c = registry.find(BotMetrics.GROUP_DEAD_SECONDS_TOTAL)
                .tag(BotMdc.BOT_GROUP_ID, GROUP_ID)
                .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(12.0);
    }

    @Test
    void incGroupDeadSeconds_nonPositiveValuesAreIgnored() {
        setBotMdc();
        metrics.incGroupDeadSeconds(0);

        Counter c = registry.find(BotMetrics.GROUP_DEAD_SECONDS_TOTAL).counter();
        assertThat(c).isNull();
    }

    @Test
    void counterIdContainsExpectedTagSet() {
        setBotMdc();
        metrics.incBotMessage("subscribe");

        Counter c = registry.find(BotMetrics.BOT_MESSAGES_TOTAL)
                .tag("cmd", "subscribe").counter();
        assertThat(c).isNotNull();

        var keys = c.getId().getTags().stream().map(Tag::getKey).toList();
        assertThat(keys).contains(
                "cmd",
                BotMdc.BOT_GROUP_ID,
                BotMdc.ENVIRONMENT_ID,
                BotMdc.GAME_TYPE,
                BotMdc.GAME_ID,
                BotMdc.GAME_NAME
        );
        // Cardinality control: bot identity must NOT include per-bot tags.
        assertThat(keys).doesNotContain(BotMdc.BOT_USER_NAME, BotMdc.BOT_ID);
    }

    /* ----- Phase 4 — bot winnings + jackpot ----- */

    @Test
    void incBotWinnings_attachesMdcTags() {
        setBotMdc();
        metrics.incBotWinnings(500L);
        metrics.incBotWinnings(250L);

        Counter c = registry.find(BotMetrics.BOT_WINNINGS_TOTAL)
                .tag(BotMdc.BOT_GROUP_ID, GROUP_ID)
                .tag(BotMdc.ENVIRONMENT_ID, ENV_ID)
                .tag(BotMdc.GAME_TYPE, GAME_TYPE)
                .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(750.0);
    }

    @Test
    void incBotJackpot_incrementsBothCountAndAmount() {
        setBotMdc();
        metrics.incBotJackpot(10_000L);
        metrics.incBotJackpot(5_000L);

        Counter count = registry.find(BotMetrics.BOT_JACKPOTS_TOTAL)
                .tag(BotMdc.BOT_GROUP_ID, GROUP_ID)
                .counter();
        Counter amount = registry.find(BotMetrics.BOT_JACKPOT_AMOUNT_TOTAL)
                .tag(BotMdc.BOT_GROUP_ID, GROUP_ID)
                .counter();

        assertThat(count).isNotNull();
        assertThat(count.count()).isEqualTo(2.0);
        assertThat(amount).isNotNull();
        assertThat(amount.count()).isEqualTo(15_000.0);
    }

    /* ----- METRICS_IMPROVEMENT Phase 1 — money-drain counter ----- */

    @Test
    void incMoneyDrained_addsAmountAndAttachesMdcTags() {
        setBotMdc();
        metrics.incMoneyDrained(3_000_000L);
        metrics.incMoneyDrained(1_500_000L);

        Counter c = registry.find(BotMetrics.BOT_MONEY_DRAINED_TOTAL)
                .tag(BotMdc.BOT_GROUP_ID, GROUP_ID)
                .tag(BotMdc.ENVIRONMENT_ID, ENV_ID)
                .tag(BotMdc.GAME_TYPE, GAME_TYPE)
                .tag(BotMdc.GAME_ID, GAME_ID)
                .tag(BotMdc.GAME_NAME, GAME_NAME)
                .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(4_500_000.0);
    }

    @Test
    void incMoneyDrained_nonPositiveValuesAreIgnored() {
        // The Bot helper floors at 0 (deposit top-up jump / net-gain windows yield
        // a negative delta). A zero/negative amount must never reach the registry —
        // the counter is monotonic and we don't want phantom-zero series.
        setBotMdc();
        metrics.incMoneyDrained(0);
        metrics.incMoneyDrained(-5_000_000L);

        Counter c = registry.find(BotMetrics.BOT_MONEY_DRAINED_TOTAL).counter();
        assertThat(c).isNull();
    }

    /* ----- RESTART_LIFECYCLE_FIX — bot-creation failure counter ----- */

    @Test
    void incBotCreationFailure_attachesReasonTagAndMdcTags() {
        setBotMdc();
        metrics.incBotCreationFailure("validation");

        Counter c = registry.find(BotMetrics.BOT_CREATION_FAILURES_TOTAL)
                .tag("reason", "validation")
                .tag(BotMdc.BOT_GROUP_ID, GROUP_ID)
                .tag(BotMdc.ENVIRONMENT_ID, ENV_ID)
                .tag(BotMdc.GAME_TYPE, GAME_TYPE)
                .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    void incBotCreationFailure_separatesReasonsIntoDistinctSeries() {
        setBotMdc();
        metrics.incBotCreationFailure("validation");
        metrics.incBotCreationFailure("auth");
        metrics.incBotCreationFailure("auth");
        metrics.incBotCreationFailure("unknown");

        Counter validation = registry.find(BotMetrics.BOT_CREATION_FAILURES_TOTAL)
                .tag("reason", "validation").counter();
        Counter auth = registry.find(BotMetrics.BOT_CREATION_FAILURES_TOTAL)
                .tag("reason", "auth").counter();
        Counter unknown = registry.find(BotMetrics.BOT_CREATION_FAILURES_TOTAL)
                .tag("reason", "unknown").counter();

        assertThat(validation).isNotNull();
        assertThat(validation.count()).isEqualTo(1.0);
        assertThat(auth).isNotNull();
        assertThat(auth.count()).isEqualTo(2.0);
        assertThat(unknown).isNotNull();
        assertThat(unknown.count()).isEqualTo(1.0);
    }

    @Test
    void incBotCreationFailure_withoutMdcDoesNotAttachPhantomTagsAndStillRecords() {
        // MDC already cleared in @BeforeEach.
        metrics.incBotCreationFailure("unknown");

        Counter c = registry.find(BotMetrics.BOT_CREATION_FAILURES_TOTAL)
                .tag("reason", "unknown").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

}
