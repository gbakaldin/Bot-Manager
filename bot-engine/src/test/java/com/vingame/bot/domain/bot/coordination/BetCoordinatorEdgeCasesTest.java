package com.vingame.bot.domain.bot.coordination;

import com.vingame.bot.domain.bot.coordination.ReservationOutcome.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supplementary QA coverage for {@link BetCoordinator} — the edge cases the plan
 * (docs/plans/BET_COORDINATION.md, AD-5) calls out beyond the happy-path already
 * pinned by {@code BetCoordinatorTest}:
 *
 * <ul>
 *   <li>degenerate grid: {@code betIncrement == 0} and {@code betIncrement < 0}
 *       degrade to "no grid" — any amount ≥ minBet is acceptable verbatim, a trim
 *       clamps to the exact remaining headroom (never rounds);</li>
 *   <li>degenerate budgets: a tiny cap floors every per-option budget to 0, and
 *       all-zero affinity weights yield zero budgets — every reserve REJECTs;</li>
 *   <li>a single option receives the full cap;</li>
 *   <li>{@code onRound(0)} sentinel never starts a budget;</li>
 *   <li>{@code onRoundComplete} is a no-op with no active round, and does NOT
 *       reset committed state (only the next {@code onRound} with a new sid does);</li>
 *   <li>a tiny-cap reject-storm under 64-thread concurrency still never breaches
 *       the cap or any option budget.</li>
 * </ul>
 */
@DisplayName("BetCoordinator — edge cases")
class BetCoordinatorEdgeCasesTest {

    private static Map<Integer, Integer> weights(int... w) {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < w.length; i++) {
            m.put(i, w[i]);
        }
        return m;
    }

    @Nested
    @DisplayName("degenerate grid (betIncrement <= 0)")
    class DegenerateGrid {

        @Test
        @DisplayName("betIncrement == 0 approves a non-grid amount verbatim")
        void zeroIncrementApprovesVerbatim() {
            BetCoordinator c = new BetCoordinator(weights(1), 10_000, 100, 0);
            c.onRound(1L);
            // No grid: allow = 155 (fits budget/cap), aligned = 155 == amount → APPROVE.
            ReservationOutcome o = c.reserve(1L, 0, 155);
            assertThat(o.decision()).isEqualTo(Decision.APPROVE);
            assertThat(o.amount()).isEqualTo(155L);
        }

        @Test
        @DisplayName("betIncrement == 0 trims to the EXACT remaining headroom (no rounding)")
        void zeroIncrementTrimsToExactRemaining() {
            // Two options weight 1:1 over cap 300 → per-option budget 150.
            BetCoordinator c = new BetCoordinator(weights(1, 1), 300, 100, 0);
            c.onRound(2L);
            // Propose 200 on option 0: allow = min(200, 150, 300) = 150; no grid → 150.
            ReservationOutcome o = c.reserve(2L, 0, 200);
            assertThat(o.decision()).isEqualTo(Decision.TRIM);
            assertThat(o.amount()).isEqualTo(150L);
            assertThat(c.snapshot().options().get(0).committedStake()).isEqualTo(150L);
        }

        @Test
        @DisplayName("negative betIncrement degrades to no-grid (does not throw / divide-by-zero)")
        void negativeIncrementDegradesToNoGrid() {
            BetCoordinator c = new BetCoordinator(weights(1), 10_000, 100, -50);
            c.onRound(3L);
            ReservationOutcome o = c.reserve(3L, 0, 137);
            assertThat(o.decision()).isEqualTo(Decision.APPROVE);
            assertThat(o.amount()).isEqualTo(137L);
        }

        @Test
        @DisplayName("no-grid reserve below minBet still REJECTs")
        void noGridRejectsBelowMinBet() {
            BetCoordinator c = new BetCoordinator(weights(1), 10_000, 100, 0);
            c.onRound(4L);
            assertThat(c.reserve(4L, 0, 50).decision()).isEqualTo(Decision.REJECT);
        }
    }

    @Nested
    @DisplayName("degenerate budgets")
    class DegenerateBudgets {

        @Test
        @DisplayName("tiny cap floors every per-option budget to 0 → all reserves REJECT")
        void tinyCapRejectsEverything() {
            // 6 options, cap 5 → floor(1/6 * 5) = 0 for every option.
            BetCoordinator c = new BetCoordinator(weights(1, 1, 1, 1, 1, 1), 5, 100, 10);
            c.onRound(10L);
            assertThat(c.snapshot().options())
                    .allSatisfy(o -> assertThat(o.targetBudget()).isZero());
            for (int opt = 0; opt < 6; opt++) {
                assertThat(c.reserve(10L, opt, 100).decision()).isEqualTo(Decision.REJECT);
            }
            assertThat(c.getCurrentAggregateStake()).isZero();
            assertThat(c.getRejectCount()).isEqualTo(6L);
        }

        @Test
        @DisplayName("all-zero affinity weights yield zero budgets → REJECT (no divide-by-zero)")
        void allZeroWeightsRejects() {
            BetCoordinator c = new BetCoordinator(weights(0, 0), 1000, 10, 10);
            c.onRound(11L);
            assertThat(c.snapshot().options())
                    .allSatisfy(o -> assertThat(o.targetBudget()).isZero());
            assertThat(c.reserve(11L, 0, 100).decision()).isEqualTo(Decision.REJECT);
        }

        @Test
        @DisplayName("a single option receives the full cap as its budget")
        void singleOptionGetsFullCap() {
            BetCoordinator c = new BetCoordinator(weights(1), 1000, 10, 10);
            c.onRound(12L);
            assertThat(c.snapshot().options().get(0).targetBudget()).isEqualTo(1000L);
            assertThat(c.reserve(12L, 0, 1000).decision()).isEqualTo(Decision.APPROVE);
            // budget now exhausted
            assertThat(c.reserve(12L, 0, 10).decision()).isEqualTo(Decision.REJECT);
            assertThat(c.getCurrentAggregateStake()).isEqualTo(1000L)
                    .isLessThanOrEqualTo(c.getMaxAggregateStakePerRound());
        }
    }

    @Nested
    @DisplayName("round lifecycle edges")
    class RoundLifecycleEdges {

        @Test
        @DisplayName("onRound(0) never starts a budget; a subsequent reserve rejects")
        void onRoundZeroIsNoOp() {
            BetCoordinator c = new BetCoordinator(weights(1, 1), 1000, 10, 10);
            c.onRound(0L);
            // No live round: even a well-formed reserve for a real sid rejects.
            assertThat(c.reserve(5L, 0, 100).decision()).isEqualTo(Decision.REJECT);
            assertThat(c.getCurrentAggregateStake()).isZero();
            // Once a real round opens, reserves flow again.
            c.onRound(5L);
            assertThat(c.reserve(5L, 0, 100).decision()).isEqualTo(Decision.APPROVE);
        }

        @Test
        @DisplayName("onRoundComplete with no active round is a silent no-op (counters untouched)")
        void onRoundCompleteNoActiveRound() {
            BetCoordinator c = new BetCoordinator(weights(1, 1), 1000, 10, 10);
            c.onRoundComplete(999L); // never begun
            assertThat(c.getApproveCount()).isZero();
            assertThat(c.getTrimCount()).isZero();
            assertThat(c.getRejectCount()).isZero();
        }

        @Test
        @DisplayName("onRoundComplete does NOT reset committed state — only the next onRound does")
        void onRoundCompletePreservesCommitted() {
            BetCoordinator c = new BetCoordinator(weights(1, 1), 1000, 10, 10);
            c.onRound(20L);
            c.reserve(20L, 0, 300);
            c.onRoundComplete(20L);
            // committed persists after completion (the DTO snapshot still reflects it)
            assertThat(c.getCurrentAggregateStake()).isEqualTo(300L);
            assertThat(c.snapshot().options().get(0).committedStake()).isEqualTo(300L);
            assertThat(c.getApproveCount()).isEqualTo(1L);
            // the NEXT round swaps in a fresh budget
            c.onRound(21L);
            assertThat(c.getCurrentAggregateStake()).isZero();
        }
    }

    @Nested
    @DisplayName("concurrency — reject storm")
    class RejectStorm {

        @Test
        @DisplayName("tiny cap under 64-thread contention never breaches cap or any option budget")
        void tinyCapConcurrencyNeverBreaches() throws Exception {
            int nOptions = 6;
            long cap = 1000;              // per-option budget = floor(1000/6) = 166
            int minBet = 100;
            int increment = 100;
            BetCoordinator c = new BetCoordinator(weights(1, 1, 1, 1, 1, 1), cap, minBet, increment);
            long sid = 555L;
            c.onRound(sid);

            int threads = 64;
            int reservesPerThread = 300;
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                final int seed = t;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        java.util.Random rnd = new java.util.Random(seed);
                        for (int i = 0; i < reservesPerThread; i++) {
                            int option = rnd.nextInt(nOptions);
                            long amount = (1 + rnd.nextInt(6)) * (long) increment; // 100..600
                            c.reserve(sid, option, amount);
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
            assertThat(snap.currentAggregateStake()).isLessThanOrEqualTo(cap);
            assertThat(snap.options()).allSatisfy(o ->
                    assertThat(o.committedStake()).isLessThanOrEqualTo(o.targetBudget()));
            assertThat(snap.currentAggregateStake() % increment).isZero();
            // Sanity: this is a genuine reject storm, not a no-op — far more proposals
            // (64*300) than the cap can ever admit, so rejects vastly dominate.
            assertThat(snap.rejectCount()).isGreaterThan(snap.approveCount() + snap.trimCount());
        }
    }
}
