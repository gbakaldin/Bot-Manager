package com.vingame.bot.domain.session.service;

import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.session.dto.SessionHistoryDTO;
import com.vingame.bot.domain.session.mapper.SessionHistoryMapper;
import com.vingame.bot.domain.session.model.SessionHistory;
import com.vingame.bot.domain.session.repository.SessionHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionHistoryService")
class SessionHistoryServiceTest {

    @Mock
    private SessionHistoryRepository repository;

    @Mock
    private SessionHistoryMapper mapper;

    @InjectMocks
    private SessionHistoryService service;

    @Nested
    @DisplayName("findById")
    class FindByIdTests {

        @Test
        @DisplayName("Should return session when found")
        void shouldReturnSessionWhenFound() {
            SessionHistory session = SessionHistory.builder().id("s-1").sessionId("sid-1").build();
            when(repository.findById("s-1")).thenReturn(Optional.of(session));

            SessionHistory result = service.findById("s-1");

            assertThat(result).isEqualTo(session);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            when(repository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById("missing"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("missing");
        }
    }

    @Nested
    @DisplayName("findBySessionId")
    class FindBySessionIdTests {

        @Test
        @DisplayName("Should return session when found")
        void shouldReturnSessionWhenFound() {
            SessionHistory session = SessionHistory.builder().id("s-1").sessionId("sid-42").build();
            when(repository.findBySessionId("sid-42")).thenReturn(Optional.of(session));

            SessionHistory result = service.findBySessionId("sid-42");

            assertThat(result).isEqualTo(session);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            when(repository.findBySessionId("sid-missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findBySessionId("sid-missing"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("sid-missing");
        }
    }

    @Nested
    @DisplayName("findAll")
    class FindAllTests {

        @Test
        @DisplayName("Should delegate to repository.findAll")
        void shouldDelegate() {
            List<SessionHistory> sessions = List.of(
                    SessionHistory.builder().id("1").build(),
                    SessionHistory.builder().id("2").build()
            );
            when(repository.findAll()).thenReturn(sessions);

            assertThat(service.findAll()).hasSize(2).isEqualTo(sessions);
            verify(repository).findAll();
        }
    }

    @Nested
    @DisplayName("findByGameId")
    class FindByGameIdTests {

        @Test
        @DisplayName("Should delegate to repository.findByGameId")
        void shouldDelegate() {
            List<SessionHistory> sessions = List.of(SessionHistory.builder().id("1").gameId("g-1").build());
            when(repository.findByGameId("g-1")).thenReturn(sessions);

            assertThat(service.findByGameId("g-1")).isEqualTo(sessions);
            verify(repository).findByGameId("g-1");
        }
    }

    @Nested
    @DisplayName("findByEnvironmentId")
    class FindByEnvironmentIdTests {

        @Test
        @DisplayName("Should delegate to repository.findByEnvironmentId")
        void shouldDelegate() {
            List<SessionHistory> sessions = List.of(SessionHistory.builder().id("1").environmentId("env-1").build());
            when(repository.findByEnvironmentId("env-1")).thenReturn(sessions);

            assertThat(service.findByEnvironmentId("env-1")).isEqualTo(sessions);
            verify(repository).findByEnvironmentId("env-1");
        }
    }

    @Nested
    @DisplayName("findByGameIdAndEnvironmentId")
    class FindByGameIdAndEnvironmentIdTests {

        @Test
        @DisplayName("Should delegate to repository.findByGameIdAndEnvironmentId")
        void shouldDelegate() {
            List<SessionHistory> sessions = List.of(
                    SessionHistory.builder().id("1").gameId("g-1").environmentId("env-1").build()
            );
            when(repository.findByGameIdAndEnvironmentId("g-1", "env-1")).thenReturn(sessions);

            assertThat(service.findByGameIdAndEnvironmentId("g-1", "env-1")).isEqualTo(sessions);
            verify(repository).findByGameIdAndEnvironmentId("g-1", "env-1");
        }
    }

    @Nested
    @DisplayName("findJackpotSessions")
    class FindJackpotSessionsTests {

        @Test
        @DisplayName("Should delegate to repository.findByJackpotTrue")
        void shouldDelegate() {
            List<SessionHistory> jackpots = List.of(SessionHistory.builder().id("1").jackpot(true).build());
            when(repository.findByJackpotTrue()).thenReturn(jackpots);

            assertThat(service.findJackpotSessions()).isEqualTo(jackpots);
            verify(repository).findByJackpotTrue();
        }
    }

    @Nested
    @DisplayName("save")
    class SaveTests {

        @Test
        @DisplayName("Should delegate to repository.save and return result")
        void shouldDelegateToRepository() {
            SessionHistory input = SessionHistory.builder().id("s-1").sessionId("sid-1").build();
            SessionHistory saved = SessionHistory.builder().id("s-1").sessionId("sid-1").build();
            when(repository.save(input)).thenReturn(saved);

            SessionHistory result = service.save(input);

            assertThat(result).isSameAs(saved);
            verify(repository).save(input);
        }

        @Test
        @DisplayName("Should not generate id (passes entity through as-is — service does not synthesize ids)")
        void shouldNotGenerateId() {
            // Production contract: SessionHistoryService.save() is a thin delegate; unlike GameService/BotGroupService
            // it does NOT auto-generate UUIDs for null/empty ids. This test pins that behavior.
            SessionHistory input = SessionHistory.builder().sessionId("sid-only").build();
            when(repository.save(input)).thenAnswer(inv -> inv.getArgument(0));

            SessionHistory result = service.save(input);

            assertThat(result.getId()).isNull();
        }
    }

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("Should map DTO onto existing entity and persist")
        void shouldUpdateAndPersist() {
            SessionHistory existing = SessionHistory.builder().id("s-1").gameName("Old").build();
            SessionHistoryDTO dto = SessionHistoryDTO.builder().gameName("New").build();

            when(repository.findById("s-1")).thenReturn(Optional.of(existing));
            when(repository.save(existing)).thenReturn(existing);

            SessionHistory result = service.update("s-1", dto);

            assertThat(result).isSameAs(existing);
            verify(mapper).updateEntityFromDTO(dto, existing);
            verify(repository).save(existing);
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when entity not found")
        void shouldThrowWhenNotFound() {
            when(repository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update("missing", SessionHistoryDTO.builder().build()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("Should call repository.deleteById")
        void shouldCallRepositoryDelete() {
            service.delete("s-1");

            verify(repository).deleteById("s-1");
        }
    }
}
