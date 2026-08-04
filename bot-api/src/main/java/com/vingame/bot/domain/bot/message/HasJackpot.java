package com.vingame.bot.domain.bot.message;

/**
 * Marker for {@link EndGameMessage} subtypes exposing a single bot's jackpot
 * payout for the just-completed round.
 * <p>
 * Drives the Phase 4 per-bot counters {@code bot_jackpots_total} and
 * {@code bot_jackpot_amount_total}. Returns {@code 0} when the bot won no
 * jackpot — the {@code BettingMiniGameBot.onEndGame} dispatch guards on
 * {@code value > 0} before incrementing.
 * <p>
 * Separate from {@link HasBotWinnings} (Architecture Decision 2 in
 * {@code docs/plans/ENDGAME_METRICS.md}) because jackpot is structurally
 * distinct from regular payout — a game may expose one without the other.
 */
public interface HasJackpot {

    /**
     * @return this bot's jackpot value for the just-completed round; {@code 0}
     *         when no jackpot was won.
     */
    long jackpotFor(String userName);
}
