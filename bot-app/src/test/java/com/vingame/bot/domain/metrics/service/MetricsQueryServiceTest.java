package com.vingame.bot.domain.metrics.service;

import com.vingame.bot.domain.metrics.MetricKey;
import com.vingame.bot.domain.metrics.MetricScope;
import com.vingame.bot.domain.metrics.dto.MetricSeriesDTO;
import com.vingame.bot.domain.metrics.dto.MetricsSummaryDTO;
import com.vingame.bot.domain.metrics.dto.MetricsTimeseriesDTO;
import com.vingame.bot.infrastructure.client.prometheus.PrometheusQueryClient;
import com.vingame.bot.infrastructure.client.prometheus.PrometheusResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies DTO shaping (METRICS_API Phase 3): vector→summary, matrix→timeseries,
 * multi-series mapping, empty-result→empty-series, and join-gauge name
 * resolution. The client is mocked — no live Prometheus.
 */
class MetricsQueryServiceTest {

    private static final String SUMMARY_WINDOW = "30d";
    private static final String TIMESERIES_WINDOW = "1h";

    private final PrometheusQueryClient client = mock(PrometheusQueryClient.class);
    private final MetricsQueryService service = new MetricsQueryService(client);

    @BeforeEach
    void setWindows() {
        ReflectionTestUtils.setField(service, "rtpSummaryWindow", SUMMARY_WINDOW);
        ReflectionTestUtils.setField(service, "rtpTimeseriesWindow", TIMESERIES_WINDOW);
    }

    private static PrometheusResult vector(PrometheusResult.Series... series) {
        return new PrometheusResult(PrometheusResult.ResultType.VECTOR, List.of(series));
    }

    private static PrometheusResult matrix(PrometheusResult.Series... series) {
        return new PrometheusResult(PrometheusResult.ResultType.MATRIX, List.of(series));
    }

    private static PrometheusResult.Series scalarSeries(double value) {
        return new PrometheusResult.Series(Map.of(),
                List.of(new PrometheusResult.Sample(1700000000L, value)));
    }

    @Test
    void summaryMapsScalarVectorsAndResolvesGameName() {
        // join gauge → readable name
        when(client.queryInstant(eq("game_join{gameId=\"g1\"}"), any()))
                .thenReturn(vector(new PrometheusResult.Series(
                        Map.of("gameId", "g1", "gameName", "BauCua", "gameType", "BETTING_MINI"),
                        List.of(new PrometheusResult.Sample(1700000000L, 1.0)))));

        // bots_by_status multi-series → status map
        when(client.queryInstant(eq("bots_by_game_status{gameId=\"g1\"}"), any()))
                .thenReturn(vector(
                        new PrometheusResult.Series(Map.of("status", "CONNECTION_AUTHENTICATED"),
                                List.of(new PrometheusResult.Sample(1700000000L, 7.0))),
                        new PrometheusResult.Series(Map.of("status", "RECONNECTING"),
                                List.of(new PrometheusResult.Sample(1700000000L, 2.0)))));

        // every other scalar key → a single value
        when(client.queryInstant(eq("sum(bots_by_game_status{gameId=\"g1\"})"), any()))
                .thenReturn(vector(scalarSeries(9.0)));
        // default for any remaining scalar keys
        for (MetricKey key : MetricKey.values()) {
            if (key.supports(MetricScope.GAME) && !key.isMultiSeries()
                    && key != MetricKey.TOTAL_BOTS) {
                // The service resolves $__range to the summary window before dispatch,
                // so stub the post-substitution PromQL (no-op for non-RTP keys).
                String dispatched = key.promql(MetricScope.GAME, "g1").replace("$__range", SUMMARY_WINDOW);
                when(client.queryInstant(eq(dispatched), any()))
                        .thenReturn(vector(scalarSeries(1.0)));
            }
        }

        MetricsSummaryDTO dto = service.summary(MetricScope.GAME, "g1");

        assertThat(dto.getScope()).isEqualTo("GAME");
        assertThat(dto.getScopeId()).isEqualTo("g1");
        assertThat(dto.getScopeName()).isEqualTo("BauCua");
        assertThat(dto.getMetrics()).containsEntry("total_bots", 9.0);
        // multi-series keys excluded from the scalar metrics map
        assertThat(dto.getMetrics()).doesNotContainKey("bots_by_status");
        // game-only key present, env-only key absent
        assertThat(dto.getMetrics()).containsKey("jackpots_rate_5m");
        assertThat(dto.getMetrics()).doesNotContainKey("reconnect_rate_5m");
        assertThat(dto.getBotsByStatus())
                .containsEntry("CONNECTION_AUTHENTICATED", 7.0)
                .containsEntry("RECONNECTING", 2.0);
    }

    @Test
    void timeseriesMapsMatrixToOrderedPoints() {
        when(client.queryInstant(any(), any())).thenReturn(vector());
        when(client.queryRange(eq(MetricKey.WINNINGS_RATE_5M.promql(MetricScope.GAME, "g1")),
                any(), any(), any()))
                .thenReturn(matrix(new PrometheusResult.Series(Map.of("__name__", "x"),
                        List.of(new PrometheusResult.Sample(1700000000L, 1.5),
                                new PrometheusResult.Sample(1700000060L, 2.5)))));

        MetricsTimeseriesDTO dto = service.timeseries(MetricScope.GAME, "g1",
                MetricKey.WINNINGS_RATE_5M,
                Instant.ofEpochSecond(1700000000L), Instant.ofEpochSecond(1700000060L),
                Duration.ofSeconds(60));

        assertThat(dto.getMetric()).isEqualTo("winnings_rate_5m");
        assertThat(dto.getStep()).isEqualTo(60L);
        assertThat(dto.getSeries()).hasSize(1);
        MetricSeriesDTO series = dto.getSeries().get(0);
        // __name__ stripped from exposed labels
        assertThat(series.getLabels()).doesNotContainKey("__name__");
        assertThat(series.getPoints()).hasSize(2);
        assertThat(series.getPoints().get(0).getTimestamp()).isEqualTo(1700000000L);
        assertThat(series.getPoints().get(0).getValue()).isEqualTo(1.5);
        assertThat(series.getPoints().get(1).getValue()).isEqualTo(2.5);
    }

    @Test
    void multiSeriesTimeseriesYieldsOneSeriesPerLabelSet() {
        when(client.queryInstant(any(), any())).thenReturn(vector());
        when(client.queryRange(eq(MetricKey.RECONNECT_RATE_5M.promql(MetricScope.ENVIRONMENT, "e1")),
                any(), any(), any()))
                .thenReturn(matrix(
                        new PrometheusResult.Series(Map.of("reason", "watchdog"),
                                List.of(new PrometheusResult.Sample(1700000000L, 0.1))),
                        new PrometheusResult.Series(Map.of("reason", "disconnect"),
                                List.of(new PrometheusResult.Sample(1700000000L, 0.2)))));

        MetricsTimeseriesDTO dto = service.timeseries(MetricScope.ENVIRONMENT, "e1",
                MetricKey.RECONNECT_RATE_5M,
                Instant.ofEpochSecond(1700000000L), Instant.ofEpochSecond(1700000060L),
                Duration.ofSeconds(60));

        assertThat(dto.getSeries()).hasSize(2);
        assertThat(dto.getSeries().get(0).getLabels()).containsEntry("reason", "watchdog");
        assertThat(dto.getSeries().get(1).getLabels()).containsEntry("reason", "disconnect");
    }

    @Test
    void emptyResultYieldsEmptySeriesNotError() {
        when(client.queryInstant(any(), any())).thenReturn(vector());
        when(client.queryRange(any(), any(), any(), any())).thenReturn(matrix());

        MetricsTimeseriesDTO dto = service.timeseries(MetricScope.GAME, "g1",
                MetricKey.BETS_PLACED_RATE_1M,
                Instant.ofEpochSecond(1700000000L), Instant.ofEpochSecond(1700003600L),
                Duration.ofSeconds(60));

        assertThat(dto.getSeries()).isEmpty();
    }

    @Test
    void unresolvedJoinGaugeLeavesNullName() {
        when(client.queryInstant(any(), any())).thenReturn(vector());

        MetricsSummaryDTO dto = service.summary(MetricScope.ENVIRONMENT, "e-unknown");

        assertThat(dto.getScopeName()).isNull();
        assertThat(dto.getScope()).isEqualTo("ENVIRONMENT");
    }
}
