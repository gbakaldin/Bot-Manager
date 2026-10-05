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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AVIATOR_BOT Phase 1: the game type, its message layer and its registry lookup exist,
 * but there is no bot yet. Starting a CRASH group must fail each bot with the same
 * "Game type not yet implemented" CARD_GAME and UP_DOWN fail with — not with a zone,
 * registry or NPE error that would send an operator looking in the wrong place.
 * Phase 3 replaces the arm (and this test) with the real {@code CrashBot} wiring.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotFactory - CRASH is not yet implemented (Phase 1)")
class BotFactoryCrashNotYetImplementedTest {

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

    @Test
    @DisplayName("a CRASH game on 119 fails with 'Game type not yet implemented: CRASH'")
    void crashFailsLikeTheCardGame() {
        Environment env = Environment.builder()
                .id("env-119")
                .brandCode(BrandCode.G4)
                .productCode(ProductCode.P_119)
                .webSocketMiniUrl("ws://example/ws")
                .customZone(false)
                .build();
        when(clientRegistry.getClients("env-119")).thenReturn(new EnvironmentClients(
                env.getId(), mock(ApiGatewayClient.class), mock(GameMsClient.class),
                mock(ClientFactory.class), env, GatewayBudget.UNLIMITED));

        Game aviator = Game.builder()
                .id("aviator")
                .name("Aviator")
                .gameType(GameType.CRASH)
                .pluginName("aviatorPlugin")
                .offset(1700)
                .build();
        BotConfiguration configuration = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("bot1").password("p").build())
                .environmentId("env-119")
                .botGroupId("g-1")
                .botIndex(1)
                .game(aviator)
                .zoneName("ignored")
                .build();

        BotFactory factory = new BotFactory(clientRegistry, eventLoopGroup, botMetrics,
                new SessionAggregationService(), new GroupLifecycleAggregator(),
                (ScopedDebugEscalator) null,
                strategyFactory, slotStrategyFactory, TestMessageTypes.REGISTRY);

        assertThatThrownBy(() -> factory.createBot("env-119", configuration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Game type not yet implemented: CRASH");
    }
}
