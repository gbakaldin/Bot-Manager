package com.vingame.bot.brands.bom.message.bettingmini.request;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class BetEntryInfo {

    private final int eid;
    private final long v;

    public static BetEntryInfo of(int eid, long v) {
        return new BetEntryInfo(eid, v);
    }
}
