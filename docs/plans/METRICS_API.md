# METRICS_API

## Goal

Expose the per-Game and per-Environment bot metrics — the exact set already
rendered on the Grafana `per-game` / `per-environment` dashboards — to the
separate-repo product UI through a **bot-manager REST API** that bot-manager
backs by querying Prometheus and returns clean, UI-bindable DTOs. The UI passes
only simple query params (`gameId`/`environmentId` + a time window and a metric
key); **bot-manager owns all PromQL** as the single source of truth, reusing the
dashboard queries verbatim. No Grafana proxying/embedding, no new datastore.
Prometheus stays the backend; its retention is bumped from the implicit 15d
default to **30d** so the UI can show up to a month of history. bot-manager
remains the only thing permitted to query Prometheus — the new public endpoints
are the controlled gateway in front of the internal-only observability stack.

## Findings — Current State

### Prometheus deployment & retention
- `prom/prometheus:v2.55.0` runs with **no `--storage.tsdb.retention.time` flag**
  (`/Users/gleb/IdeaProjects/Bot/docker-compose.yml:69-82`), so it falls back to
  the default **15d**. There is no `command:` on the service today.
- Config is **bind-mounted** read-only at
  `/etc/prometheus/prometheus.yml` from `./prometheus/prometheus.yml`
  (`docker-compose.yml:77`); the TSDB is a named volume `prometheus-data`
  (`docker-compose.yml:80`). Retention is a **container start flag**, not a config
  file setting — so it requires recreating the prometheus container, not just an
  edit to the bind-mounted `prometheus.yml`.
- **Single-compose layout (Bot-1).** bot-manager AND the whole observability
  stack (`mongo`, `loki`, `promtail`, `grafana`, `prometheus`) live in one
  Compose project (`docker-compose.yml:1-88`; user memory
  `project_bot1_compose_layout.md`). `docker compose down` during a bot redeploy
  stops Prometheus too, and a full `up -d` has previously hit a host port-9090
  conflict. The retention flag change must be applied with a **targeted**
  recreate of just the prometheus service.

### Prometheus network address (bot-manager → Prometheus)
- Prometheus scrapes bot-manager at `bot-manager:8085` over the internal compose
  network (`/Users/gleb/IdeaProjects/Bot/prometheus/prometheus.yml:24-30`;
  bot-manager listens on 8085 in-container per
  `/Users/gleb/IdeaProjects/Bot/src/main/resources/application.properties:6`).
- The reverse direction (bot-manager → Prometheus) uses the compose service name
  `prometheus` on port 9090: **`http://prometheus:9090`**. The Prometheus HTTP API
  lives at `/api/v1/query` (instant) and `/api/v1/query_range` (matrix). This
  base URL must be a configurable property, mirroring `gamems.url`
  (`application.properties:25`).

### Metric catalog & PromQL (reuse from dashboards — source of truth)
From `/Users/gleb/IdeaProjects/Bot/grafana/provisioning/dashboards/per-game.json`
and `.../per-environment.json`. Selector label is `gameId` (per-game) or
`environmentId` (per-env). Readable names come from the **join gauges**
`game_join {gameId,gameName,gameType}` and `environment_join
{environmentId,environmentName}` — named `_join` not `_info` because `_info` is a
reserved Prometheus suffix (`InfoGaugeRefresher.java:20-39`). Status counts come
from `bots_by_game_status {gameId,gameName,status}` /
`bots_by_env_status {environmentId,status}` (`InfoGaugeRefresher.java:25-28`).

Per-game PromQL (`$gameId` is the selector; per-env identical with
`bots_by_env_status` and `environmentId="$environmentId"`):

| Metric key | PromQL (instant / scalar form) | Notes |
|---|---|---|
| `bot_groups` | `count(count by (botGroupId) (bot_messages_total{gameId="$id"}))` | per-game.json:132 / per-environment.json:132 |
| `total_bots` | `sum(bots_by_game_status{gameId="$id"})` | per-game.json:191 |
| `bots_by_status` | `bots_by_game_status{gameId="$id"}` (vector, one series per `status`) | per-game.json:385; per-env.json:385 |
| `rtp_5m` | `(sum(rate(bot_winnings_total{gameId="$id"}[5m])) / sum(rate(bot_bet_amount_total{gameId="$id"}[5m]))) or vector(0)` | per-game.json:259 — the `or vector(0)` guard is mandatory |
| `dead_seconds_1h` | `sum(increase(bot_dead_seconds_total{gameId="$id"}[1h]))` | per-game.json:326 |
| `bets_placed_rate_1m` | `sum(rate(bot_bets_placed_total{gameId="$id"}[1m]))` | per-game.json:440 |
| `bet_amount_rate_1m` | `sum(rate(bot_bet_amount_total{gameId="$id"}[1m]))` | per-game.json:495 |
| `winnings_rate_5m` | `sum(rate(bot_winnings_total{gameId="$id"}[5m]))` | per-game.json:550 |
| `jackpots_rate_5m` | `sum(rate(bot_jackpots_total{gameId="$id"}[5m]))` | per-game.json:605 (per-game only) |
| `jackpot_amount_rate_5m` | `sum(rate(bot_jackpot_amount_total{gameId="$id"}[5m]))` | per-game.json:660 (per-game only) |
| `failures_rate_5m` | `sum(rate(bot_failures_total{gameId="$id"}[5m]))` | per-game.json:715; per-env.json:715 |

Per-environment-only extras:
| Metric key | PromQL | Notes |
|---|---|---|
| `rtp_per_game_5m` | `(sum by (gameName) (rate(bot_winnings_total{environmentId="$id"}[5m])) / sum by (gameName) (rate(bot_bet_amount_total{environmentId="$id"}[5m]))) or vector(0)` | per-environment.json:441 — multi-series by gameName |
| `reconnect_rate_5m` | `sum by (reason) (rate(bot_reconnects_total{environmentId="$id"}[5m]))` | per-environment.json:661 — multi-series by reason |

For **timeseries** the same expressions are sent to `/api/v1/query_range` with
`start`/`end`/`step`; for the **summary** scalar panels they go to `/api/v1/query`
(instant). The rate/increase windows (`[5m]`, `[1m]`, `[1h]`) are intrinsic to the
metric definition and are NOT the same as the user-supplied chart range.

### Existing API conventions
- Controllers: `@RestController` + `@RequestMapping("api/v1/...")`, constructor
  injection, return `ResponseEntity<DTO>`, success-path only — exceptions are
  translated centrally
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java:40-53`).
- The `GET /api/v1/bot-group/{id}/health` endpoint (`BotGroupController.java:156-163`,
  DTO `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/dto/BotGroupHealthDTO.java`)
  is the closest precedent — a **public product feature** returning a read-only,
  Lombok `@Data @Builder` DTO. This metrics API is the same class of feature.
- Error translation: `RestExceptionHandler` already maps
  `BadRequestException`→400, `ResourceNotFoundException`→404, `IllegalArgumentException`→400,
  `UpstreamGatewayException`→502, terminal `Exception`→500
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java:71-204`).
  Reuse `BadRequestException` for validation and `UpstreamGatewayException` for
  Prometheus errors.
- **HTTP client idiom is `java.net.http.HttpClient`** with Jackson, as in
  `ApiGatewayClient` (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:57,83,303-308`).
  No WebClient/RestClient/WebFlux on the classpath.
- **No Spring Security on the classpath** (`pom.xml` has only
  `spring-boot-starter-web`); all existing public endpoints are currently
  unauthenticated. Actuator (incl. `/actuator/prometheus`) is exposed on the main
  port for internal-only use (`application.properties:28`).
- **No caching abstraction** (`spring-boot-starter-cache`/Caffeine) and **no
  scheduled-cache infra** beyond ad-hoc virtual-thread schedulers
  (`InfoGaugeRefresher.java:55`).

### Configurable-URL precedent
- `gamems.url=http://gamems.dev:5007` (`application.properties:25`) read via
  `@Value` (`ApiGatewayClient.java:64,67`). The Prometheus base URL follows the
  same pattern: a new `prometheus.url` property.

## Per-aspect readiness / mapping

| Aspect | Status | Notes |
|---|---|---|
| Metrics already in Prometheus with right labels | **ready** | `gameId`/`gameName`/`environmentId`/`gameType` + join gauges present (`InfoGaugeRefresher.java:20-28`). No bot-side change. |
| PromQL to reuse | **ready** | Lifted verbatim from the two dashboards (catalog above). |
| Prometheus HTTP API reachable in-network | **ready** | `http://prometheus:9090` on the compose net. |
| 30d retention | **partial** | Requires `command:` flag on prometheus service + a targeted container recreate (Phase 1). |
| `PrometheusQueryClient` | **blocked → built** | New class; `java.net.http.HttpClient` idiom (Phase 2). |
| PromQL catalog / `MetricsQueryService` | **blocked → built** | New service owning templated queries + closed metric-key enum (Phase 3). |
| Summary / timeseries DTOs | **blocked → built** | New `@Data @Builder` DTOs mirroring health DTO (Phase 3). |
| `MetricsController` + validation | **blocked → built** | New controller; reuse `BadRequestException`/`RestExceptionHandler` (Phase 4). |
| Caching | **partial** | No cache infra; add lightweight in-process TTL cache (Phase 5). |
| Auth / rate-limit | **open** | No Spring Security today; auth model for public endpoints is an Open Item (OI-3). |
| Long-term store (Thanos/Mimir/Timescale) | **out of scope** | Documented escape hatch only. |

## Architecture Decisions

**AD-1. Prometheus retention → 30d via a `command:` flag, applied by a targeted
recreate.** Add to the `prometheus` service in
`/Users/gleb/IdeaProjects/Bot/docker-compose.yml`:
```yaml
    command:
      - "--config.file=/etc/prometheus/prometheus.yml"
      - "--storage.tsdb.path=/prometheus"
      - "--storage.tsdb.retention.time=30d"
```
The first two flags are Prometheus' own defaults and **must be restated** because
specifying `command:` overrides the image's default command entirely; omitting
them would break config loading and the TSDB path (the named volume mounts at
`/prometheus`). This is the only change to the compose file. Releaser applies it
with a **targeted** `docker compose up -d --no-deps prometheus` (per the
single-compose layout note) so Grafana/Loki/bot-manager are not bounced.

**AD-2. bot-manager owns all PromQL; UI never sends PromQL.** The UI selects from
a **closed enum** of metric keys (the catalog above) plus a `gameId`/`environmentId`
and a time window. `MetricsQueryService` holds one PromQL template per metric key,
copied verbatim from the dashboards. There is no passthrough/free-text query
endpoint — this is both the security boundary and the single source of truth so
the dashboards and the API can never drift.

**AD-3. The Prometheus base URL is a config property `prometheus.url`.** Default
`http://prometheus:9090` (compose service name), read via `@Value` exactly like
`gamems.url`. Internal-only; never exposed to the UI.

**AD-4. Two endpoint shapes per scope: `summary` (instant) and `timeseries`
(range).** Recommended controller shape (final granularity is OI-1, this is the
default the plan builds to):
- `GET /api/v1/metrics/game/{gameId}/summary` → all scalar panel values for that
  game in one DTO (one instant query per scalar metric key, or batched).
- `GET /api/v1/metrics/game/{gameId}/timeseries?metric=<key>&from=&to=&step=` →
  one metric's time series for charting.
- `GET /api/v1/metrics/environment/{environmentId}/summary`
- `GET /api/v1/metrics/environment/{environmentId}/timeseries?metric=<key>&from=&to=&step=`

`summary` uses `/api/v1/query`; `timeseries` uses `/api/v1/query_range`. The
resolved `gameName`/`environmentName` (from the join gauges) is included in every
response so the UI can label without a second call.

**AD-5. Time-range param design: explicit `from`/`to` (epoch seconds or ISO-8601)
+ `step`, with server-side validation and caps.** (Presets vs raw is OI-2; the
plan defaults to raw with sane defaults.) Validation rules, all returning HTTP
400 via `BadRequestException`:
- `metric` must be a known key for the scope (per-game vs per-env have different
  valid sets — e.g. `jackpots_rate_5m` is per-game only); unknown/cross-scope key → 400.
- `to - from` must be `> 0` and `<= 30d` (retention cap, AD-1). Beyond 30d → 400
  with a message naming the cap.
- `step` must be `>= 5s` (Prometheus scrape is 10s; sub-scrape steps waste points)
  and chosen so `(to-from)/step <= 11000` (Prometheus' own max-resolution guard) →
  400 otherwise. Default `step` when omitted = `max(15s, range/720)` to keep
  charts ~720 points.
- Defaults when `from`/`to` omitted on `timeseries`: `to=now`, `from=now-1h`
  (matches the dashboards' default window, per-game.json:16-19).

**AD-6. DTOs are read-only Lombok `@Data @Builder`, mirroring `BotGroupHealthDTO`.**
- `MetricsSummaryDTO`: scope id + resolved name + a `Map<String, Double>` (or a
  typed field per metric) of `metricKey → currentValue`, plus a `bots_by_status`
  sub-structure (`Map<String,Double>` status→count) since that panel is
  multi-series, plus `generatedAt`.
- `MetricsTimeseriesDTO`: scope id + resolved name + `metric` key + `step` + a
  `List<MetricSeriesDTO>` where each series carries its label set (e.g.
  `{status}`, `{gameName}`, `{reason}` for multi-series metrics) and a
  `List<MetricPointDTO{ long timestamp; Double value; }>`. Single-series metrics
  yield exactly one series. Empty result → empty `series` list (HTTP 200, not
  404) so the UI renders an empty chart, not an error.
- `PrometheusQueryClient` returns a thin internal model (vector vs matrix);
  mapping to these DTOs lives in `MetricsQueryService`/a mapper, not the client.

**AD-7. `PrometheusQueryClient` is internal-only and handles the full Prometheus
envelope.** Prometheus responses are `{"status":"success|error","data":{"resultType":"vector|matrix|scalar","result":[...]},"error":...}`.
The client:
- builds `GET http://{base}/api/v1/query?query=<urlencoded>&time=<t>` and
  `GET .../api/v1/query_range?query=&start=&end=&step=`;
- on `status != success` or non-2xx HTTP → throws `UpstreamGatewayException`
  (→ 502 via `RestExceptionHandler`), carrying Prometheus' `error` string;
- parses `vector` (instant: `result[].value = [ts, "stringValue"]`) and `matrix`
  (range: `result[].values = [[ts,"v"],...]`), parsing the **string** values to
  `double` (Prometheus serializes sample values as strings, incl. `"NaN"`/`"+Inf"`
  → map to `null` in the DTO so JSON stays valid);
- short connect/read timeouts (e.g. 3s/5s) so a slow Prometheus cannot stall the
  public endpoint thread.

**AD-8. Short server-side TTL cache (default 5s) on query results.** The public
UI may poll; this protects Prometheus from amplification. Implement with a small
in-process `ConcurrentHashMap`-backed TTL cache keyed by the fully-resolved
query+params (no new dependency — Caffeine/`spring-boot-starter-cache` are not on
the classpath and adding them is avoidable for a 5s TTL). TTL is a property
`metrics.cache.ttl-seconds=5`. Cache the raw Prometheus result model, not the DTO,
so summary sub-queries dedupe. (If the user prefers Caffeine, that's a trivial
swap — OI-3-adjacent, but the plan defaults to dependency-free.)

**AD-9. Auth/exposure mirrors existing public endpoints (currently
unauthenticated), with a rate-limit guard as the only hardening in v1.** Since
there is no Spring Security on the classpath and `/bot-group/{id}/health` is
already public, the metrics endpoints ship with the **same** exposure model.
Because they are public and hit Prometheus, add a lightweight per-IP (or global)
rate limiter in front of the controller (e.g. a simple token-bucket
`HandlerInterceptor`) returning HTTP 429 when exceeded. A stronger auth model
(API key / gateway-enforced) is **OI-3** — flagged, not silently assumed. The
combination of (closed metric-key enum + range caps + 5s cache + rate limit)
already prevents the public endpoint from being used to hammer Prometheus.

**AD-10. UI contract is backend-agnostic (future store swap).** The DTOs and
endpoint shapes describe *metrics*, not *Prometheus*. If retention/cardinality
ever forces a long-term store (Thanos/Mimir/Timescale via `remote_write`), only
`PrometheusQueryClient` (or a sibling impl behind an interface) changes; the
controller, DTOs, and UI contract stay fixed. Define `MetricsQueryService` to
depend on a narrow `PrometheusQueryClient` interface to make that swap a single
implementation change. The long-term store itself is **out of scope** (Open Items).

## Plan

> Maven builds require
> `JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home`
> (the default JDK is Java 8 and will not compile). Prefix every `mvn` command
> with it.

### Phase 1 — Prometheus retention bump to 30d (infra, independent)
Goal: Prometheus keeps 30d of TSDB so the API can serve up to a month.

Modify `/Users/gleb/IdeaProjects/Bot/docker-compose.yml` (prometheus service only):
- Add the `command:` block from AD-1 (`--config.file`, `--storage.tsdb.path`,
  `--storage.tsdb.retention.time=30d`).
- No change to `./prometheus/prometheus.yml` (retention is a flag, not config).

Notes for the Releaser (carried into Verification):
- Apply with a **targeted** recreate: `docker compose up -d --no-deps prometheus`
  (do NOT `down` the whole project — single-compose layout).
- Disk implication: ~2× the 15d footprint. Cardinality is low and bounded (a
  handful of `gameId`/`environmentId` × small status/reason label sets), so the
  absolute growth is modest; note it but no volume resize is expected.

Verification (this phase has on-server verification — see `## Verification` 1).

### Phase 2 — `PrometheusQueryClient`
Goal: a thin, internal-only HTTP client to Prometheus `query` + `query_range`.

Create
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/client/PrometheusQueryClient.java`
(define a small interface + the HTTP impl per AD-10):
- Base URL from `@Value("${prometheus.url:http://prometheus:9090}")` (AD-3).
- `java.net.http.HttpClient` with short timeouts (AD-7); Jackson `ObjectMapper`.
- `PrometheusResult queryInstant(String promql, Instant time)` and
  `PrometheusResult queryRange(String promql, Instant start, Instant end, Duration step)`.
- Internal model `PrometheusResult` { `ResultType` (VECTOR/MATRIX/SCALAR);
  `List<PrometheusSeries{ Map<String,String> labels; List<Sample{long ts; Double value}> samples }>` }.
- Envelope handling per AD-7: `status != success`/non-2xx → `UpstreamGatewayException`;
  `NaN`/`Inf` → `null`.
- Add `prometheus.url` to `application.properties` with the compose default and a
  comment (mirror `gamems.url`).

Verification:
- `JAVA_HOME=... mvn -q -o test`.
- `PrometheusQueryClientTest`: feed **canned Prometheus JSON** (vector, matrix,
  empty-result, `status:error`, a `NaN` sample) into the parser (extract the
  parse step so it can be unit-tested without a live server, e.g. a package-private
  `parse(String body, ResultType expected)`), asserting label maps, sample
  parsing, `null` for NaN/Inf, and that an error envelope throws
  `UpstreamGatewayException`.

### Phase 3 — `MetricsQueryService` + PromQL catalog + DTOs
Goal: the single source of truth for PromQL and the DTO shaping.

Create:
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/metrics/MetricKey.java`
  — closed enum of metric keys (catalog above), each carrying: its **scope**
  (GAME/ENVIRONMENT/BOTH), its **PromQL template** with a `%s` selector slot, and
  whether it is multi-series. Cross-scope-only keys (`jackpots_rate_5m`,
  `jackpot_amount_rate_5m` GAME-only; `rtp_per_game_5m`, `reconnect_rate_5m`
  ENV-only) are tagged so the controller can reject cross-scope use (AD-5).
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/metrics/service/MetricsQueryService.java`
  — builds the concrete PromQL by substituting the selector
  (`gameId="<id>"` / `environmentId="<id>"`), calls `PrometheusQueryClient`,
  maps `PrometheusResult` → DTO. Resolves the readable name by querying the join
  gauge (`game_join{gameId="<id>"}` / `environment_join{environmentId="<id>"}`)
  — or fold name resolution into `summary` only and pass it through.
  - `summary(scope, id)`: runs each scalar `MetricKey` for the scope as an instant
    query (plus `bots_by_status` as a vector), assembles `MetricsSummaryDTO`.
  - `timeseries(scope, id, MetricKey, start, end, step)`: one `query_range`,
    assembles `MetricsTimeseriesDTO`.
- DTOs under `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/metrics/dto/`:
  `MetricsSummaryDTO`, `MetricsTimeseriesDTO`, `MetricSeriesDTO`, `MetricPointDTO`
  (`@Data @Builder`, AD-6).

Verification:
- `JAVA_HOME=... mvn -q -o test`.
- `MetricsQueryServiceTest`: mock `PrometheusQueryClient`; assert (a) each
  `MetricKey`'s built PromQL **string-equals the dashboard expr** (this is the
  regression guard that the API and dashboards never drift — copy the expected
  strings from the catalog), (b) vector→summary and matrix→timeseries mapping
  (incl. multi-series `bots_by_status`/`rtp_per_game_5m`/`reconnect_rate_5m`),
  (c) empty result → empty series (not error), (d) name resolution from the join
  gauge.

### Phase 4 — `MetricsController` + validation
Goal: the four public endpoints with full param validation.

Create
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/metrics/controller/MetricsController.java`
(`@RequestMapping("api/v1/metrics")`, conventions per `BotGroupController`):
- `GET /game/{gameId}/summary`, `GET /game/{gameId}/timeseries`,
  `GET /environment/{environmentId}/summary`,
  `GET /environment/{environmentId}/timeseries` (AD-4).
- `timeseries` params: `metric` (required), `from`/`to` (optional epoch-seconds or
  ISO-8601), `step` (optional duration). Apply AD-5 validation, throwing
  `BadRequestException` (→ 400) for: unknown/cross-scope metric key, `to<=from`,
  range > 30d, bad/too-small `step`, or point-count > 11000. Fill defaults
  (`to=now`, `from=now-1h`, computed `step`) when omitted.
- `@Operation` swagger annotations like the other controllers; return
  `ResponseEntity.ok(dto)`.
- No new exception handlers needed — `BadRequestException`/`UpstreamGatewayException`
  are already mapped (`RestExceptionHandler.java:80,112`).

Verification:
- `JAVA_HOME=... mvn -q -o test`.
- `MetricsControllerTest` (MockMvc, mocked `MetricsQueryService`/client):
  - `GET .../game/{id}/summary` → 200 with populated DTO.
  - `GET .../game/{id}/timeseries?metric=winnings_rate_5m&from=&to=&step=` → 200.
  - unknown metric → 400; per-env-only key on a `/game/` route (and vice-versa) →
    400; `to-from > 30d` → 400 with the cap message; `step` below floor → 400;
    point-count overflow → 400.
  - Prometheus error (mock throws `UpstreamGatewayException`) → 502.

### Phase 5 — Caching + rate-limit
Goal: protect Prometheus from public polling.

- Add a dependency-free TTL cache (AD-8) wrapping `PrometheusQueryClient` calls in
  `MetricsQueryService` (or a `CachingPrometheusQueryClient` decorator), keyed by
  resolved query+time+step, TTL from `metrics.cache.ttl-seconds=5`
  (`application.properties`).
- Add a lightweight rate-limit `HandlerInterceptor` (AD-9) scoped to
  `/api/v1/metrics/**`, returning 429 when exceeded; limit from
  `metrics.ratelimit.requests-per-minute` (sane default, e.g. 120).

Verification:
- `JAVA_HOME=... mvn -q -o test`.
- `MetricsCacheTest`: two identical calls within TTL hit `PrometheusQueryClient`
  **once** (verify mock invocation count); a call after TTL expiry re-queries.
- `MetricsRateLimitTest` (MockMvc): N+1 rapid calls → the overflow call returns
  429.

### Ordering note
Phase 1 is fully independent infra (no code). Phase 2 → 3 → 4 are a strict chain.
Phase 5 layers onto Phase 4 without changing its contract. Phases 2–5 ship as one
bot image; Phase 1 ships as the compose/prometheus change — the deploy is
**two-part** (see Verification).

## Implementation Notes / Concerns

- **`command:` overrides the image default entirely.** Restate `--config.file`
  and `--storage.tsdb.path` alongside the retention flag (AD-1) or Prometheus will
  fail to find its bind-mounted config / named-volume TSDB.
- **Targeted recreate only.** The single-compose layout means a naive `down`
  bounces Grafana/Loki/bot-manager and has hit a host port-9090 conflict before
  (`project_bot1_compose_layout.md`). Use `up -d --no-deps prometheus`.
- **PromQL must be byte-identical to the dashboards** (AD-2). Phase 3 pins this
  with string-equality tests. If a dashboard expr is later changed, both must
  move together — call this out in the API docstring.
- **Prometheus serializes sample values as strings**, including `"NaN"`,
  `"+Inf"`, `"-Inf"`. Parse to `double` and map non-finite to `null` (AD-7) so the
  JSON is valid and the UI can skip-render gaps. The RTP `or vector(0)` guard
  means `rtp_5m` returns 0 (not NaN) when there are no bets — keep the guard.
- **Selector is `gameId`/`environmentId`, names come from join gauges.** Series
  created before the gameId-label rollout lack `gameId` and are excluded by design
  (per-game.json:82). Do not try to backfill; mirror dashboard behavior.
- **Instant vs range windows are distinct from the user range.** The `[5m]`/`[1m]`/`[1h]`
  inside the templates are the metric's own rate/increase window and are fixed;
  the user's `from`/`to`/`step` only parameterize `query_range`. Do not let the
  user override the intrinsic windows.
- **Multi-series metrics** (`bots_by_status`, `rtp_per_game_5m`,
  `reconnect_rate_5m`) return multiple series — the DTO must carry per-series
  label maps so the UI can legend them (AD-6).
- **Public + unauthenticated today.** AD-9 keeps parity with `/health` but adds
  rate-limiting; the real auth decision is OI-3. Do not invent an auth scheme
  silently — surface it.
- **Timeouts on the client** (AD-7) keep a slow/stuck Prometheus from exhausting
  request threads on a public endpoint.
- **Logging** (CLAUDE.md): the metrics path is request-scoped infrastructure, not
  per-bot lifecycle — log query failures at WARN (with the failing PromQL) and
  successful queries at DEBUG; nothing on this path is INFO.

## Open Items

- **OI-1 — RESOLVED (user 2026-06-26):** use the per-scope `summary` + `timeseries`
  endpoints for game and environment (the AD-4 default, 4 endpoints).
- **OI-2 — RESOLVED (user 2026-06-26): BOTH.** Support raw `from`/`to`/`step` AND
  convenience presets (`range=1h|6h|24h|7d|30d`) resolved server-side. Range capped
  to ≤30d (retention), step validated/bounded.
- **OI-3 — RESOLVED (user 2026-06-26): mirror existing + rate-limit.** No auth (same
  as the current public `/bot-group/{id}/health`), plus a rate-limit interceptor. Do
  NOT pull in Spring Security for v1. (These expose business data — RTP/money/bot
  counts — so revisit auth if the exposure model changes, but v1 matches the existing
  public API.)
- **Caffeine vs dependency-free cache** (AD-8): plan defaults to a dependency-free
  TTL map. If the project prefers `spring-boot-starter-cache`/Caffeine for
  consistency, swap in Phase 5 (trivial).
- **OUT OF SCOPE — long-term metrics store.** `remote_write` to Thanos/Mimir or a
  Timescale sink is the documented future path if retention (>30d) or cardinality
  needs grow. The API is designed (AD-10) so the backend can be swapped behind
  `PrometheusQueryClient` without changing the UI contract — but no long-term
  store is built here.

## Verification

The deploy is **two-part**: (a) the bot image carrying the new API (Phases 2–5),
and (b) the compose/prometheus retention change (Phase 1, applied with a targeted
`docker compose up -d --no-deps prometheus`). These run on staging after deploy.

1. **Prometheus came up with 30d retention (Phase 1).**
   ```
   docker compose ps prometheus
   curl -s 'http://localhost:9090/api/v1/status/flags' | grep -o '"storage.tsdb.retention.time":"[^"]*"'
   ```
   Expect prometheus `Up`, and the flag value `"30d"`. Also confirm targeting did
   not bounce the rest of the stack:
   ```
   curl -s -o /dev/null -w '%{http_code}' http://localhost:3000/api/health
   ```
   Expect `200` (Grafana still up — single-compose smoke).

2. **bot-manager is up and the new endpoints are routed.**
   ```
   curl -s http://localhost:8080/actuator/health
   curl -s -o /dev/null -w '%{http_code}' 'http://localhost:8080/v3/api-docs'
   ```
   Expect health `{"status":"UP"}` and the OpenAPI doc reachable (the four
   `/api/v1/metrics/...` operations appear in it).

3. **bot-manager can reach Prometheus in-network.** With at least one running bot
   group (so series exist), pick a live `gameId` from the join gauge:
   ```
   GID=$(curl -s 'http://localhost:9090/api/v1/query?query=game_join' | grep -o '"gameId":"[0-9]*"' | head -1 | grep -o '[0-9]*')
   curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/api/v1/metrics/game/$GID/summary"
   ```
   Expect HTTP `200` (proves bot-manager→`http://prometheus:9090` connectivity).

4. **Per-game summary returns the dashboard metric set.**
   ```
   curl -s "http://localhost:8080/api/v1/metrics/game/$GID/summary"
   ```
   Expect HTTP 200 and a JSON body containing the resolved `gameName`, a
   `total_bots` value `>= 0`, an `rtp_5m` value `>= 0` (never NaN — the
   `or vector(0)` guard), and a `bots_by_status` map. Cross-check `total_bots`
   against the dashboard query:
   `curl -s "http://localhost:9090/api/v1/query?query=sum(bots_by_game_status{gameId=\"$GID\"})"`
   — the API value must equal the Prometheus scalar.

5. **Per-game timeseries returns chartable points.**
   ```
   curl -s "http://localhost:8080/api/v1/metrics/game/$GID/timeseries?metric=bets_placed_rate_1m&from=$(($(date +%s)-3600))&to=$(date +%s)&step=60"
   ```
   Expect HTTP 200, `metric:"bets_placed_rate_1m"`, and a non-empty `series[0].points`
   array of `{timestamp,value}` (timestamps within the requested window).

6. **Validation is enforced.**
   ```
   curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/api/v1/metrics/game/$GID/timeseries?metric=not_a_real_metric"
   curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/api/v1/metrics/game/$GID/timeseries?metric=bets_placed_rate_1m&from=$(($(date +%s)-3000000))&to=$(date +%s)&step=60"
   curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/api/v1/metrics/game/$GID/timeseries?metric=reconnect_rate_5m"
   ```
   Expect `400` for each: unknown metric; range > 30d (~34.7d here); per-env-only
   key on a `/game/` route.

7. **Per-environment endpoints work symmetrically.**
   ```
   EID=$(curl -s 'http://localhost:9090/api/v1/query?query=environment_join' | grep -o '"environmentId":"[^"]*"' | head -1 | sed 's/.*:"//;s/"//')
   curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/api/v1/metrics/environment/$EID/summary"
   ```
   Expect HTTP 200 with resolved `environmentName` and the per-env metric set
   (including `reconnect_rate_5m`).

8. **Rate-limit guard responds (Phase 5).** Fire more than the configured
   per-minute limit rapidly at one endpoint and confirm at least one `429`:
   ```
   for i in $(seq 1 130); do curl -s -o /dev/null -w '%{http_code}\n' "http://localhost:8080/api/v1/metrics/game/$GID/summary"; done | sort | uniq -c
   ```
   Expect a mix of `200` and at least one `429` (tune the count to exceed
   `metrics.ratelimit.requests-per-minute`).

9. **No errors / observability stack intact.**
   ```
   docker compose ps
   ```
   Expect every service `Up` (mongo, bot-manager, loki, promtail, grafana,
   prometheus). Confirm no metrics-path ERRORs:
   `grep -E "ERROR" <app-log> | grep -i metrics` → expect none during a healthy run.
