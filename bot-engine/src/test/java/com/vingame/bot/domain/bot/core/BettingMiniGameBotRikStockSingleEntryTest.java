package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.g3.rik.RikGameMessageTypes;
import com.vingame.bot.domain.bot.message.request.RikStockBet;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BetDecision;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.RoundResult;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.util.BettingMiniGameState;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 114 {@code stockPlugin} binds a player to the entry of their first bet in a round and
 * answers any later bet on the other side with {@code BETTING_INVALID} (one backend
 * exception per bet). {@code RikStockRequest.singleEntryPerRound()} switches on the
 * {@code decideBet} lock, so a strategy that flips side mid-round is remapped onto the
 * locked entry, keeping its amount, and the lock resets at the next StartGame.
 */
@DisplayName("114 stockPlugin: single entry per round")
class BettingMiniGameBotRikStockSingleEntryTest {

    private BettingMiniGameBot bot;
    private MutableStrategy strategy;

    private static final class MutableStrategy implements BettingStrategy {
        volatile int optionId;
        volatile long amount;

        MutableStrategy(int optionId, long amount) {
            this.optionId = optionId;
            this.amount = amount;
        }

        @Override
        public Optional<BetDecision> decide(BetContext context) {
            return Optional.of(new BetDecision(optionId, amount));
        }

        @Override
        public void onRoundEnd(RoundResult result) { /* no-op */ }
    }

    private BettingMiniGameBot newBot() {
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        affinities.put(0, 1);
        affinities.put(1, 1);
        Game game = Game.builder()
                .id("g-stock").name("Coins").pluginName("stockPlugin")
                .offset(10000).optionAffinities(affinities).build();

        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100).maxBet(1000).betIncrement(100)
                .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(5)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("rikcoins1").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .strategyId(StrategyId.RANDOM.name())
                .build();

        strategy = new MutableStrategy(0, 200L);
        BettingStrategyFactory factory = mock(BettingStrategyFactory.class);
        when(factory.create(StrategyId.RANDOM.name())).thenReturn(strategy);

        BettingMiniGameBot b = new BettingMiniGameBot();
        b.setMessageTypes(new RikGameMessageTypes());
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(cfg);
        b.setStrategyFactory(factory);
        b.setRandom(new Random(0L));
        b.setMetrics(mock(BotMetrics.class));
        b.initializeSubclass();

        seedLong(b, "lastFetchedBalance", 50_000_000L);
        seedAtomic(b, "expectedCurrentBalance", 50_000_000L);
        return b;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bot == null) return;
        ScheduledExecutorService w = (ScheduledExecutorService) readField("watchdogScheduler");
        if (w != null) w.shutdownNow();
        ScheduledExecutorService s = (ScheduledExecutorService) readField("scheduler");
        if (s != null) s.shutdownNow();
    }

    @Test
    @DisplayName("a mid-round side flip is remapped onto the first bet's entry, amount kept")
    void laterBetsInARoundStayOnTheFirstEntry() throws Exception {
        bot = newBot();
        invokeOnStartGame(100L);
        setRemainingTime(50_000L);
        setField("gameState", BettingMiniGameState.BET);

        strategy.optionId = 1;
        strategy.amount = 1_000L;
        assertThat(eidOf(placeBet())).as("first bet: strategy's entry passes through").isEqualTo(1L);

        strategy.optionId = 0;
        strategy.amount = 5_000L;
        RikStockBet.BetData second = placeBet();
        assertThat(second.getEid()).as("second bet, other side proposed").isEqualTo(1L);
        assertThat(second.getV()).as("strategy's amount is preserved").isEqualTo(5_000L);

        assertThat(bot.getMemory().snapshotCurrentRoundBets())
                .as("the round accumulator only ever sees the locked entry")
                .containsOnlyKeys(1);
    }

    @Test
    @DisplayName("the lock resets at the next StartGame")
    void lockResetsOnNewRound() throws Exception {
        bot = newBot();
        invokeOnStartGame(100L);
        setRemainingTime(50_000L);
        setField("gameState", BettingMiniGameState.BET);

        strategy.optionId = 1;
        assertThat(eidOf(placeBet())).isEqualTo(1L);

        invokeOnStartGame(101L);
        setRemainingTime(50_000L);
        setField("gameState", BettingMiniGameState.BET);

        strategy.optionId = 0;
        assertThat(eidOf(placeBet())).as("first bet of the new round is unconstrained").isEqualTo(0L);
    }

    /* ---- helpers ---- */

    private RikStockBet.BetData placeBet() throws Exception {
        assertThat(invokeBetCondition()).as("bet gate open").isTrue();
        ActionRequestMessage out = invokeBetSupplier();
        assertThat(out).isInstanceOf(RikStockBet.class);
        return (RikStockBet.BetData) readBody(out);
    }

    private long eidOf(RikStockBet.BetData data) {
        return data.getEid();
    }

    private void invokeOnStartGame(long sid) throws Exception {
        StartGameMessage msg = mock(StartGameMessage.class);
        when(msg.getSessionId()).thenReturn(sid);
        ActionResponseMessage<StartGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        Method m = BettingMiniGameBot.class.getDeclaredMethod("onStartGame", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(bot, resp);
    }

    @SuppressWarnings("unchecked")
    private boolean invokeBetCondition() throws Exception {
        Method m = BettingMiniGameBot.class.getDeclaredMethod("betCondition");
        m.setAccessible(true);
        Supplier<Boolean> cond = (Supplier<Boolean>) m.invoke(bot);
        return cond.get();
    }

    @SuppressWarnings("unchecked")
    private ActionRequestMessage invokeBetSupplier() throws Exception {
        Method m = BettingMiniGameBot.class.getDeclaredMethod("bet");
        m.setAccessible(true);
        Supplier<ActionRequestMessage> sup = (Supplier<ActionRequestMessage>) m.invoke(bot);
        return sup.get();
    }

    private static Body readBody(ActionRequestMessage msg) throws Exception {
        Field f = ActionRequestMessage.class.getDeclaredField("body");
        f.setAccessible(true);
        return (Body) f.get(msg);
    }

    private void setRemainingTime(long ms) throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField("remainingTime");
        f.setAccessible(true);
        ((AtomicLong) f.get(bot)).set(ms);
    }

    private Object readField(String name) throws Exception {
        Field f = findField(name);
        f.setAccessible(true);
        return f.get(bot);
    }

    private void setField(String name, Object value) {
        try {
            Field f = findField(name);
            f.setAccessible(true);
            f.set(bot, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Field findField(String name) throws NoSuchFieldException {
        Class<?> c = bot.getClass();
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
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
