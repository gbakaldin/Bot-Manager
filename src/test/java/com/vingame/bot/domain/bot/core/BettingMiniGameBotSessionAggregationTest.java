package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.util.BettingMiniGameState;
import com.vingame.bot.domain.bot.util.SessionIdStore;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * STRATEGY_DECISION_AGGREGATION Phase 1 verification for the betting/Tai Xiu side:
 * {@link BettingMiniGameBot}'s {@code bet()} supplier now plumbs the chosen
 * {@code optionId} into {@link SessionAggregationService#recordBet} — the option
 * that seeds the per-window histogram — alongside the staked amount. This is the
 * betting analogue of {@code SlotMachineBotSessionAggregationTest}'s
 * {@code recordSpin} plumbing check; the aggregator is verified via a mock so the
 * assertion is on the exact value flowing out of the bot's decision tap.
 */
@DisplayName("BettingMiniGameBot session-aggregation option plumbing (Phase 1)")
class BettingMiniGameBotSessionAggregationTest {

    private BettingMiniGameBot bot;
    private SessionAggregationService aggregator;

    @BeforeEach
    void setUp() throws Exception {
        BotCredentials credentials = BotCredentials.builder()
                .username("decidebot1").password("pw").fingerprint("fp").build();

        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) affinities.put(i, 1);
        Game game = Game.builder()
                .id("g-dec").name("BauCua").pluginName("BauCua")
                .offset(2000).optionAffinities(affinities).build();

        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100).maxBet(1000).betIncrement(100)
                .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(3)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(credentials)
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame3").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .build();

        bot = new BettingMiniGameBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setRandom(mock(Random.class));
        bot.initializeSubclass();

        aggregator = mock(SessionAggregationService.class);
        bot.setSessionAggregator(aggregator);

        seedLong(bot, "lastFetchedBalance", 50_000_000L);
        seedAtomic(bot, "expectedCurrentBalance", 50_000_000L);
        setField(bot, "gameState", BettingMiniGameState.BET);
    }

    @AfterEach
    void tearDown() throws Exception {
        ScheduledExecutorService w = (ScheduledExecutorService) readField(bot, "watchdogScheduler");
        if (w != null) w.shutdownNow();
        ScheduledExecutorService s = (ScheduledExecutorService) readField(bot, "scheduler");
        if (s != null) s.shutdownNow();
    }

    @Test
    @DisplayName("bet() feeds recordBet with the chosen optionId (the histogram key) and the staked amount")
    void bet_feedsRecordBetWithOptionAndAmount() throws Exception {
        // Deterministic decision (mirrors BettingMiniGameBotMemoryTest):
        //  - nextInt(100)=50 with betSkipPercentage=0 -> never skip
        //  - nextInt(10)=2 -> step 2 -> 100 + 2*100 = 300 staked
        //  - nextInt(6)=3 -> option id 3
        Random rng = mock(Random.class);
        when(rng.nextInt(100)).thenReturn(50);
        when(rng.nextInt(10)).thenReturn(2);
        when(rng.nextInt(6)).thenReturn(3);
        bot.setRandom(rng);

        setField(bot, "gameState", BettingMiniGameState.BET);
        SessionIdStore sidStore = (SessionIdStore) readField(bot, "sidStore");
        sidStore.set(555L);
        ((AtomicLong) readField(bot, "remainingTime")).set(10_000L);

        Method conditionMethod = BettingMiniGameBot.class.getDeclaredMethod("betCondition");
        conditionMethod.setAccessible(true);
        Method betMethod = BettingMiniGameBot.class.getDeclaredMethod("bet");
        betMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        Supplier<Boolean> cond = (Supplier<Boolean>) conditionMethod.invoke(bot);
        @SuppressWarnings("unchecked")
        Supplier<?> sup = (Supplier<?>) betMethod.invoke(bot);

        cond.get(); // parks the decision (option 3, amount 300)
        sup.get();  // pops it, feeds the aggregator, sends the bet

        // The option (3) reaches the histogram tap with the right value, next to sid + amount.
        verify(aggregator).recordBet(555L, "decidebot1", 3, 300L);
    }

    /* ----- helpers ----- */

    private static Object readField(Object target, String name) throws Exception {
        Field f;
        try {
            f = target.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = target.getClass().getSuperclass().getDeclaredField(name);
        }
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f;
            try {
                f = target.getClass().getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                f = target.getClass().getSuperclass().getDeclaredField(name);
            }
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void seedLong(Object target, String name, long value) {
        try {
            Field f = Bot.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setLong(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void seedAtomic(Object target, String name, long value) {
        try {
            Field f = Bot.class.getDeclaredField(name);
            f.setAccessible(true);
            ((AtomicLong) f.get(target)).set(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
