package com.vingame.bot.domain.bot.message.g3.tip;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasJackpot;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class TipEndGameMessage extends EndGameMessage
        implements HasBotWinnings, HasJackpot, HasBetTotals {

    /**
     * This bot's gross winnings for the just-completed round. Source: root {@code wm}
     * field on the EndGame payload — confirmed in
     * {@code docs/plans/ROUND_DATA_COLLECTION_FINDINGS.md} as "win money of the user."
     * <p>
     * Tip's EndGame is delivered personalized to the recipient; {@code wm} is already
     * this bot's value, so the {@code userName} argument is ignored.
     */
    @Override
    public long winningsFor(String userName) {
        return wm;
    }

    /**
     * This bot's jackpot payout for the just-completed round; {@code 0} when no
     * jackpot was won ({@code iJp == false}).
     * <p>
     * <b>Field semantics (confirmed).</b> Tip carries two jackpot-shaped fields
     * on EndGame:
     * <ul>
     *   <li>{@code jpV} = {@code jackpotValue} — this player's earned jackpot in the
     *       current round. Non-zero only when {@code iJp=true}.</li>
     *   <li>{@code tJpV} = {@code totalJackpotValue} — the total winnable jackpot
     *       displayed in the game UI, distributed across all players according to
     *       their share of the bet placed on the jackpot-winning option.</li>
     * </ul>
     * {@code jpV} is therefore the correct field for the per-user payout that feeds
     * {@code bot_jackpots_total} / {@code bot_jackpot_amount_total}.
     * <p>
     * <b>No cross-product convention applies here.</b> The same field name
     * {@code tJpV} carries the <i>opposite</i> meaning in Bom and Nohu, whose
     * EndGame implementations both return {@code iJp ? tJpV : 0L} as the per-user
     * jackpot payout (see {@code BomEndGameMessage}, {@code NohuEndGameMessage}).
     * Do not rely on Bom/Nohu shape when adding a fourth product.
     */
    @Override
    public long jackpotFor(String userName) {
        return iJp ? jpV : 0L;
    }

    /**
     * Total amount this bot staked across all positions in the just-completed round.
     * <p>
     * Source: {@code sum(bs[].b)} — per
     * {@code docs/plans/ROUND_DATA_COLLECTION_FINDINGS.md}, {@code bs[i].b} is "this
     * user's bet on this option" (confirmed); the matching {@code bs[i].v} is the
     * round-wide aggregate across all players and is intentionally not summed here.
     * <p>
     * {@code userName} ignored — Tip's payload is recipient-personalized.
     */
    @Override
    public long betAmountFor(String userName) {
        if (bs == null) return 0L;
        long total = 0L;
        for (TipSubscribeMessage.BetInfoWithTotal info : bs) {
            total += info.getB();
        }
        return total;
    }

    /**
     * Number of bets this bot placed across all positions in the just-completed round.
     * Source: {@code sum(bs[].bc)} — per ROUND_DATA_COLLECTION_FINDINGS.md, {@code bc}
     * is the per-user bet count on each option (symmetric with the per-user amount
     * field {@code b}).
     */
    @Override
    public int betCountFor(String userName) {
        if (bs == null) return 0;
        int total = 0;
        for (TipSubscribeMessage.BetInfoWithTotal info : bs) {
            total += info.getBc();
        }
        return total;
    }

    @Override
    public long getSessionId() {
        return sid;
    }

    private boolean iJ;
    private int gid;
    private List<PlayerSummary> ps;
    private long tJpV;
    private EndGameInfo eIn;
    private int d1;
    private int d2;
    private int d3;
    private boolean iJp;
    private long sid;
    private List<TipSubscribeMessage.BetInfoWithTotal> bs;
    private long jpV;
    private long tJpv2;
    private int jPTp;
    private TipSubscribeMessage.JackpotCountdown jpCD;
    private long wm;
    private TipSubscribeMessage.SubscribeDice sDi;

    @JsonCreator
    public TipEndGameMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("iJ") boolean iJ,
            @JsonProperty("gid") int gid,
            @JsonProperty("ps") List<PlayerSummary> ps,
            @JsonProperty("tJpV") long tJpV,
            @JsonProperty("eIn") EndGameInfo eIn,
            @JsonProperty("d1") int d1,
            @JsonProperty("d2") int d2,
            @JsonProperty("d3") int d3,
            @JsonProperty("iJp") boolean iJp,
            @JsonProperty("sid") long sid,
            @JsonProperty("bs") List<TipSubscribeMessage.BetInfoWithTotal> bs,
            @JsonProperty("jpV") long jpV,
            @JsonProperty("tJpv2") long tJpv2,
            @JsonProperty("jPTp") int jPTp,
            @JsonProperty("jpCD") TipSubscribeMessage.JackpotCountdown jpCD,
            @JsonProperty("wm") long wm,
            @JsonProperty("sDi") TipSubscribeMessage.SubscribeDice sDi) {

        super(cmd);
        this.iJ = iJ;
        this.gid = gid;
        this.ps = ps;
        this.tJpV = tJpV;
        this.eIn = eIn;
        this.d1 = d1;
        this.d2 = d2;
        this.d3 = d3;
        this.iJp = iJp;
        this.sid = sid;
        this.bs = bs;
        this.jpV = jpV;
        this.tJpv2 = tJpv2;
        this.jPTp = jPTp;
        this.jpCD = jpCD;
        this.wm = wm;
        this.sDi = sDi;
    }

    @Getter
    @Setter
    public static class PlayerSummary {
        private String uid;
        private long wm;
        private long m;

        @JsonCreator
        public PlayerSummary(
                @JsonProperty("uid") String uid,
                @JsonProperty("wm") long wm,
                @JsonProperty("m") long m) {
            this.uid = uid;
            this.wm = wm;
            this.m = m;
        }
    }

    @Getter
    @Setter
    public static class EndGameInfo {
        private boolean iBp;

        @JsonCreator
        public EndGameInfo(@JsonProperty("iBp") boolean iBp) {
            this.iBp = iBp;
        }
    }
}
