package com.vingame.bot.domain.bot.message.g4.nohu;

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
 * Verifies that NohuGameMessageTypes wires the correct concrete subclasses into Jackson's
 * polymorphic dispatch. Schema is identical to BOM/B52 but Java types are distinct — these
 * tests pin the type-dispatch wiring at offset 4000.
 */
@DisplayName("NohuGameMessageTypes - polymorphic deserialization")
class NohuGameMessageTypesTest {

    private static final int NOHU_OFFSET = 4000;

    private ObjectMapper newMapper(boolean md5) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new NohuGameMessageTypes().getTypeRegistrations(NOHU_OFFSET, md5));
        return mapper;
    }

    private String loadFixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/messages/nohu/" + name)) {
            assertThat(in).as("fixture /messages/nohu/" + name).isNotNull();
            return new String(in.readAllBytes());
        }
    }

    @Test
    @DisplayName("subscribe (cmd=7000) → NohuSubscribeMessage with parsed timing + nested fields")
    void subscribe() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("subscribe.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(NohuSubscribeMessage.class);

        SubscribeMessage asSubscribe = (SubscribeMessage) parsed;
        assertThat(asSubscribe.getTimeForBetting()).isEqualTo(20000);
        assertThat(asSubscribe.getTimeForDecision()).isEqualTo(3000);

        NohuSubscribeMessage nohu = (NohuSubscribeMessage) parsed;
        assertThat(nohu.getSid()).isEqualTo(522069L);
        assertThat(nohu.getGS()).isEqualTo(2);
        assertThat(nohu.getBs()).hasSize(1);
        assertThat(nohu.getPR()).hasSize(1);
        assertThat(nohu.getHtr()).hasSize(1);
        assertThat(nohu.getLJp()).isNotNull();
        assertThat(nohu.getLJp().getD1()).isEqualTo(7);
    }

    @Test
    @DisplayName("startGame non-md5 (cmd=7005) → NohuStartGameMessage with sessionId")
    void startGame() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("startGame.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(NohuStartGameMessage.class);
        assertThat(parsed).isNotInstanceOf(NohuStartGameMd5Message.class);

        StartGameMessage asStart = (StartGameMessage) parsed;
        assertThat(asStart.getSessionId()).isEqualTo(522070L);
    }

    @Test
    @DisplayName("startGame md5 (cmd=7005, md5=true) → NohuStartGameMd5Message with sessionId + hash")
    void startGameMd5() throws Exception {
        ObjectMapper mapper = newMapper(true);
        String json = loadFixture("startGameMd5.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(NohuStartGameMd5Message.class);

        StartGameMd5Message asMd5 = (StartGameMd5Message) parsed;
        assertThat(asMd5.getSessionId()).isEqualTo(522070L);
        assertThat(asMd5.getMd5Hash()).isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
    }

    @Test
    @DisplayName("updateBet (cmd=7002) → NohuUpdateBetMessage with gameState + remainingTime")
    void updateBet() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("updateBet.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(NohuUpdateBetMessage.class);

        UpdateBetMessage asUpdate = (UpdateBetMessage) parsed;
        assertThat(asUpdate.getGameState()).isEqualTo(3);

        NohuUpdateBetMessage nohu = (NohuUpdateBetMessage) parsed;
        assertThat(nohu.getRemainingTime()).isEqualTo(8000L);
    }

    @Test
    @DisplayName("endGame (cmd=7006) → NohuEndGameMessage with dice")
    void endGame() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("endGame.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(NohuEndGameMessage.class);

        NohuEndGameMessage nohu = (NohuEndGameMessage) parsed;
        assertThat(nohu.getSid()).isEqualTo(522070L);
        assertThat(nohu.getD1()).isEqualTo(1);
        assertThat(nohu.getD2()).isEqualTo(2);
        assertThat(nohu.getD3()).isEqualTo(3);
    }

    @Test
    @DisplayName("HasJackpot: returns tJpV when iJp=true, 0 otherwise")
    void hasJackpot() {
        NohuEndGameMessage noJp = new NohuEndGameMessage(7006, 0, 0, 0, null, 0, 9000, false, 0, 0, null, null);
        NohuEndGameMessage withJp = new NohuEndGameMessage(7006, 0, 0, 0, null, 0, 9000, true, 0, 0, null, null);

        assertThat(noJp.jackpotFor("any-bot")).isZero();
        assertThat(withJp.jackpotFor("any-bot")).isEqualTo(9000L);
    }
}
