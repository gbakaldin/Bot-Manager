package com.vingame.bot.domain.bot.service;

import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator;
import com.vingame.bot.infrastructure.observability.ScopedDebugEscalator;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.config.client.EnvironmentClients;
import com.vingame.bot.domain.bot.core.BettingMiniGameBot;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.core.CashoutBot;
import com.vingame.bot.domain.bot.core.SlotMachineBot;
import com.vingame.bot.domain.bot.core.TaiXiuGameBot;
import com.vingame.bot.domain.bot.message.MessageTypesRegistry;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.environment.model.Environment;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Factory for creating bot instances with direct instantiation.
 * <p>
 * This factory orchestrates bot creation:
 * 1. Fetches shared environment clients from registry
 * 2. Instantiates bot with `new` operator
 * 3. Configures bot using fluent setters
 * 4. Initializes bot (authenticates, creates WebSocket client)
 * 5. Returns fully initialized bot ready to start()
 * <p>
 * Architecture Flow:
 * <pre>
 * BotFactory.createBot(environmentId, gameType, configuration)
 *   → registry.getClients(environmentId)
 *   → new BauCuaBot()
 *   → bot.setClients(apiGatewayClient, gameMsClient, clientFactory)
 *   → bot.setConfiguration(configuration)
 *   → bot.initialize()
 *      → authenticate() → get tokens
 *      → clientFactory.newClient(tokens)
 *      → initializeSubclass() (game-specific setup)
 *   → return initialized bot
 * </pre>
 *
 * Benefits over Spring DI approach:
 * - No custom scope complexity
 * - No ThreadLocal context management
 * - Simpler, more predictable lifecycle
 * - Better for parallel bot group execution
 * - Easier to debug and test
 * <p>
 * Thread Safety:
 * - EnvironmentClientRegistry is thread-safe (ConcurrentHashMap)
 * - Each bot gets its own instance with independent state
 * - Multiple threads can create bots in parallel without coordination
 */
@Slf4j
@Component
public class BotFactory {

    private final EnvironmentClientRegistry clientRegistry;
    private final EventLoopGroup eventLoopGroup;
    private final BotMetrics botMetrics;
    private final SessionAggregationService sessionAggregator;
    private final GroupLifecycleAggregator groupLifecycleAggregator;
    private final ScopedDebugEscalator scopedDebugEscalator;
    private final BettingStrategyFactory strategyFactory;
    private final SlotStrategyFactory slotStrategyFactory;
    private final MessageTypesRegistry messageTypesRegistry;

    @Autowired
    public BotFactory(EnvironmentClientRegistry clientRegistry,
                      EventLoopGroup eventLoopGroup,
                      BotMetrics botMetrics,
                      SessionAggregationService sessionAggregator,
                      GroupLifecycleAggregator groupLifecycleAggregator,
                      ScopedDebugEscalator scopedDebugEscalator,
                      BettingStrategyFactory strategyFactory,
                      SlotStrategyFactory slotStrategyFactory,
                      MessageTypesRegistry messageTypesRegistry) {
        this.clientRegistry = clientRegistry;
        this.eventLoopGroup = eventLoopGroup;
        this.botMetrics = botMetrics;
        this.sessionAggregator = sessionAggregator;
        this.groupLifecycleAggregator = groupLifecycleAggregator;
        this.scopedDebugEscalator = scopedDebugEscalator;
        this.strategyFactory = strategyFactory;
        this.slotStrategyFactory = slotStrategyFactory;
        this.messageTypesRegistry = messageTypesRegistry;
    }

    /**
     * Create a bot instance with direct instantiation.
     *
     * @param environmentId The Environment ID (for fetching shared clients)
     * @param configuration Bot configuration (credentials, behavior, and game)
     * @return Fully initialized bot ready to start()
     */
    public Bot createBot(
        String environmentId,
        BotConfiguration configuration
    ) {
        Game game = configuration.getGame();
        log.debug("Creating bot {} for environment {} (game: {})",
            configuration.getCredentials().getUsername(), environmentId, game.getName());

        // Fetch shared environment clients from registry
        EnvironmentClients environmentClients = clientRegistry.getClients(environmentId);

        Environment env = environmentClients.getEnvironment();
        // Resolve effective zoneName for this (env, game) pair. When the env is not
        // customZone=true, this returns product defaults ("MiniGame"/"Simms") rather
        // than the null miniZoneName that historically caused VingameWebSocketClient
        // to throw "Authentication configuration is required" at builder.build().
        // See RESTART_LIFECYCLE_FIX.
        String resolvedZoneName = env.resolveZoneName(game);
        if (resolvedZoneName == null || resolvedZoneName.isBlank()) {
            // Reachable when customZone=true with a blank custom field — a real
            // env misconfiguration. Also defensively reachable if game==null
            // slipped through (resolveZoneName is documented null-safe on game),
            // so we guard the gameType lookup to avoid NPE-while-formatting that
            // would mask the real cause. Fail loud with full context so the
            // operator can identify the bad env without grepping every bot's
            // auth log.
            throw new IllegalStateException(String.format(
                    "Cannot create bot %s: resolved zoneName is null/blank " +
                            "(environmentId=%s, gameType=%s, customZone=%s, " +
                            "miniZoneName=%s, cardZoneName=%s). " +
                            "When customZone=true the matching custom field must be populated.",
                    configuration.getCredentials().getUsername(),
                    environmentId,
                    game != null ? game.getGameType() : null,
                    env.isCustomZone(),
                    env.getMiniZoneName(),
                    env.getCardZoneName()
            ));
        }
        ClientFactory freshClientFactory = new ClientFactory();
        freshClientFactory.setUri(URI.create(env.getWebSocketMiniUrl()));
        freshClientFactory.setHeaders(env.getHeaders());
        freshClientFactory.setZoneName(resolvedZoneName);
        freshClientFactory.setEncryption(env.getEncryptionKey() != null && env.getEncryptionIv() != null);
        freshClientFactory.setEncryptionKey(env.getEncryptionKey());
        freshClientFactory.setEncryptionIv(env.getEncryptionIv());
        freshClientFactory.setIgnoreJwtToken(!env.isUseJwtAuth());
        freshClientFactory.setEventLoopGroup(eventLoopGroup);

        // Instantiate bot based on game type (using domain.game.model.GameType).
        // Message-types resolution is now per-branch (AD-4): betting-mini resolves
        // a product-keyed GameMessageTypes; SLOT resolves a product-neutral
        // SlotMessageTypes. Resolving betting-mini up front would throw for a SLOT
        // game on a product that lacks a betting-mini provider.
        Bot bot = switch (game.getGameType()) {
            case BETTING_MINI -> {
                BettingMiniGameBot bettingBot = new BettingMiniGameBot();
                // .forGame(game) is the game dimension the registry key lacks
                // (RIK_114_ZICZAC AD-3): the registry still answers one provider per
                // (gameType, product), and that provider decides whether it serves
                // this particular game itself. Defaults to `this` for every provider
                // that does not specialise, so this is a no-op everywhere except
                // P_114's ziczacPlugin. THIS IS THE ONLY CALL SITE — a second
                // bettingMini(...) lookup that forgets forGame silently falls back to
                // the generic provider, and ziczac's winnings go back to zero.
                bettingBot.setMessageTypes(
                        messageTypesRegistry.bettingMini(productKey(env)).forGame(game));
                // Wire the strategy registry so initializeSubclass() can build the
                // per-bot BettingStrategy for configuration.strategyId.
                bettingBot.setStrategyFactory(strategyFactory);
                yield bettingBot;
            }
            case SLOT -> {
                SlotMachineBot slotBot = new SlotMachineBot();
                slotBot.setMessageTypes(messageTypesRegistry.slot());
                // Wire the slot strategy registry so initializeSubclass() can build
                // the per-bot SlotStrategy for configuration.slotStrategyId (AD-9).
                slotBot.setSlotStrategyFactory(slotStrategyFactory);
                yield slotBot;
            }
            case TAI_XIU -> {
                // Tai Xiu reuses BettingMiniGameBot's round behavior wholesale; only the
                // message layer differs. Resolve the product-keyed, fixed-CMD provider
                // (AD-5) and wire the betting strategy factory — Tai Xiu reuses the
                // betting strategy family unchanged (AD-6), same as BETTING_MINI.
                TaiXiuGameBot taiXiuBot = new TaiXiuGameBot();
                taiXiuBot.setTaiXiuMessageTypes(
                        messageTypesRegistry.taiXiu(productKey(env)));
                taiXiuBot.setStrategyFactory(strategyFactory);
                yield taiXiuBot;
            }
            case CASHOUT -> {
                // CASHOUT_BOT AD-3: product-keyed like Tai Xiu — 119 Balloon/Soccer only.
                // No strategy factory: CASHOUT has no strategy family (AD-7).
                CashoutBot cashoutBot = new CashoutBot();
                cashoutBot.setMessageTypes(messageTypesRegistry.cashout(productKey(env)));
                yield cashoutBot;
            }
            case CARD_GAME, UP_DOWN ->
                throw new IllegalArgumentException("Game type not yet implemented: " + game.getGameType());
        };

        // Configure bot using fluent setters and initialize
        bot.setClients(
                environmentClients.getApiGatewayClient(),
                environmentClients.getGameMsClient(),
                freshClientFactory
            )
            // GATEWAY_REQUEST_BUDGET AD-1. Wired immediately after setClients, and from the
            // SAME EnvironmentClients the ApiGatewayClient came from, so a bot's WebSocket
            // upgrades and its HTTP requests are counted against one window and cannot
            // diverge. Note that freshClientFactory above is per-bot by design
            // (RESTART_LIFECYCLE_FIX) while the budget is deliberately per-environment.
            .setGatewayBudget(environmentClients.getGatewayBudget())
            .setConfiguration(configuration)
            .setMetrics(botMetrics)
            .setSessionAggregator(sessionAggregator)
            // Must be wired BEFORE initialize(): initializeSubclass() is where the
            // per-bot "initialized" feed happens (LOG_VOLUME_TIERING tier 1).
            .setGroupLifecycleAggregator(groupLifecycleAggregator)
            // LOG_VOLUME_TIERING AD-12: the bot's own early-warning signals (watchdog
            // expiry, reconnect churn) arm scoped DEBUG for its group.
            .setScopedDebugEscalator(scopedDebugEscalator)
            .initialize();

        // LOG_VOLUME_TIERING tier 1: DEBUG, not INFO. Not one of the five sites the
        // plan enumerates, but the same line class — one per bot at group start, so
        // 30k lines on a 30k-bot fleet start. Leaving it at INFO would have defeated
        // the demotion of the "initialized" twins three lines above it; the group-level
        // replacement is GroupLifecycleAggregator's "N/M bots initialized" line.
        log.debug("Successfully created bot {} for environment {}",
            configuration.getCredentials().getUsername(), environmentId);

        return bot;
    }

    /**
     * The environment's product as {@code MessageTypesRegistry} keys it — the string
     * {@code ProductCode.getCode()}, not the enum constant (PLUGIN_HOT_RELOAD AD-16).
     * <p>
     * Null-safe on purpose. {@code Environment.productCode} is a plain nullable field,
     * and before Phase 2c a null reached {@code GameMessageTypesResolver} and came back
     * as {@code IllegalArgumentException("ProductCode cannot be null")}. Passing
     * {@code env.getProductCode().getCode()} directly would turn that into an NPE here,
     * which is exactly the kind of silent contract change AD-23 forbids — so the null is
     * forwarded and the registry still produces the original message.
     */
    private static String productKey(Environment env) {
        ProductCode productCode = env.getProductCode();
        return productCode == null ? null : productCode.getCode();
    }
}
