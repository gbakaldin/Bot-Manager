package com.vingame.bot.config.client;

import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Container for environment-scoped shared clients.
 * <p>
 * One instance per environment, shared by all bots in that environment.
 * This dramatically reduces resource usage - instead of creating
 * one ApiGatewayClient and GameMsClient per bot, we create one per environment.
 * <p>
 * For example, 100 bots in the same environment will share these clients
 * instead of creating 100 separate instances.
 */
@Slf4j
@Getter
@RequiredArgsConstructor
public class EnvironmentClients {
    /**
     * Environment ID these clients are associated with
     */
    private final String environmentId;

    /**
     * Shared API Gateway client for authentication and balance operations.
     * Thread-safe and stateless - all methods accept credentials as parameters.
     */
    private final ApiGatewayClient apiGatewayClient;

    /**
     * Shared Game MS client for deposit operations.
     * Thread-safe and stateless.
     */
    private final GameMsClient gameMsClient;

    /**
     * Shared client factory for creating WebSocket clients.
     * Stateless factory that produces configured ScenarioBasedWsClient instances.
     */
    private final ClientFactory clientFactory;

    /**
     * Reference to the environment configuration.
     * Useful for accessing environment-specific settings.
     */
    private final Environment environment;

    /**
     * This environment's gateway request budget (GATEWAY_REQUEST_BUDGET AD-1).
     * <p>
     * The same object {@link #apiGatewayClient} was initialised with — held here so
     * {@code BotFactory} can wire it into the bot for the WebSocket-upgrade path without
     * resolving it a second time. One budget per environment for the life of the JVM, so a
     * rebuild of these clients (a restart, a recovery) keeps the window it was counting.
     */
    private final GatewayBudget gatewayBudget;

    /**
     * Cleanup method called when environment is removed from registry.
     * Currently no cleanup needed as clients don't hold resources,
     * but this provides a hook for future cleanup logic.
     */
    public void shutdown() {
        log.info("Shutting down clients for environment: {}", environmentId);
        // Future: Could close HttpClient connections, WebSocket pools, etc.
    }
}
