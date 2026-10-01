package com.vingame.bot.infrastructure.gateway;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Tells a <b>Cloudflare edge block</b> apart from every other answer a gwms gateway can give
 * (GATEWAY_REQUEST_BUDGET AD-13).
 * <p>
 * <b>Why this exists.</b> When this host breaches Cloudflare's 1,000-requests-per-5-minutes rule
 * the edge answers {@code 403} with an HTML page — {@code server: cloudflare}, a {@code cf-ray},
 * {@code content-type: text/html}, "Sorry, you have been blocked" — to <em>every</em> request
 * from this host for that gateway, for possibly a day (A16). Before this class nothing in the app
 * distinguished it: the login parsed the page as JSON and reported {@code Unexpected character
 * ('<')}, which was misdiagnosed for an hour on 2026-09-17. The gateway's own refusals are HTTP
 * 200 JSON envelopes, so the block is the one real 403 in the system.
 * <p>
 * <b>The rule, all three conjuncts required</b>:
 * <ol>
 *   <li>status {@code 403} or {@code 429};</li>
 *   <li>{@code server} equals {@code cloudflare} (ignoring case) <b>or</b> a {@code cf-ray}
 *       header is present — the response really came from the edge;</li>
 *   <li>{@code content-type} is {@code text/html} <b>or</b> the body carries
 *       {@code cf-error-details} or {@code Sorry, you have been blocked} — it is a page, not an
 *       origin's JSON passed through the edge.</li>
 * </ol>
 * The third conjunct is what keeps a gateway's own JSON 403 that merely transits Cloudflare (and
 * therefore carries a {@code cf-ray}) from opening a circuit that would then refuse a whole brand.
 * <p>
 * <b>Two entry points, one rule.</b> {@link #classify(HttpResponse)} for the HTTP funnel, and
 * {@link #classifyHandshakeFailure(Throwable)} for a WebSocket upgrade, whose failure carries the
 * edge's response <em>headers</em> but not its body (Netty's handshaker keeps status and headers
 * only) — so on the WS path the content type alone satisfies the third conjunct. The WS hosts sit
 * behind the same rule as the API hosts (A15), so a WS-side block opens the same circuit.
 * <p>
 * Stateless and side-effect free: deciding what to <em>do</em> about a block is the budget's job.
 */
public final class CloudflareBlockDetector {

    private CloudflareBlockDetector() {
    }

    /** How many causes {@link #classifyHandshakeFailure} walks before giving up. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * The answer: whether this is an edge block, and the {@code cf-ray} if one was sent — the one
     * value an SA/back-office ticket needs.
     */
    public record Verdict(boolean edgeBlock, String cfRay) {

        public static final Verdict NOT_A_BLOCK = new Verdict(false, null);
    }

    /** Classify a response from the HTTP funnel. */
    public static Verdict classify(HttpResponse<String> response) {
        if (response == null) {
            return Verdict.NOT_A_BLOCK;
        }
        return classify(response.statusCode(), response.headers(), response.body());
    }

    /** Classify a status, the JDK's headers and a body (which may be {@code null}). */
    public static Verdict classify(int status, HttpHeaders headers, String body) {
        return classify(status, name -> headers == null ? Optional.empty() : headers.firstValue(name), body);
    }

    /**
     * The rule itself.
     *
     * @param header a case-insensitive header lookup
     * @param body   the response body, or {@code null} when the transport did not keep it
     */
    public static Verdict classify(int status, Function<String, Optional<String>> header, String body) {
        if (status != 403 && status != 429) {
            return Verdict.NOT_A_BLOCK;
        }
        String server = header.apply("server").orElse(null);
        String cfRay = header.apply("cf-ray").map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        boolean fromEdge = (server != null && "cloudflare".equalsIgnoreCase(server.trim())) || cfRay != null;
        if (!fromEdge) {
            return Verdict.NOT_A_BLOCK;
        }
        String contentType = header.apply("content-type").orElse("");
        boolean isPage = contentType.toLowerCase(Locale.ROOT).trim().startsWith("text/html")
                || (body != null && (body.contains("cf-error-details")
                        || body.contains("Sorry, you have been blocked")));
        return isPage ? new Verdict(true, cfRay) : Verdict.NOT_A_BLOCK;
    }

    /**
     * Classify the failure of a WebSocket upgrade — the second entry point (AD-13).
     * <p>
     * Walks the cause chain for Netty's {@code WebSocketClientHandshakeException} (what
     * {@code VingameWebSocketClient.connect()} rethrows through {@code handshakeFuture().sync()})
     * or the JDK's {@code WebSocketHandshakeException} (the anonymous environment probe), and
     * classifies the response either one carries. Best-effort by construction: anything else —
     * a refused connection, a TLS failure, a timeout — is not a block, because the edge did not
     * answer it with a page.
     * <p>
     * The walk is bounded and cycle-safe: a cause chain is arbitrary foreign data, and this runs on
     * a bot's reconnect thread.
     */
    public static Verdict classifyHandshakeFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH && seen.add(cause); depth++) {
            if (cause instanceof io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException netty) {
                io.netty.handler.codec.http.HttpResponse response = netty.response();
                if (response != null) {
                    io.netty.handler.codec.http.HttpHeaders headers = response.headers();
                    return classify(response.status().code(),
                            name -> Optional.ofNullable(headers.get(name)), null);
                }
            } else if (cause instanceof java.net.http.WebSocketHandshakeException jdk) {
                HttpResponse<?> response = jdk.getResponse();
                if (response != null) {
                    Object body = response.body();
                    return classify(response.statusCode(), response.headers(),
                            body instanceof String text ? text : null);
                }
            }
            cause = cause.getCause();
        }
        return Verdict.NOT_A_BLOCK;
    }
}
