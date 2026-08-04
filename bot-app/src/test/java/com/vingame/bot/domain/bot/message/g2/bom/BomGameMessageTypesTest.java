package com.vingame.bot.domain.bot.message.g2.bom;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that BomGameMessageTypes wires the correct concrete subclasses into Jackson's
 * polymorphic dispatch and that the resulting deserialization populates the abstract-base
 * accessors the bot actually reads.
 * <p>
 * Mapper configuration mirrors {@code BettingMiniGameBot.botBehaviorScenario()} so the
 * round-trip exercises the same code path the bot uses at runtime.
 */
@DisplayName("BomGameMessageTypes - polymorphic deserialization")
class BomGameMessageTypesTest {

    private static final int BOM_OFFSET = 2000;

    private ObjectMapper newMapper(boolean md5) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new BomGameMessageTypes().getTypeRegistrations(BOM_OFFSET, md5));
        return mapper;
    }

    private String loadFixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/messages/bom/" + name)) {
            assertThat(in).as("fixture /messages/bom/" + name).isNotNull();
            return new String(in.readAllBytes());
        }
    }

    @Test
    @DisplayName("subscribe (cmd=5000) → BomSubscribeMessage with parsed timing + nested fields")
    void subscribe() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("subscribe.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(BomSubscribeMessage.class);

        SubscribeMessage asSubscribe = (SubscribeMessage) parsed;
        assertThat(asSubscribe.getTimeForBetting()).isEqualTo(15000);
        assertThat(asSubscribe.getTimeForDecision()).isEqualTo(2000);

        BomSubscribeMessage bom = (BomSubscribeMessage) parsed;
        assertThat(bom.getSid()).isEqualTo(422069L);
        assertThat(bom.getGS()).isEqualTo(2);
        assertThat(bom.getBs()).hasSize(2);
        assertThat(bom.getPR()).hasSize(2);
        assertThat(bom.getHtr()).hasSize(1);
        assertThat(bom.getLJp()).isNotNull();
        assertThat(bom.getLJp().getD1()).isEqualTo(3);
    }

    @Test
    @DisplayName("startGame non-md5 (cmd=5005) → BomStartGameMessage with sessionId")
    void startGame() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("startGame.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(BomStartGameMessage.class);
        assertThat(parsed).isNotInstanceOf(BomStartGameMd5Message.class);

        StartGameMessage asStart = (StartGameMessage) parsed;
        assertThat(asStart.getSessionId()).isEqualTo(422070L);
    }

    @Test
    @DisplayName("startGame md5 (cmd=5005, md5=true) → BomStartGameMd5Message with sessionId + hash")
    void startGameMd5() throws Exception {
        ObjectMapper mapper = newMapper(true);
        String json = loadFixture("startGameMd5.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(BomStartGameMd5Message.class);

        StartGameMd5Message asMd5 = (StartGameMd5Message) parsed;
        assertThat(asMd5.getSessionId()).isEqualTo(422070L);
        assertThat(asMd5.getMd5Hash()).isEqualTo("9e107d9d372bb6826bd81d3542a419d6");
    }

    @Test
    @DisplayName("updateBet (cmd=5002) → BomUpdateBetMessage with gameState + remainingTime")
    void updateBet() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("updateBet.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(BomUpdateBetMessage.class);

        UpdateBetMessage asUpdate = (UpdateBetMessage) parsed;
        assertThat(asUpdate.getGameState()).isEqualTo(2);

        BomUpdateBetMessage bom = (BomUpdateBetMessage) parsed;
        assertThat(bom.getRemainingTime()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("endGame (cmd=5006) → BomEndGameMessage with dice + bets")
    void endGame() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("endGame.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(BomEndGameMessage.class);

        BomEndGameMessage bom = (BomEndGameMessage) parsed;
        assertThat(bom.getSid()).isEqualTo(422070L);
        assertThat(bom.getD1()).isEqualTo(4);
        assertThat(bom.getD2()).isEqualTo(5);
        assertThat(bom.getD3()).isEqualTo(6);
        assertThat(bom.getBs()).hasSize(2);
    }

    @Test
    @DisplayName("HasJackpot: returns tJpV when iJp=true, 0 otherwise")
    void hasJackpot() {
        BomEndGameMessage noJp = new BomEndGameMessage(5006, 0, 0, 0, null, 0, 7500, false, 0, 0, null, null);
        BomEndGameMessage withJp = new BomEndGameMessage(5006, 0, 0, 0, null, 0, 7500, true, 0, 0, null, null);

        assertThat(noJp.jackpotFor("any-bot")).isZero();
        assertThat(withJp.jackpotFor("any-bot")).isEqualTo(7500L);
    }

    @Test
    @DisplayName("Cross-product guard: a Bom-only mapper rejects Nohu cmd values")
    void shouldNotDispatchAcrossProducts() throws Exception {
        // Mapper configured ONLY with BOM (offset 2000); the Nohu subscribe fixture uses cmd=7000,
        // which is not registered. Jackson should throw rather than silently fall back to a
        // sibling subtype — that is the polymorphism guarantee the bot's deserialization relies on.
        ObjectMapper mapper = newMapper(false);
        String nohuSubscribe;
        try (var in = getClass().getResourceAsStream("/messages/nohu/subscribe.json")) {
            assertThat(in).isNotNull();
            nohuSubscribe = new String(in.readAllBytes());
        }

        assertThatThrownBy(() -> mapper.readValue(nohuSubscribe, BettingMiniMessage.class))
                .isInstanceOf(InvalidTypeIdException.class)
                .hasMessageContaining("7000");
    }
}
