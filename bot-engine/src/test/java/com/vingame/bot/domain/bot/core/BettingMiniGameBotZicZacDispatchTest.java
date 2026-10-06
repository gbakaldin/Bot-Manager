package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.coordination.BetCoordinator;
import com.vingame.bot.domain.bot.coordination.JackpotScaler;
import com.vingame.bot.domain.bot.message.g3.rik.RikZicZacBallResult;
import com.vingame.bot.domain.bot.message.g3.rik.RikZicZacEndGameMessage;
import com.vingame.bot.domain.bot.strategy.TestStrategyFactories;
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
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The join between {@link RikZicZacEndGameMessage}'s markers and
 * {@code BettingMiniGameBot.onEndGame} — the sibling of
 * {@code BettingMiniGameBotRikDispatchTest}, for the 114 game that has its own
 * EndGame class.
 *
 * <p><b>Why this exists separately.</b> The whole point of RIK_114_ZICZAC Phase 1 is
 * that the metric this plan calls out — {@code bot_winnings_total} — reads a flat zero
 * on ziczac under the generic 114 provider, which is the exact silhouette of the "bets
 * never settle / host not whitelisted" failure {@code CLAUDE.md} records as having been
 * misdiagnosed twice. A message-layer test proves {@code winningsFor} returns the right
 * number; only this one proves the number reaches the counter. Those are different
 * claims and the gap between them is where "everything looks healthy and the dashboard
 * says zero" lives.
 *
 * <p>Messages are built through the public constructor, not from a fixture: the ziczac
 * fixtures live in bot-messages where {@code RikFixtureProvenanceTest} binds each one to
 * a committed wire capture, and a copy here would be a second, unbound copy free to
 * drift. Every number below is a captured round's real figure and is named as such.
 */
@DisplayName("BettingMiniGameBot.onEndGame x RikZicZacEndGameMessage (integration)")
class BettingMiniGameBotZicZacDispatchTest {

    private static final int ZICZAC_OFFSET = 9000;
    private static final int ZICZAC_END_GAME_CMD = 12006;

    private BettingMiniGameBot bot;
    private BotMetrics metrics;

    @BeforeEach
    void setUp() {
        // AD-11: ziczac has no options at all, and Game.getEffectiveOptionAffinities()
        // throws on an empty map, so a ziczac Game carries the degenerate {"0": 1}.
        // Driving the bot with exactly that is part of what this test is for.
        Map<Integer, Integer> singleOption = new LinkedHashMap<>();
        singleOption.put(0, 1);

        Game game = Game.builder()
                .id("g-ziczac").name("RIK ZicZac (Plinko)").pluginName("ziczacPlugin")
                .offset(ZICZAC_OFFSET).numberOfOptions(1)
                .optionAffinities(singleOption)
                .md5(false)
                .build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                // Kept well under mB = 50 000 000, the per-ball cap on this game.
                .minBet(50_000).maxBet(1_000_000).betIncrement(50_000)
                .maxTotalBetPerRound(10_000_000).minBetsPerRound(1).maxBetsPerRound(3)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(BotCredentials.builder()
                        .username("rikbot1").password("pw").fingerprint("fp").build())
                .environmentId("394301f4-6daf-4c55-a073-502a81c00731")
                .botGroupId("group-ziczac").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .build();

        bot = new BettingMiniGameBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setRandom(mock(Random.class));
        bot.setStrategyFactory(TestStrategyFactories.betting());
        bot.initializeSubclass();
        bot.setRandom(mock(Random.class));

        metrics = mock(BotMetrics.class);
        bot.setMetrics(metrics);

        seed(bot, "lastFetchedBalance", 500_000_000L);
        seedAtomic(bot, "expectedCurrentBalance", 500_000_000L);
        setField(bot, "gameState", BettingMiniGameState.BET);
    }

    @AfterEach
    void tearDown() throws Exception {
        ScheduledExecutorService w = (ScheduledExecutorService) readField(bot, "watchdogScheduler");
        if (w != null) w.shutdownNow();
        ScheduledExecutorService s = (ScheduledExecutorService) readField(bot, "scheduler");
        if (s != null) s.shutdownNow();
    }

    @Test
    @DisplayName("winning round 1995089: 1 405 000 winnings and a 1 000 000 / 20-ball stake reach the counters")
    void winningRoundDrivesWinningsAndBetTotals() throws Exception {
        invokeOnEndGame(bot, round1995089());

        InOrder order = inOrder(metrics);
        order.verify(metrics).incBotMessage("endGame");
        // THE metric this whole plan exists to un-zero. Under the generic 114 provider
        // this call does not happen at all — RikMainBetSummary has no `r`, ziczac has
        // no `wm` anywhere, and winningsFor returns 0 on every round forever.
        order.verify(metrics).incBotWinnings(1_405_000L);
        // 20 BALLS, not 20 clicks (AD-6). At the pinned c = 1 this equals the number
        // of engine ticks that bet, which is what makes
        // bot_bet_amount_total ~= bot_bets_placed_total x stake a usable staging check.
        order.verify(metrics).incBetsPlaced(20, 1_000_000L);

        // AD-7: HasJackpotPool is a POOL meter, not a per-user payout. No HasJackpot
        // marker, so the per-bot jackpot counter must never move however rich tJpV is.
        verify(metrics, never()).incBotJackpot(anyLong());
        assertThat(((Bot) bot).getLastRoundWinnings()).isEqualTo(1_405_000L);
    }

    @Test
    @DisplayName("losing round 1995087 still increments bot_winnings_total by 6 420 000 — r is a gross return")
    void losingRoundStillIncrementsWinnings() throws Exception {
        // Captured round 1995087: staked 10 700 000, returned 6 420 000. The bot is
        // down 4 280 000 and the counter still moves, because `odd` is a TOTAL
        // multiplier and r includes the stake. A "fix" that netted the stake off would
        // emit nothing here (the w > 0 guard) and turn the ~0.96 RTP anchor into ~-0.04
        // — which on a dashboard is indistinguishable from bets not settling.
        // Ten balls of 1 070 000 — four at 1.2x, four at 0.3x, two in the centre —
        // exactly /messages/rik/ziczac-endGame-loss.json.
        java.util.List<RikZicZacBallResult> balls = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) balls.add(new RikZicZacBallResult(1_070_000L, 1_284_000L, 1.2d));
        for (int i = 0; i < 4; i++) balls.add(new RikZicZacBallResult(1_070_000L, 321_000L, 0.3d));
        for (int i = 0; i < 2; i++) balls.add(new RikZicZacBallResult(1_070_000L, 0L, 0d));
        RikZicZacEndGameMessage end = new RikZicZacEndGameMessage(
                ZICZAC_END_GAME_CMD, 1995087L, balls, 513_800L, false);

        invokeOnEndGame(bot, end);

        verify(metrics).incBotWinnings(6_420_000L);
        verify(metrics).incBetsPlaced(10, 10_700_000L);
        // Down 4 280 000 on the round and the counter still moved.
        assertThat(((Bot) bot).getLastRoundWinnings()).isLessThan(10_700_000L).isPositive();
    }

    @Test
    @DisplayName("no-bet round 1995085 (mbs []): no winnings increment, and a zeroed stake batch")
    void noBetRoundIncrementsNothing() throws Exception {
        RikZicZacEndGameMessage end = new RikZicZacEndGameMessage(
                ZICZAC_END_GAME_CMD, 1995085L, List.of(), 204_400L, false);

        invokeOnEndGame(bot, end);

        verify(metrics, never()).incBotWinnings(anyLong());
        verify(metrics).incBetsPlaced(0, 0L);
        assertThat(((Bot) bot).getLastRoundWinnings()).isZero();
    }

    @Test
    @DisplayName("an all-centre-bucket round: a real 150 000 stake with zero winnings, and no NPE")
    void allCentreRoundStakesWithoutWinning() throws Exception {
        // odd == 0 in the centre bucket, so r == 0 is the game settling correctly. The
        // stake must still be charged or bot_bet_amount_total under-reports; 13 of the
        // capture's 81 balls landed here.
        RikZicZacEndGameMessage end = new RikZicZacEndGameMessage(
                ZICZAC_END_GAME_CMD, 1995090L,
                List.of(new RikZicZacBallResult(50_000L, 0L, 0d),
                        new RikZicZacBallResult(50_000L, 0L, 0d),
                        new RikZicZacBallResult(50_000L, 0L, 0d)),
                689_300L, false);

        invokeOnEndGame(bot, end);

        verify(metrics, never()).incBotWinnings(anyLong());
        verify(metrics).incBetsPlaced(3, 150_000L);
    }

    @Test
    @DisplayName("tJpV reaches the group jackpot scaler as a POOL observation, keyed on the round's sid")
    void jackpotPoolFeedsTheScaler() throws Exception {
        // AD-7's only live consequence: HasJackpotPool is inert unless an operator
        // turns jackpot-scale on, and then it is the pool that drives the volume
        // factor. This is the first 114 game whose meter is non-zero, so it is the
        // first that can move that factor at all.
        JackpotScaler scaler = new JackpotScaler(20_000_000L, JackpotScaler.DEFAULT_SEED_FLOOR, 0.25);
        bot.setJackpotScaler(scaler);

        invokeOnEndGame(bot, round1995089());

        // 684 300 is above the 500 000 seed floor and far below the ceiling, so the
        // factor leaves the neutral 1.0 — i.e. the meter was observed rather than
        // treated as "not observed".
        assertThat(scaler.getCurrentFactor()).isLessThan(1.0d).isGreaterThanOrEqualTo(0.25d);
    }

    @Test
    @DisplayName("no crowd is ever published from a ziczac EndGame (AD-8)")
    void noCrowdIsPublished() throws Exception {
        BetCoordinator coordinator = mock(BetCoordinator.class);
        bot.setCoordinator(coordinator);

        invokeOnEndGame(bot, round1995089());

        // The round still completes in the coordinator...
        verify(coordinator).onRoundComplete(1995089L);
        // ...but observeCrowd must never fire: obs was [] in 8/8 captured rounds and
        // this frame has no bs at all. Feeding an empty or fabricated distribution to a
        // game with one degenerate option would bias a picker that has nothing to pick.
        verify(coordinator, never()).observeCrowd(anyLong(), any());
    }

    @Test
    @DisplayName("null metrics: a real ziczac EndGame still routes through dispatch and reaches PAYOUT")
    void nullMetricsDoesNotCrash() throws Exception {
        bot.setMetrics(null);

        invokeOnEndGame(bot, round1995089());

        assertThat(readField(bot, "gameState")).isEqualTo(BettingMiniGameState.PAYOUT);
        // The local accumulator backs BotHealthDTO and is independent of Prometheus.
        assertThat(((Bot) bot).getLastRoundWinnings()).isEqualTo(1_405_000L);
    }

    @Test
    @DisplayName("a null mbs on the wire cannot kill the round handler on a bot thread")
    void nullMbsIsSurvivable() throws Exception {
        RikZicZacEndGameMessage end =
                new RikZicZacEndGameMessage(ZICZAC_END_GAME_CMD, 1995091L, null, 0L, false);

        invokeOnEndGame(bot, end);

        verify(metrics, never()).incBotWinnings(anyLong());
        verify(metrics).incBetsPlaced(0, 0L);
        assertThat(readField(bot, "gameState")).isEqualTo(BettingMiniGameState.PAYOUT);
    }

    @Test
    @DisplayName("the room's own scale never leaks into the stake counter — only mbs is charged")
    void onlyOwnBallsAreCharged() throws Exception {
        // ziczac's room feed rides 12007/12019, outside the four-CODE contract, so
        // there is no field on this frame that could carry the room's total. This pins
        // the consequence: whatever else the frame grows, the stake counter is driven
        // by mbs and nothing else.
        invokeOnEndGame(bot, round1995089());

        verify(metrics).incBetsPlaced(20, 1_000_000L);
        verify(metrics, never()).incBetsPlaced(anyInt(), org.mockito.ArgumentMatchers.eq(684_300L));
    }

    /* ----- helpers ----- */

    /**
     * Captured round {@code sid 1995089} verbatim: 20 balls of 50 000, gross return
     * 1 405 000, pool 684 300. Same numbers as {@code /messages/rik/ziczac-endGame.json}
     * — the fixture itself stays in bot-messages, bound to the capture.
     */
    private static RikZicZacEndGameMessage round1995089() {
        double[] odds = {0.3, 0, 2, 0, 2, 2, 1.2, 1.2, 1.2, 5, 0, 2, 1.2, 2, 2, 0.3, 1.2, 1.2, 0.3, 3};
        long[] returns = {15_000, 0, 100_000, 0, 100_000, 100_000, 60_000, 60_000, 60_000,
                250_000, 0, 100_000, 60_000, 100_000, 100_000, 15_000, 60_000, 60_000, 15_000, 150_000};
        java.util.List<RikZicZacBallResult> balls = new java.util.ArrayList<>();
        for (int i = 0; i < odds.length; i++) {
            balls.add(new RikZicZacBallResult(50_000L, returns[i], odds[i]));
        }
        return new RikZicZacEndGameMessage(ZICZAC_END_GAME_CMD, 1995089L, balls, 684_300L, false);
    }

    private static void invokeOnEndGame(BettingMiniGameBot b, RikZicZacEndGameMessage end) throws Exception {
        ActionResponseMessage<RikZicZacEndGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, end);
        Method m = BettingMiniGameBot.class.getDeclaredMethod("onEndGame", ActionResponseMessage.class);
        m.setAccessible(true);
        m.invoke(b, resp);
    }

    private static Object readField(Object target, String name) throws Exception {
        Field f;
        try {
            f = target.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = target.getClass().getSuperclass().getDeclaredField(name);
        }
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f;
            try {
                f = target.getClass().getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                f = target.getClass().getSuperclass().getDeclaredField(name);
            }
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
