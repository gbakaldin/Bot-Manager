package com.vingame.bot.infrastructure.probe;

import com.vingame.bot.infrastructure.probe.EnvironmentWsProbe.Outcome;
import com.vingame.bot.infrastructure.probe.EnvironmentWsProbe.ProbeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpResponse;
import java.net.http.WebSocketHandshakeException;
import java.nio.channels.UnresolvedAddressException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Classification cover for the probe (DEAD_GROUP_AUTO_RECOVERY AD-2). No network:
 * synthetic causes are fed straight into the classifier.
 * <p>
 * The load-bearing assertions are the two that decide whether a recovery may ever
 * be attempted: a <b>5xx is unhealthy</b> (that is the incident shape — the origin
 * is gone and the edge is synthesising a gateway error) and a <b>4xx is healthy</b>
 * (a server is parsing our request). The permissiveness is deliberate; tightening
 * it to 101-only would make any environment that refuses anonymous upgrades
 * permanently unrecoverable.
 */
@DisplayName("EnvironmentWsProbe.classify (AD-2)")
class EnvironmentWsProbeClassificationTest {

    private final EnvironmentWsProbe probe = new EnvironmentWsProbe(5);

    private static WebSocketHandshakeException handshake(int status) {
        HttpResponse<?> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        return new WebSocketHandshakeException(response);
    }

    @Test
    @DisplayName("502 → HTTP_5XX and unhealthy — the incident shape")
    void badGatewayIsUnhealthy() {
        ProbeResult r = probe.classify(handshake(502), 12);
        assertThat(r.outcome()).isEqualTo(Outcome.HTTP_5XX);
        assertThat(r.healthy()).isFalse();
        assertThat(r.detail()).contains("502");
    }

    @Test
    @DisplayName("403 → HTTP_4XX and healthy — a server parsed our request")
    void forbiddenIsHealthy() {
        ProbeResult r = probe.classify(handshake(403), 8);
        assertThat(r.outcome()).isEqualTo(Outcome.HTTP_4XX);
        assertThat(r.healthy()).isTrue();
    }

    @Test
    @DisplayName("any status < 500 counts as healthy")
    void statusUnderFiveHundredIsHealthy() {
        assertThat(probe.classify(handshake(200), 1).healthy()).isTrue();
        assertThat(probe.classify(handshake(499), 1).healthy()).isTrue();
        assertThat(probe.classify(handshake(500), 1).healthy()).isFalse();
    }

    @Test
    @DisplayName("connect timeout → TIMEOUT")
    void connectTimeout() {
        assertThat(probe.classify(new HttpConnectTimeoutException("too slow"), 5000).outcome())
                .isEqualTo(Outcome.TIMEOUT);
    }

    @Test
    @DisplayName("TLS failure → TLS_ERROR")
    void tlsFailure() {
        assertThat(probe.classify(new SSLHandshakeException("bad cert"), 30).outcome())
                .isEqualTo(Outcome.TLS_ERROR);
    }

    @Test
    @DisplayName("connection refused / unresolved address → CONNECT_ERROR")
    void connectFailures() {
        assertThat(probe.classify(new ConnectException("refused"), 3).outcome())
                .isEqualTo(Outcome.CONNECT_ERROR);
        assertThat(probe.classify(new UnresolvedAddressException(), 3).outcome())
                .isEqualTo(Outcome.CONNECT_ERROR);
    }

    @Test
    @DisplayName("anything else, and a null cause, → ERROR")
    void everythingElseIsError() {
        assertThat(probe.classify(new IOException("reset by peer"), 3).outcome())
                .isEqualTo(Outcome.ERROR);
        assertThat(probe.classify(null, 3).outcome()).isEqualTo(Outcome.ERROR);
    }

    @Test
    @DisplayName("only OPEN and HTTP_4XX are healthy")
    void healthyPredicate() {
        assertThat(new ProbeResult(Outcome.OPEN, 1, "ok").healthy()).isTrue();
        assertThat(new ProbeResult(Outcome.HTTP_4XX, 1, "ok").healthy()).isTrue();
        for (Outcome o : new Outcome[]{Outcome.HTTP_5XX, Outcome.TIMEOUT,
                Outcome.TLS_ERROR, Outcome.CONNECT_ERROR, Outcome.ERROR}) {
            assertThat(new ProbeResult(o, 1, "x").healthy()).as(o.name()).isFalse();
        }
    }

    @Test
    @DisplayName("outcome tags are the lower-case metric values")
    void outcomeTags() {
        assertThat(Outcome.OPEN.tag()).isEqualTo("open");
        assertThat(Outcome.HTTP_4XX.tag()).isEqualTo("http_4xx");
        assertThat(Outcome.HTTP_5XX.tag()).isEqualTo("http_5xx");
    }

    @Test
    @DisplayName("restricted headers are filtered, not forwarded — WebSocket.Builder.header would throw")
    void restrictedHeadersAreFiltered() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", "example.com");
        headers.put("Connection", "Upgrade");
        headers.put("Sec-WebSocket-Key", "abc");
        headers.put("Content-Length", "0");
        headers.put("X-Custom", "keep-me");
        headers.put("User-Agent", "keep-me-too");

        assertThat(EnvironmentWsProbe.restrictedNames(headers))
                .containsExactly("Host", "Connection", "Sec-WebSocket-Key", "Content-Length");
        assertThat(EnvironmentWsProbe.isRestricted("upgrade")).isTrue();
        assertThat(EnvironmentWsProbe.isRestricted("X-Custom")).isFalse();
        assertThat(EnvironmentWsProbe.restrictedNames(null)).isEmpty();
    }
}
