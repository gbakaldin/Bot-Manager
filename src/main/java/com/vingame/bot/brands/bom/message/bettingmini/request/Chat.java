package com.vingame.bot.brands.bom.message.bettingmini.request;

import com.vingame.webocketparser.message.request.ActionRequestMessage;
import com.vingame.webocketparser.message.request.Data;
import lombok.Getter;
import lombok.Setter;

public class Chat extends ActionRequestMessage {

    public Chat(int cmd, String zoneName, String pluginName, String mgs) {
        super(zoneName, pluginName, new ChatData(cmd, mgs));
    }

    @Getter
    @Setter
    public static class ChatData extends Data {
        private final String mgs;

        public ChatData(int cmd, String mgs) {
            super(cmd);
            this.mgs = mgs;
        }
    }

}