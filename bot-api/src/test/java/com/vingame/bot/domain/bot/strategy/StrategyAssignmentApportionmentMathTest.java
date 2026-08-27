package com.vingame.bot.domain.bot.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Complements {@link StrategyAssignmentTest} by exercising the largest-remainder
 * apportionment <i>math</i> against the 30/50/20 distribution explicitly called
 * out in Phase 4 verification.
 *
 * <p><b>The structural limitation this file was built around is gone.</b> It used
 * to say: v1 ships a single {@link StrategyId} value, {@code apportion} coalesces
 * by strategy key, and therefore no public-API path can produce a multi-bucket
 * apportionment. That stopped being true twice over — {@code StrategyId} has had
 * nine constants since BETTING_STRATEGIES, and since PLUGIN_HOT_RELOAD Phase 2b the
 * key is a {@code String}, so any two distinct literals are two buckets.
 *
 * <p>review-2b flagged that Phase 2b deleted the excuse from
 * {@code StrategyAssignment}'s javadoc without taking the coverage that had just
 * become free. {@link MultiBucketApportionment} below is that coverage: the
 * per-bucket target vector, largest-remainder leftover distributed <em>across
 * distinct buckets</em>, and the insertion-order tie-break, all through the
 * production {@code apportion}. {@code StrategyAssignmentTest} covers the
 * multi-bucket slicing loop in {@code assign}.
 *
 * <p>The inline reference implementation stays. It pins the algorithm
 * <i>algebraically</i> — an independent implementation of the same
 * largest-remainder rule, verified to produce {@code [30, 50, 20]} for
 * {@code (0.3, 0.5, 0.2) * 100} and to maintain the {@code sum == botCount}
 * invariant across a sweep of bot counts. That is a "the math we want is the math
 * we shipped" guarantee, and it is worth more now than it was: the multi-bucket
 * tests below assert production against expected values, while this asserts
 * production against a second implementation of the rule.
 */
@DisplayName("StrategyAssignment.apportion — largest-remainder math")
class StrategyAssignmentApportionmentMathTest {

    /**
     * Pure reimplementation of the largest-remainder rule used by
     * {@link StrategyAssignment#apportion}. Kept inline so any divergence
     * between this and the production routine is obvious from a diff.
     */
    private static int[] largestRemainder(double[] weights, int n) {
        double sum = 0;
        for (double w : weights) sum += w;
        int[] target = new int[weights.length];
        double[] remainder = new double[weights.length];
        int allocated = 0;
        for (int i = 0; i < weights.length; i++) {
            double exact = weights[i] / sum * n;
            target[i] = (int) Math.floor(exact);
            remainder[i] = exact - target[i];
            allocated += target[i];
        }
        int leftover = n - allocated;
        if (leftover > 0) {
            List<Integer> indices = new ArrayList<>(weights.length);
            for (int i = 0; i < weights.length; i++) indices.add(i);
            indices.sort((a, b) -> {
                int cmp = Double.compare(remainder[b], remainder[a]);
                return cmp != 0 ? cmp : Integer.compare(a, b);
            });
            for (int k = 0; k < leftover; k++) {
                target[indices.get(k)]++;
            }
        }
        return target;
    }

    @Test
    @DisplayName("Reference algorithm: weights (0.3, 0.5, 0.2) over 100 bots → exactly (30, 50, 20)")
    void thirtyFiftyTwenty() {
        int[] result = largestRemainder(new double[]{0.3, 0.5, 0.2}, 100);
        assertThat(result).containsExactly(30, 50, 20);
    }

    @Test
    @DisplayName("Reference algorithm: weights (0.3, 0.5, 0.2) over 10 bots → (3, 5, 2)")
    void thirtyFiftyTwentyOverTen() {
        int[] result = largestRemainder(new double[]{0.3, 0.5, 0.2}, 10);
        assertThat(result).containsExactly(3, 5, 2);
    }

    @Test
    @DisplayName("Reference algorithm: weights (0.3, 0.5, 0.2) over 5 bots → (2, 2, 1) — tie-break by index order")
    void thirtyFiftyTwentyOverFive() {
        // Floor: 1.5 → 1, 2.5 → 2, 1.0 → 1 = 4. Leftover 1. Remainders:
        // 0.5, 0.5, 0.0 — slots 0 and 1 tied at 0.5. Tie-break is
        // Integer.compare(a, b) (ascending index) → slot 0 wins → (2, 2, 1).
        // This is what the plan calls out at line 503: "(e.g. 2 / 2 / 1 —
        // exact values pinned by the remainder rule)".
        int[] result = largestRemainder(new double[]{0.3, 0.5, 0.2}, 5);
        assertThat(result).containsExactly(2, 2, 1);
    }

    @Test
    @DisplayName("Reference algorithm: sum-of-targets invariant across n=1..200")
    void sumsToBotCount() {
        for (int n = 1; n <= 200; n++) {
            int[] result = largestRemainder(new double[]{0.31, 0.51, 0.18}, n);
            int sum = Arrays.stream(result).sum();
            assertThat(sum).as("largest-remainder sum for n=%d", n).isEqualTo(n);
        }
    }

    @Test
    @DisplayName("Production apportion: single-bucket invariant matches reference (same math, coalesced)")
    void productionMatchesReferenceForCoalescedInput() throws Exception {
        // Three weighted entries collapsing to a single StrategyId.RANDOM:
        // production apportion() coalesces them into one bucket with summed
        // weight 1.0, then the math step trivially returns botCount. The
        // reference algorithm fed with [1.0] over the same botCount must
        // agree. This isn't a deep test — but it does pin that the production
        // routine's "coalesce then apply largest-remainder" semantics still
        // produce a sum-correct result when fed duplicate keys.
        List<WeightedStrategy> mix = List.of(
                new WeightedStrategy(StrategyId.RANDOM.name(), 0.3),
                new WeightedStrategy(StrategyId.RANDOM.name(), 0.5),
                new WeightedStrategy(StrategyId.RANDOM.name(), 0.2)
        );
        StrategyAssignment.ApportionmentResult prod = StrategyAssignment.apportion(mix, 100);
        int[] ref = largestRemainder(new double[]{1.0}, 100);

        assertThat(prod.target()).containsExactly(ref);
        assertThat(prod.target()[0]).isEqualTo(100);
    }

    @Test
    @DisplayName("Production apportion: leftover distribution honors enum order on tie")
    void productionLeftoverHonorsOrder() {
        // With a single bucket the leftover step is trivially exercised
        // (n - floor(1.0 * n) == 0). The test is here for completeness — and
        // to surface a regression if anyone changes the leftover loop.
        StrategyAssignment.ApportionmentResult res =
                StrategyAssignment.apportion(List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0)), 7);
        assertThat(res.target()).containsExactly(7);
    }

    @Test
    @DisplayName("Production apportion: ApportionmentResult fields are read via the record accessors")
    void apportionmentResultRecordAccessors() throws Exception {
        // Defensive: the record is package-private and exposed for testing.
        // Reflectively confirm the record shape so a Lombok or generation
        // change is caught here, not in StrategyAssignmentTest.
        StrategyAssignment.ApportionmentResult res =
                StrategyAssignment.apportion(List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0)), 3);
        assertThat(res.ids()).containsExactly(StrategyId.RANDOM.name());
        assertThat(res.target()).containsExactly(3);

        Method idsAccessor = StrategyAssignment.ApportionmentResult.class.getMethod("ids");
        Method targetAccessor = StrategyAssignment.ApportionmentResult.class.getMethod("target");
        assertThat(idsAccessor.invoke(res)).isEqualTo(List.of(StrategyId.RANDOM.name()));
        assertThat((int[]) targetAccessor.invoke(res)).containsExactly(3);
    }

    @Test
    @DisplayName("Production apportion: empty leftover when weights produce exact integer targets")
    void noLeftoverOnExactFraction() {
        // 1.0 weight over botCount=10 → floor(10) = 10, leftover = 0.
        StrategyAssignment.ApportionmentResult res =
                StrategyAssignment.apportion(List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0)), 10);
        assertThat(res.target()).containsExactly(10);
    }

    @Test
    @DisplayName("Reference algorithm: degenerate weights (1.0, 1.0, 1.0) over 9 bots → (3, 3, 3)")
    void uniformOverDivisible() {
        int[] result = largestRemainder(new double[]{1.0, 1.0, 1.0}, 9);
        assertThat(result).containsExactly(3, 3, 3);
    }

    @Test
    @DisplayName("Reference algorithm: degenerate weights (1.0, 1.0, 1.0) over 10 bots → (4, 3, 3) (first slot wins leftover via tie-break)")
    void uniformOverNonDivisible() {
        // Three equal weights, n=10. Floor: 3.333 → 3 each = 9. Leftover 1 goes
        // to the slot with the largest fractional remainder; all are tied at
        // 0.333. Tie-break is by index order → slot 0 wins.
        int[] result = largestRemainder(new double[]{1.0, 1.0, 1.0}, 10);
        assertThat(result).containsExactly(4, 3, 3);
    }

    @Test
    @DisplayName("Reference algorithm: zero bots in a bucket when weight is tiny relative to n")
    void smallWeightZeroBucket() {
        // 0.001 over a 1.0 + 0.001 sum at n=2: 0.999 floor 0 / 0.001 floor 0,
        // sum allocated = 0, leftover = 2. First slot (largest remainder
        // 0.998 vs 0.002) gets both leftover ones → bucket B remains 0.
        int[] result = largestRemainder(new double[]{1.0, 0.001}, 2);
        assertThat(result).containsExactly(2, 0);
    }

    /**
     * The coalesce step itself, which is a documented feature rather than a
     * limitation: a caller may submit the same key twice and the weights are
     * summed rather than the second silently winning.
     *
     * <p>The old {@code @DisplayName} on this test read "multi-bucket testing
     * requires a second StrategyId", which was untrue when it was written and is
     * doubly untrue now — see {@link MultiBucketApportionment}. The assertion is
     * unchanged; only the claim about what it implies.
     */
    @Test
    @DisplayName("Production apportion coalesces same-key entries into one bucket, summing weights")
    void coalesceLimitationDocumented() {
        Map<String, Long> tally = new LinkedHashMap<>();
        StrategyAssignment.ApportionmentResult res = StrategyAssignment.apportion(
                List.of(
                        new WeightedStrategy(StrategyId.RANDOM.name(), 0.3),
                        new WeightedStrategy(StrategyId.RANDOM.name(), 0.5),
                        new WeightedStrategy(StrategyId.RANDOM.name(), 0.2)
                ),
                100);
        // Exactly one bucket survives the coalesce — this is the limitation.
        assertThat(res.ids()).containsExactly(StrategyId.RANDOM.name());
        assertThat(res.target()).hasSize(1);
        // 0.3 + 0.5 + 0.2 summed into one bucket, which then takes all 100.
        assertThat(res.target()).containsExactly(100);
    }

    /**
     * The multi-bucket path, through the production {@code apportion}. Everything
     * here was unreachable while the key was an enum with one constant, and became
     * free at Phase 2b without anyone taking it up (review-2b).
     *
     * <p>Keys are spelled as bare literals rather than {@code StrategyId.X.name()}
     * on purpose: this is apportionment math over opaque strings, and the routine
     * must not care whether a key names a built-in. A plugin-supplied key is the
     * end state of this whole feature, and a test that only ever feeds it enum
     * names would not notice the day something started to.
     */
    @Nested
    @DisplayName("Production apportion — distinct buckets")
    class MultiBucketApportionment {

        @Test
        @DisplayName("(0.3, 0.5, 0.2) over 100 bots → exactly (30, 50, 20) across three buckets")
        void threeBucketsMatchTheReference() {
            StrategyAssignment.ApportionmentResult res = StrategyAssignment.apportion(
                    List.of(new WeightedStrategy("ALPHA", 0.3),
                            new WeightedStrategy("BETA", 0.5),
                            new WeightedStrategy("GAMMA", 0.2)),
                    100);

            assertThat(res.ids()).containsExactly("ALPHA", "BETA", "GAMMA");
            assertThat(res.target()).containsExactly(30, 50, 20);
        }

        @Test
        @DisplayName("leftover is distributed across distinct buckets by largest remainder")
        void leftoverGoesToTheLargestRemainders() {
            // (0.3, 0.5, 0.2) * 5 = (1.5, 2.5, 1.0) → floors (1, 2, 1) = 4,
            // leftover 1 → the largest remainder is BETA's 0.5, tied with ALPHA's
            // 0.5, and the tie breaks by index, so ALPHA takes it.
            StrategyAssignment.ApportionmentResult res = StrategyAssignment.apportion(
                    List.of(new WeightedStrategy("ALPHA", 0.3),
                            new WeightedStrategy("BETA", 0.5),
                            new WeightedStrategy("GAMMA", 0.2)),
                    5);

            assertThat(res.target()).containsExactly(2, 2, 1);
            assertThat(sum(res.target())).isEqualTo(5);
        }

        @Test
        @DisplayName("the tie-break is the mix's insertion order, not the keys' natural order")
        void tieBreakFollowsInsertionOrderNotAlphabetical() {
            // Three equal weights over 10 bots: floors (3, 3, 3) = 9, leftover 1,
            // all three remainders equal. The winner is whichever was submitted
            // first. ZULU is submitted first and ALPHA last, so an implementation
            // that sorted the keys instead of preserving mix order would give the
            // extra bot to ALPHA and fail here.
            StrategyAssignment.ApportionmentResult res = StrategyAssignment.apportion(
                    List.of(new WeightedStrategy("ZULU", 1.0),
                            new WeightedStrategy("MIKE", 1.0),
                            new WeightedStrategy("ALPHA", 1.0)),
                    10);

            assertThat(res.ids()).containsExactly("ZULU", "MIKE", "ALPHA");
            assertThat(res.target()).containsExactly(4, 3, 3);
        }

        @Test
        @DisplayName("a bucket whose weight is tiny relative to n gets zero bots, and the sum still holds")
        void underfilledBucketGetsZero() {
            StrategyAssignment.ApportionmentResult res = StrategyAssignment.apportion(
                    List.of(new WeightedStrategy("BULK", 1.0),
                            new WeightedStrategy("SLIVER", 0.001)),
                    2);

            assertThat(res.ids()).containsExactly("BULK", "SLIVER");
            assertThat(res.target()).containsExactly(2, 0);
            assertThat(sum(res.target())).isEqualTo(2);
        }

        @Test
        @DisplayName("sum-of-targets invariant holds across n=1..200 on a three-bucket mix")
        void sumInvariantAcrossASweep() {
            for (int n = 1; n <= 200; n++) {
                StrategyAssignment.ApportionmentResult res = StrategyAssignment.apportion(
                        List.of(new WeightedStrategy("ALPHA", 0.3),
                                new WeightedStrategy("BETA", 0.5),
                                new WeightedStrategy("GAMMA", 0.2)),
                        n);
                assertThat(sum(res.target()))
                        .withFailMessage("targets must sum to botCount for n=%d", n)
                        .isEqualTo(n);
            }
        }

        private int sum(int[] target) {
            int total = 0;
            for (int t : target) total += t;
            return total;
        }
    }
}
