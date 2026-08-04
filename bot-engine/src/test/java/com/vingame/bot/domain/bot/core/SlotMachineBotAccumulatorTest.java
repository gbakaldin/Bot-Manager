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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * BOTGROUP_GAME_MANAGEMENT Phase 3 — pins that {@link SlotMachineBot#onSpinResult}
 * increments the Phase-3 {@link Bot} accumulators:
 * <ul>
 *   <li>AD-8: {@code cumulativeWinnings += grossWinnings} under the same {@code winnings>0}
 *       guard as {@code incBotWinnings}, mirroring {@code bot_winnings_total}.</li>
 *   <li>AD-9: {@code roundsObserved} increments once per completed spin (for slots
 *       "rounds" means spins) — including losing spins.</li>
 * </ul>
 */
@DisplayName("SlotMachineBot Phase-3 accumulators (AD-8 / AD-9)")
class SlotMachineBotAccumulatorTest {

    private SlotMachineBot bot;
    private BotMetrics metrics;

    @BeforeEach
    void setUp() throws Exception {
        BotCredentials credentials = BotCredentials.builder()
                .username("slotacc1").password("pw").fingerprint("fp").build();
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

        invokeOnSubscribe(bot, deserializeSubscribe("/messages/slot/subscribeResponse.json"));
        spinInFlight(bot).set(true);
    }

    @Test
    @DisplayName("Winning spin: cumulativeWinnings += gross, roundsObserved += 1, mirrors incBotWinnings")
    void winningSpinIncrementsBothAndMirrorsMetric() throws Exception {
        assertThat(bot.getCumulativeWinnings().get()).isZero();
        assertThat(bot.getRoundsObserved().get()).isZero();

        invokeOnSpinResult(bot, deserializeSpin("/messages/slot/spinResult.json"));

        // Gross winnings = sum(wls[].crd) = 1000 + 5000 = 6000, mirroring bot_winnings_total.
        assertThat(bot.getCumulativeWinnings().get()).isEqualTo(6000L);
        verify(metrics).incBotWinnings(6000L);
        // One completed spin.
        assertThat(bot.getRoundsObserved().get()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Losing spin: cumulativeWinnings stays 0 but roundsObserved still increments (spin counts)")
    void losingSpinCountsSpinButNotWinnings() throws Exception {
        SlotSpinResultMessage loss = new SlotSpinResultMessage(
                1302, 500L, 204, List.of(), 14L, false, false, 0,
                false, false, 0L, 0L, false, List.of());

        invokeOnSpinResult(bot, loss);

        assertThat(bot.getCumulativeWinnings().get()).as("no winnings on a losing spin").isZero();
        assertThat(bot.getRoundsObserved().get()).as("a losing spin is still a completed spin").isEqualTo(1L);
    }

    @Test
    @DisplayName("Two spins (win then loss): winnings summed once, roundsObserved counts both")
    void twoSpinsAccumulate() throws Exception {
        invokeOnSpinResult(bot, deserializeSpin("/messages/slot/spinResult.json"));
        spinInFlight(bot).set(true); // simulate the next spin() setting the gate
        SlotSpinResultMessage loss = new SlotSpinResultMessage(
                1302, 500L, 204, List.of(), 14L, false, false, 0,
                false, false, 0L, 0L, false, List.of());
        invokeOnSpinResult(bot, loss);

        assertThat(bot.getCumulativeWinnings().get()).isEqualTo(6000L);
        assertThat(bot.getRoundsObserved().get()).isEqualTo(2L);
    }

    /* ---- helpers ---- */

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
