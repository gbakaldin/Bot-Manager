package com.vingame.bot.domain.bot.message.g4.win79.crash;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.crash.CrashRoundEnd;
import lombok.ToString;

/**
 * 119 Avatar round end ({@code 1707}), captured 2026-10-05:
 * <pre>{@code
 * L13 {"nOdd":2.86,"b":0,    "jOdd":11.59,"cmd":1707,"sid":1638118}
 * L24 {"nOdd":2.86,"b":10000,"jOdd":11.59,"cmd":1707,"sid":1638119}
 * }</pre>
 * {@code b} is the player's own stake for the round ({@code 0} with no bet). {@code jOdd}
 * / {@code nOdd} are the final crash points, kept for diagnostics only (on staging they
 * are the same every round, F-5).
 */
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class Win79CrashRoundEnd extends CrashRoundEnd {

    private final long sid;
    private final long b;
    private final Double jOdd;
    private final Double nOdd;

    @JsonCreator
    public Win79CrashRoundEnd(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("sid") long sid,
            @JsonProperty("b") long b,
            @JsonProperty("jOdd") Double jOdd,
            @JsonProperty("nOdd") Double nOdd) {
        super(cmd);
        this.sid = sid;
        this.b = b;
        this.jOdd = jOdd;
        this.nOdd = nOdd;
    }

    @Override
    public long sid() {
        return sid;
    }

    @Override
    public long ownStake() {
        return b;
    }

    /**
     * @param eid the runner
     * @return that runner's final crash point in hundredths; {@code 0} for an unknown
     *         runner or an absent value. Diagnostics only.
     */
    public long crashPointFor(int eid) {
        return switch (eid) {
            case Win79CrashRunners.JAKE -> Win79CrashRunners.hundredths(jOdd);
            case Win79CrashRunners.NEYTIRI -> Win79CrashRunners.hundredths(nOdd);
            default -> 0L;
        };
    }
}
