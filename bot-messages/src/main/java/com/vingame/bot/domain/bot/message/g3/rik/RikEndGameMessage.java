package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vingame.bot.domain.bot.coordination.CrowdOption;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * RIK (P_114) EndGame (CMD {@code 3006 + offset}; {@code 13006} for
 * {@code stockPlugin} at offset 10000, {@code 7006} for {@code taixiuMd5Plugin} at
 * offset 4000).
 * <p>
 * Two captured shapes, and they are disjoint:
 * <ul>
 *   <li>{@code stockPlugin} —
 *       {@code {"obs":[…],"ps":[],"cmd":13006,"d1":60,"d2":0,"sid":3793249,"d3":0,"bPl":[…],"mbs":[{…}]}}.
 *       {@code d1} is the round result, a signed percent ({@code -75} / {@code +60}),
 *       and {@code d2}/{@code d3} are constant {@code 0}. Own stake and own win ride
 *       {@code mbs}; there is no top-level {@code wm}.</li>
 *   <li>{@code taixiuMd5Plugin} —
 *       {@code {"rs":…,"bs":[…],"wm":198000,"cmd":7006,"d1":5,"d2":4,"d3":4,"iJp":false,"tJpV":0,"md5":…,"sid":2473044}}.
 *       {@code d1}/{@code d2}/{@code d3} are the three dice; own stake rides
 *       {@code bs[].b} and own win the <b>top-level {@code wm}</b>; there is no
 *       {@code mbs}. On a round this account did not bet, {@code wm} and
 *       {@code bs[].b} are <b>absent entirely</b> rather than zero.</li>
 * </ul>
 * Which of {@code eid} 0/1 is "up" on stock is irrelevant to a bettor and is not
 * decoded here.
 *
 * <h2>Payout markers: {@link HasBotWinnings} and {@link HasBetTotals} are wired</h2>
 *
 * {@code mbs} is the backend's {@code MAIN_BET_ARRAY} and {@code obs} its
 * {@code OTHER_BET_ARRAY}, so {@code mbs} is this connection's own bet and the
 * payout fields are per-recipient. See {@link RikMainBetSummary}. Consequently
 * <b>P_114 reports real {@code bot_winnings_total}, {@code bot_bets_placed_total}
 * and {@code bot_bet_amount_total}</b>, and the RTP those produce is anchored at
 * <b>~0.98 on both captured games</b> — sustained above 1.0 is the RTP-anomaly
 * health condition, not luck.
 *
 * <h2>The jackpot markers are still NOT implemented (AD-19)</h2>
 *
 * Not {@code HasJackpot}, not {@code HasJackpotPool}, and that omission is pinned by
 * a test. {@code taixiuMd5Plugin} does carry {@code iJp} / {@code tJpV} /
 * {@code tJpv2} (modelled below, because they are real), but {@code tJpV} was
 * {@code 0} in every sample and there is no {@code jpV} anywhere — and {@code tJpV}'s
 * meaning is <b>inverted between product families</b> (per-user payout on some,
 * running pool meter on others; {@code Win79EndGameMessage} devotes a javadoc section
 * to that trap). Wiring it on a meter that has only ever read zero would be a guess.
 * Costs nothing to wait: {@code jackpotScaleEnabled} is per-game opt-in and off, and
 * the scaler treats a {@code 0} pool as "not observed". One capture with a non-zero
 * meter makes it a two-line change (OI-6).
 *
 * <h2>{@code ps}, {@code bPl}, {@code rs} and {@code md5} are not modelled (AD-8)</h2>
 *
 * {@code ps} was <b>empty in all three captured stock rounds even though this account
 * bet in all three</b>, so it is demonstrably not Win79's per-player settlement list
 * and its element type is unknown. A typed model would be a guess and a
 * {@code List<Object>} would hand the next reader a list of {@code LinkedHashMap}s
 * that looks usable and is not; it is left to {@code FAIL_ON_UNKNOWN_PROPERTIES =
 * false} plus {@link JsonIgnoreProperties}. {@code bPl} is the chart's price series
 * and nothing reads it; {@code rs} is {@code taixiuMd5Plugin}'s provably-fair reveal
 * string, which carries the dice as {@code {5-4-4}} and therefore duplicates
 * {@code d1}/{@code d2}/{@code d3} (OI-7). Note that modelling any of them would not
 * even make them visible in the logs — these classes have no {@code toString} and the
 * aggregated-session sample line prints an object identity.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RikEndGameMessage extends EndGameMessage implements HasCrowdBets, HasBotWinnings, HasBetTotals {

    private long sid;

    /**
     * Round result. On {@code stockPlugin} {@code d1} is a signed percent move and
     * {@code d2}/{@code d3} are always 0; on {@code taixiuMd5Plugin} the three are
     * the dice ({@code 5-4-4}).
     */
    private int d1;
    private int d2;
    private int d3;

    /** End-of-round crowd distribution on {@code stockPlugin}. Entry {@code b} is absent, hence 0. */
    private List<RikBetInfo> obs;

    /**
     * End-of-round crowd on {@code taixiuMd5Plugin}, which sends no {@code obs}.
     * Unlike {@code obs} these entries <b>do</b> carry the own-stake {@code b}, which
     * is what {@link #betAmountFor(String)} falls back to. See {@link #crowdBets()}.
     */
    private List<RikBetInfo> bs;

    /**
     * This connection's own bet summary — the backend's {@code MAIN_BET_ARRAY}.
     * Present on {@code stockPlugin}, absent on {@code taixiuMd5Plugin}.
     * See {@link RikMainBetSummary}.
     */
    private List<RikMainBetSummary> mbs;

    /**
     * This connection's own gross return on {@code taixiuMd5Plugin}, which has no
     * {@code mbs}. <b>Absent, not zero</b>, on a round this account did not bet —
     * Jackson's {@code 0} plus {@code onEndGame}'s {@code w > 0} guard makes that a
     * no-op. Absent on {@code stockPlugin} in every captured round.
     */
    private long wm;

    /** Jackpot fields, modelled because they are real; deliberately not wired (AD-19). */
    private boolean iJp;
    private long tJpV;
    private long tJpv2;

    @JsonCreator
    public RikEndGameMessage(
            @JsonProperty("cmd") int cmd,
            @JsonProperty("sid") long sid,
            @JsonProperty("d1") int d1,
            @JsonProperty("d2") int d2,
            @JsonProperty("d3") int d3,
            @JsonProperty("obs") List<RikBetInfo> obs,
            @JsonProperty("bs") List<RikBetInfo> bs,
            @JsonProperty("mbs") List<RikMainBetSummary> mbs,
            @JsonProperty("wm") long wm,
            @JsonProperty("iJp") boolean iJp,
            @JsonProperty("tJpV") long tJpV,
            @JsonProperty("tJpv2") long tJpv2) {
        super(cmd);
        this.sid = sid;
        this.d1 = d1;
        this.d2 = d2;
        this.d3 = d3;
        this.obs = obs;
        this.bs = bs;
        this.mbs = mbs;
        this.wm = wm;
        this.iJp = iJp;
        this.tJpV = tJpV;
        this.tJpv2 = tJpv2;
    }

    @Override
    public long getSessionId() {
        return sid;
    }

    /**
     * Per-option crowd distribution, from {@code obs} with a {@code bs} fallback
     * (AD-4).
     * <p>
     * {@code obs} entries have no {@code b}, so {@code ownBet} is {@code 0} — the
     * same {@code 0} {@code BomEndGameMessage.crowdBets()} passes, and the v1
     * coordinator does not read it.
     * <p>
     * <b>The fallback is a code path a real game depends on, not a hedge.</b>
     * {@code taixiuMd5Plugin} (offset 4000) sends {@code bs} and no {@code obs} at
     * all, so without it that game's end-of-round crowd would go silent. Both fields
     * share {@link RikBetInfo}, so it costs one check.
     * <p>
     * <b>The test is null-or-empty, matching the payout accessors.</b> An
     * {@code "obs":[]} arriving next to a populated {@code bs} would otherwise report
     * an empty crowd — the exact case this fallback exists for, missed because the
     * server sent an empty array rather than omitting the key. That is not
     * hypothetical on this server: the stock EndGame carries {@code "ps":[]} in all
     * three captured rounds, so it demonstrably emits empty arrays instead of dropping
     * them. An empty {@code obs} with an empty {@code bs} still yields an empty list,
     * which is the same answer either way.
     */
    @Override
    public List<CrowdOption> crowdBets() {
        List<RikBetInfo> entries = obs != null && !obs.isEmpty() ? obs : bs;
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
                .map(e -> new CrowdOption(e.eid(), e.v(), e.b(), e.bc()))
                .toList();
    }

    /**
     * This bot's gross winnings for the round: {@code sum(mbs[].wm)} when {@code mbs}
     * is populated, else the top-level {@code wm} (AD-16).
     * <p>
     * <b>Precedence, never a sum.</b> The two sources are disjoint in both captures —
     * {@code stockPlugin} has only {@code mbs}, {@code taixiuMd5Plugin} only the
     * top-level {@code wm} — and on a hypothetical game carrying both they would be
     * the same money, so adding them would double it while precedence is correct
     * whether {@code wm} is the total or {@code mbs} is its breakdown.
     * <p>
     * <b>Returned verbatim: {@code wm} is a gross return that INCLUDES the stake</b>
     * (AD-15), the same convention {@code TaiXiuEndGameMessage.winningsFor} records.
     * A stock round staking 3000 at {@code d1 = -75} reports {@code 735} — a net loss
     * with a positive {@code wm}. Netting the stake off here turns the ~0.98 RTP
     * anchor into ~-0.02.
     * <p>
     * {@code userName} is ignored: the frame is addressed to this connection, the
     * same reasoning Tip and Win79 record. An absent {@code wm} is Jackson's
     * {@code 0} and {@code onEndGame} guards on {@code w > 0}, so a round the bot did
     * not bet increments nothing.
     */
    @Override
    public long winningsFor(String userName) {
        if (mbs != null && !mbs.isEmpty()) {
            return mbs.stream().mapToLong(RikMainBetSummary::wm).sum();
        }
        return wm;
    }

    /**
     * Total this bot staked in the round: {@code sum(mbs[].b)} when {@code mbs} is
     * populated, else {@code sum(bs[].b)} (AD-17).
     * <p>
     * <b>Never {@code obs[].v}.</b> {@code obs} is the backend's
     * {@code OTHER_BET_ARRAY} and {@code v} is the room's aggregate, so summing it
     * would charge the whole room's stake to every bot in the group. {@code obs}
     * carries no own-stake field at all.
     * <p>
     * {@code userName} ignored — recipient-personalized payload.
     */
    @Override
    public long betAmountFor(String userName) {
        if (mbs != null && !mbs.isEmpty()) {
            return mbs.stream().mapToLong(RikMainBetSummary::b).sum();
        }
        if (bs != null) {
            return bs.stream().mapToLong(RikBetInfo::b).sum();
        }
        return 0L;
    }

    /**
     * Number of own bets in the round — <b>positions with a stake, not clicks</b>.
     * <p>
     * Neither frame carries a per-bet count, so this reports the number of own-stake
     * entries whose amount is {@code > 0}: the {@code TaiXiuEndGameMessage.betCountFor}
     * precedent, which reports {@code 1} for a round with any effective stake. It
     * <b>undercounts</b> a bot that clicks the same option repeatedly (the capture
     * shows three sends collapsing into one {@code mbs} entry), which
     * {@link HasBetTotals} already warns is a legitimate divergence from the locally
     * counted sends.
     * <p>
     * <b>{@code bc} is not usable as the count on this product</b>: it counts
     * <b>distinct players</b>, not bets — {@code bc:1} on a round in which this single
     * account placed three bets — unlike Tip, where its own {@code bc} is a bet count.
     * <p>
     * The amount, which is the field that drives {@code bot_bet_amount_total}, is
     * exact; a flat-zero staking metric while bots visibly bet is the recurring
     * staging pain point this marker exists to remove.
     */
    @Override
    public int betCountFor(String userName) {
        if (mbs != null && !mbs.isEmpty()) {
            return (int) mbs.stream().filter(e -> e.b() > 0L).count();
        }
        if (bs != null) {
            return (int) bs.stream().filter(e -> e.b() > 0L).count();
        }
        return 0;
    }
}
