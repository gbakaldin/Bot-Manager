package com.vingame.bot.infrastructure.client;

import com.vingame.websocketparser.auth.AuthClient;
import com.vingame.websocketparser.auth.AuthContext;
import com.vingame.websocketparser.auth.LoginRequest;
import com.vingame.websocketparser.auth.TokensProvider;
import lombok.extern.slf4j.Slf4j;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * A bot login that <b>cannot park its caller forever</b> (GATEWAY_REQUEST_BUDGET A19/A20.1).
 * <p>
 * <b>Why this exists.</b> Every one of the four in-repo gateway calls carries
 * {@code .timeout(Duration.ofSeconds(10))}. The login does not go through them — it goes
 * through the library's {@code AuthClient}, which builds its request with <b>no
 * {@code .timeout(...)}</b> on an {@code HttpClient.newHttpClient()} with <b>no connect
 * timeout</b> ({@code websocket-parser-core-3.0.5} sources, {@code AuthClient.java:25,127-137}).
 * A stalled TCP connection on a login therefore parks a bot-creation thread indefinitely, and
 * it parks it while holding one of {@code bot.creation.parallelism}'s semaphore permits and,
 * transitively, the group lock — which is {@code FOLLOWUPS.md} P13's black hole and,
 * since the DELETE path took the same lock, a Tomcat worker per operator retry as well.
 * <p>
 * <b>Why the fix is here and not in the library.</b> {@code AuthClient.authenticate()} builds
 * the {@code HttpRequest} internally, so there is no seam to hand a timeout to, and
 * websocket-parser is third-party with its own release cycle (and a history of publishing
 * jars from uncommitted trees). Waiting for a library release would mean shipping Phases 2-5
 * as one deployment — A7's plan — with the one unbounded wait in the fleet still unbounded,
 * underneath an ESSENTIAL tier whose max-wait is {@code 0} <em>by design</em>. Bound what
 * cannot make progress first; A20.1 is explicit that this is a prerequisite for enforcement
 * being correct, not an improvement alongside it.
 * <p>
 * <b>How.</b> The library call runs on its own virtual thread and the caller waits on a
 * future for at most {@link #LOGIN_TIMEOUT}. On expiry the caller calls
 * {@link HttpClientAbortableAuthClient#abort()}, which is {@code HttpClient.shutdownNow()} on
 * the very client the parked {@code send()} is using: that terminates the in-flight exchange,
 * so the worker thread fails and exits rather than leaking for the life of the JVM. This is
 * the one reason a subclass is needed at all — {@code AuthClient}'s {@code httpClient} is
 * reachable only through a {@code protected} Lombok getter.
 * <p>
 * <b>Cancellation is deliberately not wired in here.</b> The budget refuses admission to a
 * cancelled scope <em>before</em> this runs (AD-8); once the request is in flight it has
 * already cost the edge its slot, so abandoning it early would buy nothing and would leave a
 * half-read response on a connection nobody owns.
 * <p>
 * Not a Spring bean and deliberately stateless: one instance of the underlying
 * {@code AuthClient} (and therefore one JDK {@code HttpClient}) is still created per login,
 * exactly as before, because that is what the library's API forces. AD-12 removes it in
 * Phase 5 when the login moves in-repo — at which point {@link #LOGIN_TIMEOUT} becomes a
 * plain {@code .timeout(...)} on the request and this class goes away.
 */
@Slf4j
final class BoundedLogin {

    /**
     * The bound, matching the {@code .timeout(Duration.ofSeconds(10))} the other four gateway
     * calls already carry (A19: "the same 10 s timeout the other four calls have").
     * <p>
     * Not configurable, for the same reason those four are not: it is the house bound on a
     * single gateway round trip, and a login that needs more than ten seconds is a gateway
     * that is failing, not a gateway that is slow. A brand whose login legitimately exceeds
     * it would be a genuine finding, and it would present as {@code UpstreamLoginException:
     * … did not answer within 10s} rather than as a group that never finishes starting.
     */
    static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(10);

    private BoundedLogin() {
    }

    /**
     * Perform one login, giving up after {@link #LOGIN_TIMEOUT}.
     *
     * @throws HttpTimeoutException  if the gateway did not answer in time. An
     *                               {@link java.io.IOException}, so it lands in
     *                               {@code ApiGatewayClient.authenticate}'s existing
     *                               {@code IOException} arm and becomes an
     *                               {@code UpstreamLoginException} — which is the truth: the
     *                               request was sent and the gateway did not answer.
     * @throws InterruptedException  if the caller is interrupted while waiting.
     */
    static TokensProvider login(AuthContext ctx,
                                Function<AuthContext, ? extends LoginRequest> loginRequestFactory,
                                String username)
            throws HttpTimeoutException, InterruptedException {
        return login(ctx, loginRequestFactory, username, LOGIN_TIMEOUT);
    }

    /**
     * {@link #login(AuthContext, Function, String)} with an explicit bound — the test seam,
     * and the same shape as the injected clock the budget uses: a test must be able to prove
     * that the bound and the abort work without waiting ten real seconds, and production must
     * not be able to reach a different number by accident. Package-private on purpose.
     */
    static TokensProvider login(AuthContext ctx,
                                Function<AuthContext, ? extends LoginRequest> loginRequestFactory,
                                String username,
                                Duration bound)
            throws HttpTimeoutException, InterruptedException {

        HttpClientAbortableAuthClient client = new HttpClientAbortableAuthClient(ctx, loginRequestFactory);
        CompletableFuture<TokensProvider> result = new CompletableFuture<>();
        // A virtual thread per login, for the duration of one login. The caller is itself a
        // virtual thread on every production path (the bot-creation executor, the reconnect
        // loop), so this is one continuation, not one platform thread.
        Thread.ofVirtual().name("login-" + username).start(() -> {
            try {
                result.complete(client.authenticate());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });

        boolean settled = false;
        try {
            TokensProvider tokens = result.get(bound.toMillis(), TimeUnit.MILLISECONDS);
            settled = true;
            return tokens;
        } catch (TimeoutException e) {
            // Kill the exchange the worker is parked on, so it does not outlive us. Without
            // this the bound would only move the leak from the caller to a thread nobody can
            // see, still holding a socket against a gateway we have given up on.
            client.abort();
            throw new HttpTimeoutException("login for user '" + username + "' did not answer within "
                    + bound.toMillis() + "ms");
        } catch (ExecutionException e) {
            settled = true;
            throw rethrowUnchecked(e.getCause() == null ? e : e.getCause());
        } finally {
            // Release the client on the SUCCESS and LIBRARY-FAILURE paths too (review F6). Both
            // used to return without touching it, so every login left a JDK HttpClient — and its
            // SelectorManager PLATFORM thread — for GC to reclaim whenever it got round to the
            // unreachable client. That is the plan's own Findings item ("a 3k-bot start briefly
            // spawns ~3k platform threads") and the shape MEMORY records as the Bot-1 thread-leak
            // sawtooth. The subclass exists precisely to reach the client, so making the release
            // deterministic is free — and it is the difference between "this class bounds the wait"
            // and "this class owns the login's resources", which is what its name claims.
            //
            // Not on the timeout path: the abort already did it, and calling it twice on a client
            // whose exchange is still unwinding buys nothing.
            if (settled) {
                client.abort();
            }
        }
    }

    /**
     * Rethrow the library's failure with its type intact.
     * <p>
     * {@code AuthClient.authenticate} throws only unchecked exceptions
     * ({@code MessageParsingException}, {@code WebSocketParserException}), and
     * {@code ApiGatewayClient.authenticate}'s {@code RuntimeException} arm is built on that.
     * Wrapping here would have sent every login failure on every brand into the
     * {@code IllegalStateException} bucket instead — the kind of change that is invisible in
     * a diff and total in production.
     */
    private static RuntimeException rethrowUnchecked(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(
                "Unexpected checked exception from the library login path", cause);
    }

    /**
     * {@code AuthClient} plus the one thing it does not expose: a way to abandon an exchange
     * that is not coming back.
     * <p>
     * {@code getHttpClient()} is a {@code protected} Lombok getter on the library class, so a
     * subclass is the only access. {@code HttpClient.shutdownNow()} (JDK 21) closes the
     * connection immediately, which makes the parked {@code send()} throw — the only
     * mechanism available, since the request has no timeout to set after the fact.
     */
    private static final class HttpClientAbortableAuthClient extends AuthClient {

        private HttpClientAbortableAuthClient(AuthContext ctx,
                                              Function<AuthContext, ? extends LoginRequest> factory) {
            super(ctx, factory);
        }

        /**
         * Release the underlying JDK {@code HttpClient}: terminate any in-flight exchange and let
         * its {@code SelectorManager} platform thread exit.
         * <p>
         * Called on <b>every</b> path — the timeout (where it is what unblocks the parked worker),
         * the success and the library failure (where it is what stops the selector thread being
         * GC-dependent). Named {@code abort} for the first of those; it is a release on the others.
         * <p>
         * {@code shutdownNow()} unblocks a {@code send()} parked on a connection; it does
         * <b>not</b> unblock a worker parked in address resolution, so "the worker fails and
         * exits" is exact for a connected exchange and best-effort otherwise. Harmless — the worker
         * is a virtual thread — but the javadoc above used to state it flatly.
         */
        void abort() {
            try {
                getHttpClient().shutdownNow();
            } catch (RuntimeException e) {
                // Never let the abort mask the timeout the caller is about to report.
                log.debug("Could not shut down the login HttpClient after a timeout: {}", e.toString());
            }
        }
    }
}
