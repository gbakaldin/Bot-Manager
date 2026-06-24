package com.vingame.bot.domain.bot.message.taixiu;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasJackpot;
import lombok.Getter;
import lombok.Setter;

/**
 * Tai Xiu EndGame for the captured {@code MiniGame}/{@code taixiuPlugin} product
 * (cmd {@code 1004}, AD-3) — the central refund-aware accounting class (AD-11).
 * <p>
 * <b>Captured payload (source of truth: {@code TaiXiuMessages/End.js}).</b> Dice
 * {@code d1}/{@code d2}/{@code d3} (1+6+6=13 → Tai) plus the refund-aware accounting
 * fields. The {@code c} array is dice-animation vectors and is intentionally NOT
 * modeled (ignored for accounting). Field semantics (gold currency = {@code g*},
 * coin currency = {@code c*}/{@code C*}; some inferred — confirm via OI-7):
 * <ul>
 *   <li>{@code gB} — gold game <b>bet</b> total (sample 500000)</li>
 *   <li>{@code gR} — gold <b>refund</b> (sample 500000)</li>
 *   <li>{@code G} — gold <b>win money</b> (winnings — direct field, sample 0)</li>
 *   <li>{@code GX} — gold <b>exchange</b>/gross settlement returned = {@code gR + G}
 *       (sample 500000); modeled but no longer used for winnings (OI-7)</li>
 *   <li>{@code cB}/{@code cBB}/{@code cR}/{@code CX} — coin-currency counterparts
 *       (all 0 in the capture)</li>
 *   <li>{@code iJp}/{@code jpV} — jackpot flag + value (both falsy/0 in the capture)</li>
 * </ul>
 * <p>
 * <b>Refund-aware formulas (AD-11; OI-7 RESOLVED 2026-06-24 — use the {@code G} field
 * directly, do NOT compute {@code GX − gB}):</b>
 * <ul>
 *   <li><b>effective wagered</b> = {@code gB − gR} — what actually rode on the outcome.
 *       A fully-refunded round contributes {@code 0} (capture: {@code 500000 − 500000 = 0}).</li>
 *   <li><b>winnings</b> = {@code G} — the gold win-money field directly.
 *       Capture: {@code 0}. ({@code GX − gB} was the earlier guess; it only
 *       coincidentally equals {@code G} in the fully-refunded sample because
 *       {@code GX = gR + G} and there {@code gR == gB}.)</li>
 * </ul>
 * Tai Xiu is a balanced 2-entry game: the server refunds the Tai/Xiu stake imbalance
 * to the latest bettors at round end, so a bet can be partially or fully refunded
 * independently of win/loss. Treating {@code gB} naïvely as the stake would make a
 * fully-refunded round look like a total loss — hence {@link HasBetTotals} reports the
 * <i>effective</i> wagered ({@code gB − gR}), not gross {@code gB}.
 * <p>
 * The marker accessors ignore {@code userName}: this product's EndGame is delivered
 * personalized to the recipient bot (the same convention as {@code TipEndGameMessage}),
 * so {@code gB}/{@code gR}/{@code GX} are already this bot's values.
 * <p>
 * <b>No {@code sid} (#3).</b> The captured {@code End.js} carries <b>no session id</b>.
 * Tai Xiu correlates an EndGame to the round it tracked from StartGame/subscribe
 * ({@code sidStore}) inside {@code TaiXiuGameBot.endGameSessionId()}, NOT from this
 * payload. {@link #getSessionId()} therefore returns {@code 0} and is never used as the
 * correlation key for Tai Xiu (the inherited {@code onEndGame} reads the session id
 * through the {@code endGameSessionId} seam, which the bot overrides).
 */
@Getter
@Setter
public class TaiXiuEndGameMessage extends EndGameMessage
        implements HasBotWinnings, HasJackpot, HasBetTotals {

    // Dice (1..6 each); sum determines Tai (>=11) vs Xiu (<=10).
    private int d1;
    private int d2;
    private int d3;

    // Gold-currency accounting.
    private long gB;   // gold bet total
    private long gR;   // gold refund
    private long G;    // gold win money (winnings — direct field; OI-7)
    private long GX;   // gold exchange / gross settlement returned (= gR + G; modeled, unused for winnings)

    // Coin-currency counterparts (0 in the capture; modeled for completeness).
    private long cB;
    private long cBB;
    private long cR;
    private long CX;

    // Jackpot.
    private boolean iJp;
    private long jpV;

    @JsonCreator
    public TaiXiuEndGameMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("d1") int d1,
            @JsonProperty("d2") int d2,
            @JsonProperty("d3") int d3,
            @JsonProperty("gB") long gB,
            @JsonProperty("gR") long gR,
            @JsonProperty("G") long G,
            @JsonProperty("GX") long GX,
            @JsonProperty("cB") long cB,
            @JsonProperty("cBB") long cBB,
            @JsonProperty("cR") long cR,
            @JsonProperty("CX") long CX,
            @JsonProperty("iJp") boolean iJp,
            @JsonProperty("jpV") long jpV) {
        super(cmd);
        this.d1 = d1;
        this.d2 = d2;
        this.d3 = d3;
        this.gB = gB;
        this.gR = gR;
        this.G = G;
        this.GX = GX;
        this.cB = cB;
        this.cBB = cBB;
        this.cR = cR;
        this.CX = CX;
        this.iJp = iJp;
        this.jpV = jpV;
    }

    /**
     * @return {@code 0} — the captured Tai Xiu EndGame frame carries no {@code sid}
     *         (#3). Correlation to the tracked round is done by the bot via
     *         {@code TaiXiuGameBot.endGameSessionId()} reading {@code sidStore}, not
     *         from this message, so this value is never used as the correlation key.
     */
    @Override
    public long getSessionId() {
        return 0L;
    }

    /**
     * This bot's winnings for the just-completed round = the {@code G} field directly
     * (gold win money), floored at {@code 0} (AD-11; OI-7 RESOLVED 2026-06-24). Do NOT
     * compute {@code GX − gB} — that only coincidentally matched {@code G} in the
     * fully-refunded capture (since {@code GX = gR + G} and {@code gR == gB} there).
     * {@code userName} ignored — the payload is recipient-personalized.
     */
    @Override
    public long winningsFor(String userName) {
        return Math.max(0L, G);
    }

    /**
     * Effective amount this bot wagered = {@code gB − gR} (gross bet minus refund),
     * floored at {@code 0} (AD-11). A fully-refunded round contributes {@code 0} to
     * {@code bot_bet_amount_total} so it neither inflates nor deflates RTP.
     * {@code userName} ignored — recipient-personalized payload.
     */
    @Override
    public long betAmountFor(String userName) {
        return Math.max(0L, gB - gR);
    }

    /**
     * Number of bets this bot placed in the round. The captured EndGame frame carries
     * no per-bet count, only aggregate amounts; report {@code 1} when any effective
     * stake remained after refund, else {@code 0}. The inherited
     * {@code bot_bets_placed_total} on {@code Bot} still counts bets the bot sent, so
     * the two can legitimately diverge (see {@link HasBetTotals}).
     */
    @Override
    public int betCountFor(String userName) {
        return (gB - gR) > 0L ? 1 : 0;
    }

    /**
     * Jackpot payout for the round; {@code 0} unless {@code iJp} is set. The capture
     * has {@code iJp=false}, {@code jpV=0}.
     */
    @Override
    public long jackpotFor(String userName) {
        return iJp ? jpV : 0L;
    }
}
