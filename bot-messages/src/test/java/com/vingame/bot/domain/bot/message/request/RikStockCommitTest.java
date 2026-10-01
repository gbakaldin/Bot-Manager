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
 * The P_114 / RIK {@code stockPlugin} per-bet commit frame, pinned at the <b>wire</b>
 * (RIK_114_BETTING_MINI AD-29 / AD-30).
 * <p>
 * Every assertion reads the serialized JSON — never a getter (there is none) and never
 * a round-trip through the same object. A round-trip passes happily with {@code sid} on
 * both sides and proves nothing; the failure this frame exists to end is precisely one
 * that <i>looks</i> right on the wire and is ignored.
 * <p>
 * The target, copied from the legacy Node stock bot ({@code *-rik-coins*.js}):
 * <pre>{@code {"cmd":13022,"sId":3793247}}</pre>
 */
@DisplayName("RikStockCommit — the 114 stock per-bet commit, at the wire")
class RikStockCommitTest {

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
    @DisplayName("the key set is EXACTLY {cmd, sId}")
    void keySetIsExact() throws Exception {
        RikStockCommit commit = new RikStockCommit(13022, "MiniGame", "stockPlugin", 3793247L);

        JsonNode node = MAPPER.readTree(bodyJson(commit));

        assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrder("cmd", "sId");
    }

    @Test
    @DisplayName("AD-30 REGRESSION: the session key is 'sId', never the folded 'sid'")
    void sIdIsNotFolded() throws Exception {
        RikStockCommit commit = new RikStockCommit(13022, "MiniGame", "stockPlugin", 3793247L);

        String json = bodyJson(commit);

        // sId is the THIRD <lowercase><UPPERCASE> key in two days (iAc, ziczac's iM, now
        // sId — Amendment B1's table, promoted to a rule as AD-30). Lombok's getSId()
        // would be de-mangled by Jackson into "sid" by lower-casing the whole leading
        // uppercase run. That folded key is ALSO the bet frame's session key, so a wrong
        // commit reads as a perfectly plausible frame on the wire and the server ignores
        // it silently. Only @JsonProperty("sId") on a getter-less field produces "sId".
        assertThat(json).contains("\"sId\":3793247");
        assertThat(json).doesNotContain("\"sid\"");
        assertThat(json).doesNotContain("\"SId\"");
    }

    @Test
    @DisplayName("cmd derives from the offset: 10000 -> 13022, 17000 -> 20022 (no hardcoded CMD)")
    void cmdDerivesFromOffset() throws Exception {
        // Built the way RikStockRequest builds it: cmdPrefix + 3022.
        JsonNode stock = MAPPER.readTree(bodyJson(
                new RikStockCommit(10000 + 3022, "MiniGame", "stockPlugin", 42L)));
        JsonNode other = MAPPER.readTree(bodyJson(
                new RikStockCommit(17000 + 3022, "MiniGame", "stockPlugin", 42L)));

        assertThat(stock.get("cmd").asInt()).isEqualTo(13022);
        assertThat(other.get("cmd").asInt()).isEqualTo(20022);
    }

    @Test
    @DisplayName("sId carries the sid passed in")
    void sIdCarriesTheSession() throws Exception {
        JsonNode node = MAPPER.readTree(bodyJson(
                new RikStockCommit(13022, "MiniGame", "stockPlugin", 3793247L)));

        assertThat(node.get("sId").asLong()).isEqualTo(3793247L);
        assertThat(node.get("cmd").asInt()).isEqualTo(13022);
    }

    @Test
    @DisplayName("the envelope is [\"6\", zone, plugin, body] — zone and plugin not transposed, element 0 the string \"6\" (AD-26)")
    void envelopeIsZoneThenPlugin() throws Exception {
        RikStockCommit commit = new RikStockCommit(13022, "MiniGame", "stockPlugin", 3793247L);

        String frame = commit.serialize(MAPPER);
        JsonNode arr = MAPPER.readTree(frame);

        // Same transposition trap BettingMiniGameBotRikRequestDispatchTest
        // .stockEnvelopeCarriesZoneThenPlugin exists for: two adjacent Strings.
        assertThat(arr.get(0).asText()).isEqualTo("6");
        assertThat(arr.get(1).asText()).isEqualTo("MiniGame");
        assertThat(arr.get(2).asText()).isEqualTo("stockPlugin");
        assertThat(frame).startsWith("[\"6\",\"MiniGame\",\"stockPlugin\",{");
        assertThat(frame).contains("\"sId\":3793247");
    }

    @Test
    @DisplayName("it is NOT a bet with a different cmd: no aid, no eid, no v, no b, no iAc")
    void isNotABet() throws Exception {
        String json = bodyJson(new RikStockCommit(13022, "MiniGame", "stockPlugin", 3793247L));
        JsonNode node = MAPPER.readTree(json);

        assertThat(node.has("aid")).isFalse();
        assertThat(node.has("eid")).isFalse();
        assertThat(node.has("v")).isFalse();
        assertThat(node.has("b")).isFalse();
        assertThat(node.has("iAc")).isFalse();
        assertThat(node.size()).isEqualTo(2);
    }
}
