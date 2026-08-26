package com.vingame.bot.domain.bot.strategy;

/**
 * Single entry in a {@code BotGroup.strategyMix} — pairs a strategy registry key
 * with its weight in the fill-to-target assignment. Weights are normalized
 * across the mix at assignment time, so a list of
 * {@code [(A,0.3),(B,0.5),(C,0.2)]} and {@code [(A,30),(B,50),(C,20)]} produce
 * identical bot-to-strategy splits.
 *
 * <p><b>{@code strategyId} is a {@code String}, not a {@link StrategyId}</b>
 * (PLUGIN_HOT_RELOAD Phase 2b, AD-12/AD-14): a strategy served from a plugin
 * classloader cannot name an enum constant the engine declares. This record is
 * persisted inside {@code BotGroup.strategyMix}, and the change is
 * <b>read-compatible with every existing document</b> — Spring Data has no
 * custom converters in this application, so the BSON value was already the
 * enum's {@code name()} string and it round-trips into a {@code String} field
 * unchanged. {@code PersistedStrategyKeyCompatTest} pins that against a real
 * {@code MappingMongoConverter}. No migration script, no dual-read.
 *
 * <p>Going to a string also removes Jackson's implicit rejection of an unknown
 * key on the request body; {@code BotGroupConfigValidationService} replaces it
 * with an explicit 400 on both create and PATCH (AD-15).
 *
 * <p>See {@code docs/plans/BETTING_STRATEGIES.md}, Architecture Decisions 7, 8,
 * and {@code docs/plans/PLUGIN_HOT_RELOAD.md} AD-12/AD-14/AD-15.
 *
 * @param strategyId strategy registry key; must resolve in
 *                   {@link BettingStrategyFactory}. For a built-in this is
 *                   exactly a {@link StrategyId} constant name.
 * @param weight     relative weight in the mix. {@code <= 0} weights are
 *                   rejected by the assignment routine in Phase 4.
 */
public record WeightedStrategy(String strategyId, double weight) {
}
