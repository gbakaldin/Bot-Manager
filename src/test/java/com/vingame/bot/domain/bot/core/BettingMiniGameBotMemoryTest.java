package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.strategy.BotMemory;
import com.vingame.bot.domain.bot.strategy.RoundResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2 integration-style verification that {@link BettingMiniGameBot}'s
 * onStartGame / onEndGame / bet() path correctly populates {@link BotMemory}.
 * <p>
 * Mirrors {@link BettingMiniGameBotTipDispatchTest}'s style — exercises the bot
 * through reflection on private methods so the dispatch is the real one. The
 * marker-interface metric dispatch is covered separately; this test pins the
 * <i>memory plumbing</i> contract (bet→result correlation, lastResults push).
 */
@DisplayName("BettingMiniGameBot x BotMemory (integration, Phase 2)")
class BettingMiniGameBotMemoryTest {

    private BettingMiniGameBot bot;

    @BeforeEach
    void setUp() {
        BotCredentials credentials = BotCredentials.builder()
                .username("memorybot1").password("pw").fingerprint("fp").build();

        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) affinities.put(i, 1);
        Game game = Game.builder()
                .id("g-mem").name("BauCua").pluginName("BauCua")
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

        // Seed balance cache so onNewSession inside onEndGame returns from cache.
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
    @DisplayName("initializeSubclass constructs a BotMemory with default capacity")
    void initializeSubclassBuildsMemory() {
        BotMemory mem = bot.getMemory();
        assertThat(mem).isNotNull();
        assertThat(mem.getCapacity()).isEqualTo(BotMemory.DEFAULT_CAPACITY);
        assertThat(mem.getGame()).isSameAs(bot.getConfiguration().getGame());
    }

    @Test
    @DisplayName("onStartGame opens a round; onEndGame finalizes a RoundResult onto lastResults")
    void startThenEndProducesResult() throws Exception {
        invokeOnStartGame(bot, 42069L);
        // Memory should have an in-flight round with the new sessionId
        assertThat(bot.getMemory().getCurrentRound().getSessionId()).isEqualTo(42069L);

        // Feed an EndGame with HasBotWinnings carrying a payout for this bot
        EndGameMessage end = new StubEndGame(42069L, bot.getUserName(), 750L);
        invokeOnEndGame(bot, end);

        List<RoundResult> results = bot.getMemory().snapshotLastResults();
        assertThat(results).hasSize(1);
        RoundResult result = results.get(0);
        assertThat(result.sessionId()).isEqualTo(42069L);
        assertThat(result.payout()).isEqualTo(750L);
        assertThat(result.betsByOption()).isEmpty(); // no bets sent in this synthetic round
        assertThat(result.balanceDelta()).isEqualTo(750L); // 750 payout - 0 staked
        // currentRound was reset
        assertThat(bot.getMemory().getCurrentRound().getSessionId()).isZero();
    }

    @Test
    @DisplayName("Sequence of StartGame/EndGame builds up lastResults in order")
    void multipleRoundsAccumulate() throws Exception {
        for (long sid = 100L; sid < 105L; sid++) {
            invokeOnStartGame(bot, sid);
            invokeOnEndGame(bot, new StubEndGame(sid, bot.getUserName(), sid * 10L));
        }
        List<RoundResult> results = bot.getMemory().snapshotLastResults();
        assertThat(results).hasSize(5);
        for (int i = 0; i < 5; i++) {
            long expectedSid = 100L + i;
            assertThat(results.get(i).sessionId()).isEqualTo(expectedSid);
            assertThat(results.get(i).payout()).isEqualTo(expectedSid * 10L);
        }
    }

    @Test
    @DisplayName("Mismatched sessionId at EndGame still pushes a RoundResult with empty bets")
    void mismatchedSessionStillRecordsResult() throws Exception {
        invokeOnStartGame(bot, 100L);
        // EndGame carries a different sid (e.g. dropped message scenario)
        invokeOnEndGame(bot, new StubEndGame(999L, bot.getUserName(), 0L));

        List<RoundResult> results = bot.getMemory().snapshotLastResults();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).sessionId()).isEqualTo(999L);
        assertThat(results.get(0).betsByOption()).isEmpty();
    }

    @Test
    @DisplayName("bet() Supplier accumulates per-option bets into the in-flight RoundState via BotMemory.recordBetSent")
    void betSupplierRecordsBets() throws Exception {
        invokeOnStartGame(bot, 555L);
        // Force a deterministic random output so resolveBetAmount + resolveNextEntryToBet
        // are predictable. minBet=100, maxBet=1000, step=100 -> maxSteps=9 ->
        // random.nextInt(10) selects step; affinities map has 6 options -> random.nextInt(6)
        // picks the option index. Configure both.
        Random rng = mock(Random.class);
        when(rng.nextInt(10)).thenReturn(2); // step 2 -> 100 + 2*100 = 300
        when(rng.nextInt(6)).thenReturn(3);  // option index 3
        bot.setRandom(rng);

        // Force gameState BET + sid so canBet would pass — but bet() is called directly
        // here to bypass the scenario engine. canBet is exercised in BettingMiniGameBotTest.

        // Trigger bet() supplier twice
        Method betMethod = BettingMiniGameBot.class.getDeclaredMethod("bet");
        betMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.function.Supplier<?> sup = (java.util.function.Supplier<?>) betMethod.invoke(bot);
        sup.get();
        sup.get();

        Map<Integer, Long> bets = bot.getMemory().snapshotCurrentRoundBets();
        // Both bets are for sessionId 555 -> accumulator should sum: option 3 -> 600
        assertThat(bets).containsExactly(Map.entry(3, 600L));
    }

    /* ----- helpers ----- */

    /**
     * Minimal {@link EndGameMessage} stub carrying a {@code sid} and a single bot's
     * winnings. Implements {@link HasBotWinnings} so the dispatch path in
     * {@code onEndGame} picks up the {@code wm}-equivalent value and feeds it as
     * {@code payout} into {@code BotMemory.completeRound}.
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

    private static void invokeOnStartGame(BettingMiniGameBot bot, long sid) throws Exception {
        StartGameMessage msg = mock(StartGameMessage.class);
        when(msg.getSessionId()).thenReturn(sid);
        ActionResponseMessage<StartGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        Method m = BettingMiniGameBot.class.getDeclaredMethod("onStartGame", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(bot, resp);
    }

    private static void invokeOnEndGame(BettingMiniGameBot bot, EndGameMessage msg) throws Exception {
        ActionResponseMessage<EndGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        Method m = BettingMiniGameBot.class.getDeclaredMethod("onEndGame", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(bot, resp);
    }

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
