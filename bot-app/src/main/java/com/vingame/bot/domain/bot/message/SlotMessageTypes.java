package com.vingame.bot.domain.bot.message;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.message.slot.SlotMessage;

/**
 * Provider interface for SLOT game message types.
 * <p>
 * Unlike the betting-mini {@link GameMessageTypes} (whose CMD values are computed
 * as {@code CODE + offset}), slot CMDs are global fixed constants with no offset
 * arithmetic (SLOT_MACHINE_BOT plan AD-1): {@code SUBSCRIBE = 1300},
 * {@code SPIN = 1302} (the spin request and the spin result share {@code 1302}).
 * <p>
 * Slot message classes are product-neutral — a single implementation
 * ({@code SlotMessageTypesImpl}) serves every brand, differentiated only by
 * {@code gid} (AD-3/AD-4). This interface does <b>not</b> extend
 * {@link GameMessageTypes}: the two providers have disjoint shapes and are
 * resolved through separate {@code GameMessageTypesResolver} methods.
 */
public interface SlotMessageTypes {

    // Fixed slot CMD constants — no CODE + offset arithmetic (AD-1).
    int SUBSCRIBE_CMD = 1300;
    int SPIN_CMD = 1302;

    // Fixed SmartFox extension name for ALL slot frames (subscribe + spin),
    // cross-product and cross-environment — exactly like the zone is always
    // "MiniGame" and the CMDs are fixed 1300/1302. Slots are routed by a
    // dedicated extension, so this must NOT come from Game.pluginName (which is
    // the brand's betting-mini extension, e.g. "Tip").
    String SLOT_PLUGIN_NAME = "slotMachinePlugin";

    /**
     * @return Class for deserializing subscribe response messages (cmd 1300)
     */
    Class<? extends SlotMessage> subscribeResponseType();

    /**
     * @return Class for deserializing spin result messages (cmd 1302)
     */
    Class<? extends SlotMessage> spinResultType();

    /**
     * Generate Jackson type registrations for polymorphic deserialization.
     * Registers each class against its literal cmd string ({@code "1300"} /
     * {@code "1302"}) — no offset is applied (AD-1).
     *
     * @return Array of NamedType registrations for ObjectMapper.registerSubtypes()
     */
    default NamedType[] getTypeRegistrations() {
        return new NamedType[] {
            new NamedType(subscribeResponseType(), String.valueOf(SUBSCRIBE_CMD)),
            new NamedType(spinResultType(), String.valueOf(SPIN_CMD)),
        };
    }
}
