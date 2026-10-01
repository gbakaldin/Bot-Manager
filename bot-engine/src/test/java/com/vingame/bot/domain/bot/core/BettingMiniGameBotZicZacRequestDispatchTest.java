package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.g3.rik.RikGameMessageTypes;
import com.vingame.bot.domain.bot.message.request.Bet;
import com.vingame.bot.domain.bot.message.request.RikStockBet;
import com.vingame.bot.domain.bot.message.request.ZicZacBet;
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
import com.vingame.websocketparser.scenario.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RIK_114_ZICZAC Phase 2 (Amendment A1 item 5), proven on the bot's <b>real
 * {@code botBehaviorScenario()}</b> rather than on the request: a 114
 * {@code ziczacPlugin} bot subscribes and then sends {@link ZicZacBet} frames —
 * {@code {cmd, b, c, sid, aid}}, {@code c == 1}, <b>no {@code eid}</b> — and
 * <b>never</b> a commit; the two other 114 games are unchanged by that, which is the
 * larger blast radius: {@code stockPlugin} still sends {@link RikStockBet} followed by
 * its {@code 13022} commit, and {@code taixiuMd5Plugin} still sends the shared
 * {@link Bet} alone.
 *
 * <p><b>Why the real scenario and not the supplier.</b> {@code BettingMiniGameBot.bet()}
 * parks {@code request.commit(sid)} for {@code afterBetSent} to pop, and
 * {@code afterBetSent} is wired in by the {@code .onSent(...)} line of the bet stage.
 * The "no commit" half of ziczac's claim is therefore only worth anything on the wire
 * the scenario actually drives: an {@code Optional.empty()} from {@code commit()} is
 * one thing, and the absence of a {@code 12022} frame on the socket after every
 * {@code 12002} is the fact the server cares about. {@code BettingMiniGameBotCommitDispatchTest}
 * already proves the positive path for stock on the real scenario; this class proves
 * the negative path for ziczac the same way, and re-runs both controls so a routing
 * change that broke one of them shows up next to the row it broke.
 *
 * <p>Messages are asserted from the captured frames, parsed as JSON — never through a
 * getter and never by round-trip — because the failure this phase ends is a frame that
 * looks right and carries a key the server does not know (or lacks {@code c}).
 */
@DisplayName("BettingMiniGameBot x ziczac — real botBehaviorScenario(): ZicZacBet, no commit; stock and txmd5 unchanged")
class BettingMiniGameBotZicZacRequestDispatchTest {

    private static final long SID = 1995084L;
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
    @DisplayName("ziczac: subscribe, then every bet is a ZicZacBet {cmd,b,c,sid,aid} with c=1 and no eid — and NO commit ever")
    void ziczacRealScenarioSendsZicZacBetAndNoCommit() throws Exception {
        VingameWebSocketClient client = mock(VingameWebSocketClient.class);
        BettingMiniGameBot bot = bot(new RikGameMessageTypes(), "ziczacPlugin", 9000, client);
        openRound(bot, SID);

        // The supplier the scenario is built from returns the ziczac body — and parks
        // nothing for afterBetSent. Asserted first because it is the claim that does
        // not depend on timing.
        ActionRequestMessage first = supplier(bot).get();
        assertThat(first).isInstanceOf(ZicZacBet.class);
        assertThat(pendingCommit(bot).get()).as("nothing parked for afterBetSent").isEmpty();

        // Now the production pipeline, exactly as onStart() would register it.
        Scenario s = bot.botBehaviorScenario();
        scenarios.add(s);

        // The pipeline opens with waitFor(1_000L), clocked from stage construction.
        Thread.sleep(1_100L);
        // A RECEIVED frame matching cmd(subscribeCmd): the Send stage emits the subscribe,
        // waitForMessage passes, and the message reaches the sendAsync stage and arms it.
        s.process(new RawMessage(MessageType.RECEIVED, "[5,{\"cmd\":12000}]"));

        // Wait for at least two bets, then stop the INFINITE stage so the capture is stable.
        verify(client, timeout(5_000).atLeast(2)).send(argThat(f -> f.contains("\"cmd\":12002")));
        s.shutdown();

        ArgumentCaptor<String> frames = ArgumentCaptor.forClass(String.class);
        verify(client, atLeast(3)).send(frames.capture());
        List<String> sent = frames.getAllValues();

        // Subscribe first, node-equal to what the shared Request would have sent.
        assertThat(MAPPER.readTree(sent.get(0)))
                .isEqualTo(MAPPER.readTree("[\"6\",\"MiniGame\",\"ziczacPlugin\",{\"cmd\":12000}]"));

        long bets = 0;
        for (String frame : sent) {
            JsonNode node = MAPPER.readTree(frame);
            JsonNode body = node.get(3);
            int cmd = body.get("cmd").asInt();
            // Nothing but the subscribe and the bet ever leaves: no 12022, no sId, no
            // frame of any other cmd. This is the "no commit" claim on the wire.
            assertThat(cmd).as("frame %s", frame).isIn(12000, 12002);
            assertThat(body.has("sId")).as("frame %s carries no sId", frame).isFalse();
            if (cmd != 12002) {
                continue;
            }
            bets++;
            assertThat(node.get(1).asText()).isEqualTo("MiniGame");
            assertThat(node.get(2).asText()).isEqualTo("ziczacPlugin");
            assertThat(body.fieldNames()).toIterable()
                    .as("bet #%d key set", bets)
                    .containsExactlyInAnyOrder("cmd", "b", "c", "sid", "aid");
            assertThat(body.get("b").asLong()).isEqualTo(60_000L);
            assertThat(body.get("c").asInt()).isEqualTo(1);
            assertThat(body.get("sid").asLong()).isEqualTo(SID);
            assertThat(body.get("aid").asInt()).isEqualTo(1);
            assertThat(body.has("eid")).as("AD-11: no eid").isFalse();
        }
        assertThat(bets).isGreaterThanOrEqualTo(2);
        verify(client, never()).send(argThat(f -> f.contains("12022") || f.contains("\"sId\"")));
        assertThat(pendingCommit(bot).get()).isEmpty();
    }

    @Test
    @DisplayName("ziczac: the strategy's optionId is discarded — option 0 and option 1 produce identical bodies (AD-11)")
    void ziczacDiscardsTheStrategyOption() throws Exception {
        VingameWebSocketClient client = mock(VingameWebSocketClient.class);
        BettingMiniGameBot optionZero = bot(new RikGameMessageTypes(), "ziczacPlugin", 9000, client, 0);
        BettingMiniGameBot optionOne = bot(new RikGameMessageTypes(), "ziczacPlugin", 9000, client, 1);
        openRound(optionZero, SID);
        openRound(optionOne, SID);

        JsonNode zero = MAPPER.readTree(supplier(optionZero).get().serialize(MAPPER));
        JsonNode one = MAPPER.readTree(supplier(optionOne).get().serialize(MAPPER));

        assertThat(one).isEqualTo(zero);
        assertThat(zero.get(3).has("eid")).isFalse();
    }

    @Test
    @DisplayName("stock control: still RikStockBet {cmd,v,sid,aid,eid,iAc} followed by its 13022 commit, on the real scenario")
    void stockStillSendsStockBetAndCommit() throws Exception {
        VingameWebSocketClient client = mock(VingameWebSocketClient.class);
        BettingMiniGameBot bot = bot(new RikGameMessageTypes(), "stockPlugin", 10000, client);
        openRound(bot, SID);

        assertThat(supplier(bot).get()).isInstanceOf(RikStockBet.class);
        assertThat(pendingCommit(bot).get()).isPresent();

        Scenario s = bot.botBehaviorScenario();
        scenarios.add(s);
        Thread.sleep(1_100L);
        s.process(new RawMessage(MessageType.RECEIVED, "[5,{\"cmd\":13000}]"));

        verify(client, timeout(5_000).atLeast(1)).send(argThat(f -> f.contains("\"cmd\":13022")));
        s.shutdown();

        ArgumentCaptor<String> frames = ArgumentCaptor.forClass(String.class);
        verify(client, atLeast(3)).send(frames.capture());
        List<String> sent = frames.getAllValues();

        int firstBet = indexOfFirst(sent, "\"cmd\":13002");
        assertThat(firstBet).isPositive();
        JsonNode betBody = MAPPER.readTree(sent.get(firstBet)).get(3);
        assertThat(betBody.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("cmd", "v", "sid", "aid", "eid", "iAc");
        assertThat(betBody.has("c")).as("stock carries no ball count").isFalse();
        JsonNode commitBody = MAPPER.readTree(sent.get(firstBet + 1)).get(3);
        assertThat(commitBody.fieldNames()).toIterable().containsExactlyInAnyOrder("cmd", "sId");
        assertThat(commitBody.get("cmd").asInt()).isEqualTo(13022);
        assertThat(commitBody.get("sId").asLong()).isEqualTo(SID);
    }

    @Test
    @DisplayName("txmd5 control: still the shared Bet {cmd,aid,b,eid,sid} — no c, no commit, on the real scenario")
    void txmd5StillSendsTheSharedBetAlone() throws Exception {
        VingameWebSocketClient client = mock(VingameWebSocketClient.class);
        BettingMiniGameBot bot = bot(new RikGameMessageTypes(), "taixiuMd5Plugin", 4000, client);
        openRound(bot, SID);

        ActionRequestMessage first = supplier(bot).get();
        assertThat(first).isExactlyInstanceOf(Bet.class);
        assertThat(pendingCommit(bot).get()).isEmpty();

        Scenario s = bot.botBehaviorScenario();
        scenarios.add(s);
        Thread.sleep(1_100L);
        s.process(new RawMessage(MessageType.RECEIVED, "[5,{\"cmd\":7000}]"));

        verify(client, timeout(5_000).atLeast(2)).send(argThat(f -> f.contains("\"cmd\":7002")));
        s.shutdown();

        ArgumentCaptor<String> frames = ArgumentCaptor.forClass(String.class);
        verify(client, atLeast(3)).send(frames.capture());
        for (String frame : frames.getAllValues()) {
            JsonNode body = MAPPER.readTree(frame).get(3);
            int cmd = body.get("cmd").asInt();
            assertThat(cmd).as("frame %s", frame).isIn(7000, 7002);
            if (cmd == 7002) {
                assertThat(body.fieldNames()).toIterable()
                        .containsExactlyInAnyOrder("cmd", "aid", "b", "eid", "sid");
                assertThat(body.has("c")).isFalse();
            }
        }
        verify(client, never()).send(argThat(f -> f.contains("7022") || f.contains("\"sId\"")));
    }

    private static int indexOfFirst(List<String> frames, String needle) {
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    /* -------------------------------------------------------------- fixture */

    private BettingMiniGameBot bot(GameMessageTypes registryProvider, String pluginName, int offset,
                                   VingameWebSocketClient client) throws Exception {
        return bot(registryProvider, pluginName, offset, client, 0);
    }

    /**
     * Build a bot the way {@code BotFactory} does: the provider resolved <i>per game</i>
     * ({@code forGame}), then {@code initializeSubclass}, then a mock client seeded into
     * {@code Bot.client} (normally set during auth) — the field
     * {@code botBehaviorScenario()} captures once and hands to both the pipeline context
     * and {@code afterBetSent}.
     * <p>
     * The game carries the degenerate single option {@code {0: 1}} a ziczac game must
     * have (AD-11); the stock and txmd5 controls run with the same fixture, which is
     * harmless for them and keeps the three rows comparable.
     */
    private BettingMiniGameBot bot(GameMessageTypes registryProvider, String pluginName, int offset,
                                   VingameWebSocketClient client, int optionId) throws Exception {
        Map<Integer, Integer> singleOption = new LinkedHashMap<>();
        singleOption.put(0, 1);
        Game game = Game.builder()
                .id("g-" + pluginName).name(pluginName).pluginName(pluginName)
                .offset(offset).numberOfOptions(2).md5(false)
                .optionAffinities(singleOption)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("rikziczac1").password("pw").fingerprint("fp").build())
                .environmentId("394301f4-6daf-4c55-a073-502a81c00731")
                .botGroupId("88d46075-dc8e-476c-b80d-1d0544b29c5c").botIndex(1)
                .game(game)
                .behaviorConfig(BotBehaviorConfig.builder()
                        .minBet(60_000).maxBet(120_000).betIncrement(60_000)
                        .maxTotalBetPerRound(600_000).minBetsPerRound(1).maxBetsPerRound(5)
                        .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                        .build())
                .zoneName("MiniGame").timeoutMillis(60_000L).watchdogTimeoutSeconds(120L)
                .strategyId(StrategyId.RANDOM.name())
                .build();

        // A deterministic strategy so the condition parks exactly the decision we want.
        BettingStrategyFactory factory = mock(BettingStrategyFactory.class);
        when(factory.create(StrategyId.RANDOM.name())).thenReturn(new FixedDecisionStrategy(optionId, 60_000L));

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
