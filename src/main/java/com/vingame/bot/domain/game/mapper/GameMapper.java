package com.vingame.bot.domain.game.mapper;

import com.vingame.bot.domain.game.dto.GameDTO;
import com.vingame.bot.domain.game.model.Game;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

@Mapper(componentModel = "spring")
public interface GameMapper {

    /**
     * Convert Entity to DTO.
     * <p>
     * The read DTO surface is the unified {@code optionAffinities} map. Legacy
     * {@code numberOfOptions} is never echoed back — if the entity only carries
     * the legacy field, {@link Game#getEffectiveOptionAffinities()} synthesizes
     * the flat-prior map on the fly so the wire shape is consistent.
     */
    default GameDTO toDTO(Game entity) {
        if (entity == null) {
            return null;
        }

        Map<Integer, Integer> affinities = null;
        try {
            affinities = entity.getEffectiveOptionAffinities();
        } catch (IllegalStateException ignored) {
            // A misconfigured Game (neither optionAffinities nor numberOfOptions
            // set) should not crash the read path — surface an empty/null map
            // and let the operator notice.
        }

        return GameDTO.builder()
                .id(entity.getId())
                .brandCode(entity.getBrandCode())
                .productCode(entity.getProductCode())
                .name(entity.getName())
                .description(entity.getDescription())
                .gameType(entity.getGameType())
                .pluginName(entity.getPluginName())
                .gameId(entity.getGameId())
                .optionAffinities(affinities)
                .offset(entity.getOffset())
                .md5(entity.isMd5())
                .build();
    }

    /**
     * Convert DTO to Entity using Builder pattern.
     * <p>
     * On write:
     * <ul>
     *   <li>If the DTO carries {@code optionAffinities}, use it as-is.</li>
     *   <li>Else if the DTO carries the convenience {@code numberOfOptions} shorthand
     *       (create-only), expand to {@code {0:1, 1:1, ..., n-1:1}}.</li>
     *   <li>Else leave the field null — the read-side fallback will surface the
     *       misconfiguration on first access.</li>
     * </ul>
     * The legacy {@code numberOfOptions} field on the entity is never populated by
     * this mapper — new entities are always written with {@code optionAffinities}.
     */
    default Game toEntity(GameDTO dto) {
        if (dto == null) {
            return null;
        }

        Map<Integer, Integer> affinities = resolveAffinitiesForCreate(dto);

        return Game.builder()
                .id(dto.getId())
                .brandCode(dto.getBrandCode())
                .productCode(dto.getProductCode())
                .name(dto.getName())
                .description(dto.getDescription())
                .gameType(dto.getGameType())
                .pluginName(dto.getPluginName())
                .gameId(dto.getGameId())
                .optionAffinities(affinities)
                .offset(dto.getOffset())
                .md5(Optional.ofNullable(dto.getMd5()).orElse(false))
                .build();
    }

    /**
     * Partially update existing entity with non-null values from DTO.
     * <p>
     * PATCH semantics for {@code optionAffinities}: <b>full-replace</b> when
     * present in the DTO — no field-merge (see plan, Architecture Decision 6).
     * To remove a single option, the caller sends the full map minus that key.
     * <p>
     * The convenience {@code numberOfOptions} shorthand is intentionally ignored
     * on PATCH — use {@code optionAffinities} for updates. Clearing the legacy
     * field on already-migrated docs is the Phase 6 migration's responsibility.
     */
    default void updateEntityFromDTO(GameDTO dto, @MappingTarget Game entity) {
        if (dto == null || entity == null) {
            return;
        }

        entity.setBrandCode(Optional.ofNullable(dto.getBrandCode()).orElse(entity.getBrandCode()));
        entity.setProductCode(Optional.ofNullable(dto.getProductCode()).orElse(entity.getProductCode()));
        entity.setName(Optional.ofNullable(dto.getName()).orElse(entity.getName()));
        entity.setDescription(Optional.ofNullable(dto.getDescription()).orElse(entity.getDescription()));
        entity.setGameType(Optional.ofNullable(dto.getGameType()).orElse(entity.getGameType()));
        entity.setPluginName(Optional.ofNullable(dto.getPluginName()).orElse(entity.getPluginName()));
        entity.setGameId(Optional.ofNullable(dto.getGameId()).orElse(entity.getGameId()));
        if (dto.getOptionAffinities() != null) {
            entity.setOptionAffinities(dto.getOptionAffinities());
        }
        entity.setOffset(Optional.ofNullable(dto.getOffset()).orElse(entity.getOffset()));
        entity.setMd5(Optional.ofNullable(dto.getMd5()).orElse(entity.isMd5()));
    }

    private static Map<Integer, Integer> resolveAffinitiesForCreate(GameDTO dto) {
        if (dto.getOptionAffinities() != null && !dto.getOptionAffinities().isEmpty()) {
            return dto.getOptionAffinities();
        }
        Integer legacy = dto.getNumberOfOptions();
        if (legacy != null && legacy > 0) {
            Map<Integer, Integer> synthesized = new LinkedHashMap<>(legacy);
            IntStream.range(0, legacy).forEach(i -> synthesized.put(i, 1));
            return synthesized;
        }
        return null;
    }
}
