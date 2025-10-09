package com.vingame.bot.environment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.webocketparser.auth.AuthClient;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class ApiGatewayClient extends AuthClient {

    private static final ObjectMapper mapper = new ObjectMapper();

    //private final HttpClient httpClient = HttpClient.newHttpClient();

    public ApiGatewayClient(String apiGateway, String appId, String userName, String password, String fingerprint) {
        super(apiGateway, userName, password, appId, fingerprint);
    }

    public long getBalance(String apiGateway, String authToken) {
        AtomicLong mainBalance = new AtomicLong(-1);

        Thread balanceRequest = (new Thread(() -> {
            try {
                log.info("User {}: Fetching balance from wallet", getUserName());

                /*HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI(apiGateway + "/gwms/v1/verifytoken.aspx?token=" + authToken + "&fg=7fa167c0aac23fb9d6f364722066d63b"))
                        .header("Cache-Control", "no-cache")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "PostmanRuntime/7.15.2")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());*/

                String fg = "7fa167c0aac23fb9d6f364722066d63b"; //"7fa167c0aac23fb9d6f364722066d63b"

                String urlString = apiGateway + "/gwms/v1/verifytoken.aspx?token=" + authToken + "&fg=7fa167c0aac23fb9d6f364722066d63b";
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Cache-Control", "no-cache");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "PostmanRuntime/7.15.2");

                int responseCode = conn.getResponseCode();
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

                String responseBody = response.toString();

                JsonNode dataArray = mapper.readTree(responseBody).get("data");
                if (dataArray != null && dataArray.isArray() && !dataArray.isEmpty()) {
                    JsonNode firstElement = dataArray.get(0);
                    mainBalance.set(firstElement.get("main_balance").asLong());
                    log.info("User {}: The balance is: {}", getUserName(), mainBalance);
                } else {
                    throw new RuntimeException("User: " + getUserName() + ": Data array is missing or empty: " + responseBody);
                }
            } catch (IOException e) {
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
