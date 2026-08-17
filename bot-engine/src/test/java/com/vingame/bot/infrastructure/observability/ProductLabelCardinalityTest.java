package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct verification of VIPTALK_ALERTING_V2 AD-V1's load-bearing cardinality claim:
 * adding {@code product} to the {@code bot_*} counters creates <b>zero new time
 * series</b>, because {@code product} is functionally determined by {@code gameId} /
 * {@code environmentId}, which are already on every one of those series.
 * <p>
 * The claim is not "N increments from one bot make one series" — that is true of any
 * label and proves nothing. It is an <b>A/B over a whole simulated fleet</b>: replay the
 * same traffic (several groups, several games, many bots, several environments) twice,
 * once with the label and once without, and compare the resulting series counts. Equal
 * counts is the claim; a difference is a cardinality regression, and would show up here
 * even if every other assertion in the suite still passed.
 * <p>
 * The one way the claim can break is a source that is <em>not</em> functionally
 * determined — e.g. deriving the product from something other than the bot's own
 * {@code Game}. {@link #twoProductsUnderOneGameIdWouldSplitTheSeries()} pins what that
 * failure looks like, so the guarantee is understood as a property of the source and not
 * a property of Micrometer.
 */
@DisplayName("`product` label — AD-V1 zero-new-time-series claim")
class ProductLabelCardinalityTest {

    /** gameId → (environmentId, product): the functional dependency AD-V1 rests on. */
    private static final Map<String, String[]> GAMES = Map.of(
            "game-a", new String[]{"env-tip", "116"},
            "game-b", new String[]{"env-tip", "116"},
            "game-c", new String[]{"env-bom", "097"},
            "game-d", new String[]{"env-rik", "114"});

    private static final List<String> COMMANDS = List.of("startGame", "endGame", "updateBet");

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    /**
     * Replays a fleet's worth of counter traffic and returns how many series it produced.
     *
     * @param withProduct whether each bot carries the {@code product} MDC key.
     */
    private int seriesCount(boolean withProduct) {
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new BotMdcTagsMeterFilter());
        BotMetrics metrics = new BotMetrics(registry);

        for (String gameId : GAMES.keySet().stream().sorted().toList()) {
            String environmentId = GAMES.get(gameId)[0];
            String product = GAMES.get(gameId)[1];
            for (int group = 0; group < 3; group++) {
                for (int bot = 0; bot < 20; bot++) {
                    MDC.clear();
                    MDC.put(BotMdc.BOT_GROUP_ID, "group-" + group);
                    MDC.put(BotMdc.ENVIRONMENT_ID, environmentId);
                    MDC.put(BotMdc.GAME_TYPE, "BETTING_MINI");
                    MDC.put(BotMdc.GAME_ID, gameId);
                    MDC.put(BotMdc.GAME_NAME, "Game " + gameId);
                    // Production sources this from the bot's own Game document
                    // (Bot.productCode()), which is the same object gameId/gameName
                    // come from — that is what makes it functionally determined.
                    if (withProduct) {
                        MDC.put(BotMdc.PRODUCT, product);
                    }
                    COMMANDS.forEach(metrics::incBotMessage);
                    metrics.incVerifyToken(bot % 7 == 0);
                    metrics.incLogin(true);
                }
            }
        }
        MDC.clear();
        return (int) registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().startsWith("bot_"))
                .count();
    }

    @Test
    @DisplayName("a whole fleet's traffic yields the same number of series with and without `product`")
    void productLabelAddsNoSeriesAcrossAFleet() {
        int without = seriesCount(false);
        int with = seriesCount(true);

        assertThat(without).as("sanity: the replay must actually produce series").isGreaterThan(10);
        assertThat(with)
                .as("AD-V1: `product` rides existing series; it must not create any")
                .isEqualTo(without);
    }

    @Test
    @DisplayName("every series that gained the label kept its identity tags")
    void existingIdentityTagsAreUnchanged() {
        // Series identity churns once at the deploy that introduces the label; what must
        // NOT happen is a tag being dropped or renamed in the process, which would break
        // `sum by(gameId)` dashboards rather than just re-identifying the series.
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new BotMdcTagsMeterFilter());
        BotMetrics metrics = new BotMetrics(registry);

        MDC.put(BotMdc.BOT_GROUP_ID, "group-0");
        MDC.put(BotMdc.ENVIRONMENT_ID, "env-tip");
        MDC.put(BotMdc.GAME_TYPE, "BETTING_MINI");
        MDC.put(BotMdc.GAME_ID, "game-a");
        MDC.put(BotMdc.GAME_NAME, "Bau Cua");
        MDC.put(BotMdc.PRODUCT, "116");
        metrics.incBotMessage("endGame");

        assertThat(registry.find(BotMetrics.BOT_MESSAGES_TOTAL).counter().getId().getTags())
                .extracting("key")
                .contains(BotMdc.BOT_GROUP_ID, BotMdc.ENVIRONMENT_ID, BotMdc.GAME_TYPE,
                        BotMdc.GAME_ID, BotMdc.GAME_NAME, BotMdc.PRODUCT, "cmd");
    }

    @Test
    @DisplayName("the guarantee is the functional dependency, not the label: two products on one gameId would split")
    void twoProductsUnderOneGameIdWouldSplitTheSeries() {
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new BotMdcTagsMeterFilter());
        BotMetrics metrics = new BotMetrics(registry);

        for (String product : List.of("116", "097")) {
            MDC.clear();
            MDC.put(BotMdc.BOT_GROUP_ID, "group-0");
            MDC.put(BotMdc.ENVIRONMENT_ID, "env-tip");
            MDC.put(BotMdc.GAME_TYPE, "BETTING_MINI");
            MDC.put(BotMdc.GAME_ID, "game-a");
            MDC.put(BotMdc.GAME_NAME, "Bau Cua");
            MDC.put(BotMdc.PRODUCT, product);
            metrics.incBotMessage("endGame");
        }

        // Documented, deliberately RED-if-it-ever-happens: this is only reachable if the
        // product stops being read off the bot's own Game (or a Game document's
        // productCode is edited under a live fleet, which churns rather than doubles).
        assertThat(registry.find(BotMetrics.BOT_MESSAGES_TOTAL).counters())
                .as("a non-functionally-determined product source is what would cost cardinality")
                .hasSize(2);
    }
}
