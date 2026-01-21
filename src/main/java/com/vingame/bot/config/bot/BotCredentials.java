package com.vingame.bot.config.bot;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable value object containing bot authentication credentials.
 * Used to pass runtime user-specific data to bot instances.
 */
@Value
@Builder
public class BotCredentials {
    /**
     * Bot username for authentication
     */
    String username;

    /**
     * Bot password for authentication
     */
    String password;

    /**
     * Unique fingerprint for this bot instance
     * Generated randomly per bot
     */
    String fingerprint;
}
