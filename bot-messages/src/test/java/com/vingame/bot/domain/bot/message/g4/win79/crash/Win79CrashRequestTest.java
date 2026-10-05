package com.vingame.bot.domain.bot.message.g4.win79.crash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.message.request.CrashRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AVIATOR_BOT Phase 1 / AD-10: the outbound 119 Avatar frames, serialized exactly as a
 * bot sends them ({@code ActionRequestMessage.serialize}), against the real-client
 * capture ({@code docs/captures/aviator-119-2026-10-05.jsonl} L2, L16, L21).
 *
 * <p>The body is compared as a JSON tree, so key order does not matter but every key and
 * value does. Element 0 of the envelope is ws-parser's string {@code "6"} where the real
 * client sends the number {@code 6}; that is how every product's outbound frame goes out
 * (see {@code Win79CashoutRequestTest}), so it is asserted as-is rather than "fixed" here.
 */
@DisplayName("Win79CrashRequest — outbound frames match the capture")
class Win79CrashRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final CrashRequest AVIATOR =
            new Win79CrashMessageTypes().newRequest("MiniGame", "aviatorPlugin", 1700);

    private static JsonNode envelope(String serialized) throws Exception {
        JsonNode node = MAPPER.readTree(serialized);
        assertThat(node.isArray()).isTrue();
        assertThat(node).hasSize(4);
        assertThat(node.get(0).asInt()).as("ACTION_REQUEST category").isEqualTo(6);
        assertThat(node.get(1).asText()).isEqualTo("MiniGame");
        assertThat(node.get(2).asText()).isEqualTo("aviatorPlugin");
        return node;
    }

    @Test
    @DisplayName("bet: {cmd:1702, b, sid, aid:1, eid} — the captured L16 body")
    void betMatchesTheCapture() throws Exception {
        JsonNode frame = envelope(AVIATOR.bet(10_000L, 1_638_119L, 1).serialize(MAPPER));

        assertThat(frame.get(3)).isEqualTo(MAPPER.readTree(
                "{\"cmd\":1702,\"b\":10000,\"sid\":1638119,\"aid\":1,\"eid\":1}"));
    }

    @Test
    @DisplayName("bet on eid 2 carries eid 2 (captured L27)")
    void betOnNeytiri() throws Exception {
        JsonNode frame = envelope(AVIATOR.bet(50_000L, 1_638_120L, 2).serialize(MAPPER));

        assertThat(frame.get(3)).isEqualTo(MAPPER.readTree(
                "{\"cmd\":1702,\"b\":50000,\"sid\":1638120,\"aid\":1,\"eid\":2}"));
    }

    @Test
    @DisplayName("cash-out: {cmd:1703, sid, aid:1, eid} — the captured L21 body")
    void cashOutMatchesTheCapture() throws Exception {
        JsonNode frame = envelope(AVIATOR.cashOut(1_638_119L, 1).serialize(MAPPER));

        assertThat(frame.get(3)).isEqualTo(MAPPER.readTree(
                "{\"cmd\":1703,\"sid\":1638119,\"aid\":1,\"eid\":1}"));
    }

    @Test
    @DisplayName("subscribe: {cmd:1700} on aviatorPlugin — the captured L2 body")
    void subscribeBody() throws Exception {
        String serialized = AVIATOR.subscribe().serialize(MAPPER);

        assertThat(serialized).isEqualTo("[\"6\",\"MiniGame\",\"aviatorPlugin\",{\"cmd\":1700}]");
    }

    @Test
    @DisplayName("another offset shifts every cmd and keeps the plugin")
    void otherOffset() throws Exception {
        CrashRequest other = new Win79CrashMessageTypes().newRequest("MiniGame", "otherPlugin", 4200);

        assertThat(envelope0(other.subscribe().serialize(MAPPER)).get(3).get("cmd").asInt()).isEqualTo(4200);
        assertThat(envelope0(other.bet(1L, 2L, 1).serialize(MAPPER)).get(3).get("cmd").asInt()).isEqualTo(4202);
        assertThat(envelope0(other.cashOut(2L, 1).serialize(MAPPER)).get(3).get("cmd").asInt()).isEqualTo(4203);
        assertThat(envelope0(other.bet(1L, 2L, 1).serialize(MAPPER)).get(2).asText()).isEqualTo("otherPlugin");
    }

    private static JsonNode envelope0(String serialized) throws Exception {
        return MAPPER.readTree(serialized);
    }
}
