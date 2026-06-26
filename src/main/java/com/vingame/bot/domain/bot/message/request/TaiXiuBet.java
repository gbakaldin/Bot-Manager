package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import lombok.Getter;
import lombok.Setter;

/**
 * Tai-Xiu-specific outbound bet body (TAI_XIU_114_JACKPOT plan AD-2).
 * <p>
 * The captured P_114 / {@code taixiuJackpotPlugin} bet body carries one extra field
 * over the shared betting-mini {@link Bet.BetData}:
 * <pre>{@code {"cmd":1100,"b":...,"aid":1,"eid":1,"sid":...,"a":false}}</pre>
 * The {@code a} field (an auto-bet flag; the bot never auto-bets, so it is always
 * {@code false}) does <b>not</b> appear on the 116 bet nor on any other betting-mini
 * product's bet. Adding {@code a} to the shared {@link Bet.BetData} would change every
 * product's bet, so this body is a dedicated copy of that shape plus {@code a},
 * constructed only on the 114 path ({@code TaiXiuRequest} with
 * {@code emitAutoBetFlag=true}). 116 continues to build the shared {@link Bet} and emits
 * no {@code a}, keeping that body byte-for-byte unchanged.
 * <p>
 * Body-only ({@code extends ActionRequestMessage}); the
 * {@code ["6","MiniGame","taixiuJackpotPlugin",{…}]} envelope is assembled by the
 * ws-parser from {@code zoneName} + {@code pluginName} + the {@link Body}.
 */
public class TaiXiuBet extends ActionRequestMessage implements CmdAwareMessage {

    public TaiXiuBet(int cmd, String zoneName, String pluginName,
                     long bet, long entryId, long sessionId, boolean autoBet) {
        super(zoneName, pluginName, new BetData(cmd, bet, entryId, sessionId, autoBet));
    }

    /**
     * The 114 bet body: the shared {@code {cmd, aid:1, b, eid, sid}} shape plus
     * {@code a}. Field order/names mirror {@link Bet.BetData} so the only serialized
     * difference is the added {@code a}.
     */
    @Getter
    @Setter
    public static class BetData extends Body {
        private final int aid = 1;
        private final long b;
        private final long eid;
        private final long sid;
        /** Auto-bet flag — {@code false} in the capture; the bot never auto-bets. */
        private final boolean a;

        public BetData(int cmd, long b, long eid, long sid, boolean a) {
            super(cmd);
            this.b = b;
            this.eid = eid;
            this.sid = sid;
            this.a = a;
        }
    }
}
