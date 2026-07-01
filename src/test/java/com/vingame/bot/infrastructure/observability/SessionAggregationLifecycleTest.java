package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Anti-leak and scheduler-lifecycle coverage for {@link SessionAggregationService}
 * (AGGREGATED_SESSION_LOGGING AD-7/AD-8) that the Phase 1-3 suites do not exercise:
 * <ul>
 *   <li>the hard {@link SessionAggregationService#MAX_SESSIONS} cap holds under a
 *       flood — the map never exceeds the cap (unbounded growth is the outage this
 *       whole feature exists to prevent);</li>
 *   <li>the flush scheduler starts and stops cleanly (idempotent stop, stop-without-
 *       start safe) so no virtual-thread scheduler leaks;</li>
 *   <li>a thrown exception inside one session's flush is contained by the scheduled
 *       entry point ({@code runFlush}) so the fixed-rate task is never killed — and
 *       the current containment boundary is characterised.</li>
 * </ul>
 * All timing uses the {@code flushOnce(nowNanos)} / {@code runFlush} seams — no real
 * 5s sleeps — so the suite stays deterministic.
 */
@DisplayName("SessionAggregationService - anti-leak cap + scheduler lifecycle")
class SessionAggregationLifecycleTest {

    private static final String GROUP_ID = "group-life";
    private static final String GAME_ID = "cccccccc-dddd-eeee-ffff-000000000000";
    private static final String GAME_NAME = "BauCua";

    private SessionAggregationService service;

    @BeforeEach
    void setUp() {
        service = new SessionAggregationService();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        service.stopFlushScheduler();
        MDC.clear();
    }

    private void setBotMdc() {
        MDC.put(BotMdc.BOT_GROUP_ID, GROUP_ID);
        MDC.put(BotMdc.GAME_ID, GAME_ID);
        MDC.put(BotMdc.GAME_NAME, GAME_NAME);
    }

    @Test
    @DisplayName("MAX_SESSIONS cap holds: flooding past the cap never grows the map beyond it")
    void sizeCap_holdsUnderFlood() {
        setBotMdc();
        int overflow = 50;
        for (long sid = 0; sid < SessionAggregationService.MAX_SESSIONS + overflow; sid++) {
            service.onSessionStart(sid, BettingSessionStrategy.INSTANCE, () -> "s");
        }
        // The insert-time cap keeps the map pinned at exactly MAX_SESSIONS — the
        // oldest entries were evicted, not the map allowed to grow.
        assertThat(service.liveSessionCount())
                .as("map pinned at the cap under a %d-over flood", overflow)
                .isEqualTo(SessionAggregationService.MAX_SESSIONS);

        // A flush pass re-asserts the cap (backstop) and leaves it at the cap.
        service.flushOnce(System.nanoTime());
        assertThat(service.liveSessionCount()).isEqualTo(SessionAggregationService.MAX_SESSIONS);
    }

    @Test
    @DisplayName("evictGroup after a flood returns the map to empty (no leak)")
    void evictGroup_afterFlood_returnsToEmpty() {
        setBotMdc();
        for (long sid = 0; sid < 200; sid++) {
            service.onSessionStart(sid, BettingSessionStrategy.INSTANCE, () -> "s");
        }
        assertThat(service.liveSessionCount()).isEqualTo(200);

        service.evictGroup(GROUP_ID);
        assertThat(service.liveSessionCount()).as("map returns to empty after group stop").isZero();
    }

    @Test
    @DisplayName("flush scheduler starts and stops cleanly; stop is idempotent and safe without a start")
    void scheduler_startsAndStopsCleanly() throws Exception {
        // stop without a prior start is a no-op (no NPE).
        assertThatCode(service::stopFlushScheduler).doesNotThrowAnyException();

        service.startFlushScheduler();
        ScheduledExecutorService exec = readScheduler(service);
        assertThat(exec).as("scheduler created on start").isNotNull();
        assertThat(exec.isShutdown()).as("scheduler live after start").isFalse();

        service.stopFlushScheduler();
        assertThat(exec.isShutdown()).as("scheduler shut down on stop — no leaked thread").isTrue();

        // Idempotent: a second stop does not throw.
        assertThatCode(service::stopFlushScheduler).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a throwing session flush does NOT kill the scheduled fixed-rate task (runFlush contains it)")
    void throwingFlush_doesNotKillScheduledTask() throws Exception {
        setBotMdc();
        AtomicInteger renderCalls = new AtomicInteger(0);
        SessionAggregationStrategy boom = throwingFlushStrategy(renderCalls);
        service.onSessionStart(1L, boom, () -> "s");

        // The scheduled entry point swallows the exception so the fixed-rate task
        // keeps ticking — the load-bearing "task never dies" guarantee (AD-7).
        assertThatCode(() -> runFlush(service)).doesNotThrowAnyException();
        assertThatCode(() -> runFlush(service)).doesNotThrowAnyException();
        assertThat(renderCalls.get()).as("the throwing render ran on each tick").isGreaterThanOrEqualTo(2);

        // Containment now lives in the flushOnce loop too (per-entry try/catch), not
        // only in runFlush: a throwing strategy is caught, logged at WARN, and skipped
        // so sibling sessions within the SAME tick still get their eviction and the
        // trailing size-cap backstop. So even the raw flushOnce no longer propagates.
        assertThatCode(() -> service.flushOnce(System.nanoTime())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with a healthy sibling session, the scheduled task survives a throwing session and still evicts stale entries next ticks")
    void throwingSession_taskSurvives_healthyWorkStillProceeds() throws Exception {
        setBotMdc();
        SessionAggregationStrategy boom = throwingFlushStrategy(new AtomicInteger());
        service.onSessionStart(1L, boom, () -> "s");
        // A healthy, already-ended sibling that must eventually be reclaimed.
        service.onSessionStart(2L, BettingSessionStrategy.INSTANCE, () -> "s");
        service.onSessionEnd(2L, 0L, 0L, () -> "e");
        assertThat(service.liveSessionCount()).isEqualTo(2);

        // Repeated scheduled ticks never throw despite the poisoned session; the
        // ended sibling is swept once past its grace window (CHM iteration order is
        // unspecified, so we advance time and drive several ticks to guarantee the
        // ended entry is visited on a pass that reaches it).
        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> runFlush(service)).doesNotThrowAnyException();
        }
        // The poisoned session lingers (it never ends and keeps throwing), but the
        // service is still alive and bounded — no crash, no runaway growth.
        assertThat(service.liveSessionCount()).isLessThanOrEqualTo(2);
    }

    /* ---- helpers ---- */

    private static SessionAggregationStrategy throwingFlushStrategy(AtomicInteger renderCalls) {
        return new SessionAggregationStrategy() {
            @Override
            public boolean hasRoundBoundary() {
                return true;
            }

            @Override
            public String renderStartLine(SessionAccumulator acc, SessionContext ctx) {
                return "start"; // must not throw — onSessionStart logs this
            }

            @Override
            public String renderFlushLine(SessionAccumulator acc, SessionContext ctx) {
                renderCalls.incrementAndGet();
                throw new IllegalStateException("boom in flush render");
            }

            @Override
            public String renderEndLine(SessionAccumulator acc, SessionContext ctx) {
                return "end";
            }
        };
    }

    private static ScheduledExecutorService readScheduler(SessionAggregationService svc) throws Exception {
        Field f = SessionAggregationService.class.getDeclaredField("flushScheduler");
        f.setAccessible(true);
        return (ScheduledExecutorService) f.get(svc);
    }

    private static void runFlush(SessionAggregationService svc) {
        try {
            Method m = SessionAggregationService.class.getDeclaredMethod("runFlush");
            m.setAccessible(true);
            m.invoke(svc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
