package com.vingame.bot.domain.bot.core;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetSettings;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.scenario.Scenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * <b>A budget wait taken on the session path must be shorter than the watchdog's patience</b>
 * (GATEWAY_REQUEST_BUDGET, review F1).
 * <p>
 * The failure this exists to prevent is the feature causing the thing it was built to stop.
 * {@code onNewSession} runs on the library's per-client {@code netty-ws-message-processor-*}
 * worker, and {@code BettingMiniGameBot.onEndGame} arms the watchdog on the line immediately
 * before calling it. Under {@code enforce} that thread can block on the budget three times — the
 * pre-deposit refresh, the deposit, and the confirming read — and at PRIORITIZED's configured
 * ten minutes against a 180-second watchdog, any one of them alone overruns by 33x. The watchdog
 * then calls {@code triggerFullReconnect} on a bot that is working, which is a PRIORITIZED re-auth
 * plus a PRIORITIZED WS upgrade: the pacing manufacturing a reconnect storm.
 * <p>
 * <b>Asserted against the two real config values, not against literals.</b> Both numbers are read
 * out of the shipped {@code application.properties} — {@code bot.watchdog.timeout.seconds} and
 * {@code bot.gateway.budget.tier.prioritized.max-wait} — so lowering the watchdog, or raising the
 * tier wait, cannot silently re-open this. A hardcoded "10 s < 180 s" would have passed forever.
 * <p>
 * No socket, no budget queue, no Spring: the bound is a pure function of configuration.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@DisplayName("Bot — the session-path budget wait is bounded by the watchdog")
class BotSessionBudgetWaitTest {

    /** Where {@code application.properties} is, from either module root or the repo root. */
    private static final List<String> PROPERTY_PATHS = List.of(
            "bot-app/src/main/resources/application.properties",
            "../bot-app/src/main/resources/application.properties");

    private static Properties shippedProperties() {
        Path path = PROPERTY_PATHS.stream().map(Path::of).filter(Files::isRegularFile)
                .findFirst().orElse(null);
        // An assertion, not an assumption (review T6). The file is committed and the two candidate
        // paths are exhaustive (module dir, reactor root), so there is no environment in which
        // skipping is the right answer — and a module layout change would otherwise turn the F1
        // guard into a permanently green skip, which is the one way this test can stop guarding.
        assertThat(path)
                .as("application.properties must be found from %s — if the module layout changed, "
                        + "add the new path rather than letting this test skip itself",
                        Path.of("").toAbsolutePath())
                .isNotNull();
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        } catch (IOException e) {
            throw new AssertionError("application.properties is not readable", e);
        }
        return properties;
    }

    /** The shipped watchdog timeout, in seconds. */
    private static long shippedWatchdogSeconds() {
        String value = shippedProperties().getProperty("bot.watchdog.timeout.seconds");
        assertThat(value)
                .as("bot.watchdog.timeout.seconds has been renamed or removed — this test's whole "
                        + "point is that the bound tracks it, so update both together")
                .isNotNull();
        return Long.parseLong(value.trim());
    }

    /** The shipped PRIORITIZED max-wait, parsed the way Spring's Duration converter does. */
    private static Duration shippedPrioritizedMaxWait() {
        String value = shippedProperties()
                .getProperty("bot.gateway.budget.tier.prioritized.max-wait");
        assertThat(value).isNotNull();
        String trimmed = value.trim();
        if (trimmed.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
        }
        if (trimmed.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
        }
        return Duration.ofMillis(Long.parseLong(trimmed));
    }

    private static WaitBot botWithWatchdog(long watchdogSeconds, GatewayBudget budget) {
        WaitBot bot = new WaitBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("botuser1").password("pw").fingerprint("fp-1").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(Game.builder().id("g1").name("BauCua").gameType(GameType.BETTING_MINI)
                        .pluginName("BauCua").offset(2000).numberOfOptions(6).build())
                .zoneName("MiniGame3").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(watchdogSeconds)
                .build());
        bot.setGatewayBudget(budget);
        bot.client = mock(VingameWebSocketClient.class);
        return bot;
    }

    /** A budget that reports the shipped per-tier waits and does nothing else. */
    private static GatewayBudget shippedPolicyBudget() {
        GatewayBudgetSettings settings = GatewayBudgetSettings.defaults();
        return new com.vingame.bot.infrastructure.gateway.SlidingWindowGatewayBudget(
                "env-1", "Staging", "116", settings,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), () -> 0L);
    }

    @Test
    @DisplayName("the derived bound is strictly below the SHIPPED watchdog timeout")
    void theBoundIsBelowTheShippedWatchdog() {
        long watchdogSeconds = shippedWatchdogSeconds();
        GatewayBudget budget = shippedPolicyBudget();
        try {
            Duration bound = botWithWatchdog(watchdogSeconds, budget).sessionBudgetWait();

            assertThat(bound)
                    .as("a session-path wait of null would mean 'use the tier's own', i.e. 10 "
                            + "minutes against a %ds watchdog", watchdogSeconds)
                    .isNotNull();
            assertThat(bound)
                    .as("THE invariant: a wait taken on the message-processor thread must be "
                            + "shorter than the watchdog that is counting down on that same "
                            + "thread's silence. Shipped watchdog is %ds.", watchdogSeconds)
                    .isLessThan(Duration.ofSeconds(watchdogSeconds));
            assertThat(bound)
                    .as("and with room for the three sequential waits one onNewSession can take, "
                            + "plus the round trips, plus the game message that resets the watchdog")
                    .isLessThanOrEqualTo(Duration.ofSeconds(watchdogSeconds / 4));
            assertThat(bound)
                    .as("it is a bound, so it can only ever be SHORTER than the tier's own wait")
                    .isLessThan(shippedPrioritizedMaxWait());
        } finally {
            ((com.vingame.bot.infrastructure.gateway.SlidingWindowGatewayBudget) budget).shutdown();
        }
    }

    @Test
    @DisplayName("the bound tracks the watchdog rather than being a literal")
    void theBoundTracksTheWatchdog() {
        GatewayBudget budget = shippedPolicyBudget();
        try {
            // The whole reason it is a fraction: someone lowering the watchdog to 20 s must not
            // have to remember that a number in Bot.java also needs lowering.
            assertThat(botWithWatchdog(180L, budget).sessionBudgetWait())
                    .isEqualTo(Duration.ofSeconds(45));
            assertThat(botWithWatchdog(20L, budget).sessionBudgetWait())
                    .isEqualTo(Duration.ofSeconds(5));
            assertThat(botWithWatchdog(2L, budget).sessionBudgetWait())
                    .as("floored at one second — zero would mean 'now or never', which is a "
                            + "different decision and not this method's to make")
                    .isEqualTo(Duration.ofSeconds(1));
            for (long watchdog : new long[] {2L, 20L, 60L, 180L, 600L, 3600L}) {
                assertThat(botWithWatchdog(watchdog, budget).sessionBudgetWait())
                        .as("the invariant holds for a watchdog of %ds too", watchdog)
                        .isLessThan(Duration.ofSeconds(watchdog));
            }
        } finally {
            ((com.vingame.bot.infrastructure.gateway.SlidingWindowGatewayBudget) budget).shutdown();
        }
    }

    @Test
    @DisplayName("a tier wait shorter than the derived bound wins — a bound only shortens")
    void theTierWaitWinsWhenItIsShorter() {
        GatewayBudgetSettings tightened = GatewayBudgetSettings.defaults()
                .withMaxWait(RequestTier.PRIORITIZED, Duration.ofSeconds(3));
        GatewayBudget budget = new com.vingame.bot.infrastructure.gateway.SlidingWindowGatewayBudget(
                "env-1", "Staging", "116", tightened,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), () -> 0L);
        try {
            assertThat(botWithWatchdog(180L, budget).sessionBudgetWait())
                    .as("an operator who tightens prioritized.max-wait means it")
                    .isEqualTo(Duration.ofSeconds(3));
        } finally {
            ((com.vingame.bot.infrastructure.gateway.SlidingWindowGatewayBudget) budget).shutdown();
        }
    }

    @Test
    @DisplayName("no watchdog configured means the tier's own wait, because nothing will reconnect")
    void noWatchdogMeansNoBound() {
        GatewayBudget budget = shippedPolicyBudget();
        try {
            assertThat(botWithWatchdog(0L, budget).sessionBudgetWait())
                    .as("null = 'use the tier's own'. With no watchdog there is nothing to convert "
                            + "a long wait into a reconnect, so the bound has no subject.")
                    .isNull();
        } finally {
            ((com.vingame.bot.infrastructure.gateway.SlidingWindowGatewayBudget) budget).shutdown();
        }
    }

    /** Minimal concrete bot: no scenario, no game messages, no threads. */
    static class WaitBot extends Bot {
        @Override protected void initializeSubclass() {}
        @Override protected Scenario botBehaviorScenario() { return null; }
        @Override protected void onStart() {}
    }
}
