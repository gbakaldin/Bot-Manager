package com.vingame.bot.domain.bot.core.crash;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Action;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Opened;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Outcome;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Phase;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Plan;
import com.vingame.bot.domain.bot.message.crash.CrashBetAck;
import com.vingame.bot.domain.bot.message.crash.CrashBettingClosed;
import com.vingame.bot.domain.bot.message.crash.CrashCashoutAck;
import com.vingame.bot.domain.bot.message.crash.CrashMessage;
import com.vingame.bot.domain.bot.message.crash.CrashRoundEnd;
import com.vingame.bot.domain.bot.message.crash.CrashRoundStart;
import com.vingame.bot.domain.bot.message.crash.CrashTick;
import com.vingame.bot.domain.bot.message.g4.win79.crash.Win79CrashMessageTypes;
import com.vingame.bot.domain.bot.message.g4.win79.crash.Win79CrashTick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * AVIATOR_BOT Phase 2: {@link CrashRoundStateMachine} with a fake clock and a scripted or
 * seeded {@link Random}, no Spring and no network (AD-8). Ticks are real
 * {@link Win79CrashTick}s, so the 119 runner mapping (eid 1 = Jake {@code jOdd/jFi}, eid 2
 * = Neytiri {@code nOdd/nFi}) is what the machine reads, not a test double of it.
 *
 * <p>The {@link TransitionTable} nest has one test per row of the AD-8 table.
 */
@DisplayName("CrashRoundStateMachine")
class CrashRoundStateMachineTest {

    private static final int AVIATOR = 1700;
    private static final int JAKE = 1;
    private static final int NEYTIRI = 2;
    private static final long RICH = 1_000_000_000L;

    /** Covers the captured stakes 10k / 50k / 100k: rung index = (amount - 10_000) / 5_000. */
    private static final List<Long> LADDER = CrashStakes.ladder(10_000L, 200_000L, 5_000L);

    private final AtomicLong now = new AtomicLong(1_791_199_000_000L);
    private final ScriptedRandom random = new ScriptedRandom(42L);
    private final CrashRoundStateMachine machine =
            new CrashRoundStateMachine(CrashBehavior.LEGACY, 2, now::get, random);

    // ------------------------------------------------------------------ helpers

    /**
     * A {@link Random} whose next draws can be scripted, falling back to a seeded stream.
     * The machine draws in this order: delay {@code nextLong}; then stake index
     * {@code nextInt}, target {@code nextDouble}, runner {@code nextInt}.
     */
    static final class ScriptedRandom extends Random {
        final Deque<Integer> ints = new ArrayDeque<>();
        final Deque<Double> doubles = new ArrayDeque<>();
        final Deque<Long> longs = new ArrayDeque<>();

        ScriptedRandom(long seed) {
            super(seed);
        }

        @Override
        public int nextInt(int bound) {
            Integer next = ints.pollFirst();
            return next != null ? next : super.nextInt(bound);
        }

        @Override
        public double nextDouble() {
            Double next = doubles.pollFirst();
            return next != null ? next : super.nextDouble();
        }

        @Override
        public long nextLong(long bound) {
            Long next = longs.pollFirst();
            return next != null ? next : super.nextLong(bound);
        }

        /** Script one placement: stake, target in hundredths (LEGACY range) and runner. */
        void plan(long amount, long targetH, int eid) {
            ints.addLast((int) ((amount - 10_000L) / 5_000L));
            doubles.addLast((targetH / 100.0 - 1.1) / 3.9);
            ints.addLast(eid - 1);
        }
    }

    private static Win79CrashTick tick(long sid, double jOdd, double nOdd, boolean jFi, boolean nFi) {
        return new Win79CrashTick(AVIATOR + 9, sid, jOdd, nOdd, jFi, nFi);
    }

    /** A tick of the shared curve before either runner crashed (F-1). */
    private static Win79CrashTick shared(long sid, double odd) {
        return tick(sid, odd, odd, false, false);
    }

    /** Open {@code sid} with a zero bet delay and place a scripted plan. */
    private Plan place(long sid, long amount, long targetH, int eid) {
        random.longs.addLast(0L);
        assertThat(machine.onRoundStart(sid)).isPresent();
        random.plan(amount, targetH, eid);
        Plan plan = machine.tryPlace(sid, LADDER, RICH).orElseThrow();
        assertThat(plan).isEqualTo(new Plan(amount, targetH, eid));
        return plan;
    }

    /** {@link #place} plus the matching ack: the machine is LIVE. */
    private Plan live(long sid, long amount, long targetH, int eid) {
        Plan plan = place(sid, amount, targetH, eid);
        assertThat(machine.onBetAck(eid, amount)).isEqualTo(new Action.Confirmed(plan, sid));
        assertThat(machine.phase()).isEqualTo(Phase.LIVE);
        return plan;
    }

    /** {@link #live} plus a crossing tick: the machine is CASHING. */
    private Plan cashing(long sid, long amount, long targetH, int eid) {
        Plan plan = live(sid, amount, targetH, eid);
        double odd = targetH / 100.0;
        assertThat(machine.onTick(shared(sid, odd))).isInstanceOf(Action.SendCashout.class);
        assertThat(machine.phase()).isEqualTo(Phase.CASHING);
        return plan;
    }

    private static Action.Ended crashEnded(Plan plan, long sid, long multiplierH) {
        return new Action.Ended(Outcome.CRASH, plan, sid, 0L, multiplierH, true);
    }

    // ------------------------------------------------------------------ capture replay

    /**
     * Replays the captured rounds 1638119 and 1638120
     * ({@code docs/captures/aviator-119-2026-10-05.jsonl} L15-L36) through the parsed 119
     * frames. The capture stores only a few ticks, so the climb between betting-closed and
     * the captured post-cash-out tick is filled with ticks of the shared curve at the
     * captured ~3.5% per tick (L4-L11: 1.43, 1.48, 1.53, …).
     */
    @Nested
    @DisplayName("capture replay")
    class CaptureReplay {

        private final ObjectMapper mapper = mapper();

        private static ObjectMapper mapper() {
            ObjectMapper m = new ObjectMapper();
            m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            m.registerSubtypes(new Win79CrashMessageTypes().getTypeRegistrations(AVIATOR));
            return m;
        }

        private CrashMessage frame(String json) throws Exception {
            return mapper.readValue(json, CrashMessage.class);
        }

        /** The shared curve from 1.00, ~3.5% a tick, up to {@code max}. */
        private List<Double> curve(double max) {
            List<Double> odds = new ArrayList<>();
            double odd = 1.00;
            while (odd <= max) {
                odds.add(odd);
                odd = Math.round(odd * 1.035 * 100.0) / 100.0;
            }
            return odds;
        }

        private record Round(long sid, Plan plan, List<Action> actions, int cashoutAt, int firstCrossing) {
        }

        /**
         * Start → place → ack → betting closed → climb → cash-out → ack → post-ack ticks → end.
         *
         * @param tail ticks after the cash-out ack, captured verbatim
         */
        private Round replay(String start, long amount, long targetH, int eid, String betAck,
                             String closed, String cashoutAck, List<String> tail, String end) throws Exception {
            List<Action> actions = new ArrayList<>();

            random.longs.addLast(18L); // the captured bet left ~18 ms after 1705 (L15 → L16)
            Opened opened = machine.onRoundStart(((CrashRoundStart) frame(start)).sid()).orElseThrow();
            assertThat(opened.abandoned()).isEmpty();
            long sid = opened.sid();
            assertThat(machine.tryPlace(sid, LADDER, RICH)).as("before betAt").isEmpty();
            now.addAndGet(opened.betDelayMs());
            random.plan(amount, targetH, eid);
            Plan plan = machine.tryPlace(sid, LADDER, RICH).orElseThrow();

            CrashBetAck ack = (CrashBetAck) frame(betAck);
            actions.add(machine.onBetAck(ack.eid(), ack.stake()));
            machine.onBettingClosed(((CrashBettingClosed) frame(closed)).sid());
            assertThat(machine.phase()).as("1706 does not touch a LIVE bet").isEqualTo(Phase.LIVE);

            List<Double> climb = curve(targetH / 100.0 + 0.3);
            int firstCrossing = -1;
            int cashoutAt = -1;
            for (int i = 0; i < climb.size(); i++) {
                long h = Math.round(climb.get(i) * 100.0);
                if (firstCrossing < 0 && h >= targetH) {
                    firstCrossing = i;
                }
                Action a = machine.onTick(shared(sid, climb.get(i)));
                if (a instanceof Action.SendCashout) {
                    cashoutAt = i;
                }
                actions.add(a);
                now.addAndGet(500L);
            }

            CrashCashoutAck cashAck = (CrashCashoutAck) frame(cashoutAck);
            actions.add(machine.onCashoutAck(cashAck.eid(), cashAck.stake(), cashAck.winningsFor(null)));
            for (String t : tail) {
                actions.add(machine.onTick((CrashTick) frame(t)));
            }
            actions.add(machine.onRoundEnd(((CrashRoundEnd) frame(end)).sid()));
            return new Round(sid, plan, actions, cashoutAt, firstCrossing);
        }

        @Test
        @DisplayName("round 1638119: Jake, 10,000 at 2.40 — one SendCashout on the first crossing tick, wm 24,000")
        void round1638119() throws Exception {
            Round r = replay(
                    "{\"eI\":{\"jp\":1812320},\"cmd\":1705,\"iOE\":true,\"sid\":1638119}",
                    10_000L, 240L, JAKE,
                    "{\"eid\":1,\"b\":10000,\"cmd\":1702}",
                    "{\"iUC\":100,\"cmd\":1706,\"sid\":1638119}",
                    "{\"eid\":1,\"b\":10000,\"wm\":24000,\"cmd\":1703,\"aid\":1,\"odd\":2.4}",
                    List.of("{\"nFi\":false,\"nOdd\":2.42,\"ps\":[],\"jOdd\":2.42,\"iJe\":false,\"cmd\":1709,"
                                    + "\"jFi\":false,\"sid\":1638119}",
                            "{\"nFi\":true,\"nOdd\":2.86,\"ps\":[],\"jOdd\":6,\"iJe\":true,\"cmd\":1709,"
                                    + "\"jFi\":false,\"sid\":1638119}"),
                    "{\"nOdd\":2.86,\"b\":10000,\"jOdd\":11.59,\"cmd\":1707,\"sid\":1638119}");

            assertThat(r.sid()).isEqualTo(1_638_119L);
            // the bet the capture sent at L16: {"b":10000,"sid":1638119,"eid":1}
            assertThat(r.plan()).isEqualTo(new Plan(10_000L, 240L, JAKE));
            assertThat(r.actions().get(0)).isEqualTo(new Action.Confirmed(r.plan(), 1_638_119L));
            assertThat(r.actions()).filteredOn(a -> a instanceof Action.SendCashout).hasSize(1);
            assertThat(r.cashoutAt()).isEqualTo(r.firstCrossing()).isPositive();
            Action.SendCashout send = (Action.SendCashout) r.actions().get(1 + r.cashoutAt());
            assertThat(send.sid()).isEqualTo(1_638_119L);
            assertThat(send.plan().eid()).isEqualTo(JAKE);
            assertThat(send.multiplierH()).isGreaterThanOrEqualTo(240L);

            List<Action> afterClimb = r.actions().subList(r.actions().size() - 4, r.actions().size());
            assertThat(afterClimb.get(0)).isEqualTo(
                    new Action.Ended(Outcome.CASHOUT, r.plan(), 1_638_119L, 24_000L,
                            CrashRoundStateMachine.UNKNOWN_MULTIPLIER, false));
            assertThat(afterClimb.get(1)).as("post-ack tick").isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(afterClimb.get(2)).as("crash after cash-out: no second outcome")
                    .isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(afterClimb.get(3)).isEqualTo(new Action.RoundClosed(1_638_119L, Optional.of(r.plan())));
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);
            assertThat(r.actions()).filteredOn(a -> a instanceof Action.Ended).hasSize(1);
        }

        @Test
        @DisplayName("round 1638120: Neytiri, 50,000 at 1.82 — cashes out before the 2.86 crash, wm 91,000")
        void round1638120() throws Exception {
            Round r = replay(
                    "{\"cmd\":1705,\"iOE\":true,\"sid\":1638120}",
                    50_000L, 182L, NEYTIRI,
                    "{\"eid\":2,\"b\":50000,\"cmd\":1702}",
                    "{\"iUC\":100,\"cmd\":1706,\"sid\":1638120}",
                    "{\"eid\":2,\"b\":50000,\"wm\":91000,\"cmd\":1703,\"aid\":1,\"odd\":1.82}",
                    List.of("{\"nFi\":false,\"nOdd\":1.83,\"ps\":[],\"jOdd\":1.83,\"iJe\":false,\"cmd\":1709,"
                                    + "\"jFi\":false,\"sid\":1638120}",
                            "{\"nFi\":true,\"nOdd\":2.86,\"ps\":[],\"jOdd\":6,\"iJe\":true,\"cmd\":1709,"
                                    + "\"jFi\":false,\"sid\":1638120}"),
                    "{\"nOdd\":2.86,\"b\":50000,\"jOdd\":11.59,\"cmd\":1707,\"sid\":1638120}");

            assertThat(r.plan()).isEqualTo(new Plan(50_000L, 182L, NEYTIRI));
            assertThat(r.actions()).filteredOn(a -> a instanceof Action.SendCashout).hasSize(1);
            assertThat(r.cashoutAt()).isEqualTo(r.firstCrossing());
            Action.Ended ended = (Action.Ended) r.actions().stream()
                    .filter(a -> a instanceof Action.Ended).findFirst().orElseThrow();
            assertThat(ended.outcome()).isEqualTo(Outcome.CASHOUT);
            assertThat(ended.winnings()).isEqualTo(91_000L);
            assertThat(r.actions().get(r.actions().size() - 1))
                    .isEqualTo(new Action.RoundClosed(1_638_120L, Optional.of(r.plan())));
        }

        @Test
        @DisplayName("both rounds back to back on one machine: two cash-outs, nothing abandoned between them")
        void backToBack() throws Exception {
            round1638119();
            assertThat(machine.sid()).isEqualTo(1_638_119L);
            round1638120();
            assertThat(machine.sid()).isEqualTo(1_638_120L);
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);
        }
    }

    // ------------------------------------------------------------------ F-1 and runners

    @Nested
    @DisplayName("F-1: the crashed flag on the bet's own runner")
    class CrashedFlag {

        @Test
        @DisplayName("eid 2 at 2.85: tick 2.80 then {nFi:true, nOdd:2.86} → Ended(CRASH), no SendCashout")
        void frozenValueAboveTargetNeverCashesOut() {
            Plan plan = live(7L, 20_000L, 285L, NEYTIRI);
            assertThat(machine.onTick(shared(7L, 2.80))).isEqualTo(CrashRoundStateMachine.NONE);
            Action a = machine.onTick(tick(7L, 2.90, 2.86, false, true));
            assertThat(a).isEqualTo(crashEnded(plan, 7L, 286L));
            assertThat(machine.phase()).isEqualTo(Phase.SETTLED);
            // and nothing afterwards, however high either runner reads
            assertThat(machine.onTick(tick(7L, 9.0, 2.86, false, true))).isEqualTo(CrashRoundStateMachine.NONE);
        }

        @Test
        @DisplayName("an eid-1 plan reads jOdd only: nOdd above target does nothing, jOdd crossing cashes out")
        void jakeReadsJOddOnly() {
            Plan plan = live(8L, 20_000L, 200L, JAKE);
            assertThat(machine.onTick(tick(8L, 1.90, 2.50, false, false))).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onTick(tick(8L, 1.95, 2.50, false, true))).as("Neytiri crashed: not ours")
                    .isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onTick(tick(8L, 2.00, 2.86, false, true)))
                    .isEqualTo(new Action.SendCashout(plan, 8L, 200L));
        }

        @Test
        @DisplayName("an eid-2 plan at 3.0 crashes on the nFi tick even while jOdd climbs past 3.0 (legacy bug unrepresentable)")
        void legacyEitherRunnerBugIsUnrepresentable() {
            Plan plan = live(9L, 20_000L, 300L, NEYTIRI);
            assertThat(machine.onTick(shared(9L, 2.80))).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onTick(tick(9L, 2.90, 2.86, false, true))).isEqualTo(crashEnded(plan, 9L, 286L));
            for (double j = 3.0; j <= 11.59; j += 0.25) {
                assertThat(machine.onTick(tick(9L, j, 2.86, false, true))).isEqualTo(CrashRoundStateMachine.NONE);
            }
            assertThat(machine.onRoundEnd(9L)).isEqualTo(new Action.RoundClosed(9L, Optional.of(plan)));
        }

        @Test
        @DisplayName("an eid-1 plan crashes on jFi; nFi is irrelevant to it")
        void jakeCrashesOnJFi() {
            Plan plan = live(10L, 20_000L, 500L, JAKE);
            assertThat(machine.onTick(tick(10L, 4.0, 2.86, false, true))).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onTick(tick(10L, 4.1, 2.86, true, true))).isEqualTo(crashEnded(plan, 10L, 410L));
        }

        @Test
        @DisplayName("exactly at target (multiplier == targetH) cashes out")
        void exactlyAtTarget() {
            Plan plan = live(11L, 20_000L, 285L, NEYTIRI);
            assertThat(machine.onTick(shared(11L, 2.85))).isEqualTo(new Action.SendCashout(plan, 11L, 285L));
        }
    }

    @Nested
    @DisplayName("target frozen")
    class TargetFrozen {

        @Test
        @DisplayName("100 ticks below target emit nothing and leave the plan identical")
        void hundredTicksBelowTarget() {
            live(12L, 20_000L, 400L, JAKE);
            Plan before = machine.currentPlan().orElseThrow();
            for (int i = 0; i < 100; i++) {
                double odd = 1.00 + (i % 299) / 100.0; // 1.00 .. 1.99, below 4.00
                assertThat(machine.onTick(shared(12L, odd))).isEqualTo(CrashRoundStateMachine.NONE);
            }
            assertThat(machine.currentPlan()).contains(before);
            assertThat(machine.phase()).isEqualTo(Phase.LIVE);
        }
    }

    // ------------------------------------------------------------------ races

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        private List<Action> race(CrashRoundStateMachine m, List<CrashTick> ticks) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(ticks.size());
            try {
                CountDownLatch ready = new CountDownLatch(ticks.size());
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Action>> futures = new ArrayList<>();
                for (CrashTick t : ticks) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return m.onTick(t);
                    }));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                List<Action> out = new ArrayList<>();
                for (Future<Action> f : futures) {
                    out.add(f.get(5, TimeUnit.SECONDS));
                }
                return out;
            } finally {
                pool.shutdownNow();
            }
        }

        private CrashRoundStateMachine liveMachine(long sid, int eid, long targetH) {
            ScriptedRandom r = new ScriptedRandom(1L);
            CrashRoundStateMachine m = new CrashRoundStateMachine(CrashBehavior.LEGACY, 2, now::get, r);
            r.longs.addLast(0L);
            m.onRoundStart(sid).orElseThrow();
            r.plan(20_000L, targetH, eid);
            m.tryPlace(sid, LADDER, RICH).orElseThrow();
            assertThat(m.onBetAck(eid, 20_000L)).isInstanceOf(Action.Confirmed.class);
            return m;
        }

        @Test
        @DisplayName("4 threads on the crossing tick → exactly one SendCashout (200 iterations)")
        void fourThreadsCrossing() throws Exception {
            for (int i = 0; i < 200; i++) {
                CrashRoundStateMachine m = liveMachine(20L, NEYTIRI, 250L);
                CrashTick crossing = shared(20L, 2.50);
                List<Action> out = race(m, List.of(crossing, crossing, crossing, crossing));
                assertThat(out).filteredOn(a -> a instanceof Action.SendCashout).hasSize(1);
                assertThat(out).filteredOn(a -> a == CrashRoundStateMachine.NONE).hasSize(3);
            }
        }

        @Test
        @DisplayName("crossing tick vs crash tick concurrently → exactly one of {SendCashout, Ended(CRASH)} (200 iterations)")
        void crossingVersusCrash() throws Exception {
            for (int i = 0; i < 200; i++) {
                CrashRoundStateMachine m = liveMachine(21L, NEYTIRI, 280L);
                List<Action> out = race(m, List.of(shared(21L, 2.80), tick(21L, 2.90, 2.86, false, true)));
                long sends = out.stream().filter(a -> a instanceof Action.SendCashout).count();
                long crashes = out.stream().filter(a -> a instanceof Action.Ended e && e.outcome() == Outcome.CRASH).count();
                assertThat(sends + crashes).isEqualTo(1);
                assertThat(m.phase()).isIn(Phase.CASHING, Phase.SETTLED);
            }
        }

        @Test
        @DisplayName("a crash tick handled before a stale pre-crash crossing tick: Ended(CRASH), then None")
        void staleCrossingAfterCrash() {
            Plan plan = live(22L, 20_000L, 250L, NEYTIRI);
            assertThat(machine.onTick(tick(22L, 2.90, 2.86, false, true))).isEqualTo(crashEnded(plan, 22L, 286L));
            assertThat(machine.onTick(shared(22L, 2.60))).isEqualTo(CrashRoundStateMachine.NONE);
        }
    }

    // ------------------------------------------------------------------ acks

    @Nested
    @DisplayName("acks bind only in their pending state, on (eid, stake)")
    class Acks {

        @Test
        @DisplayName("bet ack with the wrong eid or stake does not bind")
        void betAckMismatch() {
            place(30L, 20_000L, 300L, JAKE);
            assertThat(machine.onBetAck(NEYTIRI, 20_000L)).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onBetAck(JAKE, 25_000L)).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.phase()).isEqualTo(Phase.PLACED);
        }

        @Test
        @DisplayName("bet ack in the wrong state (WAITING, OPEN, LIVE) does not bind")
        void betAckWrongState() {
            assertThat(machine.onBetAck(JAKE, 20_000L)).isEqualTo(CrashRoundStateMachine.NONE);
            random.longs.addLast(1_000L);
            machine.onRoundStart(31L);
            assertThat(machine.onBetAck(JAKE, 20_000L)).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.phase()).isEqualTo(Phase.OPEN);
            machine.reset();
            live(32L, 20_000L, 300L, JAKE);
            assertThat(machine.onBetAck(JAKE, 20_000L)).as("duplicate ack").isEqualTo(CrashRoundStateMachine.NONE);
        }

        @Test
        @DisplayName("cash-out ack with the wrong eid or stake does not bind")
        void cashoutAckMismatch() {
            cashing(33L, 20_000L, 200L, NEYTIRI);
            assertThat(machine.onCashoutAck(JAKE, 20_000L, 40_000L)).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onCashoutAck(NEYTIRI, 10_000L, 40_000L)).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.phase()).isEqualTo(Phase.CASHING);
        }

        @Test
        @DisplayName("cash-out ack in LIVE, PLACED or SETTLED does not bind")
        void cashoutAckWrongState() {
            place(34L, 20_000L, 300L, JAKE);
            assertThat(machine.onCashoutAck(JAKE, 20_000L, 1L)).isEqualTo(CrashRoundStateMachine.NONE);
            machine.onBetAck(JAKE, 20_000L);
            assertThat(machine.onCashoutAck(JAKE, 20_000L, 1L)).isEqualTo(CrashRoundStateMachine.NONE);
            machine.onTick(shared(34L, 3.0));
            assertThat(machine.onCashoutAck(JAKE, 20_000L, 60_000L)).isInstanceOf(Action.Ended.class);
            assertThat(machine.onCashoutAck(JAKE, 20_000L, 60_000L)).as("duplicate")
                    .isEqualTo(CrashRoundStateMachine.NONE);
        }
    }

    // ------------------------------------------------------------------ placing

    @Nested
    @DisplayName("tryPlace")
    class TryPlace {

        @Test
        @DisplayName("empty before betAt, then succeeds at betAt")
        void beforeBetAt() {
            random.longs.addLast(3_000L);
            Opened opened = machine.onRoundStart(40L).orElseThrow();
            assertThat(opened.betDelayMs()).isEqualTo(3_000L);
            assertThat(opened.betAt()).isEqualTo(now.get() + 3_000L);
            now.addAndGet(2_999L);
            assertThat(machine.tryPlace(40L, LADDER, RICH)).isEmpty();
            now.addAndGet(1L);
            assertThat(machine.tryPlace(40L, LADDER, RICH)).isPresent();
        }

        @Test
        @DisplayName("empty after onBettingClosed, for an old sid, and after reset")
        void closedOldReset() {
            random.longs.addLast(0L);
            machine.onRoundStart(41L);
            machine.onBettingClosed(41L);
            assertThat(machine.tryPlace(41L, LADDER, RICH)).as("after 1706").isEmpty();

            random.longs.addLast(0L);
            machine.onRoundStart(42L);
            assertThat(machine.tryPlace(41L, LADDER, RICH)).as("old sid").isEmpty();
            machine.reset();
            assertThat(machine.tryPlace(42L, LADDER, RICH)).as("after reset").isEmpty();
        }

        @Test
        @DisplayName("empty with an empty ladder or a balance below the cheapest rung")
        void unaffordable() {
            random.longs.addLast(0L);
            machine.onRoundStart(43L);
            assertThat(machine.tryPlace(43L, List.of(), RICH)).isEmpty();
            assertThat(machine.tryPlace(43L, LADDER, 9_999L)).isEmpty();
            assertThat(machine.phase()).isEqualTo(Phase.OPEN);
            assertThat(machine.tryPlace(43L, LADDER, 10_000L)).contains(machine.currentPlan().orElseThrow());
            assertThat(machine.currentPlan().orElseThrow().amount()).isEqualTo(10_000L);
        }

        @Test
        @DisplayName("a second tryPlace in the same round does nothing")
        void once() {
            place(44L, 20_000L, 300L, JAKE);
            assertThat(machine.tryPlace(44L, LADDER, RICH)).isEmpty();
        }

        @Test
        @DisplayName("betAt - roundStart is in [0, 4500] (LEGACY) over 10k rounds")
        void delayRange() {
            CrashRoundStateMachine m = new CrashRoundStateMachine(CrashBehavior.LEGACY, 2, now::get, new Random(3));
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            for (long sid = 1; sid <= 10_000; sid++) {
                long start = now.get();
                Opened o = m.onRoundStart(sid).orElseThrow();
                assertThat(o.betAt() - start).isEqualTo(o.betDelayMs()).isBetween(0L, 4_500L);
                min = Math.min(min, o.betDelayMs());
                max = Math.max(max, o.betDelayMs());
            }
            assertThat(min).isLessThan(50L);
            assertThat(max).isGreaterThan(4_450L);
        }

        @Test
        @DisplayName("affordableCount is the prefix <= balance, by binary search over a computed ladder")
        void affordableCount() {
            assertThat(CrashRoundStateMachine.affordableCount(LADDER, 9_999L)).isZero();
            assertThat(CrashRoundStateMachine.affordableCount(LADDER, 10_000L)).isEqualTo(1);
            assertThat(CrashRoundStateMachine.affordableCount(LADDER, 54_999L)).isEqualTo(9);
            assertThat(CrashRoundStateMachine.affordableCount(LADDER, RICH)).isEqualTo(LADDER.size());
            assertThat(CrashRoundStateMachine.affordableCount(null, RICH)).isZero();
            List<Long> huge = CrashStakes.ladder(1L, 1_000_000_000_000L, 1L);
            assertThat(CrashRoundStateMachine.affordableCount(huge, 1_234_567_890L)).isEqualTo(1_234_567_890);
        }
    }

    // ------------------------------------------------------------------ draws

    @Nested
    @DisplayName("draws")
    class Draws {

        private List<Plan> plans(int runnerCount, List<Long> ladder, long balance, int n) {
            CrashRoundStateMachine m = new CrashRoundStateMachine(
                    new CrashBehavior(1.1, 5.0, 0L, 0L), runnerCount, now::get, new Random(2026));
            List<Plan> out = new ArrayList<>(n);
            for (long sid = 1; sid <= n; sid++) {
                m.onRoundStart(sid).orElseThrow();
                out.add(m.tryPlace(sid, ladder, balance).orElseThrow());
            }
            return out;
        }

        @Test
        @DisplayName("eid is 50% ± 2% over 10k plans with runnerCount 2")
        void eidTwoRunners() {
            List<Plan> p = plans(2, LADDER, RICH, 10_000);
            long jake = p.stream().filter(x -> x.eid() == JAKE).count();
            assertThat(p).allMatch(x -> x.eid() == JAKE || x.eid() == NEYTIRI);
            assertThat(jake / 10_000.0).isCloseTo(0.5, within(0.02));
        }

        @Test
        @DisplayName("eid is always 1 with runnerCount 1")
        void eidOneRunner() {
            assertThat(plans(1, LADDER, RICH, 2_000)).allMatch(x -> x.eid() == 1);
        }

        @Test
        @DisplayName("targetH is in [110, 500] with mean ≈ 305 ± 3")
        void target() {
            List<Plan> p = plans(2, LADDER, RICH, 10_000);
            assertThat(p).allMatch(x -> x.targetH() >= 110 && x.targetH() <= 500);
            double mean = p.stream().mapToLong(Plan::targetH).average().orElseThrow();
            assertThat(mean).isCloseTo(305.0, within(3.0));
            assertThat(p.stream().mapToLong(Plan::targetH).min().orElseThrow()).isLessThan(115);
            assertThat(p.stream().mapToLong(Plan::targetH).max().orElseThrow()).isGreaterThan(495);
        }

        @Test
        @DisplayName("amount only from the affordable ladder, uniform over it")
        void amountUniformAffordable() {
            long balance = 54_999L; // 10k..50k affordable: 9 rungs
            List<Plan> p = plans(2, LADDER, balance, 9_000);
            assertThat(p).allMatch(x -> LADDER.contains(x.amount()) && x.amount() <= balance);
            for (long rung = 10_000L; rung <= 50_000L; rung += 5_000L) {
                long r = rung;
                long count = p.stream().filter(x -> x.amount() == r).count();
                assertThat(count).as("rung %d", rung).isBetween(850L, 1_150L); // expected 1000
            }
        }
    }

    // ------------------------------------------------------------------ AD-8 table

    @Nested
    @DisplayName("AD-8 transition table, row by row")
    class TransitionTable {

        @Test
        @DisplayName("WAITING --onRoundStart(sid)--> OPEN(sid, betAt = now + U(0,4500))")
        void waitingToOpen() {
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);
            random.longs.addLast(1_234L);
            long start = now.get();
            Opened o = machine.onRoundStart(50L).orElseThrow();
            assertThat(o).isEqualTo(new Opened(50L, start + 1_234L, 1_234L, Optional.empty()));
            assertThat(machine.phase()).isEqualTo(Phase.OPEN);
            assertThat(machine.sid()).isEqualTo(50L);
            assertThat(machine.betAt()).hasValue(start + 1_234L);
            assertThat(machine.inFlight()).isFalse();
        }

        @Test
        @DisplayName("OPEN(sid) --tryPlace--> PLACED(sid, plan)")
        void openToPlaced() {
            Plan plan = place(51L, 30_000L, 222L, NEYTIRI);
            assertThat(machine.phase()).isEqualTo(Phase.PLACED);
            assertThat(machine.currentPlan()).contains(plan);
            assertThat(machine.inFlight()).isTrue();
        }

        @Test
        @DisplayName("OPEN --onBettingClosed(sid)--> WAITING [bet window missed]")
        void openToWaitingOnClose() {
            random.longs.addLast(4_500L);
            machine.onRoundStart(52L);
            machine.onBettingClosed(52L);
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);
            assertThat(machine.sid()).isEqualTo(52L);
            assertThat(machine.onRoundEnd(52L)).isEqualTo(new Action.RoundClosed(52L, Optional.empty()));
        }

        @Test
        @DisplayName("PLACED --onBetAck(eid==plan.eid && b==plan.amount)--> LIVE [Confirmed(plan)]")
        void placedToLive() {
            Plan plan = place(53L, 40_000L, 300L, JAKE);
            assertThat(machine.onBetAck(JAKE, 40_000L)).isEqualTo(new Action.Confirmed(plan, 53L));
            assertThat(machine.phase()).isEqualTo(Phase.LIVE);
        }

        @Test
        @DisplayName("LIVE --onTick(sid): crashedFor(eid)--> SETTLED [Ended(CRASH)]")
        void liveToSettledOnCrash() {
            Plan plan = live(54L, 20_000L, 300L, NEYTIRI);
            assertThat(machine.onTick(tick(54L, 2.0, 1.5, false, true))).isEqualTo(crashEnded(plan, 54L, 150L));
            assertThat(machine.phase()).isEqualTo(Phase.SETTLED);
            assertThat(machine.inFlight()).isFalse();
            assertThat(machine.currentPlan()).contains(plan);
        }

        @Test
        @DisplayName("LIVE --onTick(sid): multiplierFor(eid) >= targetH--> CASHING [SendCashout] exactly once")
        void liveToCashing() {
            Plan plan = live(55L, 20_000L, 150L, JAKE);
            assertThat(machine.onTick(shared(55L, 1.49))).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onTick(shared(55L, 1.53))).isEqualTo(new Action.SendCashout(plan, 55L, 153L));
            assertThat(machine.phase()).isEqualTo(Phase.CASHING);
            assertThat(machine.onTick(shared(55L, 1.60))).isEqualTo(CrashRoundStateMachine.NONE);
        }

        @Test
        @DisplayName("CASHING --onTick--> CASHING (crash flag ignored: the server decides)")
        void cashingIgnoresTicks() {
            cashing(56L, 20_000L, 200L, NEYTIRI);
            assertThat(machine.onTick(shared(56L, 2.2))).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onTick(tick(56L, 2.3, 2.25, false, true))).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.phase()).isEqualTo(Phase.CASHING);
        }

        @Test
        @DisplayName("CASHING --onCashoutAck(eid, b match)--> SETTLED [Ended(CASHOUT, winnings=wm)]")
        void cashingToSettled() {
            Plan plan = cashing(57L, 20_000L, 200L, NEYTIRI);
            assertThat(machine.onCashoutAck(NEYTIRI, 20_000L, 40_000L)).isEqualTo(
                    new Action.Ended(Outcome.CASHOUT, plan, 57L, 40_000L, CrashRoundStateMachine.UNKNOWN_MULTIPLIER, false));
            assertThat(machine.phase()).isEqualTo(Phase.SETTLED);
        }

        @Test
        @DisplayName("CASHING ack after the crash flag (server accepted the late cash-out) still settles CASHOUT")
        void cashingAckAfterCrashFlag() {
            Plan plan = cashing(58L, 20_000L, 280L, NEYTIRI);
            machine.onTick(tick(58L, 2.9, 2.86, false, true));
            Action a = machine.onCashoutAck(NEYTIRI, 20_000L, 56_000L);
            assertThat(a).isInstanceOf(Action.Ended.class);
            assertThat(((Action.Ended) a).outcome()).isEqualTo(Outcome.CASHOUT);
            assertThat(((Action.Ended) a).plan()).isEqualTo(plan);
        }

        @Test
        @DisplayName("PLACED --onRoundEnd--> WAITING [Ended(UNACKED)]")
        void placedRoundEnd() {
            Plan plan = place(59L, 20_000L, 300L, JAKE);
            assertThat(machine.onRoundEnd(59L)).isEqualTo(
                    new Action.Ended(Outcome.UNACKED, plan, 59L, 0L, CrashRoundStateMachine.UNKNOWN_MULTIPLIER, false));
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);
        }

        @Test
        @DisplayName("LIVE --onRoundEnd--> WAITING [Ended(CRASH)]")
        void liveRoundEnd() {
            Plan plan = live(60L, 20_000L, 300L, JAKE);
            assertThat(machine.onRoundEnd(60L)).isEqualTo(
                    new Action.Ended(Outcome.CRASH, plan, 60L, 0L, CrashRoundStateMachine.UNKNOWN_MULTIPLIER, false));
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);
        }

        @Test
        @DisplayName("CASHING --onRoundEnd--> CLOSING [RoundClosed], no outcome yet (review B2)")
        void cashingRoundEnd() {
            Plan plan = cashing(61L, 20_000L, 200L, JAKE);
            assertThat(machine.onRoundEnd(61L)).isEqualTo(new Action.RoundClosed(61L, Optional.empty()));
            assertThat(machine.phase()).isEqualTo(Phase.CLOSING);
            assertThat(machine.inFlight()).isTrue();
            assertThat(machine.currentPlan()).contains(plan);
        }

        @Test
        @DisplayName("CLOSING --onCashoutAck(match)--> SETTLED [Ended(CASHOUT)]: an ack handled after its 1707 still pays (review B2)")
        void closingCashoutAck() {
            Plan plan = cashing(64L, 20_000L, 200L, JAKE);
            machine.onRoundEnd(64L);
            assertThat(machine.onCashoutAck(NEYTIRI, 20_000L, 40_000L)).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onCashoutAck(JAKE, 10_000L, 40_000L)).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onCashoutAck(JAKE, 20_000L, 40_000L)).isEqualTo(
                    new Action.Ended(Outcome.CASHOUT, plan, 64L, 40_000L, CrashRoundStateMachine.UNKNOWN_MULTIPLIER, false));
            assertThat(machine.phase()).isEqualTo(Phase.SETTLED);
            assertThat(machine.onCashoutAck(JAKE, 20_000L, 40_000L)).isEqualTo(CrashRoundStateMachine.NONE);
        }

        @Test
        @DisplayName("CLOSING with no ack --onRoundStart(next)--> OPEN, abandoned Ended(CRASH) (the cash-out was refused)")
        void closingResolvedByNextRoundStart() {
            Plan plan = cashing(65L, 20_000L, 200L, JAKE);
            machine.onRoundEnd(65L);
            random.longs.addLast(0L);
            Opened opened = machine.onRoundStart(66L).orElseThrow();
            assertThat(opened.abandoned()).contains(
                    new Action.Ended(Outcome.CRASH, plan, 65L, 0L, CrashRoundStateMachine.UNKNOWN_MULTIPLIER, false));
            assertThat(machine.phase()).isEqualTo(Phase.OPEN);
            assertThat(machine.onCashoutAck(JAKE, 20_000L, 40_000L)).isEqualTo(CrashRoundStateMachine.NONE);
        }

        @Test
        @DisplayName("CLOSING: a repeated 1707 for its round stays CLOSING; a newer 1707 resolves Ended(CRASH); ticks are ignored")
        void closingRoundEnds() {
            Plan plan = cashing(67L, 20_000L, 200L, JAKE);
            machine.onRoundEnd(67L);
            assertThat(machine.onTick(shared(67L, 9.0))).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onRoundEnd(66L)).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onRoundEnd(67L)).isEqualTo(new Action.RoundClosed(67L, Optional.empty()));
            assertThat(machine.phase()).isEqualTo(Phase.CLOSING);
            assertThat(machine.onRoundEnd(68L)).isEqualTo(
                    new Action.Ended(Outcome.CRASH, plan, 67L, 0L, CrashRoundStateMachine.UNKNOWN_MULTIPLIER, false));
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);
            assertThat(machine.sid()).isEqualTo(68L);
        }

        @Test
        @DisplayName("CLOSING --reset()--> WAITING with no outcome; a late ack then binds nothing")
        void closingReset() {
            cashing(69L, 20_000L, 200L, JAKE);
            machine.onRoundEnd(69L);
            machine.reset();
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);
            assertThat(machine.inFlight()).isFalse();
            assertThat(machine.onCashoutAck(JAKE, 20_000L, 40_000L)).isEqualTo(CrashRoundStateMachine.NONE);
        }

        @Test
        @DisplayName("SETTLED --onRoundEnd--> WAITING [RoundClosed], no second outcome")
        void settledRoundEnd() {
            Plan plan = live(62L, 20_000L, 300L, NEYTIRI);
            machine.onTick(tick(62L, 2.0, 1.5, false, true));
            assertThat(machine.onRoundEnd(62L)).isEqualTo(new Action.RoundClosed(62L, Optional.of(plan)));
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);
            assertThat(machine.currentPlan()).isEmpty();
        }

        @Test
        @DisplayName("OPEN --onRoundEnd--> WAITING [RoundClosed]")
        void openRoundEnd() {
            random.longs.addLast(0L);
            machine.onRoundStart(63L);
            assertThat(machine.onRoundEnd(63L)).isEqualTo(new Action.RoundClosed(63L, Optional.empty()));
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);
        }

        @Test
        @DisplayName("WAITING --onRoundEnd--> WAITING [RoundClosed] (mid-flight join: the first 1707 after subscribe)")
        void waitingRoundEnd() {
            assertThat(machine.onRoundEnd(64L)).isEqualTo(new Action.RoundClosed(64L, Optional.empty()));
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);
            assertThat(machine.sid()).isEqualTo(64L);
        }

        @Test
        @DisplayName("PLACED --onRoundStart(newer sid)--> OPEN(new) [Ended(UNACKED) for the old bet first]")
        void placedMissedRoundEnd() {
            Plan plan = place(65L, 20_000L, 300L, JAKE);
            random.longs.addLast(100L);
            Opened o = machine.onRoundStart(66L).orElseThrow();
            assertThat(o.abandoned()).contains(
                    new Action.Ended(Outcome.UNACKED, plan, 65L, 0L, CrashRoundStateMachine.UNKNOWN_MULTIPLIER, false));
            assertThat(machine.phase()).isEqualTo(Phase.OPEN);
            assertThat(machine.sid()).isEqualTo(66L);
        }

        @Test
        @DisplayName("LIVE --onRoundStart(newer sid)--> OPEN(new) [Ended(CRASH) for the old bet first]")
        void liveMissedRoundEnd() {
            Plan plan = live(67L, 20_000L, 300L, JAKE);
            Opened o = machine.onRoundStart(68L).orElseThrow();
            assertThat(o.abandoned()).contains(
                    new Action.Ended(Outcome.CRASH, plan, 67L, 0L, CrashRoundStateMachine.UNKNOWN_MULTIPLIER, false));
            assertThat(machine.phase()).isEqualTo(Phase.OPEN);
        }

        @Test
        @DisplayName("CASHING --onRoundStart(newer sid)--> OPEN(new) [Ended(CRASH) for the old bet first]")
        void cashingMissedRoundEnd() {
            Plan plan = cashing(69L, 20_000L, 200L, NEYTIRI);
            Opened o = machine.onRoundStart(70L).orElseThrow();
            assertThat(o.abandoned()).contains(
                    new Action.Ended(Outcome.CRASH, plan, 69L, 0L, CrashRoundStateMachine.UNKNOWN_MULTIPLIER, false));
        }

        @Test
        @DisplayName("SETTLED --onRoundStart(newer sid)--> OPEN(new), nothing abandoned")
        void settledMissedRoundEnd() {
            live(71L, 20_000L, 300L, NEYTIRI);
            machine.onTick(tick(71L, 2.0, 1.5, false, true));
            assertThat(machine.onRoundStart(72L).orElseThrow().abandoned()).isEmpty();
        }

        @Test
        @DisplayName("any --reset()--> WAITING [nothing], from every phase")
        void resetFromEveryPhase() {
            machine.reset();
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);

            random.longs.addLast(0L);
            machine.onRoundStart(73L);
            machine.reset();
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);

            place(74L, 20_000L, 300L, JAKE);
            machine.reset();
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);
            assertThat(machine.onBetAck(JAKE, 20_000L)).as("late ack after reset").isEqualTo(CrashRoundStateMachine.NONE);

            live(75L, 20_000L, 300L, JAKE);
            machine.reset();
            assertThat(machine.onTick(shared(75L, 4.0))).as("tick after reset: no cash-out")
                    .isEqualTo(CrashRoundStateMachine.NONE);

            cashing(76L, 20_000L, 200L, JAKE);
            machine.reset();
            assertThat(machine.onCashoutAck(JAKE, 20_000L, 40_000L)).isEqualTo(CrashRoundStateMachine.NONE);

            live(77L, 20_000L, 300L, NEYTIRI);
            machine.onTick(tick(77L, 2.0, 1.5, false, true));
            machine.reset();
            assertThat(machine.phase()).isEqualTo(Phase.WAITING);
            assertThat(machine.sid()).as("sid survives the reset").isEqualTo(77L);
            assertThat(machine.onRoundEnd(77L)).isEqualTo(new Action.RoundClosed(77L, Optional.empty()));
        }
    }

    // ------------------------------------------------------------------ sid rules

    @Nested
    @DisplayName("sid rules")
    class SidRules {

        @Test
        @DisplayName("ticks for another sid, older or newer, are ignored")
        void tickOtherSid() {
            live(80L, 20_000L, 150L, JAKE);
            assertThat(machine.onTick(shared(79L, 5.0))).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onTick(shared(81L, 5.0))).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onTick(tick(79L, 5.0, 5.0, true, true))).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.phase()).isEqualTo(Phase.LIVE);
        }

        @Test
        @DisplayName("an older round end is ignored and closes nothing")
        void olderRoundEnd() {
            live(82L, 20_000L, 300L, JAKE);
            assertThat(machine.onRoundEnd(81L)).isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.phase()).isEqualTo(Phase.LIVE);
        }

        @Test
        @DisplayName("an older betting-closed is ignored")
        void olderBettingClosed() {
            random.longs.addLast(0L);
            machine.onRoundStart(83L);
            machine.onBettingClosed(82L);
            assertThat(machine.phase()).isEqualTo(Phase.OPEN);
        }

        @Test
        @DisplayName("betting-closed does not touch PLACED or LIVE")
        void bettingClosedLeavesBet() {
            place(84L, 20_000L, 300L, JAKE);
            machine.onBettingClosed(84L);
            assertThat(machine.phase()).isEqualTo(Phase.PLACED);
            machine.onBetAck(JAKE, 20_000L);
            machine.onBettingClosed(84L);
            assertThat(machine.phase()).isEqualTo(Phase.LIVE);
        }

        @Test
        @DisplayName("a duplicate round start or sid <= 0 is ignored (no second bet task)")
        void duplicateRoundStart() {
            random.longs.addLast(0L);
            assertThat(machine.onRoundStart(85L)).isPresent();
            assertThat(machine.onRoundStart(85L)).isEmpty();
            assertThat(machine.onRoundStart(0L)).isEmpty();
            assertThat(machine.onRoundStart(-1L)).isEmpty();
            assertThat(machine.sid()).isEqualTo(85L);
        }

        @Test
        @DisplayName("a lower sid round start is accepted (server sid reset) and is then playable")
        void lowerSidAccepted() {
            live(1_000L, 20_000L, 300L, JAKE);
            Opened o = machine.onRoundStart(5L).orElseThrow();
            assertThat(o.abandoned()).isPresent();
            assertThat(machine.sid()).isEqualTo(5L);
            now.addAndGet(o.betDelayMs());
            assertThat(machine.tryPlace(5L, LADDER, RICH)).isPresent();
        }

        @Test
        @DisplayName("missed 1707: the next round start emits the old bet's outcome first, then the new round plays")
        void missedRoundEnd() {
            Plan old = live(86L, 20_000L, 300L, JAKE);
            Opened o = machine.onRoundStart(87L).orElseThrow();
            assertThat(o.abandoned().orElseThrow().plan()).isEqualTo(old);
            assertThat(machine.onTick(shared(86L, 9.0))).as("late tick of the old round").isEqualTo(CrashRoundStateMachine.NONE);
            assertThat(machine.onRoundEnd(86L)).as("late 1707 of the old round").isEqualTo(CrashRoundStateMachine.NONE);
            now.addAndGet(o.betDelayMs());
            assertThat(machine.tryPlace(87L, LADDER, RICH)).isPresent();
        }

        @Test
        @DisplayName("a round end newer than a PLACED bet's sid still closes it UNACKED (1705 and 1707 both lost)")
        void newerRoundEndClosesBet() {
            Plan plan = place(88L, 20_000L, 300L, JAKE);
            Action a = machine.onRoundEnd(89L);
            assertThat(a).isEqualTo(
                    new Action.Ended(Outcome.UNACKED, plan, 88L, 0L, CrashRoundStateMachine.UNKNOWN_MULTIPLIER, false));
            assertThat(machine.sid()).isEqualTo(89L);
        }
    }

    @Test
    @DisplayName("runnerCount < 1 is rejected")
    void badRunnerCount() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new CrashRoundStateMachine(CrashBehavior.LEGACY, 0, now::get, new Random()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
