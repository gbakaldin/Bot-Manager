package com.vingame.bot.domain.bot.strategy.slot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 of {@code docs/plans/SLOT_MACHINE_BOT.md} (AD-9).
 *
 * <p>{@link FixedBetStrategy} always stakes the smallest (first) allowed value
 * — the allowed set arrives sorted ascending from the bot's {@code onSubscribe}.
 */
@DisplayName("FixedBetStrategy")
class FixedBetStrategyTest {

    @Test
    @DisplayName("Always returns the first (smallest) allowed value")
    void returnsSmallest() {
        FixedBetStrategy strategy = new FixedBetStrategy();
        List<Long> allowed = List.of(500L, 1000L, 2000L, 5000L, 10000L);

        // Distinct RNGs / balances must not change the pick — FIXED is stateless
        // and RNG-independent.
        for (int i = 0; i < 5; i++) {
            SlotBetContext ctx = new SlotBetContext(allowed, 25, 50_000_000L, new Random(i));
            assertThat(strategy.chooseBet(ctx)).isEqualTo(500L);
        }
    }
}
