# QA — OBSERVABILITY Phase 6

**Verdict:** CONCERNS (non-blocking)
**Build:** `mvn test` -> 355 tests, 0 failures, 0 errors, 0 skipped (unchanged from baseline; Phase 6 is JSON only)
**Commit:** ba33234 on `feat/observability-metrics`

## Tests added / updated

None. Phase 6 ships dashboard JSON only — no Java production code change, no Java test change. Validation is structural (JSON syntax, required fields, PromQL sanity).

## Dashboard files reviewed

- `grafana/provisioning/dashboards/bots.json` — UID `bots`, 10 panels.
- `grafana/provisioning/dashboards/game-server.json` — UID `game-server`, 7 panels.

## 1. JSON sanity / required fields

| File | JSON parse | `uid` | `title` | `tags` | `schemaVersion` | `time` | `refresh` | `panels` |
|---|---|---|---|---|---|---|---|---|
| `bots.json` | OK | `bots` | `Bots` | `["bot-manager","observability"]` | 39 | `now-1h..now` | `30s` | 10 |
| `game-server.json` | OK | `game-server` | `Game server` | `["bot-manager","observability"]` | 39 | `now-1h..now` | `30s` | 7 |

Both pass.

## 2. Datasource references

`grep "\"datasource\""` shows 20 hits in `bots.json` (10 panel + 10 target) and 15 in `game-server.json` (7 panel + 8 target — panel 7 has 2 targets). All references are the object form `{"type": "prometheus", "uid": "prometheus"}`. No bare strings, no `${...}` template UIDs, no Tempo, no Loki. Datasources are consistently pinned. Pass.

## 3. PromQL syntax + metric existence check

Every `expr` cross-checked against the Phase 2/3 inventory in `BotMetrics.java` / `ObservabilityConfig.java`. All metric names and tag names are correct.

### bots.json

| Panel | expr | OK? |
|---|---|---|
| 1 Running bot groups (stat) | `bot_groups_running` | yes — gauge registered in `ObservabilityConfig` |
| 2 Managed bots (stat) | `bots_managed` | yes — gauge registered |
| 3 Currently-open WS connections (stat) | `ws_connections_open` | yes — gauge registered |
| 4 Bot DEAD seconds (stat) | `sum(increase(bot_dead_seconds_total[1h]))` | yes — Phase 3 counter |
| 5 Bots by status (stacked timeseries) | `bots_by_status` legend `{{status}}` | yes — gauge with `status` tag |
| 6 Bet rate (timeseries) | `sum by (gameType) (rate(bot_bets_placed_total[1m]))` | yes — `gameType` is in MDC tag set |
| 7 Bet amount rate (timeseries) | `sum by (gameType) (rate(bot_bet_amount_total[1m]))` | yes |
| 8 Auto-deposit (timeseries) | `sum by (outcome) (rate(bot_auto_deposits_total[5m]))` | yes — `outcome` tag emitted |
| 9 Failure rate (timeseries) | `sum(rate(bot_failures_total[5m]))` | yes |
| 10 Reconnect rate (timeseries) | `sum by (reason) (rate(bot_reconnects_total[5m]))` | yes — `reason` tag emitted; note in description that only `watchdog` and `ws-disconnect` are emitted today |

### game-server.json

| Panel | expr | OK? |
|---|---|---|
| 1 Login success rate (stat) | `sum(rate(bot_login_total{outcome="success"}[5m])) / sum(rate(bot_login_total[5m]))` | yes — `outcome` tag emitted |
| 2 Verify-token success rate (stat) | `sum(rate(bot_verify_token_total{outcome="success"}[5m])) / sum(rate(bot_verify_token_total[5m]))` | yes |
| 3 Group DEAD seconds (stat) | `sum(increase(group_dead_seconds_total[1h]))` | yes — Phase 3 counter |
| 4 Message rate by cmd (timeseries) | `sum by (cmd) (rate(bot_messages_total[1m]))` | yes — `cmd` tag emitted in `incBotMessage` |
| 5 Watchdog firings (timeseries) | `sum by (gameType) (rate(bot_watchdog_expired_total[5m]))` | yes — `gameType` is in MDC tag set |
| 6 WS open/close rate (timeseries) | `sum by (event) (rate(bot_ws_connections_total[5m]))` | yes — `event` tag emitted (`connected\|authenticating\|disconnected`) |
| 7 Login / verify-token attempts (timeseries, 2 targets) | `sum by (outcome) (rate(bot_login_total[5m]))` and `sum by (outcome) (rate(bot_verify_token_total[5m]))` | yes |

No syntax errors. No dangling brackets/operators. No unmatched braces. No reference to `bot_winnings_total` or `game_total_*` (Phase 4/5 — deferred). Pass.

## 4. LogQL check

No Loki log panels exist in either dashboard. The question prompt asked for LogQL sanity but the implementation includes no log panels. Flagged as a possible coverage gap below.

## 5. Panel coverage vs Phase 6 plan list

### bots.json — plan panels 1-9, 11; panel 10 is Phase 4 (deferred)

| Plan panel | Status |
|---|---|
| 1 Running bot groups (stat, `bot_groups_running`) | PRESENT (panel 1) |
| 2 Managed bots (stat, `bots_managed`) | PRESENT (panel 2) |
| 3 Bots by status (stacked timeseries, `bots_by_status` by `status`) | PRESENT (panel 5) — note: plan said pie/stat? plan says "stacked time series" — implementation matches |
| 4 Bet rate by `gameType` | PRESENT (panel 6) |
| 5 Bet amount rate by `gameType` | PRESENT (panel 7) |
| 6 Auto-deposit by `outcome` | PRESENT (panel 8) |
| 7 Failure rate | PRESENT (panel 9) |
| 8 Reconnect rate by `reason` | PRESENT (panel 10) |
| 9 Currently-open WS connections (stat, `ws_connections_open`) | PRESENT (panel 3) |
| 10 RTP per game (Phase 4) | OMITTED — justified (Phase 4 deferred) |
| 11 Bot DEAD seconds (stat, Phase 3) | PRESENT (panel 4) |

### game-server.json — plan panels 1-6; panel 7 is Phase 5 (deferred)

| Plan panel | Status |
|---|---|
| 1 Login success rate | PRESENT (panel 1) |
| 2 Verify-token success rate | PRESENT (panel 2) |
| 3 Message receive rate by `cmd` | PRESENT (panel 4) |
| 4 Watchdog firings | PRESENT (panel 5) |
| 5 WS event rate by `event` | PRESENT (panel 6) |
| 6 Group DEAD seconds (Phase 3) | PRESENT (panel 3) |
| 7 Bot bet share (Phase 5) | OMITTED — justified (Phase 5 deferred) |

Plan additions (not in the original Phase 6 list, but justified):
- game-server panel 7 "Login / verify-token attempts by outcome" — useful diagnostic showing absolute attempt rate (not just success ratio). Reasonable demo addition.

All plan-listed panels are present or have a justified omission.

## 6. Concerns / follow-ups

### CONCERN 1 — `bots_dead_currently` / `groups_dead_currently` not surfaced

Phase 3 (commit 44ba23b) registered two aggregate gauges in `ObservabilityConfig`:

- `bots_dead_currently` — "Number of bots currently in DEAD state across all running groups"
- `groups_dead_currently` — "Number of bot groups currently in DEAD state"

Neither has a panel in either dashboard. Dev's justification was that the Phase 6 plan list (Section 5) does not call them out, only `bot_dead_seconds_total` and `group_dead_seconds_total`. Strictly speaking Dev is correct — the plan doesn't list them.

However, the demo story for downtime would be materially stronger if the dashboard answered both "how much downtime have we accumulated" (the increase-counter stat panels that *are* present) AND "how many bots / groups are DEAD right now" (the gauges that are NOT). The latter is the more eyeball-grabbing demo number — it changes in real time as the watchdog detects failures, whereas the 1h-increase stat only meaningfully moves after the demo session has been running for a while.

**Recommendation:** add two stat panels (one to each dashboard, alongside the existing DEAD-seconds stat). One-line addition each:
- bots.json: `bots_dead_currently` (companion stat next to "Bot DEAD seconds (last 1h)")
- game-server.json: `groups_dead_currently` (companion stat next to "Group DEAD seconds (last 1h)")

Non-blocking: dashboards function correctly without them, and Releaser can post-edit before the demo if desired.

### CONCERN 2 — No log panel(s)

Neither dashboard includes a Loki log panel. The Phase 6 plan in Section 5 doesn't explicitly require one, so this is plan-compliant. However, the demo would benefit from a single live-tail panel on the Bots dashboard pinned to `{job="bot-manager"} |~ "(?i)error|warn"` or similar, so stakeholders can see the application is talking. Optional; flag for Releaser judgment.

## 7. UX nits

- All time series queries use `rate(...[Xm])` over counters — none plot raw monotonic counter values. Good.
- Stat panels: 3 use `lastNotNull` of a gauge (running groups, managed bots, ws open) — correct. 2 use `sum(increase(...[1h]))` of a counter — correct. 2 use `sum(rate(success)) / sum(rate(total))` ratios — correct.
- Bots-by-status uses a stacked timeseries (not a pie chart). The plan said "stacked time series" — implementation matches plan; pie was a question-prompt assumption, not a plan requirement.
- All panels have legend formats. No `query: ""` empty targets, no all-empty target arrays.
- Panel titles are readable. Tag set `["bot-manager", "observability"]` is consistent across both dashboards.
- `refresh: 30s` matches the Phase 1 Prometheus `scrape_interval`. Good.
- `time: now-1h..now` is reasonable for a live demo, gives enough warmup window to show non-trivial rates.

Minor: panel 10 description in `bots.json` correctly notes that `reauth-cycle` is reserved but not currently emitted — good observability discipline.

## Failures

None.

---

## Verdict rationale

**CONCERNS, not FAIL**, because:
- All structural checks (JSON, schema, datasource refs, PromQL syntax, metric existence) pass.
- Every plan-listed Phase 6 panel is present or justifiably omitted.
- The `bots_dead_currently` / `groups_dead_currently` gauges have no panel, which is technically plan-compliant but materially weakens the downtime story for a live demo. Worth surfacing to the Releaser before Phase 7 verification.
- Optional: no Loki log panel exists; defensible but the demo would benefit from one.

Both concerns are non-blocking — the dashboards will provision, render, and serve the demo story as-is.
