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
