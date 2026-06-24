package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BetDecision;
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

import java.util.Map;
import java.util.Optional;

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
     * Entry id for <b>Tài</b> (over/high). Tai Xiu entry ids are <b>1-based</b>
     * (AD-13): Tài = {@code eid 1}, Xỉu = {@code eid 2}. This matches the captured
     * bet frame ({@code "eid":1}) and the default option set
     * {@code {1:1, 2:1}} ({@link Game#defaultTaiXiuOptionAffinities()}). No 0-based
     * assumption may leak into entry selection — a Tai Xiu bet must never emit
     * {@code eid 0}.
     */
    public static final int TAI_EID = 1;

    /**
     * Entry id for <b>Xỉu</b> (under/low) — see {@link #TAI_EID}. 1-based: {@code 2}.
     */
    public static final int XIU_EID = 2;

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

    /**
     * Default Tai Xiu to a 2-option game before the inherited init runs.
     * <p>
     * Tai Xiu always offers exactly the two Tài/Xỉu entries, so an operator never
     * configures options for it. A Tai Xiu game created with neither
     * {@code optionAffinities} nor legacy {@code numberOfOptions} would otherwise
     * make the inherited {@code initializeSubclass} (and every later
     * {@code ctx.game().getEffectiveOptionAffinities()} strategy read) throw
     * {@code IllegalStateException} — the staging failure this fixes. Applying
     * {@link Game#applyTaiXiuOptionDefaults()} here populates
     * {@code optionAffinities = {1:1, 2:1}} (eids 1 and 2 for Tài/Xỉu) so the game
     * resolves to 2 equal options.
     * <p>
     * This is the <b>only</b> place the default is applied — it is Tai-Xiu-scoped
     * and never affects {@link BettingMiniGameBot} (which still requires explicit
     * option config). The default is a no-op when any option field is already set,
     * so an explicit {@code numberOfOptions:2} (or any operator override) wins.
     */
    @Override
    protected void initializeSubclass() {
        configuration.getGame().applyTaiXiuOptionDefaults();
        super.initializeSubclass();
    }

    // ---- Single-entry-per-round lock (AD-13). ----

    /**
     * Enforce the Tai Xiu single-entry-per-round rule (AD-13): a bot may bet only
     * <b>one</b> side per round. The first bet of a round is unconstrained — the
     * strategy's chosen entry passes through. Once an entry (Tài or Xỉu) has been bet
     * this round, every later bet in the <i>same</i> round is remapped onto that
     * already-bet entry, so a strategy that flips to the other side can still
     * <i>increase</i> the locked side's stake but can never bet the opposite entry.
     *
     * <p>The lock is <b>derived from round memory</b>, not a separate field: the
     * already-bet entry is read from the in-flight {@code RoundState}'s
     * per-option bet map ({@link com.vingame.bot.domain.bot.strategy.BotMemory#snapshotCurrentRoundBets()}),
     * which {@code beginRound} clears on every {@code StartGame}/new sessionId. This
     * makes the lock reset automatically per round and survive the
     * park ({@code betCondition}) / re-derive ({@code bet()}) race — both call sites
     * read the same memory, so they stay consistent without extra synchronization.
     *
     * <p>Only the <i>entry</i> is constrained; the strategy's <i>amount</i> is always
     * preserved, so martingale-style increases on the locked side keep working.
     */
    @Override
    protected Optional<BetDecision> decideBet(BetContext ctx) {
        Optional<BetDecision> decision = super.decideBet(ctx);
        if (decision.isEmpty()) {
            return decision;
        }
        Integer lockedEntry = lockedEntryThisRound(ctx);
        if (lockedEntry == null) {
            // First bet of the round — strategy's entry choice passes through.
            return decision;
        }
        BetDecision chosen = decision.get();
        if (chosen.optionId() == lockedEntry) {
            return decision;
        }
        // Lock held: remap to the already-bet entry, keep the strategy's amount.
        log.debug("Bot {}: single-entry lock — remapping bet entry {} -> {} (amount={})",
                getUserName(), chosen.optionId(), lockedEntry, chosen.amount());
        return Optional.of(new BetDecision(lockedEntry, chosen.amount()));
    }

    /**
     * The entry this bot has already bet in the current round, or {@code null} if no
     * bet has been recorded yet this round (so the next bet is unconstrained).
     * Derived from the in-flight round's per-option bet map. By the single-entry
     * rule there is at most one key once a bet has been placed; if more than one is
     * ever present (defensive — should not happen under this lock), the first by
     * iteration order is treated as the locked entry.
     */
    private Integer lockedEntryThisRound(BetContext ctx) {
        Map<Integer, Long> betsThisRound = ctx.memory().snapshotCurrentRoundBets();
        if (betsThisRound.isEmpty()) {
            return null;
        }
        return betsThisRound.keySet().iterator().next();
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
     * is {@code −b + gR + G}, so credit back the refund {@code gR} plus winnings (the
     * {@code G} win-money field). The refund is read from the
     * {@link com.vingame.bot.domain.bot.message.taixiu.TaiXiuEndGameMessage}
     * ({@code gR}); {@code winnings} is the value the inherited {@code onEndGame} already
     * extracted via {@code HasBotWinnings} — which now returns {@code G} directly
     * (OI-7), so {@code refund + winnings == gR + G}. The bet-amount <i>metric</i> stays
     * {@code gB − gR} (handled separately by {@code HasBetTotals}) — balance credit and
     * metric semantics are intentionally distinct.
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
