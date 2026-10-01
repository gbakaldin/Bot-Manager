package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.HasJackpot;
import com.vingame.bot.domain.bot.message.HasJackpotPool;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * {@code ziczacPlugin} (RIK / P_114 Plinko) EndGame — CMD {@code 3006 + offset},
 * i.e. {@code 12006} at offset 9000.
 * <p>
 * Captured shape is
 * {@code {"p":{…},"obs":[],"tJpV":684300,"cmd":12006,"jps":["-","-","-"],"iJp":false,"sid":1995089,"mbs":[{"p":24,"b":50000,"r":15000,"odd":0.3},…]}}
 * — an {@code mbs} array of <b>balls</b> (see {@link RikZicZacBallResult}), a running
 * jackpot meter, and a {@code p} object that is deliberately not modelled (below).
 *
 * <h2>Why this is not {@link RikEndGameMessage}</h2>
 *
 * The two shapes are disjoint where it matters. {@link RikEndGameMessage} reads its
 * own win from {@code mbs[].wm} or a top-level {@code wm}; <b>ziczac has neither</b>,
 * so the generic 114 class reports {@code winningsFor() == 0} on every round while
 * {@code betAmountFor()} happens to work — the exact silhouette of the "bets never
 * settle / host not whitelisted" failure {@code CLAUDE.md} records as having been
 * misdiagnosed twice. Rather than add a third branch to that class's accessor chain,
 * ziczac gets its own classes and the two shipped 114 games stay byte-for-byte
 * untouched.
 *
 * <h2>{@code winningsFor} is {@code sum(mbs[].r)} — and deliberately NOT {@code p.wm}</h2>
 *
 * The frame carries two numerically identical candidates: {@code sum(mbs[].r)} and
 * {@code p.wm}, equal in <b>6 of 6</b> captured rounds with a bet. Only one of them
 * survives scrutiny.
 * <ul>
 *   <li>{@code mbs} is the backend's {@code MAIN_BET_ARRAY} — the constant pair
 *       ({@code MAIN_BET_ARRAY = "mbs"} / {@code OTHER_BET_ARRAY = "obs"}) that
 *       settled the same scope question for the other two 114 games — so it is
 *       <b>proven own-scoped by the source</b>, not by a coincidence of values. And
 *       {@code r = b × odd} holds in 81/81 balls.</li>
 *   <li>{@code p} carries {@code uid} / {@code u} / <b>{@code dn}</b>: a display name
 *       is what a <i>room announcement</i> needs and what a frame addressed to me does
 *       not. The room-wide {@code 12019} {@code tbps[]} carries {@code dn} the same
 *       way.</li>
 * </ul>
 * <b>The capture cannot tell the two scopes apart</b>, because this account was the
 * only bettor in every captured round — which is precisely the coincidence that made
 * an earlier RIK decision wrong and shipped a confidently-wrong rationale
 * ({@code RIK_114_BETTING_MINI} Amendment A1). Reading {@code p.wm} would replay that
 * mistake on a different field one plan later. Since the numbers agree, choosing
 * {@code mbs} costs nothing today and is right if the scopes ever diverge; if a
 * two-account capture later proves {@code p} is own-scoped, nothing here changes.
 * <p>
 * <b>Returned verbatim: it is a gross return INCLUDING the stake.</b> {@code odd} is
 * a total multiplier, so the loss round {@code 1995087} stakes 10 700 000 and reports
 * 6 420 000 — positive, and less than the stake. Netting the stake off turns the
 * ~0.96 RTP anchor into ~-0.04. A round with no bet has {@code mbs: []} and reports
 * {@code 0}; {@code onEndGame} guards on {@code w > 0}.
 *
 * <h2>{@link HasJackpotPool} IS wired; {@link HasJackpot} is NOT</h2>
 *
 * {@code tJpV} is a <b>positively-evidenced rising pool meter</b> on this game: it
 * climbs monotonically across the capture (200 200 → 204 400 → 406 800 → 513 800 →
 * 674 300 → 684 300 → 689 300), and {@code 12007}'s {@code jpv} ticks up
 * <i>within</i> a round as stake accumulates. That is exactly what
 * {@link HasJackpotPool} documents, and it is the non-zero meter the other two 114
 * games still lack — theirs read {@code 0} in every sample, so their class stays
 * unwired. <b>This closes that open item for ziczac only.</b>
 * <p>
 * The per-user marker stays off: {@code iJp} was {@code false} in 8/8 rounds and
 * {@code p.jwm} was {@code 0} in 6/6, so <b>no discharge was ever observed</b> and
 * {@code jackpotFor} would be a guess — including whether a discharge would be folded
 * into {@code mbs[].r} or reported separately (OI-5). One capture of a hit settles it.
 * Both omissions are pinned by a test.
 *
 * <h2>{@link HasCrowdBets} is NOT implemented</h2>
 *
 * {@code obs} was <b>empty in 8/8 rounds</b>, including the 6 this account bet in,
 * and there is no {@code bs} key on this frame at all. The real room feed rides
 * {@code 12007} and {@code 12019}, both outside the four-CODE contract. So
 * crowd-aware coordination and {@code optionAffinities} are inert on ziczac — not
 * broken. (The user reports {@code obs} does carry payouts when populated; nothing in
 * this capture shows it populated, OI-4.)
 *
 * <h2>{@code p}, {@code obs}, {@code jps} and the out-of-contract frames are not modelled</h2>
 *
 * An unmodelled field can never fail deserialization; a modelled one whose real type
 * turns out to be a string or a fraction fails the <b>whole frame</b>. {@code p} is
 * the room-announcement-shaped object above; {@code obs} was empty in 8/8;
 * {@code jps} is three one-character strings ({@code ['I','-','-']},
 * {@code ['R','-','K']}) of unknown meaning. All of it is preserved in the committed
 * capture, which is where an unknown belongs.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RikZicZacEndGameMessage extends EndGameMessage
        implements HasBotWinnings, HasBetTotals, HasJackpotPool {

    private long sid;

    /**
     * This connection's own balls for the round — the backend's
     * {@code MAIN_BET_ARRAY}. Empty (not absent) on a round this account did not bet.
     */
    private List<RikZicZacBallResult> mbs;

    /** The live running jackpot pool meter. Rising and non-zero on this game. */
    private long tJpV;

    /**
     * Whether this round discharged the jackpot. {@code false} in 8/8 captured
     * rounds — modelled because it is real, deliberately not wired to
     * {@link HasJackpot} (see the class javadoc).
     */
    private boolean iJp;

    @JsonCreator
    public RikZicZacEndGameMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("sid") long sid,
            @JsonProperty("mbs") List<RikZicZacBallResult> mbs,
            @JsonProperty("tJpV") long tJpV,
            @JsonProperty("iJp") boolean iJp) {
        super(cmd);
        this.sid = sid;
        this.mbs = mbs;
        this.tJpV = tJpV;
        this.iJp = iJp;
    }

    @Override
    public long getSessionId() {
        return sid;
    }

    /**
     * This bot's gross winnings for the round: {@code sum(mbs[].r)}.
     * <p>
     * <b>Not {@code p.wm}</b>, even though the two are equal in 6/6 captured rounds —
     * see the class javadoc for why that equality is not evidence. Returned verbatim
     * as a gross return <b>including the stake</b>; never net the stake off it.
     * <p>
     * {@code userName} is ignored: the frame is addressed to this connection, the
     * same reasoning every other product's implementation records. A round with no
     * bet yields {@code mbs: []} → {@code 0}, and {@code onEndGame} guards on
     * {@code w > 0}.
     */
    @Override
    public long winningsFor(String userName) {
        if (mbs == null) {
            return 0L;
        }
        return mbs.stream().mapToLong(RikZicZacBallResult::r).sum();
    }

    /**
     * Total this bot staked in the round: {@code sum(mbs[].b)}.
     * <p>
     * <b>Model-independent.</b> A bet is {@code (b, c)} — a per-ball stake and a ball
     * count — and it is not settled whether a second bet stacks onto existing balls
     * or appends new ones (OI-2). Both readings agree that the round's total stake is
     * {@code sum(mbs[].b)}, which is why this accessor does not depend on the answer.
     * <p>
     * {@code userName} ignored — recipient-personalized payload.
     */
    @Override
    public long betAmountFor(String userName) {
        if (mbs == null) {
            return 0L;
        }
        return mbs.stream().mapToLong(RikZicZacBallResult::b).sum();
    }

    /**
     * Number of own bets in the round — on this game that is <b>BALLS, not
     * clicks</b>.
     * <p>
     * This differs from every other product's meaning of the same metric and is worth
     * saying out loud: one click carrying {@code c = 5} contributes <b>five</b>
     * entries here, so the server-confirmed count would exceed the locally counted
     * sends by a factor of {@code c}. {@link HasBetTotals} already documents that such
     * a divergence is legitimate. Under the pinned {@code c = 1} the two agree
     * exactly, which is what makes {@code bot_bet_amount_total ≈
     * bot_bets_placed_total × stake} a usable check on staging.
     * <p>
     * Balls with {@code b == 0} are not counted, matching the other 114 class.
     */
    @Override
    public int betCountFor(String userName) {
        if (mbs == null) {
            return 0;
        }
        return (int) mbs.stream().filter(e -> e.b() > 0L).count();
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@code tJpV}, the live running pool meter — see the class javadoc for the
     * evidence that it is a pool rather than a per-user payout on this game. Inert
     * unless an operator sets {@code Game.jackpotScaleEnabled}, and a {@code 0} pool
     * is treated downstream as "not observed" → neutral.
     */
    @Override
    public long jackpotPool() {
        return tJpV;
    }
}
