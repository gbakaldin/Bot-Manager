package com.vingame.bot.domain.botgroup.controller;

import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.dto.BotGroupHealthDTO;
import com.vingame.bot.domain.botgroup.dto.BotGroupStatusDTO;
import com.vingame.bot.domain.botgroup.dto.OnCreate;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapper;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupFilter;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.BotGroupService;
import com.vingame.bot.domain.botgroup.sort.BotSortKey;
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
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Exception handling is delegated to
 * {@link com.vingame.bot.common.exception.RestExceptionHandler} — controllers
 * only express the success path. Service-layer throws of typed exceptions
 * (e.g. {@link com.vingame.bot.common.exception.UpstreamRegistrationException},
 * {@link com.vingame.bot.common.exception.BadRequestException},
 * {@link com.vingame.bot.common.exception.ResourceNotFoundException}) are
 * translated to HTTP responses there.
 */
@RestController
@RequestMapping("api/v1/bot-group")
public class BotGroupController {

    private final BotGroupService service;
    private final BotGroupBehaviorService behaviorService;
    private final BotGroupMapper mapper;

    @Autowired
    public BotGroupController(BotGroupService botGroupService, BotGroupBehaviorService behaviorService, BotGroupMapper mapper) {
        this.service = botGroupService;
        this.behaviorService = behaviorService;
        this.mapper = mapper;
    }

    @Operation(
            summary = "Find bot group by ID",
            description = "Returns a single value or 404 if not found")
    @GetMapping("/{id}")
    public ResponseEntity<BotGroupDTO> findById(
            @PathVariable @Parameter(description = "ID of the bot group to retrieve") String id) {
        BotGroup botGroup = service.findById(id);
        BotGroupDTO dto = mapper.toDTO(botGroup);
        // Group-level runtime statistics (BOTGROUP_GAME_MANAGEMENT Phase 3, AD-13).
        // Enriched here rather than in the mapper — stats are runtime-sourced, not
        // persisted, and must stay off the create/update write surface.
        dto.setStats(behaviorService.computeStats(id));
        return ResponseEntity.ok(dto);
    }

    @Operation(
            summary = "List supported bot-group sort keys",
            description = "Returns the valid sortBy values for the env-scoped bot-group filter, driven off the "
                    + "BotSortKey enum (BOTGROUP_GAME_MANAGEMENT Phase 4/6). Frontend uses this to build the sort "
                    + "dropdown so it cannot drift from the server's accepted keys.")
    @GetMapping("/sort-keys")
    public ResponseEntity<List<String>> getSortKeys() {
        return ResponseEntity.ok(Arrays.stream(BotSortKey.values()).map(Enum::name).toList());
    }

    @Operation(
            summary = "Filter bot groups within an environment",
            description = "Returns the bot groups in the given environment matching the filter body, sorted " +
                    "per the sortBy/sortDir fields. The environment is taken from the path; an empty body " +
                    "returns every group in that environment (default sort CREATED_TIME desc).")
    @PostMapping("/{envId}/filter")
    public ResponseEntity<List<BotGroupDTO>> filter(
            @PathVariable @Parameter(description = "Environment id to scope the filter to") String envId,
            @Parameter(description = "The filter to query the bot groups by")
            @RequestBody BotGroupFilter filter) {
        // Load → enrich with runtime stats → sort in-memory (BOTGROUP_GAME_MANAGEMENT
        // Phase 4 / AD-11). The enriched rows carry the pre-computed Phase 3 stats,
        // so mapping here just embeds them (AD-13) without recomputation.
        List<BotGroupDTO> dtos = behaviorService.filterSorted(envId, filter).stream()
                .map(row -> {
                    BotGroupDTO dto = mapper.toDTO(row.group());
                    dto.setStats(row.stats());
                    return dto;
                })
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @Operation(
            summary = "Create a new bot group",
            description = "Returns the freshly created bot group complete with the actual id. " +
                    "Set existingGroup=true to skip user registration (for migrating existing bots).")
    @PostMapping("/")
    public ResponseEntity<BotGroupDTO> save(
            @Parameter(description = "Bot group body to save in the database")
            @Validated(OnCreate.class) @RequestBody BotGroupDTO botGroupDTO) {
        BotGroup botGroup = mapper.toEntity(botGroupDTO);
        boolean skipRegistration = Boolean.TRUE.equals(botGroupDTO.getExistingGroup());
        BotGroup saved = service.save(botGroup, skipRegistration);
        return ResponseEntity.ok(mapper.toDTO(saved));
    }

    @Operation(
            summary = "Update existing bot group",
            description = "Returns the new version of the bot group updated with the provided fields. Only non-null fields in the DTO will be updated.")
    @PatchMapping("/{id}")
    public ResponseEntity<BotGroupDTO> update(
            @PathVariable @Parameter(description = "ID of the bot group to update") String id,
            @Parameter(description = "Bot group DTO containing the fields that need updating")
            @RequestBody BotGroupDTO botGroupDTO) {
        BotGroup updated = service.update(id, botGroupDTO);
        return ResponseEntity.ok(mapper.toDTO(updated));
    }

    @Operation(
            summary = "Delete bot group record by its id",
            description = "Returns only the HTTP status of the operation")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable @Parameter(description = "ID to use for deletion") String id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }


    @PostMapping("/{id}/start")
    @Operation(summary = "Start bot group", description = "Starts the bot group with the given ID")
    public ResponseEntity<Void> start(@PathVariable String id) {
        behaviorService.start(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/stop")
    @Operation(summary = "Stop bot group", description = "Stops the bot group with the given ID")
    public ResponseEntity<Void> stop(@PathVariable String id) {
        behaviorService.stop(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/restart")
    @Operation(summary = "Restart bot group", description = "Restarts the bot group with the given ID")
    public ResponseEntity<Void> restart(@PathVariable String id) {
        behaviorService.restart(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/schedule-restart")
    @Operation(summary = "Schedule bot group restart", description = "Schedules a restart for the bot group")
    public ResponseEntity<Void> scheduleRestart(
            @PathVariable String id,
            @RequestBody LocalDateTime time) {
        behaviorService.scheduleRestart(id, time);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/health")
    @Operation(
            summary = "Get bot group health details",
            description = "Returns detailed health metrics including per-bot connection status, balances, and bet counters")
    public ResponseEntity<BotGroupHealthDTO> getHealth(@PathVariable String id) {
        BotGroupHealthDTO health = behaviorService.getHealth(id);
        return ResponseEntity.ok(health);
    }

    @GetMapping("/{id}/status")
    @Operation(
            summary = "Get bot group runtime status",
            description = "Returns both target status (from database) and actual runtime status")
    public ResponseEntity<BotGroupStatusDTO> getStatus(@PathVariable String id) {
        BotGroup group = service.findById(id);
        BotGroupStatus actualStatus = behaviorService.getActualStatus(id);
        BotGroupPlayingStatus playingStatus = behaviorService.getPlayingStatus(id);

        BotGroupStatusDTO statusDTO = BotGroupStatusDTO.builder()
                .groupId(group.getId())
                .groupName(group.getName())
                .targetStatus(group.getTargetStatus())
                .actualStatus(actualStatus)
                .playingStatus(playingStatus)
                .build();

        return ResponseEntity.ok(statusDTO);
    }


}
