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
    RANDOM("Random", "Pure RNG decisions on every tick. Ignores affinity weights and history.");

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
