package com.vingame.bot.infrastructure.client;

import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.auth.TokensProvider;
import com.vingame.websocketparser.encryption.EncryptionServiceImpl;
import com.vingame.websocketparser.message.PingMessageImpl;
import io.netty.channel.EventLoopGroup;
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
    private String encryptionKey;
    private String encryptionIv;
    private EventLoopGroup eventLoopGroup;
    private TokensProvider tokens;
    private boolean ignoreJwtToken;

    /**
     * Create a new WebSocket client with authentication configured.
     *
     * PREFERRED METHOD: Pass tokens directly to avoid race conditions.
     * Use this when multiple bots share the same ClientFactory instance.
     *
     * @param tokens TokensProvider from ApiGatewayClient.authenticate()
     * @return Configured WebSocket client ready to connect
     */
    public VingameWebSocketClient newClient(TokensProvider tokens, String name) {
        if (tokens == null) {
            throw new IllegalArgumentException("TokensProvider is required.");
        }

        VingameWebSocketClient client = buildClient(tokens, name);
        log.debug("Created client {} for {}", client.getName(), name);
        return client;
    }

    /**
     * Create a new WebSocket client with authentication configured.
     *
     * DEPRECATED: Use newClient(TokensProvider) instead to avoid race conditions.
     * This method uses shared mutable state (setTokens) which is unsafe when
     * multiple bots share the same ClientFactory instance.
     *
     * @return Configured WebSocket client ready to connect
     * @deprecated Use {@link #newClient(TokensProvider)} to pass tokens directly
     */
    @Deprecated
    public VingameWebSocketClient newClient() {
        if (tokens == null) {
            throw new IllegalStateException("TokensProvider must be set before creating client.");
        }

        log.warn("DEPRECATED: Using newClient() without parameters. This is unsafe for shared factories. Use newClient(tokens, name) instead.");

        return buildClient(tokens, "unknown");
    }

    /**
     * Build the actual WebSocket client with the given tokens.
     * Shared implementation for both newClient() methods.
     */
    private VingameWebSocketClient buildClient(TokensProvider tokens, String name) {
        return VingameWebSocketClient.builder()
                .name("ws-" + name)
                .agentId("1")
                .serverUri(uri)
                .httpHeaders(headers)
                .zoneName(zoneName)
                .tokensProvider(() -> tokens)
                .pingMessage(new PingMessageImpl(zoneName))
                .pingFrequencyMillis(5000L)
                .then(builder -> {
                    if (ignoreJwtToken) {
                        builder.ignoreJwtToken();
                    }
                })
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
