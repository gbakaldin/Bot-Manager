package com.vingame.bot.domain.bot.message.g4.win79.cashout;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 119 cash-out reply, {@code X502} (Balloon {@code 1502}, Soccer {@code 2502}). The
 * captured one is a burst ({@code iF:true, blS:-1, crd:0.0}), probably a late cash-out
 * that lost the race; the winning shape is uncaptured ({@code docs/plans/CASHOUT_BOT.md}
 * OI-1) and is expected as {@code iF:true}, {@code blS != -1}, {@code crd} = gross.
 * Shape on {@link Win79CashoutFrame}.
 */
public class Win79CashoutResultFrame extends Win79CashoutFrame {

    @JsonCreator
    @SuppressWarnings("java:S107")
    public Win79CashoutResultFrame(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("b") Long b,
            @JsonProperty("odds") double odds,
            @JsonProperty("crd") double crd,
            @JsonProperty("nextOdds") Double nextOdds,
            @JsonProperty("nextCrd") Double nextCrd,
            @JsonProperty("blS") Integer blS,
            @JsonProperty("iF") boolean iF,
            @JsonProperty("sid") long sid,
            @JsonProperty("sL") Double sL,
            @JsonProperty("aS") Boolean aS,
            @JsonProperty("aSt") Boolean aSt) {
        super(cmd, b, odds, crd, nextOdds, nextCrd, blS, iF, sid, sL, aS, aSt);
    }
}
