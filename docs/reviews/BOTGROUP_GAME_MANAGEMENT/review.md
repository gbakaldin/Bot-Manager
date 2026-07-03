# Code Review — BOTGROUP_GAME_MANAGEMENT (Phase 1)

Branch: feat/botgroup-game-management
Reviewed diff: `git diff main..feat/botgroup-game-management`

Scope: Phase 1 only (Game env-scoping — model/DTO/mapper/filter/service, routes, and
`scripts/migrations/001_game_env_scope.js`). Plan compliance, test coverage, and deploy
mechanics are out of scope for this review.

## Verdict

CHANGES_REQUESTED

## Findings

### [bug] List route drops unmigrated (null-env) games; only `filter` carries the fallback
`src/main/java/com/vingame/bot/domain/game/service/GameService.java:44` (`findByBrandProductEnv`)
and `src/main/java/com/vingame/bot/domain/game/repository/GameRepository.java:15`.

The two env-scoped read paths use different env-matching semantics:

- `filter(...)` builds an explicit `orOperator(environmentId == envId, environmentId is null)`
  — the intended defensive fallback so a game with a null/absent `environmentId` stays
  visible during the deploy window (before the backfill script runs).
- `findByBrandProductEnv(...)` (backing `GET /api/v1/game/{brand}/{product}/{envId}`) uses the
  derived query `findByBrandCodeAndProductCodeAndEnvironmentId`, which compiles to a strict
  `environmentId == envId` match with **no** null-env fallback.

At runtime, in the deploy window every game still has `environmentId == null` (the script is
Releaser-run *after* the app is up). During that window the list route returns an **empty
array** for every scope while the filter route returns the same games correctly. This directly
contradicts the stated fallback intent (Game.java Javadoc: "Read-side has a defensive fallback
treating a null value as matching any env"; plan Implementation Note 2: unmigrated docs must
"stay visible"). It is an env-scoping inconsistency between two read paths that should behave
identically.

Fix shape: give the list path the same null-env OR fallback as `filter` — either drop the
derived-query method and hand-build the `Query` with the same `orOperator` block, or add a
custom `@Query` on the repository. Both read paths should share one env-criteria helper so they
cannot drift again.

Note also that verification step #2's `jq 'all(.[]; .environmentId==null or ...)'` will pass
*vacuously* on an empty array, so the smoke test as written will not catch this — it needs a
non-empty precondition.

## Notes

- **toEntity is correctly closed to the write surface (good).** `GameMapper.toEntity`
  (`GameMapper.java:70`) is a hand-written `default` method that does not copy
  `environmentId`/`createdAt`/`updatedAt` from the DTO, so a create body cannot inject them;
  the controller sets `environmentId` from the path and `GameService.save` owns the timestamps.
  `updateEntityFromDTO` likewise leaves all three untouched with an explanatory comment. The
  write-surface protection is airtight — worth calling out because it is easy to get wrong with
  auto-generated MapStruct (auto-mapping would have leaked all three).

- **Timestamp stamping is not bypassed on update (verified).** `GameService.update` now routes
  through `save(existing)` (`GameService.java:88`), so `updatedAt` is re-stamped on every
  mutation and `createdAt` is preserved (set-only-when-null). No other persistence path in this
  diff calls `repository.save` directly.

- **Null-env fallback query is semantically correct.** `Criteria.where("environmentId").is(null)`
  emits `{environmentId: null}`, which in MongoDB matches both explicit-null and missing fields,
  so the `orOperator` fallback covers both the "field absent" (pre-field docs) and "field
  present but null" cases. The inline comment is accurate.

- **Migration mapping key matches Spring Data's persisted form (verified).** `Environment` stores
  `brandCode`/`productCode` as `BrandCode`/`ProductCode` enums, which Spring Data MongoDB
  persists as their enum *name* strings ("G2", "P_097"). The script keys its map on
  `env.brandCode + "|" + env.productCode` and looks up games by the same raw fields, so the
  join is consistent. `Environment` does carry both fields
  (`environment/model/Environment.java:44-45`), so the map is populated (not empty).

- **Migration is idempotent and fails loud on ambiguity.** `environmentId` is recomputed from the
  map every run, `createdAt` is set only when absent, `updatedAt` is always re-stamped, and a
  `(brand,product)` combo resolving to more than one env aborts with a thrown error rather than
  picking arbitrarily. Unmapped games are logged (not silently skipped) and still get their
  timestamps. This is solid.

- **Minor (not blocking): no existence check on the `envId` path param.** The create route
  (`GameController.java:save`) accepts any `envId` string and persists it without verifying an
  `Environment` with that `_id` exists, so a typo produces a game pinned to a non-existent env
  that no scoped read will ever return. Brand/product get enum-parse validation for free; env
  does not. Consider a light existence check if this becomes a support burden — acceptable to
  defer.
