package com.vingame.bot.domain.bot.message.slot;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 verification (SLOT_MACHINE_BOT plan): the captured array-framed
 * {@code [5,{...}]} slot payloads round-trip through a Jackson {@link ObjectMapper}
 * with the slot subtypes registered against their literal cmd strings.
 * <p>
 * The {@code [type, {cmd,...}]} array framing is the same shape the library's
 * {@code ActionResponseMessage.deserialize} consumes ({@code node.get(0)} =
 * category 5 = ACTION_RESPONSE, {@code node.get(1)} = the typed body). Here we
 * exercise the body element directly so the test pins the message-class mapping
 * without dragging in the library scenario engine.
 * <p>
 * Phase 2 introduces {@code SlotMessageTypesImpl.getTypeRegistrations()}; until
 * then this test registers the two {@link NamedType}s inline, exactly as the plan
 * permits.
 */
@DisplayName("Slot message deserialization (Phase 1)")
class SlotMessageDeserializationTest {

    private ObjectMapper newMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerSubtypes(
                new NamedType(SlotSubscribeResponse.class, "1300"),
                new NamedType(SlotSpinResultMessage.class, "1302"));
        return mapper;
    }

    private SlotMessage readBody(ObjectMapper mapper, String resource) throws Exception {
        try (var in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("fixture " + resource).isNotNull();
            JsonNode root = mapper.readTree(in.readAllBytes());
            // Array-framed [category, body] — take the body element (node.get(1)),
            // matching the library's ActionResponseMessage.deserialize path.
            assertThat(root.isArray()).as("payload is array-framed [type,{...}]").isTrue();
            assertThat(root.get(0).asInt()).as("ACTION_RESPONSE category").isEqualTo(5);
            return mapper.treeToValue(root.get(1), SlotMessage.class);
        }
    }

    @Test
    @DisplayName("spinResult.json -> SlotSpinResultMessage with gross winnings 6000 and single bet of 500")
    void spinResultDeserializes() throws Exception {
        SlotMessage parsed = readBody(newMapper(), "/messages/slot/spinResult.json");

        assertThat(parsed).isInstanceOf(SlotSpinResultMessage.class);
        SlotSpinResultMessage spin = (SlotSpinResultMessage) parsed;

        assertThat(spin.getCmd()).isEqualTo(1302);
        assertThat(spin.getB()).isEqualTo(500L);
        assertThat(spin.getGid()).isEqualTo(204);
        // Gross winnings = sum(wls[].crd) = 1000 + 5000 = 6000 (AD-7).
        assertThat(spin.winningsFor("anyUser")).isEqualTo(6000L);
        // Single spin = single bet at the staked amount.
        assertThat(spin.betAmountFor("anyUser")).isEqualTo(500L);
        assertThat(spin.betCountFor("anyUser")).isEqualTo(1);
    }

    @Test
    @DisplayName("subscribeResponse.json -> SlotSubscribeResponse with 25 winlines and server-sourced bet set")
    void subscribeResponseDeserializes() throws Exception {
        SlotMessage parsed = readBody(newMapper(), "/messages/slot/subscribeResponse.json");

        assertThat(parsed).isInstanceOf(SlotSubscribeResponse.class);
        SlotSubscribeResponse sub = (SlotSubscribeResponse) parsed;

        assertThat(sub.getCmd()).isEqualTo(1300);
        assertThat(sub.getGid()).isEqualTo(204);
        // Winline count = ls.size() (AD-8/AD-11).
        assertThat(sub.numLines()).isEqualTo(25);
        // Allowed bet-value set = Js[].b, sorted ascending (AD-11).
        assertThat(sub.allowedBetValues())
                .containsExactly(500L, 1000L, 2000L, 5000L, 10000L);
    }

    @Test
    @DisplayName("Empty wls -> zero winnings; null Js -> empty allowed set; null ls -> zero numLines")
    void defensiveDerivedAccessors() {
        SlotSpinResultMessage noWin = new SlotSpinResultMessage(
                1302, 500L, 204, List.of(), 13L, false, false, 0,
                false, false, 0L, 0L, false, null);
        assertThat(noWin.winningsFor("x")).isZero();
        assertThat(noWin.betAmountFor("x")).isEqualTo(500L);
        assertThat(noWin.betCountFor("x")).isEqualTo(1);

        SlotSubscribeResponse empty = new SlotSubscribeResponse(1300, 204, null, null);
        assertThat(empty.numLines()).isZero();
        assertThat(empty.allowedBetValues()).isEmpty();
    }
}
