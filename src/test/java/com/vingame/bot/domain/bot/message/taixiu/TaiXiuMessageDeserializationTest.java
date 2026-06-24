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
    @DisplayName("subscribe (cmd=1005) -> TaiXiuSubscribeMessage; timing accessors default to 0 (OI-5)")
    void subscribe() throws Exception {
        BettingMiniMessage parsed = parse("subscribe.json");

        assertThat(parsed).isInstanceOf(TaiXiuSubscribeMessage.class);
        assertThat(parsed.getCmd()).isEqualTo(1005);

        SubscribeMessage asSubscribe = (SubscribeMessage) parsed;
        // No timing fields were captured on the Tai Xiu subscribe frame (OI-5).
        assertThat(asSubscribe.getTimeForBetting()).isZero();
        assertThat(asSubscribe.getTimeForDecision()).isZero();
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
        // Captured accounting: gB=gR=GX=500000.
        assertThat(end.getGB()).isEqualTo(500000L);
        assertThat(end.getGR()).isEqualTo(500000L);
        assertThat(end.getGX()).isEqualTo(500000L);

        EndGameMessage asEnd = (EndGameMessage) parsed;
        assertThat(asEnd.getSessionId()).isEqualTo(2670572L);

        // AD-11: a 100%-refunded round must net to zero stake and zero win — NOT a 500k loss.
        assertThat(((HasBetTotals) end).betAmountFor("any-bot")).isZero();   // gB - gR = 0
        assertThat(((HasBetTotals) end).betCountFor("any-bot")).isZero();    // no effective stake
        assertThat(((HasBotWinnings) end).winningsFor("any-bot")).isZero();  // GX - gB = 0
        assertThat(((HasJackpot) end).jackpotFor("any-bot")).isZero();       // iJp=false
    }

    @Test
    @DisplayName("endGame partial refund -> winnings = GX-gB, effective wagered = gB-gR (AD-11)")
    void endGamePartialRefund() throws Exception {
        TaiXiuEndGameMessage end = (TaiXiuEndGameMessage) parse("endGame_partialRefund.json");

        assertThat(end.getGB()).isEqualTo(500000L);
        assertThat(end.getGR()).isEqualTo(200000L);
        assertThat(end.getGX()).isEqualTo(700000L);

        // effective wagered = gB - gR = 300000.
        assertThat(((HasBetTotals) end).betAmountFor("any-bot")).isEqualTo(300000L);
        assertThat(((HasBetTotals) end).betCountFor("any-bot")).isEqualTo(1);
        // winnings = GX - gB = 200000.
        assertThat(((HasBotWinnings) end).winningsFor("any-bot")).isEqualTo(200000L);
    }

    @Test
    @DisplayName("endGame zero refund -> effective wagered = gB; loss yields 0 winnings (AD-11)")
    void endGameNoRefund() throws Exception {
        TaiXiuEndGameMessage end = (TaiXiuEndGameMessage) parse("endGame_noRefund.json");

        assertThat(end.getGB()).isEqualTo(500000L);
        assertThat(end.getGR()).isZero();
        assertThat(end.getGX()).isZero();

        // No refund -> full bet was at risk.
        assertThat(((HasBetTotals) end).betAmountFor("any-bot")).isEqualTo(500000L);
        assertThat(((HasBetTotals) end).betCountFor("any-bot")).isEqualTo(1);
        // GX=0 < gB -> winnings floored at 0 (a loss), no negative metric.
        assertThat(((HasBotWinnings) end).winningsFor("any-bot")).isZero();
    }

    @Test
    @DisplayName("winnings floors at 0 when GX < gB (loss); never negative")
    void winningsNeverNegative() {
        TaiXiuEndGameMessage loss = new TaiXiuEndGameMessage(
                1004, 1L, 1, 2, 3,
                /*gB*/ 500000L, /*gR*/ 0L, /*GX*/ 100000L,
                0L, 0L, 0L, 0L, false, 0L);
        assertThat(((HasBotWinnings) loss).winningsFor("x")).isZero();
        // Effective stake still the full bet (no refund).
        assertThat(((HasBetTotals) loss).betAmountFor("x")).isEqualTo(500000L);
    }

    @Test
    @DisplayName("jackpot returned only when iJp=true")
    void jackpotGated() {
        TaiXiuEndGameMessage withJp = new TaiXiuEndGameMessage(
                1004, 1L, 1, 2, 3,
                500000L, 0L, 500000L,
                0L, 0L, 0L, 0L, /*iJp*/ true, /*jpV*/ 123456L);
        assertThat(((HasJackpot) withJp).jackpotFor("x")).isEqualTo(123456L);

        TaiXiuEndGameMessage noJp = new TaiXiuEndGameMessage(
                1004, 1L, 1, 2, 3,
                500000L, 0L, 500000L,
                0L, 0L, 0L, 0L, /*iJp*/ false, /*jpV*/ 123456L);
        assertThat(((HasJackpot) noJp).jackpotFor("x")).isZero();
    }
}
