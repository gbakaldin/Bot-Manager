package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.bot.coordination.BetCoordinator;
import com.vingame.bot.domain.bot.coordination.JackpotScaler;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.core.BotStatus;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.WeightedStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
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
        // {@code restart} (RESTART_LIFECYCLE_FIX Architecture Decision 6) is
        // stricter — it throws IllegalStateException when start produces zero
        // bots, because a restart begins with a healthy running group and
        // "zero bots with targetStatus=ACTIVE" is the exact symptom that hid
        // the 2026-06-09 outage. See BotGroupBehaviorServiceRestartTest.
        // Direct {@code start} remains tolerant (e.g. first-time start of a
        // freshly-configured group where some bot rows may legitimately fail).
        @Test
        @DisplayName("Should still complete start with zero bots when every createBot throws (per-bot failures are swallowed by createBotsInParallel)")
        void shouldCompleteWithZeroBotsWhenAllCreateBotsFail() {
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
            service.start("g-1");

            // Runtime remains (zero bots, but the group is registered as running).
            assertThat(runningGroups()).containsKey("g-1");
            BotGroupRuntime runtime = runningGroups().get("g-1");
            assertThat(runtime.getBotInstances()).isEmpty();

            // Group was persisted as ACTIVE with lastStartedAt set
            verify(botGroupService).save(botGroupCaptor.capture());
            BotGroup saved = botGroupCaptor.getValue();
            assertThat(saved.getTargetStatus()).isEqualTo(BotGroupStatus.ACTIVE);
            assertThat(saved.getLastStartedAt()).isNotNull();

            // Cleanup the side-effect runtime
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
                    .strategyMix(List.of(new WeightedStrategy(StrategyId.RANDOM, 1.0)))
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
                    .allSatisfy(cfg -> assertThat(cfg.getStrategyId()).isEqualTo(StrategyId.RANDOM));

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
                            .isEqualTo(StrategyId.RANDOM));

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
                lenient().when(b.getStrategyId()).thenReturn(StrategyId.RANDOM);
                putBots(runtime, List.of(b));
                runningGroups().put("g-1", runtime);

                BotGroupHealthDTO dto = service.getHealth("g-1");

                assertThat(dto.getBots()).hasSize(1);
                BotHealthDTO botDto = dto.getBots().get(0);
                assertThat(botDto.getStrategyId()).isEqualTo(StrategyId.RANDOM);
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
                    .slotStrategyId(SlotStrategyId.RANDOM)
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
                            .isEqualTo(SlotStrategyId.FIXED));

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
                            .isEqualTo(SlotStrategyId.FIXED));

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
                    .slotStrategyId(SlotStrategyId.RANDOM)
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
        @DisplayName("listRunningGameInfo returns distinct (gameId, gameName, gameType) over live bots")
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
                        new BotGroupBehaviorService.GameInfo("game-uuid-1", "BauCua", "BETTING_MINI"),
                        new BotGroupBehaviorService.GameInfo("game-uuid-2", "SlotA", "SLOT"));
            } finally {
                r1.getExecutor().shutdownNow();
                r2.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
                runningGroups().remove("g-2");
            }
        }

        @Test
        @DisplayName("listRunningEnvironmentInfo uses the threaded environmentName; falls back to id when null")
        void listRunningEnvironmentInfo_usesThreadedName() {
            Game bauCua = game("game-uuid-1", "BauCua", GameType.BETTING_MINI);
            BotGroupRuntime named = new BotGroupRuntime("g-1", 0, "env-1", "Staging");
            BotGroupRuntime unnamed = new BotGroupRuntime("g-2", 0, "env-2"); // no name
            try {
                putBots(named, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua)));
                putBots(unnamed, List.of(mockBotWithGame(BotStatus.CONNECTION_AUTHENTICATED, bauCua)));
                runningGroups().put("g-1", named);
                runningGroups().put("g-2", unnamed);

                var infos = service.listRunningEnvironmentInfo();

                assertThat(infos).contains(
                        new BotGroupBehaviorService.EnvInfo("env-1", "Staging"),
                        // fallback: id used as display when name not threaded in
                        new BotGroupBehaviorService.EnvInfo("env-2", "env-2"));
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
                        "game-uuid-1", "BauCua", BotStatus.CONNECTION_AUTHENTICATED))).isEqualTo(3);
                assertThat(counts.get(new BotGroupBehaviorService.GameStatusKey(
                        "game-uuid-1", "BauCua", BotStatus.DEAD))).isEqualTo(1);
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
            BotGroupRuntime r1 = new BotGroupRuntime("g-1", 0, "env-1", "Staging");
            BotGroupRuntime r2 = new BotGroupRuntime("g-2", 0, "env-1", "Staging");
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
                        "env-1", BotStatus.CONNECTION_AUTHENTICATED))).isEqualTo(2);
                assertThat(counts.get(new BotGroupBehaviorService.EnvStatusKey(
                        "env-1", BotStatus.DEAD))).isEqualTo(1);
            } finally {
                r1.getExecutor().shutdownNow();
                r2.getExecutor().shutdownNow();
                runningGroups().remove("g-1");
                runningGroups().remove("g-2");
            }
        }
    }

    private static Game game(String id, String name, GameType type) {
        Game g = new Game();
        g.setId(id);
        g.setName(name);
        g.setGameType(type);
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
