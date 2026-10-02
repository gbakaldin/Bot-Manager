# QA — BOT_PROVISIONING (Phase 1)

**Verdict:** PASS
**Build:** `mvn clean install` (worktree, JDK 21) → 2756 tests, 0 failures, 0 errors
(api 148, strategies 126, messages 308, engine 695, app 1479). Baseline before QA additions also green.

## Tests added / updated

- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceAttachTest.java`
  — 7 tests added (17 total), plus a release counter on the test's `RecordingBudget`:
  - `completionEventAttachesAsynchronously` — `onRegistrationCompleted` for an ACTIVE group really
    hands off to the lifecycle thread and builds 4-5 (Dev's tests only called `attach()` directly).
  - `reservationReleasedOnSuccess` / `reservationReleasedOnFailure` — the ESSENTIAL reservation is
    released on success and when the attach throws after reserving; a failed attach does not
    advance `builtUpTo`.
  - `runtimeDyingDuringAttachDiscardsNewBots` — runtime marked DEAD mid-build: new bots cleaned
    up, never wired (`setCoordinator` never called), `builtUpTo` unchanged, reservation released.
  - `stopDuringAttachUnwinds` — `/stop` while the attach is parked in the factory: stop cancels,
    queues on the group lock, attach unwinds, new bots cleaned up and never wired, runtime torn
    down, STOPPED persisted.
  - `deleteDuringAttachUnwinds` — same via `stopAndLogout` (delete's lifecycle half): same unwind,
    and no `save` at all (no re-insert of the deleted document).
  - `deletedGroupDoesNotThrowIntoTheWorker` — group gone between completion and event:
    `onRegistrationCompleted` swallows `ResourceNotFoundException` (it runs on the worker thread).

Stop/delete tests are latch-and-lock driven (wait on `ReentrantLock.hasQueuedThreads`), no sleeps
as synchronisation; the class passed 5/5 repeated runs.

## Mutation check (each mutation applied alone, targeted test classes run, then reverted)

| # | Mutation | Killed by |
|---|---|---|
| M1 | range start `builtUpTo` (off by one) | attachBuildsExactlyTheDelta + 11 others |
| M2 | range start `1` | same 12 |
| M3 | drop `setBuiltUpTo(toIndex)` | duplicateEventIsANoOp + 6 others |
| M4 | publish even when completion write unmatched | RegistrationWorkerTest.anUnmatchedCompletionPublishesNothing |
| M5 | drop username check on raise | raisePastTheCapIsRejected (+3 existing) |
| M6 | check on `>=` instead of `>` | noRaiseNoCheck (+5 existing) |
| M7 | drop attach's `isCancelled` check | stop/deleteDuringAttachUnwinds (QA) |
| M8 | drop `reservation.release()` | reservationReleased* , runtimeDying*, stop/delete* (QA) |
| M9 | drop "runtime no longer ACTIVE" check | runtimeDyingDuringAttachDiscardsNewBots (QA) |
| M10 | drop catch-up after start | eventDuringAnInFlightStartIsCaughtUp |
| M11 | `MAX_ATTACH_PASSES = 1` | raiseDuringAnAttachIsCaughtUp |
| M12 | narrow attachIfRunning's catch | deletedGroupDoesNotThrowIntoTheWorker (QA) |
| M13 | attachIfRunning never submits | completionEventAttachesAsynchronously (QA) |

M7, M8, M9, M12, M13 survived Dev's suite; all 13 are now killed.

## Coverage of the diff

- `BotGroupService.update` raise pre-flight ← `BotGroupServiceTest$UpdateUsernameLengthValidationTests` (Dev)
- `RegistrationWorker.recordCompletion` event ← `RegistrationWorkerTest` (Dev, 2)
- `BotGroupRuntime.builtUpTo` ← `runtimeStartsBuiltUpToBotCount` + every attach test
- `BotGroupBehaviorService.attachIfRunning/attach/attachRemainderLocked/attachLocked/attachStrategySlice`,
  `startLocked` catch-up, `createBotsInParallel(from,to)` ← `BotGroupBehaviorServiceAttachTest`

## Gaps / observations

- **Observation (not a test-red defect; design question for Dev/Architect):** `attachLocked`
  advances `builtUpTo` to `toIndex` even when **zero** bots came up (e.g. every bot refused by an
  open gateway circuit — refused inside the JVM, no request sent — or auth down). The attach logs
  INFO `attached 0/N bots` and those accounts silently wait for the next restart; nothing WARNs.
  The start's analogue (0 bots) marks the group DEAD, which is visible. Javadoc says "attempted,
  not up" is intended per bot; for an all-refused attach a WARN (or not advancing on a circuit
  refusal) seems worth considering. The `circuitRefusal` reference is collected and ignored.
- The listener-failure branch in `RegistrationWorker.recordCompletion` (publisher throws) is
  untested: it is indistinguishable from outside because `processGroup`'s own catch also
  swallows it.
- A `reserve()` that throws on a cancelled scope (real budget) would surface as an ERROR
  "Asynchronous attach ... failed" for a /stop that worked; not exercised (test budget never
  throws). Low impact, edge timing only.
- End-to-end grow-while-running (plan Verification step 8) is staging-only.

## Failures

None.
