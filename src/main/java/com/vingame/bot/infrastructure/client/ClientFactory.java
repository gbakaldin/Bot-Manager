package com.vingame.bot.infrastructure.client;

import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.encryption.EncryptionServiceImpl;
import com.vingame.websocketparser.message.AuthMessage;
import com.vingame.websocketparser.message.PingMessageImpl;
import io.netty.channel.EventLoopGroup;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
@Setter
public class ClientFactory {

    private URI uri;
    private Map<String, String> headers;
    private String zoneName;
    private boolean encryption;
    private String encryptionKey;
    private String encryptionIv;
    private EventLoopGroup eventLoopGroup;
    private List<String> authTokens; // [0] = auth token, [1] = agency token

    /**
     * Create a new WebSocket client with authentication configured.
     *
     * PREFERRED METHOD: Pass tokens directly to avoid race conditions.
     * Use this when multiple bots share the same ClientFactory instance.
     *
     * @param authTokens List of auth tokens [agencyToken, authToken] from ApiGatewayClient.authenticate()
     * @return Configured WebSocket client ready to connect
     */
    public VingameWebSocketClient newClient(List<String> authTokens) {
        if (authTokens == null || authTokens.size() < 2) {
            throw new IllegalArgumentException("Auth tokens required. Expected [agencyToken, authToken].");
        }

        String agencyToken = authTokens.get(0);
        String authToken = authTokens.get(1);

        log.info("TOKENS ARE agency: {} auth: {}", agencyToken, authToken);

        VingameWebSocketClient client = buildClient(agencyToken, authToken);
        log.info("ClientFactory created new client instance: {}", System.identityHashCode(client));
        return client;
    }

    /**
     * Create a new WebSocket client with authentication configured.
     *
     * DEPRECATED: Use newClient(List<String> authTokens) instead to avoid race conditions.
     * This method uses shared mutable state (setAuthTokens) which is unsafe when
     * multiple bots share the same ClientFactory instance.
     *
     * @return Configured WebSocket client ready to connect
     * @deprecated Use {@link #newClient(List)} to pass tokens directly
     */
    @Deprecated
    public VingameWebSocketClient newClient() {
        if (authTokens == null || authTokens.size() < 2) {
            throw new IllegalStateException("Auth tokens must be set before creating client. Expected [agencyToken, authToken].");
        }

        String agencyToken = authTokens.get(0);
        String authToken = authTokens.get(1);

        log.warn("DEPRECATED: Using newClient() without parameters. This is unsafe for shared factories. Use newClient(tokens) instead.");
        log.info("TOKENS ARE agency: {} auth: {}", agencyToken, authToken);

        return buildClient(agencyToken, authToken);
    }

    /**
     * Build the actual WebSocket client with the given tokens.
     * Shared implementation for both newClient() methods.
     */
    private VingameWebSocketClient buildClient(String agencyToken, String authToken) {
        return VingameWebSocketClient.builder()
                .agentId("1")
                .serverUri(uri)
                .httpHeaders(headers)
                .zoneName(zoneName)
                .authToken(authToken)
                .agencyToken(agencyToken) // Required for monetary operations (balance, deposits)
                .authMessage(AuthMessage.builder()
                        .zoneName(zoneName)
                        .accessToken(agencyToken)
                        .agentId("1")
                        .build())
                .pingMessage(new PingMessageImpl(zoneName))
                .pingFrequencyMillis(5000L)
                .then(builder -> {
                    // Set encryption if enabled
                    if (encryption && encryptionKey != null && encryptionIv != null) {
                        builder.encryption(EncryptionServiceImpl.builder()
                                .secretKey(encryptionKey)
                                .iv(encryptionIv)
                                .build());
                    }
                })
                .then(builder -> {
                    // Set shared EventLoopGroup if provided
                    if (eventLoopGroup != null) {
                        log.info("Setting shared EventLoopGroup on client: {}", System.identityHashCode(eventLoopGroup));
                        builder.eventLoopGroup(eventLoopGroup);
                    } else {
                        log.warn("EventLoopGroup is NULL! Each client will create its own EventLoopGroup.");
                    }
                })
                .build();
    }
}
