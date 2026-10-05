package com.vingame.bot.domain.bot.core.cashout;

import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.FrameAction;
import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.Outcome;
import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.Plan;
import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.TimeoutAction;
import com.vingame.bot.domain.bot.message.cashout.CashoutBetFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CASHOUT_BOT QA: the edges of {@link CashoutBetStateMachine} that
 * {@code CashoutBetStateMachineTest} does not pin — late and out-of-order <i>terminal</i>
 * frames, the recently-ended ring's capacity, the exact watchdog boundary and backoff,
 * the ladder arithmetic for other {@code R}, probe edge cases and the watchdog-vs-terminal
 * race. Fake clock, seeded {@link Random}, no Spring.
 */
@DisplayName("CashoutBetStateMachine — QA edge cases")
class CashoutBetStateMachineEdgeCaseTest {

    private static final long FRAME_TIMEOUT_MS = 20_000L;
    private static final long BACKOFF_MS = 30_000L;
    private static final List<Long> STAKES = List.of(1_000L, 10_000L, 100_000L);
    private static final long RICH = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private CashoutBetStateMachine machine = newMachine(BACKOFF_MS, 3, 42L);

    private CashoutBetStateMachine newMachine(long backoffMs, int reconnectAfter, long seed) {
        return new CashoutBetStateMachine(CashoutBehavior.LEGACY, FRAME_TIMEOUT_MS, backoffMs,
                reconnectAfter, now::get, new Random(seed));
    }

    /** Only the fields the contract reads. */
    private static final class Frame extends CashoutBetFrame {
        private final long sid;
        private final Long stake;
        private final double multiplier;
        private final double crd;
        private final boolean fin;
        private final boolean burstFlag;

        Frame(long sid, Long stake, double multiplier, double crd, boolean fin, boolean burstFlag) {
            super(1501);
            this.sid = sid;
            this.stake = stake;
            this.multiplier = multiplier;
            this.crd = crd;
            this.fin = fin;
            this.burstFlag = burstFlag;
        }

        @Override public long sid() { return sid; }
        @Override public OptionalLong stake() { return stake == null ? OptionalLong.empty() : OptionalLong.of(stake); }
        @Override public double multiplier() { return multiplier; }
        @Override public double cashoutValue() { return crd; }
        @Override public boolean isFinal() { return fin; }
        @Override protected boolean burstSignalled() { return burstFlag; }
    }

    private static Frame progress(long sid, long stake, double multiplier) {
        return new Frame(sid, stake, multiplier, stake * multiplier, false, false);
    }

    /** The captured X502 burst: no {@code b}, {@code blS:-1}. */
    private static Frame burst(long sid) {
        return new Frame(sid, null, 4.0, 0.0, true, true);
    }

    /** The legacy X501 burst: carries {@code b}, {@code crd == 0}. */
    private static Frame burstWithStake(long sid, long stake) {
        return new Frame(sid, stake, 1.7, 0.0, true, false);
    }

    private static Frame win(long sid, double crd) {
        return new Frame(sid, null, 2.5, crd, true, false);
    }

    private Plan place() {
        machine.nextBetAt().ifPresent(at -> now.set(Math.max(now.get(), at)));
        Optional<Plan> plan = machine.tryPlace(STAKES, RICH);
        assertThat(plan).as("a bet is placed from IDLE once the pause has passed").isPresent();
        return plan.get();
    }

    private Plan placeAndBind(long sid) {
        Plan plan = place();
        assertThat(machine.onFrame(progress(sid, plan.amount(), 1.0))).isInstanceOf(FrameAction.Progress.class);
        return plan;
    }

    private TimeoutAction.TimedOut timeOut() {
        now.addAndGet(FRAME_TIMEOUT_MS);
        TimeoutAction action = machine.onTimeout();
        assertThat(action).isInstanceOf(TimeoutAction.TimedOut.class);
        return (TimeoutAction.TimedOut) action;
    }

    @Nested
    @DisplayName("late and out-of-order terminal frames")
    class LateTerminal {

        @Test
        @DisplayName("a duplicate terminal of the previous bet does not end the next PLACED bet")
        void duplicateTerminalWhilePlaced() {
            placeAndBind(20L);
            assertThat(machine.onFrame(burst(20L))).isInstanceOf(FrameAction.Ended.class);
            Plan next = place();

            // The X501 crd==0 and the X502 blS:-1 of one bet can BOTH arrive (F-4).
            assertThat(machine.onFrame(burstWithStake(20L, next.amount()))).isSameAs(CashoutBetStateMachine.NONE);
            assertThat(machine.onFrame(burst(20L))).isSameAs(CashoutBetStateMachine.NONE);
            assertThat(machine.inFlight()).isTrue();
            assertThat(machine.currentPlan()).containsSame(next);
        }

        @Test
        @DisplayName("a late win of the previous bet does not end the next LIVE bet")
        void lateWinWhileLive() {
            placeAndBind(30L);
            machine.onFrame(burst(30L));
            Plan next = placeAndBind(31L);

            assertThat(machine.onFrame(win(30L, 99_999.0))).isSameAs(CashoutBetStateMachine.NONE);
            assertThat(machine.boundSid()).hasValue(31L);
            assertThat(machine.currentPlan()).containsSame(next);
        }

        @Test
        @DisplayName("terminal first, progress of the same sid afterwards: ended once, progress ignored")
        void terminalBeforeProgress() {
            Plan plan = place();

            assertThat(machine.onFrame(burst(40L))).isInstanceOf(FrameAction.Ended.class);
            assertThat(machine.onFrame(progress(40L, plan.amount(), plan.target() + 1)))
                    .as("a reordered progress frame of an ended bet must not cash out")
                    .isSameAs(CashoutBetStateMachine.NONE);
        }

        @Test
        @DisplayName("a terminal whose stake differs from the plan does not end the PLACED bet")
        void terminalStakeMismatchWhilePlaced() {
            Plan plan = place();

            assertThat(machine.onFrame(burstWithStake(41L, plan.amount() + 1)))
                    .isSameAs(CashoutBetStateMachine.NONE);
            assertThat(machine.inFlight()).isTrue();
        }

        @Test
        @DisplayName("a late win of a timed-out CASHING bet is ignored — its winnings are not credited")
        void lateWinAfterTimeoutFromCashing() {
            Plan plan = placeAndBind(50L);
            assertThat(machine.onFrame(progress(50L, plan.amount(), plan.target())))
                    .isInstanceOf(FrameAction.SendCashout.class);

            TimeoutAction.TimedOut t = timeOut();
            assertThat(t.plan()).isEqualTo(plan);
            place();

            assertThat(machine.onFrame(win(50L, 123_456.0))).isSameAs(CashoutBetStateMachine.NONE);
        }
    }

    @Nested
    @DisplayName("the recently-ended ring")
    class Ring {

        @Test
        @DisplayName("remembers exactly the last four sids; the fifth-oldest may bind again")
        void capacityIsFour() {
            for (long sid = 1; sid <= 5; sid++) {
                placeAndBind(sid);
                machine.onFrame(burst(sid));
            }
            assertThat(CashoutBetStateMachine.RECENT_CAPACITY).isEqualTo(4);

            Plan next = place();
            for (long sid = 2; sid <= 5; sid++) {
                assertThat(machine.onFrame(progress(sid, next.amount(), 1.0)))
                        .as("sid %d is among the last four", sid)
                        .isSameAs(CashoutBetStateMachine.NONE);
            }
            assertThat(machine.onFrame(progress(1L, next.amount(), 1.0)))
                    .as("sid 1 was evicted from the ring")
                    .isInstanceOf(FrameAction.Progress.class);
        }

        @Test
        @DisplayName("a reset() does not forget ended sids")
        void ringSurvivesReset() {
            placeAndBind(60L);
            machine.onFrame(burst(60L));
            machine.reset();
            Plan next = place();

            assertThat(machine.onFrame(progress(60L, next.amount(), 1.0))).isSameAs(CashoutBetStateMachine.NONE);
        }
    }

    @Nested
    @DisplayName("the watchdog")
    class Watchdog {

        @Test
        @DisplayName("times out at exactly frameTimeout of silence, not one ms earlier")
        void exactBoundary() {
            place();
            now.addAndGet(FRAME_TIMEOUT_MS - 1);
            assertThat(machine.onTimeout()).isSameAs(CashoutBetStateMachine.NO_TIMEOUT);
            assertThat(machine.millisUntilTimeout()).isEqualTo(1L);

            now.addAndGet(1);
            assertThat(machine.onTimeout()).isInstanceOf(TimeoutAction.TimedOut.class);
            assertThat(machine.inFlight()).isFalse();
        }

        @Test
        @DisplayName("in PLACED the silence is measured from the send")
        void placedMeasuresFromSend() {
            place();
            now.addAndGet(5_000L);
            assertThat(machine.millisUntilTimeout()).isEqualTo(FRAME_TIMEOUT_MS - 5_000L);
        }

        @Test
        @DisplayName("progress in CASHING refreshes the silence clock")
        void cashingProgressRefreshes() {
            Plan plan = placeAndBind(70L);
            machine.onFrame(progress(70L, plan.amount(), plan.target()));
            now.addAndGet(FRAME_TIMEOUT_MS - 1);
            assertThat(machine.onFrame(progress(70L, plan.amount(), plan.target() + 1)))
                    .isInstanceOf(FrameAction.Progress.class);
            now.addAndGet(FRAME_TIMEOUT_MS - 1);

            assertThat(machine.onTimeout()).isSameAs(CashoutBetStateMachine.NO_TIMEOUT);
        }

        @Test
        @DisplayName("an ignored frame (other sid) does not refresh the silence clock")
        void ignoredFrameDoesNotRefresh() {
            Plan plan = placeAndBind(71L);
            now.addAndGet(FRAME_TIMEOUT_MS - 1);
            machine.onFrame(progress(72L, plan.amount(), 1.0));
            now.addAndGet(1);

            assertThat(machine.onTimeout()).isInstanceOf(TimeoutAction.TimedOut.class);
        }

        @Test
        @DisplayName("the next delay is the backoff plus a [500, 4500] ms pause draw (review #2: jitter)")
        void backoffPlusPauseDraw() {
            place();
            long at = now.get();
            TimeoutAction.TimedOut t = timeOut();

            assertThat(t.nextDelayMs()).isBetween(BACKOFF_MS + 500, BACKOFF_MS + 4_500);
            assertThat(machine.nextBetAt()).hasValue(at + FRAME_TIMEOUT_MS + t.nextDelayMs());
        }

        @Test
        @DisplayName("a zero backoff falls back to the normal [500, 4500] ms pause")
        void zeroBackoffUsesPause() {
            machine = newMachine(0L, 3, 7L);
            for (int i = 0; i < 200; i++) {
                place();
                TimeoutAction.TimedOut t = timeOut();
                assertThat(t.nextDelayMs()).isBetween(500L, 4_500L);
            }
        }

        @Test
        @DisplayName("a frameTimeout <= 0 is rejected at construction")
        void rejectsNonPositiveTimeout() {
            assertThatThrownBy(() -> new CashoutBetStateMachine(CashoutBehavior.LEGACY, 0L, BACKOFF_MS, 3,
                    now::get, new Random(1))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the ladder")
    class Ladder {

        @Test
        @DisplayName("R = 1 reconnects at 1, 2, 4, 8, 16, 32, then every 32")
        void rOne() {
            CashoutBetStateMachine m = newMachine(BACKOFF_MS, 1, 1L);
            List<Integer> rungs = new ArrayList<>();
            for (int i = 1; i <= 130; i++) {
                if (m.isReconnectRung(i)) rungs.add(i);
            }
            assertThat(rungs).containsExactly(1, 2, 4, 8, 16, 32, 64, 96, 128);
        }

        @Test
        @DisplayName("R <= 0 disables reconnects entirely")
        void rDisabled() {
            for (int r : new int[]{0, -3}) {
                CashoutBetStateMachine m = newMachine(BACKOFF_MS, r, 1L);
                for (int i = 0; i <= 500; i++) {
                    assertThat(m.isReconnectRung(i)).as("R=%d count=%d", r, i).isFalse();
                }
            }
        }

        @Test
        @DisplayName("ignored frames (stale sid, wrong stake, IDLE) do not reset the count")
        void ignoredFramesKeepTheCount() {
            place();
            timeOut();
            timeOut2();
            assertThat(machine.consecutiveTimeouts()).isEqualTo(2);

            machine.onFrame(progress(999L, 1_000L, 1.0)); // IDLE
            Plan probe = place();
            machine.onFrame(progress(998L, probe.amount() + 1, 1.0)); // stake mismatch

            assertThat(machine.consecutiveTimeouts()).isEqualTo(2);
        }

        private void timeOut2() {
            place();
            timeOut();
        }

        @Test
        @DisplayName("an immediate terminal from PLACED counts as a bound frame and resets the count")
        void terminalFromPlacedResets() {
            place();
            timeOut();
            assertThat(machine.consecutiveTimeouts()).isEqualTo(1);

            place();
            machine.onFrame(burst(80L));

            assertThat(machine.consecutiveTimeouts()).isZero();
            assertThat(place().probe()).isFalse();
        }

        @Test
        @DisplayName("the count keeps climbing across a reconnect rung until a frame binds")
        void countClimbsPastRung() {
            for (int i = 1; i <= 4; i++) {
                place();
                TimeoutAction.TimedOut t = timeOut();
                assertThat(t.consecutiveTimeouts()).isEqualTo(i);
                assertThat(t.reconnect()).isEqualTo(i == 3);
            }
        }
    }

    @Nested
    @DisplayName("probes")
    class Probes {

        @Test
        @DisplayName("a probe stakes the minimum even when the eligible list is unsorted")
        void probeTakesMinOfUnsorted() {
            place();
            timeOut();
            machine.nextBetAt().ifPresent(now::set);

            Optional<Plan> plan = machine.tryPlace(List.of(100_000L, 1_000L, 10_000L), RICH);

            assertThat(plan).map(Plan::amount).hasValue(1_000L);
            assertThat(plan).map(Plan::probe).hasValue(true);
        }

        @Test
        @DisplayName("an unaffordable probe places nothing")
        void unaffordableProbe() {
            place();
            timeOut();
            machine.nextBetAt().ifPresent(now::set);

            assertThat(machine.tryPlace(STAKES, 999L)).isEmpty();
            assertThat(machine.inFlight()).isFalse();
        }

        @Test
        @DisplayName("the probe flag is false on ordinary bets")
        void ordinaryBetIsNotAProbe() {
            for (int i = 0; i < 50; i++) {
                assertThat(place().probe()).isFalse();
                machine.reset();
            }
        }
    }

    @Test
    @DisplayName("watchdog racing the terminal frame: exactly one of TIMED_OUT / ENDED per bet (200 rounds)")
    void watchdogRacesTerminal() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 200; round++) {
                machine = newMachine(BACKOFF_MS, 3, round);
                long sid = 9_000L + round;
                placeAndBind(sid);
                now.addAndGet(FRAME_TIMEOUT_MS); // both are now eligible to end the bet

                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger ends = new AtomicInteger();
                AtomicInteger timeouts = new AtomicInteger();
                Future<?> a = pool.submit(() -> {
                    start.await();
                    if (machine.onFrame(burst(sid)) instanceof FrameAction.Ended) ends.incrementAndGet();
                    return null;
                });
                Future<?> b = pool.submit(() -> {
                    start.await();
                    if (machine.onTimeout() instanceof TimeoutAction.TimedOut) timeouts.incrementAndGet();
                    return null;
                });
                start.countDown();
                a.get(10, TimeUnit.SECONDS);
                b.get(10, TimeUnit.SECONDS);

                assertThat(ends.get() + timeouts.get()).as("round %d", round).isEqualTo(1);
                assertThat(machine.inFlight()).isFalse();
                assertThat(machine.consecutiveTimeouts()).isEqualTo(timeouts.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a win's outcome is CASHOUT even when crd is tiny but positive; 0 is a burst")
    void outcomeBoundaryOnCrd() {
        place();
        FrameAction.Ended tiny = (FrameAction.Ended) machine.onFrame(win(90L, 0.4));
        assertThat(tiny.outcome()).isEqualTo(Outcome.CASHOUT);
        assertThat(tiny.winnings()).as("round(0.4)").isZero();

        place();
        FrameAction.Ended zero = (FrameAction.Ended) machine.onFrame(win(91L, 0.0));
        assertThat(zero.outcome()).isEqualTo(Outcome.BURST);
    }

    @Test
    @DisplayName("CashoutBehavior rejects a target below 1.0, an inverted range and a negative delay")
    void behaviorValidation() {
        assertThatThrownBy(() -> new CashoutBehavior(0.9, 5.0, 500, 4500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CashoutBehavior(2.0, 1.5, 500, 4500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CashoutBehavior(1.1, 5.0, -1, 4500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CashoutBehavior(1.1, 5.0, 600, 500)).isInstanceOf(IllegalArgumentException.class);
    }
}
