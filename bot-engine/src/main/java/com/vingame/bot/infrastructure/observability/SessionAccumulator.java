package com.vingame.bot.infrastructure.observability;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAccumulator;
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

    // Slot-only aggregate (Phase 3, AD-12). Jackpot-hit count over the synthetic
    // window — incremented from the spin-result feed when the frame's `iJ` flag is
    // set. Round-based games never touch it (they have no per-spin jackpot flag on
    // the flush path), so it stays 0 for betting/Tai Xiu.
    private final LongAdder jackpotHits = new LongAdder();

    // Strategy-decision aggregates (STRATEGY_DECISION_AGGREGATION Phase 1, AD-2/AD-3).
    // A per-flush-window (tumbling) histogram of the chosen option/eid and the
    // window's amount min/max. All lock-free on the feed path: the histogram is a
    // computeIfAbsent over a key space bounded by the game's option cardinality
    // (BauCua ~6, Tai Xiu ~3) + a LongAdder per key, and min/max are LongAccumulators.
    // They are drained/reset once per window by the single flush thread in
    // captureFlushSnapshot() (sumThenReset / getThenReset) — the SAME single-writer
    // capture-then-advance seam the bettor/staked baselines use, so a bet arriving
    // mid-flush lands in the next window rather than vanishing. Keys are never removed
    // (bounded set); only their counters reset, so the map cannot grow unbounded.
    private final ConcurrentHashMap<Integer, LongAdder> optionHistogram = new ConcurrentHashMap<>();
    private final LongAccumulator amountMin = new LongAccumulator(Long::min, Long.MAX_VALUE);
    private final LongAccumulator amountMax = new LongAccumulator(Long::max, Long.MIN_VALUE);

    // Since-last-flush baseline (Phase 2, AD-6). Advanced only by the single flush
    // thread, so plain volatile fields suffice (no CAS needed — AD-6).
    private final AtomicInteger flushSeq = new AtomicInteger(0);
    private volatile int bettorBaseline = 0;
    private volatile long stakedBaseline = 0L;
    // Slot "spins since last" baseline (Phase 3, AD-12). Slots have no round
    // boundary, so the 5s flush reports betEventCount deltas against this baseline
    // and advances it each tick — the same single-flush-thread contract as above.
    private volatile long spinBaseline = 0L;

    // Per-tick flush snapshot (lost-update fix). Captured ONCE by the single flush
    // thread via captureFlushSnapshot() BEFORE the strategy renders, so the rendered
    // "since last" delta and the subsequent baseline advance read identical values.
    // A feed arriving mid-render then falls into the NEXT tick's delta instead of
    // vanishing from both this tick's (rendered before it arrived) and the next's
    // (baseline already past it). Written and read only on the flush thread within a
    // single emitFlush call → plain fields, no cross-thread publication.
    private int flushBettorSnapshot = 0;
    private long flushStakedSnapshot = 0L;
    private long flushSpinSnapshot = 0L;
    // Strategy-decision snapshot (STRATEGY_DECISION_AGGREGATION Phase 1). Drained from
    // the histogram + min/max in captureFlushSnapshot() so the renderer reads one
    // consistent, already-reset window. Sorted (TreeMap) so the rendered line is
    // deterministic/greppable; empty map + identity min/max mean "no bets this window".
    private Map<Integer, Long> flushOptionSnapshot = Map.of();
    private long flushMinSnapshot = 0L;
    private long flushMaxSnapshot = 0L;

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

    /**
     * Record one outbound bet from a bot with no strategy-decision option (the slot
     * spin path in Phase 1). Delegates to {@link #recordBet(String, Integer, long)}
     * with a {@code null} option so nothing lands in the option histogram.
     */
    public void recordBet(String bettor, long amount) {
        recordBet(bettor, null, amount);
    }

    /**
     * Record one outbound bet from a bot (the uniform cross-product stake source),
     * carrying the strategy-decision {@code option}
     * (STRATEGY_DECISION_AGGREGATION Phase 1). Lock-free: the option feeds a
     * per-window histogram bounded by the game's option cardinality, and the amount
     * feeds the window min/max. A {@code null} option is not counted in the histogram.
     */
    public void recordBet(String bettor, Integer option, long amount) {
        if (amount > 0) {
            totalStaked.add(amount);
            amountMin.accumulate(amount);
            amountMax.accumulate(amount);
        }
        betEventCount.increment();
        if (bettor != null) {
            distinctBettors.add(bettor);
        }
        if (option != null) {
            optionHistogram.computeIfAbsent(option, k -> new LongAdder()).increment();
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
     * Record one spin's result into the slot window (Phase 3, AD-12). Adds gross
     * winnings and, when {@code jackpot} is set, one jackpot hit. Unlike
     * {@link #recordEnd} this does NOT mark the session {@code ended} — a slot
     * window is long-lived and reclaimed only by the TTL sweep or group stop, never
     * by grace-then-evict. Touches {@code lastActivityNanos} so a spinning group's
     * window never goes idle past TTL.
     */
    public void recordWin(long winnings, boolean jackpot) {
        if (winnings > 0) {
            winningsTotal.add(winnings);
        }
        if (jackpot) {
            jackpotHits.increment();
        }
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

    public long jackpotHits() {
        return jackpotHits.sum();
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

    public long spinBaseline() {
        return spinBaseline;
    }

    /** Advance the since-last-flush baseline to the current totals (single flush thread). */
    public void advanceBaseline(int bettors, long staked) {
        this.bettorBaseline = bettors;
        this.stakedBaseline = staked;
    }

    /**
     * Advance the slot "spins since last" baseline to the current spin count
     * (Phase 3, AD-12). Single flush thread → no CAS. Called every tick alongside
     * {@link #advanceBaseline}; round-based sessions ignore this baseline.
     */
    public void advanceSpinBaseline(long spins) {
        this.spinBaseline = spins;
    }

    // ---- Flush snapshot (lost-update fix). ----

    /**
     * Capture the flush-delta counters ONCE for this tick (single flush thread only).
     * The strategy render then reads {@link #flushBettorSnapshot()} /
     * {@link #flushStakedSnapshot()} / {@link #flushSpinSnapshot()} and the baseline
     * advance uses the SAME snapshot, so a bet/spin arriving between render and advance
     * is neither double-counted nor lost — it simply lands in the next tick's delta.
     */
    public void captureFlushSnapshot() {
        this.flushBettorSnapshot = distinctBettors.size();
        this.flushStakedSnapshot = totalStaked.sum();
        this.flushSpinSnapshot = betEventCount.sum();
        // Drain the tumbling strategy-decision window (STRATEGY_DECISION_AGGREGATION
        // Phase 1, AD-4): sumThenReset each histogram cell and getThenReset the min/max
        // so this window's decision distribution is captured and the counters reset for
        // the next window. Keys persist (bounded); only counters reset. A bet arriving
        // between the drain here and the next window increments a freshly-zeroed cell —
        // the same lost-update tolerance the staked/bettor snapshot already accepts.
        Map<Integer, Long> snapshot = new TreeMap<>();
        for (Map.Entry<Integer, LongAdder> e : optionHistogram.entrySet()) {
            long count = e.getValue().sumThenReset();
            if (count > 0) {
                snapshot.put(e.getKey(), count);
            }
        }
        this.flushOptionSnapshot = snapshot;
        this.flushMinSnapshot = amountMin.getThenReset();
        this.flushMaxSnapshot = amountMax.getThenReset();
    }

    public int flushBettorSnapshot() {
        return flushBettorSnapshot;
    }

    public long flushStakedSnapshot() {
        return flushStakedSnapshot;
    }

    public long flushSpinSnapshot() {
        return flushSpinSnapshot;
    }

    /**
     * The captured per-window option histogram (option id → count), sorted by option
     * id, containing only options bet on in the just-drained window. Empty when no
     * bets carried an option this window.
     */
    public Map<Integer, Long> flushOptionSnapshot() {
        return flushOptionSnapshot;
    }

    /**
     * The captured minimum staked amount over the just-drained window, or
     * {@link Long#MAX_VALUE} (the accumulator identity) when the window had no
     * positive-amount bet — callers gate on the window bet count before reading.
     */
    public long flushMinSnapshot() {
        return flushMinSnapshot;
    }

    /**
     * The captured maximum staked amount over the just-drained window, or
     * {@link Long#MIN_VALUE} (the accumulator identity) when the window had no
     * positive-amount bet.
     */
    public long flushMaxSnapshot() {
        return flushMaxSnapshot;
    }
}
