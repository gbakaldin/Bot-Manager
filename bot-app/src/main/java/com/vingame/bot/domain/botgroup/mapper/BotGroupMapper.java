package com.vingame.bot.domain.botgroup.mapper;

import com.vingame.bot.common.exception.BadRequestException;
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
                .coordinationEnabled(entity.isCoordinationEnabled())
                .maxAggregateStakePerRound(entity.getMaxAggregateStakePerRound())
                .crowdAwareCoordination(entity.isCrowdAwareCoordination())
                .rampEnabled(entity.isRampEnabled())
                .rampShape(entity.getRampShape())
                .affinityWeightedProposal(entity.isAffinityWeightedProposal())
                .activationMode(entity.getActivationMode())
                .activationWindow(entity.getActivationWindow())
                .chatEnabled(entity.isChatEnabled())
                .autoDepositEnabled(entity.isAutoDepositEnabled())
                .strategyMix(entity.getStrategyMix())
                .slotStrategyId(entity.getSlotStrategyId())
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
                .coordinationEnabled(Optional.ofNullable(dto.getCoordinationEnabled()).orElse(false))
                .maxAggregateStakePerRound(Optional.ofNullable(dto.getMaxAggregateStakePerRound()).orElse(0L))
                .crowdAwareCoordination(Optional.ofNullable(dto.getCrowdAwareCoordination()).orElse(false))
                .rampEnabled(Optional.ofNullable(dto.getRampEnabled()).orElse(false))
                .rampShape(Optional.ofNullable(dto.getRampShape()).orElse(0.0))
                .affinityWeightedProposal(Optional.ofNullable(dto.getAffinityWeightedProposal()).orElse(false))
                .activationMode(dto.getActivationMode())
                .activationWindow(dto.getActivationWindow())
                .chatEnabled(Optional.ofNullable(dto.getChatEnabled()).orElse(false))
                .autoDepositEnabled(Optional.ofNullable(dto.getAutoDepositEnabled()).orElse(false))
                .strategyMix(dto.getStrategyMix())
                .slotStrategyId(dto.getSlotStrategyId())
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
        // coordinationEnabled / maxAggregateStakePerRound PATCH semantics:
        // full-replace if the DTO supplies the field (non-null); a null DTO field
        // keeps the existing value. Mirrors the other scalar fields.
        entity.setCoordinationEnabled(Optional.ofNullable(dto.getCoordinationEnabled()).orElse(entity.isCoordinationEnabled()));
        entity.setMaxAggregateStakePerRound(Optional.ofNullable(dto.getMaxAggregateStakePerRound()).orElse(entity.getMaxAggregateStakePerRound()));
        // crowdAwareCoordination PATCH semantics (CROWD_AWARE_COORDINATION AD-C6):
        // full-replace if the DTO supplies the field (non-null); a null DTO field
        // keeps the existing value. Mirrors the coordination scalar pair.
        entity.setCrowdAwareCoordination(Optional.ofNullable(dto.getCrowdAwareCoordination()).orElse(entity.isCrowdAwareCoordination()));
        // rampEnabled / rampShape PATCH semantics (JACKPOT_SCALE_AND_RAMP AD-R4):
        // full-replace if the DTO supplies the field (non-null); a null DTO field
        // keeps the existing value. Mirrors the coordination scalar pair.
        entity.setRampEnabled(Optional.ofNullable(dto.getRampEnabled()).orElse(entity.isRampEnabled()));
        entity.setRampShape(Optional.ofNullable(dto.getRampShape()).orElse(entity.getRampShape()));
        // affinityWeightedProposal PATCH semantics (AFFINITY_AWARE_PROPOSAL AD-7):
        // full-replace if the DTO supplies the field (non-null); a null DTO field
        // keeps the existing value. Mirrors the ramp/coordination scalar flags.
        entity.setAffinityWeightedProposal(Optional.ofNullable(dto.getAffinityWeightedProposal()).orElse(entity.isAffinityWeightedProposal()));
        // activationMode / activationWindow PATCH semantics: full-replace if the
        // DTO supplies the field (non-null); a null DTO field keeps the existing
        // value. Mirrors slotStrategyId. To hand a scheduled group back to the
        // schedule after a manual override, PATCH activationMode=SCHEDULED
        // (TIMED_ACTIVATION AD-4).
        entity.setActivationMode(Optional.ofNullable(dto.getActivationMode()).orElse(entity.getActivationMode()));
        entity.setActivationWindow(Optional.ofNullable(dto.getActivationWindow()).orElse(entity.getActivationWindow()));
        entity.setChatEnabled(Optional.ofNullable(dto.getChatEnabled()).orElse(entity.isChatEnabled()));
        entity.setAutoDepositEnabled(Optional.ofNullable(dto.getAutoDepositEnabled()).orElse(entity.isAutoDepositEnabled()));
        // strategyMix PATCH semantics: full-replace if DTO supplies the field
        // (non-null). A null DTO field keeps the existing value. An empty list
        // is rejected as a 400 — leaving a group with no assignable strategies
        // would crash the next start. Operators who want to clear the mix
        // should not supply the field at all (PATCH then keeps the existing
        // value), or set it to the default [(RANDOM, 1.0)].
        // Plan Architecture Decision 9 + Implementation Note 10: mid-flight
        // changes do NOT re-assign already-running bots — only newly-created /
        // restarted bots draw from the new mix.
        if (dto.getStrategyMix() != null) {
            if (dto.getStrategyMix().isEmpty()) {
                throw new BadRequestException("strategyMix must be non-empty");
            }
            entity.setStrategyMix(dto.getStrategyMix());
        }
        // slotStrategyId PATCH semantics: full-replace if DTO supplies the field
        // (non-null); a null DTO field keeps the existing value. No empty-value
        // case to guard — it is a single nullable enum, and null is a valid
        // "fall back to FIXED" state. Mid-flight changes do NOT re-assign
        // already-running bots, mirroring strategyMix.
        entity.setSlotStrategyId(Optional.ofNullable(dto.getSlotStrategyId()).orElse(entity.getSlotStrategyId()));
        entity.setTargetStatus(Optional.ofNullable(dto.getTargetStatus()).orElse(entity.getTargetStatus()));
        entity.setScheduledRestartTime(Optional.ofNullable(dto.getScheduledRestartTime()).orElse(entity.getScheduledRestartTime()));
        // Note: lastStartedAt, lastStoppedAt, lastFailureReason are system-managed, not updated via DTO
    }
}
