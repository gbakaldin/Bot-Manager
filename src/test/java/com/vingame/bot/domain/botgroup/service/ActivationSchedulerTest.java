package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.ActivationWindow;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the {@link ActivationScheduler} reconciler shell (TIMED_ACTIVATION
 * AD-8, AD-11 piece #3). Exercises the wiring between the pure
 * {@code ActivationEvaluator.decide} outcome and the {@code start()/stop()} lifecycle
 * calls, DEAD detection from both the persisted {@code targetStatus} and the live
 * runtime {@code actualStatus}, and per-group error isolation within a tick.
 *
 * <p>The pure predicate/decision is exhaustively covered by
 * {@code ActivationWindowTest} / {@code ActivationEvaluatorTest}; here we only assert
 * the scheduler's Spring/Mongo/service wiring. {@code reconcileAll()} reads the real
 * clock ({@code Instant.now()}), so windows are built relative to "now" (±1h) to make
 * open/closed deterministic and non-flaky regardless of wall-clock time or midnight.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivationScheduler.reconcileAll")
class ActivationSchedulerTest {

    private static final String ZONE = "Asia/Ho_Chi_Minh";
    private static final ZoneId ZONE_ID = ZoneId.of(ZONE);

    @Mock
    private BotGroupRepository repository;

    @Mock
    private BotGroupBehaviorService behaviorService;

    private ActivationScheduler scheduler() {
        // Do NOT call @PostConstruct start() — that would spin a real reconciler
        // thread. We drive reconcileAll() directly.
        return new ActivationScheduler(repository, behaviorService, ZONE, 60L);
    }

    /** A window that straddles the current instant (open now), all days. */
    private static ActivationWindow openNowWindow() {
        LocalTime t = ZonedDateTime.now(ZONE_ID).toLocalTime();
        return ActivationWindow.builder()
                .from(t.minusHours(1))
                .to(t.plusHours(1))
                .days(Set.of())
                .build();
    }

    /** A window an hour in the future (closed now), all days. */
    private static ActivationWindow closedNowWindow() {
        LocalTime t = ZonedDateTime.now(ZONE_ID).toLocalTime();
        return ActivationWindow.builder()
                .from(t.plusHours(1))
                .to(t.plusHours(2))
                .days(Set.of())
                .build();
    }

    private static BotGroup scheduledGroup(String id, ActivationWindow window, BotGroupStatus targetStatus) {
        return BotGroup.builder()
                .id(id)
                .environmentId("env-1")
                .activationMode(ActivationMode.SCHEDULED)
                .activationWindow(window)
                .targetStatus(targetStatus)
                .build();
    }

    @Test
    @DisplayName("open window + not running → START (calls behaviorService.start)")
    void openNotRunningStarts() {
        BotGroup g = scheduledGroup("g1", openNowWindow(), BotGroupStatus.STOPPED);
        when(repository.findByActivationMode(ActivationMode.SCHEDULED)).thenReturn(List.of(g));
        when(behaviorService.isGroupRunning("g1")).thenReturn(false);
        when(behaviorService.getActualStatus("g1")).thenReturn(BotGroupStatus.STOPPED);

        scheduler().reconcileAll();

        verify(behaviorService).start("g1");
        verify(behaviorService, never()).stop(anyString());
    }

    @Test
    @DisplayName("closed window + running → STOP (calls behaviorService.stop)")
    void closedRunningStops() {
        BotGroup g = scheduledGroup("g1", closedNowWindow(), BotGroupStatus.ACTIVE);
        when(repository.findByActivationMode(ActivationMode.SCHEDULED)).thenReturn(List.of(g));
        when(behaviorService.isGroupRunning("g1")).thenReturn(true);
        when(behaviorService.getActualStatus("g1")).thenReturn(BotGroupStatus.ACTIVE);

        scheduler().reconcileAll();

        verify(behaviorService).stop("g1");
        verify(behaviorService, never()).start(anyString());
    }

    @Test
    @DisplayName("open window + already running → NONE (no start/stop)")
    void openRunningConvergedNoOp() {
        BotGroup g = scheduledGroup("g1", openNowWindow(), BotGroupStatus.ACTIVE);
        when(repository.findByActivationMode(ActivationMode.SCHEDULED)).thenReturn(List.of(g));
        when(behaviorService.isGroupRunning("g1")).thenReturn(true);
        when(behaviorService.getActualStatus("g1")).thenReturn(BotGroupStatus.ACTIVE);

        scheduler().reconcileAll();

        verify(behaviorService, never()).start(anyString());
        verify(behaviorService, never()).stop(anyString());
    }

    @Test
    @DisplayName("DEAD via persisted targetStatus → NONE even with an open window (AD-9)")
    void deadPersistedNeverStarts() {
        BotGroup g = scheduledGroup("g1", openNowWindow(), BotGroupStatus.DEAD);
        when(repository.findByActivationMode(ActivationMode.SCHEDULED)).thenReturn(List.of(g));
        when(behaviorService.isGroupRunning("g1")).thenReturn(false);
        // getActualStatus is short-circuited by the persisted-DEAD check (||), so
        // it is deliberately not stubbed here.

        scheduler().reconcileAll();

        verify(behaviorService, never()).start(anyString());
        verify(behaviorService, never()).stop(anyString());
    }

    @Test
    @DisplayName("DEAD via live runtime actualStatus → NONE even with an open window (AD-9)")
    void deadRuntimeNeverStarts() {
        // Persisted target still ACTIVE, but the live runtime is DEAD.
        BotGroup g = scheduledGroup("g1", openNowWindow(), BotGroupStatus.ACTIVE);
        when(repository.findByActivationMode(ActivationMode.SCHEDULED)).thenReturn(List.of(g));
        when(behaviorService.isGroupRunning("g1")).thenReturn(false);
        when(behaviorService.getActualStatus("g1")).thenReturn(BotGroupStatus.DEAD);

        scheduler().reconcileAll();

        verify(behaviorService, never()).start(anyString());
        verify(behaviorService, never()).stop(anyString());
    }

    @Test
    @DisplayName("one group failing does not abort the tick — the next group is still reconciled")
    void perGroupErrorIsolation() {
        BotGroup bad = scheduledGroup("bad", openNowWindow(), BotGroupStatus.STOPPED);
        BotGroup good = scheduledGroup("good", openNowWindow(), BotGroupStatus.STOPPED);
        when(repository.findByActivationMode(ActivationMode.SCHEDULED))
                .thenReturn(List.of(bad, good));

        // First group blows up mid-reconcile; second must still be processed.
        when(behaviorService.isGroupRunning("bad")).thenThrow(new RuntimeException("boom"));
        when(behaviorService.isGroupRunning("good")).thenReturn(false);
        when(behaviorService.getActualStatus("good")).thenReturn(BotGroupStatus.STOPPED);

        scheduler().reconcileAll();

        verify(behaviorService).start("good");
    }

    @Test
    @DisplayName("no scheduled groups → no lifecycle calls")
    void noScheduledGroupsNoOp() {
        when(repository.findByActivationMode(ActivationMode.SCHEDULED)).thenReturn(List.of());

        scheduler().reconcileAll();

        verify(behaviorService, never()).start(anyString());
        verify(behaviorService, never()).stop(anyString());
    }
}
