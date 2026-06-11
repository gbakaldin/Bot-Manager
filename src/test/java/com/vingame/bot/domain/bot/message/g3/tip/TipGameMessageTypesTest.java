package com.vingame.bot.domain.bot.message.g3.tip;

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
 * Verifies that TipGameMessageTypes wires the correct concrete subclasses into Jackson's
 * polymorphic dispatch and that the resulting deserialization populates the abstract-base
 * accessors the bot actually reads.
 * <p>
 * Mapper configuration mirrors {@code BettingMiniGameBot.botBehaviorScenario()} so the
 * round-trip exercises the same code path the bot uses at runtime. Tip uses offset 8000
 * (g3 grouping) — the value is environment-configurable, not a brand-side constant.
 */
@DisplayName("TipGameMessageTypes - polymorphic deserialization")
class TipGameMessageTypesTest {

    private static final int TIP_OFFSET = 8000;

    private ObjectMapper newMapper(boolean md5) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new TipGameMessageTypes().getTypeRegistrations(TIP_OFFSET, md5));
        return mapper;
    }

    private String loadFixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/messages/tip/" + name)) {
            assertThat(in).as("fixture /messages/tip/" + name).isNotNull();
            return new String(in.readAllBytes());
        }
    }

    @Test
    @DisplayName("subscribe (cmd=11000) -> TipSubscribeMessage with parsed timing + nested fields")
    void subscribe() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("subscribe.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(TipSubscribeMessage.class);

        SubscribeMessage asSubscribe = (SubscribeMessage) parsed;
        assertThat(asSubscribe.getTimeForBetting()).isEqualTo(15000L);
        assertThat(asSubscribe.getTimeForDecision()).isEqualTo(2500L);

        TipSubscribeMessage tip = (TipSubscribeMessage) parsed;
        assertThat(tip.getSid()).isEqualTo(822069L);
        assertThat(tip.getGS()).isEqualTo(2);
        assertThat(tip.getBs()).hasSize(2);
        // Tip's BetInfoWithTotal carries both per-user (b) and aggregate (v) bet totals.
        assertThat(tip.getBs().get(0).getB()).isEqualTo(1000L);
        assertThat(tip.getBs().get(0).getV()).isEqualTo(5000L);
        assertThat(tip.getHtr()).hasSize(1);
        assertThat(tip.getJpCD()).isNotNull();
        assertThat(tip.getJpCD().getCM()).isEqualTo("active");
        assertThat(tip.getMB()).isEqualTo(1_000_000_000L);
    }

    @Test
    @DisplayName("startGame non-md5 (cmd=11005) -> TipStartGameMessage with sessionId")
    void startGame() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("startGame.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(TipStartGameMessage.class);
        assertThat(parsed).isNotInstanceOf(TipStartGameMd5Message.class);

        StartGameMessage asStart = (StartGameMessage) parsed;
        assertThat(asStart.getSessionId()).isEqualTo(822070L);

        TipStartGameMessage tip = (TipStartGameMessage) parsed;
        assertThat(tip.getCdt()).isEqualTo("countdown");
        assertThat(tip.getJpCD()).isNotNull();
    }

    @Test
    @DisplayName("startGame md5 (cmd=11005, md5=true) -> TipStartGameMd5Message with sessionId + hash")
    void startGameMd5() throws Exception {
        ObjectMapper mapper = newMapper(true);
        String json = loadFixture("startGameMd5.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(TipStartGameMd5Message.class);

        StartGameMd5Message asMd5 = (StartGameMd5Message) parsed;
        assertThat(asMd5.getSessionId()).isEqualTo(822070L);
        assertThat(asMd5.getMd5Hash()).isEqualTo("ab56b4d92b40713acc5af89985d4b786");
    }

    @Test
    @DisplayName("updateBet (cmd=11002) -> TipUpdateBetMessage; gameState hard-coded 0 pending staging verification")
    void updateBet() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("updateBet.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(TipUpdateBetMessage.class);

        UpdateBetMessage asUpdate = (UpdateBetMessage) parsed;
        // Tip's UpdateBet JSON does not carry gS/rmT — TipUpdateBetMessage.getGameState()
        // returns 0 hard-coded pending a real wire-frame capture in staging. See
        // CLAUDE.md backlog "Verify TipUpdateBetMessage.getGameState() behavior in staging".
        assertThat(asUpdate.getGameState()).isEqualTo(0);

        TipUpdateBetMessage tip = (TipUpdateBetMessage) parsed;
        assertThat(tip.getBs()).hasSize(2);
        assertThat(tip.getBs().get(0).getEid()).isEqualTo(0);
        assertThat(tip.getBs().get(0).getBc()).isEqualTo(2);
        assertThat(tip.getBs().get(0).getB()).isEqualTo(2000L);
        assertThat(tip.getBs().get(0).getV()).isEqualTo(8000L);
    }

    @Test
    @DisplayName("endGame (cmd=11006) -> TipEndGameMessage with per-user wm, jackpot fields, bs[] and ps[]")
    void endGame() throws Exception {
        ObjectMapper mapper = newMapper(false);
        String json = loadFixture("endGame.json");

        BettingMiniMessage parsed = mapper.readValue(json, BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(TipEndGameMessage.class);

        TipEndGameMessage tip = (TipEndGameMessage) parsed;
        assertThat(tip.getSid()).isEqualTo(822070L);
        assertThat(tip.getD1()).isEqualTo(1);
        assertThat(tip.getD2()).isEqualTo(2);
        assertThat(tip.getD3()).isEqualTo(3);
        // Root wm = this bot's winnings — drives HasBotWinnings.
        assertThat(tip.getWm()).isEqualTo(1500L);
        // iJp + jpV / tJpV — drives HasJackpot. jpV chosen as the per-user jackpot value
        // per Javadoc on TipEndGameMessage.getJackpot() (default pending iJp=false sample).
        assertThat(tip.isIJp()).isTrue();
        assertThat(tip.getJpV()).isEqualTo(1_603_000L);
        assertThat(tip.getTJpV()).isEqualTo(200_000L);
        // bs[] carries per-user (b/bc) and aggregate (v) — drives HasBetTotals.
        assertThat(tip.getBs()).hasSize(2);
        assertThat(tip.getBs().get(0).getB()).isEqualTo(2000L);
        assertThat(tip.getBs().get(0).getBc()).isEqualTo(2);
        // ps[] roster — useful for any future cross-player metric.
        assertThat(tip.getPs()).hasSize(2);
        assertThat(tip.getPs().get(0).getUid()).isEqualTo("bot1");
        assertThat(tip.getPs().get(0).getWm()).isEqualTo(1500L);
    }
}
