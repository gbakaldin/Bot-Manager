package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.coordination.BetCoordinator;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BetDecision;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.RoundResult;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.util.BettingMiniGameState;
import com.vingame.bot.domain.bot.util.SessionIdStore;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Coverage for the BET_COORDINATION Phase 3 bot seam (AD-2/AD-9): the
 * coordinator gate wired into {@code betCondition()} immediately after
 * {@code decideBet}.
 *
 * <p>Three cases:
 * <ul>
 *   <li><b>TRIM</b> — a wired coordinator reduces the parked (and therefore
 *       sent) amount to the grid-aligned remaining budget.</li>
 *   <li><b>REJECT</b> — a wired coordinator with no headroom makes the
 *       condition return {@code false} (skip the tick), parking nothing.</li>
 *   <li><b>Off-path identity</b> — with {@code coordinator == null} the
 *       proposed decision passes through unchanged (byte-for-byte today).</li>
 * </ul>
 */
@DisplayName("BettingMiniGameBot coordination seam (BET_COORDINATION Phase 3)")
class BettingMiniGameBotCoordinationSeamTest {

    private BettingMiniGameBot bot;
    private ControlledStrategy strategy;

    @BeforeEach
    void setUp() throws Exception {
        // 4 equal-weight options; minBet=100, betIncrement=100.
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) affinities.put(i, 1);
        Game game = Game.builder()
                .id("g-coord").name("BauCua").pluginName("BauCua")
                .offset(2000).optionAffinities(affinities).build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100).maxBet(1000).betIncrement(100)
                .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(5)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("coordbot").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame3").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .strategyId(StrategyId.RANDOM)
                .build();

        strategy = new ControlledStrategy();
        BettingStrategyFactory factory = mock(BettingStrategyFactory.class);
        when(factory.create(StrategyId.RANDOM)).thenReturn(strategy);

        bot = new BettingMiniGameBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setStrategyFactory(factory);
        bot.setRandom(new Random(0xC00D1L));
        bot.initializeSubclass();

        seedLong("lastFetchedBalance", 50_000_000L);
        seedAtomic("expectedCurrentBalance", 50_000_000L);
    }

    @AfterEach
    void tearDown() throws Exception {
        ScheduledExecutorService w = (ScheduledExecutorService) readField("watchdogScheduler");
        if (w != null) w.shutdownNow();
        ScheduledExecutorService s = (ScheduledExecutorService) readField("scheduler");
        if (s != null) s.shutdownNow();
    }

    @Test
    @DisplayName("TRIM: a wired coordinator reduces the parked and sent amount to the grid-aligned budget")
    void coordinatorTrimReducesSentAmount() throws Exception {
        // cap=1000 over 4 equal options → per-option budget floor(1/4*1000)=250.
        // Proposing 500 on option 0: allow=min(500,250,1000)=250, grid-align down
        // to 100 + floor((250-100)/100)*100 = 200. Expect TRIM to 200.
        BetCoordinator coordinator = new BetCoordinator(affinities(), 1000L, 100L, 100L);
        bot.setCoordinator(coordinator);

        openRound(5001L);
        strategy.nextDecision = Optional.of(new BetDecision(0, 500L));

        Supplier<Boolean> condition = condition();
        assertThat(condition.get()).isTrue();

        BetDecision parked = pendingDecision().get().orElseThrow();
        assertThat(parked.optionId()).isEqualTo(0);
        assertThat(parked.amount())
                .as("coordinator must trim 500 down to the grid-aligned option budget (200)")
                .isEqualTo(200L);

        // End-to-end: the supplier sends the trimmed amount (creditBalance/
        // totalBetAmount reflect 200, not 500).
        ((SessionIdStore) readField("sidStore")).set(5001L);
        Supplier<ActionRequestMessage> supplier = supplier();
        assertThat(supplier.get()).isNotNull();
        assertThat(((AtomicLong) readField("totalBetAmount")).get())
                .as("only the trimmed amount is sent/credited")
                .isEqualTo(200L);
    }

    @Test
    @DisplayName("REJECT: a wired coordinator with no headroom makes the condition skip the tick")
    void coordinatorRejectSkipsTick() throws Exception {
        // cap=0 → every per-option budget is 0 → every reserve rejects.
        BetCoordinator coordinator = new BetCoordinator(affinities(), 0L, 100L, 100L);
        bot.setCoordinator(coordinator);

        openRound(6001L);
        strategy.nextDecision = Optional.of(new BetDecision(2, 300L));
        pendingDecision().set(Optional.empty());

        Supplier<Boolean> condition = condition();
        assertThat(condition.get())
                .as("REJECT → condition returns false (skip this tick)")
                .isFalse();
        assertThat(pendingDecision().get())
                .as("nothing is parked on REJECT")
                .isEmpty();
    }

    @Test
    @DisplayName("Off-path: with coordinator == null the proposed decision passes through unchanged")
    void nullCoordinatorIsIdentity() throws Exception {
        // No coordinator wired — applyCoordination is identity (AD-9).
        openRound(7001L);
        BetDecision proposed = new BetDecision(3, 400L);
        strategy.nextDecision = Optional.of(proposed);

        Supplier<Boolean> condition = condition();
        assertThat(condition.get()).isTrue();
        assertThat(pendingDecision().get())
                .as("null coordinator → decision unchanged")
                .contains(proposed);
    }

    /* ---- helpers ---- */

    private Map<Integer, Integer> affinities() {
        Map<Integer, Integer> a = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) a.put(i, 1);
        return a;
    }

    private void openRound(long sid) throws Exception {
        invokeOnStartGame(sid);
        setField("gameState", BettingMiniGameState.BET);
        ((AtomicLong) readField("remainingTime")).set(10_000L);
        ((SessionIdStore) readField("sidStore")).set(sid);
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
    private Supplier<Boolean> condition() throws Exception {
        Method m = BettingMiniGameBot.class.getDeclaredMethod("betCondition");
        m.setAccessible(true);
        return (Supplier<Boolean>) m.invoke(bot);
    }

    @SuppressWarnings("unchecked")
    private Supplier<ActionRequestMessage> supplier() throws Exception {
        Method m = BettingMiniGameBot.class.getDeclaredMethod("bet");
        m.setAccessible(true);
        return (Supplier<ActionRequestMessage>) m.invoke(bot);
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<Optional<BetDecision>> pendingDecision() throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField("pendingDecision");
        f.setAccessible(true);
        return (AtomicReference<Optional<BetDecision>>) f.get(bot);
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

    private void setField(String name, Object value) throws Exception {
        Field f;
        try {
            f = BettingMiniGameBot.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = Bot.class.getDeclaredField(name);
        }
        f.setAccessible(true);
        f.set(bot, value);
    }

    private void seedLong(String name, long value) {
        try {
            Field f = Bot.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setLong(bot, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void seedAtomic(String name, long value) {
        try {
            Field f = Bot.class.getDeclaredField(name);
            f.setAccessible(true);
            ((AtomicLong) f.get(bot)).set(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Strategy that returns whatever decision the test parks in {@link #nextDecision}. */
    private static final class ControlledStrategy implements BettingStrategy {
        Optional<BetDecision> nextDecision = Optional.empty();
        int decideInvocations = 0;
        final List<RoundResult> rounds = new ArrayList<>();

        @Override
        public Optional<BetDecision> decide(BetContext ctx) {
            decideInvocations++;
            return nextDecision;
        }

        @Override
        public void onRoundEnd(RoundResult result) {
            rounds.add(result);
        }
    }
}
