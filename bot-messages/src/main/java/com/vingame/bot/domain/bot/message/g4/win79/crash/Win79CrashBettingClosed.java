package com.vingame.bot.domain.bot.message.g4.win79.crash;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.crash.CrashBettingClosed;
import lombok.ToString;

/**
 * 119 Avatar betting closed, flight starts ({@code 1706}), captured 2026-10-05 (L20),
 * 7.87-7.92 s after the round start in all three captured rounds:
 * <pre>{@code {"eI":{…},"iUC":100,"cmd":1706,"sid":1638119}}</pre>
 * Only {@code sid} is read; {@code iUC} is skipped (F-4).
 */
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class Win79CrashBettingClosed extends CrashBettingClosed {

    private final long sid;

    @JsonCreator
    public Win79CrashBettingClosed(
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
