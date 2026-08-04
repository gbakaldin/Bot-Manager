package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Request")
class RequestTest {

    // ActionRequestMessage stores zoneName, pluginName, body as private fields.
    // No public getters are exposed, so we use reflection in tests.
    private static String getStringField(Object target, String name) throws Exception {
        Field f = ActionRequestMessage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(target);
    }

    private static Body getBody(Object target) throws Exception {
        Field f = ActionRequestMessage.class.getDeclaredField("body");
        f.setAccessible(true);
        return (Body) f.get(target);
    }

    @Nested
    @DisplayName("subscribe")
    class SubscribeTests {

        @Test
        @DisplayName("Should produce body with cmd = cmdPrefix + 3000")
        void shouldComputeCmd() {
            Request request = new Request("MiniGame3", "ZoneA", 2000);

            SubscribeToLobbyMessage message = request.subscribe();

            assertThat(getBodyCmd(message)).isEqualTo(5000);
        }

        @Test
        @DisplayName("Should propagate zoneName and pluginName")
        void shouldPropagateZoneAndPlugin() throws Exception {
            Request request = new Request("MiniGame3", "ZoneA", 2000);

            SubscribeToLobbyMessage message = request.subscribe();

            assertThat(getStringField(message, "zoneName")).isEqualTo("ZoneA");
            assertThat(getStringField(message, "pluginName")).isEqualTo("MiniGame3");
        }
    }

    @Nested
    @DisplayName("bet")
    class BetTests {

        @Test
        @DisplayName("Should produce body with cmd = cmdPrefix + 3002")
        void shouldComputeCmd() {
            Request request = new Request("MiniGame3", "ZoneA", 2000);

            Bet bet = request.bet(500L, 3, 99999L);

            assertThat(getBodyCmd(bet)).isEqualTo(5002);
        }

        @Test
        @DisplayName("Should propagate zoneName and pluginName")
        void shouldPropagateZoneAndPlugin() throws Exception {
            Request request = new Request("MiniGameX", "CustomZone", 8000);

            Bet bet = request.bet(123L, 1, 42L);

            assertThat(getStringField(bet, "zoneName")).isEqualTo("CustomZone");
            assertThat(getStringField(bet, "pluginName")).isEqualTo("MiniGameX");
        }

        @Test
        @DisplayName("Should carry amount, entryId and sid on the produced Bet body")
        void shouldCarryFields() throws Exception {
            Request request = new Request("MiniGame3", "ZoneA", 2000);

            Bet bet = request.bet(750L, 5, 12345L);
            Bet.BetData data = (Bet.BetData) getBody(bet);

            assertThat(data.getB()).isEqualTo(750L);
            assertThat(data.getEid()).isEqualTo(5L);
            assertThat(data.getSid()).isEqualTo(12345L);
        }

        /**
         * Regression for TAI_XIU_114_JACKPOT: {@link GameRequest#bet} widened its
         * declared return type to {@link ActionRequestMessage}, but the betting-mini
         * {@link Request#bet} override must still return the concrete shared
         * {@link Bet}. The assignment to a {@code Bet} variable below is the static
         * compile-time proof that the covariant override still holds.
         */
        @Test
        @DisplayName("override still returns the concrete shared Bet (covariant), not a widened type")
        void overrideReturnsConcreteBet() {
            GameRequest request = new Request("MiniGame3", "ZoneA", 2000);

            // Covariant: the Request override narrows GameRequest.bet's
            // ActionRequestMessage back to Bet — this line would not compile otherwise.
            Bet bet = ((Request) request).bet(500L, 1, 7L);

            assertThat(bet).isInstanceOf(Bet.class);
        }

        /**
         * Regression for TAI_XIU_114_JACKPOT AD-2: the {@code a} auto-bet flag is
         * scoped to the 114 Tai Xiu bet body only. Every betting-mini product's bet
         * must serialize with NO {@code a} key — the shared {@link Bet.BetData} stays
         * byte-for-byte unchanged.
         */
        @Test
        @DisplayName("betting-mini bet serializes with NO 'a' key (a is 114-only)")
        void bettingMiniBetHasNoAutoBetFlag() throws Exception {
            Request request = new Request("MiniGame3", "ZoneA", 2000);

            Bet bet = request.bet(750L, 5, 12345L);

            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(getBody(bet));
            assertThat(json).doesNotContain("\"a\"");
            // Exactly the shared shape: cmd, aid, b, eid, sid (no extras).
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            assertThat(node.fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("cmd", "aid", "b", "eid", "sid");
        }
    }

    @Nested
    @DisplayName("chat")
    class ChatTests {

        @Test
        @DisplayName("Should produce body with cmd = cmdPrefix + 3008")
        void shouldComputeCmd() {
            Request request = new Request("MiniGame3", "ZoneA", 2000);

            Chat chat = request.chat("hello");

            assertThat(getBodyCmd(chat)).isEqualTo(5008);
        }

        @Test
        @DisplayName("Should propagate zoneName, pluginName and message")
        void shouldPropagateFields() throws Exception {
            Request request = new Request("MiniGame3", "ZoneA", 2000);

            Chat chat = request.chat("hello world");
            Chat.ChatData data = (Chat.ChatData) getBody(chat);

            assertThat(getStringField(chat, "zoneName")).isEqualTo("ZoneA");
            assertThat(getStringField(chat, "pluginName")).isEqualTo("MiniGame3");
            assertThat(data.getMgs()).isEqualTo("hello world");
        }
    }

    @Nested
    @DisplayName("autoBet")
    class AutoBetTests {

        @Test
        @DisplayName("Should produce body with cmd = cmdPrefix + 3015")
        void shouldComputeCmd() {
            Request request = new Request("MiniGame3", "ZoneA", 2000);

            AutoBet autoBet = request.autoBet(true, Map.of(1, 100L));

            assertThat(getBodyCmd(autoBet)).isEqualTo(5015);
        }

        @Test
        @DisplayName("Should propagate zoneName, pluginName, isMini and bet entries")
        void shouldPropagateFields() throws Exception {
            Request request = new Request("MiniGameX", "CustomZone", 8000);
            Map<Integer, Long> entries = new LinkedHashMap<>();
            entries.put(1, 100L);
            entries.put(2, 200L);

            AutoBet autoBet = request.autoBet(false, entries);
            AutoBet.AutoBetData data = (AutoBet.AutoBetData) getBody(autoBet);

            assertThat(getStringField(autoBet, "zoneName")).isEqualTo("CustomZone");
            assertThat(getStringField(autoBet, "pluginName")).isEqualTo("MiniGameX");
            assertThat(data.getIsMini()).isFalse();

            List<BetEntryInfo> list = data.getB();
            assertThat(list).hasSize(2);
            assertThat(list).extracting(BetEntryInfo::getEid).containsExactly(1, 2);
            assertThat(list).extracting(BetEntryInfo::getV).containsExactly(100L, 200L);
        }
    }

    private static int getBodyCmd(Object actionRequestMessage) {
        try {
            return getBody(actionRequestMessage).getCmd();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
