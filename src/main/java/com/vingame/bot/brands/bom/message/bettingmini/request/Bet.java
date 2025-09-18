package com.vingame.bot.brands.bom.message.bettingmini.request;

import com.vingame.webocketparser.message.request.ActionRequestMessage;
import com.vingame.webocketparser.message.request.Data;
import lombok.Getter;
import lombok.Setter;

public class Bet extends ActionRequestMessage {

    public Bet(int cmd, String zoneName, String pluginName, long entryId, long sessionId) {
        super(zoneName, pluginName, new BetData(cmd, entryId, sessionId));
    }

    public Bet(int cmd, String zoneName, String pluginName, long bet, long entryId, long sessionId) {
        super(zoneName, pluginName, new BetData(cmd, bet, entryId, sessionId));
    }

    @Getter
    @Setter
    public static class BetData extends Data {
        private final int aid = 1;
        private final long b;
        private final long eid;
        private final long sid;

        public BetData(int cmd, long eid, long sid) {
            this(cmd, eid, 10, sid);
        }

        public BetData(int cmd, long b, long eid, long sid) {
            super(cmd);

            this.eid = eid;
            this.sid = sid;
            this.b = b;
        }
    }

}
