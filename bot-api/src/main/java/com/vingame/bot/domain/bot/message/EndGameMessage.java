package com.vingame.bot.domain.bot.message;

/**
 * Abstract base class for end game messages.
 * Signals the end of a betting round.
 * <p>
 * Exposes the session id ({@code sid}) so the bot can correlate the just-completed
 * round with bets it placed during the BET phase. Concrete subclasses store
 * {@code sid} privately under each product's payload shape; this accessor
 * normalizes the read path for {@code BotMemory}'s bet→result correlation
 * (see {@code docs/plans/BETTING_STRATEGIES.md}, Architecture Decision 4).
 */
public abstract class EndGameMessage extends BettingMiniMessage {

    protected EndGameMessage(int cmd) {
        super(cmd);
    }

    /**
     * @return the session id ({@code sid}) carried by this EndGame payload.
     *         Used by {@code BotMemory.completeRound} to match this result against
     *         the in-flight {@code RoundState}.
     */
    public abstract long getSessionId();
}
