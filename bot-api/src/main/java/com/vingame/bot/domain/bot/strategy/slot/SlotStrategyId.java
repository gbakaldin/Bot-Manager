package com.vingame.bot.domain.bot.strategy.slot;

/**
 * Canonical identifier for a {@link SlotStrategy} implementation. Each concrete
 * strategy class is keyed by exactly one of these via the
 * {@link SlotStrategyImpl} annotation.
 *
 * <p>This is a <b>separate</b> family from the betting {@code StrategyId} (AD-9
 * of {@code docs/plans/SLOT_MACHINE_BOT.md}): slot strategies pick a bet
 * <em>amount</em> from a server-sourced allowed set and have no notion of
 * round, option, or memory. Keeping them in a distinct enum avoids mixing slot
 * and betting strategies in the group strategy-mix picker
 * ({@code StrategyController} lists only betting {@code StrategyId.values()}).
 *
 * <p>v1 ships {@link #FIXED} and {@link #RANDOM}. Both are stateless and pick
 * the amount from the {@code Js[].b} set on the {@code cmd:1300} subscribe
 * response. Renaming an enum entry breaks persisted configurations referencing
 * it via {@code BotConfiguration.slotStrategyId}; renaming a strategy class is
 * safe.
 */
public enum SlotStrategyId {
    /**
     * Always stakes the smallest (first) allowed bet value from the
     * server-sourced set. The v1 default when
     * {@code BotConfiguration.slotStrategyId} is null.
     */
    FIXED("Fixed", "Always stakes the smallest allowed bet value every spin."),

    /**
     * Uniform-random pick over the server-sourced allowed bet-value set on
     * every spin.
     */
    RANDOM("Random", "Picks a bet amount uniformly at random from the allowed set every spin.");

    private final String displayName;
    private final String description;

    SlotStrategyId(String displayName, String description) {
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
