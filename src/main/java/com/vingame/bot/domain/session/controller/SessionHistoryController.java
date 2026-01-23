package com.vingame.bot.domain.session.controller;

import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.session.dto.SessionHistoryDTO;
import com.vingame.bot.domain.session.mapper.SessionHistoryMapper;
import com.vingame.bot.domain.session.model.SessionHistory;
import com.vingame.bot.domain.session.service.SessionHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("api/v1/session-history")
public class SessionHistoryController {

    private final SessionHistoryService service;
    private final SessionHistoryMapper mapper;

    @Autowired
    public SessionHistoryController(SessionHistoryService service, SessionHistoryMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Operation(summary = "Find session history by ID")
    @GetMapping("/{id}")
    public ResponseEntity<@NotNull SessionHistoryDTO> findById(
            @PathVariable @Parameter(description = "Session history record ID") String id) {
        try {
            SessionHistory session = service.findById(id);
            return ResponseEntity.ok(mapper.toDTO(session));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Find session history by session ID")
    @GetMapping("/by-session/{sessionId}")
    public ResponseEntity<@NotNull SessionHistoryDTO> findBySessionId(
            @PathVariable @Parameter(description = "Game session ID") String sessionId) {
        try {
            SessionHistory session = service.findBySessionId(sessionId);
            return ResponseEntity.ok(mapper.toDTO(session));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(
            summary = "List session history records",
            description = "Returns all session history records, optionally filtered by gameId and/or environmentId")
    @GetMapping("/")
    public ResponseEntity<@NotNull List<SessionHistoryDTO>> findAll(
            @RequestParam(required = false) @Parameter(description = "Filter by game ID") String gameId,
            @RequestParam(required = false) @Parameter(description = "Filter by environment ID") String environmentId) {
        try {
            List<SessionHistory> sessions;

            if (gameId != null && environmentId != null) {
                sessions = service.findByGameIdAndEnvironmentId(gameId, environmentId);
            } else if (gameId != null) {
                sessions = service.findByGameId(gameId);
            } else if (environmentId != null) {
                sessions = service.findByEnvironmentId(environmentId);
            } else {
                sessions = service.findAll();
            }

            List<SessionHistoryDTO> dtos = sessions.stream()
                    .map(mapper::toDTO)
                    .toList();
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "List jackpot sessions")
    @GetMapping("/jackpots")
    public ResponseEntity<@NotNull List<SessionHistoryDTO>> findJackpotSessions() {
        try {
            List<SessionHistoryDTO> dtos = service.findJackpotSessions().stream()
                    .map(mapper::toDTO)
                    .toList();
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @Operation(summary = "Delete session history record")
    @DeleteMapping("/{id}")
    public ResponseEntity<@NotNull Void> delete(
            @PathVariable @Parameter(description = "Session history record ID") String id) {
        try {
            service.delete(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
