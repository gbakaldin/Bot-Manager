package com.vingame.bot.infrastructure.logging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Makes the two async logging queues observable, because one of them drops events
 * silently (LOG_VOLUME_TIERING AD-25(4)).
 * <p>
 * <b>Why this exists.</b> Track 2 ({@code AsyncDetail}) ships {@code blocking = false}:
 * when its 16,384-entry queue is full, {@code AsyncAppender.append} drops the event at
 * <em>every</em> level, INFO and WARN included. That is the right trade — track 2 carries
 * ~1,900 lines/s at 20k bots and blocking would park a bot thread — but "best-effort" is
 * only an acceptable contract when you can tell whether the effort succeeded. What log4j2
 * gives you on its own is not enough: the drop calls {@code error(...)} →
 * {@code DefaultErrorHandler.acquirePermit()}, which allows <b>three</b> messages and then
 * <b>one per five minutes</b>, carries no count of what was lost, and writes to
 * {@code System.err} — i.e. into the capped docker json-file, never into
 * {@code console.log}, never into Loki, never into the detail file it is reporting on, and
 * invisible to Alertmanager. During the overload incident that the wire-level tier exists
 * to explain, track 2 could be 80% lossy and the only record would be a handful of
 * unlabelled stderr lines rotating out of {@code docker logs}.
 * <p>
 * <b>What is measured, and what is not.</b> This samples queue occupancy; it does not count
 * dropped events, and that is a limitation of the library rather than a choice. An exact
 * count would need either a custom {@code ErrorHandler}
 * ({@code AbstractAppender.setHandler} refuses once the appender is started — verified in
 * log4j-core 2.24.1) or an {@code errorRef} appender resolved at configuration time, which
 * this project cannot register because {@code annotationProcessorPaths} is pinned and
 * log4j2's plugin processor never runs (the same constraint that shaped AD-9). Saturation
 * is the precondition for every drop, and a drop storm worth investigating is by definition
 * sustained, so a 10 s sample is a faithful detector of the failure mode even though a
 * sub-sample burst can slip between two reads.
 * <ul>
 *   <li>{@code log4j2_async_queue_remaining{appender}} — free slots, read live from the
 *       running configuration at scrape time, so a reconfiguration cannot leave the gauge
 *       pointing at a stopped appender. {@code -1} means the appender is not currently in
 *       the configuration at all, which is itself the interesting fact.</li>
 *   <li>{@code log4j2_async_queue_capacity{appender}} — the configured buffer, so an alert
 *       can be written as a ratio rather than against a hard-coded 8,192 / 16,384.</li>
 *   <li>{@code log4j2_async_queue_pressure_samples_total{appender}} — samples that found
 *       under {@value #PRESSURE_FRACTION} of the buffer free. Early warning.</li>
 *   <li>{@code log4j2_async_queue_full_samples_total{appender}} — samples that found the
 *       queue completely full. On {@code AsyncDetail} this is data loss; on
 *       {@code AsyncRolling}, whose {@code blocking = true}, it is bot threads parked on a
 *       queue put, which is the opposite pathology and equally worth seeing.</li>
 * </ul>
 * The WARN line exists alongside the counters for the reason the finding names: metrics are
 * scraped, but the engineer reading {@code detail.log} afterwards needs to know the file
 * has holes in it. It is emitted at most once per {@value #WARN_INTERVAL_MINUTES} minutes
 * per appender, carries the running totals rather than a bare "queue is full", and — being
 * a {@code com.vingame.bot} WARN — lands in track 1, in Loki and in Grafana, which is
 * precisely where log4j2's own status-logger note does not go.
 * <p>
 * Meter names are deliberately not {@code bot_}-prefixed: {@code BotMdcTagsMeterFilter}
 * would otherwise stamp them with whatever MDC the sampler thread inherited, and these are
 * JVM-wide facts with no owning group.
 */
@Slf4j
@Component
public class AsyncQueueMetrics {

    static final String REMAINING = "log4j2_async_queue_remaining";
    static final String CAPACITY = "log4j2_async_queue_capacity";
    static final String PRESSURE_SAMPLES = "log4j2_async_queue_pressure_samples_total";
    static final String FULL_SAMPLES = "log4j2_async_queue_full_samples_total";

    /** Sampling cadence, seconds. Matched to the Prometheus scrape interval. */
    static final long SAMPLE_INTERVAL_SECONDS = 10;

    /** Below this fraction of free slots a sample counts as "under pressure". */
    static final double PRESSURE_FRACTION = 0.10;

    /** Upper bound on the WARN rate, per appender. */
    static final long WARN_INTERVAL_MINUTES = 5;

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> lastWarnedAt = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> pressureSamples = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> fullSamples = new ConcurrentHashMap<>();
    private volatile List<String> tracked = List.of();
    private ScheduledExecutorService sampler;

    public AsyncQueueMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void start() {
        List<String> appenders = asyncAppenderNames();
        if (appenders.isEmpty()) {
            // A configuration with no AsyncAppender at all is a valid (if unexpected)
            // state — a host running the in-jar fallback of an older config, say. Say so
            // once and do nothing; a silent no-op here would look like a healthy queue.
            log.warn("No AsyncAppender in the running log4j2 configuration — "
                    + "logging-queue metrics are not being published");
            return;
        }
        track(appenders);
        sampler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("log4j2-queue-sampler").factory());
        sampler.scheduleAtFixedRate(this::sampleQuietly, SAMPLE_INTERVAL_SECONDS,
                SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("Logging-queue metrics started for {} (interval={}s)",
                appenders, SAMPLE_INTERVAL_SECONDS);
    }

    @PreDestroy
    void stop() {
        if (sampler != null) {
            sampler.shutdownNow();
        }
    }

    /**
     * Register the meters for a set of appenders and start counting against them,
     * without the scheduler. Package-private so tests drive {@link #sample(long)} and
     * {@link #record} deterministically.
     */
    void track(List<String> appenders) {
        appenders.forEach(this::registerMeters);
        tracked = appenders;
    }

    private void registerMeters(String appender) {
        pressureSamples.computeIfAbsent(appender, name -> new AtomicLong());
        fullSamples.computeIfAbsent(appender, name -> new AtomicLong());
        lastWarnedAt.computeIfAbsent(appender, name -> new AtomicLong());
        Gauge.builder(REMAINING, appender, AsyncQueueMetrics::remainingCapacity)
                .strongReference(true)
                .tag("appender", appender)
                .description("Free slots in the log4j2 async appender's queue (-1 if the appender is gone)")
                .register(registry);
        Gauge.builder(CAPACITY, appender, AsyncQueueMetrics::queueCapacity)
                .strongReference(true)
                .tag("appender", appender)
                .description("Configured size of the log4j2 async appender's queue")
                .register(registry);
        // Registered eagerly so the series exists at zero: an alert on a counter that
        // only appears once the incident starts cannot be tested before the incident.
        Counter.builder(PRESSURE_SAMPLES)
                .tag("appender", appender)
                .description("Samples that found less than 10% of the async queue free")
                .register(registry);
        Counter.builder(FULL_SAMPLES)
                .tag("appender", appender)
                .description("Samples that found the async queue completely full — on AsyncDetail (blocking=false) this is dropped log events")
                .register(registry);
    }

    private void sampleQuietly() {
        try {
            sample(System.currentTimeMillis());
        } catch (Exception e) {
            log.error("Logging-queue sampling failed: {}", e.getMessage());
        }
    }

    /**
     * One sampling pass. Package-private and clock-injected so tests drive it directly
     * rather than waiting on the scheduler.
     */
    void sample(long nowMillis) {
        for (String appender : tracked) {
            Optional<AsyncAppender> resolved = resolve(appender);
            if (resolved.isEmpty()) {
                continue;
            }
            record(appender, resolved.get().getQueueRemainingCapacity(),
                    resolved.get().getQueueCapacity(), nowMillis);
        }
    }

    /**
     * Classify and record one reading. Separated from {@link #sample(long)} so the
     * thresholds are testable without a saturated appender — filling a real 16,384-entry
     * queue is a load test, not a unit test.
     */
    void record(String appender, int remaining, int capacity, long nowMillis) {
        if (!underPressure(remaining, capacity)) {
            return;
        }
        long pressure = pressureSamples.get(appender).incrementAndGet();
        registry.counter(PRESSURE_SAMPLES, "appender", appender).increment();
        long full = fullSamples.get(appender).get();
        if (remaining <= 0) {
            full = fullSamples.get(appender).incrementAndGet();
            registry.counter(FULL_SAMPLES, "appender", appender).increment();
        }
        warnIfDue(appender, remaining, capacity, pressure, full, nowMillis);
    }

    /**
     * A reading counts as pressured when under {@value #PRESSURE_FRACTION} of the buffer
     * is free. A non-positive capacity means the queue could not be read (an appender
     * that has gone) and is deliberately not reported as pressure — an unreadable queue
     * is what the {@code -1} gauge says, and inventing pressure from it would fire the
     * alert on the wrong fact.
     */
    static boolean underPressure(int remaining, int capacity) {
        return capacity > 0 && remaining <= capacity * PRESSURE_FRACTION;
    }

    /** @return true if this reading produced a WARN, false if the throttle swallowed it. */
    boolean warnIfDue(String appender, int remaining, int capacity,
                      long pressure, long full, long nowMillis) {
        AtomicLong last = lastWarnedAt.get(appender);
        long previous = last.get();
        long interval = TimeUnit.MINUTES.toMillis(WARN_INTERVAL_MINUTES);
        if (previous != 0 && nowMillis - previous < interval) {
            return false;
        }
        if (!last.compareAndSet(previous, nowMillis)) {
            return false;
        }
        log.warn("Logging queue {} under pressure: {}/{} slots free "
                        + "({} pressured samples, {} full samples at {}s intervals since start). "
                        + "A full AsyncDetail queue DROPS events at every level (blocking=false, "
                        + "AD-25(4)) — treat logs/detail/detail.log as incomplete for this window; "
                        + "a full AsyncRolling queue blocks the calling bot thread instead.",
                appender, remaining, capacity, pressure, full, SAMPLE_INTERVAL_SECONDS);
        return true;
    }

    private static double remainingCapacity(String appender) {
        return resolve(appender).map(AsyncAppender::getQueueRemainingCapacity).orElse(-1);
    }

    private static double queueCapacity(String appender) {
        return resolve(appender).map(AsyncAppender::getQueueCapacity).orElse(-1);
    }

    /**
     * Resolve by name from the live configuration on every read. A reconfiguration
     * replaces appender instances, so caching the object would leave these meters
     * reporting a stopped queue that can never fill — the failure mode that looks most
     * like health.
     */
    private static Optional<AsyncAppender> resolve(String appender) {
        Appender found = context().getConfiguration().getAppender(appender);
        return found instanceof AsyncAppender async ? Optional.of(async) : Optional.empty();
    }

    static List<String> asyncAppenderNames() {
        return context().getConfiguration().getAppenders().values().stream()
                .filter(AsyncAppender.class::isInstance)
                .map(Appender::getName)
                .sorted()
                .toList();
    }

    private static LoggerContext context() {
        return (LoggerContext) LogManager.getContext(false);
    }
}
