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

    @Test
    @DisplayName("an upgrade that completes AFTER the probe gave up is aborted, not leaked")
    void aLateUpgradeIsAborted() throws Exception {
        // Re-review of the fix round: handshake.cancel(true) only cancelled the dependent future, so
        // the abort-on-completion callback saw no WebSocket and a late 101 stayed open for ever. A raw
        // loopback socket answers a valid 101 one second after the probe's 300 ms bound, then waits
        // to see the client hang up.
        java.util.concurrent.CompletableFuture<Boolean> clientClosed = new java.util.concurrent.CompletableFuture<>();
        try (java.net.ServerSocket late = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (java.net.Socket socket = late.accept()) {
                    java.io.BufferedReader in = new java.io.BufferedReader(
                            new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    String key = null;
                    for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:")) {
                            key = line.substring(line.indexOf(':') + 1).trim();
                        }
                    }
                    Thread.sleep(1_000);
                    String accept = java.util.Base64.getEncoder().encodeToString(
                            java.security.MessageDigest.getInstance("SHA-1").digest(
                                    (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                            + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    socket.setSoTimeout(8_000);
                    clientClosed.complete(socket.getInputStream().read() < 0);
                } catch (java.net.SocketTimeoutException e) {
                    clientClosed.complete(false);
                } catch (IOException e) {
                    clientClosed.complete(true); // a reset is a hang-up too
                } catch (Exception e) {
                    clientClosed.completeExceptionally(e);
                }
            });

            GatewayBudgetRegistry registry = new GatewayBudgetRegistry(
                    GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE), new SimpleMeterRegistry());
            SlidingWindowGatewayBudget lateBudget =
                    (SlidingWindowGatewayBudget) registry.forEnvironment("env-late", "Staging", "119", null);
            try {
                registry.bindWebSocketProbe("env-late", "ws://127.0.0.1:" + late.getLocalPort() + "/websocket",
                        java.time.Duration.ofMillis(300));
                CircuitProbe probe = (CircuitProbe) org.springframework.test.util.ReflectionTestUtils
                        .getField(lateBudget, "wsCircuitProbe");

                org.assertj.core.api.Assertions.assertThatThrownBy(probe::probe)
                        .isInstanceOf(IOException.class)
                        .hasMessageContaining("timed out");

                assertThat(clientClosed.get(15, TimeUnit.SECONDS))
                        .as("the late 101 must be aborted by the probe, not left open with a no-op listener")
                        .isTrue();
            } finally {
                lateBudget.shutdown();
            }
        }
    }

    @Test
    @DisplayName("a WS host that accepts and never answers the upgrade is hung up on at the timeout")
    void aSilentUpgradeIsHungUpOnAtTheTimeout() throws Exception {
        // The direction the re-review's suggested fix (drop cancel(true)) would have broken. On JDK 21
        // cancelling buildAsync's future tears the pending exchange down; without it the half-open
        // upgrade is held indefinitely (connectTimeout bounds the connect, not the upgrade).
        java.util.concurrent.CompletableFuture<Boolean> clientClosed = new java.util.concurrent.CompletableFuture<>();
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (java.net.Socket socket = silent.accept()) {
                    java.io.BufferedReader in = new java.io.BufferedReader(
                            new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                        // consume the upgrade request, answer nothing
                    }
                    socket.setSoTimeout(5_000);
                    clientClosed.complete(socket.getInputStream().read() < 0);
                } catch (java.net.SocketTimeoutException e) {
                    clientClosed.complete(false);
                } catch (IOException e) {
                    clientClosed.complete(true);
                }
            });

            GatewayBudgetRegistry registry = new GatewayBudgetRegistry(
                    GatewayBudgetSettings.defaults().withMode(GatewayBudgetMode.ENFORCE), new SimpleMeterRegistry());
            SlidingWindowGatewayBudget silentBudget =
                    (SlidingWindowGatewayBudget) registry.forEnvironment("env-silent", "Staging", "119", null);
            try {
                registry.bindWebSocketProbe("env-silent", "ws://127.0.0.1:" + silent.getLocalPort() + "/websocket",
                        java.time.Duration.ofMillis(300));
                CircuitProbe probe = (CircuitProbe) org.springframework.test.util.ReflectionTestUtils
                        .getField(silentBudget, "wsCircuitProbe");

                org.assertj.core.api.Assertions.assertThatThrownBy(probe::probe).isInstanceOf(IOException.class);

                assertThat(clientClosed.get(15, TimeUnit.SECONDS))
                        .as("the pending upgrade must not outlive the probe")
                        .isTrue();
            } finally {
                silentBudget.shutdown();
            }
        }
    }
}
