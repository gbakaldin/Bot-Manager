package com.vingame.bot.domain.metrics.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client token-bucket rate limiter for the public metrics endpoints
 * (METRICS_API AD-9 / Phase 5). The metrics API mirrors the existing public,
 * unauthenticated {@code /bot-group/{id}/health} (OI-3) but hits Prometheus, so a
 * rate-limit guard is the v1 hardening: combined with the closed metric-key enum,
 * the 30d range cap, and the 5s result cache, it keeps a public endpoint from
 * being used to hammer Prometheus.
 * <p>
 * Dependency-free (AD-9): a small {@link ConcurrentHashMap} of per-IP token
 * buckets, refilled continuously at {@code requests-per-minute / 60} tokens/sec
 * up to a burst capacity equal to the per-minute limit. When a bucket is empty
 * the request is rejected with HTTP 429. Buckets are created lazily and the map's
 * cardinality is bounded by the number of distinct client IPs.
 */
@Slf4j
@Component
public class MetricsRateLimitInterceptor implements HandlerInterceptor {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long capacity;
    private final double refillPerNano;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public MetricsRateLimitInterceptor(
            @Value("${metrics.ratelimit.requests-per-minute:120}") long requestsPerMinute) {
        this.capacity = Math.max(1, requestsPerMinute);
        this.refillPerNano = (double) this.capacity / (60.0 * NANOS_PER_SECOND);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String client = clientKey(request);
        Bucket bucket = buckets.computeIfAbsent(client, k -> new Bucket(capacity, System.nanoTime()));

        if (bucket.tryConsume(refillPerNano, capacity)) {
            return true;
        }

        log.warn("Metrics rate limit exceeded for client {} on {}", client, request.getRequestURI());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        try {
            response.getWriter().write(
                    "{\"type\":\"Too many requests\",\"msg\":\"Metrics rate limit exceeded. Retry later.\"}");
        } catch (java.io.IOException ignored) {
            // Body is best-effort; the 429 status is the contract.
        }
        return false;
    }

    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String remote = request.getRemoteAddr();
        return remote != null ? remote : "unknown";
    }

    /**
     * Token bucket guarded by its own monitor. Per-IP contention is negligible
     * (a single client's requests are inherently low-rate), so a {@code
     * synchronized} consume is simpler and provably correct versus a CAS loop —
     * the map itself is concurrent, only the per-bucket arithmetic is serialized.
     * Tokens are kept as a {@code double} for exact continuous refill.
     */
    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(long initialTokens, long nowNanos) {
            this.tokens = initialTokens;
            this.lastRefillNanos = nowNanos;
        }

        synchronized boolean tryConsume(double refillPerNano, long capacity) {
            long now = System.nanoTime();
            long elapsed = Math.max(0, now - lastRefillNanos);
            lastRefillNanos = now;
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
