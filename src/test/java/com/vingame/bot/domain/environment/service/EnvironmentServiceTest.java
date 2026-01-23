package com.vingame.bot.domain.environment.service;

import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.environment.dto.EnvironmentDTO;
import com.vingame.bot.domain.environment.mapper.EnvironmentMapper;
import com.vingame.bot.domain.environment.model.BrandCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.model.EnvironmentFilter;
import com.vingame.bot.domain.environment.model.EnvironmentType;
import com.vingame.bot.domain.environment.repository.EnvironmentRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EnvironmentService")
class EnvironmentServiceTest {

    @Mock
    private EnvironmentRepository repository;

    @Mock
    private EnvironmentMapper mapper;

    @Mock
    private MongoTemplate mongoTemplate;

    @Captor
    private ArgumentCaptor<Query> queryCaptor;

    @InjectMocks
    private EnvironmentService service;

    @Nested
    @DisplayName("findById")
    class FindByIdTests {

        @Test
        @DisplayName("Should return environment when found")
        void shouldReturnEnvironmentWhenFound() {
            Environment env = Environment.builder().id("env-1").name("Test").build();
            when(repository.findById("env-1")).thenReturn(Optional.of(env));

            Environment result = service.findById("env-1");

            assertThat(result).isEqualTo(env);
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
        @DisplayName("Should return all environments")
        void shouldReturnAllEnvironments() {
            List<Environment> envs = List.of(
                    Environment.builder().id("1").name("Env 1").build(),
                    Environment.builder().id("2").name("Env 2").build()
            );
            when(repository.findAll()).thenReturn(envs);

            List<Environment> result = service.findAll();

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("Should return empty list when no environments exist")
        void shouldReturnEmptyList() {
            when(repository.findAll()).thenReturn(List.of());

            List<Environment> result = service.findAll();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("filter")
    class FilterTests {

        @Test
        @DisplayName("Should filter by type")
        void shouldFilterByType() {
            List<Environment> expected = List.of(
                    Environment.builder().id("1").type(EnvironmentType.STAGING).build()
            );
            when(mongoTemplate.find(any(Query.class), eq(Environment.class))).thenReturn(expected);

            EnvironmentFilter filter = new EnvironmentFilter();
            filter.setType(EnvironmentType.STAGING);

            List<Environment> result = service.filter(filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Environment.class));
            String queryString = queryCaptor.getValue().toString();
            assertThat(queryString).contains("type");
        }

        @Test
        @DisplayName("Should filter by brand code")
        void shouldFilterByBrandCode() {
            List<Environment> expected = List.of(
                    Environment.builder().id("1").brandCode(BrandCode.G2).build()
            );
            when(mongoTemplate.find(any(Query.class), eq(Environment.class))).thenReturn(expected);

            EnvironmentFilter filter = new EnvironmentFilter();
            filter.setBrandCode(BrandCode.G2);

            List<Environment> result = service.filter(filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Environment.class));
            String queryString = queryCaptor.getValue().toString();
            assertThat(queryString).contains("brandCode");
        }

        @Test
        @DisplayName("Should filter by name (case-insensitive)")
        void shouldFilterByNameCaseInsensitive() {
            List<Environment> expected = List.of(
                    Environment.builder().id("1").name("Staging Env").build()
            );
            when(mongoTemplate.find(any(Query.class), eq(Environment.class))).thenReturn(expected);

            EnvironmentFilter filter = new EnvironmentFilter();
            filter.setName("staging env");

            List<Environment> result = service.filter(filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Environment.class));
            String queryString = queryCaptor.getValue().toString();
            assertThat(queryString).contains("name");
        }

        @Test
        @DisplayName("Should query with no criteria when filter is empty")
        void shouldReturnAllWhenNoCriteria() {
            List<Environment> all = List.of(
                    Environment.builder().id("1").build(),
                    Environment.builder().id("2").build()
            );
            when(mongoTemplate.find(any(Query.class), eq(Environment.class))).thenReturn(all);

            List<Environment> result = service.filter(new EnvironmentFilter());

            assertThat(result).hasSize(2);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Environment.class));
            assertThat(queryCaptor.getValue().getQueryObject()).isEmpty();
        }

        @Test
        @DisplayName("Should apply multiple filter criteria together")
        void shouldApplyMultipleCriteria() {
            List<Environment> expected = List.of(
                    Environment.builder().id("1").type(EnvironmentType.STAGING).brandCode(BrandCode.G2).build()
            );
            when(mongoTemplate.find(any(Query.class), eq(Environment.class))).thenReturn(expected);

            EnvironmentFilter filter = new EnvironmentFilter();
            filter.setType(EnvironmentType.STAGING);
            filter.setBrandCode(BrandCode.G2);

            List<Environment> result = service.filter(filter);

            assertThat(result).hasSize(1);
            verify(mongoTemplate).find(queryCaptor.capture(), eq(Environment.class));
            String queryString = queryCaptor.getValue().toString();
            assertThat(queryString).contains("type");
            assertThat(queryString).contains("brandCode");
        }
    }

    @Nested
    @DisplayName("save")
    class SaveTests {

        @Test
        @DisplayName("Should generate UUID when ID is null")
        void shouldGenerateIdWhenNull() {
            Environment env = Environment.builder().name("New Env").build();
            when(repository.save(any(Environment.class))).thenAnswer(inv -> inv.getArgument(0));

            Environment result = service.save(env);

            assertThat(result.getId()).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("Should generate UUID when ID is empty")
        void shouldGenerateIdWhenEmpty() {
            Environment env = Environment.builder().id("").name("New Env").build();
            when(repository.save(any(Environment.class))).thenAnswer(inv -> inv.getArgument(0));

            Environment result = service.save(env);

            assertThat(result.getId()).isNotEmpty();
        }

        @Test
        @DisplayName("Should keep existing ID when already set")
        void shouldKeepExistingId() {
            Environment env = Environment.builder().id("existing-id").name("Existing Env").build();
            when(repository.save(env)).thenReturn(env);

            Environment result = service.save(env);

            assertThat(result.getId()).isEqualTo("existing-id");
        }

        @Test
        @DisplayName("Should call repository.save")
        void shouldCallRepositorySave() {
            Environment env = Environment.builder().id("id").build();
            when(repository.save(env)).thenReturn(env);

            service.save(env);

            verify(repository).save(env);
        }
    }

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("Should update and persist entity")
        void shouldUpdateAndPersist() {
            Environment existing = Environment.builder().id("env-1").name("Old Name").build();
            EnvironmentDTO dto = EnvironmentDTO.builder().name("New Name").build();

            when(repository.findById("env-1")).thenReturn(Optional.of(existing));
            when(repository.save(existing)).thenReturn(existing);

            Environment result = service.update("env-1", dto);

            verify(mapper).updateEntityFromDTO(dto, existing);
            verify(repository).save(existing);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            when(repository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update("missing", EnvironmentDTO.builder().build()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("Should call repository.deleteById")
        void shouldCallRepositoryDelete() {
            service.delete("env-1");

            verify(repository).deleteById("env-1");
        }
    }
}
