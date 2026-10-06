package com.vingame.bot.domain.bot.core.crash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AVIATOR_BOT QA: {@link CrashStakes} ladder bounds beyond {@code CrashStakesTest} — the
 * step-versus-window edges, values near {@link Long#MAX_VALUE}, the unset sentinels, and a
 * property sweep that every ladder is exactly the set {@code {k·step : k ≥ 1, min ≤ k·step ≤ max}}.
 */
@DisplayName("CrashStakes bounds (QA)")
class CrashStakesBoundsTest {

    @Test
    @DisplayName("step equal to maxBet gives one rung; step above maxBet gives none")
    void stepVersusMax() {
        assertThat(CrashStakes.ladder(0, 50_000, 50_000)).containsExactly(50_000L);
        assertThat(CrashStakes.ladder(0, 49_999, 50_000)).isEmpty();
        assertThat(CrashStakes.ladder(50_000, 50_000, 5_000)).containsExactly(50_000L);
        assertThat(CrashStakes.ladder(50_001, 50_001, 5_000)).isEmpty();
    }

    @Test
    @DisplayName("negative maxBet is 'unset' like 0 — the legacy window, and minBet is then ignored (AD-6 as written)")
    void unsetSentinels() {
        List<Long> legacy = CrashStakes.ladder(20_000, 200_000, 5_000);
        assertThat(CrashStakes.ladder(0, -1, 0)).containsExactlyElementsOf(legacy);
        // An operator's minBet above the default window is NOT honoured while maxBet is unset.
        assertThat(CrashStakes.ladder(500_000, 0, 0)).containsExactlyElementsOf(legacy);
        assertThat(CrashStakes.ladder(0, 0, -5)).containsExactlyElementsOf(legacy);
    }

    @Test
    @DisplayName("a negative minBet (rejected by the validator, but reachable from a stale document) starts at the step, never <= 0")
    void negativeMin() {
        assertThat(CrashStakes.ladder(-100_000, 20_000, 5_000)).containsExactly(5_000L, 10_000L, 15_000L, 20_000L);
    }

    @Test
    @DisplayName("values near Long.MAX_VALUE neither overflow nor go negative")
    void nearLongMax() {
        long max = Long.MAX_VALUE;
        assertThat(CrashStakes.ladder(max - 1, max, 1)).containsExactly(max - 1, max);
        assertThat(CrashStakes.ladder(0, max, max)).containsExactly(max);
        assertThat(CrashStakes.ladder(max, max, 2)).isEmpty(); // MAX is odd

        List<Long> huge = CrashStakes.ladder(1, max, 3);
        assertThat(huge).hasSize(Integer.MAX_VALUE);
        long last = huge.get(huge.size() - 1);
        assertThat(last).isEqualTo(3L * Integer.MAX_VALUE).isPositive();
        assertThat(CrashRoundStateMachine.affordableCount(huge, max)).isEqualTo(Integer.MAX_VALUE);
        assertThat(CrashRoundStateMachine.affordableCount(huge, -1)).isZero();
    }

    @Test
    @DisplayName("property: 5000 random (min, max, step) — the ladder is exactly {k·step : k>=1, min<=k·step<=max}")
    void exactSetProperty() {
        Random r = new Random(2026_10_06L);
        for (int i = 0; i < 5_000; i++) {
            long min = r.nextInt(5) == 0 ? 0 : r.nextInt(300_000);
            long max = r.nextInt(10) == 0 ? 0 : r.nextInt(400_000);
            long step = r.nextInt(10) == 0 ? 0 : 1 + r.nextInt(60_000);
            List<Long> ladder = CrashStakes.ladder(min, max, step);
            CrashStakes.Window w = CrashStakes.window(min, max, step);

            // Brute force over k.
            long expectedFirst = -1;
            long expectedCount = 0;
            for (long k = 1; k * w.step() <= w.max(); k++) {
                long v = k * w.step();
                if (v >= w.min()) {
                    if (expectedFirst < 0) {
                        expectedFirst = v;
                    }
                    expectedCount++;
                }
            }
            String ctx = "min=" + min + " max=" + max + " step=" + step;
            assertThat(ladder).as(ctx).hasSize((int) expectedCount);
            if (expectedCount > 0) {
                assertThat(ladder.get(0)).as(ctx).isEqualTo(expectedFirst);
                assertThat(ladder.get(ladder.size() - 1)).as(ctx)
                        .isLessThanOrEqualTo(w.max())
                        .isGreaterThan(w.max() - w.step());
                assertThat(ladder.get(0) - w.step()).as(ctx + " (first is the lowest)")
                        .isLessThan(Math.max(w.min(), 1));
            }
        }
    }
}
