package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.domain.bot.core.BotStatus;
import com.vingame.bot.domain.botgroup.service.BotGroupBehaviorService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Autowired;
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
 * The gauges are registered after Spring has initialized {@link BotGroupBehaviorService}
 * so the lambda callbacks can safely read the live {@code runningGroups} map. The
 * registry holds a weak reference to the service; eager creation in the constructor
 * keeps the gauges alive for the application lifetime.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public MeterFilter botMdcTagsMeterFilter() {
        return new BotMdcTagsMeterFilter();
    }

    @Autowired
    public void registerAggregateGauges(MeterRegistry registry,
                                        BotGroupBehaviorService behaviorService) {
        Gauge.builder("bot_groups_running", behaviorService,
                        BotGroupBehaviorService::getRunningGroupCount)
                .description("Number of bot groups currently running")
                .register(registry);

        Gauge.builder("bots_managed", behaviorService,
                        BotGroupBehaviorService::getTotalManagedBots)
                .description("Total number of managed bot instances across all running groups")
                .register(registry);

        Gauge.builder("ws_connections_open", behaviorService,
                        BotGroupBehaviorService::getOpenWsConnectionCount)
                .description("Number of bots with an open WebSocket connection across all running groups")
                .register(registry);

        // One gauge per BotStatus value, tagged with status name. The MeterFilter's
        // name allow-list keeps these from picking up MDC-derived tags.
        for (BotStatus status : BotStatus.values()) {
            Gauge.builder("bots_by_status", behaviorService,
                            s -> s.countBotsByStatus(status))
                    .description("Number of bots currently in the given status across all running groups")
                    .tag("status", status.name())
                    .register(registry);
        }
    }
}
