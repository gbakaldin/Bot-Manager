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
 * Phase 3 of {@code docs/plans/MARTINGALE_STRATEGIES.md}.
 *
 * <p>Pins the D'Alembert progression (Architecture Decision A5c):
 * <ul>
 *   <li>Loss raises {@code currentBet} by {@code betIncrement}.</li>
 *   <li>Win lowers {@code currentBet} by {@code betIncrement}, floored at
 *       {@code minBet}.</li>
 *   <li>No-bet round leaves {@code currentBet} unchanged.</li>
 *   <li>Push ({@code balanceDelta == 0} with non-empty bets) is treated as a
 *       loss — A5e.</li>
 *   <li>Cap-hit reset (A5f) resets {@code currentBet} to {@code minBet} when
 *       the post-loss target exceeds {@code maxBet}.</li>
 * </ul>
 *
 * <p>Mirrors the harness pattern in
 * {@link ClassicMartingaleStrategyTest} / {@link ParoliStrategyTest}.
 */
@DisplayName("DAlembertStrategy")
class DAlembertStrategyTest {

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
    private static BetDecision primeBoundsCache(DAlembertStrategy strategy,
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
        @DisplayName("Pure loss streak ramps linearly by betIncrement")
        void pureLossStreakLinearRamp() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 50L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            DAlembertCautious strategy = new DAlembertCautious();
            primeBoundsCache(strategy, mem, beh, game);
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);

            // L → 100 + 50 = 150, L → 150 + 50 = 200, L → 200 + 50 = 250.
            long[] expected = {150L, 200L, 250L};
            long sid = 1L;
            for (long want : expected) {
                strategy.onRoundEnd(result(sid, strategy.getCurrentBet(), 0L));
                assertThat(strategy.getCurrentBet()).isEqualTo(want);
                sid++;
            }
        }

        @Test
        @DisplayName("Push (delta == 0 with non-empty bets) is treated as loss → +betIncrement")
        void pushTreatedAsLoss() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 50L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            DAlembertAggressive strategy = new DAlembertAggressive();
            primeBoundsCache(strategy, mem, beh, game);

            // Bet 100, payout 100 → delta = 0 with non-empty bets.
            strategy.onRoundEnd(result(1L, 100L, 100L));
            assertThat(strategy.getCurrentBet()).isEqualTo(150L);

            strategy.onRoundEnd(result(2L, 150L, 150L));
            assertThat(strategy.getCurrentBet()).isEqualTo(200L);
        }
    }

    @Nested
    @DisplayName("Win progression")
    class WinProgression {

        @Test
        @DisplayName("Mixed loss/win sequence: linear up then linear down")
        void mixedLossWinSequence() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 50L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            DAlembertCautious strategy = new DAlembertCautious();
            primeBoundsCache(strategy, mem, beh, game);
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);

            // L, L, L → 150, 200, 250.
            strategy.onRoundEnd(result(1L, 100L, 0L));
            assertThat(strategy.getCurrentBet()).isEqualTo(150L);
            strategy.onRoundEnd(result(2L, 150L, 0L));
            assertThat(strategy.getCurrentBet()).isEqualTo(200L);
            strategy.onRoundEnd(result(3L, 200L, 0L));
            assertThat(strategy.getCurrentBet()).isEqualTo(250L);

            // W, W → 200, 150.
            strategy.onRoundEnd(result(4L, 250L, 1_000L));
            assertThat(strategy.getCurrentBet()).isEqualTo(200L);
            strategy.onRoundEnd(result(5L, 200L, 1_000L));
            assertThat(strategy.getCurrentBet()).isEqualTo(150L);
        }

        @Test
        @DisplayName("Win at minBet floors at minBet (does not go below)")
        void winAtMinBetStaysMinBet() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 50L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            DAlembertCautious strategy = new DAlembertCautious();
            primeBoundsCache(strategy, mem, beh, game);
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);

            // W, W, W from minBet → stays at minBet (raw target 50 floored to 100).
            for (long sid = 1L; sid <= 3L; sid++) {
                strategy.onRoundEnd(result(sid, 100L, 500L));
                assertThat(strategy.getCurrentBet()).isEqualTo(100L);
            }
        }
    }

    @Nested
    @DisplayName("No-bet round")
    class NoBet {

        @Test
        @DisplayName("No-bet round leaves currentBet unchanged")
        void noBetUnchanged() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 50L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            DAlembertAggressive strategy = new DAlembertAggressive();
            primeBoundsCache(strategy, mem, beh, game);

            // L, L → currentBet 200.
            strategy.onRoundEnd(result(1L, 100L, 0L));
            strategy.onRoundEnd(result(2L, 150L, 0L));
            long before = strategy.getCurrentBet();
            assertThat(before).isEqualTo(200L);

            // No-bet round → unchanged.
            strategy.onRoundEnd(noBetResult(3L));
            assertThat(strategy.getCurrentBet()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("Clamp / align / reset")
    class ClampAlignReset {

        @Test
        @DisplayName("Cap-hit reset: linear ramp exceeds maxBet → reset to minBet")
        void capHitResetsToMinBet() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 300L, 100L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            DAlembertCautious strategy = new DAlembertCautious();
            primeBoundsCache(strategy, mem, beh, game);

            // L → 200 (within cap).
            strategy.onRoundEnd(result(1L, 100L, 0L));
            assertThat(strategy.getCurrentBet()).isEqualTo(200L);

            // L → 300 (equal to cap — still within).
            strategy.onRoundEnd(result(2L, 200L, 0L));
            assertThat(strategy.getCurrentBet()).isEqualTo(300L);

            // L → raw 400 > maxBet=300 → reset to minBet=100.
            strategy.onRoundEnd(result(3L, 300L, 0L));
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);
        }
    }
}
