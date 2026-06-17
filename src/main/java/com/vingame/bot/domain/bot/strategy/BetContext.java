package com.vingame.bot.domain.bot.strategy;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.domain.game.model.Game;

import java.util.Random;

/**
 * Per-tick read-only handle a {@link BettingStrategy} consumes inside
 * {@link BettingStrategy#decide(BetContext)}.
 *
 * <p>The context is constructed fresh on every tick by
 * {@link com.vingame.bot.domain.bot.core.BettingMiniGameBot}; strategies must
 * NOT cache it across calls (the in-flight {@link RoundState}, balance, and
 * memory snapshots will all be stale by the next tick). Per-bot interpretive
 * state (streaks, internal counters, ...) lives on the strategy instance
 * itself.
 *
 * <p>See {@code docs/plans/BETTING_STRATEGIES.md}, Architecture Decision 2 (state
 * split) and Architecture Decision 11 (RandomBehaviorStrategy reference).
 *
 * @param memory          factual rolling history (bounded last-N round results,
 *                        global recent wins). Strategies read via
 *                        {@code memory.snapshotLastResults()} /
 *                        {@code snapshotGlobalRecentWins()} for thread-safety.
 * @param behavior        immutable behavior bounds (minBet/maxBet/betIncrement,
 *                        maxBetsPerRound, betSkipPercentage, ...).
 * @param game            game configuration; primary read is
 *                        {@code game.getEffectiveOptionAffinities()}.
 * @param currentBalance  the bot's expected current balance at this tick
 *                        (mirrors {@code Bot.expectedCurrentBalance}). Useful
 *                        for bankroll-aware strategies; the v1 Random strategy
 *                        ignores it.
 * @param currentRound    in-flight round state (sessionId, bets placed so far).
 *                        Reads are not snapshotted — callers that need
 *                        thread-safe per-option bet totals should call
 *                        {@code memory.snapshotCurrentRoundBets()}.
 * @param rng             per-bot {@link Random} owned by the strategy instance.
 *                        Passed in the context so the strategy never needs to
 *                        thread its own RNG through {@code decide(...)}.
 */
public record BetContext(
        BotMemory memory,
        BotBehaviorConfig behavior,
        Game game,
        long currentBalance,
        RoundState currentRound,
        Random rng) {
}
