package com.vingame.bot.domain.bot.strategy.dto;

import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;

/**
 * Wire shape for the {@code GET /api/v1/strategy/} listing. The frontend
 * populates the strategy picker on the bot-group form from this — each entry is
 * shown as {@code displayName} with {@code description} as the tooltip /
 * secondary text, and {@code id} is the enum name that is POSTed back (inside
 * the {@code strategyMix} list for betting groups, or as {@code slotStrategyId}
 * for slot groups).
 *
 * <p>{@code id} is the bare enum name (e.g. {@code "RANDOM"}) so the DTO shape
 * is identical across game types — the listing endpoint serves both betting
 * {@link StrategyId} and slot {@link SlotStrategyId} families through this one
 * record.
 */
public record StrategyInfoDTO(String id, String displayName, String description) {

    public static StrategyInfoDTO of(StrategyId id) {
        return new StrategyInfoDTO(id.name(), id.getDisplayName(), id.getDescription());
    }

    public static StrategyInfoDTO of(SlotStrategyId id) {
        return new StrategyInfoDTO(id.name(), id.getDisplayName(), id.getDescription());
    }
}
