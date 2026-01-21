package com.vingame.bot.domain.bot.message.request;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BetEntryInfo implements CmdAwareMessage {

    private final int eid;
    private final long v;

    public static BetEntryInfo of(int eid, long v) {
        return new BetEntryInfo(eid, v);
    }
}
