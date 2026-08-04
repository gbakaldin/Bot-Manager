package com.vingame.bot.infrastructure.runtime;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 (OBSERVABILITY): verifies that {@link BotGroupRuntime} accumulates
 * group-level DEAD downtime into {@code group_dead_seconds_total} via
 * {@link BotMetrics}, and that the {@link BotGroupRuntime#stopAllBots(BotMetrics)}
 * close-out path credits the open window exactly once.
 */
@DisplayName("BotGroupRuntime dead-seconds accumulator (Phase 3)")
class BotGroupRuntimeDeadSecondsTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("markAsDead stamps groupDeadSince and is idempotent on re-entry")
    void markAsDeadStampsAndIsIdempotent() {
        BotGroupRuntime runtime = new BotGroupRuntime("g1", 0, "env-1");
        try {
            assertThat(runtime.getGroupDeadSince()).isNull();

            runtime.markAsDead();
            Instant first = runtime.getGroupDeadSince();
            assertThat(first).isNotNull();
            assertThat(runtime.getActualStatus()).isEqualTo(BotGroupStatus.DEAD);

            // Re-entry must NOT advance the stamp — preserves the original DEAD entry time.
            runtime.markAsDead();
            assertThat(runtime.getGroupDeadSince()).isEqualTo(first);
        } finally {
            runtime.getExecutor().shutdownNow();
        }
    }

    @Test
    @DisplayName("stopAllBots(metrics) credits the open DEAD window and clears the stamp")
    void stopAllBotsCreditsDeadWindow() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        BotMetrics metrics = new BotMetrics(registry);

        BotGroupRuntime runtime = new BotGroupRuntime("g-deadtest", 0, "env-1");
        try {
            // Set group MDC so the counter is tagged with botGroupId and environmentId.
            BotMdc.setGroupContext(runtime.getGroupId(), runtime.getEnvironmentId());

            runtime.markAsDead();
            // Backdate the stamp 5s to avoid sleeping; toSeconds() truncates.
            setGroupDeadSince(runtime, Instant.now().minusSeconds(5));

            runtime.stopAllBots(metrics);

            Counter c = registry.find(BotMetrics.GROUP_DEAD_SECONDS_TOTAL).counter();
            assertThat(c).as("group_dead_seconds_total must be registered after a DEAD-window close-out")
                    .isNotNull();
            assertThat(c.count()).isGreaterThanOrEqualTo(5.0).isLessThan(15.0);
            assertThat(runtime.getGroupDeadSince()).isNull();
        } finally {
            // executor already shut down by stopAllBots
        }
    }

    @Test
    @DisplayName("stopAllBots(metrics) on a never-DEAD group does NOT register the counter")
    void stopAllBotsNoDeadWindowDoesNotCredit() {
        MeterRegistry registry = new SimpleMeterRegistry();
        BotMetrics metrics = new BotMetrics(registry);

        BotGroupRuntime runtime = new BotGroupRuntime("g-clean", 0, "env-1");
        // Never markAsDead() — groupDeadSince stays null.

        runtime.stopAllBots(metrics);

        Counter c = registry.find(BotMetrics.GROUP_DEAD_SECONDS_TOTAL).counter();
        assertThat(c).as("a group that never entered DEAD must not register the counter")
                .isNull();
    }

    @Test
    @DisplayName("stopAllBots(null) is the no-metrics overload — counter not registered")
    void stopAllBotsNullMetricsSkipsIncrement() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();

        BotGroupRuntime runtime = new BotGroupRuntime("g-nullm", 0, "env-1");
        runtime.markAsDead();
        setGroupDeadSince(runtime, Instant.now().minusSeconds(5));

        runtime.stopAllBots(); // null-metrics overload

        Counter c = registry.find(BotMetrics.GROUP_DEAD_SECONDS_TOTAL).counter();
        assertThat(c).isNull();
        // Stamp is still cleared so a follow-up call with metrics doesn't double-credit
        assertThat(runtime.getGroupDeadSince()).isNull();
    }

    @Test
    @DisplayName("Double stopAllBots(metrics) credits exactly once — second call is a no-op")
    void doubleStopDoesNotDoubleCredit() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        BotMetrics metrics = new BotMetrics(registry);

        BotGroupRuntime runtime = new BotGroupRuntime("g-dbl", 0, "env-1");
        runtime.markAsDead();
        setGroupDeadSince(runtime, Instant.now().minusSeconds(4));

        runtime.stopAllBots(metrics);
        Counter first = registry.find(BotMetrics.GROUP_DEAD_SECONDS_TOTAL).counter();
        double afterFirst = first == null ? 0.0 : first.count();

        runtime.stopAllBots(metrics); // executor already shut down; should be safe

        Counter second = registry.find(BotMetrics.GROUP_DEAD_SECONDS_TOTAL).counter();
        double afterSecond = second == null ? 0.0 : second.count();
        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    private static void setGroupDeadSince(BotGroupRuntime runtime, Instant when) throws Exception {
        Field f = BotGroupRuntime.class.getDeclaredField("groupDeadSince");
        f.setAccessible(true);
        f.set(runtime, when);
    }
}
