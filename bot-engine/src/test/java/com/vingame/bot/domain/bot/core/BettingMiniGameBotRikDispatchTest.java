package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.g3.rik.RikBetInfo;
import com.vingame.bot.domain.bot.message.g3.rik.RikEndGameMessage;
import com.vingame.bot.domain.bot.message.g3.rik.RikMainBetSummary;
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
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The join between {@link RikEndGameMessage}'s payout markers and
 * {@link BettingMiniGameBot#onEndGame}'s dispatch — the same gap
 * {@code BettingMiniGameBotTipDispatchTest} closes for Tip.
 * <p>
 * {@code RikGameMessageTypesTest} / {@code RikEndGamePayoutSemanticsTest} call the
 * interface methods directly; {@code BettingMiniGameBotTest} drives the dispatch with
 * a stub. Neither shows that <b>this product's</b> numbers reach
 * {@code bot_winnings_total} / {@code bot_bets_placed_total} /
 * {@code bot_bet_amount_total}, which is what the plan's V-9 checks on staging after
 * a deploy. "The counters read a flat zero while bots visibly stake" is a recurring,
 * documented staging pain point, and Phase 1 shipped with those two markers
 * deliberately <i>omitted</i> — so the wiring is exactly the thing worth a regression
 * test rather than a re-read of the class.
 * <p>
 * Messages are built through the public constructor rather than from a fixture: the
 * RIK fixtures live in {@code bot-messages} where
 * {@code RikFixtureProvenanceTest} binds each one to a committed wire capture, and
 * copying them into this module would create a second, unbound copy free to drift.
 * The values below are the captured rounds' real numbers and are named as such.
 */
@DisplayName("BettingMiniGameBot.onEndGame x RikEndGameMessage (integration)")
class BettingMiniGameBotRikDispatchTest {

    private static final int RIK_STOCK_OFFSET = 10000;
    private static final int STOCK_END_GAME_CMD = 13006;

    private BettingMiniGameBot bot;
    private BotMetrics metrics;

    @BeforeEach
    void setUp() {
        BotCredentials credentials = BotCredentials.builder()
                .username("rikbot1").password("pw").fingerprint("fp").build();
        Game game = Game.builder()
                .id("g-rik").name("RIK Stock").pluginName("stockPlugin")
                .offset(RIK_STOCK_OFFSET).numberOfOptions(2).build();
        BotBehaviorConfig behavior = BotBehaviorConfig.builder()
                .minBet(1000).maxBet(5000).betIncrement(1000)
                .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(3)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .build();
        BotConfiguration cfg = BotConfiguration.builder()
                .credentials(credentials)
                .environmentId("394301f4-6daf-4c55-a073-502a81c00731")
                .botGroupId("group-rik").botIndex(1)
                .game(game).behaviorConfig(behavior)
                .zoneName("MiniGame").timeoutMillis(60_000L)
                .watchdogTimeoutSeconds(120L)
                .build();

        bot = new BettingMiniGameBot();
        bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
        bot.setConfiguration(cfg);
        bot.setRandom(mock(Random.class));
        bot.initializeSubclass();
        bot.setRandom(mock(Random.class));

        metrics = mock(BotMetrics.class);
        bot.setMetrics(metrics);

        seed(bot, "lastFetchedBalance", 50_000_000L);
        seedAtomic(bot, "expectedCurrentBalance", 50_000_000L);
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
    @DisplayName("stock mbs round: winnings 4704 and stake 3000 reach the counters, and no jackpot ever does")
    void stockRoundDrivesWinningsAndBetTotals() throws Exception {
        // Captured round sid 3793249: staked 3000 on a +60% move, gross return 4704.
        RikEndGameMessage end = stockEndGame(3793249L, 60,
                List.of(new RikMainBetSummary(3000L, 4704L, 3000L)),
                List.of(new RikBetInfo(0, 1, 0L, 3000L), new RikBetInfo(1, 0, 0L, 0L)));

        invokeOnEndGame(bot, end);

        InOrder order = inOrder(metrics);
        order.verify(metrics).incBotMessage("endGame");
        order.verify(metrics).incBotWinnings(4704L);
        order.verify(metrics).incBetsPlaced(1, 3000L);
        // AD-19: no HasJackpot / HasJackpotPool marker, so the jackpot counter must
        // never fire on this product however the frame is shaped.
        verify(metrics, never()).incBotJackpot(anyLong());
        assertThat(((Bot) bot).getLastRoundWinnings()).isEqualTo(4704L);
    }

    @Test
    @DisplayName("a LOSING stock round still increments bot_winnings_total by 735 — wm is a gross return")
    void losingRoundStillIncrementsWinnings() throws Exception {
        // Captured round sid 3793247: staked 3000 on a -75% move. The bot is down
        // 2265 and the counter still moves by 735, because wm INCLUDES the stake.
        // That is what makes bot_winnings_total / bot_bet_amount_total the ~0.98 RTP
        // anchor V-9 reads rather than a near-zero net. A "fix" that nets the stake
        // off would emit nothing at all here (the w > 0 guard), and RTP would read
        // ~0 on a product whose real RTP is ~0.98.
        RikEndGameMessage end = stockEndGame(3793247L, -75,
                List.of(new RikMainBetSummary(3000L, 735L, 3000L)),
                List.of(new RikBetInfo(0, 1, 0L, 3000L), new RikBetInfo(1, 0, 0L, 0L)));

        invokeOnEndGame(bot, end);

        verify(metrics).incBotWinnings(735L);
        verify(metrics).incBetsPlaced(1, 3000L);
    }

    @Test
    @DisplayName("the room's obs[].v NEVER reaches bot_bet_amount_total, even when it dwarfs our own stake")
    void roomWideObsNeverReachesTheStakeCounter() throws Exception {
        // Both captures were taken on the only bettor in the room, so obs[].v equals
        // mbs[].b in every real frame and no fixture can tell the two apart. Here the
        // room staked 900 000 000 and this bot staked 1000. Charging the room's total
        // to every bot in the group is the failure this asserts against — it would
        // inflate bot_bet_amount_total by orders of magnitude and drive RTP to ~0.
        RikEndGameMessage end = stockEndGame(3793250L, 60,
                List.of(new RikMainBetSummary(1000L, 1568L, 1000L)),
                List.of(new RikBetInfo(0, 40, 0L, 900_000_000L), new RikBetInfo(1, 7, 0L, 123_456L)));

        invokeOnEndGame(bot, end);

        verify(metrics).incBetsPlaced(1, 1000L);
        verify(metrics, never()).incBetsPlaced(anyInt(), eq(900_000_000L));
        verify(metrics, never()).incBetsPlaced(anyInt(), eq(900_123_456L));
        // bc counts distinct PLAYERS on this product (47 across the two options), so
        // it must not become the bet count either.
        verify(metrics, never()).incBetsPlaced(40, 1000L);
        verify(metrics, never()).incBetsPlaced(47, 1000L);
    }

    @Test
    @DisplayName("txmd5 shape: the top-level wm and bs[].b drive the same two counters (AD-16 / AD-17)")
    void txmd5RoundDrivesTheSameCountersFromDifferentFields() throws Exception {
        // Captured round sid 2473044 on taixiuMd5Plugin (offset 4000): no mbs at all,
        // own win in the top-level wm (198 000 = 1.98 x 100 000), own stake in bs[].b.
        RikEndGameMessage end = new RikEndGameMessage(
                /*cmd*/ 7006, /*sid*/ 2473044L, /*d1*/ 5, /*d2*/ 4, /*d3*/ 4,
                /*obs*/ null,
                /*bs*/ List.of(new RikBetInfo(1, 1, 100_000L, 100_000L),
                               new RikBetInfo(2, 0, 0L, 0L)),
                /*mbs*/ null,
                /*wm*/ 198_000L,
                /*iJp*/ false, /*tJpV*/ 0L, /*tJpv2*/ 0L);

        invokeOnEndGame(bot, end);

        verify(metrics).incBotWinnings(198_000L);
        verify(metrics).incBetsPlaced(1, 100_000L);
        // iJp/tJpV are modelled but unwired (AD-19) — still no jackpot counter.
        verify(metrics, never()).incBotJackpot(anyLong());
    }

    @Test
    @DisplayName("a round the bot did not bet: no winnings increment, and the stake batch is a zeroed no-op")
    void roundWithoutOwnBetIncrementsNothing() throws Exception {
        // Captured round sid 2473045: the server omits wm and bs[].b entirely rather
        // than sending zeros. The w > 0 guard suppresses the winnings call; the
        // bet-totals batch is still made (BotMetrics owns the zero-drop contract).
        RikEndGameMessage end = new RikEndGameMessage(
                /*cmd*/ 7006, /*sid*/ 2473045L, /*d1*/ 5, /*d2*/ 3, /*d3*/ 1,
                /*obs*/ null,
                /*bs*/ List.of(new RikBetInfo(1, 0, 0L, 0L), new RikBetInfo(2, 0, 0L, 0L)),
                /*mbs*/ null,
                /*wm*/ 0L,
                /*iJp*/ false, /*tJpV*/ 0L, /*tJpv2*/ 0L);

        invokeOnEndGame(bot, end);

        verify(metrics, never()).incBotWinnings(anyLong());
        verify(metrics).incBetsPlaced(0, 0L);
        assertThat(((Bot) bot).getLastRoundWinnings()).isZero();
    }

    @Test
    @DisplayName("null metrics: a real RikEndGameMessage still routes through dispatch and reaches PAYOUT")
    void nullMetricsDoesNotCrash() throws Exception {
        bot.setMetrics(null);

        RikEndGameMessage end = stockEndGame(3793248L, -75,
                List.of(new RikMainBetSummary(4000L, 980L, 4000L)),
                List.of(new RikBetInfo(0, 1, 0L, 4000L)));

        invokeOnEndGame(bot, end);

        assertThat(readField(bot, "gameState")).isEqualTo(BettingMiniGameState.PAYOUT);
        // The local accumulator is independent of Prometheus wiring.
        assertThat(((Bot) bot).getLastRoundWinnings()).isEqualTo(980L);
    }

    /* ----- helpers ----- */

    private static RikEndGameMessage stockEndGame(long sid, int d1,
                                                  List<RikMainBetSummary> mbs,
                                                  List<RikBetInfo> obs) {
        return new RikEndGameMessage(
                STOCK_END_GAME_CMD, sid, d1, 0, 0,
                obs,
                /*bs*/ null,
                mbs,
                /*wm*/ 0L,
                /*iJp*/ false, /*tJpV*/ 0L, /*tJpv2*/ 0L);
    }

    private static void invokeOnEndGame(BettingMiniGameBot b, RikEndGameMessage end) throws Exception {
        ActionResponseMessage<RikEndGameMessage> resp =
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
