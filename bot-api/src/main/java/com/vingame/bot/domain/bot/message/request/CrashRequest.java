package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;

/**
 * Builder for the three outbound frames of a CRASH game
 * ({@code docs/plans/AVIATOR_BOT.md} AD-5 / AD-10). One instance per bot, built by
 * {@link com.vingame.bot.domain.bot.message.CrashMessageTypes#newRequest} with the
 * resolved zone, the game's plugin name and its offset already bound, so the bot never
 * does CMD arithmetic itself.
 * <p>
 * Declared in bot-api (and implemented per product in bot-messages) because
 * {@code CrashMessageTypes.newRequest} returns it; return types are the ws-parser
 * {@link ActionRequestMessage} so this module needs nothing from bot-messages.
 * <p>
 * The {@code eid} (runner) is drawn once per round by the bot and passed to both
 * {@link #bet} and {@link #cashOut} (AD-2). A single-runner brand's implementation does
 * not serialise it.
 */
public interface CrashRequest {

    /** @return the subscribe frame, {@code cmd = offset + SUBSCRIBE_CODE}. */
    ActionRequestMessage subscribe();

    /**
     * @param amount the stake
     * @param sid    the round id from the round start
     * @param eid    the runner, {@code 1..runnerCount()}
     * @return the bet frame, {@code cmd = offset + BET_CODE}
     */
    ActionRequestMessage bet(long amount, long sid, int eid);

    /**
     * @param sid the round id the bet was placed on
     * @param eid the runner the bet was placed on
     * @return the cash-out frame, {@code cmd = offset + CASHOUT_CODE}
     */
    ActionRequestMessage cashOut(long sid, int eid);
}
