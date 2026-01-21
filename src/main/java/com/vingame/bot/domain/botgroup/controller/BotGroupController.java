package com.vingame.bot.domain.botgroup.controller;

import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.dto.BotGroupStatusDTO;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapper;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupFilter;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.BotGroupService;
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

import java.time.LocalDateTime;
import java.util.List;

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
    public ResponseEntity<@NotNull BotGroupDTO> findById(
            @PathVariable @Parameter(description = "ID of the bot group to retrieve", example = "123") int id) {
        try {
            BotGroup botGroup = service.findById(id);
            return ResponseEntity.ok(mapper.toDTO(botGroup));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(
            summary = "Get the full list of created bot groups",
            description = "Returns a list of all bot groups, does not support paging yet")
    @GetMapping("/")
    public ResponseEntity<@NotNull List<BotGroupDTO>> findAll() {
        try {
            List<BotGroupDTO> dtos = service.findAll().stream()
                    .map(mapper::toDTO)
                    .toList();
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(
            summary = "Filter all bot groups with given specs",
            description = "Returns a list of all bot groups containing the provided filter values")
    @PostMapping("/filter/")
    public ResponseEntity<@NotNull List<BotGroupDTO>> filter(
            @Parameter(description = "The filter to query the bot groups by", example = "N/A")
            @RequestBody BotGroupFilter filter) {

        try {
            List<BotGroupDTO> dtos = service.filter(filter).stream()
                    .map(mapper::toDTO)
                    .toList();
            return ResponseEntity.ok(dtos);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(
            summary = "Create a new bot group",
            description = "Returns the freshly created bot group complete with the actual id")
    @PostMapping("/")
    public ResponseEntity<@NotNull BotGroupDTO> save(
            @Parameter(description = "Bot group body to save in the database", example = "N/A")
            @RequestBody BotGroupDTO botGroupDTO) {
        try {
            BotGroup botGroup = mapper.toEntity(botGroupDTO);
            BotGroup saved = service.save(botGroup);
            return ResponseEntity.ok(mapper.toDTO(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(
            summary = "Update existing bot group",
            description = "Returns the new version of the bot group updated with the provided fields. Only non-null fields in the DTO will be updated.")
    @PatchMapping("/{id}")
    public ResponseEntity<@NotNull BotGroupDTO> update(
            @PathVariable @Parameter(description = "ID of the bot group to update", example = "123") int id,
            @Parameter(description = "Bot group DTO containing the fields that need updating (only non-empty Optional fields will be updated)", example = "N/A")
            @RequestBody BotGroupDTO botGroupDTO) {

        try {
            BotGroup updated = service.update(id, botGroupDTO);
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
            summary = "Delete bot group record by its id",
            description = "Returns only the HTTP status of the operation")
    @DeleteMapping("/{id}")
    public ResponseEntity<@NotNull Void> delete(
            @PathVariable @Parameter(description = "ID to use for deletion", example = "24") int id) {

        try {
            service.delete(id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }


    @PostMapping("/{id}/start")
    @Operation(summary = "Start bot group", description = "Starts the bot group with the given ID")
    public ResponseEntity<@NotNull Void> start(@PathVariable int id) {
        try {
            behaviorService.start(id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/{id}/stop")
    @Operation(summary = "Stop bot group", description = "Stops the bot group with the given ID")
    public ResponseEntity<@NotNull Void> stop(@PathVariable int id) {
        try {
            behaviorService.stop(id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/{id}/restart")
    @Operation(summary = "Restart bot group", description = "Restarts the bot group with the given ID")
    public ResponseEntity<@NotNull Void> restart(@PathVariable int id) {
        try {
            behaviorService.restart(id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/{id}/schedule-restart")
    @Operation(summary = "Schedule bot group restart", description = "Schedules a restart for the bot group")
    public ResponseEntity<@NotNull Void> scheduleRestart(
            @PathVariable int id,
            @RequestBody LocalDateTime time) {
        try {
            behaviorService.scheduleRestart(id, time);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{id}/status")
    @Operation(
            summary = "Get bot group runtime status",
            description = "Returns both target status (from database) and actual runtime status")
    public ResponseEntity<@NotNull BotGroupStatusDTO> getStatus(@PathVariable int id) {
        try {
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
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }


}
