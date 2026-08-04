package com.vingame.bot.domain.bot.strategy;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 of {@code docs/plans/AFFINITY_AWARE_PROPOSAL.md} — the extracted,
 * scope-agnostic {@link WeightedOptionPicker}.
 *
 * <p>Pins:
 * <ul>
 *   <li>Skewed {@code {0:5, 1:1}} → option 0 ≈ 5/6 (distribution assert).</li>
 *   <li>Equal weights → exactly one {@code nextInt(n)} draw AND uniform
 *       distribution (the RNG-consumption invariant AD-3 depends on).</li>
 *   <li>Seeded determinism — same seed produces the same sequence.</li>
 *   <li>Single-key map returns that key.</li>
 *   <li>All-zero / empty → uniform fallback + exactly one WARN.</li>
 *   <li>{@code weightsAreEqual} classification.</li>
 * </ul>
 *
 * <p>Distribution assertions use ±3 percentage points over 10 000 picks,
 * matching the harness in {@code AffinityOptionPickerTest} (SE ≤ 0.5 pp at
 * 10k, so ~6σ headroom).
 */
@DisplayName("WeightedOptionPicker")
class WeightedOptionPickerTest {

    private static final int N_PICKS = 10_000;
    private static final double TOLERANCE = 0.03;

    @Nested
    @DisplayName("Weighted draw")
    class WeightedDraw {

        @Test
        @DisplayName("Skewed {0:5, 1:1} → option 0 picked ~5/6 of the time")
        void skewedWeightDominates() {
            WeightedOptionPicker picker = new WeightedOptionPicker();
            Map<Integer, Integer> weights = new LinkedHashMap<>();
            weights.put(0, 5);
            weights.put(1, 1);

            Map<Integer, Integer> counts = drive(picker, weights, 0xDEADBEEFL);
            double rate0 = counts.get(0) / (double) N_PICKS;
            assertThat(rate0).isBetween(5.0 / 6 - TOLERANCE, 5.0 / 6 + TOLERANCE);
        }

        @Test
        @DisplayName("Negative weights clamp to 0 — never picked")
        void negativeWeightClampedToZero() {
            WeightedOptionPicker picker = new WeightedOptionPicker();
            Map<Integer, Integer> weights = new LinkedHashMap<>();
            weights.put(0, -5);
            weights.put(1, 3);

            Map<Integer, Integer> counts = drive(picker, weights, 7L);
            assertThat(counts.getOrDefault(0, 0)).isZero();
            assertThat(counts.get(1)).isEqualTo(N_PICKS);
        }
    }

    @Nested
    @DisplayName("Equal weights — uniform with a single nextInt(n) draw")
    class EqualWeights {

        @Test
        @DisplayName("{0:1,1:1,2:1,3:1} → exactly one nextInt(n) draw per pick")
        void oneNextIntDrawPerPick() {
            WeightedOptionPicker picker = new WeightedOptionPicker();
            Map<Integer, Integer> weights = new LinkedHashMap<>();
            weights.put(0, 1);
            weights.put(1, 1);
            weights.put(2, 1);
            weights.put(3, 1);

            AtomicInteger draws = new AtomicInteger();
            AtomicInteger lastBound = new AtomicInteger(-1);
            Random counting = new Random(42L) {
                @Override
                public int nextInt(int bound) {
                    draws.incrementAndGet();
                    lastBound.set(bound);
                    return super.nextInt(bound);
                }
            };

            picker.pick(weights, counting);
            assertThat(draws.get()).isEqualTo(1);
            assertThat(lastBound.get()).isEqualTo(4); // nextInt(n), n = Σw = 4
        }

        @Test
        @DisplayName("{0:1,1:1,2:1,3:1} → ~1/4 each (uniform)")
        void uniformDistribution() {
            WeightedOptionPicker picker = new WeightedOptionPicker();
            Map<Integer, Integer> weights = new LinkedHashMap<>();
            weights.put(0, 1);
            weights.put(1, 1);
            weights.put(2, 1);
            weights.put(3, 1);

            Map<Integer, Integer> counts = drive(picker, weights, 42L);
            for (int k = 0; k < 4; k++) {
                double rate = counts.get(k) / (double) N_PICKS;
                assertThat(rate).isBetween(0.25 - TOLERANCE, 0.25 + TOLERANCE);
            }
        }
    }

    @Nested
    @DisplayName("Determinism")
    class Determinism {

        @Test
        @DisplayName("Same seed → same pick sequence")
        void sameSeedSameSequence() {
            WeightedOptionPicker a = new WeightedOptionPicker();
            WeightedOptionPicker b = new WeightedOptionPicker();
            Map<Integer, Integer> weights = new LinkedHashMap<>();
            weights.put(0, 3);
            weights.put(1, 2);
            weights.put(2, 1);

            Random rngA = new Random(0xFEEDFACEL);
            Random rngB = new Random(0xFEEDFACEL);

            for (int t = 0; t < 100; t++) {
                assertThat(a.pick(weights, rngA)).isEqualTo(b.pick(weights, rngB));
            }
        }
    }

    @Nested
    @DisplayName("Single-key + fallback")
    class SingleAndFallback {

        @Test
        @DisplayName("Single-key map → always returns that key")
        void singleKeyReturnsThatKey() {
            WeightedOptionPicker picker = new WeightedOptionPicker();
            Map<Integer, Integer> weights = Map.of(7, 3);
            Random rng = new Random(0xABCDEF01L);

            for (int t = 0; t < 100; t++) {
                assertThat(picker.pick(weights, rng)).isEqualTo(7);
            }
        }

        @Test
        @DisplayName("All-zero weights → uniform fallback over keys")
        void allZeroUniformFallback() {
            WeightedOptionPicker picker = new WeightedOptionPicker();
            Map<Integer, Integer> weights = new LinkedHashMap<>();
            weights.put(0, 0);
            weights.put(1, 0);
            weights.put(2, 0);

            Map<Integer, Integer> counts = drive(picker, weights, 1L);
            for (int k = 0; k < 3; k++) {
                double rate = counts.get(k) / (double) N_PICKS;
                assertThat(rate).isBetween(1.0 / 3 - TOLERANCE, 1.0 / 3 + TOLERANCE);
            }
        }

        @Test
        @DisplayName("All-zero fallback draws exactly one nextInt(keys)")
        void allZeroFallbackOneDraw() {
            WeightedOptionPicker picker = new WeightedOptionPicker();
            Map<Integer, Integer> weights = new LinkedHashMap<>();
            weights.put(0, 0);
            weights.put(1, 0);

            AtomicInteger draws = new AtomicInteger();
            AtomicInteger lastBound = new AtomicInteger(-1);
            Random counting = new Random(5L) {
                @Override
                public int nextInt(int bound) {
                    draws.incrementAndGet();
                    lastBound.set(bound);
                    return super.nextInt(bound);
                }
            };

            picker.pick(weights, counting);
            assertThat(draws.get()).isEqualTo(1);
            assertThat(lastBound.get()).isEqualTo(2); // nextInt(keys)
        }

        @Test
        @DisplayName("All-zero over many picks → exactly one WARN (one-shot per instance)")
        void allZeroWarnsOnce() {
            Logger logger = (Logger) LogManager.getLogger(WeightedOptionPicker.class);
            CapturingAppender appender = new CapturingAppender("WeightedOptionPickerWarnTest");
            appender.start();
            logger.addAppender(appender);
            try {
                WeightedOptionPicker picker = new WeightedOptionPicker();
                Map<Integer, Integer> weights = new LinkedHashMap<>();
                weights.put(0, 0);
                weights.put(1, 0);
                Random rng = new Random(9L);
                for (int t = 0; t < 500; t++) {
                    picker.pick(weights, rng);
                }
                assertThat(appender.eventsAt(Level.WARN)).hasSize(1);
            } finally {
                logger.removeAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("Null weights → pick throws")
        void nullWeightsRejected() {
            WeightedOptionPicker picker = new WeightedOptionPicker();
            assertThatThrownBy(() -> picker.pick(null, new Random(0L)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Empty weights → pick throws")
        void emptyWeightsRejected() {
            WeightedOptionPicker picker = new WeightedOptionPicker();
            assertThatThrownBy(() -> picker.pick(new HashMap<>(), new Random(0L)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("weightsAreEqual")
    class WeightsAreEqual {

        @Test
        @DisplayName("Single-key {i:1} → equal")
        void singleKeyEqual() {
            assertThat(WeightedOptionPicker.weightsAreEqual(Map.of(3, 1))).isTrue();
        }

        @Test
        @DisplayName("{0:3, 1:3} → equal")
        void identicalValuesEqual() {
            Map<Integer, Integer> weights = new LinkedHashMap<>();
            weights.put(0, 3);
            weights.put(1, 3);
            assertThat(WeightedOptionPicker.weightsAreEqual(weights)).isTrue();
        }

        @Test
        @DisplayName("{0:5, 1:1} → not equal")
        void skewedNotEqual() {
            Map<Integer, Integer> weights = new LinkedHashMap<>();
            weights.put(0, 5);
            weights.put(1, 1);
            assertThat(WeightedOptionPicker.weightsAreEqual(weights)).isFalse();
        }

        @Test
        @DisplayName("All-zero → equal (clamps to same value)")
        void allZeroEqual() {
            Map<Integer, Integer> weights = new LinkedHashMap<>();
            weights.put(0, 0);
            weights.put(1, 0);
            assertThat(WeightedOptionPicker.weightsAreEqual(weights)).isTrue();
        }

        @Test
        @DisplayName("Negatives clamp equal — {0:-1, 1:-9} → equal (both clamp to 0)")
        void negativesClampEqual() {
            Map<Integer, Integer> weights = new LinkedHashMap<>();
            weights.put(0, -1);
            weights.put(1, -9);
            assertThat(WeightedOptionPicker.weightsAreEqual(weights)).isTrue();
        }

        @Test
        @DisplayName("Empty and null → equal (nothing to be unequal against)")
        void emptyAndNullEqual() {
            assertThat(WeightedOptionPicker.weightsAreEqual(new HashMap<>())).isTrue();
            assertThat(WeightedOptionPicker.weightsAreEqual(null)).isTrue();
        }
    }

    /**
     * Run {@code N_PICKS} picks and return a per-option count map. The picker
     * holds no RNG of its own — the {@link Random} owned by the test drives the
     * cumulative-weight sampling.
     */
    private static Map<Integer, Integer> drive(WeightedOptionPicker picker,
                                               Map<Integer, Integer> weights,
                                               long seed) {
        Random rng = new Random(seed);
        Map<Integer, Integer> counts = new HashMap<>();
        for (int t = 0; t < N_PICKS; t++) {
            int option = picker.pick(weights, rng);
            counts.merge(option, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Log4j2 ListAppender-style helper capturing events into an in-memory
     * buffer (mirrors the harness in {@code BotMemoryTest}).
     */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender(String name) {
            super(name, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> eventsAt(Level level) {
            List<LogEvent> filtered = new ArrayList<>();
            for (LogEvent e : events) {
                if (e.getLevel().equals(level)) filtered.add(e);
            }
            return filtered;
        }
    }
}
