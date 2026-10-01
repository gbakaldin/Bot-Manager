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
 * {@link ZicZacRequest} — the 114 ziczac request helper (RIK_114_ZICZAC Amendment A1
 * item 2).
 * <p>
 * One frame differs from {@link Request}: the bet body. The subscribe assertion below is
 * the other half of that claim, and it is the one worth a test: the capture shows the
 * real client's subscribe is byte-identical to ours, so a standalone class that quietly
 * changed it would break the game while the bet body looked fine. The commit assertion
 * is the difference from {@link RikStockRequest}: ziczac has no {@code 3022}, and a
 * "commit everywhere" override would send the server a frame it has never seen.
 */
@DisplayName("ZicZacRequest")
class ZicZacRequestTest {

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
        ZicZacRequest ziczac = new ZicZacRequest("ziczacPlugin", "MiniGame", 9000);
        Request shared = new Request("ziczacPlugin", "MiniGame", 9000);

        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(body(ziczac.subscribe()))))
                .isEqualTo(MAPPER.readTree(MAPPER.writeValueAsString(body(shared.subscribe()))));
        // And the whole frame, envelope included.
        assertThat(ziczac.subscribe().serialize(MAPPER)).isEqualTo(shared.subscribe().serialize(MAPPER));
    }

    @Test
    @DisplayName("subscribe() body cmd is offset + 3000 (12000 on ziczac)")
    void subscribeCmdDerivesFromOffset() throws Exception {
        ZicZacRequest ziczac = new ZicZacRequest("ziczacPlugin", "MiniGame", 9000);

        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(body(ziczac.subscribe()))).get("cmd").asInt())
                .isEqualTo(12000);
    }

    @Test
    @DisplayName("bet() returns a ZicZacBet at offset + 3002, carrying zone and plugin, with the five keys")
    void betReturnsTheZicZacBody() throws Exception {
        ZicZacRequest ziczac = new ZicZacRequest("ziczacPlugin", "MiniGame", 9000);

        ZicZacBet bet = ziczac.bet(60_000L, 0, 1995084L);

        assertThat(field(bet, "zoneName")).isEqualTo("MiniGame");
        assertThat(field(bet, "pluginName")).isEqualTo("ziczacPlugin");
        JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(body(bet)));
        assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrder("cmd", "b", "c", "sid", "aid");
        assertThat(node.get("cmd").asInt()).isEqualTo(12002);
        assertThat(node.get("b").asLong()).isEqualTo(60_000L);
        assertThat(node.get("c").asInt()).isEqualTo(1);
        assertThat(node.get("sid").asLong()).isEqualTo(1995084L);
    }

    @Test
    @DisplayName("bet() discards entryId (AD-11): 0, 1 and 7 all produce the same body")
    void entryIdIsDiscarded() throws Exception {
        ZicZacRequest ziczac = new ZicZacRequest("ziczacPlugin", "MiniGame", 9000);

        JsonNode zero = MAPPER.readTree(MAPPER.writeValueAsString(body(ziczac.bet(60_000L, 0, 1995084L))));
        JsonNode one = MAPPER.readTree(MAPPER.writeValueAsString(body(ziczac.bet(60_000L, 1, 1995084L))));
        JsonNode seven = MAPPER.readTree(MAPPER.writeValueAsString(body(ziczac.bet(60_000L, 7, 1995084L))));

        assertThat(one).isEqualTo(zero);
        assertThat(seven).isEqualTo(zero);
        assertThat(zero.has("eid")).isFalse();
    }

    @Test
    @DisplayName("it is NOT a Request subclass — Request and Bet are frozen")
    void isStandalone() {
        ZicZacRequest ziczac = new ZicZacRequest("ziczacPlugin", "MiniGame", 9000);

        assertThat(ziczac).isInstanceOf(GameRequest.class);
        assertThat(ziczac).isNotInstanceOf(Request.class);
        assertThat(ziczac).isNotInstanceOf(RikStockRequest.class);
        // Request.bet narrows its return type to the concrete Bet (pinned by
        // RequestTest.overrideReturnsConcreteBet), so subclassing would force
        // ZicZacBet extends Bet, which drags `eid` back into the body.
        assertThat(ziczac.bet(60_000L, 0, 1L)).isNotInstanceOf(Bet.class);
        assertThat(ziczac.bet(60_000L, 0, 1L)).isNotInstanceOf(RikStockBet.class);
    }

    @Test
    @DisplayName("commit(sid) is EMPTY — ziczac has no 3022; the GameRequest default is inherited, not overridden")
    void commitIsAbsent() throws Exception {
        ZicZacRequest ziczac = new ZicZacRequest("ziczacPlugin", "MiniGame", 9000);

        assertThat(ziczac.commit(1995084L)).isEmpty();
        // Inherited, not overridden: the class declares no commit method at all, so a
        // future "commit everywhere" edit to the default would be the only way this
        // changes — and RequestTest / TaiXiuRequestTest pin the default too.
        assertThat(ZicZacRequest.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("commit");
    }
}
