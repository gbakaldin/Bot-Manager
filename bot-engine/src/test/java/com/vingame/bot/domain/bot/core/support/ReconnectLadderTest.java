package com.vingame.bot.domain.bot.core.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AVIATOR_BOT Phase 2: {@link ReconnectLadder} (AD-9). */
@DisplayName("ReconnectLadder")
class ReconnectLadderTest {

    private static List<Integer> rungsUpTo(int max, int r, int topShift) {
        List<Integer> rungs = new ArrayList<>();
        for (int i = -2; i <= max; i++) {
            if (ReconnectLadder.isRung(i, r, topShift)) {
                rungs.add(i);
            }
        }
        return rungs;
    }

    @Test
    @DisplayName("r=1, topShift=5: rungs at 1, 2, 4, 8, 16, 32, 64, 96 and nowhere else up to 100")
    void crashLadder() {
        assertThat(rungsUpTo(100, 1, 5)).containsExactly(1, 2, 4, 8, 16, 32, 64, 96);
    }

    @Test
    @DisplayName("r=3, topShift=5: the CASHOUT default ladder 3, 6, 12, 24, 48, 96, 192, 288")
    void cashoutDefaultLadder() {
        assertThat(rungsUpTo(300, 3, 5)).containsExactly(3, 6, 12, 24, 48, 96, 192, 288);
    }

    @Test
    @DisplayName("r <= 0 never reconnects; count <= 0 is never a rung")
    void disabled() {
        assertThat(rungsUpTo(1_000, 0, 5)).isEmpty();
        assertThat(rungsUpTo(1_000, -1, 5)).isEmpty();
        assertThat(ReconnectLadder.isRung(0, 1, 5)).isFalse();
    }

    @Test
    @DisplayName("topShift 0 is periodic from the first rung")
    void topShiftZero() {
        assertThat(rungsUpTo(10, 2, 0)).containsExactly(2, 4, 6, 8, 10);
    }

    @Test
    @DisplayName("topShift outside [0, 30] is rejected")
    void badTopShift() {
        assertThatThrownBy(() -> ReconnectLadder.isRung(1, 1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReconnectLadder.isRung(1, 1, 31)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("large counts beyond the top stay periodic without overflow")
    void largeCounts() {
        assertThat(ReconnectLadder.isRung(Integer.MAX_VALUE, 1, 5)).isFalse();
        assertThat(ReconnectLadder.isRung(32 * 1_000_000, 1, 5)).isTrue();
        assertThat(ReconnectLadder.isRung(32 * 1_000_000 + 1, 1, 5)).isFalse();
    }
}
