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

    /**
     * Whether per-round bet ramp-up is active for this bot
     * (JACKPOT_SCALE_AND_RAMP AD-R4). Only set for {@code BETTING_MINI}/{@code TAI_XIU}
     * bots in {@code createSingleBot}; SLOT and other types leave it at the default
     * {@code false} (AD-R6). When false, the bet path is byte-for-byte today's flat
     * cadence (AD-R5).
     */
    boolean rampEnabled;

    /**
     * Ramp curve exponent {@code k} for the accept-probability power curve
     * (JACKPOT_SCALE_AND_RAMP AD-R3). Only meaningful when {@link #rampEnabled} is
     * true; defaults to {@code 0.0} for non-ramp / non-eligible bots.
     */
    double rampShape;
}
