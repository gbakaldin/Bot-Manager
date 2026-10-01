package com.vingame.bot.infrastructure.gateway;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CloudflareBlockDetector} against the page this host really received
 * (GATEWAY_REQUEST_BUDGET AD-13, plan Phase 5 tests).
 * <p>
 * The false-positive cases matter as much as the true one: an {@code EDGE_BLOCK} opens a circuit
 * that refuses <b>every</b> request to a brand, so a gateway's own JSON 403 misread as a block
 * would turn one refused bot into a brand-wide self-inflicted outage.
 */
@DisplayName("CloudflareBlockDetector — the captured page is a block; a gateway's own 403 is not")
class CloudflareBlockDetectorTest {

    private static Function<String, Optional<String>> headers(Map<String, String> values) {
        return name -> Optional.ofNullable(values.get(name.toLowerCase(java.util.Locale.ROOT)));
    }

    @Test
    @DisplayName("the captured 2026-09-17 page is an edge block, and its cf-ray is captured")
    void theCapturedPageIsABlock() {
        CapturedBlockPage page = CapturedBlockPage.load();
        assertThat(page.status()).isEqualTo(403);

        CloudflareBlockDetector.Verdict verdict =
                CloudflareBlockDetector.classify(page.status(), page::header, page.body());

        assertThat(verdict.edgeBlock()).isTrue();
        assertThat(verdict.cfRay())
                .as("the one value the SA ticket needs, and what the ERROR line prints")
                .isEqualTo(CapturedBlockPage.CF_RAY);
    }

    @Test
    @DisplayName("a JSON 403 from the gateway, even one that transited Cloudflare, is not a block")
    void aJsonForbiddenIsNotABlock() {
        // Every gwms answer passes through the edge, so cf-ray alone proves nothing about who
        // refused. The page conjunct is what keeps an origin's own refusal from opening a circuit.
        CloudflareBlockDetector.Verdict verdict = CloudflareBlockDetector.classify(403,
                headers(Map.of("server", "cloudflare", "cf-ray", "abc-HKG",
                        "content-type", "application/json")),
                "{\"status\":\"FORBIDDEN\",\"code\":403,\"message\":\"Not allowed\"}");

        assertThat(verdict.edgeBlock()).isFalse();
    }

    @Test
    @DisplayName("an HTML 403 without server: cloudflare or cf-ray is not a block")
    void aForbiddenPageThatDidNotComeFromTheEdgeIsNotABlock() {
        // An nginx 403 page from an origin: refused, but not by the rule this feature is about.
        CloudflareBlockDetector.Verdict verdict = CloudflareBlockDetector.classify(403,
                headers(Map.of("server", "nginx", "content-type", "text/html")),
                "<html><body>403 Forbidden</body></html>");

        assertThat(verdict.edgeBlock()).isFalse();
    }

    @Test
    @DisplayName("a 429 carrying a cf-ray and a block page is a block")
    void aTooManyRequestsWithCfRayIsABlock() {
        CloudflareBlockDetector.Verdict verdict = CloudflareBlockDetector.classify(429,
                headers(Map.of("cf-ray", "8f00-SIN")),
                "<div id=\"cf-error-details\">rate limited</div>");

        assertThat(verdict.edgeBlock()).isTrue();
        assertThat(verdict.cfRay()).isEqualTo("8f00-SIN");
    }

    @Test
    @DisplayName("a 200, a 401 and a 502 are never blocks, whatever they say")
    void otherStatusesAreNeverBlocks() {
        CapturedBlockPage page = CapturedBlockPage.load();
        for (int status : new int[]{200, 401, 502}) {
            assertThat(CloudflareBlockDetector.classify(status, page::header, page.body()).edgeBlock())
                    .as("status %d", status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a WS handshake refused with the edge's 403 page headers is a block")
    void aWebSocketHandshakeWithACloudflareForbiddenIsABlock() {
        // Netty keeps the status and headers of a failed handshake, not the body, so on this path
        // the content type alone has to satisfy the page conjunct.
        DefaultHttpHeaders h = new DefaultHttpHeaders();
        h.add("server", "cloudflare");
        h.add("cf-ray", CapturedBlockPage.CF_RAY);
        h.add("content-type", "text/html; charset=UTF-8");
        WebSocketClientHandshakeException handshake = new WebSocketClientHandshakeException(
                "Invalid handshake response getStatus: 403 Forbidden",
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, h));

        // Wrapped, the way a library rethrow might deliver it.
        CloudflareBlockDetector.Verdict verdict = CloudflareBlockDetector.classifyHandshakeFailure(
                new RuntimeException("connect failed", handshake));

        assertThat(verdict.edgeBlock()).isTrue();
        assertThat(verdict.cfRay()).isEqualTo(CapturedBlockPage.CF_RAY);
    }

    @Test
    @DisplayName("a WS handshake that failed for any other reason is not a block")
    void otherHandshakeFailuresAreNotBlocks() {
        DefaultHttpHeaders h = new DefaultHttpHeaders();
        h.add("server", "cloudflare");
        h.add("cf-ray", "x-HKG");
        WebSocketClientHandshakeException badGateway = new WebSocketClientHandshakeException(
                "Invalid handshake response getStatus: 502 Bad Gateway",
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY, h));

        assertThat(CloudflareBlockDetector.classifyHandshakeFailure(badGateway).edgeBlock()).isFalse();
        assertThat(CloudflareBlockDetector.classifyHandshakeFailure(
                new java.net.ConnectException("Connection refused")).edgeBlock()).isFalse();
        assertThat(CloudflareBlockDetector.classifyHandshakeFailure(null).edgeBlock()).isFalse();
    }

    @Test
    @DisplayName("a cause cycle does not hang the classifier")
    void aCauseCycleTerminates() {
        // initCause can build A -> B -> A; this runs on a bot's reconnect thread.
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(CloudflareBlockDetector.classifyHandshakeFailure(a).edgeBlock()).isFalse();
    }
}
