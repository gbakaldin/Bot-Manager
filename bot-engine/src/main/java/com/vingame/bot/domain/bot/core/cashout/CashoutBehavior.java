package com.vingame.bot.domain.bot.core.cashout;

/**
 * How a cash-out bot picks its target multiplier and its pause between bets
 * ({@code docs/plans/CASHOUT_BOT.md} AD-7). Both are uniform draws.
 *
 * <p>{@link #LEGACY} reproduces the retired Node bots ({@code min_odd 1.1},
 * {@code max_odd 5.0}, {@code random(500, 4500)} ms) and is the only instance in v1.
 * No group, DTO or UI field exposes these yet (OI-5); the record exists so that doing
 * so later is a wiring change, not a redesign.
 *
 * @param minTarget  lowest target multiplier, inclusive
 * @param maxTarget  highest target multiplier
 * @param minDelayMs shortest pause after a bet ends, inclusive
 * @param maxDelayMs longest pause after a bet ends, inclusive
 */
public record CashoutBehavior(double minTarget, double maxTarget, long minDelayMs, long maxDelayMs) {

    /** The legacy loop: target in [1.1, 5.0], pause in [500, 4500] ms. */
    public static final CashoutBehavior LEGACY = new CashoutBehavior(1.1, 5.0, 500L, 4_500L);

    public CashoutBehavior {
        if (minTarget < 1.0 || maxTarget < minTarget) {
            throw new IllegalArgumentException(
                    "target range must satisfy 1.0 <= minTarget <= maxTarget, got [" + minTarget + ", " + maxTarget + "]");
        }
        if (minDelayMs < 0 || maxDelayMs < minDelayMs) {
            throw new IllegalArgumentException(
                    "delay range must satisfy 0 <= minDelayMs <= maxDelayMs, got [" + minDelayMs + ", " + maxDelayMs + "]");
        }
    }
}
