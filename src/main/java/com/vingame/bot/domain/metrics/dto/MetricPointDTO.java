package com.vingame.bot.domain.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One time-series point. {@code value} is {@code null} when the underlying
 * Prometheus sample was non-finite (NaN/Inf) so the UI can skip-render the gap
 * (METRICS_API AD-7).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricPointDTO {

    /** Sample timestamp in epoch seconds. */
    private long timestamp;

    /** Parsed value, or {@code null} for a non-finite sample. */
    private Double value;
}
