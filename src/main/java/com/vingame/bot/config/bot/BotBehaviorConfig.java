package com.vingame.bot.config.bot;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable value object containing bot betting behavior configuration.
 * Defines how the bot should behave during gameplay.
 */
@Value
@Builder
public class BotBehaviorConfig {
    /**
     * Minimum bet amount per round
     */
    long minBet;

    /**
     * Maximum bet amount per round
     */
    long maxBet;

    /**
     * Bet increment step size
     */
    long betIncrement;

    /**
     * Maximum total bet amount allowed per round across all bets
     */
    long maxTotalBetPerRound;

    /**
     * Minimum number of bets per round
     */
    int minBetsPerRound;

    /**
     * Maximum number of bets per round
     */
    int maxBetsPerRound;

    /**
     * Whether chat feature is enabled for this bot
     */
    boolean chatEnabled;

    /**
     * Whether automatic deposit is enabled when balance is low
     */
    boolean autoDepositEnabled;

    /**
     * Percentage chance (0-100) to skip a bet opportunity.
     * Higher values mean fewer bets. Default should be around 60.
     */
    int betSkipPercentage;
}
