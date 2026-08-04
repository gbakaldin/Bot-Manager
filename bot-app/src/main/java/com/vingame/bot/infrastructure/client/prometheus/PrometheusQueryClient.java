package com.vingame.bot.infrastructure.client.prometheus;

import java.time.Duration;
import java.time.Instant;

/**
 * Narrow, internal-only gateway to a metrics backend (Prometheus today). The
 * metrics service depends on this interface, not the HTTP implementation, so a
 * future long-term-store swap (Thanos/Mimir/Timescale) is a single
 * implementation change with no change to the controller/DTOs/UI contract
 * (METRICS_API AD-10).
 */
public interface PrometheusQueryClient {

    /**
     * Run an instant query ({@code /api/v1/query}).
     *
     * @param promql the fully-resolved PromQL expression.
     * @param time   evaluation instant.
     * @return the parsed result (VECTOR or SCALAR); empty result → empty series.
     */
    PrometheusResult queryInstant(String promql, Instant time);

    /**
     * Run a range query ({@code /api/v1/query_range}).
     *
     * @param promql the fully-resolved PromQL expression.
     * @param start  range start.
     * @param end    range end.
     * @param step   resolution step.
     * @return the parsed result (MATRIX); empty result → empty series.
     */
    PrometheusResult queryRange(String promql, Instant start, Instant end, Duration step);
}
