package com.vingame.bot.domain.bot.coordination;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase J3 of {@code docs/plans/JACKPOT_SCALE_AND_RAMP.md}.
 *
 * <p>Pins the AD-J5 transfer function and AD-J7/AD-J8/AD-J10 idempotency +
 * concurrency contract:
 * <ul>
 *   <li>factor = {@code minMultiplier} at/below the seed floor;</li>
 *   <li>factor = {@code 1.0} at/above the ceiling;</li>
 *   <li>linear at the midpoint;</li>
 *   <li>neutral ({@code 1.0}) before any non-zero observation;</li>
 *   <li>a raw {@code 0} reading is "not observed" → stays neutral;</li>
 *   <li>degenerate {@code ceiling <= seedFloor} → neutral;</li>
 *   <li>{@code observePool} idempotent across repeated same-sid calls (factor
 *       stable, a single DEBUG emission);</li>
 *   <li>concurrent {@code observePool}/{@code getCurrentFactor} is coherent.</li>
 * </ul>
 */
@DisplayName("JackpotScaler")
class JackpotScalerTest {

    private static final long SEED = JackpotScaler.DEFAULT_SEED_FLOOR; // 500_000
    private static final long CEILING = 20_000_000L;
    private static final double MIN = 0.25;

    @Nested
    @DisplayName("transfer function (AD-J5)")
    class TransferFunction {

        @Test
        @DisplayName("neutral (1.0) before any observation")
        void neutralBeforeObservation() {
            JackpotScaler s = new JackpotScaler(CEILING, SEED, MIN);
            assertThat(s.getCurrentFactor()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("factor == minMultiplier at the seed floor")
        void floorAtSeed() {
            JackpotScaler s = new JackpotScaler(CEILING, SEED, MIN);
            s.observePool(1L, SEED);
            assertThat(s.getCurrentFactor()).isEqualTo(MIN);
        }

        @Test
        @DisplayName("factor == minMultiplier below the seed floor")
        void floorBelowSeed() {
            JackpotScaler s = new JackpotScaler(CEILING, SEED, MIN);
            s.observePool(1L, SEED - 1);
            assertThat(s.getCurrentFactor()).isEqualTo(MIN);
        }

        @Test
        @DisplayName("factor == 1.0 at the ceiling")
        void oneAtCeiling() {
            JackpotScaler s = new JackpotScaler(CEILING, SEED, MIN);
            s.observePool(1L, CEILING);
            assertThat(s.getCurrentFactor()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("factor == 1.0 above the ceiling")
        void oneAboveCeiling() {
            JackpotScaler s = new JackpotScaler(CEILING, SEED, MIN);
            s.observePool(1L, CEILING + 1_000_000L);
            assertThat(s.getCurrentFactor()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("linear at the midpoint: f = m + (1-m)/2")
        void linearMidpoint() {
            JackpotScaler s = new JackpotScaler(CEILING, SEED, MIN);
            long midpoint = SEED + (CEILING - SEED) / 2; // t = 0.5
            s.observePool(1L, midpoint);
            double expected = MIN + (1.0 - MIN) * 0.5; // 0.625
            assertThat(s.getCurrentFactor()).isCloseTo(expected, within(1e-9));
        }

        @Test
        @DisplayName("raw 0 is not-observed → stays neutral (never the floor)")
        void rawZeroStaysNeutral() {
            JackpotScaler s = new JackpotScaler(CEILING, SEED, MIN);
            s.observePool(1L, 0L);
            assertThat(s.getCurrentFactor())
                    .as("a 0 reading must map to neutral, not minMultiplier")
                    .isEqualTo(1.0);
            // A later non-zero observation begins tracking normally.
            s.observePool(2L, SEED);
            assertThat(s.getCurrentFactor()).isEqualTo(MIN);
        }

        @Test
        @DisplayName("degenerate ceiling <= seedFloor → neutral")
        void degenerateCeilingNeutral() {
            JackpotScaler s = new JackpotScaler(SEED, SEED, MIN); // ceiling == seed
            s.observePool(1L, 10_000_000L);
            assertThat(s.getCurrentFactor()).isEqualTo(1.0);

            JackpotScaler below = new JackpotScaler(SEED - 1, SEED, MIN); // ceiling < seed
            below.observePool(1L, 10_000_000L);
            assertThat(below.getCurrentFactor()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("snapshot reflects the last observed pool and derived factor")
        void snapshotCoherent() {
            JackpotScaler s = new JackpotScaler(CEILING, SEED, MIN);
            long midpoint = SEED + (CEILING - SEED) / 2;
            s.observePool(1L, midpoint);
            JackpotScaler.Snapshot snap = s.snapshot();
            assertThat(snap.lastObservedPool()).isEqualTo(midpoint);
            assertThat(snap.ceiling()).isEqualTo(CEILING);
            assertThat(snap.seedFloor()).isEqualTo(SEED);
            assertThat(snap.minMultiplier()).isEqualTo(MIN);
            assertThat(snap.currentFactor()).isCloseTo(0.625, within(1e-9));
        }
    }

    @Nested
    @DisplayName("observePool idempotency (AD-J7/AD-J10)")
    class Idempotency {

        private static final String LOGGER_NAME = JackpotScaler.class.getName();

        private CapturingAppender appender;
        private LoggerConfig loggerConfig;
        private LoggerContext ctx;
        private Level prevLevel;

        @BeforeEach
        void setUp() {
            appender = new CapturingAppender("CapturingAppender-jackpot-scaler");
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
                    .filter(m -> m.contains("JackpotScale sid=" + sid + " "))
                    .count();
        }

        @Test
        @DisplayName("repeated observePool for the same sid emits the summary once and keeps the factor stable")
        void idempotentPerSid() {
            JackpotScaler s = new JackpotScaler(CEILING, SEED, MIN);
            long midpoint = SEED + (CEILING - SEED) / 2;

            // onEndGame fires once per bot per round → N calls for the same sid.
            s.observePool(42L, midpoint);
            double afterFirst = s.getCurrentFactor();
            s.observePool(42L, 999_999_999L); // ignored — same sid, first-seen wins
            s.observePool(42L, 0L);

            assertThat(s.getCurrentFactor())
                    .as("later same-sid calls must not move the factor")
                    .isEqualTo(afterFirst);
            assertThat(summaryLinesFor(42L)).isEqualTo(1L);
        }

        @Test
        @DisplayName("a new sid recomputes and emits again")
        void newSidEmitsAgain() {
            JackpotScaler s = new JackpotScaler(CEILING, SEED, MIN);
            s.observePool(1L, SEED);
            assertThat(s.getCurrentFactor()).isEqualTo(MIN);
            s.observePool(2L, CEILING);
            assertThat(s.getCurrentFactor()).isEqualTo(1.0);

            assertThat(summaryLinesFor(1L)).isEqualTo(1L);
            assertThat(summaryLinesFor(2L)).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("concurrent observePool / getCurrentFactor is coherent (one winner per sid)")
        void concurrentObserveCoherent() throws Exception {
            JackpotScaler s = new JackpotScaler(CEILING, SEED, MIN);
            long sid = 777L;
            long pool = SEED + (CEILING - SEED) / 2; // t = 0.5 → 0.625
            double expected = MIN + (1.0 - MIN) * 0.5;

            int threads = 64;
            ExecutorService pool2 = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicLong neutralReads = new AtomicLong();

            for (int t = 0; t < threads; t++) {
                pool2.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        for (int i = 0; i < 200; i++) {
                            // All N bots observe the same sid → first-seen wins;
                            // repeated calls are no-ops. Interleaved lock-free reads
                            // must see either the neutral 1.0 (before the winner) or
                            // the settled factor — never a torn value.
                            s.observePool(sid, pool);
                            double f = s.getCurrentFactor();
                            if (f == 1.0) {
                                neutralReads.incrementAndGet();
                            } else {
                                assertThat(f).isCloseTo(expected, within(1e-9));
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
            pool2.shutdown();

            // Final state is the single settled factor for this one sid.
            assertThat(s.getCurrentFactor()).isCloseTo(expected, within(1e-9));
            assertThat(s.snapshot().lastObservedPool()).isEqualTo(pool);
        }
    }

    /** Log4j2 in-memory appender capturing emitted events for assertions. */
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
