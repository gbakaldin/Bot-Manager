package com.vingame.bot.domain.bot.message.g3.tip;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasJackpot;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    /* ----- Capability interfaces — ENDGAME_METRICS marker dispatch ----- */

    @Test
    @DisplayName("HasBotWinnings: returns root wm regardless of userName arg (payload is personalized)")
    void hasBotWinnings() {
        TipEndGameMessage end = newEndGame(/*iJp*/ false, /*jpV*/ 0L, /*tJpV*/ 0L, /*wm*/ 750L,
                List.of(new TipSubscribeMessage.BetInfoWithTotal(0, 1, 100L, 500L)));
        // userName is ignored — Tip delivers EndGame personalized to the recipient.
        assertThat(((HasBotWinnings) end).winningsFor("any-bot")).isEqualTo(750L);
        assertThat(((HasBotWinnings) end).winningsFor(null)).isEqualTo(750L);
    }

    @Test
    @DisplayName("HasJackpot: returns jpV (per-user) when iJp=true, 0 otherwise; tJpV ignored")
    void hasJackpot() {
        // iJp=true -> jpV is the per-user payout (default choice; see Javadoc on
        // TipEndGameMessage.jackpotFor pending iJp=false sample).
        TipEndGameMessage withJp = newEndGame(true, 1_603_000L, 200_000L, 1500L,
                List.<TipSubscribeMessage.BetInfoWithTotal>of());
        assertThat(((HasJackpot) withJp).jackpotFor("any-bot")).isEqualTo(1_603_000L);

        // iJp=false -> 0 even when tJpV > 0 (tJpV is the pool/tier, not a payout).
        TipEndGameMessage noJp = newEndGame(false, 0L, 200_000L, 0L,
                List.<TipSubscribeMessage.BetInfoWithTotal>of());
        assertThat(((HasJackpot) noJp).jackpotFor("any-bot")).isZero();
    }

    @Test
    @DisplayName("HasBetTotals: betAmountFor sums bs[].b (per-user), betCountFor sums bs[].bc")
    void hasBetTotals() {
        TipEndGameMessage end = newEndGame(false, 0L, 0L, 0L, List.of(
                new TipSubscribeMessage.BetInfoWithTotal(0, 2, 2000L, 8000L),
                new TipSubscribeMessage.BetInfoWithTotal(1, 1, 500L, 3500L),
                new TipSubscribeMessage.BetInfoWithTotal(2, 3, 100L, 9000L)
        ));
        HasBetTotals totals = (HasBetTotals) end;
        // Sum of bs[].b — per-user staked amounts, not the v aggregate.
        assertThat(totals.betAmountFor("any-bot")).isEqualTo(2600L);
        // Sum of bs[].bc — per-user bet counts.
        assertThat(totals.betCountFor("any-bot")).isEqualTo(6);
    }

    @Test
    @DisplayName("HasBetTotals: empty/null bs[] yields 0 amount and 0 count")
    void hasBetTotalsEmpty() {
        TipEndGameMessage emptyBs = newEndGame(false, 0L, 0L, 0L, List.<TipSubscribeMessage.BetInfoWithTotal>of());
        assertThat(((HasBetTotals) emptyBs).betAmountFor("any-bot")).isZero();
        assertThat(((HasBetTotals) emptyBs).betCountFor("any-bot")).isZero();

        TipEndGameMessage nullBs = newEndGame(false, 0L, 0L, 0L, null);
        assertThat(((HasBetTotals) nullBs).betAmountFor("any-bot")).isZero();
        assertThat(((HasBetTotals) nullBs).betCountFor("any-bot")).isZero();
    }

    @Test
    @DisplayName("Deserialized endGame.json fixture exercises all three capability interfaces end-to-end")
    void capabilityInterfacesFromFixture() throws Exception {
        ObjectMapper mapper = newMapper(false);
        TipEndGameMessage tip = (TipEndGameMessage) mapper.readValue(
                loadFixture("endGame.json"), BettingMiniMessage.class);

        assertThat(((HasBotWinnings) tip).winningsFor("bot1")).isEqualTo(1500L);
        assertThat(((HasJackpot) tip).jackpotFor("bot1")).isEqualTo(1_603_000L);
        // bs[]: (eid=0, bc=2, b=2000) + (eid=1, bc=1, b=500) -> count 3, amount 2500.
        assertThat(((HasBetTotals) tip).betAmountFor("bot1")).isEqualTo(2500L);
        assertThat(((HasBetTotals) tip).betCountFor("bot1")).isEqualTo(3);
    }

    /** Minimal builder for TipEndGameMessage covering the fields read by the capability methods. */
    private static TipEndGameMessage newEndGame(boolean iJp, long jpV, long tJpV, long wm,
                                                List<TipSubscribeMessage.BetInfoWithTotal> bs) {
        return new TipEndGameMessage(
                /*cmd*/ 11006,
                /*iJ*/ false,
                /*gid*/ 1,
                /*ps*/ null,
                /*tJpV*/ tJpV,
                /*eIn*/ null,
                /*d1*/ 0, /*d2*/ 0, /*d3*/ 0,
                /*iJp*/ iJp,
                /*sid*/ 0L,
                /*bs*/ bs,
                /*jpV*/ jpV,
                /*tJpv2*/ 0L,
                /*jPTp*/ 0,
                /*jpCD*/ null,
                /*wm*/ wm,
                /*sDi*/ null
        );
    }
}
