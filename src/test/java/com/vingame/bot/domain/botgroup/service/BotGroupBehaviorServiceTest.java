package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.core.BotStatus;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.bot.strategy.StrategyId;
import com.vingame.bot.domain.bot.strategy.WeightedStrategy;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyId;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.domain.botgroup.dto.BotGroupHealthDTO;
import com.vingame.bot.domain.botgroup.dto.BotHealthDTO;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.infrastructure.observability.BotMetrics;
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
        @DisplayName("start() of a SLOT group propagates the group's slotStrategyId (RANDOM) into every BotConfiguration")
        void startPropagatesSelectedSlotStrategy() {
            BotGroup group = BotGroup.builder()
                    .id("g-1")
                    .name("Slot Group")
                    .environmentId("env-1")
                    .gameId("game-1")
                    .botCount(3)
                    .namePrefix("bot")
                    .password("pass")
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
                    .allSatisfy(cfg -> assertThat(cfg.getSlotStrategyId()).isEqualTo(SlotStrategyId.RANDOM));

            BotGroupRuntime rt = runningGroups().get("g-1");
            if (rt != null) rt.stopAllBots();
        }

        @Test
        @DisplayName("start() of a SLOT group with null slotStrategyId defaults each BotConfiguration to FIXED")
        void startDefaultsToFixedWhenUnset() {
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
                            .as("null group slotStrategyId should default to FIXED at build time")
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

    // ---- helpers ----

    private static Bot mockBot(BotStatus status, boolean connected) {
        Bot b = mock(Bot.class);
        // lenient: not every test exercises every accessor (monitorHealth uses only status/isConnected;
        // getHealth uses everything). Without lenient, strict mode would flag unused stubs.
        lenient().when(b.getStatus()).thenReturn(status);
        lenient().when(b.isConnected()).thenReturn(connected);
        // Provide non-null counters so BotHealthDTO mapping (.get() calls) doesn't NPE.
        lenient().when(b.getTotalBetsPlaced()).thenReturn(new AtomicLong(0));
        lenient().when(b.getTotalBetAmount()).thenReturn(new AtomicLong(0));
        return b;
    }
}
