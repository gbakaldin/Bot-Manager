package com.vingame.bot.domain.bot.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BettingMiniGameState")
class BettingMiniGameStateTest {

    @Test
    @DisplayName("Should return BET when state == 2")
    void shouldReturnBetForTwo() {
        assertThat(BettingMiniGameState.from(2)).isEqualTo(BettingMiniGameState.BET);
    }

    @ParameterizedTest(name = "from({0}) -> PAYOUT")
    @ValueSource(ints = {0, 1, 3, 99, -1})
    @DisplayName("Should return PAYOUT for any state other than 2")
    void shouldReturnPayoutForOtherValues(int state) {
        assertThat(BettingMiniGameState.from(state)).isEqualTo(BettingMiniGameState.PAYOUT);
    }
}
