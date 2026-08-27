package com.vingame.bot.domain.bot.message.slot;

import com.vingame.bot.domain.bot.message.MessageTypesImpl;
import com.vingame.bot.domain.bot.message.SlotMessageTypes;
import com.vingame.bot.domain.game.model.GameType;
import org.springframework.stereotype.Component;

/**
 * Product-neutral implementation of {@link SlotMessageTypes} (SLOT_MACHINE_BOT
 * plan AD-3/AD-4). A single instance serves every brand — slot message classes,
 * CMDs, and protocol are identical across products, differentiated only by
 * {@code gid}. Resolved via {@code MessageTypesRegistry.slot()}, which takes no
 * product code at all; the empty {@link MessageTypesImpl#products()} below is what
 * declares that neutrality (PLUGIN_HOT_RELOAD AD-17).
 */
@Component
@MessageTypesImpl(gameType = GameType.SLOT, products = {})
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
