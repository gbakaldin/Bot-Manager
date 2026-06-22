package com.vingame.bot.domain.bot.message.request;

import com.vingame.bot.domain.bot.message.SlotMessageTypes;
import com.vingame.websocketparser.message.request.ActionRequestMessage;
import com.vingame.websocketparser.message.request.Body;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the staging fix: every outbound slot frame (subscribe {@code cmd:1300}
 * and spin {@code cmd:1302}) must route to the fixed cross-product slot
 * extension {@link SlotMessageTypes#SLOT_PLUGIN_NAME} ({@code "slotMachinePlugin"}),
 * NOT to the brand's betting-mini plugin (e.g. {@code "Tip"}). Slot frames
 * serialize as {@code [type, zoneName, pluginName, body]}; a wrong pluginName
 * routes the frame to the wrong SmartFox extension and the server never answers.
 */
@DisplayName("SlotRequest")
class SlotRequestTest {

    // ActionRequestMessage stores zoneName, pluginName, body as private fields
    // with no public getters — mirror RequestTest and read them via reflection.
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
    @DisplayName("subscribe (cmd 1300)")
    class SubscribeTests {

        @Test
        @DisplayName("Should route to the fixed slot extension, never Game.pluginName")
        void shouldUseSlotPluginName() throws Exception {
            // Constructed with the brand betting-mini zone; the plugin must still
            // be the fixed slot extension, independent of any Game config.
            SlotRequest request = new SlotRequest("MiniGame");

            SlotSubscribe message = request.subscribe(204);

            assertThat(getStringField(message, "pluginName"))
                    .as("subscribe frame routes to the fixed slot extension")
                    .isEqualTo("slotMachinePlugin")
                    .isEqualTo(SlotMessageTypes.SLOT_PLUGIN_NAME);
            // Negative pin: must NOT leak a betting-mini plugin name.
            assertThat(getStringField(message, "pluginName")).isNotEqualTo("Tip");
        }

        @Test
        @DisplayName("Should propagate zoneName as supplied and carry cmd 1300 + gid")
        void shouldPropagateZoneAndCarryGid() throws Exception {
            SlotRequest request = new SlotRequest("MiniGame");

            SlotSubscribe message = request.subscribe(204);

            assertThat(getStringField(message, "zoneName")).isEqualTo("MiniGame");
            SlotSubscribe.Data data = (SlotSubscribe.Data) getBody(message);
            assertThat(data.getCmd()).isEqualTo(SlotMessageTypes.SUBSCRIBE_CMD);
            assertThat(data.getGid()).isEqualTo(204);
        }
    }

    @Nested
    @DisplayName("spin (cmd 1302)")
    class SpinTests {

        @Test
        @DisplayName("Should route to the fixed slot extension, never Game.pluginName")
        void shouldUseSlotPluginName() throws Exception {
            SlotRequest request = new SlotRequest("MiniGame");

            SlotSpin message = request.spin(204, 500L, List.of(0, 1, 2));

            assertThat(getStringField(message, "pluginName"))
                    .as("spin frame routes to the fixed slot extension")
                    .isEqualTo("slotMachinePlugin")
                    .isEqualTo(SlotMessageTypes.SLOT_PLUGIN_NAME);
            assertThat(getStringField(message, "pluginName")).isNotEqualTo("Tip");
        }

        @Test
        @DisplayName("Should propagate zoneName and carry cmd 1302 + gid/bet/winlines")
        void shouldPropagateZoneAndCarryPayload() throws Exception {
            SlotRequest request = new SlotRequest("MiniGame");

            SlotSpin message = request.spin(204, 500L, List.of(0, 1, 2));

            assertThat(getStringField(message, "zoneName")).isEqualTo("MiniGame");
            SlotSpin.Data data = (SlotSpin.Data) getBody(message);
            assertThat(data.getCmd()).isEqualTo(SlotMessageTypes.SPIN_CMD);
            assertThat(data.getGid()).isEqualTo(204);
            assertThat(data.getB()).isEqualTo(500L);
            assertThat(data.getLs()).containsExactly(0, 1, 2);
        }
    }
}
