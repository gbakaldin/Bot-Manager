package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.strategy.BetDecision;
import com.vingame.bot.domain.bot.util.BettingMiniGameState;
import com.vingame.bot.domain.bot.util.SessionIdStore;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.vingame.bot.domain.bot.message.StartGameMessage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 5 of {@code docs/plans/BETTING_STRATEGIES.md} — end-to-end equivalence
 * test. Pins that a {@link BettingMiniGameBot} wired through the strategy code
 * path (Phase 5: condition → {@code strategy.decide(ctx)} → parked decision →
 * supplier) produces the same {@code (option, amount)} sequence as the
 * pre-Phase-5 path ({@code shouldBet} + {@code resolveBetAmount} +
 * {@code resolveNextEntryToBet}) when both are seeded with the same
 * {@link Random} sequence and given the same {@code BotBehaviorConfig} / Game.
 *
 * <p>This is the "v1 ships {@link com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategy}
 * only — its decisions are bit-for-bit equivalent to the existing always-bet
 * path" guarantee from the plan's Phase 5 spec.
 *
 * <p>{@link com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategyTest}
 * already pins the strategy in isolation; this test pins the strategy
 * <i>through the bot's actual tick path</i> — buildBetContext, the condition
 * gate, pendingDecision parking, and the supplier consumption.
 */
@DisplayName("BettingMiniGameBot x RandomBehaviorStrategy equivalence (Phase 5)")
class BettingMiniGameBotStrategyEquivalenceTest {

    private BettingMiniGameBot bot;

    @BeforeEach
    void setUp() {
        BotCredentials credentials = BotCredentials.builder()
                .username("equivbot1").password("pw").fingerprint("fp").build();

        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) affinities.put(i, 1);
        Game game = Game.builder()
                .id("g-equiv").name("BauCua").pluginName("BauCua")
                .offset(2000).optionAffinities(affinities).build();

        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100).maxBet(1000).betIncrement(100)
                .maxTotalBetPerRound(10_000)
                .minBetsPerRound(1).maxBetsPerRound(5)
                .chatEnabled(false).autoDepositEnabled(false)
                .betSkipPercentage(0)
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
        bot.initializeSubclass();

        // Seed balance cache so canBet's downstream calls (onNewSession via
        // checkBalance) don't reach out to the (mocked) API gateway.
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

    /**
     * Bit-for-bit replay of the legacy bot decision pipeline, identical to the
     * helper used inside {@code RandomBehaviorStrategyTest.Equivalence} — kept
     * here because the in-package test pins this contract at the
     * {@link BettingMiniGameBot} surface, not at the strategy.
     */
    private static List<BetDecision> legacyReplay(Random rng,
                                                  BotBehaviorConfig beh,
                                                  Game game,
                                                  int ticks,
                                                  int maxBetsPerRound) {
        List<BetDecision> out = new ArrayList<>();
        int betsThisRound = 0;
        List<Integer> options = List.copyOf(game.getEffectiveOptionAffinities().keySet());
        for (int t = 0; t < ticks; t++) {
            if (betsThisRound >= maxBetsPerRound) continue;
            if (rng.nextInt(100) < beh.getBetSkipPercentage()) continue;
            betsThisRound++;

            int maxSteps = Math.toIntExact((beh.getMaxBet() - beh.getMinBet()) / beh.getBetIncrement());
            long steps = rng.nextInt(maxSteps + 1);
            long amount = beh.getMinBet() + steps * beh.getBetIncrement();

            int option = options.get(rng.nextInt(options.size()));
            out.add(new BetDecision(option, amount));
        }
        return out;
    }

    @Test
    @DisplayName("Bot's strategy-driven (option, amount) sequence matches legacy replay for the same seed")
    void botPipelineMatchesLegacyReplayBitForBit() throws Exception {
        long seed = 0xCAFEBABEL;
        int ticks = 12;

        Game game = bot.getConfiguration().getGame();
        BotBehaviorConfig behavior = bot.getConfiguration().getBehaviorConfig();

        // Baseline: what the pre-Phase-5 code would have produced with this seed.
        List<BetDecision> legacy = legacyReplay(
                new Random(seed), behavior, game, ticks, behavior.getMaxBetsPerRound());

        // Drive the bot through the new condition/supplier path with the same seed.
        bot.setRandom(new Random(seed));
        // Open a round so canBet returns true. beginRound is what would normally
        // be called from onStartGame; do it explicitly + drive sidStore/state.
        invokeOnStartGame(42069L);
        setField("gameState", BettingMiniGameState.BET);
        ((AtomicLong) readField("remainingTime")).set(10_000L);
        SessionIdStore sidStore = (SessionIdStore) readField("sidStore");
        sidStore.set(42069L);

        Method conditionMethod = BettingMiniGameBot.class.getDeclaredMethod("betCondition");
        conditionMethod.setAccessible(true);
        Method betMethod = BettingMiniGameBot.class.getDeclaredMethod("bet");
        betMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition = (Supplier<Boolean>) conditionMethod.invoke(bot);
        @SuppressWarnings("unchecked")
        Supplier<ActionRequestMessage> supplier =
                (Supplier<ActionRequestMessage>) betMethod.invoke(bot);

        List<BetDecision> actual = new ArrayList<>();
        for (int t = 0; t < ticks; t++) {
            boolean go = condition.get();
            if (!go) continue;
            // Capture the parked decision via reflection before the supplier
            // consumes it. The bet() supplier itself can be invoked too — but
            // the test pins the decision sequence, not the Request payload.
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicReference<java.util.Optional<BetDecision>> pending =
                    (java.util.concurrent.atomic.AtomicReference<java.util.Optional<BetDecision>>)
                            readField("pendingDecision");
            BetDecision parked = pending.get().orElseThrow();
            actual.add(parked);
            // Drain the parked decision the way the scenario engine would —
            // exercises bet()'s memory-recording branch end-to-end.
            supplier.get();
        }

        assertThat(actual).isEqualTo(legacy);
    }

    @Test
    @DisplayName("Equivalence holds across a fresh round (sessionId boundary resets the strategy counter)")
    void equivalenceAcrossRoundBoundary() throws Exception {
        long seed = 0xFEEDC0FFEEL;
        int ticksPerRound = 7;

        Game game = bot.getConfiguration().getGame();
        BotBehaviorConfig behavior = bot.getConfiguration().getBehaviorConfig();

        // Two rounds back-to-back; legacy resets numberOfBetsInCurrentSession
        // on every onStartGame. Strategy-driven path resets the strategy's
        // internal counter on the BotMemory.currentRound.sessionId change.
        List<BetDecision> legacyRound1 = legacyReplay(
                new Random(seed), behavior, game, ticksPerRound, behavior.getMaxBetsPerRound());
        // Continue the legacy RNG into round 2 — both rounds use the same
        // Random instance because the strategy-side RNG is also continuous.
        Random legacyRng = new Random(seed);
        legacyReplay(legacyRng, behavior, game, ticksPerRound, behavior.getMaxBetsPerRound());
        List<BetDecision> legacyRound2 = legacyReplay(
                legacyRng, behavior, game, ticksPerRound, behavior.getMaxBetsPerRound());

        bot.setRandom(new Random(seed));

        // Round 1
        invokeOnStartGame(1001L);
        setField("gameState", BettingMiniGameState.BET);
        ((AtomicLong) readField("remainingTime")).set(10_000L);
        ((SessionIdStore) readField("sidStore")).set(1001L);

        List<BetDecision> r1 = drainTicks(ticksPerRound);
        assertThat(r1).isEqualTo(legacyRound1);

        // Round 2 — onStartGame with a new sessionId. RandomBehaviorStrategy
        // sees currentRound.sessionId change and resets its counter.
        invokeOnStartGame(1002L);
        ((AtomicLong) readField("remainingTime")).set(10_000L);
        ((SessionIdStore) readField("sidStore")).set(1002L);

        List<BetDecision> r2 = drainTicks(ticksPerRound);
        assertThat(r2).isEqualTo(legacyRound2);
    }

    private List<BetDecision> drainTicks(int ticks) throws Exception {
        Method conditionMethod = BettingMiniGameBot.class.getDeclaredMethod("betCondition");
        conditionMethod.setAccessible(true);
        Method betMethod = BettingMiniGameBot.class.getDeclaredMethod("bet");
        betMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition = (Supplier<Boolean>) conditionMethod.invoke(bot);
        @SuppressWarnings("unchecked")
        Supplier<ActionRequestMessage> supplier =
                (Supplier<ActionRequestMessage>) betMethod.invoke(bot);

        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<java.util.Optional<BetDecision>> pending =
                (java.util.concurrent.atomic.AtomicReference<java.util.Optional<BetDecision>>)
                        readField("pendingDecision");

        List<BetDecision> out = new ArrayList<>();
        for (int t = 0; t < ticks; t++) {
            if (!condition.get()) continue;
            out.add(pending.get().orElseThrow());
            supplier.get();
        }
        return out;
    }

    private void invokeOnStartGame(long sid) throws Exception {
        StartGameMessage msg = mock(StartGameMessage.class);
        when(msg.getSessionId()).thenReturn(sid);
        ActionResponseMessage<StartGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        Method m = BettingMiniGameBot.class.getDeclaredMethod(
                "onStartGame", ActionResponseMessage.class);
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
}
