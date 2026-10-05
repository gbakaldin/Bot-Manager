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
 * {@link CrashConfigValidator} — AVIATOR_BOT AD-15: validates only {@code minBet},
 * {@code maxBet} and {@code betIncrement}, the three fields a crash bot reads (AD-6), plus
 * a non-empty stake ladder, and ignores the rest.
 */
@DisplayName("CrashConfigValidator")
class CrashConfigValidatorTest {

    private final CrashConfigValidator validator = new CrashConfigValidator();

    @Test
    @DisplayName("supportedType is CRASH")
    void supportedType() {
        assertThat(validator.supportedType()).isEqualTo(GameType.CRASH);
    }

    @Test
    @DisplayName("the legacy parity window passes (20k-200k step 5k), and so does an all-unset group")
    void legacyWindowPasses() {
        assertThatCode(() -> validator.validate(
                BotGroup.builder().minBet(20_000).maxBet(200_000).betIncrement(5_000).build()))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(BotGroup.builder().build()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a window holding no multiple of the step is a 400 naming the window and the step")
    void emptyLadderRejected() {
        BotGroup group = BotGroup.builder().minBet(12_000).maxBet(14_000).betIncrement(5_000).build();

        assertThatThrownBy(() -> validator.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("step 5000")
                .hasMessageContaining("[12000, 14000]");
    }

    @Test
    @DisplayName("the empty-ladder 400 names the default step when betIncrement is unset")
    void emptyLadderNamesTheDefaultStep() {
        BotGroup group = BotGroup.builder().minBet(12_000).maxBet(14_000).build();

        assertThatThrownBy(() -> validator.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("step 5000")
                .hasMessageContaining("[12000, 14000]");
    }

    @Test
    @DisplayName("minBet > maxBet is a 400")
    void minAboveMaxRejected() {
        BotGroup group = BotGroup.builder().minBet(200_000).maxBet(20_000).build();

        assertThatThrownBy(() -> validator.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("minBet (200000) must be <= maxBet (20000)")
                .hasMessageNotContaining("no stake fits");
    }

    @Test
    @DisplayName("negative minBet and negative betIncrement are 400s")
    void negativesRejected() {
        assertThatThrownBy(() -> validator.validate(BotGroup.builder().minBet(-1).maxBet(100_000).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("minBet (-1) must be >= 0");
        assertThatThrownBy(() -> validator.validate(
                BotGroup.builder().minBet(20_000).maxBet(100_000).betIncrement(-5).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("betIncrement (-5) must be >= 0");
    }

    @Test
    @DisplayName("betting-grid fields CRASH never reads are ignored, not rejected")
    void unreadFieldsAreIgnored() {
        BotGroup group = BotGroup.builder()
                .minBet(20_000)
                .maxBet(200_000)
                .betIncrement(5_000)
                .minBetsPerRound(-5)
                .maxBetsPerRound(0)
                .maxTotalBetPerRound(0)
                .build();

        assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
    }
}
