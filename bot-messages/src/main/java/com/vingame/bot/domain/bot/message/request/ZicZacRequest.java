package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.Body;
import lombok.AllArgsConstructor;

/**
 * Outbound request helper for the P_114 / RIK {@code ziczacPlugin} (Plinko) game
 * (RIK_114_ZICZAC Amendment A1 item 2). Identical to {@link Request} except for the
 * one frame that differs: the bet body is a {@link ZicZacBet} ({@code b} + {@code c},
 * no {@code eid}) instead of the shared {@link Bet}.
 *
 * <h2>Why this is standalone rather than a subclass of {@link Request}</h2>
 *
 * The same two reasons {@link RikStockRequest} gives, and either alone is sufficient.
 * First, {@link Request} and {@link Bet} are <b>frozen</b>: six products bet through
 * them and settle, P_114's own {@code taixiuMd5Plugin} among them, so this phase must
 * not be able to move a byte of either. Second, it could not compile anyway —
 * {@code Request.bet} narrows the return type to the concrete {@link Bet} and
 * {@code RequestTest.overrideReturnsConcreteBet} pins that covariance, so an override
 * returning {@link ZicZacBet} would require {@code ZicZacBet extends Bet}, which would
 * drag {@code eid} back into the body.
 * <p>
 * {@link #subscribe()} is byte-identical to {@link Request#subscribe()} and to the
 * captured client frame ({@code {"cmd":12000}}), so nothing about subscribing changes
 * — and, per Amendment A1 §2, nothing <i>after</i> subscribing is required either: the
 * feed holds on subscribe alone and the {@code 12012} enter-room hypothesis is dead.
 * {@code chat} and {@code autoBet} are deliberately not carried over: neither is on the
 * {@link GameRequest} contract and neither has a production call site.
 *
 * <h2>No commit</h2>
 *
 * {@link #commit(long)} is <b>not</b> overridden: ziczac has no {@code 3022} frame —
 * the legacy fleet sends none for it and the capture shows none — so the
 * {@link GameRequest} default (empty) is inherited and the bot's {@code afterBetSent}
 * has nothing to send. This is the one place ziczac and stock differ on the outbound
 * side beyond the body itself.
 */
@AllArgsConstructor
public class ZicZacRequest implements GameRequest {

    private final String pluginName;
    private final String zoneName;
    private final int cmdPrefix;

    /** {@inheritDoc} — the same bare {@code {"cmd":offset + 3000}} the real client sends. */
    @Override
    public SubscribeToLobbyMessage subscribe() {
        return new SubscribeToLobbyMessage(zoneName, pluginName, new Body(cmdPrefix + 3000));
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@code amount} is the per-ball stake {@code b}, and the ball count is pinned to
     * {@code 1} (AD-10). <b>{@code entryId} is discarded</b> (AD-11): the strategy
     * keeps proposing an option from the game's degenerate {@code optionAffinities}
     * and this game has nowhere to put it — Plinko has nothing to pick. The narrowed
     * return type is documentation, not a contract anyone depends on.
     */
    @Override
    public ZicZacBet bet(long amount, int entryId, long sid) {
        return new ZicZacBet(cmdPrefix + 3002, zoneName, pluginName, amount, sid);
    }
}
