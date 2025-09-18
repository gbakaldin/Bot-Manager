package com.vingame.bot.authentication;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Consumer;

@Slf4j
public class GameMsClient {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private static final String GAME_MS_URL = "http://gamems.dev:5007";

    private final String agencyToken;
    private final int agencyId;

    private String uuid = null;
    private String memberId = null;


    public GameMsClient(String agencyToken) {
        this.agencyToken = agencyToken;

        this.agencyId = Integer.parseInt(this.agencyToken.substring(0, 1));
    }

    public void deposit(long amount, Consumer<Boolean> onComplete) {
        fetchTokenDetails();

        Thread t = (new Thread(() -> {
            try {
                log.info("Depositing {} ", amount);

                DepositRequest requestBody = DepositRequest.builder()
                        .token(agencyToken)
                        .uid(uuid)
                        .agencyId(agencyId)
                        .memberId(memberId)
                        .amount(amount)
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI(GAME_MS_URL + "/gamems/v1/agency/transfer"))
                        .header("Cache-Control", "no-cache")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "PostmanRuntime/7.15.2")
                        .POST(HttpRequest.BodyPublishers.ofString("[" + requestBody.serialize() + "]"))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                boolean success = response.statusCode() == 200;
                onComplete.accept(success);

                log.info("Response code: {} | response body: {}", response.statusCode(), response.body());

            } catch (URISyntaxException | IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }));

        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void fetchTokenDetails() {
        Thread t = (new Thread(() -> {
            try {
                log.info("Fetching token details");

                log.info("URI {}", new URI(GAME_MS_URL + "/gamems/v1/agency/verifytoken/" + agencyToken));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI(GAME_MS_URL + "/gamems/v1/agency/verifytoken/" + agencyToken))
                        .header("Cache-Control", "no-cache")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "PostmanRuntime/7.15.2")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode dataArray = mapper.readTree(response.body()).get("data");

                log.info("Token details: {}", response.body());

                uuid = dataArray.get(0).get("uid").asText();
                memberId = dataArray.get(0).get("member_id").asText();


            } catch (URISyntaxException | IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }));

        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
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
