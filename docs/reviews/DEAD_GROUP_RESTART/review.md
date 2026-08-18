# Code Review — DEAD_GROUP_RESTART

Branch: `fix/dead-group-restart`
Base: `staging` @ `8c10186`
Reviewed diff: `git diff 8c10186..fix/dead-group-restart`

Production files in diff:
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java`
(test file `BotGroupBehaviorServiceRestartTest.java` is QA scope, not reviewed here).

## Verdict

CONCERNS

No deadlock, no thread leak, no metric mis-credit. One legitimate concurrency
visibility finding that partially undermines the feature's own reliability
guarantee under a race, plus minor smells. Non-blocking, but the visibility
finding is worth a one-line follow-up before this is trusted at loadtest scale.

## Findings

### [bug] Reclaim discriminator `actualStatus` is read cross-thread but is not `volatile`
`bot-app/src/main/java/com/vingame/bot/infrastructure/runtime/BotGroupRuntime.java:65`
(consumed at `BotGroupBehaviorService.java:266` — `existing.getActualStatus()` in `startLocked`)

The new reclaim guard keys its entire decision (no-op vs full teardown+rebuild)
on `existing.getActualStatus() == BotGroupStatus.ACTIVE`. `actualStatus` is a
plain non-`volatile` field. It is **written on the health-monitor scheduler
thread** inside `markAsDead()` (`BotGroupRuntime.java:214`) and now **read on the
admin request thread** in the reclaim guard. The per-group `ReentrantLock` does
**not** close this gap: `handleBotGroupDeath`/`monitorHealth` never acquire
`groupLocks`, so `lock.lock()` in `start()` establishes a happens-before only
with prior holders of that lock — never with the monitor's `markAsDead` write.
`runningGroups.get(id)` doesn't help either: the CHM publish happened at runtime
*construction*, before `markAsDead` mutated the field, so the CHM read does not
synchronize-with the death write.

Runtime effect: an admin Start can observe a stale `ACTIVE` for a group the
monitor already marked `DEAD`, take the `"already running"` no-op branch, and
**silently fail to reclaim** — i.e. the exact single-click-restart bug this
branch fixes, resurfacing intermittently. (It can never read a torn/garbage
value — enum-ref writes are atomic — so the only failure mode is staleness →
missed reclaim, not corruption.)

The inconsistency is the tell: the sibling field `groupDeadSince` was
**deliberately** made `volatile` with the comment "*markAsDead() runs on the
health-monitor thread and stopAllBots() runs on the caller thread*"
(`BotGroupRuntime.java:91-95`). The exact same thread pair now reads
`actualStatus` for a correctness decision, but that field was left plain.

Fix shape: mark `actualStatus volatile` (mirrors `groupDeadSince`; matches the
already-documented threading model). Practically the multi-second gap between
death and the operator click plus the lock-acquire barrier make real-world
staleness very unlikely, which is why this is CONCERNS not FAIL — but the field
is now load-bearing for the fix and should carry the same visibility guarantee
its sibling already does.

### [smell] `groupLocks` entries are never removed on group delete
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:154`

`groupLocks.computeIfAbsent(id, ...)` inserts a `ReentrantLock` per id ever
passed to `start()`/`stop()` and nothing ever removes it — including on group
**delete**. Bounded not by *live* groups but by *distinct group ids seen over
the process lifetime*. For an admin-triggered, tens-of-groups deployment this is
negligible (the plan and the field comment both acknowledge it), so this is
advisory only. If the fleet abstraction ever churns group ids, revisit (e.g.
remove the lock entry inside the delete path, or on a successful `stop()` when
the runtime is gone). Note: `stop()` now also `computeIfAbsent`s a lock even to
no-op on a never-started id, so a bare Stop on a stranger id now also leaks one
entry — same negligible magnitude.

## Notes

- **Behavior preservation of `stop()` — verified byte-for-behavior.**
  `teardownRuntimeMemory` (`:801-819`) executes exactly the old inline sequence:
  `BotMdc.setGroupContext` → `stopAllBots(botMetrics)` (MDC cleared in `finally`)
  → `sessionAggregationService.evictGroup(id)` → `runningGroups.remove(id)`.
  `stop()` still does `findById` → `setTargetStatus(STOPPED)` →
  `setLastStoppedAt` → `save` → final "stopped successfully" log, in that order,
  after the teardown. The null-runtime warn+return is preserved (now inside the
  locked block). No drift.

- **Dead-seconds accounting across reclaim→rebuild — traced, correct.** Reclaim
  routes through `teardownRuntimeMemory` → `stopAllBots(botMetrics)` →
  `creditGroupDeadSeconds`, which credits `now - groupDeadSince` once and nulls
  the stamp (`BotGroupRuntime.java:315-317`). The rebuilt runtime is a fresh
  object with `groupDeadSince == null`, so no window leaks open and no second
  credit is possible (a subsequent `stopAllBots` finds a null stamp → no-op). If
  the rebuild trips the zero-bot DEAD guard (`:411-442`), that path stamps a
  fresh window and credits ~0s via its own `stopAllBots`. Exactly-once holds on
  every branch.

- **No deadlock / no lock-ordering hazard.** Only `start()` and `stop()` acquire
  `groupLocks`; a thread holds at most one group's lock at a time and never
  nests. `restart()` correctly takes **no** lock of its own — it calls `stop()`
  then `start()` sequentially, each acquiring/releasing independently. The
  stop→start gap in `restart()` is benign: a concurrent Start landing in the gap
  either rebuilds (restart's `start()` then no-ops on the fresh ACTIVE runtime)
  or the group is already up — no double-build, no leaked runtime. `ReentrantLock`
  reentrancy is moot since no path re-enters.

- **Health-monitor vs reclaim race is benign (worth recording why).** The one
  scenario that would corrupt state — the old monitor's `handleBotGroupDeath`
  writing `targetStatus=DEAD` to Mongo *after* a reclaim rebuild wrote ACTIVE —
  cannot occur, because `handleBotGroupDeath` only fires from `monitorHealth`
  under `!runtime.isGroupDead()` (`:1500`). Reclaim only runs on an
  already-DEAD runtime, so that runtime's monitor is already past the transition
  and its remaining ticks are inert (they hit the guard and only emit a DEBUG
  health line on the detached old runtime). `shutdownNow` in `stopAllBots`
  doesn't need to preempt an in-flight tick for correctness here.

- **Pre-existing, out-of-scope race (not introduced by this diff).** `stop()`
  now holds the lock, but `handleBotGroupDeath` still does not, so the lock gives
  no mutual exclusion against the monitor. A group transitioning ACTIVE→DEAD at
  the same instant an operator Stops it can still have the monitor's
  `targetStatus=DEAD` DB write land after `stop()`'s `targetStatus=STOPPED`.
  This predates the branch and is unrelated to the reclaim; flagging only so it
  isn't mistaken for a regression. A real fix would require the monitor to
  participate in `groupLocks` (or CAS the status), which the plan explicitly
  scopes out (AD-6).

- **No accidental register/deposit/zero-bot change.** The reclaim falls through
  to the unchanged build path (`createBotsInParallel` → `createSingleBot` →
  `botFactory.createBot`, re-auth only). No registration or deposit call exists
  in `BotGroupBehaviorService`; the zero-bot DEAD guard (`:411-442`) is
  untouched and still re-marks DEAD on a genuine 0/N rebuild. Confirmed no
  behavior change to those semantics.

- **Reclaim ordering vs `findById` — acceptable edge.** Reclaim runs before
  `findById(id)` (`:266` vs the load at `:284`). If `findById` throws transiently
  (e.g. Mongo blip) on a group that still exists, the DEAD runtime has already
  been torn down and removed, so `getStatus`/`getHealth` briefly report the
  STOPPED skeleton instead of DEAD until a retry. Very narrow and strictly better
  than leaking the DEAD runtime's threads; noting for completeness, not a defect.

- **`Throwable` catch at `:459` is intentional and justified** (captures the
  cause for the auto-start finally-log, rethrows unchanged) — not a
  swallow-and-continue. Fine.
