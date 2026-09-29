package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.exception.GatewayCircuitOpenException;
import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

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
 * <b>An open circuit refuses every tier — ESSENTIAL included — and parks none of them</b>
 * (GATEWAY_REQUEST_BUDGET A16.2, A20.1).
 * <p>
 * <b>Why this file is QA's and not a Phase 5 concern.</b> The rule A16.2 states is the one that
 * decides whether this feature can wedge a fleet: <em>an unbounded wait is admissible only where
 * progress is guaranteed.</em> The sliding window guarantees it (stamps expire, so a 3,000-bot
 * start is ~10 windows of monotonic progress) which is why
 * {@code bot.gateway.budget.tier.essential.max-wait=0} is legal for ESSENTIAL alone. A Cloudflare
 * edge block guarantees nothing — the user's answer on its lifetime is "possibly ~24 hours,
 * possibly until someone clears it manually" — so parking an ESSENTIAL waiter on one parks a bot
 * thread, a {@code bot.creation.parallelism} permit and (through the group lock) any {@code /stop}
 * or {@code DELETE} behind it, for up to a day. That is {@code FOLLOWUPS.md} P13 at fleet scale.
 * <p>
 * Phase 3 implemented the refusal deliberately ahead of the detector that triggers it, "so the two
 * are not designed apart". But {@code circuitOpen} is a private {@link AtomicBoolean} that nothing
 * yet writes, so <b>every branch guarding it shipped with no test reaching it</b>: the whole of
 * A16.2 rested on reading the source. The flag is flipped here through reflection, which is the
 * only seam available and is worth using — the alternative is that Phase 5 inherits four untested
 * branches and discovers on staging whether ESSENTIAL parks on a block.
 * <p>
 * Nothing here sleeps and nothing here can reach a socket: the calls are lambdas returning
 * constants, and the {@link Timeout} is the assertion of last resort — <b>if a tier ever parks on
 * an open circuit this file hangs, and the timeout is what converts that into a failure.</b>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("SlidingWindowGatewayBudget — an open circuit refuses every tier, parking none")
class SlidingWindowGatewayBudgetCircuitRefusalTest {

    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.forBot("group-1", "authtestws1", () -> false);

    private AtomicLong clock;
    private SimpleMeterRegistry meters;
    private SlidingWindowGatewayBudget budget;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(0L);
        meters = new SimpleMeterRegistry();
        budget = enforcing(GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE), meters);
    }

    @AfterEach
    void tearDown() {
        budget.shutdown();
    }

    private SlidingWindowGatewayBudget enforcing(GatewayBudgetSettings settings, SimpleMeterRegistry registry) {
        return new SlidingWindowGatewayBudget("env-1", "Staging", "116", settings, registry, clock::get);
    }

    /**
     * Flip the circuit the way Phase 5's {@code reportEdgeBlock} will. Reflection is the only
     * seam: the field is private and nothing writes it yet, which is precisely why these branches
     * were unreached.
     */
    private static void openCircuit(SlidingWindowGatewayBudget budget) {
        AtomicBoolean flag = (AtomicBoolean) ReflectionTestUtils.getField(budget, "circuitOpen");
        assertThat(flag).as("the circuit flag is where the refusal path reads it").isNotNull();
        flag.set(true);
    }

    private double counter(RequestTier tier, String outcome) {
        return meters.get(SlidingWindowGatewayBudget.REQUESTS_TOTAL)
                .tags("environmentId", "env-1", "product", "116", "tier", tier.name(), "outcome", outcome)
                .counter().count();
    }

    @Test
    @DisplayName("every tier is refused with a 503-shaped exception, and ESSENTIAL does not park")
    void everyTierIsRefusedAndEssentialDoesNotPark() {
        openCircuit(budget);

        for (RequestTier tier : RequestTier.values()) {
            AtomicBoolean called = new AtomicBoolean();
            // ESSENTIAL's configured wait is 0 = unbounded. If the circuit parked it instead of
            // refusing it, THIS CALL WOULD NEVER RETURN and the class timeout would fail the run
            // — which is the point: the assertion is as much "it returned" as "it threw".
            assertThatThrownBy(() -> budget.execute(tier, SCOPE, () -> {
                called.set(true);
                return "must not be sent";
            }))
                    .as("tier %s: an open circuit refuses rather than queueing, whatever its "
                            + "max-wait says — the block may outlive a day", tier)
                    .isInstanceOf(GatewayCircuitOpenException.class);
            assertThat(called).as("tier %s: nothing may leave the JVM", tier).isFalse();
            assertThat(counter(tier, SlidingWindowGatewayBudget.OUTCOME_CIRCUIT_OPEN))
                    .as("tier %s: counted as circuit_open, not as a timeout — an operator must be "
                            + "able to tell 'we paced ourselves' from 'the edge is refusing us'", tier)
                    .isEqualTo(1.0);
            assertThat(counter(tier, SlidingWindowGatewayBudget.OUTCOME_TIMEOUT)).isZero();
        }

        assertThat(budget.windowRequests())
                .as("nothing is stamped: a refused request cost the edge nothing, and stamping it "
                        + "would make the app throttle itself for traffic it never sent")
                .isZero();
        assertThat(budget.snapshot().circuitOpen()).isTrue();
        assertThat(budget.snapshot().describeForRollup())
                .as("the rollup line is how an operator sees it on the 5-minute tier-2 line")
                .isEqualTo("gateway=0/900 queued=0/0/0 circuit=open");
    }

    @Test
    @DisplayName("run() and runWsUpgrade() are refused too, even when upgrades are not counted")
    void theUncheckedEntryPointsAreRefusedAsWell() {
        // A5.2: "not counted by the edge" implies "not paced by us"; it does NOT imply "sent into
        // an open circuit", because the edge is refusing this host whatever it counts. That is the
        // one branch in runWsUpgrade's else-arm, and it had no test.
        SlidingWindowGatewayBudget uncounted = enforcing(GatewayBudgetSettings.defaults()
                .withMode(GatewayBudgetMode.ENFORCE)
                .withCountWsUpgrades(false), new SimpleMeterRegistry());
        try {
            openCircuit(uncounted);
            assertThatThrownBy(() -> uncounted.run(RequestTier.ESSENTIAL, SCOPE, () -> {
                throw new AssertionError("run() must not reach its call while the edge is blocking");
            })).isInstanceOf(GatewayCircuitOpenException.class);
            assertThatThrownBy(() -> uncounted.runWsUpgrade(RequestTier.PRIORITIZED, SCOPE, () -> {
                throw new AssertionError("an upgrade must not reach connect() while the edge is blocking");
            })).isInstanceOf(GatewayCircuitOpenException.class);
            assertThat(uncounted.windowRequests()).isZero();
        } finally {
            uncounted.shutdown();
        }
    }

    @Test
    @DisplayName("a soft caller gets empty rather than an exception, so no block reaches the message pipeline")
    void aDeferrableReadIsRefusedSoftly() throws Exception {
        // AD-10's caller is the drift balance read, which runs on a ws-parser message-processor
        // thread. Throwing there would break onNewSession for the round; the honest answer is
        // "no fresh figure", which is what the bot's balanceReadDeferred flag then records.
        openCircuit(budget);

        Optional<String> read = budget.tryExecute(RequestTier.DEFAULT, SCOPE, () -> "fresh", Duration.ZERO);

        assertThat(read).isEmpty();
        assertThat(counter(RequestTier.DEFAULT, SlidingWindowGatewayBudget.OUTCOME_CIRCUIT_OPEN))
                .isEqualTo(1.0);
        assertThat(budget.windowRequests()).isZero();
    }

    @Test
    @DisplayName("a waiter already queued when the circuit opens is not admitted by a pass")
    void anAlreadyQueuedWaiterIsNotAdmittedWhileTheCircuitIsOpen() throws Exception {
        // The admission pass has its own circuitOpen check, separate from the arrival path's, and
        // it is the one that matters during an outage: the queue is full of bots that were parked
        // on the window a moment before the block was recognised. Admitting them would send a
        // burst straight into the wall that is already refusing us — the exact behaviour that kept
        // the observed block alive.
        //
        // A bounded wait, so the test cannot hang if the property is broken; the assertion is that
        // it was NOT admitted before the clock is moved, not the shape of its eventual failure.
        SlidingWindowGatewayBudget bounded = enforcing(GatewayBudgetSettings.defaults()
                .withMode(GatewayBudgetMode.ENFORCE)
                .withMaxWait(RequestTier.ESSENTIAL, Duration.ofSeconds(2)), new SimpleMeterRegistry());
        try {
            for (int i = 0; i < 900; i++) {
                bounded.execute(RequestTier.ESSENTIAL, SCOPE, () -> "ok");
            }
            AtomicBoolean sent = new AtomicBoolean();
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread.ofVirtual().name("queued-waiter").start(() -> {
                try {
                    bounded.execute(RequestTier.ESSENTIAL, SCOPE, () -> {
                        sent.set(true);
                        return "sent";
                    }, Duration.ofSeconds(2));
                } catch (Throwable t) {
                    outcome.set(t);
                } finally {
                    done.countDown();
                }
            });
            for (int i = 0; i < 400 && bounded.snapshot().queuedEssential() < 1; i++) {
                Thread.sleep(5);
            }
            assertThat(bounded.snapshot().queuedEssential()).isEqualTo(1);

            // Now the block is recognised, and the whole window expires. Without the pass's own
            // check, the freed room would release the parked bot into the block.
            openCircuit(bounded);
            clock.set(Duration.ofMinutes(5).toNanos() + 1);
            bounded.admitWaiters();

            assertThat(sent)
                    .as("the freed window must NOT release a queued bot into an edge that is "
                            + "actively refusing this host")
                    .isFalse();
            assertThat(done.await(15, TimeUnit.SECONDS))
                    .as("and it must still come back — a waiter left parked on a block is the "
                            + "wedge A16.2 exists to prevent")
                    .isTrue();
            assertThat(outcome.get()).isNotNull();
            assertThat(sent).isFalse();
        } finally {
            bounded.shutdown();
        }
    }

    @Test
    @DisplayName("observe mode refuses nothing, even with the circuit open")
    void observeModeNeverRefusesOnACircuit() throws Exception {
        // The mode is an environment variable and `observe` is the shipped default, so a box that
        // has not opted into enforcement must behave exactly as Phase 1 did — including when
        // Phase 5's detector starts flipping this flag on a JVM running observe. A16: "in observe
        // mode detection logs a WARN and opens nothing", and even if something did open it, the
        // request must still go out.
        SlidingWindowGatewayBudget observing = enforcing(GatewayBudgetSettings.defaults(), new SimpleMeterRegistry());
        try {
            openCircuit(observing);
            assertThat(observing.execute(RequestTier.ESSENTIAL, SCOPE, () -> "sent")).isEqualTo("sent");
            observing.run(RequestTier.DEFAULT, SCOPE, () -> { });
            observing.runWsUpgrade(RequestTier.PRIORITIZED, SCOPE, () -> { });
            assertThat(observing.tryExecute(RequestTier.DEFAULT, SCOPE, () -> "try", Duration.ZERO))
                    .contains("try");
            assertThat(observing.windowRequests())
                    .as("four requests really were issued, so four must be counted")
                    .isEqualTo(4);
        } finally {
            observing.shutdown();
        }
    }
}
