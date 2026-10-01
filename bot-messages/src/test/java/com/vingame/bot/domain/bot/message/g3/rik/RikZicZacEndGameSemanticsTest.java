package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasJackpotPool;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The ziczac money-path claims that <b>the captured fixtures cannot make</b>.
 *
 * <p>{@code RikZicZacGameShapeTest} proves the six real frames parse to the right
 * numbers, and {@code RikZicZacCaptureArithmeticTest} proves the payout identity over
 * the whole capture. Both are necessary and neither is sufficient, because the capture
 * has exactly one property that disarms them: <b>every candidate source agrees with
 * every other candidate source in it</b>. {@code sum(mbs[].r)} equals {@code p.wm} in
 * 6/6 rounds; the account was the only bettor, so own-scope and room-scope are the same
 * numbers. A fixture-driven test therefore passes whether the accessor reads the field
 * the plan chose or the field the plan explicitly rejected.
 *
 * <p>This class supplies the discriminating cases synthetically:
 * <ul>
 *   <li>a frame where {@code p.wm} <b>disagrees</b> with {@code sum(mbs[].r)}, which is
 *       the only construction that can fail if someone "simplifies" {@code winningsFor}
 *       into reading {@code p.wm} (RIK_114_ZICZAC AD-5 — the mistake the previous RIK
 *       plan already paid for once, on a different field);</li>
 *   <li>the declared <b>type</b> of {@code odd}, asserted reflectively, because a
 *       narrowing to an integral type is silent: Jackson's {@code ACCEPT_FLOAT_AS_INT}
 *       turns {@code 1.2} into {@code 1} with no error, and
 *       {@code RikZicZacCaptureArithmeticTest} reads {@code odd} out of raw JSON
 *       ({@code JsonNode.asDouble()}), so it is structurally incapable of noticing
 *       (AD-12);</li>
 *   <li>absent / empty / zero-valued {@code mbs}, so a defensive guard is a tested
 *       guard rather than a hopeful one;</li>
 *   <li>the centre bucket, where {@code odd == 0} makes {@code r == 0} a
 *       <b>legitimate settled value</b> and not missing data.</li>
 * </ul>
 */
@DisplayName("ziczac EndGame semantics — the claims the single-bettor capture cannot test")
class RikZicZacEndGameSemanticsTest {

    private static final int ZICZAC_OFFSET = 9000;
    private static final int ZICZAC_END_GAME_CMD = 12006;

    /** Resolve exactly as {@code BotFactory} does, so the wire path is the real one. */
    private static ObjectMapper ziczacMapper() {
        GameMessageTypes resolved = new RikGameMessageTypes().forGame(
                Game.builder()
                        .gameType(GameType.BETTING_MINI)
                        .pluginName("ziczacPlugin")
                        .offset(ZICZAC_OFFSET)
                        .md5(false)
                        .build());
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(resolved.getTypeRegistrations(ZICZAC_OFFSET, false));
        return mapper;
    }

    private static RikZicZacEndGameMessage parse(String json) throws Exception {
        BettingMiniMessage msg = ziczacMapper().readValue(json, BettingMiniMessage.class);
        assertThat(msg)
                .as("the ziczac provider must bind cmd 12006 to its own EndGame class")
                .isInstanceOf(RikZicZacEndGameMessage.class);
        return (RikZicZacEndGameMessage) msg;
    }

    /* ------------------------------------------------------------------ *
     * AD-5 — the source of own winnings, made discriminating
     * ------------------------------------------------------------------ */

    @Test
    @DisplayName("winningsFor reads sum(mbs[].r) — NOT p.wm — when the two disagree")
    void winningsIgnoresPWmWhenTheyDisagree() throws Exception {
        // Deliberately impossible in the capture, and that is the point: there, one
        // bettor made p.wm and sum(mbs[].r) the same number in 6/6 rounds, so no real
        // frame can tell a correct implementation from the rejected one. Here p.wm is
        // a room-scale 999 999 999 while this connection's own balls returned 75 000.
        //
        // If anyone ever "simplifies" winningsFor to read p.wm — the field that sits
        // next to a uid/u/dn triple, i.e. room-announcement payload — this test is the
        // only thing in the build that goes red. Reading it would replay the exact
        // error RIK_114_BETTING_MINI Amendment A1 records, one plan later.
        String json = """
                {"cmd":12006,"sid":1995099,"tJpV":700000,"iJp":false,"obs":[],
                 "p":{"uid":"15_6447","u":"rv_lala00","dn":"lalalala",
                      "wm":999999999,"jwm":0,"m":1756909033},
                 "mbs":[{"p":24,"b":50000,"r":15000,"odd":0.3},
                        {"p":11,"b":50000,"r":60000,"odd":1.2}]}
                """;

        RikZicZacEndGameMessage end = parse(json);

        assertThat(((HasBotWinnings) end).winningsFor("lalalala")).isEqualTo(75_000L);
        assertThat(((HasBotWinnings) end).winningsFor("lalalala")).isNotEqualTo(999_999_999L);
        // p is not modelled at all (AD-13), so there is no accessor to reach it by
        // accident either.
        assertThat(RikZicZacEndGameMessage.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("p", "obs", "jps");
    }

    @Test
    @DisplayName("winningsFor / betAmountFor / betCountFor ignore userName entirely")
    void accessorsIgnoreUserName() throws Exception {
        // The frame is addressed to this connection — mbs is the backend's
        // MAIN_BET_ARRAY — so there is no name to match against and a caller passing
        // someone else's name (or null) must get the same answer, not zero.
        String json = """
                {"cmd":12006,"sid":1995099,"tJpV":1,"iJp":false,
                 "mbs":[{"p":1,"b":50000,"r":60000,"odd":1.2}]}
                """;

        RikZicZacEndGameMessage end = parse(json);

        for (String name : new String[]{"lalalala", "someone-else", "", null}) {
            assertThat(end.winningsFor(name)).as("winningsFor(%s)", name).isEqualTo(60_000L);
            assertThat(end.betAmountFor(name)).as("betAmountFor(%s)", name).isEqualTo(50_000L);
            assertThat(end.betCountFor(name)).as("betCountFor(%s)", name).isEqualTo(1);
        }
    }

    /* ------------------------------------------------------------------ *
     * AD-12 — odd is a double, and the narrowing is otherwise silent
     * ------------------------------------------------------------------ */

    @Test
    @DisplayName("RikZicZacBallResult.odd is DECLARED double; b and r are long")
    void ballResultComponentTypesArePinned() {
        var components = RikZicZacBallResult.class.getRecordComponents();
        assertThat(components).as("RikZicZacBallResult must stay a record").isNotNull();

        java.util.Map<String, Class<?>> byName = new java.util.LinkedHashMap<>();
        for (RecordComponent c : components) {
            byName.put(c.getName(), c.getType());
        }

        // THE narrowing guard. A `long odd` compiles, deserializes without error and
        // silently truncates the 1.2 and 0.3 buckets to 1 and 0 — corrupting every
        // payout that passes through them with no exception, no warning and no log
        // line. The capture-arithmetic test cannot catch it (it reads odd straight out
        // of JsonNode, never through this record), and a value assertion elsewhere
        // would fail with an unhelpful "expected 0.3 but was 1". This fails by name.
        assertThat(byName.get("odd"))
                .as("odd MUST be double: the bucket table holds 1.2 and 0.3, and "
                        + "Jackson's ACCEPT_FLOAT_AS_INT truncates them SILENTLY on an "
                        + "integral field (RIK_114_ZICZAC AD-12)")
                .isEqualTo(double.class);

        // Money stays long: r reaches 15 900 000 in the capture and the same frame
        // carries a balance at 82% of Integer.MAX_VALUE.
        assertThat(byName.get("b")).as("stake must be long").isEqualTo(long.class);
        assertThat(byName.get("r")).as("return must be long").isEqualTo(long.class);
    }

    @Test
    @DisplayName("odd survives the round trip as a fraction: r == b x odd off the DESERIALIZED record")
    void fractionalOddSurvivesDeserialization() throws Exception {
        // Same identity the capture test asserts, but computed from the record rather
        // than from JsonNode — so this one *does* fail if `odd` is ever narrowed.
        // Every bucket in the captured round is exercised: 0, 0.3, 1.2, 2, 3, 5.
        try (var in = getClass().getResourceAsStream("/messages/rik/ziczac-endGame.json")) {
            assertThat(in).as("fixture ziczac-endGame.json").isNotNull();
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            RikZicZacEndGameMessage end = parse(json);

            assertThat(end.getMbs()).hasSize(20);
            for (RikZicZacBallResult ball : end.getMbs()) {
                // The cast is written explicitly so this file still COMPILES if someone
                // narrows `odd` — otherwise the build breaks here with a varargs type
                // error and the developer never sees ballResultComponentTypesArePinned's
                // message, which is the one that explains what they did.
                double odd = (double) ball.odd();
                assertThat((double) ball.r())
                        .as("r must equal b x odd through the record (b=%d, odd=%s)",
                                ball.b(), odd)
                        .isCloseTo(ball.b() * odd, within(0.5d));
            }

            // The fractional buckets really are present in this fixture — otherwise the
            // loop above would be satisfied by integral odds alone and prove nothing
            // about the type.
            assertThat(end.getMbs().stream()
                    .map(b -> (double) b.odd()).distinct().sorted().toList())
                    .contains(0.0d, 0.3d, 1.2d, 2.0d, 3.0d, 5.0d);
        }
    }

    @Test
    @DisplayName("integral wire form of odd (\"odd\":2) parses as 2.0, not as a failure")
    void integralOddFormIsAccepted() throws Exception {
        // 27 of the capture's 81 balls send odd in integer form and 54 in double form —
        // the same field, both ways, in the same file. A double field takes both; this
        // pins that the mixed encoding is tolerated rather than merely unobserved.
        RikZicZacEndGameMessage end = parse("""
                {"cmd":12006,"sid":1,"tJpV":0,"iJp":false,
                 "mbs":[{"b":50000,"r":100000,"odd":2},
                        {"b":50000,"r":60000,"odd":1.2}]}
                """);

        assertThat((double) end.getMbs().get(0).odd()).isEqualTo(2.0d);
        assertThat((double) end.getMbs().get(1).odd()).isEqualTo(1.2d);
        assertThat(end.winningsFor("x")).isEqualTo(160_000L);
    }

    /* ------------------------------------------------------------------ *
     * AD-6 — a settled zero is data, and the absent cases
     * ------------------------------------------------------------------ */

    @Test
    @DisplayName("a centre-bucket ball (odd 0 → r 0) is a SETTLED ball: counted and staked, worth 0")
    void centreBucketBallIsCountedNotSkipped() throws Exception {
        // The 17-bucket table pays 0x in the centre, so r == 0 with b > 0 is the game
        // working correctly — not a missing field, not an unsettled bet. It must still
        // be counted by betCountFor and still charged to betAmountFor, or
        // bot_bet_amount_total under-reports every round that hits the centre (13 of
        // the capture's 81 balls did).
        RikZicZacEndGameMessage end = parse("""
                {"cmd":12006,"sid":1995099,"tJpV":1,"iJp":false,
                 "mbs":[{"p":11,"b":50000,"r":0,"odd":0},
                        {"p":49,"b":50000,"r":0,"odd":0},
                        {"p":12,"b":50000,"r":100000,"odd":2}]}
                """);

        assertThat(end.betCountFor("x")).as("all three balls settled").isEqualTo(3);
        assertThat(end.betAmountFor("x")).isEqualTo(150_000L);
        assertThat(end.winningsFor("x")).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("a round where EVERY ball hit the centre: stake counted, winnings 0, no NPE")
    void allCentreRoundReportsZeroWinningsAgainstARealStake() throws Exception {
        RikZicZacEndGameMessage end = parse("""
                {"cmd":12006,"sid":1995099,"tJpV":1,"iJp":false,
                 "mbs":[{"b":50000,"r":0,"odd":0},{"b":50000,"r":0,"odd":0}]}
                """);

        // This is the one legitimate shape that looks like the "bets never settle"
        // failure CLAUDE.md records as misdiagnosed twice: a real stake with zero
        // winnings. It is indistinguishable from that failure in a single round, which
        // is why the plan's V-7 reads the ratio over >= 20 rounds and not over one.
        assertThat(end.betAmountFor("x")).isEqualTo(100_000L);
        assertThat(end.betCountFor("x")).isEqualTo(2);
        assertThat(end.winningsFor("x")).isZero();
    }

    @Test
    @DisplayName("a zero-stake entry is not counted but its return still lands in winningsFor")
    void zeroStakeEntryIsNotCounted() throws Exception {
        // Documented behaviour, matching the other 114 class: betCountFor filters on
        // b > 0. Nothing in the capture produces a b == 0 entry, so this pins the
        // contract rather than an observation — and it pins that the filter is on the
        // COUNT only, never on the money.
        RikZicZacEndGameMessage end = parse("""
                {"cmd":12006,"sid":1,"tJpV":0,"iJp":false,
                 "mbs":[{"b":0,"r":7,"odd":0},{"b":50000,"r":60000,"odd":1.2}]}
                """);

        assertThat(end.betCountFor("x")).isEqualTo(1);
        assertThat(end.betAmountFor("x")).isEqualTo(50_000L);
        assertThat(end.winningsFor("x")).isEqualTo(60_007L);
    }

    @Test
    @DisplayName("mbs ABSENT from the frame (null, not []) → 0/0/0 and no NPE")
    void absentMbsIsNotAnNpe() throws Exception {
        // The captured no-bet rounds send "mbs":[]. Absent-entirely is not observed and
        // is exactly the shape that NPEs a stream() written without a guard — on a bot
        // thread, inside onEndGame, i.e. it kills the round handler for that bot.
        RikZicZacEndGameMessage end = parse("""
                {"cmd":12006,"sid":1995085,"tJpV":204400,"iJp":false,"obs":[]}
                """);

        assertThat(end.getMbs()).isNull();
        assertThat(end.winningsFor("x")).isZero();
        assertThat(end.betAmountFor("x")).isZero();
        assertThat(end.betCountFor("x")).isZero();
        // The pool meter is a property of the game, not of our bet.
        assertThat(end.jackpotPool()).isEqualTo(204_400L);
    }

    @Test
    @DisplayName("mbs set to null through the constructor → 0/0/0 (the guard, not the wire)")
    void constructorNullMbsIsGuarded() {
        RikZicZacEndGameMessage end =
                new RikZicZacEndGameMessage(ZICZAC_END_GAME_CMD, 42L, null, 0L, false);

        assertThat(end.getSessionId()).isEqualTo(42L);
        assertThat(end.winningsFor("x")).isZero();
        assertThat(end.betAmountFor("x")).isZero();
        assertThat(end.betCountFor("x")).isZero();
        assertThat(end.jackpotPool()).isZero();
    }

    /* ------------------------------------------------------------------ *
     * AD-7 — the pool meter
     * ------------------------------------------------------------------ */

    @Test
    @DisplayName("jackpotPool is tJpV verbatim; an absent tJpV is 0, which downstream reads as 'not observed'")
    void jackpotPoolIsTJpVAndDefaultsToZero() throws Exception {
        assertThat(parse("""
                {"cmd":12006,"sid":1,"tJpV":689300,"iJp":false,"mbs":[]}
                """).jackpotPool()).isEqualTo(689_300L);

        // No tJpV key at all — JackpotScaler treats 0 as "not observed" → neutral
        // factor, so the safe default is 0 and not, say, a carried-over last value.
        assertThat(parse("""
                {"cmd":12006,"sid":1,"iJp":false,"mbs":[]}
                """).jackpotPool()).isZero();
    }

    @Test
    @DisplayName("a pool beyond Integer.MAX_VALUE survives — tJpV is long")
    void jackpotPoolIsLong() throws Exception {
        // The capture's meter is only in the hundreds of thousands, but it is
        // monotonically rising and p.m on the same frame already sits at 82% of
        // Integer.MAX_VALUE. An int field would wrap negative on a busy product.
        assertThat(parse("""
                {"cmd":12006,"sid":1,"tJpV":9007199254740991,"iJp":false,"mbs":[]}
                """).jackpotPool()).isEqualTo(9_007_199_254_740_991L);
    }

    @Test
    @DisplayName("winnings and stake stay long across a round that overflows int")
    void moneyDoesNotOverflowInt() throws Exception {
        // mB is 50 000 000 per ball and up to 20 balls were observed in one round, so a
        // maxed round stakes 1 000 000 000 — and a single 100x bucket returns
        // 5 000 000 000, comfortably past Integer.MAX_VALUE. Nothing in the accessor
        // chain may narrow.
        RikZicZacEndGameMessage end = parse("""
                {"cmd":12006,"sid":1,"tJpV":0,"iJp":false,
                 "mbs":[{"b":50000000,"r":5000000000,"odd":100},
                        {"b":50000000,"r":0,"odd":0}]}
                """);

        assertThat(end.winningsFor("x")).isEqualTo(5_000_000_000L);
        assertThat(end.betAmountFor("x")).isEqualTo(100_000_000L);
        assertThat(end.betCountFor("x")).isEqualTo(2);
    }

    /* ------------------------------------------------------------------ *
     * The marker set, pinned against the interface list rather than by instanceof
     * ------------------------------------------------------------------ */

    @Test
    @DisplayName("the implemented-marker set is exactly {HasBotWinnings, HasBetTotals, HasJackpotPool}")
    void markerSetIsExact() {
        List<String> markers = java.util.Arrays.stream(
                        RikZicZacEndGameMessage.class.getInterfaces())
                .map(Class::getSimpleName)
                .sorted()
                .toList();

        // An exact set, not three isInstanceOf checks: adding HasJackpot (no discharge
        // was ever captured, OI-5) or HasCrowdBets (obs was [] in 8/8 rounds, AD-8)
        // must argue with a test rather than slip in beside the existing three.
        assertThat(markers).containsExactly(
                HasBetTotals.class.getSimpleName(),
                HasBotWinnings.class.getSimpleName(),
                HasJackpotPool.class.getSimpleName());
    }

    @Test
    @DisplayName("the ziczac UpdateBet implements NO marker at all — it exists to drop HasCrowdBets")
    void updateBetImplementsNoMarker() {
        assertThat(RikZicZacUpdateBetMessage.class.getInterfaces())
                .as("ziczac's bs is our own per-ball state, not a crowd (AD-8); "
                        + "publishing it would feed BetCoordinator an all-zero-value "
                        + "distribution for a game with no options")
                .isEmpty();

        // And the generic 114 class, which it exists to differ from, still carries it —
        // so this is a real divergence and not both classes drifting together.
        assertThat(RikUpdateBetMessage.class.getInterfaces())
                .extracting(Class::getSimpleName)
                .contains("HasCrowdBets");
    }
}
