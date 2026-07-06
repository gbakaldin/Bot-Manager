package com.vingame.bot.domain.bot.strategy;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.domain.game.model.Game;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.List;
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
 * <p>Ignores affinity values: option selection is uniform over
 * {@code game.optionAffinities.keySet()}. Affinity-aware strategies are a
 * future-strategy concern (see {@code docs/plans/BETTING_STRATEGIES.md},
 * Architecture Decision 5).
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
            if (numberOfBetsInCurrentSession >= behavior.getMaxBetsPerRound()) {
                log.trace("RandomBehaviorStrategy.decide: skip — already placed {} bets this round (max {})",
                        numberOfBetsInCurrentSession, behavior.getMaxBetsPerRound());
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

        // Option pick: uniform over the affinity-map keys (affinity values
        // ignored in v1). List.copyOf produces a deterministic insertion-order
        // view matching the legacy resolveNextEntryToBet call site.
        List<Integer> options = List.copyOf(ctx.game().getEffectiveOptionAffinities().keySet());
        int option = options.get(ctx.rng().nextInt(options.size()));

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
