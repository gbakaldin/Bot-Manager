package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.exception.GatewayBudgetExhaustedException;
import com.vingame.bot.common.exception.GatewayCircuitOpenException;
import com.vingame.bot.common.exception.GatewayRequestCancelledException;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * The per-environment sliding-window budget (GATEWAY_REQUEST_BUDGET AD-4, AD-6, AD-7).
 * <p>
 * A deque of monotonic acquisition stamps; {@code W} is the number of stamps younger than
 * {@code settings.window()}. This is the shape of the reference limiter that was written in
 * Python after the 2026-09-17 block ({@code scripts/bulk-create-accounts.py}), moved into
 * the app and made per-environment.
 * <p>
 * <b>{@code observe} counts; {@code enforce} paces.</b> In {@code observe} every entry point is
 * "stamp, then run" — byte-for-byte Phase 1 behaviour, which is what made that phase deployable
 * to ten prod environments unchanged and what still makes {@code GATEWAY_BUDGET_MODE} a safe
 * per-box switch. In {@code enforce} the same entry points queue.
 * <p>
 * <b>The admission rules</b> (AD-5), with {@code R_E} / {@code R_P} the outstanding declared
 * demand of the tiers above:
 * <pre>
 *   DEFAULT      admitted iff W &lt; default.ceiling − R_E − R_P
 *   PRIORITIZED  admitted iff W &lt; prioritized.ceiling − R_E
 *   ESSENTIAL    admitted iff W &lt; essential.ceiling
 *   every tier   and W &lt; hard-cap, and the circuit is closed
 * </pre>
 * The <em>gap</em> between two ceilings is the reservation for the tier above — there is no
 * cadence prediction anywhere, and EWMA estimation is explicitly deferred (Open Item 9).
 * Effective ceilings are floored at zero: a reservation larger than the window (3,000 bots × 3
 * = 9,000 against a 900 cap is the real shape) simply zeroes the lower tiers until it is
 * consumed or released, which is the intended strict priority and not a bug.
 * <p>
 * <b>Strict priority on the arrival path rests on the ceiling validator, not on this code.</b>
 * A request whose own tier's queue is empty is admitted immediately if its rule holds, without
 * consulting the tiers above it — so an arriving DEFAULT could in principle overtake a queued
 * ESSENTIAL. It cannot, and the reason is in {@code GatewayBudgetSettings}' constructor: ceilings
 * are validated monotonic ({@code default <= prioritized <= essential <= hard-cap}), so if
 * ESSENTIAL is queued then {@code W >= essential.ceiling >= default.ceiling} and the arriving
 * DEFAULT has no room either. Two reviewers verified this independently and neither wrote it down;
 * it is recorded here because it means <b>relaxing the monotonic validator would silently break
 * strict priority</b>, in a place nothing in this class would hint at.
 * <p>
 * <b>Strict priority across tiers, FIFO within a tier, one admission pass</b> (AD-6). Waiters
 * sit in three {@link ArrayDeque}s under one {@link ReentrantLock}, each holding its own
 * {@link CompletableFuture} — never a shared {@code Condition}. With 3,000 ESSENTIAL waiters
 * and ~3 admissions/s a {@code signalAll} would wake 9,000 threads a second for nothing.
 * {@link #admitWaiters()} walks ESSENTIAL → PRIORITIZED → DEFAULT, admitting heads while their
 * rule holds, and runs on every acquire, release, cancel, reserve and from a wake-up scheduled
 * at the <b>earliest stamp expiry</b>.
 * <p>
 * <b>An unbounded wait is admissible only where progress is guaranteed</b> (A16.2). The window
 * guarantees it, so {@code essential.max-wait=0} is legal for ESSENTIAL alone. An open circuit
 * guarantees nothing — the observed block outlived an hour and the user's answer on its lifetime
 * is "possibly ~24 hours, possibly until someone clears it manually" — so an open circuit
 * <b>refuses every tier</b> rather than parking any of them — so that {@code FOLLOWUPS.md} P13 (a
 * build that parks forever keeps its group's attempt open for the life of the JVM) has one fewer
 * way to happen. The state machine that opens and closes it is {@link #reportEdgeBlock} and
 * {@link #runCircuitProbe()} (Phase 5).
 * <p>
 * <b>Stamp on admission, not on completion.</b> A request that was admitted and then failed
 * still cost the edge a request; a request that timed out or was cancelled while queued cost it
 * nothing and is never stamped.
 * <p>
 * <b>The clock is read inside the lock, and that is load-bearing.</b> A time captured before
 * {@code lock.lock()} can be arbitrarily stale by the time the stamp lands — every bot thread of
 * a group start arrives at once, so a thread may queue on the lock for as long as the admissions
 * ahead of it take. A stamp dated earlier than its own admission expires early, and the window
 * then admits again while that request is still in flight.
 * {@code SlidingWindowGatewayBudgetClockDisciplineTest} pins it deterministically, with a clock
 * that jumps a whole window between the two reads. <b>The escalation IT does not, and was wrongly
 * credited with finding it</b> (QA Q1): its receiver-side bound is {@code hard-cap + threads} = 84,
 * which is exactly the figure the defect produces, and the fixed code measures 79-84 on the same
 * machine — so the two are indistinguishable there.
 * <p>
 * <b>What an independent observer sees can still read slightly above the cap, and the margin is
 * where that goes.</b> The invariant this class owes is exact: in any interval of length
 * {@code window}, the admissions inside it are all counted by the last of them against its own
 * lookback, so there can never be more than {@code hard-cap}. But <em>we</em> stamp at admission
 * and <em>the edge</em> counts at arrival, so when the window rolls and a fresh burst is admitted,
 * the tail of the previous burst may still be arriving: an observer's window can hold up to
 * {@code cap + (requests in flight at the roll)}.
 * <p>
 * <b>The bound on that is admission rate x latency jitter</b> — the number of admissions inside one
 * span of {@code (max latency − min latency)} — and <b>not</b> the two concurrency semaphores
 * (QA Q2). An earlier version of this javadoc said {@code bot.creation.parallelism} and
 * {@code user.registration.parallelism} (10 each) bounded it, which contradicts the plan's own
 * Findings: the first-read burst has <em>no</em> semaphore ("the one truly unbounded burst in the
 * start path"), and neither do drift reads nor reconnect upgrades. The correct bound is smaller at
 * production rates — ~3 admissions/s at the cap against sub-second jitter, i.e. an order of
 * magnitude inside the 100-request gap between {@code hard-cap=900} and Cloudflare's 1,000, which
 * is what AD-5 means by "the only margin for traffic the JVM cannot see" — so the conclusion
 * survives. The reason matters because the wrong one invites the wrong fix: raising the semaphores
 * looks free under the old bound, and is not. ({@code user.registration.parallelism} no longer
 * exists at all since Phase 4 — registration is one serial worker, not a fan-out — which removes
 * one of the two semaphores this paragraph is about and changes none of its arithmetic.) <b>It is also one more reason not to raise the hard
 * cap.</b> The {@code gateway_budget_requests_total} counter and
 * {@code gateway_budget_window_requests} gauge must agree on that, or the dashboard lies about
 * the one number this feature exists to bound. That is also why {@code count()} has its own
 * {@code outcome="counted"}: a probe stamps the window without asking for admission, and
 * without the fifth outcome the counter and the gauge provably could not reconcile.
 * <p>
 * <b>The clock is injected</b> ({@code LongSupplier nanos}, the {@code ScopedDebugRegistry}
 * seam) so every timing test is deterministic and nothing in the suite sleeps. Never
 * {@code System.currentTimeMillis} — the window must not move when the wall clock does.
 * <p>
 * Thread-safe: all window, queue and reservation state is guarded by one {@link ReentrantLock}.
 * The per-tier gauge counters are atomics read by the scrape thread.
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
    /**
     * The configured per-tier ceiling, exported so the Grafana panel plots the policy this box
     * is actually running instead of three literals baked into a dashboard JSON (A5.6/F5). A
     * panel whose reference lines are hardcoded is a panel that lies the moment an operator
     * uses the escape hatch the plan itself documents ({@code essential.ceiling=850}).
     */
    public static final String CEILING = "gateway_budget_ceiling";
    /**
     * The configured hard cap, for the same reason — and because {@code GatewayBudgetNearCap}
     * is expressed as a <em>ratio</em> against it rather than against the literal 800, so
     * lowering the cap tightens the alert instead of silently disarming it.
     */
    public static final String HARD_CAP = "gateway_budget_hard_cap";

    /**
     * {@code 1} while this environment's circuit is open — a Cloudflare edge block was detected
     * and nothing but one clearance probe per interval leaves the JVM for this gateway (AD-13).
     * {@code GatewayEdgeBlocked} reads it.
     */
    public static final String CIRCUIT_OPEN = "gateway_circuit_open";
    /** How many times the circuit has opened (closed → open transitions only). */
    public static final String CIRCUIT_OPENED_TOTAL = "gateway_circuit_opened_total";
    /**
     * Every edge block seen, by {@link GatewayEndpoint} — in <b>both</b> modes, because detection
     * is a fact about the edge whatever this JVM decides to do about it.
     */
    public static final String EDGE_BLOCKS_TOTAL = "gateway_edge_blocks_total";

    /** Bounded {@code outcome} label values of {@link #REQUESTS_TOTAL}. */
    public static final String OUTCOME_ADMITTED = "admitted";
    public static final String OUTCOME_TIMEOUT = "timeout";
    public static final String OUTCOME_CANCELLED = "cancelled";
    public static final String OUTCOME_CIRCUIT_OPEN = "circuit_open";
    /** A stamp taken without asking for admission — {@link #count}. See the class javadoc. */
    public static final String OUTCOME_COUNTED = "counted";

    private static final String[] OUTCOMES = {
            OUTCOME_ADMITTED, OUTCOME_TIMEOUT, OUTCOME_CANCELLED, OUTCOME_CIRCUIT_OPEN, OUTCOME_COUNTED};

    /**
     * How long a tier must stay un-throttled before another throttle WARN may be emitted for
     * it (AD-20). A flapping edge must not be able to spam tier 1.
     */
    private static final Duration THROTTLE_LOG_REARM = Duration.ofMinutes(5);

    /**
     * Slack added to a wake-up so it lands <em>after</em> the stamp it is waiting on has
     * expired rather than exactly on the boundary, where {@link #prune} would still be
     * deciding. One millisecond, once per expiry — cheaper than a spurious empty pass.
     */
    private static final long WAKE_UP_SLACK_NANOS = Duration.ofMillis(1).toNanos();

    private final String environmentId;
    private final String environmentName;
    private final String productCode;
    private final GatewayBudgetSettings settings;
    private final LongSupplier nanos;

    /** Acquisition stamps, oldest first. Guarded by {@link #lock}. */
    private final ArrayDeque<Long> stamps = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();

    /** One FIFO waiter queue per tier. Guarded by {@link #lock}. */
    private final Map<RequestTier, ArrayDeque<Waiter>> queues = new EnumMap<>(RequestTier.class);

    /**
     * Live declared demand, by {@code tier|botGroupId}, so an admission can find the
     * reservation it is drawing down. Guarded by {@link #lock}.
     */
    private final Map<String, TrackedReservation> reservations = new HashMap<>();

    private final Map<RequestTier, AtomicInteger> queued = new EnumMap<>(RequestTier.class);
    private final Map<RequestTier, AtomicInteger> reserved = new EnumMap<>(RequestTier.class);
    private final Map<RequestTier, Timer> waitTimers = new EnumMap<>(RequestTier.class);
    /** Every {@code {tier, outcome}} counter, resolved once at construction. */
    private final Map<String, Counter> outcomeCounters = new HashMap<>();

    /** Throttle-logging state per tier: whether we have said so, and when (AD-20). */
    private final Map<RequestTier, AtomicBoolean> throttleReported = new EnumMap<>(RequestTier.class);
    private final Map<RequestTier, AtomicLong> throttleReportedAt = new EnumMap<>(RequestTier.class);

    /**
     * Circuit state (AD-13): {@code true} from the first {@link #reportEdgeBlock} under
     * {@code enforce} until a clearance probe is answered by something that is not a block. Read
     * without the lock by the gauge and the snapshot; written only under {@link #lock}.
     * <p>
     * There is no separate HALF_OPEN value on purpose: while the one probe is in flight the
     * circuit still refuses everything, which is what "half open" has to mean here — the probe
     * is the only request allowed through, and it is not a bot's.
     */
    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);

    /** Guarded by {@link #lock}: the cf-ray that opened (or last re-confirmed) the circuit. */
    private String circuitCfRay;
    /** Guarded by {@link #lock}: when the circuit opened, for the "closed after …" line. */
    private long circuitOpenedAtNanos;
    /** Guarded by {@link #lock}: when the next clearance probe is due. */
    private long nextProbeAtNanos;
    /** Guarded by {@link #lock}: a probe is in flight, so a second must not start. */
    private boolean probeInFlight;
    /** Guarded by {@link #lock}: the pending probe timer. */
    private ScheduledFuture<?> probeTimer;

    /**
     * The anonymous clearance request, bound by {@link GatewayBudgetRegistry} once it knows this
     * environment's {@code apiGateway}. {@code null} until then — an open circuit with no probe
     * simply stays open and says so, because "we could not ask" is not "it cleared".
     */
    private volatile CircuitProbe circuitProbe;

    private Counter circuitOpenedCounter;
    private final Map<GatewayEndpoint, Counter> edgeBlockCounters = new EnumMap<>(GatewayEndpoint.class);

    /**
     * Observe-mode WARN throttle (AD-23: "detection still logs the WARN it would have acted on").
     * During a block <em>every</em> request is a block, so an unthrottled WARN would be a per-bot,
     * per-request line at tier 1 — exactly the shape CLAUDE.md forbids. One line per environment
     * per {@link #THROTTLE_LOG_REARM}, carrying how many were folded into it.
     */
    private final AtomicLong observedBlockWarnedAt = new AtomicLong(Long.MIN_VALUE);
    private final AtomicInteger observedBlocksSinceWarn = new AtomicInteger();

    /**
     * One single-threaded scheduler per budget, for wake-ups at the earliest stamp expiry.
     * A virtual thread, so 10 environments cost 10 continuations rather than 10 platform
     * threads. Never used to run a gateway call — only to re-run {@link #admitWaiters()}.
     */
    private final ScheduledExecutorService scheduler;
    /** Guarded by {@link #lock}: the pending wake-up and the stamp deadline it was set for. */
    private ScheduledFuture<?> wakeUp;
    private long wakeUpAtNanos;

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
            queues.put(tier, new ArrayDeque<>());
            throttleReported.put(tier, new AtomicBoolean());
            throttleReportedAt.put(tier, new AtomicLong(Long.MIN_VALUE));
        }
        ThreadFactory factory = Thread.ofVirtual()
                .name("gateway-budget-" + environmentId).factory();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(factory);
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
     * occurrence of exactly the thing it is watching for. Fifteen series per environment
     * (3 tiers × 5 outcomes) is the price and it is bounded.
     */
    private void registerMeters(MeterRegistry registry) {
        Tags tags = tags();

        Gauge.builder(WINDOW_REQUESTS, this, SlidingWindowGatewayBudget::windowRequests)
                .description("Requests admitted to this environment's gateway in the current sliding window")
                .tags(tags)
                .register(registry);

        Gauge.builder(HARD_CAP, this, budget -> budget.settings.hardCap())
                .description("Configured hard cap: no tier is admitted past this many requests per window")
                .tags(tags)
                .register(registry);

        Gauge.builder(CIRCUIT_OPEN, circuitOpen, open -> open.get() ? 1 : 0)
                .description("1 while a Cloudflare edge block holds this environment's circuit open")
                .tags(tags)
                .register(registry);
        circuitOpenedCounter = Counter.builder(CIRCUIT_OPENED_TOTAL)
                .description("Times this environment's gateway circuit opened on a Cloudflare edge block")
                .tags(tags)
                .register(registry);
        for (GatewayEndpoint endpoint : GatewayEndpoint.values()) {
            edgeBlockCounters.put(endpoint, Counter.builder(EDGE_BLOCKS_TOTAL)
                    .description("Responses classified as a Cloudflare edge block, by request kind")
                    .tags(tags.and("endpoint", endpoint.tag()))
                    .register(registry));
        }

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

            Gauge.builder(CEILING, this, budget -> budget.settings.ceiling(tier))
                    .description("Configured ceiling for this tier")
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

    /**
     * Resolve the wait this admission will use, in the <b>one</b> encoding the rest of the class
     * understands (review F5):
     * <ul>
     *   <li>{@code null} — unbounded, cancellable. Reachable only from a tier configured
     *       {@code max-wait=0}, which validation allows for {@link RequestTier#ESSENTIAL}
     *       alone.</li>
     *   <li>{@link Duration#ZERO} — <b>now or never</b>, always, for every tier.</li>
     *   <li>positive — a bounded wait.</li>
     * </ul>
     * <b>A caller's word is final, {@code ZERO} included.</b> Before this existed, the two
     * meanings of {@code ZERO} were told apart by asking whether the <em>tier</em> was configured
     * unbounded — so {@code tryExecute(ESSENTIAL, scope, call, ZERO)}, a literal reading of the
     * interface's own javadoc, parked unboundedly, on the one tier where that is not recoverable,
     * inside the rule ("never park a message-processor thread") it was written to serve.
     *
     * @param callerWait {@code null} to use the tier's configured policy
     */
    private Duration resolvedWait(RequestTier tier, Duration callerWait) {
        if (callerWait != null) {
            return callerWait;
        }
        return settings.isUnboundedWait(tier) ? null : settings.maxWait(tier);
    }

    @Override
    public <T> T execute(RequestTier tier, GatewayRequestScope scope, Callable<T> call) throws Exception {
        return execute(tier, scope, call, null);
    }

    @Override
    public <T> T execute(RequestTier tier, GatewayRequestScope scope, Callable<T> call, Duration maxWait)
            throws Exception {
        admitOrThrow(tier, scope, resolvedWait(tier, maxWait));
        return call.call();
    }

    @Override
    public void run(RequestTier tier, GatewayRequestScope scope, Runnable call) {
        admitOrThrowUnchecked(tier, scope, resolvedWait(tier, null));
        call.run();
    }

    @Override
    public void runWsUpgrade(RequestTier tier, GatewayRequestScope scope, Runnable upgrade) {
        if (settings.countWsUpgrades()) {
            admitOrThrowUnchecked(tier, scope, resolvedWait(tier, null));
        } else {
            // A5.2: not counted by the edge ⇒ not paced by us. But "uncounted" is not
            // "un-cancellable" (a stopped group's queued upgrade must still die, or /stop's
            // promptness would depend on a flag about Cloudflare's accounting) and it is not
            // "sent into an open circuit" either (the edge is refusing this host whatever it
            // counts). So: the two gates, and no window.
            refuseIfCircuitOpen(tier);
            refuseIfCancelled(tier, scope);
        }
        upgrade.run();
    }

    @Override
    public <T> Optional<T> tryExecute(RequestTier tier, GatewayRequestScope scope,
                                      Callable<T> call, Duration maxWait) throws Exception {
        if (!admitSoftly(tier, scope, resolvedWait(tier, maxWait))) {
            return Optional.empty();
        }
        return Optional.ofNullable(call.call());
    }

    /**
     * Admit one request, throwing the typed budget outcome if it cannot be.
     *
     * @throws InterruptedException              if the caller is interrupted while queued —
     *                                           propagated with the flag restored, because a
     *                                           caller that was interrupted has been told to
     *                                           stop and must not silently continue.
     * @throws GatewayBudgetExhaustedException   the wait elapsed with no room.
     * @throws GatewayCircuitOpenException       the edge is blocking this host (Phase 5).
     * @throws GatewayRequestCancelledException  the scope was called off.
     */
    private void admitOrThrow(RequestTier tier, GatewayRequestScope scope, Duration maxWait)
            throws InterruptedException {
        if (!admit(tier, scope, maxWait, false)) {
            // Unreachable: hard mode either admits or throws. Belt and braces so a future
            // edit to admit() cannot turn a refusal into a silent send.
            throw new IllegalStateException("gateway budget admission returned false in hard mode");
        }
    }

    /**
     * {@link #admitOrThrow} for a {@link Runnable} caller, which cannot carry an
     * {@code InterruptedException}. An interrupt while queued becomes
     * {@code GatewayRequestCancelledException} with the flag restored — the honest translation,
     * since an interrupt is a request to stop and the alternative (swallowing it, as
     * {@code VingameWebSocketClient.connect()} does) is the defect AD-8 exists to avoid.
     */
    private void admitOrThrowUnchecked(RequestTier tier, GatewayRequestScope scope, Duration maxWait) {
        try {
            admitOrThrow(tier, scope, maxWait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayRequestCancelledException(environmentId, describe(scope));
        }
    }

    /**
     * Admit one request, returning {@code false} rather than throwing when it cannot be —
     * {@link #tryExecute}'s contract, and AD-10's requirement.
     * <p>
     * A drift balance read runs on a ws-parser message-processor thread, so it must neither
     * park nor throw into the bot's message pipeline: it gives up, the caller uses its local
     * estimate, and the deferral is recorded. An open circuit is reported the same way — as
     * {@code outcome="circuit_open"} on the counter, not as an exception through the pipeline.
     */
    private boolean admitSoftly(RequestTier tier, GatewayRequestScope scope, Duration maxWait)
            throws InterruptedException {
        return admit(tier, scope, maxWait, true);
    }

    /**
     * The admission path.
     *
     * @param soft when {@code true}, a refusal is {@code false} instead of an exception —
     *             except for cancellation, which is always thrown, because a cancelled caller
     *             must not mistake "called off" for "no room right now" and carry on.
     * @return {@code true} if the request was admitted and stamped.
     */
    private boolean admit(RequestTier tier, GatewayRequestScope scope, Duration maxWait, boolean soft)
            throws InterruptedException {
        boolean admittedImmediately = false;
        // Side effects this block produces but must not run while holding the lock, and must not
        // lose to a throw either — hence collected here and run in the finally below.
        List<Runnable> deferredHere = new ArrayList<>();
        if (settings.mode() != GatewayBudgetMode.ENFORCE) {
            // observe: byte-for-byte Phase 1. Stamp, account, run. Nothing waits, nothing is
            // refused, and a cancelled scope's request is issued and therefore counted —
            // there is no queue for it to be cancelled out of.
            stampAdmitted(tier, scope, 0L);
            return true;
        }

        long startedWaiting = nanos.getAsLong();
        Waiter waiter = null;
        lock.lock();
        try {
            // The clock is read AGAIN, inside the lock, and the stamp uses THIS value — not the
            // one taken before the lock. Under contention (every bot thread of a group start
            // arrives at once) a thread can capture `startedWaiting`, queue on the lock for as
            // long as the admissions ahead of it take, and then stamp a time from before it had
            // permission to send. Such a stamp expires EARLY, which lets the window admit again
            // while the request it belonged to is still in flight — measured as an 84-in-60
            // overshoot by GatewayBudgetEscalationIT, against the stub's own arrival count.
            // `startedWaiting` survives for one purpose: the wait timer, where "how long did the
            // caller wait" is genuinely measured from before the lock.
            long now = nanos.getAsLong();
            // No admission pass here, deliberately. Every event that can CREATE room already
            // runs one — a stamp expiry (the scheduled wake-up), a reservation release, a
            // cancelScope, a probe's stamp — so a pass on the arrival path would be redundant
            // work on the hottest path in the budget. It would also be a hazard: this block
            // throws, and a pass whose admitted waiters were completed after the lock was
            // released would lose those completions to the throw.
            prune(now);
            // Retiring a leaked reservation CREATES room for the tiers below by dropping
            // reserved{tier}, so this path owes the queues a pass (review F12) — the comment above
            // is otherwise not quite true, and it is the sentence a future reader would use to
            // justify skipping a pass somewhere else. Collected, not run: this block throws.
            //
            // Gated on the FREED REMAINDER, not on whether a warning was produced (review S2). The
            // two happen to have the same answer today, because expireStaleReservationsLocked
            // returns a warning on exactly the condition that creates room — so "should I walk the
            // queues?" was being decided by "did we emit a WARN?", and throttling that WARN, or
            // dropping it to DEBUG for a small remainder, would silently revert F12 with no test
            // failing.
            int freed = expireStaleReservationsLocked(now, deferredHere);
            if (freed > 0) {
                admitWaitersLocked(now, deferredHere);
            }

            if (circuitOpen.get()) {
                // A16.2: an open circuit refuses every tier, ESSENTIAL included. Parking on a
                // block whose lifetime may be a day is parking a bot thread for a day.
                counter(tier, OUTCOME_CIRCUIT_OPEN).increment();
                if (soft) {
                    return false;
                }
                throw circuitRefusalLocked(now);
            }
            if (scope != null && scope.isCancelled()) {
                // Always thrown, even for a soft caller: "called off" must not be mistaken for
                // "no room right now" by a caller that would then carry on with a cached value.
                counter(tier, OUTCOME_CANCELLED).increment();
                throw new GatewayRequestCancelledException(environmentId, describe(scope));
            }
            // FIFO within the tier: a request never overtakes a waiter of its own tier, even
            // when there is room, or the head of a busy queue could starve behind arrivals.
            if (queues.get(tier).isEmpty() && hasRoomLocked(tier)) {
                consumeReservationLocked(tier, scope);
                stampLocked(now);
                admittedImmediately = true;
            } else if (maxWait != null && maxWait.isZero()) {
                // Now or never (F5): ZERO from a caller ALWAYS means this, whatever the tier's own
                // policy says. Nothing was sent.
                counter(tier, OUTCOME_TIMEOUT).increment();
                if (soft) {
                    return false;
                }
                throw new GatewayBudgetExhaustedException(tier, environmentId, retryAfterLocked(now));
            } else {
                waiter = new Waiter(tier, scope, startedWaiting);
                queues.get(tier).addLast(waiter);
                queued.get(tier).incrementAndGet();
                scheduleWakeUpLocked(now);
            }
        } finally {
            lock.unlock();
            runAfterUnlock(deferredHere);
        }
        if (admittedImmediately) {
            recordAdmitted(tier, scope, 0L);
            return true;
        }
        reportThrottleState();

        return await(waiter, maxWait, soft);
    }

    /**
     * Park on the waiter's future until it is admitted, cancelled, or the wait elapses.
     * <p>
     * The wait is on a per-waiter {@link CompletableFuture}, which is what makes it both
     * interruptible and free of a thundering herd. A {@code null} {@code maxWait} is an unbounded
     * wait <em>for ESSENTIAL only</em> — legal because the window drains by construction (A16.2) —
     * and it is always cancellable. <b>{@link Duration#ZERO} never reaches this method</b>: it is
     * resolved to now-or-never before a waiter exists (review F5). This paragraph said ZERO was
     * the unbounded encoding until review T1, three lines above the comment that contradicts it.
     */
    private boolean await(Waiter waiter, Duration maxWait, boolean soft) throws InterruptedException {
        // F5: null is the ONLY encoding of unbounded here. A ZERO never reaches this method — it
        // is resolved to now-or-never before a waiter is ever created.
        boolean unbounded = maxWait == null;
        try {
            if (unbounded) {
                waiter.admitted.get();
            } else {
                waiter.admitted.get(maxWait.toNanos(), TimeUnit.NANOSECONDS);
            }
            return true;
        } catch (TimeoutException e) {
            if (!dequeue(waiter)) {
                // Admitted in the instant between the timeout firing and the lock: the stamp
                // is already taken, so reporting a timeout here would make the counter and
                // the window disagree — and the request really is going out.
                return true;
            }
            counter(waiter.tier, OUTCOME_TIMEOUT).increment();
            recordWait(waiter);
            reportThrottleState();
            if (soft) {
                return false;
            }
            throw new GatewayBudgetExhaustedException(waiter.tier, environmentId, retryAfter());
        } catch (InterruptedException e) {
            dequeue(waiter);
            reportThrottleState();
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            // The only exceptional completion is cancellation (admitWaitersLocked / cancelScope).
            dequeue(waiter);
            reportThrottleState();
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("unexpected gateway budget waiter failure", e.getCause());
        }
    }

    /** Refuse outright if the edge is blocking this host — no tier parks on a block (A16.2). */
    private void refuseIfCircuitOpen(RequestTier tier) {
        if (settings.mode() == GatewayBudgetMode.ENFORCE && circuitOpen.get()) {
            counter(tier, OUTCOME_CIRCUIT_OPEN).increment();
            GatewayCircuitOpenException refusal;
            lock.lock();
            try {
                refusal = circuitRefusalLocked(nanos.getAsLong());
            } finally {
                lock.unlock();
            }
            throw refusal;
        }
    }

    /**
     * The refusal an open circuit answers with: the cf-ray that opened it (the SA ticket's one
     * value) and the time until the next clearance probe — which is <b>not</b> a deadline by which
     * the block clears (A16.3; the exception's message says so). Caller holds {@link #lock}.
     */
    private GatewayCircuitOpenException circuitRefusalLocked(long now) {
        long untilProbe = nextProbeAtNanos - now;
        return new GatewayCircuitOpenException(environmentId, circuitCfRay,
                untilProbe <= 0 ? Duration.ZERO : Duration.ofNanos(untilProbe));
    }

    /** Refuse a request whose scope has been called off, without stamping anything. */
    private void refuseIfCancelled(RequestTier tier, GatewayRequestScope scope) {
        if (settings.mode() == GatewayBudgetMode.ENFORCE && scope != null && scope.isCancelled()) {
            counter(tier, OUTCOME_CANCELLED).increment();
            throw new GatewayRequestCancelledException(environmentId, describe(scope));
        }
    }

    // ------------------------------------------------------------------ admission pass

    /**
     * Walk ESSENTIAL → PRIORITIZED → DEFAULT, admitting queue heads while their rule holds
     * (AD-6). Package-private so tests can drive it directly on a manual clock.
     */
    /**
     * The scheduler's entry point (review F4).
     * <p>
     * {@code scheduler.schedule} returns a one-shot {@code ScheduledFuture} nobody calls
     * {@code get()} on, so an exception escaping here is swallowed <b>silently</b> — not even at
     * DEBUG. Catching and logging it is the difference between a diagnosable bug and a budget
     * whose queues quietly stop being walked.
     */
    private void wakeUp() {
        try {
            admitWaiters();
        } catch (RuntimeException e) {
            log.error("env {} ({}): the gateway budget's admission wake-up failed. Queued requests "
                            + "will now wait out their max-wait rather than being admitted early; "
                            + "ESSENTIAL waiters depend on the next event to make progress.",
                    environmentId, environmentName, e);
        }
    }

    void admitWaiters() {
        // The list is OURS, not the locked method's return value (review S1). A throw inside the
        // pass used to mean the assignment never happened, so runAfterUnlock was never reached and
        // every waiter the loop had already dequeued and stamped was left in no queue, on no timer
        // and with its future never completed — an ESSENTIAL waiter parks for the life of the JVM,
        // which is the exact FOLLOWUPS P13 outcome F3 was written to remove. Owning the list here
        // makes the partial pass run from the same finally that re-arms the timer.
        List<Runnable> deferred = new ArrayList<>();
        lock.lock();
        try {
            admitWaitersLocked(nanos.getAsLong(), deferred);
        } finally {
            lock.unlock();
            runAfterUnlock(deferred);
        }
        reportThrottleState();
    }

    /**
     * One admission pass. Caller holds {@link #lock}.
     * <p>
     * <b>Accumulates</b> into the caller's {@code deferred} list the side effects that must
     * <b>not</b> happen under the lock: completing a waiter's future runs that waiter's
     * continuation, and a cancelled waiter's exceptional completion can run arbitrary downstream
     * code. Doing either while holding the budget lock would let a bot thread re-enter the budget
     * from inside the pass.
     * <p>
     * The list belongs to the caller rather than being returned (review S1) so that a partial pass
     * — one that threw after dequeuing and stamping some waiters — is still run. A returned list is
     * lost on a throw, and a lost {@code complete(null)} is an unreleased waiter.
     */
    private void admitWaitersLocked(long now, List<Runnable> deferred) {
        try {
            admitWhileRoomLocked(now, deferred);
        } finally {
            // In a finally, and that is the point (review F4). The wake-up chain is
            // self-perpetuating — a pass re-arms the timer at its end — so a throw anywhere above
            // used to leave the timer un-armed, and on the scheduler thread the exception goes into
            // a one-shot ScheduledFuture nobody calls get() on, i.e. it is not logged at all. With
            // no other traffic on this budget there is then no later event to re-arm it, and every
            // queued waiter waits out its max-wait for room that exists.
            //
            // NARROWER THAN IT READS (QA G-3): that benefit holds for a throw from prune() or
            // expireStaleReservationsLocked, where a later pass would get through. It does NOT
            // hold for a throw from the head waiter's own isCancelled() predicate, because
            // admitWhileRoomLocked consults the head BEFORE dequeuing it: the re-armed timer then
            // produces a silent permanent retry loop that throws at the same head and never
            // drains. The re-arm is still right — a queue with a timer is strictly better than one
            // without — but the fix for a throwing predicate is the contract on
            // GatewayRequestScope.cancelled, not this finally.
            scheduleWakeUpLocked(now);
        }
    }

    /**
     * The admission loop itself. Caller holds {@link #lock}.
     * <p>
     * <b>This calls foreign code under the budget lock</b>, and that is a contract rather than an
     * accident: {@code head.scope.isCancelled()} reaches {@code Bot.requestCancelled()} and, for a
     * group reservation, {@code startAttempts.isCancelled(id)}. Both are cheap, non-blocking and
     * non-throwing today. A future predicate that blocks, logs or takes another lock is a stall or
     * a lock-ordering deadlock on <em>every</em> gateway request of this environment — which is why
     * the constraint is now stated on {@code GatewayRequestScope.cancelled} too.
     */
    private void admitWhileRoomLocked(long now, List<Runnable> deferred) {
        prune(now);
        // The freed remainder is irrelevant here: this IS the pass, and it is about to walk every
        // tier regardless. admit() is the caller that has to decide whether to walk them at all.
        expireStaleReservationsLocked(now, deferred);

        for (RequestTier tier : RequestTier.values()) {
            ArrayDeque<Waiter> queue = queues.get(tier);
            while (!queue.isEmpty()) {
                Waiter head = queue.peekFirst();
                if (head.scope != null && head.scope.isCancelled()) {
                    // A waiter whose own scope says "no longer wanted" — a stopped bot, or a
                    // group whose start was cancelled. Never stamped: it did not leave the JVM.
                    queue.pollFirst();
                    queued.get(tier).decrementAndGet();
                    counter(tier, OUTCOME_CANCELLED).increment();
                    deferred.add(() -> head.admitted.completeExceptionally(
                            new GatewayRequestCancelledException(environmentId, describe(head.scope))));
                    continue;
                }
                if (circuitOpen.get() || !hasRoomLocked(tier)) {
                    break;
                }
                queue.pollFirst();
                queued.get(tier).decrementAndGet();
                consumeReservationLocked(tier, head.scope);
                stampLocked(now);
                long waited = now - head.enqueuedNanos;
                deferred.add(() -> {
                    // complete FIRST, bookkeeping second (review F3). The Kth admitted waiter's
                    // wake-up used to be delayed by K metric records and, on staging where
                    // BOT_LOG_LEVEL=DEBUG, K log lines each re-acquiring this very lock through
                    // windowRequests(). Waking a bot is the urgent half; counting it is not.
                    head.admitted.complete(null);
                    recordAdmitted(tier, head.scope, waited);
                });
            }
        }
    }

    /**
     * Run the pass's deferred side effects, <b>each isolated from the others</b> (review F3).
     * <p>
     * A waiter admitted in a pass is already dequeued and already stamped before this runs, so its
     * future is the only thing left that can release it. Without the {@code try/catch}, a throw
     * from an earlier action — {@code recordAdmitted} does a Micrometer record, a counter increment
     * and, at DEBUG, a log line — skipped every remaining {@code complete(null)}, leaving those
     * waiters in no queue and on no timer. An ESSENTIAL waiter is unbounded <em>by design</em>, so
     * it would then park for the life of the JVM holding a {@code bot.creation.parallelism} permit
     * and, transitively, the group lock. That is {@code FOLLOWUPS.md} P13, produced from inside the
     * class whose javadoc claims to narrow it.
     * <p>
     * The catch logs at ERROR and continues: a failure here is a bug in our own bookkeeping, and
     * dropping the rest of the pass on the floor is strictly worse than reporting it.
     */
    private void runAfterUnlock(List<Runnable> deferred) {
        for (Runnable action : deferred) {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.error("env {} ({}): a deferred gateway budget action failed — the remaining "
                                + "actions in this pass still run, because an unreleased waiter "
                                + "parks for the life of the JVM",
                        environmentId, environmentName, e);
            }
        }
    }

    /**
     * Whether {@code tier}'s rule holds right now. Caller holds {@link #lock} and has pruned.
     * <p>
     * Every effective ceiling is floored at {@code 0} — see the class javadoc on oversized
     * reservations. The hard cap applies to every tier including ESSENTIAL: 900 against a limit
     * of 1,000 is the only margin for traffic this JVM cannot see, and it is the margin a
     * day-long block is measured against.
     */
    private boolean hasRoomLocked(RequestTier tier) {
        int window = stamps.size();
        if (window >= settings.hardCap()) {
            return false;
        }
        int effective = settings.ceiling(tier) - reservedAboveLocked(tier);
        return window < Math.max(0, effective);
    }

    /** Outstanding declared demand of every tier <em>above</em> {@code tier} (AD-5, AD-7). */
    private int reservedAboveLocked(RequestTier tier) {
        int total = 0;
        for (RequestTier other : RequestTier.values()) {
            if (other.ordinal() < tier.ordinal()) {
                total += reserved.get(other).get();
            }
        }
        return total;
    }

    /**
     * Schedule the next wake-up at the earliest stamp expiry, if anything is waiting on it.
     * Caller holds {@link #lock}.
     * <p>
     * Idempotent and single-threaded: the existing wake-up is replaced only when the deadline
     * has actually moved, so a burst of admissions does not churn the scheduler. With no
     * waiters there is nothing to wake for and no timer is kept alive.
     */
    private void scheduleWakeUpLocked(long now) {
        boolean anyoneWaiting = false;
        for (RequestTier tier : RequestTier.values()) {
            if (!queues.get(tier).isEmpty()) {
                anyoneWaiting = true;
                break;
            }
        }
        Long oldest = stamps.peekFirst();
        if (!anyoneWaiting || oldest == null) {
            return;
        }
        long deadline = oldest + settings.window().toNanos() + WAKE_UP_SLACK_NANOS;
        if (wakeUp != null && !wakeUp.isDone() && wakeUpAtNanos == deadline) {
            return;
        }
        if (wakeUp != null) {
            wakeUp.cancel(false);
        }
        wakeUpAtNanos = deadline;
        long delay = Math.max(0L, deadline - now);
        try {
            wakeUp = scheduler.schedule(this::wakeUp, delay, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Shutdown in flight. The waiters are still cancellable and every bounded wait
            // still times out, so this degrades to "no early wake-up", not to a hang.
            log.debug("gateway budget: wake-up not scheduled for env {} — scheduler is shut down",
                    environmentId);
        }
    }

    /** Stamp, account and log one admitted request in observe mode (no lock held yet). */
    private void stampAdmitted(RequestTier tier, GatewayRequestScope scope, long waitedNanos) {
        lock.lock();
        try {
            // Read under the lock, for the same reason the enforce path does — see admit().
            long now = nanos.getAsLong();
            prune(now);
            stampLocked(now);
        } finally {
            lock.unlock();
        }
        recordAdmitted(tier, scope, waitedNanos);
    }

    /**
     * The metrics and the DEBUG line for one admitted request.
     * <p>
     * The DEBUG line carries no identity of its own, and <b>on the waiter path it therefore carries
     * the wrong one or none at all</b> (A27.1). On the arrival path it runs on the bot's own thread,
     * whose MDC the bot populated ({@code botGroupId}, {@code botId}, {@code environmentId},
     * {@code product}, …). An admitted <em>waiter</em>'s {@code recordAdmitted} runs inside
     * {@code runAfterUnlock}, i.e. on whichever thread ran the admission pass — this budget's own
     * {@code gateway-budget-<env>} scheduler thread (no MDC), the probe scheduler's, or
     * <em>another group's</em> start thread inside {@code reserve}/{@code release}. The identity is
     * still in the message text ({@code scope.describe()}) and the line is track-2 DEBUG, so
     * nothing operator-facing is wrong; the claim was. The known fix is for {@code Waiter} to
     * capture {@code MDC.getCopyOfContextMap()} at enqueue and for this method to run under it via
     * {@code BotMdc.snapshot()}/{@code restore()} — never {@code clear()} (A20.9) — recommended for
     * Phase 5 rather than required by any AD.
     */
    private void recordAdmitted(RequestTier tier, GatewayRequestScope scope, long waitedNanos) {
        waitTimers.get(tier).record(waitedNanos, TimeUnit.NANOSECONDS);
        counter(tier, OUTCOME_ADMITTED).increment();
        if (log.isDebugEnabled()) {
            log.debug("gateway budget: admitted {} request for {} — window {}/{}, waited {}ms",
                    tier, describe(scope), windowRequests(), settings.hardCap(),
                    TimeUnit.NANOSECONDS.toMillis(waitedNanos));
        }
    }

    private void recordWait(Waiter waiter) {
        waitTimers.get(waiter.tier).record(
                Math.max(0L, nanos.getAsLong() - waiter.enqueuedNanos), TimeUnit.NANOSECONDS);
    }

    /**
     * {@inheritDoc}
     * <p>
     * <b>Charged to {@code tier="ESSENTIAL"}</b> (review F14). A probe has no tier — it is never
     * queued and never refused — so the label is a choice, and ESSENTIAL is the honest one: an
     * unrefusable stamp behaves exactly like the top tier. The consequence to know before summing
     * anything is that {@code gateway_budget_requests_total{tier="ESSENTIAL"}} contains probes as
     * well as bots coming up, which is why {@code outcome} is the dimension to filter on:
     * {@code outcome="admitted"} is bot traffic, {@code outcome="counted"} is probes.
     */
    @Override
    public void count(String reason) {
        int window = stamp();
        counter(RequestTier.ESSENTIAL, OUTCOME_COUNTED).increment();
        log.debug("gateway budget: counted {} against env {} — window {}/{}",
                reason, environmentId, window, settings.hardCap());
    }

    @Override
    public void countWsUpgrade(String reason) {
        if (!settings.countWsUpgrades()) {
            log.debug("gateway budget: {} on env {} not counted — count-ws-upgrades=false",
                    reason, environmentId);
            return;
        }
        count(reason);
    }

    /**
     * Prune expired stamps, add one for now, and return the resulting window count.
     * <p>
     * Also runs one admission pass: a probe's stamp can be the thing that pushes the window
     * over a ceiling, and the same is true in reverse after a prune, so leaving the queues
     * un-walked here would delay a legitimate admission until the next wake-up.
     */
    private int stamp() {
        int window;
        // Owned here and run from the finally, so a throw inside the pass cannot strand a waiter
        // that was already dequeued and stamped (review S1).
        List<Runnable> deferred = new ArrayList<>();
        lock.lock();
        try {
            long now = nanos.getAsLong();
            prune(now);
            stampLocked(now);
            window = stamps.size();
            admitWaitersLocked(now, deferred);
        } finally {
            lock.unlock();
            runAfterUnlock(deferred);
        }
        return window;
    }

    /** Add one stamp for {@code now}. Caller holds {@link #lock} and has pruned. */
    private void stampLocked(long now) {
        stamps.addLast(now);
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

    /**
     * Seconds until the earliest stamp in the window expires — the soonest moment a retry
     * could possibly be admitted. Advice, not a promise: a higher tier may take the freed slot.
     */
    private Duration retryAfter() {
        long now = nanos.getAsLong();
        lock.lock();
        try {
            return retryAfterLocked(now);
        } finally {
            lock.unlock();
        }
    }

    private Duration retryAfterLocked(long now) {
        Long oldest = stamps.peekFirst();
        if (oldest == null) {
            return Duration.ZERO;
        }
        long remaining = oldest + settings.window().toNanos() - now;
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    // ------------------------------------------------------------------ the circuit (AD-13)

    /**
     * {@inheritDoc}
     * <p>
     * <b>The state machine.</b> {@code CLOSED → OPEN} on the first block under {@code enforce}:
     * the cf-ray is kept, the next probe is scheduled one {@code block-probe-interval} out, and an
     * admission pass runs so nothing already queued is admitted into the wall. {@code OPEN} stays
     * open on every further block (requests that were in flight when it opened land here too —
     * they are counted, not re-announced). Only {@link #runCircuitProbe()} closes it.
     */
    @Override
    public void reportEdgeBlock(GatewayEndpoint endpoint, String cfRay) {
        edgeBlockCounters.get(endpoint).increment();
        if (settings.mode() != GatewayBudgetMode.ENFORCE) {
            warnObservedBlock(endpoint, cfRay);
            return;
        }

        boolean opened = false;
        GatewayCircuitOpenException refusal;
        List<Runnable> deferred = new ArrayList<>();
        lock.lock();
        try {
            long now = nanos.getAsLong();
            if (!circuitOpen.get()) {
                circuitOpen.set(true);
                circuitCfRay = cfRay;
                circuitOpenedAtNanos = now;
                nextProbeAtNanos = now + settings.blockProbeInterval().toNanos();
                scheduleProbeLocked(now);
                opened = true;
                // Walk the queues now, under the same lock that flipped the flag, so the decision
                // about every queued waiter is taken against the open circuit.
                admitWaitersLocked(now, deferred);
            }
            refusal = circuitRefusalLocked(now);
        } finally {
            lock.unlock();
            runAfterUnlock(deferred);
        }

        if (opened) {
            circuitOpenedCounter.increment();
            // ERROR, once per open — tier-1 admissible because its rate is a function of incidents,
            // not of fleet size. It has to stand alone in Grafana: it names the brand-level
            // consequence and the action, because the circuit will not close by itself inside the
            // ~24 h the user expects a block to last (A16).
            log.error("env {} ({}): Cloudflare edge block on {}, cf-ray {} — circuit open: nothing is "
                            + "sent to this gateway except one clearance probe every {} (next in {}). Bots "
                            + "on this brand cannot log in, re-auth, deposit or reconnect. There is no "
                            + "automatic recovery to wait for — the block may outlive a day; raise an "
                            + "SA/back-office ticket quoting the cf-ray.",
                    environmentId, environmentName, endpoint.tag(), cfRay == null ? "-" : cfRay,
                    settings.blockProbeInterval(), settings.blockProbeInterval());
        }
        reportThrottleState();
        throw refusal;
    }

    /**
     * The observe-mode half of AD-23: say what enforce would have done, at most once per
     * environment per {@link #THROTTLE_LOG_REARM}, and change nothing else.
     */
    private void warnObservedBlock(GatewayEndpoint endpoint, String cfRay) {
        int folded = observedBlocksSinceWarn.incrementAndGet();
        long now = nanos.getAsLong();
        long last = observedBlockWarnedAt.get();
        if (last != Long.MIN_VALUE && now - last < THROTTLE_LOG_REARM.toNanos()) {
            return;
        }
        if (!observedBlockWarnedAt.compareAndSet(last, now)) {
            return;
        }
        observedBlocksSinceWarn.addAndGet(-folded);
        log.warn("env {} ({}): Cloudflare edge block on {}, cf-ray {} — mode=observe, so no circuit "
                        + "opens and traffic continues into the block; under enforce this would have "
                        + "refused every request to this gateway ({} block(s) in this line, "
                        + "gateway_edge_blocks_total has them all)",
                environmentId, environmentName, endpoint.tag(), cfRay == null ? "-" : cfRay, folded);
    }

    /**
     * Bind the clearance probe for this environment's gateway. Idempotent; the latest binding
     * wins, so an environment whose {@code apiGateway} is edited and whose clients are rebuilt
     * probes the new host.
     */
    public void bindCircuitProbe(CircuitProbe probe) {
        this.circuitProbe = probe;
    }

    /**
     * Schedule the next clearance probe at {@link #nextProbeAtNanos}. Caller holds {@link #lock}.
     * On the budget's own single-threaded scheduler (AD-13), which is free to block for the
     * probe's ten seconds: while the circuit is open there are no waiters for it to wake.
     */
    private void scheduleProbeLocked(long now) {
        if (probeTimer != null) {
            probeTimer.cancel(false);
        }
        long delay = Math.max(0L, nextProbeAtNanos - now);
        try {
            probeTimer = scheduler.schedule(this::probeQuietly, delay, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.debug("gateway budget: clearance probe not scheduled for env {} — scheduler is shut down",
                    environmentId);
        }
    }

    /** The scheduler's entry point — a one-shot task nobody calls {@code get()} on (see F4). */
    private void probeQuietly() {
        try {
            runCircuitProbe();
        } catch (RuntimeException e) {
            log.error("env {} ({}): the Cloudflare clearance probe failed unexpectedly; the circuit "
                    + "stays open until the next one", environmentId, environmentName, e);
            lock.lock();
            try {
                probeInFlight = false;
                if (circuitOpen.get()) {
                    long now = nanos.getAsLong();
                    nextProbeAtNanos = now + settings.blockProbeInterval().toNanos();
                    scheduleProbeLocked(now);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Issue the one clearance probe if it is due, and close or re-arm the circuit on its answer
     * (AD-13 as amended by A16.1). Package-private so tests drive it on a manual clock, the way they
     * drive {@link #admitWaiters()}.
     * <ul>
     *   <li>Not open, or a probe already in flight: nothing.</li>
     *   <li>Not yet due on this budget's clock: re-arm for the remainder.</li>
     *   <li>Answered and <b>not</b> a block — any status, the gateway's own JSON error included:
     *       <b>closed</b>, one INFO line, gauge to 0, and an admission pass.</li>
     *   <li>Answered with a block: stays open for another interval, one WARN line,
     *       {@code gateway_edge_blocks_total{endpoint="circuit-probe"}}.</li>
     *   <li>No answer at all (connect failure, timeout) or no probe bound: stays open. Not hearing
     *       from the edge is not evidence it stopped refusing us.</li>
     * </ul>
     * The probe is stamped with {@code count("circuit-probe")} — unconditionally, because it is an
     * HTTP GET and not an upgrade (A5.3, A29.5).
     */
    void runCircuitProbe() {
        CircuitProbe probe = circuitProbe;
        lock.lock();
        try {
            if (!circuitOpen.get() || probeInFlight) {
                return;
            }
            long now = nanos.getAsLong();
            if (now - nextProbeAtNanos < 0) {
                scheduleProbeLocked(now);
                return;
            }
            if (probe == null) {
                nextProbeAtNanos = now + settings.blockProbeInterval().toNanos();
                scheduleProbeLocked(now);
            } else {
                probeInFlight = true;
            }
        } finally {
            lock.unlock();
        }
        if (probe == null) {
            log.warn("env {} ({}): Cloudflare edge block — no clearance probe is bound for this "
                            + "environment (its apiGateway was never seen), so the circuit stays open; "
                            + "restarting bot-manager is the way to close it",
                    environmentId, environmentName);
            return;
        }

        count("circuit-probe");
        CircuitProbe.Answer answer = null;
        String failure = null;
        try {
            answer = probe.probe();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = "interrupted";
        } catch (Exception e) {
            failure = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }

        boolean closed = false;
        Duration openFor = Duration.ZERO;
        String rayForLog;
        List<Runnable> deferred = new ArrayList<>();
        lock.lock();
        try {
            probeInFlight = false;
            long now = nanos.getAsLong();
            if (answer != null && !answer.verdict().edgeBlock()) {
                circuitOpen.set(false);
                closed = true;
                openFor = Duration.ofNanos(Math.max(0L, now - circuitOpenedAtNanos));
                circuitCfRay = null;
                if (probeTimer != null) {
                    probeTimer.cancel(false);
                    probeTimer = null;
                }
                admitWaitersLocked(now, deferred);
            } else {
                if (answer != null && answer.verdict().cfRay() != null) {
                    circuitCfRay = answer.verdict().cfRay();
                }
                nextProbeAtNanos = now + settings.blockProbeInterval().toNanos();
                scheduleProbeLocked(now);
            }
            rayForLog = circuitCfRay;
        } finally {
            lock.unlock();
            runAfterUnlock(deferred);
        }

        if (closed) {
            log.info("env {} ({}): Cloudflare edge block cleared — clearance probe answered HTTP {}; "
                            + "circuit closed after {}",
                    environmentId, environmentName, answer.status(), openFor);
            reportThrottleState();
        } else if (answer != null) {
            edgeBlockCounters.get(GatewayEndpoint.CIRCUIT_PROBE).increment();
            log.warn("env {} ({}): Cloudflare edge block still in force — clearance probe refused "
                            + "(HTTP {}, cf-ray {}); next probe in {}",
                    environmentId, environmentName, answer.status(),
                    rayForLog == null ? "-" : rayForLog, settings.blockProbeInterval());
        } else {
            log.warn("env {} ({}): Cloudflare clearance probe got no answer ({}); the circuit stays "
                            + "open, next probe in {}",
                    environmentId, environmentName, failure, settings.blockProbeInterval());
        }
    }

    // ------------------------------------------------------------------ declared demand

    @Override
    public Reservation reserve(RequestTier tier, int permits, GatewayRequestScope scope) {
        int declared = Math.max(0, permits);
        String key = reservationKey(tier, scope);
        TrackedReservation reservation;
        List<Runnable> deferredHere = new ArrayList<>();
        lock.lock();
        try {
            // createdNanos read INSIDE the lock, like every other value the accounting uses — the
            // last sibling of the defect the clock-discipline fix closed (review F9). The direction
            // was safe (a reservation retired sooner, never later) but the class javadoc's claim
            // that the clock is read under the lock was unqualified, and an unqualified claim is
            // what the next reader copies.
            reservation = new TrackedReservation(tier, declared, key, nanos.getAsLong());
            reserved.get(tier).addAndGet(declared);
            if (key != null) {
                // One start per group at a time (the attempt registry's putIfAbsent), so a
                // second live reservation on the same key is not expressible. If one ever
                // appears, the newer owns the key and the older still releases its own
                // remainder — it just stops being consumable, which is the safe direction.
                //
                // ...but the evicted one also becomes invisible to the TTL, which iterates this
                // MAP, so if its release() never runs its contribution to reserved{tier} shrinks
                // the lower tiers' ceilings for the life of the JVM with no backstop (review F11).
                // "Not expressible" is a whole-program reachability argument about a map key, and
                // this codebase's standing position on those is that they are not enough on their
                // own. So: the state the guard cannot cover cannot arrive silently.
                TrackedReservation superseded = reservations.put(key, reservation);
                if (superseded != null) {
                    int orphaned = superseded.outstanding;
                    deferredHere.add(() -> log.warn("env {} ({}): a second {} reservation arrived "
                                    + "for {} while one was live — the older one's {} unconsumed "
                                    + "requests are now outside the leak TTL and will shrink the "
                                    + "lower tiers' ceilings until its own release() runs",
                            environmentId, environmentName, tier, key, orphaned));
                }
            }
            admitWaitersLocked(nanos.getAsLong(), deferredHere);
        } finally {
            lock.unlock();
            runAfterUnlock(deferredHere);
        }
        log.debug("gateway budget: reserved {} {} requests for {} on env {}",
                declared, tier, describe(scope), environmentId);
        return reservation;
    }

    /**
     * Draw one permit down from the reservation this admission belongs to, if any.
     * <p>
     * Matched on {@code (tier, botGroupId)} — AD-7's rule — so a group's own flood consumes the
     * demand it declared instead of paying for it twice. Caller holds {@link #lock}.
     */
    private void consumeReservationLocked(RequestTier tier, GatewayRequestScope scope) {
        String key = reservationKey(tier, scope);
        if (key == null) {
            return;
        }
        TrackedReservation reservation = reservations.get(key);
        if (reservation != null && reservation.consumeOneLocked()) {
            reserved.get(tier).decrementAndGet();
            if (reservation.outstanding == 0) {
                reservations.remove(key, reservation);
            }
        }
    }

    /**
     * Retire a reservation nobody released within {@code 2 × window} (AD-7).
     * <p>
     * A leaked reservation is worse than a missing one: it shrinks the lower tiers' ceilings
     * for the life of the JVM, so registration and drift reads would be starved by a start
     * that finished hours ago. {@code startLocked}'s {@code finally} releases it; this is the
     * guard for the paths where that {@code finally} did not run. Caller holds {@link #lock}.
     */
    private int expireStaleReservationsLocked(long now, List<Runnable> warnings) {
        if (reservations.isEmpty()) {
            return 0;
        }
        int freed = 0;
        long ttl = settings.window().toNanos() * 2;
        Iterator<Map.Entry<String, TrackedReservation>> it = reservations.entrySet().iterator();
        while (it.hasNext()) {
            TrackedReservation reservation = it.next().getValue();
            if (now - reservation.createdNanos >= ttl) {
                int remainder = reservation.retireLocked();
                if (remainder > 0) {
                    freed += remainder;
                    reserved.get(reservation.tier).addAndGet(-remainder);
                    RequestTier tier = reservation.tier;
                    // Returned as a deferred action rather than logged here (review F10). This
                    // method is called from admit() — the hottest path in the budget — with the
                    // lock held, and track 1's appender is `blocking = true` by deliberate choice
                    // (CLAUDE.md AD-24), so a saturated log queue would park the emitting thread
                    // WITH THE BUDGET LOCK HELD and stall every gateway request on this
                    // environment until the appender drains. reportThrottleState's javadoc states
                    // that rule; this was the one place that broke it.
                    warnings.add(() -> log.warn("env {} ({}): retiring a leaked {} reservation of "
                                    + "{} unconsumed requests after {} — a start did not release "
                                    + "its declared demand",
                            environmentId, environmentName, tier, remainder, Duration.ofNanos(ttl)));
                }
                it.remove();
            }
        }
        return freed;
    }

    private static String reservationKey(RequestTier tier, GatewayRequestScope scope) {
        if (scope == null || scope.botGroupId() == null) {
            return null;
        }
        return tier.name() + '|' + scope.botGroupId();
    }

    /**
     * Declared demand that gives itself back exactly once.
     * <p>
     * Idempotent release matters more than it looks: {@code startLocked}'s {@code finally}
     * releases it, and so will the {@code try}-with-resources form if a caller uses one. A
     * double release would drive the gauge negative and hand the lower tiers ceiling room that
     * was never returned.
     */
    private final class TrackedReservation implements Reservation {
        private final RequestTier tier;
        private final String key;
        private final long createdNanos;
        /** Guarded by {@link SlidingWindowGatewayBudget#lock}. */
        private int outstanding;

        private TrackedReservation(RequestTier tier, int permits, String key, long createdNanos) {
            this.tier = tier;
            this.outstanding = permits;
            this.key = key;
            this.createdNanos = createdNanos;
        }

        @Override
        public void release() {
            int remainder;
            List<Runnable> deferred = new ArrayList<>();
            lock.lock();
            try {
                remainder = outstanding;
                outstanding = 0;
                if (remainder > 0) {
                    reserved.get(tier).addAndGet(-remainder);
                }
                if (key != null) {
                    reservations.remove(key, this);
                }
                // Releasing the remainder raises the lower tiers' effective ceilings, so the
                // queues have to be walked before this returns — otherwise a DEFAULT waiter
                // sits until the next stamp expiry for room that already exists.
                admitWaitersLocked(nanos.getAsLong(), deferred);
            } finally {
                lock.unlock();
                runAfterUnlock(deferred);
            }
        }

        @Override
        public int remaining() {
            lock.lock();
            try {
                return outstanding;
            } finally {
                lock.unlock();
            }
        }

        /** @return true if a permit was available and has been drawn down. */
        private boolean consumeOneLocked() {
            if (outstanding <= 0) {
                return false;
            }
            outstanding--;
            return true;
        }

        private int retireLocked() {
            int remainder = outstanding;
            outstanding = 0;
            return remainder;
        }
    }

    // ------------------------------------------------------------------ cancellation

    @Override
    public void cancelScope(String botGroupId) {
        if (botGroupId == null) {
            return;
        }
        List<Runnable> deferred = new ArrayList<>();
        int woken = 0;
        lock.lock();
        try {
            for (RequestTier tier : RequestTier.values()) {
                Iterator<Waiter> it = queues.get(tier).iterator();
                while (it.hasNext()) {
                    Waiter waiter = it.next();
                    if (waiter.scope == null || !botGroupId.equals(waiter.scope.botGroupId())) {
                        continue;
                    }
                    it.remove();
                    queued.get(tier).decrementAndGet();
                    counter(tier, OUTCOME_CANCELLED).increment();
                    woken++;
                    deferred.add(() -> waiter.admitted.completeExceptionally(
                            new GatewayRequestCancelledException(environmentId, describe(waiter.scope))));
                }
            }
            // Cancelling frees no window room (nothing was stamped) but it does free the
            // reservation's pre-emptive shrink once startLocked's finally releases it, and it
            // can unblock a tier whose head was a cancelled waiter behind a live one.
            admitWaitersLocked(nanos.getAsLong(), deferred);
        } finally {
            lock.unlock();
            runAfterUnlock(deferred);
        }
        reportThrottleState();
        // DEBUG, not INFO: this fires on every /stop and every DELETE of a group, and the
        // operator-facing statement ("stop requested while a start was in flight") is already
        // one tier-1 line at the call site in BotGroupBehaviorService.
        log.debug("gateway budget: cancelScope({}) on env {} woke {} queued requests",
                botGroupId, environmentId, woken);
    }

    /** One queued request. The future is per-waiter — never a shared condition (AD-6). */
    private static final class Waiter {
        private final RequestTier tier;
        private final GatewayRequestScope scope;
        private final long enqueuedNanos;
        private final CompletableFuture<Void> admitted = new CompletableFuture<>();

        private Waiter(RequestTier tier, GatewayRequestScope scope, long enqueuedNanos) {
            this.tier = tier;
            this.scope = scope;
            this.enqueuedNanos = enqueuedNanos;
        }
    }

    /**
     * Remove {@code waiter} from its queue.
     *
     * @return {@code true} if it was still queued — {@code false} means it was admitted
     *         concurrently, and the caller must treat the request as going out.
     */
    private boolean dequeue(Waiter waiter) {
        lock.lock();
        try {
            if (queues.get(waiter.tier).remove(waiter)) {
                queued.get(waiter.tier).decrementAndGet();
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------ throttle reporting

    /**
     * One WARN when a tier first becomes throttled and one INFO when it clears, per
     * environment per tier, with a 5-minute re-arm (AD-20).
     * <p>
     * Called <b>outside</b> the lock, always: this is an operator-facing line on the edge of a
     * paced fleet, and emitting it from inside the budget lock would put a log-appender queue
     * wait on the critical path of every admission.
     * <p>
     * Under {@code enforce} a queue that is briefly non-empty is <em>normal</em> — a 300-bot
     * group start is one window of queueing by design (AD-18) — so the re-arm is what keeps
     * this from becoming noise. The signal that actually hurts is sustained queueing, and that
     * is a Prometheus rule ({@code GatewayBudgetSustainedQueue}) rather than a log line,
     * because "still queued fifteen minutes later" is not a statement any single call site can
     * make.
     */
    private void reportThrottleState() {
        long now = nanos.getAsLong();
        for (RequestTier tier : RequestTier.values()) {
            int depth = queued.get(tier).get();
            // `warned` means "a throttled WARN is outstanding for this tier and its matching
            // cleared INFO has not been emitted". The two are paired deliberately: a WARN with
            // no clear leaves an operator unable to tell a resolved burst from a live one, and
            // a clear with no WARN is a line about something nobody was told about.
            AtomicBoolean warned = throttleReported.get(tier);
            AtomicLong lastWarn = throttleReportedAt.get(tier);
            if (depth > 0) {
                if (warned.get()) {
                    continue;
                }
                long last = lastWarn.get();
                if (last != Long.MIN_VALUE && now - last < THROTTLE_LOG_REARM.toNanos()) {
                    // Inside the re-arm window: stay silent in BOTH directions, so a flapping
                    // edge cannot spam tier 1 with alternating throttled/cleared pairs.
                    continue;
                }
                if (!warned.compareAndSet(false, true)) {
                    continue;
                }
                lastWarn.set(now);
                log.warn("env {} ({}): {} tier throttled — window {}/{}, queue {}",
                        environmentId, environmentName, tier, windowRequests(),
                        settings.hardCap(), depth);
            } else if (warned.compareAndSet(true, false)) {
                log.info("env {} ({}): {} tier throttle cleared — window {}/{}",
                        environmentId, environmentName, tier, windowRequests(), settings.hardCap());
            }
        }
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

    @Override
    public Duration maxWait(RequestTier tier) {
        return settings.isUnboundedWait(tier) ? null : settings.maxWait(tier);
    }

    @Override
    public Duration registrationMaxWait() {
        return settings.registrationMaxWait();
    }

    @Override
    public Duration observeModePacing() {
        if (settings.mode() == GatewayBudgetMode.ENFORCE) {
            // The budget is doing the pacing. A caller that also slept would be paced twice and
            // would blame this class for being slow.
            return Duration.ZERO;
        }
        // window / default.ceiling — the DEFAULT tier's own share of the window, expressed as a
        // period. 300 s / 500 = 600 ms with the shipped policy. Derived rather than configured on
        // purpose: a separate property is a second number that can disagree with the ceiling it
        // is supposed to be a restatement of.
        return settings.window().dividedBy(settings.ceiling(RequestTier.DEFAULT));
    }

    /** The policy this budget was built with. */
    public GatewayBudgetSettings settings() {
        return settings;
    }

    /**
     * Stop the wake-up scheduler. Not wired to a Spring lifecycle: budgets live as long as the
     * JVM (the registry never evicts one, so an environment's window survives its clients being
     * rebuilt), and a bounded wait still times out and a cancel still wakes without a
     * scheduler. Tests call it so a suite does not accumulate one virtual scheduler per fixture.
     */
    public void shutdown() {
        lock.lock();
        try {
            if (wakeUp != null) {
                wakeUp.cancel(false);
            }
            if (probeTimer != null) {
                probeTimer.cancel(false);
            }
        } finally {
            lock.unlock();
        }
        scheduler.shutdownNow();
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

    private static String describe(GatewayRequestScope scope) {
        return scope == null ? "-" : scope.describe();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
