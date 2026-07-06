package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
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
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the STRATEGY_DECISION_AGGREGATION Phase 1 additions to the 5s
 * betting/Tai Xiu flush line — the tumbling per-window option histogram and the
 * window's amount min/avg/max. Time is injected via
 * {@link SessionAggregationService#flushOnce(long)} (never a real sleep) so the
 * suite is deterministic.
 * <p>
 * Covers: (a) a window's flush line carries the correct option histogram + amount
 * min/avg/max; (b) both reset each window — a later window's line reflects only its
 * own bets; (c) a zero-bet (PAYOUT) window renders the {@code -} placeholders; (d)
 * the histogram sums correctly under concurrent {@code recordBet} feeds.
 */
@DisplayName("SessionAggregationService - Phase 1 strategy-decision (option histogram + amount min/avg/max)")
class SessionAggregationDecisionTest {

    private static final String GROUP_ID = "group-decision";
    private static final String GAME_ID = "aaaaaaaa-bbbb-cccc-dddd-ffffffffffff";
    private static final String GAME_NAME = "BauCua";
    private static final long SID = 700100L;
    private static final String LOGGER_NAME = SessionAggregationService.class.getName();

    /** Captures the two appended segments: options list and the amount summary. */
    private static final Pattern DECISION_LINE = Pattern.compile(
            "options: (.+?) \\| amount min/avg/max: (\\S+)");

    private SessionAggregationService service;
    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private LoggerContext ctx;
    private Level prevLevel;

    @BeforeEach
    void setUp() {
        service = new SessionAggregationService();
        MDC.clear();

        appender = new CapturingAppender("CapturingAppender-session-decision");
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
        MDC.clear();
    }

    private void setBotMdc() {
        MDC.put(BotMdc.BOT_GROUP_ID, GROUP_ID);
        MDC.put(BotMdc.GAME_ID, GAME_ID);
        MDC.put(BotMdc.GAME_NAME, GAME_NAME);
    }

    private List<LogEvent> flushEvents() {
        return appender.events().stream()
                .filter(e -> e.getMessage().getFormattedMessage().contains("UpdateBet #"))
                .toList();
    }

    private static Matcher decisionOf(LogEvent e) {
        Matcher m = DECISION_LINE.matcher(e.getMessage().getFormattedMessage());
        assertThat(m.find()).as("flush line carries the decision segments").isTrue();
        return m;
    }

    @Test
    @DisplayName("one window: flush line shows the sorted option histogram and amount min/avg/max")
    void window_showsHistogramAndAmountSummary() {
        setBotMdc();
        service.onSessionStart(SID, BettingSessionStrategy.INSTANCE, () -> "s");

        // Options {0,0,1,5,5,5}; amounts 100,200,300,400,500,600 → sum 2100 / 6 = 350.
        service.recordBet(SID, "botA", 0, 100L);
        service.recordBet(SID, "botB", 0, 200L);
        service.recordBet(SID, "botC", 1, 300L);
        service.recordBet(SID, "botD", 5, 400L);
        service.recordBet(SID, "botE", 5, 500L);
        service.recordBet(SID, "botF", 5, 600L);

        service.flushOnce(System.nanoTime());

        List<LogEvent> flushes = flushEvents();
        assertThat(flushes).hasSize(1);
        assertThat(flushes.get(0).getLevel()).as("flush stays DEBUG").isEqualTo(Level.DEBUG);

        Matcher m = decisionOf(flushes.get(0));
        assertThat(m.group(1)).as("sorted, compact option histogram").isEqualTo("[0]x2 [1]x1 [5]x3");
        assertThat(m.group(2)).as("amount min/avg/max over the window").isEqualTo("100/350/600");

        // The existing three fields are unchanged / not double-emitted.
        String line = flushes.get(0).getMessage().getFormattedMessage();
        assertThat(line).contains("total staked: 2100");
        assertThat(line).contains("total bettors this round: 6");
    }

    @Test
    @DisplayName("histogram + min/max are tumbling: a later window reflects ONLY its own bets")
    void histogramAndMinMax_resetEachWindow() {
        setBotMdc();
        service.onSessionStart(SID, BettingSessionStrategy.INSTANCE, () -> "s");

        // Window 1: options {0,1}, amounts 100 + 300.
        service.recordBet(SID, "botA", 0, 100L);
        service.recordBet(SID, "botB", 1, 300L);
        service.flushOnce(System.nanoTime());

        // Window 2: only option 5, amount 700 — must not carry any window-1 option/min/max.
        service.recordBet(SID, "botC", 5, 700L);
        service.flushOnce(System.nanoTime());

        List<LogEvent> flushes = flushEvents();
        assertThat(flushes).hasSize(2);

        Matcher m1 = decisionOf(flushes.get(0));
        assertThat(m1.group(1)).isEqualTo("[0]x1 [1]x1");
        assertThat(m1.group(2)).as("window 1 min/avg/max").isEqualTo("100/200/300");

        Matcher m2 = decisionOf(flushes.get(1));
        assertThat(m2.group(1)).as("window 2 sees only its own option").isEqualTo("[5]x1");
        assertThat(m2.group(2)).as("window 2 min/avg/max reset to its own single bet").isEqualTo("700/700/700");
    }

    @Test
    @DisplayName("a zero-bet (PAYOUT) window renders the '-' placeholders, no divide-by-zero")
    void emptyWindow_rendersPlaceholders() {
        setBotMdc();
        service.onSessionStart(SID, BettingSessionStrategy.INSTANCE, () -> "s");

        // Flush with no bets recorded this window.
        service.flushOnce(System.nanoTime());

        List<LogEvent> flushes = flushEvents();
        assertThat(flushes).hasSize(1);
        Matcher m = decisionOf(flushes.get(0));
        assertThat(m.group(1)).as("no options this window").isEqualTo("-");
        assertThat(m.group(2)).as("no amount summary this window").isEqualTo("-");
    }

    @Test
    @DisplayName("option histogram sums correctly under concurrent recordBet feeds")
    void histogram_correctUnderConcurrentFeeds() throws InterruptedException {
        setBotMdc();
        service.onSessionStart(SID, BettingSessionStrategy.INSTANCE, () -> "s");

        int bots = 120;
        int optionCount = 3; // options 0, 1, 2
        long amount = 100L;
        LongAdder[] expected = {new LongAdder(), new LongAdder(), new LongAdder()};

        CountDownLatch ready = new CountDownLatch(bots);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(bots);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < bots; i++) {
            String bettor = "bot-" + i;
            int option = i % optionCount;
            expected[option].increment();
            Thread t = new Thread(() -> {
                setBotMdc();
                ready.countDown();
                try {
                    go.await();
                    service.recordBet(SID, bettor, option, amount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    MDC.clear();
                    done.countDown();
                }
            });
            threads.add(t);
            t.start();
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        // All feeds joined before the flush → a consistent snapshot.
        service.flushOnce(System.nanoTime());

        List<LogEvent> flushes = flushEvents();
        assertThat(flushes).hasSize(1);
        Matcher m = decisionOf(flushes.get(0));

        // 120 bots evenly across 3 options → 40 each. All amounts equal → min==avg==max.
        assertThat(m.group(1)).isEqualTo("[0]x" + expected[0].sum()
                + " [1]x" + expected[1].sum()
                + " [2]x" + expected[2].sum());
        assertThat(m.group(2)).isEqualTo("100/100/100");
    }

    /** Minimal in-memory log4j2 appender for asserting emitted events + levels. */
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
