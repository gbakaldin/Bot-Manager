package com.vingame.bot.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.infrastructure.auth.AuthProfile;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Client for interacting with the API Gateway.
 * This is a prototype-scoped bean - each injection gets a new instance
 * that must be initialized with environment-specific data via {@link #init(String, String)}.
 */
@Slf4j
@Service
@Scope("prototype")
public class ApiGatewayClient {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String VERIFY_TOKEN_ENDPOINT = "/gwms/v1/verifytoken.aspx";
    private static final String USER_AGENT = "PostmanRuntime/7.15.2";
    private static final String SESSION_TOKEN_HEADER = "X-TOKEN";

    private final DisplayNameService displayNameService;
    private final BotMetrics metrics;
    private final HttpClient httpClient;

    /**
     * Max number of users to register simultaneously.
     * Controls concurrency to avoid overwhelming the auth server.
     * Configurable via application.properties: user.registration.parallelism
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
    public ApiGatewayClient init(String apiGateway, String appId, AuthProfile authProfile) {
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

    /** Backward-compatible overload — uses standard user endpoints and no X-TOKEN. */
    public ApiGatewayClient init(String apiGateway, String appId, Function<AuthContext, ? extends LoginRequest> loginRequestFactory) {
        return init(apiGateway, appId, new AuthProfile(
                "/user/login.aspx", "/user/register.aspx", "/user/update.aspx", null, loginRequestFactory
        ));
    }

    private void checkInitialized() {
        if (!initialized) {
            throw new IllegalStateException("ApiGatewayClient not initialized. Call init(apiGateway, appId) first.");
        }
    }

    /**
     * Authenticate a bot with the given credentials.
     *
     * @param credentials Bot credentials (username, password, fingerprint)
     * @return TokensProvider containing agencyToken, authToken, and jwtToken
     */
    public TokensProvider authenticate(BotCredentials credentials) {
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
            log.info("[Login] POST {} | X-TOKEN: {} | body: {}",
                    apiGateway + loginPath, xToken != null ? xToken : "(none)", requestBody);
        } catch (Exception e) {
            log.warn("[Login] Could not serialize login request for logging: {}", e.getMessage());
        }

        try {
            TokensProvider tokens = new AuthClient(ctx, loginRequestFactory).authenticate();
            log.info("[Login] response: agencyToken={} | authToken={} | jwtToken={}",
                    tokens.getAgencyToken(), tokens.getAuthToken(), tokens.getJwtToken());
            metrics.incLogin(true);
            return tokens;
        } catch (RuntimeException e) {
            // Counter increment must not change error semantics — BotFactory relies on
            // the exception propagating up so the bot creation pipeline records the failure.
            metrics.incLogin(false);
            throw e;
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
     * A 500ms delay before verifytoken gives the auth server time to reach internal consistency.
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
            log.info("Successfully registered user {}/{}: {}", index, totalCount, username);

            if (displayNameService.hasDisplayNames()) {
                try {
                    if (xToken == null) {
                        Thread.sleep(500);
                        verifyToken(result.authToken(), result.fingerprint());
                    }
                    String displayName = setDisplayNameWithRetry(username, result.authToken(), 5);
                    if (displayName != null) {
                        log.info("Set display name '{}' for user {}", displayName, username);
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

    /**
     * Call verifytoken to activate the session after registration.
     * The response is not used — the call is made purely to allow the auth server
     * to complete internal session setup before subsequent operations.
     */
    private void verifyToken(String authToken, String fingerprint) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiGateway + VERIFY_TOKEN_ENDPOINT + "?token=" + authToken + "&fg=" + fingerprint))
                .header("Cache-Control", "no-cache")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    private record RegistrationResult(String agencyToken, String authToken, String fingerprint) {}

    private RegistrationResult registerSingleUser(String userNamePrefix, String password, int index) throws IOException, InterruptedException {
        String ip = botIp;
        String username = userNamePrefix + index;
        String fingerprint = AuthClient.generateFingerprint();

        UserRegistrationRequest request;
        HttpRequest.Builder requestBuilder;

        if (xToken != null) {
            request = UserRegistrationRequest.builder()
                    .username(username)
                    .password(password)
                    .ip(ip)
                    .registerIp(ip)
                    .os("OS X")
                    .appId(appId)
                    .device("Computer")
                    .browser("chrome")
                    .source(appId)
                    .build();
            requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiGateway + registrationPath))
                    .header("Content-Type", "application/json")
                    .header(SESSION_TOKEN_HEADER, xToken);
        } else {
            String avatar = "Avatar" + (int) (Math.random() * 45 + 1);
            request = UserRegistrationRequest.builder()
                    .fullname("_undefined")
                    .username(username)
                    .password(password)
                    .avatar(avatar)
                    .registerIp(ip)
                    .ip(ip)
                    .os("OS X")
                    .appId(appId)
                    .device("Computer")
                    .browser("chrome")
                    .fg(fingerprint)
                    .type("BOT")
                    .build();
            requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiGateway + registrationPath))
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://097.stgame.win")
                    .header("Referer", "https://097.stgame.win");
        }

        String requestBody = mapper.writeValueAsString(request);
        log.info("[Register] POST {} | X-TOKEN: {} | body: {}",
                apiGateway + registrationPath, xToken != null ? xToken : "(none)", requestBody);

        HttpRequest httpRequest = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();
        log.info("[Register] response HTTP {} | body: {}", response.statusCode(), responseBody);

        UserRegistrationResponse registrationResponse = mapper.readValue(responseBody, UserRegistrationResponse.class);

        if (!registrationResponse.isSuccess() || registrationResponse.getError() != null) {
            String errorMsg = String.format("Registration failed: %s (status: %s, code: %d)",
                registrationResponse.getMessage(),
                registrationResponse.getStatus(),
                registrationResponse.getCode());
            throw new RuntimeException(errorMsg);
        }

        var data = registrationResponse.getData().get(0);
        return new RegistrationResult(data.getToken(), data.getSessionId(), fingerprint);
    }

    /**
     * Set or update the display name (fullname) for a user.
     *
     * @param username     The username (required for B52 endpoint)
     * @param sessionToken The session token obtained from authentication (used for standard envs)
     * @param displayName  The new display name to set
     * @return true if successful, false if name is already taken
     */
    public boolean setDisplayName(String username, String sessionToken, String displayName) {
        checkInitialized();
        try {
            Object body;
            String headerToken;
            if (xToken != null) {
                body = java.util.Map.of("username", username, "fullname", displayName);
                headerToken = xToken;
            } else {
                body = java.util.Map.of("fullname", displayName, "aff_id", "");
                headerToken = sessionToken;
            }

            String requestBody = mapper.writeValueAsString(body);
            String url = apiGateway + updateFullnamePath;
            log.info("[UpdateFullname] POST {} | X-TOKEN: {} | body: {}", url, headerToken, requestBody);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header(SESSION_TOKEN_HEADER, headerToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();
            log.info("[UpdateFullname] response HTTP {} | body: {}", response.statusCode(), responseBody);

            JsonNode responseJson = mapper.readTree(responseBody);
            String status = responseJson.has("status") ? responseJson.get("status").asText() : null;

            if ("INVALID".equals(status)) {
                log.warn("Display name '{}' is already taken", displayName);
                return false;
            }

            if ("OK".equals(status)) {
                log.info("Display name set successfully to: {}", displayName);
                return true;
            }

            String message = responseJson.has("message") ? responseJson.get("message").asText() : "Unknown error";
            throw new RuntimeException("Failed to set display name: " + message + " (status: " + status + ")");

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to set display name: " + displayName, e);
        }
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

            log.info("Retrying with different name (attempt {}/{})", attempt + 1, maxRetries);
        }

        log.error("Failed to set display name after {} attempts", maxRetries);
        return null;
    }

    /**
     * Fetch balance for a user.
     *
     * @param authToken   Authentication token
     * @param fingerprint User's fingerprint
     * @param username    Username (for logging)
     * @return User's main balance
     */
    public long getBalance(String authToken, String fingerprint, String username) {
        checkInitialized();
        try {
            Thread.sleep(500);

            String url = apiGateway + VERIFY_TOKEN_ENDPOINT + "?token=" + authToken + "&fg=" + fingerprint;
            log.info("[VerifyToken] GET {} | user: {}", url, username);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Cache-Control", "no-cache")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();
            log.info("[VerifyToken] response HTTP {} | body: {}", response.statusCode(), responseBody);

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