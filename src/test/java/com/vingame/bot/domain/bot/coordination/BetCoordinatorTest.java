package com.vingame.bot.domain.bot.coordination;

import com.vingame.bot.domain.bot.coordination.ReservationOutcome.Decision;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 of {@code docs/plans/BET_COORDINATION.md}.
 *
 * <p>Pins the AD-3/AD-4/AD-5 reservation core:
 * <ul>
 *   <li>per-option budget split {@code B(o)=floor(w/W*cap)} across N options;</li>
 *   <li>trim to remaining budget with grid alignment (never rounds up);</li>
 *   <li>reject below {@code minBet};</li>
 *   <li>aggregate cap binds before per-option budget;</li>
 *   <li>stale-{@code sid} (and {@code 0} sentinel) reject;</li>
 *   <li>{@code onRound} idempotent across repeated same-{@code sid} calls;</li>
 *   <li>concurrent {@code reserve} from many threads never exceeds cap or any
 *       option budget.</li>
 * </ul>
 */
@DisplayName("BetCoordinator")
class BetCoordinatorTest {

    private static Map<Integer, Integer> weights(int... w) {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < w.length; i++) {
            m.put(i, w[i]);
        }
        return m;
    }

    @Nested
    @DisplayName("budget split")
    class BudgetSplit {

        @Test
        @DisplayName("splits the cap across N options by weight (floor)")
        void splitsByWeight() {
            // weights 1:1:2 over cap 1000 → 250 / 250 / 500
            BetCoordinator c = new BetCoordinator(weights(1, 1, 2), 1000, 10, 10);
            c.onRound(42L);

            BetCoordinator.Snapshot snap = c.snapshot();
            assertThat(snap.options()).extracting(BetCoordinator.OptionSnapshot::targetBudget)
                    .containsExactly(250L, 250L, 500L);
        }

        @Test
        @DisplayName("uniform TaiXiu N=2 splits the cap 50/50")
        void uniformSplit() {
            BetCoordinator c = new BetCoordinator(Map.of(1, 1, 2, 1), 1000, 10, 10);
            c.onRound(7L);
            assertThat(c.snapshot().options()).allSatisfy(o ->
                    assertThat(o.targetBudget()).isEqualTo(500L));
        }

        @Test
        @DisplayName("rounding slack from floor stays under the aggregate cap")
        void floorSlack() {
            // 3 options weight 1 each over cap 100 → floor(33) each = 99 < 100
            BetCoordinator c = new BetCoordinator(weights(1, 1, 1), 100, 10, 10);
            c.onRound(1L);
            long sumBudgets = c.snapshot().options().stream()
                    .mapToLong(BetCoordinator.OptionSnapshot::targetBudget).sum();
            assertThat(sumBudgets).isEqualTo(99L).isLessThanOrEqualTo(100L);
        }
    }

    @Nested
    @DisplayName("reserve policy")
    class ReservePolicy {

        @Test
        @DisplayName("approves a proposal that fits within budget and grid")
        void approveWithinBudget() {
            BetCoordinator c = new BetCoordinator(weights(1, 1), 1000, 10, 10);
            c.onRound(5L);

            ReservationOutcome o = c.reserve(5L, 0, 100);
            assertThat(o.decision()).isEqualTo(Decision.APPROVE);
            assertThat(o.amount()).isEqualTo(100L);
            assertThat(c.snapshot().options().get(0).committedStake()).isEqualTo(100L);
            assertThat(c.getCurrentAggregateStake()).isEqualTo(100L);
        }

        @Test
        @DisplayName("trims to remaining option budget, grid-aligned DOWN")
        void trimToOptionBudget() {
            // option 0 budget = 250; increment 100, minBet 50.
            BetCoordinator c = new BetCoordinator(weights(1, 1, 2), 1000, 50, 100);
            c.onRound(9L);

            // commit 150 first → remaining 100 on option 0 (150 aligns to 150 exactly → APPROVE).
            assertThat(c.reserve(9L, 0, 150).decision()).isEqualTo(Decision.APPROVE);
            // remaining option budget = 250-150 = 100; propose 130 → allow 100 → aligned 50+floor((100-50)/100)*100 = 50
            ReservationOutcome o = c.reserve(9L, 0, 130);
            assertThat(o.decision()).isEqualTo(Decision.TRIM);
            assertThat(o.amount()).isEqualTo(50L);
            // never rounds up beyond remaining
            assertThat(c.snapshot().options().get(0).committedStake())
                    .isLessThanOrEqualTo(250L);
        }

        @Test
        @DisplayName("grid alignment floors to minBet + k*increment, never up")
        void gridAlignsDown() {
            BetCoordinator c = new BetCoordinator(weights(1), 10_000, 100, 250);
            c.onRound(3L);
            // propose 640 → aligned = 100 + floor((640-100)/250)*250 = 100 + 2*250 = 600
            ReservationOutcome o = c.reserve(3L, 0, 640);
            assertThat(o.decision()).isEqualTo(Decision.TRIM);
            assertThat(o.amount()).isEqualTo(600L);
        }

        @Test
        @DisplayName("rejects when the alignable amount is below minBet")
        void rejectBelowMinBet() {
            // option 0 budget = 500; commit 480, remaining 20 < minBet 50 → reject
            BetCoordinator c = new BetCoordinator(weights(1, 1), 1000, 50, 10);
            c.onRound(11L);
            c.reserve(11L, 0, 480); // 480 aligns to 480 (50 + 43*10)
            ReservationOutcome o = c.reserve(11L, 0, 100);
            assertThat(o.decision()).isEqualTo(Decision.REJECT);
            assertThat(o.amount()).isZero();
        }

        @Test
        @DisplayName("aggregate cap binds before per-option budget")
        void aggregateCapBinds() {
            // Two options, weight 1:1 over cap 200 → per-option budget 100 each.
            // But drain the aggregate on option 0 first, then option 1 (with
            // 100 budget free) is still capped by the aggregate remainder.
            BetCoordinator c = new BetCoordinator(weights(1, 1), 200, 10, 10);
            c.onRound(21L);
            assertThat(c.reserve(21L, 0, 100).amount()).isEqualTo(100L); // opt0 full
            // opt1 budget is 100 free, but aggregate remaining = 200-100 = 100.
            assertThat(c.reserve(21L, 1, 100).amount()).isEqualTo(100L); // exactly fills cap
            // now aggregate is exhausted; any further proposal rejects.
            assertThat(c.reserve(21L, 1, 100).decision()).isEqualTo(Decision.REJECT);
            assertThat(c.getCurrentAggregateStake()).isEqualTo(200L)
                    .isLessThanOrEqualTo(c.getMaxAggregateStakePerRound());
        }

        @Test
        @DisplayName("aggregate remainder trims a proposal even with option budget free")
        void aggregateTrims() {
            // 3 options each budget floor(1/3*300)=100, cap 300.
            BetCoordinator c = new BetCoordinator(weights(1, 1, 1), 300, 10, 10);
            c.onRound(22L);
            c.reserve(22L, 0, 100);
            c.reserve(22L, 1, 100);
            // aggregate remaining = 100; option 2 budget = 100 free; propose 100 → approve 100
            assertThat(c.reserve(22L, 2, 100).decision()).isEqualTo(Decision.APPROVE);
            assertThat(c.getCurrentAggregateStake()).isLessThanOrEqualTo(300L);
        }
    }

    @Nested
    @DisplayName("round lifecycle")
    class RoundLifecycle {

        @Test
        @DisplayName("rejects the 0 sentinel session id")
        void rejectSentinel() {
            BetCoordinator c = new BetCoordinator(weights(1, 1), 1000, 10, 10);
            c.onRound(5L);
            assertThat(c.reserve(0L, 0, 100).decision()).isEqualTo(Decision.REJECT);
        }

        @Test
        @DisplayName("rejects a stale (mismatched) session id")
        void rejectStaleSid() {
            BetCoordinator c = new BetCoordinator(weights(1, 1), 1000, 10, 10);
            c.onRound(5L);
            assertThat(c.reserve(4L, 0, 100).decision()).isEqualTo(Decision.REJECT);
            // and reserving for the live sid still works
            assertThat(c.reserve(5L, 0, 100).decision()).isEqualTo(Decision.APPROVE);
        }

        @Test
        @DisplayName("onRound is idempotent across repeated same-sid calls")
        void onRoundIdempotent() {
            BetCoordinator c = new BetCoordinator(weights(1, 1), 1000, 10, 10);
            c.onRound(5L);
            c.reserve(5L, 0, 100); // committed 100 on option 0
            // the other N-1 bots see the same sid → must NOT reset the budget
            c.onRound(5L);
            c.onRound(5L);
            assertThat(c.snapshot().options().get(0).committedStake()).isEqualTo(100L);
            assertThat(c.getCurrentAggregateStake()).isEqualTo(100L);
        }

        @Test
        @DisplayName("a new sid swaps in a fresh budget (committed reset)")
        void newSidResets() {
            BetCoordinator c = new BetCoordinator(weights(1, 1), 1000, 10, 10);
            c.onRound(5L);
            c.reserve(5L, 0, 500);
            c.onRound(6L);
            assertThat(c.getCurrentAggregateStake()).isZero();
            assertThat(c.reserve(6L, 0, 100).decision()).isEqualTo(Decision.APPROVE);
        }
    }

    @Nested
    @DisplayName("onRoundComplete idempotency (AD-6)")
    class OnRoundCompleteIdempotency {

        private static final String LOGGER_NAME = BetCoordinator.class.getName();

        private CapturingAppender appender;
        private LoggerConfig loggerConfig;
        private LoggerContext ctx;
        private Level prevLevel;

        @BeforeEach
        void setUp() {
            appender = new CapturingAppender("CapturingAppender-bet-coordinator");
            appender.start();
            ctx = (LoggerContext) LogManager.getContext(false);
            loggerConfig = ctx.getConfiguration().getLoggerConfig(LOGGER_NAME);
            prevLevel = loggerConfig.getLevel();
            loggerConfig.addAppender(appender, Level.ALL, null);
            loggerConfig.setLevel(Level.ALL);
            ctx.updateLoggers();
        }

        @AfterEach
        void tearDown() {
            loggerConfig.removeAppender(appender.getName());
            loggerConfig.setLevel(prevLevel);
            ctx.updateLoggers();
        }

        private long summaryLinesFor(long sid) {
            return appender.events().stream()
                    .map(e -> e.getMessage().getFormattedMessage())
                    .filter(m -> m.contains("Coordination sid=" + sid + " "))
                    .count();
        }

        @Test
        @DisplayName("repeated onRoundComplete for the same round emits the summary once")
        void idempotentPerSid() {
            BetCoordinator c = new BetCoordinator(weights(1, 1), 1000, 10, 10);
            c.onRound(5L);
            c.reserve(5L, 0, 100);

            // onEndGame fires once per bot per round → N calls for the same sid.
            c.onRoundComplete(5L);
            c.onRoundComplete(5L);
            c.onRoundComplete(5L);

            assertThat(summaryLinesFor(5L)).isEqualTo(1L);
        }

        @Test
        @DisplayName("a genuinely new round emits its own summary again")
        void newRoundEmitsAgain() {
            BetCoordinator c = new BetCoordinator(weights(1, 1), 1000, 10, 10);

            c.onRound(5L);
            c.reserve(5L, 0, 100);
            c.onRoundComplete(5L);
            c.onRoundComplete(5L); // duplicate no-op

            c.onRound(6L);
            c.reserve(6L, 0, 100);
            c.onRoundComplete(6L);
            c.onRoundComplete(6L); // duplicate no-op

            assertThat(summaryLinesFor(5L)).isEqualTo(1L);
            assertThat(summaryLinesFor(6L)).isEqualTo(1L);
        }

        @Test
        @DisplayName("no active round (0 sentinel) emits nothing")
        void noActiveRoundNoOp() {
            BetCoordinator c = new BetCoordinator(weights(1, 1), 1000, 10, 10);
            c.onRoundComplete(0L);
            c.onRoundComplete(99L);
            assertThat(appender.events().stream()
                    .map(e -> e.getMessage().getFormattedMessage())
                    .filter(m -> m.contains("Coordination sid="))
                    .count()).isZero();
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("many concurrent reserves never exceed cap or any option budget")
        void concurrentStress() throws Exception {
            int nOptions = 6;
            long cap = 50_000;
            int minBet = 10;
            int increment = 10;
            BetCoordinator c = new BetCoordinator(weights(1, 1, 1, 1, 1, 1), cap, minBet, increment);
            long sid = 777L;
            c.onRound(sid);

            int threads = 64;
            int reservesPerThread = 200;
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicLong approvedPlusTrimmed = new AtomicLong();

            for (int t = 0; t < threads; t++) {
                final int seed = t;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        java.util.Random rnd = new java.util.Random(seed);
                        for (int i = 0; i < reservesPerThread; i++) {
                            int option = rnd.nextInt(nOptions);
                            long amount = (1 + rnd.nextInt(20)) * (long) increment; // 10..200
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

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            BetCoordinator.Snapshot snap = c.snapshot();
            // aggregate invariant
            assertThat(snap.currentAggregateStake()).isLessThanOrEqualTo(cap);
            assertThat(approvedPlusTrimmed.get()).isEqualTo(snap.currentAggregateStake());
            // per-option invariant
            assertThat(snap.options()).allSatisfy(o ->
                    assertThat(o.committedStake()).isLessThanOrEqualTo(o.targetBudget()));
            // and every committed amount is grid-valid at the aggregate level
            assertThat(snap.currentAggregateStake() % increment).isZero();
        }
    }

    /** Minimal in-memory log4j2 appender for asserting emitted summary lines. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender(String name) {
            super(name, null, PatternLayout.createDefaultLayout(), false, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> events() {
            return new ArrayList<>(events);
        }
    }
}
