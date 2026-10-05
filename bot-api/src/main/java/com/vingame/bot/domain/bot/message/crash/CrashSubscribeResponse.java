package com.vingame.bot.domain.bot.message.crash;

/**
 * The subscribe reply of a CRASH game ({@code offset + SUBSCRIBE_CODE}, e.g.
 * {@code 1700}): the round snapshot. A marker — the bot reads nothing from it
 * ({@code docs/plans/AVIATOR_BOT.md} AD-12: never bet off the snapshot; the first bet is
 * on the next round start). Its arrival is what proves the subscribe was accepted.
 */
public abstract class CrashSubscribeResponse extends CrashMessage {

    protected CrashSubscribeResponse(int cmd) {
        super(cmd);
    }
}
