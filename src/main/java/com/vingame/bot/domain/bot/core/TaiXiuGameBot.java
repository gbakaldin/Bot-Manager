package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.TaiXiuMessageTypes;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import com.vingame.bot.domain.bot.message.request.GameRequest;
import com.vingame.bot.domain.bot.message.request.TaiXiuRequest;
import com.vingame.bot.domain.game.model.Game;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Tai Xiu bot (TAI_XIU_BOT plan AD-1). Tai Xiu is round-based with
 * <b>behaviorally identical</b> phase/countdown/watchdog/accounting semantics to
 * {@link BettingMiniGameBot}, so this class extends it and reuses every inherited
 * handler ({@code onSubscribe}/{@code onStartGame}/{@code onUpdate}/{@code onEndGame},
 * the pendingDecision park/pop, strategy wiring, watchdog, reconnect, cleanup).
 * <p>
 * The only thing genuinely different is the <b>message layer</b>: Tai Xiu's CMDs are
 * <b>fixed literal constants</b> (single game instance, no per-env OFFSET — AD-3), and
 * the bet carries Tài/Xỉu {@code eid}/{@code aid} via a dedicated {@link TaiXiuRequest}
 * (AD-12). This bot therefore overrides only:
 * <ul>
 *   <li>the CMD seams ({@link #subscribeCmd()}/{@link #startGameCmd()}/
 *       {@link #endGameCmd()}/{@link #updateBetCmd()}) → fixed Tai Xiu constants;</li>
 *   <li>{@link #messageTypeRegistrations()} → the no-offset Tai Xiu provider;</li>
 *   <li>the concrete-class accessors ({@code subscribeType}/{@code startGameType}/
 *       {@code startGameMd5Type}/{@code updateBetType}/{@code endGameType}) →
 *       delegate to {@link #taiXiuMessageTypes};</li>
 *   <li>{@link #buildRequest(Game)} → a {@link TaiXiuRequest} (bare fixed CMDs);</li>
 *   <li>{@link #endGameSessionId(EndGameMessage)} → the tracked session (the captured
 *       Tai Xiu EndGame has no {@code sid}, #3);</li>
 *   <li>{@link #balanceCreditFor(EndGameMessage, long)} → the refund-aware net credit
 *       {@code gR + winnings} (AD-11).</li>
 * </ul>
 * Per AD-9 this bot <b>never reads {@code game.getOffset()}</b>: every CMD seam returns
 * a fixed constant, so the inherited {@code offset} field (assigned in
 * {@code initializeSubclass}) is dead for Tai Xiu.
 */
@Slf4j
public class TaiXiuGameBot extends BettingMiniGameBot {

    /**
     * Fixed-CMD, per-product message provider for Tai Xiu. Wired by
     * {@code BotFactory} (Phase 6 — {@code GameMessageTypesResolver.resolveTaiXiu}).
     * Replaces the inherited betting {@code messageTypes} field, which is left null
     * for Tai Xiu (all accessor + registration seams are overridden to use this one).
     */
    @Setter
    private TaiXiuMessageTypes taiXiuMessageTypes;

    public TaiXiuGameBot() {
        super();
    }

    // ---- Fixed CMD seams (AD-3). No CODE + offset arithmetic. ----

    @Override
    protected int subscribeCmd() {
        return TaiXiuMessageTypes.SUBSCRIBE_CMD; // 1005
    }

    @Override
    protected int startGameCmd() {
        return TaiXiuMessageTypes.START_GAME_CMD; // 1002
    }

    @Override
    protected int endGameCmd() {
        return TaiXiuMessageTypes.END_GAME_CMD; // 1004
    }

    /**
     * @return a sentinel {@code -1}. No updateBet frame was captured (OI-5), so
     *         updateBet is omitted in v1: {@link #updateBetType()} returns null and the
     *         inherited scenario skips the updateBet handler entirely. This value is
     *         only fed to the OutputPrinter cmd list; it is never registered or matched.
     *         Overriding (rather than inheriting the {@code UPDATE_BET_CODE + offset}
     *         default) keeps the bot from reading {@code game.getOffset()} (AD-9).
     */
    @Override
    protected int updateBetCmd() {
        return -1;
    }

    // ---- No-offset registrations + per-product accessors (AD-3/AD-4). ----

    @Override
    protected NamedType[] messageTypeRegistrations() {
        return taiXiuMessageTypes.getTypeRegistrations();
    }

    @Override
    protected Class<? extends SubscribeMessage> subscribeType() {
        return taiXiuMessageTypes.subscribeType();
    }

    @Override
    protected Class<? extends StartGameMessage> startGameType() {
        return taiXiuMessageTypes.startGameType();
    }

    @Override
    protected Class<? extends StartGameMd5Message> startGameMd5Type() {
        return taiXiuMessageTypes.startGameMd5Type();
    }

    /**
     * @return {@code null} — updateBet is omitted in v1 (OI-5). The inherited scenario
     *         skips the handler when this is null.
     */
    @Override
    protected Class<? extends UpdateBetMessage> updateBetType() {
        return taiXiuMessageTypes.updateBetType();
    }

    @Override
    protected Class<? extends EndGameMessage> endGameType() {
        return taiXiuMessageTypes.endGameType();
    }

    // ---- Dedicated request (bare fixed CMDs + eid/aid), AD-12. ----

    @Override
    protected GameRequest buildRequest(Game game) {
        return new TaiXiuRequest(game.getPluginName(), configuration.getZoneName());
    }

    // ---- EndGame correlation + refund-aware balance (#3, AD-11). ----

    /**
     * The captured Tai Xiu EndGame frame carries no {@code sid} (#3), so correlate the
     * just-finished round to the session the bot tracked from StartGame / the subscribe
     * response ({@code sidStore}), NOT from the message body.
     */
    @Override
    protected long endGameSessionId(EndGameMessage msg) {
        return getSidStore().get();
    }

    /**
     * Refund-aware net balance credit at round end (AD-11). The full bet {@code b} was
     * debited at bet time ({@code creditBalance}); a Tai Xiu round's true balance effect
     * is {@code −b + gR + winnings}, so credit back the refund {@code gR} plus winnings.
     * The refund is read from the {@link com.vingame.bot.domain.bot.message.taixiu.TaiXiuEndGameMessage}
     * ({@code gR = gB − effectiveWagered}); {@code winnings} is the value the inherited
     * {@code onEndGame} already extracted via {@code HasBotWinnings}. The bet-amount
     * <i>metric</i> stays {@code gB − gR} (handled separately by {@code HasBetTotals}) —
     * balance credit and metric semantics are intentionally distinct.
     */
    @Override
    protected long balanceCreditFor(EndGameMessage msg, long winnings) {
        long refund = 0L;
        if (msg instanceof com.vingame.bot.domain.bot.message.taixiu.TaiXiuEndGameMessage tx) {
            refund = tx.getGR();
        }
        return refund + winnings;
    }
}
