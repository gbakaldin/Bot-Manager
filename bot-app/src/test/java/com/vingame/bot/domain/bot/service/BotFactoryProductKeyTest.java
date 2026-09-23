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
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.observability.BotMetrics;
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
 * PLUGIN_HOT_RELOAD Phase 2c, AD-20/AD-23 — the operator-facing failure text is
 * unchanged <b>at the call site</b>, not only in the registry's own unit test.
 *
 * <p>2c re-keyed the message layer from {@code ProductCode} to
 * {@code ProductCode.getCode()}, which put a dereference between {@code BotFactory}
 * and the lookup. {@code Environment.productCode} is a plain nullable field, so the
 * obvious spelling — {@code env.getProductCode().getCode()} — turns what used to be
 * {@code IllegalArgumentException("ProductCode cannot be null")} into a
 * {@code NullPointerException} with no product in it. {@code BotFactory.productKey}
 * forwards the null instead, and that decision is invisible to
 * {@code MessageTypesRegistryTest}: the registry is correct either way, it is the
 * caller that decides which exception an operator sees.
 *
 * <p>The unimplemented-product cases are here for the same reason. Verification step
 * P2-7 greps this exact string out of {@code docker logs bot-manager} after a group on
 * an unsupported brand fails to start, and the path it greps is this one — through
 * {@code createBot}, not through a directly constructed registry.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotFactory - product key forwarding preserves the pre-2c failure text")
class BotFactoryProductKeyTest {

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

    private BotFactory factory() {
        return new BotFactory(clientRegistry, eventLoopGroup, botMetrics,
                new com.vingame.bot.infrastructure.observability.SessionAggregationService(),
                new com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator(),
                (com.vingame.bot.infrastructure.observability.ScopedDebugEscalator) null,
                strategyFactory, slotStrategyFactory, TestMessageTypes.REGISTRY);
    }

    /**
     * customZone=false, so {@code resolveZoneName} returns the product default and the
     * fail-loud zoneName guard (a different feature, RESTART_LIFECYCLE_FIX) is passed —
     * execution reaches the game-type switch, which is what this test is about.
     */
    private static Environment envWith(ProductCode productCode) {
        return Environment.builder()
                .id("env-1")
                .webSocketMiniUrl("ws://example/ws")
                .customZone(false)
                .productCode(productCode)
                .build();
    }

    private void stubEnvironment(ProductCode productCode) {
        Environment env = envWith(productCode);
        when(clientRegistry.getClients("env-1")).thenReturn(new EnvironmentClients(
                env.getId(),
                mock(ApiGatewayClient.class),
                mock(GameMsClient.class),
                mock(ClientFactory.class),
                env, GatewayBudget.UNLIMITED));
    }

    private static BotConfiguration configFor(GameType gameType) {
        return BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("bot1").password("p").build())
                .environmentId("env-1")
                .botGroupId("g-1")
                .botIndex(1)
                .game(Game.builder().id("game-1").gameType(gameType).build())
                .zoneName("ignored")
                .build();
    }

    @Test
    @DisplayName("BETTING_MINI on an env with no productCode → IllegalArgumentException, not NPE")
    void bettingMiniWithNullProductCodeKeepsTheNullMessage() {
        stubEnvironment(null);

        assertThatThrownBy(() -> factory().createBot("env-1", configFor(GameType.BETTING_MINI)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ProductCode cannot be null");
    }

    @Test
    @DisplayName("TAI_XIU on an env with no productCode → IllegalArgumentException, not NPE")
    void taiXiuWithNullProductCodeKeepsTheNullMessage() {
        stubEnvironment(null);

        assertThatThrownBy(() -> factory().createBot("env-1", configFor(GameType.TAI_XIU)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ProductCode cannot be null");
    }

    @Test
    @DisplayName("BETTING_MINI on an unimplemented product → the exact P2-7 grep string")
    void bettingMiniUnimplementedProductTextIsUnchanged() {
        stubEnvironment(ProductCode.P_066);

        assertThatThrownBy(() -> factory().createBot("env-1", configFor(GameType.BETTING_MINI)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("GameMessageTypes not yet implemented for product code: 066."
                        + " Please create a GameMessageTypes implementation for this product.");
    }

    /**
     * The Tai Xiu contract name, which AD-20 does not quote and which the pre-2c
     * {@code resolveTaiXiu} threw as its own variant. 097 has a betting-mini provider
     * and no Tai Xiu one, so this also proves the two tables are consulted separately
     * at the call site.
     */
    @Test
    @DisplayName("TAI_XIU on a product with only a betting-mini provider names TaiXiuMessageTypes")
    void taiXiuUnimplementedProductNamesItsOwnContract() {
        stubEnvironment(ProductCode.P_097);

        assertThatThrownBy(() -> factory().createBot("env-1", configFor(GameType.TAI_XIU)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TaiXiuMessageTypes not yet implemented for product code: 097."
                        + " Please create a TaiXiuMessageTypes implementation for this product.");
    }
}
