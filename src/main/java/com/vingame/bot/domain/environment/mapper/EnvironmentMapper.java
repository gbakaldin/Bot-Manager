package com.vingame.bot.domain.environment.mapper;

import com.vingame.bot.domain.environment.dto.EnvironmentDTO;
import com.vingame.bot.domain.environment.model.Environment;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import java.util.Optional;

@Mapper(componentModel = "spring")
public interface EnvironmentMapper {

    /**
     * Convert Entity to DTO
     * Null fields will be excluded from JSON due to @JsonInclude(NON_NULL)
     */
    default EnvironmentDTO toDTO(Environment entity) {
        if (entity == null) {
            return null;
        }

        return EnvironmentDTO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .type(entity.getType())
                .brandCode(entity.getBrandCode())
                .productCode(entity.getProductCode())
                .webSocketMiniUrl(entity.getWebSocketMiniUrl())
                .webSocketCardUrl(entity.getWebSocketCardUrl())
                .hostUrl(entity.getHostUrl())
                .apiGatewayUrl(entity.getApiGatewayUrl())
                .appId(entity.getAppId())
                .headers(entity.getHeaders())
                .customZone(entity.isCustomZone())
                .binaryFrame(entity.isBinaryFrame())
                .miniZoneName(entity.getMiniZoneName())
                .cardZoneName(entity.getCardZoneName())
                .encryptionKey(entity.getEncryptionKey())
                .encryptionIv(entity.getEncryptionIv())
                .alertOnLowBalance(entity.isAlertOnLowBalance())
                .build();
    }

    /**
     * Convert DTO to Entity using Builder pattern
     * Uses Optional.ofNullable().orElse() for null-safe defaults
     */
    default Environment toEntity(EnvironmentDTO dto) {
        if (dto == null) {
            return null;
        }

        return Environment.builder()
                .id(dto.getId())
                .name(dto.getName())
                .type(dto.getType())
                .brandCode(dto.getBrandCode())
                .productCode(dto.getProductCode())
                .webSocketMiniUrl(dto.getWebSocketMiniUrl())
                .webSocketCardUrl(dto.getWebSocketCardUrl())
                .hostUrl(dto.getHostUrl())
                .apiGatewayUrl(dto.getApiGatewayUrl())
                .appId(dto.getAppId())
                .headers(dto.getHeaders())
                .customZone(Optional.ofNullable(dto.getCustomZone()).orElse(false))
                .binaryFrame(Optional.ofNullable(dto.getBinaryFrame()).orElse(false))
                .miniZoneName(dto.getMiniZoneName())
                .cardZoneName(dto.getCardZoneName())
                .encryptionKey(dto.getEncryptionKey())
                .encryptionIv(dto.getEncryptionIv())
                .alertOnLowBalance(Optional.ofNullable(dto.getAlertOnLowBalance()).orElse(false))
                .build();
    }

    /**
     * Partially update existing entity with non-null values from DTO
     * Uses Optional.ofNullable().orElse() to keep existing value if DTO field is null
     */
    default void updateEntityFromDTO(EnvironmentDTO dto, @MappingTarget Environment entity) {
        if (dto == null || entity == null) {
            return;
        }

        entity.setName(Optional.ofNullable(dto.getName()).orElse(entity.getName()));
        entity.setType(Optional.ofNullable(dto.getType()).orElse(entity.getType()));
        entity.setBrandCode(Optional.ofNullable(dto.getBrandCode()).orElse(entity.getBrandCode()));
        entity.setProductCode(Optional.ofNullable(dto.getProductCode()).orElse(entity.getProductCode()));
        entity.setWebSocketMiniUrl(Optional.ofNullable(dto.getWebSocketMiniUrl()).orElse(entity.getWebSocketMiniUrl()));
        entity.setWebSocketCardUrl(Optional.ofNullable(dto.getWebSocketCardUrl()).orElse(entity.getWebSocketCardUrl()));
        entity.setHostUrl(Optional.ofNullable(dto.getHostUrl()).orElse(entity.getHostUrl()));
        entity.setApiGatewayUrl(Optional.ofNullable(dto.getApiGatewayUrl()).orElse(entity.getApiGatewayUrl()));
        entity.setAppId(Optional.ofNullable(dto.getAppId()).orElse(entity.getAppId()));
        entity.setHeaders(Optional.ofNullable(dto.getHeaders()).orElse(entity.getHeaders()));
        entity.setCustomZone(Optional.ofNullable(dto.getCustomZone()).orElse(entity.isCustomZone()));
        entity.setBinaryFrame(Optional.ofNullable(dto.getBinaryFrame()).orElse(entity.isBinaryFrame()));
        entity.setMiniZoneName(Optional.ofNullable(dto.getMiniZoneName()).orElse(entity.getMiniZoneName()));
        entity.setCardZoneName(Optional.ofNullable(dto.getCardZoneName()).orElse(entity.getCardZoneName()));
        entity.setEncryptionKey(Optional.ofNullable(dto.getEncryptionKey()).orElse(entity.getEncryptionKey()));
        entity.setEncryptionIv(Optional.ofNullable(dto.getEncryptionIv()).orElse(entity.getEncryptionIv()));
        entity.setAlertOnLowBalance(Optional.ofNullable(dto.getAlertOnLowBalance()).orElse(entity.isAlertOnLowBalance()));
    }
}
