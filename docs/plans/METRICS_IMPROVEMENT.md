# METRICS_IMPROVEMENT

Q3 roadmap #2 — "metrics improvement" MVP. Two new/changed metrics that must
stay byte-identical across three surfaces: bot instrumentation
(`BotMetrics` + bot core), the Grafana provisioned dashboards
(`per-game.json` / `per-environment.json`), and the frontend metrics API
(`MetricKey` catalog + `MetricsQueryService` + DTOs).

## Goal

Ship the two product-UI panels we can actually back with our own data:
**(1) Money drain / day** — per-bot net real-balance depletion, observed from
balance/deposit deltas (deliberately NOT derived from
`bot_bet_amount_total − bot_winnings_total`, because balance is independent
ground truth and per-round accounting just had a bug); and **(2) a stable
long-term RTP** to replace the current 5m-window RTP that swings 159%→50% within
half an hour. Both must aggregate cleanly per group / per game / per environment
via the existing `gameId`/`gameName`/`environmentId` labels, slot into the two
provisioned dashboards, and be exposed through the metrics API under the same
PromQL the dashboards use (the `MetricKeyDashboardParityTest` contract). All the
"vs user" panels are out of scope (no user-side data) — see Out of Scope.

## Findings — Current State

**Instrumentation**
- `BotMetrics` is the single holder of every `bot_*` counter; each `inc*` reads
  identity tags (`botGroupId`, `environmentId`, `gameType`, `gameId`, `gameName`)
  from MDC via `mdcTags()` and interns one series per tag-set
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java:90`).
  Note: `botId`/`botUserName` are deliberately NOT tags — **all bots in a group
  share one series per counter** (cardinality cap, class javadoc lines 40-49).
- Existing money counters already use this shape: `bot_bet_amount_total`
  (`incBetsPlaced`, line 210), `bot_winnings_total` (`incBotWinnings`, line 236).
  `bot_winnings_total` was just corrected to `winnings = GX − gR`, so the RTP
  inputs are now right.
- Balance path lives in `Bot`:
  - `deposit()` tops up a fixed `1_000_000_000L`, then re-fetches the real
    balance into `lastFetchedBalance` and `expectedCurrentBalance`
    (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:239`).
    It already calls `metrics.incBotAutoDeposit(...)`.
  - `checkBalance()` fetches the real balance from the API gateway only when the
    local drift exceeds 1M, else returns the cached `expectedCurrentBalance`
    (`Bot.java:262`). `lastFetchedBalance` is `volatile long = -1`,
    `expectedCurrentBalance` is an `AtomicLong` (`Bot.java:76-77`).
  - Auto-deposit trigger: `BettingMiniGameBot` line 300-304 and `SlotMachineBot`
    line 159-163 call `checkBalance()` then `deposit()` when
    `balance < getMinBalance()` (default 5M, `Bot.java:283`).
- `InfoGaugeRefresher` owns the aggregate gauges `bots_by_game_status` /
  `bots_by_env_status` (and the `game_join` / `environment_join` name joins),
  refreshed every 10s, on the MDC-tag exclusion allow-list
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/InfoGaugeRefresher.java:101`).
  These are the per-scope bot-count series the drain average divides by.

**Metrics API**
- `MetricKey` is the closed PromQL catalog; the current RTP keys are `RTP_5M`
  (both scopes) and `RTP_PER_GAME_5M` (env only), both `rate(...[5m])`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/metrics/MetricKey.java:51,93`).
  `promql(scope,id)` fills every `%s` slot with the validated selector
  (line 132); the selector is injection-guarded by `MetricScope.selector`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/metrics/MetricScope.java`),
  which also permits a leading `$` so the literal `$gameId` form renders for the
  parity test.
- `MetricsQueryService.summary` iterates every scalar key the scope supports and
  runs one instant query each into `MetricsSummaryDTO.metrics`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/metrics/service/MetricsQueryService.java:52`).
  `timeseries` runs one `query_range`
  (line 84). The service owns NO PromQL — all of it comes from `MetricKey`.
- `MetricsSummaryDTO.metrics` is a flat `Map<String,Double>` (`metricKey →
  value`) — new scalar keys appear automatically, no structural change
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/metrics/dto/MetricsSummaryDTO.java:33`).
- `MetricKeyDashboardParityTest` string-equals each key's built PromQL (with
  `$gameId`/`$environmentId` substituted) against the set of dashboard `expr`
  strings — byte-for-byte
  (`/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/metrics/MetricKeyDashboardParityTest.java:41`).

**Dashboards & deploy**
- RTP panel expr in `per-game.json:259` is exactly the `RTP_5M` template with
  `$gameId` filled. `per-environment.json` mirrors it plus `rtp_per_game_5m`.
- Grafana mounts `./grafana/provisioning` as a bind mount
  (`/Users/gleb/IdeaProjects/Bot/docker-compose.yml:64`); dashboards auto-load on
  Grafana container start. Per the bot-1 single-compose layout, a bot redeploy
  takes the whole stack (Grafana/Prometheus/Loki) down and back up, which is what
  reloads dashboards (GRAFANA_PER_GAME_ENV_DASHBOARDS.md Verification step 8).
- Existing metrics props live at `application.properties:31-44`
  (`prometheus.url`, `metrics.cache.ttl-seconds`, `metrics.ratelimit...`).
  Prometheus retention is 30d (METRICS_API AD-1, `docker-compose.yml:76`).

## Per-aspect readiness / mapping

| Aspect | Status | Notes |
|---|---|---|
| Drain counter `bot_money_drained_total` | ready | New `inc*` in `BotMetrics`, same `mdcTags()` shape — no new infra. |
| Drain recording point (balance fetch) | ready | Both server-fetch sites already in `Bot` (`checkBalance`, `deposit`). |
| Drain negative (net-gain) handling | ready | Floor at 0 in the helper; documented bias. |
| Drain per-bot averaging | ready | Divide by `bots_by_game_status`/`bots_by_env_status` (already emitted). |
| `money_drain_per_day` MetricKey (game+env) | ready | Scalar key, both scopes; auto-included in summary. |
| Drain dashboard panels | ready | One stat panel each in per-game/per-env. |
| RTP inputs correctness | ready | `bot_winnings_total` fix already deployed. |
| RTP range-based rework | partial | Needs `$__range` token + service substitution + property windows. |
| RTP key rename (`rtp_5m`→`rtp`) | ready | Locked: UI repo switches keys in lockstep, no back-compat aliases — AD-7. |
| Parity test under `$__range` | ready | `$__range` is a shared literal in both template and dashboard expr. |
| Two-payload deploy | ready | Bot image (instrumentation) + dashboard JSON sync + Grafana reload. |
| vs-user panels | blocked | No user-side data — Out of Scope. |

## Architecture Decisions

**AD-1. Drain is recorded from observed server-balance deltas, floored at 0.**
A single private helper in `Bot` is the only place `lastFetchedBalance` is
updated from a server fetch. On each fetch it computes
`delta = previousFetched − newFetched` and increments
`bot_money_drained_total` by `max(0, delta)`. This fires at BOTH server-fetch
sites — `checkBalance()` (line 269) and the post-deposit re-fetch in `deposit()`
(line 248) — and nowhere else. Rationale: the deposit top-up jump
(`~5M → ~1.005B`) yields a large NEGATIVE delta, which floors to 0, so top-ups
never register as negative drain and never need special-casing; every other
fetch captures real burn. This is the user's "balance/deposit-delta" mechanism,
expressed as one anchor instead of two parallel rules.

**AD-2. The metric is a monotonic counter `bot_money_drained_total` (currency
units), NOT a gauge.** A counter makes `drain/day = increase(...[24h])`, resets
on process restart are corrected by `increase()`, and it aggregates/averages
cleanly per group/game/env via label sums. A gauge cannot yield a clean per-day
burn rate over a Prometheus range. Net-gain periods contribute 0 (AD-1 floor);
this biases the counter UPWARD relative to true net depletion (gain windows that
would offset drain are dropped). That bias is intended: "money drain" is a burn
gauge, not net P&L — net P&L would be `bet − winnings`, which the user explicitly
rejected for this metric. Document the bias in the BotMetrics javadoc and the
panel description.

**AD-3. Labels are the standard `mdcTags()` set.** `bot_money_drained_total`
carries `botGroupId`, `environmentId`, `gameType`, `gameId`, `gameName` exactly
like the other `bot_*` counters — no `botId`. All bots in a group therefore share
one series, so `increase()` over that series already sums the group's drain;
`sum(...)` across a game/env sums all groups. This is what lets the same metric
slot into both per-game and per-env dashboards with no extra work.

**AD-4. "Drain per day, averaged per bot in the group" PromQL.** Total drain over
24h divided by the current bot count for the scope:
- GAME: `(sum(increase(bot_money_drained_total{%s}[24h])) / sum(bots_by_game_status{%s})) or vector(0)`
- ENV:  `(sum(increase(bot_money_drained_total{%s}[24h])) / sum(bots_by_env_status{%s})) or vector(0)`

`[24h]` is fixed (it IS "per day") — NOT `$__range`. The divisor differs by scope
(`bots_by_game_status` vs `bots_by_env_status`), mirroring the existing
`TOTAL_BOTS` key. `or vector(0)` covers the no-bots / no-series case (empty /
empty → 0). This is a scalar key, both scopes → it lands in
`MetricsSummaryDTO.metrics` automatically.

**AD-5. RTP becomes a ratio-of-sums over the query window via `increase()`,
using the `$__range` Grafana token as a shared literal.** Dashboard panels:
- GAME/ENV: `(sum(increase(bot_winnings_total{%s}[$__range])) / sum(increase(bot_bet_amount_total{%s}[$__range]))) or vector(0)`
- ENV per-game (multi-series): `(sum by (gameName) (increase(bot_winnings_total{%s}[$__range])) / sum by (gameName) (increase(bot_bet_amount_total{%s}[$__range]))) or vector(0)`

Using `$__range` makes the dashboard panel follow the time picker, so per-hour /
per-day / since-update RTP is just a range change — no dedicated hour/day
metrics. `increase()` corrects counter resets on process restart. This is a true
stake-weighted ratio-of-sums (converges to real RTP over the window), NOT a mean
of per-session ratios.

**AD-6. The metrics API resolves `$__range` to a concrete window per endpoint.**
`MetricKey` templates store the literal `$__range` (so `promql()` emits it and
the parity test matches the dashboard byte-for-byte). `MetricsQueryService`
replaces `$__range` with a real duration immediately before dispatch:
- `summary` (instant `query`): `metrics.rtp.summary-window` (default `30d` =
  retention) → the headline RTP is the long-term value, not 5m.
- `timeseries` (`query_range`): `metrics.rtp.timeseries-window` (default `1h`) →
  each point is a 1h SLIDING-window ratio.
Sliding (not cumulative-since-start) is recommended: a cumulative ratio flattens
and hides drift after a config/update change, defeating the stated "see whether a
game drifts" goal; a 1h sliding window is stable yet still shows drift. Both
windows are configurable properties. Replacement is a literal
`String.replace("$__range", window)` (Java `replace` is non-regex; `$` is safe).

**AD-7. Rename `rtp_5m`→`rtp` and `rtp_per_game_5m`→`rtp_per_game` (LOCKED, no
back-compat aliases).** The semantics changed materially (5m sliding →
range-based long-term); keeping a `_5m` suffix on a non-5m metric is actively
misleading and a silent expr swap under the same key would mislead any cached
consumer. The wire key the UI sends changes — this is a breaking change for the
separate UI repo, which has confirmed it will switch the key names in lockstep
with this deploy. There is NO fallback that keeps the old `*_5m` keys: the
`MetricKey` enum (constant key string + the closed catalog), the dashboard panel
`expr` strings, and `MetricKeyDashboardParityTest` all use the new names only,
and the old `rtp_5m` / `rtp_per_game_5m` keys are removed outright. No new
scope-only keys: `rtp` stays both scopes, `rtp_per_game` stays env-only.

**AD-8. No DTO shape change.** `MetricsSummaryDTO.metrics` gains the keys
`money_drain_per_day`, `rtp` (replacing `rtp_5m`). `MetricsTimeseriesDTO` is
unaffected. Nothing structural.

## Plan

### Phase 1 — Money-drain instrumentation (ship bot image first, data accrues)
Files: `BotMetrics.java`, `Bot.java`, `BotMetricsTest.java`, `BotTest`
(or equivalent core test).
1. In `BotMetrics`: add constant `BOT_MONEY_DRAINED_TOTAL =
   "bot_money_drained_total"` and method `incMoneyDrained(long amount)` that
   no-ops when `amount <= 0` and otherwise increments by `amount` with
   `mdcTags()` (mirror `incBotDeadSeconds`, BotMetrics.java:296). Javadoc the
   floor-at-0 upward bias (AD-2).
2. In `Bot`: introduce a private helper, e.g.
   `private void recordFetchedBalance(long newBalance)` that, when
   `lastFetchedBalance >= 0`, computes `delta = lastFetchedBalance − newBalance`,
   calls `metrics.incMoneyDrained(Math.max(0, delta))` (null-guarded like the
   existing `metrics != null` checks), then assigns `lastFetchedBalance =
   newBalance`. Route BOTH server fetches through it:
   - `checkBalance()` line 269-275: replace the direct
     `lastFetchedBalance = apiGatewayClient.getBalance(...)` with
     `recordFetchedBalance(apiGatewayClient.getBalance(...))` (keep the
     `expectedCurrentBalance.set(...)` after).
   - `deposit()` line 248-253: same — the post-deposit re-fetch goes through the
     helper, so the top-up jump floors to 0.
   Do not change the `lastFetchedBalance < 0` guard semantics (first-ever fetch
   sets the anchor, records nothing).
3. Unit tests: drain recorded on a downward fetch delta; zero recorded on the
   deposit top-up jump (upward); zero on net-gain fetch; nothing on the first
   fetch (anchor). Assert the counter name/tags via a `SimpleMeterRegistry`.
4. Deploy shape: this phase ships the **bot image** only (no dashboard/API yet).
   Data starts accruing immediately so Phase 3/4 verification has non-zero drain.

### Phase 2 — RTP rework (catalog + dashboards + service + props)
Files: `MetricKey.java`, `MetricsQueryService.java`, `application.properties`
(Dev edits — outside Architect's write scope), `per-game.json`,
`per-environment.json`, `MetricKeyDashboardParityTest`, `MetricsQueryServiceTest`,
`MetricsQueryServiceEdgeTest`.
1. `MetricKey`: rename `RTP_5M`→`RTP` (key string `"rtp"`) and
   `RTP_PER_GAME_5M`→`RTP_PER_GAME` (key string `"rtp_per_game"`); swap their
   templates to the AD-5 `increase(...[$__range])` forms. Update the javadoc
   line-number citations.
2. Dashboards: replace the RTP panel `expr` in `per-game.json` (the RTP stat
   panel near line 259) and the two RTP panels in `per-environment.json`
   (single-RTP near line 259 and `rtp_per_game` near line 441) with the exact
   AD-5 strings (`$gameId`/`$environmentId` filled, `$__range` literal). Keep
   panel ids/titles; update descriptions to "stake-weighted RTP over the selected
   range; change the time range for hourly/daily/since-update".
3. `MetricsQueryService`: add `@Value` fields for `metrics.rtp.summary-window`
   (default `30d`) and `metrics.rtp.timeseries-window` (default `1h`); add a
   private `applyWindow(String promql, String window)` returning
   `promql.replace("$__range", window)`; call it on the built PromQL in
   `summary()` (summary window) and `timeseries()` (timeseries window) before
   passing to the client. Non-RTP keys (no `$__range`) pass through untouched.
4. `application.properties`: add the two props near line 36 with the defaults
   above and a one-line comment each.
5. Tests: update parity test expectations (it auto-builds from `MetricKey`, so it
   only needs the dashboards to match — no test-code change beyond any hardcoded
   key names); update `MetricsQueryServiceTest`/edge tests that reference
   `rtp_5m`/`RTP_5M` and assert the `$__range` substitution (summary →`30d`,
   timeseries →`1h`).
6. Independently shippable: RTP inputs already exist, so this can deploy without
   Phase 1.

### Phase 3 — Money-drain surfacing (catalog + dashboards)
Files: `MetricKey.java`, `per-game.json`, `per-environment.json`,
`MetricKeyDashboardParityTest`.
1. `MetricKey`: add `MONEY_DRAIN_PER_DAY("money_drain_per_day", false, ...)` with
   the AD-4 GAME and ENVIRONMENT templates.
2. Dashboards: add a "Money drain / day (avg per bot)" stat panel to
   `per-game.json` and `per-environment.json` with the exact AD-4 built expr per
   scope. Unit/format = currency. Description notes the floor-at-0 bias (AD-2).
3. Parity test passes automatically once dashboard exprs match. Summary endpoint
   auto-includes `money_drain_per_day` (scalar, both scopes).
4. Depends on Phase 1 data for live verification (counter must exist + accrue).

### Phase 4 — Deploy & verify
Two-payload deploy (GRAFANA_PER_GAME_ENV_DASHBOARDS deploy shape):
1. Bot image: built from Phases 1-3 (instrumentation + catalog + service + props).
2. Dashboard JSON: sync the repo `grafana/provisioning/dashboards/*.json` to
   Bot-1's bind-mounted `./grafana/provisioning`, then restart the Grafana
   container (a full stack redeploy does this per the single-compose layout).
3. Run the Verification section. Re-verify the co-located stack
   (Grafana/Prometheus/Loki UP) since a bot redeploy cycles all of it.

## Implementation Notes / Concerns

- **`$__range` must NOT reach Prometheus.** It is a Grafana-only token; the API
  would 400/parse-error if `[$__range]` is sent. AD-6's `applyWindow` substitution
  is mandatory on every code path that dispatches an RTP key. Add an edge test
  that asserts no dispatched PromQL contains `$__range`.
- **Parity is byte-exact.** The `or vector(0)`, the `sum by (gameName)` spacing,
  the `[$__range]`/`[24h]` literals, and the `%s` selector must produce a string
  identical to the dashboard `expr`. Copy the dashboard string into the template
  (with `%s` for the id), or vice versa — do not retype.
- **Shared-series subtlety.** Because no `botId` tag exists, `sum(increase(
  bot_money_drained_total[24h]))` over a single group is `increase()` of one
  series, not a sum across N bot series. The division by `bots_by_game_status`
  is what converts group-total drain to per-bot average. Do not also `sum by
  (botId)` — there is no such label.
- **Divisor staleness.** `bots_by_*_status` is a live gauge; averaging 24h drain
  by the current bot count is approximate if the group was resized mid-window.
  Acceptable for an MVP burn indicator; note it in the panel description.
- **Counter reset on redeploy.** Every deploy restarts the bot process and resets
  all counters. `increase()` handles this; the headline RTP/drain will briefly be
  based on a partial window right after deploy. Expected, not a bug.
- **`checkBalance` fetch frequency.** Drain is only sampled when `checkBalance`
  actually hits the server (drift > 1M) or on deposit. Between fetches the counter
  is flat. Over 24h that is plenty of samples for a daily rate; do not lower the
  1M threshold for metrics' sake.
- **Logging.** No new INFO lines. The drain helper is metric-only; keep the
  existing DEBUG balance logs. Per CLAUDE.md, per-bot detail stays DEBUG.
- **UI key rename (AD-7).** The `rtp_5m`→`rtp` / `rtp_per_game_5m`→`rtp_per_game`
  wire-key change is breaking with no back-compat alias. The UI repo switches in
  lockstep with this deploy; cut both over together, or the UI's RTP panel
  silently 400s on the (now removed) old key.

## Open Items

- **AD-7 UI coordination — RESOLVED:** the UI repo will switch the wire keys
  `rtp_5m`→`rtp` and `rtp_per_game_5m`→`rtp_per_game` in lockstep with this
  deploy. No back-compat aliases; the old keys are removed. No further action.
- **RTP timeseries window value:** default `1h` sliding (AD-6). Confirm `1h` vs
  `24h` smoothing if the UI's RTP chart spans days — adjustable via
  `metrics.rtp.timeseries-window` with no code change.
- **Drain window:** fixed `[24h]` (AD-4). If the UI later wants a range-following
  drain, switch to `$__range` + AD-6 substitution — deferred.

## Out of Scope (vs-user panels)

The product UI carries several "vs user" comparison panels that require
**user-side (real-player) data we do not collect**. They are out of scope and
will remain "N/A":
- **Bot vs user RTP share** — needs real-player wagered/returns per game; we only
  observe bot stakes/winnings.
- **AVG bet vs user** — needs real-player average bet; not available.
- **Win share vs user** — needs real-player win counts; not available.
None of these can be derived from bot-manager metrics; implementing them would
require a feed from the game server's user-side accounting, which is a separate
upstream integration and not part of this MVP.

## Verification

Run on staging after the Phase 4 deploy. This feature HAS on-server verification
beyond the universal smoke test. Replace `<host>` with the actuator host, and
`<prom>` with the Prometheus host (`:9090`), `<api>` with the metrics-API base.

1. **Build + all tests incl. parity.**
   ```
   JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
     mvn -q test -Dtest='MetricKeyDashboardParityTest,MetricsQueryServiceTest,MetricsQueryServiceEdgeTest,BotMetricsTest'
   ```
   Expect BUILD SUCCESS; parity test green (catalog == dashboards byte-for-byte).

2. **Drain counter is emitted.** After a bot group has run long enough to take a
   downward balance fetch (a few rounds):
   ```
   curl -s '<host>/actuator/prometheus' | grep '^bot_money_drained_total'
   ```
   Expect at least one line carrying `gameId="..."`, `gameName="..."`,
   `environmentId="..."` and a value `> 0`.

3. **Prometheus scraped the drain series.**
   ```
   curl -s 'http://<prom>:9090/api/v1/query?query=sum(increase(bot_money_drained_total[24h]))'
   ```
   Expect `data.result[0].value[1] > 0`.

4. **Drain-per-day panel PromQL returns a finite value.** Pick a running
   `<gameId>`:
   ```
   curl -s -G 'http://<prom>:9090/api/v1/query' \
     --data-urlencode 'query=(sum(increase(bot_money_drained_total{gameId="<gameId>"}[24h])) / sum(bots_by_game_status{gameId="<gameId>"})) or vector(0)'
   ```
   Expect a numeric `value[1]` `>= 0` (per-bot daily drain).

5. **API summary exposes both new keys and stable RTP.**
   ```
   curl -s '<api>/api/v1/metrics/game/<gameId>/summary'
   ```
   Expect `metrics.money_drain_per_day` present and `>= 0`; expect `metrics.rtp`
   present (and `metrics.rtp_5m` absent); RTP should be a plausible long-term
   ratio (roughly 0.8–1.0 for a healthy game), NOT a wild value.

6. **RTP stability across windows = range change only.** Query the same scope's
   timeseries over 1h then 24h and confirm the headline barely moves between
   adjacent points (no 159%→50% swings):
   ```
   curl -s '<api>/api/v1/metrics/game/<gameId>/timeseries?metric=rtp&from=<now-3600>&to=<now>&step=300'
   ```
   Expect a smooth series; values bounded and converging, not oscillating wildly.

7. **`$__range` never leaves the API.** (Negative check.) The instant RTP query
   used by summary must contain a concrete window, not the token:
   ```
   curl -s '<host>/actuator/loggers/com.vingame.bot.domain.metrics' >/dev/null  # sanity
   ```
   Confirmed primarily by the Phase 2 edge test; on-server, step 5 returning a
   numeric `rtp` (not an error) is the live proof the substitution fired.

8. **Both dashboards load with the new/changed panels.** After Grafana restart:
   ```
   curl -s -u <gf-user>:<pass> 'http://<gf-host>:3000/api/dashboards/uid/per-game' | grep -o 'increase(bot_winnings_total'
   curl -s -u <gf-user>:<pass> 'http://<gf-host>:3000/api/dashboards/uid/per-environment' | grep -o 'bot_money_drained_total'
   ```
   Expect a match in each (RTP rework present in per-game; drain panel present in
   per-env). Open both dashboards: RTP and "Money drain / day" panels render a
   value (not "No data"), changing the RTP panel's time range visibly changes its
   value.

9. **Co-located stack re-verified.** Per the bot-1 single-compose layout, confirm
   Grafana/Prometheus/Loki are all UP after the redeploy and both dashboards
   appear in the Grafana dashboard list (universal smoke).
