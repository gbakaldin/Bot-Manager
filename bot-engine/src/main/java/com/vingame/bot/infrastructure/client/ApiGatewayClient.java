package com.vingame.bot.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.common.exception.UpstreamLoginException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.infrastructure.auth.AuthProfile;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.client.dto.UserRegistrationRequest;
import com.vingame.bot.infrastructure.client.dto.UserRegistrationResponse;
import com.vingame.bot.infrastructure.client.dto.UserRegistrationResult;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.websocketparser.auth.AuthClient;
import com.vingame.websocketparser.auth.AuthContext;
import com.vingame.websocketparser.auth.LoginRequest;
import com.vingame.websocketparser.auth.TokensProvider;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
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

    private final DisplayNameService displayNameService;
    private final BotMetrics metrics;
    private final HttpClient httpClient;

    /**
     * Max number of users to register simultaneously.
     * Controls concurrency to avoid overwhelming the auth server.
     * Configurable via application.properties: user.registration.parallelism
     * <p>
     * This bounds <b>concurrency</b> (how many sockets are open at once), not <b>rate</b>.
     * Since GATEWAY_REQUEST_BUDGET AD-2 the rate is the environment's
     * {@link GatewayBudget}'s business, so raising this number no longer raises the request
     * rate against the gateway — it only makes the admitted requests overlap more.
     */
    @Value("${user.registration.parallelism:10}")
    private int registrationParallelism;

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
     * <b>The single funnel.</b> Every HTTP request this class makes is admitted by the
     * environment's budget here and nowhere else.
     * <p>
     * This is the only {@code httpClient.send(} call site in the class and
     * {@code GatewayCallSiteGuardTest} keeps it that way. From Phase 4 this is also where
     * every response is classified before it is parsed, so a Cloudflare block page becomes
     * "edge block, cf-ray …" instead of {@code Unexpected character ('<')} — the exact
     * message that was misdiagnosed for an hour on 2026-09-17.
     * <p>
     * The caller's checked exceptions are rethrown unwrapped: the funnel must not change the
     * exception a caller already handles.
     */
    private HttpResponse<String> send(RequestTier tier, GatewayRequestScope scope, HttpRequest request)
            throws IOException, InterruptedException {
        return underBudget(tier, scope, () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    /**
     * The funnel's non-HTTP twin: run {@code call} under the budget and unwrap its checked
     * exceptions.
     * <p>
     * Used for the login, which still goes through the library's {@code AuthClient} until
     * Phase 4 moves it in-repo (AD-12) — the library parses the body as JSON before anything
     * else, so neither the status code nor the {@code server} / {@code cf-ray} headers
     * survive, which is why block detection on the most exposed request has to wait for that
     * move. It costs the edge a request either way, so it is counted from Phase 1.
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

        try {
            String requestBody = mapper.writeValueAsString(loginRequestFactory.apply(ctx));
            log.debug("[Login] POST {} | X-TOKEN: {} | body: {}",
                    apiGateway + loginPath, xToken, requestBody);
        } catch (Exception e) {
            log.warn("[Login] Could not serialize login request for logging: {}", e.getMessage());
        }

        try {
            TokensProvider tokens = underBudget(tier, scope,
                    () -> new AuthClient(ctx, loginRequestFactory).authenticate());
            log.debug("[Login] response: agencyToken={} | authToken={} | jwtToken={}",
                    tokens.getAgencyToken(), tokens.getAuthToken(), tokens.getJwtToken());
            metrics.incLogin(true);
            return tokens;
        } catch (IOException | InterruptedException e) {
            // Unreachable today: the library's authenticate() throws only unchecked, and the
            // funnel rethrows the caller's checked exceptions unwrapped. Declared so that
            // Phase 4's in-repo login (which does own the HTTP call, and does throw these)
            // lands without changing this method's contract.
            metrics.incLogin(false);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UpstreamLoginException(
                    "Login failed for user '" + credentials.getUsername() + "': " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Counter increment must not change error semantics — BotFactory relies on
            // the exception propagating up so the bot creation pipeline records the failure.
            metrics.incLogin(false);
            // Wrap the library's bare RuntimeException ("No data in response" etc.)
            // in a typed UpstreamLoginException so callers and the REST advice
            // can distinguish login failures from generic runtime errors. The
            // library's message (currently misleading) is preserved verbatim
            // until the websocket-parser library learns to surface the upstream
            // envelope. See API_ERROR_FORWARDING AD-7.
            throw new UpstreamLoginException(
                    "Login failed for user '" + credentials.getUsername() + "': " + e.getMessage(),
                    e);
        }
    }

    /**
     * Bulk register users on the authentication server using parallel execution.
     * <p>
     * Uses virtual threads with controlled concurrency (configurable via user.registration.parallelism).
     * This dramatically reduces registration time compared to sequential execution:
     * - Sequential (old): 100 users × 3s = 300s (~5 minutes)
     * - Parallel (new): 100 users / 10 parallelism × ~3s = ~30s
     *
     * @param userNamePrefix Prefix for usernames (e.g., "bot" creates "bot1", "bot2", etc.)
     * @param password       Password for all created users
     * @param count          Number of users to create
     * @return UserRegistrationResult with success/failure details
     */
    public UserRegistrationResult registerUsers(String userNamePrefix, String password, int count) {
        checkInitialized();
        log.info("Starting parallel user registration: {} users with prefix '{}' (parallelism={})",
                count, userNamePrefix, registrationParallelism);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        Semaphore semaphore = new Semaphore(registrationParallelism);

        // Use virtual threads for parallel registration
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("user-registration-", 0).factory())) {

            List<CompletableFuture<Void>> futures = new ArrayList<>(count);

            for (int i = 1; i <= count; i++) {
                final int userIndex = i;
                final String username = userNamePrefix + userIndex;

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        // Acquire permit (blocks if at max concurrency)
                        semaphore.acquire();
                        try {
                            registerSingleUserWithDisplayName(userNamePrefix, password, userIndex, count);
                            successCount.incrementAndGet();
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failureCount.incrementAndGet();
                        String errorMsg = String.format("Registration interrupted for %s", username);
                        errors.add(errorMsg);
                        log.error(errorMsg);
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                        String errorMsg = String.format("Failed to register %s: %s", username, e.getMessage());
                        errors.add(errorMsg);
                        log.error(errorMsg);
                    }
                }, executor);

                futures.add(future);
            }

            // Wait for all registrations to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        log.info("Parallel user registration completed. Success: {}, Failures: {}",
                successCount.get(), failureCount.get());

        return UserRegistrationResult.builder()
                .totalRequested(count)
                .successCount(successCount.get())
                .failureCount(failureCount.get())
                .errors(errors)
                .build();
    }

    /**
     * Register a single user and set their display name.
     * This method is called in parallel from registerUsers().
     * <p>
     * Uses tokens returned directly from the register response — no re-authentication needed.
     *
     * @param userNamePrefix Username prefix
     * @param password       Password
     * @param index          User index (1-based)
     * @param totalCount     Total number of users being registered (for logging)
     */
    private void registerSingleUserWithDisplayName(String userNamePrefix, String password, int index, int totalCount) {
        String username = userNamePrefix + index;

        try {
            RegistrationResult result = registerSingleUser(userNamePrefix, password, index);
            log.debug("Successfully registered user {}/{}: {}", index, totalCount, username);

            if (displayNameService.hasDisplayNames()) {
                try {
                    String displayName = setDisplayNameWithRetry(username, result.authToken(), 5);
                    if (displayName != null) {
                        log.debug("Set display name '{}' for user {}", displayName, username);
                    } else {
                        log.warn("Could not set display name for user {}", username);
                    }
                } catch (Exception e) {
                    log.warn("Failed to set display name for {}: {}", username, e.getMessage());
                }
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to register user: " + username, e);
        }
    }

    private record RegistrationResult(String agencyToken, String authToken, String fingerprint) {}

    private RegistrationResult registerSingleUser(String userNamePrefix, String password, int index) throws IOException, InterruptedException {
        String ip = botIp;
        String username = userNamePrefix + index;
        String fingerprint = AuthClient.generateFingerprint();

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
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(apiGateway + registrationPath))
                .header("Content-Type", "application/json")
                .header(SESSION_TOKEN_HEADER, xToken);

        String requestBody = mapper.writeValueAsString(request);
        log.debug("[Register] POST {} | X-TOKEN: {} | body: {}",
                apiGateway + registrationPath, xToken, requestBody);

        HttpRequest httpRequest = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        // DEFAULT tier (AD-3): registration is the work whose deferral costs nothing that is
        // not recoverable. The scope carries the username PREFIX, which is the only identity
        // a registration has — it runs inside BotGroupService.save, before any bot of the
        // group exists, so there is no botGroupId to cancel it by.
        HttpResponse<String> response = send(RequestTier.DEFAULT,
                GatewayRequestScope.registration(userNamePrefix), httpRequest);
        String responseBody = response.body();
        log.debug("[Register] response HTTP {} | body: {}", response.statusCode(), responseBody);

        UserRegistrationResponse registrationResponse = mapper.readValue(responseBody, UserRegistrationResponse.class);

        if (!registrationResponse.isSuccess() || registrationResponse.getError() != null) {
            String errorMsg = String.format("Registration failed: %s (status: %s, code: %d)",
                registrationResponse.getMessage(),
                registrationResponse.getStatus(),
                registrationResponse.getCode());
            throw new RuntimeException(errorMsg);
        }

        var data = registrationResponse.getData().get(0);
        log.debug("Registered user {} with sessionId={} (fingerprint={})",
                username, data.getSessionId(), fingerprint);
        return new RegistrationResult(data.getToken(), data.getSessionId(), fingerprint);
    }

    /**
     * Set or update the display name (fullname) for a user.
     *
     * @param username     The username (required for B52 endpoint)
     * @param sessionToken Retained for signature compatibility; no longer used (X-TOKEN is always used).
     * @param displayName  The new display name to set
     * @return true if successful, false if name is already taken
     */
    public boolean setDisplayName(String username, String sessionToken, String displayName) {
        checkInitialized();
        try {
            Object body = java.util.Map.of("username", username, "fullname", displayName);

            String requestBody = mapper.writeValueAsString(body);
            String url = apiGateway + updateFullnamePath;
            log.debug("[UpdateFullname] POST {} | X-TOKEN: {} | body: {}", url, xToken, requestBody);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header(SESSION_TOKEN_HEADER, xToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            // DEFAULT tier, registration scope (AD-3). The identity available here is the
            // full username rather than the prefix — this is called from the retry loop, one
            // user at a time, and the username is what an operator greps when a bot ends up
            // nameless (a nameless account stalls the ziczac round engine).
            HttpResponse<String> response = send(RequestTier.DEFAULT,
                    GatewayRequestScope.registration(username), httpRequest);
            String responseBody = response.body();
            log.debug("[UpdateFullname] response HTTP {} | body: {}", response.statusCode(), responseBody);

            JsonNode responseJson = mapper.readTree(responseBody);
            String status = responseJson.has("status") ? responseJson.get("status").asText() : null;

            if (isDisplayNameTaken(status)) {
                log.debug("Display name '{}' is already taken ({})", displayName, status);
                return false;
            }

            if ("OK".equals(status)) {
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
     */
    static boolean isDisplayNameTaken(String status) {
        return "INVALID".equals(status) || "EXISTED".equals(status);
    }

    /**
     * Set display name with automatic retry on name conflicts.
     *
     * @param username     The username (required for B52 endpoint)
     * @param sessionToken The session token from authentication (used for standard envs)
     * @param maxRetries   Maximum retry attempts
     * @return The display name that was set, or null if all attempts failed
     */
    public String setDisplayNameWithRetry(String username, String sessionToken, int maxRetries) {
        if (!displayNameService.hasDisplayNames()) {
            log.warn("No display names available, skipping display name assignment");
            return null;
        }

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            String displayName = displayNameService.getRandomDisplayName();
            if (displayName == null) {
                log.warn("Failed to get random display name on attempt {}", attempt + 1);
                continue;
            }

            if (setDisplayName(username, sessionToken, displayName)) {
                return displayName;
            }

            log.debug("Retrying with different name (attempt {}/{})", attempt + 1, maxRetries);
        }

        log.error("Failed to set display name after {} attempts", maxRetries);
        return null;
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
        checkInitialized();
        try {
            Object body = java.util.Map.of("username", username, "amount", amount);
            String requestBody = mapper.writeValueAsString(body);
            String url = apiGateway + BOT_DEPOSIT_ENDPOINT;
            log.debug("[BotDeposit] POST {} | X-TOKEN: {} | body: {}", url, xToken, requestBody);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header(SESSION_TOKEN_HEADER, xToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = send(tier, scope, httpRequest);
            String responseBody = response.body();
            boolean success = response.statusCode() == 200;
            if (success) {
                log.debug("[BotDeposit] response HTTP {} | body: {}", response.statusCode(), responseBody);
            } else {
                log.warn("[BotDeposit] non-200 response for user {} — status: {} | body: {}",
                        username, response.statusCode(), responseBody);
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
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = send(tier, scope, httpRequest);
            String responseBody = response.body();
            log.debug("[VerifyToken] response HTTP {} | body: {}", response.statusCode(), responseBody);

            JsonNode dataArray = mapper.readTree(responseBody).get("data");
            if (dataArray != null && dataArray.isArray() && !dataArray.isEmpty()) {
                JsonNode firstElement = dataArray.get(0);
                long balance = firstElement.get("main_balance").asLong();
                metrics.incVerifyToken(true);
                return balance;
            } else {
                metrics.incVerifyToken(false);
                throw new RuntimeException("User: " + username + ": Data array is missing or empty: " + responseBody);
            }
        } catch (IOException | InterruptedException e) {
            metrics.incVerifyToken(false);
            throw new RuntimeException("Failed to fetch balance for user: " + username, e);
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