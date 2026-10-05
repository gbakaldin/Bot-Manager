package com.vingame.bot.domain.bot.core.crash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AVIATOR_BOT Phase 2: {@link CrashBehavior} (AD-7). */
@DisplayName("CrashBehavior")
class CrashBehaviorTest {

    @Test
    @DisplayName("LEGACY is target U(1.1, 5.0), bet 0-4500 ms after the round opens")
    void legacy() {
        assertThat(CrashBehavior.LEGACY).isEqualTo(new CrashBehavior(1.1, 5.0, 0L, 4_500L));
    }

    @Test
    @DisplayName("a degenerate range (min == max) is allowed")
    void degenerate() {
        assertThat(new CrashBehavior(2.0, 2.0, 100L, 100L).maxTarget()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("bad ranges are rejected")
    void rejects() {
        assertThatThrownBy(() -> new CrashBehavior(0.9, 5.0, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CrashBehavior(3.0, 2.0, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CrashBehavior(1.1, 5.0, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CrashBehavior(1.1, 5.0, 5, 4)).isInstanceOf(IllegalArgumentException.class);
    }
}
