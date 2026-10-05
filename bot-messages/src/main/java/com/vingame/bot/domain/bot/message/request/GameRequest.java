package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;

import java.util.Optional;

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
 * <p>
 * {@link #commit(long)} is the one <b>optional</b> frame on the contract
 * (RIK_114_BETTING_MINI AD-28): a per-bet follow-up some games require after the bet
 * itself. It is a {@code default} returning {@link Optional#empty()}, so
 * {@link Request} and {@link TaiXiuRequest} inherit "no commit" without being edited
 * and only a product that overrides it ever builds the frame.
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

    /**
     * The per-bet <b>commit</b> frame, if this game requires one after the bet.
     * <p>
     * Some games do not treat a bet as placed until a second frame commits it to the
     * position. The one known case is P_114 / RIK {@code stockPlugin}, whose real client
     * and legacy Node bot both follow every {@code 13002} bet with
     * {@code {"cmd":13022,"sId":<sid>}} (RIK_114_BETTING_MINI AD-27 amended, AD-29);
     * without it the server accepts and echoes the bet in-round and then discards it at
     * EndGame.
     * <p>
     * The default is <b>none</b>: {@link Request} and {@link TaiXiuRequest} deliberately
     * inherit this and are not edited (AD-28) — six products settle through them with no
     * commit, and {@code RequestTest} / {@code TaiXiuRequestTest} pin the empty result
     * so a well-meaning "commit everywhere" override cannot land unnoticed. The method
     * lives on {@code GameRequest} rather than on {@link GameRequestFactory} because it
     * is a frame the request <i>builds</i>, like {@link #bet}, not a resolution the
     * provider makes.
     *
     * @param sid the currently-tracked session id — the same value the preceding bet
     *            carried
     * @return the commit frame to send immediately after the bet, or empty if the game
     *         has no such frame
     */
    default Optional<ActionRequestMessage> commit(long sid) {
        return Optional.empty();
    }

    /**
     * Whether the server binds a player to <b>one</b> entry per round. When true the
     * bot remaps every bet after the round's first onto the entry that first bet chose,
     * because the server rejects a bet on any other entry with {@code BETTING_INVALID}
     * ({@code entry != player.getEntryPosition()}) — one backend exception per bet.
     * <p>
     * The default is <b>false</b>: BettingMini games such as Bau Cua legitimately take
     * bets on several entries in one round, so only a product that overrides this is
     * locked. Tai Xiu has the same rule but enforces it in {@code TaiXiuGameBot}, which
     * predates this method.
     */
    default boolean singleEntryPerRound() {
        return false;
    }
}
