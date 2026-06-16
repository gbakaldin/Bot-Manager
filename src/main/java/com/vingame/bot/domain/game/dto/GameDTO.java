package com.vingame.bot.domain.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.model.GameType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GameDTO {

    private String id;
    private BrandCode brandCode;
    private ProductCode productCode;

    private String name;
    private String description;
    private GameType gameType;

    private String pluginName;
    private Integer gameId;
    private Integer offset;
    private Boolean md5;

    /**
     * Option id → affinity weight. Read-side: returned from the mapper as the
     * unified game shape (Phase 1 of BETTING_STRATEGIES). PATCH semantics:
     * full-replace (no field-merge — see plan, Architecture Decision 6).
     */
    private Map<Integer, Integer> optionAffinities;

    /**
     * Write-only convenience shorthand. If {@code optionAffinities} is omitted
     * on create, the mapper expands {@code numberOfOptions=n} into
     * {@code {0:1, 1:1, ..., n-1:1}}. Ignored on PATCH (use
     * {@code optionAffinities} for updates). Not surfaced on read responses.
     */
    private Integer numberOfOptions;
}
