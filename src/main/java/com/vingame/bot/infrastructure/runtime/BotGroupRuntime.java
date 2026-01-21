package com.vingame.bot.infrastructure.runtime;

import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runtime state for a bot group that is currently managed by the application.
 * This class holds the actual running bot instances and their current status.
 * <p>
 * Thread Management:
 * - Uses Java 21 virtual threads for bot execution (lightweight, scalable to 100k+ bots)
 * - Each bot runs in its own virtual thread (not platform thread)
 * - Tracks Future<?> for each bot to monitor individual status
 * - Graceful shutdown with 30s timeout before force termination
 * <p>
 * Scale targets:
 * - Production: up to 2,000 concurrent bots
 * - Load testing: up to 100,000 concurrent bots
 * <p>
 * Virtual threads are ideal here because:
 * - Bots spend most time waiting for WebSocket I/O (handled by Netty's event loop)
 * - Platform threads would be wasteful at scale (2000+ threads = significant memory overhead)
 * - Virtual threads have near-zero overhead and can scale to millions
 */
@Slf4j
@Getter
@Setter
public class BotGroupRuntime {

    private final String groupId;
    private final List<Bot> botInstances;
    private final List<Future<?>> botFutures;
    private final ExecutorService executor;

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
     * @param groupId  The bot group ID
     * @param botCount Number of bots in the group (used for initial capacity, not thread pool sizing)
     */
    public BotGroupRuntime(String groupId, int botCount) {
        this.groupId = groupId;
        this.botInstances = new ArrayList<>(botCount);
        this.botFutures = new ArrayList<>(botCount);
        this.executor = createExecutor(groupId);
        this.actualStatus = BotGroupStatus.ACTIVE;
        this.playingStatus = BotGroupPlayingStatus.IDLE;
        this.startedAt = Instant.now();
        this.consecutiveFailures = 0;
    }

    /**
     * Create virtual thread executor for this bot group.
     * <p>
     * Uses Java 21's virtual threads which are:
     * - Lightweight (minimal memory overhead per thread)
     * - Scalable (can handle 100k+ concurrent tasks)
     * - Efficient (automatically yield during blocking operations)
     * <p>
     * Virtual threads are managed by the JVM and multiplexed onto
     * a small pool of carrier (platform) threads.
     *
     * @param groupId The bot group ID (used for thread naming)
     * @return Virtual thread executor
     */
    private ExecutorService createExecutor(String groupId) {
        ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name("botgroup-" + groupId + "-bot-", 0)
                        .factory()
        );

        log.info("Created virtual thread executor for bot group {}", groupId);
        return virtualExecutor;
    }

    /**
     * Start a bot in a virtual thread.
     * <p>
     * Submits bot.start() as a task and tracks the Future for monitoring.
     *
     * @param bot The bot to start
     */
    public void startBot(Bot bot) {
        Future<?> future = executor.submit(() -> {
            try {
                log.info("Bot {} starting in virtual thread {}", bot.getUserName(), Thread.currentThread().getName());
                bot.start();
            } catch (Exception e) {
                log.error("Bot {} failed in virtual thread {}", bot.getUserName(), Thread.currentThread().getName(), e);
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
     * Stop all bot instances and shutdown executor.
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

        // Shutdown virtual thread executor gracefully
        if (executor != null && !executor.isShutdown()) {
            log.info("Shutting down virtual thread executor for group {}", groupId);
            executor.shutdown();

            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Executor for group {} did not terminate within 30s, forcing shutdown", groupId);
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for executor shutdown for group {}", groupId, e);
                executor.shutdownNow();
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
