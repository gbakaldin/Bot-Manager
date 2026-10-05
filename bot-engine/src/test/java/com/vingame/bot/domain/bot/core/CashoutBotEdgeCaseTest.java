package com.vingame.bot.domain.bot.core;

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
import com.vingame.bot.domain.bot.message.request.SubscribeToLobbyMessage;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.ScopedDebugEscalator;
import com.vingame.websocketparser.VingameWebSocketClient;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CASHOUT_BOT QA: {@link CashoutBot} edges that {@code CashoutBotDispatchTest} does not
 * pin — duplicate and late terminal frames vs. outcome accounting, timeout accounting,
 * a failing cash-out send, the park-and-pop race fallback, stake selection against the
 * server's bets, the full reconnect ladder through the bot, and the real watchdog
 * scheduler (arm on send, re-arm after a mid-bet frame). Driven by hand, no socket.
 */
@DisplayName("CashoutBot — QA edge cases")
class CashoutBotEdgeCaseTest {

    private static final long START_BALANCE = 50_000_000L;
    private static final int OFFSET = 2500;
    private static final List<Long> SERVER_BETS =
            List.of(1_000L, 10_000L, 100_000L, 500_000L, 1_000_000L, 5_000_000L, 10_000_000L);

    private final AtomicLong now = new AtomicLong(10_000_000L);
    private final List<String> reconnectReasons = new CopyOnWriteArrayList<>();

    private SimpleMeterRegistry registry;
    private VingameWebSocketClient channel;
    private CashoutBot bot;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        channel = mock(VingameWebSocketClient.class);
        bot = newBot(1_000L, 1_000_000L, 20, true);
    }

    @AfterEach
    void tearDown() {
        bot.cleanup();
    }

    private CashoutBot newBot(long minBet, long maxBet, long frameTimeoutSeconds, boolean fakeClock) {
        Game game = Game.builder()
                .id("g-soccer").name("Soccer").pluginName("soccerPlugin")
                .gameType(GameType.CASHOUT).offset(OFFSET).build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(minBet).maxBet(maxBet).autoDepositEnabled(false).build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("cashbot2").password("pw").fingerprint("fp").build())
                .environmentId("env-119").botGroupId("group-2").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame").timeoutMillis(60_000L)
                .cashoutFrameTimeoutSeconds(frameTimeoutSeconds)
                .cashoutTimeoutBackoffSeconds(30)
                .cashoutReconnectAfterTimeouts(3)
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
        if (fakeClock) {
            b.setClock(now::get);
        }
        b.setRandom(new Random(11L));
        b.initializeSubclass();
        b.setMetrics(new BotMetrics(registry));
        b.lastFetchedBalance = START_BALANCE;
        b.expectedCurrentBalance.set(START_BALANCE);
        b.bindCashoutChannel(channel, new ObjectMapper());
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

    private Plan placeBet() {
        bot.machine().nextBetAt().ifPresent(at -> now.set(Math.max(now.get(), at)));
        assertThat(bot.betCondition().get()).as("bet condition opens").isTrue();
        assertThat(bot.bet().get()).isInstanceOf(Win79CashoutBet.class);
        return bot.machine().currentPlan().orElseThrow();
    }

    private void timeOutCurrentBet() {
        now.addAndGet(20_000L);
        bot.onWatchdogExpired();
    }

    private double count(String name, String... tags) {
        Counter c = registry.find(name).tags(tags).counter();
        return c == null ? 0.0 : c.count();
    }

    private double outcome(String o) {
        return count(BotMetrics.BOT_CASHOUT_BETS_TOTAL, "outcome", o);
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    // ------------------------------------------------------------------ outcome accounting

    @Nested
    @DisplayName("outcome accounting")
    class Accounting {

        @Test
        @DisplayName("the same burst on X501 and then X502 is counted once — one confirmed bet, one burst")
        void duplicateTerminalCountedOnce() {
            subscribe(SERVER_BETS);
            Plan plan = placeBet();
            frame(progress(7L, plan.amount(), 1.01));

            frame(burstOnProgressCmd(7L, plan.amount()));
            frame(burstOnResultCmd(7L));

            assertThat(outcome("burst")).isEqualTo(1.0);
            assertThat(count(BotMetrics.BOT_BETS_PLACED_TOTAL)).isEqualTo(1.0);
            assertThat(count(BotMetrics.BOT_BET_AMOUNT_TOTAL)).isEqualTo((double) plan.amount());
            assertThat(bot.getRoundsObserved().get()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a burst with no b on the wire is accounted at the plan's stake")
        void burstStakeFromPlan() {
            subscribe(SERVER_BETS);
            Plan plan = placeBet();

            frame(burstOnResultCmd(8L));

            assertThat(count(BotMetrics.BOT_BET_AMOUNT_TOTAL)).isEqualTo((double) plan.amount());
        }

        @Test
        @DisplayName("a duplicate win is credited once")
        void duplicateWinCreditedOnce() {
            subscribe(SERVER_BETS);
            Plan plan = placeBet();
            frame(progress(9L, plan.amount(), plan.target()));
            long before = bot.getExpectedBalance();
            double crd = plan.amount() * plan.target();

            frame(win(9L, plan.target(), crd));
            frame(win(9L, plan.target(), crd));

            assertThat(bot.getExpectedBalance()).isEqualTo(before + Math.round(crd));
            assertThat(count(BotMetrics.BOT_WINNINGS_TOTAL)).isEqualTo((double) Math.round(crd));
            assertThat(outcome("cashout")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a timeout keeps the local debit and is not a confirmed bet")
        void timeoutKeepsDebit() {
            subscribe(SERVER_BETS);
            Plan plan = placeBet();

            timeOutCurrentBet();

            assertThat(bot.getExpectedBalance()).isEqualTo(START_BALANCE - plan.amount());
            assertThat(outcome("timeout")).isEqualTo(1.0);
            assertThat(count(BotMetrics.BOT_BETS_PLACED_TOTAL)).isZero();
            assertThat(count(BotMetrics.BOT_BET_AMOUNT_TOTAL)).isZero();
            assertThat(bot.getRoundsObserved().get()).as("a timeout is not a completed bet").isZero();
        }

        @Test
        @DisplayName("a late win after its bet timed out is not credited and not counted (drift re-sync owns it)")
        void lateWinAfterTimeout() {
            subscribe(SERVER_BETS);
            Plan plan = placeBet();
            frame(progress(10L, plan.amount(), plan.target()));
            timeOutCurrentBet();
            long before = bot.getExpectedBalance();

            frame(win(10L, plan.target(), plan.amount() * plan.target()));

            assertThat(bot.getExpectedBalance()).isEqualTo(before);
            assertThat(outcome("cashout")).isZero();
            assertThat(outcome("timeout")).isEqualTo(1.0);
            assertThat(count(BotMetrics.BOT_WINNINGS_TOTAL)).isZero();
        }

        @Test
        @DisplayName("outcomes sum to bets: N cash-outs + M bursts + K timeouts")
        void outcomesSum() {
            subscribe(SERVER_BETS);
            long sid = 100L;
            for (int i = 0; i < 30; i++) {
                Plan plan = placeBet();
                switch (i % 3) {
                    case 0 -> {
                        frame(progress(sid, plan.amount(), plan.target()));
                        frame(win(sid, plan.target(), plan.amount() * plan.target()));
                    }
                    case 1 -> frame(burstOnResultCmd(sid));
                    default -> timeOutCurrentBet();
                }
                sid++;
            }
            assertThat(outcome("cashout")).isEqualTo(10.0);
            assertThat(outcome("burst")).isEqualTo(10.0);
            assertThat(outcome("timeout")).isEqualTo(10.0);
            assertThat(count(BotMetrics.BOT_BETS_PLACED_TOTAL)).isEqualTo(20.0);
            verify(channel, times(10)).send(anyString());
        }
    }

    // ------------------------------------------------------------------ cash-out channel

    @Nested
    @DisplayName("the cash-out send")
    class CashoutSend {

        @Test
        @DisplayName("a throwing channel does not escape the frame handler; the bet still ends normally")
        void throwingChannel() {
            doThrow(new IllegalStateException("socket closed")).when(channel).send(anyString());
            subscribe(SERVER_BETS);
            Plan plan = placeBet();

            assertThatCode(() -> frame(progress(20L, plan.amount(), plan.target()))).doesNotThrowAnyException();
            frame(burstOnResultCmd(20L));
            assertThat(outcome("burst")).isEqualTo(1.0);

            Plan next = placeBet();
            assertThatCode(() -> frame(progress(21L, next.amount(), next.target()))).doesNotThrowAnyException();
            verify(channel, times(2)).send(anyString());
        }

        @Test
        @DisplayName("an unbound channel does not escape the frame handler")
        void unboundChannel() {
            bot.bindCashoutChannel(null, null);
            subscribe(SERVER_BETS);
            Plan plan = placeBet();

            assertThatCode(() -> frame(progress(22L, plan.amount(), plan.target()))).doesNotThrowAnyException();
            assertThat(bot.machine().boundSid()).hasValue(22L);
        }
    }

    // ------------------------------------------------------------------ bet loop

    @Nested
    @DisplayName("the bet loop")
    class BetLoop {

        @Test
        @DisplayName("race fallback: a reconnect between condition and supplier still sends one bet, debited once")
        void raceFallbackRederives() {
            subscribe(SERVER_BETS);
            bot.machine().nextBetAt().ifPresent(now::set);
            assertThat(bot.betCondition().get()).isTrue();

            bot.beforeReconnect(); // clears the parked plan and resets the machine

            ActionRequestMessage out = bot.bet().get();
            assertThat(out).isInstanceOf(Win79CashoutBet.class);
            Plan plan = bot.machine().currentPlan().orElseThrow();
            assertThat(bot.getExpectedBalance()).isEqualTo(START_BALANCE - plan.amount());
        }

        @Test
        @DisplayName("race fallback with nothing placeable sends a harmless re-subscribe and debits nothing")
        void raceFallbackWithoutStakes() {
            ActionRequestMessage out = bot.bet().get(); // never subscribed: no stakes

            assertThat(out).isInstanceOf(SubscribeToLobbyMessage.class);
            assertThat(bot.getExpectedBalance()).isEqualTo(START_BALANCE);
            assertThat(bot.machine().inFlight()).isFalse();
        }

        @Test
        @DisplayName("stakes stay inside the server's bets ∩ [minBet, maxBet] and within the balance")
        void stakesWithinWindowAndBalance() {
            subscribe(SERVER_BETS);
            List<Long> seen = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                bot.expectedCurrentBalance.set(150_000L);
                bot.lastFetchedBalance = 150_000L;
                now.addAndGet(5_000L);
                if (bot.betCondition().get()) {
                    bot.bet().get();
                    seen.add(bot.machine().currentPlan().orElseThrow().amount());
                    bot.beforeReconnect();
                }
            }
            assertThat(seen).isNotEmpty();
            assertThat(seen).allMatch(a -> a == 1_000L || a == 10_000L || a == 100_000L);
            assertThat(seen).contains(1_000L, 10_000L, 100_000L);
        }

        @Test
        @DisplayName("a re-subscribe mid-bet drops the bet: no cash-out for it afterwards, ladder kept")
        void resubscribeMidBet() {
            subscribe(SERVER_BETS);
            placeBet();
            timeOutCurrentBet();
            Plan probe = placeBet();
            assertThat(probe.probe()).isTrue();

            subscribe(SERVER_BETS);

            assertThat(bot.machine().inFlight()).isFalse();
            assertThat(bot.machine().consecutiveTimeouts()).as("subscribe does not reset the ladder").isEqualTo(1);
            frame(progress(31L, probe.amount(), 10.0));
            verify(channel, never()).send(anyString());
        }
    }

    // ------------------------------------------------------------------ stake set

    @Nested
    @DisplayName("eligibleStakes (AD-6)")
    class EligibleStakes {

        @Test
        @DisplayName("bounds are inclusive, output ascending; null, zero and negative entries dropped")
        void filterAndSort() {
            List<Long> allowed = Arrays.asList(100_000L, null, 0L, -1_000L, 1_000L, 10_000L, 1_000_000L);

            assertThat(CashoutBot.eligibleStakes(allowed, 1_000L, 100_000L))
                    .containsExactly(1_000L, 10_000L, 100_000L);
            assertThat(CashoutBot.eligibleStakes(allowed, 0L, 100_000L))
                    .as("minBet 0 still never stakes 0")
                    .containsExactly(1_000L, 10_000L, 100_000L);
        }

        @Test
        @DisplayName("a window of one value, or one between server bets, behaves exactly")
        void narrowWindows() {
            assertThat(CashoutBot.eligibleStakes(SERVER_BETS, 500_000L, 500_000L)).containsExactly(500_000L);
            assertThat(CashoutBot.eligibleStakes(SERVER_BETS, 2_000L, 9_000L)).isEmpty();
            assertThat(CashoutBot.eligibleStakes(null, 1_000L, 100_000L)).isEmpty();
            assertThat(CashoutBot.eligibleStakes(List.of(), 1_000L, 100_000L)).isEmpty();
        }

        @Test
        @DisplayName("minBet set but maxBet unset still uses the default window, not [minBet, 0]")
        void maxUnsetIgnoresMin() {
            bot.cleanup();
            bot = newBot(10_000L, 0L, 20, true);
            subscribe(SERVER_BETS);

            assertThat(bot.eligibleStakes()).containsExactly(1_000L, 10_000L, 100_000L);
        }

        @Test
        @DisplayName("a subscribe with no bets authenticates the connection, registers the series, and does not bet")
        void subscribeWithoutBets() {
            subscribe(null);

            assertThat(bot.getStatus()).isEqualTo(BotStatus.CONNECTION_AUTHENTICATED);
            assertThat(registry.find(BotMetrics.BOT_CASHOUT_BETS_TOTAL).counters()).hasSize(3);
            now.addAndGet(60_000L);
            assertThat(bot.betCondition().get()).isFalse();
        }
    }

    // ------------------------------------------------------------------ ladder through the bot

    @Nested
    @DisplayName("the watchdog ladder through the bot")
    class Ladder {

        @Test
        @DisplayName("reconnects at 3 and 6; a bound frame resets so the next reconnect is 3 timeouts later")
        void fullLadder() {
            subscribe(SERVER_BETS);
            for (int i = 1; i <= 6; i++) {
                placeBet();
                timeOutCurrentBet();
            }
            assertThat(reconnectReasons).hasSize(2);
            assertThat(reconnectReasons.get(1)).contains("6 cash-out bets unanswered");

            Plan plan = placeBet();
            frame(progress(40L, plan.amount(), 1.01));
            frame(burstOnResultCmd(40L));
            for (int i = 1; i <= 3; i++) {
                placeBet();
                timeOutCurrentBet();
            }

            assertThat(reconnectReasons).hasSize(3);
            assertThat(reconnectReasons.get(2)).contains("3 cash-out bets unanswered");
            assertThat(count(BotMetrics.BOT_WATCHDOG_EXPIRED_TOTAL)).isEqualTo(3.0);
            assertThat(outcome("timeout")).isEqualTo(9.0);
        }

        @Test
        @DisplayName("a throwing scoped-debug escalator never stops the reconnect")
        void escalatorFailureDoesNotBlockReconnect() {
            ScopedDebugEscalator escalator = mock(ScopedDebugEscalator.class);
            doThrow(new RuntimeException("boom")).when(escalator).onWatchdogExpiry(anyString());
            bot.setScopedDebugEscalator(escalator);
            subscribe(SERVER_BETS);

            for (int i = 1; i <= 3; i++) {
                placeBet();
                timeOutCurrentBet();
            }

            verify(escalator).onWatchdogExpiry("group-2");
            assertThat(reconnectReasons).hasSize(1);
        }

        @Test
        @DisplayName("an idle bot's watchdog does nothing; a stopped bot's does nothing even mid-bet")
        void idleAndStopped() {
            subscribe(SERVER_BETS);
            now.addAndGet(600_000L);
            bot.onWatchdogExpired();
            assertThat(outcome("timeout")).isZero();

            placeBet();
            bot.cleanup();
            timeOutCurrentBet();

            assertThat(outcome("timeout")).isZero();
            assertThat(reconnectReasons).isEmpty();
        }
    }

    // ------------------------------------------------------------------ real scheduler

    @Nested
    @DisplayName("the real watchdog scheduler (wall clock)")
    class RealScheduler {

        @Test
        @DisplayName("sending a bet arms the watchdog: an unanswered bet times out on its own")
        void armedOnSend() throws Exception {
            bot.cleanup();
            bot = newBot(1_000L, 100_000L, 1, false);
            subscribe(SERVER_BETS);

            bot.bet().get(); // fallback path: places now, regardless of the pause

            assertThat(waitUntil(() -> outcome("timeout") == 1.0, 8_000L))
                    .as("the watchdog fired and counted the timeout").isTrue();
            assertThat(bot.machine().inFlight()).isFalse();
        }

        @Test
        @DisplayName("a watchdog that fires after a mid-bet frame re-arms for the remainder")
        void reArmsAfterFrame() throws Exception {
            bot.cleanup();
            bot = newBot(1_000L, 100_000L, 2, false);
            subscribe(SERVER_BETS);

            bot.bet().get();
            Plan plan = bot.machine().currentPlan().orElseThrow();
            Thread.sleep(500);
            frame(progress(50L, plan.amount(), 1.0)); // first fire (~2 s) sees ~1.5 s of silence

            assertThat(waitUntil(() -> outcome("timeout") == 1.0, 8_000L))
                    .as("without the re-arm the bet would hang in flight forever").isTrue();
            assertThat(reconnectReasons).isEmpty();
        }

        @Test
        @DisplayName("an idle bot is never timed out by the wall-clock watchdog")
        void idleNeverArmed() throws Exception {
            bot.cleanup();
            bot = newBot(1_000L, 100_000L, 1, false);
            subscribe(SERVER_BETS);

            Thread.sleep(1_500);

            assertThat(outcome("timeout")).isZero();
            assertThat(reconnectReasons).isEmpty();
        }
    }
}
