package com.vingame.bot.domain.metrics.ratelimit;

import com.vingame.bot.common.exception.RestExceptionHandler;
import com.vingame.bot.domain.metrics.MetricScope;
import com.vingame.bot.domain.metrics.controller.MetricsController;
import com.vingame.bot.domain.metrics.dto.MetricsSummaryDTO;
import com.vingame.bot.domain.metrics.service.MetricsQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the metrics rate-limit guard (METRICS_API Phase 5 / AD-9): once a
 * client exhausts its per-minute token bucket, further requests return HTTP 429.
 * Wires {@link MetricsController} through standalone MockMvc with the
 * {@link MetricsRateLimitInterceptor} scoped to {@code /api/v1/metrics/**}, using
 * a small limit so the overflow is reached quickly.
 */
@DisplayName("MetricsRateLimitInterceptor")
class MetricsRateLimitInterceptorTest {

    private static final int LIMIT = 5;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MetricsQueryService service = Mockito.mock(MetricsQueryService.class);
        when(service.summary(any(MetricScope.class), anyString()))
                .thenReturn(MetricsSummaryDTO.builder()
                        .scope("GAME").scopeId("g1").scopeName("BauCua")
                        .metrics(Map.of()).botsByStatus(Map.of())
                        .generatedAt(Instant.now()).build());

        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(LIMIT);

        mockMvc = MockMvcBuilders.standaloneSetup(new MetricsController(service))
                .setControllerAdvice(new RestExceptionHandler())
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    @DisplayName("requests within the limit pass; the overflow request gets 429")
    void overflowReturns429() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            mockMvc.perform(get("/api/v1/metrics/game/g1/summary"))
                    .andExpect(status().isOk());
        }
        // The (LIMIT+1)th immediate request exhausts the bucket → 429.
        mockMvc.perform(get("/api/v1/metrics/game/g1/summary"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("distinct client IPs have independent buckets")
    void distinctClientsAreIndependent() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            mockMvc.perform(get("/api/v1/metrics/game/g1/summary").with(r -> { r.setRemoteAddr("10.0.0.1"); return r; }))
                    .andExpect(status().isOk());
        }
        // Same instant, different IP → fresh bucket, still allowed.
        mockMvc.perform(get("/api/v1/metrics/game/g1/summary").with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }))
                .andExpect(status().isOk());
    }
}
