package com.vingame.bot.domain.botgroup.mapper;

import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import java.util.Optional;

@Mapper(componentModel = "spring")
public interface BotGroupMapper {

    /**
     * Convert Entity to DTO
     * Null fields will be excluded from JSON due to @JsonInclude(NON_NULL)
     */
    default BotGroupDTO toDTO(BotGroup entity) {
        if (entity == null) {
            return null;
        }

        return BotGroupDTO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .environmentId(entity.getEnvironmentId())
                .namePrefix(entity.getNamePrefix())
                .password(entity.getPassword())
                .gameId(entity.getGameId())
                .botCount(entity.getBotCount())
                .minBet(entity.getMinBet())
                .maxBet(entity.getMaxBet())
                .betIncrement(entity.getBetIncrement())
                .maxTotalBetPerRound(entity.getMaxTotalBetPerRound())
                .minBetsPerRound(entity.getMinBetsPerRound())
                .maxBetsPerRound(entity.getMaxBetsPerRound())
                .timeBased(entity.isTimeBased())
                .timeFrom(entity.getTimeFrom())
                .timeUntil(entity.getTimeUntil())
                .chatEnabled(entity.isChatEnabled())
                .autoDepositEnabled(entity.isAutoDepositEnabled())
                .targetStatus(entity.getTargetStatus())
                .scheduledRestartTime(entity.getScheduledRestartTime())
                .lastStartedAt(entity.getLastStartedAt())
                .lastStoppedAt(entity.getLastStoppedAt())
                .lastFailureReason(entity.getLastFailureReason())
                .build();
    }

    /**
     * Convert DTO to Entity using Builder pattern
     * Uses Optional.ofNullable().orElse() for null-safe defaults
     */
    default BotGroup toEntity(BotGroupDTO dto) {
        if (dto == null) {
            return null;
        }

        return BotGroup.builder()
                .id(dto.getId())
                .name(dto.getName())
                .environmentId(dto.getEnvironmentId())
                .namePrefix(dto.getNamePrefix())
                .password(dto.getPassword())
                .gameId(dto.getGameId())
                .botCount(Optional.ofNullable(dto.getBotCount()).orElse(0))
                .minBet(Optional.ofNullable(dto.getMinBet()).orElse(0L))
                .maxBet(Optional.ofNullable(dto.getMaxBet()).orElse(0L))
                .betIncrement(Optional.ofNullable(dto.getBetIncrement()).orElse(0L))
                .maxTotalBetPerRound(Optional.ofNullable(dto.getMaxTotalBetPerRound()).orElse(0L))
                .minBetsPerRound(Optional.ofNullable(dto.getMinBetsPerRound()).orElse(0))
                .maxBetsPerRound(Optional.ofNullable(dto.getMaxBetsPerRound()).orElse(0))
                .timeBased(Optional.ofNullable(dto.getTimeBased()).orElse(false))
                .timeFrom(dto.getTimeFrom())
                .timeUntil(dto.getTimeUntil())
                .chatEnabled(Optional.ofNullable(dto.getChatEnabled()).orElse(false))
                .autoDepositEnabled(Optional.ofNullable(dto.getAutoDepositEnabled()).orElse(false))
                .targetStatus(dto.getTargetStatus())
                .scheduledRestartTime(dto.getScheduledRestartTime())
                .lastStartedAt(dto.getLastStartedAt())
                .lastStoppedAt(dto.getLastStoppedAt())
                .lastFailureReason(dto.getLastFailureReason())
                .build();
    }

    /**
     * Partially update existing entity with non-null values from DTO
     * Uses Optional.ofNullable().orElse() to keep existing value if DTO field is null
     */
    default void updateEntityFromDTO(BotGroupDTO dto, @MappingTarget BotGroup entity) {
        if (dto == null || entity == null) {
            return;
        }

        entity.setName(Optional.ofNullable(dto.getName()).orElse(entity.getName()));
        entity.setEnvironmentId(Optional.ofNullable(dto.getEnvironmentId()).orElse(entity.getEnvironmentId()));
        entity.setNamePrefix(Optional.ofNullable(dto.getNamePrefix()).orElse(entity.getNamePrefix()));
        entity.setPassword(Optional.ofNullable(dto.getPassword()).orElse(entity.getPassword()));
        entity.setGameId(Optional.ofNullable(dto.getGameId()).orElse(entity.getGameId()));
        entity.setBotCount(Optional.ofNullable(dto.getBotCount()).orElse(entity.getBotCount()));
        entity.setMinBet(Optional.ofNullable(dto.getMinBet()).orElse(entity.getMinBet()));
        entity.setMaxBet(Optional.ofNullable(dto.getMaxBet()).orElse(entity.getMaxBet()));
        entity.setBetIncrement(Optional.ofNullable(dto.getBetIncrement()).orElse(entity.getBetIncrement()));
        entity.setMaxTotalBetPerRound(Optional.ofNullable(dto.getMaxTotalBetPerRound()).orElse(entity.getMaxTotalBetPerRound()));
        entity.setMinBetsPerRound(Optional.ofNullable(dto.getMinBetsPerRound()).orElse(entity.getMinBetsPerRound()));
        entity.setMaxBetsPerRound(Optional.ofNullable(dto.getMaxBetsPerRound()).orElse(entity.getMaxBetsPerRound()));
        entity.setTimeBased(Optional.ofNullable(dto.getTimeBased()).orElse(entity.isTimeBased()));
        entity.setTimeFrom(Optional.ofNullable(dto.getTimeFrom()).orElse(entity.getTimeFrom()));
        entity.setTimeUntil(Optional.ofNullable(dto.getTimeUntil()).orElse(entity.getTimeUntil()));
        entity.setChatEnabled(Optional.ofNullable(dto.getChatEnabled()).orElse(entity.isChatEnabled()));
        entity.setAutoDepositEnabled(Optional.ofNullable(dto.getAutoDepositEnabled()).orElse(entity.isAutoDepositEnabled()));
        entity.setTargetStatus(Optional.ofNullable(dto.getTargetStatus()).orElse(entity.getTargetStatus()));
        entity.setScheduledRestartTime(Optional.ofNullable(dto.getScheduledRestartTime()).orElse(entity.getScheduledRestartTime()));
        // Note: lastStartedAt, lastStoppedAt, lastFailureReason are system-managed, not updated via DTO
    }
}
