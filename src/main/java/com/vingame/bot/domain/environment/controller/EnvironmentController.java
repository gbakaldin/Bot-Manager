package com.vingame.bot.domain.environment.controller;

import com.vingame.bot.domain.environment.dto.EnvironmentDTO;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.environment.mapper.EnvironmentMapper;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.model.EnvironmentFilter;
import com.vingame.bot.domain.environment.service.EnvironmentService;
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
@RequestMapping("api/v1/environment")
public class EnvironmentController {

    private final EnvironmentService service;
    private final EnvironmentMapper mapper;

    @Autowired
    public EnvironmentController(EnvironmentService environmentService, EnvironmentMapper mapper) {
        this.service = environmentService;
        this.mapper = mapper;
    }

    @Operation(
            summary = "Find environment by ID",
            description = "Returns a single value or 404 if not found or 400 if the ID is malformed")
    @GetMapping("/{id}")
    public ResponseEntity<@NotNull EnvironmentDTO> findById(
            @PathVariable @Parameter(description = "ID of the environment to retrieve", example = "123") String id) {

        try {
            Environment environment = service.findById(id);
            return ResponseEntity.ok(mapper.toDTO(environment));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(
            summary = "Get the full list of created environments",
            description = "Returns a list of all environments, does not support paging yet")
    @GetMapping("/")
    public ResponseEntity<@NotNull List<EnvironmentDTO>> findAll() {
        try {
            List<EnvironmentDTO> dtos = service.findAll().stream()
                    .map(mapper::toDTO)
                    .toList();
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(
            summary = "Filter all environments with given specs",
            description = "Returns a list of all environments containing the provided brand and type values")
    @PostMapping("/filter/")
    public ResponseEntity<@NotNull List<EnvironmentDTO>> filter(
            @Parameter(description = "The filter to query the environments by", example = "N/A") //TODO: pending example
            @RequestBody EnvironmentFilter filter) {

        try {
            List<EnvironmentDTO> dtos = service.filter(filter).stream()
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
            summary = "Create a new environment",
            description = "Returns the freshly created environment complete with the actual id")
    @PostMapping("/")
    public ResponseEntity<@NotNull EnvironmentDTO> save(
            @Parameter(description = "Environment body to save in the database", example = "N/A") //TODO: pending example
            @RequestBody EnvironmentDTO environmentDTO) {
        try {
            Environment environment = mapper.toEntity(environmentDTO);
            Environment saved = service.save(environment);
            return ResponseEntity.ok(mapper.toDTO(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(
            summary = "Update existing environment",
            description = "Returns the new version of the environment updated with the provided fields. Only non-null fields in the DTO will be updated.")
    @PatchMapping("/{id}")
    public ResponseEntity<@NotNull EnvironmentDTO> update(
            @PathVariable @Parameter(description = "ID of the environment to update", example = "123") String id,
            @Parameter(description = "Environment DTO containing the fields that need updating (only non-empty Optional fields will be updated)", example = "N/A") //TODO: pending example
            @RequestBody EnvironmentDTO environmentDTO) {

        try {
            Environment updated = service.update(id, environmentDTO);
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
            summary = "Delete environment record by its id",
            description = "Returns only the HTTP status of the operation")
    @DeleteMapping("/{id}")
    public ResponseEntity<@NotNull Void> delete(
            @PathVariable @Parameter(description = "ID to use for deletion", example = "24") String id) {

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
