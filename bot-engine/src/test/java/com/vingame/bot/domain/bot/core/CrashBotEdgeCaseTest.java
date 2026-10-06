package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Phase;
import com.vingame.bot.domain.bot.core.crash.CrashRoundStateMachine.Plan;
import com.vingame.bot.domain.bot.message.crash.CrashBetAck;
import com.vingame.bot.domain.bot.message.crash.CrashBettingClosed;
import com.vingame.bot.domain.bot.message.crash.CrashCashoutAck;
import com.vingame.bot.domain.bot.message.crash.CrashMessage;
import com.vingame.bot.domain.bot.message.crash.CrashRoundEnd;
import com.vingame.bot.domain.bot.message.crash.CrashRoundStart;
import com.vingame.bot.domain.bot.message.crash.CrashSubscribeResponse;
import com.vingame.bot.domain.bot.message.g4.win79.crash.Win79CrashMessageTypes;
import com.vingame.bot.domain.bot.message.g4.win79.crash.Win79CrashTick;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.ScopedDebugEscalator;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AVIATOR_BOT QA: {@link CrashBot} edge cases {@code CrashBotDispatchTest} does not reach —
 * send failures, a bet task racing betting-closed inside the CAS, an early-firing bet task,
 * the session-check skip, duplicate and stale frames, reconnects mid-bet in every in-flight
 * phase, a cash-out decided before the own runner crashed, the stake/balance boundary,
 * and the silence task's escalation and MDC propagation.
 *
 * <p>Same harness as {@code CrashBotDispatchTest}: real 119 frames through the production
 * Jackson registrations, scheduled tasks captured and run by hand against a fake clock,
 * real Micrometer counters read back.
 */
@DisplayName("CrashBot edge cases (QA)")
class CrashBotEdgeCaseTest {

    private static final long START_BALANCE = 50_000_000L;
    private static final int OFFSET = 1700;
    private static final int JAKE = 1;
    private static final int NEYTIRI = 2;
    private static final long SID = 1_638_119L;
    private static final long NEXT_SID = 1_638_120L;
    private static final long WINDOW_MS = 180_000L;

    private static final String CASHOUT_ACK = "{\"eid\":1,\"b\":10000,\"wm\":24000,\"cmd\":1703,\"aid\":1,\"odd\":2.4}";

    private final AtomicLong now = new AtomicLong(10_000_000L);
    /** One-shot hook run on the next clock read — the race seam for "inside tryPlace". */
    private final AtomicReference<Runnable> onNextClockRead = new AtomicReference<>();
    private final LongSupplier clock = () -> {
        Runnable hook = onNextClockRead.getAndSet(null);
        if (hook != null) {
            hook.run();
        }
        return now.get();
    };
    private final List<String> reconnectReasons = new ArrayList<>();
    private final List<Scheduled> scheduled = new ArrayList<>();
    private final ScriptedRandom random = new ScriptedRandom(7L);
    private final ObjectMapper reader = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ObjectMapper inbound = inboundMapper();

    /** Run inside {@code checkBalance}, i.e. while {@code sessionCheckInProgress} is set. */
    private Runnable checkBalanceHook;

    private SimpleMeterRegistry registry;
    private VingameWebSocketClient channel;
    private CrashBot bot;

    private record Scheduled(Runnable task, long delayMs, ScheduledFuture<?> future) {
    }

    private static ObjectMapper inboundMapper() {
        ObjectMapper m = new ObjectMapper();
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        m.registerSubtypes(new Win79CrashMessageTypes().getTypeRegistrations(OFFSET));
        return m;
    }

    /** Scripted draws over a seeded fallback. Machine draw order: delay; stake index, target, runner. */
    static final class ScriptedRandom extends Random {
        final Deque<Integer> ints = new ArrayDeque<>();
        final Deque<Double> doubles = new ArrayDeque<>();
        final Deque<Long> longs = new ArrayDeque<>();

        ScriptedRandom(long seed) {
            super(seed);
        }

        @Override
        public int nextInt(int bound) {
            Integer next = ints.pollFirst();
            return next != null ? next : super.nextInt(bound);
        }

        @Override
        public double nextDouble() {
            Double next = doubles.pollFirst();
            return next != null ? next : super.nextDouble();
        }

        @Override
        public long nextLong(long bound) {
            Long next = longs.pollFirst();
            return next != null ? next : super.nextLong(bound);
        }

        /** One placement on the [10000, 200000] step-5000 ladder, LEGACY target range. */
        void plan(long amount, long targetH, int eid) {
            ints.addLast((int) ((amount - 10_000L) / 5_000L));
            doubles.addLast((targetH / 100.0 - 1.1) / 3.9);
            ints.addLast(eid - 1);
        }
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        channel = mock(VingameWebSocketClient.class);
        when(channel.isOpen()).thenReturn(true); // a live socket, as in production
        BotMdc.set("group-crash", 1, "env-119", "119", "CRASH", "g-aviator", "Aviator", "crashbot1");
        bot = newBot();
    }

    @AfterEach
    void tearDown() {
        bot.cleanup();
        BotMdc.clear();
    }

    private CrashBot newBot() {
        Game game = Game.builder()
                .id("g-aviator").name("Aviator").pluginName("aviatorPlugin")
                .gameType(GameType.CRASH).offset(OFFSET).build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(10_000L).maxBet(200_000L).betIncrement(5_000L).autoDepositEnabled(false).build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("crashbot1").password("pw").fingerprint("fp").build())
                .environmentId("env-119").botGroupId("group-crash").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(WINDOW_MS / 1_000L)
                .build();

        CrashBot b = new CrashBot() {
            @Override
            protected void triggerFullReconnect(String reason) {
                reconnectReasons.add(reason);
            }

            @Override
            ScheduledFuture<?> schedule(Runnable task, long delayMs) {
                ScheduledFuture<?> future = mock(ScheduledFuture.class);
                scheduled.add(new Scheduled(task, delayMs, future));
                return future;
            }

            @Override
            protected long checkBalance() {
                Runnable hook = checkBalanceHook;
                if (hook != null) {
                    hook.run();
                }
                return expectedCurrentBalance.get();
            }
        };
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(cfg);
        b.setMessageTypes(new Win79CrashMessageTypes());
        b.setClock(clock);
        b.setRandom(random);
        b.setMetrics(new BotMetrics(registry));
        b.initializeSubclass();
        b.lastFetchedBalance = START_BALANCE;
        b.expectedCurrentBalance.set(START_BALANCE);
        b.bindSendChannel(channel, new ObjectMapper());
        return b;
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private <T extends CrashMessage> ActionResponseMessage<T> msg(String json) {
        try {
            return new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE,
                    (T) inbound.readValue(json, CrashMessage.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void subscribe() {
        bot.onSubscribe(this.<CrashSubscribeResponse>msg("{\"cmd\":1700,\"sid\":1638118}"));
    }

    private void roundStart(long sid) {
        bot.onRoundStart(this.<CrashRoundStart>msg("{\"cmd\":1705,\"iOE\":true,\"sid\":" + sid + "}"));
    }

    private void betAck(int eid, long b) {
        bot.onBetAck(this.<CrashBetAck>msg("{\"eid\":" + eid + ",\"b\":" + b + ",\"cmd\":1702}"));
    }

    private void bettingClosed(long sid) {
        bot.onBettingClosed(this.<CrashBettingClosed>msg("{\"iUC\":100,\"cmd\":1706,\"sid\":" + sid + "}"));
    }

    private void tick(long sid, double jOdd, double nOdd, boolean jFi, boolean nFi) {
        bot.onTick(new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE,
                new Win79CrashTick(OFFSET + 9, sid, jOdd, nOdd, jFi, nFi)));
    }

    private void tick(long sid, double odd) {
        tick(sid, odd, odd, false, false);
    }

    private void cashoutAck(String json) {
        bot.onCashoutAck(this.<CrashCashoutAck>msg(json));
    }

    private void roundEnd(long sid) {
        bot.onRoundEnd(this.<CrashRoundEnd>msg(
                "{\"nOdd\":2.86,\"b\":10000,\"jOdd\":11.59,\"cmd\":1707,\"sid\":" + sid + "}"));
    }

    private Scheduled last() {
        return scheduled.get(scheduled.size() - 1);
    }

    /** Round start with a scripted delay, the plan scripted, the bet task run at its due time. */
    private void openAndPlace(long sid, long amount, long targetH, int eid) {
        random.longs.addLast(18L);
        int before = scheduled.size();
        roundStart(sid);
        assertThat(scheduled).hasSize(before + 1);
        Scheduled task = last();
        now.addAndGet(task.delayMs());
        random.plan(amount, targetH, eid);
        task.task().run();
    }

    /** {@link #openAndPlace} plus the matching ack: LIVE. */
    private void live(long sid, long amount, long targetH, int eid) {
        openAndPlace(sid, amount, targetH, eid);
        betAck(eid, amount);
        assertThat(bot.machine().phase()).isEqualTo(Phase.LIVE);
    }

    private double count(String name, String... tags) {
        Counter c = registry.find(name).tags(tags).counter();
        return c == null ? 0.0 : c.count();
    }

    private double outcome(String o) {
        return count(BotMetrics.BOT_CRASH_BETS_TOTAL, "outcome", o);
    }

    private double allOutcomes() {
        return outcome("cashout") + outcome("crash") + outcome("unacked");
    }

    private List<JsonNode> sentWithCmd(int cmd) throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(channel, atLeast(0)).send(captor.capture());
        List<JsonNode> bodies = new ArrayList<>();
        for (String s : captor.getAllValues()) {
            JsonNode body = reader.readTree(s).get(3);
            if (body.get("cmd").asInt() == cmd) {
                bodies.add(body);
            }
        }
        return bodies;
    }

    // ================================================================== send failures

    @Test
    @DisplayName("bet send throws: no exception leaves the task, the local debit stands, WARN once then DEBUG, 1707 counts unacked")
    void betSendFailure() {
        doThrow(new IllegalStateException("socket gone")).when(channel).send(anyString());
        subscribe();

        try (LogCapture logs = new LogCapture()) {
            assertThatCode(() -> openAndPlace(SID, 10_000L, 240L, JAKE)).doesNotThrowAnyException();
            assertThat(bot.machine().phase()).isEqualTo(Phase.PLACED);
            assertThat(bot.getExpectedBalance()).isEqualTo(START_BALANCE - 10_000L);
            roundEnd(SID);

            assertThatCode(() -> openAndPlace(NEXT_SID, 10_000L, 240L, JAKE)).doesNotThrowAnyException();
            roundEnd(NEXT_SID);

            assertThat(logs.at(Level.WARN)).as("WARN once per bot").hasSize(1);
            assertThat(logs.at(Level.WARN).get(0)).contains("crash bet frame not sent");
            assertThat(logs.containing("frame not sent")).as("the second failure at DEBUG").hasSize(2);
        }
        assertThat(outcome("unacked")).isEqualTo(2.0);
        assertThat(count(BotMetrics.BOT_BETS_PLACED_TOTAL)).isZero();
    }

    @Test
    @DisplayName("no send channel bound yet: the bet task does not throw and the bet ends unacked")
    void unboundChannel() {
        bot.bindSendChannel(null, null);
        subscribe();

        assertThatCode(() -> openAndPlace(SID, 10_000L, 240L, JAKE)).doesNotThrowAnyException();
        roundEnd(SID);

        assertThat(outcome("unacked")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("cash-out send throws: exactly one attempt per bet (no retry on later ticks), counts crash by the next round start, no winnings")
    void cashoutSendFailure() {
        subscribe();
        live(SID, 10_000L, 200L, JAKE);
        doThrow(new IllegalStateException("socket gone")).when(channel).send(anyString());

        assertThatCode(() -> tick(SID, 2.00)).doesNotThrowAnyException();
        tick(SID, 2.10);
        tick(SID, 2.50);
        roundEnd(SID);
        // Review B2: a pending cash-out survives its 1707 (CLOSING); the next round start resolves it.
        assertThat(allOutcomes()).isZero();
        random.longs.addLast(0L);
        roundStart(NEXT_SID);

        verify(channel, times(2)).send(anyString()); // the bet + one cash-out attempt
        assertThat(outcome("crash")).isEqualTo(1.0);
        assertThat(outcome("cashout")).isZero();
        assertThat(count(BotMetrics.BOT_WINNINGS_TOTAL)).isZero();
    }

    // ================================================================== bet task races

    @Test
    @DisplayName("1706 lands inside tryPlace (between the read and the CAS): no bet, no debit, no reschedule")
    void bettingClosedRacesTryPlace() {
        subscribe();
        random.longs.addLast(2_000L);
        roundStart(SID);
        Scheduled task = last();
        now.addAndGet(task.delayMs());
        random.plan(10_000L, 240L, JAKE);
        int before = scheduled.size();

        // tryPlace's only clock read happens after it has read OPEN and before its CAS.
        onNextClockRead.set(() -> bettingClosed(SID));
        task.task().run();

        verify(channel, never()).send(anyString());
        assertThat(bot.getExpectedBalance()).isEqualTo(START_BALANCE);
        assertThat(bot.machine().phase()).isEqualTo(Phase.WAITING);
        assertThat(scheduled).as("a closed round is not retried").hasSize(before);
    }

    @Test
    @DisplayName("a bet task that fires before betAt re-arms for exactly the remainder, then places")
    void earlyBetTaskReschedules() throws Exception {
        subscribe();
        random.longs.addLast(3_000L);
        roundStart(SID);
        Scheduled first = last();
        now.addAndGet(2_000L); // the scheduler ran 1 s early relative to the machine's clock

        first.task().run();

        verify(channel, never()).send(anyString());
        assertThat(scheduled).hasSize(2);
        assertThat(last().delayMs()).isEqualTo(1_000L);

        now.addAndGet(1_000L);
        random.plan(15_000L, 240L, NEYTIRI);
        last().task().run();
        List<JsonNode> bets = sentWithCmd(1702);
        assertThat(bets).hasSize(1);
        assertThat(bets.get(0).get("b").asLong()).isEqualTo(15_000L);
        assertThat(bets.get(0).get("eid").asInt()).isEqualTo(NEYTIRI);
    }

    @Test
    @DisplayName("a bet task that fires during the between-rounds session check skips its round")
    void sessionCheckSkipsTheBet() {
        subscribe();
        random.longs.addLast(500L);
        roundStart(SID);
        Scheduled task = last();
        now.addAndGet(task.delayMs());
        random.plan(10_000L, 240L, JAKE);
        checkBalanceHook = task.task();

        bot.onNewSession();

        verify(channel, never()).send(anyString());
        assertThat(bot.machine().phase()).as("the round stays open, nothing placed").isEqualTo(Phase.OPEN);
    }

    @Test
    @DisplayName("a bet task for a round that has since been superseded places nothing")
    void staleBetTask() {
        subscribe();
        random.longs.addLast(4_000L);
        roundStart(SID);
        Scheduled old = last();
        verify(channel, never()).send(anyString());

        random.longs.addLast(4_000L);
        roundStart(NEXT_SID); // 1707 lost
        verify(old.future()).cancel(false);
        now.addAndGet(4_000L);
        old.task().run(); // ran anyway (cancel raced the fire)

        verify(channel, never()).send(anyString());
        assertThat(bot.machine().phase()).isEqualTo(Phase.OPEN);
        assertThat(bot.machine().sid()).isEqualTo(NEXT_SID);
    }

    // ================================================================== stale / duplicate frames

    @Test
    @DisplayName("a duplicate 1705 schedules no second bet task and sends no second bet")
    void duplicateRoundStart() throws Exception {
        subscribe();
        openAndPlace(SID, 10_000L, 240L, JAKE);
        int before = scheduled.size();

        roundStart(SID);

        assertThat(scheduled).hasSize(before);
        assertThat(sentWithCmd(1702)).hasSize(1);
        assertThat(bot.machine().phase()).isEqualTo(Phase.PLACED);
    }

    @Test
    @DisplayName("duplicate acks and a duplicate 1707 count once: 1 placed, 24000 won, 1 cashout outcome")
    void duplicateAcksAndRoundEnd() {
        subscribe();
        live(SID, 10_000L, 240L, JAKE);
        betAck(JAKE, 10_000L);
        tick(SID, 2.40);
        cashoutAck(CASHOUT_ACK);
        cashoutAck(CASHOUT_ACK);
        roundEnd(SID);
        roundEnd(SID);

        assertThat(count(BotMetrics.BOT_BETS_PLACED_TOTAL)).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_WINNINGS_TOTAL)).isEqualTo(24_000.0);
        assertThat(bot.getExpectedBalance()).isEqualTo(START_BALANCE - 10_000L + 24_000L);
        assertThat(outcome("cashout")).isEqualTo(1.0);
        assertThat(allOutcomes()).as("at most one outcome per bet").isEqualTo(1.0);
    }

    @Test
    @DisplayName("ticks while the bet is unacked never cash out, even above target; after the ack the next crossing does")
    void tickBeforeAck() throws Exception {
        subscribe();
        openAndPlace(SID, 10_000L, 150L, JAKE);

        tick(SID, 1.80);
        tick(SID, 2.00);
        assertThat(sentWithCmd(1703)).isEmpty();

        betAck(JAKE, 10_000L);
        tick(SID, 2.10);
        assertThat(sentWithCmd(1703)).hasSize(1);
    }

    @Test
    @DisplayName("a late tick of the previous round, far above target, sends no cash-out for the new round's bet")
    void staleTickOfPreviousRound() throws Exception {
        subscribe();
        live(SID, 10_000L, 300L, JAKE);
        roundEnd(SID);
        live(NEXT_SID, 10_000L, 300L, JAKE);

        tick(SID, 9.99);

        assertThat(sentWithCmd(1703)).isEmpty();
        assertThat(bot.machine().phase()).isEqualTo(Phase.LIVE);
    }

    @Test
    @DisplayName("an ack for the other runner, or for another stake, does not confirm the bet")
    void mismatchedAcks() {
        subscribe();
        openAndPlace(SID, 10_000L, 240L, JAKE);

        betAck(NEYTIRI, 10_000L);
        betAck(JAKE, 15_000L);

        assertThat(bot.machine().phase()).isEqualTo(Phase.PLACED);
        assertThat(count(BotMetrics.BOT_BETS_PLACED_TOTAL)).isZero();
    }

    // ================================================================== crash vs cash-out

    @Test
    @DisplayName("own runner crashes on the first tick seen, frozen above target: crash, no 1703")
    void firstTickSeenIsOwnCrashAboveTarget() throws Exception {
        subscribe();
        live(SID, 10_000L, 200L, NEYTIRI);

        tick(SID, 6.00, 2.86, false, true);

        assertThat(sentWithCmd(1703)).isEmpty();
        assertThat(outcome("crash")).isEqualTo(1.0);
        tick(SID, 6.50, 2.86, false, true);
        roundEnd(SID);
        assertThat(sentWithCmd(1703)).isEmpty();
        assertThat(allOutcomes()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the other runner crashing never settles our bet; our runner crossing later still cashes out")
    void otherRunnerCrashIsIrrelevant() throws Exception {
        subscribe();
        live(SID, 10_000L, 300L, JAKE);

        tick(SID, 2.86, 2.86, false, true); // Neytiri crashes
        assertThat(allOutcomes()).isZero();
        tick(SID, 3.00, 2.86, false, true);

        List<JsonNode> cashouts = sentWithCmd(1703);
        assertThat(cashouts).hasSize(1);
        assertThat(cashouts.get(0).get("eid").asInt()).isEqualTo(JAKE);
    }

    @Test
    @DisplayName("cash-out sent, then own runner's crash tick: nothing more is sent, and a late ack still pays")
    void crashFlagAfterCashoutSent() throws Exception {
        subscribe();
        live(SID, 10_000L, 240L, JAKE);
        tick(SID, 2.40);
        assertThat(sentWithCmd(1703)).hasSize(1);

        tick(SID, 2.45, 2.45, true, false);
        assertThat(allOutcomes()).as("the server decides, not the crash flag").isZero();
        assertThat(sentWithCmd(1703)).hasSize(1);

        cashoutAck(CASHOUT_ACK);
        roundEnd(SID);

        assertThat(outcome("cashout")).isEqualTo(1.0);
        assertThat(allOutcomes()).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_WINNINGS_TOTAL)).isEqualTo(24_000.0);
    }

    @Test
    @DisplayName("cash-out sent but refused (no ack): counts crash by the next round start, no winnings")
    void cashoutRefused() {
        subscribe();
        live(SID, 10_000L, 240L, JAKE);
        tick(SID, 2.40);
        tick(SID, 2.45, 2.45, true, false);

        roundEnd(SID);
        // Review B2: CLOSING until the next round start, in case the ack was only reordered.
        assertThat(bot.machine().phase()).isEqualTo(Phase.CLOSING);
        assertThat(allOutcomes()).isZero();
        random.longs.addLast(0L);
        roundStart(NEXT_SID);

        assertThat(outcome("crash")).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_WINNINGS_TOTAL)).isZero();
        assertThat(bot.getLastRoundWinnings()).isZero();
    }

    /**
     * Review B2, confirmed red. ws-parser runs four inbound workers per client, so an X703
     * ack and the X707 right after it can be handled in either order. Handled 1707-first,
     * today the machine closes CASHING as CRASH and the ack then finds WAITING and is
     * dropped: the server paid {@code wm}, but {@code bot_winnings_total} misses it and
     * {@code outcome="crash"} is over-counted. This asserts the intended behaviour and is
     * the test the review asks for. Enable it with the fix.
     */
    @Test
    @DisplayName("B2: a cash-out ack handled after its 1707 still pays and counts cashout, with exactly one outcome")
    void cashoutAckAfterRoundEnd() {
        subscribe();
        live(SID, 10_000L, 240L, JAKE);
        tick(SID, 2.40);

        roundEnd(SID);
        cashoutAck(CASHOUT_ACK);

        assertThat(outcome("cashout")).isEqualTo(1.0);
        assertThat(outcome("crash")).isZero();
        assertThat(count(BotMetrics.BOT_WINNINGS_TOTAL)).isEqualTo(24_000.0);
        assertThat(bot.getExpectedBalance()).isEqualTo(START_BALANCE - 10_000L + 24_000L);
        assertThat(allOutcomes()).isEqualTo(1.0);
        assertThat(bot.roundsObserved.get()).as("the 1707 still marks the round boundary").isEqualTo(1L);
    }

    /**
     * Review B2's other half, green today and to stay green after the fix: a cash-out that
     * the server refused (no ack ever) is still resolved as a loss by the next round start.
     */
    @Test
    @DisplayName("B2 guard: cash-out sent, 1707 handled, no ack ever — the bet ends crash by the next round start at the latest")
    void cashoutNeverAckedResolvesAsCrash() {
        subscribe();
        live(SID, 10_000L, 240L, JAKE);
        tick(SID, 2.40);
        roundEnd(SID);
        random.longs.addLast(0L);
        roundStart(NEXT_SID);

        assertThat(outcome("crash")).isEqualTo(1.0);
        assertThat(allOutcomes()).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_WINNINGS_TOTAL)).isZero();
    }

    /**
     * Review B3, confirmed red. Periodic logout is {@code logout()} (client closed) → sleep →
     * {@code restart()}, and nothing on that path cancels the bet task or resets the machine
     * ({@code beforeReconnect()} runs only from {@code tryReconnectWs}). A bet task that
     * fires inside the sleep places into the still-OPEN round, debits the local balance and
     * "sends" to a closed client.
     */
    @Test
    @Disabled("AVIATOR_BOT review B3 (open): the bet task outlives logout()/client close — enable with the fix")
    @DisplayName("B3: a bet task that fires after logout() closed the client neither debits nor sends")
    void betTaskAfterLogout() {
        bot.client = channel;
        subscribe();
        random.longs.addLast(2_000L);
        roundStart(SID);
        Scheduled task = last();

        bot.logout();
        when(channel.isOpen()).thenReturn(false);
        now.addAndGet(task.delayMs());
        random.plan(10_000L, 240L, JAKE);
        task.task().run();

        verify(channel, never()).send(anyString());
        assertThat(bot.getExpectedBalance()).isEqualTo(START_BALANCE);
        assertThat(bot.machine().inFlight()).isFalse();
    }

    /**
     * Review B1, confirmed red. The silence task re-arms itself unconditionally and, on every
     * ladder rung, increments {@code bot_watchdog_expired_total} and arms group-scoped DEBUG
     * before {@code triggerFullReconnect} — which returns at once for a DEAD bot. A DEAD bot
     * in a group below the dead threshold therefore re-arms scoped DEBUG for the whole group
     * every 32 windows, forever.
     */
    @Test
    @Disabled("AVIATOR_BOT review B1 (open): the silence task keeps escalating on a DEAD bot — enable with the fix")
    @DisplayName("B1: on a DEAD bot the silence task neither counts, escalates nor re-arms")
    void silenceTaskOnDeadBot() throws Exception {
        ScopedDebugEscalator escalator = mock(ScopedDebugEscalator.class);
        bot.setScopedDebugEscalator(escalator);
        java.lang.reflect.Field status = Bot.class.getDeclaredField("status");
        status.setAccessible(true);
        status.set(bot, BotStatus.DEAD);
        int before = scheduled.size();

        for (int window = 1; window <= 32; window++) {
            now.addAndGet(WINDOW_MS);
            bot.onSilenceCheck();
        }

        assertThat(count(BotMetrics.BOT_WATCHDOG_EXPIRED_TOTAL)).isZero();
        verify(escalator, never()).onWatchdogExpiry(anyString());
        assertThat(scheduled).as("a DEAD bot's silence task is not re-armed").hasSize(before);
    }

    // ================================================================== reconnect mid-bet

    @Test
    @DisplayName("reconnect while PLACED: a late ack confirms nothing, its 1707 reports no outcome, the next round plays")
    void reconnectWhilePlaced() throws Exception {
        subscribe();
        openAndPlace(SID, 10_000L, 240L, JAKE);

        bot.beforeReconnect();
        betAck(JAKE, 10_000L);
        subscribe();
        roundEnd(SID);

        assertThat(count(BotMetrics.BOT_BETS_PLACED_TOTAL)).isZero();
        assertThat(allOutcomes()).as("a reset abandons the bet without an outcome").isZero();
        assertThat(bot.getExpectedBalance()).as("the local debit stands").isEqualTo(START_BALANCE - 10_000L);

        openAndPlace(NEXT_SID, 20_000L, 240L, NEYTIRI);
        List<JsonNode> bets = sentWithCmd(1702);
        assertThat(bets).hasSize(2);
        assertThat(bets.get(1).get("sid").asLong()).isEqualTo(NEXT_SID);
    }

    @Test
    @DisplayName("reconnect while CASHING: a late cash-out ack is dropped and no outcome is counted (accepted gap)")
    void reconnectWhileCashing() throws Exception {
        subscribe();
        live(SID, 10_000L, 240L, JAKE);
        tick(SID, 2.40);
        assertThat(bot.machine().phase()).isEqualTo(Phase.CASHING);

        bot.beforeReconnect();
        cashoutAck(CASHOUT_ACK);
        tick(SID, 3.00);
        roundEnd(SID);

        assertThat(sentWithCmd(1703)).hasSize(1);
        assertThat(allOutcomes()).isZero();
        assertThat(count(BotMetrics.BOT_WINNINGS_TOTAL)).isZero();
    }

    @Test
    @DisplayName("reconnect keeps the silence count: after two silent windows and a reconnect, window 3 does not reconnect, window 4 does")
    void reconnectKeepsSilenceCount() {
        bot.client = mock(VingameWebSocketClient.class);
        now.addAndGet(WINDOW_MS);
        bot.onSilenceCheck();
        now.addAndGet(WINDOW_MS);
        bot.onSilenceCheck();
        assertThat(reconnectReasons).hasSize(2);

        bot.beforeReconnect();
        bot.onStart();
        now.addAndGet(WINDOW_MS);
        bot.onSilenceCheck();
        assertThat(reconnectReasons).hasSize(2);
        now.addAndGet(WINDOW_MS);
        bot.onSilenceCheck();
        assertThat(reconnectReasons).hasSize(3);
        assertThat(reconnectReasons.get(2)).contains("4 silent windows");
    }

    // ================================================================== stakes vs balance

    @Test
    @DisplayName("a balance exactly at the cheapest rung bets that rung")
    void balanceExactlyCheapestRung() throws Exception {
        subscribe();
        bot.expectedCurrentBalance.set(10_000L);
        random.longs.addLast(0L);
        roundStart(SID);
        last().task().run(); // seeded draws: index bounded by the one affordable rung

        List<JsonNode> bets = sentWithCmd(1702);
        assertThat(bets).hasSize(1);
        assertThat(bets.get(0).get("b").asLong()).isEqualTo(10_000L);
        assertThat(bot.getExpectedBalance()).isZero();
    }

    @Test
    @DisplayName("stake never exceeds the balance: 300 seeded rounds at balance 27500 bet only 10k/15k/20k/25k, all four")
    void stakeBoundedByBalance() throws Exception {
        subscribe();
        for (int round = 0; round < 300; round++) {
            bot.expectedCurrentBalance.set(27_500L);
            random.longs.addLast(0L);
            roundStart(SID + round);
            last().task().run();
            roundEnd(SID + round);
        }
        Set<Long> stakes = new HashSet<>();
        for (JsonNode bet : sentWithCmd(1702)) {
            stakes.add(bet.get("b").asLong());
        }
        assertThat(sentWithCmd(1702)).hasSize(300);
        assertThat(stakes).containsExactlyInAnyOrder(10_000L, 15_000L, 20_000L, 25_000L);
    }

    @Test
    @DisplayName("below the cheapest rung the bot pauses, and resumes once funded — each transition logged once")
    void pauseThenResume() throws Exception {
        subscribe();
        try (LogCapture logs = new LogCapture()) {
            bot.expectedCurrentBalance.set(5_000L);
            for (int round = 0; round < 2; round++) {
                random.longs.addLast(0L);
                roundStart(SID + round);
                last().task().run();
                roundEnd(SID + round);
            }
            verify(channel, never()).send(anyString());

            bot.expectedCurrentBalance.set(START_BALANCE);
            for (int round = 2; round < 4; round++) {
                random.longs.addLast(0L);
                roundStart(SID + round);
                last().task().run();
                roundEnd(SID + round);
            }
            assertThat(logs.containing("pausing bets")).hasSize(1);
            assertThat(logs.containing("resuming bets")).hasSize(1);
        }
        assertThat(sentWithCmd(1702)).hasSize(2);
    }

    // ================================================================== silence task

    @Test
    @DisplayName("a throwing scoped-debug escalator never stops the reconnect")
    void escalatorFailureStillReconnects() {
        ScopedDebugEscalator escalator = mock(ScopedDebugEscalator.class);
        doThrow(new IllegalStateException("cap reached")).when(escalator).onWatchdogExpiry(anyString());
        bot.setScopedDebugEscalator(escalator);
        now.addAndGet(WINDOW_MS);

        assertThatCode(bot::onSilenceCheck).doesNotThrowAnyException();

        verify(escalator).onWatchdogExpiry("group-crash");
        assertThat(reconnectReasons).hasSize(1);
        assertThat(count(BotMetrics.BOT_WATCHDOG_EXPIRED_TOTAL)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("silence after a subscribe WARNs subscribed=true; a frame ends the episode and the next one WARNs and reconnects from window 1 again")
    void silenceEpisodes() {
        subscribe();
        try (LogCapture logs = new LogCapture()) {
            now.addAndGet(WINDOW_MS);
            bot.onSilenceCheck();
            now.addAndGet(WINDOW_MS);
            bot.onSilenceCheck();
            assertThat(logs.at(Level.WARN)).hasSize(1);
            assertThat(logs.at(Level.WARN).get(0)).contains("silent window 1").contains("subscribed=true");

            random.longs.addLast(4_000L);
            roundStart(SID); // a frame
            now.addAndGet(WINDOW_MS);
            bot.onSilenceCheck();
            assertThat(logs.at(Level.WARN)).as("one WARN per silence episode").hasSize(2);
        }
        assertThat(reconnectReasons).hasSize(3);
        assertThat(reconnectReasons.get(2)).contains("1 silent windows");
    }

    @Test
    @DisplayName("a silence task that fires after cleanup neither reconnects nor re-arms")
    void silenceAfterCleanup() {
        bot.cleanup();
        int before = scheduled.size();
        now.addAndGet(10 * WINDOW_MS);

        bot.onSilenceCheck();

        assertThat(reconnectReasons).isEmpty();
        assertThat(scheduled).hasSize(before);
    }

    @Test
    @DisplayName("a bet task that fires after cleanup sends nothing")
    void betAfterCleanup() {
        subscribe();
        random.longs.addLast(0L);
        roundStart(SID);
        Scheduled task = last();
        bot.cleanup();

        task.task().run();

        verify(channel, never()).send(anyString());
    }

    @Test
    @DisplayName("the silence task carries the bot's MDC onto the scheduler thread: the watchdog counter has the group tag")
    void silenceTaskCarriesMdc() throws Exception {
        bot.mdcSnapshot = MDC.getCopyOfContextMap();
        bot.client = mock(VingameWebSocketClient.class);
        bot.onStart();
        Runnable silenceTask = last().task();
        now.addAndGet(WINDOW_MS);

        Thread t = new Thread(() -> {
            MDC.clear();
            silenceTask.run();
        });
        t.start();
        t.join(5_000L);

        assertThat(reconnectReasons).hasSize(1);
        assertThat(count(BotMetrics.BOT_WATCHDOG_EXPIRED_TOTAL, "botGroupId", "group-crash")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the bet task carries the bot's MDC onto the scheduler thread")
    void betTaskCarriesMdc() throws Exception {
        bot.mdcSnapshot = MDC.getCopyOfContextMap();
        subscribe();
        random.longs.addLast(0L);
        roundStart(SID);
        Runnable betTask = last().task();
        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(inv -> {
            seen.set(MDC.get(BotMdc.BOT_GROUP_ID));
            return null;
        }).when(channel).send(anyString());

        Thread t = new Thread(() -> {
            MDC.clear();
            betTask.run();
        });
        t.start();
        t.join(5_000L);

        assertThat(seen.get()).isEqualTo("group-crash");
    }

    // ------------------------------------------------------------------ log capture

    /** Captures CrashBot's own log lines at DEBUG and above for the try block. */
    private static final class LogCapture implements AutoCloseable {
        private final CapturingAppender appender = new CapturingAppender();
        private final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        private final LoggerConfig config;
        private final Level previous;

        LogCapture() {
            appender.start();
            config = ctx.getConfiguration().getLoggerConfig(CrashBot.class.getName());
            previous = config.getLevel();
            config.addAppender(appender, Level.ALL, null);
            config.setLevel(Level.DEBUG);
            ctx.updateLoggers();
        }

        List<String> at(Level level) {
            return appender.events.stream()
                    .filter(e -> e.getLevel() == level && CrashBot.class.getName().equals(e.getLoggerName()))
                    .map(e -> e.getMessage().getFormattedMessage())
                    .toList();
        }

        List<String> containing(String fragment) {
            return appender.events.stream()
                    .filter(e -> CrashBot.class.getName().equals(e.getLoggerName()))
                    .map(e -> e.getMessage().getFormattedMessage())
                    .filter(m -> m.contains(fragment))
                    .toList();
        }

        @Override
        public void close() {
            config.removeAppender(appender.getName());
            config.setLevel(previous);
            ctx.updateLoggers();
        }
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender() {
            super("CapturingAppender-crash-qa", null, PatternLayout.createDefaultLayout(), false, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
