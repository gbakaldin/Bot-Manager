package com.vingame.bot.domain.botgroup.controller;

import com.vingame.bot.domain.botgroup.dto.BotGroupDTO;
import com.vingame.bot.domain.botgroup.dto.BotGroupHealthDTO;
import com.vingame.bot.domain.botgroup.dto.BotGroupStatusDTO;
import com.vingame.bot.domain.botgroup.dto.OnCreate;
import com.vingame.bot.domain.botgroup.mapper.BotGroupMapper;
import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupFilter;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.model.StartOrigin;
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
import java.util.function.Function;

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
    @Operation(summary = "Start bot group",
            description = "Accepts a start for the bot group and returns its status immediately. "
                    + "200 means ACCEPTED, not finished: the build runs on a virtual thread and a "
                    + "large group legitimately takes tens of minutes once its gateway requests "
                    + "are paced. Poll GET /{id}/status — actualStatus is STARTING with botsUp "
                    + "climbing toward botCount, then ACTIVE. A second start while one is in "
                    + "flight is accepted and describes the same attempt; it never starts a "
                    + "second build. 404 for an unknown id and 400 for a group with no "
                    + "environment or no game are still answered synchronously.")
    public ResponseEntity<BotGroupStatusDTO> start(@PathVariable String id) {
        return ResponseEntity.ok(runWithManualOverrideAsync(id, ActivationMode.MANUAL_ON,
                onFailure -> behaviorService.startAsync(id, StartOrigin.REST, onFailure)));
    }

    @PostMapping("/{id}/stop")
    @Operation(summary = "Stop bot group", description = "Stops the bot group with the given ID. "
            + "Synchronous, and it wins over a start in flight: the start is cancelled before the "
            + "stop takes the group lock, so a stop never waits out the start it is stopping.")
    public ResponseEntity<Void> stop(@PathVariable String id) {
        runWithManualOverride(id, ActivationMode.MANUAL_OFF, () -> behaviorService.stop(id));
        return ResponseEntity.ok().build();
    }

    /**
     * Operator manual-override integration (TIMED_ACTIVATION AD-4). An operator
     * {@code /start} or {@code /stop} on a <em>scheduled-capable</em> group (one
     * whose {@code activationMode} is non-null) parks it as {@code MANUAL_ON} /
     * {@code MANUAL_OFF} so the next reconciler tick does not undo the action;
     * the group rejoins the schedule only via an explicit PATCH back to
     * {@code SCHEDULED}.
     * <p>
     * The mode flip is persisted <b>before</b> the lifecycle action to close a
     * TOCTOU race with the reconciler: a tick that lands during the action
     * window sees a non-{@code SCHEDULED} mode, resolves to {@code NONE}, and so
     * cannot undo the operator's action. If the action ultimately throws, the
     * prior mode is restored so a failed action leaves no spurious mode change.
     * <p>
     * Legacy, non-timed groups ({@code activationMode == null}) are left
     * completely untouched — no flip, their start/stop semantics are unchanged.
     * <p>
     * This flip lives <b>only</b> at the controller layer (operator-initiated).
     * The reconciler and the {@code onStartup} auto-start path call
     * {@code behaviorService.start/stop} directly and stay mode-neutral — a flip
     * from those paths would defeat scheduling.
     */
    private void runWithManualOverride(String id, ActivationMode manualMode, Runnable action) {
        BotGroup group = service.findById(id);
        if (group.getActivationMode() == null) {
            // Legacy, non-timed group — no mode flip, unchanged behavior.
            action.run();
            return;
        }
        ActivationMode priorMode = group.getActivationMode();
        service.setActivationMode(group, manualMode);
        try {
            action.run();
        } catch (RuntimeException e) {
            // Roll back the flip so a failed action leaves the mode unchanged.
            service.setActivationMode(group, priorMode);
            throw e;
        }
    }

    /**
     * {@link #runWithManualOverride} for an action that only <em>accepts</em> work
     * (GATEWAY_REQUEST_BUDGET AD-15). The rollback has to be handed to the action instead of
     * living in a {@code catch}, because the failure it undoes happens on a virtual thread long
     * after this method has returned — for a paced 3,000-bot start, up to fifty minutes after.
     * <p>
     * Both halves are wired and they cannot both fire: the synchronous {@code catch} covers the
     * validation the accept does on this thread (404, the two 400s), and the {@code onFailure}
     * runnable covers the build. If the synchronous half throws, no task was ever submitted.
     */
    private BotGroupStatusDTO runWithManualOverrideAsync(
            String id, ActivationMode manualMode, Function<Runnable, BotGroupStatus> action) {
        BotGroup group = service.findById(id);
        if (group.getActivationMode() == null) {
            // Legacy, non-timed group — no mode flip, nothing to roll back.
            return statusDTO(group, action.apply(() -> { }));
        }
        ActivationMode priorMode = group.getActivationMode();
        service.setActivationMode(group, manualMode);
        try {
            return statusDTO(group, action.apply(() -> restoreMode(id, priorMode)));
        } catch (RuntimeException e) {
            // The accept itself failed, so no build was submitted and no async rollback will run.
            service.setActivationMode(group, priorMode);
            throw e;
        }
    }

    /**
     * Undo a manual-override flip from the build thread. Re-reads the group rather than reusing
     * the instance the request thread loaded: minutes have passed, and writing a stale document
     * back would silently revert whatever else changed in between.
     */
    private void restoreMode(String id, ActivationMode priorMode) {
        service.setActivationMode(service.findById(id), priorMode);
    }

    @PostMapping("/{id}/restart")
    @Operation(summary = "Restart bot group",
            description = "Accepts a restart and returns the group's status immediately; same "
                    + "200-means-accepted contract as /start. The stop, the pause and the rebuild "
                    + "all run on a virtual thread, so a restart that ends with zero bots is "
                    + "reported as lastError on GET /{id}/status rather than as a 500.")
    public ResponseEntity<BotGroupStatusDTO> restart(@PathVariable String id) {
        // No manual-override flip: a restart is not a statement about whether the group should be
        // running, so it must not park a SCHEDULED group as MANUAL_ON (TIMED_ACTIVATION AD-4).
        // Unchanged from the synchronous version.
        BotGroup group = service.findById(id);
        BotGroupStatus accepted = behaviorService.restartAsync(id, StartOrigin.REST, () -> { });
        return ResponseEntity.ok(statusDTO(group, accepted));
    }

    @PostMapping("/{id}/registration/retry")
    @Operation(summary = "Resume a failed account registration",
            description = "Clears REGISTRATION_FAILED back to REGISTRATION_PENDING and re-queues "
                    + "the group. Registration resumes from registeredCount + 1 — nothing starts "
                    + "over and no account is created twice. 200 means accepted, like /start: "
                    + "poll GET /{id}/status for registeredCount climbing toward botCount. "
                    + "400 if the group is not in REGISTRATION_FAILED, 404 for an unknown id.")
    public ResponseEntity<BotGroupStatusDTO> retryRegistration(@PathVariable String id) {
        // No manual-override flip (contrast /start): retrying a registration says nothing about
        // whether the group should be running, so it must not park a SCHEDULED group as
        // MANUAL_ON — the same reasoning that keeps /restart mode-neutral (TIMED_ACTIVATION AD-4).
        BotGroup group = service.retryRegistration(id);
        return ResponseEntity.ok(statusDTO(group, behaviorService.getActualStatus(id)));
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
        return ResponseEntity.ok(statusDTO(group, behaviorService.getActualStatus(id)));
    }

    /**
     * The one place a {@link BotGroupStatusDTO} is assembled, so the ack returned by
     * {@code /start} and {@code /restart} and the body of {@code /status} cannot drift
     * (GATEWAY_REQUEST_BUDGET A1).
     * <p>
     * {@code actualStatus} is passed in rather than read here: the start path has just been told
     * {@code STARTING} by the service and re-reading it would open a window in which the ack says
     * something else.
     */
    private BotGroupStatusDTO statusDTO(BotGroup group, BotGroupStatus actualStatus) {
        boolean registering = group.getRegistrationState() != null;
        String startError = behaviorService.getLastStartError(group.getId());
        return BotGroupStatusDTO.builder()
                .groupId(group.getId())
                .groupName(group.getName())
                // Derived through the same helper the entity→DTO mapper uses, so /status and
                // GET /{id} cannot disagree about a registering group (A1: the two registration
                // constants are produced at the DTO boundary and nowhere else).
                .targetStatus(BotGroupMapper.renderedStatus(group))
                .actualStatus(actualStatus)
                .playingStatus(behaviorService.getPlayingStatus(group.getId()))
                .botCount(group.getBotCount())
                .botsUp(behaviorService.getStartBotsUp(group.getId()))
                // Null for a group with no registration history at all (legacy, or
                // existingGroup=true), so "absent" keeps meaning "this group was never
                // asynchronously registered" rather than "zero accounts exist".
                .registeredCount(registering || group.getRegisteredCount() > 0
                        ? group.getRegisteredCount() : null)
                .namedCount(registering || group.getNamedCount() > 0
                        ? group.getNamedCount() : null)
                // A start error wins a tie because it is necessarily the newer event: a group
                // cannot be started until its registrationState has cleared.
                .lastError(startError != null ? startError : group.getRegistrationError())
                .build();
    }


}
