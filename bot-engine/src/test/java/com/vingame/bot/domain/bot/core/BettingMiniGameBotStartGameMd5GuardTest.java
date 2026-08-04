package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.taixiu.MiniGameTaiXiuMessageTypes;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.scenario.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * Defensive-hardening regression (flagged by Tai Xiu review): the StartGame
 * handler registration in {@link BettingMiniGameBot#botBehaviorScenario()} must be
 * null-safe, mirroring the existing updateBet null-guard.
 * <p>
 * When a fixed-CMD game is configured with {@code md5=true} but its provider has no
 * md5 StartGame variant ({@link MiniGameTaiXiuMessageTypes#startGameMd5Type()}
 * returns {@code null}, AD-10), the md5 StartGame branch
 * ({@code game.isMd5() ? startGameMd5Type() : startGameType()}) resolves to
 * {@code null}. Before the guard, {@code .onMessage(null, …)} would have NPE'd while
 * compiling the scenario. The guard now skips the handler instead — exactly as the
 * updateBet path already does when {@code updateBetType()} is null.
 */
@DisplayName("BettingMiniGameBot startGame md5 null-guard")
class BettingMiniGameBotStartGameMd5GuardTest {

    private TaiXiuGameBot bot;

    @AfterEach
    void tearDown() throws Exception {
        if (bot == null) return;
        ScheduledExecutorService w = (ScheduledExecutorService) readField("watchdogScheduler");
        if (w != null) w.shutdownNow();
        ScheduledExecutorService s = (ScheduledExecutorService) readField("scheduler");
        if (s != null) s.shutdownNow();
    }

    @Test
    @DisplayName("md5=true with null md5 StartGame type: scenario builds without NPE (md5 handler skipped)")
    void md5StartGameNullDoesNotNpe() throws Exception {
        bot = newTaiXiuBot(true);

        // Sanity-pin the precondition the guard exists for: this config's md5 branch
        // resolves to null exactly like updateBetType() does.
        Class<? extends StartGameMd5Message> md5Type =
                (Class<? extends StartGameMd5Message>) invokeSeam("startGameMd5Type");
        assertThat(md5Type).as("MiniGameTaiXiuMessageTypes has no md5 StartGame variant").isNull();

        // Build the scenario. Before the guard this NPE'd on .onMessage(null, …)
        // while compiling. With the guard the md5 StartGame handler is simply skipped.
        assertThatCode(() -> {
            Scenario scenario = (Scenario) invokeSeam("botBehaviorScenario");
            assertThat(scenario).isNotNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("md5=false (non-md5 happy path): scenario still builds, unchanged")
    void nonMd5HappyPathStillBuilds() throws Exception {
        bot = newTaiXiuBot(false);

        assertThatCode(() -> {
            Scenario scenario = (Scenario) invokeSeam("botBehaviorScenario");
            assertThat(scenario).isNotNull();
        }).doesNotThrowAnyException();
    }

    /* ---- fixtures ---- */

    private TaiXiuGameBot newTaiXiuBot(boolean md5) {
        Game game = Game.builder()
                .id("g-taixiu").name("TaiXiu").pluginName("taixiuPlugin")
                .offset(999_999) // poison offset — Tai Xiu must never read it (AD-9)
                .numberOfOptions(2)
                .md5(md5)
                .build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100_000).maxBet(1_000_000).betIncrement(100_000)
                .maxTotalBetPerRound(2_000_000).minBetsPerRound(1).maxBetsPerRound(1)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("taixiumd5bot").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .strategyId(StrategyId.RANDOM)
                .build();

        TaiXiuGameBot b = new TaiXiuGameBot();
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(cfg);
        b.setTaiXiuMessageTypes(new MiniGameTaiXiuMessageTypes());
        b.setRandom(new Random(0L));
        b.initializeSubclass();
        // The inherited `client` field is normally set during auth (initialize()).
        // botBehaviorScenario() feeds it into PipelineContext, which rejects a null
        // client. Seed a mock so the scenario can compile without a live connection.
        seedClient(b, mock(VingameWebSocketClient.class));
        return b;
    }

    private static void seedClient(TaiXiuGameBot b, VingameWebSocketClient client) {
        try {
            Field f = Bot.class.getDeclaredField("client");
            f.setAccessible(true);
            f.set(b, client);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /* ---- reflection ---- */

    private Object invokeSeam(String name) throws Exception {
        Method m = BettingMiniGameBot.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(bot);
    }

    private Object readField(String name) throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(bot);
    }
}
