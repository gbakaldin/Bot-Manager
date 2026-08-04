package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Outbound slot spin request, {@code cmd:1302} (the spin request and the spin
 * result share cmd 1302; inbound matching uses {@code typeOf(RECEIVED)} so the
 * bot does not match its own outbound echo — SLOT_MACHINE_BOT plan AD-1).
 * <p>
 * Note the {@code ls}-name clash with the {@code 1300} subscribe response: here
 * {@code ls} = the selected winline indices ({@code List<Integer>}, e.g.
 * {@code [0..numLines-1]}); on the subscribe response {@code ls} = winline
 * definitions. Different fields on different classes — see AD-8.
 */
public class SlotSpin extends ActionRequestMessage implements CmdAwareMessage {

    public SlotSpin(int cmd, String zoneName, String pluginName, int gid, long bet, List<Integer> ls) {
        super(zoneName, pluginName, new Data(cmd, gid, bet, ls));
    }

    @Getter
    @Setter
    public static class Data extends Body {
        private final int aid = 1;
        private final long b;
        private final int gid;
        /** Selected winline indices for this spin (AD-8). */
        private final List<Integer> ls;

        public Data(int cmd, int gid, long bet, List<Integer> ls) {
            super(cmd);
            this.gid = gid;
            this.b = bet;
            this.ls = ls;
        }
    }
}
