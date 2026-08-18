package com.vingame.bot.infrastructure.notification;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the VipTalk transport: request shaping and the guarantee that it never
 * throws (VIPTALK_ALERTING AD-3).
 */
class VipTalkClientTest {

    private static final String TOKEN = "test-token";
    private static final String ROOM_A = "!roomA:matrix-uat.viptalk.org";
    private static final String ROOM_B = "!roomB:matrix-uat.viptalk.org";

    @Test
    void buildFormBody_encodesTextAndRepeatsRoomIds() {
        String body = VipTalkClient.buildFormBody("hello world", List.of(ROOM_A, ROOM_B));

        assertEquals("text=hello+world"
                        + "&roomIds=%21roomA%3Amatrix-uat.viptalk.org"
                        + "&roomIds=%21roomB%3Amatrix-uat.viptalk.org",
                body);
    }

    @Test
    void buildFormBody_escapesFormDelimitersInMessageText() {
        // A message containing & or = must not be able to inject extra form fields.
        String body = VipTalkClient.buildFormBody("a&roomIds=!evil:host b=c", List.of(ROOM_A));

        assertTrue(body.startsWith("text=a%26roomIds%3D%21evil%3Ahost+b%3Dc&roomIds="));
        assertEquals(1, body.split("&roomIds=", -1).length - 1, "only the real room parameter survives");
    }

    @Test
    void buildFormBody_preservesMultilineAndUnicode() {
        String body = VipTalkClient.buildFormBody("🔴 CRITICAL\nline two", List.of(ROOM_A));

        assertTrue(body.startsWith("text=%F0%9F%94%B4+CRITICAL%0Aline+two&roomIds="), body);
    }

    @Test
    void send_postsToBotTokenPathAndReportsSent() throws Exception {
        AtomicReference<String> seenUrl = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        VipTalkClient client = new VipTalkClient(true, "https://api.viptalk.org", TOKEN,
                (url, body) -> {
                    seenUrl.set(url);
                    seenBody.set(body);
                    return new VipTalkClient.RawResponse(200, "{\"ok\":true}");
                });

        VipTalkSendResult result = client.send("hi", List.of(ROOM_A, ROOM_B));

        assertEquals("https://api.viptalk.org/v1/bot/" + TOKEN + "/sendMessage", seenUrl.get());
        assertTrue(seenBody.get().startsWith("text=hi&roomIds="));
        assertEquals(VipTalkSendResult.Outcome.SENT, result.outcome());
        assertEquals(2, result.roomCount());
        assertEquals(200, result.statusCode());
    }

    @Test
    void send_nonSuccessStatusIsFailedNotThrown() {
        VipTalkClient client = new VipTalkClient(true, "https://api.viptalk.org", TOKEN,
                (url, body) -> new VipTalkClient.RawResponse(403, "bot not in room"));

        VipTalkSendResult result = client.send("hi", List.of(ROOM_A));

        assertEquals(VipTalkSendResult.Outcome.FAILED, result.outcome());
        assertEquals(403, result.statusCode());
        assertTrue(result.detail().contains("403"));
    }

    @Test
    void send_transportFailureIsFailedNotThrown() {
        VipTalkClient client = new VipTalkClient(true, "https://api.viptalk.org", TOKEN,
                (url, body) -> {
                    throw new IOException("connection reset");
                });

        VipTalkSendResult result = client.send("hi", List.of(ROOM_A));

        assertEquals(VipTalkSendResult.Outcome.FAILED, result.outcome());
        assertEquals(0, result.statusCode());
        assertTrue(result.detail().contains("connection reset"));
    }

    @Test
    void send_disabledChannelSkipsWithoutCallingTransport() {
        VipTalkClient client = new VipTalkClient(false, "https://api.viptalk.org", TOKEN,
                (url, body) -> {
                    throw new AssertionError("transport must not be called when disabled");
                });

        assertFalse(client.isEnabled());
        assertEquals(VipTalkSendResult.Outcome.SKIPPED, client.send("hi", List.of(ROOM_A)).outcome());
    }

    @Test
    void enabledWithBlankTokenSelfDisables() {
        // AD-3: a misconfigured side channel must not be able to take the app down,
        // and must not attempt a send against an empty token path either.
        VipTalkClient client = new VipTalkClient(true, "https://api.viptalk.org", "  ",
                (url, body) -> {
                    throw new AssertionError("transport must not be called without a token");
                });

        assertFalse(client.isEnabled());
        assertEquals(VipTalkSendResult.Outcome.SKIPPED, client.send("hi", List.of(ROOM_A)).outcome());
    }

    @Test
    void send_withNoRoomsSkips() {
        VipTalkClient client = new VipTalkClient(true, "https://api.viptalk.org", TOKEN,
                (url, body) -> {
                    throw new AssertionError("transport must not be called with no rooms");
                });

        VipTalkSendResult result = client.send("hi", List.of());

        assertEquals(VipTalkSendResult.Outcome.SKIPPED, result.outcome());
        assertEquals(0, result.roomCount());
    }

    // --- token redaction -------------------------------------------------------------
    //
    // VipTalk echoes the full request path back in the `path` field of its error bodies
    // (docs/reviews/VIPTALK_ALERTING_V2/spike.md), and the bot token IS that path. The
    // 4xx that guarantees this branch runs is a bad or expired token — the exact moment
    // the token is most sensitive — so the body must never be logged verbatim.

    private static final String SECRET = "SUPERSECRET-TOKEN-123";

    private CapturingAppender appender;
    private LoggerContext ctx;
    private LoggerConfig loggerConfig;
    private Level previousLevel;

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            loggerConfig.removeAppender(appender.getName());
            loggerConfig.setLevel(previousLevel);
            ctx.updateLoggers();
            appender = null;
        }
    }

    private void captureClientLogs() {
        appender = new CapturingAppender("CapturingAppender-viptalk");
        appender.start();
        ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = ctx.getConfiguration().getLoggerConfig(VipTalkClient.class.getName());
        previousLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.ALL);
        ctx.updateLoggers();
    }

    private List<String> loggedMessages() {
        return appender.events().stream()
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    @Test
    @DisplayName("a 4xx body echoing the request path is logged with the token redacted")
    void send_nonSuccessBodyIsRedactedBeforeLogging() {
        captureClientLogs();
        String echoedBody = "{\"statusCode\":400,\"error\":\"Cannot send empty message\","
                + "\"path\":\"/v1/bot/" + SECRET + "/sendMessage\"}";
        VipTalkClient client = new VipTalkClient(true, "https://api.viptalk.org", SECRET,
                (url, body) -> new VipTalkClient.RawResponse(400, echoedBody));

        VipTalkSendResult result = client.send("hi", List.of(ROOM_A));

        assertEquals(VipTalkSendResult.Outcome.FAILED, result.outcome());
        assertFalse(loggedMessages().stream().anyMatch(m -> m.contains(SECRET)),
                "the bot token must never reach a log line: " + loggedMessages());
        assertTrue(loggedMessages().stream().anyMatch(m -> m.contains("/v1/bot/***/sendMessage")),
                "the rest of the body must survive so the operator can still act: " + loggedMessages());
        assertFalse(result.detail().contains(SECRET), "nor the result detail");
    }

    @Test
    @DisplayName("a transport exception whose message carries the URL is logged with the token redacted")
    void send_transportFailureMessageIsRedacted() {
        captureClientLogs();
        VipTalkClient client = new VipTalkClient(true, "https://api.viptalk.org", SECRET,
                (url, body) -> {
                    throw new IOException("failed to connect to " + url);
                });

        VipTalkSendResult result = client.send("hi", List.of(ROOM_A));

        assertEquals(VipTalkSendResult.Outcome.FAILED, result.outcome());
        assertFalse(result.detail().contains(SECRET), "detail is surfaced through the API: " + result.detail());
        assertFalse(loggedMessages().stream().anyMatch(m -> m.contains(SECRET)),
                "the bot token must never reach a log line: " + loggedMessages());
    }

    @Test
    @DisplayName("redact leaves a blank-token client's text alone rather than splicing *** everywhere")
    void redact_isANoOpWithoutAToken() {
        VipTalkClient client = new VipTalkClient(true, "https://api.viptalk.org", "",
                (url, body) -> new VipTalkClient.RawResponse(200, ""));

        assertEquals("nothing to hide", client.redact("nothing to hide"));
        assertEquals(null, client.redact(null));
    }

    /** Minimal in-memory log4j2 appender for asserting what actually reaches a log line. */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender(String name) {
            super(name, null, PatternLayout.createDefaultLayout(), false, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> events() {
            return new ArrayList<>(events);
        }
    }
}
