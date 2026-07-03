package com.vingame.bot.domain.botgroup.sort;

import com.vingame.bot.common.exception.BadRequestException;

import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sort-key catalog for the env-scoped bot-group filter (BOTGROUP_GAME_MANAGEMENT
 * Phase 4 / AD-11). Each key maps to a value extractor over a
 * {@link BotGroupSortRow}; a {@code null} extracted value is treated as N/A and
 * always sorts to the bottom (AD-12), regardless of direction.
 *
 * <p><b>Runtime-only keys</b> ({@link #STATUS}, {@link #BALANCE},
 * {@link #ACTIVE_BOTS}, {@link #ACTIVE_TIME}, {@link #AVG_WINNING}) are N/A when the
 * group is not running. For the stats-backed ones this is inherent — the Phase 3
 * {@link com.vingame.bot.domain.botgroup.dto.BotGroupStatsDTO} carries {@code null}
 * fields for a stopped group. {@link #STATUS} derives "not running" from the absence
 * of a runtime ({@code activeTimeSeconds == null}) so a stopped group's status also
 * sorts to the bottom.
 *
 * <p>There is intentionally <b>no {@code ENVIRONMENT} key</b> — the list is already
 * env-scoped by the path.
 */
public enum BotSortKey {

    /** Runtime status (ACTIVE/DEAD). N/A when the group is not running (AD-12). */
    STATUS(row -> row.stats().getActiveTimeSeconds() != null ? row.actualStatus() : null),

    /** Configured bot count (never N/A). */
    BOT_COUNT(row -> row.group().getBotCount()),

    /** First-persist timestamp (AD-14). */
    CREATED_TIME(row -> row.group().getCreatedAt()),

    /** Group name. */
    NAME(row -> row.group().getName()),

    /** Configured {@code maxBet} (never N/A). */
    BET_AMOUNT(row -> row.group().getMaxBet()),

    /** Group-average balance over active bots (AD-10). N/A when not running / no active bots. */
    BALANCE(row -> row.stats().getAverageBalance()),

    /** Live active-bot count (AD-10). N/A when not running. */
    ACTIVE_BOTS(row -> row.stats().getActiveBots()),

    /** Most-recent-persist timestamp (AD-16). */
    UPDATED_TIME(row -> row.group().getUpdatedAt()),

    /** Resolved game-type name. N/A when the referenced game is missing. */
    GAME_TYPE(BotGroupSortRow::gameType),

    /** Group-average cumulative winnings over active bots (AD-8). N/A when not running / no active bots. */
    AVG_WINNING(row -> row.stats().getAverageWinning()),

    /** Seconds since last start/restart (AD-9). N/A when not running. */
    ACTIVE_TIME(row -> row.stats().getActiveTimeSeconds()),

    /** Configured {@code maxTotalBetPerRound} (never N/A). */
    MAX_PER_ROUND(row -> row.group().getMaxTotalBetPerRound());

    /** Default key when {@code sortBy} is absent (AD-11). */
    public static final BotSortKey DEFAULT = CREATED_TIME;

    private final Function<BotGroupSortRow, Comparable<?>> extractor;

    BotSortKey(Function<BotGroupSortRow, Comparable<?>> extractor) {
        this.extractor = extractor;
    }

    /**
     * Extract the sort value for a row; {@code null} means N/A (sorts to the
     * bottom).
     */
    public Comparable<?> extract(BotGroupSortRow row) {
        return extractor.apply(row);
    }

    /**
     * Resolve a raw sort-key string case-insensitively (AD-11). Null/blank →
     * {@link #DEFAULT}; an unrecognised value → {@link BadRequestException} (HTTP
     * 400).
     */
    public static BotSortKey resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        for (BotSortKey key : values()) {
            if (key.name().equalsIgnoreCase(raw.trim())) {
                return key;
            }
        }
        throw new BadRequestException("Unknown bot-group sort key '" + raw + "'. Valid keys: "
                + Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", ")));
    }
}
