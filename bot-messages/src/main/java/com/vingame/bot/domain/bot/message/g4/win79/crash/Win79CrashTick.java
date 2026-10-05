package com.vingame.bot.domain.bot.message.g4.win79.crash;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.crash.CrashTick;
import lombok.ToString;

/**
 * 119 Avatar flight tick ({@code 1709}), about every 500 ms. Captured 2026-10-05:
 * <pre>{@code
 * L4  {"nFi":false,"nOdd":1.43,"ps":[],"jOdd":1.43,"iJe":false,"cmd":1709,"jFi":false,"sid":1638118}
 * L12 {"nFi":true, "nOdd":2.86,"ps":[],"jOdd":6,   "iJe":true, "cmd":1709,"jFi":false,"sid":1638118}
 * }</pre>
 * Both runners share one curve ({@code jOdd == nOdd}) until one crashes; the crashed
 * runner's value then freezes and its flag goes true while the other keeps climbing
 * ({@code docs/plans/AVIATOR_BOT.md} F-1). L12 is exactly that: Neytiri crashed at 2.86,
 * Jake at 6 (an integer on the wire, F-2) and still flying.
 *
 * <p>Runner mapping per {@link Win79CrashRunners}. {@code ps[]} (every player's
 * cash-outs, potentially huge at prod) and {@code iJe} are skipped, not deserialized.
 *
 * <p>No Lombok {@code @Getter}: {@code getJOdd()} / {@code getNFi()} would de-mangle to
 * {@code jodd} / {@code nfi}. The wire is read through the creator and exposed through
 * {@link #multiplierFor(int)} / {@link #crashedFor(int)} only.
 */
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class Win79CrashTick extends CrashTick {

    private final long sid;
    private final Double jOdd;
    private final Double nOdd;
    private final boolean jFi;
    private final boolean nFi;

    @JsonCreator
    public Win79CrashTick(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("sid") long sid,
            @JsonProperty("jOdd") Double jOdd,
            @JsonProperty("nOdd") Double nOdd,
            @JsonProperty("jFi") boolean jFi,
            @JsonProperty("nFi") boolean nFi) {
        super(cmd);
        this.sid = sid;
        this.jOdd = jOdd;
        this.nOdd = nOdd;
        this.jFi = jFi;
        this.nFi = nFi;
    }

    @Override
    public long sid() {
        return sid;
    }

    /**
     * {@inheritDoc}
     * <p>eid 1 → {@code jOdd}, eid 2 → {@code nOdd}; anything else → {@code 0}.
     */
    @Override
    public long multiplierFor(int eid) {
        return switch (eid) {
            case Win79CrashRunners.JAKE -> Win79CrashRunners.hundredths(jOdd);
            case Win79CrashRunners.NEYTIRI -> Win79CrashRunners.hundredths(nOdd);
            default -> 0L;
        };
    }

    /**
     * {@inheritDoc}
     * <p>eid 1 → {@code jFi}, eid 2 → {@code nFi}; anything else → {@code true}.
     */
    @Override
    public boolean crashedFor(int eid) {
        return switch (eid) {
            case Win79CrashRunners.JAKE -> jFi;
            case Win79CrashRunners.NEYTIRI -> nFi;
            default -> true;
        };
    }
}
