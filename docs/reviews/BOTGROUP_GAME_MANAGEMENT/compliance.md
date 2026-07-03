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
