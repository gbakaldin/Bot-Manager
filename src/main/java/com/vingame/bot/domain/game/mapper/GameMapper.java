package com.vingame.bot.domain.game.mapper;

import com.vingame.bot.domain.game.dto.GameDTO;
import com.vingame.bot.domain.game.model.Game;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import java.util.Optional;

@Mapper(componentModel = "spring")
public interface GameMapper {

    /**
     * Convert Entity to DTO
     * Null fields will be excluded from JSON due to @JsonInclude(NON_NULL)
     */
    default GameDTO toDTO(Game entity) {
        if (entity == null) {
            return null;
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
                .numberOfOptions(entity.getNumberOfOptions())
                .offset(entity.getOffset())
                .md5(entity.isMd5())
                .build();
    }

    /**
     * Convert DTO to Entity using Builder pattern
     * Uses Optional.ofNullable().orElse() for null-safe defaults
     */
    default Game toEntity(GameDTO dto) {
        if (dto == null) {
            return null;
        }

        return Game.builder()
                .id(dto.getId())
                .brandCode(dto.getBrandCode())
                .productCode(dto.getProductCode())
                .name(dto.getName())
                .description(dto.getDescription())
                .gameType(dto.getGameType())
                .pluginName(dto.getPluginName())
                .gameId(dto.getGameId())
                .numberOfOptions(Optional.ofNullable(dto.getNumberOfOptions()).orElse(0))
                .offset(dto.getOffset())
                .md5(Optional.ofNullable(dto.getMd5()).orElse(false))
                .build();
    }

    /**
     * Partially update existing entity with non-null values from DTO
     * Uses Optional.ofNullable().orElse() to keep existing value if DTO field is null
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
        entity.setNumberOfOptions(Optional.ofNullable(dto.getNumberOfOptions()).orElse(entity.getNumberOfOptions()));
        entity.setOffset(Optional.ofNullable(dto.getOffset()).orElse(entity.getOffset()));
        entity.setMd5(Optional.ofNullable(dto.getMd5()).orElse(entity.isMd5()));
    }
}
