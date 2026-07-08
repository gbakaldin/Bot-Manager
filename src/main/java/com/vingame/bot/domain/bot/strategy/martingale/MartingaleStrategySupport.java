package com.vingame.bot.domain.bot.strategy.martingale;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BetDecision;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.RoundResult;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Shared base for every Martingale-family strategy. Owns the per-bet bookkeeping
 * common to Classic Martingale, Paroli, D'Alembert, and Fibonacci:
 *
 * <ul>
 *   <li>{@code currentBet} — the next bet amount this strategy will place.
 *       Lazily initialised to {@code behavior.minBet} on the first
 *       {@link #decide(BetContext)} call because the strategy has no access to
 *       {@link BotBehaviorConfig} at construction time (see
 *       {@code docs/plans/MARTINGALE_STRATEGIES.md} Implementation Note 1).</li>
 *   <li>{@code currentRoundSessionId} / {@code numberOfBetsInCurrentSession} —
 *       per-round counter handling identical to
 *       {@link com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy}.</li>
 *   <li>Cached {@code minBet} / {@code maxBet} / {@code betIncrement} — captured
 *       on the first {@code decide} call so {@link #onRoundEnd(RoundResult)},
 *       which receives no {@link BotBehaviorConfig}, can apply the clamp /
 *       align / reset rules in {@link #applyClampAlignReset(long)}.</li>
 *   <li>An {@link AffinityOptionPicker} instantiated with the
 *       {@link RiskProfile} forwarded by the concrete subclass.</li>
 * </ul>
 *
 * <p><b>Progression hooks.</b> Concrete progression classes
 * ({@code ClassicMartingaleStrategy}, {@code ParoliStrategy},
 * {@code DAlembertStrategy}, {@code FibonacciStrategy}) implement the three
 * {@code nextBetAfterX} hooks to mutate {@code currentBet} (and any progression-
 * specific state) in response to win / loss / no-bet outcomes. The shared
 * {@link #onRoundEnd(RoundResult)} method dispatches to the right hook under
 * {@code synchronized (this)}, then applies the clamp / align / reset rules
 * exactly once before storing the result back into {@code currentBet}.
 *
 * <p>The hooks are called <i>inside</i> the synchronized block and must not
 * synchronize on {@code this} themselves (that would re-enter the monitor, which
 * is fine on the JVM but redundant). Subclass mutable state (e.g. Paroli's
 * {@code consecutiveWins}, Fibonacci's {@code fibIndex}) is protected by the
 * same monitor; no separate locking is needed.
 *
 * <p><b>Thread model.</b> Same as
 * {@link com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy}:
 * {@code decide} runs on the scenario thread, {@code onRoundEnd} runs on the
 * netty processor thread for this bot. The two threads are distinct, so every
 * mutable-state access is wrapped in {@code synchronized (this)}.
 *
 * <p><b>Clamp / align / reset semantics</b> (A5f in the plan):
 * <ol>
 *   <li>Align the raw target down to the nearest {@code minBet + k*betIncrement}
 *       step.</li>
 *   <li>Floor at {@code minBet}.</li>
 *   <li>If the aligned target exceeds {@code maxBet}, reset to {@code minBet}
 *       and return a {@link ClampResult} flagged as a cap-hit reset — concrete
 *       progressions key off the flag to clear progression-specific state (e.g.
 *       Paroli's streak counter, Fibonacci's {@code fibIndex}).</li>
 * </ol>
 *
 * <p><b>Logging.</b> Matches {@link com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy}
 * conventions: DEBUG per {@code decide}, DEBUG per {@code onRoundEnd}, no
 * routine INFO. The reflective {@code getClass().getSimpleName()} prefix in
 * each log line lets operators tell the eight concrete strategies apart in a
 * shared log file.
 */
@Slf4j
public abstract class MartingaleStrategySupport implements BettingStrategy {

    private final RiskProfile profile;
    private final AffinityOptionPicker picker;

    private long currentBet;
    private long currentRoundSessionId;
    private int numberOfBetsInCurrentSession;

    /**
     * Cached behavior bounds — populated on the first {@code decide()} call so
     * {@link #onRoundEnd(RoundResult)} can apply the clamp / align / reset
     * rules without a live {@link BotContext}.
     *
     * <p>{@code boundsCached} flips to {@code true} only after a successful
     * decide, so a defensive {@code onRoundEnd} that arrives before any
     * {@code decide} (theoretically impossible in production — a round must
     * start before it can end — but pinned by a test in Phase 2 onward) is a
     * WARN-and-skip rather than a NPE / divide-by-zero.
     */
    private long cachedMinBet;
    private long cachedMaxBet;
    private long cachedBetIncrement;
    private boolean boundsCached;

    /**
     * @param profile risk profile that the picker uses to weight options. Locked
     *                at construction (Architecture Decision A2).
     */
    protected MartingaleStrategySupport(RiskProfile profile) {
        this.profile = profile;
        this.picker = new AffinityOptionPicker(profile);
        this.currentBet = 0L;
        this.currentRoundSessionId = 0L;
        this.numberOfBetsInCurrentSession = 0;
    }

    public final RiskProfile getProfile() {
        return profile;
    }

    /** Test-only accessor — returns the next bet amount the strategy will place. */
    public final long getCurrentBet() {
        synchronized (this) {
            return currentBet;
        }
    }

    @Override
    public final Optional<BetDecision> decide(BetContext ctx) {
        BotBehaviorConfig behavior = ctx.behavior();
        long amount;

        long sid = ctx.currentRound().getSessionId();
        synchronized (this) {
            // Round boundary: reset per-round bet counter when the in-flight
            // RoundState's sessionId changes. Matches RandomBehaviorStrategy
            // (line 70-73) — keeps the gating semantics consistent across the
            // strategy family.
            if (sid != currentRoundSessionId) {
                currentRoundSessionId = sid;
                numberOfBetsInCurrentSession = 0;
            }

            // Cache behavior bounds on first call. onRoundEnd uses these.
            // Lazy init (Implementation Note 1): strategies can't see
            // BotBehaviorConfig at construction time, so the first decide is
            // the only place to grab them.
            cachedMinBet = behavior.getMinBet();
            cachedMaxBet = behavior.getMaxBet();
            cachedBetIncrement = behavior.getBetIncrement();
            boundsCached = true;
            if (currentBet == 0L) {
                currentBet = cachedMinBet;
            }

            // JACKPOT_SCALE_AND_RAMP (AD-J4): read the jackpot-scaled per-round cap
            // from the context, not behavior.getMaxBetsPerRound() directly. With the
            // feature off (factor 1.0) it equals behavior.getMaxBetsPerRound() exactly.
            int maxBetsPerRound = ctx.effectiveMaxBetsPerRound();
            if (numberOfBetsInCurrentSession >= maxBetsPerRound) {
                log.trace("{}.decide: skip — already placed {} bets this round (max {})",
                        getClass().getSimpleName(),
                        numberOfBetsInCurrentSession, maxBetsPerRound);
                return Optional.empty();
            }

            // Skip-tick gate runs BEFORE the option pick — mirrors
            // RandomBehaviorStrategy line 82 ordering so RNG consumption stays
            // deterministic in tests. betSkipPercentage defaults to 0 until
            // BotGroup wires it through (BETTING_STRATEGIES Implementation
            // Note 2), so today the gate is effectively a no-op.
            if (ctx.rng().nextInt(100) < behavior.getBetSkipPercentage()) {
                log.trace("{}.decide: skip — betSkipPercentage gate fired",
                        getClass().getSimpleName());
                return Optional.empty();
            }
            numberOfBetsInCurrentSession++;

            // Capture currentBet inside the monitor — onRoundEnd may mutate it
            // on the netty processor thread between releasing the monitor and
            // building the BetDecision.
            amount = currentBet;
        }

        int option = picker.pick(ctx.game().getEffectiveOptionAffinities(), ctx.rng());
        log.trace("{}.decide: bet option={}, amount={} (currentBet={}, profile={})",
                getClass().getSimpleName(), option, amount, amount, profile);
        return Optional.of(new BetDecision(option, amount));
    }

    @Override
    public final void onRoundEnd(RoundResult result) {
        synchronized (this) {
            if (!boundsCached) {
                // Defensive guard (Implementation Note 1): onRoundEnd arrived
                // before any decide cached the bounds. We have nothing to align
                // against, so skip the progression update — the next decide
                // will populate the cache and the strategy resumes on the
                // round after.
                log.warn("{}.onRoundEnd: behavior bounds not yet cached — skipping progression update (sessionId={}, delta={})",
                        getClass().getSimpleName(), result.sessionId(), result.balanceDelta());
                return;
            }

            long staked = 0L;
            for (Long v : result.betsByOption().values()) {
                if (v != null) staked += v;
            }

            long prevBet = currentBet;
            long rawTarget;
            if (staked == 0L) {
                // No-bet round — most progressions leave currentBet untouched,
                // but the hook is overridable for completeness.
                rawTarget = nextBetAfterNoBet(currentBet);
            } else if (result.balanceDelta() > 0L) {
                rawTarget = nextBetAfterWin(currentBet);
            } else {
                // delta < 0 (loss) and delta == 0 with non-empty bets (push).
                // Push is treated as a loss for all four progressions —
                // Architecture Decision A5e.
                rawTarget = nextBetAfterLoss(currentBet);
            }

            ClampResult clamped = applyClampAlignReset(rawTarget);
            currentBet = clamped.bet();
            if (clamped.capHit()) {
                onCapHitReset();
            }
            log.trace("{}.onRoundEnd: delta={}, prevBet={}, nextBet={} (capHit={})",
                    getClass().getSimpleName(), result.balanceDelta(), prevBet,
                    currentBet, clamped.capHit());
        }
    }

    // ------------------------------------------------------------------
    // Progression hooks (called inside the synchronized block of onRoundEnd).
    //
    // Implementations return the new raw bet target — clamp / align / reset
    // is applied by the base class so the per-progression code stays focused
    // on its own arithmetic.
    // ------------------------------------------------------------------

    /**
     * @param currentBet the bet amount the strategy just placed.
     * @return raw bet target after a winning round; the base class applies the
     *         clamp / align / reset rules before storing.
     */
    protected abstract long nextBetAfterWin(long currentBet);

    /**
     * @param currentBet the bet amount the strategy just placed.
     * @return raw bet target after a losing round (or push — see A5e); the base
     *         class applies the clamp / align / reset rules before storing.
     */
    protected abstract long nextBetAfterLoss(long currentBet);

    /**
     * @param currentBet the bet amount the strategy did <i>not</i> place
     *                   (no-bet round).
     * @return raw bet target after a no-bet round. Default: unchanged — most
     *         progressions only update on a real outcome. Override only if the
     *         progression has a reason to advance on idle ticks.
     */
    protected long nextBetAfterNoBet(long currentBet) {
        return currentBet;
    }

    /**
     * Hook fired after the clamp/align/reset path resets {@code currentBet} to
     * {@code minBet} because the progression would otherwise exceed
     * {@code maxBet}. Concrete progressions override to clear progression-
     * specific state (e.g. Paroli's {@code consecutiveWins}, Fibonacci's
     * {@code fibIndex}).
     *
     * <p>Called inside the {@code synchronized (this)} block of
     * {@link #onRoundEnd(RoundResult)} — implementations must not synchronize
     * again.
     */
    protected void onCapHitReset() {
        // Default: no progression state to clear.
    }

    /**
     * Applies the clamp / align / reset rules from Architecture Decision A5f
     * to {@code rawTarget} and returns both the resulting bet amount and a
     * {@code capHit} flag (true iff the aligned target exceeded {@code maxBet}
     * and the strategy bankrupted its progression).
     *
     * <p>Order:
     * <ol>
     *   <li>Align down to the nearest {@code minBet + k*betIncrement} step.
     *       Negative or sub-{@code minBet} raw targets are floored to
     *       {@code minBet} before alignment.</li>
     *   <li>Floor at {@code minBet}.</li>
     *   <li>Cap at {@code maxBet}: if the aligned target exceeds {@code maxBet},
     *       reset to {@code minBet} and flag as a cap-hit.</li>
     * </ol>
     */
    protected final ClampResult applyClampAlignReset(long rawTarget) {
        long aligned;
        if (rawTarget <= cachedMinBet) {
            aligned = cachedMinBet;
        } else if (cachedBetIncrement <= 0L) {
            // Defensive: a degenerate betIncrement of 0 (or negative) would
            // divide-by-zero below. Floor to minBet so the strategy keeps
            // betting at the minimum and surfaces the misconfiguration as a
            // visible "no progression" symptom.
            aligned = cachedMinBet;
        } else {
            long steps = (rawTarget - cachedMinBet) / cachedBetIncrement;
            aligned = cachedMinBet + steps * cachedBetIncrement;
        }

        if (aligned > cachedMaxBet) {
            log.trace("{}: cap hit, target={}, maxBet={}, resetting to minBet={}",
                    getClass().getSimpleName(), rawTarget, cachedMaxBet, cachedMinBet);
            return new ClampResult(cachedMinBet, true);
        }
        return new ClampResult(aligned, false);
    }

    protected final long cachedMinBet() {
        return cachedMinBet;
    }

    protected final long cachedMaxBet() {
        return cachedMaxBet;
    }

    protected final long cachedBetIncrement() {
        return cachedBetIncrement;
    }

    /**
     * Result of {@link #applyClampAlignReset(long)} — the post-clamp bet amount
     * plus a flag indicating whether the maxBet cap was hit (and therefore the
     * progression bankrupted itself).
     */
    protected record ClampResult(long bet, boolean capHit) {
    }
}
