package com.vingame.bot.domain.bot.strategy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable, finalized record of a single completed round. Pushed onto
 * {@link BotMemory#getLastResults()} when {@code EndGame} arrives and the
 * in-flight {@link RoundState} can be matched by {@code sessionId}.
 *
 * <p>Authoritative data only — strategy-side interpretation (streaks,
 * counters, regret scoring, ...) lives on the strategy instance, not here.
 * See {@code docs/plans/BETTING_STRATEGIES.md}, Architecture Decision 2.
 *
 * @param sessionId      session id ({@code sid}) of the round.
 * @param winningOption  winning option id as exposed by the EndGame payload.
 *                       Best-effort for v1: {@link Optional#empty()} when no
 *                       {@code HasWinningOption} marker is implemented yet
 *                       (see Implementation Note 4).
 * @param betsByOption   bets the bot placed during this round, keyed by option id.
 *                       Empty map = bot skipped the round.
 * @param payout         gross payout for this bot (from {@code HasBotWinnings.winningsFor}).
 * @param balanceDelta   {@code payout - sum(betsByOption.values())} — net
 *                       change to the bot's balance from this round.
 * @param endedAt        instant the {@code EndGame} message was processed.
 */
public record RoundResult(
        long sessionId,
        Optional<Integer> winningOption,
        Map<Integer, Long> betsByOption,
        long payout,
        long balanceDelta,
        Instant endedAt) {
}
