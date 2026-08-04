package com.vingame.bot.domain.bot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.BettingMiniMessage;
import com.vingame.bot.domain.bot.message.g3.tip.TipEndGameMessage;
import com.vingame.bot.domain.bot.message.g3.tip.TipGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.tip.TipSubscribeMessage;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Integration-style verification that the marker-interface dispatch in
 * {@link BettingMiniGameBot#onEndGame} actually invokes the right
 * {@link BotMetrics} methods when fed a <b>real</b> {@link TipEndGameMessage}
 * instance — both built via the public constructor and built via the
 * Jackson-deserialized fixture pipeline used at runtime.
 * <p>
 * The unit tests in {@code TipGameMessageTypesTest} cover {@code TipEndGameMessage}'s
 * interface methods in isolation (direct call), and {@code BettingMiniGameBotTest}'s
 * {@code OnEndGameMarkerDispatchTests} cover the dispatch with a {@code StubEndGameMessage}.
 * Neither exercises the join — that's this test's job. Closing the gap means a refactor
 * that breaks the contract between the dispatch and the per-message extraction (e.g.
 * silently swallowing a non-zero {@code wm}) fails here and only here.
 */
@DisplayName("BettingMiniGameBot.onEndGame x TipEndGameMessage (integration)")
class BettingMiniGameBotTipDispatchTest {

    private static final int TIP_OFFSET = 8000;

    private BettingMiniGameBot bot;
    private BotMetrics metrics;

    @BeforeEach
    void setUp() {
        BotCredentials credentials = BotCredentials.builder()
                .username("tipbot1").password("pw").fingerprint("fp").build();
        Game game = Game.builder()
                .id("g-tip").name("Tip").pluginName("Tip")
                .offset(TIP_OFFSET).numberOfOptions(6).build();
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

        // Seed balance cache so onNewSession() inside onEndGame does not call out.
        seed(bot, "lastFetchedBalance", 50_000_000L);
        seedAtomic(bot, "expectedCurrentBalance", 50_000_000L);

        // Force gameState non-null so onEndGame is a meaningful transition.
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
    @DisplayName("Constructor-built TipEndGameMessage with wm + iJp + jpV + bs[]: all three counters fire in order")
    void constructorBuilt_dispatchesAllThreeCounters() throws Exception {
        // wm=1500 -> incBotWinnings(1500), iJp=true + jpV=10_000 -> incBotJackpot(10_000),
        // bs[(bc=2,b=2000), (bc=1,b=500)] -> incBetsPlaced(3, 2500).
        TipEndGameMessage tip = newTipEndGame(
                /*wm*/ 1500L,
                /*iJp*/ true, /*jpV*/ 10_000L, /*tJpV*/ 200_000L,
                List.of(
                        new TipSubscribeMessage.BetInfoWithTotal(0, 2, 2000L, 8000L),
                        new TipSubscribeMessage.BetInfoWithTotal(1, 1, 500L, 3500L)
                ));

        invokeOnEndGame(bot, tip);

        InOrder order = inOrder(metrics);
        order.verify(metrics).incBotMessage("endGame");
        order.verify(metrics).incBotWinnings(1500L);
        order.verify(metrics).incBotJackpot(10_000L);
        // Critical assertion: the dispatch must pass bs[].b (per-user) + bs[].bc, not
        // a local accumulator. If TipEndGameMessage.betAmountFor ever switched to reading
        // a private field, this would still pass with whatever value happens to be there;
        // but the fixture-driven test below pins the source to the bs[] arrays.
        order.verify(metrics).incBetsPlaced(3, 2500L);
        // lastRoundWinnings written from HasBotWinnings branch (AD-6).
        assertThat(((Bot) bot).getLastRoundWinnings()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("Fixture-deserialized TipEndGameMessage drives dispatch with the JSON-extracted values")
    void fixtureDeserialized_dispatchesAllThreeCounters() throws Exception {
        TipEndGameMessage tip = deserializeEndGame("/messages/tip/endGame.json");
        // Sanity check: the fixture's wm=1500 and iJp=true with jpV=1_603_000.
        // bs[] sums to (bc=2+1=3, b=2000+500=2500).
        assertThat(tip.getWm()).isEqualTo(1500L);
        assertThat(tip.isIJp()).isTrue();
        assertThat(tip.getJpV()).isEqualTo(1_603_000L);

        invokeOnEndGame(bot, tip);

        verify(metrics).incBotMessage("endGame");
        verify(metrics).incBotWinnings(1500L);
        // jpV (1_603_000), not tJpV (200_000) — pins the AD-2 decision through the
        // actual dispatch path, not just the direct interface call.
        verify(metrics).incBotJackpot(1_603_000L);
        verify(metrics, never()).incBotJackpot(200_000L);
        // bs[].b / bs[].bc — server-authoritative bet totals (per the HasBetTotals
        // contract; AD-4). If this ever falls back onto Bot.totalBetsPlaced /
        // Bot.totalBetAmount (which are SENT counts, not CONFIRMED), this assertion
        // would still fire on the test bot (no bets sent here -> 0) and break loud.
        verify(metrics).incBetsPlaced(3, 2500L);
    }

    @Test
    @DisplayName("iJp=false, jpV=0, tJpV=200_000: incBotJackpot NEVER fires (jpV pinning under dispatch)")
    void noJackpotWhenIJpFalse_doesNotFallBackToTJpV() throws Exception {
        // The defensible-default jackpot choice (jpV when iJp=true, else 0) must hold
        // when routed through the dispatch path, not just through direct interface calls.
        TipEndGameMessage tip = newTipEndGame(
                /*wm*/ 0L,
                /*iJp*/ false, /*jpV*/ 0L, /*tJpV*/ 200_000L,
                List.of());

        invokeOnEndGame(bot, tip);

        // Caller-side `if (j > 0)` guard prevents the call entirely.
        verify(metrics, never()).incBotJackpot(anyLong());
        // tJpV being 200_000 must NOT leak into the counter — pins the AD-2 field choice.
        verify(metrics, never()).incBotJackpot(200_000L);
        // wm=0 path: caller-side `if (w > 0)` guard prevents the call. lastRoundWinnings
        // is still overwritten with 0 per AD-6.
        verify(metrics, never()).incBotWinnings(anyLong());
        assertThat(((Bot) bot).getLastRoundWinnings()).isZero();
    }

    @Test
    @DisplayName("Empty bs[]: HasBetTotals branch still calls incBetsPlaced(0, 0); BotMetrics no-ops by contract")
    void emptyBetTotals_callsBatchWithZeros() throws Exception {
        // The dispatch does not gate on count>0 — the BotMetrics method itself does.
        // This pins the "no double-gating" contract documented on BotMetrics.incBetsPlaced.
        TipEndGameMessage tip = newTipEndGame(
                /*wm*/ 0L,
                /*iJp*/ false, /*jpV*/ 0L, /*tJpV*/ 0L,
                /*bs*/ null);

        invokeOnEndGame(bot, tip);

        // Dispatch must call incBetsPlaced even with empty/null bs[], because the
        // bot-side does not know the per-game payload shape — the responsibility for
        // "drop zero-count batches" sits inside BotMetrics.incBetsPlaced (verified
        // separately in BotMetricsTest.incBetsPlaced_zeroOrNegativeCountIsNoOp...).
        verify(metrics).incBetsPlaced(0, 0L);
    }

    @Test
    @DisplayName("Null metrics on bot: real TipEndGameMessage routes through dispatch without NPE; gameState -> PAYOUT")
    void nullMetrics_realTipMessage_doesNotCrash() throws Exception {
        bot.setMetrics(null);

        TipEndGameMessage tip = newTipEndGame(
                /*wm*/ 9999L,
                /*iJp*/ true, /*jpV*/ 50_000L, /*tJpV*/ 0L,
                List.of(new TipSubscribeMessage.BetInfoWithTotal(0, 1, 100L, 500L)));

        invokeOnEndGame(bot, tip);

        assertThat(readField(bot, "gameState")).isEqualTo(BettingMiniGameState.PAYOUT);
    }

    /* ----- helpers ----- */

    private static TipEndGameMessage newTipEndGame(long wm,
                                                   boolean iJp, long jpV, long tJpV,
                                                   List<TipSubscribeMessage.BetInfoWithTotal> bs) {
        return new TipEndGameMessage(
                /*cmd*/ 11006,
                /*iJ*/ false,
                /*gid*/ 1,
                /*ps*/ null,
                /*tJpV*/ tJpV,
                /*eIn*/ null,
                /*d1*/ 0, /*d2*/ 0, /*d3*/ 0,
                /*iJp*/ iJp,
                /*sid*/ 822070L,
                /*bs*/ bs,
                /*jpV*/ jpV,
                /*tJpv2*/ 0L,
                /*jPTp*/ 0,
                /*jpCD*/ null,
                /*wm*/ wm,
                /*sDi*/ null
        );
    }

    private TipEndGameMessage deserializeEndGame(String resourcePath) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(new TipGameMessageTypes().getTypeRegistrations(TIP_OFFSET, false));
        try (var in = getClass().getResourceAsStream(resourcePath)) {
            assertThat(in).as("fixture " + resourcePath).isNotNull();
            BettingMiniMessage parsed = mapper.readValue(in.readAllBytes(), BettingMiniMessage.class);
            assertThat(parsed).isInstanceOf(TipEndGameMessage.class);
            return (TipEndGameMessage) parsed;
        }
    }

    private static void invokeOnEndGame(BettingMiniGameBot b, TipEndGameMessage tip) throws Exception {
        ActionResponseMessage<TipEndGameMessage> resp =
                new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, tip);
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
