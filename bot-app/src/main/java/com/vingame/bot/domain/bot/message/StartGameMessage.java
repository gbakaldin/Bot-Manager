package com.vingame.bot.domain.bot.message;

/**
 * Abstract base class for start game messages across all products.
 * Provides the minimal data the bot needs to track game sessions.
 */
public abstract class StartGameMessage extends BettingMiniMessage {

    protected StartGameMessage(int cmd) {
        super(cmd);
    }

    public abstract long getSessionId();
}
