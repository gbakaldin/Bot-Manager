package com.vingame.bot.infrastructure.gateway;

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

/**
 * <b>Phase 1 cannot block, delay, refuse or reorder anything — in either mode.</b>
 * <p>
 * This is the single most important property of the whole phase, because it is the promise
 * on which "deployable to ten prod environments unchanged" rests. The window is measured;
 * nothing else changes. {@code SlidingWindowGatewayBudgetWindowTest} covers the arithmetic
 * of the window; this class covers the negative: every entry point on the interface, driven
 * far past the hard cap, on a clock that never advances (so not one stamp can expire and
 * every enforcement rule that Phase 3 will add is maximally violated), still admits.
 * <p>
 * <b>{@code mode=enforce} is included deliberately.</b> An operator can set
 * {@code GATEWAY_BUDGET_MODE=enforce} on a box today — compose passes it, Spring binds it,
 * {@code GatewayBudgetMode.parse} accepts it — and Phase 3's enforcement does not exist yet.
 * The registry warns about it at startup, and what must be true underneath that warning is
 * that such a budget behaves <em>exactly</em> like an observe one rather than half-enforcing
 * through a code path nobody has written. A partial enforcement reachable by one environment
 * variable would be a behaviour change on a production fleet that no test asserted.
 * <p>
 * The {@link Timeout} is the assertion of last resort: if a future change ever makes an
 * admission park, these tests fail in seconds instead of hanging a CI run. Nothing here
 * sleeps, and nothing here can reach a socket — the calls are lambdas returning constants.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("SlidingWindowGatewayBudget — observe-only: nothing waits, nothing is refused")
class SlidingWindowGatewayBudgetObserveModeTest {

    /** Well past 900, and past every tier ceiling, so no admission rule can be satisfied. */
    private static final int FAR_PAST_THE_CAP = 1_500;

    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.forBot("group-1", "authtestws1", () -> false);

    /** A clock that never moves: no stamp can expire, so the window only ever grows. */
    private final AtomicLong frozenClock = new AtomicLong(0L);

    private SlidingWindowGatewayBudget budget(GatewayBudgetMode mode) {
        return new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                settings(mode), new SimpleMeterRegistry(), frozenClock::get);
    }

    private static GatewayBudgetSettings settings(GatewayBudgetMode mode) {
        GatewayBudgetSettings d = GatewayBudgetSettings.defaults();
        return new GatewayBudgetSettings(mode, d.window(), d.hardCap(), d.ceilings(),
                d.maxWaits(), d.registrationMaxWait(), d.countWsUpgrades(), d.blockCooldown());
    }

    @Test
    @DisplayName("every entry point admits far past the hard cap, in observe AND in enforce")
    void noEntryPointEverRefuses() throws Exception {
        for (GatewayBudgetMode mode : GatewayBudgetMode.values()) {
            SlidingWindowGatewayBudget budget = budget(mode);
            List<String> ran = new ArrayList<>();

            // Fill the window past the cap with the tier whose ceiling is LOWEST, so that
            // from Phase 3 every one of the calls below would be refused or queued.
            for (int i = 0; i < FAR_PAST_THE_CAP; i++) {
                budget.execute(RequestTier.DEFAULT, SCOPE, () -> "ok");
            }
            assertThat(budget.windowRequests())
                    .as("mode=%s: the window is measured, not bounded, in Phase 1", mode)
                    .isEqualTo(FAR_PAST_THE_CAP);

            // Now every remaining entry point, each at the tier that Phase 3 will be
            // strictest about, with the window already 600 requests past the hard cap.
            assertThat(budget.execute(RequestTier.DEFAULT, SCOPE, () -> "execute")).isEqualTo("execute");
            budget.run(RequestTier.DEFAULT, SCOPE, () -> ran.add("run"));
            budget.runWsUpgrade(RequestTier.DEFAULT, SCOPE, () -> ran.add("wsUpgrade"));
            Optional<String> tried =
                    budget.tryExecute(RequestTier.DEFAULT, SCOPE, () -> "try", Duration.ZERO);
            budget.count("ws-probe");
            assertThatCode(() -> budget.cancelScope("group-1"))
                    .as("mode=%s: cancelScope must be a no-op, not a throw — Phase 2 calls it "
                            + "from stop() before the queues it protects exist", mode)
                    .doesNotThrowAnyException();

            assertThat(ran).as("mode=%s", mode).containsExactly("run", "wsUpgrade");
            assertThat(tried)
                    .as("mode=%s: tryExecute(ZERO) is an execute in Phase 1 — the deferral "
                            + "semantics of AD-10 arrive with enforcement", mode)
                    .contains("try");
            assertThat(budget.windowRequests())
                    .as("mode=%s: four more requests left the JVM (execute, run, upgrade, try) "
                            + "plus one probe; a cancelScope sends nothing", mode)
                    .isEqualTo(FAR_PAST_THE_CAP + 5);
            assertThat(budget.snapshot().mode())
                    .as("the snapshot reports the configured mode honestly even though the "
                            + "behaviour is identical — that is what the startup WARN is about")
                    .isEqualTo(mode);
        }
    }

    @Test
    @DisplayName("a cancelled scope is admitted in enforce mode too — Phase 3 tripwire")
    void aCancelledScopeIsAdmittedInEitherMode() throws Exception {
        // The companion assertion in SlidingWindowGatewayBudgetWindowTest pins this for
        // observe. It is repeated for enforce because the mode is the switch a reader would
        // expect the pre-admission check to hang off, and it does not exist in either.
        // WHEN PHASE 3 LANDS, BOTH OF THESE TESTS MUST BE REWRITTEN, not deleted: the target
        // behaviour is that a cancelled scope is never stamped and the caller sees
        // GatewayRequestCancelledException.
        SlidingWindowGatewayBudget budget = budget(GatewayBudgetMode.ENFORCE);
        AtomicBoolean called = new AtomicBoolean();
        GatewayRequestScope cancelled =
                GatewayRequestScope.forBot("group-1", "bot1", () -> true);

        budget.execute(RequestTier.ESSENTIAL, cancelled, () -> {
            called.set(true);
            return "sent anyway";
        });

        assertThat(called).as("the request really was issued, so it really must be counted").isTrue();
        assertThat(budget.windowRequests()).isEqualTo(1);
    }

    @Test
    @DisplayName("the queue-depth gauges stay at zero however hard the budget is driven")
    void nothingIsEverQueued() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SlidingWindowGatewayBudget budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                settings(GatewayBudgetMode.ENFORCE), meters, frozenClock::get);

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
                    .as("no request may time out while nothing enforces")
                    .isZero();
            assertThat(meters.get(SlidingWindowGatewayBudget.REQUESTS_TOTAL)
                    .tags("environmentId", "env-1", "product", "116", "tier", tier.name(),
                            "outcome", SlidingWindowGatewayBudget.OUTCOME_CANCELLED)
                    .counter().count())
                    .isZero();
        }
        assertThat(budget.snapshot().describeForRollup())
                .isEqualTo("gateway=1500/900 queued=0/0/0 circuit=closed");
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
                settings(GatewayBudgetMode.OBSERVE), meters, frozenClock::get);

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
                .as("every admission returned — an admission that parks is the defect this "
                        + "whole test class exists to exclude")
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
    }

    @Test
    @DisplayName("a reservation larger than the whole window changes nothing in Phase 1")
    void anOversizedReservationDoesNotThrottleAnything() throws Exception {
        // Phase 3's arithmetic floors the lower ceilings at zero when a reservation exceeds
        // them (3,000 bots x 3 = 9,000 declared against a 900 cap is the real shape). Today
        // it must be pure accounting: declaring demand is not sending anything, and it must
        // not accidentally start refusing the tiers below.
        SlidingWindowGatewayBudget budget = budget(GatewayBudgetMode.ENFORCE);

        try (GatewayBudget.Reservation reservation =
                     budget.reserve(RequestTier.ESSENTIAL, 9_000, SCOPE)) {
            assertThat(budget.windowRequests()).isZero();
            for (int i = 0; i < 600; i++) {
                budget.execute(RequestTier.DEFAULT, SCOPE, () -> "ok");
            }
            assertThat(budget.windowRequests())
                    .as("600 DEFAULT requests against a 500 ceiling, with 9,000 ESSENTIAL "
                            + "declared — all admitted, because nothing enforces yet")
                    .isEqualTo(600);
            assertThat(reservation.remaining()).isEqualTo(9_000);
        }

        // close() is release(), so the AutoCloseable form is safe to use in production code
        // (startLocked's try/finally) without double-counting.
        assertThat(budget.snapshot().windowRequests()).isEqualTo(600);
    }

    @Test
    @DisplayName("a negative reservation is clamped rather than driving the gauge below zero")
    void aNegativeReservationIsClamped() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        SlidingWindowGatewayBudget budget = new SlidingWindowGatewayBudget("env-1", "Staging", "116",
                settings(GatewayBudgetMode.OBSERVE), meters, frozenClock::get);

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
    }
}
