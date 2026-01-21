package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
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
