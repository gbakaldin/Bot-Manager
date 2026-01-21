package com.vingame.bot.domain.bot.message;

/**
 * Abstract base class for end game messages.
 * Signals the end of a betting round.
 * <p>
 * Currently has no abstract methods - add accessors if bots need
 * to read result data in the future.
 */
public abstract class EndGameMessage extends BettingMiniMessage {

    protected EndGameMessage(int cmd) {
        super(cmd);
    }
}
