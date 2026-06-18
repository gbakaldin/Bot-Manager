package com.vingame.bot.domain.bot.strategy.martingale;

import lombok.extern.slf4j.Slf4j;

/**
 * Classic Martingale progression — doubles the bet after every loss and resets
 * to {@code minBet} after every win. See
 * {@code docs/plans/MARTINGALE_STRATEGIES.md} Architecture Decision A5a.
 *
 * <p><b>Per-outcome rules</b> (the base class
 * {@link MartingaleStrategySupport#onRoundEnd} applies clamp / align / reset on
 * top of the raw target this class returns):
 * <ul>
 *   <li><b>Win</b> ({@code balanceDelta > 0}) → reset to {@code minBet}.</li>
 *   <li><b>Loss</b> ({@code balanceDelta < 0}) → {@code currentBet * 2}.</li>
 *   <li><b>Push</b> ({@code balanceDelta == 0} with non-empty bets) is routed
 *       through the loss hook by the base class — Architecture Decision A5e.</li>
 *   <li><b>No-bet</b> ({@code betsByOption.isEmpty()}) → unchanged (default
 *       inherited from the base class).</li>
 * </ul>
 *
 * <p><b>Overflow guard.</b> Doubling a {@code long} can overflow around
 * {@code 1.15 * 10^19}. {@link Math#multiplyExact(long, long)} surfaces the
 * overflow as an {@link ArithmeticException} which we map to a sentinel
 * {@link Long#MAX_VALUE} — the base class's
 * {@link MartingaleStrategySupport#applyClampAlignReset(long)} then trips the
 * cap-hit path (the doubled value is above any sane {@code maxBet}) and resets
 * the progression to {@code minBet}. See plan Implementation Note 3.
 *
 * <p>This class is abstract so the eight concrete strategy ids share exactly
 * this progression code — Cautious / Aggressive only differ in the
 * {@link AffinityOptionPicker} weighting, which is configured by the
 * {@link RiskProfile} forwarded through the {@code super(...)} constructor.
 */
@Slf4j
public abstract class ClassicMartingaleStrategy extends MartingaleStrategySupport {

    protected ClassicMartingaleStrategy(RiskProfile profile) {
        super(profile);
    }

    @Override
    protected final long nextBetAfterWin(long currentBet) {
        // Reset to the minimum on any winning round.
        return cachedMinBet();
    }

    @Override
    protected final long nextBetAfterLoss(long currentBet) {
        try {
            return Math.multiplyExact(currentBet, 2L);
        } catch (ArithmeticException overflow) {
            // Doubling overflowed long. Surface a sentinel above any sane
            // maxBet so the base class trips the cap-hit reset path. The bot
            // resumes the progression from minBet on the next round.
            log.warn("{}: doubling overflow at currentBet={}, signalling cap reset",
                    getClass().getSimpleName(), currentBet);
            return Long.MAX_VALUE;
        }
    }
}
