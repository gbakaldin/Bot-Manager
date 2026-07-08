package com.vingame.bot.domain.bot.core;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.domain.bot.coordination.BetCoordinator;
import com.vingame.bot.domain.bot.coordination.JackpotScaler;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.auth.TokensProvider;
import com.vingame.websocketparser.scenario.Scenario;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
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
    // Absolute cap on full back-off cycles (re-auth rounds) before the bot is marked
    // DEAD (RESILIENCE_HARDENING P1, Decision 2/3). Covers the "WS keeps dropping but
    // re-auth keeps succeeding" case (Gap A) where the loop would otherwise reset
    // attempt=0 and retry forever. A capped bot transitions to DEAD, which then feeds
    // the group circuit breaker (deadBotGroupThreshold) — closing Gap B without any
    // change to BotGroupBehaviorService.monitorHealth.
    private static final int MAX_RECONNECT_CYCLES = 10;

    // Shared environment clients (set via builder-style setters)
    protected ApiGatewayClient apiGatewayClient;
    protected GameMsClient gameMsClient;
    protected ClientFactory clientFactory;

    // Observability — set via builder-style setter (BotFactory wires the singleton bean).
    protected BotMetrics metrics;

    // Per-session log aggregation — set via builder-style setter (BotFactory wires the
    // singleton bean). Null-tolerant exactly like {@code metrics}: unit-test fixtures
    // that build a bot without Spring leave it null and every feed callsite guards on it.
    protected SessionAggregationService sessionAggregator;

    // Group-scoped bet coordinator — set via builder-style setter by the runtime
    // startBot loop (one instance per running group; NOT app-scoped, so it is not
    // wired in BotFactory). Null-tolerant exactly like {@code sessionAggregator}:
    // null means coordination is off and the bot proposes/sends as it does today.
    protected BetCoordinator coordinator;

    // Group-scoped jackpot scaler (JACKPOT_SCALE_AND_RAMP) — set via builder-style
    // setter by the runtime startBot loop, mirroring {@code coordinator}. Null-tolerant:
    // null means jackpot-scale is off (or the type is ineligible) and the bot's
    // effective per-round bet cap is the configured maxBetsPerRound (factor 1.0).
    protected JackpotScaler jackpotScaler;

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

    /**
     * Strategy id assigned to this bot at start by the group's strategy mix.
     * <p>
     * Populated from {@link BotConfiguration#getStrategyId()} in
     * {@link #setConfiguration(BotConfiguration)}. Surfaced via
     * {@code BotHealthDTO.strategyId} so operators can see the per-bot
     * assignment in {@code GET /api/v1/bot-group/{id}/health}.
     * <p>
     * May be {@code null} for legacy callers that build a {@code BotConfiguration}
     * without going through the group-start assignment path (currently none in
     * production code; defensive against test fixtures that use the builder
     * directly).
     */
    @Getter
    protected StrategyId strategyId;

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

    // In-memory cumulative winnings, mirroring the {@code bot_winnings_total}
    // Prometheus counter value-for-value (BOTGROUP_GAME_MANAGEMENT AD-8). Incremented
    // at the exact site that calls {@code metrics.incBotWinnings(w)} in the endgame /
    // spin-result path, guarded on {@code w > 0} — but, unlike the metric, NOT gated on
    // {@code metrics != null}: it backs the group "average winning" stat independently
    // of whether Prometheus is wired. Read only via the group stats enrichment (never
    // from Prometheus in the sort/enrich path, AD-4).
    @Getter
    protected final AtomicLong cumulativeWinnings = new AtomicLong(0);

    // Per-bot count of completed rounds observed since this bot was constructed
    // (BOTGROUP_GAME_MANAGEMENT AD-9). Incremented once per completed round —
    // {@code onEndGame} for betting/Tai Xiu, spin-result for slots. The group
    // "rounds since last restart" stat is the MAX of this counter across the
    // group's bots (dedup-free and robust to server-side subscriber pruning).
    // Resets naturally because bots are freshly built on every start/restart.
    @Getter
    protected final AtomicLong roundsObserved = new AtomicLong(0);

    @Getter
    private volatile BotStatus status = BotStatus.AUTHENTICATING;

    // Timestamp of the most recent transition INTO DEAD. Cleared when the bot exits
    // DEAD (transition to any other status) or when its DEAD window is credited at
    // cleanup(). Volatile because transitionStatus may be invoked from many threads
    // (Netty IO, reconnect virtual threads, scenario pool, watchdog scheduler).
    private volatile Instant deadSince;

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
        // The assignment is logged once at INFO in BotGroupBehaviorService.createSingleBot
        // — surface it here as a field for the health DTO and (in Phase 5) the
        // strategy-factory lookup. Null-tolerant because some test fixtures
        // build a BotConfiguration with no strategyId.
        this.strategyId = configuration.getStrategyId();
        log.debug("Bot {} configured", userName);
        return this;
    }

    public Bot setMetrics(BotMetrics metrics) {
        this.metrics = metrics;
        return this;
    }

    public Bot setSessionAggregator(SessionAggregationService sessionAggregator) {
        this.sessionAggregator = sessionAggregator;
        return this;
    }

    /**
     * Wire the group-scoped {@link BetCoordinator}. Null-tolerant and fluent,
     * mirroring {@link #setSessionAggregator}. A {@code null} argument (coordination
     * off) leaves the bot on today's byte-for-byte path — {@code applyCoordination}
     * becomes identity. Injected by the runtime startBot loop before {@code start()},
     * so the scenario never sees a half-wired bot.
     */
    public Bot setCoordinator(BetCoordinator coordinator) {
        this.coordinator = coordinator;
        return this;
    }

    /**
     * Wire the group-scoped {@link JackpotScaler}. Null-tolerant and fluent,
     * mirroring {@link #setCoordinator}. A {@code null} argument (jackpot-scale off
     * or type ineligible) leaves the bot on today's byte-for-byte path — the
     * effective per-round bet cap stays the configured {@code maxBetsPerRound}
     * (factor 1.0). Injected by the runtime startBot loop before {@code start()},
     * so the scenario never sees a half-wired bot.
     */
    public Bot setJackpotScaler(JackpotScaler jackpotScaler) {
        this.jackpotScaler = jackpotScaler;
        return this;
    }

    public Bot initialize() {
        BotMdc.set(
                configuration.getBotGroupId(),
                configuration.getBotIndex(),
                configuration.getEnvironmentId(),
                configuration.getGame().getGameType().name(),
                configuration.getGame().getId(),
                configuration.getGame().getName(),
                userName
        );
        // Snapshot MDC immediately after BotMdc.set(...) so it's available before any
        // async callback can fire. configureClient(client) registers onWsStatusChange and
        // onDisconnect listeners that the library can invoke on its own threads as soon as
        // client.connect() returns; if the snapshot were captured later, those early
        // callbacks would see a null snapshot and silently skip MDC propagation.
        this.mdcSnapshot = MDC.getCopyOfContextMap();
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

            log.debug("Bot initialized and connected. Client: {}",
                     System.identityHashCode(client));

            return this;
        } finally {
            BotMdc.clear();
        }
    }

    protected abstract void initializeSubclass();

    public void cleanup() {
        stopped = true;
        log.debug("Cleaning up bot {}", userName);
        if (client != null && client.isOpen()) {
            try {
                stop();
            } catch (Exception e) {
                log.error("Error stopping bot {} during cleanup", userName, e);
            }
        }
        // Credit the terminal DEAD window, if any. cleanup() is invoked from
        // BotGroupRuntime.stopAllBots() on the BehaviorService thread, which has no
        // MDC. Re-apply the bot's snapshot so the dead-seconds counter is tagged
        // with the same {botGroupId,environmentId,gameType} as the rest of this
        // bot's meters. mdcWrap is null-safe on missing snapshot.
        mdcWrap(this::creditDeadSeconds).run();
    }

    public void restart() {
        log.info("Bot {}: restart requested", userName);
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
        log.debug("Bot {} stopping. Closing client instance: {}",
                 userName, System.identityHashCode(client));
        client.close();
    }

    protected void connectToSocket() {
        client.connect();
    }

    public void authenticate() {
        try {
            this.tokens = apiGatewayClient.authenticate(credentials);
            log.debug("Bot {}: authentication succeeded", userName);
        } catch (Exception e) {
            log.error("Bot {}: Authentication failed: {}", userName, e.getMessage());
        }
    }

    public void logout() {
        try {
            stop();
            log.debug("Bot {}: Logged out", userName);
        } catch (Exception e) {
            log.error("Bot {}: Logout failed: {}", userName, e.getMessage());
        }
    }

    public void deposit() {
        if (lastFetchedBalance < 0) {
            return;
        }

        // Deposit via the gwms bot-deposit endpoint (credits the game-spendable
        // wallet partition). Replaces the legacy GameMsClient agency-transfer path,
        // which credited the agency partition the game engine never debits — the
        // P_097/BOM "balance visible but every bet rejected" symptom.
        boolean success = apiGatewayClient.deposit(userName, 1_000_000_000L);
        if (success) {
            log.debug("Bot {}: Deposit successful, fetching new balance...", userName);
            if (metrics != null) metrics.incBotAutoDeposit(true);
            recordFetchedBalance(apiGatewayClient.getBalance(
                getClient().getAuthToken(),
                credentials.getFingerprint(),
                userName
            ));
            expectedCurrentBalance.set(lastFetchedBalance);
            log.debug("Bot {}: New balance: {}", userName, expectedCurrentBalance);
        } else {
            log.warn("Bot {}: Deposit failed", userName);
            if (metrics != null) metrics.incBotAutoDeposit(false);
        }
    }

    protected long checkBalance() {
        log.debug("checkBalance() ENTRY. lastFetched: {}, expected: {}, delta: {}",
                 lastFetchedBalance, expectedCurrentBalance.get(),
                 Math.abs(lastFetchedBalance - expectedCurrentBalance.get()));

        if (Math.abs(lastFetchedBalance - expectedCurrentBalance.get()) > 1_000_000L) {
            log.debug("checkBalance() fetching from server (delta > 1M)");
            recordFetchedBalance(apiGatewayClient.getBalance(
                getClient().getAuthToken(),
                credentials.getFingerprint(),
                userName
            ));
            log.debug("checkBalance() fetched: {}", lastFetchedBalance);
            expectedCurrentBalance.set(lastFetchedBalance);
        } else {
            log.debug("checkBalance() using cached: {}", expectedCurrentBalance.get());
        }

        return expectedCurrentBalance.get();
    }

    /**
     * Single anchor for every authoritative server balance fetch
     * (METRICS_IMPROVEMENT Phase 1, AD-1). Both {@code checkBalance()} and the
     * post-deposit re-fetch in {@code deposit()} route through here, and nowhere
     * else updates {@code lastFetchedBalance} from a server fetch.
     * <p>
     * Computes {@code delta = previousFetched - newBalance} and increments
     * {@code bot_money_drained_total} by {@code max(0, delta)} — a downward move
     * (real burn) records the drop; an upward move (the deposit top-up jump, or a
     * net-gain round) yields a negative delta that floors to 0, so top-ups never
     * register as drain (AD-1/AD-2). The first-ever fetch ({@code lastFetchedBalance
     * < 0}) only sets the anchor and records nothing.
     * <p>
     * Metric-only: no INFO lines (the existing DEBUG balance logs stay).
     */
    private void recordFetchedBalance(long newBalance) {
        if (lastFetchedBalance >= 0 && metrics != null) {
            long delta = lastFetchedBalance - newBalance;
            metrics.incMoneyDrained(Math.max(0, delta));
        }
        lastFetchedBalance = newBalance;
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
        if (prev == next) return; // idempotent re-entry — no log churn, no double-counting
        this.status = next;
        log.debug("Bot {}: {} → {}", userName, prev, next);
        if (next == BotStatus.DEAD) {
            // Stamp the start of this DEAD window. deadSince is cleared on exit
            // (below) or at cleanup() — so seeing a non-null value here would mean
            // the previous exit branch missed; we still re-stamp defensively.
            this.deadSince = Instant.now();
            if (metrics != null) metrics.incBotFailure();
        } else if (prev == BotStatus.DEAD) {
            // Bot revived: credit the just-closed DEAD window and clear the stamp.
            // Note: BotStatus.DEAD is terminal in current code (no revive path exists);
            // this branch is defensive in case a future REST endpoint or manual
            // recovery adds one. The terminal-DEAD-on-cleanup path is in cleanup().
            creditDeadSeconds();
        }
    }

    /**
     * If a DEAD window is currently open, credit its elapsed seconds to
     * {@code bot_dead_seconds_total} and clear the stamp. Idempotent — calling
     * twice without a new DEAD entry is a no-op.
     */
    private void creditDeadSeconds() {
        Instant since = this.deadSince;
        if (since == null) return;
        this.deadSince = null;
        if (metrics == null) return;
        long seconds = Duration.between(since, Instant.now()).toSeconds();
        metrics.incBotDeadSeconds(seconds);
    }

    private void configureClient(VingameWebSocketClient wsClient) {
        // Both callbacks may fire on threads with no MDC: onWsStatusChange runs on the
        // Netty IO loop (multiThreadIoEventLoopGroup-2-N) and on the library's
        // reconnect-<name> virtual thread; onDisconnect always runs on the library's
        // reconnect-<name> virtual thread. Wrap them so transitionStatus() / log lines
        // emitted inside carry the bot's identity.
        wsClient.onWsStatusChange(mdcConsumer(wsStatus -> {
            switch (wsStatus) {
                case CONNECTED -> {
                    transitionStatus(BotStatus.CONNECTED);
                    if (metrics != null) metrics.incBotWsEvent("connected");
                }
                case AUTHENTICATING_WS -> {
                    transitionStatus(BotStatus.AUTHENTICATING_CONNECTION);
                    if (metrics != null) metrics.incBotWsEvent("authenticating");
                }
                case DISCONNECTED -> {
                    if (metrics != null) metrics.incBotWsEvent("disconnected");
                }
                default -> {}
            }
        }));
        wsClient.onDisconnect(mdcWrap(() -> {
            if (!stopped) onWsDisconnected();
        }));
    }

    // ---- Reconnect logic ----

    private void onWsDisconnected() {
        // DEAD is terminal (RESILIENCE_HARDENING P1): a capped/dead bot must never be
        // resurrected. A late Netty onDisconnect from the last (now-closed) client — or a
        // still-running watchdog — could otherwise CAS the guard back on and spawn a fresh
        // reconnect loop, re-arming failure metrics and leaking the Netty client. Guard
        // consistently with stopped, before the CAS.
        if (stopped || status == BotStatus.DEAD) return;
        if (!reconnecting.compareAndSet(false, true)) {
            return; // reconnect loop already running — it will handle the retry
        }
        transitionStatus(BotStatus.RECONNECTING);
        log.warn("Bot {}: WS disconnected — starting retrial flow", userName);
        // One increment per reconnect EVENT, tagged by the originating reason.
        // Internal escalations (loop fall-through, performReauth) must not increment.
        if (metrics != null) metrics.incBotReconnect("ws-disconnect");
        Thread.ofVirtual().name("reconnect-" + userName).start(mdcWrap(this::runWsReconnectLoop));
    }

    // Called from the watchdog in BettingMiniGameBot — skips WS backoff, re-auths immediately
    protected void triggerFullReconnect(String reason) {
        // DEAD is terminal (RESILIENCE_HARDENING P1): the watchdog must not revive a bot
        // the reconnect cap already gave up on. Guard on DEAD alongside stopped, before the CAS.
        if (stopped || status == BotStatus.DEAD) return;
        if (!reconnecting.compareAndSet(false, true)) {
            return; // reconnect already in progress
        }
        transitionStatus(BotStatus.RECONNECTING);
        log.warn("Bot {}: full reconnect triggered — {}", userName, reason);
        if (metrics != null) {
            metrics.incBotReconnect(normalizeReconnectReason(reason));
        }
        if (client != null && client.isOpen()) {
            client.close();
        }
        Thread.ofVirtual().name("reconnect-" + userName).start(mdcWrap(this::runAuthThenWsLoop));
    }

    /**
     * Normalize the free-form reconnect reason string to a small bounded enum-like value
     * used as the {@code reason} tag on {@code bot_reconnects_total}. Cardinality budget
     * (Architecture Decision 7): {@code watchdog | ws-disconnect | reauth-cycle}.
     */
    private static String normalizeReconnectReason(String raw) {
        if (raw == null) return "ws-disconnect";
        if (raw.startsWith("watchdog")) return "watchdog";
        return "ws-disconnect";
    }

    private void runWsReconnectLoop() {
        runWsReconnectLoop(0);
    }

    /**
     * WS reconnect worker with an absolute cap on full back-off cycles
     * (RESILIENCE_HARDENING P1, Gap A). {@code startCycle} is the number of
     * re-auth cycles already consumed before entering this loop — 0 for the
     * WS-disconnect path, 1 for {@link #runAuthThenWsLoop()} (which performed an
     * immediate re-auth before falling through here, so the cap cannot be bypassed
     * by re-entering via that path).
     */
    private void runWsReconnectLoop(int startCycle) {
        // No metric increment here: the reconnect event was already counted by
        // onWsDisconnected (reason=ws-disconnect) or triggerFullReconnect (reason=
        // watchdog). This loop is the worker that retries; falling through from
        // runAuthThenWsLoop must not double-count either. The cycle cap below marks
        // the bot DEAD (not a reconnect event) so it also does not re-increment.
        int attempt = 0;
        int cycle = startCycle;
        while (!stopped) {
            long delaySecs = BACKOFF_SECONDS[Math.min(attempt, BACKOFF_SECONDS.length - 1)];
            sleep(delaySecs * 1000);
            if (stopped) return;

            if (tryReconnectWs()) {
                sleep(RECONNECT_CONFIRM_SECONDS * 1000);
                if (!stopped && client != null && client.isOpen()) {
                    log.debug("Bot {}: reconnected to WS (attempt {})", userName, attempt + 1);
                    reconnecting.set(false);
                    return;
                }
                log.debug("Bot {}: reconnect attempt {} did not hold", userName, attempt + 1);
            }

            attempt++;

            // After full backoff sequence exhausted, count the cycle and either
            // re-authenticate and start over, or give up once the absolute cap is hit.
            if (attempt >= BACKOFF_SECONDS.length) {
                cycle++;
                if (cycle >= MAX_RECONNECT_CYCLES) {
                    log.warn("Bot {}: giving up after {} reconnect cycles — marking DEAD",
                            userName, cycle);
                    transitionStatus(BotStatus.DEAD);
                    reconnecting.set(false);
                    // Close the last WS client so its wired onDisconnect handler can't fire
                    // later and try to resurrect a DEAD bot (the DEAD guard in
                    // onWsDisconnected also blocks it — this releases the Netty channel too).
                    closeClientQuietly();
                    return;
                }
                if (!performReauth()) return; // marks DEAD if auth fails
                attempt = 0;
            }
        }
    }

    private void runAuthThenWsLoop() {
        // No metric increment here either: triggerFullReconnect counted this event
        // with its originating reason (typically watchdog). The "reauth-cycle"
        // tag is unused in current code; if a future caller needs it, increment
        // at that callsite before spawning this loop.
        if (stopped) return;
        if (!performReauth()) return;
        if (stopped) return;

        if (tryReconnectWs()) {
            sleep(RECONNECT_CONFIRM_SECONDS * 1000);
            if (!stopped && client != null && client.isOpen()) {
                log.debug("Bot {}: reconnected after full re-auth", userName);
                reconnecting.set(false);
                return;
            }
        }
        // This path already consumed one re-auth cycle above; enter the loop at
        // cycle=1 so the MAX_RECONNECT_CYCLES cap cannot be bypassed via the
        // watchdog-triggered full-reconnect route.
        runWsReconnectLoop(1);
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
            // Terminal DEAD (e.g. "account does not exist"): close the last WS client so its
            // onDisconnect handler can't fire later and re-arm the reconnect machinery.
            closeClientQuietly();
            return false;
        }
    }

    /**
     * Close the current WS {@code client} if open, swallowing any error. Used on terminal
     * DEAD paths so the client's wired {@code onDisconnect} handler cannot fire afterwards
     * and revive the bot. Null- and double-close-safe: reads {@code client} once and guards
     * on {@code isOpen()}; the DEAD status guard in {@link #onWsDisconnected()} makes any
     * onDisconnect that does fire a no-op regardless.
     */
    private void closeClientQuietly() {
        VingameWebSocketClient c = this.client;
        if (c != null && c.isOpen()) {
            try {
                c.close();
            } catch (Exception e) {
                log.debug("Bot {}: error closing WS client on terminal DEAD path: {}",
                        userName, e.getMessage());
            }
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

    protected abstract Scenario botBehaviorScenario();

    protected void creditBalance(long amount) {
        this.expectedCurrentBalance.addAndGet(-amount);
        this.totalBetsPlaced.incrementAndGet();
        this.totalBetAmount.addAndGet(amount);
        // Bet counters (bot_bets_placed_total, bot_bet_amount_total) moved to
        // BettingMiniGameBot.onEndGame's HasBetTotals branch — authoritative
        // server-side recording (see docs/plans/ENDGAME_METRICS.md AD-4).
        // The local AtomicLong accumulators above still count bets SENT and
        // remain readable via BotHealthDTO; the Prometheus counters now count
        // bets CONFIRMED by the server's EndGame payload.
    }

    public final void start() {
        log.debug("Bot starting. Client: {} | Thread: {}",
                 System.identityHashCode(client), Thread.currentThread().getName());
        onStart();
        transitionStatus(BotStatus.STARTED);
    }

    protected abstract void onStart();
}
