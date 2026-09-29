package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.exception.GatewayBudgetExhaustedException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>How long each tier waits, and what it is told when it gives up</b>
 * (GATEWAY_REQUEST_BUDGET AD-5, AD-10, AD-11, A16.2).
 * <p>
 * The interesting cases are the two ends. {@link RequestTier#ESSENTIAL} has
 * {@code max-wait=0}, which means <b>unbounded but cancellable</b>, and that is admissible for
 * exactly one reason: the sliding window drains by construction, so a 3,000-bot start is ~10
 * windows of monotonic progress rather than an open-ended park. Anything that does <em>not</em>
 * drain by construction may not be waited on unboundedly — which is why an open circuit refuses
 * every tier instead of parking ESSENTIAL (A16.2), and why {@code FOLLOWUPS.md} P13 had to be
 * re-read before this wait was given out.
 * <p>
 * At the other end, {@link #tryExecuteWithZeroWaitDoesNotStamp} is AD-10: a drift balance read
 * runs on a ws-parser message-processor thread, so it must neither park nor throw into the bot's
 * message pipeline. It gives up immediately, the bot plays on its local estimate, and nothing is
 * stamped because nothing was sent.
 * <p>
 * Manual clock throughout; nothing sleeps for a timeout it is asserting. The one real wall-clock
 * wait is a 100 ms {@code max-wait}, because a timeout has to elapse in real time to be a
 * timeout — the window it is waiting on is still driven by the injected clock.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("SlidingWindowGatewayBudget — enforce: waits, timeouts and Retry-After")
class SlidingWindowGatewayBudgetWaitTest {

    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.forBot("group-1", "authtestws1", () -> false);

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

    private void fill(RequestTier tier, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            budget.execute(tier, SCOPE, () -> "ok");
        }
    }

    private double counter(RequestTier tier, String outcome) {
        return meters.get(SlidingWindowGatewayBudget.REQUESTS_TOTAL)
                .tags("environmentId", "env-1", "product", "116", "tier", tier.name(), "outcome", outcome)
                .counter().count();
    }

    @Test
    @DisplayName("a DEFAULT request times out with the right type, and Retry-After is the next expiry")
    void defaultTimesOutWithRetryAfter() throws Exception {
        // One stamp at t=0, the rest at t=60s, so the earliest expiry — and therefore
        // Retry-After — is 4 minutes away from the moment of refusal.
        budget.execute(RequestTier.DEFAULT, SCOPE, () -> "first");
        clock.addAndGet(Duration.ofSeconds(60).toNanos());
        fill(RequestTier.DEFAULT, 499);
        assertThat(budget.windowRequests()).isEqualTo(500);

        GatewayBudgetExhaustedException refused = (GatewayBudgetExhaustedException)
                org.assertj.core.api.Assertions.catchThrowable(() ->
                        budget.execute(RequestTier.DEFAULT, SCOPE, () -> "ok", Duration.ZERO));

        assertThat(refused).isNotNull();
        assertThat(refused.getTier()).isEqualTo(RequestTier.DEFAULT);
        assertThat(refused.getEnvironmentId()).isEqualTo("env-1");
        assertThat(refused.getRetryAfter().toSeconds())
                .as("the soonest moment a retry could POSSIBLY be admitted: the earliest stamp "
                        + "in the window expires four minutes from now. Advice, not a promise — a "
                        + "higher tier may take the freed slot first.")
                .isEqualTo(240);
        assertThat(refused.getMessage())
                .as("client-visible on an unauthenticated endpoint (it reaches lastError through "
                        + "ClientSafeMessage), so it may name a tier, a duration and an "
                        + "environment id and must never name a host, a port or an upstream body")
                .contains("tier DEFAULT")
                .contains("env-1")
                .doesNotContain("http")
                .doesNotContain("://");
        assertThat(counter(RequestTier.DEFAULT, SlidingWindowGatewayBudget.OUTCOME_TIMEOUT))
                .isEqualTo(1.0);
        assertThat(budget.windowRequests())
                .as("a refused request was never sent, so it must never be stamped")
                .isEqualTo(500);
    }

    @Test
    @DisplayName("a bounded wait really elapses, and the waiter leaves no queue depth behind")
    void aBoundedWaitElapsesAndCleansUp() throws Exception {
        SlidingWindowGatewayBudget bounded = new SlidingWindowGatewayBudget("env-2", "Staging", "116",
                GatewayBudgetSettings.defaults()
                        .withMode(GatewayBudgetMode.ENFORCE)
                        .withMaxWait(RequestTier.DEFAULT, Duration.ofMillis(100)),
                new SimpleMeterRegistry(), clock::get);
        try {
            for (int i = 0; i < 500; i++) {
                bounded.execute(RequestTier.DEFAULT, SCOPE, () -> "ok");
            }

            assertThatThrownBy(() -> bounded.execute(RequestTier.DEFAULT, SCOPE, () -> "ok"))
                    .isInstanceOf(GatewayBudgetExhaustedException.class);

            assertThat(bounded.snapshot().queuedDefault())
                    .as("a timed-out waiter must remove itself, or the queue-depth gauge — which "
                            + "the sustained-queueing alert reads — drifts up for ever")
                    .isZero();
        } finally {
            bounded.shutdown();
        }
    }

    @Test
    @DisplayName("ESSENTIAL never times out: it parks until the window drains, then goes")
    void essentialWaitsUnboundedAndIsEventuallyAdmitted() throws Exception {
        fill(RequestTier.ESSENTIAL, 900);

        CountDownLatch admitted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.ofVirtual().name("essential-waiter").start(() -> {
            try {
                budget.execute(RequestTier.ESSENTIAL, SCOPE, () -> {
                    admitted.countDown();
                    return "ok";
                });
            } catch (Throwable t) {
                failure.set(t);
                admitted.countDown();
            }
        });
        for (int i = 0; i < 300 && budget.snapshot().queuedEssential() == 0; i++) {
            Thread.sleep(10);
        }
        assertThat(budget.snapshot().queuedEssential()).isEqualTo(1);
        assertThat(admitted.getCount())
                .as("max-wait=0 means unbounded, so nothing may have happened yet")
                .isEqualTo(1);

        // Drain the window and drive the pass the scheduler would have driven.
        clock.set(WINDOW.toNanos() + 1);
        budget.admitWaiters();

        assertThat(admitted.await(10, TimeUnit.SECONDS))
                .as("the window drains BY CONSTRUCTION, which is the only reason an unbounded "
                        + "wait is admissible here at all (A16.2)")
                .isTrue();
        assertThat(failure.get()).isNull();
        assertThat(counter(RequestTier.ESSENTIAL, SlidingWindowGatewayBudget.OUTCOME_TIMEOUT))
                .as("ESSENTIAL must never time out — a bot that cannot come up because the app "
                        + "paced it is the failure this tier exists to prevent")
                .isZero();
    }

    @Test
    @DisplayName("tryExecute(ZERO) gives up immediately, stamps nothing and does not throw")
    void tryExecuteWithZeroWaitDoesNotStamp() throws Exception {
        // AD-10. This is the drift balance read, and it runs on a ws-parser message-processor
        // thread: parking it would stall the bot's message pipeline and throwing into it would
        // break onNewSession. So it returns empty, the bot uses its local estimate, and it sets
        // balanceReadDeferred so the pre-deposit refresh knows the figure is stale.
        fill(RequestTier.DEFAULT, 500);
        AtomicBoolean called = new AtomicBoolean();

        Optional<String> result = budget.tryExecute(RequestTier.DEFAULT, SCOPE, () -> {
            called.set(true);
            return "balance";
        }, Duration.ZERO);

        assertThat(result).isEmpty();
        assertThat(called).isFalse();
        assertThat(budget.windowRequests()).isEqualTo(500);
        assertThat(counter(RequestTier.DEFAULT, SlidingWindowGatewayBudget.OUTCOME_TIMEOUT))
                .as("a deferral is a bounded outcome and must be visible — otherwise a fleet "
                        + "whose balance reads are all being deferred looks perfectly healthy")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("tryExecute with room admits and returns the value")
    void tryExecuteAdmitsWhenThereIsRoom() throws Exception {
        Optional<String> result =
                budget.tryExecute(RequestTier.DEFAULT, SCOPE, () -> "balance", Duration.ZERO);

        assertThat(result).contains("balance");
        assertThat(budget.windowRequests()).isEqualTo(1);
    }

    @Test
    @DisplayName("the registration wait override outlasts DEFAULT's own 30 s")
    void theRegistrationOverrideIsHonoured() throws Exception {
        // AD-19: registration runs at DEFAULT but waits registration.max-wait, so an admitted
        // registration FINISHES rather than half-finishes when a group start floods the window
        // mid-way. A half-registered group is the thing that gets forgotten.
        fill(RequestTier.DEFAULT, 500);

        CountDownLatch admitted = new CountDownLatch(1);
        Thread.ofVirtual().name("registration").start(() -> {
            try {
                budget.execute(RequestTier.DEFAULT, GatewayRequestScope.registration("authtestws"),
                        () -> {
                            admitted.countDown();
                            return "registered";
                        }, Duration.ofMinutes(15));
            } catch (Exception e) {
                // asserted through the latch
            }
        });
        for (int i = 0; i < 300 && budget.snapshot().queuedDefault() == 0; i++) {
            Thread.sleep(10);
        }

        // DEFAULT's configured wait is 30 s; the override is 15 minutes. Advance the window by
        // a full 5 minutes — far past 30 s — and the waiter is still there to be admitted.
        clock.set(WINDOW.toNanos() + 1);
        budget.admitWaiters();

        assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
