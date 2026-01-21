package com.vingame.bot.infrastructure.runtime;

import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runtime state for a bot group that is currently managed by the application.
 * This class holds the actual running bot instances and their current status.
 * <p>
 * Thread Management:
 * - Uses Spring's ThreadPoolTaskExecutor for bot execution
 * - Each bot runs in its own thread from the pool
 * - Tracks Future<?> for each bot to monitor individual status
 * - Graceful shutdown with 30s timeout before force termination
 */
@Slf4j
@Getter
@Setter
public class BotGroupRuntime {

    private final String groupId;
    private final List<Bot> botInstances;
    private final List<Future<?>> botFutures;
    private final ThreadPoolTaskExecutor executor;

    // Actual runtime status (source of truth while group is managed)
    private BotGroupStatus actualStatus;           // ACTIVE, STOPPED, DEAD
    private BotGroupPlayingStatus playingStatus;   // PLAYING, IDLE, PENDING

    // Runtime metadata
    private Instant startedAt;
    private ScheduledExecutorService healthMonitor;
    private int consecutiveFailures;

    /**
     * Create a new runtime for a bot group.
     *
     * @param groupId The bot group ID
     * @param botCount Number of bots in the group (for thread pool sizing)
     */
    public BotGroupRuntime(String groupId, int botCount) {
        this.groupId = groupId;
        this.botInstances = new ArrayList<>(botCount);
        this.botFutures = new ArrayList<>(botCount);
        this.executor = createExecutor(groupId, botCount);
        this.actualStatus = BotGroupStatus.ACTIVE;
        this.playingStatus = BotGroupPlayingStatus.IDLE;
        this.startedAt = Instant.now();
        this.consecutiveFailures = 0;
    }

    /**
     * Create and configure ThreadPoolTaskExecutor for this bot group.
     * <p>
     * Configuration:
     * - Core pool size = bot count (one thread per bot)
     * - Max pool size = bot count (fixed size pool)
     * - Named threads: "botgroup-{groupId}-bot-{n}"
     * - Reject policy: CallerRuns (safety fallback, shouldn't happen with fixed pool)
     *
     * @param groupId The bot group ID
     * @param botCount Number of bots
     * @return Configured and initialized executor
     */
    private ThreadPoolTaskExecutor createExecutor(String groupId, int botCount) {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(botCount);
        taskExecutor.setMaxPoolSize(botCount);
        taskExecutor.setQueueCapacity(0); // No queue, direct handoff
        taskExecutor.setThreadNamePrefix("botgroup-" + groupId + "-bot-");
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationSeconds(30);
        taskExecutor.initialize();

        log.info("Created thread pool for bot group {} with {} threads", groupId, botCount);
        return taskExecutor;
    }

    /**
     * Start a bot in the thread pool.
     * <p>
     * Submits bot.start() as a task and tracks the Future for monitoring.
     *
     * @param bot The bot to start
     */
    public void startBot(Bot bot) {
        Future<?> future = executor.submit(() -> {
            try {
                log.info("Bot {} starting in thread {}", bot.getUserName(), Thread.currentThread().getName());
                bot.start();
            } catch (Exception e) {
                log.error("Bot {} failed in thread {}", bot.getUserName(), Thread.currentThread().getName(), e);
            }
        });

        botInstances.add(bot);
        botFutures.add(future);
    }

    /**
     * Check how many bots are still running.
     *
     * @return Number of bots with non-completed futures
     */
    public long getRunningBotCount() {
        return botFutures.stream()
            .filter(future -> !future.isDone())
            .count();
    }

    /**
     * Check if more than half of bots are disconnected.
     *
     * @return true if >50% bots are disconnected
     */
    public boolean hasMajorityDisconnected() {
        long disconnectedCount = botInstances.stream()
            .filter(bot -> bot.getClient() == null || !bot.getClient().isOpen())
            .count();

        double disconnectedPercentage = (double) disconnectedCount / botInstances.size();
        return disconnectedPercentage > 0.5;
    }

    /**
     * Mark this bot group as dead due to failures
     */
    public void markAsDead() {
        this.actualStatus = BotGroupStatus.DEAD;
    }

    /**
     * Increment failure counter
     */
    public void incrementFailures() {
        this.consecutiveFailures++;
    }

    /**
     * Reset failure counter (e.g., after successful operation)
     */
    public void resetFailures() {
        this.consecutiveFailures = 0;
    }

    /**
     * Stop all bot instances and shutdown thread pool.
     * <p>
     * Shutdown process:
     * 1. Cleanup all bots (closes WebSocket connections gracefully)
     * 2. Shutdown executor (waits up to 30s for tasks to complete)
     * 3. Force shutdown if timeout exceeded
     * 4. Shutdown health monitor
     */
    public void stopAllBots() {
        log.info("Stopping all bots for group {}", groupId);

        // Cleanup all bots (close connections gracefully)
        botInstances.forEach(bot -> {
            try {
                bot.cleanup();
            } catch (Exception e) {
                log.error("Error cleaning up bot {}", bot.getUserName(), e);
            }
        });

        // Shutdown thread pool gracefully
        if (executor != null && !executor.getThreadPoolExecutor().isShutdown()) {
            log.info("Shutting down thread pool for group {}", groupId);
            executor.shutdown();

            try {
                if (!executor.getThreadPoolExecutor().awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Thread pool for group {} did not terminate within 30s, forcing shutdown", groupId);
                    executor.getThreadPoolExecutor().shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for thread pool shutdown for group {}", groupId, e);
                executor.getThreadPoolExecutor().shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Shutdown health monitor
        if (healthMonitor != null && !healthMonitor.isShutdown()) {
            healthMonitor.shutdownNow();
        }

        log.info("All bots stopped for group {}", groupId);
    }
}
