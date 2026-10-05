package com.vingame.bot.domain.bot.core.crash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CrashStakes} — AVIATOR_BOT AD-6: the step ladder a crash bot draws its stake
 * from, and the validator's emptiness check (AD-15).
 */
@DisplayName("CrashStakes — the AD-6 step ladder")
class CrashStakesTest {

    @Test
    @DisplayName("ladder(20000, 200000, 5000) has 37 rungs, 20000 first, 200000 last")
    void legacyWindow() {
        List<Long> ladder = CrashStakes.ladder(20_000, 200_000, 5_000);

        assertThat(ladder).hasSize(37);
        assertThat(ladder.get(0)).isEqualTo(20_000L);
        assertThat(ladder.get(36)).isEqualTo(200_000L);
        assertThat(ladder).isSorted().doesNotHaveDuplicates();
        assertThat(ladder).allMatch(v -> v % 5_000 == 0);
    }

    @Test
    @DisplayName("ladder(0, 0, 0) falls back to the default window and step — the same 37 rungs")
    void allUnsetFallsBackToTheDefaultWindow() {
        assertThat(CrashStakes.ladder(0, 0, 0))
                .containsExactlyElementsOf(CrashStakes.ladder(20_000, 200_000, 5_000));
    }

    @Test
    @DisplayName("maxBet unset takes the whole default window, keeping a set betIncrement")
    void unsetMaxBetKeepsTheStep() {
        List<Long> ladder = CrashStakes.ladder(0, 0, 10_000);

        assertThat(ladder).first().isEqualTo(20_000L);
        assertThat(ladder).last().isEqualTo(200_000L);
        assertThat(ladder).hasSize(19);
    }

    @Test
    @DisplayName("ladder(12000, 14000, 5000) is empty — no multiple of the step in the window")
    void noMultipleInTheWindow() {
        assertThat(CrashStakes.ladder(12_000, 14_000, 5_000)).isEmpty();
    }

    @Test
    @DisplayName("bounds that are not multiples round inwards: [12000, 33000] step 5000 → 15000..30000")
    void boundsRoundInwards() {
        assertThat(CrashStakes.ladder(12_000, 33_000, 5_000))
                .containsExactly(15_000L, 20_000L, 25_000L, 30_000L);
    }

    @Test
    @DisplayName("minBet 0 with maxBet set starts at k = 1, never at a 0 stake")
    void cheapestRungIsTheStep() {
        assertThat(CrashStakes.ladder(0, 20_000, 5_000))
                .containsExactly(5_000L, 10_000L, 15_000L, 20_000L);
    }

    @Test
    @DisplayName("a huge window is computed, not materialised, and still reads correctly")
    void hugeLadderIsLazy() {
        List<Long> ladder = CrashStakes.ladder(1, 1_000_000_000_000L, 1);

        assertThat(ladder).hasSize(Integer.MAX_VALUE);
        assertThat(ladder.get(0)).isEqualTo(1L);
        assertThat(ladder.get(Integer.MAX_VALUE - 1)).isEqualTo((long) Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("the ladder is unmodifiable and bounds-checked")
    void unmodifiableAndBoundsChecked() {
        List<Long> ladder = CrashStakes.ladder(20_000, 30_000, 5_000);

        assertThatThrownBy(() -> ladder.add(35_000L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ladder.get(3)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> ladder.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(new ArrayList<>(ladder)).containsExactly(20_000L, 25_000L, 30_000L);
        assertThat(ladder.subList(1, 3)).containsExactly(25_000L, 30_000L);
    }

    @Test
    @DisplayName("window() reports what was searched: the default window when maxBet is unset")
    void windowReportsTheEffectiveSearch() {
        assertThat(CrashStakes.window(0, 0, 0)).isEqualTo(new CrashStakes.Window(20_000, 200_000, 5_000));
        assertThat(CrashStakes.window(12_000, 14_000, 0)).isEqualTo(new CrashStakes.Window(12_000, 14_000, 5_000));
        assertThat(CrashStakes.window(1, 2, 7)).isEqualTo(new CrashStakes.Window(1, 2, 7));
    }
}
