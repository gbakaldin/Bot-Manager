package com.vingame.bot.domain.bot.strategy;

import java.util.Optional;

/**
 * Pluggable per-bot decision logic for betting.
 *
 * <p>One instance per bot per restart — strategies are stateful (streaks,
 * internal counters, regret scores, ...). The factual rolling history lives on
 * {@link BotMemory}; the strategy owns only interpretive state. See
 * {@code docs/plans/BETTING_STRATEGIES.md}, Architecture Decisions 1, 2, 6.
 *
 * <p>Thread model: {@link #decide(BetContext)} is invoked on the scenario
 * thread (pool-N-thread-1); {@link #onRoundEnd(RoundResult)} is invoked on the
 * netty message-processor thread for this bot. The two threads are distinct.
 * Implementations holding mutable fields must synchronize them.
 */
public interface BettingStrategy {

    /**
     * Called once per EndGame after {@link BotMemory#completeRound} has
     * pushed the finalized {@link RoundResult} onto memory. Strategies use this
     * to update interpretive state (e.g. a Martingale strategy resets its loss
     * streak on a win).
     *
     * <p>{@link RandomBehaviorStrategy} is a no-op here — RNG has no memory.
     */
    void onRoundEnd(RoundResult result);

    /**
     * Called per scenario tick whenever the bot is in a state where a bet
     * could be placed ({@code canBet()} true: session live, BET phase, time
     * remaining).
     *
     * @return {@link Optional#empty()} to skip this tick; a present
     *         {@link BetDecision} to place that bet now.
     */
    Optional<BetDecision> decide(BetContext ctx);
}
