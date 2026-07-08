package com.vingame.bot.domain.botgroup.mapper;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.WeightedStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.ActivationWindow;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                    .coordinationEnabled(true)
                    .maxAggregateStakePerRound(50000L)
                    .rampEnabled(true)
                    .rampShape(3.0)
                    .affinityWeightedProposal(true)
                    .activationMode(ActivationMode.SCHEDULED)
                    .activationWindow(ActivationWindow.builder()
                            .from(LocalTime.of(18, 0))
                            .to(LocalTime.of(23, 0))
                            .days(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
                            .build())
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
            assertThat(dto.getCoordinationEnabled()).isTrue();
            assertThat(dto.getMaxAggregateStakePerRound()).isEqualTo(50000L);
            assertThat(dto.getRampEnabled()).isTrue();
            assertThat(dto.getRampShape()).isEqualTo(3.0);
            assertThat(dto.getAffinityWeightedProposal()).isTrue();
            assertThat(dto.getActivationMode()).isEqualTo(ActivationMode.SCHEDULED);
            assertThat(dto.getActivationWindow()).isEqualTo(ActivationWindow.builder()
                    .from(LocalTime.of(18, 0))
                    .to(LocalTime.of(23, 0))
                    .days(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
                    .build());
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
                    .coordinationEnabled(true)
                    .maxAggregateStakePerRound(50000L)
                    .rampEnabled(true)
                    .rampShape(3.0)
                    .affinityWeightedProposal(true)
                    .activationMode(ActivationMode.SCHEDULED)
                    .activationWindow(ActivationWindow.builder()
                            .from(LocalTime.of(22, 0))
                            .to(LocalTime.of(2, 0))
                            .days(Set.of(DayOfWeek.FRIDAY))
                            .build())
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
            assertThat(entity.isCoordinationEnabled()).isTrue();
            assertThat(entity.getMaxAggregateStakePerRound()).isEqualTo(50000L);
            assertThat(entity.isRampEnabled()).isTrue();
            assertThat(entity.getRampShape()).isEqualTo(3.0);
            assertThat(entity.isAffinityWeightedProposal()).isTrue();
            assertThat(entity.getActivationMode()).isEqualTo(ActivationMode.SCHEDULED);
            assertThat(entity.getActivationWindow()).isEqualTo(ActivationWindow.builder()
                    .from(LocalTime.of(22, 0))
                    .to(LocalTime.of(2, 0))
                    .days(Set.of(DayOfWeek.FRIDAY))
                    .build());
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
            assertThat(entity.isCoordinationEnabled()).isFalse();
            assertThat(entity.getMaxAggregateStakePerRound()).isEqualTo(0L);
            assertThat(entity.isRampEnabled()).isFalse();
            assertThat(entity.getRampShape()).isEqualTo(0.0);
            assertThat(entity.isAffinityWeightedProposal()).isFalse();
            assertThat(entity.getActivationMode()).isNull();
            assertThat(entity.getActivationWindow()).isNull();
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
                    .coordinationEnabled(true)
                    .maxAggregateStakePerRound(50000L)
                    .rampEnabled(true)
                    .rampShape(3.0)
                    .affinityWeightedProposal(true)
                    .activationMode(ActivationMode.SCHEDULED)
                    .activationWindow(ActivationWindow.builder()
                            .from(LocalTime.of(18, 0))
                            .to(LocalTime.of(23, 0))
                            .build())
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
            assertThat(entity.isCoordinationEnabled()).isTrue();
            assertThat(entity.getMaxAggregateStakePerRound()).isEqualTo(50000L);
            assertThat(entity.isRampEnabled()).isTrue();
            assertThat(entity.getRampShape()).isEqualTo(3.0);
            assertThat(entity.isAffinityWeightedProposal()).isTrue();
            assertThat(entity.getActivationMode()).isEqualTo(ActivationMode.SCHEDULED);
            assertThat(entity.getActivationWindow()).isEqualTo(ActivationWindow.builder()
                    .from(LocalTime.of(18, 0))
                    .to(LocalTime.of(23, 0))
                    .build());
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

    @Nested
    @DisplayName("strategyMix mapping (Phase 4)")
    class StrategyMixTests {

        @Test
        @DisplayName("toDTO emits strategyMix as-is when persisted on the entity")
        void toDtoEmitsStrategyMix() {
            List<WeightedStrategy> mix = List.of(new WeightedStrategy(StrategyId.RANDOM, 1.0));
            BotGroup entity = BotGroup.builder().id("g").name("g").strategyMix(mix).build();

            BotGroupDTO dto = mapper.toDTO(entity);

            assertThat(dto.getStrategyMix()).isEqualTo(mix);
        }

        @Test
        @DisplayName("toDTO returns null strategyMix when entity has none (read-side fallback lives in the service)")
        void toDtoNullStrategyMixWhenAbsent() {
            BotGroup entity = BotGroup.builder().id("g").name("g").build();

            BotGroupDTO dto = mapper.toDTO(entity);

            // Mapper does NOT synthesize the default — that's BotGroupBehavior-
            // Service.effectiveStrategyMix's job. Keeping the mapper a pure
            // shape transform makes the wire payload reflect the persisted
            // doc faithfully.
            assertThat(dto.getStrategyMix()).isNull();
        }

        @Test
        @DisplayName("toEntity persists strategyMix as-is when provided in the DTO")
        void toEntityPersistsStrategyMix() {
            List<WeightedStrategy> mix = List.of(new WeightedStrategy(StrategyId.RANDOM, 1.0));
            BotGroupDTO dto = BotGroupDTO.builder().name("g").strategyMix(mix).build();

            BotGroup entity = mapper.toEntity(dto);

            assertThat(entity.getStrategyMix()).isEqualTo(mix);
        }

        @Test
        @DisplayName("PATCH full-replaces strategyMix when DTO supplies a non-empty list")
        void patchFullReplacesStrategyMix() {
            List<WeightedStrategy> oldMix = List.of(new WeightedStrategy(StrategyId.RANDOM, 0.5));
            List<WeightedStrategy> newMix = List.of(new WeightedStrategy(StrategyId.RANDOM, 1.0));

            BotGroup entity = BotGroup.builder().id("g").name("g").strategyMix(oldMix).build();
            BotGroupDTO patch = BotGroupDTO.builder().strategyMix(newMix).build();

            mapper.updateEntityFromDTO(patch, entity);

            // Full-replace, not merge — the new list wholesale overwrites
            // the persisted one. This is the contract documented in the
            // plan's Architecture Decision 9.
            assertThat(entity.getStrategyMix()).isEqualTo(newMix);
        }

        @Test
        @DisplayName("PATCH retains existing strategyMix when DTO omits the field (null)")
        void patchKeepsExistingWhenDtoNull() {
            List<WeightedStrategy> existing = List.of(new WeightedStrategy(StrategyId.RANDOM, 1.0));
            BotGroup entity = BotGroup.builder().id("g").name("g").strategyMix(existing).build();
            BotGroupDTO patch = BotGroupDTO.builder().name("renamed").build();

            mapper.updateEntityFromDTO(patch, entity);

            assertThat(entity.getStrategyMix()).isEqualTo(existing);
        }

        @Test
        @DisplayName("PATCH with an explicit empty strategyMix throws BadRequestException (Implementation Note 10)")
        void patchEmptyStrategyMixRejected() {
            BotGroup entity = BotGroup.builder().id("g").name("g")
                    .strategyMix(List.of(new WeightedStrategy(StrategyId.RANDOM, 1.0))).build();
            BotGroupDTO patch = BotGroupDTO.builder().strategyMix(List.of()).build();

            assertThatThrownBy(() -> mapper.updateEntityFromDTO(patch, entity))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("non-empty");
        }
    }

    @Nested
    @DisplayName("slotStrategyId mapping (SLOT_MACHINE_BOT)")
    class SlotStrategyIdTests {

        @Test
        @DisplayName("toDTO emits slotStrategyId as-is when persisted on the entity")
        void toDtoEmitsSlotStrategyId() {
            BotGroup entity = BotGroup.builder().id("g").name("g")
                    .slotStrategyId(SlotStrategyId.RANDOM).build();

            BotGroupDTO dto = mapper.toDTO(entity);

            assertThat(dto.getSlotStrategyId()).isEqualTo(SlotStrategyId.RANDOM);
        }

        @Test
        @DisplayName("toDTO returns null slotStrategyId when entity has none (FIXED fallback lives in the service)")
        void toDtoNullSlotStrategyIdWhenAbsent() {
            BotGroup entity = BotGroup.builder().id("g").name("g").build();

            assertThat(mapper.toDTO(entity).getSlotStrategyId()).isNull();
        }

        @Test
        @DisplayName("toEntity persists slotStrategyId as-is when provided in the DTO")
        void toEntityPersistsSlotStrategyId() {
            BotGroupDTO dto = BotGroupDTO.builder().name("g")
                    .slotStrategyId(SlotStrategyId.RANDOM).build();

            BotGroup entity = mapper.toEntity(dto);

            assertThat(entity.getSlotStrategyId()).isEqualTo(SlotStrategyId.RANDOM);
        }

        @Test
        @DisplayName("PATCH full-replaces slotStrategyId when DTO supplies a value")
        void patchFullReplacesSlotStrategyId() {
            BotGroup entity = BotGroup.builder().id("g").name("g")
                    .slotStrategyId(SlotStrategyId.FIXED).build();
            BotGroupDTO patch = BotGroupDTO.builder().slotStrategyId(SlotStrategyId.RANDOM).build();

            mapper.updateEntityFromDTO(patch, entity);

            assertThat(entity.getSlotStrategyId()).isEqualTo(SlotStrategyId.RANDOM);
        }

        @Test
        @DisplayName("PATCH retains existing slotStrategyId when DTO omits the field (null)")
        void patchKeepsExistingWhenDtoNull() {
            BotGroup entity = BotGroup.builder().id("g").name("g")
                    .slotStrategyId(SlotStrategyId.RANDOM).build();
            BotGroupDTO patch = BotGroupDTO.builder().name("renamed").build();

            mapper.updateEntityFromDTO(patch, entity);

            assertThat(entity.getSlotStrategyId()).isEqualTo(SlotStrategyId.RANDOM);
        }
    }

    @Nested
    @DisplayName("coordination fields mapping (BET_COORDINATION Phase 1)")
    class CoordinationTests {

        @Test
        @DisplayName("PATCH full-replaces both coordination fields when the DTO supplies them")
        void patchFullReplacesCoordinationFields() {
            BotGroup entity = BotGroup.builder().id("g").name("g")
                    .coordinationEnabled(false)
                    .maxAggregateStakePerRound(10000L)
                    .build();
            BotGroupDTO patch = BotGroupDTO.builder()
                    .coordinationEnabled(true)
                    .maxAggregateStakePerRound(50000L)
                    .build();

            mapper.updateEntityFromDTO(patch, entity);

            assertThat(entity.isCoordinationEnabled()).isTrue();
            assertThat(entity.getMaxAggregateStakePerRound()).isEqualTo(50000L);
        }

        @Test
        @DisplayName("PATCH retains existing coordination fields when the DTO omits them (null)")
        void patchKeepsExistingWhenDtoNull() {
            BotGroup entity = BotGroup.builder().id("g").name("g")
                    .coordinationEnabled(true)
                    .maxAggregateStakePerRound(50000L)
                    .build();
            BotGroupDTO patch = BotGroupDTO.builder().name("renamed").build();

            mapper.updateEntityFromDTO(patch, entity);

            assertThat(entity.isCoordinationEnabled()).isTrue();
            assertThat(entity.getMaxAggregateStakePerRound()).isEqualTo(50000L);
        }
    }

    @Nested
    @DisplayName("ramp fields mapping (JACKPOT_SCALE_AND_RAMP Phase R1)")
    class RampTests {

        @Test
        @DisplayName("toDTO emits both ramp fields from the entity")
        void toDtoEmitsRampFields() {
            BotGroup entity = BotGroup.builder().id("g").name("g")
                    .rampEnabled(true)
                    .rampShape(2.5)
                    .build();

            BotGroupDTO dto = mapper.toDTO(entity);

            assertThat(dto.getRampEnabled()).isTrue();
            assertThat(dto.getRampShape()).isEqualTo(2.5);
        }

        @Test
        @DisplayName("toEntity persists both ramp fields from the DTO")
        void toEntityPersistsRampFields() {
            BotGroupDTO dto = BotGroupDTO.builder().name("g")
                    .rampEnabled(true)
                    .rampShape(2.5)
                    .build();

            BotGroup entity = mapper.toEntity(dto);

            assertThat(entity.isRampEnabled()).isTrue();
            assertThat(entity.getRampShape()).isEqualTo(2.5);
        }

        @Test
        @DisplayName("toEntity defaults ramp fields when the DTO omits them (false / 0.0)")
        void toEntityDefaultsRampFieldsWhenNull() {
            BotGroupDTO dto = BotGroupDTO.builder().name("g").build();

            BotGroup entity = mapper.toEntity(dto);

            assertThat(entity.isRampEnabled()).isFalse();
            assertThat(entity.getRampShape()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("PATCH full-replaces both ramp fields when the DTO supplies them")
        void patchFullReplacesRampFields() {
            BotGroup entity = BotGroup.builder().id("g").name("g")
                    .rampEnabled(false)
                    .rampShape(1.0)
                    .build();
            BotGroupDTO patch = BotGroupDTO.builder()
                    .rampEnabled(true)
                    .rampShape(3.0)
                    .build();

            mapper.updateEntityFromDTO(patch, entity);

            assertThat(entity.isRampEnabled()).isTrue();
            assertThat(entity.getRampShape()).isEqualTo(3.0);
        }

        @Test
        @DisplayName("PATCH retains existing ramp fields when the DTO omits them (null)")
        void patchKeepsExistingWhenDtoNull() {
            BotGroup entity = BotGroup.builder().id("g").name("g")
                    .rampEnabled(true)
                    .rampShape(3.0)
                    .build();
            BotGroupDTO patch = BotGroupDTO.builder().name("renamed").build();

            mapper.updateEntityFromDTO(patch, entity);

            assertThat(entity.isRampEnabled()).isTrue();
            assertThat(entity.getRampShape()).isEqualTo(3.0);
        }
    }

    @Nested
    @DisplayName("affinityWeightedProposal mapping (AFFINITY_AWARE_PROPOSAL Phase 2)")
    class AffinityWeightedProposalTests {

        @Test
        @DisplayName("toDTO emits the flag from the entity")
        void toDtoEmitsFlag() {
            BotGroup entity = BotGroup.builder().id("g").name("g")
                    .affinityWeightedProposal(true)
                    .build();

            BotGroupDTO dto = mapper.toDTO(entity);

            assertThat(dto.getAffinityWeightedProposal()).isTrue();
        }

        @Test
        @DisplayName("toEntity persists the flag from the DTO")
        void toEntityPersistsFlag() {
            BotGroupDTO dto = BotGroupDTO.builder().name("g")
                    .affinityWeightedProposal(true)
                    .build();

            BotGroup entity = mapper.toEntity(dto);

            assertThat(entity.isAffinityWeightedProposal()).isTrue();
        }

        @Test
        @DisplayName("toEntity defaults the flag to false when the DTO omits it")
        void toEntityDefaultsFlagWhenNull() {
            BotGroupDTO dto = BotGroupDTO.builder().name("g").build();

            BotGroup entity = mapper.toEntity(dto);

            assertThat(entity.isAffinityWeightedProposal()).isFalse();
        }

        @Test
        @DisplayName("PATCH full-replaces the flag when the DTO supplies it")
        void patchFullReplacesFlag() {
            BotGroup entity = BotGroup.builder().id("g").name("g")
                    .affinityWeightedProposal(false)
                    .build();
            BotGroupDTO patch = BotGroupDTO.builder()
                    .affinityWeightedProposal(true)
                    .build();

            mapper.updateEntityFromDTO(patch, entity);

            assertThat(entity.isAffinityWeightedProposal()).isTrue();
        }

        @Test
        @DisplayName("PATCH retains the existing flag when the DTO omits it (null)")
        void patchKeepsExistingWhenDtoNull() {
            BotGroup entity = BotGroup.builder().id("g").name("g")
                    .affinityWeightedProposal(true)
                    .build();
            BotGroupDTO patch = BotGroupDTO.builder().name("renamed").build();

            mapper.updateEntityFromDTO(patch, entity);

            assertThat(entity.isAffinityWeightedProposal()).isTrue();
        }
    }
}
