package com.vingame.bot.domain.bot.message.g4.win79.cashout;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.vingame.bot.domain.bot.message.request.CmdAwareMessage;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * 119 cash-out request, {@code X502}:
 * <pre>{@code {"cmd":1502,"sid":<server sid>,"aid":1,"aSt":false}}</pre>
 * ({@code docs/plans/CASHOUT_BOT.md} AD-10). {@code sid} is the server's id for the
 * live bet, bound from its frames. See {@link Win79CashoutBet} for why {@code aSt}
 * carries {@code @JsonProperty} and no getter.
 */
public class Win79CashoutCashOut extends ActionRequestMessage implements CmdAwareMessage {

    public Win79CashoutCashOut(int cmd, String zoneName, String pluginName, long sid) {
        super(zoneName, pluginName, new Data(cmd, sid));
    }

    @Getter
    @JsonPropertyOrder({"cmd", "sid", "aid", "aSt"})
    public static class Data extends Body {

        private final long sid;

        private final int aid = 1;

        @Getter(AccessLevel.NONE)
        @JsonProperty("aSt")
        private final boolean aSt = false;

        public Data(int cmd, long sid) {
            super(cmd);
            this.sid = sid;
        }
    }
}
