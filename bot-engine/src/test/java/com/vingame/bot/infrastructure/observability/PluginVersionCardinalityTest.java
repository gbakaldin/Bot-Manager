package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.common.plugin.PluginVersions;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD AD-5, verified the way AD-V1's sibling claim is verified: by an
 * <b>A/B fleet replay</b>, not by an argument.
 * <p>
 * {@code pluginVersion} is on MDC so it reaches the logs, and MDC is exactly the channel
 * {@link BotMdcTagsMeterFilter} turns into metric tags. The decision is that it must
 * <b>not</b> make that trip: the {@code bot_*} counters are already per-group, so adding
 * the label doubles the cardinality of every one of them the moment two versions coexist
 * during a step-5/6 drain — and leaves a stale N-labelled copy of each for Prometheus'
 * full retention window afterwards. The single family that legitimately carries the label
 * is {@code bots_by_plugin_version}, a per-group join gauge built from live iteration in
 * {@code InfoGaugeRefresher}, not from MDC.
 * <p>
 * So the replay runs a whole simulated fleet twice — once with the key on MDC, once
 * without — and asserts the {@code bot_*} series count is <b>identical</b>. Unlike the
 * {@code product} case, where equality followed from a functional dependency, here
 * equality is enforced by omission: it holds only for as long as nobody adds
 * {@code BotMdc.PLUGIN_VERSION} to the filter's tag list. That one-line change is what
 * this test exists to catch, and {@link #twoVersionsInOneGroupWouldSplitEverySeries()}
 * shows what it would cost.
 */
@DisplayName("`pluginVersion` — AD-5 zero-new-series-on-bot_* claim")
class PluginVersionCardinalityTest {

    /** gameId → (environmentId, product), as in {@code ProductLabelCardinalityTest}. */
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
     * Replays a fleet's worth of counter traffic and returns how many {@code bot_*} series
     * it produced.
     *
     * @param pluginVersion the version to put on each bot's MDC, or {@code null} for the
     *                      pre-change baseline
     */
    private int seriesCount(String pluginVersion) {
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new BotMdcTagsMeterFilter());
        BotMetrics metrics = new BotMetrics(registry);

        for (String gameId : GAMES.keySet().stream().sorted().toList()) {
            String environmentId = GAMES.get(gameId)[0];
            String product = GAMES.get(gameId)[1];
            for (int group = 0; group < 3; group++) {
                for (int bot = 0; bot < 20; bot++) {
                    MDC.clear();
                    BotMdc.set("group-" + group, bot, environmentId, product,
                            "BETTING_MINI", gameId, "Game " + gameId, "authtestws" + bot);
                    // Exactly what Bot.initialize() does, one line later.
                    BotMdc.setPluginVersion(pluginVersion);
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
    @DisplayName("a whole fleet's traffic yields the same number of bot_* series with and without it")
    void pluginVersionAddsNoSeriesAcrossAFleet() {
        int without = seriesCount(null);
        int with = seriesCount(PluginVersions.BUILTIN);

        assertThat(without).as("sanity: the replay must actually produce series").isGreaterThan(10);
        assertThat(with)
                .as("AD-5: pluginVersion is on MDC for the logs only; it must not reach the "
                        + "bot_* meters through BotMdcTagsMeterFilter")
                .isEqualTo(without);
    }

    @Test
    @DisplayName("no bot_* series carries a pluginVersion tag at all")
    void noBotSeriesCarriesTheTag() {
        // The series COUNT above would also be equal if the label were applied uniformly to
        // every series (one constant value adds no cardinality today) — and that version of
        // the code would then double every series on the first day of a real drain, long
        // after this test had signed it off. So assert the stronger property directly.
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new BotMdcTagsMeterFilter());
        BotMetrics metrics = new BotMetrics(registry);

        BotMdc.set("group-0", 1, "env-tip", "116", "BETTING_MINI",
                "game-a", "Bau Cua", "authtestws1");
        BotMdc.setPluginVersion(PluginVersions.BUILTIN);
        metrics.incBotMessage("endGame");
        metrics.incLogin(true);

        assertThat(MDC.get(BotMdc.PLUGIN_VERSION))
                .as("the key must genuinely be on MDC, or this asserts nothing")
                .isEqualTo(PluginVersions.BUILTIN);
        assertThat(registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey)
                .distinct()
                .toList())
                .as("no meter in the registry may carry the pluginVersion tag")
                .doesNotContain(BotMdc.PLUGIN_VERSION);
    }

    @Test
    @DisplayName("the cost being avoided: two versions in one group would split every counter")
    void twoVersionsInOneGroupWouldSplitEverySeries() {
        // Documented, deliberately RED-if-it-ever-happens. This is the step-5/6 drain shape:
        // one group, half its bots recycled onto N+1. If the label were on the filter, every
        // bot_* series in the fleet would exist twice for the whole drain and the N-labelled
        // half would linger for Prometheus' retention window after it finished.
        MeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new BotMdcTagsMeterFilter());
        BotMetrics metrics = new BotMetrics(registry);

        for (String version : List.of("builtin", "v2")) {
            MDC.clear();
            BotMdc.set("group-0", 1, "env-tip", "116", "BETTING_MINI",
                    "game-a", "Bau Cua", "authtestws1");
            MDC.put(BotMdc.PLUGIN_VERSION, version);
            // Simulate the filter having been given the key, without changing the filter.
            metrics.incBotMessage("endGame");
        }

        assertThat(registry.find(BotMetrics.BOT_MESSAGES_TOTAL).counters())
                .as("with the label off the filter, a mixed-version group still has ONE series "
                        + "per (group, cmd) — this is the cardinality AD-5 buys")
                .hasSize(1);
    }
}
