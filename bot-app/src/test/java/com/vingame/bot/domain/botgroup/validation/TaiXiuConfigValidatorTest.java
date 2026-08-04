package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TaiXiuConfigValidator} (TAI_XIU_BOT plan AD-8). Tai Xiu was
 * promoted from a permissive no-op to the same STRICT-GRID rule set as
 * {@link BettingMiniConfigValidator}; both delegate to the shared
 * {@link BettingGridRules}. These tests mirror the BETTING_MINI boundary cases
 * and additionally assert parity — anything BETTING_MINI rejects/accepts, TAI_XIU
 * rejects/accepts identically (same delegate, same message).
 */
@DisplayName("TaiXiuConfigValidator")
class TaiXiuConfigValidatorTest {

    private final TaiXiuConfigValidator validator = new TaiXiuConfigValidator();
    private final BettingMiniConfigValidator bettingMini = new BettingMiniConfigValidator();

    /**
     * The same baseline valid config the BETTING_MINI tests use: minBet=100,
     * maxBet=500, betIncrement=10 ((500-100)%10==0), bets-per-round 1..5, cap 1000.
     */
    private static BotGroup.BotGroupBuilder validConfig() {
        return BotGroup.builder()
                .minBet(100)
                .maxBet(500)
                .betIncrement(10)
                .minBetsPerRound(1)
                .maxBetsPerRound(5)
                .maxTotalBetPerRound(1000);
    }

    @Test
    @DisplayName("supportedType is TAI_XIU")
    void supportedType() {
        assertThat(validator.supportedType()).isEqualTo(GameType.TAI_XIU);
    }

    @Test
    @DisplayName("valid config passes")
    void validConfigPasses() {
        assertThatCode(() -> validator.validate(validConfig().build()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("minBet > maxBet rejected (grid now enforced, AD-8)")
    void minBetExceedsMaxBetRejected() {
        BotGroup group = validConfig().minBet(500).maxBet(100).build();
        assertThatThrownBy(() -> validator.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("minBet (500) must be <= maxBet (100)");
    }

    @Test
    @DisplayName("betIncrement = 0 rejected")
    void betIncrementZeroRejected() {
        BotGroup group = validConfig().betIncrement(0).build();
        assertThatThrownBy(() -> validator.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("betIncrement (0) must be > 0");
    }

    @Test
    @DisplayName("off-grid (maxBet - minBet) not divisible by betIncrement rejected")
    void offGridRejected() {
        // (105 - 100) % 10 == 5 != 0
        BotGroup group = validConfig().minBet(100).maxBet(105).betIncrement(10).build();
        assertThatThrownBy(() -> validator.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must be exactly divisible by betIncrement (10)");
    }

    @Test
    @DisplayName("maxTotalBetPerRound < maxBet rejected (cross-field cap)")
    void capBelowMaxBetRejected() {
        BotGroup group = validConfig().maxTotalBetPerRound(300).build();
        assertThatThrownBy(() -> validator.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("maxTotalBetPerRound (300) must be >= maxBet (500)");
    }

    @Test
    @DisplayName("zero-min boundary accepted (minBet=0, minBetsPerRound=0)")
    void zeroMinBoundaryAccepted() {
        BotGroup group = BotGroup.builder()
                .minBet(0)
                .maxBet(100)
                .betIncrement(10)
                .minBetsPerRound(0)
                .maxBetsPerRound(5)
                .maxTotalBetPerRound(1000)
                .build();
        assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("multiple simultaneous violations are all reported in one exception")
    void multipleViolationsAggregated() {
        BotGroup group = BotGroup.builder()
                .minBet(500)
                .maxBet(100)
                .betIncrement(0)
                .minBetsPerRound(0)
                .maxBetsPerRound(0)
                .maxTotalBetPerRound(50)
                .build();

        assertThatThrownBy(() -> validator.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid bot-group config:")
                .hasMessageContaining("minBet (500) must be <= maxBet (100)")
                .hasMessageContaining("betIncrement (0) must be > 0")
                .hasMessageContaining("maxBetsPerRound (0) must be >= 1")
                .hasMessageContaining("maxTotalBetPerRound (50) must be >= maxBet (100)");
    }

    @Test
    @DisplayName("the previously-permissive garbage config is now rejected (no longer a no-op)")
    void previouslyPermissiveGarbageNowRejected() {
        // The exact config PermissiveConfigValidatorsTest used to prove TAI_XIU was
        // a no-op. AD-8 promotes the validator, so this must now throw.
        BotGroup garbage = BotGroup.builder()
                .minBet(999)
                .maxBet(1)
                .betIncrement(0)
                .minBetsPerRound(-5)
                .maxBetsPerRound(0)
                .maxTotalBetPerRound(0)
                .build();
        assertThatThrownBy(() -> validator.validate(garbage))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("parity with BETTING_MINI: identical message on a rejected config")
    void parityRejectionMessageMatchesBettingMini() {
        BotGroup group = validConfig().minBet(100).maxBet(111).betIncrement(10).build();

        String taiXiuMsg = catchMessage(() -> validator.validate(group));
        String bettingMsg = catchMessage(() -> bettingMini.validate(group));

        assertThat(taiXiuMsg)
                .as("Tai Xiu delegates to the same shared rule body as BETTING_MINI")
                .isEqualTo(bettingMsg);
    }

    @Test
    @DisplayName("parity with BETTING_MINI: both accept the same valid config")
    void parityValidConfigAcceptedByBoth() {
        BotGroup group = validConfig().build();
        assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
        assertThatCode(() -> bettingMini.validate(group)).doesNotThrowAnyException();
    }

    private static String catchMessage(Runnable r) {
        try {
            r.run();
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }
}
