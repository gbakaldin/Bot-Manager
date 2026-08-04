package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import lombok.Getter;
import lombok.Setter;

/**
 * Outbound slot subscribe request, {@code cmd:1300}. Slot CMDs are fixed
 * constants with no {@code CODE + offset} arithmetic (SLOT_MACHINE_BOT plan
 * AD-1). The only payload field beyond the inherited {@code cmd} is {@code gid}.
 */
public class SlotSubscribe extends ActionRequestMessage implements CmdAwareMessage {

    public SlotSubscribe(int cmd, String zoneName, String pluginName, int gid) {
        super(zoneName, pluginName, new Data(cmd, gid));
    }

    @Getter
    @Setter
    public static class Data extends Body {
        private final int gid;

        public Data(int cmd, int gid) {
            super(cmd);
            this.gid = gid;
        }
    }
}
