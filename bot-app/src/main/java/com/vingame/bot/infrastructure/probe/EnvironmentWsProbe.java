package com.vingame.bot.infrastructure.probe;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Anonymous WebSocket reachability probe for one environment
 * (DEAD_GROUP_AUTO_RECOVERY AD-2). Answers a single question: <em>did the origin
 * behind this URL answer at all?</em>
 *
 * <p><b>It never touches a bot account.</b> No {@code ApiGatewayClient}, no
 * {@code BotCredentials}, no {@code TokensProvider}, no register, no deposit, no
 * AUTH frame — the socket is aborted the instant the handshake completes. It is
 * deliberately built on the JDK's {@link HttpClient#newWebSocketBuilder()} rather
 * than on {@code VingameWebSocketClient}, which requires tokens, sends AUTH, and
 * carries its own connect-time behaviour that is precisely what this must not
 * measure.
 *
 * <p><b>"Healthy" is permissive on purpose</b> ({@link ProbeResult#healthy()} =
 * {@code OPEN || HTTP_4XX}). The fault class being detected is "the origin process
 * is gone and the edge is synthesising a gateway error"; a well-formed 4xx proves a
 * server is parsing our request, which is exactly the transition being waited for.
 * An environment that would only ever return 101 to an <em>authenticated</em>
 * upgrade would otherwise be permanently ineligible for recovery with no visible
 * reason.
 */
@Slf4j
@Component
public class EnvironmentWsProbe {

    /**
     * Header names {@link WebSocket.Builder#header} rejects with
     * {@link IllegalArgumentException}. A probe that blindly forwarded
     * {@code Environment.headers} would fail 100% of the time on any environment
     * that sets one and would look exactly like an outage — so they are filtered,
     * not caught and ignored. Compared lower-case; {@code sec-websocket-*} is
     * matched by prefix.
     */
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "host", "connection", "upgrade", "content-length");

    private static final String RESTRICTED_PREFIX = "sec-websocket-";

    /** No-op listener: the probe reads nothing and writes nothing. */
    private static final WebSocket.Listener NOOP_LISTENER = new WebSocket.Listener() {};

    /** URLs whose skipped-header set has already been logged (DEBUG, once per URL). */
    private final Set<String> skippedHeadersLogged = ConcurrentHashMap.newKeySet();

    private final HttpClient httpClient;
    private final Duration timeout;

    public EnvironmentWsProbe(@Value("${bot.recovery.probe.timeout-seconds:5}") long timeoutSeconds) {
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** How the origin answered a single probe. */
    public enum Outcome {
        /** HTTP 101 — the upgrade completed. */
        OPEN,
        /** A completed HTTP response with status {@code < 500}. Healthy (AD-2). */
        HTTP_4XX,
        /** A completed HTTP response with status {@code >= 500} — the outage shape. */
        HTTP_5XX,
        TIMEOUT,
        TLS_ERROR,
        CONNECT_ERROR,
        /** Anything else, including a malformed URL. */
        ERROR;

        /** Lower-case metric tag value, e.g. {@code open} / {@code http_5xx}. */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One probe result.
     *
     * @param outcome       the classification
     * @param latencyMillis wall-clock time the attempt took, success or failure
     * @param detail        short human-readable cause, for the transition log line
     */
    public record ProbeResult(Outcome outcome, long latencyMillis, String detail) {

        /** AD-2: the origin answered. */
        public boolean healthy() {
            return outcome == Outcome.OPEN || outcome == Outcome.HTTP_4XX;
        }
    }

    /**
     * Probe one WebSocket URL. Never throws: every failure is classified into a
     * {@link ProbeResult}, because a failure to probe <em>is</em> the measurement.
     *
     * @param wsUrl   the environment's {@code webSocketMiniUrl} — the URL every bot
     *                uses, whatever its game type
     * @param headers the environment's configured headers; restricted names are
     *                skipped (see {@link #RESTRICTED_HEADERS}). May be {@code null}.
     */
    public ProbeResult probe(String wsUrl, Map<String, String> headers) {
        long startNanos = System.nanoTime();
        WebSocket webSocket = null;
        try {
            WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                    .connectTimeout(timeout);
            applyHeaders(builder, wsUrl, headers);

            webSocket = builder.buildAsync(URI.create(wsUrl), NOOP_LISTENER)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new ProbeResult(Outcome.OPEN, elapsedMillis(startNanos), "handshake completed");
        } catch (ExecutionException e) {
            return classify(e.getCause(), elapsedMillis(startNanos));
        } catch (TimeoutException e) {
            return new ProbeResult(Outcome.TIMEOUT, elapsedMillis(startNanos),
                    "no response within " + timeout.toMillis() + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(Outcome.ERROR, elapsedMillis(startNanos), "interrupted");
        } catch (RuntimeException e) {
            // Malformed URL, unsupported scheme, or a header the filter above did
            // not know about. Not an origin failure, but it is unhealthy.
            return new ProbeResult(Outcome.ERROR, elapsedMillis(startNanos),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (webSocket != null) {
                // Abort, not close: no closing handshake, no lingering socket.
                webSocket.abort();
            }
        }
    }

    /**
     * Classify the cause of a failed handshake (AD-2). Package-private so the
     * classification test can feed it synthetic causes with no network.
     */
    ProbeResult classify(Throwable cause, long latencyMillis) {
        if (cause instanceof WebSocketHandshakeException handshake) {
            int status = handshake.getResponse() != null
                    ? handshake.getResponse().statusCode() : 0;
            Outcome outcome = status < 500 ? Outcome.HTTP_4XX : Outcome.HTTP_5XX;
            return new ProbeResult(outcome, latencyMillis, "HTTP " + status);
        }
        // HttpConnectTimeoutException is an HttpTimeoutException, not a
        // ConnectException — check it before the connect branch regardless.
        if (cause instanceof HttpConnectTimeoutException || cause instanceof TimeoutException) {
            return new ProbeResult(Outcome.TIMEOUT, latencyMillis, describe(cause));
        }
        if (cause instanceof SSLException) {
            return new ProbeResult(Outcome.TLS_ERROR, latencyMillis, describe(cause));
        }
        if (cause instanceof ConnectException || cause instanceof UnresolvedAddressException) {
            return new ProbeResult(Outcome.CONNECT_ERROR, latencyMillis, describe(cause));
        }
        return new ProbeResult(Outcome.ERROR, latencyMillis, describe(cause));
    }

    /**
     * Copy the environment's headers onto the builder, skipping the restricted
     * names. The skipped set is logged once per URL at DEBUG — it is a static
     * property of the environment record, not a per-tick event.
     */
    private void applyHeaders(WebSocket.Builder builder, String wsUrl, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        List<String> skipped = new ArrayList<>(2);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null || entry.getValue() == null) {
                continue;
            }
            if (isRestricted(name)) {
                skipped.add(name);
                continue;
            }
            builder.header(name, entry.getValue());
        }
        if (!skipped.isEmpty() && skippedHeadersLogged.add(wsUrl)) {
            log.debug("ws probe {}: skipping restricted header(s) {}", wsUrl, skipped);
        }
    }

    /** Whether {@link WebSocket.Builder#header} would reject this header name. */
    static boolean isRestricted(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return RESTRICTED_HEADERS.contains(lower) || lower.startsWith(RESTRICTED_PREFIX);
    }

    /**
     * The header names of {@code headers} this probe would skip, in encounter
     * order. Exposed for tests and for the DEBUG line above.
     */
    static List<String> restrictedNames(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyList();
        }
        return headers.keySet().stream()
                .filter(n -> n != null && isRestricted(n))
                .toList();
    }

    private static String describe(Throwable cause) {
        if (cause == null) {
            return "unknown";
        }
        return cause.getMessage() == null
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
