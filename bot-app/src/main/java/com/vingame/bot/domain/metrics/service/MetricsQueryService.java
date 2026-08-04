package com.vingame.bot.domain.metrics.service;

import com.vingame.bot.domain.metrics.MetricKey;
import com.vingame.bot.domain.metrics.MetricScope;
import com.vingame.bot.domain.metrics.dto.MetricPointDTO;
import com.vingame.bot.domain.metrics.dto.MetricSeriesDTO;
import com.vingame.bot.domain.metrics.dto.MetricsSummaryDTO;
import com.vingame.bot.domain.metrics.dto.MetricsTimeseriesDTO;
import com.vingame.bot.infrastructure.client.prometheus.PrometheusQueryClient;
import com.vingame.bot.infrastructure.client.prometheus.PrometheusResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for resolving a {@link MetricKey} + scope + time window
 * into clean, UI-bindable DTOs by querying Prometheus through
 * {@link PrometheusQueryClient} (METRICS_API Phase 3).
 * <p>
 * The service owns no PromQL itself — every query string comes from
 * {@link MetricKey} (which copies the dashboards verbatim). The readable scope
 * name is resolved from the join gauges ({@code game_join} / {@code environment_join},
 * {@code InfoGaugeRefresher}). Mapping {@code PrometheusResult} → DTO lives here,
 * not in the client (AD-6).
 */
@Slf4j
@Service
public class MetricsQueryService {

    private static final String GAME_JOIN = "game_join{%s}";
    private static final String ENVIRONMENT_JOIN = "environment_join{%s}";
    private static final String GAME_NAME_LABEL = "gameName";
    private static final String ENVIRONMENT_NAME_LABEL = "environmentName";
    private static final String STATUS_LABEL = "status";

    /** Grafana-only token stored verbatim in RTP templates; must never reach Prometheus (AD-6). */
    private static final String RANGE_TOKEN = "$__range";

    private final PrometheusQueryClient client;

    /** Concrete window the summary (instant) RTP query resolves {@code $__range} to (AD-6). */
    @Value("${metrics.rtp.summary-window:30d}")
    private String rtpSummaryWindow;

    /** Concrete sliding window each timeseries RTP point resolves {@code $__range} to (AD-6). */
    @Value("${metrics.rtp.timeseries-window:1h}")
    private String rtpTimeseriesWindow;

    public MetricsQueryService(PrometheusQueryClient client) {
        this.client = client;
    }

    /**
     * Resolve the Grafana-only {@code $__range} token in a built PromQL to a
     * concrete duration (AD-6). Non-RTP keys carry no token and pass through
     * untouched. {@link String#replace(CharSequence, CharSequence)} is non-regex,
     * so the literal {@code $} is safe.
     */
    private String applyWindow(String promql, String window) {
        return promql.replace(RANGE_TOKEN, window);
    }

    /**
     * Assemble the summary DTO: an instant query per scalar metric key for the
     * scope, plus {@code bots_by_status} as a status→count map, plus the
     * resolved scope name.
     */
    public MetricsSummaryDTO summary(MetricScope scope, String id) {
        // Live "now" instant queries pass null to the client so they dedupe within
        // the cache TTL (AD-8); `now` is used only for the DTO's generatedAt.
        Instant now = Instant.now();
        String scopeName = resolveScopeName(scope, id, null);

        Map<String, Double> metrics = new LinkedHashMap<>();
        for (MetricKey key : MetricKey.values()) {
            if (!key.supports(scope) || key.isMultiSeries()) {
                continue; // multi-series keys are not scalar summary panels
            }
            String promql = applyWindow(key.promql(scope, id), rtpSummaryWindow);
            PrometheusResult result = client.queryInstant(promql, null);
            metrics.put(key.key(), firstScalar(result));
        }

        Map<String, Double> botsByStatus = resolveBotsByStatus(scope, id, null);

        return MetricsSummaryDTO.builder()
                .scope(scope.name())
                .scopeId(id)
                .scopeName(scopeName)
                .metrics(metrics)
                .botsByStatus(botsByStatus)
                .generatedAt(now)
                .build();
    }

    /**
     * Assemble the timeseries DTO for one metric: a single {@code query_range},
     * mapped to one or more series (multi-series metrics yield one per label set).
     * An empty result yields an empty series list (HTTP 200, not 404).
     */
    public MetricsTimeseriesDTO timeseries(MetricScope scope, String id, MetricKey key,
                                           Instant start, Instant end, Duration step) {
        String scopeName = resolveScopeName(scope, id, end);
        String promql = applyWindow(key.promql(scope, id), rtpTimeseriesWindow);
        PrometheusResult result = client.queryRange(promql, start, end, step);

        List<MetricSeriesDTO> series = result.series().stream()
                .map(this::toSeriesDTO)
                .toList();

        return MetricsTimeseriesDTO.builder()
                .scope(scope.name())
                .scopeId(id)
                .scopeName(scopeName)
                .metric(key.key())
                .from(start.getEpochSecond())
                .to(end.getEpochSecond())
                .step(step.toSeconds())
                .series(series)
                .generatedAt(Instant.now())
                .build();
    }

    private MetricSeriesDTO toSeriesDTO(PrometheusResult.Series series) {
        // Drop __name__ from the exposed labels — it is internal Prometheus
        // bookkeeping, not a UI-meaningful series discriminator.
        Map<String, String> labels = new LinkedHashMap<>(series.labels());
        labels.remove("__name__");

        List<MetricPointDTO> points = series.samples().stream()
                .map(s -> MetricPointDTO.builder().timestamp(s.timestamp()).value(s.value()).build())
                .toList();

        return MetricSeriesDTO.builder().labels(labels).points(points).build();
    }

    private Map<String, Double> resolveBotsByStatus(MetricScope scope, String id, Instant time) {
        PrometheusResult result = client.queryInstant(MetricKey.BOTS_BY_STATUS.promql(scope, id), time);
        Map<String, Double> byStatus = new LinkedHashMap<>();
        for (PrometheusResult.Series s : result.series()) {
            String status = s.labels().get(STATUS_LABEL);
            Double value = s.samples().isEmpty() ? null : s.samples().get(0).value();
            if (status != null) {
                byStatus.put(status, value);
            }
        }
        return byStatus;
    }

    /**
     * Resolve the readable scope name from the join gauge. Returns {@code null}
     * if Prometheus has no join series for the id (e.g. a never-started scope) —
     * mirrors dashboard behavior rather than failing the whole response.
     */
    private String resolveScopeName(MetricScope scope, String id, Instant time) {
        String template = scope == MetricScope.GAME ? GAME_JOIN : ENVIRONMENT_JOIN;
        String nameLabel = scope == MetricScope.GAME ? GAME_NAME_LABEL : ENVIRONMENT_NAME_LABEL;
        String promql = String.format(template, scope.selector(id));

        PrometheusResult result = client.queryInstant(promql, time);
        for (PrometheusResult.Series s : result.series()) {
            String name = s.labels().get(nameLabel);
            if (name != null) {
                return name;
            }
        }
        log.debug("No {} join series for {}=\"{}\"", nameLabel, scope.selectorLabel(), id);
        return null;
    }

    /** First scalar value from an instant result, or {@code null} if empty/non-finite. */
    private Double firstScalar(PrometheusResult result) {
        if (result.series().isEmpty()) {
            return null;
        }
        List<PrometheusResult.Sample> samples = result.series().get(0).samples();
        return samples.isEmpty() ? null : samples.get(0).value();
    }
}
