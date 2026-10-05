package com.vingame.bot.domain.bot.core.cashout;

import com.vingame.bot.domain.bot.core.support.ReconnectLadder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AVIATOR_BOT Phase 2 (AD-9, OI-5): the shared {@link ReconnectLadder} has exactly the
 * semantics of CASHOUT's private {@code isReconnectRung}, which this feature does not
 * migrate. Lives in the cashout package only to reach that package-private method; it
 * changes nothing about CASHOUT.
 */
@DisplayName("ReconnectLadder ≡ CashoutBetStateMachine.isReconnectRung")
class ReconnectLadderParityTest {

    @Test
    @DisplayName("identical for R in [-1, 7] and every count in [-1, 2000]")
    void parity() {
        for (int r = -1; r <= 7; r++) {
            CashoutBetStateMachine m = new CashoutBetStateMachine(CashoutBehavior.LEGACY, 20_000L, 30_000L,
                    r, () -> 0L, new Random(1));
            for (int count = -1; count <= 2_000; count++) {
                assertThat(ReconnectLadder.isRung(count, r, 5))
                        .as("R=%d count=%d", r, count)
                        .isEqualTo(m.isReconnectRung(count));
            }
        }
    }
}
