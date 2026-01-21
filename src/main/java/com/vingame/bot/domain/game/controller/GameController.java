package com.vingame.bot.domain.game.controller;

import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.environment.model.BrandCode;
import com.vingame.bot.domain.environment.model.ProductCode;
import com.vingame.bot.domain.game.dto.GameDTO;
import com.vingame.bot.domain.game.mapper.GameMapper;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameFilter;
import com.vingame.bot.domain.game.service.GameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("api/v1/game")
public class GameController {

    private final GameService service;
    private final GameMapper mapper;

    @Autowired
    public GameController(GameService service, GameMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Operation(
            summary = "Find game by ID",
            description = "Returns a single game or 404 if not found")
    @GetMapping("/{id}")
    public ResponseEntity<@NotNull GameDTO> findById(
            @PathVariable @Parameter(description = "ID of the game to retrieve", example = "game-bom-baucua") String id) {

        try {
            Game game = service.findById(id);
            return ResponseEntity.ok(mapper.toDTO(game));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(
            summary = "Get all games for a brand and product",
            description = "Returns a list of all games available in the specified brand and product")
    @GetMapping("/{brandCode}/{productCode}")
    public ResponseEntity<@NotNull List<GameDTO>> findByBrandAndProduct(
            @PathVariable @Parameter(description = "Brand code", example = "G2") BrandCode brandCode,
            @PathVariable @Parameter(description = "Product code", example = "P_097") ProductCode productCode) {

        try {
            List<GameDTO> dtos = service.findByBrandAndProduct(brandCode, productCode).stream()
                    .map(mapper::toDTO)
                    .toList();
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(
            summary = "Filter games with given criteria",
            description = "Returns a list of games matching the provided filter criteria")
    @PostMapping("/filter/")
    public ResponseEntity<@NotNull List<GameDTO>> filter(
            @Parameter(description = "Filter criteria for games")
            @RequestBody GameFilter filter) {

        try {
            List<GameDTO> dtos = service.filter(filter).stream()
                    .map(mapper::toDTO)
                    .toList();
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(
            summary = "Create a new game",
            description = "Creates a new game configuration. Requires brandCode and productCode.")
    @PostMapping("/{brandCode}/{productCode}")
    public ResponseEntity<@NotNull GameDTO> save(
            @PathVariable @Parameter(description = "Brand code", example = "G2") BrandCode brandCode,
            @PathVariable @Parameter(description = "Product code", example = "P_097") ProductCode productCode,
            @Parameter(description = "Game configuration to create")
            @RequestBody GameDTO gameDTO) {

        try {
            Game game = mapper.toEntity(gameDTO);
            game.setBrandCode(brandCode);
            game.setProductCode(productCode);
            Game saved = service.save(game);
            return ResponseEntity.ok(mapper.toDTO(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(
            summary = "Update existing game",
            description = "Updates the game with the provided fields. Only non-null fields in the DTO will be updated.")
    @PatchMapping("/{id}")
    public ResponseEntity<@NotNull GameDTO> update(
            @PathVariable @Parameter(description = "ID of the game to update", example = "game-bom-baucua") String id,
            @Parameter(description = "Game DTO containing fields to update")
            @RequestBody GameDTO gameDTO) {

        try {
            Game updated = service.update(id, gameDTO);
            return ResponseEntity.ok(mapper.toDTO(updated));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(
            summary = "Delete game by ID",
            description = "Deletes the game configuration")
    @DeleteMapping("/{id}")
    public ResponseEntity<@NotNull Void> delete(
            @PathVariable @Parameter(description = "ID of the game to delete", example = "game-bom-baucua") String id) {

        try {
            service.delete(id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
