package com.vingame.bot.domain.bot.strategy.martingale;

import lombok.extern.slf4j.Slf4j;

/**
 * D'Alembert progression — linear ramp on losses, linear retreat on wins. See
 * {@code docs/plans/MARTINGALE_STRATEGIES.md} Architecture Decision A5c.
 *
 * <p><b>Per-outcome rules</b> (the base class
 * {@link MartingaleStrategySupport#onRoundEnd} applies clamp / align / reset on
 * top of the raw target this class returns):
 * <ul>
 *   <li><b>Win</b> ({@code balanceDelta > 0}) →
 *       {@code currentBet = max(minBet, currentBet - betIncrement)}. The floor
 *       at {@code minBet} happens here rather than relying solely on the base
 *       class clamp so a single-step retreat below {@code minBet} doesn't
 *       briefly produce a negative raw target.</li>
 *   <li><b>Loss</b> ({@code balanceDelta < 0}) →
 *       {@code currentBet + betIncrement}.</li>
 *   <li><b>Push</b> ({@code balanceDelta == 0} with non-empty bets) is routed
 *       through the loss hook by the base class — Architecture Decision A5e.</li>
 *   <li><b>No-bet</b> ({@code betsByOption.isEmpty()}) → unchanged (default
 *       inherited from the base class).</li>
 * </ul>
 *
 * <p>If the post-loss target exceeds {@code maxBet} after alignment, the base
 * class trips the cap-hit reset path and {@code currentBet} returns to
 * {@code minBet}. Unlike Paroli / Fibonacci, D'Alembert has no extra progression
 * state to clear on cap-hit, so {@link #onCapHitReset()} is not overridden —
 * the base class default no-op suffices.
 *
 * <p>This class is abstract so the two concrete strategy ids share exactly this
 * progression code — Cautious / Aggressive only differ in the
 * {@link AffinityOptionPicker} weighting, which is configured by the
 * {@link RiskProfile} forwarded through the {@code super(...)} constructor.
 */
@Slf4j
public abstract class DAlembertStrategy extends MartingaleStrategySupport {

    protected DAlembertStrategy(RiskProfile profile) {
        super(profile);
    }

    @Override
    protected final long nextBetAfterWin(long currentBet) {
        long step = cachedBetIncrement();
        long retreated = currentBet - step;
        // Floor explicitly at minBet — a single-step retreat that would land
        // below the floor is clamped here rather than briefly going negative
        // and relying on the base class to lift it back up.
        long floor = cachedMinBet();
        return Math.max(floor, retreated);
    }

    @Override
    protected final long nextBetAfterLoss(long currentBet) {
        long step = cachedBetIncrement();
        // betIncrement is guaranteed positive in practice; if a misconfigured
        // 0/negative slips through, applyClampAlignReset floors to minBet and
        // surfaces the misconfiguration as a "no progression" symptom.
        return currentBet + step;
    }
}
