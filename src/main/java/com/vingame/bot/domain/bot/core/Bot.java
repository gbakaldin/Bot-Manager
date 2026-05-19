package com.vingame.bot.domain.bot.core;

import com.vingame.bot.common.logging.BotMdc;
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

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Slf4j
public abstract class Bot {

    // Shared environment clients (set via builder-style setters)
    protected ApiGatewayClient apiGatewayClient;
    protected GameMsClient gameMsClient;
    protected ClientFactory clientFactory;

    // Bot runtime configuration (set via builder-style setters)
    @Getter
    protected BotConfiguration configuration;
    protected BotCredentials credentials;

    @Getter
    protected VingameWebSocketClient client;

    @Getter
    protected String userName;

    @Getter
    protected String apiGateway;

    @Getter
    protected TokensProvider tokens; // All authentication tokens from ApiGatewayClient

    protected volatile long lastFetchedBalance = -1;
    protected final AtomicLong expectedCurrentBalance = new AtomicLong(-100_000_000L);

    // Health metrics
    @Getter
    protected final AtomicLong totalBetsPlaced = new AtomicLong(0);
    @Getter
    protected final AtomicLong totalBetAmount = new AtomicLong(0);
    @Getter
    protected volatile long lastRoundWinnings = 0;

    @Getter
    private volatile BotStatus status = BotStatus.AUTHENTICATING;

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
        BotMdc.set(
                configuration.getBotGroupId(),
                configuration.getBotIndex(),
                configuration.getEnvironmentId(),
                configuration.getGame().getName(),
                userName
        );
        try {
            log.debug("Initializing bot {}", userName);

            transitionStatus(BotStatus.AUTHENTICATING);
            this.tokens = apiGatewayClient.authenticate(credentials);
            transitionStatus(BotStatus.AUTHENTICATED);

            this.client = clientFactory.newClient(tokens, userName);
            initializeSubclass();
            configureClient(client);

            transitionStatus(BotStatus.CONNECTING);
            log.debug("Setting auth tokens [agency: {}..., auth: {}...]",
                     tokens.getAgencyToken().substring(0, 10),
                     tokens.getAuthToken().substring(0, 10));
            client.connect();

            log.info("Bot initialized and connected. Client: {}",
                     System.identityHashCode(client));

            return this;
        } finally {
            BotMdc.clear();
        }
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
        if (client != null && client.isOpen()) {
            client.close();
        }
        this.client = clientFactory.newClient(tokens, userName);
        configureClient(client);
        transitionStatus(BotStatus.CONNECTING);
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
            this.tokens = apiGatewayClient.authenticate(credentials);
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
        log.debug("checkBalance() ENTRY. lastFetched: {}, expected: {}, delta: {}",
                 lastFetchedBalance, expectedCurrentBalance.get(),
                 Math.abs(lastFetchedBalance - expectedCurrentBalance.get()));

        if (Math.abs(lastFetchedBalance - expectedCurrentBalance.get()) > 1_000_000L) {
            log.debug("checkBalance() fetching from server (delta > 1M)");
            lastFetchedBalance = apiGatewayClient.getBalance(
                getClient().getAuthToken(),
                credentials.getFingerprint(),
                userName
            );
            log.debug("checkBalance() fetched: {}", lastFetchedBalance);
            expectedCurrentBalance.set(lastFetchedBalance);
        } else {
            log.debug("checkBalance() using cached: {}", expectedCurrentBalance.get());
        }

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

    protected void markConnectionAuthenticated() {
        if (status != BotStatus.CONNECTION_AUTHENTICATED) {
            transitionStatus(BotStatus.CONNECTION_AUTHENTICATED);
        }
    }

    private void transitionStatus(BotStatus next) {
        BotStatus prev = this.status;
        this.status = next;
        log.info("Bot {}: {} → {}", userName, prev, next);
    }

    private void configureClient(VingameWebSocketClient wsClient) {
        wsClient.onWsStatusChange(wsStatus -> {
            switch (wsStatus) {
                case CONNECTED -> transitionStatus(BotStatus.CONNECTED);
                case AUTHENTICATING_WS -> transitionStatus(BotStatus.AUTHENTICATING_CONNECTION);
                default -> {}
            }
        });
        wsClient.onDisconnect(() ->
            log.warn("Bot {}: disconnected (status was {})", userName, status)
        );
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
        log.debug("Bot starting. Client: {} | Thread: {}",
                 System.identityHashCode(client), Thread.currentThread().getName());

        // Connection and authentication already happened in initialize()
        // Just run the bot logic
        onStart();
        transitionStatus(BotStatus.STARTED);
    }

    protected abstract void onStart();

}
