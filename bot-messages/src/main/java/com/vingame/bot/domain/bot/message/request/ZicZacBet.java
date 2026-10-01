package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import lombok.Getter;

/**
 * The P_114 / RIK {@code ziczacPlugin} (Plinko) outbound bet body
 * (RIK_114_ZICZAC AD-9 / AD-10 / AD-11, Amendment A1 item 1), transcribed verbatim
 * from the wire capture
 * ({@code bot-messages/src/test/resources/captures/rik-ziczacPlugin-12000.jsonl}):
 * <pre>{@code {"cmd":12002,"b":60000,"c":1,"sid":1995084,"aid":1}}</pre>
 *
 * <h2>A per-BALL stake plus a ball COUNT, and no {@code eid} at all</h2>
 *
 * {@code b} is the stake <i>per ball</i> and {@code c} is how many balls that stake
 * buys; the server debits {@code b × c}. Plinko has nothing to pick — every ball drops
 * from the same slot and the board decides the bucket — so there is no option to name
 * and the real client sends <b>no {@code eid}</b>. The shared {@link Bet.BetData}
 * sends {@code b} + {@code eid} and no {@code c}; on this game that frame is
 * accepted in-round, echoed in the {@code 12002} UpdateBet, and then <b>discarded at
 * settlement</b> — {@code confirmed staked: 0 | total win: 0} on 38/38 EndGames
 * (Amendment A1 §4). This body is what ends that.
 * <p>
 * The caller's {@code entryId} is discarded at the request boundary
 * ({@link ZicZacRequest#bet}); this class simply has no field for it (AD-11). The
 * {@code eid} that later appears in the {@code 12002} echo's {@code bs[]} is the
 * <i>server's</i> slot index for that ball, assigned on append (Amendment A1 §5) — it
 * is not ours and never was. No {@code v}, no {@code iAc}: those are
 * {@code stockPlugin}'s keys ({@link RikStockBet}), not this game's.
 *
 * <h2>{@code c} is pinned to {@code 1} and is not configurable (AD-10)</h2>
 *
 * One engine bet = one ball at the strategy's amount. This is what keeps the engine's
 * arithmetic true, not a simplification: {@code creditBalance(amount)},
 * {@code memory.recordBetSent(sid, option, amount)} and
 * {@code RoundResult.balanceDelta = payout − sum(bets)} all assume a send debits
 * exactly {@code amount}. With {@code c = 1} the server debits {@code b × 1 = amount}
 * and all three stay exact; with {@code c > 1} every one of them is wrong by a factor
 * of {@code c} until the engine is taught about it (OI-6). The per-round volume knob
 * already exists and is {@code maxBetsPerRound} — five balls is five ticks.
 *
 * <h2>Why there is NO {@code @JsonProperty} here — and why that says nothing about {@link RikStockBet}</h2>
 *
 * Every key on this frame — {@code cmd}, {@code b}, {@code c}, {@code sid},
 * {@code aid} — is <b>all-lowercase</b>. Lombok's getters are {@code getB()},
 * {@code getC()}, {@code getSid()}, {@code getAid()}, and Jackson de-mangles each to
 * exactly the key the wire wants; there is no leading uppercase run for Jackson to
 * fold, so the {@code <lowercase><UPPERCASE>} trap that {@link RikStockBet}'s
 * {@code iAc} and {@link RikStockCommit}'s {@code sId} fall into
 * (RIK_114_BETTING_MINI AD-30 / Amendment B1) <b>cannot bite this class</b>. Adding
 * {@code @JsonProperty} "defensively" would be noise. Conversely, <b>do not remove it
 * from {@code RikStockBet} or {@code RikStockCommit} by analogy with this class</b>:
 * their keys are mixed-case and the annotation is the only thing that produces the
 * right wire key there. The rule is per key, not per product.
 * <p>
 * The class-level {@code @Getter} is therefore correct and necessary — without any
 * getter a private, unannotated field is invisible to Jackson at default visibility
 * and the body would serialize as {@code {"cmd":…}} alone. {@code ZicZacBetTest} pins
 * the serialized <b>key set</b>, not a round-trip.
 * <p>
 * Body-only ({@code extends ActionRequestMessage}); the
 * {@code ["6","MiniGame","ziczacPlugin",{…}]} envelope is assembled by the ws-parser
 * from {@code zoneName} + {@code pluginName} + the {@link Body}. Element 0 is the
 * string {@code "6"} where the real client sends the number {@code 6} — ws-parser's
 * {@code String.valueOf(...)}, ruled out as a cause on this product because
 * {@code taixiuMd5Plugin} settles through the identical envelope
 * (RIK_114_BETTING_MINI AD-26).
 */
public class ZicZacBet extends ActionRequestMessage implements CmdAwareMessage {

    public ZicZacBet(int cmd, String zoneName, String pluginName, long bet, long sessionId) {
        super(zoneName, pluginName, new BetData(cmd, bet, sessionId));
    }

    /**
     * Exactly {@code {cmd, b, c, sid, aid}} — no {@code eid}, no {@code v}, no
     * {@code iAc}. Field order is irrelevant on the wire; the key set is not.
     */
    @Getter
    public static class BetData extends Body {

        /** The stake <b>per ball</b>. {@code mB = 50 000 000} is the per-ball maximum on this game. */
        private final long b;

        /** The ball count. Always {@code 1} (AD-10) — see the class javadoc for why. */
        private final int c = 1;

        private final long sid;

        private final int aid = 1;

        public BetData(int cmd, long b, long sid) {
            super(cmd);

            this.b = b;
            this.sid = sid;
        }
    }
}
