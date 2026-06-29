package com.vingame.bot.domain.metrics.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit coverage for {@link MetricsRateLimitInterceptor}: token-bucket
 * refill over real time, the burst capacity equalling the configured per-minute
 * limit, the 429 body, and — post-review — that buckets are keyed on the real TCP
 * peer ({@code getRemoteAddr()}) and <b>not</b> on the client-controlled
 * {@code X-Forwarded-For} header (security: spoof bypass + memory-DoS), and that
 * the bucket map stays bounded under a flood of distinct peers. These exercise
 * {@code preHandle} against {@link MockHttpServletRequest}/{@link MockHttpServletResponse}
 * so the bucket arithmetic is observed without HTTP plumbing.
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

    // ---- keying: real peer (getRemoteAddr), never the spoofable X-Forwarded-For ----

    @Test
    @DisplayName("buckets key on remoteAddr — X-Forwarded-For is ignored, so rotating it does not bypass the limit")
    void xffRotationDoesNotBypassLimit() {
        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(2);

        // Same real peer, attacker rotating XFF on every request → still one bucket.
        assertThat(call(interceptor, req("10.0.0.1", "1.1.1.1"))).isTrue();
        assertThat(call(interceptor, req("10.0.0.1", "2.2.2.2"))).isTrue();
        // Bucket drained despite a fresh forwarded value — XFF is not trusted.
        assertThat(call(interceptor, req("10.0.0.1", "3.3.3.3"))).isFalse();
    }

    @Test
    @DisplayName("distinct remoteAddr peers maintain independent buckets")
    void distinctRemoteAddrsAreIndependent() {
        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(1);

        assertThat(call(interceptor, req("10.0.0.1", null))).isTrue();
        assertThat(call(interceptor, req("10.0.0.1", null))).isFalse();
        // Different peer → fresh bucket.
        assertThat(call(interceptor, req("10.0.0.2", null))).isTrue();
    }

    @Test
    @DisplayName("a null remoteAddr falls back to a stable 'unknown' key")
    void nullRemoteAddrFallsBack() {
        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(1);

        assertThat(call(interceptor, req(null, "1.1.1.1"))).isTrue();
        // Same fallback key (XFF ignored) → drained.
        assertThat(call(interceptor, req(null, "2.2.2.2"))).isFalse();
    }

    @Test
    @DisplayName("a per-minute limit of 0 is clamped to a usable bucket of at least 1")
    void zeroLimitClampedToOne() {
        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(0);
        MockHttpServletRequest r = req("10.0.0.1", null);

        assertThat(call(interceptor, r)).isTrue(); // capacity floored to 1
        assertThat(call(interceptor, r)).isFalse();
    }

    // ---- bounded map: a flood of distinct peers cannot grow the map unboundedly ----

    @Test
    @DisplayName("the bucket map stays bounded under a flood of distinct full-bucket peers")
    void bucketMapStaysBounded() throws Exception {
        // capacity 1 → each peer makes one allowed call, immediately leaving a
        // drained bucket. Let those buckets refill to full, then flood with new
        // peers: the sweep evicts the refilled (full) buckets at the cap.
        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(600); // 10 tokens/sec → fast refill

        // Far more distinct peers than MAX_BUCKETS, each touched once then left to refill.
        for (int i = 0; i < 25_000; i++) {
            call(interceptor, req("10." + (i >> 16 & 0xFF) + "." + (i >> 8 & 0xFF) + "." + (i & 0xFF), null));
        }

        int size = bucketMapSize(interceptor);
        // MAX_BUCKETS is 10_000; the map must never exceed it after sweeps fire.
        assertThat(size).isLessThanOrEqualTo(10_000);
    }

    @Test
    @DisplayName("429 still fires on exhaustion even after the map has been swept")
    void limitStillEnforcedAfterSweep() {
        MetricsRateLimitInterceptor interceptor = new MetricsRateLimitInterceptor(600);

        // Churn many distinct peers to trigger sweeps.
        for (int i = 0; i < 25_000; i++) {
            call(interceptor, req("172.16." + (i >> 8 & 0xFF) + "." + (i & 0xFF), null));
        }

        // A specific peer's limit is still enforced: drain its burst then expect 429.
        MockHttpServletRequest r = req("192.168.0.1", null);
        int allowed = 0;
        for (int i = 0; i < 600; i++) {
            if (call(interceptor, r)) {
                allowed++;
            }
        }
        assertThat(allowed).isGreaterThan(0);
        assertThat(call(interceptor, r)).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static int bucketMapSize(MetricsRateLimitInterceptor interceptor) throws Exception {
        java.lang.reflect.Field f = MetricsRateLimitInterceptor.class.getDeclaredField("buckets");
        f.setAccessible(true);
        return ((java.util.Map<String, ?>) f.get(interceptor)).size();
    }
}
