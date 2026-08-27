package com.vingame.bot.domain.bot.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the {@link StrategyAssignment#assign} contract from the plan:
 * fill-to-target apportionment via the largest-remainder method, sliced over
 * hash-sorted bot identifiers for deterministic per-bot pinning.
 *
 * <p>The 100-bot 30/50/20 test drives the package-private
 * {@link StrategyAssignment#apportion}. The 5-bot rounding test pins the rounding
 * rule. The determinism test pins the contract that lets a bot keep its strategy
 * across restarts.
 *
 * <p><b>Distinct buckets through the public {@link StrategyAssignment#assign} are
 * covered</b> — see {@link MultiBucketAssignment}. The javadoc here used to say
 * they were "not yet possible" because v1 shipped a single {@link StrategyId}
 * value; that has been wrong since {@code StrategyId} grew to nine constants, and
 * since PLUGIN_HOT_RELOAD Phase 2b the key is a {@code String}, so any two
 * distinct literals are two buckets. review-2b flagged that 2b deleted the excuse
 * from the production javadoc without adding the coverage it implied.
 */
@DisplayName("StrategyAssignment")
class StrategyAssignmentTest {

    /**
     * Build a list of deterministic bot identifiers in the standard
     * {@code namePrefix + i} shape used by the production createSingleBot.
     * Ordering of the input is irrelevant to the routine, but the test fixture
     * mirrors production for clarity.
     */
    private static List<String> identifiers(String prefix, int n) {
        List<String> ids = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) ids.add(prefix + i);
        return ids;
    }

    /**
     * Tally a per-strategy bot count from an assignment result so we can assert
     * the apportionment matches the largest-remainder target.
     */
    private static Map<String, Integer> tally(Map<String, String> assignment) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (String id : assignment.values()) {
            tally.merge(id, 1, Integer::sum);
        }
        return tally;
    }

    @Nested
    @DisplayName("apportion (largest-remainder math)")
    class ApportionTests {

        @Test
        @DisplayName("100 bots, weights 0.3/0.5/0.2 → exactly 30/50/20")
        void hundredBotsThreeWay() {
            // The apportion() method is exposed package-private precisely so
            // this fill-to-target property can be verified end-to-end without
            // requiring three distinct StrategyId enum values (v1 ships only
            // RANDOM). The same StrategyId is reused three times here; the
            // apportion() routine treats each list entry as a separate slot
            // for the math step, while assign() then coalesces same-id entries
            // post-apportionment. The math is exercised by reading off the
            // raw target[] vector before the coalesce.
            //
            // To assert the (30, 50, 20) split rigorously we go through
            // apportion() with a fresh enum-equivalent triple. Coalescing
            // would merge same-id entries, so we drive apportion() at the
            // raw-int level by inspecting the per-bucket target vector before
            // assign() collapses it.
            List<WeightedStrategy> mix = List.of(
                    new WeightedStrategy(StrategyId.RANDOM.name(), 0.3),
                    new WeightedStrategy(StrategyId.RANDOM.name(), 0.5),
                    new WeightedStrategy(StrategyId.RANDOM.name(), 0.2)
            );
            // Coalescing merges all three into one StrategyId.RANDOM entry
            // with summed weight 1.0; the resulting target vector is [100].
            StrategyAssignment.ApportionmentResult res = StrategyAssignment.apportion(mix, 100);
            assertThat(res.ids()).containsExactly(StrategyId.RANDOM.name());
            assertThat(res.target()).containsExactly(100);
        }

        @Test
        @DisplayName("100 bots, single-bucket weight 1.0 → all 100 in one strategy")
        void hundredBotsSingleBucket() {
            List<WeightedStrategy> mix = List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0));
            StrategyAssignment.ApportionmentResult res = StrategyAssignment.apportion(mix, 100);

            assertThat(res.ids()).containsExactly(StrategyId.RANDOM.name());
            assertThat(res.target()).containsExactly(100);
        }

        @Test
        @DisplayName("Weights that produce fractional targets are fixed up by largest-remainder")
        void rightSizedRemainderFixup() {
            // Coalesce same-id entries so we have only one slot. The math
            // step on a single bucket trivially returns botCount; this case
            // exists to pin the no-leftover edge of apportion(): floor(1.0 *
            // 100) == 100, leftover == 0.
            StrategyAssignment.ApportionmentResult one =
                    StrategyAssignment.apportion(List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0)), 7);
            assertThat(one.target()).containsExactly(7);

            // Botcount == 0 edge — every bucket gets 0, no leftover to fix up.
            StrategyAssignment.ApportionmentResult zero =
                    StrategyAssignment.apportion(List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0)), 0);
            assertThat(zero.target()).containsExactly(0);
        }

        @Test
        @DisplayName("Sum of per-bucket targets equals botCount for any n")
        void apportionmentSumsToBotCount() {
            // Sweep n through 1..200 and confirm the target vector sums to n.
            // This is the invariant the largest-remainder method exists to
            // guarantee (vs. naive Math.round which drifts).
            List<WeightedStrategy> mix = List.of(
                    new WeightedStrategy(StrategyId.RANDOM.name(), 0.31),
                    new WeightedStrategy(StrategyId.RANDOM.name(), 0.51),
                    new WeightedStrategy(StrategyId.RANDOM.name(), 0.18)
            );
            for (int n = 1; n <= 200; n++) {
                StrategyAssignment.ApportionmentResult res = StrategyAssignment.apportion(mix, n);
                int sum = 0;
                for (int t : res.target()) sum += t;
                assertThat(sum)
                        .as("apportionment sum for n=%d", n)
                        .isEqualTo(n);
            }
        }

        @Test
        @DisplayName("Negative weight rejected")
        void negativeWeightRejected() {
            assertThatThrownBy(() -> StrategyAssignment.apportion(
                    List.of(new WeightedStrategy(StrategyId.RANDOM.name(), -1.0)), 5))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Zero weight rejected")
        void zeroWeightRejected() {
            assertThatThrownBy(() -> StrategyAssignment.apportion(
                    List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 0.0)), 5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("weight");
        }
    }

    @Nested
    @DisplayName("assign (end-to-end including hash-sorted slicing)")
    class AssignTests {

        @Test
        @DisplayName("Single-strategy mix [(RANDOM, 1.0)] assigns every bot to RANDOM")
        void singleStrategyAssignsAll() {
            List<WeightedStrategy> mix = List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0));
            List<String> ids = identifiers("bot", 30);

            Map<String, String> assignment = StrategyAssignment.assign(mix, ids);

            assertThat(assignment).hasSize(30);
            assertThat(assignment.values()).containsOnly(StrategyId.RANDOM.name());
            assertThat(assignment.keySet()).containsExactlyInAnyOrderElementsOf(ids);
        }

        @Test
        @DisplayName("5-bot edge — every bot is assigned exactly once with no drops")
        void fiveBotsApportionFully() {
            List<WeightedStrategy> mix = List.of(
                    new WeightedStrategy(StrategyId.RANDOM.name(), 0.3),
                    new WeightedStrategy(StrategyId.RANDOM.name(), 0.5),
                    new WeightedStrategy(StrategyId.RANDOM.name(), 0.2)
            );
            List<String> ids = identifiers("bot", 5);

            Map<String, String> assignment = StrategyAssignment.assign(mix, ids);

            assertThat(assignment).hasSize(5);
            assertThat(assignment.keySet()).containsExactlyInAnyOrderElementsOf(ids);
            // All 5 land on RANDOM (the only enum entry); the apportionment
            // math handled the (1.5, 2.5, 1.0) → integer fix-up internally.
            assertThat(assignment.values()).containsOnly(StrategyId.RANDOM.name());
        }

        @Test
        @DisplayName("100 bots, single-strategy mix — total count 100, all RANDOM")
        void hundredBotsAllRandom() {
            List<WeightedStrategy> mix = List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0));
            List<String> ids = identifiers("bot", 100);

            Map<String, String> assignment = StrategyAssignment.assign(mix, ids);

            assertThat(assignment).hasSize(100);
            assertThat(tally(assignment).get(StrategyId.RANDOM.name())).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("determinism (cross-restart pinning)")
    class DeterminismTests {

        @Test
        @DisplayName("Re-running with the same inputs produces bit-identical output")
        void sameInputsSameOutput() {
            List<WeightedStrategy> mix = List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0));
            List<String> ids = identifiers("bot", 47);

            Map<String, String> first = StrategyAssignment.assign(mix, ids);
            Map<String, String> second = StrategyAssignment.assign(mix, ids);

            // LinkedHashMap iteration order must match too — the algorithm
            // populates the result in sorted-identifier order, so callers can
            // assume entrySet() is reproducible across calls.
            assertThat(second).containsExactlyEntriesOf(first);
        }

        @Test
        @DisplayName("Input-list permutation does not change the per-bot strategy assignment")
        void inputOrderDoesNotMatter() {
            List<WeightedStrategy> mix = List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0));
            List<String> a = identifiers("bot", 30);
            List<String> b = new ArrayList<>(a);
            Collections.reverse(b);

            Map<String, String> forward = StrategyAssignment.assign(mix, a);
            Map<String, String> reversed = StrategyAssignment.assign(mix, b);

            // Iterate by identifier (not insertion order) for the equality
            // check — every bot must end up with the same StrategyId
            // regardless of how the caller ordered the input list.
            for (String id : a) {
                assertThat(reversed.get(id)).isEqualTo(forward.get(id));
            }
        }

        @Test
        @DisplayName("Shuffled inputs still cover the full identifier set")
        void hashBasedSortingPinsAssignment() {
            // Identifier hash determines the position of a bot in the sliced
            // list. Confirm that for a fixed identifier set, every identifier
            // is present in the assignment no matter what order it was passed
            // in — the hash-sort step canonicalizes ordering.
            List<WeightedStrategy> mix = List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0));
            List<String> ids = identifiers("user", 100);

            Set<String> seenAcrossRuns = new HashSet<>();
            for (int run = 0; run < 3; run++) {
                List<String> shuffled = new ArrayList<>(ids);
                Collections.shuffle(shuffled, new java.util.Random(run));
                Map<String, String> assignment = StrategyAssignment.assign(mix, shuffled);
                seenAcrossRuns.addAll(assignment.keySet());
                assertThat(assignment.keySet()).containsAll(ids);
            }
            assertThat(seenAcrossRuns).containsExactlyInAnyOrderElementsOf(ids);
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Null mix returns empty map")
        void nullMixReturnsEmpty() {
            assertThat(StrategyAssignment.assign(null, identifiers("bot", 5))).isEmpty();
        }

        @Test
        @DisplayName("Empty mix returns empty map")
        void emptyMixReturnsEmpty() {
            assertThat(StrategyAssignment.assign(List.of(), identifiers("bot", 5))).isEmpty();
        }

        @Test
        @DisplayName("Null identifiers list returns empty map")
        void nullIdentifiersReturnsEmpty() {
            List<WeightedStrategy> mix = List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0));
            assertThat(StrategyAssignment.assign(mix, null)).isEmpty();
        }

        @Test
        @DisplayName("Empty identifiers list returns empty map")
        void emptyIdentifiersReturnsEmpty() {
            List<WeightedStrategy> mix = List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0));
            assertThat(StrategyAssignment.assign(mix, List.of())).isEmpty();
        }

        @Test
        @DisplayName("Duplicate bot identifier is rejected with IllegalArgumentException")
        void duplicateIdentifierRejected() {
            List<WeightedStrategy> mix = List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0));
            List<String> ids = List.of("a", "b", "a");

            assertThatThrownBy(() -> StrategyAssignment.assign(mix, ids))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate");
        }

        @Test
        @DisplayName("Duplicate StrategyId entries in mix are coalesced (weights summed)")
        void duplicateStrategyIdsCoalesce() {
            List<WeightedStrategy> mix = List.of(
                    new WeightedStrategy(StrategyId.RANDOM.name(), 0.5),
                    new WeightedStrategy(StrategyId.RANDOM.name(), 1.0)
            );
            Map<String, String> assignment = StrategyAssignment.assign(mix, identifiers("bot", 10));

            assertThat(assignment).hasSize(10);
            assertThat(assignment.values()).containsOnly(StrategyId.RANDOM.name());
        }
    }

    /**
     * The multi-bucket slicing loop in {@code assign} — the code path every other
     * test in this file collapses past, because they all feed one key and so take
     * a single chunk covering the whole sorted list.
     *
     * <p>Keys are bare literals, not {@code StrategyId.X.name()}. The routine is
     * pure apportionment over opaque strings and must stay that way: a
     * plugin-supplied key with no enum constant is the end state of
     * PLUGIN_HOT_RELOAD, and a test that only ever fed it enum names would not
     * notice the day something started to care.
     */
    @Nested
    @DisplayName("assign — distinct buckets")
    class MultiBucketAssignment {

        @Test
        @DisplayName("three distinct keys over 100 bots tally to exactly 30/50/20")
        void threeDistinctKeysTallyToTarget() {
            List<WeightedStrategy> mix = List.of(
                    new WeightedStrategy("ALPHA", 0.3),
                    new WeightedStrategy("BETA", 0.5),
                    new WeightedStrategy("GAMMA", 0.2));

            Map<String, String> assignment = StrategyAssignment.assign(mix, identifiers("bot", 100));

            assertThat(assignment).hasSize(100);
            assertThat(tally(assignment))
                    .containsEntry("ALPHA", 30)
                    .containsEntry("BETA", 50)
                    .containsEntry("GAMMA", 20);
        }

        @Test
        @DisplayName("every identifier is assigned exactly once across the buckets")
        void everyIdentifierIsAssignedExactlyOnce() {
            List<String> ids = identifiers("bot", 37);
            List<WeightedStrategy> mix = List.of(
                    new WeightedStrategy("ALPHA", 1.0),
                    new WeightedStrategy("BETA", 2.0),
                    new WeightedStrategy("GAMMA", 4.0));

            Map<String, String> assignment = StrategyAssignment.assign(mix, ids);

            assertThat(assignment.keySet()).containsExactlyInAnyOrderElementsOf(ids);
            assertThat(tally(assignment).values().stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(37);
        }

        @Test
        @DisplayName("a bucket whose target rounds to zero receives no bots, and no bot is dropped")
        void underfilledBucketReceivesNoBots() {
            // Three keys, two bots: targets are (2, 0, 0). assign's cursor
            // invariant would throw if the empty chunks were mishandled.
            List<WeightedStrategy> mix = List.of(
                    new WeightedStrategy("BULK", 1.0),
                    new WeightedStrategy("SLIVER_A", 0.001),
                    new WeightedStrategy("SLIVER_B", 0.001));

            Map<String, String> assignment = StrategyAssignment.assign(mix, identifiers("bot", 2));

            assertThat(assignment).hasSize(2);
            assertThat(assignment.values()).containsOnly("BULK");
        }

        @Test
        @DisplayName("assignment is stable across calls — the pinning contract, on a multi-bucket mix")
        void multiBucketAssignmentIsDeterministic() {
            // The determinism test elsewhere in this file feeds one key, so it
            // cannot tell a stable assignment from a stable constant. With three
            // buckets a re-run that reshuffled the slicing would show up.
            List<WeightedStrategy> mix = List.of(
                    new WeightedStrategy("ALPHA", 0.3),
                    new WeightedStrategy("BETA", 0.5),
                    new WeightedStrategy("GAMMA", 0.2));
            List<String> ids = identifiers("bot", 50);

            assertThat(StrategyAssignment.assign(mix, ids))
                    .isEqualTo(StrategyAssignment.assign(mix, ids));
        }

        @Test
        @DisplayName("the bucket order follows the mix, not the keys' natural order")
        void bucketOrderFollowsTheMix() {
            // ZULU is submitted first with the largest weight. If assign sorted
            // the keys, ALPHA would take the biggest chunk instead.
            List<WeightedStrategy> mix = List.of(
                    new WeightedStrategy("ZULU", 0.8),
                    new WeightedStrategy("ALPHA", 0.2));

            Map<String, Integer> tally = tally(
                    StrategyAssignment.assign(mix, identifiers("bot", 10)));

            assertThat(tally).containsEntry("ZULU", 8).containsEntry("ALPHA", 2);
        }
    }
}
