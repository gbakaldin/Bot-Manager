# QA — DEAD_GROUP_RESTART

**Verdict:** PASS
**Build:** `mvn clean install` (full reactor, Java 21) → 1492 tests, 0 failures, 0 errors, 0 skipped

Branch `fix/dead-group-restart`, base `staging`. Reviewed `git diff staging..HEAD`:
production change is confined to
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java`
(reclaim guard in `start()`, extracted `teardownRuntimeMemory`, per-group `ReentrantLock`),
plus the test file. No `src/main` change outside that one file.

## Count reconciliation

Full reactor, per module (surefire aggregate):

| Module | Tests |
|---|---|
| bot-api | 98 |
| bot-strategies | 111 |
| bot-messages | 136 |
| bot-engine | 339 |
| bot-app | 808 |
| **Total** | **1492** |

- Pre-fix reactor baseline: **1485**.
- Dev added **6** tests to `bot-app` (`BotGroupBehaviorServiceRestartTest`, the
  `start_reclaim*` / `start_isNoOp*` / `start_concurrent*` family) → 1491.
- QA added **1** test (`stop_tearsDownRuntimeAndPersistsStopped`, see Gaps → stop()) → **1492**.
- Removed / disabled pre-existing tests: **0** (`git diff` shows `6+` and `0-` `@Test`
  in the diff; QA add is `+1`). No `@Disabled`/`@Ignore` introduced.

## Fail-on-base / pass-on-branch confirmation

I swapped the base (`staging`) copy of `BotGroupBehaviorService.java` under the new
tests and ran them, then restored the branch copy:

- **On base:** `start_reclaimsAndRebuildsWhenRuntimeIsDead` **FAILS**
  ("botFactory.createBot … zero interactions" — `start()` no-ops on the lingering DEAD
  runtime, which is exactly the bug). `start_concurrentStartsDoNotDoubleBuild` also FAILS
  (same zero-interaction no-op). `start_isNoOpWhenRuntimeIsActive` **PASSES** on base —
  proving the tests discriminate the fix, not just green-on-branch.
- **On branch:** all 17 tests in `BotGroupBehaviorServiceRestartTest` PASS;
  `BotGroupBehaviorServiceTest#shouldNoOpWhenAlreadyRunning` (the ACTIVE→no-op contract
  the plan says must stay green) PASSES in the full reactor run.

The primary regression genuinely proves the fix.

## Tests added / updated

- `bot-app/.../BotGroupBehaviorServiceRestartTest.java` — 6 Dev tests + 1 QA test covering
  the reclaim path and the `stop()` refactor.

## Coverage of the diff

Production `BotGroupBehaviorService` ← `BotGroupBehaviorServiceRestartTest`:

- **Reclaim-on-DEAD in `start()` (AD-1/AD-2)** ← `start_reclaimsAndRebuildsWhenRuntimeIsDead`
  (rebuild happens: 3 `createBot`, new ACTIVE runtime instance, `save(targetStatus=ACTIVE)`)
  + `start_isNoOpWhenRuntimeIsActive` (ACTIVE runtime → `findById`/`createBot` never called,
  same instance retained). The ACTIVE discriminator is asserted on both arms.
- **Dead-seconds credited exactly once (AD-4)** ← `start_reclaimCreditsGroupDeadSecondsExactlyOnce`
  — real `SimpleMeterRegistry`+`BotMetrics`, backdated `groupDeadSince`; asserts
  `group_dead_seconds_total > 0`, old runtime stamp cleared (so no second credit possible),
  rebuilt runtime `groupDeadSince == null`. Product-correct against double/zero credit.
- **Old monitor + logout scheduler shut down (AD-6 / thread-leak trap)** ←
  `start_reclaimShutsOldMonitorAndLogoutScheduler` — real `ScheduledExecutorService`s
  injected on the DEAD runtime; both `isShutdown()` after reclaim.
- **No registration / deposit on rebuild — account reuse (AD-8, product-critical)** ←
  `start_reclaimDoesNotRegisterOrDeposit` — only account-facing collaborator invoked is
  `botFactory.createBot` (re-auth) ×3; log capture asserts no line matching
  `registr`/`deposit`. Belt-and-suspenders and structurally sound (the service has no
  registration collaborator at all).
- **Concurrency guard / one build under a race (AD-5)** ← `start_concurrentStartsDoNotDoubleBuild`
  — two threads race `start()` on a DEAD runtime; exactly 3 `createBot` (not 6), final
  ACTIVE. Deterministic: on branch the lock serializes reclaim+build so the loser sees the
  fresh ACTIVE runtime and no-ops; on base the test fails (both no-op → 0 calls).
- **`stop()` behavior-preservation after the `teardownRuntimeMemory` extraction (AD-3)** ←
  `stop_tearsDownRuntimeAndPersistsStopped` (QA-added) — runtime removed from
  `runningGroups`, executor shut, `evictGroup` called, entity saved with
  `targetStatus=STOPPED` + `lastStoppedAt` stamped.

## Gaps

- **Zero-bot rebuild via the reclaim path re-marking DEAD** is not asserted *specifically*
  for the `start()`-reclaim entry. It is covered for the shared zero-bot guard by the
  pre-existing `restart_failsLoudlyWhenZeroBotsCreated` (same `startLocked` code the reclaim
  falls through into), so the behavior itself is exercised; only the reclaim→zero-bot
  composition is untested. Low risk (path-independent guard) — not worth a redundant test.
- **Reclaim-before-`findById` ordering** and the **`finally` build-failure cleanup not
  double-tearing-down the already-reclaimed runtime** (Implementation Notes) are reasoned
  in the plan but not unit-asserted. Integration-flavoured; low risk given the old runtime
  is removed from the map before the rebuild inserts a new one.
- **`stop()` gap closed by QA.** `stop()` was structurally refactored (body extracted to
  `teardownRuntimeMemory`, `findById`/`save` moved after teardown, method wrapped in the
  per-group lock) yet had **no** direct unit test — only indirect exercise via `restart()`
  and the reclaim tests' `finally`. I added `stop_tearsDownRuntimeAndPersistsStopped` to
  pin the observable `stop()` contract (map removal, executor shutdown, session evict,
  STOPPED + `lastStoppedAt` persist). Confirmed it passes on the branch.
- **Phase 2 staging end-to-end** (thread-count delta, Loki reclaim-log grep, live
  `group_dead_seconds_total`, two-click idempotency on a real fleet) is operator/integration
  scope — deferred to the Releaser per the plan's `## Verification`.

## Failures (if any)

None on the branch. The only failures observed were the deliberate fail-on-base run
(reclaim + concurrency tests) confirming the regression, described above.
