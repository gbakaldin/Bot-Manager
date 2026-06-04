package com.vingame.bot.domain.botgroup.mapper;

import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BotGroupMapper")
class BotGroupMapperTest {

    private final BotGroupMapper mapper = Mappers.getMapper(BotGroupMapper.class);

    @Nested
    @DisplayName("toDTO")
    class ToDTOTests {

        @Test
        @DisplayName("Should map all fields from entity to DTO")
        void shouldMapAllFields() {
            LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0);
            BotGroup entity = BotGroup.builder()
                    .id("id-1")
                    .name("Group 1")
                    .environmentId("env-1")
                    .namePrefix("bot")
                    .password("pass")
                    .gameId("game-1")
                    .botCount(10)
                    .minBet(100L)
                    .maxBet(1000L)
                    .betIncrement(50L)
                    .maxTotalBetPerRound(5000L)
                    .minBetsPerRound(1)
                    .maxBetsPerRound(5)
                    .timeBased(true)
                    .timeFrom(now)
                    .timeUntil(now.plusHours(1))
                    .chatEnabled(true)
                    .autoDepositEnabled(true)
                    .targetStatus(BotGroupStatus.ACTIVE)
                    .scheduledRestartTime(now.plusDays(1))
                    .lastStartedAt(now.minusHours(2))
                    .lastStoppedAt(now.minusHours(1))
                    .lastFailureReason("failure")
                    .build();

            BotGroupDTO dto = mapper.toDTO(entity);

            assertThat(dto.getId()).isEqualTo("id-1");
            assertThat(dto.getName()).isEqualTo("Group 1");
            assertThat(dto.getEnvironmentId()).isEqualTo("env-1");
            assertThat(dto.getNamePrefix()).isEqualTo("bot");
            assertThat(dto.getPassword()).isEqualTo("pass");
            assertThat(dto.getGameId()).isEqualTo("game-1");
            assertThat(dto.getBotCount()).isEqualTo(10);
            assertThat(dto.getMinBet()).isEqualTo(100L);
            assertThat(dto.getMaxBet()).isEqualTo(1000L);
            assertThat(dto.getBetIncrement()).isEqualTo(50L);
            assertThat(dto.getMaxTotalBetPerRound()).isEqualTo(5000L);
            assertThat(dto.getMinBetsPerRound()).isEqualTo(1);
            assertThat(dto.getMaxBetsPerRound()).isEqualTo(5);
            assertThat(dto.getTimeBased()).isTrue();
            assertThat(dto.getTimeFrom()).isEqualTo(now);
            assertThat(dto.getTimeUntil()).isEqualTo(now.plusHours(1));
            assertThat(dto.getChatEnabled()).isTrue();
            assertThat(dto.getAutoDepositEnabled()).isTrue();
            assertThat(dto.getTargetStatus()).isEqualTo(BotGroupStatus.ACTIVE);
            assertThat(dto.getScheduledRestartTime()).isEqualTo(now.plusDays(1));
            assertThat(dto.getLastStartedAt()).isEqualTo(now.minusHours(2));
            assertThat(dto.getLastStoppedAt()).isEqualTo(now.minusHours(1));
            assertThat(dto.getLastFailureReason()).isEqualTo("failure");
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
            LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0);
            BotGroupDTO dto = BotGroupDTO.builder()
                    .id("id-1")
                    .name("Group 1")
                    .environmentId("env-1")
                    .namePrefix("bot")
                    .password("pass")
                    .gameId("game-1")
                    .botCount(10)
                    .minBet(100L)
                    .maxBet(1000L)
                    .betIncrement(50L)
                    .maxTotalBetPerRound(5000L)
                    .minBetsPerRound(1)
                    .maxBetsPerRound(5)
                    .timeBased(true)
                    .timeFrom(now)
                    .timeUntil(now.plusHours(1))
                    .chatEnabled(true)
                    .autoDepositEnabled(true)
                    .targetStatus(BotGroupStatus.STOPPED)
                    .scheduledRestartTime(now.plusDays(1))
                    .lastStartedAt(now.minusHours(2))
                    .lastStoppedAt(now.minusHours(1))
                    .lastFailureReason("failure")
                    .build();

            BotGroup entity = mapper.toEntity(dto);

            assertThat(entity.getId()).isEqualTo("id-1");
            assertThat(entity.getName()).isEqualTo("Group 1");
            assertThat(entity.getEnvironmentId()).isEqualTo("env-1");
            assertThat(entity.getNamePrefix()).isEqualTo("bot");
            assertThat(entity.getPassword()).isEqualTo("pass");
            assertThat(entity.getGameId()).isEqualTo("game-1");
            assertThat(entity.getBotCount()).isEqualTo(10);
            assertThat(entity.getMinBet()).isEqualTo(100L);
            assertThat(entity.getMaxBet()).isEqualTo(1000L);
            assertThat(entity.getBetIncrement()).isEqualTo(50L);
            assertThat(entity.getMaxTotalBetPerRound()).isEqualTo(5000L);
            assertThat(entity.getMinBetsPerRound()).isEqualTo(1);
            assertThat(entity.getMaxBetsPerRound()).isEqualTo(5);
            assertThat(entity.isTimeBased()).isTrue();
            assertThat(entity.getTimeFrom()).isEqualTo(now);
            assertThat(entity.getTimeUntil()).isEqualTo(now.plusHours(1));
            assertThat(entity.isChatEnabled()).isTrue();
            assertThat(entity.isAutoDepositEnabled()).isTrue();
            assertThat(entity.getTargetStatus()).isEqualTo(BotGroupStatus.STOPPED);
            assertThat(entity.getScheduledRestartTime()).isEqualTo(now.plusDays(1));
            assertThat(entity.getLastStartedAt()).isEqualTo(now.minusHours(2));
            assertThat(entity.getLastStoppedAt()).isEqualTo(now.minusHours(1));
            assertThat(entity.getLastFailureReason()).isEqualTo("failure");
        }

        @Test
        @DisplayName("Should return null when DTO is null")
        void shouldReturnNullForNull() {
            assertThat(mapper.toEntity(null)).isNull();
        }

        @Test
        @DisplayName("Should default numeric/boolean fields when DTO has nulls")
        void shouldDefaultPrimitivesWhenNull() {
            BotGroupDTO dto = BotGroupDTO.builder().name("New").build();

            BotGroup entity = mapper.toEntity(dto);

            assertThat(entity.getName()).isEqualTo("New");
            assertThat(entity.getBotCount()).isEqualTo(0);
            assertThat(entity.getMinBet()).isEqualTo(0L);
            assertThat(entity.getMaxBet()).isEqualTo(0L);
            assertThat(entity.getBetIncrement()).isEqualTo(0L);
            assertThat(entity.getMaxTotalBetPerRound()).isEqualTo(0L);
            assertThat(entity.getMinBetsPerRound()).isEqualTo(0);
            assertThat(entity.getMaxBetsPerRound()).isEqualTo(0);
            assertThat(entity.isTimeBased()).isFalse();
            assertThat(entity.isChatEnabled()).isFalse();
            assertThat(entity.isAutoDepositEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("updateEntityFromDTO")
    class UpdateEntityFromDTOTests {

        @Test
        @DisplayName("Should overwrite only fields that DTO provides, keeping the rest unchanged")
        void shouldKeepFieldsWhenDtoNull() {
            LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0);
            BotGroup entity = BotGroup.builder()
                    .id("id-1")
                    .name("Old")
                    .environmentId("env-1")
                    .namePrefix("bot")
                    .password("pass")
                    .gameId("game-1")
                    .botCount(10)
                    .minBet(100L)
                    .maxBet(1000L)
                    .betIncrement(50L)
                    .maxTotalBetPerRound(5000L)
                    .minBetsPerRound(1)
                    .maxBetsPerRound(5)
                    .timeBased(true)
                    .timeFrom(now)
                    .chatEnabled(true)
                    .autoDepositEnabled(true)
                    .targetStatus(BotGroupStatus.ACTIVE)
                    .build();

            BotGroupDTO dto = BotGroupDTO.builder().name("New").build();

            mapper.updateEntityFromDTO(dto, entity);

            assertThat(entity.getName()).isEqualTo("New");
            // All other fields untouched
            assertThat(entity.getId()).isEqualTo("id-1");
            assertThat(entity.getEnvironmentId()).isEqualTo("env-1");
            assertThat(entity.getNamePrefix()).isEqualTo("bot");
            assertThat(entity.getPassword()).isEqualTo("pass");
            assertThat(entity.getGameId()).isEqualTo("game-1");
            assertThat(entity.getBotCount()).isEqualTo(10);
            assertThat(entity.getMinBet()).isEqualTo(100L);
            assertThat(entity.getMaxBet()).isEqualTo(1000L);
            assertThat(entity.getBetIncrement()).isEqualTo(50L);
            assertThat(entity.getMaxTotalBetPerRound()).isEqualTo(5000L);
            assertThat(entity.getMinBetsPerRound()).isEqualTo(1);
            assertThat(entity.getMaxBetsPerRound()).isEqualTo(5);
            assertThat(entity.isTimeBased()).isTrue();
            assertThat(entity.getTimeFrom()).isEqualTo(now);
            assertThat(entity.isChatEnabled()).isTrue();
            assertThat(entity.isAutoDepositEnabled()).isTrue();
            assertThat(entity.getTargetStatus()).isEqualTo(BotGroupStatus.ACTIVE);
        }

        @Test
        @DisplayName("Should be a no-op when DTO is null")
        void shouldBeNoOpWhenDtoNull() {
            BotGroup entity = BotGroup.builder().id("id").name("Old").build();

            mapper.updateEntityFromDTO(null, entity);

            assertThat(entity.getName()).isEqualTo("Old");
        }
    }
}
