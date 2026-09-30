package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * One environment's outbound gateway request budget: the single funnel every request to a
 * gwms gateway passes through (GATEWAY_REQUEST_BUDGET AD-1).
 * <p>
 * <b>Why this exists.</b> Cloudflare rate-limits each gwms gateway at 1,000 requests per
 * 5 minutes per source IP. A breach is not a slow-down: the edge answers an HTML block page
 * with {@code 403} to <em>every</em> request from this host for that gateway — so the whole
 * brand cannot log in, re-authenticate, read a balance or deposit — and the one observed
 * block had not aged out after a full hour. Meanwhile every reconnect loop in the fleet
 * keeps burning requests into a wall that cannot answer. The app previously had no rate
 * limiter at all: two concurrency semaphores at parallelism 10 breach the 5-minute rule in
 * about ten seconds of sustained fan-out.
 * <p>
 * <b>One budget per environment, and one JVM per IP.</b> The rule is per gateway host, so
 * two environments on different hosts must not throttle each other. There is deliberately
 * no cross-process coordination — if two instances ever share an egress IP that is a
 * deployment error, not something this interface will paper over.
 * <p>
 * <b>Two modes, one shape.</b> In {@code bot.gateway.budget.mode=observe} every method below
 * counts, tags, logs and publishes and none of them waits or refuses — that is what made
 * Phase 1 deployable to ten prod environments unchanged. In {@code enforce} (Phase 3) the same
 * methods queue by tier, admit by ceiling, time out per tier and refuse a cancelled scope. No
 * call site differs between the two; the mode is read inside the budget.
 * <p>
 * <b>An unbounded wait is admissible only where progress is guaranteed</b> (A16.2). The sliding
 * window guarantees it — stamps expire, so a 3,000-bot start is ~10 windows of monotonic
 * progress — which is why {@code essential.max-wait=0} means "unbounded, cancellable" and is
 * legal for {@link RequestTier#ESSENTIAL} alone. A Cloudflare edge block guarantees nothing
 * (the user's answer to Open Item 7 is "possibly ~24 hours, possibly until someone clears it
 * manually"), so an <b>open circuit refuses every tier</b> rather than parking any of them.
 * Parking on a block is parking a bot thread for a day, and a start that parks forever inside
 * the budget is exactly how {@code FOLLOWUPS.md} P13's attempt stays open for the life of the
 * JVM. The circuit itself lands in Phase 5; the refusal path is here from Phase 3 so the two
 * cannot be designed apart.
 * <p>
 * <b>Where the wait will happen matters.</b> {@link #execute} parks the <em>caller's</em>
 * thread. Group-start callers and reconnect loops are virtual threads, where parking is the
 * intended cost. The ws-parser message-processor threads that run
 * {@code onEndGame → onNewSession} are the library's, which is why drift balance reads will
 * use {@link #tryExecute} with {@link Duration#ZERO} rather than ever blocking there
 * (AD-10).
 */
public interface GatewayBudget {

    /**
     * A pass-through budget for fixtures, ad-hoc tooling and any {@code ApiGatewayClient}
     * built without an environment: it runs every call immediately and counts nothing.
     * <p>
     * Production code never resolves this — {@code EnvironmentClientRegistry} always hands
     * {@code ApiGatewayClient.init} a real per-environment budget from
     * {@link GatewayBudgetRegistry}, and {@code EnvironmentClientRegistryBudgetWiringTest}
     * pins that. It exists so that a test fixture cannot be the thing that discovers a
     * missing budget with a {@code NullPointerException} on a bot thread.
     */
    GatewayBudget UNLIMITED = new UnlimitedGatewayBudget();

    /**
     * Admit one request of {@code tier} and run it, blocking the caller until it is
     * admitted.
     * <p>
     * The window is stamped <b>at admission, not at completion</b>: a request that was
     * admitted and then failed still cost the edge a request, and one that timed out or was
     * cancelled while queued cost it nothing. The {@code requests_total} counter and the
     * window gauge have to agree on that or the dashboard lies.
     *
     * @throws Exception whatever {@code call} throws, unwrapped — the funnel must not
     *                   change the exception a caller already handles. From Phase 3 it may
     *                   additionally throw {@code GatewayBudgetExhaustedException},
     *                   {@code GatewayCircuitOpenException} or
     *                   {@code GatewayRequestCancelledException}.
     */
    <T> T execute(RequestTier tier, GatewayRequestScope scope, Callable<T> call) throws Exception;

    /**
     * {@link #execute} with an explicit wait, overriding the tier's configured
     * {@code max-wait}.
     * <p>
     * One caller today: user registration, which runs at {@link RequestTier#DEFAULT} but waits
     * {@code bot.gateway.budget.registration.max-wait} (AD-19) rather than DEFAULT's 30 s, so
     * that an admitted registration <b>finishes</b> rather than half-finishes when a group
     * start floods the window mid-way. A half-registered group is the thing that gets
     * forgotten; a slow one is not.
     * <p>
     * It is a wait override and nothing else: the tier still decides the ceiling, the priority
     * and the queue. A longer wait cannot promote a request past a tier above it.
     * <p>
     * <b>{@code maxWait} has exactly one meaning per value, whatever the tier</b> (review F5):
     * {@link Duration#ZERO} is <em>now or never</em>, a positive value is a bounded wait, and
     * {@code null} means "use the tier's configured policy" (which for
     * {@link RequestTier#ESSENTIAL} is unbounded-but-cancellable). A caller's word is final —
     * {@code ZERO} previously parked <em>unboundedly</em> on any tier configured unbounded, which
     * is the opposite of what it says and was a trap sitting exactly where AD-10's "never park a
     * message-processor thread" rule lives.
     */
    <T> T execute(RequestTier tier, GatewayRequestScope scope, Callable<T> call, Duration maxWait)
            throws Exception;

    /**
     * {@link #execute} for a call that throws nothing checked.
     * <p>
     * <b>No production caller today</b> (review F13), and that is worth knowing rather than
     * looking for: it is {@link #runWsUpgrade}'s non-WS twin, kept because A5.1 required
     * enforcement to reach both and because the next unchecked-only gateway call should not have
     * to re-derive it. Its enforcement path is the same one {@code runWsUpgrade} uses and is
     * covered by {@code SlidingWindowGatewayBudgetAdmissionTest.everyEntryPointIsPaced}.
     * <p>
     * A caller interrupted while queued gets {@code GatewayRequestCancelledException} with the
     * interrupt flag restored, because a {@link Runnable} cannot carry an
     * {@code InterruptedException} and swallowing it silently would let an interrupted upgrade
     * proceed — which is the {@code connect()}-eats-interrupts defect AD-8 exists to avoid.
     */
    void run(RequestTier tier, GatewayRequestScope scope, Runnable call);

    /**
     * Run a <b>WebSocket upgrade</b> under the budget, honouring
     * {@code bot.gateway.budget.count-ws-upgrades}.
     * <p>
     * Separate from {@link #run} because the question "does the edge count a WS upgrade
     * against the same rule?" was open (Open Item 1). It is now <b>closed: the WS hosts sit
     * behind the same Cloudflare rule as the API hosts</b> (A15, user-confirmed), so
     * {@code count-ws-upgrades=true} is fact rather than the conservative guess it shipped as.
     * The flag survives as a kill switch, in one place, rather than as a condition duplicated
     * at three {@code connect()} sites.
     * <p>
     * <b>When the flag is off, this still checks cancellation and the circuit; it neither waits
     * on nor consumes the window</b> (A5.2). "Not counted by the edge" implies "not paced by
     * us"; it does not imply "un-cancellable" — a stopped group's queued upgrade must still
     * die, or {@code /stop}'s promptness depends on a flag about Cloudflare's accounting — and
     * it does not imply "sent into an open circuit", because the edge is refusing us regardless
     * of what it counts.
     * <p>
     * That this path enforces at all is the load-bearing half of the feature rather than a
     * completeness item (A15.3): every watchdog reconnect in the fleet is a counted upgrade,
     * and the observed staging hot loop was ~180 of them per 5-minute window from two sick
     * groups — 18% of the entire Cloudflare allowance, which makes a reconnect storm a
     * plausible <em>cause</em> of a block and not only a symptom of a sick fleet.
     */
    void runWsUpgrade(RequestTier tier, GatewayRequestScope scope, Runnable upgrade);

    /**
     * Try to admit one request, giving up after {@code maxWait}. {@link Duration#ZERO} means
     * "admit now or not at all".
     *
     * @param maxWait {@link Duration#ZERO} for now-or-never (whatever the tier), a positive value
     *                for a bounded wait, {@code null} for the tier's configured policy — see
     *                {@link #execute(RequestTier, GatewayRequestScope, Callable, Duration)}
     * @return the call's result, or {@link Optional#empty()} if it was never admitted — in
     *         which case <b>nothing was stamped</b>, because nothing was sent.
     */
    <T> Optional<T> tryExecute(RequestTier tier, GatewayRequestScope scope, Callable<T> call, Duration maxWait)
            throws Exception;

    /**
     * Stamp the window for a request this JVM is making <b>without</b> asking for admission:
     * the anonymous environment WebSocket probe and the circuit breaker's own clearance
     * probe. They must never wait (a probe that queues behind a group start cannot answer
     * "is the edge serving again?"), but they do cost the edge a request, so they count.
     *
     * @param reason a short, bounded label for the DEBUG line — {@code "ws-probe"},
     *               {@code "circuit-probe"}. Never a URL.
     */
    void count(String reason);

    /**
     * {@link #count} for a probe that is a <b>WebSocket upgrade</b> rather than an HTTP
     * request: it stamps only when {@code bot.gateway.budget.count-ws-upgrades} says an upgrade
     * costs the edge a request (A5.3, reviewer F2).
     * <p>
     * Without this twin, answering Open Item 1 "no" would have silenced the three
     * {@code connect()} sites and left the anonymous environment probe stamping anyway — one
     * flag governing a decision in two halves. Phase 5's {@code count("circuit-probe")} is an
     * HTTP GET and keeps the unconditional form.
     */
    void countWsUpgrade(String reason);

    /**
     * Declare demand up front (AD-7): {@code permits} requests of {@code tier} are about to
     * be made on behalf of {@code scope}, and until the reservation is released the tiers
     * <em>below</em> {@code tier} should treat the window as that much fuller.
     * <p>
     * Purely pre-emptive: it stops DEFAULT and PRIORITIZED filling the window in the minutes
     * before an ESSENTIAL flood arrives. Once the window is actually at the ceilings the
     * admission rules alone do the work. Always release it in a {@code finally} —
     * enforcement additionally retires a leaked reservation after {@code 2 × window}.
     * <p>
     * <b>Consumption is by scope and tier</b>: an admission at {@code tier} whose scope carries
     * the same {@code botGroupId} draws one permit down, so a group's own flood does not also
     * pay the pre-emptive price it declared. One caller —
     * {@code BotGroupBehaviorService.createBotsInParallel}, at {@code botCount × 3} (login +
     * WS upgrade + first balance read, A15.1) — and it is taken <b>after the runtime is
     * published</b>, deliberately: {@code cancelStartInFlight} resolves the environment from
     * the runtime, so a reservation taken before it would be unreleasable by a {@code /stop}
     * landing in that window (A20.4).
     */
    Reservation reserve(RequestTier tier, int permits, GatewayRequestScope scope);

    /**
     * Wake every queued request whose scope carries {@code botGroupId}, completing it with
     * {@code GatewayRequestCancelledException} and stamping nothing.
     * <p>
     * <b>Wake, not merely mark</b> (A20.3). Before Phase 3 a cancelled build returned within one
     * in-flight HTTP call per semaphore permit, so {@code /stop} was prompt whatever the budget
     * did. With waiter queues a bot parked <em>inside</em> the budget cannot return until this
     * method completes its future — so this is a <b>stop-latency</b> requirement on two paths,
     * not a pacing nicety: both {@code /stop} and {@code DELETE} (through
     * {@code stopAndLogout}, which takes the group lock since Phase 2) wait on the build
     * unwinding.
     * <p>
     * The ordering at the call site is load-bearing (AD-8, AD-16): {@code stop()} must
     * cancel the attempt and the scope <b>before</b> it takes the group lock, or a
     * {@code /stop} waits out the paced start it is trying to stop.
     */
    void cancelScope(String botGroupId);

    /** Everything an operator-facing line or a gauge needs, taken as one consistent read. */
    Snapshot snapshot();

    /** Whether WebSocket upgrades are stamped into this budget's window (Open Item 1). */
    boolean countsWsUpgrades();

    /**
     * The wait a registration request may use instead of {@link RequestTier#DEFAULT}'s (AD-19).
     * <p>
     * Exposed on the interface rather than read from the settings at the call site because
     * {@code ApiGatewayClient} holds a {@link GatewayBudget}, not a
     * {@code GatewayBudgetSettings}, and a fixture's {@link #UNLIMITED} has no settings at all.
     */
    Duration registrationMaxWait();

    /**
     * How long a <b>self-paced</b> caller must sleep between consecutive requests because this
     * budget is not pacing them — {@link Duration#ZERO} when it is (A2.3, A28.1).
     * <p>
     * There is exactly one such caller and there must not be a second: {@code RegistrationWorker}
     * is a single serial loop at ~100-300 ms per call, which is ~200-600 requests in a 5-minute
     * window <b>on its own</b>. In {@code enforce} the budget holds it under the DEFAULT ceiling
     * like everything else; in {@code observe} nothing does, so the one component that would
     * otherwise be unpaced paces itself at {@code window / default.ceiling} — 300 s / 500 =
     * <b>600 ms</b>. That is not a second rate limiter and not a new knob: it is the same
     * configured ceiling, applied by the component the mode leaves uncovered. The phase order
     * (enforcement shipped in Phase 3) makes it a belt rather than the mechanism.
     * <p>
     * <b>Why this is on the interface at all.</b> The two numbers it is derived from — the window
     * and the DEFAULT ceiling — are reachable through neither {@link #snapshot()} nor any other
     * method here, and {@code settings()} exists only on {@link SlidingWindowGatewayBudget}. A
     * downcast would be a {@code ClassCastException} on every fixture, which all carry
     * {@link #UNLIMITED} (A28.1). Deriving it here also keeps the mode test in one place: the
     * caller sleeps whatever it is told and has no opinion about {@code observe} versus
     * {@code enforce}.
     * <p>
     * Deliberately <b>not</b> a default method. A default returning zero would mean a budget that
     * forgot to implement it silently unpaces the one unpaced caller in the system, which is the
     * exact failure this exists to prevent; an abstract method makes every implementation state
     * its answer.
     */
    Duration observeModePacing();

    /**
     * The configured maximum wait for {@code tier}, or {@code null} when that tier is configured
     * to wait <b>unbounded</b> (which only {@link RequestTier#ESSENTIAL} may be).
     * <p>
     * Exposed so a caller that has its own, stricter reason to bound a wait can take the
     * <em>smaller</em> of the two rather than replacing the policy. {@code Bot.sessionBudgetWait()}
     * is the one such caller: a wait taken on a ws-parser message-processor thread must be shorter
     * than the watchdog's patience, and a bound is only ever allowed to make a wait shorter.
     * <p>
     * {@code null} rather than {@link Duration#ZERO} for unbounded, deliberately: from a caller
     * {@code ZERO} means <em>now or never</em>, and that ambiguity is review F5. For the same
     * reason {@code null} is also the answer of a budget that has <b>no policy at all</b>
     * ({@link #UNLIMITED}, review S4) — {@code ZERO} from this method must never be a third
     * meaning, so a caller may always pass what it reads here straight back into
     * {@code execute(…, maxWait)}.
     */
    Duration maxWait(RequestTier tier);

    /**
     * A declared-demand reservation. {@link #release()} is idempotent and must be called
     * from a {@code finally}.
     */
    interface Reservation extends AutoCloseable {

        /** Return the unconsumed remainder to the budget. Idempotent. */
        void release();

        /** How many of the declared permits have not been consumed or released yet. */
        int remaining();

        @Override
        default void close() {
            release();
        }
    }

    /**
     * A consistent point-in-time read of one environment's budget.
     * <p>
     * The three queue depths are named individually rather than carried in a map because
     * every consumer (the rollup line, the gauges, the tests) wants them in tier order and
     * a map's iteration order is not that.
     */
    record Snapshot(
            String environmentId,
            String environmentName,
            String productCode,
            GatewayBudgetMode mode,
            int windowRequests,
            int hardCap,
            int queuedEssential,
            int queuedPrioritized,
            int queuedDefault,
            boolean circuitOpen) {

        /**
         * The tier-2 rollup fragment (AD-20):
         * {@code gateway=137/900 queued=0/0/0 circuit=closed}.
         * <p>
         * Rendered here rather than in {@code FleetRollupLogger} so the one string an
         * operator greps has one definition.
         */
        public String describeForRollup() {
            return "gateway=" + windowRequests + "/" + hardCap
                    + " queued=" + queuedEssential + "/" + queuedPrioritized + "/" + queuedDefault
                    + " circuit=" + (circuitOpen ? "open" : "closed");
        }
    }
}
