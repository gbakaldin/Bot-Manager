package com.vingame.bot.domain.bot.message.g4.win79;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.coordination.CrowdOption;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.HasJackpot;
import com.vingame.bot.domain.bot.message.HasJackpotPool;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * WIN79 EndGame (CMD {@code 3006 + offset}; {@code 5006} at offset 2000).
 *
 * <h2>Field semantics — WIN79 follows the TIP convention, not Bom/Nohu</h2>
 *
 * This is the distinction {@code TipEndGameMessage}'s javadoc warns about, and
 * getting it backwards is silent rather than loud, so it is spelled out here.
 * Confirmed against a captured <em>winning</em> round (2026-09-10):
 * <ul>
 *   <li>{@code wm} = <b>this bot's gross win</b> for the round ({@code 5000} in the
 *       capture, matching the bot's own entry in {@code ps}). Feeds
 *       {@link HasBotWinnings} and therefore {@code bot_winnings_total} and RTP.</li>
 *   <li>{@code jpV} = <b>this player's</b> earned jackpot, non-zero only when
 *       {@code iJp} is true. Feeds {@link HasJackpot}.</li>
 *   <li>{@code tJpV} = the <b>pool meter</b> ({@code 210 140} in the capture), the
 *       total winnable jackpot shown in the UI. Feeds {@link HasJackpotPool}.</li>
 * </ul>
 * Bom and Nohu use {@code tJpV} for the <em>opposite</em> thing — both return
 * {@code iJp ? tJpV : 0L} as the per-user jackpot payout. <b>Do not copy their shape
 * here</b>, and do not assume a shared convention across products; there is none.
 *
 * <h2>Why {@link HasBotWinnings} matters on a new product</h2>
 *
 * Neither {@code BomEndGameMessage} nor {@code NohuEndGameMessage} implements it, so
 * P_097 and P_118 report {@code bot_winnings_total} as a flat zero forever and their
 * RTP is unverifiable — a real, already-diagnosed defect. WIN79 carries a per-bot
 * payout field, so it is wired from day one and 119 gets working payout/RTP metrics
 * on arrival.
 *
 * <h2>{@code ps} vs top-level {@code wm}</h2>
 *
 * {@code ps} keys players by server uid ({@code "18_1973"}), which is <b>not</b> the
 * username a bot knows itself by — so {@link #winningsFor(String)} deliberately
 * ignores its argument and reads the top-level {@code wm}, exactly as Tip does. The
 * frame is addressed to this connection, so the top-level value is already
 * this bot's. {@code ps} is modelled for observability only.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class Win79EndGameMessage extends EndGameMessage
        implements HasBotWinnings, HasJackpot, HasJackpotPool, HasCrowdBets {

    private long sid;

    /** The three Bau Cua dice faces. */
    private int d1;
    private int d2;
    private int d3;

    /** This bot's gross win for the round. */
    private long wm;

    /** This player's earned jackpot; meaningful only when {@link #iJp} is true. */
    private long jpV;

    /** Live running jackpot pool meter — NOT a per-user payout. */
    private long tJpV;
    private long tJpV2;

    /** Whether this player won a jackpot on this round. */
    private boolean iJp;

    /** Residual/refund amount reported by the server ({@code 0} in the capture). */
    private long rm;

    private List<Win79BetInfo> bs;
    private List<Win79PlayerResult> ps;

    @JsonCreator
    public Win79EndGameMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("sid") long sid,
            @JsonProperty("d1") int d1,
            @JsonProperty("d2") int d2,
            @JsonProperty("d3") int d3,
            @JsonProperty("wm") long wm,
            @JsonProperty("jpV") long jpV,
            @JsonProperty("tJpV") long tJpV,
            @JsonProperty("tJpV2") long tJpV2,
            @JsonProperty("iJp") boolean iJp,
            @JsonProperty("rm") long rm,
            @JsonProperty("bs") List<Win79BetInfo> bs,
            @JsonProperty("ps") List<Win79PlayerResult> ps) {
        super(cmd);
        this.sid = sid;
        this.d1 = d1;
        this.d2 = d2;
        this.d3 = d3;
        this.wm = wm;
        this.jpV = jpV;
        this.tJpV = tJpV;
        this.tJpV2 = tJpV2;
        this.iJp = iJp;
        this.rm = rm;
        this.bs = bs;
        this.ps = ps;
    }

    @Override
    public long getSessionId() {
        return sid;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Reads the top-level {@code wm}; {@code userName} is ignored because the frame
     * is already scoped to this connection. See the class javadoc.
     */
    @Override
    public long winningsFor(String userName) {
        return wm;
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@code jpV} guarded by {@code iJp} — the Tip shape. Emphatically <b>not</b>
     * {@code tJpV}, which is the pool meter on this product.
     */
    @Override
    public long jackpotFor(String userName) {
        return iJp ? jpV : 0L;
    }

    @Override
    public long jackpotPool() {
        return tJpV;
    }

    @Override
    public List<CrowdOption> crowdBets() {
        if (bs == null) {
            return List.of();
        }
        return bs.stream()
                .map(e -> new CrowdOption(e.eid(), e.v(), e.b(), e.bc()))
                .toList();
    }
}
