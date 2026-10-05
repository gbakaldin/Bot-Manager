package com.vingame.bot.domain.bot.message.crash;

/**
 * The server's acknowledgement of the bot's bet ({@code offset + BET_CODE}, e.g.
 * {@code 1702}). <b>Carries no sid</b> ({@code docs/plans/AVIATOR_BOT.md} F-3): a bot has
 * one bet per round, so the ack binds on {@code (eid, stake)} while a bet is pending.
 */
public abstract class CrashBetAck extends CrashMessage {

    protected CrashBetAck(int cmd) {
        super(cmd);
    }

    /** @return the runner the bet is on (wire {@code eid}). */
    public abstract int eid();

    /** @return the accepted stake (wire {@code b}). */
    public abstract long stake();
}
