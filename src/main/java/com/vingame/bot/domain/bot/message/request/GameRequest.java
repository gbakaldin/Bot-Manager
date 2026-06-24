package com.vingame.bot.domain.bot.message.request;

/**
 * Common outbound-request contract shared by the round-based game bots
 * ({@code BettingMiniGameBot} and its Tai Xiu subclass). It exposes only the two
 * outbound frames the inherited scenario builds — {@link #subscribe()} and
 * {@link #bet(long, int, long)} — so the bot's {@code buildRequest()} seam can
 * return either the betting-mini {@link Request} (CMD = {@code cmdPrefix + CODE})
 * or the Tai Xiu {@link TaiXiuRequest} (bare fixed CMDs, AD-12) without the
 * scenario knowing which.
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
     */
    Bet bet(long amount, int entryId, long sid);
}
