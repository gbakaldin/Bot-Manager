package com.vingame.bot.domain.bot.message.slot;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.vingame.websocketparser.message.request.Body;

/**
 * Abstract base class for all SLOT game messages.
 * Extends {@link Body} to satisfy the {@code onMessage} type constraint and
 * configures Jackson polymorphic deserialization based on the {@code "cmd"}
 * property — mirroring
 * {@link com.vingame.bot.domain.bot.message.BettingMiniMessage}.
 * <p>
 * Unlike the betting-mini family, slot CMDs are global fixed constants
 * ({@code 1300} subscribe, {@code 1302} spin) with no {@code CODE + offset}
 * arithmetic (see SLOT_MACHINE_BOT plan AD-1). Concrete subtypes are registered
 * against the literal cmd strings {@code "1300"} / {@code "1302"} by the slot
 * message-types provider (added in Phase 2).
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "cmd",
        visible = true
)
public abstract class SlotMessage extends Body {

    protected SlotMessage(int cmd) {
        super(cmd);
    }
}
