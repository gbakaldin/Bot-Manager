package com.vingame.bot.domain.bot.message.request;

import com.vingame.bot.domain.bot.message.SlotMessageTypes;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * Builder for outbound SLOT request messages. Unlike {@link Request} (the
 * betting-mini builder, which adds {@code cmdPrefix + CODE} to every message),
 * slot CMDs are fixed global constants with no offset arithmetic
 * (SLOT_MACHINE_BOT plan AD-1): {@link SlotMessageTypes#SUBSCRIBE_CMD} = 1300,
 * {@link SlotMessageTypes#SPIN_CMD} = 1302.
 * <p>
 * The SmartFox extension (plugin) name is the fixed, cross-product
 * {@link SlotMessageTypes#SLOT_PLUGIN_NAME} for every slot frame — slots are
 * routed by a dedicated extension, so the plugin name is NOT taken from
 * {@code Game.pluginName} (that is the brand's betting-mini extension). Only the
 * zone name is supplied by the caller (already resolved to "MiniGame").
 */
@AllArgsConstructor
public class SlotRequest {

    private final String zoneName;

    /**
     * Build a subscribe request ({@code cmd:1300}) for the given {@code gid}.
     */
    public SlotSubscribe subscribe(int gid) {
        return new SlotSubscribe(SlotMessageTypes.SUBSCRIBE_CMD, zoneName, SlotMessageTypes.SLOT_PLUGIN_NAME, gid);
    }

    /**
     * Build a spin request ({@code cmd:1302}). The {@code ls} list carries the
     * selected winline indices ({@code [0..numLines-1]}); {@code bet} is the
     * per-line stake (AD-8).
     */
    public SlotSpin spin(int gid, long bet, List<Integer> ls) {
        return new SlotSpin(SlotMessageTypes.SPIN_CMD, zoneName, SlotMessageTypes.SLOT_PLUGIN_NAME, gid, bet, ls);
    }
}
