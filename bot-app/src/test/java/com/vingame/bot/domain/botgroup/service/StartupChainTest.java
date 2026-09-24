package com.vingame.bot.domain.botgroup.service;

import com.vingame.bot.config.bot.BotConfiguration;
import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.bot.service.BotFactory;
import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.service.GameService;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
import com.vingame.bot.infrastructure.observability.BotMetrics;
import com.vingame.bot.infrastructure.observability.GroupLifecycleAggregator;
import com.vingame.bot.infrastructure.observability.ScopedDebugEscalator;
import com.vingame.bot.infrastructure.observability.SessionAggregationService;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The application-ready startup daisy-chain (GATEWAY_REQUEST_BUDGET AD-14).
 * <p>
 * The property that matters most here is negative and easy to lose: <b>{@code onStartup()} must
 * return immediately</b>. It used to be a {@code @PostConstruct}, so it ran inside context
 * refresh — before Tomcat bound its port — and every second it spent starting groups was a
 * second the app answered nothing and the container healthcheck was failing. At today's speeds
 * that was tens of seconds; once a start is paced to 900 gateway requests per 5 minutes a
 * 3,000-bot group takes 33-50 minutes, and a fleet would keep the container unreachable and
 * restart-looping for hours. So {@link StartupChainTest#onStartupReturnsWhileTheChainIsStillRunning}
 * is the regression test for the whole reason this moved.
 * <p>
 * Nothing here reaches a network: {@code BotFactory} is a mock.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Startup daisy-chain")
class StartupChainTest {

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
        ReflectionTestUtils.setField(service, "deadBotGroupThreshold", 0.80);
        ReflectionTestUtils.setField(service, "botCreationParallelism", 2);
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
    @DisplayName("onStartup() returns while the chain is still building — Tomcat is never held")
    void onStartupReturnsWhileTheChainIsStillRunning() throws Exception {
        CountDownLatch buildEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BotGroup g1 = group("g1");
        when(botGroupService.findByTargetStatus(BotGroupStatus.ACTIVE)).thenReturn(List.of(g1));
        when(botGroupService.findById("g1")).thenReturn(g1);
        when(gameService.findById("game-1")).thenReturn(game());
        when(environmentService.findById("env-1")).thenAnswer(inv -> {
            buildEntered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return environment();
        });
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        service.onStartup();

        // The whole point: we are back on this thread while the group is still being built.
        assertThat(buildEntered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(service.startupChainThread().isAlive()).isTrue();
        assertThat(service.isGroupRunning("g1")).isTrue();

        release.countDown();
        joinChain();
    }

    @Test
    @DisplayName("groups start one at a time, in order, and two builds never overlap")
    void groupsStartOneAtATimeInOrder() throws Exception {
        BotGroup g1 = group("g1");
        BotGroup g2 = group("g2");
        BotGroup g3 = group("g3");
        when(botGroupService.findByTargetStatus(BotGroupStatus.ACTIVE)).thenReturn(List.of(g1, g2, g3));
        when(botGroupService.findById("g1")).thenReturn(g1);
        when(botGroupService.findById("g2")).thenReturn(g2);
        when(botGroupService.findById("g3")).thenReturn(g3);
        when(environmentService.findById("env-1")).thenReturn(environment());
        when(gameService.findById("game-1")).thenReturn(game());

        Set<String> building = ConcurrentHashMap.newKeySet();
        AtomicInteger maxConcurrentGroups = new AtomicInteger();
        when(botFactory.createBot(anyString(), any(BotConfiguration.class))).thenAnswer(inv -> {
            BotConfiguration config = inv.getArgument(1);
            building.add(config.getBotGroupId());
            maxConcurrentGroups.accumulateAndGet(building.size(), Math::max);
            Thread.sleep(5);
            building.remove(config.getBotGroupId());
            return stubBot(config.getCredentials().getUsername());
        });

        service.onStartup();
        joinChain();

        assertThat(maxConcurrentGroups.get())
                .as("the chain is the CEO's 'daisy-chain, never burst': one group's gateway "
                        + "requests at a time, so each group gets a complete start rather than "
                        + "every group getting a partial one")
                .isEqualTo(1);

        // Each group is now read twice — once by the chain's eligibility re-read (R5) and once by
        // startLocked — so this asserts the ORDER of the reads rather than their count.
        var order = inOrder(botGroupService);
        order.verify(botGroupService, atLeastOnce()).findById("g1");
        order.verify(botGroupService, atLeastOnce()).findById("g2");
        order.verify(botGroupService, atLeastOnce()).findById("g3");
    }

    @Test
    @DisplayName("a group stopped while the chain was still running is not started (R5)")
    void aGroupStoppedDuringTheChainIsSkipped() {
        BotGroup first = group("g1");
        BotGroup second = group("g2");
        when(botGroupService.findByTargetStatus(BotGroupStatus.ACTIVE))
                .thenReturn(List.of(first, second));
        when(botGroupService.findById("g1")).thenReturn(first);
        // The operator's /stop lands while the chain is building g1: by the time the chain reaches
        // g2 the document says STOPPED. Pre-Phase-2 this was unreachable — the loop ran before
        // Tomcat bound its port — and the write that used to be lost is the ONLY opt-out
        // DEAD_GROUP_AUTO_RECOVERY has.
        when(botGroupService.findById("g2")).thenReturn(BotGroup.builder()
                .id("g2").name("Group g2").environmentId("env-1").gameId("game-1")
                .botCount(2).namePrefix("botg2").password("pass")
                .targetStatus(BotGroupStatus.STOPPED)
                .build());
        when(environmentService.findById("env-1")).thenReturn(environment());
        when(gameService.findById("game-1")).thenReturn(game());
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        service.onStartup();
        joinChain();

        assertThat(service.isGroupRunning("g1")).isTrue();
        assertThat(service.isGroupRunning("g2"))
                .as("the chain must not overwrite an operator's STOPPED with ACTIVE")
                .isFalse();
        // And nothing was built for it: the skip happens before the start, not inside it.
        verify(botFactory, times(2)).createBot(anyString(), any(BotConfiguration.class));
    }

    @Test
    @DisplayName("a group moved onto the activation schedule mid-chain is left to the reconciler")
    void aGroupScheduledDuringTheChainIsSkipped() {
        BotGroup queued = group("g1");
        when(botGroupService.findByTargetStatus(BotGroupStatus.ACTIVE)).thenReturn(List.of(queued));
        when(botGroupService.findById("g1")).thenReturn(BotGroup.builder()
                .id("g1").name("Group g1").environmentId("env-1").gameId("game-1")
                .botCount(2).namePrefix("botg1").password("pass")
                .targetStatus(BotGroupStatus.ACTIVE)
                .activationMode(ActivationMode.SCHEDULED)
                .build());

        service.onStartup();
        joinChain();

        assertThat(service.isGroupRunning("g1")).isFalse();
        verify(botFactory, never()).createBot(anyString(), any(BotConfiguration.class));
    }

    @Test
    @DisplayName("a shutdown abandons the chain with one line, not one group failure per group")
    void aShutdownAbandonsTheChainQuietly() {
        BotGroup first = group("g1");
        BotGroup second = group("g2");
        BotGroup third = group("g3");
        when(botGroupService.findByTargetStatus(BotGroupStatus.ACTIVE))
                .thenReturn(List.of(first, second, third));
        when(botGroupService.findById("g1")).thenReturn(first);
        when(environmentService.findById("env-1")).thenReturn(environment());
        when(gameService.findById("game-1")).thenReturn(game());
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> {
                    // A restart lands while the first group is building.
                    service.shutdown();
                    return stubBot("bot" + System.nanoTime());
                });

        List<String> info = captureBehaviorServiceLogs(() -> {
            service.onStartup();
            joinChain();
        });

        // Without the cooperative flag, botCreationExecutor.shutdownNow() turns every remaining
        // group into a RejectedExecutionException reported as a GROUP FAILURE: N page-worthy
        // ERRORs with stack traces per restart, on a fleet of a few hundred groups. A restart is
        // not a group failure.
        assertThat(indexOfLineContaining(info, "abandoning the daisy-chain with 2 of 3"))
                .as("one line, naming what was abandoned")
                .isNotNegative();
        verify(botGroupService, never()).findById("g2");
        verify(botGroupService, never()).findById("g3");
    }

    @Test
    @DisplayName("a failing group does not stop the chain")
    void aFailingGroupDoesNotStopTheChain() throws Exception {
        BotGroup good1 = group("g1");
        // No gameId: startLocked's validation rejects it with a BadRequestException.
        BotGroup bad = BotGroup.builder()
                .id("bad").name("Bad").environmentId("env-1")
                .botCount(1).namePrefix("bot").password("pass")
                .targetStatus(BotGroupStatus.ACTIVE)
                .build();
        BotGroup good2 = group("g3");
        when(botGroupService.findByTargetStatus(BotGroupStatus.ACTIVE))
                .thenReturn(List.of(good1, bad, good2));
        when(botGroupService.findById("g1")).thenReturn(good1);
        when(botGroupService.findById("bad")).thenReturn(bad);
        when(botGroupService.findById("g3")).thenReturn(good2);
        when(environmentService.findById("env-1")).thenReturn(environment());
        when(gameService.findById("game-1")).thenReturn(game());
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        service.onStartup();
        joinChain();

        assertThat(service.isGroupRunning("g1")).isTrue();
        assertThat(service.isGroupRunning("bad")).isFalse();
        assertThat(service.isGroupRunning("g3"))
                .as("per-group isolation: one bad group must not cost the fleet its restart")
                .isTrue();
        assertThat(service.getLastStartError("bad")).contains("gameId");
    }

    @Test
    @DisplayName("the 'queued for daisy-chained start' line precedes the first start")
    void theQueuedLinePrecedesTheFirstStart() {
        BotGroup g1 = group("g1");
        when(botGroupService.findByTargetStatus(BotGroupStatus.ACTIVE)).thenReturn(List.of(g1));
        when(botGroupService.findById("g1")).thenReturn(g1);
        when(environmentService.findById("env-1")).thenReturn(environment());
        when(gameService.findById("game-1")).thenReturn(game());
        when(botFactory.createBot(anyString(), any(BotConfiguration.class)))
                .thenAnswer(inv -> stubBot("bot" + System.nanoTime()));

        List<String> info = captureBehaviorServiceLogs(() -> {
            service.onStartup();
            joinChain();
        });

        int queued = indexOfLineContaining(info, "queued for daisy-chained start");
        int firstStart = indexOfLineContaining(info, "Auto-starting bot group");
        int complete = indexOfLineContaining(info, "Bot Manager startup complete");

        assertThat(queued).as("the liveness line a smoke test greps for (plan V0b)").isNotNegative();
        assertThat(queued).isLessThan(firstStart);
        // "startup complete" now means the chain finished, which on a real fleet can be an hour
        // after the app became reachable. It stays last, and it stays the line that reports the
        // running count.
        assertThat(complete).isGreaterThan(firstStart);
        assertThat(info.get(queued)).contains("1 bot groups queued");
    }

    @Test
    @DisplayName("SCHEDULED groups are still skipped — the reconciler owns them")
    void scheduledGroupsAreStillSkipped() throws Exception {
        BotGroup scheduled = BotGroup.builder()
                .id("sched").name("Scheduled").environmentId("env-1").gameId("game-1")
                .botCount(1).namePrefix("bot").password("pass")
                .activationMode(ActivationMode.SCHEDULED)
                .targetStatus(BotGroupStatus.ACTIVE)
                .build();
        when(botGroupService.findByTargetStatus(BotGroupStatus.ACTIVE)).thenReturn(List.of(scheduled));

        service.onStartup();
        joinChain();

        verify(botGroupService, never()).findById(anyString());
        verify(botFactory, never()).createBot(anyString(), any(BotConfiguration.class));
        assertThat(service.isGroupRunning("sched")).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private void joinChain() {
        Thread chain = service.startupChainThread();
        assertThat(chain).as("onStartup must have submitted the daisy-chain").isNotNull();
        try {
            chain.join(TimeUnit.SECONDS.toMillis(20));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the startup chain", e);
        }
        assertThat(chain.isAlive()).as("the startup chain did not finish").isFalse();
    }

    private static int indexOfLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static BotGroup group(String id) {
        return BotGroup.builder()
                .id(id).name("Group " + id).environmentId("env-1").gameId("game-1")
                .botCount(2).namePrefix("bot" + id).password("pass")
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

    /**
     * Run {@code action} with a capturing appender on {@link BotGroupBehaviorService}'s logger and
     * return the INFO messages in emission order. Same idiom as
     * {@code BotGroupBehaviorServiceRestartTest}; duplicated rather than shared so neither test
     * can silently change the other's capture.
     */
    private static List<String> captureBehaviorServiceLogs(Runnable action) {
        CapturingAppender appender = new CapturingAppender("CapturingAppender-chain-" + System.nanoTime());
        appender.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        String loggerName = BotGroupBehaviorService.class.getName();
        LoggerConfig loggerConfig = ctx.getConfiguration().getLoggerConfig(loggerName);
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
        return appender.events().stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender(String name) {
            super(name, null, PatternLayout.createDefaultLayout(), false, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> events() {
            return Collections.unmodifiableList(new ArrayList<>(events));
        }
    }
}
