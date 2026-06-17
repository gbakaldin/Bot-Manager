package com.vingame.bot.config.bot;

import com.vingame.bot.domain.bot.strategy.StrategyId;
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
     * ID of the bot group this bot belongs to
     */
    String botGroupId;

    /**
     * Index of this bot within its group (1-based).
     * Used for MDC logging context.
     */
    int botIndex;

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

    long watchdogTimeoutSeconds;

    /**
     * Strategy id assigned to this bot by the group's strategy mix at start.
     * <p>
     * Populated by {@code BotGroupBehaviorService.createSingleBot()} from the
     * fill-to-target assignment over {@code BotGroup.strategyMix}. Read by the
     * bot lifecycle to instantiate the per-bot {@code BettingStrategy} instance
     * (Phase 5) and surfaced on {@code BotHealthDTO} for observability.
     * <p>
     * May be {@code null} on legacy code paths that bypass the assignment
     * (no production caller); the {@code Bot} accessor tolerates that.
     */
    StrategyId strategyId;
}
