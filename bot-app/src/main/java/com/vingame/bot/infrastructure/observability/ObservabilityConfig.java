package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.domain.bot.core.BotStatus;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link BotMdcTagsMeterFilter} bean and registers aggregate gauges that
 * sample the live runtime state of all bot groups.
 * <p>
 * Aggregate gauges are global counts and intentionally carry NO {@code botGroupId} /
 * {@code environmentId} / {@code gameType} tags — the {@link BotMdcTagsMeterFilter}
 * excludes them by an explicit name allow-list.
 * <p>
 * The gauges are registered as a {@link MeterBinder} bean rather than via an
 * {@code @Autowired} method that takes {@code MeterRegistry}. Spring Boot's actuator
 * auto-configuration invokes {@code bindTo(...)} after the registry is fully
 * constructed, which breaks the bean-init cycle that Spring 6 / Boot 3.4 reject by
 * default: the auto-configured {@code MeterRegistry} consumes our {@code MeterFilter}
 * bean from this same class, so requesting the registry as a method parameter (or
 * an {@code @Autowired} field) on this class creates a self-referential dependency.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public MeterFilter botMdcTagsMeterFilter() {
        return new BotMdcTagsMeterFilter();
    }

    @Bean
    public MeterBinder botAggregateGauges(BotGroupBehaviorService behaviorService) {
        return registry -> {
            // strongReference(true): the state object is the singleton
            // BotGroupBehaviorService, which is always strongly reachable in
            // production. Micrometer's default weak reference adds no benefit here
            // and lets the gauge silently return NaN if the object is ever GC'd —
            // a fragility that also made these gauges flaky under test GC pressure.
            Gauge.builder("bot_groups_running", behaviorService,
                            BotGroupBehaviorService::getRunningGroupCount)
                    .strongReference(true)
                    .description("Number of bot groups currently running")
                    .register(registry);

            Gauge.builder("bots_managed", behaviorService,
                            BotGroupBehaviorService::getTotalManagedBots)
                    .strongReference(true)
                    .description("Total number of managed bot instances across all running groups")
                    .register(registry);

            Gauge.builder("ws_connections_open", behaviorService,
                            BotGroupBehaviorService::getOpenWsConnectionCount)
                    .strongReference(true)
                    .description("Number of bots with an open WebSocket connection across all running groups")
                    .register(registry);

            // One gauge per BotStatus value, tagged with status name. The MeterFilter's
            // name allow-list keeps these from picking up MDC-derived tags.
            for (BotStatus status : BotStatus.values()) {
                Gauge.builder("bots_by_status", behaviorService,
                                s -> s.countBotsByStatus(status))
                        .strongReference(true)
                        .description("Number of bots currently in the given status across all running groups")
                        .tag("status", status.name())
                        .register(registry);
            }

            // Phase 3: companion gauges to the bot/group dead-seconds counters. These
            // expose the current snapshot of how many bots/groups are in DEAD right
            // now — the counter answers "how much downtime", the gauge answers "how
            // many are down right now". Aggregate; no MDC tags.
            Gauge.builder("bots_dead_currently", behaviorService,
                            BotGroupBehaviorService::countBotsDeadCurrently)
                    .strongReference(true)
                    .description("Number of bots currently in DEAD state across all running groups")
                    .register(registry);

            Gauge.builder("groups_dead_currently", behaviorService,
                            BotGroupBehaviorService::countGroupsDeadCurrently)
                    .strongReference(true)
                    .description("Number of bot groups currently in DEAD state")
                    .register(registry);
        };
    }
}
