package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * QA gap coverage for {@link SlotMachineBot} — edge cases of the spin gates that
 * the Phase-4/7 dev tests do not pin directly (SLOT_MACHINE_BOT plan):
 * <ul>
 *   <li>AD-13 — the balance gate blocks a spin when
 *       {@code expectedCurrentBalance < chosenBet * numLines};</li>
 *   <li>AD-6/reconnect — {@code beforeReconnect()} clears the in-flight gate and
 *       the parked bet so a re-subscribe can spin afresh;</li>
 *   <li>AD-11/AD-12 — a subscribe response carrying a <em>different</em> winline
 *       count drives the staked cost gate (the count is server-sourced, not 25);</li>
 *   <li>Implementation Notes — a spin result for a foreign {@code gid} is ignored
 *       (no accounting) but still clears the in-flight gate.</li>
 * </ul>
 */
@DisplayName("SlotMachineBot gate edge cases (QA)")
class SlotMachineBotGateEdgeCasesTest {

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

        seed(bot, "lastFetchedBalance", 50_000_000L);
        seedAtomic(bot, "expectedCurrentBalance", 50_000_000L);
    }

    @Test
    @DisplayName("AD-13: balance below chosenBet*numLines closes the gate and parks nothing")
    void balanceBelowSpinCost_blocksSpin() throws Exception {
        // FIXED strategy → chosenBet = 500 (smallest). numLines = 25 → cost = 12_500.
        // Subscribe with the captured 25-line fixture.
        subscribe(new SlotSubscribeResponse(1300, 204,
                winlines(25), List.of(tier(500), tier(1000))));

        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition = (Supplier<Boolean>) invoke(bot, "spinCondition");

        // Exactly one unit below the cost (500 * 25 = 12_500) blocks the spin.
        seedAtomic(bot, "expectedCurrentBalance", 12_499L);
        assertThat(condition.get()).as("balance one below spin cost").isFalse();
        assertThat(pendingBet(bot).get()).as("nothing parked when gated").isEmpty();

        // Exactly at the cost opens the gate (>= comparison).
        seedAtomic(bot, "expectedCurrentBalance", 12_500L);
        assertThat(condition.get()).as("balance exactly at spin cost").isTrue();
        assertThat(pendingBet(bot).get()).as("bet parked once affordable").isPresent();
    }

    @Test
    @DisplayName("AD-11/AD-12: a different server-sourced numLines drives the cost gate")
    void differentNumLines_drivesCostGate() throws Exception {
        // A 10-line game → cost gate = 500 * 10 = 5_000, not 12_500.
        subscribe(new SlotSubscribeResponse(1300, 204,
                winlines(10), List.of(tier(500), tier(1000))));

        assertThat(readInt(bot, "numLines")).isEqualTo(10);

        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition = (Supplier<Boolean>) invoke(bot, "spinCondition");

        seedAtomic(bot, "expectedCurrentBalance", 4_999L);
        assertThat(condition.get()).as("below 10-line cost").isFalse();

        seedAtomic(bot, "expectedCurrentBalance", 5_000L);
        assertThat(condition.get()).as("at 10-line cost").isTrue();
    }

    @Test
    @DisplayName("AD-6/reconnect: beforeReconnect() clears the in-flight gate and the parked bet")
    void beforeReconnect_clearsGateAndPendingBet() throws Exception {
        subscribe(new SlotSubscribeResponse(1300, 204,
                winlines(25), List.of(tier(500))));

        // Park a bet and mark a spin in flight (as spin() would).
        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition = (Supplier<Boolean>) invoke(bot, "spinCondition");
        assertThat(condition.get()).isTrue();
        assertThat(pendingBet(bot).get()).isPresent();
        spinInFlight(bot).set(true);

        // Reconnect path resets both.
        Method m = SlotMachineBot.class.getDeclaredMethod("beforeReconnect");
        m.setAccessible(true);
        m.invoke(bot);

        assertThat(spinInFlight(bot).get()).as("in-flight gate cleared by reconnect").isFalse();
        assertThat(pendingBet(bot).get()).as("parked bet cleared by reconnect").isEmpty();
    }

    @Test
    @DisplayName("Foreign-gid spin result is ignored (no accounting) but clears the in-flight gate")
    void foreignGidResult_ignoredButClearsGate() throws Exception {
        subscribe(new SlotSubscribeResponse(1300, 204,
                winlines(25), List.of(tier(500))));
        spinInFlight(bot).set(true);
        long before = bot.getExpectedBalance();

        // gid 999 != bot gid 204 → guard fires.
        SlotSpinResultMessage foreign = new SlotSpinResultMessage(
                1302, 500L, 999, List.of(), 1L, true, false, 0,
                false, false, 0L, 0L, false,
                List.of(new SlotSpinResultMessage.WinLine(
                        9999L, 0, List.of(), "X", 1, false, false, false)));

        invokeOnSpinResult(bot, foreign);

        // No winnings credit, no bet recorded — the foreign frame is dropped.
        verify(metrics).incBotMessage("spin");
        verify(metrics, never()).incBotWinnings(anyLong());
        verify(metrics, never()).incBetsPlaced(anyInt(), anyLong());
        assertThat(bot.getExpectedBalance()).as("balance untouched by foreign gid").isEqualTo(before);
        // Gate is still cleared so the bot is not wedged (Implementation Notes).
        assertThat(spinInFlight(bot).get()).as("gate cleared even for foreign gid").isFalse();
    }

    /* ----- builders ----- */

    private static List<SlotSubscribeResponse.WinlineDef> winlines(int n) {
        java.util.List<SlotSubscribeResponse.WinlineDef> defs = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            defs.add(new SlotSubscribeResponse.WinlineDef(i, new int[]{0, 0, 0, 0, 0}));
        }
        return defs;
    }

    private static SlotSubscribeResponse.JackpotTier tier(long b) {
        return new SlotSubscribeResponse.JackpotTier(b, 204, 0L, 1);
    }

    private void subscribe(SlotSubscribeResponse resp) throws Exception {
        ActionResponseMessage<SlotSubscribeResponse> msg =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, resp);
        Method m = SlotMachineBot.class.getDeclaredMethod("onSubscribe", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(bot, msg);
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

    @SuppressWarnings("unchecked")
    private static AtomicBoolean spinInFlight(SlotMachineBot b) throws Exception {
        Field f = SlotMachineBot.class.getDeclaredField("spinInFlight");
        f.setAccessible(true);
        return (AtomicBoolean) f.get(b);
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<Optional<Long>> pendingBet(SlotMachineBot b) throws Exception {
        Field f = SlotMachineBot.class.getDeclaredField("pendingBet");
        f.setAccessible(true);
        return (AtomicReference<Optional<Long>>) f.get(b);
    }

    private static int readInt(SlotMachineBot b, String name) throws Exception {
        Field f = SlotMachineBot.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(b);
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
