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

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The circuit breaker's state machine, driven by the real trigger — {@link
 * SlidingWindowGatewayBudget#reportEdgeBlock} — on a manual clock (GATEWAY_REQUEST_BUDGET AD-13 as
 * amended by A16, plan Phase 5 tests, A29.1).
 * <p>
 * <b>What A29.1 asked for and where it is.</b> Phase 3 shipped the refusal half of the circuit with
 * no way to reach it ({@code circuitOpen} had no setter). This file reaches it the way production
 * will: {@code admit} refusing every tier, ESSENTIAL included, and answering {@code false} to a soft
 * caller; {@code refuseIfCircuitOpen} on the {@code count-ws-upgrades=false} upgrade path; and
 * {@code outcome="circuit_open"} on the counter. The pass's treatment of a waiter that was already
 * queued when the circuit opened is pinned separately, in
 * {@code SlidingWindowGatewayBudgetCircuitRefusalTest}.
 * <p>
 * Nothing here touches a socket: the clearance probe is a lambda.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("Gateway circuit breaker — a block opens it, every tier is refused, only a probe closes it")
class GatewayCircuitBreakerTest {

    private static final GatewayRequestScope SCOPE =
            GatewayRequestScope.forBot("group-1", "authtestws1", () -> false);
    private static final Duration INTERVAL = GatewayBudgetSettings.BLOCK_PROBE_INTERVAL_DEFAULT;
    private static final String RAY = CapturedBlockPage.CF_RAY;

    private AtomicLong clock;
    private SimpleMeterRegistry meters;
    private SlidingWindowGatewayBudget budget;
    private AtomicInteger probes;
    private AtomicReference<Object> nextProbeAnswer;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000_000L);
        meters = new SimpleMeterRegistry();
        budget = new SlidingWindowGatewayBudget("env-1", "Staging", "119",
                GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE), meters, clock::get);
        probes = new AtomicInteger();
        nextProbeAnswer = new AtomicReference<>(blocked());
        budget.bindCircuitProbe(() -> {
            probes.incrementAndGet();
            Object answer = nextProbeAnswer.get();
            if (answer instanceof IOException io) {
                throw io;
            }
            return (CircuitProbe.Answer) answer;
        });
    }

    @AfterEach
    void tearDown() {
        budget.shutdown();
    }

    private static CircuitProbe.Answer blocked() {
        return new CircuitProbe.Answer(403, new CloudflareBlockDetector.Verdict(true, "probe-ray-HKG"));
    }

    private static CircuitProbe.Answer gatewayAnswered() {
        // The gateway's own JSON error for the anonymous token: not a block, so it closes the circuit.
        return new CircuitProbe.Answer(200, CloudflareBlockDetector.Verdict.NOT_A_BLOCK);
    }

    private double counter(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }

    private double gauge() {
        return meters.get(SlidingWindowGatewayBudget.CIRCUIT_OPEN).gauge().value();
    }

    private double outcome(RequestTier tier, String outcome) {
        return counter(SlidingWindowGatewayBudget.REQUESTS_TOTAL, "tier", tier.name(), "outcome", outcome);
    }

    private void openTheCircuit() {
        assertThatThrownBy(() -> budget.reportEdgeBlock(GatewayEndpoint.LOGIN, RAY))
                .isInstanceOf(GatewayCircuitOpenException.class);
    }

    @Test
    @DisplayName("a block opens the circuit, and the detecting request fails as a 503-shaped budget outcome")
    void aBlockOpensTheCircuit() {
        assertThat(gauge()).as("pre-registered at zero").isZero();
        assertThat(counter(SlidingWindowGatewayBudget.EDGE_BLOCKS_TOTAL, "endpoint", "login")).isZero();

        assertThatThrownBy(() -> budget.reportEdgeBlock(GatewayEndpoint.LOGIN, RAY))
                .isInstanceOfSatisfying(GatewayCircuitOpenException.class, e -> {
                    assertThat(e.getCfRay()).isEqualTo(RAY);
                    assertThat(e.getRetryAfter())
                            .as("Retry-After is when we will next ASK, not when it will work (A16.3)")
                            .isEqualTo(INTERVAL);
                    assertThat(e.getMessage()).contains("may last a day");
                });

        assertThat(budget.snapshot().circuitOpen()).isTrue();
        assertThat(gauge()).isEqualTo(1.0);
        assertThat(counter(SlidingWindowGatewayBudget.CIRCUIT_OPENED_TOTAL)).isEqualTo(1.0);
        assertThat(counter(SlidingWindowGatewayBudget.EDGE_BLOCKS_TOTAL, "endpoint", "login")).isEqualTo(1.0);
        assertThat(budget.snapshot().describeForRollup()).endsWith("circuit=open");
    }

    @Test
    @DisplayName("while open every tier is refused at once — ESSENTIAL included, nothing parks, nothing is sent")
    void everyTierIsRefused() throws Exception {
        openTheCircuit();

        for (RequestTier tier : RequestTier.values()) {
            AtomicInteger sent = new AtomicInteger();
            // ESSENTIAL's max-wait is unbounded. If the circuit parked it, this would never return
            // and the class timeout would fail the run (A16.2).
            assertThatThrownBy(() -> budget.execute(tier, SCOPE, sent::incrementAndGet))
                    .as("tier %s", tier)
                    .isInstanceOf(GatewayCircuitOpenException.class);
            assertThat(sent).hasValue(0);
            assertThat(outcome(tier, SlidingWindowGatewayBudget.OUTCOME_CIRCUIT_OPEN))
                    .as("tier %s counted as circuit_open, never as a timeout", tier)
                    .isEqualTo(1.0);
            assertThat(outcome(tier, SlidingWindowGatewayBudget.OUTCOME_TIMEOUT)).isZero();
        }

        assertThat(budget.tryExecute(RequestTier.DEFAULT, SCOPE, () -> "fresh", Duration.ZERO))
                .as("a soft caller (the drift read on a message-processor thread) gets empty, not a throw")
                .isEmpty();
        assertThat(budget.windowRequests()).as("nothing refused was stamped").isZero();
    }

    @Test
    @DisplayName("an uncounted WS upgrade is still refused while open (count-ws-upgrades=false)")
    void anUncountedUpgradeIsRefused() {
        SlidingWindowGatewayBudget uncounted = new SlidingWindowGatewayBudget("env-2", "Staging", "119",
                GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE).withCountWsUpgrades(false),
                new SimpleMeterRegistry(), clock::get);
        try {
            assertThatThrownBy(() -> uncounted.reportEdgeBlock(GatewayEndpoint.WS_UPGRADE, RAY))
                    .isInstanceOf(GatewayCircuitOpenException.class);
            assertThatThrownBy(() -> uncounted.runWsUpgrade(RequestTier.PRIORITIZED, SCOPE, () -> {
                throw new AssertionError("an upgrade must not reach connect() while the edge refuses us");
            }))
                    .isInstanceOfSatisfying(GatewayCircuitOpenException.class,
                            e -> assertThat(e.getCfRay()).isEqualTo(RAY));
        } finally {
            uncounted.shutdown();
        }
    }

    @Test
    @DisplayName("a second block while open is counted but neither re-opens nor re-announces")
    void aSecondBlockDoesNotReopen() {
        openTheCircuit();
        clock.addAndGet(Duration.ofMinutes(10).toNanos());

        assertThatThrownBy(() -> budget.reportEdgeBlock(GatewayEndpoint.VERIFY_TOKEN, "other-ray"))
                .isInstanceOfSatisfying(GatewayCircuitOpenException.class, e ->
                        assertThat(e.getRetryAfter())
                                .as("the probe schedule is not pushed back by blocks of requests that "
                                        + "were already in flight when it opened")
                                .isEqualTo(INTERVAL.minusMinutes(10)));

        assertThat(counter(SlidingWindowGatewayBudget.CIRCUIT_OPENED_TOTAL)).isEqualTo(1.0);
        assertThat(counter(SlidingWindowGatewayBudget.EDGE_BLOCKS_TOTAL, "endpoint", "verifytoken"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("no probe before the interval; exactly one probe at it, counted against the window")
    void exactlyOneProbePerInterval() {
        openTheCircuit();

        clock.addAndGet(INTERVAL.minusSeconds(1).toNanos());
        budget.runCircuitProbe();
        assertThat(probes).as("not due yet").hasValue(0);

        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        budget.runCircuitProbe();
        budget.runCircuitProbe();
        assertThat(probes)
                .as("one probe per interval — the second call is inside the next interval")
                .hasValue(1);
        assertThat(outcome(RequestTier.ESSENTIAL, SlidingWindowGatewayBudget.OUTCOME_COUNTED))
                .as("count(\"circuit-probe\") is unconditional: it is an HTTP GET, not an upgrade")
                .isEqualTo(1.0);
        assertThat(budget.windowRequests()).isEqualTo(1);
    }

    @Test
    @DisplayName("a probe that is refused keeps the circuit open for another interval")
    void aBlockedProbeReopens() {
        openTheCircuit();
        clock.addAndGet(INTERVAL.toNanos());
        nextProbeAnswer.set(blocked());

        budget.runCircuitProbe();

        assertThat(budget.snapshot().circuitOpen()).isTrue();
        assertThat(gauge()).isEqualTo(1.0);
        assertThat(counter(SlidingWindowGatewayBudget.EDGE_BLOCKS_TOTAL, "endpoint", "circuit-probe"))
                .isEqualTo(1.0);
        assertThat(counter(SlidingWindowGatewayBudget.CIRCUIT_OPENED_TOTAL))
                .as("it never closed, so it did not open again")
                .isEqualTo(1.0);
        assertThatThrownBy(() -> budget.execute(RequestTier.ESSENTIAL, SCOPE, () -> "x"))
                .isInstanceOfSatisfying(GatewayCircuitOpenException.class, e -> {
                    assertThat(e.getRetryAfter()).isEqualTo(INTERVAL);
                    assertThat(e.getCfRay()).as("the probe's ray is the freshest evidence").isEqualTo("probe-ray-HKG");
                });

        clock.addAndGet(INTERVAL.toNanos());
        budget.runCircuitProbe();
        assertThat(probes).as("constant interval, never exponential").hasValue(2);
    }

    @Test
    @DisplayName("a probe that gets no answer at all is not evidence the block lifted")
    void anUnansweredProbeKeepsItOpen() {
        openTheCircuit();
        clock.addAndGet(INTERVAL.toNanos());
        nextProbeAnswer.set(new IOException("connect timed out"));

        budget.runCircuitProbe();

        assertThat(probes).hasValue(1);
        assertThat(budget.snapshot().circuitOpen()).isTrue();
    }

    @Test
    @DisplayName("an answered probe closes the circuit and traffic is admitted again")
    void anAnsweredProbeCloses() throws Exception {
        openTheCircuit();
        clock.addAndGet(INTERVAL.toNanos());
        nextProbeAnswer.set(gatewayAnswered());

        budget.runCircuitProbe();

        assertThat(budget.snapshot().circuitOpen()).isFalse();
        assertThat(gauge()).isZero();
        for (RequestTier tier : new RequestTier[]{RequestTier.ESSENTIAL, RequestTier.PRIORITIZED, RequestTier.DEFAULT}) {
            assertThat(budget.execute(tier, SCOPE, () -> "sent")).isEqualTo("sent");
        }
        clock.addAndGet(INTERVAL.toNanos());
        budget.runCircuitProbe();
        assertThat(probes).as("a closed circuit never probes").hasValue(1);

        // And a later block opens it again, as a fresh incident.
        openTheCircuit();
        assertThat(counter(SlidingWindowGatewayBudget.CIRCUIT_OPENED_TOTAL)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("observe mode counts the block and opens nothing — the request proceeds as before")
    void observeModeNeverOpens() throws Exception {
        SimpleMeterRegistry observeMeters = new SimpleMeterRegistry();
        SlidingWindowGatewayBudget observing = new SlidingWindowGatewayBudget("env-3", "Prod", "119",
                GatewayBudgetSettings.defaults(), observeMeters, clock::get);
        try {
            for (int i = 0; i < 5; i++) {
                observing.reportEdgeBlock(GatewayEndpoint.DEPOSIT, RAY); // returns normally
            }

            assertThat(observing.snapshot().circuitOpen()).isFalse();
            assertThat(observeMeters.get(SlidingWindowGatewayBudget.EDGE_BLOCKS_TOTAL)
                    .tags("endpoint", "deposit").counter().count())
                    .as("detection is a fact about the edge in either mode")
                    .isEqualTo(5.0);
            assertThat(observeMeters.get(SlidingWindowGatewayBudget.CIRCUIT_OPENED_TOTAL).counter().count())
                    .isZero();
            assertThat(observing.execute(RequestTier.ESSENTIAL, SCOPE, () -> "sent")).isEqualTo("sent");
        } finally {
            observing.shutdown();
        }
    }

    @Test
    @DisplayName("a circuit opened by a WS-upgrade block is cleared by probing the WS host, not the API host")
    void aWsOpenedCircuitProbesTheWsHost() {
        // review-phase5. The API and WS hosts can be different Cloudflare zones; the API host
        // answering says nothing about whether the WS host still refuses us.
        AtomicInteger wsProbes = new AtomicInteger();
        AtomicReference<CircuitProbe.Answer> wsAnswer = new AtomicReference<>(blocked());
        budget.bindWsCircuitProbe(() -> {
            wsProbes.incrementAndGet();
            return wsAnswer.get();
        });
        nextProbeAnswer.set(gatewayAnswered()); // the API host is fine
        assertThatThrownBy(() -> budget.reportEdgeBlock(GatewayEndpoint.WS_UPGRADE, RAY))
                .isInstanceOf(GatewayCircuitOpenException.class);

        clock.addAndGet(INTERVAL.toNanos());
        budget.runCircuitProbe();

        assertThat(wsProbes).hasValue(1);
        assertThat(probes).as("the API probe is not the evidence for a WS block").hasValue(0);
        assertThat(budget.snapshot().circuitOpen()).isTrue();

        wsAnswer.set(new CircuitProbe.Answer(101, CloudflareBlockDetector.Verdict.NOT_A_BLOCK));
        clock.addAndGet(INTERVAL.toNanos());
        budget.runCircuitProbe();
        assertThat(budget.snapshot().circuitOpen()).isFalse();
    }

    @Test
    @DisplayName("a circuit opened by an HTTP block is cleared by the API probe even when a WS probe is bound")
    void anHttpOpenedCircuitProbesTheApiHost() {
        AtomicInteger wsProbes = new AtomicInteger();
        budget.bindWsCircuitProbe(() -> {
            wsProbes.incrementAndGet();
            return blocked();
        });
        nextProbeAnswer.set(gatewayAnswered());
        openTheCircuit(); // LOGIN

        clock.addAndGet(INTERVAL.toNanos());
        budget.runCircuitProbe();

        assertThat(wsProbes).hasValue(0);
        assertThat(probes).hasValue(1);
        assertThat(budget.snapshot().circuitOpen()).isFalse();
    }
}
