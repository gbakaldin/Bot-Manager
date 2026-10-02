package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.gateway.GatewayRequestScope;
import com.vingame.bot.common.gateway.RequestTier;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.bot.strategy.StrategyAssignment;
import com.vingame.bot.domain.bot.strategy.WeightedStrategy;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.model.RegistrationState;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.infrastructure.gateway.GatewayBudget;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetMode;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator;
import com.vingame.bot.infrastructure.observability.ScopedDebugEscalator;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
import com.vingame.bot.infrastructure.runtime.BotGroupRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>Accounts registered by a {@code botCount} raise join the running group without a restart</b>
 * (BOT_PROVISIONING AD-12, Phase 1).
 * <p>
 * No socket and no gateway: {@code BotFactory} is a mock that records the configuration of every
 * bot it is asked for, so "which indices were built" is read straight off it.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Bot group attach — new accounts join a running group")
class BotGroupBehaviorServiceAttachTest {

    @Mock private BotGroupService botGroupService;
    @Mock private EnvironmentService environmentService;
    @Mock private GameService gameService;
    @Mock private BotFactory botFactory;
    @Mock private BotMetrics botMetrics;
    @Mock private SessionAggregationService sessionAggregationService;
    @Mock private GroupLifecycleAggregator groupLifecycleAggregator;
    @Mock private ScopedDebugEscalator scopedDebugEscalator;
    @Mock private GatewayBudgetRegistry gatewayBudgetRegistry;

    @InjectMocks
    private BotGroupBehaviorService service;

    private final List<BotConfiguration> built = new CopyOnWriteArrayList<>();
    private final List<int[]> reservations = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger releases =
            new java.util.concurrent.atomic.AtomicInteger();
    private final List<String> budgetLog = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        when(gatewayBudgetRegistry.forEnvironment(any(), any(), any())).thenReturn(new RecordingBudget());
        when(botGroupService.save(any(BotGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        when(environmentService.findById(anyString())).thenReturn(environment());
        when(gameService.findById(anyString())).thenReturn(game());
        when(botFactory.createBot(anyString(), any())).thenAnswer(inv -> {
            BotConfiguration configuration = inv.getArgument(1);
            built.add(configuration);
            return stubBot("bot" + configuration.getBotIndex());
        });

        ReflectionTestUtils.setField(service, "deadBotGroupThreshold", 0.80);
        ReflectionTestUtils.setField(service, "botCreationParallelism", 4);
        ReflectionTestUtils.setField(service, "watchdogTimeoutSeconds", 180L);
        ReflectionTestUtils.setField(service, "periodicLogoutEnabled", false);
        ReflectionTestUtils.setField(service, "periodicLogoutIntervalMinutes", 60);
        ReflectionTestUtils.setField(service, "reconnectDelaySeconds", 5);
        ReflectionTestUtils.setField(service, "activationZone", "Asia/Ho_Chi_Minh");
    }

    @AfterEach
    void tearDown() {
        runningGroups().values().forEach(r -> {
            try {
                r.stopAllBots();
            } catch (Exception ignored) {
                // best-effort
            }
        });
        service.shutdown();
    }

    @Test
    @DisplayName("the constructor sets builtUpTo to the start's botCount")
    void runtimeStartsBuiltUpToBotCount() {
        assertThat(new BotGroupRuntime("g", 7, "env").getBuiltUpTo()).isEqualTo(7);
    }

    @Test
    @DisplayName("attach builds exactly the new indices, advances builtUpTo and keeps the old bots")
    void attachBuildsExactlyTheDelta() {
        startWith(3);
        List<Bot> original = new ArrayList<>(runtime().getBotInstances());
        built.clear();
        reservations.clear();

        when(botGroupService.findById("g-1")).thenReturn(activeGroup(5));
        service.attach("g-1");

        assertThat(indices()).containsExactlyInAnyOrder(4, 5);
        assertThat(runtime().getBuiltUpTo()).isEqualTo(5);
        assertThat(runtime().getBotInstances())
                .as("the three bots already playing are still there, untouched")
                .hasSize(5)
                .containsAll(original);
        original.forEach(bot -> verify(bot, never()).cleanup());
        assertThat(reservations)
                .as("ESSENTIAL x 3 x added, exactly as a start declares it")
                .singleElement()
                .satisfies(r -> assertThat(r).containsExactly(RequestTier.ESSENTIAL.ordinal(), 6));
        verify(groupLifecycleAggregator).expectAdditional("g-1", "Group", 2);
    }

    @Test
    @DisplayName("a duplicate completion event builds nothing")
    void duplicateEventIsANoOp() {
        startWith(3);
        when(botGroupService.findById("g-1")).thenReturn(activeGroup(5));
        service.attach("g-1");
        built.clear();
        reservations.clear();

        service.attach("g-1");

        assertThat(built).isEmpty();
        assertThat(reservations).as("nothing to build, nothing declared").isEmpty();
        assertThat(runtime().getBotInstances()).hasSize(5);
    }

    @Test
    @DisplayName("a lowered botCount attaches nothing and stops nobody")
    void loweredBotCountIsANoOp() {
        startWith(5);
        built.clear();

        when(botGroupService.findById("g-1")).thenReturn(activeGroup(3));
        service.attach("g-1");

        assertThat(built).isEmpty();
        assertThat(runtime().getBotInstances()).hasSize(5);
    }

    @Test
    @DisplayName("no attach when the group has no runtime")
    void noAttachWithoutRuntime() {
        when(botGroupService.findById("g-1")).thenReturn(activeGroup(5));

        service.attachIfRunning("g-1");
        service.onRegistrationCompleted(new RegistrationCompletedEvent("g-1"));

        assertThat(built).isEmpty();
        assertThat(runningGroups()).isEmpty();
        assertThat(service.getActualStatus("g-1"))
                .as("no attempt was opened, so nothing reads STARTING")
                .isEqualTo(BotGroupStatus.STOPPED);
    }

    @Test
    @DisplayName("no attach when the runtime is DEAD — the reclaim on the next start builds everything")
    void noAttachForDeadRuntime() {
        startWith(3);
        runtime().markAsDead();
        built.clear();

        when(botGroupService.findById("g-1")).thenReturn(activeGroup(5));
        service.attachIfRunning("g-1");
        service.attach("g-1");

        assertThat(built).isEmpty();
        assertThat(runtime().getBuiltUpTo()).isEqualTo(3);
    }

    @Test
    @DisplayName("no attach when the group is not ACTIVE or is registering again")
    void noAttachForStoppedOrPendingGroup() {
        startWith(3);
        built.clear();

        BotGroup stopped = activeGroup(5);
        stopped.setTargetStatus(BotGroupStatus.STOPPED);
        when(botGroupService.findById("g-1")).thenReturn(stopped);
        service.attach("g-1");

        BotGroup pending = activeGroup(8);
        pending.setRegistrationState(RegistrationState.PENDING);
        when(botGroupService.findById("g-1")).thenReturn(pending);
        service.attach("g-1");

        assertThat(built).isEmpty();
        assertThat(runtime().getBuiltUpTo()).isEqualTo(3);
    }

    @Test
    @DisplayName("the attached bots' strategies equal the slice of a full-group assignment")
    void strategySliceEqualsFullAssignmentSlice() {
        List<WeightedStrategy> mix = List.of(
                new WeightedStrategy("RANDOM", 0.5),
                new WeightedStrategy("MARTINGALE", 0.3),
                new WeightedStrategy("FIXED_BET", 0.2));
        BotGroup group = activeGroup(10);
        group.setStrategyMix(mix);

        List<String> identifiers = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            identifiers.add("bot" + i);
        }
        Map<String, String> full = StrategyAssignment.assign(mix, identifiers);

        Map<String, String> slice = BotGroupBehaviorService.attachStrategySlice(group, 7, 10);

        assertThat(slice.keySet()).containsExactly("bot7", "bot8", "bot9", "bot10");
        slice.forEach((id, strategy) -> assertThat(strategy).isEqualTo(full.get(id)));

        // And the attach really hands those strategies to the bots it builds.
        BotGroup startGroup = activeGroup(6);
        startGroup.setStrategyMix(mix);
        when(botGroupService.findById("g-1")).thenReturn(startGroup);
        service.start("g-1");
        built.clear();
        when(botGroupService.findById("g-1")).thenReturn(group);
        service.attach("g-1");

        assertThat(built).hasSize(4);
        built.forEach(c -> assertThat(c.getStrategyId())
                .as("strategy of bot%d", c.getBotIndex())
                .isEqualTo(full.get("bot" + c.getBotIndex())));
    }

    @Test
    @DisplayName("a completion event refused while a start is in flight is caught up when the start finishes")
    void eventDuringAnInFlightStartIsCaughtUp() throws Exception {
        // The race: the start read botCount=3; while it is building, a raise to 5 finishes
        // registering and its event arrives. The runtime is STARTING and the attempt is open, so
        // the event is refused — and nothing would ever re-deliver it.
        when(botGroupService.findById("g-1")).thenReturn(activeGroup(3));
        java.util.concurrent.atomic.AtomicBoolean raised = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger builtWhenEventHandled =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        org.mockito.Mockito.doAnswer(inv -> {
            BotConfiguration configuration = inv.getArgument(1);
            if (raised.compareAndSet(false, true)) {
                when(botGroupService.findById("g-1")).thenReturn(activeGroup(5));
                service.onRegistrationCompleted(new RegistrationCompletedEvent("g-1"));
                // Other start bots build concurrently (parallelism 4), so count only new indices.
                builtWhenEventHandled.set((int) built.stream()
                        .filter(c -> c.getBotIndex() > 3).count());
            }
            built.add(configuration);
            return stubBot("bot" + configuration.getBotIndex());
        }).when(botFactory).createBot(anyString(), any());

        service.startAsync("g-1", com.vingame.bot.domain.botgroup.model.StartOrigin.REST, () -> { });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (service.getActualStatus("g-1") == BotGroupStatus.STARTING && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertThat(builtWhenEventHandled.get())
                .as("the event itself built nothing — it was refused by the start in flight")
                .isZero();
        assertThat(indices())
                .as("the start built 1-3, then caught up 4-5 before releasing the lock")
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5);
        assertThat(runtime().getBuiltUpTo()).isEqualTo(5);
        assertThat(runtime().getBotInstances()).hasSize(5);
        assertThat(runtime().getActualStatus()).isEqualTo(BotGroupStatus.ACTIVE);
    }

    @Test
    @DisplayName("a raise completing during an attach is caught up by the same attach")
    void raiseDuringAnAttachIsCaughtUp() {
        startWith(3);
        built.clear();
        when(botGroupService.findById("g-1")).thenReturn(activeGroup(5));
        java.util.concurrent.atomic.AtomicBoolean raised = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(inv -> {
            BotConfiguration configuration = inv.getArgument(1);
            if (raised.compareAndSet(false, true)) {
                when(botGroupService.findById("g-1")).thenReturn(activeGroup(7));
            }
            built.add(configuration);
            return stubBot("bot" + configuration.getBotIndex());
        }).when(botFactory).createBot(anyString(), any());

        service.attach("g-1");

        assertThat(indices()).containsExactlyInAnyOrder(4, 5, 6, 7);
        assertThat(runtime().getBuiltUpTo()).isEqualTo(7);
    }

    // ------------------------------------------------------------------ QA additions

    @Test
    @DisplayName("QA: a completion event for an ACTIVE group attaches through the lifecycle thread")
    void completionEventAttachesAsynchronously() throws Exception {
        startWith(3);
        built.clear();
        when(botGroupService.findById("g-1")).thenReturn(activeGroup(5));

        service.onRegistrationCompleted(new RegistrationCompletedEvent("g-1"));

        awaitUntil(() -> runtime().getBuiltUpTo() == 5
                && service.getActualStatus("g-1") == BotGroupStatus.ACTIVE);
        assertThat(indices()).containsExactlyInAnyOrder(4, 5);
        assertThat(runtime().getBotInstances()).hasSize(5);
    }

    @Test
    @DisplayName("QA: the budget reservation is released after a successful attach")
    void reservationReleasedOnSuccess() {
        startWith(3);
        reservations.clear();
        releases.set(0);
        when(botGroupService.findById("g-1")).thenReturn(activeGroup(5));

        service.attach("g-1");

        assertThat(reservations).hasSize(1);
        assertThat(releases.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("QA: the budget reservation is released when the attach throws after reserving")
    void reservationReleasedOnFailure() {
        startWith(3);
        built.clear();
        reservations.clear();
        releases.set(0);
        when(botGroupService.findById("g-1")).thenReturn(activeGroup(5));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(groupLifecycleAggregator).expectAdditional(anyString(), any(), org.mockito.ArgumentMatchers.eq(2));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.attach("g-1"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(reservations).hasSize(1);
        assertThat(releases.get()).as("reservation released on the failure path").isEqualTo(1);
        assertThat(built).isEmpty();
        assertThat(runtime().getBuiltUpTo()).as("a failed attach does not consume the range").isEqualTo(3);
        assertThat(runtime().getBotInstances()).hasSize(3);
    }

    @Test
    @DisplayName("QA: a runtime that dies while the attach builds discards the new bots")
    void runtimeDyingDuringAttachDiscardsNewBots() {
        startWith(3);
        built.clear();
        releases.set(0);
        reservations.clear();
        when(botGroupService.findById("g-1")).thenReturn(activeGroup(5));
        List<Bot> attached = new CopyOnWriteArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            BotConfiguration configuration = inv.getArgument(1);
            built.add(configuration);
            runtime().markAsDead();
            Bot bot = stubBot("bot" + configuration.getBotIndex());
            attached.add(bot);
            return bot;
        }).when(botFactory).createBot(anyString(), any());

        service.attach("g-1");

        assertThat(attached).hasSize(2);
        attached.forEach(bot -> {
            verify(bot).cleanup();
            verify(bot, never()).setCoordinator(any());
        });
        assertThat(runtime().getBotInstances()).hasSize(3).doesNotContainAnyElementsOf(attached);
        assertThat(runtime().getBuiltUpTo()).isEqualTo(3);
        assertThat(releases.get()).isEqualTo(reservations.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("QA: a /stop during an attach cancels it, cleans up the new bots and never starts them")
    void stopDuringAttachUnwinds() throws Exception {
        List<Bot> attached = runAttachInterruptedBy(() -> service.stop("g-1"));

        assertThat(runningGroups()).as("the stop tore the runtime down").doesNotContainKey("g-1");
        org.mockito.ArgumentCaptor<BotGroup> saved = org.mockito.ArgumentCaptor.forClass(BotGroup.class);
        verify(botGroupService, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getTargetStatus()).isEqualTo(BotGroupStatus.STOPPED);
        assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.STOPPED);
    }

    @Test
    @DisplayName("QA: a delete during an attach cancels it, cleans up the new bots and writes nothing")
    void deleteDuringAttachUnwinds() throws Exception {
        List<Bot> attached = runAttachInterruptedBy(() -> service.stopAndLogout("g-1"));

        assertThat(runningGroups()).doesNotContainKey("g-1");
        verify(botGroupService, never()).save(any());
    }

    @Test
    @DisplayName("QA: a group deleted between completion and event does not throw into the worker")
    void deletedGroupDoesNotThrowIntoTheWorker() {
        startWith(3);
        built.clear();
        when(botGroupService.findById("g-1"))
                .thenThrow(new com.vingame.bot.common.exception.ResourceNotFoundException("gone"));

        org.assertj.core.api.Assertions.assertThatCode(
                        () -> service.onRegistrationCompleted(new RegistrationCompletedEvent("g-1")))
                .doesNotThrowAnyException();
        assertThat(built).isEmpty();
        assertThat(runtime().getBuiltUpTo()).isEqualTo(3);
    }

    /**
     * Start with 3 bots, raise to 5, submit the attach through the event, park both new bots
     * inside the factory, run {@code interrupt} on another thread until it is queued on the group
     * lock (i.e. it has already cancelled the attempt), then let the build finish. Returns the
     * attached bot mocks after asserting they were cleaned up and never wired into the runtime,
     * and that the reservation was released.
     */
    private List<Bot> runAttachInterruptedBy(Runnable interrupt) throws Exception {
        startWith(3);
        built.clear();
        reservations.clear();
        releases.set(0);
        org.mockito.Mockito.clearInvocations(botGroupService);
        when(botGroupService.findById("g-1")).thenReturn(activeGroup(5));

        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);
        List<Bot> attached = new CopyOnWriteArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            BotConfiguration configuration = inv.getArgument(1);
            built.add(configuration);
            entered.countDown();
            assertThat(proceed.await(20, TimeUnit.SECONDS)).isTrue();
            Bot bot = stubBot("bot" + configuration.getBotIndex());
            attached.add(bot);
            return bot;
        }).when(botFactory).createBot(anyString(), any());

        service.onRegistrationCompleted(new RegistrationCompletedEvent("g-1"));
        assertThat(entered.await(20, TimeUnit.SECONDS)).as("both new bots entered the factory").isTrue();

        Thread interrupter = Thread.ofVirtual().start(interrupt);
        java.util.concurrent.locks.ReentrantLock lock = groupLocks().get("g-1");
        awaitUntil(lock::hasQueuedThreads);
        proceed.countDown();
        interrupter.join(TimeUnit.SECONDS.toMillis(20));
        assertThat(interrupter.isAlive()).isFalse();
        awaitUntil(() -> service.getActualStatus("g-1") != BotGroupStatus.STARTING);

        assertThat(indices()).containsExactlyInAnyOrder(4, 5);
        assertThat(attached).hasSize(2);
        attached.forEach(bot -> {
            verify(bot).cleanup();
            verify(bot, never()).setCoordinator(any());
            verify(bot, never()).setJackpotScaler(any());
        });
        assertThat(reservations).hasSize(1);
        assertThat(releases.get()).isEqualTo(1);
        return attached;
    }

    @SuppressWarnings("unchecked")
    private Map<String, java.util.concurrent.locks.ReentrantLock> groupLocks() {
        return (Map<String, java.util.concurrent.locks.ReentrantLock>)
                ReflectionTestUtils.getField(service, "groupLocks");
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 20 s");
            }
            Thread.sleep(10);
        }
    }

    // ------------------------------------------------------------------ fix round (review + QA)

    @Test
    @DisplayName("fix 1: the start's reservation is released before the catch-up attach reserves")
    void startReservationReleasedBeforeCatchUpReserves() throws Exception {
        when(botGroupService.findById("g-1")).thenReturn(activeGroup(3));
        java.util.concurrent.atomic.AtomicBoolean raised = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(inv -> {
            BotConfiguration configuration = inv.getArgument(1);
            if (raised.compareAndSet(false, true)) {
                when(botGroupService.findById("g-1")).thenReturn(activeGroup(5));
            }
            built.add(configuration);
            return stubBot("bot" + configuration.getBotIndex());
        }).when(botFactory).createBot(anyString(), any());

        service.start("g-1");

        assertThat(runtime().getBuiltUpTo()).isEqualTo(5);
        assertThat(budgetLog).contains("reserve:9", "release:9", "reserve:6");
        assertThat(budgetLog.indexOf("release:9"))
                .as("no second ESSENTIAL reservation on the same tier|group key while the start's is live: %s",
                        budgetLog)
                .isLessThan(budgetLog.indexOf("reserve:6"));
    }

    @Test
    @DisplayName("fix 2: an event refused after the holder's last re-read is run when the holder finishes")
    void eventAfterTheHoldersLastReReadIsNotLost() throws Exception {
        startWith(3);
        built.clear();
        java.util.concurrent.atomic.AtomicReference<BotGroup> current =
                new java.util.concurrent.atomic.AtomicReference<>(activeGroup(5));
        java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        when(botGroupService.findById("g-1")).thenAnswer(inv -> {
            BotGroup answer = current.get();
            // The attach's catch-up re-read after pass 1 (builtUpTo already 5): answer the stale
            // document, and let the registration of 6-7 complete and publish right now — the
            // window between the holder's last re-read and its finish().
            if (runtime().getBuiltUpTo() == 5 && fired.compareAndSet(false, true)) {
                current.set(activeGroup(7));
                service.onRegistrationCompleted(new RegistrationCompletedEvent("g-1"));
            }
            return answer;
        });

        service.onRegistrationCompleted(new RegistrationCompletedEvent("g-1"));

        awaitUntil(() -> runtime().getBuiltUpTo() == 7 && !service.getActualStatus("g-1").equals(BotGroupStatus.STARTING));
        assertThat(fired).isTrue();
        assertThat(indices()).containsExactlyInAnyOrder(4, 5, 6, 7);
    }

    @Test
    @DisplayName("fix 2: hitting the pass cap hands the remainder to a fresh attempt instead of stranding it")
    void passCapRemainderIsPickedUp() throws Exception {
        startWith(3);
        built.clear();
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(4);
        when(botGroupService.findById("g-1")).thenAnswer(inv -> activeGroup(count.get()));
        org.mockito.Mockito.doAnswer(inv -> {
            BotConfiguration configuration = inv.getArgument(1);
            built.add(configuration);
            // Every pass's build completes another raise, up to 10: 4,5,6,7,8 fill the five
            // passes of the first hold and 9,10 are left for the cap's hand-off.
            count.updateAndGet(c -> Math.min(10, c + 1));
            return stubBot("bot" + configuration.getBotIndex());
        }).when(botFactory).createBot(anyString(), any());

        service.onRegistrationCompleted(new RegistrationCompletedEvent("g-1"));

        awaitUntil(() -> runtime().getBuiltUpTo() == 10 && service.getActualStatus("g-1") == BotGroupStatus.ACTIVE);
        assertThat(indices()).containsExactlyInAnyOrder(4, 5, 6, 7, 8, 9, 10);
    }

    @Test
    @DisplayName("fix 3: a group dying during an attach aborts it and is marked DEAD")
    void deathDuringAttachAbortsItAndMarksDead() throws Exception {
        startWith(3);
        built.clear();
        when(botGroupService.findById("g-1")).thenReturn(activeGroup(5));
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);
        List<Bot> attached = new CopyOnWriteArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            BotConfiguration configuration = inv.getArgument(1);
            built.add(configuration);
            entered.countDown();
            assertThat(proceed.await(20, TimeUnit.SECONDS)).isTrue();
            Bot bot = stubBot("bot" + configuration.getBotIndex());
            attached.add(bot);
            return bot;
        }).when(botFactory).createBot(anyString(), any());

        service.onRegistrationCompleted(new RegistrationCompletedEvent("g-1"));
        assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
        BotGroupRuntime runtime = runtime();

        Thread monitor = Thread.ofVirtual().start(
                () -> ReflectionTestUtils.invokeMethod(service, "handleBotGroupDeath", runtime));
        awaitUntil(() -> startAttempts().isCancelled("g-1"));
        proceed.countDown();
        monitor.join(TimeUnit.SECONDS.toMillis(20));

        assertThat(runtime.getActualStatus())
                .as("the attach unwound inside the monitor's second wait and the group was marked")
                .isEqualTo(BotGroupStatus.DEAD);
        assertThat(runtime.getBuiltUpTo()).isEqualTo(3);
        awaitUntil(() -> attached.size() == 2);
        awaitUntil(() -> !startAttempts().isOpen("g-1"));
        attached.forEach(bot -> {
            verify(bot).cleanup();
            verify(bot, never()).setCoordinator(any());
        });
        assertThat(runtime.getBotInstances()).hasSize(3);
    }

    @Test
    @DisplayName("fix 4: an operator /restart during an attach is a 409, not a 200 that does nothing")
    void restDuringAttachIsAConflict() throws Exception {
        java.util.concurrent.CountDownLatch proceed = parkAttach();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.restartAsync("g-1", com.vingame.bot.domain.botgroup.model.StartOrigin.REST, () -> { }))
                    .isInstanceOf(com.vingame.bot.common.exception.ConflictException.class)
                    .hasMessageContaining("attaching");
        } finally {
            proceed.countDown();
        }
    }

    @Test
    @DisplayName("fix 4: a scheduled restart landing during an attach runs after it")
    void scheduledRestartDuringAttachIsDeferred() throws Exception {
        java.util.concurrent.CountDownLatch proceed = parkAttach();
        BotGroupRuntime before = runtime();

        BotGroupStatus answer = service.restartAsync("g-1",
                com.vingame.bot.domain.botgroup.model.StartOrigin.SCHEDULED_RESTART, () -> { });
        assertThat(answer).isEqualTo(BotGroupStatus.STARTING);
        proceed.countDown();

        awaitUntil(() -> runtime() != null && runtime() != before
                && runtime().getActualStatus() == BotGroupStatus.ACTIVE
                && !startAttempts().isOpen("g-1"));
        assertThat(runtime().getBuiltUpTo()).isEqualTo(5);
    }

    @Test
    @DisplayName("fix 5: a gateway refusal stops builtUpTo at the contiguous prefix; the rest is retried")
    void refusalKeepsTheRemainderPending() {
        startWith(3);
        built.clear();
        when(botGroupService.findById("g-1")).thenReturn(activeGroup(6));
        List<Bot> attached = new CopyOnWriteArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            BotConfiguration configuration = inv.getArgument(1);
            built.add(configuration);
            if (configuration.getBotIndex() == 5) {
                throw new com.vingame.bot.common.exception.GatewayCircuitOpenException("env-1", "ray", Duration.ofSeconds(30));
            }
            Bot bot = stubBot("bot" + configuration.getBotIndex());
            attached.add(bot);
            return bot;
        }).when(botFactory).createBot(anyString(), any());

        service.attach("g-1");

        assertThat(runtime().getBuiltUpTo()).as("4 came up, 5 was refused unsent").isEqualTo(4);
        assertThat(runtime().getBotInstances()).hasSize(4);
        Bot bot6 = attached.stream().filter(b -> b.getUserName().equals("bot6")).findFirst().orElseThrow();
        verify(bot6).cleanup();
        verify(bot6, never()).setCoordinator(any());

        // The next trigger retries 5-6 and nothing else.
        built.clear();
        org.mockito.Mockito.doAnswer(inv -> {
            BotConfiguration configuration = inv.getArgument(1);
            built.add(configuration);
            return stubBot("bot" + configuration.getBotIndex());
        }).when(botFactory).createBot(anyString(), any());
        service.attach("g-1");

        assertThat(indices()).containsExactlyInAnyOrder(5, 6);
        assertThat(runtime().getBuiltUpTo()).isEqualTo(6);
        assertThat(runtime().getBotInstances()).hasSize(6);
    }

    /** Start 3, raise to 5, submit the attach through the event and park both new bots. */
    private java.util.concurrent.CountDownLatch parkAttach() throws Exception {
        startWith(3);
        built.clear();
        when(botGroupService.findById("g-1")).thenReturn(activeGroup(5));
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean parking = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(inv -> {
            BotConfiguration configuration = inv.getArgument(1);
            built.add(configuration);
            if (parking.get()) {
                entered.countDown();
                assertThat(proceed.await(20, TimeUnit.SECONDS)).isTrue();
            }
            return stubBot("bot" + configuration.getBotIndex());
        }).when(botFactory).createBot(anyString(), any());
        service.onRegistrationCompleted(new RegistrationCompletedEvent("g-1"));
        assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
        // Later builds (the deferred restart) must not park.
        Thread.ofVirtual().start(() -> {
            try {
                proceed.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            parking.set(false);
        });
        return proceed;
    }

    private StartAttemptRegistry startAttempts() {
        return (StartAttemptRegistry) ReflectionTestUtils.getField(service, "startAttempts");
    }

    // ------------------------------------------------------------------ helpers

    private void startWith(int botCount) {
        when(botGroupService.findById("g-1")).thenReturn(activeGroup(botCount));
        service.start("g-1");
        assertThat(runtime().getActualStatus()).isEqualTo(BotGroupStatus.ACTIVE);
        assertThat(runtime().getBuiltUpTo()).isEqualTo(botCount);
    }

    private List<Integer> indices() {
        return built.stream().map(BotConfiguration::getBotIndex).toList();
    }

    private BotGroupRuntime runtime() {
        return runningGroups().get("g-1");
    }

    @SuppressWarnings("unchecked")
    private Map<String, BotGroupRuntime> runningGroups() {
        return (Map<String, BotGroupRuntime>) ReflectionTestUtils.getField(service, "runningGroups");
    }

    private static BotGroup activeGroup(int botCount) {
        return BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(botCount).namePrefix("bot").password("pass")
                .registeredCount(botCount).namedCount(botCount)
                .targetStatus(BotGroupStatus.ACTIVE)
                .build();
    }

    private static Environment environment() {
        return Environment.builder().id("env-1").name("env").customZone(true)
                .miniZoneName("zone").build();
    }

    private static Game game() {
        return Game.builder().id("game-1").name("BauCua").build();
    }

    private static Bot stubBot(String username) {
        Bot b = mock(Bot.class);
        lenient().when(b.getUserName()).thenReturn(username);
        lenient().when(b.getTotalBetsPlaced()).thenReturn(new AtomicLong(0));
        lenient().when(b.getTotalBetAmount()).thenReturn(new AtomicLong(0));
        return b;
    }

    /** Records each declaration as {@code [tier ordinal, permits]} and runs every call inline. */
    private final class RecordingBudget implements GatewayBudget {

        @Override
        public Reservation reserve(RequestTier tier, int permits, GatewayRequestScope scope) {
            reservations.add(new int[]{tier.ordinal(), permits});
            budgetLog.add("reserve:" + permits);
            return new Reservation() {
                @Override
                public void release() {
                    releases.incrementAndGet();
                    budgetLog.add("release:" + permits);
                }

                @Override
                public int remaining() {
                    return 0;
                }
            };
        }

        @Override
        public <T> T execute(RequestTier tier, GatewayRequestScope scope, Callable<T> call) throws Exception {
            return call.call();
        }

        @Override
        public <T> T execute(RequestTier tier, GatewayRequestScope scope, Callable<T> call,
                             Duration maxWait) throws Exception {
            return call.call();
        }

        @Override
        public void run(RequestTier tier, GatewayRequestScope scope, Runnable call) {
            call.run();
        }

        @Override
        public void runWsUpgrade(RequestTier tier, GatewayRequestScope scope, Runnable upgrade) {
            upgrade.run();
        }

        @Override
        public <T> Optional<T> tryExecute(RequestTier tier, GatewayRequestScope scope,
                                          Callable<T> call, Duration maxWait) throws Exception {
            return Optional.ofNullable(call.call());
        }

        @Override
        public void count(String reason) {
        }

        @Override
        public void countWsUpgrade(String reason) {
        }

        @Override
        public void reportEdgeBlock(com.vingame.bot.infrastructure.gateway.GatewayEndpoint endpoint,
                                    String cfRay) {
        }

        @Override
        public void cancelScope(String botGroupId) {
        }

        @Override
        public Snapshot snapshot() {
            return new Snapshot("env-1", "env", "116", GatewayBudgetMode.ENFORCE,
                    0, 900, 0, 0, 0, false);
        }

        @Override
        public boolean countsWsUpgrades() {
            return true;
        }

        @Override
        public Duration registrationMaxWait() {
            return Duration.ofMinutes(15);
        }

        @Override
        public Duration observeModePacing() {
            return Duration.ZERO;
        }

        @Override
        public Duration maxWait(RequestTier tier) {
            return tier == RequestTier.ESSENTIAL ? null : Duration.ofMinutes(10);
        }
    }
}
