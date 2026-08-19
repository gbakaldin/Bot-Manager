package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.message.slot.SlotSubscribeResponse;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * LOG_VOLUME_TIERING Phase 2, step 6 — the {@code below spin cost} line is bounded.
 * <p>
 * <b>What it used to do.</b> The balance gate lives in the {@code sendAsync} condition,
 * which the scenario engine evaluates on the slot bot's fixed 3 s cadence. An underfunded
 * bot never leaves that state on its own, so the line fired ~1,200 times an hour, per bot,
 * indefinitely — the one log class in the application whose volume was a function of neither
 * bot count nor round rate but simply of elapsed time. That is why the plan calls it an
 * unbounded spam trap and fixes it in the same phase as the scoped-DEBUG machinery.
 * <p>
 * The state is now logged on its <em>transitions</em>: once on entry, once on exit. This
 * test drives 100 gate evaluations (5 minutes of real cadence) and pins that the count is
 * 1, not 100.
 */
@DisplayName("SlotMachineBot below-spin-cost logging is bounded (LOG_VOLUME_TIERING P2)")
class SlotMachineBotSpinCostLogTest {

    private static final String LOGGER = SlotMachineBot.class.getName();

    private SlotMachineBot bot;
    private CapturingAppender appender;
    private LoggerContext ctx;
    private LoggerConfig loggerConfig;
    private Level previousLevel;

    @BeforeEach
    void setUp() throws Exception {
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
                .zoneName("MiniGame3").timeoutMillis(60_000L)
                .build();

        bot = new SlotMachineBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setMessageTypes(new SlotMessageTypesImpl());
        bot.initializeSubclass();
        // Seed the balance BEFORE subscribing: onSubscribe → onNewSession → checkBalance,
        // which would otherwise reach for a WS client this fixture does not have.
        seed(bot, "lastFetchedBalance", 50_000_000L);
        seedAtomic(bot, "expectedCurrentBalance", 50_000_000L);
        subscribe(new SlotSubscribeResponse(1300, 204, winlines(25), List.of(tier(500))));

        appender = new CapturingAppender("CapturingAppender-spincost");
        appender.start();
        ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = ctx.getConfiguration().getLoggerConfig(LOGGER);
        previousLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.DEBUG);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(previousLevel);
        ctx.updateLoggers();
    }

    @Test
    @DisplayName("100 gate evaluations below the spin cost produce ONE line, not 100")
    void belowCostLogsOnceOnEntry() throws Exception {
        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition = (Supplier<Boolean>) invoke(bot, "spinCondition");
        // FIXED strategy → bet 500 over 25 winlines = 12_500 per spin.
        seedAtomic(bot, "expectedCurrentBalance", 12_499L);

        for (int i = 0; i < 100; i++) {
            assertThat(condition.get()).isFalse();
        }

        assertThat(messagesContaining("below spin cost"))
                .as("the gate is evaluated every 3s and the bot never leaves the state alone")
                .hasSize(1);
    }

    @Test
    @DisplayName("recovering logs the exit once, and a relapse re-arms the entry line")
    void transitionsAreLoggedBothWays() throws Exception {
        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition = (Supplier<Boolean>) invoke(bot, "spinCondition");

        seedAtomic(bot, "expectedCurrentBalance", 12_499L);
        condition.get();
        condition.get();

        // A deposit lands.
        seedAtomic(bot, "expectedCurrentBalance", 50_000L);
        condition.get();
        condition.get();

        assertThat(messagesContaining("below spin cost")).hasSize(1);
        assertThat(messagesContaining("covers spin cost")).hasSize(1);

        // …and is spent again: the state is genuinely tracked, not latched once forever.
        seedAtomic(bot, "expectedCurrentBalance", 100L);
        condition.get();
        condition.get();

        assertThat(messagesContaining("below spin cost")).hasSize(2);
    }

    @Test
    @DisplayName("a fully funded bot logs neither line")
    void fundedBotIsSilent() throws Exception {
        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition = (Supplier<Boolean>) invoke(bot, "spinCondition");
        seedAtomic(bot, "expectedCurrentBalance", 50_000_000L);

        for (int i = 0; i < 20; i++) {
            condition.get();
        }

        assertThat(messagesContaining("spin cost")).isEmpty();
    }

    private List<String> messagesContaining(String fragment) {
        List<String> matches = new ArrayList<>();
        for (LogEvent event : appender.events()) {
            String message = event.getMessage().getFormattedMessage();
            if (message.contains(fragment)) {
                matches.add(message);
            }
        }
        return matches;
    }

    // ---- fixtures (mirrors SlotMachineBotGateEdgeCasesTest)

    private void subscribe(SlotSubscribeResponse response) throws Exception {
        ActionResponseMessage<SlotSubscribeResponse> msg =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, response);
        Method m = SlotMachineBot.class.getDeclaredMethod("onSubscribe", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(bot, msg);
    }

    private static List<SlotSubscribeResponse.WinlineDef> winlines(int count) {
        List<SlotSubscribeResponse.WinlineDef> defs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            defs.add(new SlotSubscribeResponse.WinlineDef(i, new int[]{0, 0, 0, 0, 0}));
        }
        return defs;
    }

    private static SlotSubscribeResponse.JackpotTier tier(long bet) {
        return new SlotSubscribeResponse.JackpotTier(bet, 204, 0L, 1);
    }

    private static Object invoke(SlotMachineBot b, String name) throws Exception {
        Method m = SlotMachineBot.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(b);
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

    /** Minimal in-memory log4j2 appender (same idiom as StrategyDecisionLogLevelTest). */
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
}
