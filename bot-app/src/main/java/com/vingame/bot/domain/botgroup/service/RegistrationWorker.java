package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.GatewayBudgetException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.RegistrationState;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.dto.RegistrationOutcome;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Creates a bot group's gateway accounts, one account at a time, off the request thread
 * (GATEWAY_REQUEST_BUDGET A2 / A6 Phase 4).
 *
 * <h2>What this replaced, and why it is not a tuning of it</h2>
 * {@code BotGroupService.save} used to call {@code ApiGatewayClient.registerUsers(prefix,
 * password, count)} <b>synchronously, on the Tomcat worker</b>, fanning {@code count} virtual
 * threads out under a semaphore. Three properties of that shape were not fixable in place:
 * <ul>
 *   <li><b>It held an HTTP request open for the whole job.</b> Once each account could wait
 *       {@code bot.gateway.budget.registration.max-wait} (15 m), a 200-account create was a worst
 *       case of ~5 hours on one request thread (A25.2). A 500-account create under Cloudflare's
 *       1,000-per-5-minutes rule <em>cannot</em> be a synchronous HTTP call — the arithmetic
 *       alone forbids it.</li>
 *   <li><b>It could not resume.</b> A JVM restart, a deploy, or one refused account left a group
 *       whose document said nothing about how far it had got, and the only recovery was to
 *       delete it and start again — spending the whole window a second time on accounts that
 *       already existed. Half-registered groups are the state nobody comes back to.</li>
 *   <li><b>It reported our own pacing as an upstream failure.</b> A budget refusal became a
 *       per-account failure string, then an {@code UpstreamRegistrationException}, then a
 *       <b>502 "Game server error"</b> about a gateway that was never asked (A25.1, review F2).</li>
 * </ul>
 * Asynchrony is what fixes the first, the two persisted counters fix the second, and A2.6's
 * distinction — a budget refusal re-queues, a gateway refusal costs an attempt — fixes the third.
 *
 * <h2>The group is the job</h2>
 * There is no job collection, no scheduler table and no id to correlate (A2.1). Usernames are
 * {@code namePrefix + index}, so the work list, the progress and the identity are all already in
 * the group document: {@code registeredCount} / {@code namedCount} against {@code botCount}.
 * <p>
 * <b>That only works because this worker is serial and in-order.</b> {@code registeredCount = k}
 * means "indices 1..k are done" and means nothing at all under a parallel worker. Keeping one
 * thread is therefore a correctness requirement, not a politeness to the gateway — and the
 * gateway pacing is the budget's job, not this class's. <b>It is not a rate limiter and must not
 * grow one.</b>
 *
 * <h2>Selection is driven by the persisted state, never by an in-memory queue</h2>
 * Each tick re-reads {@code findByRegistrationState(PENDING)}, exactly as
 * {@link DeadGroupRecoveryScheduler} reads {@code targetStatus == DEAD}, and for the same reason:
 * a group whose registration was interrupted by a restart is absent from every in-memory
 * structure, and that is precisely the case with the longest time-to-notice. Resumption across a
 * JVM restart therefore costs no extra machinery — {@link #onStartup()} only announces it.
 * {@link #enqueue(String)} exists so a create starts within milliseconds instead of within a
 * tick; it adds promptness, never correctness.
 *
 * <h2>What costs an attempt and what does not (A2.6)</h2>
 * <ul>
 *   <li><b>A {@link GatewayBudgetException} — a budget timeout, an open Cloudflare circuit, a
 *       cancelled scope — costs no attempt, but it does cost the group its turn.</b> The group is
 *       left {@code PENDING} and is passed over for
 *       {@code bot.registration.deferral-backoff-seconds} so the next-oldest group gets the
 *       thread (review B2). Those say "not now"; they do not say "this account cannot be
 *       created", and a group that went {@code FAILED} because the fleet was busy would need a
 *       human for no reason.</li>
 *   <li><b>A gateway refusal costs one attempt</b> against
 *       {@code bot.registration.max-attempts-per-user}. Spending it leaves the group
 *       {@code FAILED} with the reason on the document, one ERROR, and no silent retry —
 *       {@code POST /{id}/registration/retry} is the way back, and it resumes from
 *       {@code registeredCount + 1} rather than starting over.</li>
 *   <li><b>A transport failure costs one <em>transport</em> attempt</b> against the larger
 *       {@code bot.registration.max-transport-attempts-per-user} (review S3). "We could not ask"
 *       is neither a yes nor a no, and a 60-second network blip must not permanently stop a
 *       500-account create — but it still stops eventually, because a group that cannot reach its
 *       gateway at all must not be invisible.</li>
 * </ul>
 * The worker never skips an index. Skipping would make {@code registeredCount} stop meaning
 * "1..k are done", which is the invariant the whole design rests on.
 *
 * <h2>The target is the document's, not this pass's</h2>
 * {@code botCount} is re-read on every index, and the completion write is conditional on it
 * (QA F-1). A pass can legitimately last hours, and A2.7 makes "raise {@code botCount} to ask for
 * more accounts" a product feature, so a target captured once at the top of the loop was a target
 * that could be silently out of date for the whole job.
 */
@Slf4j
@Component
public class RegistrationWorker {

    /** {@code outcome} tag values on {@code registration_accounts_total}. */
    static final String OUTCOME_SUCCESS = "success";
    static final String OUTCOME_EXISTS = "exists";
    static final String OUTCOME_FAILED = "failed";

    /** See {@code DeadGroupRecoveryScheduler.TAG_UNRESOLVED} — a dropped tag key is a dropped series. */
    static final String TAG_UNRESOLVED = "unknown";

    /**
     * The floor on the registration wait, for the one budget that answers {@link Duration#ZERO}.
     * <p>
     * {@code GatewayBudgetSettings} rejects a zero {@code registration.max-wait} outright (review
     * F5), so a configured budget cannot produce one. {@code GatewayBudget.UNLIMITED} can, and
     * does — harmlessly there, because it never waits. The floor exists so that the value this
     * class reads is a wait rather than {@code tryExecute}'s <em>now or never</em>, whatever
     * budget it came from: a zero reaching {@code execute(…, maxWait)} on a real budget would
     * turn every registration into a fail-fast the moment the DEFAULT queue is non-empty, and it
     * would do it silently.
     */
    private static final Duration MIN_REGISTRATION_WAIT = Duration.ofSeconds(30);

    private final BotGroupRepository repository;
    private final MongoTemplate mongoTemplate;
    private final EnvironmentClientRegistry clientRegistry;
    private final GatewayBudgetRegistry gatewayBudgetRegistry;
    private final EnvironmentService environmentService;
    private final BotMetrics botMetrics;

    private final long tickSeconds;
    private final int maxAttemptsPerUser;
    private final int maxTransportAttemptsPerUser;
    private final int displayNameRetries;
    private final Duration failureBackoff;

    /**
     * How long a group whose environment refused it on the budget is passed over for (review B2).
     * <p>
     * Floored at one tick, which is the whole point: selection is "oldest PENDING that is due",
     * and a budget deferral deliberately spends no attempt (A2.6), so without a {@code notBefore}
     * the same group is the oldest due group again ten seconds later, <em>forever</em>. One starved
     * environment then held the single worker thread — for up to {@code registration.max-wait}
     * per pass — and no group on any other environment registered a single account for the whole
     * duration. That is the anti-starvation half of {@link DeadGroupRecoveryScheduler}'s idiom
     * ("a permanently failing group cannot starve the others"), which is the one half this class
     * copied without.
     */
    private final Duration deferralBackoff;

    /**
     * Groups called off while registering — a {@code DELETE} landing mid-job. Read by the scope
     * predicate, so it must stay a plain set read: the budget asks it <b>while holding its own
     * lock</b>, and a predicate that blocks there stalls every gateway request of that
     * environment (see {@code GatewayRequestScope.cancelled}).
     */
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    /** Per-group attempt state for the index currently being worked. In memory, by design. */
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    /**
     * Cached for the gauges, so a scrape never issues a Mongo query — three app instances at a
     * 10 s scrape is the kind of gauge that quietly becomes a database load problem.
     * <p>
     * Refreshed at the top of every tick <b>and again inside a long pass</b> whenever the cache is
     * older than one tick (review S5). A pass blocks the worker thread for the whole duration of
     * one group's registration — hours for a 500-account group under {@code enforce} — and before
     * the in-pass refresh both gauges reported the state at the start of it, so
     * {@code RegistrationStalled} was blind to a group that failed while another was registering.
     * <p>
     * <b>What is still not covered, and is the trap CLAUDE.md names for {@code LogQueueSaturated}'s
     * vanished appender:</b> if this worker's scheduled task dies ({@code tickQuietly} catches
     * {@code Exception}, not {@code Throwable}) or its executor is shut down, both gauges keep
     * reporting their last values indefinitely and a stalled registration is unalertable. The
     * freshness stamp below is what a future heartbeat metric would publish; today it only bounds
     * the in-pass staleness.
     */
    private volatile int pendingGroups;
    private volatile int failedGroups;
    private volatile Instant gaugesRefreshedAt;

    private ScheduledExecutorService worker;

    public RegistrationWorker(BotGroupRepository repository,
                              MongoTemplate mongoTemplate,
                              EnvironmentClientRegistry clientRegistry,
                              GatewayBudgetRegistry gatewayBudgetRegistry,
                              EnvironmentService environmentService,
                              BotMetrics botMetrics,
                              @Value("${bot.registration.tick-seconds:10}") long tickSeconds,
                              @Value("${bot.registration.max-attempts-per-user:3}") int maxAttemptsPerUser,
                              @Value("${bot.registration.display-name-retries:5}") int displayNameRetries,
                              @Value("${bot.registration.failure-backoff-seconds:30}") long failureBackoffSeconds,
                              @Value("${bot.registration.max-transport-attempts-per-user:10}")
                              int maxTransportAttemptsPerUser,
                              @Value("${bot.registration.deferral-backoff-seconds:60}")
                              long deferralBackoffSeconds) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.clientRegistry = clientRegistry;
        this.gatewayBudgetRegistry = gatewayBudgetRegistry;
        this.environmentService = environmentService;
        this.botMetrics = botMetrics;
        this.tickSeconds = Math.max(1, tickSeconds);
        this.maxAttemptsPerUser = Math.max(1, maxAttemptsPerUser);
        this.displayNameRetries = Math.max(1, displayNameRetries);
        this.failureBackoff = Duration.ofSeconds(Math.max(0, failureBackoffSeconds));
        // At least as many as a gateway refusal gets, and by default more — see recordFailure.
        this.maxTransportAttemptsPerUser =
                Math.max(this.maxAttemptsPerUser, maxTransportAttemptsPerUser);
        // At least one tick, so another group always gets a turn before this one is reconsidered.
        this.deferralBackoff =
                Duration.ofSeconds(Math.max(this.tickSeconds, deferralBackoffSeconds));
    }

    @PostConstruct
    public void start() {
        // Single thread, and it is the serialisation (A2.4). scheduleWithFixedDelay rather than
        // AtFixedRate: a 500-account group legitimately occupies this thread for a long time, and
        // fixed-rate would queue up a burst of ticks behind it that all find the same group.
        worker = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("registration-worker").factory());
        worker.scheduleWithFixedDelay(this::tickQuietly, tickSeconds, tickSeconds, TimeUnit.SECONDS);
        log.info("Registration worker started (tick={}s, max-attempts-per-user={}, "
                        + "display-name-retries={}, failure-backoff={}s)",
                tickSeconds, maxAttemptsPerUser, displayNameRetries, failureBackoff.toSeconds());
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down registration worker...");
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    /**
     * <b>Announce</b> any registration a previous JVM left unfinished (A2.2). It does not resume
     * it: the first tick does, up to {@code tick-seconds} later. The first line of this javadoc
     * said "and immediately resume" until review T5, three paragraphs above the sentence that
     * corrects it.
     * <p>
     * The resumption itself needs nothing from this method — selection reads the persisted state,
     * so the first tick would find these groups anyway. What it adds is the one line an operator
     * needs at boot: a group created five minutes before a deploy is otherwise indistinguishable
     * from a group nobody ever created, and the failure mode of *not* noticing is a half-populated
     * group that gets started and reports authentication failures for the accounts that do not
     * exist yet.
     * <p>
     * Ordering against the start daisy-chain does not matter and is deliberately not arranged: a
     * group that is still registering cannot be started at all ({@code validateStartable}), so the
     * two chains cannot collide over the same group.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            List<BotGroup> resuming = repository.findByRegistrationState(RegistrationState.PENDING);
            if (!resuming.isEmpty()) {
                log.info("Registration worker: {} bot groups resuming account registration",
                        resuming.size());
            }
        } catch (RuntimeException e) {
            // Never rethrown, unlike the start chain's equivalent. An exception out of an
            // ApplicationReadyEvent listener closes the context and exits the JVM, and losing the
            // whole instance over a log line about groups the tick will find by itself in ten
            // seconds would be a self-inflicted outage.
            log.error("Registration worker: could not read the groups to resume: {}", e.toString(), e);
        }
    }

    /**
     * Ask the worker to look at {@code botGroupId} now rather than on the next tick, and clear
     * whatever attempt state it was carrying.
     * <p>
     * The id is used for the state reset and the log line only — the tick re-reads the persisted
     * state either way, so this is promptness, not routing.
     * <p>
     * <b>"An enqueue that is lost costs at most {@code tick-seconds}" is only true while the
     * worker is idle</b> (review S7), which is the case it was written for — a fresh create on a
     * quiet instance. While a 500-account group is being registered, a lost enqueue costs that job
     * plus {@code tick-seconds}, and an enqueue that is <em>not</em> lost costs the same, because
     * it queues behind the running task on the single thread. Both are acceptable and neither is
     * load-bearing; the sentence matters because it is what the next reader will use to decide
     * whether this path needs hardening. Running on the worker's own single thread is what keeps
     * the "one group at a time" property true for a create that arrives mid-registration.
     */
    public void enqueue(String botGroupId) {
        cancelled.remove(botGroupId);
        attempts.remove(botGroupId);
        if (worker == null) {
            return;
        }
        try {
            worker.execute(this::tickQuietly);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The context is closing. The group stays PENDING and the next JVM resumes it.
            log.debug("Registration worker is shutting down; group {} stays pending", botGroupId);
        }
    }

    /**
     * Call off a group's registration — a {@code DELETE} while accounts are being created.
     * <p>
     * <b>Two halves, and both are needed.</b> The flag stops the worker's loop between accounts
     * and makes the scope's predicate answer true; {@code cancelScope} wakes a request that is
     * already parked <em>inside</em> the budget, which the flag alone cannot reach. Without the
     * second half a delete arriving during a starved window waits out up to fifteen minutes of
     * registration wait, on the HTTP thread, for an account nobody wants.
     * <p>
     * {@code find()} rather than {@code forEnvironment()} deliberately: conjuring a budget, and a
     * fresh set of {@code gateway_budget_*} series, as a side effect of a delete would be a lie
     * about the fleet. A null means nothing was ever queued there, which is the common case.
     */
    public void cancel(String botGroupId, String environmentId) {
        cancelled.add(botGroupId);
        attempts.remove(botGroupId);
        if (environmentId == null) {
            return;
        }
        GatewayBudget budget = gatewayBudgetRegistry.find(environmentId);
        if (budget != null) {
            budget.cancelScope(botGroupId);
        }
    }

    /** Groups whose accounts are still being created. Backs {@code registration_pending_groups}. */
    public int getPendingGroupCount() {
        return pendingGroups;
    }

    /** Groups whose registration stopped and will not resume unattended. Backs the alert. */
    public int getFailedGroupCount() {
        return failedGroups;
    }

    private void tickQuietly() {
        try {
            tick();
        } catch (Exception e) {
            log.error("Registration worker tick failed: {}", e.getMessage(), e);
        }
    }

    /** One pass. Package-private so tests can drive it without racing a scheduler. */
    void tick() {
        List<BotGroup> pending = repository.findByRegistrationState(RegistrationState.PENDING);
        pendingGroups = pending.size();
        failedGroups = (int) repository.countByRegistrationState(RegistrationState.FAILED);
        gaugesRefreshedAt = Instant.now();
        pruneCancelled(pending);

        Instant now = Instant.now();
        BotGroup next = pending.stream()
                .filter(group -> !cancelled.contains(group.getId()))
                // isDue now covers a budget DEFERRAL as well as a failure backoff (review B2), so
                // a group whose environment cannot admit a DEFAULT request steps aside and the
                // next-oldest group gets the thread.
                .filter(group -> isDue(group.getId(), now))
                // Oldest create first, so a 500-account group cannot be starved by a stream of
                // small ones and the order is the same on every instance. createdAt is stamped by
                // BotGroupService.save on the persist that made the group PENDING, so it is never
                // null here; the id is the tie-break purely for determinism.
                .min(Comparator.comparing((BotGroup g) -> g.getCreatedAt(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(BotGroup::getId))
                .orElse(null);

        if (next != null) {
            register(next);
        }
    }

    /**
     * Create this group's remaining accounts, in index order, until they are all done or
     * something says stop.
     * <p>
     * It runs to completion on the worker thread — a 500-account group holds it for the whole
     * job, which <b>is</b> the serialisation A2.1's high-water mark depends on. Nothing
     * time-sensitive may be added to this tick for the same reason
     * {@code DeadGroupRecoveryScheduler}'s probe has a scheduler of its own.
     */
    private void register(BotGroup group) {
        String id = group.getId();
        // Tag values are substituted rather than dropped: BotMetrics.mdcTags() skips an absent
        // value, and the Prometheus exposition keeps only the FIRST label-key set it sees under a
        // metric name, silently omitting every later shape for the life of the JVM. One group
        // with an unresolvable environment would therefore delete every other group's series.
        String environmentTag = tagValue(group.getEnvironmentId());
        String product = resolveProduct(group.getEnvironmentId());

        BotMdc.setGroupContext(id, environmentTag, product);
        try {
            // All three outcome series at zero under this group's tags, before anything can
            // increment one. A counter that first appears at 1 is invisible to increase() — see
            // BotMetrics.initRegistrationSeries for why that silently disarms an alert rule.
            botMetrics.initRegistrationSeries(OUTCOME_SUCCESS, OUTCOME_EXISTS, OUTCOME_FAILED);

            ApiGatewayClient client = clientRegistry.getClients(group.getEnvironmentId())
                    .getApiGatewayClient();
            Duration maxWait = registrationWait(client);
            Duration pacing = client.observeModePacing();
            boolean names = client.hasDisplayNames();
            GatewayRequestScope scope = GatewayRequestScope.registration(
                    id, group.getNamePrefix(), () -> cancelled.contains(id));

            int target = group.getBotCount();
            int registered = group.getRegisteredCount();
            int named = group.getNamedCount();

            while (true) {
                // The target is RE-READ, once per index (QA F-1). `target` used to be captured
                // before the loop, from a document this pass may hold for hours, and
                // recordCompletion then cleared registrationState against that stale value: a
                // botCount raise landing mid-pass was ERASED — botCount 20, registeredCount 10,
                // not PENDING, so no tick ever selected the group again, the extra accounts were
                // never created, and the bots built on the missing indices failed to authenticate
                // at start, presenting as an auth outage rather than as a create that lied. The
                // window was the whole duration of a pass, which is exactly when A2.7's "raise
                // botCount to register more" is used. One indexed read per account, against a
                // gateway call of 100-300 ms plus pacing, and it honours a LOWERED count too.
                target = currentTarget(id, target);
                if (isComplete(registered, named, target, names)) {
                    break;
                }
                if (cancelled.contains(id)) {
                    log.info("group {} ({}): registration cancelled at {}/{} accounts",
                            id, group.getName(), registered, target);
                    return;
                }
                refreshGaugesIfStale();

                // The next index to work on. With a name pool that is the first index whose NAME
                // has not landed, which may already be registered — that is the resume case the
                // two counters exist to express (A17.3), and it costs register + update-fullname
                // rather than the three requests the plan budgeted for it (A30).
                int index = (names ? named : registered) + 1;
                String username = group.getNamePrefix() + index;

                try {
                    if (registered < index) {
                        RegistrationOutcome outcome =
                                client.registerOne(group.getNamePrefix(), group.getPassword(),
                                        index, scope, maxWait);
                        registered = index;
                        persistProgress(id, registered, named);
                        botMetrics.incRegistrationAccount(
                                outcome == RegistrationOutcome.ALREADY_EXISTED
                                        ? OUTCOME_EXISTS : OUTCOME_SUCCESS);
                        // DEBUG, per CLAUDE.md's tier rule: one line per account is a rate that
                        // is a function of account count. The group-level statement is the single
                        // completion line below; the per-account rate is bot metrics' job.
                        log.debug("Registered {} ({}/{}) — {}", username, index, target, outcome);
                        sleep(pacing);
                    }

                    if (names && named < index) {
                        String displayName = client.setDisplayNameWithRetry(
                                username, displayNameRetries, scope, maxWait);
                        named = index;
                        persistProgress(id, registered, named);
                        if (displayName == null) {
                            // WARN and move on, which is the pre-existing behaviour and the right
                            // one: the account exists and works, it is just anonymous. Not silent,
                            // because a nameless account in a RIK ziczac room stalls the round
                            // engine for every player in it, and this line is how an operator
                            // finds the handful to name by hand.
                            log.warn("Could not set a display name for {} after {} attempts — "
                                            + "the account exists but is nameless",
                                    username, displayNameRetries);
                        } else {
                            log.debug("Set display name '{}' for {}", displayName, username);
                        }
                        sleep(pacing);
                    }

                    // Progress on this index clears whatever the previous one spent.
                    attempts.remove(id);

                } catch (GatewayBudgetException e) {
                    // A2.6: "not now", not "this cannot be created". No attempt is consumed, the
                    // group stays PENDING, and the next tick picks it up from exactly here. This
                    // is the classification that used to produce a 502 about a healthy gateway.
                    //
                    // It does cost the group its TURN, though (review B2): without a notBefore
                    // this group is the oldest due PENDING group again on the very next tick, so
                    // one starved environment suspended account creation for every group in the
                    // JVM — and under Phase 5 an open Cloudflare circuit ("may last a day or
                    // more") would have done it for a day.
                    recordDeferral(id, index);
                    log.debug("group {}: registration of {} deferred by the gateway budget ({}) — "
                                    + "re-queued at {}/{}, not before {}",
                            id, username, e.getMessage(), registered, target,
                            attempts.get(id) == null ? "now" : attempts.get(id).notBefore());
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.debug("group {}: registration interrupted at {}/{}", id, registered, target);
                    return;
                } catch (Exception e) {
                    // A real refusal. It charges the index an attempt and, if that spends the
                    // budget, stops the group — either way this pass is over: retrying inside the
                    // loop would be a hot loop against a gateway that just said no.
                    recordFailure(group, index, username, target, e);
                    return;
                }
            }

            recordCompletion(group, registered, named, target, names);

        } catch (Exception e) {
            // Per-group isolation, mirroring the recovery reconciler: one malformed group (a
            // missing environment, an unresolvable client) must not abort the worker for every
            // other group, and this loop runs again from scratch on the next tick.
            log.error("group {}: registration pass failed outside the account loop: {}",
                    id, e.getMessage(), e);
        } finally {
            BotMdc.clear();
        }
    }

    private static boolean isComplete(int registered, int named, int target, boolean names) {
        // namedCount is only a completion criterion when there is a name pool to draw from. With
        // no pool it stays at 0 rather than being advanced to match registeredCount, because
        // claiming N accounts were named when none were would be a worse lie than an honest zero.
        return registered >= target && (!names || named >= target);
    }

    /**
     * Clear the registration state — <b>only if the document's own {@code botCount} is met</b>
     * (QA F-1).
     * <p>
     * The condition is on the Mongo query, not on this pass's arithmetic, and that is the point:
     * the loop's re-read of the target closes the hours-wide window, and this closes the
     * microseconds-wide one left between the last read and this write. A raise that lands in
     * between simply does not match, so the group stays {@code PENDING} and the next tick resumes
     * it — the one outcome that must be impossible is clearing the state for a group that is not
     * actually complete, because nothing selects a group that is not {@code PENDING}.
     */
    private void recordCompletion(BotGroup group, int registered, int named, int target,
                                  boolean names) {
        // With a name pool an index is done only when both halves landed, so the met target is the
        // smaller counter. Without one, namedCount stays 0 by design (see isComplete).
        int done = names ? Math.min(registered, named) : registered;
        long matched = mongoTemplate.updateFirst(
                        Query.query(Criteria.where("_id").is(group.getId())
                                .and("botCount").lte(done)),
                        new Update().set("registrationState", null)
                                .set("registrationError", null)
                                .set("registeredCount", registered)
                                .set("namedCount", named)
                                .set("updatedAt", Instant.now()),
                        BotGroup.class)
                .getMatchedCount();

        if (matched == 0) {
            // Either botCount was raised in the last microsecond or the group was deleted. Both
            // are handled by doing nothing: the document decides, and it still says PENDING.
            log.debug("group {}: completion at {}/{} did not match the document (botCount raised, "
                            + "or the group was deleted) — leaving it PENDING",
                    group.getId(), done, target);
            return;
        }

        attempts.remove(group.getId());
        // Tier 1: one INFO line per group per registration, which is the whole of this feature's
        // INFO budget. Nothing per account reaches INFO by any path.
        log.info("group {} ({}): registration complete, {}/{} accounts{}",
                group.getId(), group.getName(), registered, target,
                names ? " (" + named + " named)" : " (no display-name pool configured)");
    }

    /**
     * Charge one attempt to {@code index} and, if the budget for it is spent, stop the group.
     *
     * <p><b>A transport failure is charged against its own, larger budget</b> (review S3). The
     * classification used to be binary — {@link GatewayBudgetException} deferred, everything else
     * a refusal — so a DNS hiccup, a connection reset or three 10-second timeouts on the same
     * index spent the whole {@code max-attempts-per-user=3} budget inside ~60 s and left a
     * 500-account group {@code FAILED}, needing a human, with a socket message in
     * {@code registrationError} as though the gateway had rejected the account. An
     * {@code IOException} anywhere in the cause chain (which covers {@code HttpTimeoutException}
     * and {@code HttpConnectTimeoutException}, and the {@code RuntimeException} the naming path
     * wraps them in) says neither "yes" nor "no", so it gets
     * {@code max-transport-attempts-per-user=10} — ~5 minutes of blip tolerance at the default
     * backoff — and then still stops the group rather than retrying forever, because a group that
     * cannot reach its gateway at all must not be invisible.
     */
    private void recordFailure(BotGroup group, int index, String username, int target,
                               Exception failure) {
        String id = group.getId();
        boolean transport = isTransportFailure(failure);
        Attempt attempt = attempts.compute(id, (key, current) -> {
            Attempt base = current != null && current.index == index
                    ? current : new Attempt(index, 0, 0, Instant.EPOCH);
            return new Attempt(index,
                    base.refusals() + (transport ? 0 : 1),
                    base.transportFailures() + (transport ? 1 : 0),
                    Instant.now().plus(failureBackoff));
        });

        botMetrics.incRegistrationAccount(OUTCOME_FAILED);

        int spent = transport ? attempt.transportFailures() : attempt.refusals();
        int budget = transport ? maxTransportAttemptsPerUser : maxAttemptsPerUser;
        String kind = transport ? "transport attempt" : "attempt";

        if (spent < budget) {
            log.warn("group {} ({}): registering {} failed ({} {}/{}) — retrying in {}s: {}",
                    id, group.getName(), username, kind, spent, budget,
                    failureBackoff.toSeconds(), failure.getMessage());
            return;
        }

        String reason = String.format("Registration stopped at account %d of %d (%s) after %d %ss: "
                        + "%s", index, target, username, spent, kind, failure.getMessage());
        mongoTemplate.updateFirst(byId(id),
                new Update().set("registrationState", RegistrationState.FAILED)
                        .set("registrationError", reason)
                        .set("updatedAt", Instant.now()),
                BotGroup.class);
        attempts.remove(id);

        // The group will not move again without a human, so this is a hand-off and it is an
        // ERROR. It does NOT skip ahead to index+1: skipping would break the high-water mark's
        // "indices 1..k are done" meaning, which is what every resume in this class depends on.
        //
        // The second half of the advice WORKS as of review B4: PATCHing botCount down to a met
        // target clears REGISTRATION_FAILED in BotGroupService.update, so the group is startable
        // straight afterwards. It used to send the operator in a circle — update() could only ever
        // SET pending and was gated off for a FAILED group, so the /start answered the same 400
        // repeating the same advice.
        log.error("group {} ({}): {}. Nothing will retry it — POST "
                        + "/api/v1/bot-group/{}/registration/retry to resume from account {}{}",
                id, group.getName(), reason, id, index,
                // "PATCH botCount down to 0" is not advice, and index 1 is where a bad prefix, a
                // bad password or a brand-side rejection stops a group — i.e. the commonest
                // failure of all. Only offer the second route when there is something to start
                // with.
                index > 1
                        ? String.format(", or PATCH botCount down to %d to use the accounts that "
                                + "exist.", index - 1)
                        : ". No account was created at all, so there is nothing to start with: "
                                + "check namePrefix, the password and the brand's username rules.");
    }

    /**
     * Pass this group over until {@link #deferralBackoff} has elapsed, <b>without charging it an
     * attempt</b> (review B2).
     * <p>
     * The counts are carried through unchanged: a deferral is not a failure, and a group that
     * waited out a busy window must not arrive at its next real attempt with a spent budget.
     */
    private void recordDeferral(String id, int index) {
        Instant notBefore = Instant.now().plus(deferralBackoff);
        attempts.compute(id, (key, current) -> current != null && current.index == index
                ? new Attempt(index, current.refusals(), current.transportFailures(), notBefore)
                : new Attempt(index, 0, 0, notBefore));
    }

    /**
     * Whether {@code failure} is a transport problem rather than a gateway answer.
     * <p>
     * The chain is walked because {@code ApiGatewayClient} wraps its own
     * {@code IOException}/{@code HttpTimeoutException} in a {@code RuntimeException} on the naming
     * path, so the top-level type says nothing.
     */
    private static boolean isTransportFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    private void persistProgress(String id, int registered, int named) {
        // A targeted $set rather than repository.save(group), deliberately: the worker holds a
        // document it read up to several minutes ago, and saving the whole thing would revert any
        // PATCH an operator made in the meantime — including the botCount raise that A2.7 makes a
        // product feature. The narrow race that remains is the other way round (a PATCH's
        // read-modify-write reverting a counter the worker advanced in the millisecond between),
        // and it is self-correcting: the re-registered index answers EXISTED and costs one
        // request.
        mongoTemplate.updateFirst(byId(id),
                new Update().set("registeredCount", registered)
                        .set("namedCount", named)
                        .set("updatedAt", Instant.now()),
                BotGroup.class);
    }

    private static Query byId(String id) {
        return Query.query(Criteria.where("_id").is(id));
    }

    private boolean isDue(String id, Instant now) {
        Attempt attempt = attempts.get(id);
        return attempt == null || !now.isBefore(attempt.notBefore());
    }

    /**
     * This group's registration target as the document says it is <em>right now</em>, or
     * {@code fallback} if it cannot be read (QA F-1).
     * <p>
     * An unreadable document is <b>not</b> treated as a deleted one: cancellation has its own
     * mechanism ({@code cancelled} plus {@code cancelScope}), and inferring "deleted" from a Mongo
     * hiccup would abandon a registration for a group that is perfectly alive.
     */
    private int currentTarget(String id, int fallback) {
        try {
            return repository.findById(id).map(BotGroup::getBotCount).orElse(fallback);
        } catch (RuntimeException e) {
            log.debug("group {}: could not re-read the registration target ({}) — keeping {}",
                    id, e.getMessage(), fallback);
            return fallback;
        }
    }

    /** Refresh the gauges mid-pass if they are older than a tick. See {@link #pendingGroups}. */
    private void refreshGaugesIfStale() {
        Instant at = gaugesRefreshedAt;
        if (at != null && Instant.now().isBefore(at.plusSeconds(tickSeconds))) {
            return;
        }
        try {
            pendingGroups = (int) repository.countByRegistrationState(RegistrationState.PENDING);
            failedGroups = (int) repository.countByRegistrationState(RegistrationState.FAILED);
            gaugesRefreshedAt = Instant.now();
        } catch (RuntimeException e) {
            log.debug("Could not refresh the registration gauges mid-pass: {}", e.getMessage());
        }
    }

    /**
     * Drop cancelled ids whose group is no longer {@code PENDING} (review S6).
     * <p>
     * Group ids are UUIDs, so an id added by a {@code DELETE} was never removed and the set grew
     * for the life of the JVM — tiny, but unbounded, and consulted from inside the budget's lock
     * through the scope predicate.
     * <p>
     * <b>Why this cannot un-cancel a live job.</b> Everything that reads the flag — the account
     * loop, and the scope predicate of a registration request parked inside the budget — runs on
     * this same single worker thread, which is the thread executing this method. No loop is in
     * progress while it runs. A group still listed as {@code PENDING} keeps its entry, so a
     * {@code DELETE} whose own delete threw is no more and no less blocked than before.
     */
    private void pruneCancelled(List<BotGroup> pending) {
        if (cancelled.isEmpty()) {
            return;
        }
        Set<String> stillPending = pending.stream()
                .map(BotGroup::getId)
                .collect(java.util.stream.Collectors.toSet());
        cancelled.retainAll(stillPending);
    }

    /**
     * This environment's registration wait, floored. See {@link #MIN_REGISTRATION_WAIT} for why
     * the floor is not paranoia about a value the settings already validate.
     */
    private Duration registrationWait(ApiGatewayClient client) {
        Duration configured = client.registrationMaxWait();
        return configured == null || configured.compareTo(MIN_REGISTRATION_WAIT) < 0
                ? MIN_REGISTRATION_WAIT : configured;
    }

    /**
     * The observe-mode pacing sleep between accounts (A2.3). Zero under {@code enforce}, where
     * the budget is doing the pacing, and zero for the fixture budget.
     */
    private static void sleep(Duration pacing) {
        if (pacing == null || pacing.isZero() || pacing.isNegative()) {
            return;
        }
        try {
            Thread.sleep(pacing.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Never null — see {@code DeadGroupRecoveryScheduler.resolveProduct} for why. */
    private String resolveProduct(String environmentId) {
        if (environmentId == null) {
            return TAG_UNRESOLVED;
        }
        try {
            Environment environment = environmentService.findById(environmentId);
            return environment != null && environment.getProductCode() != null
                    ? environment.getProductCode().getCode() : TAG_UNRESOLVED;
        } catch (Exception e) {
            log.debug("Cannot resolve environment {} for registration MDC: {}",
                    environmentId, e.getMessage());
            return TAG_UNRESOLVED;
        }
    }

    private static String tagValue(String value) {
        return value == null || value.isEmpty() ? TAG_UNRESOLVED : value;
    }

    /**
     * How many consecutive times one index has been refused, how many times it failed in
     * transport, and when the group may be looked at again.
     * <p>
     * In memory by design: a JVM restart resets the budget, which is the same decision
     * {@code DeadGroupRecoveryScheduler} makes (AD-11) and for the same reason — the state
     * describes an episode, not the group.
     * <p>
     * <b>Three fields, three different meanings, and they must not be merged.</b>
     * {@code refusals} is a gateway <em>answer</em> and is capped at
     * {@code max-attempts-per-user}; {@code transportFailures} is "we could not ask" and is capped
     * at {@code max-transport-attempts-per-user} (review S3); {@code notBefore} is also written by
     * a budget deferral, which charges <em>neither</em> count (review B2, A2.6).
     */
    private record Attempt(int index, int refusals, int transportFailures, Instant notBefore) {
    }
}
