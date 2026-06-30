# Code Review — OBSERVABILITY Phase 6

Branch: `feat/observability-metrics`
Commit reviewed: `ba33234` on `44ba23b`
Diff: `git diff 44ba23b..ba33234`
Files: `grafana/provisioning/dashboards/bots.json`, `grafana/provisioning/dashboards/game-server.json` (both new).

## Verdict

**APPROVE WITH NITS**

No blockers. All PromQL is correct against the Phase 2/3 metric inventory, datasource references are consistent, schema is valid, layout is sensible. Two minor polish items (denominator NaN, Phase 3 aggregate gauges omitted) and one editorial nit. Nothing demo-blocking.

---

## Findings

### 1. [minor] Login/verify success-rate stats divide by zero on cold start
`grafana/provisioning/dashboards/game-server.json`, panel id 1 ("Login success rate (5m)") and panel id 2 ("Verify-token success rate (5m)").

Expression:
```
sum(rate(bot_login_total{outcome="success"}[5m])) / sum(rate(bot_login_total[5m]))
```
On a freshly-started stack (no logins yet in the 5m window), both numerator and denominator are 0 and the stat renders "No data" with the red threshold (because the red step is `value: null`). During the demo, the first ~60s after `docker compose up` will show the most important stat panel as a red "No data" box, which looks worse than reality.

Suggested fix: wrap the ratio so the panel renders 0 when there's no traffic, then turns green as soon as a success ticks in:
```
sum(rate(bot_login_total{outcome="success"}[5m])) /
clamp_min(sum(rate(bot_login_total[5m])), 1e-12)
```
Or `(... or vector(0))` on each side. Cosmetic, but cheap.

### 2. [minor] Phase 3 aggregate gauges (`bots_dead_currently`, `groups_dead_currently`) have no panels
`ObservabilityConfig.java:65-73` exposes both gauges. Phase 3 shipped them deliberately as the "how many are down right now" companion to the dead-seconds counters. Neither dashboard surfaces them.

Two cheap stat panels — "Bots currently DEAD" (Bots dashboard, top row) and "Groups currently DEAD" (Game server dashboard, top row) — would be the highest-value demo additions: they answer "is anything broken right now?" at a glance. The plan's authoritative panel list (lines 371-388) doesn't enumerate them, which is presumably why Dev omitted them, but they are explicitly intended for surface.

Recommend adding both before the demo. Both are pure `bots_dead_currently` / `groups_dead_currently` exprs (no rate, no by-clause).

### 3. [nit] Game server panel id 5 ("Watchdog firings") groups by `gameType` but title doesn't reflect it
`grafana/provisioning/dashboards/game-server.json`, panel id 5.

Expression `sum by (gameType) (rate(bot_watchdog_expired_total[5m]))` is correct — `bot_watchdog_expired_total` carries the MDC-derived `gameType` tag via `BotMetrics.mdcTags()` (verified at `BotMetrics.java:129-134` and the caller in `BettingMiniGameBot.java:149` which runs on a bot-MDC-tagged virtual thread). The series legend will be the gameType name. Title says "(game silence)" — fine, but the panel description doesn't mention the per-gameType split. Cosmetic. Could rename to "Watchdog firings (by game type)".

### 4. [nit] Stat panels have `graphMode: "area"` but no sparkline frame in this layout
All four top-row stat panels (Bots panel ids 1-4 and Game server panel ids 1-3) use `"graphMode": "area"` which renders a small sparkline behind the number. For a "running bot groups" gauge that changes once an hour, the sparkline is just visual noise. Not worth changing now, just noting.

---

## Panel-by-panel correctness table

### `bots.json`

| id | type | metric / expr | tags | verdict |
|----|------|---------------|------|---------|
| 1 | stat | `bot_groups_running` | none | OK (gauge, raw value) |
| 2 | stat | `bots_managed` | none | OK (gauge, raw value) |
| 3 | stat | `ws_connections_open` | none | OK (gauge, raw value) |
| 4 | stat | `sum(increase(bot_dead_seconds_total[1h]))` | s | OK (counter, increase over window) |
| 5 | timeseries | `bots_by_status` legend `{{status}}` | status | OK (multi-series gauge, stacked) |
| 6 | timeseries | `sum by (gameType) (rate(bot_bets_placed_total[1m]))` | ops | OK (counter rate, correct window) |
| 7 | timeseries | `sum by (gameType) (rate(bot_bet_amount_total[1m]))` | short | OK (counter rate; `short` unit fine — bet amount has no SI unit) |
| 8 | timeseries | `sum by (outcome) (rate(bot_auto_deposits_total[5m]))` | ops | OK |
| 9 | timeseries | `sum(rate(bot_failures_total[5m]))` | ops | OK (single series — failures intentionally not split by tag) |
| 10 | timeseries | `sum by (reason) (rate(bot_reconnects_total[5m]))` | ops | OK; description note about `reauth-cycle` non-emission is good |

### `game-server.json`

| id | type | metric / expr | unit | verdict |
|----|------|---------------|------|---------|
| 1 | stat | login success ratio over 5m | percentunit | OK pattern; see Finding 1 (cold-start NaN) |
| 2 | stat | verify-token success ratio over 5m | percentunit | OK pattern; same NaN caveat |
| 3 | stat | `sum(increase(group_dead_seconds_total[1h]))` | s | OK |
| 4 | timeseries | `sum by (cmd) (rate(bot_messages_total[1m]))` | ops | OK (legend will be subscribe/startGame/updateBet/endGame) |
| 5 | timeseries | `sum by (gameType) (rate(bot_watchdog_expired_total[5m]))` | ops | OK; see nit Finding 3 |
| 6 | timeseries | `sum by (event) (rate(bot_ws_connections_total[5m]))` | ops | OK (legend = connected/authenticating/disconnected) |
| 7 | timeseries | two targets, login + verify rate by outcome | ops | OK |

All metric names, tag names, and counter-vs-gauge usage match `BotMetrics.java`, `ObservabilityConfig.java`, and `BotMdc.java` as of `44ba23b`.

---

## Watchdog-double-count handling

PASS. Panel 10 ("Reconnect rate by reason") uses `sum by (reason) (rate(bot_reconnects_total[5m]))` and carries an explicit description: *"Phase 2 emits reason in {watchdog, ws-disconnect}. The reauth-cycle tag value is reserved but not currently emitted."* This matches the actual emission sites (`Bot.java:356` `ws-disconnect`, `Bot.java:369` `normalizeReconnectReason(reason)`). The `reauth-cycle` series will simply never appear in the legend, no double-counting. No action needed.

---

## Phase 3 aggregate gauge panels (`bots_dead_currently`, `groups_dead_currently`)

**Recommend adding.** See Finding 2. They are demo-high-value, plan-authoritative metrics that already exist on the `/actuator/prometheus` endpoint. Two stat panels, one per dashboard, top row. Not a blocker for shipping but the dashboards undersell what Phase 3 gave them.

---

## Phase 4/5 metric references

Verified absent. No reference to `bot_winnings_total`, `game_total_bet_amount_total`, RTP ratios, or share metrics in either JSON. Dev correctly omitted Phase 4/5 panels.

---

## Scope check

Clean. `git diff 44ba23b..ba33234 --name-only` returns exactly two files, both under `grafana/provisioning/dashboards/`. No changes to:
- Java sources
- `pom.xml`
- `docker-compose.yml`
- `application.properties`
- `dashboards.yml` provisioning provider (Phase 1 artifact, untouched)
- Loki, Promtail, Prometheus configs
- The Grafana `user:` directive

No scope creep.

---

## Looks good — what I specifically verified

- All 12 PromQL metric names referenced in panel exprs exist as registered Micrometer meters (cross-checked against `BotMetrics.java:45-56` constants and `ObservabilityConfig.java:36-72` Gauge registrations).
- Counter metrics are always wrapped in `rate(...[window])` or `increase(...[window])`; gauges (`bot_groups_running`, `bots_managed`, `ws_connections_open`, `bots_by_status`) are queried raw. No "rate of a gauge" or "raw counter as stat" bugs.
- Tag names used in `by (...)` and `legendFormat` (`status`, `gameType`, `outcome`, `cmd`, `reason`, `event`) all match the tag keys actually set in `BotMetrics.java`.
- The `gameType` group-by on panels 6/7 (Bots) and 5 (Game server) is reachable: it's an MDC key (`BotMdc.GAME_TYPE = "gameType"`) attached via `BotMetrics.mdcTags()` to every `bot_*` counter created from a bot-owned thread.
- Datasource refs are uniform: every panel and every target uses `{"type": "prometheus", "uid": "prometheus"}`. No legacy expression refs, no `${DS_PROMETHEUS}` template variables, no bare strings.
- Schema: `schemaVersion: 39` is compatible with Grafana 11.4 (current max is 41; older versions render fine). `uid` values `bots` and `game-server` are stable, no UUIDs. `version: 1` is the correct initial value.
- Grid layout: top row of each dashboard is summary stats; time series fill rows below; no overlapping `gridPos` rects, no large gaps. Bots dashboard fills a 24-wide grid cleanly across rows y=0, y=4, y=12, y=20. Game server fills y=0, y=4, y=12.
- Tags `["bot-manager", "observability"]` consistent across both files.
- Both dashboards have `refresh: "30s"`, `time: now-1h..now`, `graphTooltip: 1` (shared crosshair) — sensible demo defaults.
- No Loki panels in either dashboard — the diff is Prometheus-only, so LogQL syntax wasn't in scope to review.
- JSON parses cleanly; no trailing commas, no obvious malformation.
