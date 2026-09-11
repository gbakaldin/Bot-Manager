package com.vingame.bot.domain.bot.message.g4.win79;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasCrowdBets;
import com.vingame.bot.domain.bot.message.HasJackpot;
import com.vingame.bot.domain.bot.message.HasJackpotPool;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins WIN79 (P_119) betting-mini polymorphic dispatch and field semantics.
 * <p>
 * <b>Every fixture under {@code /messages/win79/} is a real frame</b> captured from
 * live {@code gourdCrabPlugin} (Bau Cua) traffic on 2026-09-10, not hand-authored —
 * only the subscribe frame's {@code htr} and {@code cH} arrays were trimmed for
 * size, and neither is modelled. Offset is 2000, so CMDs are 5000/5002/5005/5006.
 */
@DisplayName("Win79GameMessageTypes - polymorphic deserialization")
class Win79GameMessageTypesTest {

    private static final int WIN79_OFFSET = 2000;

    private ObjectMapper newMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // md5=false: WIN79 betting-mini is not md5, and startGameMd5Type() is null.
        mapper.registerSubtypes(new Win79GameMessageTypes().getTypeRegistrations(WIN79_OFFSET, false));
        return mapper;
    }

    private String loadFixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/messages/win79/" + name)) {
            assertThat(in).as("fixture /messages/win79/" + name).isNotNull();
            return new String(in.readAllBytes());
        }
    }

    @Test
    @DisplayName("subscribe (cmd=5000) → Win79SubscribeMessage; tFB/tFD drive the bet window")
    void subscribe() throws Exception {
        BettingMiniMessage parsed = newMapper().readValue(loadFixture("subscribe.json"), BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(Win79SubscribeMessage.class);

        SubscribeMessage asSubscribe = (SubscribeMessage) parsed;
        assertThat(asSubscribe.getTimeForBetting()).isEqualTo(25000L);
        assertThat(asSubscribe.getTimeForDecision()).isEqualTo(3000L);

        Win79SubscribeMessage win79 = (Win79SubscribeMessage) parsed;
        assertThat(win79.getSid()).isEqualTo(9640L);
        assertThat(win79.getGS()).isEqualTo(3);
        assertThat(win79.getRmT()).isEqualTo(9275L);
        assertThat(win79.getTJpV()).isEqualTo(209750L);
        // Six options — gourdCrabPlugin is Bau Cua.
        assertThat(win79.getBs()).hasSize(6);
        assertThat(((HasCrowdBets) parsed).crowdBets()).hasSize(6);
    }

    @Test
    @DisplayName("startGame (cmd=5005) → Win79StartGameMessage with sessionId")
    void startGame() throws Exception {
        BettingMiniMessage parsed = newMapper().readValue(loadFixture("startGame.json"), BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(Win79StartGameMessage.class);
        assertThat(((StartGameMessage) parsed).getSessionId()).isEqualTo(9648L);
    }

    @Test
    @DisplayName("updateBet (cmd=5002) → Win79UpdateBetMessage carrying the live crowd array")
    void updateBet() throws Exception {
        BettingMiniMessage parsed = newMapper().readValue(loadFixture("updateBet.json"), BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(Win79UpdateBetMessage.class);

        // WIN79 is the SECOND product (after Tip) with intra-round `bs`. This is what
        // feeds BetCoordinator.observeCrowd the live within-window signal.
        assertThat(parsed).isInstanceOf(HasCrowdBets.class);
        var crowd = ((HasCrowdBets) parsed).crowdBets();
        assertThat(crowd).hasSize(1);
        assertThat(crowd.get(0).optionId()).isEqualTo(5);
        assertThat(crowd.get(0).value()).isEqualTo(1000L);
        assertThat(crowd.get(0).ownBet()).isEqualTo(1000L);
        assertThat(crowd.get(0).count()).isEqualTo(1);
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
    @DisplayName("endGame (cmd=5006) → Win79EndGameMessage with dice and session")
    void endGame() throws Exception {
        BettingMiniMessage parsed = newMapper().readValue(loadFixture("endGame.json"), BettingMiniMessage.class);

        assertThat(parsed).isInstanceOf(Win79EndGameMessage.class);

        Win79EndGameMessage end = (Win79EndGameMessage) parsed;
        assertThat(end.getSessionId()).isEqualTo(9641L);
        assertThat(end.getD1()).isEqualTo(2);
        assertThat(end.getD2()).isEqualTo(1);
        assertThat(end.getD3()).isEqualTo(2);
        assertThat(end.getPs()).hasSize(1);
        assertThat(end.getPs().get(0).uid()).isEqualTo("18_1973");
        assertThat(end.getPs().get(0).wm()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("endGame winnings come from wm — the field BOM and Nohu lack entirely")
    void endGameExposesBotWinnings() throws Exception {
        BettingMiniMessage parsed = newMapper().readValue(loadFixture("endGame.json"), BettingMiniMessage.class);

        // The fixture is a REAL WINNING round, which is the only way this assertion
        // means anything — a zero-win frame could not distinguish "wm is the winnings
        // field" from "wm happens to be 0 like everything else".
        assertThat(parsed).isInstanceOf(HasBotWinnings.class);
        assertThat(((HasBotWinnings) parsed).winningsFor("any-username")).isEqualTo(5000L);
    }

    @Test
    @DisplayName("jpV is the per-user jackpot and tJpV the pool — the TIP convention, inverted vs Bom/Nohu")
    void endGameJackpotFollowsTipConvention() throws Exception {
        BettingMiniMessage parsed = newMapper().readValue(loadFixture("endGame.json"), BettingMiniMessage.class);

        // Bom/Nohu return `iJp ? tJpV : 0` as the per-user payout. WIN79 must NOT:
        // tJpV here is the 210140 pool meter, and returning it as a per-bot jackpot
        // would inflate bot_jackpot_amount_total by five orders of magnitude.
        assertThat(((HasJackpot) parsed).jackpotFor("any-username")).isZero();
        assertThat(((HasJackpotPool) parsed).jackpotPool()).isEqualTo(210140L);
    }

    @Test
    @DisplayName("endGame crowd array maps eid/v/b/bc onto CrowdOption")
    void endGameCrowdBets() throws Exception {
        BettingMiniMessage parsed = newMapper().readValue(loadFixture("endGame.json"), BettingMiniMessage.class);

        var crowd = ((HasCrowdBets) parsed).crowdBets();
        assertThat(crowd).hasSize(6);
        assertThat(crowd).allSatisfy(c -> {
            assertThat(c.value()).isEqualTo(1000L);
            assertThat(c.ownBet()).isEqualTo(1000L);
            assertThat(c.count()).isEqualTo(1);
        });
        assertThat(crowd).extracting("optionId").containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5);
    }
}
