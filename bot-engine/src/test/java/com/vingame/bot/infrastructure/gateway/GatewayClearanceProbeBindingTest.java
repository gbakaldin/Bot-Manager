package com.vingame.bot.infrastructure.gateway;

import com.vingame.bot.common.exception.GatewayCircuitOpenException;
import com.vingame.bot.infrastructure.client.stub.StubGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA, GATEWAY_REQUEST_BUDGET Phase 5: <b>when</b> the registry binds the clearance probe.
 * <p>
 * {@code GatewayBudgetRegistry.forEnvironment} binds the probe on every call that knows the
 * gateway, not only when it creates the budget, for two stated reasons: the WS probe scheduler can
 * create an environment's budget first with no {@code apiGateway} to offer, and an environment
 * whose {@code apiGateway} is edited gets its clients rebuilt through the same call. Nothing tested
 * either. A mutation that binds only at creation survived the whole suite — and its production
 * consequence is a circuit that can never close (no probe, "restart bot-manager to close it") for
 * any environment whose recovery probe happened to run before its first bot client was built.
 * <p>
 * Loopback {@link StubGateway}s only.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("QA — the clearance probe is bound whenever the gateway becomes known, latest URL wins")
class GatewayClearanceProbeBindingTest {

    private StubGateway first;
    private StubGateway second;
    private AtomicLong clock;
    private GatewayBudgetRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        first = StubGateway.start();
        second = StubGateway.start();
        clock = new AtomicLong(1_000L);
        registry = new GatewayBudgetRegistry(GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE),
                new SimpleMeterRegistry(), clock::get);
    }

    @AfterEach
    void tearDown() {
        GatewayBudget budget = registry.find("env-1");
        if (budget instanceof SlidingWindowGatewayBudget sliding) {
            sliding.shutdown();
        }
        first.close();
        second.close();
    }

    private SlidingWindowGatewayBudget openAndAdvance() {
        SlidingWindowGatewayBudget budget = (SlidingWindowGatewayBudget) registry.find("env-1");
        assertThatThrownBy(() -> budget.reportEdgeBlock(GatewayEndpoint.LOGIN, CapturedBlockPage.CF_RAY))
                .isInstanceOf(GatewayCircuitOpenException.class);
        clock.addAndGet(GatewayBudgetSettings.BLOCK_PROBE_INTERVAL_DEFAULT.toNanos());
        return budget;
    }

    @Test
    @DisplayName("a budget first created without a gateway URL gets its probe when the URL arrives")
    void aBudgetCreatedWithoutAUrlIsBoundLater() {
        // The WS probe scheduler's call: no apiGateway, so nothing to bind.
        registry.forEnvironment("env-1", "Staging", "119");
        // Then the environment's clients are built, and the gateway becomes known.
        registry.forEnvironment("env-1", "Staging", "119", first.baseUrl());
        first.block();

        SlidingWindowGatewayBudget budget = openAndAdvance();
        budget.runCircuitProbe();

        assertThat(first.countFor("verifytoken"))
                .as("the probe went out — without a binding the circuit could never close")
                .isEqualTo(1);
        first.unblock();
        clock.addAndGet(GatewayBudgetSettings.BLOCK_PROBE_INTERVAL_DEFAULT.toNanos());
        budget.runCircuitProbe();
        assertThat(budget.snapshot().circuitOpen()).isFalse();
    }

    @Test
    @DisplayName("an edited apiGateway re-binds: the probe goes to the new host, not the old one")
    void anEditedGatewayRebinds() {
        registry.forEnvironment("env-1", "Staging", "119", first.baseUrl());
        registry.forEnvironment("env-1", "Staging", "119", second.baseUrl());
        second.block();

        openAndAdvance().runCircuitProbe();

        assertThat(second.countFor("verifytoken")).isEqualTo(1);
        assertThat(first.totalReceived()).as("the old host is no longer this environment's gateway").isZero();
    }
}
