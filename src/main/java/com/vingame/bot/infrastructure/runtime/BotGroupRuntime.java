package com.vingame.bot.infrastructure.runtime;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final String environmentId;
    // CopyOnWriteArrayList: bot creation writes infrequently while the Prometheus
    // scrape thread reads via gauge suppliers in ObservabilityConfig. Avoids CME
    // without coarse external locking.
    private final List<Bot> botInstances;
    private final List<Future<?>> botFutures;
    private final ExecutorService executor;

    // Actual runtime status (source of truth while group is managed)
    private BotGroupStatus actualStatus;           // ACTIVE, STOPPED, DEAD
    private BotGroupPlayingStatus playingStatus;   // PLAYING, IDLE, PENDING

    // Runtime metadata
    private Instant startedAt;
    private ScheduledExecutorService healthMonitor;
    private ScheduledExecutorService logoutScheduler;
    private int consecutiveFailures;

    // Round-robin index for periodic logout
    private final AtomicInteger logoutIndex = new AtomicInteger(0);

    // Timestamp of the most recent transition INTO DEAD at the group level.
    // Cleared at stopAllBots() after the dead-window is credited. Volatile because
    // markAsDead() runs on the health-monitor thread and stopAllBots() runs on the
    // BehaviorService caller thread.
    private volatile Instant groupDeadSince;

    /**
     * Create a new runtime for a bot group.
     *
     * @param groupId       The bot group ID
     * @param botCount      Number of bots in the group (used for initial capacity, not thread pool sizing)
     * @param environmentId The environment ID (used for MDC logging context)
     */
    public BotGroupRuntime(String groupId, int botCount, String environmentId) {
        this.groupId = groupId;
        this.environmentId = environmentId;
        this.botInstances = new CopyOnWriteArrayList<>();
        this.botFutures = new CopyOnWriteArrayList<>();
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
            BotConfiguration config = bot.getConfiguration();
            BotMdc.set(
                    config.getBotGroupId(),
                    config.getBotIndex(),
                    config.getEnvironmentId(),
                    config.getGame().getGameType().name(),
                    config.getGame().getId(),
                    config.getGame().getName(),
                    bot.getUserName()
            );
            try {
                log.info("Bot starting in virtual thread {}", Thread.currentThread().getName());
                bot.start();
            } catch (Exception e) {
                log.error("Bot failed in virtual thread {}", Thread.currentThread().getName(), e);
            } finally {
                BotMdc.clear();
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

    public boolean isGroupDead() {
        return this.actualStatus == BotGroupStatus.DEAD;
    }

    /**
     * Mark this bot group as dead due to failures.
     * <p>
     * Stamps {@link #groupDeadSince} so the downtime window is credited to
     * {@code group_dead_seconds_total} at the next {@link #stopAllBots(BotMetrics)}.
     * Idempotent on the timestamp — re-entry into DEAD without an intervening
     * stop does not move the stamp forward, preserving the original entry time.
     */
    public void markAsDead() {
        log.warn("Bot group {} entering DEAD state", groupId);
        this.actualStatus = BotGroupStatus.DEAD;
        if (this.groupDeadSince == null) {
            this.groupDeadSince = Instant.now();
        }
    }

    /**
     * Get the next bot for periodic logout using round-robin.
     * Thread-safe via AtomicInteger.
     *
     * @return The next bot to logout, or null if no bots available
     */
    public Bot getNextBotForLogout() {
        if (botInstances.isEmpty()) {
            return null;
        }
        int index = logoutIndex.getAndUpdate(i -> (i + 1) % botInstances.size());
        return botInstances.get(index);
    }

    /**
     * Stop all bot instances and shutdown executor, with no group-level dead-seconds
     * accounting. Retained for callers that do not have a {@link BotMetrics} reference
     * (older tests, ad-hoc tooling). Prefer {@link #stopAllBots(BotMetrics)}.
     */
    public void stopAllBots() {
        stopAllBots(null);
    }

    /**
     * Stop all bot instances and shutdown executor.
     * <p>
     * Shutdown process:
     * 1. Credit the open group-DEAD window (if any) to {@code group_dead_seconds_total}.
     * 2. Cleanup all bots (closes WebSocket connections gracefully). Each bot
     *    credits its own terminal DEAD window inside {@link Bot#cleanup()}.
     * 3. Shutdown executor (waits up to 30s for tasks to complete).
     * 4. Force shutdown if timeout exceeded.
     * 5. Shutdown health monitor and logout scheduler.
     *
     * @param metrics optional Micrometer facade; pass {@code null} to skip the
     *                group-level dead-seconds increment (bots still credit their
     *                own DEAD windows via {@code Bot.cleanup()}, which holds its
     *                own {@code BotMetrics} reference).
     */
    public void stopAllBots(BotMetrics metrics) {
        log.info("Stopping all bots for group {}", groupId);

        // Credit the open group-DEAD window before cleanup so the timestamp is
        // measured against "now" rather than against the deferred shutdown end.
        // STOPPED itself is excluded by Architecture Decision 3 — only the DEAD
        // window that preceded the stop counts.
        creditGroupDeadSeconds(metrics);

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

        // Shutdown logout scheduler
        if (logoutScheduler != null && !logoutScheduler.isShutdown()) {
            logoutScheduler.shutdownNow();
        }

        log.info("All bots stopped for group {}", groupId);
    }

    /**
     * If a group-level DEAD window is currently open, credit its elapsed seconds
     * and clear the stamp. Idempotent — a second call without re-entering DEAD
     * is a no-op. Tags are read from MDC at the callsite; set
     * {@link BotMdc#BOT_GROUP_ID} (and optionally environment / game) before
     * calling if per-group time series are required.
     */
    private void creditGroupDeadSeconds(BotMetrics metrics) {
        Instant since = this.groupDeadSince;
        if (since == null) return;
        this.groupDeadSince = null;
        if (metrics == null) return;
        long seconds = Duration.between(since, Instant.now()).toSeconds();
        metrics.incGroupDeadSeconds(seconds);
    }
}
