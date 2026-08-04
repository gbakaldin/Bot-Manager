package com.vingame.bot.domain.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Timeseries response for one metric over a window (the {@code query_range}
 * shape, METRICS_API AD-6). An empty result yields an empty {@code series} list
 * with HTTP 200 (not 404) so the UI renders an empty chart, not an error.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricsTimeseriesDTO {

    /** {@code GAME} or {@code ENVIRONMENT}. */
    private String scope;

    /** The scope id ({@code gameId} / {@code environmentId}). */
    private String scopeId;

    /** Resolved readable name from the join gauge ({@code gameName} / {@code environmentName}); {@code null} if unresolved. */
    private String scopeName;

    /** The metric key (e.g. {@code winnings_rate_5m}). */
    private String metric;

    /** Requested window start (epoch seconds). */
    private long from;

    /** Requested window end (epoch seconds). */
    private long to;

    /** Resolved step in seconds. */
    private long step;

    /** One series per label set; single-series metrics carry exactly one. */
    private List<MetricSeriesDTO> series;

    /** When this response was assembled. */
    private Instant generatedAt;
}
