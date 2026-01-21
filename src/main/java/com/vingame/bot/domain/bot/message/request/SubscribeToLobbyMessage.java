package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;

public class SubscribeToLobbyMessage extends ActionRequestMessage implements CmdAwareMessage {

    public SubscribeToLobbyMessage(String pluginName, Body data) {
        super("MiniGame", pluginName, data);
    }

    public SubscribeToLobbyMessage(String zoneName, String pluginName, Body data) {
        super(zoneName, pluginName, data);
    }
}
