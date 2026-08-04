package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.HasBotWinnings;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.util.BettingMiniGameState;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BOTGROUP_GAME_MANAGEMENT Phase 3 — pins that {@link BettingMiniGameBot#onEndGame}
 * increments the two new {@link Bot} accumulators at the exact site that mirrors
 * {@code bot_winnings_total}:
 * <ul>
 *   <li>AD-8: {@code cumulativeWinnings} increases by {@code winningsFor(user)} under the
 *       same {@code w > 0} guard as the metric, and does so INDEPENDENTLY of whether
 *       {@code metrics} is wired.</li>
 *   <li>AD-9: {@code roundsObserved} increments once per completed round, regardless of
 *       the winnings outcome (a losing round still counts).</li>
 * </ul>
 */
@DisplayName("BettingMiniGameBot Phase-3 accumulators (AD-8 / AD-9)")
class BettingMiniGameBotAccumulatorTest {

    private BettingMiniGameBot bot;

    @BeforeEach
    void setUp() {
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) affinities.put(i, 1);
        Game game = Game.builder()
                .id("g-acc").name("BauCua").pluginName("BauCua")
                .offset(2000).optionAffinities(affinities).build();

        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(100).maxBet(1000).betIncrement(100)
                .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(3)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder().username("accbot").password("pw").fingerprint("fp").build())
                .environmentId("env-1").botGroupId("group-1").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame3").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .strategyId(StrategyId.RANDOM)
                .build();

        bot = new BettingMiniGameBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setRandom(new Random(0L));
        bot.initializeSubclass();

        seedLong("lastFetchedBalance", 50_000_000L);
        seedAtomic("expectedCurrentBalance", 50_000_000L);
        setField("gameState", BettingMiniGameState.BET);
    }

    @AfterEach
    void tearDown() throws Exception {
        ScheduledExecutorService w = (ScheduledExecutorService) readField("watchdogScheduler");
        if (w != null) w.shutdownNow();
        ScheduledExecutorService s = (ScheduledExecutorService) readField("scheduler");
        if (s != null) s.shutdownNow();
    }

    @Test
    @DisplayName("Winning round: cumulativeWinnings += w, roundsObserved += 1, and it mirrors incBotWinnings(w)")
    void winningRoundIncrementsBothAndMirrorsMetric() throws Exception {
        BotMetrics metrics = mock(BotMetrics.class);
        bot.setMetrics(metrics);

        assertThat(bot.getCumulativeWinnings().get()).isZero();
        assertThat(bot.getRoundsObserved().get()).isZero();

        invokeOnStartGame(100L);
        invokeOnEndGame(new StubEndGame(100L, bot.getUserName(), 500L));

        // cumulativeWinnings mirrors bot_winnings_total value-for-value.
        assertThat(bot.getCumulativeWinnings().get()).isEqualTo(500L);
        verify(metrics).incBotWinnings(500L);
        // One completed round.
        assertThat(bot.getRoundsObserved().get()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Losing round (w=0): cumulativeWinnings stays 0, but roundsObserved still increments")
    void losingRoundCountsRoundButNotWinnings() throws Exception {
        bot.setMetrics(mock(BotMetrics.class));

        invokeOnStartGame(200L);
        invokeOnEndGame(new StubEndGame(200L, bot.getUserName(), 0L));

        assertThat(bot.getCumulativeWinnings().get()).as("w=0 guard: no winnings accrued").isZero();
        assertThat(bot.getRoundsObserved().get()).as("a losing round is still a completed round").isEqualTo(1L);
    }

    @Test
    @DisplayName("cumulativeWinnings accrues without metrics wired (not gated on metrics != null, AD-8)")
    void accumulatesWithoutMetrics() throws Exception {
        // Deliberately do NOT call bot.setMetrics(...) — metrics stays null.
        invokeOnStartGame(300L);
        invokeOnEndGame(new StubEndGame(300L, bot.getUserName(), 750L));

        assertThat(bot.getCumulativeWinnings().get()).isEqualTo(750L);
        assertThat(bot.getRoundsObserved().get()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Multiple rounds: cumulativeWinnings sums winners, roundsObserved counts every round")
    void multipleRoundsAccumulate() throws Exception {
        bot.setMetrics(mock(BotMetrics.class));

        long[] payouts = {100L, 0L, 400L};
        for (int i = 0; i < payouts.length; i++) {
            long sid = 400L + i;
            invokeOnStartGame(sid);
            invokeOnEndGame(new StubEndGame(sid, bot.getUserName(), payouts[i]));
        }

        // Winners summed (100 + 400 = 500); the 0-payout round adds nothing.
        assertThat(bot.getCumulativeWinnings().get()).isEqualTo(500L);
        // Every round counted, including the losing one.
        assertThat(bot.getRoundsObserved().get()).isEqualTo(3L);
    }

    /* ---- helpers ---- */

    private void invokeOnStartGame(long sid) throws Exception {
        StartGameMessage msg = mock(StartGameMessage.class);
        when(msg.getSessionId()).thenReturn(sid);
        ActionResponseMessage<StartGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        Method m = BettingMiniGameBot.class.getDeclaredMethod("onStartGame", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(bot, resp);
    }

    private void invokeOnEndGame(EndGameMessage msg) throws Exception {
        ActionResponseMessage<EndGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
        Method m = BettingMiniGameBot.class.getDeclaredMethod("onEndGame", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(bot, resp);
    }

    private Object readField(String name) throws Exception {
        Field f;
        try {
            f = BettingMiniGameBot.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = Bot.class.getDeclaredField(name);
        }
        f.setAccessible(true);
        return f.get(bot);
    }

    private void setField(String name, Object value) {
        try {
            Field f;
            try {
                f = BettingMiniGameBot.class.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                f = Bot.class.getDeclaredField(name);
            }
            f.setAccessible(true);
            f.set(bot, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void seedLong(String name, long value) {
        try {
            Field f = Bot.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setLong(bot, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void seedAtomic(String name, long value) {
        try {
            Field f = Bot.class.getDeclaredField(name);
            f.setAccessible(true);
            ((AtomicLong) f.get(bot)).set(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class StubEndGame extends EndGameMessage implements HasBotWinnings {
        private final long sessionId;
        private final String winningsUser;
        private final long winnings;

        StubEndGame(long sessionId, String winningsUser, long winnings) {
            super(0);
            this.sessionId = sessionId;
            this.winningsUser = winningsUser;
            this.winnings = winnings;
        }

        @Override
        public long getSessionId() {
            return sessionId;
        }

        @Override
        public long winningsFor(String userName) {
            return userName != null && userName.equals(winningsUser) ? winnings : 0L;
        }
    }
}
