package com.vingame.bot.domain.bot.message.g4.win79.crash;

import com.vingame.bot.domain.bot.message.CrashMessageTypes;
import com.vingame.bot.domain.bot.message.request.CrashRequest;
import com.vingame.bot.domain.bot.message.request.SubscribeToLobbyMessage;
import com.vingame.websocketparser.message.request.Body;

/**
 * 119 implementation of {@link CrashRequest}: zone, plugin and offset bound once per
 * bot, CMD = offset + code ({@code docs/plans/AVIATOR_BOT.md} AD-4).
 */
public class Win79CrashRequest implements CrashRequest {

    private final String zoneName;
    private final String pluginName;
    private final int offset;

    public Win79CrashRequest(String zoneName, String pluginName, int offset) {
        this.zoneName = zoneName;
        this.pluginName = pluginName;
        this.offset = offset;
    }

    /** {@code [6, zone, plugin, {"cmd": offset}]} — the generic lobby subscribe (captured L2). */
    @Override
    public SubscribeToLobbyMessage subscribe() {
        return new SubscribeToLobbyMessage(zoneName, pluginName,
                new Body(offset + CrashMessageTypes.SUBSCRIBE_CODE));
    }

    @Override
    public Win79CrashBet bet(long amount, long sid, int eid) {
        return new Win79CrashBet(offset + CrashMessageTypes.BET_CODE, zoneName, pluginName, amount, sid, eid);
    }

    @Override
    public Win79CrashCashOut cashOut(long sid, int eid) {
        return new Win79CrashCashOut(offset + CrashMessageTypes.CASHOUT_CODE, zoneName, pluginName, sid, eid);
    }
}
