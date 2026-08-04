package com.vingame.bot.domain.bot.strategy.martingale;

import lombok.extern.slf4j.Slf4j;

/**
 * Fibonacci progression — advances one step along the Fibonacci sequence on a
 * loss, retreats two steps on a win. See
 * {@code docs/plans/MARTINGALE_STRATEGIES.md} Architecture Decision A5d.
 *
 * <p><b>Extra state.</b> {@link #fibIndex} (starts at 0) keys the bet amount:
 * {@code currentBet = minBet * fib(fibIndex)} with
 * {@code fib(0) = 1, fib(1) = 1, fib(2) = 2, fib(3) = 3, fib(4) = 5, ...}. The
 * shared {@code currentBet} field remains the source of truth at bet time
 * (matches Classic / Paroli / D'Alembert) — win/loss callbacks recompute it
 * from the updated {@code fibIndex}.
 *
 * <p><b>Per-outcome rules</b> (the base class
 * {@link MartingaleStrategySupport#onRoundEnd} applies clamp / align / reset on
 * top of the raw target this class returns):
 * <ul>
 *   <li><b>Win</b> ({@code balanceDelta > 0}) → {@code fibIndex = max(0,
 *       fibIndex - 2)}, then {@code minBet * fib(fibIndex)}.</li>
 *   <li><b>Loss</b> ({@code balanceDelta < 0}) → {@code fibIndex++}, capped at
 *       {@link #FIB_INDEX_CAP} as an overflow belt-and-braces guard, then
 *       {@code minBet * fib(fibIndex)}.</li>
 *   <li><b>Push</b> → routed through the loss hook by the base class
 *       (Architecture Decision A5e).</li>
 *   <li><b>No-bet</b> → unchanged. {@code fibIndex} preserved (default
 *       inherited from the base class).</li>
 * </ul>
 *
 * <p><b>Overflow guard.</b> {@code fib(i)} grows exponentially; beyond
 * {@code i ≈ 92} the raw Fibonacci number overflows {@code long}, and multiplying
 * by {@code minBet} overflows even earlier. We cap {@code fibIndex} at
 * {@link #FIB_INDEX_CAP} = 64 (any reasonable {@code maxBet} trips the cap-hit
 * reset path long before then) and additionally wrap the {@code minBet * fib(i)}
 * multiplication in {@link Math#multiplyExact} — on
 * {@link ArithmeticException} we surface a {@link Long#MAX_VALUE} sentinel that
 * tells the base class to take the cap-hit reset path. {@link #onCapHitReset()}
 * then clears {@code fibIndex} back to 0.
 *
 * <p><b>Cap-hit reset.</b> If the aligned target exceeds {@code maxBet}, the
 * base class flips into the cap-hit reset path and calls
 * {@link #onCapHitReset()}, which additionally clears {@code fibIndex}. This
 * mirrors Paroli's streak-reset pattern.
 *
 * <p>This class is abstract so the two concrete strategy ids share exactly this
 * progression code — Cautious / Aggressive only differ in the
 * {@link AffinityOptionPicker} weighting, which is configured by the
 * {@link RiskProfile} forwarded through the {@code super(...)} constructor.
 */
@Slf4j
public abstract class FibonacciStrategy extends MartingaleStrategySupport {

    /**
     * Hard cap on {@code fibIndex} as an overflow belt-and-braces guard. Beyond
     * index ~92, {@code fib(i)} overflows {@code long} even without multiplying
     * by {@code minBet}; the multiplication overflows much earlier. The
     * clamp/reset path in {@link MartingaleStrategySupport#applyClampAlignReset}
     * is the primary line of defense — this cap only matters if {@code maxBet}
     * is set to something pathological. See plan Architecture Decision A5d.
     */
    static final int FIB_INDEX_CAP = 64;

    /**
     * Current position in the Fibonacci sequence. Guarded by the base class's
     * monitor — every read/write happens inside {@code synchronized (this)} of
     * {@link MartingaleStrategySupport#onRoundEnd}.
     */
    private int fibIndex;

    protected FibonacciStrategy(RiskProfile profile) {
        super(profile);
        this.fibIndex = 0;
    }

    /** Test-only accessor — exposes the current Fibonacci index for assertions. */
    public final int getFibIndex() {
        synchronized (this) {
            return fibIndex;
        }
    }

    /**
     * Test-only setter — forces {@code fibIndex} to a target value so the
     * overflow guard can be exercised without running 60+ losses to drive the
     * index up to the cap. Used by {@code FibonacciStrategyTest}. Production
     * callers must not depend on this method.
     */
    final void setFibIndexForTest(int index) {
        synchronized (this) {
            this.fibIndex = index;
        }
    }

    @Override
    protected final long nextBetAfterWin(long currentBet) {
        // Two-step retreat, floored at 0. fib(0) = fib(1) = 1, so a retreat
        // from index 1 lands at 0 with the same bet amount (1*minBet) — the
        // base class clamp is a no-op in that case.
        fibIndex = Math.max(0, fibIndex - 2);
        return computeBet(fibIndex);
    }

    @Override
    protected final long nextBetAfterLoss(long currentBet) {
        // Advance one step. Hard-cap at FIB_INDEX_CAP so we never feed an
        // index this far into fib() that the iterative computation itself
        // overflows — the multiplyExact below is the real overflow check, but
        // capping the index keeps the iterative loop bounded.
        if (fibIndex < FIB_INDEX_CAP) {
            fibIndex++;
        }
        return computeBet(fibIndex);
    }

    @Override
    protected final void onCapHitReset() {
        // The aligned target overflowed maxBet (or we surfaced the
        // multiplyExact sentinel) — the base class already reset currentBet
        // to minBet. Restart the Fibonacci sequence too.
        fibIndex = 0;
    }

    /**
     * Computes {@code minBet * fib(index)} with overflow detection. Returns
     * {@link Long#MAX_VALUE} on overflow so the base class trips the cap-hit
     * reset path (which calls {@link #onCapHitReset()} and clears
     * {@code fibIndex}).
     */
    private long computeBet(int index) {
        long minBet = cachedMinBet();
        long fib = fib(index);
        try {
            return Math.multiplyExact(minBet, fib);
        } catch (ArithmeticException overflow) {
            log.warn("{}: minBet * fib({}) overflowed long (minBet={}, fib={}), signalling cap reset",
                    getClass().getSimpleName(), index, minBet, fib);
            return Long.MAX_VALUE;
        }
    }

    /**
     * Iterative Fibonacci with {@code fib(0) = fib(1) = 1, fib(2) = 2, ...}.
     * Caller must ensure {@code index <= FIB_INDEX_CAP}; the loop will not run
     * past index 92 (where {@code long} itself overflows) because of that cap.
     */
    static long fib(int index) {
        if (index <= 1) {
            return 1L;
        }
        long a = 1L;
        long b = 1L;
        for (int i = 2; i <= index; i++) {
            long next = a + b;
            a = b;
            b = next;
        }
        return b;
    }
}
