package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.websocketparser.message.properties.MessageCategory;
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
 * Phase 4 verification (SLOT_MACHINE_BOT plan): {@code onSubscribe} captures the
 * server-sourced winline count and allowed bet set, marks the connection
 * authenticated, and the AD-12 spin gate flips from blocked (before subscribe)
 * to allowed (after subscribe, given sufficient balance).
 */
@DisplayName("SlotMachineBot.onSubscribe (Phase 4)")
class SlotMachineBotSubscribeTest {

    private SlotMachineBot bot;
    private BotMetrics metrics;

    @BeforeEach
    void setUp() {
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

        // Seed balance cache so onNewSession() inside onSubscribe does not call out.
        seed(bot, "lastFetchedBalance", 50_000_000L);
        seedAtomic(bot, "expectedCurrentBalance", 50_000_000L);
    }

    @Test
    @DisplayName("Before onSubscribe: spinCondition() false (AD-12 gate); after: numLines/betValues captured and gate opens")
    void onSubscribe_capturesServerConfig_andOpensSpinGate() throws Exception {
        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition = (Supplier<Boolean>) invoke(bot, "spinCondition");

        // AD-12: no subscribe yet — gate is closed regardless of balance.
        assertThat(condition.get()).as("spinCondition before subscribe").isFalse();

        SlotSubscribeResponse resp = deserializeSubscribe("/messages/slot/subscribeResponse.json");
        invokeOnSubscribe(bot, resp);

        verify(metrics).incBotMessage("subscribe");
        assertThat(readField(bot, "numLines")).isEqualTo(25);
        @SuppressWarnings("unchecked")
        List<Long> betValues = (List<Long>) readField(bot, "allowedBetValues");
        assertThat(betValues).containsExactly(500L, 1000L, 2000L, 5000L, 10000L);
        assertThat(bot.getStatus()).isEqualTo(BotStatus.CONNECTION_AUTHENTICATED);

        // AD-12: after subscribe, with ample balance, the gate opens.
        assertThat(condition.get()).as("spinCondition after subscribe").isTrue();
    }

    @Test
    @DisplayName("Subscribe with no winlines/bet values: WARN + skip capture; connection still marked authenticated, spin gate stays closed")
    void onSubscribe_missingConfig_authenticatesButDoesNotCapture() throws Exception {
        SlotSubscribeResponse empty = new SlotSubscribeResponse(1300, 204, null, null);
        invokeOnSubscribe(bot, empty);

        // Server config is NOT captured — the bot must not spin blind (AD-12).
        assertThat(readField(bot, "numLines")).isEqualTo(0);
        assertThat(readField(bot, "allowedBetValues")).isNull();
        // But the connection IS authenticated (a live socket acked a 1300): the
        // bot must not wedge in AUTHENTICATING_CONNECTION on a degenerate response
        // (matches BettingMiniGameBot.onSubscribe — mark authenticated first).
        assertThat(bot.getStatus()).isEqualTo(BotStatus.CONNECTION_AUTHENTICATED);

        // The spin gate stays closed because numLines/allowedBetValues are unset.
        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition = (Supplier<Boolean>) invoke(bot, "spinCondition");
        assertThat(condition.get()).as("spin gate stays closed on degenerate 1300").isFalse();
    }

    /* ----- helpers ----- */

    private SlotSubscribeResponse deserializeSubscribe(String resource) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new SlotMessageTypesImpl().getTypeRegistrations());
        try (var in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("fixture " + resource).isNotNull();
            JsonNode root = mapper.readTree(in.readAllBytes());
            return (SlotSubscribeResponse) mapper.treeToValue(root.get(1),
                    com.vingame.bot.domain.bot.message.slot.SlotMessage.class);
        }
    }

    private static void invokeOnSubscribe(SlotMachineBot b, SlotSubscribeResponse resp) throws Exception {
        ActionResponseMessage<SlotSubscribeResponse> msg =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, resp);
        Method m = SlotMachineBot.class.getDeclaredMethod("onSubscribe", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(b, msg);
    }

    private static Object invoke(SlotMachineBot b, String name) throws Exception {
        Method m = SlotMachineBot.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(b);
    }

    private static Object readField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
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
