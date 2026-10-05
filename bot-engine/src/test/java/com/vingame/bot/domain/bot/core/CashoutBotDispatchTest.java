package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.Plan;
import com.vingame.bot.domain.bot.message.cashout.CashoutBetFrame;
import com.vingame.bot.domain.bot.message.g4.win79.cashout.Win79CashoutBet;
import com.vingame.bot.domain.bot.message.g4.win79.cashout.Win79CashoutMessageTypes;
import com.vingame.bot.domain.bot.message.g4.win79.cashout.Win79CashoutProgressFrame;
import com.vingame.bot.domain.bot.message.g4.win79.cashout.Win79CashoutResultFrame;
import com.vingame.bot.domain.bot.message.g4.win79.cashout.Win79CashoutSubscribeResponse;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CASHOUT_BOT Phase 3: {@link CashoutBot} driven by hand — no scenario engine, no
 * socket — the {@code SlotMachineBotSpinStreamTest} approach. Pull the bet condition and
 * supplier, feed real 119 frame objects into the frame handler, fire the watchdog with a
 * fake clock, and read the real Micrometer counters.
 */
@DisplayName("CashoutBot dispatch (Phase 3)")
class CashoutBotDispatchTest {

    private static final long START_BALANCE = 50_000_000L;
    private static final int OFFSET = 1500;
    private static final List<Long> SERVER_BETS =
            List.of(1_000L, 10_000L, 100_000L, 500_000L, 1_000_000L, 5_000_000L, 10_000_000L);

    private final AtomicLong now = new AtomicLong(10_000_000L);
    private final List<String> reconnectReasons = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SimpleMeterRegistry registry;
    private VingameWebSocketClient channel;
    private CashoutBot bot;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        channel = mock(VingameWebSocketClient.class);
        bot = newBot(1_000L, 100_000L);
    }

    @AfterEach
    void tearDown() {
        bot.cleanup();
    }

    private CashoutBot newBot(long minBet, long maxBet) {
        return newBot(minBet, maxBet, 3);
    }

    private CashoutBot newBot(long minBet, long maxBet, int reconnectAfter) {
        Game game = Game.builder()
                .id("g-balloon").name("Balloon").pluginName("balloonPlugin")
                .gameType(GameType.CASHOUT).offset(OFFSET).build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(minBet).maxBet(maxBet).autoDepositEnabled(false).build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("cashbot1").password("pw").fingerprint("fp").build())
                .environmentId("env-119").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame").timeoutMillis(60_000L)
                .cashoutFrameTimeoutSeconds(20).cashoutTimeoutBackoffSeconds(30).cashoutReconnectAfterTimeouts(reconnectAfter)
                .build();

        CashoutBot b = new CashoutBot() {
            @Override
            protected void triggerFullReconnect(String reason) {
                reconnectReasons.add(reason);
            }
        };
        b.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        b.setConfiguration(cfg);
        b.setMessageTypes(new Win79CashoutMessageTypes());
        b.setClock(now::get);
        b.setRandom(new Random(7L));
        b.initializeSubclass();
        b.setMetrics(new BotMetrics(registry));
        b.lastFetchedBalance = START_BALANCE;
        b.expectedCurrentBalance.set(START_BALANCE);

        ObjectMapper wire = new ObjectMapper();
        b.bindCashoutChannel(channel, wire);
        return b;
    }

    // ------------------------------------------------------------------ helpers

    private void subscribe(List<Long> bets) {
        bot.onSubscribe(new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE,
                new Win79CashoutSubscribeResponse(OFFSET, bets, 0L)));
    }

    private void frame(CashoutBetFrame f) {
        bot.onFrame(new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, f));
    }

    private static Win79CashoutProgressFrame progress(long sid, long stake, double odds) {
        return new Win79CashoutProgressFrame(OFFSET + 1, stake, odds, stake * odds, odds + 0.02,
                stake * (odds + 0.02), 0, false, sid, null, false, false);
    }

    private static Win79CashoutProgressFrame burstOnProgressCmd(long sid, long stake) {
        return new Win79CashoutProgressFrame(OFFSET + 1, stake, 1.7, 0.0, null, null,
                0, true, sid, null, false, false);
    }

    private static Win79CashoutResultFrame burstOnResultCmd(long sid) {
        return new Win79CashoutResultFrame(OFFSET + 2, null, 4.0, 0.0, null, null,
                -1, true, sid, 0.0, false, false);
    }

    private static Win79CashoutResultFrame win(long sid, double odds, double crd) {
        return new Win79CashoutResultFrame(OFFSET + 2, null, odds, crd, null, null,
                0, true, sid, 0.0, false, false);
    }

    /** Pass the pause, run condition then supplier, and return the placed plan. */
    private Plan placeBet() {
        bot.machine().nextBetAt().ifPresent(at -> now.set(Math.max(now.get(), at)));
        assertThat(bot.betCondition().get()).as("bet condition opens").isTrue();
        ActionRequestMessage out = bot.bet().get();
        assertThat(out).isInstanceOf(Win79CashoutBet.class);
        return bot.machine().currentPlan().orElseThrow();
    }

    private double count(String name, String... tags) {
        Counter c = registry.find(name).tags(tags).counter();
        return c == null ? 0.0 : c.count();
    }

    private List<JsonNode> sentFrames() throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(channel, org.mockito.Mockito.atLeast(0)).send(captor.capture());
        List<JsonNode> frames = new ArrayList<>();
        for (String s : captor.getAllValues()) {
            frames.add(mapper.readTree(s));
        }
        return frames;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("subscribe -> bet -> progress xN -> win: one cash-out with the bound sid, winnings credited, counters")
    void winStream() throws Exception {
        assertThat(bot.betCondition().get()).as("no bet before subscribe").isFalse();
        subscribe(SERVER_BETS);
        assertThat(bot.eligibleStakes()).containsExactly(1_000L, 10_000L, 100_000L);
        assertThat(count(BotMetrics.BOT_CASHOUT_BETS_TOTAL, "outcome", "timeout"))
                .as("pre-registered at 0 on subscribe (AD-12)").isZero();
        assertThat(registry.find(BotMetrics.BOT_CASHOUT_BETS_TOTAL).tag("outcome", "timeout").counter())
                .isNotNull();

        Plan plan = placeBet();
        assertThat(plan.amount()).isIn(1_000L, 10_000L, 100_000L);
        assertThat(bot.getExpectedBalance()).isEqualTo(START_BALANCE - plan.amount());
        assertThat(bot.betCondition().get()).as("one bet in flight").isFalse();

        long sid = 1_801_744L;
        for (int i = 0; i < 10; i++) {
            now.addAndGet(200);
            frame(progress(sid, plan.amount(), 1.0 + (plan.target() - 1.0) * i / 20.0));
        }
        verify(channel, never()).send(anyString());

        frame(progress(sid, plan.amount(), plan.target()));
        frame(progress(sid, plan.amount(), plan.target() + 0.1));
        frame(progress(sid, plan.amount(), plan.target() + 0.2));

        List<JsonNode> sent = sentFrames();
        assertThat(sent).as("exactly one cash-out").hasSize(1);
        JsonNode body = sent.get(0).get(3);
        assertThat(sent.get(0).get(2).asText()).isEqualTo("balloonPlugin");
        assertThat(body.get("cmd").asInt()).isEqualTo(1502);
        assertThat(body.get("sid").asLong()).isEqualTo(sid);

        double crd = plan.amount() * plan.target();
        long before = bot.getExpectedBalance();
        frame(win(sid, plan.target(), crd));

        long expectedWin = Math.round(crd);
        assertThat(bot.getExpectedBalance()).isEqualTo(before + expectedWin);
        assertThat(bot.getLastRoundWinnings()).isEqualTo(expectedWin);
        assertThat(count(BotMetrics.BOT_BETS_PLACED_TOTAL)).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_BET_AMOUNT_TOTAL)).isEqualTo((double) plan.amount());
        assertThat(count(BotMetrics.BOT_WINNINGS_TOTAL)).isEqualTo((double) expectedWin);
        assertThat(count(BotMetrics.BOT_CASHOUT_BETS_TOTAL, "outcome", "cashout")).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_CASHOUT_BETS_TOTAL, "outcome", "burst")).isZero();
        assertThat(count(BotMetrics.BOT_MESSAGES_TOTAL, "cmd", "cashoutResult")).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_MESSAGES_TOTAL, "cmd", "cashoutProgress")).isEqualTo(13.0);
        assertThat(bot.machine().inFlight()).isFalse();
        verify(channel, times(1)).send(anyString());
    }

    @Test
    @DisplayName("a burst on X501 (crd 0, iF true) counts burst, no winnings, one confirmed bet")
    void burstOnProgressCmd() {
        subscribe(SERVER_BETS);
        Plan plan = placeBet();
        frame(progress(42L, plan.amount(), 1.01));
        long before = bot.getExpectedBalance();

        frame(burstOnProgressCmd(42L, plan.amount()));

        assertThat(bot.getExpectedBalance()).isEqualTo(before);
        assertThat(count(BotMetrics.BOT_CASHOUT_BETS_TOTAL, "outcome", "burst")).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_CASHOUT_BETS_TOTAL, "outcome", "cashout")).isZero();
        assertThat(count(BotMetrics.BOT_WINNINGS_TOTAL)).isZero();
        assertThat(count(BotMetrics.BOT_BETS_PLACED_TOTAL)).isEqualTo(1.0);
        verify(channel, never()).send(anyString());
    }

    @Test
    @DisplayName("a burst on X502 (blS -1, no b) counts burst, no winnings — even with no progress at all")
    void burstOnResultCmd() {
        subscribe(SERVER_BETS);
        placeBet();

        frame(burstOnResultCmd(43L));

        assertThat(count(BotMetrics.BOT_CASHOUT_BETS_TOTAL, "outcome", "burst")).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_WINNINGS_TOTAL)).isZero();
        assertThat(bot.getLastRoundWinnings()).isZero();
        assertThat(bot.machine().inFlight()).isFalse();
    }

    @Test
    @DisplayName("watchdog expiry counts timeout; triggerFullReconnect only on the 3rd consecutive one")
    void watchdogLadder() {
        subscribe(SERVER_BETS);
        for (int i = 1; i <= 3; i++) {
            placeBet();
            now.addAndGet(20_000L);
            bot.onWatchdogExpired();

            assertThat(count(BotMetrics.BOT_CASHOUT_BETS_TOTAL, "outcome", "timeout")).isEqualTo((double) i);
            assertThat(reconnectReasons).as("after timeout #%d", i).hasSize(i < 3 ? 0 : 1);
        }
        assertThat(reconnectReasons.get(0)).startsWith("watchdog").contains("3 cash-out bets unanswered");
        assertThat(count(BotMetrics.BOT_WATCHDOG_EXPIRED_TOTAL)).isEqualTo(1.0);
        assertThat(count(BotMetrics.BOT_BETS_PLACED_TOTAL)).as("a timeout is not a confirmed bet").isZero();
    }

    @Test
    @DisplayName("a watchdog that fires early (a frame arrived) times nothing out")
    void earlyWatchdogIsHarmless() {
        subscribe(SERVER_BETS);
        Plan plan = placeBet();
        now.addAndGet(15_000L);
        frame(progress(50L, plan.amount(), 1.02));
        now.addAndGet(10_000L);

        bot.onWatchdogExpired();

        assertThat(count(BotMetrics.BOT_CASHOUT_BETS_TOTAL, "outcome", "timeout")).isZero();
        assertThat(bot.machine().inFlight()).isTrue();
    }

    @Test
    @DisplayName("after a timeout the next bet is a probe at the cheapest stake")
    void probeAfterTimeout() {
        subscribe(SERVER_BETS);
        placeBet();
        now.addAndGet(20_000L);
        bot.onWatchdogExpired();

        Plan probe = placeBet();

        assertThat(probe.probe()).isTrue();
        assertThat(probe.amount()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("beforeReconnect mid-bet returns to IDLE; a crossing frame afterwards sends no cash-out")
    void beforeReconnectMidBet() {
        subscribe(SERVER_BETS);
        Plan plan = placeBet();
        frame(progress(60L, plan.amount(), 1.01));

        bot.beforeReconnect();

        assertThat(bot.machine().inFlight()).isFalse();
        frame(progress(60L, plan.amount(), plan.target() + 1));
        verify(channel, never()).send(anyString());
    }

    @Test
    @DisplayName("an empty stake intersection means the bot does not bet")
    void emptyIntersection() {
        bot.cleanup();
        bot = newBot(20_000_000L, 30_000_000L);
        subscribe(SERVER_BETS);

        assertThat(bot.eligibleStakes()).isEmpty();
        now.addAndGet(60_000L);
        assertThat(bot.betCondition().get()).isFalse();
    }

    @Test
    @DisplayName("maxBet unset (0) uses the [1000, 100000] default window — Balloon parity (AD-6)")
    void defaultWindow() {
        bot.cleanup();
        bot = newBot(0L, 0L);
        subscribe(SERVER_BETS);

        assertThat(bot.eligibleStakes()).containsExactly(1_000L, 10_000L, 100_000L);
    }

    @Test
    @DisplayName("Soccer parity window [1000, 1000000] gives all five legacy stakes")
    void soccerWindow() {
        assertThat(CashoutBot.eligibleStakes(SERVER_BETS, 1_000L, 1_000_000L))
                .containsExactly(1_000L, 10_000L, 100_000L, 500_000L, 1_000_000L);
    }

    @Test
    @DisplayName("a balance below the cheapest stake parks the bot")
    void brokeBotParks() {
        subscribe(SERVER_BETS);
        bot.expectedCurrentBalance.set(999L);
        bot.lastFetchedBalance = 999L;
        now.addAndGet(60_000L);

        assertThat(bot.betCondition().get()).isFalse();
        assertThat(bot.machine().inFlight()).isFalse();
    }

    @Test
    @DisplayName("review #3: a bet's late terminal handling does not cancel the NEXT bet's watchdog")
    void lateTerminalKeepsNextBetsWatchdog() {
        subscribe(SERVER_BETS);
        Plan first = placeBet();
        assertThat(bot.watchdogArmed()).isTrue();

        // The frame handler wins the CAS to IDLE and then stalls before its follow-up...
        CashoutBetFrame terminal = burstOnResultCmd(70L);
        bot.machine().onFrame(progress(70L, first.amount(), 1.01));
        var ended = (com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.FrameAction.Ended)
                bot.machine().onFrame(terminal);
        // ...long enough for the bet loop to place bet N+1 and arm its watchdog...
        placeBet();
        assertThat(bot.watchdogArmed()).isTrue();

        // ...then the stalled handler resumes.
        bot.onBetEnded(ended, terminal);

        assertThat(bot.watchdogArmed())
                .as("bet N+1 must keep its watchdog, or a silent bet N+1 wedges the bot forever")
                .isTrue();
        assertThat(bot.machine().inFlight()).isTrue();
    }

    @Test
    @DisplayName("review #5: the subscribe reply after a ladder reconnect keeps the timeout backoff")
    void subscribeAfterTimeoutKeepsBackoff() {
        subscribe(SERVER_BETS);
        placeBet();
        now.addAndGet(20_000L);
        bot.onWatchdogExpired();
        long backoffUntil = bot.machine().nextBetAt().orElseThrow();

        bot.beforeReconnect();          // reconnect...
        now.addAndGet(2_000L);
        subscribe(SERVER_BETS);         // ...and the re-subscribe's reply

        assertThat(bot.machine().nextBetAt()).hasValue(backoffUntil);
        now.set(backoffUntil - 1);
        assertThat(bot.betCondition().get()).as("still inside the backoff").isFalse();
    }

    @Test
    @DisplayName("review #6: bot.cashout.reconnect-after-timeouts=0 means the default 3, not 'disabled'")
    void zeroReconnectAfterMeansDefault() {
        bot.cleanup();
        bot = newBot(1_000L, 100_000L, 0);
        subscribe(SERVER_BETS);
        for (int i = 1; i <= 3; i++) {
            placeBet();
            now.addAndGet(20_000L);
            bot.onWatchdogExpired();
        }
        assertThat(reconnectReasons).hasSize(1);
    }

    @Test
    @DisplayName("review #9 / Q-3: a null in the server's bets does not break subscribe")
    void nullServerBetIsDropped() {
        List<Long> withNull = new ArrayList<>(SERVER_BETS);
        withNull.add(1, null);

        subscribe(withNull);

        assertThat(bot.eligibleStakes()).containsExactly(1_000L, 10_000L, 100_000L);
        now.addAndGet(60_000L);
        assertThat(bot.betCondition().get()).as("the bot bets").isTrue();
    }

    @Test
    @DisplayName("a CASHOUT game with no offset fails loud at initialize")
    void missingOffsetFailsLoud() {
        CashoutBot b = new CashoutBot();
        b.setConfiguration(BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("x").password("p").build())
                .game(Game.builder().name("Balloon").gameType(GameType.CASHOUT).pluginName("balloonPlugin").build())
                .zoneName("MiniGame")
                .build());
        b.setMessageTypes(new Win79CashoutMessageTypes());

        org.assertj.core.api.Assertions.assertThatThrownBy(b::initializeSubclass)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Balloon")
                .hasMessageContaining("offset");
    }
}
