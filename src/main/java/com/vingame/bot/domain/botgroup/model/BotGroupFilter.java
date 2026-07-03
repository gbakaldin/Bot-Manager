package com.vingame.bot.domain.botgroup.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BotGroupFilter {

    private String name;
    private String gameId;

    /**
     * Sort key, resolved case-insensitively against
     * {@link com.vingame.bot.domain.botgroup.sort.BotSortKey}
     * (BOTGROUP_GAME_MANAGEMENT Phase 4 / AD-11). Null/blank → {@code CREATED_TIME};
     * an unrecognised value → HTTP 400.
     */
    private String sortBy;

    /**
     * Sort direction, resolved case-insensitively against {@code asc}/{@code desc}
     * (AD-11). Null/blank or unrecognised → {@code desc}.
     */
    private String sortDir;
}
