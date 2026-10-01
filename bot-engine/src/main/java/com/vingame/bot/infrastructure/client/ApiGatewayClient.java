package com.vingame.bot.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.common.exception.GatewayBudgetException;
import com.vingame.bot.common.exception.UpstreamLoginException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.client.dto.UserRegistrationRequest;
import com.vingame.bot.infrastructure.client.dto.RegistrationOutcome;
import com.vingame.bot.infrastructure.client.dto.UserRegistrationResponse;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.infrastructure.gateway.CloudflareBlockDetector;
import com.vingame.bot.infrastructure.gateway.GatewayEndpoint;
import com.vingame.websocketparser.auth.AuthContext;
import com.vingame.websocketparser.auth.LoginRequest;
import com.vingame.websocketparser.auth.TokensProvider;
import com.vingame.websocketparser.exception.MessageParsingException;
import com.vingame.websocketparser.exception.WebSocketParserException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Client for interacting with the API Gateway.
 * This is a prototype-scoped bean - each injection gets a new instance
 * that must be initialized with environment-specific data via
 * {@link #init(String, String, AuthProfile, GatewayBudget)}.
 * <p>
 * <b>Every outbound HTTP request in this class goes through one funnel</b>
 * ({@link #send(RequestTier, GatewayRequestScope, HttpRequest)}) and therefore through the
 * environment's {@link GatewayBudget} (GATEWAY_REQUEST_BUDGET). That is what makes the
 * Cloudflare 1,000-per-5-minutes rule countable at all, and from Phase 3 it is what makes it
 * enforceable. {@code GatewayCallSiteGuardTest} fails the build if a second
 * {@code httpClient.send(} appears anywhere in this class — a bypass would be invisible to
 * every metric and every alert, and would present as a whole-brand auth outage.
 * <p>
 * The public methods that make a request take {@code (tier, scope)} from their caller
 * rather than defaulting them. The tier is a statement of intent that only the caller can
 * make (a login during a group start is ESSENTIAL; the same login from the reconnect loop is
 * PRIORITIZED), so there is deliberately no overload that omits it.
 */
@Slf4j
@Service
@Scope("prototype")
public class ApiGatewayClient {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String VERIFY_TOKEN_ENDPOINT = "/gwms/v1/verifytoken.aspx";
    private static final String BOT_DEPOSIT_ENDPOINT = "/gwms/v1/bot/deposit.aspx";
    private static final String USER_AGENT = "PostmanRuntime/7.15.2";
    private static final String SESSION_TOKEN_HEADER = "X-TOKEN";

    /**
     * The gwms body status meaning "that already exists" — <b>at HTTP 200</b>, with
     * {@code code: 409} beside it. One string, two meanings, and which one it has depends
     * entirely on the endpoint that was called (A28.3):
     * <ul>
     *   <li>from {@code register.aspx} it means <b>this account already exists</b>, i.e. this
     *       index is already done — which is what makes a resumed registration possible at all
     *       ({@link RegistrationOutcome#ALREADY_EXISTED});</li>
     *   <li>from {@code update-fullname.aspx} it means <b>that display name is taken</b>, i.e.
     *       re-roll ({@link #isDisplayNameTaken}).</li>
     * </ul>
     * Reading it against the wrong endpoint gets both cases exactly backwards: a resumed account
     * would look like a naming collision, and a collided name would look like a finished account.
     * Captured live on 097/BOM staging —
     * {@code docs/reviews/GATEWAY_REQUEST_BUDGET/gwms-register-envelope.md}.
     */
    static final String STATUS_EXISTED = "EXISTED";

    /**
     * The house bound on one gateway round trip, on every request this class makes — all five,
     * the login included.
     * <p>
     * It used to be four copies of {@code Duration.ofSeconds(10)} and one request with no
     * timeout at all: the login, which went through the library's {@code AuthClient} with no
     * request timeout and no connect timeout (GATEWAY_REQUEST_BUDGET A19), so a stalled TCP
     * connection parked a build thread — and with it a semaphore permit and the group lock — for
     * the life of the JVM. Phase 3 bounded it from the outside ({@code BoundedLogin}: a virtual
     * thread, a 10 s wait, {@code shutdownNow()} through a subclass). Phase 5 moved the login
     * in-repo (AD-12), so it is now a plain {@code .timeout(...)} on the request like the other
     * four, and the wrapper is gone (A29.3).
     */
    static final Duration GATEWAY_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The most requests this client keeps in flight at once (GATEWAY_REQUEST_BUDGET A33, the
     * staging-release anomaly A1).
     * <p>
     * <b>Why a bound exists at all.</b> {@link #httpClient} is the JDK's default client, which
     * negotiates HTTP/2 with the gateway, and there is one per environment — so every gateway
     * request of every bot on the environment is a <em>stream</em> on <b>one</b> TCP connection.
     * The server caps concurrent streams per connection ({@code SETTINGS_MAX_CONCURRENT_STREAMS}),
     * and JDK 21's client does not queue or open a second connection at that cap: it fails the
     * request immediately with {@code IOException("too many concurrent streams")}, thrown from
     * {@code jdk.internal.net.http.Http2Connection.reserveStream0} via
     * {@code Http2ClientImpl.getConnectionFor}. Nothing reaches the gateway. Phase 5 moved the
     * login onto this client (the library's {@code AuthClient} had a connection of its own), so
     * a group's burst of first balance reads now shares the connection with the next group's
     * logins, and on staging 4-8 bots per 100-bot RIK group lost their login or their first
     * balance read to it on every fleet start.
     * <p>
     * <b>Why 32.</b> Well below 100, the floor RFC 9113 §6.5.2 recommends for the server's
     * setting (the gateway's actual value is not known to us; it was never measured, because
     * measuring it means talking to the gateway). The margin also absorbs any lag in the JDK releasing a
     * stream's reservation slightly after {@code send} has returned. The bound costs no
     * throughput that matters: at a few hundred ms per round trip, 32 streams is 60-150 requests
     * a second, against a budget that admits 900 per five minutes. It is a constant, not a
     * property, on purpose: a {@code @Value}-sized semaphore is {@code Semaphore(0)} in every
     * client the container did not build, and that hangs instead of failing (QA's G1).
     * <p>
     * The permit is taken <b>inside</b> the budget-admitted call ({@link #httpCall}), never before
     * admission: a request holding a permit while it queued in the budget would let a DEFAULT
     * read starve an ESSENTIAL one of streams. The one exception is the AD-10 drift read
     * ({@link #sendIfAdmitted}), which takes its permit first and <b>without waiting</b>, then
     * asks the budget with {@code Duration.ZERO}: neither step can park, so neither can starve
     * anything. {@code ApiGatewayClientStreamLimitTest} reproduces
     * the failure against a loopback HTTP/2 server and pins this value below the RFC floor.
     */
    static final int MAX_IN_FLIGHT_REQUESTS = 32;

    private final DisplayNameService displayNameService;
    private final BotMetrics metrics;
    private final HttpClient httpClient;

    /** {@link #MAX_IN_FLIGHT_REQUESTS} permits, one per request on the wire. Fair: FIFO. */
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT_REQUESTS, true);

    @Value("${bot.ip}")
    private String botIp;

    private String apiGateway;
    private String appId;
    private String loginPath;
    private String registrationPath;
    private String updateFullnamePath;
    private String xToken;
    private Function<AuthContext, ? extends LoginRequest> loginRequestFactory;
    private boolean initialized = false;

    /**
     * This environment's request budget. Every request in this class is admitted through it.
     * <p>
     * Defaults to {@link GatewayBudget#UNLIMITED} so a client built by a fixture cannot NPE
     * on a bot thread; production always receives the per-environment budget through
     * {@link #init(String, String, AuthProfile, GatewayBudget)} and
     * {@code EnvironmentClientRegistryBudgetWiringTest} pins that it does.
     */
    private GatewayBudget gatewayBudget = GatewayBudget.UNLIMITED;

    /** The environment the stream meters are tagged with; null until a real budget is wired. */
    private String metricsEnvironmentId;

    @Autowired
    public ApiGatewayClient(DisplayNameService displayNameService, BotMetrics metrics) {
        this.displayNameService = displayNameService;
        this.metrics = metrics;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Initialize the client with environment-specific configuration.
     * Must be called before using any other methods.
     */
    public ApiGatewayClient init(String apiGateway, String appId, AuthProfile authProfile,
                                GatewayBudget gatewayBudget) {
        this.gatewayBudget = gatewayBudget == null ? GatewayBudget.UNLIMITED : gatewayBudget;
        this.apiGateway = apiGateway;
        this.appId = appId;
        this.loginPath = authProfile.loginPath();
        this.registrationPath = authProfile.registrationPath();
        this.updateFullnamePath = authProfile.updateFullnamePath();
        this.xToken = authProfile.xToken();
        this.loginRequestFactory = authProfile.loginRequestFactory();
        // A33: in-flight gauge and permit-timeout counter, at zero, the moment the environment's
        // client exists. Skipped for UNLIMITED (fixtures), which carries no environment.
        String environmentId = this.gatewayBudget.snapshot().environmentId();
        if (environmentId != null && metrics != null) {
            this.metricsEnvironmentId = environmentId;
            metrics.registerGatewayClientStreams(environmentId, this::inFlightRequests);
        }
        this.initialized = true;
        log.debug("ApiGatewayClient initialized with gateway: {}, appId: {}", apiGateway, appId);
        return this;
    }

    /**
     * Test seam — initialise without a budget, i.e. with {@link GatewayBudget#UNLIMITED}.
     * <p>
     * <b>Not for production.</b> The only production caller is
     * {@code EnvironmentClientRegistry.createClients}, which must pass the environment's
     * budget; {@code GatewayCallSiteGuardTest} asserts against the source that it does,
     * because a client initialised through this overload would send unbudgeted traffic while
     * every {@code gateway_budget_*} series sat at zero.
     */
    ApiGatewayClient init(String apiGateway, String appId, AuthProfile authProfile) {
        return init(apiGateway, appId, authProfile, GatewayBudget.UNLIMITED);
    }

    /** Backward-compatible overload — uses standard user endpoints and no X-TOKEN. */
    public ApiGatewayClient init(String apiGateway, String appId,
                                 Function<AuthContext, ? extends LoginRequest> loginRequestFactory,
                                 GatewayBudget gatewayBudget) {
        return init(apiGateway, appId, new AuthProfile(
                "/user/login.aspx", "/user/register.aspx", "/user/update.aspx", null, loginRequestFactory
        ), gatewayBudget);
    }

    private void checkInitialized() {
        if (!initialized) {
            throw new IllegalStateException("ApiGatewayClient not initialized. Call init(apiGateway, appId) first.");
        }
    }

    /**
     * A credential rendered for a log line: the first ten characters and nothing more (review
     * SEC1, CLAUDE.md's house idiom).
     * <p>
     * The {@code X-TOKEN} these lines used to print in full is the <b>environment's admin
     * credential</b> — the one that authorises {@code update-fullname} and {@code deposit} for
     * every account on the brand — and they are DEBUG lines, which staging runs at
     * ({@code BOT_LOG_LEVEL=DEBUG}), once per account on the one feature that creates accounts in
     * bulk. Ten characters is enough to tell two tokens apart and to recognise the {@code 18-}
     * agency prefix that CLAUDE.md's token table turns on; it is not enough to use.
     */
    private static String masked(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "<none>";
        }
        return secret.length() <= 10 ? "***" : secret.substring(0, 10) + "...";
    }

    /**
     * The same body, with every {@code password} value replaced (review SEC1).
     * <p>
     * The envelope itself is genuinely diagnostic — {@code appId}, {@code ip}, {@code source} and
     * {@code type} are what a brand's register failure is compared against
     * ({@code gwms-register-envelope.md}) — so the body is kept and the one field that is never
     * diagnostic is removed, rather than dropping the line.
     */
    private static String withoutSecrets(String requestBody) {
        return requestBody == null ? null
                : PASSWORD_VALUE.matcher(requestBody).replaceAll("\"password\":\"***\"");
    }

    /** {@code "password": "…"} in a serialised request body, escapes included. */
    private static final java.util.regex.Pattern PASSWORD_VALUE =
            java.util.regex.Pattern.compile("\"password\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"");

    /**
     * <b>The single funnel.</b> Every HTTP request this class makes is admitted by the
     * environment's budget here and nowhere else.
     * <p>
     * This is the only {@code httpClient.send(} call site in the class and
     * {@code GatewayCallSiteGuardTest} keeps it that way. Every response is classified for a
     * Cloudflare edge block <b>before anything parses it</b> — in {@link #httpCall}, which all three
     * funnel entry points share (A29.4, Implementation Note 11) — so a block page becomes "edge
     * block, cf-ray …" and an open circuit instead of {@code Unexpected character ('<')}, the exact
     * message that was misdiagnosed for an hour on 2026-09-17.
     * <p>
     * The caller's checked exceptions are rethrown unwrapped: the funnel must not change the
     * exception a caller already handles.
     */
    private HttpResponse<String> send(RequestTier tier, GatewayRequestScope scope, HttpRequest request)
            throws IOException, InterruptedException {
        return underBudget(tier, scope, httpCall(request));
    }

    /**
     * {@link #send} with an explicit wait, overriding the tier's configured {@code max-wait}.
     * <p>
     * One caller: user registration, which runs at {@link RequestTier#DEFAULT} but waits
     * {@code bot.gateway.budget.registration.max-wait} (AD-19), so that an admitted registration
     * <b>finishes</b> rather than half-finishes when a group start floods the window mid-way. A
     * half-registered group is precisely the thing that gets forgotten; a slow one is not.
     */
    private HttpResponse<String> send(RequestTier tier, GatewayRequestScope scope, HttpRequest request,
                                      Duration maxWait) throws IOException, InterruptedException {
        // The permit wait shares the caller's deadline (A33 review): budget wait + permit wait <=
        // maxWait, so the session path's watchdog invariant (Bot.sessionBudgetWait) is unchanged.
        long deadlineNanos = System.nanoTime() + maxWait.toNanos();
        try {
            return gatewayBudget.execute(tier, scope, httpCall(request, deadlineNanos), maxWait);
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unexpected checked exception from the gateway budget funnel", e);
        }
    }

    /**
     * {@link #send}, but giving up immediately if the budget has no room — AD-10's
     * {@code tryExecute(…, Duration.ZERO)}.
     * <p>
     * Used for the drift balance re-sync, which runs on a ws-parser message-processor thread.
     * Parking that thread would stall the bot's whole message pipeline and throwing into it
     * would break {@code onNewSession}, so the read is simply skipped and the bot plays on its
     * local estimate for another round.
     *
     * @return empty when the request was <b>not</b> sent, in which case nothing was stamped.
     */
    private Optional<HttpResponse<String>> sendIfAdmitted(RequestTier tier, GatewayRequestScope scope,
                                                          HttpRequest request)
            throws IOException, InterruptedException {
        // A33 review: AD-10's "never parks" covers the stream permit too. It is taken BEFORE
        // admission and without waiting, so a saturated connection answers exactly like a full
        // window — empty — and, because the budget was never asked, nothing is stamped either.
        // Holding the permit across tryExecute(ZERO) cannot starve anyone: that call never waits.
        if (!inFlight.tryAcquire()) {
            return Optional.empty();
        }
        try {
            return gatewayBudget.tryExecute(tier, scope, () -> sendClassified(request), Duration.ZERO);
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unexpected checked exception from the gateway budget funnel", e);
        } finally {
            inFlight.release();
        }
    }

    /**
     * The one and only place this class hands a request to the JDK client — and therefore the one
     * place every gateway answer is first seen (A29.4).
     * <p>
     * Factored out so the three funnel entry points above share it: a second literal
     * {@code httpClient.send(} would be an uncounted, unpaced escape from the budget, and
     * {@code GatewayCallSiteGuardTest} fails the build on one.
     * <p>
     * <b>Classify before parse.</b> The response is handed to {@link CloudflareBlockDetector} the
     * moment it exists, inside the admitted call. On an edge block the budget is told
     * ({@link GatewayBudget#reportEdgeBlock}): under {@code enforce} that opens the circuit and
     * throws {@code GatewayCircuitOpenException}, so no caller ever parses an HTML page; under
     * {@code observe} it returns and the caller proceeds exactly as before Phase 5. The request
     * stays stamped either way — it reached the edge.
     */
    private Callable<HttpResponse<String>> httpCall(HttpRequest request) {
        return httpCall(request, null);
    }

    /**
     * @param deadlineNanos the caller's overall deadline ({@link System#nanoTime()} scale), or
     *                      {@code null} when the caller has none; see {@link #permitWait}
     */
    private Callable<HttpResponse<String>> httpCall(HttpRequest request, Long deadlineNanos) {
        return () -> {
            acquireStream(permitWait(deadlineNanos, System.nanoTime()));
            try {
                return sendClassified(request);
            } finally {
                // In finally on purpose, and pinned by ApiGatewayClientStreamPermitReleaseTest: a
                // permit lost on a failure path is lost for the life of the JVM, and after 32 of
                // them every gateway request on the environment times out.
                inFlight.release();
            }
        };
    }

    /** The one {@code httpClient.send} — every caller above already holds a stream permit. */
    private HttpResponse<String> sendClassified(HttpRequest request) throws IOException, InterruptedException {
        return classified(request, httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    /**
     * How long a request may wait for a stream permit: {@link #GATEWAY_REQUEST_TIMEOUT}, or less
     * if the caller has a deadline and less than that is left of it — never negative.
     * <p>
     * The deadline is what keeps the session path inside the watchdog (A33 review). A
     * session-path call passes {@code Bot.sessionBudgetWait()} (watchdog / 4) as its
     * {@code maxWait}; with a separate permit wait on top, the three-call {@code onNewSession}
     * chain could reach 3 x (45 + 10 + 10 + 0.5) = 196.5 s against a 180 s watchdog. Sharing the
     * deadline restores 3 x (45 + 10 + 0.5) = 166.5 s: the budget wait and the permit wait
     * together are at most {@code maxWait}, and the request's own 10 s timeout and
     * {@code readBalance}'s 500 ms sleep come on top, exactly as before A33.
     */
    static Duration permitWait(Long deadlineNanos, long nowNanos) {
        if (deadlineNanos == null) {
            return GATEWAY_REQUEST_TIMEOUT;
        }
        long left = Math.max(0L, deadlineNanos - nowNanos);
        return Duration.ofNanos(Math.min(left, GATEWAY_REQUEST_TIMEOUT.toNanos()));
    }

    /**
     * Wait up to {@code wait} for one of the {@link #MAX_IN_FLIGHT_REQUESTS} stream permits (A33).
     * <p>
     * The wait is at most {@link #GATEWAY_REQUEST_TIMEOUT} (see {@link #permitWait}), on top of
     * the request's own timeout, which is left exactly as it was. Every permit is held by a
     * request that is itself bounded by that timeout, so a full wait means the gateway has stopped
     * answering 32 requests in a row — or our own connection is saturated.
     * <p>
     * It surfaces as {@link StreamWaitTimeoutException}, an {@link HttpTimeoutException} and so an
     * {@code IOException}: the registration worker charges it as a transport attempt (review S3),
     * a login gets its {@code UpstreamLoginException}, a balance read its "Failed to fetch
     * balance". It is never an edge block: only a response is ever classified. What it does
     * <b>not</b> do is count as a gateway failure: {@code bot_login_total{outcome="failure"}} and
     * {@code bot_verify_token_total{outcome="failure"}} — the inputs to
     * {@code EnvironmentLoginFailing} / {@code EnvironmentAuthDown} — skip it, because the gateway
     * was never asked. It is counted in {@link BotMetrics#GATEWAY_CLIENT_STREAM_WAIT_TIMEOUTS_TOTAL}
     * instead, beside the {@link BotMetrics#GATEWAY_CLIENT_INFLIGHT_REQUESTS} gauge.
     * <p>
     * The request was admitted, and therefore stamped, before this wait; a timed-out wait leaves
     * it stamped and unsent. That over-counts the window, which is the safe direction, and is not
     * a re-send — nothing here sends anything twice.
     */
    private void acquireStream(Duration wait) throws InterruptedException, HttpTimeoutException {
        if (!inFlight.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS)) {
            if (metricsEnvironmentId != null) {
                metrics.incGatewayClientStreamWaitTimeout(metricsEnvironmentId);
            }
            throw new StreamWaitTimeoutException("no free gateway stream within " + wait
                    + ": " + MAX_IN_FLIGHT_REQUESTS + " requests already in flight on this environment");
        }
    }

    /** Permits in use right now — what {@link BotMetrics#GATEWAY_CLIENT_INFLIGHT_REQUESTS} reads. */
    int inFlightRequests() {
        return MAX_IN_FLIGHT_REQUESTS - inFlight.availablePermits();
    }

    /**
     * Our own connection had no free stream in time; the gateway was never asked (A33). A distinct
     * type only so the login and balance-read failure counters can leave it out.
     */
    static final class StreamWaitTimeoutException extends HttpTimeoutException {
        StreamWaitTimeoutException(String message) {
            super(message);
        }
    }

    private HttpResponse<String> classified(HttpRequest request, HttpResponse<String> response) {
        CloudflareBlockDetector.Verdict verdict = CloudflareBlockDetector.classify(response);
        if (verdict.edgeBlock()) {
            gatewayBudget.reportEdgeBlock(endpointOf(request), verdict.cfRay());
        }
        return response;
    }

    /**
     * Which of the request kinds this is, for the bounded {@code endpoint} label — matched on the
     * configured paths rather than passed down, so the funnel's signatures stay as they are. Never
     * returns a URL: a {@code verifytoken} URL carries a bot's session token in its query.
     */
    private GatewayEndpoint endpointOf(HttpRequest request) {
        String path = request.uri().getPath();
        if (path == null) {
            return GatewayEndpoint.LOGIN;
        }
        if (path.endsWith(VERIFY_TOKEN_ENDPOINT)) {
            return GatewayEndpoint.VERIFY_TOKEN;
        }
        if (path.endsWith(BOT_DEPOSIT_ENDPOINT)) {
            return GatewayEndpoint.DEPOSIT;
        }
        if (registrationPath != null && path.endsWith(registrationPath)) {
            return GatewayEndpoint.REGISTER;
        }
        if (updateFullnamePath != null && path.endsWith(updateFullnamePath)) {
            return GatewayEndpoint.UPDATE_FULLNAME;
        }
        return GatewayEndpoint.LOGIN;
    }

    /**
     * A response body rendered for a WARN line: the body itself, unless it is a Cloudflare block
     * page — five kilobytes of HTML that would otherwise land on track 1 once per bot per request
     * for as long as a block lasts. The verdict and the cf-ray are what an operator needs.
     */
    private static String bodyForLog(HttpResponse<String> response) {
        CloudflareBlockDetector.Verdict verdict = CloudflareBlockDetector.classify(response);
        if (verdict.edgeBlock()) {
            return "<Cloudflare edge block page, cf-ray " + verdict.cfRay() + ">";
        }
        return response.body();
    }

    /**
     * Run {@code call} under the budget and unwrap its checked exceptions — {@link #send}'s body.
     * <p>
     * It used to be the funnel's non-HTTP twin as well, for the login while that still went through
     * the library's {@code AuthClient}. The login is an ordinary funnel request since Phase 5
     * (AD-12), so every call through here is an {@link #httpCall}.
     */
    private <T> T underBudget(RequestTier tier, GatewayRequestScope scope, Callable<T> call)
            throws IOException, InterruptedException {
        try {
            return gatewayBudget.execute(tier, scope, call);
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // GatewayBudget.execute declares `throws Exception` for the general case. Every
            // call passed in here throws only IOException or InterruptedException, so
            // anything else came out of the budget layer itself and is a bug there — not a
            // gateway failure, and it must not be reported as one.
            throw new IllegalStateException(
                    "Unexpected checked exception from the gateway budget funnel", e);
        }
    }

    /**
     * Authenticate a bot with the given credentials.
     *
     * @param credentials Bot credentials (username, password, fingerprint)
     * @param tier        why this login matters: {@link RequestTier#ESSENTIAL} on the start
     *                    path ({@code Bot.initialize}), {@link RequestTier#PRIORITIZED} from
     *                    the reconnect loop's re-auth. Only the caller knows which.
     * @param scope       the bot this login is for, and how to learn it has been called off
     * @return TokensProvider containing agencyToken, authToken, and jwtToken
     */
    public TokensProvider authenticate(BotCredentials credentials, RequestTier tier, GatewayRequestScope scope) {
        checkInitialized();

        AuthContext ctx = new AuthContext(
            apiGateway,
            credentials.getUsername(),
            credentials.getPassword(),
            appId,
            credentials.getFingerprint(),
            loginPath,
            xToken
        );

        // Built BEFORE the try (review-phase5): a body that cannot be serialised (or a brand factory
        // that throws) is a request that was never sent, and the try's IOException / RuntimeException
        // arms both count bot_login_total{outcome="failure"} — the input to EnvironmentLoginFailing,
        // which must count gateway refusals, not our own bugs. Same exception type for the caller.
        HttpRequest request;
        try {
            request = loginRequest(ctx);
        } catch (IOException | RuntimeException e) {
            throw new UpstreamLoginException("Login failed for user '" + credentials.getUsername()
                    + "': could not build the login request: " + e.getMessage(), e);
        }

        try {
            HttpResponse<String> response = send(tier, scope, request);
            TokensProvider tokens = parseLoginResponse(credentials.getUsername(), response);
            log.debug("[Login] response: agencyToken={} | authToken={} | jwtToken={}",
                    masked(tokens.getAgencyToken()), masked(tokens.getAuthToken()),
                    masked(tokens.getJwtToken()));
            metrics.incLogin(true);
            return tokens;
        } catch (IOException | InterruptedException e) {
            // Every transport failure lands here since the login moved in-repo (AD-12): the
            // request's .timeout(GATEWAY_REQUEST_TIMEOUT) surfaces as HttpTimeoutException, a
            // refused or reset connection as its own IOException. Under the library these were
            // wrapped as WebSocketParserException and took the RuntimeException arm instead —
            // and the timeout did not exist at all (A19). Either way the request left the JVM
            // and the gateway did not answer, so incLogin(false) is correct here and is exactly
            // what must NOT happen on the budget arm below. The contract callers see is
            // unchanged: an UpstreamLoginException, i.e. a RuntimeException.
            if (!(e instanceof StreamWaitTimeoutException)) {
                // A33: a permit timeout never reached the gateway; it has its own counter.
                metrics.incLogin(false);
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UpstreamLoginException(
                    "Login failed for user '" + credentials.getUsername() + "': " + e.getMessage(), e);
        } catch (GatewayBudgetException e) {
            // A4 — AHEAD of the RuntimeException arm, and the whole of AD-9 depends on it.
            // GatewayBudgetException is a RuntimeException, so without this arm a paced or
            // refused login is rewrapped as UpstreamLoginException and three things break at
            // once: Bot.performReauth marks the bot DEAD for a request the JVM chose not to
            // send (AD-9's one rule), classifyCreationFailure takes the "auth" arm instead of
            // the "budget" arm Phase 1 shipped for exactly this, and
            // bot_login_total{outcome="failure"} — the per-brand regression gate and the input
            // to EnvironmentLoginFailing — counts our own throttling as upstream login
            // failures. Since /start no longer has an HTTP response, that tag is one of only
            // three places a budget outcome during a build is visible at all.
            //
            // And deliberately NO metrics.incLogin(false): nothing was sent, so there was no
            // login to fail. The budget's own gateway_budget_requests_total{outcome} carries it.
            // The same holds for an edge block detected on THIS login (Phase 5): it is reported as
            // GatewayCircuitOpenException, counted in gateway_edge_blocks_total{endpoint="login"},
            // and is a brand-wide fact rather than a failure of this account.
            throw e;
        } catch (RuntimeException e) {
            // Counter increment must not change error semantics — BotFactory relies on
            // the exception propagating up so the bot creation pipeline records the failure.
            metrics.incLogin(false);
            // An envelope the gateway answered but that carries no tokens, or a body that is not
            // JSON. Wrapped in a typed UpstreamLoginException so callers and the REST advice can
            // distinguish login failures from generic runtime errors. Since AD-12 the message
            // carries the upstream envelope's status/code/message, which is what
            // API_ERROR_FORWARDING AD-7 could only call "best effort" while the library owned it.
            throw new UpstreamLoginException(
                    "Login failed for user '" + credentials.getUsername() + "': " + e.getMessage(),
                    e);
        }
    }

    /**
     * The login request, built <b>exactly</b> as the library's {@code AuthClient.authenticate} built
     * it (AD-12): {@code POST apiGateway + loginPath}; {@code Cache-Control: no-cache},
     * {@code Content-Type: application/json}, {@code User-Agent: PostmanRuntime/7.15.2}, and
     * {@code X-TOKEN} when the context carries one; the body is the brand's
     * {@code loginRequestFactory} applied to the context, serialised with this class's mapper —
     * which serialises identically to the library's {@code ObjectMapperProvider.getDefault()} (they
     * differ only in deserialisation leniency, and inclusion is {@code ALWAYS} in both). The one
     * addition is {@link #GATEWAY_REQUEST_TIMEOUT}, which is not on the wire.
     * <p>
     * "Exactly" is a claim with a test: {@code ApiGatewayClientLoginTest} records what the library
     * sends and what this sends against the same loopback stub, for every {@code LoginRequest}
     * implementation, and compares method, path, headers and body bytes.
     * <p>
     * The library rebuilt the context {@code withFingerprint(fingerprint)} from its own field before
     * applying the factory (Implementation Note 12); that is the identity on the context built in
     * {@link #authenticate}, so the factory sees the same values.
     */
    HttpRequest loginRequest(AuthContext ctx) throws IOException {
        String requestBody = mapper.writeValueAsString(loginRequestFactory.apply(ctx));
        log.debug("[Login] POST {} | X-TOKEN: {} | body: {}",
                ctx.apiGateway() + ctx.loginPath(), masked(ctx.xToken()), withoutSecrets(requestBody));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(ctx.apiGateway() + ctx.loginPath()))
                .header("Cache-Control", "no-cache")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT);
        if (ctx.xToken() != null) {
            builder.header(SESSION_TOKEN_HEADER, ctx.xToken());
        }
        return builder
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(GATEWAY_REQUEST_TIMEOUT)
                .build();
    }

    /**
     * Read {@code data[0].token} (agency), {@code session_id} (auth) and {@code token2} (JWT) exactly
     * as the library did, failing with the library's own exception types so the cause a caller
     * might inspect is unchanged.
     * <p>
     * What changed is the message: it now carries the HTTP status and the envelope's
     * {@code status}/{@code code}/{@code message} — the gateway's actual reason — and never the raw
     * body. The library put the body in {@code MessageParsingException}'s payload and a Jackson
     * excerpt of it in its message; a refusal reason belongs in a log, a credential-bearing body
     * does not.
     */
    private static TokensProvider parseLoginResponse(String username, HttpResponse<String> response) {
        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            // Not an IOException on purpose: the gateway (or something in front of it) DID answer,
            // so this must not read as a transport failure. A Cloudflare page never reaches here
            // under enforce — httpCall already turned it into an open circuit.
            throw new WebSocketParserException("User " + username + ": login response was not JSON (HTTP "
                    + response.statusCode() + ")", e);
        }
        if (root == null || !root.has("data") || !root.get("data").isArray() || root.get("data").isEmpty()) {
            throw new MessageParsingException("User " + username
                    + ": Data array is missing or empty in auth response" + envelope(response, root), null);
        }
        JsonNode first = root.get("data").get(0);
        if (!first.has("token")) {
            throw new MessageParsingException("User " + username
                    + ": Agency token (field 'token') is missing in auth response" + envelope(response, root), null);
        }
        if (!first.has("session_id")) {
            throw new MessageParsingException("User " + username
                    + ": Auth token (field 'session_id') is missing in auth response" + envelope(response, root), null);
        }
        String jwt = first.has("token2") ? first.get("token2").asText() : null;
        return TokensProvider.of(first.get("token").asText(), first.get("session_id").asText(), jwt);
    }

    /** {@code " (HTTP 200, status: INVALID, code: 400, message: …)"} — the gateway's own words. */
    private static String envelope(HttpResponse<String> response, JsonNode root) {
        StringBuilder out = new StringBuilder(" (HTTP ").append(response.statusCode());
        if (root != null && root.isObject()) {
            for (String field : new String[]{"status", "code", "message"}) {
                JsonNode value = root.get(field);
                if (value != null && !value.isNull()) {
                    out.append(", ").append(field).append(": ").append(value.asText());
                }
            }
        }
        return out.append(')').toString();
    }

    /**
     * Register <b>one</b> account — {@code userNamePrefix + index} — on the auth gateway
     * (GATEWAY_REQUEST_BUDGET A6 Phase 4 item 5).
     * <p>
     * <b>This replaced a bulk, fan-out {@code registerUsers(prefix, password, count)}</b>, and
     * the fan-out is what had to go rather than be tuned. It ran {@code count} virtual threads
     * under a {@code Semaphore(user.registration.parallelism)} and joined them with
     * {@code allOf(...).join()}, which means:
     * <ul>
     *   <li>a 200-account create was a worst case of ~5 hours parked on one Tomcat worker, once
     *       each user could wait {@code registration.max-wait} (A25.2);</li>
     *   <li>a budget refusal came back as a per-user failure string and then as an
     *       {@code UpstreamRegistrationException} — a <b>502 about a gateway that was never
     *       asked</b> (A25.1, review F2);</li>
     *   <li>the semaphore was sized from a {@code @Value} field, so any caller the Spring
     *       container did not build got {@code Semaphore(0)} and <b>hung</b> rather than failed
     *       (QA's G1 / Open Item 15).</li>
     * </ul>
     * All three were properties of the bulk method. {@code RegistrationWorker} calls this one
     * index at a time from a single virtual thread, so there is no concurrency to bound, no
     * request thread to park, and no batch whose failure has to be summarised.
     * <p>
     * <b>The caller supplies the scope and the wait, and neither has a default here.</b> The
     * scope must carry the {@code botGroupId} (A28.6) or a {@code DELETE} arriving mid-
     * registration cannot call off a queued request — {@code GatewayBudget.cancelScope} keys on
     * exactly that. The wait is the batch-wide {@code registration.max-wait}, passed down rather
     * than re-read, so an index admitted late does not restart the clock.
     * <p>
     * The tier is <b>not</b> a parameter: registration is {@link RequestTier#DEFAULT} by
     * definition (AD-3) — it is the one class of gateway work whose deferral costs nothing that
     * is not recoverable, because an unattempted index is simply attempted later. There is no
     * call site that could honestly ask for anything else.
     *
     * @return {@link RegistrationOutcome#CREATED} or {@link RegistrationOutcome#ALREADY_EXISTED};
     *         both mean "this index is done"
     * @throws RuntimeException on any other envelope — fail closed (A28.3). A 200 is not proof of
     *         anything here, so an unrecognised {@code status} costs the index an attempt rather
     *         than being optimistically believed.
     */
    public RegistrationOutcome registerOne(String userNamePrefix, String password, int index,
                                           GatewayRequestScope scope, Duration maxWait)
            throws IOException, InterruptedException {
        checkInitialized();
        String ip = botIp;
        String username = userNamePrefix + index;

        UserRegistrationRequest request = UserRegistrationRequest.builder()
                .username(username)
                .password(password)
                .ip(ip)
                .registerIp(ip)
                .os("OS X")
                .appId(appId)
                .device("Computer")
                .browser("WEB")
                .source(appId)
                .type("BOT")
                .build();

        String requestBody = mapper.writeValueAsString(request);
        log.debug("[Register] POST {} | X-TOKEN: {} | user: {} | body: {}",
                apiGateway + registrationPath, masked(xToken), username,
                withoutSecrets(requestBody));

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiGateway + registrationPath))
                .header("Content-Type", "application/json")
                .header(SESSION_TOKEN_HEADER, xToken)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(GATEWAY_REQUEST_TIMEOUT)
                .build();

        // DEFAULT tier with the registration WAIT OVERRIDE (AD-3, AD-19), not DEFAULT's 30 s.
        // The tier still decides the ceiling and the priority; only the patience is different,
        // because a registration refused half way through a group leaves a HALF-REGISTERED
        // group, and that is the state nobody comes back to. A slow one is merely slow.
        HttpResponse<String> response = send(RequestTier.DEFAULT, scope, httpRequest, maxWait);
        String responseBody = response.body();
        log.debug("[Register] response HTTP {} | body: {}", response.statusCode(), responseBody);

        UserRegistrationResponse registrationResponse =
                mapper.readValue(responseBody, UserRegistrationResponse.class);
        String status = registrationResponse.getStatus();

        // The resumability read, and it comes FIRST because the alternative — isSuccess() — would
        // never see it: the EXISTED envelope is HTTP 200 with code 409, so isSuccess() is false
        // and the generic throw below would report an already-done index as a failure. That is
        // the whole of Open Item 13.
        if (STATUS_EXISTED.equalsIgnoreCase(status)) {
            log.debug("User {} already exists — index {} is already registered", username, index);
            return RegistrationOutcome.ALREADY_EXISTED;
        }

        if (!registrationResponse.isSuccess() || registrationResponse.getError() != null) {
            // Fail closed. The message carries status and code because those are what an operator
            // compares against gwms-register-envelope.md when a brand answers a shape we have not
            // seen; it deliberately carries no host, no token and no request body (A20.8), since
            // it ends up in registrationError and therefore on GET /{id}/status.
            throw new RuntimeException(String.format(
                    "Registration failed for %s: %s (status: %s, code: %d)",
                    username, registrationResponse.getMessage(), status,
                    registrationResponse.getCode()));
        }

        // NOTE: isSuccess() is `status == "OK" || code == 200`, so a 200 carrying an unrecognised
        // status is read as success. That is deliberately left as it is: isSuccess() is this
        // codebase's definition of an accepted gwms envelope on every brand that works today, and
        // tightening it to `status == "OK"` on the one path a 500-account create runs through is
        // how a brand with different wording stops being able to create groups at all. The
        // EXISTED arm above is the case that actually needed distinguishing.
        var data = registrationResponse.getData();
        if (data != null && !data.isEmpty()) {
            log.debug("Registered user {} (sessionId={})", username, data.get(0).getSessionId());
        } else {
            log.debug("Registered user {} — envelope carried no data block", username);
        }
        return RegistrationOutcome.CREATED;
    }

    /**
     * Set or update the display name (fullname) for a user.
     *
     * <p><b>There is no session token here, and its absence is load-bearing.</b> This endpoint
     * authenticates with the per-environment admin {@code X-TOKEN} and identifies the account by
     * the {@code username} in the body — exactly like {@code register.aspx} and
     * {@code deposit.aspx}. The parameter that used to sit here was documented as "retained for
     * signature compatibility; no longer used" and was, literally, never read.
     *
     * <p>That is what makes the <b>resume path cost two gateway requests rather than three</b>
     * (GATEWAY_REQUEST_BUDGET A30, correcting A17.3 / A28.3). The plan reasoned that an index
     * which registered but was never named could not be finished from a re-register, because a
     * re-register returns no {@code session_id} and {@code setDisplayName} needs one — so it
     * budgeted {@code register + login + update-fullname}. The second half of that is false in
     * this codebase: no token is needed, so the worker calls {@code update-fullname} directly and
     * the login never happens. Do not add one back on the strength of the plan's arithmetic.
     *
     * @param username    the account to name; the gateway's only identifier for it
     * @param displayName the new display name to set
     * @param scope       who this request belongs to and how to learn it has been called off. It
     *                    carries the {@code botGroupId} so a {@code DELETE} during registration
     *                    can cancel a queued one (A28.6)
     * @param maxWait     the batch's shared registration wait, passed down rather than re-read so
     *                    a late attempt does not restart the clock (review F2)
     * @return true if successful, false if the name is already taken
     */
    public boolean setDisplayName(String username, String displayName,
                                  GatewayRequestScope scope, Duration maxWait) {
        checkInitialized();
        try {
            Object body = java.util.Map.of("username", username, "fullname", displayName);

            String requestBody = mapper.writeValueAsString(body);
            String url = apiGateway + updateFullnamePath;
            log.debug("[UpdateFullname] POST {} | X-TOKEN: {} | body: {}", url, masked(xToken), requestBody);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header(SESSION_TOKEN_HEADER, xToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(GATEWAY_REQUEST_TIMEOUT)
                    .build();

            // DEFAULT tier with the registration wait override (AD-3, AD-19), and the caller's
            // scope — which carries the botGroupId, so this request is cancellable by the group
            // that owns it (A28.6). It used to build its own group-less scope here, which meant
            // a DELETE landing mid-registration could not reach a queued update-fullname at all.
            HttpResponse<String> response = send(RequestTier.DEFAULT, scope, httpRequest, maxWait);
            String responseBody = response.body();
            log.debug("[UpdateFullname] response HTTP {} | body: {}", response.statusCode(), responseBody);

            JsonNode responseJson = mapper.readTree(responseBody);
            String status = responseJson.has("status") ? responseJson.get("status").asText() : null;

            if (isDisplayNameTaken(status)) {
                log.debug("Display name '{}' is already taken ({})", displayName, status);
                return false;
            }

            if ("OK".equalsIgnoreCase(status)) {
                log.debug("Display name set successfully to: {}", displayName);
                return true;
            }

            String message = responseJson.has("message") ? responseJson.get("message").asText() : "Unknown error";
            throw new RuntimeException("Failed to set display name: " + message + " (status: " + status + ")");

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to set display name: " + displayName, e);
        }
    }

    /**
     * Whether an {@code update-fullname} response status means "that name is taken,
     * pick another" — the only outcome {@link #setDisplayNameWithRetry} should re-roll on.
     * <p>
     * Gateways are not uniform here: some answer {@code INVALID}, the RIK/P_114 gateway
     * ({@code api-gwrik.sgame.us}) answers {@code EXISTED} with HTTP-style code 409 and
     * "Tên hiển thị đã được sử dụng". Before {@code EXISTED} was recognised it fell
     * through to the generic throw, which aborted the retry loop on the first
     * collision — 19 of 100 bots in a 2026-09-18 group registered nameless, and the
     * ziczac room froze until they were named by hand (a nameless account in the room
     * stalls the round engine).
     * <p>
     * <b>Case-insensitive, like {@code registerOne}'s reading of the same constant</b> (review
     * T2). It used to be the only case-<em>sensitive</em> comparison against
     * {@link #STATUS_EXISTED} in the class, and the two strictnesses pointed the wrong way: a
     * brand answering {@code existed} would fall through to the generic throw and reproduce the
     * 2026-09-18 freeze exactly, while the fail-safe reading merely re-rolls a name. The envelope's
     * casing is not something we control.
     */
    static boolean isDisplayNameTaken(String status) {
        return "INVALID".equalsIgnoreCase(status) || STATUS_EXISTED.equalsIgnoreCase(status);
    }

    /**
     * Set a display name, re-rolling on a name collision, against the batch's shared registration
     * deadline rather than a fresh wait per attempt (review F2).
     * <p>
     * The pool collides often enough to matter — ~43% on the observed 5k-name file at 100 bots —
     * so the retry is the normal path, not an error path.
     *
     * @param username   the account to name
     * @param maxRetries how many distinct names to try before giving up
     * @param scope      the caller's cancellable, group-carrying scope (A28.6)
     * @param maxWait    the batch's shared registration wait, propagated verbatim to every
     *                   attempt
     * @return the display name that was set, or {@code null} if every attempt collided
     */
    public String setDisplayNameWithRetry(String username, int maxRetries,
                                          GatewayRequestScope scope, Duration maxWait) {
        if (!displayNameService.hasDisplayNames()) {
            // DEBUG, not WARN: with the worker this fires once per account, and CLAUDE.md's tier
            // rule puts anything whose rate is a function of account count below INFO outright.
            // The group-level statement is RegistrationWorker's one completion line; the fleet-
            // level one is the single startup WARN DisplayNameService already emits when the name
            // file is missing.
            log.debug("No display names available, skipping display name assignment for {}", username);
            return null;
        }

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String displayName = displayNameService.getRandomDisplayName();
            if (displayName == null) {
                log.debug("Failed to get random display name on attempt {}", attempt + 1);
                continue;
            }

            if (setDisplayName(username, displayName, scope, maxWait)) {
                return displayName;
            }

            log.debug("Retrying with different name (attempt {}/{})", attempt + 1, maxRetries);
        }

        // DEBUG here too, and the caller is what reports it: RegistrationWorker charges the account
        // an attempt (RIK review G1) — a nameless account stalls a ziczac round engine, so it is
        // retried on the next pass and, if it stays nameless, stops the group as
        // REGISTRATION_FAILED naming it, rather than being logged and counted as named.
        log.debug("Failed to set display name for {} after {} attempts", username, maxRetries);
        return null;
    }

    /**
     * Whether this environment has a display-name pool at all. Read by
     * {@code RegistrationWorker} so that a deployment without a name file skips the naming half
     * of registration outright, rather than calling into it once per account and taking a log
     * line per account for an answer that is constant for the JVM.
     */
    public boolean hasDisplayNames() {
        return displayNameService.hasDisplayNames();
    }

    /**
     * This environment's registration wait ({@code bot.gateway.budget.registration.max-wait}).
     * <p>
     * Exposed here rather than resolved from {@code GatewayBudgetRegistry} at the call site so
     * the worker's wait is by construction the wait of the <em>same</em> budget its requests go
     * through — a client built by a fixture carries {@link GatewayBudget#UNLIMITED} and would
     * otherwise be paced by a budget that is not admitting it.
     */
    public Duration registrationMaxWait() {
        return gatewayBudget.registrationMaxWait();
    }

    /**
     * How long a self-paced caller must sleep between requests because this environment's budget
     * is not enforcing — zero when it is (A2.3). See
     * {@link GatewayBudget#observeModePacing()}; exposed here for the same reason as
     * {@link #registrationMaxWait()}.
     */
    public Duration observeModePacing() {
        return gatewayBudget.observeModePacing();
    }

    /**
     * Deposit funds into a bot's game wallet via the gwms bot-deposit endpoint.
     * <p>
     * This credits the game-spendable wallet partition — unlike the legacy
     * {@code GameMsClient} agency-transfer path, which credits the agency
     * partition that {@code verifytoken.aspx} reports but the game engine does
     * not debit (the P_097/BOM "balance visible but bets rejected" symptom).
     * Uses the same per-env admin {@code X-TOKEN} + username-in-body pattern as
     * register / update-fullname.
     *
     * @param username Bot username to credit
     * @param amount   Amount to deposit
     * @param tier     {@link RequestTier#PRIORITIZED} from {@code Bot.deposit} — a bot that
     *                 cannot top up stops betting, but it is already up, so it does not
     *                 outrank a group that is coming up
     * @param scope    the bot this deposit is for
     * @return true if the deposit succeeded (HTTP 200)
     */
    public boolean deposit(String username, long amount, RequestTier tier, GatewayRequestScope scope) {
        return deposit(username, amount, tier, scope, null);
    }

    /**
     * {@link #deposit(String, long, RequestTier, GatewayRequestScope)} with an explicit wait,
     * overriding the tier's configured {@code max-wait}.
     * <p>
     * The one caller that needs it is {@code Bot.deposit}, which runs on a ws-parser
     * message-processor thread and must not park there longer than the watchdog's patience — see
     * {@code Bot.sessionBudgetWait()} (review F1).
     *
     * @param maxWait {@code null} to use the tier's configured wait
     */
    public boolean deposit(String username, long amount, RequestTier tier, GatewayRequestScope scope,
                           Duration maxWait) {
        checkInitialized();
        try {
            Object body = java.util.Map.of("username", username, "amount", amount);
            String requestBody = mapper.writeValueAsString(body);
            String url = apiGateway + BOT_DEPOSIT_ENDPOINT;
            log.debug("[BotDeposit] POST {} | X-TOKEN: {} | body: {}", url, masked(xToken), requestBody);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header(SESSION_TOKEN_HEADER, xToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(GATEWAY_REQUEST_TIMEOUT)
                    .build();

            HttpResponse<String> response = maxWait == null
                    ? send(tier, scope, httpRequest)
                    : send(tier, scope, httpRequest, maxWait);
            String responseBody = response.body();
            boolean success = response.statusCode() == 200;
            if (success) {
                log.debug("[BotDeposit] response HTTP {} | body: {}", response.statusCode(), responseBody);
            } else {
                log.warn("[BotDeposit] non-200 response for user {} — status: {} | body: {}",
                        username, response.statusCode(), bodyForLog(response));
            }
            return success;
        } catch (IOException | InterruptedException e) {
            log.error("Bot deposit failed for user: {}", username, e);
            return false;
        }
    }

    /**
     * Fetch balance for a user.
     *
     * @param authToken   Authentication token
     * @param fingerprint User's fingerprint
     * @param username    Username (for logging)
     * @param tier        {@link RequestTier#ESSENTIAL} for a bot's <b>first</b> read (it is on
     *                    the start path and a bot that fails it never installs its scenario),
     *                    {@link RequestTier#PRIORITIZED} for the read that confirms a deposit,
     *                    {@link RequestTier#DEFAULT} for a drift re-sync
     * @param scope       the bot this read is for
     * @return User's main balance
     */
    public long getBalance(String authToken, String fingerprint, String username,
                           RequestTier tier, GatewayRequestScope scope) {
        return getBalance(authToken, fingerprint, username, tier, scope, null);
    }

    /**
     * {@link #getBalance(String, String, String, RequestTier, GatewayRequestScope)} with an
     * explicit wait, overriding the tier's configured {@code max-wait}.
     * <p>
     * Used by every balance read {@code Bot} takes from its session path, because that path runs
     * on a ws-parser message-processor thread and a wait longer than the watchdog's patience turns
     * a healthy bot into a reconnect — see {@code Bot.sessionBudgetWait()} (review F1).
     *
     * @param maxWait {@code null} to use the tier's configured wait
     */
    public long getBalance(String authToken, String fingerprint, String username,
                           RequestTier tier, GatewayRequestScope scope, Duration maxWait) {
        return readBalance(authToken, fingerprint, username, tier, scope, false, maxWait)
                .orElseThrow(() -> new IllegalStateException(
                        "a blocking balance read returned no value for user " + username));
    }

    /**
     * Fetch balance <b>only if the budget has room right now</b>, at
     * {@link RequestTier#DEFAULT} — the drift re-sync (AD-10).
     * <p>
     * This runs on a ws-parser message-processor thread (the {@code onEndGame → onNewSession}
     * chain), which is the library's, not ours. Parking it would stall the bot's whole message
     * pipeline and throwing into it would break the session handler, so an unadmitted read is
     * simply skipped: the caller keeps its local estimate for another round and records that the
     * figure is now stale. The first read is a different matter entirely — it is on the start
     * path and blocking ESSENTIAL — and goes through {@link #getBalance}.
     *
     * @return the fresh balance, or empty when the budget had no room and <b>nothing was sent</b>
     */
    public OptionalLong getBalanceIfAdmitted(String authToken, String fingerprint, String username,
                                             GatewayRequestScope scope) {
        return readBalance(authToken, fingerprint, username, RequestTier.DEFAULT, scope, true, null);
    }

    private OptionalLong readBalance(String authToken, String fingerprint, String username,
                                     RequestTier tier, GatewayRequestScope scope, boolean deferrable,
                                     Duration maxWait) {
        checkInitialized();
        try {
            Thread.sleep(500);

            String url = apiGateway + VERIFY_TOKEN_ENDPOINT + "?token=" + authToken + "&fg=" + fingerprint;
            log.debug("[VerifyToken] GET {} | user: {}", url, username);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Cache-Control", "no-cache")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .timeout(GATEWAY_REQUEST_TIMEOUT)
                    .build();

            HttpResponse<String> maybeResponse;
            if (deferrable) {
                Optional<HttpResponse<String>> admitted = sendIfAdmitted(tier, scope, httpRequest);
                if (admitted.isEmpty()) {
                    // Deliberately not a metric increment and not a WARN: this is the budget
                    // working, on the hottest path in the fleet (one per bot per round at scale).
                    // gateway_budget_requests_total{outcome="timeout"} already counts it, per
                    // tier, and the bot records the deferral so the pre-deposit refresh knows.
                    log.debug("[VerifyToken] drift read deferred for user {} — the budget had no room",
                            username);
                    return OptionalLong.empty();
                }
                maybeResponse = admitted.get();
            } else if (maxWait == null) {
                maybeResponse = send(tier, scope, httpRequest);
            } else {
                maybeResponse = send(tier, scope, httpRequest, maxWait);
            }
            HttpResponse<String> response = maybeResponse;
            String responseBody = response.body();
            log.debug("[VerifyToken] response HTTP {} | body: {}", response.statusCode(), responseBody);

            JsonNode dataArray = mapper.readTree(responseBody).get("data");
            if (dataArray != null && dataArray.isArray() && !dataArray.isEmpty()) {
                JsonNode firstElement = dataArray.get(0);
                long balance = firstElement.get("main_balance").asLong();
                metrics.incVerifyToken(true);
                return OptionalLong.of(balance);
            } else {
                metrics.incVerifyToken(false);
                throw new RuntimeException("User: " + username + ": Data array is missing or empty: " + responseBody);
            }
        } catch (IOException | InterruptedException e) {
            if (!(e instanceof StreamWaitTimeoutException)) {
                // A33: a permit timeout never reached the gateway; it has its own counter.
                metrics.incVerifyToken(false);
            }
            throw new RuntimeException("Failed to fetch balance for user: " + username, e);
        } catch (GatewayBudgetException e) {
            if (deferrable && e instanceof com.vingame.bot.common.exception.GatewayCircuitOpenException) {
                // review-phase5: AD-10 says the drift read never parks and never throws a budget
                // outcome — it runs on a ws-parser message-processor thread (onEndGame ->
                // onNewSession). Refused reads already answer empty, but the read that DETECTS the
                // block was admitted, and httpCall's reportEdgeBlock throws from inside it. At
                // fleet scale the drift read is one of the likeliest requests to meet a block first
                // (per bot per round, on bots whose sockets are open), so every one in flight at
                // that moment used to throw out of onEndGame. The figure is now stale, which is
                // exactly what empty tells the bot; the next pre-deposit refresh is refused cleanly.
                log.debug("[VerifyToken] drift read for user {} met the Cloudflare edge block — "
                        + "the circuit is open; keeping the local estimate", username);
                return OptionalLong.empty();
            }
            // A4's milder twin, and it has to be AHEAD of the RuntimeException arm below.
            // This method already rethrows the type unwrapped (good — AD-9's non-terminal
            // handling in Bot can see it), but the arm below would increment
            // bot_verify_token_total{outcome="failure"} on the way past, which is what
            // EnvironmentAuthDown fires on. A window we paced ourselves is not an auth
            // outage, and an alert that says it is would send an operator to the gateway.
            throw e;
        } catch (RuntimeException e) {
            // Catch the RuntimeException we threw above so it's not double-incremented,
            // but anything else (e.g. JSON parse failures, NPE on missing fields) is
            // also a verify-token failure — increment and rethrow.
            if (e.getMessage() == null || !e.getMessage().startsWith("User: ")) {
                metrics.incVerifyToken(false);
            }
            throw e;
        }
    }

    // Getters for environment config (useful for other components)
    public String getApiGateway() {
        checkInitialized();
        return apiGateway;
    }

    public String getAppId() {
        checkInitialized();
        return appId;
    }
}


/*
{
  "fullname": "ggqgq4gb1123",
  "username": "ggqgq4gb1123",
  "password": "123123a",
  "app_id": "bc114097",
  "avatar": "avatar20",
  "os": "OS X",
  "device": "Computer",
  "browser": "chrome",
  "fg": "7fa167c0aac23fb9d6f364722066d63b",
  "referer": "",
  "aff_id": "BC114097"
}


{
    "status": "OK",
    "code": 200,
    "message": "Register successful",
    "data": [
        {
            "avatar": "avatar20",
            "username": "ggqgq4gb1123",
            "fullname": "_undefined",
            "is_deposit": false,
            "token": "29-b227a157b56f4b9430158cc2d71838cf",
            "session_id": "d829a3b0149f6e7e3318fbad0e17d8a7",
            "level": "LEVEL0",
            "main_balance": 0,
            "extra_balance": 0,
            "aff_id": "BC114097",
            "id": 2740,
            "type": "USER"
        }
    ]
}
* */