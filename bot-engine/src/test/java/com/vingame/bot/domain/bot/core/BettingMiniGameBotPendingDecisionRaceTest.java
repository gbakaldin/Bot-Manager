package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the race documented in
 * {@code docs/reviews/BETTING_STRATEGIES/review.md} (Bug 2): the bet supplier
 * was throwing {@link IllegalStateException} when a netty-thread event
 * ({@code onStartGame}, {@code beforeReconnect}) cleared the parked
 * {@code pendingDecision} between {@code betCondition()} parking it and
 * {@code bet()} consuming it.
 *
 * <p>Fix shape (see commit message): {@code onStartGame} no longer clears
 * {@code pendingDecision} — the strategy's per-round counter keys on
 * {@code RoundState.sessionId} so the leftover decision is replaced on the
 * next condition call anyway. {@code beforeReconnect} still clears (the WS
 * is down and we want any stale value gone before reconnect). The supplier
 * itself is defensive: if it observes an empty decision (clear from
 * {@code beforeReconnect} only), it re-derives via the strategy on the
 * current context rather than crashing the scenario.
 */
@DisplayName("BettingMiniGameBot pendingDecision race (review Bug 2)")
class BettingMiniGameBotPendingDecisionRaceTest {

    private BettingMiniGameBot bot;
    private ControlledStrategy strategy;

    @BeforeEach
    void setUp() throws Exception {
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) affinities.put(i, 1);
        Game game = Game.builder()
                .id("g-race").name("BauCua").pluginName("BauCua")
                .offset(2000).optionAffinities(affinities).build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100).maxBet(1000).betIncrement(100)
                .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(5)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("racebot").password("pw").fingerprint("fp").build())
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
        bot.setRandom(new Random(0xA5A5A5A5L));
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
    @DisplayName("onStartGame no longer clears pendingDecision — parked decision survives a mid-tick StartGame")
    void onStartGameDoesNotClearPendingDecision() throws Exception {
        // Thread A (scenario thread): betCondition runs, parks a decision for round R1.
        openRound(1001L);
        strategy.nextDecision = Optional.of(new BetDecision(2, 500L));
        Supplier<Boolean> condition = condition();
        assertThat(condition.get()).isTrue();
        AtomicReference<Optional<BetDecision>> parked = pendingDecision();
        assertThat(parked.get()).isPresent();
        BetDecision before = parked.get().orElseThrow();

        // Thread B (netty processor thread): a new round's StartGame fires before
        // the scenario thread invokes the supplier. Pre-fix this would clear
        // pendingDecision; post-fix the parked decision survives.
        invokeOnStartGame(1002L);

        assertThat(parked.get())
                .as("pendingDecision must NOT be cleared by onStartGame (review Bug 2)")
                .isPresent()
                .contains(before);
    }

    @Test
    @DisplayName("Race scenario: condition parks → onStartGame fires → supplier runs without throwing and without spurious bet")
    void supplierSurvivesOnStartGameRace() throws Exception {
        // Set up round R1
        openRound(2001L);
        strategy.nextDecision = Optional.of(new BetDecision(1, 300L));

        // 1. Thread A: condition parks decision D1 for round R1.
        Supplier<Boolean> condition = condition();
        assertThat(condition.get()).isTrue();

        // 2. Thread B: onStartGame for round R2 fires concurrently. Post-fix this
        //    no longer clears the parked decision.
        invokeOnStartGame(2002L);
        // Update sidStore to reflect the new round (bet() reads it).
        ((SessionIdStore) readField("sidStore")).set(2002L);

        // 3. Thread A: supplier runs. Must NOT throw.
        Supplier<ActionRequestMessage> supplier = supplier();
        ActionRequestMessage[] result = new ActionRequestMessage[1];
        assertThatCode(() -> result[0] = supplier.get())
                .as("supplier must not throw when the parked decision survives onStartGame")
                .doesNotThrowAnyException();
        assertThat(result[0]).isNotNull();
        // The decision used is the one originally parked — strategy was not
        // invoked a second time in this code path (the parked value was still
        // present).
        assertThat(strategy.decideInvocations).isEqualTo(1);
    }

    @Test
    @DisplayName("Supplier degrades gracefully when pendingDecision is externally cleared (beforeReconnect race)")
    void supplierRederivesOnEmptyParked() throws Exception {
        openRound(3001L);
        // The condition would normally park a decision; here we simulate the
        // post-clear state directly (as if beforeReconnect cleared it after
        // condition returned true). The supplier should re-derive via strategy.
        // Pre-fix this threw IllegalStateException.
        strategy.nextDecision = Optional.of(new BetDecision(0, 100L));

        // Park nothing — simulate beforeReconnect having cleared it.
        pendingDecision().set(Optional.empty());

        Supplier<ActionRequestMessage> supplier = supplier();
        ActionRequestMessage[] result = new ActionRequestMessage[1];
        assertThatCode(() -> result[0] = supplier.get())
                .as("supplier must not throw when pendingDecision is empty; should re-derive")
                .doesNotThrowAnyException();
        assertThat(result[0]).isNotNull();
        // Strategy was invoked once (the re-derivation) — no parked value was
        // available to short-circuit.
        assertThat(strategy.decideInvocations).isEqualTo(1);
    }

    @Test
    @DisplayName("Supplier still surfaces a loud error if strategy declines re-derivation post-race")
    void supplierThrowsIfStrategyAlsoDeclinesAfterRace() throws Exception {
        openRound(4001L);
        // Empty pendingDecision (post-race) AND strategy declines on re-derivation.
        // This is a "should not happen in production" path (the condition just
        // succeeded a tick ago) but we surface it loudly rather than silently
        // skipping — see bet() Javadoc.
        strategy.nextDecision = Optional.empty();
        pendingDecision().set(Optional.empty());

        Supplier<ActionRequestMessage> supplier = supplier();
        assertThatCode(supplier::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strategy declined re-derivation");
    }

    /* ---- helpers ---- */

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

    /**
     * Strategy that returns whatever decision the test parks in
     * {@link #nextDecision}. Counts {@link #decide} invocations so the test can
     * distinguish "supplier short-circuited via the parked value" from
     * "supplier re-derived via the strategy".
     */
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
