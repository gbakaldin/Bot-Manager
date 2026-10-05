package com.vingame.bot.domain.bot.message.g4.win79.cashout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.domain.bot.message.request.CashoutRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASHOUT_BOT Phase 1 / AD-10: the outbound 119 cash-out frames, serialized exactly as
 * a bot sends them ({@code ActionRequestMessage.serialize}).
 *
 * <p>The bet is compared against the real-client capture:
 * <pre>{@code [6,"MiniGame","balloonPlugin",{"cmd":1501,"b":100000,"aid":1,"sL":2,"aS":false,"aSt":false}]}</pre>
 * Element 0 is ws-parser's string {@code "6"} where the real client sends the number
 * {@code 6}. That is how every product's outbound frame goes out (see
 * {@code ZicZacBet}'s javadoc), so it is asserted as-is rather than "fixed" here.
 *
 * <p>The mixed-case keys {@code sL}, {@code aS}, {@code aSt} are the trap this test
 * exists for: a Lombok getter would emit {@code sl} / {@code as} / {@code ast} next to
 * (or instead of) them. Comparing the whole body, key set and values, catches either.
 */
@DisplayName("Win79CashoutRequest — outbound frames match the capture")
class Win79CashoutRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final CashoutRequest BALLOON =
            new Win79CashoutMessageTypes().newRequest("MiniGame", "balloonPlugin", 1500);

    private static JsonNode envelope(String serialized) throws Exception {
        JsonNode node = MAPPER.readTree(serialized);
        assertThat(node.isArray()).isTrue();
        assertThat(node).hasSize(4);
        return node;
    }

    @Test
    @DisplayName("bet: the captured real-client body, with no sid")
    void betMatchesTheCapture() throws Exception {
        String serialized = BALLOON.bet(100_000L).serialize(MAPPER);

        assertThat(serialized).isEqualTo(
                "[\"6\",\"MiniGame\",\"balloonPlugin\","
                        + "{\"cmd\":1501,\"b\":100000,\"aid\":1,\"sL\":2,\"aS\":false,\"aSt\":false}]");

        JsonNode frame = envelope(serialized);
        assertThat(frame.get(3)).isEqualTo(MAPPER.readTree(
                "{\"cmd\":1501,\"b\":100000,\"aid\":1,\"sL\":2,\"aS\":false,\"aSt\":false}"));
        assertThat(frame.get(3).has("sid"))
                .as("the server assigns the sid; the real client sends none")
                .isFalse();
    }

    @Test
    @DisplayName("cash-out: {cmd:1502, sid, aid:1, aSt:false}")
    void cashOutBody() throws Exception {
        String serialized = BALLOON.cashOut(1_801_744L).serialize(MAPPER);

        assertThat(serialized).isEqualTo(
                "[\"6\",\"MiniGame\",\"balloonPlugin\","
                        + "{\"cmd\":1502,\"sid\":1801744,\"aid\":1,\"aSt\":false}]");
    }

    @Test
    @DisplayName("subscribe: {cmd:1500} on the game's plugin")
    void subscribeBody() throws Exception {
        String serialized = BALLOON.subscribe().serialize(MAPPER);

        assertThat(serialized).isEqualTo("[\"6\",\"MiniGame\",\"balloonPlugin\",{\"cmd\":1500}]");
    }

    @Test
    @DisplayName("Soccer (offset 2500, soccerPlugin): 2500 / 2501 / 2502 on its own plugin")
    void soccerOffsets() throws Exception {
        CashoutRequest soccer = new Win79CashoutMessageTypes().newRequest("MiniGame", "soccerPlugin", 2500);

        JsonNode subscribe = envelope(soccer.subscribe().serialize(MAPPER));
        JsonNode bet = envelope(soccer.bet(1_000_000L).serialize(MAPPER));
        JsonNode cashOut = envelope(soccer.cashOut(7L).serialize(MAPPER));

        assertThat(subscribe.get(2).asText()).isEqualTo("soccerPlugin");
        assertThat(subscribe.get(3).get("cmd").asInt()).isEqualTo(2500);
        assertThat(bet.get(2).asText()).isEqualTo("soccerPlugin");
        assertThat(bet.get(3).get("cmd").asInt()).isEqualTo(2501);
        assertThat(bet.get(3).get("b").asLong()).isEqualTo(1_000_000L);
        assertThat(cashOut.get(3).get("cmd").asInt()).isEqualTo(2502);
        assertThat(cashOut.get(3).get("sid").asLong()).isEqualTo(7L);
    }
}
