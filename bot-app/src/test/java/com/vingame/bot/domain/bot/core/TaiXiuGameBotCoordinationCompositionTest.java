package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.coordination.BetCoordinator;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.request.Bet;
import com.vingame.bot.domain.bot.message.taixiu.MiniGameTaiXiuMessageTypes;
import com.vingame.bot.domain.bot.message.taixiu.TaiXiuStartGameMessage;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BET_COORDINATION AD-2 composition: the coordinator gate runs <b>after</b>
 * {@link TaiXiuGameBot#decideBet}, so it only ever sees the TaiXiu single-entry
 * <b>locked</b> option — never the entry the strategy currently wants. This test
 * proves the two features compose without either knowing about the other:
 *
 * <ul>
 *   <li>the coordinator commits stake to the LOCKED option (Tài), even when the
 *       strategy has flipped to Xỉu — Xỉu's committed stake stays 0;</li>
 *   <li>a REJECT on the locked option skips the tick — the coordinator never
 *       "escapes" the lock by approving the strategy's wanted (other) option,
 *       which would have had free budget.</li>
 * </ul>
 */
@DisplayName("TaiXiuGameBot × BetCoordinator composition (AD-2: gate after decideBet)")
class TaiXiuGameBotCoordinationCompositionTest {

    private static final long START_BALANCE = 50_000_000L;
    private static final long BASE_SID = 2670572L;

    private TaiXiuGameBot bot;
    private MutableStrategy strategy;

    /** Strategy whose desired entry + amount can be flipped between ticks. */
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

    private TaiXiuGameBot newBot() throws Exception {
        Game game = Game.builder()
                .id("g-taixiu").name("TaiXiu").pluginName("taixiuPlugin")
                .numberOfOptions(2)
                .build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100_000).maxBet(2_000_000).betIncrement(100_000)
                .maxTotalBetPerRound(10_000_000).minBetsPerRound(1).maxBetsPerRound(5)
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

        strategy = new MutableStrategy(TaiXiuGameBot.TAI_EID, 100_000L);
        BettingStrategyFactory factory = mock(BettingStrategyFactory.class);
        when(factory.create(StrategyId.RANDOM)).thenReturn(strategy);

        TaiXiuGameBot b = new TaiXiuGameBot();
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(cfg);
        b.setStrategyFactory(factory);
        b.setTaiXiuMessageTypes(new MiniGameTaiXiuMessageTypes());
        b.setRandom(new Random(0L));
        b.setMetrics(mock(BotMetrics.class));
        b.initializeSubclass();

        seedLong(b, "lastFetchedBalance", START_BALANCE);
        seedAtomic(b, "expectedCurrentBalance", START_BALANCE);
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
    @DisplayName("coordinator commits to the LOCKED entry (Tài), never the strategy's flipped Xỉu")
    void coordinatorSeesLockedEntry() throws Exception {
        bot = newBot();
        // Symmetric budgets so BOTH options have ample headroom. If the coordinator
        // (wrongly) saw the strategy's wanted option it would still approve, but the
        // committed stake would land on the WRONG option — that's what we assert against.
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        affinities.put(TaiXiuGameBot.TAI_EID, 1); // 1
        affinities.put(TaiXiuGameBot.XIU_EID, 1); // 2
        // cap 1_000_000 → per-option budget 500_000; minBet/increment match the group.
        BetCoordinator coordinator = new BetCoordinator(affinities, 1_000_000L, 100_000L, 100_000L);
        bot.setCoordinator(coordinator);

        openRound(BASE_SID);

        // First bet: strategy wants Tài (unconstrained first pick) → lock onto Tài.
        strategy.optionId = TaiXiuGameBot.TAI_EID;
        strategy.amount = 100_000L;
        Bet.BetData first = placeBet();
        assertThat(first.getEid()).as("first bet is Tài").isEqualTo(1L);

        // Second tick: strategy flips to Xỉu. The lock remaps back to Tài, and the
        // coordinator — running AFTER decideBet — reserves against Tài (option 1).
        strategy.optionId = TaiXiuGameBot.XIU_EID;
        strategy.amount = 100_000L;
        Bet.BetData second = placeBet();
        assertThat(second.getEid()).as("still locked to Tài with coordinator wired").isEqualTo(1L);

        BetCoordinator.Snapshot snap = coordinator.snapshot();
        long taiCommitted = optionCommitted(snap, TaiXiuGameBot.TAI_EID);
        long xiuCommitted = optionCommitted(snap, TaiXiuGameBot.XIU_EID);
        assertThat(taiCommitted)
                .as("both bets committed to the LOCKED Tài option")
                .isEqualTo(200_000L);
        assertThat(xiuCommitted)
                .as("the coordinator never saw the strategy's flipped Xỉu — 0 committed there")
                .isZero();
        assertThat(snap.approveCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("REJECT on the locked entry skips the tick — does not escape the lock to the free option")
    void rejectOnLockedEntrySkipsTick() throws Exception {
        bot = newBot();
        // Asymmetric budgets: Tài (locked) budget is exactly one bet; Xỉu has plenty.
        // weight 1:9 over cap 1_000_000 → Tài budget = 100_000, Xỉu budget = 900_000.
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        affinities.put(TaiXiuGameBot.TAI_EID, 1);
        affinities.put(TaiXiuGameBot.XIU_EID, 9);
        BetCoordinator coordinator = new BetCoordinator(affinities, 1_000_000L, 100_000L, 100_000L);
        bot.setCoordinator(coordinator);

        openRound(BASE_SID);

        // First bet: Tài, 100_000 → exactly fills Tài's budget (APPROVE).
        strategy.optionId = TaiXiuGameBot.TAI_EID;
        strategy.amount = 100_000L;
        assertThat(placeBet().getEid()).isEqualTo(1L);
        assertThat(optionCommitted(coordinator.snapshot(), TaiXiuGameBot.TAI_EID)).isEqualTo(100_000L);

        // Second tick: strategy flips to Xỉu (which has 900_000 free). The lock remaps
        // to Tài, whose budget is now exhausted → the coordinator REJECTs → tick skipped.
        // A coordinator that saw Xỉu would have APPROVED — this asserts it did not.
        strategy.optionId = TaiXiuGameBot.XIU_EID;
        strategy.amount = 100_000L;
        assertThat(invokeBetCondition())
                .as("REJECT on the exhausted locked Tài budget → skip the tick")
                .isFalse();
        assertThat(pendingDecision()).as("nothing parked on REJECT").isEmpty();

        BetCoordinator.Snapshot snap = coordinator.snapshot();
        assertThat(optionCommitted(snap, TaiXiuGameBot.XIU_EID))
                .as("Xỉu was never reserved — the lock was not escaped")
                .isZero();
        assertThat(snap.rejectCount()).isEqualTo(1L);
        assertThat(snap.currentAggregateStake()).isEqualTo(100_000L);
    }

    /* ---- helpers ---- */

    private static long optionCommitted(BetCoordinator.Snapshot snap, int optionId) {
        return snap.options().stream()
                .filter(o -> o.optionId() == optionId)
                .mapToLong(BetCoordinator.OptionSnapshot::committedStake)
                .findFirst().orElseThrow();
    }

    private void openRound(long sid) throws Exception {
        invokeHandler("onSubscribe", parseFixture("subscribe.json"));
        invokeHandler("onStartGame", startGameWithSid(sid));
        setRemainingTime(50_000L);
        setField("gameState", BettingMiniGameState.BET);
    }

    private Bet.BetData placeBet() throws Exception {
        assertThat(invokeBetCondition()).as("bet gate open").isTrue();
        ActionRequestMessage out = invokeBetSupplier();
        return (Bet.BetData) readBody(out);
    }

    private StartGameMessage startGameWithSid(long sid) throws Exception {
        TaiXiuStartGameMessage msg = (TaiXiuStartGameMessage) parseFixture("startGame.json");
        msg.setSid(sid);
        return msg;
    }

    private BettingMiniMessage parseFixture(String name) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new MiniGameTaiXiuMessageTypes().getTypeRegistrations());
        try (var in = getClass().getResourceAsStream("/messages/taixiu/" + name)) {
            assertThat(in).as("fixture /messages/taixiu/" + name).isNotNull();
            return mapper.readValue(in.readAllBytes(), BettingMiniMessage.class);
        }
    }

    private void invokeHandler(String name, BettingMiniMessage payload) throws Exception {
        ActionResponseMessage<BettingMiniMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, payload);
        Method m = BettingMiniGameBot.class.getDeclaredMethod(name, ActionResponseMessage.class);
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

    @SuppressWarnings("unchecked")
    private Optional<BetDecision> pendingDecision() throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField("pendingDecision");
        f.setAccessible(true);
        return ((AtomicReference<Optional<BetDecision>>) f.get(bot)).get();
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
