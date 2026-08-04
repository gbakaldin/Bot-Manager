package com.vingame.bot.domain.bot.message;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.coordination.CrowdOption;
import com.vingame.bot.domain.bot.message.g2.b52.B52EndGameMessage;
import com.vingame.bot.domain.bot.message.g2.b52.B52GameMessageTypes;
import com.vingame.bot.domain.bot.message.g2.b52.B52SubscribeMessage;
import com.vingame.bot.domain.bot.message.g2.bom.BomEndGameMessage;
import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g2.bom.BomSubscribeMessage;
import com.vingame.bot.domain.bot.message.g3.tip.TipEndGameMessage;
import com.vingame.bot.domain.bot.message.g3.tip.TipGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.tip.TipSubscribeMessage;
import com.vingame.bot.domain.bot.message.g3.tip.TipUpdateBetMessage;
import com.vingame.bot.domain.bot.message.g4.nohu.NohuEndGameMessage;
import com.vingame.bot.domain.bot.message.g4.nohu.NohuGameMessageTypes;
import com.vingame.bot.domain.bot.message.g4.nohu.NohuSubscribeMessage;
import com.vingame.bot.domain.bot.message.taixiu.TaiXiuEndGameMessage;
import com.vingame.bot.domain.bot.message.taixiu.TaiXiuSubscribeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CROWD_AWARE_COORDINATION Phase 3: the {@link HasCrowdBets} marker exposes each
 * product's parsed {@code bs} crowd array as a {@code List<CrowdOption>} keyed on
 * {@code eid}, carrying the per-option aggregate stake {@code v}.
 *
 * <p>Pins the product map from the plan's Findings:
 * <ul>
 *   <li><b>Tip</b>: UpdateBet (the live intra-round signal), EndGame, and Subscribe
 *       all carry {@code bs} → {@link HasCrowdBets}.</li>
 *   <li><b>BOM / B52 / Nohu</b>: EndGame + Subscribe carry {@code bs}; their
 *       UpdateBet ({@code {gS, rmT}} only) is NOT a {@link HasCrowdBets}.</li>
 *   <li><b>Tai Xiu</b>: no {@code bs} on any parsed frame → NOT a {@link HasCrowdBets}
 *       (crowd-aware is a safe no-op, AD-C7).</li>
 * </ul>
 * Mapper configuration mirrors {@code BettingMiniGameBot.botBehaviorScenario()}
 * ({@code FAIL_ON_UNKNOWN_PROPERTIES=false}).
 */
@DisplayName("HasCrowdBets - per-option crowd bs array (Phase 3)")
class HasCrowdBetsTest {

    private String loadFixture(String path) throws Exception {
        try (var in = getClass().getResourceAsStream(path)) {
            assertThat(in).as("fixture " + path).isNotNull();
            return new String(in.readAllBytes());
        }
    }

    private BettingMiniMessage parse(ObjectMapper mapper, String path) throws Exception {
        return mapper.readValue(loadFixture(path), BettingMiniMessage.class);
    }

    private ObjectMapper mapper(Object types, int offset) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        if (types instanceof BomGameMessageTypes t) mapper.registerSubtypes(t.getTypeRegistrations(offset, false));
        if (types instanceof B52GameMessageTypes t) mapper.registerSubtypes(t.getTypeRegistrations(offset, false));
        if (types instanceof NohuGameMessageTypes t) mapper.registerSubtypes(t.getTypeRegistrations(offset, false));
        if (types instanceof TipGameMessageTypes t) mapper.registerSubtypes(t.getTypeRegistrations(offset, false));
        return mapper;
    }

    /* ---- Tip: UpdateBet (intra-round), EndGame, Subscribe all carry bs ---- */

    @Test
    @DisplayName("Tip UpdateBet: HasCrowdBets, crowdBets() returns per-option v (the live signal)")
    void tipUpdateBet() throws Exception {
        BettingMiniMessage parsed = parse(mapper(new TipGameMessageTypes(), 8000), "/messages/tip/updateBet.json");
        assertThat(parsed).isInstanceOf(TipUpdateBetMessage.class).isInstanceOf(HasCrowdBets.class);

        List<CrowdOption> crowd = ((HasCrowdBets) parsed).crowdBets();
        assertThat(crowd).hasSize(2);
        // Fixture bs: [{eid:0, v:8000, b:2000, bc:2}, {eid:1, v:3500, b:500, bc:1}]
        assertThat(crowd).extracting(CrowdOption::optionId).containsExactly(0, 1);
        assertThat(crowd).extracting(CrowdOption::value).containsExactly(8000L, 3500L);
        // b carried on the WithTotal variant (D3, unused by v1 math); bc carried too.
        assertThat(crowd).extracting(CrowdOption::ownBet).containsExactly(2000L, 500L);
        assertThat(crowd).extracting(CrowdOption::count).containsExactly(2, 1);
    }

    @Test
    @DisplayName("Tip EndGame: HasCrowdBets, crowdBets() returns per-option v")
    void tipEndGame() throws Exception {
        BettingMiniMessage parsed = parse(mapper(new TipGameMessageTypes(), 8000), "/messages/tip/endGame.json");
        assertThat(parsed).isInstanceOf(TipEndGameMessage.class).isInstanceOf(HasCrowdBets.class);

        List<CrowdOption> crowd = ((HasCrowdBets) parsed).crowdBets();
        assertThat(crowd).extracting(CrowdOption::optionId).containsExactly(0, 1);
        assertThat(crowd).extracting(CrowdOption::value).containsExactly(8000L, 3500L);
    }

    @Test
    @DisplayName("Tip Subscribe: HasCrowdBets, crowdBets() returns per-option v")
    void tipSubscribe() throws Exception {
        BettingMiniMessage parsed = parse(mapper(new TipGameMessageTypes(), 8000), "/messages/tip/subscribe.json");
        assertThat(parsed).isInstanceOf(TipSubscribeMessage.class).isInstanceOf(HasCrowdBets.class);

        List<CrowdOption> crowd = ((HasCrowdBets) parsed).crowdBets();
        assertThat(crowd).extracting(CrowdOption::optionId).containsExactly(0, 1);
        assertThat(crowd).extracting(CrowdOption::value).containsExactly(5000L, 3000L);
    }

    /* ---- BOM / B52 / Nohu: EndGame + Subscribe carry bs; UpdateBet does NOT ---- */

    @Test
    @DisplayName("BOM EndGame: HasCrowdBets (no b on the BetInfo shape → ownBet 0)")
    void bomEndGame() throws Exception {
        BettingMiniMessage parsed = parse(mapper(new BomGameMessageTypes(), 2000), "/messages/bom/endGame.json");
        assertThat(parsed).isInstanceOf(BomEndGameMessage.class).isInstanceOf(HasCrowdBets.class);

        List<CrowdOption> crowd = ((HasCrowdBets) parsed).crowdBets();
        // Fixture bs: [{eid:0, v:1000, bc:1}, {eid:3, v:5000, bc:1}] — no b field.
        assertThat(crowd).extracting(CrowdOption::optionId).containsExactly(0, 3);
        assertThat(crowd).extracting(CrowdOption::value).containsExactly(1000L, 5000L);
        assertThat(crowd).extracting(CrowdOption::ownBet).containsExactly(0L, 0L);
    }

    @Test
    @DisplayName("BOM Subscribe: HasCrowdBets, carries own b on the WithTotal shape")
    void bomSubscribe() throws Exception {
        BettingMiniMessage parsed = parse(mapper(new BomGameMessageTypes(), 2000), "/messages/bom/subscribe.json");
        assertThat(parsed).isInstanceOf(BomSubscribeMessage.class).isInstanceOf(HasCrowdBets.class);

        List<CrowdOption> crowd = ((HasCrowdBets) parsed).crowdBets();
        assertThat(crowd).extracting(CrowdOption::optionId).containsExactly(0, 3);
        assertThat(crowd).extracting(CrowdOption::value).containsExactly(1000L, 5000L);
        assertThat(crowd).extracting(CrowdOption::ownBet).containsExactly(1000L, 5000L);
    }

    @Test
    @DisplayName("BOM UpdateBet: NOT HasCrowdBets ({gS,rmT} only — no faked intra-round signal)")
    void bomUpdateBetNotCrowd() throws Exception {
        BettingMiniMessage parsed = parse(mapper(new BomGameMessageTypes(), 2000), "/messages/bom/updateBet.json");
        assertThat(parsed).isNotInstanceOf(HasCrowdBets.class);
    }

    @Test
    @DisplayName("B52 EndGame + Subscribe: HasCrowdBets; UpdateBet is NOT")
    void b52() throws Exception {
        BettingMiniMessage end = parse(mapper(new B52GameMessageTypes(), 6000), "/messages/b52/endGame.json");
        assertThat(end).isInstanceOf(B52EndGameMessage.class).isInstanceOf(HasCrowdBets.class);
        assertThat(((HasCrowdBets) end).crowdBets()).extracting(CrowdOption::optionId).containsExactly(2);
        assertThat(((HasCrowdBets) end).crowdBets()).extracting(CrowdOption::value).containsExactly(3000L);

        BettingMiniMessage sub = parse(mapper(new B52GameMessageTypes(), 6000), "/messages/b52/subscribe.json");
        assertThat(sub).isInstanceOf(B52SubscribeMessage.class).isInstanceOf(HasCrowdBets.class);
        assertThat(((HasCrowdBets) sub).crowdBets()).extracting(CrowdOption::value).containsExactly(3000L);

        BettingMiniMessage upd = parse(mapper(new B52GameMessageTypes(), 6000), "/messages/b52/updateBet.json");
        assertThat(upd).isNotInstanceOf(HasCrowdBets.class);
    }

    @Test
    @DisplayName("Nohu EndGame + Subscribe: HasCrowdBets; UpdateBet is NOT")
    void nohu() throws Exception {
        BettingMiniMessage end = parse(mapper(new NohuGameMessageTypes(), 4000), "/messages/nohu/endGame.json");
        assertThat(end).isInstanceOf(NohuEndGameMessage.class).isInstanceOf(HasCrowdBets.class);
        assertThat(((HasCrowdBets) end).crowdBets()).extracting(CrowdOption::optionId).containsExactly(1);
        assertThat(((HasCrowdBets) end).crowdBets()).extracting(CrowdOption::value).containsExactly(2000L);

        BettingMiniMessage sub = parse(mapper(new NohuGameMessageTypes(), 4000), "/messages/nohu/subscribe.json");
        assertThat(sub).isInstanceOf(NohuSubscribeMessage.class).isInstanceOf(HasCrowdBets.class);
        assertThat(((HasCrowdBets) sub).crowdBets()).extracting(CrowdOption::value).containsExactly(2000L);

        BettingMiniMessage upd = parse(mapper(new NohuGameMessageTypes(), 4000), "/messages/nohu/updateBet.json");
        assertThat(upd).isNotInstanceOf(HasCrowdBets.class);
    }

    /* ---- Tai Xiu: no bs on any parsed frame → NOT HasCrowdBets (AD-C7) ---- */

    @Test
    @DisplayName("Tai Xiu Subscribe + EndGame: NOT HasCrowdBets (no bs on the wire — safe no-op, AD-C7)")
    void taiXiuNotCrowd() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(
                new NamedType(TaiXiuSubscribeMessage.class, "1005"),
                new NamedType(TaiXiuEndGameMessage.class, "1004"));

        BettingMiniMessage sub = mapper.readValue(loadFixture("/messages/taixiu/subscribe.json"), BettingMiniMessage.class);
        assertThat(sub).isInstanceOf(TaiXiuSubscribeMessage.class).isNotInstanceOf(HasCrowdBets.class);

        BettingMiniMessage end = mapper.readValue(loadFixture("/messages/taixiu/endGame_fullRefund.json"), BettingMiniMessage.class);
        assertThat(end).isInstanceOf(TaiXiuEndGameMessage.class).isNotInstanceOf(HasCrowdBets.class);
    }
}
