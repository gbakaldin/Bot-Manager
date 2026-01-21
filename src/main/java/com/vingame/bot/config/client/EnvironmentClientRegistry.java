package com.vingame.bot.config.client;

import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import io.netty.channel.EventLoopGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing environment-scoped shared clients.
 * <p>
 * This component maintains a cache of EnvironmentClients, creating them
 * on-demand and reusing them for all bots within the same environment.
 * <p>
 * Thread-safe and designed for concurrent access by multiple bot threads.
 * <p>
 * Benefits:
 * - Reduces memory footprint (1 HttpClient per environment vs per bot)
 * - Enables connection pooling at the environment level
 * - Simplifies client lifecycle management
 * - Improves performance through resource sharing
 */
@Slf4j
@Component
public class EnvironmentClientRegistry {

    private final ConcurrentHashMap<String, EnvironmentClients> registry = new ConcurrentHashMap<>();
    private final EnvironmentService environmentService;
    private final EventLoopGroup eventLoopGroup;
    private final ObjectProvider<ApiGatewayClient> apiGatewayClientProvider;
    private final String gameMsUrl;

    @Autowired
    public EnvironmentClientRegistry(
            EnvironmentService environmentService,
            EventLoopGroup eventLoopGroup,
            ObjectProvider<ApiGatewayClient> apiGatewayClientProvider,
            @Value("${gamems.url}") String gameMsUrl
    ) {
        this.environmentService = environmentService;
        this.eventLoopGroup = eventLoopGroup;
        this.apiGatewayClientProvider = apiGatewayClientProvider;
        this.gameMsUrl = gameMsUrl;
    }

    /**
     * Get or create shared clients for an environment.
     * <p>
     * Thread-safe: Uses computeIfAbsent for atomic get-or-create operation.
     * First call for an environment creates the clients, subsequent calls return cached instance.
     *
     * @param environmentId ID of the environment
     * @return EnvironmentClients instance (cached or newly created)
     * @throws com.vingame.bot.common.exception.ResourceNotFoundException if environment doesn't exist
     */
    public EnvironmentClients getClients(String environmentId) {
        return registry.computeIfAbsent(environmentId, this::createClients);
    }

    /**
     * Remove environment clients from registry.
     * <p>
     * Should be called when an environment is deleted to free resources.
     * Calls shutdown() on the EnvironmentClients before removing.
     *
     * @param environmentId ID of the environment to remove
     */
    public void removeClients(String environmentId) {
        EnvironmentClients clients = registry.remove(environmentId);
        if (clients != null) {
            clients.shutdown();
            log.info("Removed clients for environment: {}", environmentId);
        }
    }

    /**
     * Clear all clients from registry.
     * Useful for testing or application shutdown.
     */
    public void clearAll() {
        log.info("Clearing all environment clients from registry");
        registry.values().forEach(EnvironmentClients::shutdown);
        registry.clear();
    }

    /**
     * Get number of cached environment client sets.
     * Useful for monitoring and diagnostics.
     */
    public int size() {
        return registry.size();
    }

    /**
     * Create shared clients for an environment.
     * <p>
     * Private factory method that constructs all shared clients
     * from the environment configuration.
     *
     * @param environmentId ID of the environment
     * @return New EnvironmentClients instance
     */
    private EnvironmentClients createClients(String environmentId) {
        log.info("Creating shared clients for environment: {}", environmentId);

        // Fetch environment configuration
        Environment env = environmentService.findById(environmentId);

        // Get prototype ApiGatewayClient from Spring and initialize with environment config
        ApiGatewayClient apiGatewayClient = apiGatewayClientProvider.getObject();
        apiGatewayClient.init(env.getApiGatewayUrl(), env.getAppId());

        // Create shared GameMsClient (stateless) with global GameMS URL
        GameMsClient gameMsClient = new GameMsClient(gameMsUrl);

        // Create shared ClientFactory with shared EventLoopGroup
        ClientFactory clientFactory = new ClientFactory();
        clientFactory.setUri(URI.create(env.getWebSocketMiniUrl()));
        clientFactory.setHeaders(env.getHeaders());
        clientFactory.setZoneName(env.getMiniZoneName());
        clientFactory.setEncryption(true);
        clientFactory.setEventLoopGroup(eventLoopGroup); // CRITICAL: Share EventLoopGroup across all bots

        log.info("Successfully created shared clients for environment {} ({})",
            env.getName(), environmentId);

        return new EnvironmentClients(
            environmentId,
            apiGatewayClient,
            gameMsClient,
            clientFactory,
            env
        );
    }
}
