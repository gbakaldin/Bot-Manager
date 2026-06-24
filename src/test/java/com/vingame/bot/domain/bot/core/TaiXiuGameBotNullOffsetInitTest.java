package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.taixiu.MiniGameTaiXiuMessageTypes;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BetDecision;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.RoundResult;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for the staging NPE in the shared bot init path.
 *
 * <p>{@link BettingMiniGameBot#initializeSubclass()} unboxes
 * {@code game.getOffset()} into the primitive {@code offset} field. For a Tai Xiu
 * game the offset is <b>null by design</b> (AD-9: the fixed-CMD bot never uses
 * offset). The eager auto-unbox threw
 * {@code NullPointerException: Cannot invoke "java.lang.Integer.intValue()"
 * because the return value of "Game.getOffset()" is null}, so a correctly
 * configured Tai Xiu group created <b>0 bots</b> — every createBot NPE'd in this
 * shared path before any subclass code ran.
 *
 * <p>Unlike {@link TaiXiuGameBotStreamTest}, which seeded a non-null poison
 * offset and scrubbed the init read with {@code clearInvocations}, this test
 * reproduces the actual staging failure mode (offset truly {@code null}) and
 * proves {@code initializeSubclass()} now completes without throwing while the
 * fixed CMD seams remain literal constants (offset never leaks into a CMD).
 */
@DisplayName("TaiXiuGameBot init with null offset (regression)")
class TaiXiuGameBotNullOffsetInitTest {

    private TaiXiuGameBot bot;

    private TaiXiuGameBot buildBotWithNullOffset() {
        // Real Game with offset left unset → getOffset() returns null, exactly as a
        // Tai Xiu game is configured in production (AD-9).
        Game game = Game.builder()
                .id("g-taixiu").name("TaiXiu").pluginName("taixiuPlugin")
                .numberOfOptions(2)
                .build();
        assertThat(game.getOffset()).as("precondition: Tai Xiu game offset is null").isNull();

        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100_000).maxBet(1_000_000).betIncrement(100_000)
                .maxTotalBetPerRound(2_000_000).minBetsPerRound(1).maxBetsPerRound(1)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("taixiubot1").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .strategyId(StrategyId.RANDOM)
                .build();

        BettingStrategy fixed = new BettingStrategy() {
            @Override
            public Optional<BetDecision> decide(BetContext context) {
                return Optional.of(new BetDecision(1, 500_000L));
            }
            @Override
            public void onRoundEnd(RoundResult result) { /* no-op */ }
        };
        BettingStrategyFactory factory = mock(BettingStrategyFactory.class);
        when(factory.create(StrategyId.RANDOM)).thenReturn(fixed);

        TaiXiuGameBot b = new TaiXiuGameBot();
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(cfg);
        b.setStrategyFactory(factory);
        b.setTaiXiuMessageTypes(new MiniGameTaiXiuMessageTypes());
        b.setRandom(new Random(0L));
        return b;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bot == null) return;
        ScheduledExecutorService w = (ScheduledExecutorService) readField("watchdogScheduler");
        if (w != null) w.shutdownNow();
    }

    @Test
    @DisplayName("initializeSubclass() does not NPE when game.getOffset() is null (staging repro)")
    void initializeSubclass_nullOffset_doesNotThrow() {
        bot = buildBotWithNullOffset();

        // Before the fix this threw NPE on the game.getOffset() auto-unbox at
        // BettingMiniGameBot.initializeSubclass — the exact staging failure that
        // made a Tai Xiu group create 0 bots.
        assertThatCode(bot::initializeSubclass).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fixed CMD seams stay literal after null-offset init (offset never leaks)")
    void fixedCmdSeams_unaffectedByNullOffset() throws Exception {
        bot = buildBotWithNullOffset();
        bot.initializeSubclass();

        // The 0 fallback is a dead store for Tai Xiu: every CMD seam returns the
        // bare fixed constant, never CODE + offset. If the fallback ever leaked
        // into the inherited derivation these would shift.
        assertThat(invokeIntSeam("subscribeCmd")).isEqualTo(1005);
        assertThat(invokeIntSeam("startGameCmd")).isEqualTo(1002);
        assertThat(invokeIntSeam("endGameCmd")).isEqualTo(1004);
    }

    private int invokeIntSeam(String name) throws Exception {
        Method m = BettingMiniGameBot.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return (int) m.invoke(bot);
    }

    private Object readField(String name) throws Exception {
        java.lang.reflect.Field f = findField(bot.getClass(), name);
        f.setAccessible(true);
        return f.get(bot);
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // walk up the hierarchy
            }
        }
        throw new IllegalStateException("field not found: " + name);
    }
}
