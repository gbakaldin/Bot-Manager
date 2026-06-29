# Code Review — METRICS_API

Branch: feat/metrics-api
Reviewed diff: `git diff main..feat/metrics-api`

## Verdict

CHANGES_REQUESTED

PASS = no `bug` or `security` findings, smells/styles are advisory.
CHANGES_REQUESTED = at least one `bug` or `security` finding.

Two `security` findings (one of which is QA's OBS-1, confirmed and assessed) and
one `bug`. The rest of the feature is well-built: clean interface/decorator
separation, correct token-bucket arithmetic, robust Prometheus envelope parsing,
careful time-window capping, and no PromQL leakage in the DTOs.

## Findings

### [security] Unvalidated scope id is interpolated raw into PromQL → injection past the closed-key boundary
`src/main/java/com/vingame/bot/domain/metrics/MetricScope.java:25`
(reached from `MetricsController` path variables `gameId` / `environmentId`, via
`MetricsQueryService` → `MetricKey.promql`)

`selector(String id)` builds the predicate by raw string concatenation:

```java
return selectorLabel + "=\"" + id + "\"";
```

The `id` comes straight from the `@PathVariable` and is never validated or
escaped anywhere in the metrics package (confirmed: no `Pattern`/regex/`@Valid`
on the path vars, no sanitization in `selector`). A caller who sends an id
containing a double-quote breaks out of the label matcher and injects arbitrary
PromQL. Example:

```
GET /api/v1/metrics/game/x"} or bot_winnings_total{foo="bar/summary
```

produces a selector like `gameId="x"} or bot_winnings_total{foo="bar"` which is
valid PromQL with attacker-controlled structure. `HttpPrometheusQueryClient.encode()`
URL-encodes the whole expression for transport, so the injected quote survives
verbatim and Prometheus decodes and executes it.

The enum's own javadoc calls the closed `MetricKey` catalog "the security
boundary" — but that boundary only covers the *template*; the `%s` slot is an
open hole. Prometheus is read-only and internal, so this is not RCE or data
mutation, but it is real: arbitrary read of every metric/label in the TSDB
(information disclosure of internal metric names the API was designed to hide)
and a vector for crafting deliberately expensive queries (regex-matcher or
cross-product) against an internal store that the rate limiter only throttles by
request count, not by query cost. It also defeats the cross-scope restriction
(`key.supports(scope)`), since the injected fragment can name any series.

Fix shape: validate the id at the trust boundary before it reaches `selector`.
A strict allow-list character class (the ids in this system are UUIDs / slugs)
rejected with `BadRequestException` → 400 is the smallest correct fix, e.g. in
`MetricScope.selector` or in the controller: reject anything not matching
`[A-Za-z0-9_-]+`. Escaping the quote/backslash per PromQL string rules is the
alternative, but allow-listing is simpler and matches the "closed catalog"
philosophy of the rest of the feature.

### [security] Rate-limit bucket map is unbounded and keyed on client-controlled `X-Forwarded-For` (QA OBS-1, confirmed)
`src/main/java/com/vingame/bot/domain/metrics/ratelimit/MetricsRateLimitInterceptor.java:35,46,64`

The `buckets` `ConcurrentHashMap` is populated lazily per client key and is
**never** evicted — there is no size cap and no periodic sweep, in contrast to
`CachingPrometheusQueryClient`, which guards itself with `MAX_ENTRIES` + `sweep()`.
The key is derived from `X-Forwarded-For` first (`clientKey`), falling back to
`getRemoteAddr()`. On this public, unauthenticated endpoint an attacker fully
controls `X-Forwarded-For` and can mint a fresh, distinct value on every request,
creating an unbounded number of `Bucket` entries that are never reclaimed. That is
a slow memory-exhaustion DoS — and the rate limiter, the component meant to
*protect* the endpoint, becomes the amplifier. The class javadoc asserting
"the map's cardinality is bounded by the number of distinct client IPs" is the
exact wrong assumption once the key is a spoofable header.

Severity: this is the headline hardening gap of the feature. The whole point of
AD-9 is to keep a public endpoint from being abused; an unbounded per-request
allocation keyed on a forgeable header undercuts that goal. I rate it `security`
(memory-DoS on an unauthenticated surface), matching QA's OBS-1.

Fix shape (two parts, both warranted):
1. Bound the map. Mirror the cache: a `MAX_BUCKETS` cap with a sweep that drops
   buckets that are full (i.e. idle long enough to have refilled to capacity —
   those carry no rate-limit state and are safe to evict), or a scheduled sweep
   on a virtual thread. A hard cap with eviction is the minimum.
2. Stop trusting `X-Forwarded-For` blindly. Either key on `getRemoteAddr()` (the
   real peer — the LB/ingress), or only honor `X-Forwarded-For` from a
   configured trusted-proxy set. As written, per-IP limiting is also trivially
   *bypassable* (rotate the header to get a fresh full bucket every request), so
   the limiter does not even achieve its stated function against a hostile
   client — fixing the key fixes both the DoS and the bypass.

Note on the arithmetic itself (asked): the token-bucket math is **correct**.
Refill is `capacity/(60·1e9)` tokens per nanosecond, accumulated as
`min(capacity, tokens + elapsed·refillPerNano)` and consumed in unit steps;
`tokens` is a `double` for exact continuous refill; `lastRefillNanos` advances
monotonically via `System.nanoTime()`; a new bucket starts full so burst
capacity equals the per-minute limit. The per-`Bucket` `synchronized` block is a
sound choice (the `ConcurrentHashMap` handles cross-key concurrency, only the
per-bucket arithmetic needs serializing) and there is no shared mutable state
across buckets. The only defect is the lifecycle/keying above, not the algorithm.

### [bug] Instant-query cache key includes the per-second timestamp, largely defeating the TTL cache for `summary`
`src/main/java/com/vingame/bot/infrastructure/client/prometheus/CachingPrometheusQueryClient.java:54-58`

The comment states the key "excludes the instant ... within a TTL window the
'now' value is treated as the same logical query," but the code does the
opposite for the only callers that exist:

```java
String key = "i " + promql + " " + (time == null ? "now" : time.getEpochSecond());
```

`MetricsQueryService.summary()` and `resolveScopeName()` always call
`queryInstant(promql, now)` with `now = Instant.now()` (never `null`), so the
epoch-**second** is folded into the key. Two summary polls 1+ seconds apart
therefore produce different keys and **miss** the cache even though they fall
inside the 5s TTL — the dedup only works for requests landing in the same wall-clock
second. That is the opposite of the AD-8 intent ("identical queries served from
cache for a few seconds") and means a UI polling every 2–3s amplifies onto
Prometheus almost unthrottled, which is precisely what the cache was added to
prevent.

The `null`-time branch ("now") would behave correctly, but no caller passes
`null`. Either the comment or the code is wrong; given the stated design intent,
the code is the defect.

Fix shape: drop the timestamp from the instant key when the caller intends "now"
(e.g. service passes `null` for live queries and reserves an explicit `Instant`
only for true historical lookups), or normalize the cache instant to a TTL bucket
boundary rather than the raw second. Range queries are unaffected (explicit
start/end are legitimately part of the key).

## Notes

- Good: `HttpPrometheusQueryClient` envelope handling is thorough — non-2xx and
  `status != "success"` both raise `UpstreamPrometheusException` (→ 502, mapping
  confirmed in `RestExceptionHandler`), `NaN`/`±Inf` samples are coerced to
  `null` so the emitted JSON stays valid, unparseable bodies are caught, and
  `InterruptedException` correctly re-sets the interrupt flag. Connect/read
  timeouts (3s/5s) are sensible for a request-scoped public path. `HttpClient` is
  built once and reused. Catching `IOException | InterruptedException` (not broad
  `Exception`) is appropriate and justified.
- Good: PromQL never reaches the client. DTOs (`MetricsSummaryDTO`,
  `MetricsTimeseriesDTO`, `MetricSeriesDTO`, `MetricPointDTO`) carry only keys,
  labels, values and timestamps; `__name__` is stripped in `toSeriesDTO`. The
  502 message preserves Prometheus' own error string but not the internal URL.
- Good: `TimeWindow.resolve` precedence is correct and well-tested in shape —
  range vs from/to mutual exclusion → 400, zero/negative window → 400, 30d cap,
  `MIN_STEP` floor, and the `MAX_POINTS` guard all fire before the upstream call.
  Step parsing (plain seconds / suffix / ISO-8601, explicit `ms` rejection) is
  defensive. No under/over-cap logic issues found.
- `MetricKey.promql` counting `%` chars to size the format args
  (`template.chars().filter(c -> c == '%').count()`) is clever but fragile — any
  future template containing a literal `%` (e.g. a `%%` escape or a `%`-bearing
  label value) would miscount and throw `MissingFormatArgumentException` /
  `UnknownFormatConversionException` at runtime. Today every template uses only
  `%s` so it is correct, but a named-placeholder substitution
  (`template.replace("{sel}", selector)`) would be both clearer and robust. Minor
  smell, not blocking.
- `MetricsWebConfig` registers the interceptor on `/api/v1/metrics/**` (leading
  slash) while the controller's `@RequestMapping` is `api/v1/metrics` (no slash,
  consistent with `BotGroupController`/`EnvironmentController`). Spring normalizes
  both so the interceptor matches correctly; no action needed, just noting the
  cosmetic inconsistency.
- `CachingPrometheusQueryClient`'s `get()` deliberately avoids `computeIfAbsent`
  to not hold the bin lock across the HTTP call, accepting a benign double-load on
  a cold miss — a reasonable, well-documented trade-off. Thread-safety is sound
  and the `MAX_ENTRIES` sweep is correct.
- Question for the author: are `gameId`/`environmentId` guaranteed UUID/slug
  shaped at every call site, or can free-form ids occur? The answer determines
  whether the injection fix can be a strict allow-list (preferred) or needs full
  PromQL string-escaping.

---

# Re-review

Branch: feat/metrics-api
Reviewed diff: `git diff 0ac7c83..HEAD` (commits `88dabdd`, `cb9a0f3`, `73e51d1`)

## Verdict

PASS

All three prior blocking findings (2 `security` + 1 `bug`) are properly closed.
One new advisory `smell` on the rate-limit bound (non-blocking) — see below.

## Disposition of prior findings

### [security] PromQL injection — CLOSED
`MetricScope.selector(String id)` (`MetricScope.java:55`) now validates against
`VALID_ID = \$?[A-Za-z0-9_-]+` and throws `BadRequestException` on any miss or
`null`. Verified:
- **Single chokepoint, no bypass.** Every selector in the package is built via
  `scope.selector(id)`. The only two call sites are `MetricKey.promql`
  (`MetricKey.java:138`) and `MetricsQueryService.resolveScopeName`
  (`MetricsQueryService.java:140`). The summary path (`queryInstant` for each
  scalar key + `bots_by_status` + scope-name resolution) and the timeseries path
  (`queryRange` + scope-name resolution) both route exclusively through these,
  for both `GAME` and `ENVIRONMENT`. No code constructs a `label="..."` matcher
  independently.
- **Regex prevents breakout.** `matches()` anchors the whole string; the class
  `[A-Za-z0-9_-]` admits no double-quote, backslash, brace, whitespace, or
  newline (`.`/`DOTALL` not used), so the `id="<...>"` matcher cannot be escaped.
- **Leading `$` is safe.** Permitted only to render the Grafana template-variable
  form pinned by the parity test; `$` carries no breakout meaning inside a quoted
  PromQL label value, and the quote/backslash exclusion is what closes the hole.
- **Null handled** — explicit `id == null` guard before the matcher.
- **400 mapping confirmed** — `RestExceptionHandler.handleBadRequest`
  (`RestExceptionHandler.java:80`) maps `BadRequestException` → `ResponseEntity.badRequest()`.

### [bug] Instant-query cache key defeated the TTL — CLOSED
- **Live "now" dedupes within TTL.** `MetricsQueryService.summary` now passes
  `null` for every live instant query (`MetricsQueryService.java:56,63,67`), and
  uses the real `Instant.now()` only for the DTO's `generatedAt`
  (`MetricsQueryService.java:75`). `CachingPrometheusQueryClient.queryInstant`
  maps `time == null` to a time-independent key
  (`CacheKey.instant(promql, null)`, `CachingPrometheusQueryClient.java:65-66`),
  so polls seconds apart collapse to one key inside the 5s TTL.
- **Explicit historical instants stay distinct** — a non-null `Instant` folds the
  epoch-second into the key, kept separate from live "now" and from each other.
- **Record covers all fields, no collision.** `CacheKey(Kind, String promql,
  Long start, Long end, Long step)` is a record; generated `equals`/`hashCode`
  cover all five components. `kind` separates the INSTANT and RANGE namespaces,
  so an instant and a range with the same promql never collide.
- **File is genuinely text now.** `file(1)` reports "Java source, UTF-8 text" for
  both the working tree and the `HEAD` blob; a NUL-byte scan finds zero NUL bytes
  (5838 bytes with and without NUL-stripping); `git grep -I` (skips binaries)
  returns the file's lines, i.e. git classifies the HEAD blob as text. The `Bin`
  marker in `git diff 0ac7c83..HEAD --stat` and the `- -` numstat are artifacts of
  the *start* of the range still holding the old binary blob — git flags a diff
  binary if either side is; the committed result is text. CLOSED.

### [security] Rate-limit bucket map — CLOSED (keying + arithmetic); residual smell on the bound
- **Keying no longer spoofable — CLOSED.** `clientKey`
  (`MetricsRateLimitInterceptor.java:98`) reads only `getRemoteAddr()` (null →
  `"unknown"`); `X-Forwarded-For` is no longer consulted, so the header-rotation
  bypass and the header-driven unbounded growth are both gone. The class javadoc
  documents the proxy trade-off (behind a shared LB this collapses to a coarse
  near-global limit) honestly; acceptable for the v1 "shield internal Prometheus"
  goal.
- **Token-bucket arithmetic still correct after the change.** `tryConsume`
  (`:119`) refills `min(capacity, tokens + elapsed·refillPerNano)` then consumes
  `1.0`; `lastRefillNanos` advances monotonically; new buckets start full. The new
  `isFull` predicate (`:132`) recomputes refill read-only (does not mutate
  `lastRefillNanos`) — a clean pure predicate. The `get`-then-`computeIfAbsent`
  pattern (`:64-69`) is race-safe.

## Findings

### [smell] Bucket-map bound is sweep-on-insert with no hard ceiling; the sweep is idleness-dependent
`src/main/java/com/vingame/bot/domain/metrics/ratelimit/MetricsRateLimitInterceptor.java:66-69,93-95`

`MAX_BUCKETS` only *triggers* `sweepFullBuckets()` on insert; it is not a hard
cap. If the sweep frees nothing, the code still falls through to
`computeIfAbsent` and inserts, so the map can grow past `MAX_BUCKETS`. Eviction
eligibility is `isFull()` — i.e. a bucket is reclaimable only after it has been
idle long enough to refill to capacity (~`60/capacity` s ≈ 0.5 s at the default
120/min). Under a sustained flood of *distinct, never-reused* source addresses
arriving faster than they refill (feasible from a directly-exposed host facing an
IPv6 /64), every bucket at sweep time is recently-active and therefore not full,
so the sweep removes ~nothing and the map keeps growing.

This is materially weaker than the original (XFF) defect — that vector was free
over a single connection via a forged header; this one requires genuinely
distinct *completed-TCP* peer addresses (spoofed sources can't finish the
handshake to reach `preHandle`), and any proxy/LB in front collapses all clients
to one key, making the map size 1. Each `Bucket` is tens of bytes, and the sweep
reclaims aggressively the moment a flood pauses. So this is a hardening gap, not
a live exploitable DoS on a normal deployment — hence `smell`, not blocking
`security`.

Fix shape (optional, for a true bound): after `sweepFullBuckets()`, if the map is
still at the cap, enforce a hard ceiling — e.g. evict the entry with the oldest
`lastRefillNanos` (closest to refilled), or simply skip rate-limiting / reject
when saturated, or run a periodic scheduled sweep on a virtual thread that also
drops sufficiently-old buckets regardless of fullness. Mirrors the cache's
guarantee (the cache's TTL makes *every* entry reclaimable after 5 s; this map's
eligibility is attacker-controllable via never reusing a key).

## Notes

- The keying/cache/injection changes are accompanied by focused tests
  (`MetricScopeTest`, `MetricsControllerInjectionTest`,
  `CachingPrometheusQueryClientTest`, expanded `MetricsRateLimitInterceptorUnitTest`).
  Test adequacy is QA's call, not mine, but the diff is self-consistent with the
  documented behavior.
- The prior advisory on `MetricKey.promql` counting `%` chars to size format args
  is unchanged and remains a minor smell (still correct for today's all-`%s`
  templates).

## Re-review verdict

PASS — no remaining `bug` or `security` findings. One advisory `smell` (rate-limit
bound) left to author discretion.
