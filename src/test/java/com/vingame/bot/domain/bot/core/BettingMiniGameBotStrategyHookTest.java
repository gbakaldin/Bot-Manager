package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BetDecision;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.BotMemory;
import com.vingame.bot.domain.bot.strategy.RoundResult;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.util.BettingMiniGameState;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the contract from Phase 5 of {@code docs/plans/BETTING_STRATEGIES.md}
 * Implementation Note 4: {@link BettingMiniGameBot#onEndGame} routes the
 * finalized {@link RoundResult} into {@code strategy.onRoundEnd(...)} AFTER
 * {@code BotMemory.completeRound}, and in v1 the {@code winningOption} is
 * always {@link Optional#empty()} (no {@code HasWinningOption} marker exists
 * yet).
 *
 * <p>Also pins the v1 invariant that {@link BotMemory#snapshotGlobalRecentWins}
 * remains empty as a consequence of always passing {@code Optional.empty()} —
 * future strategies that depend on global wins will fail loudly until the
 * winning-option extractors are wired per Implementation Note 4.
 */
@DisplayName("BettingMiniGameBot strategy.onRoundEnd hook (Phase 5 / Note 4)")
class BettingMiniGameBotStrategyHookTest {

    private BettingMiniGameBot bot;
    private RecordingStrategy strategy;

    @BeforeEach
    void setUp() {
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) affinities.put(i, 1);
        Game game = Game.builder()
                .id("g-hook").name("BauCua").pluginName("BauCua")
                .offset(2000).optionAffinities(affinities).build();

        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100).maxBet(1000).betIncrement(100)
                .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(3)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("hookbot").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame3").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .strategyId(StrategyId.RANDOM)
                .build();

        strategy = new RecordingStrategy();
        // Wire a factory that returns the spy strategy. The bot's initializeSubclass
        // calls factory.create(...), so this guarantees the recording strategy is
        // what gets invoked when onEndGame fires.
        BettingStrategyFactory factory = mock(BettingStrategyFactory.class);
        when(factory.create(StrategyId.RANDOM, 0L)).thenReturn(strategy);
        // Match any seed (the bot derives a per-bot seed) — return the same
        // recording strategy regardless.
        when(factory.create(org.mockito.ArgumentMatchers.eq(StrategyId.RANDOM),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(strategy);

        bot = new BettingMiniGameBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setStrategyFactory(factory);
        bot.setRandom(new Random(0L));
        bot.initializeSubclass();

        // Seed the balance cache so onNewSession in onEndGame returns from cache.
        seedLong("lastFetchedBalance", 50_000_000L);
        seedAtomic("expectedCurrentBalance", 50_000_000L);
        setField("gameState", BettingMiniGameState.BET);
    }

    @AfterEach
    void tearDown() throws Exception {
        ScheduledExecutorService w = (ScheduledExecutorService) readField("watchdogScheduler");
        if (w != null) w.shutdownNow();
        ScheduledExecutorService s = (ScheduledExecutorService) readField("scheduler");
        if (s != null) s.shutdownNow();
    }

    @Test
    @DisplayName("onEndGame calls strategy.onRoundEnd exactly once per round")
    void onRoundEndCalledOncePerRound() throws Exception {
        invokeOnStartGame(100L);
        invokeOnEndGame(new StubEndGame(100L, bot.getUserName(), 500L));

        assertThat(strategy.roundsObserved).hasSize(1);
        RoundResult observed = strategy.roundsObserved.get(0);
        assertThat(observed.sessionId()).isEqualTo(100L);
        assertThat(observed.payout()).isEqualTo(500L);
    }

    @Test
    @DisplayName("v1: strategy.onRoundEnd is called with winningOption=Optional.empty() (Implementation Note 4)")
    void winningOptionIsEmptyInV1() throws Exception {
        invokeOnStartGame(200L);
        invokeOnEndGame(new StubEndGame(200L, bot.getUserName(), 0L));

        assertThat(strategy.roundsObserved).hasSize(1);
        // Plan Implementation Note 4: no HasWinningOption marker exists; v1 ships
        // with Optional.empty for every round. A future strategy that requires
        // the winning option will fail loudly here as a signal to add the marker.
        assertThat(strategy.roundsObserved.get(0).winningOption()).isEmpty();
    }

    @Test
    @DisplayName("v1: BotMemory.snapshotGlobalRecentWins stays empty across many rounds")
    void globalRecentWinsStaysEmptyInV1() throws Exception {
        for (long sid = 300L; sid < 305L; sid++) {
            invokeOnStartGame(sid);
            invokeOnEndGame(new StubEndGame(sid, bot.getUserName(), 0L));
        }
        // Because BettingMiniGameBot.onEndGame passes Optional.empty into
        // BotMemory.recordGlobalWin (a no-op when the winningOption is empty —
        // see BotMemory#recordGlobalWin), the snapshot remains empty in v1.
        assertThat(bot.getMemory().snapshotGlobalRecentWins()).isEmpty();
    }

    @Test
    @DisplayName("onRoundEnd is called AFTER BotMemory.completeRound (RoundResult is pushed to lastResults first)")
    void onRoundEndCalledAfterCompleteRound() throws Exception {
        invokeOnStartGame(400L);
        // At the moment strategy.onRoundEnd fires, the RoundResult must already
        // be present on lastResults — the bot calls completeRound first, then
        // onRoundEnd. The recording strategy captures the size of lastResults
        // at the instant of the callback.
        strategy.observeMemoryFrom(bot.getMemory());
        invokeOnEndGame(new StubEndGame(400L, bot.getUserName(), 0L));

        assertThat(strategy.lastResultsSnapshotAtCallback).hasSize(1);
        assertThat(strategy.lastResultsSnapshotAtCallback.get(0).sessionId()).isEqualTo(400L);
    }

    @Test
    @DisplayName("Sequence of rounds delivers one onRoundEnd per round, in sessionId order")
    void multipleRoundsDeliverInOrder() throws Exception {
        for (long sid = 500L; sid < 505L; sid++) {
            invokeOnStartGame(sid);
            invokeOnEndGame(new StubEndGame(sid, bot.getUserName(), sid));
        }
        assertThat(strategy.roundsObserved).hasSize(5);
        for (int i = 0; i < 5; i++) {
            long expected = 500L + i;
            assertThat(strategy.roundsObserved.get(i).sessionId()).isEqualTo(expected);
            assertThat(strategy.roundsObserved.get(i).payout()).isEqualTo(expected);
        }
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
    }

    private void invokeOnEndGame(EndGameMessage msg) throws Exception {
        ActionResponseMessage<EndGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        Method m = BettingMiniGameBot.class.getDeclaredMethod("onEndGame", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(bot, resp);
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

    private void setField(String name, Object value) {
        try {
            Field f;
            try {
                f = BettingMiniGameBot.class.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                f = Bot.class.getDeclaredField(name);
            }
            f.setAccessible(true);
            f.set(bot, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    /**
     * Recording strategy that captures every {@link #onRoundEnd} invocation in
     * an in-order list. {@link #decide} always returns empty (so this fixture
     * does not interact with the bet path — only the round-end callback).
     */
    private static final class RecordingStrategy implements BettingStrategy {
        final List<RoundResult> roundsObserved = new ArrayList<>();
        List<RoundResult> lastResultsSnapshotAtCallback = List.of();
        private BotMemory observedMemory;

        void observeMemoryFrom(BotMemory memory) {
            this.observedMemory = memory;
        }

        @Override
        public Optional<BetDecision> decide(BetContext ctx) {
            return Optional.empty();
        }

        @Override
        public void onRoundEnd(RoundResult result) {
            roundsObserved.add(result);
            if (observedMemory != null) {
                // Capture lastResults at the moment the callback fires; this
                // pins that the bot called BotMemory.completeRound *before*
                // strategy.onRoundEnd (Implementation Note 4 ordering).
                lastResultsSnapshotAtCallback = observedMemory.snapshotLastResults();
            }
        }
    }

    /**
     * Minimal {@link EndGameMessage} stub carrying a {@code sid} and a single
     * bot's winnings. Implements {@link HasBotWinnings} so onEndGame's
     * extraction path picks up the {@code payout} fed into
     * {@code BotMemory.completeRound} → the {@link RoundResult} that flows into
     * {@code strategy.onRoundEnd}.
     */
    private static class StubEndGame extends EndGameMessage implements HasBotWinnings {
        private final long sessionId;
        private final String winningsUser;
        private final long winnings;

        StubEndGame(long sessionId, String winningsUser, long winnings) {
            super(0);
            this.sessionId = sessionId;
            this.winningsUser = winningsUser;
            this.winnings = winnings;
        }

        @Override
        public long getSessionId() {
            return sessionId;
        }

        @Override
        public long winningsFor(String userName) {
            return userName != null && userName.equals(winningsUser) ? winnings : 0L;
        }
    }
}
