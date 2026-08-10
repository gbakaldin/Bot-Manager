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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 * Dev's suite pins that a dead-channel client <em>is</em> closed at the four migrated sites.
 * This suite covers the three things that survey left open:
 * <ol>
 *   <li><b>Error tolerance.</b> Dropping the {@code isOpen()} guard means {@code close()} now
 *       runs on clients that were previously skipped, so it is newly reachable with a channel
 *       in an arbitrary state. {@code restart()} used to call a bare {@code client.close()}
 *       whose exception would propagate and abort the restart; it now routes through
 *       {@code closeQuietly}, which swallows. That is a behavior change and is pinned here.</li>
 *   <li><b>The periodic-logout path</b> ({@code BotGroupBehaviorService.performPeriodicLogout}:
 *       {@code logout()} → sleep → {@code restart()}), the one production sequence in which a
 *       single client instance is genuinely reachable from two close sites in a row. This is
 *       what {@code lastClosedClient} exists for.</li>
 *   <li><b>The fifth, un-migrated {@code isOpen()}-guarded close</b> in
 *       {@code triggerFullReconnect}, which Phase 1 deliberately left in place. Both halves of
 *       its behavior are pinned so the scope decision is visible and any later migration has
 *       to update these assertions on purpose.</li>
 * </ol>
 */
@DisplayName("Bot WS-client close accounting (Phase 1 follow-up)")
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
         * through {@code closeQuietly}, which logs at DEBUG and continues, so a client that
         * fails to tear down cannot cost the bot its restart.
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
         * {@code stop()} stamps {@code lastClosedClient} <em>before</em> calling
         * {@code close()}, so an instance whose teardown threw is treated as closed and is
         * never retried. That is correct against the real library rather than merely
         * convenient: {@code VingameWebSocketClient.close()} flips its one-shot guard
         * ({@code isClosing.getAndSet(true)}) as its very first statement, so any later throw
         * leaves nothing a second call could still do. Pinned because the reasoning is not
         * local to this file.
         */
        @Test
        @DisplayName("A client whose close() threw is not re-closed by a later terminal close")
        void clientThatFailedToCloseIsNotRetried() throws Exception {
            VingameWebSocketClient bad = deadChannelClient();
            doThrow(new RuntimeException("teardown blew up")).when(bad).close();
            bot.client = bad;

            bot.cleanup();
            invokeNoArg("closeClientQuietly");

            verify(bad, times(1)).close();
        }
    }

    @Nested
    @DisplayName("periodic logout: logout() then restart()")
    class PeriodicLogoutPath {

        /**
         * {@code performPeriodicLogout} is the one production caller that reaches a single
         * client instance from two close sites in sequence. Pre-Phase-1 the second site was a
         * no-op by accident (the client had just been closed, so {@code isOpen()} was false);
         * now it is a no-op on purpose, via {@code lastClosedClient}.
         */
        @Test
        @DisplayName("The logged-out client is closed exactly once, and the reconnect's client is not closed at all")
        void logoutThenRestartClosesOldClientExactlyOnce() {
            VingameWebSocketClient old = openClient();
            bot.client = old;

            VingameWebSocketClient fresh = openClient();
            when(clientFactory.newClient(any(), anyString())).thenReturn(fresh);

            bot.logout();
            bot.restart();

            verify(old, times(1)).close();
            assertThat(bot.getClient()).isSameAs(fresh);
            verify(fresh, never()).close();
            // logout() must NOT mark the bot stopped — the restart that follows depends on it.
            assertThat(bot.isStopped()).isFalse();
        }
    }

    @Nested
    @DisplayName("lastClosedClient is a single slot, so it can never skip a live reference")
    class SingleSlotDedup {

        /**
         * The safety argument for {@code lastClosedClient} is that it is a one-entry memo:
         * it can only ever suppress a close for the instance it is currently holding — an
         * instance that was, by construction, just closed. Closing a different client
         * overwrites the slot, so the older instance loses its suppression and would be
         * closed <em>again</em> rather than skipped. Double-close is harmless (library
         * one-shot); a missed close is the bug Phase 1 exists to prevent. This pins that the
         * failure mode falls on the harmless side.
         */
        @Test
        @DisplayName("Re-closing an evicted instance closes it again rather than skipping it")
        void evictedInstanceIsClosedAgainNotSkipped() throws Exception {
            VingameWebSocketClient a = deadChannelClient();
            VingameWebSocketClient b = deadChannelClient();

            invokeCloseQuietly(a);
            invokeCloseQuietly(a); // suppressed — a still occupies the slot
            verify(a, times(1)).close();

            invokeCloseQuietly(b); // evicts a from the slot
            invokeCloseQuietly(a); // no longer suppressed: closed again, never skipped

            verify(a, times(2)).close();
            verify(b, times(1)).close();
        }
    }

    @Nested
    @DisplayName("the fifth isOpen()-guarded close, left un-migrated in triggerFullReconnect")
    class UnmigratedTriggerFullReconnectSite {

        /**
         * Phase 1 migrated four close sites and left {@code Bot.triggerFullReconnect}'s
         * {@code if (client != null && client.isOpen()) client.close();} alone. This pins why
         * that is safe rather than merely untouched: the site itself skips the dead-channel
         * client, but the worker it spawns closes it unconditionally on every route out —
         * here via {@code performReauth}'s failure path, which calls
         * {@code closeClientQuietly()}. (The other route, {@code tryReconnectWs}, is covered
         * by {@code BotOrphanClientCloseTest#reconnectClosesDeadChannelClient}.) So no
         * reference is dropped un-closed; only the accounting is inconsistent.
         */
        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("A dead-channel client the site skips is still closed by the worker it spawns")
        void deadChannelClientSkippedBySiteIsClosedByTheSpawnedWorker() {
            VingameWebSocketClient dead = deadChannelClient();
            bot.client = dead;

            // Terminate the spawned runAuthThenWsLoop immediately and deterministically:
            // performReauth throws, marks DEAD, and closes the client on its way out.
            when(apiGatewayClient.authenticate(any()))
                    .thenThrow(new RuntimeException("auth gateway down"));

            bot.triggerFullReconnect("watchdog: no messages");

            verify(dead, timeout(5000).times(1)).close();
        }

        /**
         * The other half of leaving the fifth site alone: when the channel <em>is</em> open,
         * the site closes the client with a bare {@code client.close()} that does not stamp
         * {@code lastClosedClient}, so the very next close site sees an unrecorded instance
         * and closes it a second time. Harmless — the library's {@code close()} is one-shot —
         * but it means the "exactly one close() per instance" invariant the new field was
         * added to keep does not actually hold on the watchdog path.
         * <p>
         * <b>If the fifth site is later migrated to {@code closeQuietly}, this expectation
         * becomes {@code times(1)} and this test must be updated deliberately.</b>
         */
        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("An open client is closed twice: the site's bare close() bypasses the accounting")
        void openClientIsClosedTwiceBecauseSiteBypassesAccounting() {
            VingameWebSocketClient open = openClient();
            bot.client = open;

            when(apiGatewayClient.authenticate(any()))
                    .thenThrow(new RuntimeException("auth gateway down"));

            bot.triggerFullReconnect("watchdog: no messages");

            verify(open, timeout(5000).times(2)).close();
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
