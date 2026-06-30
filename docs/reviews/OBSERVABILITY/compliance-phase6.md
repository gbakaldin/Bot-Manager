# Compliance — OBSERVABILITY Phase 6

Branch: `feat/observability-metrics`
Plan reviewed: `docs/plans/OBSERVABILITY.md` (Section 5 Phase 6 + AD 1, 5, 10, 11) at commit `ba33234`
Diff reviewed: `git diff 44ba23b..ba33234` (post-Phase-3 base → Phase 6 head)

## Verdict

**PLAN_AMENDED**

Phase 6 ships exactly the two dashboard JSONs called for, every panel listed in the plan is present (modulo the explicitly-gated Phase 4/5 items), Phase 1 carry-overs hold, and AD coverage is clean. Two surgical plan corrections are warranted (datasource-reference acceptance criterion is stale vs the Phase 1 polish; Phase 6 panel list is out of sync with the Phase 3 "currently dead" gauges that were added in Phase 3). Both are real technical drifts of the plan from reality, not Dev preference.

## Per-deliverable table

| Deliverable | Status | Notes |
|---|---|---|
| `grafana/provisioning/dashboards/bots.json` | PRESENT | 10 panels, all plan-listed bots panels accounted for. Schema v39, refresh 30s, `now-1h..now` default window. |
| `grafana/provisioning/dashboards/game-server.json` | PRESENT | 7 panels; the seventh is a consolidation panel Dev added (see deviation table). |
| Auto-provisioned via Phase 1 `dashboards.yml` | PRESENT | `dashboards.yml` provider unchanged; both new JSONs live under the provider's `path:`. |

## Panel coverage table

### Bots dashboard

| # | Plan-listed panel | Status | Evidence |
|---|---|---|---|
| 1 | Running bot groups (stat, `bot_groups_running`) | PRESENT | panel id 1 |
| 2 | Managed bots (stat, `bots_managed`) | PRESENT | panel id 2 |
| 3 | Bots by status (stacked TS, `bots_by_status` by `status`) | PRESENT | panel id 5, stacking mode normal, `{{status}}` legend |
| 4 | Bet rate (TS, `rate(bot_bets_placed_total[1m])` by `gameType`) | PRESENT | panel id 6, `sum by (gameType) (rate(...))` |
| 5 | Bet amount rate (TS, `rate(bot_bet_amount_total[1m])` by `gameType`) | PRESENT | panel id 7 |
| 6 | Auto-deposit frequency (TS, `rate(bot_auto_deposits_total[5m])` by `outcome`) | PRESENT | panel id 8 |
| 7 | Failure rate (TS, `rate(bot_failures_total[5m])`) | PRESENT | panel id 9 |
| 8 | Reconnect rate (TS, `rate(bot_reconnects_total[5m])` by `reason`) | PRESENT | panel id 10 (with note that `reauth-cycle` reason is reserved-but-unemitted today) |
| 9 | Currently-open WS connections (stat, `ws_connections_open`) | PRESENT | panel id 3 |
| 10 | (Phase 4) RTP per game | OMITTED-justified | Phase 4 explicitly deferred; metric not registered, would render "No data" or `0/0`. |
| 11 | (Phase 3) Bot DEAD seconds in window (`increase(bot_dead_seconds_total[1h])`) | PRESENT | panel id 4 |

### Game server dashboard

| # | Plan-listed panel | Status | Evidence |
|---|---|---|---|
| 1 | Login success rate | PRESENT | panel id 1 |
| 2 | Verify-token success rate | PRESENT | panel id 2 |
| 3 | Message receive rate by `cmd` | PRESENT | panel id 4 |
| 4 | Watchdog firings | PRESENT | panel id 5 (broken out by `gameType` — small enrichment, not a drift) |
| 5 | WS event rate by `event` | PRESENT | panel id 6 |
| 6 | (Phase 3) Group DEAD seconds (`increase(group_dead_seconds_total[1h])`) | PRESENT | panel id 3 |
| 7 | (Phase 5) Bot bet share | OMITTED-justified | Phase 5 deferred; `game_total_bet_amount_total` is not registered. |
| — | Login / verify-token attempts panel (Dev addition) | EXTRA, ACCEPTED | panel id 7, consolidates two outcome-tagged series in one chart. See deviation table. |

## AD coverage

| AD | Subject | Status |
|---|---|---|
| 1 | Stack: Prometheus alongside Loki; Loki stays default | OK — `loki.yml` is `isDefault: true`, `prometheus.yml` is `isDefault: false`. Every dashboard panel references the Prometheus datasource only. No Loki panels (none planned for Phase 6). |
| 5 | Cardinality cap: `groupId × envId × gameType` only — no `username`/`botId` | OK — no panel groups by or filters on `username`, `botId`, or `botIndex`. Phase 6 groups by `gameType`, `cmd`, `event`, `outcome`, `reason`, `status` — all bounded enums per plan. |
| 10 | Per-callsite MDC read + filter as defense-in-depth | OK — dashboards consume the resulting time series shape; nothing in the JSONs assumes more or fewer tags than the producer side emits. |
| 11 | Naming convention (`bot_*` / `bots_*` / `group_*`, counters end `_total`, durations `_seconds_total`) | OK — every expression uses plan-spec metric names: `bot_groups_running`, `bots_managed`, `bots_by_status`, `bot_bets_placed_total`, `bot_bet_amount_total`, `bot_auto_deposits_total`, `bot_failures_total`, `bot_reconnects_total`, `ws_connections_open`, `bot_dead_seconds_total`, `bot_messages_total`, `bot_watchdog_expired_total`, `bot_ws_connections_total`, `bot_login_total`, `bot_verify_token_total`, `group_dead_seconds_total`. |

## Scope check

| Touched | In scope? |
|---|---|
| `grafana/provisioning/dashboards/bots.json` (new) | YES |
| `grafana/provisioning/dashboards/game-server.json` (new) | YES |
| Anything else | NO — `git diff 44ba23b..ba33234 --stat` shows only the two JSONs (+708 lines total). No Java, no infra, no `dashboards.yml`, no datasource YAML, no `application.properties`, no `pom.xml`. Clean. |

## Phase 1 carry-over check

| Carry-over | Status |
|---|---|
| `dashboards.yml` provider points at `/etc/grafana/provisioning/dashboards` | OK — unchanged from Phase 1; provider scans the same directory the two new JSONs live in. |
| Datasource UID `prometheus` referenced in every panel | OK — verified: every `datasource` object in both JSONs is `{"type": "prometheus", "uid": "prometheus"}`, and every `targets[].datasource` matches. No hardcoded UIDs from any other source. |
| Datasource UID `loki` referenced where appropriate | N/A — Phase 6 panel list contains no Loki panels by plan; none expected. |
| Loki remains default per AD 1 | OK (verified in `loki.yml`/`prometheus.yml`). |

## Dev deviation verdicts

| Deviation | Verdict | Reasoning |
|---|---|---|
| Phase 4 winnings panel (Bots panel 10) OMITTED | APPROVE | Phase 4 explicitly DEMO OPTIONAL / recommend-defer; `bot_winnings_total` not registered. Including the panel would render `NaN` (zero/zero) and degrade the demo. Plan's gating language ("Phase 3/4/5 panels gracefully degrade to 'No data' if those phases are skipped") supports either ship-or-skip; Dev's choice to skip is the cleaner read. |
| Phase 5 share panel (Game server panel 7-as-planned) OMITTED | APPROVE | Phase 5 DEFERRED; `game_total_bet_amount_total` does not exist. Same reasoning as above. |
| Phase 3 dead-seconds panels INCLUDED | APPROVE | Phase 3 shipped (commit `44ba23b`), metrics are registered, panels render. Matches plan's "if shipped" gating. |
| Aggregate "Currently dead" gauges (`bots_dead_currently`, `groups_dead_currently`) NOT panelled | APPROVE for the diff, with plan amendment | These gauges were added in Phase 3 (verified in `ObservabilityConfig`) but the Phase 6 panel enumeration in the plan (written at Phase 1 time) does not list them. Dev is correct that the plan's Phase 6 list omits them. This is a real plan oversight — see Plan Amendment 2 below. Verdict for the diff stays APPROVE because Dev faithfully implemented the plan as written. Recommendation is to amend the plan and let a follow-up Dev session add the two stat panels; not a SEND_BACK_TO_DEV because the diff matches the written plan. |
| Datasource referenced by UID, not by name | APPROVE, plan amendment required | Plan's Phase 6 acceptance criterion still says "reference … by name (not by UID) so they survive a datasource-recreate". That criterion was written before the Phase 1 polish (`caaf0e1`) pinned stable UIDs (`prometheus`, `loki`) explicitly so dashboards could hardcode them. With pinned UIDs the survives-a-recreate concern goes away (the UID is fixed by `loki.yml`/`prometheus.yml`, not generated). Dev's UID-based references are the now-correct pattern; the plan's stale-by-name criterion is the bug. See Plan Amendment 1. |
| Panel consolidation: combined login + verify-token attempts into one panel (Game server panel 7) | APPROVE | Pragmatic; both series share the same y-axis (ops, by outcome), labels disambiguate via `login {{outcome}}` / `verify-token {{outcome}}`. Cardinality unchanged. Adds an extra panel beyond plan but does not remove any plan-listed panel — net positive for the demo. |
| Watchdog firings panel broken out by `gameType` (Game server panel 5) | APPROVE | Plan said simply `rate(bot_watchdog_expired_total[5m])`; Dev added `sum by (gameType)` which is more useful for diagnosing "is one game silent?". Within plan's cardinality cap. |

## Phase 3 gauge-panel decision

**Recommend amending the plan to add `bots_dead_currently` and `groups_dead_currently` as Phase 6 stat panels (Bots and Game server dashboards respectively); do not send the current diff back.**

Reasoning: when Phase 3 was specified in the plan, two aggregate gauges (`bots_dead_currently`, `groups_dead_currently`) were added to `ObservabilityConfig` alongside the two `_seconds_total` counters. The Phase 6 panel list was written earlier and only mentions the counters' "in window" stats. This is a Phase-6/Phase-3 sync bug in the plan, not a Dev mistake — Dev correctly implemented the panel list as written. The fix is a plan amendment plus a follow-up dashboard tweak (one extra stat tile each, both trivial). Doing it as SEND_BACK_TO_DEV against the current Phase 6 commit would punish a faithful implementation; doing it as a future micro-edit is honest.

## Plan amendments

Two surgical amendments applied to `docs/plans/OBSERVABILITY.md` at the bottom of the existing `## Amendment — 2026-06-04` block:

1. **Datasource-reference acceptance criterion updated** — change the Phase 6 acceptance bullet that says "reference by name (not UID) so they survive a datasource-recreate" to "reference by the pinned UID `prometheus` (and, where Loki panels exist, `loki`); the Phase 1 polish at `caaf0e1` pinned both UIDs explicitly so the recreate-resilience concern is satisfied by the datasource YAMLs, not by indirection through panel variables." Also relax the Implementation-Notes bullet that says the same. The original by-name guidance was correct under Grafana's default UID-from-hash behaviour but stale under the polished Phase 1 setup.

2. **Phase 6 panel list updated to include Phase 3 aggregate gauges** — add to the Bots dashboard panel list: "Currently DEAD bots (stat, `bots_dead_currently`) — Phase 3 metric." Add to the Game server dashboard panel list: "Currently DEAD groups (stat, `groups_dead_currently`) — Phase 3 metric." These were registered in Phase 3 but the Phase 6 list (written earlier) didn't get the sync. Marked as a follow-up micro-task for Dev rather than holding up the Phase 6 commit.
