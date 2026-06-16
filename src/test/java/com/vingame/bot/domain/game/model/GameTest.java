package com.vingame.bot.domain.game.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Game")
class GameTest {

    @Nested
    @DisplayName("getEffectiveOptionAffinities")
    class GetEffectiveOptionAffinitiesTests {

        @Test
        @DisplayName("Returns optionAffinities as-is when set and non-empty")
        void shouldReturnExplicitMap() {
            Map<Integer, Integer> configured = Map.of(0, 1, 1, 3);
            Game game = Game.builder()
                    .optionAffinities(configured)
                    .build();

            Map<Integer, Integer> result = game.getEffectiveOptionAffinities();

            assertThat(result).isEqualTo(configured);
        }

        @Test
        @DisplayName("Synthesizes {0:1, ..., n-1:1} from legacy numberOfOptions when optionAffinities is null")
        void shouldSynthesizeFromLegacyWhenAffinitiesNull() {
            // Legacy field on the entity is populated by Mongo when reading a
            // pre-Phase-1 document. Builder exposes the field for tests so we
            // can simulate that read.
            Game game = Game.builder()
                    .numberOfOptions(4)
                    .build();

            Map<Integer, Integer> result = game.getEffectiveOptionAffinities();

            assertThat(result).containsOnly(
                    Map.entry(0, 1),
                    Map.entry(1, 1),
                    Map.entry(2, 1),
                    Map.entry(3, 1));
            assertThat(result).hasSize(4);
        }

        @Test
        @DisplayName("Synthesizes from legacy numberOfOptions when optionAffinities is empty map")
        void shouldSynthesizeFromLegacyWhenAffinitiesEmpty() {
            Game game = Game.builder()
                    .optionAffinities(Map.of())
                    .numberOfOptions(3)
                    .build();

            Map<Integer, Integer> result = game.getEffectiveOptionAffinities();

            assertThat(result).hasSize(3).containsKeys(0, 1, 2);
        }

        @Test
        @DisplayName("Explicit optionAffinities wins over legacy numberOfOptions when both set")
        void shouldPreferExplicitAffinitiesOverLegacy() {
            Map<Integer, Integer> configured = Map.of(0, 2, 1, 1);
            Game game = Game.builder()
                    .optionAffinities(configured)
                    .numberOfOptions(99)
                    .build();

            assertThat(game.getEffectiveOptionAffinities()).isEqualTo(configured);
        }

        @Test
        @DisplayName("Throws IllegalStateException when neither optionAffinities nor numberOfOptions is set")
        void shouldThrowWhenNeitherSet() {
            Game game = Game.builder()
                    .id("misconfigured")
                    .name("BrokenGame")
                    .build();

            assertThatThrownBy(game::getEffectiveOptionAffinities)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("misconfigured")
                    .hasMessageContaining("BrokenGame");
        }

        @Test
        @DisplayName("Throws when optionAffinities is null and numberOfOptions is 0")
        void shouldThrowWhenLegacyIsZero() {
            Game game = Game.builder()
                    .numberOfOptions(0)
                    .build();

            assertThatThrownBy(game::getEffectiveOptionAffinities)
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
