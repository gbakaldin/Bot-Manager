package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.bot.coordination.BetCoordinator;
import com.vingame.bot.domain.bot.coordination.JackpotScaler;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.core.BotStatus;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.WeightedStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
import com.vingame.bot.config.bot.BotBehaviorConfig;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.domain.botgroup.dto.BotGroupHealthDTO;
import com.vingame.bot.domain.botgroup.dto.BotHealthDTO;
import com.vingame.bot.domain.botgroup.dto.CoordinationStateDTO;
import com.vingame.bot.domain.botgroup.dto.JackpotScaleStateDTO;
import com.vingame.bot.domain.botgroup.dto.RampStateDTO;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.CrowdCountSemantic;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator;
import com.vingame.bot.infrastructure.observability.ScopedDebugEscalator;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import com.vingame.bot.infrastructure.runtime.BotGroupRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BotGroupBehaviorService")
class BotGroupBehaviorServiceTest {

    @Mock
    private BotGroupService botGroupService;

    @Mock
    private EnvironmentService environmentService;

    @Mock
    private GameService gameService;

    @Mock
    private BotFactory botFactory;

    @Mock
    private BotMetrics botMetrics;

    @Mock
    private SessionAggregationService sessionAggregationService;

    @Mock
    private GroupLifecycleAggregator groupLifecycleAggregator;

    @Mock
    private ScopedDebugEscalator scopedDebugEscalator;

    @Captor
    private ArgumentCaptor<BotGroup> botGroupCaptor;

    @InjectMocks
    private BotGroupBehaviorService service;

    @BeforeEach
    void initConfigFields() {
        // @Value-injected fields normally come from application.properties — set them by reflection
        ReflectionTestUtils.setField(service, "deadBotGroupThreshold", 0.80);
        ReflectionTestUtils.setField(service, "botCreationParallelism", 10);
        ReflectionTestUtils.setField(service, "watchdogTimeoutSeconds", 180L);
        ReflectionTestUtils.setField(service, "periodicLogoutEnabled", true);
        ReflectionTestUtils.setField(service, "periodicLogoutIntervalMinutes", 60);
        ReflectionTestUtils.setField(service, "reconnectDelaySeconds", 5);
    }

    @AfterEach
    void shutdownExecutors() {
        // Service spins up scheduler + botCreationExecutor at construction. Shut them down.
        try {
            service.shutdown();
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, ReentrantLock> groupLocks() {
        try {
            Field f = BotGroupBehaviorService.class.getDeclaredField("groupLocks");
            f.setAccessible(true);
            return (Map<String, ReentrantLock>) f.get(service);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, BotGroupRuntime> runningGroups() {
        try {
            Field f = BotGroupBehaviorService.class.getDeclaredField("runningGroups");
            f.setAccessible(true);
            return (Map<String, BotGroupRuntime>) f.get(service);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void invokePrivateMonitorHealth(BotGroupBehaviorService service, BotGroupRuntime runtime) {
        try {
            Method m = BotGroupBehaviorService.class.getDeclaredMethod("monitorHealth", BotGroupRuntime.class);
            m.setAccessible(true);
            m.invoke(service, runtime);
        } catch (InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException(ite.getCause());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void invokePrivateStartPeriodicLogoutScheduler(BotGroupBehaviorService service,
                                                                   BotGroupRuntime runtime,
                                                                   Environment env) {
        try {
            Method m = BotGroupBehaviorService.class.getDeclaredMethod(
                    "startPeriodicLogoutScheduler", BotGroupRuntime.class, Environment.class);
            m.setAccessible(true);
            m.invoke(service, runtime, env);
        } catch (InvocationTargetException ite) {
            if (ite.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException(ite.getCause());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void putBots(BotGroupRuntime runtime, List<Bot> bots) {
        try {
            Field f = BotGroupRuntime.class.getDeclaredField("botInstances");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Bot> list = (List<Bot>) f.get(runtime);
            list.clear();
            list.addAll(bots);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("onStartup - startup ownership (TIMED_ACTIVATION AD-10)")
    class OnStartupOwnershipTests {

        @Test
        @DisplayName("SCHEDULED groups are skipped — the reconciler owns them, so start() is never entered")
        void scheduledGroupsSkipped() {
            BotGroup scheduled = BotGroup.builder()
                    .id("sched-1")
                    .name("Scheduled")
                    .environmentId("env-1")
                    .activationMode(com.vingame.bot.domain.botgroup.model.ActivationMode.SCHEDULED)
                    .targetStatus(BotGroupStatus.ACTIVE)
                    .build();
            when(botGroupService.findByTargetStatus(BotGroupStatus.ACTIVE))
                    .thenReturn(List.of(scheduled));

            service.onStartup();

            // start() resolves the group via findById first; a skipped group is
            // never started, so findById is never called for it.
            verify(botGroupService, never()).findById(anyString());
            assertThat(runningGroups()).doesNotContainKey("sched-1");
        }

        @Test
        @DisplayName("null-mode (legacy) groups still auto-start — start() is entered (findById called)")
        void legacyGroupsStillAutoStart() {
            // Legacy group with no environment: start() enters, resolves the group,
            // and fails the environment guard — the failure is swallowed by
            // onStartup's per-group try/catch. The point is that it was NOT skipped.
            BotGroup legacy = BotGroup.builder()
                    .id("legacy-1")
                    .name("Legacy")
                    .activationMode(null)
                    .targetStatus(BotGroupStatus.ACTIVE)
                    .build();
            when(botGroupService.findByTargetStatus(BotGroupStatus.ACTIVE))
                    .thenReturn(List.of(legacy));
            when(botGroupService.findById("legacy-1")).thenReturn(legacy);

            service.onStartup();

            verify(botGroupService).findById("legacy-1");
        }
    }

    @Nested
    @DisplayName("start - validation guards")
    class StartValidationTests {

        @Test
        @DisplayName("Should throw BadRequestException and not retain runtime when environmentId is null")
        void shouldThrowWhenEnvironmentIdNull() {
            BotGroup group = BotGroup.builder()
                    .id("g-1")
                    .name("Group")
                    .gameId("game-1")
                    .build();
            when(botGroupService.findById("g-1")).thenReturn(group);

            assertThatThrownBy(() -> service.start("g-1"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("environmentId");

            assertThat(runningGroups()).doesNotContainKey("g-1");
            // No bot was ever attempted
            verify(botFactory, never()).createBot(anyString(), any(BotConfiguration.class));
        }

        @Test
        @DisplayName("Should throw BadRequestException and not retain runtime when gameId is null")
        void shouldThrowWhenGameIdNull() {
            BotGroup group = BotGroup.builder()
                    .id("g-1")
                    .name("Group")
                    .environmentId("env-1")
                    .build();
            when(botGroupService.findById("g-1")).thenReturn(group);

            assertThatThrownBy(() -> service.start("g-1"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("gameId");

            assertThat(runningGroups()).doesNotContainKey("g-1");
            verify(botFactory, never()).createBot(anyString(), any(BotConfiguration.class));
        }

        @Test
        @DisplayName("Should log warning and return early when group is already running (no bot creation)")
        void shouldNoOpWhenAlreadyRunning() {
            BotGroupRuntime existing = new BotGroupRuntime("g-1", 0, "env-1");
            try {
                runningGroups().put("g-1", existing);

                service.start("g-1");

                // findById should NOT have been called since we returned early
                verify(botGroupService, never()).findById(anyString());
                verify(botFactory, never()).createBot(anyString(), any(BotConfiguration.class));
                // Runtime should still be the same instance
                assertThat(runningGroups().get("g-1")).isSameAs(existing);
            } finally {
                existing.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }
    }

    @Nested
    @DisplayName("start - createBot failure behavior")
    class StartCreateBotFailureTests {

        // NOTE: This test exercises the direct {@code start} path only.
        // When direct {@code start} produces zero live bots (botCount > 0 but
        // every createBot failed and was swallowed by createBotsInParallel),
        // it marks the group DEAD and persists targetStatus=DEAD — the same
        // terminal state monitorHealth uses — instead of lying with ACTIVE +
        // 0 bots (TECH_DEBT_CLEANUP_2026_07 Architecture Decision 2). It does
        // NOT throw: direct start runs from onStartup (per-group isolated) and
        // the controller /start, where DEAD is the operator-visible signal.
        // {@code restart} (RESTART_LIFECYCLE_FIX Architecture Decision 6) is
        // stricter — it throws IllegalStateException on zero bots, because a
        // restart begins with a healthy running group and that is the exact
        // symptom that hid the 2026-06-09 outage. The two paths intentionally
        // differ. See BotGroupBehaviorServiceRestartTest.
        @Test
        @DisplayName("Should mark the group DEAD (targetStatus=DEAD, no throw) when every createBot fails and zero bots start")
        void shouldMarkGroupDeadWhenAllCreateBotsFail() {
            BotGroup group = BotGroup.builder()
                    .id("g-1")
                    .name("Group")
                    .environmentId("env-1")
                    .gameId("game-1")
                    .botCount(3)
                    .namePrefix("bot")
                    .password("pass")
                    .build();

            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            Game game = Game.builder().id("game-1").name("BauCua").build();

            when(botGroupService.findById("g-1")).thenReturn(group);
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(game);
            when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                    .thenThrow(new RuntimeException("auth failed"));

            // Per-bot failures are caught inside createBotsInParallel; start does NOT throw.
            assertThatCode(() -> service.start("g-1")).doesNotThrowAnyException();

            // Runtime remains registered (zero bots) and is flipped to the DEAD
            // in-memory state — the same terminal state monitorHealth uses, so
            // getHealth/getStatus surface DEAD rather than ACTIVE+0-bots.
            assertThat(runningGroups()).containsKey("g-1");
            BotGroupRuntime runtime = runningGroups().get("g-1");
            assertThat(runtime.getBotInstances()).isEmpty();
            assertThat(runtime.isGroupDead()).isTrue();
            assertThat(runtime.getActualStatus()).isEqualTo(BotGroupStatus.DEAD);

            // Group was persisted as DEAD (not ACTIVE) with lastStartedAt set,
            // lastStoppedAt cleared, and an operator-visible failure reason.
            verify(botGroupService).save(botGroupCaptor.capture());
            BotGroup saved = botGroupCaptor.getValue();
            assertThat(saved.getTargetStatus()).isEqualTo(BotGroupStatus.DEAD);
            assertThat(saved.getLastStartedAt()).isNotNull();
            assertThat(saved.getLastStoppedAt()).isNull();
            assertThat(saved.getLastFailureReason())
                    .as("operator-visible reason for the DEAD transition")
                    .contains("0/3");

            // Cleanup the side-effect runtime
            runtime.stopAllBots();
        }

        // Boundary for AD-2's guard `bots.isEmpty() && group.getBotCount() > 0`:
        // a group legitimately configured with botCount == 0 produces zero bots
        // but is NOT a failure — it must stay ACTIVE, never DEAD. This pins the
        // `botCount > 0` half of the guard so a future refactor that drops it
        // (marking every empty group DEAD) is caught.
        @Test
        @DisplayName("Should keep a zero-botCount group ACTIVE (not DEAD) — the DEAD path only fires when botCount > 0")
        void shouldKeepZeroBotCountGroupActive() {
            BotGroup group = BotGroup.builder()
                    .id("g-zero")
                    .name("ZeroCount")
                    .environmentId("env-1")
                    .gameId("game-1")
                    .botCount(0)
                    .namePrefix("bot")
                    .password("pass")
                    .build();

            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            Game game = Game.builder().id("game-1").name("BauCua").build();

            when(botGroupService.findById("g-zero")).thenReturn(group);
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(game);
            // botFactory.createBot is never called for botCount == 0.

            assertThatCode(() -> service.start("g-zero")).doesNotThrowAnyException();

            BotGroupRuntime runtime = runningGroups().get("g-zero");
            assertThat(runtime).isNotNull();
            assertThat(runtime.getBotInstances()).isEmpty();
            assertThat(runtime.isGroupDead()).isFalse();

            verify(botGroupService).save(botGroupCaptor.capture());
            assertThat(botGroupCaptor.getValue().getTargetStatus()).isEqualTo(BotGroupStatus.ACTIVE);

            runtime.stopAllBots();
        }
    }

    @Nested
    @DisplayName("scheduleRestart - validation")
    class ScheduleRestartTests {

        @Test
        @DisplayName("Should throw BadRequestException when scheduled time is in the past")
        void shouldRejectPastTimes() {
            BotGroup group = BotGroup.builder().id("g-1").name("Group").build();
            when(botGroupService.findById("g-1")).thenReturn(group);

            LocalDateTime past = LocalDateTime.now().minusMinutes(5);

            assertThatThrownBy(() -> service.scheduleRestart("g-1", past))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Scheduled time must be in the future");

            // No save occurred
            verify(botGroupService, never()).save(any(BotGroup.class));
        }

        @Test
        @DisplayName("Should persist scheduledRestartTime when time is in the future")
        void shouldPersistFutureTime() {
            BotGroup group = BotGroup.builder().id("g-1").name("Group").build();
            when(botGroupService.findById("g-1")).thenReturn(group);

            // Far enough in the future that the scheduled restart will not fire during the test
            LocalDateTime future = LocalDateTime.now().plusYears(10);

            service.scheduleRestart("g-1", future);

            verify(botGroupService).save(botGroupCaptor.capture());
            BotGroup saved = botGroupCaptor.getValue();
            assertThat(saved.getScheduledRestartTime()).isEqualTo(future);
        }
    }

    @Nested
    @DisplayName("getHealth")
    class GetHealthTests {

        @Test
        @DisplayName("Should return STOPPED skeleton DTO when no runtime exists")
        void shouldReturnStoppedDtoWhenNoRuntime() {
            BotGroup group = BotGroup.builder().id("g-1").name("Group").build();
            when(botGroupService.findById("g-1")).thenReturn(group);

            BotGroupHealthDTO dto = service.getHealth("g-1");

            assertThat(dto.getGroupId()).isEqualTo("g-1");
            assertThat(dto.getGroupName()).isEqualTo("Group");
            assertThat(dto.getStatus()).isEqualTo(BotGroupStatus.STOPPED);
            assertThat(dto.getTotalBots()).isEqualTo(0);
            assertThat(dto.getConnectedBots()).isEqualTo(0);
            assertThat(dto.getDisconnectedBots()).isEqualTo(0);
            assertThat(dto.getBots()).isEmpty();
        }

        @Test
        @DisplayName("Should aggregate per-bot statuses correctly (connected/reconnecting/dead/disconnected)")
        void shouldAggregateMixedStatuses() {
            BotGroup group = BotGroup.builder().id("g-1").name("Group").build();
            when(botGroupService.findById("g-1")).thenReturn(group);

            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 5, "env-1");
            runtime.setPlayingStatus(BotGroupPlayingStatus.PLAYING);
            // A fresh runtime is STARTING (GATEWAY_REQUEST_BUDGET A1); this fixture is a group
            // whose build has finished, which is the state startLocked's flip leaves behind.
            runtime.setActualStatus(BotGroupStatus.ACTIVE);
            try {
                // 2 connected (one CONNECTION_AUTHENTICATED + one STARTED), 1 reconnecting,
                // 1 dead, 1 disconnected
                Bot connected1 = mockBot(BotStatus.CONNECTION_AUTHENTICATED, true);
                Bot connected2 = mockBot(BotStatus.STARTED, true);
                Bot reconnecting = mockBot(BotStatus.RECONNECTING, false);
                Bot dead = mockBot(BotStatus.DEAD, false);
                Bot disconnected = mockBot(BotStatus.AUTHENTICATED, false);

                putBots(runtime, List.of(connected1, connected2, reconnecting, dead, disconnected));
                runningGroups().put("g-1", runtime);

                BotGroupHealthDTO dto = service.getHealth("g-1");

                assertThat(dto.getTotalBots()).isEqualTo(5);
                assertThat(dto.getConnectedBots()).isEqualTo(2);
                assertThat(dto.getReconnectingBots()).isEqualTo(1);
                assertThat(dto.getDeadBots()).isEqualTo(1);
                // disconnected = total - connected - reconnecting - dead = 5 - 2 - 1 - 1 = 1
                assertThat(dto.getDisconnectedBots()).isEqualTo(1);
                assertThat(dto.getStatus()).isEqualTo(BotGroupStatus.ACTIVE);
                assertThat(dto.getPlayingStatus()).isEqualTo(BotGroupPlayingStatus.PLAYING);
                assertThat(dto.getStartedAt()).isNotNull();
                assertThat(dto.getBots()).hasSize(5);
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("getHealth() surfaces the coordinator state when a coordinator is present on the runtime (BET_COORDINATION Phase 4)")
        void healthSurfacesCoordinationState() {
            BotGroup group = BotGroup.builder().id("g-1").name("Group").build();
            when(botGroupService.findById("g-1")).thenReturn(group);

            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1");
            runtime.setPlayingStatus(BotGroupPlayingStatus.PLAYING);
            try {
                // Affinities 3:1 across two options; cap 1000, minBet 100, increment 100.
                // Target budgets: opt1 = 3/4*1000 = 750, opt2 = 1/4*1000 = 250.
                Map<Integer, Integer> affinities = new LinkedHashMap<>();
                affinities.put(1, 3);
                affinities.put(2, 1);
                BetCoordinator coordinator = new BetCoordinator(affinities, 1000L, 100L, 100L);
                coordinator.onRound(42L);
                coordinator.reserve(42L, 1, 100L);  // APPROVE: 100 fits opt1 budget
                coordinator.reserve(42L, 2, 300L);  // TRIM: clamped to opt2 budget 250, grid-aligned to 200
                coordinator.reserve(42L, 1, 50L);   // REJECT: below minBet
                runtime.setCoordinator(coordinator);

                putBots(runtime, List.of());
                runningGroups().put("g-1", runtime);

                BotGroupHealthDTO dto = service.getHealth("g-1");

                CoordinationStateDTO coordination = dto.getCoordination();
                assertThat(coordination).isNotNull();
                assertThat(coordination.isEnabled()).isTrue();
                assertThat(coordination.getMaxAggregateStakePerRound()).isEqualTo(1000L);
                assertThat(coordination.getCurrentAggregateStake()).isEqualTo(300L); // 100 + 200
                assertThat(coordination.getApproveCount()).isEqualTo(1L);
                assertThat(coordination.getTrimCount()).isEqualTo(1L);
                assertThat(coordination.getRejectCount()).isEqualTo(1L);

                // Crowd-off (internal-tier) coordinator: crowd fields present-but-inert
                // (CROWD_AWARE_COORDINATION AD-C10) — crowdAware=false, semantic echoed,
                // per-option crowdStake=0.
                assertThat(coordination.isCrowdAware()).isFalse();
                assertThat(coordination.getCrowdCountSemantic()).isEqualTo("UNKNOWN");

                assertThat(coordination.getOptions()).hasSize(2);
                CoordinationStateDTO.OptionStateDTO opt1 = coordination.getOptions().get(0);
                assertThat(opt1.getOptionId()).isEqualTo(1);
                assertThat(opt1.getTargetWeight()).isEqualTo(3);
                assertThat(opt1.getTargetBudget()).isEqualTo(750L);
                assertThat(opt1.getCommittedStake()).isEqualTo(100L);
                assertThat(opt1.getRealizedFraction()).isEqualTo(100.0 / 750.0);
                assertThat(opt1.getCrowdStake()).isZero();

                CoordinationStateDTO.OptionStateDTO opt2 = coordination.getOptions().get(1);
                assertThat(opt2.getOptionId()).isEqualTo(2);
                assertThat(opt2.getTargetWeight()).isEqualTo(1);
                assertThat(opt2.getTargetBudget()).isEqualTo(250L);
                assertThat(opt2.getCommittedStake()).isEqualTo(200L);
                assertThat(opt2.getRealizedFraction()).isEqualTo(200.0 / 250.0);
                assertThat(opt2.getCrowdStake()).isZero();
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("getHealth() surfaces the crowd view for a crowd-aware coordinator, per-option crowdStake in optionAffinities order (CROWD_AWARE_COORDINATION Phase 4, AD-C10)")
        void healthSurfacesCrowdAwareCoordinationState() {
            BotGroup group = BotGroup.builder().id("g-1").name("Group").build();
            when(botGroupService.findById("g-1")).thenReturn(group);

            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1");
            runtime.setPlayingStatus(BotGroupPlayingStatus.PLAYING);
            try {
                // Insertion order 2 then 1 to guard the Map-order caveat: the health
                // option list must follow optionAffinities iteration order (2, 1), NOT
                // the crowd/budget map values() order.
                Map<Integer, Integer> affinities = new LinkedHashMap<>();
                affinities.put(2, 1);
                affinities.put(1, 3);
                BetCoordinator coordinator = new BetCoordinator(
                        affinities, 1000L, 100L, 100L, true, CrowdCountSemantic.BETS.name());
                coordinator.onRound(42L);
                // Observe a crowd concentrated on option 1: v=400 on opt1, v=50 on opt2.
                // Pure crowd X(o) = max(0, v(o) − committed(o)); no fleet committed yet,
                // so crowdStake surfaces as v(o): opt1=400, opt2=50.
                coordinator.observeCrowd(42L, List.of(
                        new com.vingame.bot.domain.bot.coordination.CrowdOption(1, 400L, 0L, 7),
                        new com.vingame.bot.domain.bot.coordination.CrowdOption(2, 50L, 0L, 2)));
                runtime.setCoordinator(coordinator);

                putBots(runtime, List.of());
                runningGroups().put("g-1", runtime);

                BotGroupHealthDTO dto = service.getHealth("g-1");

                CoordinationStateDTO coordination = dto.getCoordination();
                assertThat(coordination).isNotNull();
                assertThat(coordination.isEnabled()).isTrue();
                assertThat(coordination.isCrowdAware()).isTrue();
                assertThat(coordination.getCrowdCountSemantic()).isEqualTo("BETS");

                // Per-option ordering follows optionAffinities (2, 1) — guards the
                // Map-order caveat.
                assertThat(coordination.getOptions()).hasSize(2);
                CoordinationStateDTO.OptionStateDTO first = coordination.getOptions().get(0);
                assertThat(first.getOptionId()).isEqualTo(2);
                assertThat(first.getCrowdStake()).isEqualTo(50L);

                CoordinationStateDTO.OptionStateDTO second = coordination.getOptions().get(1);
                assertThat(second.getOptionId()).isEqualTo(1);
                assertThat(second.getCrowdStake()).isEqualTo(400L);
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("getHealth() leaves the coordination block null when no coordinator is present (BET_COORDINATION Phase 4)")
        void healthOmitsCoordinationWhenAbsent() {
            BotGroup group = BotGroup.builder().id("g-1").name("Group").build();
            when(botGroupService.findById("g-1")).thenReturn(group);

            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1");
            runtime.setPlayingStatus(BotGroupPlayingStatus.PLAYING);
            try {
                putBots(runtime, List.of());
                runningGroups().put("g-1", runtime);

                BotGroupHealthDTO dto = service.getHealth("g-1");

                assertThat(dto.getCoordination()).isNull();
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("getHealth() surfaces the jackpot-scale state when a scaler is present on the runtime (JACKPOT_SCALE_AND_RAMP Phase J4)")
        void healthSurfacesJackpotScaleState() {
            BotGroup group = BotGroup.builder().id("g-1").name("Group").build();
            when(botGroupService.findById("g-1")).thenReturn(group);

            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1");
            runtime.setPlayingStatus(BotGroupPlayingStatus.PLAYING);
            try {
                // ceiling 2_000_000, seedFloor 500_000, minMultiplier 0.25.
                // Observe pool 1_250_000 → t = (1_250_000 - 500_000)/(2_000_000 - 500_000) = 0.5
                // → factor = 0.25 + 0.75 * 0.5 = 0.625.
                JackpotScaler scaler = new JackpotScaler(2_000_000L, 500_000L, 0.25);
                scaler.observePool(42L, 1_250_000L);
                runtime.setJackpotScaler(scaler);

                putBots(runtime, List.of());
                runningGroups().put("g-1", runtime);

                BotGroupHealthDTO dto = service.getHealth("g-1");

                JackpotScaleStateDTO jackpotScale = dto.getJackpotScale();
                assertThat(jackpotScale).isNotNull();
                assertThat(jackpotScale.isEnabled()).isTrue();
                assertThat(jackpotScale.getJackpotCeiling()).isEqualTo(2_000_000L);
                assertThat(jackpotScale.getSeedFloor()).isEqualTo(500_000L);
                assertThat(jackpotScale.getLastObservedPool()).isEqualTo(1_250_000L);
                assertThat(jackpotScale.getCurrentFactor()).isEqualTo(0.625);
                assertThat(jackpotScale.getMinMultiplier()).isEqualTo(0.25);
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("getHealth() leaves the jackpotScale block null when no scaler is present (JACKPOT_SCALE_AND_RAMP Phase J4)")
        void healthOmitsJackpotScaleWhenAbsent() {
            BotGroup group = BotGroup.builder().id("g-1").name("Group").build();
            when(botGroupService.findById("g-1")).thenReturn(group);

            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1");
            runtime.setPlayingStatus(BotGroupPlayingStatus.PLAYING);
            try {
                putBots(runtime, List.of());
                runningGroups().put("g-1", runtime);

                BotGroupHealthDTO dto = service.getHealth("g-1");

                assertThat(dto.getJackpotScale()).isNull();
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("getHealth() surfaces the ramp state from the group entity when rampEnabled (JACKPOT_SCALE_AND_RAMP Phase R3)")
        void healthSurfacesRampState() {
            BotGroup group = BotGroup.builder()
                    .id("g-1").name("Group")
                    .rampEnabled(true).rampShape(3.0)
                    .build();
            when(botGroupService.findById("g-1")).thenReturn(group);

            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1");
            runtime.setPlayingStatus(BotGroupPlayingStatus.PLAYING);
            try {
                putBots(runtime, List.of());
                runningGroups().put("g-1", runtime);

                BotGroupHealthDTO dto = service.getHealth("g-1");

                RampStateDTO ramp = dto.getRamp();
                assertThat(ramp).isNotNull();
                assertThat(ramp.isEnabled()).isTrue();
                assertThat(ramp.getRampShape()).isEqualTo(3.0);
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("getHealth() leaves the ramp block null when rampEnabled=false (JACKPOT_SCALE_AND_RAMP Phase R3)")
        void healthOmitsRampWhenDisabled() {
            BotGroup group = BotGroup.builder()
                    .id("g-1").name("Group")
                    .rampEnabled(false).rampShape(3.0)
                    .build();
            when(botGroupService.findById("g-1")).thenReturn(group);

            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1");
            runtime.setPlayingStatus(BotGroupPlayingStatus.PLAYING);
            try {
                putBots(runtime, List.of());
                runningGroups().put("g-1", runtime);

                BotGroupHealthDTO dto = service.getHealth("g-1");

                assertThat(dto.getRamp()).isNull();
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }
    }

    @Nested
    @DisplayName("isGroupRunning / getRunningBotCountForGroup")
    class GroupRunningTests {

        @Test
        @DisplayName("Should return false / 0 when no runtime")
        void noRuntime() {
            assertThat(service.isGroupRunning("g-missing")).isFalse();
            assertThat(service.getRunningBotCountForGroup("g-missing")).isEqualTo(0);
        }

        @Test
        @DisplayName("Should return true when runtime is ACTIVE")
        void runtimeActive() {
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1");
            try {
                runningGroups().put("g-1", runtime);

                assertThat(service.isGroupRunning("g-1")).isTrue();
                // no bots → running count is 0 (still ACTIVE)
                assertThat(service.getRunningBotCountForGroup("g-1")).isEqualTo(0);
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("Should return false when runtime exists but is DEAD")
        void runtimeDead() {
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1");
            try {
                runtime.markAsDead();
                runningGroups().put("g-1", runtime);

                assertThat(service.isGroupRunning("g-1")).isFalse();
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }
    }

    @Nested
    @DisplayName("getActualStatus / getPlayingStatus")
    class StatusGetterTests {

        @Test
        @DisplayName("getActualStatus returns STOPPED when no runtime")
        void actualStatusNoRuntime() {
            assertThat(service.getActualStatus("g-missing")).isEqualTo(BotGroupStatus.STOPPED);
        }

        @Test
        @DisplayName("getPlayingStatus returns null when no runtime")
        void playingStatusNoRuntime() {
            assertThat(service.getPlayingStatus("g-missing")).isNull();
        }

        @Test
        @DisplayName("Both return runtime values when runtime is present")
        void returnsRuntimeValues() {
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1");
            try {
                runtime.setPlayingStatus(BotGroupPlayingStatus.PLAYING);
                // A fresh runtime is STARTING (A1); this fixture stands in for a group whose
                // build has finished, which is what startLocked's flip produces.
                runtime.setActualStatus(BotGroupStatus.ACTIVE);
                runningGroups().put("g-1", runtime);

                assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.ACTIVE);
                assertThat(service.getPlayingStatus("g-1")).isEqualTo(BotGroupPlayingStatus.PLAYING);
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }
    }

    @Nested
    @DisplayName("startPeriodicLogoutScheduler - env overrides global")
    class PeriodicLogoutConfigTests {

        @Test
        @DisplayName("Env enabled=false beats global enabled=true (scheduler is not started)")
        void envFalseBeatsGlobalTrue() {
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 1, "env-1");
            try {
                // Need at least one bot, otherwise the no-bots guard hits first
                putBots(runtime, List.of(mock(Bot.class)));

                Environment env = Environment.builder()
                        .id("env-1")
                        .name("env")
                        .periodicLogoutEnabled(false)
                        .build();

                invokePrivateStartPeriodicLogoutScheduler(service, runtime, env);

                assertThat(runtime.getLogoutScheduler()).isNull();
            } finally {
                runtime.getExecutor().shutdownNow();
                ScheduledExecutorService s = runtime.getLogoutScheduler();
                if (s != null) s.shutdownNow();
            }
        }

        @Test
        @DisplayName("Env enabled=null falls back to global enabled=true (scheduler is started)")
        void envNullUsesGlobalTrue() {
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 1, "env-1");
            ScheduledExecutorService createdScheduler = null;
            try {
                putBots(runtime, List.of(mock(Bot.class)));

                Environment env = Environment.builder()
                        .id("env-1")
                        .name("env")
                        // periodicLogoutEnabled and periodicLogoutIntervalMinutes both null
                        .build();

                invokePrivateStartPeriodicLogoutScheduler(service, runtime, env);

                createdScheduler = runtime.getLogoutScheduler();
                assertThat(createdScheduler).isNotNull();
                assertThat(createdScheduler.isShutdown()).isFalse();
            } finally {
                if (createdScheduler != null) createdScheduler.shutdownNow();
                runtime.getExecutor().shutdownNow();
            }
        }

        @Test
        @DisplayName("Env enabled=true with custom interval starts scheduler (uses env's interval)")
        void envTrueWithCustomInterval() {
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 1, "env-1");
            ScheduledExecutorService createdScheduler = null;
            try {
                putBots(runtime, List.of(mock(Bot.class)));

                Environment env = Environment.builder()
                        .id("env-1")
                        .name("env")
                        .periodicLogoutEnabled(true)
                        .periodicLogoutIntervalMinutes(30)
                        .build();

                invokePrivateStartPeriodicLogoutScheduler(service, runtime, env);

                createdScheduler = runtime.getLogoutScheduler();
                assertThat(createdScheduler).isNotNull();
                // Hard to assert interval directly without firing the task; existence is the
                // best we can do given the private scheduler internals.
            } finally {
                if (createdScheduler != null) createdScheduler.shutdownNow();
                runtime.getExecutor().shutdownNow();
            }
        }

        @Test
        @DisplayName("Global enabled=false (no env override) means scheduler is not started")
        void globalFalseNoOverride() {
            ReflectionTestUtils.setField(service, "periodicLogoutEnabled", false);

            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 1, "env-1");
            try {
                putBots(runtime, List.of(mock(Bot.class)));

                Environment env = Environment.builder()
                        .id("env-1")
                        .name("env")
                        // periodicLogoutEnabled null → fall back to global (false)
                        .build();

                invokePrivateStartPeriodicLogoutScheduler(service, runtime, env);

                assertThat(runtime.getLogoutScheduler()).isNull();
            } finally {
                runtime.getExecutor().shutdownNow();
            }
        }

        @Test
        @DisplayName("Empty bot list skips scheduler setup even when enabled")
        void emptyBotsSkipsScheduler() {
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1");
            try {
                // no bots added
                Environment env = Environment.builder()
                        .id("env-1")
                        .name("env")
                        .periodicLogoutEnabled(true)
                        .build();

                invokePrivateStartPeriodicLogoutScheduler(service, runtime, env);

                assertThat(runtime.getLogoutScheduler()).isNull();
            } finally {
                runtime.getExecutor().shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("stopAndLogout - cascade-delete teardown (Phase 7)")
    class StopAndLogoutTests {

        @Test
        @DisplayName("Cleans up (logs out) every bot via the stopped-first teardown, evicts session state, stops managing the group — and never calls the raw logout()")
        void cleansUpAndTearsDown() {
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 2, "env-1");
            Bot b1 = mockBot(BotStatus.CONNECTION_AUTHENTICATED, true);
            Bot b2 = mockBot(BotStatus.STARTED, true);
            putBots(runtime, List.of(b1, b2));
            runningGroups().put("g-1", runtime);

            service.stopAndLogout("g-1");

            // Each bot is torn down via cleanup() — which sets stopped=true BEFORE
            // closing the WS (the logout), so onDisconnect's retry is suppressed.
            verify(b1).cleanup();
            verify(b2).cleanup();
            // The raw logout() (close WITHOUT the stopped flag) must never be used on
            // the delete path — it would manufacture a false reconnect per bot.
            verify(b1, never()).logout();
            verify(b2, never()).logout();
            // Aggregated-session state dropped and the group is no longer managed.
            verify(sessionAggregationService).evictGroup("g-1");
            assertThat(runningGroups()).doesNotContainKey("g-1");
            // Flipped out of ACTIVE so a concurrent periodic-logout tick bails.
            assertThat(runtime.getActualStatus()).isEqualTo(BotGroupStatus.STOPPED);
        }

        @Test
        @DisplayName("Is a no-op for a group that is not running (idempotent)")
        void noOpWhenNotRunning() {
            service.stopAndLogout("g-missing");

            verify(sessionAggregationService, never()).evictGroup(anyString());
        }

        @Test
        @DisplayName("Tolerates a single bot's cleanup throwing and still completes the teardown")
        void toleratesCleanupFailure() {
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 2, "env-1");
            Bot bad = mockBot(BotStatus.CONNECTION_AUTHENTICATED, true);
            Bot good = mockBot(BotStatus.STARTED, true);
            // stopAllBots wraps each cleanup() in try/catch, so one bad bot cannot
            // abort the cascade.
            org.mockito.Mockito.doThrow(new RuntimeException("cleanup boom")).when(bad).cleanup();
            putBots(runtime, List.of(bad, good));
            runningGroups().put("g-1", runtime);

            service.stopAndLogout("g-1");

            // The healthy bot is still cleaned up and the teardown still runs.
            verify(good).cleanup();
            verify(sessionAggregationService).evictGroup("g-1");
            assertThat(runningGroups()).doesNotContainKey("g-1");
        }

        @Test
        @DisplayName("Cleans up every bot BEFORE evicting session state / dropping the group (ordering)")
        void logsOutBeforeTeardown() {
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 2, "env-1");
            Bot b1 = mockBot(BotStatus.CONNECTION_AUTHENTICATED, true);
            Bot b2 = mockBot(BotStatus.STARTED, true);
            putBots(runtime, List.of(b1, b2));
            runningGroups().put("g-1", runtime);

            service.stopAndLogout("g-1");

            // The stopped-first cleanup (the logout) for every bot must happen before
            // the session-agg eviction that ends the teardown — clean up while the
            // runtime is still intact, then evict. inOrder spans the bot mocks and the
            // agg mock.
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(b1, b2, sessionAggregationService);
            inOrder.verify(b1).cleanup();
            inOrder.verify(b2).cleanup();
            inOrder.verify(sessionAggregationService).evictGroup("g-1");
        }

        @Test
        @DisplayName("Does NOT persist a STOPPED status — the caller deletes the document next (no DB round-trip)")
        void doesNotPersistStoppedStatus() {
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 1, "env-1");
            Bot b1 = mockBot(BotStatus.CONNECTION_AUTHENTICATED, true);
            putBots(runtime, List.of(b1));
            runningGroups().put("g-1", runtime);

            service.stopAndLogout("g-1");

            // Unlike stop(), stopAndLogout must not write STOPPED back to Mongo:
            // BotGroupService.delete removes the document immediately afterwards,
            // so a save would be a wasted round-trip on a doomed document.
            verify(botGroupService, never()).save(any(BotGroup.class));
        }
    }

    @Nested
    @DisplayName("monitorHealth - dead-threshold trigger")
    class MonitorHealthTests {

        @Test
        @DisplayName("Should mark group DEAD and save when dead-bot ratio meets the threshold")
        void shouldMarkDeadAtOrAboveThreshold() {
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 5, "env-1");
            try {
                // 4/5 dead (80%) at threshold 0.80 → triggers
                Bot d1 = mockBot(BotStatus.DEAD, false);
                Bot d2 = mockBot(BotStatus.DEAD, false);
                Bot d3 = mockBot(BotStatus.DEAD, false);
                Bot d4 = mockBot(BotStatus.DEAD, false);
                Bot alive = mockBot(BotStatus.CONNECTION_AUTHENTICATED, true);
                putBots(runtime, List.of(d1, d2, d3, d4, alive));

                BotGroup group = BotGroup.builder().id("g-1").name("Group").build();
                when(botGroupService.findById("g-1")).thenReturn(group);
                // handleBotGroupDeath now re-checks the runtime's identity against
                // runningGroups under the group lock, which is the production
                // precondition: monitorHealth only runs from a live runtime's own
                // health monitor.
                runningGroups().put("g-1", runtime);

                invokePrivateMonitorHealth(service, runtime);

                assertThat(runtime.isGroupDead()).isTrue();
                verify(botGroupService).save(botGroupCaptor.capture());
                BotGroup saved = botGroupCaptor.getValue();
                assertThat(saved.getTargetStatus()).isEqualTo(BotGroupStatus.DEAD);
                assertThat(saved.getLastFailureReason()).isNotNull();
            } finally {
                runtime.getExecutor().shutdownNow();
            }
        }

        @Test
        @DisplayName("Should NOT mark group DEAD when dead-bot ratio is below the threshold")
        void shouldNotMarkDeadBelowThreshold() {
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 4, "env-1");
            try {
                // 2/4 dead (50%) at threshold 0.80 → does NOT trigger
                Bot d1 = mockBot(BotStatus.DEAD, false);
                Bot d2 = mockBot(BotStatus.DEAD, false);
                Bot a1 = mockBot(BotStatus.CONNECTION_AUTHENTICATED, true);
                Bot a2 = mockBot(BotStatus.CONNECTION_AUTHENTICATED, true);
                putBots(runtime, List.of(d1, d2, a1, a2));

                invokePrivateMonitorHealth(service, runtime);

                assertThat(runtime.isGroupDead()).isFalse();
                verify(botGroupService, never()).save(any(BotGroup.class));
            } finally {
                runtime.getExecutor().shutdownNow();
            }
        }

        /**
         * DEAD_GROUP_AUTO_RECOVERY: handleBotGroupDeath used to take no lock, so a
         * monitor tick sitting between its findById and its save when an operator's
         * stop() landed could leave Mongo at DEAD with no runtime — a fully eligible
         * recovery candidate for a group the operator had just stopped, and the one
         * AD-5 hole that needs no PATCH. It now tryLocks, and losing the lock means
         * a start or a stop owns the status, so this tick has nothing to say.
         */
        @Test
        @DisplayName("Should not touch the group when a start/stop holds the per-group lock")
        void shouldNotWriteWhileAnotherLifecycleOperationHoldsTheLock() throws Exception {
            BotGroupRuntime runtime = new BotGroupRuntime("g-locked", 5, "env-1");
            ReentrantLock lock = new ReentrantLock();
            try {
                Bot d1 = mockBot(BotStatus.DEAD, false);
                Bot d2 = mockBot(BotStatus.DEAD, false);
                Bot d3 = mockBot(BotStatus.DEAD, false);
                Bot d4 = mockBot(BotStatus.DEAD, false);
                Bot alive = mockBot(BotStatus.CONNECTION_AUTHENTICATED, true);
                putBots(runtime, List.of(d1, d2, d3, d4, alive));
                runningGroups().put("g-locked", runtime);
                groupLocks().put("g-locked", lock);

                // Somebody else — a stop() mid-teardown — is holding it.
                Thread holder = new Thread(lock::lock);
                holder.start();
                holder.join();

                invokePrivateMonitorHealth(service, runtime);

                assertThat(runtime.isGroupDead())
                        .as("the lock holder decides this group's fate, not the monitor")
                        .isFalse();
                verify(botGroupService, never()).save(any(BotGroup.class));
            } finally {
                // The holder thread has exited, so the lock can never be released;
                // dropping the entry is how the fixture is cleaned up.
                groupLocks().remove("g-locked");
                runningGroups().remove("g-locked");
                runtime.getExecutor().shutdownNow();
            }
        }

        @Test
        @DisplayName("Should not re-mark a runtime that has already been torn down or replaced")
        void shouldNotMarkATornDownRuntime() {
            // Re-marking would re-open a groupDeadSince window that stopAllBots has
            // already credited and closed, and nothing would ever credit it again.
            BotGroupRuntime stale = new BotGroupRuntime("g-stale", 5, "env-1");
            try {
                Bot d1 = mockBot(BotStatus.DEAD, false);
                Bot d2 = mockBot(BotStatus.DEAD, false);
                Bot d3 = mockBot(BotStatus.DEAD, false);
                Bot d4 = mockBot(BotStatus.DEAD, false);
                Bot alive = mockBot(BotStatus.CONNECTION_AUTHENTICATED, true);
                putBots(stale, List.of(d1, d2, d3, d4, alive));
                // Deliberately NOT in runningGroups: stop() removed it already.

                invokePrivateMonitorHealth(service, stale);

                assertThat(stale.isGroupDead()).isFalse();
                verify(botGroupService, never()).save(any(BotGroup.class));
            } finally {
                stale.getExecutor().shutdownNow();
            }
        }

        @Test
        @DisplayName("Should return early without crashing when there are no bots")
        void shouldHandleNoBots() {
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1");
            try {
                invokePrivateMonitorHealth(service, runtime);

                assertThat(runtime.isGroupDead()).isFalse();
                verify(botGroupService, never()).save(any(BotGroup.class));
            } finally {
                runtime.getExecutor().shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("Phase 4 — strategy assignment + health DTO surfacing")
    class StrategyAssignmentIntegrationTests {

        @Test
        @DisplayName("start() propagates the assigned StrategyId into BotConfiguration for every bot")
        void startPropagatesStrategyIdToConfiguration() {
            BotGroup group = BotGroup.builder()
                    .id("g-1")
                    .name("Group")
                    .environmentId("env-1")
                    .gameId("game-1")
                    .botCount(3)
                    .namePrefix("bot")
                    .password("pass")
                    .strategyMix(List.of(new WeightedStrategy(StrategyId.RANDOM.name(), 1.0)))
                    .build();

            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            Game game = Game.builder().id("game-1").name("BauCua").build();

            when(botGroupService.findById("g-1")).thenReturn(group);
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(game);

            // Capture the BotConfiguration passed to createBot — we want to
            // assert that strategyId is non-null and matches the assignment.
            ArgumentCaptor<BotConfiguration> configCaptor = ArgumentCaptor.forClass(BotConfiguration.class);
            // Throw to short-circuit further setup — the captor still records
            // the configuration so we can verify the strategyId field.
            when(botFactory.createBot(anyString(), configCaptor.capture()))
                    .thenThrow(new RuntimeException("intentional — captures only"));

            service.start("g-1");

            // Every attempted bot creation carried a non-null strategyId.
            // With a [(RANDOM, 1.0)] mix every bot lands on RANDOM, which is
            // the verifiable invariant for v1 (single-enum case).
            assertThat(configCaptor.getAllValues())
                    .as("captured BotConfigurations")
                    .isNotEmpty()
                    .allSatisfy(cfg -> assertThat(cfg.getStrategyId()).isEqualTo(StrategyId.RANDOM.name()));

            // Cleanup the side-effect runtime created on the failed start path.
            BotGroupRuntime rt = runningGroups().get("g-1");
            if (rt != null) rt.stopAllBots();
        }

        @Test
        @DisplayName("start() falls back to [(RANDOM, 1.0)] when the group has no strategyMix (unmigrated docs)")
        void startFallsBackOnMissingStrategyMix() {
            BotGroup group = BotGroup.builder()
                    .id("g-1")
                    .name("Group")
                    .environmentId("env-1")
                    .gameId("game-1")
                    .botCount(2)
                    .namePrefix("bot")
                    .password("pass")
                    // strategyMix intentionally not set — simulates an
                    // unmigrated Mongo doc that pre-dates Phase 4.
                    .build();

            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            Game game = Game.builder().id("game-1").name("BauCua").build();

            when(botGroupService.findById("g-1")).thenReturn(group);
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(game);

            ArgumentCaptor<BotConfiguration> configCaptor = ArgumentCaptor.forClass(BotConfiguration.class);
            when(botFactory.createBot(anyString(), configCaptor.capture()))
                    .thenThrow(new RuntimeException("intentional — captures only"));

            service.start("g-1");

            assertThat(configCaptor.getAllValues())
                    .isNotEmpty()
                    .allSatisfy(cfg -> assertThat(cfg.getStrategyId())
                            .as("read-side fallback should default missing mix to RANDOM")
                            .isEqualTo(StrategyId.RANDOM.name()));

            BotGroupRuntime rt = runningGroups().get("g-1");
            if (rt != null) rt.stopAllBots();
        }

        @Test
        @DisplayName("getHealth() surfaces the per-bot strategyId on BotHealthDTO")
        void healthSurfacesStrategyId() {
            BotGroup group = BotGroup.builder().id("g-1").name("Group").build();
            when(botGroupService.findById("g-1")).thenReturn(group);

            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 1, "env-1");
            runtime.setPlayingStatus(BotGroupPlayingStatus.PLAYING);
            try {
                Bot b = mockBot(BotStatus.CONNECTION_AUTHENTICATED, true);
                lenient().when(b.getStrategyId()).thenReturn(StrategyId.RANDOM.name());
                putBots(runtime, List.of(b));
                runningGroups().put("g-1", runtime);

                BotGroupHealthDTO dto = service.getHealth("g-1");

                assertThat(dto.getBots()).hasSize(1);
                BotHealthDTO botDto = dto.getBots().get(0);
                assertThat(botDto.getStrategyId()).isEqualTo(StrategyId.RANDOM.name());
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }
    }

    @Nested
    @DisplayName("SLOT_MACHINE_BOT — slotStrategyId propagation")
    class SlotStrategyIdPropagationTests {

        @Test
        @DisplayName("start() of a SLOT group overrides any client-supplied slotStrategyId (RANDOM) to FIXED on every BotConfiguration")
        void startOverridesSelectedSlotStrategyToFixed() {
            BotGroup group = BotGroup.builder()
                    .id("g-1")
                    .name("Slot Group")
                    .environmentId("env-1")
                    .gameId("game-1")
                    .botCount(3)
                    .namePrefix("bot")
                    .password("pass")
                    // client picked RANDOM, but slots are never selectable — must be overridden to FIXED
                    .slotStrategyId(SlotStrategyId.RANDOM.name())
                    .build();

            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            Game game = Game.builder().id("game-1").name("Slot").gameType(GameType.SLOT).build();

            when(botGroupService.findById("g-1")).thenReturn(group);
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(game);

            ArgumentCaptor<BotConfiguration> configCaptor = ArgumentCaptor.forClass(BotConfiguration.class);
            when(botFactory.createBot(anyString(), configCaptor.capture()))
                    .thenThrow(new RuntimeException("intentional — captures only"));

            service.start("g-1");

            assertThat(configCaptor.getAllValues())
                    .as("captured BotConfigurations for SLOT group")
                    .isNotEmpty()
                    .allSatisfy(cfg -> assertThat(cfg.getSlotStrategyId())
                            .as("group slotStrategyId RANDOM must be silently overridden to FIXED")
                            .isEqualTo(SlotStrategyId.FIXED.name()));

            BotGroupRuntime rt = runningGroups().get("g-1");
            if (rt != null) rt.stopAllBots();
        }

        @Test
        @DisplayName("start() of a SLOT group with null slotStrategyId assigns each BotConfiguration FIXED")
        void startAssignsFixedWhenUnset() {
            BotGroup group = BotGroup.builder()
                    .id("g-1")
                    .name("Slot Group")
                    .environmentId("env-1")
                    .gameId("game-1")
                    .botCount(2)
                    .namePrefix("bot")
                    .password("pass")
                    // slotStrategyId intentionally not set
                    .build();

            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            Game game = Game.builder().id("game-1").name("Slot").gameType(GameType.SLOT).build();

            when(botGroupService.findById("g-1")).thenReturn(group);
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(game);

            ArgumentCaptor<BotConfiguration> configCaptor = ArgumentCaptor.forClass(BotConfiguration.class);
            when(botFactory.createBot(anyString(), configCaptor.capture()))
                    .thenThrow(new RuntimeException("intentional — captures only"));

            service.start("g-1");

            assertThat(configCaptor.getAllValues())
                    .isNotEmpty()
                    .allSatisfy(cfg -> assertThat(cfg.getSlotStrategyId())
                            .as("SLOT bots always run FIXED regardless of group slotStrategyId")
                            .isEqualTo(SlotStrategyId.FIXED.name()));

            BotGroupRuntime rt = runningGroups().get("g-1");
            if (rt != null) rt.stopAllBots();
        }

        @Test
        @DisplayName("start() of a non-SLOT (betting) group leaves slotStrategyId null on BotConfiguration even if the group set one")
        void startLeavesSlotStrategyNullForBettingGroup() {
            BotGroup group = BotGroup.builder()
                    .id("g-1")
                    .name("Betting Group")
                    .environmentId("env-1")
                    .gameId("game-1")
                    .botCount(2)
                    .namePrefix("bot")
                    .password("pass")
                    // even if a slotStrategyId leaks onto a betting group, it must not flow through
                    .slotStrategyId(SlotStrategyId.RANDOM.name())
                    .build();

            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            Game game = Game.builder().id("game-1").name("BauCua").gameType(GameType.BETTING_MINI).build();

            when(botGroupService.findById("g-1")).thenReturn(group);
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(game);

            ArgumentCaptor<BotConfiguration> configCaptor = ArgumentCaptor.forClass(BotConfiguration.class);
            when(botFactory.createBot(anyString(), configCaptor.capture()))
                    .thenThrow(new RuntimeException("intentional — captures only"));

            service.start("g-1");

            assertThat(configCaptor.getAllValues())
                    .isNotEmpty()
                    .allSatisfy(cfg -> assertThat(cfg.getSlotStrategyId())
                            .as("betting groups must not carry a slotStrategyId")
                            .isNull());

            BotGroupRuntime rt = runningGroups().get("g-1");
            if (rt != null) rt.stopAllBots();
        }
    }

    @Nested
    @DisplayName("ramp config propagation (JACKPOT_SCALE_AND_RAMP AD-R4/AD-R6)")
    class RampConfigPropagationTests {

        @Test
        @DisplayName("start() of a BETTING_MINI group threads rampEnabled/rampShape onto each BotBehaviorConfig")
        void startThreadsRampForBettingMini() {
            BotGroup group = BotGroup.builder()
                    .id("g-1").name("Betting Group").environmentId("env-1").gameId("game-1")
                    .botCount(2).namePrefix("bot").password("pass")
                    .rampEnabled(true).rampShape(3.0)
                    .build();
            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            Game game = Game.builder().id("game-1").name("BauCua").gameType(GameType.BETTING_MINI).build();

            when(botGroupService.findById("g-1")).thenReturn(group);
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(game);

            ArgumentCaptor<BotConfiguration> configCaptor = ArgumentCaptor.forClass(BotConfiguration.class);
            when(botFactory.createBot(anyString(), configCaptor.capture()))
                    .thenThrow(new RuntimeException("intentional — captures only"));

            service.start("g-1");

            assertThat(configCaptor.getAllValues())
                    .isNotEmpty()
                    .allSatisfy(cfg -> {
                        assertThat(cfg.getBehaviorConfig().isRampEnabled())
                                .as("BETTING_MINI bot carries the group's rampEnabled").isTrue();
                        assertThat(cfg.getBehaviorConfig().getRampShape())
                                .as("BETTING_MINI bot carries the group's rampShape").isEqualTo(3.0);
                    });

            BotGroupRuntime rt = runningGroups().get("g-1");
            if (rt != null) rt.stopAllBots();
        }

        @Test
        @DisplayName("start() of a TAI_XIU group threads rampEnabled/rampShape onto each BotBehaviorConfig")
        void startThreadsRampForTaiXiu() {
            BotGroup group = BotGroup.builder()
                    .id("g-1").name("TaiXiu Group").environmentId("env-1").gameId("game-1")
                    .botCount(2).namePrefix("bot").password("pass")
                    .rampEnabled(true).rampShape(2.0)
                    .build();
            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            Game game = Game.builder().id("game-1").name("TaiXiu").gameType(GameType.TAI_XIU).build();

            when(botGroupService.findById("g-1")).thenReturn(group);
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(game);

            ArgumentCaptor<BotConfiguration> configCaptor = ArgumentCaptor.forClass(BotConfiguration.class);
            when(botFactory.createBot(anyString(), configCaptor.capture()))
                    .thenThrow(new RuntimeException("intentional — captures only"));

            service.start("g-1");

            assertThat(configCaptor.getAllValues())
                    .isNotEmpty()
                    .allSatisfy(cfg -> {
                        assertThat(cfg.getBehaviorConfig().isRampEnabled()).isTrue();
                        assertThat(cfg.getBehaviorConfig().getRampShape()).isEqualTo(2.0);
                    });

            BotGroupRuntime rt = runningGroups().get("g-1");
            if (rt != null) rt.stopAllBots();
        }

        @Test
        @DisplayName("start() of a SLOT group leaves ramp fields at defaults (false / 0.0) even when the group set them")
        void startLeavesRampDefaultForSlot() {
            BotGroup group = BotGroup.builder()
                    .id("g-1").name("Slot Group").environmentId("env-1").gameId("game-1")
                    .botCount(2).namePrefix("bot").password("pass")
                    // ramp leaks onto a SLOT group — must NOT flow through (AD-R6)
                    .rampEnabled(true).rampShape(3.0)
                    .build();
            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            Game game = Game.builder().id("game-1").name("Slot").gameType(GameType.SLOT).build();

            when(botGroupService.findById("g-1")).thenReturn(group);
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(game);

            ArgumentCaptor<BotConfiguration> configCaptor = ArgumentCaptor.forClass(BotConfiguration.class);
            when(botFactory.createBot(anyString(), configCaptor.capture()))
                    .thenThrow(new RuntimeException("intentional — captures only"));

            service.start("g-1");

            assertThat(configCaptor.getAllValues())
                    .isNotEmpty()
                    .allSatisfy(cfg -> {
                        assertThat(cfg.getBehaviorConfig().isRampEnabled())
                                .as("SLOT bots must never carry ramp").isFalse();
                        assertThat(cfg.getBehaviorConfig().getRampShape())
                                .as("SLOT bots keep the default rampShape").isEqualTo(0.0);
                    });

            BotGroupRuntime rt = runningGroups().get("g-1");
            if (rt != null) rt.stopAllBots();
        }
    }

    @Nested
    @DisplayName("affinityWeightedProposal config propagation (AFFINITY_AWARE_PROPOSAL AD-7)")
    class AffinityWeightedProposalPropagationTests {

        @Test
        @DisplayName("start() of a BETTING_MINI group threads affinityWeightedProposal onto each BotBehaviorConfig")
        void startThreadsAffinityForBettingMini() {
            BotGroup group = BotGroup.builder()
                    .id("g-1").name("Betting Group").environmentId("env-1").gameId("game-1")
                    .botCount(2).namePrefix("bot").password("pass")
                    .affinityWeightedProposal(true)
                    .build();
            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            Game game = Game.builder().id("game-1").name("BauCua").gameType(GameType.BETTING_MINI).build();

            when(botGroupService.findById("g-1")).thenReturn(group);
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(game);

            ArgumentCaptor<BotConfiguration> configCaptor = ArgumentCaptor.forClass(BotConfiguration.class);
            when(botFactory.createBot(anyString(), configCaptor.capture()))
                    .thenThrow(new RuntimeException("intentional — captures only"));

            service.start("g-1");

            assertThat(configCaptor.getAllValues())
                    .isNotEmpty()
                    .allSatisfy(cfg -> assertThat(cfg.getBehaviorConfig().isAffinityWeightedProposal())
                            .as("BETTING_MINI bot carries the group's affinityWeightedProposal").isTrue());

            BotGroupRuntime rt = runningGroups().get("g-1");
            if (rt != null) rt.stopAllBots();
        }

        @Test
        @DisplayName("start() of a TAI_XIU group threads affinityWeightedProposal onto each BotBehaviorConfig")
        void startThreadsAffinityForTaiXiu() {
            BotGroup group = BotGroup.builder()
                    .id("g-1").name("TaiXiu Group").environmentId("env-1").gameId("game-1")
                    .botCount(2).namePrefix("bot").password("pass")
                    .affinityWeightedProposal(true)
                    .build();
            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            Game game = Game.builder().id("game-1").name("TaiXiu").gameType(GameType.TAI_XIU).build();

            when(botGroupService.findById("g-1")).thenReturn(group);
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(game);

            ArgumentCaptor<BotConfiguration> configCaptor = ArgumentCaptor.forClass(BotConfiguration.class);
            when(botFactory.createBot(anyString(), configCaptor.capture()))
                    .thenThrow(new RuntimeException("intentional — captures only"));

            service.start("g-1");

            assertThat(configCaptor.getAllValues())
                    .isNotEmpty()
                    .allSatisfy(cfg -> assertThat(cfg.getBehaviorConfig().isAffinityWeightedProposal()).isTrue());

            BotGroupRuntime rt = runningGroups().get("g-1");
            if (rt != null) rt.stopAllBots();
        }

        @Test
        @DisplayName("start() of a SLOT group leaves affinityWeightedProposal at default false even when the group set it")
        void startLeavesAffinityDefaultForSlot() {
            BotGroup group = BotGroup.builder()
                    .id("g-1").name("Slot Group").environmentId("env-1").gameId("game-1")
                    .botCount(2).namePrefix("bot").password("pass")
                    // affinity leaks onto a SLOT group — must NOT flow through (AD-7)
                    .affinityWeightedProposal(true)
                    .build();
            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            Game game = Game.builder().id("game-1").name("Slot").gameType(GameType.SLOT).build();

            when(botGroupService.findById("g-1")).thenReturn(group);
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(game);

            ArgumentCaptor<BotConfiguration> configCaptor = ArgumentCaptor.forClass(BotConfiguration.class);
            when(botFactory.createBot(anyString(), configCaptor.capture()))
                    .thenThrow(new RuntimeException("intentional — captures only"));

            service.start("g-1");

            assertThat(configCaptor.getAllValues())
                    .isNotEmpty()
                    .allSatisfy(cfg -> assertThat(cfg.getBehaviorConfig().isAffinityWeightedProposal())
                            .as("SLOT bots must never carry affinity-weighted proposal").isFalse());

            BotGroupRuntime rt = runningGroups().get("g-1");
            if (rt != null) rt.stopAllBots();
        }
    }

    @Nested
    @DisplayName("jackpot-scaler build gating on start (JACKPOT_SCALE_AND_RAMP AD-J3/AD-S1)")
    class JackpotScalerBuildTests {

        private BotGroup group(GameType type, boolean jackpotEnabled, long ceiling) {
            BotGroup g = BotGroup.builder()
                    .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                    .botCount(2).namePrefix("bot").password("pass")
                    .build();
            when(botGroupService.findById("g-1")).thenReturn(g);
            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            Game gg = Game.builder().id("game-1").name("G").gameType(type)
                    .jackpotScaleEnabled(jackpotEnabled).jackpotCeiling(ceiling).build();
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(gg);
            // Short-circuit bot creation — the scaler is built on the runtime BEFORE the
            // bot loop, so its presence is observable even when every createBot fails.
            when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                    .thenThrow(new RuntimeException("intentional — captures only"));
            return g;
        }

        @Test
        @DisplayName("BETTING_MINI + jackpotScaleEnabled builds a scaler with the game's ceiling (AD-J3)")
        void buildsForBettingMini() {
            group(GameType.BETTING_MINI, true, 20_000_000L);

            service.start("g-1");

            BotGroupRuntime rt = runningGroups().get("g-1");
            try {
                assertThat(rt).isNotNull();
                JackpotScaler scaler = rt.getJackpotScaler();
                assertThat(scaler).as("eligible + enabled ⇒ scaler present").isNotNull();
                // Seed floor is the constant; below-seed pool ⇒ minMultiplier 0.25 floor.
                scaler.observePool(1L, JackpotScaler.DEFAULT_SEED_FLOOR);
                assertThat(scaler.getCurrentFactor()).isEqualTo(0.25);
                // Ceiling is the configured value: pool at ceiling ⇒ factor 1.0.
                scaler.observePool(2L, 20_000_000L);
                assertThat(scaler.getCurrentFactor()).isEqualTo(1.0);
            } finally {
                if (rt != null) rt.stopAllBots();
            }
        }

        @Test
        @DisplayName("TAI_XIU + jackpotScaleEnabled builds a scaler (AD-J3 — not gated out)")
        void buildsForTaiXiu() {
            group(GameType.TAI_XIU, true, 10_000_000L);

            service.start("g-1");

            BotGroupRuntime rt = runningGroups().get("g-1");
            try {
                assertThat(rt).isNotNull();
                assertThat(rt.getJackpotScaler())
                        .as("Tai Xiu is supported for jackpot-scale (AD-J3)")
                        .isNotNull();
            } finally {
                if (rt != null) rt.stopAllBots();
            }
        }

        @Test
        @DisplayName("jackpotScaleEnabled=false builds NO scaler even for an eligible type (AD-S3)")
        void noScalerWhenDisabled() {
            group(GameType.BETTING_MINI, false, 20_000_000L);

            service.start("g-1");

            BotGroupRuntime rt = runningGroups().get("g-1");
            try {
                assertThat(rt).isNotNull();
                assertThat(rt.getJackpotScaler())
                        .as("flag off ⇒ no scaler, bet path is today's (AD-S3)")
                        .isNull();
            } finally {
                if (rt != null) rt.stopAllBots();
            }
        }

        @Test
        @DisplayName("ineligible type (SLOT) builds NO scaler even when the flag is on (AD-S1)")
        void noScalerForIneligibleType() {
            group(GameType.SLOT, true, 20_000_000L);

            service.start("g-1");

            BotGroupRuntime rt = runningGroups().get("g-1");
            try {
                assertThat(rt).isNotNull();
                assertThat(rt.getJackpotScaler())
                        .as("SLOT shares no betting-mini round model ⇒ no scaler (AD-S1)")
                        .isNull();
            } finally {
                if (rt != null) rt.stopAllBots();
            }
        }
    }

    @Nested
    @DisplayName("crowd-aware coordinator build gating on start (CROWD_AWARE_COORDINATION AD-C6/AD-C10)")
    class CrowdAwareCoordinatorBuildTests {

        /**
         * A coordination-enabled group on an eligible game, short-circuiting bot
         * creation so the coordinator (built on the runtime before the bot loop) is
         * observable even when every createBot fails.
         */
        private void group(boolean coordinationEnabled, boolean crowdAware, CrowdCountSemantic semantic) {
            BotGroup g = BotGroup.builder()
                    .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                    .botCount(2).namePrefix("bot").password("pass")
                    .coordinationEnabled(coordinationEnabled)
                    .crowdAwareCoordination(crowdAware)
                    .maxAggregateStakePerRound(1000L).minBet(100L).betIncrement(100L)
                    .build();
            Map<Integer, Integer> affinities = new LinkedHashMap<>();
            affinities.put(0, 1);
            affinities.put(1, 1);
            Game gg = Game.builder().id("game-1").name("BauCua").gameType(GameType.BETTING_MINI)
                    .optionAffinities(affinities)
                    .crowdCountSemantic(semantic)
                    .build();
            Environment env = Environment.builder().id("env-1").name("Env").miniZoneName("zone").build();
            when(botGroupService.findById("g-1")).thenReturn(g);
            when(environmentService.findById("env-1")).thenReturn(env);
            when(gameService.findById("game-1")).thenReturn(gg);
            when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                    .thenThrow(new RuntimeException("intentional — captures only"));
        }

        @Test
        @DisplayName("coordinationEnabled + crowdAwareCoordination builds a crowd-aware coordinator carrying the game semantic")
        void buildsCrowdAwareCoordinator() {
            group(true, true, CrowdCountSemantic.BETS);

            service.start("g-1");

            BotGroupRuntime rt = runningGroups().get("g-1");
            try {
                assertThat(rt).isNotNull();
                BetCoordinator coordinator = rt.getCoordinator();
                assertThat(coordinator).as("coordination on ⇒ coordinator present").isNotNull();
                assertThat(coordinator.isCrowdAware())
                        .as("both flags set ⇒ crowd tier enabled (AD-C6)").isTrue();
                assertThat(coordinator.getCrowdCountSemantic())
                        .as("game's crowdCountSemantic threaded through (AD-C5/AD-C10)")
                        .isEqualTo("BETS");
            } finally {
                if (rt != null) rt.stopAllBots();
            }
        }

        @Test
        @DisplayName("coordinationEnabled + crowdAwareCoordination=false builds a NON-crowd coordinator (internal tier verbatim)")
        void buildsNonCrowdCoordinatorWhenFlagOff() {
            group(true, false, CrowdCountSemantic.PLAYERS);

            service.start("g-1");

            BotGroupRuntime rt = runningGroups().get("g-1");
            try {
                assertThat(rt).isNotNull();
                BetCoordinator coordinator = rt.getCoordinator();
                assertThat(coordinator).as("coordination on ⇒ coordinator present").isNotNull();
                assertThat(coordinator.isCrowdAware())
                        .as("crowdAwareCoordination off ⇒ internal tier, no crowd (AD-C6)")
                        .isFalse();
                // The semantic is still carried for the health block regardless of the bit.
                assertThat(coordinator.getCrowdCountSemantic()).isEqualTo("PLAYERS");
            } finally {
                if (rt != null) rt.stopAllBots();
            }
        }

        @Test
        @DisplayName("coordinationEnabled=false builds NO coordinator (crowd bit is moot — AD-C6)")
        void noCoordinatorWhenCoordinationOff() {
            group(false, true, CrowdCountSemantic.UNKNOWN);

            service.start("g-1");

            BotGroupRuntime rt = runningGroups().get("g-1");
            try {
                assertThat(rt).isNotNull();
                assertThat(rt.getCoordinator())
                        .as("no coordination ⇒ no coordinator, crowd-aware cannot run (AD-C6)")
                        .isNull();
            } finally {
                if (rt != null) rt.stopAllBots();
            }
        }
    }

    // ---- helpers ----

    @Nested
    @DisplayName("per-game / per-env info + status snapshots (observability)")
    class GameEnvSnapshotTests {

        @Test
        @DisplayName("listRunningGameInfo returns distinct (gameId, gameName, gameType, environmentId, product) over live bots")
        void listRunningGameInfo_distinctGames() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            Game slot = game("game-uuid-2", "SlotA", GameType.SLOT);

            BotGroupRuntime r1 = new BotGroupRuntime("g-1", 0, "env-1", "Staging");
            BotGroupRuntime r2 = new BotGroupRuntime("g-2", 0, "env-1", "Staging");
            try {
                // two bots on the same game (must dedupe) + one on another game
                putBots(r1, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua),
                        mockBotWithGame(BotStatus.STARTED, bauCua)));
                putBots(r2, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, slot)));
                runningGroups().put("g-1", r1);
                runningGroups().put("g-2", r2);

                var infos = service.listRunningGameInfo();

                assertThat(infos).hasSize(2);
                assertThat(infos).extracting(BotGroupBehaviorService.GameInfo::gameId)
                        .containsExactlyInAnyOrder("game-uuid-1", "game-uuid-2");
                assertThat(infos).contains(
                        new BotGroupBehaviorService.GameInfo("game-uuid-1", "BauCua", "BETTING_MINI",
                                "env-1", "116"),
                        new BotGroupBehaviorService.GameInfo("game-uuid-2", "SlotA", "SLOT",
                                "env-1", "116"));
            } finally {
                r1.getExecutor().shutdownNow();
                r2.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
                runningGroups().remove("g-2");
            }
        }

        @Test
        @DisplayName("listRunningEnvironmentInfo uses the threaded environmentName + product; falls back to id when null")
        void listRunningEnvironmentInfo_usesThreadedName() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime named = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            BotGroupRuntime unnamed = new BotGroupRuntime("g-2", 0, "env-2"); // no name, no product
            try {
                putBots(named, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua)));
                putBots(unnamed, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua)));
                runningGroups().put("g-1", named);
                runningGroups().put("g-2", unnamed);

                var infos = service.listRunningEnvironmentInfo();

                assertThat(infos).contains(
                        new BotGroupBehaviorService.EnvInfo("env-1", "Staging", "116"),
                        // fallback: id used as display when name not threaded in;
                        // a runtime built without a product carries a null product
                        new BotGroupBehaviorService.EnvInfo("env-2", "env-2", null));
            } finally {
                named.getExecutor().shutdownNow();
                unnamed.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
                runningGroups().remove("g-2");
            }
        }

        @Test
        @DisplayName("countBotsByGameAndStatus breaks bot counts down by status per game")
        void countBotsByGameAndStatus_breakdown() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime r1 = new BotGroupRuntime("g-1", 0, "env-1", "Staging");
            try {
                putBots(r1, List.of(
                        mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua),
                        mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua),
                        mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua),
                        mockBotWithGame(BotStatus.DEAD, bauCua)));
                runningGroups().put("g-1", r1);

                Map<BotGroupBehaviorService.GameStatusKey, Integer> counts =
                        service.countBotsByGameAndStatus();

                assertThat(counts.get(new BotGroupBehaviorService.GameStatusKey(
                        "game-uuid-1", "BauCua", BotStatus.CONNECTION_AUTHENTICATED,
                        "env-1", "BETTING_MINI", "116"))).isEqualTo(3);
                assertThat(counts.get(new BotGroupBehaviorService.GameStatusKey(
                        "game-uuid-1", "BauCua", BotStatus.DEAD,
                        "env-1", "BETTING_MINI", "116"))).isEqualTo(1);
            } finally {
                r1.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("countBotsByEnvAndStatus breaks bot counts down by status per environment, across groups")
        void countBotsByEnvAndStatus_breakdown() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            Game slot = game("game-uuid-2", "SlotA", GameType.SLOT);
            // two groups in the same environment must aggregate together
            BotGroupRuntime r1 = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            BotGroupRuntime r2 = new BotGroupRuntime("g-2", 0, "env-1", "Staging", "Group 2", "116");
            try {
                putBots(r1, List.of(
                        mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua),
                        mockBotWithGame(BotStatus.DEAD, bauCua)));
                putBots(r2, List.of(
                        mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, slot)));
                runningGroups().put("g-1", r1);
                runningGroups().put("g-2", r2);

                Map<BotGroupBehaviorService.EnvStatusKey, Integer> counts =
                        service.countBotsByEnvAndStatus();

                assertThat(counts.get(new BotGroupBehaviorService.EnvStatusKey(
                        "env-1", BotStatus.CONNECTION_AUTHENTICATED, "116"))).isEqualTo(2);
                assertThat(counts.get(new BotGroupBehaviorService.EnvStatusKey(
                        "env-1", BotStatus.DEAD, "116"))).isEqualTo(1);
            } finally {
                r1.getExecutor().shutdownNow();
                r2.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
                runningGroups().remove("g-2");
            }
        }

        // ---- VIPTALK_ALERTING_V2 Phase 1: per-environment aggregate accessors ----

        @Test
        @DisplayName("countManagedBotsByEnv aggregates groups sharing an environment and sums to getTotalManagedBots")
        void countManagedBotsByEnv_aggregatesAcrossGroups() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime r1 = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            BotGroupRuntime r2 = new BotGroupRuntime("g-2", 0, "env-1", "Staging", "Group 2", "116");
            BotGroupRuntime r3 = new BotGroupRuntime("g-3", 0, "env-2", "Prod", "Group 3", "097");
            try {
                putBots(r1, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua),
                        mockBotWithGame(BotStatus.DEAD, bauCua)));
                putBots(r2, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua)));
                putBots(r3, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua)));
                runningGroups().put("g-1", r1);
                runningGroups().put("g-2", r2);
                runningGroups().put("g-3", r3);

                Map<BotGroupBehaviorService.EnvKey, Integer> counts = service.countManagedBotsByEnv();

                assertThat(counts.get(new BotGroupBehaviorService.EnvKey("env-1", "116"))).isEqualTo(3);
                assertThat(counts.get(new BotGroupBehaviorService.EnvKey("env-2", "097"))).isEqualTo(1);
                // the per-env gauge must sum to the fleet gauge (AD-V2 verification)
                assertThat(counts.values().stream().mapToInt(Integer::intValue).sum())
                        .isEqualTo(service.getTotalManagedBots());
            } finally {
                r1.getExecutor().shutdownNow();
                r2.getExecutor().shutdownNow();
                r3.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
                runningGroups().remove("g-2");
                runningGroups().remove("g-3");
            }
        }

        // ---- PLUGIN_HOT_RELOAD Phase 1: per-(group, version) accessor ----

        @Test
        @DisplayName("countBotsByPluginVersion splits one group across versions and sums to getTotalManagedBots")
        void countBotsByPluginVersion_splitsAGroupAndSumsToTheFleet() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime r1 = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            BotGroupRuntime r2 = new BotGroupRuntime("g-2", 0, "env-2", "Prod", "Group 2", "097");
            try {
                // g-1 is the step-5/6 drain shape: one group, genuinely mixed-version. That is
                // the reason the version is a ROW label and deliberately not a group-level MDC
                // key — a group-level fact could not represent this state at all.
                putBots(r1, List.of(
                        mockBotWithPluginVersion(bauCua, null),
                        mockBotWithPluginVersion(bauCua, "builtin"),
                        mockBotWithPluginVersion(bauCua, "v2")));
                putBots(r2, List.of(mockBotWithPluginVersion(bauCua, null)));
                runningGroups().put("g-1", r1);
                runningGroups().put("g-2", r2);

                Map<BotGroupBehaviorService.PluginVersionKey, Integer> counts =
                        service.countBotsByPluginVersion();

                // An unset pluginVersion is `builtin`, not a second key — Phase 1 sets the
                // field nowhere, so if the default leaked through as null or "" the whole
                // family would render under an empty label on the first deploy.
                assertThat(counts.get(new BotGroupBehaviorService.PluginVersionKey(
                        "g-1", "env-1", "116", "builtin"))).isEqualTo(2);
                assertThat(counts.get(new BotGroupBehaviorService.PluginVersionKey(
                        "g-1", "env-1", "116", "v2"))).isEqualTo(1);
                assertThat(counts.get(new BotGroupBehaviorService.PluginVersionKey(
                        "g-2", "env-2", "097", "builtin"))).isEqualTo(1);
                assertThat(counts.keySet())
                        .allSatisfy(key -> assertThat(key.pluginVersion()).isNotBlank());

                // Verification P1-5 is exactly this equality, read off the scrape:
                // sum(bots_by_plugin_version) must equal bots_managed. A shortfall on the box
                // means a bot escaped the accounting, and this is where that is cheap to catch.
                assertThat(counts.values().stream().mapToInt(Integer::intValue).sum())
                        .as("sum(bots_by_plugin_version) == bots_managed")
                        .isEqualTo(service.getTotalManagedBots());
            } finally {
                r1.getExecutor().shutdownNow();
                r2.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
                runningGroups().remove("g-2");
            }
        }

        @Test
        @DisplayName("countBotsByPluginVersion is empty when no group is running")
        void countBotsByPluginVersion_isEmptyWithNoGroups() {
            // The idle-instance reading. An empty map means no rows are registered at all,
            // which is what MultiGauge.register(…, true) needs in order to retire stale rows
            // rather than leave a stopped group's bots on the dashboard forever.
            assertThat(service.countBotsByPluginVersion()).isEmpty();
        }

        @Test
        @DisplayName("countOpenWsByEnv uses isConnected() — not BotStatus — and sums to getOpenWsConnectionCount")
        void countOpenWsByEnv_usesIsConnectedPredicate() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime r1 = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            try {
                // Deliberately CONNECTION_AUTHENTICATED but NOT connected: the gauge must
                // follow isConnected(), mirroring the fleet gauge's predicate exactly.
                Bot connected = mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua);
                lenient().when(connected.isConnected()).thenReturn(true);
                Bot zombie = mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua);
                lenient().when(zombie.isConnected()).thenReturn(false);
                putBots(r1, List.of(connected, zombie));
                runningGroups().put("g-1", r1);

                Map<BotGroupBehaviorService.EnvKey, Integer> counts = service.countOpenWsByEnv();

                assertThat(counts.get(new BotGroupBehaviorService.EnvKey("env-1", "116"))).isEqualTo(1);
                assertThat(counts.values().stream().mapToInt(Integer::intValue).sum())
                        .isEqualTo(service.getOpenWsConnectionCount());
            } finally {
                r1.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("countOpenWsByEnv emits a zero row for an environment whose bots all lost the socket")
        void countOpenWsByEnv_emitsZeroRow() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime r1 = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            try {
                putBots(r1, List.of(mockBotWithGame(BotStatus.DEAD, bauCua)));
                runningGroups().put("g-1", r1);

                // The row must exist with value 0 — omitting it would make
                // EnvironmentSocketDown (Phase 3) silently never fire.
                assertThat(service.countOpenWsByEnv())
                        .containsEntry(new BotGroupBehaviorService.EnvKey("env-1", "116"), 0);
            } finally {
                r1.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }
    }

    @Nested
    @DisplayName("dead-group + balance snapshots (VIPTALK_ALERTING_V2 Phase 4)")
    class DeadGroupAndBalanceSnapshotTests {

        // ---- groups_dead_by_env: the labelled sibling of groups_dead_currently ----

        @Test
        @DisplayName("countDeadGroupsByEnv counts DEAD groups per environment and sums to countGroupsDeadCurrently")
        void countDeadGroupsByEnv_aggregatesAcrossGroups() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime dead1 = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            BotGroupRuntime dead2 = new BotGroupRuntime("g-2", 0, "env-1", "Staging", "Group 2", "116");
            BotGroupRuntime alive = new BotGroupRuntime("g-3", 0, "env-2", "Prod", "Group 3", "097");
            try {
                putBots(dead1, List.of(mockBotWithGame(BotStatus.DEAD, bauCua)));
                putBots(dead2, List.of(mockBotWithGame(BotStatus.DEAD, bauCua)));
                putBots(alive, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua)));
                dead1.markAsDead();
                dead2.markAsDead();
                runningGroups().put("g-1", dead1);
                runningGroups().put("g-2", dead2);
                runningGroups().put("g-3", alive);

                Map<BotGroupBehaviorService.EnvKey, Integer> counts = service.countDeadGroupsByEnv();

                assertThat(counts.get(new BotGroupBehaviorService.EnvKey("env-1", "116"))).isEqualTo(2);
                // the healthy environment still gets a row, value 0 — a gap would read
                // as "no data" on a dashboard rather than "nothing is dead"
                assertThat(counts.get(new BotGroupBehaviorService.EnvKey("env-2", "097"))).isEqualTo(0);
                // AD-V2: the labelled sibling must agree with the fleet aggregate it
                // does NOT replace.
                assertThat(counts.values().stream().mapToInt(Integer::intValue).sum())
                        .isEqualTo(service.countGroupsDeadCurrently());
            } finally {
                dead1.getExecutor().shutdownNow();
                dead2.getExecutor().shutdownNow();
                alive.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
                runningGroups().remove("g-2");
                runningGroups().remove("g-3");
            }
        }

        // ---- group_balance_ratio ----

        @Test
        @DisplayName("listGroupBalances averages expectedBalance over connected bots only, and reports the ratio")
        void listGroupBalances_averagesOverConnectedBots() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime r1 = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            try {
                // 200 + 400 over two CONNECTED bots = 300; the disconnected bot's 0 must
                // not drag the average down (that is the "group is restarting", not
                // "group is broke", case).
                putBots(r1, List.of(
                        mockBotWithBalance(bauCua, true, 200L, false, 1_000L),
                        mockBotWithBalance(bauCua, true, 400L, false, 1_000L),
                        mockBotWithBalance(bauCua, false, 0L, false, 1_000L)));
                runningGroups().put("g-1", r1);

                var balances = service.listGroupBalances();

                assertThat(balances).hasSize(1);
                var balance = balances.iterator().next();
                assertThat(balance.botGroupId()).isEqualTo("g-1");
                assertThat(balance.groupName()).isEqualTo("Group 1");
                assertThat(balance.environmentId()).isEqualTo("env-1");
                assertThat(balance.product()).isEqualTo("116");
                assertThat(balance.gameId()).isEqualTo("game-uuid-1");
                assertThat(balance.gameName()).isEqualTo("BauCua");
                assertThat(balance.avgExpectedBalance()).isEqualTo(300L);
                assertThat(balance.depositAmount()).isEqualTo(1_000L);
                assertThat(balance.ratio()).isEqualTo(0.30);
            } finally {
                r1.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("listGroupBalances skips auto-deposit groups — dipping below 10% there is normal operation")
        void listGroupBalances_skipsAutoDepositGroups() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime auto = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            try {
                putBots(auto, List.of(mockBotWithBalance(bauCua, true, 1L, true, 1_000L)));
                runningGroups().put("g-1", auto);

                assertThat(service.listGroupBalances()).isEmpty();
            } finally {
                auto.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("listGroupBalances skips a group with no connected bot — a stopped group must not read 0 and page")
        void listGroupBalances_skipsGroupWithNoActiveBots() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime reconnecting = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            try {
                putBots(reconnecting, List.of(
                        mockBotWithBalance(bauCua, false, 5_000L, false, 1_000L),
                        mockBotWithBalance(bauCua, false, 5_000L, false, 1_000L)));
                runningGroups().put("g-1", reconnecting);

                assertThat(service.listGroupBalances()).isEmpty();
            } finally {
                reconnecting.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("listGroupBalances falls back to the default deposit amount when the group carries none")
        void listGroupBalances_fallsBackToDefaultDepositAmount() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime r1 = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            try {
                // depositAmount 0 = unset, exactly as Bot.resolveDepositAmount() reads it.
                // A zero denominator would make the ratio NaN/Infinity and silence the rule.
                putBots(r1, List.of(mockBotWithBalance(bauCua, true,
                        Bot.DEFAULT_DEPOSIT_AMOUNT / 20, false, 0L)));
                runningGroups().put("g-1", r1);

                var balance = service.listGroupBalances().iterator().next();

                assertThat(balance.depositAmount()).isEqualTo(Bot.DEFAULT_DEPOSIT_AMOUNT);
                assertThat(balance.ratio()).isEqualTo(0.05);
            } finally {
                r1.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("listGroupBalances tolerates a bot group with no bots and no behavior config")
        void listGroupBalances_isNullSafe() {
            BotGroupRuntime empty = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            BotGroupRuntime noConfig = new BotGroupRuntime("g-2", 0, "env-1", "Staging", "Group 2", "116");
            try {
                // A bot whose configuration carries no behaviorConfig: the gauge refresh
                // runs every 10 s and must never throw out of it.
                putBots(noConfig, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED,
                        game("game-uuid-1", "BauCua", GameType.BETTING_MINI))));
                runningGroups().put("g-1", empty);
                runningGroups().put("g-2", noConfig);

                assertThatCode(() -> service.listGroupBalances()).doesNotThrowAnyException();
                assertThat(service.listGroupBalances()).isEmpty();
            } finally {
                empty.getExecutor().shutdownNow();
                noConfig.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
                runningGroups().remove("g-2");
            }
        }

        /**
         * {@code Bot.expectedCurrentBalance} is constructed as {@code -100,000,000} and is
         * only ever written from a server read inside {@code checkBalance()} /
         * {@code deposit()}, both of which run from {@code onNewSession()} — i.e. from the
         * <em>first round</em>, not from connecting. The real value is pinned in
         * {@code bot-engine BalanceGaugeSemanticsTest#expectedBalanceIsANegativeSentinelBeforeTheFirstServerRead};
         * this test covers what {@code listGroupBalances} does with it.
         * <p>
         * {@code isConnected()} is true well before the first round, so the sentinel used
         * to be published as a negative ratio, and {@code GroupBalanceLow} ({@code < 0.10})
         * fired once its 5 m {@code for:} window elapsed. Harmless when rounds arrive every
         * 30–60 s; it bit in the one case that matters — a game delivering no rounds at all
         * — where it duplicated {@code GameNoRounds} into the product room with nonsense
         * wording ("average balance at -2,000% of its deposit"). The plan's own Phase 4
         * verification expects every value in {@code (0, ~1.0]}, and now it holds: a bot
         * with no completed server read ({@code lastFetchedBalance == -1}) is not counted,
         * and a group where none has is not published.
         */
        @Test
        @DisplayName("a connected-but-not-yet-playing group publishes no row at all, "
                + "rather than the uninitialised sentinel")
        void listGroupBalances_skipsGroupsWithNoEstablishedBalance() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime fresh = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            try {
                // Exactly what a real Bot reports between "socket authenticated" and
                // "first round received" — see BalanceGaugeSemanticsTest.
                putBots(fresh, List.of(
                        mockBotWithBalance(bauCua, true, -100_000_000L, false, 5_000_000L),
                        mockBotWithBalance(bauCua, true, -100_000_000L, false, 5_000_000L)));
                runningGroups().put("g-1", fresh);

                assertThat(service.listGroupBalances())
                        .as("no row means no series means no alert — the honest state for "
                                + "a balance nobody has read yet")
                        .isEmpty();
            } finally {
                fresh.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("a group part-way through its first round averages only the bots that have read a balance")
        void listGroupBalances_averagesOnlyBotsWithAnEstablishedBalance() {
            // The realistic transitional shape: rounds have started, some bots have run
            // checkBalance() and some have not. Averaging the sentinel in would drag the
            // group's ratio negative and fire on a group that is fine.
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime mixed = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            try {
                putBots(mixed, List.of(
                        mockBotWithBalance(bauCua, true, 600L, false, 1_000L),
                        mockBotWithBalance(bauCua, true, 400L, false, 1_000L),
                        mockBotWithBalance(bauCua, true, -100_000_000L, false, 1_000L)));
                runningGroups().put("g-1", mixed);

                var balance = service.listGroupBalances().iterator().next();

                assertThat(balance.avgExpectedBalance()).isEqualTo(500L);
                assertThat(balance.ratio()).isEqualTo(0.50);
            } finally {
                mixed.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("a group is dropped from the gauge the moment its last bot disconnects, "
                + "so a dying group never freezes at its last ratio")
        void listGroupBalances_dropsTheGroupWhenTheLastBotDisconnects() {
            // Complements InfoGaugeRefresherTest's row-removal test from the source side:
            // the refresher can only drop a row if this method stops emitting it. A group
            // whose bots have all dropped their sockets (a DEAD group, or one mid-restart)
            // must vanish rather than keep publishing the balance it had when it died —
            // GroupBalanceLow has no way to tell a stale row from a live one.
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime dying = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            try {
                Bot connected = mockBotWithBalance(bauCua, true, 100L, false, 10_000L);
                Bot disconnected = mockBotWithBalance(bauCua, false, 100L, false, 10_000L);
                putBots(dying, List.of(connected, disconnected));
                runningGroups().put("g-1", dying);

                assertThat(service.listGroupBalances()).hasSize(1);

                when(connected.isConnected()).thenReturn(false);

                assertThat(service.listGroupBalances())
                        .as("no connected bot ⇒ no row ⇒ the series disappears on the next refresh")
                        .isEmpty();
            } finally {
                dying.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }
    }

    @Nested
    @DisplayName("`product` label cardinality + provenance (VIPTALK_ALERTING_V2 Phase 1)")
    class ProductLabelCardinalityTests {

        @Test
        @DisplayName("adding product/environmentId/gameType splits no game rows: still one per (gameId, status)")
        void gameRowsAreNotSplitByTheNewLabels() {
            // AD-V1's claim, checked over the real aggregation: two groups on the SAME
            // game must collapse into the same keys, so the row count is still exactly
            // the number of distinct (gameId, status) pairs. A source that were not
            // functionally determined by gameId would show up here as extra rows.
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime r1 = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            BotGroupRuntime r2 = new BotGroupRuntime("g-2", 0, "env-1", "Staging", "Group 2", "116");
            try {
                putBots(r1, List.of(
                        mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua),
                        mockBotWithGame(BotStatus.DEAD, bauCua)));
                putBots(r2, List.of(
                        mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua),
                        mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua)));
                runningGroups().put("g-1", r1);
                runningGroups().put("g-2", r2);

                Map<BotGroupBehaviorService.GameStatusKey, Integer> counts =
                        service.countBotsByGameAndStatus();

                long distinctGameAndStatus = counts.keySet().stream()
                        .map(k -> k.gameId() + "|" + k.status())
                        .distinct().count();
                assertThat(counts).hasSize((int) distinctGameAndStatus).hasSize(2);
                assertThat(counts.get(new BotGroupBehaviorService.GameStatusKey(
                        "game-uuid-1", "BauCua", BotStatus.CONNECTION_AUTHENTICATED,
                        "env-1", "BETTING_MINI", "116"))).isEqualTo(3);
            } finally {
                r1.getExecutor().shutdownNow();
                r2.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
                runningGroups().remove("g-2");
            }
        }

        @Test
        @DisplayName("per-env rows stay one per environment while groups agree on the product")
        void envRowsAreNotSplitByProduct() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime r1 = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            BotGroupRuntime r2 = new BotGroupRuntime("g-2", 0, "env-1", "Staging", "Group 2", "116");
            try {
                putBots(r1, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua)));
                putBots(r2, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua)));
                runningGroups().put("g-1", r1);
                runningGroups().put("g-2", r2);

                assertThat(service.countManagedBotsByEnv()).hasSize(1);
                assertThat(service.countOpenWsByEnv()).hasSize(1);
                assertThat(service.countBotsByEnvAndStatus()).hasSize(1);
            } finally {
                r1.getExecutor().shutdownNow();
                r2.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
                runningGroups().remove("g-2");
            }
        }

        @Test
        @DisplayName("sum(bots_managed_by_env) == bots_managed even if two groups on one env disagree on product")
        void perEnvSumMatchesTheFleetGaugeEvenUnderProductChurn() {
            // Reachable transiently: Environment.productCode is read at group START, so a
            // product edit between two starts leaves two runtimes on one env carrying
            // different products. That splits the row (identity churn, self-healing on
            // restart) — but the Phase 1 verification query, sum(per-env) - fleet == 0,
            // must still hold, because the split is a partition and not a duplication.
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime before = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            BotGroupRuntime after = new BotGroupRuntime("g-2", 0, "env-1", "Staging", "Group 2", "097");
            try {
                putBots(before, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua),
                        mockBotWithGame(BotStatus.DEAD, bauCua)));
                putBots(after, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua)));
                runningGroups().put("g-1", before);
                runningGroups().put("g-2", after);

                Map<BotGroupBehaviorService.EnvKey, Integer> counts = service.countManagedBotsByEnv();

                assertThat(counts).hasSize(2);
                assertThat(counts.values().stream().mapToInt(Integer::intValue).sum())
                        .isEqualTo(service.getTotalManagedBots());
            } finally {
                before.getExecutor().shutdownNow();
                after.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
                runningGroups().remove("g-2");
            }
        }

        @Test
        @DisplayName("one authority: a Game contradicting its Environment still labels every row with the environment's product")
        void gameRowsFollowTheEnvironmentProductWhenTheGameContradictsIt() {
            // The environment is the single source of `product` (it is the brand's gateway;
            // Game.productCode is a nullable convenience copy). Before this, game rows read
            // Game.productCode and env rows read Environment.productCode, so a misfiled game
            // sent its GameNoRounds and its EnvironmentSocketDown to two different product
            // rooms — and broke GameNoRounds outright, since its `unless` only fires when
            // both operands agree on every label after sum by(...).
            Game misfiled = game("game-uuid-9", "Misfiled", GameType.BETTING_MINI,
                    "env-1", ProductCode.P_097);
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            try {
                putBots(runtime, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, misfiled)));
                runningGroups().put("g-1", runtime);

                assertThat(service.countBotsByGameAndStatus().keySet())
                        .allSatisfy(k -> assertThat(k.product()).isEqualTo("116"));
                assertThat(service.listRunningGameInfo())
                        .allSatisfy(i -> assertThat(i.product()).isEqualTo("116"));
                assertThat(service.countManagedBotsByEnv().keySet())
                        .allSatisfy(k -> assertThat(k.product()).isEqualTo("116"));
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("one authority: a Game with no environmentId still labels every row with the group's environment")
        void gameRowsFollowTheGroupEnvironmentWhenTheGameHasNone() {
            // BotGroupService only rejects a Game whose environmentId DISAGREES with the
            // group's — a null one is a supported state. Before this, such a game produced
            // gauge rows carrying environmentId="" while bot_messages_total carried the real
            // UUID, so GameNoRounds' `unless` matched nothing and the rule fired permanently,
            // critical + audience:product, for a perfectly healthy game.
            Game noEnv = game("game-uuid-8", "NoEnv", GameType.BETTING_MINI, null, ProductCode.P_116);
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1", "Staging", "Group 1", "116");
            try {
                putBots(runtime, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, noEnv)));
                runningGroups().put("g-1", runtime);

                assertThat(service.countBotsByGameAndStatus().keySet())
                        .allSatisfy(k -> assertThat(k.environmentId()).isEqualTo("env-1"));
                assertThat(service.listRunningGameInfo())
                        .allSatisfy(i -> assertThat(i.environmentId()).isEqualTo("env-1"));
                assertThat(service.countBotsByEnvAndStatus().keySet())
                        .allSatisfy(k -> assertThat(k.environmentId()).isEqualTo("env-1"));
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("a runtime with no environment (legacy/ad-hoc) falls back to the Game's, so rows still label")
        void gameRowsFallBackToTheGameEnvironmentWhenTheRuntimeHasNone() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, null, null);
            try {
                putBots(runtime, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua)));
                runningGroups().put("g-1", runtime);

                assertThat(service.countBotsByGameAndStatus().keySet())
                        .allSatisfy(k -> assertThat(k.environmentId()).isEqualTo("env-1"));
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }

        @Test
        @DisplayName("a runtime with no product (legacy/ad-hoc) falls back to the Game's, so rows still label")
        void gameRowsFallBackToTheGameProductWhenTheRuntimeHasNone() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime runtime = new BotGroupRuntime("g-1", 0, "env-1", "Staging");
            try {
                putBots(runtime, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua)));
                runningGroups().put("g-1", runtime);

                assertThat(service.countBotsByGameAndStatus().keySet())
                        .allSatisfy(k -> assertThat(k.product()).isEqualTo("116"));
            } finally {
                runtime.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
            }
        }
    }

    private static Game game(String id, String name, GameType type) {
        // environmentId + productCode are what make `product` functionally dependent
        // on labels already present (VIPTALK_ALERTING_V2 AD-V1), so the gauge-support
        // tuples read them straight off the Game the bot holds.
        return game(id, name, type, "env-1", ProductCode.P_116);
    }

    private static Game game(String id, String name, GameType type,
                             String environmentId, ProductCode productCode) {
        Game g = new Game();
        g.setId(id);
        g.setName(name);
        g.setGameType(type);
        g.setEnvironmentId(environmentId);
        g.setProductCode(productCode);
        return g;
    }

    private static Bot mockBotWithGame(BotStatus status, Game game) {
        Bot b = mock(Bot.class);
        BotConfiguration config = BotConfiguration.builder()
                .game(game)
                .environmentId("env-1")
                .botGroupId("g-1")
                .botIndex(1)
                .build();
        lenient().when(b.getStatus()).thenReturn(status);
        lenient().when(b.getConfiguration()).thenReturn(config);
        return b;
    }

    /**
     * A bot carrying the one thing {@code countBotsByPluginVersion} reads beyond identity.
     * <p>
     * {@code getPluginVersion()} is answered by delegating to the configuration exactly as
     * the real {@code Bot} does, rather than stubbed to a literal: Phase 1 sets the field
     * nowhere, so the interesting case is precisely the unset one, and a stub returning a
     * literal would test the stub instead of {@code resolvePluginVersion()}'s default.
     */
    private static Bot mockBotWithPluginVersion(Game game, String pluginVersion) {
        Bot b = mock(Bot.class);
        BotConfiguration config = BotConfiguration.builder()
                .game(game)
                .environmentId("env-1")
                .botGroupId("g-1")
                .botIndex(1)
                .pluginVersion(pluginVersion)
                .build();
        lenient().when(b.getStatus()).thenReturn(BotStatus.CONNECTION_AUTHENTICATED);
        lenient().when(b.getConfiguration()).thenReturn(config);
        lenient().when(b.getPluginVersion()).thenAnswer(inv -> config.resolvePluginVersion());
        return b;
    }

    /**
     * A bot carrying the two things {@code listGroupBalances} reads beyond identity:
     * the group-uniform {@link BotBehaviorConfig} (auto-deposit flag + deposit
     * amount) and its own {@code expectedCurrentBalance} — AD-V12's numerator, NOT
     * {@code lastFetchedBalance}.
     */
    private static Bot mockBotWithBalance(Game game, boolean connected, long expectedBalance,
                                          boolean autoDepositEnabled, long depositAmount) {
        Bot b = mock(Bot.class);
        BotConfiguration config = BotConfiguration.builder()
                .game(game)
                .environmentId("env-1")
                .botGroupId("g-1")
                .botIndex(1)
                .behaviorConfig(BotBehaviorConfig.builder()
                        .autoDepositEnabled(autoDepositEnabled)
                        .depositAmount(depositAmount)
                        .build())
                .build();
        lenient().when(b.getConfiguration()).thenReturn(config);
        lenient().when(b.isConnected()).thenReturn(connected);
        lenient().when(b.getExpectedBalance()).thenReturn(expectedBalance);
        // Keep the two balance fields coherent with a real Bot: expectedCurrentBalance is
        // only ever written from a server read, and lastFetchedBalance (sentinel -1) is
        // written by the same read — so a negative expected balance means no read has
        // happened yet, and lastFetchedBalance must still be -1. listGroupBalances now
        // filters on that, so a mock that reported 0 here would hide the very case the
        // filter exists for.
        lenient().when(b.getLastFetchedBalance())
                .thenReturn(expectedBalance >= 0 ? expectedBalance : -1L);
        return b;
    }

    private static Bot mockBot(BotStatus status, boolean connected) {
        Bot b = mock(Bot.class);
        // lenient: not every test exercises every accessor (monitorHealth uses only status/isConnected;
        // getHealth uses everything). Without lenient, strict mode would flag unused stubs.
        lenient().when(b.getStatus()).thenReturn(status);
        lenient().when(b.isConnected()).thenReturn(connected);
        // Provide non-null counters so BotHealthDTO mapping (.get() calls) doesn't NPE.
        lenient().when(b.getTotalBetsPlaced()).thenReturn(new AtomicLong(0));
        lenient().when(b.getTotalBetAmount()).thenReturn(new AtomicLong(0));
        // Phase-3 stats accumulators: computeStats (via getHealth) reads these,
        // so the mock must mirror a real Bot's non-null AtomicLong fields.
        lenient().when(b.getRoundsObserved()).thenReturn(new AtomicLong(0));
        lenient().when(b.getCumulativeWinnings()).thenReturn(new AtomicLong(0));
        return b;
    }
}
