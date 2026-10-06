package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AVIATOR_BOT QA: {@code bot_crash_bets_total} pre-registration under the <b>full</b> MDC a
 * running bot carries (not the four keys {@code BotMetricsCrashSeriesTest} sets), and
 * isolation between groups — an increment for one group must never touch, or be absorbed
 * by, another group's pre-registered series.
 */
@DisplayName("bot_crash_bets_total tags (QA)")
class BotMetricsCrashSeriesTagsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BotMetrics metrics = new BotMetrics(registry);

    @AfterEach
    void tearDown() {
        BotMdc.clear();
    }

    private static Set<Tag> tags(Counter c) {
        return new HashSet<>(c.getId().getTags());
    }

    @Test
    @DisplayName("under the full bot MDC, every increment lands on a pre-registered series with an identical tag set")
    void fullMdcSameTagSet() {
        BotMdc.set("group-a", 7, "env-119", "119", "CRASH", "g-aviator", "Aviator", "crashbot7");
        metrics.initCrashSeries();
        Set<Set<Tag>> preRegistered = new HashSet<>();
        registry.find(BotMetrics.BOT_CRASH_BETS_TOTAL).counters().forEach(c -> preRegistered.add(tags(c)));
        assertThat(preRegistered).hasSize(3);

        for (String outcome : BotMetrics.CRASH_OUTCOMES) {
            metrics.incCrashOutcome(outcome);
        }

        assertThat(registry.find(BotMetrics.BOT_CRASH_BETS_TOTAL).counters()).hasSize(3)
                .allSatisfy(c -> {
                    assertThat(c.count()).isEqualTo(1.0);
                    assertThat(preRegistered).contains(tags(c));
                    assertThat(c.getId().getTag("botGroupId")).isEqualTo("group-a");
                    assertThat(c.getId().getTag("environmentId")).isEqualTo("env-119");
                });
    }

    @Test
    @DisplayName("two groups: each pre-registers its own three series, increments stay in their group")
    void groupsIsolated() {
        BotMdc.set("group-a", 1, "env-119", "119", "CRASH", "g-aviator", "Aviator", "a1");
        metrics.initCrashSeries();
        BotMdc.set("group-b", 1, "env-119", "119", "CRASH", "g-aviator", "Aviator", "b1");
        metrics.initCrashSeries();
        metrics.incCrashOutcome("crash");

        assertThat(registry.find(BotMetrics.BOT_CRASH_BETS_TOTAL).counters()).hasSize(6);
        assertThat(registry.find(BotMetrics.BOT_CRASH_BETS_TOTAL)
                .tags("botGroupId", "group-b", "outcome", "crash").counter().count()).isEqualTo(1.0);
        assertThat(registry.find(BotMetrics.BOT_CRASH_BETS_TOTAL)
                .tags("botGroupId", "group-a", "outcome", "crash").counter().count()).isZero();
    }

    @Test
    @DisplayName("the outcome vocabulary is exactly cashout|crash|unacked and disjoint from CASHOUT's series")
    void vocabulary() {
        assertThat(BotMetrics.CRASH_OUTCOMES).containsExactly("cashout", "crash", "unacked");
        assertThat(BotMetrics.BOT_CRASH_BETS_TOTAL).isNotEqualTo(BotMetrics.BOT_CASHOUT_BETS_TOTAL);
        BotMdc.set("group-a", 1, "env-119", "119", "CRASH", "g-aviator", "Aviator", "a1");
        metrics.initCrashSeries();
        assertThat(registry.find(BotMetrics.BOT_CASHOUT_BETS_TOTAL).counters())
                .as("crash pre-registration creates no CASHOUT series").isEmpty();
        assertThat(List.copyOf(BotMetrics.CRASH_OUTCOMES)).doesNotHaveDuplicates();
    }
}
