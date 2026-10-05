package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AVIATOR_BOT AD-11: {@code bot_crash_bets_total} is pre-registered at zero for all three
 * outcomes under the same MDC tags the increments use — otherwise the first {@code unacked}
 * would be invisible to {@code increase()} (the {@code group_recovery_*} trap) or would land
 * on a second series.
 */
@DisplayName("BotMetrics — bot_crash_bets_total series (AD-11)")
class BotMetricsCrashSeriesTest {

    private SimpleMeterRegistry registry;
    private BotMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new BotMetrics(registry);
        MDC.put(BotMdc.BOT_GROUP_ID, "g-crash");
        MDC.put(BotMdc.ENVIRONMENT_ID, "env-119");
        MDC.put(BotMdc.GAME_TYPE, "CRASH");
        MDC.put(BotMdc.GAME_NAME, "Aviator");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private Collection<Counter> series() {
        return registry.find(BotMetrics.BOT_CRASH_BETS_TOTAL).counters();
    }

    @Test
    @DisplayName("initCrashSeries materialises cashout, crash and unacked at 0, idempotently")
    void preRegistersAtZero() {
        metrics.initCrashSeries();
        metrics.initCrashSeries();

        assertThat(series()).hasSize(3);
        assertThat(series()).allSatisfy(c -> assertThat(c.count()).isZero());
        assertThat(series()).extracting(c -> c.getId().getTag("outcome"))
                .containsExactlyInAnyOrder("cashout", "crash", "unacked");
    }

    @Test
    @DisplayName("increments land on the pre-registered series, not a second one, with the MDC group tag")
    void incrementsHitTheSameSeries() {
        metrics.initCrashSeries();

        metrics.incCrashOutcome("unacked");
        metrics.incCrashOutcome("unacked");
        metrics.incCrashOutcome("crash");

        assertThat(series()).hasSize(3);
        assertThat(registry.find(BotMetrics.BOT_CRASH_BETS_TOTAL)
                .tags("outcome", "unacked", "botGroupId", "g-crash").counter().count()).isEqualTo(2.0);
        assertThat(registry.find(BotMetrics.BOT_CRASH_BETS_TOTAL)
                .tags("outcome", "crash").counter().count()).isEqualTo(1.0);
        assertThat(registry.find(BotMetrics.BOT_CRASH_BETS_TOTAL)
                .tags("outcome", "cashout").counter().count()).isZero();
    }

    @Test
    @DisplayName("no per-bot tag is added")
    void noPerBotTag() {
        MDC.put(BotMdc.BOT_ID, "bot-1");
        metrics.initCrashSeries();

        assertThat(series()).allSatisfy(c -> assertThat(c.getId().getTag(BotMdc.BOT_ID)).isNull());
    }
}
