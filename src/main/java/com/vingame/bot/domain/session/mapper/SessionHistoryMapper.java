package com.vingame.bot.domain.session.mapper;

import com.vingame.bot.domain.session.dto.SessionHistoryDTO;
import com.vingame.bot.domain.session.model.SessionHistory;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

import java.util.Optional;

@Mapper(componentModel = "spring")
public interface SessionHistoryMapper {

    default SessionHistoryDTO toDTO(SessionHistory entity) {
        if (entity == null) {
            return null;
        }

        return SessionHistoryDTO.builder()
                .id(entity.getId())
                .sessionId(entity.getSessionId())
                .gameId(entity.getGameId())
                .gameName(entity.getGameName())
                .environmentId(entity.getEnvironmentId())
                .startedAt(entity.getStartedAt())
                .endedAt(entity.getEndedAt())
                .botCount(entity.getBotCount())
                .totalBotBet(entity.getTotalBotBet())
                .totalPlayerBet(entity.getTotalPlayerBet())
                .botRtp(entity.getBotRtp())
                .totalRtp(entity.getTotalRtp())
                .jackpot(entity.isJackpot())
                .botJackpotWinnings(entity.getBotJackpotWinnings())
                .playerJackpotWinnings(entity.getPlayerJackpotWinnings())
                .totalWinningsSinceLastDeposit(entity.getTotalWinningsSinceLastDeposit())
                .lastDepositSessionId(entity.getLastDepositSessionId())
                .build();
    }

    default SessionHistory toEntity(SessionHistoryDTO dto) {
        if (dto == null) {
            return null;
        }

        return SessionHistory.builder()
                .id(dto.getId())
                .sessionId(dto.getSessionId())
                .gameId(dto.getGameId())
                .gameName(dto.getGameName())
                .environmentId(dto.getEnvironmentId())
                .startedAt(dto.getStartedAt())
                .endedAt(dto.getEndedAt())
                .botCount(Optional.ofNullable(dto.getBotCount()).orElse(0))
                .totalBotBet(Optional.ofNullable(dto.getTotalBotBet()).orElse(0L))
                .totalPlayerBet(Optional.ofNullable(dto.getTotalPlayerBet()).orElse(0L))
                .botRtp(dto.getBotRtp())
                .totalRtp(dto.getTotalRtp())
                .jackpot(Optional.ofNullable(dto.getJackpot()).orElse(false))
                .botJackpotWinnings(Optional.ofNullable(dto.getBotJackpotWinnings()).orElse(0L))
                .playerJackpotWinnings(Optional.ofNullable(dto.getPlayerJackpotWinnings()).orElse(0L))
                .totalWinningsSinceLastDeposit(Optional.ofNullable(dto.getTotalWinningsSinceLastDeposit()).orElse(0L))
                .lastDepositSessionId(dto.getLastDepositSessionId())
                .build();
    }

    default void updateEntityFromDTO(SessionHistoryDTO dto, @MappingTarget SessionHistory entity) {
        if (dto == null || entity == null) {
            return;
        }

        entity.setSessionId(Optional.ofNullable(dto.getSessionId()).orElse(entity.getSessionId()));
        entity.setGameId(Optional.ofNullable(dto.getGameId()).orElse(entity.getGameId()));
        entity.setGameName(Optional.ofNullable(dto.getGameName()).orElse(entity.getGameName()));
        entity.setEnvironmentId(Optional.ofNullable(dto.getEnvironmentId()).orElse(entity.getEnvironmentId()));
        entity.setStartedAt(Optional.ofNullable(dto.getStartedAt()).orElse(entity.getStartedAt()));
        entity.setEndedAt(Optional.ofNullable(dto.getEndedAt()).orElse(entity.getEndedAt()));
        entity.setBotCount(Optional.ofNullable(dto.getBotCount()).orElse(entity.getBotCount()));
        entity.setTotalBotBet(Optional.ofNullable(dto.getTotalBotBet()).orElse(entity.getTotalBotBet()));
        entity.setTotalPlayerBet(Optional.ofNullable(dto.getTotalPlayerBet()).orElse(entity.getTotalPlayerBet()));
        entity.setBotRtp(Optional.ofNullable(dto.getBotRtp()).orElse(entity.getBotRtp()));
        entity.setTotalRtp(Optional.ofNullable(dto.getTotalRtp()).orElse(entity.getTotalRtp()));
        entity.setJackpot(Optional.ofNullable(dto.getJackpot()).orElse(entity.isJackpot()));
        entity.setBotJackpotWinnings(Optional.ofNullable(dto.getBotJackpotWinnings()).orElse(entity.getBotJackpotWinnings()));
        entity.setPlayerJackpotWinnings(Optional.ofNullable(dto.getPlayerJackpotWinnings()).orElse(entity.getPlayerJackpotWinnings()));
        entity.setTotalWinningsSinceLastDeposit(Optional.ofNullable(dto.getTotalWinningsSinceLastDeposit()).orElse(entity.getTotalWinningsSinceLastDeposit()));
        entity.setLastDepositSessionId(Optional.ofNullable(dto.getLastDepositSessionId()).orElse(entity.getLastDepositSessionId()));
    }
}
