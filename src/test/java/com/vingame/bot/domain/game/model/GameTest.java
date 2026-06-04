package com.vingame.bot.domain.game.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Game")
class GameTest {

    @Nested
    @DisplayName("getEffectiveBettingOptions")
    class GetEffectiveBettingOptionsTests {

        @Test
        @DisplayName("Should fall back to [0..numberOfOptions) when bettingOptions is null")
        void shouldFallBackWhenNull() {
            Game game = Game.builder()
                    .numberOfOptions(6)
                    .bettingOptions(null)
                    .build();

            List<Integer> result = game.getEffectiveBettingOptions();

            assertThat(result).containsExactly(0, 1, 2, 3, 4, 5);
        }

        @Test
        @DisplayName("Should fall back to [0..numberOfOptions) when bettingOptions is empty")
        void shouldFallBackWhenEmpty() {
            Game game = Game.builder()
                    .numberOfOptions(3)
                    .bettingOptions(List.of())
                    .build();

            List<Integer> result = game.getEffectiveBettingOptions();

            assertThat(result).containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("Should return configured list when bettingOptions is populated")
        void shouldReturnConfiguredList() {
            List<Integer> configured = List.of(1, 10, 100);
            Game game = Game.builder()
                    .numberOfOptions(6)
                    .bettingOptions(configured)
                    .build();

            List<Integer> result = game.getEffectiveBettingOptions();

            assertThat(result).isEqualTo(configured);
        }

        @Test
        @DisplayName("Should return empty list when numberOfOptions is 0 and bettingOptions is null")
        void shouldReturnEmptyWhenNumberOfOptionsZero() {
            Game game = Game.builder()
                    .numberOfOptions(0)
                    .bettingOptions(null)
                    .build();

            List<Integer> result = game.getEffectiveBettingOptions();

            assertThat(result).isEmpty();
        }
    }
}
