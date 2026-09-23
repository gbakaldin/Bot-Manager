package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetRegistry;
import com.vingame.bot.infrastructure.gateway.GatewayBudgetSettings;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvInfo;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.EnvKey;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService.GroupHealth;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LOG_VOLUME_TIERING tier 2 — the fleet rollup must be <b>quiet about healthy groups</b>.
 * <p>
 * That is the single property the whole tier model rests on: INFO volume has to scale with
 * sickness, not with fleet size. A rollup that printed one line per group would be correct,
 * readable, and would reintroduce exactly the bot-count-proportional INFO class that Phase 1
 * exists to remove — at 300 groups, silently. So the assertions below are mostly about lines
 * that must *not* appear.
 * <p>
 * Verification P1-6 and P1-7 check the same two properties on the box; this checks them in
 * the build, where a regression is cheap to find.
 */
@DisplayName("FleetRollupLogger — one line per env, and a group line only when something is wrong")
class FleetRollupLoggerTest {

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("fleet-rollup-capture", null, PatternLayout.createDefaultLayout(), true, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    private BotGroupBehaviorService behaviorService;
    private SessionAggregationService sessionAggregationService;
    private FleetRollupLogger rollup;

    private GatewayBudgetRegistry gatewayBudgetRegistry;
    private CapturingAppender appender;
    private LoggerContext ctx;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        behaviorService = mock(BotGroupBehaviorService.class);
        sessionAggregationService = mock(SessionAggregationService.class);
        // A real budget registry, not a mock: the gateway fragment of the env line is read
        // through snapshotOrEmpty, and a mock would answer null and assert nothing.
        gatewayBudgetRegistry = new GatewayBudgetRegistry(
                GatewayBudgetSettings.defaults(), new SimpleMeterRegistry());
        rollup = new FleetRollupLogger(behaviorService, sessionAggregationService, gatewayBudgetRegistry);

        appender = new CapturingAppender();
        appender.start();
        ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig = ctx.getConfiguration().getLoggerConfig(FleetRollupLogger.class.getName());
        originalLevel = loggerConfig.getLevel();
        loggerConfig.setLevel(Level.INFO);
        loggerConfig.addAppender(appender, Level.INFO, null);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        loggerConfig.removeAppender("fleet-rollup-capture");
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    private List<String> lines() {
        return appender.events.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(e -> e.getMessage().getFormattedMessage())
                .toList();
    }

    private static GroupHealth healthy(String id, String name, String env) {
        return new GroupHealth(id, name, env, "116", 50, 0, 0, 50, false);
    }

    private void stubEnv(String envId, String envName, int managed, int connected, int deadGroups) {
        when(behaviorService.listRunningEnvironmentInfo())
                .thenReturn(List.of(new EnvInfo(envId, envName, "116")));
        EnvKey key = new EnvKey(envId, "116");
        when(behaviorService.countManagedBotsByEnv()).thenReturn(Map.of(key, managed));
        when(behaviorService.countOpenWsByEnv()).thenReturn(Map.of(key, connected));
        when(behaviorService.countDeadGroupsByEnv()).thenReturn(Map.of(key, deadGroups));
    }

    @Test
    @DisplayName("three healthy groups on one env produce ONE line, not four")
    void healthyFleetIsOneLinePerEnvironment() {
        when(behaviorService.listGroupHealth()).thenReturn(List.of(
                healthy("g1", "alpha", "env-1"),
                healthy("g2", "beta", "env-1"),
                healthy("g3", "gamma", "env-1")));
        stubEnv("env-1", "prod-116", 150, 150, 0);
        when(sessionAggregationService.drainRollup()).thenReturn(List.of());

        rollup.rollupOnce();

        // This is P1-7 in the build: no `group … playing=` line for a healthy group.
        assertThat(lines()).hasSize(1);
        assertThat(lines().get(0))
                .startsWith("env env-1 (prod-116, product 116)")
                .contains("groups=3")
                .contains("bots=150")
                .contains("connected=150")
                .contains("dead=0")
                .contains("deadGroups=0")
                // GATEWAY_REQUEST_BUDGET AD-20. No budget exists for env-1 in this fixture, and
                // the line still carries the fragment with the configured hard cap: reading the
                // rollup must not CREATE a budget (and with it a fresh set of gateway_budget_*
                // series), and an operator who greps for `gateway=` must not have to work out
                // whether a missing fragment means "idle" or "feature not deployed".
                .contains("gateway=0/900 queued=0/0/0 circuit=closed");
    }

    @Test
    @DisplayName("the env line carries the environment's live gateway request window")
    void theEnvironmentLineCarriesTheGatewayWindow() {
        when(behaviorService.listGroupHealth()).thenReturn(List.of(healthy("g1", "alpha", "env-1")));
        stubEnv("env-1", "prod-116", 50, 50, 0);
        when(sessionAggregationService.drainRollup()).thenReturn(List.of());

        // Spend four requests on this environment the way a starting group would.
        var budget = gatewayBudgetRegistry.forEnvironment("env-1", "prod-116", "116");
        for (int i = 0; i < 4; i++) {
            budget.count("fixture");
        }

        rollup.rollupOnce();

        // This is the one number the whole feature exists to bound, on the INFO line that is
        // emitted anyway — which is what makes it visible in Loki without adding a line class
        // whose rate scales with bot count or round rate.
        assertThat(lines()).hasSize(1);
        assertThat(lines().get(0)).contains("gateway=4/900 queued=0/0/0 circuit=closed");
    }

    @Test
    @DisplayName("an unclean group adds a detail line; its clean siblings still add none")
    void uncleanGroupGetsADetailLine() {
        when(behaviorService.listGroupHealth()).thenReturn(List.of(
                healthy("g1", "alpha", "env-1"),
                new GroupHealth("g2", "beta", "env-1", "116", 48, 1, 1, 50, false),
                healthy("g3", "gamma", "env-1")));
        stubEnv("env-1", "prod-116", 150, 148, 0);
        when(sessionAggregationService.drainRollup()).thenReturn(List.of());

        rollup.rollupOnce();

        assertThat(lines()).hasSize(2);
        assertThat(lines()).anySatisfy(line -> assertThat(line)
                .startsWith("group g2 (beta)")
                .contains("playing=48")
                .contains("reconnecting=1")
                .contains("dead=1/50"));
        // The env line's dead count is summed from the same snapshot the detail line was
        // rendered from, so the two can never disagree.
        assertThat(lines()).anySatisfy(line -> assertThat(line)
                .startsWith("env env-1")
                .contains("dead=1"));
        assertThat(lines()).noneSatisfy(line -> assertThat(line).contains("alpha"));
        assertThat(lines()).noneSatisfy(line -> assertThat(line).contains("gamma"));
    }

    @Test
    @DisplayName("a DEAD group gets a line even when none of its surviving bots look bad")
    void deadGroupIsNeverClean() {
        when(behaviorService.listGroupHealth()).thenReturn(List.of(
                new GroupHealth("g1", "alpha", "env-1", "116", 50, 0, 0, 50, true)));
        stubEnv("env-1", "prod-116", 50, 50, 1);
        when(sessionAggregationService.drainRollup()).thenReturn(List.of());

        rollup.rollupOnce();

        assertThat(lines()).hasSize(2);
        assertThat(lines()).anySatisfy(line -> assertThat(line)
                .startsWith("group g1 (alpha)").contains("groupDead=true"));
    }

    @Test
    @DisplayName("AD-8's rounds and stake ride the env line, and the group line when there is one")
    void drainedActivityIsCarriedOnBothLineKinds() {
        when(behaviorService.listGroupHealth()).thenReturn(List.of(
                healthy("g1", "alpha", "env-1"),
                new GroupHealth("g2", "beta", "env-1", "116", 48, 2, 0, 50, false)));
        stubEnv("env-1", "prod-116", 100, 98, 0);
        when(sessionAggregationService.drainRollup()).thenReturn(List.of(
                new SessionAggregationService.GroupRollup("g1", 100, 1_000L),
                new SessionAggregationService.GroupRollup("g2", 37, 500L)));

        rollup.rollupOnce();

        // The env total covers BOTH groups, including the clean one that prints no line
        // of its own — that is how a clean group's activity survives AD-8's demotion.
        assertThat(lines()).anySatisfy(line -> assertThat(line)
                .startsWith("env env-1").contains("rounds=137").contains("staked=1500"));
        assertThat(lines()).anySatisfy(line -> assertThat(line)
                .startsWith("group g2").contains("rounds=37").contains("staked=500"));
    }

    @Test
    @DisplayName("an idle instance emits nothing at all, but still drains the counters")
    void idleInstanceIsSilent() {
        when(behaviorService.listGroupHealth()).thenReturn(List.of());
        when(sessionAggregationService.drainRollup()).thenReturn(List.of());

        rollup.rollupOnce();

        // A "nothing is running" line every 5 minutes forever is exactly the sort of
        // constant background cost this feature exists to remove.
        assertThat(lines()).isEmpty();
        org.mockito.Mockito.verify(sessionAggregationService).drainRollup();
    }

    @Test
    @DisplayName("both line kinds keep their MDC tags, so they stay filterable in Loki")
    void linesCarryMdcTags() {
        when(behaviorService.listGroupHealth()).thenReturn(List.of(
                new GroupHealth("g2", "beta", "env-1", "116", 48, 1, 1, 50, false)));
        stubEnv("env-1", "prod-116", 50, 48, 0);
        when(sessionAggregationService.drainRollup()).thenReturn(List.of());

        rollup.rollupOnce();

        LogEvent envLine = appender.events.stream()
                .filter(e -> e.getMessage().getFormattedMessage().startsWith("env "))
                .findFirst().orElseThrow();
        LogEvent groupLine = appender.events.stream()
                .filter(e -> e.getMessage().getFormattedMessage().startsWith("group "))
                .findFirst().orElseThrow();

        assertThat(envLine.getContextData().<String>getValue(BotMdc.ENVIRONMENT_ID)).isEqualTo("env-1");
        assertThat(groupLine.getContextData().<String>getValue(BotMdc.BOT_GROUP_ID)).isEqualTo("g2");
        assertThat(groupLine.getContextData().<String>getValue(BotMdc.PRODUCT)).isEqualTo("116");
    }
}
