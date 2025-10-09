package com.vingame.bot.brands.bom.message.bettingmini.request;

import com.vingame.bot.brands.bom.message.bettingmini.polymorphism.CmdAwareMessage;
import com.vingame.webocketparser.message.request.ActionRequestMessage;
import com.vingame.webocketparser.message.request.Body;
import lombok.Getter;

public class FetchSessionDetail extends ActionRequestMessage implements CmdAwareMessage {

    public FetchSessionDetail(String zoneName, String pluginName, int cmd, long sid, int aid) {
        super(zoneName, pluginName, new FetchSessionDetailData(cmd, sid, aid));
    }

    public FetchSessionDetail(String zoneName, String pluginName, int cmd, long sid) {
        this(zoneName, pluginName, cmd, sid, 1);
    }

    @Getter
    public static class FetchSessionDetailData extends Body {
        private final long sid;
        private final int aid;

        public FetchSessionDetailData(int cmd, long sid, int aid) {
            super(cmd);
            this.sid = sid;
            this.aid = aid;
        }
    }
}
