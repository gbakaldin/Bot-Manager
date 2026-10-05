package com.vingame.bot.domain.bot.message.crash;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.vingame.websocketparser.message.request.Body;

/**
 * Abstract base class for every inbound CRASH game message (119 Avatar,
 * {@code aviatorPlugin}). Extends {@link Body} to satisfy the {@code onMessage} type
 * constraint and configures Jackson polymorphic deserialization on the {@code "cmd"}
 * property — mirroring {@link com.vingame.bot.domain.bot.message.cashout.CashoutMessage}.
 * <p>
 * CMDs are {@code offset + code} with the codes on
 * {@link com.vingame.bot.domain.bot.message.CrashMessageTypes} ({@code docs/plans/AVIATOR_BOT.md}
 * AD-4), so 119 Avatar (offset 1700) speaks 1700/1702/1703/1705/1706/1707/1709. Concrete
 * subtypes are registered against those cmd strings by
 * {@link com.vingame.bot.domain.bot.message.CrashMessageTypes#getTypeRegistrations(int)}.
 * The bet board ({@code X708}) and the jackpot frame ({@code X716}) are deliberately
 * <b>not</b> registered and never parsed.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "cmd",
        visible = true
)
public abstract class CrashMessage extends Body {

    protected CrashMessage(int cmd) {
        super(cmd);
    }
}
