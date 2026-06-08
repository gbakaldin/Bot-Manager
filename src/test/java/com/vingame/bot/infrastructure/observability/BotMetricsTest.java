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
    private static final String GAME_TYPE = "BauCua";

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
                .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
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

    @Test
    void incBetPlaced_incrementsBothCountAndAmount() {
        setBotMdc();
        metrics.incBetPlaced(500_000L);
        metrics.incBetPlaced(250_000L);

        Counter bets = registry.find(BotMetrics.BOT_BETS_PLACED_TOTAL).counter();
        Counter amount = registry.find(BotMetrics.BOT_BET_AMOUNT_TOTAL).counter();

        assertThat(bets).isNotNull();
        assertThat(bets.count()).isEqualTo(2.0);
        assertThat(amount).isNotNull();
        assertThat(amount.count()).isEqualTo(750_000.0);
    }

    /* ----- ENDGAME_METRICS Phase A — batch overload for HasBetTotals dispatch ----- */

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
    void incBetsPlaced_zeroOrNegativeCountIsNoOpAndCreatesNoCounter() {
        setBotMdc();
        metrics.incBetsPlaced(0, 0L);
        metrics.incBetsPlaced(-3, 100L);

        Counter bets = registry.find(BotMetrics.BOT_BETS_PLACED_TOTAL).counter();
        Counter amount = registry.find(BotMetrics.BOT_BET_AMOUNT_TOTAL).counter();

        // Defensive contract: zero/negative count must never reach the registry,
        // mirroring the dead-seconds silent-drop pattern.
        assertThat(bets).isNull();
        assertThat(amount).isNull();
    }

    @Test
    void incBetsPlaced_sharesTimeSeriesWithSingleBetIncBetPlaced() {
        // Both incBetPlaced(long) and incBetsPlaced(int, long) target the same
        // two meters with the same MDC tag shape — they must aggregate into the
        // same time series (no series split when the bet-counter callsite
        // migrates from creditBalance to onEndGame in Phase B).
        setBotMdc();
        metrics.incBetPlaced(100L);
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
        assertThat(hasGroup).isFalse();
        assertThat(hasEnv).isFalse();
        assertThat(hasGame).isFalse();
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
                BotMdc.GAME_TYPE
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

    /* ----- Phase 5 — game-aggregate counters with gameType-only tags ----- */

    @Test
    void incGameTotalWinnings_attachesOnlyGameTypeTag() {
        setBotMdc(); // populate all MDC keys, including botGroupId & environmentId
        metrics.incGameTotalWinnings(1_000L);

        Counter c = registry.find(BotMetrics.GAME_TOTAL_WINNINGS_TOTAL)
                .tag(BotMdc.GAME_TYPE, GAME_TYPE)
                .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1_000.0);

        // Critical: AD 5 — botGroupId / environmentId MUST NOT be tagged.
        var keys = c.getId().getTags().stream().map(Tag::getKey).toList();
        assertThat(keys).doesNotContain(BotMdc.BOT_GROUP_ID, BotMdc.ENVIRONMENT_ID,
                BotMdc.BOT_USER_NAME, BotMdc.BOT_ID);
        // gameType IS present.
        assertThat(keys).contains(BotMdc.GAME_TYPE);
    }

    @Test
    void incGameTotalBetAmount_attachesOnlyGameTypeTag() {
        setBotMdc();
        metrics.incGameTotalBetAmount(2_500L);

        Counter c = registry.find(BotMetrics.GAME_TOTAL_BET_AMOUNT_TOTAL)
                .tag(BotMdc.GAME_TYPE, GAME_TYPE)
                .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(2_500.0);

        var keys = c.getId().getTags().stream().map(Tag::getKey).toList();
        assertThat(keys).doesNotContain(BotMdc.BOT_GROUP_ID, BotMdc.ENVIRONMENT_ID,
                BotMdc.BOT_USER_NAME, BotMdc.BOT_ID);
        assertThat(keys).contains(BotMdc.GAME_TYPE);
    }

    @Test
    void incGameTotalWinnings_emptyMdc_omitsGameTypeTag() {
        // No MDC set — Tags should be empty, but registration must still succeed.
        metrics.incGameTotalWinnings(100L);

        Counter c = registry.find(BotMetrics.GAME_TOTAL_WINNINGS_TOTAL).counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(100.0);

        boolean hasGameType = c.getId().getTags().stream()
                .anyMatch(t -> BotMdc.GAME_TYPE.equals(t.getKey()));
        assertThat(hasGameType).isFalse();
    }
}
