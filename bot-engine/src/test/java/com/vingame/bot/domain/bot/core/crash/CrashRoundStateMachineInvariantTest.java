package com.vingame.bot.domain.bot.core.crash;

import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Action;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Opened;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Outcome;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Phase;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Plan;
import com.vingame.bot.domain.bot.message.g4.win79.crash.Win79CrashTick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AVIATOR_BOT QA: {@link CrashRoundStateMachine} invariants under arbitrary event orders
 * (AD-8), not just the scripted paths of {@code CrashRoundStateMachineTest}. Seeded
 * random walks feed every entry point — round start/close/end for current, older, newer
 * and invalid sids, bet tasks, matching and mismatched acks, ticks with every combination
 * of crash flags and multipliers on both runners, resets — and check, per bet:
 * <ul>
 *   <li>at most one {@code Confirmed}, one {@code SendCashout} and one {@code Ended};</li>
 *   <li>no {@code SendCashout} from a tick whose own-runner crash flag is set, nor after a
 *       crash flag for the own runner was processed while the bet was LIVE, nor after the
 *       bet ended;</li>
 *   <li>a {@code SendCashout} only at or above target, on the plan's own runner;</li>
 *   <li>{@code Ended(CASHOUT)} only after this bet's {@code SendCashout};
 *       {@code Ended(UNACKED)} only without a {@code Confirmed}; {@code Ended(CRASH)} only
 *       with one; non-zero winnings only on {@code CASHOUT}.</li>
 * </ul>
 * Bets are tracked by {@link Plan} identity: the machine hands back the instance it froze.
 */
@DisplayName("CrashRoundStateMachine invariants (QA)")
class CrashRoundStateMachineInvariantTest {

    private static final int AVIATOR = 1700;
    private static final List<Long> LADDER = CrashStakes.ladder(10_000L, 200_000L, 5_000L);

    /** Per-bet bookkeeping. */
    private static final class Bet {
        int confirmed;
        int sendCashout;
        int ended;
        boolean liveCrashSeen;
        boolean endedSeen;
        long sid;
    }

    private static final class Walk {
        final AtomicLong now = new AtomicLong(1_000_000L);
        final Random rnd;
        final CrashRoundStateMachine m;
        final Map<Plan, Bet> bets = new IdentityHashMap<>();
        long sid = 100L;

        Walk(long seed, int runners) {
            rnd = new Random(seed);
            m = new CrashRoundStateMachine(CrashBehavior.LEGACY, runners, now::get, new Random(seed * 31 + 7));
        }

        Bet bet(Plan p) {
            Bet b = bets.get(p);
            assertThat(b).as("an action names a plan the machine never placed: %s", p).isNotNull();
            return b;
        }

        long pickSid() {
            return switch (rnd.nextInt(6)) {
                case 0 -> sid - 1;
                case 1 -> sid + 1;
                case 2 -> 0L;
                default -> sid;
            };
        }

        /** Mostly the plan's own runner, so acks bind often enough to reach LIVE / SETTLED. */
        int ackEid(Optional<Plan> plan) {
            return plan.isPresent() && rnd.nextInt(4) != 0 ? plan.get().eid() : 1 + rnd.nextInt(3);
        }

        double odd() {
            return Math.round((1.0 + rnd.nextDouble() * 5.0) * 100.0) / 100.0;
        }

        void step() {
            Phase before = m.phase();
            Optional<Plan> planBefore = m.currentPlan();
            switch (rnd.nextInt(20)) {
                case 0 -> {
                    long s = rnd.nextInt(8) == 0 ? sid : (rnd.nextInt(10) == 0 ? sid - 3 : sid + 1);
                    Optional<Opened> o = m.onRoundStart(s);
                    if (o.isPresent()) {
                        sid = s;
                        o.get().abandoned().ifPresent(e -> ended(e, planBefore.orElse(null)));
                    }
                }
                case 1, 2 -> {
                    now.addAndGet(rnd.nextInt(5_000));
                    long s = pickSid();
                    Optional<Plan> placed = m.tryPlace(s, LADDER, rnd.nextInt(4) == 0 ? 9_999L : 1_000_000L);
                    placed.ifPresent(p -> {
                        assertThat(before).isEqualTo(Phase.OPEN);
                        assertThat(LADDER).contains(p.amount());
                        assertThat(p.targetH()).isBetween(110L, 500L);
                        assertThat(p.eid()).isBetween(1, m.runnerCount());
                        assertThat(bets).doesNotContainKey(p);
                        Bet b = new Bet();
                        b.sid = s;
                        bets.put(p, b);
                    });
                }
                case 3, 4 -> {
                    int eid = ackEid(planBefore);
                    long stake = planBefore.isPresent() && rnd.nextInt(4) != 0 ? planBefore.get().amount() : 10_000L;
                    Action a = m.onBetAck(eid, stake);
                    if (a instanceof Action.Confirmed c) {
                        assertThat(before).isEqualTo(Phase.PLACED);
                        assertThat(c.plan().eid()).isEqualTo(eid);
                        assertThat(c.plan().amount()).isEqualTo(stake);
                        Bet b = bet(c.plan());
                        assertThat(b.endedSeen).isFalse();
                        b.confirmed++;
                    } else {
                        assertThat(a).isEqualTo(CrashRoundStateMachine.NONE);
                    }
                }
                case 5 -> m.onBettingClosed(pickSid());
                case 6, 7, 8, 9, 10, 11, 12, 13, 14 -> {
                    boolean jFi = rnd.nextInt(8) == 0;
                    boolean nFi = rnd.nextInt(8) == 0;
                    Win79CrashTick t = new Win79CrashTick(AVIATOR + 9, pickSid(), odd(), odd(), jFi, nFi);
                    Action a = m.onTick(t);
                    if (a instanceof Action.SendCashout sc) {
                        Bet b = bet(sc.plan());
                        int eid = sc.plan().eid();
                        assertThat(before).isEqualTo(Phase.LIVE);
                        assertThat(t.crashedFor(eid)).as("no cash-out off a crashed own runner").isFalse();
                        assertThat(b.liveCrashSeen).as("no cash-out after the own runner's crash flag").isFalse();
                        assertThat(b.endedSeen).as("no cash-out after the bet ended").isFalse();
                        assertThat(t.multiplierFor(eid)).isGreaterThanOrEqualTo(sc.plan().targetH());
                        assertThat(sc.multiplierH()).isEqualTo(t.multiplierFor(eid));
                        assertThat(sc.sid()).isEqualTo(t.sid()).isEqualTo(b.sid);
                        b.sendCashout++;
                    } else if (a instanceof Action.Ended e) {
                        assertThat(e.outcome()).isEqualTo(Outcome.CRASH);
                        assertThat(e.crashed()).isTrue();
                        assertThat(t.crashedFor(e.plan().eid())).isTrue();
                        ended(e, planBefore.orElse(null));
                    } else {
                        assertThat(a).isEqualTo(CrashRoundStateMachine.NONE);
                    }
                    // Record a LIVE-phase own-runner crash flag that the machine saw.
                    if (before == Phase.LIVE && planBefore.isPresent() && t.sid() == m.sid()
                            && t.crashedFor(planBefore.get().eid())) {
                        bets.get(planBefore.get()).liveCrashSeen = true;
                    }
                }
                case 15, 16 -> {
                    int eid = ackEid(planBefore);
                    long stake = planBefore.isPresent() && rnd.nextInt(4) != 0 ? planBefore.get().amount() : 10_000L;
                    long wm = 1 + rnd.nextInt(500_000);
                    Action a = m.onCashoutAck(eid, stake, wm);
                    if (a instanceof Action.Ended e) {
                        assertThat(before).isEqualTo(Phase.CASHING);
                        assertThat(e.outcome()).isEqualTo(Outcome.CASHOUT);
                        assertThat(e.winnings()).isEqualTo(wm);
                        ended(e, planBefore.orElse(null));
                    } else {
                        assertThat(a).isEqualTo(CrashRoundStateMachine.NONE);
                    }
                }
                case 17 -> {
                    Action a = m.onRoundEnd(pickSid());
                    if (a instanceof Action.Ended e) {
                        assertThat(e.outcome()).isNotEqualTo(Outcome.CASHOUT);
                        ended(e, planBefore.orElse(null));
                    }
                    if (!(a instanceof Action.None)) {
                        assertThat(m.phase()).isEqualTo(Phase.WAITING);
                        assertThat(m.inFlight()).isFalse();
                    }
                }
                default -> {
                    if (rnd.nextInt(4) == 0) {
                        m.reset();
                        assertThat(m.phase()).isEqualTo(Phase.WAITING);
                    }
                }
            }
            assertThat(m.inFlight()).isEqualTo(
                    m.phase() == Phase.PLACED || m.phase() == Phase.LIVE || m.phase() == Phase.CASHING);
        }

        void ended(Action.Ended e, Plan inFlightBefore) {
            Bet b = bet(e.plan());
            assertThat(e.plan()).as("an outcome is only for the bet that was in flight").isSameAs(inFlightBefore);
            assertThat(b.endedSeen).as("at most one outcome per bet").isFalse();
            b.endedSeen = true;
            b.ended++;
            assertThat(e.sid()).isEqualTo(b.sid);
            switch (e.outcome()) {
                case CASHOUT -> {
                    assertThat(b.sendCashout).as("CASHOUT only after this bet's cash-out").isEqualTo(1);
                    assertThat(e.winnings()).isPositive();
                }
                case UNACKED -> {
                    assertThat(b.confirmed).isZero();
                    assertThat(e.winnings()).isZero();
                }
                case CRASH -> {
                    assertThat(b.confirmed).isEqualTo(1);
                    assertThat(e.winnings()).isZero();
                }
            }
        }

        void checkTotals() {
            for (Bet b : bets.values()) {
                assertThat(b.confirmed).isLessThanOrEqualTo(1);
                assertThat(b.sendCashout).isLessThanOrEqualTo(1);
                assertThat(b.ended).isLessThanOrEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("400 seeded random walks x 1500 events, two runners: every per-bet invariant holds")
    void randomWalksTwoRunners() {
        int placed = 0;
        int cashouts = 0;
        int crashes = 0;
        int unacked = 0;
        for (long seed = 1; seed <= 400; seed++) {
            Walk w = new Walk(seed, 2);
            for (int i = 0; i < 1_500; i++) {
                w.step();
            }
            w.checkTotals();
            placed += w.bets.size();
            for (Bet b : w.bets.values()) {
                cashouts += b.sendCashout;
            }
            crashes += (int) w.bets.values().stream().filter(b -> b.ended == 1 && b.confirmed == 1 && b.sendCashout == 0).count();
            unacked += (int) w.bets.values().stream().filter(b -> b.ended == 1 && b.confirmed == 0).count();
        }
        // The walk must actually exercise the interesting paths, or it proves nothing.
        assertThat(placed).isGreaterThan(1_500);
        assertThat(cashouts).isGreaterThan(150);
        assertThat(crashes).isGreaterThan(150);
        assertThat(unacked).isGreaterThan(500);
    }

    @Test
    @DisplayName("single-runner walks: every plan is on runner 1 and the invariants hold")
    void randomWalksOneRunner() {
        for (long seed = 1_000; seed < 1_100; seed++) {
            Walk w = new Walk(seed, 1);
            for (int i = 0; i < 400; i++) {
                w.step();
            }
            w.checkTotals();
            assertThat(w.bets.keySet()).allSatisfy(p -> assertThat(p.eid()).isEqualTo(1));
        }
    }

    @Test
    @DisplayName("concurrent: crossing tick, own crash tick, cash-out ack and round end raced on 4 threads, 500 times — at most one outcome, CASHOUT only after the cash-out")
    void concurrentEndings() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 500; i++) {
                AtomicLong now = new AtomicLong(0L);
                CrashRoundStateMachine m = new CrashRoundStateMachine(
                        new CrashBehavior(2.0, 2.0, 0L, 0L), 2, now::get, new Random(i));
                long sid = 10L + i;
                m.onRoundStart(sid).orElseThrow();
                Plan plan = m.tryPlace(sid, List.of(10_000L), 1_000_000L).orElseThrow();
                assertThat(m.onBetAck(plan.eid(), 10_000L)).isInstanceOf(Action.Confirmed.class);
                int eid = plan.eid();
                boolean jFi = eid == 1;
                boolean nFi = eid == 2;
                Win79CrashTick crossing = new Win79CrashTick(AVIATOR + 9, sid, 2.5, 2.5, false, false);
                Win79CrashTick crash = new Win79CrashTick(AVIATOR + 9, sid, 2.6, 2.6, jFi, nFi);

                List<Action> actions = Collections.synchronizedList(new ArrayList<>());
                CountDownLatch go = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(4);
                List<Runnable> tasks = List.of(
                        () -> actions.add(m.onTick(crossing)),
                        () -> actions.add(m.onTick(crash)),
                        () -> {
                            actions.add(m.onCashoutAck(eid, 10_000L, 25_000L));
                            actions.add(m.onCashoutAck(eid, 10_000L, 25_000L));
                        },
                        () -> actions.add(m.onRoundEnd(sid)));
                for (Runnable t : tasks) {
                    pool.execute(() -> {
                        try {
                            go.await();
                            t.run();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                go.countDown();
                assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

                long ended = actions.stream().filter(a -> a instanceof Action.Ended).count();
                long sends = actions.stream().filter(a -> a instanceof Action.SendCashout).count();
                assertThat(ended).as("iteration %d: exactly one outcome (the round end guarantees one)", i).isEqualTo(1);
                assertThat(sends).isLessThanOrEqualTo(1);
                Action.Ended e = (Action.Ended) actions.stream().filter(a -> a instanceof Action.Ended).findFirst().orElseThrow();
                if (e.outcome() == Outcome.CASHOUT) {
                    assertThat(sends).as("CASHOUT requires the cash-out to have been decided").isEqualTo(1);
                    assertThat(e.winnings()).isEqualTo(25_000L);
                } else {
                    assertThat(e.outcome()).isEqualTo(Outcome.CRASH);
                }
                assertThat(m.phase()).isIn(Phase.WAITING, Phase.SETTLED);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a round start with sid <= 0 opens nothing and leaves a live bet untouched")
    void nonPositiveSidIgnored() {
        AtomicLong now = new AtomicLong(0L);
        CrashRoundStateMachine m = new CrashRoundStateMachine(CrashBehavior.LEGACY, 2, now::get, new Random(5));
        assertThat(m.onRoundStart(0L)).isEmpty();
        assertThat(m.onRoundStart(-7L)).isEmpty();
        assertThat(m.phase()).isEqualTo(Phase.WAITING);

        Opened o = m.onRoundStart(9L).orElseThrow();
        now.set(o.betAt());
        Plan p = m.tryPlace(9L, LADDER, 1_000_000L).orElseThrow();
        m.onBetAck(p.eid(), p.amount());
        assertThat(m.onRoundStart(0L)).isEmpty();
        assertThat(m.phase()).isEqualTo(Phase.LIVE);
        assertThat(m.currentPlan()).containsSame(p);
    }

    @Test
    @DisplayName("tryPlace never picks a stake above the balance, whatever the balance, over a seeded sweep")
    void stakeNeverAboveBalance() {
        AtomicLong now = new AtomicLong(0L);
        CrashRoundStateMachine m = new CrashRoundStateMachine(
                new CrashBehavior(1.1, 5.0, 0L, 0L), 2, now::get, new Random(99));
        Random balances = new Random(100);
        int placed = 0;
        for (long sid = 1; sid <= 5_000; sid++) {
            m.onRoundStart(sid).orElseThrow();
            long balance = balances.nextInt(250_000);
            Optional<Plan> p = m.tryPlace(sid, LADDER, balance);
            if (balance < 10_000L) {
                assertThat(p).isEmpty();
            } else {
                assertThat(p).isPresent();
                assertThat(p.get().amount()).isLessThanOrEqualTo(balance).isGreaterThanOrEqualTo(10_000L);
                placed++;
            }
            m.onRoundEnd(sid);
        }
        assertThat(placed).isGreaterThan(4_000);
    }
}
