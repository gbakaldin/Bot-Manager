package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;
import com.vingame.bot.domain.bot.util.BettingMiniGameState;
import com.vingame.bot.domain.bot.util.GameState;
import com.vingame.bot.domain.bot.util.SessionIdStore;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BettingMiniGameBot")
class BettingMiniGameBotTest {

    private BettingMiniGameBot bot;
    private Random random;

    @BeforeEach
    void setUp() {
        random = mock(Random.class);

        BotCredentials credentials = BotCredentials.builder()
                .username("bot1")
                .password("pw")
                .fingerprint("fp")
                .build();

        Game game = Game.builder()
                .id("g1")
                .name("BauCua")
                .pluginName("BauCua")
                .offset(2000)
                .numberOfOptions(6)
                .bettingOptions(null)
                .build();

        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100)
                .maxBet(1000)
                .betIncrement(100)
                .maxTotalBetPerRound(10_000)
                .minBetsPerRound(1)
                .maxBetsPerRound(3)
                .chatEnabled(false)
                .autoDepositEnabled(false)
                .betSkipPercentage(0)
                .build();

        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(credentials)
                .environmentId("env-1")
                .botGroupId("group-1")
                .botIndex(1)
                .game(game)
                .behaviorConfig(behavior)
                .zoneName("MiniGame3")
                .timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .build();

        bot = new BettingMiniGameBot();
        // Wire just enough to initialize the subclass — skipping full initialize() to avoid auth.
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setRandom(random);

        // initializeSubclass() needs configuration and credentials set
        bot.initializeSubclass();
        // setRandom AFTER initializeSubclass to ensure it sticks (initializeSubclass doesn't touch random)
        bot.setRandom(random);
    }

    @AfterEach
    void tearDown() throws Exception {
        // shut down watchdog scheduler to avoid leaking threads
        ScheduledExecutorService watchdog = (ScheduledExecutorService) readField("watchdogScheduler");
        if (watchdog != null) watchdog.shutdownNow();
        ScheduledExecutorService countdown = (ScheduledExecutorService) readField("scheduler");
        if (countdown != null) countdown.shutdownNow();
    }

    /* ----- shouldBet ----- */

    @Nested
    @DisplayName("shouldBet")
    class ShouldBetTests {

        @Test
        @DisplayName("With betSkipPercentage=0 and maxBetsPerRound=3, returns true 3 times then false")
        void shouldRespectMaxBetsCap() {
            // betSkipPercentage = 0 -> random.nextInt(100) < 0 is never true -> never skip
            when(random.nextInt(100)).thenReturn(50); // any value >= 0 -> not skip

            assertThat(bot.shouldBet()).isTrue();
            assertThat(bot.shouldBet()).isTrue();
            assertThat(bot.shouldBet()).isTrue();
            assertThat(bot.shouldBet()).isFalse(); // cap hit
        }

        @Test
        @DisplayName("With betSkipPercentage=100, always returns false (always skips)")
        void shouldAlwaysSkipAt100Percent() throws Exception {
            setBehavior(100, 100, 1000, 100, 3);
            // Any value from 0..99 < 100 -> skip
            when(random.nextInt(100)).thenReturn(0, 50, 99);

            assertThat(bot.shouldBet()).isFalse();
            assertThat(bot.shouldBet()).isFalse();
            assertThat(bot.shouldBet()).isFalse();
        }

        @Test
        @DisplayName("With betSkipPercentage=50, returns false when random=30, true when random=70")
        void shouldHonor50PercentSkip() throws Exception {
            setBehavior(50, 100, 1000, 100, 5);

            when(random.nextInt(100)).thenReturn(30); // 30 < 50 -> skip
            assertThat(bot.shouldBet()).isFalse();

            when(random.nextInt(100)).thenReturn(70); // 70 < 50 false -> bet
            assertThat(bot.shouldBet()).isTrue();
        }
    }

    /* ----- resolveBetAmount ----- */

    @Nested
    @DisplayName("resolveBetAmount")
    class ResolveBetAmountTests {

        @Test
        @DisplayName("minBet=100, maxBet=1000, step=100: random=0 -> 100; random=9 -> 1000; random=5 -> 600")
        void shouldRespectStepBoundsAndScale() {
            // maxSteps = (1000-100)/100 = 9 -> random.nextInt(10)
            when(random.nextInt(10)).thenReturn(0);
            assertThat(bot.resolveBetAmount()).isEqualTo(100L);

            when(random.nextInt(10)).thenReturn(9);
            assertThat(bot.resolveBetAmount()).isEqualTo(1000L);

            when(random.nextInt(10)).thenReturn(5);
            assertThat(bot.resolveBetAmount()).isEqualTo(600L);
        }

        @Test
        @DisplayName("minBet==maxBet collapses to single value (random.nextInt(1) -> 0)")
        void shouldCollapseWhenMinEqualsMax() throws Exception {
            setBehavior(0, 500, 500, 100, 3);
            when(random.nextInt(1)).thenReturn(0);

            assertThat(bot.resolveBetAmount()).isEqualTo(500L);
        }
    }

    /* ----- resolveNextEntryToBet (private) ----- */

    @Nested
    @DisplayName("resolveNextEntryToBet")
    class ResolveNextEntryToBetTests {

        @Test
        @DisplayName("Picks from configured bettingOptions when present")
        void shouldUseConfiguredOptions() throws Exception {
            setBettingOptions(List.of(1, 10, 100));
            when(random.nextInt(3)).thenReturn(1);

            int result = (int) invokePrivate("resolveNextEntryToBet");

            assertThat(result).isEqualTo(10);
        }

        @Test
        @DisplayName("Falls back to [0..numberOfOptions) when bettingOptions is null")
        void shouldFallBackToRange() throws Exception {
            setBettingOptions(null);
            setNumberOfOptions(5);
            when(random.nextInt(5)).thenReturn(3);

            int result = (int) invokePrivate("resolveNextEntryToBet");

            assertThat(result).isEqualTo(3);
        }
    }

    /* ----- canBet (private) ----- */

    @Nested
    @DisplayName("canBet")
    class CanBetTests {

        @Test
        @DisplayName("All gates closed -> false (no session)")
        void shouldFalseWhenNoSession() throws Exception {
            setGameState(BettingMiniGameState.BET);
            setRemainingTime(10_000);
            setBlockBetTime(2_000);
            setSidStoreValue(0L); // no session

            assertThat(invokeCanBet()).isFalse();
        }

        @Test
        @DisplayName("Session exists but wrong phase (PAYOUT) -> false")
        void shouldFalseWhenNotBetPhase() throws Exception {
            setSidStoreValue(42069L);
            setGameState(BettingMiniGameState.PAYOUT);
            setRemainingTime(10_000);
            setBlockBetTime(2_000);

            assertThat(invokeCanBet()).isFalse();
        }

        @Test
        @DisplayName("Not enough time remaining -> false")
        void shouldFalseWhenNotEnoughTime() throws Exception {
            setSidStoreValue(42069L);
            setGameState(BettingMiniGameState.BET);
            setRemainingTime(1_000); // < blockBetTime
            setBlockBetTime(2_000);

            assertThat(invokeCanBet()).isFalse();
        }

        @Test
        @DisplayName("All gates open -> true")
        void shouldTrueWhenAllOpen() throws Exception {
            setSidStoreValue(42069L);
            setGameState(BettingMiniGameState.BET);
            setRemainingTime(10_000);
            setBlockBetTime(2_000);

            assertThat(invokeCanBet()).isTrue();
        }

        private boolean invokeCanBet() throws Exception {
            Method m = BettingMiniGameBot.class.getDeclaredMethod("canBet");
            m.setAccessible(true);
            return (boolean) m.invoke(bot);
        }
    }

    /* ----- session lifecycle handlers ----- */

    @Nested
    @DisplayName("session lifecycle handlers")
    class LifecycleHandlerTests {

        @Test
        @DisplayName("onSubscribe sets blockBetTime, timeForBetting, transitions status to CONNECTION_AUTHENTICATED")
        void shouldHandleOnSubscribe() throws Exception {
            // Force status to STARTED first so the transition is visible
            setBotStatus(BotStatus.STARTED);

            SubscribeMessage msg = mock(SubscribeMessage.class);
            when(msg.getTimeForDecision()).thenReturn(2000L);
            when(msg.getTimeForBetting()).thenReturn(15_000L);

            ActionResponseMessage<SubscribeMessage> resp =
                    new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);

            invokePrivate("onSubscribe", new Class<?>[]{ActionResponseMessage.class}, resp);

            assertThat(readField("blockBetTime")).isEqualTo(2000L);
            assertThat(readField("timeForBetting")).isEqualTo(15_000L);
            assertThat(bot.getStatus()).isEqualTo(BotStatus.CONNECTION_AUTHENTICATED);
        }

        @Test
        @DisplayName("onStartGame sets sessionId, state=BET, resets bet count")
        void shouldHandleOnStartGame() throws Exception {
            // Force a prior bet count so we can verify reset
            setIntField("numberOfBetsInCurrentSession", 7);

            StartGameMessage msg = mock(StartGameMessage.class);
            when(msg.getSessionId()).thenReturn(42069L);

            ActionResponseMessage<StartGameMessage> resp =
                    new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);

            invokePrivate("onStartGame", new Class<?>[]{ActionResponseMessage.class}, resp);

            SessionIdStore store = (SessionIdStore) readField("sidStore");
            assertThat(store.get()).isEqualTo(42069L);
            assertThat(readField("gameState")).isEqualTo(BettingMiniGameState.BET);
            assertThat(readField("numberOfBetsInCurrentSession")).isEqualTo(0);
        }

        @Test
        @DisplayName("onUpdate sets state to BET when gameState=2")
        void shouldHandleOnUpdateBetPhase() throws Exception {
            setGameState(null);

            UpdateBetMessage msg = mock(UpdateBetMessage.class);
            when(msg.getGameState()).thenReturn(2);

            ActionResponseMessage<UpdateBetMessage> resp =
                    new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);

            invokePrivate("onUpdate", new Class<?>[]{ActionResponseMessage.class}, resp);

            assertThat(readField("gameState")).isEqualTo(BettingMiniGameState.BET);
        }

        @Test
        @DisplayName("onUpdate sets state to PAYOUT when gameState=99 (anything > 0 and != 2)")
        void shouldHandleOnUpdatePayoutPhase() throws Exception {
            setGameState(null);

            UpdateBetMessage msg = mock(UpdateBetMessage.class);
            when(msg.getGameState()).thenReturn(99);

            ActionResponseMessage<UpdateBetMessage> resp =
                    new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);

            invokePrivate("onUpdate", new Class<?>[]{ActionResponseMessage.class}, resp);

            assertThat(readField("gameState")).isEqualTo(BettingMiniGameState.PAYOUT);
        }

        @Test
        @DisplayName("onUpdate with gameState=0 leaves state unchanged")
        void shouldNotChangeStateWhenGameStateZero() throws Exception {
            setGameState(BettingMiniGameState.BET);

            UpdateBetMessage msg = mock(UpdateBetMessage.class);
            when(msg.getGameState()).thenReturn(0);

            ActionResponseMessage<UpdateBetMessage> resp =
                    new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);

            invokePrivate("onUpdate", new Class<?>[]{ActionResponseMessage.class}, resp);

            assertThat(readField("gameState")).isEqualTo(BettingMiniGameState.BET);
        }

        @Test
        @DisplayName("onEndGame transitions state to PAYOUT")
        void shouldHandleOnEndGame() throws Exception {
            // autoDeposit is off so onNewSession won't attempt to deposit
            setLastFetchedBalance(50_000_000L);
            setExpectedCurrentBalance(50_000_000L);
            setGameState(BettingMiniGameState.BET);

            EndGameMessage msg = mock(EndGameMessage.class);
            ActionResponseMessage<EndGameMessage> resp =
                    new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);

            invokePrivate("onEndGame", new Class<?>[]{ActionResponseMessage.class}, resp);

            assertThat(readField("gameState")).isEqualTo(BettingMiniGameState.PAYOUT);
        }
    }

    /* ----- beforeReconnect ----- */

    @Nested
    @DisplayName("beforeReconnect")
    class BeforeReconnectTests {

        @Test
        @DisplayName("Resets sidStore, gameState, remainingTime, and bet count; cancels timers")
        void shouldResetStateAndTimers() throws Exception {
            setSidStoreValue(999L);
            setGameState(BettingMiniGameState.BET);
            setRemainingTime(5000L);
            setIntField("numberOfBetsInCurrentSession", 3);

            // Provide a scheduler + watchdogTask so beforeReconnect cancels them
            ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            try {
                Field schedField = BettingMiniGameBot.class.getDeclaredField("scheduler");
                schedField.setAccessible(true);
                schedField.set(bot, scheduler);

                java.util.concurrent.ScheduledFuture<?> watchdogTask =
                        scheduler.schedule(() -> {}, 60, TimeUnit.SECONDS);
                Field taskField = BettingMiniGameBot.class.getDeclaredField("watchdogTask");
                taskField.setAccessible(true);
                taskField.set(bot, watchdogTask);

                bot.beforeReconnect();

                SessionIdStore store = (SessionIdStore) readField("sidStore");
                assertThat(store.get()).isEqualTo(0L);
                assertThat(readField("gameState")).isNull();
                AtomicLong remaining = (AtomicLong) readField("remainingTime");
                assertThat(remaining.get()).isEqualTo(0L);
                assertThat(readField("numberOfBetsInCurrentSession")).isEqualTo(0);
                // scheduler field should be nulled
                assertThat(readField("scheduler")).isNull();
                // watchdogTask should be nulled
                assertThat(readField("watchdogTask")).isNull();
            } finally {
                if (!scheduler.isShutdown()) scheduler.shutdownNow();
            }
        }
    }

    /* ----- watchdog (real timer-based) ----- */

    @Nested
    @DisplayName("watchdog")
    class WatchdogTests {

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("Fires triggerFullReconnect after timeout")
        void shouldFireAfterTimeout() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> reasonRef = new AtomicReference<>();
            BettingMiniGameBot trackingBot = newWatchdogBot(1, latch, reasonRef, null);

            SubscribeMessage msg = mock(SubscribeMessage.class);
            when(msg.getTimeForDecision()).thenReturn(2000L);
            when(msg.getTimeForBetting()).thenReturn(15_000L);
            ActionResponseMessage<SubscribeMessage> resp =
                    new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);

            try {
                invokePrivateOn(trackingBot, "onSubscribe",
                        new Class<?>[]{ActionResponseMessage.class}, resp);

                boolean fired = latch.await(3, TimeUnit.SECONDS);
                assertThat(fired).isTrue();
                assertThat(reasonRef.get()).contains("watchdog timeout");
            } finally {
                shutdownSchedulers(trackingBot);
            }
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("Reschedules on each scheduling message (onSubscribe / onStartGame / onEndGame)")
        void shouldRescheduleOnEachMessage() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> reasonRef = new AtomicReference<>();
            BettingMiniGameBot trackingBot = newWatchdogBot(1, latch, reasonRef, null);

            try {
                // Initial schedule via onSubscribe
                invokeOnSubscribe(trackingBot, 2000L, 15_000L);

                // 500ms later, reschedule via onStartGame
                Thread.sleep(500);
                invokeOnStartGame(trackingBot, 42069L);

                // 500ms later, reschedule via onEndGame
                Thread.sleep(500);
                invokeOnEndGame(trackingBot);

                // Wait up to 800ms — watchdog should NOT have fired since last reschedule
                boolean fired = latch.await(800, TimeUnit.MILLISECONDS);
                assertThat(fired).isFalse();
            } finally {
                shutdownSchedulers(trackingBot);
            }
        }

        @Test
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        @DisplayName("After cleanup(), onWatchdogExpired() short-circuits and does not call triggerFullReconnect")
        void shouldShortCircuitAfterCleanup() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean triggered = new AtomicBoolean(false);
            BettingMiniGameBot trackingBot = newWatchdogBot(10, latch, null, triggered);

            try {
                trackingBot.cleanup(); // sets stopped=true
                invokePrivateOn(trackingBot, "onWatchdogExpired", new Class<?>[]{});

                assertThat(triggered.get()).isFalse();
            } finally {
                shutdownSchedulers(trackingBot);
            }
        }
    }

    /* ----- helper methods ----- */

    private BettingMiniGameBot newWatchdogBot(long timeoutSec,
                                              CountDownLatch latch,
                                              AtomicReference<String> reasonRef,
                                              AtomicBoolean triggered) {
        BettingMiniGameBot b = new BettingMiniGameBot() {
            @Override
            protected void triggerFullReconnect(String reason) {
                if (triggered != null) triggered.set(true);
                if (reasonRef != null) reasonRef.set(reason);
                if (latch != null) latch.countDown();
            }
        };

        BotCredentials credentials = BotCredentials.builder()
                .username("watchdogbot")
                .password("pw")
                .fingerprint("fp")
                .build();
        Game game = Game.builder()
                .id("g1")
                .name("BauCua")
                .pluginName("BauCua")
                .offset(2000)
                .numberOfOptions(6)
                .build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100).maxBet(1000).betIncrement(100)
                .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(3)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(credentials)
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("Z").timeoutMillis(1000L)
                .watchdogTimeoutSeconds(timeoutSec)
                .build();
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(cfg);
        b.setRandom(mock(Random.class));
        b.initializeSubclass();
        b.setRandom(mock(Random.class));
        // Seed balance cache so onEndGame -> onNewSession -> checkBalance returns from cache and does not call getClient()
        try {
            Field f = Bot.class.getDeclaredField("lastFetchedBalance");
            f.setAccessible(true);
            f.setLong(b, 50_000_000L);
            Field f2 = Bot.class.getDeclaredField("expectedCurrentBalance");
            f2.setAccessible(true);
            ((AtomicLong) f2.get(b)).set(50_000_000L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return b;
    }

    private void invokeOnSubscribe(BettingMiniGameBot b, long timeForDecision, long timeForBetting) throws Exception {
        SubscribeMessage msg = mock(SubscribeMessage.class);
        when(msg.getTimeForDecision()).thenReturn(timeForDecision);
        when(msg.getTimeForBetting()).thenReturn(timeForBetting);
        ActionResponseMessage<SubscribeMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        invokePrivateOn(b, "onSubscribe", new Class<?>[]{ActionResponseMessage.class}, resp);
    }

    private void invokeOnStartGame(BettingMiniGameBot b, long sessionId) throws Exception {
        StartGameMessage msg = mock(StartGameMessage.class);
        when(msg.getSessionId()).thenReturn(sessionId);
        ActionResponseMessage<StartGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        invokePrivateOn(b, "onStartGame", new Class<?>[]{ActionResponseMessage.class}, resp);
    }

    private void invokeOnEndGame(BettingMiniGameBot b) throws Exception {
        EndGameMessage msg = mock(EndGameMessage.class);
        ActionResponseMessage<EndGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        invokePrivateOn(b, "onEndGame", new Class<?>[]{ActionResponseMessage.class}, resp);
    }

    private static void shutdownSchedulers(BettingMiniGameBot b) {
        try {
            Field f = BettingMiniGameBot.class.getDeclaredField("watchdogScheduler");
            f.setAccessible(true);
            ScheduledExecutorService w = (ScheduledExecutorService) f.get(b);
            if (w != null) w.shutdownNow();
            Field f2 = BettingMiniGameBot.class.getDeclaredField("scheduler");
            f2.setAccessible(true);
            ScheduledExecutorService s = (ScheduledExecutorService) f2.get(b);
            if (s != null) s.shutdownNow();
        } catch (Exception ignored) {}
    }

    /* ----- reflection helpers ----- */

    private Object invokePrivate(String name) throws Exception {
        return invokePrivate(name, new Class<?>[]{});
    }

    private Object invokePrivate(String name, Class<?>[] argTypes, Object... args) throws Exception {
        return invokePrivateOn(bot, name, argTypes, args);
    }

    private static Object invokePrivateOn(BettingMiniGameBot target, String name, Class<?>[] argTypes, Object... args) throws Exception {
        Method m = BettingMiniGameBot.class.getDeclaredMethod(name, argTypes);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private Object readField(String name) throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(bot);
    }

    private void setBehavior(int betSkipPercentage, long minBet, long maxBet, long betStep, int maxBetsPerRound) throws Exception {
        BotConfiguration current = bot.getConfiguration();
        BotBehaviorConfig newBehavior = BotBehaviorConfig.builder()
                .minBet(minBet).maxBet(maxBet).betIncrement(betStep)
                .maxTotalBetPerRound(10_000)
                .minBetsPerRound(1).maxBetsPerRound(maxBetsPerRound)
                .chatEnabled(false).autoDepositEnabled(false)
                .betSkipPercentage(betSkipPercentage)
                .build();
        BotConfiguration newCfg = BotConfiguration.builder()
                .credentials(current.getCredentials())
                .environmentId(current.getEnvironmentId())
                .botGroupId(current.getBotGroupId())
                .botIndex(current.getBotIndex())
                .game(current.getGame())
                .behaviorConfig(newBehavior)
                .zoneName(current.getZoneName())
                .timeoutMillis(current.getTimeoutMillis())
                .watchdogTimeoutSeconds(current.getWatchdogTimeoutSeconds())
                .build();
        bot.setConfiguration(newCfg);
    }

    private void setBettingOptions(List<Integer> options) throws Exception {
        bot.getConfiguration().getGame().setBettingOptions(options);
    }

    private void setNumberOfOptions(int n) throws Exception {
        bot.getConfiguration().getGame().setNumberOfOptions(n);
    }

    private void setGameState(GameState state) throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField("gameState");
        f.setAccessible(true);
        f.set(bot, state);
    }

    private void setRemainingTime(long ms) throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField("remainingTime");
        f.setAccessible(true);
        AtomicLong rt = (AtomicLong) f.get(bot);
        rt.set(ms);
    }

    private void setBlockBetTime(long ms) throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField("blockBetTime");
        f.setAccessible(true);
        f.setLong(bot, ms);
    }

    private void setSidStoreValue(long sid) throws Exception {
        SessionIdStore store = (SessionIdStore) readField("sidStore");
        store.set(sid);
    }

    private void setIntField(String name, int value) throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(bot, value);
    }

    private void setBotStatus(BotStatus status) throws Exception {
        Field f = Bot.class.getDeclaredField("status");
        f.setAccessible(true);
        f.set(bot, status);
    }

    private void setLastFetchedBalance(long v) throws Exception {
        Field f = Bot.class.getDeclaredField("lastFetchedBalance");
        f.setAccessible(true);
        f.setLong(bot, v);
    }

    private void setExpectedCurrentBalance(long v) throws Exception {
        Field f = Bot.class.getDeclaredField("expectedCurrentBalance");
        f.setAccessible(true);
        AtomicLong ref = (AtomicLong) f.get(bot);
        ref.set(v);
    }
}
