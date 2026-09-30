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
 *       cancelled scope — costs nothing.</b> The group is simply left {@code PENDING} and picked
 *       up again. Those say "not now"; they do not say "this account cannot be created", and a
 *       group that went {@code FAILED} because the fleet was busy would need a human for no
 *       reason.</li>
 *   <li><b>A gateway refusal costs one attempt</b> against
 *       {@code bot.registration.max-attempts-per-user}. Spending it leaves the group
 *       {@code FAILED} with the reason on the document, one ERROR, and no silent retry —
 *       {@code POST /{id}/registration/retry} is the way back, and it resumes from
 *       {@code registeredCount + 1} rather than starting over.</li>
 * </ul>
 * The worker never skips an index. Skipping would make {@code registeredCount} stop meaning
 * "1..k are done", which is the invariant the whole design rests on.
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
    private final int displayNameRetries;
    private final Duration failureBackoff;

    /**
     * Groups called off while registering — a {@code DELETE} landing mid-job. Read by the scope
     * predicate, so it must stay a plain set read: the budget asks it <b>while holding its own
     * lock</b>, and a predicate that blocks there stalls every gateway request of that
     * environment (see {@code GatewayRequestScope.cancelled}).
     */
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    /** Per-group attempt state for the index currently being worked. In memory, by design. */
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** Cached for the gauges, refreshed each tick, so a scrape never issues a Mongo query. */
    private volatile int pendingGroups;
    private volatile int failedGroups;

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
                              @Value("${bot.registration.failure-backoff-seconds:30}") long failureBackoffSeconds) {
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
     * Announce, and immediately resume, any registration a previous JVM left unfinished (A2.2).
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
     * state either way, so this is promptness, not routing, and an enqueue that is lost costs at
     * most {@code tick-seconds}. Running on the worker's own single thread is what keeps the
     * "one group at a time" property true for a create that arrives mid-registration.
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
        failedGroups = repository.findByRegistrationState(RegistrationState.FAILED).size();

        Instant now = Instant.now();
        BotGroup next = pending.stream()
                .filter(group -> !cancelled.contains(group.getId()))
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

            while (!isComplete(registered, named, target, names)) {
                if (cancelled.contains(id)) {
                    log.info("group {} ({}): registration cancelled at {}/{} accounts",
                            id, group.getName(), registered, target);
                    return;
                }

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
                    log.debug("group {}: registration of {} deferred by the gateway budget ({}) — "
                                    + "re-queued at {}/{}",
                            id, username, e.getMessage(), registered, target);
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

    private void recordCompletion(BotGroup group, int registered, int named, int target,
                                  boolean names) {
        mongoTemplate.updateFirst(byId(group.getId()),
                new Update().set("registrationState", null)
                        .set("registrationError", null)
                        .set("registeredCount", registered)
                        .set("namedCount", named)
                        .set("updatedAt", Instant.now()),
                BotGroup.class);
        attempts.remove(group.getId());
        // Tier 1: one INFO line per group per registration, which is the whole of this feature's
        // INFO budget. Nothing per account reaches INFO by any path.
        log.info("group {} ({}): registration complete, {}/{} accounts{}",
                group.getId(), group.getName(), registered, target,
                names ? " (" + named + " named)" : " (no display-name pool configured)");
    }

    /** Charge one attempt to {@code index} and, if the budget for it is spent, stop the group. */
    private void recordFailure(BotGroup group, int index, String username, int target,
                               Exception failure) {
        String id = group.getId();
        Attempt attempt = attempts.compute(id, (key, current) ->
                current != null && current.index == index
                        ? new Attempt(index, current.count + 1, Instant.now().plus(failureBackoff))
                        : new Attempt(index, 1, Instant.now().plus(failureBackoff)));

        botMetrics.incRegistrationAccount(OUTCOME_FAILED);

        if (attempt.count < maxAttemptsPerUser) {
            log.warn("group {} ({}): registering {} failed (attempt {}/{}) — retrying in {}s: {}",
                    id, group.getName(), username, attempt.count, maxAttemptsPerUser,
                    failureBackoff.toSeconds(), failure.getMessage());
            return;
        }

        String reason = String.format("Registration stopped at account %d of %d (%s) after %d "
                        + "attempts: %s", index, target, username, attempt.count,
                failure.getMessage());
        mongoTemplate.updateFirst(byId(id),
                new Update().set("registrationState", RegistrationState.FAILED)
                        .set("registrationError", reason)
                        .set("updatedAt", Instant.now()),
                BotGroup.class);
        attempts.remove(id);

        // The group will not move again without a human, so this is a hand-off and it is an
        // ERROR. It does NOT skip ahead to index+1: skipping would break the high-water mark's
        // "indices 1..k are done" meaning, which is what every resume in this class depends on.
        log.error("group {} ({}): {}. Nothing will retry it — POST "
                        + "/api/v1/bot-group/{}/registration/retry to resume from account {}, or "
                        + "PATCH botCount down to {} to use the accounts that exist.",
                id, group.getName(), reason, id, index, index - 1);
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
        return attempt == null || !now.isBefore(attempt.notBefore);
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
     * How many consecutive times one index has been refused by the gateway, and when it may be
     * tried again. In memory by design: a JVM restart resets the budget, which is the same
     * decision {@code DeadGroupRecoveryScheduler} makes (AD-11) and for the same reason — the
     * state describes an episode, not the group.
     */
    private record Attempt(int index, int count, Instant notBefore) {
    }
}
