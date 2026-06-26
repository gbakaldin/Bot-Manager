package com.vingame.bot.domain.bot.message.request;

import com.vingame.bot.domain.bot.message.TaiXiuMessageTypes;
import com.vingame.websocketparser.message.request.Body;
import lombok.AllArgsConstructor;

/**
 * Builder for outbound Tai Xiu request messages (TAI_XIU_BOT plan AD-12).
 * <p>
 * Unlike the betting-mini {@link Request} — which derives every outbound CMD as
 * {@code cmdPrefix + CODE} with a fixed {@code +2} gap between subscribe
 * ({@code +3000}) and bet ({@code +3002}) — Tai Xiu's CMDs are <b>bare fixed
 * literals</b>: subscribe {@code 1005}, bet {@code 1000} for P_116. That {@code −5}
 * gap cannot be produced by any single {@code cmdPrefix}, so a dedicated builder is
 * required (mirrors {@link SlotRequest}, which carries explicit fixed CMDs for the
 * same reason).
 * <p>
 * The two CMDs are <b>injected at construction</b> rather than read from static
 * constants (TAI_XIU_114_JACKPOT plan AD-4): {@code TaiXiuGameBot.buildRequest()}
 * passes {@code provider.subscribeCmd()} / {@code provider.betCmd()}, so the same
 * builder serves both the P_116 provider (1005/1000) and the P_114 jackpot provider
 * (1105/1100, {@code cmdOffset()==100}).
 * <p>
 * The <b>bet body shape is reused from betting-mini</b> ({@link Bet.BetData} =
 * {@code {cmd, aid:1, b, eid, sid}}) — it already matches the captured Tai Xiu bet
 * {@code {"cmd":1000,"b":...,"sid":...,"aid":1,"eid":1}} (AD-12). The only change vs
 * betting-mini is passing the fixed {@code cmd=1000} instead of {@code cmdPrefix+3002},
 * and {@code eid} is the chosen Tài/Xỉu entry (exactly 2 options) from the inherited
 * strategy decision; {@code aid} stays {@code 1} (OI-6).
 * <p>
 * The bodies are body-only ({@code extends Body} / {@code ActionRequestMessage}); the
 * {@code ["6","MiniGame","taixiuPlugin",{…}]} envelope is assembled by the ws-parser
 * from {@code zoneName} + {@code pluginName} + the {@link Body}.
 */
@AllArgsConstructor
public class TaiXiuRequest implements GameRequest {

    private final String pluginName;
    private final String zoneName;
    /** Effective subscribe CMD from the provider ({@link TaiXiuMessageTypes#subscribeCmd()}). */
    private final int subscribeCmd;
    /** Effective bet CMD from the provider ({@link TaiXiuMessageTypes#betCmd()}). */
    private final int betCmd;

    /**
     * Build a subscribe request emitting the provider's effective subscribe CMD
     * (1005 for P_116, 1105 for P_114) — no offset, no prefix.
     */
    @Override
    public SubscribeToLobbyMessage subscribe() {
        return new SubscribeToLobbyMessage(
                zoneName, pluginName, new Body(subscribeCmd));
    }

    /**
     * Build a bet request emitting the provider's effective bet CMD (1000 for P_116,
     * 1100 for P_114). Reuses {@link Bet.BetData}'s field shape
     * ({@code cmd, aid:1, b, eid, sid}).
     *
     * @param amount  the stake ({@code b})
     * @param entryId the chosen Tài/Xỉu entry ({@code eid}; exactly 2 options)
     * @param sid     the currently-tracked session id
     */
    @Override
    public Bet bet(long amount, int entryId, long sid) {
        return new Bet(betCmd, zoneName, pluginName, amount, entryId, sid);
    }
}
