package com.vingame.bot.brands.bom;

import com.vingame.webocketparser.VingameWebsocketClient;
import com.vingame.webocketparser.encryption.EncryptionServiceImpl;
import com.vingame.webocketparser.message.PingMessage;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.Map;

@Slf4j
@Setter
public class ClientFactory {

    private URI uri;
    private Map<String, String> headers;
    private String zoneName;
    private boolean encryption;

    public VingameWebsocketClient newClient() {
        return VingameWebsocketClient.builder()
                .agentId("1")
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
                .build();
    }
}
