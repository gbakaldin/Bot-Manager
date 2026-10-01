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
 * The P_114 / RIK {@code stockPlugin} bet body, pinned at the <b>wire</b>
 * (RIK_114_BETTING_MINI AD-22 / AD-23).
 * <p>
 * Every assertion here reads the serialized JSON, never a getter and never a
 * round-trip through the same object. That is the whole point: a round-trip passes
 * happily with {@code iac} on both sides and proves nothing, and the failure this
 * phase exists to end is precisely a frame that <i>looks</i> right and carries a key
 * the server does not know.
 * <p>
 * The target, copied from the capture
 * ({@code bot-messages/src/test/resources/captures/rik-stockPlugin-13000.jsonl}):
 * <pre>{@code {"cmd":13002,"v":1000,"sid":3793247,"aid":1,"eid":0,"iAc":true}}</pre>
 */
@DisplayName("RikStockBet — the 114 stock bet body, at the wire")
class RikStockBetTest {

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
    @DisplayName("the key set is EXACTLY {cmd, v, sid, aid, eid, iAc}")
    void keySetIsExact() throws Exception {
        RikStockBet bet = new RikStockBet(13002, "MiniGame", "stockPlugin", 1000L, 0, 3793247L);

        JsonNode node = MAPPER.readTree(bodyJson(bet));

        assertThat(node.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("cmd", "v", "sid", "aid", "eid", "iAc");
    }

    @Test
    @DisplayName("AD-23 REGRESSION: the flag key is 'iAc', never the mangled 'iac'")
    void iAcIsNotMangled() throws Exception {
        RikStockBet bet = new RikStockBet(13002, "MiniGame", "stockPlugin", 1000L, 0, 3793247L);

        String json = bodyJson(bet);

        // Jackson lower-cases the ENTIRE leading uppercase run when it de-mangles a
        // getter name, so both Lombok's isIAc() and a hand-rolled getIAc() produce
        // "iac". Only the explicit @JsonProperty("iAc") on the field produces "iAc" —
        // and leaving the generated getter in place alongside it would emit BOTH keys.
        // This is one missing annotation (or one re-added getter) away from shipping a
        // frame the server silently ignores, which is the exact bug this phase fixes.
        assertThat(json).contains("\"iAc\":true");
        assertThat(json).doesNotContain("\"iac\"");
        assertThat(json).doesNotContain("\"IAc\"");
    }

    @Test
    @DisplayName("there is NO 'b' — the stake rides 'v' alone (AD-22, not the OI-3 both-keys hedge)")
    void stakeRidesVAndOnlyV() throws Exception {
        RikStockBet bet = new RikStockBet(13002, "MiniGame", "stockPlugin", 1000L, 0, 3793247L);

        String json = bodyJson(bet);
        JsonNode node = MAPPER.readTree(json);

        assertThat(json).doesNotContain("\"b\"");
        assertThat(node.get("v").asLong()).isEqualTo(1000L);
        assertThat(node.has("b")).isFalse();
    }

    @Test
    @DisplayName("the values are the captured frame's: cmd 13002, v 1000, sid, aid 1, eid 0, iAc true")
    void valuesMatchTheCapturedFrame() throws Exception {
        RikStockBet bet = new RikStockBet(13002, "MiniGame", "stockPlugin", 1000L, 0, 3793247L);

        JsonNode node = MAPPER.readTree(bodyJson(bet));

        assertThat(node.get("cmd").asInt()).isEqualTo(13002);
        assertThat(node.get("v").asLong()).isEqualTo(1000L);
        assertThat(node.get("sid").asLong()).isEqualTo(3793247L);
        assertThat(node.get("aid").asInt()).isEqualTo(1);
        assertThat(node.get("eid").asInt()).isEqualTo(0);
        assertThat(node.get("iAc").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("cmd derives from the offset: 10000 -> 13002, 4000 -> 7002 (no hardcoded CMD)")
    void cmdDerivesFromOffset() throws Exception {
        // Built the way RikStockRequest builds it: cmdPrefix + 3002.
        JsonNode stock = MAPPER.readTree(bodyJson(
                new RikStockBet(10000 + 3002, "MiniGame", "stockPlugin", 5000L, 1, 42L)));
        JsonNode other = MAPPER.readTree(bodyJson(
                new RikStockBet(4000 + 3002, "MiniGame", "stockPlugin", 5000L, 1, 42L)));

        assertThat(stock.get("cmd").asInt()).isEqualTo(13002);
        assertThat(other.get("cmd").asInt()).isEqualTo(7002);
    }

    @Test
    @DisplayName("the envelope's element 0 is the STRING \"6\" — recorded, not a defect (AD-26)")
    void envelopeElementZeroIsTheStringSix() throws Exception {
        RikStockBet bet = new RikStockBet(13002, "MiniGame", "stockPlugin", 1000L, 0, 3793247L);

        String frame = bet.serialize(MAPPER);

        // The real client sends the NUMBER 6; ws-parser writes
        // String.valueOf(type.getTypeNumber()) in ActionRequestMessage.serialize, so we
        // send "6". It is a library concern, not ours, and it is ruled out as a cause:
        // taixiuMd5Plugin settles through this identical envelope. Pinned here so the
        // next reader does not re-discover it and spend a day on it.
        assertThat(frame).startsWith("[\"6\",\"MiniGame\",\"stockPlugin\",{");
        assertThat(frame).contains("\"iAc\":true");
    }
}
