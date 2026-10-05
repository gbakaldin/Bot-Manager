package com.vingame.bot.domain.bot.message;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.message.slot.SlotSpinResultMessage;
import com.vingame.bot.domain.bot.message.slot.SlotSubscribeResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 verification (SLOT_MACHINE_BOT plan AD-1/AD-4): the product-neutral
 * slot provider registers its two classes against the literal fixed cmd strings
 * {@code "1300"} / {@code "1302"} with no offset arithmetic, and the registry
 * exposes it via {@code slot()} (PLUGIN_HOT_RELOAD Phase 2c).
 */
@DisplayName("SlotMessageTypes provider + registry split")
class SlotMessageTypesTest {

    @Test
    @DisplayName("Fixed cmd constants are 1300 (subscribe) and 1302 (spin)")
    void fixedCmdConstants() {
        assertThat(SlotMessageTypes.SUBSCRIBE_CMD).isEqualTo(1300);
        assertThat(SlotMessageTypes.SPIN_CMD).isEqualTo(1302);
    }

    @Test
    @DisplayName("Impl exposes the slot subscribe-response and spin-result classes")
    void implAccessors() {
        SlotMessageTypes types = new SlotMessageTypesImpl();
        assertThat(types.subscribeResponseType()).isEqualTo(SlotSubscribeResponse.class);
        assertThat(types.spinResultType()).isEqualTo(SlotSpinResultMessage.class);
    }

    @Test
    @DisplayName("getTypeRegistrations has 2 entries keyed on literal cmd strings, no offset")
    void typeRegistrations() {
        NamedType[] regs = new SlotMessageTypesImpl().getTypeRegistrations();

        assertThat(regs).hasSize(2);
        Map<String, Class<?>> byName = Arrays.stream(regs)
                .collect(Collectors.toMap(NamedType::getName, NamedType::getType));
        assertThat(byName).containsOnlyKeys("1300", "1302");
        assertThat(byName.get("1300")).isEqualTo(SlotSubscribeResponse.class);
        assertThat(byName.get("1302")).isEqualTo(SlotSpinResultMessage.class);
    }

    @Test
    @DisplayName("registry.slot() returns a product-neutral SlotMessageTypes")
    void resolveSlotReturnsProvider() {
        SlotMessageTypes resolved = new MessageTypesRegistry(
                List.of(), List.of(new SlotMessageTypesImpl()), List.of(), List.of(), List.of()).slot();

        assertThat(resolved).isInstanceOf(SlotMessageTypesImpl.class);
        assertThat(resolved.getTypeRegistrations()).hasSize(2);
    }
}
