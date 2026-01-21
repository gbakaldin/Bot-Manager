package com.vingame.bot.domain.bot.service;

import com.vingame.bot.infrastructure.client.ClientFactory;
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

    @Autowired
    public BotFactory(EnvironmentClientRegistry clientRegistry, EventLoopGroup eventLoopGroup) {
        this.clientRegistry = clientRegistry;
        this.eventLoopGroup = eventLoopGroup;
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

        // DIAGNOSTIC: Create a FRESH ClientFactory for each bot instead of sharing
        // This helps identify if the issue is with shared ClientFactory state
        Environment env = environmentClients.getEnvironment();
        ClientFactory freshClientFactory = new ClientFactory();
        freshClientFactory.setUri(URI.create(env.getWebSocketMiniUrl()));
        freshClientFactory.setHeaders(env.getHeaders());
        freshClientFactory.setZoneName(env.getMiniZoneName());
        freshClientFactory.setEncryption(env.getEncryptionKey() != null && env.getEncryptionIv() != null);
        freshClientFactory.setEncryptionKey(env.getEncryptionKey());
        freshClientFactory.setEncryptionIv(env.getEncryptionIv());
        freshClientFactory.setEventLoopGroup(eventLoopGroup);

        log.info("Created fresh ClientFactory for bot {} (factory: {}, eventLoopGroup: {})",
            configuration.getCredentials().getUsername(),
            System.identityHashCode(freshClientFactory),
            eventLoopGroup != null ? System.identityHashCode(eventLoopGroup) : "NULL");

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
                freshClientFactory  // Use fresh factory instead of shared
            )
            .setConfiguration(configuration)
            .initialize();

        log.info("Successfully created bot {} for environment {}",
            configuration.getCredentials().getUsername(), environmentId);

        return bot;
    }
}
