package com.vingame.bot.domain.bot.service;

import com.vingame.bot.domain.bot.message.MessageTypesRegistry;
import com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes;
import com.vingame.bot.domain.bot.message.g3.tip.TipGameMessageTypes;
import com.vingame.bot.domain.bot.message.g4.nohu.NohuGameMessageTypes;
import com.vingame.bot.domain.bot.message.g4.win79.cashout.Win79CashoutMessageTypes;
import com.vingame.bot.domain.bot.message.slot.SlotMessageTypesImpl;
import com.vingame.bot.domain.bot.message.taixiu.JackpotTaiXiuMessageTypes;
import com.vingame.bot.domain.bot.message.taixiu.MiniGameTaiXiuMessageTypes;

import java.util.List;

/**
 * The message-types registry for the {@code BotFactory} wiring fixtures, carrying the
 * same providers the component scan discovers (PLUGIN_HOT_RELOAD Phase 2c), plus the
 * 119 cash-out provider (CASHOUT_BOT).
 *
 * <p><b>Deliberately not a mock.</b> What those fixtures assert is that a bot for a
 * given product ends up holding the right provider; a stubbed registry would make that
 * assertion about the stub. It is also deliberately <em>not</em> a scanned context:
 * whether the production scan finds these beans is a different question, asserted in
 * {@code MessageTypesRegistryTest} / {@code MessageTypesCoverageTest} (bot-messages)
 * and, under {@code Starter}'s own scan,
 * {@code ApplicationContextLoadsTest.messageTypesRegistryIsFullyPopulated}.
 */
final class TestMessageTypes {

    static final MessageTypesRegistry REGISTRY = new MessageTypesRegistry(
            List.of(new BomGameMessageTypes(), new TipGameMessageTypes(), new NohuGameMessageTypes()),
            List.of(new SlotMessageTypesImpl()),
            List.of(new MiniGameTaiXiuMessageTypes(), new JackpotTaiXiuMessageTypes()),
            List.of(new Win79CashoutMessageTypes()));

    private TestMessageTypes() {
    }
}
