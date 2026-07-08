package com.vingame.bot.domain.bot.strategy;

import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.domain.game.model.Game;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 of {@code docs/plans/BETTING_STRATEGIES.md}.
 *
 * <p>Pins:
 * <ul>
 *   <li>RNG-consumption order: skip-check → bet-amount → option. The same
 *       seed fed through this strategy and through the legacy
 *       {@code BettingMiniGameBot.shouldBet()}+{@code resolveBetAmount()}+
 *       {@code resolveNextEntryToBet()} sequence yields byte-identical
 *       {@link BetDecision} output. This is the equivalence proof Phase 5
 *       relies on.</li>
 *   <li>Per-round bet-count gate against {@code behavior.maxBetsPerRound}.</li>
 *   <li>{@code betSkipPercentage = 0} → always bets (mirrors the
 *       latent-bug behavior documented in Findings; {@code RandomBehaviorStrategy}
 *       reproduces the live system bit-for-bit, including this).</li>
 *   <li>{@code betSkipPercentage = 100} → never bets.</li>
 *   <li>Per-round counter resets when {@code currentRound.sessionId} changes
 *       (StartGame boundary).</li>
 *   <li>{@link RandomBehaviorStrategy#onRoundEnd(RoundResult)} is a no-op (no
 *       observable mutation).</li>
 * </ul>
 */
@DisplayName("RandomBehaviorStrategy")
class RandomBehaviorStrategyTest {

    private static Game gameWithOptions(int n) {
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) affinities.put(i, 1);
        return Game.builder()
                .id("g1").name("BauCua").pluginName("BauCua")
                .offset(2000).optionAffinities(affinities).build();
    }

    private static BotBehaviorConfig behavior(long minBet, long maxBet, long step,
                                              int maxBetsPerRound, int skipPct) {
        return BotBehaviorConfig.builder()
                .minBet(minBet).maxBet(maxBet).betIncrement(step)
                .maxBetsPerRound(maxBetsPerRound).betSkipPercentage(skipPct)
                .build();
    }

    private static BetContext ctx(BotMemory mem, BotBehaviorConfig beh, Game g, Random rng) {
        return new BetContext(mem, beh, g, mem.getCurrentBalance(),
                mem.getCurrentRound(), rng);
    }

    @Nested
    @DisplayName("RNG-consumption equivalence with legacy BettingMiniGameBot")
    class Equivalence {

        /**
         * Bit-for-bit replay of the legacy bot decision pipeline:
         * {@code shouldBet()} consumes {@code nextInt(100)} for the skip check
         * (counter incremented on pass); {@code resolveBetAmount()} consumes
         * {@code nextInt(maxSteps+1)}; {@code resolveNextEntryToBet()} consumes
         * {@code nextInt(options.size())}.
         */
        private List<BetDecision> legacyReplay(Random rng,
                                                BotBehaviorConfig beh,
                                                Game game,
                                                int ticks,
                                                int maxBetsPerRound) {
            List<BetDecision> out = new ArrayList<>();
            int betsThisRound = 0;
            List<Integer> options = List.copyOf(game.getEffectiveOptionAffinities().keySet());
            for (int t = 0; t < ticks; t++) {
                if (betsThisRound >= maxBetsPerRound) continue;
                if (rng.nextInt(100) < beh.getBetSkipPercentage()) continue;
                betsThisRound++;

                int maxSteps = Math.toIntExact((beh.getMaxBet() - beh.getMinBet()) / beh.getBetIncrement());
                long steps = rng.nextInt(maxSteps + 1);
                long amount = beh.getMinBet() + steps * beh.getBetIncrement();

                int option = options.get(rng.nextInt(options.size()));
                out.add(new BetDecision(option, amount));
            }
            return out;
        }

        @Test
        @DisplayName("Same seed: strategy output == legacy replay (skip=0, 6 options, multiple ticks)")
        void equivalenceWithSkipZero() {
            Game game = gameWithOptions(6);
            BotBehaviorConfig beh = behavior(100L, 1000L, 100L, 5, 0);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(42L, 1_000_000L);

            long seed = 0xDEADBEEFL;
            int ticks = 5;

            List<BetDecision> legacy = legacyReplay(new Random(seed), beh, game, ticks, beh.getMaxBetsPerRound());

            RandomBehaviorStrategy strategy = new RandomBehaviorStrategy();
            Random stratRng = new Random(seed);
            List<BetDecision> actual = new ArrayList<>();
            for (int t = 0; t < ticks; t++) {
                strategy.decide(ctx(mem, beh, game, stratRng)).ifPresent(actual::add);
            }

            assertThat(actual).isEqualTo(legacy);
        }

        @Test
        @DisplayName("Same seed: strategy output == legacy replay (skip=40, mixed pattern)")
        void equivalenceWithSkipFraction() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(50L, 500L, 50L, 10, 40);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 5_000_000L);

            long seed = 12345L;
            int ticks = 20;

            List<BetDecision> legacy = legacyReplay(new Random(seed), beh, game, ticks, beh.getMaxBetsPerRound());

            RandomBehaviorStrategy strategy = new RandomBehaviorStrategy();
            Random stratRng = new Random(seed);
            List<BetDecision> actual = new ArrayList<>();
            for (int t = 0; t < ticks; t++) {
                strategy.decide(ctx(mem, beh, game, stratRng)).ifPresent(actual::add);
            }

            assertThat(actual).isEqualTo(legacy);
        }
    }

    @Nested
    @DisplayName("decide() — gating")
    class Gating {

        @Test
        @DisplayName("betSkipPercentage = 0: every tick (within maxBetsPerRound) produces a decision")
        void alwaysBetWhenSkipZero() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 200L, 100L, 100, 0);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            RandomBehaviorStrategy strategy = new RandomBehaviorStrategy();
            Random rng = new Random(7L);

            int decisions = 0;
            for (int t = 0; t < 50; t++) {
                if (strategy.decide(ctx(mem, beh, game, rng)).isPresent()) decisions++;
            }
            assertThat(decisions).isEqualTo(50);
        }

        @Test
        @DisplayName("betSkipPercentage = 100: never bets")
        void neverBetWhenSkipFull() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 200L, 100L, 100, 100);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            RandomBehaviorStrategy strategy = new RandomBehaviorStrategy();
            Random rng = new Random(7L);

            for (int t = 0; t < 50; t++) {
                assertThat(strategy.decide(ctx(mem, beh, game, rng))).isEmpty();
            }
        }

        @Test
        @DisplayName("maxBetsPerRound caps the number of decisions per round")
        void maxBetsPerRoundCap() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 200L, 100L, 3, 0);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            RandomBehaviorStrategy strategy = new RandomBehaviorStrategy();
            Random rng = new Random(7L);

            int decisions = 0;
            for (int t = 0; t < 50; t++) {
                if (strategy.decide(ctx(mem, beh, game, rng)).isPresent()) decisions++;
            }
            assertThat(decisions).isEqualTo(3);
        }

        @Test
        @DisplayName("effectiveMaxBetsPerRound (jackpot-scaled cap) governs the count, not behavior.maxBetsPerRound (JACKPOT_SCALE_AND_RAMP AD-J4)")
        void effectiveCapGovernsWhenScaledDown() {
            Game game = gameWithOptions(4);
            // Configured cap is 8; the jackpot-scaled effective cap is 2. The strategy
            // must honour the CONTEXT's effective cap (the lever), not the raw config.
            BotBehaviorConfig beh = behavior(100L, 200L, 100L, 8, 0);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            RandomBehaviorStrategy strategy = new RandomBehaviorStrategy();
            Random rng = new Random(7L);

            int decisions = 0;
            for (int t = 0; t < 50; t++) {
                // effectiveMaxBetsPerRound = 2 (< configured 8): the volume lever
                BetContext scaled = new BetContext(mem, beh, game, mem.getCurrentBalance(),
                        mem.getCurrentRound(), rng, /*effectiveMaxBetsPerRound*/ 2);
                if (strategy.decide(scaled).isPresent()) decisions++;
            }
            assertThat(decisions)
                    .as("scaled-down effective cap (2) caps the round below the configured max (8)")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("Per-round counter resets when sessionId changes")
        void counterResetsOnNewRound() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 200L, 100L, 2, 0);
            BotMemory mem = new BotMemory(game);

            RandomBehaviorStrategy strategy = new RandomBehaviorStrategy();
            Random rng = new Random(7L);

            // Round 1
            mem.beginRound(1L, 1_000_000L);
            int r1 = 0;
            for (int t = 0; t < 10; t++) {
                if (strategy.decide(ctx(mem, beh, game, rng)).isPresent()) r1++;
            }
            assertThat(r1).isEqualTo(2);

            // Begin round 2 (sessionId changes)
            mem.completeRound(1L, Optional.empty(), 0L);
            mem.beginRound(2L, 1_000_000L);
            int r2 = 0;
            for (int t = 0; t < 10; t++) {
                if (strategy.decide(ctx(mem, beh, game, rng)).isPresent()) r2++;
            }
            assertThat(r2).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("decide() — value invariants")
    class ValueInvariants {

        @Test
        @DisplayName("optionId is always a key of the affinity map")
        void optionInRange() {
            Game game = gameWithOptions(6);
            BotBehaviorConfig beh = behavior(100L, 100L, 100L, 100, 0);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            RandomBehaviorStrategy strategy = new RandomBehaviorStrategy();
            Random rng = new Random(99L);

            for (int t = 0; t < 100; t++) {
                BetDecision d = strategy.decide(ctx(mem, beh, game, rng)).orElseThrow();
                assertThat(game.getEffectiveOptionAffinities().keySet()).contains(d.optionId());
            }
        }

        @Test
        @DisplayName("amount aligned with betIncrement and within [minBet, maxBet]")
        void amountWithinBounds() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 1_000L, 100L, 100, 0);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            RandomBehaviorStrategy strategy = new RandomBehaviorStrategy();
            Random rng = new Random(11L);

            for (int t = 0; t < 100; t++) {
                BetDecision d = strategy.decide(ctx(mem, beh, game, rng)).orElseThrow();
                assertThat(d.amount()).isBetween(beh.getMinBet(), beh.getMaxBet());
                assertThat((d.amount() - beh.getMinBet()) % beh.getBetIncrement()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("onRoundEnd")
    class OnRoundEnd {

        @Test
        @DisplayName("Is a no-op — does not affect subsequent decide() output")
        void noOp() {
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 500L, 100L, 100, 0);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            // Two strategies fed an identical RNG: one receives onRoundEnd in
            // between decides, the other doesn't. Outputs must match.
            RandomBehaviorStrategy a = new RandomBehaviorStrategy();
            RandomBehaviorStrategy b = new RandomBehaviorStrategy();
            Random rngA = new Random(33L);
            Random rngB = new Random(33L);

            RoundResult dummy = new RoundResult(
                    1L, Optional.empty(), Map.of(), 0L, 0L, java.time.Instant.now());

            List<BetDecision> outA = new ArrayList<>();
            List<BetDecision> outB = new ArrayList<>();
            for (int t = 0; t < 10; t++) {
                outA.add(a.decide(ctx(mem, beh, game, rngA)).orElseThrow());
                a.onRoundEnd(dummy);  // extra onRoundEnd calls must not change state
                a.onRoundEnd(dummy);
                outB.add(b.decide(ctx(mem, beh, game, rngB)).orElseThrow());
            }

            assertThat(outA).isEqualTo(outB);
        }
    }
}
