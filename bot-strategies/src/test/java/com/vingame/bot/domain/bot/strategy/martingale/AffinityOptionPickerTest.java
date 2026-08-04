package com.vingame.bot.domain.bot.strategy.martingale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 of {@code docs/plans/MARTINGALE_STRATEGIES.md}.
 *
 * <p>Pins:
 * <ul>
 *   <li>CAUTIOUS weights each option by {@code max(0, affinity)}; the
 *       distribution proportions across N picks match the expected ratio
 *       within a tolerance.</li>
 *   <li>AGGRESSIVE inverts the weighting around {@code (maxAffinity + 1)} so
 *       the lowest-affinity option dominates — the highest-affinity option
 *       still appears at the {@code +1} minimum rate.</li>
 *   <li>Equal affinities degenerate to uniform under both profiles.</li>
 *   <li>All-zero / all-negative affinities trigger the uniform fallback path
 *       (logs WARN, never crashes the scenario thread).</li>
 *   <li>Single-key affinity map always returns that key (no division by zero
 *       in the cumulative-weight loop).</li>
 *   <li>Determinism with a seeded {@link Random} — the same seed must produce
 *       the same pick sequence so the Phase 2 / 3 / 4 strategy tests have a
 *       stable harness.</li>
 * </ul>
 *
 * <p>Distribution assertions use a tolerance of ±3 percentage points against
 * the analytical expected rate. With 10 000 picks the standard error on a
 * Bernoulli observation is ≤ 0.5 pp, so a 3 pp band gives ~6σ headroom — the
 * test will flake on a deeply broken implementation, not on RNG noise.
 */
@DisplayName("AffinityOptionPicker")
class AffinityOptionPickerTest {

    private static final int N_PICKS = 10_000;
    private static final double TOLERANCE = 0.03;

    @Nested
    @DisplayName("CAUTIOUS — weights by raw affinity")
    class Cautious {

        @Test
        @DisplayName("Affinities {0:5, 1:1} → option 0 picked ~5/6 of the time")
        void heavyWeightDominates() {
            AffinityOptionPicker picker = new AffinityOptionPicker(RiskProfile.CAUTIOUS);
            Map<Integer, Integer> affinities = new LinkedHashMap<>();
            affinities.put(0, 5);
            affinities.put(1, 1);

            Map<Integer, Integer> counts = drive(picker, affinities, 0xDEADBEEFL);
            double rate0 = counts.get(0) / (double) N_PICKS;
            assertThat(rate0).isBetween(5.0 / 6 - TOLERANCE, 5.0 / 6 + TOLERANCE);
        }

        @Test
        @DisplayName("Equal affinities {0:1, 1:1, 2:1} → ~1/3 each")
        void uniformAffinitiesDegenerateToUniform() {
            AffinityOptionPicker picker = new AffinityOptionPicker(RiskProfile.CAUTIOUS);
            Map<Integer, Integer> affinities = new LinkedHashMap<>();
            affinities.put(0, 1);
            affinities.put(1, 1);
            affinities.put(2, 1);

            Map<Integer, Integer> counts = drive(picker, affinities, 42L);
            for (int k = 0; k < 3; k++) {
                double rate = counts.get(k) / (double) N_PICKS;
                assertThat(rate).isBetween(1.0 / 3 - TOLERANCE, 1.0 / 3 + TOLERANCE);
            }
        }

        @Test
        @DisplayName("Zero-affinity option is never picked")
        void zeroAffinityNeverPicked() {
            AffinityOptionPicker picker = new AffinityOptionPicker(RiskProfile.CAUTIOUS);
            Map<Integer, Integer> affinities = new LinkedHashMap<>();
            affinities.put(0, 0);
            affinities.put(1, 3);

            Map<Integer, Integer> counts = drive(picker, affinities, 7L);
            assertThat(counts.getOrDefault(0, 0)).isZero();
            assertThat(counts.get(1)).isEqualTo(N_PICKS);
        }
    }

    @Nested
    @DisplayName("AGGRESSIVE — inverts weighting around (maxAffinity + 1)")
    class Aggressive {

        @Test
        @DisplayName("Affinities {0:5, 1:1} → option 1 picked ~5/6 of the time")
        void lowAffinityDominates() {
            AffinityOptionPicker picker = new AffinityOptionPicker(RiskProfile.AGGRESSIVE);
            Map<Integer, Integer> affinities = new LinkedHashMap<>();
            affinities.put(0, 5);
            affinities.put(1, 1);

            // Expected weights: maxAffinity=5, so reflectAround=6.
            // option 0 weight = 6 - 5 = 1; option 1 weight = 6 - 1 = 5. Ratio 5/6 for option 1.
            Map<Integer, Integer> counts = drive(picker, affinities, 0xCAFEBABEL);
            double rate1 = counts.get(1) / (double) N_PICKS;
            assertThat(rate1).isBetween(5.0 / 6 - TOLERANCE, 5.0 / 6 + TOLERANCE);
        }

        @Test
        @DisplayName("Equal affinities {0:1, 1:1, 2:1} → ~1/3 each (degenerate)")
        void uniformAffinitiesDegenerateToUniform() {
            AffinityOptionPicker picker = new AffinityOptionPicker(RiskProfile.AGGRESSIVE);
            Map<Integer, Integer> affinities = new LinkedHashMap<>();
            affinities.put(0, 1);
            affinities.put(1, 1);
            affinities.put(2, 1);

            // All affinities equal → reflectAround = 2 → every weight = 1. Uniform.
            Map<Integer, Integer> counts = drive(picker, affinities, 99L);
            for (int k = 0; k < 3; k++) {
                double rate = counts.get(k) / (double) N_PICKS;
                assertThat(rate).isBetween(1.0 / 3 - TOLERANCE, 1.0 / 3 + TOLERANCE);
            }
        }

        @Test
        @DisplayName("Highest-affinity option still picked at the +1 minimum (never starves)")
        void maxAffinityNeverStarves() {
            AffinityOptionPicker picker = new AffinityOptionPicker(RiskProfile.AGGRESSIVE);
            Map<Integer, Integer> affinities = new LinkedHashMap<>();
            affinities.put(0, 10);
            affinities.put(1, 1);

            // reflectAround = 11. weights: option 0 = 1, option 1 = 10. Total 11.
            // option 0 rate ≈ 1/11 ≈ 0.091. Assert it's non-zero — the +1 floor matters.
            Map<Integer, Integer> counts = drive(picker, affinities, 13L);
            double rate0 = counts.get(0) / (double) N_PICKS;
            assertThat(rate0).isBetween(1.0 / 11 - TOLERANCE, 1.0 / 11 + TOLERANCE);
            assertThat(counts.get(0)).isPositive();
        }
    }

    @Nested
    @DisplayName("Defensive fallback paths")
    class Fallback {

        @Test
        @DisplayName("All-zero affinities → uniform fallback over input keys")
        void allZeroAffinitiesUniformFallback() {
            AffinityOptionPicker picker = new AffinityOptionPicker(RiskProfile.CAUTIOUS);
            Map<Integer, Integer> affinities = new LinkedHashMap<>();
            affinities.put(0, 0);
            affinities.put(1, 0);
            affinities.put(2, 0);

            Map<Integer, Integer> counts = drive(picker, affinities, 1L);
            // ~1/3 each — fallback is uniform over the three keys.
            for (int k = 0; k < 3; k++) {
                double rate = counts.get(k) / (double) N_PICKS;
                assertThat(rate).isBetween(1.0 / 3 - TOLERANCE, 1.0 / 3 + TOLERANCE);
            }
        }

        @Test
        @DisplayName("All-negative affinities → uniform fallback (cautious profile)")
        void allNegativeAffinitiesUniformFallback() {
            AffinityOptionPicker picker = new AffinityOptionPicker(RiskProfile.CAUTIOUS);
            Map<Integer, Integer> affinities = new LinkedHashMap<>();
            affinities.put(0, -5);
            affinities.put(1, -1);

            Map<Integer, Integer> counts = drive(picker, affinities, 2L);
            double rate0 = counts.get(0) / (double) N_PICKS;
            double rate1 = counts.get(1) / (double) N_PICKS;
            assertThat(rate0).isBetween(0.5 - TOLERANCE, 0.5 + TOLERANCE);
            assertThat(rate1).isBetween(0.5 - TOLERANCE, 0.5 + TOLERANCE);
        }

        @Test
        @DisplayName("Single-option map → always returns that option (no RNG draw on cumulative loop is fine)")
        void singleOptionMapAlwaysReturnsThatOption() {
            AffinityOptionPicker picker = new AffinityOptionPicker(RiskProfile.AGGRESSIVE);
            Map<Integer, Integer> affinities = Map.of(7, 3);
            Random rng = new Random(0xABCDEF01L);

            for (int t = 0; t < 100; t++) {
                assertThat(picker.pick(affinities, rng)).isEqualTo(7);
            }
        }

        @Test
        @DisplayName("Single-option map with zero affinity → still returns that option (uniform fallback over keyset)")
        void singleOptionZeroAffinityFallback() {
            AffinityOptionPicker picker = new AffinityOptionPicker(RiskProfile.CAUTIOUS);
            Map<Integer, Integer> affinities = Map.of(4, 0);
            Random rng = new Random(123L);

            for (int t = 0; t < 50; t++) {
                assertThat(picker.pick(affinities, rng)).isEqualTo(4);
            }
        }

        @Test
        @DisplayName("Null profile → constructor throws")
        void nullProfileRejected() {
            assertThatThrownBy(() -> new AffinityOptionPicker(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Null affinities → pick throws")
        void nullAffinitiesRejected() {
            AffinityOptionPicker picker = new AffinityOptionPicker(RiskProfile.CAUTIOUS);
            assertThatThrownBy(() -> picker.pick(null, new Random(0L)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Empty affinities → pick throws")
        void emptyAffinitiesRejected() {
            AffinityOptionPicker picker = new AffinityOptionPicker(RiskProfile.CAUTIOUS);
            assertThatThrownBy(() -> picker.pick(new HashMap<>(), new Random(0L)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Determinism")
    class Determinism {

        @Test
        @DisplayName("Same seed → same pick sequence")
        void sameSeedSameSequence() {
            AffinityOptionPicker a = new AffinityOptionPicker(RiskProfile.CAUTIOUS);
            AffinityOptionPicker b = new AffinityOptionPicker(RiskProfile.CAUTIOUS);
            Map<Integer, Integer> affinities = new LinkedHashMap<>();
            affinities.put(0, 3);
            affinities.put(1, 2);
            affinities.put(2, 1);

            Random rngA = new Random(0xFEEDFACEL);
            Random rngB = new Random(0xFEEDFACEL);

            for (int t = 0; t < 100; t++) {
                assertThat(a.pick(affinities, rngA)).isEqualTo(b.pick(affinities, rngB));
            }
        }
    }

    /**
     * Run {@code N_PICKS} picks and return a per-option count map. The picker
     * holds no RNG of its own — the {@link Random} owned by the test drives
     * the cumulative-weight sampling.
     */
    private static Map<Integer, Integer> drive(AffinityOptionPicker picker,
                                                Map<Integer, Integer> affinities,
                                                long seed) {
        Random rng = new Random(seed);
        Map<Integer, Integer> counts = new HashMap<>();
        for (int t = 0; t < N_PICKS; t++) {
            int option = picker.pick(affinities, rng);
            counts.merge(option, 1, Integer::sum);
        }
        return counts;
    }
}
