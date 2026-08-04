package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
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
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * Hardens TAI_XIU_BOT focus area #3 — <b>EndGame session correlation</b>. The
 * captured Tai Xiu EndGame frame carries <b>no {@code sid}</b>
 * ({@code TaiXiuEndGameMessage.getSessionId()} returns {@code 0}), so the bot must
 * correlate the just-finished round to the session it tracked from StartGame via the
 * {@code endGameSessionId()} seam reading {@code sidStore}, NOT from the message body.
 * <p>
 * The existing dispatch/stream tests pin balance + metrics, which flow through
 * {@code balanceCreditFor}/the marker interfaces and would still pass even if the
 * correlation were broken. The correlation itself is only observable through the
 * finalized {@link RoundResult} that {@code BotMemory.completeRound} pushes:
 * <ul>
 *   <li><b>Correct</b> (seam → {@code sidStore.get()}): the EndGame matches the
 *       in-flight round, so {@code RoundResult.sessionId} == the tracked StartGame
 *       {@code sid}, {@code betsByOption} is non-empty (the bot's bet is attributed),
 *       and {@code balanceDelta == payout − staked}.</li>
 *   <li><b>Broken</b> (if the bot read {@code msg.getSessionId()} == 0): the
 *       in-flight round is discarded — {@code RoundResult.sessionId} would be {@code 0},
 *       {@code betsByOption} empty, {@code balanceDelta == payout} (staked lost).</li>
 * </ul>
 * Capturing the {@link RoundResult} via the strategy's {@code onRoundEnd} callback is
 * the cleanest observation point (it is exactly the value
 * {@code BettingMiniGameBot.onEndGame} forwards to {@code strategy.onRoundEnd}).
 */
@DisplayName("TaiXiuGameBot EndGame session correlation (#3)")
class TaiXiuGameBotSessionCorrelationTest {

    private static final long START_BALANCE = 50_000_000L;
    private static final int CHOSEN_EID = 1;
    private static final long CHOSEN_AMOUNT = 500_000L;
    private static final long TRACKED_SID = 2670572L; // from startGame.json

    private TaiXiuGameBot bot;
    private final AtomicReference<RoundResult> captured = new AtomicReference<>();

    private void buildBot() {
        Game game = Game.builder()
                .id("g-taixiu").name("TaiXiu").pluginName("taixiuPlugin")
                .offset(999_999).numberOfOptions(2)
                .build();
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

        // Strategy that bets deterministically AND captures the RoundResult the bot
        // forwards on round end — our observation point for the correlation.
        BettingStrategy capturing = new BettingStrategy() {
            @Override
            public Optional<BetDecision> decide(BetContext context) {
                return Optional.of(new BetDecision(CHOSEN_EID, CHOSEN_AMOUNT));
            }
            @Override
            public void onRoundEnd(RoundResult result) {
                captured.set(result);
            }
        };
        BettingStrategyFactory factory = mock(BettingStrategyFactory.class);
        when(factory.create(StrategyId.RANDOM)).thenReturn(capturing);

        bot = new TaiXiuGameBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setStrategyFactory(factory);
        bot.setTaiXiuMessageTypes(new MiniGameTaiXiuMessageTypes());
        bot.setRandom(new Random(0L));
        bot.initializeSubclass();
        bot.setMetrics(mock(BotMetrics.class));

        seedLong("lastFetchedBalance", START_BALANCE);
        seedAtomic("expectedCurrentBalance", START_BALANCE);
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
    @DisplayName("endGameSessionId() seam returns the tracked session, not the message's sid=0")
    void seamReadsTrackedSession() throws Exception {
        buildBot();
        // Drive a StartGame so sidStore tracks TRACKED_SID.
        invokeOnStartGame((StartGameMessage) parseFixture("startGame.json"));

        EndGameMessage end = (EndGameMessage) parseFixture("endGame_partialRefund.json");
        // Defensive: the message itself exposes no usable sid (#3).
        assertThat(end.getSessionId()).isZero();

        long correlated = invokeEndGameSessionId(end);
        assertThat(correlated)
                .as("endGameSessionId must come from the tracked session (sidStore), not msg.getSessionId()=0")
                .isEqualTo(TRACKED_SID);
    }

    @Test
    @DisplayName("EndGame (no sid) correlates the bet to the tracked round: RoundResult is non-discarded")
    void endGameCorrelatesToTrackedRound() throws Exception {
        buildBot();

        // subscribe → start opens the BET window at TRACKED_SID.
        invokeOnSubscribe((SubscribeMessage) parseFixture("subscribe.json"));
        invokeOnStartGame((StartGameMessage) parseFixture("startGame.json"));
        setRemainingTime(50_000L);
        setField("gameState", BettingMiniGameState.BET);

        // Place a bet so the in-flight round accumulates betsByOption under TRACKED_SID.
        assertThat(invokeBetCondition()).isTrue();
        invokeBetSupplier();

        // End the round with a partial-refund frame: G=120k payout, gB=500k, gR=200k.
        invokeOnEndGame((EndGameMessage) parseFixture("endGame_partialRefund.json"));

        RoundResult result = captured.get();
        assertThat(result).as("strategy.onRoundEnd received a RoundResult").isNotNull();

        // Correlation correct → finalized round is keyed by the TRACKED sid, not 0.
        assertThat(result.sessionId())
                .as("RoundResult.sessionId must be the tracked StartGame sid (correlation), not 0")
                .isEqualTo(TRACKED_SID);
        // Non-discarded → the bot's bet is attributed to the round.
        assertThat(result.betsByOption())
                .as("a correlated round keeps the bet; a discarded (sid=0) round would be empty")
                .containsEntry(CHOSEN_EID, CHOSEN_AMOUNT);
        // payout = winnings = G = 120000.
        assertThat(result.payout()).isEqualTo(120_000L);
        // balanceDelta = payout - staked = 120000 - 500000 = -380000 (round-correlation
        // delta tracks gross stake, distinct from the refund-aware local balance credit).
        assertThat(result.balanceDelta()).isEqualTo(120_000L - CHOSEN_AMOUNT);
    }

    @Test
    @DisplayName("control: when the in-flight sid does NOT match the tracked round, BotMemory discards it")
    void mismatchedSessionIsDiscarded() throws Exception {
        // This is the contrapositive: it proves the assertions above are load-bearing —
        // a non-matching sid genuinely produces an empty, sid-tagged-by-arg RoundResult.
        // We move the tracked session AFTER the bet so the EndGame correlation key
        // (still TRACKED_SID via the seam) no longer matches the in-flight round.
        buildBot();
        invokeOnSubscribe((SubscribeMessage) parseFixture("subscribe.json"));
        invokeOnStartGame((StartGameMessage) parseFixture("startGame.json")); // sidStore = TRACKED_SID
        setRemainingTime(50_000L);
        setField("gameState", BettingMiniGameState.BET);
        assertThat(invokeBetCondition()).isTrue();
        invokeBetSupplier(); // bet recorded under TRACKED_SID

        // Advance the tracked session to a different round WITHOUT a new beginRound for it
        // by issuing a fresh StartGame at a different sid (begins a new in-flight round).
        invokeOnStartGame(startGameWithSid(TRACKED_SID + 1)); // sidStore = TRACKED_SID+1, new round begun

        invokeOnEndGame((EndGameMessage) parseFixture("endGame_partialRefund.json"));

        RoundResult result = captured.get();
        assertThat(result).isNotNull();
        // The seam now resolves to TRACKED_SID+1; the in-flight round (begun for
        // TRACKED_SID+1, with no bet placed) matches, so betsByOption is empty.
        assertThat(result.sessionId()).isEqualTo(TRACKED_SID + 1);
        assertThat(result.betsByOption())
                .as("no bet was placed in the newly-begun round → empty attribution")
                .isEmpty();
    }

    /* ---- helpers ---- */

    private BettingMiniMessage parseFixture(String name) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new MiniGameTaiXiuMessageTypes().getTypeRegistrations());
        try (var in = getClass().getResourceAsStream("/messages/taixiu/" + name)) {
            assertThat(in).as("fixture /messages/taixiu/" + name).isNotNull();
            return mapper.readValue(in.readAllBytes(), BettingMiniMessage.class);
        }
    }

    private StartGameMessage startGameWithSid(long sid) throws Exception {
        TaiXiuStartGameMessage msg = (TaiXiuStartGameMessage) parseFixture("startGame.json");
        msg.setSid(sid);
        return msg;
    }

    private void invokeOnSubscribe(SubscribeMessage msg) throws Exception {
        invokeHandler("onSubscribe", msg);
    }

    private void invokeOnStartGame(StartGameMessage msg) throws Exception {
        invokeHandler("onStartGame", msg);
    }

    private void invokeOnEndGame(EndGameMessage msg) throws Exception {
        invokeHandler("onEndGame", msg);
    }

    private void invokeHandler(String name, BettingMiniMessage payload) throws Exception {
        ActionResponseMessage<BettingMiniMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, payload);
        Method m = BettingMiniGameBot.class.getDeclaredMethod(name, ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(bot, resp);
    }

    private long invokeEndGameSessionId(EndGameMessage msg) throws Exception {
        Method m = BettingMiniGameBot.class.getDeclaredMethod("endGameSessionId", EndGameMessage.class);
        m.setAccessible(true);
        return (long) m.invoke(bot, msg);
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
