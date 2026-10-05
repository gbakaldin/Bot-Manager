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
 * {@link CashoutConfigValidator} — CASHOUT_BOT AD-15: validates only {@code minBet} /
 * {@code maxBet}, the two fields a cash-out bot reads (AD-6), and ignores the rest.
 */
@DisplayName("CashoutConfigValidator")
class CashoutConfigValidatorTest {

    private final CashoutConfigValidator validator = new CashoutConfigValidator();

    @Test
    @DisplayName("supportedType is CASHOUT")
    void supportedType() {
        assertThat(validator.supportedType()).isEqualTo(GameType.CASHOUT);
    }

    @Test
    @DisplayName("the two legacy parity windows pass (Balloon 1k-100k, Soccer 1k-1M)")
    void legacyParityWindowsPass() {
        assertThatCode(() -> validator.validate(BotGroup.builder().minBet(1000).maxBet(100_000).build()))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(BotGroup.builder().minBet(1000).maxBet(1_000_000).build()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("minBet > maxBet is a 400")
    void minAboveMaxRejected() {
        BotGroup group = BotGroup.builder().minBet(100_000).maxBet(1000).build();

        assertThatThrownBy(() -> validator.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("minBet (100000) must be <= maxBet (1000)");
    }

    @Test
    @DisplayName("negative minBet is a 400")
    void negativeMinBetRejected() {
        BotGroup group = BotGroup.builder().minBet(-1).maxBet(1000).build();

        assertThatThrownBy(() -> validator.validate(group))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("minBet (-1) must be >= 0");
    }

    @Test
    @DisplayName("maxBet <= 0 means unset — any non-negative minBet passes (AD-6 default window)")
    void unsetMaxBetIsNotAnOrderingViolation() {
        assertThatCode(() -> validator.validate(BotGroup.builder().minBet(5000).maxBet(0).build()))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(BotGroup.builder().build()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("betting-grid fields CASHOUT never reads are ignored, not rejected")
    void unreadFieldsAreIgnored() {
        BotGroup group = BotGroup.builder()
                .minBet(1000)
                .maxBet(100_000)
                .betIncrement(0)
                .minBetsPerRound(-5)
                .maxBetsPerRound(0)
                .maxTotalBetPerRound(0)
                .build();

        assertThatCode(() -> validator.validate(group)).doesNotThrowAnyException();
    }
}
