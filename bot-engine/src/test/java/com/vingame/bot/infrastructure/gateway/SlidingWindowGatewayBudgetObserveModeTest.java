package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.exception.GatewayRequestCancelledException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>{@code observe} cannot block, delay, refuse or reorder anything.</b>
 * <p>
 * This is the property on which "deployable to ten prod environments unchanged" rests, and it
 * survives Phase 3 unchanged: enforcement added queues, ceilings and waits, and an
 * {@code observe} budget must still be "stamp, then run" byte-for-byte. The mode is an
 * environment variable, so this is also the test that keeps
 * {@code GATEWAY_BUDGET_MODE=observe → enforce → observe} a safe round trip on a live box.
 * <p>
 * <b>What changed in Phase 3, and why this class was rewritten rather than deleted.</b> Before
 * enforcement every test here ran both modes and asserted they behaved identically, because
 * they did. Two of those assertions were deliberate tripwires for exactly this phase (A5.5):
 * {@code aCancelledScopeIsAdmittedInEitherMode} asserted that a cancelled scope's request was
 * issued and counted <em>in enforce</em>, which is now the opposite of correct. It is replaced
 * by {@link #aCancelledScopeIsRefusedAndNotStampedUnderEnforce}, which asserts the target
 * behaviour — including the half the old assertion could never have reached: a scope whose
 * <b>bot is perfectly healthy</b> and whose <em>group's start</em> was cancelled. That is the
 * population Phase 2 created, and a rewrite that only covered {@code Bot.isStopped()} would
 * have pinned the case that was already true.
 * <p>
 * The {@link Timeout} is the assertion of last resort: if a future change ever makes an
 * {@code observe} admission park, these tests fail in seconds instead of hanging a CI run.
 * Nothing here sleeps, and nothing here can reach a socket — the calls are lambdas returning
 * constants.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("SlidingWindowGatewayBudget — observe: nothing waits, nothing is refused")
class SlidingWindowGatewayBudgetObserveModeTest {

    /** Well past 900, and past every tier ceiling, so no admission rule can be satisfied. */
    private static final int FAR_PAST_THE_CAP = 1_500;

    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.forBot("group-1", "authtestws1", () -> false);

    /** A clock that never moves: no stamp can expire, so the window only ever grows. */
    private final AtomicLong frozenClock = new AtomicLong(0L);

    private SlidingWindowGatewayBudget budget(GatewayBudgetMode mode) {
        return new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                GatewayBudgetSettings.defaults().withMode(mode),
                new SimpleMeterRegistry(), frozenClock::get);
    }

    @Test
    @DisplayName("every entry point admits far past the hard cap in observe mode")
    void noEntryPointEverRefusesInObserveMode() throws Exception {
        SlidingWindowGatewayBudget budget = budget(GatewayBudgetMode.OBSERVE);
        List<String> ran = new ArrayList<>();

        // Fill the window past the cap with the tier whose ceiling is LOWEST, so that under
        // enforce every one of the calls below would be refused or queued.
        for (int i = 0; i < FAR_PAST_THE_CAP; i++) {
            budget.execute(RequestTier.DEFAULT, SCOPE, () -> "ok");
        }
        assertThat(budget.windowRequests())
                .as("the window is measured, not bounded, in observe mode")
                .isEqualTo(FAR_PAST_THE_CAP);

        // Now every remaining entry point, each at the tier enforcement is strictest about,
        // with the window already 600 requests past the hard cap.
        assertThat(budget.execute(RequestTier.DEFAULT, SCOPE, () -> "execute")).isEqualTo("execute");
        assertThat(budget.execute(RequestTier.DEFAULT, SCOPE, () -> "override", Duration.ofMinutes(15)))
                .isEqualTo("override");
        budget.run(RequestTier.DEFAULT, SCOPE, () -> ran.add("run"));
        budget.runWsUpgrade(RequestTier.DEFAULT, SCOPE, () -> ran.add("wsUpgrade"));
        Optional<String> tried =
                budget.tryExecute(RequestTier.DEFAULT, SCOPE, () -> "try", Duration.ZERO);
        budget.count("ws-probe");
        budget.countWsUpgrade("ws-probe");
        assertThatCode(() -> budget.cancelScope("group-1"))
                .as("cancelScope must be a no-op, not a throw, when nothing is queued")
                .doesNotThrowAnyException();

        assertThat(ran).containsExactly("run", "wsUpgrade");
        assertThat(tried)
                .as("tryExecute(ZERO) is an execute in observe mode — AD-10's deferral only "
                        + "happens where there is something to defer to")
                .contains("try");
        assertThat(budget.windowRequests())
                .as("five more requests left the JVM (execute, override, run, upgrade, try) plus "
                        + "two probe stamps; a cancelScope sends nothing")
                .isEqualTo(FAR_PAST_THE_CAP + 7);
        assertThat(budget.snapshot().mode()).isEqualTo(GatewayBudgetMode.OBSERVE);
        budget.shutdown();
    }

    @Test
    @DisplayName("a cancelled scope is issued and counted in observe mode — it really was sent")
    void aCancelledScopeIsStillSentInObserveMode() throws Exception {
        SlidingWindowGatewayBudget budget = budget(GatewayBudgetMode.OBSERVE);
        AtomicBoolean called = new AtomicBoolean();
        GatewayRequestScope cancelled = GatewayRequestScope.forBot("group-1", "bot1", () -> true);

        budget.execute(RequestTier.ESSENTIAL, cancelled, () -> {
            called.set(true);
            return "sent anyway";
        });

        assertThat(called)
                .as("observe has no queue for a request to be cancelled OUT of, so the honest "
                        + "behaviour is to issue it — and therefore to count it")
                .isTrue();
        assertThat(budget.windowRequests()).isEqualTo(1);
        budget.shutdown();
    }

    @Test
    @DisplayName("under enforce a cancelled scope is refused, and nothing is stamped")
    void aCancelledScopeIsRefusedAndNotStampedUnderEnforce() {
        // A5.5's rewrite, and the reason it asserts BOTH halves of cancellation: before Phase 2
        // the only cancelled scope in the fleet came from Bot.isStopped(); now every bot of a
        // cancelled group start has one, and the group half is the one a /stop depends on.
        SlidingWindowGatewayBudget budget = budget(GatewayBudgetMode.ENFORCE);

        AtomicBoolean botStopped = new AtomicBoolean(false);
        AtomicBoolean groupStartCancelled = new AtomicBoolean(false);
        AtomicBoolean called = new AtomicBoolean();
        GatewayRequestScope scope = GatewayRequestScope.forBot("group-1", "authtestws1",
                () -> botStopped.get() || groupStartCancelled.get());

        // Half one: a stopped bot. This is the case that was already true in Phase 1.
        botStopped.set(true);
        assertThatThrownBy(() -> budget.execute(RequestTier.ESSENTIAL, scope, () -> {
            called.set(true);
            return "must not be sent";
        })).isInstanceOf(GatewayRequestCancelledException.class);
        assertThat(called).isFalse();

        // Half two: a PERFECTLY HEALTHY bot whose group's start was cancelled by a /stop or a
        // DELETE. This is the population Phase 2 created and the half the old tripwire could
        // not have covered — it is also the only reason a paced start unwinds rather than
        // running to completion after the operator already got their 200.
        botStopped.set(false);
        groupStartCancelled.set(true);
        assertThatThrownBy(() -> budget.execute(RequestTier.ESSENTIAL, scope, () -> {
            called.set(true);
            return "must not be sent";
        })).isInstanceOf(GatewayRequestCancelledException.class);

        assertThat(called)
                .as("a refused request must never reach its call — the whole point is that it "
                        + "does not leave the JVM")
                .isFalse();
        assertThat(budget.windowRequests())
                .as("never stamped: it never left the JVM, so it never cost the edge a request. "
                        + "If this were 2 the window would over-report and a paced fleet would "
                        + "throttle itself for traffic it did not send.")
                .isZero();
        budget.shutdown();
    }

    @Test
    @DisplayName("the queue-depth gauges stay at zero however hard an observe budget is driven")
    void nothingIsEverQueuedInObserveMode() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SlidingWindowGatewayBudget budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                GatewayBudgetSettings.defaults(), meters, frozenClock::get);

        for (int i = 0; i < FAR_PAST_THE_CAP; i++) {
            budget.execute(RequestTier.values()[i % RequestTier.values().length], SCOPE, () -> "ok");
        }

        for (RequestTier tier : RequestTier.values()) {
            assertThat(meters.get(SlidingWindowGatewayBudget.QUEUE_DEPTH)
                    .tags("environmentId", "env-1", "product", "116", "tier", tier.name())
                    .gauge().value())
                    .as("queue depth for %s — a non-zero value here would mean something parked", tier)
                    .isZero();
            assertThat(meters.get(SlidingWindowGatewayBudget.REQUESTS_TOTAL)
                    .tags("environmentId", "env-1", "product", "116", "tier", tier.name(),
                            "outcome", SlidingWindowGatewayBudget.OUTCOME_TIMEOUT)
                    .counter().count())
                    .as("no request may time out in observe mode")
                    .isZero();
            assertThat(meters.get(SlidingWindowGatewayBudget.REQUESTS_TOTAL)
                    .tags("environmentId", "env-1", "product", "116", "tier", tier.name(),
                            "outcome", SlidingWindowGatewayBudget.OUTCOME_CANCELLED)
                    .counter().count())
                    .isZero();
        }
        assertThat(budget.snapshot().describeForRollup())
                .isEqualTo("gateway=1500/900 queued=0/0/0 circuit=closed");
        budget.shutdown();
    }

    @Test
    @DisplayName("concurrent admissions lose no stamp and admit in the order the callers arrive")
    void theWindowIsExactUnderConcurrency() throws Exception {
        // The real budget is called from every bot thread of a group start at once. The deque
        // is guarded by one lock, and an off-by-one under contention would make the window
        // read LOW — the direction that lets a fleet walk into a block while the dashboard
        // says there is room. Exactness is the assertion, not absence of exceptions.
        int threads = 16;
        int perThread = 100;
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SlidingWindowGatewayBudget budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                GatewayBudgetSettings.defaults(), meters, frozenClock::get);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Map<RequestTier, Integer> expectedPerTier = new EnumMap<>(RequestTier.class);
        for (RequestTier tier : RequestTier.values()) {
            expectedPerTier.put(tier, 0);
        }

        for (int t = 0; t < threads; t++) {
            RequestTier tier = RequestTier.values()[t % RequestTier.values().length];
            expectedPerTier.merge(tier, perThread, Integer::sum);
            Thread.ofVirtual().name("budget-load-" + t).start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        budget.execute(tier, SCOPE, () -> "ok");
                    }
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS))
                .as("every admission returned — an observe admission that parks is the defect "
                        + "this test class exists to exclude")
                .isTrue();

        assertThat(failures).isEmpty();
        assertThat(budget.windowRequests()).isEqualTo(threads * perThread);
        for (RequestTier tier : RequestTier.values()) {
            assertThat(meters.get(SlidingWindowGatewayBudget.REQUESTS_TOTAL)
                    .tags("environmentId", "env-1", "product", "116", "tier", tier.name(),
                            "outcome", SlidingWindowGatewayBudget.OUTCOME_ADMITTED)
                    .counter().count())
                    .as("admitted{tier=%s} must agree with the window, or the dashboard lies "
                            + "about the one number this feature bounds", tier)
                    .isEqualTo(expectedPerTier.get(tier).doubleValue());
        }
        budget.shutdown();
    }

    @Test
    @DisplayName("a reservation larger than the whole window changes nothing in observe mode")
    void anOversizedReservationDoesNotThrottleAnythingInObserveMode() throws Exception {
        // Under enforce this reservation floors both lower ceilings at zero, which is the
        // intended strict priority (3,000 bots x 3 = 9,000 declared against a 900 cap is the
        // real shape) and is asserted in SlidingWindowGatewayBudgetReservationTest. In observe
        // it must be pure accounting: declaring demand sends nothing and refuses nothing.
        SlidingWindowGatewayBudget budget = budget(GatewayBudgetMode.OBSERVE);

        try (GatewayBudget.Reservation reservation =
                     budget.reserve(RequestTier.ESSENTIAL, 9_000, SCOPE)) {
            assertThat(budget.windowRequests()).isZero();
            for (int i = 0; i < 600; i++) {
                budget.execute(RequestTier.DEFAULT, SCOPE, () -> "ok");
            }
            assertThat(budget.windowRequests())
                    .as("600 DEFAULT requests against a 500 ceiling, with 9,000 ESSENTIAL "
                            + "declared — all admitted, because observe enforces nothing")
                    .isEqualTo(600);
            assertThat(reservation.remaining())
                    .as("observe consumes no permits either: the accounting exists so the "
                            + "gateway_budget_reserved gauge is real, not so it throttles")
                    .isEqualTo(9_000);
        }

        // close() is release(), so the AutoCloseable form is safe to use in production code
        // (startLocked's try/finally) without double-counting.
        assertThat(budget.snapshot().windowRequests()).isEqualTo(600);
        budget.shutdown();
    }

    @Test
    @DisplayName("a negative reservation is clamped rather than driving the gauge below zero")
    void aNegativeReservationIsClamped() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SlidingWindowGatewayBudget budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                GatewayBudgetSettings.defaults(), meters, frozenClock::get);

        // demand = botCount x 3, and botCount comes from a Mongo document. A zero or negative
        // count must not hand the lower tiers ceiling room that does not exist.
        GatewayBudget.Reservation reservation = budget.reserve(RequestTier.ESSENTIAL, -5, SCOPE);

        assertThat(reservation.remaining()).isZero();
        assertThat(meters.get(SlidingWindowGatewayBudget.RESERVED)
                .tags("environmentId", "env-1", "product", "116", "tier", "ESSENTIAL")
                .gauge().value()).isZero();
        reservation.release();
        assertThat(meters.get(SlidingWindowGatewayBudget.RESERVED)
                .tags("environmentId", "env-1", "product", "116", "tier", "ESSENTIAL")
                .gauge().value()).isZero();
        budget.shutdown();
    }
}
