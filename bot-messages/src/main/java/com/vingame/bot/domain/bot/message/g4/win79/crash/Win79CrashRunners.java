package com.vingame.bot.domain.bot.message.g4.win79.crash;

/**
 * The 119 Avatar runner mapping ({@code docs/plans/AVIATOR_BOT.md} AD-2, user-confirmed):
 * <b>eid 1 = Jake</b> ({@code jOdd} / {@code jFi}), <b>eid 2 = Neytiri</b>
 * ({@code nOdd} / {@code nFi}). The wire word "odd" means multiplier (payout ratio),
 * never probability, and does not get past this package.
 */
final class Win79CrashRunners {

    /** Jake: wire {@code jOdd} / {@code jFi}. */
    static final int JAKE = 1;

    /** Neytiri: wire {@code nOdd} / {@code nFi}. */
    static final int NEYTIRI = 2;

    /** Runners per round on 119 Avatar. */
    static final int COUNT = 2;

    private Win79CrashRunners() {
    }

    /**
     * F-2: the wire value is a decimal with two places that can arrive as an integer
     * ({@code jOdd: 6}). Parsed as a double, scaled ×100 and rounded, so binary noise
     * ({@code 1.43 × 100 = 142.999…}) never costs a hundredth.
     *
     * @param odd the wire multiplier, or {@code null} when absent
     * @return hundredths; {@code 0} when absent
     */
    static long hundredths(Double odd) {
        return odd == null ? 0L : Math.round(odd * 100.0);
    }
}
