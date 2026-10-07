package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.model.StartOrigin;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.service.GameService;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The windows in which a lifecycle command lands on a start that is still in flight
 * (GATEWAY_REQUEST_BUDGET Phase 2, AD-8/AD-15/AD-16).
 * <p>
 * {@code BotGroupBehaviorServiceAsyncStartTest} covers the common shape: a {@code /stop} while
 * the build is inside {@code createBotsInParallel}. This class covers the shapes around it,
 * each of which has its own ordering and its own way of going wrong:
 * <ul>
 *   <li><b>the accepted-but-not-yet-locked window</b> — the regression {@code 750fc91} fixed.
 *       The stop gets the group lock <em>first</em>, finds no runtime, persists {@code STOPPED}
 *       and returns; the build then wakes up and must not bring the group up anyway;</li>
 *   <li><b>{@code /stop} during a {@code /restart}</b> — the operator's stop must win over a
 *       rebuild that is already tearing the group down and putting it back;</li>
 *   <li><b>{@code restart()}'s own internal stop</b> — which must <em>not</em> cancel the
 *       attempt, or every {@code /restart} silently becomes a {@code /stop};</li>
 *   <li><b>two starts racing</b>, <b>a delete mid-start</b>, and <b>where the per-bot
 *       cancellation check sits</b>, which is what decides whether a cancelled start stops
 *       spending gateway requests or merely stops recording them.</li>
 * </ul>
 * <p>
 * Nothing here opens a socket or makes a gateway request: {@code BotFactory} is a mock, so no
 * bot is ever authenticated. That is a hard rule — a test that fires real traffic can block a
 * whole brand at the Cloudflare edge for an hour, which is the incident this whole plan exists
 * to prevent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Bot group start — cancellation races")
class BotGroupStartCancellationRaceTest {

    @Mock private BotGroupService botGroupService;
    @Mock private EnvironmentService environmentService;
    @Mock private GameService gameService;
    @Mock private BotFactory botFactory;
    @Mock private BotMetrics botMetrics;
    @Mock private SessionAggregationService sessionAggregationService;
    @Mock private GroupLifecycleAggregator groupLifecycleAggregator;
    @Mock private ScopedDebugEscalator scopedDebugEscalator;
    @Mock private GatewayBudgetRegistry gatewayBudgetRegistry;
    /** PLUGIN_HOT_RELOAD_3_4 D-15: answers null, so bots fall back to `builtin` as before. */

    @InjectMocks
    private BotGroupBehaviorService service;

    /**
     * Every {@code targetStatus} that reached {@code BotGroupService.save}, recorded <b>at the
     * moment of the call</b>. An {@code ArgumentCaptor} cannot answer this: the service saves
     * the same {@code BotGroup} instance repeatedly, so a captor hands back one object whose
     * field has since moved on, and a two-save sequence reads as the final value twice.
     */
    private final List<BotGroupStatus> persisted = new CopyOnWriteArrayList<>();

    @BeforeEach
    void initConfigFields() {
        // GATEWAY_REQUEST_BUDGET Phase 3: startLocked declares its demand up front
        // (reserve(ESSENTIAL, botCount x 3)) and the registry is mocked here, so without this an
        // unstubbed mock returns null and every start NPEs. UNLIMITED is the honest fixture
        // budget: it runs every call immediately and its reservation is a no-op, which is the
        // right shape for tests that are about the lifecycle rather than about pacing. The
        // reservation itself is asserted against a recording budget in
        // BotGroupBehaviorServiceReservationTest.
        //
        // lenient(), because most tests in this class never start a group and STRICT_STUBS would
        // fail them for an unnecessary stubbing.
        org.mockito.Mockito.lenient().when(gatewayBudgetRegistry.forEnvironment(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.vingame.bot.infrastructure.gateway.GatewayBudget.UNLIMITED);
        when(botGroupService.save(any(BotGroup.class))).thenAnswer(inv -> {
            BotGroup saved = inv.getArgument(0);
            persisted.add(saved.getTargetStatus());
            return saved;
        });
        ReflectionTestUtils.setField(service, "deadBotGroupThreshold", 0.80);
        ReflectionTestUtils.setField(service, "botCreationParallelism", 1);
        ReflectionTestUtils.setField(service, "watchdogTimeoutSeconds", 180L);
        ReflectionTestUtils.setField(service, "periodicLogoutEnabled", false);
        ReflectionTestUtils.setField(service, "periodicLogoutIntervalMinutes", 60);
        ReflectionTestUtils.setField(service, "reconnectDelaySeconds", 5);
        ReflectionTestUtils.setField(service, "activationZone", "Asia/Ho_Chi_Minh");
    }

    @AfterEach
    void shutdownExecutors() {
        try {
            service.shutdown();
        } catch (Exception ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------------ the 750fc91 window

    /**
     * The regression {@code 750fc91} fixed, reproduced deterministically rather than by timing.
     * <p>
     * The window is "the start has been accepted and its attempt is open, but its build thread
     * has not taken the group lock yet". A {@code /stop} landing there takes the lock first,
     * finds nothing in {@code runningGroups}, persists {@code STOPPED} and returns — and its
     * {@code finally} calls {@code clearRetained}. If that also dropped the <em>open</em>
     * attempt, it would remove the very flag the still-parked build polls: the build would then
     * wake up, see no cancellation, and bring the group up behind a {@code /stop} that had
     * already answered {@code 200} and written {@code STOPPED}.
     * <p>
     * The lock is pre-seeded and held by the test thread, which is what pins the ordering
     * without a sleep: the build parks on it, and {@code stop()} — called on the same thread —
     * re-enters it freely.
     */
    @Test
    @DisplayName("a stop in the accepted-but-not-yet-locked window is not undone by the build")
    void stopBeforeTheBuildTakesTheLockIsNotUndone() throws Exception {
        BotGroup group = group(2);
        stubLifecycleLookups(group);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        ReentrantLock lock = new ReentrantLock();
        groupLocks().put("g-1", lock);
        lock.lock();
        try {
            assertThat(service.startAsync("g-1", StartOrigin.REST, () -> { }))
                    .isEqualTo(BotGroupStatus.STARTING);
            // The build thread is now parked on the lock this thread holds: the attempt is open
            // and no runtime exists. Exactly the window.
            awaitTrue("the build parked on the group lock", () -> lock.getQueueLength() >= 1);
            assertThat(runningGroups()).doesNotContainKey("g-1");

            // Re-entrant on this thread, so the stop runs to completion here while the build
            // still cannot make progress.
            service.stop("g-1");

            assertThat(startAttempts().isCancelled("g-1"))
                    .as("the attempt must still be open AND cancelled after the stop — dropping "
                            + "it here is what uncancelled the build")
                    .isTrue();
        } finally {
            lock.unlock();
        }

        awaitNoStartInFlight();

        verify(botFactory, never()).createBot(anyString(), any(BotConfiguration.class));
        assertThat(runningGroups()).as("the build must not leave a runtime behind")
                .doesNotContainKey("g-1");
        assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.STOPPED);
        assertThat(service.isGroupRunning("g-1")).isFalse();
        assertThat(persistedStatuses())
                .as("the group must never be persisted ACTIVE behind a stop that already answered")
                .containsExactly(BotGroupStatus.STOPPED);
    }

    /**
     * The cascade-delete path in the <b>accepted-but-not-yet-locked</b> window, which is the only
     * ordering in which a DELETE still runs {@code clearRetained} against an <em>open</em>
     * attempt.
     * <p>
     * {@code deleteMidStartCancelsTheBuild} below used to cover this by accident. Since R6 gave
     * {@code stopAndLogout} the per-group lock it cannot: a delete that lands mid-build now parks
     * until the build releases the lock, by which time the build's own {@code finally} has closed
     * the attempt, so {@code clearRetained} only ever sees a closed one there. Here the build is
     * still parked on the lock, so the delete reaches {@code clearRetained} while the attempt is
     * open — and if that dropped the open attempt it would remove the very flag the parked build
     * polls, and the group would come up under a document that is about to be deleted. That is
     * the {@code 750fc91} hazard on the delete path, and this is its regression test.
     * <p>
     * Same pre-seeded-lock idiom as {@code stopBeforeTheBuildTakesTheLockIsNotUndone}: the test
     * thread holds the lock, so the build parks on it and {@code stopAndLogout} — re-entrant on
     * this thread — runs to completion without waiting for anything.
     */
    @Test
    @DisplayName("a delete in the accepted-but-not-yet-locked window is not undone by the build")
    void deleteBeforeTheBuildTakesTheLockIsNotUndone() throws Exception {
        BotGroup group = group(2);
        stubLifecycleLookups(group);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        ReentrantLock lock = new ReentrantLock();
        groupLocks().put("g-1", lock);
        lock.lock();
        try {
            assertThat(service.startAsync("g-1", StartOrigin.REST, () -> { }))
                    .isEqualTo(BotGroupStatus.STARTING);
            awaitTrue("the build parked on the group lock", () -> lock.getQueueLength() >= 1);
            assertThat(runningGroups()).doesNotContainKey("g-1");

            // BotGroupService.delete's teardown. Re-entrant here, so its clearRetained runs while
            // the build is still parked and its attempt is still open.
            service.stopAndLogout("g-1");

            assertThat(startAttempts().isCancelled("g-1"))
                    .as("the attempt must still be open AND cancelled after the delete — "
                            + "clearRetained must not drop an open attempt, or the build wakes up "
                            + "uncancelled and authenticates bots into a deleted group")
                    .isTrue();
        } finally {
            lock.unlock();
        }

        awaitNoStartInFlight();

        verify(botFactory, never()).createBot(anyString(), any(BotConfiguration.class));
        assertThat(runningGroups()).as("the build must not leave a runtime behind")
                .doesNotContainKey("g-1");
        assertThat(persistedStatuses())
                .as("a delete persists nothing, and the build must not re-insert the document "
                        + "Spring Data's upsert-by-_id would happily recreate")
                .isEmpty();
    }

    /**
     * The same window reached through the cascade-delete path, which has its own
     * {@code clearRetained} call and therefore its own copy of the hazard.
     */
    @Test
    @DisplayName("a delete mid-start cancels the build and the build stays cancelled")
    void deleteMidStartCancelsTheBuild() throws Exception {
        BotGroup group = group(3);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(gameService.findById("game-1")).thenReturn(game());
        when(environmentService.findById("env-1")).thenAnswer(inv -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return environment();
        });
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        service.startAsync("g-1", StartOrigin.REST, () -> { });
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        // BotGroupService.delete calls this before deleting the document — on its own thread here
        // because, since R6, stopAndLogout takes the per-group lock like every other lifecycle
        // path (without it, a DELETE tore the runtime down underneath a live build: the build's
        // finally then saw a null runtime, never called stopAllBots, and leaked every bot that had
        // authenticated after the remove). So the teardown parks behind this build, exactly as
        // /stop does, and the cancel — which happens BEFORE the lock, and is the thing under test
        // — is observable while it parks.
        Thread deleter = new Thread(() -> service.stopAndLogout("g-1"), "test-deleter");
        deleter.start();

        awaitTrue("the delete cancelled the start before taking the group lock",
                () -> startAttempts().isCancelled("g-1"));

        release.countDown();
        deleter.join(TimeUnit.SECONDS.toMillis(20));
        assertThat(deleter.isAlive()).as("the delete completed rather than waiting out the start")
                .isFalse();
        awaitNoStartInFlight();

        verify(botFactory, never()).createBot(anyString(), any(BotConfiguration.class));
        assertThat(runningGroups()).doesNotContainKey("g-1");
        assertThat(persistedStatuses())
                .as("stopAndLogout persists nothing — the document is about to be deleted")
                .isEmpty();
    }

    // ------------------------------------------------------------------ restart

    /**
     * <b>{@code restart()}'s internal stop must not cancel the attempt.</b> AD-16 says it
     * should ("restart()'s internal stop does the same"); Dev found that following it turns
     * every {@code /restart} into a silent {@code /stop}, because by the time the internal stop
     * runs the restart's <em>own</em> attempt is the one in flight. This test is the pin for
     * that finding: it is one {@code parkRuntimeless} argument away from being "fixed" back to
     * the plan's wording, and the symptom would be a group that reports {@code 200 STARTING},
     * tears itself down and never comes back.
     */
    @Test
    @DisplayName("restart's own internal stop does not cancel the restart it is part of")
    void restartInternalStopDoesNotCancelItsOwnAttempt() throws Exception {
        BotGroup group = group(2);
        group.setTargetStatus(BotGroupStatus.ACTIVE);
        stubLifecycleLookups(group);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));
        seedRunningRuntime(group, BotGroupStatus.ACTIVE);

        service.restartAsync("g-1", StartOrigin.REST, () -> { });
        awaitNoStartInFlight();

        assertThat(service.getLastStartError("g-1"))
                .as("a restart that cancelled itself ends as the zero-bot IllegalStateException")
                .isNull();
        verify(botFactory, times(2)).createBot(anyString(), any(BotConfiguration.class));
        assertThat(service.getStartBotsUp("g-1")).isEqualTo(2);
        assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.ACTIVE);
        assertThat(persistedStatuses())
                .as("stop then start: the group ends ACTIVE, not parked STOPPED")
                .containsExactly(BotGroupStatus.STOPPED, BotGroupStatus.ACTIVE);
    }

    /**
     * An operator {@code /stop} during a {@code /restart} still wins. The restart's attempt is
     * the one in flight, so the stop cancels it; the rebuild must unwind rather than race the
     * stop to a final ACTIVE.
     */
    @Test
    @DisplayName("a stop during a restart wins — the rebuild unwinds and the group stays stopped")
    void stopDuringARestartWins() throws Exception {
        BotGroup group = group(3);
        group.setTargetStatus(BotGroupStatus.ACTIVE);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(gameService.findById("game-1")).thenReturn(game());
        when(environmentService.findById("env-1")).thenAnswer(inv -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return environment();
        });
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));
        seedRunningRuntime(group, BotGroupStatus.ACTIVE);

        service.restartAsync("g-1", StartOrigin.REST, () -> { });
        // The rebuild half has begun: the internal stop has already run.
        assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();

        Thread stopper = new Thread(() -> service.stop("g-1"), "test-stopper");
        stopper.start();
        awaitTrue("the stop cancelled the restart before taking the lock",
                () -> startAttempts().isCancelled("g-1"));

        release.countDown();
        stopper.join(TimeUnit.SECONDS.toMillis(20));
        assertThat(stopper.isAlive()).as("the stop did not wait out the rebuild").isFalse();
        awaitNoStartInFlight();

        verify(botFactory, never()).createBot(anyString(), any(BotConfiguration.class));
        assertThat(runningGroups()).doesNotContainKey("g-1");
        assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.STOPPED);
        assertThat(persistedStatuses())
                .as("the last word is the operator's stop, never an ACTIVE from the rebuild")
                .doesNotContain(BotGroupStatus.ACTIVE)
                .endsWith(BotGroupStatus.STOPPED);
        // Q2, and the pin the fix for it did not bring with it. A /stop that wins this race is an
        // operator decision that COMPLETED: zero bots is the intended outcome, not a failure.
        // Before the fix, restart()'s zero-bot check had no cancellation arm, so the same outcome
        // was logged as an ERROR and wrote "produced 0/3 bots; check ...
        // bot_creation_failures_total" into lastError — pointing an operator at a metric that had
        // not moved, on a path where startLocked deliberately logs INFO for exactly this reason.
        // One assertion covers the whole arm: without it, checkRestartProducedBots records that
        // sentence and throws.
        assertThat(service.getLastStartError("g-1"))
                .as("a successful stop must not leave a failure behind on GET /{id}/status")
                .isNull();
    }

    // ------------------------------------------------------------------ two starts racing

    @Test
    @DisplayName("two starts submitted at the same instant produce exactly one build")
    void twoConcurrentStartsProduceOneBuild() throws Exception {
        BotGroup group = group(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(gameService.findById("game-1")).thenReturn(game());
        when(environmentService.findById("env-1")).thenAnswer(inv -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return environment();
        });
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        CyclicBarrier gate = new CyclicBarrier(2);
        List<BotGroupStatus> acks = new CopyOnWriteArrayList<>();
        Runnable startOnce = () -> {
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            acks.add(service.startAsync("g-1", StartOrigin.REST, () -> { }));
        };
        Thread a = new Thread(startOnce, "start-a");
        Thread b = new Thread(startOnce, "start-b");
        a.start();
        b.start();
        a.join(TimeUnit.SECONDS.toMillis(20));
        b.join(TimeUnit.SECONDS.toMillis(20));

        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        awaitNoStartInFlight();

        assertThat(acks)
                .as("both callers get an honest answer; putIfAbsent decides which one builds")
                .containsExactly(BotGroupStatus.STARTING, BotGroupStatus.STARTING);
        verify(environmentService, times(1)).findById("env-1");
        verify(botFactory, times(2)).createBot(anyString(), any(BotConfiguration.class));
        assertThat(service.getStartBotsUp("g-1"))
                .as("a second build would have double-counted the group's bots")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------ where the cancel check sits

    /**
     * The per-bot cancellation check has to be <b>under the semaphore permit</b>, not only
     * before it. Every one of the N tasks is submitted at once, so all N clear a pre-acquire
     * check within milliseconds and then park on the semaphore; for a 3,000-bot group at
     * parallelism 10 that is ~2,990 tasks already past the gate before any {@code /stop} can
     * arrive. Only a task that has just taken a permit is about to spend gateway requests.
     * <p>
     * With parallelism 2 and 20 bots, a cancel that lands while the first two hold permits must
     * leave the gateway spend at those two — not at twenty.
     */
    @Test
    @DisplayName("a cancel under the permit bounds the gateway spend by the parallelism")
    void cancelUnderThePermitBoundsGatewaySpend() throws Exception {
        ReflectionTestUtils.setField(service, "botCreationParallelism", 2);
        BotGroup group = group(20);
        stubLifecycleLookups(group);
        CountDownLatch bothPermitsHeld = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class))).thenAnswer(inv -> {
            bothPermitsHeld.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            return stubBot("bot" + System.nanoTime());
        });

        service.startAsync("g-1", StartOrigin.REST, () -> { });
        assertThat(bothPermitsHeld.await(15, TimeUnit.SECONDS))
                .as("both permits are in use and the other 18 tasks are parked on the semaphore")
                .isTrue();

        Thread stopper = new Thread(() -> service.stop("g-1"), "test-stopper");
        stopper.start();
        awaitTrue("the attempt was cancelled", () -> startAttempts().isCancelled("g-1"));

        release.countDown();
        stopper.join(TimeUnit.SECONDS.toMillis(20));
        awaitNoStartInFlight();

        verify(botFactory, atMost(2)).createBot(anyString(), any(BotConfiguration.class));
        verify(botMetrics, never()).incBotCreationFailure(anyString());
        assertThat(runningGroups()).doesNotContainKey("g-1");
    }

    /**
     * The other half of AD-8: every bot built by a start carries a predicate its environment's
     * gateway budget consults, so a request already queued when the {@code /stop} lands is
     * called off instead of sent. The engine side is
     * {@code BotGatewayTierTest}; this pins that the app actually wires the group's
     * cancellation flag into the configuration, which is the only place it can come from.
     */
    @Test
    @DisplayName("every bot's configuration carries the group's cancellation predicate")
    void botConfigurationCarriesTheCancellationPredicate() throws Exception {
        BotGroup group = group(1);
        stubLifecycleLookups(group);
        ArgumentCaptor<BotConfiguration> captor = ArgumentCaptor.forClass(BotConfiguration.class);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot-1"));

        service.startAsync("g-1", StartOrigin.REST, () -> { });
        awaitNoStartInFlight();

        verify(botFactory).createBot(anyString(), captor.capture());
        BooleanSupplier cancelled = captor.getValue().getStartCancelled();
        assertThat(cancelled).as("without this the budget cannot call off a queued request")
                .isNotNull();
        assertThat(cancelled.getAsBoolean()).isFalse();

        // Open a fresh attempt and cancel it: the supplier must read the live registry, not a
        // snapshot taken when the bot was built.
        startAttempts().begin("g-1", StartOrigin.REST);
        startAttempts().cancel("g-1");
        assertThat(cancelled.getAsBoolean()).isTrue();
        startAttempts().finish("g-1", null);
    }

    // ------------------------------------------------------------------ entry-point parity

    /**
     * {@code validateStartable} is shared so <em>every</em> entry point inherits the two 400s
     * rather than discovering the misconfiguration halfway through a build. Recovery is the
     * entry point with no HTTP caller to report to, so the thing that matters here is that the
     * attempt it opened is closed again — a leaked attempt would report the group
     * {@code STARTING} for the rest of the JVM's life and refuse every later start.
     */
    @Test
    @DisplayName("startForRecovery inherits the gameId guard and leaks no attempt")
    void recoveryInheritsValidationAndLeaksNoAttempt() {
        BotGroup group = BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1")
                .botCount(2).namePrefix("bot").password("pass")
                .targetStatus(BotGroupStatus.DEAD)
                .build();
        when(botGroupService.findById("g-1")).thenReturn(group);

        assertThatThrownBy(() -> service.startForRecovery("g-1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("gameId");

        assertThat(startAttempts().isOpen("g-1"))
                .as("an attempt left open wedges the group as STARTING for ever")
                .isFalse();
        assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.STOPPED);
        assertThat(service.getLastStartError("g-1")).contains("gameId");
    }

    /**
     * A scheduled restart goes through {@code restartAsync}, so it is a tracked attempt like
     * any other: visible on {@code /status}, and a no-op rather than a second build if a start
     * is already in flight. It also must not run on the shared scheduler thread for the paced
     * duration of a start.
     */
    @Test
    @DisplayName("a scheduled restart runs through the tracked async entry")
    void scheduledRestartIsTracked() throws Exception {
        BotGroup group = group(2);
        group.setTargetStatus(BotGroupStatus.ACTIVE);
        stubLifecycleLookups(group);
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        service.scheduleRestart("g-1", java.time.LocalDateTime.now().plusNanos(300_000_000L));

        awaitTrue("the scheduled restart opened an attempt",
                () -> startAttempts().isOpen("g-1") || service.getStartBotsUp("g-1") != null);
        awaitNoStartInFlight();

        assertThat(service.getStartBotsUp("g-1"))
                .as("the scheduled restart is visible on /status like any other start")
                .isEqualTo(2);
        assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.ACTIVE);
    }

    // ------------------------------------------------------------------ helpers

    private void stubLifecycleLookups(BotGroup group) {
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(environment());
        when(gameService.findById("game-1")).thenReturn(game());
    }

    private void seedRunningRuntime(BotGroup group, BotGroupStatus status) {
        BotGroupRuntime runtime = new BotGroupRuntime(
                group.getId(), group.getBotCount(), group.getEnvironmentId(), "env",
                group.getName(), null);
        runtime.setActualStatus(status);
        runningGroups().put(group.getId(), runtime);
    }

    /** Every {@code targetStatus} this service persisted, in order. */
    private List<BotGroupStatus> persistedStatuses() {
        return List.copyOf(persisted);
    }

    private static BotGroup group(int botCount) {
        return BotGroup.builder()
                .id("g-1").name("Group").environmentId("env-1").gameId("game-1")
                .botCount(botCount).namePrefix("bot").password("pass")
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

    private void awaitNoStartInFlight() {
        awaitTrue("the start finished", () -> !startAttempts().isOpen("g-1"));
    }

    /** Poll rather than sleep: the build runs on a virtual thread this test does not own. */
    private static void awaitTrue(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for: " + what, e);
            }
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    @SuppressWarnings("unchecked")
    private Map<String, BotGroupRuntime> runningGroups() {
        return (Map<String, BotGroupRuntime>) field("runningGroups");
    }

    @SuppressWarnings("unchecked")
    private Map<String, ReentrantLock> groupLocks() {
        return (Map<String, ReentrantLock>) field("groupLocks");
    }

    private StartAttemptRegistry startAttempts() {
        return (StartAttemptRegistry) field("startAttempts");
    }

    private Object field(String name) {
        try {
            Field f = BotGroupBehaviorService.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(service);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
