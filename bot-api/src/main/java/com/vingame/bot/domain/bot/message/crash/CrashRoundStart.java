package com.vingame.bot.domain.bot.message.crash;

/**
 * New round, betting opens ({@code offset + ROUND_START_CODE}, e.g. {@code 1705}).
 * {@code docs/plans/AVIATOR_BOT.md} AD-5 / AD-10.
 */
public abstract class CrashRoundStart extends CrashMessage {

    protected CrashRoundStart(int cmd) {
        super(cmd);
    }

    /** @return the round id the bet must carry (wire {@code sid}). */
    public abstract long sid();
}
