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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link SessionAggregationService} Phase 2 behavior — the 5s
 * UpdateBet flush and the full eviction lifecycle. Time is injected via
 * {@link SessionAggregationService#flushOnce(long)} (never a real 5s sleep) so the
 * suite is deterministic and fast.
 * <p>
 * Covers: (a) a flush emits a correctly-counted DEBUG line and its since-last-flush
 * deltas sum to the round total; (b) an ended session is evicted after
 * {@link SessionAggregationService#GRACE_NANOS grace}; (c) a stale session is swept by
 * {@link SessionAggregationService#TTL_NANOS TTL}; (d) {@code evictGroup} removes a
 * group's sessions; (e) flush counts are correct under concurrent bet feeds.
 */
@DisplayName("SessionAggregationService - Phase 2 (5s flush + eviction lifecycle)")
class SessionAggregationFlushTest {

    private static final String GROUP_ID = "group-flush";
    private static final String GAME_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String GAME_NAME = "BauCua";
    private static final long SID = 500100L;
    private static final String LOGGER_NAME = SessionAggregationService.class.getName();

    private static final Pattern FLUSH_LINE = Pattern.compile(
            "UpdateBet #(\\d+) \\| new bettors since last: (\\d+) \\| "
                    + "total bettors this round: (\\d+) \\| total staked: (\\d+)");

    private SessionAggregationService service;
    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private LoggerContext ctx;
    private Level prevLevel;

    @BeforeEach
    void setUp() {
        service = new SessionAggregationService();
        MDC.clear();

        appender = new CapturingAppender("CapturingAppender-session-flush");
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

    @Test
    @DisplayName("flush emits ONE DEBUG line per active session; deltas sum to the round total, baseline resets each tick")
    void flush_countsAndDelta() {
        setBotMdc();
        service.onSessionStart(SID, BettingSessionStrategy.INSTANCE, () -> "s");

        // First window: two distinct bettors stake 100 + 200 = 300.
        service.recordBet(SID, "botA", 100L);
        service.recordBet(SID, "botB", 200L);
        service.flushOnce(System.nanoTime());

        // Second window: one new bettor (+ a repeat from botA that is not a new bettor).
        service.recordBet(SID, "botC", 50L);
        service.recordBet(SID, "botA", 25L);
        service.flushOnce(System.nanoTime());

        List<LogEvent> flushes = flushEvents();
        assertThat(flushes).as("one flush line per tick").hasSize(2);

        // Both lines are DEBUG (per-round detail).
        assertThat(flushes).allMatch(e -> e.getLevel() == Level.DEBUG);

        Matcher m1 = FLUSH_LINE.matcher(flushes.get(0).getMessage().getFormattedMessage());
        assertThat(m1.find()).isTrue();
        assertThat(m1.group(1)).as("flush seq #1").isEqualTo("1");
        assertThat(m1.group(2)).as("new bettors since last (window 1)").isEqualTo("2");
        assertThat(m1.group(3)).as("total bettors this round").isEqualTo("2");
        assertThat(m1.group(4)).as("total staked").isEqualTo("300");

        Matcher m2 = FLUSH_LINE.matcher(flushes.get(1).getMessage().getFormattedMessage());
        assertThat(m2.find()).isTrue();
        assertThat(m2.group(1)).as("flush seq #2 monotonic").isEqualTo("2");
        assertThat(m2.group(2)).as("new bettors since last (window 2)").isEqualTo("1");
        assertThat(m2.group(3)).as("total bettors this round (cumulative)").isEqualTo("3");
        assertThat(m2.group(4)).as("total staked (cumulative)").isEqualTo("375");

        // Deltas across windows sum to the cumulative distinct-bettor count.
        int deltaSum = Integer.parseInt(m1.group(2)) + Integer.parseInt(m2.group(2));
        assertThat(deltaSum).isEqualTo(3);
    }

    @Test
    @DisplayName("ended session is NOT flushed, and is evicted after the grace window")
    void endedSession_evictedAfterGrace() {
        setBotMdc();
        service.onSessionStart(SID, BettingSessionStrategy.INSTANCE, () -> "s");
        service.recordBet(SID, "botA", 100L);
        service.onSessionEnd(SID, 0L, 100L, () -> "e");
        long endedAt = System.nanoTime(); // >= the accumulator's lastActivityNanos

        // Within grace: entry survives and emits NO flush line (round is closed).
        service.flushOnce(endedAt);
        assertThat(service.liveSessionCount()).as("survives within grace").isEqualTo(1);
        assertThat(flushEvents()).as("no flush for an ended session").isEmpty();

        // Past grace: swept.
        service.flushOnce(endedAt + SessionAggregationService.GRACE_NANOS + 1);
        assertThat(service.liveSessionCount()).as("evicted after grace").isEqualTo(0);
    }

    @Test
    @DisplayName("stale (never-ended) session past TTL is swept by the flush")
    void staleSession_sweptByTtl() {
        setBotMdc();
        service.onSessionStart(SID, BettingSessionStrategy.INSTANCE, () -> "s");
        service.recordBet(SID, "botA", 100L);
        long activeAt = System.nanoTime();

        // Still active well within TTL: flushed, not evicted.
        service.flushOnce(activeAt);
        assertThat(service.liveSessionCount()).isEqualTo(1);
        assertThat(flushEvents()).as("active session is flushed").hasSize(1);

        // No further activity; past TTL the abandoned session is reclaimed.
        service.flushOnce(activeAt + SessionAggregationService.TTL_NANOS + 1);
        assertThat(service.liveSessionCount()).as("stale session swept by TTL").isEqualTo(0);
    }

    @Test
    @DisplayName("evictGroup drops every session for a stopped group (group-stop hook)")
    void evictGroup_dropsGroupSessions() {
        setBotMdc();
        service.onSessionStart(SID, BettingSessionStrategy.INSTANCE, () -> "s");
        service.onSessionStart(SID + 1, BettingSessionStrategy.INSTANCE, () -> "s");
        service.recordBet(SID, "botA", 100L);
        assertThat(service.liveSessionCount()).isEqualTo(2);

        service.evictGroup(GROUP_ID);
        assertThat(service.liveSessionCount()).isEqualTo(0);

        // A flush after eviction is a clean no-op (no lines, no crash).
        service.flushOnce(System.nanoTime());
        assertThat(flushEvents()).isEmpty();
    }

    @Test
    @DisplayName("flush counts are correct under concurrent bet feeds")
    void flush_correctUnderConcurrentFeeds() throws InterruptedException {
        setBotMdc();
        service.onSessionStart(SID, BettingSessionStrategy.INSTANCE, () -> "s");

        int bots = 100;
        long amountPerBot = 10L;
        CountDownLatch ready = new CountDownLatch(bots);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(bots);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < bots; i++) {
            String bettor = "bot-" + i;
            Thread t = new Thread(() -> {
                setBotMdc();
                ready.countDown();
                try {
                    go.await();
                    service.recordBet(SID, bettor, amountPerBot);
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

        // All feeds have completed (joined) before the flush → a consistent snapshot.
        service.flushOnce(System.nanoTime());

        List<LogEvent> flushes = flushEvents();
        assertThat(flushes).hasSize(1);
        Matcher m = FLUSH_LINE.matcher(flushes.get(0).getMessage().getFormattedMessage());
        assertThat(m.find()).isTrue();
        assertThat(m.group(2)).as("all 100 bettors new in the first window").isEqualTo("100");
        assertThat(m.group(3)).as("total bettors this round").isEqualTo("100");
        assertThat(m.group(4)).as("total staked == 100 * 10").isEqualTo(String.valueOf(bots * amountPerBot));
    }

    @Test
    @DisplayName("a session whose flush throws does NOT skip eviction of the other sessions in the same tick")
    void poisonedSession_doesNotSkipNeighbourEviction() {
        setBotMdc();
        // Active session A whose renderFlushLine throws — it is emitted (active,
        // not ended, within TTL) so its strategy blows up mid-tick.
        long throwingSid = SID;
        service.onSessionStart(throwingSid, new ThrowingFlushStrategy(), () -> "s");
        // Two normal sessions B and C that we end so they are past-grace and MUST be
        // evicted in the same tick, regardless of iteration order vs the throwing one.
        long endedSidB = SID + 1;
        long endedSidC = SID + 2;
        service.onSessionStart(endedSidB, BettingSessionStrategy.INSTANCE, () -> "s");
        service.onSessionStart(endedSidC, BettingSessionStrategy.INSTANCE, () -> "s");
        service.onSessionEnd(endedSidB, 0L, 100L, () -> "e");
        service.onSessionEnd(endedSidC, 0L, 100L, () -> "e");

        assertThat(service.liveSessionCount()).as("three sessions live before flush").isEqualTo(3);

        long now = System.nanoTime();
        // Past grace (B/C evictable) but well within TTL (A stays active → emitted →
        // throws). The containment must swallow the throw and still evict B and C and
        // reach enforceSizeCap() at the end of the loop.
        long flushAt = now + SessionAggregationService.GRACE_NANOS + 1;
        assertThat(flushAt - now).isLessThan(SessionAggregationService.TTL_NANOS);

        // Does not propagate the strategy exception.
        service.flushOnce(flushAt);

        assertThat(service.liveSessionCount())
                .as("both ended neighbours evicted despite the poisoned session throwing")
                .isEqualTo(1);

        // The containment logged a WARN naming the throwing session, and the loop
        // reached the end (enforceSizeCap) rather than unwinding.
        List<LogEvent> warns = appender.events().stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getMessage().getFormattedMessage().contains("flush error for session"))
                .toList();
        assertThat(warns).as("one contained WARN for the throwing session").hasSize(1);
        assertThat(warns.get(0).getMessage().getFormattedMessage())
                .contains(String.valueOf(throwingSid));
    }

    @Test
    @DisplayName("flush delta counts a bet arriving between render and advance (no lost update)")
    void flushDelta_countsBetArrivingBetweenRenderAndAdvance() {
        setBotMdc();
        // Strategy that, on the first flush render, injects a fresh bettor — modelling a
        // recordBet landing AFTER the render read but BEFORE the baseline advance. With
        // the snapshot-once fix the baseline advances to the pre-injection snapshot, so
        // the late bettor is reported in the NEXT tick's delta rather than being lost.
        InjectingFlushStrategy strategy = new InjectingFlushStrategy(service, SID);
        service.onSessionStart(SID, strategy, () -> "s");

        service.recordBet(SID, "botA", 100L);
        service.recordBet(SID, "botB", 200L);

        // Tick 1: snapshot = 2 bettors; render injects "lateBot" (now 3 live); baseline
        // advances to the snapshot (2), not the post-injection count.
        service.flushOnce(System.nanoTime());
        // Tick 2: the injected bettor shows up as the one "new since last".
        service.flushOnce(System.nanoTime());

        List<LogEvent> flushes = flushEvents();
        assertThat(flushes).hasSize(2);

        Matcher m1 = FLUSH_LINE.matcher(flushes.get(0).getMessage().getFormattedMessage());
        assertThat(m1.find()).isTrue();
        assertThat(m1.group(2)).as("tick 1 new bettors (snapshot, pre-injection)").isEqualTo("2");
        assertThat(m1.group(3)).as("tick 1 total bettors (snapshot)").isEqualTo("2");

        Matcher m2 = FLUSH_LINE.matcher(flushes.get(1).getMessage().getFormattedMessage());
        assertThat(m2.find()).isTrue();
        assertThat(m2.group(2))
                .as("tick 2 counts the bet injected between tick 1's render and advance")
                .isEqualTo("1");
        assertThat(m2.group(3)).as("tick 2 total bettors cumulative").isEqualTo("3");

        // The deltas sum to the full distinct-bettor count — nothing lost between
        // render and advance (with the pre-fix re-read this sum would be 2).
        int deltaSum = Integer.parseInt(m1.group(2)) + Integer.parseInt(m2.group(2));
        assertThat(deltaSum).as("no lost update").isEqualTo(3);
    }

    /** Strategy whose flush render always throws — models a poisoned session. */
    private static final class ThrowingFlushStrategy implements SessionAggregationStrategy {
        @Override
        public boolean hasRoundBoundary() {
            return true;
        }

        @Override
        public String renderStartLine(SessionAccumulator acc, SessionContext ctx) {
            return BettingSessionStrategy.INSTANCE.renderStartLine(acc, ctx);
        }

        @Override
        public String renderFlushLine(SessionAccumulator acc, SessionContext ctx) {
            throw new IllegalStateException("boom in renderFlushLine");
        }

        @Override
        public String renderEndLine(SessionAccumulator acc, SessionContext ctx) {
            return BettingSessionStrategy.INSTANCE.renderEndLine(acc, ctx);
        }
    }

    /**
     * Strategy that injects one fresh bettor during the first flush render, simulating a
     * feed arriving between the render read and the baseline advance.
     */
    private static final class InjectingFlushStrategy implements SessionAggregationStrategy {
        private final SessionAggregationService service;
        private final long sid;
        private boolean injected = false;

        InjectingFlushStrategy(SessionAggregationService service, long sid) {
            this.service = service;
            this.sid = sid;
        }

        @Override
        public boolean hasRoundBoundary() {
            return true;
        }

        @Override
        public String renderStartLine(SessionAccumulator acc, SessionContext ctx) {
            return BettingSessionStrategy.INSTANCE.renderStartLine(acc, ctx);
        }

        @Override
        public String renderFlushLine(SessionAccumulator acc, SessionContext ctx) {
            // Render from the snapshot first (delegate), THEN inject — the injection
            // lands after the render read but before emitFlush advances the baseline.
            String line = BettingSessionStrategy.INSTANCE.renderFlushLine(acc, ctx);
            if (!injected) {
                injected = true;
                service.recordBet(sid, "lateBot", 500L);
            }
            return line;
        }

        @Override
        public String renderEndLine(SessionAccumulator acc, SessionContext ctx) {
            return BettingSessionStrategy.INSTANCE.renderEndLine(acc, ctx);
        }
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
