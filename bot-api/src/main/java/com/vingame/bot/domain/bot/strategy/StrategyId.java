package com.vingame.bot.domain.bot.strategy;

/**
 * Catalogue of the <b>built-in</b> {@link BettingStrategy} implementations, and
 * the compile-time home of their display metadata. {@link BotGroup#getStrategyMix()
 * Bot groups} reference strategies by <em>key</em> (NOT by class name) — so
 * renaming a strategy class is safe but renaming an entry here breaks persisted
 * configurations.
 *
 * <p><b>This enum is no longer the registry key</b> (PLUGIN_HOT_RELOAD Phase 2a,
 * AD-12). The key is the constant <em>name</em> as a {@code String}:
 * {@code @StrategyImpl("RANDOM")} keys the registry, and no runtime code path
 * may switch on this enum or use it as a map key. It is retained because it is
 * the only structured record of the canonical key strings and their UI copy, and
 * because deleting it would turn the renaming warning above from a loud
 * compile-time fact into folklore. {@code StrategyCatalogParityTest} pins
 * every constant name here to a registered bean.
 *
 * <p>A new built-in is still added by extending this enum and shipping a new
 * {@code @StrategyImpl}-annotated class; a plugin-supplied strategy will
 * register a key that appears here not at all. See
 * {@code docs/plans/BETTING_STRATEGIES.md} Architecture Decision 7 and
 * {@code docs/plans/PLUGIN_HOT_RELOAD.md} AD-12.
 */
public enum StrategyId {
    /**
     * v1 default — pure-RNG decisions on every tick, mirrors the pre-strategy
     * {@code BettingMiniGameBot.shouldBet()} / {@code resolveBetAmount()} /
     * {@code resolveNextEntryToBet()} behavior bit-for-bit when fed an
     * identically-seeded {@link java.util.Random}. See
     * {@link RandomBehaviorStrategy} for the canonical implementation.
     */
    RANDOM("Random", "Pure RNG decisions on every tick. Ignores affinity weights and history."),

    MARTINGALE_CLASSIC_CAUTIOUS("Classic Martingale (Cautious)",
            "Doubles the bet after every loss and resets to the minimum after a win. "
            + "Picks the safer entries more often."),
    MARTINGALE_CLASSIC_AGGRESSIVE("Classic Martingale (Aggressive)",
            "Doubles the bet after every loss and resets to the minimum after a win. "
            + "Chases the riskier entries with bigger payouts."),
    PAROLI_CAUTIOUS("Paroli (Cautious)",
            "Doubles the bet after every win and resets after a loss or after a short winning streak. "
            + "Picks the safer entries more often."),
    PAROLI_AGGRESSIVE("Paroli (Aggressive)",
            "Doubles the bet after every win and resets after a loss or after a short winning streak. "
            + "Chases the riskier entries with bigger payouts."),
    DALEMBERT_CAUTIOUS("D'Alembert (Cautious)",
            "Raises the bet by one step after a loss and lowers it by one step after a win. "
            + "Picks the safer entries more often."),
    DALEMBERT_AGGRESSIVE("D'Alembert (Aggressive)",
            "Raises the bet by one step after a loss and lowers it by one step after a win. "
            + "Chases the riskier entries with bigger payouts."),
    FIBONACCI_CAUTIOUS("Fibonacci (Cautious)",
            "Follows the Fibonacci sequence — one step forward after a loss, two steps back after a win. "
            + "Picks the safer entries more often."),
    FIBONACCI_AGGRESSIVE("Fibonacci (Aggressive)",
            "Follows the Fibonacci sequence — one step forward after a loss, two steps back after a win. "
            + "Chases the riskier entries with bigger payouts.");

    private final String displayName;
    private final String description;

    StrategyId(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
