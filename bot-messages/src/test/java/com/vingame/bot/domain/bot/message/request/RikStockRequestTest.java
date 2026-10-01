package com.vingame.bot.domain.bot.message.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RikStockRequest} — the 114 stock request helper (RIK_114_BETTING_MINI AD-25).
 * <p>
 * Two frames differ from {@link Request}: the bet body (Phase 2) and the per-bet commit
 * (Phase 3, AD-28/AD-29). The subscribe assertion below is the other half of that
 * claim, and it is the one worth a test: the capture shows the real client's subscribe
 * is byte-identical to ours, so a standalone class that quietly changed it would break
 * the game while the bet body looked fine.
 */
@DisplayName("RikStockRequest")
class RikStockRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Body body(Object message) throws Exception {
        Field f = ActionRequestMessage.class.getDeclaredField("body");
        f.setAccessible(true);
        return (Body) f.get(message);
    }

    private static String field(Object message, String name) throws Exception {
        Field f = ActionRequestMessage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(message);
    }

    @Test
    @DisplayName("subscribe() is node-equal to Request.subscribe() for the same inputs")
    void subscribeIsIdenticalToTheSharedRequest() throws Exception {
        RikStockRequest rik = new RikStockRequest("stockPlugin", "MiniGame", 10000);
        Request shared = new Request("stockPlugin", "MiniGame", 10000);

        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(body(rik.subscribe()))))
                .isEqualTo(MAPPER.readTree(MAPPER.writeValueAsString(body(shared.subscribe()))));
        // And the whole frame, envelope included.
        assertThat(rik.subscribe().serialize(MAPPER)).isEqualTo(shared.subscribe().serialize(MAPPER));
    }

    @Test
    @DisplayName("subscribe() body cmd is offset + 3000 (13000 on stock)")
    void subscribeCmdDerivesFromOffset() throws Exception {
        RikStockRequest rik = new RikStockRequest("stockPlugin", "MiniGame", 10000);

        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(body(rik.subscribe()))).get("cmd").asInt())
                .isEqualTo(13000);
    }

    @Test
    @DisplayName("bet() returns a RikStockBet at offset + 3002, carrying zone and plugin")
    void betReturnsTheStockBody() throws Exception {
        RikStockRequest rik = new RikStockRequest("stockPlugin", "MiniGame", 10000);

        RikStockBet bet = rik.bet(1000L, 0, 3793247L);

        assertThat(field(bet, "zoneName")).isEqualTo("MiniGame");
        assertThat(field(bet, "pluginName")).isEqualTo("stockPlugin");
        String json = MAPPER.writeValueAsString(body(bet));
        assertThat(MAPPER.readTree(json).get("cmd").asInt()).isEqualTo(13002);
        assertThat(MAPPER.readTree(json).get("v").asLong()).isEqualTo(1000L);
        assertThat(json).doesNotContain("\"b\"");
    }

    @Test
    @DisplayName("it is NOT a Request subclass — Request and Bet are frozen (AD-25)")
    void isStandalone() {
        RikStockRequest rik = new RikStockRequest("stockPlugin", "MiniGame", 10000);

        assertThat(rik).isInstanceOf(GameRequest.class);
        assertThat(rik).isNotInstanceOf(Request.class);
        // Request.bet narrows its return type to the concrete Bet (pinned by
        // RequestTest.overrideReturnsConcreteBet), so subclassing would force
        // RikStockBet extends Bet, which drags `b` back into the body.
        assertThat(rik.bet(1000L, 0, 1L)).isNotInstanceOf(Bet.class);
    }

    @Test
    @DisplayName("commit(sid) is present, is a RikStockCommit at offset + 3022, carrying zone and plugin (AD-28/AD-29)")
    void commitReturnsTheStockCommit() throws Exception {
        RikStockRequest rik = new RikStockRequest("stockPlugin", "MiniGame", 10000);

        Optional<ActionRequestMessage> commit = rik.commit(3793247L);

        assertThat(commit).isPresent();
        assertThat(commit.get()).isInstanceOf(RikStockCommit.class);
        assertThat(field(commit.get(), "zoneName")).isEqualTo("MiniGame");
        assertThat(field(commit.get(), "pluginName")).isEqualTo("stockPlugin");
        JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(body(commit.get())));
        assertThat(node.get("cmd").asInt()).isEqualTo(13022);
        assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrder("cmd", "sId");
    }

    @Test
    @DisplayName("bet(…, sid) and commit(sid) carry the SAME session under DIFFERENT keys — sid vs sId")
    void betAndCommitAgreeOnTheSession() throws Exception {
        RikStockRequest rik = new RikStockRequest("stockPlugin", "MiniGame", 10000);
        long sid = 3793247L;

        JsonNode bet = MAPPER.readTree(MAPPER.writeValueAsString(body(rik.bet(1000L, 0, sid))));
        JsonNode commit = MAPPER.readTree(MAPPER.writeValueAsString(body(rik.commit(sid).orElseThrow())));

        // The one assertion that ties the pair together: the legacy bot sends
        //   {"cmd":13002,…,"sid":S,…} then {"cmd":13022,"sId":S}
        // — same value, and the key really is spelled differently on the two frames.
        assertThat(bet.get("sid").asLong()).isEqualTo(sid);
        assertThat(commit.get("sId").asLong()).isEqualTo(sid);
        assertThat(bet.has("sId")).isFalse();
        assertThat(commit.has("sid")).isFalse();
    }
}
