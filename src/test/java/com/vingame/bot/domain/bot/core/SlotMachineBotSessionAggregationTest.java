package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.slot.SlotMessage;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.message.slot.SlotSpinResultMessage;
import com.vingame.bot.domain.bot.message.slot.SlotSubscribeResponse;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import com.vingame.bot.infrastructure.observability.SlotSessionStrategy;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Phase 3 verification (AGGREGATED_SESSION_LOGGING plan): the {@code SlotMachineBot}
 * feeds the {@link SessionAggregationService} synthetic slot window from its own
 * spin path (AD-12) — {@code spin()} → {@code recordSpin(totalStake)} and
 * {@code onSpinResult} → {@code recordSpinResult(winnings, iJ)} — mirroring how
 * {@code BettingMiniGameBot} feeds {@code recordBet}/{@code onSessionEnd}. Bot-sourced
 * (not frame-parsed); the aggregator is verified via a mock.
 */
@DisplayName("SlotMachineBot session-aggregation feed (Phase 3)")
class SlotMachineBotSessionAggregationTest {

    private SlotMachineBot bot;
    private SessionAggregationService aggregator;

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

        aggregator = mock(SessionAggregationService.class);
        bot.setSessionAggregator(aggregator);

        seed(bot, "lastFetchedBalance", 50_000_000L);
        seedAtomic(bot, "expectedCurrentBalance", 50_000_000L);

        // numLines=25, allowedBetValues from the fixture.
        invokeOnSubscribe(bot, deserializeSubscribe());
    }

    @Test
    @DisplayName("spin() feeds recordSpin with the TOTAL stake (perLine b * numLines)")
    void spin_feedsRecordSpinWithTotalStake() throws Exception {
        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition = (Supplier<Boolean>) invoke(bot, "spinCondition");
        @SuppressWarnings("unchecked")
        Supplier<ActionRequestMessage> spin = (Supplier<ActionRequestMessage>) invoke(bot, "spin");

        assertThat(condition.get()).isTrue(); // parks a bet (FIXED → 500 per line)
        spin.get();

        // Total stake = per-line 500 * numLines 25 = 12_500 (matches the debit/metric).
        verify(aggregator).recordSpin(SlotSessionStrategy.INSTANCE, "slotbot1", 12_500L);
    }

    @Test
    @DisplayName("onSpinResult feeds recordSpinResult with gross winnings and no jackpot")
    void spinResult_feedsRecordSpinResult() throws Exception {
        invokeOnSpinResult(bot, deserializeSpin());
        // Fixture: winnings = 1000 + 5000 = 6000, iJ = false.
        verify(aggregator).recordSpinResult(6000L, false);
    }

    @Test
    @DisplayName("onSpinResult passes the jackpot flag through when iJ is set")
    void spinResult_passesJackpotFlag() throws Exception {
        // A jackpot spin: iJ = true, one winning line crediting 50_000.
        SlotSpinResultMessage jackpot = new SlotSpinResultMessage(
                1302, 500L, 204, List.of(), 20L, true, false, 0,
                true, false, 50_000L, 50_000L, false,
                List.of(new SlotSpinResultMessage.WinLine(
                        50_000L, 1, List.of(), "J", 9, true, false, false)));

        invokeOnSpinResult(bot, jackpot);
        verify(aggregator).recordSpinResult(50_000L, true);
    }

    /* ----- fixtures + reflection helpers (mirror the sibling slot tests) ----- */

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
