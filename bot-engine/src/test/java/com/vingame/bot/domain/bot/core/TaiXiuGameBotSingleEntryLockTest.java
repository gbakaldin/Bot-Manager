package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.request.Bet;
import com.vingame.bot.domain.bot.message.taixiu.MiniGameTaiXiuMessageTypes;
import com.vingame.bot.domain.bot.message.taixiu.TaiXiuStartGameMessage;
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
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
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
 * AD-13 verification (TAI_XIU_BOT plan): the Tai Xiu <b>single-entry-per-round
 * lock</b> and <b>1-based entry ids</b> (Tài = eid 1, Xỉu = eid 2).
 *
 * <p>The lock is enforced by {@link TaiXiuGameBot#decideBet} which is routed
 * through by both betting call sites in {@link BettingMiniGameBot}
 * ({@code betCondition()} parks the decision, {@code bet()} pops/re-derives). The
 * locked entry is derived from round memory (the in-flight round's per-option bet
 * map) so it resets automatically on every {@code StartGame}/new sessionId.
 *
 * <p>Pinned invariants:
 * <ul>
 *   <li>First bet of a round passes the strategy's entry choice through unchanged.</li>
 *   <li>A later tick in the SAME round whose strategy flips to the other entry is
 *       remapped back to the locked entry, while the strategy's <i>amount</i> is
 *       preserved (martingale-style increases on the locked side keep working).</li>
 *   <li>Symmetric: holds whether the round opens on Tài or Xỉu.</li>
 *   <li>The lock resets on a new round — the bot is then free to pick either entry.</li>
 *   <li>Emitted eids are always 1-based ({1,2}); a Tai Xiu bet never emits eid 0.</li>
 * </ul>
 */
@DisplayName("TaiXiuGameBot single-entry-per-round lock + 1-based eids (AD-13)")
class TaiXiuGameBotSingleEntryLockTest {

    private static final long START_BALANCE = 50_000_000L;
    private static final long BASE_SID = 2670572L;

    private TaiXiuGameBot bot;

    /** A strategy whose desired entry + amount can be flipped between ticks. */
    private static final class MutableStrategy implements BettingStrategy {
        volatile int optionId;
        volatile long amount;

        MutableStrategy(int optionId, long amount) {
            this.optionId = optionId;
            this.amount = amount;
        }

        @Override
        public Optional<BetDecision> decide(BetContext context) {
            return Optional.of(new BetDecision(optionId, amount));
        }

        @Override
        public void onRoundEnd(RoundResult result) { /* no-op */ }
    }

    private MutableStrategy strategy;

    private TaiXiuGameBot newBot() throws Exception {
        Game game = Game.builder()
                .id("g-taixiu").name("TaiXiu").pluginName("taixiuPlugin")
                .numberOfOptions(2)
                .build();

        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100_000).maxBet(2_000_000).betIncrement(100_000)
                .maxTotalBetPerRound(10_000_000).minBetsPerRound(1).maxBetsPerRound(5)
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

        strategy = new MutableStrategy(TaiXiuGameBot.TAI_EID, 500_000L);
        BettingStrategyFactory factory = mock(BettingStrategyFactory.class);
        when(factory.create(StrategyId.RANDOM)).thenReturn(strategy);

        TaiXiuGameBot b = new TaiXiuGameBot();
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(cfg);
        b.setStrategyFactory(factory);
        b.setTaiXiuMessageTypes(new MiniGameTaiXiuMessageTypes());
        b.setRandom(new Random(0L));
        b.setMetrics(mock(BotMetrics.class));
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
    @DisplayName("1-based eids: emitted entry is always in {1,2}, never 0")
    void emittedEidsAreOneBased() throws Exception {
        bot = newBot();
        openRound(BASE_SID);

        strategy.optionId = TaiXiuGameBot.TAI_EID;
        assertThat(eidOf(placeBet())).isEqualTo(1L);
        assertThat(TaiXiuGameBot.TAI_EID).isEqualTo(1);
        assertThat(TaiXiuGameBot.XIU_EID).isEqualTo(2);

        openRound(BASE_SID + 1);
        strategy.optionId = TaiXiuGameBot.XIU_EID;
        long eid = eidOf(placeBet());
        assertThat(eid).isEqualTo(2L);
        assertThat(eid).isNotZero();
    }

    @Test
    @DisplayName("first bet of a round passes the strategy entry through unchanged")
    void firstBetUnconstrained() throws Exception {
        bot = newBot();

        openRound(BASE_SID);
        strategy.optionId = TaiXiuGameBot.XIU_EID;
        assertThat(eidOf(placeBet())).as("first bet = strategy's choice (Xỉu)").isEqualTo(2L);

        openRound(BASE_SID + 1);
        strategy.optionId = TaiXiuGameBot.TAI_EID;
        assertThat(eidOf(placeBet())).as("first bet = strategy's choice (Tài)").isEqualTo(1L);
    }

    @Test
    @DisplayName("lock holds Tài: after betting Tài, a Xỉu-wanting tick stays Tài with strategy's amount")
    void lockHoldsStartingTai() throws Exception {
        bot = newBot();
        openRound(BASE_SID);

        // First bet: Tài (eid 1).
        strategy.optionId = TaiXiuGameBot.TAI_EID;
        strategy.amount = 500_000L;
        Bet.BetData first = placeBet();
        assertThat(first.getEid()).isEqualTo(1L);
        assertThat(first.getB()).isEqualTo(500_000L);

        // Same round, strategy now wants Xỉu with a martingale-bumped amount.
        strategy.optionId = TaiXiuGameBot.XIU_EID;
        strategy.amount = 1_000_000L;
        Bet.BetData second = placeBet();
        assertThat(second.getEid()).as("entry locked to Tài despite strategy wanting Xỉu").isEqualTo(1L);
        assertThat(second.getB()).as("strategy's increased amount is preserved").isEqualTo(1_000_000L);
    }

    @Test
    @DisplayName("lock holds Xỉu: after betting Xỉu, a Tài-wanting tick stays Xỉu with strategy's amount")
    void lockHoldsStartingXiu() throws Exception {
        bot = newBot();
        openRound(BASE_SID);

        // First bet: Xỉu (eid 2).
        strategy.optionId = TaiXiuGameBot.XIU_EID;
        strategy.amount = 300_000L;
        Bet.BetData first = placeBet();
        assertThat(first.getEid()).isEqualTo(2L);

        // Same round, strategy now wants Tài, bumped amount.
        strategy.optionId = TaiXiuGameBot.TAI_EID;
        strategy.amount = 900_000L;
        Bet.BetData second = placeBet();
        assertThat(second.getEid()).as("entry locked to Xỉu despite strategy wanting Tài").isEqualTo(2L);
        assertThat(second.getB()).as("strategy's increased amount is preserved").isEqualTo(900_000L);
    }

    @Test
    @DisplayName("lock resets per round: after a new sessionId the bot can pick the other entry")
    void lockResetsOnNewRound() throws Exception {
        bot = newBot();

        // Round 1: lock onto Tài.
        openRound(BASE_SID);
        strategy.optionId = TaiXiuGameBot.TAI_EID;
        assertThat(eidOf(placeBet())).isEqualTo(1L);
        strategy.optionId = TaiXiuGameBot.XIU_EID;
        assertThat(eidOf(placeBet())).as("still locked to Tài in round 1").isEqualTo(1L);

        // Round 2: new sessionId resets the lock — strategy now freely picks Xỉu.
        openRound(BASE_SID + 1);
        strategy.optionId = TaiXiuGameBot.XIU_EID;
        assertThat(eidOf(placeBet())).as("lock reset, Xỉu now allowed in round 2").isEqualTo(2L);
        strategy.optionId = TaiXiuGameBot.TAI_EID;
        assertThat(eidOf(placeBet())).as("now locked to Xỉu in round 2").isEqualTo(2L);
    }

    @Test
    @DisplayName("re-derive fallback: bet() supplier with NO parked decision still honors the lock")
    void reDeriveFallbackHonorsLock() throws Exception {
        bot = newBot();
        openRound(BASE_SID);

        // First bet via the normal park→pop path: lock onto Tài.
        strategy.optionId = TaiXiuGameBot.TAI_EID;
        strategy.amount = 400_000L;
        assertThat(eidOf(placeBet())).isEqualTo(1L);

        // Now simulate the park/re-derive race: condition parks a decision, but a
        // concurrent beforeReconnect clears pendingDecision before the supplier
        // runs. The supplier must re-derive via decideBet() and still produce Tài,
        // even though the strategy is now flipped to Xỉu. This is the path the
        // existing placeBet() helper never exercises (it always pops a parked value).
        strategy.optionId = TaiXiuGameBot.XIU_EID;
        strategy.amount = 800_000L;
        assertThat(invokeBetCondition()).as("condition parks a (remapped) decision").isTrue();
        clearPendingDecision(); // emulate beforeReconnect wiping the parked value

        Bet.BetData reDerived = (Bet.BetData) readBody(invokeBetSupplier());
        assertThat(reDerived.getEid())
                .as("re-derive path agrees with parked path: still locked to Tài")
                .isEqualTo(1L);
        assertThat(reDerived.getB())
                .as("re-derived amount is the strategy's current amount")
                .isEqualTo(800_000L);
    }

    @Test
    @DisplayName("park vs re-derive cannot disagree: both call sites read the same round memory")
    void parkAndReDeriveAgreeWithinRound() throws Exception {
        bot = newBot();
        openRound(BASE_SID);

        // Lock onto Xỉu first.
        strategy.optionId = TaiXiuGameBot.XIU_EID;
        strategy.amount = 200_000L;
        assertThat(eidOf(placeBet())).isEqualTo(2L);

        // Strategy flips to Tài. Compute the PARKED entry (via condition) and the
        // RE-DERIVED entry (via supplier with pending cleared) for the SAME tick;
        // they must be identical.
        strategy.optionId = TaiXiuGameBot.TAI_EID;
        strategy.amount = 600_000L;

        assertThat(invokeBetCondition()).isTrue();
        long parkedEid = parkedEntry();        // peek what condition parked
        clearPendingDecision();
        long reDerivedEid = ((Bet.BetData) readBody(invokeBetSupplier())).getEid();

        assertThat(parkedEid).as("parked decision entry").isEqualTo(2L);
        assertThat(reDerivedEid).as("re-derived entry equals parked entry").isEqualTo(parkedEid);
    }

    @Test
    @DisplayName("lock holds across MANY bets in one round with martingale flipping every tick")
    void lockHoldsAcrossManyBets() throws Exception {
        bot = newBot();
        openRound(BASE_SID);

        // First bet: Tài.
        strategy.optionId = TaiXiuGameBot.TAI_EID;
        strategy.amount = 100_000L;
        Bet.BetData first = placeBet();
        assertThat(first.getEid()).isEqualTo(1L);
        assertThat(first.getB()).isEqualTo(100_000L);

        // Five more ticks: strategy flips to Xỉu every time and doubles the stake
        // (martingale-on-the-wrong-side). Every emitted bet must stay Tài (eid 1)
        // while the strategy's increasing amount is preserved verbatim.
        long amount = 100_000L;
        for (int tick = 1; tick <= 5; tick++) {
            amount *= 2;
            // Strategy alternates the side it WANTS so we never accidentally line
            // up with the lock: odd ticks want Xỉu, even ticks want Tài-again.
            strategy.optionId = (tick % 2 == 1) ? TaiXiuGameBot.XIU_EID : TaiXiuGameBot.TAI_EID;
            strategy.amount = amount;
            Bet.BetData b = placeBet();
            assertThat(b.getEid()).as("tick " + tick + " stays locked to Tài").isEqualTo(1L);
            assertThat(b.getB()).as("tick " + tick + " preserves strategy amount").isEqualTo(amount);
        }
    }

    @Test
    @DisplayName("stale lock cannot leak across rounds even though pendingDecision is not cleared on StartGame")
    void staleLockDoesNotLeakAcrossStartGame() throws Exception {
        bot = newBot();
        openRound(BASE_SID);

        // Round 1: lock onto Tài, then park (but do NOT consume) a remapped
        // decision — leaving a stale value in pendingDecision exactly like a tick
        // that straddles the round boundary.
        strategy.optionId = TaiXiuGameBot.TAI_EID;
        strategy.amount = 500_000L;
        assertThat(eidOf(placeBet())).isEqualTo(1L);
        strategy.optionId = TaiXiuGameBot.XIU_EID;
        assertThat(invokeBetCondition()).as("round-1 condition parks a stale Tài decision").isTrue();

        // New StartGame with a fresh sessionId. onStartGame does NOT clear
        // pendingDecision by design — beginRound clears the round bet map, which is
        // what the lock derives from. The first bet of round 2 must therefore be
        // free: strategy wants Xỉu and Xỉu must be emitted.
        invokeHandler("onStartGame", startGameWithSid(BASE_SID + 1));
        setRemainingTime(50_000L);
        setField("gameState", BettingMiniGameState.BET);

        strategy.optionId = TaiXiuGameBot.XIU_EID;
        strategy.amount = 700_000L;
        Bet.BetData round2First = placeBet();
        assertThat(round2First.getEid())
                .as("round-2 first bet is unconstrained — no stale Tài lock from round 1")
                .isEqualTo(2L);
        assertThat(round2First.getB()).isEqualTo(700_000L);
    }

    @Test
    @DisplayName("defensive: a multi-key round-bet map locks to the first entry by iteration order")
    void defensiveMultiKeyMapLocksToFirstEntry() throws Exception {
        bot = newBot();
        openRound(BASE_SID);

        // Force the (should-never-happen) state where the round bet map has BOTH
        // entries recorded. The lock derivation must still return a value in {1,2}
        // (the first by iteration order) and never null/0.
        recordBetSent(BASE_SID, TaiXiuGameBot.TAI_EID, 100_000L);
        recordBetSent(BASE_SID, TaiXiuGameBot.XIU_EID, 100_000L);

        strategy.optionId = TaiXiuGameBot.XIU_EID;
        strategy.amount = 300_000L;
        Bet.BetData b = placeBet();
        assertThat(b.getEid()).as("locked entry is one of the recorded 1-based ids").isIn(1L, 2L);
        assertThat(b.getEid()).as("never collapses to 0").isNotZero();
    }

    /* ---- round / bet helpers ---- */

    /** subscribe → start → open BET window for the given sid. */
    private void openRound(long sid) throws Exception {
        invokeHandler("onSubscribe", (BettingMiniMessage) parseFixture("subscribe.json"));
        invokeHandler("onStartGame", startGameWithSid(sid));
        setRemainingTime(50_000L);
        setField("gameState", BettingMiniGameState.BET);
    }

    /** Drive betCondition() then bet() and return the emitted bet body. */
    private Bet.BetData placeBet() throws Exception {
        assertThat(invokeBetCondition()).as("bet gate open").isTrue();
        ActionRequestMessage out = invokeBetSupplier();
        return (Bet.BetData) readBody(out);
    }

    private long eidOf(Bet.BetData data) {
        return data.getEid();
    }

    private StartGameMessage startGameWithSid(long sid) throws Exception {
        TaiXiuStartGameMessage msg = (TaiXiuStartGameMessage) parseFixture("startGame.json");
        msg.setSid(sid);
        return msg;
    }

    private BettingMiniMessage parseFixture(String name) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new MiniGameTaiXiuMessageTypes().getTypeRegistrations());
        try (var in = getClass().getResourceAsStream("/messages/taixiu/" + name)) {
            assertThat(in).as("fixture /messages/taixiu/" + name).isNotNull();
            return mapper.readValue(in.readAllBytes(), BettingMiniMessage.class);
        }
    }

    /* ---- reflective invocation ---- */

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

    private static Body readBody(ActionRequestMessage msg) throws Exception {
        Field f = ActionRequestMessage.class.getDeclaredField("body");
        f.setAccessible(true);
        return (Body) f.get(msg);
    }

    /** Emulate a beforeReconnect wiping the parked decision before bet() runs. */
    @SuppressWarnings("unchecked")
    private void clearPendingDecision() throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField("pendingDecision");
        f.setAccessible(true);
        ((AtomicReference<Optional<BetDecision>>) f.get(bot)).set(Optional.empty());
    }

    /** Peek the entry currently parked by betCondition() without consuming it. */
    @SuppressWarnings("unchecked")
    private long parkedEntry() throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField("pendingDecision");
        f.setAccessible(true);
        Optional<BetDecision> parked = ((AtomicReference<Optional<BetDecision>>) f.get(bot)).get();
        assertThat(parked).as("a decision is parked").isPresent();
        return parked.get().optionId();
    }

    /** Force a bet into the in-flight round memory (defensive-path setup). */
    private void recordBetSent(long sid, int optionId, long amount) throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField("memory");
        f.setAccessible(true);
        BotMemory memory = (BotMemory) f.get(bot);
        memory.recordBetSent(sid, optionId, amount);
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
