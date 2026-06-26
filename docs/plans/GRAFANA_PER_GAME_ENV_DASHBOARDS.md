# GRAFANA_PER_GAME_ENV_DASHBOARDS

## Goal

Stand up two provisioned-as-code Grafana dashboards that auto-adapt as new games
and environments appear, without one-file-per-entity (Option A — template
variables). (1) A **per-Game** dashboard templated by a `$game` variable that
selects an individual Mongo `Game` entity (NOT `gameType`) — critical because a
single product like P_116 runs ~8 distinct slot `Game` rows that must be viewed
separately. (2) A **per-Environment** dashboard templated by an `$environment`
variable showing that environment's overall health/throughput/RTP aggregated
across all its games and groups. Both dropdowns show readable names (game name,
environment name), not UUIDs. The prerequisite, and the bulk of the engineering
work, is a **metrics-label phase**: today the bot metrics carry no per-`Game`
label and no readable environment name, so neither dashboard is buildable until
`gameId` + `gameName` + `environmentName` labels are added to the
dashboard-relevant `bot_*` series.

## Findings — Current State

### Metrics labeling today (the blocker)
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java`
  attaches exactly three identity tags on every `bot_*` counter, read from MDC in
  `mdcTags()` (BotMetrics.java:81-87): `botGroupId`, `environmentId`, `gameType`.
  Architecture Decision 5 (BotMetrics.java:39-40) caps cardinality to these three
  and explicitly forbids per-bot tags. **There is no `gameId` and no readable
  name label of any kind.** Every dashboard-relevant counter
  (`bot_messages_total`, `bot_bets_placed_total`, `bot_bet_amount_total`,
  `bot_winnings_total`, `bot_jackpots_total`, `bot_jackpot_amount_total`,
  `bot_failures_total`, `bot_ws_connections_total`, `bot_dead_seconds_total`,
  `bot_reconnects_total`) is tagged only by those three keys.
- **The MDC key named `gameType` does NOT hold the GameType enum — it holds the
  Game's display *name*.** `BotMdc.GAME_TYPE = "gameType"`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/logging/BotMdc.java:20`),
  but both call sites populate it with `configuration.getGame().getName()`:
  `Bot.initialize` (Bot.java:138-144, line 142 passes `getGame().getName()`) and
  `BotGroupRuntime.startBot` (BotGroupRuntime.java:130-136, line 134 passes
  `getGame().getName()`). So the label currently exported as `gameType` on Prometheus
  is actually the per-Game *name* string (e.g. "BauCua"), confirmed by the
  existing test `BotMetricsTest` which uses `GAME_TYPE = "BauCua"`
  (`/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/infrastructure/observability/BotMetricsTest.java:24`).
  This is a latent naming bug — the dashboards labeled "per game type" in
  `bots.json` actually group by game *name*. It also means a readable name is
  *already partially present*, but mis-keyed and not joinable to a stable id.
- `BotMdcTagsMeterFilter`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/BotMdcTagsMeterFilter.java`)
  is defense-in-depth: it re-adds the same three MDC keys to any `bot_*` meter
  created outside `BotMetrics` (map(), lines 56-75) and holds the
  `AGGREGATE_METER_NAMES` allow-list (lines 47-54) that keeps aggregate gauges
  from picking up MDC tags. Any new MDC key added to `BotMetrics.mdcTags()` must
  also be added here to stay consistent.

### What the bot already has access to
- `BotConfiguration`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/config/bot/BotConfiguration.java:46`)
  carries the full `Game game` entity and `String environmentId`
  (BotConfiguration.java:28). The bot can read `game.getId()` (Mongo `_id`, a
  String UUID — Game.java:28-29), `game.getName()` (Game.java:33), and
  `game.getGameType()` (Game.java:35). So `gameId` and `gameName` are both already
  in hand at MDC-set time — no new lookup, no DB call.
- **`environmentName` is NOT in `BotConfiguration`.** The bot has
  `environmentId` only. The `Environment` entity
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/environment/model/Environment.java:42`)
  has `name`, but the bot/runtime does not currently hold the Environment object.
  Resolving `environmentName` requires either (a) threading it into
  `BotConfiguration` at build time in `BotGroupBehaviorService`/`BotFactory`, or
  (b) a Grafana-side join via a small `info` metric (see AD-4). Investigation
  below picks (b) for environmentName to avoid widening every metric's cardinality
  with a long human string, and (a) is unnecessary.

### MDC set sites (where labels are born)
- `BotMdc.set(...)` is called in exactly two places: `Bot.initialize`
  (Bot.java:138) and `BotGroupRuntime.startBot` (BotGroupRuntime.java:130). Both
  take the same 5 args and both currently pass `game.getName()` into the
  `gameType` slot. Any new MDC key must be set in both, or the metric series will
  be inconsistent depending on which thread emitted it.
- `BotMdc` (BotMdc.java) is the single definition point for the key constants and
  the `set`/`clear` methods. Adding keys means editing this class, both call
  sites, the `clear()` method, and the meter filter.

### Aggregate status gauges (the per-game status-breakdown gap)
- `bots_by_status` is an **aggregate** gauge with one series per `BotStatus`,
  registered in `ObservabilityConfig.botAggregateGauges`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/ObservabilityConfig.java:55-61`),
  backed by `BotGroupBehaviorService.countBotsByStatus(status)`
  (BotGroupBehaviorService.java:739-747). It carries **no** game/env tag and is
  explicitly on the `AGGREGATE_METER_NAMES` exclusion list
  (BotMdcTagsMeterFilter.java:47-54). So "bot status breakdown for the selected
  game" cannot be derived from `bots_by_status` as it exists. Same for
  `bots_managed`, `ws_connections_open`, `bots_dead_currently`.
- However the data is available: `BotGroupBehaviorService` iterates
  `runningGroups.values()` → `runtime.getBotInstances()` → each `Bot` exposes
  `getConfiguration().getGame()` (id/name/gameType) and
  `getConfiguration().getEnvironmentId()`, plus `getStatus()`. A new
  game/env-labeled status gauge is therefore feasible by iterating bots and
  grouping by `(gameId, status)` / `(environmentId, status)` — see AD-3.

### Grafana / Prometheus provisioning (the pattern to mirror)
- Dashboards live in `/Users/gleb/IdeaProjects/Bot/grafana/provisioning/dashboards/`
  as `bots.json` and `game-server.json`. The provider config
  `dashboards.yml` (same dir) auto-loads every `*.json` in
  `/etc/grafana/provisioning/dashboards`, `updateIntervalSeconds: 30`,
  `allowUiUpdates: false`. Dropping a new `*.json` there is the entire
  registration mechanism — picked up on container restart (or within 30s of a
  file change if the dir is bind-mounted).
- `bots.json` is the canonical example: `"schemaVersion": 39`, datasource
  `{"type":"prometheus","uid":"prometheus"}`, `templating.list: []` (no variables
  yet), panels are `stat`/`timeseries` with PromQL in `targets[].expr` and
  `legendFormat`. It already groups several panels `by (gameType)` (bots.json:376,
  430, 849) — which, per the finding above, is really grouping by game *name*.
- The datasource uid is `prometheus`
  (`/Users/gleb/IdeaProjects/Bot/grafana/provisioning/datasources/prometheus.yml`).
  Prometheus scrapes `bot-manager:8085/actuator/prometheus`
  (`/Users/gleb/IdeaProjects/Bot/prometheus/prometheus.yml:16-24`); this file does
  not change for new labels — Micrometer exposes everything on the same endpoint.

### Tests to mirror
- `BotMetricsTest`
  (`/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/infrastructure/observability/BotMetricsTest.java`)
  is the harness for the label work: it builds a `SimpleMeterRegistry` with the
  `BotMdcTagsMeterFilter`, sets MDC keys directly, calls an `inc*` method, and
  asserts the counter is found `.tag(key, value)`. New-label assertions slot
  straight into this pattern.
- `BotMdcTagsMeterFilterTest` and `ObservabilityConfigTest` exist in the same
  package and must be kept green when the filter's tag set and the gauges change.

## Per-aspect readiness / mapping

| Aspect | Status | Notes |
|---|---|---|
| `$game` / `$environment` template variables | **ready** | Grafana `label_values` on the new labels; design in AD-5/AD-6. |
| Readable game name in dropdown | **partial** | A name string is already emitted, but mis-keyed as `gameType` and not joined to a stable id. Fixed by AD-1 (`gameId` value + `gameName` display). |
| Readable environment name in dropdown | **blocked** | No `environmentName` anywhere in metrics today. AD-2 adds it via an `info` metric join. |
| Per-game RTP / bets / amount / winnings / jackpots / failures | **blocked** | Counters lack `gameId`. AD-1 adds it to the dashboard-relevant counters. |
| Per-game total bot groups | **blocked** | Needs `count(count by (botGroupId) (...))` over a `gameId`-labeled series (AD-1) — no new metric. |
| Per-game / per-env bot status breakdown (connected/reconnecting/dead/disconnected) | **blocked** | `bots_by_status` is aggregate-only, no game/env tag (ObservabilityConfig.java:55). AD-3 adds labeled status gauges. |
| Per-game / per-env dead-time | **partial** | `bot_dead_seconds_total` exists; gets `gameId`/`gameName` via AD-1, env already has `environmentId`. |
| Per-env throughput/RTP aggregated | **ready after labels** | `environmentId` already on counters; env dashboard mostly works on existing labels + the env-name join (AD-2). |
| Provisioning mechanism | **ready** | Drop `*.json` into `grafana/provisioning/dashboards/`; mirror `bots.json`. |
| Rollout readability when old series lack new labels | **partial** | Addressed by AD-7 (PromQL tolerant of absent label; old series age out within retention). |

## Architecture Decisions

**AD-1. Add `gameId` and `gameName` labels to the dashboard-relevant `bot_*`
counters, sourced from the Mongo `Game` entity the bot already holds.**
- New MDC keys on `BotMdc`: `GAME_ID = "gameId"` (value =
  `configuration.getGame().getId()`, the Mongo `_id` String UUID — the stable
  per-Game key) and `GAME_NAME = "gameName"` (value =
  `configuration.getGame().getName()`, the readable display string).
- **Keep the existing `gameType` MDC key but fix its value to the actual enum:**
  set it to `configuration.getGame().getGameType().name()` (e.g. `SLOT`,
  `BETTING_MINI`) instead of the game name. This corrects the latent naming bug
  (Findings) so `gameType` means game type, `gameName` means the display name, and
  `gameId` is the stable id. Existing `bots.json` panels that group `by (gameType)`
  then correctly group by enum (5 values) instead of by name.
- Set all four keys (`gameId`, `gameName`, `gameType`, plus the unchanged
  `environmentId`, `botGroupId`, `botId`, `botUserName`) in **both**
  `Bot.initialize` (Bot.java:138) and `BotGroupRuntime.startBot`
  (BotGroupRuntime.java:130) via the extended `BotMdc.set(...)`.
- `BotMetrics.mdcTags()` adds `gameId` and `gameName` to the tag list
  (BotMetrics.java:81-87). `BotMdcTagsMeterFilter.map()` adds the same two keys
  (BotMdcTagsMeterFilter.java:66-69) for defense-in-depth.
- **Which counters get the new labels:** all of them automatically, because
  `mdcTags()` is shared by every `inc*` method. This is acceptable (see AD-9
  cardinality analysis) and simpler/safer than a per-counter allow-list — the
  dashboard-relevant set (messages, bets_placed, bet_amount, winnings, jackpots,
  jackpot_amount, failures, ws_connections, dead_seconds, reconnects) is the
  majority anyway, and tagging the few non-dashboard counters (login,
  verify_token, auto_deposits, watchdog_expired, creation_failures) with `gameId`
  costs nothing extra in dimensionality given the bounded game set.

**AD-2. `environmentName` is provided via a Grafana-side join on a small
`environment_info` metric, NOT as a label on every bot counter.** Putting a long
human environment name on every `bot_*` series is unnecessary cardinality (it is
1:1 with `environmentId`, which is already present). Instead emit one gauge
`environment_info{environmentId="<uuid>", environmentName="<name>"} 1` per
running environment (registered alongside the aggregate gauges in
`ObservabilityConfig`, value always 1). The `$environment` dropdown uses
`label_values(environment_info, environmentName)` for display and maps the chosen
name back to its id with a second query (AD-6). This is the standard Prometheus
"info metric" pattern and keeps the counters lean.
- Rationale for asymmetry with `gameName` (AD-1 puts `gameName` directly on
  counters): the per-Game dashboard needs to *group panels* by name (legend, RTP
  per game) and a co-located `gameName` label is the simplest robust way to render
  legends without a join in every panel; the count of `(gameId, gameName)` pairs
  is bounded (tens). The per-Env dashboard only needs the name in the dropdown,
  not in panel legends, so a single info metric suffices and avoids fattening
  every series. Both choices are defensible; this split minimizes total
  cardinality while keeping each dashboard's PromQL simple.
- A symmetric `game_info{gameId, gameName, gameType}` gauge is **also** emitted
  (same loop) so the `$game` dropdown can show names for games that are configured
  but have not yet emitted any counter series (e.g. a freshly created game whose
  group has not bet yet). The dropdown sources from `game_info`; panels source the
  `gameName` label on counters. This guarantees the dropdown is populated the
  moment a group starts, before the first bet.

**AD-3. Add per-game and per-env labeled status gauges for the status
breakdown.** `bots_by_status` stays aggregate (do not break it). Add two new gauge
families in `ObservabilityConfig.botAggregateGauges`, backed by new
`BotGroupBehaviorService` methods that iterate live bots and group by the bot's
`Game`/environment:
- `bots_by_game_status{gameId, gameName, status}` — count of bots in each status
  per Game.
- `bots_by_env_status{environmentId, status}` — count of bots in each status per
  environment (env name comes from the `environment_info` join, AD-2).
These two new names go on the `AGGREGATE_METER_NAMES` exclusion list in
`BotMdcTagsMeterFilter` so they do not also pick up MDC tags from the sampling
thread. The backing methods return a `Map`-shaped snapshot; register one
`Gauge.builder` per `(distinct entity, status)` is impractical for a dynamic set,
so register a `MultiGauge` (Micrometer `MultiGauge.builder(...).register(...)`)
that is refreshed on each scrape from the live bot iteration. Decision: use
`MultiGauge` for both families because the `(gameId × status)` set is dynamic and
unknown at startup.

**AD-4. The `$game` variable's value is `gameId`; its display text is `gameName`.**
Grafana template variables support a display/value split via the
`label_values(<metric>, <label>)` query combined with a regex or via the
`__text`/`__value` convention when the query returns two columns. Robust approach
chosen: source the variable from the `game_info` metric with a regex-capture query
so the dropdown lists names and resolves to ids. Concretely the variable query is
`label_values(game_info, gameName)` for the visible list, and panels filter by a
second hidden variable `gameId` derived from
`label_values(game_info{gameName="$game"}, gameId)`. Rationale: a single
two-column mapping in `label_values` is brittle across Grafana versions; two
linked variables (`$game` = name picker, `$gameId` = resolved id used in PromQL)
is the most version-robust and is self-documenting. Same pattern for environment
(AD-6).

**AD-5. Per-Game dashboard is a SINGLE dashboard with a `$game` dropdown, not
repeating rows and not N files.** The user said "dashboard for game" and chose
Option A. A single dashboard with a dropdown selecting one Game is the cleanest
match: it is one JSON file, auto-adapts (new games appear in the dropdown with
zero file changes), and shows exactly one game's full panel set at a time — which
is what "see each one separately" means. Repeating rows (one row per game on a
single screen) is rejected for v1: with ~8 slot games per product plus betting
games, a repeat-by-`$game` view becomes an unreadable wall and defeats the
"focus on one game" intent. (A future "all games at a glance" overview can be a
separate third dashboard — Open Item.)

**AD-6. Per-Environment dashboard mirrors AD-4/AD-5: single dashboard,
`$environment` dropdown (display `environmentName`, value `environmentId`).**
Variable query `label_values(environment_info, environmentName)` for the picker;
hidden linked variable `$environmentId` =
`label_values(environment_info{environmentName="$environment"}, environmentId)`
used in every panel's PromQL.

**AD-7. Dashboards must render during the label rollout when old series lack the
new labels.** A counter time series created before this deploy has no `gameId`
label; after deploy the bot process restarts and emits fresh series *with* the
labels, but Prometheus retains the old label-less series until they age out of
retention. Decisions:
- All per-game panels filter on `{gameId="$gameId"}`. A series without `gameId`
  simply does not match the filter and is excluded — no error, it just doesn't
  appear. This is the desired behavior (old data is pre-feature and not
  attributable to a game).
- RTP and ratio panels wrap divisions in `... or vector(0)` exactly as the
  existing `bots.json` RTP panels do (bots.json:793, 849) so an empty numerator
  or denominator renders 0 rather than "No data".
- No migration of historical series is attempted; the dashboards are correct for
  data emitted from this deploy forward. Document this in the dashboard panel
  descriptions.

**AD-8. `gameId` value is the Mongo `_id` (String UUID), not `Game.gameId`
(Integer).** `Game.gameId` (Game.java:48) is the env-scoped numeric channel/gid
and is NOT unique across games/products (two products can both have gid 204).
The Mongo `_id` (`Game.id`, Game.java:28-29) is the globally unique stable key
and is what the dashboard must filter on to isolate one Game entity. The label is
named `gameId` but carries `Game.getId()`. (If a future reviewer finds the name
`gameId` colliding-confusing with the entity field `Game.gameId`, that is a
naming nit, not a correctness issue — call it out in the PR.)

**AD-9. Cardinality decision — bounded, accepted, documented as an amendment to
AD-5 of `BotMetrics`.** Games and environments are bounded operator-managed sets
(tens, not thousands). `gameName` is 1:1 with `gameId`; `gameType` is 1:n into
~5 enum values. Adding `gameId` + `gameName` to the existing
`(botGroupId, environmentId, gameType)` tuple multiplies series count by at most
the number of distinct games — but in practice a botGroupId maps to exactly one
game, so `gameId`/`gameName` are *functionally dependent on `botGroupId`* and add
**near-zero** real cardinality (they are constant within a group's series). The
per-game/per-env status `MultiGauge`s add `O(games × statuses)` and
`O(envs × statuses)` series — bounded by tens × ~8 statuses = low hundreds. The
`environment_info`/`game_info` gauges add one series per env/game. Total increase
is bounded and small. This plan amends `BotMetrics` Architecture Decision 5 to
permit `gameId` and `gameName` as identity tags (companions of the existing
trio); the prohibition on unbounded per-bot tags (botId, userName) stands.
Update the AD-5 javadoc block in `BotMetrics.java` to record this.

## Plan

> Maven note: all builds use
> `JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home`
> (default JDK is Java 8 and will not compile this project).

### Phase 1 — Add `gameId` / `gameName` labels + fix `gameType` value
Goal: every `bot_*` counter carries `gameId`, `gameName`, and a corrected
`gameType` (enum, not name). Prerequisite for the per-Game dashboard.

Steps:
1. `BotMdc` (`.../common/logging/BotMdc.java`): add
   `public static final String GAME_ID = "gameId";` and
   `public static final String GAME_NAME = "gameName";`. Extend `set(...)` to take
   `gameId` and `gameName` params (or pass the `Game` object and derive inside —
   prefer explicit params to keep `BotMdc` dependency-free of the domain). Put
   both keys in `set(...)` and remove them in `clear()`.
2. Update the two call sites to pass `game.getId()`, `game.getName()`, and the
   corrected `gameType = game.getGameType().name()`:
   - `Bot.initialize` (Bot.java:138-144).
   - `BotGroupRuntime.startBot` (BotGroupRuntime.java:130-136).
3. `BotMetrics.mdcTags()` (BotMetrics.java:81-87): add
   `addIfPresent(tags, BotMdc.GAME_ID); addIfPresent(tags, BotMdc.GAME_NAME);`
   and bump the `ArrayList` initial capacity. Update the AD-5 javadoc block
   (BotMetrics.java:39-40) per AD-9.
4. `BotMdcTagsMeterFilter.map()` (BotMdcTagsMeterFilter.java:66-69): add the same
   two `addTagIfPresent` calls.

Verification:
- `mvn -q -o test-compile` compiles.
- `BotMetricsTest`: extend `setBotMdc()` to also set `gameId`/`gameName`; add
  assertions that `incBotMessage`, `incBetsPlaced`, `incBotWinnings`,
  `incBotJackpot`, `incBotFailure`, `incBotDeadSeconds` produce counters findable
  by `.tag("gameId", ...)` and `.tag("gameName", ...)`. Add one assertion that
  `gameType` now equals the enum form (set MDC `gameType` = `"SLOT"`).
- `BotMdcTagsMeterFilterTest`: assert the filter re-adds `gameId`/`gameName`.

### Phase 2 — `game_info` / `environment_info` join gauges
Goal: dropdown-population metrics that exist the moment a group starts (before any
bet), carrying the readable names.

Steps:
1. `BotGroupBehaviorService`: add methods returning the live distinct sets, e.g.
   `Collection<GameInfo> listRunningGameInfo()` (distinct `(gameId, gameName,
   gameType)` over `runningGroups → botInstances → getConfiguration().getGame()`)
   and `Collection<EnvInfo> listRunningEnvironmentInfo()` (distinct
   `(environmentId, ...)`). **environmentName source:** the runtime currently
   holds only `environmentId` (BotGroupRuntime.java:49); to emit
   `environmentName` the service must resolve it. Resolve via the existing
   `EnvironmentService`/repository by id at sample time (cache per scrape), OR
   thread `environmentName` into `BotGroupRuntime` at start. **Open Item flag:
   confirm the cheapest source** — see Open Items.
2. `ObservabilityConfig.botAggregateGauges`: register a `MultiGauge`
   `game_info` refreshed each scrape from `listRunningGameInfo()`, each row value
   `1`, tags `{gameId, gameName, gameType}`; and `environment_info` similarly with
   `{environmentId, environmentName}`.
3. Add `game_info`, `environment_info` to `AGGREGATE_METER_NAMES` in
   `BotMdcTagsMeterFilter` so they do not pick up MDC tags.

Verification:
- `mvn -q -o test`.
- `ObservabilityConfigTest`: with a stubbed `BotGroupBehaviorService` returning a
  fixed game/env set, assert `registry.find("game_info").tag("gameName", ...)`
  and `environment_info` series exist with value 1.

### Phase 3 — per-game / per-env status `MultiGauge`s
Goal: status breakdown filterable by `$gameId` / `$environmentId`.

Steps:
1. `BotGroupBehaviorService`: add
   `Map<GameStatusKey,Integer> countBotsByGameAndStatus()` grouping live bots by
   `(gameId, gameName, status)`, and `countBotsByEnvAndStatus()` by
   `(environmentId, status)`. Reuse the iteration shape of
   `countBotsByStatus` (BotGroupBehaviorService.java:739-747).
2. `ObservabilityConfig`: register `MultiGauge` `bots_by_game_status`
   (tags `gameId, gameName, status`) and `bots_by_env_status`
   (tags `environmentId, status`), refreshed each scrape.
3. Add both names to `AGGREGATE_METER_NAMES`.

Verification:
- `mvn -q -o test`.
- `ObservabilityConfigTest`: stub the service to report e.g. 3 bots
  CONNECTION_AUTHENTICATED + 1 DEAD for one game; assert
  `bots_by_game_status{status="DEAD"} == 1` and `{status="CONNECTION_AUTHENTICATED"} == 3`.

### Phase 4 — Per-Game dashboard JSON
Goal: `grafana/provisioning/dashboards/per-game.json`, single dashboard,
`$game` dropdown (name) → `$gameId` (id).

Steps (mirror `bots.json` structure: schemaVersion 39, datasource uid
`prometheus`):
1. `templating.list`:
   - `$game`: type `query`,
     `query: "label_values(game_info, gameName)"`, `refresh: 2` (on time range
     change), `sort: 1`.
   - `$gameId`: type `query`,
     `query: "label_values(game_info{gameName=\"$game\"}, gameId)"`,
     `hide: 2` (hidden), `refresh: 2`.
2. Panels (all filtering `{gameId="$gameId"}`), concrete PromQL:
   - **Total bot groups (stat):**
     `count(count by (botGroupId) (bot_messages_total{gameId="$gameId"}))` —
     distinct botGroupId count for the selected game. (Falls back to any
     per-group series; `bot_messages_total` is the most reliably non-zero.)
   - **Total bots (stat):**
     `sum(bots_by_game_status{gameId="$gameId"})`.
   - **Bots by status (timeseries, stacked):**
     `bots_by_game_status{gameId="$gameId"}` legend `{{status}}`. Covers
     connected (`CONNECTION_AUTHENTICATED`), reconnecting, dead, disconnected —
     whichever `BotStatus` values are live.
   - **RTP (stat, percentunit):**
     `(sum(rate(bot_winnings_total{gameId="$gameId"}[5m])) / sum(rate(bot_bet_amount_total{gameId="$gameId"}[5m]))) or vector(0)`.
   - **Bets placed rate (timeseries, ops):**
     `sum(rate(bot_bets_placed_total{gameId="$gameId"}[1m]))`.
   - **Bet amount rate (timeseries):**
     `sum(rate(bot_bet_amount_total{gameId="$gameId"}[1m]))`.
   - **Winnings rate (timeseries):**
     `sum(rate(bot_winnings_total{gameId="$gameId"}[5m]))`.
   - **Jackpots (timeseries, ops):**
     `sum(rate(bot_jackpots_total{gameId="$gameId"}[5m]))` and a companion
     `bot_jackpot_amount_total` panel.
   - **Failures rate (timeseries, ops):**
     `sum(rate(bot_failures_total{gameId="$gameId"}[5m]))`.
   - **Dead-time (stat, seconds):**
     `sum(increase(bot_dead_seconds_total{gameId="$gameId"}[1h]))`.
3. Panel descriptions note the AD-7 rollout caveat (pre-deploy series lack
   `gameId` and are excluded by design).

Verification: see `## Verification`.

### Phase 5 — Per-Environment dashboard JSON
Goal: `grafana/provisioning/dashboards/per-environment.json`, `$environment`
dropdown (name) → `$environmentId` (id).

Steps:
1. `templating.list`:
   - `$environment`: `label_values(environment_info, environmentName)`.
   - `$environmentId` (hidden):
     `label_values(environment_info{environmentName=\"$environment\"}, environmentId)`.
2. Panels (filtering `{environmentId="$environmentId"}`):
   - **Total bot groups / total bots (stat):**
     `count(count by (botGroupId) (bot_messages_total{environmentId="$environmentId"}))`
     and `sum(bots_by_env_status{environmentId="$environmentId"})`.
   - **Bots by status (stacked):** `bots_by_env_status{environmentId="$environmentId"}`.
   - **Throughput (bets/amount rate):**
     `sum(rate(bot_bets_placed_total{environmentId="$environmentId"}[1m]))`,
     `sum(rate(bot_bet_amount_total{environmentId="$environmentId"}[1m]))`.
   - **RTP overall:**
     `(sum(rate(bot_winnings_total{environmentId="$environmentId"}[5m])) / sum(rate(bot_bet_amount_total{environmentId="$environmentId"}[5m]))) or vector(0)`.
   - **RTP per game within env (timeseries):**
     `(sum by (gameName) (rate(bot_winnings_total{environmentId="$environmentId"}[5m])) / sum by (gameName) (rate(bot_bet_amount_total{environmentId="$environmentId"}[5m]))) or vector(0)`
     legend `{{gameName}}` — lets an operator see which game drags the env.
   - **Failures / reconnects / dead-time** for the env, same shape as Phase 4 but
     filtered by `environmentId`.

Verification: see `## Verification`.

### Ordering note
Phases 1-3 are backend and must merge before Phases 4-5 (the dashboards reference
the new labels/metrics). Phase 1 is independent; Phases 2 and 3 both touch
`ObservabilityConfig` and `BotGroupBehaviorService` and can land together. Phases
4 and 5 are pure JSON and independent of each other.

## Implementation Notes / Concerns

- **Two MDC set sites must stay in sync.** Forgetting `BotGroupRuntime.startBot`
  (BotGroupRuntime.java:130) while updating `Bot.initialize` produces series that
  carry `gameId` on some threads and not others — a silent split that breaks
  dropdown/panel joins. Edit both; the verification asserts both paths.
- **`gameType` value change is a behavioral change to existing dashboards.**
  After Phase 1, `bots.json` panels grouping `by (gameType)` switch from grouping
  by game *name* (e.g. "BauCua") to grouping by enum (e.g. "BETTING_MINI"). This
  is the correct fix but will visibly change legends on the existing Bots
  dashboard. Optionally update those `bots.json` legends to `by (gameName)` if the
  per-name view is still wanted — flag to the user. Do NOT silently leave a panel
  titled "per game type" grouping by name.
- **`Game.getId()` vs `Game.gameId`** (AD-8): use `getId()` (the UUID `_id`).
  Using the Integer `gameId` would collide across products/envs and make the
  `$game` filter select multiple unrelated games.
- **`MultiGauge` refresh semantics.** `MultiGauge.register(rows)` replaces the row
  set; call it inside the gauge's value-supplier path so each scrape reflects live
  bot state. A stale row for a stopped game must be dropped, else the dropdown
  keeps a dead game forever. Verify a game that stops disappears from
  `game_info`/`bots_by_game_status` on the next scrape.
- **environmentName resolution cost.** Resolving env name by id on every scrape
  via the repository is a DB hit per scrape (10s interval). Cache it (the env set
  is tiny and rarely changes) or thread the name into `BotGroupRuntime` at start.
  See Open Items — confirm with the user before implementing.
- **Grafana variable refresh.** Set variable `refresh: 2` (on time-range change)
  not `1` (on dashboard load only) so newly started games/envs appear without a
  full reload, matching the "auto-adapting" goal.
- **`or vector(0)` on every ratio panel** (AD-7) to avoid "No data" when a freshly
  selected game has no bets yet.
- **Logging levels:** no new log lines are required; the gauges and labels are
  metric-only. If any INFO is added for the info-gauge registration, one line at
  startup is fine per CLAUDE.md.

## Open Items

- **RESOLVED (user 2026-06-26) — panel list:** use the **recommended default set**
  (per-Game: total bot groups, total bots + status breakdown, RTP, bets placed, bet
  amount, winnings, jackpots, failures, dead-time; per-Env: the same aggregated
  across the env). Extras (reconnect-by-reason, auto-deposit, watchdog) are out of
  v1 scope.
- **RESOLVED (user 2026-06-26) — display fields:** use `Game.getName()` and
  `Environment.getName()` as the operator-facing display strings.
- **RESOLVED (user 2026-06-26) — environmentName resolution:** thread the name into
  `BotGroupRuntime` at group start (no per-scrape DB hit). Proceed with the one-line
  field + constructor change.
- **RESOLVED (user 2026-06-26) — `gameType` label bug:** **fix it properly** —
  relabel `gameType` to the actual enum (`game.getGameType()`), add a separate
  `gameName` label (`game.getName()`), AND **repoint the existing `bots.json`
  panels** that group `by (gameType)` so their legends stay correct (they will now
  show the enum rather than the game name). This is a load-bearing correction, not
  optional.
- **Future "all games overview" dashboard** (AD-5): a third dashboard with
  repeating rows or a table across all games is deferred / out of scope for v1.
- **Slot games sharing a numeric `gid`** is handled by AD-8 (filter on UUID `_id`,
  not numeric gid); no action needed, noted for awareness.

## Verification

These run on staging after deploy. This feature has on-server verification beyond
the universal smoke test. Replace `<host>` with the bot-manager actuator host
(internal `:8085` in-container, or the mapped host port).

1. **New labels are emitted on bot counters.** After a bot group with a known
   game has run ~30s:
   ```
   curl -s '<host>/actuator/prometheus' | grep '^bot_bets_placed_total' | head
   ```
   Expect at least one line containing both `gameId="..."` and `gameName="..."`
   (and `gameType="SLOT"` or `"BETTING_MINI"`, i.e. the enum, not a game name).
   Confirm `gameId` is a UUID, not a small integer.

2. **`game_info` / `environment_info` join metrics exist.**
   ```
   curl -s '<host>/actuator/prometheus' | grep -E '^game_info|^environment_info'
   ```
   Expect one `game_info{gameId="...",gameName="...",gameType="..."} 1` per
   running game and one `environment_info{environmentId="...",environmentName="..."} 1`
   per running environment.

3. **Per-game/per-env status gauges populate.**
   ```
   curl -s '<host>/actuator/prometheus' | grep -E '^bots_by_game_status|^bots_by_env_status'
   ```
   Expect series tagged with `gameId`/`gameName`/`status` and
   `environmentId`/`status`; the sum of `bots_by_game_status` for a known game
   equals that group's bot count.

4. **Prometheus has scraped the new series.** Against Prometheus (default
   `:9090`):
   ```
   curl -s 'http://<prom-host>:9090/api/v1/query?query=count(bot_bets_placed_total{gameId!=""})'
   ```
   Expect `data.result[0].value[1]` (a count) `> 0`.

5. **Per-Game dashboard loads and the `$game` variable populates.** After Grafana
   container restart (dashboards auto-load from
   `grafana/provisioning/dashboards/*.json`):
   ```
   curl -s -u <grafana-user>:<pass> 'http://<grafana-host>:3000/api/dashboards/uid/per-game'
   ```
   Expect HTTP 200 and a body whose `dashboard.templating.list` contains a `game`
   variable. Then query the variable's option set:
   ```
   curl -s -u <grafana-user>:<pass> -G 'http://<grafana-host>:3000/api/datasources/proxy/uid/prometheus/api/v1/label/gameName/values' \
     --data-urlencode 'match[]=game_info'
   ```
   Expect the running games' readable names in `data[]`.

6. **Per-Environment dashboard loads and `$environment` populates.**
   ```
   curl -s -u <grafana-user>:<pass> 'http://<grafana-host>:3000/api/dashboards/uid/per-environment'
   ```
   Expect HTTP 200 with an `environment` template variable. Confirm
   `label_values(environment_info, environmentName)` returns the running env
   name(s).

7. **A selected-game RTP panel returns a value (not "No data").** Pick a `gameId`
   from step 1 and query the panel's PromQL directly:
   ```
   curl -s -G 'http://<prom-host>:9090/api/v1/query' \
     --data-urlencode 'query=(sum(rate(bot_winnings_total{gameId="<gameId>"}[5m])) / sum(rate(bot_bet_amount_total{gameId="<gameId>"}[5m]))) or vector(0)'
   ```
   Expect a numeric result (>= 0; `or vector(0)` guarantees a value even with no
   bets).

8. **Bot-1 co-located stack re-verified.** Per the bot-1 single-compose layout,
   redeploying bot-manager takes down Grafana/Prometheus/Loki too; the Grafana
   container restart is what loads the new dashboards. After the full redeploy,
   re-run the universal smoke (Grafana/Prometheus/Loki all UP) and confirm both
   new dashboards appear in the Grafana dashboard list.
```

## Amendment — 2026-06-26 (Compliance / Architect-2)

**What changed:** The Phase 2 / Phase 3 `MultiGauge` refresh mechanism.

**Original text (AD-3 and Implementation Notes → "MultiGauge refresh
semantics"):** prescribed calling `MultiGauge.register(rows)` "inside the gauge's
value-supplier path so each scrape reflects live bot state," and Phase 2 step 2
implied registering the info gauges within
`ObservabilityConfig.botAggregateGauges`.

**Why it was wrong (falsifiable):** Micrometer's `MultiGauge` (verified against
`micrometer-core` 1.14.5) exposes only the imperative methods
`register(Iterable<Row>)` and `register(Iterable<Row>, boolean)`. There is no
value-supplier / sample-on-scrape callback for a `MultiGauge`, unlike the simple
`Gauge.builder(state, fn)` used by the aggregate gauges. The prescribed
"value-supplier path" does not exist for this meter type, so the plan's stated
mechanism is not implementable as written.

**Accepted realization:** The four `MultiGauge`s (`game_info`,
`environment_info`, `bots_by_game_status`, `bots_by_env_status`) are owned by a
new `InfoGaugeRefresher` component that re-`register(...)`s their full row set on
a dedicated single-thread virtual-thread scheduler at a fixed 10s cadence aligned
to the Prometheus scrape interval. Full row-set replacement each cycle drops
stopped games/envs (the stale-row requirement). The four names remain on the
`AGGREGATE_METER_NAMES` allow-list so they never inherit MDC tags from the
refresher thread. This honors the intent of AD-2 and AD-3 in full; the only
practical difference is that row values are sampled at the 10s refresh rather
than exactly at scrape time, which is equivalent for these dashboards given the
matched cadence.

No other plan text is changed; all phases otherwise implemented as specified.
