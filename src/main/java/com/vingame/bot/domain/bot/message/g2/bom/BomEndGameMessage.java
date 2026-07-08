package com.vingame.bot.domain.bot.message.g2.bom;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.coordination.CrowdOption;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.HasJackpot;
import com.vingame.bot.domain.bot.message.HasJackpotPool;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BomEndGameMessage extends EndGameMessage implements HasJackpot, HasJackpotPool, HasCrowdBets {

    @Override
    public long jackpotFor(String userName) {
        return iJp ? tJpV : 0L;
    }

    /**
     * Live running jackpot pool meter ({@code tJpV}). DISTINCT from
     * {@link #jackpotFor(String)} — see {@link HasJackpotPool}.
     */
    @Override
    public long jackpotPool() {
        return tJpV;
    }

    @Override
    public long getSessionId() {
        return sid;
    }

    /**
     * Per-option crowd distribution from the EndGame {@code bs} array (AD-C1).
     * The {@code BetInfo} entry shape has no {@code b} field, so own-bet is 0
     * (the coordinator does not use it in v1 anyway — AD-C4). A missing {@code bs}
     * yields an empty list.
     */
    @Override
    public List<CrowdOption> crowdBets() {
        if (bs == null) {
            return List.of();
        }
        return bs.stream()
                .map(e -> new CrowdOption(e.getEid(), e.getV(), 0L, e.getBc()))
                .toList();
    }

    private int d1;
    private int d2;
    private int d3;

    private int[] sD;

    private long tJpv2;
    private long tJpV;

    private boolean iJp;

    private int jpT;

    private long sid;

    private LastJackpotData lJp;
    private List<BetInfo> bs;

    @JsonCreator
    public BomEndGameMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("d1") int d1,
            @JsonProperty("d2") int d2,
            @JsonProperty("d3") int d3,
            @JsonProperty("sD") int[] sD,
            @JsonProperty("tJpv2") long tJpv2,
            @JsonProperty("tJpV") long tJpV,
            @JsonProperty("iJp") boolean iJp,
            @JsonProperty("jpT") int jpT,
            @JsonProperty("sid") long sid,
            @JsonProperty("lJp") LastJackpotData lJp,
            @JsonProperty("bs") List<BetInfo> bs) {

        super(cmd);
        this.d1 = d1;
        this.d2 = d2;
        this.d3 = d3;
        this.sD = sD;
        this.tJpv2 = tJpv2;
        this.tJpV = tJpV;
        this.iJp = iJp;
        this.jpT = jpT;
        this.sid = sid;
        this.lJp = lJp;
        this.bs = bs;
    }

    @Setter
    @Getter
    public static class BetInfo {
        private int eid;
        private int bc;
        private long v;

        @JsonCreator
        public BetInfo(
                @JsonProperty("eid") int eid,
                @JsonProperty("bc") int bc,
                @JsonProperty("v") long v) {
            this.eid = eid;
            this.bc = bc;
            this.v = v;
        }
    }

    @Getter
    @Setter
    public static class LastJackpotData {
        private int d1;
        private long sid;

        @JsonCreator
        public LastJackpotData(
                @JsonProperty("d1") int d1,
                @JsonProperty("sid") long sid) {
            this.d1 = d1;
            this.sid = sid;
        }
    }
}
