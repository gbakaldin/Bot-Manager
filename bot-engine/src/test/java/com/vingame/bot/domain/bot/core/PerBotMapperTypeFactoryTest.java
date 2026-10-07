package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.util.LRUMap;
import com.fasterxml.jackson.databind.util.LookupCache;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g4.win79.cashout.Win79CashoutMessageTypes;
import com.vingame.bot.domain.bot.message.g4.win79.crash.Win79CrashMessageTypes;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.message.taixiu.MiniGameTaiXiuMessageTypes;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.TestStrategyFactories;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.scenario.PipelineStage;
import com.vingame.websocketparser.scenario.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-8</b> (spike rule 2, scenarios 3a/3b): every per-bot mapper
 * is built on the plugin bundle's {@link TypeFactory}, never the shared default.
 * <p>
 * Why it matters: {@code TypeFactory.defaultInstance()} holds a strong 200-entry type
 * cache that every {@code new ObjectMapper()} shares. A plugin type resolved through a
 * default mapper stays in it after the bot is gone, and pins the plugin classloader with
 * it — the spike's scenario 3a, "N" (not collected). A private cache per bundle is
 * scenario 3b, "Y".
 * <p>
 * <b>The mapper asserted on is the one the scenario actually deserializes with</b>: it is
 * read back out of the compiled {@link Scenario} ({@code Scenario.pipeline →
 * PipelineContext.objectMapper}, the reference {@code Scenario.process} hands to every
 * {@code ProcessableMessage}), not rebuilt by the test. So a bot class that goes back to a
 * bare {@code new ObjectMapper()} in {@code botBehaviorScenario()} fails here, whatever
 * {@code Bot.newMessageMapper()} does. One case per bot class with its own mapper, plus
 * {@code TaiXiuGameBot}, which inherits {@code BettingMiniGameBot}'s.
 */
@DisplayName("Per-bot mappers use the plugin bundle's TypeFactory (L-8)")
class PerBotMapperTypeFactoryTest {

    /** Every bot type that builds a per-bot message mapper. */
    enum BotKind {
        BETTING_MINI(PerBotMapperTypeFactoryTest::bettingMiniBot),
        TAI_XIU(PerBotMapperTypeFactoryTest::taiXiuBot),
        SLOT(PerBotMapperTypeFactoryTest::slotBot),
        CASHOUT(PerBotMapperTypeFactoryTest::cashoutBot),
        CRASH(PerBotMapperTypeFactoryTest::crashBot);

        private final Supplier<Bot> fixture;

        BotKind(Supplier<Bot> fixture) {
            this.fixture = fixture;
        }
    }

    /** A type nothing else in the JVM resolves, so only this test can put it in a cache. */
    static final class Probe {
        public int x;
    }

    private final List<Scenario> scenarios = new ArrayList<>();
    private final List<Bot> bots = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // compile() starts the sendAsync stage's scheduler; leave no thread behind.
        scenarios.forEach(Scenario::shutdown);
        bots.forEach(Bot::cleanup);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BotKind.class)
    @DisplayName("the scenario's mapper resolves types through the wired bundle factory and its private cache")
    void scenarioMapperUsesTheBundleTypeFactory(BotKind kind) throws Exception {
        LookupCache<Object, JavaType> bundleCache = new LRUMap<>(16, 200);
        TypeFactory bundleTypeFactory = TypeFactory.defaultInstance().withCache(bundleCache);

        Bot bot = kind.fixture.get();
        bot.setPluginTypeFactory(bundleTypeFactory);
        ObjectMapper mapper = scenarioMapper(bot);

        assertThat(mapper.getTypeFactory()).isSameAs(bundleTypeFactory);
        // setTypeFactory rewires both configs; a mapper that only had its field swapped
        // (or none at all) would still resolve through the default on one side.
        assertThat(mapper.getDeserializationConfig().getTypeFactory()).isSameAs(bundleTypeFactory);
        assertThat(mapper.getSerializationConfig().getTypeFactory()).isSameAs(bundleTypeFactory);

        // And resolution really lands in the bundle's cache, not the shared one: this is
        // the property the spike measured, and the one that releases the loader.
        mapper.readValue("{\"x\":1}", Probe.class);
        assertThat(bundleCache.get(Probe.class))
                .as("a type the scenario mapper resolves is cached in the bundle's private cache")
                .isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BotKind.class)
    @DisplayName("with no factory wired (fixtures only) the mapper keeps the shared default, as before")
    void unwiredBotFallsBackToTheDefaultTypeFactory(BotKind kind) throws Exception {
        Bot bot = kind.fixture.get();

        ObjectMapper mapper = scenarioMapper(bot);

        assertThat(mapper.getTypeFactory()).isSameAs(TypeFactory.defaultInstance());
    }

    // ------------------------------------------------------------------ plumbing

    private ObjectMapper scenarioMapper(Bot bot) throws Exception {
        bots.add(bot);
        // botBehaviorScenario() feeds Bot.client into the PipelineContext, which rejects
        // null; it is normally set during initialize()'s auth.
        bot.client = mock(VingameWebSocketClient.class);
        Scenario scenario = bot.botBehaviorScenario();
        scenarios.add(scenario);
        Field pipeline = Scenario.class.getDeclaredField("pipeline");
        pipeline.setAccessible(true);
        PipelineStage<?, ?> head = (PipelineStage<?, ?>) pipeline.get(scenario);
        return head.getContext().getObjectMapper();
    }

    // ------------------------------------------------------------------ fixtures

    private static BotBehaviorConfig behavior() {
        return BotBehaviorConfig.builder()
                .minBet(100_000).maxBet(1_000_000).betIncrement(100_000)
                .maxTotalBetPerRound(2_000_000).minBetsPerRound(1).maxBetsPerRound(1)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
    }

    private static BotConfiguration config(String user, Game game) {
        return BotConfiguration.builder()
                .credentials(BotCredentials.builder().username(user).password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-l8").botIndex(1)
                .game(game).behaviorConfig(behavior())
                .zoneName("MiniGame").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .cashoutFrameTimeoutSeconds(20).cashoutTimeoutBackoffSeconds(30)
                .cashoutReconnectAfterTimeouts(3)
                .strategyId(StrategyId.RANDOM.name())
                .build();
    }

    private static Bot bettingMiniBot() {
        Game game = Game.builder().id("g-bm").name("BauCua").pluginName("BauCua")
                .gameType(GameType.BETTING_MINI).offset(2000).numberOfOptions(6).build();
        BettingMiniGameBot b = new BettingMiniGameBot();
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(config("l8bm", game));
        b.setMessageTypes(new BomGameMessageTypes());
        b.setStrategyFactory(TestStrategyFactories.betting());
        b.setRandom(new Random(0L));
        b.initializeSubclass();
        return b;
    }

    private static Bot taiXiuBot() {
        Game game = Game.builder().id("g-tx").name("TaiXiu").pluginName("taixiuPlugin")
                .gameType(GameType.TAI_XIU).numberOfOptions(2).build();
        TaiXiuGameBot b = new TaiXiuGameBot();
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(config("l8tx", game));
        b.setTaiXiuMessageTypes(new MiniGameTaiXiuMessageTypes());
        b.setStrategyFactory(TestStrategyFactories.betting());
        b.setRandom(new Random(0L));
        b.initializeSubclass();
        return b;
    }

    private static Bot slotBot() {
        Game game = Game.builder().id("g-slot").name("SlotTip").pluginName("Tip")
                .gameType(GameType.SLOT).gameId(204).build();
        SlotMachineBot b = new SlotMachineBot();
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(config("l8slot", game));
        b.setMessageTypes(new SlotMessageTypesImpl());
        b.setSlotStrategyFactory(TestStrategyFactories.slot());
        b.initializeSubclass();
        return b;
    }

    private static Bot cashoutBot() {
        Game game = Game.builder().id("g-balloon").name("Balloon").pluginName("balloonPlugin")
                .gameType(GameType.CASHOUT).offset(1500).build();
        CashoutBot b = new CashoutBot();
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(config("l8cash", game));
        b.setMessageTypes(new Win79CashoutMessageTypes());
        b.setRandom(new Random(7L));
        b.initializeSubclass();
        return b;
    }

    private static Bot crashBot() {
        Game game = Game.builder().id("g-aviator").name("Aviator").pluginName("aviatorPlugin")
                .gameType(GameType.CRASH).offset(1700).build();
        CrashBot b = new CrashBot();
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(config("l8crash", game));
        b.setMessageTypes(new Win79CrashMessageTypes());
        b.setRandom(new Random(7L));
        b.initializeSubclass();
        return b;
    }
}
