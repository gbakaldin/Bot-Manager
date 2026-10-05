package com.vingame.bot.domain.bot.service;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.config.client.EnvironmentClients;
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
import io.netty.channel.EventLoopGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CASHOUT_BOT Phase 3: {@link BotFactory#createBot} builds a
 * {@link com.vingame.bot.domain.bot.core.CashoutBot} for a CASHOUT game on 119 instead of
 * throwing "Game type not yet implemented" — the {@code BotFactorySlotWiringTest}
 * pattern: {@code createBot} ends in {@code Bot.initialize()}, which would open a real
 * socket, so the flow is stopped at the auth boundary with a sentinel. Reaching the
 * sentinel proves the CASHOUT arm ran, resolved the 119 provider and entered
 * {@code initialize()}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotFactory - CASHOUT wiring (Phase 3)")
class BotFactoryCashoutWiringTest {

    @Mock
    private EnvironmentClientRegistry clientRegistry;

    @Mock
    private EventLoopGroup eventLoopGroup;

    @Mock
    private BotMetrics botMetrics;

    @Mock
    private BettingStrategyFactory strategyFactory;

    @Mock
    private SlotStrategyFactory slotStrategyFactory;

    private static final class AuthSentinel extends RuntimeException {
        AuthSentinel() {
            super("auth-boundary-reached");
        }
    }

    private BotFactory factory() {
        return new BotFactory(clientRegistry, eventLoopGroup, botMetrics,
                new SessionAggregationService(), new GroupLifecycleAggregator(),
                (ScopedDebugEscalator) null,
                strategyFactory, slotStrategyFactory, TestMessageTypes.REGISTRY);
    }

    private static Environment env(ProductCode product) {
        return Environment.builder()
                .id("env-1")
                .brandCode(BrandCode.G4)
                .productCode(product)
                .webSocketMiniUrl("ws://example/ws")
                .customZone(false)
                .build();
    }

    private static BotConfiguration config() {
        Game balloon = Game.builder()
                .id("balloon")
                .name("Balloon")
                .gameType(GameType.CASHOUT)
                .pluginName("balloonPlugin")
                .offset(1500)
                .build();
        return BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("cashbot1").password("p").build())
                .environmentId("env-1")
                .botGroupId("g-1")
                .botIndex(1)
                .game(balloon)
                .zoneName("ignored")
                .build();
    }

    @Test
    @DisplayName("a CASHOUT game on 119 selects the CashoutBot branch and reaches initialize()")
    void cashoutOn119ReachesInitialize() {
        ApiGatewayClient apiGatewayClient = mock(ApiGatewayClient.class);
        when(apiGatewayClient.getApiGateway()).thenReturn("https://gw.example");
        when(apiGatewayClient.authenticate(any(), any(), any())).thenThrow(new AuthSentinel());
        when(clientRegistry.getClients("env-1")).thenReturn(new EnvironmentClients(
                "env-1", apiGatewayClient, mock(GameMsClient.class), mock(ClientFactory.class),
                env(ProductCode.P_119), GatewayBudget.UNLIMITED));

        assertThatThrownBy(() -> factory().createBot("env-1", config()))
                .isInstanceOf(AuthSentinel.class)
                .hasMessageNotContaining("not yet implemented");
    }

    @Test
    @DisplayName("a CASHOUT game on a product with no provider fails with the registry's text, before auth")
    void cashoutOn116FailsInTheRegistry() {
        when(clientRegistry.getClients("env-1")).thenReturn(new EnvironmentClients(
                "env-1", mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class),
                env(ProductCode.P_116), GatewayBudget.UNLIMITED));

        assertThatThrownBy(() -> factory().createBot("env-1", config()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CashoutMessageTypes not yet implemented for product code: 116."
                        + " Please create a CashoutMessageTypes implementation for this product.");
    }
}
