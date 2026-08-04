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
 * As of Phase 2 the two {@link NamedType} registrations are sourced from
 * {@code SlotMessageTypesImpl.getTypeRegistrations()} — the same provider the
 * slot bot uses — rather than declared inline.
 */
@DisplayName("Slot message deserialization (Phase 1)")
class SlotMessageDeserializationTest {

    private ObjectMapper newMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Phase 2: register via the provider's getTypeRegistrations() rather than
        // inline NamedTypes — exercises SlotMessageTypesImpl as the bot will use it.
        mapper.registerSubtypes(new SlotMessageTypesImpl().getTypeRegistrations());
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

    @Test
    @DisplayName("Partial spin-result JSON: only cmd present -> scalar defaults, missing wls -> zero winnings")
    void partialSpinResultDeserializes() throws Exception {
        // A minimal 1302 body with only the cmd discriminator. All other scalars
        // default (FAIL_ON_UNKNOWN_PROPERTIES is off; missing primitives default).
        String json = "[5, {\"cmd\": 1302}]";
        JsonNode root = newMapper().readTree(json);
        SlotMessage parsed = newMapper().treeToValue(root.get(1), SlotMessage.class);

        assertThat(parsed).isInstanceOf(SlotSpinResultMessage.class);
        SlotSpinResultMessage spin = (SlotSpinResultMessage) parsed;
        assertThat(spin.getCmd()).isEqualTo(1302);
        assertThat(spin.getB()).isZero();
        assertThat(spin.getGid()).isZero();
        // Missing wls -> null -> zero winnings (no NPE).
        assertThat(spin.getWls()).isNull();
        assertThat(spin.winningsFor("x")).isZero();
        assertThat(spin.betCountFor("x")).isEqualTo(1);
    }

    @Test
    @DisplayName("Unknown extra fields on the subscribe body are tolerated (eSC/taxes/lss/etc.)")
    void unknownFieldsTolerated() throws Exception {
        // The captured 1300 fixture carries eSC, taxes, as, ae, fss, lss — none
        // modeled on SlotSubscribeResponse. They must deserialize without failing.
        SlotMessage parsed = readBody(newMapper(), "/messages/slot/subscribeResponse.json");
        assertThat(parsed).isInstanceOf(SlotSubscribeResponse.class);
        // (round-trip already asserted in subscribeResponseDeserializes; this pins
        // that the un-modeled fields do not break the parse.)
        assertThat(((SlotSubscribeResponse) parsed).numLines()).isEqualTo(25);
    }
}
