package com.vingame.bot.infrastructure.client;

import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.auth.TokensProvider;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-11</b>: for a bot of an isolated plugin bundle the shared Netty
 * group is mandatory. Without it ws-parser falls back to a private
 * {@code MultiThreadIoEventLoopGroup} per client ({@code VingameWebSocketClient:400}), whose
 * platform threads start lazily — possibly under a plugin frame, pinning the plugin loader
 * for their whole life (spike 7b). {@code BotFactory} sets
 * {@code requireSharedEventLoopGroup} from the bundle; with it set, a missing group is an
 * {@link IllegalStateException} when the client is built, so the fallback is unreachable.
 * Classpath mode keeps the old WARN-only behaviour.
 */
@DisplayName("ClientFactory — the shared EventLoopGroup is mandatory for isolated-bundle bots (L-11)")
class ClientFactoryRequiresGroupTest {

    private static ClientFactory factory() {
        ClientFactory factory = new ClientFactory();
        factory.setUri(URI.create("ws://127.0.0.1:1/websocket"));
        factory.setZoneName("MiniGame");
        return factory;
    }

    private static TokensProvider tokens() {
        return TokensProvider.of("18-agency-0123456789", "session-0123456789", null);
    }

    @Test
    @DisplayName("required and missing: building the client fails, naming the rule")
    void requiredAndMissingFails() {
        ClientFactory factory = factory();
        factory.setRequireSharedEventLoopGroup(true);

        assertThatThrownBy(() -> factory.newClient(tokens(), "bot1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no shared EventLoopGroup")
                .hasMessageContaining("L-11");
    }

    @Test
    @DisplayName("required and present: the client builds")
    void requiredAndPresentBuilds() {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            ClientFactory factory = factory();
            factory.setRequireSharedEventLoopGroup(true);
            factory.setEventLoopGroup(group);

            VingameWebSocketClient client = factory.newClient(tokens(), "bot1");
            assertThat(client).isNotNull();
            closeQuietly(client);
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    @DisplayName("not required (classpath mode, fixtures): a missing group is still only a WARN")
    void notRequiredKeepsTheOldBehaviour() {
        VingameWebSocketClient client = factory().newClient(tokens(), "bot1");
        assertThat(client).isNotNull();
        closeQuietly(client);
    }

    private static void closeQuietly(VingameWebSocketClient client) {
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // best effort: a never-connected client
        }
    }
}
