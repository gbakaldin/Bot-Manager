package com.vingame.bot.infrastructure.probe;

import com.sun.net.httpserver.HttpServer;
import com.vingame.bot.infrastructure.probe.EnvironmentWsProbe.Outcome;
import com.vingame.bot.infrastructure.probe.EnvironmentWsProbe.ProbeResult;
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
 * QA, GATEWAY_REQUEST_BUDGET Phase 5 (A15.4, A29.2): the anonymous environment probe against a
 * <b>real</b> JDK WebSocket handshake on loopback, refused the way the Cloudflare edge refuses it.
 * <p>
 * {@code EnvironmentWsProbeClassificationTest} feeds {@code classify} a mocked
 * {@code HttpResponse} whose headers the test chose. Whether the JDK's {@code WebSocket} client
 * really hands back a {@code WebSocketHandshakeException} whose response still carries
 * {@code server}, {@code cf-ray} and {@code content-type} — the only things the detector reads on
 * this path — is what decides whether {@code EDGE_BLOCK} can ever be produced in production, and
 * that is only answerable by letting the JDK make the request. Before Phase 5 this exact 403 read
 * as {@code HTTP_4XX}, i.e. healthy, i.e. "recover now" during a day-long block.
 * <p>
 * Loopback only: a JDK {@code HttpServer} on {@code 127.0.0.1}, ephemeral port, answering every
 * upgrade with a canned response. No gateway, no real edge.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisplayName("QA — EnvironmentWsProbe reads a real Cloudflare-shaped upgrade refusal as EDGE_BLOCK")
class EnvironmentWsProbeEdgeBlockLoopbackTest {

    private static final String CF_RAY = "a3c7e4004acc850e-HKG";
    private static final String BLOCK_PAGE = "<!DOCTYPE html><html><head><title>Attention Required! | Cloudflare"
            + "</title></head><body><div id=\"cf-error-details\"><h1>Sorry, you have been blocked</h1>"
            + "</div></body></html>";

    private HttpServer server;
    private final AtomicInteger received = new AtomicInteger();
    private final AtomicReference<Answer> answer = new AtomicReference<>();

    private record Answer(int status, Map<String, String> headers, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            received.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            Answer a = answer.get();
            byte[] bytes = a.body().getBytes(StandardCharsets.UTF_8);
            a.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(a.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String wsUrl() {
        return "ws://127.0.0.1:" + server.getAddress().getPort() + "/websocket";
    }

    @Test
    @DisplayName("the edge's HTML 403 with server: cloudflare and a cf-ray is EDGE_BLOCK and unhealthy")
    void aCloudflareBlockPageIsAnEdgeBlock() {
        answer.set(new Answer(403, Map.of(
                "Server", "cloudflare",
                "CF-RAY", CF_RAY,
                "Content-Type", "text/html; charset=UTF-8"), BLOCK_PAGE));

        ProbeResult result = new EnvironmentWsProbe(5).probe(wsUrl(), null);

        assertThat(received).hasValue(1);
        assertThat(result.outcome())
                .as("what the JDK client really surfaces for the refusal — detail: %s", result.detail())
                .isEqualTo(Outcome.EDGE_BLOCK);
        assertThat(result.healthy()).isFalse();
        assertThat(result.detail()).contains(CF_RAY).doesNotContain("<!DOCTYPE");
    }

    @Test
    @DisplayName("a JSON 403 that merely transited Cloudflare is still AD-2's healthy HTTP_4XX")
    void aJsonForbiddenThroughTheEdgeIsStillHealthy() {
        // The false-positive direction: an origin that refuses anonymous upgrades with its own JSON
        // must stay recoverable, or the edge-block check would make it permanently ineligible.
        answer.set(new Answer(403, Map.of(
                "Server", "cloudflare",
                "CF-RAY", CF_RAY,
                "Content-Type", "application/json"), "{\"status\":\"FORBIDDEN\",\"code\":403}"));

        ProbeResult result = new EnvironmentWsProbe(5).probe(wsUrl(), null);

        assertThat(result.outcome()).isEqualTo(Outcome.HTTP_4XX);
        assertThat(result.healthy()).isTrue();
    }
}
