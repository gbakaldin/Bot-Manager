package com.vingame.bot.brands.bom.message.bettingmini.request;


import com.vingame.webocketparser.message.request.ActionRequestMessage;
import com.vingame.webocketparser.message.request.Data;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FetchAllPlayers extends ActionRequestMessage {

    public FetchAllPlayers(int cmd, String zoneName, String pluginName, int lm, int of, Map<String, Boolean> filterCriteria) {
        super(zoneName, pluginName, new FetchAllPlayerData(cmd, lm, of, filterCriteria));
    }

    @Getter
    public static class FetchAllPlayerData extends Data {

        private final int lm;
        private final int of;
        private final List<FilterItem> sr;


        public FetchAllPlayerData(int cmd, int lm, int of, Map<String, Boolean> filterCriteria) {
            super(cmd);

            this.lm = lm;
            this.of = of;

            this.sr = new ArrayList<>();
            for (Map.Entry<String, Boolean> entry : filterCriteria.entrySet()) {
                this.sr.add(new FilterItem(entry.getKey(), entry.getValue()));
            }
        }

    }

    @Getter
    public static class FilterItem {

        private final String f;
        private final String o;

        public FilterItem(String f, boolean ascendingOrder) {
            this.f = f;
            this.o = ascendingOrder ? "asc" : "desc";
        }

    }
}
