# TECH_DEBT_CLEANUP_2026_07

## Goal

Land a bundle of six correctness / tech-debt fixes on branch
`chore/tech-debt-cleanup-2026-07`. The bundle is a mix of real production bugs
(a mapper silently dropping three fields; a `start()` that reports success with
zero bots) and API/UX consistency work (aligned filter semantics; consistent
UUID generation). Two of the six items originally in scope —
`RESTART_LIFECYCLE_FIX` (item 3) and the RFC-7807 `@RestControllerAdvice`
(item 4) — **have already largely landed on `main`** ahead of this branch;
this plan verifies what remains and folds only the residual work in, to avoid
re-doing or conflicting with shipped code. Each phase is independently testable
and committable, sequenced trivial-first so the structural verification (item 4)
sits isolated.

## Findings — Current State

Investigated on `main` (branch point for `chore/tech-debt-cleanup-2026-07`).
The codebase is substantially ahead of `docs/plans/FOLLOWUPS.md`, which is
stale (last updated 2026-06-02). Concrete state per item:

**Item 1 — EnvironmentMapper drops three fields.** Confirmed still broken.
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/environment/mapper/EnvironmentMapper.java`
is hand-written `default` methods (not MapStruct-generated). All three methods
(`toDTO` :17, `toEntity` :48, `updateEntityFromDTO` :79) omit `useJwtAuth`,
`periodicLogoutEnabled`, `periodicLogoutIntervalMinutes`. Field types confirmed:
entity `Environment.java:69` `boolean useJwtAuth` (primitive), `:72` `Boolean
periodicLogoutEnabled`, `:73` `Integer periodicLogoutIntervalMinutes`; DTO
`EnvironmentDTO.java:50` `Boolean useJwtAuth` (boxed), `:53`/`:54` same boxed
types. So `useJwtAuth` is the primitive/boxed-mismatch case — must be handled
exactly like `customZone`/`binaryFrame` (`Optional.ofNullable(dto.getX()).orElse(...)`).

**Item 2 — `start()` reports success with zero bots.** Confirmed still open for
the **direct `start()`** path.
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java`:
`createBotsInParallel` (:421) catches per-bot failures, logs at ERROR, and
increments `bot_creation_failures_total` (:475) — but returns the (possibly
empty) list. `start()` then registers the runtime ACTIVE, persists
`targetStatus=ACTIVE`, and logs `"started successfully with {} bots"` (:362)
even when the count is 0. `monitorHealth` early-returns on empty (:1369).
**Note:** `restart()` (:787) already throws `IllegalStateException` on
zero-bots-with-positive-botCount (:800-810) — that half landed with
RESTART_LIFECYCLE_FIX. The direct `start()` path does not, by deliberate design
(see the NOTE comment at `BotGroupBehaviorServiceTest.java:277-284`).

**Item 3 — Restart lifecycle bug.** **Already landed.** `Environment.resolveZoneName(Game)`
exists (`Environment.java:96`) with `DEFAULT_MINI_ZONE_NAME`/`DEFAULT_CARD_ZONE_NAME`
constants (:30). All three read sites consume it: `BotFactory.java:111`,
`BotGroupBehaviorService.java:632`, `EnvironmentClientRegistry.java:139`
(comment confirms the dead `setZoneName` line was removed — option (a) from that
plan). `BotMetrics.incBotCreationFailure(reason)` exists (`BotMetrics.java:161`)
and is wired from `createBotsInParallel` (:475) via `classifyCreationFailure`
(:496). `restart()` throws loudly on zero bots (:800). The full plan is at
`/Users/gleb/IdeaProjects/Bot/docs/plans/RESTART_LIFECYCLE_FIX.md`. **This bundle
does not re-derive or re-implement it** — it only verifies via smoke that the
shipped fix is intact (see Phase 5 verification).

**Item 4 — Bare 500s / `@RestControllerAdvice`.** **Already landed.**
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java`
is a full `@RestControllerAdvice extends ResponseEntityExceptionHandler` with the
exact mapping this item requires: `ResourceNotFoundException`→404 (bodyless,
:71), `IllegalArgumentException`/`BadRequestException`→400 (:80/:88),
everything else→500-with-sanitised-body (:204). It emits an `ErrorResponse
{type,msg}` envelope (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/dto/ErrorResponse.java`)
— note this is a bespoke `{type,msg}` envelope, **not** Spring's `ProblemDetail`
type, but it satisfies the requirement (structured body + logged cause). The
per-endpoint `catch (Exception)` blocks are **gone**: `GameController` and
`EnvironmentController` have zero catch blocks; `BotGroupController` has one
`catch (RuntimeException)` at :190 that is **not** an error-swallower — it is the
`runWithManualOverride` activation-mode rollback (:179-195), unrelated to this
item and must be left untouched. So item 4's structural work is done; only a
**verification pass** remains to confirm no regressions and that the six
`isBadRequest()`/404 assertions still hold.

**Item 5 — Inconsistent `name` filter semantics.** Confirmed. `GameService.java:83`
is unanchored `Pattern.quote(name)` (contains); `EnvironmentService.java:69` and
`BotGroupService.java:100` are anchored `"^" + Pattern.quote(name) + "$"` (exact).
Standardising on **contains** means changing Environment + BotGroup only; Game is
already correct. Test state: `GameServiceTest.java:234` already pins contains
(`Pattern.quote("taixiu")`); `EnvironmentServiceTest.java:188` pins anchored
(`"^" + Pattern.quote("staging env") + "$"`); `BotGroupServiceTest.java:193`
pins anchored (`"^" + Pattern.quote("test group") + "$"`). Two test assertions
flip; Game's stays.

**Item 6 — `SessionHistoryService.save()` doesn't generate UUIDs.** Confirmed.
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/session/service/SessionHistoryService.java:55`
is a bare `return repository.save(session)`. Siblings generate UUIDs:
`GameService.java:116-117`, `BotGroupService.java:138/192` (both `id == null ||
id.isEmpty()` → `UUID.randomUUID().toString()`). **Caller investigation:**
`SessionHistoryService.save` has **zero production callers** — the only injector
is `SessionHistoryController`, which exposes GET/DELETE only, no write endpoint
(`SessionHistoryController.java` has `findById`/`findBySessionId`/`findAll`/
`findJackpotSessions`/`delete`, no POST/PATCH that reaches `save`). No code reads
back a generated id. Aligning with siblings is therefore safe and carries no
read-back-contract risk. The pinning test is
`SessionHistoryServiceTest.java:188` `shouldNotGenerateId`.

## Per-aspect readiness / mapping

| Item | Aspect | State | Notes |
|---|---|---|---|
| 1 | EnvironmentMapper 3 fields | ready | Add 3 mappings × 3 methods; `useJwtAuth` = boxed/primitive case. Extend `EnvironmentMapperTest` to full field count. |
| 2 | `start()` zero-bot success | ready (decision below) | Direct `start()` still tolerant; `restart()` already strict. Remedy chosen: mark DEAD (AD-2). Update pinning test at `BotGroupBehaviorServiceTest.java:287`. |
| 3 | Restart lifecycle | **done** | Landed with `RESTART_LIFECYCLE_FIX.md`. No new work; verify via smoke only. |
| 4 | `@RestControllerAdvice` / bare 500s | **done** | `RestExceptionHandler` present, per-endpoint catches removed. Verify tests only; do not touch the `runWithManualOverride` catch. |
| 5 | `name` filter semantics | ready | Align Environment + BotGroup to contains; Game already contains. Flip 2 test assertions. |
| 6 | SessionHistory UUID | ready (decision below) | Zero callers, no read-back risk. Align with siblings (AD-6). Replace pinning test. |

## Architecture Decisions

1. **Items 3 and 4 are treated as already-shipped; this bundle does not
   re-implement them.** Phase 5 verifies they are intact via the standing test
   suite and the staging smoke test. Rationale: re-deriving landed code risks
   conflicts and wasted review. `RESTART_LIFECYCLE_FIX.md` remains the canonical
   record for item 3.

2. **Item 2 remedy: mark the group DEAD when `start()` produces zero bots and
   `botCount > 0`; do not throw from direct `start()`.** After the bot-start
   loop, if `bots.isEmpty() && group.getBotCount() > 0`, set the runtime dead
   (`runtime.markGroupDead()` — the same transition `monitorHealth` uses at
   `BotGroupBehaviorService.java:1380`) and persist `targetStatus=DEAD`, then
   return normally without throwing. **Justification:** (a) Direct `start()` is
   called from `onStartup` (:207, catches and logs) and from the controller
   `/start`. Throwing from `start()` would turn the auto-start path's per-group
   isolation into noise and would make `/start` 500 with no partial-state signal;
   DEAD is the existing, operator-visible terminal state that `getHealth`/
   `getStatus` already surface and that alerting keys on. (b) It is symmetric
   with `monitorHealth`, which already transitions a running group to DEAD on the
   dead-bot threshold — a group that started with zero live bots is the extreme
   case of that same condition, so reusing `markGroupDead()` keeps one code path
   for "this group is not viable." (c) `restart()` keeps its stricter
   `IllegalStateException` (already shipped) — that path begins from a healthy
   group and a hard failure there is genuinely exceptional and worth a 500; the
   two paths intentionally differ, and the existing NOTE comment at
   `BotGroupBehaviorServiceTest.java:277` already documents that split.
   *(Rejected alternatives: propagating an exception from `start()` — breaks
   auto-start isolation and gives no queryable state; a grace-period DEAD in
   `monitorHealth` — leaves a window where the DB lies as "ACTIVE, healthy" and
   requires new timer state. Immediate DEAD is the least-surprising, uses only
   existing machinery.)*

3. **`bot_creation_failures_total` already fires per failed bot; item 2 adds no
   new metric.** The zero-bot DEAD transition is observable through the existing
   metric plus the group's DEAD status. No new counter.

4. **Item 5: standardise on contains (unanchored, case-insensitive,
   `Pattern.quote`).** Rule everywhere:
   `Criteria.where("name").regex(Pattern.quote(name), "i")`. Rationale: matches
   what a user types into a filter box; `GameService` is already this shape, so
   we converge on the existing majority-of-intent behavior rather than
   introducing a new one.

5. **Item 1: match the existing null-handling idiom exactly.** `toDTO` reads the
   getters straight (primitive `boolean useJwtAuth` boxes on read). `toEntity`
   and `updateEntityFromDTO` use `Optional.ofNullable(dto.getX()).orElse(...)` —
   for `useJwtAuth` the orElse target is `false` (entity default) in `toEntity`
   and `entity.isUseJwtAuth()` in `updateEntityFromDTO`, mirroring
   `customZone`/`binaryFrame`. `periodicLogoutEnabled`/`periodicLogoutIntervalMinutes`
   are boxed on both sides — carry null through (in `updateEntityFromDTO`,
   `.orElse(entity.getPeriodicLogout...())`). No default fabricated for the two
   periodic-logout fields; null is a valid "use global default" per the
   consumer at `startPeriodicLogoutScheduler`.

6. **Item 6: align `SessionHistoryService.save` with siblings — generate a UUID
   when id is null/empty.** `if (session.getId() == null ||
   session.getId().isEmpty()) session.setId(UUID.randomUUID().toString());`
   before `repository.save`. **Justification:** zero production callers and no
   read-back contract (Findings item 6), so no behavior depends on Mongo's
   `@Id` auto-generation; matching `GameService`/`BotGroupService` removes a
   latent surprise for the first writer that gets added. The pinning test
   `shouldNotGenerateId` is replaced, not kept.

7. **`BotGroupController`'s `catch (RuntimeException)` at :190 is out of scope
   and stays.** It is the `runWithManualOverride` activation-mode rollback, not
   an error-swallower. Item 4 does not touch it.

## Plan

Ordered trivial/low-risk first. Each phase is one Dev session and one commit.
Build for every phase (see Verification for the exact invocation) with
`JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home`.

### Phase 1 — EnvironmentMapper: map the three dropped fields (item 1)

Files:
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/environment/mapper/EnvironmentMapper.java`
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/environment/mapper/EnvironmentMapperTest.java`

Steps:
1. `toDTO` (:22-41): add `.useJwtAuth(entity.isUseJwtAuth())`,
   `.periodicLogoutEnabled(entity.getPeriodicLogoutEnabled())`,
   `.periodicLogoutIntervalMinutes(entity.getPeriodicLogoutIntervalMinutes())`.
2. `toEntity` (:53-72): add
   `.useJwtAuth(Optional.ofNullable(dto.getUseJwtAuth()).orElse(false))`,
   `.periodicLogoutEnabled(dto.getPeriodicLogoutEnabled())`,
   `.periodicLogoutIntervalMinutes(dto.getPeriodicLogoutIntervalMinutes())`
   (boxed fields carried through as-is; no fabricated default per AD-5).
3. `updateEntityFromDTO` (:84-100): add
   `entity.setUseJwtAuth(Optional.ofNullable(dto.getUseJwtAuth()).orElse(entity.isUseJwtAuth()));`
   plus the two `setPeriodicLogout...(Optional.ofNullable(dto.getX()).orElse(entity.getX()))`.
4. `EnvironmentMapperTest`: in the "map all fields entity→DTO" (:27) and
   "map all fields DTO→entity" (:84) tests, set the three new fields on the
   input and assert them on the output. In the partial-update test (:154) add a
   case that flips `useJwtAuth` and asserts the periodic-logout fields are left
   untouched when absent. Update the field-count assertion (doc says 17 → should
   become 20).

### Phase 2 — Align `name` filter semantics to contains (item 5)

Files:
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/environment/service/EnvironmentService.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java`
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/environment/service/EnvironmentServiceTest.java`
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupServiceTest.java`

Steps:
1. `EnvironmentService.java:69`: change to
   `Criteria.where("name").regex(Pattern.quote(filter.getName()), "i")`.
2. `BotGroupService.java:100`: same change.
3. `GameService` — no change (already contains).
4. `EnvironmentServiceTest.java:188`: change the expected pattern from
   `"^" + Pattern.quote("staging env") + "$"` to `Pattern.quote("staging env")`;
   keep the case-insensitive flag assertion.
5. `BotGroupServiceTest.java:193`: change `"^" + Pattern.quote("test group") + "$"`
   to `Pattern.quote("test group")`.
6. Optionally update `@DisplayName` on those two tests from "anchored"/"exact"
   to "contains" for clarity (GameServiceTest already says "contains").

### Phase 3 — SessionHistoryService: generate UUID for new entities (item 6)

Files:
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/session/service/SessionHistoryService.java`
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/session/service/SessionHistoryServiceTest.java`

Steps:
1. `save` (:55): before delegating, `if (session.getId() == null ||
   session.getId().isEmpty()) session.setId(UUID.randomUUID().toString());`
   Add the `java.util.UUID` import.
2. Replace `shouldNotGenerateId` (:188) with `shouldGenerateIdWhenNull` and add
   `shouldGenerateIdWhenBlank` (empty-string id) and
   `shouldPreserveExistingId` (non-empty id passes through untouched) — mirroring
   the sibling `GameServiceTest`/`BotGroupServiceTest` save-id tests.

### Phase 4 — `start()` marks group DEAD on zero bots (item 2)

Files:
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java`
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceTest.java`

Steps:
1. In `start()` after the bot-start loop and before / around the persist at
   :356-360: if `bots.isEmpty() && group.getBotCount() > 0`, call
   `runtime.markGroupDead()` (the transition used at :1380 — confirm the exact
   method name on `BotGroupRuntime` during implementation; use whatever
   `monitorHealth` calls to flip a group DEAD) and set
   `group.setTargetStatus(BotGroupStatus.DEAD)` instead of `ACTIVE` for this
   case, then persist. Log at ERROR:
   `"Group {} started 0/{} bots — marking DEAD"`. Do **not** throw (AD-2).
   Leave the health-monitor and periodic-logout scheduler startup as-is (they
   early-return on empty and are harmless).
2. Update `BotGroupBehaviorServiceTest.StartCreateBotFailureTests.shouldCompleteWithZeroBotsWhenAllCreateBotsFail`
   (:287): rename to `shouldMarkGroupDeadWhenAllCreateBotsFail`; keep the
   arrange block; change assertions to: runtime still registered (containsKey),
   runtime bot instances empty, saved `targetStatus == DEAD` (was `ACTIVE`),
   and `start` does **not** throw. Update the NOTE comment block at :277-284 to
   reflect that direct `start` now marks DEAD (not "remains tolerant"); keep the
   note that `restart` throws.
3. Confirm no other test asserts `ACTIVE` for a zero-bot start (grep the test
   file for `BotGroupStatus.ACTIVE` in the failure-tests nest before editing).

### Phase 5 — Verify items 3 and 4 are intact + full-suite green

No production change. This phase is a verification-only commit-boundary (may be
folded into the final commit of Phase 4 if the suite is green).

Steps:
1. Run the full suite (invocation below). Confirm the six `isBadRequest()`/404
   controller assertions referenced by `FOLLOWUPS.md` P2 still pass
   (`BotGroupControllerTest`, `EnvironmentControllerTest`) and the
   `RestExceptionHandler` tests (if present) pass — these guard item 4.
2. Confirm `BotGroupBehaviorServiceRestartTest` (item 3's regression net) passes.
3. If any of the above fail, stop and report — it means item 3 or 4 regressed on
   `main` since they landed, which is out of scope for this bundle and needs a
   separate decision.

## Implementation Notes / Concerns

- **Item 1 primitive/boxed asymmetry.** `useJwtAuth` is `boolean` on the entity
  but `Boolean` on the DTO. In `toDTO` the getter is `isUseJwtAuth()` (returns
  primitive, auto-boxes). In `toEntity`/`updateEntityFromDTO` the DTO getter is
  `getUseJwtAuth()` (boxed, nullable) — must go through `Optional.ofNullable`.
  Copying the `customZone`/`binaryFrame` lines and renaming is the safest path.
- **Item 2 exact DEAD transition.** Read `BotGroupRuntime` for the method
  `monitorHealth` calls at `BotGroupBehaviorService.java:1380` to flip a group
  DEAD (likely `markGroupDead()` / `setGroupDead(true)`), and reuse it verbatim
  so the health-block/`getHealth` view is consistent. Do not invent a new flag.
- **Item 2 vs. `restart()`.** Do not add a throw to `start()`. `restart()`
  already throws (shipped); the two paths intentionally differ per AD-2. The
  existing NOTE at `BotGroupBehaviorServiceTest.java:277` documents the split —
  update it, don't delete it.
- **Item 5: only two files change in production; Game stays.** Do not "fix"
  `GameService` — it is already the target shape. Touching it would flip
  `GameServiceTest.java:234` needlessly.
- **Item 6 has no callers today.** The change is forward-looking. Do not add a
  write endpoint or wire `save` into any aggregation path as part of this item —
  that is out of scope; the fix is only the UUID guard so the first future
  writer inherits sibling behavior.
- **Item 4 is verification-only.** Do not add a new advice or reintroduce
  `ProblemDetail` — `RestExceptionHandler` + `ErrorResponse {type,msg}` already
  satisfy the requirement and the frontend branches on `type`. Introducing
  Spring `ProblemDetail` now would be a gratuitous API-shape change.
- **`FOLLOWUPS.md` is stale.** After this bundle lands, its P1/P5/P6/P7 entries
  should be marked RESOLVED and P3 noted as already-resolved (via
  `RestExceptionHandler`). Doc-only; can ride the final commit.
- **Build JDK.** Every `mvn` invocation must carry the Java-21 `JAVA_HOME`; the
  default JDK is Java 8 and the build fails immediately without it.

## Open Items

- **Out of scope:** re-implementing item 3 (`RESTART_LIFECYCLE_FIX`, shipped) and
  item 4 (`RestExceptionHandler`, shipped) — verify only.
- **Out of scope:** wiring `SessionHistoryService.save` into any producer, or
  adding a write endpoint — item 6 is the UUID guard alone.
- **Out of scope:** migrating `ErrorResponse` to Spring `ProblemDetail`.
- **Deferred to Dev during Phase 4:** confirm the exact `BotGroupRuntime` DEAD
  transition method name and reuse it (do not add a parallel flag).
- **Doc follow-up:** mark `FOLLOWUPS.md` P1/P3/P5/P6/P7 RESOLVED.

## Verification

These fixes are almost entirely unit-testable; there is **no feature-specific
on-server behavior to exercise beyond the universal smoke test and the
already-shipped restart/advice paths.** The primary gate is `mvn test`.

### Local build + test (mandatory, every phase)

```
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
  mvn -q test
```
Expect: `BUILD SUCCESS`, zero test failures. Per phase, additionally:

- **Phase 1:** expect `EnvironmentMapperTest` green with the three new field
  assertions; expect the field-count assertion at 20 (was 17).
- **Phase 2:** expect `EnvironmentServiceTest` and `BotGroupServiceTest` name-
  filter tests green with the `Pattern.quote(name)` (unanchored) expectation;
  `GameServiceTest` unchanged and green.
- **Phase 3:** expect `SessionHistoryServiceTest` green with the new
  `shouldGenerateIdWhenNull` / `shouldGenerateIdWhenBlank` /
  `shouldPreserveExistingId`; `shouldNotGenerateId` gone.
- **Phase 4:** expect `BotGroupBehaviorServiceTest` green with
  `shouldMarkGroupDeadWhenAllCreateBotsFail` asserting saved `targetStatus ==
  DEAD` and no exception thrown.
- **Phase 5:** expect the full suite green, including the six
  `isBadRequest()`/404 controller assertions (item 4 guard) and
  `BotGroupBehaviorServiceRestartTest` (item 3 guard).

### On-server verification (staging / Bot-1, after deploy)

Beyond the universal smoke test, only two lightweight checks apply — both
exercise the behavior this bundle actually changes at the API surface. Substitute
`<bot-1>` with the staging host.

1. **Item 1 — round-trip the three previously-dropped fields.** Create an
   environment with all three set, then read it back:
   ```
   curl -sf -X POST https://<bot-1>/api/v1/environment/ \
     -H 'Content-Type: application/json' \
     -d '{"name":"td-cleanup-verify","useJwtAuth":true,"periodicLogoutEnabled":true,"periodicLogoutIntervalMinutes":45,"type":"...","brandCode":"...","productCode":"..."}'
   ```
   Then `GET` the created id and inspect:
   ```
   curl -sf https://<bot-1>/api/v1/environment/<id> | jq '{useJwtAuth,periodicLogoutEnabled,periodicLogoutIntervalMinutes}'
   ```
   Expect HTTP 200 and `{"useJwtAuth":true,"periodicLogoutEnabled":true,"periodicLogoutIntervalMinutes":45}`
   (pre-fix these would be `false`/`null`/`null`). Delete the test env afterward:
   `curl -sf -X DELETE https://<bot-1>/api/v1/environment/<id>` — expect HTTP 200.
   *(Fill the required `type`/`brandCode`/`productCode` from an existing env's
   shape; those are unrelated to this fix.)*

2. **Item 5 — partial-name filter now matches on environments/bot-groups.**
   With at least one environment whose name contains a known substring:
   ```
   curl -sf -X POST https://<bot-1>/api/v1/environment/filter/ \
     -H 'Content-Type: application/json' -d '{"name":"<substring>"}' | jq 'length'
   ```
   Expect HTTP 200 and a length `>= 1` where `<substring>` is a proper substring
   (not the full name) of an existing environment name. Pre-fix this returned 0
   (anchored/exact). Repeat against `POST /api/v1/bot-group/filter/` if a
   bot-group with a known partial name exists.

Items 2, 3, 4, 6 have **no additional on-server check beyond the universal smoke
test**: item 2 (DEAD-on-zero-bots) only manifests under a real auth-gateway
outage and is fully covered by unit tests; items 3 and 4 are already shipped and
covered by the standing suite plus the restart smoke steps in
`RESTART_LIFECYCLE_FIX.md` §Verification; item 6 has no write endpoint to
exercise. If the Releaser wants to confirm item 2 opportunistically, a
misconfigured environment (bad `apiGatewayUrl`) attached to a small group and
started should leave the group at `status=DEAD` via `GET
/api/v1/bot-group/<id>/status` — expect `"DEAD"`, and
`bot_creation_failures_total` non-zero on `GET /actuator/prometheus` — but this
is optional, not required.
