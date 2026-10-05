package com.vingame.bot.domain.bot.message.cashout;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.vingame.websocketparser.message.request.Body;

/**
 * Abstract base class for every inbound CASHOUT game message (119 Balloon /
 * Soccer). Extends {@link Body} to satisfy the {@code onMessage} type constraint
 * and configures Jackson polymorphic deserialization on the {@code "cmd"}
 * property — mirroring {@link com.vingame.bot.domain.bot.message.slot.SlotMessage}.
 * <p>
 * CMDs are {@code offset + code} with codes {@code SUBSCRIBE = 0},
 * {@code BET = 1}, {@code CASHOUT = 2} ({@code docs/plans/CASHOUT_BOT.md} AD-2), so
 * Balloon (offset 1500) speaks 1500/1501/1502 and Soccer (offset 2500) speaks
 * 2500/2501/2502. Concrete subtypes are registered against those cmd strings by
 * {@link com.vingame.bot.domain.bot.message.CashoutMessageTypes#getTypeRegistrations(int)}.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "cmd",
        visible = true
)
public abstract class CashoutMessage extends Body {

    protected CashoutMessage(int cmd) {
        super(cmd);
    }
}
