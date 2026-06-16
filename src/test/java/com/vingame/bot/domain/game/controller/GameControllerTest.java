package com.vingame.bot.domain.game.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.dto.GameDTO;
import com.vingame.bot.domain.game.mapper.GameMapper;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameFilter;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.domain.game.service.GameService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GameController.class)
@DisplayName("GameController")
class GameControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GameService service;

    @MockitoBean
    private GameMapper mapper;

    @Nested
    @DisplayName("GET /api/v1/game/{id}")
    class GetByIdTests {

        @Test
        @DisplayName("Should return 200 OK when game exists")
        void shouldReturnOkWhenGameExists() throws Exception {
            // Arrange
            String gameId = "game-bom-baucua";
            Game game = Game.builder()
                    .id(gameId)
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCua")
                    .gameType(GameType.BETTING_MINI)
                    .pluginName("MiniGame3")
                    .gameId(3)
                    .numberOfOptions(6)
                    .offset(2000)
                    .md5(false)
                    .build();

            GameDTO dto = GameDTO.builder()
                    .id(gameId)
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCua")
                    .gameType(GameType.BETTING_MINI)
                    .pluginName("MiniGame3")
                    .gameId(3)
                    .numberOfOptions(6)
                    .offset(2000)
                    .md5(false)
                    .build();

            when(service.findById(gameId)).thenReturn(game);
            when(mapper.toDTO(game)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(get("/api/v1/game/{id}", gameId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(gameId))
                    .andExpect(jsonPath("$.name").value("BauCua"))
                    .andExpect(jsonPath("$.brandCode").value("G2"))
                    .andExpect(jsonPath("$.productCode.code").value("097"))
                    .andExpect(jsonPath("$.gameType").value("BETTING_MINI"))
                    .andExpect(jsonPath("$.pluginName").value("MiniGame3"))
                    .andExpect(jsonPath("$.numberOfOptions").value(6))
                    .andExpect(jsonPath("$.offset").value(2000))
                    .andExpect(jsonPath("$.md5").value(false));
        }

        @Test
        @DisplayName("Should return 404 Not Found when game does not exist")
        void shouldReturnNotFoundWhenGameDoesNotExist() throws Exception {
            // Arrange
            String gameId = "missing";
            when(service.findById(gameId)).thenThrow(new ResourceNotFoundException("Game not found"));

            // Act & Assert
            mockMvc.perform(get("/api/v1/game/{id}", gameId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when ID is invalid")
        void shouldReturnBadRequestWhenIdIsInvalid() throws Exception {
            // Arrange
            String gameId = "invalid";
            when(service.findById(gameId)).thenThrow(new IllegalArgumentException("Invalid ID"));

            // Act & Assert
            mockMvc.perform(get("/api/v1/game/{id}", gameId))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/game/{brandCode}/{productCode}")
    class FindByBrandAndProductTests {

        @Test
        @DisplayName("Should return 200 OK with list of games for given brand and product")
        void shouldReturnOkWithListOfGames() throws Exception {
            // Arrange
            Game game1 = Game.builder()
                    .id("g1")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCua")
                    .build();

            Game game2 = Game.builder()
                    .id("g2")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCuaMini")
                    .build();

            GameDTO dto1 = GameDTO.builder()
                    .id("g1")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCua")
                    .build();

            GameDTO dto2 = GameDTO.builder()
                    .id("g2")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCuaMini")
                    .build();

            when(service.findByBrandAndProduct(BrandCode.G2, ProductCode.P_097))
                    .thenReturn(List.of(game1, game2));
            when(mapper.toDTO(game1)).thenReturn(dto1);
            when(mapper.toDTO(game2)).thenReturn(dto2);

            // Act & Assert
            mockMvc.perform(get("/api/v1/game/{brandCode}/{productCode}", "G2", "P_097"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].id").value("g1"))
                    .andExpect(jsonPath("$[0].name").value("BauCua"))
                    .andExpect(jsonPath("$[1].id").value("g2"))
                    .andExpect(jsonPath("$[1].name").value("BauCuaMini"));
        }

        @Test
        @DisplayName("Should return 200 OK with empty list when no games found")
        void shouldReturnOkWithEmptyListWhenNoGames() throws Exception {
            // Arrange
            when(service.findByBrandAndProduct(BrandCode.G4, ProductCode.P_118))
                    .thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(get("/api/v1/game/{brandCode}/{productCode}", "G4", "P_118"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("Should return 500 Internal Server Error when service throws")
        void shouldReturnInternalServerErrorWhenServiceThrows() throws Exception {
            // Arrange
            when(service.findByBrandAndProduct(BrandCode.G2, ProductCode.P_097))
                    .thenThrow(new RuntimeException("boom"));

            // Act & Assert
            mockMvc.perform(get("/api/v1/game/{brandCode}/{productCode}", "G2", "P_097"))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/game/filter/")
    class FilterTests {

        @Test
        @DisplayName("Should return 200 OK with filtered games")
        void shouldReturnOkWithFilteredGames() throws Exception {
            // Arrange
            GameFilter filter = GameFilter.builder()
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .gameType(GameType.BETTING_MINI)
                    .name("Bau")
                    .build();

            Game match = Game.builder()
                    .id("g1")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCua")
                    .gameType(GameType.BETTING_MINI)
                    .build();

            GameDTO dto = GameDTO.builder()
                    .id("g1")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCua")
                    .gameType(GameType.BETTING_MINI)
                    .build();

            when(service.filter(any(GameFilter.class))).thenReturn(List.of(match));
            when(mapper.toDTO(match)).thenReturn(dto);

            // Act & Assert
            mockMvc.perform(post("/api/v1/game/filter/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(filter)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value("g1"))
                    .andExpect(jsonPath("$[0].name").value("BauCua"));
        }

        @Test
        @DisplayName("Should return 200 OK with empty list when no matches")
        void shouldReturnOkWithEmptyListWhenNoMatches() throws Exception {
            // Arrange
            GameFilter filter = GameFilter.builder().name("Nonexistent").build();

            when(service.filter(any(GameFilter.class))).thenReturn(List.of());

            // Act & Assert
            mockMvc.perform(post("/api/v1/game/filter/")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(filter)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Nested
    @DisplayName("POST /api/v1/game/{brandCode}/{productCode}")
    class CreateTests {

        @Test
        @DisplayName("Should return 200 OK with created game and set brand/product from path")
        void shouldReturnOkWithCreatedGame() throws Exception {
            // Arrange — body intentionally omits brandCode/productCode; controller must inject from path.
            GameDTO inputDto = GameDTO.builder()
                    .name("BauCua")
                    .gameType(GameType.BETTING_MINI)
                    .pluginName("MiniGame3")
                    .gameId(3)
                    .numberOfOptions(6)
                    .offset(2000)
                    .md5(false)
                    .build();

            Game entityFromMapper = Game.builder()
                    .name("BauCua")
                    .gameType(GameType.BETTING_MINI)
                    .pluginName("MiniGame3")
                    .gameId(3)
                    .numberOfOptions(6)
                    .offset(2000)
                    .md5(false)
                    .build();

            Game savedEntity = Game.builder()
                    .id("new-id-123")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCua")
                    .gameType(GameType.BETTING_MINI)
                    .pluginName("MiniGame3")
                    .gameId(3)
                    .numberOfOptions(6)
                    .offset(2000)
                    .md5(false)
                    .build();

            GameDTO outputDto = GameDTO.builder()
                    .id("new-id-123")
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCua")
                    .gameType(GameType.BETTING_MINI)
                    .build();

            when(mapper.toEntity(any(GameDTO.class))).thenReturn(entityFromMapper);
            when(service.save(any(Game.class))).thenReturn(savedEntity);
            when(mapper.toDTO(savedEntity)).thenReturn(outputDto);

            // Act & Assert
            mockMvc.perform(post("/api/v1/game/{brandCode}/{productCode}", "G2", "P_097")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(inputDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("new-id-123"))
                    .andExpect(jsonPath("$.name").value("BauCua"))
                    .andExpect(jsonPath("$.brandCode").value("G2"))
                    .andExpect(jsonPath("$.productCode.code").value("097"));

            // Verify controller set brandCode/productCode from path before passing to service.save
            ArgumentCaptor<Game> captor = ArgumentCaptor.forClass(Game.class);
            verify(service).save(captor.capture());
            Game passedToSave = captor.getValue();
            assertThat(passedToSave.getBrandCode()).isEqualTo(BrandCode.G2);
            assertThat(passedToSave.getProductCode()).isEqualTo(ProductCode.P_097);
        }

        @Test
        @DisplayName("Should return 400 Bad Request when save throws IllegalArgumentException")
        void shouldReturnBadRequestWhenSaveThrowsIllegalArgument() throws Exception {
            // Arrange
            GameDTO inputDto = GameDTO.builder().name("BadGame").build();
            Game entity = Game.builder().name("BadGame").build();

            when(mapper.toEntity(any(GameDTO.class))).thenReturn(entity);
            when(service.save(any(Game.class))).thenThrow(new IllegalArgumentException("Invalid game"));

            // Act & Assert
            mockMvc.perform(post("/api/v1/game/{brandCode}/{productCode}", "G2", "P_097")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(inputDto)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 500 Internal Server Error when save fails unexpectedly")
        void shouldReturnInternalServerErrorWhenSaveFails() throws Exception {
            // Arrange
            GameDTO inputDto = GameDTO.builder().name("Game").build();
            Game entity = Game.builder().name("Game").build();

            when(mapper.toEntity(any(GameDTO.class))).thenReturn(entity);
            when(service.save(any(Game.class))).thenThrow(new RuntimeException("DB down"));

            // Act & Assert
            mockMvc.perform(post("/api/v1/game/{brandCode}/{productCode}", "G2", "P_097")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(inputDto)))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/game/{id}")
    class UpdateTests {

        @Test
        @DisplayName("Should return 200 OK with updated game")
        void shouldReturnOkWithUpdatedGame() throws Exception {
            // Arrange
            String gameId = "game-bom-baucua";
            GameDTO updateDto = GameDTO.builder()
                    .name("BauCua Renamed")
                    .numberOfOptions(8)
                    .build();

            Game updatedEntity = Game.builder()
                    .id(gameId)
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCua Renamed")
                    .numberOfOptions(8)
                    .build();

            GameDTO outputDto = GameDTO.builder()
                    .id(gameId)
                    .brandCode(BrandCode.G2)
                    .productCode(ProductCode.P_097)
                    .name("BauCua Renamed")
                    .numberOfOptions(8)
                    .build();

            when(service.update(eq(gameId), any(GameDTO.class))).thenReturn(updatedEntity);
            when(mapper.toDTO(updatedEntity)).thenReturn(outputDto);

            // Act & Assert
            mockMvc.perform(patch("/api/v1/game/{id}", gameId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(gameId))
                    .andExpect(jsonPath("$.name").value("BauCua Renamed"))
                    .andExpect(jsonPath("$.numberOfOptions").value(8));
        }

        @Test
        @DisplayName("Should return 404 Not Found when game does not exist")
        void shouldReturnNotFoundWhenGameDoesNotExist() throws Exception {
            // Arrange
            String gameId = "missing";
            GameDTO updateDto = GameDTO.builder().name("X").build();

            when(service.update(eq(gameId), any(GameDTO.class)))
                    .thenThrow(new ResourceNotFoundException("Game not found"));

            // Act & Assert
            mockMvc.perform(patch("/api/v1/game/{id}", gameId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when update throws IllegalArgumentException")
        void shouldReturnBadRequestWhenIllegalArgument() throws Exception {
            // Arrange
            String gameId = "bad";
            GameDTO updateDto = GameDTO.builder().name("X").build();

            when(service.update(eq(gameId), any(GameDTO.class)))
                    .thenThrow(new IllegalArgumentException("Invalid update"));

            // Act & Assert
            mockMvc.perform(patch("/api/v1/game/{id}", gameId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/v1/game/types")
    class GetGameTypesTests {

        @Test
        @DisplayName("Should return all GameType enum values")
        void shouldReturnAllGameTypeEnumValues() throws Exception {
            mockMvc.perform(get("/api/v1/game/types"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(GameType.values().length))
                    .andExpect(jsonPath("$[*]").value(org.hamcrest.Matchers.hasItem("BETTING_MINI")));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/game/{id}")
    class DeleteTests {

        @Test
        @DisplayName("Should return 200 OK when delete succeeds")
        void shouldReturnOkWhenDeleteSucceeds() throws Exception {
            // Arrange
            String gameId = "game-1";
            doNothing().when(service).delete(gameId);

            // Act & Assert
            mockMvc.perform(delete("/api/v1/game/{id}", gameId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when delete throws IllegalArgumentException")
        void shouldReturnBadRequestWhenIllegalArgument() throws Exception {
            // Arrange — Phase 0 flipped GameController.delete from notFound() to badRequest()
            // for IllegalArgumentException. ResourceNotFoundException is NOT a separate catch in delete().
            String gameId = "bad";
            doThrow(new IllegalArgumentException("Invalid id")).when(service).delete(gameId);

            // Act & Assert
            mockMvc.perform(delete("/api/v1/game/{id}", gameId))
                    .andExpect(status().isBadRequest());
        }
    }
}
