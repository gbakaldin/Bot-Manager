package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.GameMessageTypesResolver;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.TaiXiuMessageTypes;
import com.vingame.bot.domain.bot.message.request.Bet;
import com.vingame.bot.domain.bot.message.request.TaiXiuBet;
import com.vingame.bot.domain.bot.message.taixiu.TaiXiuStartGameMessage;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BetDecision;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.RoundResult;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.util.BettingMiniGameState;
import com.vingame.bot.domain.brand.model.ProductCode;
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
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 6 verification (TAI_XIU_114_JACKPOT plan): an end-to-end drive of
 * {@link TaiXiuGameBot} wired with the <b>P_114 jackpot provider</b>
 * ({@code resolveTaiXiu(P_114)}). Mirrors {@link TaiXiuGameBotStreamTest} (116) but
 * pins the +100 CMDs and the extra {@code a:false} on the bet, and includes a 116
 * regression guard.
 *
 * <p>Pinned:
 * <ul>
 *   <li>The bot uses the +100 outbound CMDs — subscribe {@code 1105}, bet
 *       {@code 1100} — and the +100 inbound matchers — startGame {@code 1102}, endGame
 *       {@code 1104} — i.e. it consumes the 114 frames.</li>
 *   <li>The bet body is a {@link TaiXiuBet} carrying {@code a:false} with the correct
 *       {@code eid} and tracked {@code sid}.</li>
 *   <li>Refund-aware accounting (gB/gR/G) holds exactly as for 116, including the
 *       captured full-refund round contributing {@code 0} to both stake and winnings.</li>
 *   <li><b>Regression guard:</b> a sibling 116 bot still uses {@code 1005/1002/1004/1000}
 *       and emits a {@link Bet} with NO {@code a} field.</li>
 * </ul>
 */
@DisplayName("TaiXiuGameBot P_114 jackpot stream (Phase 6)")
class TaiXiuJackpotGameBotStreamTest {

    private static final long START_BALANCE = 50_000_000L;
    private static final int CHOSEN_EID = 1;            // Tài (1) vs Xỉu (2)
    private static final long CHOSEN_AMOUNT = 500_000L;
    private static final long BASE_SID = 5971L;

    private TaiXiuGameBot bot;
    private BotMetrics metrics;

    private TaiXiuGameBot newBot(ProductCode product, String pluginName) throws Exception {
        Game game = Game.builder()
                .id("g-taixiu-114").name("TaiXiuJackpot").pluginName(pluginName)
                .offset(999_999)   // poison — Tai Xiu must never read game.getOffset()
                .numberOfOptions(2)
                .build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100_000).maxBet(1_000_000).betIncrement(100_000)
                .maxTotalBetPerRound(2_000_000).minBetsPerRound(1).maxBetsPerRound(1)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("tx114bot1").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .strategyId(StrategyId.RANDOM)
                .build();

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

        TaiXiuGameBot b = new TaiXiuGameBot();
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(cfg);
        b.setStrategyFactory(factory);
        // Wired exactly as production does — through the resolver.
        b.setTaiXiuMessageTypes(GameMessageTypesResolver.resolveTaiXiu(product));
        b.setRandom(new Random(0L));
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
    @DisplayName("114 bot: +100 CMDs (1105/1102/1104/1100), bet carries a:false, refund-aware accounting")
    void p114StreamUsesPlus100CmdsAndAFlag() throws Exception {
        bot = newBot(ProductCode.P_114, "taixiuJackpotPlugin");
        metrics = mock(BotMetrics.class);
        bot.setMetrics(metrics);

        // The +100 CMD seams: subscribe/start/end matchers shifted by the provider.
        assertThat(invokeIntSeam("subscribeCmd")).isEqualTo(1105);
        assertThat(invokeIntSeam("startGameCmd")).isEqualTo(1102);
        assertThat(invokeIntSeam("endGameCmd")).isEqualTo(1104);

        List<String> sequence = List.of(
                "endGame_noRefund.json",      // gB=500k gR=0    G=80k
                "endGame_fullRefund.json",    // gB=500k gR=500k G=0  (captured)
                "endGame_partialRefund.json", // gB=500k gR=200k G=120k
                "endGame_fullRefund.json",    // captured full refund again
                "endGame_noRefund.json"
        );

        long expectedBalance = START_BALANCE;
        long expectedEffectiveWagered = 0L;
        long expectedWinnings = 0L;
        long fullRefundEffective = 0L;
        long fullRefundWinnings = 0L;

        for (int i = 0; i < sequence.size(); i++) {
            String fixture = sequence.get(i);
            long sid = BASE_SID + i;
            Refund r = refundOf(fixture);

            // subscribe (inbound 1105) + start (inbound 1102) open the BET window.
            invokeOnSubscribe((SubscribeMessage) parseFixture("subscribe.json"));
            invokeOnStartGame(startGameWithSid(sid));
            setRemainingTime(50_000L);
            setField("gameState", BettingMiniGameState.BET);

            assertThat(invokeBetCondition()).as("bet gate open round %d", i).isTrue();
            ActionRequestMessage out = invokeBetSupplier();

            // 114 bet uses the TaiXiuBet body (cmd 1100 + a:false).
            assertThat(out).as("114 bet body round %d", i).isInstanceOf(TaiXiuBet.class);
            TaiXiuBet.BetData data = (TaiXiuBet.BetData) readBody(out);
            assertThat(data.getCmd()).as("114 bet cmd round %d", i).isEqualTo(1100);
            assertThat(data.getEid()).isEqualTo(CHOSEN_EID);
            assertThat(data.getB()).isEqualTo(CHOSEN_AMOUNT);
            assertThat(data.getSid()).as("bet sid tracks StartGame round %d", i).isEqualTo(sid);
            assertThat(data.getAid()).isEqualTo(1);
            assertThat(data.isA()).as("a:false round %d", i).isFalse();

            expectedBalance -= CHOSEN_AMOUNT;
            assertThat(currentBalance()).as("balance after bet round %d", i).isEqualTo(expectedBalance);
            seedLong(bot, "lastFetchedBalance", currentBalance());

            // end the round — drives the inbound 1104 matcher + refund accounting.
            invokeOnEndGame((EndGameMessage) parseFixture(fixture));

            expectedBalance += r.refund + r.winnings;
            long effective = r.bet - r.refund;
            expectedEffectiveWagered += effective;
            expectedWinnings += r.winnings;
            if (r.refund == r.bet) {
                fullRefundEffective += effective;
                fullRefundWinnings += r.winnings;
            }

            assertThat(currentBalance()).as("running balance after round %d", i).isEqualTo(expectedBalance);
            assertThat(bot.getLastRoundWinnings()).as("lastRoundWinnings round %d", i).isEqualTo(r.winnings);
        }

        // Refund-aware identity: balance = start − Σb + Σrefund + Σwinnings.
        long totalBet = (long) sequence.size() * CHOSEN_AMOUNT;
        long sumRefund = 0L;
        for (String fixture : sequence) sumRefund += refundOf(fixture).refund;
        assertThat(currentBalance())
                .as("running balance = start − Σb + Σrefund + Σwinnings")
                .isEqualTo(START_BALANCE - totalBet + sumRefund + expectedWinnings);

        // bot_bet_amount_total = Σ(gB − gR) — effective stake, not gross.
        ArgumentCaptor<Integer> countCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Long> amountCap = ArgumentCaptor.forClass(Long.class);
        verify(metrics, times(sequence.size())).incBetsPlaced(countCap.capture(), amountCap.capture());
        long emittedBetAmount = amountCap.getAllValues().stream().mapToLong(Long::longValue).sum();
        assertThat(emittedBetAmount)
                .as("Σ bot_bet_amount_total = Σ(gB − gR)")
                .isEqualTo(expectedEffectiveWagered);
        assertThat(emittedBetAmount).isLessThan(totalBet);

        verify(metrics, atLeastOnce()).incBotWinnings(80_000L);
        verify(metrics).incBotWinnings(120_000L);
        verify(metrics, never()).incBotWinnings(0L);

        // The captured full-refund round contributed exactly 0 to both.
        assertThat(fullRefundEffective).isZero();
        assertThat(fullRefundWinnings).isZero();
    }

    @Test
    @DisplayName("regression: P_116 bot still uses 1005/1002/1004/1000 and emits a Bet with NO 'a' field")
    void p116RegressionUnchanged() throws Exception {
        bot = newBot(ProductCode.P_116, "taixiuPlugin");
        metrics = mock(BotMetrics.class);
        bot.setMetrics(metrics);

        TaiXiuMessageTypes provider116 = GameMessageTypesResolver.resolveTaiXiu(ProductCode.P_116);
        assertThat(provider116.emitsAutoBetFlag()).isFalse();

        assertThat(invokeIntSeam("subscribeCmd")).isEqualTo(1005);
        assertThat(invokeIntSeam("startGameCmd")).isEqualTo(1002);
        assertThat(invokeIntSeam("endGameCmd")).isEqualTo(1004);

        // The 116 fixtures live in the parent folder; drive one bet.
        invokeOnSubscribe((SubscribeMessage) parse116Fixture("subscribe.json"));
        invokeOnStartGame((StartGameMessage) parse116Fixture("startGame.json"));
        setRemainingTime(50_000L);
        setField("gameState", BettingMiniGameState.BET);

        assertThat(invokeBetCondition()).isTrue();
        ActionRequestMessage out = invokeBetSupplier();

        // 116 must use the shared Bet body, cmd 1000, with NO 'a'.
        assertThat(out).isInstanceOf(Bet.class);
        Body body = readBody(out);
        assertThat(body).isInstanceOf(Bet.BetData.class);
        assertThat(((Bet.BetData) body).getCmd()).isEqualTo(1000);
        String json = new ObjectMapper().writeValueAsString(body);
        assertThat(json).as("116 bet must carry no 'a' field").doesNotContain("\"a\"");
    }

    /* ---- refund bookkeeping mirror of the fixtures (gB / gR / G) ---- */

    private record Refund(long bet, long refund, long winnings) {}

    private static Refund refundOf(String fixture) {
        return switch (fixture) {
            case "endGame_fullRefund.json"    -> new Refund(500_000L, 500_000L, 0L);
            case "endGame_partialRefund.json" -> new Refund(500_000L, 200_000L, 120_000L);
            case "endGame_noRefund.json"      -> new Refund(500_000L, 0L, 80_000L);
            default -> throw new IllegalArgumentException("unknown fixture " + fixture);
        };
    }

    /* ---- fixture parsing (114 jackpot subfolder; 116 parent folder) ---- */

    private BettingMiniMessage parseFixture(String name) throws Exception {
        return parseFrom("/messages/taixiu/jackpot/" + name);
    }

    private BettingMiniMessage parse116Fixture(String name) throws Exception {
        return parseFrom("/messages/taixiu/" + name);
    }

    private BettingMiniMessage parseFrom(String resource) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(
                ((TaiXiuMessageTypes) readField("taiXiuMessageTypes")).getTypeRegistrations());
        try (var in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("fixture " + resource).isNotNull();
            return mapper.readValue(in.readAllBytes(), BettingMiniMessage.class);
        }
    }

    private StartGameMessage startGameWithSid(long sid) throws Exception {
        TaiXiuStartGameMessage msg = (TaiXiuStartGameMessage) parseFixture("startGame.json");
        msg.setSid(sid);
        return msg;
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

    private int invokeIntSeam(String name) throws Exception {
        Method m = BettingMiniGameBot.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return (int) m.invoke(bot);
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
