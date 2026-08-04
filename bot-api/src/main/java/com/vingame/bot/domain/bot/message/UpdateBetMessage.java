package com.vingame.bot.domain.bot.message;

/**
 * Abstract base class for bet update messages.
 * Provides game state information during a round.
 */
public abstract class UpdateBetMessage extends BettingMiniMessage {

    protected UpdateBetMessage(int cmd) {
        super(cmd);
    }

    /**
     * @return Current game state ID (maps to BettingMiniGameState)
     */
    public abstract int getGameState();
}
