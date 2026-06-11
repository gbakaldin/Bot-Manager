package com.vingame.bot.domain.bot.service;

import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.config.client.EnvironmentClients;
import com.vingame.bot.domain.bot.core.BettingMiniGameBot;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.GameMessageTypesResolver;
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

    @Autowired
    public BotFactory(EnvironmentClientRegistry clientRegistry,
                      EventLoopGroup eventLoopGroup,
                      BotMetrics botMetrics) {
        this.clientRegistry = clientRegistry;
        this.eventLoopGroup = eventLoopGroup;
        this.botMetrics = botMetrics;
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
            // Only reachable when customZone=true with a blank custom field — a real
            // env misconfiguration. Fail loud with full context so the operator can
            // identify the bad env without grepping every bot's auth log.
            throw new IllegalStateException(String.format(
                    "Cannot create bot %s: resolved zoneName is null/blank " +
                            "(environmentId=%s, gameType=%s, customZone=%s, " +
                            "miniZoneName=%s, cardZoneName=%s). " +
                            "When customZone=true the matching custom field must be populated.",
                    configuration.getCredentials().getUsername(),
                    environmentId,
                    game.getGameType(),
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

        // Resolve message types based on environment's product code
        GameMessageTypes messageTypes = GameMessageTypesResolver.resolve(env.getProductCode());

        // Instantiate bot based on game type (using domain.game.model.GameType)
        Bot bot = switch (game.getGameType()) {
            case BETTING_MINI -> {
                BettingMiniGameBot bettingBot = new BettingMiniGameBot();
                bettingBot.setMessageTypes(messageTypes);
                yield bettingBot;
            }
            case SLOT, TAI_XIU, CARD_GAME, UP_DOWN ->
                throw new IllegalArgumentException("Game type not yet implemented: " + game.getGameType());
        };

        // Configure bot using fluent setters and initialize
        bot.setClients(
                environmentClients.getApiGatewayClient(),
                environmentClients.getGameMsClient(),
                freshClientFactory
            )
            .setConfiguration(configuration)
            .setMetrics(botMetrics)
            .initialize();

        log.info("Successfully created bot {} for environment {}",
            configuration.getCredentials().getUsername(), environmentId);

        return bot;
    }
}
