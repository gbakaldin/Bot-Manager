package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.coordination.CrowdOption;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The money semantics of {@link RikEndGameMessage}, pinned where the two captures
 * <b>cannot</b> pin them.
 *
 * <h2>Why this class exists separately from {@link RikGameMessageTypesTest}</h2>
 *
 * Both captures were taken on an account that was the <b>only bettor in every
 * round</b>. That makes {@code obs[].v} (the room), {@code bs[].v} (the room) and
 * {@code mbs[].b} / {@code bs[].b} (this connection's own stake) numerically
 * identical in every real frame we hold — so a fixture-only test cannot tell a
 * correct implementation from one that sums the room's stake into
 * {@code bot_bet_amount_total} for every bot in the group. Exactly that coincidence
 * is what produced the wrong AD-5 that Phase 1 shipped (Amendment A1).
 * <p>
 * The frames below are therefore <b>synthetic and labelled as such</b> — real frames
 * with the room's figures deliberately pulled apart from the bot's own, which is the
 * only way to make the distinction falsifiable. Fixture files stay real frames only;
 * the one real fixture this class does read, {@code endGame-loss.json}, is the
 * captured round {@code sid 3793247} and is bound to the capture by
 * {@link RikFixtureProvenanceTest}.
 *
 * @see RikGameMessageTypesTest stock shapes off real fixtures
 * @see RikTaiXiuMd5GameShapeTest txmd5 shapes off real fixtures
 */
@DisplayName("RikEndGameMessage - payout semantics the captures cannot prove on their own")
class RikEndGamePayoutSemanticsTest {

    private static final int RIK_STOCK_OFFSET = 10000;

    private ObjectMapper newMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // The production posture (BettingMiniGameBot:905).
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new RikGameMessageTypes().getTypeRegistrations(RIK_STOCK_OFFSET, false));
        return mapper;
    }

    private RikEndGameMessage parse(String json) throws Exception {
        BettingMiniMessage parsed = newMapper().readValue(json, BettingMiniMessage.class);
        assertThat(parsed).isInstanceOf(RikEndGameMessage.class);
        return (RikEndGameMessage) parsed;
    }

    private RikEndGameMessage parseFixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/messages/rik/" + name)) {
            assertThat(in).as("fixture /messages/rik/" + name).isNotNull();
            return parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private Map<Integer, CrowdOption> byOption(RikEndGameMessage end) {
        return ((HasCrowdBets) end).crowdBets().stream()
                .collect(Collectors.toMap(CrowdOption::optionId, Function.identity()));
    }

    /* ------------------------------------------------------------------ *
     * 1. wm is a GROSS RETURN INCLUDING STAKE, not a profit.
     * ------------------------------------------------------------------ */

    @Test
    @DisplayName("a LOSING round still reports a positive wm — 3000 staked returned 735 (AD-15)")
    void winningsAreAGrossReturnSoALossIsStillPositive() throws Exception {
        // REAL frame: captured round sid 3793247, d1 = -75%.
        // 3000 x (100-75)/100 x 0.98 = 735. The bot lost 2265 and winningsFor still
        // reads 735, because wm INCLUDES the stake.
        RikEndGameMessage end = parseFixture("endGame-loss.json");

        assertThat(end.getSessionId()).isEqualTo(3793247L);
        assertThat(end.getD1()).isEqualTo(-75);

        long staked = ((HasBetTotals) end).betAmountFor("anything");
        long won = ((HasBotWinnings) end).winningsFor("anything");

        assertThat(staked).isEqualTo(3000L);
        assertThat(won).isEqualTo(735L);

        // The whole point, stated as an assertion so a "fix" that nets the stake off
        // winningsFor (which would give -2265, or 0 after clamping) fails here:
        // a net loss carries a POSITIVE gross return, and it is below the stake.
        assertThat(won).isPositive();
        assertThat(won).isLessThan(staked);
    }

    @Test
    @DisplayName("winningsFor is never net: winnings/stake on the captured rounds is the ~0.98 RTP anchor, not ~-0.02")
    void ratioOverTheThreeCapturedStockRoundsIsTheRtpAnchor() throws Exception {
        // All three captured stock rounds, so the metric ratio an operator reads on
        // Grafana is pinned rather than inferred: (735 + 980 + 4704) / (3000 + 4000 +
        // 3000) = 6419 / 10000 = 0.64. Below 1.0 (bots lose on average) and nowhere
        // near either failure signature V-9 names: ~1.98 (stake double-counted as
        // winnings) or ~0.02 (stake netted off).
        RikEndGameMessage loss = parseFixture("endGame-loss.json");
        RikEndGameMessage win = parseFixture("endGame.json");

        long won = ((HasBotWinnings) loss).winningsFor("x") + ((HasBotWinnings) win).winningsFor("x");
        long staked = ((HasBetTotals) loss).betAmountFor("x") + ((HasBetTotals) win).betAmountFor("x");

        assertThat(won).isEqualTo(735L + 4704L);
        assertThat(staked).isEqualTo(3000L + 3000L);
        assertThat((double) won / staked).isBetween(0.5, 1.5);
    }

    /* ------------------------------------------------------------------ *
     * 2. Own stake comes from mbs / bs, NEVER from the room-wide obs.
     * ------------------------------------------------------------------ */

    @Test
    @DisplayName("betAmountFor reads mbs[].b and IGNORES the room-wide obs[].v (AD-17)")
    void betAmountNeverReadsTheRoomWideObs() throws Exception {
        // SYNTHETIC — and it has to be. In every captured round this account was the
        // only bettor, so obs[].v == mbs[].b and a test built on a fixture would pass
        // against an implementation that reads the room. Here the room staked
        // 900 000 000 across 40 players while this bot staked 1000.
        // Backend constants: MAIN_BET_ARRAY = "mbs" (mine), OTHER_BET_ARRAY = "obs".
        String busyRoom = """
                {"cmd":13006,"sid":3793249,"d1":60,"d2":0,"d3":0,
                 "obs":[{"eid":0,"bc":40,"v":900000000},{"eid":1,"bc":7,"v":123456}],
                 "mbs":[{"b":1000,"wm":1568,"m":1000}]}
                """;

        RikEndGameMessage end = parse(busyRoom);

        assertThat(((HasBetTotals) end).betAmountFor("anything")).isEqualTo(1000L);
        assertThat(((HasBotWinnings) end).winningsFor("anything")).isEqualTo(1568L);
        // Positions with a stake, not the 47 clicks obs's bc counts.
        assertThat(((HasBetTotals) end).betCountFor("anything")).isEqualTo(1);

        // obs is still reported as the CROWD — it is not being ignored, it is being
        // used for the one thing it means.
        assertThat(byOption(end).get(0).value()).isEqualTo(900_000_000L);
    }

    @Test
    @DisplayName("an obs-only endGame (no mbs, no bs) reports ZERO own stake, not the room's total")
    void obsOnlyEndGameReportsZeroOwnStake() throws Exception {
        // SYNTHETIC: the shape a round this bot sat out would take on stockPlugin —
        // obs is present and non-zero because other players bet. Falling through to
        // obs here is the single change that would silently charge every bot in the
        // group the whole room's stake.
        String roundWeSatOut = """
                {"cmd":13006,"sid":3793250,"d1":-12,"d2":0,"d3":0,
                 "obs":[{"eid":0,"bc":9,"v":5000000},{"eid":1,"bc":3,"v":250000}],
                 "ps":[]}
                """;

        RikEndGameMessage end = parse(roundWeSatOut);

        assertThat(end.getMbs()).isNull();
        assertThat(end.getBs()).isNull();
        assertThat(((HasBetTotals) end).betAmountFor("anything")).isZero();
        assertThat(((HasBetTotals) end).betCountFor("anything")).isZero();
        assertThat(((HasBotWinnings) end).winningsFor("anything")).isZero();

        // …while the crowd view is fully populated.
        assertThat(byOption(end).get(0).value()).isEqualTo(5_000_000L);
    }

    /* ------------------------------------------------------------------ *
     * 3. mbs vs the top-level wm: PRECEDENCE, never a sum (AD-16).
     * ------------------------------------------------------------------ */

    @Test
    @DisplayName("a frame carrying BOTH mbs and a top-level wm takes mbs — it must never add them")
    void mbsWinsOverTopLevelWmAndTheTwoAreNeverSummed() throws Exception {
        // SYNTHETIC: the two sources are disjoint in both captures (stock has only
        // mbs, txmd5 only the top-level wm), so no real frame can exercise the
        // precedence rule. On a game that carried both they would be THE SAME MONEY,
        // and summing would double-count every payout on the product.
        String both = """
                {"cmd":13006,"sid":1,"d1":60,
                 "obs":[{"eid":0,"bc":1,"v":3000}],
                 "mbs":[{"b":3000,"wm":4704,"m":3000}],
                 "wm":4704}
                """;

        RikEndGameMessage end = parse(both);

        assertThat(end.getWm()).isEqualTo(4704L);
        assertThat(((HasBotWinnings) end).winningsFor("anything"))
                .as("precedence, not summation — 9408 would be double-counted money")
                .isEqualTo(4704L);
    }

    @Test
    @DisplayName("an EMPTY mbs falls through to the top-level wm / bs, it does not report zero")
    void emptyMbsFallsThroughRatherThanZeroing() throws Exception {
        // SYNTHETIC: `"mbs":[]` is non-null but carries nothing. The guard is
        // `!mbs.isEmpty()`, so this must behave like a frame with no mbs at all —
        // a plain null check would report 0 winnings on a won round.
        String emptyMbs = """
                {"cmd":13006,"sid":2,"d1":5,"d2":4,"d3":4,
                 "mbs":[],
                 "bs":[{"eid":1,"bc":1,"b":100000,"v":100000},{"eid":2,"bc":0,"b":0,"v":0}],
                 "wm":198000}
                """;

        RikEndGameMessage end = parse(emptyMbs);

        assertThat(end.getMbs()).isEmpty();
        assertThat(((HasBotWinnings) end).winningsFor("anything")).isEqualTo(198_000L);
        assertThat(((HasBetTotals) end).betAmountFor("anything")).isEqualTo(100_000L);
        assertThat(((HasBetTotals) end).betCountFor("anything")).isEqualTo(1);
    }

    /* ------------------------------------------------------------------ *
     * 4. betCountFor counts POSITIONS WITH A STAKE, not clicks and not bc.
     * ------------------------------------------------------------------ */

    @Test
    @DisplayName("betCountFor counts positions with a stake — zero-stake entries do not count, on either branch")
    void betCountCountsStakedPositionsOnly() throws Exception {
        // SYNTHETIC: no captured round has more than one staked position (this
        // account bet one door), so the multi-entry and zero-entry arms are
        // unexercised by any fixture.
        String twoStakedOfThree = """
                {"cmd":13006,"sid":3,"d1":60,
                 "mbs":[{"b":1000,"wm":1568,"m":1000},{"b":0,"wm":0,"m":0},{"b":2500,"wm":3920,"m":2500}]}
                """;

        RikEndGameMessage end = parse(twoStakedOfThree);

        assertThat(((HasBetTotals) end).betCountFor("anything")).isEqualTo(2);
        assertThat(((HasBetTotals) end).betAmountFor("anything")).isEqualTo(3500L);
        // Winnings sum across the staked entries — the breakdown case AD-16 allows.
        assertThat(((HasBotWinnings) end).winningsFor("anything")).isEqualTo(1568L + 3920L);

        // Same rule on the bs branch (txmd5 shape), where the un-bet option always
        // arrives with b = 0 or absent.
        String bsBranch = """
                {"cmd":13006,"sid":4,"d1":5,"d2":4,"d3":4,
                 "bs":[{"eid":1,"bc":1,"b":100000,"v":100000},{"eid":2,"bc":4,"b":0,"v":7000000}],
                 "wm":198000}
                """;

        RikEndGameMessage bsEnd = parse(bsBranch);
        assertThat(((HasBetTotals) bsEnd).betCountFor("anything")).isEqualTo(1);
        // Note the un-bet option's crowd v of 7 000 000 is NOT in the stake.
        assertThat(((HasBetTotals) bsEnd).betAmountFor("anything")).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("bc is never used as the bet count — three clicks arrive as bc:1 on this product")
    void bcIsPlayersNotBetsAndIsNotTheCount() throws Exception {
        // REAL frame: round 3793249, in which this account sent THREE bets. obs
        // reports bc:1 (distinct players) and mbs collapses the three sends into one
        // entry. Both numbers are 1 here for different reasons, and neither is 3 —
        // betCountFor undercounts by design (the TaiXiuEndGameMessage precedent) and
        // reaching for bc would not fix it, it would just report the player count.
        RikEndGameMessage end = parseFixture("endGame.json");

        assertThat(end.getObs()).extracting(RikBetInfo::bc).containsExactly(0, 1);
        assertThat(((HasBetTotals) end).betCountFor("anything")).isEqualTo(1);
        // The AMOUNT, which is what drives bot_bet_amount_total, is exact.
        assertThat(((HasBetTotals) end).betAmountFor("anything")).isEqualTo(3000L);
    }

    /* ------------------------------------------------------------------ *
     * 5. AD-3 — every money field is a long.
     * ------------------------------------------------------------------ */

    @Test
    @DisplayName("money above Integer.MAX_VALUE survives on every payout field (AD-3)")
    void moneyFieldsDoNotTruncateAtIntegerMaxValue() throws Exception {
        // SYNTHETIC but not hypothetical: this same environment has carried
        // tJpV = 1 846 444 000 (86% of Integer.MAX_VALUE) through this app, the stock
        // capture's ps[].m reads 1 759 134 614, and bot.deposit.amount is
        // 1 000 000 000 — a twice-deposited bot sits above the int ceiling by
        // construction. An int field here would wrap to a negative and poison
        // bot_bet_amount_total / bot_winnings_total silently.
        long overflowStake = 3_000_000_000L;   // > Integer.MAX_VALUE (2 147 483 647)
        long overflowWin = 4_704_000_000L;
        String big = """
                {"cmd":13006,"sid":9007199254740993,"d1":60,
                 "obs":[{"eid":0,"bc":1,"v":9000000000}],
                 "mbs":[{"b":3000000000,"wm":4704000000,"m":3000000000}],
                 "tJpV":1846444000,"tJpv2":9000000000,"iJp":true}
                """;

        RikEndGameMessage end = parse(big);

        assertThat(((HasBetTotals) end).betAmountFor("anything")).isEqualTo(overflowStake);
        assertThat(((HasBotWinnings) end).winningsFor("anything")).isEqualTo(overflowWin);
        assertThat(byOption(end).get(0).value()).isEqualTo(9_000_000_000L);
        assertThat(end.getTJpV()).isEqualTo(1_846_444_000L);
        assertThat(end.getTJpv2()).isEqualTo(9_000_000_000L);
        assertThat(end.isIJp()).isTrue();
        // sid is a long like every other product's.
        assertThat(end.getSessionId()).isEqualTo(9007199254740993L);
    }
}
