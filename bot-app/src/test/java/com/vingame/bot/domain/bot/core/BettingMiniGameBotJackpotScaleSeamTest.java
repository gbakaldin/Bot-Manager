package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.coordination.JackpotScaler;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase J3 bot seam (JACKPOT_SCALE_AND_RAMP AD-J4): the volume factor snapshotted
 * at {@code onStartGame} drives {@code BetContext.effectiveMaxBetsPerRound =
 * max(1, round(maxBetsPerRound × factor))}, floored at 1, with the feature off
 * (factor 1.0 / null scaler) equalling {@code maxBetsPerRound} exactly.
 */
@DisplayName("BettingMiniGameBot jackpot-scale seam (JACKPOT_SCALE_AND_RAMP Phase J3)")
class BettingMiniGameBotJackpotScaleSeamTest {

    private static final int MAX_BETS_PER_ROUND = 8;
    private static final long SEED = JackpotScaler.DEFAULT_SEED_FLOOR; // 500_000
    private static final long CEILING = 20_000_000L;
    private static final double MIN = 0.25;

    private BettingMiniGameBot bot;

    @BeforeEach
    void setUp() throws Exception {
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) affinities.put(i, 1);
        Game game = Game.builder()
                .id("g-jp").name("BauCua").pluginName("BauCua")
                .offset(2000).optionAffinities(affinities).build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100).maxBet(1000).betIncrement(100)
                .maxTotalBetPerRound(10_000).minBetsPerRound(1)
                .maxBetsPerRound(MAX_BETS_PER_ROUND)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("jpbot").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame3").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .strategyId(StrategyId.RANDOM)
                .build();

        bot = new BettingMiniGameBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setRandom(new Random(0xF00DL));
        bot.initializeSubclass();

        seedAtomic("expectedCurrentBalance", 50_000_000L);
    }

    @AfterEach
    void tearDown() throws Exception {
        freezeSchedulers();
    }

    @Test
    @DisplayName("no scaler wired → effective cap == configured maxBetsPerRound (factor 1.0)")
    void nullScalerIsIdentity() throws Exception {
        invokeOnStartGame(1L);
        assertThat(buildBetContext().effectiveMaxBetsPerRound()).isEqualTo(MAX_BETS_PER_ROUND);
    }

    @Test
    @DisplayName("factor 1.0 (pool at ceiling) → effective cap == configured max")
    void factorOneKeepsConfiguredMax() throws Exception {
        JackpotScaler scaler = new JackpotScaler(CEILING, SEED, MIN);
        scaler.observePool(100L, CEILING); // factor 1.0
        bot.setJackpotScaler(scaler);

        invokeOnStartGame(2L);
        assertThat(buildBetContext().effectiveMaxBetsPerRound()).isEqualTo(MAX_BETS_PER_ROUND);
    }

    @Test
    @DisplayName("midpoint factor 0.625 → round(8 × 0.625) = 5")
    void midpointRoundsCap() throws Exception {
        JackpotScaler scaler = new JackpotScaler(CEILING, SEED, MIN);
        scaler.observePool(100L, SEED + (CEILING - SEED) / 2); // factor 0.625
        bot.setJackpotScaler(scaler);

        invokeOnStartGame(3L);
        // round(8 * 0.625) = round(5.0) = 5
        assertThat(buildBetContext().effectiveMaxBetsPerRound()).isEqualTo(5);
    }

    @Test
    @DisplayName("floor factor 0.25 → round(8 × 0.25) = 2")
    void floorFactorScalesCap() throws Exception {
        JackpotScaler scaler = new JackpotScaler(CEILING, SEED, MIN);
        scaler.observePool(100L, SEED); // factor 0.25
        bot.setJackpotScaler(scaler);

        invokeOnStartGame(4L);
        // round(8 * 0.25) = round(2.0) = 2
        assertThat(buildBetContext().effectiveMaxBetsPerRound()).isEqualTo(2);
    }

    @Test
    @DisplayName("floors at 1 even when round(max × factor) would be 0")
    void floorsAtOne() throws Exception {
        // maxBetsPerRound = 1 at the floor factor: round(1 * 0.25) = round(0.25) = 0
        // → max(1, 0) = 1. Rebuild the bot with maxBetsPerRound = 1.
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) affinities.put(i, 1);
        Game game = Game.builder()
                .id("g-jp1").name("BauCua").pluginName("BauCua")
                .offset(2000).optionAffinities(affinities).build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100).maxBet(1000).betIncrement(100)
                .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(1)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("jpbot1").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame3").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .strategyId(StrategyId.RANDOM)
                .build();
        bot = new BettingMiniGameBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setRandom(new Random(0xF00DL));
        bot.initializeSubclass();
        seedAtomic("expectedCurrentBalance", 50_000_000L);

        JackpotScaler scaler = new JackpotScaler(CEILING, SEED, MIN);
        scaler.observePool(100L, SEED); // factor 0.25
        bot.setJackpotScaler(scaler);

        invokeOnStartGame(5L);
        assertThat(buildBetContext().effectiveMaxBetsPerRound())
                .as("a bot never drops below one bet per round while betting is enabled")
                .isEqualTo(1);
    }

    /* ---- helpers ---- */

    private void invokeOnStartGame(long sid) throws Exception {
        StartGameMessage msg = mock(StartGameMessage.class);
        when(msg.getSessionId()).thenReturn(sid);
        ActionResponseMessage<StartGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        Method m = BettingMiniGameBot.class.getDeclaredMethod("onStartGame", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(bot, resp);
        // Freeze the live countdown scheduler onStartGame spins up so no
        // background virtual thread mutates bot state (remainingTime / sidStore
        // via reconnect paths) between here and the buildBetContext() assertion.
        // The effective-cap seam reads the onStartGame-snapshotted
        // currentJackpotFactor (not remainingTime), so these assertions are not
        // racy today, but the freeze keeps the harness uniform with the ramp
        // seam and removes the latent async surface.
        freezeSchedulers();
    }

    private void freezeSchedulers() throws Exception {
        ScheduledExecutorService countdown = (ScheduledExecutorService) readField("scheduler");
        if (countdown != null) {
            countdown.shutdownNow();
            countdown.awaitTermination(2, TimeUnit.SECONDS);
            Field f = BettingMiniGameBot.class.getDeclaredField("scheduler");
            f.setAccessible(true);
            f.set(bot, null);
        }
        ScheduledExecutorService watchdog = (ScheduledExecutorService) readField("watchdogScheduler");
        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private BetContext buildBetContext() throws Exception {
        Method m = BettingMiniGameBot.class.getDeclaredMethod("buildBetContext");
        m.setAccessible(true);
        return (BetContext) m.invoke(bot);
    }

    private Object readField(String name) throws Exception {
        Field f;
        try {
            f = BettingMiniGameBot.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = Bot.class.getDeclaredField(name);
        }
        f.setAccessible(true);
        return f.get(bot);
    }

    private void seedAtomic(String name, long value) throws Exception {
        Field f = Bot.class.getDeclaredField(name);
        f.setAccessible(true);
        ((AtomicLong) f.get(bot)).set(value);
    }
}
