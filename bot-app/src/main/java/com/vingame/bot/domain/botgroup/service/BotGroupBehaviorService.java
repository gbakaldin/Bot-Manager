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
import com.vingame.bot.domain.botgroup.dto.RampStateDTO;
import com.vingame.bot.domain.botgroup.dto.BotHealthDTO;
import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupFilter;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.model.RecoveryEligibility;
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
import com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator;
import com.vingame.bot.infrastructure.observability.ScopedDebugEscalator;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
     * Tier-1 log aggregation (LOG_VOLUME_TIERING). Used here for two things only:
     * declaring a group's expected bot count before the fan-out, so its one
     * "N/M bots initialized" line can name the denominator, and evicting the group's
     * pending counters on teardown alongside {@code sessionAggregationService}.
     */
    private final GroupLifecycleAggregator groupLifecycleAggregator;

    /**
     * Auto-escalation of scoped per-group DEBUG (LOG_VOLUME_TIERING AD-12). Used here for
     * the one trigger only this class can see — a group's {@code dead/total} rising while
     * still under {@code bot.group.dead.threshold} — and to evict a stopped group's
     * bookkeeping alongside the other per-group aggregators.
     */
    private final ScopedDebugEscalator scopedDebugEscalator;

    /**
     * Max number of bots to create/authenticate simultaneously.
     * Controls concurrency to avoid overwhelming the game server's auth endpoint.
     * Configurable via application.properties: bot.creation.parallelism
     * <p>
     * This bounds <b>concurrency</b> (how many sockets are open at once), not <b>rate</b>.
     * Since GATEWAY_REQUEST_BUDGET AD-2 the rate is the per-environment gateway budget's
     * business, so raising this number no longer raises the request rate against the
     * gateway — it only makes the admitted requests overlap more.
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
     * Amount credited by a single auto-deposit top-up, per environment. Defaults to
     * the value {@code Bot.deposit()} previously hardcoded, so an instance with no
     * explicit setting behaves exactly as before. Global rather than per-group: the
     * sensible value tracks the product's currency scale, not the fleet.
     */
    @Value("${bot.deposit.amount:1000000000}")
    private long depositAmount;

    /**
     * Business wall-clock zone the activation windows are interpreted in — the same
     * single app-wide value {@code ActivationScheduler} reads. Needed here only by
     * {@link #startForRecovery(String)}, which re-asserts the recovery predicate
     * (and therefore a SCHEDULED group's window) under the group lock.
     * <p>
     * The initialiser is not redundant: Spring overwrites it, but the Mockito unit
     * tests construct this service directly and would otherwise pass {@code null}
     * into {@code ZoneId.of}.
     */
    @Value("${bot.activation.zone:Asia/Ho_Chi_Minh}")
    private String activationZone = "Asia/Ho_Chi_Minh";

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

    /**
     * Starts in flight (GATEWAY_REQUEST_BUDGET Phase 2). Owned here rather than injected:
     * it has no dependencies, nothing outside this service reads it directly, and keeping it
     * off the constructor keeps every {@code @InjectMocks} fixture of this class non-null —
     * which is why none of its call sites need a null guard.
     * <p>
     * It covers the window {@link #runningGroups} cannot: between "the start was accepted" and
     * "a runtime exists", the group is only visible here.
     */
    private final StartAttemptRegistry startAttempts = new StartAttemptRegistry();

    // Per-group lock serializing the reclaim-decision + build in start() and the
    // teardown in stop() (DEAD_GROUP_RESTART AD-5). Makes the
    // containsKey/reclaim/put sequence atomic so a double-click or a
    // health-monitor race cannot double-build or leak a runtime's executor +
    // monitor + logout threads. Bounded by the number of groups (tens); never
    // cleaned (negligible). restart() takes no lock of its own — its stop()/
    // start() each acquire/release sequentially (non-nested, no reentrancy).
    private final ConcurrentHashMap<String, ReentrantLock> groupLocks = new ConcurrentHashMap<>();

    /**
     * How long {@link #handleBotGroupDeath} waits for the per-group lock before
     * giving up. Short on purpose: {@code stop()} holds that lock for up to 30 s
     * inside {@code stopAllBots}, and the monitor thread must not park behind its
     * own shutdown. Giving up is safe — whoever holds the lock is a start or a stop,
     * and both write the group's status themselves.
     */
    private static final long DEATH_LOCK_TIMEOUT_SECONDS = 2;

    @Autowired
    public BotGroupBehaviorService(
            BotGroupService botGroupService,
            EnvironmentService environmentService,
            GameService gameService,
            BotFactory botFactory,
            BotMetrics botMetrics,
            SessionAggregationService sessionAggregationService,
            GroupLifecycleAggregator groupLifecycleAggregator,
            ScopedDebugEscalator scopedDebugEscalator
    ) {
        this.botGroupService = botGroupService;
        this.environmentService = environmentService;
        this.gameService = gameService;
        this.botFactory = botFactory;
        this.botMetrics = botMetrics;
        this.sessionAggregationService = sessionAggregationService;
        this.groupLifecycleAggregator = groupLifecycleAggregator;
        this.scopedDebugEscalator = scopedDebugEscalator;

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
        // Per-group lock (AD-5): serialize the reclaim-decision + build so a
        // double-click or a health-monitor race cannot double-build or leak the
        // first runtime's executor + monitor + logout threads. A second
        // concurrent Start blocks here, then observes the fresh ACTIVE runtime
        // and no-ops below. Held across the whole (I/O-bound, internally
        // parallel) build — acceptable for an infrequent admin action.
        ReentrantLock lock = groupLocks.computeIfAbsent(id, k -> new ReentrantLock());
        lock.lock();
        try {
            startLocked(id);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Start a bot group on behalf of the auto-recovery reconciler
     * (DEAD_GROUP_AUTO_RECOVERY AD-1). <b>The one and only new entry point this
     * feature adds</b>: it is deliberately a thin wrapper over the existing
     * {@link #startLocked(String)} — same lock, same reclaim, same rebuild — so
     * recovery cannot drift into a second lifecycle path. Everything the teardown
     * owes (crediting the open group-dead window exactly once, shutting the health
     * monitor, the periodic-logout scheduler and the per-bot watchdogs) is already
     * done there and is not repeated here.
     * <p>
     * The rebuild <b>re-authenticates existing accounts</b>. It never registers a
     * user and never recreates the Mongo group — registration lives only in
     * {@code BotGroupService.save}, gated on a null id, and every save on this path
     * carries the group's existing id, so it is structurally unreachable.
     * <p>
     * <b>It "never deposits" only in the sense that it adds no deposit a manual
     * {@code /restart} would not make.</b> The recovery <em>code</em> moves no money.
     * The recovered <em>group</em> can: {@code startLocked} builds its
     * {@code BotBehaviorConfig} with {@code autoDepositEnabled(group.isAutoDepositEnabled())},
     * so a bot in an auto-deposit group tops up from its own play loop once its
     * balance falls below minimum — exactly as it would after an operator pressed
     * Restart, which is the right standard and the one the plan's V9 states. Read
     * "recovery cannot move money" into this and you will be wrong about a group with
     * auto-deposit on.
     * <p>
     * <b>Why not just call {@link #start(String)}:</b> the reconciler decided this
     * group was a recovery candidate on a previous line of code, possibly seconds
     * ago. Between that decision and this call an operator may have issued
     * {@code POST /stop} (→ {@code targetStatus=STOPPED}, the AD-5 opt-out) or a
     * manual {@code /start}. Re-reading the persisted group and re-asserting
     * {@link RecoveryEligibility} <em>inside</em> the lock is what closes that
     * window; {@code start()} would act on the stale decision.
     * <p>
     * Nothing is caught: the caller isolates each group in its own try/catch and
     * needs the exception to classify the attempt as {@code outcome="error"}.
     *
     * @return whether the group actually came up — a live ACTIVE runtime with at
     *         least one running bot. {@code false} also covers "no longer eligible",
     *         which is a skip, not a failure the caller should charge to the budget
     *         differently: both simply mean the group is not running.
     *         <p>
     *         <b>That is a deliberately low bar, and it is not a health check.</b> A
     *         rebuild that authenticated one bot of fifty reports success here. A
     *         proportional predicate was tried and reverted because it buys nothing:
     *         a partial start is a <em>successful</em> {@code startLocked}, so it has
     *         already persisted {@code targetStatus=ACTIVE} and left an ACTIVE
     *         runtime by the time this returns, and {@code RecoveryEligibility} then
     *         vetoes on ACTIVE while {@code RecoveryCandidateSelector} stops
     *         selecting the group at all. Whatever this method answers, the group is
     *         out of the candidate set and there is no next attempt; the only thing a
     *         stricter predicate changes is the label on the counter and the text of
     *         the log line. The degraded group itself is
     *         {@code docs/plans/FOLLOWUPS.md} P10, which records what a real fix has
     *         to do.
     */
    public boolean startForRecovery(String id) {
        ReentrantLock lock = groupLocks.computeIfAbsent(id, k -> new ReentrantLock());
        lock.lock();
        try {
            BotGroup persisted = botGroupService.findById(id);
            BotGroupRuntime runtime = runningGroups.get(id);
            BotGroupStatus runtimeStatus = runtime != null ? runtime.getActualStatus() : null;
            boolean runtimeGroupDead = runtime != null && runtime.isGroupDead();

            if (!RecoveryEligibility.isCandidate(
                    persisted.getTargetStatus(), persisted.getActivationMode(),
                    persisted.getActivationWindow(), persisted.getBotCount(),
                    runtimeStatus, runtimeGroupDead,
                    Instant.now(), ZoneId.of(activationZone))) {
                log.info("Bot group {} is no longer a recovery candidate under the lock "
                                + "(targetStatus={}, activationMode={}, runtime={}) — not starting",
                        id, persisted.getTargetStatus(), persisted.getActivationMode(), runtimeStatus);
                return false;
            }

            startLocked(id);

            BotGroupRuntime rebuilt = runningGroups.get(id);
            return rebuilt != null
                    && rebuilt.getActualStatus() == BotGroupStatus.ACTIVE
                    && rebuilt.getRunningBotCount() > 0;
        } finally {
            lock.unlock();
        }
    }

    private void startLocked(String id) {
        // Reclaim guard (AD-1, AD-2). A genuinely running group has
        // actualStatus=ACTIVE (set in the BotGroupRuntime constructor); the only
        // non-ACTIVE state a runtime can hold while still in runningGroups is
        // DEAD — left there by EITHER the health-monitor death path
        // (handleBotGroupDeath) OR the zero-bot start path. ACTIVE ⇒ keep today's
        // no-op; non-ACTIVE ⇒ reclaim (full teardown) and fall through to rebuild
        // from the persisted group + existing accounts (re-auth only — no
        // register, no deposit, no DB-group recreation). See DEAD_GROUP_RESTART.
        BotGroupRuntime existing = runningGroups.get(id);
        if (existing != null) {
            // GATEWAY_REQUEST_BUDGET A1: STARTING counts as ACTIVE here. A runtime is born
            // STARTING, so testing ACTIVE alone would make this guard tear down and rebuild a
            // group whose build is in flight — the opposite of what the guard is for, and under
            // a paced start it would abandon up to 50 minutes of admitted gateway requests.
            // Unreachable in practice (the per-group lock serialises starts and the attempt
            // registry refuses a second /start), which is exactly why it is written down rather
            // than relied upon.
            if (existing.getActualStatus() == BotGroupStatus.ACTIVE
                    || existing.getActualStatus() == BotGroupStatus.STARTING) {
                log.warn("Bot group {} is already running (status {})", id, existing.getActualStatus());
                return;
            }
            // Lingering non-viable (DEAD) runtime. Reclaim: full teardown credits
            // the open dead-window exactly once, shuts monitor + logout scheduler
            // + executor, and cleans up bots (recovering the threads it was
            // leaking while DEAD), then fall through to rebuild.
            log.info("Bot group {} has a non-viable ({}) runtime — reclaiming before restart",
                    id, existing.getActualStatus());
            teardownRuntimeMemory(id, existing);
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

            // The `product` metric label comes from the environment for every meter this
            // group produces (AD-V1). A Game filed under a different product than the
            // environment it belongs to is a data error: it does not split the label any
            // more, but it does mean the game's own document is wrong, and Phase 3's rules
            // are named after products. Once per group start, never per bot.
            if (game.getProductCode() != null && environment.getProductCode() != null
                    && game.getProductCode() != environment.getProductCode()) {
                log.warn("Game {} is filed under product {} but environment {} is product {} — "
                                + "alerts for this group are routed by the environment's product. "
                                + "Fix the game's productCode.",
                        game.getName(), game.getProductCode(), environment.getName(),
                        environment.getProductCode());
            }

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
            Map<String, String> strategyAssignment = StrategyAssignment.assign(
                    effectiveStrategyMix(group), botIdentifiers);
            // LOG_VOLUME_TIERING tier 1: ONE line for the whole assignment, here where the
            // group-level decision is actually made, replacing the per-bot INFO line that
            // used to fire N times inside createSingleBot. The per-bot detail is still
            // available at DEBUG (and per-group via /api/v1/logging/debug).
            log.info("Bot group {}: strategy mix {}", id, strategyCounts(strategyAssignment));

            // Create runtime state
            BotGroupRuntime runtime = new BotGroupRuntime(id, group.getBotCount(),
                    group.getEnvironmentId(), environment.getName(), group.getName(),
                    environment.getProductCode() != null
                            ? environment.getProductCode().getCode() : null);
            runningGroups.put(id, runtime);

            // BET_COORDINATION (AD-9/AD-10): build a group-scoped coordinator only
            // when enabled AND the game shares the betting-mini/TaiXiu round model
            // (SLOT has no shared-round betting — AD-10). Off ⇒ no coordinator,
            // every bot's ref stays null, the bet path is byte-for-byte today's.
            if (group.isCoordinationEnabled()
                    && (game.getGameType() == GameType.BETTING_MINI || game.getGameType() == GameType.TAI_XIU)) {
                // CROWD_AWARE_COORDINATION (AD-C6): crowd-awareness is a sub-mode of
                // coordination — only a coordinationEnabled group gets a coordinator
                // at all, and the crowdAware bit is an added flag on top. When
                // crowdAwareCoordination=false the coordinator is byte-for-byte the
                // internal tier (observeCrowd is inert, AD-C6). The count semantic is
                // carried for the Phase 4 health snapshot only (AD-C5/AD-C10).
                boolean crowdAware = group.isCrowdAwareCoordination();
                BetCoordinator coordinator = new BetCoordinator(
                        game.getEffectiveOptionAffinities(),
                        group.getMaxAggregateStakePerRound(),
                        group.getMinBet(),
                        group.getBetIncrement(),
                        crowdAware,
                        game.getEffectiveCrowdCountSemantic().name());
                runtime.setCoordinator(coordinator);
                log.info("Bet coordinator created for group {} ({} options, aggregate cap {})",
                        group.getName(), game.getEffectiveOptionAffinities().size(),
                        group.getMaxAggregateStakePerRound());
                if (crowdAware) {
                    log.info("Crowd-aware coordination enabled for group {} (countSemantic={})",
                            group.getName(), game.getEffectiveCrowdCountSemantic());
                }
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

            // Declare the target count for tier 1 (LOG_VOLUME_TIERING). This is the only
            // place that knows both the target and the group's display name, so without
            // it the aggregated line can only report how many bots came up, not how many
            // were meant to — and "47" reads like success where "47/50" reads like three
            // auth failures.
            groupLifecycleAggregator.expectInitialized(id, group.getName(), group.getBotCount());

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

            // Zero-bot start: createBotsInParallel swallows per-bot failures (logging
            // ERROR + incrementing bot_creation_failures_total), so start() can reach
            // here with an empty bot list when every bot failed to authenticate. A group
            // that came up with 0/N live bots is not viable — mark it DEAD (the same
            // terminal state monitorHealth uses via markAsDead) and persist
            // targetStatus=DEAD, rather than lying to the operator with ACTIVE + 0 bots.
            // We do NOT throw here (unlike restart()): direct start() is called from
            // onStartup (per-group isolated) and the controller /start; DEAD is the
            // existing operator-visible state getHealth/getStatus surface. See
            // TECH_DEBT_CLEANUP_2026_07 AD-2. restart() keeps its stricter throw.
            //
            // This check runs BEFORE the schedulers are started so the zero-bot DEAD
            // path never creates them: unlike startPeriodicLogoutScheduler (which
            // early-returns on an empty bot list), startHealthMonitoring would
            // otherwise unconditionally spin up a ScheduledExecutorService that leaks
            // against a DEAD idle runtime until an operator /stop reaps it. See the
            // TECH_DEBT_CLEANUP_2026_07 review, "Health-monitor scheduler is left
            // running on the zero-bot DEAD path".
            if (bots.isEmpty() && group.getBotCount() > 0) {
                runtime.markAsDead();
                // markAsDead() stamped groupDeadSince, opening a group-dead-seconds
                // window. This path never reaches the finally-block stopAllBots, so
                // credit + close that (~0s) window NOW via stopAllBots(botMetrics),
                // rather than leaving it open to be credited in one shot at a much
                // later /stop — which would inflate group_dead_seconds_total by the
                // whole idle-DEAD interval the group sat with nothing to recover.
                // stopAllBots clears groupDeadSince, so the later /stop finds it null
                // and creditGroupDeadSeconds is a no-op there → no double-credit. The
                // runtime is intentionally NOT removed from runningGroups, so
                // getHealth/getStatus/stop still surface the DEAD group. actualStatus
                // stays DEAD (stopAllBots does not touch it). No schedulers were
                // started on this branch, so stopAllBots only closes the empty executor
                // and credits the window. Set the group MDC so the dead-seconds
                // increment is tagged with botGroupId/environmentId, mirroring stop().
                BotMdc.setGroupContext(runtime.getGroupId(), runtime.getEnvironmentId(),
                        runtime.getProduct());
                try {
                    runtime.stopAllBots(botMetrics);
                } finally {
                    BotMdc.clear();
                }
                group.setTargetStatus(BotGroupStatus.DEAD);
                group.setLastFailureReason("Started 0/" + group.getBotCount() + " bots — all bot creations failed");
                group.setLastStartedAt(LocalDateTime.now());
                group.setLastStoppedAt(null);
                botGroupService.save(group);

                log.error("Group {} started 0/{} bots — marking DEAD", group.getName(), group.getBotCount());
                started = true;
                return;
            }

            // The build is done and the bots are up: the runtime stops being STARTING and
            // becomes ACTIVE (GATEWAY_REQUEST_BUDGET A1). Before the schedulers, deliberately —
            // performPeriodicLogout and the health monitor both gate on ACTIVE, so flipping
            // after them would leave a window in which the first tick skipped itself.
            runtime.setActualStatus(BotGroupStatus.ACTIVE);

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
                                               failedRuntime.getEnvironmentId(),
                                               failedRuntime.getProduct());
                        try {
                            failedRuntime.stopAllBots(botMetrics);
                            // Drop any session entries a partially-started group registered
                            // before the failure, so a failed start leaks nothing (AD-8).
                            sessionAggregationService.evictGroup(failedRuntime.getGroupId());
                            groupLifecycleAggregator.evictGroup(failedRuntime.getGroupId());
                            scopedDebugEscalator.evictGroup(failedRuntime.getGroupId());
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
                                           Map<String, String> strategyAssignment) {
        int botCount = group.getBotCount();
        Semaphore semaphore = new Semaphore(botCreationParallelism);
        // The group's product, for the MDC that tags bot_creation_failures_total below.
        // A failure here means no Bot object exists to carry it, so this is the only
        // place the counter can pick the label up (VIPTALK_ALERTING_V2 AD-V1) — and
        // without it, `audience: product` rules cannot be built on that counter at all.
        String product = product(environment, game);

        List<CompletableFuture<Bot>> futures = new ArrayList<>(botCount);

        for (int i = 1; i <= botCount; i++) {
            final int botIndex = i;

            CompletableFuture<Bot> future = CompletableFuture.supplyAsync(() -> {
                BotMdc.setGroupContext(group.getId(), group.getEnvironmentId(), product);
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
        // environmentId/product tags — defeating Decision 5's per-group cardinality
        // goal and leaving the counter unroutable to a product room.
        // Mirror the same try/finally pattern used by start()'s outer catch
        // (lines 251-256) and stop() (lines 422-427).
        BotMdc.setGroupContext(group.getId(), group.getEnvironmentId(), product);
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
     * <p>
     * The bounded value set is {@code validation | auth | budget | unknown}.
     */
    private static String classifyCreationFailure(Throwable cause) {
        // GATEWAY_REQUEST_BUDGET AD-9 — a NEW bounded label value, and it must be tested
        // FIRST. "budget" means this JVM chose not to send the request: the gateway was never
        // asked, so counting it as "auth" would put a self-imposed pacing decision in the
        // same bucket as a rejected credential and make EnvironmentLoginFailing fire on our
        // own throttling. Inert until Phase 3 — nothing throws a GatewayBudgetException while
        // the facade is in observe mode — and first, rather than last, because the heuristic
        // below matches on the substring "token" and these messages can carry it.
        if (cause instanceof com.vingame.bot.common.exception.GatewayBudgetException) {
            return "budget";
        }
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
     * @param strategyAssignment Map from username → assigned strategy registry
     *                           key, computed once per group start by
     *                           {@link StrategyAssignment#assign}. Lookup by
     *                           username; missing keys fall back to
     *                           {@link StrategyId#RANDOM} (defensive — the
     *                           assignment is built from the same identifier
     *                           shape, so a miss is a bug).
     * @return The created and initialized bot
     */
    private Bot createSingleBot(BotGroup group, Environment environment, Game game, int botIndex,
                                Map<String, String> strategyAssignment) {
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
                .autoDepositEnabled(group.isAutoDepositEnabled())
                .depositAmount(depositAmount);
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
        // Affinity-weighted option proposal (AFFINITY_AWARE_PROPOSAL AD-7): the
        // weighted-pick seam lives in RandomBehaviorStrategy.decide, reached only
        // by BETTING_MINI/TAI_XIU bots (both extend BettingMiniGameBot). SLOT and
        // other types have no option id, so the flag is never set on them — they
        // keep the builder default (affinityWeightedProposal=false), mirroring the
        // ramp gating above.
        if (game.getGameType() == GameType.BETTING_MINI || game.getGameType() == GameType.TAI_XIU) {
            behaviorConfigBuilder
                    .rampEnabled(group.isRampEnabled())
                    .rampShape(group.getRampShape())
                    .affinityWeightedProposal(group.isAffinityWeightedProposal());
        }
        BotBehaviorConfig behaviorConfig = behaviorConfigBuilder.build();

        // Resolve assigned strategy. Defensive fallback: if the username is
        // missing from the assignment map (should never happen — both are built
        // from the same namePrefix + i shape), default to RANDOM so the bot
        // still starts. The assignment map carries the per-bot lifecycle
        // identity downstream (Phase 5 will read configuration.strategyId
        // in BettingMiniGameBot.initializeSubclass to build the strategy).
        // StrategyId.RANDOM.name() rather than a "RANDOM" literal: the enum survives
        // as the compile-time catalogue of the built-in keys (PLUGIN_HOT_RELOAD AD-12),
        // so the defensive default stays tied to the catalogue.
        String strategyId = strategyAssignment.getOrDefault(username, StrategyId.RANDOM.name());
        // DEBUG. This was INFO under BETTING_STRATEGIES AD-14, whose argument was
        // explicitly "N bots = N lines at start, mirrors the 'Bot starting in virtual
        // thread' line emitted from BotGroupRuntime at the same scale" — LOG_VOLUME_TIERING
        // supersedes that: INFO may not contain anything whose rate is a function of bot
        // count, and at 30k bots those two classes were ~60k lines per fleet start. The
        // group-level replacement is the one "strategy mix" line at the assignment site;
        // the sibling line in BotGroupRuntime went to DEBUG in the same pass. MDC
        // (botGroupId, botIndex, gameType) is set by createBotsInParallel, so this stays
        // grep-able by group from Loki and reachable per-group via /api/v1/logging/debug.
        log.debug("Bot {}: assigned strategy {}", username, strategyId);

        // SLOT bots always run the basic FIXED slot strategy. Strategy variety has
        // no purpose for slots (slot play is invisible to other players), so any
        // client-supplied group.slotStrategyId is intentionally ignored and
        // overridden to FIXED here — the create/update still succeeds, this is a
        // silent override, not a rejection. The betting strategyId above is
        // meaningless for slots but harmless, so it is left in place unchanged;
        // SlotMachineBot.initializeSubclass reads configuration.slotStrategyId and
        // ignores strategyId.
        String slotStrategyId = null;
        if (game.getGameType() == GameType.SLOT) {
            slotStrategyId = SlotStrategyId.FIXED.name();
            // DEBUG for the same reason as the line above: one per slot bot at group start.
            log.debug("Bot {}: assigned slot strategy {} (slot strategy is not selectable)", username, slotStrategyId);
        }

        BotConfiguration configuration = BotConfiguration.builder()
                .credentials(credentials)
                .environmentId(group.getEnvironmentId())
                // The single source of the `product` metric label (AD-V1): the environment,
                // which is also what the per-environment gauges carry. See product(...).
                .productCode(product(environment, game))
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
    /**
     * The assignment as a {@code {RANDOM=30, MARTINGALE=17}} histogram, for the single
     * group-level line that replaced the per-bot "assigned strategy" INFO lines
     * (LOG_VOLUME_TIERING tier 1). Sorted so two groups with the same mix log the same
     * string and the line is diffable.
     */
    static String strategyCounts(Map<String, String> assignment) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String strategyId : assignment.values()) {
            counts.merge(strategyId, 1, Integer::sum);
        }
        return counts.toString();
    }

    private static List<WeightedStrategy> effectiveStrategyMix(BotGroup group) {
        List<WeightedStrategy> mix = group.getStrategyMix();
        if (mix == null || mix.isEmpty()) {
            return List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0));
        }
        return mix;
    }

    /**
     * Stop a bot group - stops all bots, cleans up resources, removes from runtime map.
     * <p>
     * <b>A group with no in-memory runtime is still parked {@code STOPPED}</b>
     * (DEAD_GROUP_AUTO_RECOVERY Phase 2). This used to be a bare WARN + return that
     * persisted nothing, so a group that died and then outlived its runtime — the
     * ordinary shape after an app restart, since {@code onStartup} rebuilds only
     * {@code targetStatus=ACTIVE} groups — was stuck at {@code DEAD} with no way for
     * an operator to express "leave it down". AD-5 makes {@code STOPPED} the <i>only</i>
     * opt-out from auto-recovery, so a {@code STOPPED} an operator cannot reach is an
     * opt-out they cannot use; that makes this a prerequisite for the recovery
     * reconciler rather than a tidy-up. Persisting the intent is all this path does —
     * there is no runtime to tear down.
     * <p>
     * The path is idempotent: a runtime-less group that is <i>already</i> {@code STOPPED}
     * keeps the historical WARN and writes nothing. Behaviour for a group <b>with</b> a
     * runtime is unchanged.
     */
    public void stop(String id) {
        stop(id, true);
    }

    /**
     * {@link #stop(String)}, with the Phase 2 runtime-less park made optional.
     *
     * @param parkRuntimeless whether a group with no in-memory runtime should have
     *        {@code STOPPED} persisted for it. True for the operator {@code /stop}
     *        entry point, where persisting the intent is the whole point.
     *        <b>False for {@link #restart(String)}</b>: restart's internal stop is a
     *        teardown step, not a statement of intent, and under AD-5 a persisted
     *        {@code STOPPED} is a permanent opt-out from auto-recovery. A
     *        {@code /restart} whose start half throws — the common case for exactly
     *        the groups being restarted here, since the exhaustion ERROR points the
     *        operator at this endpoint while the gateway may still be sick — would
     *        otherwise leave the group parked {@code STOPPED} where it used to be
     *        left {@code DEAD}: silently opted out of the recovery that told the
     *        operator to press the button, invisible to {@code findByTargetStatus(DEAD)}
     *        and to every dead-group signal that reads the persisted status, with
     *        nothing logged to say so.
     */
    private void stop(String id, boolean parkRuntimeless) {
        // Same per-group lock as start() (AD-5) so an operator Stop cannot race a
        // Start's reclaim. restart() calls stop() then start() sequentially
        // (non-nested), so there is no reentrancy/deadlock concern.
        ReentrantLock lock = groupLocks.computeIfAbsent(id, k -> new ReentrantLock());
        lock.lock();
        try {
            BotGroupRuntime runtime = runningGroups.get(id);
            if (runtime == null) {
                if (!parkRuntimeless) {
                    // restart()'s teardown step: there is nothing to tear down and
                    // nothing to say. Pre-Phase-2 behaviour, deliberately.
                    log.warn("Bot group {} is not running", id);
                    return;
                }
                // No runtime to tear down, but the operator's intent still has to
                // land in Mongo — see the javadoc. findById throws
                // ResourceNotFoundException for an unknown id; the only REST caller
                // (BotGroupController.runWithManualOverride) already loaded the group
                // before calling us, so this adds no new 404 to the API surface.
                BotGroup persisted = botGroupService.findById(id);
                if (persisted.getTargetStatus() == BotGroupStatus.STOPPED) {
                    // Already parked. Keep the historical WARN and skip the write so a
                    // repeated /stop cannot keep re-stamping lastStoppedAt.
                    log.warn("Bot group {} is not running", id);
                    return;
                }
                log.info("Bot group {} has no runtime — persisting STOPPED (was {})",
                        id, persisted.getTargetStatus());
                persisted.setTargetStatus(BotGroupStatus.STOPPED);
                persisted.setLastStoppedAt(LocalDateTime.now());
                botGroupService.save(persisted);
                return;
            }

            // No outer try/catch — let the original exception propagate to
            // RestExceptionHandler. Wrapping in RuntimeException would lose the
            // exception type and erase the structured response body.
            // teardownRuntimeMemory credits the open DEAD window (once) under
            // group MDC, cleans up bots, shuts executor + monitor + logout
            // scheduler, evicts session state, and drops from runningGroups.
            teardownRuntimeMemory(id, runtime);

            // Update entity
            BotGroup group = botGroupService.findById(id);
            group.setTargetStatus(BotGroupStatus.STOPPED);
            group.setLastStoppedAt(LocalDateTime.now());
            botGroupService.save(group);

            log.info("Bot group {} stopped successfully", id);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tear down a runtime's in-memory footprint: credit the open group-DEAD
     * window (exactly once, clearing the stamp), cleanup bots (graceful WS close),
     * shut executor + health monitor + logout scheduler, evict aggregated-session
     * state, and drop from {@code runningGroups}. Shared by {@link #stop(String)}
     * and the {@code start()} reclaim path (DEAD_GROUP_RESTART AD-3) so the two
     * teardowns cannot drift. Group MDC is set around {@code stopAllBots} so the
     * dead-seconds increment is tagged with botGroupId/environmentId, mirroring
     * the old inline {@code stop()} body.
     */
    private void teardownRuntimeMemory(String id, BotGroupRuntime runtime) {
        // Set group MDC so the group-level dead-seconds increment (if a DEAD
        // window is open) is tagged with botGroupId/environmentId. Cleared in the
        // finally so we don't leak MDC into the caller thread.
        BotMdc.setGroupContext(runtime.getGroupId(), runtime.getEnvironmentId(),
                runtime.getProduct());
        try {
            // Stop all bots and shutdown executor + monitor + logout scheduler
            runtime.stopAllBots(botMetrics);
        } finally {
            BotMdc.clear();
        }

        // Drop this group's aggregated-session entries immediately so nothing dangles
        // (AD-8 group-stop hook). TTL sweep is the backstop; this reclaims on stop.
        sessionAggregationService.evictGroup(id);
        groupLifecycleAggregator.evictGroup(id);
        scopedDebugEscalator.evictGroup(id);

        // Remove from runtime map
        runningGroups.remove(id);
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
        BotMdc.setGroupContext(runtime.getGroupId(), runtime.getEnvironmentId(),
                runtime.getProduct());
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
        groupLifecycleAggregator.evictGroup(id);
        scopedDebugEscalator.evictGroup(id);
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
     * <p>
     * <b>A failed restart never leaves the group opted out of auto-recovery</b>
     * (DEAD_GROUP_AUTO_RECOVERY AD-5). {@code STOPPED} became a permanent opt-out,
     * and {@code restart} is {@code stop} then {@code start} — so on the unhappy
     * path the internal stop's {@code STOPPED} was the last thing written, and the
     * group was disabled for the very feature whose exhaustion ERROR sends operators
     * to this endpoint. Two things stop that: the internal stop does not park a
     * runtime-less group (see {@link #stop(String, boolean)}), and a start that
     * throws restores whatever the persisted status was before the restart began.
     * A failed restart is therefore a no-op on persisted intent, which is what it
     * always looked like.
     */
    public void restart(String id) {
        log.info("Restarting bot group {}", id);

        // Read before the stop: this is the intent to restore if the start half
        // throws. A group that was already STOPPED stays STOPPED — the restore only
        // ever undoes a STOPPED that this method's own stop() wrote.
        BotGroupStatus statusBeforeRestart = statusBeforeRestart(id);

        stop(id, false);

        // Brief pause before restart (uses virtual thread, no platform thread blocked)
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            start(id);
        } catch (RuntimeException e) {
            restoreStatusAfterFailedRestart(id, statusBeforeRestart);
            throw e;
        }

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
     * The persisted {@code targetStatus} to put back if a {@code /restart}'s start
     * half throws. Best-effort: an unreadable group simply yields {@code null} and
     * no restore is attempted, because the start is about to fail on the same read
     * anyway.
     */
    private BotGroupStatus statusBeforeRestart(String id) {
        try {
            return botGroupService.findById(id).getTargetStatus();
        } catch (Exception e) {
            log.debug("Cannot read bot group {} before restart: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Undo the {@code STOPPED} that {@code restart}'s internal {@code stop} wrote,
     * when the start half failed (DEAD_GROUP_AUTO_RECOVERY AD-5).
     * <p>
     * Only ever fires on the exact shape it is there for: the persisted status is
     * {@code STOPPED} <em>now</em> and was something else before. It is deliberately
     * not a general "restore on failure" — a group the operator had genuinely parked
     * stays parked, and a start that persisted {@code ACTIVE} or the zero-bot guard's
     * {@code DEAD} is left alone, because those are statements the start path made on
     * purpose.
     * <p>
     * Failing to restore is logged and swallowed: the caller is already propagating
     * the real failure, and replacing it with a Mongo error would hide the cause.
     */
    private void restoreStatusAfterFailedRestart(String id, BotGroupStatus statusBeforeRestart) {
        if (statusBeforeRestart == null || statusBeforeRestart == BotGroupStatus.STOPPED) {
            return;
        }
        try {
            BotGroup group = botGroupService.findById(id);
            if (group.getTargetStatus() != BotGroupStatus.STOPPED) {
                return;
            }
            log.warn("Restart of bot group {} failed after its stop persisted STOPPED — "
                            + "restoring targetStatus={} so the group is not silently opted out "
                            + "of auto-recovery", id, statusBeforeRestart);
            group.setTargetStatus(statusBeforeRestart);
            botGroupService.save(group);
        } catch (Exception e) {
            log.error("Could not restore targetStatus={} for bot group {} after a failed restart: {}",
                    statusBeforeRestart, id, e.getMessage(), e);
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
                .ramp(buildRampState(group))
                .build();
    }

    /**
     * Read-side ramp view (JACKPOT_SCALE_AND_RAMP Phase R3, AD-R7). Returns
     * {@code null} when the group has {@code rampEnabled=false}, so the {@code ramp}
     * block is absent for off/legacy groups. When the group is not running,
     * {@link #getHealth(String)} returns before this is ever called, so the block is
     * likewise absent — matching how {@code coordination}/{@code jackpotScale} gate on
     * a running runtime. The ramp is stateless per-bot (AD-R7), so this reads the
     * group entity as the single source of the configured shape rather than any bot's
     * {@code BotBehaviorConfig}; strictly read-only.
     */
    private RampStateDTO buildRampState(BotGroup group) {
        if (!group.isRampEnabled()) {
            return null;
        }
        return RampStateDTO.builder()
                .enabled(true)
                .rampShape(group.getRampShape())
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
                        .crowdStake(o.crowdStake())
                        .observedCrowdStake(o.observedCrowdStake())
                        .crowdAdjustedBudget(o.crowdAdjustedBudget())
                        .observedCrowdCount(o.observedCrowdCount())
                        .build())
                .toList();
        return CoordinationStateDTO.builder()
                .enabled(true)
                .maxAggregateStakePerRound(snapshot.maxAggregateStakePerRound())
                .currentAggregateStake(snapshot.currentAggregateStake())
                .approveCount(snapshot.approveCount())
                .trimCount(snapshot.trimCount())
                .rejectCount(snapshot.rejectCount())
                .crowdAware(snapshot.crowdAware())
                .crowdCountSemantic(snapshot.crowdCountSemantic())
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
     * Check if a bot group is currently running — which, since the start became
     * asynchronous, includes <b>a start that is still in flight</b>
     * (GATEWAY_REQUEST_BUDGET AD-16).
     * <p>
     * Both widenings matter and they cover different windows:
     * <ul>
     *   <li>{@code STARTING} runtime — the build has begun. Without this,
     *       {@code ActivationScheduler} would decide START again on its next tick and
     *       {@code RecoveryCandidateSelector} could pick a group an operator is already
     *       restarting.</li>
     *   <li>an open start attempt with <em>no</em> runtime yet — the task has been submitted
     *       but has not reached {@code startLocked}. Short today, but it is the window a
     *       one-minute reconciler tick lands in most often, and the plan's whole point is that
     *       the gap before a runtime exists can be long.</li>
     * </ul>
     * A {@code DEAD} or {@code STOPPED} runtime is still not running, unchanged.
     */
    public boolean isGroupRunning(String groupId) {
        BotGroupRuntime runtime = runningGroups.get(groupId);
        if (runtime != null) {
            return runtime.getActualStatus() == BotGroupStatus.ACTIVE
                    || runtime.getActualStatus() == BotGroupStatus.STARTING;
        }
        return startAttempts.isOpen(groupId);
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
    public record GameInfo(String gameId, String gameName, String gameType,
                           String environmentId, String product) {
    }

    /**
     * Identity tuple for the {@code environment_join} join gauge (AD-2): the
     * environment id and its readable name (threaded into {@link BotGroupRuntime}
     * at group start from {@code Environment.getName()}), plus the numeric product
     * code (VIPTALK_ALERTING_V2 AD-V1).
     */
    public record EnvInfo(String environmentId, String environmentName, String product) {
    }

    /**
     * Grouping key for {@code bots_by_game_status}: game identity + bot status.
     * {@code environmentId}, {@code gameType} and {@code product} are carried so the
     * {@code GameNoRounds} rule's {@code unless} operands produce identical label
     * sets on both sides after {@code sum by(...)} (VIPTALK_ALERTING_V2 Phase 1/3).
     * All three are functionally determined by {@code gameId}, so they add no series.
     */
    public record GameStatusKey(String gameId, String gameName, BotStatus status,
                                String environmentId, String gameType, String product) {
    }

    /** Grouping key for {@code bots_by_env_status}: environment id + product + bot status. */
    public record EnvStatusKey(String environmentId, BotStatus status, String product) {
    }

    /**
     * Grouping key for the per-environment aggregate gauges
     * {@code bots_managed_by_env} / {@code ws_connections_open_by_env}
     * (VIPTALK_ALERTING_V2 Phase 1). {@code product} is functionally determined by
     * {@code environmentId}, so it costs no extra series.
     */
    public record EnvKey(String environmentId, String product) {
    }

    /**
     * Grouping key for {@code bots_by_plugin_version} (PLUGIN_HOT_RELOAD AD-5): the
     * owning group plus the plugin version its bots' implementations came from.
     * <p>
     * Per <em>group</em> rather than per environment because the question this gauge
     * answers during a step-5/6 drain is "which groups are on which version, and how far
     * has the drain got". {@code environmentId} and {@code product} ride along as the
     * usual routing labels and are functionally determined by the group, so they cost no
     * series. It is deliberately <b>not</b> a group-level MDC key: mid-drain a group is
     * mixed-version, which is exactly why the version is on the row and not on the group.
     */
    public record PluginVersionKey(String botGroupId, String environmentId,
                                   String product, String pluginVersion) {
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
                distinct.putIfAbsent(game.getId(),
                        new GameInfo(game.getId(), game.getName(), gameType(game),
                                environmentId(runtime, game), product(runtime, game)));
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
            distinct.putIfAbsent(envId, new EnvInfo(envId, envName, runtime.getProduct()));
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
                GameStatusKey key = new GameStatusKey(game.getId(), game.getName(), bot.getStatus(),
                        environmentId(runtime, game), gameType(game), product(runtime, game));
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
                EnvStatusKey key = new EnvStatusKey(envId, bot.getStatus(), runtime.getProduct());
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Per-environment analogue of {@link #getTotalManagedBots()}, backing the
     * {@code bots_managed_by_env} MultiGauge (VIPTALK_ALERTING_V2 Phase 1). Groups
     * sharing an environment aggregate into one row; {@code sum(bots_managed_by_env)}
     * must equal {@code bots_managed}.
     */
    public Map<EnvKey, Integer> countManagedBotsByEnv() {
        Map<EnvKey, Integer> counts = new LinkedHashMap<>();
        for (BotGroupRuntime runtime : runningGroups.values()) {
            String envId = runtime.getEnvironmentId();
            if (envId == null) continue;
            counts.merge(new EnvKey(envId, runtime.getProduct()),
                    runtime.getBotInstances().size(), Integer::sum);
        }
        return counts;
    }

    /**
     * Snapshot count of live bots grouped by {@code (botGroupId, pluginVersion)}, backing
     * the {@code bots_by_plugin_version} MultiGauge (PLUGIN_HOT_RELOAD AD-5).
     * <p>
     * This is the <b>only</b> metric family carrying {@code pluginVersion}. The label is
     * deliberately kept off {@code BotMdcTagsMeterFilter}, so it never lands on the
     * {@code bot_*} counters: those are already per-group, and during a drain the label
     * would double the cardinality of every one of them and leave a stale N-labelled copy
     * for Prometheus' full retention window. One join-style gauge answers the drain
     * question without that.
     * <p>
     * Every managed bot is counted — {@link Bot#getPluginVersion()} never returns null —
     * so {@code sum(bots_by_plugin_version)} must equal {@code bots_managed}, and a
     * shortfall means a bot escaped the accounting rather than that it is unversioned.
     * <p>
     * <b>The missing {@code if (envId == null) continue;} is deliberate, and is what makes
     * that invariant hold.</b> Every env-keyed sibling above
     * ({@link #countManagedBotsByEnv()}, {@link #countBotsByEnvAndStatus()},
     * {@link #countOpenWsByEnv()}) skips a group with no {@code environmentId}, because for
     * them the environment <em>is</em> the key. Here it is one label among four, so such a
     * group still contributes a row (with {@code environmentId=""} after the null-safe
     * tagging). Adding the guard back for consistency with its neighbours would silently
     * break verification P1-5 — a shortfall would then mean "some group has no environment",
     * which is exactly the reading the paragraph above tells the operator to rule out.
     */
    public Map<PluginVersionKey, Integer> countBotsByPluginVersion() {
        Map<PluginVersionKey, Integer> counts = new LinkedHashMap<>();
        for (BotGroupRuntime runtime : runningGroups.values()) {
            for (Bot bot : runtime.getBotInstances()) {
                PluginVersionKey key = new PluginVersionKey(runtime.getGroupId(),
                        runtime.getEnvironmentId(), runtime.getProduct(),
                        bot.getPluginVersion());
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Per-environment analogue of {@link #getOpenWsConnectionCount()}, backing the
     * {@code ws_connections_open_by_env} MultiGauge (VIPTALK_ALERTING_V2 Phase 1).
     * Uses {@link Bot#isConnected()} — deliberately the same predicate as the fleet
     * gauge, NOT {@code BotStatus}, so the per-environment rule mirrors the retired
     * fleet rule exactly. A row is emitted for every environment that has managed
     * bots, including one with zero open sockets (the case the rule must catch).
     */
    public Map<EnvKey, Integer> countOpenWsByEnv() {
        Map<EnvKey, Integer> counts = new LinkedHashMap<>();
        for (BotGroupRuntime runtime : runningGroups.values()) {
            String envId = runtime.getEnvironmentId();
            if (envId == null) continue;
            EnvKey key = new EnvKey(envId, runtime.getProduct());
            int open = 0;
            for (Bot bot : runtime.getBotInstances()) {
                if (bot.isConnected()) open++;
            }
            counts.merge(key, open, Integer::sum);
        }
        return counts;
    }

    /**
     * {@link #countOpenWsByEnv()} restricted to runtimes whose {@code actualStatus}
     * is {@code ACTIVE} — the accessor behind AD-10's live-sibling short-circuit
     * (DEAD_GROUP_AUTO_RECOVERY).
     * <p>
     * <b>Why the unrestricted sibling cannot be used for that.</b> AD-10 skips the
     * network probe when "any <em>running</em> group on the same environment" holds
     * an open socket, on the grounds that a live sibling is stronger evidence than a
     * probe. {@code countOpenWsByEnv} iterates <em>all</em> of {@code runningGroups},
     * which includes the DEAD runtime of the group being evaluated:
     * {@code handleBotGroupDeath} only marks the runtime DEAD, it does not stop the
     * bots, and at {@code bot.group.dead.threshold=0.80} a group is DEAD while up to
     * 20% of its bots are still connected. So the most common death shape — the group
     * dies, a minority survives — would have declared its own environment healthy on
     * the strength of its own sockets, and the probe gate that exists to require
     * <em>independent</em> evidence would have been satisfied by the thing being
     * recovered.
     * <p>
     * Filtering to ACTIVE excludes every candidate structurally rather than by
     * arithmetic: {@code RecoveryEligibility} condition 3 rejects any group with an
     * ACTIVE runtime, so a group counted here can never be a group being recovered.
     * <p>
     * <b>What this still does not prove.</b> {@link Bot#isConnected()} stays true for
     * a server-side-pruned zombie (see CLAUDE.md, "Server-Side Subscriber Pruning"),
     * so a fleet of silently evicted bots reads as a live sibling. The short-circuit
     * is cheap positive evidence, not a proof of origin health; the cost of it being
     * wrong is one budgeted attempt (AD-8).
     */
    public Map<EnvKey, Integer> countOpenWsByEnvForActiveRuntimes() {
        Map<EnvKey, Integer> counts = new LinkedHashMap<>();
        for (BotGroupRuntime runtime : runningGroups.values()) {
            String envId = runtime.getEnvironmentId();
            if (envId == null || runtime.getActualStatus() != BotGroupStatus.ACTIVE) continue;
            EnvKey key = new EnvKey(envId, runtime.getProduct());
            int open = 0;
            for (Bot bot : runtime.getBotInstances()) {
                if (bot.isConnected()) open++;
            }
            counts.merge(key, open, Integer::sum);
        }
        return counts;
    }

    /**
     * Per-environment count of bot groups currently in DEAD state, backing the
     * {@code groups_dead_by_env} MultiGauge (VIPTALK_ALERTING_V2 Phase 4).
     * <p>
     * The labelled sibling of the {@code groups_dead_currently} fleet gauge, which
     * stays exactly as it is (AD-V2) — this does not replace it, it makes the same
     * fact routable. Phase 3 retired {@code BotGroupDead} and left group-level
     * deadness visible only indirectly, as a step in its bots' contribution to
     * {@code EnvironmentDeadBotRatioHigh}; a DEAD group is an operator-actionable
     * event in its own right (it must be one-click restarted), so it gets a
     * first-class per-environment series again.
     * <p>
     * The predicate is {@code groupDeadSince != null} — deliberately the same one
     * {@link #countGroupsDeadCurrently()} uses, so {@code sum(groups_dead_by_env)}
     * equals {@code groups_dead_currently}. A row is emitted for <em>every</em>
     * environment that has running groups, including the healthy zero, so a
     * dashboard shows the healthy state rather than a gap.
     */
    public Map<EnvKey, Integer> countDeadGroupsByEnv() {
        Map<EnvKey, Integer> counts = new LinkedHashMap<>();
        for (BotGroupRuntime runtime : runningGroups.values()) {
            String envId = runtime.getEnvironmentId();
            if (envId == null) continue;
            counts.merge(new EnvKey(envId, runtime.getProduct()),
                    runtime.getGroupDeadSince() != null ? 1 : 0, Integer::sum);
        }
        return counts;
    }

    /**
     * Ids of the in-memory runtimes that currently report themselves DEAD
     * (DEAD_GROUP_AUTO_RECOVERY AD-4).
     * <p>
     * The recovery selector is driven by the <em>persisted</em>
     * {@code targetStatus == DEAD}, which is what makes it see a group that died
     * before this JVM started — the longest-down case, and the one
     * {@link #countDeadGroupsByEnv()} is structurally blind to. This accessor
     * supplies the other half of that union: a runtime that is DEAD in memory while
     * Mongo still says otherwise, which happens when {@code handleBotGroupDeath}'s
     * save threw and its catch swallowed the failure.
     * <p>
     * Deliberately ids only, not runtimes: the caller re-reads the persisted group
     * anyway, and handing out live runtime objects to a scheduler thread is not a
     * seam this class wants.
     */
    public Collection<String> listDeadRuntimeGroupIds() {
        List<String> ids = new ArrayList<>();
        for (BotGroupRuntime runtime : runningGroups.values()) {
            if (runtime.isGroupDead()) {
                ids.add(runtime.getGroupId());
            }
        }
        return ids;
    }

    /**
     * Health snapshot of one running bot group, backing the tier-2 fleet rollup
     * (LOG_VOLUME_TIERING Phase 1 step 3).
     * <p>
     * These are exactly the figures {@link #monitorHealth(BotGroupRuntime)} already
     * computes on its 30 s cadence and logs at DEBUG per group. The rollup does not
     * recompute them differently or add a subsystem — it reads them on a 5-minute
     * cadence and prints a line only for the groups that are <em>not</em> clean, so
     * INFO volume scales with sickness rather than with fleet size.
     *
     * @param groupDead whether the group itself has been marked DEAD (the same
     *                  {@code groupDeadSince != null} predicate as
     *                  {@link #countDeadGroupsByEnv()}), which is a reason to print a
     *                  detail line even when every surviving bot looks fine
     */
    public record GroupHealth(String botGroupId, String groupName, String environmentId,
                              String product, int playing, int reconnecting, int dead,
                              int total, boolean groupDead) {

        /**
         * A group is clean when nothing about it warrants a line: it is not DEAD and no
         * bot is DEAD or RECONNECTING. Deliberately <em>not</em> "playing == total" — a
         * bot in a transient CONNECTING/AUTHENTICATING state during a start is normal
         * and must not make every group print a line for the first few minutes of its
         * life.
         */
        public boolean isClean() {
            return !groupDead && dead == 0 && reconnecting == 0;
        }
    }

    /**
     * Per-group health snapshot over every running group, for the tier-2 fleet rollup.
     * Same live iteration and same predicates as {@link #monitorHealth(BotGroupRuntime)}:
     * {@code dead}/{@code reconnecting} from {@link BotStatus}, {@code playing} from
     * {@link Bot#isConnected()} (the connection predicate, not a status).
     */
    public Collection<GroupHealth> listGroupHealth() {
        List<GroupHealth> health = new ArrayList<>();
        for (BotGroupRuntime runtime : runningGroups.values()) {
            List<Bot> bots = runtime.getBotInstances();
            int dead = 0;
            int reconnecting = 0;
            int playing = 0;
            for (Bot bot : bots) {
                if (bot.getStatus() == BotStatus.DEAD) dead++;
                if (bot.getStatus() == BotStatus.RECONNECTING) reconnecting++;
                if (bot.isConnected()) playing++;
            }
            health.add(new GroupHealth(runtime.getGroupId(), runtime.getGroupName(),
                    runtime.getEnvironmentId(), runtime.getProduct(),
                    playing, reconnecting, dead, bots.size(),
                    runtime.getGroupDeadSince() != null));
        }
        return health;
    }

    /**
     * Balance snapshot of one running bot group, backing the
     * {@code group_avg_balance} / {@code group_balance_ratio} MultiGauges
     * (VIPTALK_ALERTING_V2 AD-V12 / AD-V13).
     *
     * @param avgExpectedBalance mean {@link Bot#getExpectedBalance()} over the
     *                           group's <em>active</em> ({@code isConnected()}) bots
     * @param depositAmount      what a single auto-deposit top-up would credit —
     *                           the denominator of the ratio
     */
    public record GroupBalance(String botGroupId, String groupName, String environmentId,
                               String product, String gameId, String gameName,
                               long avgExpectedBalance, long depositAmount) {

        /** Average balance as a fraction of one deposit; the alert threshold is 0.10. */
        public double ratio() {
            return depositAmount > 0 ? (double) avgExpectedBalance / depositAmount : 0d;
        }
    }

    /**
     * Balance snapshot per running bot group, for the low-balance alert (A3).
     * <p>
     * <b>Reads {@code expectedCurrentBalance}, not {@code lastFetchedBalance}</b>
     * (AD-V12). {@code checkBalance()} re-reads the server whenever the two diverge
     * by more than {@code balanceSyncThreshold} — 1% of the deposit amount — so they
     * can never be more than one 1% band apart, which is immaterial at a 10% alert
     * threshold. {@code expected} is also the value the app itself uses to decide a
     * top-up and the value the UI stat reports, so alerting on it keeps alert,
     * behaviour and UI consistent. Same averaging shape as {@link #computeStats}.
     * <p>
     * A row is emitted only when (AD-V13):
     * <ul>
     *   <li>auto-deposit is <b>off</b> for the group — an auto-deposit group dipping
     *       below 10% is normal operation, not an alert;</li>
     *   <li>at least one bot is active ({@code isConnected()}) <em>and</em> has had its
     *       balance established by a server read ({@code lastFetchedBalance >= 0}) — a
     *       stopped or fully reconnecting group must not read 0 and page someone, and a
     *       freshly started one must not publish the uninitialised
     *       {@code expectedCurrentBalance} sentinel.</li>
     * </ul>
     * The one case where {@code expected} is untrustworthy is a bot that stopped
     * receiving rounds, so {@code onNewSession()} never runs and both balance values
     * freeze. That is {@code GameNoRounds}' job (Phase 3), not this metric's.
     * <p>
     * Group-uniform fields ({@code behaviorConfig}, {@code game}) are read off the
     * first bot that carries a configuration: every bot in a group is built from the
     * same {@code BotBehaviorConfig} builder and the same {@code Game}. Nothing here
     * touches Mongo — this runs on the 10 s gauge-refresh thread.
     */
    public Collection<GroupBalance> listGroupBalances() {
        List<GroupBalance> balances = new ArrayList<>();
        for (BotGroupRuntime runtime : runningGroups.values()) {
            List<Bot> bots = runtime.getBotInstances();
            BotConfiguration sample = bots.stream()
                    .map(Bot::getConfiguration)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            if (sample == null) continue;

            BotBehaviorConfig behavior = sample.getBehaviorConfig();
            if (behavior == null || behavior.isAutoDepositEnabled()) continue;

            // Connected AND with a balance actually established from the server.
            // `expectedCurrentBalance` starts at the -100,000,000 sentinel and is only
            // written from a server read, which happens on the first ROUND, not on
            // connect — while isConnected() is true well before that. Averaging the
            // sentinel published group_balance_ratio = -20.0 and fired GroupBalanceLow
            // on a perfectly healthy freshly-started group. Harmless when rounds arrive
            // inside the 5m `for:`; it bit in exactly the case that matters — a game
            // delivering no rounds at all freezes every bot on the sentinel forever, so
            // the product room got "average balance at -2,000% of its deposit" next to
            // the GameNoRounds it duplicates. A bot whose balance has never been
            // established has nothing to say about the group's balance, so it is not
            // counted; a group where none has is not published at all.
            List<Bot> activeBots = bots.stream()
                    .filter(Bot::isConnected)
                    .filter(bot -> bot.getLastFetchedBalance() >= 0)
                    .toList();
            if (activeBots.isEmpty()) continue;

            long depositAmount = behavior.getDepositAmount() > 0
                    ? behavior.getDepositAmount()
                    : Bot.DEFAULT_DEPOSIT_AMOUNT;
            if (depositAmount <= 0) continue;

            long avgExpectedBalance = activeBots.stream()
                    .mapToLong(Bot::getExpectedBalance)
                    .sum() / activeBots.size();

            Game game = sample.getGame();
            balances.add(new GroupBalance(
                    runtime.getGroupId(),
                    runtime.getGroupName() != null ? runtime.getGroupName() : runtime.getGroupId(),
                    runtime.getEnvironmentId(),
                    product(runtime, game),
                    game != null ? game.getId() : null,
                    game != null ? game.getName() : null,
                    avgExpectedBalance,
                    depositAmount));
        }
        return balances;
    }

    /** {@code GameType} enum name, or {@code ""} when unset. */
    private static String gameType(Game game) {
        return game.getGameType() != null ? game.getGameType().name() : "";
    }

    /**
     * The numeric product code for a game-scoped gauge row (AD-V1).
     * <p>
     * <b>The environment wins.</b> Every gauge row and every {@code bot_*} counter now
     * resolves {@code product} from the running group's {@code Environment}; the
     * {@code Game}'s own {@code productCode} is only a fallback for a runtime built
     * without one. Before this, game rows read {@code Game.productCode} and env rows read
     * {@code Environment.productCode}, so a game filed under the wrong product sent its
     * {@code GameNoRounds} and its {@code EnvironmentSocketDown} to two different product
     * rooms — and, worse, broke {@code GameNoRounds} itself, whose {@code unless} only
     * fires when both operands agree on every label. A contradiction is logged at group
     * start (see {@code startLocked}); this method makes it harmless meanwhile.
     *
     * @param runtime the running group the bot belongs to; may be {@code null}.
     * @param game    the bot's game; may be {@code null}.
     * @return the numeric code, or {@code null} when neither knows one — the gauge row
     *         builders render a null as {@code ""}.
     */
    private static String product(BotGroupRuntime runtime, Game game) {
        if (runtime != null && runtime.getProduct() != null && !runtime.getProduct().isEmpty()) {
            return runtime.getProduct();
        }
        return game != null && game.getProductCode() != null ? game.getProductCode().getCode() : null;
    }

    /**
     * The environment id for a game-scoped gauge row — the exact counterpart of
     * {@link #product(BotGroupRuntime, Game)}, and for the same reason.
     * <p>
     * <b>The running group wins.</b> {@code bot_messages_total} and every other
     * {@code bot_*} counter takes {@code environmentId} from the MDC, which is set from
     * {@code BotConfiguration.environmentId} — i.e. {@code BotGroup.environmentId}. The
     * game-scoped gauges used to take it from {@code Game.environmentId} instead, and
     * {@code BotGroupService} deliberately tolerates a {@code Game} whose
     * {@code environmentId} is null (it only rejects one that <em>disagrees</em>). For such
     * a game the gauge row carried {@code environmentId=""} while the counter carried the
     * real UUID, so {@code GameNoRounds}' {@code unless} could never pair its two operands
     * and the rule fired permanently for a perfectly healthy game — a {@code critical},
     * {@code audience: product} page into a product room, forever.
     *
     * @param runtime the running group the bot belongs to; may be {@code null}.
     * @param game    the bot's game; may be {@code null}.
     * @return the environment id, or {@code null} when neither knows one — the gauge row
     *         builders render a null as {@code ""}.
     */
    private static String environmentId(BotGroupRuntime runtime, Game game) {
        if (runtime != null && runtime.getEnvironmentId() != null && !runtime.getEnvironmentId().isEmpty()) {
            return runtime.getEnvironmentId();
        }
        return game != null ? game.getEnvironmentId() : null;
    }

    /**
     * The numeric product code to stamp on a group's bots and meters: the environment's,
     * falling back to the game's. The one place the authority is decided.
     */
    private static String product(Environment environment, Game game) {
        if (environment != null && environment.getProductCode() != null) {
            return environment.getProductCode().getCode();
        }
        return game != null && game.getProductCode() != null ? game.getProductCode().getCode() : null;
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
     * Get actual runtime status (STARTING, ACTIVE, STOPPED, DEAD).
     * <p>
     * A group with no runtime but an open start attempt reads {@code STARTING}
     * (GATEWAY_REQUEST_BUDGET A1): {@code POST /start} answers with this value the moment it
     * accepts the work, before the build thread has created the runtime, and answering
     * {@code STOPPED} there would tell an operator their start did nothing.
     */
    public BotGroupStatus getActualStatus(String id) {
        return Optional.ofNullable(runningGroups.get(id))
                .map(BotGroupRuntime::getActualStatus)
                .orElseGet(() -> startAttempts.isOpen(id)
                        ? BotGroupStatus.STARTING
                        : BotGroupStatus.STOPPED);
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
            BotMdc.setGroupContext(runtime.getGroupId(), runtime.getEnvironmentId(),
                    runtime.getProduct());
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

        // LOG_VOLUME_TIERING AD-12: a dead ratio that is rising but still under the
        // threshold is the last moment at which DEBUG for this group is worth anything.
        // The escalator applies the band (half the threshold) and its own cooldown, so
        // this sample can be handed over unconditionally on every 30 s tick.
        if (!runtime.isGroupDead()) {
            scopedDebugEscalator.onGroupHealth(runtime.getGroupId(), dead, bots.size());
        }

        if (!runtime.isGroupDead() && (double) dead / bots.size() >= deadBotGroupThreshold) {
            handleBotGroupDeath(runtime);
        }
    }

    /**
     * Handle bot group death — mark the runtime DEAD and persist
     * {@code targetStatus=DEAD}.
     *
     * <p><b>Takes the per-group lock</b> (DEAD_GROUP_AUTO_RECOVERY). It never used
     * to, and it runs on the health-monitor thread while {@code stop()} writes
     * {@code STOPPED} only <em>after</em> {@code teardownRuntimeMemory} returns. A
     * monitor tick sitting between its own {@code findById} and {@code save} when an
     * operator's Stop lands would leave Mongo at {@code DEAD} with no runtime — a
     * fully eligible recovery candidate for a group the operator just stopped, and
     * the one AD-5 hole that needs no PATCH to reach. It is narrow (the losing order
     * requires the tick to be mid-flight at {@code shutdownNow}, survive the
     * interrupt, and be slower than one {@code findById} plus one {@code save}), it
     * is pre-existing, and only its <em>consequence</em> is new — but the consequence
     * is that autonomous recovery restarts money-spending bots against operator
     * intent, so the race is closed rather than documented.
     *
     * <p><b>{@code tryLock} with a short timeout, never a bare {@code lock()}.</b>
     * {@code stop()} holds this lock while {@code stopAllBots} waits up to 30 s on
     * the bot executor and then calls {@code healthMonitor.shutdownNow()}. Blocking
     * here uninterruptibly would park the monitor thread behind its own shutdown for
     * that whole window. Failing to acquire is not a lost update either: the lock is
     * held by a {@code start} or a {@code stop}, and both of those write the group's
     * status themselves — whatever they decide is more recent than this tick's
     * opinion. The runtime is left untouched in that case, so the next tick (if there
     * is one; the monitor may be being shut down) simply re-evaluates.
     *
     * <p>Under the lock, the runtime is re-checked for identity against
     * {@code runningGroups}: a runtime that has been torn down or replaced must not
     * be re-marked DEAD, which would re-open a {@code groupDeadSince} window that
     * {@code stopAllBots} has already credited and closed, and which nothing would
     * ever credit again.
     */
    private void handleBotGroupDeath(BotGroupRuntime runtime) {
        String id = runtime.getGroupId();
        ReentrantLock lock = groupLocks.computeIfAbsent(id, k -> new ReentrantLock());
        boolean held = false;
        try {
            held = lock.tryLock(DEATH_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!held) {
            log.warn("Bot group {} looks DEAD but a start/stop holds its lock — leaving the "
                    + "status to that operation", id);
            return;
        }
        try {
            if (runningGroups.get(id) != runtime) {
                log.warn("Bot group {} looks DEAD but its runtime has already been torn down "
                        + "or replaced — not marking it", id);
                return;
            }

            log.error("Bot group {} has been marked as DEAD due to repeated failures", id);

            runtime.markAsDead();

            try {
                BotGroup group = botGroupService.findById(id);
                group.setTargetStatus(BotGroupStatus.DEAD);
                group.setLastFailureReason("Multiple bot disconnections detected");
                botGroupService.save(group);
            } catch (Exception e) {
                log.error("Failed to update database for dead bot group {}: {}", id, e.getMessage());
            }
        } finally {
            lock.unlock();
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
            BotMdc.setGroupContext(runtime.getGroupId(), runtime.getEnvironmentId(),
                    runtime.getProduct());
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

        // INFO (not DEBUG) so the operator has the "why" for the per-bot restart that
        // follows — that restart line is itself DEBUG since LOG_VOLUME_TIERING Phase 1
        // (`Bot {}: restart requested`, Bot.java), which is exactly why this one stays:
        // it is the only default-visible trace that periodic logout is doing anything.
        // Its rate is a function of GROUPS, not bot count — at most one per scheduler
        // interval per group — so it does not breach the tier-1 invariant.
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