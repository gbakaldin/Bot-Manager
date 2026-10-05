package com.vingame.bot.domain.bot.message.g4.win79.crash;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.crash.CrashSubscribeResponse;
import lombok.ToString;

/**
 * 119 Avatar subscribe reply / round snapshot ({@code 1700}), captured 2026-10-05 (L3):
 * <pre>{@code {"op":[],"b":0,"eI":{…},"cH":[…50 chat lines…],"htr":[…50 rounds…],"jFi":false,"gS":4,
 *  "sid":1638118,"nFi":false,"tFB":5000,"nOdd":2.86,"cCUCO":4887,"jOdd":11.59,"rmT":0,"cmd":1700,"tFl":3,"iOE":true}}</pre>
 * The bot bets off none of it ({@code docs/plans/AVIATOR_BOT.md} AD-12, F-4); only
 * {@code sid} is kept, for diagnostics. {@code gS}, {@code rmT}, {@code cH},
 * {@code htr} and the rest are skipped, not modelled.
 */
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class Win79CrashSubscribeResponse extends CrashSubscribeResponse {

    private final long sid;

    @JsonCreator
    public Win79CrashSubscribeResponse(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("sid") long sid) {
        super(cmd);
        this.sid = sid;
    }

    /** @return the round in flight at subscribe time. Diagnostics only. */
    public long sid() {
        return sid;
    }
}
