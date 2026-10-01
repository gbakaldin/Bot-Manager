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
 *   <li>it is <b>Cloudflare's</b> block page: when the body is available (the HTTP path), the body
 *       must carry one of {@code cf-error-details}, {@code Sorry, you have been blocked} or
 *       {@code Attention Required! | Cloudflare}; only when the transport kept no body (the WS
 *       path) is {@code content-type: text/html} accepted in its place.</li>
 * </ol>
 * <b>Conjunct 2 proves almost nothing on its own</b> (review-phase5): Cloudflare stamps
 * {@code server: cloudflare} and a {@code cf-ray} on <em>every</em> proxied response, so every gwms
 * answer satisfies it. On the HTTP path the rule therefore rests on the body. "403 + text/html" was
 * the rule until then, and the gwms gateways are IIS, which serves its own HTML 403 pages (403.6
 * "IP address rejected" for an endpoint this host is not allow-listed for): that would have opened
 * the circuit for the whole brand, the next probe would close it an hour later, and the next such
 * request would reopen it — an hour-long outage per cycle.
 * <p>
 * <b>The WS path's limit</b> (QA phase 5, finding 1): ws-parser installs
 * {@code HttpObjectAggregator(8192)}, so an edge page larger than 8 KB surfaces as a
 * {@code TooLongHttpContentException} with no response attached and is <b>not</b> classified. The
 * captured block page is ~5 KB (Cloudflare's 1015/1020 pages are of that size); a larger challenge
 * or interstitial page would be missed on the WS path only — the HTTP path has no such limit, and
 * the next login or balance read on the brand would still open the circuit.
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
     * @param body   the response body, or {@code null} when the transport did not keep it — which
     *               is the only case in which {@code content-type: text/html} alone counts
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
        boolean isPage;
        if (body != null) {
            // The HTTP path: the body is the evidence. An origin's own HTML 403 transiting the
            // edge carries the same server/cf-ray headers and must not open a circuit.
            isPage = body.contains("cf-error-details")
                    || body.contains("Sorry, you have been blocked")
                    || body.contains("Attention Required! | Cloudflare");
        } else {
            // The WS path: Netty keeps no body, so the content type is all there is.
            String contentType = header.apply("content-type").orElse("");
            isPage = contentType.toLowerCase(Locale.ROOT).trim().startsWith("text/html");
        }
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
