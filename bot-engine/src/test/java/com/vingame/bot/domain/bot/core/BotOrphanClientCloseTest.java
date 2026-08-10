package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.auth.TokensProvider;
import com.vingame.websocketparser.scenario.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orphan-client regression suite (BOT_LIVENESS_SEMANTICS Phase 1, AD-6).
 * <p>
 * Every close site in {@link Bot} used to be guarded on {@code client.isOpen()}, which is
 * {@code isConnected && channel != null && channel.isActive()} in the library. A client whose
 * channel had died therefore reported {@code isOpen() == false} and was <b>never</b> closed —
 * while {@code tryReconnectWs()} immediately overwrote {@code this.client} with a fresh
 * instance, dropping the reference to an object whose {@code sendAsync} pipeline was still
 * scheduled and still closed over the live bot's fields. The orphan kept evaluating the bot's
 * bet condition and calling {@code send(...)}, and the library logged
 * {@code "Client ws-<name>: Cannot send message, not connected"} once per bet window, forever.
 * That is the mechanism behind the sustained staging log flood.
 * <p>
 * The library's {@code close()} in 3.0.5 is one-shot, idempotent and self-sufficient — it
 * shuts scenarios (and with them every {@code SendAsync} scheduler), the ping scheduler, the
 * channel and the message-processor pool, and it runs even for a client that never connected.
 * So the fix is to drop the guard everywhere. Every test below therefore uses a client mock
 * whose {@code isOpen()} is {@code false} — the exact state the old guard skipped.
 */
@DisplayName("Bot orphan-client close (dead channel must still be closed)")
class BotOrphanClientCloseTest {

    private ApiGatewayClient apiGatewayClient;
    private GameMsClient gameMsClient;
    private ClientFactory clientFactory;
    private TokensProvider tokens;

    private TestBot bot;

    /** A client whose channel has died: connected once, not open now. Never closed pre-fix. */
    private VingameWebSocketClient deadChannelClient;

    @BeforeEach
    void setUp() {
        apiGatewayClient = mock(ApiGatewayClient.class);
        gameMsClient = mock(GameMsClient.class);
        clientFactory = mock(ClientFactory.class);
        tokens = mock(TokensProvider.class);

        when(apiGatewayClient.getApiGateway()).thenReturn("http://gateway.test");

        BotCredentials credentials = BotCredentials.builder()
                .username("bot1").password("pw").fingerprint("fp").build();
        Game game = Game.builder().id("g1").name("G").pluginName("Plugin")
                .offset(2000).numberOfOptions(6).build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(credentials)
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).zoneName("Z").timeoutMillis(1000L)
                .watchdogTimeoutSeconds(120L)
                .build();

        bot = new TestBot();
        bot.setClients(apiGatewayClient, gameMsClient, clientFactory);
        bot.setConfiguration(cfg);

        deadChannelClient = mock(VingameWebSocketClient.class);
        when(deadChannelClient.isOpen()).thenReturn(false);
    }

    @Nested
    @DisplayName("close happens even though isOpen() is false")
    class ClosesDeadChannelClient {

        @Test
        @DisplayName("tryReconnectWs closes the outgoing dead-channel client exactly once before replacing it")
        void reconnectClosesDeadChannelClient() throws Exception {
            bot.client = deadChannelClient;

            VingameWebSocketClient fresh = mock(VingameWebSocketClient.class);
            when(fresh.isOpen()).thenReturn(true);
            when(clientFactory.newClient(any(), eq("bot1"))).thenReturn(fresh);

            boolean reconnected = invokeTryReconnectWs();

            assertThat(reconnected).isTrue();
            // Pre-fix this was times(0) — the orphan the flood came from.
            verify(deadChannelClient, times(1)).close();
            assertThat(bot.getClient()).isSameAs(fresh);
            verify(fresh, never()).close();
        }

        @Test
        @DisplayName("restart closes the outgoing dead-channel client exactly once before replacing it")
        void restartClosesDeadChannelClient() {
            bot.client = deadChannelClient;

            VingameWebSocketClient fresh = mock(VingameWebSocketClient.class);
            when(fresh.isOpen()).thenReturn(true);
            when(clientFactory.newClient(any(), eq("bot1"))).thenReturn(fresh);

            bot.restart();

            verify(deadChannelClient, times(1)).close();
            assertThat(bot.getClient()).isSameAs(fresh);
            verify(fresh, never()).close();
        }

        @Test
        @DisplayName("cleanup closes a dead-channel client exactly once")
        void cleanupClosesDeadChannelClient() {
            bot.client = deadChannelClient;

            bot.cleanup();

            assertThat(bot.isStopped()).isTrue();
            verify(deadChannelClient, times(1)).close();
        }

        @Test
        @DisplayName("cleanup on a bot that never got a client is a no-op, not an NPE")
        void cleanupWithoutClientDoesNotThrow() {
            bot.client = null;

            bot.cleanup();

            assertThat(bot.isStopped()).isTrue();
        }

        @Test
        @DisplayName("closeClientQuietly (terminal path) closes a dead-channel client exactly once")
        void terminalPathClosesDeadChannelClient() throws Exception {
            bot.client = deadChannelClient;

            invokePrivate("closeClientQuietly");

            verify(deadChannelClient, times(1)).close();
        }
    }

    @Nested
    @DisplayName("exactly one close per client instance")
    class OneClosePerInstance {

        @Test
        @DisplayName("A second reconnect closes only the client of that round — never the already-closed one again")
        void secondReconnectDoesNotRecloseTheFirstClient() throws Exception {
            bot.client = deadChannelClient;

            VingameWebSocketClient first = mock(VingameWebSocketClient.class);
            when(first.isOpen()).thenReturn(false);
            VingameWebSocketClient second = mock(VingameWebSocketClient.class);
            when(second.isOpen()).thenReturn(true);
            when(clientFactory.newClient(any(), anyString())).thenReturn(first, second);

            invokeTryReconnectWs();
            invokeTryReconnectWs();

            verify(deadChannelClient, times(1)).close();
            verify(first, times(1)).close();
            verify(second, never()).close();
            assertThat(bot.getClient()).isSameAs(second);
        }

        @Test
        @DisplayName("A failed attempt closes the client it just built, and the next attempt does not close it again")
        void failedAttemptClosesItsOwnClientOnceOnly() throws Exception {
            bot.client = deadChannelClient;

            // Half-built client: the library constructor has already started its
            // message-processor workers, so a client that throws on connect() still owns
            // resources and still has to be closed.
            VingameWebSocketClient halfBuilt = mock(VingameWebSocketClient.class);
            when(halfBuilt.isOpen()).thenReturn(false);
            doThrow(new RuntimeException("ws down")).when(halfBuilt).connect();

            VingameWebSocketClient next = mock(VingameWebSocketClient.class);
            when(next.isOpen()).thenReturn(true);
            when(clientFactory.newClient(any(), anyString())).thenReturn(halfBuilt, next);

            assertThat(invokeTryReconnectWs()).isFalse();
            verify(halfBuilt, times(1)).close();

            assertThat(invokeTryReconnectWs()).isTrue();
            // Still exactly one — the retry must not double-close what the failure path closed.
            verify(halfBuilt, times(1)).close();
            verify(deadChannelClient, times(1)).close();
            verify(next, never()).close();
        }

        @Test
        @DisplayName("A client torn down by cleanup is not closed a second time by a later terminal close")
        void cleanupThenTerminalCloseClosesOnce() throws Exception {
            bot.client = deadChannelClient;

            bot.cleanup();
            invokePrivate("closeClientQuietly");

            verify(deadChannelClient, times(1)).close();
        }
    }

    /* ----- helpers ----- */

    private boolean invokeTryReconnectWs() throws Exception {
        Method m = Bot.class.getDeclaredMethod("tryReconnectWs");
        m.setAccessible(true);
        try {
            return (boolean) m.invoke(bot);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    private void invokePrivate(String name) throws Exception {
        Method m = Bot.class.getDeclaredMethod(name);
        m.setAccessible(true);
        try {
            m.invoke(bot);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    static class TestBot extends Bot {
        @Override protected void sleep(long millis) { /* no real sleeping in tests */ }
        @Override protected void initializeSubclass() {}
        @Override protected Scenario botBehaviorScenario() { return null; }
        @Override protected void onStart() {}
    }
}
