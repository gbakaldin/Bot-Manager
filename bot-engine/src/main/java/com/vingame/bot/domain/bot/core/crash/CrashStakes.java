package com.vingame.bot.domain.bot.core.crash;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/**
 * The stake ladder of a CRASH bot ({@code docs/plans/AVIATOR_BOT.md} AD-6). Pure, no I/O;
 * called by the bot to draw a stake and by {@code CrashConfigValidator} to reject a group
 * whose window holds no stake at all (AD-15).
 *
 * <p>The server sends no allowed-bet list (F-6), so the ladder comes from the group:
 * <ul>
 *   <li>{@code step = betIncrement > 0 ? betIncrement : 5_000};</li>
 *   <li>{@code ladder = { k·step : k ≥ 1, minBet ≤ k·step ≤ maxBet }};</li>
 *   <li>{@code maxBet ≤ 0} means unset, and the whole window is then the legacy one,
 *       {@code [20_000, 200_000]} — multiples of 5,000 in that range are what the legacy
 *       Node bot sent.</li>
 * </ul>
 *
 * <p><b>The returned list is computed, not materialised.</b> {@code get(i)} is
 * {@code first + i·step}; nothing is allocated per rung. A group with {@code maxBet}
 * {@code 10^12} and {@code betIncrement} {@code 1} is accepted by the validator, and a
 * materialised ladder would then be terabytes per bot. A ladder with more than
 * {@link Integer#MAX_VALUE} rungs is truncated at the top to that many rungs — a
 * configuration no operator means, and not worth a validation rule.
 */
public final class CrashStakes {

    /** Step used when the group's {@code betIncrement} is unset ({@code <= 0}). Legacy parity. */
    public static final long DEFAULT_STEP = 5_000L;

    /** Window floor used when the group's {@code maxBet} is unset ({@code <= 0}). Legacy parity. */
    public static final long DEFAULT_MIN_BET = 20_000L;

    /** Window ceiling used when the group's {@code maxBet} is unset ({@code <= 0}). Legacy parity. */
    public static final long DEFAULT_MAX_BET = 200_000L;

    private CrashStakes() {
    }

    /**
     * The effective window and step a group's three fields resolve to, before any rung
     * is computed. Exposed so a 400 can name what was actually searched.
     *
     * @param min  lowest allowed stake, inclusive
     * @param max  highest allowed stake, inclusive
     * @param step stake granularity, always {@code > 0}
     */
    public record Window(long min, long max, long step) {
    }

    /**
     * @param minBet       the group's {@code minBet}
     * @param maxBet       the group's {@code maxBet}; {@code <= 0} means unset
     * @param betIncrement the group's {@code betIncrement}; {@code <= 0} means unset
     * @return the window and step {@link #ladder} searches
     */
    public static Window window(long minBet, long maxBet, long betIncrement) {
        long step = betIncrement > 0 ? betIncrement : DEFAULT_STEP;
        if (maxBet <= 0) {
            return new Window(DEFAULT_MIN_BET, DEFAULT_MAX_BET, step);
        }
        return new Window(minBet, maxBet, step);
    }

    /**
     * @param minBet       the group's {@code minBet}
     * @param maxBet       the group's {@code maxBet}; {@code <= 0} means unset
     * @param betIncrement the group's {@code betIncrement}; {@code <= 0} means unset
     * @return every allowed stake, ascending, unmodifiable and random-access; empty when no
     *         positive multiple of the step falls in the window
     */
    public static List<Long> ladder(long minBet, long maxBet, long betIncrement) {
        Window w = window(minBet, maxBet, betIncrement);
        long step = w.step();
        // k >= 1: the cheapest rung is the step itself, whatever minBet says.
        long kFirst = w.min() <= step ? 1 : ceilDiv(w.min(), step);
        long kLast = w.max() / step;
        if (kLast < kFirst) {
            return List.of();
        }
        long count = kLast - kFirst + 1;
        int size = count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
        return new Ladder(kFirst * step, step, size);
    }

    private static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }

    /** {@code first, first + step, …}, {@code size} rungs, computed on access. */
    private static final class Ladder extends AbstractList<Long> implements RandomAccess {

        private final long first;
        private final long step;
        private final int size;

        Ladder(long first, long step, int size) {
            this.first = first;
            this.step = step;
            this.size = size;
        }

        @Override
        public Long get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("rung " + index + " of " + size);
            }
            return first + index * step;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public String toString() {
            return size == 0 ? "[]"
                    : "[" + first + ".." + get(size - 1) + " step " + step + ", " + size + " rungs]";
        }
    }
}
