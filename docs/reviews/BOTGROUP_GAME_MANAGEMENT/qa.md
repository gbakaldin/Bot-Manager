# QA — BOTGROUP_GAME_MANAGEMENT (Phase 1: Game env-scoping)

**Verdict:** PASS
**Build:** `mvn clean install` → 1133 tests, 0 failures, 0 errors (Game slice: 53 tests)

## Scope reviewed

`git diff main..feat/botgroup-game-management` — Phase 1 only:
- `Game` gains `environmentId` / `createdAt` / `updatedAt` (all nullable in the entity).
- `GameDTO` / `GameMapper` expose the three fields read-side; `updateEntityFromDTO`
  intentionally does not map them.
- `GameFilter` drops `brandCode`/`productCode` (moved to path).
- Routes reworked to `GET|POST /api/v1/game/{brand}/{product}/{envId}` and
  `POST .../{envId}/filter`; create sets `environmentId` from the path.
- `GameService.findByBrandProductEnv`, env-scoped `filter` with a defensive
  null-env `$or` read-side fallback, `save` timestamp stamping, `update` routed
  through `save`.
- `GameRepository.findByBrandCodeAndProductCodeAndEnvironmentId`.
- `scripts/migrations/001_game_env_scope.js` (Releaser-run, not executed here).

## Tests added / updated

Dev delivered a solid first pass; QA added 6 tests closing the load-bearing gaps
the task called out (env-in-path create, null-env fallback structure, createdAt
stamped once, updatedAt stamped on every save incl. via `update`, and the mapper's
refusal to mutate the three new fields).

- `src/test/java/com/vingame/bot/domain/game/service/GameServiceTest.java`
  - `FilterTests.envCriterionOrStructure` — parses the captured `Query`'s `$or`
    clause and asserts exactly two branches: one `{environmentId: "env-097"}` and
    one with an explicit-null `environmentId` (the defensive fallback), rather than
    just string-matching `"$or"`.
  - `UpdateTests.updateRestampsUpdatedAtAndPreservesCreatedAt` — `update` preserves
    the pre-existing `createdAt` and moves a stale `updatedAt` forward (AD-16).
  - `UpdateTests.updateStampsUpdatedAtWhenPreviouslyNull` — update of an unmigrated
    doc (no timestamps) stamps both; `createdAt == updatedAt`.
- `src/test/java/com/vingame/bot/domain/game/mapper/GameMapperTest.java`
  - `ToDTOTests.shouldMapEnvScopeAndTimestamps` — `toDTO` carries
    `environmentId`/`createdAt`/`updatedAt`.
  - `ToDTOTests.shouldEmitNullEnvScopeForUnmigratedEntity` — null-in / null-out.
  - `UpdateEntityFromDTOTests.shouldNotOverwriteEnvScopeOrTimestamps` — a write DTO
    carrying these fields cannot move a game between envs or rewrite its audit
    stamps; the mapper ignores all three (path + service are authoritative).

Pre-existing Dev tests kept and verified green: env-scoped `findByBrandProductEnv`
delegation, `filter` brand/product/env constraint + null-env fallback + gameType +
name-contains + empty-body, `save` UUID + create-time stamping + createdAt-preserve,
and the controller slice for all three reworked routes incl. the create path
capturing `environmentId` from the path.

## Coverage of the diff

- `Game.java` (new fields) ← `GameMapperTest`, `GameServiceTest` (built/asserted throughout).
- `GameDTO.java` / `GameMapper.java` ← `GameMapperTest.ToDTOTests` (read-side mapping)
  and `UpdateEntityFromDTOTests.shouldNotOverwriteEnvScopeOrTimestamps` (write-side
  non-mutation — the load-bearing mapper contract).
- `GameService.findByBrandProductEnv` ← `GameServiceTest.FindByBrandProductEnvTests`.
- `GameService.filter` (env scope + null-env `$or` fallback) ← `GameServiceTest.FilterTests`
  incl. new structural `$or` assertion.
- `GameService.save` (createdAt-once, updatedAt-every-save) ← `GameServiceTest.SaveTests`.
- `GameService.update` (routes through save → re-stamps updatedAt, preserves createdAt)
  ← `GameServiceTest.UpdateTests` (two new tests).
- `GameController` new routes + env-from-path on create ← `GameControllerTest`
  (`FindByBrandProductEnvTests`, `FilterTests`, `CreateTests`).
- `GameRepository.findByBrandCodeAndProductCodeAndEnvironmentId` ← exercised via the
  service delegation test (derived-query method, no custom impl to unit-test).

## Gaps

- **Real Mongo `$or` null-match semantics** are asserted structurally (the query
  carries a null-`environmentId` branch), not against a live database. Whether
  Mongo treats `{environmentId: null}` as matching both explicit-null and absent
  fields is Spring Data / Mongo behavior, not app logic; verifying it end-to-end is
  covered by Verification step 2/3 (staging curl) and is integration-only. No
  Testcontainers test added — consistent with the existing unit-level idiom for
  `GameService` (MongoTemplate mocked).
- **Migration script** `scripts/migrations/001_game_env_scope.js` is a mongosh
  script run by the Releaser; not unit-testable in the Java suite. Reviewed by
  inspection: idempotent, aborts on ambiguous `(brand,product)→env`, logs unmapped
  games, backfills `createdAt` only when absent and always re-stamps `updatedAt`.
  Left to Verification step 12 (Releaser post-script `countDocuments` checks).
- **Unrelated untracked file** `src/main/java/com/vingame/bot/T.java` exists in the
  working tree (not part of the Phase 1 diff, not committed on this branch). It
  compiles and does not affect the build; flagging so it is not accidentally
  committed. Out of QA scope (not in `main..HEAD`).

## Failures

None.

---

# QA — BOTGROUP_GAME_MANAGEMENT (Phase 2: BotGroup env-in-path filter)

**Verdict:** PASS
**Build:** `mvn clean install` → 1140 tests, 0 failures, 0 errors

## Scope reviewed

`git diff 16a84d6..HEAD` — Phase 2 only:
- `BotGroupFilter` drops `environmentId` (moves to the path, mandatory).
- `BotGroupService.filter(String environmentId, BotGroupFilter)` — env now always
  constrains the query (from the arg); empty body returns every group in that env.
- `BotGroupService.validateGameEnvironmentMatch` on the create path (both the
  registering and `skipRegistration` branches): mismatch → `BadRequestException`
  (400); guarded when the group has no `gameId` or the game's `environmentId` is
  null.
- `BotGroupController`: `POST /filter/` replaced by `POST /{envId}/filter`; the
  unscoped `GET /` (findAll) hard-removed (AD-6). `service.findAll()` stays for
  internal callers.

## Tests added / updated

Dev delivered a solid first pass (route move + empty-body + the four AD-7 cases +
`GET /` removal). QA added 3 tests closing the load-bearing gaps Dev's set left:

- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupServiceTest.java`
  - `GameEnvironmentValidationTests.shouldRejectMismatchOnSkipRegistrationPath` —
    AD-7 also fires on the `skipRegistration=true` existing-group migration branch
    (which bypasses the auth fan-out): mismatch → 400, `repository.save` and
    `clientRegistry.getClients` never reached. Dev only exercised the registering
    branch.
  - `GameEnvironmentValidationTests.shouldNotValidateGameEnvOnUpdate` — AD-7 is a
    create-only guard; `update()` merges + persists without re-checking game/env, so
    a pre-existing mismatch cannot block a PATCH (`gameService.findById` never
    called).
- `src/test/java/com/vingame/bot/domain/botgroup/controller/BotGroupControllerTest.java`
  - `FilterTests.oldUnscopedFilterRouteIsGone` — the old `POST /filter/` contract no
    longer maps (4xx) and never resolves to the new handler; complements the
    existing `GET /` removal test.

Pre-existing Dev tests kept and verified green: filter always carries the env-scope
criterion (name/gameId narrowing within env; empty body = env-scope-only query),
the AD-7 mismatch/match/null-env/no-gameId quartet (mismatch verified to reject
before fan-out and persist), the new `POST /{envId}/filter` route (by-gameId,
empty-body-all-in-env passing the path env, empty result), and the removed
unscoped `GET /`.

## Coverage of the diff

- `BotGroupFilter` (env field removed) ← compiled against by every filter test; no
  body env field remains to assert.
- `BotGroupService.filter(env, filter)` ← `BotGroupServiceTest.FilterTests`
  (env always present; name/gameId within scope; empty body = only `environmentId`
  key).
- `BotGroupService.validateGameEnvironmentMatch` ←
  `GameEnvironmentValidationTests` (mismatch→400 before fan-out/persist on both the
  registering and skip-registration branches; match/null-env/no-gameId allowed;
  not invoked on update).
- `BotGroupController` `POST /{envId}/filter` + removed `GET /` + removed
  `POST /filter/` ← `BotGroupControllerTest.FilterTests` / `GetAllRemovedTests`
  (env from path forwarded to the service; old routes 4xx, `findAll`/`filter`
  never invoked).

## Gaps

- **Real Mongo env-scope semantics** are asserted structurally (the captured
  `Query` carries `environmentId == <arg>`), not against a live database —
  consistent with the existing `MongoTemplate`-mocked unit idiom. End-to-end env
  scoping is covered by Verification step 4 (staging curl). No Testcontainers test
  added.
- **Exact 404-vs-405 status** for the removed `GET /` and old `POST /filter/`
  routes is asserted as `is4xxClientError` rather than a fixed code — the precise
  status is Spring dispatcher behavior (a sibling method mapping on the same path
  yields 405, otherwise 404) and not app logic. Verification step 5 pins the
  staging expectation (404/405, not 200).
- **Unrelated untracked file** `src/main/java/com/vingame/bot/T.java` remains in the
  working tree (not part of this diff, not committed on this branch). Flagged again
  so it is not accidentally committed. Out of QA scope.

## Failures

None.

---

# QA — BOTGROUP_GAME_MANAGEMENT (Phase 3: BotGroup statistics)

**Verdict:** PASS
**Build:** `mvn clean install` → 1151 tests, 0 failures, 0 errors (11 new)

## Scope reviewed

`git diff 930001f..HEAD` — Phase 3 only:
- `Bot` gains two accumulators: `AtomicLong cumulativeWinnings` (AD-8, mirrors
  `bot_winnings_total`) and `AtomicLong roundsObserved` (AD-9), both with getters.
- `BettingMiniGameBot.onEndGame`: `cumulativeWinnings.addAndGet(w)` under the same
  `w > 0` guard as the metric (but not gated on `metrics != null`), and
  `roundsObserved.incrementAndGet()` once per completed round.
- `SlotMachineBot.onSpinResult`: same pair — `cumulativeWinnings += gross` under
  `winnings > 0`, `roundsObserved++` once per completed spin.
- New `BotGroupStatsDTO` (5 nullable boxed fields).
- `BotGroupBehaviorService.computeStats(groupId)`: null runtime → all-null;
  `roundsSinceRestart` = max over bots; `activeTimeSeconds` from `startedAt`;
  averages over `isConnected()` bots only, null when zero active.
- Wired into `getHealth` (skeleton + full) and the controller `GET /{id}` +
  filter list items.

## Tests added / updated

Dev's diff added only the `getHealth`-path mock stubs (`getRoundsObserved` /
`getCumulativeWinnings` in the shared `mockBot` helper). QA added 11 tests directly
covering the load-bearing Phase-3 logic, which had no dedicated coverage:

- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceStatsTest.java` (4)
  — `computeStats` directly:
  - `noRuntimeAllNull` — non-running group → block non-null, every field N/A (AD-13).
  - `runningGroupComputesMaxRoundsAndActiveOnlyAverages` — `roundsSinceRestart` = MAX
    across ALL bots incl. a disconnected one; `averageBalance`/`averageWinning`
    computed over the 2 connected bots ONLY (the disconnected bot's huge values must
    not leak into the mean); `activeBots` = 2 (AD-9/AD-10).
  - `liveRuntimeAllReconnectingYieldsNullAverages` — live runtime, zero connected
    bots → averages `null` (never 0), rounds still = max (Implementation Note 5).
  - `runningGroupNoBots` — empty runtime → rounds 0, activeBots 0, averages null.
- `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotAccumulatorTest.java` (4)
  — the increment SITE in `onEndGame`:
  - winning round: `cumulativeWinnings == w` AND `verify(metrics).incBotWinnings(w)`
    (mirrors the metric value-for-value), `roundsObserved == 1`.
  - losing round (`w=0`): `cumulativeWinnings` stays 0, `roundsObserved == 1`
    (guard `w>0` on winnings; round still counts).
  - `accumulatesWithoutMetrics` — with `metrics` unset (null), `cumulativeWinnings`
    still accrues (AD-8: not gated on `metrics != null`).
  - multi-round: winners summed (100+400=500), `roundsObserved == 3` incl. the loss.
- `src/test/java/com/vingame/bot/domain/bot/core/SlotMachineBotAccumulatorTest.java` (3)
  — the increment SITE in `onSpinResult`:
  - winning spin: `cumulativeWinnings == 6000` (gross = Σ wls[].crd) AND
    `verify(metrics).incBotWinnings(6000)`, `roundsObserved == 1`.
  - losing spin: `cumulativeWinnings` stays 0, `roundsObserved == 1` (spins count).
  - two spins (win then loss): winnings summed once, `roundsObserved == 2`.

## Coverage of the diff

- `Bot.java` (new accumulators) ← exercised by both accumulator tests (real Bot
  subclasses, real fields) and the stats test (mocked getters).
- `BettingMiniGameBot.onEndGame` increments ← `BettingMiniGameBotAccumulatorTest`
  (value mirror + w>0 guard + metrics-independence + per-round count).
- `SlotMachineBot.onSpinResult` increments ← `SlotMachineBotAccumulatorTest`
  (gross-winnings mirror + winnings>0 guard + per-spin count).
- `BotGroupBehaviorService.computeStats` ← `BotGroupBehaviorServiceStatsTest`
  (all four branches: no-runtime, connected-only averaging, zero-active-null,
  no-bots).
- `BotGroupStatsDTO` ← asserted field-by-field via the stats test.
- Wiring into `getHealth` ← existing `BotGroupBehaviorServiceTest.GetHealthTests`
  still green with the Phase-3 mock stubs (the DTO now carries a non-null `stats`
  block; those tests don't assert on it but no longer NPE).

## Gaps

- **Controller-level embedding** of `stats` into `GET /{id}` and the filter list
  items (`dto.setStats(behaviorService.computeStats(...))`) is verified by
  inspection + the `computeStats` unit coverage, not a fresh MockMvc slice — the
  controller change is a one-line enrichment delegating to the fully-unit-covered
  service method. End-to-end presence is covered by Verification step 6 (staging
  `curl .../health | jq '.stats'`).
- **`activeTimeSeconds` exact value** is asserted only as non-null / `>= 0` (it is
  wall-clock `startedAt`→now); pinning an exact second count would be flaky. The
  derivation (present when running, null when not) is covered.
- **Slot "rounds = spins" semantics** (Implementation Note 3) are covered as a
  per-spin increment; there is no StartGame/EndGame round boundary for slots, so
  this is the intended behavior, not a gap.
- **Integer division** in the averages (`sum / count`) is intentional (long math,
  truncating) and asserted with values that divide evenly; no rounding-mode contract
  to test.

## Failures

None.

---

# QA — BOTGROUP_GAME_MANAGEMENT (Phase 4: BotGroup sorting)

**Verdict:** PASS
**Build:** `mvn clean install` → 1184 tests, 0 failures, 0 errors (13 new)

## Scope reviewed

`git diff d0d667a..HEAD` — Phase 4 only:
- `BotGroup` gains `createdAt` / `updatedAt` (`Instant`); `BotGroupService.save`
  stamps `createdAt` once and `updatedAt` on every save, and `update` now routes
  its persist through `save` so a PATCH re-stamps `updatedAt` (AD-14/AD-16).
- New `sort` package: `BotSortKey` (12-key catalog + `equalsIgnoreCase` resolve,
  null/blank→`CREATED_TIME`, unknown→`BadRequestException`/400), `SortDirection`
  (lenient resolve, default `DESC`), `BotGroupSortRow` (enriched row), and
  `BotGroupSorter` (AD-12 comparator: present values by direction, N/A pinned to
  the bottom regardless of direction, tie-break NAME asc then id asc).
- `BotGroupBehaviorService.filterSorted(envId, filter)` — load (via
  `BotGroupService.filter`) → enrich (Phase-3 stats + actualStatus + gameType
  resolved once per distinct gameId) → in-memory sort.
- `BotGroupFilter` gains `sortBy` / `sortDir`; the controller filter delegates to
  `filterSorted` and embeds the pre-computed stats.
- `scripts/migrations/002_botgroup_timestamps.js` (Releaser-run, not executed here).

## Tests added / updated

Dev delivered a strong first pass (`BotGroupSorterTest` per-key + resolution +
tie-break, `BotGroupBehaviorServiceFilterSortedTest` wiring, `BotGroupServiceTest`
timestamp stamping, `BotGroupControllerTest` filter/route/400). QA closed the
load-bearing gaps the task called out — several keys were only exercised in one
direction, and N/A-to-bottom was proven only for BALANCE and GAME_TYPE:

- `src/test/java/com/vingame/bot/domain/botgroup/sort/BotGroupSorterExhaustiveTest.java` (12 — new file)
  - One parameterized case per `BotSortKey` (all 12: STATUS, BOT_COUNT,
    CREATED_TIME, NAME, BET_AMOUNT, BALANCE, ACTIVE_BOTS, UPDATED_TIME, GAME_TYPE,
    AVG_WINNING, ACTIVE_TIME, MAX_PER_ROUND). Each case asserts the full asc AND
    desc order, and — for the runtime-only / resolved keys — that the N/A
    (null-extracted) row is the tail in BOTH directions. Present rows are named so
    that NAME order is the reverse of value order, proving the primary key (not the
    tie-break) drives the ordering; rows are fed shuffled so a no-op sort cannot pass.
- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceFilterSortedTest.java` (1)
  - `unknownSortKeyPropagates` — with real groups loaded, an unknown `sortBy`
    surfaces as `BadRequestException` through the actual `BotGroupSorter.sort` →
    `BotSortKey.resolve` path (the controller slice only mocks the throw; this pins
    the real end-to-end propagation the 400 depends on).

Pre-existing Dev tests kept and verified green: per-key ordering + resolution +
default + no-ENVIRONMENT-key + tie-break (`BotGroupSorterTest`); load→enrich→sort
wiring incl. gameType-once-per-distinct-id and missing-game→null
(`BotGroupBehaviorServiceFilterSortedTest`); createdAt-once / updatedAt-every-save /
update-re-stamps (`BotGroupServiceTest.TimestampTests`); controller filter delegates
to `filterSorted`, embeds stats, and 400-on-unknown-key (`BotGroupControllerTest`).

## Coverage of the diff

- `BotSortKey` (12 extractors + resolve) ← `BotGroupSorterTest.KeyResolution` +
  `BotGroupSorterExhaustiveTest` (every key, both directions, N/A-to-bottom).
- `SortDirection.resolve` ← `BotGroupSorterTest.DirectionResolution` + exercised in
  every exhaustive case.
- `BotGroupSorter.comparator` (AD-12: present-by-dir, N/A-last, NAME/id tie-break)
  ← `BotGroupSorterTest` (BALANCE/GAME_TYPE/STATUS/tie-break) +
  `BotGroupSorterExhaustiveTest` (exhaustive both-directions + N/A tail).
- `BotGroupBehaviorService.filterSorted` (+ `resolveGameTypes`) ←
  `BotGroupBehaviorServiceFilterSortedTest` (load/enrich/sort, distinct-id lookup,
  missing-game→N/A, unknown-key→400 propagation).
- `BotGroup.createdAt/updatedAt` + `BotGroupService.save`/`update` stamping ←
  `BotGroupServiceTest.TimestampTests` (new-group stamps both; update preserves
  createdAt, moves updatedAt forward) and read by CREATED_TIME/UPDATED_TIME sort
  cases.
- `BotGroupFilter.sortBy/sortDir` + controller delegation ← `BotGroupControllerTest.FilterTests`.

## Gaps

- **Real Mongo timestamp persistence** (that `createdAt`/`updatedAt` round-trip
  through Spring Data) is asserted structurally on the entity, not against a live
  database — consistent with the existing `MongoTemplate`-mocked unit idiom. Sort
  behavior over those fields is covered in-memory. End-to-end sort ordering is
  covered by Verification step 7 (staging curl `sortBy=name` asc + unknown-key→400).
- **`activeTimeSeconds`-derived N/A for STATUS/runtime keys** is exercised via the
  Phase-3 stats DTO shape (null fields on a stopped group), not by standing up a
  live runtime — the enrichment is the Phase-3-covered `computeStats`; Phase 4 only
  reads its output.
- **Migration script** `scripts/migrations/002_botgroup_timestamps.js` is a mongosh
  script run by the Releaser; not unit-testable in the Java suite. Reviewed by
  inspection alongside the Phase-1 sibling. Left to Releaser post-script checks.
- **Unrelated untracked file** `src/main/java/com/vingame/bot/T.java` remains in the
  working tree (not part of this diff, not committed on this branch). Left unstaged
  as instructed. Out of QA scope.

## Failures

None.

---

# QA — BOTGROUP_GAME_MANAGEMENT (Phase 5: Game sorting)

**Verdict:** PASS
**Build:** `mvn clean install` → 1200 tests, 0 failures, 0 errors

## Scope reviewed

`git diff 379eeb7..HEAD` — Phase 5 only:
- `GameSortKey` enum (CREATED_TIME, BOT_GROUP_COUNT, BOT_COUNT, GAME_TYPE, NAME,
  ACTIVE_GROUP_COUNT, ACTIVE_BOT_COUNT — no BRAND/PRODUCT); `equalsIgnoreCase`
  `resolve` with null/blank → CREATED_TIME default and unknown → 400; ACTIVE_*
  keys gated to N/A when the game is inactive.
- `GameSortRow` record (game + botGroupCount/botCount/activeGroupCount/activeBotCount,
  `active()` = activeGroupCount > 0).
- `GameSorter.sort`/`comparator` (AD-11/AD-12), reusing `SortDirection` + the
  extracted shared `SortComparators.compareNaLast`.
- `SortComparators` — the null-last/direction-aware compare extracted out of
  `BotGroupSorter` so Phase 4 and Phase 5 share one N/A-to-bottom rule.
- `GameFilter` gains `sortBy`/`sortDir`.
- `BotGroupBehaviorService.filterGamesSorted` + private `enrichGame`
  (load via `gameService.filter` → per-game aggregate over
  `botGroupService.findByGameId` → in-memory sort); `BotGroupService.findByGameId`;
  `GameController.filter` delegates to the behavior service.

## Tests added / updated

Dev delivered a strong first pass (`GameSorterTest`, `BotGroupBehaviorServiceGameFilterSortedTest`).
QA extended two files to close the load-bearing gaps the task called out:

- `src/test/java/com/vingame/bot/domain/game/sort/GameSorterTest.java`
  - `ValueKeys.countOrdering` — extended to assert BOTH directions for BOT_GROUP_COUNT
    and BOT_COUNT (Dev had asc-only / desc-only respectively), so every count key is
    pinned asc AND desc.
  - `ValueKeys.createdTimeDesc` (new) — explicit CREATED_TIME desc ordering (Dev only
    had asc + the implicit default), completing asc/desc for the 7th key.
- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceGameFilterSortedTest.java`
  - `activeAggregatesCountRunningOnly` (new) — registers real ACTIVE runtimes for two
    of a game's three referencing groups (with 4 and 2 non-completed bot futures) and
    leaves the third + a second game's group not running. Asserts
    `botGroupCount`=3 and `botCount`=23 (Σ configured over **all** three referencing
    groups, running or not), while `activeGroupCount`=2 and `activeBotCount`=6 count
    **only** the running groups/bots. Also asserts the active game sorts above the
    idle game (N/A → bottom) under ACTIVE_BOT_COUNT desc. This is the only test that
    exercises the runtime side of `enrichGame` — Dev's cases register no runtimes, so
    active-* was always 0 there.

## Coverage of the diff

- `GameSortKey` (7 extractors + resolve) ← `GameSorterTest.KeyResolution`
  (equalsIgnoreCase / whitespace-trim / null-blank default / unknown→400 /
  no BRAND-PRODUCT) + `ValueKeys` + `ActiveKeys` (every key, both directions).
- `GameSorter.comparator` (AD-12: present-by-dir, N/A-last, NAME/id tie-break)
  ← `GameSorterTest.ActiveKeys` (ACTIVE_* N/A-to-bottom both dirs), `TieBreak`
  (equal-primary → NAME asc → id; N/A block ordered by NAME asc).
- `GameSortRow.active()` ← `GameSorterTest` (inactive rows → N/A) +
  `BotGroupBehaviorServiceGameFilterSortedTest.activeAggregatesCountRunningOnly`
  (active()=true for a running-referenced game, false for an idle one).
- `SortComparators.compareNaLast` (extracted) ← exercised transitively by both the
  Game (Phase 5) and BotGroup (Phase 4) sorter suites; `BotGroupSorterTest` +
  `BotGroupSorterExhaustiveTest` remain green, confirming the refactor is behavior-
  preserving for the Phase-4 sort.
- `BotGroupBehaviorService.filterGamesSorted` + `enrichGame` ←
  `BotGroupBehaviorServiceGameFilterSortedTest` (load/enrich/sort wiring;
  BOT_COUNT sums configured over referencing groups; ACTIVE_* count running only;
  unknown-key→400 through the real path; findByGameId called once per game).
- `BotGroupService.findByGameId` ← same service test (mocked; delegates to repo).

## Gaps

- **Controller HTTP slice for the sorted filter** (that `GameController.filter`
  delegates to `behaviorService.filterGamesSorted` and maps `row.game()` to DTO) is
  covered structurally by the existing `GameControllerTest` update in the diff; the
  service wiring is unit-covered directly. End-to-end ordering is Verification step 8
  (staging curl `sortBy=name` desc + game sort-keys).
- **Slot vs betting game-type** in aggregates is immaterial to sorting — the enrich
  path sums configured `botCount` and counts running groups/bots regardless of type;
  not separately parameterized.
- **Live Mongo `findByGameId` round-trip** is mocked, consistent with the repository-
  mocked unit idiom used across the suite.
- **Unrelated untracked file** `src/main/java/com/vingame/bot/T.java` remains in the
  working tree — not part of this diff, left unstaged as instructed. Out of QA scope.

## Failures

None.

---

# QA — BOTGROUP_GAME_MANAGEMENT (Phase 6: sort-key lookup endpoints)

**Verdict:** PASS
**Build:** `mvn clean install` → 1204 tests, 0 failures, 0 errors (2 new)

## Scope reviewed

`git diff ea589d5..HEAD` — Phase 6 only:
- `GET /api/v1/bot-group/sort-keys` → `ResponseEntity<List<String>>` off
  `Arrays.stream(BotSortKey.values()).map(Enum::name).toList()`.
- `GET /api/v1/game/sort-keys` → same off `GameSortKey.values()`.
Both are pure enum-catalog lookups (no service, no arg) so the frontend sort
dropdown cannot drift from the keys `BotSortKey.resolve` / `GameSortKey.resolve`
accept on the filter endpoints.

## Tests added / updated

Dev delivered WebMvcTest slices asserting 200 + list length == `values().length`
and `hasItem` per enum key (exact set membership). QA added one exact-ordered
assertion per endpoint — the tightest anti-drift guard — pinning the response to
the full enum list in declaration order (which is what `values()` streams):

- `src/test/java/com/vingame/bot/domain/botgroup/controller/BotGroupControllerTest.java`
  - `GetSortKeysTests.shouldReturnExactBotSortKeyListInOrder` — response body
    equals `Arrays.stream(BotSortKey.values()).map(Enum::name).toList()` via strict
    JSON array match (`content().json(..., true)`): no extra keys, no missing keys,
    same order.
- `src/test/java/com/vingame/bot/domain/game/controller/GameControllerTest.java`
  - `GetSortKeysTests.shouldReturnExactGameSortKeyListInOrder` — same strict-array
    contract off `GameSortKey.values()`.

Pre-existing Dev tests kept and verified green: `shouldReturnAllBotSortKeys` /
`shouldReturnAllGameSortKeys` (200 + length + `hasItem` per key). Together
length-equals + all-present already implies exact set equality; the QA additions
make the "exact, ordered, cannot drift" guarantee explicit and also catch an
ordering regression the set-membership tests would miss.

## Coverage of the diff

- `BotGroupController.getSortKeys` ← `BotGroupControllerTest.GetSortKeysTests`
  (200, full `BotSortKey` list, exact order).
- `GameController.getSortKeys` ← `GameControllerTest.GetSortKeysTests`
  (200, full `GameSortKey` list, exact order).
- Drift guard vs the filter contract: because both handler and test derive from the
  same `values()`, and the Phase 4/5 `resolve` tests assert `resolve` accepts every
  `values()` entry, the lookup list is transitively pinned to the accepted-key set.

## Gaps

- **`{key,label}` DTO shape** (the plan offered `List<String>` OR a
  `StrategyInfoDTO`-style DTO as alternatives) — Dev chose `List<String>`; the
  Verification steps (`jq 'index("BALANCE")'`, `index("BOT_GROUP_COUNT")`) assume a
  flat string array, matching the implementation. No label/i18n surface to test.
- **Live HTTP round-trip** is the WebMvcTest slice (MockMvc), not a running server —
  consistent with every other controller slice in the suite. End-to-end is
  Verification step 9 (staging curl on both `/sort-keys`).
- **Unrelated untracked file** `src/main/java/com/vingame/bot/T.java` remains in the
  working tree — not part of this diff, left unstaged as instructed. Out of QA scope.

## Failures

None.

---

# QA — BOTGROUP_GAME_MANAGEMENT (Phase 7: cascading deletes)

**Verdict:** PASS
**Build:** `mvn clean install` → 1215 tests, 0 failures, 0 errors (6 new)

## Scope reviewed

`git diff 966448e..HEAD` — Phase 7 only:
- `BotGroupBehaviorService.stopAndLogout(id)` — the teardown half of the cascade
  (AD-15): no-op when the group is not running; else flip out of ACTIVE → per-bot
  `bot.logout()` (tolerating one throwing) → existing `stopAllBots` teardown →
  `sessionAggregationService.evictGroup` → drop from `runningGroups`. Deliberately
  does NOT persist STOPPED (the caller deletes the doc next).
- `BotGroupService.delete` = `behaviorService.stopAndLogout(id)` then
  `repository.deleteById(id)`.
- `GameService.delete` cascades to `botGroupService.findByGameId(id)` groups
  (each `delete`d) then deletes the game.
- `EnvironmentService.delete` deletes each `gameService.findByEnvironmentId(id)`
  game (each cascading to its groups), then any remaining
  `botGroupService.findByEnvironmentId(id)` orphan group, then the env.
- New `GameRepository.findByEnvironmentId` / `GameService.findByEnvironmentId`.
- `@Lazy` on the three back-references (`BotGroupService.behaviorService`,
  `GameService.botGroupService`, `EnvironmentService.botGroupService`) to break the
  Spring construction cycles.

## Tests added / updated

Dev delivered a solid first pass (`stopAndLogout` happy/no-op/tolerate-throw;
per-level cascade `inOrder` tests that re-mock the downstream `service.delete`).
QA closed the load-bearing gaps the task called out — the documented "no DB
round-trip" behavior and the logout-before-teardown ordering were unasserted, and
no test exercised the true multi-service delegation chain end-to-end:

- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceTest.java` (2)
  - `StopAndLogoutTests.logsOutBeforeTeardown` — `inOrder` across the bot mocks and
    the session-agg mock: every `bot.logout()` fires BEFORE `evictGroup`, i.e. bots
    log out while the runtime is still intact, then teardown evicts. Guards the
    ordering the cascade depends on.
  - `StopAndLogoutTests.doesNotPersistStoppedStatus` — `verify(botGroupService,
    never()).save(...)`: unlike `stop()`, `stopAndLogout` must not write STOPPED back
    to Mongo (the caller deletes the doc next; a save would be a wasted round-trip on
    a doomed document — the exact behavior the javadoc claims).
- `src/test/java/com/vingame/bot/domain/botgroup/service/CascadeDeleteChainTest.java` (4 — new file)
  - Wires the **real** `EnvironmentService` + `GameService` + `BotGroupService`
    together (only the three repositories and the behavior service are mocks) so the
    actual production cascade is exercised through every hop — the existing per-level
    tests re-mock `service.delete` and never prove the chain end-to-end. The `@Lazy`
    runtime cycle is mirrored by giving `BotGroupService` a mock `GameService` (its
    delete path never touches `gameService`) and handing the real `BotGroupService`
    to the real `GameService`/`EnvironmentService`.
  - `fullChainDeletesEverythingInOrder` — env→game→group: `inOrder` proves
    `stopAndLogout("group-1")` → `botGroupRepo.deleteById("group-1")` →
    `gameRepo.deleteById("game-a")` → `envRepo.deleteById("env-1")`, strictly in that
    order (no parent removed while a child still references it — AD-15).
  - `orphanGroupSweptBeforeEnv` — a group referencing the env directly (null gameId /
    cross-env game, found only by the env-scoped sweep) is stopped-and-deleted before
    the env doc: no orphan left behind.
  - `idempotentWhenGroupsAlreadyStopped` — a no-op `stopAndLogout` (models an
    already-stopped group) still deletes the group and completes the game+env cascade.
  - `gameWithNoGroupsDeletesOnlyGame` — deleting a game with no referencing groups
    deletes only the game; no stray `stopAndLogout` / group deletion.

Pre-existing Dev tests kept and verified green: `stopAndLogout` happy path (both
bots logged out, evict, dropped from `runningGroups`, status flipped STOPPED),
no-op-when-not-running, tolerate-logout-throw; `BotGroupService.delete`
stop-then-deleteById `inOrder`; `GameService.delete` cascade-to-groups `inOrder` +
no-groups path; `EnvironmentService.delete` games-then-groups-then-env `inOrder` +
empty path.

## Coverage of the diff

- `BotGroupBehaviorService.stopAndLogout` ← `BotGroupBehaviorServiceTest.StopAndLogoutTests`
  (happy teardown, no-op, tolerate-throw, logout-before-evict ordering, no-STOPPED-save).
- `BotGroupService.delete` ← `BotGroupServiceTest.DeleteTests` (stop→delete `inOrder`)
  + `CascadeDeleteChainTest` (real delegation to the behavior-service mock + repo).
- `GameService.delete` (+ `findByEnvironmentId`) ← `GameServiceTest.DeleteTests`
  (cascade `inOrder`, no-groups) + `CascadeDeleteChainTest.gameWithNoGroupsDeletesOnlyGame`
  (real BotGroupService delegation).
- `EnvironmentService.delete` ← `EnvironmentServiceTest.DeleteTests`
  (games→groups→env `inOrder`, empty) + `CascadeDeleteChainTest` (full real chain,
  orphan sweep, already-stopped idempotency).
- `GameRepository.findByEnvironmentId` ← exercised via `GameService.findByEnvironmentId`
  delegation in the env cascade (derived-query method, no custom impl to unit-test).
- `@Lazy` cycle break ← `mvn clean install` boots the full context in the
  integration/controller slices without a `BeanCurrentlyInCreationException`; the
  manual wiring in `CascadeDeleteChainTest` also confirms no construction cycle in the
  delete path.

## Gaps

- **Real Spring `@Lazy` proxy resolution** is proven by the app-context tests booting
  green, not by a dedicated cycle-detection test — a fresh `@SpringBootTest` would add
  nothing beyond what the existing controller slices already establish.
- **Live Mongo cascade round-trip** (that `deleteById` / `findByGameId` /
  `findByEnvironmentId` actually remove/return docs) is mocked, consistent with the
  repository-mocked unit idiom across the suite. End-to-end deletion + no-orphan is
  covered by Verification step 11 (staging: create disposable env/game/group, DELETE
  the env, assert the group filter is empty/404 and the game is 404, grep the log for
  the group's stop line).
- **Concurrent periodic-logout race** (the ACTIVE-flip guarding a concurrent tick) is
  asserted at the state level (`actualStatus == STOPPED` after `stopAndLogout`), not by
  standing up a real concurrent scheduler — thread-timing tests would be flaky; the
  status gate is the deterministic invariant.
- **Unrelated untracked file** `src/main/java/com/vingame/bot/T.java` remains in the
  working tree — not part of this diff, left unstaged as instructed. Out of QA scope.

## Failures

None.
