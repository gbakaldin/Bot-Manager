package com.vingame.bot.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

@Slf4j
public class GameMsClient {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String AGENCY_TRANSFER_ENDPOINT = "/gamems/v1/agency/transfer";
    private static final String AGENCY_VERIFY_TOKEN_ENDPOINT = "/gamems/v1/agency/verifytoken/";
    private static final String USER_AGENT = "PostmanRuntime/7.15.2";

    private final String gameMsUrl;

    /**
     * NEW: Stateless constructor for shared GameMsClient instances.
     * Does not store user-specific credentials or token details.
     *
     * @param gameMsUrl Game microservice base URL
     */
    public GameMsClient(String gameMsUrl) {
        this.gameMsUrl = gameMsUrl;
    }

    /**
     * DEPRECATED: Use stateless constructor + deposit(agencyToken, amount, onComplete) instead.
     * This constructor stores per-bot credentials in the client, which prevents sharing.
     *
     * @deprecated Use {@link #GameMsClient(String)} and {@link #deposit(String, long, Consumer)}
     */
    @Deprecated
    public GameMsClient() {
        this.gameMsUrl = "http://gamems.dev:5007"; // Default hardcoded value
    }

    // Deprecated stateful fields - will be removed in Phase 5
    @Deprecated
    private String agencyToken;
    @Deprecated
    private int agencyId;
    @Deprecated
    private String uuid = null;
    @Deprecated
    private String memberId = null;

    /**
     * NEW: Stateless deposit method that accepts agencyToken as parameter.
     * Allows a single GameMsClient instance to handle deposits for multiple bots.
     *
     * @param agencyToken Agency authentication token
     * @param amount Amount to deposit
     * @param onComplete Callback with success/failure status
     */
    public void deposit(String agencyToken, long amount, Consumer<Boolean> onComplete) {
        TokenDetails tokenDetails = fetchTokenDetails(agencyToken);
        int agencyId = Integer.parseInt(agencyToken.substring(0, 1));

        Thread t = new Thread(() -> {
            try {
                log.info("Depositing {} for agency token {}", amount, agencyToken);

                DepositRequest requestBody = DepositRequest.builder()
                        .token(agencyToken)
                        .uid(tokenDetails.getUuid())
                        .agencyId(agencyId)
                        .memberId(tokenDetails.getMemberId())
                        .amount(amount)
                        .build();

                String jsonBody = "[" + requestBody.serialize() + "]";

                // Build HTTP request using Java 11+ HttpClient
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(gameMsUrl + AGENCY_TRANSFER_ENDPOINT))
                        .header("Cache-Control", "no-cache")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                // Send request
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                boolean success = response.statusCode() == 200;
                onComplete.accept(success);

                log.info("Response code: {} | response body: {}", response.statusCode(), response.body());

            } catch (IOException | InterruptedException e) {
                onComplete.accept(false);
                log.error("Deposit failed for agency token {}", agencyToken, e);
            }
        });

        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * NEW: Stateless method to fetch token details from agency token.
     * Returns TokenDetails containing uuid and memberId.
     *
     * @param agencyToken Agency authentication token
     * @return TokenDetails with uuid and memberId
     */
    public TokenDetails fetchTokenDetails(String agencyToken) {
        try {
            log.info("Fetching token details for agency token {}", agencyToken);

            String urlString = gameMsUrl + AGENCY_VERIFY_TOKEN_ENDPOINT + agencyToken;
            log.info("URI {}", urlString);

            // Build HTTP request using Java 11+ HttpClient
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(urlString))
                    .header("Cache-Control", "no-cache")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            // Send request
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            String responseBody = response.body();
            JsonNode dataArray = mapper.readTree(responseBody).get("data");

            log.info("Token details: {}", responseBody);

            String uuid = dataArray.get(0).get("uid").asText();
            String memberId = dataArray.get(0).get("member_id").asText();

            return new TokenDetails(uuid, memberId);

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to fetch token details for agency token: " + agencyToken, e);
        }
    }

    /**
     * DEPRECATED: Use stateless version deposit(agencyToken, amount, onComplete) instead.
     * This method relies on stored agencyToken field from parent class.
     *
     * @deprecated Use {@link #deposit(String, long, Consumer)}
     */
    @Deprecated
    public void deposit(long amount, Consumer<Boolean> onComplete) {
        fetchTokenDetails();

        Thread t = new Thread(() -> {
            try {
                log.info("Depositing {} ", amount);

                DepositRequest requestBody = DepositRequest.builder()
                        .token(agencyToken)
                        .uid(uuid)
                        .agencyId(agencyId)
                        .memberId(memberId)
                        .amount(amount)
                        .build();

                String jsonBody = "[" + requestBody.serialize() + "]";

                // Build HTTP request using Java 11+ HttpClient
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(gameMsUrl + AGENCY_TRANSFER_ENDPOINT))
                        .header("Cache-Control", "no-cache")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                // Send request
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                boolean success = response.statusCode() == 200;
                onComplete.accept(success);

                log.info("Response code: {} | response body: {}", response.statusCode(), response.body());

            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * DEPRECATED: Use stateless version fetchTokenDetails(agencyToken) instead.
     * This method relies on stored agencyToken field.
     *
     * @deprecated Use {@link #fetchTokenDetails(String)}
     */
    @Deprecated
    private void fetchTokenDetails() {
        Thread t = new Thread(() -> {
            try {
                log.info("Fetching token details");

                String urlString = gameMsUrl + AGENCY_VERIFY_TOKEN_ENDPOINT + agencyToken;
                log.info("URI {}", urlString);

                // Build HTTP request using Java 11+ HttpClient
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(urlString))
                        .header("Cache-Control", "no-cache")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();

                // Send request
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                String responseBody = response.body();
                JsonNode dataArray = mapper.readTree(responseBody).get("data");

                log.info("Token details: {}", responseBody);

                uuid = dataArray.get(0).get("uid").asText();
                memberId = dataArray.get(0).get("member_id").asText();

            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * DEPRECATED: This method stores per-bot agency token in the client.
     * Use stateless deposit(agencyToken, amount, onComplete) instead.
     *
     * @deprecated Use {@link #deposit(String, long, Consumer)}
     */
    @Deprecated
    public void setAgencyToken(String agencyToken) {
        this.agencyToken = agencyToken;
        this.agencyId = Integer.parseInt(this.agencyToken.substring(0, 1));
    }

    /**
     * Value object containing token details fetched from agency verification endpoint.
     */
    @Getter
    @AllArgsConstructor
    public static class TokenDetails {
        private final String uuid;
        private final String memberId;
    }

    @Getter
    private static final class DepositRequest {
        @JsonProperty("token") private final String token;
        @JsonProperty("uid") private final String uid;
        @JsonProperty("agency_id") private final int agencyId;
        @JsonProperty("member_id") private final String memberId;
        @JsonProperty("amount") private final long amount;
        @JsonProperty("transaction_id") private final String transactionId;

        @JsonProperty("action") private final String action = "WIN";

        @JsonProperty("data") private final DepositRequestData data;

        public DepositRequest(Builder builder) {
            this.token = builder.token;
            this.uid = builder.uid;
            this.agencyId = builder.agencyId;
            this.memberId = builder.memberId;
            this.amount = builder.amount;
            this.transactionId = builder.token + "-" + java.util.UUID.randomUUID();
            this.data = new DepositRequestData(builder.amount);
        }

        public String serialize() {
            try {
                return mapper.writeValueAsString(this);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public static Builder builder() {
            return new Builder();
        }

        private static class Builder {
            private String token;
            private String uid;
            private int agencyId;
            private String memberId;
            private long amount;

            public Builder token(String token) {
                this.token = token;
                return this;
            }

            public Builder uid(String uid) {
                this.uid = uid;
                return this;
            }

            public Builder agencyId(int agencyId) {
                this.agencyId = agencyId;
                return this;
            }

            public Builder memberId(String memberId) {
                this.memberId = memberId;
                return this;
            }

            public Builder amount(long amount) {
                this.amount = amount;
                return this;
            }

            public DepositRequest build() {
                return new DepositRequest(this);
            }
        }
    }

    @Getter
    @RequiredArgsConstructor
    private static final class DepositRequestData {
        @JsonProperty("game_ticket_status") private final String gameTicketStatus = "Deposit";
        @JsonProperty("game_your_bet") private final String gameYourBet = "User For BE to test";
        @JsonProperty("game_winlost") private final long gameWinLost;
    }
}
