package com.vingame.bot.domain.bot.message.g3.tip;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class TipEndGameMessage extends EndGameMessage {

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
