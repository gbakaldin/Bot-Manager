package com.vingame.bot.domain.bot.message.g4.win79.cashout;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.vingame.bot.domain.bot.message.request.CmdAwareMessage;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * 119 cash-out bet, {@code X501}, transcribed from the real client
 * (2026-10-05, {@code balloonPlugin}):
 * <pre>{@code ["6","MiniGame","balloonPlugin",{"cmd":1501,"b":100000,"aid":1,"sL":2,"aS":false,"aSt":false}]}</pre>
 * <b>No {@code sid}</b> — the server assigns it and reports it on the first progress
 * frame ({@code docs/plans/CASHOUT_BOT.md} AD-10). {@code sL} / {@code aS} /
 * {@code aSt} are sent verbatim as constants; nothing reads or depends on them (OI-4).
 *
 * <p>The envelope's element 0 is ws-parser's string {@code "6"} where the real client
 * sends the number {@code 6}; every product's request goes out the same way.
 *
 * <p><b>{@code sL}, {@code aS}, {@code aSt} need {@code @JsonProperty} and no getter</b>:
 * Lombok's {@code getSL()} / {@code getAS()} / {@code getASt()} would de-mangle to
 * {@code sl} / {@code as} / {@code ast} — the {@code RikStockBet.iAc} trap.
 */
public class Win79CashoutBet extends ActionRequestMessage implements CmdAwareMessage {

    public Win79CashoutBet(int cmd, String zoneName, String pluginName, long amount) {
        super(zoneName, pluginName, new Data(cmd, amount));
    }

    @Getter
    @JsonPropertyOrder({"cmd", "b", "aid", "sL", "aS", "aSt"})
    public static class Data extends Body {

        private final long b;

        private final int aid = 1;

        @Getter(AccessLevel.NONE)
        @JsonProperty("sL")
        private final int sL = 2;

        @Getter(AccessLevel.NONE)
        @JsonProperty("aS")
        private final boolean aS = false;

        @Getter(AccessLevel.NONE)
        @JsonProperty("aSt")
        private final boolean aSt = false;

        public Data(int cmd, long b) {
            super(cmd);
            this.b = b;
        }
    }
}
