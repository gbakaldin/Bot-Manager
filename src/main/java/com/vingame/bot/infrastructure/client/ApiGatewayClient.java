package com.vingame.bot.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.infrastructure.client.dto.UserRegistrationRequest;
import com.vingame.bot.infrastructure.client.dto.UserRegistrationResponse;
import com.vingame.bot.infrastructure.client.dto.UserRegistrationResult;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.websocketparser.auth.AuthClient;
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

    private static final String USER_REGISTER_ENDPOINT = "/user/register.aspx";
    private static final String USER_UPDATE_ENDPOINT = "/user/update.aspx";
    private static final String VERIFY_TOKEN_ENDPOINT = "/gwms/v1/verifytoken.aspx";
    private static final String USER_AGENT = "PostmanRuntime/7.15.2";
    private static final String SESSION_TOKEN_HEADER = "X-TOKEN";

    private final DisplayNameService displayNameService;
    private final HttpClient httpClient;

    /**
     * Max number of users to register simultaneously.
     * Controls concurrency to avoid overwhelming the auth server.
     * Configurable via application.properties: user.registration.parallelism
     */
    @Value("${user.registration.parallelism:10}")
    private int registrationParallelism;

    private String apiGateway;
    private String appId;
    private boolean initialized = false;

    @Autowired
    public ApiGatewayClient(DisplayNameService displayNameService) {
        this.displayNameService = displayNameService;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Initialize the client with environment-specific configuration.
     * Must be called before using any other methods.
     *
     * @param apiGateway API gateway base URL
     * @param appId      Application ID
     * @return this instance for method chaining
     */
    public ApiGatewayClient init(String apiGateway, String appId) {
        this.apiGateway = apiGateway;
        this.appId = appId;
        this.initialized = true;
        log.debug("ApiGatewayClient initialized with gateway: {}, appId: {}", apiGateway, appId);
        return this;
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
     * @return List of tokens [agencyToken, authToken]
     */
    public List<String> authenticate(BotCredentials credentials) {
        checkInitialized();

        // Use AuthClient for authentication (composition)
        AuthClient authClient = new AuthClient(
            apiGateway,
            credentials.getUsername(),
            credentials.getPassword(),
            appId,
            credentials.getFingerprint()
        );

        TokensProvider tokens = authClient.authenticate();
        return List.of(tokens.getAgencyToken(), tokens.getAuthToken());
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
     *
     * @param userNamePrefix Username prefix
     * @param password       Password
     * @param index          User index (1-based)
     * @param totalCount     Total number of users being registered (for logging)
     */
    private void registerSingleUserWithDisplayName(String userNamePrefix, String password, int index, int totalCount) {
        String username = userNamePrefix + index;

        try {
            registerSingleUser(userNamePrefix, password, index);
            log.info("Successfully registered user {}/{}: {}", index, totalCount, username);

            // Set display name if names are available
            if (displayNameService.hasDisplayNames()) {
                try {
                    setDisplayNameForUser(username, password);
                } catch (Exception e) {
                    log.warn("Failed to set display name for {}: {}", username, e.getMessage());
                    // Don't fail the whole registration just because display name failed
                }
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to register user: " + username, e);
        }
    }

    /**
     * Authenticate a user and set their display name.
     */
    private void setDisplayNameForUser(String username, String password) {
        String fingerprint = AuthClient.generateFingerprint();
        BotCredentials credentials = BotCredentials.builder()
                .username(username)
                .password(password)
                .fingerprint(fingerprint)
                .build();

        List<String> tokens = authenticate(credentials);
        if (tokens == null || tokens.isEmpty()) {
            throw new RuntimeException("Authentication failed for user: " + username);
        }

        String authToken = tokens.getFirst();

        // Set display name with retry
        String displayName = setDisplayNameWithRetry(authToken, 5);
        if (displayName != null) {
            log.info("Set display name '{}' for user {}", displayName, username);
        } else {
            log.warn("Could not set display name for user {}", username);
        }
    }

    private void registerSingleUser(String userNamePrefix, String password, int index) throws IOException, InterruptedException {
        String ip = generateRandomIp();
        String avatar = "Avatar" + (int)(Math.random() * 45 + 1);
        String fingerprint = AuthClient.generateFingerprint();

        UserRegistrationRequest request = UserRegistrationRequest.builder()
                .fullname("_undefined")
                .username(userNamePrefix + index)
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

        String requestBody = mapper.writeValueAsString(request);

        /*
        --header 'Origin: https://097.stgame.win'
        --header 'Referer: https://097.stgame.win' \
        **/

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiGateway + USER_REGISTER_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Origin", "https://097.stgame.win")
                .header("Referer", "https://097.stgame.win")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        log.info("Sending request to {} for user {}: {}", apiGateway + USER_REGISTER_ENDPOINT, userNamePrefix + index, requestBody);
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        String responseBody = response.body();
        log.info("Registration response for {}{}: {}", userNamePrefix, index, responseBody);

        UserRegistrationResponse registrationResponse = mapper.readValue(responseBody, UserRegistrationResponse.class);

        if (!registrationResponse.isSuccess() || registrationResponse.getError() != null) {
            String errorMsg = String.format("Registration failed: %s (status: %s, code: %d)",
                registrationResponse.getMessage(),
                registrationResponse.getStatus(),
                registrationResponse.getCode());
            throw new RuntimeException(errorMsg);
        }
    }

    private String generateRandomIp() {
        return (int)(Math.random() * 255 + 1) + "." +
               (int)(Math.random() * 255 + 1) + "." +
               (int)(Math.random() * 255 + 1) + "." +
               (int)(Math.random() * 255 + 1);
    }

    /**
     * Set or update the display name (fullname) for a user.
     *
     * @param sessionToken The session token (X-TOKEN) obtained from authentication
     * @param displayName  The new display name to set
     * @return true if successful, false if name is already taken
     */
    public boolean setDisplayName(String sessionToken, String displayName) {
        checkInitialized();
        try {
            log.info("Setting display name to: {}", displayName);

            String requestBody = mapper.writeValueAsString(java.util.Map.of("fullname", displayName));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiGateway + USER_UPDATE_ENDPOINT))
                    .header("Content-Type", "application/json")
                    .header(SESSION_TOKEN_HEADER, sessionToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();
            log.debug("Set display name response: {}", responseBody);

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
     * @param sessionToken The session token from authentication
     * @param maxRetries   Maximum retry attempts
     * @return The display name that was set, or null if all attempts failed
     */
    public String setDisplayNameWithRetry(String sessionToken, int maxRetries) {
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

            if (setDisplayName(sessionToken, displayName)) {
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
            log.info("User {}: Fetching balance from wallet", username);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiGateway + VERIFY_TOKEN_ENDPOINT + "?token=" + authToken + "&fg=" + fingerprint))
                    .header("Cache-Control", "no-cache")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();

            JsonNode dataArray = mapper.readTree(responseBody).get("data");
            if (dataArray != null && dataArray.isArray() && !dataArray.isEmpty()) {
                JsonNode firstElement = dataArray.get(0);
                long balance = firstElement.get("main_balance").asLong();
                log.info("User {}: The balance is: {}", username, balance);
                return balance;
            } else {
                throw new RuntimeException("User: " + username + ": Data array is missing or empty: " + responseBody);
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to fetch balance for user: " + username, e);
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