package com.vingame.bot.domain.bot.message.taixiu;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasJackpot;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 verification (TAI_XIU_BOT plan): the captured {@code MiniGame}/{@code taixiuPlugin}
 * Tai Xiu payloads round-trip through a Jackson {@link ObjectMapper} into the concrete
 * Tai Xiu message classes, and the refund-aware accounting formulas (AD-11) hold for the
 * full / partial / zero-refund EndGame cases.
 * <p>
 * The provider ({@code TaiXiuMessageTypes}) and resolver land in Phase 3; this test
 * registers the subtypes inline against the <b>fixed</b> CMD strings (1005/1002/1004,
 * AD-3) — the same approach {@code SlotMessageDeserializationTest} used before its
 * provider existed. The {@code "cmd"} discriminator + {@code visible=true} on
 * {@link BettingMiniMessage} drives the polymorphic dispatch. Mapper config mirrors
 * {@code BettingMiniGameBot.botBehaviorScenario()} ({@code FAIL_ON_UNKNOWN_PROPERTIES=false}),
 * so un-modeled fields (the {@code c} animation array, {@code G}/{@code C}/{@code ag}/…)
 * deserialize without failing.
 */
@DisplayName("Tai Xiu message deserialization (Phase 2)")
class TaiXiuMessageDeserializationTest {

    private ObjectMapper newMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Fixed Tai Xiu CMDs — no per-env offset (AD-3). Bet (1000) is outbound-only
        // and is NOT registered as an inbound polymorphic subtype.
        mapper.registerSubtypes(
                new NamedType(TaiXiuSubscribeMessage.class, "1005"),
                new NamedType(TaiXiuStartGameMessage.class, "1002"),
                new NamedType(TaiXiuEndGameMessage.class, "1004"));
        return mapper;
    }

    private String loadFixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/messages/taixiu/" + name)) {
            assertThat(in).as("fixture /messages/taixiu/" + name).isNotNull();
            return new String(in.readAllBytes());
        }
    }

    private BettingMiniMessage parse(String fixture) throws Exception {
        return newMapper().readValue(loadFixture(fixture), BettingMiniMessage.class);
    }

    @Test
    @DisplayName("subscribe (cmd=1005) -> TaiXiuSubscribeMessage; tFB/tFBB/sid from real inbound response")
    void subscribe() throws Exception {
        BettingMiniMessage parsed = parse("subscribe.json");

        assertThat(parsed).isInstanceOf(TaiXiuSubscribeMessage.class);
        assertThat(parsed.getCmd()).isEqualTo(1005);

        SubscribeMessage asSubscribe = (SubscribeMessage) parsed;
        // Real inbound subscribe response (SubscribeResponse.js): tFB=50000 is the
        // betting window, tFBB=3000 the late-bet cutoff.
        assertThat(asSubscribe.getTimeForBetting()).isEqualTo(50000L);
        assertThat(asSubscribe.getTimeForDecision()).isEqualTo(3000L);

        TaiXiuSubscribeMessage tx = (TaiXiuSubscribeMessage) parsed;
        assertThat(tx.getSid()).isEqualTo(2670594L);
        assertThat(tx.getGS()).isEqualTo(3);
        assertThat(tx.getRmT()).isEqualTo(13535L);
        assertThat(tx.getOdE()).isEqualTo(3.99);
        assertThat(tx.isIES()).isTrue();
    }

    @Test
    @DisplayName("getTimeForDecision() falls back to default 3000ms only when tFBB is absent/zero")
    void timeForDecisionDefault() throws Exception {
        // Absent tFBB (the 114 shape) -> default 3000ms cutoff.
        String noTfbb = "{\"cmd\":1005,\"tFB\":51000,\"sid\":5966,\"gS\":3,\"rmT\":12460}";
        TaiXiuSubscribeMessage absent =
                (TaiXiuSubscribeMessage) newMapper().readValue(noTfbb, BettingMiniMessage.class);
        assertThat(absent.getTimeForDecision())
                .as("no tFBB -> default 3000ms")
                .isEqualTo(3000L);

        // tFBB:0 is treated the same as absent -> default 3000ms.
        String zeroTfbb = "{\"cmd\":1005,\"tFB\":51000,\"tFBB\":0,\"sid\":5966}";
        TaiXiuSubscribeMessage zero =
                (TaiXiuSubscribeMessage) newMapper().readValue(zeroTfbb, BettingMiniMessage.class);
        assertThat(zero.getTimeForDecision())
                .as("tFBB=0 -> default 3000ms")
                .isEqualTo(3000L);

        // A real non-3000 tFBB passes through untouched — the default only fills absent/zero.
        String tfbb5000 = "{\"cmd\":1005,\"tFB\":51000,\"tFBB\":5000,\"sid\":5966}";
        TaiXiuSubscribeMessage explicit =
                (TaiXiuSubscribeMessage) newMapper().readValue(tfbb5000, BettingMiniMessage.class);
        assertThat(explicit.getTimeForDecision())
                .as("tFBB=5000 -> 5000 (default does not override a real value)")
                .isEqualTo(5000L);
    }

    @Test
    @DisplayName("startGame (cmd=1002) -> TaiXiuStartGameMessage with sessionId, odE, iES")
    void startGame() throws Exception {
        BettingMiniMessage parsed = parse("startGame.json");

        assertThat(parsed).isInstanceOf(TaiXiuStartGameMessage.class);
        assertThat(parsed.getCmd()).isEqualTo(1002);

        StartGameMessage asStart = (StartGameMessage) parsed;
        assertThat(asStart.getSessionId()).isEqualTo(2670572L);

        TaiXiuStartGameMessage tx = (TaiXiuStartGameMessage) parsed;
        assertThat(tx.getOdE()).isEqualTo(3.99);
        assertThat(tx.isIES()).isTrue();
    }

    @Test
    @DisplayName("endGame full refund (End.js) -> dice parsed; winnings 0, effective wagered 0 (AD-11)")
    void endGameFullRefund() throws Exception {
        BettingMiniMessage parsed = parse("endGame_fullRefund.json");

        assertThat(parsed).isInstanceOf(TaiXiuEndGameMessage.class);
        assertThat(parsed.getCmd()).isEqualTo(1004);

        TaiXiuEndGameMessage end = (TaiXiuEndGameMessage) parsed;
        // Dice 1+6+6=13 -> Tai.
        assertThat(end.getD1()).isEqualTo(1);
        assertThat(end.getD2()).isEqualTo(6);
        assertThat(end.getD3()).isEqualTo(6);
        // Captured accounting: gB=gR=GX=500000, G=0 (GX = gR + G).
        assertThat(end.getGB()).isEqualTo(500000L);
        assertThat(end.getGR()).isEqualTo(500000L);
        assertThat(end.getG()).isZero();
        assertThat(end.getGX()).isEqualTo(500000L);

        EndGameMessage asEnd = (EndGameMessage) parsed;
        // #3: the captured Tai Xiu EndGame carries NO sid — getSessionId() returns 0;
        // the bot correlates via the tracked session (sidStore), not this field.
        assertThat(asEnd.getSessionId()).isZero();

        // AD-11: a 100%-refunded round must net to zero stake and zero win — NOT a 500k loss.
        assertThat(((HasBetTotals) end).betAmountFor("any-bot")).isZero();   // gB - gR = 0
        assertThat(((HasBetTotals) end).betCountFor("any-bot")).isZero();    // no effective stake
        assertThat(((HasBotWinnings) end).winningsFor("any-bot")).isZero();  // winnings = GX - gR = 0
        assertThat(((HasJackpot) end).jackpotFor("any-bot")).isZero();       // iJp=false
    }

    @Test
    @DisplayName("endGame partial refund -> winnings = G (NOT GX-gB), effective wagered = gB-gR (AD-11)")
    void endGamePartialRefund() throws Exception {
        TaiXiuEndGameMessage end = (TaiXiuEndGameMessage) parse("endGame_partialRefund.json");

        assertThat(end.getGB()).isEqualTo(500000L);
        assertThat(end.getGR()).isEqualTo(200000L);
        assertThat(end.getG()).isEqualTo(120000L);
        assertThat(end.getGX()).isEqualTo(320000L);   // GX = gR + G = 200000 + 120000

        // effective wagered = gB - gR = 300000.
        assertThat(((HasBetTotals) end).betAmountFor("any-bot")).isEqualTo(300000L);
        assertThat(((HasBetTotals) end).betCountFor("any-bot")).isEqualTo(1);
        // winnings = GX - gR = 120000 (== G here, since GX = gR + G). A regression to GX-gB would give
        // 320000-500000 = -180000 (floored to 0) — provably different from G, so this
        // assertion truly pins the G-based formula.
        assertThat(((HasBotWinnings) end).winningsFor("any-bot")).isEqualTo(120000L);
        assertThat(((HasBotWinnings) end).winningsFor("any-bot"))
                .as("winnings must be GX-gR, not GX-gB")
                .isNotEqualTo(Math.max(0L, end.getGX() - end.getGB()));
    }

    @Test
    @DisplayName("endGame zero refund -> winnings = G (NOT GX-gB), effective wagered = gB (AD-11)")
    void endGameNoRefund() throws Exception {
        TaiXiuEndGameMessage end = (TaiXiuEndGameMessage) parse("endGame_noRefund.json");

        assertThat(end.getGB()).isEqualTo(500000L);
        assertThat(end.getGR()).isZero();
        assertThat(end.getG()).isEqualTo(80000L);
        assertThat(end.getGX()).isEqualTo(80000L);    // GX = gR + G = 0 + 80000

        // No refund -> full bet was at risk.
        assertThat(((HasBetTotals) end).betAmountFor("any-bot")).isEqualTo(500000L);
        assertThat(((HasBetTotals) end).betCountFor("any-bot")).isEqualTo(1);
        // winnings = GX - gR = 80000 (== G here, since GX = gR + G). A regression to GX-gB would give 80000-500000 = -420000
        // (floored to 0) — provably different from G.
        assertThat(((HasBotWinnings) end).winningsFor("any-bot")).isEqualTo(80000L);
        assertThat(((HasBotWinnings) end).winningsFor("any-bot"))
                .as("winnings must be GX-gR, not GX-gB")
                .isNotEqualTo(Math.max(0L, end.getGX() - end.getGB()));
    }

    @Test
    @DisplayName("winnings floors at 0 when GX < gR (defensive); winnings = GX - gR")
    void winningsNeverNegative() {
        // Winnings = max(0, GX - gR). A frame where the refund exceeds the gross
        // settlement (GX < gR) would compute negative — must floor to 0.
        TaiXiuEndGameMessage loss = new TaiXiuEndGameMessage(
                1004, 1, 2, 3,
                /*gB*/ 500000L, /*gR*/ 300000L, /*G*/ 0L, /*GX*/ 100000L,
                0L, 0L, 0L, 0L, false, 0L, /*tJpV*/ 0L);
        assertThat(((HasBotWinnings) loss).winningsFor("x")).isZero(); // max(0, 100000 - 300000)
        // Effective stake = gB - gR.
        assertThat(((HasBetTotals) loss).betAmountFor("x")).isEqualTo(200000L);
    }

    @Test
    @DisplayName("P_114 jackpot frame: G/gR absent -> winnings = GX (gross return)")
    void winningsFromGXWhenGandGRabsent() {
        // The P_114 taixiuJackpotPlugin EndGame omits G and gR (default 0), carrying
        // only gB + GX. Winnings = GX - gR = GX. Reading the G field (old contract)
        // would have returned 0 — the bug that under-reported every RIK win.
        TaiXiuEndGameMessage win = new TaiXiuEndGameMessage(
                1104, 4, 6, 3,
                /*gB*/ 56320000L, /*gR*/ 0L, /*G*/ 0L, /*GX*/ 91443200L,
                0L, 0L, 0L, 0L, false, 0L, /*tJpV*/ 0L);
        assertThat(((HasBotWinnings) win).winningsFor("x")).isEqualTo(91443200L);
        assertThat(((HasBetTotals) win).betAmountFor("x")).isEqualTo(56320000L);
    }

    @Test
    @DisplayName("jackpot returned only when iJp=true")
    void jackpotGated() {
        TaiXiuEndGameMessage withJp = new TaiXiuEndGameMessage(
                1004, 1, 2, 3,
                500000L, 0L, 0L, 500000L,
                0L, 0L, 0L, 0L, /*iJp*/ true, /*jpV*/ 123456L, /*tJpV*/ 0L);
        assertThat(((HasJackpot) withJp).jackpotFor("x")).isEqualTo(123456L);

        TaiXiuEndGameMessage noJp = new TaiXiuEndGameMessage(
                1004, 1, 2, 3,
                500000L, 0L, 0L, 500000L,
                0L, 0L, 0L, 0L, /*iJp*/ false, /*jpV*/ 123456L, /*tJpV*/ 0L);
        assertThat(((HasJackpot) noJp).jackpotFor("x")).isZero();
    }
}
