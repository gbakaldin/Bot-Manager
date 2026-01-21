package com.vingame.bot.domain.botgroup.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.dto.BotGroupStatusDTO;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapper;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupFilter;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.model.GameType;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.BotGroupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BotGroupController.class)
@DisplayName("BotGroupController")
class BotGroupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BotGroupService service;

    @MockitoBean
    private BotGroupBehaviorService behaviorService;

    @MockitoBean
    private BotGroupMapper mapper;

    @Nested
    @DisplayName("GET /api/v1/bot-group/{id}")
    class GetByIdTests {

        @Test
        @DisplayName("Should return 200 OK when bot group exists")
        void shouldReturnOkWhenBotGroupExists() throws Exception {
            // Arrange
            int groupId = 123;
            BotGroup botGroup = BotGroup.builder()
                    .id(String.valueOf(groupId))
                    .name("Test Bot Group")
                    .namePrefix("bot")
                    .password("password123")
                    .gameType(GameType.BAU_CUA)
                    .botCount(10)
                    .environmentId("env-1")
                    .build();

            BotGroupDTO dto = BotGroupDTO.builder()
                    .id(String.valueOf(groupId))
                    .name("Test Bot Group")
                    .namePrefix("bot")
                    .gameType(GameType.BAU_CUA)
                    .botCount(10)
                    .environmentId("env-1")
                    .build();

            when(service.findById(groupId)).thenReturn(botGroup);
            when(mapper.toDTO(botGroup)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/{id}", groupId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(String.valueOf(groupId)))
                    .andExpect(jsonPath("$.name").value("Test Bot Group"))
                    .andExpect(jsonPath("$.gameType").value("BAU_CUA"))
                    .andExpect(jsonPath("$.botCount").value(10))
                    .andExpect(jsonPath("$.environmentId").value("env-1"));
        }

        @Test
        @DisplayName("Should return 404 Not Found when bot group does not exist")
        void shouldReturnNotFoundWhenBotGroupDoesNotExist() throws Exception {
            // Arrange
            int groupId = 999;
            when(service.findById(groupId)).thenThrow(new ResourceNotFoundException("Bot group not found"));

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/{id}", groupId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when ID is invalid")
        void shouldReturnBadRequestWhenIdIsInvalid() throws Exception {
            // Arrange
            int groupId = -1;
            when(service.findById(groupId)).thenThrow(new IllegalArgumentException("Invalid ID"));

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/{id}", groupId))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/bot-group/")
    class GetAllTests {

        @Test
        @DisplayName("Should return 200 OK with list of bot groups")
        void shouldReturnOkWithListOfBotGroups() throws Exception {
            // Arrange
            BotGroup group1 = BotGroup.builder()
                    .id("1")
                    .name("Group 1")
                    .gameType(GameType.BAU_CUA)
                    .build();

            BotGroup group2 = BotGroup.builder()
                    .id("2")
                    .name("Group 2")
                    .gameType(GameType.BAU_CUA_MINI)
                    .build();

            BotGroupDTO dto1 = BotGroupDTO.builder()
                    .id("1")
                    .name("Group 1")
                    .gameType(GameType.BAU_CUA)
                    .build();

            BotGroupDTO dto2 = BotGroupDTO.builder()
                    .id("2")
                    .name("Group 2")
                    .gameType(GameType.BAU_CUA_MINI)
                    .build();

            when(service.findAll()).thenReturn(List.of(group1, group2));
            when(mapper.toDTO(group1)).thenReturn(dto1);
            when(mapper.toDTO(group2)).thenReturn(dto2);

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].id").value("1"))
                    .andExpect(jsonPath("$[1].id").value("2"));
        }

        @Test
        @DisplayName("Should return 200 OK with empty list when no bot groups exist")
        void shouldReturnOkWithEmptyListWhenNoBotGroupsExist() throws Exception {
            // Arrange
            when(service.findAll()).thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bot-group/filter/")
    class FilterTests {

        @Test
        @DisplayName("Should return 200 OK with filtered bot groups by game type")
        void shouldReturnOkWithFilteredBotGroupsByGameType() throws Exception {
            // Arrange
            BotGroupFilter filter = new BotGroupFilter();
            filter.setGameType(GameType.BAU_CUA);

            BotGroup group = BotGroup.builder()
                    .id("1")
                    .name("Filtered Group")
                    .gameType(GameType.BAU_CUA)
                    .build();

            BotGroupDTO dto = BotGroupDTO.builder()
                    .id("1")
                    .name("Filtered Group")
                    .gameType(GameType.BAU_CUA)
                    .build();

            when(service.filter(any(BotGroupFilter.class))).thenReturn(List.of(group));
            when(mapper.toDTO(group)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/filter/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(filter)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("Filtered Group"))
                    .andExpect(jsonPath("$[0].gameType").value("BAU_CUA"));
        }

        @Test
        @DisplayName("Should return 200 OK with filtered bot groups by environment ID")
        void shouldReturnOkWithFilteredBotGroupsByEnvironmentId() throws Exception {
            // Arrange
            BotGroupFilter filter = new BotGroupFilter();
            filter.setEnvironmentId("env-123");

            BotGroup group = BotGroup.builder()
                    .id("1")
                    .name("Environment Group")
                    .environmentId("env-123")
                    .gameType(GameType.BAU_CUA)
                    .build();

            BotGroupDTO dto = BotGroupDTO.builder()
                    .id("1")
                    .name("Environment Group")
                    .environmentId("env-123")
                    .gameType(GameType.BAU_CUA)
                    .build();

            when(service.filter(any(BotGroupFilter.class))).thenReturn(List.of(group));
            when(mapper.toDTO(group)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/filter/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(filter)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].environmentId").value("env-123"));
        }

        @Test
        @DisplayName("Should return 200 OK with empty list when no matches")
        void shouldReturnOkWithEmptyListWhenNoMatches() throws Exception {
            // Arrange
            BotGroupFilter filter = new BotGroupFilter();
            filter.setName("NonExistentGroup");

            when(service.filter(any(BotGroupFilter.class))).thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/filter/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(filter)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bot-group/")
    class CreateTests {

        @Test
        @DisplayName("Should return 200 OK with created bot group")
        void shouldReturnOkWithCreatedBotGroup() throws Exception {
            // Arrange
            BotGroupDTO inputDto = BotGroupDTO.builder()
                    .name("New Bot Group")
                    .namePrefix("testbot")
                    .password("secret")
                    .gameType(GameType.BAU_CUA)
                    .botCount(5)
                    .environmentId("env-1")
                    .build();

            BotGroup entity = BotGroup.builder()
                    .name("New Bot Group")
                    .namePrefix("testbot")
                    .password("secret")
                    .gameType(GameType.BAU_CUA)
                    .botCount(5)
                    .environmentId("env-1")
                    .build();

            BotGroup savedEntity = BotGroup.builder()
                    .id("123")
                    .name("New Bot Group")
                    .namePrefix("testbot")
                    .password("secret")
                    .gameType(GameType.BAU_CUA)
                    .botCount(5)
                    .environmentId("env-1")
                    .build();

            BotGroupDTO outputDto = BotGroupDTO.builder()
                    .id("123")
                    .name("New Bot Group")
                    .namePrefix("testbot")
                    .gameType(GameType.BAU_CUA)
                    .botCount(5)
                    .environmentId("env-1")
                    .build();

            when(mapper.toEntity(any(BotGroupDTO.class))).thenReturn(entity);
            when(service.save(entity)).thenReturn(savedEntity);
            when(mapper.toDTO(savedEntity)).thenReturn(outputDto);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(inputDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("123"))
                    .andExpect(jsonPath("$.name").value("New Bot Group"))
                    .andExpect(jsonPath("$.gameType").value("BAU_CUA"))
                    .andExpect(jsonPath("$.botCount").value(5));
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/bot-group/{id}")
    class UpdateTests {

        @Test
        @DisplayName("Should return 200 OK with updated bot group")
        void shouldReturnOkWithUpdatedBotGroup() throws Exception {
            // Arrange
            int groupId = 123;
            BotGroupDTO updateDto = BotGroupDTO.builder()
                    .name("Updated Name")
                    .botCount(20)
                    .build();

            BotGroup updatedEntity = BotGroup.builder()
                    .id(String.valueOf(groupId))
                    .name("Updated Name")
                    .botCount(20)
                    .gameType(GameType.BAU_CUA)
                    .build();

            BotGroupDTO outputDto = BotGroupDTO.builder()
                    .id(String.valueOf(groupId))
                    .name("Updated Name")
                    .botCount(20)
                    .gameType(GameType.BAU_CUA)
                    .build();

            when(service.update(eq(groupId), any(BotGroupDTO.class))).thenReturn(updatedEntity);
            when(mapper.toDTO(updatedEntity)).thenReturn(outputDto);

            // Act & Assert
            mockMvc.perform(patch("/api/v1/bot-group/{id}", groupId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(String.valueOf(groupId)))
                    .andExpect(jsonPath("$.name").value("Updated Name"))
                    .andExpect(jsonPath("$.botCount").value(20));
        }

        @Test
        @DisplayName("Should return 404 Not Found when bot group does not exist")
        void shouldReturnNotFoundWhenBotGroupDoesNotExist() throws Exception {
            // Arrange
            int groupId = 999;
            BotGroupDTO updateDto = BotGroupDTO.builder()
                    .name("Updated Name")
                    .build();

            when(service.update(eq(groupId), any(BotGroupDTO.class)))
                    .thenThrow(new ResourceNotFoundException("Bot group not found"));

            // Act & Assert
            mockMvc.perform(patch("/api/v1/bot-group/{id}", groupId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/bot-group/{id}")
    class DeleteTests {

        @Test
        @DisplayName("Should return 200 OK when bot group is deleted")
        void shouldReturnOkWhenBotGroupIsDeleted() throws Exception {
            // Arrange
            int groupId = 123;
            doNothing().when(service).delete(groupId);

            // Act & Assert
            mockMvc.perform(delete("/api/v1/bot-group/{id}", groupId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 404 Not Found when bot group does not exist")
        void shouldReturnNotFoundWhenBotGroupDoesNotExist() throws Exception {
            // Arrange
            int groupId = 999;
            doThrow(new IllegalArgumentException("Not found")).when(service).delete(groupId);

            // Act & Assert
            mockMvc.perform(delete("/api/v1/bot-group/{id}", groupId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bot-group/{id}/start")
    class StartTests {

        @Test
        @DisplayName("Should return 200 OK when bot group is started")
        void shouldReturnOkWhenBotGroupIsStarted() throws Exception {
            // Arrange
            int groupId = 123;
            doNothing().when(behaviorService).start(groupId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/start", groupId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 404 Not Found when bot group does not exist")
        void shouldReturnNotFoundWhenBotGroupDoesNotExist() throws Exception {
            // Arrange
            int groupId = 999;
            doThrow(new IllegalArgumentException("Not found")).when(behaviorService).start(groupId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/start", groupId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 500 Internal Server Error when start fails")
        void shouldReturnInternalServerErrorWhenStartFails() throws Exception {
            // Arrange
            int groupId = 123;
            doThrow(new RuntimeException("Start failed")).when(behaviorService).start(groupId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/start", groupId))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bot-group/{id}/stop")
    class StopTests {

        @Test
        @DisplayName("Should return 200 OK when bot group is stopped")
        void shouldReturnOkWhenBotGroupIsStopped() throws Exception {
            // Arrange
            int groupId = 123;
            doNothing().when(behaviorService).stop(groupId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/stop", groupId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 404 Not Found when bot group does not exist")
        void shouldReturnNotFoundWhenBotGroupDoesNotExist() throws Exception {
            // Arrange
            int groupId = 999;
            doThrow(new IllegalArgumentException("Not found")).when(behaviorService).stop(groupId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/stop", groupId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bot-group/{id}/restart")
    class RestartTests {

        @Test
        @DisplayName("Should return 200 OK when bot group is restarted")
        void shouldReturnOkWhenBotGroupIsRestarted() throws Exception {
            // Arrange
            int groupId = 123;
            doNothing().when(behaviorService).restart(groupId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/restart", groupId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 404 Not Found when bot group does not exist")
        void shouldReturnNotFoundWhenBotGroupDoesNotExist() throws Exception {
            // Arrange
            int groupId = 999;
            doThrow(new IllegalArgumentException("Not found")).when(behaviorService).restart(groupId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/restart", groupId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bot-group/{id}/schedule-restart")
    class ScheduleRestartTests {

        @Test
        @DisplayName("Should return 200 OK when restart is scheduled")
        void shouldReturnOkWhenRestartIsScheduled() throws Exception {
            // Arrange
            int groupId = 123;
            LocalDateTime futureTime = LocalDateTime.now().plusHours(2);
            doNothing().when(behaviorService).scheduleRestart(eq(groupId), any(LocalDateTime.class));

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/schedule-restart", groupId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(futureTime)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 404 Not Found when bot group does not exist")
        void shouldReturnNotFoundWhenBotGroupDoesNotExist() throws Exception {
            // Arrange
            int groupId = 999;
            LocalDateTime futureTime = LocalDateTime.now().plusHours(2);
            doThrow(new IllegalArgumentException("Not found"))
                    .when(behaviorService).scheduleRestart(eq(groupId), any(LocalDateTime.class));

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/schedule-restart", groupId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(futureTime)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/bot-group/{id}/status")
    class GetStatusTests {

        @Test
        @DisplayName("Should return 200 OK with bot group status")
        void shouldReturnOkWithBotGroupStatus() throws Exception {
            // Arrange
            int groupId = 123;
            BotGroup group = BotGroup.builder()
                    .id(String.valueOf(groupId))
                    .name("Test Group")
                    .targetStatus(BotGroupStatus.ACTIVE)
                    .build();

            when(service.findById(groupId)).thenReturn(group);
            when(behaviorService.getActualStatus(groupId)).thenReturn(BotGroupStatus.ACTIVE);
            when(behaviorService.getPlayingStatus(groupId)).thenReturn(BotGroupPlayingStatus.PLAYING);

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/{id}/status", groupId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.groupId").value(String.valueOf(groupId)))
                    .andExpect(jsonPath("$.groupName").value("Test Group"))
                    .andExpect(jsonPath("$.targetStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.actualStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.playingStatus").value("PLAYING"));
        }

        @Test
        @DisplayName("Should return 404 Not Found when bot group does not exist")
        void shouldReturnNotFoundWhenBotGroupDoesNotExist() throws Exception {
            // Arrange
            int groupId = 999;
            when(service.findById(groupId)).thenThrow(new ResourceNotFoundException("Bot group not found"));

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/{id}/status", groupId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return status with null playingStatus when bot group is stopped")
        void shouldReturnStatusWithNullPlayingStatusWhenBotGroupIsStopped() throws Exception {
            // Arrange
            int groupId = 123;
            BotGroup group = BotGroup.builder()
                    .id(String.valueOf(groupId))
                    .name("Stopped Group")
                    .targetStatus(BotGroupStatus.STOPPED)
                    .build();

            when(service.findById(groupId)).thenReturn(group);
            when(behaviorService.getActualStatus(groupId)).thenReturn(BotGroupStatus.STOPPED);
            when(behaviorService.getPlayingStatus(groupId)).thenReturn(null);

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/{id}/status", groupId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.targetStatus").value("STOPPED"))
                    .andExpect(jsonPath("$.actualStatus").value("STOPPED"))
                    .andExpect(jsonPath("$.playingStatus").doesNotExist());
        }
    }
}