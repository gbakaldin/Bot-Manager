package com.vingame.bot.domain.bot.message.g3.tip;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.coordination.CrowdOption;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class TipSubscribeMessage extends SubscribeMessage implements HasCrowdBets {

    private int gid;
    private List<ChatMessage> cH;
    private long tbu;
    private long tJpV;
    private boolean iab;
    private int gS;
    private long tFJp;
    private long sid;
    private List<BetInfoWithTotal> bs;
    private long tJpv2;
    private JackpotCountdown jpCD;
    private long wm;
    private SubscribeDice sDi;
    private List<HistoryItem> htr;
    private long tFB;
    private String cdt;
    private int jPTp;
    private long tFD;
    private long tTU;
    private long mB;
    private long rmT;
    private long tFJp2;
    private int did;
    private long tFP;

    @JsonCreator
    public TipSubscribeMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("gid") int gid,
            @JsonProperty("cH") List<ChatMessage> cH,
            @JsonProperty("tbu") long tbu,
            @JsonProperty("tJpV") long tJpV,
            @JsonProperty("iab") boolean iab,
            @JsonProperty("gS") int gS,
            @JsonProperty("tFJp") long tFJp,
            @JsonProperty("sid") long sid,
            @JsonProperty("bs") List<BetInfoWithTotal> bs,
            @JsonProperty("tJpv2") long tJpv2,
            @JsonProperty("jpCD") JackpotCountdown jpCD,
            @JsonProperty("wm") long wm,
            @JsonProperty("sDi") SubscribeDice sDi,
            @JsonProperty("htr") List<HistoryItem> htr,
            @JsonProperty("tFB") long tFB,
            @JsonProperty("cdt") String cdt,
            @JsonProperty("jPTp") int jPTp,
            @JsonProperty("tFD") long tFD,
            @JsonProperty("tTU") long tTU,
            @JsonProperty("mB") long mB,
            @JsonProperty("rmT") long rmT,
            @JsonProperty("tFJp2") long tFJp2,
            @JsonProperty("did") int did,
            @JsonProperty("tFP") long tFP) {

        super(cmd);
        this.gid = gid;
        this.cH = cH;
        this.tbu = tbu;
        this.tJpV = tJpV;
        this.iab = iab;
        this.gS = gS;
        this.tFJp = tFJp;
        this.sid = sid;
        this.bs = bs;
        this.tJpv2 = tJpv2;
        this.jpCD = jpCD;
        this.wm = wm;
        this.sDi = sDi;
        this.htr = htr;
        this.tFB = tFB;
        this.cdt = cdt;
        this.jPTp = jPTp;
        this.tFD = tFD;
        this.tTU = tTU;
        this.mB = mB;
        this.rmT = rmT;
        this.tFJp2 = tFJp2;
        this.did = did;
        this.tFP = tFP;
    }

    /**
     * Per-option crowd distribution from the Subscribe {@code bs} array (AD-C1) —
     * the round-open seed (typically near-zero crowd). {@code BetInfoWithTotal}
     * carries own {@code b} (D3, unused by v1). Empty when no {@code bs}.
     */
    @Override
    public List<CrowdOption> crowdBets() {
        if (bs == null) {
            return List.of();
        }
        return bs.stream()
                .map(e -> new CrowdOption(e.getEid(), e.getV(), e.getB(), e.getBc()))
                .toList();
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
    public static class ChatMessage {
        private String uid;
        private long tst;
        private String mgs;
        private String fu;

        @JsonCreator
        public ChatMessage(
                @JsonProperty("uid") String uid,
                @JsonProperty("tst") long tst,
                @JsonProperty("mgs") String mgs,
                @JsonProperty("fu") String fu) {
            this.uid = uid;
            this.tst = tst;
            this.mgs = mgs;
            this.fu = fu;
        }
    }

    @Getter
    @Setter
    public static class BetInfoWithTotal {
        private int eid;
        private int bc;
        private long b;
        private long v;

        @JsonCreator
        public BetInfoWithTotal(
                @JsonProperty("eid") int eid,
                @JsonProperty("bc") int bc,
                @JsonProperty("b") long b,
                @JsonProperty("v") long v) {
            this.eid = eid;
            this.bc = bc;
            this.b = b;
            this.v = v;
        }
    }

    @Getter
    @Setter
    public static class JackpotCountdown {
        private String bET;
        private String bST;
        private String cM;

        @JsonCreator
        public JackpotCountdown(
                @JsonProperty("bET") String bET,
                @JsonProperty("bST") String bST,
                @JsonProperty("cM") String cM) {
            this.bET = bET;
            this.bST = bST;
            this.cM = cM;
        }
    }

    @Getter
    @Setter
    public static class SubscribeDice {
        private int d1;
        private int d2;
        private int d3;

        @JsonCreator
        public SubscribeDice(
                @JsonProperty("d1") int d1,
                @JsonProperty("d2") int d2,
                @JsonProperty("d3") int d3) {
            this.d1 = d1;
            this.d2 = d2;
            this.d3 = d3;
        }
    }

    @Getter
    @Setter
    public static class HistoryItem {
        private int d1;
        private int d2;
        private int d3;
        private long sid;

        @JsonCreator
        public HistoryItem(
                @JsonProperty("d1") int d1,
                @JsonProperty("d2") int d2,
                @JsonProperty("d3") int d3,
                @JsonProperty("sid") long sid) {
            this.d1 = d1;
            this.d2 = d2;
            this.d3 = d3;
            this.sid = sid;
        }
    }
}
