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
 * <p>Pins the Fibonacci progression (Architecture Decision A5d):
 * <ul>
 *   <li>Loss advances {@code fibIndex} by one and sets
 *       {@code currentBet = minBet * fib(fibIndex)}.</li>
 *   <li>Win retreats {@code fibIndex} by two (floored at 0) and recomputes
 *       {@code currentBet}.</li>
 *   <li>No-bet round leaves both {@code fibIndex} and {@code currentBet}
 *       unchanged.</li>
 *   <li>Push ({@code balanceDelta == 0} with non-empty bets) is treated as a
 *       loss — A5e.</li>
 *   <li>Cap-hit reset (A5f) resets {@code fibIndex} to 0 via
 *       {@link FibonacciStrategy#onCapHitReset()}.</li>
 *   <li>Overflow guard (A5d): {@code minBet * fib(fibIndex)} overflow surfaces
 *       a {@link Long#MAX_VALUE} sentinel that trips the cap-hit reset path,
 *       leaving {@code fibIndex = 0} and {@code currentBet = minBet}.</li>
 * </ul>
 *
 * <p>Mirrors the harness pattern in
 * {@link ClassicMartingaleStrategyTest} / {@link ParoliStrategyTest}.
 */
@DisplayName("FibonacciStrategy")
class FibonacciStrategyTest {

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

    private static BetDecision primeBoundsCache(FibonacciStrategy strategy,
                                                BotMemory mem,
                                                BotBehaviorConfig beh,
                                                Game game) {
        return strategy.decide(ctx(mem, beh, game, new Random(1L))).orElseThrow();
    }

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

    private static RoundResult noBetResult(long sessionId) {
        return new RoundResult(
                sessionId, Optional.empty(), Map.of(), 0L, 0L, Instant.now());
    }

    @Nested
    @DisplayName("Fibonacci helper")
    class FibHelper {

        @Test
        @DisplayName("fib(0..7) returns the expected Fibonacci sequence (1,1,2,3,5,8,13,21)")
        void fibSequenceMatches() {
            long[] expected = {1, 1, 2, 3, 5, 8, 13, 21};
            for (int i = 0; i < expected.length; i++) {
                assertThat(FibonacciStrategy.fib(i))
                        .as("fib(%d)", i)
                        .isEqualTo(expected[i]);
            }
        }
    }

    @Nested
    @DisplayName("Loss progression")
    class LossProgression {

        @Test
        @DisplayName("Pure loss streak walks the Fibonacci sequence")
        void pureLossStreakAdvancesFibIndex() {
            Game game = gameWithOptions(4);
            // minBet=10, step=10 keeps every fib(i)*10 aligned without shift.
            BotBehaviorConfig beh = behavior(10L, 1_000_000L, 10L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            FibonacciCautious strategy = new FibonacciCautious();
            primeBoundsCache(strategy, mem, beh, game);
            assertThat(strategy.getCurrentBet()).isEqualTo(10L);
            assertThat(strategy.getFibIndex()).isZero();

            // L → idx=1, fib(1)=1, currentBet=10.
            strategy.onRoundEnd(result(1L, 10L, 0L));
            assertThat(strategy.getFibIndex()).isEqualTo(1);
            assertThat(strategy.getCurrentBet()).isEqualTo(10L);

            // L → idx=2, fib(2)=2, currentBet=20.
            strategy.onRoundEnd(result(2L, 10L, 0L));
            assertThat(strategy.getFibIndex()).isEqualTo(2);
            assertThat(strategy.getCurrentBet()).isEqualTo(20L);

            // L → idx=3, fib(3)=3, currentBet=30.
            strategy.onRoundEnd(result(3L, 20L, 0L));
            assertThat(strategy.getFibIndex()).isEqualTo(3);
            assertThat(strategy.getCurrentBet()).isEqualTo(30L);

            // L → idx=4, fib(4)=5, currentBet=50.
            strategy.onRoundEnd(result(4L, 30L, 0L));
            assertThat(strategy.getFibIndex()).isEqualTo(4);
            assertThat(strategy.getCurrentBet()).isEqualTo(50L);

            // L → idx=5, fib(5)=8, currentBet=80.
            strategy.onRoundEnd(result(5L, 50L, 0L));
            assertThat(strategy.getFibIndex()).isEqualTo(5);
            assertThat(strategy.getCurrentBet()).isEqualTo(80L);
        }

        @Test
        @DisplayName("Push (delta == 0 with non-empty bets) treated as loss → fibIndex advances")
        void pushTreatedAsLoss() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(10L, 1_000_000L, 10L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            FibonacciAggressive strategy = new FibonacciAggressive();
            primeBoundsCache(strategy, mem, beh, game);

            // Bet 10, payout 10 → delta = 0 with non-empty bets.
            strategy.onRoundEnd(result(1L, 10L, 10L));
            assertThat(strategy.getFibIndex()).isEqualTo(1);
            assertThat(strategy.getCurrentBet()).isEqualTo(10L);

            // Another push → fibIndex=2.
            strategy.onRoundEnd(result(2L, 10L, 10L));
            assertThat(strategy.getFibIndex()).isEqualTo(2);
            assertThat(strategy.getCurrentBet()).isEqualTo(20L);
        }
    }

    @Nested
    @DisplayName("Win progression")
    class WinProgression {

        @Test
        @DisplayName("Win after 4 losses retreats two indices (idx 4 → 2)")
        void winRetreatsTwoIndices() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(10L, 1_000_000L, 10L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            FibonacciCautious strategy = new FibonacciCautious();
            primeBoundsCache(strategy, mem, beh, game);

            // L, L, L, L → idx=4, currentBet=50.
            strategy.onRoundEnd(result(1L, 10L, 0L));
            strategy.onRoundEnd(result(2L, 10L, 0L));
            strategy.onRoundEnd(result(3L, 20L, 0L));
            strategy.onRoundEnd(result(4L, 30L, 0L));
            assertThat(strategy.getFibIndex()).isEqualTo(4);
            assertThat(strategy.getCurrentBet()).isEqualTo(50L);

            // W → idx=max(0, 4-2)=2, fib(2)=2, currentBet=20.
            strategy.onRoundEnd(result(5L, 50L, 1_000L));
            assertThat(strategy.getFibIndex()).isEqualTo(2);
            assertThat(strategy.getCurrentBet()).isEqualTo(20L);
        }

        @Test
        @DisplayName("Win at low index floors at 0 (idx=1 → 0, then stays 0)")
        void winFloorsAtZero() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(10L, 1_000_000L, 10L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            FibonacciCautious strategy = new FibonacciCautious();
            primeBoundsCache(strategy, mem, beh, game);

            // L → idx=1, currentBet=10.
            strategy.onRoundEnd(result(1L, 10L, 0L));
            assertThat(strategy.getFibIndex()).isEqualTo(1);

            // W → idx=max(0, 1-2)=0, currentBet=10*fib(0)=10.
            strategy.onRoundEnd(result(2L, 10L, 100L));
            assertThat(strategy.getFibIndex()).isZero();
            assertThat(strategy.getCurrentBet()).isEqualTo(10L);

            // W again → idx still 0, currentBet=10.
            strategy.onRoundEnd(result(3L, 10L, 100L));
            assertThat(strategy.getFibIndex()).isZero();
            assertThat(strategy.getCurrentBet()).isEqualTo(10L);
        }
    }

    @Nested
    @DisplayName("No-bet round")
    class NoBet {

        @Test
        @DisplayName("No-bet round preserves fibIndex and currentBet")
        void noBetPreservesFibIndex() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(10L, 1_000_000L, 10L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            FibonacciAggressive strategy = new FibonacciAggressive();
            primeBoundsCache(strategy, mem, beh, game);

            // L, L → idx=2, currentBet=20.
            strategy.onRoundEnd(result(1L, 10L, 0L));
            strategy.onRoundEnd(result(2L, 10L, 0L));
            assertThat(strategy.getFibIndex()).isEqualTo(2);
            assertThat(strategy.getCurrentBet()).isEqualTo(20L);

            // No-bet → unchanged.
            strategy.onRoundEnd(noBetResult(3L));
            assertThat(strategy.getFibIndex()).isEqualTo(2);
            assertThat(strategy.getCurrentBet()).isEqualTo(20L);

            // L → idx=3, currentBet=30. Counter survived the no-bet.
            strategy.onRoundEnd(result(4L, 20L, 0L));
            assertThat(strategy.getFibIndex()).isEqualTo(3);
            assertThat(strategy.getCurrentBet()).isEqualTo(30L);
        }
    }

    @Nested
    @DisplayName("Clamp / align / reset")
    class ClampAlignReset {

        @Test
        @DisplayName("Cap-hit reset clears fibIndex and resets currentBet to minBet")
        void capHitClearsFibIndex() {
            Game game = gameWithOptions(4);
            // minBet=10, maxBet=25, step=10. Third loss targets fib(3)=3 →
            // 10*3 = 30 > maxBet=25 → cap-hit reset.
            BotBehaviorConfig beh = behavior(10L, 25L, 10L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            FibonacciCautious strategy = new FibonacciCautious();
            primeBoundsCache(strategy, mem, beh, game);

            // L → idx=1, currentBet=10 (within cap).
            strategy.onRoundEnd(result(1L, 10L, 0L));
            assertThat(strategy.getFibIndex()).isEqualTo(1);
            assertThat(strategy.getCurrentBet()).isEqualTo(10L);

            // L → idx=2, currentBet=20 (within cap).
            strategy.onRoundEnd(result(2L, 10L, 0L));
            assertThat(strategy.getFibIndex()).isEqualTo(2);
            assertThat(strategy.getCurrentBet()).isEqualTo(20L);

            // L → idx would advance to 3, raw target 30 > maxBet=25 → cap-hit.
            // onCapHitReset clears fibIndex to 0; currentBet returns to minBet.
            strategy.onRoundEnd(result(3L, 20L, 0L));
            assertThat(strategy.getFibIndex()).isZero();
            assertThat(strategy.getCurrentBet()).isEqualTo(10L);
        }

        @Test
        @DisplayName("Overflow guard: fib(64) * minBet overflows long → cap-hit reset (no crash)")
        void overflowGuardResetsCleanly() {
            Game game = gameWithOptions(4);
            // minBet * fib(64) overflows for minBet = 10^9 (10^9 * 1.06e13 = 1.06e22).
            // maxBet = 10^14 sits above any non-overflowed aligned bet up to
            // fib(63), so the only way to trip the reset is via the overflow
            // sentinel returned by computeBet().
            BotBehaviorConfig beh = behavior(1_000_000_000L, 100_000_000_000_000L, 1L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000_000_000L);

            FibonacciCautious strategy = new FibonacciCautious();
            primeBoundsCache(strategy, mem, beh, game);

            // Fast-forward to fibIndex=63 via the test-only setter — drives
            // the next loss to fibIndex=64 where the multiply overflows.
            strategy.setFibIndexForTest(63);

            // L → idx=64; computeBet(64) overflows → Long.MAX_VALUE sentinel
            // → applyClampAlignReset trips cap-hit → onCapHitReset clears
            // fibIndex to 0; currentBet returns to minBet.
            strategy.onRoundEnd(result(1L, 1_000_000_000L, 0L));
            assertThat(strategy.getFibIndex()).isZero();
            assertThat(strategy.getCurrentBet()).isEqualTo(1_000_000_000L);
        }
    }
}
