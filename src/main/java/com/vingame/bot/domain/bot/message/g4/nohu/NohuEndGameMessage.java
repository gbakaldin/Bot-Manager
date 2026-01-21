package com.vingame.bot.domain.bot.message.g4.nohu;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class NohuEndGameMessage extends EndGameMessage {

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
    public NohuEndGameMessage(
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
