# Compliance — METRICS_IMPROVEMENT

Branch: feat/metrics-improvement
Plan reviewed: `docs/plans/METRICS_IMPROVEMENT.md` (at commit 2091a67)
Diff reviewed: `git diff main..feat/metrics-improvement`

## Verdict

PASS

## Phase-by-phase

### Phase 1 — Money-drain instrumentation
Status: implemented

- `BotMetrics`: `BOT_MONEY_DRAINED_TOTAL = "bot_money_drained_total"` constant and
  `incMoneyDrained(long amount)` added. Method no-ops on `amount <= 0`, otherwise
  increments by `amount` with `mdcTags()` — exact `bot_*` counter shape (AD-3, no
  `botId`, shared series per group). Javadoc documents the floor-at-0 upward bias
  (AD-2). Matches plan step 1 precisely.
- `Bot.recordFetchedBalance(long newBalance)`: the single anchor (AD-1). When
  `lastFetchedBalance >= 0 && metrics != null`, computes
  `delta = lastFetchedBalance - newBalance`, calls
  `incMoneyDrained(Math.max(0, delta))`, then assigns `lastFetchedBalance`. Both
  server-fetch sites routed through it — `checkBalance()` and the post-deposit
  re-fetch in `deposit()` — and the `expectedCurrentBalance.set(...)` calls are
  preserved after. First-fetch anchor semantics (`lastFetchedBalance < 0`)
  unchanged. Matches plan step 2 exactly.
- Unit tests: `BotMetricsTest` covers add-with-tags and non-positive-ignored;
  `BotTest.MoneyDrainTests` covers downward-delta drain with full identity labels,
  net-gain floor-to-0, first-fetch anchor, and deposit top-up jump floor-to-0,
  all via `SimpleMeterRegistry` + `BotMdcTagsMeterFilter`. Matches plan step 3.

### Phase 2 — RTP rework (catalog + dashboards + service + props)
Status: implemented

- `MetricKey`: `RTP_5M`→`RTP` (key `"rtp"`, both scopes) and
  `RTP_PER_GAME_5M`→`RTP_PER_GAME` (key `"rtp_per_game"`, env-only). Templates
  swapped to the AD-5 `(sum(increase(...[$__range])) / sum(increase(...[$__range]))) or vector(0)`
  forms, with `sum by (gameName)` for the per-game env variant. `$__range` stored
  verbatim. No back-compat aliases — the `_5m` keys are removed outright (AD-7).
- Dashboards: `per-game.json` RTP panel and `per-environment.json` RTP-overall +
  RTP-per-game panels updated to the exact AD-5 exprs (`$gameId`/`$environmentId`
  filled, `$__range` literal). Titles dropped the `(5m)` suffix; descriptions now
  read "stake-weighted RTP over the selected range; change the time range...".
  Panel ids preserved.
- `MetricsQueryService`: `@Value` fields `rtpSummaryWindow` (default `30d`) and
  `rtpTimeseriesWindow` (default `1h`); private `applyWindow(promql, window)` =
  `promql.replace("$__range", window)`; applied in `summary()` (summary window)
  and `timeseries()` (timeseries window) before dispatch. Non-RTP keys pass
  through untouched. Matches AD-6 and plan step 3.
- `application.properties`: `metrics.rtp.summary-window=30d` and
  `metrics.rtp.timeseries-window=1h` added near the existing metrics props with
  comments. Defaults match the plan.
- Tests: parity test updated for the renamed enum constants;
  `MetricsQueryServiceTest`/`EdgeTest` stub the post-substitution PromQL and add
  explicit `$__range`-never-dispatched assertions (summary→`30d`,
  timeseries→`1h`) — covers the mandatory negative check from Implementation
  Notes. `MetricsControllerTest` summary fixture switched `rtp_5m`→`rtp`.

### Phase 3 — Money-drain surfacing (catalog + dashboards)
Status: implemented

- `MetricKey.MONEY_DRAIN_PER_DAY("money_drain_per_day", false, ...)` added with
  both AD-4 templates (`bots_by_game_status` / `bots_by_env_status` divisors,
  fixed `[24h]`, `or vector(0)`). Scalar, both scopes — auto-included in summary.
- Dashboards: "Money drain / day (avg per bot)" stat panel added to both
  `per-game.json` and `per-environment.json` with the exact AD-4 built expr per
  scope. Descriptions document the floor-at-0 upward bias (AD-2) and the
  divisor-staleness caveat.
- Parity passes automatically (catalog == dashboards byte-for-byte).

### Phase 4 — Deploy & verify
Status: out of compliance scope (deploy is the Releaser's; verification is QA's).
Note: the request only covered the 3 implementation phases.

## Cross-cutting checks

- **Parity test (byte-equality):** Intact. `MetricKeyDashboardParityTest` green —
  every renamed/new RTP and drain template, with `$gameId`/`$environmentId`
  substituted and `$__range`/`[24h]` literals, equals the dashboard `expr`
  strings. The `or vector(0)`, `sum by (gameName)` spacing all match.
- **DTO impact (AD-8):** No structural change. `MetricsSummaryDTO.metrics` is a
  flat map that auto-gains `money_drain_per_day` and `rtp` (replacing `rtp_5m`).
  The only DTO file touched is `MetricSeriesDTO.java` — a javadoc-only edit
  (`rtp_per_game_5m`→`rtp_per_game` in a comment). No shape change.
- **Config properties:** Match the plan defaults exactly (`30d` / `1h`).
- **Verification section achievability:** Each step references things that now
  exist — the counter, the new keys, the substituted windows, the dashboard
  panels. Step 1's targeted test command passes locally (88 tests, 0 failures).
- **Out-of-scope discipline:** The vs-user panels were correctly NOT added; the
  AD-7 rename was applied with no lingering `_5m` keys/aliases.

## Out-of-scope changes

The branch diff carries a large amount of unrelated content (other features'
plans/reviews, `.claude/agents`, `PLUGIN_PLAN.md`, `seed.js`, `TaiXiuMessages/`,
`docs/api/METRICS_API.md`). None of it is production code for this feature and
none touches the METRICS_IMPROVEMENT surfaces. This appears to be pre-existing
untracked/branch baggage rather than work introduced by these three commits
(the 3 feature commits touch only the planned files). Not a compliance blocker,
but the Releaser should ensure unrelated files are not swept into the deploy.

## Noted deviations (none blocking)

1. **Dashboard unit `currencyVND`** — the plan said "Unit/format = currency";
   the dev chose Grafana's `currencyVND` unit. This is a faithful concretization
   for a VND-denominated balance, consistent with the metric's currency-units
   intent (AD-2). Acceptable refinement, not drift.
2. **`MetricsControllerInjectionTest` window set** — the dev added
   `ReflectionTestUtils.setField(...)` to populate the two `@Value` RTP-window
   fields in the no-Spring, directly-constructed service instance. Without this
   the fields would be null and `$__range` substitution would not fire under
   that test harness. This is a necessary test fix, within the broad "update
   tests" scope of Phase 2, and matches the same pattern used in
   `MetricsQueryServiceTest`/`EdgeTest`. In-scope, correct.

Both deviations are matters of faithful detailing, not departures from any
Architecture Decision. No send-back warranted.
