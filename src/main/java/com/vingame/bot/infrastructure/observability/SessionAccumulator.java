package com.vingame.bot.infrastructure.observability;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Concurrent per-session value object holding the running aggregates for one
 * {@code (botGroupId, gameId, sid)} key (AGGREGATED_SESSION_LOGGING plan AD-3).
 * <p>
 * Every feed uses a lock-free adder / concurrent set, so a netty IO thread or a
 * scenario virtual thread can record a bet or a win without ever blocking. There
 * is no synchronization on the hot path; the only CAS is the first-seen EndGame
 * guard ({@link #markEndLogged()}).
 * <p>
 * <b>Bounded by construction (load-bearing).</b> The accumulator carries no
 * per-bot map — bettor identity lives in a single deduplicating
 * {@link ConcurrentHashMap#newKeySet() key set} bounded by the group's live bot
 * count, and every numeric aggregate is a fixed-size adder. Combined with the
 * owning service's size cap + TTL eviction (AD-8), the memory footprint per live
 * session is O(distinct bettors), and dead sessions are removed — this is the
 * anti-leak invariant the whole feature exists to protect.
 */
public final class SessionAccumulator {

    private final SessionAggregationStrategy strategy;

    /**
     * MDC snapshot captured when this session was first observed, so the Phase 2
     * flush thread (which has no MDC of its own) can emit correctly-tagged lines.
     * Unused in Phase 1 (StartGame/EndGame log on the bot's own MDC-tagged thread)
     * but captured now so the structure is flush-ready.
     */
    private final Map<String, String> mdcSnapshot;

    // Aggregates — all lock-free.
    private final LongAdder totalStaked = new LongAdder();
    private final LongAdder betEventCount = new LongAdder();
    private final Set<String> distinctBettors = ConcurrentHashMap.newKeySet();
    private final LongAdder winningsTotal = new LongAdder();
    private final LongAdder confirmedBetTotal = new LongAdder();

    // Since-last-flush baseline (Phase 2, AD-6). Advanced only by the single flush
    // thread, so plain volatile fields suffice (no CAS needed — AD-6).
    private final AtomicInteger flushSeq = new AtomicInteger(0);
    private volatile int bettorBaseline = 0;
    private volatile long stakedBaseline = 0L;

    // First-seen EndGame guard (Implementation Notes "First-seen for EndGame"):
    // every bot accumulates its win/bet first, then exactly one CAS winner logs.
    private final AtomicBoolean endLogged = new AtomicBoolean(false);

    // Eviction bookkeeping (AD-8). lastActivityNanos backs the TTL sweep; `ended`
    // marks a session whose EndGame was observed so the grace-then-evict path can
    // reclaim it. Sweep wiring is Phase 2; the fields exist now so the structure
    // is removable and bounded from the start.
    private volatile long lastActivityNanos;
    private volatile boolean ended = false;

    public SessionAccumulator(SessionAggregationStrategy strategy,
                              Map<String, String> mdcSnapshot,
                              long createdNanos) {
        this.strategy = strategy;
        this.mdcSnapshot = mdcSnapshot;
        this.lastActivityNanos = createdNanos;
    }

    /** Record one outbound bet from a bot (the uniform cross-product stake source). */
    public void recordBet(String bettor, long amount) {
        if (amount > 0) {
            totalStaked.add(amount);
        }
        betEventCount.increment();
        if (bettor != null) {
            distinctBettors.add(bettor);
        }
        touch();
    }

    /** Accumulate one bot's EndGame outcome (gross winnings + server-confirmed stake). */
    public void recordEnd(long winnings, long confirmedBet) {
        if (winnings > 0) {
            winningsTotal.add(winnings);
        }
        if (confirmedBet > 0) {
            confirmedBetTotal.add(confirmedBet);
        }
        this.ended = true;
        touch();
    }

    /**
     * First-seen EndGame guard. Returns {@code true} for exactly one caller across
     * all N bots observing EndGame for this key; the winner logs the summary.
     */
    public boolean markEndLogged() {
        return endLogged.compareAndSet(false, true);
    }

    private void touch() {
        this.lastActivityNanos = System.nanoTime();
    }

    // ---- Read accessors (used by the strategy renderers). ----

    public SessionAggregationStrategy strategy() {
        return strategy;
    }

    public Map<String, String> mdcSnapshot() {
        return mdcSnapshot;
    }

    public long totalStaked() {
        return totalStaked.sum();
    }

    public long betEventCount() {
        return betEventCount.sum();
    }

    public int bettorCount() {
        return distinctBettors.size();
    }

    public long winningsTotal() {
        return winningsTotal.sum();
    }

    public long confirmedBetTotal() {
        return confirmedBetTotal.sum();
    }

    public long lastActivityNanos() {
        return lastActivityNanos;
    }

    public boolean isEnded() {
        return ended;
    }

    // ---- Flush baseline (Phase 2). ----

    public int flushSeq() {
        return flushSeq.get();
    }

    public int nextFlushSeq() {
        return flushSeq.incrementAndGet();
    }

    public int bettorBaseline() {
        return bettorBaseline;
    }

    public long stakedBaseline() {
        return stakedBaseline;
    }

    /** Advance the since-last-flush baseline to the current totals (single flush thread). */
    public void advanceBaseline(int bettors, long staked) {
        this.bettorBaseline = bettors;
        this.stakedBaseline = staked;
    }
}
