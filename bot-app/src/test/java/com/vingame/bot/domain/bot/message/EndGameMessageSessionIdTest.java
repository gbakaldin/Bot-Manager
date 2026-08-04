package com.vingame.bot.domain.bot.message;

import com.vingame.bot.domain.bot.message.g2.b52.B52EndGameMessage;
import com.vingame.bot.domain.bot.message.g2.bom.BomEndGameMessage;
import com.vingame.bot.domain.bot.message.g3.tip.TipEndGameMessage;
import com.vingame.bot.domain.bot.message.g4.nohu.NohuEndGameMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 of {@code docs/plans/BETTING_STRATEGIES.md}. {@link EndGameMessage#getSessionId()}
 * was promoted from product-private fields to an abstract accessor on the base
 * class so {@link com.vingame.bot.domain.bot.strategy.BotMemory#completeRound}
 * can correlate any concrete EndGame with the in-flight {@code RoundState}.
 *
 * <p>This test pins that the four concrete subclasses (Tip, Bom, Nohu, B52)
 * actually return the {@code sid} field through the abstract accessor — a
 * subclass that left {@code getSessionId} returning {@code 0L} would silently
 * make every round look mismatched and discard every bet from the rolling
 * history.
 */
@DisplayName("EndGameMessage.getSessionId concrete-subclass overrides")
class EndGameMessageSessionIdTest {

    @Test
    @DisplayName("TipEndGameMessage.getSessionId returns the sid field verbatim")
    void tipReturnsSid() {
        TipEndGameMessage end = new TipEndGameMessage(
                /*cmd*/ 11006,
                /*iJ*/ false,
                /*gid*/ 1,
                /*ps*/ null,
                /*tJpV*/ 0L,
                /*eIn*/ null,
                /*d1*/ 0, /*d2*/ 0, /*d3*/ 0,
                /*iJp*/ false,
                /*sid*/ 822070L,
                /*bs*/ java.util.List.of(),
                /*jpV*/ 0L,
                /*tJpv2*/ 0L,
                /*jPTp*/ 0,
                /*jpCD*/ null,
                /*wm*/ 0L,
                /*sDi*/ null
        );
        // Abstract accessor must return the same value as the private getter
        // (Lombok @Getter on sid) — proves the override is wired, not a stub.
        assertThat(end.getSessionId()).isEqualTo(822070L);
        assertThat(end.getSid()).isEqualTo(end.getSessionId());
    }

    @Test
    @DisplayName("BomEndGameMessage.getSessionId returns the sid field verbatim")
    void bomReturnsSid() {
        BomEndGameMessage end = new BomEndGameMessage(
                /*cmd*/ 2006,
                /*d1*/ 1, /*d2*/ 2, /*d3*/ 3,
                /*sD*/ null,
                /*tJpv2*/ 0L,
                /*tJpV*/ 0L,
                /*iJp*/ false,
                /*jpT*/ 0,
                /*sid*/ 422069L,
                /*lJp*/ null,
                /*bs*/ java.util.List.of()
        );
        assertThat(end.getSessionId()).isEqualTo(422069L);
        assertThat(end.getSid()).isEqualTo(end.getSessionId());
    }

    @Test
    @DisplayName("NohuEndGameMessage.getSessionId returns the sid field verbatim")
    void nohuReturnsSid() {
        NohuEndGameMessage end = new NohuEndGameMessage(
                /*cmd*/ 8006,
                /*d1*/ 4, /*d2*/ 5, /*d3*/ 6,
                /*sD*/ null,
                /*tJpv2*/ 0L,
                /*tJpV*/ 0L,
                /*iJp*/ false,
                /*jpT*/ 0,
                /*sid*/ 313131L,
                /*lJp*/ null,
                /*bs*/ java.util.List.of()
        );
        assertThat(end.getSessionId()).isEqualTo(313131L);
        assertThat(end.getSid()).isEqualTo(end.getSessionId());
    }

    @Test
    @DisplayName("B52EndGameMessage.getSessionId returns the sid field verbatim")
    void b52ReturnsSid() {
        B52EndGameMessage end = new B52EndGameMessage(
                /*cmd*/ 7006,
                /*d1*/ 0, /*d2*/ 0, /*d3*/ 0,
                /*sD*/ null,
                /*tJpv2*/ 0L,
                /*tJpV*/ 0L,
                /*iJp*/ false,
                /*jpT*/ 0,
                /*sid*/ 100200L,
                /*lJp*/ null,
                /*bs*/ java.util.List.of()
        );
        assertThat(end.getSessionId()).isEqualTo(100200L);
        assertThat(end.getSid()).isEqualTo(end.getSessionId());
    }

    @Test
    @DisplayName("EndGameMessage.getSessionId() is an abstract method (no default in base class)")
    void abstractMethodContract() throws NoSuchMethodException {
        // Reflection-pin: if anyone adds a default implementation on the base
        // class, this test will fail and force a review — a concrete default
        // would silently break bet→result correlation by returning 0 for every
        // unhandled subclass.
        var method = EndGameMessage.class.getDeclaredMethod("getSessionId");
        assertThat(java.lang.reflect.Modifier.isAbstract(method.getModifiers()))
                .as("EndGameMessage.getSessionId must remain abstract — see BETTING_STRATEGIES Architecture Decision 4")
                .isTrue();
    }
}
