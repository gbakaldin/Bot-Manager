package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Micrometer {@link MeterFilter} that reads bot identity tags from MDC and attaches
 * them as common tags on every {@code bot_*} meter. Implements Architecture Decision 10
 * (MDC-as-tags via MeterFilter).
 * <p>
 * <b>Caveat — defense-in-depth, not the primary mechanism:</b> Micrometer's
 * {@code AbstractMeterRegistry#getOrCreateMeter} caches Counter handles by the
 * <i>pre-filter</i> {@code Meter.Id} (see {@code preFilterIdToMeterMap}). If two
 * callsites register the same name+tags under different MDC values, both will resolve
 * to the same cached Counter — the filter's mapping is bypassed for the second call.
 * Therefore the per-callsite tagging is performed inside {@link BotMetrics} itself
 * (which reads MDC and applies tags BEFORE registration, producing distinct
 * pre-filter ids). This filter handles two remaining responsibilities:
 * <ol>
 *   <li>If some future code emits a {@code bot_*} meter outside {@link BotMetrics}
 *       without MDC tags, this filter is the safety net that still applies them.</li>
 *   <li>The aggregate-gauge allow-list ensures that {@code bot_groups_running},
 *       {@code bots_managed}, {@code ws_connections_open}, and {@code bots_by_status}
 *       never accidentally pick up per-bot tags from a thread that happens to have
 *       MDC populated.</li>
 * </ol>
 * Spring-internal meters ({@code jvm_*}, {@code http_server_requests_*}, etc.) and
 * any other non-{@code bot_*} meter pass through untouched.
 */
public class BotMdcTagsMeterFilter implements MeterFilter {

    private static final String PREFIX_BOT = "bot_";

    /**
     * Meter names that must NOT receive MDC-derived tags. These are aggregate gauges
     * registered in {@link ObservabilityConfig} that read across all bot groups and
     * therefore have no single owning group/env/game.
     */
    private static final Set<String> AGGREGATE_METER_NAMES = Set.of(
            "bots_managed",
            "bot_groups_running",
            "ws_connections_open",
            "bots_by_status",
            "bots_dead_currently",
            "groups_dead_currently"
    );

    @Override
    public Meter.Id map(Meter.Id id) {
        String name = id.getName();
        if (AGGREGATE_METER_NAMES.contains(name)) {
            return id;
        }
        if (!name.startsWith(PREFIX_BOT)) {
            return id;
        }

        List<Tag> extra = new ArrayList<>(5);
        addTagIfPresent(extra, BotMdc.BOT_GROUP_ID);
        addTagIfPresent(extra, BotMdc.ENVIRONMENT_ID);
        addTagIfPresent(extra, BotMdc.GAME_TYPE);
        addTagIfPresent(extra, BotMdc.GAME_ID);
        addTagIfPresent(extra, BotMdc.GAME_NAME);

        if (extra.isEmpty()) {
            return id;
        }
        return id.withTags(Tags.of(id.getTags()).and(extra));
    }

    private static void addTagIfPresent(List<Tag> tags, String key) {
        String value = MDC.get(key);
        if (value != null && !value.isEmpty()) {
            tags.add(Tag.of(key, value));
        }
    }
}
