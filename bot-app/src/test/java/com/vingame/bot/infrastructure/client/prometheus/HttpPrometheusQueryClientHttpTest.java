package com.vingame.bot.infrastructure.client.prometheus;

import com.sun.net.httpserver.HttpServer;
import com.vingame.bot.common.exception.UpstreamPrometheusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA-added coverage of the {@link HttpPrometheusQueryClient} <b>HTTP transport
 * path</b> (the dev test only exercised the package-private {@code parse}). Uses a
 * throwaway {@link HttpServer} on a loopback port to verify: the non-2xx → 502
 * mapping (AD-7), that the success path still parses end-to-end through
 * {@code queryInstant}/{@code queryRange}, and that the request carries the
 * URL-encoded PromQL plus the {@code time}/{@code start}/{@code end}/{@code step}
 * params Prometheus expects.
 */
@DisplayName("HttpPrometheusQueryClient (transport)")
class HttpPrometheusQueryClientHttpTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Start a stub Prometheus that returns the given status + body and records the last query string. */
    private HttpPrometheusQueryClient stub(int status, String body, AtomicReference<String> capturedQuery)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", exchange -> {
            if (capturedQuery != null) {
                capturedQuery.set(exchange.getRequestURI().getRawQuery());
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        return new HttpPrometheusQueryClient("http://127.0.0.1:" + port);
    }

    @Test
    @DisplayName("HTTP 500 from Prometheus → UpstreamPrometheusException (→ 502)")
    void non2xxMapsToUpstreamException() throws IOException {
        HttpPrometheusQueryClient client = stub(500, "internal boom", null);

        assertThatThrownBy(() -> client.queryInstant("up", Instant.ofEpochSecond(1700000000L)))
                .isInstanceOf(UpstreamPrometheusException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("HTTP 400 with an error envelope → UpstreamPrometheusException")
    void non2xxWithEnvelopeMapsToUpstreamException() throws IOException {
        // Prometheus returns 4xx + an error envelope for bad queries; non-2xx wins first.
        HttpPrometheusQueryClient client = stub(400,
                "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"parse error\"}", null);

        assertThatThrownBy(() -> client.queryInstant("bad(", Instant.ofEpochSecond(1700000000L)))
                .isInstanceOf(UpstreamPrometheusException.class);
    }

    @Test
    @DisplayName("instant success path parses end-to-end and URL-encodes the query + time param")
    void instantSuccessEndToEnd() throws IOException {
        AtomicReference<String> q = new AtomicReference<>();
        HttpPrometheusQueryClient client = stub(200,
                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                        + "{\"metric\":{\"status\":\"OK\"},\"value\":[1700000000,\"5\"]}]}}",
                q);

        PrometheusResult result = client.queryInstant("sum(bots_by_game_status{gameId=\"g1\"})",
                Instant.ofEpochSecond(1700000000L));

        assertThat(result.resultType()).isEqualTo(PrometheusResult.ResultType.VECTOR);
        assertThat(result.series().get(0).samples().get(0).value()).isEqualTo(5.0);
        // PromQL is URL-encoded; the time param is present.
        assertThat(q.get()).contains("query=sum%28bots_by_game_status");
        assertThat(q.get()).contains("time=1700000000");
    }

    @Test
    @DisplayName("range success path sends start/end/step and parses the matrix")
    void rangeSuccessEndToEnd() throws IOException {
        AtomicReference<String> q = new AtomicReference<>();
        HttpPrometheusQueryClient client = stub(200,
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                        + "{\"metric\":{\"reason\":\"watchdog\"},\"values\":[[1700000000,\"0.1\"],[1700000060,\"0.2\"]]}]}}",
                q);

        PrometheusResult result = client.queryRange("rate(x[5m])",
                Instant.ofEpochSecond(1700000000L), Instant.ofEpochSecond(1700003600L),
                Duration.ofSeconds(60));

        assertThat(result.resultType()).isEqualTo(PrometheusResult.ResultType.MATRIX);
        assertThat(result.series().get(0).samples()).hasSize(2);
        assertThat(q.get()).contains("start=1700000000").contains("end=1700003600").contains("step=60");
    }
}
