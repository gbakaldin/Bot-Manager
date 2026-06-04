package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import com.vingame.bot.domain.bot.core.BotStatus;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ObservabilityConfig} registers aggregate gauges that read
 * directly from the {@link BotGroupBehaviorService} accessors AND that those gauges
 * are NOT decorated by {@link BotMdcTagsMeterFilter} even when MDC is populated.
 */
class ObservabilityConfigTest {

    private MeterRegistry registry;
    private BotGroupBehaviorService behaviorService;
    private ObservabilityConfig config;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new BotMdcTagsMeterFilter());
        behaviorService = mock(BotGroupBehaviorService.class);
        config = new ObservabilityConfig();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void botGroupsRunningGauge_readsFromService() {
        when(behaviorService.getRunningGroupCount()).thenReturn(3);

        config.registerAggregateGauges(registry, behaviorService);

        Gauge gauge = registry.find("bot_groups_running").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(3.0);

        when(behaviorService.getRunningGroupCount()).thenReturn(5);
        assertThat(gauge.value()).isEqualTo(5.0);
    }

    @Test
    void botsManagedGauge_readsFromService() {
        when(behaviorService.getTotalManagedBots()).thenReturn(42);

        config.registerAggregateGauges(registry, behaviorService);

        Gauge gauge = registry.find("bots_managed").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(42.0);
    }

    @Test
    void wsConnectionsOpenGauge_readsFromService() {
        when(behaviorService.getOpenWsConnectionCount()).thenReturn(15);

        config.registerAggregateGauges(registry, behaviorService);

        Gauge gauge = registry.find("ws_connections_open").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(15.0);
    }

    @Test
    void botsByStatusGauge_oneSeriesPerStatus() {
        for (BotStatus s : BotStatus.values()) {
            when(behaviorService.countBotsByStatus(s)).thenReturn(s == BotStatus.DEAD ? 2 : 0);
        }

        config.registerAggregateGauges(registry, behaviorService);

        for (BotStatus s : BotStatus.values()) {
            Gauge g = registry.find("bots_by_status").tag("status", s.name()).gauge();
            assertThat(g).as("bots_by_status gauge for status %s", s).isNotNull();
            if (s == BotStatus.DEAD) {
                assertThat(g.value()).isEqualTo(2.0);
            } else {
                assertThat(g.value()).isEqualTo(0.0);
            }
        }
    }

    @Test
    void botsDeadCurrentlyGauge_readsFromService() {
        when(behaviorService.countBotsDeadCurrently()).thenReturn(4);

        config.registerAggregateGauges(registry, behaviorService);

        Gauge gauge = registry.find("bots_dead_currently").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(4.0);

        when(behaviorService.countBotsDeadCurrently()).thenReturn(0);
        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void groupsDeadCurrentlyGauge_readsFromService() {
        when(behaviorService.countGroupsDeadCurrently()).thenReturn(2);

        config.registerAggregateGauges(registry, behaviorService);

        Gauge gauge = registry.find("groups_dead_currently").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(2.0);
    }

    @Test
    void deadCurrentlyGauges_areOnTheAggregateExclusionList() {
        // Populate MDC: if the gauges were on the bot_*-prefixed branch they would
        // pick up these tags. The allow-list (BotMdcTagsMeterFilter) keeps them clean.
        MDC.put(BotMdc.BOT_GROUP_ID, "group-xyz");
        MDC.put(BotMdc.ENVIRONMENT_ID, "env-xyz");
        MDC.put(BotMdc.GAME_TYPE, "BauCua");

        when(behaviorService.countBotsDeadCurrently()).thenReturn(1);
        when(behaviorService.countGroupsDeadCurrently()).thenReturn(1);

        config.registerAggregateGauges(registry, behaviorService);

        for (String name : new String[]{"bots_dead_currently", "groups_dead_currently"}) {
            Gauge gauge = registry.find(name).gauge();
            assertThat(gauge).as("aggregate gauge %s should be registered", name).isNotNull();
            boolean hasGroup = gauge.getId().getTags().stream()
                    .anyMatch(t -> BotMdc.BOT_GROUP_ID.equals(t.getKey()));
            assertThat(hasGroup).as("aggregate gauge %s must not carry botGroupId", name).isFalse();
        }
    }

    @Test
    void aggregateGauges_doNotReceiveMdcTags_evenWhenMdcIsPopulated() {
        MDC.put(BotMdc.BOT_GROUP_ID, "group-xyz");
        MDC.put(BotMdc.ENVIRONMENT_ID, "env-xyz");
        MDC.put(BotMdc.GAME_TYPE, "BauCua");

        when(behaviorService.getRunningGroupCount()).thenReturn(1);
        when(behaviorService.getTotalManagedBots()).thenReturn(5);
        when(behaviorService.getOpenWsConnectionCount()).thenReturn(5);

        config.registerAggregateGauges(registry, behaviorService);

        for (String name : new String[]{"bot_groups_running", "bots_managed", "ws_connections_open"}) {
            Gauge gauge = registry.find(name).gauge();
            assertThat(gauge).as("aggregate gauge %s should be registered", name).isNotNull();
            boolean hasGroup = gauge.getId().getTags().stream()
                    .anyMatch(t -> BotMdc.BOT_GROUP_ID.equals(t.getKey()));
            assertThat(hasGroup).as("aggregate gauge %s must not carry botGroupId", name).isFalse();
        }
    }
}
