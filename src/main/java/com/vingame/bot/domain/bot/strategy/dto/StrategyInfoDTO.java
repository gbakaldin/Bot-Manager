package com.vingame.bot.domain.bot.strategy.dto;

import com.vingame.bot.domain.bot.strategy.StrategyId;

/**
 * Wire shape for the {@code GET /api/v1/strategy/} listing. The frontend
 * populates the strategy-mix picker on the bot-group form from this — each
 * entry is shown as {@code displayName} with {@code description} as the
 * tooltip / secondary text, and {@code id} is what is POSTed back inside the
 * {@code strategyMix} list.
 */
public record StrategyInfoDTO(StrategyId id, String displayName, String description) {

    public static StrategyInfoDTO of(StrategyId id) {
        return new StrategyInfoDTO(id, id.getDisplayName(), id.getDescription());
    }
}
