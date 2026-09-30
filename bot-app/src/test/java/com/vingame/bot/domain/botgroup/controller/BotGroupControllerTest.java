package com.vingame.bot.domain.botgroup.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.common.exception.RestExceptionHandler;
import com.vingame.bot.common.exception.UpstreamLoginException;
import com.vingame.bot.common.exception.UpstreamRegistrationException;
import com.vingame.bot.domain.bot.core.BotStatus;
import com.vingame.bot.domain.botgroup.model.ActivationMode;
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
import com.vingame.bot.domain.botgroup.model.StartOrigin;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
        @DisplayName("Accepts the start with 200 + a STARTING DTO, and does not run it inline")
        void shouldReturnOkWithStartingDto() throws Exception {
            // Arrange — legacy null-mode group: activationMode stays null (AD-4),
            // so the controller must NOT flip the mode after a manual start.
            String groupId = "123";
            when(service.findById(groupId))
                    .thenReturn(BotGroup.builder().id(groupId).name("Group").botCount(50).build());
            when(behaviorService.startAsync(eq(groupId), eq(StartOrigin.REST), any()))
                    .thenReturn(BotGroupStatus.STARTING);
            when(behaviorService.getStartBotsUp(groupId)).thenReturn(3);

            // Act & Assert — 200 now means ACCEPTED (A3): the build is on a virtual thread and
            // may legitimately run for tens of minutes, so the body has to say what to poll.
            mockMvc.perform(post("/api/v1/bot-group/{id}/start", groupId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.groupId").value(groupId))
                    .andExpect(jsonPath("$.actualStatus").value("STARTING"))
                    .andExpect(jsonPath("$.botCount").value(50))
                    .andExpect(jsonPath("$.botsUp").value(3));

            verify(behaviorService).startAsync(eq(groupId), eq(StartOrigin.REST), any());
            // The synchronous entry point is gone: a blocking start here is what held an HTTP
            // thread (and, on the startup path, Tomcat itself) for the whole build.
            verify(behaviorService, never()).start(anyString());
            verify(service, never()).setActivationMode(any(BotGroup.class), any());
        }

        @Test
        @DisplayName("Manual start parks a SCHEDULED group as MANUAL_ON before the action (AD-4, TOCTOU)")
        void manualStartParksScheduledGroupAsManualOn() throws Exception {
            // Arrange — scheduled-capable group: an operator start must flip the
            // mode to MANUAL_ON *before* starting so a reconciler tick racing the
            // action window sees a non-SCHEDULED mode and cannot stop it.
            String groupId = "123";
            when(service.findById(groupId)).thenReturn(
                    BotGroup.builder().id(groupId).activationMode(ActivationMode.SCHEDULED).build());
            when(behaviorService.startAsync(eq(groupId), eq(StartOrigin.REST), any()))
                    .thenReturn(BotGroupStatus.STARTING);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/start", groupId))
                    .andExpect(status().isOk());

            // The flip must be persisted BEFORE the lifecycle action.
            InOrder inOrder = inOrder(service, behaviorService);
            inOrder.verify(service).setActivationMode(
                    argThat((BotGroup g) -> groupId.equals(g.getId())), eq(ActivationMode.MANUAL_ON));
            inOrder.verify(behaviorService).startAsync(eq(groupId), eq(StartOrigin.REST), any());
        }

        @Test
        @DisplayName("The mode rollback is handed to the async start, and running it restores SCHEDULED")
        void asyncFailureRollsBackModeThroughTheCallback() throws Exception {
            // The controller's catch cannot see a failure that happens minutes after the
            // response (AD-15 / Implementation Note 8), so the rollback travels into startAsync
            // as a Runnable. Here we capture it and run it, which is what the build thread does
            // on failure.
            String groupId = "123";
            when(service.findById(groupId)).thenReturn(
                    BotGroup.builder().id(groupId).activationMode(ActivationMode.SCHEDULED).build());
            ArgumentCaptor<Runnable> onFailure = ArgumentCaptor.forClass(Runnable.class);
            when(behaviorService.startAsync(eq(groupId), eq(StartOrigin.REST), onFailure.capture()))
                    .thenReturn(BotGroupStatus.STARTING);

            mockMvc.perform(post("/api/v1/bot-group/{id}/start", groupId))
                    .andExpect(status().isOk());

            verify(service).setActivationMode(any(BotGroup.class), eq(ActivationMode.MANUAL_ON));
            assertThat(onFailure.getValue()).as("a rollback must be handed to the build").isNotNull();

            onFailure.getValue().run();

            verify(service).setActivationMode(any(BotGroup.class), eq(ActivationMode.SCHEDULED));
        }

        @Test
        @DisplayName("A failure in the synchronous half still rolls the flip back and still answers 500")
        void synchronousFailureRollsBackMode() throws Exception {
            // Only the accept is synchronous now, but it can still throw (a Mongo read, the two
            // 400s), and in that case no build was submitted — so the catch, not the callback, is
            // what has to undo the flip.
            String groupId = "123";
            when(service.findById(groupId)).thenReturn(
                    BotGroup.builder().id(groupId).activationMode(ActivationMode.SCHEDULED).build());
            doThrow(new RuntimeException("Start failed"))
                    .when(behaviorService).startAsync(eq(groupId), eq(StartOrigin.REST), any());

            mockMvc.perform(post("/api/v1/bot-group/{id}/start", groupId))
                    .andExpect(status().isInternalServerError());

            InOrder inOrder = inOrder(service, behaviorService);
            inOrder.verify(service).setActivationMode(any(BotGroup.class), eq(ActivationMode.MANUAL_ON));
            inOrder.verify(behaviorService).startAsync(eq(groupId), eq(StartOrigin.REST), any());
            inOrder.verify(service).setActivationMode(any(BotGroup.class), eq(ActivationMode.SCHEDULED));
        }

        @Test
        @DisplayName("404 for an unknown id stays synchronous")
        void unknownIdIsStillSynchronous() throws Exception {
            String groupId = "999";
            when(service.findById(groupId)).thenThrow(new ResourceNotFoundException("BotGroup " + groupId + " not found"));

            mockMvc.perform(post("/api/v1/bot-group/{id}/start", groupId))
                    .andExpect(status().isNotFound());

            verify(behaviorService, never()).startAsync(anyString(), any(), any());
        }

        @Test
        @DisplayName("400 from the accept's validation stays synchronous (no environment / no game)")
        void validationBadRequestIsStillSynchronous() throws Exception {
            // The two checks startLocked does first are hoisted into the accept precisely so a
            // misconfigured group is a 400 and not a 200 followed by a silent failure.
            String groupId = "123";
            when(service.findById(groupId)).thenReturn(BotGroup.builder().id(groupId).build());
            doThrow(new BadRequestException("BotGroup Group has no gameId set."))
                    .when(behaviorService).startAsync(eq(groupId), eq(StartOrigin.REST), any());

            mockMvc.perform(post("/api/v1/bot-group/{id}/start", groupId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("Bad request"))
                    .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("gameId")));

            verify(service, never()).setActivationMode(any(BotGroup.class), any());
        }

        @Test
        @DisplayName("A second start while one is in flight is still a 200 describing the same attempt")
        void secondStartIsAcceptedAndDescribesTheSameAttempt() throws Exception {
            String groupId = "123";
            when(service.findById(groupId))
                    .thenReturn(BotGroup.builder().id(groupId).name("Group").botCount(50).build());
            // startAsync's putIfAbsent lost the race, so it reports the status of the attempt
            // that is already running rather than submitting a second build.
            when(behaviorService.startAsync(eq(groupId), eq(StartOrigin.REST), any()))
                    .thenReturn(BotGroupStatus.STARTING);
            when(behaviorService.getStartBotsUp(groupId)).thenReturn(12);

            mockMvc.perform(post("/api/v1/bot-group/{id}/start", groupId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.actualStatus").value("STARTING"))
                    .andExpect(jsonPath("$.botsUp").value(12));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bot-group/{id}/stop")
    class StopTests {

        @Test
        @DisplayName("Should return 200 OK when bot group is stopped")
        void shouldReturnOkWhenBotGroupIsStopped() throws Exception {
            // Arrange — legacy null-mode group: activationMode stays null (AD-4),
            // so the controller must NOT flip the mode after a manual stop.
            String groupId = "123";
            doNothing().when(behaviorService).stop(groupId);
            when(service.findById(groupId))
                    .thenReturn(BotGroup.builder().id(groupId).build());

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/stop", groupId))
                    .andExpect(status().isOk());

            verify(behaviorService).stop(groupId);
            verify(service, never()).setActivationMode(any(BotGroup.class), any());
        }

        @Test
        @DisplayName("Manual stop parks a SCHEDULED group as MANUAL_OFF before the action (AD-4, TOCTOU)")
        void manualStopParksScheduledGroupAsManualOff() throws Exception {
            // Arrange — scheduled-capable group: an operator stop must flip the
            // mode to MANUAL_OFF *before* stopping so a reconciler tick racing the
            // action window sees a non-SCHEDULED mode and cannot restart it.
            String groupId = "123";
            doNothing().when(behaviorService).stop(groupId);
            when(service.findById(groupId)).thenReturn(
                    BotGroup.builder().id(groupId).activationMode(ActivationMode.SCHEDULED).build());

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/stop", groupId))
                    .andExpect(status().isOk());

            // The flip must be persisted BEFORE the lifecycle action.
            InOrder inOrder = inOrder(service, behaviorService);
            inOrder.verify(service).setActivationMode(
                    argThat((BotGroup g) -> groupId.equals(g.getId())), eq(ActivationMode.MANUAL_OFF));
            inOrder.verify(behaviorService).stop(groupId);
        }

        @Test
        @DisplayName("A failing stop rolls back the mode flip, leaving activationMode unchanged (AD-4)")
        void failingStopRollsBackMode() throws Exception {
            // Arrange — scheduled-capable group whose stop fails: the MANUAL_OFF
            // flip must be reverted to the prior SCHEDULED mode.
            String groupId = "123";
            when(service.findById(groupId)).thenReturn(
                    BotGroup.builder().id(groupId).activationMode(ActivationMode.SCHEDULED).build());
            doThrow(new RuntimeException("Stop failed")).when(behaviorService).stop(groupId);

            mockMvc.perform(post("/api/v1/bot-group/{id}/stop", groupId))
                    .andExpect(status().isInternalServerError());

            // Flip to MANUAL_OFF, then restore SCHEDULED on failure, in that order.
            InOrder inOrder = inOrder(service, behaviorService);
            inOrder.verify(service).setActivationMode(any(BotGroup.class), eq(ActivationMode.MANUAL_OFF));
            inOrder.verify(behaviorService).stop(groupId);
            inOrder.verify(service).setActivationMode(any(BotGroup.class), eq(ActivationMode.SCHEDULED));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when stop throws IllegalArgumentException")
        void shouldReturnBadRequestWhenIllegalArgument() throws Exception {
            // Arrange — legacy null-mode group: no flip, action runs and throws.
            String groupId = "999";
            when(service.findById(groupId)).thenReturn(BotGroup.builder().id(groupId).build());
            doThrow(new IllegalArgumentException("Not found")).when(behaviorService).stop(groupId);

            // Act & Assert
            mockMvc.perform(post("/api/v1/bot-group/{id}/stop", groupId))
                    .andExpect(status().isBadRequest());

            verify(service, never()).setActivationMode(any(BotGroup.class), any());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/bot-group/{id}/restart")
    class RestartTests {

        @Test
        @DisplayName("Accepts the restart with 200 + a STARTING DTO, carrying the last start error")
        void shouldReturnOkWithStartingDto() throws Exception {
            String groupId = "123";
            when(service.findById(groupId))
                    .thenReturn(BotGroup.builder().id(groupId).name("Group").botCount(20).build());
            when(behaviorService.restartAsync(eq(groupId), eq(StartOrigin.REST), any()))
                    .thenReturn(BotGroupStatus.STARTING);
            // A previous restart that produced zero bots used to be a 500; it is now readable
            // here, because the exception happens long after the response (AD-15).
            when(behaviorService.getLastStartError(groupId))
                    .thenReturn("java.lang.IllegalStateException: Restart of group 123 produced 0/20 bots");

            mockMvc.perform(post("/api/v1/bot-group/{id}/restart", groupId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.actualStatus").value("STARTING"))
                    .andExpect(jsonPath("$.botCount").value(20))
                    .andExpect(jsonPath("$.lastError").value(
                            org.hamcrest.Matchers.containsString("produced 0/20 bots")));

            verify(behaviorService).restartAsync(eq(groupId), eq(StartOrigin.REST), any());
            verify(behaviorService, never()).restart(anyString());
        }

        @Test
        @DisplayName("A restart never parks a SCHEDULED group as MANUAL_ON")
        void restartDoesNotFlipActivationMode() throws Exception {
            String groupId = "123";
            when(service.findById(groupId)).thenReturn(
                    BotGroup.builder().id(groupId).activationMode(ActivationMode.SCHEDULED).build());
            when(behaviorService.restartAsync(eq(groupId), eq(StartOrigin.REST), any()))
                    .thenReturn(BotGroupStatus.STARTING);

            mockMvc.perform(post("/api/v1/bot-group/{id}/restart", groupId))
                    .andExpect(status().isOk());

            // Unchanged from the synchronous version: a restart is not a statement about whether
            // the group should be running (TIMED_ACTIVATION AD-4).
            verify(service, never()).setActivationMode(any(BotGroup.class), any());
        }

        @Test
        @DisplayName("404 for an unknown id stays synchronous")
        void unknownIdIsStillSynchronous() throws Exception {
            String groupId = "999";
            when(service.findById(groupId)).thenThrow(new ResourceNotFoundException("BotGroup " + groupId + " not found"));

            mockMvc.perform(post("/api/v1/bot-group/{id}/restart", groupId))
                    .andExpect(status().isNotFound());

            verify(behaviorService, never()).restartAsync(anyString(), any(), any());
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

        /**
         * The shape of the three nullable progress fields for a group that has not been started
         * in this JVM (GATEWAY_REQUEST_BUDGET A1). All three are absent rather than {@code 0}:
         * "no progress to report" and "zero bots came up" are different answers, and
         * {@code registeredCount} is reserved for asynchronous registration (Phase 4) — a client
         * that starts reading it now must not be handed a zero that looks like a fact.
         * {@code botCount} is the one that is always present, because it is the denominator for
         * both senses.
         */
        @Test
        @DisplayName("botsUp / registeredCount / lastError are absent, not zero, for a never-started group")
        void progressFieldsAreAbsentUntilThereIsProgress() throws Exception {
            String groupId = "123";
            when(service.findById(groupId)).thenReturn(BotGroup.builder()
                    .id(groupId).name("Fresh Group").botCount(40).build());
            when(behaviorService.getActualStatus(groupId)).thenReturn(BotGroupStatus.STOPPED);
            when(behaviorService.getStartBotsUp(groupId)).thenReturn(null);
            when(behaviorService.getLastStartError(groupId)).thenReturn(null);

            mockMvc.perform(get("/api/v1/bot-group/{id}/status", groupId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.botCount").value(40))
                    .andExpect(jsonPath("$.botsUp").doesNotExist())
                    .andExpect(jsonPath("$.lastError").doesNotExist())
                    // Phase 4 populates this from an additive document field; until then it must
                    // stay null so the response shape does not change again when it lands.
                    .andExpect(jsonPath("$.registeredCount").doesNotExist());
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

    @Nested
    @DisplayName("POST /{id}/registration/retry (GATEWAY_REQUEST_BUDGET A2.6)")
    class RegistrationRetryEndpoint {

        @Test
        @DisplayName("200 + BotGroupStatusDTO carrying the progress to resume from")
        void retryAnswersTheStatusDto() throws Exception {
            String groupId = "123";
            BotGroup resumed = BotGroup.builder()
                    .id(groupId).name("Tai Xiu 500").botCount(500).registeredCount(63)
                    .registrationState("PENDING")
                    .build();
            when(service.retryRegistration(groupId)).thenReturn(resumed);
            when(behaviorService.getActualStatus(groupId)).thenReturn(BotGroupStatus.STOPPED);

            mockMvc.perform(post("/api/v1/bot-group/{id}/registration/retry", groupId))
                    .andExpect(status().isOk())
                    // The derived status, so this endpoint and GET /{id} cannot disagree about a
                    // group that is registering (A1: the two registration constants are produced
                    // at the DTO boundary and nowhere else).
                    .andExpect(jsonPath("$.targetStatus").value("REGISTRATION_PENDING"))
                    .andExpect(jsonPath("$.registeredCount").value(63))
                    .andExpect(jsonPath("$.botCount").value(500));
        }

        @Test
        @DisplayName("the 400 from a group that is not FAILED is answered synchronously")
        void retryOfAHealthyGroupIsABadRequest() throws Exception {
            String groupId = "123";
            when(service.retryRegistration(groupId))
                    .thenThrow(new BadRequestException("Bot group 'G' is not in REGISTRATION_FAILED"));

            mockMvc.perform(post("/api/v1/bot-group/{id}/registration/retry", groupId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a retry never parks a SCHEDULED group as MANUAL_ON")
        void retryIsActivationModeNeutral() throws Exception {
            // Same reasoning that keeps /restart mode-neutral (TIMED_ACTIVATION AD-4): retrying a
            // registration says nothing about whether the group should be running, and parking it
            // MANUAL_ON would silently take it off its schedule.
            String groupId = "123";
            when(service.retryRegistration(groupId)).thenReturn(
                    BotGroup.builder().id(groupId).name("S").botCount(10)
                            .activationMode(ActivationMode.SCHEDULED)
                            .registrationState("PENDING").build());

            mockMvc.perform(post("/api/v1/bot-group/{id}/registration/retry", groupId))
                    .andExpect(status().isOk());

            verify(service, never()).setActivationMode(any(BotGroup.class), any(ActivationMode.class));
        }
    }
}