package com.vingame.bot.domain.botgroup.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.common.exception.RestExceptionHandler;
import com.vingame.bot.common.exception.UpstreamLoginException;
import com.vingame.bot.common.exception.UpstreamRegistrationException;
import com.vingame.bot.domain.bot.core.BotStatus;
import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.dto.BotGroupHealthDTO;
import com.vingame.bot.domain.botgroup.dto.BotGroupStatsDTO;
import com.vingame.bot.domain.botgroup.dto.BotGroupStatusDTO;
import com.vingame.bot.domain.botgroup.dto.BotHealthDTO;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapper;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupFilter;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.sort.BotGroupSortRow;
import com.vingame.bot.domain.botgroup.sort.BotSortKey;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.BotGroupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BotGroupController.class)
@Import(RestExceptionHandler.class)
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
            String groupId = "123";
            BotGroup botGroup = BotGroup.builder()
                    .id(groupId)
                    .name("Test Bot Group")
                    .namePrefix("bot")
                    .password("password123")
                    .gameId("game-baucua")
                    .botCount(10)
                    .environmentId("env-1")
                    .build();

            BotGroupDTO dto = BotGroupDTO.builder()
                    .id(groupId)
                    .name("Test Bot Group")
                    .namePrefix("bot")
                    .gameId("game-baucua")
                    .botCount(10)
                    .environmentId("env-1")
                    .build();

            when(service.findById(groupId)).thenReturn(botGroup);
            when(mapper.toDTO(botGroup)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/{id}", groupId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(groupId))
                    .andExpect(jsonPath("$.name").value("Test Bot Group"))
                    .andExpect(jsonPath("$.gameId").value("game-baucua"))
                    .andExpect(jsonPath("$.botCount").value(10))
                    .andExpect(jsonPath("$.environmentId").value("env-1"));
        }

        @Test
        @DisplayName("Should return 404 Not Found when bot group does not exist")
        void shouldReturnNotFoundWhenBotGroupDoesNotExist() throws Exception {
            // Arrange
            String groupId = "999";
            when(service.findById(groupId)).thenThrow(new ResourceNotFoundException("Bot group not found"));

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/{id}", groupId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when ID is invalid")
        void shouldReturnBadRequestWhenIdIsInvalid() throws Exception {
            // Arrange
            String groupId = "invalid";
            when(service.findById(groupId)).thenThrow(new IllegalArgumentException("Invalid ID"));

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/{id}", groupId))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/bot-group/ (removed, AD-6)")
    class GetAllRemovedTests {

        @Test
        @DisplayName("Unscoped list-all endpoint is hard-removed — no longer maps")
        void unscopedListAllIsGone() throws Exception {
            // AD-6: superseded by the env-scoped filter; no redirect/deprecation shim.
            mockMvc.perform(get("/api/v1/bot-group/"))
                    .andExpect(status().is4xxClientError());

            verify(service, never()).findAll();
        }
    }

    @Nested
    @DisplayName("GET /api/v1/bot-group/sort-keys")
    class GetSortKeysTests {

        @Test
        @DisplayName("Should return every BotSortKey enum name")
        void shouldReturnAllBotSortKeys() throws Exception {
            var perform = mockMvc.perform(get("/api/v1/bot-group/sort-keys"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(BotSortKey.values().length));
            for (BotSortKey key : BotSortKey.values()) {
                perform.andExpect(jsonPath("$[*]").value(org.hamcrest.Matchers.hasItem(key.name())));
            }
        }

        @Test
        @DisplayName("Should return the FULL BotSortKey list in exact enum order (cannot drift from the filter's accepted keys)")
        void shouldReturnExactBotSortKeyListInOrder() throws Exception {
            var expected = java.util.Arrays.stream(BotSortKey.values()).map(Enum::name).toList();
            mockMvc.perform(get("/api/v1/bot-group/sort-keys"))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .content().json(new ObjectMapper().writeValueAsString(expected), true));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bot-group/{envId}/filter")
    class FilterTests {

        @Test
        @DisplayName("Should return 200 OK with filtered bot groups by game id")
        void shouldReturnOkWithFilteredBotGroupsByGameId() throws Exception {
            // Arrange
            BotGroupFilter filter = new BotGroupFilter();
            filter.setGameId("game-baucua");

            BotGroup group = BotGroup.builder()
                    .id("1")
                    .name("Filtered Group")
                    .gameId("game-baucua")
                    .build();

            BotGroupDTO dto = BotGroupDTO.builder()
                    .id("1")
                    .name("Filtered Group")
                    .gameId("game-baucua")
                    .build();

            when(behaviorService.filterSorted(eq("env-123"), any(BotGroupFilter.class)))
                    .thenReturn(List.of(row(group)));
            when(mapper.toDTO(group)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{envId}/filter", "env-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(filter)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("Filtered Group"))
                    .andExpect(jsonPath("$[0].gameId").value("game-baucua"));
        }

        @Test
        @DisplayName("Empty body returns all groups in the env, passing the env id from the path")
        void shouldReturnAllInEnvWithEmptyBody() throws Exception {
            BotGroup group = BotGroup.builder()
                    .id("1")
                    .name("Environment Group")
                    .environmentId("env-123")
                    .gameId("game-baucua")
                    .build();

            BotGroupDTO dto = BotGroupDTO.builder()
                    .id("1")
                    .name("Environment Group")
                    .environmentId("env-123")
                    .gameId("game-baucua")
                    .build();

            when(behaviorService.filterSorted(eq("env-123"), any(BotGroupFilter.class)))
                    .thenReturn(List.of(row(group)));
            when(mapper.toDTO(group)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{envId}/filter", "env-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].environmentId").value("env-123"));

            verify(behaviorService).filterSorted(eq("env-123"), any(BotGroupFilter.class));
        }

        @Test
        @DisplayName("Old unscoped POST /filter/ route no longer maps (moved to /{envId}/filter)")
        void oldUnscopedFilterRouteIsGone() throws Exception {
            // AD-5: env moved from the body to a mandatory path segment; the old
            // /filter/ contract must not silently resolve to the new handler.
            mockMvc.perform(post("/api/v1/bot-group/filter/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().is4xxClientError());

            verify(behaviorService, never()).filterSorted(any(), any(BotGroupFilter.class));
        }

        @Test
        @DisplayName("Should return 200 OK with empty list when no matches")
        void shouldReturnOkWithEmptyListWhenNoMatches() throws Exception {
            // Arrange
            BotGroupFilter filter = new BotGroupFilter();
            filter.setName("NonExistentGroup");

            when(behaviorService.filterSorted(eq("env-123"), any(BotGroupFilter.class)))
                    .thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{envId}/filter", "env-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(filter)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("Unknown sort key surfaces as 400 (propagated from the enrich/sort path)")
        void unknownSortKeyReturnsBadRequest() throws Exception {
            // AD-11: an unrecognised sortBy resolves to a BadRequestException in
            // BotSortKey.resolve, mapped to 400 by RestExceptionHandler.
            when(behaviorService.filterSorted(eq("env-123"), any(BotGroupFilter.class)))
                    .thenThrow(new BadRequestException("Unknown bot-group sort key 'nonsense'."));

            mockMvc.perform(post("/api/v1/bot-group/{envId}/filter", "env-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sortBy\":\"nonsense\"}"))
                    .andExpect(status().isBadRequest());
        }

        private BotGroupSortRow row(BotGroup group) {
            return new BotGroupSortRow(group, BotGroupStatsDTO.builder().build(),
                    BotGroupStatus.STOPPED, "BETTING_MINI");
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
                    .gameId("game-baucua")
                    .botCount(5)
                    .environmentId("env-1")
                    .build();

            BotGroup entity = BotGroup.builder()
                    .name("New Bot Group")
                    .namePrefix("testbot")
                    .password("secret")
                    .gameId("game-baucua")
                    .botCount(5)
                    .environmentId("env-1")
                    .build();

            BotGroup savedEntity = BotGroup.builder()
                    .id("123")
                    .name("New Bot Group")
                    .namePrefix("testbot")
                    .password("secret")
                    .gameId("game-baucua")
                    .botCount(5)
                    .environmentId("env-1")
                    .build();

            BotGroupDTO outputDto = BotGroupDTO.builder()
                    .id("123")
                    .name("New Bot Group")
                    .namePrefix("testbot")
                    .gameId("game-baucua")
                    .botCount(5)
                    .environmentId("env-1")
                    .build();

            when(mapper.toEntity(any(BotGroupDTO.class))).thenReturn(entity);
            when(service.save(any(BotGroup.class), eq(false))).thenReturn(savedEntity);
            when(mapper.toDTO(savedEntity)).thenReturn(outputDto);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(inputDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("123"))
                    .andExpect(jsonPath("$.name").value("New Bot Group"))
                    .andExpect(jsonPath("$.gameId").value("game-baucua"))
                    .andExpect(jsonPath("$.botCount").value(5));
        }

        @Test
        @DisplayName("Should return 502 Bad Gateway with forwarded error when upstream registration fails")
        void shouldReturnBadGatewayWhenRegistrationFails() throws Exception {
            BotGroupDTO inputDto = BotGroupDTO.builder()
                    .name("Demo 116 BC")
                    .namePrefix("dem0bc116bot")
                    .password("a123123A")
                    .gameId("game-1")
                    .botCount(30)
                    .environmentId("env-1")
                    .build();

            BotGroup entity = BotGroup.builder()
                    .name("Demo 116 BC")
                    .namePrefix("dem0bc116bot")
                    .build();

            when(mapper.toEntity(any(BotGroupDTO.class))).thenReturn(entity);
            when(service.save(any(BotGroup.class), eq(false)))
                    .thenThrow(new UpstreamRegistrationException(
                            "Failed to register any users for bot group 'Demo 116 BC'. " +
                                    "Errors: Tên đăng nhập không được nhiều hơn 12 ký tự"));

            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(inputDto)))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.type").value("Game server error"))
                    .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString(
                            "Tên đăng nhập không được nhiều hơn 12 ký tự")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request with body when service throws BadRequestException")
        void shouldReturnBadRequestWithBodyWhenBadRequestException() throws Exception {
            // gameId present so the universal @Validated(OnCreate) layer passes and
            // the request reaches the service mock, which is what this test asserts.
            BotGroupDTO inputDto = BotGroupDTO.builder()
                    .name("Bad")
                    .namePrefix("longprefix")
                    .password("p")
                    .botCount(99)
                    .environmentId("env-tip")
                    .gameId("game-tip")
                    .build();

            BotGroup entity = BotGroup.builder().name("Bad").build();

            when(mapper.toEntity(any(BotGroupDTO.class))).thenReturn(entity);
            when(service.save(any(BotGroup.class), eq(false)))
                    .thenThrow(new BadRequestException(
                            "Username too long for product P_116: ..."));

            mockMvc.perform(post("/api/v1/bot-group/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(inputDto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("Bad request"))
                    .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString(
                            "Username too long for product P_116")));
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/bot-group/{id}")
    class UpdateTests {

        @Test
        @DisplayName("Should return 200 OK with updated bot group")
        void shouldReturnOkWithUpdatedBotGroup() throws Exception {
            // Arrange
            String groupId = "123";
            BotGroupDTO updateDto = BotGroupDTO.builder()
                    .name("Updated Name")
                    .botCount(20)
                    .build();

            BotGroup updatedEntity = BotGroup.builder()
                    .id(groupId)
                    .name("Updated Name")
                    .botCount(20)
                    .gameId("game-baucua")
                    .build();

            BotGroupDTO outputDto = BotGroupDTO.builder()
                    .id(groupId)
                    .name("Updated Name")
                    .botCount(20)
                    .gameId("game-baucua")
                    .build();

            when(service.update(eq(groupId), any(BotGroupDTO.class))).thenReturn(updatedEntity);
            when(mapper.toDTO(updatedEntity)).thenReturn(outputDto);

            // Act & Assert
            mockMvc.perform(patch("/api/v1/bot-group/{id}", groupId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(groupId))
                    .andExpect(jsonPath("$.name").value("Updated Name"))
                    .andExpect(jsonPath("$.botCount").value(20));
        }

        @Test
        @DisplayName("Should return 404 Not Found when bot group does not exist")
        void shouldReturnNotFoundWhenBotGroupDoesNotExist() throws Exception {
            // Arrange
            String groupId = "999";
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
            String groupId = "123";
            doNothing().when(service).delete(groupId);

            // Act & Assert
            mockMvc.perform(delete("/api/v1/bot-group/{id}", groupId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when delete throws IllegalArgumentException")
        void shouldReturnBadRequestWhenIllegalArgument() throws Exception {
            // Arrange
            String groupId = "999";
            doThrow(new IllegalArgumentException("Not found")).when(service).delete(groupId);

            // Act & Assert
            mockMvc.perform(delete("/api/v1/bot-group/{id}", groupId))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bot-group/{id}/start")
    class StartTests {

        @Test
        @DisplayName("Should return 200 OK when bot group is started")
        void shouldReturnOkWhenBotGroupIsStarted() throws Exception {
            // Arrange
            String groupId = "123";
            doNothing().when(behaviorService).start(groupId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/start", groupId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when start throws IllegalArgumentException")
        void shouldReturnBadRequestWhenIllegalArgument() throws Exception {
            // Arrange
            String groupId = "999";
            doThrow(new IllegalArgumentException("Not found")).when(behaviorService).start(groupId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/start", groupId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 500 Internal Server Error with sanitised body when start fails")
        void shouldReturnInternalServerErrorWhenStartFails() throws Exception {
            // The 500 fallback in RestExceptionHandler intentionally does not
            // echo e.getMessage() to the client — raw exception messages can
            // carry Mongo hostnames, Spring wiring failures, JDK HttpClient
            // infra details. The full exception is logged server-side.
            String groupId = "123";
            doThrow(new RuntimeException("Start failed")).when(behaviorService).start(groupId);

            mockMvc.perform(post("/api/v1/bot-group/{id}/start", groupId))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.type").value("Internal error"))
                    .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString(
                            "Internal server error")))
                    .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("Start failed"))));
        }

        @Test
        @DisplayName("Should return 502 Bad Gateway when start surfaces UpstreamLoginException")
        void shouldReturnBadGatewayWhenUpstreamLogin() throws Exception {
            String groupId = "123";
            doThrow(new UpstreamLoginException(
                    "Login failed for user 'authtest1': No data in response"))
                    .when(behaviorService).start(groupId);

            mockMvc.perform(post("/api/v1/bot-group/{id}/start", groupId))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.type").value("Game server error"))
                    .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString(
                            "Login failed for user 'authtest1'")));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bot-group/{id}/stop")
    class StopTests {

        @Test
        @DisplayName("Should return 200 OK when bot group is stopped")
        void shouldReturnOkWhenBotGroupIsStopped() throws Exception {
            // Arrange
            String groupId = "123";
            doNothing().when(behaviorService).stop(groupId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/stop", groupId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when stop throws IllegalArgumentException")
        void shouldReturnBadRequestWhenIllegalArgument() throws Exception {
            // Arrange
            String groupId = "999";
            doThrow(new IllegalArgumentException("Not found")).when(behaviorService).stop(groupId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/stop", groupId))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bot-group/{id}/restart")
    class RestartTests {

        @Test
        @DisplayName("Should return 200 OK when bot group is restarted")
        void shouldReturnOkWhenBotGroupIsRestarted() throws Exception {
            // Arrange
            String groupId = "123";
            doNothing().when(behaviorService).restart(groupId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/restart", groupId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when restart throws IllegalArgumentException")
        void shouldReturnBadRequestWhenIllegalArgument() throws Exception {
            // Arrange
            String groupId = "999";
            doThrow(new IllegalArgumentException("Not found")).when(behaviorService).restart(groupId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/restart", groupId))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bot-group/{id}/schedule-restart")
    class ScheduleRestartTests {

        @Test
        @DisplayName("Should return 200 OK when restart is scheduled")
        void shouldReturnOkWhenRestartIsScheduled() throws Exception {
            // Arrange
            String groupId = "123";
            LocalDateTime futureTime = LocalDateTime.now().plusHours(2);
            doNothing().when(behaviorService).scheduleRestart(eq(groupId), any(LocalDateTime.class));

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/schedule-restart", groupId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(futureTime)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when scheduleRestart throws IllegalArgumentException")
        void shouldReturnBadRequestWhenIllegalArgument() throws Exception {
            // Arrange
            String groupId = "999";
            LocalDateTime futureTime = LocalDateTime.now().plusHours(2);
            doThrow(new IllegalArgumentException("Not found"))
                    .when(behaviorService).scheduleRestart(eq(groupId), any(LocalDateTime.class));

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/schedule-restart", groupId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(futureTime)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/bot-group/{id}/status")
    class GetStatusTests {

        @Test
        @DisplayName("Should return 200 OK with bot group status")
        void shouldReturnOkWithBotGroupStatus() throws Exception {
            // Arrange
            String groupId = "123";
            BotGroup group = BotGroup.builder()
                    .id(groupId)
                    .name("Test Group")
                    .targetStatus(BotGroupStatus.ACTIVE)
                    .build();

            when(service.findById(groupId)).thenReturn(group);
            when(behaviorService.getActualStatus(groupId)).thenReturn(BotGroupStatus.ACTIVE);
            when(behaviorService.getPlayingStatus(groupId)).thenReturn(BotGroupPlayingStatus.PLAYING);

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/{id}/status", groupId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.groupId").value(groupId))
                    .andExpect(jsonPath("$.groupName").value("Test Group"))
                    .andExpect(jsonPath("$.targetStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.actualStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.playingStatus").value("PLAYING"));
        }

        @Test
        @DisplayName("Should return 404 Not Found when bot group does not exist")
        void shouldReturnNotFoundWhenBotGroupDoesNotExist() throws Exception {
            // Arrange
            String groupId = "999";
            when(service.findById(groupId)).thenThrow(new ResourceNotFoundException("Bot group not found"));

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/{id}/status", groupId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return status with null playingStatus when bot group is stopped")
        void shouldReturnStatusWithNullPlayingStatusWhenBotGroupIsStopped() throws Exception {
            // Arrange
            String groupId = "123";
            BotGroup group = BotGroup.builder()
                    .id(groupId)
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

    @Nested
    @DisplayName("GET /api/v1/bot-group/{id}/health")
    class GetHealthTests {

        @Test
        @DisplayName("Should return 200 OK with full BotGroupHealthDTO body")
        void shouldReturnOkWithBotGroupHealth() throws Exception {
            // Arrange
            String groupId = "123";

            BotHealthDTO bot1 = BotHealthDTO.builder()
                    .username("authtestws1")
                    .status(BotStatus.STARTED)
                    .connected(true)
                    .balance(950_000L)
                    .lastFetchedBalance(1_000_000L)
                    .totalBetsPlaced(5)
                    .totalBetAmount(50_000L)
                    .lastRoundWinnings(2_500L)
                    .build();

            BotHealthDTO bot2 = BotHealthDTO.builder()
                    .username("authtestws2")
                    .status(BotStatus.RECONNECTING)
                    .connected(false)
                    .balance(800_000L)
                    .lastFetchedBalance(800_000L)
                    .totalBetsPlaced(3)
                    .totalBetAmount(30_000L)
                    .lastRoundWinnings(0L)
                    .build();

            BotGroupHealthDTO health = BotGroupHealthDTO.builder()
                    .groupId(groupId)
                    .groupName("Test Health Group")
                    .status(BotGroupStatus.ACTIVE)
                    .playingStatus(BotGroupPlayingStatus.PLAYING)
                    .startedAt(Instant.parse("2026-01-01T00:00:00Z"))
                    .consecutiveFailures(0)
                    .totalBots(2)
                    .connectedBots(1)
                    .reconnectingBots(1)
                    .deadBots(0)
                    .disconnectedBots(1)
                    .bots(List.of(bot1, bot2))
                    .build();

            when(behaviorService.getHealth(groupId)).thenReturn(health);

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/{id}/health", groupId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.groupId").value(groupId))
                    .andExpect(jsonPath("$.groupName").value("Test Health Group"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.playingStatus").value("PLAYING"))
                    .andExpect(jsonPath("$.totalBots").value(2))
                    .andExpect(jsonPath("$.connectedBots").value(1))
                    .andExpect(jsonPath("$.reconnectingBots").value(1))
                    .andExpect(jsonPath("$.deadBots").value(0))
                    .andExpect(jsonPath("$.disconnectedBots").value(1))
                    .andExpect(jsonPath("$.consecutiveFailures").value(0))
                    .andExpect(jsonPath("$.bots").isArray())
                    .andExpect(jsonPath("$.bots.length()").value(2))
                    .andExpect(jsonPath("$.bots[0].username").value("authtestws1"))
                    .andExpect(jsonPath("$.bots[0].status").value("STARTED"))
                    .andExpect(jsonPath("$.bots[0].connected").value(true))
                    .andExpect(jsonPath("$.bots[0].balance").value(950_000))
                    .andExpect(jsonPath("$.bots[0].totalBetsPlaced").value(5))
                    .andExpect(jsonPath("$.bots[1].username").value("authtestws2"))
                    .andExpect(jsonPath("$.bots[1].status").value("RECONNECTING"))
                    .andExpect(jsonPath("$.bots[1].connected").value(false));
        }

        @Test
        @DisplayName("Should return 404 Not Found when bot group does not exist")
        void shouldReturnNotFoundWhenBotGroupDoesNotExist() throws Exception {
            // Arrange
            String groupId = "999";
            when(behaviorService.getHealth(groupId))
                    .thenThrow(new ResourceNotFoundException("Bot group not found"));

            // Act & Assert
            mockMvc.perform(get("/api/v1/bot-group/{id}/health", groupId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 500 Internal Server Error with sanitised body when getHealth throws unexpected exception")
        void shouldReturnInternalServerErrorWhenGetHealthFails() throws Exception {
            // Sanitised 500 fallback — body never echoes the raw exception
            // message. Server log carries the trace.
            String groupId = "123";
            when(behaviorService.getHealth(groupId))
                    .thenThrow(new RuntimeException("Boom"));

            mockMvc.perform(get("/api/v1/bot-group/{id}/health", groupId))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.type").value("Internal error"))
                    .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString(
                            "Internal server error")))
                    .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("Boom"))));
        }
    }
}