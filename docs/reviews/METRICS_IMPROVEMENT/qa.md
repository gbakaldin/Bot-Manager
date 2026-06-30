# QA — METRICS_IMPROVEMENT

**Verdict:** PASS
**Build:** `mvn -o clean install` → 1096 tests, 0 failures, 0 errors (was 1092 on the
pre-QA branch state; +4 added here).

## Tests added / updated

QA added 4 tests (Dev already shipped the bulk of the coverage on the branch; these
close the gaps below):

- `src/test/java/com/vingame/bot/domain/bot/core/BotTest.java` (`MoneyDrainTests`)
  - `noDrainWhenCachedPathTaken` — covers the cached `checkBalance()` branch
    (drift ≤ 1M): no server fetch, `recordFetchedBalance` never runs, no drain
    counter, anchor untouched. The diff routed both fetch sites through the new
    helper, so the "no fetch ⇒ no drain" path needed a guard.
  - `consecutiveDropsAccumulateAndReanchor` — two successive downward fetches
    accrue drain off the *latest* anchor (10M→7M = 3M, then 7M→5M = 2M, total 5M),
    pinning the `lastFetchedBalance = newBalance` re-anchor semantics.
- `src/test/java/com/vingame/bot/domain/metrics/service/MetricsQueryServiceEdgeTest.java`
  - `summaryIncludesRenamedAndNewScalarKeys` — explicit pin that `summary()`
    auto-includes `rtp` and `money_drain_per_day` (GAME + ENV), drops `rtp_5m` /
    `rtp_per_game_5m`, and excludes the multi-series `rtp_per_game` from the scalar
    map (AD-7/AD-8, focus item 4).
  - `nonRtpKeyDispatchedUnchanged` — a non-RTP key (`dead_seconds_1h`) carries no
    `$__range` token and is dispatched byte-for-byte unchanged through
    `applyWindow()` (focus item 2).

Dev-authored tests verified as covering the diff (left as-is):
- `BotTest.MoneyDrainTests`: drop accrues exact delta with full identity labels,
  net-gain floors to 0, first-fetch anchors only, deposit top-up jump floors to 0.
- `BotMetricsTest`: `incMoneyDrained` accumulates + attaches MDC tags; non-positive
  amounts never reach the registry.
- `MetricsQueryServiceEdgeTest`: summary→`30d` and timeseries→`1h` substitution,
  no `$__range` in any dispatched query, RTP `or vector(0)` → 0.0, null scalar
  carried through.
- `MetricKeyDashboardParityTest` (data-driven over every `MetricKey`): renamed
  `rtp`/`rtp_per_game` and new `money_drain_per_day` exprs match the dashboards
  byte-for-byte; env-only `rtp_per_game` not offered for GAME.
- `MetricsControllerTest` / `MetricsControllerInjectionTest`: key rename + `@Value`
  window fields set for non-Spring construction.

## Coverage of the diff

- `Bot.java` (`recordFetchedBalance` anchor) ← `BotTest.MoneyDrainTests` — drop
  accrues, deposit jump floors to 0, net-gain floors to 0, first-fetch anchors,
  cached path records nothing, consecutive drops re-anchor + accumulate, labels
  correct (5-tag `mdcTags()` set).
- `BotMetrics.incMoneyDrained` ← `BotMetricsTest` — value, tag shape, ≤0 drop.
- `MetricKey` (RTP rename + `$__range` templates + `MONEY_DRAIN_PER_DAY`) ←
  `MetricKeyDashboardParityTest` — catalog == dashboard exprs byte-for-byte.
- `MetricsQueryService.applyWindow` ← `MetricsQueryServiceEdgeTest` /
  `MetricsQueryServiceTest` — summary `30d`, timeseries `1h`, no token leak,
  non-RTP keys untouched, new keys auto-surface in summary.
- `MetricSeriesDTO` / `application.properties` / dashboard JSON — javadoc/config/
  data; exercised transitively by parity + service tests.

Dashboard hygiene check (manual): `grep` of both dashboards shows no stale
`rtp_5m` / `rtp_per_game_5m` exprs; the only `[5m]` lines are the separate
`winnings_rate_5m` panels, which are unrelated to RTP.

## Gaps

- **Live / on-server verification (plan Verification steps 2-9)** — drain series
  actually emitted by a running group, Prometheus scrape, Grafana panel render,
  RTP stability across windows. Out of scope for unit QA; deferred to the Releaser
  staging smoke. The `$__range`-never-leaves-the-API guarantee is fully covered
  unit-side, so step 7 is effectively pre-verified.
- **Divisor staleness / 24h-window behavior of `money_drain_per_day`** is a
  PromQL/Prometheus-runtime property (`increase(...[24h])`), not unit-testable; the
  template string itself is pinned by the parity test.

## Failures (if any)

None.
