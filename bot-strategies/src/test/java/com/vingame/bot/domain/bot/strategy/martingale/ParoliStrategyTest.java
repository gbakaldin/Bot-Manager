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
 * <p>Pins the Paroli progression (Architecture Decision A5b):
 * <ul>
 *   <li>Win doubles {@code currentBet} and increments the streak counter.</li>
 *   <li>Hitting the locked-in {@code STREAK_CAP = 3} banks: resets
 *       {@code currentBet} to {@code minBet} and the counter to 0.</li>
 *   <li>Loss resets {@code currentBet} to {@code minBet} and the counter to 0.</li>
 *   <li>Push ({@code balanceDelta == 0} with non-empty bets) is treated as a
 *       loss — A5e.</li>
 *   <li>No-bet round leaves both {@code currentBet} and the streak counter
 *       unchanged.</li>
 *   <li>Cap-hit reset (Architecture Decision A5f) also clears the streak
 *       counter via {@code onCapHitReset}.</li>
 * </ul>
 *
 * <p>Mirrors the harness pattern in
 * {@link com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategyTest}.
 */
@DisplayName("ParoliStrategy")
class ParoliStrategyTest {

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

    private static BetDecision primeBoundsCache(ParoliStrategy strategy,
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
    @DisplayName("Win streak")
    class WinStreak {

        @Test
        @DisplayName("Win streak below cap (W, W) doubles each round")
        void winStreakBelowCapDoubles() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 100L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            ParoliCautious strategy = new ParoliCautious();
            primeBoundsCache(strategy, mem, beh, game);
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);
            assertThat(strategy.getConsecutiveWins()).isZero();

            // W → currentBet = 200, streak = 1.
            strategy.onRoundEnd(result(1L, 100L, 500L));
            assertThat(strategy.getCurrentBet()).isEqualTo(200L);
            assertThat(strategy.getConsecutiveWins()).isEqualTo(1);

            // W → currentBet = 400, streak = 2.
            strategy.onRoundEnd(result(2L, 200L, 1_000L));
            assertThat(strategy.getCurrentBet()).isEqualTo(400L);
            assertThat(strategy.getConsecutiveWins()).isEqualTo(2);
        }

        @Test
        @DisplayName("Hitting STREAK_CAP=3 banks: reset to minBet, counter to 0")
        void streakCapBanks() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 100L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            ParoliAggressive strategy = new ParoliAggressive();
            primeBoundsCache(strategy, mem, beh, game);

            // W, W → streak = 2, currentBet = 400.
            strategy.onRoundEnd(result(1L, 100L, 500L));
            strategy.onRoundEnd(result(2L, 200L, 1_000L));
            assertThat(strategy.getCurrentBet()).isEqualTo(400L);
            assertThat(strategy.getConsecutiveWins()).isEqualTo(2);

            // Third W hits STREAK_CAP=3 → bank: currentBet = minBet, streak = 0.
            strategy.onRoundEnd(result(3L, 400L, 2_000L));
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);
            assertThat(strategy.getConsecutiveWins()).isZero();
        }
    }

    @Nested
    @DisplayName("Loss handling")
    class LossHandling {

        @Test
        @DisplayName("Loss mid-streak resets currentBet and counter")
        void lossMidStreakResets() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 100L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            ParoliCautious strategy = new ParoliCautious();
            primeBoundsCache(strategy, mem, beh, game);

            // W, W → streak = 2, currentBet = 400.
            strategy.onRoundEnd(result(1L, 100L, 500L));
            strategy.onRoundEnd(result(2L, 200L, 1_000L));
            assertThat(strategy.getConsecutiveWins()).isEqualTo(2);

            // L → currentBet = minBet, streak = 0.
            strategy.onRoundEnd(result(3L, 400L, 0L));
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);
            assertThat(strategy.getConsecutiveWins()).isZero();
        }

        @Test
        @DisplayName("Push (delta == 0 with non-empty bets) treated as loss → reset")
        void pushTreatedAsLoss() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 100L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            ParoliCautious strategy = new ParoliCautious();
            primeBoundsCache(strategy, mem, beh, game);

            // W → streak = 1, currentBet = 200.
            strategy.onRoundEnd(result(1L, 100L, 500L));
            assertThat(strategy.getConsecutiveWins()).isEqualTo(1);

            // Push (bet 200, payout 200) → treated as loss → reset.
            strategy.onRoundEnd(result(2L, 200L, 200L));
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);
            assertThat(strategy.getConsecutiveWins()).isZero();
        }
    }

    @Nested
    @DisplayName("No-bet handling")
    class NoBetHandling {

        @Test
        @DisplayName("No-bet round preserves streak and currentBet")
        void noBetPreservesStreak() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 100_000L, 100L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            ParoliAggressive strategy = new ParoliAggressive();
            primeBoundsCache(strategy, mem, beh, game);

            // W → streak = 1, currentBet = 200.
            strategy.onRoundEnd(result(1L, 100L, 500L));
            long currentBet = strategy.getCurrentBet();
            int currentStreak = strategy.getConsecutiveWins();
            assertThat(currentBet).isEqualTo(200L);
            assertThat(currentStreak).isEqualTo(1);

            // No-bet → unchanged.
            strategy.onRoundEnd(noBetResult(2L));
            assertThat(strategy.getCurrentBet()).isEqualTo(currentBet);
            assertThat(strategy.getConsecutiveWins()).isEqualTo(currentStreak);

            // W → streak = 2, currentBet = 400. Streak survived the no-bet.
            strategy.onRoundEnd(result(3L, 200L, 1_000L));
            assertThat(strategy.getCurrentBet()).isEqualTo(400L);
            assertThat(strategy.getConsecutiveWins()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Cap reset")
    class CapReset {

        @Test
        @DisplayName("Cap-hit reset also clears streak counter")
        void capResetClearsStreak() {
            Game game = gameWithOptions(4);
            // minBet=100, maxBet=300, betIncrement=100. STREAK_CAP not hit
            // (need 3 wins in a row, but second double already exceeds cap).
            BotBehaviorConfig beh = behavior(100L, 300L, 100L);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            ParoliCautious strategy = new ParoliCautious();
            primeBoundsCache(strategy, mem, beh, game);

            // W → streak = 1, currentBet = 200 (within cap).
            strategy.onRoundEnd(result(1L, 100L, 500L));
            assertThat(strategy.getCurrentBet()).isEqualTo(200L);
            assertThat(strategy.getConsecutiveWins()).isEqualTo(1);

            // W → streak would be 2, doubled raw target = 400 > maxBet=300
            // → cap-hit reset: currentBet = minBet, streak cleared to 0.
            strategy.onRoundEnd(result(2L, 200L, 1_000L));
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);
            assertThat(strategy.getConsecutiveWins()).isZero();
        }
    }
}
