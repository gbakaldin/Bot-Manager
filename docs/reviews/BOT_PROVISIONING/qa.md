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

---

# QA — BOT_PROVISIONING (Phase 2: registration-time deposit)

Commits under test: `d818d86`, `f73ce2e`, `bbb5381`.

**Verdict:** PASS (one low-severity non-money defect, P2-1, reported below; test left `@Disabled`)
**Build:** `mvn clean install -o` (worktree, JDK 21) → 2858 tests, 0 failures, 0 errors, 1 skipped
(api 148, strategies 126, messages 308, engine 710, app 1566). Baseline before QA additions:
2830, all green.

## Tests added / updated (28)

- `bot-app/.../botgroup/service/DepositLedgerTest.java` (new, 12) — the **Mongo** `DepositLedger`.
  Every dev test runs against `InMemoryDepositLedger`, so the real class's conditional writes —
  the actual never-twice guard in production — were untested: deleting any condition failed
  nothing. Pins the exact filter/update documents: `markInFlight` filter
  `{_id, depositedCount: index-1, depositInFlight: null}` + upsert + returnNew + result check;
  `credit` is one write conditioned on the marker that sets the count *and* clears the marker,
  throws on no match; `clearInFlight` never touches the count; `resolve` conditioned on the
  marker, `$max` for credited, marker-only for not-credited, false when stale; `seedAtLeast` is
  `$max` upsert; `delete` by id.
- `bot-engine/.../client/ApiGatewayClientRegistrationDepositEdgeBlockTest.java` (new, 3) — real
  `SlidingWindowGatewayBudget` + `StubGateway` block page: post-marker
  `GatewayCircuitOpenException` → `REFUSED`; OBSERVE → `REFUSED` via 403; an open circuit at
  admission is rethrown with no marker and no send.
- `bot-engine/.../client/ApiGatewayClientRegistrationDepositAfterSendTest.java` (new, 3) — server
  reads the request and drops the connection → `UNKNOWN` (not `NOT_SENT`); a runtime failure
  after the call → `UNKNOWN`; submission is `DEFAULT` tier with the caller's registration
  `maxWait` and scope.
- `RegistrationWorkerDepositTest` (+5) — all four `registration_deposits_total` outcomes and
  `registration_deposit_amount_total` pre-registered at 0 under the group MDC, the incremented
  series is the pre-registered one; `NOT_SENT` x4 charged to the transport budget (group never
  FAILs at max-attempts 3); credited-but-unrecorded still counts the money and never re-sends;
  REFUSED/NOT_SENT never advance, UNKNOWN never clears; plus the `@Disabled` P2-1 test.
- `BotGroupServiceTest$InitialDepositTests` (+5) — PATCH negative / above-cap on a complete
  group → 400; unchanged `initialDeposit` during PENDING is not rejected; raise on a
  REGISTRATION_FAILED group seeds nothing; stale resolution (marker moved) → 400, no enqueue;
  delete forgets the ledger entry.

Validation already covered by Dev and confirmed by mutation: negative, > cap, cap/0 accepted,
`existingGroup` + deposit, PATCH while PENDING/FAILED → 400.

## Mutation check (25 mutations, each applied alone, targeted classes run, reverted)

All 25 **killed**. `*` = killed only by a QA-added test (survived Dev's suite).

| # | Mutation | Killed by |
|---|---|---|
| A* | `markInFlight` filter loses `depositedCount==index-1 && depositInFlight==null` | `DepositLedgerTest.markInFlightIsConditional` |
| U* | `markInFlight` ignores the returned document | `markInFlightRejectsAnUnexpectedResult` |
| B | marker written **after** the send (client) | Dev `http200IsCredited` (+ QA after-send / edge-block) |
| C1* | `credit` clears the marker without advancing the count | `creditAdvancesAndClearsInOneConditionalWrite` |
| C2 | worker calls `clearInFlight` instead of `credit` | Dev worker tests incl. model |
| V* | `credit` silent on no match | `creditWithoutMatchThrows` |
| D | group with a set marker is not stopped on selection | Dev `markerOnSelectionFailsWithoutSending`, model |
| H | UNKNOWN clears the marker | Dev `unknownFailsImmediately`, model |
| Q | credit-write failure clears marker and retries | Dev `creditWriteFailureStopsTheGroup` |
| E | retry without `depositOutcome` proceeds | Dev `retryWithoutAnswerIsRejected` |
| I* | `resolve` not conditioned on the marker index | `resolveCredited` / `resolveNotCredited` |
| F | raise on a complete group does not seed (funds existing accounts) | Dev `raiseOnCompleteGroupSeedsTheLedger` |
| F2* | seed uses `$set` instead of `$max` | `seedIsMonotonicUpsert` |
| G | 5xx classified REFUSED | Dev `http5xxIsUnknown` |
| R | interrupt after marker → NOT_SENT | Dev `interruptClassification` |
| S* | I/O failure after marker → NOT_SENT | `ioFailureAfterSendIsUnknown` |
| T* | generic failure after marker → NOT_SENT | `runtimeFailureAfterSendIsUnknown` |
| L* | Cloudflare edge block after marker → UNKNOWN | `enforceEdgeBlockIsRefused` |
| M* | `DepositNotSentException` loses its IOException cause (charged as refusal) | `notSentDoesNotSpendTheRefusalBudget` |
| K | deposit tier PRIORITIZED | Dev `http200IsCredited`, `defaultTierWithTheRegistrationWait` |
| J* | one outcome series not pre-registered | `depositSeriesArePreRegisteredAtZero` |
| N | `existingGroup` + deposit allowed | Dev `existingGroupWithDepositIsRejected` |
| O | PATCH `initialDeposit` during registration allowed | Dev `changeDuringRegistrationIsRejected` |
| P | negative allowed | Dev `negativeIsRejected`, QA `patchOutOfRangeIsRejected` |
| P2 | cap off-by-one (`>=`) | Dev `boundsAreAccepted` |

## Flakiness

`RegistrationWorkerDepositTest` run 6x consecutively: 45/45 (1 skipped) every time. The model
test's 25 seeds are fixed (`7919 * repetition`), so it is deterministic by construction; as an
exploration (not committed) it was run once with `@RepeatedTest(1000)` and `System.nanoTime()`
seeds — 1000/1000 passed. No sleeps-as-synchronisation in the added tests.

## Coverage of the diff

- `DepositLedger` ← `DepositLedgerTest` (filter/update shape of every write); semantics via
  `InMemoryDepositLedger` in the worker/service tests.
- `ApiGatewayClient.depositForRegistration` ← Dev `ApiGatewayClientRegistrationDepositTest` +
  QA edge-block and after-send tests: every AD-7 row, marker ordering, tier, wait.
- `RegistrationWorker` deposit stage ← `RegistrationWorkerDepositTest` (Dev 40 + QA 5).
- `BotGroupService` validation / AD-8 retry / AD-9 seed / delete ← `BotGroupServiceTest$InitialDepositTests`.
- `BotMetrics` deposit counters ← `depositSeriesArePreRegisteredAtZero`.
- Controller `depositOutcome` param ← Dev `BotGroupControllerTest`.

## Gaps

- **No real-Mongo test of `DepositLedger`.** The shape tests prove the documents; they do not
  prove Mongo's upsert-collision behaviour (a non-matching upsert on an existing `_id` raising
  E11000) or that the package-private `Entry` maps. No Testcontainers in the build and Docker on
  this box is unreliable; recommend one Testcontainers test, or verify on staging via plan
  Verification steps 3-5 (`depositInFlight:null`, `registration_deposits_total{outcome="unknown"} 0`).
- Mapper/DTO read-only behaviour of `depositedCount` / `depositInFlight` on input not separately
  asserted (Dev changed the mapper; no mutation applied there).

## Defects

- **P2-1 (low, no money at stake) — the named mark can skip unnamed accounts after an AD-9 seed.**
  `RegistrationWorker` picks `index = (deposit ? deposited : names ? named : registered) + 1`,
  which assumes `deposited <= named`. A group that completed while no display-name pool was
  loaded (`namedCount 0`) and is then raised with `initialDeposit > 0` is seeded to the old
  botCount, so the pass starts at the first new index and `named = index` jumps the named mark
  over accounts that were never named. Reproduced: registered 3, named 0, ledger 3, raise to 5 →
  names only `bot4, bot5`, `namedCount = 5`, group COMPLETE. Without a deposit the same raise
  names 1..5. Funding is correct (only 4, 5 sent). Impact: `namedCount` lies; nameless accounts
  are the RIK-room freeze shape. Test `RegistrationWorkerDepositTest.seededFundedMarkDoesNotSkipNaming`
  is committed `@Disabled` (verified red with the condition deactivated). Fix suggestion: start at
  `min(deposited, names ? named : registered) + 1` and skip the deposit stage for indices
  `<= deposited` (already the case via `deposited < index`).

## Failures

None.
