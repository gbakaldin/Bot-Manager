package com.vingame.bot.domain.bot.service;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.config.client.EnvironmentClients;
import com.vingame.bot.domain.bot.core.BettingMiniGameBot;
import com.vingame.bot.domain.bot.core.CashoutBot;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;
import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator;
import com.vingame.bot.infrastructure.observability.ScopedDebugEscalator;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import com.vingame.bot.infrastructure.plugin.PluginRegistries;
import com.vingame.bot.infrastructure.plugin.PluginRuntime;
import com.vingame.bot.infrastructure.plugin.TestPluginRuntimes;
import io.netty.channel.EventLoopGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * PLUGIN_HOT_RELOAD_3_4 D-9: {@code BotFactory} reads {@link PluginRuntime#current()}
 * <b>once per bot</b> and wires the bot from that one {@link PluginRegistries} value — its
 * strategy factory, its message types and, for every bot type, the bundle's Jackson
 * {@code TypeFactory} (step 5b). The bot keeps them for life, which is step 5's drain
 * semantics.
 * <p>
 * The bot is intercepted at construction ({@code mockConstruction}, answering
 * {@code RETURNS_SELF} so the fluent chain runs to the end) because {@code BotFactory}
 * builds it with {@code new} and immediately authenticates it; what is asserted is what the
 * factory handed it, not anything the bot then does.
 */
@DisplayName("BotFactory - wires each bot from one PluginRegistries value (D-9)")
class BotFactoryPluginRegistriesWiringTest {

    private final EnvironmentClientRegistry clientRegistry = mock(EnvironmentClientRegistry.class);
    private final PluginRuntime runtime = spy(TestPluginRuntimes.of(
            mock(BettingStrategyFactory.class), mock(SlotStrategyFactory.class), TestMessageTypes.REGISTRY));
    private final PluginRegistries plugins = runtime.current();

    private BotFactory factory() {
        return new BotFactory(clientRegistry, mock(EventLoopGroup.class), mock(BotMetrics.class),
                new SessionAggregationService(), new GroupLifecycleAggregator(),
                (ScopedDebugEscalator) null, runtime);
    }

    private void environment(ProductCode product) {
        ApiGatewayClient apiGatewayClient = mock(ApiGatewayClient.class);
        when(apiGatewayClient.getApiGateway()).thenReturn("https://gw.example");
        Environment env = Environment.builder()
                .id("env-1").brandCode(BrandCode.G3).productCode(product)
                .webSocketMiniUrl("ws://example/ws").customZone(false).build();
        when(clientRegistry.getClients("env-1")).thenReturn(new EnvironmentClients(
                "env-1", apiGatewayClient, mock(GameMsClient.class), mock(ClientFactory.class),
                env, GatewayBudget.UNLIMITED));
    }

    private static BotConfiguration config(Game game) {
        return BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("wirebot1").password("p").build())
                .environmentId("env-1").botGroupId("g-1").botIndex(1)
                .game(game).zoneName("ignored")
                .build();
    }

    @Test
    @DisplayName("a betting bot gets the current registries' strategy factory and TypeFactory, from one current() read")
    void bettingBotIsWiredFromOneRegistriesValue() {
        environment(ProductCode.P_116);
        Game game = Game.builder().id("bc").name("BauCua").gameType(GameType.BETTING_MINI)
                .pluginName("BauCua").offset(2000).build();
        org.mockito.Mockito.clearInvocations(runtime);

        try (MockedConstruction<BettingMiniGameBot> built = mockConstruction(BettingMiniGameBot.class,
                withSettings().defaultAnswer(RETURNS_SELF))) {
            factory().createBot("env-1", config(game));

            assertThat(built.constructed()).hasSize(1);
            BettingMiniGameBot bot = built.constructed().get(0);
            verify(bot).setStrategyFactory(plugins.bettingStrategies());
            verify(bot).setPluginTypeFactory(plugins.typeFactory());
        }
        verify(runtime, times(1)).current();
    }

    @Test
    @DisplayName("a bot type with no strategy family still gets the bundle's TypeFactory")
    void cashoutBotGetsTheTypeFactory() {
        environment(ProductCode.P_119);
        Game game = Game.builder().id("balloon").name("Balloon").gameType(GameType.CASHOUT)
                .pluginName("balloonPlugin").offset(1500).build();

        try (MockedConstruction<CashoutBot> built = mockConstruction(CashoutBot.class,
                withSettings().defaultAnswer(RETURNS_SELF))) {
            factory().createBot("env-1", config(game));

            CashoutBot bot = built.constructed().get(0);
            verify(bot).setMessageTypes(plugins.messageTypes().cashout("119"));
            verify(bot).setPluginTypeFactory(plugins.typeFactory());
        }
    }
}
