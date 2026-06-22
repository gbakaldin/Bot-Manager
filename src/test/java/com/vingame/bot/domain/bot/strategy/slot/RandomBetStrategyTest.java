package com.vingame.bot.domain.bot.strategy.slot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 of {@code docs/plans/SLOT_MACHINE_BOT.md} (AD-9).
 *
 * <p>{@link RandomBetStrategy} picks uniformly from the server-sourced allowed
 * set using the bot-owned RNG ({@link SlotBetContext#rng()}). A seeded
 * {@link Random} pins the pick sequence deterministically.
 */
@DisplayName("RandomBetStrategy")
class RandomBetStrategyTest {

    private static final List<Long> ALLOWED = List.of(500L, 1000L, 10000L);

    @Test
    @DisplayName("Picks deterministically from the allowed set given a seeded Random")
    void deterministicPickFromSeededRandom() {
        RandomBetStrategy strategy = new RandomBetStrategy();
        Random seeded = new Random(42L);

        // Reference: the strategy consumes rng.nextInt(size) once per call, so
        // the pick sequence equals ALLOWED.get(reference.nextInt(3)) with an
        // identically-seeded RNG. Pin the equivalence over several calls.
        Random reference = new Random(42L);
        for (int i = 0; i < 10; i++) {
            SlotBetContext ctx = new SlotBetContext(ALLOWED, 25, 50_000_000L, seeded);
            long expected = ALLOWED.get(reference.nextInt(ALLOWED.size()));
            assertThat(strategy.chooseBet(ctx)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("Every pick is a member of the allowed set")
    void picksAreMembersOfAllowedSet() {
        RandomBetStrategy strategy = new RandomBetStrategy();
        Random seeded = new Random(7L);

        for (int i = 0; i < 100; i++) {
            SlotBetContext ctx = new SlotBetContext(ALLOWED, 25, 50_000_000L, seeded);
            assertThat(ALLOWED).contains(strategy.chooseBet(ctx));
        }
    }

    @Test
    @DisplayName("Single-element allowed set always returns that element (nextInt(1) == 0)")
    void singleElementSet() {
        RandomBetStrategy strategy = new RandomBetStrategy();
        Random seeded = new Random(99L);
        for (int i = 0; i < 10; i++) {
            SlotBetContext ctx = new SlotBetContext(List.of(2500L), 25, 50_000_000L, seeded);
            assertThat(strategy.chooseBet(ctx)).isEqualTo(2500L);
        }
    }

    @Test
    @DisplayName("Picks span the whole allowed set over many draws (no element starved)")
    void coversAllElements() {
        RandomBetStrategy strategy = new RandomBetStrategy();
        Random seeded = new Random(123L);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) {
            SlotBetContext ctx = new SlotBetContext(ALLOWED, 25, 50_000_000L, seeded);
            seen.add(strategy.chooseBet(ctx));
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(ALLOWED);
    }
}
