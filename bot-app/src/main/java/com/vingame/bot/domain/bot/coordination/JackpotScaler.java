package com.vingame.bot.domain.bot.coordination;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Group-scoped, in-memory, scope-agnostic jackpot volume scaler. It observes the
 * game's live jackpot pool meter (parsed off EndGame as {@code tJpV}, exposed via
 * {@link com.vingame.bot.domain.bot.message.HasJackpotPool}) and derives a
 * per-round <em>volume scale factor</em> {@code f ∈ [minMultiplier, 1.0]} that the
 * bots apply to their per-round bet ceiling ({@code maxBetsPerRound}). A higher
 * pool ⇒ a factor nearer {@code 1.0} ⇒ bots bet at fuller intensity, mimicking
 * real players piling in as a jackpot grows.
 *
 * <p>See {@code docs/plans/JACKPOT_SCALE_AND_RAMP.md} (Phase J3, AD-J5/AD-J8/AD-J10).
 * Deliberately Spring-free and carrying no {@code BotGroup}/{@code BotGroupRuntime}
 * dependency (AD-S2, mirroring {@link BetCoordinator} AD-8): the group wiring builds
 * one from {@code Game} fields; a future Fleet builds one identically from
 * fleet-level fields and owns it.
 *
 * <p><b>Transfer function (AD-J5).</b> For observed pool {@code p}, seed floor
 * {@code s}, ceiling {@code c}, minimum multiplier {@code m}:
 * <pre>
 *   if no non-zero pool observed yet OR c <= s:  f = 1.0   (neutral)
 *   else:  t = clamp((p - s) / (c - s), 0, 1);   f = m + (1 - m) * t
 * </pre>
 * A raw {@code 0} reading is "not observed" (Jackson's default for a missing
 * {@code tJpV}) and maps to neutral — never to the floor. So a meterless Tai Xiu
 * game with the flag on degrades to neutral (today's volume), it is not throttled.
 *
 * <p><b>Concurrency (mirrors {@link BetCoordinator} AD-3).</b> A single
 * {@link ReentrantLock} guards the first-seen idempotency and the paired update of
 * {@code lastObservedPool}/{@code currentFactor}/{@code observed}. {@code observePool}
 * runs on the netty message-processor thread (one call per bot per round);
 * {@link #getCurrentFactor()} is a lock-free {@code volatile} read on the scenario
 * thread. {@code ReentrantLock} (unlike {@code synchronized}) does not pin virtual
 * threads.
 */
@Slf4j
public final class JackpotScaler {

    /** Known server jackpot reset value (≈500k); the seed floor of the transfer function (AD-J6). */
    public static final long DEFAULT_SEED_FLOOR = 500_000L;

    private final long ceiling;
    private final long seedFloor;
    private final double minMultiplier;

    private final ReentrantLock lock = new ReentrantLock();

    // Guarded for the paired write under the lock; read lock-free via volatiles.
    private volatile long lastObservedPool;
    private volatile double currentFactor = 1.0;
    private volatile boolean observed;

    // First-seen guard for observePool (AD-J7). observePool is called once per bot
    // per round (N calls for one sid); track the last sid already folded in so only
    // the first bot to complete a given round recomputes + emits the DEBUG summary.
    // Mutated only under the lock. 0 = no round observed yet.
    private long lastObservedSessionId;

    /**
     * @param ceiling       operator-configured per-game ceiling ({@code Game.jackpotCeiling}).
     *                      At/above it the factor is {@code 1.0}.
     * @param seedFloor     the jackpot reset value; typically {@link #DEFAULT_SEED_FLOOR}.
     *                      At/below it the factor is {@code minMultiplier}.
     * @param minMultiplier the factor's floor (baseline quiet-table presence, e.g. 0.25).
     */
    public JackpotScaler(long ceiling, long seedFloor, double minMultiplier) {
        this.ceiling = ceiling;
        this.seedFloor = seedFloor;
        this.minMultiplier = minMultiplier;
    }

    /**
     * Fold this round's observed pool into the scaler (AD-J7). First-seen
     * idempotent across the group's bots: under the lock, only the first call for a
     * given {@code sessionId} recomputes {@code currentFactor} and emits the AD-J10
     * DEBUG summary; the other N−1 bots' calls for the same sid are a no-op. A raw
     * {@code 0} pool is "not observed" and never trips the {@code observed} flag, so
     * the factor stays neutral until a genuine non-zero meter arrives (AD-J5).
     *
     * @param sessionId the completed round's session id (first-seen key).
     * @param pool      the observed live pool meter ({@code tJpV}); {@code 0} = not observed.
     */
    public void observePool(long sessionId, long pool) {
        double factor;
        lock.lock();
        try {
            if (sessionId != 0L && sessionId == lastObservedSessionId) {
                return;
            }
            lastObservedSessionId = sessionId;
            if (pool > 0L) {
                lastObservedPool = pool;
                observed = true;
            }
            factor = computeFactor();
            currentFactor = factor;
        } finally {
            lock.unlock();
        }
        // AD-J10: one DEBUG line per group per round, on the first-seen path only.
        log.debug("JackpotScale sid={} pool={} factor={} ceiling={}",
                sessionId, pool, factor, ceiling);
    }

    /**
     * The current volume scale factor for the upcoming round (AD-J4). Lock-free
     * {@code volatile} read. Returns the neutral {@code 1.0} until a non-zero pool
     * has been observed at least once this run.
     */
    public double getCurrentFactor() {
        return currentFactor;
    }

    /**
     * Recompute the factor from {@code lastObservedPool} per the AD-J5 transfer
     * function. Must be called under the lock. Neutral ({@code 1.0}) whenever the
     * signal is untrustworthy: nothing non-zero observed yet, or a degenerate
     * {@code ceiling <= seedFloor}.
     */
    private double computeFactor() {
        if (!observed || ceiling <= seedFloor) {
            return 1.0;
        }
        double t = (double) (lastObservedPool - seedFloor) / (double) (ceiling - seedFloor);
        t = Math.max(0.0, Math.min(1.0, t));
        return minMultiplier + (1.0 - minMultiplier) * t;
    }

    /**
     * Coherent snapshot of the scaler's live state for the Phase J4 health DTO,
     * read under a single lock acquisition to avoid a torn view.
     */
    public Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(lastObservedPool, currentFactor, ceiling, seedFloor, minMultiplier);
        } finally {
            lock.unlock();
        }
    }

    /** Coherent whole-scaler view for the health DTO (Phase J4). */
    public record Snapshot(long lastObservedPool,
                           double currentFactor,
                           long ceiling,
                           long seedFloor,
                           double minMultiplier) {
    }
}
