package com.vingame.bot.infrastructure.notification;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
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
}
