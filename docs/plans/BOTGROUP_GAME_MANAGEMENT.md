# Bot Group & Game Management API Enhancements

## Goal

Make the Game domain environment-scoped (today it is not — a game can only be
identified by brand+product, so "the same game in staging vs prod" cannot be
represented), rework the Bot Group and Game read/filter surfaces to be
env-scoped in the path, populate the per-group Statistics fields the UI currently
renders as N/A, add server-side sorting to both list surfaces, expose the sort-key
catalogs for the frontend, and make Environment/Game deletes cascade cleanly
(stop → logout → deregister-from-runtime → delete) so no orphan bot groups are
left behind. This is a set of contract + model changes; the load-bearing pieces
(Game env-scoping + migration, Bot Group env-in-path filter) ship first.

## Findings — Current State

### Game is NOT environment-scoped
- `Game` `@Document(collection="games")` has `brandCode`, `productCode`, `gameId`
  (numeric channel), but **no `environmentId`** —
  `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/model/Game.java:26-50`.
- Endpoints are brand+product only:
  - List: `GET /api/v1/game/{brandCode}/{productCode}` —
    `GameController.java:67-75`.
  - Create: `POST /api/v1/game/{brandCode}/{productCode}` —
    `GameController.java:93-104`.
  - Filter: `POST /api/v1/game/filter/` with brand/product in the body —
    `GameController.java:80-88`.
- `GameService.filter` keys on brandCode/productCode/gameType/name only, no env —
  `GameService.java:42-57`. `GameRepository.findByBrandCodeAndProductCode` —
  `GameRepository.java:13`. `GameFilter` fields: brandCode/productCode/gameType/name —
  `GameFilter.java:14-20`.

### BotGroup IS env-scoped, but list/filter/validation are weak
- `BotGroup.environmentId` exists — `BotGroup.java:27`. `BotGroup.gameId` — `:32`.
- **`BotGroup.gameId` holds the Game Mongo `_id` (UUID string), not
  `Game.gameId`** — confirmed by `gameService.findById(group.getGameId())` at
  `BotGroupBehaviorService.java:229`. `BotGroupDTO.gameId` is a `String`
  (`BotGroupDTO.java:42-43`).
- Filter carries env in the **body** and env is optional —
  `BotGroupFilter.java:14-16`, `BotGroupService.filter` `:70-82`.
- `GET /api/v1/bot-group/` lists across **all** environments unscoped —
  `BotGroupController.java:68-74`, `BotGroupService.findAll` `:58-60`.
- **Nothing validates that a group's `gameId` belongs to its `environmentId`** —
  create path is `BotGroupService.save` `:113-172`; only username-length and
  game-type config validation run.
- `BotGroupRepository` already has `findByEnvironmentId` and `findByGameId` —
  `BotGroupRepository.java:11,13`.

### Statistics fields — nothing is populated today
- `BotGroupHealthDTO` carries per-bot health but no group-level rounds / active-time /
  averages — `BotGroupHealthDTO.java:17-34`. `BotHealthDTO` exposes per-bot
  `balance` (`getExpectedBalance`), `lastRoundWinnings`, `totalBetsPlaced` —
  `BotHealthDTO.java:14-32`, built at `BotGroupBehaviorService.java:681-693`.
- `BotGroupRuntime` holds `startedAt` (Instant, reset on each start/restart),
  `botInstances` (live list) — `BotGroupRuntime.java:58,67,102-113`.
- **No in-memory cumulative winnings** — `Bot` tracks only
  `expectedCurrentBalance`, `totalBetsPlaced`, `totalBetAmount`, and a **volatile
  `lastRoundWinnings`** (per-round, not cumulative) —
  `Bot.java:90-104`, winnings updated in `BettingMiniGameBot.onEndGame`
  `:432-437`.
- **No per-group round counter** exists anywhere in-memory.
- `bot_winnings_total` is a Prometheus counter tagged via `mdcTags()` (which
  includes `botGroupId`) — `BotMetrics.java:240-245`, incremented at
  `BettingMiniGameBot.java:437`.

### Metrics are game-scoped and query Prometheus
- `GET /api/v1/metrics/game/{gameId}/summary|timeseries` resolve PromQL from
  `MetricKey` — `MetricsController.java:60-80`, `MetricsQueryService.java`. `gameId`
  here is the Game Mongo `_id` (join gauge `game_join`), and every `bot_*` series
  is tagged with that id via `mdcTags()`. **No change needed** (see AD-2).

### Lookup-endpoint pattern already exists
- `StrategyController` `GET /api/v1/strategy/` returns `List<StrategyInfoDTO>`
  from enum values — `StrategyController.java:29-39`.
- `GameController` `GET /types` returns `List<GameTypeDTO>` — `:49-52`.
- `BrandController` `GET /products` — `BrandController.java:26-29`.

### Cascade delete plumbing
- `GameService.delete` / `EnvironmentService.delete` / `BotGroupService.delete`
  are bare `repository.deleteById` — `GameService.java:72-74`,
  `EnvironmentService.java:126-128`, `BotGroupService.java:224-226`. No cascade.
- `EnvironmentController` already injects `BotGroupService` **and**
  `BotGroupBehaviorService` — `EnvironmentController.java:38-48` (used for
  env stat enrichment `:136-151`).
- Periodic-logout path: `bot.logout()` then `bot.restart()` in
  `performPeriodicLogout` — `BotGroupBehaviorService.java:1059-1074`.
  Group stop path: `behaviorService.stop(id)` → `runtime.stopAllBots(botMetrics)`
  → per-bot `bot.cleanup()` (graceful WS close) → remove from `runningGroups`
  → persist `STOPPED` — `BotGroupBehaviorService.java:550-585`,
  `BotGroupRuntime.java:243-289`. `Bot.logout()` / `Bot.cleanup()` /
  `Bot.restart()` — `Bot.java:248,199,217`.

### Migration tooling
- **No migration framework** (no Mongock / no `CommandLineRunner` migrations).
  The established pattern (BETTING_STRATEGIES Phase 6,
  `docs/plans/BETTING_STRATEGIES.md:121,589-593`) is **`updateMany` Mongo-shell
  scripts run by the Releaser**, with a **read-side fallback in code** covering
  the deploy window. `scripts/` exists but holds only ops helpers.

## Per-aspect readiness / mapping

| Aspect | Readiness | Notes |
|---|---|---|
| #1 Game `environmentId` field + endpoints | Ready | Field + route rework straightforward; migration is one deterministic `(brand,product)→env` backfill (staging inspected, no ambiguity). AD-3. |
| #1 Metrics stay env-correct | Ready | Transitive via `gameId`→one env. **No code change.** AD-2. |
| #2 BotGroup env-in-path filter | Ready | `findByEnvironmentId` exists; move env out of `BotGroupFilter` body. |
| #2 Remove `GET /` | Ready | Superseded; hard-remove (frontend does not use it). AD-6. |
| #2 gameId∈env validation | Ready | Post-#1; gate on game having a non-null `environmentId`. Included (not vetoable). AD-7. |
| #3 Active time | Ready | `runtime.startedAt`. |
| #3 Active bots | Ready | `runtime.botInstances` + `isConnected()`. |
| #3 Average balance | Ready | mean `getExpectedBalance()` over active bots. |
| #3 Average winning | Partial | No in-memory cumulative winnings — add `AtomicLong` mirroring `bot_winnings_total`. AD-8. |
| #3 Rounds since restart | Partial | No round counter — add per-bot `AtomicLong`, group = max. AD-9. |
| #4 BotGroup sorting | Ready | Load-all → enrich → in-memory sort. Depends on #3 for runtime keys. |
| #5 Game sorting | Ready | Aggregates over `findByGameId`. Depends on #1. |
| #6 Sort-key lookups | Ready | Mirror `StrategyController`. |
| #7 Cascade deletes | Ready | Reuse `behaviorService.stop` + `bot.logout()`; env controller already wired. |

## Architecture Decisions

1. **Game gains `environmentId` (nullable in the model, required on the new
   create route).** The same logical game in two environments is two separate
   `Game` documents, each with its own `_id` and `environmentId`. Read-side is
   env-filtered; the field is nullable in the entity so pre-migration docs still
   deserialize during the deploy window.

2. **Metrics are NOT changed.** Because each `gameId` (Game `_id`) now belongs to
   exactly one environment, the existing `GET /metrics/game/{gameId}/...` surface
   is transitively env-correct. Do not add an env filter to metrics. Do not touch
   `MetricKey`, `MetricsQueryService`, or the join gauges.

3. **Game backfill is a Releaser-run `updateMany` script + read-side fallback,
   NOT application code** (mirrors BETTING_STRATEGIES Phase 6). Staging was
   inspected (container `bot-java-mongo-1`, DB `botmanager`): **11 games, 5
   environments, 24 bot groups, 0 groups with null `gameId`, and every one of the
   5 `(brandCode, productCode)` combos maps to exactly ONE environment** (G2|P_097
   → "097 Staging", G2|P_098 → "098 Staging", G3|P_114 → "114 Staging", G3|P_116
   → "116 Staging", G4|P_118 → "118 Staging"). So the backfill is a **single
   deterministic pass**: for each `Game`, set `environmentId` = the `_id` of the
   sole environment whose `(brandCode, productCode)` match. **No cloning, no
   bot-group repointing, no ambiguous set, no operator review** — each `BotGroup`
   already points at a Game `_id` that resolves to exactly one env. The read-side
   fallback (env filter treats a null-`environmentId` game as matching any env) is
   **defensive-only** and should never fire on current data (the script sets every
   game's `environmentId` before traffic). Note: `Game.gameId` (the numeric
   channel) is null across all staging games — identity is the `_id` UUID,
   consistent with `BotGroup.gameId` = Game `_id`.

4. **New Game routes (env in path):**
   - List: `GET /api/v1/game/{brandCode}/{productCode}/{envId}`
   - Create: `POST /api/v1/game/{brandCode}/{productCode}/{envId}`
   - Filter: `POST /api/v1/game/{brandCode}/{productCode}/{envId}/filter`
   `GameFilter` drops `brandCode`/`productCode` (now path); keeps `gameType`,
   `name`, and gains `sortBy`/`sortDir`. Create sets `environmentId` from the path
   the same way it sets brand/product today (`GameController.java:100-101`).

5. **New Bot Group filter route (env in path):**
   `POST /api/v1/bot-group/{envId}/filter`. `environmentId` is **removed** from
   `BotGroupFilter` (moves to path, mandatory). An empty body returns all groups
   in that env. `BotGroupFilter` keeps `name`, `gameId`, and gains `sortBy`/`sortDir`.

6. **`GET /api/v1/bot-group/` (findAll) is hard-removed** — superseded by the
   env-scoped filter; the current frontend does not use it, so no redirect or
   deprecation shim. `GET /{id}`, `/{id}/health`, `/{id}/status`, and the
   lifecycle endpoints are unchanged. (`EnvironmentController` internally calls
   `botGroupService.findAll()` — that service method stays; only the HTTP endpoint
   is removed.)

7. **gameId∈env validation (included).** On `BotGroupService.save`
   (new-group path only), after config validation, look up
   `gameService.findById(group.gameId)`; if that game's `environmentId` is
   non-null and does not equal `group.environmentId`, throw `BadRequestException`
   (→ 400). Skip the check when the game's `environmentId` is null (defensive —
   should not occur once the Phase 1 backfill has run). This is guarded so it never
   blocks the Game migration window.

8. **Average winning is computed from a new in-memory cumulative accumulator**,
   not from Prometheus. Add `AtomicLong cumulativeWinnings` to `Bot`, incremented
   in `onEndGame` at the exact site that calls `metrics.incBotWinnings(w)`
   (`BettingMiniGameBot.java:437`) so it mirrors `bot_winnings_total` value-for-value.
   Group average winning = mean `cumulativeWinnings` over **active** bots. Rationale:
   the sort path (AD-11) must enrich thousands of groups in-memory; a per-group
   Prometheus query is a non-starter.

9. **Rounds since last restart = max over the group's bots of a per-bot
   round counter.** Add `AtomicLong roundsObserved` to `Bot`, incremented once per
   completed round (`onEndGame` for betting/Tai Xiu; slot spin-result for slots).
   The group value is `max` across bots (dedup-free and robust to server-side
   subscriber pruning where some bots stop receiving). The counter resets naturally
   because bots are freshly constructed on every start/restart. "Active time" =
   seconds between `runtime.startedAt` and now.

10. **"Active" bot definition:** `bot.isConnected()` (matches the existing
    `connectedBots` count at `BotGroupBehaviorService.java:695`). Average
    balance/winning are computed over active bots only; if zero active bots →
    the average is **N/A (null)**.

11. **Sorting mechanism: load-all-from-Mongo → enrich-in-memory-with-runtime →
    sort-in-memory.** No Mongo-side aggregation, no persisted derived fields. Scale
    is ≤ thousands of groups/games. Sort keys resolved from the incoming string via
    `equalsIgnoreCase`; unknown key → `BadRequestException` (400). Default when
    `sortBy` absent: `CREATED_TIME` desc. `sortDir` resolved via `equalsIgnoreCase`
    against `asc`/`desc`; default `desc`.

12. **N/A ordering is deterministic: N/A always sorts to the bottom regardless of
    `sortDir`.** Runtime-only keys (STATUS, BALANCE, ACTIVE_BOTS, ACTIVE_TIME,
    AVG_WINNING for groups; ACTIVE_* for games) are N/A when the group/game is
    inactive. Ties and N/A blocks break by `NAME` ascending (stable secondary),
    then by `id`. Implement as a comparator that partitions present-vs-N/A first,
    orders present values by `sortDir`, and always appends N/A.

13. **Stats live in a dedicated `BotGroupStatsDTO`, embedded in both the filter
    list items and `GET /{id}` / `/{id}/health`.** Do not pollute the create/update
    `BotGroupDTO` write surface. All stats fields are nullable boxed types; null =
    N/A. Enrichment is a new method on `BotGroupBehaviorService` (it owns
    `runningGroups`).

14. **`CREATED_TIME` sourcing — add `createdAt`.** Neither `BotGroup` nor `Game`
    has a `createdAt` field today, and Mongo `_id` is a UUID string
    (`UUID.randomUUID()`, `BotGroupService.java:165`, `GameService.java:61`) —
    **not** an ObjectId, so it carries no timestamp. Decision (confirmed): **add a
    `createdAt` (`Instant`) field** to both `BotGroup` and `Game`, set on first
    save when null. The Phase 1 (Game) and Phase 4 (BotGroup) migration scripts
    **backfill existing docs to a fixed timestamp** (these are throwaway staging
    test docs — the exact value is immaterial; use a single `ISODate` ~deploy day
    so nothing sorts as N/A on `CREATED_TIME`).

15. **Cascade delete order is Environment → Games → BotGroups**, and each affected
    bot group is **stopped → logged out (per-bot `bot.logout()`, reusing the
    periodic-logout path) → removed from `runningGroups` → deleted** before the
    parent. No attempt to deregister users on the bot server (there is no such API;
    leftover accounts are expected and fine). Bot-group's own delete becomes the
    same stop→logout→delete sequence.

16. **`UPDATED_TIME` sourcing — add `updatedAt`.** Add a dedicated `updatedAt`
    (`Instant`) field to both `BotGroup` and `Game`, stamped on **every** save
    (create and update). `UPDATED_TIME` sort reads this field directly (no
    derivation from audit stamps). Backfill existing docs to the same fixed
    timestamp as `createdAt` in the migration scripts.

## Plan

### Phase 1 — Game env-scoping: model, routes, filter, `createdAt`/`updatedAt` (+ migration script)
Goal: Game becomes env-scoped end to end; existing groups still start via read-side
fallback.
- `Game.java`: add `private String environmentId;`, `private Instant createdAt;`,
  `private Instant updatedAt;`.
- `GameDTO.java`: add `environmentId`, `createdAt`, `updatedAt` (all read-side).
- `GameMapper.java`: map the three fields in `toDTO`; in `toEntity` leave
  `environmentId` unset (set from path in controller, mirroring brand/product at
  `GameController.java:100`); `updateEntityFromDTO` must **not** overwrite
  `environmentId`/`createdAt` (path + create are authoritative).
- `GameFilter.java`: remove `brandCode`/`productCode`; keep `gameType`, `name`.
  (sortBy/sortDir added in Phase 5.)
- `GameRepository.java`: no env-scoped read method — a derived query cannot express
  the null-env `$or` fallback, so **both** env-scoped read paths build the scope
  `Query` in `GameService` instead (see below). (Corrected: an earlier draft added a
  derived `findByBrandCodeAndProductCodeAndEnvironmentId`, which does a strict
  `environmentId == envId` match with no fallback — that would have returned an empty
  list on the list route for every scope during the deploy window while `filter`
  returned the games. Fixed so both paths share one criteria builder.)
- `GameService.java`:
  - `findByBrandProductEnv(brand, product, envId)` and
    `filter(brand, product, envId, filter)` **share one private env-criteria helper**
    (`scopeQuery`) so their env semantics cannot drift. Brand and product are always
    constrained; the env criterion carries the null-env **read-side fallback** (a game
    with null/absent `environmentId` matches any env) on **both** paths — so an
    unmigrated doc stays visible on the list route exactly as it does on the filter
    route during the deploy window. Defensive-only per AD-3 — never expected to fire
    post-backfill.
  - `save`: set `createdAt = Instant.now()` when null; set `updatedAt = Instant.now()`
    on every save (AD-14, AD-16).
- `GameController.java`: rework list/create/filter to the AD-4 routes; set
  `environmentId` from path on create.
- **Migration script** (delivered as `scripts/migrations/<n>_game_env_scope.js`,
  documented, **run by Releaser** — Dev writes the script but does not run it). Per
  AD-3 the staging data is unambiguous, so the script is a single deterministic
  pass with no cloning and no bot-group repointing:
  1. Build the `(brandCode, productCode) → environment._id` map (5 entries; each
     combo maps to exactly one env).
  2. For each `Game`, `updateMany`-style set `environmentId` from that map keyed on
     the game's own `(brandCode, productCode)`.
  3. Backfill `createdAt` and `updatedAt` on every game to a single fixed
     `ISODate` (~deploy day) so nothing sorts as N/A (AD-14/AD-16).
  The script must log any game whose `(brandCode, productCode)` is not in the map
  (should be zero on staging) rather than silently skip it.
- Build gate: `mvn -q -DskipTests package` green; MapStruct regenerates cleanly.
- Test gate: unit tests for `GameService.filter` env scoping incl. null-env
  fallback; controller slice test for the three new routes.

### Phase 2 — BotGroup env-in-path filter; remove `GET /`; gameId∈env validation
Depends on Phase 1 (for the validation sub-item).
- `BotGroupFilter.java`: remove `environmentId`; keep `name`, `gameId`.
- `BotGroupService.filter(String envId, BotGroupFilter)` — env from arg (mandatory),
  name/gameId from body.
- `BotGroupController.java`: replace `POST /filter/` with
  `POST /{envId}/filter` (AD-5); **hard-delete** `GET /` findAll (AD-6).
- (AD-7, included) `BotGroupService.save` new-group path: add guarded gameId∈env
  check (mismatch → 400; null-env game → allowed defensively).
- Build + test gate: filter slice test (empty body returns all in env; name/gameId
  narrowing); validation unit test (mismatch → 400; null-env game → allowed);
  assert `GET /api/v1/bot-group/` no longer maps (404/405).

### Phase 3 — BotGroup statistics (runtime counters + stats DTO)
- `Bot.java`: add `AtomicLong cumulativeWinnings` and `AtomicLong roundsObserved`
  with getters.
- `BettingMiniGameBot.onEndGame` (`:432-437`): `cumulativeWinnings.addAndGet(w)`
  (guard w>0, same guard as the metric) and `roundsObserved.incrementAndGet()`.
  Slot bot: increment `roundsObserved` on spin-result (implementation note below).
- New `BotGroupStatsDTO` (all boxed, nullable): `roundsSinceRestart`,
  `activeTimeSeconds`, `activeBots`, `averageBalance`, `averageWinning`.
- `BotGroupBehaviorService`: `computeStats(String groupId)` reading `runningGroups`
  — null runtime → all-null stats. Averages over `isConnected()` bots; empty →
  null. Rounds = max `roundsObserved`; active time = `startedAt`→now seconds.
- Wire stats into `GET /{id}` response and `GET /{id}/health` (embed the block),
  and into the Phase-2 filter list items (see Phase 4 DTO).
- Build + test gate: `computeStats` unit tests (inactive → all N/A; active with
  known bot fakes → correct means, max rounds, active-only averaging).

### Phase 4 — BotGroup sorting (+ `createdAt`/`updatedAt`)
Depends on Phase 3 (BALANCE/ACTIVE_BOTS/ACTIVE_TIME/AVG_WINNING keys).
- `BotGroup.java`: add `createdAt` (AD-14) and `updatedAt` (AD-16);
  `BotGroupService.save` sets `createdAt` when null and `updatedAt` on every save.
  (Mirror the Game backfill: a `scripts/migrations/<n>_botgroup_timestamps.js`
  sets both on existing docs to the fixed `ISODate`, Releaser-run.)
- `BotSortKey` enum: STATUS, BOT_COUNT, CREATED_TIME, NAME, BET_AMOUNT, BALANCE,
  ACTIVE_BOTS, UPDATED_TIME, GAME_TYPE, AVG_WINNING, ACTIVE_TIME, MAX_PER_ROUND.
  (No ENVIRONMENT key.) Map each key to a value extractor over
  `(BotGroup, BotGroupStatsDTO, actualStatus)`.
  - BET_AMOUNT → configured `maxBet` (configured, per scope note); MAX_PER_ROUND →
    `maxTotalBetPerRound`; BOT_COUNT → configured `botCount`; GAME_TYPE → resolved
    game's `gameType` name (look up once per distinct gameId); UPDATED_TIME →
    the new `updatedAt` field (AD-16); CREATED_TIME → the new `createdAt` field.
- Filter: build enriched list items (`BotGroupDTO` + `BotGroupStatsDTO` +
  `actualStatus`), apply the AD-11/AD-12 comparator.
- `sortBy`/`sortDir` on `BotGroupFilter`.
- Build + test gate: comparator tests per key incl. N/A-to-bottom for both dirs;
  `equalsIgnoreCase` resolution; unknown key → 400.

### Phase 5 — Game sorting
Depends on Phase 1.
- `GameSortKey` enum: CREATED_TIME, BOT_GROUP_COUNT, BOT_COUNT, GAME_TYPE, NAME,
  ACTIVE_GROUP_COUNT, ACTIVE_BOT_COUNT. (No BRAND/PRODUCT key.)
- Aggregates per game via `botGroupRepository.findByGameId(game.getId())`:
  BOT_GROUP_COUNT = group count; BOT_COUNT = Σ configured `botCount`;
  ACTIVE_GROUP_COUNT = Σ `behaviorService.isGroupRunning`; ACTIVE_BOT_COUNT =
  Σ `getRunningBotCountForGroup`. Compute once per game in the enrich pass.
- `sortBy`/`sortDir` on `GameFilter`; same comparator util as Phase 4.
- Build + test gate: aggregate + comparator unit tests.

### Phase 6 — Sort-key lookup endpoints
- `GET /api/v1/bot-group/sort-keys` → `List<String>` (or `{key,label}` DTO mirroring
  `StrategyInfoDTO`) from `BotSortKey.values()`.
- `GET /api/v1/game/sort-keys` → from `GameSortKey.values()`.
- Build + test gate: trivial slice tests (200 + full key list).

### Phase 7 — Cascading deletes
- `BotGroupBehaviorService.stopAndLogout(String id)`: if running, set stopping,
  per-bot `bot.logout()`, then existing stop teardown (`stopAllBots` + remove from
  `runningGroups` + evict session aggregation); tolerate not-running groups.
- `BotGroupService.delete`: call `behaviorService.stopAndLogout(id)` then
  `repository.deleteById(id)`.
- `GameService.delete`: for each `botGroupRepository.findByGameId(id)` →
  `botGroupService.delete(group.id)`, then delete the game. (Inject the bot-group
  service; watch for a cycle — see Implementation Notes.)
- `EnvironmentService.delete` (or an orchestrator in the controller, which already
  has both services): for each game in env → `gameService.delete`; for each bot
  group in env not already removed → `botGroupService.delete`; then delete the env.
  Order per AD-15.
- Build + test gate: cascade unit tests with fakes (delete env → games + groups
  gone, `stopAndLogout` invoked per running group; no orphan left).

## Implementation Notes / Concerns

1. **`BotGroup.gameId` is the Game `_id`, not `Game.gameId`.** Every gameId-based
   lookup/aggregate uses the Mongo `_id`. Do not confuse with the numeric channel
   `Game.gameId`.
2. **Read-side fallback is defensive-only.** The staging backfill (AD-3) sets
   `environmentId` on all 11 games before traffic, so the null-env fallback should
   never fire. Keep it anyway so an unmigrated doc (or a game created between deploy
   and backfill) stays visible and does not spuriously trip the AD-7 validation.
   Ship it in Phase 1; a future cleanup can drop it once staging + any other env
   are confirmed fully backfilled.
3. **Slot rounds-observed:** slots have no StartGame/EndGame `sid` round
   (`SessionAggregationService` uses a synthetic per-`(group,gameId)` window). Wire
   `roundsObserved++` into the slot spin-result path; document that for slot groups
   "rounds" means completed spins. If the exact hook is awkward, leaving slot
   `roundsObserved` at 0 (→ N/A) is acceptable for v1 — flag to reviewer.
4. **Do not query Prometheus in the sort/enrich path.** AD-8/AD-11. Averages and
   winnings come from in-memory Bot accumulators only.
5. **Averages only over active (`isConnected`) bots.** A group with a live runtime
   but all bots reconnecting must still yield N/A averages, not 0.
6. **Cascade cycle risk:** `GameService` depending on `BotGroupService` which
   depends (transitively) back on game/env services can create a Spring circular
   dependency. Prefer orchestrating the cascade in a thin service or in the
   controller layer (`EnvironmentController` already injects both), or use
   `@Lazy` on the injected bot-group service. Decide before Phase 7 coding.
7. **`stopAndLogout` vs existing `stop`:** `stop` already closes WS via
   `bot.cleanup()`. The extra `bot.logout()` performs the server-side logout the
   periodic path uses; call it before teardown. Tolerate `logout()` throwing per
   bot (log + continue) so one bad bot doesn't abort a cascade.
8. **MapStruct:** adding fields to `Game`/`GameDTO` requires a clean rebuild so the
   generated mapper impl picks them up; a stale `target/generated-sources` can hide
   mapping gaps — do a `mvn clean` on Phase 1.
9. **`UPDATED_TIME` reads the new `updatedAt` field** (AD-16), stamped on every
   save. Ensure every persistence path that mutates a group/game routes through the
   service `save` so the stamp is not bypassed.
10. **`GET /bot-group/` is hard-removed** (AD-6) — the current frontend does not use
    it. Any out-of-band caller will get 404/405; that is intended.

## Open Items

All five decision items are **RESOLVED** (staging inspected; user-confirmed). Kept
here as a closed record.

1. **Game migration — RESOLVED (unambiguous).** Staging inspection (11 games, 5
   envs, 24 groups, 0 null `gameId`) shows every `(brandCode, productCode)` combo
   maps to exactly one environment. Backfill is a single deterministic
   `(brand,product)→env._id` pass — no cloning, no repointing, no ambiguous set, no
   operator review. See AD-3 and Phase 1.
2. **`createdAt` — RESOLVED (YES).** Added to both `Game` and `BotGroup`, stamped on
   create, existing docs backfilled to a fixed `ISODate`. See AD-14.
3. **`updatedAt` — RESOLVED (YES).** Dedicated `updatedAt` field on both entities,
   stamped on every save; `UPDATED_TIME` sort reads it. See AD-16.
4. **Remove `GET /bot-group/` — RESOLVED (hard-remove).** Frontend does not use it;
   no redirect/deprecation. See AD-6.
5. **gameId∈env validation — RESOLVED (include).** Kept in Phase 2, no longer
   vetoable. See AD-7.
6. **Out of scope (unchanged):** filter-name matching unification (BotGroup/Env
   whole-string vs Game substring); user deregistration on the bot server (no API,
   by design).

## Verification

Run after deploy to staging. `${BASE}` = staging bot-manager base URL,
`${ENV}` = a known environmentId, `${BRAND}`/`${PRODUCT}` a known pair,
`${GROUP}` a known bot group id, `${GAME}` a known game `_id`. Staging caveats
from ops memory: the bot-manager and observability stack share one Compose
project (redeploy restarts Grafana/Prometheus/Loki — re-verify them in the
universal smoke), and P_116/TIP groups cannot reach WS (DNS block) so use a
non-TIP env for any live-round checks.

1. **App up (universal smoke).**
   `curl -s -o /dev/null -w '%{http_code}' ${BASE}/actuator/health` — expect `200`.

2. **Game env-scoped list.**
   `curl -s -o /dev/null -w '%{http_code}' ${BASE}/api/v1/game/${BRAND}/${PRODUCT}/${ENV}`
   — expect `200`; body is a JSON array. Each element has `environmentId == ${ENV}`
   (or null for not-yet-migrated games):
   `curl -s ${BASE}/api/v1/game/${BRAND}/${PRODUCT}/${ENV} | jq -e 'all(.[]; .environmentId==null or .environmentId=="'${ENV}'")'`
   — expect exit `0`.

3. **Game env-scoped filter.**
   `curl -s -o /dev/null -w '%{http_code}' -X POST ${BASE}/api/v1/game/${BRAND}/${PRODUCT}/${ENV}/filter -H 'Content-Type: application/json' -d '{}'`
   — expect `200`.

4. **Bot-group env-in-path filter, empty body returns the env's groups.**
   `curl -s -X POST ${BASE}/api/v1/bot-group/${ENV}/filter -H 'Content-Type: application/json' -d '{}' | jq -e 'all(.[]; .environmentId=="'${ENV}'")'`
   — expect exit `0`.

5. **Old unscoped list is gone (AD-6).**
   `curl -s -o /dev/null -w '%{http_code}' ${BASE}/api/v1/bot-group/`
   — expect `404` (or `405`), **not** `200`.

6. **Statistics populated for a running group.** With `${GROUP}` started:
   `curl -s ${BASE}/api/v1/bot-group/${GROUP}/health | jq '.stats'`
   — expect a non-null object; `activeBots >= 0`, `activeTimeSeconds` a positive
   number. For a stopped group, expect the stats block present with null fields:
   `curl -s ${BASE}/api/v1/bot-group/${STOPPED_GROUP}/health | jq -e '.stats.activeBots==null'`
   — expect exit `0`.

7. **Bot-group sorting.**
   `curl -s -X POST '${BASE}/api/v1/bot-group/${ENV}/filter' -H 'Content-Type: application/json' -d '{"sortBy":"name","sortDir":"asc"}' | jq -e '[.[].name]==([.[].name]|sort)'`
   — expect exit `0` (names ascending). Unknown key:
   `curl -s -o /dev/null -w '%{http_code}' -X POST ${BASE}/api/v1/bot-group/${ENV}/filter -H 'Content-Type: application/json' -d '{"sortBy":"nonsense"}'`
   — expect `400`.

8. **Game sorting.**
   `curl -s -X POST ${BASE}/api/v1/game/${BRAND}/${PRODUCT}/${ENV}/filter -H 'Content-Type: application/json' -d '{"sortBy":"name","sortDir":"desc"}' | jq -e '[.[].name]==([.[].name]|sort|reverse)'`
   — expect exit `0`.

9. **Sort-key lookups.**
   `curl -s ${BASE}/api/v1/bot-group/sort-keys | jq -e 'index("BALANCE") != null and index("AVG_WINNING") != null'`
   — expect exit `0`.
   `curl -s ${BASE}/api/v1/game/sort-keys | jq -e 'index("BOT_GROUP_COUNT") != null'`
   — expect exit `0`.

10. **Metrics unchanged (regression guard).**
    `curl -s -o /dev/null -w '%{http_code}' ${BASE}/api/v1/metrics/game/${GAME}/summary`
    — expect `200` (game-scoped metrics still resolve; env-scoping did not alter
    the metrics surface).

11. **Cascade delete (use a disposable throwaway group/game/env created for the
    test — do NOT run against production data).** Create env `${T_ENV}` with one
    game `${T_GAME}` and one group `${T_GROUP}`; then:
    `curl -s -o /dev/null -w '%{http_code}' -X DELETE ${BASE}/api/v1/environment/${T_ENV}` — expect `200`;
    `curl -s -o /dev/null -w '%{http_code}' ${BASE}/api/v1/game/... /${T_GAME:not-applicable}` — instead verify no orphan:
    `curl -s -X POST ${BASE}/api/v1/bot-group/${T_ENV}/filter -H 'Content-Type: application/json' -d '{}'`
    — expect `404` (env gone) or an empty array, and
    `curl -s -o /dev/null -w '%{http_code}' ${BASE}/api/v1/game/{id}` for `${T_GAME}`
    — expect `404`. Confirm the app log shows a `Periodic logout starting` /
    stop line for `${T_GROUP}` during the cascade (grep the container log for the
    group id).

12. **Migration verification (Releaser, post-script).** After running the Phase 1
    backfill script against staging Mongo (DB `botmanager`):
    `mongosh --eval 'db.games.countDocuments({environmentId:{$exists:false}})'`
    — expect `0` (all 11 games backfilled; the data is unambiguous per AD-3, so
    there is no separate ambiguous set), and
    `mongosh --eval 'db.games.countDocuments({createdAt:{$exists:false}})'` — expect
    `0`, and
    `mongosh --eval 'db.botGroups.find({}).forEach(g=>{var game=db.games.findOne({_id:g.gameId}); if(game && game.environmentId && game.environmentId!=g.environmentId) print("MISMATCH "+g._id)})'`
    — expect no `MISMATCH` output (every group's game resolves to the group's env).
