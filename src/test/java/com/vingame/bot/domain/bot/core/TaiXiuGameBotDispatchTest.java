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
import com.vingame.bot.domain.bot.message.request.Bet;
import com.vingame.bot.domain.bot.message.taixiu.MiniGameTaiXiuMessageTypes;
import com.vingame.bot.domain.bot.message.taixiu.TaiXiuEndGameMessage;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4 verification (TAI_XIU_BOT plan): {@link TaiXiuGameBot} reuses the
 * inherited round behavior and only swaps the fixed-CMD message layer + the
 * refund-aware accounting (AD-11). Drives a deterministic
 * subscribe → start → bet → end stream over the captured fixtures.
 * <p>
 * Pins:
 * <ul>
 *   <li>onSubscribe marks the connection authenticated and captures the betting
 *       window from the real inbound subscribe response (tFB / tFBB).</li>
 *   <li>The bet is built via {@link com.vingame.bot.domain.bot.message.request.TaiXiuRequest}
 *       with the bare fixed cmd {@code 1000}, the chosen Tài/Xỉu {@code eid} and the
 *       currently-tracked {@code sid} — during the BET window only.</li>
 *   <li>The three refund cases (full / partial / zero) produce the correct net
 *       balance, effective stake, and winnings (AD-11).</li>
 * </ul>
 */
@DisplayName("TaiXiuGameBot dispatch (Phase 4)")
class TaiXiuGameBotDispatchTest {

    private static final long START_BALANCE = 50_000_000L;
    private static final int CHOSEN_EID = 1;        // Tài (1) vs Xỉu (2) — exactly 2 options
    private static final long CHOSEN_AMOUNT = 500_000L;

    private TaiXiuGameBot bot;
    private BotMetrics metrics;

    @BeforeEach
    void setUp() {
        Game game = Game.builder()
                .id("g-taixiu").name("TaiXiu").pluginName("taixiuPlugin")
                // Poison offset: Tai Xiu must NEVER read game.getOffset() (AD-9). A
                // CODE+offset leak would corrupt the fixed CMDs and fail the assertions.
                .offset(999_999)
                .numberOfOptions(2)
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

        // Deterministic strategy: always bet CHOSEN_AMOUNT on CHOSEN_EID.
        BettingStrategy fixed = new BettingStrategy() {
            @Override
            public Optional<BetDecision> decide(BetContext context) {
                return Optional.of(new BetDecision(CHOSEN_EID, CHOSEN_AMOUNT));
            }
            @Override
            public void onRoundEnd(RoundResult result) { /* no-op */ }
        };
        BettingStrategyFactory factory = mock(BettingStrategyFactory.class);
        when(factory.create(StrategyId.RANDOM)).thenReturn(fixed);

        bot = new TaiXiuGameBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setStrategyFactory(factory);
        bot.setTaiXiuMessageTypes(new MiniGameTaiXiuMessageTypes());
        bot.setRandom(new Random(0L));
        bot.initializeSubclass();

        metrics = mock(BotMetrics.class);
        bot.setMetrics(metrics);

        // Seed balance so onNewSession() inside onEndGame uses the cache (no HTTP).
        seedLong("lastFetchedBalance", START_BALANCE);
        seedAtomic("expectedCurrentBalance", START_BALANCE);
    }

    @AfterEach
    void tearDown() throws Exception {
        ScheduledExecutorService w = (ScheduledExecutorService) readField("watchdogScheduler");
        if (w != null) w.shutdownNow();
        ScheduledExecutorService s = (ScheduledExecutorService) readField("scheduler");
        if (s != null) s.shutdownNow();
    }

    @Test
    @DisplayName("onSubscribe (real inbound 1005): marks authenticated + captures betting window tFB/tFBB")
    void onSubscribeCapturesTiming() throws Exception {
        SubscribeMessage sub = (SubscribeMessage) parseFixture("subscribe.json");
        invokeOnSubscribe(sub);

        assertThat(readField("status").toString()).isEqualTo("CONNECTION_AUTHENTICATED");
        // tFB=50000 -> timeForBetting; tFBB=3000 -> blockBetTime.
        assertThat((long) readField("timeForBetting")).isEqualTo(50_000L);
        assertThat((long) readField("blockBetTime")).isEqualTo(3_000L);
        verify(metrics).incBotMessage("subscribe");
    }

    @Test
    @DisplayName("bet during BET window: TaiXiuRequest emits cmd 1000 + chosen eid + tracked sid; balance debited")
    void betEmitsFixedCmdAndTrackedSid() throws Exception {
        // Subscribe + StartGame open the BET window at the tracked sid.
        invokeOnSubscribe((SubscribeMessage) parseFixture("subscribe.json"));
        invokeOnStartGame((StartGameMessage) parseFixture("startGame.json")); // sid=2670572

        // Force enough time on the clock so doesEnoughTimeRemain() is true.
        setRemainingTime(50_000L);
        setField("gameState", BettingMiniGameState.BET);

        assertThat(invokeBetCondition()).isTrue();
        ActionRequestMessage outbound = invokeBetSupplier();
        assertThat(outbound).isInstanceOf(Bet.class);

        Body body = readBody(outbound);
        assertThat(body).isInstanceOf(Bet.BetData.class);
        Bet.BetData data = (Bet.BetData) body;
        // Bare fixed Tai Xiu bet cmd — NOT cmdPrefix+3002, NOT CODE+offset (AD-12).
        assertThat(data.getCmd()).isEqualTo(1000);
        assertThat(data.getEid()).isEqualTo(CHOSEN_EID);
        assertThat(data.getB()).isEqualTo(CHOSEN_AMOUNT);
        // Session correlated from StartGame (tracked sid), not the message body.
        assertThat(data.getSid()).isEqualTo(2670572L);
        assertThat(data.getAid()).isEqualTo(1);

        // Balance debited by the full stake at bet time.
        assertThat(currentBalance()).isEqualTo(START_BALANCE - CHOSEN_AMOUNT);
    }

    @Test
    @DisplayName("full refund (gB=gR=GX=500k): net balance 0, effective stake 0, winnings 0 (AD-11)")
    void fullRefundNetsToZero() throws Exception {
        runRound("endGame_fullRefund.json");

        // Round net = -b + gR + winnings = -500000 + 500000 + 0 = 0.
        assertThat(currentBalance()).isEqualTo(START_BALANCE);
        // Effective wagered = gB - gR = 0; bets-placed batched with 0 count.
        verify(metrics).incBetsPlaced(0, 0L);
        // No winnings -> incBotWinnings never called with a positive value.
        verify(metrics, org.mockito.Mockito.never()).incBotWinnings(org.mockito.ArgumentMatchers.anyLong());
        assertThat(bot.getLastRoundWinnings()).isZero();
    }

    @Test
    @DisplayName("partial refund (gB=500k, gR=200k, GX=700k): balance +400k, stake 300k, winnings 200k (AD-11)")
    void partialRefundNetsCorrectly() throws Exception {
        runRound("endGame_partialRefund.json");

        // Net = -b + gR + winnings = -500000 + 200000 + 200000 = -100000.
        assertThat(currentBalance()).isEqualTo(START_BALANCE - 100_000L);
        // effective wagered = gB - gR = 300000.
        verify(metrics).incBetsPlaced(1, 300_000L);
        // winnings = GX - gB = 200000.
        verify(metrics).incBotWinnings(200_000L);
        assertThat(bot.getLastRoundWinnings()).isEqualTo(200_000L);
    }

    @Test
    @DisplayName("zero refund (gB=500k, gR=0, GX=0 loss): balance -500k, stake 500k, winnings 0 (AD-11)")
    void zeroRefundFullLoss() throws Exception {
        runRound("endGame_noRefund.json");

        // Net = -b + 0 + 0 = -500000.
        assertThat(currentBalance()).isEqualTo(START_BALANCE - 500_000L);
        // No refund -> full bet at risk.
        verify(metrics).incBetsPlaced(1, 500_000L);
        verify(metrics, org.mockito.Mockito.never()).incBotWinnings(org.mockito.ArgumentMatchers.anyLong());
        assertThat(bot.getLastRoundWinnings()).isZero();
    }

    /* ---- round driver: subscribe -> start -> bet -> end ---- */

    private void runRound(String endGameFixture) throws Exception {
        invokeOnSubscribe((SubscribeMessage) parseFixture("subscribe.json"));
        invokeOnStartGame((StartGameMessage) parseFixture("startGame.json"));
        setRemainingTime(50_000L);
        setField("gameState", BettingMiniGameState.BET);

        // Place the bet (debits the full stake).
        assertThat(invokeBetCondition()).isTrue();
        invokeBetSupplier();
        assertThat(currentBalance()).isEqualTo(START_BALANCE - CHOSEN_AMOUNT);

        invokeOnEndGame((EndGameMessage) parseFixture(endGameFixture));
    }

    /* ---- fixture parsing ---- */

    private BettingMiniMessage parseFixture(String name) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new MiniGameTaiXiuMessageTypes().getTypeRegistrations());
        try (var in = getClass().getResourceAsStream("/messages/taixiu/" + name)) {
            assertThat(in).as("fixture /messages/taixiu/" + name).isNotNull();
            return mapper.readValue(in.readAllBytes(), BettingMiniMessage.class);
        }
    }

    /* ---- reflective handler / supplier invocation ---- */

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

    /* ---- low-level reflection ---- */

    private static Body readBody(ActionRequestMessage msg) throws Exception {
        Field f = ActionRequestMessage.class.getDeclaredField("body");
        f.setAccessible(true);
        return (Body) f.get(msg);
    }

    private long currentBalance() throws Exception {
        Field f = Bot.class.getDeclaredField("expectedCurrentBalance");
        f.setAccessible(true);
        return ((AtomicLong) f.get(bot)).get();
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
