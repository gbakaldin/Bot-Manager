package com.vingame.bot.domain.game.dto;

import com.vingame.bot.domain.game.model.GameType;

/**
 * Wire shape for the {@code GET /api/v1/game/types} endpoint. Pairs the
 * canonical enum code with a human-readable label so the frontend can render
 * the dropdown without hardcoding labels of its own.
 */
public record GameTypeDTO(GameType code, String displayName) {

    public static GameTypeDTO of(GameType type) {
        return new GameTypeDTO(type, type.getDisplayName());
    }
}
