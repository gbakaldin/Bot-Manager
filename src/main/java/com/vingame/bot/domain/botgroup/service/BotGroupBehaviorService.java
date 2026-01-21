package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.infrastructure.runtime.BotGroupRuntime;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.websocketparser.auth.AuthClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class BotGroupBehaviorService {

    private final BotGroupService botGroupService;
    private final EnvironmentService environmentService;
    private final GameService gameService;
    private final BotFactory botFactory;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

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
        log.info("BotGroupBehaviorService initialized with direct bot instantiation");
    }

    /**
     * Auto-start bot groups on application startup
     * Starts all groups where targetStatus == ACTIVE
     */
    @PostConstruct
    public void onStartup() {
        log.info("Bot Manager starting up - checking for bot groups to auto-start");

        botGroupService.findAll().stream()
                .filter(group -> group.getTargetStatus() == BotGroupStatus.ACTIVE)
                .forEach(group -> {
                    try {
                        log.info("Auto-starting bot group: {} (ID: {})", group.getName(), group.getId());
                        start(Integer.parseInt(group.getId()));
                    } catch (Exception e) {
                        log.error("Failed to auto-start bot group {} (ID: {}): {}",
                                group.getName(), group.getId(), e.getMessage(), e);
                    }
                });

        log.info("Bot Manager startup complete. {} bot groups running", runningGroups.size());
    }

    /**
     * Start a bot group - creates and starts bot instances using Spring DI
     */
    public void start(int id) {
        String groupId = String.valueOf(id);

        // Check if already running
        if (runningGroups.containsKey(groupId)) {
            log.warn("Bot group {} is already running", groupId);
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
            var environment = environmentService.findById(group.getEnvironmentId());

            // Load game configuration (throws ResourceNotFoundException if not found)
            Game game = gameService.findById(group.getGameId());

            // Create runtime state (creates thread pool)
            BotGroupRuntime runtime = new BotGroupRuntime(groupId, group.getBotCount());
            runningGroups.put(groupId, runtime);

            log.info("Creating {} bots for group {} with direct instantiation", group.getBotCount(), group.getName());

            // Create and start each bot using BotFactory
            for (int i = 1; i <= group.getBotCount(); i++) {
                // CRITICAL: Add delay BEFORE creating bot (except for first bot)
                // This ensures WebSocket clients are created sequentially, not simultaneously
                // Matches the JS implementation which delays between bot creation
                if (i > 1) {
                    try {
                        Thread.sleep(2000); // 2 second delay before creating next bot
                        log.debug("Delayed 2s before creating bot {}/{}", i, group.getBotCount());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Bot startup delay interrupted");
                    }
                }

                // Generate credentials for this bot
                String username = group.getNamePrefix() + (i);
                String password = group.getPassword();

                // Generate a unique fingerprint for this bot that will be preserved across all API calls
                // This simulates a real player using the same device consistently
                String fingerprint = AuthClient.generateFingerprint();

                BotCredentials credentials = BotCredentials.builder()
                    .username(username)
                    .password(password)
                    .fingerprint(fingerprint)
                    .build();

                // Create behavior configuration from BotGroup
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

                // Combine into full configuration
                BotConfiguration configuration = BotConfiguration.builder()
                    .credentials(credentials)
                    .environmentId(group.getEnvironmentId())
                    .game(game)
                    .behaviorConfig(behaviorConfig)
                    .zoneName(environment.getMiniZoneName())
                    .build();

                // Create bot using factory (direct instantiation with fluent setters)
                // This authenticates and creates the WebSocket client
                Bot bot = botFactory.createBot(
                    group.getEnvironmentId(),
                    configuration
                );

                // Start bot in thread pool
                runtime.startBot(bot);

                log.info("Created and started bot {} ({}/{})",
                    username, i, group.getBotCount());

                // CRITICAL: Add delay AFTER starting bot to ensure connection completes
                // before next bot attempts to connect
                if (i < group.getBotCount()) {
                    try {
                        Thread.sleep(3000); // 3 second delay to allow bot to fully connect
                        log.debug("Delayed 3s after starting bot {}/{} to allow connection to complete", i, group.getBotCount());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Bot connection delay interrupted");
                    }
                }
            }

            // Start health monitoring
            startHealthMonitoring(runtime);

            // Update entity
            group.setTargetStatus(BotGroupStatus.ACTIVE);
            group.setLastStartedAt(LocalDateTime.now());
            group.setLastStoppedAt(null);
            botGroupService.save(group);

            log.info("Bot group {} started successfully with {} bots", group.getName(), group.getBotCount());

        } catch (Exception e) {
            log.error("Failed to start bot group {}: {}", group.getName(), e.getMessage(), e);

            // Cleanup runtime if it was created
            BotGroupRuntime failedRuntime = runningGroups.remove(groupId);
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
     * Stop a bot group - stops all bots, cleans up resources, removes from runtime map
     */
    public void stop(int id) {
        String groupId = String.valueOf(id);

        BotGroupRuntime runtime = runningGroups.get(groupId);
        if (runtime == null) {
            log.warn("Bot group {} is not running", groupId);
            return;
        }

        try {
            // Stop all bots and shutdown thread pool
            // This will call cleanup() on each bot to close WebSocket connections
            runtime.stopAllBots();

            // Remove from runtime map
            runningGroups.remove(groupId);

            // Update entity
            BotGroup group = botGroupService.findById(id);
            group.setTargetStatus(BotGroupStatus.STOPPED);
            group.setLastStoppedAt(LocalDateTime.now());
            botGroupService.save(group);

            log.info("Bot group {} stopped successfully", groupId);

        } catch (Exception e) {
            log.error("Error stopping bot group {}: {}", groupId, e.getMessage(), e);
            throw new RuntimeException("Failed to stop bot group", e);
        }
    }

    /**
     * Restart a bot group (even if DEAD)
     */
    public void restart(int id) {
        log.info("Restarting bot group {}", id);
        stop(id);

        // Brief pause before restart
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
    public void scheduleRestart(int id, LocalDateTime time) {
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
     * Get actual runtime status (ACTIVE, STOPPED, DEAD)
     */
    public BotGroupStatus getActualStatus(int id) {
        String groupId = String.valueOf(id);
        return Optional.ofNullable(runningGroups.get(groupId))
                .map(BotGroupRuntime::getActualStatus)
                .orElse(BotGroupStatus.STOPPED);
    }

    /**
     * Get playing status (PLAYING, IDLE, PENDING) - only relevant when ACTIVE
     */
    public BotGroupPlayingStatus getPlayingStatus(int id) {
        String groupId = String.valueOf(id);
        return Optional.ofNullable(runningGroups.get(groupId))
                .map(BotGroupRuntime::getPlayingStatus)
                .orElse(null); // null if not running
    }

    /**
     * Start health monitoring for a bot group
     */
    private void startHealthMonitoring(BotGroupRuntime runtime) {
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
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
            BotGroup group = botGroupService.findById(Integer.parseInt(runtime.getGroupId()));
            group.setTargetStatus(BotGroupStatus.DEAD);
            group.setLastFailureReason("Multiple bot disconnections detected");
            botGroupService.save(group);
        } catch (Exception e) {
            log.error("Failed to update database for dead bot group {}: {}", runtime.getGroupId(), e.getMessage());
        }
    }

}

