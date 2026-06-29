# Compliance — METRICS_API

Branch: feat/metrics-api
Plan reviewed: `docs/plans/METRICS_API.md` (at branch HEAD 912574e)
Diff reviewed: `git diff main..feat/metrics-api`

## Verdict

PASS

## Phase-by-phase

### Phase 1 — Prometheus retention bump to 30d
Status: implemented
The `prometheus` service in `docker-compose.yml` gains exactly the AD-1 `command:`
block: `--config.file=/etc/prometheus/prometheus.yml`,
`--storage.tsdb.path=/prometheus`, `--storage.tsdb.retention.time=30d`, with a
comment restating why the two defaults must be present. No change to
`prometheus/prometheus.yml` (retention is a flag, not config), as required. This
is the only compose change. Targeted-recreate instructions remain in the plan's
`## Verification` for the Releaser. Faithful.

### Phase 2 — PrometheusQueryClient
Status: implemented
`HttpPrometheusQueryClient` uses `java.net.http.HttpClient` (NOT WebClient),
3s connect / 5s request timeouts (AD-7), behind a narrow `PrometheusQueryClient`
interface (AD-10). Hits `/api/v1/query` and `/api/v1/query_range`; base URL from
`@Value("${prometheus.url:http://prometheus:9090}")` (AD-3); `prometheus.url`
added to `application.properties` mirroring `gamems.url`. Envelope parsing covers
vector, matrix, and scalar result types; `status != success`, non-2xx, transport
errors, and unparseable bodies all throw an upstream exception; `NaN`/`+Inf`/
`-Inf` → `null`. The parse step is package-private (`parse(body, expected,
promql)`) for unit testing as the plan asked.

Deviation (faithful — not drift): the plan said map failures to 502 via
`UpstreamGatewayException`. Dev introduced `UpstreamPrometheusException extends
UpstreamGatewayException`. `RestExceptionHandler` maps the base class and all
subclasses to 502 (verified at `RestExceptionHandler.java:112`), so the 502
contract holds and the dedicated subclass is a faithful elaboration that adds a
clearer error identity. Minor cosmetic nit (NOT compliance-relevant): the
subclass passes type string `"Game server error"` for what is a Prometheus error
— wrong label in the body `type` field, but it does not affect the status code or
any planned behavior. Flagging for the Reviewer, not a send-back.

### Phase 3 — MetricKey catalog + MetricsQueryService + DTOs
Status: implemented
`MetricKey` is a closed enum carrying per-scope PromQL templates, a multi-series
flag, and scope support. I verified every template **byte-for-byte against the
dashboards**: all 11 per-game `expr` strings (per-game.json lines 132/191/259/
326/385/440/495/550/605/660/715) and all 11 per-environment `expr` strings
(per-environment.json 132/191/259/326/385/441/496/551/606/661/716) match the
substituted templates exactly, including the mandatory `or vector(0)` guards and
the `bots_by_game_status` vs `bots_by_env_status` metric-name split. The
anti-drift guarantee is additionally pinned by `MetricKeyDashboardParityTest`
(reads both JSON files, asserts each built PromQL is present verbatim) plus
GAME-only / ENV-only scope-availability assertions. Cross-scope keys are tagged
correctly (`jackpots_rate_5m` / `jackpot_amount_rate_5m` GAME-only;
`rtp_per_game_5m` / `reconnect_rate_5m` ENV-only). `MetricsQueryService` owns no
PromQL, resolves the readable name from the join gauges, maps vector→summary and
matrix→timeseries, strips `__name__`, and returns empty series (not 404) on empty
results. DTOs (`MetricsSummaryDTO`, `MetricsTimeseriesDTO`, `MetricSeriesDTO`,
`MetricPointDTO`) are `@Data @Builder` per AD-6. Faithful.

### Phase 4 — MetricsController + validation
Status: implemented
`MetricsController` (`@RequestMapping("api/v1/metrics")`) exposes exactly the four
AD-4 / OI-1 endpoints (game/env × summary/timeseries). Validation throws
`BadRequestException` (→ 400) for unknown metric, cross-scope key, `to<=from`,
range > 30d, sub-floor step, and point-count overflow. Time params support BOTH
raw `from`/`to`/`step` AND `range` presets (OI-2), with raw/preset mutual
exclusion. `@Operation` swagger annotations and success-only `ResponseEntity.ok`
match `BotGroupController` conventions. Faithful.

### Phase 5 — Caching + rate-limit
Status: implemented
`CachingPrometheusQueryClient` is a dependency-free `ConcurrentHashMap` TTL cache
(default 5s via `metrics.cache.ttl-seconds`), `@Primary`, caching the raw
`PrometheusResult` keyed by resolved query + time/range/step, with `ttl=0`
disabling and a `MAX_ENTRIES` safety sweep — matches AD-8. `MetricsRateLimitInterceptor`
is a dependency-free per-IP token bucket returning HTTP 429, registered via
`MetricsWebConfig` only on `/api/v1/metrics/**`, limit from
`metrics.ratelimit.requests-per-minute` (default 120). No Spring Security added
(OI-3 = mirror existing + rate-limit). Faithful.

## Resolved-decision check

- OI-1 (4-endpoint shape): held — exactly four endpoints, two per scope.
- OI-2 (both time-param modes): held — raw `from`/`to`/`step` and `range` presets,
  mutually exclusive.
- OI-3 (no-auth + rate-limit): held — no Spring Security; per-IP rate-limit
  interceptor on the metrics path only.
- No new datastore: held — Prometheus only; no remote_write / Thanos / Timescale.
- PromQL parity with dashboards: held — verified verbatim against both dashboard
  JSONs and pinned by a parity test.

## Drift

None rising to send-back. Two faithful elaborations and one cosmetic nit:

1. `UpstreamPrometheusException` subclass instead of raw `UpstreamGatewayException`
   — faithful elaboration; 502 contract preserved via base-class handler.
2. `TimeWindow` helper (not named in the plan) — see below; faithful.
3. Cosmetic: `UpstreamPrometheusException`'s `type` body label says "Game server
   error" for a Prometheus failure. Not a status/behavior deviation from the plan;
   left for the Reviewer.

### TimeWindow helper (explicitly asked to judge)
Faithful elaboration, not drift. AD-5 / OI-2 require parsing raw and preset time
params, defaulting, and enforcing the 30d cap, 5s step floor, and 11000-point
guard — the plan describes the rules but names no class. Extracting them into a
testable `record TimeWindow.resolve(...)` keeps the controller thin and is exactly
the kind of structural choice left to the implementer. It implements the AD-5
rules precisely (30d cap, MIN_STEP 5s, MAX_POINTS 11000, default step
`max(5s, range/720)`, default 1h window). No verdict impact.

### OBS-1 (QA): rate-limit bucket map never evicted
The plan does NOT anticipate bucket eviction. AD-9 specifies "a lightweight
per-IP (or global) rate limiter ... returning HTTP 429"; the implementation
javadoc's claim that map cardinality "is bounded by the number of distinct client
IPs" holds only for honest clients, since the key is the client-controlled
`X-Forwarded-For` leftmost IP. This is a real (low-severity) gap, but it is a gap
the plan never required closing — the plan's hardening scope for v1 is exactly
(closed enum + range caps + 5s cache + rate-limit), and auth/exposure hardening is
explicitly deferred to OI-3. So this is neither plan drift nor an Architect-1
technical error; it is a v1-acceptable limitation already surfaced by QA. No
amendment and no send-back on this basis. Recommend the Releaser/owner track it as
a follow-up (size-capped or periodic sweep of stale buckets) alongside the OI-3
auth decision.

## Out-of-scope changes

The branch diff carries a large amount of unrelated material not part of
METRICS_API and not requested by this plan: other plans under `docs/plans/`,
many `docs/reviews/*` from other features, `.claude/agents/*`, `PLUGIN_PLAN.md`,
`TaiXiuMessages/*`, `seed.js`, app/console logs, and two large binaries
(`bot.tar` ~392MB, `infra-images.tar.gz` ~533MB). None of these are METRICS_API
production code or tests, so they do not affect this compliance verdict, but the
binaries and logs should not be committed to the branch — flag to the user / the
Releaser to strip them before merge (they are unrelated to the metrics feature and
bloat the history).

## Amendments to the plan

None. No Architect-1 technical oversight was found; every planned approach was
achievable and was implemented faithfully.
