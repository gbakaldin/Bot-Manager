package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;

/**
 * Builder for the three outbound frames of a CASHOUT game
 * ({@code docs/plans/CASHOUT_BOT.md} AD-4 / AD-10). One instance per bot, built by
 * {@link com.vingame.bot.domain.bot.message.CashoutMessageTypes#newRequest} with the
 * resolved zone, the game's plugin name and its offset already bound, so the bot
 * never does CMD arithmetic itself.
 * <p>
 * Declared in bot-api (and implemented per product in bot-messages) because
 * {@code CashoutMessageTypes.newRequest} returns it; return types are the ws-parser
 * {@link ActionRequestMessage} so this module needs nothing from bot-messages.
 */
public interface CashoutRequest {

    /** @return the subscribe frame, {@code cmd = offset + SUBSCRIBE_CODE}. */
    ActionRequestMessage subscribe();

    /**
     * @param amount the stake
     * @return the bet frame, {@code cmd = offset + BET_CODE}. Carries <b>no</b>
     *         {@code sid}: the server assigns one and reports it on the first
     *         progress frame (AD-8 binding).
     */
    ActionRequestMessage bet(long amount);

    /**
     * @param sid the server's id for the live bet, as bound from its frames
     * @return the cash-out frame, {@code cmd = offset + CASHOUT_CODE}
     */
    ActionRequestMessage cashOut(long sid);
}
