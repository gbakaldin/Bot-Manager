package com.vingame.bot.domain.metrics.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-added direct unit coverage for {@link MetricsRateLimitInterceptor} that the
 * dev's MockMvc test does not reach: token-bucket refill over real time,
 * {@code X-Forwarded-For} client-key extraction (single + multi-IP), the burst
 * capacity equalling the configured per-minute limit, the 429 body, and bucket
 * isolation by forwarded IP. These exercise {@code preHandle} against
 * {@link MockHttpServletRequest}/{@link MockHttpServletResponse} so the bucket
 * arithmetic is observed without HTTP plumbing.
 */
@DisplayName("MetricsRateLimitInterceptor (unit)")
class MetricsRateLimitInterceptorUnitTest {

    private static MockHttpServletRequest req(String remoteAddr, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/metrics/game/g1/summary");
        if (remoteAddr != null) {
            r.setRemoteAddr(remoteAddr);
        }
        if (xff != null) {
            r.addHeader("X-Forwarded-For", xff);
        }
        return r;
    }

    private static boolean call(MetricsRateLimitInterceptor i, MockHttpServletRequest r) {
        return i.preHandle(r, new MockHttpServletResponse(), new Object());
    }

    @Test
    @DisplayName("burst capacity equals the per-minute limit; the next call is rejected")
    void burstCapacityEqualsLimit() {
        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(10);
        MockHttpServletRequest r = req("10.0.0.1", null);

        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (call(interceptor, r)) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(10);
        assertThat(call(interceptor, r)).isFalse(); // bucket empty
    }

    @Test
    @DisplayName("429 status and JSON body are written on rejection")
    void rejectionWrites429AndBody() throws Exception {
        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(1);
        MockHttpServletRequest r = req("10.0.0.1", null);

        assertThat(call(interceptor, r)).isTrue();

        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean ok = interceptor.preHandle(r, resp, new Object());

        assertThat(ok).isFalse();
        assertThat(resp.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(resp.getContentType()).contains("application/json");
        assertThat(resp.getContentAsString()).contains("rate limit");
    }

    @Test
    @DisplayName("tokens refill over time: after waiting one refill interval a rejected client is allowed again")
    void refillOverTime() throws InterruptedException {
        // 60/min → 1 token/sec; a single token refills in ~1s.
        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(60);
        MockHttpServletRequest r = req("10.0.0.1", null);

        // Drain the full burst.
        for (int i = 0; i < 60; i++) {
            call(interceptor, r);
        }
        assertThat(call(interceptor, r)).isFalse();

        // Wait for at least one token to refill.
        Thread.sleep(1100);
        assertThat(call(interceptor, r)).isTrue();
    }

    @Test
    @DisplayName("X-Forwarded-For with a single IP keys the bucket by that IP, not remoteAddr")
    void forwardedForSingleIp() {
        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(2);

        // Two requests via the same forwarded IP but different remoteAddr → same bucket.
        assertThat(call(interceptor, req("10.0.0.1", "203.0.113.7"))).isTrue();
        assertThat(call(interceptor, req("10.0.0.2", "203.0.113.7"))).isTrue();
        // Third on the same forwarded IP → bucket drained regardless of remoteAddr.
        assertThat(call(interceptor, req("10.0.0.3", "203.0.113.7"))).isFalse();
    }

    @Test
    @DisplayName("X-Forwarded-For with multiple IPs keys on the first (original client) IP")
    void forwardedForMultipleIps() {
        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(2);

        // "client, proxy1, proxy2" → key on the leftmost client IP.
        assertThat(call(interceptor, req("10.0.0.1", "198.51.100.5, 203.0.113.1, 203.0.113.2"))).isTrue();
        assertThat(call(interceptor, req("10.0.0.9", "198.51.100.5, 10.10.10.10"))).isTrue();
        assertThat(call(interceptor, req("10.0.0.9", "198.51.100.5"))).isFalse(); // same leftmost IP, drained

        // A different leftmost client IP gets its own fresh bucket.
        assertThat(call(interceptor, req("10.0.0.9", "198.51.100.6, 203.0.113.1"))).isTrue();
    }

    @Test
    @DisplayName("distinct forwarded client IPs maintain independent buckets")
    void distinctForwardedIpsAreIndependent() {
        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(1);

        assertThat(call(interceptor, req("10.0.0.1", "1.1.1.1"))).isTrue();
        assertThat(call(interceptor, req("10.0.0.1", "1.1.1.1"))).isFalse();
        // Different forwarded IP → fresh bucket.
        assertThat(call(interceptor, req("10.0.0.1", "2.2.2.2"))).isTrue();
    }

    @Test
    @DisplayName("blank X-Forwarded-For falls back to remoteAddr")
    void blankForwardedFallsBackToRemoteAddr() {
        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(1);

        assertThat(call(interceptor, req("10.0.0.1", "   "))).isTrue();
        // Same remoteAddr (blank XFF ignored) → drained.
        assertThat(call(interceptor, req("10.0.0.1", "   "))).isFalse();
        // Different remoteAddr → fresh bucket.
        assertThat(call(interceptor, req("10.0.0.2", null))).isTrue();
    }

    @Test
    @DisplayName("a per-minute limit of 0 is clamped to a usable bucket of at least 1")
    void zeroLimitClampedToOne() {
        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(0);
        MockHttpServletRequest r = req("10.0.0.1", null);

        assertThat(call(interceptor, r)).isTrue(); // capacity floored to 1
        assertThat(call(interceptor, r)).isFalse();
    }
}
