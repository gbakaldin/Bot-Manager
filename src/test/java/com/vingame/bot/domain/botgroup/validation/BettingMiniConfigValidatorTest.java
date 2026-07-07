package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.game.model.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BettingMiniConfigValidator")
class BettingMiniConfigValidatorTest {

    private final BettingMiniConfigValidator validator = new BettingMiniConfigValidator();

    /**
     * A baseline valid config: minBet=100, maxBet=500, betIncrement=10
     * ((500-100)%10==0), bets-per-round 1..5, cap 1000 (>= maxBet and >=
     * minBet*minBetsPerRound = 100).
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
    @DisplayName("supportedType is BETTING_MINI")
    void supportedType() {
        assertThat(validator.supportedType()).isEqualTo(GameType.BETTING_MINI);
    }

    @Test
    @DisplayName("valid config passes")
    void validConfigPasses() {
        assertThatCode(() -> validator.validate(validConfig().build()))
                .doesNotThrowAnyException();
    }

    @Nested
    @DisplayName("zero-min boundary cases are accepted")
    class ZeroMinBoundary {

        @Test
        @DisplayName("minBet=0 is accepted (grid (100-0)%10==0 holds)")
        void minBetZeroAccepted() {
            BotGroup group = validConfig()
                    .minBet(0)
                    .maxBet(100)
                    .betIncrement(10)
                    .build();
            assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("minBetsPerRound=0 is accepted")
        void minBetsPerRoundZeroAccepted() {
            BotGroup group = validConfig().minBetsPerRound(0).build();
            assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("both mins zero accepted (plan verification step 9)")
        void bothMinsZeroAccepted() {
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
    }

    @Nested
    @DisplayName("strictly-positive fields reject zero")
    class StrictlyPositive {

        @Test
        @DisplayName("maxBet=0 rejected")
        void maxBetZero() {
            // minBet must also be 0 to keep ordering valid and isolate the maxBet rule.
            BotGroup group = validConfig().minBet(0).maxBet(0).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("maxBet (0) must be > 0");
        }

        @Test
        @DisplayName("betIncrement=0 rejected")
        void betIncrementZero() {
            BotGroup group = validConfig().betIncrement(0).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("betIncrement (0) must be > 0");
        }

        @Test
        @DisplayName("maxBetsPerRound=0 rejected")
        void maxBetsPerRoundZero() {
            BotGroup group = validConfig().minBetsPerRound(0).maxBetsPerRound(0).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("maxBetsPerRound (0) must be >= 1");
        }

        @Test
        @DisplayName("maxTotalBetPerRound=0 rejected")
        void maxTotalBetPerRoundZero() {
            BotGroup group = validConfig().maxTotalBetPerRound(0).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("maxTotalBetPerRound (0) must be > 0");
        }
    }

    @Nested
    @DisplayName("non-negativity")
    class NonNegativity {

        @Test
        @DisplayName("negative minBet rejected")
        void negativeMinBet() {
            BotGroup group = validConfig().minBet(-1).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("minBet (-1) must be >= 0");
        }

        @Test
        @DisplayName("negative minBetsPerRound rejected")
        void negativeMinBetsPerRound() {
            BotGroup group = validConfig().minBetsPerRound(-1).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("minBetsPerRound (-1) must be >= 0");
        }

        @Test
        @DisplayName("negative betIncrement rejected (non-negativity + strictly-positive)")
        void negativeBetIncrement() {
            BotGroup group = validConfig().betIncrement(-10).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("betIncrement (-10) must be >= 0")
                    .hasMessageContaining("betIncrement (-10) must be > 0");
        }
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        @DisplayName("minBet > maxBet rejected")
        void minBetExceedsMaxBet() {
            BotGroup group = validConfig().minBet(500).maxBet(100).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("minBet (500) must be <= maxBet (100)");
        }

        @Test
        @DisplayName("minBetsPerRound > maxBetsPerRound rejected")
        void minBetsExceedsMaxBets() {
            BotGroup group = validConfig().minBetsPerRound(6).maxBetsPerRound(5).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("minBetsPerRound (6) must be <= maxBetsPerRound (5)");
        }
    }

    @Nested
    @DisplayName("grid / divisibility")
    class Grid {

        @Test
        @DisplayName("(maxBet - minBet) not divisible by betIncrement rejected")
        void notDivisible() {
            // (105 - 100) % 10 == 5 != 0
            BotGroup group = validConfig().minBet(100).maxBet(105).betIncrement(10).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("must be exactly divisible by betIncrement (10)");
        }

        @Test
        @DisplayName("exact divisibility passes")
        void divisiblePasses() {
            // (130 - 100) % 10 == 0
            BotGroup group = validConfig().minBet(100).maxBet(130).betIncrement(10).build();
            assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("minBet == maxBet with any positive increment passes the grid (range 0)")
        void equalBoundsPassGrid() {
            BotGroup group = validConfig().minBet(200).maxBet(200).betIncrement(7).build();
            assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("grid check is skipped (not double-reported) when betIncrement is 0")
        void gridSkippedWhenIncrementZero() {
            BotGroup group = validConfig().betIncrement(0).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("betIncrement (0) must be > 0")
                    // no divisibility message: betIncrement==0 guards out the grid check
                    .matches(t -> !t.getMessage().contains("exactly divisible"),
                            "should not report a divisibility violation when betIncrement is 0");
        }
    }

    @Nested
    @DisplayName("cross-field caps")
    class CrossField {

        @Test
        @DisplayName("maxTotalBetPerRound < maxBet rejected")
        void capBelowMaxBet() {
            // maxTotalBetPerRound (300) < maxBet (500), but still >= minBet*minBetsPerRound (100)
            BotGroup group = validConfig().maxTotalBetPerRound(300).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("maxTotalBetPerRound (300) must be >= maxBet (500)");
        }

        @Test
        @DisplayName("maxTotalBetPerRound < minBet * minBetsPerRound rejected")
        void capBelowMinTotal() {
            // minBet=100, minBetsPerRound=8 => minTotal=800; cap=500 >= maxBet=500 but < 800.
            BotGroup group = validConfig()
                    .minBet(100)
                    .maxBet(500)
                    .betIncrement(10)
                    .minBetsPerRound(8)
                    .maxBetsPerRound(10)
                    .maxTotalBetPerRound(500)
                    .build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining(
                            "maxTotalBetPerRound (500) must be >= minBet * minBetsPerRound (100 * 8 = 800)");
        }

        @Test
        @DisplayName("minTotal uses long math (no int overflow on minBet * minBetsPerRound)")
        void minTotalUsesLongMath() {
            // minBet near Integer.MAX, minBetsPerRound=2 would overflow int but not long.
            long bigMin = 3_000_000_000L; // > Integer.MAX_VALUE
            BotGroup group = BotGroup.builder()
                    .minBet(bigMin)
                    .maxBet(bigMin)
                    .betIncrement(1)
                    .minBetsPerRound(2)
                    .maxBetsPerRound(2)
                    .maxTotalBetPerRound(bigMin) // < bigMin*2, so cross-field bites
                    .build();
            // Expected minTotal = 6_000_000_000 (would be negative/garbage under int math).
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("6000000000");
        }
    }

    @Nested
    @DisplayName("grid / cross-field boundary cases (QA)")
    class BoundaryCases {

        @Test
        @DisplayName("range exceeds betIncrement by exactly 1 rejected (off-by-one grid)")
        void offByOneGridRejected() {
            // (111 - 100) % 10 == 1 != 0 — the tightest off-grid case.
            BotGroup group = validConfig().minBet(100).maxBet(111).betIncrement(10).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("(maxBet (111) - minBet (100)) must be exactly divisible by betIncrement (10)");
        }

        @Test
        @DisplayName("betIncrement larger than the (positive) range rejected as off-grid")
        void incrementLargerThanRange() {
            // range = 5, increment = 10 → 5 % 10 == 5 != 0. A single step cannot
            // even reach maxBet, so the grid rule correctly bites.
            BotGroup group = validConfig().minBet(100).maxBet(105).betIncrement(10).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("must be exactly divisible by betIncrement (10)");
        }

        @Test
        @DisplayName("range exactly equal to betIncrement passes (one step)")
        void rangeEqualsIncrementPasses() {
            // (110 - 100) % 10 == 0 — exactly one increment of headroom.
            BotGroup group = validConfig().minBet(100).maxBet(110).betIncrement(10).build();
            assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("betIncrement == 1 makes every integer range valid (grid degenerate)")
        void incrementOneAlwaysOnGrid() {
            BotGroup group = validConfig().minBet(100).maxBet(137).betIncrement(1).build();
            assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("maxTotalBetPerRound exactly == maxBet passes the cap (>= boundary)")
        void capEqualsMaxBetPasses() {
            // cap == maxBet (500) and >= minBet*minBetsPerRound (100*1=100): both clauses hold at the edge.
            BotGroup group = validConfig().maxTotalBetPerRound(500).build();
            assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("maxTotalBetPerRound one below maxBet rejected (just under the cap edge)")
        void capOneBelowMaxBetRejected() {
            BotGroup group = validConfig().maxTotalBetPerRound(499).build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("maxTotalBetPerRound (499) must be >= maxBet (500)");
        }

        @Test
        @DisplayName("maxTotalBetPerRound exactly == minBet * minBetsPerRound passes (both non-zero, >= edge)")
        void capEqualsMinTotalPasses() {
            // minBet=100, minBetsPerRound=5 → minTotal=500; cap=500 == minTotal and == maxBet.
            BotGroup group = validConfig()
                    .minBet(100)
                    .maxBet(500)
                    .betIncrement(10)
                    .minBetsPerRound(5)
                    .maxBetsPerRound(5)
                    .maxTotalBetPerRound(500)
                    .build();
            assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("maxTotalBetPerRound one below minBet * minBetsPerRound rejected (both non-zero)")
        void capOneBelowMinTotalRejected() {
            // minBet=100, minBetsPerRound=5 → minTotal=500; cap=499 >= maxBet? no — keep maxBet low to
            // isolate the minTotal clause: maxBet=400, cap=499 >= 400 holds, but 499 < 500 minTotal.
            BotGroup group = validConfig()
                    .minBet(100)
                    .maxBet(400)
                    .betIncrement(10)
                    .minBetsPerRound(5)
                    .maxBetsPerRound(5)
                    .maxTotalBetPerRound(499)
                    .build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("maxTotalBetPerRound (499) must be >= minBet * minBetsPerRound (100 * 5 = 500)")
                    // maxBet clause must NOT fire here (cap 499 >= maxBet 400), isolating the cross-field clause.
                    .matches(t -> !t.getMessage().contains("must be >= maxBet"),
                            "the maxBet cap clause should not fire when cap >= maxBet");
        }

        @Test
        @DisplayName("minBet * minBetsPerRound at exactly Long.MAX boundary does not overflow into a false pass")
        void minTotalNoSilentOverflow() {
            // minBet * minBetsPerRound would overflow long and wrap negative under naive math; assert
            // the validator still reports the cross-field violation rather than silently passing.
            long bigMin = 4_000_000_000L; // > Integer.MAX
            BotGroup group = BotGroup.builder()
                    .minBet(bigMin)
                    .maxBet(bigMin)
                    .betIncrement(1)
                    .minBetsPerRound(3)        // 4e9 * 3 = 1.2e10, fits in long, exceeds the cap below
                    .maxBetsPerRound(3)
                    .maxTotalBetPerRound(bigMin)
                    .build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("12000000000");
        }
    }

    @Nested
    @DisplayName("coordination cap (BET_COORDINATION AD-1)")
    class CoordinationCap {

        @Test
        @DisplayName("coordination disabled: maxAggregateStakePerRound is unconstrained (even 0)")
        void disabledImposesNoConstraint() {
            // coordinationEnabled defaults to false; cap 0 (< minBet 100) must not bite.
            BotGroup group = validConfig()
                    .coordinationEnabled(false)
                    .maxAggregateStakePerRound(0)
                    .build();
            assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("enabled + cap below minBet rejected (400)")
        void enabledCapBelowMinBetRejected() {
            // minBet=100, cap=50 < 100 → violation.
            BotGroup group = validConfig()
                    .coordinationEnabled(true)
                    .maxAggregateStakePerRound(50)
                    .build();
            assertThatThrownBy(() -> validator.validate(group))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining(
                            "maxAggregateStakePerRound (50) must be >= minBet (100) when coordinationEnabled is true");
        }

        @Test
        @DisplayName("enabled + cap == minBet passes (>= boundary)")
        void enabledCapEqualsMinBetPasses() {
            // minBet=100, cap=100: the >= edge holds.
            BotGroup group = validConfig()
                    .coordinationEnabled(true)
                    .maxAggregateStakePerRound(100)
                    .build();
            assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("enabled + cap above minBet passes")
        void enabledCapAboveMinBetPasses() {
            BotGroup group = validConfig()
                    .coordinationEnabled(true)
                    .maxAggregateStakePerRound(500000)
                    .build();
            assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("coordination cap is decoupled from the per-bot maxTotalBetPerRound")
        void decoupledFromPerBotCap() {
            // maxTotalBetPerRound stays a valid 1000; the aggregate cap (150) is well
            // below it but still >= minBet (100), so no violation — the two caps are
            // independent.
            BotGroup group = validConfig()
                    .maxTotalBetPerRound(1000)
                    .coordinationEnabled(true)
                    .maxAggregateStakePerRound(150)
                    .build();
            assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("multiple simultaneous violations are all reported in one exception")
    void multipleViolationsAggregated() {
        // minBet > maxBet, betIncrement = 0, maxBetsPerRound = 0, cap too low.
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
    @DisplayName("violations are joined with '; ' under the aggregated prefix")
    void aggregatedMessageFormat() {
        BotGroup group = validConfig().minBet(500).maxBet(100).betIncrement(0).build();
        assertThatThrownBy(() -> validator.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("Invalid bot-group config: ")
                .matches(t -> t.getMessage().contains("; "),
                        "multiple violations are joined with '; '");
    }
}
