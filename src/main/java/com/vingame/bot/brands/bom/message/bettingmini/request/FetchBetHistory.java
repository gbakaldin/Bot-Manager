package com.vingame.bot.brands.bom.message.bettingmini.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.webocketparser.message.request.ActionRequestMessage;
import com.vingame.webocketparser.message.request.Data;
import lombok.Getter;

public class FetchBetHistory extends ActionRequestMessage {

    public FetchBetHistory(int cmd, String zoneName, String pluginName, int aid, int limit, int skip) {
        super(zoneName, pluginName, new FetchBetHistoryData(cmd, aid, limit, skip));
    }

    @Getter
    public static class FetchBetHistoryData extends Data {

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
