package com.vingame.bot.domain.bot.message;

/**
 * Marker for {@link EndGameMessage} subtypes exposing a single bot's gross
 * payout for the just-completed round.
 * <p>
 * Drives the Phase 4 per-bot counter {@code bot_winnings_total}.
 * <p>
 * Dispatched from {@link com.vingame.bot.domain.bot.core.BettingMiniGameBot}'s
 * {@code onEndGame} via an {@code instanceof} branch; the bot supplies its own
 * {@code userName} (see {@code docs/plans/ENDGAME_METRICS.md} AD-2 / AD-3).
 * <p>
 * Identifier contract: implementations receive the bot's {@code userName}
 * (the same identifier as {@link com.vingame.bot.common.logging.BotMdc#BOT_USER_NAME}).
 * A bot whose {@code userName} is not present in the payload is indistinguishable
 * from a bot that placed bets and won nothing — both legitimate {@code 0} cases.
 */
public interface HasBotWinnings {

    /**
     * @return this bot's gross winnings for the just-completed round; {@code 0}
     *         when the bot won nothing or is absent from the payload.
     */
    long winningsFor(String userName);
}
