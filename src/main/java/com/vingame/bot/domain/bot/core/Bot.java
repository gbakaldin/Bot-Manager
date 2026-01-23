package com.vingame.bot.domain.bot.core;

import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.auth.TokensProvider;
import com.vingame.websocketparser.scenario.Scenario;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Slf4j
public abstract class Bot {

    // Shared environment clients (set via builder-style setters)
    protected ApiGatewayClient apiGatewayClient;
    protected GameMsClient gameMsClient;
    protected ClientFactory clientFactory;

    // Bot runtime configuration (set via builder-style setters)
    protected BotConfiguration configuration;
    protected BotCredentials credentials;

    @Getter
    protected VingameWebSocketClient client;

    @Getter
    protected String userName;

    @Getter
    protected String apiGateway;

    @Getter
    protected String authToken; // Token for WebSocket authentication (from authenticate()[0])

    @Getter
    protected String agencyToken; // Token for GameMS deposits (from authenticate()[1])

    protected volatile long lastFetchedBalance = -1;
    protected final AtomicLong expectedCurrentBalance = new AtomicLong(-100_000_000L);

    // Health metrics
    @Getter
    protected final AtomicLong totalBetsPlaced = new AtomicLong(0);
    @Getter
    protected final AtomicLong totalBetAmount = new AtomicLong(0);
    @Getter
    protected volatile long lastRoundWinnings = 0;

    /**
     * No-arg constructor for factory instantiation.
     * Factory will use fluent setters to configure, then call initialize().
     */
    public Bot() {
        // Factory will call setClients() and setConfiguration() before initialize()
    }

    /**
     * Set environment clients (fluent setter for builder pattern).
     *
     * @param apiGatewayClient Shared API gateway client
     * @param gameMsClient Shared GameMS client
     * @param clientFactory Shared WebSocket client factory
     * @return this for method chaining
     */
    public Bot setClients(
        ApiGatewayClient apiGatewayClient,
        GameMsClient gameMsClient,
        ClientFactory clientFactory
    ) {
        this.apiGatewayClient = apiGatewayClient;
        this.gameMsClient = gameMsClient;
        this.clientFactory = clientFactory;
        this.apiGateway = apiGatewayClient.getApiGateway();
        return this;
    }

    /**
     * Set bot configuration (fluent setter for builder pattern).
     *
     * @param configuration Bot configuration including credentials and behavior
     * @return this for method chaining
     */
    public Bot setConfiguration(BotConfiguration configuration) {
        this.configuration = configuration;
        this.credentials = configuration.getCredentials();
        this.userName = credentials.getUsername();
        log.debug("Bot {} configured", userName);
        return this;
    }

    /**
     * Initialize bot after clients and configuration are set.
     * <p>
     * Lifecycle: new Bot() → setClients() → setConfiguration() → initialize()
     * <p>
     * This method:
     * 1. Authenticates with game server to get auth tokens
     * 2. Creates WebSocket client with authentication baked in
     * 3. Calls child-specific initialization
     * 4. Ready for start() call
     *
     * @return this for method chaining
     */
    public Bot initialize() {
        log.debug("Initializing bot {}", userName);

        // Authenticate to get tokens BEFORE creating WebSocket client
        // Returns [agencyToken, authToken]
        List<String> authTokens = apiGatewayClient.authenticate(credentials);
        this.agencyToken = authTokens.get(0); // Store agency token for GameMS deposits
        this.authToken = authTokens.get(1);   // Store auth token for WebSocket authentication

        // Create WebSocket client from factory, passing tokens directly
        // This avoids race conditions when multiple bots share the same factory
        this.client = clientFactory.newClient(authTokens);

        // Call child-specific initialization
        initializeSubclass();

        // CRITICAL: Authenticate THEN connect (matches old Connector behavior)
        // authenticate() sets up the auth tokens that will be used on connect
        // connect() actually connects and sends AUTH message
        // This ensures bot 1 is fully connected before bot 2 is even created
        log.info("Bot {} - setting auth tokens [agency: {}, auth: {}]",
                 userName, agencyToken.substring(0, 10) + "...", authToken.substring(0, 10) + "...");
        client.authenticate(TokensProvider.of(agencyToken, authToken));
        client.connect();

        log.info("Bot {} initialized successfully and connected. Client: {}",
                 userName, System.identityHashCode(client));

        return this;
    }

    /**
     * Child-specific initialization hook.
     * Called by parent's initialize() after client is created.
     * Override this to set up game-specific scenarios, request objects, etc.
     */
    protected abstract void initializeSubclass();

    /**
     * Cleanup bot resources.
     * <p>
     * Called manually by BotGroupRuntime when:
     * - BotGroup is stopped
     * - Application shutdown
     * <p>
     * Ensures WebSocket connection is closed gracefully.
     */
    public void cleanup() {
        log.info("Cleaning up bot {}", userName);
        if (client != null && client.isOpen()) {
            try {
                stop();
            } catch (Exception e) {
                log.error("Error stopping bot {} during cleanup", userName, e);
            }
        }
    }

    public void restart() {
        // Close old client
        if (client != null && client.isOpen()) {
            client.close();
        }
        // Create new client from factory with existing tokens
        List<String> tokens = List.of(agencyToken, authToken);
        this.client = clientFactory.newClient(tokens);
        // Re-setup authentication and reconnect
        client.authenticate(TokensProvider.of(agencyToken, authToken));
        client.connect();
        start();
    }

    public void stop() {
        log.info("Bot {} stopping. Closing client instance: {}",
                 userName, System.identityHashCode(client));
        client.close();
    }

    protected abstract boolean shouldBet();

    protected void connectToSocket() {
        client.connect();
    }

    public void authenticate() {
        try {
            List<String> tokens = apiGatewayClient.authenticate(credentials);
            client.authenticate(TokensProvider.of(tokens.get(0), tokens.get(1)));
        } catch (Exception e) {
            log.error("Bot {}: Authentication failed: {}", userName, e.getMessage());
        }
    }

    public void logout() {
        try {
            stop();
            // TODO: Implement explicit logout in ApiGatewayClient if needed
            log.info("Bot {}: Logged out", userName);
        } catch (Exception e) {
            log.error("Bot {}: Logout failed: {}", userName, e.getMessage());
        }
    }

    public void deposit() {
        if (lastFetchedBalance < 0) {
            return;
        }

        gameMsClient.deposit(client.getAgencyToken(), 1_000_000_000L, success -> {
            if (success) {
                log.info("Bot {}: Deposit successful, fetching new balance...", userName);
                lastFetchedBalance = apiGatewayClient.getBalance(
                    getClient().getAuthToken(),
                    credentials.getFingerprint(),
                    userName
                );
                expectedCurrentBalance.set(lastFetchedBalance);
                log.info("Bot {}: New balance: {}", userName, expectedCurrentBalance);
            } else {
                log.warn("Bot {}: Deposit failed", userName);
            }
        });
    }

    protected long checkBalance() {
        log.info("Bot {} - checkBalance() ENTRY. lastFetched: {}, expected: {}, delta: {}",
                 userName, lastFetchedBalance, expectedCurrentBalance.get(),
                 Math.abs(lastFetchedBalance - expectedCurrentBalance.get()));

        if (Math.abs(lastFetchedBalance - expectedCurrentBalance.get()) > 1_000_000L) {
            log.info("Bot {} - checkBalance() fetching from server (delta > 1M)", userName);
            String token = getClient().getAuthToken();
            log.info("Bot {} - VERIFYING TOKEN: {}", userName, token);
            lastFetchedBalance = apiGatewayClient.getBalance(
                token,
                credentials.getFingerprint(),
                userName
            );
            log.info("Bot {} - checkBalance() fetched: {}", userName, lastFetchedBalance);
            expectedCurrentBalance.set(lastFetchedBalance);
        } else {
            log.info("Bot {} - checkBalance() SKIPPED fetch (delta < 1M), returning cached: {}", userName, expectedCurrentBalance.get());
        }

        log.info("Bot {} - checkBalance() RETURNING: {}", userName, expectedCurrentBalance.get());
        return expectedCurrentBalance.get();
    }

    protected long getMinBalance() {
        return 5_000_000L;
    }

    public long getExpectedBalance() {
        return expectedCurrentBalance.get();
    }

    public long getLastFetchedBalance() {
        return lastFetchedBalance;
    }

    public boolean isConnected() {
        return client != null && client.isOpen();
    }

    protected abstract long resolveBetAmount();

    protected abstract Supplier<Boolean> resolveBetCondition();

    protected abstract Scenario botBehaviorScenario();

    protected void creditBalance(long amount) {
        this.expectedCurrentBalance.addAndGet(-amount);
        this.totalBetsPlaced.incrementAndGet();
        this.totalBetAmount.addAndGet(amount);
    }

    public final void start() {
        log.info("========== Bot {} START ========== Client: {} | Thread: {}",
                 userName, System.identityHashCode(client), Thread.currentThread().getName());

        // Connection and authentication already happened in initialize()
        // Just run the bot logic
        onStart();
        log.info("Bot {} - onStart() completed", userName);

        log.info("========== Bot {} START COMPLETED ==========", userName);
    }

    protected abstract void onStart();

}
