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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7 verification (TAI_XIU_BOT plan): a deterministic, multi-round
 * end-to-end drive of the inherited {@code subscribe → start → bet → end} loop on
 * {@link TaiXiuGameBot}, mixing the three captured EndGame fixtures (full /
 * partial / zero refund). Where Phase 4 ({@link TaiXiuGameBotDispatchTest})
 * exercises each refund case in isolation, this test pins the <b>running totals
 * across a whole sequence</b> and the refund-aware identities (AD-11).
 *
 * <p>Pinned invariants:
 * <ul>
 *   <li><b>Running balance</b> equals the refund-aware identity
 *       {@code start − Σb + Σrefund(gR) + Σwinnings(G)} across the whole stream —
 *       NOT recomputed via {@code GX − gB}; winnings is the {@code G} field
 *       directly (OI-7).</li>
 *   <li><b>{@code bot_bet_amount_total}</b> accumulates {@code Σ(gB − gR)} — the
 *       <i>effective</i> stake, not the gross {@code gB}; a fully-refunded round
 *       contributes exactly {@code 0}.</li>
 *   <li><b>{@code incBetsPlaced}</b> fires once per round; the captured full-refund
 *       round contributes {@code 0} to both winnings and effective wagered.</li>
 *   <li>The cmd matchers use the <b>fixed</b> Tai Xiu CMDs (subscribe 1005,
 *       startGame 1002, endGame 1004) and the bet carries the bare fixed cmd 1000
 *       — no offset arithmetic (AD-3).</li>
 *   <li><b>Negative (AD-9):</b> the bot never reads {@code game.getOffset()} — the
 *       Game is a Mockito spy seeded with a poison offset, and {@code getOffset()}
 *       is verified never-invoked across the full stream.</li>
 * </ul>
 */
@DisplayName("TaiXiuGameBot deterministic round stream (Phase 7)")
class TaiXiuGameBotStreamTest {

    private static final long START_BALANCE = 50_000_000L;
    private static final int CHOSEN_EID = 1;            // Tài (1) vs Xỉu (2)
    private static final long CHOSEN_AMOUNT = 500_000L; // matches captured gB

    /** Distinct sid per round so each round correlates as its own RoundState. */
    private static final long BASE_SID = 2670572L;

    private TaiXiuGameBot bot;
    private BotMetrics metrics;
    private Game gameSpy;

    private TaiXiuGameBot newBot() throws Exception {
        // Mockito spy over the real Game so we can verify getOffset() is NEVER
        // read (AD-9). The poison offset would corrupt every fixed CMD if it leaked.
        gameSpy = spy(Game.builder()
                .id("g-taixiu").name("TaiXiu").pluginName("taixiuPlugin")
                .offset(999_999)            // poison — must never be consulted
                .numberOfOptions(2)
                .build());

        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100_000).maxBet(1_000_000).betIncrement(100_000)
                .maxTotalBetPerRound(2_000_000).minBetsPerRound(1).maxBetsPerRound(1)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("taixiubot1").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(gameSpy).behaviorConfig(behavior)
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

        TaiXiuGameBot b = new TaiXiuGameBot();
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(cfg);
        b.setStrategyFactory(factory);
        b.setTaiXiuMessageTypes(new MiniGameTaiXiuMessageTypes());
        b.setRandom(new Random(0L));
        b.initializeSubclass();
        // The inherited initializeSubclass() dead-stores game.getOffset() into the
        // unused `offset` field (BettingMiniGameBot.java) — a generic setup read,
        // NOT a CMD-derivation use. Clear that one invocation so the AD-9 negative
        // assertion below pins what actually matters: the offset is never consulted
        // during the operational subscribe→start→bet→end stream. The poison value
        // (999_999) is the second guard — if it leaked into any CMD, the fixed-CMD
        // assertions (1005/1002/1004/1000) would already fail.
        //
        // NOTE: this test seeds a NON-null poison offset, so it does not exercise
        // the real production case where a Tai Xiu game's offset is null. That
        // null-offset init path (the staging NPE) is covered directly, without any
        // clearInvocations workaround, by TaiXiuGameBotNullOffsetInitTest.
        org.mockito.Mockito.clearInvocations(gameSpy);

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
    @DisplayName("mixed full/partial/zero-refund stream: running balance + effective stake + per-round bets (AD-11)")
    void mixedRefundStream_runningBalanceAndEffectiveStake() throws Exception {
        bot = newBot();
        metrics = mock(BotMetrics.class);
        bot.setMetrics(metrics);

        // Fixed CMD matchers must be the bare Tai Xiu constants regardless of the
        // poison offset (AD-3): if offset leaked, these would be 1005+999999 etc.
        assertThat(invokeIntSeam("subscribeCmd")).isEqualTo(1005);
        assertThat(invokeIntSeam("startGameCmd")).isEqualTo(1002);
        assertThat(invokeIntSeam("endGameCmd")).isEqualTo(1004);

        // A mixed sequence that includes the captured full-refund round twice and
        // interleaves partial + zero refund. Order is deliberately non-trivial.
        List<String> sequence = List.of(
                "endGame_noRefund.json",      // gB=500k gR=0      G=80k
                "endGame_fullRefund.json",    // gB=500k gR=500k   G=0   (captured)
                "endGame_partialRefund.json", // gB=500k gR=200k   G=120k
                "endGame_fullRefund.json",    // gB=500k gR=500k   G=0   (captured again)
                "endGame_noRefund.json"       // gB=500k gR=0      G=80k
        );

        long expectedBalance = START_BALANCE;
        long expectedEffectiveWagered = 0L; // Σ(gB − gR)
        long expectedWinnings = 0L;         // Σ G
        long fullRefundEffective = 0L;      // effective stake attributed to full-refund rounds
        long fullRefundWinnings = 0L;       // winnings attributed to full-refund rounds

        for (int i = 0; i < sequence.size(); i++) {
            String fixture = sequence.get(i);
            long sid = BASE_SID + i;       // distinct round id per tick
            Refund r = refundOf(fixture);

            // subscribe → start opens the BET window at this round's sid.
            invokeOnSubscribe((SubscribeMessage) parseFixture("subscribe.json"));
            invokeOnStartGame(startGameWithSid(sid));
            setRemainingTime(50_000L);
            setField("gameState", BettingMiniGameState.BET);

            // place the bet: full stake b debited at bet time.
            assertThat(invokeBetCondition()).as("bet gate open round %d", i).isTrue();
            ActionRequestMessage out = invokeBetSupplier();
            Bet.BetData data = (Bet.BetData) readBody(out);
            // bare fixed bet cmd 1000 — never cmdPrefix+3002, never CODE+offset.
            assertThat(data.getCmd()).as("fixed bet cmd round %d", i).isEqualTo(1000);
            assertThat(data.getEid()).isEqualTo(CHOSEN_EID);
            assertThat(data.getSid()).as("bet sid tracks StartGame round %d", i).isEqualTo(sid);

            expectedBalance -= CHOSEN_AMOUNT;
            assertThat(currentBalance()).as("balance after bet round %d", i).isEqualTo(expectedBalance);

            // Keep checkBalance() (called from onNewSession at round end) on its
            // cache path: re-sync lastFetchedBalance to the expected balance so the
            // accumulated cross-round delta never crosses the 1M HTTP-fetch
            // threshold (no WS client is wired in this unit test). This simulates a
            // server fetch returning the bot's own expected balance and keeps the
            // test focused on the refund-aware accounting identity, not the fetch path.
            seedLong(bot, "lastFetchedBalance", currentBalance());

            // end the round with the chosen refund fixture.
            invokeOnEndGame((EndGameMessage) parseFixture(fixture));

            // refund-aware net per round = −b + gR + G (b already subtracted above).
            expectedBalance += r.refund + r.winnings;
            long effective = r.bet - r.refund; // Σ(gB − gR)
            expectedEffectiveWagered += effective;
            expectedWinnings += r.winnings;
            if (r.refund == r.bet) {           // captured full-refund round
                fullRefundEffective += effective;
                fullRefundWinnings += r.winnings;
            }

            assertThat(currentBalance()).as("running balance after round %d", i).isEqualTo(expectedBalance);
            assertThat(bot.getLastRoundWinnings()).as("lastRoundWinnings round %d", i).isEqualTo(r.winnings);
        }

        // Closed-form refund-aware identity across the WHOLE stream:
        // balance = start − Σb + Σrefund + Σwinnings.
        long totalBet = (long) sequence.size() * CHOSEN_AMOUNT;
        // Recompute Σrefund explicitly to assert the identity in the plan's form.
        long sumRefund = 0L;
        for (String fixture : sequence) sumRefund += refundOf(fixture).refund;
        assertThat(currentBalance())
                .as("running balance = start − Σb + Σrefund + Σwinnings (AD-11)")
                .isEqualTo(START_BALANCE - totalBet + sumRefund + expectedWinnings);

        // bot_bet_amount_total accumulates Σ(gB − gR) — effective stake, NOT gross.
        // Sum the totalAmount argument across every incBetsPlaced call.
        ArgumentCaptor<Integer> countCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Long> amountCap = ArgumentCaptor.forClass(Long.class);
        verify(metrics, times(sequence.size())).incBetsPlaced(countCap.capture(), amountCap.capture());
        long emittedBetAmount = amountCap.getAllValues().stream().mapToLong(Long::longValue).sum();
        assertThat(emittedBetAmount)
                .as("Σ bot_bet_amount_total = Σ(gB − gR), effective stake not gross")
                .isEqualTo(expectedEffectiveWagered);
        // gross would be size * gB; assert we are strictly below it (refunds netted).
        assertThat(emittedBetAmount).isLessThan((long) sequence.size() * CHOSEN_AMOUNT);

        // incBetsPlaced fired exactly once per round.
        assertThat(countCap.getAllValues()).hasSize(sequence.size());

        // Winnings emitted only for rounds with G > 0 (full-refund rounds emit none).
        verify(metrics, atLeastOnce()).incBotWinnings(80_000L);  // noRefund rounds
        verify(metrics).incBotWinnings(120_000L);                // partial round
        verify(metrics, never()).incBotWinnings(0L);

        // The captured full-refund round contributed EXACTLY 0 to both.
        assertThat(fullRefundEffective).as("full-refund effective stake").isZero();
        assertThat(fullRefundWinnings).as("full-refund winnings").isZero();

        // Negative assertion (AD-9): the bot never read game.getOffset() across the
        // entire stream — the fixed CMD seams must not consult the poison offset.
        verify(gameSpy, never()).getOffset();
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

    /** Parse startGame.json then override its sid so each round is distinct. */
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
