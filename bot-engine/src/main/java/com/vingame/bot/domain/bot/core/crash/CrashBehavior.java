package com.vingame.bot.domain.bot.core.crash;

/**
 * How a crash bot picks its cash-out target and when, after betting opens, it places its
 * bet ({@code docs/plans/AVIATOR_BOT.md} AD-7). Both are uniform draws.
 *
 * <p>{@link #LEGACY} reproduces the retired Node bot ({@code odd = U(1.1, 5.0)}, bet sent
 * {@code random(0, 4500)} ms after the round opens) and is the only instance in v1. No
 * group, DTO or UI field exposes these yet (OI-3); the record exists so that doing so
 * later is a wiring change, not a redesign — the same reasoning as {@code CashoutBehavior}.
 *
 * <p>For a standard crash curve the target is a variance knob, not an EV knob, which is
 * why one uniform draw is enough for v1.
 *
 * @param minTarget     lowest target multiplier, inclusive
 * @param maxTarget     highest target multiplier, inclusive
 * @param betDelayMinMs shortest delay between the round opening and the bet, inclusive
 * @param betDelayMaxMs longest delay between the round opening and the bet, inclusive
 */
public record CrashBehavior(double minTarget, double maxTarget, long betDelayMinMs, long betDelayMaxMs) {

    /** The legacy bot: target in [1.1, 5.0], bet 0-4500 ms after the round opens. */
    public static final CrashBehavior LEGACY = new CrashBehavior(1.1, 5.0, 0L, 4_500L);

    public CrashBehavior {
        if (minTarget < 1.0 || maxTarget < minTarget) {
            throw new IllegalArgumentException(
                    "target range must satisfy 1.0 <= minTarget <= maxTarget, got [" + minTarget + ", " + maxTarget + "]");
        }
        if (betDelayMinMs < 0 || betDelayMaxMs < betDelayMinMs) {
            throw new IllegalArgumentException(
                    "bet delay range must satisfy 0 <= betDelayMinMs <= betDelayMaxMs, got ["
                            + betDelayMinMs + ", " + betDelayMaxMs + "]");
        }
    }
}
