package com.vingame.bot.infrastructure.gateway;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GatewayBudgetRegistry}: <b>exactly one budget object per environment id, for the
 * life of the JVM</b> (GATEWAY_REQUEST_BUDGET AD-1), and exactly one set of
 * {@code gateway_budget_*} series with it.
 * <p>
 * Both halves are silent when they are wrong, which is why they are asserted here rather
 * than left to the wiring test. Two budgets for one environment each count part of the
 * traffic, so the window reads low, {@code GatewayBudgetNearCap} does not fire, and the first
 * symptom is a whole-brand Cloudflare block that the dashboard says cannot be happening.
 * Duplicate meter registration is the same failure wearing a different hat: Micrometer keeps
 * the first gauge it was given for a name+tag set, so a second budget's window would be
 * invisible even to itself.
 * <p>
 * The concurrency case is not hypothetical. {@code EnvironmentClientRegistry.getClients} is
 * reached from every bot-creation thread of a group start, from the activation tick and from
 * the recovery reconciler, and two groups on the same environment starting together is the
 * ordinary case.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("GatewayBudgetRegistry — one budget, and one set of series, per environment")
class GatewayBudgetRegistryTest {

    private SimpleMeterRegistry meters;
    private GatewayBudgetRegistry registry;

    private CapturingAppender appender;
    private LoggerContext ctx;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("gateway-budget-registry-capture", null, PatternLayout.createDefaultLayout(), true, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        registry = new GatewayBudgetRegistry(GatewayBudgetSettings.defaults(), meters);

        appender = new CapturingAppender();
        appender.start();
        ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = ctx.getConfiguration()
                .getLoggerConfig(GatewayBudgetRegistry.class.getName());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.DEBUG, null);
        loggerConfig.setLevel(Level.DEBUG);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        loggerConfig.removeAppender("gateway-budget-registry-capture");
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    /**
     * Only this class's own events. The appender may well be attached to an ancestor
     * LoggerConfig (there is no dedicated one for this class), so filtering by logger name is
     * what keeps an unrelated INFO line from a neighbouring class out of the assertion.
     */
    private List<String> linesAt(Level level) {
        return appender.events.stream()
                .filter(e -> e.getLevel() == level)
                .filter(e -> GatewayBudgetRegistry.class.getName().equals(e.getLoggerName()))
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    @Test
    @DisplayName("forEnvironment is idempotent and keyed by environment id alone")
    void forEnvironmentIsIdempotent() {
        GatewayBudget first = registry.forEnvironment("env-1", "Staging", "116");
        GatewayBudget again = registry.forEnvironment("env-1", "Staging", "116");
        // Same id, different name and product: the KEY is the id, exactly as in
        // EnvironmentClientRegistry, so a renamed environment keeps its window rather than
        // silently starting a second one beside it.
        GatewayBudget renamed = registry.forEnvironment("env-1", "Renamed", "097");
        GatewayBudget other = registry.forEnvironment("env-2", "Staging", "116");

        assertThat(again).isSameAs(first);
        assertThat(renamed).isSameAs(first);
        assertThat(other).isNotSameAs(first);
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("concurrent first calls for one environment still produce exactly one budget")
    void concurrentForEnvironmentProducesOneBudget() throws Exception {
        int threads = 24;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<GatewayBudget> resolved = new CopyOnWriteArraySet<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().name("budget-registry-" + t).start(() -> {
                try {
                    start.await();
                    resolved.add(registry.forEnvironment("env-1", "Staging", "116"));
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).isEmpty();
        // CopyOnWriteArraySet compares by equals(), which for these objects is identity.
        assertThat(resolved)
                .as("computeIfAbsent's mapping function runs at most once per absent key; if it "
                        + "did not, each caller would count against its own window")
                .hasSize(1);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.find("env-1")).isSameAs(resolved.iterator().next());
    }

    @Test
    @DisplayName("a reader never conjures a budget: find and snapshotOrEmpty do not create")
    void readsDoNotCreate() {
        assertThat(registry.find("env-1")).isNull();

        GatewayBudget.Snapshot empty = registry.snapshotOrEmpty("env-1", "Staging", "116");

        assertThat(registry.size())
                .as("the 5-minute rollup reads every running environment; if reading created a "
                        + "budget, an environment with no clients would grow a full set of "
                        + "gateway_budget_* series out of a log line")
                .isZero();
        assertThat(meters.getMeters()).isEmpty();
        // The shape is constant so `gateway=` is greppable whether or not a budget exists.
        assertThat(empty.windowRequests()).isZero();
        assertThat(empty.hardCap()).isEqualTo(900);
        assertThat(empty.mode()).isEqualTo(GatewayBudgetMode.OBSERVE);
        assertThat(empty.describeForRollup()).isEqualTo("gateway=0/900 queued=0/0/0 circuit=closed");
    }

    @Test
    @DisplayName("every series is registered once per environment, with explicit env+product tags")
    void meterNamesAndTagsArePerEnvironment() {
        registry.forEnvironment("env-1", "Staging", "116");
        registry.forEnvironment("env-2", "Prod", "097");

        // 44 meters per environment, and the arithmetic is worth writing down because two of
        // the terms are not obvious:
        //   1  window gauge
        //  +1  hard-cap gauge               (exported so GatewayBudgetNearCap is a RATIO against
        //      the configured cap rather than the literal 800 — lowering the cap then tightens
        //      the alert instead of silently disarming it)
        //  +3  queue-depth gauges           (one per tier)
        //  +3  reserved gauges
        //  +3  ceiling gauges               (so the Grafana panel plots the policy this box is
        //      running instead of three literals baked into a dashboard JSON — A5.6/F5)
        //  +3  wait timers
        // +15  requests_total counters      (3 tiers x 5 bounded outcomes; the fifth is
        //      `counted`, without which the counter and the window provably cannot reconcile,
        //      because count() stamps without asking for admission — A5.6/F3)
        // +15  `gateway_budget_wait.histogram` gauges — the five SLO buckets per tier, which
        //      Micrometer materialises as separate meters carrying an `le` tag and which become
        //      gateway_budget_wait_seconds_bucket{le=...} in the Prometheus exposition. They are
        //      wanted (that is what makes a p95-by-tier panel possible), and they are counted
        //      here so that raising or removing an SLO bucket is a deliberate edit.
        // Asserted as a total so a duplicate registration for one environment is visible.
        assertThat(meters.getMeters()).hasSize(2 * 44);

        for (String name : List.of(
                SlidingWindowGatewayBudget.WINDOW_REQUESTS,
                SlidingWindowGatewayBudget.HARD_CAP,
                SlidingWindowGatewayBudget.QUEUE_DEPTH,
                SlidingWindowGatewayBudget.RESERVED,
                SlidingWindowGatewayBudget.CEILING,
                SlidingWindowGatewayBudget.REQUESTS_TOTAL,
                SlidingWindowGatewayBudget.WAIT_TIMER)) {
            List<Meter> named = meters.getMeters().stream()
                    .filter(m -> m.getId().getName().equals(name))
                    .toList();
            assertThat(named).as("%s must exist", name).isNotEmpty();
            assertThat(named).allSatisfy(m -> {
                // Explicitly tagged, never through BotMetrics.mdcTags(): these are published
                // from the scrape thread and the probe scheduler, neither of which has bot MDC.
                assertThat(m.getId().getTag("environmentId")).isIn("env-1", "env-2");
                assertThat(m.getId().getTag("product")).isIn("116", "097");
                // Not bot_-prefixed, so BotMdcTagsMeterFilter leaves them alone.
                assertThat(m.getId().getName()).doesNotStartWith("bot_");
            });
            Set<String> envs = named.stream()
                    .map(m -> m.getId().getTag("environmentId"))
                    .collect(Collectors.toSet());
            assertThat(envs).as("%s is per-environment", name).containsExactlyInAnyOrder("env-1", "env-2");
        }
    }

    @Test
    @DisplayName("two environments count independently — Cloudflare's rule is per host")
    void environmentsDoNotShareAWindow() throws Exception {
        GatewayBudget a = registry.forEnvironment("env-1", "Staging", "116");
        GatewayBudget b = registry.forEnvironment("env-2", "Prod", "097");

        for (int i = 0; i < 5; i++) {
            a.count("ws-probe");
        }

        assertThat(((SlidingWindowGatewayBudget) a).windowRequests()).isEqualTo(5);
        assertThat(((SlidingWindowGatewayBudget) b).windowRequests())
                .as("a quiet brand must not inherit a busy brand's window")
                .isZero();
        assertThat(registry.snapshotAll()).hasSize(2);
        assertThat(registry.snapshotAll().stream().map(GatewayBudget.Snapshot::environmentId))
                .containsExactlyInAnyOrder("env-1", "env-2");
    }

    @Test
    @DisplayName("startup logs the posture at INFO, once, with the numbers the release greps")
    void startupPostureIsLoggedOnce() {
        registry.logStartupPosture();

        assertThat(linesAt(Level.INFO)).hasSize(1);
        String line = linesAt(Level.INFO).get(0);
        // V1a greps this line. If the rendering changes, the release verification step and
        // this assertion change together — that is the point of pinning it.
        assertThat(line)
                .contains("Gateway budget registry started (mode=observe")
                .contains("hard-cap=900")
                .contains("ceilings default=500 prioritized=750 essential=900")
                .contains("count-ws-upgrades=true");
        assertThat(linesAt(Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("an enforce-mode instance states its posture and nothing more")
    void enforceModeSaysNothingBeyondThePosture() {
        // A5.5's second half, inverted on purpose. Until Phase 3 this test asserted the PRESENCE
        // of a WARN saying "enforce is set but NOTHING is being paced" — correct while that was
        // true, and the single worst line to leave behind once it is not: tier 1, Loki-visible,
        // and an actively false statement about a production instance's posture. Shipping
        // enforcement while the app tells operators it is not enforcing is the one outcome that
        // must be impossible, so its absence is asserted rather than assumed.
        GatewayBudgetSettings enforcing =
                GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE);

        new GatewayBudgetRegistry(enforcing, new SimpleMeterRegistry()).logStartupPosture();

        assertThat(linesAt(Level.INFO)).singleElement().asString()
                .as("V3a greps this line to prove which posture a box is in")
                .contains("mode=enforce");
        assertThat(linesAt(Level.WARN))
                .as("no qualification, no caveat, nothing that contradicts the INFO line")
                .isEmpty();
    }

    @Test
    @DisplayName("the injected clock reaches the budgets it creates")
    void theClockIsHandedToEveryBudget() throws Exception {
        // Without this seam every window test would have to sleep for five minutes, and the
        // production budget would be on the wall clock, where an NTP step moves the window.
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        GatewayBudgetRegistry clocked = new GatewayBudgetRegistry(
                GatewayBudgetSettings.defaults(), new SimpleMeterRegistry(), clock::get);
        SlidingWindowGatewayBudget budget =
                (SlidingWindowGatewayBudget) clocked.forEnvironment("env-1", "Staging", "116");

        budget.count("ws-probe");
        assertThat(budget.windowRequests()).isEqualTo(1);

        clock.addAndGet(Duration.ofMinutes(5).toNanos());
        assertThat(budget.windowRequests())
                .as("the budget is reading the registry's clock, not System.nanoTime()")
                .isZero();
    }
}
