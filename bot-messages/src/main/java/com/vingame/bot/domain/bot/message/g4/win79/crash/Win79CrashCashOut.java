package com.vingame.bot.domain.bot.message.g4.win79.crash;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.vingame.bot.domain.bot.message.request.CmdAwareMessage;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import lombok.Getter;

/**
 * 119 Avatar cash-out, {@code 1703}, transcribed from the real client (2026-10-05, L21):
 * <pre>{@code {"cmd":1703,"sid":1638119,"aid":1,"eid":1}}</pre>
 * Same {@code sid} and {@code eid} as the bet ({@code docs/plans/AVIATOR_BOT.md} AD-10).
 */
public class Win79CrashCashOut extends ActionRequestMessage implements CmdAwareMessage {

    public Win79CrashCashOut(int cmd, String zoneName, String pluginName, long sid, int eid) {
        super(zoneName, pluginName, new Data(cmd, sid, eid));
    }

    @Getter
    @JsonPropertyOrder({"cmd", "sid", "aid", "eid"})
    public static class Data extends Body {

        private final long sid;

        private final int aid = 1;

        private final int eid;

        public Data(int cmd, long sid, int eid) {
            super(cmd);
            this.sid = sid;
            this.eid = eid;
        }
    }
}
