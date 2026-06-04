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
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public abstract class Bot {

    // Backoff schedule for WS reconnect (seconds): total ≈ 4:45
    private static final long[] BACKOFF_SECONDS = {5, 10, 30, 60, 60, 60, 60};
    // Time to wait after submitting a reconnect to confirm the connection is alive
    private static final long RECONNECT_CONFIRM_SECONDS = 3;

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
    protected TokensProvider tokens;

    protected volatile long lastFetchedBalance = -1;
    protected final AtomicLong expectedCurrentBalance = new AtomicLong(-100_000_000L);

    // Snapshot of MDC keys captured at the end of initialize() so that work scheduled
    // onto threads with no MDC (Netty IO loop, library reconnect virtual threads,
    // PipelineStage schedulers, our own reconnect threads, watchdog/countdown schedulers)
    // can re-apply the bot's identity context around their log emissions.
    protected volatile Map<String, String> mdcSnapshot;

    // Health metrics
    @Getter
    protected final AtomicLong totalBetsPlaced = new AtomicLong(0);
    @Getter
    protected final AtomicLong totalBetAmount = new AtomicLong(0);
    @Getter
    protected volatile long lastRoundWinnings = 0;

    @Getter
    private volatile BotStatus status = BotStatus.AUTHENTICATING;

    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private volatile boolean stopped = false;

    public Bot() {}

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

    public Bot setConfiguration(BotConfiguration configuration) {
        this.configuration = configuration;
        this.credentials = configuration.getCredentials();
        this.userName = credentials.getUsername();
        log.debug("Bot {} configured", userName);
        return this;
    }

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

            // Snapshot MDC while all five bot keys are still populated by BotMdc.set(...)
            // above. Captured here (not in setConfiguration / setClients) so gameType and
            // botUserName are guaranteed present. Re-applied via mdcWrap / mdcCall /
            // mdcSupplier / mdcConsumer on threads that wouldn't otherwise carry MDC.
            this.mdcSnapshot = MDC.getCopyOfContextMap();

            return this;
        } finally {
            BotMdc.clear();
        }
    }

    protected abstract void initializeSubclass();

    public void cleanup() {
        stopped = true;
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

    public boolean isStopped() {
        return stopped;
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
        wsClient.onDisconnect(() -> {
            if (!stopped) onWsDisconnected();
        });
    }

    // ---- Reconnect logic ----

    private void onWsDisconnected() {
        if (!reconnecting.compareAndSet(false, true)) {
            return; // reconnect loop already running — it will handle the retry
        }
        transitionStatus(BotStatus.RECONNECTING);
        log.warn("Bot {}: WS disconnected — starting retrial flow", userName);
        Thread.ofVirtual().name("reconnect-" + userName).start(this::runWsReconnectLoop);
    }

    // Called from the watchdog in BettingMiniGameBot — skips WS backoff, re-auths immediately
    protected void triggerFullReconnect(String reason) {
        if (stopped) return;
        if (!reconnecting.compareAndSet(false, true)) {
            return; // reconnect already in progress
        }
        transitionStatus(BotStatus.RECONNECTING);
        log.warn("Bot {}: full reconnect triggered — {}", userName, reason);
        if (client != null && client.isOpen()) {
            client.close();
        }
        Thread.ofVirtual().name("reconnect-" + userName).start(this::runAuthThenWsLoop);
    }

    private void runWsReconnectLoop() {
        int attempt = 0;
        while (!stopped) {
            long delaySecs = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)];
            sleep(delaySecs * 1000);
            if (stopped) return;

            if (tryReconnectWs()) {
                sleep(RECONNECT_CONFIRM_SECONDS * 1000);
                if (!stopped && client != null && client.isOpen()) {
                    log.info("Bot {}: reconnected to WS (attempt {})", userName, attempt + 1);
                    reconnecting.set(false);
                    return;
                }
                log.debug("Bot {}: reconnect attempt {} did not hold", userName, attempt + 1);
            }

            attempt++;

            // After full backoff sequence exhausted, re-authenticate and start over
            if (attempt >= BACKOFF_SECONDS.length) {
                if (!performReauth()) return; // marks DEAD if auth fails
                attempt = 0;
            }
        }
    }

    private void runAuthThenWsLoop() {
        if (stopped) return;
        if (!performReauth()) return;
        if (stopped) return;

        if (tryReconnectWs()) {
            sleep(RECONNECT_CONFIRM_SECONDS * 1000);
            if (!stopped && client != null && client.isOpen()) {
                log.info("Bot {}: reconnected after full re-auth", userName);
                reconnecting.set(false);
                return;
            }
        }
        runWsReconnectLoop();
    }

    private boolean performReauth() {
        try {
            log.debug("Bot {}: re-authenticating", userName);
            transitionStatus(BotStatus.AUTHENTICATING);
            this.tokens = apiGatewayClient.authenticate(credentials);
            transitionStatus(BotStatus.AUTHENTICATED);
            return true;
        } catch (Exception e) {
            log.error("Bot {}: re-authentication failed — marking DEAD", userName);
            transitionStatus(BotStatus.DEAD);
            reconnecting.set(false);
            return false;
        }
    }

    private boolean tryReconnectWs() {
        try {
            if (client != null && client.isOpen()) {
                client.close();
            }
            this.client = clientFactory.newClient(tokens, userName);
            configureClient(client);
            transitionStatus(BotStatus.CONNECTING);
            client.connect();
            beforeReconnect();
            start();
            return true;
        } catch (Exception e) {
            log.debug("Bot {}: WS reconnect attempt failed: {}", userName, e.getMessage());
            return false;
        }
    }

    // Hook for subclasses to clean up game state before scenarios are re-added on reconnect
    protected void beforeReconnect() {}

    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- MDC wrap helpers ----
    //
    // Contract (Architecture Decision 7 in docs/plans/LOGGING_PIPELINE_FIX.md):
    //   1. Stash the caller's current MDC via MDC.getCopyOfContextMap().
    //   2. If mdcSnapshot is non-null, apply it via MDC.setContextMap(snapshot).
    //      If mdcSnapshot is null (snapshot not yet captured), leave MDC alone.
    //   3. Run the wrapped action.
    //   4. In finally: if the stashed map was non-null restore it via setContextMap;
    //      otherwise MDC.clear(). This keeps the wrap re-entrant and safe on threads
    //      that already had a different MDC.

    protected Runnable mdcWrap(Runnable r) {
        return () -> {
            Map<String, String> stash = MDC.getCopyOfContextMap();
            if (mdcSnapshot != null) {
                MDC.setContextMap(mdcSnapshot);
            }
            try {
                r.run();
            } finally {
                if (stash != null) {
                    MDC.setContextMap(stash);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    protected <T> Callable<T> mdcCall(Callable<T> c) {
        return () -> {
            Map<String, String> stash = MDC.getCopyOfContextMap();
            if (mdcSnapshot != null) {
                MDC.setContextMap(mdcSnapshot);
            }
            try {
                return c.call();
            } finally {
                if (stash != null) {
                    MDC.setContextMap(stash);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    protected <T> Supplier<T> mdcSupplier(Supplier<T> s) {
        return () -> {
            Map<String, String> stash = MDC.getCopyOfContextMap();
            if (mdcSnapshot != null) {
                MDC.setContextMap(mdcSnapshot);
            }
            try {
                return s.get();
            } finally {
                if (stash != null) {
                    MDC.setContextMap(stash);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    protected <T> Consumer<T> mdcConsumer(Consumer<T> c) {
        return t -> {
            Map<String, String> stash = MDC.getCopyOfContextMap();
            if (mdcSnapshot != null) {
                MDC.setContextMap(mdcSnapshot);
            }
            try {
                c.accept(t);
            } finally {
                if (stash != null) {
                    MDC.setContextMap(stash);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    // ---- Abstract & template methods ----

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
        onStart();
        transitionStatus(BotStatus.STARTED);
    }

    protected abstract void onStart();
}
