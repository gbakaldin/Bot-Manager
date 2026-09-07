package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The shared candidate set (DEAD_GROUP_AUTO_RECOVERY AD-3 / AD-4).
 *
 * <p>{@code RecoveryEligibilityTest} covers the pure predicate; what is asserted
 * here is the <em>join</em> the selector performs — the persisted
 * {@code targetStatus == DEAD} query unioned with the in-memory DEAD runtimes, and
 * the runtime status it derives for each row. Both the probe scheduler and the
 * reconciler go through this one function, so anything wrong here is wrong in both
 * at once.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecoveryCandidateSelector.select (AD-3/AD-4)")
class RecoveryCandidateSelectorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

    @Mock
    private BotGroupRepository repository;

    @Mock
    private BotGroupBehaviorService behaviorService;

    private static BotGroup group(String id, BotGroupStatus target) {
        return BotGroup.builder()
                .id(id).name("group-" + id).environmentId("env-1").botCount(20)
                .targetStatus(target)
                .build();
    }

    private List<String> selectIds() {
        return RecoveryCandidateSelector.select(repository, behaviorService, NOW, ZONE)
                .stream().map(BotGroup::getId).toList();
    }

    @Test
    @DisplayName("a persisted-DEAD row with no runtime is a candidate")
    void persistedDeadRowIsSelected() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD))
                .thenReturn(List.of(group("g1", BotGroupStatus.DEAD)));
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of());

        assertThat(selectIds()).containsExactly("g1");
    }

    @Test
    @DisplayName("a DEAD runtime whose row still says ACTIVE is unioned in (the swallowed-save case, AD-4)")
    void memoryOnlyDeadRuntimeIsUnionedIn() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD)).thenReturn(List.of());
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of("g2"));
        when(repository.findById("g2")).thenReturn(Optional.of(group("g2", BotGroupStatus.ACTIVE)));

        assertThat(selectIds()).containsExactly("g2");
    }

    @Test
    @DisplayName("a DEAD row whose runtime is ACTIVE is excluded — a running group is not dead (AD-3 condition 3)")
    void liveActiveRuntimeBeatsThePersistedDeadRow() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD))
                .thenReturn(List.of(group("g1", BotGroupStatus.DEAD)));
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of());
        when(behaviorService.isGroupRunning("g1")).thenReturn(true);

        assertThat(selectIds()).isEmpty();
    }

    @Test
    @DisplayName("a dead-runtime id with no surviving Mongo row is skipped, not thrown on")
    void deadRuntimeWithNoRowIsSkipped() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD)).thenReturn(List.of());
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of("ghost"));
        when(repository.findById("ghost")).thenReturn(Optional.empty());

        assertThat(selectIds()).isEmpty();
    }

    @Test
    @DisplayName("a group present in both the DEAD query and the dead-runtime set appears exactly once")
    void unionDoesNotDuplicate() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD))
                .thenReturn(List.of(group("g1", BotGroupStatus.DEAD)));
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of("g1"));

        assertThat(selectIds()).containsExactly("g1");
        // The row from the DEAD query is reused: no second read for the same id.
        verify(repository, never()).findById("g1");
    }

    /**
     * The AD-5 opt-out, driven through the <em>selector</em> rather than the
     * predicate — because this union is the path that actually produces the shape.
     * A {@code STOPPED} row never appears in {@code findByTargetStatus(DEAD)}; it
     * enters through {@code listDeadRuntimeGroupIds()}, is re-read by id, and is then
     * evaluated with {@code runtimeStatus = DEAD}. Before the explicit condition-2a
     * veto it satisfied condition 2's second disjunct and was selected, and
     * {@code startForRecovery} re-asserts the same predicate, so nothing downstream
     * caught it either.
     */
    @Test
    @DisplayName("a STOPPED row with a lingering DEAD runtime is never selected (AD-5)")
    void stoppedRowWithDeadRuntimeIsNotSelected() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD)).thenReturn(List.of());
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of("g3"));
        when(repository.findById("g3")).thenReturn(Optional.of(group("g3", BotGroupStatus.STOPPED)));

        assertThat(selectIds())
                .as("the operator opt-out survives a lingering DEAD runtime")
                .isEmpty();
    }

    @Test
    @DisplayName("MANUAL_OFF closes the same gap for timed groups — the operator's Stop also flips the mode")
    void manualOffStoppedRowWithDeadRuntimeIsNotSelected() {
        // BotGroupController.runWithManualOverride flips a non-legacy group to
        // MANUAL_OFF on /stop, so condition 4 rejects it regardless of condition 2.
        // The gap above therefore only reaches legacy (activationMode == null) and
        // MANUAL_ON groups.
        when(repository.findByTargetStatus(BotGroupStatus.DEAD)).thenReturn(List.of());
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of("g4"));
        when(repository.findById("g4")).thenReturn(Optional.of(
                group("g4", BotGroupStatus.STOPPED).toBuilder()
                        .activationMode(ActivationMode.MANUAL_OFF).build()));
        lenient().when(behaviorService.isGroupRunning("g4")).thenReturn(false);

        assertThat(selectIds()).isEmpty();
    }
}
