package com.vingame.bot.domain.bot.message.crash;

import com.vingame.bot.domain.bot.message.HasBotWinnings;

/**
 * The server's acknowledgement of the bot's cash-out ({@code offset + CASHOUT_CODE},
 * e.g. {@code 1703}). <b>Carries no sid</b> ({@code docs/plans/AVIATOR_BOT.md} F-3), so it
 * binds on {@code (eid, stake)} while a cash-out is pending.
 *
 * <p>{@link HasBotWinnings} lives here and not on the round end (AD-11): this is the
 * only frame that carries the bot's payout. {@link #payout()} is gross — captured
 * {@code 10000 × 2.4 = 24000}, {@code 50000 × 1.82 = 91000}.
 */
public abstract class CrashCashoutAck extends CrashMessage implements HasBotWinnings {

    protected CrashCashoutAck(int cmd) {
        super(cmd);
    }

    /** @return the runner the bet was on (wire {@code eid}). */
    public abstract int eid();

    /** @return the stake that was cashed out (wire {@code b}). */
    public abstract long stake();

    /** @return the multiplier the cash-out settled at (wire {@code odd}); a payout ratio. */
    public abstract double multiplier();

    /** @return the gross payout (119 wire {@code wm}). */
    public abstract double payout();

    /**
     * {@inheritDoc}
     * <p>
     * The ack is about one player's own bet, so {@code userName} is not needed to find
     * the bot's entry; it is accepted to satisfy {@link HasBotWinnings}.
     *
     * @return {@code round(payout())}
     */
    @Override
    public long winningsFor(String userName) {
        return Math.round(payout());
    }
}
