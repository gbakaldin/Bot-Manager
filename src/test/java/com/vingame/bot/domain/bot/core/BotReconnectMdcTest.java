package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.scenario.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the reconnect virtual threads spawned by {@link Bot} at the
 * {@code Thread.ofVirtual().name("reconnect-" + userName).start(mdcWrap(...))}
 * sites inherit the bot's MDC snapshot.
 * <p>
 * Both spawns use {@code mdcWrap} around the same {@code mdcSnapshot} field,
 * so verifying one path is sufficient to confirm the wrapping is in effect.
 * We test both via {@code triggerFullReconnect("test")} (calls
 * {@code runAuthThenWsLoop}) and via {@code onWsDisconnected()} (calls
 * {@code runWsReconnectLoop}) for documentation completeness.
 * <p>
 * The strategy: subclass {@code Bot} and override the protected {@code sleep}
 * method to (1) capture {@code MDC.getCopyOfContextMap()} into a future on
 * first invocation, then (2) flip the {@code stopped} flag so the loop exits
 * immediately. Both reconnect loops call {@code sleep} as the first action
 * after entering, which means the capture happens inside the {@code mdcWrap}
 * lambda body — i.e. with the snapshot applied.
 */
@DisplayName("Bot reconnect MDC propagation")
class BotReconnectMdcTest {

    private static final Map<String, String> SNAPSHOT = Map.of(
            "botGroupId", "group-reconnect-test",
            "botId", "1",
            "environmentId", "env-reconnect-test",
            "gameType", "BauCua",
            "botUserName", "bot_reconnect"
    );

    private CapturingBot bot;

    @BeforeEach
    void setUp() throws Exception {
        ApiGatewayClient apiGw = mock(ApiGatewayClient.class);
        when(apiGw.getApiGateway()).thenReturn("http://gw.test");
        // For triggerFullReconnect path: runAuthThenWsLoop calls performReauth() first.
        // Return tokens (or anything non-throwing) so the loop reaches sleep().
        when(apiGw.authenticate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(mock(com.vingame.websocketparser.auth.TokensProvider.class));

        BotCredentials credentials = BotCredentials.builder()
                .username("bot_reconnect").password("pw").fingerprint("fp").build();
        Game game = Game.builder().id("g1").name("BauCua").pluginName("BauCua")
                .offset(2000).numberOfOptions(6).build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(credentials)
                .environmentId("env-reconnect-test").botGroupId("group-reconnect-test")
                .botIndex(1).game(game).zoneName("Z").timeoutMillis(1000L)
                .watchdogTimeoutSeconds(120L)
                .build();

        bot = new CapturingBot();
        bot.setClients(apiGw, mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);

        // Inject the snapshot directly — replicates what Bot.initialize() does at
        // the end of its try block, without forcing us to run real auth + WS.
        Field f = Bot.class.getDeclaredField("mdcSnapshot");
        f.setAccessible(true);
        f.set(bot, new HashMap<>(SNAPSHOT));

        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("triggerFullReconnect spawns a virtual thread that sees the MDC snapshot")
    void triggerFullReconnectVirtualThreadCarriesSnapshot() throws Exception {
        // Pre-condition: not already reconnecting, not stopped.
        bot.triggerFullReconnect("test");

        // The spawned virtual thread runs mdcWrap(this::runAuthThenWsLoop).
        // Inside it: performReauth() succeeds (mocked), then sleep(3000) — the
        // first sleep call captures MDC.
        Map<String, String> captured = bot.capturedMdc.get(5, TimeUnit.SECONDS);

        assertThat(captured.get("botGroupId")).isEqualTo("group-reconnect-test");
        assertThat(captured.get("environmentId")).isEqualTo("env-reconnect-test");
        assertThat(captured.get("gameType")).isEqualTo("BauCua");
        assertThat(captured.get("botUserName")).isEqualTo("bot_reconnect");
        assertThat(captured).isEqualTo(SNAPSHOT);
    }

    @Test
    @DisplayName("onWsDisconnected spawns a virtual thread that sees the MDC snapshot")
    void onWsDisconnectedVirtualThreadCarriesSnapshot() throws Exception {
        // onWsDisconnected is private — invoke via reflection. It is the method
        // wired into the wsClient.onDisconnect listener in configureClient(...).
        VingameWebSocketClient wsClient = mock(VingameWebSocketClient.class);
        bot.client = wsClient;

        java.lang.reflect.Method m = Bot.class.getDeclaredMethod("onWsDisconnected");
        m.setAccessible(true);
        m.invoke(bot);

        // The spawned virtual thread runs mdcWrap(this::runWsReconnectLoop).
        // First action inside: sleep(BACKOFF_SECONDS[0] * 1000).
        Map<String, String> captured = bot.capturedMdc.get(5, TimeUnit.SECONDS);

        assertThat(captured).isEqualTo(SNAPSHOT);
        assertThat(captured.get("botGroupId")).isEqualTo("group-reconnect-test");
    }

    /**
     * Bot subclass whose only customisation is the {@code sleep(long)} override:
     * first invocation captures MDC and trips the {@code stopped} flag so the
     * loop exits cleanly. Used as a probe inside the virtual thread spawned
     * by {@code mdcWrap(this::runWsReconnectLoop)} / {@code mdcWrap(this::runAuthThenWsLoop)}.
     */
    static class CapturingBot extends Bot {
        final CompletableFuture<Map<String, String>> capturedMdc = new CompletableFuture<>();

        @Override
        protected void sleep(long millis) {
            Map<String, String> snap = MDC.getCopyOfContextMap();
            // First call wins. completeUniquely-style: capturedMdc is final,
            // completing twice is a no-op on the second call.
            if (!capturedMdc.isDone()) {
                capturedMdc.complete(snap == null ? new HashMap<>() : new HashMap<>(snap));
            }
            // Flip stopped so the loop body sees `if (stopped) return;` on the next
            // iteration and exits. Skip real sleeping entirely.
            try {
                Field stoppedF = Bot.class.getDeclaredField("stopped");
                stoppedF.setAccessible(true);
                stoppedF.set(this, true);
            } catch (Exception ignored) {
                // best-effort; if it fails the loop will sleep for real and
                // potentially time out — acceptable test failure mode.
            }
        }

        @Override protected void initializeSubclass() {}
        @Override protected Scenario botBehaviorScenario() { return null; }
        @Override protected void onStart() {}
    }
}
