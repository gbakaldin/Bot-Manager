package com.vingame.bot.domain.metrics.controller;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.metrics.MetricKey;
import com.vingame.bot.domain.metrics.MetricScope;
import com.vingame.bot.domain.metrics.dto.MetricsSummaryDTO;
import com.vingame.bot.domain.metrics.dto.MetricsTimeseriesDTO;
import com.vingame.bot.domain.metrics.service.MetricsQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, read-only metrics API (METRICS_API Phase 4) — the controlled gateway in
 * front of the internal-only Prometheus. Four endpoints, two per scope:
 * <ul>
 *   <li>{@code GET /game/{gameId}/summary} — instant values for the full
 *       per-game catalog.</li>
 *   <li>{@code GET /game/{gameId}/timeseries?metric=&...} — one metric's series
 *       over a window.</li>
 *   <li>{@code GET /environment/{environmentId}/summary}</li>
 *   <li>{@code GET /environment/{environmentId}/timeseries?metric=&...}</li>
 * </ul>
 * The UI never sends PromQL — only a {@link MetricKey} key + scope id + time
 * window (AD-2). bot-manager owns every query.
 * <p>
 * Time params (OI-2): {@code timeseries} supports BOTH raw {@code from}/{@code to}/
 * {@code step} AND convenience {@code range} presets ({@code 1h|6h|24h|7d|30d}).
 * {@code range} and explicit {@code from}/{@code to} are mutually exclusive (see
 * {@link TimeWindow#resolve}).
 * <p>
 * Validation failures throw {@link BadRequestException} (→ 400) and Prometheus
 * failures surface as {@code UpstreamPrometheusException} (→ 502) — both via
 * {@link com.vingame.bot.common.exception.RestExceptionHandler}; the controller
 * expresses only the success path, mirroring {@code BotGroupController}.
 * <p>
 * Exposure mirrors the existing public {@code /bot-group/{id}/health} endpoint:
 * unauthenticated, with a rate-limit interceptor as the only hardening (AD-9,
 * OI-3). A stronger auth model remains OI-3.
 */
@RestController
@RequestMapping("api/v1/metrics")
public class MetricsController {

    private final MetricsQueryService service;

    public MetricsController(MetricsQueryService service) {
        this.service = service;
    }

    @Operation(
            summary = "Per-game metrics summary",
            description = "Current instant value of every scalar metric for the game, plus the "
                    + "bots-by-status breakdown and the resolved game name.")
    @GetMapping("/game/{gameId}/summary")
    public ResponseEntity<MetricsSummaryDTO> gameSummary(
            @PathVariable @Parameter(description = "gameId selector") String gameId) {
        return ResponseEntity.ok(service.summary(MetricScope.GAME, gameId));
    }

    @Operation(
            summary = "Per-game metric timeseries",
            description = "Time series for one metric over a window. Supply either a 'range' preset "
                    + "(1h|6h|24h|7d|30d) or explicit 'from'/'to' (epoch-seconds or ISO-8601) — not both. "
                    + "'step' is optional (default ~720 points). Range is capped at 30d.")
    @GetMapping("/game/{gameId}/timeseries")
    public ResponseEntity<MetricsTimeseriesDTO> gameTimeseries(
            @PathVariable @Parameter(description = "gameId selector") String gameId,
            @RequestParam @Parameter(description = "Metric key (e.g. winnings_rate_5m)") String metric,
            @RequestParam(required = false) @Parameter(description = "Preset window: 1h|6h|24h|7d|30d") String range,
            @RequestParam(required = false) @Parameter(description = "Window start (epoch-seconds or ISO-8601)") String from,
            @RequestParam(required = false) @Parameter(description = "Window end (epoch-seconds or ISO-8601)") String to,
            @RequestParam(required = false) @Parameter(description = "Step (60, 60s, 5m, 1h, or PT1M)") String step) {
        return timeseries(MetricScope.GAME, gameId, metric, range, from, to, step);
    }

    @Operation(
            summary = "Per-environment metrics summary",
            description = "Current instant value of every scalar metric for the environment, plus the "
                    + "bots-by-status breakdown and the resolved environment name.")
    @GetMapping("/environment/{environmentId}/summary")
    public ResponseEntity<MetricsSummaryDTO> environmentSummary(
            @PathVariable @Parameter(description = "environmentId selector") String environmentId) {
        return ResponseEntity.ok(service.summary(MetricScope.ENVIRONMENT, environmentId));
    }

    @Operation(
            summary = "Per-environment metric timeseries",
            description = "Time series for one metric over a window. Supply either a 'range' preset "
                    + "(1h|6h|24h|7d|30d) or explicit 'from'/'to' (epoch-seconds or ISO-8601) — not both. "
                    + "'step' is optional (default ~720 points). Range is capped at 30d.")
    @GetMapping("/environment/{environmentId}/timeseries")
    public ResponseEntity<MetricsTimeseriesDTO> environmentTimeseries(
            @PathVariable @Parameter(description = "environmentId selector") String environmentId,
            @RequestParam @Parameter(description = "Metric key (e.g. reconnect_rate_5m)") String metric,
            @RequestParam(required = false) @Parameter(description = "Preset window: 1h|6h|24h|7d|30d") String range,
            @RequestParam(required = false) @Parameter(description = "Window start (epoch-seconds or ISO-8601)") String from,
            @RequestParam(required = false) @Parameter(description = "Window end (epoch-seconds or ISO-8601)") String to,
            @RequestParam(required = false) @Parameter(description = "Step (60, 60s, 5m, 1h, or PT1M)") String step) {
        return timeseries(MetricScope.ENVIRONMENT, environmentId, metric, range, from, to, step);
    }

    /** Shared timeseries handling: metric/scope validation (AD-5) then window resolution (OI-2). */
    private ResponseEntity<MetricsTimeseriesDTO> timeseries(MetricScope scope, String id, String metric,
                                                            String range, String from, String to, String step) {
        MetricKey key = resolveMetric(scope, metric);
        TimeWindow window = TimeWindow.resolve(range, from, to, step);
        MetricsTimeseriesDTO dto = service.timeseries(scope, id, key, window.start(), window.end(), window.step());
        return ResponseEntity.ok(dto);
    }

    /**
     * Resolve a wire metric key to a {@link MetricKey} valid for the scope.
     * Unknown keys and cross-scope misuse (a GAME-only key on the environment
     * endpoint or vice-versa) both throw {@link BadRequestException} → 400 (AD-5).
     */
    private MetricKey resolveMetric(MetricScope scope, String metric) {
        MetricKey key = MetricKey.fromKey(metric);
        if (key == null) {
            throw new BadRequestException("Unknown metric '" + metric + "'.");
        }
        if (!key.supports(scope)) {
            throw new BadRequestException(
                    "Metric '" + metric + "' is not available for scope " + scope.name() + ".");
        }
        return key;
    }
}
