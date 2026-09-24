package com.vingame.bot.domain.bot.core;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.domain.bot.coordination.BetCoordinator;
import com.vingame.bot.domain.bot.coordination.JackpotScaler;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator;
import com.vingame.bot.infrastructure.observability.ScopedDebugEscalator;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import com.vingame.bot.config.bot.BotBehaviorConfig;
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
import java.util.function.BooleanSupplier;
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

    /**
     * This environment's gateway request budget (GATEWAY_REQUEST_BUDGET AD-1) — set via a
     * builder-style setter by {@code BotFactory}, which reads it off {@code EnvironmentClients}
     * so a bot and its {@code ApiGatewayClient} can never end up on different budgets.
     * <p>
     * Used here for the <b>WebSocket upgrade</b> only: the HTTP requests a bot makes are
     * already funnelled inside {@code ApiGatewayClient}, and what is left is
     * {@code client.connect()}, which this class owns. Initialised to
     * {@link GatewayBudget#UNLIMITED} rather than left null, so a fixture that builds a bot
     * without Spring behaves exactly as it did before this feature — the same posture as
     * {@code metrics} and {@code sessionAggregator}, except that the fallback here is a real
     * object rather than a null check at every call site.
     */
    protected GatewayBudget gatewayBudget = GatewayBudget.UNLIMITED;

    // Observability — set via builder-style setter (BotFactory wires the singleton bean).
    protected BotMetrics metrics;

    // Per-session log aggregation — set via builder-style setter (BotFactory wires the
    // singleton bean). Null-tolerant exactly like {@code metrics}: unit-test fixtures
    // that build a bot without Spring leave it null and every feed callsite guards on it.
    protected SessionAggregationService sessionAggregator;

    // Group-level lifecycle aggregation (LOG_VOLUME_TIERING tier 1) — set via
    // builder-style setter (BotFactory wires the singleton bean), mirroring
    // {@code sessionAggregator}. Folds the per-bot "initialized" and "triggering
    // deposit" lines into one line per group; null-tolerant, so a fixture without
    // Spring simply gets the per-bot DEBUG lines and no aggregate.
    protected GroupLifecycleAggregator groupLifecycleAggregator;

    // Auto-escalation of scoped per-group DEBUG (LOG_VOLUME_TIERING AD-12) — set via
    // builder-style setter (BotFactory wires the singleton bean), mirroring
    // {@code groupLifecycleAggregator}. Fed from the reconnect sites here and the
    // watchdog site in BettingMiniGameBot; null-tolerant, so a fixture without Spring
    // simply never escalates.
    protected ScopedDebugEscalator scopedDebugEscalator;

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
     * Strategy registry key assigned to this bot at start by the group's
     * strategy mix.
     * <p>
     * Populated from {@link BotConfiguration#getStrategyId()} in
     * {@link #setConfiguration(BotConfiguration)}. Surfaced via
     * {@code BotHealthDTO.strategyId} so operators can see the per-bot
     * assignment in {@code GET /api/v1/bot-group/{id}/health}.
     * <p>
     * A {@code String} and not a {@link StrategyId} since PLUGIN_HOT_RELOAD
     * Phase 2b (AD-12); for a built-in it is exactly a {@link StrategyId}
     * constant name, and the JSON it renders to on the health DTO is unchanged.
     * <p>
     * May be {@code null} for legacy callers that build a {@code BotConfiguration}
     * without going through the group-start assignment path (currently none in
     * production code; defensive against test fixtures that use the builder
     * directly).
     */
    @Getter
    protected String strategyId;

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
     * Wire the app-scoped {@link GroupLifecycleAggregator} (LOG_VOLUME_TIERING tier 1).
     * Null-tolerant and fluent, mirroring {@link #setSessionAggregator}. Must be set
     * before {@link #initialize()}, because {@code initializeSubclass()} is where the
     * per-bot "initialized" feed happens.
     */
    public Bot setGroupLifecycleAggregator(GroupLifecycleAggregator groupLifecycleAggregator) {
        this.groupLifecycleAggregator = groupLifecycleAggregator;
        return this;
    }

    /**
     * Wire the app-scoped {@link ScopedDebugEscalator} (LOG_VOLUME_TIERING AD-12).
     * Null-tolerant and fluent, mirroring {@link #setGroupLifecycleAggregator}. A null
     * argument means this bot's early-warning signals never arm scoped DEBUG — the
     * signals themselves (the WARN lines, {@code bot_reconnects_total},
     * {@code bot_watchdog_expired_total}) are unaffected either way.
     */
    public Bot setScopedDebugEscalator(ScopedDebugEscalator scopedDebugEscalator) {
        this.scopedDebugEscalator = scopedDebugEscalator;
        return this;
    }

    /**
     * Feed one reconnect EVENT to the auto-escalator. Called from exactly the two sites
     * that increment {@code bot_reconnects_total}, so the metric and the escalation
     * count the same things (AD-12). Never lets an escalation problem break a reconnect.
     */
    protected void notifyReconnectEscalation() {
        if (scopedDebugEscalator == null || configuration == null) {
            return;
        }
        try {
            scopedDebugEscalator.onReconnect(configuration.getBotGroupId());
        } catch (Exception e) {
            log.warn("Bot {}: scoped-debug escalation failed: {}", userName, e.getMessage());
        }
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

    /**
     * Wire this bot's environment gateway budget. Null-tolerant: a null resolves to
     * {@link GatewayBudget#UNLIMITED}, because a bot that silently NPEs on its first
     * {@code connect()} is a worse outcome than a bot whose WebSocket upgrade is uncounted.
     */
    public Bot setGatewayBudget(GatewayBudget gatewayBudget) {
        this.gatewayBudget = gatewayBudget == null ? GatewayBudget.UNLIMITED : gatewayBudget;
        return this;
    }

    public Bot initialize() {
        BotMdc.set(
                configuration.getBotGroupId(),
                configuration.getBotIndex(),
                configuration.getEnvironmentId(),
                productCode(),
                configuration.getGame().getGameType().name(),
                configuration.getGame().getId(),
                configuration.getGame().getName(),
                userName
        );
        // PLUGIN_HOT_RELOAD AD-11. Ordering is load-bearing: this must precede the
        // mdcSnapshot capture below, or the tag is missing from every line emitted by an
        // async callback — which, for a bot that dies during connect, is most of them.
        BotMdc.setPluginVersion(getPluginVersion());
        // Snapshot MDC immediately after BotMdc.set(...) so it's available before any
        // async callback can fire. configureClient(client) registers onWsStatusChange and
        // onDisconnect listeners that the library can invoke on its own threads as soon as
        // client.connect() returns; if the snapshot were captured later, those early
        // callbacks would see a null snapshot and silently skip MDC propagation.
        this.mdcSnapshot = MDC.getCopyOfContextMap();
        // Materialise the round-outcome counters at zero while the MDC is populated,
        // so a group that never settles a round reports 0 instead of vanishing from
        // /actuator/prometheus entirely. Group-scoped tags, so this is five series
        // per group and a map lookup for every bot after the first. See
        // BotMetrics#preRegisterOutcomeCounters for why the distinction matters.
        if (metrics != null) metrics.preRegisterOutcomeCounters();
        try {
            log.debug("Initializing bot {}", userName);

            transitionStatus(BotStatus.AUTHENTICATING);
            // ESSENTIAL (GATEWAY_REQUEST_BUDGET AD-3): this is the group-start path — /start,
            // /restart, the startup chain, activation and recovery all reach here — and a bot
            // that cannot log in does not exist.
            this.tokens = apiGatewayClient.authenticate(credentials, RequestTier.ESSENTIAL, scope());
            transitionStatus(BotStatus.AUTHENTICATED);

            this.client = clientFactory.newClient(tokens, userName);
            initializeSubclass();
            configureClient(client);

            transitionStatus(BotStatus.CONNECTING);
            log.debug("Setting auth tokens [agency: {}..., auth: {}...]",
                     tokens.getAgencyToken().substring(0, 10),
                     tokens.getAuthToken().substring(0, 10));
            connectUnderBudget(RequestTier.ESSENTIAL, client);

            log.debug("Bot initialized and connected. Client: {}",
                     System.identityHashCode(client));

            return this;
        } finally {
            BotMdc.clear();
        }
    }

    /**
     * Numeric product code for this bot ({@code ProductCode.getCode()}, e.g. {@code "116"})
     * — the {@code product} MDC key / metric label of VIPTALK_ALERTING_V2 AD-V1.
     * <p>
     * Delegates to {@link BotConfiguration#resolveProductCode()}, which reads the product
     * the group's <b>environment</b> resolved at start and only falls back to the
     * {@link Game} document. One implementation for every site that labels a meter, so the
     * bot-scoped counters and the environment-scoped gauges cannot disagree about which
     * product room an alert belongs in. {@code null} when neither knows one; MDC and the
     * meter filter both skip nulls, so such a bot simply carries no {@code product} label.
     */
    protected String productCode() {
        return configuration.resolveProductCode();
    }

    /**
     * The plugin version this bot's product implementation was loaded from
     * (PLUGIN_HOT_RELOAD AD-11), e.g. {@code "builtin"} — never {@code null}.
     * <p>
     * Delegates to {@link BotConfiguration#resolvePluginVersion()} so the MDC tag on this
     * bot's lines and the {@code bots_by_plugin_version} row it is counted in can never
     * disagree. Public because the per-group gauge iterates live {@code Bot} instances;
     * there is deliberately no INFO line carrying it — {@code pluginVersion} is a per-bot
     * fact and its INFO-tier representation is the group-level gauge (AD-9).
     */
    public String getPluginVersion() {
        return configuration.resolvePluginVersion();
    }

    protected abstract void initializeSubclass();

    public void cleanup() {
        stopped = true;
        log.debug("Cleaning up bot {}", userName);
        // Unconditional (BOT_LIVENESS_SEMANTICS AD-6): a bot torn down while its channel
        // is already dead still owns the client's scenario / ping / processor resources,
        // and the old isOpen() guard skipped exactly that teardown.
        if (client != null) {
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
        // LOG_VOLUME_TIERING tier 1: DEBUG, not INFO. Periodic logout restarts one bot
        // per group per cycle, so at 30k bots this was a standing ~8 INFO lines/s that
        // said nothing a group-level line does not. The "why" is already at INFO at
        // group level — the periodic-logout cycle line — and this is the per-bot detail
        // under it.
        log.debug("Bot {}: restart requested", userName);
        // Close the outgoing client unconditionally BEFORE its reference is overwritten
        // (BOT_LIVENESS_SEMANTICS AD-6). Previously guarded on isOpen(), so a restart on a
        // dead channel — the common case, since restart is what a dead channel provokes —
        // leaked the client and its sendAsync pipeline.
        closeQuietly(this.client);
        this.client = clientFactory.newClient(tokens, userName);
        configureClient(client);
        transitionStatus(BotStatus.CONNECTING);
        // DEFAULT (AD-3): this is the periodic-logout restart — one bot per group per hour,
        // reusing its existing tokens (no re-login). Nothing is broken and nothing is
        // starting, so it yields to both other tiers.
        connectUnderBudget(RequestTier.DEFAULT, client);
        start();
    }

    public void stop() {
        log.debug("Bot {} stopping. Closing client instance: {}",
                 userName, System.identityHashCode(client));
        client.close();
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
        long depositAmount = resolveDepositAmount();
        // PRIORITIZED (AD-3), for the deposit and the read that confirms it: a bot that
        // cannot top up stops betting, but it is already up, so it does not outrank a group
        // that is still coming up.
        boolean success = apiGatewayClient.deposit(
                userName, depositAmount, RequestTier.PRIORITIZED, scope());
        if (success) {
            log.debug("Bot {}: Deposit of {} successful, fetching new balance...", userName, depositAmount);
            if (metrics != null) metrics.incBotAutoDeposit(true);
            recordFetchedBalance(apiGatewayClient.getBalance(
                getClient().getAuthToken(),
                credentials.getFingerprint(),
                userName,
                RequestTier.PRIORITIZED,
                scope()
            ));
            expectedCurrentBalance.set(lastFetchedBalance);
            log.debug("Bot {}: New balance: {}", userName, expectedCurrentBalance);
        } else {
            log.warn("Bot {}: Deposit failed", userName);
            if (metrics != null) metrics.incBotAutoDeposit(false);
        }
    }

    protected long checkBalance() {
        long syncThreshold = balanceSyncThreshold();
        log.debug("checkBalance() ENTRY. lastFetched: {}, expected: {}, delta: {}, threshold: {}",
                 lastFetchedBalance, expectedCurrentBalance.get(),
                 Math.abs(lastFetchedBalance - expectedCurrentBalance.get()), syncThreshold);

        if (Math.abs(lastFetchedBalance - expectedCurrentBalance.get()) > syncThreshold) {
            log.debug("checkBalance() fetching from server (delta > {})", syncThreshold);
            // AD-3: the FIRST read (lastFetchedBalance < 0) is ESSENTIAL, every later one is
            // DEFAULT. The first read is not "the first round" — it is on the start path:
            // BettingMiniGameBot.onStart calls onNewSession() BEFORE it installs its
            // scenario, and expectedCurrentBalance is seeded at -100M, so this branch always
            // fires once per bot at start. A bot whose first read fails never installs its
            // scenario and becomes a silent zombie; a bot whose drift re-sync is deferred
            // simply plays on its local estimate for another round.
            RequestTier tier = lastFetchedBalance < 0 ? RequestTier.ESSENTIAL : RequestTier.DEFAULT;
            recordFetchedBalance(apiGatewayClient.getBalance(
                getClient().getAuthToken(),
                credentials.getFingerprint(),
                userName,
                tier,
                scope()
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

    /**
     * Amount credited by a single auto-deposit top-up when the group's
     * {@code BotBehaviorConfig} carries no explicit {@code depositAmount}
     * (i.e. it is {@code 0}). This is the value {@link #deposit()} used
     * unconditionally before {@code bot.deposit.amount} made it configurable, so
     * any caller that builds a config without the field keeps the prior behavior.
     */
    public static final long DEFAULT_DEPOSIT_AMOUNT = 1_000_000_000L;

    /**
     * Resolve the per-top-up deposit amount for this bot: the configured
     * {@code depositAmount} when positive, else {@link #DEFAULT_DEPOSIT_AMOUNT}.
     * Null-safe on {@code configuration} for fixtures that call {@code deposit()}
     * without one.
     */
    private long resolveDepositAmount() {
        BotBehaviorConfig behavior = configuration != null
                ? configuration.getBehaviorConfig()
                : null;
        long configured = behavior != null ? behavior.getDepositAmount() : 0L;
        return configured > 0 ? configured : DEFAULT_DEPOSIT_AMOUNT;
    }

    /**
     * Auto-deposit trigger threshold as a percentage of the per-top-up deposit
     * amount. A bot tops up once its balance falls below this fraction of what a
     * single deposit credits.
     */
    public static final int MIN_BALANCE_PERCENT_OF_DEPOSIT = 10;

    /**
     * Local-vs-server balance drift, as a percentage of the deposit amount, above
     * which {@link #checkBalance()} re-reads the authoritative server balance
     * instead of trusting its local running figure.
     */
    public static final int BALANCE_SYNC_PERCENT_OF_DEPOSIT = 1;

    /**
     * Drift threshold at which {@link #checkBalance()} re-syncs from the server,
     * derived from the configured deposit amount rather than fixed.
     * <p>
     * This was a hardcoded {@code 1_000_000L}, which only suited one currency
     * scale and, on a small deposit, meant a bot could stake a large fraction of
     * its balance without ever re-reading the server. That is precisely how the
     * 2026-08-10 prod TIP freeze stayed invisible: bots funded with 5,000,000
     * staked up to 358,000 each — 7% of balance — and never crossed the 1,000,000
     * drift, so the app never noticed the server balance had not moved at all.
     * At 1% of a 5,000,000 deposit the re-read now happens every 50,000 of drift.
     * <p>
     * Floored at 1 so a very small configured deposit cannot produce a zero
     * threshold, which would re-fetch on every single call — each of which pays
     * the {@code getBalance} round trip.
     */
    private long balanceSyncThreshold() {
        return Math.max(1L, resolveDepositAmount() * BALANCE_SYNC_PERCENT_OF_DEPOSIT / 100);
    }

    /**
     * Balance below which auto-deposit fires, derived from the configured
     * {@link #resolveDepositAmount() deposit amount} rather than being a fixed
     * figure.
     * <p>
     * This was previously a hardcoded {@code 5_000_000L}, which only made sense
     * for one currency scale and — once {@code bot.deposit.amount} became
     * configurable — could equal the deposit amount itself. When threshold and
     * top-up are equal, the first deposit lands the bot exactly <i>at</i> the
     * threshold rather than clearing it, so the next bet immediately re-triggers
     * a second full top-up. Deriving the threshold keeps it an order of magnitude
     * below the credit, so one deposit always buys real runway.
     * <p>
     * Integer arithmetic throughout — no floating point on money.
     */
    protected long getMinBalance() {
        return resolveDepositAmount() * MIN_BALANCE_PERCENT_OF_DEPOSIT / 100;
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
        notifyReconnectEscalation();
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
        notifyReconnectEscalation();
        // Unconditional (BOT_LIVENESS_SEMANTICS AD-6), like every other close site. The old
        // isOpen() guard skipped the dead-channel case and left the close to happen only
        // after performReauth() returned — an auth-gateway round trip that, in the failure
        // mode this plan is written against, is the slow or hanging one. For that whole
        // window the orphan's sendAsync pipeline stayed scheduled and kept emitting
        // "Cannot send message, not connected". Closing here kills the emitter promptly.
        closeQuietly(this.client);
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
            // PRIORITIZED (AD-3): a bot that is already part of a running fleet and is
            // trying to get back in. Note AD-9 — from Phase 3 a GatewayBudgetException here
            // must NOT mark the bot DEAD (the catch below does, for every exception today);
            // it is one failed attempt of the existing backoff loop, because a request the
            // JVM chose not to send is not a gateway refusal.
            this.tokens = apiGatewayClient.authenticate(credentials, RequestTier.PRIORITIZED, scope());
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
     * Close the current WS {@code client}, swallowing any error. Used on terminal
     * DEAD paths so the client's wired {@code onDisconnect} handler cannot fire afterwards
     * and revive the bot. Null-safe; the DEAD status guard in {@link #onWsDisconnected()}
     * makes any onDisconnect that does fire a no-op regardless.
     * <p>
     * No longer guarded on {@code isOpen()} (BOT_LIVENESS_SEMANTICS AD-6) — that guard meant
     * the terminal path skipped the close for precisely the clients that needed it, since a
     * bot reaching a terminal state normally has a dead channel.
     */
    private void closeClientQuietly() {
        closeQuietly(this.client);
    }

    /**
     * Close {@code c} unconditionally — no {@code isOpen()} guard — swallowing any error.
     * <p>
     * The library's {@code close()} is one-shot, idempotent and self-sufficient in 3.0.5: it
     * shuts scenarios (and with them every {@code SendAsync} scheduler), the ping scheduler,
     * the channel and the message-processor pool, and it runs even for a client that never
     * connected. Calling it on a dead-channel or never-connected client is therefore both
     * safe and necessary — skipping it is what produced orphan clients. Because the library
     * guards re-entry with {@code isClosing.getAndSet(true)}, a redundant close from a second
     * site is a no-op and needs no accounting on our side.
     * <p>
     * A throw out of {@code close()} means the scenario / ping / processor resources were NOT
     * reclaimed — i.e. exactly the leak this phase exists to prevent — so it is logged at WARN
     * with the throwable, matching the level {@code cleanup()} already uses for the same
     * failure reached through {@link #stop()}. The library catches almost everything inside
     * {@code close()}, so an escape is genuinely exceptional and will not be noisy.
     */
    private void closeQuietly(VingameWebSocketClient c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception e) {
            log.warn("Bot {}: error closing WS client {}",
                    userName, System.identityHashCode(c), e);
        }
    }

    private boolean tryReconnectWs() {
        // Close the outgoing client BEFORE this.client is overwritten below. The pre-Phase-1
        // code closed it only when isOpen(), i.e. never for the dead channel that caused the
        // reconnect in the first place — the reference was then dropped while the client's
        // sendAsync pipeline was still scheduled and still closing over this live bot's
        // fields, so it kept evaluating canBet() and logging "Cannot send message, not
        // connected" for the life of the process (BOT_LIVENESS_SEMANTICS AD-6).
        closeQuietly(this.client);
        VingameWebSocketClient fresh = null;
        try {
            fresh = clientFactory.newClient(tokens, userName);
            this.client = fresh;
            configureClient(fresh);
            transitionStatus(BotStatus.CONNECTING);
            connectUnderBudget(RequestTier.PRIORITIZED, fresh);
            beforeReconnect();
            start();
            return true;
        } catch (Exception e) {
            log.debug("Bot {}: WS reconnect attempt failed: {}", userName, e.getMessage());
            // The half-built client already owns its message-processor workers (started in
            // the library constructor) and may have picked up scenarios from a partial
            // start(). Close it here rather than leaving it to the next attempt, which may
            // never come — the bot can be stopped, or the loop can give up, before then.
            closeQuietly(fresh);
            return false;
        }
    }

    /**
     * The single WebSocket-upgrade call site (GATEWAY_REQUEST_BUDGET AD-3).
     * <p>
     * All three upgrades a bot ever performs go through here, each with its own tier:
     * {@link RequestTier#ESSENTIAL} from {@link #initialize()}, {@link RequestTier#DEFAULT}
     * from {@link #restart()} (periodic logout), {@link RequestTier#PRIORITIZED} from
     * {@link #tryReconnectWs()}. {@code GatewayCallSiteGuardTest} fails the build if a
     * {@code .connect()} appears anywhere else in this class.
     * <p>
     * Whether the upgrade is counted at all is
     * {@code bot.gateway.budget.count-ws-upgrades} (Open Item 1: the WS hosts may not sit
     * behind the same Cloudflare rule as the {@code /gwms/v1/*} API host). The decision lives
     * in the budget rather than here, so the answer, when it arrives, is one flag in one
     * place instead of a condition at three call sites.
     * <p>
     * <b>Cancellation happens before this method runs, never inside it.</b>
     * {@code VingameWebSocketClient.connect()} swallows {@code InterruptedException} — it
     * logs, restores the flag, and returns normally with a <em>half-built</em> client — so
     * interrupting an upgrade in flight does not abort it, it corrupts it. The budget
     * therefore refuses admission to a cancelled scope and this call is simply never
     * reached (AD-8).
     */
    private void connectUnderBudget(RequestTier tier, VingameWebSocketClient target) {
        gatewayBudget.runWsUpgrade(tier, scope(), target::connect);
    }

    /**
     * This bot's {@link GatewayRequestScope}: who a queued request belongs to, and how the
     * budget learns it should no longer be sent.
     * <p>
     * Cancellation has two halves, and both are needed: a stopped bot's queued login is work
     * nobody wants any more, and so is <em>every</em> queued request of a group whose start has
     * been called off — that second half is what lets a {@code /stop} unwind a paced start
     * instead of waiting out its up-to-50 minutes. A request that is never admitted is never
     * stamped into the window, because it never left the JVM.
     * <p>
     * Null-safe on {@code configuration} for fixtures that reach a gateway call before
     * {@code setConfiguration} — such a scope simply carries no group and is not cancellable
     * by group, which is the truth about it.
     */
    private GatewayRequestScope scope() {
        return GatewayRequestScope.forBot(
                configuration == null ? null : configuration.getBotGroupId(),
                userName,
                this::requestCancelled);
    }

    /**
     * Whether a queued gateway request of this bot's should still be sent: no if the bot has
     * been stopped, and no if the group start it belongs to has been cancelled
     * (GATEWAY_REQUEST_BUDGET AD-8).
     * <p>
     * A method rather than a composed lambda so {@link #scope()} — called once per gateway
     * request — allocates one method reference instead of a fresh closure chain, and so the
     * null-guard for a configuration-less fixture lives in one readable place.
     */
    private boolean requestCancelled() {
        if (isStopped()) {
            return true;
        }
        BooleanSupplier startCancelled =
                configuration == null ? null : configuration.getStartCancelled();
        return startCancelled != null && startCancelled.getAsBoolean();
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
