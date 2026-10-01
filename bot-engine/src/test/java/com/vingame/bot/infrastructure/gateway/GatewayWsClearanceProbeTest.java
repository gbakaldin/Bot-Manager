package com.vingame.bot.infrastructure.gateway;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The WS-host clearance probe {@link GatewayBudgetRegistry#bindWebSocketProbe} builds, end to end
 * against a loopback server that refuses the upgrade (review-phase5). Loopback only: the JDK
 * {@code HttpServer} cannot complete an upgrade, but every refusal shape that matters is a refusal.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("WS clearance probe — a Cloudflare refusal of the upgrade is a block, any other answer is not")
class GatewayWsClearanceProbeTest {

    private HttpServer server;
    private final AtomicInteger received = new AtomicInteger();
    private final AtomicReference<Object[]> answer = new AtomicReference<>();
    private SlidingWindowGatewayBudget budget;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            received.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            Object[] a = answer.get();
            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) a[1];
            byte[] bytes = ((String) a[2]).getBytes(StandardCharsets.UTF_8);
            headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders((Integer) a[0], bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        GatewayBudgetRegistry registry = new GatewayBudgetRegistry(
                GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE), new SimpleMeterRegistry());
        budget = (SlidingWindowGatewayBudget) registry.forEnvironment("env-1", "Staging", "119", null);
        registry.bindWebSocketProbe("env-1", "ws://127.0.0.1:" + server.getAddress().getPort() + "/websocket");
    }

    @AfterEach
    void tearDown() {
        budget.shutdown();
        server.stop(0);
    }

    @Test
    @DisplayName("the WS host still refusing the upgrade with Cloudflare's page keeps the answer a block")
    void aCloudflareRefusalIsABlock() throws Exception {
        CapturedBlockPage page = CapturedBlockPage.load();
        answer.set(new Object[]{403, Map.of("Server", "cloudflare", "CF-RAY", CapturedBlockPage.CF_RAY,
                "Content-Type", "text/html; charset=UTF-8"), page.body()});

        CircuitProbe probe = (CircuitProbe) org.springframework.test.util.ReflectionTestUtils
                .getField(budget, "wsCircuitProbe");
        assertThat(probe).as("bound by bindWebSocketProbe").isNotNull();
        CircuitProbe.Answer result = probe.probe();

        assertThat(received).hasValue(1);
        assertThat(result.status()).isEqualTo(403);
        assertThat(result.verdict().edgeBlock()).isTrue();
    }

    @Test
    @DisplayName("an origin's own refusal of an anonymous upgrade is not a block — the edge let us through")
    void anOriginRefusalIsNotABlock() throws Exception {
        answer.set(new Object[]{403, Map.of("Server", "cloudflare", "CF-RAY", "x-HKG",
                "Content-Type", "application/json"), "{\"status\":\"FORBIDDEN\"}"});

        CircuitProbe probe = (CircuitProbe) org.springframework.test.util.ReflectionTestUtils
                .getField(budget, "wsCircuitProbe");
        CircuitProbe.Answer result = probe.probe();

        assertThat(result.verdict().edgeBlock()).isFalse();
    }
}
