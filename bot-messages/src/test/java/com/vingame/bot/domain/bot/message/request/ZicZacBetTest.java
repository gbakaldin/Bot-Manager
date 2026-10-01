package com.vingame.bot.domain.bot.message.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The P_114 / RIK {@code ziczacPlugin} bet body, pinned at the <b>wire</b>
 * (RIK_114_ZICZAC AD-10 / AD-11, Amendment A1 item 5).
 * <p>
 * Every assertion reads the serialized JSON, never a getter and never a round-trip:
 * the failure this phase ends is a frame that <i>looks</i> right and carries a key the
 * server does not know (or lacks one it needs — {@code c}), and a round-trip proves
 * nothing about either.
 * <p>
 * The target, copied from the capture
 * ({@code bot-messages/src/test/resources/captures/rik-ziczacPlugin-12000.jsonl}):
 * <pre>{@code {"cmd":12002,"b":60000,"c":1,"sid":1995084,"aid":1}}</pre>
 */
@DisplayName("ZicZacBet — the 114 ziczac bet body, at the wire")
class ZicZacBetTest {

    /** The mapper the bot actually serializes outbound frames with: a bare one. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Body body(Object message) throws Exception {
        Field f = ActionRequestMessage.class.getDeclaredField("body");
        f.setAccessible(true);
        return (Body) f.get(message);
    }

    private static String bodyJson(Object message) throws Exception {
        return MAPPER.writeValueAsString(body(message));
    }

    @Test
    @DisplayName("the key set is EXACTLY {cmd, b, c, sid, aid}")
    void keySetIsExact() throws Exception {
        ZicZacBet bet = new ZicZacBet(12002, "MiniGame", "ziczacPlugin", 60_000L, 1995084L);

        JsonNode node = MAPPER.readTree(bodyJson(bet));

        assertThat(node.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("cmd", "b", "c", "sid", "aid");
    }

    @Test
    @DisplayName("there is NO eid (AD-11), NO v and NO iAc — those are stock's keys, not this game's")
    void noEidNoStockKeys() throws Exception {
        ZicZacBet bet = new ZicZacBet(12002, "MiniGame", "ziczacPlugin", 60_000L, 1995084L);

        String json = bodyJson(bet);
        JsonNode node = MAPPER.readTree(json);

        assertThat(node.has("eid")).isFalse();
        assertThat(node.has("v")).isFalse();
        assertThat(node.has("iAc")).isFalse();
        assertThat(json).doesNotContain("\"eid\"").doesNotContain("\"v\"").doesNotContain("iAc");
    }

    @Test
    @DisplayName("c == 1, always (AD-10) — the engine debits exactly `amount` per send")
    void ballCountIsPinnedToOne() throws Exception {
        for (long stake : new long[] {60_000L, 1_060_000L, 50_000_000L}) {
            JsonNode node = MAPPER.readTree(bodyJson(
                    new ZicZacBet(12002, "MiniGame", "ziczacPlugin", stake, 1995084L)));

            assertThat(node.get("c").isInt()).as("c is an integer").isTrue();
            assertThat(node.get("c").asInt()).as("c for stake %d", stake).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the values are the captured frame's: cmd 12002, b 60000, c 1, sid, aid 1")
    void valuesMatchTheCapturedFrame() throws Exception {
        ZicZacBet bet = new ZicZacBet(12002, "MiniGame", "ziczacPlugin", 60_000L, 1995084L);

        JsonNode node = MAPPER.readTree(bodyJson(bet));

        assertThat(node.get("cmd").asInt()).isEqualTo(12002);
        assertThat(node.get("b").asLong()).isEqualTo(60_000L);
        assertThat(node.get("c").asInt()).isEqualTo(1);
        assertThat(node.get("sid").asLong()).isEqualTo(1995084L);
        assertThat(node.get("aid").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the stake rides b as a per-ball amount, not a round total — 1 060 000 is emitted as-is")
    void stakeIsPerBallAndUntouched() throws Exception {
        // The capture shows 60 000 and 1 060 000 both accepted; whatever the strategy
        // hands over is what goes on the wire, unscaled by c (which is 1 anyway).
        JsonNode node = MAPPER.readTree(bodyJson(
                new ZicZacBet(12002, "MiniGame", "ziczacPlugin", 1_060_000L, 1995084L)));

        assertThat(node.get("b").asLong()).isEqualTo(1_060_000L);
    }

    @Test
    @DisplayName("every key is all-lowercase, so no @JsonProperty is needed and none is present — the <lower><UPPER> trap does not apply")
    void noKeyNeedsAnExplicitJsonProperty() throws Exception {
        // RikStockBet's iAc and RikStockCommit's sId need @JsonProperty because Jackson
        // folds the whole leading uppercase run of a getter name. Nothing here has one.
        // Pinned as a statement about the wire rather than about annotations: if a
        // mixed-case key ever lands on this frame, this test names the rule to read.
        JsonNode node = MAPPER.readTree(bodyJson(
                new ZicZacBet(12002, "MiniGame", "ziczacPlugin", 60_000L, 1995084L)));

        node.fieldNames().forEachRemaining(key ->
                assertThat(key).as("key %s must be all-lowercase", key).isEqualTo(key.toLowerCase()));
        for (Field f : ZicZacBet.BetData.class.getDeclaredFields()) {
            assertThat(f.getAnnotations())
                    .as("field %s carries no annotation", f.getName())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("cmd derives from the offset: 9000 -> 12002, 10000 -> 13002 (no hardcoded CMD)")
    void cmdDerivesFromOffset() throws Exception {
        // Built the way ZicZacRequest builds it: cmdPrefix + 3002.
        JsonNode ziczac = MAPPER.readTree(bodyJson(
                new ZicZacBet(9000 + 3002, "MiniGame", "ziczacPlugin", 5000L, 42L)));
        JsonNode other = MAPPER.readTree(bodyJson(
                new ZicZacBet(10000 + 3002, "MiniGame", "ziczacPlugin", 5000L, 42L)));

        assertThat(ziczac.get("cmd").asInt()).isEqualTo(12002);
        assertThat(other.get("cmd").asInt()).isEqualTo(13002);
    }

    @Test
    @DisplayName("the envelope is [\"6\", zone, plugin, body] — element 0 the STRING \"6\", as on every product (AD-26)")
    void envelopeCarriesZoneThenPlugin() throws Exception {
        ZicZacBet bet = new ZicZacBet(12002, "MiniGame", "ziczacPlugin", 60_000L, 1995084L);

        String frame = bet.serialize(MAPPER);
        JsonNode node = MAPPER.readTree(frame);

        assertThat(frame).startsWith("[\"6\",\"MiniGame\",\"ziczacPlugin\",{");
        assertThat(node.get(1).asText()).isEqualTo("MiniGame");
        assertThat(node.get(2).asText()).isEqualTo("ziczacPlugin");
        assertThat(node.get(3).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("cmd", "b", "c", "sid", "aid");
    }
}
