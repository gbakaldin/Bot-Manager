package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.websocketparser.scenario.Scenario;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3 (OBSERVABILITY): verifies that {@link Bot} accumulates time spent in
 * {@link BotStatus#DEAD} into {@code bot_dead_seconds_total} via {@link BotMetrics}.
 * <p>
 * Two routes credit the counter:
 * <ul>
 *   <li>Transition out of DEAD (defensive — DEAD is terminal in current code).</li>
 *   <li>{@link Bot#cleanup()} on a still-DEAD bot — the production close-out path.</li>
 * </ul>
 * Architecture Decision 3: STOPPED is intentional and does NOT add to the counter
 * on its own; only the elapsed DEAD seconds preceding any stop are credited.
 */
@DisplayName("Bot dead-seconds accumulator (Phase 3)")
class BotDeadSecondsTest {

    private MeterRegistry registry;
    private BotMetrics metrics;
    private TestBot bot;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new BotMetrics(registry);

        ApiGatewayClient apiGatewayClient = mock(ApiGatewayClient.class);
        when(apiGatewayClient.getApiGateway()).thenReturn("http://gateway.test");

        BotCredentials credentials = BotCredentials.builder()
                .username("botuser1")
                .password("pw")
                .fingerprint("fp-1")
                .build();

        Game game = Game.builder()
                .id("g1")
                .name("BauCua")
                .pluginName("BauCua")
                .offset(2000)
                .numberOfOptions(6)
                .build();

        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(credentials)
                .environmentId("env-1")
                .botGroupId("group-1")
                .botIndex(1)
                .game(game)
                .zoneName("MiniGame3")
                .timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .build();

        bot = new TestBot();
        bot.setClients(apiGatewayClient, mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setMetrics(metrics);
    }

    @Test
    @DisplayName("Transition INTO DEAD stamps deadSince")
    void enterDeadStampsTimestamp() throws Exception {
        invokeTransition(BotStatus.DEAD);

        Instant stamp = readDeadSince();
        assertThat(stamp).isNotNull();
        assertThat(stamp).isCloseTo(Instant.now(), within10s());
    }

    @Test
    @DisplayName("Transition OUT of DEAD credits elapsed seconds and clears stamp")
    void exitDeadCreditsCounterAndClears() throws Exception {
        // Backdate deadSince so Duration.between() yields a stable positive value
        // without sleeping (toSeconds() truncates fractions, so <1s sleeps round to 0).
        forceStatus(BotStatus.DEAD);
        setDeadSince(Instant.now().minusSeconds(7));

        invokeTransition(BotStatus.AUTHENTICATING);

        Counter c = registry.find(BotMetrics.BOT_DEAD_SECONDS_TOTAL).counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isGreaterThanOrEqualTo(7.0).isLessThan(15.0);
        assertThat(readDeadSince()).isNull();
    }

    @Test
    @DisplayName("DEAD -> DEAD is a no-op: counter does not move, stamp does not advance")
    void deadToDeadIsNoOp() throws Exception {
        invokeTransition(BotStatus.DEAD);
        Instant firstStamp = readDeadSince();
        assertThat(firstStamp).isNotNull();

        // Re-enter DEAD — should be a no-op per transitionStatus's prev==next guard.
        invokeTransition(BotStatus.DEAD);

        // Stamp must not have moved
        assertThat(readDeadSince()).isEqualTo(firstStamp);

        // Counter must NOT have ticked — no exit-from-DEAD branch ran
        Counter c = registry.find(BotMetrics.BOT_DEAD_SECONDS_TOTAL).counter();
        if (c != null) {
            assertThat(c.count()).isEqualTo(0.0);
        }
    }

    @Test
    @DisplayName("cleanup() on a DEAD bot credits the terminal DEAD window")
    void cleanupOnDeadBotCreditsTerminalWindow() throws Exception {
        forceStatus(BotStatus.DEAD);
        setDeadSince(Instant.now().minusSeconds(12));

        bot.cleanup();

        Counter c = registry.find(BotMetrics.BOT_DEAD_SECONDS_TOTAL).counter();
        assertThat(c).as("dead-seconds counter must register after cleanup() of a DEAD bot")
                .isNotNull();
        assertThat(c.count()).isGreaterThanOrEqualTo(12.0).isLessThan(20.0);
        assertThat(readDeadSince()).isNull();
        assertThat(bot.isStopped()).isTrue();
    }

    @Test
    @DisplayName("cleanup() on a non-DEAD bot does NOT register the counter")
    void cleanupOnLiveBotDoesNotCredit() {
        // bot is AUTHENTICATING by default; deadSince is null.
        bot.cleanup();

        Counter c = registry.find(BotMetrics.BOT_DEAD_SECONDS_TOTAL).counter();
        // Counter may legitimately not exist yet (lazy registration) — that's the assertion.
        assertThat(c).as("a non-DEAD cleanup must not register the dead-seconds counter")
                .isNull();
    }

    @Test
    @DisplayName("DEAD -> STOPPED-style direct transition still credits the DEAD window")
    void deadToTerminalDirectCreditsCounter() throws Exception {
        // No STOPPED in BotStatus — but the invariant is "any transition out of DEAD
        // credits". Use CONNECTING as a stand-in for "any non-DEAD next status".
        forceStatus(BotStatus.DEAD);
        setDeadSince(Instant.now().minusSeconds(3));

        invokeTransition(BotStatus.CONNECTING);

        Counter c = registry.find(BotMetrics.BOT_DEAD_SECONDS_TOTAL).counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    @DisplayName("Multiple DEAD-revive cycles sum independently into the same counter")
    void multipleDeadReviveCyclesSumIndependently() throws Exception {
        // Cycle 1: DEAD for ~4s, then revive.
        forceStatus(BotStatus.DEAD);
        setDeadSince(Instant.now().minusSeconds(4));
        invokeTransition(BotStatus.CONNECTING);

        Counter c = registry.find(BotMetrics.BOT_DEAD_SECONDS_TOTAL).counter();
        assertThat(c).isNotNull();
        double afterCycle1 = c.count();
        assertThat(afterCycle1).isGreaterThanOrEqualTo(4.0).isLessThan(15.0);

        // Cycle 2: DEAD again for ~6s, then revive. Stamp must have been cleared
        // by cycle 1's exit branch — otherwise this cycle would credit a stale window.
        invokeTransition(BotStatus.DEAD);
        setDeadSince(Instant.now().minusSeconds(6));
        invokeTransition(BotStatus.CONNECTING);

        double afterCycle2 = c.count();
        // Each cycle credits independently — second cycle adds ~6s on top of cycle 1.
        assertThat(afterCycle2 - afterCycle1).isGreaterThanOrEqualTo(6.0).isLessThan(15.0);
        assertThat(readDeadSince()).isNull();
    }

    /* ---- reflection helpers ---- */

    private static org.assertj.core.data.TemporalUnitOffset within10s() {
        return new org.assertj.core.data.TemporalUnitWithinOffset(
                10, java.time.temporal.ChronoUnit.SECONDS);
    }

    private void invokeTransition(BotStatus next) throws Exception {
        Method m = Bot.class.getDeclaredMethod("transitionStatus", BotStatus.class);
        m.setAccessible(true);
        m.invoke(bot, next);
    }

    private void forceStatus(BotStatus s) throws Exception {
        Field f = Bot.class.getDeclaredField("status");
        f.setAccessible(true);
        f.set(bot, s);
    }

    private void setDeadSince(Instant when) throws Exception {
        Field f = Bot.class.getDeclaredField("deadSince");
        f.setAccessible(true);
        f.set(bot, when);
    }

    private Instant readDeadSince() throws Exception {
        Field f = Bot.class.getDeclaredField("deadSince");
        f.setAccessible(true);
        return (Instant) f.get(bot);
    }

    static class TestBot extends Bot {
        @Override protected void initializeSubclass() {}
        @Override protected Scenario botBehaviorScenario() { return null; }
        @Override protected void onStart() {}
    }
}
