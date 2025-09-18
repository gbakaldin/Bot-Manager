package com.vingame.bot.brands.bom.message.bettingmini.request;


import com.vingame.webocketparser.message.request.ActionRequestMessage;
import com.vingame.webocketparser.message.request.Data;

public class SubscribeToLobbyMessage extends ActionRequestMessage {

    public SubscribeToLobbyMessage(String pluginName, Data data) {
        super("MiniGame", pluginName, data);
    }

    public SubscribeToLobbyMessage(String zoneName, String pluginName, Data data) {
        super(zoneName, pluginName, data);
    }
}
