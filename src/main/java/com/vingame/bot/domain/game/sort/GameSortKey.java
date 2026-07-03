package com.vingame.bot.domain.game.sort;

import com.vingame.bot.common.exception.BadRequestException;

import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sort-key catalog for the env-scoped game filter (BOTGROUP_GAME_MANAGEMENT
 * Phase 5). Mirrors the Phase 4 {@link com.vingame.bot.domain.botgroup.sort.BotSortKey}
 * pattern: each key maps to a value extractor over a {@link GameSortRow}; a
 * {@code null} extracted value is treated as N/A and always sorts to the bottom
 * (AD-12), regardless of direction.
 *
 * <p><b>Active aggregate keys</b> ({@link #ACTIVE_GROUP_COUNT},
 * {@link #ACTIVE_BOT_COUNT}) are N/A when the game is inactive — i.e. no referencing
 * bot group is running ({@link GameSortRow#active()}). {@link #BOT_GROUP_COUNT} and
 * {@link #BOT_COUNT} are configured/count aggregates and never N/A (0 when the game
 * has no referencing groups).
 *
 * <p>There is intentionally <b>no {@code BRAND}/{@code PRODUCT} key</b> — the list is
 * already scoped by {@code (brandCode, productCode, envId)} in the path.
 */
public enum GameSortKey {

    /** First-persist timestamp (AD-14). */
    CREATED_TIME(row -> row.game().getCreatedAt()),

    /** Number of bot groups referencing the game (never N/A; 0 when none). */
    BOT_GROUP_COUNT(row -> row.botGroupCount()),

    /** Σ configured {@code botCount} over referencing groups (never N/A). */
    BOT_COUNT(row -> row.botCount()),

    /** Resolved game-type name. N/A when the game has no {@code gameType}. */
    GAME_TYPE(row -> row.game().getGameType() != null ? row.game().getGameType().name() : null),

    /** Game name. */
    NAME(row -> row.game().getName()),

    /** Σ running groups. N/A when the game is inactive (no running group). */
    ACTIVE_GROUP_COUNT(row -> row.active() ? Integer.valueOf(row.activeGroupCount()) : null),

    /** Σ running bots over referencing groups. N/A when the game is inactive. */
    ACTIVE_BOT_COUNT(row -> row.active() ? Integer.valueOf(row.activeBotCount()) : null);

    /** Default key when {@code sortBy} is absent (AD-11). */
    public static final GameSortKey DEFAULT = CREATED_TIME;

    private final Function<GameSortRow, Comparable<?>> extractor;

    GameSortKey(Function<GameSortRow, Comparable<?>> extractor) {
        this.extractor = extractor;
    }

    /**
     * Extract the sort value for a row; {@code null} means N/A (sorts to the
     * bottom).
     */
    public Comparable<?> extract(GameSortRow row) {
        return extractor.apply(row);
    }

    /**
     * Resolve a raw sort-key string case-insensitively (AD-11). Null/blank →
     * {@link #DEFAULT}; an unrecognised value → {@link BadRequestException} (HTTP
     * 400).
     */
    public static GameSortKey resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        for (GameSortKey key : values()) {
            if (key.name().equalsIgnoreCase(raw.trim())) {
                return key;
            }
        }
        throw new BadRequestException("Unknown game sort key '" + raw + "'. Valid keys: "
                + Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", ")));
    }
}
