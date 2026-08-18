package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOG_VOLUME_TIERING tier 1 — {@link GroupLifecycleAggregator} must turn N per-bot lines
 * into exactly one per-group line, and must never grow without bound doing it.
 * <p>
 * The assertions are made against real captured {@link LogEvent}s rather than against
 * internal counters, because the whole point of the class is what it emits: how many
 * lines, at what level, with which MDC tag. A test over the counters would still pass if
 * the class emitted one line per bot.
 * <p>
 * Time is injected into {@link GroupLifecycleAggregator#sweepOnce(long)} so the 5 s idle
 * deadline is exercised without sleeping — the same seam
 * {@code SessionAggregationService.flushOnce} uses.
 */
@DisplayName("GroupLifecycleAggregator — one line per group, not one per bot")
class GroupLifecycleAggregatorTest {

    private static final String GROUP = "grp-1";

    /** Captures events published by the aggregator's own logger. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("capture", null, PatternLayout.createDefaultLayout(), true, null);
        }

        @Override
        public void append(LogEvent event) {
            // The event is mutable and recycled by log4j2; keep an immutable copy.
            events.add(event.toImmutable());
        }
    }

    private GroupLifecycleAggregator aggregator;
    private CapturingAppender appender;
    private LoggerContext ctx;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        aggregator = new GroupLifecycleAggregator();
        appender = new CapturingAppender();
        appender.start();

        ctx = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        loggerConfig = config.getLoggerConfig(GroupLifecycleAggregator.class.getName());
        // The LoggerConfig's OWN level gates before any appender-level filter, and
        // bot-engine's test classpath carries no log4j2 config, so this resolves to a
        // DefaultConfiguration root at ERROR — every log.info() would be a no-op and
        // every assertion below would fail for a reason that has nothing to do with the
        // class under test. Lower it for the duration and restore in tearDown.
        originalLevel = loggerConfig.getLevel();
        loggerConfig.setLevel(Level.DEBUG);
        loggerConfig.addAppender(appender, Level.DEBUG, null);
        ctx.updateLoggers();

        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        loggerConfig.removeAppender("capture");
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
        MDC.clear();
    }

    private List<String> emitted() {
        return appender.events.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    private static void withGroupMdc(String groupId, Runnable body) {
        MDC.put(BotMdc.BOT_GROUP_ID, groupId);
        MDC.put(BotMdc.ENVIRONMENT_ID, "env-1");
        try {
            body.run();
        } finally {
            MDC.clear();
        }
    }

    @Nested
    @DisplayName("initializations")
    class Initializations {

        @Test
        @DisplayName("50 bots produce exactly ONE line, naming the count and the target")
        void fiftyBotsProduceOneLine() {
            aggregator.expectInitialized(GROUP, "prod-baucua", 50);
            withGroupMdc(GROUP, () -> {
                for (int i = 0; i < 50; i++) {
                    aggregator.recordInitialized("game=BauCua, strategy=RANDOM");
                }
            });

            // No sweep needed: reaching the expected count emits immediately, so the line
            // lands with the group start it describes rather than 5 s afterwards.
            assertThat(emitted()).hasSize(1);
            assertThat(emitted().get(0))
                    .contains("50/50 bots initialized")
                    .contains(GROUP)
                    .contains("prod-baucua")
                    .contains("game=BauCua, strategy=RANDOM");
        }

        @Test
        @DisplayName("a short start still emits, via the idle sweep, and shows the shortfall")
        void shortStartEmitsOnIdleWithShortfall() {
            aggregator.expectInitialized(GROUP, "prod-baucua", 50);
            withGroupMdc(GROUP, () -> {
                for (int i = 0; i < 47; i++) {
                    aggregator.recordInitialized("game=BauCua, strategy=RANDOM");
                }
            });

            // Three bots failed to authenticate, so the completion path never fires.
            assertThat(emitted()).isEmpty();

            // Not yet idle.
            aggregator.sweepOnce(System.nanoTime());
            assertThat(emitted()).isEmpty();

            aggregator.sweepOnce(System.nanoTime() + GroupLifecycleAggregator.IDLE_FLUSH_NANOS);
            // "47/50" is the whole value of the expect() seam: a bare "47" reads like
            // success, "47/50" reads like three auth failures.
            assertThat(emitted()).hasSize(1);
            assertThat(emitted().get(0)).contains("47/50 bots initialized");
        }

        @Test
        @DisplayName("with no declared expectation the line reports a bare count")
        void undeclaredGroupReportsBareCount() {
            withGroupMdc(GROUP, () -> {
                aggregator.recordInitialized("game=Slot, gid=7, strategy=FIXED");
                aggregator.recordInitialized("game=Slot, gid=7, strategy=FIXED");
            });

            aggregator.sweepOnce(System.nanoTime() + GroupLifecycleAggregator.IDLE_FLUSH_NANOS);

            assertThat(emitted()).hasSize(1);
            assertThat(emitted().get(0)).contains("2 bots initialized").doesNotContain("/");
        }

        @Test
        @DisplayName("two groups starting together get one line each, not one merged line")
        void groupsAreKeyedSeparately() {
            aggregator.expectInitialized("a", "group-a", 2);
            aggregator.expectInitialized("b", "group-b", 3);
            withGroupMdc("a", () -> {
                aggregator.recordInitialized("game=A");
                aggregator.recordInitialized("game=A");
            });
            withGroupMdc("b", () -> {
                aggregator.recordInitialized("game=B");
                aggregator.recordInitialized("game=B");
                aggregator.recordInitialized("game=B");
            });

            assertThat(emitted()).hasSize(2);
            assertThat(emitted()).anySatisfy(line ->
                    assertThat(line).contains("group-a").contains("2/2"));
            assertThat(emitted()).anySatisfy(line ->
                    assertThat(line).contains("group-b").contains("3/3"));
        }
    }

    @Nested
    @DisplayName("auto-deposits")
    class Deposits {

        @Test
        @DisplayName("N deposits fold into one line carrying the count and the summed total")
        void depositsFoldIntoOneLine() {
            withGroupMdc(GROUP, () -> {
                aggregator.recordAutoDeposit(1_000L);
                aggregator.recordAutoDeposit(2_000L);
                aggregator.recordAutoDeposit(3_500L);
            });

            aggregator.sweepOnce(System.nanoTime() + GroupLifecycleAggregator.IDLE_FLUSH_NANOS);

            assertThat(emitted()).hasSize(1);
            assertThat(emitted().get(0))
                    .contains("3 bots auto-deposited")
                    .contains("total 6500");
        }
    }

    @Nested
    @DisplayName("safety properties")
    class Safety {

        @Test
        @DisplayName("the emitted line keeps the botGroupId MDC tag, even off the bot thread")
        void emittedLineCarriesMdc() {
            withGroupMdc(GROUP, () -> aggregator.recordInitialized("game=BauCua"));

            // Sweep from a thread with NO MDC — the production case, since the sweeper is
            // its own scheduler. Demoting/aggregating a line is only safe if the tag that
            // makes it drillable survives.
            MDC.clear();
            aggregator.sweepOnce(System.nanoTime() + GroupLifecycleAggregator.IDLE_FLUSH_NANOS);

            LogEvent event = appender.events.stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .reduce((first, second) -> second)
                    .orElseThrow();
            // Explicit <String> — ContextData.getValue is generic and assertThat(Object)
            // is otherwise ambiguous with assertThat(Predicate).
            assertThat(event.getContextData().<String>getValue(BotMdc.BOT_GROUP_ID)).isEqualTo(GROUP);
            assertThat(event.getContextData().<String>getValue(BotMdc.ENVIRONMENT_ID)).isEqualTo("env-1");
        }

        @Test
        @DisplayName("a feed from a thread with no group MDC is dropped, not misattributed")
        void feedWithoutMdcIsDropped() {
            MDC.clear();
            aggregator.recordInitialized("game=BauCua");
            aggregator.recordAutoDeposit(100L);

            aggregator.sweepOnce(System.nanoTime() + GroupLifecycleAggregator.IDLE_FLUSH_NANOS);

            assertThat(emitted()).isEmpty();
            assertThat(aggregator.pendingSizes()).containsExactly(0, 0);
        }

        @Test
        @DisplayName("emitting removes the entry, so a stopped group leaves nothing pending")
        void emitDrainsAndEvictClears() {
            aggregator.expectInitialized(GROUP, "g", 1);
            withGroupMdc(GROUP, () -> aggregator.recordInitialized("game=BauCua"));
            assertThat(aggregator.pendingSizes()).containsExactly(0, 0);

            withGroupMdc(GROUP, () -> aggregator.recordAutoDeposit(10L));
            assertThat(aggregator.pendingSizes()).containsExactly(0, 1);

            aggregator.evictGroup(GROUP);
            assertThat(aggregator.pendingSizes()).containsExactly(0, 0);

            // Eviction drops the counters rather than emitting them: a group being torn
            // down has no start to describe.
            aggregator.sweepOnce(System.nanoTime() + GroupLifecycleAggregator.IDLE_FLUSH_NANOS);
            assertThat(emitted()).hasSize(1); // only the initialized line from above
        }

        @Test
        @DisplayName("the pending map is bounded — overflow flushes early rather than growing")
        void pendingMapIsBounded() {
            for (int i = 0; i < GroupLifecycleAggregator.MAX_GROUPS + 25; i++) {
                String group = "g-" + i;
                withGroupMdc(group, () -> aggregator.recordAutoDeposit(1L));
            }

            assertThat(aggregator.pendingSizes().get(1))
                    .as("an aggregator that exists to bound log volume must bound its own state")
                    .isLessThanOrEqualTo(GroupLifecycleAggregator.MAX_GROUPS);
            // Overflow emits rather than discards: an early line, never a lost one.
            assertThat(emitted()).hasSize(25);
        }
    }
}
