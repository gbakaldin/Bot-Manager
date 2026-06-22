package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.request.SlotSpin;
import com.vingame.bot.domain.bot.message.slot.SlotMessage;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.message.slot.SlotSpinResultMessage;
import com.vingame.bot.domain.bot.message.slot.SlotSubscribeResponse;
import com.vingame.bot.domain.bot.strategy.slot.RandomBetStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * Phase 7 verification (SLOT_MACHINE_BOT plan): a deterministic, end-to-end-ish
 * drive of the {@code SlotMachineBot} condition→supplier→result loop, mirroring
 * the betting-mini deterministic dispatch tests.
 *
 * <p>The loop is driven by hand (no scenario engine / no threads): pull the
 * {@code spinCondition()} supplier and the {@code spin()} supplier, feed
 * deserialized {@code spinResult.json} frames into {@code onSpinResult}, and pin
 * the plan's invariants at each step:
 * <ul>
 *   <li>AD-12 — no spin fires before the {@code cmd:1300} subscribe response is
 *       received (the pre-subscribe {@code spinCondition()} gate is closed);</li>
 *   <li>AD-6 — one-spin-in-flight: {@code spinCondition()} returns false once a
 *       spin has been built and before its result arrives;</li>
 *   <li>AD-8/AD-13 — the spin's selected winlines are {@code [0..numLines-1]}
 *       drawn from the <em>server-sourced</em> winline count, and the staked
 *       {@code b} is drawn from the server-provided {@code Js[].b} set;</li>
 *   <li>AD-7/AD-13 — balance accounting across the stream: debit the TOTAL stake
 *       {@code b * numLines} per spin on send, credit {@code sum(wls[].crd)} on
 *       result (gross, not net).</li>
 * </ul>
 *
 * <p><b>Accounting note.</b> The per-line bet {@code b} (a value from the server
 * {@code Js} set) is staked across each of {@code numLines} winlines, so the
 * total amount staked per spin is {@code b * numLines}. The debit
 * ({@code creditBalance}), the balance gate ({@code spinCondition}), and the
 * {@code bot_bet_amount_total} metric all record this total stake consistently;
 * the spin request still carries the per-line {@code b}. This test asserts the
 * debit and metric at {@code b * numLines}.
 */
@DisplayName("SlotMachineBot deterministic spin stream (Phase 7)")
class SlotMachineBotSpinStreamTest {

    private static final long START_BALANCE = 50_000_000L;
    private static final List<Long> ALLOWED = List.of(500L, 1000L, 2000L, 5000L, 10000L);
    private static final int NUM_LINES = 25;

    private SlotMachineBot newBot(SlotStrategyId strategyId, Random rng, BotMetrics metrics) throws Exception {
        BotCredentials credentials = BotCredentials.builder()
                .username("slotbot1").password("pw").fingerprint("fp").build();
        Game game = Game.builder()
                .id("g-slot").name("SlotTip").pluginName("Tip")
                .gameType(GameType.SLOT).gameId(204).build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100).maxBet(1000).betIncrement(100)
                .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(3)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(credentials)
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .slotStrategyId(strategyId)
                .zoneName("MiniGame3").timeoutMillis(60_000L)
                .build();

        SlotMachineBot bot = new SlotMachineBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setMessageTypes(new SlotMessageTypesImpl());
        bot.initializeSubclass();
        // Seed the RNG after init (initializeSubclass installs a process-seeded one).
        if (rng != null) {
            bot.setRandom(rng);
        }
        bot.setMetrics(metrics);

        seed(bot, "lastFetchedBalance", START_BALANCE);
        seedAtomic(bot, "expectedCurrentBalance", START_BALANCE);
        return bot;
    }

    @Test
    @DisplayName("Pre-subscribe gate, in-flight gate, server-sourced bet+winlines, single-spin accounting")
    void singleSpin_fullLoopInvariants() throws Exception {
        BotMetrics metrics = mock(BotMetrics.class);
        SlotMachineBot bot = newBot(SlotStrategyId.FIXED, null, metrics);

        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition = (Supplier<Boolean>) invoke(bot, "spinCondition");
        @SuppressWarnings("unchecked")
        Supplier<ActionRequestMessage> spinSupplier = (Supplier<ActionRequestMessage>) invoke(bot, "spin");

        // AD-12: before the 1300 response, the gate is closed (no server config yet).
        assertThat(condition.get()).as("spinCondition before subscribe (AD-12)").isFalse();
        assertThat(pendingBet(bot).get()).as("nothing parked before subscribe").isEmpty();

        // Receive the subscribe response → numLines + allowedBetValues captured.
        invokeOnSubscribe(bot, deserializeSubscribe());
        assertThat(readInt(bot, "numLines")).isEqualTo(NUM_LINES);
        assertThat(readBetValues(bot)).containsExactlyElementsOf(ALLOWED);

        // AD-12: now the gate opens and parks a bet.
        assertThat(condition.get()).as("spinCondition after subscribe").isTrue();
        assertThat(pendingBet(bot).get()).as("bet parked by condition").isPresent();

        long balanceBeforeSpin = bot.getExpectedBalance();

        // spin() pops the parked bet, debits b, sets the in-flight gate.
        ActionRequestMessage out = spinSupplier.get();
        assertThat(out).isInstanceOf(SlotSpin.class);
        SlotSpin.Data data = spinData(out);

        // Staging fix: the bot's spin frame must route to the fixed slot
        // extension, NOT the game's "Tip" pluginName configured in newBot().
        assertThat(pluginName(out))
                .as("spin frame routes to the fixed slot extension, not Game.pluginName")
                .isEqualTo("slotMachinePlugin");

        // AD-8/AD-13: FIXED strategy picks the smallest server-sourced value...
        assertThat(data.getB()).as("staked b from server-sourced set").isEqualTo(500L);
        assertThat(data.getGid()).isEqualTo(204);
        // ...and selects all server-sourced winlines [0..numLines-1].
        assertThat(data.getLs())
                .as("selected winline indices [0..24]")
                .containsExactlyElementsOf(IntStream.range(0, NUM_LINES).boxed().toList());

        // AD-7/AD-13: debit on send is the TOTAL stake = b(500) * numLines(25) = 12_500.
        assertThat(bot.getExpectedBalance()).isEqualTo(balanceBeforeSpin - 12_500L);
        // Local sent-counters bumped by creditBalance with the total stake.
        assertThat(bot.getTotalBetsPlaced().get()).isEqualTo(1L);
        assertThat(bot.getTotalBetAmount().get()).isEqualTo(12_500L);

        // AD-6: a spin is now in flight → the gate is closed even with ample balance.
        assertThat(spinInFlight(bot).get()).as("in-flight gate set by spin()").isTrue();
        assertThat(condition.get()).as("spinCondition while in flight (AD-6)").isFalse();

        long balanceBeforeResult = bot.getExpectedBalance();

        // Result arrives: gross winnings = sum(wls[].crd) = 1000 + 5000 = 6000.
        invokeOnSpinResult(bot, deserializeSpin());

        verify(metrics).incBotMessage("spin");
        verify(metrics).incBotWinnings(6000L);
        verify(metrics).incBetsPlaced(1, 12_500L);

        assertThat(bot.getLastRoundWinnings()).isEqualTo(6000L);
        assertThat(bot.getExpectedBalance()).isEqualTo(balanceBeforeResult + 6000L);
        // AD-6: gate cleared → a new spin may be parked again.
        assertThat(spinInFlight(bot).get()).as("in-flight gate cleared by result").isFalse();
        assertThat(condition.get()).as("spinCondition re-opens after result").isTrue();
    }

    @Test
    @DisplayName("Multi-spin stream: running balance = start - Σb + Σwinnings, N bets recorded")
    void multiSpin_runningBalanceAndBetCount() throws Exception {
        BotMetrics metrics = mock(BotMetrics.class);
        SlotMachineBot bot = newBot(SlotStrategyId.FIXED, null, metrics);

        invokeOnSubscribe(bot, deserializeSubscribe());

        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition = (Supplier<Boolean>) invoke(bot, "spinCondition");
        @SuppressWarnings("unchecked")
        Supplier<ActionRequestMessage> spinSupplier = (Supplier<ActionRequestMessage>) invoke(bot, "spin");

        final int spins = 5;
        long runningBalance = bot.getExpectedBalance();
        long totalStaked = 0L;
        long totalWon = 0L;

        for (int i = 0; i < spins; i++) {
            // One spin must be allowed and exactly one in flight at a time (AD-6).
            assertThat(condition.get()).as("tick %d gate open", i).isTrue();
            ActionRequestMessage out = spinSupplier.get();
            SlotSpin.Data data = spinData(out);
            long perLine = data.getB();
            assertThat(perLine).isEqualTo(500L); // FIXED
            // Total stake = per-line b * numLines(25) = 12_500.
            long staked = perLine * NUM_LINES;
            assertThat(spinInFlight(bot).get()).as("in flight after spin %d", i).isTrue();
            assertThat(condition.get()).as("gate closed while spin %d in flight", i).isFalse();

            runningBalance -= staked;
            totalStaked += staked;
            assertThat(bot.getExpectedBalance()).as("balance debited after spin %d", i).isEqualTo(runningBalance);

            // Each result is the same captured fixture (winnings 6000).
            invokeOnSpinResult(bot, deserializeSpin());
            long won = 6000L;
            runningBalance += won;
            totalWon += won;
            assertThat(spinInFlight(bot).get()).as("gate cleared after result %d", i).isFalse();
            assertThat(bot.getExpectedBalance()).as("balance credited after result %d", i).isEqualTo(runningBalance);
        }

        // Closed-form invariant across the whole stream.
        assertThat(bot.getExpectedBalance()).isEqualTo(START_BALANCE - totalStaked + totalWon);
        // Local sent-counters and server-confirmed bet metric both equal N.
        assertThat(bot.getTotalBetsPlaced().get()).isEqualTo((long) spins);
        assertThat(bot.getTotalBetAmount().get()).isEqualTo(totalStaked);
        verify(metrics, times(spins)).incBetsPlaced(1, 12_500L);
        verify(metrics, times(spins)).incBotWinnings(6000L);
    }

    @Test
    @DisplayName("RANDOM strategy: seeded RNG draws bets only from the server-sourced Js set")
    void randomStrategy_betsDrawnFromServerSet() throws Exception {
        // Seed chosen so the run exercises more than one distinct value.
        SlotMachineBot bot = newBot(SlotStrategyId.RANDOM, new Random(42L), mock(BotMetrics.class));
        // Standalone (no SlotStrategyFactory) → initializeSubclass falls back to an
        // inline FixedBetStrategy regardless of slotStrategyId. Inject the real
        // RandomBetStrategy directly to exercise the random bet-amount path; it
        // reads the bot-owned seeded RNG via SlotBetContext.rng().
        setStrategy(bot, new RandomBetStrategy());

        invokeOnSubscribe(bot, deserializeSubscribe());

        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition = (Supplier<Boolean>) invoke(bot, "spinCondition");
        @SuppressWarnings("unchecked")
        Supplier<ActionRequestMessage> spinSupplier = (Supplier<ActionRequestMessage>) invoke(bot, "spin");

        List<Long> observed = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            assertThat(condition.get()).isTrue();
            ActionRequestMessage out = spinSupplier.get();
            long staked = spinData(out).getB();
            assertThat(ALLOWED).as("every bet is from the server-sourced Js set").contains(staked);
            observed.add(staked);
            invokeOnSpinResult(bot, deserializeSpin());
        }
        // Deterministic sequence for seed 42 over [500,1000,2000,5000,10000]:
        // exactly one nextInt(5) draw per spin (parked by spinCondition, popped by
        // spin() with no re-derive on the happy path).
        assertThat(observed).containsExactly(
                500L, 5000L, 5000L, 10000L, 500L, 500L, 500L, 5000L);
        // Sanity: the seed yields a non-degenerate sequence (more than one value).
        assertThat(observed.stream().distinct().count()).isGreaterThan(1L);
    }

    /* ----- fixtures ----- */

    private SlotSubscribeResponse deserializeSubscribe() throws Exception {
        return (SlotSubscribeResponse) readBody("/messages/slot/subscribeResponse.json");
    }

    private SlotSpinResultMessage deserializeSpin() throws Exception {
        return (SlotSpinResultMessage) readBody("/messages/slot/spinResult.json");
    }

    private SlotMessage readBody(String resource) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new SlotMessageTypesImpl().getTypeRegistrations());
        try (var in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("fixture " + resource).isNotNull();
            JsonNode root = mapper.readTree(in.readAllBytes());
            return mapper.treeToValue(root.get(1), SlotMessage.class);
        }
    }

    /* ----- reflection helpers (mirror the Phase-4 dispatch tests) ----- */

    private static void invokeOnSubscribe(SlotMachineBot b, SlotSubscribeResponse resp) throws Exception {
        ActionResponseMessage<SlotSubscribeResponse> msg =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, resp);
        Method m = SlotMachineBot.class.getDeclaredMethod("onSubscribe", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(b, msg);
    }

    private static void invokeOnSpinResult(SlotMachineBot b, SlotSpinResultMessage spin) throws Exception {
        ActionResponseMessage<SlotSpinResultMessage> msg =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, spin);
        Method m = SlotMachineBot.class.getDeclaredMethod("onSpinResult", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(b, msg);
    }

    private static Object invoke(SlotMachineBot b, String name) throws Exception {
        Method m = SlotMachineBot.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(b);
    }

    private static SlotSpin.Data spinData(ActionRequestMessage out) throws Exception {
        Field bodyField = ActionRequestMessage.class.getDeclaredField("body");
        bodyField.setAccessible(true);
        Body body = (Body) bodyField.get(out);
        return (SlotSpin.Data) body;
    }

    // ActionRequestMessage stores pluginName as a private field with no getter
    // (mirror RequestTest) — read it to assert the slot frame's SmartFox routing.
    private static String pluginName(ActionRequestMessage out) throws Exception {
        Field f = ActionRequestMessage.class.getDeclaredField("pluginName");
        f.setAccessible(true);
        return (String) f.get(out);
    }

    @SuppressWarnings("unchecked")
    private static AtomicBoolean spinInFlight(SlotMachineBot b) throws Exception {
        Field f = SlotMachineBot.class.getDeclaredField("spinInFlight");
        f.setAccessible(true);
        return (AtomicBoolean) f.get(b);
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Optional<Long>> pendingBet(SlotMachineBot b) throws Exception {
        Field f = SlotMachineBot.class.getDeclaredField("pendingBet");
        f.setAccessible(true);
        return (AtomicReference<Optional<Long>>) f.get(b);
    }

    private static void setStrategy(SlotMachineBot b, SlotStrategy strategy) throws Exception {
        Field f = SlotMachineBot.class.getDeclaredField("strategy");
        f.setAccessible(true);
        f.set(b, strategy);
    }

    private static int readInt(SlotMachineBot b, String name) throws Exception {
        Field f = SlotMachineBot.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(b);
    }

    @SuppressWarnings("unchecked")
    private static List<Long> readBetValues(SlotMachineBot b) throws Exception {
        Field f = SlotMachineBot.class.getDeclaredField("allowedBetValues");
        f.setAccessible(true);
        return (List<Long>) f.get(b);
    }

    private static void seed(Object target, String name, long value) {
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
