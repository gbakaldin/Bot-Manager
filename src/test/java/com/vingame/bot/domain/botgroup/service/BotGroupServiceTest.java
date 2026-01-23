package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.config.client.EnvironmentClientRegistry;
import com.vingame.bot.config.client.EnvironmentClients;
import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapper;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupFilter;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.infrastructure.client.ApiGatewayClient;
import com.vingame.bot.infrastructure.client.dto.UserRegistrationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BotGroupService")
class BotGroupServiceTest {

    @Mock
    private BotGroupRepository repository;

    @Mock
    private BotGroupMapper mapper;

    @Mock
    private EnvironmentClientRegistry clientRegistry;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private EnvironmentClients environmentClients;

    @Mock
    private ApiGatewayClient apiGatewayClient;

    @Captor
    private ArgumentCaptor<Query> queryCaptor;

    @InjectMocks
    private BotGroupService service;

    @Nested
    @DisplayName("findById")
    class FindByIdTests {

        @Test
        @DisplayName("Should return bot group when found")
        void shouldReturnBotGroupWhenFound() {
            BotGroup group = BotGroup.builder().id("123").name("Test").build();
            when(repository.findById("123")).thenReturn(Optional.of(group));

            BotGroup result = service.findById("123");

            assertThat(result).isEqualTo(group);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            when(repository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById("missing"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("findAll")
    class FindAllTests {

        @Test
        @DisplayName("Should return all bot groups")
        void shouldReturnAll() {
            List<BotGroup> groups = List.of(
                    BotGroup.builder().id("1").build(),
                    BotGroup.builder().id("2").build()
            );
            when(repository.findAll()).thenReturn(groups);

            assertThat(service.findAll()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("findByTargetStatus")
    class FindByTargetStatusTests {

        @Test
        @DisplayName("Should delegate to repository")
        void shouldDelegateToRepository() {
            List<BotGroup> active = List.of(BotGroup.builder().id("1").targetStatus(BotGroupStatus.ACTIVE).build());
            when(repository.findByTargetStatus(BotGroupStatus.ACTIVE)).thenReturn(active);

            List<BotGroup> result = service.findByTargetStatus(BotGroupStatus.ACTIVE);

            assertThat(result).hasSize(1);
            verify(repository).findByTargetStatus(BotGroupStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("filter")
    class FilterTests {

        @Test
        @DisplayName("Should filter by environment ID")
        void shouldFilterByEnvironmentId() {
            List<BotGroup> expected = List.of(BotGroup.builder().id("1").environmentId("env-a").build());
            when(mongoTemplate.find(any(Query.class), eq(BotGroup.class))).thenReturn(expected);

            BotGroupFilter filter = new BotGroupFilter();
            filter.setEnvironmentId("env-a");

            List<BotGroup> result = service.filter(filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(BotGroup.class));
            String queryString = queryCaptor.getValue().toString();
            assertThat(queryString).contains("environmentId");
            assertThat(queryString).contains("env-a");
        }

        @Test
        @DisplayName("Should filter by name (case-insensitive)")
        void shouldFilterByName() {
            List<BotGroup> expected = List.of(BotGroup.builder().id("2").name("Test Group").build());
            when(mongoTemplate.find(any(Query.class), eq(BotGroup.class))).thenReturn(expected);

            BotGroupFilter filter = new BotGroupFilter();
            filter.setName("test group");

            List<BotGroup> result = service.filter(filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(BotGroup.class));
            String queryString = queryCaptor.getValue().toString();
            assertThat(queryString).contains("name");
        }

        @Test
        @DisplayName("Should filter by game ID")
        void shouldFilterByGameId() {
            List<BotGroup> expected = List.of(BotGroup.builder().id("1").gameId("game-1").build());
            when(mongoTemplate.find(any(Query.class), eq(BotGroup.class))).thenReturn(expected);

            BotGroupFilter filter = new BotGroupFilter();
            filter.setGameId("game-1");

            List<BotGroup> result = service.filter(filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(BotGroup.class));
            String queryString = queryCaptor.getValue().toString();
            assertThat(queryString).contains("gameId");
            assertThat(queryString).contains("game-1");
        }

        @Test
        @DisplayName("Should query with no criteria when filter is empty")
        void shouldReturnAllWhenNoCriteria() {
            List<BotGroup> all = List.of(
                    BotGroup.builder().id("1").build(),
                    BotGroup.builder().id("2").build()
            );
            when(mongoTemplate.find(any(Query.class), eq(BotGroup.class))).thenReturn(all);

            assertThat(service.filter(new BotGroupFilter())).hasSize(2);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(BotGroup.class));
            // Empty query should have no criteria
            assertThat(queryCaptor.getValue().getQueryObject()).isEmpty();
        }
    }

    @Nested
    @DisplayName("save - new group")
    class SaveNewGroupTests {

        @Test
        @DisplayName("Should register users and generate ID for new group")
        void shouldRegisterUsersAndGenerateId() {
            BotGroup group = BotGroup.builder()
                    .name("New Group")
                    .environmentId("env-1")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(5)
                    .build();

            UserRegistrationResult successResult = UserRegistrationResult.builder()
                    .totalRequested(5)
                    .successCount(5)
                    .failureCount(0)
                    .build();

            when(clientRegistry.getClients("env-1")).thenReturn(environmentClients);
            when(environmentClients.getApiGatewayClient()).thenReturn(apiGatewayClient);
            when(apiGatewayClient.registerUsers("bot", "pass", 5)).thenReturn(successResult);
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            BotGroup result = service.save(group);

            assertThat(result.getId()).isNotNull().isNotEmpty();
            verify(apiGatewayClient).registerUsers("bot", "pass", 5);
            verify(repository).save(group);
        }

        @Test
        @DisplayName("Should throw when user registration completely fails")
        void shouldThrowWhenRegistrationFails() {
            BotGroup group = BotGroup.builder()
                    .name("Failing Group")
                    .environmentId("env-1")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(5)
                    .build();

            UserRegistrationResult failResult = UserRegistrationResult.builder()
                    .totalRequested(5)
                    .successCount(0)
                    .failureCount(5)
                    .errors(List.of("Connection refused"))
                    .build();

            when(clientRegistry.getClients("env-1")).thenReturn(environmentClients);
            when(environmentClients.getApiGatewayClient()).thenReturn(apiGatewayClient);
            when(apiGatewayClient.registerUsers("bot", "pass", 5)).thenReturn(failResult);

            assertThatThrownBy(() -> service.save(group))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to register any users");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Should still save when registration is partial success")
        void shouldSaveOnPartialSuccess() {
            BotGroup group = BotGroup.builder()
                    .name("Partial Group")
                    .environmentId("env-1")
                    .namePrefix("bot")
                    .password("pass")
                    .botCount(5)
                    .build();

            UserRegistrationResult partialResult = UserRegistrationResult.builder()
                    .totalRequested(5)
                    .successCount(3)
                    .failureCount(2)
                    .errors(List.of("User already exists"))
                    .build();

            when(clientRegistry.getClients("env-1")).thenReturn(environmentClients);
            when(environmentClients.getApiGatewayClient()).thenReturn(apiGatewayClient);
            when(apiGatewayClient.registerUsers("bot", "pass", 5)).thenReturn(partialResult);
            when(repository.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            BotGroup result = service.save(group);

            assertThat(result.getId()).isNotNull();
            verify(repository).save(group);
        }
    }

    @Nested
    @DisplayName("save - existing group")
    class SaveExistingGroupTests {

        @Test
        @DisplayName("Should skip user registration for existing group")
        void shouldSkipRegistrationForExistingGroup() {
            BotGroup group = BotGroup.builder()
                    .id("existing-id")
                    .name("Existing Group")
                    .environmentId("env-1")
                    .build();

            when(repository.save(group)).thenReturn(group);

            BotGroup result = service.save(group);

            assertThat(result.getId()).isEqualTo("existing-id");
            verify(clientRegistry, never()).getClients(anyString());
            verify(repository).save(group);
        }
    }

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("Should update and persist entity")
        void shouldUpdateAndPersist() {
            BotGroup existing = BotGroup.builder().id("123").name("Old").build();
            BotGroupDTO dto = BotGroupDTO.builder().name("New").build();

            when(repository.findById("123")).thenReturn(Optional.of(existing));
            when(repository.save(existing)).thenReturn(existing);

            service.update("123", dto);

            verify(mapper).updateEntityFromDTO(dto, existing);
            verify(repository).save(existing);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            when(repository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update("missing", BotGroupDTO.builder().build()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("Should call repository.deleteById")
        void shouldCallRepositoryDelete() {
            service.delete("123");

            verify(repository).deleteById("123");
        }
    }
}
