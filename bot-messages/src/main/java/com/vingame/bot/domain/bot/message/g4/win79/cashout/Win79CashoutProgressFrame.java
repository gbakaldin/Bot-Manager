package com.vingame.bot.domain.bot.message.g4.win79.cashout;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 119 progress frame, {@code X501} (Balloon {@code 1501}, Soccer {@code 2501}). Usually
 * {@code iF:false}; legacy also saw bursts here as {@code iF:true, crd:0}
 * ({@code docs/plans/CASHOUT_BOT.md} F-4), which {@code isBurst()} covers. Shape on
 * {@link Win79CashoutFrame}.
 */
public class Win79CashoutProgressFrame extends Win79CashoutFrame {

    @JsonCreator
    @SuppressWarnings("java:S107")
    public Win79CashoutProgressFrame(
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
