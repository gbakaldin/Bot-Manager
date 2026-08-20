package com.vingame.bot.infrastructure.logging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOG_VOLUME_TIERING AD-25(4) — the drop that nothing could see.
 * <p>
 * Track 2 ships {@code blocking = false}, so a full {@code AsyncDetail} queue discards the
 * event at every level. That is the right trade, but log4j2 reports it only through
 * {@code DefaultErrorHandler}: three messages, then one per five minutes, uncounted, on
 * {@code System.err} — into the capped docker json-file, never into {@code console.log},
 * never into Loki, never into the detail file it is reporting on. Since Phase 4 track 2 is
 * the sole home of wire-level forensics, an engineer can read {@code detail.log} after an
 * incident with no way to know it has holes in it.
 * <p>
 * These tests pin the replacement: two gauges, two counters, and a throttled WARN that
 * carries running totals and travels track 1 (i.e. reaches Loki). They deliberately do not
 * fill a real 16,384-entry queue — that is a load test — so the classification and the
 * throttle are driven through {@link AsyncQueueMetrics#record} while discovery and the
 * gauges are exercised against the real {@code LoggerContext} the module runs on.
 */
@DisplayName("AD-25(4) — a silently lossy detail queue is countable and alertable")
class AsyncQueueMetricsTest {

    private static final List<Path> ALERTS = List.of(
            Path.of("..", "prometheus", "alerts.yml"),
            Path.of("prometheus", "alerts.yml"));

    private MeterRegistry registry;
    private AsyncQueueMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AsyncQueueMetrics(registry);
    }

    @Test
    @DisplayName("both async appenders are discovered from the live configuration")
    void theTwoTracksAreFound() {
        assertThat(AsyncQueueMetrics.asyncAppenderNames())
                .as("the sampler discovers appenders by type rather than by a hard-coded pair, "
                        + "so a third async appender is instrumented the day it is added")
                .contains("AsyncRolling", "AsyncDetail");
    }

    @Test
    @DisplayName("the gauges read the live queue, and say so when the appender is gone")
    void theGaugesResolveTheAppenderOnEveryRead() {
        metrics.track(List.of("AsyncDetail", "NoSuchAppender"));

        Gauge remaining = registry.find(AsyncQueueMetrics.REMAINING)
                .tag("appender", "AsyncDetail").gauge();
        Gauge capacity = registry.find(AsyncQueueMetrics.CAPACITY)
                .tag("appender", "AsyncDetail").gauge();
        assertThat(remaining).isNotNull();
        assertThat(capacity).isNotNull();
        assertThat(capacity.value())
                .as("the configured buffer, so an alert can be a ratio rather than a "
                        + "hard-coded 8192/16384 that silently stops matching when retuned")
                .isGreaterThan(0);
        assertThat(remaining.value())
                .as("an idle queue is empty, so free slots equal the buffer")
                .isEqualTo(capacity.value());

        assertThat(registry.find(AsyncQueueMetrics.REMAINING)
                .tag("appender", "NoSuchAppender").gauge().value())
                .as("-1 rather than 0: an unreadable queue must not look like a full one, "
                        + "and 'the appender vanished' is itself the interesting fact")
                .isEqualTo(-1);
    }

    @Test
    @DisplayName("pressure is a fraction of the buffer, and an unreadable queue is not pressure")
    void theThresholdIsRelativeAndFailsOpen() {
        assertThat(AsyncQueueMetrics.underPressure(1_639, 16_384))
                .as("just over 10% of 16,384 free is not pressure").isFalse();
        assertThat(AsyncQueueMetrics.underPressure(1_638, 16_384))
                .as("at or under 10% free is").isTrue();
        assertThat(AsyncQueueMetrics.underPressure(0, 16_384))
                .as("a full queue is the case that drops events").isTrue();
        assertThat(AsyncQueueMetrics.underPressure(-1, -1))
                .as("a queue that could not be read is reported by the gauge as -1, not "
                        + "invented as pressure — firing the alert on the wrong fact is worse "
                        + "than not firing it")
                .isFalse();
    }

    @Test
    @DisplayName("a full queue moves both counters; a merely tight one moves only pressure")
    void theCountersSeparatePressureFromLoss() {
        metrics.track(List.of("AsyncDetail"));
        long now = 1_000_000L;

        metrics.record("AsyncDetail", 8_000, 16_384, now);
        assertThat(count(AsyncQueueMetrics.PRESSURE_SAMPLES)).isZero();

        metrics.record("AsyncDetail", 100, 16_384, now);
        assertThat(count(AsyncQueueMetrics.PRESSURE_SAMPLES)).isEqualTo(1);
        assertThat(count(AsyncQueueMetrics.FULL_SAMPLES))
                .as("nearly full is not yet lossy — this is the early warning, not the loss")
                .isZero();

        metrics.record("AsyncDetail", 0, 16_384, now);
        assertThat(count(AsyncQueueMetrics.PRESSURE_SAMPLES)).isEqualTo(2);
        assertThat(count(AsyncQueueMetrics.FULL_SAMPLES))
                .as("on AsyncDetail (blocking=false) a full queue IS dropped events")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the WARN is throttled to one per five minutes but the counters never are")
    void theWarnIsRateLimitedAndTheCountIsNot() {
        metrics.track(List.of("AsyncDetail"));
        long start = 1_000_000L;
        long interval = TimeUnit.MINUTES.toMillis(AsyncQueueMetrics.WARN_INTERVAL_MINUTES);

        assertThat(metrics.warnIfDue("AsyncDetail", 0, 16_384, 1, 1, start))
                .as("the first saturation is reported immediately").isTrue();
        assertThat(metrics.warnIfDue("AsyncDetail", 0, 16_384, 2, 2, start + interval - 1))
                .as("and then throttled, so a drop storm cannot itself become the flood")
                .isFalse();
        assertThat(metrics.warnIfDue("AsyncDetail", 0, 16_384, 3, 3, start + interval))
                .as("but it recurs, carrying the running totals — which is the part "
                        + "log4j2's own status-logger note never gives you")
                .isTrue();

        for (int i = 0; i < 50; i++) {
            metrics.record("AsyncDetail", 0, 16_384, start);
        }
        assertThat(count(AsyncQueueMetrics.FULL_SAMPLES))
                .as("every sample is counted even while the WARN is silent: the metric is "
                        + "the record, the WARN is only the pointer")
                .isEqualTo(50);
    }

    @Test
    @DisplayName("prometheus/alerts.yml alerts on the metrics this class publishes")
    void theAlertRuleAndTheMeterNamesAgree() {
        String rules = readAlerts();
        assertThat(rules)
                .as("a gauge nobody alerts on is the same silent loss in a different place; "
                        + "rename the meter and this fails rather than the alert going quiet")
                .contains(AsyncQueueMetrics.REMAINING)
                .contains(AsyncQueueMetrics.CAPACITY);
        assertThat(rules)
                .as("and the rule must survive the audience guard, i.e. carry a label block")
                .contains("alert: LogQueueSaturated");
    }

    private double count(String name) {
        return registry.find(name).tag("appender", "AsyncDetail").counter().count();
    }

    private static String readAlerts() {
        Path path = ALERTS.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        Assumptions.assumeTrue(path != null,
                "prometheus/alerts.yml not found from " + Path.of("").toAbsolutePath());
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
