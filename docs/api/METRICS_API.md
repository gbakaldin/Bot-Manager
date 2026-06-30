# Metrics API — UI-facing metrics over Prometheus

Status: **shipped** (deployed to Bot-1, verified 2026-06-29). Plan:
[`docs/plans/METRICS_API.md`](../plans/METRICS_API.md). Reviews:
[`docs/reviews/METRICS_API/`](../reviews/METRICS_API/).

## What this is

A small set of **public, read-only REST endpoints** on bot-manager that expose
the per-Game and per-Environment bot metrics — the same set already rendered on
the Grafana `per-game` / `per-environment` dashboards — to the product UI (a
separate repo).

bot-manager backs these endpoints by querying its internal Prometheus and
returns clean, UI-bindable JSON DTOs. The UI never speaks Prometheus and never
sends PromQL: it passes only a scope id, a metric key, and a time window.
**bot-manager owns all PromQL** as the single source of truth, reusing the
dashboard queries verbatim.

Why it exists: not everyone who needs these numbers has Grafana/back-office
access, and Grafana is internal-only. These endpoints are the controlled gateway
in front of the internal observability stack — no Grafana proxying/embedding, no
new datastore.

```
  Product UI (separate repo)
        │  GET /api/v1/metrics/...  (scope id + metric key + time window)
        ▼
  bot-manager  ── MetricsController ─► MetricsQueryService ─► PrometheusQueryClient
   (owns PromQL, the closed MetricKey catalog)                      │
        ▲                                                           │ http://prometheus:9090
        │  JSON DTOs (summary / timeseries)                         ▼
        │                                            internal Prometheus (30d retention)
```

## Endpoints

Base path: `/api/v1/metrics`. Two shapes per scope (game, environment):

| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/game/{gameId}/summary` | Current instant value of every scalar metric for the game + bots-by-status + resolved game name |
| GET | `/game/{gameId}/timeseries?metric=&...` | One metric's series over a window (for charting) |
| GET | `/environment/{environmentId}/summary` | Same as game summary, per environment |
| GET | `/environment/{environmentId}/timeseries?metric=&...` | One metric's series over a window, per environment |

`summary` runs Prometheus instant queries (`/api/v1/query`); `timeseries` runs a
range query (`/api/v1/query_range`).

The endpoints appear in the OpenAPI doc (`/v3/api-docs`) and Swagger UI
(`/swagger-ui.html`).

### Authentication & exposure

These endpoints are **public and unauthenticated**, matching the existing
public `/api/v1/bot-group/{id}/health` endpoint. The only hardening in v1 is a
per-client rate limiter (see below). They expose business data (RTP, money
flows, bot counts), so a stronger auth model is tracked as an open item
(plan OI-3) and should be revisited if the exposure model changes.

## `timeseries` query parameters

| Param | Required | Format | Notes |
|-------|----------|--------|-------|
| `metric` | yes | metric key | Must be valid for the scope (see catalog); unknown/cross-scope → 400 |
| `range` | no | `1h` \| `6h` \| `24h` \| `7d` \| `30d` | Preset window `now-duration .. now`. Mutually exclusive with `from`/`to` |
| `from` | no | epoch-seconds or ISO-8601 | Window start. Default `to - 1h` |
| `to` | no | epoch-seconds or ISO-8601 | Window end. Default `now` |
| `step` | no | `60`, `60s`, `5m`, `1h`, `1d`, or ISO-8601 `PT1M` | Resolution. Default `max(5s, range/720)` (~720 points) |

Parameter rules (all violations → **HTTP 400**, `BadRequestException`):

- `range` and explicit `from`/`to` are **mutually exclusive** — supplying both is a conflict.
- `to` must be strictly after `from`.
- The window may not exceed **30 days** (the Prometheus retention cap).
- `step` must be **≥ 5s** (Prometheus scrape resolution is 10s; sub-scrape steps just waste points).
- The window must yield **≤ 11 000 points** (`window / step`) — Prometheus' own max-resolution guard. Too high → 400 telling you to increase `step` or shrink the range.

Note: the rate/increase windows baked into each metric (`[5m]`, `[1m]`, `[1h]`)
are **intrinsic to the metric definition** and are *not* the user's chart range.
The user's `from`/`to`/`step` only parameterize the range query envelope.

## Metric catalog

Each key carries one PromQL template per scope, lifted **verbatim** from the
dashboards. The `MetricKeyDashboardParityTest` asserts each built query
string-equals the dashboard `expr`, so the API and dashboards can never drift —
if a dashboard query changes, the template in `MetricKey` must move with it.

### Cross-scope (valid on both `/game` and `/environment`)

| Metric key | Meaning | Multi-series |
|------------|---------|:---:|
| `bot_groups` | Distinct active bot groups | |
| `total_bots` | Total bots in scope | |
| `bots_by_status` | Bot counts per status | ✅ (by `status`) |
| `rtp_5m` | 5m RTP (winnings/bet-amount), guarded `or vector(0)` so it's never NaN | |
| `dead_seconds_1h` | Dead time over the last hour (seconds) | |
| `bets_placed_rate_1m` | Bets placed rate (1m) | |
| `bet_amount_rate_1m` | Bet amount rate (1m) | |
| `winnings_rate_5m` | Winnings rate (5m) | |
| `failures_rate_5m` | Failures rate (5m) | |

### GAME-only (`/game` route only)

| Metric key | Meaning |
|------------|---------|
| `jackpots_rate_5m` | Jackpots rate (5m) |
| `jackpot_amount_rate_5m` | Jackpot amount rate (5m) |

### ENVIRONMENT-only (`/environment` route only)

| Metric key | Meaning | Multi-series |
|------------|---------|:---:|
| `rtp_per_game_5m` | Per-game RTP within the environment | ✅ (by `gameName`) |
| `reconnect_rate_5m` | Reconnect rate (5m) | ✅ (by `reason`) |

Using a GAME-only key on `/environment` (or vice-versa) returns **400**.

The readable name (`scopeName` in responses) is resolved from the join gauges
`game_join{gameId,gameName,gameType}` / `environment_join{environmentId,environmentName}`.
Series created before the `gameId`-label rollout lack the selector label and are
excluded by design (mirrors dashboard behavior). `scopeName` is `null` if it
can't be resolved.

## Response shapes

### Summary — `MetricsSummaryDTO`

```jsonc
{
  "scope": "GAME",                       // GAME | ENVIRONMENT
  "scopeId": "45d42867-...",             // gameId / environmentId
  "scopeName": "Fruit Shop",             // resolved from join gauge; null if unresolved
  "metrics": {                           // metricKey -> current value (non-finite -> null)
    "total_bots": 40.0,
    "rtp_5m": 0.6407,
    "bets_placed_rate_1m": 896.0,
    "bot_groups": 6.0
    // ... every scalar metric for the scope
  },
  "botsByStatus": {                      // multi-series bots_by_status panel, status -> count
    "CONNECTION_AUTHENTICATED": 40.0
  },
  "generatedAt": "2026-06-29T09:09:00Z"
}
```

### Timeseries — `MetricsTimeseriesDTO`

```jsonc
{
  "scope": "GAME",
  "scopeId": "45d42867-...",
  "scopeName": "Fruit Shop",
  "metric": "bets_placed_rate_1m",
  "from": 1719651600,                    // resolved window start (epoch seconds)
  "to": 1719655200,                      // resolved window end (epoch seconds)
  "step": 60,                            // resolved step (seconds)
  "series": [                            // one entry per label set; single-series metrics carry exactly one
    {
      "labels": { "status": "CONNECTION_AUTHENTICATED" },  // distinguishing labels; {} for a single aggregate series
      "points": [
        { "timestamp": 1719651600, "value": 0.0 },
        { "timestamp": 1719651660, "value": 895.96 },
        { "timestamp": 1719651720, "value": null }         // null = non-finite Prometheus sample (NaN/Inf) — skip-render the gap
      ]
    }
  ],
  "generatedAt": "2026-06-29T09:09:00Z"
}
```

An empty result yields an **empty `series` list with HTTP 200** (not 404), so
the UI renders an empty chart rather than an error.

## Error responses

Handled centrally by `RestExceptionHandler`; the controller only expresses the
success path.

| Condition | Status | Cause |
|-----------|:---:|-------|
| Unknown metric key | 400 | `metric` not in the catalog |
| Cross-scope metric | 400 | e.g. `reconnect_rate_5m` on a `/game` route |
| Bad time window | 400 | `range`+`from`/`to` conflict, `to ≤ from`, range > 30d, `step` < 5s, > 11 000 points, unparseable `from`/`to`/`step` |
| Invalid scope id | 400 | id fails the `[A-Za-z0-9_-]+` allow-list (PromQL-injection guard, see below) |
| Rate limit exceeded | 429 | Per-client token bucket drained |
| Prometheus error / unreachable | 502 | `UpstreamPrometheusException` — carries Prometheus' `error` string |

## Hardening (the security-fix commits)

The feature shipped in `0ac7c83` and was followed by three security fixes before
the re-review PASS (`e2f60e8`):

- **PromQL injection closed (`88dabdd`).** The scope id from the path was being
  interpolated raw into the PromQL label matcher (`label="<id>"`), so an id
  containing a `"` could break out and inject arbitrary PromQL. Now every query
  path routes through a single chokepoint, `MetricScope.selector(id)`, which
  validates the id against `[A-Za-z0-9_-]+` (UUID/slug shaped) before any query
  string is built. Invalid → 400. (A leading `$` is allowed only so the same code
  path can render the Grafana `$gameId` template form the parity test pins
  against; `$` carries no breakout risk inside a quoted value.)

- **Rate limiter fixed (`cb9a0f3`).** It originally keyed buckets on the
  client-controlled `X-Forwarded-For` header and never evicted the map — so an
  attacker could rotate XFF to mint a fresh bucket every request (trivial bypass)
  *and* grow the map unboundedly (slow memory-DoS). The component meant to
  protect Prometheus had become the amplifier. Now it keys on
  `request.getRemoteAddr()` (the real TCP peer) and bounds the bucket map with a
  sweep. Behind a single proxy this is a coarse/near-global limit — acceptable
  for v1, whose only goal is shielding internal Prometheus. Safe XFF handling
  would need a configurable trusted-proxy allow-list (out of scope, documented in
  the code).

- **Cache key fixes (`73e51d1`).** Deduped live "now" instant queries and dropped
  NUL cache-key delimiters in `CachingPrometheusQueryClient`.

## Caching

A dependency-free, in-process TTL cache wraps Prometheus query results
(`CachingPrometheusQueryClient`) so a polling UI doesn't amplify load onto
Prometheus. Keyed by the fully-resolved query + params; caches the raw result
model (so summary sub-queries dedupe). TTL is configurable; `0` disables it.

## Configuration (`application.properties`)

```properties
# Base URL bot-manager uses to query Prometheus (internal-only, never exposed to the UI)
prometheus.url=http://prometheus:9090

# Short server-side TTL (seconds) on Prometheus query results; 0 disables caching
metrics.cache.ttl-seconds=5

# Per-client (per-IP) token-bucket rate limit on /api/v1/metrics/**; over → HTTP 429
metrics.ratelimit.requests-per-minute=120
```

## Prometheus retention (30d)

To serve up to a month of history, Prometheus TSDB retention was bumped from the
implicit 15d default to **30d** via a `command:` flag on the `prometheus` service
in `docker-compose.yml`:

```yaml
    command:
      - "--config.file=/etc/prometheus/prometheus.yml"
      - "--storage.tsdb.path=/prometheus"
      - "--storage.tsdb.retention.time=30d"
```

The first two flags are Prometheus' own defaults and **must be restated** —
specifying `command:` overrides the image's default command entirely, so omitting
them would break config loading and the TSDB path.

> **Deploy note (Bot-1 single-compose layout):** bot-manager and the whole
> observability stack live in one Compose project. Apply retention changes with a
> **targeted** recreate — `docker compose up -d --no-deps prometheus` — never a
> full `down`, which would bounce Grafana/Loki/bot-manager (and has hit a host
> port-9090 conflict before). See `docs/reviews/METRICS_API/release.md` for the
> as-deployed record.

Longer-term storage (`remote_write` to Thanos/Mimir/Timescale) is out of scope.
The API is deliberately backend-agnostic (DTOs describe *metrics*, not
*Prometheus*); a future store swap only changes `PrometheusQueryClient` behind
its interface — the controller, DTOs, and UI contract stay fixed.

## Source map

| Concern | Class |
|---------|-------|
| Endpoints | `domain/metrics/controller/MetricsController.java` |
| Time-window parse/validate | `domain/metrics/controller/TimeWindow.java` |
| Metric catalog (PromQL templates) | `domain/metrics/MetricKey.java` |
| Scope + id validation (injection guard) | `domain/metrics/MetricScope.java` |
| Query orchestration + DTO mapping | `domain/metrics/service/MetricsQueryService.java` |
| Prometheus HTTP client | `infrastructure/client/prometheus/HttpPrometheusQueryClient.java` (interface `PrometheusQueryClient`) |
| TTL cache decorator | `infrastructure/client/prometheus/CachingPrometheusQueryClient.java` |
| Rate-limit interceptor | `domain/metrics/ratelimit/MetricsRateLimitInterceptor.java` (wired in `config/MetricsWebConfig.java`) |
| Response DTOs | `domain/metrics/dto/{MetricsSummaryDTO,MetricsTimeseriesDTO,MetricSeriesDTO,MetricPointDTO}.java` |

## Examples

```bash
# Per-game summary
curl http://localhost:8080/api/v1/metrics/game/45d42867-.../summary

# Per-game timeseries, raw window
curl "http://localhost:8080/api/v1/metrics/game/45d42867-.../timeseries?metric=bets_placed_rate_1m&from=1719651600&to=1719655200&step=60"

# Per-game timeseries, preset window
curl "http://localhost:8080/api/v1/metrics/game/45d42867-.../timeseries?metric=winnings_rate_5m&range=24h"

# Per-environment summary
curl http://localhost:8080/api/v1/metrics/environment/ad4e7948-.../summary

# Per-environment, env-only metric
curl "http://localhost:8080/api/v1/metrics/environment/ad4e7948-.../timeseries?metric=reconnect_rate_5m&range=6h"
```
