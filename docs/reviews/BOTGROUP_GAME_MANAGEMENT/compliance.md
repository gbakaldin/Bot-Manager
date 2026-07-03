# Compliance — BOTGROUP_GAME_MANAGEMENT (Phase 1 only)

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
