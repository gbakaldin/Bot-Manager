package com.vingame.bot.domain.bot.message.g4.win79;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.coordination.CrowdOption;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * WIN79 subscribe response (CMD {@code 3000 + offset}; {@code 5000} at offset 2000).
 * <p>
 * The two abstract accessors drive the bot's bet window: {@code tFB} (25 000 ms in
 * the capture) is how long betting is open, {@code tFD} (3 000 ms) is the tail
 * during which bets are refused. Same field names and roles as Nohu and Bom.
 * <p>
 * <b>The outbound subscribe CMD is a known open question.</b> The real WIN79 client
 * subscribes with {@code {"cmd":5012,"iM":false}} — i.e. CODE 3012, which exists
 * nowhere in this codebase — while {@code Request.subscribe()} sends
 * {@code 3000 + offset} = {@code 5000}. The inbound response modelled here is
 * {@code 5000} either way. Deliberately left alone (user's call, 2026-09-10): 3000 is
 * believed to work, and {@code Request.subscribe()} is shared by all four
 * betting-mini products, so changing it would need a per-product seam rather than an
 * edit in place. If subscription turns out to fail on 119, this is the first thing to
 * revisit.
 * <p>
 * The frame also carries chat history ({@code cH}), round history ({@code htr}),
 * jackpot timers ({@code tFJp}/{@code exTFJp}/{@code tFP}) and {@code ldDs}. None are
 * read by the engine, so they are tolerated rather than modelled — the scenario
 * mapper sets {@code FAIL_ON_UNKNOWN_PROPERTIES=false} and this class adds
 * {@link JsonIgnoreProperties} as belt-and-braces.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class Win79SubscribeMessage extends SubscribeMessage implements HasCrowdBets {

    private long sid;
    private int gS;

    /** Milliseconds the bet window is open. */
    private long tFB;
    /** Milliseconds before round end during which bets are refused. */
    private long tFD;
    /** Milliseconds remaining in the current phase at subscribe time. */
    private long rmT;

    /** Live running jackpot pool meter. */
    private long tJpV;
    private long tJpV2;

    /** This bot's win on the round in progress; {@code 0} at a clean subscribe. */
    private long wm;

    private long tTU;
    private boolean iab;

    private int d1;
    private int d2;
    private int d3;

    private List<Win79BetInfo> bs;

    @JsonCreator
    public Win79SubscribeMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("sid") long sid,
            @JsonProperty("gS") int gS,
            @JsonProperty("tFB") long tFB,
            @JsonProperty("tFD") long tFD,
            @JsonProperty("rmT") long rmT,
            @JsonProperty("tJpV") long tJpV,
            @JsonProperty("tJpV2") long tJpV2,
            @JsonProperty("wm") long wm,
            @JsonProperty("tTU") long tTU,
            @JsonProperty("iab") boolean iab,
            @JsonProperty("d1") int d1,
            @JsonProperty("d2") int d2,
            @JsonProperty("d3") int d3,
            @JsonProperty("bs") List<Win79BetInfo> bs) {
        super(cmd);
        this.sid = sid;
        this.gS = gS;
        this.tFB = tFB;
        this.tFD = tFD;
        this.rmT = rmT;
        this.tJpV = tJpV;
        this.tJpV2 = tJpV2;
        this.wm = wm;
        this.tTU = tTU;
        this.iab = iab;
        this.d1 = d1;
        this.d2 = d2;
        this.d3 = d3;
        this.bs = bs;
    }

    @Override
    public long getTimeForBetting() {
        return tFB;
    }

    @Override
    public long getTimeForDecision() {
        return tFD;
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
