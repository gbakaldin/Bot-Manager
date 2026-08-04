package com.vingame.bot.infrastructure.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit tests for the STRATEGY_DECISION_AGGREGATION additions to
 * {@link SessionAccumulator} — the tumbling option histogram and the window
 * amount min/max. These bypass the service/render layer (covered by
 * {@link SessionAggregationDecisionTest}) and pin the accumulator's
 * capture-then-drain contract directly, so the anti-leak and lost-update
 * invariants are asserted at their source.
 * <p>
 * Everything runs on the calling thread (the accumulator's drain is
 * single-flush-thread by contract), so the suite is deterministic — no sleeps,
 * no scheduler.
 */
@DisplayName("SessionAccumulator - strategy-decision histogram + amount min/max")
class SessionAccumulatorDecisionTest {

    private SessionAccumulator newAcc() {
        return new SessionAccumulator(BettingSessionStrategy.INSTANCE, Map.of(), System.nanoTime());
    }

    @Test
    @DisplayName("histogram counts are correct across multiple bets in one window")
    void histogram_countsCorrectlyInOneWindow() {
        SessionAccumulator acc = newAcc();
        acc.recordBet("a", 0, 100L);
        acc.recordBet("b", 0, 100L);
        acc.recordBet("c", 1, 100L);
        acc.recordBet("d", 5, 100L);
        acc.recordBet("e", 5, 100L);
        acc.recordBet("f", 5, 100L);

        acc.captureFlushSnapshot();

        assertThat(acc.flushOptionSnapshot()).containsExactly(
                Map.entry(0, 2L), Map.entry(1, 1L), Map.entry(5, 3L));
        // Sorted ascending by option id (TreeMap) so the rendered line is deterministic.
        assertThat(acc.flushOptionSnapshot().keySet()).containsExactly(0, 1, 5);
    }

    @Test
    @DisplayName("tumbling reset: a drained key returns to empty and a later window carries only its own bets")
    void histogram_tumblesAndReturnsToEmptyWhenIdle() {
        SessionAccumulator acc = newAcc();

        // Window 1.
        acc.recordBet("a", 0, 100L);
        acc.recordBet("b", 0, 200L);
        acc.recordBet("c", 1, 300L);
        acc.captureFlushSnapshot();
        assertThat(acc.flushOptionSnapshot()).containsExactly(Map.entry(0, 2L), Map.entry(1, 1L));

        // Window 2: NO new bets — the drained cells reset to zero and the snapshot is
        // empty. This is the anti-leak invariant: an idle window retains no per-window
        // decision state (the keys persist internally but their counters are drained).
        acc.captureFlushSnapshot();
        assertThat(acc.flushOptionSnapshot()).as("idle window returns to empty").isEmpty();

        // Window 3: a single bet on a PREVIOUSLY-seen key reuses the reset cell — the
        // count is 1 (its own bet), not 3 (window-1 carryover) → genuine reset.
        acc.recordBet("d", 0, 900L);
        acc.captureFlushSnapshot();
        assertThat(acc.flushOptionSnapshot()).containsExactly(Map.entry(0, 1L));
    }

    @Test
    @DisplayName("amount min/max: single-value window collapses min==max")
    void amountMinMax_singleValue() {
        SessionAccumulator acc = newAcc();
        acc.recordBet("a", 3, 777L);
        acc.captureFlushSnapshot();

        assertThat(acc.flushMinSnapshot()).isEqualTo(777L);
        assertThat(acc.flushMaxSnapshot()).isEqualTo(777L);
    }

    @Test
    @DisplayName("amount min/max reset each window to the accumulator identity when idle")
    void amountMinMax_resetToIdentityWhenIdle() {
        SessionAccumulator acc = newAcc();
        acc.recordBet("a", 0, 500L);
        acc.captureFlushSnapshot();
        assertThat(acc.flushMinSnapshot()).isEqualTo(500L);
        assertThat(acc.flushMaxSnapshot()).isEqualTo(500L);

        // Idle window: min/max drain back to identity (MAX_VALUE / MIN_VALUE). The
        // renderer gates on windowBets<=0 and prints '-' rather than these identities,
        // so they never surface — asserted here so the reset contract is explicit.
        acc.captureFlushSnapshot();
        assertThat(acc.flushMinSnapshot()).isEqualTo(Long.MAX_VALUE);
        assertThat(acc.flushMaxSnapshot()).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    @DisplayName("a null option is NOT counted in the histogram but still feeds staked + min/max (slot Phase-1 delegation)")
    void nullOption_skipsHistogramButFeedsAmount() {
        SessionAccumulator acc = newAcc();
        acc.recordBet("a", null, 100L); // the recordBet(bettor, amount) delegation path
        acc.recordBet("b", 5, 200L);
        acc.captureFlushSnapshot();

        // Only the option-bearing bet is in the histogram...
        assertThat(acc.flushOptionSnapshot()).containsExactly(Map.entry(5, 1L));
        // ...but both bets feed the amount window and the staked total.
        assertThat(acc.flushMinSnapshot()).isEqualTo(100L);
        assertThat(acc.flushMaxSnapshot()).isEqualTo(200L);
        assertThat(acc.flushStakedSnapshot()).isEqualTo(300L);
        assertThat(acc.flushSpinSnapshot()).as("both are bet events").isEqualTo(2L);
    }

    @Test
    @DisplayName("amount=0 is counted in the histogram + bet count but gated out of staked and min/max")
    void zeroAmount_gatedFromStakedAndMinMax() {
        SessionAccumulator acc = newAcc();
        acc.recordBet("a", 3, 0L); // zero-amount bet
        acc.captureFlushSnapshot();

        // The option decision is still recorded...
        assertThat(acc.flushOptionSnapshot()).containsExactly(Map.entry(3, 1L));
        assertThat(acc.flushSpinSnapshot()).as("bet event is counted").isEqualTo(1L);
        // ...but the amount>0 gate keeps 0 out of staked and out of min/max (they stay
        // at the accumulator identity). Documents that a zero-amount bet does not
        // participate in the amount summary — production bet amounts are always > 0.
        assertThat(acc.flushStakedSnapshot()).isZero();
        assertThat(acc.flushMinSnapshot()).isEqualTo(Long.MAX_VALUE);
        assertThat(acc.flushMaxSnapshot()).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    @DisplayName("lost-update: a bet arriving after capture lands wholly in the NEXT window — histogram and staked delta agree, no loss, no double-count")
    void midFlushBet_consistentAcrossHistogramAndStakedDelta() {
        SessionAccumulator acc = newAcc();

        // ---- Window 1 ----
        acc.recordBet("a", 0, 100L);
        acc.captureFlushSnapshot();               // drains window 1: {0:1}, staked snap 100
        Map<Integer, Long> w1 = acc.flushOptionSnapshot();
        long w1Staked = acc.flushStakedSnapshot();
        long w1Bets = acc.flushSpinSnapshot();

        // A bet arrives AFTER the snapshot capture but BEFORE the baseline advances —
        // the exact mid-flush race the capture-then-advance seam handles.
        acc.recordBet("b", 5, 999L);

        // Baseline advance uses the SAME captured snapshot (as emitFlush does).
        acc.advanceBaseline(acc.flushBettorSnapshot(), w1Staked);
        acc.advanceSpinBaseline(w1Bets);

        // Window 1 must NOT contain the mid-flush bet.
        assertThat(w1).containsExactly(Map.entry(0, 1L));

        // ---- Window 2 ----
        acc.captureFlushSnapshot();
        Map<Integer, Long> w2 = acc.flushOptionSnapshot();
        long w2Bets = acc.flushSpinSnapshot() - acc.spinBaseline();
        long w2Staked = acc.flushStakedSnapshot() - acc.stakedBaseline();

        // The mid-flush bet lands exactly once in window 2's histogram...
        assertThat(w2).containsExactly(Map.entry(5, 1L));
        // ...and the histogram count agrees with the staked/bet deltas for the SAME
        // window — the invariant that a bet isn't lost from both windows nor
        // double-counted across the histogram vs the delta counters.
        long w2HistogramSum = w2.values().stream().mapToLong(Long::longValue).sum();
        assertThat(w2HistogramSum).isEqualTo(w2Bets).isEqualTo(1L);
        assertThat(w2Staked).isEqualTo(999L);
        assertThat(acc.flushMinSnapshot()).isEqualTo(999L);
        assertThat(acc.flushMaxSnapshot()).isEqualTo(999L);
    }
}
