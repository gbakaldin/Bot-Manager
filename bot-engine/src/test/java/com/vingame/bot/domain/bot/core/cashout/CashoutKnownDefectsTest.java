package com.vingame.bot.domain.bot.core.cashout;

import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.FrameAction;
import com.vingame.bot.domain.bot.core.cashout.CashoutBetStateMachine.Plan;
import com.vingame.bot.domain.bot.message.cashout.CashoutBetFrame;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASHOUT_BOT QA: defects confirmed red against {@code ed85d86} and committed
 * {@code @Disabled} so the build stays green until Dev fixes them. Each test is the
 * acceptance check for its fix: remove {@code @Disabled} in the fixing commit.
 * See {@code docs/reviews/CASHOUT_BOT/qa.md} (Q-1, Q-2) and review.md.
 */
@DisplayName("CashoutBetStateMachine — known defects (red, @Disabled)")
class CashoutKnownDefectsTest {

    private static final List<Long> STAKES = List.of(1_000L, 10_000L, 100_000L);

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final CashoutBetStateMachine machine = new CashoutBetStateMachine(
            CashoutBehavior.LEGACY, 20_000L, 30_000L, 3, now::get, new Random(42L));

    private static final class Frame extends CashoutBetFrame {
        private final long sid;
        private final Long stake;
        private final double multiplier;
        private final boolean fin;

        Frame(long sid, Long stake, double multiplier, boolean fin) {
            super(1501);
            this.sid = sid;
            this.stake = stake;
            this.multiplier = multiplier;
            this.fin = fin;
        }

        @Override public long sid() { return sid; }
        @Override public OptionalLong stake() { return stake == null ? OptionalLong.empty() : OptionalLong.of(stake); }
        @Override public double multiplier() { return multiplier; }
        @Override public double cashoutValue() { return fin ? 0.0 : 1.0; }
        @Override public boolean isFinal() { return fin; }
        @Override protected boolean burstSignalled() { return false; }
    }

    private Plan place() {
        machine.nextBetAt().ifPresent(at -> now.set(Math.max(now.get(), at)));
        return machine.tryPlace(STAKES, 1_000_000_000L).orElseThrow();
    }

    @Test
    @Disabled("Q-1 (review [bug]): reset() does not remember the bound sid — red until fixed")
    @DisplayName("Q-1: after reset() mid-bet, a late frame of the abandoned bet does not bind the next bet")
    void resetRemembersBoundSid() {
        Plan live = place();
        assertThat(machine.onFrame(new Frame(51L, live.amount(), 1.0, false)))
                .isInstanceOf(FrameAction.Progress.class);

        machine.reset(); // beforeReconnect / onSubscribe
        Plan next = place();

        // The abandoned bet keeps streaming on the server. An X502 carries no b, a
        // progress frame may carry an equal stake — neither may bind the new plan.
        assertThat(machine.onFrame(new Frame(51L, next.amount(), 1.0, false)))
                .isSameAs(CashoutBetStateMachine.NONE);
        assertThat(machine.onFrame(new Frame(51L, null, 1.0, true)))
                .isSameAs(CashoutBetStateMachine.NONE);
        assertThat(machine.currentPlan()).containsSame(next);
    }

    @Test
    @Disabled("Q-2 (review [smell]): a frame with no sid (deserialised as 0) binds — red until fixed")
    @DisplayName("Q-2: a frame with sid 0 (the server's 'no bet' value) does not bind a PLACED bet")
    void sidZeroDoesNotBind() {
        place();

        assertThat(machine.onFrame(new Frame(0L, null, 1.0, false))).isSameAs(CashoutBetStateMachine.NONE);
        assertThat(machine.boundSid()).isEmpty();
    }
}
