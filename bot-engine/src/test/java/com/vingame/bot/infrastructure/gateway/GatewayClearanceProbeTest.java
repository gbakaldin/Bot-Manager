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
 * The clearance probe {@link GatewayBudgetRegistry} binds, end to end against the loopback stub
 * (GATEWAY_REQUEST_BUDGET AD-13): one anonymous {@code verifytoken.aspx?token=probe&fg=probe},
 * classified for a block, and the only request that leaves the JVM while the circuit is open.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("Clearance probe — anonymous, one per interval, closes the circuit only on a real answer")
class GatewayClearanceProbeTest {

    private StubGateway stub;
    private AtomicLong clock;
    private SlidingWindowGatewayBudget budget;

    @BeforeEach
    void setUp() throws IOException {
        stub = StubGateway.start();
        clock = new AtomicLong(1_000L);
        GatewayBudgetRegistry registry = new GatewayBudgetRegistry(
                GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE),
                new SimpleMeterRegistry(), clock::get);
        budget = (SlidingWindowGatewayBudget) registry.forEnvironment("env-1", "Staging", "119", stub.baseUrl());
    }

    @AfterEach
    void tearDown() {
        budget.shutdown();
        stub.close();
    }

    private void blockAndOpen() {
        stub.block();
        assertThatThrownBy(() -> budget.reportEdgeBlock(GatewayEndpoint.LOGIN, CapturedBlockPage.CF_RAY))
                .isInstanceOf(GatewayCircuitOpenException.class);
        clock.addAndGet(GatewayBudgetSettings.BLOCK_PROBE_INTERVAL_DEFAULT.toNanos());
    }

    @Test
    @DisplayName("the probe is an anonymous GET of verifytoken with token=probe — no account, no token")
    void theProbeIsAnonymous() {
        blockAndOpen();

        budget.runCircuitProbe();

        assertThat(stub.requests()).hasSize(1);
        StubGateway.RecordedRequest probe = stub.requests().get(0);
        assertThat(probe.method()).isEqualTo("GET");
        assertThat(probe.path()).isEqualTo("/gwms/v1/verifytoken.aspx");
        assertThat(probe.query()).isEqualTo("token=probe&fg=probe");
        assertThat(probe.header("X-TOKEN")).as("never the admin credential").isNull();
        assertThat(budget.snapshot().circuitOpen()).as("the stub still answers the block page").isTrue();
    }

    @Test
    @DisplayName("once the edge stops blocking, the next probe closes the circuit")
    void anAnsweredProbeCloses() {
        blockAndOpen();
        stub.unblock();

        budget.runCircuitProbe();

        assertThat(budget.snapshot().circuitOpen())
                .as("the stub's normal verifytoken answer is not a block — any such answer closes it")
                .isFalse();
        assertThat(stub.totalReceived()).isEqualTo(1);
    }
}
