package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * The per-environment sliding-window budget (GATEWAY_REQUEST_BUDGET AD-4).
 * <p>
 * A deque of monotonic acquisition stamps; {@code W} is the number of stamps younger than
 * {@code settings.window()}. This is the shape of the reference limiter that was written in
 * Python after the 2026-09-17 block ({@code scripts/bulk-create-accounts.py}), moved into
 * the app and made per-environment.
 * <p>
 * <b>What this class does in Phase 1, and what it deliberately does not.</b> It counts, it
 * publishes, and it logs. It does <b>not</b> queue, wait, refuse or open a circuit — there
 * are no waiter queues here yet, so {@link #execute} is exactly "stamp, then run", for both
 * modes. That is the whole point of shipping observe first: the only behaviour change on a
 * production fleet is that {@code W} becomes observable, which is what tells us whether the
 * ceilings the plan chose are the right ones before anything starts depending on them.
 * Enforcement (three waiter queues under one lock, a {@code CompletableFuture} per waiter,
 * one admission pass walking ESSENTIAL → PRIORITIZED → DEFAULT, wake-ups scheduled at the
 * earliest stamp expiry) lands in Phase 3, and the Cloudflare circuit breaker in Phase 4.
 * <p>
 * <b>Stamp on admission, not on completion.</b> A request that was admitted and then failed
 * still cost the edge a request; a request that timed out or was cancelled while queued
 * cost it nothing and is never stamped. The {@code gateway_budget_requests_total} counter
 * and {@code gateway_budget_window_requests} gauge must agree on that, or the dashboard
 * lies about the one number this feature exists to bound.
 * <p>
 * <b>The clock is injected</b> ({@code LongSupplier nanos}, the {@code ScopedDebugRegistry}
 * seam) so every timing test is deterministic and nothing in the suite sleeps. Never
 * {@code System.currentTimeMillis} — the window must not move when the wall clock does.
 * <p>
 * Thread-safe: all window state is guarded by one {@link ReentrantLock}. The per-tier
 * counters are atomics read by gauges on the scrape thread.
 */
@Slf4j
public class SlidingWindowGatewayBudget implements GatewayBudget {

    // --- meter names (AD-20). Not bot_-prefixed, so BotMdcTagsMeterFilter leaves them
    // alone, and tagged EXPLICITLY with {environmentId, product} rather than through
    // BotMetrics.mdcTags(): these are environment-scoped and are published from threads
    // that have no bot MDC (the scrape thread, the probe scheduler).
    public static final String WINDOW_REQUESTS = "gateway_budget_window_requests";
    public static final String QUEUE_DEPTH = "gateway_budget_queue_depth";
    public static final String RESERVED = "gateway_budget_reserved";
    public static final String REQUESTS_TOTAL = "gateway_budget_requests_total";
    public static final String WAIT_TIMER = "gateway_budget_wait";

    /** Bounded {@code outcome} label values of {@link #REQUESTS_TOTAL}. */
    public static final String OUTCOME_ADMITTED = "admitted";
    public static final String OUTCOME_TIMEOUT = "timeout";
    public static final String OUTCOME_CANCELLED = "cancelled";
    public static final String OUTCOME_CIRCUIT_OPEN = "circuit_open";

    private static final String[] OUTCOMES =
            {OUTCOME_ADMITTED, OUTCOME_TIMEOUT, OUTCOME_CANCELLED, OUTCOME_CIRCUIT_OPEN};

    private final String environmentId;
    private final String environmentName;
    private final String productCode;
    private final GatewayBudgetSettings settings;
    private final LongSupplier nanos;

    /** Acquisition stamps, oldest first. Guarded by {@link #lock}. */
    private final ArrayDeque<Long> stamps = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();

    private final Map<RequestTier, AtomicInteger> queued = new EnumMap<>(RequestTier.class);
    private final Map<RequestTier, AtomicInteger> reserved = new EnumMap<>(RequestTier.class);
    private final Map<RequestTier, Timer> waitTimers = new EnumMap<>(RequestTier.class);
    /** Every {@code {tier, outcome}} counter, resolved once at construction. */
    private final Map<String, Counter> outcomeCounters = new HashMap<>();

    /**
     * Circuit state. Always closed in Phase 1 — the detector and the state machine land in
     * Phase 4. Present now so {@link #snapshot()} (and therefore the rollup line) has its
     * final shape and an operator reading {@code circuit=closed} is reading a real field.
     */
    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);

    public SlidingWindowGatewayBudget(String environmentId,
                                      String environmentName,
                                      String productCode,
                                      GatewayBudgetSettings settings,
                                      MeterRegistry registry,
                                      LongSupplier nanos) {
        this.environmentId = environmentId;
        this.environmentName = environmentName;
        this.productCode = productCode;
        this.settings = settings;
        this.nanos = nanos;
        for (RequestTier tier : RequestTier.values()) {
            queued.put(tier, new AtomicInteger());
            reserved.put(tier, new AtomicInteger());
        }
        registerMeters(registry);
    }

    /**
     * Materialise every series this budget will ever publish, at zero, the moment the budget
     * is created.
     * <p>
     * The counters are pre-registered for the same reason
     * {@code BotMetrics.initGroupRecoverySeries} exists: a Micrometer counter registered
     * lazily at increment time first appears at {@code 1} and stays at {@code 1}, and
     * {@code increase()} over samples that are all {@code 1} is {@code last - first = 0}
     * with Prometheus' counter-start extrapolation gated on {@code resultValue > 0}. A rule
     * or panel over a lazily-registered counter therefore reads zero through the first
     * occurrence of exactly the thing it is watching for. Twelve series per environment
     * (3 tiers × 4 outcomes) is the price and it is bounded.
     */
    private void registerMeters(MeterRegistry registry) {
        Tags tags = tags();

        Gauge.builder(WINDOW_REQUESTS, this, SlidingWindowGatewayBudget::windowRequests)
                .description("Requests admitted to this environment's gateway in the current sliding window")
                .tags(tags)
                .register(registry);

        for (RequestTier tier : RequestTier.values()) {
            Tags tierTags = tags.and("tier", tier.name());

            Gauge.builder(QUEUE_DEPTH, queued.get(tier), AtomicInteger::get)
                    .description("Requests waiting for admission at this tier")
                    .tags(tierTags)
                    .register(registry);

            Gauge.builder(RESERVED, reserved.get(tier), AtomicInteger::get)
                    .description("Outstanding declared demand at this tier")
                    .tags(tierTags)
                    .register(registry);

            waitTimers.put(tier, Timer.builder(WAIT_TIMER)
                    .description("Time a request waited for admission at this tier")
                    .serviceLevelObjectives(
                            Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(60),
                            Duration.ofSeconds(300), Duration.ofSeconds(600))
                    .tags(tierTags)
                    .register(registry));

            for (String outcome : OUTCOMES) {
                outcomeCounters.put(counterKey(tier, outcome), Counter.builder(REQUESTS_TOTAL)
                        .description("Gateway requests by tier and admission outcome")
                        .tags(tierTags.and("outcome", outcome))
                        .register(registry));
            }
        }
    }

    private Tags tags() {
        return Tags.of("environmentId", nullSafe(environmentId), "product", nullSafe(productCode));
    }

    // ------------------------------------------------------------------ the funnel

    @Override
    public <T> T execute(RequestTier tier, GatewayRequestScope scope, Callable<T> call) throws Exception {
        admit(tier, scope);
        return call.call();
    }

    @Override
    public void run(RequestTier tier, GatewayRequestScope scope, Runnable call) {
        admit(tier, scope);
        call.run();
    }

    @Override
    public void runWsUpgrade(RequestTier tier, GatewayRequestScope scope, Runnable upgrade) {
        if (settings.countWsUpgrades()) {
            admit(tier, scope);
        }
        upgrade.run();
    }

    @Override
    public <T> Optional<T> tryExecute(RequestTier tier, GatewayRequestScope scope,
                                      Callable<T> call, Duration maxWait) throws Exception {
        // Phase 1: admission is unconditional, so a try is an execute. From Phase 3 this is
        // where a DEFAULT drift read gives up immediately (maxWait = ZERO) rather than
        // parking a library message-processor thread (AD-10).
        admit(tier, scope);
        return Optional.ofNullable(call.call());
    }

    /**
     * Stamp the window for one admitted request and account for it.
     * <p>
     * In Phase 1 every request is admitted with a zero wait, in both modes. The wait timer
     * is still recorded — a series that only exists once something goes wrong is a series
     * nobody has a baseline for, and {@code gateway_budget_wait_seconds_max < 0.01} is the
     * release check that observe mode really never parked anything.
     * <p>
     * The DEBUG line carries no identity of its own: on every path that matters it runs on a
     * thread whose MDC the bot already populated ({@code botGroupId}, {@code botId},
     * {@code environmentId}, {@code product}, …), which is what makes a per-request line
     * admissible at all under the tier model — it is DEBUG, so it reaches the detail track
     * and never Loki.
     */
    private void admit(RequestTier tier, GatewayRequestScope scope) {
        long waited = 0L;
        int window = stamp();
        waitTimers.get(tier).record(waited, java.util.concurrent.TimeUnit.NANOSECONDS);
        counter(tier, OUTCOME_ADMITTED).increment();
        if (log.isDebugEnabled()) {
            log.debug("gateway budget: admitted {} request for {} — window {}/{}",
                    tier, scope == null ? "-" : scope.describe(), window, settings.hardCap());
        }
    }

    @Override
    public void count(String reason) {
        int window = stamp();
        log.debug("gateway budget: counted {} against env {} — window {}/{}",
                reason, environmentId, window, settings.hardCap());
    }

    /** Prune expired stamps, add one for now, and return the resulting window count. */
    private int stamp() {
        long now = nanos.getAsLong();
        lock.lock();
        try {
            prune(now);
            stamps.addLast(now);
            return stamps.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drop every stamp at least {@code window} old.
     * <p>
     * {@code >=} and not {@code >}: a stamp taken exactly one window ago is outside the
     * window, which is the boundary the Cloudflare rule uses and the one
     * {@code SlidingWindowGatewayBudgetWindowTest} pins. Caller holds {@link #lock}.
     */
    private void prune(long now) {
        long cutoff = now - settings.window().toNanos();
        Long oldest;
        while ((oldest = stamps.peekFirst()) != null && oldest - cutoff <= 0) {
            stamps.pollFirst();
        }
    }

    /** {@code W}: how many requests this environment has sent in the current window. */
    public int windowRequests() {
        long now = nanos.getAsLong();
        lock.lock();
        try {
            prune(now);
            return stamps.size();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ declared demand

    @Override
    public Reservation reserve(RequestTier tier, int permits, GatewayRequestScope scope) {
        int declared = Math.max(0, permits);
        reserved.get(tier).addAndGet(declared);
        log.debug("gateway budget: reserved {} {} requests for {} on env {}",
                declared, tier, scope == null ? "-" : scope.describe(), environmentId);
        return new TrackedReservation(tier, declared);
    }

    /**
     * Declared demand that gives itself back exactly once.
     * <p>
     * Idempotent release matters more than it looks: {@code startLocked}'s {@code finally}
     * will release it, and so will the {@code try}-with-resources form if a caller uses one.
     * A double release would drive the gauge negative and, from Phase 3, would hand the
     * lower tiers ceiling room that was never returned.
     */
    private final class TrackedReservation implements Reservation {
        private final RequestTier tier;
        private final AtomicInteger outstanding;

        private TrackedReservation(RequestTier tier, int permits) {
            this.tier = tier;
            this.outstanding = new AtomicInteger(permits);
        }

        @Override
        public void release() {
            int remaining = outstanding.getAndSet(0);
            if (remaining > 0) {
                reserved.get(tier).addAndGet(-remaining);
            }
        }

        @Override
        public int remaining() {
            return outstanding.get();
        }
    }

    // ------------------------------------------------------------------ cancellation

    @Override
    public void cancelScope(String botGroupId) {
        // Nothing is ever queued in Phase 1, so there is nothing to wake. The method exists
        // now because the CALL SITE ordering is the load-bearing part (AD-8/AD-16): stop()
        // must cancel before it takes the group lock, and that ordering is introduced with
        // the async start in Phase 2, before the queues it protects exist in Phase 3.
        log.debug("gateway budget: cancelScope({}) on env {} — nothing queued (observe-only phase)",
                botGroupId, environmentId);
    }

    // ------------------------------------------------------------------ reads

    @Override
    public Snapshot snapshot() {
        return new Snapshot(
                environmentId,
                environmentName,
                productCode,
                settings.mode(),
                windowRequests(),
                settings.hardCap(),
                queued.get(RequestTier.ESSENTIAL).get(),
                queued.get(RequestTier.PRIORITIZED).get(),
                queued.get(RequestTier.DEFAULT).get(),
                circuitOpen.get());
    }

    @Override
    public boolean countsWsUpgrades() {
        return settings.countWsUpgrades();
    }

    /** The policy this budget was built with. */
    public GatewayBudgetSettings settings() {
        return settings;
    }

    /**
     * The pre-registered counter for this {@code {tier, outcome}} pair. Never builds one on
     * the fly: an outcome that is not in {@link #OUTCOMES} is a bug (an unbounded label
     * value), and failing loudly here is better than quietly growing the label set.
     */
    private Counter counter(RequestTier tier, String outcome) {
        Counter counter = outcomeCounters.get(counterKey(tier, outcome));
        if (counter == null) {
            throw new IllegalArgumentException("unknown gateway budget outcome '" + outcome
                    + "' — outcome is a bounded label and every value must be pre-registered");
        }
        return counter;
    }

    private static String counterKey(RequestTier tier, String outcome) {
        return tier.name() + '|' + outcome;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
