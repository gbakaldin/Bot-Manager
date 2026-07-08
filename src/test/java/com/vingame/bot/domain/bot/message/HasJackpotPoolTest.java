package com.vingame.bot.domain.bot.message;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.message.g2.b52.B52EndGameMessage;
import com.vingame.bot.domain.bot.message.g2.b52.B52GameMessageTypes;
import com.vingame.bot.domain.bot.message.g2.bom.BomEndGameMessage;
import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.tip.TipEndGameMessage;
import com.vingame.bot.domain.bot.message.g3.tip.TipGameMessageTypes;
import com.vingame.bot.domain.bot.message.g4.nohu.NohuEndGameMessage;
import com.vingame.bot.domain.bot.message.g4.nohu.NohuGameMessageTypes;
import com.vingame.bot.domain.bot.message.taixiu.TaiXiuEndGameMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase J1 of {@code docs/plans/JACKPOT_SCALE_AND_RAMP.md}: the new
 * {@link HasJackpotPool} marker exposes the game's live running jackpot pool meter
 * ({@code tJpV}) off the EndGame frame, DISTINCT from {@link HasJackpot} (the per-bot
 * payout driving {@code bot_jackpots_total}).
 * <p>
 * Pins that all five supported EndGame classes implement the marker and return the
 * wire {@code tJpV}: the four G2/Tip betting-mini products (which already parsed
 * {@code tJpV}) and the shared {@link TaiXiuEndGameMessage} (which gains {@code tJpV}
 * parsing here). The Tai Xiu tests cover both the P_114 jackpot frame (live meter →
 * non-zero pool) and the plain P_116 frame (no meter on the wire → {@code 0}, mapped
 * to neutral downstream per AD-J5).
 * <p>
 * Mapper configuration mirrors {@code BettingMiniGameBot.botBehaviorScenario()}
 * ({@code FAIL_ON_UNKNOWN_PROPERTIES=false}), so it exercises the same code path the
 * bot uses at runtime.
 */
@DisplayName("HasJackpotPool - live pool meter (tJpV) off EndGame (Phase J1)")
class HasJackpotPoolTest {

    private String loadFixture(String path) throws Exception {
        try (var in = getClass().getResourceAsStream(path)) {
            assertThat(in).as("fixture " + path).isNotNull();
            return new String(in.readAllBytes());
        }
    }

    private BettingMiniMessage parse(ObjectMapper mapper, String path) throws Exception {
        return mapper.readValue(loadFixture(path), BettingMiniMessage.class);
    }

    /* ---- Four G2 / Tip betting-mini products: jackpotPool() == fixture tJpV ---- */

    @Test
    @DisplayName("BomEndGameMessage: jackpotPool() returns the fixture tJpV")
    void bomPool() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new BomGameMessageTypes().getTypeRegistrations(2000, false));

        BomEndGameMessage end = (BomEndGameMessage) parse(mapper, "/messages/bom/endGame.json");
        assertThat(end).isInstanceOf(HasJackpotPool.class);
        // Fixture carries tJpV=0 (no discharge in progress) — the pool read must equal it.
        assertThat(((HasJackpotPool) end).jackpotPool()).isEqualTo(end.getTJpV());
        assertThat(((HasJackpotPool) end).jackpotPool()).isEqualTo(0L);
    }

    @Test
    @DisplayName("NohuEndGameMessage: jackpotPool() returns the fixture tJpV")
    void nohuPool() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new NohuGameMessageTypes().getTypeRegistrations(4000, false));

        NohuEndGameMessage end = (NohuEndGameMessage) parse(mapper, "/messages/nohu/endGame.json");
        assertThat(end).isInstanceOf(HasJackpotPool.class);
        assertThat(((HasJackpotPool) end).jackpotPool()).isEqualTo(end.getTJpV());
    }

    @Test
    @DisplayName("B52EndGameMessage: jackpotPool() returns the fixture tJpV")
    void b52Pool() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new B52GameMessageTypes().getTypeRegistrations(6000, false));

        B52EndGameMessage end = (B52EndGameMessage) parse(mapper, "/messages/b52/endGame.json");
        assertThat(end).isInstanceOf(HasJackpotPool.class);
        assertThat(((HasJackpotPool) end).jackpotPool()).isEqualTo(end.getTJpV());
    }

    @Test
    @DisplayName("TipEndGameMessage: jackpotPool() returns the fixture tJpV (200000), NOT the per-bot jpV")
    void tipPool() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new TipGameMessageTypes().getTypeRegistrations(8000, false));

        TipEndGameMessage end = (TipEndGameMessage) parse(mapper, "/messages/tip/endGame.json");
        assertThat(end).isInstanceOf(HasJackpotPool.class);
        // The Tip fixture has a non-zero tJpV (200000) AND a non-zero per-bot jpV
        // (1603000) — a clean discrimination that the meter is tJpV, not the payout.
        assertThat(((HasJackpotPool) end).jackpotPool()).isEqualTo(200_000L);
        assertThat(((HasJackpotPool) end).jackpotPool()).isEqualTo(end.getTJpV());
        // The per-bot payout (HasJackpot) is untouched and DISTINCT.
        assertThat(((HasJackpot) end).jackpotFor("bot1")).isEqualTo(1_603_000L);
        assertThat(((HasJackpotPool) end).jackpotPool())
                .as("pool meter (tJpV) is distinct from per-bot payout (jpV)")
                .isNotEqualTo(((HasJackpot) end).jackpotFor("bot1"));
    }

    /* ---- Tai Xiu: shared class, both variants ---- */

    private ObjectMapper taiXiuMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Fixed Tai Xiu CMDs (no per-env offset): plain P_116 endGame=1004, jackpot
        // P_114 endGame=1104 — the shared TaiXiuEndGameMessage handles both.
        mapper.registerSubtypes(
                new NamedType(TaiXiuEndGameMessage.class, "1004"),
                new NamedType(TaiXiuEndGameMessage.class, "1104"));
        return mapper;
    }

    @Test
    @DisplayName("P_114 Tai Xiu (cmd 1104): live tJpV parsed -> jackpotPool() == tJpV (non-zero)")
    void taiXiuJackpotPool() throws Exception {
        TaiXiuEndGameMessage end = (TaiXiuEndGameMessage) taiXiuMapper()
                .readValue(loadFixture("/messages/taixiu/jackpot/endGame_fullRefund.json"),
                        BettingMiniMessage.class);

        assertThat(end.getCmd()).isEqualTo(1104);
        assertThat(end).isInstanceOf(HasJackpotPool.class);
        // The captured P_114 jackpot frame carries a live meter tJpV=227898300.
        assertThat(end.getTJpV()).isEqualTo(227_898_300L);
        assertThat(((HasJackpotPool) end).jackpotPool()).isEqualTo(227_898_300L);
        // Per-bot jackpot payout untouched: iJp=false -> 0, DISTINCT from the pool.
        assertThat(((HasJackpot) end).jackpotFor("any-bot")).isZero();
    }

    @Test
    @DisplayName("Plain P_116 Tai Xiu (cmd 1004, no tJpV on wire): jackpotPool() == 0 (neutral, AD-J5)")
    void taiXiuPlainPoolDefaultsToZero() throws Exception {
        TaiXiuEndGameMessage end = (TaiXiuEndGameMessage) taiXiuMapper()
                .readValue(loadFixture("/messages/taixiu/endGame_fullRefund.json"),
                        BettingMiniMessage.class);

        assertThat(end.getCmd()).isEqualTo(1004);
        assertThat(end).isInstanceOf(HasJackpotPool.class);
        // The plain P_116 taixiuPlugin frame carries tJpV:0 (or omits it) -> 0 -> neutral.
        assertThat(((HasJackpotPool) end).jackpotPool()).isZero();
    }

    @Test
    @DisplayName("Tai Xiu: a frame that OMITS tJpV entirely defaults jackpotPool() to 0")
    void taiXiuMissingTJpVDefaultsToZero() throws Exception {
        // Minimal 1004 frame with NO tJpV key at all — Jackson defaults the primitive
        // long to 0 (the "not observed" reading that maps to the neutral factor).
        String noTJpV = "{\"cmd\":1004,\"d1\":1,\"d2\":2,\"d3\":3,\"gB\":500000,\"gR\":0,\"G\":0,\"GX\":0}";
        TaiXiuEndGameMessage end = (TaiXiuEndGameMessage) taiXiuMapper()
                .readValue(noTJpV, BettingMiniMessage.class);

        assertThat(((HasJackpotPool) end).jackpotPool()).isZero();
    }
}
