package com.vingame.bot.domain.bot.coordination;

import com.vingame.bot.domain.bot.coordination.ReservationOutcome.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 of {@code docs/plans/CROWD_AWARE_COORDINATION.md} (WP#6) — the
 * scope-agnostic crowd core.
 *
 * <p>Pins the AD-C2/AD-C4/AD-C5/AD-C6/AD-C9 crowd-tier math on top of the shipped
 * {@link BetCoordinator}:
 * <ul>
 *   <li><b>Reduction identity (AD-C2):</b> crowd-off / all-zero crowd ⇒
 *       {@code B_crowd(o) == B(o)} bit-for-bit, and a full reserve sequence is
 *       byte-identical to a crowd-disabled coordinator (the composition pin);</li>
 *   <li><b>Crowd skew (AD-C2):</b> a crowd pile on a low-affinity option shrinks
 *       that option's fleet budget and grows the under-crowded one, with
 *       {@code Σ B_crowd ≤ C} and each {@code ∈ [0, C]};</li>
 *   <li><b>committed preserved (AD-C9):</b> committed totals survive a mid-round
 *       {@code observeCrowd} budget swap and the cap still binds;</li>
 *   <li><b>Double-count (AD-C4):</b> {@code X(o) = max(0, v(o) − committed(o))};</li>
 *   <li><b>Value-only (AD-C5):</b> garbage {@code bc} counts never move the
 *       budget;</li>
 *   <li><b>Gate (AD-C6):</b> a non-crowd-aware coordinator never recomputes;</li>
 *   <li><b>Stale sid dropped + concurrency (AD-C3/AD-C9).</b></li>
 * </ul>
 */
@DisplayName("BetCoordinator — crowd tier")
class BetCoordinatorCrowdTest {

    private static Map<Integer, Integer> weights(int... w) {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < w.length; i++) {
            m.put(i, w[i]);
        }
        return m;
    }

    private static CrowdOption crowd(int optionId, long value) {
        return new CrowdOption(optionId, value, 0L, 0);
    }

    private static CrowdOption crowd(int optionId, long value, int count) {
        return new CrowdOption(optionId, value, 0L, count);
    }

    /** The internal-tier budget the shipped 4-arg coordinator would compute. */
    private static Map<Integer, Long> internalBudget(Map<Integer, Integer> w, long cap) {
        BetCoordinator ref = new BetCoordinator(w, cap, 10, 10);
        ref.onRound(1L);
        return ref.currentBudget();
    }

    @Nested
    @DisplayName("reduction identity (crowd-off == internal tier)")
    class ReductionIdentity {

        @Test
        @DisplayName("no crowd observed ⇒ B_crowd(o) == B(o) bit-for-bit")
        void noCrowdEqualsInternal() {
            Map<Integer, Integer> w = weights(1, 1, 2);
            long cap = 1000;
            BetCoordinator crowdC = new BetCoordinator(w, cap, 10, 10, true);
            crowdC.onRound(42L);

            // Never observed any crowd → must equal the internal split exactly.
            assertThat(crowdC.currentBudget()).isEqualTo(internalBudget(w, cap));
            // and equal to the documented 250/250/500 split (per option key).
            assertThat(crowdC.currentBudget())
                    .containsEntry(0, 250L).containsEntry(1, 250L).containsEntry(2, 500L);
        }

        @Test
        @DisplayName("all-zero crowd observed ⇒ B_crowd(o) == B(o) bit-for-bit")
        void allZeroCrowdEqualsInternal() {
            Map<Integer, Integer> w = weights(3, 5, 7, 11); // awkward weights, floor slack
            long cap = 1_000_000;
            BetCoordinator crowdC = new BetCoordinator(w, cap, 100, 100, true);
            crowdC.onRound(9L);

            crowdC.observeCrowd(9L, List.of(
                    crowd(0, 0), crowd(1, 0), crowd(2, 0), crowd(3, 0)));

            assertThat(crowdC.currentBudget()).isEqualTo(internalBudget(w, cap));
        }

        @Test
        @DisplayName("full reserve sequence is byte-identical to a crowd-disabled coordinator")
        void reserveSequenceMatchesDisabled() {
            Map<Integer, Integer> w = weights(1, 1, 2, 4);
            long cap = 5000;
            long minBet = 50;
            long inc = 50;

            BetCoordinator disabled = new BetCoordinator(w, cap, minBet, inc);       // 4-arg, off
            BetCoordinator enabledNoObs = new BetCoordinator(w, cap, minBet, inc, true);

            long sid = 314L;
            disabled.onRound(sid);
            enabledNoObs.onRound(sid);

            // Identical proposal stream; crowd-enabled but NEVER observed → must
            // behave byte-for-byte like the disabled coordinator.
            long[][] props = {
                    {0, 200}, {1, 130}, {2, 400}, {3, 1000}, {2, 700},
                    {0, 90}, {3, 5000}, {1, 250}, {3, 300}, {2, 60}
            };
            for (long[] p : props) {
                ReservationOutcome a = disabled.reserve(sid, (int) p[0], p[1]);
                ReservationOutcome b = enabledNoObs.reserve(sid, (int) p[0], p[1]);
                assertThat(b.decision()).isEqualTo(a.decision());
                assertThat(b.amount()).isEqualTo(a.amount());
            }
            assertThat(enabledNoObs.currentBudget()).isEqualTo(disabled.currentBudget());
            assertThat(enabledNoObs.getCurrentAggregateStake())
                    .isEqualTo(disabled.getCurrentAggregateStake());
        }
    }

    @Nested
    @DisplayName("gate (AD-C6)")
    class Gate {

        @Test
        @DisplayName("a non-crowd-aware coordinator never recomputes from crowd")
        void disabledIgnoresObserveCrowd() {
            Map<Integer, Integer> w = weights(1, 1, 2);
            long cap = 1000;
            BetCoordinator c = new BetCoordinator(w, cap, 10, 10); // crowdAware=false
            assertThat(c.isCrowdAware()).isFalse();
            c.onRound(5L);

            // Pile a huge crowd on option 0 — a crowd-aware coordinator would
            // slash its budget. The disabled one must ignore it entirely.
            c.observeCrowd(5L, List.of(crowd(0, 100_000), crowd(1, 0), crowd(2, 0)));

            assertThat(c.currentBudget()).isEqualTo(internalBudget(w, cap));
            assertThat(c.currentBudget())
                    .containsEntry(0, 250L).containsEntry(1, 250L).containsEntry(2, 500L);
        }
    }

    @Nested
    @DisplayName("crowd skew (AD-C2)")
    class CrowdSkew {

        @Test
        @DisplayName("crowd on a low-affinity option shrinks its budget; the under-crowded high-affinity option's grows")
        void skewMovesTowardTarget() {
            // Affinity target P = 1:1:2 over cap 1000 → internal 250/250/500.
            Map<Integer, Integer> w = weights(1, 1, 2);
            long cap = 1000;
            BetCoordinator c = new BetCoordinator(w, cap, 10, 10, true);
            c.onRound(7L);

            Map<Integer, Long> before = c.currentBudget(); // 250/250/500

            // Crowd piles 600 on the LOW-affinity option 0, nothing elsewhere.
            // X_total = 600, combined pool = 1600.
            //   opt0: floor(1*1600/4) - 600 = 400 - 600 = -200 → clamp 0
            //   opt1: floor(1*1600/4) - 0   = 400
            //   opt2: floor(2*1600/4) - 0   = 800 → clamp to cap 1000? no, 800 ≤ 1000
            c.observeCrowd(7L, List.of(crowd(0, 600), crowd(1, 0), crowd(2, 0)));
            Map<Integer, Long> after = c.currentBudget();

            // The over-crowded low-affinity option's fleet budget shrinks (to 0 here).
            assertThat(after.get(0)).isLessThan(before.get(0));
            assertThat(after.get(0)).isEqualTo(0L);
            // The under-crowded high-affinity option's budget grows.
            assertThat(after.get(2)).isGreaterThan(before.get(2));
            assertThat(after.get(1)).isEqualTo(400L);
            assertThat(after.get(2)).isEqualTo(800L);

            // Invariants: Σ B_crowd ≤ C is NOT guaranteed by the formula alone
            // (it can exceed C across options — the aggregate cap in reserve is the
            // hard ceiling), but each option ∈ [0, C].
            assertThat(after.values()).allSatisfy(v ->
                    assertThat(v).isBetween(0L, cap));
        }

        @Test
        @DisplayName("a crowd larger than the target on an option drives that option's budget to 0 (clamp)")
        void overfillClampsToZero() {
            Map<Integer, Integer> w = weights(1, 1);
            long cap = 1000;
            BetCoordinator c = new BetCoordinator(w, cap, 10, 10, true);
            c.onRound(8L);

            // Massive crowd on option 0. X_total = 10000, pool = 11000.
            //   opt0: floor(1*11000/2) - 10000 = 5500 - 10000 < 0 → 0
            //   opt1: 5500 - 0 = 5500 → clamp to cap 1000
            c.observeCrowd(8L, List.of(crowd(0, 10_000), crowd(1, 0)));
            Map<Integer, Long> after = c.currentBudget();

            assertThat(after.get(0)).isEqualTo(0L);
            assertThat(after.get(1)).isEqualTo(cap); // clamped up to the ceiling C
            assertThat(after.values()).allSatisfy(v -> assertThat(v).isBetween(0L, cap));
        }

        @Test
        @DisplayName("each B_crowd ∈ [0, C] under a lopsided crowd")
        void perOptionBounds() {
            Map<Integer, Integer> w = weights(2, 3, 5);
            long cap = 500_000;
            BetCoordinator c = new BetCoordinator(w, cap, 100, 100, true);
            c.onRound(4L);
            c.observeCrowd(4L, List.of(
                    crowd(0, 900_000), crowd(1, 12_345), crowd(2, 0)));
            assertThat(c.currentBudget().values()).allSatisfy(v ->
                    assertThat(v).isBetween(0L, cap));
        }
    }

    @Nested
    @DisplayName("committed preserved across mid-round observeCrowd (AD-C9)")
    class CommittedPreserved {

        @Test
        @DisplayName("committed totals survive the budget swap and the cap still binds")
        void committedSurvivesSwap() {
            Map<Integer, Integer> w = weights(1, 1, 2);
            long cap = 1000;
            BetCoordinator c = new BetCoordinator(w, cap, 10, 10, true);
            c.onRound(6L);

            // Fleet commits 300 on option 2 and 100 on option 0 before crowd arrives.
            assertThat(c.reserve(6L, 2, 300).decision()).isEqualTo(Decision.APPROVE);
            assertThat(c.reserve(6L, 0, 100).decision()).isEqualTo(Decision.APPROVE);
            assertThat(c.getCurrentAggregateStake()).isEqualTo(400L);

            // Mid-round crowd observation swaps the budget.
            c.observeCrowd(6L, List.of(crowd(0, 500), crowd(1, 0), crowd(2, 50)));

            // committed totals must survive the swap.
            assertThat(c.snapshot().options().get(0).committedStake()).isEqualTo(100L);
            assertThat(c.snapshot().options().get(2).committedStake()).isEqualTo(300L);
            assertThat(c.getCurrentAggregateStake()).isEqualTo(400L);

            // The cap still binds: keep reserving; aggregate never exceeds C.
            for (int i = 0; i < 50; i++) {
                c.reserve(6L, i % 3, 1000);
            }
            assertThat(c.getCurrentAggregateStake()).isLessThanOrEqualTo(cap);
        }

        @Test
        @DisplayName("double-count: X(o) = max(0, v(o) − committed(o)) closes the loop as the fleet fills")
        void doubleCountSubtraction() {
            // Single option to make the arithmetic exact.
            Map<Integer, Integer> w = weights(1);
            long cap = 1000;
            BetCoordinator c = new BetCoordinator(w, cap, 10, 10, true);
            c.onRound(3L);

            // Fleet commits 200 on option 0.
            assertThat(c.reserve(3L, 0, 200).decision()).isEqualTo(Decision.APPROVE);

            // The crowd v(0) reported by the feed INCLUDES the fleet's own 200
            // (bots are subscribers). Feed says v=200 → pure crowd X = 200-200 = 0
            // → B_crowd = floor(1*(0+1000)/1) - 0 = 1000 (internal tier, no real crowd).
            c.observeCrowd(3L, List.of(crowd(0, 200)));
            assertThat(c.currentBudget().get(0)).isEqualTo(1000L);

            // Now the feed reports v=700: 200 is the fleet's, so pure crowd X = 500.
            //   B_crowd = floor(1*(500+1000)/1) - 500 = 1500 - 500 = 1000 → clamp cap 1000.
            c.observeCrowd(3L, List.of(crowd(0, 700)));
            assertThat(c.currentBudget().get(0)).isEqualTo(1000L);

            // With two options the subtraction is visibly non-trivial:
            Map<Integer, Integer> w2 = weights(1, 1);
            BetCoordinator c2 = new BetCoordinator(w2, cap, 10, 10, true);
            c2.onRound(3L);
            c2.reserve(3L, 0, 400); // fleet committed 400 on opt0
            // Feed v(0)=400 (all fleet's own), v(1)=600 (all real crowd).
            //   X(0) = max(0, 400-400) = 0 ; X(1) = max(0, 600-0) = 600
            //   pool = 600 + 1000 = 1600
            //   opt0: floor(1*1600/2) - 0   = 800
            //   opt1: floor(1*1600/2) - 600 = 800 - 600 = 200
            c2.observeCrowd(3L, List.of(crowd(0, 400), crowd(1, 600)));
            assertThat(c2.currentBudget().get(0)).isEqualTo(800L);
            assertThat(c2.currentBudget().get(1)).isEqualTo(200L);
        }
    }

    @Nested
    @DisplayName("value-only steering (AD-C5)")
    class ValueOnly {

        @Test
        @DisplayName("garbage bc counts yield the same budget as zero counts")
        void countNeverAffectsBudget() {
            Map<Integer, Integer> w = weights(1, 1, 2);
            long cap = 1000;

            BetCoordinator zero = new BetCoordinator(w, cap, 10, 10, true);
            zero.onRound(1L);
            zero.observeCrowd(1L, List.of(
                    crowd(0, 300, 0), crowd(1, 100, 0), crowd(2, 0, 0)));

            BetCoordinator garbage = new BetCoordinator(w, cap, 10, 10, true);
            garbage.onRound(1L);
            garbage.observeCrowd(1L, List.of(
                    crowd(0, 300, Integer.MAX_VALUE),
                    crowd(1, 100, -999),
                    crowd(2, 0, 123456)));

            // Same v(o), wildly different bc → budgets must be identical.
            assertThat(garbage.currentBudget()).isEqualTo(zero.currentBudget());
        }
    }

    @Nested
    @DisplayName("stale sid + sentinel")
    class Staleness {

        @Test
        @DisplayName("observeCrowd for a stale (mismatched) sid is dropped")
        void staleSidDropped() {
            Map<Integer, Integer> w = weights(1, 1, 2);
            long cap = 1000;
            BetCoordinator c = new BetCoordinator(w, cap, 10, 10, true);
            c.onRound(10L);
            Map<Integer, Long> internal = internalBudget(w, cap);

            // A straddling frame for a stale/older sid must not touch the budget.
            c.observeCrowd(9L, List.of(crowd(0, 900), crowd(1, 0), crowd(2, 0)));
            assertThat(c.currentBudget()).isEqualTo(internal);

            // The 0 sentinel is likewise ignored.
            c.observeCrowd(0L, List.of(crowd(0, 900)));
            assertThat(c.currentBudget()).isEqualTo(internal);

            // The live sid does take effect.
            c.observeCrowd(10L, List.of(crowd(0, 600), crowd(1, 0), crowd(2, 0)));
            assertThat(c.currentBudget().get(0)).isEqualTo(0L);
        }

        @Test
        @DisplayName("unknown eids in the crowd feed are ignored defensively")
        void unknownEidsIgnored() {
            Map<Integer, Integer> w = weights(1, 1); // options 0, 1 only
            long cap = 1000;
            BetCoordinator c = new BetCoordinator(w, cap, 10, 10, true);
            c.onRound(2L);
            // eid 99 is not an affinity option → must be ignored (no crash).
            c.observeCrowd(2L, List.of(crowd(0, 200), crowd(99, 5000), crowd(1, 0)));
            Map<Integer, Long> after = c.currentBudget();
            // X_total counts only known options: X(0)=200 → pool=1200
            //   opt0: floor(1*1200/2) - 200 = 600 - 200 = 400
            //   opt1: floor(1*1200/2) - 0   = 600
            assertThat(after.get(0)).isEqualTo(400L);
            assertThat(after.get(1)).isEqualTo(600L);
            assertThat(after).doesNotContainKey(99);
        }

        @Test
        @DisplayName("a new round with NO prior crowd ever observed reverts to internal tier")
        void newRoundWithoutPriorCrowdRevertsToInternal() {
            Map<Integer, Integer> w = weights(1, 1, 2);
            long cap = 1000;
            BetCoordinator c = new BetCoordinator(w, cap, 10, 10, true);
            c.onRound(1L); // never observed any crowd
            c.onRound(2L); // fresh round, still no crowd ever seen
            // No carry-forward seed exists → the internal tier (reduction identity).
            assertThat(c.currentBudget()).isEqualTo(internalBudget(w, cap));
        }
    }

    @Nested
    @DisplayName("one-round-lag carry-forward (AD-C3, BOM/B52/Nohu EndGame seed)")
    class CarryForward {

        @Test
        @DisplayName("an EndGame crowd observation on round N seeds round N+1's opening budget")
        void endGameObservationSeedsNextRound() {
            // BOM/B52/Nohu have NO intra-round bs: their only crowd signal is the
            // prior round's EndGame bs, which must seed the NEXT round's opening
            // budget (one-round lag). Affinity 1:1:2 over cap 1000 → internal 250/250/500.
            Map<Integer, Integer> w = weights(1, 1, 2);
            long cap = 1000;
            BetCoordinator c = new BetCoordinator(w, cap, 10, 10, true);

            c.onRound(100L);
            // Full-round crowd distribution reported at EndGame (fed against the still-
            // current finished round's sid, as onEndGame does). Crowd piles 600 on the
            // low-affinity option 0.
            c.observeCrowd(100L, List.of(crowd(0, 600), crowd(1, 0), crowd(2, 0)));

            // Round rolls over. The NEXT round must OPEN seeded from that distribution
            // (committed=0 at open, so X(o)=v(o)):
            //   X_total=600, pool=1600
            //   opt0: floor(1*1600/4) - 600 = 400 - 600 = -200 → clamp 0
            //   opt1: floor(1*1600/4) - 0   = 400
            //   opt2: floor(2*1600/4) - 0   = 800
            c.onRound(101L);
            Map<Integer, Long> opening = c.currentBudget();
            assertThat(opening.get(0)).isEqualTo(0L);
            assertThat(opening.get(1)).isEqualTo(400L);
            assertThat(opening.get(2)).isEqualTo(800L);
            // It must NOT be the internal tier — the lag actually steered the open.
            assertThat(opening).isNotEqualTo(internalBudget(w, cap));
        }

        @Test
        @DisplayName("a fresh intra-round observation overwrites the carried-forward seed (Tip path)")
        void freshIntraRoundOverwritesSeed() {
            Map<Integer, Integer> w = weights(1, 1, 2);
            long cap = 1000;
            BetCoordinator c = new BetCoordinator(w, cap, 10, 10, true);

            c.onRound(200L);
            c.observeCrowd(200L, List.of(crowd(0, 600), crowd(1, 0), crowd(2, 0)));
            c.onRound(201L); // seeded from the 600-on-opt0 prior
            assertThat(c.currentBudget().get(0)).isEqualTo(0L);

            // A fresh intra-round frame for the new round (Tip's UpdateBet) must win —
            // the seed is only a lagged prior. Now the crowd is on opt2 instead.
            //   X(2)=600, pool=1600 → opt0: 400, opt1: 400, opt2: floor(2*1600/4)-600=200
            c.observeCrowd(201L, List.of(crowd(0, 0), crowd(1, 0), crowd(2, 600)));
            assertThat(c.currentBudget().get(0)).isEqualTo(400L);
            assertThat(c.currentBudget().get(1)).isEqualTo(400L);
            assertThat(c.currentBudget().get(2)).isEqualTo(200L);
        }

        @Test
        @DisplayName("crowd-off rollover always yields the internal-tier budget (reduction identity)")
        void crowdOffRolloverStaysInternal() {
            // A crowd-DISABLED coordinator never seeds a carry-forward, even if
            // observeCrowd is called — every round opens at the internal tier.
            Map<Integer, Integer> w = weights(1, 1, 2);
            long cap = 1000;
            BetCoordinator off = new BetCoordinator(w, cap, 10, 10); // crowdAware=false

            off.onRound(300L);
            off.observeCrowd(300L, List.of(crowd(0, 900), crowd(1, 0), crowd(2, 0)));
            off.onRound(301L);
            off.onRound(302L);
            assertThat(off.currentBudget()).isEqualTo(internalBudget(w, cap));
        }
    }

    @Nested
    @DisplayName("monotonic-by-arrival (AD-C3)")
    class MonotonicByArrival {

        @Test
        @DisplayName("a straggler frame with a smaller aggregate does not overwrite a newer/larger one")
        void olderSmallerFrameIgnored() {
            Map<Integer, Integer> w = weights(1, 1);
            long cap = 1000;
            BetCoordinator c = new BetCoordinator(w, cap, 10, 10, true);
            c.onRound(1L);

            // Newer/larger frame lands first: Σv = 800.
            //   X(0)=800, pool=1800 → opt0: floor(1*1800/2)-800=100, opt1: 900
            c.observeCrowd(1L, List.of(crowd(0, 800), crowd(1, 0)));
            Map<Integer, Long> afterLarge = c.currentBudget();
            assertThat(afterLarge.get(0)).isEqualTo(100L);
            assertThat(afterLarge.get(1)).isEqualTo(900L);

            // A reordered straggler with a SMALLER aggregate (Σv=200) must be ignored.
            c.observeCrowd(1L, List.of(crowd(0, 200), crowd(1, 0)));
            assertThat(c.currentBudget()).isEqualTo(afterLarge);

            // An equal-aggregate frame is likewise ignored (strictly-greater gate).
            c.observeCrowd(1L, List.of(crowd(0, 500), crowd(1, 300)));
            assertThat(c.currentBudget()).isEqualTo(afterLarge);

            // A genuinely larger frame does apply — Σv=2000 all on opt0.
            //   X(0)=2000, pool=3000 → opt0: floor(1*3000/2)-2000=-500→0, opt1: 1500→clamp cap 1000
            c.observeCrowd(1L, List.of(crowd(0, 2000), crowd(1, 0)));
            assertThat(c.currentBudget()).isNotEqualTo(afterLarge);
            assertThat(c.currentBudget().get(0)).isEqualTo(0L);
            assertThat(c.currentBudget().get(1)).isEqualTo(cap);
        }

        @Test
        @DisplayName("the high-water mark resets each round so the first frame always applies")
        void highWaterResetsPerRound() {
            Map<Integer, Integer> w = weights(1, 1);
            long cap = 1000;
            BetCoordinator c = new BetCoordinator(w, cap, 10, 10, true);

            c.onRound(1L);
            c.observeCrowd(1L, List.of(crowd(0, 5000), crowd(1, 0))); // large Σv=5000

            // New round: the mark resets, so a fresh SMALL frame (Σv=100) must apply
            // (it is the first frame of the new round, overwriting the lagged seed).
            c.onRound(2L);
            c.observeCrowd(2L, List.of(crowd(0, 100), crowd(1, 0)));
            //   X(0)=100, pool=1100 → opt0: floor(1*1100/2)-100=450, opt1: 550
            assertThat(c.currentBudget().get(0)).isEqualTo(450L);
            assertThat(c.currentBudget().get(1)).isEqualTo(550L);
        }
    }

    @Nested
    @DisplayName("AD-C10 per-option health fields")
    class HealthFields {

        @Test
        @DisplayName("crowd-aware snapshot surfaces observedCrowdStake, crowdAdjustedBudget, observedCrowdCount")
        void crowdAwareSurfacesAllThree() {
            Map<Integer, Integer> w = weights(1, 1, 2);
            long cap = 1000;
            // Semantic BETS → the count IS surfaced.
            BetCoordinator c = new BetCoordinator(w, cap, 10, 10, true, "BETS");
            c.onRound(1L);
            // Crowd on opt0: v=600 count=7; opt1: v=0 count=0; opt2: v=0 count=0.
            //   X(0)=600, pool=1600 → adj: opt0=0, opt1=400, opt2=800
            c.observeCrowd(1L, List.of(crowd(0, 600, 7), crowd(1, 0, 0), crowd(2, 0, 0)));

            BetCoordinator.Snapshot snap = c.snapshot();
            // Ordered by optionAffinities key set (0,1,2).
            assertThat(snap.options()).extracting(BetCoordinator.OptionSnapshot::optionId)
                    .containsExactly(0, 1, 2);

            BetCoordinator.OptionSnapshot o0 = snap.options().get(0);
            assertThat(o0.observedCrowdStake()).isEqualTo(600L);   // raw v(o)
            assertThat(o0.crowdAdjustedBudget()).isEqualTo(0L);    // B_crowd(o0)
            assertThat(o0.observedCrowdCount()).isEqualTo(7L);     // bc(o), semantic known

            assertThat(snap.options().get(1).crowdAdjustedBudget()).isEqualTo(400L);
            assertThat(snap.options().get(2).crowdAdjustedBudget()).isEqualTo(800L);
            assertThat(snap.options().get(1).observedCrowdStake()).isEqualTo(0L);
        }

        @Test
        @DisplayName("count is suppressed (0) when the semantic is UNKNOWN")
        void countSuppressedWhenUnknown() {
            Map<Integer, Integer> w = weights(1, 1);
            long cap = 1000;
            BetCoordinator c = new BetCoordinator(w, cap, 10, 10, true); // UNKNOWN
            c.onRound(1L);
            c.observeCrowd(1L, List.of(crowd(0, 300, 42), crowd(1, 0, 9)));

            BetCoordinator.Snapshot snap = c.snapshot();
            // observedCrowdCount is obs-only and semantic-gated → 0 under UNKNOWN,
            // even though bc was reported.
            assertThat(snap.options().get(0).observedCrowdCount()).isEqualTo(0L);
            // The stake fields are still surfaced.
            assertThat(snap.options().get(0).observedCrowdStake()).isEqualTo(300L);
        }

        @Test
        @DisplayName("a crowd-off coordinator surfaces zero crowd fields and the internal-tier adjusted budget")
        void crowdOffFieldsInert() {
            Map<Integer, Integer> w = weights(1, 1, 2);
            long cap = 1000;
            BetCoordinator off = new BetCoordinator(w, cap, 10, 10); // crowdAware=false
            off.onRound(1L);
            off.observeCrowd(1L, List.of(crowd(0, 900, 5))); // inert (gated)

            BetCoordinator.Snapshot snap = off.snapshot();
            assertThat(snap.crowdAware()).isFalse();
            for (BetCoordinator.OptionSnapshot o : snap.options()) {
                assertThat(o.observedCrowdStake()).isZero();
                assertThat(o.observedCrowdCount()).isZero();
                assertThat(o.crowdStake()).isZero();
            }
            // crowdAdjustedBudget reduces to the internal tier B(o) = 250/250/500.
            assertThat(snap.options().get(0).crowdAdjustedBudget()).isEqualTo(250L);
            assertThat(snap.options().get(1).crowdAdjustedBudget()).isEqualTo(250L);
            assertThat(snap.options().get(2).crowdAdjustedBudget()).isEqualTo(500L);
        }
    }

    @Nested
    @DisplayName("concurrency (AD-C9)")
    class Concurrency {

        @Test
        @DisplayName("concurrent reserve + observeCrowd never breach cap or option bounds")
        void concurrentReserveAndObserve() throws Exception {
            int nOptions = 6;
            long cap = 50_000;
            int minBet = 10;
            int increment = 10;
            BetCoordinator c = new BetCoordinator(
                    weights(1, 1, 1, 1, 1, 1), cap, minBet, increment, true);
            long sid = 555L;
            c.onRound(sid);

            int reserveThreads = 48;
            int observeThreads = 16;
            int reservesPerThread = 200;
            int observesPerThread = 100;

            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch ready = new CountDownLatch(reserveThreads + observeThreads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(reserveThreads + observeThreads);
            AtomicLong approvedPlusTrimmed = new AtomicLong();

            for (int t = 0; t < reserveThreads; t++) {
                final int seed = t;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        java.util.Random rnd = new java.util.Random(seed);
                        for (int i = 0; i < reservesPerThread; i++) {
                            int option = rnd.nextInt(nOptions);
                            long amount = (1 + rnd.nextInt(20)) * (long) increment;
                            ReservationOutcome o = c.reserve(sid, option, amount);
                            if (o.decision() != Decision.REJECT) {
                                approvedPlusTrimmed.addAndGet(o.amount());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            for (int t = 0; t < observeThreads; t++) {
                final int seed = 1000 + t;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        java.util.Random rnd = new java.util.Random(seed);
                        for (int i = 0; i < observesPerThread; i++) {
                            List<CrowdOption> obs = new ArrayList<>(nOptions);
                            for (int o = 0; o < nOptions; o++) {
                                obs.add(crowd(o, rnd.nextInt(20_000)));
                            }
                            c.observeCrowd(sid, obs);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            BetCoordinator.Snapshot snap = c.snapshot();
            // The aggregate cap is the hard ceiling regardless of crowd swaps.
            assertThat(snap.currentAggregateStake()).isLessThanOrEqualTo(cap);
            assertThat(approvedPlusTrimmed.get()).isEqualTo(snap.currentAggregateStake());
            assertThat(snap.currentAggregateStake() % increment).isZero();
            // Every option's live crowd-adjusted budget stays in [0, C].
            assertThat(c.currentBudget().values()).allSatisfy(v ->
                    assertThat(v).isBetween(0L, cap));
        }
    }
}
