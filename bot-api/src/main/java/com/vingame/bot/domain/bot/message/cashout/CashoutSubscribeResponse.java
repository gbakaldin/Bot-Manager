package com.vingame.bot.domain.bot.message.cashout;

import java.util.List;

/**
 * The subscribe reply of a CASHOUT game ({@code offset + SUBSCRIBE_CODE}, e.g.
 * {@code 1500}). The only thing the bot reads from it is the server's allowed stake
 * set ({@code docs/plans/CASHOUT_BOT.md} AD-4 / AD-6): a bot's stake is a uniform pick
 * from {@code allowedBets() ∩ [minBet, maxBet]}.
 */
public abstract class CashoutSubscribeResponse extends CashoutMessage {

    protected CashoutSubscribeResponse(int cmd) {
        super(cmd);
    }

    /**
     * @return the server's allowed stakes, ascending; an empty list (never
     *         {@code null}) when the frame carried none — which means the bot does
     *         not bet (AD-6).
     */
    public abstract List<Long> allowedBets();
}
