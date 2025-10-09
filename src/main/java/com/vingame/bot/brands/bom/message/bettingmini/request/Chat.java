package com.vingame.bot.brands.bom.message.bettingmini.request;

import com.vingame.bot.brands.bom.message.bettingmini.polymorphism.CmdAwareMessage;
import com.vingame.webocketparser.message.request.ActionRequestMessage;
import com.vingame.webocketparser.message.request.Body;
import lombok.Getter;
import lombok.Setter;

public class Chat extends ActionRequestMessage implements CmdAwareMessage {

    public Chat(int cmd, String zoneName, String pluginName, String mgs) {
        super(zoneName, pluginName, new ChatData(cmd, mgs));
    }

    @Getter
    @Setter
    public static class ChatData extends Body {
        private final String mgs;

        public ChatData(int cmd, String mgs) {
            super(cmd);
            this.mgs = mgs;
        }
    }

}