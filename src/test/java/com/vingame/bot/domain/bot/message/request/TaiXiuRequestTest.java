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

    /** P_116 provider CMDs (cmdOffset 0): subscribe 1005, bet 1000. */
    private final TaiXiuRequest request = new TaiXiuRequest("taixiuPlugin", "MiniGame", 1005, 1000);

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

    @Nested
    @DisplayName("offset-aware CMDs (AD-4)")
    class OffsetTests {

        /** A provider at cmdOffset 100 supplies subscribe 1105 / bet 1100. */
        private final TaiXiuRequest shifted =
                new TaiXiuRequest("taixiuJackpotPlugin", "MiniGame", 1105, 1100);

        @Test
        @DisplayName("subscribe emits the injected +100 cmd 1105")
        void subscribeUsesInjectedCmd() throws Exception {
            assertThat(getBody(shifted.subscribe()).getCmd()).isEqualTo(1105);
        }

        @Test
        @DisplayName("bet emits the injected +100 cmd 1100 while keeping the Bet.BetData shape")
        void betUsesInjectedCmd() throws Exception {
            Bet bet = shifted.bet(500_000L, 1, 2670572L);
            Bet.BetData data = (Bet.BetData) getBody(bet);
            assertThat(data.getCmd()).isEqualTo(1100);
            assertThat(data.getB()).isEqualTo(500_000L);
            assertThat(data.getEid()).isEqualTo(1L);
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
