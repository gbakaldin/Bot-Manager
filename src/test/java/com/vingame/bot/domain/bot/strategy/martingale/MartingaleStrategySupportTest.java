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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * QA gap-fill tests targeting behavior in {@link MartingaleStrategySupport}
 * that the per-progression Phase 2 / 3 / 4 tests do not exercise.
 *
 * <p>Covers Implementation Note 1 of
 * {@code docs/plans/MARTINGALE_STRATEGIES.md}: the defensive
 * {@code boundsCached == false} guard inside {@code onRoundEnd}. The plan
 * explicitly requested a test that calls {@code onRoundEnd} before any
 * {@code decide} has primed the behavior-bounds cache, and asserts the
 * strategy does not crash. None of the existing per-progression tests cover
 * this — every one of them uses {@code primeBoundsCache(...)} before driving
 * any {@code onRoundEnd}, which papers over the defensive path.
 *
 * <p>Also pins, on a single representative concrete strategy:
 * <ul>
 *   <li>{@code getCurrentBet()} returns 0L (the lazy-init sentinel) before
 *       any {@code decide} call, and {@code minBet} after — confirming the
 *       lazy-init contract.</li>
 *   <li>A second {@code decide()} call on the same session id does not
 *       re-prime the per-round counter (the {@code sessionId} round-boundary
 *       guard in {@code decide()}). Pinned by counting consecutive bets up
 *       to {@code maxBetsPerRound} on the same session and asserting the
 *       (maxBetsPerRound + 1)th decide returns empty.</li>
 *   <li>A session-id rollover resets the per-round counter so the next
 *       round can place bets again. Pinned by driving past
 *       {@code maxBetsPerRound} on one session, then calling
 *       {@code beginRound(sid+1, ...)} and confirming the strategy bets
 *       again.</li>
 * </ul>
 *
 * <p>These tests intentionally exercise {@link ClassicMartingaleCautious} as
 * the representative — the per-round counter / lazy-init / defensive-guard
 * code all live in the shared base class, so coverage on one progression
 * pins them for all eight strategies.
 */
@DisplayName("MartingaleStrategySupport (shared base) — QA gap fills")
class MartingaleStrategySupportTest {

    private static Game gameWithOptions(int n) {
        Map<Integer, Integer> affinities = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) affinities.put(i, 1);
        return Game.builder()
                .id("g1").name("BauCua").pluginName("BauCua")
                .offset(2000).optionAffinities(affinities).build();
    }

    private static BotBehaviorConfig behavior(long minBet, long maxBet, long step,
                                              int maxBetsPerRound) {
        return BotBehaviorConfig.builder()
                .minBet(minBet).maxBet(maxBet).betIncrement(step)
                .maxBetsPerRound(maxBetsPerRound).betSkipPercentage(0)
                .build();
    }

    private static BetContext ctx(BotMemory mem, BotBehaviorConfig beh, Game g, Random rng) {
        return new BetContext(mem, beh, g, mem.getCurrentBalance(),
                mem.getCurrentRound(), rng);
    }

    /** Build a single-option-bet RoundResult — same shape as the per-progression tests. */
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

    @Nested
    @DisplayName("Defensive guard: onRoundEnd before any decide (Implementation Note 1)")
    class BoundsCacheGuard {

        @Test
        @DisplayName("onRoundEnd before any decide does not crash and does not mutate currentBet")
        void onRoundEndBeforeDecideIsSafe() {
            // The strategy has never been driven through decide(), so the
            // base class has no cached minBet / maxBet / betIncrement.
            // applyClampAlignReset would divide by 0 / negative-floor without
            // the boundsCached guard — see MartingaleStrategySupport.onRoundEnd
            // (line 182-191).
            ClassicMartingaleCautious strategy = new ClassicMartingaleCautious();
            assertThat(strategy.getCurrentBet())
                    .as("currentBet pre-decide is the lazy-init sentinel 0L")
                    .isZero();

            RoundResult loss = result(1L, 100L, 0L);
            assertThatCode(() -> strategy.onRoundEnd(loss))
                    .as("onRoundEnd must not crash when bounds are uncached")
                    .doesNotThrowAnyException();

            // currentBet stays 0 — the defensive guard skips the progression
            // update entirely (WARN-and-skip per Implementation Note 1).
            assertThat(strategy.getCurrentBet())
                    .as("currentBet must remain at the lazy-init sentinel after the defensive skip")
                    .isZero();
        }

        @Test
        @DisplayName("decide after a defensive-skipped onRoundEnd still primes bounds and bets minBet")
        void decideRecoversAfterDefensiveSkip() {
            // Validates the recovery path called out in Implementation Note 1:
            // "the first cached value will arrive on the next decide, so this
            // is recoverable without crashing."
            ClassicMartingaleCautious strategy = new ClassicMartingaleCautious();
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 10_000L, 100L, 10);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(1L, 1_000_000L);

            // onRoundEnd lands first — defensive skip.
            strategy.onRoundEnd(result(0L, 100L, 0L));
            assertThat(strategy.getCurrentBet()).isZero();

            // Now decide runs — bounds get cached, currentBet lazily inits to minBet.
            BetDecision decision = strategy.decide(ctx(mem, beh, game, new Random(1L)))
                    .orElseThrow();
            assertThat(decision.amount()).isEqualTo(100L);
            assertThat(strategy.getCurrentBet()).isEqualTo(100L);

            // And the strategy keeps progressing normally on the next onRoundEnd.
            strategy.onRoundEnd(result(1L, 100L, 0L));
            assertThat(strategy.getCurrentBet())
                    .as("post-recovery loss doubles currentBet to 200")
                    .isEqualTo(200L);
        }
    }

    @Nested
    @DisplayName("Per-round counter (round-boundary handling in decide)")
    class PerRoundCounter {

        @Test
        @DisplayName("Bets per round are capped at maxBetsPerRound; (cap+1)th decide returns empty")
        void perRoundCounterCaps() {
            ClassicMartingaleCautious strategy = new ClassicMartingaleCautious();
            Game game = gameWithOptions(4);
            // maxBetsPerRound = 2 so two ticks bet, the third does not.
            BotBehaviorConfig beh = behavior(100L, 10_000L, 100L, 2);
            BotMemory mem = new BotMemory(game);
            mem.beginRound(7L, 1_000_000L);
            Random rng = new Random(0x5EEDL);

            assertThat(strategy.decide(ctx(mem, beh, game, rng))).isPresent();
            assertThat(strategy.decide(ctx(mem, beh, game, rng))).isPresent();
            assertThat(strategy.decide(ctx(mem, beh, game, rng)))
                    .as("third decide on the same session must respect maxBetsPerRound=2")
                    .isEmpty();
        }

        @Test
        @DisplayName("Round boundary (sessionId change) resets the per-round counter")
        void sessionBoundaryResetsCounter() {
            ClassicMartingaleCautious strategy = new ClassicMartingaleCautious();
            Game game = gameWithOptions(4);
            BotBehaviorConfig beh = behavior(100L, 10_000L, 100L, 1);
            BotMemory mem = new BotMemory(game);
            Random rng = new Random(0xC0FFEEL);

            // Session 1: one bet, then capped.
            mem.beginRound(1L, 1_000_000L);
            assertThat(strategy.decide(ctx(mem, beh, game, rng))).isPresent();
            assertThat(strategy.decide(ctx(mem, beh, game, rng))).isEmpty();

            // Roll over to session 2 — the in-flight RoundState.sessionId
            // changes, so the strategy's per-round counter must reset.
            mem.beginRound(2L, 1_000_000L);
            assertThat(strategy.decide(ctx(mem, beh, game, rng)))
                    .as("first decide on a fresh session must place a bet again")
                    .isPresent();
        }
    }
}
