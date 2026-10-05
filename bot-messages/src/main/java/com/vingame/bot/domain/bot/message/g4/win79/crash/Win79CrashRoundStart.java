package com.vingame.bot.domain.bot.message.g4.win79.crash;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.crash.CrashRoundStart;
import lombok.ToString;

/**
 * 119 Avatar round start, betting opens ({@code 1705}), captured 2026-10-05 (L15):
 * <pre>{@code {"eI":{"jp":1812320,…},"cmd":1705,"iOE":true,"sid":1638119}}</pre>
 * Only {@code sid} is read; {@code eI} (jackpot info) and {@code iOE} are skipped.
 */
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class Win79CrashRoundStart extends CrashRoundStart {

    private final long sid;

    @JsonCreator
    public Win79CrashRoundStart(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("sid") long sid) {
        super(cmd);
        this.sid = sid;
    }

    @Override
    public long sid() {
        return sid;
    }
}
