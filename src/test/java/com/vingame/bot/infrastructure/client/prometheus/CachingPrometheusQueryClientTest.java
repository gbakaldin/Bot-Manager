package com.vingame.bot.infrastructure.client.prometheus;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the short-TTL cache (METRICS_API Phase 5 / AD-8): identical queries
 * within the TTL hit the delegate exactly once; a query after expiry re-hits; a
 * differing query key is not deduped; ttl=0 disables caching. The delegate is a
 * concrete {@link HttpPrometheusQueryClient} mock — call counts prove caching.
 */
class CachingPrometheusQueryClientTest {

    private static PrometheusResult someResult() {
        return new PrometheusResult(PrometheusResult.ResultType.VECTOR,
                List.of(new PrometheusResult.Series(Map.of(),
                        List.of(new PrometheusResult.Sample(1700000000L, 1.0)))));
    }

    @Test
    void identicalInstantQueriesWithinTtlHitDelegateOnce() {
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryInstant(eq("up"), any())).thenReturn(someResult());
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 5);

        Instant t = Instant.ofEpochSecond(1700000000L);
        cache.queryInstant("up", t);
        cache.queryInstant("up", t);
        cache.queryInstant("up", t);

        verify(delegate, times(1)).queryInstant(eq("up"), any());
    }

    @Test
    void identicalRangeQueriesWithinTtlHitDelegateOnce() {
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryRange(eq("rate(x[5m])"), any(), any(), any())).thenReturn(someResult());
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 5);

        Instant start = Instant.ofEpochSecond(1700000000L);
        Instant end = Instant.ofEpochSecond(1700003600L);
        Duration step = Duration.ofSeconds(60);

        cache.queryRange("rate(x[5m])", start, end, step);
        cache.queryRange("rate(x[5m])", start, end, step);

        verify(delegate, times(1)).queryRange(eq("rate(x[5m])"), any(), any(), any());
    }

    @Test
    void differentQueryKeysAreNotDeduped() {
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryInstant(any(), any())).thenReturn(someResult());
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 5);

        Instant t = Instant.ofEpochSecond(1700000000L);
        cache.queryInstant("up", t);
        cache.queryInstant("down", t);

        verify(delegate, times(1)).queryInstant(eq("up"), any());
        verify(delegate, times(1)).queryInstant(eq("down"), any());
    }

    @Test
    void queryAfterTtlExpiryReHitsDelegate() throws InterruptedException {
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryInstant(eq("up"), any())).thenReturn(someResult());
        // 1s TTL keeps the test fast while still exercising real expiry.
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 1);

        Instant t = Instant.ofEpochSecond(1700000000L);
        cache.queryInstant("up", t);
        Thread.sleep(1100);
        cache.queryInstant("up", t);

        verify(delegate, times(2)).queryInstant(eq("up"), any());
    }

    @Test
    void ttlZeroDisablesCaching() {
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryInstant(eq("up"), any())).thenReturn(someResult());
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 0);

        Instant t = Instant.ofEpochSecond(1700000000L);
        cache.queryInstant("up", t);
        cache.queryInstant("up", t);

        verify(delegate, times(2)).queryInstant(eq("up"), any());
        assertThat(cache.snapshot()).isEmpty();
    }

    // ---- cache-key correctness: the key must include the full time window ----

    @Test
    void rangeQueriesDifferingOnlyInStartAreNotDeduped() {
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryRange(any(), any(), any(), any())).thenReturn(someResult());
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 60);

        Instant end = Instant.ofEpochSecond(1700003600L);
        Duration step = Duration.ofSeconds(60);
        // Same query + end + step, different start → a different logical window.
        cache.queryRange("rate(x[5m])", Instant.ofEpochSecond(1700000000L), end, step);
        cache.queryRange("rate(x[5m])", Instant.ofEpochSecond(1700001000L), end, step);

        // A stale cached result must NOT be served for a different range.
        verify(delegate, times(2)).queryRange(eq("rate(x[5m])"), any(), any(), any());
    }

    @Test
    void rangeQueriesDifferingOnlyInEndAreNotDeduped() {
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryRange(any(), any(), any(), any())).thenReturn(someResult());
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 60);

        Instant start = Instant.ofEpochSecond(1700000000L);
        Duration step = Duration.ofSeconds(60);
        cache.queryRange("rate(x[5m])", start, Instant.ofEpochSecond(1700003600L), step);
        cache.queryRange("rate(x[5m])", start, Instant.ofEpochSecond(1700007200L), step);

        verify(delegate, times(2)).queryRange(eq("rate(x[5m])"), any(), any(), any());
    }

    @Test
    void rangeQueriesDifferingOnlyInStepAreNotDeduped() {
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryRange(any(), any(), any(), any())).thenReturn(someResult());
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 60);

        Instant start = Instant.ofEpochSecond(1700000000L);
        Instant end = Instant.ofEpochSecond(1700003600L);
        cache.queryRange("rate(x[5m])", start, end, Duration.ofSeconds(60));
        cache.queryRange("rate(x[5m])", start, end, Duration.ofSeconds(120));

        verify(delegate, times(2)).queryRange(eq("rate(x[5m])"), any(), any(), any());
    }

    @Test
    void instantQueriesAtDistinctHistoricalTimesAreNotDeduped() {
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryInstant(eq("up"), any())).thenReturn(someResult());
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 60);

        cache.queryInstant("up", Instant.ofEpochSecond(1700000000L));
        cache.queryInstant("up", Instant.ofEpochSecond(1700000001L));

        verify(delegate, times(2)).queryInstant(eq("up"), any());
    }

    @Test
    void instantAndRangeNamespacesDoNotCollide() {
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryInstant(any(), any())).thenReturn(someResult());
        when(delegate.queryRange(any(), any(), any(), any())).thenReturn(someResult());
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 60);

        Instant t = Instant.ofEpochSecond(1700000000L);
        // Same query string + same start time as the instant: must not collide.
        cache.queryInstant("q", t);
        cache.queryRange("q", t, Instant.ofEpochSecond(1700003600L), Duration.ofSeconds(60));

        verify(delegate, times(1)).queryInstant(eq("q"), any());
        verify(delegate, times(1)).queryRange(eq("q"), any(), any(), any());
    }

    // ---- live "now" instant dedup (review fix): null time keys time-independently ----

    @Test
    void liveNowInstantQueriesWithinTtlDedupeToSingleDelegateCall() {
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryInstant(eq("up"), any())).thenReturn(someResult());
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 5);

        // Two live "now" polls (caller passes null) a "second" apart must hit the
        // cache once — the bug was folding getEpochSecond() into the key.
        cache.queryInstant("up", null);
        cache.queryInstant("up", null);
        cache.queryInstant("up", null);

        verify(delegate, times(1)).queryInstant(eq("up"), any());
    }

    @Test
    void liveNowAndExplicitInstantDoNotCollide() {
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryInstant(eq("up"), any())).thenReturn(someResult());
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 60);

        cache.queryInstant("up", null);                                  // live now
        cache.queryInstant("up", Instant.ofEpochSecond(1700000000L));    // historical

        verify(delegate, times(2)).queryInstant(eq("up"), any());
    }

    // ---- delimiter-free key: no collision across field boundaries ----

    @Test
    void instantKeysDoNotCollideAcrossPromqlBoundary() {
        // With a concatenated string key, promql "a" + (time)"b" could collide with
        // promql "ab". The composite-record key cannot — distinct components, distinct key.
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryInstant(any(), any())).thenReturn(someResult());
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 60);

        // "a" at epoch 0 vs "a0" at "now" — must be two distinct cache entries.
        cache.queryInstant("a", Instant.ofEpochSecond(0L));
        cache.queryInstant("a0", null);

        verify(delegate, times(1)).queryInstant(eq("a"), any());
        verify(delegate, times(1)).queryInstant(eq("a0"), any());
        assertThat(cache.snapshot()).hasSize(2);
    }

    @Test
    void rangeKeysDoNotCollideAcrossFieldBoundaries() {
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryRange(any(), any(), any(), any())).thenReturn(someResult());
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 60);

        // promql "x" / start 1 / end 23 vs promql "x" / start 12 / end 3 — a
        // delimiter-free concatenation could alias; the record key cannot.
        cache.queryRange("x", Instant.ofEpochSecond(1L), Instant.ofEpochSecond(23L), Duration.ofSeconds(60));
        cache.queryRange("x", Instant.ofEpochSecond(12L), Instant.ofEpochSecond(3L), Duration.ofSeconds(60));

        verify(delegate, times(2)).queryRange(eq("x"), any(), any(), any());
        assertThat(cache.snapshot()).hasSize(2);
    }

    // ---- thread-safety: concurrent cold-miss loads must not corrupt state ----

    @Test
    void concurrentAccessReturnsResultsAndKeepsCacheConsistent() throws InterruptedException {
        HttpPrometheusQueryClient delegate = mock(HttpPrometheusQueryClient.class);
        when(delegate.queryInstant(any(), any())).thenReturn(someResult());
        CachingPrometheusQueryClient cache = new CachingPrometheusQueryClient(delegate, 60);

        int threads = 16;
        int perThread = 50;
        Thread[] workers = new Thread[threads];
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            final int id = i;
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        // Mix of shared and per-thread keys.
                        Instant t = Instant.ofEpochSecond(1700000000L + (j % 5));
                        PrometheusResult r = cache.queryInstant("k" + (id % 4), t);
                        if (r == null || r.series().isEmpty()) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread w : workers) {
            w.join();
        }

        assertThat(failures.get()).isZero();
        // 4 distinct keys × 5 distinct times = at most 20 cached entries.
        assertThat(cache.snapshot().size()).isLessThanOrEqualTo(20);
    }
}
