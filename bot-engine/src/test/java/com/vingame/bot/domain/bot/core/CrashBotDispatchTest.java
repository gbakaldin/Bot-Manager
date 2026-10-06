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
import com.vingame.bot.domain.bot.message.crash.CrashTick;
import com.vingame.bot.domain.bot.message.g4.win79.crash.Win79CrashMessageTypes;
import com.vingame.bot.domain.bot.message.g4.win79.crash.Win79CrashTick;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.BotMetrics;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AVIATOR_BOT Phase 3: {@link CrashBot} driven by hand — no scenario engine, no socket —
 * the {@code CashoutBotDispatchTest} approach. Real 119 frames (parsed from the captured
 * JSON through the production Jackson registrations) go into the handlers, the scheduled
 * tasks are captured and run by hand against a fake clock, and the real Micrometer
 * counters are read back.
 */
@DisplayName("CrashBot dispatch (Phase 3)")
class CrashBotDispatchTest {

    private static final long START_BALANCE = 50_000_000L;
    private static final int OFFSET = 1700;
    private static final int JAKE = 1;
    private static final int NEYTIRI = 2;
    private static final long SID = 1_638_119L;
    private static final long WINDOW_MS = 180_000L;

    private static final String START = "{\"cmd\":1705,\"iOE\":true,\"sid\":1638119}";
    private static final String CLOSED = "{\"iUC\":100,\"cmd\":1706,\"sid\":1638119}";
    private static final String END = "{\"nOdd\":2.86,\"b\":10000,\"jOdd\":11.59,\"cmd\":1707,\"sid\":1638119}";
    private static final String CASHOUT_ACK = "{\"eid\":1,\"b\":10000,\"wm\":24000,\"cmd\":1703,\"aid\":1,\"odd\":2.4}";
    /** L12: Neytiri crashes at 2.86 while Jake (integer wire value) is still climbing. */
    private static final String CRASH_TICK = "{\"nFi\":true,\"nOdd\":2.86,\"ps\":[],\"jOdd\":6,\"iJe\":true,"
            + "\"cmd\":1709,\"jFi\":false,\"sid\":1638119}";

    private final AtomicLong now = new AtomicLong(10_000_000L);
    private final List<String> reconnectReasons = new ArrayList<>();
    private final List<Scheduled> scheduled = new ArrayList<>();
    private final ScriptedRandom random = new ScriptedRandom(11L);
    private final ObjectMapper reader = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ObjectMapper inbound = inboundMapper();

    private static ObjectMapper inboundMapper() {
        ObjectMapper m = new ObjectMapper();
        m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        m.registerSubtypes(new Win79CrashMessageTypes().getTypeRegistrations(OFFSET));
        return m;
    }

    private SimpleMeterRegistry registry;
    private VingameWebSocketClient channel;
    private CrashBot bot;

    private record Scheduled(Runnable task, long delayMs, ScheduledFuture<?> future) {
    }

    /**
     * Scripted draws, falling back to a seeded stream. The machine draws: delay
     * {@code nextLong}; then stake index {@code nextInt}, target {@code nextDouble}, runner
     * {@code nextInt}.
     */
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
        when(channel.isOpen()).thenReturn(true); // a live socket, as in production (review B3 gate)
        // Bot.initialize sets the MDC before initializeSubclass; the fixture does the same and
        // keeps it, so increments land on the series pre-registration created.
        BotMdc.set("group-crash", 1, "env-119", "119", "CRASH", "g-aviator", "Aviator", "crashbot1");
        bot = newBot(10_000L, 200_000L, 5_000L);
    }

    @AfterEach
    void tearDown() {
        bot.cleanup();
        BotMdc.clear();
    }

    private CrashBot newBot(long minBet, long maxBet, long step) {
        Game game = Game.builder()
                .id("g-aviator").name("Aviator").pluginName("aviatorPlugin")
                .gameType(GameType.CRASH).offset(OFFSET).build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(minBet).maxBet(maxBet).betIncrement(step).autoDepositEnabled(false).build();
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
        };
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(cfg);
        b.setMessageTypes(new Win79CrashMessageTypes());
        b.setClock(now::get);
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

    private void roundStart(String json) {
        bot.onRoundStart(this.<CrashRoundStart>msg(json));
    }

    private void betAck(int eid, long b) {
        bot.onBetAck(this.<CrashBetAck>msg("{\"eid\":" + eid + ",\"b\":" + b + ",\"cmd\":1702}"));
    }

    private void bettingClosed(String json) {
        bot.onBettingClosed(this.<CrashBettingClosed>msg(json));
    }

    private void tick(String json) {
        bot.onTick(this.<CrashTick>msg(json));
    }

    private void tick(long sid, double odd) {
        bot.onTick(new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE,
                new Win79CrashTick(OFFSET + 9, sid, odd, odd, false, false)));
    }

    private void cashoutAck(String json) {
        bot.onCashoutAck(this.<CrashCashoutAck>msg(json));
    }

    private void roundEnd(String json) {
        bot.onRoundEnd(this.<CrashRoundEnd>msg(json));
    }

    /** The round start → scripted plan → the bet task, run by hand at its due time. */
    private Plan openAndPlace(long amount, long targetH, int eid) {
        random.longs.addLast(18L);
        int before = scheduled.size();
        roundStart(START);
        assertThat(scheduled).as("one bet task per round start").hasSize(before + 1);
        Scheduled task = scheduled.get(scheduled.size() - 1);
        assertThat(task.delayMs()).isEqualTo(18L);
        now.addAndGet(task.delayMs());
        random.plan(amount, targetH, eid);
        task.task().run();
        return bot.machine().currentPlan().orElseThrow();
    }

    private double count(String name, String... tags) {
        Counter c = registry.find(name).tags(tags).counter();
        return c == null ? 0.0 : c.count();
    }

    private List<JsonNode> sentBodies() throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(channel, atLeast(0)).send(captor.capture());
        List<JsonNode> bodies = new ArrayList<>();
        for (String s : captor.getAllValues()) {
            JsonNode frame = reader.readTree(s);
            assertThat(frame.get(1).asText()).isEqualTo("MiniGame");
            assertThat(frame.get(2).asText()).isEqualTo("aviatorPlugin");
            bodies.add(frame.get(3));
        }
        return bodies;
    }

    private List<JsonNode> sentWithCmd(int cmd) throws Exception {
        return sentBodies().stream().filter(b -> b.get("cmd").asInt() == cmd).toList();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("right after initializeSubclass, all three bot_crash_bets_total series exist at 0 with the group tag")
    void seriesPreRegisteredBeforeAnyFrame() {
        for (String outcome : List.of("cashout", "crash", "unacked")) {
            Counter c = registry.find(BotMetrics.BOT_CRASH_BETS_TOTAL)
                    .tags("outcome", outcome, "botGroupId", "group-crash").counter();
            assertThat(c).as("outcome=%s pre-registered", outcome).isNotNull();
            assertThat(c.count()).isZero();
        }
    }

    @Test
    @DisplayName("capture round 1638119: one bet, one cash-out with matching sid/eid, +1 placed, +24000 winnings, cashout +1")
    void captureRoundCashout() throws Exception {
        subscribe();
        assertThat(bot.ladder()).hasSize(39);

        Plan plan = openAndPlace(10_000L, 240L, JAKE);
        assertThat(plan).isEqualTo(new Plan(10_000L, 240L, JAKE));
        List<JsonNode> bets = sentWithCmd(1702);
        assertThat(bets).hasSize(1);
        assertThat(bets.get(0).get("b").asLong()).isEqualTo(10_000L);
        assertThat(bets.get(0).get("sid").asLong()).isEqualTo(SID);
        assertThat(bets.get(0).get("eid").asInt()).isEqualTo(JAKE);
        assertThat(bets.get(0).get("aid").asInt()).isEqualTo(1);
        assertThat(bot.getExpectedBalance()).isEqualTo(START_BALANCE - 10_000L);

        betAck(JAKE, 10_000L);
        assertThat(count(BotMetrics.BOT_BETS_PLACED_TOTAL)).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_BET_AMOUNT_TOTAL)).isEqualTo(10_000.0);

        bettingClosed(CLOSED);
        for (double odd = 1.00; odd < 2.40; odd = Math.round(odd * 1.035 * 100.0) / 100.0) {
            tick(SID, odd);
        }
        assertThat(sentWithCmd(1703)).as("nothing below target").isEmpty();
        tick(SID, 2.42);
        tick(SID, 2.50);
        tick(SID, 2.59);

        List<JsonNode> cashouts = sentWithCmd(1703);
        assertThat(cashouts).as("exactly one cash-out").hasSize(1);
        assertThat(cashouts.get(0).get("sid").asLong()).isEqualTo(SID);
        assertThat(cashouts.get(0).get("eid").asInt()).isEqualTo(JAKE);
        assertThat(cashouts.get(0).get("aid").asInt()).isEqualTo(1);

        long before = bot.getExpectedBalance();
        cashoutAck(CASHOUT_ACK);
        assertThat(bot.getExpectedBalance()).isEqualTo(before + 24_000L);
        assertThat(bot.getLastRoundWinnings()).isEqualTo(24_000L);
        assertThat(count(BotMetrics.BOT_WINNINGS_TOTAL)).isEqualTo(24_000.0);
        assertThat(count(BotMetrics.BOT_CRASH_BETS_TOTAL, "outcome", "cashout")).isEqualTo(1.0);

        tick(CRASH_TICK);
        roundEnd(END);

        assertThat(sentBodies()).as("bet + cash-out, nothing after the ack").hasSize(2);
        assertThat(count(BotMetrics.BOT_CRASH_BETS_TOTAL, "outcome", "crash")).isZero();
        assertThat(count(BotMetrics.BOT_CRASH_BETS_TOTAL, "outcome", "unacked")).isZero();
        assertThat(registry.find(BotMetrics.BOT_CRASH_BETS_TOTAL).counters())
                .as("increments hit the pre-registered series, not new ones").hasSize(3);
        assertThat(bot.roundsObserved.get()).isEqualTo(1L);
        assertThat(count(BotMetrics.BOT_MESSAGES_TOTAL, "cmd", "crashRoundEnd")).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_MESSAGES_TOTAL, "cmd", "crashCashoutAck")).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_MESSAGES_TOTAL, "cmd", "crashBetAck")).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_MESSAGES_TOTAL, "cmd", "crashRoundStart")).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_MESSAGES_TOTAL, "cmd", "crashBettingClosed")).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_MESSAGES_TOTAL, "cmd", "crashSubscribe")).isEqualTo(1.0);
        assertThat(bot.machine().phase()).isEqualTo(Phase.WAITING);
    }

    @Test
    @DisplayName("F-1: an eid-2 bet with target 2.85 through 2.80 then the L12 crash tick (nOdd 2.86) counts crash, sends no 1703")
    void neytiriCrashTick() throws Exception {
        subscribe();
        openAndPlace(10_000L, 285L, NEYTIRI);
        betAck(NEYTIRI, 10_000L);
        bettingClosed(CLOSED);
        tick(SID, 2.80);

        tick(CRASH_TICK);

        assertThat(sentWithCmd(1703)).isEmpty();
        assertThat(count(BotMetrics.BOT_CRASH_BETS_TOTAL, "outcome", "crash")).isEqualTo(1.0);
        assertThat(bot.getLastRoundWinnings()).isZero();

        tick(SID, 3.0); // Jake keeps climbing, irrelevant to an eid-2 bet
        roundEnd(END);
        assertThat(sentWithCmd(1703)).isEmpty();
        assertThat(count(BotMetrics.BOT_CRASH_BETS_TOTAL, "outcome", "crash")).as("no second outcome").isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_WINNINGS_TOTAL)).isZero();
    }

    @Test
    @DisplayName("a bet with no ack before 1707 counts unacked and is not a confirmed bet")
    void unackedBet() {
        subscribe();
        openAndPlace(20_000L, 200L, JAKE);
        bettingClosed(CLOSED);
        tick(SID, 2.5);

        roundEnd(END);

        assertThat(count(BotMetrics.BOT_CRASH_BETS_TOTAL, "outcome", "unacked")).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_BETS_PLACED_TOTAL)).isZero();
        assertThat(bot.roundsObserved.get()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a missed 1707: the next round start reports the old live bet as crash first")
    void missedRoundEndAbandonsOnNextStart() {
        subscribe();
        openAndPlace(10_000L, 300L, JAKE);
        betAck(JAKE, 10_000L);

        random.longs.addLast(0L);
        roundStart("{\"cmd\":1705,\"iOE\":true,\"sid\":1638120}");

        assertThat(count(BotMetrics.BOT_CRASH_BETS_TOTAL, "outcome", "crash")).isEqualTo(1.0);
        assertThat(bot.machine().phase()).isEqualTo(Phase.OPEN);
        assertThat(bot.machine().sid()).isEqualTo(1_638_120L);
    }

    @Test
    @DisplayName("the bet task fired after betting closed sends nothing")
    void betTaskAfterBettingClosed() {
        subscribe();
        random.longs.addLast(4_000L);
        roundStart(START);
        Scheduled task = scheduled.get(scheduled.size() - 1);
        bettingClosed(CLOSED);

        now.addAndGet(task.delayMs());
        task.task().run();

        verify(channel, never()).send(anyString());
        assertThat(bot.getExpectedBalance()).isEqualTo(START_BALANCE);
    }

    @Test
    @DisplayName("beforeReconnect: a pending bet task is cancelled and sends nothing; a live bet sends no cash-out later")
    void beforeReconnectMidFlight() throws Exception {
        subscribe();
        random.longs.addLast(1_000L);
        roundStart(START);
        Scheduled pending = scheduled.get(scheduled.size() - 1);

        bot.beforeReconnect();

        verify(pending.future()).cancel(false);
        now.addAndGet(pending.delayMs());
        pending.task().run();
        verify(channel, never()).send(anyString());
        assertThat(bot.watch().isSubscribed()).isFalse();

        // Next round, the bet goes live, then a reconnect mid-flight.
        random.longs.addLast(0L);
        subscribe();
        roundStart("{\"cmd\":1705,\"iOE\":true,\"sid\":1638120}");
        random.plan(10_000L, 150L, JAKE);
        scheduled.get(scheduled.size() - 1).task().run();
        betAck(JAKE, 10_000L);
        tick(1_638_120L, 1.2);

        bot.beforeReconnect();
        tick(1_638_120L, 2.0);

        assertThat(sentWithCmd(1702)).hasSize(1);
        assertThat(sentWithCmd(1703)).as("no cash-out after the reset").isEmpty();
        assertThat(count(BotMetrics.BOT_CRASH_BETS_TOTAL, "outcome", "cashout")
                + count(BotMetrics.BOT_CRASH_BETS_TOTAL, "outcome", "crash")
                + count(BotMetrics.BOT_CRASH_BETS_TOTAL, "outcome", "unacked"))
                .as("a reset abandons the bet without an outcome").isZero();
    }

    @Test
    @DisplayName("an empty stake ladder: one WARN on subscribe, no bet")
    void emptyLadder() {
        bot.cleanup();
        bot = newBot(12_000L, 14_000L, 5_000L);
        try (LogCapture logs = new LogCapture()) {
            subscribe();
            assertThat(logs.at(Level.WARN)).hasSize(1);
            assertThat(logs.at(Level.WARN).get(0)).contains("empty crash stake ladder");
        }
        assertThat(bot.ladder()).isEmpty();
        random.longs.addLast(0L);
        roundStart(START);
        scheduled.get(scheduled.size() - 1).task().run();
        verify(channel, never()).send(anyString());
    }

    @Test
    @DisplayName("a balance below the cheapest rung parks the bot; the pause logs once")
    void brokeBotParks() {
        subscribe();
        bot.expectedCurrentBalance.set(9_999L);
        try (LogCapture logs = new LogCapture()) {
            for (int round = 0; round < 3; round++) {
                random.longs.addLast(0L);
                roundStart("{\"cmd\":1705,\"iOE\":true,\"sid\":" + (SID + round) + "}");
                scheduled.get(scheduled.size() - 1).task().run();
            }
            assertThat(logs.containing("pausing bets")).hasSize(1);
        }
        verify(channel, never()).send(anyString());
    }

    @Test
    @DisplayName("silence: no frames triggers triggerFullReconnect at windows 1 and 2, not 3; the reconnect's own WARN carries subscribed=false")
    void silenceLadder() {
        try (LogCapture logs = new LogCapture()) {
            for (int window = 1; window <= 3; window++) {
                now.addAndGet(WINDOW_MS);
                bot.onSilenceCheck();
                assertThat(reconnectReasons).as("after window %d", window).hasSize(Math.min(window, 2));
            }
            // Review S3: triggerFullReconnect WARNs the reason itself; CrashBot adds no second WARN.
            assertThat(logs.at(Level.WARN)).isEmpty();
            assertThat(logs.containing("silent window 1")).hasSize(1);
        }
        assertThat(reconnectReasons.get(0)).startsWith("watchdog").contains("1 silent windows")
                .contains("subscribed=false");
        assertThat(count(BotMetrics.BOT_WATCHDOG_EXPIRED_TOTAL)).isEqualTo(2.0);
        assertThat(scheduled).as("each check re-arms a full window")
                .extracting(Scheduled::delayMs).containsOnly(WINDOW_MS);
    }

    @Test
    @DisplayName("silence: a frame mid-window re-arms for the remainder and reconnects nothing")
    void silenceRemaining() {
        now.addAndGet(60_000L);
        subscribe();
        now.addAndGet(WINDOW_MS - 60_000L);

        bot.onSilenceCheck();

        assertThat(reconnectReasons).isEmpty();
        assertThat(scheduled).extracting(Scheduled::delayMs).containsExactly(60_000L);
    }

    @Test
    @DisplayName("onStart arms the silence watch before any subscribe reply")
    void onStartArmsTheWatch() {
        bot.client = mock(VingameWebSocketClient.class);

        bot.onStart();

        assertThat(scheduled).extracting(Scheduled::delayMs).containsExactly(WINDOW_MS);
    }

    @Test
    @DisplayName("a stale 1707 counts no round")
    void staleRoundEnd() {
        subscribe();
        random.longs.addLast(0L);
        roundStart("{\"cmd\":1705,\"iOE\":true,\"sid\":1638120}");

        roundEnd(END); // sid 1638119 < 1638120

        assertThat(bot.roundsObserved.get()).isZero();
        assertThat(bot.machine().phase()).isEqualTo(Phase.OPEN);
    }

    @Test
    @DisplayName("a CRASH game with no offset fails loud at initialize")
    void missingOffsetFailsLoud() {
        CrashBot b = new CrashBot();
        b.setConfiguration(BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("x").password("p").build())
                .game(Game.builder().name("Aviator").gameType(GameType.CRASH).pluginName("aviatorPlugin").build())
                .zoneName("MiniGame")
                .build());
        b.setMessageTypes(new Win79CrashMessageTypes());

        assertThatThrownBy(b::initializeSubclass)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aviator")
                .hasMessageContaining("offset");
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
            super("CapturingAppender-crash", null, PatternLayout.createDefaultLayout(), false, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
