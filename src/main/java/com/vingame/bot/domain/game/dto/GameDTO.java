package com.vingame.bot.domain.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.model.CrowdCountSemantic;
import com.vingame.bot.domain.game.model.GameType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
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

    /**
     * Environment this game belongs to. Read-side only — on create it is set
     * from the request path by the controller (mirroring brand/product), never
     * from the request body.
     */
    private String environmentId;

    /** Read-side only. First-persist timestamp; never written from the DTO. */
    private Instant createdAt;

    /** Read-side only. Last-persist timestamp; never written from the DTO. */
    private Instant updatedAt;

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
     * Jackpot-based volume scale toggle (JACKPOT_SCALE_AND_RAMP AD-J1). Boxed so
     * a PATCH that omits it keeps the existing entity value (PATCH-null = keep).
     */
    private Boolean jackpotScaleEnabled;

    /**
     * Jackpot pool ceiling at which bots bet at full intensity
     * (JACKPOT_SCALE_AND_RAMP AD-J1). Boxed for the same PATCH-null = keep
     * semantics. Validated to exceed the seed floor ({@code > 500000}) when
     * {@code jackpotScaleEnabled} is true.
     */
    private Long jackpotCeiling;

    /**
     * Per-game crowd count semantic (CROWD_AWARE_COORDINATION AD-C5). PATCH-null =
     * keep the persisted value; a non-null value full-replaces it. Any enum value
     * is valid ({@code UNKNOWN} is the safe default) — no validation rule, since it
     * is observability-only in v1.
     */
    private CrowdCountSemantic crowdCountSemantic;

    /**
     * Write-only convenience shorthand. If {@code optionAffinities} is omitted
     * on create, the mapper expands {@code numberOfOptions=n} into
     * {@code {0:1, 1:1, ..., n-1:1}}. Ignored on PATCH (use
     * {@code optionAffinities} for updates). Not surfaced on read responses.
     */
    private Integer numberOfOptions;
}
