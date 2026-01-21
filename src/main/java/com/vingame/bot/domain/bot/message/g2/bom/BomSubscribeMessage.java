package com.vingame.bot.domain.bot.message.g2.bom;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BomSubscribeMessage extends SubscribeMessage {

    private long mnB;
    private long tJpV;
    private boolean iab;
    private long tTU;
    private long wm;
    private long rmT;
    private long tFJp;
    private long tFJp2;
    private long tFP;
    private long tJpv2;
    private long mB;
    private int gS;
    private long sid;
    private long tFB;
    private long tFD;
    private List<Integer> chp;
    private BomEndGameMessage.LastJackpotData lJp;
    private List<BetInfoWithTotal> bs;
    private List<PayoutRatio> pR;
    private List<ChatMessage> cH;
    private List<HistoryItem> htr;

    @JsonCreator
    public BomSubscribeMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("mnB") long mnB,
            @JsonProperty("tJpV") long tJpV,
            @JsonProperty("iab") boolean iab,
            @JsonProperty("tTU") long tTU,
            @JsonProperty("wm") long wm,
            @JsonProperty("rmT") long rmT,
            @JsonProperty("tFJp") long tFJp,
            @JsonProperty("tFJp2") long tFJp2,
            @JsonProperty("tFP") long tFP,
            @JsonProperty("tJpv2") long tJpv2,
            @JsonProperty("mB") long mB,
            @JsonProperty("gS") int gS,
            @JsonProperty("sid") long sid,
            @JsonProperty("tFB") long tFB,
            @JsonProperty("tFD") long tFD,
            @JsonProperty("chp") List<Integer> chp,
            @JsonProperty("lJp") BomEndGameMessage.LastJackpotData lJp,
            @JsonProperty("bs") List<BetInfoWithTotal> bs,
            @JsonProperty("pR") List<PayoutRatio> pR,
            @JsonProperty("cH") List<ChatMessage> cH,
            @JsonProperty("htr") List<HistoryItem> htr) {

        super(cmd);
        this.mnB = mnB;
        this.tJpV = tJpV;
        this.iab = iab;
        this.tTU = tTU;
        this.wm = wm;
        this.rmT = rmT;
        this.tFJp = tFJp;
        this.tFJp2 = tFJp2;
        this.tFP = tFP;
        this.tJpv2 = tJpv2;
        this.mB = mB;
        this.gS = gS;
        this.sid = sid;
        this.tFB = tFB;
        this.tFD = tFD;
        this.chp = chp;
        this.lJp = lJp;
        this.bs = bs;
        this.pR = pR;
        this.cH = cH;
        this.htr = htr;
    }

    @Override
    public long getTimeForBetting() {
        return tFB;
    }

    @Override
    public long getTimeForDecision() {
        return tFD;
    }

    @Getter
    @Setter
    public static class BetInfoWithTotal extends BomEndGameMessage.BetInfo {
        private long b;

        @JsonCreator
        public BetInfoWithTotal(
                @JsonProperty("b") long b,
                @JsonProperty("eid") int eid,
                @JsonProperty("bc") int bc,
                @JsonProperty("v") long v) {
            super(eid, bc, v);
            this.b = b;
        }
    }

    @Getter
    @Setter
    public static class PayoutRatio {
        private int eid;
        private double v;

        @JsonCreator
        public PayoutRatio(
                @JsonProperty("eid") int eid,
                @JsonProperty("v") double v) {
            this.eid = eid;
            this.v = v;
        }
    }

    @Getter
    @Setter
    public static class HistoryItem {
        private List<Integer> sD;
        private int d1;
        private int d2;
        private int d3;
        private long sid;

        @JsonCreator
        public HistoryItem(
                @JsonProperty("sD") List<Integer> sD,
                @JsonProperty("d1") int d1,
                @JsonProperty("d2") int d2,
                @JsonProperty("d3") int d3,
                @JsonProperty("sid") long sid) {
            this.sD = sD;
            this.d1 = d1;
            this.d2 = d2;
            this.d3 = d3;
            this.sid = sid;
        }
    }

    @Getter
    @Setter
    public static class ChatMessage {
        private String uid;
        private int c;
        private String mgs;
        private String fu;

        @JsonCreator
        public ChatMessage(
                @JsonProperty("uid") String uid,
                @JsonProperty("c") int c,
                @JsonProperty("mgs") String mgs,
                @JsonProperty("fu") String fu) {
            this.uid = uid;
            this.c = c;
            this.mgs = mgs;
            this.fu = fu;
        }
    }
}
