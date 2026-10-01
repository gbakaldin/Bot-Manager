package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import lombok.AllArgsConstructor;

import java.util.Optional;

/**
 * Outbound request helper for the P_114 / RIK {@code stockPlugin} game
 * (RIK_114_BETTING_MINI AD-25). Identical to {@link Request} except for the two frames
 * that differ: the bet body is a {@link RikStockBet} ({@code v} + {@code iAc}, no
 * {@code b}) instead of the shared {@link Bet}, and every bet is followed by a
 * {@link RikStockCommit} ({@code {"cmd":offset+3022,"sId":sid}}, AD-27 amended /
 * AD-29) where {@link Request} sends nothing.
 *
 * <h2>Why this is standalone rather than a subclass of {@link Request}</h2>
 *
 * Two reasons, and either alone is sufficient. First, {@link Request} and {@link Bet}
 * are <b>frozen</b>: six products bet through them and settle, P_114's own
 * {@code taixiuMd5Plugin} among them, so this phase must not be able to move a byte of
 * either. Second, it could not compile anyway — {@code Request.bet} narrows the return
 * type to the concrete {@link Bet} and {@code RequestTest.overrideReturnsConcreteBet}
 * pins that covariance, so an override returning {@link RikStockBet} would require
 * {@code RikStockBet extends Bet}, which would drag {@code b} back into the body.
 * <p>
 * {@link #subscribe()} is byte-identical to {@link Request#subscribe()} and to the
 * captured client frame ({@code {"cmd":13000}}), so nothing about subscribing changes.
 * {@code chat} and {@code autoBet} are deliberately not carried over: neither is on the
 * {@link GameRequest} contract and neither has a production call site.
 */
@AllArgsConstructor
public class RikStockRequest implements GameRequest {

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
     * {@code amount} is emitted as {@code v}, not {@code b} (AD-22). The narrowed return
     * type is documentation, not a contract anyone depends on.
     */
    @Override
    public RikStockBet bet(long amount, int entryId, long sid) {
        return new RikStockBet(cmdPrefix + 3002, zoneName, pluginName, amount, entryId, sid);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Always present on stock: {@code {"cmd":offset+3022,"sId":sid}} (AD-29). This is
     * reached only through the AD-21 allowlist in {@code RikGameMessageTypes.requestFor}
     * — the sole way a bot holds a {@code RikStockRequest} — so the commit is scoped to
     * {@code stockPlugin} and nothing else without any further dispatch. The browser
     * sends one per round after its last bet; the legacy Node bot one after every bet;
     * we follow the legacy bot, because the bot never knows which bet is its last.
     */
    @Override
    public Optional<ActionRequestMessage> commit(long sid) {
        return Optional.of(new RikStockCommit(cmdPrefix + 3022, zoneName, pluginName, sid));
    }
}
