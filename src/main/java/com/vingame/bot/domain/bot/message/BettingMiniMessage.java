package com.vingame.bot.domain.bot.message;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.vingame.websocketparser.message.request.Body;

/**
 * Abstract base class for all BettingMini game messages.
 * Extends Body to satisfy the onMessage type constraint.
 * Configures Jackson polymorphic deserialization based on the "cmd" property.
 * <p>
 * Concrete message classes are registered dynamically via
 * {@link GameMessageTypes#getTypeRegistrations(int, boolean)}.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "cmd",
        visible = true
)
public abstract class BettingMiniMessage extends Body {

    protected BettingMiniMessage(int cmd) {
        super(cmd);
    }
}
