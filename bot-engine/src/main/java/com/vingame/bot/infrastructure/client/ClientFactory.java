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
     * Build the actual WebSocket client with the given tokens.
     * Shared implementation for the newClient methods.
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
                // ws-parser 3.0.x defaults awaitServerReady=true, which blocks connect()
                // for up to 30s waiting on a hardcoded AUTH-ACK ([1,true...]) + cmd:100
                // ready-push. Our game servers don't emit that exact handshake, so every
                // bot times out. We send AUTH via the scenario and don't need the library's
                // blocking readiness gate — opt out to restore the 2.3.10 connect semantics.
                // (The 3.0.5 thread-leak fix — virtualized executors + self-sufficient
                // close() — is independent of this flag and stays in effect.)
                .awaitServerReady(false)
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
                        // DEBUG, not INFO. newClient() is called from Bot.initialize,
                        // Bot.restart() and the re-auth path, so at INFO this fired once per
                        // bot at start, once per bot per periodic-logout cycle and once per
                        // reconnect — it cancelled out the demotion of `restart requested`
                        // three frames earlier and, at the observed prod reconnect rates, was
                        // the largest INFO class left in the app (LOG_VOLUME_TIERING tier 1).
                        // The identity hash is constant for the life of the EventLoopGroup, so
                        // the diagnostic ("is every client really sharing one?") is preserved
                        // by the one-shot INFO line in NettyEventLoopConfig, which prints the
                        // same hash once per JVM.
                        log.debug("Setting shared EventLoopGroup on client: {}", System.identityHashCode(eventLoopGroup));
                        builder.eventLoopGroup(eventLoopGroup);
                    } else {
                        log.warn("EventLoopGroup is NULL! Each client will create its own EventLoopGroup.");
                    }
                })
                .build();
    }
}
