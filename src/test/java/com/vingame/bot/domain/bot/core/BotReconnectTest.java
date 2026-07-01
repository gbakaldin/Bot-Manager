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
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Bot reconnect & backoff")
class BotReconnectTest {

    private ApiGatewayClient apiGatewayClient;
    private GameMsClient gameMsClient;
    private ClientFactory clientFactory;
    private VingameWebSocketClient wsClient;
    private TokensProvider tokens;

    private FastBot bot;

    @BeforeEach
    void setUp() {
        apiGatewayClient = mock(ApiGatewayClient.class);
        gameMsClient = mock(GameMsClient.class);
        clientFactory = mock(ClientFactory.class);
        wsClient = mock(VingameWebSocketClient.class);
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

        bot = new FastBot();
        bot.setClients(apiGatewayClient, gameMsClient, clientFactory);
        bot.setConfiguration(cfg);
    }

    /* ----- triggerFullReconnect ----- */

    @Nested
    @DisplayName("triggerFullReconnect")
    class TriggerFullReconnectTests {

        @Test
        @DisplayName("Is idempotent — second call short-circuits when reconnecting flag is set")
        void shouldShortCircuitWhenAlreadyReconnecting() throws Exception {
            // Manually flip the reconnecting flag so the next triggerFullReconnect short-circuits.
            setReconnecting(bot, true);

            // Avoid spawning a virtual thread by leaving status non-RECONNECTING; observe no transition.
            BotStatus before = bot.getStatus();
            bot.triggerFullReconnect("second call");

            assertThat(bot.getStatus()).isEqualTo(before); // no transition happened
            // No client closure
            verify(wsClient, never()).close();
        }

        @Test
        @DisplayName("No-ops when bot is stopped")
        void shouldNoOpWhenStopped() throws Exception {
            bot.client = wsClient; // attach a client so we can verify close() not called
            when(wsClient.isOpen()).thenReturn(true);

            bot.cleanup(); // sets stopped=true
            BotStatus afterCleanup = bot.getStatus();

            bot.triggerFullReconnect("reason after stop");

            assertThat(bot.getStatus()).isEqualTo(afterCleanup);
            assertThat(getReconnecting(bot)).isFalse();
            // cleanup() may have called close() once — verify no additional close from triggerFullReconnect
            verify(wsClient, times(1)).close();
        }
    }

    /* ----- runWsReconnectLoop ----- */

    @Nested
    @DisplayName("runWsReconnectLoop")
    class RunWsReconnectLoopTests {

        @Test
        @DisplayName("Success on attempt 1: one backoff (5000), one confirm (3000), reconnecting flag clears")
        void shouldSucceedOnFirstAttempt() throws Exception {
            setReconnecting(bot, true); // simulate already in reconnect

            VingameWebSocketClient newClient = mock(VingameWebSocketClient.class);
            when(newClient.isOpen()).thenReturn(true);
            when(clientFactory.newClient(any(), eq("bot1"))).thenReturn(newClient);
            // tokens may be null since we never authenticated — that's fine; newClient mock doesn't care

            invokePrivate("runWsReconnectLoop");

            assertThat(bot.sleeps).containsExactly(5000L, 3000L);
            assertThat(getReconnecting(bot)).isFalse();
            // start() set status to STARTED at the end of tryReconnectWs
            assertThat(bot.getStatus()).isEqualTo(BotStatus.STARTED);
        }

        @Test
        @DisplayName("After 7 failed attempts, calls performReauth; if stopped afterwards, loop exits cleanly")
        void shouldExhaustBackoffThenReauth() throws Exception {
            setReconnecting(bot, true);

            // newClient throws every time -> tryReconnectWs returns false
            when(clientFactory.newClient(any(), anyString()))
                    .thenThrow(new RuntimeException("ws down"));

            // After re-auth returns, we stop the bot to break the loop
            when(apiGatewayClient.authenticate(any())).thenAnswer(inv -> {
                // After this call, mark stopped so loop exits
                Field stoppedF = Bot.class.getDeclaredField("stopped");
                stoppedF.setAccessible(true);
                stoppedF.set(bot, true);
                return tokens;
            });

            invokePrivate("runWsReconnectLoop");

            // Full backoff sequence sleeps (no confirm sleep since tryReconnectWs never returned true)
            assertThat(bot.sleeps).containsExactly(5000L, 10_000L, 30_000L, 60_000L, 60_000L, 60_000L, 60_000L);
            verify(apiGatewayClient, times(1)).authenticate(any());
        }
    }

    /* ----- absolute reconnect-cycle cap (RESILIENCE_HARDENING P1, Gap A) ----- */

    @Nested
    @DisplayName("absolute reconnect-cycle cap")
    class ReconnectCycleCapTests {

        /**
         * The 2026-06-30 outage's terminating condition — {@code performReauth()}
         * marking the bot DEAD when re-auth <i>throws</i> — does NOT cover the case
         * where re-auth keeps <b>succeeding</b> but the WS keeps dropping (server-side
         * subscriber pruning). Before the {@code MAX_RECONNECT_CYCLES} cap the loop
         * reset {@code attempt = 0} after every re-auth and retried forever. This test
         * pins convergence to DEAD.
         */
        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("WS always fails but re-auth always succeeds: bot reaches DEAD, does not loop forever")
        void shouldGiveUpAndDieWhenWsNeverHoldsButReauthSucceeds() throws Exception {
            setReconnecting(bot, true);

            // Re-auth always succeeds — the "account is fine" case the DEAD-on-throw
            // path never terminates.
            when(apiGatewayClient.authenticate(any())).thenReturn(tokens);

            // Every fresh client comes up but never holds — models a WS subchannel that
            // is silently pruned right after connect. tryReconnectWs() returns true (no
            // throw), but the post-connect isOpen() check fails, so no attempt ever
            // sticks and the loop marches through its cycles.
            VingameWebSocketClient closedClient = mock(VingameWebSocketClient.class);
            when(closedClient.isOpen()).thenReturn(false);
            when(clientFactory.newClient(any(), anyString())).thenReturn(closedClient);

            // @Timeout guards against the pre-fix infinite loop; a hang fails the test.
            invokePrivate("runWsReconnectLoop");

            // Converged to terminal DEAD and cleared the guard so the group circuit
            // breaker (deadBotGroupThreshold) can count it — closing Gap A and Gap B.
            assertThat(bot.getStatus()).isEqualTo(BotStatus.DEAD);
            assertThat(getReconnecting(bot)).isFalse();

            // MAX_RECONNECT_CYCLES = 10: cycles 1..9 re-authenticate; cycle 10 gives up
            // BEFORE re-auth. So authenticate() ran exactly 9 times — proving it is the
            // cap, not an auth failure, that terminated the loop.
            verify(apiGatewayClient, times(9)).authenticate(any());
            verify(apiGatewayClient, atLeastOnce()).authenticate(any());
        }

        /**
         * The watchdog routes through {@link Bot#triggerFullReconnect} →
         * {@code runAuthThenWsLoop}, which performs one immediate re-auth and then
         * tail-calls the worker with {@code startCycle = 1}. If that initial re-auth
         * did NOT count as a cycle (i.e. the loop re-entered at cycle 0) the bot would
         * get 11 total cycles via this route — silently bypassing the cap. This pins
         * that the watchdog path still converges to DEAD after exactly the same budget:
         * one initial re-auth + eight in-loop re-auths (cycles 2..9) = 9 authenticate
         * calls, cycle 10 gives up before re-auth. A cycle-0 re-entry bug would show 10.
         */
        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("Watchdog re-entry (runAuthThenWsLoop) cannot bypass the cap: DEAD, initial re-auth counts as cycle 1")
        void watchdogReentryCannotBypassCap() throws Exception {
            setReconnecting(bot, true);

            when(apiGatewayClient.authenticate(any())).thenReturn(tokens);

            VingameWebSocketClient closedClient = mock(VingameWebSocketClient.class);
            when(closedClient.isOpen()).thenReturn(false);
            when(clientFactory.newClient(any(), anyString())).thenReturn(closedClient);

            // @Timeout guards against a cap that fails to terminate on this route.
            invokePrivate("runAuthThenWsLoop");

            assertThat(bot.getStatus()).isEqualTo(BotStatus.DEAD);
            assertThat(getReconnecting(bot)).isFalse();
            // 1 initial re-auth (runAuthThenWsLoop) + 8 in-loop (cycles 2..9) = 9.
            // If runAuthThenWsLoop entered the worker at cycle=0 this would be 10.
            verify(apiGatewayClient, times(9)).authenticate(any());
        }

        /**
         * The cap must not prematurely kill a bot that recovers. WS fails a full
         * back-off round (7 attempts) and one re-auth cycle, then the next attempt's
         * client holds — the loop must reset and return cleanly with the guard cleared,
         * never reaching DEAD.
         */
        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("Recovers within the cap: WS holds after one failed cycle — bot is NOT marked DEAD")
        void shouldRecoverWithinCapWithoutDying() throws Exception {
            setReconnecting(bot, true);

            when(apiGatewayClient.authenticate(any())).thenReturn(tokens);

            VingameWebSocketClient closed = mock(VingameWebSocketClient.class);
            when(closed.isOpen()).thenReturn(false);
            VingameWebSocketClient open = mock(VingameWebSocketClient.class);
            when(open.isOpen()).thenReturn(true);

            // First 7 attempts (one full back-off round) never hold; after the single
            // re-auth the 8th attempt's client holds → recovery well within the cap.
            when(clientFactory.newClient(any(), anyString()))
                    .thenReturn(closed, closed, closed, closed, closed, closed, closed, open);

            invokePrivate("runWsReconnectLoop");

            assertThat(bot.getStatus()).isNotEqualTo(BotStatus.DEAD);
            assertThat(getReconnecting(bot)).isFalse();
            // Exactly one re-auth cycle consumed — far below MAX_RECONNECT_CYCLES.
            verify(apiGatewayClient, times(1)).authenticate(any());
        }
    }

    /* ----- performReauth ----- */

    @Nested
    @DisplayName("performReauth")
    class PerformReauthTests {

        @Test
        @DisplayName("On failure transitions to DEAD, clears reconnecting flag, returns false")
        void shouldMarkDeadOnFailure() throws Exception {
            setReconnecting(bot, true);

            when(apiGatewayClient.authenticate(any()))
                    .thenThrow(new RuntimeException("auth server down"));

            Method m = Bot.class.getDeclaredMethod("performReauth");
            m.setAccessible(true);
            boolean result = (boolean) m.invoke(bot);

            assertThat(result).isFalse();
            assertThat(bot.getStatus()).isEqualTo(BotStatus.DEAD);
            assertThat(getReconnecting(bot)).isFalse();
        }
    }

    /* ----- runAuthThenWsLoop ----- */

    @Nested
    @DisplayName("runAuthThenWsLoop")
    class RunAuthThenWsLoopTests {

        @Test
        @DisplayName("Happy path: re-auth + WS reconnect succeed; only the confirm-sleep (3000) is recorded")
        void shouldReturnAfterSuccessfulReauthAndReconnect() throws Exception {
            setReconnecting(bot, true);

            when(apiGatewayClient.authenticate(any())).thenReturn(tokens);

            VingameWebSocketClient newClient = mock(VingameWebSocketClient.class);
            when(newClient.isOpen()).thenReturn(true);
            when(clientFactory.newClient(any(), eq("bot1"))).thenReturn(newClient);

            invokePrivate("runAuthThenWsLoop");

            // Only confirm-sleep (3000ms), no backoff sequence
            assertThat(bot.sleeps).containsExactly(3000L);
            assertThat(getReconnecting(bot)).isFalse();
        }

        @Test
        @DisplayName("Re-auth ok but WS reconnect fails -> falls through to runWsReconnectLoop (both confirm and backoff observed)")
        void shouldFallThroughToWsLoopWhenWsFails() throws Exception {
            setReconnecting(bot, true);

            when(apiGatewayClient.authenticate(any())).thenReturn(tokens);

            // First newClient: returns a client whose isOpen() is false (after confirm)
            // Second newClient (in runWsReconnectLoop): also false; then we mark stopped to break the loop
            VingameWebSocketClient closedClient = mock(VingameWebSocketClient.class);
            when(closedClient.isOpen()).thenReturn(false);

            when(clientFactory.newClient(any(), anyString()))
                    .thenReturn(closedClient) // first call: tryReconnectWs returns true but isOpen=false after sleep
                    .thenAnswer(inv -> {
                        // Mark stopped so runWsReconnectLoop exits after its first sleep+attempt
                        Field stoppedF = Bot.class.getDeclaredField("stopped");
                        stoppedF.setAccessible(true);
                        stoppedF.set(bot, true);
                        return closedClient;
                    });

            invokePrivate("runAuthThenWsLoop");

            // 3000 (confirm after first reconnect attempt in auth loop) + 5000 (first backoff in ws loop)
            assertThat(bot.sleeps).contains(3000L, 5000L);
        }
    }

    /* ----- helpers ----- */

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

    private static void setReconnecting(FastBot bot, boolean v) throws Exception {
        Field f = Bot.class.getDeclaredField("reconnecting");
        f.setAccessible(true);
        AtomicBoolean ab = (AtomicBoolean) f.get(bot);
        ab.set(v);
    }

    private static boolean getReconnecting(FastBot bot) throws Exception {
        Field f = Bot.class.getDeclaredField("reconnecting");
        f.setAccessible(true);
        AtomicBoolean ab = (AtomicBoolean) f.get(bot);
        return ab.get();
    }

    /**
     * Subclass that overrides {@code sleep(long)} so tests run instantly and we can
     * assert the exact backoff sequence the loop applied.
     */
    static class FastBot extends Bot {
        final List<Long> sleeps = new CopyOnWriteArrayList<>();

        @Override
        protected void sleep(long millis) {
            sleeps.add(millis);
            // no actual sleep
        }

        @Override protected void initializeSubclass() {}
        @Override protected Scenario botBehaviorScenario() { return null; }
        @Override protected void onStart() {}
    }
}
