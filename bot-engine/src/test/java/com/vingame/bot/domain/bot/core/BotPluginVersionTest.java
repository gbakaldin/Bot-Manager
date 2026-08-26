package com.vingame.bot.domain.bot.core;

import com.vingame.bot.common.plugin.PluginVersions;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.websocketparser.scenario.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The source of the {@code pluginVersion} MDC key and of every
 * {@code bots_by_plugin_version} row (PLUGIN_HOT_RELOAD AD-10/AD-11).
 * <p>
 * The property that matters is that it is <b>never null</b>. Nothing sets the field in
 * Phase 1 — with one classloader the answer is a constant, so the group-start path does not
 * thread a resolver through — and the whole per-group gauge is built by iterating live
 * bots. A null-returning accessor would therefore not produce an "unversioned" row; it
 * would produce a row tagged with the empty string, or drop the bot from the gauge while
 * leaving it in {@code bots_managed}, which is exactly the shortfall verification P1-5
 * treats as a defect.
 */
@DisplayName("Bot.getPluginVersion() — source of the `pluginVersion` tag")
class BotPluginVersionTest {

    private static Game game() {
        return Game.builder()
                .id("g1")
                .name("Bau Cua")
                .gameType(GameType.BETTING_MINI)
                .pluginName("Bau Cua")
                .environmentId("env-1")
                .productCode(ProductCode.P_116)
                .offset(2000)
                .numberOfOptions(6)
                .build();
    }

    private static BotConfiguration.BotConfigurationBuilder config() {
        return BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("bot1").password("pw").fingerprint("fp").build())
                .environmentId("env-1")
                .botGroupId("group-1")
                .botIndex(1)
                .game(game())
                .zoneName("MiniGame3");
    }

    private static VersionBot bot(BotConfiguration configuration) {
        VersionBot bot = new VersionBot();
        bot.setConfiguration(configuration);
        return bot;
    }

    @Test
    @DisplayName("an unset pluginVersion resolves to `builtin`, never null")
    void unsetResolvesToBuiltin() {
        assertThat(bot(config().build()).getPluginVersion()).isEqualTo(PluginVersions.BUILTIN);
        assertThat(bot(config().pluginVersion(null).build()).getPluginVersion())
                .isEqualTo(PluginVersions.BUILTIN);
        assertThat(bot(config().pluginVersion("").build()).getPluginVersion())
                .as("an empty string is a label value Prometheus would happily keep — treat it "
                        + "as unset, like the product resolution one method over does")
                .isEqualTo(PluginVersions.BUILTIN);
    }

    @Test
    @DisplayName("an explicit version is carried through unchanged — the step-4/5 path")
    void explicitVersionIsCarriedThrough() {
        assertThat(bot(config().pluginVersion("v2").build()).getPluginVersion()).isEqualTo("v2");
    }

    @Test
    @DisplayName("the bot and its configuration cannot disagree")
    void botAndConfigurationAgree() {
        // One implementation, two readers: the MDC tag on this bot's lines and the gauge row
        // it is counted in. If these ever diverge, a drain reads as though bots moved
        // versions in the logs but not in the metrics, or the other way round.
        BotConfiguration configuration = config().pluginVersion("v2").build();
        assertThat(bot(configuration).getPluginVersion())
                .isEqualTo(configuration.resolvePluginVersion());
    }

    /** Minimal concrete Bot; the accessor under test is public on Bot itself. */
    static class VersionBot extends Bot {
        @Override protected void initializeSubclass() {}
        @Override protected Scenario botBehaviorScenario() { return null; }
        @Override protected void onStart() {}
    }
}
