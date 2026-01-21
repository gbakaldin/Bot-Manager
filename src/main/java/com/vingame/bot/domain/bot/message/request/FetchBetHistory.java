package com.vingame.bot.domain.bot.message.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import lombok.Getter;

public class FetchBetHistory extends ActionRequestMessage implements CmdAwareMessage {

    public FetchBetHistory(int cmd, String zoneName, String pluginName, int aid, int limit, int skip) {
        super(zoneName, pluginName, new FetchBetHistoryData(cmd, aid, limit, skip));
    }

    @Getter
    public static class FetchBetHistoryData extends Body {

        private final int aid;

        @JsonProperty("L")
        private final int limit;

        @JsonProperty("S")
        private final int skip;

        public FetchBetHistoryData(int cmd, int aid, int limit, int skip) {
            super(cmd);

            this.aid = aid;
            this.limit = limit;
            this.skip = skip;

        }

    }

}
