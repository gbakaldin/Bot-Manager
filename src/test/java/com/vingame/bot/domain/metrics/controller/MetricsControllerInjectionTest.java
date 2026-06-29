package com.vingame.bot.domain.metrics.controller;

import com.vingame.bot.common.exception.RestExceptionHandler;
import com.vingame.bot.domain.metrics.service.MetricsQueryService;
import com.vingame.bot.infrastructure.client.prometheus.PrometheusQueryClient;
import com.vingame.bot.infrastructure.client.prometheus.PrometheusResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end injection coverage for the scope-id trust boundary (METRICS_API
 * review fix): the controller is wired to the <b>real</b> {@link MetricsQueryService}
 * over a mocked {@link PrometheusQueryClient}, so a malicious path-variable id
 * actually reaches {@link com.vingame.bot.domain.metrics.MetricScope#selector} and
 * must be rejected with 400 — never reaching Prometheus. A normal id passes through
 * to the client. The standalone-MockMvc {@code MetricsControllerTest} mocks the
 * service and so cannot exercise this chain.
 */
@DisplayName("MetricsController — PromQL injection guard")
class MetricsControllerInjectionTest {

    private final PrometheusQueryClient client = mock(PrometheusQueryClient.class);
    private final MetricsQueryService service = new MetricsQueryService(client);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new MetricsController(service))
            .setControllerAdvice(new RestExceptionHandler())
            .build();

    private static PrometheusResult emptyVector() {
        return new PrometheusResult(PrometheusResult.ResultType.VECTOR, List.of());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("game summary id containing a PromQL breakout → 400, Prometheus never called")
    void gameSummaryInjectionRejected() throws Exception {
        String malicious = "x\"} or bot_winnings_total{foo=\"bar";
        mockMvc.perform(get("/api/v1/metrics/game/" + enc(malicious) + "/summary"))
                .andExpect(status().isBadRequest());

        verify(client, never()).queryInstant(any(), any());
        verify(client, never()).queryRange(any(), any(), any(), any());
    }

    @Test
    @DisplayName("environment summary id with a trailing quote → 400")
    void environmentSummaryInjectionRejected() throws Exception {
        mockMvc.perform(get("/api/v1/metrics/environment/" + enc("e1\"") + "/summary"))
                .andExpect(status().isBadRequest());
        verify(client, never()).queryInstant(any(), any());
    }

    @Test
    @DisplayName("game timeseries id with a brace injection → 400")
    void gameTimeseriesInjectionRejected() throws Exception {
        mockMvc.perform(get("/api/v1/metrics/game/" + enc("g1{foo}") + "/timeseries")
                        .param("metric", "winnings_rate_5m")
                        .param("range", "1h"))
                .andExpect(status().isBadRequest());
        verify(client, never()).queryRange(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a normal UUID id passes validation and reaches the client")
    void validUuidPasses() throws Exception {
        when(client.queryInstant(any(), any())).thenReturn(emptyVector());

        mockMvc.perform(get("/api/v1/metrics/game/0c9a93cb-20d6-4f57-9dbc-5c315dcf52e2/summary"))
                .andExpect(status().isOk());

        verify(client, org.mockito.Mockito.atLeastOnce()).queryInstant(any(), any());
    }
}
