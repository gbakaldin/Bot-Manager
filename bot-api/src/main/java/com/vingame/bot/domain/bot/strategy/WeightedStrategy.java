package com.vingame.bot.domain.bot.strategy;

/**
 * Single entry in a {@code BotGroup.strategyMix} — pairs a {@link StrategyId}
 * with its weight in the fill-to-target assignment. Weights are normalized
 * across the mix at assignment time, so a list of
 * {@code [(A,0.3),(B,0.5),(C,0.2)]} and {@code [(A,30),(B,50),(C,20)]} produce
 * identical bot-to-strategy splits.
 *
 * <p>See {@code docs/plans/BETTING_STRATEGIES.md}, Architecture Decisions 7, 8.
 *
 * @param strategyId enum-keyed strategy identifier; must resolve in
 *                   {@link BettingStrategyFactory}.
 * @param weight     relative weight in the mix. {@code <= 0} weights are
 *                   rejected by the assignment routine in Phase 4.
 */
public record WeightedStrategy(StrategyId strategyId, double weight) {
}
