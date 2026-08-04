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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SessionAggregationService} (AGGREGATED_SESSION_LOGGING
 * Phase 1). Covers the race-free first-seen StartGame guard (one line under
 * concurrent access, lazy raw sample invoked once), the EndGame summary content
 * (total staked == Σ recordBet, bettor count == distinct users), and the resolved
 * emit level (INFO) for both summary lines.
 */
@DisplayName("SessionAggregationService - Phase 1 (StartGame/EndGame summaries)")
class SessionAggregationServiceTest {

    private static final String GROUP_ID = "group-abc";
    private static final String GAME_ID = "11111111-2222-3333-4444-555555555555";
    private static final String GAME_NAME = "BauCua";
    private static final long SID = 422069L;
    private static final String LOGGER_NAME = SessionAggregationService.class.getName();

    private SessionAggregationService service;
    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private LoggerContext ctx;
    private Level prevLevel;

    @BeforeEach
    void setUp() {
        service = new SessionAggregationService();
        MDC.clear();

        appender = new CapturingAppender("CapturingAppender-session-agg");
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

    private List<LogEvent> eventsContaining(String needle) {
        return appender.events().stream()
                .filter(e -> e.getMessage().getFormattedMessage().contains(needle))
                .toList();
    }

    @Test
    @DisplayName("100 concurrent onSessionStart for one key → exactly ONE start line, sample supplier invoked once")
    void firstSeenGuard_emitsOnceUnderConcurrency() throws InterruptedException {
        int bots = 100;
        AtomicInteger sampleInvocations = new AtomicInteger(0);
        CountDownLatch ready = new CountDownLatch(bots);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(bots);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < bots; i++) {
            Thread t = new Thread(() -> {
                setBotMdc();
                ready.countDown();
                try {
                    go.await();
                    service.onSessionStart(SID, BettingSessionStrategy.INSTANCE,
                            () -> {
                                sampleInvocations.incrementAndGet();
                                return "raw-sample";
                            });
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

        List<LogEvent> starts = eventsContaining("entered session " + SID);
        assertThat(starts).as("exactly one StartGame line for 100 bots").hasSize(1);
        assertThat(sampleInvocations.get()).as("raw sample supplier invoked only on winner").isEqualTo(1);
        assertThat(service.liveSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("StartGame session-entry line emits at INFO and names group + sid")
    void startLine_isInfo_andCarriesIdentity() {
        setBotMdc();
        service.onSessionStart(SID, BettingSessionStrategy.INSTANCE, () -> "raw");

        List<LogEvent> starts = eventsContaining("entered session " + SID);
        assertThat(starts).hasSize(1);
        LogEvent e = starts.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.INFO);
        String msg = e.getMessage().getFormattedMessage();
        assertThat(msg).contains(GAME_NAME).contains(GROUP_ID).contains("sample: raw");
    }

    @Test
    @DisplayName("onSessionEnd from N bots → ONE summary; total staked == Σ recordBet, bettors == distinct users")
    void endGame_summarizesTotalsOnce() {
        setBotMdc();
        service.onSessionStart(SID, BettingSessionStrategy.INSTANCE, () -> "start-raw");

        // Three distinct bettors stake different amounts in this session.
        service.recordBet(SID, "botA", 0, 100L);
        service.recordBet(SID, "botB", 0, 250L);
        service.recordBet(SID, "botC", 0, 300L);
        // botA bets a second time — a bet event, not a new distinct bettor.
        service.recordBet(SID, "botA", 0, 50L);

        // Every bot observes EndGame; only the first-close bot logs.
        service.onSessionEnd(SID, 400L, 100L, () -> "end-raw");
        service.onSessionEnd(SID, 0L, 250L, () -> "should-not-log");
        service.onSessionEnd(SID, 0L, 300L, () -> "should-not-log");

        List<LogEvent> ends = eventsContaining("session " + SID + " ended");
        assertThat(ends).as("exactly one EndGame summary").hasSize(1);
        LogEvent e = ends.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.INFO);
        String msg = e.getMessage().getFormattedMessage();
        // total staked == 100 + 250 + 300 + 50 = 700 (outbound recordBet sum).
        assertThat(msg).contains("total staked: 700");
        // bettors == distinct user names == 3.
        assertThat(msg).contains("bettors: 3");
        // total win == winnings accumulated as-of-first-close (only botA's 400 so far).
        assertThat(msg).contains("total win: 400");
    }

    @Test
    @DisplayName("Tai Xiu-style winnings flow through unchanged (service is game-agnostic; passes bot-computed G)")
    void taiXiu_winningsFlowThrough() {
        setBotMdc();
        service.onSessionStart(SID, BettingSessionStrategy.INSTANCE, () -> "start");
        service.recordBet(SID, "tx1", 0, 1_000L);
        service.recordBet(SID, "tx2", 0, 2_000L);
        // Tai Xiu winnings = G (extracted by the bot via HasBotWinnings); the service
        // logs exactly what it is handed.
        service.onSessionEnd(SID, 3_000L, 1_000L, () -> "end");
        service.onSessionEnd(SID, 1_500L, 2_000L, () -> "end2");

        List<LogEvent> ends = eventsContaining("session " + SID + " ended");
        assertThat(ends).hasSize(1);
        String msg = ends.get(0).getMessage().getFormattedMessage();
        assertThat(msg).contains("total staked: 3000");
        assertThat(msg).contains("bettors: 2");
        // Winnings are the values passed in (as-of-first-close): only tx1's 3000.
        assertThat(msg).contains("total win: 3000");
    }

    @Test
    @DisplayName("evictGroup removes all sessions for a group")
    void evictGroup_clearsGroupSessions() {
        setBotMdc();
        service.onSessionStart(SID, BettingSessionStrategy.INSTANCE, () -> "s");
        service.onSessionStart(SID + 1, BettingSessionStrategy.INSTANCE, () -> "s");
        assertThat(service.liveSessionCount()).isEqualTo(2);

        service.evictGroup(GROUP_ID);
        assertThat(service.liveSessionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("no MDC identity → no session created, no crash")
    void noMdc_isNoOp() {
        MDC.clear();
        service.onSessionStart(SID, BettingSessionStrategy.INSTANCE, () -> "s");
        service.recordBet(SID, "bot", 0, 100L);
        service.onSessionEnd(SID, 0L, 0L, () -> "e");
        assertThat(service.liveSessionCount()).isEqualTo(0);
        assertThat(eventsContaining("entered session")).isEmpty();
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
