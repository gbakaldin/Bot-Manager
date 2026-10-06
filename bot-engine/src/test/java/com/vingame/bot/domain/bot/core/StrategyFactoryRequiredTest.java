package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.TaiXiuMessageTypes;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.strategy.TestStrategyFactories;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * PLUGIN_HOT_RELOAD_3_4 D-4: the engine has no fallback to a concrete plugin strategy.
 * A bot built without its strategy factory fails at {@code initializeSubclass} with an
 * {@link IllegalStateException}, instead of silently playing
 * {@code RandomBehaviorStrategy} / {@code FixedBetStrategy} whatever its configured key.
 * The happy-path twin proves the wired path still resolves the default keys.
 */
@DisplayName("Strategy factory is required (D-4)")
class StrategyFactoryRequiredTest {

    @Test
    @DisplayName("BettingMiniGameBot without a BettingStrategyFactory throws, naming the factory")
    void bettingBotRequiresFactory() {
        BettingMiniGameBot bot = new BettingMiniGameBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(config(bettingGame()));

        assertThatThrownBy(bot::initializeSubclass)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BettingStrategyFactory not wired — BotFactory always sets it");
    }

    @Test
    @DisplayName("TaiXiuGameBot inherits the requirement")
    void taiXiuBotRequiresFactory() {
        TaiXiuGameBot bot = new TaiXiuGameBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(config(bettingGame()));
        bot.setTaiXiuMessageTypes(mock(TaiXiuMessageTypes.class));

        assertThatThrownBy(bot::initializeSubclass)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BettingStrategyFactory not wired");
    }

    @Test
    @DisplayName("SlotMachineBot without a SlotStrategyFactory throws, naming the factory")
    void slotBotRequiresFactory() {
        SlotMachineBot bot = new SlotMachineBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(config(slotGame()));
        bot.setMessageTypes(new SlotMessageTypesImpl());

        assertThatThrownBy(bot::initializeSubclass)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SlotStrategyFactory not wired — BotFactory always sets it");
    }

    @Test
    @DisplayName("wired with the real factories, the null keys still default to RANDOM / FIXED")
    void wiredFactoriesResolveTheDefaults() throws Exception {
        BettingMiniGameBot betting = new BettingMiniGameBot();
        betting.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        betting.setConfiguration(config(bettingGame()));
        betting.setStrategyFactory(TestStrategyFactories.betting());
        betting.initializeSubclass();

        SlotMachineBot slot = new SlotMachineBot();
        slot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        slot.setConfiguration(config(slotGame()));
        slot.setMessageTypes(new SlotMessageTypesImpl());
        slot.setSlotStrategyFactory(TestStrategyFactories.slot());
        slot.initializeSubclass();

        // By simple name: the engine test names no plugin class, which is the point of D-4.
        assertThat(betting.getStrategy().getClass().getSimpleName()).isEqualTo("RandomBehaviorStrategy");
        // SlotMachineBot exposes no getter for its strategy; read the field.
        Field slotStrategy = SlotMachineBot.class.getDeclaredField("strategy");
        slotStrategy.setAccessible(true);
        assertThat(slotStrategy.get(slot).getClass().getSimpleName()).isEqualTo("FixedBetStrategy");
    }

    private static Game bettingGame() {
        return Game.builder().id("g-1").name("BauCua").pluginName("bauCuaPlugin")
                .offset(2000).numberOfOptions(6).build();
    }

    private static Game slotGame() {
        return Game.builder().id("g-slot").name("SlotTip").pluginName("Tip")
                .gameType(GameType.SLOT).gameId(204).build();
    }

    private static BotConfiguration config(Game game) {
        return BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("factorybot1").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game)
                .behaviorConfig(BotBehaviorConfig.builder()
                        .minBet(100).maxBet(1000).betIncrement(100)
                        .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(3)
                        .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                        .build())
                .zoneName("MiniGame").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .build();
    }
}
