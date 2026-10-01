package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.g3.rik.RikGameMessageTypes;
import com.vingame.bot.domain.bot.message.request.GameRequest;
import com.vingame.bot.domain.bot.message.request.RikStockCommit;
import com.vingame.bot.domain.bot.message.request.SubscribeToLobbyMessage;
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
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.message.RawMessage;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.properties.MessageType;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import com.vingame.websocketparser.scenario.PipelineContext;
import com.vingame.websocketparser.scenario.Scenario;
import com.vingame.websocketparser.scenario.processors.SentMessageContext;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.vingame.websocketparser.scenario.Scenario.pipeline;
import static com.vingame.websocketparser.scenario.processors.OutboundMessage.buildMessage;
import static com.vingame.websocketparser.scenario.processors.SendMode.INFINITE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA for RIK_114_BETTING_MINI Phase 3 (AD-31 / AD-32), covering what
 * {@code BettingMiniGameBotCommitDispatchTest} asserts on the callback in isolation but
 * not on its consequence:
 *
 * <ol>
 *   <li><b>The bet loop survives a throwing commit.</b> {@code callbackNeverThrows} proves
 *       {@code afterBetSent} returns normally; this class proves the thing AD-32 actually
 *       protects — that the {@code scheduleAtFixedRate} bet task is <i>still firing</i>
 *       after the commit has failed on it, on the library's real {@code SendAsync}. With
 *       the {@code try/catch} removed from {@code afterBetSent}, exactly one bet leaves
 *       and the loop is dead for the life of the connection. The same test pins the
 *       <i>shape</i> of what the failure emits: exactly <b>one</b> WARN per bot however
 *       many ticks fail, the rest at DEBUG — a deterministic serialize failure at the
 *       1 s bet interval would otherwise be one WARN per bet per bot into track 1 and
 *       Loki, the rate the CLAUDE.md tiering rule exists to keep out of it.</li>
 *   <li><b>No stale commit can outlive a reconnect.</b> The one window where a parked
 *       commit is not consumed by the same runnable that parked it is
 *       {@code beforeReconnect} landing between the supplier's {@code set} and the
 *       callback's {@code getAndSet}. This class parks a commit for one sid, fires
 *       {@code beforeReconnect}, opens a new round and drives one full bet: the only
 *       commit on the wire carries the <b>new</b> sid, and the old one never appears.</li>
 *   <li><b>txmd5 on the REAL scenario.</b> {@code txmd5SendsBetOnly} drives a hand-built
 *       one-stage pipeline; this class drives {@code botBehaviorScenario()} itself on a
 *       {@code taixiuMd5Plugin} bot through several bets and asserts the frame set is
 *       exactly {subscribe, bet} — no {@code 7022}, no {@code sId}, ever.</li>
 * </ol>
 */
@DisplayName("BettingMiniGameBot — the per-bet commit cannot kill the bet loop or outlive a reconnect (QA, RIK_114 Phase 3)")
class BettingMiniGameBotCommitLoopSurvivalTest {

    private static final long SID_A = 3793247L;
    private static final long SID_B = 3793248L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<BettingMiniGameBot> bots = new ArrayList<>();
    private final List<Scenario> scenarios = new ArrayList<>();

    private CapturingAppender appender;
    private LoggerContext logCtx;
    private LoggerConfig loggerConfig;
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
        appender = new CapturingAppender("CapturingAppender-commit-survival");
        appender.start();
        logCtx = (LoggerContext) LogManager.getContext(false);
        // Attach at the shared application logger and raise it to DEBUG so the demoted
        // repeat failures are RECORDED and their level asserted, not inferred from absence.
        loggerConfig = logCtx.getConfiguration().getLoggerConfig("com.vingame.bot");
        previousLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.DEBUG);
        logCtx.updateLoggers();
    }

    @AfterEach
    void tearDown() throws Exception {
        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(previousLevel);
        logCtx.updateLoggers();
        for (Scenario s : scenarios) {
            s.shutdown();
        }
        for (BettingMiniGameBot bot : bots) {
            shutdown(bot, "watchdogScheduler");
            shutdown(bot, "scheduler");
        }
    }

    /* ---------------------------------------------------------------- tests */

    @Test
    @DisplayName("AD-32: a commit whose serialize throws on every bet does NOT cancel the fixed-rate bet task — bets keep leaving")
    void poisonedCommitDoesNotKillTheBetLoop() throws Exception {
        VingameWebSocketClient client = mock(VingameWebSocketClient.class);
        BettingMiniGameBot bot = bot(new RikGameMessageTypes(), "stockPlugin", 10000, client);
        openRound(bot, SID_A);

        // The bot's real RikStockRequest, with commit() replaced by a body whose
        // serialize() throws — the fault AD-32's try/catch exists for. subscribe/bet are
        // untouched, so the bet frames on the wire are the real 13002.
        AtomicInteger commitBuilds = new AtomicInteger();
        GameRequest real = (GameRequest) readField(bot, "request");
        setField(bot, "request", new PoisonCommitRequest(real, commitBuilds));

        // The production stage shape (INFINITE + interval => scheduleAtFixedRate), with
        // the bot's real condition, supplier and callback, at a fast interval.
        PipelineContext ctx = PipelineContext.buildContext()
                .client(client).objectMapper(MAPPER).tag("commit-survival").build();
        Scenario s = pipeline(ctx)
                .sendAsync(buildMessage()
                        .messageSupplier(supplier(bot))
                        .mode(INFINITE)
                        .condition(condition(bot))
                        .interval(100L, MILLISECONDS)
                        .onSent(bot.afterBetSent(MAPPER, client))
                        .build())
                .compile();
        scenarios.add(s);
        s.process(new RawMessage(MessageType.RECEIVED, "[5,{\"cmd\":13005}]"));

        // Five bets is five ticks past the first failure. If the exception had escaped
        // onSent, ScheduledThreadPoolExecutor would have cancelled the task after tick 1
        // and this verify would time out at exactly one send.
        verify(client, timeout(5_000).atLeast(5)).send(argThat(f -> f.contains("\"cmd\":13002")));
        s.shutdown();

        // Every tick built (and failed) a commit — the poison was reached, not skipped.
        assertThat(commitBuilds.get()).isGreaterThanOrEqualTo(5);
        // Nothing commit-shaped ever reached the wire, and the failed value was popped.
        verify(client, never()).send(argThat(f -> f.contains("13022") || f.contains("\"sId\"")));
        assertThat(pendingCommit(bot).get()).isEmpty();

        // The failure is per bet and deterministic, so its log shape is what decides
        // whether track 1 survives it: exactly ONE WARN for this bot, carrying the
        // throwable; every later tick is DEBUG. Reviewer smell 2 on Phase 3.
        List<LogEvent> commitFailures = appender.events().stream()
                .filter(e -> e.getLoggerName().equals(BettingMiniGameBot.class.getName()))
                .filter(e -> e.getMessage().getFormattedMessage().contains("commit frame not sent"))
                .toList();
        List<LogEvent> warns = commitFailures.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).as("one WARN per bot, however many ticks fail").hasSize(1);
        assertThat(warns.get(0).getThrown())
                .as("the one WARN carries the throwable, not e.toString()")
                .isInstanceOf(IllegalStateException.class);
        // >= 5 bets left on ONE scheduler thread, so the callbacks of at least the first
        // four ticks have completed (the fifth bet's send ran after the fourth callback
        // returned); the fifth callback may still be in flight past shutdown().
        assertThat(commitFailures.stream().filter(e -> e.getLevel() == Level.DEBUG).count())
                .as("the repeats are demoted, not dropped")
                .isGreaterThanOrEqualTo(3);
        assertThat(commitFailures)
                .allMatch(e -> e.getLevel() == Level.WARN || e.getLevel() == Level.DEBUG);
    }

    @Test
    @DisplayName("AD-32: a commit parked before beforeReconnect never reaches the wire; the next bet's commit carries the NEW sid only")
    void staleCommitCannotOutliveReconnect() throws Exception {
        VingameWebSocketClient client = mock(VingameWebSocketClient.class);
        BettingMiniGameBot bot = bot(new RikGameMessageTypes(), "stockPlugin", 10000, client);

        // Round A: the supplier runs and parks commit(A) — and then the netty thread's
        // beforeReconnect lands BEFORE SendAsync reaches onSent. (The supplier's return
        // value, the bet for A, is dropped here exactly as a dying socket would drop it.)
        openRound(bot, SID_A);
        assertThat(condition(bot).get()).isTrue();
        ActionRequestMessage betA = supplier(bot).get();
        assertThat(betA.serialize(MAPPER)).contains("\"sid\":" + SID_A);
        assertThat(pendingCommit(bot).get()).isPresent();
        Method reconnect = BettingMiniGameBot.class.getDeclaredMethod("beforeReconnect");
        reconnect.setAccessible(true);
        reconnect.invoke(bot);
        assertThat(pendingCommit(bot).get()).isEmpty();

        // The callback for the aborted tick fires anyway (SendAsync calls it after every
        // client.send): nothing is parked, so nothing is sent — not the stale A.
        bot.afterBetSent(MAPPER, client).accept(new SentMessageContext(0, null));
        verify(client, never()).send(anyString());

        // Round B after the reconnect: one full bet tick, the way SendAsync runs it.
        openRound(bot, SID_B);
        assertThat(condition(bot).get()).isTrue();
        ActionRequestMessage betB = supplier(bot).get();
        client.send(betB.serialize(MAPPER));
        bot.afterBetSent(MAPPER, client).accept(new SentMessageContext(1, null));

        ArgumentCaptor<String> frames = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).send(frames.capture());
        List<String> sent = frames.getAllValues();
        assertThat(sent.get(0)).contains("\"cmd\":13002").contains("\"sid\":" + SID_B);
        assertThat(sent.get(1)).contains("\"cmd\":13022").contains("\"sId\":" + SID_B);
        // The old session is on no frame at all — bet and commit are built from the same
        // currentSid local in one supplier run, so they cannot disagree.
        assertThat(sent).noneMatch(f -> f.contains(String.valueOf(SID_A)));
        assertThat(pendingCommit(bot).get()).isEmpty();
    }

    @Test
    @DisplayName("txmd5 through the bot's REAL botBehaviorScenario(): several bets, frame set is exactly {7000, 7002} — no 7022, no sId")
    void txmd5RealScenarioNeverSendsACommit() throws Exception {
        VingameWebSocketClient client = mock(VingameWebSocketClient.class);
        BettingMiniGameBot bot = bot(new RikGameMessageTypes(), "taixiuMd5Plugin", 4000, client);
        openRound(bot, SID_A);

        Scenario s = bot.botBehaviorScenario();
        scenarios.add(s);

        // waitFor(1_000L) is clocked from stage construction and SKIPS early arrivals.
        Thread.sleep(1_100L);
        s.process(new RawMessage(MessageType.RECEIVED, "[5,{\"cmd\":7000}]"));

        // Two bets on the production 1 s interval, then freeze the capture.
        verify(client, timeout(6_000).atLeast(2)).send(argThat(f -> f.contains("\"cmd\":7002")));
        s.shutdown();

        ArgumentCaptor<String> frames = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).send(frames.capture());
        List<String> sent = frames.getAllValues();

        assertThat(sent.get(0)).contains("\"cmd\":7000");
        assertThat(sent).allMatch(f -> f.contains("\"cmd\":7000") || f.contains("\"cmd\":7002"),
                "every frame is the subscribe or the bet — nothing else exists on txmd5");
        assertThat(sent).noneMatch(f -> f.contains("7022") || f.contains("\"sId\""));
        assertThat(sent.stream().filter(f -> f.contains("\"cmd\":7002")).count()).isGreaterThanOrEqualTo(2);
        // The bet frames are the shared Request/Bet shape: b, not v; sid present.
        assertThat(sent.stream().filter(f -> f.contains("\"cmd\":7002")))
                .allMatch(f -> f.contains("\"b\":") && f.contains("\"sid\":" + SID_A) && !f.contains("\"v\":"));
        assertThat(pendingCommit(bot).get()).isEmpty();
    }

    /* -------------------------------------------------------------- fixture */

    /** Delegates subscribe/bet to the real request; commit() returns a body that cannot serialize. */
    private record PoisonCommitRequest(GameRequest real, AtomicInteger commitBuilds) implements GameRequest {
        @Override
        public SubscribeToLobbyMessage subscribe() {
            return real.subscribe();
        }

        @Override
        public ActionRequestMessage bet(long amount, int entryId, long sid) {
            return real.bet(amount, entryId, sid);
        }

        @Override
        public Optional<ActionRequestMessage> commit(long sid) {
            commitBuilds.incrementAndGet();
            RikStockCommit poison = mock(RikStockCommit.class);
            when(poison.serialize(any())).thenThrow(new IllegalStateException("poisoned commit for sid " + sid));
            return Optional.of(poison);
        }
    }

    private BettingMiniGameBot bot(GameMessageTypes registryProvider, String pluginName, int offset,
                                   VingameWebSocketClient client) throws Exception {
        Game game = Game.builder()
                .id("g-" + pluginName).name(pluginName).pluginName(pluginName)
                .offset(offset).numberOfOptions(2).md5(false).build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("rikqa1").password("pw").fingerprint("fp").build())
                .environmentId("394301f4-6daf-4c55-a073-502a81c00731")
                .botGroupId("group-rik-qa").botIndex(1)
                .game(game)
                .behaviorConfig(BotBehaviorConfig.builder()
                        .minBet(1000).maxBet(1000).betIncrement(1000)
                        .maxTotalBetPerRound(1_000_000).minBetsPerRound(1).maxBetsPerRound(100)
                        .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                        .build())
                .zoneName("MiniGame").timeoutMillis(60_000L).watchdogTimeoutSeconds(120L)
                .strategyId(StrategyId.RANDOM.name())
                .build();

        BettingStrategyFactory factory = mock(BettingStrategyFactory.class);
        when(factory.create(StrategyId.RANDOM.name())).thenReturn(new FixedDecisionStrategy(0, 1000L));

        BettingMiniGameBot bot = new BettingMiniGameBot();
        bots.add(bot);
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setStrategyFactory(factory);
        bot.setMessageTypes(registryProvider.forGame(game));
        bot.initializeSubclass();
        seedClient(bot, client);
        seedLong(bot, "lastFetchedBalance", 50_000_000L);
        seedAtomic(bot, "expectedCurrentBalance", 50_000_000L);
        return bot;
    }

    private static void openRound(BettingMiniGameBot bot, long sid) throws Exception {
        StartGameMessage msg = mock(StartGameMessage.class);
        when(msg.getSessionId()).thenReturn(sid);
        ActionResponseMessage<StartGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        Method m = BettingMiniGameBot.class.getDeclaredMethod("onStartGame", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(bot, resp);
        setField(bot, "gameState", BettingMiniGameState.BET);
        ((AtomicLong) readField(bot, "remainingTime")).set(10_000L);
        ((SessionIdStore) readField(bot, "sidStore")).set(sid);
    }

    @SuppressWarnings("unchecked")
    private static Supplier<Boolean> condition(BettingMiniGameBot bot) throws Exception {
        Method m = BettingMiniGameBot.class.getDeclaredMethod("betCondition");
        m.setAccessible(true);
        return (Supplier<Boolean>) m.invoke(bot);
    }

    @SuppressWarnings("unchecked")
    private static Supplier<ActionRequestMessage> supplier(BettingMiniGameBot bot) throws Exception {
        Method m = BettingMiniGameBot.class.getDeclaredMethod("bet");
        m.setAccessible(true);
        return (Supplier<ActionRequestMessage>) m.invoke(bot);
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Optional<ActionRequestMessage>> pendingCommit(BettingMiniGameBot bot)
            throws Exception {
        return (AtomicReference<Optional<ActionRequestMessage>>) readField(bot, "pendingCommit");
    }

    private static void seedClient(BettingMiniGameBot b, VingameWebSocketClient client) throws Exception {
        Field f = Bot.class.getDeclaredField("client");
        f.setAccessible(true);
        f.set(b, client);
    }

    private static void seedLong(BettingMiniGameBot b, String name, long value) throws Exception {
        Field f = Bot.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setLong(b, value);
    }

    private static void seedAtomic(BettingMiniGameBot b, String name, long value) throws Exception {
        Field f = Bot.class.getDeclaredField(name);
        f.setAccessible(true);
        ((AtomicLong) f.get(b)).set(value);
    }

    private static Object readField(BettingMiniGameBot bot, String name) throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(bot);
    }

    private static void setField(BettingMiniGameBot bot, String name, Object value) throws Exception {
        Field f = BettingMiniGameBot.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(bot, value);
    }

    private static void shutdown(BettingMiniGameBot bot, String name) throws Exception {
        Object executor = readField(bot, name);
        if (executor != null) {
            ((ScheduledExecutorService) executor).shutdownNow();
        }
    }

    /** Minimal in-memory log4j2 appender (same idiom as PerBotInitLogLevelTest). */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender(String name) {
            super(name, null, PatternLayout.createDefaultLayout(), false, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> events() {
            return new ArrayList<>(events);
        }
    }

    private record FixedDecisionStrategy(int optionId, long amount) implements BettingStrategy {
        @Override
        public Optional<BetDecision> decide(BetContext ctx) {
            return Optional.of(new BetDecision(optionId, amount));
        }

        @Override
        public void onRoundEnd(RoundResult result) {
        }
    }
}
