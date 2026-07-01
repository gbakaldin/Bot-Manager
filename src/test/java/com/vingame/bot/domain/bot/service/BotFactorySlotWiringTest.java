package com.vingame.bot.domain.bot.service;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.config.client.EnvironmentClients;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.BotMetrics;
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
 * Phase 5 verification (SLOT_MACHINE_BOT): {@link BotFactory#createBot} wires the
 * {@code case SLOT} branch to build a {@link com.vingame.bot.domain.bot.core.SlotMachineBot}
 * instead of throwing "Game type not yet implemented".
 * <p>
 * {@code createBot} ends in {@code Bot.initialize()}, which opens a real Netty
 * WebSocket connection — not something to drive in a unit test. We therefore stop
 * the flow deterministically at the auth boundary: {@code authenticate()} throws a
 * sentinel. The point this proves is the one Phase 5 owns: the type {@code switch}
 * has already run and selected the SLOT branch by the time we reach
 * {@code initialize()}. The OLD code threw {@code IllegalArgumentException("Game
 * type not yet implemented: SLOT")} <em>before</em> {@code initialize()}; the new
 * code constructs the slot bot (invoking the slot message-types + slot-strategy
 * setters) and only then enters {@code initialize()} where our sentinel fires.
 * Seeing the sentinel — and never the not-implemented error — confirms the wiring.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotFactory - SLOT wiring (Phase 5)")
class BotFactorySlotWiringTest {

    @Mock
    private EnvironmentClientRegistry clientRegistry;

    @Mock
    private io.netty.channel.EventLoopGroup eventLoopGroup;

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
        return new BotFactory(clientRegistry, eventLoopGroup, botMetrics, new com.vingame.bot.infrastructure.observability.SessionAggregationService(), strategyFactory, slotStrategyFactory);
    }

    private static EnvironmentClients envClientsWith(Environment env,
                                                     ApiGatewayClient apiGatewayClient) {
        return new EnvironmentClients(
                env.getId(),
                apiGatewayClient,
                mock(GameMsClient.class),
                mock(ClientFactory.class),
                env);
    }

    private static BotConfiguration slotConfig(Game game) {
        return BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("slotbot1").password("p").build())
                .environmentId("env-1")
                .botGroupId("g-1")
                .botIndex(1)
                .game(game)
                .zoneName("ignored")
                .build();
    }

    @Test
    @DisplayName("case SLOT selects the SlotMachineBot branch (no \"not yet implemented\"); reaches initialize()")
    void createBot_slotGame_wiresSlotBranchAndReachesInitialize() {
        // customZone=false → resolveZoneName returns a non-blank product default,
        // so the zoneName fail-loud guard does not pre-empt the type switch.
        Environment env = Environment.builder()
                .id("env-1")
                .webSocketMiniUrl("ws://example/ws")
                .customZone(false)
                .build();
        Game game = Game.builder()
                .id("game-1")
                .name("SlotTipTest")
                .gameType(GameType.SLOT)
                .gameId(204)
                .pluginName("Tip")
                .build();

        ApiGatewayClient apiGatewayClient = mock(ApiGatewayClient.class);
        when(apiGatewayClient.getApiGateway()).thenReturn("https://gw.example");
        // Stop deterministically at the auth boundary — this fires only if the
        // type switch already selected the SLOT branch and entered initialize().
        when(apiGatewayClient.authenticate(any())).thenThrow(new AuthSentinel());

        when(clientRegistry.getClients("env-1")).thenReturn(envClientsWith(env, apiGatewayClient));

        assertThatThrownBy(() -> factory().createBot("env-1", slotConfig(game)))
                .isInstanceOf(AuthSentinel.class)
                // Negative assertion: the legacy not-implemented path must be gone.
                .hasMessageNotContaining("not yet implemented");
    }
}
