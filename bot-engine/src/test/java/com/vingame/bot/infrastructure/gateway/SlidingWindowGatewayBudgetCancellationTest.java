package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.exception.GatewayRequestCancelledException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>{@code cancelScope} wakes waiters, it does not merely mark them</b>
 * (GATEWAY_REQUEST_BUDGET AD-8, A20.3).
 * <p>
 * This is a <b>stop-latency</b> requirement, not a pacing nicety, and it is a requirement on two
 * paths. Before enforcement a cancelled build returned within one in-flight HTTP call per
 * semaphore permit, so {@code /stop} answering in five seconds was comfortable whatever the
 * budget did. With waiter queues a bot parked <em>inside</em> the budget cannot return until this
 * method completes its future — and since Phase 2 both {@code POST /{id}/stop} <b>and</b>
 * {@code DELETE /{id}} (through {@code stopAndLogout}) take the group lock and therefore wait on
 * the build unwinding. A {@code cancelScope} that only set a flag would turn V2c's "stop 200
 * within 5 s" into "stop 200 within the paced duration of the start", which for a 3,000-bot group
 * is 33-50 minutes on a Tomcat worker.
 * <p>
 * <b>Cancellation is a predicate, never a thread interrupt</b>, and the two reasons are in the
 * codebase rather than in a preference: {@code CompletableFuture.join()} is not interruptible and
 * {@code createBotsInParallel} joins its per-bot futures, so interrupting the start thread would
 * not abort a paced start; and {@code VingameWebSocketClient.connect()} <em>swallows</em>
 * {@code InterruptedException} and returns a half-built client, so an upgrade must be abandoned
 * before {@code connect()} is entered. An interrupt that does reach a waiter is still honoured,
 * with the flag restored — asserted below.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("SlidingWindowGatewayBudget — enforce: cancellation wakes waiters")
class SlidingWindowGatewayBudgetCancellationTest {

    private static final GatewayRequestScope GROUP_1 =
            GatewayRequestScope.forBot("group-1", "authtestws1", () -> false);
    private static final GatewayRequestScope GROUP_2 =
            GatewayRequestScope.forBot("group-2", "othergroup1", () -> false);

    private AtomicLong clock;
    private SimpleMeterRegistry meters;
    private SlidingWindowGatewayBudget budget;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(0L);
        meters = new SimpleMeterRegistry();
        budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE),
                meters, clock::get);
    }

    @AfterEach
    void tearDown() {
        budget.shutdown();
    }

    private void fillToTheCap() throws Exception {
        for (int i = 0; i < 900; i++) {
            budget.execute(RequestTier.ESSENTIAL, GROUP_1, () -> "ok");
        }
    }

    private double counter(RequestTier tier, String outcome) {
        return meters.get(SlidingWindowGatewayBudget.REQUESTS_TOTAL)
                .tags("environmentId", "env-1", "product", "116", "tier", tier.name(), "outcome", outcome)
                .counter().count();
    }

    /** Park one waiter of {@code tier} on {@code scope} and return once it is queued. */
    private AtomicReference<Throwable> park(RequestTier tier, GatewayRequestScope scope,
                                            CountDownLatch done) throws InterruptedException {
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        int before = depth(tier);
        Thread.ofVirtual().name("waiter-" + tier).start(() -> {
            try {
                budget.execute(tier, scope, () -> "must not be sent");
                outcome.set(new AssertionError("admitted, but the window is at the cap"));
            } catch (Throwable t) {
                outcome.set(t);
            } finally {
                done.countDown();
            }
        });
        for (int i = 0; i < 300 && depth(tier) == before; i++) {
            Thread.sleep(10);
        }
        assertThat(depth(tier)).isEqualTo(before + 1);
        return outcome;
    }

    private int depth(RequestTier tier) {
        GatewayBudget.Snapshot s = budget.snapshot();
        return switch (tier) {
            case ESSENTIAL -> s.queuedEssential();
            case PRIORITIZED -> s.queuedPrioritized();
            case DEFAULT -> s.queuedDefault();
        };
    }

    @Test
    @DisplayName("cancelScope wakes only the matching group's waiters, and stamps nothing")
    void cancelScopeWakesOnlyTheMatchingGroup() throws Exception {
        fillToTheCap();

        CountDownLatch cancelled = new CountDownLatch(2);
        CountDownLatch survivor = new CountDownLatch(1);
        AtomicReference<Throwable> mine1 = park(RequestTier.ESSENTIAL, GROUP_1, cancelled);
        AtomicReference<Throwable> mine2 = park(RequestTier.ESSENTIAL, GROUP_1, cancelled);
        AtomicReference<Throwable> theirs = park(RequestTier.ESSENTIAL, GROUP_2, survivor);

        budget.cancelScope("group-1");

        assertThat(cancelled.await(10, TimeUnit.SECONDS))
                .as("the waiters must be WOKEN, not merely marked — a /stop and a DELETE are both "
                        + "parked on the group lock waiting for this build to unwind")
                .isTrue();
        assertThat(mine1.get()).isInstanceOf(GatewayRequestCancelledException.class);
        assertThat(mine2.get()).isInstanceOf(GatewayRequestCancelledException.class);
        assertThat(survivor.getCount())
                .as("group-2's waiter is another group's start and must be untouched — the key is "
                        + "GatewayRequestScope.botGroupId, which is what cancelStartInFlight passes")
                .isEqualTo(1);
        assertThat(theirs.get()).isNull();
        assertThat(depth(RequestTier.ESSENTIAL)).isEqualTo(1);

        assertThat(budget.windowRequests())
                .as("a cancelled waiter never left the JVM, so it never cost the edge a request. "
                        + "V3e checks exactly this: the window must NOT jump by the cancelled "
                        + "amount when a /stop lands mid-start.")
                .isEqualTo(900);
        assertThat(counter(RequestTier.ESSENTIAL, SlidingWindowGatewayBudget.OUTCOME_CANCELLED))
                .isEqualTo(2.0);

        budget.cancelScope("group-2");
    }

    @Test
    @DisplayName("cancelling one tier's waiters preserves the order of the others")
    void cancellationPreservesTheOtherTiersOrder() throws Exception {
        fillToTheCap();

        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch others = new CountDownLatch(2);
        park(RequestTier.ESSENTIAL, GROUP_1, cancelled);
        park(RequestTier.PRIORITIZED, GROUP_2, others);
        park(RequestTier.DEFAULT, GROUP_2, others);

        budget.cancelScope("group-1");

        assertThat(cancelled.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(depth(RequestTier.PRIORITIZED)).isEqualTo(1);
        assertThat(depth(RequestTier.DEFAULT)).isEqualTo(1);
        assertThat(others.getCount()).isEqualTo(2);

        budget.cancelScope("group-2");
    }

    @Test
    @DisplayName("a waiter whose own scope goes cancelled is dropped at the next admission pass")
    void aSelfCancellingWaiterIsDroppedOnTheNextPass() throws Exception {
        // The other half of AD-8. cancelScope is the push; this is the pull — the admission pass
        // asks every head whether it still wants to be sent. It is what makes Bot.isStopped()
        // effective for a bot that was stopped individually, with no group-level cancel at all.
        fillToTheCap();

        AtomicBoolean stopped = new AtomicBoolean(false);
        GatewayRequestScope scope =
                GatewayRequestScope.forBot("group-3", "authtestws9", stopped::get);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread.ofVirtual().name("self-cancelling").start(() -> {
            try {
                budget.execute(RequestTier.ESSENTIAL, scope, () -> "must not be sent");
            } catch (Throwable t) {
                outcome.set(t);
            } finally {
                done.countDown();
            }
        });
        for (int i = 0; i < 300 && depth(RequestTier.ESSENTIAL) == 0; i++) {
            Thread.sleep(10);
        }

        stopped.set(true);
        // Drain the window so the pass runs and finds it — the point is that it is dropped
        // rather than admitted, even though there is now room for it.
        clock.set(Duration.ofMinutes(5).toNanos() + 1);
        budget.admitWaiters();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(outcome.get()).isInstanceOf(GatewayRequestCancelledException.class);
        assertThat(budget.windowRequests())
                .as("room existed, and it still must not have been used by a request nobody wants")
                .isZero();
    }

    @Test
    @DisplayName("a request whose scope is already cancelled is refused before it queues")
    void anAlreadyCancelledScopeNeverQueues() throws Exception {
        GatewayRequestScope dead = GatewayRequestScope.forBot("group-1", "authtestws1", () -> true);

        assertThatThrownBy(() -> budget.execute(RequestTier.ESSENTIAL, dead, () -> "nope"))
                .isInstanceOf(GatewayRequestCancelledException.class);

        assertThat(depth(RequestTier.ESSENTIAL)).isZero();
        assertThat(budget.windowRequests()).isZero();
    }

    @Test
    @DisplayName("an interrupted waiter leaves the queue and restores the flag")
    void anInterruptedWaiterRestoresTheFlag() throws Exception {
        fillToTheCap();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicBoolean flagRestored = new AtomicBoolean();
        Thread waiter = Thread.ofVirtual().name("interruptible").unstarted(() -> {
            try {
                budget.execute(RequestTier.ESSENTIAL, GROUP_1, () -> "nope");
            } catch (Throwable t) {
                outcome.set(t);
                flagRestored.set(Thread.currentThread().isInterrupted());
            } finally {
                done.countDown();
            }
        });
        waiter.start();
        for (int i = 0; i < 300 && depth(RequestTier.ESSENTIAL) == 0; i++) {
            Thread.sleep(10);
        }

        waiter.interrupt();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(outcome.get())
                .as("an interrupt is a request to stop, and it propagates — the budget must not "
                        + "swallow it the way connect() does")
                .isInstanceOf(InterruptedException.class);
        assertThat(flagRestored).isTrue();
        assertThat(depth(RequestTier.ESSENTIAL))
                .as("and it must leave no queue depth behind, or the sustained-queueing alert "
                        + "reads a waiter that no longer exists")
                .isZero();
    }

    @Test
    @DisplayName("run() translates an interrupt into a cancellation, because a Runnable cannot carry one")
    void runTranslatesAnInterruptIntoACancellation() throws Exception {
        fillToTheCap();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicBoolean flagRestored = new AtomicBoolean();
        List<String> ran = new CopyOnWriteArrayList<>();
        Thread waiter = Thread.ofVirtual().name("interruptible-upgrade").unstarted(() -> {
            try {
                budget.runWsUpgrade(RequestTier.ESSENTIAL, GROUP_1, () -> ran.add("connect"));
            } catch (Throwable t) {
                outcome.set(t);
                flagRestored.set(Thread.currentThread().isInterrupted());
            } finally {
                done.countDown();
            }
        });
        waiter.start();
        for (int i = 0; i < 300 && depth(RequestTier.ESSENTIAL) == 0; i++) {
            Thread.sleep(10);
        }

        waiter.interrupt();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(outcome.get())
                .as("a Runnable cannot throw InterruptedException, and swallowing it would let an "
                        + "interrupted upgrade proceed into connect() — which is the exact defect "
                        + "AD-8 exists to avoid, since connect() returns a half-built client")
                .isInstanceOf(GatewayRequestCancelledException.class);
        assertThat(flagRestored).isTrue();
        assertThat(ran).isEmpty();
    }

    @Test
    @DisplayName("cancelScope for a group with nothing queued is a harmless no-op")
    void cancelScopeWithNothingQueuedIsANoOp() {
        budget.cancelScope("group-1");
        budget.cancelScope(null);

        assertThat(budget.windowRequests()).isZero();
        assertThat(counter(RequestTier.ESSENTIAL, SlidingWindowGatewayBudget.OUTCOME_CANCELLED))
                .as("stop() and DELETE call this on every group, whether or not a start is in "
                        + "flight; it must not invent a cancellation")
                .isZero();
    }
}
