# QA — METRICS_API

**Verdict:** PASS
**Build:** `mvn -o test` → 1050 tests, 0 failures, 0 errors (BUILD SUCCESS)

## Scope

QA of the UI metrics API on `feat/metrics-api` (`main..feat/metrics-api`). Focus
per the brief: `TimeWindow` param handling, scope validation, cache correctness,
rate-limit, Prometheus client parsing, and dashboard-PromQL parity. Tests only;
no production code changed.

## Tests added / updated

- `src/test/java/.../domain/metrics/controller/TimeWindowTest.java` — **adopted +
  fixed.** This file was present untracked and **did not compile** (used
  `Assertions.within(Duration)` for `isCloseTo(Instant, …)`, a signature that does
  not exist in the project's AssertJ). Fixed to `within(5, ChronoUnit.SECONDS)`.
  38 tests covering: defaults (1h window, step floor, ~720-point step for 1h/24h,
  tiny-window flooring), preset vs raw precedence + mutual exclusion, case-
  insensitive/unknown presets, `from`/`to`-only defaulting, the **30d cap
  boundary (exactly 30d allowed, 30d+1s → 400)**, inverted/zero window → 400, the
  **5s step floor boundary (5s ok, 4s/0/negative → 400)**, the **11000-point cap
  boundary (exactly 11000 ok, 11001 → 400; default step never overflows even at
  30d)**, all step formats (plain/`30s`/`5m`/`1h`/`1d`/`PT1M`/`pt5m`, `ms`
  rejected, unknown unit/garbage → 400), and from/to parsing (epoch, ISO, mixed,
  malformed → 400).
- `src/test/java/.../domain/metrics/ratelimit/MetricsRateLimitInterceptorUnitTest.java`
  — **new (8 tests).** Burst capacity == per-minute limit, 429 status + JSON body,
  **token refill over real time** (drain → wait 1.1s → allowed), `X-Forwarded-For`
  single-IP keying (overrides remoteAddr), **multi-IP XFF keys on the leftmost
  client IP**, distinct forwarded IPs independent, blank-XFF fallback to
  remoteAddr, zero-limit clamped to 1.
- `src/test/java/.../infrastructure/client/prometheus/HttpPrometheusQueryClientHttpTest.java`
  — **new (4 tests).** Exercises the **HTTP transport path** the dev test skipped:
  non-2xx (500, 400+envelope) → `UpstreamPrometheusException` (→ 502); instant +
  range success end-to-end via a throwaway loopback `HttpServer`, asserting the
  URL-encoded PromQL and `time`/`start`/`end`/`step` params.
- `src/test/java/.../infrastructure/client/prometheus/HttpPrometheusQueryClientMalformedTest.java`
  — **new (8 tests).** Partial/malformed `data.result`: wrong-arity value tuples,
  missing `value`/`values`, mixed valid/invalid matrix tuples, scalar wrong arity,
  missing `resultType` (falls back to expected), non-array `result`, missing
  `status` → error envelope.
- `src/test/java/.../domain/metrics/service/MetricsQueryServiceEdgeTest.java`
  — **new (5 tests).** RTP `or vector(0)` surfacing as `0.0` (not null), non-finite
  scalar carried as `null`, `bots_by_status` series missing the `status` label
  skipped (no NPE), timeseries DTO echoes requested from/to/step, null sample
  carried through as a gap point.

Dev's existing metrics tests reviewed and confirmed green & adequate:
`MetricsControllerTest` (15), `MetricsQueryServiceTest` (5),
`CachingPrometheusQueryClientTest` (11, incl. cache-key-includes-window and
concurrency), `HttpPrometheusQueryClientTest` (7, parse-level),
`MetricKeyDashboardParityTest` (4), `MetricsRateLimitInterceptorTest` (2).

## Coverage of the diff

- `TimeWindow.java` ← `TimeWindowTest` (every AD-5/OI-2 rule + all boundaries).
- `MetricsController.java` ← `MetricsControllerTest` (4 endpoints, validation, 502).
- `MetricKey.java` / `MetricScope.java` ← `MetricKeyDashboardParityTest` (PromQL
  string-equals the dashboard `expr` — the anti-drift guarantee holds) + scope
  rejection via controller tests.
- `MetricsQueryService.java` ← `MetricsQueryServiceTest` + `…EdgeTest` (mapping,
  name resolution, RTP-zero, null/NaN, status-label-missing).
- `HttpPrometheusQueryClient.java` ← `HttpPrometheusQueryClientTest` (parse) +
  `…HttpTest` (transport/502) + `…MalformedTest` (partial payloads).
- `CachingPrometheusQueryClient.java` ← `CachingPrometheusQueryClientTest`
  (dedupe within TTL, distinct keys, post-expiry re-hit, ttl=0 disables, key
  includes window+step, instant/range namespace separation, concurrency).
- `MetricsRateLimitInterceptor.java` ← `MetricsRateLimitInterceptorTest` +
  `…UnitTest` (exhaustion, IP isolation, refill, XFF single/multi, 429 body).
- `docker-compose.yml` / `application.properties` ← infra/config, verified by
  inspection (matches AD-1/AD-3/AD-8/AD-9); on-server verification deferred to the
  Releaser per the plan's `## Verification`.

## Defects / observations

No functional defects found in production code; all 1050 tests pass.

**Pre-existing broken test (fixed by QA):** the untracked `TimeWindowTest.java`
did not compile (`within(Duration)`). It blocked `mvn test` for the whole module.
Fixed and adopted. If the dev intended to commit it, it shipped non-compiling.

**OBS-1 (low) — rate-limit bucket map is not evicted.** `MetricsRateLimitInterceptor.buckets`
grows one entry per distinct client key and is never swept (unlike
`CachingPrometheusQueryClient`, which has a `MAX_ENTRIES` sweep). Because the key
is the client-controlled `X-Forwarded-For` leftmost IP, a caller rotating that
header can create unbounded buckets → slow memory growth. The class javadoc
claims cardinality "is bounded by the number of distinct client IPs," which is
only true for honest clients. Not a v1 blocker (endpoints are the AD-9 public-but-
unauthenticated model and the brief flags auth as OI-3), but the "doesn't leak
buckets unboundedly" property is **not** guaranteed. Suggest a periodic/size-capped
sweep of empty-and-stale buckets. Test `…UnitTest` documents the current keying
behavior so any future eviction change is observable.

**OBS-2 (low, by-design) — XFF-based limiting is spoofable.** Keying on a client-
supplied header means one real client can evade the limit by rotating the header,
and (per OBS-1) inflate the bucket map. Inherent to AD-9's dependency-free per-IP
design; acceptable for v1, surfaced not silently accepted.

**OBS-3 (info) — negative step division.** `parseStep("-5")` yields
`Duration.ofSeconds(-5)`; the `< MIN_STEP` check fires first (→ 400) before the
`windowSpan/step` division, so no divide-by-negative/zero reaches the point-count
check. Confirmed safe; covered by `TimeWindowTest.StepFloor`.

## Failures

None.
