package com.vingame.bot.domain.bot.message.crash;

/**
 * Betting closed, flight starts ({@code offset + BETTING_CLOSED_CODE}, e.g.
 * {@code 1706}). {@code docs/plans/AVIATOR_BOT.md} AD-5.
 */
public abstract class CrashBettingClosed extends CrashMessage {

    protected CrashBettingClosed(int cmd) {
        super(cmd);
    }

    /** @return the round id (wire {@code sid}). */
    public abstract long sid();
}
