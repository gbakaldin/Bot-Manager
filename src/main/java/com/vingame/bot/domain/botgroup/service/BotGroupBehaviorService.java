package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.domain.bot.coordination.BetCoordinator;
import com.vingame.bot.domain.bot.coordination.JackpotScaler;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.bot.strategy.StrategyAssignment;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.WeightedStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.core.BotStatus;
import com.vingame.bot.domain.botgroup.dto.BotGroupHealthDTO;
import com.vingame.bot.domain.botgroup.dto.BotGroupStatsDTO;
import com.vingame.bot.domain.botgroup.dto.CoordinationStateDTO;
import com.vingame.bot.domain.botgroup.dto.JackpotScaleStateDTO;
import com.vingame.bot.domain.botgroup.dto.BotHealthDTO;
import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupFilter;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.sort.BotGroupSortRow;
import com.vingame.bot.domain.botgroup.sort.BotGroupSorter;
import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameFilter;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.domain.game.sort.GameSortRow;
import com.vingame.bot.domain.game.sort.GameSorter;
import com.vingame.bot.infrastructure.runtime.BotGroupRuntime;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.websocketparser.auth.AuthClient;
import com.vingame.websocketparser.exception.ValidationException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing bot group lifecycle: start, stop, restart, scheduling.
 * <p>
 * Uses parallel bot creation with controlled concurrency for fast startup at scale.
 * <p>
 * Scale targets:
 * - Production: up to 2,000 concurrent bots
 * - Load testing: up to 100,000 concurrent bots
 */
@Slf4j
@Service
public class BotGroupBehaviorService {

    private final BotGroupService botGroupService;
    private final EnvironmentService environmentService;
    private final GameService gameService;
    private final BotFactory botFactory;
    private final BotMetrics botMetrics;
    private final SessionAggregationService sessionAggregationService;

    /**
     * Max number of bots to create/authenticate simultaneously.
     * Controls concurrency to avoid overwhelming the game server's auth endpoint.
     * Configurable via application.properties: bot.creation.parallelism
     */
    @Value("${bot.creation.parallelism:10}")
    private int botCreationParallelism;

    /**
     * Global default for enabling periodic logout.
     * Can be overridden per-environment via Environment.periodicLogoutEnabled.
     */
    @Value("${bot.periodic-logout.enabled:true}")
    private boolean periodicLogoutEnabled;

    /**
     * Global default interval between logout cycles (minutes).
     * Can be overridden per-environment via Environment.periodicLogoutIntervalMinutes.
     */
    @Value("${bot.periodic-logout.interval-minutes:60}")
    private int periodicLogoutIntervalMinutes;

    /**
     * Delay between logout and reconnect (seconds).
     */
    @Value("${bot.periodic-logout.reconnect-delay-seconds:5}")
    private int reconnectDelaySeconds;

    /**
     * Fraction of bots that must be DEAD before the entire group is marked DEAD (0.0–1.0).
     */
    @Value("${bot.group.dead.threshold:0.80}")
    private double deadBotGroupThreshold;

    /**
     * Seconds without any game message before the watchdog triggers a full bot reconnect.
     */
    @Value("${bot.watchdog.timeout.seconds:180}")
    private long watchdogTimeoutSeconds;

    /**
     * Scheduler for timed operations (scheduled restarts, etc.)
     * Uses virtual threads for efficiency.
     */
    private final ScheduledExecutorService scheduler;

    /**
     * Executor for parallel bot creation.
     * Uses virtual threads for lightweight, scalable execution.
     */
    private final ExecutorService botCreationExecutor;

    // Runtime state map: groupId -> BotGroupRuntime
    private final ConcurrentHashMap<String, BotGroupRuntime> runningGroups = new ConcurrentHashMap<>();

    @Autowired
    public BotGroupBehaviorService(
            BotGroupService botGroupService,
            EnvironmentService environmentService,
            GameService gameService,
            BotFactory botFactory,
            BotMetrics botMetrics,
            SessionAggregationService sessionAggregationService
    ) {
        this.botGroupService = botGroupService;
        this.environmentService = environmentService;
        this.gameService = gameService;
        this.botFactory = botFactory;
        this.botMetrics = botMetrics;
        this.sessionAggregationService = sessionAggregationService;

        // Use virtual threads for scheduled tasks
        this.scheduler = Executors.newScheduledThreadPool(4, Thread.ofVirtual().factory());

        // Use virtual threads for parallel bot creation
        this.botCreationExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("bot-creation-", 0).factory()
        );

        log.info("BotGroupBehaviorService initialized with parallel bot creation (parallelism will be configured from properties)");
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down BotGroupBehaviorService executors...");
        scheduler.shutdownNow();
        botCreationExecutor.shutdownNow();
    }

    /**
     * Auto-start bot groups on application startup.
     * Starts all groups where targetStatus == ACTIVE.
     * <p>
     * Startup ownership (TIMED_ACTIVATION AD-10): groups with
     * {@code activationMode == SCHEDULED} are <b>skipped</b> here — the first
     * activation-reconciler tick owns starting them iff their window is
     * currently open, avoiding a boot-time start→immediate-stop churn for a
     * group whose window is closed. Non-scheduled ({@code null}) groups
     * auto-start on {@code targetStatus == ACTIVE} exactly as before; parked
     * {@code MANUAL_ON}/{@code MANUAL_OFF} groups are already governed by their
     * persisted {@code targetStatus}, so they resume correctly with no special
     * casing.
     */
    @PostConstruct
    public void onStartup() {
        log.info("Bot Manager starting up - checking for bot groups to auto-start");

        botGroupService.findByTargetStatus(BotGroupStatus.ACTIVE)
                .forEach(group -> {
                    if (group.getActivationMode() == ActivationMode.SCHEDULED) {
                        log.info("Skipping auto-start for scheduled bot group {} (ID: {}) — " +
                                "the activation reconciler owns it", group.getName(), group.getId());
                        return;
                    }
                    try {
                        log.info("Auto-starting bot group: {} (ID: {})", group.getName(), group.getId());
                        start(group.getId());
                    } catch (Exception e) {
                        log.error("Failed to auto-start bot group {} (ID: {}): {}",
                                group.getName(), group.getId(), e.getMessage(), e);
                    }
                });

        log.info("Bot Manager startup complete. {} bot groups running", runningGroups.size());
    }

    /**
     * Start a bot group - creates and starts bot instances in parallel.
     * <p>
     * Uses parallel execution with controlled concurrency (configurable via bot.creation.parallelism).
     * This dramatically reduces startup time compared to sequential creation:
     * - Sequential (old): 100 bots × 5s = 500s (~8 minutes)
     * - Parallel (new): 100 bots / 10 parallelism × ~5s = ~50s
     */
    public void start(String id) {
        // Check if already running
        if (runningGroups.containsKey(id)) {
            log.warn("Bot group {} is already running", id);
            return;
        }

        BotGroup group = botGroupService.findById(id);

        boolean started = false;
        // Capture the in-flight failure so the cleanup log in the finally
        // block can attach the cause. Without this, the failure-path log
        // line would lose the exception type, message, and stacktrace —
        // critical detail for the auto-start path on application ready,
        // where there is no advice in the call chain.
        Throwable failure = null;
        try {
            // Verify environment exists
            if (group.getEnvironmentId() == null) {
                throw new BadRequestException(
                        "BotGroup " + group.getName() + " has no environmentId set. " +
                                "Please assign an environment before starting the bot group."
                );
            }

            // Verify game exists
            if (group.getGameId() == null) {
                throw new BadRequestException(
                        "BotGroup " + group.getName() + " has no gameId set. " +
                                "Please assign a game before starting the bot group."
                );
            }

            // Load environment (throws ResourceNotFoundException if not found)
            Environment environment = environmentService.findById(group.getEnvironmentId());

            // Load game configuration (throws ResourceNotFoundException if not found)
            Game game = gameService.findById(group.getGameId());

            // Compute per-bot strategy assignment up-front. The identifier shape
            // (namePrefix + botIndex) matches what createSingleBot builds for
            // the username, so the assignment.get(username) lookup hits.
            // Done here (outside the parallel section) because the
            // fill-to-target algorithm needs the full bot-id list to apportion
            // weights — not a per-bot decision.
            List<String> botIdentifiers = new ArrayList<>(group.getBotCount());
            for (int i = 1; i <= group.getBotCount(); i++) {
                botIdentifiers.add(group.getNamePrefix() + i);
            }
            Map<String, StrategyId> strategyAssignment = StrategyAssignment.assign(
                    effectiveStrategyMix(group), botIdentifiers);

            // Create runtime state
            BotGroupRuntime runtime = new BotGroupRuntime(id, group.getBotCount(),
                    group.getEnvironmentId(), environment.getName());
            runningGroups.put(id, runtime);

            // BET_COORDINATION (AD-9/AD-10): build a group-scoped coordinator only
            // when enabled AND the game shares the betting-mini/TaiXiu round model
            // (SLOT has no shared-round betting — AD-10). Off ⇒ no coordinator,
            // every bot's ref stays null, the bet path is byte-for-byte today's.
            if (group.isCoordinationEnabled()
                    && (game.getGameType() == GameType.BETTING_MINI || game.getGameType() == GameType.TAI_XIU)) {
                BetCoordinator coordinator = new BetCoordinator(
                        game.getEffectiveOptionAffinities(),
                        group.getMaxAggregateStakePerRound(),
                        group.getMinBet(),
                        group.getBetIncrement());
                runtime.setCoordinator(coordinator);
                log.info("Bet coordinator created for group {} ({} options, aggregate cap {})",
                        group.getName(), game.getEffectiveOptionAffinities().size(),
                        group.getMaxAggregateStakePerRound());
            }

            // JACKPOT_SCALE_AND_RAMP (AD-J3/AD-S1): build a group-scoped jackpot
            // scaler only when the game's jackpotScaleEnabled AND the type shares the
            // betting-mini/TaiXiu round model. No per-type branch or skip-log — a Tai
            // Xiu game with no live tJpV on the wire simply never observes a non-zero
            // pool and stays neutral (AD-J3/AD-J5). Off ⇒ no scaler, every bot's ref
            // stays null, the bet path is byte-for-byte today's (AD-S3).
            if (game.isJackpotScaleEnabled()
                    && (game.getGameType() == GameType.BETTING_MINI || game.getGameType() == GameType.TAI_XIU)) {
                JackpotScaler jackpotScaler = new JackpotScaler(
                        game.getJackpotCeiling(),
                        JackpotScaler.DEFAULT_SEED_FLOOR,
                        0.25);
                runtime.setJackpotScaler(jackpotScaler);
                log.info("Jackpot scaler created for group {} (ceiling {})",
                        group.getName(), game.getJackpotCeiling());
                // AD-J9: optional coordinator-cap composition deferred
            }

            log.info("Creating {} bots for group {} with parallel execution (parallelism={})",
                    group.getBotCount(), group.getName(), botCreationParallelism);

            // Create bots in parallel with controlled concurrency
            List<Bot> bots = createBotsInParallel(group, environment, game, strategyAssignment);

            // Start all bots
            for (Bot bot : bots) {
                // BET_COORDINATION (Phase 3): inject the group-scoped coordinator
                // BEFORE startBot so the scenario never sees a half-wired bot.
                // Null when coordination is off ⇒ the bot bypasses coordination.
                bot.setCoordinator(runtime.getCoordinator());
                // JACKPOT_SCALE_AND_RAMP (AD-J8): inject the group-scoped jackpot
                // scaler BEFORE startBot, exactly like the coordinator. Null when
                // jackpot-scale is off ⇒ the bot's effective cap stays maxBetsPerRound.
                bot.setJackpotScaler(runtime.getJackpotScaler());
                runtime.startBot(bot);
                log.debug("Started bot {}", bot.getUserName());
            }

            // Start health monitoring
            startHealthMonitoring(runtime);

            // Start periodic logout scheduler
            startPeriodicLogoutScheduler(runtime, environment);

            // Update entity
            group.setTargetStatus(BotGroupStatus.ACTIVE);
            group.setLastStartedAt(LocalDateTime.now());
            group.setLastStoppedAt(null);
            botGroupService.save(group);

            log.info("Bot group {} started successfully with {} bots", group.getName(), bots.size());
            started = true;

        } catch (Throwable t) {
            // Capture for the finally-block log and rethrow unchanged — the
            // typed exception still propagates to RestExceptionHandler.
            // Wrapping in RuntimeException would erase the type and force
            // every failure into the generic 500 bucket.
            failure = t;
            throw t;
        } finally {
            if (!started) {
                BotGroupRuntime failedRuntime = runningGroups.remove(id);
                if (failedRuntime != null) {
                    try {
                        // Re-apply group MDC so any group-level dead-seconds
                        // increment ends up tagged with
                        // botGroupId/environmentId; the finally block runs on
                        // the same caller thread but may have lost the MDC if
                        // set earlier in start().
                        BotMdc.setGroupContext(failedRuntime.getGroupId(),
                                               failedRuntime.getEnvironmentId());
                        try {
                            failedRuntime.stopAllBots(botMetrics);
                            // Drop any session entries a partially-started group registered
                            // before the failure, so a failed start leaks nothing (AD-8).
                            sessionAggregationService.evictGroup(failedRuntime.getGroupId());
                        } finally {
                            BotMdc.clear();
                        }
                    } catch (Exception cleanupEx) {
                        // Pass cleanupEx as the final arg so SLF4J attaches
                        // the trace — otherwise an executor-shutdown failure
                        // would surface as a one-liner with no diagnostic.
                        log.error("Error cleaning up bots after failed start of group {}: {}",
                                group.getName(), cleanupEx.getMessage(), cleanupEx);
                    }
                }
                // Attach the captured failure so operators grepping for
                // "Failed to start bot group" see the cause inline. Matters
                // for the auto-start path (PostConstruct) where no advice
                // logs the exception elsewhere.
                log.error("Failed to start bot group {}: {}", group.getName(),
                        failure != null ? failure.toString() : "(unknown)", failure);
            }
        }
    }

    /**
     * Create bots in parallel with controlled concurrency.
     * <p>
     * Uses a Semaphore to limit how many bots are being created simultaneously,
     * preventing overwhelming the game server's authentication endpoint.
     *
     * @param group       The bot group configuration
     * @param environment The environment configuration
     * @param game        The game configuration
     * @return List of created and initialized bots
     */
    private List<Bot> createBotsInParallel(BotGroup group, Environment environment, Game game,
                                           Map<String, StrategyId> strategyAssignment) {
        int botCount = group.getBotCount();
        Semaphore semaphore = new Semaphore(botCreationParallelism);

        List<CompletableFuture<Bot>> futures = new ArrayList<>(botCount);

        for (int i = 1; i <= botCount; i++) {
            final int botIndex = i;

            CompletableFuture<Bot> future = CompletableFuture.supplyAsync(() -> {
                BotMdc.setGroupContext(group.getId(), group.getEnvironmentId());
                try {
                    semaphore.acquire();
                    try {
                        return createSingleBot(group, environment, game, botIndex, strategyAssignment);
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Bot creation interrupted", e);
                } finally {
                    BotMdc.clear();
                }
            }, botCreationExecutor);

            futures.add(future);
        }

        // Wait for all bots to be created and collect results
        List<Bot> bots = new ArrayList<>(botCount);
        List<Throwable> errors = new ArrayList<>();

        // The result-collection loop runs on the caller thread of start(), NOT on
        // the per-bot virtual thread (where MDC was set inside the supplyAsync
        // lambda and cleared in its finally). Without an explicit group MDC here,
        // bot_creation_failures_total would register without botGroupId/
        // environmentId tags — defeating Decision 5's per-group cardinality goal.
        // Mirror the same try/finally pattern used by start()'s outer catch
        // (lines 251-256) and stop() (lines 422-427).
        BotMdc.setGroupContext(group.getId(), group.getEnvironmentId());
        try {
            for (int i = 0; i < futures.size(); i++) {
                try {
                    Bot bot = futures.get(i).join();
                    bots.add(bot);
                } catch (Exception e) {
                    // Unwrap CompletionException → real cause; users care about the
                    // actual auth/validation failure, not the wrapper.
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.error("Failed to create bot {}/{} for group {} (env {}): {}",
                            i + 1, botCount, group.getId(), group.getEnvironmentId(),
                            cause.toString(), cause);
                    botMetrics.incBotCreationFailure(classifyCreationFailure(cause));
                    errors.add(e);
                }
            }
        } finally {
            BotMdc.clear();
        }

        if (!errors.isEmpty()) {
            log.warn("Created {}/{} bots successfully ({} failures) for group {}",
                    bots.size(), botCount, errors.size(), group.getId());
        }

        return bots;
    }

    /**
     * Classify a bot-creation failure into a bounded reason tag for
     * {@code bot_creation_failures_total}. Bounded labels keep Prometheus
     * cardinality low. RESTART_LIFECYCLE_FIX Architecture Decision 5.
     */
    private static String classifyCreationFailure(Throwable cause) {
        // UpstreamLoginException is the typed auth-failure path (API_ERROR_-
        // FORWARDING Phase B). Match it explicitly so it lands in "auth"
        // without depending on the message-substring heuristic below.
        if (cause instanceof com.vingame.bot.common.exception.UpstreamLoginException) {
            return "auth";
        }
        if (cause instanceof BadRequestException
                || cause instanceof IllegalStateException
                || cause instanceof IllegalArgumentException) {
            return "validation";
        }
        // websocket-parser's ValidationException (e.g. "Authentication configuration
        // is required...") is semantically validation, not auth. Without this
        // explicit arm it would land in the "auth" bucket below because its
        // message contains "auth". Post-RESTART_LIFECYCLE_FIX this path is
        // unreachable (BotFactory now throws IllegalStateException first), but
        // any future regression that re-introduces a builder-validation path
        // would otherwise silently mislabel as "auth".
        if (cause instanceof ValidationException) {
            return "validation";
        }
        // Match common auth-failure markers without forcing a hard dependency on
        // any specific exception type — ApiGatewayClient may throw various
        // IOExceptions / RuntimeExceptions wrapping the upstream auth failure.
        String className = cause.getClass().getSimpleName().toLowerCase();
        String message = cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";
        if (className.contains("auth") || message.contains("auth")
                || message.contains("login") || message.contains("token")) {
            return "auth";
        }
        return "unknown";
    }

    /**
     * Create a single bot with all necessary configuration.
     *
     * @param group              The bot group configuration
     * @param environment        The environment configuration
     * @param game               The game configuration
     * @param botIndex           The index of this bot (1-based)
     * @param strategyAssignment Map from username → assigned {@link StrategyId},
     *                           computed once per group start by
     *                           {@link StrategyAssignment#assign}. Lookup by
     *                           username; missing keys fall back to
     *                           {@link StrategyId#RANDOM} (defensive — the
     *                           assignment is built from the same identifier
     *                           shape, so a miss is a bug).
     * @return The created and initialized bot
     */
    private Bot createSingleBot(BotGroup group, Environment environment, Game game, int botIndex,
                                Map<String, StrategyId> strategyAssignment) {
        String username = group.getNamePrefix() + botIndex;
        String password = group.getPassword();

        // Generate a unique fingerprint for this bot
        String fingerprint = AuthClient.generateFingerprint();

        BotCredentials credentials = BotCredentials.builder()
                .username(username)
                .password(password)
                .fingerprint(fingerprint)
                .build();

        BotBehaviorConfig.BotBehaviorConfigBuilder behaviorConfigBuilder = BotBehaviorConfig.builder()
                .minBet(group.getMinBet())
                .maxBet(group.getMaxBet())
                .betIncrement(group.getBetIncrement())
                .maxTotalBetPerRound(group.getMaxTotalBetPerRound())
                .minBetsPerRound(group.getMinBetsPerRound())
                .maxBetsPerRound(group.getMaxBetsPerRound())
                .chatEnabled(group.isChatEnabled())
                .autoDepositEnabled(group.isAutoDepositEnabled());
        // BET_COORDINATION (AD-7): under coordination the coordinator is the sole
        // throttle, so per-bot skip is redundant — pin betSkipPercentage to 0 so
        // bots propose every eligible tick (maximal headroom for trim-only steering).
        // Currently unset ⇒ already 0; this makes the invariant explicit/future-proof.
        if (group.isCoordinationEnabled()) {
            behaviorConfigBuilder.betSkipPercentage(0);
        }
        // Bet ramp-up (JACKPOT_SCALE_AND_RAMP AD-R4/AD-R6): the ramp seam lives in
        // BettingMiniGameBot.betCondition, shared only by BETTING_MINI and TAI_XIU
        // (both extend BettingMiniGameBot). SLOT and other types have no bet-window
        // model, so the ramp params are never set on them — they keep the builder
        // defaults (rampEnabled=false / rampShape=0.0), mirroring the game-type
        // gating the coordinator/jackpot-scaler use at start() (AD-S1).
        if (game.getGameType() == GameType.BETTING_MINI || game.getGameType() == GameType.TAI_XIU) {
            behaviorConfigBuilder
                    .rampEnabled(group.isRampEnabled())
                    .rampShape(group.getRampShape());
        }
        BotBehaviorConfig behaviorConfig = behaviorConfigBuilder.build();

        // Resolve assigned strategy. Defensive fallback: if the username is
        // missing from the assignment map (should never happen — both are built
        // from the same namePrefix + i shape), default to RANDOM so the bot
        // still starts. The assignment map carries the per-bot lifecycle
        // identity downstream (Phase 5 will read configuration.strategyId
        // in BettingMiniGameBot.initializeSubclass to build the strategy).
        StrategyId strategyId = strategyAssignment.getOrDefault(username, StrategyId.RANDOM);
        // INFO per Architecture Decision 14 and CLAUDE.md logging guidance:
        // group-level lifecycle, bounded — N bots = N lines at start, mirrors
        // the "Bot starting in virtual thread" line emitted from BotGroupRuntime
        // at the same scale. MDC (botGroupId, botIndex, gameType) is already
        // set by createBotsInParallel's BotMdc.setGroupContext call, so this
        // line is grep-able by group from Loki.
        log.info("Bot {}: assigned strategy {}", username, strategyId);

        // SLOT bots always run the basic FIXED slot strategy. Strategy variety has
        // no purpose for slots (slot play is invisible to other players), so any
        // client-supplied group.slotStrategyId is intentionally ignored and
        // overridden to FIXED here — the create/update still succeeds, this is a
        // silent override, not a rejection. The betting strategyId above is
        // meaningless for slots but harmless, so it is left in place unchanged;
        // SlotMachineBot.initializeSubclass reads configuration.slotStrategyId and
        // ignores strategyId.
        SlotStrategyId slotStrategyId = null;
        if (game.getGameType() == GameType.SLOT) {
            slotStrategyId = SlotStrategyId.FIXED;
            log.info("Bot {}: assigned slot strategy {} (slot strategy is not selectable)", username, slotStrategyId);
        }

        BotConfiguration configuration = BotConfiguration.builder()
                .credentials(credentials)
                .environmentId(group.getEnvironmentId())
                .botGroupId(group.getId())
                .botIndex(botIndex)
                .game(game)
                .behaviorConfig(behaviorConfig)
                .zoneName(environment.resolveZoneName(game))
                .watchdogTimeoutSeconds(watchdogTimeoutSeconds)
                .strategyId(strategyId)
                .slotStrategyId(slotStrategyId)
                .build();

        // Create bot using factory (authenticates and creates WebSocket client)
        Bot bot = botFactory.createBot(group.getEnvironmentId(), configuration);

        log.debug("Created bot {} ({}/{})", username, botIndex, group.getBotCount());
        return bot;
    }

    /**
     * Resolve the effective strategy mix for a bot group, applying the
     * read-side fallback for unmigrated Mongo docs. Defaults to
     * {@code [(RANDOM, 1.0)]} when the group's persisted mix is null or empty,
     * so groups that pre-date Phase 4 still start cleanly without operator
     * intervention (Architecture Decision 7 in
     * {@code docs/plans/BETTING_STRATEGIES.md}).
     */
    private static List<WeightedStrategy> effectiveStrategyMix(BotGroup group) {
        List<WeightedStrategy> mix = group.getStrategyMix();
        if (mix == null || mix.isEmpty()) {
            return List.of(new WeightedStrategy(StrategyId.RANDOM, 1.0));
        }
        return mix;
    }

    /**
     * Stop a bot group - stops all bots, cleans up resources, removes from runtime map
     */
    public void stop(String id) {
        BotGroupRuntime runtime = runningGroups.get(id);
        if (runtime == null) {
            log.warn("Bot group {} is not running", id);
            return;
        }

        // No outer try/catch — let the original exception propagate to
        // RestExceptionHandler. Wrapping in RuntimeException would lose the
        // exception type and erase the structured response body.
        // Set group MDC so the group-level dead-seconds increment (if a DEAD
        // window is open) is tagged with botGroupId/environmentId. Cleared in
        // the finally so we don't leak MDC into the caller thread.
        BotMdc.setGroupContext(runtime.getGroupId(), runtime.getEnvironmentId());
        try {
            // Stop all bots and shutdown executor
            runtime.stopAllBots(botMetrics);
        } finally {
            BotMdc.clear();
        }

        // Drop this group's aggregated-session entries immediately so nothing dangles
        // (AD-8 group-stop hook). TTL sweep is the backstop; this reclaims on stop.
        sessionAggregationService.evictGroup(id);

        // Remove from runtime map
        runningGroups.remove(id);

        // Update entity
        BotGroup group = botGroupService.findById(id);
        group.setTargetStatus(BotGroupStatus.STOPPED);
        group.setLastStoppedAt(LocalDateTime.now());
        botGroupService.save(group);

        log.info("Bot group {} stopped successfully", id);
    }

    /**
     * Stop a bot group and log every bot out of the game server before tearing
     * down the runtime — the teardown half of the cascade-delete path
     * (BOTGROUP_GAME_MANAGEMENT AD-15 / Phase 7). Ordering:
     * flip the group out of ACTIVE → {@link BotGroupRuntime#stopAllBots(BotMetrics)}
     * (per-bot {@link Bot#cleanup()}: a graceful WS close, which <i>is</i> the
     * logout — there is no distinct server-side logout API) → evict
     * aggregated-session state → drop from {@code runningGroups} (stop managing it).
     * <p>
     * <b>No explicit {@code bot.logout()} loop.</b> {@code logout()} closes the
     * client <i>without</i> first setting the bot's {@code stopped} flag, so the
     * wired {@code onDisconnect} handler ({@code Bot}: {@code if (!stopped)
     * onWsDisconnected()}) would fire for every bot — emitting a retry WARN,
     * incrementing {@code bot_reconnects_total{reason=ws-disconnect}}, and spawning
     * a {@code reconnect-<name>} virtual thread per bot. On a delete of an N-bot
     * group that manufactured N false reconnect events + N reconnect threads (the
     * unbounded-reconnect failure mode behind a prior staging OOM). {@code cleanup()}
     * (invoked by {@code stopAllBots}) deliberately sets {@code stopped = true}
     * <i>before</i> closing, so {@code onDisconnect}'s guard suppresses the retry.
     * Relying on it gives a delete with zero retry WARNs, zero reconnect
     * increments, and zero reconnect threads.
     * <p>
     * Idempotent: a group that is not running is a no-op (already stopped /
     * already removed). Per-bot {@code cleanup()} failures are swallowed inside
     * {@code stopAllBots} so one bad bot cannot abort a cascade.
     * <p>
     * Unlike {@link #stop(String)} this does <b>not</b> persist a STOPPED status —
     * the sole caller ({@code BotGroupService.delete}) deletes the document next,
     * so a DB round-trip would be wasted.
     */
    public void stopAndLogout(String id) {
        BotGroupRuntime runtime = runningGroups.get(id);
        if (runtime == null) {
            log.debug("Bot group {} is not running; nothing to stop/logout before delete", id);
            return;
        }

        // Flip out of ACTIVE first so a concurrent periodic-logout tick bails at
        // its status gate (performPeriodicLogout) instead of reconnecting a bot
        // we are about to tear down.
        runtime.setActualStatus(BotGroupStatus.STOPPED);

        // Group MDC so the per-bot teardown lines and the dead-window credit inside
        // stopAllBots carry botGroupId/environmentId. Cleared in finally.
        BotMdc.setGroupContext(runtime.getGroupId(), runtime.getEnvironmentId());
        try {
            // Stop teardown: per-bot cleanup() sets stopped=true THEN closes the WS
            // (the logout) — the stopped-first order is what suppresses onDisconnect's
            // retry so no false reconnect is manufactured. Also shuts the executor +
            // monitor + logout-scheduler and credits the group dead-window.
            runtime.stopAllBots(botMetrics);
        } finally {
            BotMdc.clear();
        }

        // Drop aggregated-session entries and stop managing the group.
        sessionAggregationService.evictGroup(id);
        runningGroups.remove(id);

        log.info("Bot group {} stopped and logged out (cascade delete)", id);
    }

    /**
     * Restart a bot group (even if DEAD).
     * <p>
     * RESTART_LIFECYCLE_FIX Architecture Decision 6: if the subsequent {@code start}
     * produces zero live bots while the group's {@code botCount} is positive, throw
     * {@link IllegalStateException}. {@code start()} itself swallows per-bot failures
     * intentionally — the controller layer needs an explicit signal that a restart
     * (which begins with a healthy running group) silently went to zero bots, since
     * "zero bots with targetStatus=ACTIVE" was the symptom that originally hid the
     * 2026-06-09 outage.
     * <p>
     * <b>Post-throw state.</b> When the zero-bot exception fires, {@code start()}
     * has already persisted {@code targetStatus=ACTIVE} and inserted a (zero-bot)
     * runtime into {@code runningGroups}. The DB and runtime are "lying" exactly
     * as before; the only difference is that the failure is now visible via the
     * exception + ERROR log + {@code bot_creation_failures_total} metric.
     * <p>
     * <b>Operator recovery procedure.</b> POST {@code /stop} to clear the
     * inconsistent runtime + persist {@code targetStatus=STOPPED}, then POST
     * {@code /start} to retry. The underlying cause (e.g. misconfigured
     * environment, auth gateway outage) should be investigated before retrying;
     * blindly re-issuing {@code /restart} will mechanically re-run the
     * {@code stop} + {@code start} sequence and is likely to fail the same way.
     */
    public void restart(String id) {
        log.info("Restarting bot group {}", id);
        stop(id);

        // Brief pause before restart (uses virtual thread, no platform thread blocked)
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        start(id);

        // Verify post-start runtime is populated. If start() produced zero bots
        // despite a non-zero botCount, surface that as an exception. The controller
        // already returns 500 on Exception; this turns silent failure into an
        // entry the operator can grep for and metrics they can alert on.
        BotGroup group = botGroupService.findById(id);
        BotGroupRuntime runtime = runningGroups.get(id);
        int alive = runtime != null ? runtime.getBotInstances().size() : 0;
        if (group.getBotCount() > 0 && alive == 0) {
            throw new IllegalStateException(String.format(
                    "Restart of group %s produced %d/%d bots; check logs and %s metric for cause",
                    id, alive, group.getBotCount(), BotMetrics.BOT_CREATION_FAILURES_TOTAL));
        }
    }

    /**
     * Schedule a restart for a specific time
     */
    public void scheduleRestart(String id, LocalDateTime time) {
        BotGroup group = botGroupService.findById(id);

        long delayMillis = Duration.between(LocalDateTime.now(), time).toMillis();

        if (delayMillis <= 0) {
            throw new BadRequestException("Scheduled time must be in the future");
        }

        scheduler.schedule(() -> {
            log.info("Executing scheduled restart for bot group {}", id);
            restart(id);
        }, delayMillis, TimeUnit.MILLISECONDS);

        // Update entity
        group.setScheduledRestartTime(time);
        botGroupService.save(group);

        log.info("Scheduled restart for bot group {} at {}", id, time);
    }

    /**
     * Get health details for a bot group including per-bot metrics.
     */
    public BotGroupHealthDTO getHealth(String id) {
        BotGroup group = botGroupService.findById(id);
        BotGroupRuntime runtime = runningGroups.get(id);

        if (runtime == null) {
            return BotGroupHealthDTO.builder()
                    .groupId(id)
                    .groupName(group.getName())
                    .status(BotGroupStatus.STOPPED)
                    .totalBots(0)
                    .connectedBots(0)
                    .disconnectedBots(0)
                    .bots(List.of())
                    .stats(computeStats(id))
                    .build();
        }

        List<BotHealthDTO> botDtos = runtime.getBotInstances().stream()
                .map(bot -> BotHealthDTO.builder()
                        .username(bot.getUserName())
                        .status(bot.getStatus())
                        .connected(bot.isConnected())
                        .balance(bot.getExpectedBalance())
                        .lastFetchedBalance(bot.getLastFetchedBalance())
                        .totalBetsPlaced(bot.getTotalBetsPlaced().get())
                        .totalBetAmount(bot.getTotalBetAmount().get())
                        .lastRoundWinnings(bot.getLastRoundWinnings())
                        .strategyId(bot.getStrategyId())
                        .build())
                .toList();

        int connected = (int) botDtos.stream().filter(BotHealthDTO::isConnected).count();
        int reconnecting = (int) botDtos.stream()
                .filter(b -> b.getStatus() == BotStatus.RECONNECTING).count();
        int dead = (int) botDtos.stream()
                .filter(b -> b.getStatus() == BotStatus.DEAD).count();

        return BotGroupHealthDTO.builder()
                .groupId(id)
                .groupName(group.getName())
                .status(runtime.getActualStatus())
                .playingStatus(runtime.getPlayingStatus())
                .startedAt(runtime.getStartedAt())
                .consecutiveFailures(runtime.getConsecutiveFailures())
                .totalBots(botDtos.size())
                .connectedBots(connected)
                .reconnectingBots(reconnecting)
                .deadBots(dead)
                .disconnectedBots(botDtos.size() - connected - reconnecting - dead)
                .bots(botDtos)
                .stats(computeStats(id))
                .coordination(buildCoordinationState(runtime.getCoordinator()))
                .jackpotScale(buildJackpotScaleState(runtime.getJackpotScaler()))
                .build();
    }

    /**
     * Read-side jackpot-scaler view (JACKPOT_SCALE_AND_RAMP Phase J4, AD-J10).
     * Returns {@code null} when the group has no scaler (jackpot-scale off /
     * ineligible / not running), so the {@code jackpotScale} block is absent for
     * those groups. Otherwise reads the scaler's coherent {@code snapshot()} — a
     * single lock acquisition so the view is never torn against a concurrent
     * {@code observePool} — and maps it into the DTO. Strictly read-only: nothing
     * here mutates scaler state.
     */
    private JackpotScaleStateDTO buildJackpotScaleState(JackpotScaler scaler) {
        if (scaler == null) {
            return null;
        }
        JackpotScaler.Snapshot snapshot = scaler.snapshot();
        return JackpotScaleStateDTO.builder()
                .enabled(true)
                .jackpotCeiling(snapshot.ceiling())
                .seedFloor(snapshot.seedFloor())
                .lastObservedPool(snapshot.lastObservedPool())
                .currentFactor(snapshot.currentFactor())
                .minMultiplier(snapshot.minMultiplier())
                .build();
    }

    /**
     * Read-side coordinator view (BET_COORDINATION Phase 4, AD-6). Returns
     * {@code null} when the group has no coordinator (coordination off), so the
     * {@code coordination} block is absent for off/legacy groups. Otherwise reads
     * the coordinator's coherent {@code snapshot()} — a single lock acquisition so
     * the view is never torn against a concurrent reservation — and maps it into
     * the DTO. Strictly read-only: nothing here mutates coordinator state.
     */
    private CoordinationStateDTO buildCoordinationState(BetCoordinator coordinator) {
        if (coordinator == null) {
            return null;
        }
        BetCoordinator.Snapshot snapshot = coordinator.snapshot();
        List<CoordinationStateDTO.OptionStateDTO> options = snapshot.options().stream()
                .map(o -> CoordinationStateDTO.OptionStateDTO.builder()
                        .optionId(o.optionId())
                        .targetWeight(o.weight())
                        .targetBudget(o.targetBudget())
                        .committedStake(o.committedStake())
                        .realizedFraction(o.targetBudget() > 0
                                ? (double) o.committedStake() / o.targetBudget()
                                : 0.0)
                        .build())
                .toList();
        return CoordinationStateDTO.builder()
                .enabled(true)
                .maxAggregateStakePerRound(snapshot.maxAggregateStakePerRound())
                .currentAggregateStake(snapshot.currentAggregateStake())
                .approveCount(snapshot.approveCount())
                .trimCount(snapshot.trimCount())
                .rejectCount(snapshot.rejectCount())
                .options(options)
                .build();
    }

    /**
     * Compute group-level runtime statistics (BOTGROUP_GAME_MANAGEMENT Phase 3),
     * reading live state from {@code runningGroups}.
     * <p>
     * A group with no runtime (not running) yields an all-null-fields block — every
     * field renders as N/A. For a running group:
     * <ul>
     *   <li>{@code activeTimeSeconds} = seconds between {@code runtime.startedAt} and now (AD-9).</li>
     *   <li>{@code roundsSinceRestart} = MAX of the per-bot {@code roundsObserved} counter
     *       across all bots (AD-9); 0 when no bot has observed a round yet.</li>
     *   <li>{@code activeBots} = count of {@code isConnected()} bots (AD-10).</li>
     *   <li>{@code averageBalance} / {@code averageWinning} = means over the <em>active</em>
     *       ({@code isConnected()}) bots only (AD-8/AD-10); {@code null} when zero bots are
     *       active — never 0 (Implementation Note 5).</li>
     * </ul>
     * All averages come from in-memory {@code Bot} accumulators; Prometheus is never
     * queried here (AD-4).
     */
    public BotGroupStatsDTO computeStats(String groupId) {
        BotGroupRuntime runtime = runningGroups.get(groupId);
        if (runtime == null) {
            // Not running → every field N/A.
            return BotGroupStatsDTO.builder().build();
        }

        List<Bot> bots = runtime.getBotInstances();

        // Rounds since last restart = max over the group's bots (dedup-free, robust to
        // subscriber pruning). Bots are freshly built each start/restart, so the counter
        // is already scoped to "since last restart".
        long roundsSinceRestart = bots.stream()
                .mapToLong(bot -> bot.getRoundsObserved().get())
                .max()
                .orElse(0L);

        Instant startedAt = runtime.getStartedAt();
        Long activeTimeSeconds = startedAt != null
                ? Duration.between(startedAt, Instant.now()).toSeconds()
                : null;

        // Averages over active (isConnected) bots only. Zero active bots → null (N/A),
        // not 0 — a live runtime whose bots are all reconnecting must still read N/A.
        List<Bot> activeBots = bots.stream()
                .filter(Bot::isConnected)
                .toList();
        int activeCount = activeBots.size();

        Long averageBalance = null;
        Long averageWinning = null;
        if (activeCount > 0) {
            long balanceSum = activeBots.stream()
                    .mapToLong(Bot::getExpectedBalance)
                    .sum();
            long winningSum = activeBots.stream()
                    .mapToLong(bot -> bot.getCumulativeWinnings().get())
                    .sum();
            averageBalance = balanceSum / activeCount;
            averageWinning = winningSum / activeCount;
        }

        return BotGroupStatsDTO.builder()
                .roundsSinceRestart(roundsSinceRestart)
                .activeTimeSeconds(activeTimeSeconds)
                .activeBots(activeCount)
                .averageBalance(averageBalance)
                .averageWinning(averageWinning)
                .build();
    }

    /**
     * Env-scoped bot-group filter with in-memory sorting (BOTGROUP_GAME_MANAGEMENT
     * Phase 4 / AD-11). Loads the matching groups from Mongo (via
     * {@link BotGroupService#filter(String, BotGroupFilter)}), enriches each with the
     * Phase 3 runtime stats, the runtime {@code actualStatus}, and the resolved
     * game-type name, then sorts the enriched rows in memory per AD-12. No Mongo-side
     * aggregation and no persisted derived fields.
     *
     * <p>The returned {@link BotGroupSortRow}s carry the pre-computed stats so the
     * controller can map to DTOs without recomputing. The sort key/direction come
     * from {@code filter.sortBy}/{@code filter.sortDir}; an unknown key surfaces as
     * HTTP 400 via {@link com.vingame.bot.domain.botgroup.sort.BotSortKey#resolve}.
     */
    public List<BotGroupSortRow> filterSorted(String environmentId, BotGroupFilter filter) {
        List<BotGroup> groups = botGroupService.filter(environmentId, filter);
        Map<String, String> gameTypeById = resolveGameTypes(groups);
        List<BotGroupSortRow> rows = groups.stream()
                .map(group -> new BotGroupSortRow(
                        group,
                        computeStats(group.getId()),
                        getActualStatus(group.getId()),
                        group.getGameId() == null ? null : gameTypeById.get(group.getGameId())))
                .toList();
        return BotGroupSorter.sort(rows, filter.getSortBy(), filter.getSortDir());
    }

    /**
     * Env-scoped game filter with in-memory sorting (BOTGROUP_GAME_MANAGEMENT
     * Phase 5). Loads the matching games via
     * {@link GameService#filter(BrandCode, ProductCode, String, GameFilter)}, then
     * enriches each with the aggregates over the bot groups referencing it (via
     * {@link BotGroupService#findByGameId} — {@code BotGroup.gameId} is the Game
     * Mongo {@code _id}, Implementation Note 1), and sorts the enriched rows in
     * memory per AD-11/AD-12. No Mongo-side aggregation, no persisted derived fields.
     *
     * <p>The sort key/direction come from {@code filter.sortBy}/{@code filter.sortDir};
     * an unknown key surfaces as HTTP 400 via {@link com.vingame.bot.domain.game.sort.GameSortKey#resolve}.
     */
    public List<GameSortRow> filterGamesSorted(BrandCode brandCode, ProductCode productCode,
                                               String environmentId, GameFilter filter) {
        List<Game> games = gameService.filter(brandCode, productCode, environmentId, filter);
        List<GameSortRow> rows = games.stream()
                .map(this::enrichGame)
                .toList();
        return GameSorter.sort(rows, filter.getSortBy(), filter.getSortDir());
    }

    /**
     * Compute the per-game aggregates over the bot groups referencing it (Phase 5).
     * {@code botGroupCount}/{@code botCount} are configured aggregates (never N/A);
     * {@code activeGroupCount}/{@code activeBotCount} are runtime sums (0 when the
     * game is inactive, gated to N/A by the sort keys). Computed once per game.
     */
    private GameSortRow enrichGame(Game game) {
        List<BotGroup> groups = botGroupService.findByGameId(game.getId());
        int botCount = 0;
        int activeGroupCount = 0;
        int activeBotCount = 0;
        for (BotGroup group : groups) {
            botCount += group.getBotCount();
            if (isGroupRunning(group.getId())) {
                activeGroupCount++;
                activeBotCount += getRunningBotCountForGroup(group.getId());
            }
        }
        return new GameSortRow(game, groups.size(), botCount, activeGroupCount, activeBotCount);
    }

    /**
     * Resolve the {@code gameType} enum name for each distinct {@code gameId}
     * (Game Mongo {@code _id}) referenced by the groups — looked up once per
     * distinct id (AD-11 note). A missing game (deleted out from under the group)
     * maps to {@code null}, which the {@code GAME_TYPE} sort key treats as N/A.
     */
    private Map<String, String> resolveGameTypes(List<BotGroup> groups) {
        Map<String, String> byId = new HashMap<>();
        for (BotGroup group : groups) {
            String gameId = group.getGameId();
            if (gameId == null || byId.containsKey(gameId)) {
                continue;
            }
            try {
                Game game = gameService.findById(gameId);
                byId.put(gameId, game.getGameType() != null ? game.getGameType().name() : null);
            } catch (ResourceNotFoundException e) {
                byId.put(gameId, null);
            }
        }
        return byId;
    }

    /**
     * Check if a bot group is currently running (has an active runtime).
     */
    public boolean isGroupRunning(String groupId) {
        BotGroupRuntime runtime = runningGroups.get(groupId);
        return runtime != null && runtime.getActualStatus() == BotGroupStatus.ACTIVE;
    }

    // ---- Aggregate accessors for observability gauges (used by ObservabilityConfig) ----

    /** Number of bot groups currently in the running map. */
    public int getRunningGroupCount() {
        return runningGroups.size();
    }

    /** Total number of managed bot instances across all running groups. */
    public int getTotalManagedBots() {
        int total = 0;
        for (BotGroupRuntime runtime : runningGroups.values()) {
            total += runtime.getBotInstances().size();
        }
        return total;
    }

    /** Total number of bots with an open WebSocket connection across all running groups. */
    public int getOpenWsConnectionCount() {
        int total = 0;
        for (BotGroupRuntime runtime : runningGroups.values()) {
            for (Bot bot : runtime.getBotInstances()) {
                if (bot.isConnected()) total++;
            }
        }
        return total;
    }

    /** Count of bots currently in the given status across all running groups. */
    public int countBotsByStatus(BotStatus status) {
        int total = 0;
        for (BotGroupRuntime runtime : runningGroups.values()) {
            for (Bot bot : runtime.getBotInstances()) {
                if (bot.getStatus() == status) total++;
            }
        }
        return total;
    }

    /**
     * Count of bots currently in DEAD state across all running groups.
     * Backs the {@code bots_dead_currently} aggregate gauge. Equivalent to
     * {@code countBotsByStatus(BotStatus.DEAD)}; broken out so the gauge name
     * does not depend on the {@code status} tag value.
     */
    public int countBotsDeadCurrently() {
        return countBotsByStatus(BotStatus.DEAD);
    }

    /**
     * Count of bot groups currently in DEAD state (i.e. {@code groupDeadSince}
     * has been stamped and not yet credited). Backs the
     * {@code groups_dead_currently} aggregate gauge.
     */
    public int countGroupsDeadCurrently() {
        int total = 0;
        for (BotGroupRuntime runtime : runningGroups.values()) {
            if (runtime.getGroupDeadSince() != null) total++;
        }
        return total;
    }

    // ---- Per-game / per-env info + status snapshots (used by ObservabilityConfig) ----

    /**
     * Identity tuple for the {@code game_join} join gauge (AD-2): the stable Mongo
     * {@code _id} ({@code gameId}), the readable {@code gameName}, and the
     * {@code gameType} enum name. {@code gameId} is the Mongo {@code _id} (a UUID
     * string), NOT {@link Game#getGameId()} (the env-scoped numeric channel) — see AD-8.
     */
    public record GameInfo(String gameId, String gameName, String gameType) {
    }

    /**
     * Identity tuple for the {@code environment_join} join gauge (AD-2): the
     * environment id and its readable name (threaded into {@link BotGroupRuntime}
     * at group start from {@code Environment.getName()}).
     */
    public record EnvInfo(String environmentId, String environmentName) {
    }

    /** Grouping key for {@code bots_by_game_status}: game identity + bot status. */
    public record GameStatusKey(String gameId, String gameName, BotStatus status) {
    }

    /** Grouping key for {@code bots_by_env_status}: environment id + bot status. */
    public record EnvStatusKey(String environmentId, BotStatus status) {
    }

    /**
     * Distinct set of games currently backing live bots, for the {@code game_join}
     * join gauge. Sourced from each bot's {@link BotConfiguration#getGame()} so the
     * dropdown is populated the moment a group starts, before the first bet (AD-2).
     */
    public Collection<GameInfo> listRunningGameInfo() {
        Map<String, GameInfo> distinct = new LinkedHashMap<>();
        for (BotGroupRuntime runtime : runningGroups.values()) {
            for (Bot bot : runtime.getBotInstances()) {
                Game game = bot.getConfiguration().getGame();
                if (game == null) continue;
                String gameType = game.getGameType() != null ? game.getGameType().name() : "";
                distinct.putIfAbsent(game.getId(),
                        new GameInfo(game.getId(), game.getName(), gameType));
            }
        }
        return distinct.values();
    }

    /**
     * Distinct set of environments currently backing live bots, for the
     * {@code environment_join} join gauge. The readable name comes from
     * {@link BotGroupRuntime#getEnvironmentName()} (threaded in at start, AD-2);
     * if absent (e.g. a runtime built without an Environment), the id is used as a
     * fallback display so the series still resolves.
     */
    public Collection<EnvInfo> listRunningEnvironmentInfo() {
        Map<String, EnvInfo> distinct = new LinkedHashMap<>();
        for (BotGroupRuntime runtime : runningGroups.values()) {
            String envId = runtime.getEnvironmentId();
            if (envId == null) continue;
            String envName = runtime.getEnvironmentName() != null
                    ? runtime.getEnvironmentName() : envId;
            distinct.putIfAbsent(envId, new EnvInfo(envId, envName));
        }
        return distinct.values();
    }

    /**
     * Snapshot count of live bots grouped by {@code (gameId, gameName, status)},
     * backing the {@code bots_by_game_status} MultiGauge (AD-3). Reuses the live
     * iteration shape of {@link #countBotsByStatus(BotStatus)}.
     */
    public Map<GameStatusKey, Integer> countBotsByGameAndStatus() {
        Map<GameStatusKey, Integer> counts = new LinkedHashMap<>();
        for (BotGroupRuntime runtime : runningGroups.values()) {
            for (Bot bot : runtime.getBotInstances()) {
                Game game = bot.getConfiguration().getGame();
                if (game == null) continue;
                GameStatusKey key = new GameStatusKey(game.getId(), game.getName(), bot.getStatus());
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Snapshot count of live bots grouped by {@code (environmentId, status)},
     * backing the {@code bots_by_env_status} MultiGauge (AD-3). The env name for
     * the dashboard comes from the {@code environment_join} join, not this map.
     */
    public Map<EnvStatusKey, Integer> countBotsByEnvAndStatus() {
        Map<EnvStatusKey, Integer> counts = new LinkedHashMap<>();
        for (BotGroupRuntime runtime : runningGroups.values()) {
            String envId = runtime.getEnvironmentId();
            if (envId == null) continue;
            for (Bot bot : runtime.getBotInstances()) {
                EnvStatusKey key = new EnvStatusKey(envId, bot.getStatus());
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Get the number of running bots for a specific group.
     * Returns 0 if the group is not running.
     */
    public int getRunningBotCountForGroup(String groupId) {
        BotGroupRuntime runtime = runningGroups.get(groupId);
        if (runtime == null) {
            return 0;
        }
        return (int) runtime.getRunningBotCount();
    }

    /**
     * Get actual runtime status (ACTIVE, STOPPED, DEAD)
     */
    public BotGroupStatus getActualStatus(String id) {
        return Optional.ofNullable(runningGroups.get(id))
                .map(BotGroupRuntime::getActualStatus)
                .orElse(BotGroupStatus.STOPPED);
    }

    /**
     * Get playing status (PLAYING, IDLE, PENDING) - only relevant when ACTIVE
     */
    public BotGroupPlayingStatus getPlayingStatus(String id) {
        return Optional.ofNullable(runningGroups.get(id))
                .map(BotGroupRuntime::getPlayingStatus)
                .orElse(null); // null if not running
    }

    /**
     * Start health monitoring for a bot group
     */
    private void startHealthMonitoring(BotGroupRuntime runtime) {
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("health-monitor-" + runtime.getGroupId()).factory()
        );
        runtime.setHealthMonitor(monitor);

        monitor.scheduleAtFixedRate(() -> {
            BotMdc.setGroupContext(runtime.getGroupId(), runtime.getEnvironmentId());
            try {
                monitorHealth(runtime);
            } catch (Exception e) {
                log.error("Health monitoring error for group {}: {}", runtime.getGroupId(), e.getMessage());
            } finally {
                BotMdc.clear();
            }
        }, 10, 30, TimeUnit.SECONDS);
    }

    /**
     * Monitor health of bot group — logs a summary and marks group DEAD if enough bots have given up.
     */
    private void monitorHealth(BotGroupRuntime runtime) {
        List<Bot> bots = runtime.getBotInstances();
        if (bots.isEmpty()) return;

        long dead = bots.stream().filter(b -> b.getStatus() == BotStatus.DEAD).count();
        long reconnecting = bots.stream().filter(b -> b.getStatus() == BotStatus.RECONNECTING).count();
        long playing = bots.stream().filter(Bot::isConnected).count();

        runtime.setConsecutiveFailures((int) dead);

        log.debug("Group {} health — playing: {}, reconnecting: {}, dead: {}/{}",
                runtime.getGroupId(), playing, reconnecting, dead, bots.size());

        if (!runtime.isGroupDead() && (double) dead / bots.size() >= deadBotGroupThreshold) {
            handleBotGroupDeath(runtime);
        }
    }

    /**
     * Handle bot group death - mark as DEAD and update database
     */
    private void handleBotGroupDeath(BotGroupRuntime runtime) {
        log.error("Bot group {} has been marked as DEAD due to repeated failures", runtime.getGroupId());

        runtime.markAsDead();

        try {
            BotGroup group = botGroupService.findById(runtime.getGroupId());
            group.setTargetStatus(BotGroupStatus.DEAD);
            group.setLastFailureReason("Multiple bot disconnections detected");
            botGroupService.save(group);
        } catch (Exception e) {
            log.error("Failed to update database for dead bot group {}: {}", runtime.getGroupId(), e.getMessage());
        }
    }

    /**
     * Start periodic logout scheduler for a bot group.
     * One bot logs out and reconnects per interval (round-robin).
     *
     * @param runtime     The bot group runtime
     * @param environment The environment (for per-environment config overrides)
     */
    private void startPeriodicLogoutScheduler(BotGroupRuntime runtime, Environment environment) {
        // Determine effective configuration (environment overrides global)
        boolean enabled = environment.getPeriodicLogoutEnabled() != null
                ? environment.getPeriodicLogoutEnabled()
                : periodicLogoutEnabled;

        int intervalMinutes = environment.getPeriodicLogoutIntervalMinutes() != null
                ? environment.getPeriodicLogoutIntervalMinutes()
                : periodicLogoutIntervalMinutes;

        if (!enabled) {
            log.info("Periodic logout disabled for group {} (environment: {})",
                    runtime.getGroupId(), environment.getName());
            return;
        }

        if (runtime.getBotInstances().isEmpty()) {
            log.warn("No bots in group {}, skipping periodic logout setup", runtime.getGroupId());
            return;
        }

        ScheduledExecutorService logoutScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual()
                        .name("logout-scheduler-" + runtime.getGroupId())
                        .factory()
        );
        runtime.setLogoutScheduler(logoutScheduler);

        logoutScheduler.scheduleAtFixedRate(() -> {
            BotMdc.setGroupContext(runtime.getGroupId(), runtime.getEnvironmentId());
            try {
                performPeriodicLogout(runtime);
            } catch (Exception e) {
                log.error("Periodic logout error for group {}: {}",
                        runtime.getGroupId(), e.getMessage(), e);
            } finally {
                BotMdc.clear();
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);

        log.info("Periodic logout scheduler started for group {} (interval: {} minutes, reconnect delay: {} seconds)",
                runtime.getGroupId(), intervalMinutes, reconnectDelaySeconds);
    }

    /**
     * Perform a single periodic logout cycle.
     * Gets the next bot via round-robin, logs it out, waits, then reconnects.
     */
    private void performPeriodicLogout(BotGroupRuntime runtime) {
        // Skip if group is stopping or dead
        if (runtime.getActualStatus() != BotGroupStatus.ACTIVE) {
            log.debug("Skipping periodic logout for group {} - not active (status: {})",
                    runtime.getGroupId(), runtime.getActualStatus());
            return;
        }

        Bot bot = runtime.getNextBotForLogout();
        if (bot == null) {
            log.warn("No bot available for periodic logout in group {}", runtime.getGroupId());
            return;
        }

        // Skip if bot is already disconnected
        if (bot.getClient() == null || !bot.getClient().isOpen()) {
            log.debug("Bot {} already disconnected, skipping periodic logout", bot.getUserName());
            return;
        }

        // INFO (not DEBUG) so the operator has the "why" for the subsequent
        // `Bot {userName}: restart requested` INFO line at Bot.java:176. Without this
        // context, the restart INFO is opaque. Per-bot but bounded — fires at most once
        // per scheduler interval per group.
        log.info("Periodic logout starting for bot {} in group {}",
                bot.getUserName(), runtime.getGroupId());

        try {
            // Logout (closes connection)
            bot.logout();

            // Wait before reconnecting (virtual thread, no platform thread blocked)
            Thread.sleep(reconnectDelaySeconds * 1000L);

            // Check again if group is still active before reconnecting
            if (runtime.getActualStatus() != BotGroupStatus.ACTIVE) {
                log.debug("Group {} stopped during logout delay, skipping reconnect for bot {}",
                        runtime.getGroupId(), bot.getUserName());
                return;
            }

            // Reconnect
            bot.restart();

            log.debug("Periodic logout completed for bot {} in group {}",
                    bot.getUserName(), runtime.getGroupId());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Periodic logout interrupted for bot {} in group {}",
                    bot.getUserName(), runtime.getGroupId());
        } catch (Exception e) {
            log.error("Periodic logout failed for bot {} in group {}: {}",
                    bot.getUserName(), runtime.getGroupId(), e.getMessage(), e);
        }
    }
}