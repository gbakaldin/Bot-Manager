package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>A stamp is dated from inside the budget lock, never from before it</b> — the regression test
 * for the defect {@code GatewayBudgetEscalationIT} is credited with finding.
 * <p>
 * <b>Why QA is adding this.</b> The fix is in {@code admit()}: the clock is read a second time
 * <em>inside</em> {@code lock.lock()} and the stamp uses that value, not the {@code startedWaiting}
 * read taken before it. Under contention a thread can capture the earlier time, queue on the lock
 * while the admissions ahead of it complete, and then write a stamp dated <em>before it had
 * permission to send</em>. Such a stamp expires early, and the window then admits again while the
 * request it belonged to is still in flight — the window under-reports in the one direction that
 * ends in a Cloudflare block while the Grafana panel says there is room.
 * <p>
 * <b>Nothing in the tree failed when the defect was reinstated.</b> QA reverted the fix (one line,
 * {@code long now = startedWaiting}) and the full suite stayed green, the escalation IT included:
 * its receiver-side bound is {@code hard-cap + threads} = 84, and the overshoot the commit message
 * attributes to this defect is exactly 84 — the same figure the <em>fixed</em> code produces on the
 * same machine (measured: 79, 84, 84 over three runs with the fix, 84 with the defect). That bound
 * is right for what it is there for (we stamp at admission, the edge counts at arrival, so an
 * observer's window legitimately holds the in-flight tail) but it cannot discriminate a stale stamp
 * from ordinary flush latency, and no other test looked at stamp dating at all.
 * <p>
 * <b>How this discriminates deterministically.</b> The injected clock returns {@code T0} on its
 * first read and {@code T0 + window + 1} on every read after it — i.e. a whole window is
 * "consumed" between the pre-lock read and the in-lock read, which is the exaggerated form of what
 * lock contention does. The stamp must therefore be fresh, and the window must contain it. With the
 * pre-lock value the stamp is dated {@code T0}, which the very next prune discards: a request the
 * budget admitted is absent from the window it was admitted against.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
@DisplayName("SlidingWindowGatewayBudget — the stamp is dated from inside the lock")
class SlidingWindowGatewayBudgetClockDisciplineTest {

    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.forBot("group-1", "authtestws1", () -> false);

    /**
     * A clock that jumps by a whole window between its first read and its second — the caricature
     * of a thread that read the time, then waited on the lock. Every later read returns the same
     * later value, so the test is about <em>which</em> of the two reads the stamp used and nothing
     * else.
     */
    private static LongSupplier clockThatJumpsAfterTheFirstRead(long t0, AtomicInteger reads) {
        return () -> reads.getAndIncrement() == 0 ? t0 : t0 + WINDOW.toNanos() + 1;
    }

    @Test
    @DisplayName("enforce: an admitted request is in the window it was admitted against")
    void anAdmittedRequestIsInTheWindowItWasAdmittedAgainst() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        SlidingWindowGatewayBudget budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE),
                new SimpleMeterRegistry(), clockThatJumpsAfterTheFirstRead(1_000_000L, reads));
        try {
            assertThat(budget.execute(RequestTier.ESSENTIAL, SCOPE, () -> "sent")).isEqualTo("sent");

            assertThat(budget.windowRequests())
                    .as("the stamp must be dated from INSIDE the lock. Dated from before it, this "
                            + "reads 0: a request we admitted, and therefore sent, is missing from "
                            + "the window — so the window admits again while it is still in flight")
                    .isEqualTo(1);
            assertThat(reads.get())
                    .as("and the pre-lock read (the wait timer's baseline) is still a SEPARATE "
                            + "read from the stamp's — collapsing them is the defect, in either "
                            + "direction")
                    .isGreaterThanOrEqualTo(3);
        } finally {
            budget.shutdown();
        }
    }

    @Test
    @DisplayName("observe takes exactly one clock read per admission, and it is the stamp's")
    void observeModeHasNoPreLockReadToGetWrong() throws Exception {
        // observe reaches stampAdmitted(), which reads the clock ONCE and only under the lock, so
        // there is no second value for a stamp to be mis-dated from — the jumping clock above
        // cannot discriminate anything here, and a test that used it would fail for the wrong
        // reason (the jump would land between the stamp and the measurement).
        //
        // What is worth pinning is the count: one read. An edit that "unifies" the two paths by
        // hoisting a pre-lock read to the top of admit() for both modes would make this 2 and
        // would re-open the defect on the observe path, where no other assertion would notice —
        // observe is the shipped default on every prod box.
        AtomicInteger reads = new AtomicInteger();
        SlidingWindowGatewayBudget budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                GatewayBudgetSettings.defaults(),
                new SimpleMeterRegistry(), () -> {
                    reads.incrementAndGet();
                    return 1_000_000L;
                });
        try {
            budget.execute(RequestTier.DEFAULT, SCOPE, () -> "sent");
            assertThat(reads.get()).isEqualTo(1);
            assertThat(budget.windowRequests()).isEqualTo(1);
        } finally {
            budget.shutdown();
        }
    }

    @Test
    @DisplayName("the window is monotonic under a real clock: every admission is counted once")
    void everyAdmissionIsCountedOnceUnderARealClock() throws Exception {
        // The complement of the above, on a clock that only moves forward: 400 admissions under
        // the hard cap must produce a window of exactly 400. A stamp dated early would show up
        // here as a window BELOW the number admitted, which is the failure that matters — the
        // dashboard, the tier-2 rollup line and GatewayBudgetNearCap all read this gauge.
        AtomicLong wall = new AtomicLong(System.nanoTime());
        SlidingWindowGatewayBudget budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE),
                new SimpleMeterRegistry(), () -> wall.addAndGet(1_000L));
        try {
            for (int i = 0; i < 400; i++) {
                budget.execute(RequestTier.ESSENTIAL, SCOPE, () -> "ok");
            }
            assertThat(budget.windowRequests()).isEqualTo(400);
        } finally {
            budget.shutdown();
        }
    }
}
