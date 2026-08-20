package com.vingame.bot.infrastructure.logging;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Registers the {@code log4j2_async_queue_*} meters exactly as {@link AsyncQueueMetrics}
 * does at startup, for tests that live in another package.
 * <p>
 * {@code AlertRuleMetricsTest} (in {@code ...infrastructure.observability}) has to render
 * the exact exposition production produces before it can check {@code LogQueueSaturated}
 * against it. That is the only cross-package need, and it is not a reason to make
 * {@code AsyncQueueMetrics.track} public: it mutates a Spring singleton and has one
 * production caller, so it stays package-private like {@code InfoGaugeRefresher}'s
 * {@code registerInfoGauges}. The seam is this fixture instead — test code, in the same
 * package as the class it exercises, so widening the production API is unnecessary.
 */
public final class AsyncQueueMeterFixture {

    private AsyncQueueMeterFixture() {
    }

    /**
     * Register the gauges and counters for every {@code AsyncAppender} in the build's live
     * log4j2 configuration, against the given registry. No scheduler is started.
     */
    public static void registerAgainstRunningContext(MeterRegistry registry) {
        new AsyncQueueMetrics(registry).track(AsyncQueueMetrics.asyncAppenderNames());
    }
}
