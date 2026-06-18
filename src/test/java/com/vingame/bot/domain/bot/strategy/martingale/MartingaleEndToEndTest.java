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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 of {@code docs/plans/MARTINGALE_STRATEGIES.md}.
 *
 * <p>End-to-end deterministic stream verification for the Martingale family.
 * Each test drives a strategy through the full {@link BotMemory} lifecycle —
 * {@code beginRound} / {@code recordBetSent} / {@code completeRound} —
 * exactly the way {@code BettingMiniGameBot} drives it in production — and
 * pins the {@code currentBet} trajectory at every step against a hand-computed
 * expected progression.
 *
 * <p>The shared script is
 * {@code [LOSS, LOSS, WIN, NOBET, LOSS, PUSH, WIN, WIN, WIN]}. It deliberately
 * exercises:
 * <ul>
 *   <li>A pure loss streak (rounds 1, 2) — drives the loss progression.</li>
 *   <li>A win (round 3) — drives the win reset / retreat path.</li>
 *   <li>A no-bet round (round 4) — pins that idle rounds leave progression
 *       state untouched (especially Paroli's streak counter and Fibonacci's
 *       {@code fibIndex}).</li>
 *   <li>A push (round 6, {@code balanceDelta == 0} with non-empty bets) —
 *       pins Architecture Decision A5e: push is routed through the loss hook
 *       by the base class.</li>
 *   <li>A three-win streak (rounds 7, 8, 9) — drives Paroli's
 *       {@code STREAK_CAP = 3} bank reset and Fibonacci's two-step retreat
 *       floor at 0.</li>
 * </ul>
 *
 * <p>The plan specifically calls out Classic Martingale and Paroli (the two
 * doublers) as the must-cover strategies for this end-to-end pass — they
 * exercise the cap-hit reset and the streak-cap bank, respectively. D'Alembert
 * and Fibonacci are covered as well so all four progressions get a full
 * synthetic-stream replay through this harness. Each progression is tested in
 * both its Cautious and Aggressive variants to confirm the {@link RiskProfile}
 * constructor argument doesn't accidentally affect the progression arithmetic
 * (the picker output is irrelevant to the bet amounts).
 *
 * <p>Mirrors the hand-rolled harness pattern from
 * {@link com.vingame.bot.domain.bot.strategy.RandomBehaviorStrategyTest} and
 * the Phase 2/3 per-progression tests in this package.
 */
@DisplayName("Martingale end-to-end deterministic stream (Phase 4)")
class MartingaleEndToEndTest {

    /**
     * Per-round outcome flag. The mapping to {@link BotMemory}'s lifecycle:
     * <ul>
     *   <li>{@link #LOSS} — bot bets {@code currentBet}, payout is {@code 0}
     *       so {@code balanceDelta = -currentBet < 0}.</li>
     *   <li>{@link #WIN} — bot bets {@code currentBet}, payout is
     *       {@code currentBet * 2} so {@code balanceDelta = +currentBet > 0}.</li>
     *   <li>{@link #PUSH} — bot bets {@code currentBet}, payout is
     *       {@code currentBet} so {@code balanceDelta == 0} with non-empty bets.
     *       Routes through the loss hook by Architecture Decision A5e.</li>
     *   <li>{@link #NOBET} — bot skips the round entirely; no bet recorded, no
     *       payout, so {@code balanceDelta == 0} with empty bets. Routes through
     *       {@code nextBetAfterNoBet} (default: unchanged).</li>
     * </ul>
     */
    private enum Outcome { LOSS, WIN, PUSH, NOBET }

    /** Fixed script used by every progression assertion. */
    private static final List<Outcome> SCRIPT = List.of(
            Outcome.LOSS, Outcome.LOSS, Outcome.WIN, Outcome.NOBET,
            Outcome.LOSS, Outcome.PUSH, Outcome.WIN, Outcome.WIN, Outcome.WIN);

    /**
     * Six-option game with varied affinities. The affinities deliberately do
     * NOT influence the {@code currentBet} progression — they only feed the
     * {@link AffinityOptionPicker}. The bet trajectory assertions below ignore
     * which option each bet lands on; the option-picking math is pinned by
     * {@link AffinityOptionPickerTest}.
     */
    private static Game varyingAffinityGame() {
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        affinities.put(0, 5);
        affinities.put(1, 3);
        affinities.put(2, 1);
        affinities.put(3, 1);
        affinities.put(4, 3);
        affinities.put(5, 5);
        return Game.builder()
                .id("g1").name("BauCua").pluginName("BauCua")
                .offset(2000).optionAffinities(affinities).build();
    }

    private static BotBehaviorConfig behavior(long minBet, long maxBet, long step) {
        return BotBehaviorConfig.builder()
                .minBet(minBet).maxBet(maxBet).betIncrement(step)
                .maxBetsPerRound(1).betSkipPercentage(0)
                .build();
    }

    private static BetContext ctx(BotMemory mem, BotBehaviorConfig beh, Game g, Random rng) {
        return new BetContext(mem, beh, g, mem.getCurrentBalance(),
                mem.getCurrentRound(), rng);
    }

    /**
     * Drives {@code strategy} through {@link #SCRIPT} via the full
     * {@link BotMemory} lifecycle and returns the per-round {@code currentBet}
     * <i>after</i> each round's {@code onRoundEnd}. The returned list has the
     * same length as {@link #SCRIPT}; index {@code i} is the bet the strategy
     * would place on round {@code i + 2} (i.e. after round {@code i + 1}'s
     * outcome has been applied).
     *
     * <p>Mechanics per round:
     * <ol>
     *   <li>{@link BotMemory#beginRound(long, long)} clears the in-flight round
     *       and snapshots the balance — this is what {@code onStartGame} does
     *       in {@code BettingMiniGameBot}.</li>
     *   <li>If the round is a betting round (LOSS / WIN / PUSH),
     *       {@link com.vingame.bot.domain.bot.strategy.BettingStrategy#decide}
     *       is called to pull a {@link BetDecision} out, and
     *       {@link BotMemory#recordBetSent} accumulates it on the in-flight
     *       state — exactly what {@code BettingMiniGameBot.bet()} does after a
     *       successful {@code creditBalance}. For NOBET the {@code decide}
     *       call is skipped to match the production "scenario thread didn't
     *       tick this round" behaviour.</li>
     *   <li>{@link BotMemory#completeRound(long, Optional, long)} finalises the
     *       in-flight round into a {@link RoundResult} with the right
     *       {@code balanceDelta} — this is the per-bot view {@code onEndGame}
     *       hands the strategy. The returned {@code RoundResult} is fed
     *       directly to {@code strategy.onRoundEnd} so the strategy sees the
     *       same {@code Map<Integer, Long>} of bets and the same
     *       {@code balanceDelta} sign it would see in production.</li>
     * </ol>
     */
    private static <T extends MartingaleStrategySupport> List<Long> driveScript(
            T strategy, BotMemory mem, BotBehaviorConfig beh, Game game, long seed) {

        // Lifetime-scoped seeded RNG — matches production (BetContext.rng() is
        // the bot's own Random, instantiated once at bot creation).
        Random rng = new Random(seed);

        // Initial round boundary — primes BotMemory the same way onStartGame
        // would on the very first round of a bot's session.
        long sid = 1L;
        long balance = 1_000_000L;
        mem.beginRound(sid, balance);

        // First decide() call also primes the strategy's behavior-bounds cache
        // so onRoundEnd has minBet / maxBet / betIncrement available. This is
        // load-bearing: the lazy-init in MartingaleStrategySupport (see
        // Implementation Note 1) means a strategy whose first interaction is
        // onRoundEnd would WARN and skip. We don't rely on that defensive
        // path — every test here calls decide first.
        List<Long> trajectory = new ArrayList<>();
        for (Outcome outcome : SCRIPT) {
            long staked = 0L;
            long payout = 0L;

            if (outcome != Outcome.NOBET) {
                BetDecision decision = strategy.decide(ctx(mem, beh, game, rng)).orElseThrow();
                // Record the bet on the in-flight round — without this the
                // completeRound call below sees an empty betsByOption map and
                // routes the strategy through the NOBET branch.
                mem.recordBetSent(sid, decision.optionId(), decision.amount());
                staked = decision.amount();
                payout = switch (outcome) {
                    case LOSS  -> 0L;
                    case WIN   -> staked * 2L;
                    case PUSH  -> staked;
                    case NOBET -> 0L;  // unreachable; satisfies switch exhaustiveness
                };
            }

            RoundResult result = mem.completeRound(sid, Optional.empty(), payout);
            strategy.onRoundEnd(result);
            trajectory.add(strategy.getCurrentBet());

            // Advance the simulated balance and roll over the session id —
            // matches onStartGame firing for the next round.
            balance += (payout - staked);
            sid++;
            mem.beginRound(sid, balance);
        }
        return trajectory;
    }

    @Nested
    @DisplayName("Classic Martingale (doubler; cap-hit reset on overflow)")
    class ClassicMartingaleDeterministicStream {

        /**
         * Hand-computed trajectory with {@code minBet=100, maxBet=10_000,
         * betIncrement=100}. No cap-hit is hit on this script — see the
         * sibling {@link #capHitDuringStream()} test for the cap-hit variant.
         *
         * <table>
         *   <tr><th>Round</th><th>Outcome</th><th>Pre-bet</th><th>Post-onRoundEnd</th></tr>
         *   <tr><td>1</td><td>LOSS</td><td>100</td><td>200</td></tr>
         *   <tr><td>2</td><td>LOSS</td><td>200</td><td>400</td></tr>
         *   <tr><td>3</td><td>WIN</td><td>400</td><td>100 (reset)</td></tr>
         *   <tr><td>4</td><td>NOBET</td><td>—</td><td>100 (unchanged)</td></tr>
         *   <tr><td>5</td><td>LOSS</td><td>100</td><td>200</td></tr>
         *   <tr><td>6</td><td>PUSH</td><td>200</td><td>400 (loss-routed)</td></tr>
         *   <tr><td>7</td><td>WIN</td><td>400</td><td>100 (reset)</td></tr>
         *   <tr><td>8</td><td>WIN</td><td>100</td><td>100 (reset stays)</td></tr>
         *   <tr><td>9</td><td>WIN</td><td>100</td><td>100</td></tr>
         * </table>
         */
        private static final long[] EXPECTED = {200L, 400L, 100L, 100L, 200L, 400L, 100L, 100L, 100L};

        @Test
        @DisplayName("Cautious variant follows the doubler progression bit-for-bit")
        void cautiousProgression() {
            runOnce(new ClassicMartingaleCautious());
        }

        @Test
        @DisplayName("Aggressive variant follows the same progression — RiskProfile only affects picker")
        void aggressiveProgression() {
            runOnce(new ClassicMartingaleAggressive());
        }

        private void runOnce(ClassicMartingaleStrategy strategy) {
            Game game = varyingAffinityGame();
            BotBehaviorConfig beh = behavior(100L, 10_000L, 100L);
            BotMemory mem = new BotMemory(game);

            List<Long> trajectory = driveScript(strategy, mem, beh, game, 0xC0FFEEL);

            assertThat(trajectory)
                    .as("Classic Martingale trajectory for %s", strategy.getClass().getSimpleName())
                    .containsExactly(boxed(EXPECTED));
            // Final bet is minBet — the strategy is back at the start of its
            // progression after the bank-on-WIN sequence.
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Cap-hit reset fires mid-stream when the doubled target exceeds maxBet")
        void capHitDuringStream() {
            // Tight maxBet so the second loss in the opening L,L pair already
            // exceeds the cap: minBet=100, betIncrement=100, maxBet=300. The
            // first L takes currentBet to 200 (within cap); the second L would
            // target 400 > maxBet=300, so the base class resets to minBet=100.
            // The rest of the script then plays out from the reset state.
            Game game = varyingAffinityGame();
            BotBehaviorConfig beh = behavior(100L, 300L, 100L);
            BotMemory mem = new BotMemory(game);
            ClassicMartingaleCautious strategy = new ClassicMartingaleCautious();

            List<Long> trajectory = driveScript(strategy, mem, beh, game, 0xBEEFL);

            // Expected progression:
            //   R1 LOSS:  cb 100 → 200
            //   R2 LOSS:  cb 200 → 400 > cap → reset to 100
            //   R3 WIN:   cb 100 → 100
            //   R4 NOBET: cb       → 100
            //   R5 LOSS:  cb 100 → 200
            //   R6 PUSH:  cb 200 → 400 > cap → reset to 100
            //   R7 WIN:   cb 100 → 100
            //   R8 WIN:   cb 100 → 100
            //   R9 WIN:   cb 100 → 100
            assertThat(trajectory).containsExactly(
                    200L, 100L, 100L, 100L, 200L, 100L, 100L, 100L, 100L);
        }
    }

    @Nested
    @DisplayName("Paroli (doubler-on-win; streak-cap bank at 3)")
    class ParoliDeterministicStream {

        /**
         * Hand-computed trajectory with {@code minBet=100, maxBet=10_000,
         * betIncrement=100}. STREAK_CAP=3 fires on round 9 (the third W in the
         * tail run).
         *
         * <table>
         *   <tr><th>Round</th><th>Outcome</th><th>Pre-bet</th><th>Streak after</th><th>cb after</th></tr>
         *   <tr><td>1</td><td>LOSS</td><td>100</td><td>0</td><td>100</td></tr>
         *   <tr><td>2</td><td>LOSS</td><td>100</td><td>0</td><td>100</td></tr>
         *   <tr><td>3</td><td>WIN</td><td>100</td><td>1</td><td>200</td></tr>
         *   <tr><td>4</td><td>NOBET</td><td>—</td><td>1 (preserved)</td><td>200 (unchanged)</td></tr>
         *   <tr><td>5</td><td>LOSS</td><td>200</td><td>0 (reset)</td><td>100</td></tr>
         *   <tr><td>6</td><td>PUSH</td><td>100</td><td>0</td><td>100 (loss-routed)</td></tr>
         *   <tr><td>7</td><td>WIN</td><td>100</td><td>1</td><td>200</td></tr>
         *   <tr><td>8</td><td>WIN</td><td>200</td><td>2</td><td>400</td></tr>
         *   <tr><td>9</td><td>WIN</td><td>400</td><td>0 (bank at cap)</td><td>100</td></tr>
         * </table>
         */
        private static final long[] EXPECTED = {100L, 100L, 200L, 200L, 100L, 100L, 200L, 400L, 100L};

        @Test
        @DisplayName("Cautious variant follows the doubler-on-win progression and banks at STREAK_CAP")
        void cautiousProgression() {
            runOnce(ParoliCautious::new);
        }

        @Test
        @DisplayName("Aggressive variant follows the same progression — RiskProfile only affects picker")
        void aggressiveProgression() {
            runOnce(ParoliAggressive::new);
        }

        private void runOnce(Supplier<? extends ParoliStrategy> factory) {
            Game game = varyingAffinityGame();
            BotBehaviorConfig beh = behavior(100L, 10_000L, 100L);
            BotMemory mem = new BotMemory(game);
            ParoliStrategy strategy = factory.get();

            List<Long> trajectory = driveScript(strategy, mem, beh, game, 0xFACADEL);

            assertThat(trajectory)
                    .as("Paroli trajectory for %s", strategy.getClass().getSimpleName())
                    .containsExactly(boxed(EXPECTED));
            // After the bank-on-cap at round 9 the streak counter is cleared.
            assertThat(strategy.getConsecutiveWins()).isZero();
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);
        }
    }

    @Nested
    @DisplayName("D'Alembert (linear ramp / retreat)")
    class DAlembertDeterministicStream {

        /**
         * Hand-computed trajectory with {@code minBet=100, maxBet=10_000,
         * betIncrement=100}. The retreat floors at {@code minBet} so the tail
         * three-win run cannot drive {@code currentBet} below 100.
         *
         * <table>
         *   <tr><th>Round</th><th>Outcome</th><th>Pre-bet</th><th>cb after</th></tr>
         *   <tr><td>1</td><td>LOSS</td><td>100</td><td>200</td></tr>
         *   <tr><td>2</td><td>LOSS</td><td>200</td><td>300</td></tr>
         *   <tr><td>3</td><td>WIN</td><td>300</td><td>200</td></tr>
         *   <tr><td>4</td><td>NOBET</td><td>—</td><td>200 (unchanged)</td></tr>
         *   <tr><td>5</td><td>LOSS</td><td>200</td><td>300</td></tr>
         *   <tr><td>6</td><td>PUSH</td><td>300</td><td>400 (loss-routed)</td></tr>
         *   <tr><td>7</td><td>WIN</td><td>400</td><td>300</td></tr>
         *   <tr><td>8</td><td>WIN</td><td>300</td><td>200</td></tr>
         *   <tr><td>9</td><td>WIN</td><td>200</td><td>100 (floored)</td></tr>
         * </table>
         */
        private static final long[] EXPECTED = {200L, 300L, 200L, 200L, 300L, 400L, 300L, 200L, 100L};

        @Test
        @DisplayName("Cautious variant follows the linear progression bit-for-bit")
        void cautiousProgression() {
            runOnce(new DAlembertCautious());
        }

        @Test
        @DisplayName("Aggressive variant follows the same progression — RiskProfile only affects picker")
        void aggressiveProgression() {
            runOnce(new DAlembertAggressive());
        }

        private void runOnce(DAlembertStrategy strategy) {
            Game game = varyingAffinityGame();
            BotBehaviorConfig beh = behavior(100L, 10_000L, 100L);
            BotMemory mem = new BotMemory(game);

            List<Long> trajectory = driveScript(strategy, mem, beh, game, 0x1234567890L);

            assertThat(trajectory)
                    .as("D'Alembert trajectory for %s", strategy.getClass().getSimpleName())
                    .containsExactly(boxed(EXPECTED));
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);
        }
    }

    @Nested
    @DisplayName("Fibonacci (one step forward on loss; two steps back on win)")
    class FibonacciDeterministicStream {

        /**
         * Hand-computed trajectory with {@code minBet=100, maxBet=10_000,
         * betIncrement=100}. {@code currentBet = minBet * fib(fibIndex)} where
         * {@code fib(0) = fib(1) = 1, fib(2) = 2, fib(3) = 3, fib(4) = 5, ...}.
         * The two-step retreat floors at index 0.
         *
         * <table>
         *   <tr><th>Round</th><th>Outcome</th><th>Pre-bet</th><th>fibIndex after</th><th>cb after</th></tr>
         *   <tr><td>1</td><td>LOSS</td><td>100</td><td>1</td><td>100 (= 100*fib(1))</td></tr>
         *   <tr><td>2</td><td>LOSS</td><td>100</td><td>2</td><td>200 (= 100*fib(2))</td></tr>
         *   <tr><td>3</td><td>WIN</td><td>200</td><td>0 (max(0, 2-2))</td><td>100 (= 100*fib(0))</td></tr>
         *   <tr><td>4</td><td>NOBET</td><td>—</td><td>0 (preserved)</td><td>100 (unchanged)</td></tr>
         *   <tr><td>5</td><td>LOSS</td><td>100</td><td>1</td><td>100</td></tr>
         *   <tr><td>6</td><td>PUSH</td><td>100</td><td>2</td><td>200 (loss-routed)</td></tr>
         *   <tr><td>7</td><td>WIN</td><td>200</td><td>0 (max(0, 2-2))</td><td>100</td></tr>
         *   <tr><td>8</td><td>WIN</td><td>100</td><td>0 (max(0, 0-2))</td><td>100</td></tr>
         *   <tr><td>9</td><td>WIN</td><td>100</td><td>0 (max(0, 0-2))</td><td>100</td></tr>
         * </table>
         */
        private static final long[] EXPECTED = {100L, 200L, 100L, 100L, 100L, 200L, 100L, 100L, 100L};

        @Test
        @DisplayName("Cautious variant follows the Fibonacci progression with two-step retreat floor")
        void cautiousProgression() {
            runOnce(new FibonacciCautious());
        }

        @Test
        @DisplayName("Aggressive variant follows the same progression — RiskProfile only affects picker")
        void aggressiveProgression() {
            runOnce(new FibonacciAggressive());
        }

        private void runOnce(FibonacciStrategy strategy) {
            Game game = varyingAffinityGame();
            BotBehaviorConfig beh = behavior(100L, 10_000L, 100L);
            BotMemory mem = new BotMemory(game);

            List<Long> trajectory = driveScript(strategy, mem, beh, game, 0xABCDEFL);

            assertThat(trajectory)
                    .as("Fibonacci trajectory for %s", strategy.getClass().getSimpleName())
                    .containsExactly(boxed(EXPECTED));
            // After the tail three-win run with a starting index of 2, two-step
            // retreats clamp the index back to 0.
            assertThat(strategy.getFibIndex()).isZero();
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);
        }
    }

    @Nested
    @DisplayName("Picker still produces valid option ids during the stream")
    class PickerIntegration {

        /**
         * Smoke check that the picker remains in lockstep with the progression
         * during a full script replay — every {@code decide()} returns a
         * {@code BetDecision} whose {@code optionId} is one of the game's
         * affinity keys. Picker-side distribution math is pinned by
         * {@link AffinityOptionPickerTest}; this only confirms that the picker
         * is actually invoked end-to-end (it's plausible to wire a strategy
         * that always returns the same option and pass the bet-trajectory
         * assertion above).
         */
        @Test
        @DisplayName("Every emitted optionId is a valid affinity key for the script's betting rounds")
        void optionIdsAreValidThroughout() {
            Game game = varyingAffinityGame();
            BotBehaviorConfig beh = behavior(100L, 10_000L, 100L);

            for (Supplier<? extends MartingaleStrategySupport> ctor : List.<Supplier<? extends MartingaleStrategySupport>>of(
                    ClassicMartingaleCautious::new, ClassicMartingaleAggressive::new,
                    ParoliCautious::new, ParoliAggressive::new,
                    DAlembertCautious::new, DAlembertAggressive::new,
                    FibonacciCautious::new, FibonacciAggressive::new)) {

                MartingaleStrategySupport strategy = ctor.get();
                BotMemory mem = new BotMemory(game);
                Random rng = new Random(0x5EED_5EEDL);

                long sid = 1L;
                long balance = 1_000_000L;
                mem.beginRound(sid, balance);

                for (Outcome outcome : SCRIPT) {
                    long staked = 0L;
                    long payout = 0L;
                    if (outcome != Outcome.NOBET) {
                        BetDecision decision = strategy.decide(ctx(mem, beh, game, rng)).orElseThrow();
                        assertThat(game.getEffectiveOptionAffinities().keySet())
                                .as("optionId for %s on round %d", strategy.getClass().getSimpleName(), sid)
                                .contains(decision.optionId());
                        assertThat(decision.amount())
                                .as("amount for %s on round %d", strategy.getClass().getSimpleName(), sid)
                                .isBetween(beh.getMinBet(), beh.getMaxBet());
                        mem.recordBetSent(sid, decision.optionId(), decision.amount());
                        staked = decision.amount();
                        payout = switch (outcome) {
                            case LOSS  -> 0L;
                            case WIN   -> staked * 2L;
                            case PUSH  -> staked;
                            case NOBET -> 0L;
                        };
                    }
                    RoundResult result = mem.completeRound(sid, Optional.empty(), payout);
                    strategy.onRoundEnd(result);
                    balance += (payout - staked);
                    sid++;
                    mem.beginRound(sid, balance);
                }
            }
        }
    }

    /**
     * AssertJ's {@code containsExactly} on a {@code List<Long>} needs
     * {@code Long[]} (not {@code long[]}). Small helper so the per-progression
     * EXPECTED arrays can stay as primitive {@code long[]} (cheap to read at
     * a glance, no autoboxing in the literal).
     */
    private static Long[] boxed(long[] xs) {
        Long[] out = new Long[xs.length];
        for (int i = 0; i < xs.length; i++) out[i] = xs[i];
        return out;
    }

}
