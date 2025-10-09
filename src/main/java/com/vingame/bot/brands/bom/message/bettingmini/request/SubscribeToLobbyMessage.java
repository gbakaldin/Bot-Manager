package com.vingame.bot.brands.bom.message.bettingmini.request;


import com.vingame.bot.brands.bom.message.bettingmini.polymorphism.CmdAwareMessage;
import com.vingame.webocketparser.message.request.ActionRequestMessage;
import com.vingame.webocketparser.message.request.Body;

public class SubscribeToLobbyMessage extends ActionRequestMessage implements CmdAwareMessage {

    public SubscribeToLobbyMessage(String pluginName, Body data) {
        super("MiniGame", pluginName, data);
    }

    public SubscribeToLobbyMessage(String zoneName, String pluginName, Body data) {
        super(zoneName, pluginName, data);
    }
}
