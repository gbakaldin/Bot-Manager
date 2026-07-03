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
