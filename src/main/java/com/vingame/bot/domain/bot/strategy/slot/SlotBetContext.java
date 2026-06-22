package com.vingame.bot.domain.bot.strategy.slot;

import java.util.List;
import java.util.Random;

/**
 * Per-spin read-only handle a {@link SlotStrategy} consumes inside
 * {@link SlotStrategy#chooseBet(SlotBetContext)}.
 *
 * <p>Constructed fresh by {@code SlotMachineBot} on every spin tick; strategies
 * must NOT cache it across calls. Unlike the betting {@code BetContext} there is
 * no round, option, or memory — slots are request/response per spin (AD-9 of
 * {@code docs/plans/SLOT_MACHINE_BOT.md}).
 *
 * @param allowedBetValues server-sourced allowed bet-value set ({@code Js[].b}
 *                         from the {@code cmd:1300} subscribe response, sorted
 *                         ascending), captured by the bot in {@code onSubscribe}.
 *                         Never null/empty when the strategy is invoked — the
 *                         bot gates the first spin on having received the 1300
 *                         response (AD-12).
 * @param numLines         server-sourced winline count ({@code ls.size()} from
 *                         the 1300 response). Also captured in
 *                         {@code onSubscribe}; informational for the strategy
 *                         (the per-spin cost gate {@code chosenBet * numLines}
 *                         is the bot's job, AD-13).
 * @param currentBalance   the bot's expected current balance at this tick.
 *                         Useful for bankroll-aware strategies; the v1
 *                         strategies ignore it.
 * @param rng              per-bot {@link Random} owned by the bot. Passed in so
 *                         the strategy never holds its own RNG.
 */
public record SlotBetContext(
        List<Long> allowedBetValues,
        int numLines,
        long currentBalance,
        Random rng) {
}
