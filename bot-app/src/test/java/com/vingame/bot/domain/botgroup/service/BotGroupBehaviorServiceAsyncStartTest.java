package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.common.logging.BotMdc;
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
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The asynchronous start (GATEWAY_REQUEST_BUDGET Phase 2, AD-15/AD-16/A1).
 * <p>
 * Every test here gates the build on a latch, because the properties under test are all
 * <em>about the window while a start is in flight</em> — and that window used to be the
 * duration of an HTTP request. Under the request budget it is up to 50 minutes for a
 * 3,000-bot group, which is why it needs a status, progress, a single-flight rule and a way
 * to be called off at all.
 * <p>
 * No network: {@code BotFactory} is a mock, so nothing authenticates and nothing reaches a
 * gateway.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotGroupBehaviorService - asynchronous start")
class BotGroupBehaviorServiceAsyncStartTest {

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
        ReflectionTestUtils.setField(service, "deadBotGroupThreshold", 0.80);
        // One bot at a time, so a cancellation can be observed mid-build deterministically.
        ReflectionTestUtils.setField(service, "botCreationParallelism", 1);
        ReflectionTestUtils.setField(service, "watchdogTimeoutSeconds", 180L);
        ReflectionTestUtils.setField(service, "periodicLogoutEnabled", false);
        ReflectionTestUtils.setField(service, "periodicLogoutIntervalMinutes", 60);
        ReflectionTestUtils.setField(service, "reconnectDelaySeconds", 5);
    }

    @AfterEach
    void shutdownExecutors() {
        try {
            service.shutdown();
        } catch (Exception ignored) {
            // best effort
        }
    }

    @Test
    @DisplayName("the group reads STARTING and counts as running before a runtime exists")
    void startingIsVisibleBeforeTheRuntimeExists() throws Exception {
        BotGroup group = group(5);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(gameService.findById("game-1")).thenReturn(game());
        // Block the build BEFORE the runtime is created, which is the window runningGroups
        // cannot see and the reason isGroupRunning had to learn about attempts at all.
        when(environmentService.findById("env-1")).thenAnswer(inv -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return environment();
        });
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        BotGroupStatus accepted = service.startAsync("g-1", StartOrigin.REST, () -> { });

        assertThat(accepted)
                .as("the ack says STARTING — 200 means accepted, not finished")
                .isEqualTo(BotGroupStatus.STARTING);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(runningGroups()).as("no runtime yet — this is the window under test")
                .doesNotContainKey("g-1");
        assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.STARTING);
        assertThat(service.isGroupRunning("g-1"))
                .as("a start in flight is running, or the activation reconciler starts it again "
                        + "every minute and recovery can select it (AD-16)")
                .isTrue();
        assertThat(service.getStartBotsUp("g-1")).isZero();

        release.countDown();
        awaitNoStartInFlight();

        assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.ACTIVE);
        assertThat(service.getStartBotsUp("g-1"))
                .as("progress is retained after the build so /status can render 5/5")
                .isEqualTo(5);
        assertThat(service.getLastStartError("g-1")).isNull();
    }

    @Test
    @DisplayName("/health and /status agree in the reclaim window, where the answer used to be DEAD (RR2)")
    void healthAgreesWithStatusInTheReclaimWindow() throws Exception {
        // RR2. R3 fixed only getHealth's runtime-LESS branch; this is the half it left, and
        // getActualStatus' own javadoc calls it "the one answer that is actively misleading".
        //
        // A /start on a DEAD group opens the attempt immediately, and the DEAD runtime stays in
        // runningGroups until startLocked's teardown — which is behind the group lock and can be
        // MINUTES under pacing. For that whole window /status said STARTING (correct) and
        // /health — the public-facing UI health feature — read the runtime directly and said DEAD.
        BotGroup group = group(2);
        when(botGroupService.findById("g-1")).thenReturn(group);

        BotGroupRuntime dead = new BotGroupRuntime("g-1", 2, "env-1", "env", "Group", null);
        dead.markAsDead();
        runningGroups().put("g-1", dead);
        assertThat(dead.getActualStatus()).isEqualTo(BotGroupStatus.DEAD);

        // The attempt an accepted /start opens, without running a build: what is under test is
        // what the two endpoints answer while a DEAD runtime and an open attempt coexist.
        assertThat(startAttempts().begin("g-1", StartOrigin.REST)).isTrue();
        try {
            assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.STARTING);
            assertThat(service.getHealth("g-1").getStatus())
                    .as("the public-facing UI health endpoint must not tell an operator their "
                            + "/start did nothing while /status says it was accepted")
                    .isEqualTo(BotGroupStatus.STARTING);
        } finally {
            startAttempts().finish("g-1", null);
        }

        // And with no attempt open it goes back to reporting the runtime, which is the truth then.
        assertThat(service.getHealth("g-1").getStatus()).isEqualTo(BotGroupStatus.DEAD);
        assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.DEAD);
    }

    @Test
    @DisplayName("a redundant /start on a live group does not make the two endpoints disagree either")
    void healthAgreesWithStatusOnARedundantStart() {
        // The milder inverse RR2 also names: /status said STARTING while /health said ACTIVE.
        BotGroup group = group(2);
        when(botGroupService.findById("g-1")).thenReturn(group);

        BotGroupRuntime live = new BotGroupRuntime("g-1", 2, "env-1", "env", "Group", null);
        live.setActualStatus(BotGroupStatus.ACTIVE);
        runningGroups().put("g-1", live);

        assertThat(startAttempts().begin("g-1", StartOrigin.REST)).isTrue();
        try {
            assertThat(service.getHealth("g-1").getStatus())
                    .isEqualTo(service.getActualStatus("g-1"))
                    .isEqualTo(BotGroupStatus.STARTING);
        } finally {
            startAttempts().finish("g-1", null);
        }
    }

    @Test
    @DisplayName("the runtime is STARTING while building and only ACTIVE is ever persisted")
    void startingIsNeverPersisted() throws Exception {
        BotGroup group = group(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(environment());
        when(gameService.findById("game-1")).thenReturn(game());
        when(botFactory.createBot(anyString(), any(BotConfiguration.class))).thenAnswer(inv -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return stubBot("bot" + System.nanoTime());
        });

        service.startAsync("g-1", StartOrigin.REST, () -> { });
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        // The runtime exists and is STARTING — the state that must never reach Mongo, because an
        // older jar cannot deserialise it and findByTargetStatus(ACTIVE) is on the boot path.
        assertThat(runningGroups().get("g-1").getActualStatus()).isEqualTo(BotGroupStatus.STARTING);

        release.countDown();
        awaitNoStartInFlight();

        ArgumentCaptor<BotGroup> saved = ArgumentCaptor.forClass(BotGroup.class);
        verify(botGroupService, times(1)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(BotGroup::getTargetStatus)
                .containsOnly(BotGroupStatus.ACTIVE);
        assertThat(runningGroups().get("g-1").getActualStatus()).isEqualTo(BotGroupStatus.ACTIVE);
    }

    @Test
    @DisplayName("a second start while one is in flight does not submit a second build")
    void secondStartDoesNotSubmitASecondBuild() throws Exception {
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

        service.startAsync("g-1", StartOrigin.REST, () -> { });
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        BotGroupStatus second = service.startAsync("g-1", StartOrigin.REST, () -> { });

        assertThat(second)
                .as("the loser of the putIfAbsent still answers with what the group is doing")
                .isEqualTo(BotGroupStatus.STARTING);

        release.countDown();
        awaitNoStartInFlight();

        // One build: the environment was resolved once, and exactly botCount bots were created.
        verify(environmentService, times(1)).findById("env-1");
        verify(botFactory, times(2)).createBot(anyString(), any(BotConfiguration.class));
    }

    @Test
    @DisplayName("a stop during a build cancels it, leaves no runtime and persists STOPPED")
    void stopDuringABuildCancelsIt() throws Exception {
        BotGroup group = group(5);
        CountDownLatch firstBot = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(environment());
        when(gameService.findById("game-1")).thenReturn(game());
        when(botFactory.createBot(anyString(), any(BotConfiguration.class))).thenAnswer(inv -> {
            firstBot.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return stubBot("bot" + System.nanoTime());
        });

        service.startAsync("g-1", StartOrigin.REST, () -> { });
        assertThat(firstBot.await(10, TimeUnit.SECONDS)).isTrue();

        // stop() on its own thread because it blocks on the per-group lock the build holds. That
        // it blocks there is fine; what must not happen is that it blocks there *before*
        // cancelling, which is the whole reason the cancel is ordered ahead of the lock (AD-8).
        Thread stopper = new Thread(() -> service.stop("g-1"), "test-stopper");
        stopper.start();

        // The cancel lands without waiting for the lock. This assertion is the ordering.
        awaitTrue("the stop cancelled the attempt before taking the group lock",
                () -> startAttempts().isCancelled("g-1"));

        release.countDown();
        stopper.join(TimeUnit.SECONDS.toMillis(20));
        assertThat(stopper.isAlive()).as("the stop completed rather than waiting out the start").isFalse();
        awaitNoStartInFlight();

        assertThat(runningGroups()).as("the half-built runtime was torn down").doesNotContainKey("g-1");
        assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.STOPPED);
        // Only the one bot that had already taken a permit was built; the other four saw the flag
        // after acquiring and were never created.
        verify(botFactory, times(1)).createBot(anyString(), any(BotConfiguration.class));
        ArgumentCaptor<BotGroup> saved = ArgumentCaptor.forClass(BotGroup.class);
        verify(botGroupService).save(saved.capture());
        assertThat(saved.getValue().getTargetStatus()).isEqualTo(BotGroupStatus.STOPPED);
        // A cancelled bot is neither up nor failed: it never asked the gateway for anything.
        verify(botMetrics, times(0)).incBotCreationFailure(anyString());
    }

    @Test
    @DisplayName("a failed start is recorded as the attempt's lastError, not thrown at the caller")
    void asyncFailureIsRecordedAsLastError() {
        BotGroup group = group(2);
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1"))
                .thenThrow(new IllegalStateException("environment exploded at mongo-7.internal:27017"));

        BotGroupStatus accepted = service.startAsync("g-1", StartOrigin.REST, () -> { });

        assertThat(accepted).isEqualTo(BotGroupStatus.STARTING);
        awaitNoStartInFlight();

        // R2: lastError is on GET /{id}/status and both acks, and the API has no auth in front of
        // it yet. A foreign exception's own words never reach it — the message above is exactly
        // the shape RestExceptionHandler refuses to echo (a hostname and a port). The class name
        // is kept because the holder of a lastError has no request URI to correlate a log with.
        assertThat(service.getLastStartError("g-1"))
                .doesNotContain("mongo-7.internal")
                .doesNotContain("environment exploded")
                .contains("Internal server error")
                .contains("IllegalStateException");
        assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.STOPPED);
        assertThat(service.isGroupRunning("g-1")).isFalse();
    }

    @Test
    @DisplayName("a failure this codebase authored keeps its own words — that is the point of the split")
    void ourOwnExceptionsAreForwardedVerbatim() {
        BotGroup group = group(2);
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenThrow(
                new ResourceNotFoundException("Environment env-1 not found"));

        service.startAsync("g-1", StartOrigin.REST, () -> { });
        awaitNoStartInFlight();

        // BotManagerException subclasses carry messages we wrote, and RestExceptionHandler
        // already forwards them verbatim at 400/404/502 — sanitising them here would have made
        // the field useless for the failures it exists to explain.
        assertThat(service.getLastStartError("g-1")).isEqualTo("Environment env-1 not found");
    }

    @Test
    @DisplayName("a zero-bot start reports why, instead of DEAD with a null lastError")
    void zeroBotStartRecordsItsReason() {
        BotGroup group = group(3);
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(environment());
        when(gameService.findById("game-1")).thenReturn(game());
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenThrow(new IllegalStateException("auth refused"));

        service.startAsync("g-1", StartOrigin.REST, () -> { });
        awaitNoStartInFlight();

        // R10: this branch returns normally, so nothing is thrown for finish() to classify. The
        // group is DEAD with a persisted lastFailureReason, and /status has to say the same thing.
        assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.DEAD);
        assertThat(service.getStartBotsUp("g-1")).isZero();
        assertThat(service.getLastStartError("g-1")).isEqualTo(
                "Started 0/3 bots — all bot creations failed");
    }

    @Test
    @DisplayName("the onFailure rollback runs on the build thread, exactly once")
    void onFailureRunsForAnAsyncFailure() {
        BotGroup group = group(2);
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1"))
                .thenThrow(new IllegalStateException("environment exploded"));
        AtomicLong rollbacks = new AtomicLong();

        service.startAsync("g-1", StartOrigin.REST, rollbacks::incrementAndGet);
        awaitNoStartInFlight();

        assertThat(rollbacks.get())
                .as("the controller's catch cannot see this failure — the callback is the only "
                        + "thing that can restore a manual-override mode flip")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the two validation rejections stay synchronous, and open no attempt")
    void validationIsSynchronous() {
        when(botGroupService.findById("g-1"))
                .thenReturn(BotGroup.builder().id("g-1").name("Group").botCount(2).build());

        assertThat(catchBadRequest(() -> service.startAsync("g-1", StartOrigin.REST, () -> { })))
                .as("no environmentId ⇒ 400 on the request thread, not a 200 and a silent failure")
                .contains("environmentId");
        assertThat(service.getActualStatus("g-1")).isEqualTo(BotGroupStatus.STOPPED);
        assertThat(service.isGroupRunning("g-1")).isFalse();
    }

    @Test
    @DisplayName("restartAsync's zero-bot failure lands in lastError instead of reaching a caller")
    void restartZeroBotFailureLandsInLastError() {
        BotGroup group = group(2);
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(environment());
        when(gameService.findById("game-1")).thenReturn(game());
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenThrow(new IllegalStateException("auth refused"));

        service.restartAsync("g-1", StartOrigin.REST, () -> { });
        awaitNoStartInFlight();

        assertThat(service.getLastStartError("g-1"))
                .as("the RESTART_LIFECYCLE_FIX zero-bot signal is kept — it just cannot be an "
                        + "HTTP status any more")
                .contains("produced 0/2 bots");
    }

    @Test
    @DisplayName("botsUp follows the bots that are up, not the slowest index (R8)")
    void progressIsNotPinnedByOneSlowBot() throws Exception {
        // Parallelism 3 with a 5-bot group. Bot 1 is held; bots 2 and 3 complete. While bot 1 is
        // still in flight, botsUp must already read 2 — the index-ordered join loop reported 0,
        // because it cannot get past future[0]. On a path where 33-50 minutes is a normal
        // duration, a progress field that lies in the "stuck" direction is worse than no field.
        ReflectionTestUtils.setField(service, "botCreationParallelism", 3);
        BotGroup group = group(5);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch othersDone = new CountDownLatch(2);
        AtomicLong created = new AtomicLong();
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(environment());
        when(gameService.findById("game-1")).thenReturn(game());
        when(botFactory.createBot(anyString(), any(BotConfiguration.class))).thenAnswer(inv -> {
            if (created.incrementAndGet() == 1) {
                firstEntered.countDown();
                assertThat(releaseFirst.await(10, TimeUnit.SECONDS)).isTrue();
            } else {
                othersDone.countDown();
            }
            return stubBot("bot" + System.nanoTime());
        });

        service.startAsync("g-1", StartOrigin.REST, () -> { });
        assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(othersDone.await(10, TimeUnit.SECONDS)).isTrue();

        awaitTrue("progress reflects the bots that are actually up",
                () -> service.getStartBotsUp("g-1") != null && service.getStartBotsUp("g-1") >= 2);

        releaseFirst.countDown();
        awaitNoStartInFlight();
        assertThat(service.getStartBotsUp("g-1")).isEqualTo(5);
    }

    @Test
    @DisplayName("the async-failure ERROR keeps the group MDC it was given (Q3)")
    void theAsyncFailureErrorCarriesTheGroupMdc() {
        // The MDC set on the build thread is what makes the async lifecycle lines attributable in
        // Loki, and this ERROR is the only place an asynchronous failure is reported at all.
        // createBotsInParallel's finally used to clear() the whole MDC, so the lines after it —
        // this one included — reached track 1 with no botGroupId to filter on.
        BotGroup group = group(2);
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(environment());
        when(gameService.findById("game-1")).thenReturn(game());
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenThrow(new IllegalStateException("auth refused"));

        List<LogEvent> events = captureWithMdc(() -> {
            service.restartAsync("g-1", StartOrigin.REST, () -> { });
            awaitNoStartInFlight();
        });

        LogEvent asyncFailure = events.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .filter(e -> e.getMessage().getFormattedMessage().contains("Asynchronous restart"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the async-failure ERROR was not emitted"));

        assertThat((String) asyncFailure.getContextData().getValue(BotMdc.BOT_GROUP_ID))
                .isEqualTo("g-1");
        assertThat((String) asyncFailure.getContextData().getValue(BotMdc.ENVIRONMENT_ID))
                .isEqualTo("env-1");
    }

    @Test
    @DisplayName("a start refused by an open circuit names the edge block in lastError, with one group-level line")
    void aStartRefusedByAnOpenCircuitSaysSo() {
        // A16.2 / A32.3 S1. Every login is refused inside the JVM; /status used to read "all bot
        // creations failed", which is the auth-outage shape this feature exists to stop being
        // misdiagnosed. And the refusal used to be one ERROR with a stack trace per bot.
        BotGroup group = group(3);
        when(botGroupService.findById("g-1")).thenReturn(group);
        when(environmentService.findById("env-1")).thenReturn(environment());
        when(gameService.findById("game-1")).thenReturn(game());
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenThrow(new com.vingame.bot.common.exception.GatewayCircuitOpenException(
                        "env-1", "a3c7e4004acc850e-HKG", java.time.Duration.ofMinutes(60)));

        List<LogEvent> events = captureWithMdc(() -> {
            service.startAsync("g-1", StartOrigin.REST, () -> { });
            awaitNoStartInFlight();
        });

        assertThat(service.getActualStatus("g-1"))
                .as("DEAD, not left un-started: ActivationScheduler must not re-decide START every minute")
                .isEqualTo(BotGroupStatus.DEAD);
        assertThat(service.getLastStartError("g-1"))
                .startsWith("Started 0/3 bots — Gateway edge block")
                .contains("a3c7e4004acc850e-HKG")
                .doesNotContain("all bot creations failed");
        // The zero-bot branch persists DEAD, so the group is a recovery candidate that the reconciler
        // will skip on this very circuit: its skip series must already exist at 0 (re-review).
        org.mockito.Mockito.verify(botMetrics).initGroupRecoverySkipSeries("circuit_open");
        assertThat(events.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .filter(e -> e.getMessage().getFormattedMessage().startsWith("Failed to create bot")))
                .as("no per-bot ERROR for a brand-level fact")
                .isEmpty();
        assertThat(events.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(e -> e.getMessage().getFormattedMessage())
                .filter(m -> m.contains("3/3 bots refused by an open gateway circuit")))
                .hasSize(1);
    }

    /** Capture {@link BotGroupBehaviorService}'s events, MDC included, while {@code action} runs. */
    private static List<LogEvent> captureWithMdc(Runnable action) {
        List<LogEvent> events = new CopyOnWriteArrayList<>();
        AbstractAppender appender = new AbstractAppender(
                "AsyncMdcAppender-" + System.nanoTime(), null,
                PatternLayout.createDefaultLayout(), false, null) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig loggerConfig =
                ctx.getConfiguration().getLoggerConfig(BotGroupBehaviorService.class.getName());
        Level prev = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.ALL);
        ctx.updateLoggers();
        try {
            action.run();
        } finally {
            loggerConfig.removeAppender(appender.getName());
            loggerConfig.setLevel(prev);
            ctx.updateLoggers();
            appender.stop();
        }
        return events;
    }

    // ------------------------------------------------------------------ helpers

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

    private static String catchBadRequest(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected a BadRequestException");
        } catch (com.vingame.bot.common.exception.BadRequestException e) {
            return e.getMessage();
        }
    }

    private void awaitNoStartInFlight() {
        awaitTrue("the start finished", () -> !startAttempts().isOpen("g-1"));
    }

    /**
     * Poll rather than sleep: the build runs on a virtual thread this test does not own, and a
     * fixed sleep is either flaky on a loaded laptop or slow on every run.
     */
    private static void awaitTrue(String what, java.util.function.BooleanSupplier condition) {
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
        try {
            Field f = BotGroupBehaviorService.class.getDeclaredField("runningGroups");
            f.setAccessible(true);
            return (Map<String, BotGroupRuntime>) f.get(service);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private StartAttemptRegistry startAttempts() {
        try {
            Field f = BotGroupBehaviorService.class.getDeclaredField("startAttempts");
            f.setAccessible(true);
            return (StartAttemptRegistry) f.get(service);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
