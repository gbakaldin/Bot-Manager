package com.vingame.bot.domain.session.mapper;

import com.vingame.bot.domain.session.dto.SessionHistoryDTO;
import com.vingame.bot.domain.session.model.SessionHistory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SessionHistoryMapper")
class SessionHistoryMapperTest {

    private final SessionHistoryMapper mapper = Mappers.getMapper(SessionHistoryMapper.class);

    @Nested
    @DisplayName("toDTO")
    class ToDTOTests {

        @Test
        @DisplayName("Should map all fields from entity to DTO")
        void shouldMapAllFields() {
            Instant start = Instant.parse("2026-01-01T12:00:00Z");
            Instant end = Instant.parse("2026-01-01T12:05:00Z");
            SessionHistory entity = SessionHistory.builder()
                    .id("history-1")
                    .sessionId("sid-1")
                    .gameId("game-1")
                    .gameName("BauCua")
                    .environmentId("env-1")
                    .startedAt(start)
                    .endedAt(end)
                    .botCount(10)
                    .totalBotBet(5000L)
                    .totalPlayerBet(10000L)
                    .botRtp(95.5)
                    .totalRtp(97.2)
                    .jackpot(true)
                    .botJackpotWinnings(1000L)
                    .playerJackpotWinnings(2000L)
                    .totalWinningsSinceLastDeposit(7500L)
                    .lastDepositSessionId("sid-0")
                    .build();

            SessionHistoryDTO dto = mapper.toDTO(entity);

            assertThat(dto.getId()).isEqualTo("history-1");
            assertThat(dto.getSessionId()).isEqualTo("sid-1");
            assertThat(dto.getGameId()).isEqualTo("game-1");
            assertThat(dto.getGameName()).isEqualTo("BauCua");
            assertThat(dto.getEnvironmentId()).isEqualTo("env-1");
            assertThat(dto.getStartedAt()).isEqualTo(start);
            assertThat(dto.getEndedAt()).isEqualTo(end);
            assertThat(dto.getBotCount()).isEqualTo(10);
            assertThat(dto.getTotalBotBet()).isEqualTo(5000L);
            assertThat(dto.getTotalPlayerBet()).isEqualTo(10000L);
            assertThat(dto.getBotRtp()).isEqualTo(95.5);
            assertThat(dto.getTotalRtp()).isEqualTo(97.2);
            assertThat(dto.getJackpot()).isTrue();
            assertThat(dto.getBotJackpotWinnings()).isEqualTo(1000L);
            assertThat(dto.getPlayerJackpotWinnings()).isEqualTo(2000L);
            assertThat(dto.getTotalWinningsSinceLastDeposit()).isEqualTo(7500L);
            assertThat(dto.getLastDepositSessionId()).isEqualTo("sid-0");
        }

        @Test
        @DisplayName("Should return null when entity is null")
        void shouldReturnNullForNull() {
            assertThat(mapper.toDTO(null)).isNull();
        }
    }

    @Nested
    @DisplayName("toEntity")
    class ToEntityTests {

        @Test
        @DisplayName("Should map all fields from DTO to entity")
        void shouldMapAllFields() {
            Instant start = Instant.parse("2026-01-01T12:00:00Z");
            Instant end = Instant.parse("2026-01-01T12:05:00Z");
            SessionHistoryDTO dto = SessionHistoryDTO.builder()
                    .id("history-1")
                    .sessionId("sid-1")
                    .gameId("game-1")
                    .gameName("BauCua")
                    .environmentId("env-1")
                    .startedAt(start)
                    .endedAt(end)
                    .botCount(10)
                    .totalBotBet(5000L)
                    .totalPlayerBet(10000L)
                    .botRtp(95.5)
                    .totalRtp(97.2)
                    .jackpot(true)
                    .botJackpotWinnings(1000L)
                    .playerJackpotWinnings(2000L)
                    .totalWinningsSinceLastDeposit(7500L)
                    .lastDepositSessionId("sid-0")
                    .build();

            SessionHistory entity = mapper.toEntity(dto);

            assertThat(entity.getId()).isEqualTo("history-1");
            assertThat(entity.getSessionId()).isEqualTo("sid-1");
            assertThat(entity.getGameId()).isEqualTo("game-1");
            assertThat(entity.getGameName()).isEqualTo("BauCua");
            assertThat(entity.getEnvironmentId()).isEqualTo("env-1");
            assertThat(entity.getStartedAt()).isEqualTo(start);
            assertThat(entity.getEndedAt()).isEqualTo(end);
            assertThat(entity.getBotCount()).isEqualTo(10);
            assertThat(entity.getTotalBotBet()).isEqualTo(5000L);
            assertThat(entity.getTotalPlayerBet()).isEqualTo(10000L);
            assertThat(entity.getBotRtp()).isEqualTo(95.5);
            assertThat(entity.getTotalRtp()).isEqualTo(97.2);
            assertThat(entity.isJackpot()).isTrue();
            assertThat(entity.getBotJackpotWinnings()).isEqualTo(1000L);
            assertThat(entity.getPlayerJackpotWinnings()).isEqualTo(2000L);
            assertThat(entity.getTotalWinningsSinceLastDeposit()).isEqualTo(7500L);
            assertThat(entity.getLastDepositSessionId()).isEqualTo("sid-0");
        }

        @Test
        @DisplayName("Should default numeric/boolean fields when DTO has nulls")
        void shouldDefaultPrimitivesWhenNull() {
            SessionHistoryDTO dto = SessionHistoryDTO.builder().sessionId("sid").build();

            SessionHistory entity = mapper.toEntity(dto);

            assertThat(entity.getSessionId()).isEqualTo("sid");
            assertThat(entity.getBotCount()).isEqualTo(0);
            assertThat(entity.getTotalBotBet()).isEqualTo(0L);
            assertThat(entity.getTotalPlayerBet()).isEqualTo(0L);
            assertThat(entity.isJackpot()).isFalse();
            assertThat(entity.getBotJackpotWinnings()).isEqualTo(0L);
            assertThat(entity.getPlayerJackpotWinnings()).isEqualTo(0L);
            assertThat(entity.getTotalWinningsSinceLastDeposit()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should return null when DTO is null")
        void shouldReturnNullForNull() {
            assertThat(mapper.toEntity(null)).isNull();
        }
    }

    @Nested
    @DisplayName("updateEntityFromDTO")
    class UpdateEntityFromDTOTests {

        @Test
        @DisplayName("Should overwrite only fields that DTO provides, keeping the rest unchanged")
        void shouldKeepFieldsWhenDtoNull() {
            Instant start = Instant.parse("2026-01-01T12:00:00Z");
            SessionHistory entity = SessionHistory.builder()
                    .id("history-1")
                    .sessionId("sid-1")
                    .gameId("game-1")
                    .gameName("Old Game")
                    .environmentId("env-1")
                    .startedAt(start)
                    .botCount(10)
                    .totalBotBet(5000L)
                    .totalPlayerBet(10000L)
                    .botRtp(95.5)
                    .totalRtp(97.2)
                    .jackpot(true)
                    .botJackpotWinnings(1000L)
                    .playerJackpotWinnings(2000L)
                    .totalWinningsSinceLastDeposit(7500L)
                    .lastDepositSessionId("sid-0")
                    .build();

            SessionHistoryDTO dto = SessionHistoryDTO.builder().gameName("New Game").build();

            mapper.updateEntityFromDTO(dto, entity);

            assertThat(entity.getGameName()).isEqualTo("New Game");
            // Other fields untouched
            assertThat(entity.getId()).isEqualTo("history-1");
            assertThat(entity.getSessionId()).isEqualTo("sid-1");
            assertThat(entity.getGameId()).isEqualTo("game-1");
            assertThat(entity.getEnvironmentId()).isEqualTo("env-1");
            assertThat(entity.getStartedAt()).isEqualTo(start);
            assertThat(entity.getBotCount()).isEqualTo(10);
            assertThat(entity.getTotalBotBet()).isEqualTo(5000L);
            assertThat(entity.getTotalPlayerBet()).isEqualTo(10000L);
            assertThat(entity.getBotRtp()).isEqualTo(95.5);
            assertThat(entity.getTotalRtp()).isEqualTo(97.2);
            assertThat(entity.isJackpot()).isTrue();
            assertThat(entity.getBotJackpotWinnings()).isEqualTo(1000L);
            assertThat(entity.getPlayerJackpotWinnings()).isEqualTo(2000L);
            assertThat(entity.getTotalWinningsSinceLastDeposit()).isEqualTo(7500L);
            assertThat(entity.getLastDepositSessionId()).isEqualTo("sid-0");
        }

        @Test
        @DisplayName("Should be a no-op when DTO is null")
        void shouldBeNoOpWhenDtoNull() {
            SessionHistory entity = SessionHistory.builder().sessionId("sid").build();

            mapper.updateEntityFromDTO(null, entity);

            assertThat(entity.getSessionId()).isEqualTo("sid");
        }
    }
}
