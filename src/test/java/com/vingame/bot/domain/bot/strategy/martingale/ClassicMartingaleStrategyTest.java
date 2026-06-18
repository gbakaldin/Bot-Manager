package com.vingame.bot.domain.bot.strategy.martingale;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.domain.bot.strategy.BetContext;
import com.vingame.bot.domain.bot.strategy.BetDecision;
import com.vingame.bot.domain.bot.strategy.BotMemory;
import com.vingame.bot.domain.bot.strategy.RoundResult;
import com.vingame.bot.domain.game.model.Game;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 of {@code docs/plans/MARTINGALE_STRATEGIES.md}.
 *
 * <p>Pins the Classic Martingale progression (Architecture Decision A5a):
 * <ul>
 *   <li>Loss doubles {@code currentBet}.</li>
 *   <li>Win resets to {@code minBet}.</li>
 *   <li>No-bet round leaves {@code currentBet} unchanged.</li>
 *   <li>Push ({@code balanceDelta == 0} with non-empty bets) is treated as a
 *       loss — A5e.</li>
 *   <li>The clamp / align / reset rules (A5f) trigger when the doubled target
 *       exceeds {@code maxBet}: reset to {@code minBet}.</li>
 *   <li>Alignment rounds the raw target down to the nearest
 *       {@code minBet + k*betIncrement} step.</li>
 *   <li>Doubling overflow surfaces as a cap-hit reset rather than a crash.</li>
 * </ul>
 *
 * <p>Mirrors the harness pattern in
 * {@link com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategyTest}:
 * hand-rolled {@link BotMemory} + {@link BetContext} factories, seeded
 * {@link Random}, per-round boundary driven by
 * {@code mem.completeRound(...)} + {@code mem.beginRound(...)}.
 */
@DisplayName("ClassicMartingaleStrategy")
class ClassicMartingaleStrategyTest {

    private static Game gameWithOptions(int n) {
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) affinities.put(i, 1);
        return Game.builder()
                .id("g1").name("BauCua").pluginName("BauCua")
                .offset(2000).optionAffinities(affinities).build();
    }

    private static BotBehaviorConfig behavior(long minBet, long maxBet, long step) {
        return BotBehaviorConfig.builder()
                .minBet(minBet).maxBet(maxBet).betIncrement(step)
                .maxBetsPerRound(100).betSkipPercentage(0)
                .build();
    }

    private static BetContext ctx(BotMemory mem, BotBehaviorConfig beh, Game g, Random rng) {
        return new BetContext(mem, beh, g, mem.getCurrentBalance(),
                mem.getCurrentRound(), rng);
    }

    /**
     * Force the strategy to cache its behavior bounds (minBet / maxBet /
     * betIncrement). The base class lazy-initialises these on the first
     * {@code decide()} call — see
     * {@code MartingaleStrategySupport} javadoc and Implementation Note 1.
     */
    private static BetDecision primeBoundsCache(ClassicMartingaleStrategy strategy,
                                                 BotMemory mem,
                                                 BotBehaviorConfig beh,
                                                 Game game) {
        return strategy.decide(ctx(mem, beh, game, new Random(1L))).orElseThrow();
    }

    /** Build a loss/win/push RoundResult for a single-option bet. */
    private static RoundResult result(long sessionId, long staked, long payout) {
        Map<Integer, Long> bets = staked == 0L ? Map.of() : Map.of(0, staked);
        return new RoundResult(
                sessionId,
                Optional.empty(),
                bets,
                payout,
                payout - staked,
                Instant.now());
    }

    /** Empty-bets RoundResult — represents a round the bot skipped. */
    private static RoundResult noBetResult(long sessionId) {
        return new RoundResult(
                sessionId, Optional.empty(), Map.of(), 0L, 0L, Instant.now());
    }

    @Nested
    @DisplayName("Loss progression")
    class LossProgression {

        @Test
        @DisplayName("Pure loss streak doubles currentBet on each round")
        void pureLossStreakDoubles() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 100L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            ClassicMartingaleCautious strategy = new ClassicMartingaleCautious();
            primeBoundsCache(strategy, mem, beh, game);
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);

            long[] expected = {200L, 400L, 800L, 1_600L, 3_200L};
            long sid = 1L;
            for (long want : expected) {
                strategy.onRoundEnd(result(sid, strategy.getCurrentBet(), 0L));
                assertThat(strategy.getCurrentBet()).isEqualTo(want);
                sid++;
            }
        }

        @Test
        @DisplayName("Push (delta == 0 with non-empty bets) is treated as loss → currentBet doubles")
        void pushTreatedAsLoss() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 100L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            ClassicMartingaleAggressive strategy = new ClassicMartingaleAggressive();
            primeBoundsCache(strategy, mem, beh, game);

            // Bet 100, payout 100 → delta = 0 with non-empty bets.
            strategy.onRoundEnd(result(1L, 100L, 100L));
            assertThat(strategy.getCurrentBet()).isEqualTo(200L);

            strategy.onRoundEnd(result(2L, 200L, 200L));
            assertThat(strategy.getCurrentBet()).isEqualTo(400L);
        }
    }

    @Nested
    @DisplayName("Win progression")
    class WinProgression {

        @Test
        @DisplayName("Win after losses resets to minBet")
        void winResetsToMinBet() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 100L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            ClassicMartingaleCautious strategy = new ClassicMartingaleCautious();
            primeBoundsCache(strategy, mem, beh, game);

            // L, L → currentBet 400.
            strategy.onRoundEnd(result(1L, 100L, 0L));
            strategy.onRoundEnd(result(2L, 200L, 0L));
            assertThat(strategy.getCurrentBet()).isEqualTo(400L);

            // W → reset to minBet.
            strategy.onRoundEnd(result(3L, 400L, 1000L));
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Win at minBet keeps currentBet at minBet")
        void winAtMinBetStaysMinBet() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 100L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            ClassicMartingaleCautious strategy = new ClassicMartingaleCautious();
            primeBoundsCache(strategy, mem, beh, game);

            strategy.onRoundEnd(result(1L, 100L, 500L));
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);
        }
    }

    @Nested
    @DisplayName("No-bet round")
    class NoBet {

        @Test
        @DisplayName("No-bet round leaves currentBet unchanged")
        void noBetUnchanged() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 100L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            ClassicMartingaleAggressive strategy = new ClassicMartingaleAggressive();
            primeBoundsCache(strategy, mem, beh, game);

            // L, L → currentBet 400.
            strategy.onRoundEnd(result(1L, 100L, 0L));
            strategy.onRoundEnd(result(2L, 200L, 0L));
            long before = strategy.getCurrentBet();
            assertThat(before).isEqualTo(400L);

            // No-bet round → unchanged.
            strategy.onRoundEnd(noBetResult(3L));
            assertThat(strategy.getCurrentBet()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("Clamp / align / reset")
    class ClampAlignReset {

        @Test
        @DisplayName("Cap-hit reset: doubled target exceeds maxBet → reset to minBet")
        void capHitResetsToMinBet() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 300L, 100L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            ClassicMartingaleCautious strategy = new ClassicMartingaleCautious();
            primeBoundsCache(strategy, mem, beh, game);

            // L → 200 (within cap).
            strategy.onRoundEnd(result(1L, 100L, 0L));
            assertThat(strategy.getCurrentBet()).isEqualTo(200L);

            // L → raw target 400 > maxBet=300 → reset to minBet=100.
            strategy.onRoundEnd(result(2L, 200L, 0L));
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Alignment: raw target 200 with step 70 → aligned to 170 (minBet + 1*step)")
        void alignmentRoundsDownToStep() {
            Game game = gameWithOptions(4);
            // minBet=100, maxBet=10000, betIncrement=70. Doubled raw=200.
            // Aligned: minBet + ((200-100)/70)*70 = 100 + 1*70 = 170.
            BotBehaviorConfig beh = behavior(100L, 10_000L, 70L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            ClassicMartingaleCautious strategy = new ClassicMartingaleCautious();
            primeBoundsCache(strategy, mem, beh, game);

            strategy.onRoundEnd(result(1L, 100L, 0L));
            assertThat(strategy.getCurrentBet()).isEqualTo(170L);
        }

        @Test
        @DisplayName("Doubling overflow surfaces as cap-hit reset (no crash)")
        void doublingOverflowResets() {
            Game game = gameWithOptions(4);
            // minBet > Long.MAX_VALUE / 2 forces overflow on the very first
            // double — multiplyExact(minBet, 2) throws ArithmeticException
            // which the strategy maps to a Long.MAX_VALUE sentinel.
            long minBet = Long.MAX_VALUE / 2 + 1L;
            BotBehaviorConfig beh = behavior(minBet, Long.MAX_VALUE - 1L, 1L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            ClassicMartingaleCautious strategy = new ClassicMartingaleCautious();
            primeBoundsCache(strategy, mem, beh, game);
            assertThat(strategy.getCurrentBet()).isEqualTo(minBet);

            // L: doubling minBet overflows long. Sentinel Long.MAX_VALUE
            // surfaces; clamp path resets to minBet because
            // Long.MAX_VALUE > maxBet = Long.MAX_VALUE - 1.
            strategy.onRoundEnd(result(1L, minBet, 0L));
            assertThat(strategy.getCurrentBet()).isEqualTo(minBet);
        }
    }
}
