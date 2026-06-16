package com.vingame.bot.domain.bot.message.g2.b52;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that B52GameMessageTypes wires the correct concrete subclasses into Jackson's
 * polymorphic dispatch. Schema is identical to BOM/Nohu but Java types are distinct — these
 * tests pin the type-dispatch wiring at offset 6000.
 */
@DisplayName("B52GameMessageTypes - polymorphic deserialization")
class B52GameMessageTypesTest {

    private static final int B52_OFFSET = 6000;

    private ObjectMapper newMapper(boolean md5) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new B52GameMessageTypes().getTypeRegistrations(B52_OFFSET, md5));
        return mapper;
    }

    private String loadFixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/messages/b52/" + name)) {
            assertThat(in).as("fixture /messages/b52/" + name).isNotNull();
            return new String(in.readAllBytes());
        }
    }

    @Test
    @DisplayName("subscribe (cmd=9000) → B52SubscribeMessage with parsed timing + nested fields")
    void subscribe() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("subscribe.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(B52SubscribeMessage.class);

        SubscribeMessage asSubscribe = (SubscribeMessage) parsed;
        assertThat(asSubscribe.getTimeForBetting()).isEqualTo(18000);
        assertThat(asSubscribe.getTimeForDecision()).isEqualTo(2500);

        B52SubscribeMessage b52 = (B52SubscribeMessage) parsed;
        assertThat(b52.getSid()).isEqualTo(622069L);
        assertThat(b52.getGS()).isEqualTo(2);
        assertThat(b52.getBs()).hasSize(1);
        assertThat(b52.getPR()).hasSize(2);
        assertThat(b52.getHtr()).hasSize(1);
        assertThat(b52.getLJp()).isNotNull();
        assertThat(b52.getLJp().getD1()).isEqualTo(2);
    }

    @Test
    @DisplayName("startGame non-md5 (cmd=9005) → B52StartGameMessage with sessionId")
    void startGame() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("startGame.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(B52StartGameMessage.class);
        assertThat(parsed).isNotInstanceOf(B52StartGameMd5Message.class);

        StartGameMessage asStart = (StartGameMessage) parsed;
        assertThat(asStart.getSessionId()).isEqualTo(622070L);
    }

    @Test
    @DisplayName("startGame md5 (cmd=9005, md5=true) → B52StartGameMd5Message with sessionId + hash")
    void startGameMd5() throws Exception {
        ObjectMapper mapper = newMapper(true);
        String json = loadFixture("startGameMd5.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(B52StartGameMd5Message.class);

        StartGameMd5Message asMd5 = (StartGameMd5Message) parsed;
        assertThat(asMd5.getSessionId()).isEqualTo(622070L);
        assertThat(asMd5.getMd5Hash()).isEqualTo("5d41402abc4b2a76b9719d911017c592");
    }

    @Test
    @DisplayName("updateBet (cmd=9002) → B52UpdateBetMessage with gameState + remainingTime")
    void updateBet() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("updateBet.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(B52UpdateBetMessage.class);

        UpdateBetMessage asUpdate = (UpdateBetMessage) parsed;
        assertThat(asUpdate.getGameState()).isEqualTo(2);

        B52UpdateBetMessage b52 = (B52UpdateBetMessage) parsed;
        assertThat(b52.getRemainingTime()).isEqualTo(11000L);
    }

    @Test
    @DisplayName("endGame (cmd=9006) → B52EndGameMessage with dice + jackpot fields")
    void endGame() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("endGame.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(B52EndGameMessage.class);

        B52EndGameMessage b52 = (B52EndGameMessage) parsed;
        assertThat(b52.getSid()).isEqualTo(622070L);
        assertThat(b52.getD1()).isEqualTo(7);
        assertThat(b52.getD2()).isEqualTo(8);
        assertThat(b52.getD3()).isEqualTo(9);
        assertThat(b52.isIJp()).isTrue();
        assertThat(b52.getJpT()).isEqualTo(2);
    }

    @Test
    @DisplayName("HasJackpot: returns tJpV when iJp=true, 0 otherwise")
    void hasJackpot() {
        B52EndGameMessage noJp = new B52EndGameMessage(9006, 0, 0, 0, null, 0, 12000, false, 0, 0, null, null);
        B52EndGameMessage withJp = new B52EndGameMessage(9006, 0, 0, 0, null, 0, 12000, true, 0, 0, null, null);

        assertThat(noJp.jackpotFor("any-bot")).isZero();
        assertThat(withJp.jackpotFor("any-bot")).isEqualTo(12000L);
    }
}
