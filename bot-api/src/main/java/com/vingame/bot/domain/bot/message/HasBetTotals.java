package com.vingame.bot.domain.bot.message;

/**
 * Marker for {@link EndGameMessage} subtypes exposing the authoritative
 * server-side tally of how many bets a given user placed in the
 * just-completed round and the total amount staked.
 * <p>
 * Drives the per-bot counters {@code bot_bets_placed_total} and
 * {@code bot_bet_amount_total} via the batch overload
 * {@link com.vingame.bot.infrastructure.observability.BotMetrics#incBetsPlaced(int, long)}.
 * <p>
 * Once any concrete {@link EndGameMessage} subtype implements this interface,
 * those two counters reflect bets confirmed by the server's EndGame payload
 * rather than bets the bot sent — the local accumulators on {@code Bot}
 * (read by {@code BotHealthDTO}) still count bets sent, so the two values can
 * legitimately diverge when the server rejects bets. See
 * {@code docs/plans/ENDGAME_METRICS.md} AD-4 for the rationale.
 */
public interface HasBetTotals {

    /** @return total amount this user staked in the just-completed round. */
    long betAmountFor(String userName);

    /** @return number of bets this user placed in the just-completed round. */
    int betCountFor(String userName);
}
