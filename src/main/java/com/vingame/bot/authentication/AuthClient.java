package com.vingame.bot.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class AuthClient {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newHttpClient();


    public long getBalance(String apiGateway, String authToken) {
        AtomicLong mainBalance = new AtomicLong(-1);

        Thread balanceRequest = (new Thread(() -> {
            try {
                log.info("Fetching balance from wallet");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI(apiGateway + "/gwms/v1/verifytoken.aspx?token=" + authToken + "&fg=7fa167c0aac23fb9d6f364722066d63b"))
                        .header("Cache-Control", "no-cache")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "PostmanRuntime/7.15.2")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                JsonNode dataArray = mapper.readTree(response.body()).get("data");
                if (dataArray != null && dataArray.isArray() && !dataArray.isEmpty()) {
                    JsonNode firstElement = dataArray.get(0);
                    mainBalance.set(firstElement.get("main_balance").asLong());
                    log.info("The balance is: {}", mainBalance);
                } else {
                    throw new IllegalArgumentException("Data array is missing or empty: " + response.body());
                }
            } catch (URISyntaxException | IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }));

        balanceRequest.start();
        try {
            balanceRequest.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        return mainBalance.get();
    }
}
