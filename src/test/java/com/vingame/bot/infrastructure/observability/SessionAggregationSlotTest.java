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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link SessionAggregationService} Phase 3 slot behavior — the
 * synthetic per-{@code (botGroupId, gameId)} rolling window (AD-12). Time is injected
 * via {@link SessionAggregationService#flushOnce(long)} (never a real 5s sleep) so the
 * suite is deterministic and fast.
 * <p>
 * Covers: (a) a slot group produces one correctly-counted DEBUG window summary per
 * flush (spins/staked/win/jackpot) and no StartGame/EndGame lines; (b) the synthetic
 * window advances/rolls — {@code spins since last} is a per-tick delta while total
 * staked/win are cumulative; (c) a spinning window is reclaimed by the TTL sweep;
 * (d) {@code evictGroup} drops the slot window on group stop.
 */
@DisplayName("SessionAggregationService - Phase 3 (slot synthetic window)")
class SessionAggregationSlotTest {

    private static final String GROUP_ID = "group-slot";
    private static final String GAME_ID = "11111111-2222-3333-4444-555555555555";
    private static final String GAME_NAME = "SlotTip";
    private static final String LOGGER_NAME = SessionAggregationService.class.getName();

    private static final Pattern SLOT_LINE = Pattern.compile(
            "SlotWindow " + GAME_NAME + "/" + GROUP_ID + " #(\\d+) \\| "
                    + "spins since last: (\\d+) \\| total staked: (\\d+) \\| "
                    + "total win: (\\d+) \\| jackpot hits: (\\d+)");

    private SessionAggregationService service;
    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private LoggerContext ctx;
    private Level prevLevel;

    @BeforeEach
    void setUp() {
        service = new SessionAggregationService();
        MDC.clear();

        appender = new CapturingAppender("CapturingAppender-session-slot");
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

    private List<LogEvent> slotLines() {
        return appender.events().stream()
                .filter(e -> e.getMessage().getFormattedMessage().contains("SlotWindow"))
                .toList();
    }

    @Test
    @DisplayName("slot group emits ONE DEBUG window line per flush with correct spin/stake/win/jackpot counts")
    void slotWindow_countsPerFlush() {
        setBotMdc();
        // Three bots each spin once (total stake 500 * numLines already folded in by
        // the bot; here we feed the total stake directly). One spin hits a jackpot.
        service.recordSpin(SlotSessionStrategy.INSTANCE, "slotA", 12_500L);
        service.recordSpinResult(6_000L, false);
        service.recordSpin(SlotSessionStrategy.INSTANCE, "slotB", 12_500L);
        service.recordSpinResult(0L, false);
        service.recordSpin(SlotSessionStrategy.INSTANCE, "slotC", 12_500L);
        service.recordSpinResult(50_000L, true); // jackpot

        service.flushOnce(System.nanoTime());

        List<LogEvent> lines = slotLines();
        assertThat(lines).as("one slot window line per (group,gameId) per flush").hasSize(1);
        assertThat(lines.get(0).getLevel()).as("slot summary is DEBUG").isEqualTo(Level.DEBUG);

        Matcher m = SLOT_LINE.matcher(lines.get(0).getMessage().getFormattedMessage());
        assertThat(m.find()).as("slot line shape").isTrue();
        assertThat(m.group(1)).as("window flush seq #1").isEqualTo("1");
        assertThat(m.group(2)).as("spins since last").isEqualTo("3");
        assertThat(m.group(3)).as("total staked = 3 * 12500").isEqualTo("37500");
        assertThat(m.group(4)).as("total win = 6000 + 50000").isEqualTo("56000");
        assertThat(m.group(5)).as("jackpot hits").isEqualTo("1");

        // Slots never emit StartGame/EndGame lifecycle lines (AD-12).
        assertThat(appender.events()).noneMatch(e ->
                e.getMessage().getFormattedMessage().contains("entered session")
                        || e.getMessage().getFormattedMessage().contains(" ended"));
    }

    @Test
    @DisplayName("synthetic window rolls: 'spins since last' is a per-tick delta; staked/win are cumulative")
    void slotWindow_rollsBaselinePerTick() {
        setBotMdc();

        // Window 1: two spins.
        service.recordSpin(SlotSessionStrategy.INSTANCE, "slotA", 100L);
        service.recordSpin(SlotSessionStrategy.INSTANCE, "slotB", 100L);
        service.flushOnce(System.nanoTime());

        // Window 2: three more spins (one jackpot).
        service.recordSpin(SlotSessionStrategy.INSTANCE, "slotA", 100L);
        service.recordSpinResult(0L, true);
        service.recordSpin(SlotSessionStrategy.INSTANCE, "slotB", 100L);
        service.recordSpin(SlotSessionStrategy.INSTANCE, "slotC", 100L);
        service.flushOnce(System.nanoTime());

        List<LogEvent> lines = slotLines();
        assertThat(lines).hasSize(2);

        Matcher m1 = SLOT_LINE.matcher(lines.get(0).getMessage().getFormattedMessage());
        assertThat(m1.find()).isTrue();
        assertThat(m1.group(1)).isEqualTo("1");
        assertThat(m1.group(2)).as("window 1 spins since last").isEqualTo("2");
        assertThat(m1.group(3)).as("window 1 cumulative staked").isEqualTo("200");
        assertThat(m1.group(5)).as("no jackpot yet").isEqualTo("0");

        Matcher m2 = SLOT_LINE.matcher(lines.get(1).getMessage().getFormattedMessage());
        assertThat(m2.find()).isTrue();
        assertThat(m2.group(1)).as("flush seq monotonic").isEqualTo("2");
        assertThat(m2.group(2)).as("window 2 delta (only the 3 new spins)").isEqualTo("3");
        assertThat(m2.group(3)).as("staked is cumulative across windows").isEqualTo("500");
        assertThat(m2.group(5)).as("jackpot cumulative").isEqualTo("1");

        // Deltas across windows sum to the total spins (2 + 3 = 5).
        int deltaSum = Integer.parseInt(m1.group(2)) + Integer.parseInt(m2.group(2));
        assertThat(deltaSum).isEqualTo(5);
    }

    @Test
    @DisplayName("an idle slot window past TTL is swept (never marks ended, so TTL is the reclaim path)")
    void slotWindow_sweptByTtl() {
        setBotMdc();
        service.recordSpin(SlotSessionStrategy.INSTANCE, "slotA", 100L);
        long activeAt = System.nanoTime();

        // Active within TTL: flushed, not evicted.
        service.flushOnce(activeAt);
        assertThat(service.liveSessionCount()).isEqualTo(1);
        assertThat(slotLines()).hasSize(1);

        // No further spins; past TTL the window is reclaimed (no grace/ended path).
        service.flushOnce(activeAt + SessionAggregationService.TTL_NANOS + 1);
        assertThat(service.liveSessionCount()).as("stale slot window swept by TTL").isEqualTo(0);
    }

    @Test
    @DisplayName("evictGroup drops the slot window on group stop")
    void evictGroup_dropsSlotWindow() {
        setBotMdc();
        service.recordSpin(SlotSessionStrategy.INSTANCE, "slotA", 100L);
        service.recordSpinResult(10L, false);
        assertThat(service.liveSessionCount()).isEqualTo(1);

        service.evictGroup(GROUP_ID);
        assertThat(service.liveSessionCount()).isEqualTo(0);

        // A flush after eviction is a clean no-op.
        service.flushOnce(System.nanoTime());
        assertThat(slotLines()).isEmpty();
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
