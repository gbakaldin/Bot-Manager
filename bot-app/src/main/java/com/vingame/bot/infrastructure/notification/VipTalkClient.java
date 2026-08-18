package com.vingame.bot.infrastructure.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Transport for the VipTalk messenger HTTP API — the only channel bot-manager
 * publishes alerts and announcements on.
 * <p>
 * One POST per call, regardless of room count (VIPTALK_ALERTING AD-4):
 * <pre>
 * POST {baseUrl}/v1/bot/{botToken}/sendMessage
 * Content-Type: application/x-www-form-urlencoded
 *
 * text=&lt;message&gt;&amp;roomIds=&lt;room1&gt;&amp;roomIds=&lt;room2&gt;
 * </pre>
 * The bot token sits in the URL <em>path</em>, not a header. {@code roomIds} is a
 * repeated form parameter, so a fleet-wide broadcast is a single request.
 * Recipients are Matrix room IDs ({@code !aBcDeF:matrix-uat.viptalk.org}).
 * <p>
 * Built on {@link java.net.http.HttpClient}, the established HTTP idiom here (see
 * {@code ApiGatewayClient}, {@code HttpPrometheusQueryClient}) — neither WebClient
 * nor RestClient is on the classpath.
 * <p>
 * <b>Never throws</b> (AD-3). Alerting is a side channel; a VipTalk outage must not
 * surface as a failure in whatever was being reported on. Every error path returns a
 * {@link VipTalkSendResult}.
 * <p>
 * Logging (CLAUDE.md): this is request-scoped infrastructure, not group lifecycle —
 * successful sends DEBUG, failures WARN. Nothing here is INFO except the one-shot
 * startup line describing the resolved configuration.
 */
@Slf4j
@Component
public class VipTalkClient {

    private final boolean enabled;
    private final String baseUrl;
    private final String botToken;
    private final Duration requestTimeout;
    private final HttpPoster poster;

    /**
     * The constructor Spring uses.
     * <p>
     * {@code @Autowired} is <b>load-bearing</b>, not decoration: this class also declares
     * a package-private test seam below, and Spring only auto-selects a constructor when
     * there is exactly one. With two candidates and no annotation it falls back to a no-arg
     * constructor that does not exist, and the context fails at startup with
     * {@code NoSuchMethodException: VipTalkClient.<init>()} — which is exactly what took
     * bot-manager into a crash loop on the 2026-08-18 staging deploy. Do not remove it
     * while the seam exists. Cf. {@code AlertMessageFormatter}, which has the same shape
     * and the same annotation.
     */
    @Autowired
    public VipTalkClient(
            @Value("${viptalk.enabled:false}") boolean enabled,
            @Value("${viptalk.base-url:https://api.viptalk.org}") String baseUrl,
            @Value("${viptalk.bot-token:}") String botToken,
            @Value("${viptalk.connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${viptalk.read-timeout-seconds:10}") int readTimeoutSeconds) {

        // Strip any trailing slash so path concatenation is unambiguous.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.botToken = botToken == null ? "" : botToken.strip();
        this.requestTimeout = Duration.ofSeconds(readTimeoutSeconds);

        // AD-3: enabled with no token is a misconfiguration, but failing the context
        // would take the whole bot manager down over a side channel. Self-disable loudly.
        boolean usable = enabled && !this.botToken.isEmpty();
        if (enabled && this.botToken.isEmpty()) {
            log.error("VipTalk alerting is enabled but viptalk.bot-token is blank — "
                    + "alerting disabled. Set VIPTALK_BOT_TOKEN and restart.");
        }
        this.enabled = usable;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
        this.poster = (url, body) -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(requestTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new RawResponse(response.statusCode(), response.body());
        };

        log.info("VipTalk alerting {} (baseUrl={})", this.enabled ? "enabled" : "disabled", this.baseUrl);
    }

    /**
     * Test seam — injects a canned {@link HttpPoster} so the request-shaping logic can
     * be exercised without a live server.
     */
    VipTalkClient(boolean enabled, String baseUrl, String botToken, HttpPoster poster) {
        this.enabled = enabled && botToken != null && !botToken.isBlank();
        this.baseUrl = baseUrl;
        this.botToken = botToken == null ? "" : botToken;
        this.requestTimeout = Duration.ofSeconds(10);
        this.poster = poster;
    }

    /**
     * Whether this channel will actually attempt delivery. False when
     * {@code viptalk.enabled=false} or the bot token is blank.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sends one message to one or more rooms in a single request.
     *
     * @param text    the message body. VipTalk {@code sendMessage} takes plain text —
     *                there is no subject field, so any title must already be part of this.
     * @param roomIds Matrix room IDs to deliver to. Empty ⇒ {@link VipTalkSendResult.Outcome#SKIPPED}.
     * @return the outcome; never {@code null}, never throws.
     */
    public VipTalkSendResult send(String text, List<String> roomIds) {
        if (!enabled) {
            log.debug("VipTalk send skipped — channel disabled");
            return VipTalkSendResult.skipped("VipTalk channel is disabled");
        }
        if (roomIds == null || roomIds.isEmpty()) {
            log.debug("VipTalk send skipped — no rooms to deliver to");
            return VipTalkSendResult.skipped("no rooms configured for this alert");
        }
        if (text == null || text.isBlank()) {
            return VipTalkSendResult.skipped("empty message body");
        }

        String url = baseUrl + "/v1/bot/" + botToken + "/sendMessage";
        String body = buildFormBody(text, roomIds);

        RawResponse response;
        try {
            response = poster.post(url, body);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String detail = redact(e.getMessage());
            log.warn("VipTalk send to {} room(s) failed in transport: {}", roomIds.size(), detail);
            return VipTalkSendResult.failed(roomIds.size(), 0, "VipTalk transport failure: " + detail);
        }

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.debug("VipTalk message delivered to {} room(s) (HTTP {})", roomIds.size(), response.statusCode());
            return VipTalkSendResult.sent(roomIds.size(), response.statusCode());
        }

        // Body is included: VipTalk reports the reason (bad token, bot not in room) there,
        // and those are exactly the failures an operator has to act on. It is REDACTED
        // first: VipTalk echoes the request path back in the error body's `path` field
        // (see docs/reviews/VIPTALK_ALERTING_V2/spike.md) and the bot token is in that
        // path — so the single most likely 4xx, a bad or expired token, would otherwise
        // write the token to WARN and ship it to Loki.
        log.warn("VipTalk returned HTTP {} for {} room(s): {}",
                response.statusCode(), roomIds.size(), redact(response.body()));
        return VipTalkSendResult.failed(roomIds.size(), response.statusCode(),
                "VipTalk returned HTTP " + response.statusCode());
    }

    /**
     * Builds the {@code application/x-www-form-urlencoded} body: a single {@code text}
     * field followed by one {@code roomIds} field per room.
     * <p>
     * Package-private and free of HTTP/Spring state so the encoding can be unit-tested
     * directly, mirroring {@code HttpPrometheusQueryClient.parse}.
     */
    static String buildFormBody(String text, List<String> roomIds) {
        StringBuilder body = new StringBuilder("text=").append(urlEncode(text));
        for (String roomId : roomIds) {
            body.append("&roomIds=").append(urlEncode(roomId));
        }
        return body.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Replaces every occurrence of the bot token with {@code ***}.
     * <p>
     * Applied to anything derived from a response body or a transport exception before
     * it reaches a log or a {@link VipTalkSendResult} detail. The token is a known exact
     * string here, so this is a substring replace rather than a pattern guess — no
     * false positives, no misses. Both the URL-encoded and the raw form are covered:
     * the token rides in the path unencoded, but an echoed path may come back encoded.
     * <p>
     * A blank token means the channel is disabled and nothing is sent; the guard is
     * there so {@code replace("", …)} — which would splice {@code ***} between every
     * character — can never run.
     */
    String redact(String value) {
        if (value == null || botToken.isEmpty()) {
            return value;
        }
        String redacted = value.replace(botToken, "***");
        String encoded = urlEncode(botToken);
        return encoded.equals(botToken) ? redacted : redacted.replace(encoded, "***");
    }

    /** Minimal HTTP response view — just what the client needs, and trivially fakeable in tests. */
    record RawResponse(int statusCode, String body) {}

    /** Abstraction over the POST, so tests can run without a network. */
    @FunctionalInterface
    interface HttpPoster {
        RawResponse post(String url, String body) throws IOException, InterruptedException;
    }
}
