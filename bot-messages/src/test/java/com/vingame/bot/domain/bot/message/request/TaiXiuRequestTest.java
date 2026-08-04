package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link TaiXiuRequest} emits the BARE injected Tai Xiu CMDs (subscribe
 * {@code 1005}, bet {@code 1000} for P_116) with no {@code cmdPrefix} arithmetic
 * (AD-12), and reuses {@link Bet.BetData}'s field shape
 * ({@code cmd, aid:1, b, eid, sid}). The CMDs are now passed in at construction
 * (TAI_XIU_114_JACKPOT plan AD-4) so the same builder serves any offset.
 */
@DisplayName("TaiXiuRequest")
class TaiXiuRequestTest {

    /** P_116 provider CMDs (cmdOffset 0): subscribe 1005, bet 1000; no auto-bet flag. */
    private final TaiXiuRequest request = new TaiXiuRequest("taixiuPlugin", "MiniGame", 1005, 1000, false);

    @Nested
    @DisplayName("subscribe")
    class SubscribeTests {

        @Test
        @DisplayName("emits bare cmd 1005 (no offset/prefix)")
        void cmd() throws Exception {
            SubscribeToLobbyMessage msg = request.subscribe();
            assertThat(getBody(msg).getCmd()).isEqualTo(1005);
        }

        @Test
        @DisplayName("propagates zoneName and pluginName")
        void zoneAndPlugin() throws Exception {
            SubscribeToLobbyMessage msg = request.subscribe();
            assertThat(getStringField(msg, "zoneName")).isEqualTo("MiniGame");
            assertThat(getStringField(msg, "pluginName")).isEqualTo("taixiuPlugin");
        }
    }

    @Nested
    @DisplayName("bet")
    class BetTests {

        @Test
        @DisplayName("emits bare cmd 1000 (NOT cmdPrefix+3002)")
        void cmd() throws Exception {
            ActionRequestMessage bet = request.bet(500_000L, 1, 2670572L);
            assertThat(getBody(bet).getCmd()).isEqualTo(1000);
        }

        @Test
        @DisplayName("carries amount, eid, sid, and aid=1 (reuses Bet.BetData shape)")
        void fields() throws Exception {
            ActionRequestMessage bet = request.bet(500_000L, 2, 2670572L);
            assertThat(bet).isInstanceOf(Bet.class);
            Bet.BetData data = (Bet.BetData) getBody(bet);
            assertThat(data.getB()).isEqualTo(500_000L);
            assertThat(data.getEid()).isEqualTo(2L);  // Xỉu
            assertThat(data.getSid()).isEqualTo(2670572L);
            assertThat(data.getAid()).isEqualTo(1);
        }

        @Test
        @DisplayName("116 bet (emitAutoBetFlag=false) is the shared Bet body with NO 'a' field")
        void noAutoBetFlagForP116() throws Exception {
            ActionRequestMessage bet = request.bet(500_000L, 1, 2670572L);
            // 116 must use the shared Bet body (byte-for-byte unchanged) — not TaiXiuBet.
            assertThat(bet).isInstanceOf(Bet.class);
            assertThat(getBody(bet)).isInstanceOf(Bet.BetData.class);
            // Serialized 116 bet must carry no "a" key.
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(getBody(bet));
            assertThat(json).doesNotContain("\"a\"");
        }
    }

    @Nested
    @DisplayName("offset-aware CMDs + a-flag (AD-2/AD-4)")
    class OffsetTests {

        /** A P_114 jackpot provider: subscribe 1105 / bet 1100, emitAutoBetFlag=true. */
        private final TaiXiuRequest shifted =
                new TaiXiuRequest("taixiuJackpotPlugin", "MiniGame", 1105, 1100, true);

        @Test
        @DisplayName("subscribe emits the injected +100 cmd 1105")
        void subscribeUsesInjectedCmd() throws Exception {
            assertThat(getBody(shifted.subscribe()).getCmd()).isEqualTo(1105);
        }

        @Test
        @DisplayName("114 bet emits +100 cmd 1100, the TaiXiuBet body, and a:false")
        void betUsesInjectedCmd() throws Exception {
            ActionRequestMessage bet = shifted.bet(500_000L, 1, 2670572L);
            // 114 path uses the dedicated TaiXiuBet body carrying a.
            assertThat(bet).isInstanceOf(TaiXiuBet.class);
            assertThat(getBody(bet)).isInstanceOf(TaiXiuBet.BetData.class);
            TaiXiuBet.BetData data = (TaiXiuBet.BetData) getBody(bet);
            assertThat(data.getCmd()).isEqualTo(1100);
            assertThat(data.getB()).isEqualTo(500_000L);
            assertThat(data.getEid()).isEqualTo(1L);
            assertThat(data.getSid()).isEqualTo(2670572L);
            assertThat(data.getAid()).isEqualTo(1);
            assertThat(data.isA()).isFalse();
        }

        @Test
        @DisplayName("114 bet serializes to {cmd:1100, aid:1, b, eid, sid, a:false}")
        void betSerializesWithAFalse() throws Exception {
            ActionRequestMessage bet = shifted.bet(500_000L, 1, 2670572L);
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(getBody(bet));
            assertThat(node.get("cmd").asInt()).isEqualTo(1100);
            assertThat(node.get("aid").asInt()).isEqualTo(1);
            assertThat(node.get("b").asLong()).isEqualTo(500_000L);
            assertThat(node.get("eid").asLong()).isEqualTo(1L);
            assertThat(node.get("sid").asLong()).isEqualTo(2670572L);
            assertThat(node.has("a")).isTrue();
            assertThat(node.get("a").asBoolean()).isFalse();
        }
    }

    private static Body getBody(ActionRequestMessage msg) throws Exception {
        Field f = ActionRequestMessage.class.getDeclaredField("body");
        f.setAccessible(true);
        return (Body) f.get(msg);
    }

    private static String getStringField(ActionRequestMessage msg, String name) throws Exception {
        Field f = ActionRequestMessage.class.getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(msg);
    }
}
