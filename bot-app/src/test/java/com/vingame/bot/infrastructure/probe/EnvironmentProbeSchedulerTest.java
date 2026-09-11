package com.vingame.bot.infrastructure.probe;

import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvKey;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.service.EnvironmentService;
import com.vingame.bot.infrastructure.probe.EnvironmentWsProbe.Outcome;
import com.vingame.bot.infrastructure.probe.EnvironmentWsProbe.ProbeResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wiring cover for the observe-only probe scheduler (DEAD_GROUP_AUTO_RECOVERY
 * Phase 1). The pure predicate is covered exhaustively by
 * {@code RecoveryEligibilityTest} and the classification by
 * {@code EnvironmentWsProbeClassificationTest}; here we assert the properties that
 * only exist once the pieces are joined:
 * <ul>
 *   <li><b>zero probe traffic when no group is dead</b> — the normal state of the
 *       fleet, and the reason this scheduler can run unconditionally;</li>
 *   <li>candidate selection off the persisted status <em>unioned</em> with the
 *       in-memory DEAD runtimes (AD-4);</li>
 *   <li>de-duplication by {@code webSocketMiniUrl};</li>
 *   <li>streak arithmetic (AD-12) and its reset;</li>
 *   <li>the live-sibling short-circuit (AD-10);</li>
 *   <li>INFO on transitions only (AD-14) — the property that keeps this feature
 *       tier-1 admissible.</li>
 * </ul>
 * Nothing here asserts a start/stop, because Phase 1 must never perform one.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnvironmentProbeScheduler.probeAll (observe-only)")
class EnvironmentProbeSchedulerTest {

    private static final String URL = "wss://tipclubgw-sock.stgame.win/ws";
    private static final ProbeResult OPEN = new ProbeResult(Outcome.OPEN, 12, "handshake completed");
    private static final ProbeResult BAD_GATEWAY = new ProbeResult(Outcome.HTTP_5XX, 30, "HTTP 502");

    @Mock
    private BotGroupRepository repository;

    @Mock
    private BotGroupBehaviorService behaviorService;

    @Mock
    private EnvironmentService environmentService;

    @Mock
    private EnvironmentWsProbe probe;

    private SimpleMeterRegistry registry;
    private EnvironmentProbeScheduler scheduler;

    private CapturingAppender appender;
    private LoggerContext ctx;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("env-ws-probe-capture", null, PatternLayout.createDefaultLayout(), true, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // Do NOT call @PostConstruct start() — that would spin a real prober thread.
        scheduler = new EnvironmentProbeScheduler(repository, behaviorService, environmentService,
                probe, registry, "Asia/Ho_Chi_Minh", 60L, 2);

        appender = new CapturingAppender();
        appender.start();
        ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = ctx.getConfiguration()
                .getLoggerConfig(EnvironmentProbeScheduler.class.getName());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.setLevel(Level.INFO);
        loggerConfig.addAppender(appender, Level.INFO, null);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        loggerConfig.removeAppender("env-ws-probe-capture");
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    private List<String> infoLines() {
        return appender.events.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    private static BotGroup deadGroup(String id, String envId) {
        return BotGroup.builder()
                .id(id)
                .name("group-" + id)
                .environmentId(envId)
                .botCount(20)
                .targetStatus(BotGroupStatus.DEAD)
                .build();
    }

    private static Environment environment(String id, String url) {
        return Environment.builder()
                .id(id)
                .name("TIP staging " + id)
                .productCode(ProductCode.P_116)
                .webSocketMiniUrl(url)
                .headers(Map.of())
                .build();
    }

    private double counter(String envId, String outcome) {
        var c = registry.find(EnvironmentProbeScheduler.ENV_WS_PROBE_TOTAL)
                .tags("environmentId", envId, "outcome", outcome)
                .counter();
        return c == null ? 0d : c.count();
    }

    private Double gauge(String envId) {
        var g = registry.find(EnvironmentProbeScheduler.ENV_WS_PROBE_HEALTHY)
                .tags("environmentId", envId)
                .gauge();
        return g == null ? null : g.value();
    }

    @Test
    @DisplayName("no dead group → no probe, no series at all")
    void noCandidatesMeansNoTraffic() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD)).thenReturn(List.of());
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of());

        scheduler.probeAll();

        verify(probe, never()).probe(any(), any());
        assertThat(registry.find(EnvironmentProbeScheduler.ENV_WS_PROBE_TOTAL).counters()).isEmpty();
        assertThat(registry.find(EnvironmentProbeScheduler.ENV_WS_PROBE_HEALTHY).gauges()).isEmpty();
        assertThat(infoLines()).isEmpty();
    }

    @Test
    @DisplayName("a persisted-DEAD group → one probe, counted, and healthy only after the streak")
    void deadGroupIsProbedAndStreakGatesHealth() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD))
                .thenReturn(List.of(deadGroup("g1", "env-1")));
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of());
        when(environmentService.findById("env-1")).thenReturn(environment("env-1", URL));
        when(probe.probe(eq(URL), any())).thenReturn(OPEN);

        scheduler.probeAll();

        verify(probe, times(1)).probe(eq(URL), any());
        assertThat(counter("env-1", "open")).isEqualTo(1d);
        assertThat(gauge("env-1")).isZero();
        assertThat(scheduler.isHealthy("env-1")).isFalse();

        scheduler.probeAll();

        assertThat(counter("env-1", "open")).isEqualTo(2d);
        assertThat(gauge("env-1")).isEqualTo(1d);
        assertThat(scheduler.isHealthy("env-1")).isTrue();
    }

    @Test
    @DisplayName("two environments sharing a webSocketMiniUrl are probed once, counted twice")
    void urlIsDeduplicated() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD))
                .thenReturn(List.of(deadGroup("g1", "env-1"), deadGroup("g2", "env-2")));
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of());
        when(environmentService.findById("env-1")).thenReturn(environment("env-1", URL));
        when(environmentService.findById("env-2")).thenReturn(environment("env-2", URL));
        when(probe.probe(eq(URL), any())).thenReturn(OPEN);

        scheduler.probeAll();

        verify(probe, times(1)).probe(eq(URL), any());
        assertThat(counter("env-1", "open")).isEqualTo(1d);
        assertThat(counter("env-2", "open")).isEqualTo(1d);
    }

    @Test
    @DisplayName("a DEAD runtime whose DB row still says ACTIVE is unioned in (AD-4)")
    void inMemoryDeadRuntimeIsUnionedIn() {
        BotGroup memoryDead = deadGroup("g9", "env-1").toBuilder()
                .targetStatus(BotGroupStatus.ACTIVE)
                .build();
        when(repository.findByTargetStatus(BotGroupStatus.DEAD)).thenReturn(List.of());
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of("g9"));
        when(repository.findById("g9")).thenReturn(Optional.of(memoryDead));
        when(environmentService.findById("env-1")).thenReturn(environment("env-1", URL));
        when(probe.probe(eq(URL), any())).thenReturn(OPEN);

        scheduler.probeAll();

        verify(probe, times(1)).probe(eq(URL), any());
    }

    @Test
    @DisplayName("a DEAD row whose runtime is ACTIVE is not probed (the health-monitor race)")
    void activeRuntimeIsNotProbed() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD))
                .thenReturn(List.of(deadGroup("g1", "env-1")));
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of());
        when(behaviorService.isGroupRunning("g1")).thenReturn(true);

        scheduler.probeAll();

        verify(probe, never()).probe(any(), any());
    }

    @Test
    @DisplayName("a MANUAL_OFF group is not probed")
    void manualOffIsNotProbed() {
        BotGroup parked = deadGroup("g1", "env-1").toBuilder()
                .activationMode(ActivationMode.MANUAL_OFF)
                .build();
        when(repository.findByTargetStatus(BotGroupStatus.DEAD)).thenReturn(List.of(parked));
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of());

        scheduler.probeAll();

        verify(probe, never()).probe(any(), any());
    }

    @Test
    @DisplayName("a live sibling with an open socket short-circuits the probe (AD-10)")
    void liveSiblingShortCircuits() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD))
                .thenReturn(List.of(deadGroup("g1", "env-1")));
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of());
        when(environmentService.findById("env-1")).thenReturn(environment("env-1", URL));
        when(behaviorService.countOpenWsByEnvForActiveRuntimes())
                .thenReturn(Map.of(new EnvKey("env-1", "116"), 3));

        scheduler.probeAll();
        scheduler.probeAll();

        verify(probe, never()).probe(any(), any());
        assertThat(counter("env-1", EnvironmentProbeScheduler.OUTCOME_LIVE_SIBLING)).isEqualTo(2d);
        assertThat(scheduler.isHealthy("env-1")).isTrue();
    }

    /**
     * AD-10's evidence must come from somebody else. {@code handleBotGroupDeath}
     * only marks the runtime DEAD — it does not stop the bots — and at
     * {@code dead.threshold=0.80} a group is DEAD while up to 20% of them are still
     * connected. Counting those as a live sibling would let the most common death
     * shape declare its own environment healthy, satisfy the probe gate with the
     * thing being recovered, and reach {@code healthy-streak} in two ticks having
     * never touched the network.
     * <p>
     * The scheduler asks {@code countOpenWsByEnvForActiveRuntimes}, so a DEAD
     * runtime contributes nothing and a real probe happens.
     */
    @Test
    @DisplayName("a dying group's own surviving bots are not a live sibling — the probe still runs (AD-10)")
    void aDeadRuntimesOwnSocketsDoNotShortCircuitTheProbe() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD))
                .thenReturn(List.of(deadGroup("g1", "env-1")));
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of("g1"));
        when(environmentService.findById("env-1")).thenReturn(environment("env-1", URL));
        // The DEAD group's own minority is invisible to the ACTIVE-only accessor.
        when(behaviorService.countOpenWsByEnvForActiveRuntimes()).thenReturn(Map.of());
        when(probe.probe(eq(URL), any())).thenReturn(BAD_GATEWAY);

        scheduler.probeAll();

        verify(probe, times(1)).probe(eq(URL), any());
        assertThat(counter("env-1", EnvironmentProbeScheduler.OUTCOME_LIVE_SIBLING)).isZero();
        assertThat(scheduler.isHealthy("env-1")).isFalse();
    }

    @Test
    @DisplayName("one unhealthy probe resets the streak")
    void unhealthyResetsTheStreak() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD))
                .thenReturn(List.of(deadGroup("g1", "env-1")));
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of());
        when(environmentService.findById("env-1")).thenReturn(environment("env-1", URL));
        when(probe.probe(eq(URL), any())).thenReturn(OPEN, OPEN, BAD_GATEWAY);

        scheduler.probeAll();
        scheduler.probeAll();
        assertThat(scheduler.isHealthy("env-1")).isTrue();

        scheduler.probeAll();

        assertThat(scheduler.isHealthy("env-1")).isFalse();
        assertThat(gauge("env-1")).isZero();
        assertThat(counter("env-1", "http_5xx")).isEqualTo(1d);
    }

    @Test
    @DisplayName("INFO is emitted on transitions only — three healthy ticks are one line")
    void infoOnlyOnTransition() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD))
                .thenReturn(List.of(deadGroup("g1", "env-1")));
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of());
        when(environmentService.findById("env-1")).thenReturn(environment("env-1", URL));
        when(probe.probe(eq(URL), any())).thenReturn(OPEN, OPEN, OPEN, BAD_GATEWAY, BAD_GATEWAY);

        for (int i = 0; i < 5; i++) {
            scheduler.probeAll();
        }

        assertThat(infoLines()).hasSize(2);
        assertThat(infoLines().get(0))
                .contains("env env-1", "ws probe healthy", "open", "1 dead group(s) eligible");
        assertThat(infoLines().get(1)).contains("ws probe unhealthy", "http_5xx");
    }

    @Test
    @DisplayName("when the candidate goes away the streak is forgotten and the row disappears")
    void stateIsEvictedWhenNoLongerACandidate() {
        when(repository.findByTargetStatus(BotGroupStatus.DEAD))
                .thenReturn(List.of(deadGroup("g1", "env-1")), List.of(deadGroup("g1", "env-1")),
                        List.of());
        when(behaviorService.listDeadRuntimeGroupIds()).thenReturn(List.of());
        when(environmentService.findById("env-1")).thenReturn(environment("env-1", URL));
        when(probe.probe(eq(URL), any())).thenReturn(OPEN);

        scheduler.probeAll();
        scheduler.probeAll();
        assertThat(scheduler.isHealthy("env-1")).isTrue();

        scheduler.probeAll();

        assertThat(scheduler.isHealthy("env-1")).isFalse();
        assertThat(registry.find(EnvironmentProbeScheduler.ENV_WS_PROBE_HEALTHY).gauges()).isEmpty();
    }

    /**
     * {@code isHealthy(null)} <b>throws</b>, and the recovery reconciler's null guard
     * is why that is survivable.
     *
     * <p>{@code envUrls} is a {@code ConcurrentHashMap}, which forbids a null key, so
     * this is not a defensive-programming opinion — it is the contract of the
     * collaborator {@code DeadGroupRecoveryScheduler} calls once per candidate per
     * tick. A group with no {@code environmentId} is reachable ({@code @NotBlank} on
     * {@code BotGroupDTO.environmentId} is {@code OnCreate}-only), and before the
     * guard one such row aborted every recovery tick for the whole fleet.
     *
     * <p>This test exists to keep
     * {@code DeadGroupRecoverySchedulerTest.aCandidateWithNoEnvironmentDoesNotAbortTheTick}
     * honest: that test stubs a mock to throw here, and a stub that stopped matching
     * reality would make it prove nothing. If this class is ever made null-tolerant,
     * this test fails and points at the one that has to be revisited.
     */
    @Test
    @DisplayName("isHealthy(null) throws — the fact the recovery scheduler's null guard exists for")
    void isHealthyThrowsOnANullEnvironmentId() {
        assertThatThrownBy(() -> scheduler.isHealthy(null))
                .isInstanceOf(NullPointerException.class);
    }
}
