package com.vingame.bot.domain.bot.message.g4.win79.crash;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.vingame.bot.domain.bot.message.request.CmdAwareMessage;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import lombok.Getter;

/**
 * 119 Avatar bet, {@code 1702}, transcribed from the real client (2026-10-05, L16):
 * <pre>{@code {"cmd":1702,"b":10000,"sid":1638119,"aid":1,"eid":1}}</pre>
 * {@code sid} is the round from the round start; {@code eid} the runner drawn for the
 * round ({@code docs/plans/AVIATOR_BOT.md} AD-2 / AD-10). {@code aid} is the constant 1.
 *
 * <p>The envelope's element 0 is ws-parser's string {@code "6"} where the real client
 * sends the number {@code 6}; every product's request goes out the same way.
 */
public class Win79CrashBet extends ActionRequestMessage implements CmdAwareMessage {

    public Win79CrashBet(int cmd, String zoneName, String pluginName, long amount, long sid, int eid) {
        super(zoneName, pluginName, new Data(cmd, amount, sid, eid));
    }

    @Getter
    @JsonPropertyOrder({"cmd", "b", "sid", "aid", "eid"})
    public static class Data extends Body {

        private final long b;

        private final long sid;

        private final int aid = 1;

        private final int eid;

        public Data(int cmd, long b, long sid, int eid) {
            super(cmd);
            this.b = b;
            this.sid = sid;
            this.eid = eid;
        }
    }
}
