package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;

/**
 * Common outbound-request contract shared by the round-based game bots
 * ({@code BettingMiniGameBot} and its Tai Xiu subclass). It exposes only the two
 * outbound frames the inherited scenario builds — {@link #subscribe()} and
 * {@link #bet(long, int, long)} — so the bot's {@code buildRequest()} seam can
 * return either the betting-mini {@link Request} (CMD = {@code cmdPrefix + CODE})
 * or the Tai Xiu {@link TaiXiuRequest} (bare fixed CMDs, AD-12) without the
 * scenario knowing which.
 * <p>
 * {@link #bet(long, int, long)} returns the common {@link ActionRequestMessage}
 * supertype rather than the shared {@link Bet} concrete type so a product can return a
 * product-specific bet body (TAI_XIU_114_JACKPOT plan AD-2: P_114 returns a
 * {@link TaiXiuBet} carrying the extra {@code a} field, while P_116 and every
 * betting-mini product keep returning the shared {@link Bet}).
 */
public interface GameRequest {

    /** Build the outbound subscribe frame. */
    SubscribeToLobbyMessage subscribe();

    /**
     * Build the outbound bet frame.
     *
     * @param amount  the stake ({@code b})
     * @param entryId the chosen entry id ({@code eid})
     * @param sid     the currently-tracked session id
     * @return the bet frame ({@link Bet} for the shared shape, or a product-specific
     *         body such as {@link TaiXiuBet})
     */
    ActionRequestMessage bet(long amount, int entryId, long sid);
}
