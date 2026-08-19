package com.vingame.bot.domain.logging.controller;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.common.logging.ScopedDebugRegistry;
import com.vingame.bot.domain.logging.dto.ScopedDebugDTO;
import com.vingame.bot.domain.logging.dto.ScopedDebugStatusDTO;
import com.vingame.bot.infrastructure.logging.ScopedDebugInstaller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scoped per-group DEBUG (LOG_VOLUME_TIERING Phase 2, AD-11).
 * <ul>
 *   <li>{@code POST   /debug/{botGroupId}?minutes=N} — surface this group's DEBUG for N
 *       minutes. The TTL is <b>mandatory</b>; N defaults and is capped by configuration.</li>
 *   <li>{@code DELETE /debug/{botGroupId}} — turn it off early.</li>
 *   <li>{@code GET    /debug} — the policy and the windows currently open.</li>
 * </ul>
 * <b>Why this exists rather than {@code /actuator/loggers}.</b> That endpoint still works
 * and is still the escape hatch, but it sets a <em>global</em> level: on a ten-environment
 * production instance {@code {"configuredLevel":"DEBUG"}} is a ~5 GB/hour action, and it is
 * the shape that filled the disk on 2026-06-30. This endpoint costs one group's worth of
 * volume and turns itself off.
 * <p>
 * <b>Scope, not level.</b> It raises verbosity for a group; it cannot lower it, cannot
 * silence anything, and cannot reach TRACE (the raw WS frame dumps stay a deliberate global
 * action — see {@code ScopedDebugFilter}).
 * <p>
 * Exposure mirrors {@code /api/v1/metrics/**} and {@code /api/v1/alerts/**}: unauthenticated.
 * It cannot leak data, but it can be used to generate load, and it is folded into the
 * existing Spring Security + Keycloak item rather than solved here.
 */
@Slf4j
@RestController
@RequestMapping("api/v1/logging")
public class LogLevelController {

    private final ScopedDebugRegistry registry;
    private final ScopedDebugInstaller installer;
    private final int defaultMinutes;
    private final int maxMinutes;

    public LogLevelController(ScopedDebugRegistry registry,
                              ScopedDebugInstaller installer,
                              @Value("${bot.logging.scoped-debug.default-minutes:15}") int defaultMinutes,
                              @Value("${bot.logging.scoped-debug.max-minutes:120}") int maxMinutes) {
        this.registry = registry;
        this.installer = installer;
        this.defaultMinutes = defaultMinutes;
        this.maxMinutes = maxMinutes;
    }

    @Operation(
            summary = "Surface one bot group's DEBUG logging for a bounded time",
            description = "Raises this group's lines to DEBUG without changing the level for "
                    + "anything else, for `minutes` minutes (default 15, capped by "
                    + "bot.logging.scoped-debug.max-minutes). The window always expires — "
                    + "there is no permanent form. TRACE is not reachable this way.")
    @PostMapping("/debug/{botGroupId}")
    public ResponseEntity<ScopedDebugDTO> enable(
            @PathVariable @Parameter(description = "Bot group id") String botGroupId,
            @RequestParam(required = false) @Parameter(description = "TTL in minutes") Integer minutes) {

        if (!installer.isInstalled()) {
            throw new BadRequestException("Scoped per-group DEBUG is not armed on this instance "
                    + "(bot.logging.scoped-debug.enabled=false, or the com.vingame.bot logger is "
                    + "missing from log4j2.properties)");
        }
        int ttlMinutes = minutes != null ? minutes : defaultMinutes;
        if (ttlMinutes <= 0) {
            throw new BadRequestException("minutes must be greater than 0");
        }
        if (ttlMinutes > maxMinutes) {
            throw new BadRequestException("minutes must not exceed " + maxMinutes
                    + " — scoped DEBUG is a debugging window, not a level change");
        }

        Instant expiresAt = registry.enable(botGroupId, Duration.ofMinutes(ttlMinutes))
                .orElseThrow(() -> new BadRequestException(
                        "Cannot enable scoped DEBUG for group " + botGroupId + ": at most "
                                + registry.getMaxScopes() + " groups may be scoped at once"));

        // INFO, deliberately: turning verbosity up is a group-level lifecycle event, and the
        // one line that explains a sudden change in this instance's log volume.
        log.info("scoped debug enabled for group {} by operator request — expires {}",
                botGroupId, expiresAt);
        return ResponseEntity.ok(new ScopedDebugDTO(botGroupId, expiresAt));
    }

    @Operation(summary = "Turn a group's scoped DEBUG off before its TTL expires")
    @DeleteMapping("/debug/{botGroupId}")
    public ResponseEntity<Void> disable(@PathVariable String botGroupId) {
        if (!registry.disable(botGroupId)) {
            throw new ResourceNotFoundException("No active scoped DEBUG for group " + botGroupId);
        }
        log.info("scoped debug disabled for group {} by operator request", botGroupId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Active scoped-DEBUG windows",
            description = "Whether the mechanism is armed, the TTL policy, and every group "
                    + "currently scoped — including groups armed by auto-escalation.")
    @GetMapping("/debug")
    public ResponseEntity<ScopedDebugStatusDTO> list() {
        List<ScopedDebugDTO> scopes = new ArrayList<>();
        for (Map.Entry<String, Instant> entry : registry.activeScopes().entrySet()) {
            scopes.add(new ScopedDebugDTO(entry.getKey(), entry.getValue()));
        }
        return ResponseEntity.ok(new ScopedDebugStatusDTO(
                installer.isInstalled(), defaultMinutes, maxMinutes, registry.getMaxScopes(), scopes));
    }
}
