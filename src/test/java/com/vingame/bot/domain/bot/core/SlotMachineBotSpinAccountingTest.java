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
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase 4 verification (SLOT_MACHINE_BOT plan): {@code onSpinResult} accounting
 * — gross winnings credit, single-bet totals, lastRoundWinnings, balance delta,
 * and the one-spin-in-flight gate clearing (AD-6/AD-7).
 */
@DisplayName("SlotMachineBot.onSpinResult accounting (Phase 4)")
class SlotMachineBotSpinAccountingTest {

    private SlotMachineBot bot;
    private BotMetrics metrics;

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

        metrics = mock(BotMetrics.class);
        bot.setMetrics(metrics);

        seed(bot, "lastFetchedBalance", 50_000_000L);
        seedAtomic(bot, "expectedCurrentBalance", 50_000_000L);

        // Capture server-sourced config (numLines, allowedBetValues) via onSubscribe.
        invokeOnSubscribe(bot, deserializeSubscribe("/messages/slot/subscribeResponse.json"));
        // Simulate a spin in flight (set by spin()) so onSpinResult clears it.
        spinInFlight(bot).set(true);
    }

    @Test
    @DisplayName("Winning spin: gross winnings credited, single bet recorded, gate cleared")
    void winningSpin_accountsGrossWinnings() throws Exception {
        long before = bot.getExpectedBalance();
        SlotSpinResultMessage spin = deserializeSpin("/messages/slot/spinResult.json");

        invokeOnSpinResult(bot, spin);

        InOrder order = inOrder(metrics);
        order.verify(metrics).incBotMessage("spin");
        // Gross winnings = sum(wls[].crd) = 1000 + 5000 = 6000 (AD-7).
        order.verify(metrics).incBotWinnings(6000L);
        // Single spin = one bet at staked b=500 (AD-7).
        order.verify(metrics).incBetsPlaced(1, 500L);

        assertThat(bot.getLastRoundWinnings()).isEqualTo(6000L);
        // onSpinResult only credits winnings (the debit happens in spin()); +6000.
        assertThat(bot.getExpectedBalance()).isEqualTo(before + 6000L);
        assertThat(spinInFlight(bot).get()).as("gate cleared").isFalse();
    }

    @Test
    @DisplayName("Losing spin (wls empty, bw=false): no winnings credit, still one bet recorded")
    void losingSpin_noWinningsButStillCountsBet() throws Exception {
        long before = bot.getExpectedBalance();
        SlotSpinResultMessage loss = new SlotSpinResultMessage(
                1302, 500L, 204, List.of(), 14L, false, false, 0,
                false, false, 0L, 0L, false, List.of());

        invokeOnSpinResult(bot, loss);

        verify(metrics).incBotMessage("spin");
        verify(metrics, never()).incBotWinnings(anyLong());
        verify(metrics).incBetsPlaced(1, 500L);

        assertThat(bot.getLastRoundWinnings()).isZero();
        assertThat(bot.getExpectedBalance()).isEqualTo(before);
        assertThat(spinInFlight(bot).get()).isFalse();
    }

    /* ----- helpers ----- */

    private SlotSubscribeResponse deserializeSubscribe(String resource) throws Exception {
        return (SlotSubscribeResponse) readBody(resource);
    }

    private SlotSpinResultMessage deserializeSpin(String resource) throws Exception {
        return (SlotSpinResultMessage) readBody(resource);
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

    @SuppressWarnings("unchecked")
    private static AtomicBoolean spinInFlight(SlotMachineBot b) throws Exception {
        Field f = SlotMachineBot.class.getDeclaredField("spinInFlight");
        f.setAccessible(true);
        return (AtomicBoolean) f.get(b);
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
