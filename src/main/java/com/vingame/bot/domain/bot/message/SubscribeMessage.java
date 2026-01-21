package com.vingame.bot.domain.bot.message;

/**
 * Abstract base class for subscribe response messages.
 * Provides timing configuration for the betting phase.
 */
public abstract class SubscribeMessage extends BettingMiniMessage {

    protected SubscribeMessage(int cmd) {
        super(cmd);
    }

    /**
     * @return Time available for betting in milliseconds
     */
    public abstract long getTimeForBetting();

    /**
     * @return Time threshold before round end when bets are blocked, in milliseconds
     */
    public abstract long getTimeForDecision();
}
