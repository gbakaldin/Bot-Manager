package com.vingame.bot.domain.bot.message.request;

import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link TaiXiuRequest} emits the BARE fixed Tai Xiu CMDs (subscribe
 * {@code 1005}, bet {@code 1000}) with no {@code cmdPrefix} arithmetic (AD-12), and
 * reuses {@link Bet.BetData}'s field shape ({@code cmd, aid:1, b, eid, sid}).
 */
@DisplayName("TaiXiuRequest")
class TaiXiuRequestTest {

    private final TaiXiuRequest request = new TaiXiuRequest("taixiuPlugin", "MiniGame");

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
            Bet bet = request.bet(500_000L, 1, 2670572L);
            assertThat(getBody(bet).getCmd()).isEqualTo(1000);
        }

        @Test
        @DisplayName("carries amount, eid, sid, and aid=1 (reuses Bet.BetData shape)")
        void fields() throws Exception {
            Bet bet = request.bet(500_000L, 2, 2670572L);
            Bet.BetData data = (Bet.BetData) getBody(bet);
            assertThat(data.getB()).isEqualTo(500_000L);
            assertThat(data.getEid()).isEqualTo(2L);  // Xỉu
            assertThat(data.getSid()).isEqualTo(2670572L);
            assertThat(data.getAid()).isEqualTo(1);
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
