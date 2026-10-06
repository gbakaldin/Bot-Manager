package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

public class AutoBet extends ActionRequestMessage {

    public AutoBet(int cmd, String zoneName, String pluginName, boolean isMini, List<BetEntryInfo> b) {
        super(zoneName, pluginName, new AutoBetData(cmd, isMini, b));
    }

    @Getter
    @Setter
    public static class AutoBetData extends Body {

        private final boolean iab = true;
        private final boolean isMini;

        private final List<BetEntryInfo> b;

        public AutoBetData(int cmd, boolean isMini, List<BetEntryInfo> b) {
            super(cmd);

            this.isMini = isMini;
            this.b = b;
        }

        public boolean getIsMini() {
            return isMini;
        }

    }

}
