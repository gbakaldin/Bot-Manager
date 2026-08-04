package com.vingame.bot.domain.bot.message.slot;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Inbound slot spin result, {@code cmd:1302} (the spin request and spin result
 * share cmd 1302; inbound matching uses {@code typeOf(RECEIVED)} so the bot does
 * not match its own outbound echo).
 * <p>
 * Routed through the same marker-interface dispatch as the betting-mini
 * {@code onEndGame} (SLOT_MACHINE_BOT plan AD-7): winnings are <b>gross</b>
 * ({@code sum(wls[].crd)}) — the staked {@code b} was already debited on send,
 * so do not subtract it. Bet totals are a single spin: {@code betCountFor=1},
 * {@code betAmountFor=b}.
 */
@Getter
@Setter
public class SlotSpinResultMessage extends SlotMessage
        implements HasBotWinnings, HasBetTotals {

    /** Staked bet amount for this spin (per-line stake). */
    private long b;
    private int gid;
    /** Symbol board. */
    private List<Integer> sbs;
    private long sid;
    private boolean bw;
    /** Has free spins. */
    private boolean hFS;
    /** Free-spin count. */
    private int fss;
    private boolean iJ;
    private boolean hMG;
    private long J;
    private long mX;
    private boolean as;
    /** Winning lines for this spin. */
    private List<WinLine> wls;

    @JsonCreator
    public SlotSpinResultMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("b") long b,
            @JsonProperty("gid") int gid,
            @JsonProperty("sbs") List<Integer> sbs,
            @JsonProperty("sid") long sid,
            @JsonProperty("bw") boolean bw,
            @JsonProperty("hFS") boolean hFS,
            @JsonProperty("fss") int fss,
            @JsonProperty("iJ") boolean iJ,
            @JsonProperty("hMG") boolean hMG,
            @JsonProperty("J") long J,
            @JsonProperty("mX") long mX,
            @JsonProperty("as") boolean as,
            @JsonProperty("wls") List<WinLine> wls) {

        super(cmd);
        this.b = b;
        this.gid = gid;
        this.sbs = sbs;
        this.sid = sid;
        this.bw = bw;
        this.hFS = hFS;
        this.fss = fss;
        this.iJ = iJ;
        this.hMG = hMG;
        this.J = J;
        this.mX = mX;
        this.as = as;
        this.wls = wls;
    }

    /**
     * This bot's gross winnings for the spin = {@code sum(wls[].crd)} (AD-7).
     * The spin result is private to the requesting bot, so {@code userName} is
     * ignored.
     */
    @Override
    public long winningsFor(String userName) {
        if (wls == null) {
            return 0L;
        }
        long total = 0L;
        for (WinLine wl : wls) {
            total += wl.getCrd();
        }
        return total;
    }

    /**
     * Per-line staked amount {@code b} (AD-7). {@code userName} ignored.
     * <p>
     * NOTE: this is the <b>per-line</b> bet, not the total stake. The total
     * amount staked per spin is {@code b * numLines}, but {@code numLines} is
     * not carried on the result frame — it is server-sourced from the 1300
     * subscribe response and lives on the bot. {@code SlotMachineBot.onSpinResult}
     * multiplies this value by its known {@code numLines} so the
     * {@code bot_bet_amount_total} metric records the total stake (consistent
     * with the balance gate and the debit). Do not treat this return value as
     * the total stake.
     */
    @Override
    public long betAmountFor(String userName) {
        return b;
    }

    /** One spin = one bet (AD-7). {@code userName} ignored. */
    @Override
    public int betCountFor(String userName) {
        return 1;
    }

    @Getter
    @Setter
    public static class WinLine {
        /** Credit (winnings) for this line. */
        private long crd;
        private int lid;
        private List<Integer> sbIds;
        private String sbN;
        private int sbId;
        private boolean iJ;
        private boolean img;
        private boolean fs;

        @JsonCreator
        public WinLine(
                @JsonProperty("crd") long crd,
                @JsonProperty("lid") int lid,
                @JsonProperty("sbIds") List<Integer> sbIds,
                @JsonProperty("sbN") String sbN,
                @JsonProperty("sbId") int sbId,
                @JsonProperty("iJ") boolean iJ,
                @JsonProperty("img") boolean img,
                @JsonProperty("fs") boolean fs) {
            this.crd = crd;
            this.lid = lid;
            this.sbIds = sbIds;
            this.sbN = sbN;
            this.sbId = sbId;
            this.iJ = iJ;
            this.img = img;
            this.fs = fs;
        }
    }
}
