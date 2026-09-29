package com.vingame.bot.config.client;

import com.vingame.bot.infrastructure.auth.AuthStrategyFactory;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
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
    private final AuthStrategyFactory authStrategyFactory;
    private final GatewayBudgetRegistry gatewayBudgetRegistry;
    private final String gameMsUrl;

    @Autowired
    public EnvironmentClientRegistry(
            EnvironmentService environmentService,
            EventLoopGroup eventLoopGroup,
            ObjectProvider<ApiGatewayClient> apiGatewayClientProvider,
            AuthStrategyFactory authStrategyFactory,
            GatewayBudgetRegistry gatewayBudgetRegistry,
            @Value("${gamems.url}") String gameMsUrl
    ) {
        this.environmentService = environmentService;
        this.eventLoopGroup = eventLoopGroup;
        this.apiGatewayClientProvider = apiGatewayClientProvider;
        this.authStrategyFactory = authStrategyFactory;
        this.gatewayBudgetRegistry = gatewayBudgetRegistry;
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

        // Get prototype ApiGatewayClient from Spring and initialize with environment config.
        // appId is brand-static; prefer the value baked into ProductCode and fall back to the
        // Environment record for any product whose ProductCode.appId has not been populated yet.
        String appId = env.getProductCode() != null && env.getProductCode().getAppId() != null
                ? env.getProductCode().getAppId()
                : env.getAppId();
        // GATEWAY_REQUEST_BUDGET AD-1: this registry and the budget registry are keyed
        // identically (environmentId), so the client, the bots and the metrics all end up on
        // the same per-environment window. forEnvironment is a computeIfAbsent and is never
        // evicted, so rebuilding an environment's clients (a restart, a recovery) keeps the
        // window it was already counting rather than resetting it to zero.
        //
        // The gateway URL is passed so the registry can WARN when two environments resolve to
        // the same gateway HOST (A16.6a): Cloudflare counts per (source IP x gateway host), so
        // two such environments would run two 900-request windows against one 1,000-request
        // limit and neither would be able to see the other. This is the only place in the app
        // that knows an environment's gateway URL at the moment its budget is created.
        GatewayBudget gatewayBudget = gatewayBudgetRegistry.forEnvironment(
                environmentId,
                env.getName(),
                env.getProductCode() != null ? env.getProductCode().getCode() : null,
                env.getApiGatewayUrl());

        ApiGatewayClient apiGatewayClient = apiGatewayClientProvider.getObject();
        apiGatewayClient.init(env.getApiGatewayUrl(), appId,
                authStrategyFactory.getAuthProfile(env), gatewayBudget);

        // Create shared GameMsClient (stateless) with global GameMS URL
        GameMsClient gameMsClient = new GameMsClient(gameMsUrl);

        // Create shared ClientFactory with shared EventLoopGroup.
        // NOTE: zoneName is intentionally NOT set on this cached factory. The
        // cached factory is shared across all games for an environment and is
        // never used to build WebSocket clients directly — BotFactory.createBot
        // constructs its own ClientFactory per bot and resolves zoneName from
        // the (Environment, Game) pair via Environment.resolveZoneName(game).
        // Setting a single zoneName here would be misleading because the
        // resolved value depends on the game type. See RESTART_LIFECYCLE_FIX.
        ClientFactory clientFactory = new ClientFactory();
        clientFactory.setUri(URI.create(env.getWebSocketMiniUrl()));
        clientFactory.setHeaders(env.getHeaders());
        clientFactory.setEncryption(true);
        clientFactory.setIgnoreJwtToken(!env.isUseJwtAuth());
        clientFactory.setEventLoopGroup(eventLoopGroup); // CRITICAL: Share EventLoopGroup across all bots

        log.info("Successfully created shared clients for environment {} ({})",
            env.getName(), environmentId);

        return new EnvironmentClients(
            environmentId,
            apiGatewayClient,
            gameMsClient,
            clientFactory,
            env,
            gatewayBudget
        );
    }
}
