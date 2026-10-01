package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Callable;

/**
 * A {@link GatewayBudget} that records what was submitted to it and, optionally, <b>refuses to
 * run the call at all</b>.
 * <p>
 * The second half is the load-bearing half. These tests exist to pin the tier and scope every
 * call site declares, and the call itself is an HTTP request to a gwms gateway. In
 * {@link Mode#BLOCK} the recorded call is never invoked and a sentinel is thrown instead, so a
 * test of {@code ApiGatewayClient} can assert its intent without a socket existing anywhere —
 * which is mandatory here, not tidiness: a test that fired real traffic could get a whole
 * brand blocked at the Cloudflare edge for over an hour (GATEWAY_REQUEST_BUDGET AD-21).
 */
public class RecordingGatewayBudget implements GatewayBudget {

    /** What the fake does once it has recorded a submission. */
    public enum Mode {
        /** Record and run — for callers whose call is itself a mock. */
        RUN,
        /** Record and throw {@link Sentinel} without running — for callers that would do I/O. */
        BLOCK,
        /**
         * Record and throw the configured {@link com.vingame.bot.common.exception.GatewayBudgetException}
         * without running — what an exhausted window or an open circuit does under
         * {@code enforce}. This is how a call-site test proves a budget outcome reaches (or is
         * deliberately kept from reaching) its caller, with no queue, no clock and no socket.
         */
        REFUSE
    }

    /** Thrown instead of performing the recorded call in {@link Mode#BLOCK}. */
    public static class Sentinel extends RuntimeException {
        public Sentinel() {
            super("gateway call intercepted by RecordingGatewayBudget — no request was made");
        }
    }

    /** One submission: what tier it claimed, on whose behalf, and the wait it asked for. */
    public record Submission(RequestTier tier, GatewayRequestScope scope, boolean wsUpgrade,
                             Duration maxWait) {}

    private final Mode mode;
    /** What {@link Mode#REFUSE} throws. Null in the other two modes. */
    private final com.vingame.bot.common.exception.GatewayBudgetException refusal;
    /**
     * Thread-safe on purpose: a real budget is called from every bot thread at once (a group start
     * is {@code bot.creation.parallelism} concurrent authentications). A plain {@code ArrayList}
     * here loses or corrupts entries under that fan-out, and it does so intermittently — the kind
     * of test that passes alone and fails in the full suite. (The second fan-out this named,
     * {@code registerUsers}, was deleted in Phase 4; registration is now one serial worker —
     * review T3.)
     */
    private final List<Submission> submissions = new CopyOnWriteArrayList<>();
    private final List<String> counted = new CopyOnWriteArrayList<>();
    private final List<String> countedWsUpgrades = new CopyOnWriteArrayList<>();
    private final List<String> cancelledScopes = new CopyOnWriteArrayList<>();

    public RecordingGatewayBudget() {
        this(Mode.RUN);
    }

    public RecordingGatewayBudget(Mode mode) {
        this.mode = mode;
        this.refusal = null;
    }

    /** A budget that records and then refuses with {@code refusal} — {@link Mode#REFUSE}. */
    public static RecordingGatewayBudget refusing(
            com.vingame.bot.common.exception.GatewayBudgetException refusal) {
        return new RecordingGatewayBudget(refusal);
    }

    private RecordingGatewayBudget(com.vingame.bot.common.exception.GatewayBudgetException refusal) {
        this.mode = Mode.REFUSE;
        this.refusal = refusal;
    }

    public List<Submission> submissions() {
        return List.copyOf(submissions);
    }

    /** Tiers in submission order — the assertion most call-site tests actually want. */
    public List<RequestTier> tiers() {
        return submissions.stream().map(Submission::tier).toList();
    }

    public List<String> counted() {
        return List.copyOf(counted);
    }

    public List<String> cancelledScopes() {
        return List.copyOf(cancelledScopes);
    }

    public Submission only() {
        if (submissions.size() != 1) {
            throw new AssertionError("expected exactly one gateway submission, got " + submissions);
        }
        return submissions.get(0);
    }

    public void clear() {
        submissions.clear();
        counted.clear();
        countedWsUpgrades.clear();
        cancelledScopes.clear();
    }

    private <T> T record(RequestTier tier, GatewayRequestScope scope, boolean wsUpgrade, Callable<T> call)
            throws Exception {
        return record(tier, scope, wsUpgrade, call, null);
    }

    private <T> T record(RequestTier tier, GatewayRequestScope scope, boolean wsUpgrade,
                         Callable<T> call, Duration maxWait) throws Exception {
        submissions.add(new Submission(tier, scope, wsUpgrade, maxWait));
        if (mode == Mode.BLOCK) {
            throw new Sentinel();
        }
        if (mode == Mode.REFUSE) {
            throw refusal;
        }
        return call == null ? null : call.call();
    }

    @Override
    public <T> T execute(RequestTier tier, GatewayRequestScope scope, Callable<T> call) throws Exception {
        return record(tier, scope, false, call);
    }

    @Override
    public <T> T execute(RequestTier tier, GatewayRequestScope scope, Callable<T> call,
                         Duration maxWait) throws Exception {
        return record(tier, scope, false, call, maxWait);
    }

    @Override
    public void run(RequestTier tier, GatewayRequestScope scope, Runnable call) {
        try {
            record(tier, scope, false, () -> {
                call.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void runWsUpgrade(RequestTier tier, GatewayRequestScope scope, Runnable upgrade) {
        try {
            record(tier, scope, true, () -> {
                upgrade.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public <T> Optional<T> tryExecute(RequestTier tier, GatewayRequestScope scope,
                                      Callable<T> call, Duration maxWait) throws Exception {
        return Optional.ofNullable(record(tier, scope, false, call));
    }

    @Override
    public void count(String reason) {
        counted.add(reason);
    }

    @Override
    public void countWsUpgrade(String reason) {
        countedWsUpgrades.add(reason);
        counted.add(reason);
    }

    /** Every {@link #reportEdgeBlock} call, as {@code endpoint|cfRay}. */
    private final List<String> edgeBlocks = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Records the block and returns normally — i.e. behaves like an observe-mode budget. */
    @Override
    public void reportEdgeBlock(GatewayEndpoint endpoint, String cfRay) {
        edgeBlocks.add(endpoint.tag() + "|" + cfRay);
    }

    public List<String> edgeBlocks() {
        return List.copyOf(edgeBlocks);
    }

    /** Only the probe stamps that went through the WS-aware twin (A5.3). */
    public List<String> countedWsUpgrades() {
        return List.copyOf(countedWsUpgrades);
    }

    @Override
    public Reservation reserve(RequestTier tier, int permits, GatewayRequestScope scope) {
        return new Reservation() {
            @Override
            public void release() {
                // no accounting here — reservations are covered against the real budget
            }

            @Override
            public int remaining() {
                return permits;
            }
        };
    }

    @Override
    public void cancelScope(String botGroupId) {
        cancelledScopes.add(botGroupId);
    }

    @Override
    public Snapshot snapshot() {
        return new Snapshot("env-fake", "Fake", "000", GatewayBudgetMode.OBSERVE,
                submissions.size(), 900, 0, 0, 0, false);
    }

    @Override
    public boolean countsWsUpgrades() {
        return true;
    }

    @Override
    public Duration registrationMaxWait() {
        return Duration.ofMinutes(15);
    }

    /**
     * Zero, i.e. "this budget paces you" — a recording fixture must never make its caller sleep
     * 600 ms per account, which would turn a five-account test into a three-second one and a
     * fifty-account test into a build no one runs.
     */
    @Override
    public Duration observeModePacing() {
        return Duration.ZERO;
    }

    @Override
    public Duration maxWait(RequestTier tier) {
        return tier == RequestTier.ESSENTIAL ? null : Duration.ofMinutes(10);
    }
}
