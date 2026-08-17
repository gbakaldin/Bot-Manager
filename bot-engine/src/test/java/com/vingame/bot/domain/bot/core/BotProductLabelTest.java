package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.websocketparser.scenario.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The source of the {@code product} metric label (VIPTALK_ALERTING_V2 AD-V1).
 * <p>
 * {@code Bot.productCode()} is what makes the whole Phase 1 cardinality argument hold.
 * It delegates to {@code BotConfiguration.resolveProductCode()}, which reads the product
 * the group's <b>environment</b> resolved at start and falls back to the bot's
 * {@code Game}. Either way it is <b>one value per {@code environmentId}</b>, and
 * therefore one value per {@code gameId} — the functional dependency AD-V1 claims, and
 * the reason the label costs no series (see {@code ProductLabelCardinalityTest}).
 * <p>
 * The environment is the authority on purpose: the per-environment gauges
 * ({@code bots_managed_by_env}, {@code bots_by_env_status}) can only read it from there,
 * so sourcing the bot-scoped counters anywhere else would let one group's game-scoped and
 * environment-scoped alerts route to two different product rooms.
 * <p>
 * It also has to be null-tolerant: {@code productCode} post-dates some Mongo documents on
 * both entities, and a bot with neither must simply carry no label rather than fail or
 * emit an empty one.
 */
@DisplayName("Bot.productCode() — source of the `product` metric label")
class BotProductLabelTest {

    private static Game game(String id, String name, ProductCode product) {
        return Game.builder()
                .id(id)
                .name(name)
                .gameType(GameType.BETTING_MINI)
                .pluginName(name)
                .environmentId("env-1")
                .productCode(product)
                .offset(2000)
                .numberOfOptions(6)
                .build();
    }

    private static ProductBot bot(Game game, int botIndex) {
        ProductBot bot = new ProductBot();
        bot.setConfiguration(BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("bot" + botIndex).password("pw").fingerprint("fp").build())
                .environmentId("env-1")
                .botGroupId("group-1")
                .botIndex(botIndex)
                .game(game)
                .zoneName("MiniGame3")
                .timeoutMillis(60_000L)
                .build());
        return bot;
    }

    @Test
    @DisplayName("returns the numeric ProductCode.getCode(), not the enum name or display name")
    void returnsTheNumericCode() {
        assertThat(bot(game("g1", "Bau Cua", ProductCode.P_116), 1).product()).isEqualTo("116");
        assertThat(bot(game("g2", "Tai Xiu", ProductCode.P_114), 2).product()).isEqualTo("114");
        assertThat(bot(game("g3", "Bom", ProductCode.P_097), 3).product())
                .isEqualTo("097")
                .isNotEqualTo(ProductCode.P_097.name())
                .isNotEqualTo(ProductCode.P_097.getDisplayName());
    }

    @Test
    @DisplayName("a Game document with no productCode yields null, so MDC skips the label")
    void nullProductCodeYieldsNull() {
        // Implementation Note 4: older Mongo documents predate Game.productCode. Such a
        // bot carries no `product` label at all — never "" — and its audience:product
        // alerts fall back to the environmentId → Mongo hop at routing time.
        assertThat(bot(game("g4", "Legacy", null), 4).product()).isNull();
    }

    @Test
    @DisplayName("every bot on one Game reports the same product — the AD-V1 functional dependency")
    void allBotsOnOneGameAgreeOnTheProduct() {
        Game shared = game("g5", "Bau Cua", ProductCode.P_116);

        List<String> products = List.of(
                bot(shared, 1).product(), bot(shared, 2).product(), bot(shared, 3).product());

        assertThat(products).containsOnly("116");
    }

    @Test
    @DisplayName("the environment's product (set on the configuration at group start) wins over the Game's")
    void configurationProductWinsOverTheGameDocument() {
        // The contradiction case: a Game filed under P_097 running in a P_116 environment.
        // Both the bot's counters and the per-environment gauges must say 116, or
        // GameNoRounds (which joins the two families on `product`) silently never fires.
        ProductBot bot = new ProductBot();
        bot.setConfiguration(BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("bot1").password("pw").fingerprint("fp").build())
                .environmentId("env-1")
                .productCode("116")
                .botGroupId("group-1")
                .botIndex(1)
                .game(game("g8", "Misfiled", ProductCode.P_097))
                .zoneName("MiniGame3")
                .build());

        assertThat(bot.product()).isEqualTo("116");
    }

    @Test
    @DisplayName("two games of different products stay distinguishable")
    void differentGamesKeepTheirOwnProducts() {
        assertThat(bot(game("g6", "Bau Cua", ProductCode.P_116), 1).product())
                .isNotEqualTo(bot(game("g7", "Bom", ProductCode.P_097), 2).product());
    }

    /** Minimal concrete Bot exposing the protected derivation. */
    static class ProductBot extends Bot {
        @Override protected void initializeSubclass() {}
        @Override protected Scenario botBehaviorScenario() { return null; }
        @Override protected void onStart() {}

        String product() {
            return productCode();
        }
    }
}
