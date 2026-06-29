package com.vingame.bot.domain.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * One series within a timeseries response. Single-series metrics yield exactly
 * one of these; multi-series metrics ({@code bots_by_status},
 * {@code rtp_per_game_5m}, {@code reconnect_rate_5m}) yield one per label set so
 * the UI can legend them (METRICS_API AD-6).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricSeriesDTO {

    /** The series' distinguishing labels (e.g. {@code {status}}, {@code {gameName}}, {@code {reason}}); may be empty for a single aggregate series. */
    private Map<String, String> labels;

    /** Ordered points across the requested window. */
    private List<MetricPointDTO> points;
}
