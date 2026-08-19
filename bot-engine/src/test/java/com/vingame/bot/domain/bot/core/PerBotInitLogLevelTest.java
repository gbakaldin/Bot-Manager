package com.vingame.bot.domain.bot.core;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.auth.TokensProvider;
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
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LOG_VOLUME_TIERING Phase 1, tier 1 — the per-bot {@code initialized} lines are DEBUG,
 * and the group-level replacement is fed exactly once per bot.
 * <p>
 * <b>Why the level itself is worth a test.</b> This is the largest single INFO class in the
 * application by burst volume: one line per bot at group start, i.e. 30,000 lines when a
 * 30k-bot fleet comes up, all inside a few seconds, at the exact moment the operator most
 * needs the log to be readable. Phase 1 replaces it with one line per group from
 * {@link GroupLifecycleAggregator} — but "replaces" only holds if the per-bot line actually
 * went down a level. A revert is one word (`log.debug` → `log.info`) in a class nobody reads
 * for logging reasons, it breaks no test that asserts on message content, and its symptom is
 * not an error but a fleet start that is 30,000 lines louder than it was designed to be.
 * {@link com.vingame.bot.infrastructure.observability.GroupLifecycleAggregatorTest} proves
 * the aggregate exists; this proves the thing it aggregates stopped shouting.
 * <p>
 * Both concrete bot classes are covered because they are twins that are edited separately
 * — the plan lists {@code BettingMiniGameBot:192} and {@code SlotMachineBot:155} as two of
 * the five call sites precisely because a change to one routinely misses the other.
 */
@DisplayName("Per-bot 'initialized' logging is DEBUG and feeds the group aggregate (P1-4/P1-5)")
class PerBotInitLogLevelTest {

    private static final String GROUP_ID = "group-init-level";

    private CapturingAppender appender;
    private LoggerContext ctx;
    private LoggerConfig loggerConfig;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        appender = new CapturingAppender("CapturingAppender-init-level");
        appender.start();
        ctx = (LoggerContext) LogManager.getContext(false);
        // Attach at the shared application logger so both bot classes are captured by one
        // appender, and raise it to DEBUG so a still-INFO line and a correctly-DEBUG line
        // are BOTH recorded — the level is then asserted, not inferred from presence.
        loggerConfig = ctx.getConfiguration().getLoggerConfig("com.vingame.bot");
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
    @DisplayName("BettingMiniGameBot's initialized line is DEBUG, never INFO")
    void bettingBotInitializedIsDebug() {
        BettingMiniGameBot bot = new BettingMiniGameBot();
        ApiGatewayClient apiGw = mock(ApiGatewayClient.class);
        when(apiGw.getApiGateway()).thenReturn("http://gw.test");
        bot.setClients(apiGw, mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(bettingConfig());

        bot.initializeSubclass();

        assertThat(levelsOf("BettingMiniGameBot initialized"))
                .as("one line per bot at group start — 30k lines on a fleet start. Tier 1 "
                        + "replaced it with one line per group; INFO here means BOTH are "
                        + "emitted and the phase bought nothing.")
                .containsExactly(Level.DEBUG);
    }

    @Test
    @DisplayName("SlotMachineBot's initialized line is DEBUG, never INFO")
    void slotBotInitializedIsDebug() {
        SlotMachineBot bot = new SlotMachineBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class),
                mock(ClientFactory.class));
        bot.setConfiguration(slotConfig());
        bot.setMessageTypes(new SlotMessageTypesImpl());

        bot.initializeSubclass();

        assertThat(levelsOf("SlotMachineBot initialized"))
                .as("the slot twin of the betting line — the two are edited separately and "
                        + "the plan lists them as two of the five sites for that reason")
                .containsExactly(Level.DEBUG);
    }

    @Test
    @DisplayName("each bot feeds the group aggregator exactly once, from initializeSubclass")
    void eachBotFeedsTheAggregatorOnce() {
        GroupLifecycleAggregator aggregator = mock(GroupLifecycleAggregator.class);

        BettingMiniGameBot betting = new BettingMiniGameBot();
        ApiGatewayClient apiGw = mock(ApiGatewayClient.class);
        when(apiGw.getApiGateway()).thenReturn("http://gw.test");
        betting.setClients(apiGw, mock(GameMsClient.class), mock(ClientFactory.class));
        betting.setConfiguration(bettingConfig());
        betting.setGroupLifecycleAggregator(aggregator);
        betting.initializeSubclass();

        SlotMachineBot slot = new SlotMachineBot();
        slot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class),
                mock(ClientFactory.class));
        slot.setConfiguration(slotConfig());
        slot.setMessageTypes(new SlotMessageTypesImpl());
        slot.setGroupLifecycleAggregator(aggregator);
        slot.initializeSubclass();

        // The count on the aggregate line is only right if the feed is 1:1 with bots. An
        // extra feed inflates "47/47 bots initialized" into a number an operator would
        // read as a second group starting; a missing one under-reports a full start as a
        // partial one, which is the shape tier 1 uses to mean "something went wrong".
        verify(aggregator, times(2)).recordInitialized(anyString());
    }

    @Test
    @DisplayName("the feed happens with the bot's MDC already applied, or it is dropped")
    void theAggregatorFeedSeesTheGroupMdc() {
        // The single sequencing fact tier 1 rests on, and it is invisible when broken:
        // GroupLifecycleAggregator.recordInitialized() resolves its group from
        // MDC(botGroupId) and RETURNS SILENTLY when there is none. Bot.initialize()
        // happens to call BotMdc.set(...) before initializeSubclass(); move the feed
        // earlier, or the BotMdc.set later, and every feed is dropped — no exception, no
        // warning, just a group-start line that never appears while the per-bot DEBUG
        // lines it replaced are also gone.
        GroupLifecycleAggregator aggregator = mock(GroupLifecycleAggregator.class);
        List<String> groupIdsSeenByTheAggregator = new ArrayList<>();
        doAnswer(invocation -> {
            groupIdsSeenByTheAggregator.add(MDC.get(BotMdc.BOT_GROUP_ID));
            return null;
        }).when(aggregator).recordInitialized(anyString());

        VingameWebSocketClient wsClient = mock(VingameWebSocketClient.class);
        TokensProvider tokens = mock(TokensProvider.class);
        when(tokens.getAgencyToken()).thenReturn("agency1234567890");
        when(tokens.getAuthToken()).thenReturn("auth1234567890abc");
        ApiGatewayClient apiGw = mock(ApiGatewayClient.class);
        when(apiGw.getApiGateway()).thenReturn("http://gw.test");
        when(apiGw.authenticate(any())).thenReturn(tokens);
        ClientFactory clientFactory = mock(ClientFactory.class);
        when(clientFactory.newClient(any(), anyString())).thenReturn(wsClient);

        BettingMiniGameBot bot = new BettingMiniGameBot();
        bot.setClients(apiGw, mock(GameMsClient.class), clientFactory);
        bot.setConfiguration(bettingConfig());
        bot.setGroupLifecycleAggregator(aggregator);
        bot.initialize();

        assertThat(groupIdsSeenByTheAggregator)
                .as("Bot.initialize() must apply the bot's MDC before initializeSubclass() "
                        + "runs the tier-1 feed; a null here means every feed is silently "
                        + "discarded and the group-level line never emits")
                .containsExactly(GROUP_ID);
    }

    @Test
    @DisplayName("a bot with no aggregator wired still initializes — fixtures must not need Spring")
    void aggregatorIsOptional() {
        SlotMachineBot bot = new SlotMachineBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class),
                mock(ClientFactory.class));
        bot.setConfiguration(slotConfig());
        bot.setMessageTypes(new SlotMessageTypesImpl());

        // No setGroupLifecycleAggregator: the null-tolerance the field's javadoc promises.
        // Every non-Spring bot fixture in this module depends on it.
        bot.initializeSubclass();

        assertThat(levelsOf("SlotMachineBot initialized")).containsExactly(Level.DEBUG);
    }

    // ---- fixtures

    private static BotConfiguration bettingConfig() {
        return BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("bot_init").password("pw").fingerprint("fp").build())
                .environmentId("env-init").botGroupId(GROUP_ID).botIndex(1)
                .game(Game.builder().id("g1").name("BauCua").pluginName("BauCua")
                        .gameType(GameType.BETTING_MINI)
                        .offset(2000).numberOfOptions(6).build())
                .behaviorConfig(behavior())
                .zoneName("MiniGame3").timeoutMillis(60_000L).watchdogTimeoutSeconds(120L)
                .build();
    }

    private static BotConfiguration slotConfig() {
        return BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("slot_init").password("pw").fingerprint("fp").build())
                .environmentId("env-init").botGroupId(GROUP_ID).botIndex(1)
                .game(Game.builder().id("g-slot").name("SlotTip").pluginName("Tip")
                        .gameType(GameType.SLOT).gameId(204).build())
                .behaviorConfig(behavior())
                .zoneName("MiniGame3").timeoutMillis(60_000L)
                .build();
    }

    private static BotBehaviorConfig behavior() {
        return BotBehaviorConfig.builder()
                .minBet(100).maxBet(1000).betIncrement(100)
                .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(3)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
    }

    private List<Level> levelsOf(String fragment) {
        List<Level> levels = new ArrayList<>();
        for (LogEvent event : appender.events()) {
            if (event.getMessage().getFormattedMessage().contains(fragment)) {
                levels.add(event.getLevel());
            }
        }
        return levels;
    }

    /** Minimal in-memory appender (same idiom as SlotMachineBotSpinCostLogTest). */
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
