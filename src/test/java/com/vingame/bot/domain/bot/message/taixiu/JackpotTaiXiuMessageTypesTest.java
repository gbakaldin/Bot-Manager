package com.vingame.bot.domain.bot.message.taixiu;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.GameMessageTypesResolver;
import com.vingame.bot.domain.bot.message.HasBetTotals;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.HasJackpot;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.TaiXiuMessageTypes;
import com.vingame.bot.domain.brand.model.ProductCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 verification (TAI_XIU_114_JACKPOT plan AD-5/AD-6): the P_114 jackpot
 * provider exposes the +100 CMDs (1105/1102/1104/1100), registers the <b>reused</b>
 * inbound classes (AD-3) against {@code "1105"/"1102"/"1104"}, sets the auto-bet flag,
 * is wired into {@code resolveTaiXiu(P_114)}, and the 114 capture-derived fixtures
 * round-trip into those reused classes with the bot-relevant fields intact + the
 * refund-aware accounting (AD-9) holding for full / partial / zero refund.
 */
@DisplayName("JackpotTaiXiuMessageTypes (P_114) provider + deserialization")
class JackpotTaiXiuMessageTypesTest {

    private final TaiXiuMessageTypes provider = new JackpotTaiXiuMessageTypes();

    /* ---- provider shape: +100 CMDs, a-flag, reused inbound classes ---- */

    @Test
    @DisplayName("cmdOffset 100 -> effective cmds 1105/1102/1104/1100")
    void offsetShiftsAllFourCmds() {
        assertThat(provider.cmdOffset()).isEqualTo(100);
        assertThat(provider.subscribeCmd()).isEqualTo(1105);
        assertThat(provider.startGameCmd()).isEqualTo(1102);
        assertThat(provider.endGameCmd()).isEqualTo(1104);
        assertThat(provider.betCmd()).isEqualTo(1100);
    }

    @Test
    @DisplayName("emitsAutoBetFlag() is true (114 bet carries a:false) — 116 default is false")
    void emitsAutoBetFlag() {
        assertThat(provider.emitsAutoBetFlag()).isTrue();
        assertThat(new MiniGameTaiXiuMessageTypes().emitsAutoBetFlag()).isFalse();
    }

    @Test
    @DisplayName("reuses the 116 inbound classes (AD-3); md5/updateBet null")
    void reusesInboundClasses() {
        assertThat(provider.subscribeType()).isEqualTo(TaiXiuSubscribeMessage.class);
        assertThat(provider.startGameType()).isEqualTo(TaiXiuStartGameMessage.class);
        assertThat(provider.endGameType()).isEqualTo(TaiXiuEndGameMessage.class);
        assertThat(provider.startGameMd5Type()).isNull();
        assertThat(provider.updateBetType()).isNull();
    }

    @Test
    @DisplayName("getTypeRegistrations binds the reused classes against 1105/1102/1104 (no bet)")
    void typeRegistrationsAreShiftedByPlus100() {
        NamedType[] regs = provider.getTypeRegistrations();
        assertThat(regs).hasSize(3);
        Map<String, Class<?>> byName = Arrays.stream(regs)
                .collect(Collectors.toMap(NamedType::getName, NamedType::getType));
        assertThat(byName).containsOnlyKeys("1105", "1102", "1104");
        assertThat(byName.get("1105")).isEqualTo(TaiXiuSubscribeMessage.class);
        assertThat(byName.get("1102")).isEqualTo(TaiXiuStartGameMessage.class);
        assertThat(byName.get("1104")).isEqualTo(TaiXiuEndGameMessage.class);
        // Bet (1100) is outbound-only and must NOT be registered.
        assertThat(byName).doesNotContainKey("1100");
    }

    /* ---- resolver wiring (AD-6) ---- */

    @Test
    @DisplayName("resolveTaiXiu(P_114) returns the jackpot provider")
    void resolverReturnsJackpotProvider() {
        TaiXiuMessageTypes resolved = GameMessageTypesResolver.resolveTaiXiu(ProductCode.P_114);
        assertThat(resolved).isInstanceOf(JackpotTaiXiuMessageTypes.class);
        assertThat(resolved.cmdOffset()).isEqualTo(100);
        assertThat(resolved.emitsAutoBetFlag()).isTrue();
        assertThat(resolved.getTypeRegistrations()).hasSize(3);
    }

    @Test
    @DisplayName("resolveTaiXiu(P_116) still returns the offset-0 provider (no 114 regression)")
    void resolverP116Unchanged() {
        TaiXiuMessageTypes resolved = GameMessageTypesResolver.resolveTaiXiu(ProductCode.P_116);
        assertThat(resolved).isInstanceOf(MiniGameTaiXiuMessageTypes.class);
        assertThat(resolved.cmdOffset()).isZero();
        assertThat(resolved.emitsAutoBetFlag()).isFalse();
    }

    @Test
    @DisplayName("resolveTaiXiu(P_066) still throws 'not yet implemented'")
    void resolverUnimplementedStillThrows() {
        assertThatThrownBy(() -> GameMessageTypesResolver.resolveTaiXiu(ProductCode.P_066))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ProductCode.P_066.getCode())
                .hasMessageContaining("not yet implemented");
    }

    /* ---- 114 fixtures round-trip through the provider's +100 registrations ---- */

    private ObjectMapper newMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(provider.getTypeRegistrations());
        return mapper;
    }

    private BettingMiniMessage parse(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/messages/taixiu/jackpot/" + name)) {
            assertThat(in).as("fixture /messages/taixiu/jackpot/" + name).isNotNull();
            return newMapper().readValue(in.readAllBytes(), BettingMiniMessage.class);
        }
    }

    @Test
    @DisplayName("subscribe (cmd=1105) -> TaiXiuSubscribeMessage; tFB/sid/gS/rmT from the 114 capture")
    void subscribeRoundTrips() throws Exception {
        BettingMiniMessage parsed = parse("subscribe.json");

        assertThat(parsed).isInstanceOf(TaiXiuSubscribeMessage.class);
        assertThat(parsed.getCmd()).isEqualTo(1105);

        SubscribeMessage asSubscribe = (SubscribeMessage) parsed;
        // 114 capture: tFB=51000 betting window. No tFBB in 114 -> getTimeForDecision()=0.
        assertThat(asSubscribe.getTimeForBetting()).isEqualTo(51000L);
        assertThat(asSubscribe.getTimeForDecision()).isZero();

        TaiXiuSubscribeMessage tx = (TaiXiuSubscribeMessage) parsed;
        assertThat(tx.getSid()).isEqualTo(5966L);
        assertThat(tx.getGS()).isEqualTo(3);
        assertThat(tx.getRmT()).isEqualTo(12460L);
        // odE:-1 in 114 (modeled, not consumed); the htr[] (with sd/gid) is ignored.
        assertThat(tx.getOdE()).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("startGame (cmd=1102) -> TaiXiuStartGameMessage; gid extra tolerated, odE defaults, sid read")
    void startGameRoundTrips() throws Exception {
        BettingMiniMessage parsed = parse("startGame.json");

        assertThat(parsed).isInstanceOf(TaiXiuStartGameMessage.class);
        assertThat(parsed.getCmd()).isEqualTo(1102);

        StartGameMessage asStart = (StartGameMessage) parsed;
        assertThat(asStart.getSessionId()).isEqualTo(5971L);

        TaiXiuStartGameMessage tx = (TaiXiuStartGameMessage) parsed;
        // 114 StartGame has no odE -> default 0.0 (not consumed). gid:"102" extra ignored.
        assertThat(tx.getOdE()).isZero();
        assertThat(tx.isIES()).isFalse();
    }

    @Test
    @DisplayName("endGame full refund (capture: gB=gR=GX=500k, G=0) -> winnings 0, effective wagered 0")
    void endGameFullRefundRoundTrips() throws Exception {
        BettingMiniMessage parsed = parse("endGame_fullRefund.json");

        assertThat(parsed).isInstanceOf(TaiXiuEndGameMessage.class);
        assertThat(parsed.getCmd()).isEqualTo(1104);

        TaiXiuEndGameMessage end = (TaiXiuEndGameMessage) parsed;
        assertThat(end.getGB()).isEqualTo(500000L);
        assertThat(end.getGR()).isEqualTo(500000L);
        assertThat(end.getG()).isZero();
        assertThat(end.getGX()).isEqualTo(500000L);

        // AD-9: a fully-refunded 114 round nets to zero stake and zero win.
        assertThat(((HasBetTotals) end).betAmountFor("any")).isZero();   // gB - gR = 0
        assertThat(((HasBetTotals) end).betCountFor("any")).isZero();
        assertThat(((HasBotWinnings) end).winningsFor("any")).isZero();  // G = 0
        // Live tJpV (the jackpot pool) is NOT a per-bot win; iJp=false -> 0.
        assertThat(((HasJackpot) end).jackpotFor("any")).isZero();
    }

    @Test
    @DisplayName("endGame partial refund -> winnings = G, effective wagered = gB-gR")
    void endGamePartialRefundRoundTrips() throws Exception {
        TaiXiuEndGameMessage end = (TaiXiuEndGameMessage) parse("endGame_partialRefund.json");
        assertThat(end.getCmd()).isEqualTo(1104);
        assertThat(((HasBetTotals) end).betAmountFor("any")).isEqualTo(300000L); // 500k-200k
        assertThat(((HasBetTotals) end).betCountFor("any")).isEqualTo(1);
        assertThat(((HasBotWinnings) end).winningsFor("any")).isEqualTo(120000L); // G
    }

    @Test
    @DisplayName("endGame zero refund -> winnings = G, effective wagered = gB")
    void endGameNoRefundRoundTrips() throws Exception {
        TaiXiuEndGameMessage end = (TaiXiuEndGameMessage) parse("endGame_noRefund.json");
        assertThat(end.getCmd()).isEqualTo(1104);
        assertThat(((HasBetTotals) end).betAmountFor("any")).isEqualTo(500000L); // gR=0
        assertThat(((HasBetTotals) end).betCountFor("any")).isEqualTo(1);
        assertThat(((HasBotWinnings) end).winningsFor("any")).isEqualTo(80000L); // G
    }

    /* ---- the bet fixture is the outbound 114 shape (cmd 1100 + a:false) ---- */

    @Test
    @DisplayName("bet fixture is cmd 1100 with the extra a:false field")
    void betFixtureCarriesAFalse() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var node = mapper.readTree(
                getClass().getResourceAsStream("/messages/taixiu/jackpot/bet.json"));
        assertThat(node.get("cmd").asInt()).isEqualTo(1100);
        assertThat(node.get("aid").asInt()).isEqualTo(1);
        assertThat(node.has("a")).isTrue();
        assertThat(node.get("a").asBoolean()).isFalse();
    }
}
