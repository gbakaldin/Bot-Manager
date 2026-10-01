package com.vingame.bot.domain.bot.message.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * The P_114 / RIK {@code stockPlugin} per-bet <b>commit</b> frame (RIK_114_BETTING_MINI
 * AD-27 amended, AD-29), transcribed verbatim from the legacy Node stock bot:
 * <pre>{@code {"cmd":13022,"sId":3793247}}</pre>
 *
 * <h2>What it is, and why it is sent after every bet</h2>
 *
 * Phase 2's wire capture showed every one of our {@code 13002} bets <b>accepted,
 * attributed and echoed in-round</b> and then <b>discarded at EndGame</b>
 * ({@code mbs:[]}, {@code obs} zeroed, server balance untouched). The one frame both
 * non-us clients send and we never did is this one — the browser once per round after
 * its last bet, the legacy bot ({@code Staging-098:/home/sgame/bot/*-rik-coins*.js})
 * in the very next statement after every bet:
 * <pre>{@code
 * socket.send(JSON.stringify([6,"MiniGame","stockPlugin",{"cmd":13002,"v":…,"sid":S,…}]));
 * socket.send(JSON.stringify([6,"MiniGame","stockPlugin",{"cmd":13022,"sId":S}]));
 * }</pre>
 * We follow the legacy bot's <b>per-bet</b> cadence (AD-29) rather than the browser's
 * once-per-round: the bot never knows which bet is its last (the strategy decides tick
 * by tick), the scenario has no bet-window-close hook, and the legacy bot ran on prod
 * with a commit after every bet and no server complaint. If V-19's captured response
 * shows the server wants exactly one per round, "commit on the first bet of each
 * {@code sid}" is the next experiment — not built ahead of the evidence.
 *
 * <h2>{@code @JsonProperty("sId")} + {@code @Getter(AccessLevel.NONE)} — both, no getter (AD-30)</h2>
 *
 * {@code sId} has the identical shape to {@link RikStockBet}'s {@code iAc}: a
 * {@code <lowercase><UPPERCASE>} key. Lombok's {@code @Getter} would generate
 * {@code getSId()}, and Jackson de-mangles a getter name by lower-casing the
 * <b>entire</b> leading uppercase run ({@code SI} → {@code si}), so the wire key would
 * become <b>{@code sid}</b>. That is worse than a typo: {@code sid} is <i>also</i> the
 * bet frame's session key, so a reviewer reading the wire would see a perfectly
 * plausible frame — and the server would ignore it, silently, reproducing the exact
 * accept-then-discard this phase exists to end.
 * <p>
 * The shipped shape is the only one correct under <b>every</b> Lombok configuration
 * (see {@link RikStockBet}'s measured table, Amendment B1): with no getter at all the
 * annotated field is the single property whatever the repo-root {@code lombok.config}
 * says. There is deliberately <b>no class-level {@code @Getter}/{@code @Setter}</b> on
 * {@link CommitData} — with a single field there is nothing for them to do, and the
 * field-level {@code NONE} would be the only thing standing between a class-level
 * annotation and B1's row 2. {@code RikStockCommitTest} pins the serialized key set
 * <b>and</b> {@code doesNotContain("\"sid\"")}; a round-trip test would pass with the
 * wrong key on both sides and prove nothing.
 * <p>
 * Body-only ({@code extends ActionRequestMessage}); the
 * {@code ["6","MiniGame","stockPlugin",{…}]} envelope is assembled by the ws-parser
 * from {@code zoneName} + {@code pluginName} + the {@link Body}, exactly as for the bet
 * (element 0 stays the string {@code "6"}, AD-26). Nothing inbound is modelled for the
 * server's reply — its shape has never been seen (OI-14); AD-7 stands.
 */
public class RikStockCommit extends ActionRequestMessage implements CmdAwareMessage {

    public RikStockCommit(int cmd, String zoneName, String pluginName, long sessionId) {
        super(zoneName, pluginName, new CommitData(cmd, sessionId));
    }

    /**
     * Exactly {@code {cmd, sId}} — nothing else. This is not a bet with a different
     * cmd: no {@code aid}, no {@code eid}, no {@code v}. Field order is irrelevant on
     * the wire; the key set is not.
     */
    public static class CommitData extends Body {

        /**
         * The session being committed to — the same value the preceding bet carried as
         * {@code sid}. Spelled {@code sId} on the wire, which is why the getter is
         * suppressed and the key is explicit (AD-30, class javadoc).
         */
        @Getter(AccessLevel.NONE)
        @JsonProperty("sId")
        private final long sId;

        public CommitData(int cmd, long sId) {
            super(cmd);
            this.sId = sId;
        }
    }
}
