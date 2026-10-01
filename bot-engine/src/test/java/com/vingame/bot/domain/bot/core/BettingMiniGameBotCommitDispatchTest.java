package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.rik.RikGameMessageTypes;
import com.vingame.bot.domain.bot.message.request.RikStockCommit;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.vingame.websocketparser.scenario.Scenario.pipeline;
import static com.vingame.websocketparser.scenario.processors.OutboundMessage.buildMessage;
import static com.vingame.websocketparser.scenario.processors.SendMode.ONCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RIK_114_BETTING_MINI Phase 3 (AD-31 / AD-32), proven on the bot rather than on the
 * request: a 114 {@code stockPlugin} bot sends its bet <b>and then</b> the per-bet
 * {@code 13022} commit for the same session, in that order, through the real
 * ws-parser {@code SendAsync} path; every other bot sends the bet alone.
 *
 * <p><b>Why the ordering test runs the library's own stage.</b> AD-31's claim is that
 * {@code onSent} runs on the same thread immediately after {@code client.send(bet)}
 * returns, so bet-before-commit is structural rather than a race. That is a claim
 * about {@code SendAsync}, so the first test compiles a one-stage pipeline with the
 * bot's real supplier, condition and callback and lets the library drive them. If the
 * library reordered the two, or the {@code .onSent(...)} line were dropped from
 * {@code botBehaviorScenario}, this is the test that reads it.
 *
 * <p>The negative half is the larger blast radius: {@code taixiuMd5Plugin} (same
 * provider, settles today) and a non-114 product must gain no frame. The allowlist in
 * {@code RikGameMessageTypes.requestFor} is what keeps the commit on stock alone, and
 * dropping its one row sends commits on txmd5 — which the second test reads.
 */
@DisplayName("BettingMiniGameBot — the per-bet commit (RIK_114 Phase 3, AD-31/AD-32)")
class BettingMiniGameBotCommitDispatchTest {

    private static final long SID = 3793247L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<BettingMiniGameBot> bots = new ArrayList<>();
    private final List<Scenario> scenarios = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
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
    @DisplayName("stock: bet THEN commit, same sid, in that order — on the real SendAsync path")
    void stockSendsBetThenCommitInOrder() throws Exception {
        VingameWebSocketClient client = mock(VingameWebSocketClient.class);
        BettingMiniGameBot bot = bot(new RikGameMessageTypes(), "stockPlugin", 10000, client);
        openRound(bot, SID);

        PipelineContext ctx = PipelineContext.buildContext()
                .client(client).objectMapper(MAPPER).tag("commit-test").build();
        Scenario s = pipeline(ctx)
                .sendAsync(buildMessage()
                        .messageSupplier(supplier(bot))
                        .mode(ONCE)
                        .condition(condition(bot))
                        .onSent(bot.afterBetSent(MAPPER, client))
                        .build())
                .compile();
        scenarios.add(s);

        // Any message reaching the stage arms its scheduler (interval 0 => one-shot).
        s.process(new RawMessage(MessageType.RECEIVED, "[5,{\"cmd\":13005}]"));

        InOrder inOrder = inOrder(client);
        inOrder.verify(client, timeout(2_000)).send(argThat(f ->
                f.contains("\"cmd\":13002") && f.contains("\"sid\":" + SID)));
        inOrder.verify(client, timeout(2_000)).send(argThat(f ->
                f.contains("\"cmd\":13022") && f.contains("\"sId\":" + SID)));
        verify(client, timeout(2_000).times(2)).send(anyString());

        // The parked slot is consumed, not left armed for the next tick.
        assertThat(pendingCommit(bot).get()).isEmpty();
    }

    @Test
    @DisplayName("stock, through the bot's REAL botBehaviorScenario(): subscribe, then every bet is followed by its commit")
    void stockRealScenarioSendsCommitAfterEveryBet() throws Exception {
        VingameWebSocketClient client = mock(VingameWebSocketClient.class);
        BettingMiniGameBot bot = bot(new RikGameMessageTypes(), "stockPlugin", 10000, client);
        openRound(bot, SID);

        // The production pipeline, exactly as onStart() would register it — including
        // (or, under mutation, excluding) the .onSent(...) line on the bet stage. The
        // one-stage test above proves the library's ordering; this one proves the
        // scenario actually wires the callback in. Dropping that line reds this test.
        Scenario s = bot.botBehaviorScenario();
        scenarios.add(s);

        // The pipeline opens with waitFor(1_000L), clocked from stage construction.
        Thread.sleep(1_100L);
        // A RECEIVED frame matching cmd(subscribeCmd): the Send stage emits the subscribe,
        // waitForMessage passes, and the message reaches the sendAsync stage and arms it.
        s.process(new RawMessage(MessageType.RECEIVED, "[5,{\"cmd\":13000}]"));

        // Wait for the first commit, then stop the INFINITE stage so the capture is stable.
        verify(client, timeout(5_000).atLeastOnce()).send(argThat(f -> f.contains("\"cmd\":13022")));
        s.shutdown();

        org.mockito.ArgumentCaptor<String> frames = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, org.mockito.Mockito.atLeast(3)).send(frames.capture());
        List<String> sent = frames.getAllValues();

        int firstSubscribe = indexOfFirst(sent, "\"cmd\":13000");
        int firstBet = indexOfFirst(sent, "\"cmd\":13002");
        int firstCommit = indexOfFirst(sent, "\"cmd\":13022");
        assertThat(firstSubscribe).isZero();
        assertThat(firstBet).isGreaterThan(firstSubscribe);
        assertThat(firstCommit).isEqualTo(firstBet + 1);

        // Every bet is immediately followed by a commit for the same session — the
        // legacy bot's two consecutive socket.send calls, per bet (AD-29), and the
        // bet-then-commit order is structural on one thread (AD-31).
        long bets = 0;
        for (int i = 0; i < sent.size(); i++) {
            if (sent.get(i).contains("\"cmd\":13002")) {
                bets++;
                assertThat(sent.get(i)).contains("\"sid\":" + SID);
                if (i + 1 < sent.size()) {
                    assertThat(sent.get(i + 1))
                            .as("frame after bet #%d", bets)
                            .contains("\"cmd\":13022")
                            .contains("\"sId\":" + SID)
                            .doesNotContain("\"sid\"");
                }
            }
        }
        assertThat(bets).isPositive();
        long commits = sent.stream().filter(f -> f.contains("\"cmd\":13022")).count();
        // The last bet may have been captured before its commit if shutdown raced it.
        assertThat(commits).isBetween(bets - 1, bets);
    }

    private static int indexOfFirst(List<String> frames, String needle) {
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("txmd5 (same provider, offset 4000): bet only — and afterBetSent afterwards sends nothing")
    void txmd5SendsBetOnly() throws Exception {
        VingameWebSocketClient client = mock(VingameWebSocketClient.class);
        BettingMiniGameBot bot = bot(new RikGameMessageTypes(), "taixiuMd5Plugin", 4000, client);
        openRound(bot, SID);

        PipelineContext ctx = PipelineContext.buildContext()
                .client(client).objectMapper(MAPPER).tag("commit-test").build();
        Scenario s = pipeline(ctx)
                .sendAsync(buildMessage()
                        .messageSupplier(supplier(bot))
                        .mode(ONCE)
                        .condition(condition(bot))
                        .onSent(bot.afterBetSent(MAPPER, client))
                        .build())
                .compile();
        scenarios.add(s);

        s.process(new RawMessage(MessageType.RECEIVED, "[5,{\"cmd\":7005}]"));

        verify(client, timeout(2_000).times(1)).send(argThat(f -> f.contains("\"cmd\":7002")));
        verify(client, times(1)).send(anyString());
        verify(client, never()).send(argThat(f -> f.contains("7022") || f.contains("\"sId\"")));

        // A second onSent with nothing parked is a no-op — the allowlist protects this
        // direction (a denylist would hand txmd5 the commit).
        bot.afterBetSent(MAPPER, client).accept(new SentMessageContext(0, null));
        verify(client, times(1)).send(anyString());
    }

    @Test
    @DisplayName("a non-114 provider (BOM BauCua, offset 2000): bet only, pendingCommit stays empty")
    void otherProductSendsBetOnly() throws Exception {
        VingameWebSocketClient client = mock(VingameWebSocketClient.class);
        BettingMiniGameBot bot = bot(new BomGameMessageTypes(), "BauCua", 2000, client);
        openRound(bot, SID);

        // Drive the supplier directly: what it parks is the whole claim here.
        assertThat(condition(bot).get()).isTrue();
        ActionRequestMessage bet = supplier(bot).get();
        String frame = bet.serialize(MAPPER);
        assertThat(frame).contains("\"cmd\":5002");

        assertThat(pendingCommit(bot).get()).isEmpty();

        // And the callback, invoked the way SendAsync invokes it, sends nothing.
        client.send(frame); // what SendAsync would have done
        bot.afterBetSent(MAPPER, client).accept(new SentMessageContext(0, null));
        verify(client, times(1)).send(anyString());
        verify(client, never()).send(argThat(f -> f.contains("\"sId\"")));
    }

    @Test
    @DisplayName("afterBetSent never throws: a commit whose serialize fails is logged and client.send is not called")
    void callbackNeverThrows() throws Exception {
        VingameWebSocketClient client = mock(VingameWebSocketClient.class);
        BettingMiniGameBot bot = bot(new RikGameMessageTypes(), "stockPlugin", 10000, client);

        ActionRequestMessage poison = mock(RikStockCommit.class);
        when(poison.serialize(any())).thenThrow(new IllegalStateException("boom"));
        pendingCommit(bot).set(Optional.of(poison));

        Consumer<SentMessageContext> callback = bot.afterBetSent(MAPPER, client);
        // An exception escaping here would cancel the scheduleAtFixedRate bet task for
        // the life of the connection (AD-32). It must be swallowed.
        assertThatCode(() -> callback.accept(new SentMessageContext(0, null)))
                .doesNotThrowAnyException();

        verify(client, never()).send(anyString());
        // The poisoned value was popped, not left to poison the next tick too.
        assertThat(pendingCommit(bot).get()).isEmpty();
    }

    @Test
    @DisplayName("AD-31: the commit goes to the client CAPTURED at scenario build, not to whatever Bot.client points at afterwards")
    void commitRealScenarioBoundToCapturedClientNotBotClient() throws Exception {
        VingameWebSocketClient captured = mock(VingameWebSocketClient.class);
        VingameWebSocketClient fresh = mock(VingameWebSocketClient.class);
        BettingMiniGameBot bot = bot(new RikGameMessageTypes(), "stockPlugin", 10000, captured);
        openRound(bot, SID);

        // The production pipeline is built while Bot.client == captured ...
        Scenario s = bot.botBehaviorScenario();
        scenarios.add(s);
        // ... and then a reconnect swaps the field, the way tryReconnectWs / restart do,
        // before a single bet has left. Every frame of this scenario — subscribe, bet AND
        // commit — must still go to the client the scenario was built against. Sending
        // the commit through the field would put it on `fresh` here.
        seedClient(bot, fresh);

        Thread.sleep(1_100L);
        s.process(new RawMessage(MessageType.RECEIVED, "[5,{\"cmd\":13000}]"));

        verify(captured, timeout(5_000).atLeastOnce()).send(argThat(f -> f.contains("\"cmd\":13022")));
        s.shutdown();

        verify(captured, atLeastOnce()).send(argThat(f -> f.contains("\"cmd\":13002")));
        verify(fresh, never()).send(anyString());
    }

    @Test
    @DisplayName("AD-31: afterBetSent sends through the channel it was handed, even after Bot.client is reassigned")
    void callbackSendsThroughHandedChannel() throws Exception {
        VingameWebSocketClient captured = mock(VingameWebSocketClient.class);
        VingameWebSocketClient fresh = mock(VingameWebSocketClient.class);
        BettingMiniGameBot bot = bot(new RikGameMessageTypes(), "stockPlugin", 10000, captured);

        Consumer<SentMessageContext> callback = bot.afterBetSent(MAPPER, captured);
        pendingCommit(bot).set(Optional.of(new RikStockCommit(13022, "MiniGame", "stockPlugin", SID)));
        seedClient(bot, fresh);

        callback.accept(new SentMessageContext(0, null));

        verify(captured, times(1)).send(argThat(f -> f.contains("\"cmd\":13022") && f.contains("\"sId\":" + SID)));
        verify(fresh, never()).send(anyString());
    }

    @Test
    @DisplayName("beforeReconnect clears a parked commit (AD-32 — the one external clear)")
    void beforeReconnectClearsPendingCommit() throws Exception {
        VingameWebSocketClient client = mock(VingameWebSocketClient.class);
        BettingMiniGameBot bot = bot(new RikGameMessageTypes(), "stockPlugin", 10000, client);
        pendingCommit(bot).set(Optional.of(new RikStockCommit(13022, "MiniGame", "stockPlugin", SID)));

        Method m = BettingMiniGameBot.class.getDeclaredMethod("beforeReconnect");
        m.setAccessible(true);
        m.invoke(bot);

        assertThat(pendingCommit(bot).get()).isEmpty();
    }

    /* -------------------------------------------------------------- fixture */

    /**
     * Build a bot the way {@code BotFactory} does: the provider resolved <i>per game</i>
     * ({@code forGame}), then {@code initializeSubclass}, then a mock client seeded into
     * {@code Bot.client} (normally set during auth) — the field
     * {@code botBehaviorScenario()} captures once and hands to both the pipeline context
     * and {@code afterBetSent}.
     */
    private BettingMiniGameBot bot(GameMessageTypes registryProvider, String pluginName, int offset,
                                   VingameWebSocketClient client) throws Exception {
        Game game = Game.builder()
                .id("g-" + pluginName).name(pluginName).pluginName(pluginName)
                .offset(offset).numberOfOptions(2).md5(false).build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("rikcommit1").password("pw").fingerprint("fp").build())
                .environmentId("394301f4-6daf-4c55-a073-502a81c00731")
                .botGroupId("group-rik").botIndex(1)
                .game(game)
                .behaviorConfig(BotBehaviorConfig.builder()
                        .minBet(1000).maxBet(1000).betIncrement(1000)
                        .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(3)
                        .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                        .build())
                .zoneName("MiniGame").timeoutMillis(60_000L).watchdogTimeoutSeconds(120L)
                .strategyId(StrategyId.RANDOM.name())
                .build();

        // A deterministic strategy so the condition parks exactly the decision we want.
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

    /** onStartGame with a sid, BET phase, plenty of time — the state {@code canBet()} needs. */
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

    /** Always bets the same option and amount; never declines. */
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
