package com.vingame.bot.domain.bot.message.g4.win79.crash;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.crash.CrashCashoutAck;
import lombok.ToString;

/**
 * 119 Avatar cash-out ack ({@code 1703}), captured 2026-10-05, ~320 ms after the
 * cash-out:
 * <pre>{@code
 * L22 {"eid":1,"b":10000,"wm":24000,"cmd":1703,"aid":1,"odd":2.4}
 * L33 {"eid":2,"b":50000,"wm":91000,"cmd":1703,"aid":1,"odd":1.82}
 * }</pre>
 * {@code wm} is the gross payout ({@code b × odd}); <b>no sid</b>
 * ({@code docs/plans/AVIATOR_BOT.md} F-3). {@code aid} is the constant account type
 * echoed back; kept for diagnostics.
 */
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class Win79CrashCashoutAck extends CrashCashoutAck {

    private final int eid;
    private final long b;
    private final double wm;
    private final double odd;
    private final Integer aid;

    @JsonCreator
    public Win79CrashCashoutAck(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("eid") int eid,
            @JsonProperty("b") long b,
            @JsonProperty("wm") double wm,
            @JsonProperty("odd") double odd,
            @JsonProperty("aid") Integer aid) {
        super(cmd);
        this.eid = eid;
        this.b = b;
        this.wm = wm;
        this.odd = odd;
        this.aid = aid;
    }

    @Override
    public int eid() {
        return eid;
    }

    @Override
    public long stake() {
        return b;
    }

    @Override
    public double multiplier() {
        return odd;
    }

    @Override
    public double payout() {
        return wm;
    }
}
