package com.vingame.bot.domain.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Summary response: the current scalar value of every scalar metric for a scope
 * in one DTO (the instant-{@code query} shape, METRICS_API AD-6). The
 * multi-series {@code bots_by_status} panel is carried as a separate
 * {@code status -> count} map; the rest are flattened into {@code metrics}.
 * A metric whose value was non-finite (NaN/Inf) maps to {@code null}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricsSummaryDTO {

    /** {@code GAME} or {@code ENVIRONMENT}. */
    private String scope;

    /** The scope id ({@code gameId} / {@code environmentId}). */
    private String scopeId;

    /** Resolved readable name from the join gauge; {@code null} if unresolved. */
    private String scopeName;

    /** {@code metricKey -> currentValue} for every scalar metric in scope. */
    private Map<String, Double> metrics;

    /** {@code status -> botCount} (the multi-series {@code bots_by_status} panel). */
    private Map<String, Double> botsByStatus;

    /** When this response was assembled. */
    private Instant generatedAt;
}
