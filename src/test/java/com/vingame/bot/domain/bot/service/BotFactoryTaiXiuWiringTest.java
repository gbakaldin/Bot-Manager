package com.vingame.bot.domain.bot.service;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.config.client.EnvironmentClients;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;
import com.vingame.bot.domain.brand.model.ProductCode;
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
 * Phase 6 verification (TAI_XIU_BOT, AD-5): {@link BotFactory#createBot} wires the
 * {@code case TAI_XIU} branch to build a
 * {@link com.vingame.bot.domain.bot.core.TaiXiuGameBot} instead of throwing
 * "Game type not yet implemented".
 * <p>
 * Mirrors {@link BotFactorySlotWiringTest}: {@code createBot} ends in
 * {@code Bot.initialize()}, which opens a real Netty WebSocket connection, so we
 * stop deterministically at the auth boundary ({@code authenticate()} throws a
 * sentinel). Reaching the sentinel — and never the not-implemented error — proves
 * the type {@code switch} selected the TAI_XIU branch, constructed the
 * {@code TaiXiuGameBot}, and invoked its message-types + strategy-factory setters
 * before entering {@code initialize()}.
 * <p>
 * Because the TAI_XIU branch resolves the message types <em>inside</em> the switch
 * via {@code GameMessageTypesResolver.resolveTaiXiu(env.getProductCode())}, the env
 * is given {@link ProductCode#P_116 P_116} — the only Tai Xiu product implemented
 * in v1 (Phase 3 placeholder). A successful (non-throwing) resolve there is itself
 * part of the wiring this test exercises.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotFactory - TAI_XIU wiring (Phase 6)")
class BotFactoryTaiXiuWiringTest {

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

    private static BotConfiguration taiXiuConfig(Game game) {
        return BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("taixiubot1").password("p").build())
                .environmentId("env-1")
                .botGroupId("g-1")
                .botIndex(1)
                .game(game)
                .zoneName("ignored")
                .build();
    }

    @Test
    @DisplayName("case TAI_XIU selects the TaiXiuGameBot branch (no \"not yet implemented\"); reaches initialize()")
    void createBot_taiXiuGame_wiresTaiXiuBranchAndReachesInitialize() {
        // customZone=false → resolveZoneName returns a non-blank product default,
        // so the zoneName fail-loud guard does not pre-empt the type switch.
        // productCode=P_116 is the v1 Tai Xiu product, so resolveTaiXiu resolves
        // the message-types provider rather than throwing inside the switch.
        Environment env = Environment.builder()
                .id("env-1")
                .webSocketMiniUrl("ws://example/ws")
                .customZone(false)
                .productCode(ProductCode.P_116)
                .build();
        Game game = Game.builder()
                .id("game-1")
                .name("TaiXiuTest")
                .gameType(GameType.TAI_XIU)
                .gameId(0)
                .pluginName("taixiuPlugin")
                .build();

        ApiGatewayClient apiGatewayClient = mock(ApiGatewayClient.class);
        when(apiGatewayClient.getApiGateway()).thenReturn("https://gw.example");
        // Stop deterministically at the auth boundary — this fires only if the
        // type switch already selected the TAI_XIU branch and entered initialize().
        when(apiGatewayClient.authenticate(any())).thenThrow(new AuthSentinel());

        when(clientRegistry.getClients("env-1")).thenReturn(envClientsWith(env, apiGatewayClient));

        assertThatThrownBy(() -> factory().createBot("env-1", taiXiuConfig(game)))
                .isInstanceOf(AuthSentinel.class)
                // Negative assertion: the legacy not-implemented path must be gone.
                .hasMessageNotContaining("not yet implemented");
    }
}
