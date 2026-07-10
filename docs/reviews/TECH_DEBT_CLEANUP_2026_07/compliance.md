# Compliance — TECH_DEBT_CLEANUP_2026_07

Branch: `chore/tech-debt-cleanup-2026-07`
Plan reviewed: `docs/plans/TECH_DEBT_CLEANUP_2026_07.md` (at branch HEAD be7e11f)
Diff reviewed: `git diff main..chore/tech-debt-cleanup-2026-07` (commits 410cb99, d03b4c3, dc8d7fd, be7e11f)

## Verdict

PASS

## Phase-by-phase

### Phase 1 — EnvironmentMapper: map the three dropped fields (item 1 / AD-5)
Status: implemented
Notes: Commit 410cb99. All three methods now carry the three fields.
- `toDTO`: reads getters straight — `entity.isUseJwtAuth()` (primitive auto-boxes), `getPeriodicLogoutEnabled()`, `getPeriodicLogoutIntervalMinutes()`.
- `toEntity`: `useJwtAuth` = `Optional.ofNullable(dto.getUseJwtAuth()).orElse(false)` (boxed→primitive, entity default `false`); the two periodic-logout fields carried through boxed as-is with **no fabricated default** — exactly as AD-5 requires (null = "use global default").
- `updateEntityFromDTO`: `useJwtAuth` → `.orElse(entity.isUseJwtAuth())`; periodic-logout fields → `.orElse(entity.getPeriodicLogout...())` (untouched when absent).
- Tests: entity→DTO and DTO→entity full-field tests assert the three new fields; the `toEntity`-with-nulls test asserts `useJwtAuth=false` and both periodic-logout fields `null`; a new partial-update test (`shouldUpdateUseJwtAuthAndKeepPeriodicLogout`) flips `useJwtAuth` and confirms periodic-logout fields are preserved. AD-5 primitive/boxed handling and null carry-through fully honored.

### Phase 2 — Align `name` filter to contains (item 5 / AD-4)
Status: implemented
Notes: Commit d03b4c3. `EnvironmentService` and `BotGroupService` both changed from `"^" + Pattern.quote(name) + "$"` to `Pattern.quote(name)` with the `"i"` flag retained. `GameService` was **NOT touched** (it was already contains — confirmed still `Pattern.quote(...)` at line 83, and absent from the diff). Exactly the two-file scope AD-4 prescribes. Test assertions in `EnvironmentServiceTest` and `BotGroupServiceTest` flipped to the unanchored expectation with case-insensitive flag retained; comments updated from "anchored" to "contains". `GameServiceTest` untouched.

### Phase 3 — SessionHistoryService: generate UUID for new entities (item 6 / AD-6)
Status: implemented
Notes: Commit dc8d7fd. Added `if (session.getId() == null || session.getId().isEmpty()) session.setId(UUID.randomUUID().toString());` before `repository.save`, plus the `java.util.UUID` import — aligned exactly with the `GameService`/`BotGroupService` sibling idiom. Pinning test `shouldNotGenerateId` was **replaced** (not kept) by `shouldGenerateIdWhenNull`, `shouldGenerateIdWhenBlank`, and `shouldPreserveExistingId`, mirroring the sibling save-id tests. No write endpoint added; `save` not wired into any producer (out-of-scope items respected).

### Phase 4 — start() marks group DEAD on zero bots (item 2 / AD-2, AD-3)
Status: implemented
Notes: Commit be7e11f. After the bot-start loop, `if (bots.isEmpty() && group.getBotCount() > 0)` calls `runtime.markAsDead()` and persists `targetStatus=DEAD`, then `return`s **without throwing** — precisely AD-2.
- **DEAD transition reuses existing machinery, not a new flag:** `runtime.markAsDead()` is the exact method `monitorHealth`'s death path (`handleBotGroupDeath`, line 1414) calls, and the surrounding `setTargetStatus(DEAD)` + `botGroupService.save(group)` mirror that same handler. No parallel flag introduced.
- **`restart()` retains its throw:** `IllegalStateException` at line 831 is intact and unchanged — the intentional split between the two paths is preserved.
- **AD-3 (no new metric):** the change adds no counter; observability rides the existing `bot_creation_failures_total` plus the DEAD status. Confirmed no new metric in the diff.
- Test: `shouldCompleteWithZeroBotsWhenAllCreateBotsFail` renamed to `shouldMarkGroupDeadWhenAllCreateBotsFail`; asserts runtime still registered (containsKey), bot instances empty, saved `targetStatus == DEAD` (was `ACTIVE`), and `start` does not throw. The NOTE comment block updated to reflect DEAD-marking (not "remains tolerant") while keeping the note that `restart` throws.

### Phase 5 — Verify items 3 and 4 intact (ADs 1 & 7)
Status: implemented (verify-only, no production change — as designed)
Notes: Items 3 and 4 treated as already-shipped. Confirmed the branch did **NOT** re-implement them:
- `RestExceptionHandler` and everything under `common/exception/` — absent from the diff (untouched).
- Restart-lifecycle zone resolution (`Environment.resolveZoneName`, `BotFactory`) — absent from the diff (untouched).
- `BotGroupController`'s `runWithManualOverride` `catch (RuntimeException)` at ~:190 — `BotGroupController.java` is entirely absent from the diff (untouched). AD-7 honored.

## Build / test verification

`mvn test` over the five touched test classes (`EnvironmentMapperTest`, `EnvironmentServiceTest`, `BotGroupServiceTest`, `SessionHistoryServiceTest`, `BotGroupBehaviorServiceTest`) with the Java-21 `JAVA_HOME`: **140 tests run, 0 failures, 0 errors, BUILD SUCCESS.** The ERROR-level lines observed are the intentional failure-scenario logs from the zero-bot and no-environment test cases, not test failures.

## Drift

None. Every phase implements the plan exactly, including the asymmetric `start()`/`restart()` split, the reuse of `markAsDead()`, the no-fabricated-default null carry-through, the Game-untouched filter scope, and the verify-only treatment of items 3/4.

## Out-of-scope changes

None. The diff touches exactly the ten files the plan enumerates (5 production + 5 test). No production file outside plan scope was modified.

## Doc follow-up status (FOLLOWUPS.md)

**Pending — not done on this branch.** The plan's Open Items and Implementation Notes state the follow-up of marking `FOLLOWUPS.md` P1/P5/P6/P7 RESOLVED (and P3 already-resolved) is **doc-only and "can ride the final commit."** `FOLLOWUPS.md` is not in the branch diff, so P1, P5, P6, and P7 still read as open (P2/P4 already carry their own RESOLVED notes; P3 is already effectively resolved via `RestExceptionHandler` but not annotated as such in the P3 entry).

This is a discretionary doc-hygiene follow-up, explicitly optional per the plan, and does **not** affect the faithfulness of the code implementation — hence it does not gate the PASS verdict. Flagging it so the Releaser (or a trailing doc commit) can update `FOLLOWUPS.md` P1/P3/P5/P6/P7 to RESOLVED before the bundle is considered fully closed out.

## Amendments to the plan

None.
