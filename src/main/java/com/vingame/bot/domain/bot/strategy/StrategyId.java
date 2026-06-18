package com.vingame.bot.domain.bot.strategy;

/**
 * Canonical identifier for a {@link BettingStrategy} implementation. Each
 * concrete strategy class is keyed by exactly one of these via the
 * {@link StrategyImpl} annotation, and {@link BotGroup#getStrategyMix() bot
 * groups} reference strategies by this enum (NOT by class name) — so renaming
 * a strategy class is safe but renaming an enum entry breaks persisted
 * configurations.
 *
 * <p>v1 ships {@link #RANDOM} only. Future strategies (Martingale, slow-build,
 * gambler, ...) are added by extending this enum and shipping a new
 * {@code @StrategyImpl}-annotated class. See
 * {@code docs/plans/BETTING_STRATEGIES.md}, Architecture Decision 7.
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
