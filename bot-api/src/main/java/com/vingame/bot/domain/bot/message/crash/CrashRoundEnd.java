package com.vingame.bot.domain.bot.message.crash;

/**
 * Round end ({@code offset + ROUND_END_CODE}, e.g. {@code 1707}). Carries the final
 * crash points and the bot's own stake for the round ({@code 0} when it placed no bet).
 * {@code docs/plans/AVIATOR_BOT.md} AD-5 / AD-11: no {@code HasBetTotals} — the stake
 * only echoes the bot's own plan and is read for a one-shot cross-check.
 */
public abstract class CrashRoundEnd extends CrashMessage {

    protected CrashRoundEnd(int cmd) {
        super(cmd);
    }

    /** @return the round id (wire {@code sid}). */
    public abstract long sid();

    /** @return the bot's own stake this round (119 wire {@code b}); {@code 0} when none. */
    public abstract long ownStake();
}
