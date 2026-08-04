package com.vingame.bot.domain.bot.strategy.slot;

/**
 * Pluggable per-bot bet-amount selection for slot spins.
 *
 * <p>A slot strategy only picks the bet <em>amount</em> from the server-sourced
 * allowed set ({@link SlotBetContext#allowedBetValues()}). It does not decide
 * <em>whether</em> to spin — cadence and the one-spin-in-flight / balance gates
 * are the bot's job (AD-6/AD-13). There is therefore no skip return and no
 * result hook in v1 (both v1 strategies are stateless).
 *
 * <p>Parallel to the betting {@code BettingStrategy} but deliberately minimal —
 * no round, option, or memory concepts (AD-9 of
 * {@code docs/plans/SLOT_MACHINE_BOT.md}).
 *
 * <p>Thread model: {@link #chooseBet(SlotBetContext)} is invoked on the
 * scenario-owned pool thread (the {@code sendAsync} condition/supplier).
 */
public interface SlotStrategy {

    /**
     * Pick the bet amount for the next spin.
     *
     * @param ctx per-spin context carrying the server-sourced allowed bet
     *            values, winline count, current balance and the bot's RNG.
     * @return the chosen bet amount; MUST be one of
     *         {@link SlotBetContext#allowedBetValues()}.
     */
    long chooseBet(SlotBetContext ctx);
}
