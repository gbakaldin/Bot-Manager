package com.vingame.bot.domain.bot.strategy.martingale;

import lombok.extern.slf4j.Slf4j;

/**
 * Paroli progression — doubles the bet after every win and resets after a loss
 * or when the consecutive-wins streak reaches {@link #STREAK_CAP}. See
 * {@code docs/plans/MARTINGALE_STRATEGIES.md} Architecture Decision A5b.
 *
 * <p><b>Extra state.</b> {@link #consecutiveWins} tracks how many rounds in a
 * row the bot has won <i>while betting</i>. The streak survives no-bet rounds
 * (we count "rounds won when we wagered," not "consecutive rounds total") and
 * resets on either a loss, a push (treated as loss — Architecture Decision
 * A5e), or a cap-hit reset.
 *
 * <p><b>Per-outcome rules</b> (the base class
 * {@link MartingaleStrategySupport#onRoundEnd} applies clamp / align / reset on
 * top of the raw target this class returns):
 * <ul>
 *   <li><b>Win</b> ({@code balanceDelta > 0}) → increment {@link #consecutiveWins}.
 *       If the new count reaches {@link #STREAK_CAP}, bank the streak: reset
 *       {@code currentBet} to {@code minBet} and clear the counter. Otherwise
 *       double {@code currentBet}.</li>
 *   <li><b>Loss</b> ({@code balanceDelta < 0}) → reset {@code currentBet} to
 *       {@code minBet} and clear the streak counter.</li>
 *   <li><b>Push</b> → routed through the loss hook by the base class
 *       (Architecture Decision A5e).</li>
 *   <li><b>No-bet</b> → unchanged. Streak preserved (default inherited).</li>
 * </ul>
 *
 * <p><b>Cap-hit reset.</b> If the doubled target exceeds {@code maxBet}, the
 * base class flips into the cap-hit reset path and calls
 * {@link #onCapHitReset()}, which additionally clears {@link #consecutiveWins}.
 *
 * <p><b>Overflow guard.</b> Same {@link Math#multiplyExact} pattern as
 * {@link ClassicMartingaleStrategy}: doubling overflow surfaces a sentinel
 * {@link Long#MAX_VALUE} that trips the cap-hit reset.
 *
 * <p><b>Streak cap is a strategy-personality constant, not an operator knob.</b>
 * Architecture Decision A5b explicitly locks it at 3 and rules out
 * configurability through {@code BotGroup} for this phase.
 */
@Slf4j
public abstract class ParoliStrategy extends MartingaleStrategySupport {

    /**
     * Maximum number of consecutive wins before the strategy banks the streak
     * and resets to {@code minBet}. Locked at 3 by Architecture Decision A5b.
     */
    private static final int STREAK_CAP = 3;

    /**
     * Number of consecutive wins on rounds where the bot actually bet. Guarded
     * by the base class's monitor — every read/write happens inside
     * {@code synchronized (this)} of {@link MartingaleStrategySupport#onRoundEnd}.
     */
    private int consecutiveWins;

    protected ParoliStrategy(RiskProfile profile) {
        super(profile);
        this.consecutiveWins = 0;
    }

    /** Test-only accessor — exposes the current streak count for assertions. */
    public final int getConsecutiveWins() {
        synchronized (this) {
            return consecutiveWins;
        }
    }

    @Override
    protected final long nextBetAfterWin(long currentBet) {
        consecutiveWins++;
        if (consecutiveWins >= STREAK_CAP) {
            // Bank the streak: explicit reset to minBet and clear the counter.
            // Returning minBet here means the base class will run minBet through
            // applyClampAlignReset, which is a no-op (minBet aligns to itself
            // and is below maxBet by construction).
            consecutiveWins = 0;
            return cachedMinBet();
        }
        try {
            return Math.multiplyExact(currentBet, 2L);
        } catch (ArithmeticException overflow) {
            // Same pattern as ClassicMartingaleStrategy — surface a sentinel
            // above maxBet so the cap-hit reset path fires (which also clears
            // consecutiveWins via onCapHitReset).
            log.warn("{}: doubling overflow at currentBet={}, signalling cap reset",
                    getClass().getSimpleName(), currentBet);
            return Long.MAX_VALUE;
        }
    }

    @Override
    protected final long nextBetAfterLoss(long currentBet) {
        consecutiveWins = 0;
        return cachedMinBet();
    }

    @Override
    protected final void onCapHitReset() {
        // The doubled target overflowed maxBet — the base class already reset
        // currentBet to minBet. Clear the streak counter to match.
        consecutiveWins = 0;
    }
}
