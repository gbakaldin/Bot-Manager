# Compliance — BOTGROUP_GAME_MANAGEMENT (Phases 1–2)

Branch: `feat/botgroup-game-management`
Plan reviewed: `docs/plans/BOTGROUP_GAME_MANAGEMENT.md` (at commit 8fafc65)
Diff reviewed: `git diff main..feat/botgroup-game-management`

Scope of this verdict: **Phase 1 only** (Game env-scoping: model, routes, filter,
`createdAt`/`updatedAt`, migration script). Phases 2–7 are not yet on the branch
and are out of scope for this review.

## Verdict

PASS

## Phase-by-phase

### Phase 1 — Game env-scoping: model, routes, filter, createdAt/updatedAt (+ migration script)
Status: implemented

Every plan bullet is delivered, and each is consistent with the governing
Architecture Decisions:

- `Game.java` — adds `environmentId`, `createdAt`, `updatedAt` (all `Instant`
  where dated), nullable in the entity so pre-migration docs deserialize during
  the deploy window. Matches AD-1 (nullable in model), AD-14, AD-16. Fields are
  documented with the AD references.
- `GameDTO.java` — adds the three fields, explicitly annotated read-side only.
  Matches the Phase 1 bullet.
- `GameMapper.java` — `toDTO` maps all three; `toEntity`/`updateEntityFromDTO`
  deliberately do **not** touch `environmentId`/`createdAt`/`updatedAt` (path +
  create + service.save are authoritative). Matches the Phase 1 mapper bullet and
  Implementation Note 8. Mapper is hand-built via the builder, so no stale
  generated-impl risk.
- `GameFilter.java` — drops `brandCode`/`productCode` (moved to path per AD-4);
  keeps `gameType`, `name`; correctly does **not** add `sortBy`/`sortDir` (those
  are Phase 5). Matches AD-4.
- `GameRepository.java` — adds `findByBrandCodeAndProductCodeAndEnvironmentId`.
- `GameService.findByBrandProductEnv` — delegates to the env-scoped repository
  query (exact match on `environmentId`).
- `GameService.filter(brand, product, envId, filter)` — always constrains
  brand + product; env criterion is an `$or` of `{environmentId: envId}` and
  `{environmentId: null}`, i.e. the AD-3 defensive null-env read-side fallback.
  `Criteria.is(null)` matches both explicit-null and missing fields in Mongo, so
  an unmigrated doc stays visible. Matches AD-3.
- `GameService.save` — stamps `createdAt = now` only when null; stamps
  `updatedAt = now` on every save. Matches AD-14/AD-16.
- `GameController.java` — list/create/filter reworked to the AD-4 env-in-path
  routes (`/{brandCode}/{productCode}/{envId}`, `.../filter`); create sets
  `environmentId` from the path exactly as it sets brand/product. Matches AD-4.
- `scripts/migrations/001_game_env_scope.js` — a single deterministic pass:
  builds the `(brandCode|productCode) → environment._id` map from the
  `environments` collection (verified: `Environment` carries `brandCode` and
  `productCode`), sets `environmentId` on every game from that map, backfills
  `createdAt`/`updatedAt` to a fixed `ISODate`. It **aborts loudly** on an
  ambiguous mapping and **logs (never silently skips)** any unmapped game, per
  AD-3 and the Phase 1 migration bullet. Idempotent; `createdAt` only set when
  absent. No cloning, no bot-group repointing. Correctly notes Spring Data
  persists the enums as name strings and keys on those.

Both dev clarifications verified against the plan:
- **List route is exact-match; fallback only on filter.** Consistent — the Phase 1
  plan text wires the null-env fallback only into `filter`, and the list
  (`findByBrandProductEnv`) delegates to the exact-match repository query.
  Verification #2's `jq` assertion (`.environmentId==null or ==ENV`) still holds
  under exact match (all returned rows are `==ENV`), so the plan's verification is
  achievable as written.
- **`update` routes through `save` for `updatedAt`.** Verified: `GameService.update`
  now calls `save(existing)` rather than `repository.save(existing)`, so every
  mutation re-stamps `updatedAt` (AD-16 / Implementation Note 9). `existing` is
  loaded from DB with its `createdAt`, which `save` preserves. `GameController`
  is the only caller of `update`.

## Build + suite

- `mvn test-compile` (full project, main + tests): BUILD SUCCESS.
- `mvn test -Dtest=GameServiceTest,GameControllerTest`: 33 run, 0 failures,
  0 errors. Covers env-scoped list delegation, filter env scoping incl. the
  null-env fallback branch, empty-body path-only scoping, and the
  createdAt/updatedAt stamping (create + re-save preserve-created/restamp-updated).
- No stale callers of the removed/renamed methods (`findByBrandAndProduct`,
  single-arg `filter`) remain anywhere in `src/main` or `src/test`.

(Note: the sandboxed test fork crashes with "forked VM terminated without
properly saying goodbye" — an environment/sandbox artifact, not a test failure;
the run is green once the fork is allowed to start.)

## Drift

None. Diff faithfully implements Phase 1 as specified.

## Out-of-scope changes

None. The diff touches only the Game domain (model/dto/mapper/filter/repository/
service/controller), its tests, the migration script, and the plan doc itself.
No Phase 2–7 code leaked in early (e.g., no `sortBy`/`sortDir`, no
`BotGroupStatsDTO`, no cascade delete). This is correct given only Phase 1 is
reported done.

## Amendments to the plan

None.

---

# Compliance — BOTGROUP_GAME_MANAGEMENT — Phase 2

Branch: `feat/botgroup-game-management`
Plan reviewed: `docs/plans/BOTGROUP_GAME_MANAGEMENT.md` (at commit 8fafc65)
Diff reviewed: `git diff 16a84d6..HEAD` (single commit 2c32ded, exactly Phase 2)

Scope of this verdict: **Phase 2 only** (BotGroup env-in-path filter; hard-remove
`GET /` findAll; guarded gameId∈env validation at create). Phase 1 verdict above
is unchanged. Phases 3–7 are not yet on the branch.

## Verdict

PASS

## Phase-by-phase

### Phase 2 — BotGroup env-in-path filter; remove `GET /`; gameId∈env validation
Status: implemented

Every Phase 2 plan bullet is delivered and consistent with AD-5/AD-6/AD-7 and the
Implementation Notes:

- **`BotGroupFilter.java`** — `environmentId` removed; `name` and `gameId` kept.
  `sortBy`/`sortDir` correctly NOT added (those are Phase 4). Matches the Phase 2
  bullet and AD-5.
- **`BotGroupService.filter(String environmentId, BotGroupFilter)`** — env now
  comes from the mandatory arg and is **always** applied as a criterion
  (`Criteria.where("environmentId").is(environmentId)`); the old optional-body
  branch is gone. `name`/`gameId` narrow within that scope. An empty body yields a
  query whose only criterion is `environmentId` (asserted by the strengthened unit
  test `containsExactly("environmentId")`). Matches AD-5 and Verification #4.
- **`BotGroupController.java`** — `POST /filter/` replaced by
  `POST /{envId}/filter` with `@PathVariable envId` threaded into the service
  (AD-5). `GET /` findAll is hard-deleted — method, mapping, and OpenAPI block all
  removed, no redirect/deprecation shim (AD-6). `GET /{id}`, `/{id}/health`,
  `/{id}/status`, lifecycle endpoints, and `POST /` create are untouched, as AD-6
  requires. Verified `service.findAll()` remains (still used by
  `EnvironmentController`) — only the HTTP endpoint was removed.
- **gameId∈env validation (AD-7, non-vetoable)** — `validateGameEnvironmentMatch`
  is invoked on the new-group path immediately after `configValidation.validate`
  (the AD-7 ordering: "after config validation"). It looks up
  `gameService.findById(group.gameId)` and throws `BadRequestException` (→ 400 via
  `RestExceptionHandler`) only when the game's `environmentId` is **non-null** and
  differs from the group's. Null-env game → allowed (defensive migration-window
  guard, AD-7 / AD-3 / Impl Note 2). `GameService` is injected via constructor;
  `BadRequestException` already imported. Matches AD-7 and Verification (implicit
  in create path).

## Judgment call — no-op when `gameId` is null

The Dev flagged one addition not literally spelled out in AD-7: a
`if (gameId == null) return;` guard before the `findById` lookup. Assessment:

- **Consistent with the plan's stated intent.** AD-7 says the check "is guarded so
  it never blocks the Game migration window," and Impl Note 2 says the fallback
  exists so an unmigrated/edge-case game "does not spuriously trip the AD-7
  validation." A null `gameId` would make `gameService.findById(null)` throw
  (not-found / illegal-arg), which would block group creation for a reason
  unrelated to an env mismatch — exactly the "never blocks" failure mode AD-7
  is written to avoid. The no-op honors that intent.
- **AD-7 is silent, not contradicted.** AD-7 enumerates the null-`environmentId`
  case but does not address null `gameId`; the Dev filled an unspecified gap in
  the one direction consistent with the surrounding decisions. This is not drift
  (no specified behavior was deviated from) and not a plan error (the plan made no
  false assumption). Per the drift policy this is a Dev judgment call within the
  plan's intent, not a case for PLAN_AMENDED.
- **No amendment needed.** The behavior is defensive-only (staging has 0 groups
  with null `gameId`, AD-3), fully covered by a dedicated unit test
  (`shouldSkipWhenNoGameId` asserts `gameService.findById` is never called), and
  introduces no contradiction with the plan text. Recording it here in the
  compliance record is sufficient; the plan does not require editing.

## Build + suite

- `mvn -o test -Dtest=BotGroupServiceTest,BotGroupControllerTest` (with the
  project JDK 21): **BUILD SUCCESS**, `Tests run: 57, Failures: 0, Errors: 0,
  Skipped: 0`.
- New/updated Phase 2 coverage verified green: filter always env-scoped
  (`shouldScopeByEnvironmentId`), name/gameId narrowing carries the env scope,
  empty-body path-only scoping (`shouldReturnAllInEnvWhenBodyEmpty`), env-mismatch
  → 400 (`shouldRejectWhenGameEnvMismatch`, also asserts no registration fan-out
  and no persist), env-match accepted, null-env game accepted, and null-gameId
  no-op. Controller: `unscopedListAllIsGone` asserts `GET /api/v1/bot-group/` now
  returns 4xx and `service.findAll()` is never called; filter tests hit
  `POST /{envId}/filter` and verify the env id is passed from the path.
- No stale callers of the old single-arg `filter(BotGroupFilter)` or of
  `BotGroupFilter.getEnvironmentId()/setEnvironmentId()` remain in `src/main`.

## Drift

None. Diff faithfully implements Phase 2 as specified.

## Out-of-scope changes

None. The diff touches only `BotGroupController`, `BotGroupFilter`,
`BotGroupService` (main) and their two test classes. No Phase 3–7 code leaked in
early (no `BotGroupStatsDTO`, no `BotSortKey`, no `sortBy`/`sortDir`, no cascade
delete). Correct given only Phase 2 is reported done.

## Amendments to the plan

None.

---

# Compliance — BOTGROUP_GAME_MANAGEMENT — Phase 3

Branch: `feat/botgroup-game-management`
Plan reviewed: `docs/plans/BOTGROUP_GAME_MANAGEMENT.md`
Diff reviewed: `git diff 930001f..HEAD` (commits e7a7180, 25162ba, 62ce2af — exactly Phase 3)

Scope of this verdict: **Phase 3 only** (BotGroup statistics: runtime counters +
stats DTO). Phase 1 and Phase 2 verdicts above are unchanged. Phases 4–7 are not
yet on the branch.

## Verdict

PASS

## Phase-by-phase

### Phase 3 — BotGroup statistics (runtime counters + stats DTO)
Status: implemented

Every Phase 3 plan bullet is delivered and consistent with AD-8, AD-9, AD-10,
AD-13, and Implementation Notes 3/4/5:

- **`Bot.java`** — adds `final AtomicLong cumulativeWinnings` and
  `final AtomicLong roundsObserved`, both `@Getter`, both initialized to 0. The
  Javadoc cites AD-8/AD-9 and correctly states the mirror-`bot_winnings_total`
  and max-across-bots semantics. Matches the Phase 3 bullet.
- **`BettingMiniGameBot.onEndGame`** — `cumulativeWinnings.addAndGet(w)` is placed
  at the exact site that increments the metric, under the **same `w > 0` guard**
  but **not** gated on `metrics != null` — exactly AD-8 ("mirror value-for-value…
  incremented at the exact site that calls `metrics.incBotWinnings(w)`").
  `roundsObserved.incrementAndGet()` fires once per completed round in
  `onEndGame`, matching AD-9.
- **`SlotMachineBot`** — `cumulativeWinnings.addAndGet(winnings)` inside the
  `winnings > 0` block alongside `metrics.incBotWinnings`, again ungated on
  `metrics`. `roundsObserved.incrementAndGet()` fires on every spin-result,
  satisfying AD-9 and Implementation Note 3 (slot "rounds" = completed spins; the
  hook was wired rather than left at 0, which the note permitted as a fallback but
  did not prefer).
- **`BotGroupStatsDTO`** — new, all five fields boxed/nullable, exactly the plan's
  shape: `roundsSinceRestart` (Long), `activeTimeSeconds` (Long), `activeBots`
  (Integer), `averageBalance` (Long), `averageWinning` (Long). Javadoc documents
  the null=N/A convention and the active-only averaging. Matches AD-13.
- **`BotGroupBehaviorService.computeStats(String groupId)`** — reads
  `runningGroups`; null runtime → all-null block (`builder().build()`), so every
  field renders N/A for a not-running group (AD-13). For a running group:
  `roundsSinceRestart` = `max` of per-bot `roundsObserved` (0 when none yet),
  `activeTimeSeconds` = `Duration.between(startedAt, now).toSeconds()`,
  `activeBots` = count of `isConnected()` bots, and `averageBalance`/
  `averageWinning` are means over the **active (`isConnected`) bots only**,
  returning `null` (not 0) when `activeCount == 0`. This is AD-8/AD-9/AD-10 and
  Implementation Note 5 verbatim. No Prometheus access anywhere in the method
  (AD-4 / Implementation Note 4) — averages come only from the in-memory `Bot`
  accumulators.
- **Wiring** — stats embedded into `GET /{id}` (controller enrichment, kept out of
  the mapper deliberately so it stays off the create/update write surface, AD-13),
  into both branches of `getHealth` (`BotGroupHealthDTO.stats`, present on every
  response), and into the env-scoped filter list items
  (`BotGroupController.filter` sets `dto.setStats(...)` per item). `BotGroupDTO`
  gains a read-only nullable `stats` field, never mapped from the entity. This
  matches the Phase 3 wiring bullet exactly.

## The flagged item — stats embedded in the filter list items during Phase 3

Confirmed **within Phase 3 scope**, not premature Phase 4 work. The Phase 3 plan
bullet reads: "Wire stats into `GET /{id}` response and `GET /{id}/health` (embed
the block), **and into the Phase-2 filter list items (see Phase 4 DTO)**." The
parenthetical "see Phase 4 DTO" is a forward cross-reference (Phase 4 adds the
enriched-item sorting on top of these keys), not a deferral — the sentence's main
clause explicitly directs wiring the block into the filter list items in Phase 3.
AD-13 independently mandates the block be "embedded in **both** the filter list
items and `GET /{id}` / `/{id}/health`." The Dev embedded it via
`BotGroupDTO.stats`, which is the AD-13 shape (a dedicated `BotGroupStatsDTO`
embedded in the list item). Phase 4 will layer the comparator/sort over these same
enriched items; nothing here pre-implements Phase 4 (no `BotSortKey`, no
`sortBy`/`sortDir`, no comparator). No drift.

## Build + suite

`mvn clean install` (full project, JDK 21): **BUILD SUCCESS**,
`Tests run: 1140, Failures: 0, Errors: 0, Skipped: 0`. The dev's earlier
`-DskipTests` regression (two `getHealth` tests NPE'd on the new `.get()` calls
against un-stubbed Bot mocks) is fixed: `BotGroupBehaviorServiceTest` now stubs
`getRoundsObserved()` / `getCumulativeWinnings()` with non-null `AtomicLong`s
(commit 62ce2af). The full suite is green with tests enabled — verified by an
actual `mvn clean install` run, not a skip.

## Drift

None. Diff faithfully implements Phase 3 as specified.

## Out-of-scope changes

None. The diff touches only `Bot`, `BettingMiniGameBot`, `SlotMachineBot`
(counters), the new `BotGroupStatsDTO`, `BotGroupDTO`/`BotGroupHealthDTO` (stats
field), `BotGroupController`/`BotGroupBehaviorService` (enrichment), and one test
class. No Phase 4–7 code leaked in early. Correct given only Phase 3 is reported
done.

## Amendments to the plan

None.

---

# Compliance — BOTGROUP_GAME_MANAGEMENT — Phase 4

Branch: `feat/botgroup-game-management`
Plan reviewed: `docs/plans/BOTGROUP_GAME_MANAGEMENT.md` (at commit 8fafc65)
Diff reviewed: `git diff d0d667a..HEAD` (commits 3aad523, b317b90, 308c816 — exactly Phase 4)

Scope of this verdict: **Phase 4 only** (BotGroup sorting + `createdAt`/`updatedAt`).
Phases 5–7 are not yet on the branch and are out of scope for this review.

## Verdict

PASS

## Phase-by-phase

### Phase 4 — BotGroup sorting (+ `createdAt`/`updatedAt`)
Status: implemented

Every Phase 4 plan bullet is delivered and consistent with AD-11, AD-12, AD-14,
AD-16, and the Implementation Notes.

- **Timestamps on BotGroup (AD-14/AD-16).** `BotGroup.createdAt` and
  `BotGroup.updatedAt` (`Instant`) added
  (`BotGroup.java`). `BotGroupService.save` stamps `createdAt` once (only when
  null) and `updatedAt` on every save — matches AD-14 ("set on first save when
  null") and AD-16 ("stamped on every save"). Migration
  `scripts/migrations/002_botgroup_timestamps.js` backfills both to a fixed
  `ISODate` (`2026-07-03`), mirroring migration 001, Releaser-run, idempotent,
  with post-checks — matches the Phase 4 migration bullet and AD-14/AD-16.
- **`update()` → `save()` reroute.** `BotGroupService.update` now returns
  `save(existing)` instead of `repository.save(existing)`, so the `updatedAt`
  stamp is not bypassed on the PATCH path. This directly honors Implementation
  Note 9 ("Ensure every persistence path that mutates a group/game routes through
  the service `save`"). Test `updatePreservesCreatedAtRestampsUpdated` pins that
  `createdAt` is preserved and `updatedAt` is re-stamped.
- **`BotSortKey` enum — all 12 keys, no ENVIRONMENT.** STATUS, BOT_COUNT,
  CREATED_TIME, NAME, BET_AMOUNT, BALANCE, ACTIVE_BOTS, UPDATED_TIME, GAME_TYPE,
  AVG_WINNING, ACTIVE_TIME, MAX_PER_ROUND — exactly the plan's list, no
  ENVIRONMENT key (asserted absent by test `noEnvironmentKey`). Extractors match
  the plan's mapping: BET_AMOUNT → configured `maxBet`, MAX_PER_ROUND →
  `maxTotalBetPerRound`, BOT_COUNT → configured `botCount`, GAME_TYPE → resolved
  game's `gameType` name, UPDATED_TIME → `updatedAt`, CREATED_TIME → `createdAt`.
  Runtime keys read the Phase 3 `BotGroupStatsDTO`.
- **`equalsIgnoreCase` resolution / default / unknown → 400 (AD-11).**
  `BotSortKey.resolve` trims + `equalsIgnoreCase`; null/blank → `CREATED_TIME`;
  unknown → `BadRequestException` (→ 400). `SortDirection.resolve` is
  `equalsIgnoreCase` against asc/desc; null/blank/unknown → `DESC` (lenient, per
  AD-11 — only unknown *keys* are 400). All pinned by tests.
- **Load → enrich → in-memory sort (AD-11).** `BotGroupBehaviorService.filterSorted`
  loads via `BotGroupService.filter`, enriches each group into a `BotGroupSortRow`
  (persisted group + Phase 3 stats + `actualStatus` + resolved gameType looked up
  once per distinct gameId), then sorts in-memory via `BotGroupSorter`. No
  Mongo-side aggregation, no persisted derived sort fields. Controller reroutes the
  filter endpoint through `filterSorted`.
- **N/A deterministic ordering (AD-12).** `BotGroupSorter.compareNaLast` partitions
  present-vs-null, always appends N/A to the bottom regardless of direction, then
  `thenComparing` NAME asc, then id. Tests cover N/A-to-bottom for both dirs on
  BALANCE and GAME_TYPE, the N/A block ordered by NAME asc, and secondary
  tie-break by NAME then id independent of direction.
- **STATUS N/A semantics.** STATUS extracts `actualStatus` only when
  `activeTimeSeconds != null` (i.e. a runtime exists), so a stopped group's status
  sorts to the bottom — a reasonable realization of AD-12's "STATUS is a
  runtime-only key, N/A when inactive."

Full build + suite: `mvn clean test` (JDK 21) — **BUILD SUCCESS**,
`Tests run: 1171, Failures: 0, Errors: 0, Skipped: 0` (up from 1140 at Phase 3).
The stacktrace visible in build logs is a `RestExceptionHandler`-logged handled
exception from an unrelated controller test, not a failure.

## The flagged item — BotGroup timestamps landing in Phase 4

Confirmed **consistent with the plan as written**, not drift. AD-14 and AD-16
both state the fields go on *both* entities, and AD-14 explicitly splits the
migration into "the Phase 1 (Game) and Phase 4 (BotGroup) migration scripts."
The Phase 4 plan section is literally titled "BotGroup sorting (+
`createdAt`/`updatedAt`)" with the BotGroup timestamp addition as its first
bullet. Phase 1 correctly added the fields to Game only. So the plan **already
records** that the BotGroup timestamp addition lands in Phase 4 — no amendment is
needed and none is issued. The dev implemented exactly what AD-14/AD-16 + the
Phase 4 bullet specify.

## Drift

None. Diff faithfully implements Phase 4 as specified.

## Out-of-scope changes

None. The diff touches only the Phase 4 surface: `BotGroup` (timestamps),
`BotGroupService` (stamping + `update`→`save` reroute), `BotGroupFilter`
(`sortBy`/`sortDir`), the new `sort/` package (`BotSortKey`, `SortDirection`,
`BotGroupSortRow`, `BotGroupSorter`), `BotGroupBehaviorService` (`filterSorted`
+ `resolveGameTypes`), `BotGroupController` (reroute), migration 002, and the
associated tests. No Phase 5–7 code leaked in early. The `update`→`save` reroute,
while touching a Phase 2/3-era method, is squarely in service of AD-16 (Phase 4)
and is called out in Implementation Note 9 — not out-of-scope.

## Amendments to the plan

None.

---

# Compliance — BOTGROUP_GAME_MANAGEMENT — Phase 5

Branch: `feat/botgroup-game-management`
Plan reviewed: `docs/plans/BOTGROUP_GAME_MANAGEMENT.md`
Diff reviewed: `git diff 379eeb7..HEAD` (commits 2539023, d4321c5, 0e21cfd — exactly Phase 5: Game sorting)

Scope of this verdict: **Phase 5 only** (Game sorting). Phase 1–4 verdicts above are
unchanged. Phases 6–7 are not yet on the branch.

## Verdict

PASS

## Phase-by-phase

### Phase 5 — Game sorting
Status: implemented

Every Phase 5 plan bullet is delivered and consistent with AD-11/AD-12 and the
Implementation Notes:

- **`GameSortKey` enum** (`domain/game/sort/GameSortKey.java`) — exactly the seven
  specified keys: `CREATED_TIME`, `BOT_GROUP_COUNT`, `BOT_COUNT`, `GAME_TYPE`,
  `NAME`, `ACTIVE_GROUP_COUNT`, `ACTIVE_BOT_COUNT`. **No `BRAND`/`PRODUCT` key**
  (the list is already scoped by `(brand,product,env)` in the path). Each key maps
  to a value extractor over `GameSortRow`; `resolve(raw)` is case-insensitive
  (`equalsIgnoreCase`, trims, null/blank → `CREATED_TIME`), unknown → 400
  (`BadRequestException`). Default `CREATED_TIME`. Matches AD-11.
- **N/A gating (AD-12)** — `ACTIVE_GROUP_COUNT`/`ACTIVE_BOT_COUNT` extract `null`
  (→ N/A, sorts to bottom) when `!row.active()` (i.e. no referencing group
  running). `BOT_GROUP_COUNT`/`BOT_COUNT` are count aggregates — never N/A, `0`
  when the game has no referencing groups. `GAME_TYPE` is N/A when `gameType` is
  null. This is the same deterministic N/A-to-bottom behavior as Phase 4.
- **Aggregates over referencing bot groups** (`BotGroupBehaviorService.enrichGame`)
  — per game, loads `botGroupService.findByGameId(game.getId())` (delegates to
  `BotGroupRepository.findByGameId`; `BotGroup.gameId` = Game Mongo `_id`, Impl
  Note 1). `BOT_GROUP_COUNT` = `groups.size()`; `BOT_COUNT` = Σ configured
  `group.getBotCount()`; `ACTIVE_GROUP_COUNT` = Σ `isGroupRunning(id)`;
  `ACTIVE_BOT_COUNT` = Σ `getRunningBotCountForGroup(id)`. Computed once per game
  in the enrich pass, exactly as the plan specifies.
- **Load → enrich → in-memory sort (AD-11)** — `filterGamesSorted` loads via
  `gameService.filter(brand,product,env,filter)`, maps to `GameSortRow`, then
  `GameSorter.sort(rows, sortBy, sortDir)`. No Mongo-side aggregation, no persisted
  derived fields.
- **`sortBy`/`sortDir` on `GameFilter`** — added; Javadoc documents the
  resolution semantics. Matches the Phase 5 bullet.
- **Shared comparator util** — the Phase-4 null-last compare was extracted into a
  new `SortComparators.compareNaLast` (in the `botgroup.sort` package);
  `BotGroupSorter` now calls it and `GameSorter` reuses it plus `SortDirection`.
  `GameSorter.comparator` applies the same present-by-dir / N/A-to-bottom /
  tie-break-by-NAME-asc-then-id rule as Phase 4. Matches "same comparator util as
  Phase 4" and AD-12.

## Judgment call — `filterGamesSorted` placement (BotGroupBehaviorService, not GameController/GameService)

The Dev placed the orchestration method on `BotGroupBehaviorService` and had
`GameController` delegate to `behaviorService.filterGamesSorted(...)`, rather than
building the enrichment inline in the controller. Assessment: **faithful — no drift,
no amendment.**

- **Consistent with the plan's intent.** Phase 5's aggregate spec already names
  `behaviorService.isGroupRunning` and `getRunningBotCountForGroup` as the sources
  for the active-* counts, so the enrichment inherently lives with the runtime
  owner. AD-13 puts the analogous BotGroup enrichment on `BotGroupBehaviorService`
  ("it owns `runningGroups`"), and the accepted Phase-4 precedent placed
  `filterSorted` there too. Consolidating `filterGamesSorted` alongside it is the
  natural reading, not a deviation.
- **The cycle claim is technically real.** `BotGroupBehaviorService` injects
  `GameService` (constructor + field). Placing the enrichment in `GameService`
  would require `GameService` to inject `BotGroupBehaviorService` for the runtime
  counts → a genuine Spring circular dependency (`GameService →
  BotGroupBehaviorService → GameService`). Verified in the diff.
- **Not a plan error → not PLAN_AMENDED.** The plan made no false technical
  assumption: the controller-orchestration approach the plan implies is still
  possible, and the Dev instead chose the placement that mirrors the already-accepted
  Phase-4 precedent and sidesteps the cycle. Per the asymmetric drift policy this is
  a Dev structural judgment call within plan intent — recorded here, no plan edit
  required.

## Build + suite

- `mvn -o test-compile` (full project): BUILD SUCCESS.
- `mvn -o test` (full suite, project JDK 21): **Tests run: 1198, Failures: 0,
  Errors: 0, Skipped: 0 — BUILD SUCCESS.**
- Phase 5 coverage green: `GameSorterTest` (key resolution incl. case-insensitive,
  default, unknown→400, no BRAND/PRODUCT; per-key ordering; active-* N/A-to-bottom
  for both dirs; NAME-then-id tie-break; N/A block ordered by NAME),
  `BotGroupBehaviorServiceGameFilterSortedTest` (load→enrich→sort wiring, aggregate
  values over referencing groups, inactive→N/A, unknown key→400 through the real
  path), and the updated `GameControllerTest` (delegation to
  `behaviorService.filterGamesSorted`). `BotGroupSorterTest` still green after the
  `SortComparators` extraction.

## Drift

None. Diff faithfully implements Phase 5 as specified.

## Out-of-scope changes

None. The diff touches only the Phase 5 surface: the new `domain/game/sort/`
package (`GameSortKey`, `GameSortRow`, `GameSorter`), `GameFilter`
(`sortBy`/`sortDir`), `GameController` (delegation), `BotGroupBehaviorService`
(`filterGamesSorted` + `enrichGame`), `BotGroupService` (`findByGameId` passthrough),
the extracted `SortComparators` with `BotGroupSorter` rewired to it, and the
associated tests. The `SortComparators` extraction touches Phase-4 code but is a
pure refactor in direct service of "same comparator util as Phase 4" — not
out-of-scope. No Phase 6–7 code leaked in early.

## Amendments to the plan

None.

---

# Compliance — BOTGROUP_GAME_MANAGEMENT — Phase 6

Branch: `feat/botgroup-game-management`
Plan reviewed: `docs/plans/BOTGROUP_GAME_MANAGEMENT.md` (at commit 8fafc65)
Diff reviewed: `git diff ea589d5..HEAD` (commit f232b5e — exactly Phase 6: sort-key lookup endpoints)

Scope of this verdict: **Phase 6 only** (sort-key lookup endpoints). Phase 1–5
verdicts above are unchanged.

## Verdict

PASS

## Phase-by-phase

### Phase 6 — Sort-key lookup endpoints
Status: implemented

Both plan bullets are delivered and mirror the existing lookup pattern:

- `GET /api/v1/bot-group/sort-keys` — `BotGroupController.getSortKeys()` returns
  `ResponseEntity<List<String>>` built from
  `Arrays.stream(BotSortKey.values()).map(Enum::name).toList()`. Driven directly off
  the `BotSortKey` enum, so it cannot drift from the keys the Phase-4 filter accepts.
- `GET /api/v1/game/sort-keys` — `GameController.getSortKeys()`, same shape off
  `GameSortKey.values()`.

Both mirror the sanctioned lookup precedent (`GameController /types` uses the
identical `Arrays.stream(GameType.values()).map(...).toList()` shape; StrategyController /
BrandController /products are the same enum-to-list pattern named in the plan's
"Lookup-endpoint pattern already exists" finding).

**Enum coverage matches the plan spec exactly:**
- `BotSortKey` = STATUS, BOT_COUNT, CREATED_TIME, NAME, BET_AMOUNT, BALANCE,
  ACTIVE_BOTS, UPDATED_TIME, GAME_TYPE, AVG_WINNING, ACTIVE_TIME, MAX_PER_ROUND
  (the 12 keys named in Phase 4 / AD-11; no ENVIRONMENT key, as specified).
- `GameSortKey` = CREATED_TIME, BOT_GROUP_COUNT, BOT_COUNT, GAME_TYPE, NAME,
  ACTIVE_GROUP_COUNT, ACTIVE_BOT_COUNT (the 7 keys named in Phase 5; no
  BRAND/PRODUCT key, as specified).

**Route placement is safe.** `GET /sort-keys` is a literal-segment mapping and
coexists with `GET /{id}` in both controllers; Spring's literal-over-path-variable
precedence resolves `/sort-keys` to the lookup handler, not the by-id handler.
Confirmed against the mapping inventory (BotGroupController `/{id}` at :60 vs
`/sort-keys` at :77; GameController `/sort-keys` at :64 vs `/{id}` at :72).

**`List<String>` of enum names is a plan-sanctioned choice, not drift.** Phase 6
literally offers "`List<String>` (or `{key,label}` DTO mirroring `StrategyInfoDTO`)"
— the Dev took the first of the two plan-offered options. It is further the *only*
option that satisfies Verification step 9 as written: that step uses
`jq -e 'index("BALANCE") != null ...'` / `index("BOT_GROUP_COUNT")`, which is a
string-array membership test that requires a flat JSON array of strings
(`["BALANCE", ...]`); a `{key,label}` object array would make `index("BALANCE")`
return null and fail the check. The Dev's citation of step 9 is therefore correct.
Per the asymmetric drift policy this is an explicitly-offered plan alternative — no
deviation to record.

**Verification step 9 is achievable against this diff.** The bot-group endpoint
returns a JSON string array containing `"BALANCE"` and `"AVG_WINNING"` (both present
in `BotSortKey`); the game endpoint returns one containing `"BOT_GROUP_COUNT"`
(present in `GameSortKey`). Both `index(...)` assertions will pass.

## Tests

Both new slice tests match the plan's "trivial slice tests (200 + full key list)"
gate and assert the *complete* enum set rather than a hardcoded subset (so they stay
correct as keys are added):
- `BotGroupControllerTest.GetSortKeysTests#shouldReturnAllBotSortKeys` — 200,
  `$.length() == BotSortKey.values().length`, and `hasItem(name)` for every key.
- `GameControllerTest.GetSortKeysTests#shouldReturnAllGameSortKeys` — same over
  `GameSortKey`.

## Build + suite

- `mvn clean test` (full project, project JDK 21): **Tests run: 1204, Failures: 0,
  Errors: 0, Skipped: 0 — BUILD SUCCESS.**
- Note for the record: an initial run surfaced mass *Errors* across unrelated test
  classes. Root cause was a stray, uncompilable `src/main/java/com/vingame/bot/T.java`
  present in the working tree (untracked, not part of the Phase 6 commit); once it was
  gone the full suite was clean and each affected class passed in isolation. Not a
  Phase 6 defect and not committed — flagged so the Releaser confirms no such stray
  file ships.

## Drift

None. Diff faithfully implements Phase 6 as specified.

## Out-of-scope changes

None. The diff touches only the four Phase 6 surfaces: the two `getSortKeys`
handlers (`BotGroupController`, `GameController`) with their imports, and the two
slice tests. No production logic outside the lookup endpoints was modified; no
Phase 7 code leaked in.

## Amendments to the plan

None.

---

# Compliance — BOTGROUP_GAME_MANAGEMENT — Phase 7 (final)

Branch: `feat/botgroup-game-management`
Plan reviewed: `docs/plans/BOTGROUP_GAME_MANAGEMENT.md`
Diff reviewed: `git diff 966448e..HEAD` (commits c238993, ef35c6f — exactly
Phase 7: cascading deletes)

Scope of this verdict: **Phase 7 only** (cascading deletes). Phases 1–6 were
reviewed and PASSed in the sections above.

## Verdict

PASS

## Phase-by-phase

### Phase 7 — Cascading deletes
Status: implemented

Every plan bullet is delivered, and each is consistent with AD-15 and
Implementation Notes 6/7.

- `BotGroupBehaviorService.stopAndLogout(String id)` — implemented exactly as
  the Phase 7 bullet and AD-15 specify. Ordering: null-runtime → no-op (idempotent
  for a not-running group); flip `actualStatus` to STOPPED first (so a concurrent
  periodic-logout tick bails at its status gate before reconnecting a bot about to
  be torn down); per-bot `bot.logout()` — the same server-side logout the
  periodic-logout path uses (plan findings lines 85–86), minus the `restart()`,
  correctly omitted since the group is being deleted; then the existing stop
  teardown `runtime.stopAllBots(botMetrics)` (graceful WS close, executor/monitor/
  logout-scheduler shutdown, dead-window credit); then `sessionAggregationService
  .evictGroup(id)` and `runningGroups.remove(id)`. Matches the plan's
  "stop → logout → deregister-from-runtime" chain.
  - Per-bot `logout()` is wrapped in try/catch (log-and-continue) so one bad bot
    cannot abort the cascade — Implementation Note 7 satisfied.
  - MDC group context is set around the loop and cleared in `finally` — consistent
    with the logging norms (per-bot lines carry `botGroupId`/`environmentId`).
  - Deliberately does **not** persist STOPPED (unlike `stop`) because the sole
    caller deletes the document next — a documented, sensible deviation from
    `stop`, not drift. The plan said "reuse the stop teardown," which it does for
    the runtime half; skipping the DB write on a doomed document is within spec.
- `BotGroupService.delete` — `behaviorService.stopAndLogout(id)` then
  `repository.deleteById(id)`, in that order (InOrder-asserted in the test).
  Matches the Phase 7 bullet: own delete = stop → logout → delete.
- `GameService.delete` — for each `botGroupService.findByGameId(id)` →
  `botGroupService.delete(group.getId())`, then `repository.deleteById(id)`.
  Correctly keys on `findByGameId` (Implementation Note 1: `BotGroup.gameId` is the
  Game Mongo `_id`). Groups deleted before the game — no orphan. Matches the bullet.
- `EnvironmentService.delete` — cascade order Environment → Games → BotGroups:
  first every `gameService.findByEnvironmentId(id)` is deleted (each game cascades
  to its own groups), then any bot group still in the env via
  `botGroupService.findByEnvironmentId(id)` (fresh query, so already-deleted groups
  no longer appear — the "not already removed" semantics of the plan bullet), then
  the env document. Matches AD-15 ordering exactly.
- Supporting reads added: `GameRepository.findByEnvironmentId` +
  `GameService.findByEnvironmentId`. `BotGroupService.findByGameId`/
  `findByEnvironmentId` already existed (from earlier phases). All present.
- **No user deregistration on the bot server** — none added anywhere; each
  Javadoc explicitly records "no such API, leftover accounts expected" per AD-15
  and Open Item 6. Correct.

### `@Lazy` cycle-break — acceptable, plan-sanctioned, no plan note warranted
The cascade creates a Spring constructor-injection cycle
(`GameService → BotGroupService → GameService`, and
`EnvironmentService → BotGroupService`/`GameService`). Dev broke it with `@Lazy`
on the injected `BotGroupService`/`BotGroupBehaviorService` at three sites
(`GameService`, `EnvironmentService`, `BotGroupService`). Implementation Note 6
**explicitly lists `@Lazy` as one of the sanctioned options** ("...or use `@Lazy`
on the injected bot-group service. Decide before Phase 7 coding."). This is a
pre-approved implementation choice, not drift, and does **not** warrant a plan
amendment or note — the plan already anticipated it. The application context wires
and the full suite boots, confirming the cycle is actually broken.

## Build + suite

- `mvn clean test` (full project, JDK 21): **Tests run: 1209, Failures: 0,
  Errors: 0, Skipped: 0 — BUILD SUCCESS.** MapStruct regenerated cleanly on the
  clean build.
- Phase 7 test coverage present and asserting the right things: `stopAndLogout`
  logs-out-and-tears-down, no-op-when-not-running (idempotent), tolerates a bot's
  `logout()` throwing; `BotGroupService.delete` InOrder(stopAndLogout, deleteById);
  `GameService.delete` cascades to referencing groups before the game;
  `EnvironmentService.delete` InOrder(games, groups, env).

## Overall Verification section — satisfiable by the branch (final-phase check)

With Phase 7 landed, every step in the plan's `## Verification` now references
things that exist on the branch:
- Items 1–5 (app up, Game env list/filter, bot-group env-in-path filter, old
  `GET /` gone) → Phases 1–2.
- Item 6 (statistics block) → Phase 3.
- Items 7–8 (bot-group / game sorting) → Phases 4–5.
- Item 9 (sort-key lookups) → Phase 6.
- Item 10 (metrics unchanged) → AD-2, no code touched — regression guard holds.
- Item 11 (cascade delete: env with game+group → all gone, no orphan) → Phase 7.
  Achievable. Minor note: item 11's log-line hint says grep for a
  "`Periodic logout starting` / stop line"; the cascade actually emits
  `Bot group {} stopped and logged out (cascade delete)` (plus the per-bot logout
  lines). The grep-for-a-stop-line-tagged-with-the-group-id still succeeds, so the
  step is satisfiable as written — not worth a plan amendment.
- Item 12 (migration verification) → Releaser-run against the Phase 1 script,
  which ships on the branch.

All Verification steps are achievable against the current diff.

## Drift

None. The Phase 7 diff faithfully implements AD-15 and the Phase 7 plan bullets.

## Out-of-scope changes

None in the committed Phase 7 range (`966448e..HEAD`).

**Working-tree note for the Releaser (not drift, not blocking):** the working
tree has an *uncommitted* addition to
`src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceTest.java`
adding two further `stopAndLogout` tests (logout-before-teardown ordering; does
not persist STOPPED). They are additive test hardening, fully consistent with the
shipped implementation, and pass as part of the 1209-green run — but they are not
yet committed. Releaser should ensure they are committed (or intentionally
dropped) so the branch state matches what was verified. Separately, the earlier
`src/main/java/com/vingame/bot/T.java` stray flagged in the Phase 6 section is
**gone** from the working tree — resolved.

## Amendments to the plan

None.
