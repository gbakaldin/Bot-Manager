package com.vingame.bot.domain.bot.message.slot;

import com.vingame.bot.domain.bot.message.SlotMessageTypes;

/**
 * Product-neutral implementation of {@link SlotMessageTypes} (SLOT_MACHINE_BOT
 * plan AD-3/AD-4). A single instance serves every brand — slot message classes,
 * CMDs, and protocol are identical across products, differentiated only by
 * {@code gid}. Resolved via {@code GameMessageTypesResolver.resolveSlot()}.
 */
public class SlotMessageTypesImpl implements SlotMessageTypes {

    @Override
    public Class<? extends SlotMessage> subscribeResponseType() {
        return SlotSubscribeResponse.class;
    }

    @Override
    public Class<? extends SlotMessage> spinResultType() {
        return SlotSpinResultMessage.class;
    }
}
