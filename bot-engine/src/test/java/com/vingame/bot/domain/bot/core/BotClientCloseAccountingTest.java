package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.scenario.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA companion to {@link BotOrphanClientCloseTest} (BOT_LIVENESS_SEMANTICS Phase 1, AD-6).
 * <p>
 * Dev's suite pins that a dead-channel client <em>is</em> closed at every close site. This
 * suite covers what that survey left open:
 * <ol>
 *   <li><b>Error tolerance.</b> Dropping the {@code isOpen()} guard means {@code close()} now
 *       runs on clients that were previously skipped, so it is newly reachable with a channel
 *       in an arbitrary state. {@code restart()} used to call a bare {@code client.close()}
 *       whose exception would propagate and abort the restart; it now routes through
 *       {@code closeQuietly}, which logs at WARN with the throwable and continues. That is a
 *       behavior change and is pinned here.</li>
 *   <li><b>Redundant closes are harmless, missed closes are the bug.</b> With no per-instance
 *       accounting, a single instance reachable from two close sites in a row is simply closed
 *       twice; the library's {@code close()} is one-shot ({@code isClosing.getAndSet(true)}),
 *       so the second call is a no-op. The tests below assert reachability of the close, not a
 *       call count — the property with runtime meaning.</li>
 *   <li><b>The periodic-logout path</b> ({@code BotGroupBehaviorService.performPeriodicLogout}:
 *       {@code logout()} → sleep → {@code restart()}), the one production sequence in which a
 *       single client instance is genuinely reachable from two close sites in a row.</li>
 *   <li><b>{@code triggerFullReconnect}</b>, the fifth close site, migrated to
 *       {@code closeQuietly} in the review follow-up. The point of migrating it was promptness:
 *       the old guard deferred the close of a dead-channel client until {@code performReauth()}
 *       returned — an auth-gateway round trip that is the slow or hanging one in the failure
 *       mode this plan targets — while the orphan kept flooding. That timing is pinned.</li>
 * </ol>
 */
@DisplayName("Bot WS-client close behavior (Phase 1 follow-up)")
class BotClientCloseAccountingTest {

    private ApiGatewayClient apiGatewayClient;
    private ClientFactory clientFactory;

    private TestBot bot;

    @BeforeEach
    void setUp() {
        apiGatewayClient = mock(ApiGatewayClient.class);
        GameMsClient gameMsClient = mock(GameMsClient.class);
        clientFactory = mock(ClientFactory.class);

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
    }

    private VingameWebSocketClient deadChannelClient() {
        VingameWebSocketClient c = mock(VingameWebSocketClient.class);
        when(c.isOpen()).thenReturn(false);
        return c;
    }

    private VingameWebSocketClient openClient() {
        VingameWebSocketClient c = mock(VingameWebSocketClient.class);
        when(c.isOpen()).thenReturn(true);
        return c;
    }

    @Nested
    @DisplayName("a throwing close() never blocks the caller's own job")
    class CloseErrorsAreSwallowed {

        /**
         * Pre-Phase-1 {@code restart()} was {@code if (client != null && client.isOpen())
         * client.close();} — an unguarded call whose exception propagated out of
         * {@code restart()} and left the bot with no fresh client at all. Now the close goes
         * through {@code closeQuietly}, which logs and continues, so a client that fails to
         * tear down cannot cost the bot its restart.
         */
        @Test
        @DisplayName("restart survives a close() that throws and still builds, connects and starts a fresh client")
        void restartSwallowsThrowingCloseAndStillRebuilds() {
            VingameWebSocketClient bad = deadChannelClient();
            doThrow(new RuntimeException("teardown blew up")).when(bad).close();
            bot.client = bad;

            VingameWebSocketClient fresh = openClient();
            when(clientFactory.newClient(any(), anyString())).thenReturn(fresh);

            assertThatCode(() -> bot.restart()).doesNotThrowAnyException();

            verify(bad, times(1)).close();
            assertThat(bot.getClient()).isSameAs(fresh);
            verify(fresh, times(1)).connect();
            verify(fresh, never()).close();
        }

        @Test
        @DisplayName("cleanup survives a close() that throws and still marks the bot stopped")
        void cleanupSwallowsThrowingCloseAndStaysStopped() {
            VingameWebSocketClient bad = deadChannelClient();
            doThrow(new RuntimeException("teardown blew up")).when(bad).close();
            bot.client = bad;

            assertThatCode(() -> bot.cleanup()).doesNotThrowAnyException();

            assertThat(bot.isStopped()).isTrue();
            verify(bad, times(1)).close();
        }

        /**
         * A client whose teardown threw stays reachable as {@code this.client}, so a later
         * close site calls {@code close()} on it again — and that second throw is swallowed
         * too. Retrying is dead weight rather than a repair ({@code close()} flips its
         * one-shot guard as its very first statement, so a throw after that leaves nothing a
         * second call could still do), but it must never escape: {@code closeClientQuietly}
         * is on the terminal DEAD path and {@code cleanup} is on the group-teardown loop.
         */
        @Test
        @DisplayName("A client whose close() threw is re-closed harmlessly by a later terminal close")
        void clientThatFailedToCloseIsRetriedHarmlessly() throws Exception {
            VingameWebSocketClient bad = deadChannelClient();
            doThrow(new RuntimeException("teardown blew up")).when(bad).close();
            bot.client = bad;

            bot.cleanup();
            assertThatCode(() -> invokeNoArg("closeClientQuietly")).doesNotThrowAnyException();

            verify(bad, times(2)).close();
        }
    }

    @Nested
    @DisplayName("periodic logout: logout() then restart()")
    class PeriodicLogoutPath {

        /**
         * {@code performPeriodicLogout} is the one production caller that reaches a single
         * client instance from two close sites in sequence ({@code logout()} → {@code stop()},
         * then {@code restart()} → {@code closeQuietly}). The load-bearing assertions are that
         * the old client is closed at all, that the reconnect's client is untouched, and that
         * the bot is not left stopped — not how many times the redundant close ran.
         */
        @Test
        @DisplayName("The logged-out client is closed, the reconnect's client is not, and the bot stays runnable")
        void logoutThenRestartClosesOldClient() {
            VingameWebSocketClient old = openClient();
            bot.client = old;

            VingameWebSocketClient fresh = openClient();
            when(clientFactory.newClient(any(), anyString())).thenReturn(fresh);

            bot.logout();
            bot.restart();

            verify(old, atLeastOnce()).close();
            assertThat(bot.getClient()).isSameAs(fresh);
            verify(fresh, never()).close();
            // logout() must NOT mark the bot stopped — the restart that follows depends on it.
            assertThat(bot.isStopped()).isFalse();
        }
    }

    @Nested
    @DisplayName("closeQuietly is unconditional: no isOpen() guard, no per-instance memo")
    class CloseQuietlyIsUnconditional {

        /**
         * The helper deliberately carries no state beyond the null check. An earlier revision
         * memoised the last-closed instance to keep a "one close() per instance" count; the
         * review removed it — it re-introduced a conditional close (the same shape as the
         * {@code isOpen()} guard that caused the incident, differing only in the predicate),
         * it was a non-atomic read-modify-write on a field touched by the Netty I/O loop, the
         * watchdog, the countdown scheduler and the reconnect virtual threads, and it retained
         * a hard reference to the very client being orphan-proofed. Since the library's
         * {@code close()} is one-shot, the memo bought nothing at runtime. This pins that the
         * helper now always attempts the close.
         */
        @Test
        @DisplayName("Closing the same instance twice calls close() twice — redundant, never skipped")
        void repeatedCloseIsNotSuppressed() throws Exception {
            VingameWebSocketClient a = deadChannelClient();
            VingameWebSocketClient b = deadChannelClient();

            invokeCloseQuietly(a);
            invokeCloseQuietly(a);
            invokeCloseQuietly(b);
            invokeCloseQuietly(a);

            verify(a, times(3)).close();
            verify(b, times(1)).close();
        }

        @Test
        @DisplayName("A null client is a no-op, not an NPE")
        void nullClientIsANoOp() {
            assertThatCode(() -> invokeCloseQuietly(null)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("triggerFullReconnect closes promptly, not after performReauth")
    class TriggerFullReconnectSite {

        /**
         * The fifth close site used to be {@code if (client != null && client.isOpen())
         * client.close();}, so a dead-channel client — the state that provokes a reconnect in
         * the first place — was skipped there and only closed downstream, after
         * {@code performReauth()} had returned. For that whole window the orphan's
         * {@code sendAsync} pipeline stayed scheduled and kept emitting
         * {@code "Cannot send message, not connected"}, which is precisely what Phase 1 exists
         * to stop. Now the site closes it directly.
         * <p>
         * The latch makes the ordering assertable rather than assumed: {@code authenticate}
         * blocks, and the close must already have happened while it is still in flight.
         */
        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("The client is closed before performReauth's gateway call returns")
        void closesBeforeReauthCompletes() throws Exception {
            VingameWebSocketClient dead = deadChannelClient();
            bot.client = dead;

            CountDownLatch reauthEntered = new CountDownLatch(1);
            CountDownLatch releaseReauth = new CountDownLatch(1);
            when(apiGatewayClient.authenticate(any())).thenAnswer(inv -> {
                reauthEntered.countDown();
                releaseReauth.await(10, TimeUnit.SECONDS);
                throw new RuntimeException("auth gateway down");
            });

            bot.triggerFullReconnect("watchdog: no messages");

            assertThat(reauthEntered.await(5, TimeUnit.SECONDS)).isTrue();
            // Still inside the (hanging) gateway call — the close must already have happened.
            verify(dead, timeout(5000).atLeastOnce()).close();

            releaseReauth.countDown();
        }

        /**
         * An open channel takes the same path: the site no longer inspects {@code isOpen()} at
         * all, so both the open and the dead-channel case are closed by the site itself.
         */
        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("An open client is closed by the site too, without waiting for the worker")
        void openClientIsAlsoClosedBySite() {
            VingameWebSocketClient open = openClient();
            bot.client = open;

            when(apiGatewayClient.authenticate(any()))
                    .thenThrow(new RuntimeException("auth gateway down"));

            bot.triggerFullReconnect("watchdog: no messages");

            verify(open, atLeastOnce()).close();
        }
    }

    /* ----- helpers ----- */

    private void invokeNoArg(String name) throws Exception {
        Method m = Bot.class.getDeclaredMethod(name);
        m.setAccessible(true);
        invoke(m);
    }

    private void invokeCloseQuietly(VingameWebSocketClient c) throws Exception {
        Method m = Bot.class.getDeclaredMethod("closeQuietly", VingameWebSocketClient.class);
        m.setAccessible(true);
        try {
            m.invoke(bot, c);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    private void invoke(Method m) throws Exception {
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
