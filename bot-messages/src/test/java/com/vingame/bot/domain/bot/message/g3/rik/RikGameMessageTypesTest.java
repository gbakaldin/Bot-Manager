package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.coordination.CrowdOption;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.HasJackpot;
import com.vingame.bot.domain.bot.message.HasJackpotPool;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins RIK (P_114) betting-mini polymorphic dispatch and field semantics.
 * <p>
 * <b>Every fixture this class reads is a real frame</b> from the {@code stockPlugin}
 * capture of 2026-09-15, copied verbatim — including the subscribe frame's full
 * 50-entry {@code cH} and its nested {@code bH}, which are deliberately unmodelled
 * (AD-6) and are therefore a tolerance regression test.
 * {@link RikFixtureProvenanceTest} enforces that "verbatim" against the committed
 * capture itself. Offset is 10000, so CMDs are 13000/13002/13005/13006.
 * <p>
 * The second captured 114 game — {@code taixiuMd5Plugin} at offset 4000, a
 * materially different EndGame shape — is covered by
 * {@link RikTaiXiuMd5GameShapeTest}, which reads the {@code txmd5-*} fixtures.
 */
@DisplayName("RikGameMessageTypes - polymorphic deserialization")
class RikGameMessageTypesTest {

    private static final int RIK_STOCK_OFFSET = 10000;

    private ObjectMapper newMapper(boolean md5) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new RikGameMessageTypes().getTypeRegistrations(RIK_STOCK_OFFSET, md5));
        return mapper;
    }

    private ObjectMapper newMapper() {
        // stockPlugin is not md5 — its 13005 hash is always "-".
        return newMapper(false);
    }

    private String loadFixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/messages/rik/" + name)) {
            assertThat(in).as("fixture /messages/rik/" + name).isNotNull();
            return new String(in.readAllBytes());
        }
    }

    /** The capture's arrays are ordered {@code [{eid:1},{eid:0}]} — never index by position. */
    private Map<Integer, CrowdOption> byOption(BettingMiniMessage parsed) {
        return ((HasCrowdBets) parsed).crowdBets().stream()
                .collect(Collectors.toMap(CrowdOption::optionId, Function.identity()));
    }

    @Test
    @DisplayName("subscribe (cmd=13000) → RikSubscribeMessage; tFB/tFD drive the bet window")
    void subscribe() throws Exception {
        BettingMiniMessage parsed = newMapper().readValue(loadFixture("subscribe.json"), BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(RikSubscribeMessage.class);

        SubscribeMessage asSubscribe = (SubscribeMessage) parsed;
        assertThat(asSubscribe.getTimeForBetting()).isEqualTo(21000L);
        assertThat(asSubscribe.getTimeForDecision()).isEqualTo(1000L);

        RikSubscribeMessage rik = (RikSubscribeMessage) parsed;
        assertThat(rik.getSid()).isEqualTo(3793247L);
        assertThat(rik.getGS()).isEqualTo(2);
        assertThat(rik.getTFP()).isEqualTo(11000L);
        assertThat(rik.getRmT()).isEqualTo(4545L);
        // mB is the server's max bet. long, not int (AD-3) — this env has already
        // carried values at 86% of Integer.MAX_VALUE through this app.
        assertThat(rik.getMB()).isEqualTo(500_000_000L);
        assertThat(rik.getBs()).hasSize(2);

        // The unmodelled cH / htr / bH must not break the parse — the fixture keeps
        // them verbatim precisely so this is a regression test and not a claim.
        assertThat(byOption(parsed)).containsOnlyKeys(0, 1);
    }

    @Test
    @DisplayName("startGame (cmd=13005) → RikStartGameMessage with sessionId")
    void startGame() throws Exception {
        BettingMiniMessage parsed = newMapper().readValue(loadFixture("startGame.json"), BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(RikStartGameMessage.class);
        assertThat(((StartGameMessage) parsed).getSessionId()).isEqualTo(3793248L);
    }

    @Test
    @DisplayName("the SAME startGame frame registered with md5=true → RikStartGameMd5Message, hash \"-\" not null")
    void startGameMd5() throws Exception {
        // There is deliberately no separate startGameMd5.json fixture: 13005 always
        // carries md5, so the one real frame is both fixtures.
        BettingMiniMessage parsed =
                newMapper(true).readValue(loadFixture("startGame.json"), BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(RikStartGameMd5Message.class);
        assertThat(((StartGameMessage) parsed).getSessionId()).isEqualTo(3793248L);
        // AD-12: "-" is returned as "-". "This game publishes no hash" is a fact
        // worth preserving, so it is never normalised to null.
        assertThat(((StartGameMd5Message) parsed).getMd5Hash()).isEqualTo("-");
    }

    @Test
    @DisplayName("updateBet (cmd=13002) → RikUpdateBetMessage carrying the live crowd array")
    void updateBet() throws Exception {
        BettingMiniMessage parsed = newMapper().readValue(loadFixture("updateBet.json"), BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(RikUpdateBetMessage.class);
        assertThat(parsed).isInstanceOf(HasCrowdBets.class);

        Map<Integer, CrowdOption> crowd = byOption(parsed);
        assertThat(crowd).containsOnlyKeys(0, 1);
        // Own stake on the option we bet: b = 3000, mirrored by v since we were the
        // only bettor in the captured room.
        assertThat(crowd.get(0).ownBet()).isEqualTo(3000L);
        assertThat(crowd.get(0).value()).isEqualTo(3000L);
        // bc counts distinct PLAYERS, not bets: three bets, bc = 1.
        assertThat(crowd.get(0).count()).isEqualTo(1);
        assertThat(crowd.get(1).value()).isZero();
        assertThat(crowd.get(1).ownBet()).isZero();
    }

    @Test
    @DisplayName("updateBet reports gameState 0 — the frame carries no gS, and the caller guards on > 0")
    void updateBetHasNoGameState() throws Exception {
        BettingMiniMessage parsed = newMapper().readValue(loadFixture("updateBet.json"), BettingMiniMessage.class);

        // Deliberate: BettingMiniGameBot.onUpdate does `if (gameStateId > 0)`, so 0
        // leaves the phase untouched rather than resetting it. Returning anything
        // non-zero here would drive the state machine off a frame with no state in it.
        assertThat(((UpdateBetMessage) parsed).getGameState()).isZero();
    }

    @Test
    @DisplayName("endGame (cmd=13006) → RikEndGameMessage; sid, result and the obs crowd")
    void endGame() throws Exception {
        BettingMiniMessage parsed = newMapper().readValue(loadFixture("endGame.json"), BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(RikEndGameMessage.class);

        RikEndGameMessage end = (RikEndGameMessage) parsed;
        assertThat(end.getSessionId()).isEqualTo(3793249L);
        // d1 is the signed percent move; d2/d3 are constant 0 on this game.
        assertThat(end.getD1()).isEqualTo(60);
        assertThat(end.getD2()).isZero();
        assertThat(end.getD3()).isZero();

        // The crowd comes from obs; this game sends no bs on EndGame (the bs field is
        // the AD-4 fallback for a different, un-captured 114 game).
        assertThat(end.getBs()).isNull();
        Map<Integer, CrowdOption> crowd = byOption(parsed);
        assertThat(crowd).containsOnlyKeys(0, 1);
        assertThat(crowd.get(0).value()).isEqualTo(3000L);
        assertThat(crowd.get(0).count()).isEqualTo(1);
        // obs entries carry no `b`, so ownBet is 0 — the Bom shape.
        assertThat(crowd.get(0).ownBet()).isZero();
        assertThat(crowd.get(1).value()).isZero();

        // mbs is this connection's OWN bet (MAIN_BET_ARRAY); the next test reads the
        // payout metrics off it.
        assertThat(end.getMbs()).hasSize(1);
        assertThat(end.getMbs().get(0).b()).isEqualTo(3000L);
        assertThat(end.getMbs().get(0).wm()).isEqualTo(4704L);
    }

    @Test
    @DisplayName("endGame exposes this bot's OWN winnings and stake from mbs — and still no jackpot marker")
    void endGameExposesOwnWinningsAndStake() throws Exception {
        BettingMiniMessage parsed = newMapper().readValue(loadFixture("endGame.json"), BettingMiniMessage.class);

        // mbs is the backend's MAIN_BET_ARRAY and obs its OTHER_BET_ARRAY, so mbs is
        // THIS connection's own bet. (mbs[].wm being byte-identical to the mW on the
        // room-wide 13018 announcement is an artefact of a capture with exactly one
        // bettor in it — ours — which makes "my win" and "the room's biggest win"
        // trivially the same number. It is not evidence of scope.)
        assertThat(parsed)
                .isInstanceOf(HasBotWinnings.class)
                .isInstanceOf(HasBetTotals.class)
                // AD-19 stays pinned: stock carries no jackpot field at all, and the
                // txmd5 tJpV meter read 0 in every sample while its meaning is
                // inverted between product families. Do not wire it on a guess.
                .isNotInstanceOf(HasJackpot.class)
                .isNotInstanceOf(HasJackpotPool.class);

        // Real values off the stock fixture (sid 3793249, d1 +60, stake 3000):
        // wm is the GROSS RETURN INCLUDING STAKE — 3000 x 160% x 0.98 = 4704 — so it
        // is returned verbatim and must never be netted against the stake.
        assertThat(((HasBotWinnings) parsed).winningsFor("anything")).isEqualTo(4704L);
        assertThat(((HasBetTotals) parsed).betAmountFor("anything")).isEqualTo(3000L);
        // Positions with a stake, not clicks: the bot sent three bets this round and
        // the frame collapses them into one mbs entry (bc counts players here, so it
        // is no help either). The undercount is documented on the method.
        assertThat(((HasBetTotals) parsed).betCountFor("anything")).isEqualTo(1);
    }

    @Test
    @DisplayName("a NON-EMPTY endGame ps still parses — it is unmodelled, not rejected (AD-8)")
    void endGameToleratesNonEmptyPs() throws Exception {
        // SYNTHETIC, not a fixture: `ps` was empty in all three captured rounds even
        // though this account bet in all three, so its element type is unknown and a
        // typed model would be a guess. The shape below is invented purely to prove
        // that a populated `ps` cannot break the parse the day the server sends one.
        // Fixture files stay real frames only.
        String syntheticPs = """
                {"cmd":13006,"sid":3793249,"d1":60,"d2":0,"d3":0,
                 "obs":[{"eid":1,"bc":0,"v":0},{"eid":0,"bc":1,"v":3000}],
                 "mbs":[{"b":3000,"wm":4704,"m":3000}],
                 "ps":[{"uid":"15_6447","b":[{"eid":0,"v":3000}],"m":1759134614}]}
                """;

        BettingMiniMessage parsed = newMapper().readValue(syntheticPs, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(RikEndGameMessage.class);
        assertThat(((RikEndGameMessage) parsed).getSessionId()).isEqualTo(3793249L);
        assertThat(byOption(parsed)).containsOnlyKeys(0, 1);
    }

    @Test
    @DisplayName("crowdBets falls back from obs to bs — the AD-4 path a real second 114 game depends on")
    void endGameFallsBackToBs() throws Exception {
        // SYNTHETIC: stockPlugin never sends bs on EndGame. The fallback is no longer
        // a hedge — taixiuMd5Plugin (offset 4000) sends bs and no obs at all, so
        // without this its end-of-round crowd would go silent. Exercised against the
        // real frame in RikTaiXiuMd5GameShapeTest; pinned in isolation here.
        String bsOnlyEndGame = """
                {"cmd":13006,"sid":42,"d1":-75,"bs":[{"eid":0,"bc":2,"v":5000}]}
                """;

        BettingMiniMessage parsed = newMapper().readValue(bsOnlyEndGame, BettingMiniMessage.class);

        List<CrowdOption> crowd = ((HasCrowdBets) parsed).crowdBets();
        assertThat(crowd).hasSize(1);
        assertThat(crowd.get(0).optionId()).isZero();
        assertThat(crowd.get(0).value()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("an EMPTY obs next to a populated bs still falls back — the server sends [] rather than omitting")
    void endGameFallsBackOnEmptyObsToo() throws Exception {
        // SYNTHETIC, and the reason the fallback tests null-OR-EMPTY rather than null:
        // this server demonstrably emits empty arrays instead of dropping the key —
        // the stock EndGame carries "ps":[] in all three captured rounds. A null-only
        // test would report an empty crowd here, which is precisely the case AD-4's
        // fallback exists to cover. The payout accessors already check null-or-empty;
        // this removes the asymmetry.
        String emptyObsEndGame = """
                {"cmd":13006,"sid":43,"d1":-75,"obs":[],
                 "bs":[{"eid":1,"bc":2,"b":1000,"v":5000}]}
                """;

        BettingMiniMessage parsed = newMapper().readValue(emptyObsEndGame, BettingMiniMessage.class);

        List<CrowdOption> crowd = ((HasCrowdBets) parsed).crowdBets();
        assertThat(crowd).hasSize(1);
        assertThat(crowd.get(0).optionId()).isEqualTo(1);
        assertThat(crowd.get(0).value()).isEqualTo(5000L);
        assertThat(crowd.get(0).ownBet()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("both crowd arrays empty → empty list, not a throw and not a fallback loop")
    void endGameWithBothCrowdArraysEmpty() throws Exception {
        BettingMiniMessage parsed = newMapper()
                .readValue("{\"cmd\":13006,\"sid\":44,\"obs\":[],\"bs\":[]}", BettingMiniMessage.class);

        // Same answer either way, and the null-or-empty test must not change that.
        assertThat(((HasCrowdBets) parsed).crowdBets()).isEmpty();
        assertThat(((RikEndGameMessage) parsed).getSessionId()).isEqualTo(44L);
    }

    @Test
    @DisplayName("no crowd array at all → empty list, never a throw")
    void crowdBetsNeverThrows() throws Exception {
        BettingMiniMessage parsed = newMapper()
                .readValue("{\"cmd\":13006,\"sid\":7}", BettingMiniMessage.class);

        assertThat(((HasCrowdBets) parsed).crowdBets()).isEmpty();
        assertThat(((RikEndGameMessage) parsed).getSessionId()).isEqualTo(7L);
    }
}
