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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
     * Header names the JDK refuses to send on a WebSocket upgrade. A probe that
     * blindly forwarded {@code Environment.headers} would fail 100% of the time on
     * any environment that sets one and would look exactly like an outage — so they
     * are filtered, not caught and ignored. Compared lower-case;
     * {@code sec-websocket-*} is matched by prefix.
     *
     * <p><b>Where the rejection actually comes from</b> — not, despite appearances,
     * from {@code WebSocket.Builder#header}, which only records the pair.
     * {@code sec-websocket-*} is rejected inside {@code OpeningHandshake}'s
     * constructor and the other five by {@code HttpRequest.Builder.header} against
     * {@code jdk.internal.net.http.common.Utils.DISALLOWED_HEADERS_SET}
     * ({@code connection}, {@code content-length}, {@code expect}, {@code host},
     * {@code upgrade}). Both happen during {@code buildAsync}, which is why an
     * unfiltered name would surface at the generic {@code RuntimeException} arm of
     * {@link #probe} rather than at the {@code builder.header} call — an
     * {@code Outcome.ERROR} that no amount of origin health could ever clear.
     * {@code expect} is in the list for that reason and no other: it is unlikely in
     * today's data and it is one word.
     *
     * <p><b>{@code Host} is stripped, and every environment configures one.</b>
     * {@code EnvironmentService.validateAndMergeWsHeaders} <em>rejects</em> an
     * environment that supplies no {@code Host} and {@code Origin}, so this filter
     * fires on 100% of environments. The consequence is real and is accepted here:
     * on an environment whose configured {@code Host} differs from the URL
     * authority, <b>the probe reaches a different vhost than the bots do</b>, and
     * with AD-2's {@code status < 500 ⇒ healthy} a default vhost answering 404 while
     * the real origin is down reads as "serving again" and authorises attempts. The
     * cost of that is one budgeted attempt (AD-8); the mechanical escape hatch, if it
     * ever matters, is the {@code jdk.httpclient.allowRestrictedHeaders=host} net
     * property, which would let this filter pass {@code Host} through.
     */
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "host", "connection", "upgrade", "content-length", "expect");

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
        CompletableFuture<WebSocket> handshake = null;
        try {
            WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                    .connectTimeout(timeout);
            applyHeaders(builder, wsUrl, headers);

            handshake = builder.buildAsync(URI.create(wsUrl), NOOP_LISTENER);
            webSocket = handshake.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new ProbeResult(Outcome.OPEN, elapsedMillis(startNanos), "handshake completed");
        } catch (ExecutionException e) {
            return classify(e.getCause(), elapsedMillis(startNanos));
        } catch (TimeoutException e) {
            abandon(handshake);
            return new ProbeResult(Outcome.TIMEOUT, elapsedMillis(startNanos),
                    "no response within " + timeout.toMillis() + "ms");
        } catch (InterruptedException e) {
            abandon(handshake);
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
     * Give up on a handshake that has not completed, without leaking it.
     * <p>
     * {@code get(timeout)} does <b>not</b> cancel the underlying task, so a bare
     * timeout return would drop the future on the floor: if the handshake completed
     * a moment later the JDK would hold an open WebSocket with a no-op listener that
     * nobody ever aborts, plus its connection-pool entry, until the peer or an idle
     * timer killed it. That fires once per tick per URL against exactly the sort of
     * degraded origin this component exists to watch — up to 60 leaked sockets an
     * hour per sick environment, for as long as it stays sick, in the JVM whose last
     * outage (2026-06-30) was unbounded reconnect threads.
     * <p>
     * Cancel <em>and</em> abort-on-completion, because the two are not
     * interchangeable: cancel loses the race if the WebSocket is already constructed,
     * and {@code whenComplete} alone would let a hung connect keep running.
     */
    private static void abandon(CompletableFuture<WebSocket> handshake) {
        if (handshake == null) {
            return;
        }
        handshake.cancel(true);
        handshake.whenComplete((ws, error) -> {
            if (ws != null) {
                ws.abort();
            }
        });
    }

    /**
     * Classify the cause of a failed handshake (AD-2). Package-private so the
     * classification test can feed it synthetic causes with no network.
     */
    ProbeResult classify(Throwable cause, long latencyMillis) {
        if (cause instanceof WebSocketHandshakeException handshake) {
            if (handshake.getResponse() == null) {
                // No response to read a status from. This used to fall through as
                // status 0, and 0 < 500, so it classified HTTP_4XX and therefore
                // *healthy* — the one unclassifiable condition in this class that
                // landed on the side which authorises a recovery attempt, printing
                // "HTTP 0" as its evidence. Every other unknown is ERROR; so is this.
                return new ProbeResult(Outcome.ERROR, latencyMillis,
                        "handshake failed with no response");
            }
            int status = handshake.getResponse().statusCode();
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
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null || entry.getValue() == null || isRestricted(name)) {
                continue;
            }
            builder.header(name, entry.getValue());
        }
        if (!skippedHeadersLogged.contains(wsUrl)) {
            // Read through restrictedNames rather than accumulating a second list in
            // the loop above: the test asserts that method, and a private copy of the
            // same filter is a copy that can drift from the loop that runs.
            List<String> skipped = restrictedNames(headers);
            if (!skipped.isEmpty() && skippedHeadersLogged.add(wsUrl)) {
                log.debug("ws probe {}: skipping restricted header(s) {}", wsUrl, skipped);
            }
        }
    }

    /** Whether {@link WebSocket.Builder#header} would reject this header name. */
    static boolean isRestricted(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return RESTRICTED_HEADERS.contains(lower) || lower.startsWith(RESTRICTED_PREFIX);
    }

    /**
     * The header names of {@code headers} this probe skips, in encounter order.
     * Used by {@link #applyHeaders} for its once-per-URL DEBUG line, and by the
     * classification test — the same method in both, so the test cannot be asserting
     * a filter the production path does not use.
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
