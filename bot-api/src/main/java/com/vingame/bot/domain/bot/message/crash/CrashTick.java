package com.vingame.bot.domain.bot.message.crash;

/**
 * Flight tick ({@code offset + TICK_CODE}, e.g. {@code 1709}), about every 500 ms
 * during the flight. Read through {@link HasRunnerMultiplier}; see that interface for
 * the crashed-flag rule ({@code docs/plans/AVIATOR_BOT.md} F-1 / AD-2).
 *
 * <p>Ticks are 2/s/bot, so concrete classes model only the runner fields and the sid;
 * the per-player board ({@code ps[]}) is not deserialized.
 */
public abstract class CrashTick extends CrashMessage implements HasRunnerMultiplier {

    protected CrashTick(int cmd) {
        super(cmd);
    }

    /** @return the round id (wire {@code sid}). */
    public abstract long sid();
}
