package com.vingame.bot.domain.bot.message.g4.win79.crash;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.crash.CrashBetAck;
import lombok.ToString;

/**
 * 119 Avatar bet ack ({@code 1702}), captured 2026-10-05 (L17), ~200 ms after the bet:
 * <pre>{@code {"eid":1,"b":10000,"cmd":1702}}</pre>
 * <b>No sid</b> ({@code docs/plans/AVIATOR_BOT.md} F-3).
 */
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class Win79CrashBetAck extends CrashBetAck {

    private final int eid;
    private final long b;

    @JsonCreator
    public Win79CrashBetAck(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("eid") int eid,
            @JsonProperty("b") long b) {
        super(cmd);
        this.eid = eid;
        this.b = b;
    }

    @Override
    public int eid() {
        return eid;
    }

    @Override
    public long stake() {
        return b;
    }
}
