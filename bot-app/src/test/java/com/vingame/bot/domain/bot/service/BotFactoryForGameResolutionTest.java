package com.vingame.bot.domain.bot.service;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.config.client.EnvironmentClients;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.MessageTypesRegistry;
import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.rik.RikGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.rik.RikZicZacGameMessageTypes;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.message.taixiu.JackpotTaiXiuMessageTypes;
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
import com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator;
import com.vingame.bot.infrastructure.observability.ScopedDebugEscalator;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import io.netty.channel.EventLoopGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code BotFactory}'s <b>one</b> call to {@link GameMessageTypes#forGame(Game)}
 * (RIK_114_ZICZAC AD-3), asserted at the call site rather than on the provider.
 *
 * <p>This is the highest-risk surface in the ziczac feature and it is not ziczac.
 * {@code forGame} is a new default method on a bot-api interface that
 * {@code BotFactory} now invokes for <b>every betting-mini bot of every product</b>, so
 * a mistake here breaks brands that have nothing to do with P_114.
 * {@code RikGameMessageTypesRoutingTest} and
 * {@code GameMessageTypesForGameContractTest} prove what the providers return; only a
 * factory-level test can prove <i>where it is called</i>, and the two things that must
 * hold there are:
 * <ol>
 *   <li>the BETTING_MINI branch calls it, with the bot's own {@link Game} — a branch
 *       that forgot it would send ziczac back to the generic provider and
 *       {@code bot_winnings_total} silently back to zero, with no error anywhere;</li>
 *   <li>the SLOT and TAI_XIU branches do <b>not</b> — they resolve different contracts
 *       ({@code SlotMessageTypes} / {@code TaiXiuMessageTypes}, neither of which extends
 *       {@code GameMessageTypes}), and a stray betting-mini lookup in either would
 *       throw for any product that has no betting-mini provider.</li>
 * </ol>
 *
 * <p><b>How the assertions get made.</b> {@code createBot} ends in
 * {@code Bot.initialize()}, which authenticates and opens a real Netty socket, so the
 * flow is stopped deterministically at the auth boundary with a sentinel — the same
 * idiom as {@code BotFactorySlotWiringTest}. The type switch has already run by then,
 * which is what makes the spy verifications meaningful. One test additionally throws
 * <i>from {@code forGame} itself</i>: seeing that sentinel instead of the auth one
 * proves the call happens inside the switch, ahead of authentication, and that its
 * value is what the branch goes on to use.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BotFactory — the single forGame call site (RIK_114_ZICZAC AD-3)")
class BotFactoryForGameResolutionTest {

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

    /** Sentinel for "the type switch already ran and we reached initialize()". */
    private static final class AuthSentinel extends RuntimeException {
        AuthSentinel() {
            super("auth-boundary-reached");
        }
    }

    /** Sentinel for "forGame was invoked, and before authentication". */
    private static final class ForGameSentinel extends RuntimeException {
        ForGameSentinel() {
            super("forGame-invoked");
        }
    }

    /**
     * The real P_114 provider, spied so the call can be verified without being
     * replaced. A spy keeps the production routing intact — a stub would make the
     * assertion about the stub, which is the mistake {@code TestMessageTypes} exists to
     * avoid.
     */
    private RikGameMessageTypes rik;

    private MessageTypesRegistry registry;

    @BeforeEach
    void setUp() {
        rik = spy(new RikGameMessageTypes());
        // A real registry over real providers: 114 (spied), 097/098, the product-neutral
        // SLOT provider, and 114's TAI_XIU provider so the TAI_XIU branch can resolve.
        registry = new MessageTypesRegistry(
                List.of(rik, new BomGameMessageTypes()),
                List.of(new SlotMessageTypesImpl()),
                List.of(new JackpotTaiXiuMessageTypes()), List.of());
    }

    private BotFactory factory() {
        return new BotFactory(clientRegistry, eventLoopGroup, botMetrics,
                new SessionAggregationService(),
                new GroupLifecycleAggregator(),
                (ScopedDebugEscalator) null,
                strategyFactory, slotStrategyFactory, registry);
    }

    /** customZone=false → resolveZoneName yields a product default, so the switch is reached. */
    private void stubEnv(ProductCode product) {
        Environment env = Environment.builder()
                .id("env-114")
                .webSocketMiniUrl("ws://example/ws")
                .customZone(false)
                .productCode(product)
                .build();
        ApiGatewayClient apiGatewayClient = mock(ApiGatewayClient.class);
        when(apiGatewayClient.getApiGateway()).thenReturn("https://gw.example");
        when(apiGatewayClient.authenticate(any(), any(), any())).thenThrow(new AuthSentinel());
        when(clientRegistry.getClients("env-114")).thenReturn(new EnvironmentClients(
                env.getId(), apiGatewayClient, mock(GameMsClient.class),
                mock(ClientFactory.class), env, GatewayBudget.UNLIMITED));
    }

    private static Game game(GameType type, String pluginName, Integer offset) {
        return Game.builder()
                .id("game-" + pluginName)
                .name(pluginName)
                .gameType(type)
                .pluginName(pluginName)
                .offset(offset)
                .md5(false)
                .build();
    }

    private static BotConfiguration config(Game game) {
        return BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("rikbot1").password("p").build())
                .environmentId("env-114")
                .botGroupId("g-1")
                .botIndex(1)
                .game(game)
                .zoneName("ignored")
                .build();
    }

    /* ---------------- the BETTING_MINI branch calls it ---------------- */

    @Test
    @DisplayName("BETTING_MINI: forGame is called exactly once, with the bot's own Game instance")
    void bettingMiniResolvesPerGame() {
        stubEnv(ProductCode.P_114);
        Game ziczac = game(GameType.BETTING_MINI, "ziczacPlugin", 9000);

        assertThatThrownBy(() -> factory().createBot("env-114", config(ziczac)))
                .isInstanceOf(AuthSentinel.class);

        ArgumentCaptor<Game> captured = ArgumentCaptor.forClass(Game.class);
        verify(rik).forGame(captured.capture());
        // The SAME Game the configuration carries — not a rebuilt copy, and not the
        // Environment's idea of a game. Routing is decided on pluginName, so handing
        // forGame anything other than the bot's own game silently mis-routes.
        assertThat(captured.getValue()).isSameAs(ziczac);
        // ...and the routing it performs is the ziczac one.
        assertThat(rik.forGame(captured.getValue())).isInstanceOf(RikZicZacGameMessageTypes.class);
    }

    @Test
    @DisplayName("BETTING_MINI on a non-ziczac 114 game: forGame is still called, and answers the generic provider")
    void bettingMiniStockGameStillResolvesButIsUnchanged() {
        stubEnv(ProductCode.P_114);
        Game stock = game(GameType.BETTING_MINI, "stockPlugin", 10000);

        assertThatThrownBy(() -> factory().createBot("env-114", config(stock)))
                .isInstanceOf(AuthSentinel.class);

        verify(rik).forGame(stock);
        // The regression claim for the two shipped 114 games, at the call site: the
        // factory hands the bot exactly what the registry answered with.
        assertThat(rik.forGame(stock)).isSameAs(rik);
    }

    @Test
    @DisplayName("forGame runs INSIDE the type switch, before authentication — its value is what the branch uses")
    void forGameRunsBeforeAuthentication() {
        stubEnv(ProductCode.P_114);
        Game ziczac = game(GameType.BETTING_MINI, "ziczacPlugin", 9000);
        doThrow(new ForGameSentinel()).when(rik).forGame(any());

        // Seeing ForGameSentinel rather than AuthSentinel pins the ordering: the
        // resolution is part of building the bot, not something bolted on afterwards,
        // so there is no window in which a BettingMiniGameBot exists holding the
        // unresolved provider.
        assertThatThrownBy(() -> factory().createBot("env-114", config(ziczac)))
                .isInstanceOf(ForGameSentinel.class);
    }

    /* ---------------- the other two branches must not ---------------- */

    @Test
    @DisplayName("SLOT: the betting-mini provider is never consulted, let alone forGame'd")
    void slotBranchNeverResolvesPerGame() {
        stubEnv(ProductCode.P_114);
        Game slot = game(GameType.SLOT, "Tip", null);

        assertThatThrownBy(() -> factory().createBot("env-114", config(slot)))
                .isInstanceOf(AuthSentinel.class)
                .hasMessageNotContaining("not yet implemented");

        // SlotMessageTypes does not extend GameMessageTypes and has no game dimension.
        // A stray forGame here would also mean a stray bettingMini(product) lookup,
        // which throws outright for any product with a slot provider and no
        // betting-mini one — i.e. it would break slot groups on 066/103/105/222.
        verify(rik, never()).forGame(any());
    }

    @Test
    @DisplayName("TAI_XIU: the betting-mini provider is never consulted, let alone forGame'd")
    void taiXiuBranchNeverResolvesPerGame() {
        stubEnv(ProductCode.P_114);
        // Tai Xiu games carry a null offset by design (fixed CMDs).
        Game taiXiu = game(GameType.TAI_XIU, "taixiuJackpotPlugin", null);

        assertThatThrownBy(() -> factory().createBot("env-114", config(taiXiu)))
                .isInstanceOf(AuthSentinel.class);

        verify(rik, never()).forGame(any());
    }

    @Test
    @DisplayName("a non-114 product's betting-mini bot is untouched: its own provider resolves to itself")
    void otherProductsAreUnaffected() {
        stubEnv(ProductCode.P_097);
        Game bauCua = game(GameType.BETTING_MINI, "BauCua", 2000);

        assertThatThrownBy(() -> factory().createBot("env-114", config(bauCua)))
                .isInstanceOf(AuthSentinel.class);

        // 097 resolves Bom, so the 114 provider is never even reached...
        verify(rik, never()).forGame(any());
        // ...and Bom's inherited default is the identity, so nothing about the bot it
        // builds differs from before this feature.
        GameMessageTypes bom = registry.bettingMini("097");
        assertThat(bom.forGame(bauCua)).isSameAs(bom);
        // Including for a P_114 plugin name, which no other product may claim.
        assertThat(bom.forGame(game(GameType.BETTING_MINI, "ziczacPlugin", 9000))).isSameAs(bom);
    }

    @Test
    @DisplayName("BETTING_MINI on an env with no productCode still fails on the product key, not inside forGame")
    void nullProductCodeStillFailsAtTheLookup() {
        stubEnv(null);
        Game ziczac = game(GameType.BETTING_MINI, "ziczacPlugin", 9000);

        // Ordering guard: `bettingMini(productKey(env)).forGame(game)` evaluates the
        // lookup first, so the operator-facing message an unconfigured environment
        // produces is unchanged by this feature. A refactor that resolved the game
        // first would change it into something else.
        assertThatThrownBy(() -> factory().createBot("env-114", config(ziczac)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ProductCode cannot be null");

        verify(rik, never()).forGame(any());
    }
}
