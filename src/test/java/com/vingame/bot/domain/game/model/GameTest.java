package com.vingame.bot.domain.game.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
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

        @Test
        @DisplayName("Synthesizes from legacy bettingOptions = [1, 3, 5] preserving the explicit option ids")
        void shouldSynthesizeFromLegacyBettingOptions() {
            // Pre-Phase-1 docs that customised the option set (e.g. odd-only
            // dice faces) stored bettingOptions = [1, 3, 5]. The fallback must
            // preserve those keys — synthesizing {0:1, 1:1, 2:1} from the
            // list length would silently re-map the game to options [0, 1, 2].
            Game game = Game.builder()
                    .bettingOptions(List.of(1, 3, 5))
                    .build();

            Map<Integer, Integer> result = game.getEffectiveOptionAffinities();

            assertThat(result).containsOnly(
                    Map.entry(1, 1),
                    Map.entry(3, 1),
                    Map.entry(5, 1));
            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("Prefers bettingOptions over numberOfOptions when both legacy fields are set")
        void shouldPreferBettingOptionsOverNumberOfOptions() {
            // Mirrors the pre-Phase-1 getEffectiveBettingOptions() preference:
            // an explicit list wins over the implicit range.
            Game game = Game.builder()
                    .bettingOptions(List.of(2, 4, 6))
                    .numberOfOptions(10)
                    .build();

            Map<Integer, Integer> result = game.getEffectiveOptionAffinities();

            assertThat(result).containsOnly(
                    Map.entry(2, 1),
                    Map.entry(4, 1),
                    Map.entry(6, 1));
        }

        @Test
        @DisplayName("Explicit optionAffinities still wins over legacy bettingOptions")
        void shouldPreferAffinitiesOverBettingOptions() {
            Map<Integer, Integer> configured = Map.of(0, 2, 1, 3);
            Game game = Game.builder()
                    .optionAffinities(configured)
                    .bettingOptions(List.of(7, 8, 9))
                    .build();

            assertThat(game.getEffectiveOptionAffinities()).isEqualTo(configured);
        }

        @Test
        @DisplayName("Falls back to numberOfOptions when bettingOptions is empty")
        void shouldFallBackToNumberOfOptionsWhenBettingOptionsEmpty() {
            Game game = Game.builder()
                    .bettingOptions(List.of())
                    .numberOfOptions(3)
                    .build();

            Map<Integer, Integer> result = game.getEffectiveOptionAffinities();

            assertThat(result).hasSize(3).containsKeys(0, 1, 2);
        }
    }
}
