package com.vingame.bot.domain.bot.strategy;

/**
 * A single bet a {@link BettingStrategy} has decided to place on this tick.
 * Returned by {@link BettingStrategy#decide(BetContext)} wrapped in an
 * {@link java.util.Optional} — present means "place this bet now," empty means
 * "skip this tick."
 *
 * <p>See {@code docs/plans/BETTING_STRATEGIES.md}, Architecture Decision 1.
 *
 * @param optionId option id to bet on; must be a key in
 *                 {@code Game.getEffectiveOptionAffinities()} for the bot's game.
 * @param amount   bet amount in account currency; must be within
 *                 {@code [behavior.minBet, behavior.maxBet]} and align with
 *                 {@code behavior.betIncrement}. Validation is the strategy's
 *                 responsibility — {@link BettingMiniGameBot} forwards the
 *                 decision to the WS request verbatim.
 */
public record BetDecision(int optionId, long amount) {
}
