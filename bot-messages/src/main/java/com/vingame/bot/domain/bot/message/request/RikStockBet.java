package com.vingame.bot.domain.bot.message.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * The P_114 / RIK {@code stockPlugin} outbound bet body (RIK_114_BETTING_MINI AD-22),
 * transcribed verbatim from the wire capture:
 * <pre>{@code {"cmd":13002,"v":1000,"sid":3793247,"aid":1,"eid":0,"iAc":true}}</pre>
 *
 * <h2>The stake key is {@code v} and there is NO {@code b}</h2>
 *
 * The shared {@link Bet.BetData} sends {@code b}, which every other betting-mini
 * product settles with — including P_114's own {@code taixiuMd5Plugin}. On
 * {@code stockPlugin} it stakes nothing: 156 consecutive settled rounds with
 * {@code bot_bets_placed_total} flat at {@code 0} while the txmd5 control confirmed 32
 * bets through the identical {@code Request}/{@link Bet} on the same box in the same
 * window (Amendment A3). One product, one brand, one host, one bet class, two games,
 * one settles — which rules out whitelisting, the envelope, the environment and the
 * credentials in a single observation.
 * <p>
 * Emitting {@code b} <b>and</b> {@code v} together was considered and rejected (OI-3):
 * it sends a frame no real client sends, and if it then worked we would not learn which
 * key was read. Fidelity over hedging, the rule RIK_114_BETTING_MINI AD-9 set
 * (its sibling {@link GameRequestFactory} cites a different document's AD-9).
 * <p>
 * {@code iAc} was {@code true} in all 8 exported outbound 13002 frames across 3 rounds.
 * Its meaning is unknown, so it is transcribed rather than interpreted. {@code aid} is
 * the literal {@code 1}, as on every other product; {@code eid} is the strategy's chosen
 * option and is meaningful here (stock has two doors, Up and Down).
 *
 * <h2>{@code @JsonProperty("iAc")} is mandatory, and its absence is silent</h2>
 *
 * Jackson de-mangles a getter name by lower-casing the <b>whole</b> leading uppercase
 * run, so both Lombok's generated {@code isIAc()} and a hand-rolled {@code getIAc()}
 * serialize the key as <b>{@code iac}</b>. No getter-naming trick can produce
 * {@code iAc}; only an explicit {@code @JsonProperty} can. (The codebase's existing
 * workaround for the same class of bug, {@code AutoBet.AutoBetData.getIsMini()}, does
 * <b>not</b> transfer: its field is {@code isMini}, whose leading {@code i} is followed
 * by a lowercase letter, so there is no uppercase run to fold.)
 *
 * <h2>Why the getter is suppressed with {@code @Getter(AccessLevel.NONE)}</h2>
 *
 * <b>Not</b> because keeping it would make the frame carry both keys — under this
 * repo's Lombok configuration it would not. Measured on this module's classpath
 * (jackson-databind <b>2.15.2</b>, 2026-09-17) for a private {@code boolean iAc},
 * "annotated" meaning {@code @JsonProperty("iAc")} on the <i>field</i>:
 *
 * <pre>
 *   shape                                        repo lombok.config        no copyableAnnotations
 *   ------------------------------------------------------------------------------------------
 *   annotated + Getter(NONE)      (SHIPPED)      {"iAc":true}              {"iAc":true}
 *   annotated + Lombok's isIAc()                 {"iAc":true}              {"iac":true,"iAc":true}
 *   annotated + hand-written isIAc()             {"iac":true,"iAc":true}   {"iac":true,"iAc":true}
 *   Lombok's isIAc() only, no annotation         {"iac":true}              {"iac":true}
 *   hand-rolled getIAc(), no annotation          {"iac":true}              {"iac":true}
 * </pre>
 *
 * Row 2 is correct only because the <b>repo-root {@code lombok.config}</b> carries
 * {@code lombok.copyableAnnotations += com.fasterxml.jackson.annotation.JsonProperty}:
 * Lombok stamps the field's annotation onto the getter it generates, and Jackson merges
 * the two into one correctly-named property. Lombok cannot stamp a getter a <b>human</b>
 * writes, which is why row 3 — the shape someone reaches for when "simplifying" the
 * Lombok away — really does emit both keys.
 * <p>
 * So the reason to suppress the getter is not the double key; it is that row 1 is the
 * only row that is correct <b>under every configuration</b>. With no getter at all the
 * annotated field is the single property whatever {@code lombok.config} says, so this
 * frame stops depending on a two-line file two directories up that nothing else in this
 * feature mentions and that a future edit could change. The flag has no reader in this
 * codebase, so the getter buys nothing to weigh against that.
 * <p>
 * Nothing fails when this is wrong: the frame ships looking correct and the server
 * ignores the key it does not know — which is precisely the silent no-op this whole
 * phase exists to end. {@code RikStockBetTest} pins the serialized <b>key set</b> (not a
 * round-trip, which would pass happily with {@code iac} on both sides).
 * <p>
 * Body-only ({@code extends ActionRequestMessage}); the
 * {@code ["6","MiniGame","stockPlugin",{…}]} envelope is assembled by the ws-parser from
 * {@code zoneName} + {@code pluginName} + the {@link Body}. Element 0 is the string
 * {@code "6"} where the real client sends the number {@code 6} — that is ws-parser's
 * {@code String.valueOf(...)} and is <b>not</b> the fault (AD-26): txmd5 settles through
 * the identical envelope.
 */
public class RikStockBet extends ActionRequestMessage implements CmdAwareMessage {

    public RikStockBet(int cmd, String zoneName, String pluginName,
                       long bet, long entryId, long sessionId) {
        super(zoneName, pluginName, new BetData(cmd, bet, entryId, sessionId));
    }

    /**
     * Exactly {@code {cmd, v, sid, aid, eid, iAc}} — no {@code b}. Field order is
     * irrelevant on the wire; the key set is not.
     */
    @Getter
    @Setter
    public static class BetData extends Body {

        /** The stake. On this game the server reads {@code v}, never {@code b} (AD-22). */
        private final long v;
        private final long sid;
        private final int aid = 1;
        private final long eid;

        /**
         * {@code true} in 8/8 captured outbound frames; meaning unknown, transcribed
         * not interpreted. The Lombok getter is suppressed on purpose — see the class
         * javadoc for the measured table: with no getter the annotated field is the
         * single property under <i>every</i> Lombok configuration, which is what this
         * frame's correctness should rest on rather than on the repo-root
         * {@code lombok.config}.
         */
        @Getter(AccessLevel.NONE)
        @JsonProperty("iAc")
        private final boolean iAc = true;

        public BetData(int cmd, long v, long eid, long sid) {
            super(cmd);

            this.v = v;
            this.eid = eid;
            this.sid = sid;
        }
    }
}
