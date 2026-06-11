package com.vingame.bot.domain.session.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.common.exception.RestExceptionHandler;
import com.vingame.bot.domain.session.dto.SessionHistoryDTO;
import com.vingame.bot.domain.session.mapper.SessionHistoryMapper;
import com.vingame.bot.domain.session.model.SessionHistory;
import com.vingame.bot.domain.session.service.SessionHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SessionHistoryController.class)
@Import(RestExceptionHandler.class)
@DisplayName("SessionHistoryController")
class SessionHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SessionHistoryService service;

    @MockitoBean
    private SessionHistoryMapper mapper;

    @Nested
    @DisplayName("GET /api/v1/session-history/{id}")
    class GetByIdTests {

        @Test
        @DisplayName("Should return 200 OK when session history exists")
        void shouldReturnOkWhenSessionHistoryExists() throws Exception {
            // Arrange
            String id = "sess-1";
            SessionHistory entity = SessionHistory.builder()
                    .id(id)
                    .sessionId("422069")
                    .gameId("game-1")
                    .gameName("BauCua")
                    .environmentId("env-1")
                    .botCount(10)
                    .totalBotBet(5000L)
                    .build();

            SessionHistoryDTO dto = SessionHistoryDTO.builder()
                    .id(id)
                    .sessionId("422069")
                    .gameId("game-1")
                    .gameName("BauCua")
                    .environmentId("env-1")
                    .botCount(10)
                    .totalBotBet(5000L)
                    .build();

            when(service.findById(id)).thenReturn(entity);
            when(mapper.toDTO(entity)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(get("/api/v1/session-history/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.sessionId").value("422069"))
                    .andExpect(jsonPath("$.gameId").value("game-1"))
                    .andExpect(jsonPath("$.gameName").value("BauCua"))
                    .andExpect(jsonPath("$.environmentId").value("env-1"))
                    .andExpect(jsonPath("$.botCount").value(10))
                    .andExpect(jsonPath("$.totalBotBet").value(5000));
        }

        @Test
        @DisplayName("Should return 404 Not Found when session history does not exist")
        void shouldReturnNotFoundWhenSessionHistoryDoesNotExist() throws Exception {
            // Arrange
            String id = "missing";
            when(service.findById(id)).thenThrow(new ResourceNotFoundException("Not found"));

            // Act & Assert
            mockMvc.perform(get("/api/v1/session-history/{id}", id))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/session-history/by-session/{sessionId}")
    class FindBySessionIdTests {

        @Test
        @DisplayName("Should return 200 OK when session history exists for session ID")
        void shouldReturnOkWhenSessionHistoryExists() throws Exception {
            // Arrange
            String sessionId = "422070";
            SessionHistory entity = SessionHistory.builder()
                    .id("sess-2")
                    .sessionId(sessionId)
                    .gameId("game-1")
                    .startedAt(Instant.parse("2026-01-01T00:00:00Z"))
                    .build();

            SessionHistoryDTO dto = SessionHistoryDTO.builder()
                    .id("sess-2")
                    .sessionId(sessionId)
                    .gameId("game-1")
                    .startedAt(Instant.parse("2026-01-01T00:00:00Z"))
                    .build();

            when(service.findBySessionId(sessionId)).thenReturn(entity);
            when(mapper.toDTO(entity)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(get("/api/v1/session-history/by-session/{sessionId}", sessionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("sess-2"))
                    .andExpect(jsonPath("$.sessionId").value(sessionId))
                    .andExpect(jsonPath("$.gameId").value("game-1"));
        }

        @Test
        @DisplayName("Should return 404 Not Found when session ID has no history")
        void shouldReturnNotFoundWhenSessionHistoryDoesNotExist() throws Exception {
            // Arrange
            String sessionId = "missing-session";
            when(service.findBySessionId(sessionId))
                    .thenThrow(new ResourceNotFoundException("Not found"));

            // Act & Assert
            mockMvc.perform(get("/api/v1/session-history/by-session/{sessionId}", sessionId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/session-history/")
    class FindAllTests {

        @Test
        @DisplayName("Should call service.findAll when no query params are provided")
        void shouldCallFindAllWhenNoQueryParams() throws Exception {
            // Arrange
            SessionHistory entity = SessionHistory.builder().id("a").sessionId("1").build();
            SessionHistoryDTO dto = SessionHistoryDTO.builder().id("a").sessionId("1").build();

            when(service.findAll()).thenReturn(List.of(entity));
            when(mapper.toDTO(entity)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(get("/api/v1/session-history/"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value("a"));

            verify(service).findAll();
            verify(service, never()).findByGameId(org.mockito.ArgumentMatchers.anyString());
            verify(service, never()).findByEnvironmentId(org.mockito.ArgumentMatchers.anyString());
            verify(service, never()).findByGameIdAndEnvironmentId(
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("Should call service.findByGameId when only gameId query param is provided")
        void shouldCallFindByGameIdWhenOnlyGameIdProvided() throws Exception {
            // Arrange
            String gameId = "game-baucua";
            SessionHistory entity = SessionHistory.builder().id("b").gameId(gameId).build();
            SessionHistoryDTO dto = SessionHistoryDTO.builder().id("b").gameId(gameId).build();

            when(service.findByGameId(gameId)).thenReturn(List.of(entity));
            when(mapper.toDTO(entity)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(get("/api/v1/session-history/").param("gameId", gameId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].gameId").value(gameId));

            verify(service).findByGameId(gameId);
            verify(service, never()).findAll();
            verify(service, never()).findByEnvironmentId(org.mockito.ArgumentMatchers.anyString());
            verify(service, never()).findByGameIdAndEnvironmentId(
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("Should call service.findByEnvironmentId when only environmentId query param is provided")
        void shouldCallFindByEnvironmentIdWhenOnlyEnvironmentIdProvided() throws Exception {
            // Arrange
            String envId = "env-prod";
            SessionHistory entity = SessionHistory.builder().id("c").environmentId(envId).build();
            SessionHistoryDTO dto = SessionHistoryDTO.builder().id("c").environmentId(envId).build();

            when(service.findByEnvironmentId(envId)).thenReturn(List.of(entity));
            when(mapper.toDTO(entity)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(get("/api/v1/session-history/").param("environmentId", envId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].environmentId").value(envId));

            verify(service).findByEnvironmentId(envId);
            verify(service, never()).findAll();
            verify(service, never()).findByGameId(org.mockito.ArgumentMatchers.anyString());
            verify(service, never()).findByGameIdAndEnvironmentId(
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("Should call service.findByGameIdAndEnvironmentId when both query params are provided")
        void shouldCallFindByGameIdAndEnvironmentIdWhenBothProvided() throws Exception {
            // Arrange
            String gameId = "game-baucua";
            String envId = "env-prod";
            SessionHistory entity = SessionHistory.builder()
                    .id("d")
                    .gameId(gameId)
                    .environmentId(envId)
                    .build();
            SessionHistoryDTO dto = SessionHistoryDTO.builder()
                    .id("d")
                    .gameId(gameId)
                    .environmentId(envId)
                    .build();

            when(service.findByGameIdAndEnvironmentId(gameId, envId)).thenReturn(List.of(entity));
            when(mapper.toDTO(entity)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(get("/api/v1/session-history/")
                            .param("gameId", gameId)
                            .param("environmentId", envId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].gameId").value(gameId))
                    .andExpect(jsonPath("$[0].environmentId").value(envId));

            verify(service).findByGameIdAndEnvironmentId(gameId, envId);
            verify(service, never()).findAll();
            verify(service, never()).findByGameId(org.mockito.ArgumentMatchers.anyString());
            verify(service, never()).findByEnvironmentId(org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/session-history/jackpots")
    class FindJackpotSessionsTests {

        @Test
        @DisplayName("Should return 200 OK with jackpot sessions")
        void shouldReturnOkWithJackpotSessions() throws Exception {
            // Arrange
            SessionHistory entity = SessionHistory.builder()
                    .id("jp-1")
                    .sessionId("422071")
                    .jackpot(true)
                    .botJackpotWinnings(100_000L)
                    .build();

            SessionHistoryDTO dto = SessionHistoryDTO.builder()
                    .id("jp-1")
                    .sessionId("422071")
                    .jackpot(true)
                    .botJackpotWinnings(100_000L)
                    .build();

            when(service.findJackpotSessions()).thenReturn(List.of(entity));
            when(mapper.toDTO(entity)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(get("/api/v1/session-history/jackpots"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value("jp-1"))
                    .andExpect(jsonPath("$[0].jackpot").value(true))
                    .andExpect(jsonPath("$[0].botJackpotWinnings").value(100_000));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/session-history/{id}")
    class DeleteTests {

        @Test
        @DisplayName("Should return 200 OK when delete succeeds")
        void shouldReturnOkWhenDeleteSucceeds() throws Exception {
            // Arrange
            String id = "sess-to-delete";
            doNothing().when(service).delete(id);

            // Act & Assert
            mockMvc.perform(delete("/api/v1/session-history/{id}", id))
                    .andExpect(status().isOk());
            verify(service).delete(id);
        }
    }
}
