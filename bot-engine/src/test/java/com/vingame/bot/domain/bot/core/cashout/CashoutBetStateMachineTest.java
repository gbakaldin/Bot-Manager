package com.vingame.bot.domain.bot.core.cashout;

import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.FrameAction;
import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.Outcome;
import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.Plan;
import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.TimeoutAction;
import com.vingame.bot.domain.bot.message.cashout.CashoutBetFrame;
import org.junit.jupiter.api.BeforeEach;
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
import static org.assertj.core.api.Assertions.within;

/**
 * CASHOUT_BOT Phase 2: {@link CashoutBetStateMachine} with a fake clock and a seeded
 * {@link Random}, no Spring and no network.
 */
@DisplayName("CashoutBetStateMachine")
class CashoutBetStateMachineTest {

    private static final long FRAME_TIMEOUT_MS = 20_000L;
    private static final long BACKOFF_MS = 30_000L;
    private static final int RECONNECT_AFTER = 3;
    private static final List<Long> STAKES = List.of(1_000L, 10_000L, 100_000L);
    private static final long RICH = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private CashoutBetStateMachine machine;

    @BeforeEach
    void setUp() {
        machine = newMachine(42L);
    }

    private CashoutBetStateMachine newMachine(long seed) {
        return new CashoutBetStateMachine(CashoutBehavior.LEGACY, FRAME_TIMEOUT_MS, BACKOFF_MS,
                RECONNECT_AFTER, now::get, new Random(seed));
    }

    /** A bet frame with exactly the fields the contract reads. */
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

    private static Frame burst(long sid) {
        return new Frame(sid, null, 4.0, 0.0, true, true);
    }

    private static Frame win(long sid, double crd) {
        return new Frame(sid, null, 2.5, crd, true, false);
    }

    /** Place a bet (advancing past any pause first) and return its plan. */
    private Plan place() {
        machine.nextBetAt().ifPresent(at -> now.set(Math.max(now.get(), at)));
        Optional<Plan> plan = machine.tryPlace(STAKES, RICH);
        assertThat(plan).as("a bet is placed from IDLE once the pause has passed").isPresent();
        return plan.get();
    }

    /** Place a bet and bind it to {@code sid} with one below-target progress frame. */
    private Plan placeAndBind(long sid) {
        Plan plan = place();
        assertThat(machine.onFrame(progress(sid, plan.amount(), 1.0)))
                .isInstanceOf(FrameAction.Progress.class);
        return plan;
    }

    @Nested
    @DisplayName("the target")
    class Target {

        @Test
        @DisplayName("is frozen: 50 frames below it send no cash-out and leave it unchanged")
        void frozen() {
            Plan plan = placeAndBind(1L);
            double target = plan.target();

            for (int i = 0; i < 50; i++) {
                double below = 1.0 + (target - 1.0) * i / 50.0;
                now.addAndGet(100);
                assertThat(machine.onFrame(progress(1L, plan.amount(), Math.min(below, target - 1e-9))))
                        .isInstanceOf(FrameAction.Progress.class);
            }

            assertThat(machine.currentPlan()).map(Plan::target).hasValue(target);
        }

        @Test
        @DisplayName("is uniform in [1.1, 5.0]: 10k draws, mean 3.05 ± 0.05")
        void uniform() {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            double sum = 0;
            int n = 10_000;
            for (int i = 0; i < n; i++) {
                Plan plan = place();
                min = Math.min(min, plan.target());
                max = Math.max(max, plan.target());
                sum += plan.target();
                machine.reset();
            }
            assertThat(min).isGreaterThanOrEqualTo(1.1);
            assertThat(max).isLessThanOrEqualTo(5.0);
            assertThat(sum / n).isCloseTo(3.05, within(0.05));
        }
    }

    @Nested
    @DisplayName("the cash-out")
    class Cashout {

        @Test
        @DisplayName("is sent for the first frame at or above the target, with the bound sid")
        void firstCrossing() {
            Plan plan = placeAndBind(7L);

            FrameAction action = machine.onFrame(progress(7L, plan.amount(), plan.target()));

            assertThat(action).isInstanceOf(FrameAction.SendCashout.class);
            assertThat(((FrameAction.SendCashout) action).sid()).isEqualTo(7L);
            assertThat(machine.onFrame(progress(7L, plan.amount(), plan.target() + 1)))
                    .as("CASHING keeps accepting progress and never cashes out twice")
                    .isInstanceOf(FrameAction.Progress.class);
        }

        @Test
        @DisplayName("a first frame already above target cashes out straight from PLACED")
        void fromPlaced() {
            Plan plan = place();

            assertThat(machine.onFrame(progress(8L, plan.amount(), plan.target() + 0.5)))
                    .isInstanceOf(FrameAction.SendCashout.class);
        }

        @Test
        @DisplayName("exactly one SEND_CASHOUT when 4 threads deliver crossing frames at once (200 rounds)")
        void exactlyOnceUnderConcurrency() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                for (int round = 0; round < 200; round++) {
                    machine = newMachine(round);
                    long sid = 1_000L + round;
                    Plan plan = placeAndBind(sid);
                    double crossing = plan.target() + 0.01;

                    CountDownLatch start = new CountDownLatch(1);
                    AtomicInteger cashouts = new AtomicInteger();
                    List<Future<?>> futures = new ArrayList<>();
                    for (int t = 0; t < 4; t++) {
                        futures.add(pool.submit(() -> {
                            start.await();
                            for (int i = 0; i < 25; i++) {
                                if (machine.onFrame(progress(sid, plan.amount(), crossing))
                                        instanceof FrameAction.SendCashout) {
                                    cashouts.incrementAndGet();
                                }
                            }
                            return null;
                        }));
                    }
                    start.countDown();
                    for (Future<?> f : futures) {
                        f.get(10, TimeUnit.SECONDS);
                    }

                    assertThat(cashouts.get()).as("round %d", round).isEqualTo(1);
                }
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("a final frame racing progress frames ends the bet exactly once, with at most one cash-out")
        void finalWinsTheRace() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                for (int round = 0; round < 200; round++) {
                    machine = newMachine(round);
                    long sid = 5_000L + round;
                    Plan plan = placeAndBind(sid);
                    CountDownLatch start = new CountDownLatch(1);
                    AtomicInteger ends = new AtomicInteger();
                    AtomicInteger cashouts = new AtomicInteger();
                    List<Future<?>> futures = new ArrayList<>();
                    for (int t = 0; t < 4; t++) {
                        boolean terminal = t == 0;
                        futures.add(pool.submit(() -> {
                            start.await();
                            for (int i = 0; i < 25; i++) {
                                FrameAction a = machine.onFrame(terminal
                                        ? burst(sid)
                                        : progress(sid, plan.amount(), plan.target() + 0.01));
                                if (a instanceof FrameAction.Ended) ends.incrementAndGet();
                                if (a instanceof FrameAction.SendCashout) cashouts.incrementAndGet();
                            }
                            return null;
                        }));
                    }
                    start.countDown();
                    for (Future<?> f : futures) {
                        f.get(10, TimeUnit.SECONDS);
                    }
                    assertThat(ends.get()).as("round %d ends", round).isEqualTo(1);
                    assertThat(cashouts.get()).as("round %d cash-outs", round).isLessThanOrEqualTo(1);
                    assertThat(machine.inFlight()).isFalse();
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("the end of a bet")
    class End {

        @Test
        @DisplayName("a burst ends the bet from PLACED (no progress at all)")
        void burstFromPlaced() {
            Plan plan = place();

            FrameAction action = machine.onFrame(burst(9L));

            assertThat(action).isInstanceOf(FrameAction.Ended.class);
            FrameAction.Ended ended = (FrameAction.Ended) action;
            assertThat(ended.outcome()).isEqualTo(Outcome.BURST);
            assertThat(ended.plan()).isEqualTo(plan);
            assertThat(ended.winnings()).isZero();
            assertThat(machine.inFlight()).isFalse();
        }

        @Test
        @DisplayName("a burst ends the bet from LIVE")
        void burstFromLive() {
            placeAndBind(10L);

            FrameAction action = machine.onFrame(burst(10L));

            assertThat(action).isInstanceOf(FrameAction.Ended.class);
            assertThat(((FrameAction.Ended) action).outcome()).isEqualTo(Outcome.BURST);
        }

        @Test
        @DisplayName("a burst ends the bet from CASHING (the late cash-out lost the race)")
        void burstFromCashing() {
            Plan plan = placeAndBind(11L);
            machine.onFrame(progress(11L, plan.amount(), plan.target()));

            FrameAction action = machine.onFrame(burst(11L));

            assertThat(((FrameAction.Ended) action).outcome()).isEqualTo(Outcome.BURST);
        }

        @Test
        @DisplayName("a final win ends the bet as CASHOUT with round(crd) winnings")
        void winFromCashing() {
            Plan plan = placeAndBind(12L);
            machine.onFrame(progress(12L, plan.amount(), plan.target()));

            FrameAction action = machine.onFrame(win(12L, 250_000.4));

            FrameAction.Ended ended = (FrameAction.Ended) action;
            assertThat(ended.outcome()).isEqualTo(Outcome.CASHOUT);
            assertThat(ended.winnings()).isEqualTo(250_000L);
            assertThat(ended.sid()).isEqualTo(12L);
        }

        @Test
        @DisplayName("a progress frame after the terminal is ignored and sends no cash-out")
        void progressAfterTerminal() {
            Plan plan = placeAndBind(13L);
            machine.onFrame(burst(13L));

            assertThat(machine.onFrame(progress(13L, plan.amount(), plan.target() + 1)))
                    .isSameAs(CashoutBetStateMachine.NONE);
        }

        @Test
        @DisplayName("nextBetAt is in [end + 500, end + 4500]; tryPlace before it places nothing")
        void pauseAfterEnd() {
            for (int i = 0; i < 500; i++) {
                placeAndBind(100L + i);
                long endedAt = now.get();
                machine.onFrame(burst(100L + i));

                long next = machine.nextBetAt().orElseThrow();
                assertThat(next).isBetween(endedAt + 500, endedAt + 4_500);
                now.set(next - 1);
                assertThat(machine.tryPlace(STAKES, RICH)).isEmpty();
                now.set(next);
            }
        }
    }

    @Nested
    @DisplayName("binding")
    class Binding {

        @Test
        @DisplayName("a frame whose sid recently ended does not bind the next bet")
        void recentlyEndedSidDoesNotBind() {
            placeAndBind(20L);
            machine.onFrame(burst(20L));
            Plan next = place();

            assertThat(machine.onFrame(progress(20L, next.amount(), 1.0))).isSameAs(CashoutBetStateMachine.NONE);
            assertThat(machine.boundSid()).isEmpty();
            assertThat(machine.onFrame(progress(21L, next.amount(), 1.0)))
                    .isInstanceOf(FrameAction.Progress.class);
            assertThat(machine.boundSid()).hasValue(21L);
        }

        @Test
        @DisplayName("a frame whose stake differs from the plan does not bind")
        void stakeMismatchDoesNotBind() {
            Plan plan = place();

            assertThat(machine.onFrame(progress(30L, plan.amount() + 1, 1.0))).isSameAs(CashoutBetStateMachine.NONE);
            assertThat(machine.boundSid()).isEmpty();
        }

        @Test
        @DisplayName("a frame for another sid is ignored once bound; any frame while IDLE is ignored")
        void otherSidAndIdleIgnored() {
            Plan plan = placeAndBind(40L);

            assertThat(machine.onFrame(progress(41L, plan.amount(), plan.target() + 1)))
                    .isSameAs(CashoutBetStateMachine.NONE);
            machine.onFrame(burst(40L));
            assertThat(machine.onFrame(progress(42L, plan.amount(), 1.0))).isSameAs(CashoutBetStateMachine.NONE);
        }
    }

    @Nested
    @DisplayName("stake selection")
    class Stake {

        @Test
        @DisplayName("draws only from the eligible stakes, and only one the balance covers")
        void eligibleAndAffordable() {
            for (int i = 0; i < 300; i++) {
                Optional<Plan> plan = machine.tryPlace(STAKES, 50_000L);
                if (plan.isPresent()) {
                    assertThat(plan.get().amount()).isIn(1_000L, 10_000L);
                    machine.reset();
                }
                now.addAndGet(5_000);
            }
            assertThat(machine.tryPlace(STAKES, 999L)).as("balance below every stake").isEmpty();
        }

        @Test
        @DisplayName("an empty stake set places nothing")
        void emptyStakes() {
            assertThat(machine.tryPlace(List.of(), RICH)).isEmpty();
            assertThat(machine.tryPlace(null, RICH)).isEmpty();
        }

        @Test
        @DisplayName("hits every eligible stake over many draws")
        void allStakesReachable() {
            List<Long> seen = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                seen.add(place().amount());
                machine.reset();
            }
            assertThat(seen).contains(1_000L, 10_000L, 100_000L);
        }

        @Test
        @DisplayName("tryPlace while a bet is in flight places nothing")
        void oneInFlight() {
            place();
            now.addAndGet(10_000);
            assertThat(machine.tryPlace(STAKES, RICH)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the timeout ladder")
    class Ladder {

        /** Place a bet, let it go silent for the frame timeout, and fire the watchdog. */
        private TimeoutAction.TimedOut timeOutOneBet() {
            place();
            now.addAndGet(FRAME_TIMEOUT_MS);
            TimeoutAction action = machine.onTimeout();
            assertThat(action).isInstanceOf(TimeoutAction.TimedOut.class);
            return (TimeoutAction.TimedOut) action;
        }

        @Test
        @DisplayName("reconnect fires at 3, 6, 12, 24, 48, 96, 192 and at no other count up to 200")
        void reconnectRungs() {
            List<Integer> rungs = new ArrayList<>();
            for (int i = 1; i <= 200; i++) {
                TimeoutAction.TimedOut t = timeOutOneBet();
                assertThat(t.consecutiveTimeouts()).isEqualTo(i);
                if (t.reconnect()) {
                    rungs.add(i);
                }
                if (i % 7 == 0) {
                    machine.reset(); // a reconnect does not reset the ladder
                }
            }
            assertThat(rungs).containsExactly(3, 6, 12, 24, 48, 96, 192);
        }

        @Test
        @DisplayName("every multiple of R·32 past the top rung reconnects")
        void steadyStateEveryR32() {
            assertThat(machine.isReconnectRung(288)).isTrue();
            assertThat(machine.isReconnectRung(384)).isTrue();
            assertThat(machine.isReconnectRung(300)).isFalse();
        }

        @Test
        @DisplayName("each timeout waits at least the backoff before the next bet")
        void backoff() {
            long at = now.get();
            TimeoutAction.TimedOut t = timeOutOneBet();

            assertThat(t.nextDelayMs()).isGreaterThanOrEqualTo(BACKOFF_MS);
            long timedOutAt = at + FRAME_TIMEOUT_MS;
            assertThat(machine.nextBetAt()).hasValue(timedOutAt + t.nextDelayMs());
        }

        @Test
        @DisplayName("after a timeout every bet is a probe at the cheapest eligible stake")
        void probe() {
            timeOutOneBet();
            for (int i = 0; i < 20; i++) {
                Plan plan = place();
                assertThat(plan.probe()).isTrue();
                assertThat(plan.amount()).isEqualTo(1_000L);
                machine.reset();
            }
        }

        @Test
        @DisplayName("the count survives reset() but a bound frame resets it")
        void resetSemantics() {
            timeOutOneBet();
            timeOutOneBet();
            machine.reset();
            assertThat(machine.consecutiveTimeouts()).isEqualTo(2);

            Plan plan = place();
            machine.onFrame(progress(77L, plan.amount(), 1.0));
            assertThat(machine.consecutiveTimeouts()).isZero();
            machine.onFrame(burst(77L));
            assertThat(place().probe()).isFalse();
        }

        @Test
        @DisplayName("a watchdog that raced a frame is a no-op; idle is a no-op")
        void staleWatchdog() {
            assertThat(machine.onTimeout()).isSameAs(CashoutBetStateMachine.NO_TIMEOUT);
            Plan plan = placeAndBind(88L);
            now.addAndGet(FRAME_TIMEOUT_MS - 1);
            machine.onFrame(progress(88L, plan.amount(), 1.01));
            now.addAndGet(FRAME_TIMEOUT_MS - 1);

            assertThat(machine.onTimeout()).isSameAs(CashoutBetStateMachine.NO_TIMEOUT);
            assertThat(machine.millisUntilTimeout()).isEqualTo(1L);
            assertThat(machine.inFlight()).isTrue();
        }

        @Test
        @DisplayName("late frames of a timed-out bet do not bind the next one")
        void timedOutSidRemembered() {
            Plan plan = placeAndBind(99L);
            now.addAndGet(FRAME_TIMEOUT_MS);
            machine.onTimeout();
            Plan next = place();

            assertThat(machine.onFrame(progress(99L, next.amount(), 1.0))).isSameAs(CashoutBetStateMachine.NONE);
            assertThat(plan).isNotNull();
        }
    }

    @Nested
    @DisplayName("reset()")
    class Reset {

        @Test
        @DisplayName("from IDLE, PLACED, LIVE and CASHING goes to IDLE and emits nothing afterwards")
        void fromEveryState() {
            machine.reset();
            assertThat(machine.inFlight()).isFalse();

            place();
            machine.reset();
            assertThat(machine.inFlight()).isFalse();

            Plan live = placeAndBind(51L);
            machine.reset();
            assertThat(machine.inFlight()).isFalse();
            assertThat(machine.onFrame(progress(51L, live.amount(), live.target() + 1)))
                    .isSameAs(CashoutBetStateMachine.NONE);

            Plan cashing = placeAndBind(52L);
            machine.onFrame(progress(52L, cashing.amount(), cashing.target()));
            machine.reset();
            assertThat(machine.inFlight()).isFalse();
            assertThat(machine.onFrame(win(52L, 1_000.0))).isSameAs(CashoutBetStateMachine.NONE);
            assertThat(machine.millisUntilTimeout()).isEqualTo(-1L);
        }

        @Test
        @DisplayName("sets a fresh pause in [500, 4500] ms")
        void freshPause() {
            long at = now.get();
            machine.reset();
            assertThat(machine.nextBetAt().orElseThrow()).isBetween(at + 500, at + 4_500);
        }

        @Test
        @DisplayName("placeIgnoringDelay places from IDLE inside the pause (the park-and-pop fallback)")
        void placeIgnoringDelay() {
            machine.reset();
            assertThat(machine.tryPlace(STAKES, RICH)).isEmpty();
            assertThat(machine.placeIgnoringDelay(STAKES, RICH)).isPresent();
            assertThat(machine.placeIgnoringDelay(STAKES, RICH)).as("not from PLACED").isEmpty();
        }
    }

    @Nested
    @DisplayName("review fixes (#1, #2, #3, #5)")
    class ReviewFixes {

        @Test
        @DisplayName("#1: reset() from CASHING remembers the bound sid too")
        void resetFromCashingRemembersSid() {
            Plan plan = placeAndBind(61L);
            machine.onFrame(progress(61L, plan.amount(), plan.target()));

            machine.reset();
            Plan next = place();

            assertThat(machine.onFrame(win(61L, 5_000.0))).isSameAs(CashoutBetStateMachine.NONE);
            assertThat(machine.onFrame(progress(61L, next.amount(), 1.0))).isSameAs(CashoutBetStateMachine.NONE);
            assertThat(machine.onFrame(progress(62L, next.amount(), 1.0))).isInstanceOf(FrameAction.Progress.class);
        }

        @Test
        @DisplayName("#2: a negative sid never binds either; a positive one still does")
        void nonPositiveSidNeverBinds() {
            Plan plan = place();

            assertThat(machine.onFrame(progress(-1L, plan.amount(), 1.0))).isSameAs(CashoutBetStateMachine.NONE);
            assertThat(machine.onFrame(burst(0L))).isSameAs(CashoutBetStateMachine.NONE);
            assertThat(machine.inFlight()).isTrue();
            assertThat(machine.onFrame(progress(1L, plan.amount(), 1.0))).isInstanceOf(FrameAction.Progress.class);
        }

        @Test
        @DisplayName("#3: the post-timeout pause is backoff + U[500, 4500] ms, and differs between bots")
        void timeoutBackoffIsJittered() {
            java.util.Set<Long> delays = new java.util.HashSet<>();
            for (long seed = 0; seed < 20; seed++) {
                machine = newMachine(seed);
                now.set(1_000_000L);
                place();
                now.addAndGet(FRAME_TIMEOUT_MS);
                TimeoutAction.TimedOut t = (TimeoutAction.TimedOut) machine.onTimeout();

                assertThat(t.nextDelayMs()).isBetween(BACKOFF_MS + 500, BACKOFF_MS + 4_500);
                delays.add(t.nextDelayMs());
            }
            assertThat(delays)
                    .as("bots that timed out at the same instant must not re-probe in lockstep")
                    .hasSizeGreaterThan(10);
        }

        @Test
        @DisplayName("#5: reset() from IDLE keeps a pending timeout backoff")
        void resetKeepsPendingBackoff() {
            place();
            now.addAndGet(FRAME_TIMEOUT_MS);
            machine.onTimeout();
            long backoffUntil = machine.nextBetAt().orElseThrow();

            now.addAndGet(1_000L); // a reconnect, then the subscribe reply
            machine.reset();

            assertThat(machine.nextBetAt()).hasValue(backoffUntil);
            now.set(backoffUntil - 1);
            assertThat(machine.tryPlace(STAKES, RICH)).isEmpty();
        }

        @Test
        @DisplayName("#5: reset() from IDLE with no pending pause still draws a fresh one")
        void resetFromIdleWithoutBackoffDrawsFresh() {
            now.addAndGet(60_000L);
            long at = now.get();

            machine.reset();

            assertThat(machine.nextBetAt().orElseThrow()).isBetween(at + 500, at + 4_500);
        }
    }

    @Test
    @DisplayName("CashoutBehavior.LEGACY is the legacy loop's numbers")
    void legacyBehavior() {
        assertThat(CashoutBehavior.LEGACY.minTarget()).isEqualTo(1.1);
        assertThat(CashoutBehavior.LEGACY.maxTarget()).isEqualTo(5.0);
        assertThat(CashoutBehavior.LEGACY.minDelayMs()).isEqualTo(500L);
        assertThat(CashoutBehavior.LEGACY.maxDelayMs()).isEqualTo(4_500L);
    }
}
