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
 * <b>Phase 1 is observe-only.</b> Every method below counts, tags, logs and publishes;
 * none of them waits, refuses or opens a circuit. Enforcement (queues, per-tier ceilings,
 * cancellable waits) lands in Phase 3 behind {@code bot.gateway.budget.mode=enforce}, and
 * the circuit breaker in Phase 4. The shape is complete now so no call site has to be
 * revisited to get it.
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
     * {@link #execute} for a call that throws nothing checked — the WebSocket upgrade path
     * and anything else whose failure is already unchecked.
     */
    void run(RequestTier tier, GatewayRequestScope scope, Runnable call);

    /**
     * Run a <b>WebSocket upgrade</b> under the budget, honouring
     * {@code bot.gateway.budget.count-ws-upgrades}.
     * <p>
     * Separate from {@link #run} because the question "does the edge count a WS upgrade
     * against the same rule?" is open (Open Item 1): the WS hosts
     * ({@code …-sock.stgame.win}, {@code s009-ws-proxy-119.stgame.win}) may or may not sit
     * behind it. {@code true} is the conservative default and costs up to a third of the
     * window on a group start; when the answer arrives it is one flag, in one place, rather
     * than a condition duplicated at three {@code connect()} sites.
     */
    void runWsUpgrade(RequestTier tier, GatewayRequestScope scope, Runnable upgrade);

    /**
     * Try to admit one request, giving up after {@code maxWait}. {@link Duration#ZERO} means
     * "admit now or not at all".
     *
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
     * Declare demand up front (AD-7): {@code permits} requests of {@code tier} are about to
     * be made on behalf of {@code scope}, and until the reservation is released the tiers
     * <em>below</em> {@code tier} should treat the window as that much fuller.
     * <p>
     * Purely pre-emptive: it stops DEFAULT and PRIORITIZED filling the window in the minutes
     * before an ESSENTIAL flood arrives. Once the window is actually at the ceilings the
     * admission rules alone do the work. Always release it in a {@code finally} —
     * enforcement additionally retires a leaked reservation after {@code 2 × window}.
     * <p>
     * Nothing calls this in Phase 1; the group-start reservation lands with enforcement in
     * Phase 3. The accounting is live now so the {@code gateway_budget_reserved} gauge is
     * real rather than a hardcoded zero.
     */
    Reservation reserve(RequestTier tier, int permits, GatewayRequestScope scope);

    /**
     * Wake every queued request whose scope carries {@code botGroupId}, completing it with
     * {@code GatewayRequestCancelledException} and stamping nothing.
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
