package com.vingame.bot.domain.metrics.controller;

import com.vingame.bot.common.exception.RestExceptionHandler;
import com.vingame.bot.common.exception.UpstreamPrometheusException;
import com.vingame.bot.domain.metrics.MetricKey;
import com.vingame.bot.domain.metrics.MetricScope;
import com.vingame.bot.domain.metrics.dto.MetricPointDTO;
import com.vingame.bot.domain.metrics.dto.MetricSeriesDTO;
import com.vingame.bot.domain.metrics.dto.MetricsSummaryDTO;
import com.vingame.bot.domain.metrics.dto.MetricsTimeseriesDTO;
import com.vingame.bot.domain.metrics.service.MetricsQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc coverage for {@link MetricsController} (METRICS_API Phase 4): happy
 * path (game+env, summary+timeseries), {@code range} presets and raw
 * {@code from}/{@code to}/{@code step}, and the AD-5 validation failures
 * (unknown metric, cross-scope key, range &gt; 30d, bad step, range+from
 * conflict). The service is mocked — no Prometheus.
 */
@WebMvcTest(MetricsController.class)
@Import(RestExceptionHandler.class)
@DisplayName("MetricsController")
class MetricsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MetricsQueryService service;

    private static MetricsSummaryDTO sampleSummary(MetricScope scope, String id, String name) {
        return MetricsSummaryDTO.builder()
                .scope(scope.name())
                .scopeId(id)
                .scopeName(name)
                .metrics(Map.of("total_bots", 12.0, "rtp", 0.95))
                .botsByStatus(Map.of("CONNECTION_AUTHENTICATED", 10.0))
                .generatedAt(Instant.now())
                .build();
    }

    private static MetricsTimeseriesDTO sampleTimeseries(MetricScope scope, String id, MetricKey key) {
        MetricSeriesDTO series = MetricSeriesDTO.builder()
                .labels(Map.of())
                .points(List.of(MetricPointDTO.builder().timestamp(1700000000L).value(1.5).build()))
                .build();
        return MetricsTimeseriesDTO.builder()
                .scope(scope.name())
                .scopeId(id)
                .scopeName("Name")
                .metric(key.key())
                .from(1700000000L)
                .to(1700003600L)
                .step(60)
                .series(List.of(series))
                .generatedAt(Instant.now())
                .build();
    }

    @Nested
    @DisplayName("summary")
    class Summary {

        @Test
        @DisplayName("GET /game/{id}/summary → 200 with populated DTO")
        void gameSummary() throws Exception {
            when(service.summary(MetricScope.GAME, "g1"))
                    .thenReturn(sampleSummary(MetricScope.GAME, "g1", "BauCua"));

            mockMvc.perform(get("/api/v1/metrics/game/g1/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scope").value("GAME"))
                    .andExpect(jsonPath("$.scopeId").value("g1"))
                    .andExpect(jsonPath("$.scopeName").value("BauCua"))
                    .andExpect(jsonPath("$.metrics.total_bots").value(12.0))
                    .andExpect(jsonPath("$.botsByStatus.CONNECTION_AUTHENTICATED").value(10.0));
        }

        @Test
        @DisplayName("GET /environment/{id}/summary → 200 with populated DTO")
        void environmentSummary() throws Exception {
            when(service.summary(MetricScope.ENVIRONMENT, "e1"))
                    .thenReturn(sampleSummary(MetricScope.ENVIRONMENT, "e1", "Prod"));

            mockMvc.perform(get("/api/v1/metrics/environment/e1/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scope").value("ENVIRONMENT"))
                    .andExpect(jsonPath("$.scopeName").value("Prod"));
        }
    }

    @Nested
    @DisplayName("timeseries — happy path")
    class TimeseriesHappy {

        @Test
        @DisplayName("game timeseries with raw from/to/step → 200")
        void gameTimeseriesRaw() throws Exception {
            when(service.timeseries(eq(MetricScope.GAME), eq("g1"), eq(MetricKey.WINNINGS_RATE_5M),
                    any(), any(), any()))
                    .thenReturn(sampleTimeseries(MetricScope.GAME, "g1", MetricKey.WINNINGS_RATE_5M));

            mockMvc.perform(get("/api/v1/metrics/game/g1/timeseries")
                            .param("metric", "winnings_rate_5m")
                            .param("from", "1700000000")
                            .param("to", "1700003600")
                            .param("step", "60"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.metric").value("winnings_rate_5m"))
                    .andExpect(jsonPath("$.series[0].points[0].value").value(1.5));
        }

        @Test
        @DisplayName("game timeseries with range preset → 200 and resolves window server-side")
        void gameTimeseriesRangePreset() throws Exception {
            when(service.timeseries(eq(MetricScope.GAME), eq("g1"), eq(MetricKey.BETS_PLACED_RATE_1M),
                    any(), any(), any()))
                    .thenReturn(sampleTimeseries(MetricScope.GAME, "g1", MetricKey.BETS_PLACED_RATE_1M));

            mockMvc.perform(get("/api/v1/metrics/game/g1/timeseries")
                            .param("metric", "bets_placed_rate_1m")
                            .param("range", "24h"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Duration> step = ArgumentCaptor.forClass(Duration.class);
            verify(service).timeseries(eq(MetricScope.GAME), eq("g1"), eq(MetricKey.BETS_PLACED_RATE_1M),
                    start.capture(), end.capture(), step.capture());

            Duration span = Duration.between(start.getValue(), end.getValue());
            assertThat(span.toHours()).isEqualTo(24);
            // ~720-point default: 24h / 720 = 120s.
            assertThat(step.getValue()).isEqualTo(Duration.ofSeconds(120));
        }

        @Test
        @DisplayName("environment-only metric on environment timeseries → 200")
        void environmentTimeseriesEnvOnlyMetric() throws Exception {
            when(service.timeseries(eq(MetricScope.ENVIRONMENT), eq("e1"), eq(MetricKey.RECONNECT_RATE_5M),
                    any(), any(), any()))
                    .thenReturn(sampleTimeseries(MetricScope.ENVIRONMENT, "e1", MetricKey.RECONNECT_RATE_5M));

            mockMvc.perform(get("/api/v1/metrics/environment/e1/timeseries")
                            .param("metric", "reconnect_rate_5m")
                            .param("range", "1h"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.metric").value("reconnect_rate_5m"));
        }

        @Test
        @DisplayName("timeseries with no time params defaults to a 1h window")
        void timeseriesDefaultWindow() throws Exception {
            when(service.timeseries(eq(MetricScope.GAME), eq("g1"), eq(MetricKey.WINNINGS_RATE_5M),
                    any(), any(), any()))
                    .thenReturn(sampleTimeseries(MetricScope.GAME, "g1", MetricKey.WINNINGS_RATE_5M));

            mockMvc.perform(get("/api/v1/metrics/game/g1/timeseries")
                            .param("metric", "winnings_rate_5m"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
            verify(service).timeseries(eq(MetricScope.GAME), eq("g1"), eq(MetricKey.WINNINGS_RATE_5M),
                    start.capture(), end.capture(), any());
            assertThat(Duration.between(start.getValue(), end.getValue()).toHours()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("timeseries — validation (400)")
    class TimeseriesValidation {

        @Test
        @DisplayName("unknown metric → 400")
        void unknownMetric() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/game/g1/timeseries")
                            .param("metric", "not_a_real_metric"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("GAME-only metric on environment endpoint → 400")
        void gameOnlyKeyOnEnvironment() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/environment/e1/timeseries")
                            .param("metric", "jackpots_rate_5m"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("ENV-only metric on game endpoint → 400")
        void envOnlyKeyOnGame() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/game/g1/timeseries")
                            .param("metric", "reconnect_rate_5m"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("range > 30d → 400")
        void rangeOverCap() throws Exception {
            long now = Instant.now().getEpochSecond();
            long from = now - Duration.ofDays(35).toSeconds();
            mockMvc.perform(get("/api/v1/metrics/game/g1/timeseries")
                            .param("metric", "winnings_rate_5m")
                            .param("from", String.valueOf(from))
                            .param("to", String.valueOf(now))
                            .param("step", "3600"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("step below the 5s floor → 400")
        void stepBelowFloor() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/game/g1/timeseries")
                            .param("metric", "winnings_rate_5m")
                            .param("range", "1h")
                            .param("step", "1"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("point-count overflow (tiny step over a big window) → 400")
        void pointCountOverflow() throws Exception {
            long now = Instant.now().getEpochSecond();
            long from = now - Duration.ofDays(20).toSeconds();
            mockMvc.perform(get("/api/v1/metrics/game/g1/timeseries")
                            .param("metric", "winnings_rate_5m")
                            .param("from", String.valueOf(from))
                            .param("to", String.valueOf(now))
                            .param("step", "5"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("range preset AND explicit from → 400 (mutually exclusive)")
        void rangeAndExplicitConflict() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/game/g1/timeseries")
                            .param("metric", "winnings_rate_5m")
                            .param("range", "1h")
                            .param("from", "1700000000"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("unknown range preset → 400")
        void unknownRangePreset() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/game/g1/timeseries")
                            .param("metric", "winnings_rate_5m")
                            .param("range", "13h"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("to <= from → 400")
        void invertedWindow() throws Exception {
            mockMvc.perform(get("/api/v1/metrics/game/g1/timeseries")
                            .param("metric", "winnings_rate_5m")
                            .param("from", "1700003600")
                            .param("to", "1700000000")
                            .param("step", "60"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("upstream errors")
    class Upstream {

        @Test
        @DisplayName("Prometheus failure → 502")
        void prometheusFailure() throws Exception {
            when(service.summary(MetricScope.GAME, "g1"))
                    .thenThrow(new UpstreamPrometheusException("Prometheus down"));

            mockMvc.perform(get("/api/v1/metrics/game/g1/summary"))
                    .andExpect(status().isBadGateway());
        }
    }
}
