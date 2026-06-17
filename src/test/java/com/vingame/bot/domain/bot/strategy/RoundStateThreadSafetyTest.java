package com.vingame.bot.domain.bot.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for review.md Bug 3: {@link RoundState} primitive
 * scalars were plain (non-volatile), so the cross-thread read in
 * {@code RandomBehaviorStrategy.decide} (scenario thread) had no
 * happens-before edge to the write in {@code BotMemory.beginRound} (netty
 * thread). The JMM allows visibility lag and (on 32-bit JVMs) torn reads.
 *
 * <p>The fix marks the scalars {@code volatile}. We can't easily assert
 * JMM semantics from a test, but we can at least pin the field modifiers
 * so a future refactor doesn't silently drop the {@code volatile} keyword.
 */
@DisplayName("RoundState scalar fields are volatile (review Bug 3)")
class RoundStateThreadSafetyTest {

    @Test
    @DisplayName("sessionId is volatile so cross-thread reads from strategy.decide() have a happens-before edge")
    void sessionIdIsVolatile() throws Exception {
        Field f = RoundState.class.getDeclaredField("sessionId");
        assertThat(Modifier.isVolatile(f.getModifiers()))
                .as("RoundState.sessionId must be volatile — strategy.decide reads it outside BotMemory monitor")
                .isTrue();
    }

    @Test
    @DisplayName("All primitive scalar fields are volatile — audit pin to prevent regression")
    void allScalarsVolatile() throws Exception {
        // The scenario thread may read any of these via getCurrentRound() once
        // future strategies start inspecting phase / remainingTimeMs in
        // addition to sessionId. Pin the contract here so a future refactor
        // doesn't silently drop the volatile modifier off one of them.
        List<String> scalarFields = new ArrayList<>();
        for (Field f : RoundState.class.getDeclaredFields()) {
            // Skip the bets map (HashMap, not a scalar — synchronized at
            // BotMemory level; snapshotCurrentRoundBets does the defensive copy).
            if (f.getType().isPrimitive() || Enum.class.isAssignableFrom(f.getType())
                    || f.getType().getName().startsWith("com.vingame.bot.domain.bot.util.")) {
                scalarFields.add(f.getName());
                assertThat(Modifier.isVolatile(f.getModifiers()))
                        .as("RoundState." + f.getName() + " must be volatile (audit pin)")
                        .isTrue();
            }
        }
        // Sanity: we actually inspected the three documented scalars.
        assertThat(scalarFields).contains("sessionId", "phase", "remainingTimeMs");
    }
}
