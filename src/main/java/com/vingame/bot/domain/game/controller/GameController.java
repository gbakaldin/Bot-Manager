package com.vingame.bot.domain.game.controller;

import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.dto.GameDTO;
import com.vingame.bot.domain.game.dto.GameTypeDTO;
import com.vingame.bot.domain.game.mapper.GameMapper;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameFilter;
import com.vingame.bot.domain.game.model.GameType;

import java.util.Arrays;
import com.vingame.bot.domain.game.service.GameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

/**
 * Exception handling is delegated to
 * {@link com.vingame.bot.common.exception.RestExceptionHandler}.
 */
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
            summary = "List supported game types",
            description = "Returns each GameType enum value paired with a human-readable label. Frontend uses this to populate the game-type selector before creating a new Game.")
    @GetMapping("/types")
    public ResponseEntity<List<GameTypeDTO>> getGameTypes() {
        return ResponseEntity.ok(Arrays.stream(GameType.values()).map(GameTypeDTO::of).toList());
    }

    @Operation(
            summary = "Find game by ID",
            description = "Returns a single game or 404 if not found")
    @GetMapping("/{id}")
    public ResponseEntity<GameDTO> findById(
            @PathVariable @Parameter(description = "ID of the game to retrieve", example = "game-bom-baucua") String id) {
        Game game = service.findById(id);
        return ResponseEntity.ok(mapper.toDTO(game));
    }

    @Operation(
            summary = "Get all games for a brand, product and environment",
            description = "Returns a list of all games available in the specified brand, product and environment")
    @GetMapping("/{brandCode}/{productCode}/{envId}")
    public ResponseEntity<List<GameDTO>> findByBrandProductEnv(
            @PathVariable @Parameter(description = "Brand code", example = "G2") BrandCode brandCode,
            @PathVariable @Parameter(description = "Product code", example = "P_097") ProductCode productCode,
            @PathVariable @Parameter(description = "Environment id", example = "097-staging") String envId) {
        List<GameDTO> dtos = service.findByBrandProductEnv(brandCode, productCode, envId).stream()
                .map(mapper::toDTO)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @Operation(
            summary = "Filter games with given criteria",
            description = "Returns a list of games in the given brand/product/environment matching the filter body")
    @PostMapping("/{brandCode}/{productCode}/{envId}/filter")
    public ResponseEntity<List<GameDTO>> filter(
            @PathVariable @Parameter(description = "Brand code", example = "G2") BrandCode brandCode,
            @PathVariable @Parameter(description = "Product code", example = "P_097") ProductCode productCode,
            @PathVariable @Parameter(description = "Environment id", example = "097-staging") String envId,
            @Parameter(description = "Filter criteria for games")
            @RequestBody GameFilter filter) {
        List<GameDTO> dtos = service.filter(brandCode, productCode, envId, filter).stream()
                .map(mapper::toDTO)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @Operation(
            summary = "Create a new game",
            description = "Creates a new game configuration. Brand, product and environment are taken from the path.")
    @PostMapping("/{brandCode}/{productCode}/{envId}")
    public ResponseEntity<GameDTO> save(
            @PathVariable @Parameter(description = "Brand code", example = "G2") BrandCode brandCode,
            @PathVariable @Parameter(description = "Product code", example = "P_097") ProductCode productCode,
            @PathVariable @Parameter(description = "Environment id", example = "097-staging") String envId,
            @Parameter(description = "Game configuration to create")
            @RequestBody GameDTO gameDTO) {
        Game game = mapper.toEntity(gameDTO);
        game.setBrandCode(brandCode);
        game.setProductCode(productCode);
        game.setEnvironmentId(envId);
        Game saved = service.save(game);
        return ResponseEntity.ok(mapper.toDTO(saved));
    }

    @Operation(
            summary = "Update existing game",
            description = "Updates the game with the provided fields. Only non-null fields in the DTO will be updated.")
    @PatchMapping("/{id}")
    public ResponseEntity<GameDTO> update(
            @PathVariable @Parameter(description = "ID of the game to update", example = "game-bom-baucua") String id,
            @Parameter(description = "Game DTO containing fields to update")
            @RequestBody GameDTO gameDTO) {
        Game updated = service.update(id, gameDTO);
        return ResponseEntity.ok(mapper.toDTO(updated));
    }

    @Operation(
            summary = "Delete game by ID",
            description = "Deletes the game configuration")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable @Parameter(description = "ID of the game to delete", example = "game-bom-baucua") String id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }
}
