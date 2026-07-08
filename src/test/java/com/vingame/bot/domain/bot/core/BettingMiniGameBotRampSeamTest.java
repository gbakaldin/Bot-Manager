package com.vingame.bot.domain.bot.core;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.config.bot.BotCredentials;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BetDecision;
import com.vingame.bot.domain.bot.strategy.BettingStrategy;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.RoundResult;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.util.BettingMiniGameState;
import com.vingame.bot.domain.bot.util.SessionIdStore;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.ClientFactory;
import com.vingame.bot.infrastructure.client.GameMsClient;
import com.vingame.websocketparser.message.properties.MessageCategory;
import com.vingame.websocketparser.message.response.ActionResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for the JACKPOT_SCALE_AND_RAMP Phase R2 ramp bet-path seam
 * (AD-R1/AD-R2/AD-R5): the per-tick probabilistic accept gate wired into
 * {@code betCondition()} immediately after the {@code canBet()}/{@code strategy}
 * guards and <em>before</em> {@code decideBet}/{@code applyCoordination}.
 *
 * <p>Correctness invariants under test:
 * <ul>
 *   <li><b>Off = today, no RNG (AD-R5)</b> — with ramp disabled every eligible
 *       tick is accepted and {@code rng} is never drawn, so the strategy's
 *       RNG-consumption order is unchanged.</li>
 *   <li><b>Ordering</b> — a deferred tick never reaches the strategy
 *       ({@code decideInvocations} stays 0), so it advances no per-round
 *       counter and reserves no coordinator budget.</li>
 *   <li><b>Shape</b> — with a linear ramp ({@code k=1}) accept probability rises
 *       with elapsed fraction; {@code k>1} back-loads more than {@code k=1}.</li>
 *   <li><b>Fail-safe</b> — {@code timeForBetting <= 0} accepts unconditionally.</li>
 * </ul>
 */
@DisplayName("BettingMiniGameBot ramp seam (JACKPOT_SCALE_AND_RAMP Phase R2)")
class BettingMiniGameBotRampSeamTest {

    /* ---- off path: every tick accepted, RNG untouched ---- */

    @Test
    @DisplayName("Ramp off: every eligible tick accepted AND rng is never drawn")
    void rampOffAcceptsEveryTickWithoutDrawingRng() throws Exception {
        CountingRandom counting = new CountingRandom();
        Harness h = new Harness(rampBehavior(false, 3.0), counting);
        h.openRound(1001L, /*timeForBetting*/ 10_000L, /*remainingTime*/ 10_000L);

        Supplier<Boolean> condition = h.condition();
        for (int i = 0; i < 50; i++) {
            // sweep the whole window; every tick must still bet with ramp off
            h.remainingTime().set(10_000L - i * 200L);
            assertThat(condition.get()).as("tick %d accepted with ramp off", i).isTrue();
        }
        assertThat(counting.doubleDraws)
                .as("AD-R5: the off path must draw NO RNG (order preserved for the strategy)")
                .isZero();
        // strategy still consulted on every accepted tick
        assertThat(h.strategy.decideInvocations).isEqualTo(50);
    }

    @Test
    @DisplayName("Ramp shape <= 0: treated as off — accept, no RNG drawn")
    void rampShapeZeroIsOff() throws Exception {
        CountingRandom counting = new CountingRandom();
        Harness h = new Harness(rampBehavior(true, 0.0), counting);
        h.openRound(1002L, 10_000L, 5_000L);

        assertThat(h.condition().get()).isTrue();
        assertThat(counting.doubleDraws)
                .as("rampShape <= 0 short-circuits before any rng draw")
                .isZero();
    }

    /* ---- fail-safe: window <= 0 accepts ---- */

    @Test
    @DisplayName("window (timeForBetting) <= 0: accept unconditionally, no RNG drawn")
    void zeroWindowAccepts() throws Exception {
        CountingRandom counting = new CountingRandom();
        Harness h = new Harness(rampBehavior(true, 3.0), counting);
        h.openRound(1003L, /*timeForBetting*/ 0L, /*remainingTime*/ 0L);

        assertThat(h.condition().get()).isTrue();
        assertThat(counting.doubleDraws)
                .as("fail-safe path returns before drawing rng")
                .isZero();
    }

    /* ---- deferred tick never touches the strategy ---- */

    @Test
    @DisplayName("Ordering: a deferred tick never reaches the strategy (no counter advance)")
    void deferredTickDoesNotConsultStrategy() throws Exception {
        // rng that always returns 1.0 → nextDouble() < pAccept is always false
        // (pAccept maxes at 1.0), so every tick defers.
        Random alwaysDefer = new Random() {
            @Override
            public double nextDouble() {
                return 1.0;
            }
        };
        Harness h = new Harness(rampBehavior(true, 3.0), alwaysDefer);
        h.openRound(1004L, 10_000L, 5_000L); // mid-window, pAccept < 1.0

        assertThat(h.condition().get()).as("forced defer → skip tick").isFalse();
        assertThat(h.pendingDecision().get()).as("nothing parked on defer").isEmpty();
        assertThat(h.strategy.decideInvocations)
                .as("AD-R1: deferred tick runs BEFORE decideBet — strategy untouched")
                .isZero();
    }

    /* ---- linear (k=1): accept rate rises with elapsed fraction ---- */

    @Test
    @DisplayName("Linear (k=1): accept rate rises monotonically with elapsed fraction")
    void linearAcceptRateRisesWithElapsedFraction() throws Exception {
        double rateEarly = acceptRate(1.0, /*x*/ 0.1, 200_000);
        double rateMid = acceptRate(1.0, 0.5, 200_000);
        double rateLate = acceptRate(1.0, 0.9, 200_000);

        // linear pAccept = 0.15 + 0.85*x → 0.235 / 0.575 / 0.915
        assertThat(rateEarly).isLessThan(rateMid);
        assertThat(rateMid).isLessThan(rateLate);
        assertThat(rateEarly).isCloseTo(0.15 + 0.85 * 0.1, org.assertj.core.data.Offset.offset(0.01));
        assertThat(rateMid).isCloseTo(0.15 + 0.85 * 0.5, org.assertj.core.data.Offset.offset(0.01));
        assertThat(rateLate).isCloseTo(0.15 + 0.85 * 0.9, org.assertj.core.data.Offset.offset(0.01));
    }

    /* ---- k>1 back-loads more than k=1 ---- */

    @Test
    @DisplayName("k>1 back-loads: at x=0.5 the accept rate is lower than linear (bets pile later)")
    void higherShapeBackLoadsMoreThanLinear() throws Exception {
        double linearAtHalf = acceptRate(1.0, 0.5, 200_000);
        double backLoadedAtHalf = acceptRate(3.0, 0.5, 200_000);

        assertThat(backLoadedAtHalf)
                .as("k=3 defers more of the early/mid window than k=1")
                .isLessThan(linearAtHalf);
        // k=3 at x=0.5: 0.15 + 0.85*0.125 = 0.25625
        assertThat(backLoadedAtHalf)
                .isCloseTo(0.15 + 0.85 * Math.pow(0.5, 3.0), org.assertj.core.data.Offset.offset(0.01));
    }

    /* ---- measure the empirical accept rate at a fixed elapsed fraction ---- */

    private double acceptRate(double shape, double elapsedFraction, int ticks) throws Exception {
        Harness h = new Harness(rampBehavior(true, shape), new Random(424242L + (long) (shape * 31)));
        long window = 10_000L;
        long remaining = Math.round((1.0 - elapsedFraction) * window);
        h.openRound(9000L + (long) (shape * 1000), window, remaining);
        // Strategy always offers a decision so an accepted tick returns true.
        h.strategy.nextDecision = Optional.of(new BetDecision(0, 100L));

        Supplier<Boolean> condition = h.condition();
        int accepts = 0;
        for (int i = 0; i < ticks; i++) {
            h.remainingTime().set(remaining); // hold elapsed fraction fixed
            if (condition.get()) accepts++;
        }
        return accepts / (double) ticks;
    }

    private static BotBehaviorConfig rampBehavior(boolean enabled, double shape) {
        return BotBehaviorConfig.builder()
                .minBet(100).maxBet(1000).betIncrement(100)
                .maxTotalBetPerRound(10_000).minBetsPerRound(1).maxBetsPerRound(1_000_000)
                .chatEnabled(false).autoDepositEnabled(false).betSkipPercentage(0)
                .rampEnabled(enabled).rampShape(shape)
                .build();
    }

    /* ---- Random that counts nextDouble() draws (proves the off-path no-draw) ---- */
    private static final class CountingRandom extends Random {
        int doubleDraws = 0;

        @Override
        public double nextDouble() {
            doubleDraws++;
            return super.nextDouble();
        }
    }

    /** Strategy that returns whatever decision the test parks in {@link #nextDecision}. */
    private static final class ControlledStrategy implements BettingStrategy {
        Optional<BetDecision> nextDecision = Optional.of(new BetDecision(0, 100L));
        int decideInvocations = 0;
        final List<RoundResult> rounds = new ArrayList<>();

        @Override
        public Optional<BetDecision> decide(BetContext ctx) {
            decideInvocations++;
            return nextDecision;
        }

        @Override
        public void onRoundEnd(RoundResult result) {
            rounds.add(result);
        }
    }

    /** Reflection harness building a real bot and driving betCondition(). */
    private final class Harness {
        final BettingMiniGameBot bot;
        final ControlledStrategy strategy;

        Harness(BotBehaviorConfig behavior, Random rng) throws Exception {
            Map<Integer, Integer> affinities = new LinkedHashMap<>();
            for (int i = 0; i < 4; i++) affinities.put(i, 1);
            Game game = Game.builder()
                    .id("g-ramp").name("BauCua").pluginName("BauCua")
                    .offset(2000).optionAffinities(affinities).build();
            BotConfiguration cfg = BotConfiguration.builder()
                    .credentials(BotCredentials.builder().username("rampbot").password("pw").fingerprint("fp").build())
                    .environmentId("env-1").botGroupId("group-1").botIndex(1)
                    .game(game).behaviorConfig(behavior)
                    .zoneName("MiniGame3").timeoutMillis(60_000L)
                    .watchdogTimeoutSeconds(120L)
                    .strategyId(StrategyId.RANDOM)
                    .build();

            strategy = new ControlledStrategy();
            BettingStrategyFactory factory = mock(BettingStrategyFactory.class);
            when(factory.create(StrategyId.RANDOM)).thenReturn(strategy);

            bot = new BettingMiniGameBot();
            bot.setClients(mock(ApiGatewayClient.class), mock(GameMsClient.class), mock(ClientFactory.class));
            bot.setConfiguration(cfg);
            bot.setStrategyFactory(factory);
            bot.setRandom(rng);
            bot.initializeSubclass();

            seedLong("lastFetchedBalance", 50_000_000L);
            seedAtomic("expectedCurrentBalance", 50_000_000L);
            harnesses.add(this);
        }

        void openRound(long sid, long timeForBetting, long remaining) throws Exception {
            StartGameMessage msg = mock(StartGameMessage.class);
            when(msg.getSessionId()).thenReturn(sid);
            ActionResponseMessage<StartGameMessage> resp =
                    new ActionResponseMessage<>(MessageCategory.ACTION_RESPONSE, msg);
            Method m = BettingMiniGameBot.class.getDeclaredMethod("onStartGame", ActionResponseMessage.class);
            m.setAccessible(true);
            m.invoke(bot, resp);

            setField("gameState", BettingMiniGameState.BET);
            setField("timeForBetting", timeForBetting);
            // Disable the late-bet cutoff so canBet() reflects only session +
            // BET phase; the ramp gate is what we exercise here, not the
            // blockBetTime countdown (default 3000ms would otherwise mask the
            // late window we sweep).
            setField("blockBetTime", 0L);
            remainingTime().set(remaining);
            ((SessionIdStore) readField("sidStore")).set(sid);
        }

        @SuppressWarnings("unchecked")
        Supplier<Boolean> condition() throws Exception {
            Method m = BettingMiniGameBot.class.getDeclaredMethod("betCondition");
            m.setAccessible(true);
            return (Supplier<Boolean>) m.invoke(bot);
        }

        @SuppressWarnings("unchecked")
        AtomicReference<Optional<BetDecision>> pendingDecision() throws Exception {
            Field f = BettingMiniGameBot.class.getDeclaredField("pendingDecision");
            f.setAccessible(true);
            return (AtomicReference<Optional<BetDecision>>) f.get(bot);
        }

        AtomicLong remainingTime() throws Exception {
            return (AtomicLong) readField("remainingTime");
        }

        Object readField(String name) throws Exception {
            Field f;
            try {
                f = BettingMiniGameBot.class.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                f = Bot.class.getDeclaredField(name);
            }
            f.setAccessible(true);
            return f.get(bot);
        }

        void setField(String name, Object value) throws Exception {
            Field f;
            try {
                f = BettingMiniGameBot.class.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                f = Bot.class.getDeclaredField(name);
            }
            f.setAccessible(true);
            f.set(bot, value);
        }

        void seedLong(String name, long value) throws Exception {
            Field f = Bot.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setLong(bot, value);
        }

        void seedAtomic(String name, long value) throws Exception {
            Field f = Bot.class.getDeclaredField(name);
            f.setAccessible(true);
            ((AtomicLong) f.get(bot)).set(value);
        }

        void shutdown() throws Exception {
            ScheduledExecutorService w = (ScheduledExecutorService) readField("watchdogScheduler");
            if (w != null) w.shutdownNow();
            ScheduledExecutorService s = (ScheduledExecutorService) readField("scheduler");
            if (s != null) s.shutdownNow();
        }
    }

    private final List<Harness> harnesses = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (Harness h : harnesses) h.shutdown();
    }
}
