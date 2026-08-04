package com.vingame.bot.domain.bot.strategy;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.domain.game.model.Game;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * v1 default strategy — pure RNG on every tick, no memory.
 *
 * <p>Reproduces the pre-strategy {@link com.vingame.bot.domain.bot.core.BettingMiniGameBot}
 * behavior bit-for-bit when fed the same {@link Random} seed and the same
 * {@link BotBehaviorConfig} / {@link Game}: identical RNG consumption order
 * (skip-check → bet-amount → option), identical per-round bet-count gate,
 * identical inclusive bounds on amount-step selection. The equivalence test in
 * {@code RandomBehaviorStrategyTest} pins this contract — Phase 5 reuses the
 * same harness against a deterministic StartGame/EndGame stream.
 *
 * <p>Option selection is uniform by default — a flat pick over
 * {@code game.getEffectiveOptionAffinities().keySet()} that throws the weight
 * values away. When the bot group opts in via {@code affinityWeightedProposal}
 * ({@link BetContext#affinityWeightedProposal()}) <b>and</b> the game's affinity
 * weights are not all equal, the option is instead drawn <i>proportional</i> to
 * those weights via {@link WeightedOptionPicker} — biasing proposals toward
 * high-affinity options (see {@code docs/plans/AFFINITY_AWARE_PROPOSAL.md} AD-3).
 * With the flag off, or on but weights equal (e.g. default TaiXiu {@code {1:1,2:1}}),
 * the pick short-circuits back to today's exact uniform {@code nextInt(n)} draw —
 * byte-for-byte identical RNG consumption, no behavior change.
 *
 * <p>Ignores rolling history: {@link BotMemory#snapshotLastResults()} and
 * {@link BotMemory#snapshotGlobalRecentWins()} are never read. Future
 * trend-followers will.
 *
 * <p><b>Statefulness.</b> Holds a per-round bet counter that resets on
 * {@link #onRoundEnd}, matching the legacy {@code numberOfBetsInCurrentSession}
 * field. The counter is the only mutable state; reads/writes happen on the
 * scenario thread ({@code decide}) and the netty processor thread
 * ({@code onRoundEnd}) — guarded by {@code synchronized} since the two threads
 * are distinct.
 *
 * <p><b>Spring scope.</b> Prototype-scoped so the registry produces a fresh
 * instance per bot. Constructed by {@link BettingStrategyFactory} via
 * reflection — the no-arg constructor is the entry point; the RNG comes from
 * {@link BetContext#rng()} on every {@code decide} call (the strategy never
 * holds its own RNG, the bot owns it).
 */
@Slf4j
@Component
@Scope("prototype")
@StrategyImpl(StrategyId.RANDOM)
public final class RandomBehaviorStrategy implements BettingStrategy {

    private int numberOfBetsInCurrentSession;
    private long currentRoundSessionId;

    // Stateless weighted-categorical picker for the opt-in affinity-weighted
    // path (AFFINITY_AWARE_PROPOSAL AD-3). Holds no RNG of its own — the per-tick
    // rng is threaded in via ctx.rng(). Mirrors how MartingaleStrategySupport
    // holds its AffinityOptionPicker.
    private final WeightedOptionPicker picker = new WeightedOptionPicker();

    public RandomBehaviorStrategy() {
        this.numberOfBetsInCurrentSession = 0;
        this.currentRoundSessionId = 0L;
    }

    @Override
    public Optional<BetDecision> decide(BetContext ctx) {
        BotBehaviorConfig behavior = ctx.behavior();

        // Detect a round boundary: the in-flight RoundState's sessionId is
        // stamped by BotMemory.beginRound on StartGame. When it changes since
        // the last decide() call, the per-round bet counter resets — mirrors
        // the legacy onStartGame numberOfBetsInCurrentSession = 0 line.
        long sid = ctx.currentRound().getSessionId();
        synchronized (this) {
            if (sid != currentRoundSessionId) {
                currentRoundSessionId = sid;
                numberOfBetsInCurrentSession = 0;
            }
            // JACKPOT_SCALE_AND_RAMP (AD-J4): read the jackpot-scaled per-round cap
            // from the context, not behavior.getMaxBetsPerRound() directly. With the
            // feature off (factor 1.0) it equals behavior.getMaxBetsPerRound() exactly.
            int maxBetsPerRound = ctx.effectiveMaxBetsPerRound();
            if (numberOfBetsInCurrentSession >= maxBetsPerRound) {
                log.trace("RandomBehaviorStrategy.decide: skip — already placed {} bets this round (max {})",
                        numberOfBetsInCurrentSession, maxBetsPerRound);
                return Optional.empty();
            }

            // Skip-percentage gate (mirrors legacy shouldBet — order of RNG
            // calls matters for the equivalence test in Phase 5).
            if (ctx.rng().nextInt(100) < behavior.getBetSkipPercentage()) {
                log.trace("RandomBehaviorStrategy.decide: skip — betSkipPercentage gate fired");
                return Optional.empty();
            }
            numberOfBetsInCurrentSession++;
        }

        // Bet-amount RNG: identical to legacy resolveBetAmount.
        long minBet = behavior.getMinBet();
        long maxBet = behavior.getMaxBet();
        long betStep = behavior.getBetIncrement();
        int maxSteps = Math.toIntExact((maxBet - minBet) / betStep);
        long steps = ctx.rng().nextInt(maxSteps + 1);
        long amount = minBet + (steps * betStep);

        // Option pick (AFFINITY_AWARE_PROPOSAL AD-3). Default/off = today's exact
        // uniform draw over the affinity-map keys; the weighted branch only runs
        // when the group opted in AND the weights are actually skewed. On the off
        // path AND the toggle-on-but-equal-weights path the RNG consumption is
        // byte-for-byte identical to today: exactly one nextInt(n) draw (the
        // weightsAreEqual short-circuit keeps the equal-weight case out of the
        // picker entirely, so no int-cast/edge risk and no extra draw).
        Map<Integer, Integer> affinities = ctx.game().getEffectiveOptionAffinities();
        int option;
        if (ctx.affinityWeightedProposal() && !WeightedOptionPicker.weightsAreEqual(affinities)) {
            // Weighted branch: one nextInt(Σw) draw, Σw ≠ n in general.
            option = picker.pick(affinities, ctx.rng());
        } else {
            // Off / equal-weight path — today's exact code, unchanged. List.copyOf
            // produces a deterministic insertion-order view matching the legacy
            // resolveNextEntryToBet call site. One nextInt(n) draw.
            List<Integer> options = List.copyOf(affinities.keySet());
            option = options.get(ctx.rng().nextInt(options.size()));
        }

        log.trace("RandomBehaviorStrategy.decide: bet option={}, amount={}", option, amount);
        return Optional.of(new BetDecision(option, amount));
    }

    @Override
    public void onRoundEnd(RoundResult result) {
        // No-op: RNG has no memory. Future strategies (Martingale, trend-follower)
        // mutate interpretive state here.
        log.trace("RandomBehaviorStrategy.onRoundEnd: sessionId={}, payout={}, delta={}",
                result.sessionId(), result.payout(), result.balanceDelta());
    }
}
