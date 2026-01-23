package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.botgroup.dto.BotGroupHealthDTO;
import com.vingame.bot.domain.botgroup.dto.BotHealthDTO;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.infrastructure.runtime.BotGroupRuntime;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.websocketparser.auth.AuthClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
            BotFactory botFactory
    ) {
        this.botGroupService = botGroupService;
        this.environmentService = environmentService;
        this.gameService = gameService;
        this.botFactory = botFactory;

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
     */
    @PostConstruct
    public void onStartup() {
        log.info("Bot Manager starting up - checking for bot groups to auto-start");

        botGroupService.findByTargetStatus(BotGroupStatus.ACTIVE)
                .forEach(group -> {
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

        try {
            // Verify environment exists
            if (group.getEnvironmentId() == null) {
                throw new IllegalStateException(
                        "BotGroup " + group.getName() + " has no environmentId set. " +
                                "Please assign an environment before starting the bot group."
                );
            }

            // Verify game exists
            if (group.getGameId() == null) {
                throw new IllegalStateException(
                        "BotGroup " + group.getName() + " has no gameId set. " +
                                "Please assign a game before starting the bot group."
                );
            }

            // Load environment (throws ResourceNotFoundException if not found)
            Environment environment = environmentService.findById(group.getEnvironmentId());

            // Load game configuration (throws ResourceNotFoundException if not found)
            Game game = gameService.findById(group.getGameId());

            // Create runtime state
            BotGroupRuntime runtime = new BotGroupRuntime(id, group.getBotCount());
            runningGroups.put(id, runtime);

            log.info("Creating {} bots for group {} with parallel execution (parallelism={})",
                    group.getBotCount(), group.getName(), botCreationParallelism);

            // Create bots in parallel with controlled concurrency
            List<Bot> bots = createBotsInParallel(group, environment, game);

            // Start all bots
            for (Bot bot : bots) {
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

        } catch (Exception e) {
            log.error("Failed to start bot group {}: {}", group.getName(), e.getMessage(), e);

            // Cleanup runtime if it was created
            BotGroupRuntime failedRuntime = runningGroups.remove(id);
            if (failedRuntime != null) {
                try {
                    failedRuntime.stopAllBots();
                } catch (Exception cleanupEx) {
                    log.error("Error cleaning up bots after failed start: {}", cleanupEx.getMessage());
                }
            }

            throw new RuntimeException("Failed to start bot group: " + e.getMessage(), e);
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
    private List<Bot> createBotsInParallel(BotGroup group, Environment environment, Game game) {
        int botCount = group.getBotCount();
        Semaphore semaphore = new Semaphore(botCreationParallelism);

        List<CompletableFuture<Bot>> futures = new ArrayList<>(botCount);

        for (int i = 1; i <= botCount; i++) {
            final int botIndex = i;

            CompletableFuture<Bot> future = CompletableFuture.supplyAsync(() -> {
                try {
                    // Acquire permit (blocks if at max concurrency)
                    semaphore.acquire();
                    try {
                        return createSingleBot(group, environment, game, botIndex);
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Bot creation interrupted", e);
                }
            }, botCreationExecutor);

            futures.add(future);
        }

        // Wait for all bots to be created and collect results
        List<Bot> bots = new ArrayList<>(botCount);
        List<Throwable> errors = new ArrayList<>();

        for (int i = 0; i < futures.size(); i++) {
            try {
                Bot bot = futures.get(i).join();
                bots.add(bot);
            } catch (Exception e) {
                log.error("Failed to create bot {}/{}: {}", i + 1, botCount, e.getMessage());
                errors.add(e);
            }
        }

        if (!errors.isEmpty()) {
            log.warn("Created {}/{} bots successfully ({} failures)",
                    bots.size(), botCount, errors.size());
        }

        return bots;
    }

    /**
     * Create a single bot with all necessary configuration.
     *
     * @param group       The bot group configuration
     * @param environment The environment configuration
     * @param game        The game configuration
     * @param botIndex    The index of this bot (1-based)
     * @return The created and initialized bot
     */
    private Bot createSingleBot(BotGroup group, Environment environment, Game game, int botIndex) {
        String username = group.getNamePrefix() + botIndex;
        String password = group.getPassword();

        // Generate a unique fingerprint for this bot
        String fingerprint = AuthClient.generateFingerprint();

        BotCredentials credentials = BotCredentials.builder()
                .username(username)
                .password(password)
                .fingerprint(fingerprint)
                .build();

        BotBehaviorConfig behaviorConfig = BotBehaviorConfig.builder()
                .minBet(group.getMinBet())
                .maxBet(group.getMaxBet())
                .betIncrement(group.getBetIncrement())
                .maxTotalBetPerRound(group.getMaxTotalBetPerRound())
                .minBetsPerRound(group.getMinBetsPerRound())
                .maxBetsPerRound(group.getMaxBetsPerRound())
                .chatEnabled(group.isChatEnabled())
                .autoDepositEnabled(group.isAutoDepositEnabled())
                .build();

        BotConfiguration configuration = BotConfiguration.builder()
                .credentials(credentials)
                .environmentId(group.getEnvironmentId())
                .game(game)
                .behaviorConfig(behaviorConfig)
                .zoneName(environment.getMiniZoneName())
                .build();

        // Create bot using factory (authenticates and creates WebSocket client)
        Bot bot = botFactory.createBot(group.getEnvironmentId(), configuration);

        log.info("Created bot {} ({}/{})", username, botIndex, group.getBotCount());
        return bot;
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

        try {
            // Stop all bots and shutdown executor
            runtime.stopAllBots();

            // Remove from runtime map
            runningGroups.remove(id);

            // Update entity
            BotGroup group = botGroupService.findById(id);
            group.setTargetStatus(BotGroupStatus.STOPPED);
            group.setLastStoppedAt(LocalDateTime.now());
            botGroupService.save(group);

            log.info("Bot group {} stopped successfully", id);

        } catch (Exception e) {
            log.error("Error stopping bot group {}: {}", id, e.getMessage(), e);
            throw new RuntimeException("Failed to stop bot group", e);
        }
    }

    /**
     * Restart a bot group (even if DEAD)
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
    }

    /**
     * Schedule a restart for a specific time
     */
    public void scheduleRestart(String id, LocalDateTime time) {
        BotGroup group = botGroupService.findById(id);

        long delayMillis = Duration.between(LocalDateTime.now(), time).toMillis();

        if (delayMillis <= 0) {
            throw new IllegalArgumentException("Scheduled time must be in the future");
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
                    .build();
        }

        List<BotHealthDTO> botDtos = runtime.getBotInstances().stream()
                .map(bot -> BotHealthDTO.builder()
                        .username(bot.getUserName())
                        .connected(bot.isConnected())
                        .balance(bot.getExpectedBalance())
                        .lastFetchedBalance(bot.getLastFetchedBalance())
                        .totalBetsPlaced(bot.getTotalBetsPlaced().get())
                        .totalBetAmount(bot.getTotalBetAmount().get())
                        .lastRoundWinnings(bot.getLastRoundWinnings())
                        .build())
                .toList();

        int connected = (int) botDtos.stream().filter(BotHealthDTO::isConnected).count();

        return BotGroupHealthDTO.builder()
                .groupId(id)
                .groupName(group.getName())
                .status(runtime.getActualStatus())
                .playingStatus(runtime.getPlayingStatus())
                .startedAt(runtime.getStartedAt())
                .consecutiveFailures(runtime.getConsecutiveFailures())
                .totalBots(botDtos.size())
                .connectedBots(connected)
                .disconnectedBots(botDtos.size() - connected)
                .bots(botDtos)
                .build();
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
            try {
                monitorHealth(runtime);
            } catch (Exception e) {
                log.error("Health monitoring error for group {}: {}", runtime.getGroupId(), e.getMessage());
            }
        }, 10, 30, TimeUnit.SECONDS);
    }

    /**
     * Monitor health of bot group
     */
    private void monitorHealth(BotGroupRuntime runtime) {
        // Check if majority of bots are disconnected
        if (runtime.hasMajorityDisconnected()) {
            runtime.incrementFailures();

            if (runtime.getConsecutiveFailures() >= 3) {
                handleBotGroupDeath(runtime);
            }
        } else {
            runtime.resetFailures();
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
            try {
                performPeriodicLogout(runtime);
            } catch (Exception e) {
                log.error("Periodic logout error for group {}: {}",
                        runtime.getGroupId(), e.getMessage(), e);
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
            log.info("Bot {} already disconnected, skipping periodic logout", bot.getUserName());
            return;
        }

        log.info("Periodic logout starting for bot {} in group {}",
                bot.getUserName(), runtime.getGroupId());

        try {
            // Logout (closes connection)
            bot.logout();

            // Wait before reconnecting (virtual thread, no platform thread blocked)
            Thread.sleep(reconnectDelaySeconds * 1000L);

            // Check again if group is still active before reconnecting
            if (runtime.getActualStatus() != BotGroupStatus.ACTIVE) {
                log.info("Group {} stopped during logout delay, skipping reconnect for bot {}",
                        runtime.getGroupId(), bot.getUserName());
                return;
            }

            // Reconnect
            bot.restart();

            log.info("Periodic logout completed for bot {} in group {}",
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
