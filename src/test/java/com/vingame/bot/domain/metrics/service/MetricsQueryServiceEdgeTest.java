package com.vingame.bot.domain.metrics.service;

import com.vingame.bot.domain.metrics.MetricKey;
import com.vingame.bot.domain.metrics.MetricScope;
import com.vingame.bot.domain.metrics.dto.MetricsSummaryDTO;
import com.vingame.bot.domain.metrics.dto.MetricsTimeseriesDTO;
import com.vingame.bot.infrastructure.client.prometheus.PrometheusQueryClient;
import com.vingame.bot.infrastructure.client.prometheus.PrometheusResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA-added edge coverage for {@link MetricsQueryService} that complements the
 * dev's mapping tests: the RTP {@code or vector(0)} guard surfacing as {@code 0.0}
 * (never null/NaN), a non-finite scalar arriving as {@code null} from the client,
 * a {@code bots_by_status} series missing its {@code status} label being skipped,
 * the {@code timeseries} DTO echoing the requested window verbatim, series with a
 * {@code null} sample value being carried through (gap rendering), and the AD-6
 * guarantee that the Grafana-only {@code $__range} token is always resolved to a
 * concrete window before dispatch and never reaches Prometheus.
 */
@DisplayName("MetricsQueryService (edge)")
class MetricsQueryServiceEdgeTest {

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

    private static PrometheusResult.Series scalar(Double value) {
        return new PrometheusResult.Series(Map.of(),
                List.of(new PrometheusResult.Sample(1700000000L, value)));
    }

    /** The PromQL the service actually dispatches for an RTP summary query. */
    private static String dispatchedRtpSummary() {
        return MetricKey.RTP.promql(MetricScope.GAME, "g1").replace("$__range", SUMMARY_WINDOW);
    }

    @Test
    @DisplayName("RTP guard 'or vector(0)' surfaces as 0.0, not null")
    void rtpZeroGuardSurfacesAsZero() {
        // Default every scalar key to empty, then override RTP with a 0.0 result.
        when(client.queryInstant(any(), any())).thenReturn(vector());
        when(client.queryInstant(eq(dispatchedRtpSummary()), any()))
                .thenReturn(vector(scalar(0.0)));

        MetricsSummaryDTO dto = service.summary(MetricScope.GAME, "g1");

        assertThat(dto.getMetrics()).contains(entry("rtp", 0.0));
    }

    @Test
    @DisplayName("a non-finite scalar (null from the client) is carried as null in the metrics map")
    void nonFiniteScalarCarriedAsNull() {
        when(client.queryInstant(any(), any())).thenReturn(vector());
        when(client.queryInstant(eq(dispatchedRtpSummary()), any()))
                .thenReturn(vector(scalar(null))); // NaN/Inf already mapped to null by the client

        MetricsSummaryDTO dto = service.summary(MetricScope.GAME, "g1");

        assertThat(dto.getMetrics()).containsKey("rtp");
        assertThat(dto.getMetrics().get("rtp")).isNull();
    }

    @Test
    @DisplayName("a bots_by_status series missing its status label is skipped")
    void botsByStatusSkipsSeriesWithoutStatusLabel() {
        when(client.queryInstant(any(), any())).thenReturn(vector());
        when(client.queryInstant(eq(MetricKey.BOTS_BY_STATUS.promql(MetricScope.GAME, "g1")), any()))
                .thenReturn(vector(
                        new PrometheusResult.Series(Map.of("status", "OK"),
                                List.of(new PrometheusResult.Sample(1700000000L, 3.0))),
                        // No status label → must be skipped, not NPE.
                        new PrometheusResult.Series(Map.of("other", "x"),
                                List.of(new PrometheusResult.Sample(1700000000L, 9.0)))));

        MetricsSummaryDTO dto = service.summary(MetricScope.GAME, "g1");

        assertThat(dto.getBotsByStatus()).containsOnly(entry("OK", 3.0));
    }

    @Test
    @DisplayName("timeseries DTO echoes the requested from/to/step verbatim")
    void timeseriesEchoesRequestedWindow() {
        when(client.queryInstant(any(), any())).thenReturn(vector());
        when(client.queryRange(any(), any(), any(), any()))
                .thenReturn(new PrometheusResult(PrometheusResult.ResultType.MATRIX, List.of()));

        MetricsTimeseriesDTO dto = service.timeseries(MetricScope.GAME, "g1",
                MetricKey.WINNINGS_RATE_5M,
                Instant.ofEpochSecond(1700000000L), Instant.ofEpochSecond(1700003600L),
                Duration.ofSeconds(90));

        assertThat(dto.getFrom()).isEqualTo(1700000000L);
        assertThat(dto.getTo()).isEqualTo(1700003600L);
        assertThat(dto.getStep()).isEqualTo(90L);
        assertThat(dto.getSeries()).isEmpty();
    }

    @Test
    @DisplayName("a null sample value in a series is carried through as a null point (gap)")
    void nullSampleValueCarriedAsGap() {
        when(client.queryInstant(any(), any())).thenReturn(vector());
        when(client.queryRange(any(), any(), any(), any()))
                .thenReturn(new PrometheusResult(PrometheusResult.ResultType.MATRIX,
                        List.of(new PrometheusResult.Series(Map.of("gameName", "BauCua"),
                                List.of(new PrometheusResult.Sample(1700000000L, 1.0),
                                        new PrometheusResult.Sample(1700000060L, null))))));

        MetricsTimeseriesDTO dto = service.timeseries(MetricScope.ENVIRONMENT, "e1",
                MetricKey.RTP_PER_GAME,
                Instant.ofEpochSecond(1700000000L), Instant.ofEpochSecond(1700000060L),
                Duration.ofSeconds(60));

        assertThat(dto.getSeries()).hasSize(1);
        assertThat(dto.getSeries().get(0).getPoints().get(1).getValue()).isNull();
    }

    @Test
    @DisplayName("summary resolves $__range to the summary window and never dispatches the raw token (AD-6)")
    void summaryResolvesRangeTokenToSummaryWindow() {
        when(client.queryInstant(any(), any())).thenReturn(vector());

        service.summary(MetricScope.GAME, "g1");

        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).queryInstant(queries.capture(), any());
        assertThat(queries.getAllValues())
                .as("no dispatched instant query may contain the raw $__range token")
                .noneMatch(q -> q.contains("$__range"))
                .anyMatch(q -> q.equals(dispatchedRtpSummary()));
    }

    @Test
    @DisplayName("summary auto-includes the renamed rtp and new money_drain_per_day scalar keys, dropping the old _5m keys (AD-7/AD-8)")
    void summaryIncludesRenamedAndNewScalarKeys() {
        when(client.queryInstant(any(), any())).thenReturn(vector(scalar(0.9)));

        MetricsSummaryDTO game = service.summary(MetricScope.GAME, "g1");
        assertThat(game.getMetrics())
                .as("renamed RTP key surfaces under its new name on GAME")
                .containsKey("rtp")
                .as("new drain key auto-included on GAME (scalar, both scopes)")
                .containsKey("money_drain_per_day")
                .as("old _5m RTP keys are gone (no back-compat alias)")
                .doesNotContainKey("rtp_5m")
                .as("multi-series rtp_per_game is excluded from the scalar summary map")
                .doesNotContainKey("rtp_per_game");

        MetricsSummaryDTO env = service.summary(MetricScope.ENVIRONMENT, "e1");
        assertThat(env.getMetrics())
                .containsKey("rtp")
                .containsKey("money_drain_per_day")
                .doesNotContainKey("rtp_5m")
                .doesNotContainKey("rtp_per_game_5m");
    }

    @Test
    @DisplayName("a non-RTP scalar key carries no $__range token and is dispatched byte-for-byte unchanged")
    void nonRtpKeyDispatchedUnchanged() {
        when(client.queryInstant(any(), any())).thenReturn(vector());

        service.summary(MetricScope.GAME, "g1");

        String deadRaw = MetricKey.DEAD_SECONDS_1H.promql(MetricScope.GAME, "g1");
        assertThat(deadRaw)
                .as("non-RTP templates carry no Grafana range token")
                .doesNotContain("$__range");

        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).queryInstant(queries.capture(), any());
        assertThat(queries.getAllValues())
                .as("non-RTP key passes through applyWindow() untouched")
                .contains(deadRaw);
    }

    @Test
    @DisplayName("timeseries resolves $__range to the timeseries window and never dispatches the raw token (AD-6)")
    void timeseriesResolvesRangeTokenToTimeseriesWindow() {
        when(client.queryInstant(any(), any())).thenReturn(vector());
        when(client.queryRange(any(), any(), any(), any()))
                .thenReturn(new PrometheusResult(PrometheusResult.ResultType.MATRIX, List.of()));

        service.timeseries(MetricScope.GAME, "g1", MetricKey.RTP,
                Instant.ofEpochSecond(1700000000L), Instant.ofEpochSecond(1700003600L),
                Duration.ofSeconds(300));

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(client).queryRange(query.capture(), any(), any(), any());
        assertThat(query.getValue())
                .doesNotContain("$__range")
                .isEqualTo(MetricKey.RTP.promql(MetricScope.GAME, "g1")
                        .replace("$__range", TIMESERIES_WINDOW));
    }
}
