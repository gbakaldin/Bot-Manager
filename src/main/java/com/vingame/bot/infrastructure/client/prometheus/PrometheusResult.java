package com.vingame.bot.infrastructure.client.prometheus;

import java.util.List;
import java.util.Map;

/**
 * Thin, backend-neutral model of a Prometheus query response, produced by
 * {@link PrometheusQueryClient}. Deliberately decoupled from the Prometheus
 * wire envelope and from the public metrics DTOs — mapping to DTOs lives in the
 * metrics service (METRICS_API AD-6 / AD-10), so a future store swap touches
 * only the client.
 *
 * @param resultType VECTOR for instant queries, MATRIX for range queries,
 *                   SCALAR for scalar expressions.
 * @param series     one entry per result series; an empty list for an empty
 *                   result (a valid, non-error outcome).
 */
public record PrometheusResult(ResultType resultType, List<Series> series) {

    public enum ResultType {
        VECTOR, MATRIX, SCALAR
    }

    /**
     * A single result series: its label set and its samples. An instant
     * (vector) series carries exactly one sample; a range (matrix) series
     * carries the ordered list of samples across the window.
     *
     * @param labels  the metric's label set ({@code __name__} included as
     *                Prometheus returns it); empty for scalar results.
     * @param samples ordered samples; sample values that were non-finite on the
     *                wire ({@code NaN}/{@code +Inf}/{@code -Inf}) are carried as
     *                {@code null} so the JSON stays valid.
     */
    public record Series(Map<String, String> labels, List<Sample> samples) {
    }

    /**
     * One sample point.
     *
     * @param timestamp epoch seconds (Prometheus' sample timestamp).
     * @param value     the parsed double value, or {@code null} when the wire
     *                  value was non-finite.
     */
    public record Sample(long timestamp, Double value) {
    }
}
