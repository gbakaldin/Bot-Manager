package com.vingame.bot.domain.environment.controller;

import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.BotGroupService;
import com.vingame.bot.domain.environment.dto.EnvironmentDTO;
import com.vingame.bot.domain.environment.mapper.EnvironmentMapper;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.model.EnvironmentFilter;
import com.vingame.bot.domain.environment.service.EnvironmentService;
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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Exception handling is delegated to
 * {@link com.vingame.bot.common.exception.RestExceptionHandler}.
 */
@RestController
@RequestMapping("api/v1/environment")
public class EnvironmentController {

    private final EnvironmentService service;
    private final EnvironmentMapper mapper;
    private final BotGroupService botGroupService;
    private final BotGroupBehaviorService botGroupBehaviorService;

    @Autowired
    public EnvironmentController(EnvironmentService environmentService, EnvironmentMapper mapper,
                                 BotGroupService botGroupService, BotGroupBehaviorService botGroupBehaviorService) {
        this.service = environmentService;
        this.mapper = mapper;
        this.botGroupService = botGroupService;
        this.botGroupBehaviorService = botGroupBehaviorService;
    }

    @Operation(
            summary = "Find environment by ID",
            description = "Returns a single value or 404 if not found or 400 if the ID is malformed")
    @GetMapping("/{id}")
    public ResponseEntity<EnvironmentDTO> findById(
            @PathVariable @Parameter(description = "ID of the environment to retrieve", example = "123") String id) {
        Environment environment = service.findById(id);
        EnvironmentDTO dto = mapper.toDTO(environment);
        enrichWithBotGroupStats(dto, botGroupService.findByEnvironmentId(id));
        return ResponseEntity.ok(dto);
    }

    @Operation(
            summary = "Get the full list of created environments",
            description = "Returns a list of all environments, does not support paging yet")
    @GetMapping("/")
    public ResponseEntity<List<EnvironmentDTO>> findAll() {
        List<Environment> environments = service.findAll();
        Map<String, List<BotGroup>> groupsByEnv = botGroupService.findAll().stream()
                .collect(Collectors.groupingBy(BotGroup::getEnvironmentId));

        List<EnvironmentDTO> dtos = environments.stream()
                .map(env -> {
                    EnvironmentDTO dto = mapper.toDTO(env);
                    enrichWithBotGroupStats(dto, groupsByEnv.getOrDefault(env.getId(), List.of()));
                    return dto;
                })
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @Operation(
            summary = "Filter all environments with given specs",
            description = "Returns a list of all environments containing the provided brand and type values")
    @PostMapping("/filter/")
    public ResponseEntity<List<EnvironmentDTO>> filter(
            @Parameter(description = "The filter to query the environments by", example = "N/A") //TODO: pending example
            @RequestBody EnvironmentFilter filter) {
        List<Environment> environments = service.filter(filter);
        Map<String, List<BotGroup>> groupsByEnv = botGroupService.findAll().stream()
                .collect(Collectors.groupingBy(BotGroup::getEnvironmentId));

        List<EnvironmentDTO> dtos = environments.stream()
                .map(env -> {
                    EnvironmentDTO dto = mapper.toDTO(env);
                    enrichWithBotGroupStats(dto, groupsByEnv.getOrDefault(env.getId(), List.of()));
                    return dto;
                })
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @Operation(
            summary = "Create a new environment",
            description = "Returns the freshly created environment complete with the actual id")
    @PostMapping("/")
    public ResponseEntity<EnvironmentDTO> save(
            @Parameter(description = "Environment body to save in the database", example = "N/A") //TODO: pending example
            @RequestBody EnvironmentDTO environmentDTO) {
        Environment environment = mapper.toEntity(environmentDTO);
        Environment saved = service.save(environment);
        return ResponseEntity.ok(mapper.toDTO(saved));
    }

    @Operation(
            summary = "Update existing environment",
            description = "Returns the new version of the environment updated with the provided fields. Only non-null fields in the DTO will be updated.")
    @PatchMapping("/{id}")
    public ResponseEntity<EnvironmentDTO> update(
            @PathVariable @Parameter(description = "ID of the environment to update", example = "123") String id,
            @Parameter(description = "Environment DTO containing the fields that need updating (only non-empty Optional fields will be updated)", example = "N/A") //TODO: pending example
            @RequestBody EnvironmentDTO environmentDTO) {
        Environment updated = service.update(id, environmentDTO);
        return ResponseEntity.ok(mapper.toDTO(updated));
    }

    @Operation(
            summary = "Delete environment record by its id",
            description = "Returns only the HTTP status of the operation")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable @Parameter(description = "ID to use for deletion", example = "24") String id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }

    private void enrichWithBotGroupStats(EnvironmentDTO dto, List<BotGroup> groups) {
        int runningGroups = 0;
        int runningBots = 0;

        for (BotGroup group : groups) {
            if (botGroupBehaviorService.isGroupRunning(group.getId())) {
                runningGroups++;
                runningBots += botGroupBehaviorService.getRunningBotCountForGroup(group.getId());
            }
        }

        dto.setTotalBotGroups(groups.size());
        dto.setTotalBots(groups.stream().mapToInt(BotGroup::getBotCount).sum());
        dto.setRunningBotGroups(runningGroups);
        dto.setRunningBots(runningBots);
    }

}
