package com.vingame.bot.domain.bot.message.g4.win79.cashout;

import com.vingame.bot.domain.bot.message.CashoutMessageTypes;
import com.vingame.bot.domain.bot.message.request.CashoutRequest;
import com.vingame.bot.domain.bot.message.request.SubscribeToLobbyMessage;
import com.vingame.websocketparser.message.request.Body;

/**
 * 119 implementation of {@link CashoutRequest}: zone, plugin and offset bound once
 * per bot, CMD = offset + code ({@code docs/plans/CASHOUT_BOT.md} AD-2).
 */
public class Win79CashoutRequest implements CashoutRequest {

    private final String zoneName;
    private final String pluginName;
    private final int offset;

    public Win79CashoutRequest(String zoneName, String pluginName, int offset) {
        this.zoneName = zoneName;
        this.pluginName = pluginName;
        this.offset = offset;
    }

    /** {@code [6, zone, plugin, {"cmd": offset}]} — the generic lobby subscribe. */
    @Override
    public SubscribeToLobbyMessage subscribe() {
        return new SubscribeToLobbyMessage(zoneName, pluginName,
                new Body(offset + CashoutMessageTypes.SUBSCRIBE_CODE));
    }

    @Override
    public Win79CashoutBet bet(long amount) {
        return new Win79CashoutBet(offset + CashoutMessageTypes.BET_CODE, zoneName, pluginName, amount);
    }

    @Override
    public Win79CashoutCashOut cashOut(long sid) {
        return new Win79CashoutCashOut(offset + CashoutMessageTypes.CASHOUT_CODE, zoneName, pluginName, sid);
    }
}
