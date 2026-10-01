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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>The cap holds, whatever the tier mix</b> — the one property the whole feature exists for
 * (GATEWAY_REQUEST_BUDGET AD-4, AD-5, AD-6), on a manual clock.
 * <p>
 * Nothing here sleeps and nothing here can reach a socket. That is not a speed preference: a
 * test of this class that made a real request could get a whole brand blocked at the Cloudflare
 * edge, and the user has confirmed there is <b>no tolerable cooldown</b> for that block — it may
 * be ~24 hours, or until someone clears it by hand. Every "wait" below is a waiter parked on a
 * future that the test itself releases by moving the clock and driving one admission pass.
 * <p>
 * The {@link Timeout} is the assertion of last resort: an admission that parks when it should
 * not fails here in seconds rather than hanging a CI run.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("SlidingWindowGatewayBudget — enforce: ceilings, hard cap, priority, FIFO")
class SlidingWindowGatewayBudgetAdmissionTest {

    private static final Duration WINDOW = Duration.ofMinutes(5);

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

    private static GatewayRequestScope scope(String group) {
        return GatewayRequestScope.forBot(group, "authtestws1", () -> false);
    }

    /** Fill the window with {@code n} admissions at {@code tier}, asserting each one lands. */
    private void fill(RequestTier tier, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            budget.execute(tier, scope("group-1"), () -> "ok");
        }
    }

    private double counter(RequestTier tier, String outcome) {
        return meters.get(SlidingWindowGatewayBudget.REQUESTS_TOTAL)
                .tags("environmentId", "env-1", "product", "116", "tier", tier.name(), "outcome", outcome)
                .counter().count();
    }

    @Test
    @DisplayName("DEFAULT stops at 500, PRIORITIZED at 750, ESSENTIAL at 900")
    void eachTierStopsAtItsOwnCeiling() throws Exception {
        // DEFAULT's 500 is reached first, and a DEFAULT arrival with a zero wait is refused
        // there even though there are 400 requests of room left in the window — the gap IS the
        // reservation for the tiers above it.
        fill(RequestTier.DEFAULT, 500);
        assertThat(budget.windowRequests()).isEqualTo(500);
        assertThatThrownBy(() -> budget.execute(RequestTier.DEFAULT, scope("group-1"),
                () -> "ok", Duration.ZERO))
                .isInstanceOf(GatewayBudgetExhaustedException.class);

        // PRIORITIZED can still use the next 250 — that is what the gap was being held for.
        fill(RequestTier.PRIORITIZED, 250);
        assertThat(budget.windowRequests()).isEqualTo(750);
        assertThatThrownBy(() -> budget.execute(RequestTier.PRIORITIZED, scope("group-1"),
                () -> "ok", Duration.ZERO))
                .isInstanceOf(GatewayBudgetExhaustedException.class);

        // ESSENTIAL gets the last 150, and then nothing does.
        fill(RequestTier.ESSENTIAL, 150);
        assertThat(budget.windowRequests()).isEqualTo(900);
        assertThat(budget.windowRequests())
                .as("the hard cap. 900 against Cloudflare's 1,000 is the only margin for traffic "
                        + "this JVM cannot see — bulk-create-accounts.py on the same host, a "
                        + "second instance sharing the egress IP — and it is the margin a "
                        + "day-long block is measured against. Do NOT raise it.")
                .isEqualTo(900);
    }

    @Test
    @DisplayName("nothing passes the hard cap whatever the tier mix")
    void theHardCapHoldsForEveryTierMix() throws Exception {
        // Round-robin across all three tiers, 1,200 attempts with a zero wait, on a frozen
        // clock so not one stamp can expire. Whatever order they arrive in, the window is 900.
        int refused = 0;
        for (int i = 0; i < 1_200; i++) {
            RequestTier tier = RequestTier.values()[i % RequestTier.values().length];
            try {
                budget.execute(tier, scope("group-1"), () -> "ok", Duration.ZERO);
            } catch (GatewayBudgetExhaustedException e) {
                refused++;
            }
        }

        assertThat(budget.windowRequests())
                .as("this is the assertion the whole plan exists for")
                .isLessThanOrEqualTo(900);
        assertThat(refused).isPositive();
        // Refusals are counted and admissions are counted, and together they account for every
        // attempt — the counter and the gauge must agree or the dashboard lies.
        double admitted = 0;
        double timeouts = 0;
        for (RequestTier tier : RequestTier.values()) {
            admitted += counter(tier, SlidingWindowGatewayBudget.OUTCOME_ADMITTED);
            timeouts += counter(tier, SlidingWindowGatewayBudget.OUTCOME_TIMEOUT);
        }
        assertThat(admitted).isEqualTo((double) budget.windowRequests());
        assertThat(admitted + timeouts).isEqualTo(1_200.0);
    }

    @Test
    @DisplayName("an ESSENTIAL arriving after 100 queued DEFAULTs is admitted first")
    void strictPriorityAcrossTiers() throws Exception {
        // The window is full for DEFAULT (500) but not for ESSENTIAL (900), so 100 queued
        // DEFAULT waiters must not delay a group start by one request.
        fill(RequestTier.DEFAULT, 500);

        CountDownLatch queued = new CountDownLatch(100);
        CountDownLatch unwound = new CountDownLatch(100);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 100; i++) {
            Thread.ofVirtual().name("default-waiter-" + i).start(() -> {
                try {
                    queued.countDown();
                    budget.execute(RequestTier.DEFAULT, scope("group-1"), () -> "ok",
                            Duration.ofSeconds(20));
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    unwound.countDown();
                }
            });
        }
        assertThat(queued.await(10, TimeUnit.SECONDS)).isTrue();
        // Wait for the queue depth to settle rather than for a wall-clock interval.
        for (int i = 0; i < 200 && budget.snapshot().queuedDefault() < 100; i++) {
            Thread.sleep(10);
        }
        assertThat(budget.snapshot().queuedDefault()).isEqualTo(100);

        // The ESSENTIAL request does not queue at all: its own queue is empty and its own
        // ceiling has room, so it is admitted synchronously with 100 DEFAULTs still parked.
        assertThat(budget.execute(RequestTier.ESSENTIAL, scope("group-1"), () -> "start"))
                .isEqualTo("start");
        assertThat(budget.windowRequests()).isEqualTo(501);
        assertThat(budget.snapshot().queuedDefault())
                .as("admitting the ESSENTIAL must not have released any DEFAULT — it consumed "
                        + "room, it did not free any")
                .isEqualTo(100);

        // Await the unwind rather than sampling the list: cancelScope completes 100 futures, and
        // each waiter's continuation then runs on its own virtual thread. Asserting the list size
        // without a barrier is a race that fails ~30% of the time in a full-suite run.
        budget.cancelScope("group-1");
        assertThat(unwound.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).hasSize(100);
    }

    @Test
    @DisplayName("FIFO within a tier: the freed slot goes to the waiter that arrived first")
    void fifoWithinATier() throws Exception {
        // Room is freed ONE slot at a time, and the test waits for each admitted waiter to run
        // before freeing the next. That is what makes this deterministic: the budget admits in
        // deque order under its lock, but each admitted waiter's continuation then runs on its own
        // virtual thread, so recording the order from inside the calls would be recording the
        // order the SCHEDULER happened to resume them in — which is how the first version of this
        // test produced ["first", "third", "second"] roughly one run in twenty.
        //
        // Three stamps one second apart, then the rest of the ceiling, so exactly one stamp
        // expires per clock step.
        budget.execute(RequestTier.DEFAULT, scope("group-1"), () -> "ok");
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        budget.execute(RequestTier.DEFAULT, scope("group-1"), () -> "ok");
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        budget.execute(RequestTier.DEFAULT, scope("group-1"), () -> "ok");
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        fill(RequestTier.DEFAULT, 497);
        assertThat(budget.windowRequests()).isEqualTo(500);

        List<String> admittedOrder = new CopyOnWriteArrayList<>();
        List<String> arrivalOrder = List.of("first", "second", "third");
        CountDownLatch done = new CountDownLatch(3);
        for (String name : arrivalOrder) {
            int expectedDepth = arrivalOrder.indexOf(name) + 1;
            Thread.ofVirtual().name("waiter-" + name).start(() -> {
                try {
                    budget.execute(RequestTier.DEFAULT, scope("group-1"), () -> admittedOrder.add(name),
                            Duration.ofSeconds(20));
                } catch (Exception e) {
                    // any failure shows up as a missing entry in admittedOrder
                } finally {
                    done.countDown();
                }
            });
            // Serialise enqueueing so "arrival order" is a fact of the test, not a race.
            for (int i = 0; i < 400 && budget.snapshot().queuedDefault() < expectedDepth; i++) {
                Thread.sleep(5);
            }
            assertThat(budget.snapshot().queuedDefault()).isEqualTo(expectedDepth);
        }

        // One slot at a time, with a barrier after each.
        for (int step = 0; step < 3; step++) {
            clock.set(WINDOW.toNanos() + Duration.ofSeconds(step).toNanos() + 1);
            budget.admitWaiters();
            int expected = step + 1;
            for (int i = 0; i < 400 && admittedOrder.size() < expected; i++) {
                Thread.sleep(5);
            }
            assertThat(admittedOrder)
                    .as("exactly one waiter per freed slot, at step %d", step)
                    .hasSize(expected);
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(admittedOrder)
                .as("FIFO within a tier is what stops the head of a busy queue starving behind "
                        + "later arrivals — under pacing the head is the oldest bot of a start")
                .containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("a slot freed by expiry wakes exactly the right head, and only one of them")
    void oneFreedSlotWakesOneWaiter() throws Exception {
        // One admission at t=0, then fill to the ESSENTIAL ceiling at t=1s, so exactly one
        // stamp expires one window after t=0 and the rest a second later.
        budget.execute(RequestTier.ESSENTIAL, scope("group-1"), () -> "first");
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        fill(RequestTier.ESSENTIAL, 899);
        assertThat(budget.windowRequests()).isEqualTo(900);

        CountDownLatch admitted = new CountDownLatch(1);
        List<String> results = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 5; i++) {
            String name = "waiter-" + i;
            Thread.ofVirtual().name(name).start(() -> {
                try {
                    budget.execute(RequestTier.ESSENTIAL, scope("group-1"), () -> {
                        results.add(name);
                        admitted.countDown();
                        return name;
                    });
                } catch (Exception e) {
                    // cancelled at the end of the test
                }
            });
        }
        for (int i = 0; i < 300 && budget.snapshot().queuedEssential() < 5; i++) {
            Thread.sleep(10);
        }
        assertThat(budget.snapshot().queuedEssential()).isEqualTo(5);

        // Expire the t=0 stamp only.
        clock.set(WINDOW.toNanos() + 1);
        budget.admitWaiters();
        assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();

        // Settle, then assert that exactly ONE waiter went through. Waking all five would put
        // the window at 904 — over the cap, which is the failure mode that matters.
        Thread.sleep(50);
        assertThat(results).hasSize(1);
        assertThat(budget.windowRequests()).isEqualTo(900);
        assertThat(budget.snapshot().queuedEssential()).isEqualTo(4);

        budget.cancelScope("group-1");
    }

    @Test
    @DisplayName("run() and runWsUpgrade() are paced too, not only execute()")
    void everyEntryPointIsPaced() throws Exception {
        // A5.1. Every WS upgrade in the fleet goes through runWsUpgrade, and A15 closed Open
        // Item 1 as "the WS hosts are behind the same rule": the observed staging hot loop was
        // ~180 watchdog reconnects per 5-minute window from two sick groups, ~18% of the entire
        // Cloudflare allowance. Enforcement that reached execute() only would stamp those and
        // pace none of them, which makes a reconnect storm a block waiting to happen.
        //
        // Neither run() nor runWsUpgrade() takes a wait — they use their tier's configured one,
        // and ESSENTIAL's is 0 = unbounded, which is exactly right in production (the window
        // drains by construction, so an ESSENTIAL upgrade parks until there is room rather than
        // failing a bot that is coming up) and unusable in a test. So this budget gives both
        // tiers a 100 ms wait: the property under test is "the call is not reached", not the
        // length of the wait, which SlidingWindowGatewayBudgetWaitTest owns.
        SlidingWindowGatewayBudget bounded = new SlidingWindowGatewayBudget("env-3", "Staging", "116",
                GatewayBudgetSettings.defaults()
                        .withMode(GatewayBudgetMode.ENFORCE)
                        .withMaxWait(RequestTier.ESSENTIAL, Duration.ofMillis(100))
                        .withMaxWait(RequestTier.PRIORITIZED, Duration.ofMillis(100)),
                new SimpleMeterRegistry(), clock::get);
        try {
            for (int i = 0; i < 900; i++) {
                bounded.execute(RequestTier.ESSENTIAL, scope("group-1"), () -> "ok");
            }

            assertThatThrownBy(() -> bounded.run(RequestTier.ESSENTIAL, scope("group-1"), () -> {
                throw new AssertionError("run() must not reach its call past the hard cap");
            })).isInstanceOf(GatewayBudgetExhaustedException.class);

            assertThatThrownBy(() -> bounded.runWsUpgrade(RequestTier.PRIORITIZED, scope("group-1"), () -> {
                throw new AssertionError("runWsUpgrade() must not reach connect() past the hard cap");
            })).isInstanceOf(GatewayBudgetExhaustedException.class);

            assertThat(bounded.windowRequests()).isEqualTo(900);
        } finally {
            bounded.shutdown();
        }
    }

    @Test
    @DisplayName("count-ws-upgrades=false: the upgrade is neither paced nor counted, but is still cancellable")
    void anUncountedUpgradeIsStillCancellable() throws Exception {
        // A5.2's decision, so nobody has to guess it from the flag's name. "Not counted by the
        // edge" ⇒ "not paced by us"; it does NOT mean "un-cancellable", or /stop's promptness
        // would depend on a flag about Cloudflare's accounting.
        SlidingWindowGatewayBudget uncounted = new SlidingWindowGatewayBudget("env-2", "Staging", "116",
                GatewayBudgetSettings.defaults()
                        .withMode(GatewayBudgetMode.ENFORCE)
                        .withCountWsUpgrades(false),
                new SimpleMeterRegistry(), clock::get);
        try {
            // Window far past the cap: an upgrade still goes out, because the edge does not
            // count it and pacing it would buy nothing.
            for (int i = 0; i < 900; i++) {
                uncounted.execute(RequestTier.ESSENTIAL, scope("group-1"), () -> "ok");
            }
            boolean[] upgraded = {false};
            uncounted.runWsUpgrade(RequestTier.ESSENTIAL, scope("group-1"), () -> upgraded[0] = true);
            assertThat(upgraded[0]).isTrue();
            assertThat(uncounted.windowRequests())
                    .as("the upgrade consumed nothing — that is what the flag means")
                    .isEqualTo(900);

            // ...and a cancelled scope's upgrade is still refused before connect() is entered,
            // which is the only place it CAN be refused: connect() swallows InterruptedException
            // and returns a half-built client.
            GatewayRequestScope cancelled =
                    GatewayRequestScope.forBot("group-1", "authtestws1", () -> true);
            assertThatThrownBy(() -> uncounted.runWsUpgrade(RequestTier.ESSENTIAL, cancelled, () -> {
                throw new AssertionError("a cancelled scope must not reach connect()");
            })).isInstanceOf(com.vingame.bot.common.exception.GatewayRequestCancelledException.class);
        } finally {
            uncounted.shutdown();
        }
    }

    @Test
    @DisplayName("countWsUpgrade honours the same flag, so the probe's stamp follows one answer")
    void theProbeStampFollowsTheFlag() {
        budget.countWsUpgrade("ws-probe");
        assertThat(budget.windowRequests())
                .as("count-ws-upgrades=true is now FACT (A15), not a conservative guess")
                .isEqualTo(1);

        SlidingWindowGatewayBudget uncounted = new SlidingWindowGatewayBudget("env-2", "Staging", "116",
                GatewayBudgetSettings.defaults().withCountWsUpgrades(false),
                new SimpleMeterRegistry(), clock::get);
        try {
            uncounted.countWsUpgrade("ws-probe");
            assertThat(uncounted.windowRequests())
                    .as("A5.3: answering Open Item 1 'no' must silence the probe's stamp too, or "
                            + "one flag governs a decision in two halves")
                    .isZero();
            // The HTTP probe form stays unconditional — a verifytoken GET is an HTTP request
            // whatever the answer about WS upgrades is.
            uncounted.count("circuit-probe");
            assertThat(uncounted.windowRequests()).isEqualTo(1);
        } finally {
            uncounted.shutdown();
        }
    }

    @Test
    @DisplayName("a pass that throws after admitting a waiter still releases that waiter (d760f6d S1)")
    void aPassThatThrowsStillReleasesTheWaitersItAdmitted() throws Exception {
        // review-phase4-fixround: S1 had no test. The pass admits an ESSENTIAL waiter (dequeued,
        // stamped, completion deferred) and THEN throws on the PRIORITIZED head's predicate. With
        // a returned list the completion was lost on the throw and the ESSENTIAL waiter — unbounded
        // by design — parked for the life of the JVM. With the caller-owned list it runs from the
        // unlocking finally.
        fill(RequestTier.ESSENTIAL, 900);

        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        GatewayRequestScope throwsFromSecondCall = GatewayRequestScope.forBot("group-p", "bot-p", () -> {
            if (calls.incrementAndGet() >= 2) {
                throw new IllegalStateException("predicate broke inside the pass");
            }
            return false;
        });
        CountDownLatch prioritizedDone = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                // Bounded, so this thread finishes on its own: after the throw this head wedges
                // every later pass (QA G-3), which is the documented contract, not under test here.
                budget.execute(RequestTier.PRIORITIZED, throwsFromSecondCall, () -> "p", Duration.ofSeconds(2));
            } catch (Exception ignored) {
                // timeout, expected
            } finally {
                prioritizedDone.countDown();
            }
        });
        CountDownLatch essentialDone = new CountDownLatch(1);
        List<Object> essentialResult = new CopyOnWriteArrayList<>();
        Thread.ofVirtual().start(() -> {
            try {
                essentialResult.add(budget.execute(RequestTier.ESSENTIAL, scope("group-e"), () -> "sent"));
            } catch (Exception e) {
                essentialResult.add(e);
            } finally {
                essentialDone.countDown();
            }
        });
        for (int i = 0; i < 400 && (budget.snapshot().queuedEssential() < 1
                || budget.snapshot().queuedPrioritized() < 1); i++) {
            Thread.sleep(5);
        }
        assertThat(budget.snapshot().queuedPrioritized()).isEqualTo(1);
        assertThat(budget.snapshot().queuedEssential()).isEqualTo(1);

        // The whole window expires; the pass admits E first (strict priority), then throws on P.
        clock.set(WINDOW.toNanos() + 1);
        assertThatThrownBy(() -> budget.admitWaiters()).isInstanceOf(IllegalStateException.class);

        assertThat(essentialDone.await(5, TimeUnit.SECONDS))
                .as("the waiter the pass admitted before it threw must not be stranded")
                .isTrue();
        assertThat(essentialResult).containsExactly("sent");
        assertThat(prioritizedDone.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("UNLIMITED has no wait policy: maxWait is null, never ZERO (d760f6d S4)")
    void unlimitedMaxWaitIsNull() {
        // ZERO from a caller means "now or never" (review F5); answering it here gave it a third
        // meaning. review-phase4-fixround: nothing asserted it.
        for (RequestTier tier : RequestTier.values()) {
            assertThat(GatewayBudget.UNLIMITED.maxWait(tier)).as("%s", tier).isNull();
        }
    }
}
