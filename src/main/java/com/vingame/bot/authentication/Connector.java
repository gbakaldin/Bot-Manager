package com.vingame.bot.authentication;

import com.vingame.webocketparser.VingameWebsocketClient;
import com.vingame.webocketparser.encryption.EncryptionServiceImpl;
import com.vingame.webocketparser.message.PingMessage;
import com.vingame.webocketparser.scenario.Scenario;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Setter
public class Connector {

    private URI uri;
    private Map<String, String> headers;
    private List<Scenario> scenarios;
    private String zoneName;
    private String agencyToken;
    private String authToken;
    private boolean encryption;
    private Supplier<List<String>> accessTokenSupplier;

    public VingameWebsocketClient newConnection() {
        if (agencyToken == null) {
            if (accessTokenSupplier == null) {
                throw new RuntimeException("No means of obtaining the access token provided");
            }

            List<String> tokens = accessTokenSupplier.get();
            agencyToken = tokens.get(0);
            authToken = tokens.get(1);
        }

        VingameWebsocketClient client = VingameWebsocketClient.builder()
                .agentId("1")
                .agencyToken(agencyToken)
                .authToken(authToken)
                .then(builder -> {
                    if (encryption) {
                        builder.encryption(EncryptionServiceImpl.builder()
                                .secretKey(EncryptionServiceImpl.DEFAULT_SECRET_KEY)
                                .iv(EncryptionServiceImpl.DEFAULT_IV)
                                .build());
                    }
                })
                .zoneName(zoneName)
                .pingMessage(new PingMessage(zoneName))
                .pingFrequencyMillis(5000L)
                .serverUri(uri)
                .httpHeaders(headers)
                .then(builder -> {
                    if (scenarios == null) {
                        return;
                    }
                    for (Scenario scenario : scenarios) {
                        builder.addScenario(scenario);
                    }
                })
                .build();
        client.connect();

        return client;
    }
}
