# Compliance — GRAFANA_PER_GAME_ENV_DASHBOARDS

Branch: feat/metrics-game-labels
Plan reviewed: `docs/plans/GRAFANA_PER_GAME_ENV_DASHBOARDS.md` (at commit a386f87)
Diff reviewed: `git diff main..feat/metrics-game-labels` (main at bf2f76c)

## Verdict

PLAN_AMENDED

The diff faithfully implements every phase and every resolved decision. The one
mechanism the plan prescribed for refreshing the Phase 2/3 `MultiGauge`s — "call
`register(rows)` inside the gauge's value-supplier path" — is technically
impossible (Micrometer's `MultiGauge` has no value-supplier/callback API). The
dev's scheduled `InfoGaugeRefresher` is the correct way to achieve the plan's
stated intent. Because the plan asserted a falsifiable, incorrect claim about the
`MultiGauge` API, this is a genuine plan oversight, so the verdict is
PLAN_AMENDED (mechanism note corrected) and the diff is accepted.

## Phase-by-phase

### Phase 1 — Add `gameId` / `gameName` labels + fix `gameType` value
Status: implemented
Notes:
- `BotMdc` adds `GAME_ID`/`GAME_NAME` constants, extends `set(...)` with explicit
  params (domain-free, as the plan preferred), sets both in `set(...)`, removes
  both in `clear()`, and documents the AD-1/AD-8 semantics.
- Both call sites updated and in sync: `Bot.initialize` (Bot.java) and
  `BotGroupRuntime.startBot` both pass `getGameType().name()` (corrected enum),
  `getId()` (Mongo `_id` UUID, per AD-8), and `getName()`.
- `BotMetrics.mdcTags()` adds the two keys, bumps capacity to 5, and the AD-5
  javadoc is rewritten to record the AD-9 cardinality amendment.
- `BotMdcTagsMeterFilter.map()` re-adds the same two keys (capacity bumped to 5).
- Tests: `BotMetricsTest` (28) asserts the new labels and the `gameType`-enum
  regression guard; `BotMdcTagsMeterFilterTest` asserts the filter re-adds them.
  All green.

### Phase 2 — `game_info` / `environment_info` join gauges
Status: implemented (with the mechanism deviation amended below)
Notes:
- `BotGroupBehaviorService` adds `listRunningGameInfo()` and
  `listRunningEnvironmentInfo()` with `GameInfo`/`EnvInfo` records, distinct over
  live bot iteration, populated the moment a group starts (before first bet).
- `environmentName` is threaded into `BotGroupRuntime` at group start exactly as
  the RESOLVED Open Item directed: new field + overloaded constructor (the
  3-arg ctor delegates with `null`), `@Getter` exposes `getEnvironmentName()`,
  and `BotGroupBehaviorService.start` passes `environment.getName()`. No
  per-scrape DB lookup. Null-safe fallback to the id keeps the series resolvable.
- The two `info` gauges are registered as `MultiGauge`s in `InfoGaugeRefresher`
  (not literally in `ObservabilityConfig.botAggregateGauges` as the plan's step 2
  wording said — a faithful relocation; see Drift). Both names are on
  `AGGREGATE_METER_NAMES`.

### Phase 3 — per-game / per-env status `MultiGauge`s
Status: implemented (with the mechanism deviation amended below)
Notes:
- `countBotsByGameAndStatus()` (keyed `gameId, gameName, status`) and
  `countBotsByEnvAndStatus()` (keyed `environmentId, status`) added, reusing the
  `countBotsByStatus` iteration shape.
- `bots_by_game_status` and `bots_by_env_status` registered as `MultiGauge`s,
  both on `AGGREGATE_METER_NAMES`, refreshed by full row-set replacement so a
  stopped game/env drops on the next cycle (the plan's stale-row requirement).
- Tests: `InfoGaugeRefresherTest` (12) and `BotGroupBehaviorServiceTest`
  GameEnvSnapshotTests (4) cover the snapshots and row registration. Green.

### Phase 4 — Per-Game dashboard JSON
Status: implemented
Notes: `per-game.json`, uid `per-game`, schemaVersion 39, datasource uid
`prometheus`. `$game` = `label_values(game_info, gameName)` (visible, refresh 2);
`$gameId` = `label_values(game_info{gameName="$game"}, gameId)` (hidden, refresh
2). Panel set matches the RESOLVED recommended default exactly: bot groups, total
bots, RTP (`or vector(0)`), DEAD seconds, bots-by-status, bets-placed/bet-amount
rates, winnings, jackpot wins + jackpot amount, failures. All filter
`{gameId="$gameId"}`. PromQL matches the plan's concrete expressions.

### Phase 5 — Per-Environment dashboard JSON
Status: implemented
Notes: `per-environment.json`, uid `per-environment`. `$environment` /
`$environmentId` linked variables mirror AD-6. Panels match the plan: bot groups,
total bots, RTP overall, bots-by-status, RTP-per-game-within-env (`sum by
(gameName)`), throughput, winnings, reconnect-by-reason, failures, DEAD seconds.
All filter `{environmentId="$environmentId"}`; ratio panels carry `or vector(0)`.

`bots.json` repointed: see "Resolved decisions" — handled correctly.

## Resolved decisions — all honored

- Recommended panel set: present in both dashboards; no out-of-v1 extras except a
  reconnect-by-reason panel on the per-env board, which the plan itself listed in
  Phase 5 ("Failures / reconnects / dead-time"). In scope.
- `Game.getName()` / `Environment.getName()` as display fields: used for
  `gameName` and `environmentName` respectively.
- environmentName threaded at start: done via the field + constructor change, no
  per-scrape DB hit.
- `gameType` bug fixed properly + `bots.json` repointed: done, and done well.
  Panels titled "per game type" (Bet rate, Bet amount rate) correctly REMAIN
  grouped `by (gameType)` — now the enum — with clarifying descriptions added.
  Panels titled "per game" (RTP/winnings/drain/jackpots per game) were repointed
  to `sum by (gameName)` so they keep showing per-individual-game data via the
  now-correct label. No panel is left titled one way while grouping the other —
  precisely the mislabeling the plan's Implementation Notes warned against. This
  is a faithful, slightly superior reading of the Open Item.

## Drift

### MultiGauge refresh mechanism (AMENDED — accepted)
The plan (AD-3 and Implementation Notes "MultiGauge refresh semantics") directed:
"call it [`register(rows)`] inside the gauge's value-supplier path so each scrape
reflects live bot state." Micrometer's `MultiGauge` (verified against
micrometer-core 1.14.5) exposes only imperative `register(Iterable<Row>)` /
`register(Iterable<Row>, boolean)` — there is no value-supplier or
sample-on-scrape callback. The prescribed mechanism is not implementable.

The dev instead added `InfoGaugeRefresher`: a dedicated single-thread
virtual-thread scheduler (project idiom) that calls `register(...)` every 10s
(aligned to the Prometheus scrape interval), replacing the full row set each
cycle. This satisfies the plan's actual intent on every count: rows reflect live
bot state, stopped games/envs drop on the next refresh, and names appear the
moment a group starts. The four meter names are on `AGGREGATE_METER_NAMES`, so
they never inherit MDC tags from the refresher thread. Lifecycle is managed via
`@PostConstruct`/`@PreDestroy`.

Minor consequence: row values are sampled at the 10s refresh rather than exactly
at scrape time. With refresh aligned to the 10s scrape this is functionally
equivalent for the dashboards. Acceptable.

This is the textbook case for PLAN_AMENDED rather than SEND_BACK_TO_DEV: a
falsifiable, incorrect technical assumption in the plan about an external API,
with an implementation that honors the plan's intent.

## Out-of-scope changes

- `strongReference(true)` added to the six pre-existing aggregate gauges in
  `ObservabilityConfig`. Not requested by the plan. It is a justified, documented
  stabilization (the state object is the singleton `BotGroupBehaviorService`,
  always strongly reachable; the default weak ref let the gauge return NaN under
  test GC pressure). It does not contradict the plan and does not change
  production semantics for these always-reachable beans. Acceptable; noted for
  the reviewer/QA.
- `docs/reviews/GRAFANA_PER_GAME_ENV_DASHBOARDS/qa.md` added by the QA agent —
  outside compliance scope, no objection.

## Amendments to the plan

`docs/plans/GRAFANA_PER_GAME_ENV_DASHBOARDS.md` gains an `## Amendment —
2026-06-26` section recording that Micrometer's `MultiGauge` has no
value-supplier path, that the Phase 2/3 gauges are therefore refreshed by a
scheduled `InfoGaugeRefresher` (10s, scrape-aligned, full row-set replacement),
and that this is the accepted realization of AD-3 / the "MultiGauge refresh
semantics" note. No other plan text is changed.
