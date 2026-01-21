package com.vingame.bot.config.bot;

import com.vingame.bot.domain.game.model.Game;
import lombok.Builder;
import lombok.Value;

/**
 * Immutable value object containing complete bot configuration.
 * Combines runtime credentials with behavior settings and environment linkage.
 * <p>
 * This configuration object is passed to the bot factory to create
 * properly configured bot instances.
 */
@Value
@Builder
public class BotConfiguration {
    /**
     * Bot authentication credentials (username, password, fingerprint)
     */
    BotCredentials credentials;

    /**
     * ID of the environment this bot should connect to
     * Used to resolve environment-specific shared clients
     */
    String environmentId;

    /**
     * Game configuration containing offset, numberOfOptions, pluginName, md5, etc.
     */
    Game game;

    /**
     * Betting behavior configuration
     */
    BotBehaviorConfig behaviorConfig;

    /**
     * Zone name for WebSocket messages (e.g., "MiniGame3")
     * Retrieved from environment configuration
     */
    String zoneName;

    long timeoutMillis;
}
