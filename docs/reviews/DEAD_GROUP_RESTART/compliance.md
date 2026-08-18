# Compliance — DEAD_GROUP_RESTART

Branch: `fix/dead-group-restart`
Plan reviewed: `docs/plans/DEAD_GROUP_RESTART.md` (at branch tip)
Diff reviewed: `git diff staging..fix/dead-group-restart` (base staging @ `8c10186`)
Commits: `aa09538` (fix), `f5cae2a` (test)

## Verdict

COMPLIANT (PASS)

The diff faithfully implements Option A. All four traps the plan committed to are
resolved in code (not merely asserted in tests), and all secondary requirements
(a)–(d) hold. Diff scope is exactly the two files the plan named
(`BotGroupBehaviorService.java` + `BotGroupBehaviorServiceRestartTest.java`),
113/281 lines — no out-of-scope production changes. Targeted suite green:
`Tests run: 76, Failures: 0, Errors: 0` including the 16-test RestartTest.

## Phase-by-phase

### Phase 1 — Reclaim-on-DEAD in `start()` + per-group lock + tests
Status: implemented

- **Step 1a — teardown helper (AD-3):** `teardownRuntimeMemory(String id,
  BotGroupRuntime runtime)` added exactly as specified — group MDC around
  `runtime.stopAllBots(botMetrics)`, then `sessionAggregationService.evictGroup(id)`,
  then `runningGroups.remove(id)`. `stop()` refactored to call it and then persist
  `targetStatus=STOPPED` + `lastStoppedAt` + final log. Behavior-preserving for
  `stop()`; the exception-propagation comment is preserved verbatim.
- **Step 1b — reclaim guard (AD-1, AD-2):** `runningGroups.get(id)`; `ACTIVE` ⇒
  `WARN "already running"` + return (old no-op preserved byte-for-byte);
  non-ACTIVE (DEAD) ⇒ `INFO "...non-viable (DEAD) runtime — reclaiming before
  restart"` + `teardownRuntimeMemory(...)` + fall through. Placed before
  `findById(id)`, matching the plan's ordering note.
- **Step 1c — per-group lock (AD-5):** `ConcurrentHashMap<String, ReentrantLock>
  groupLocks` added; `start()` and `stop()` each acquire
  `computeIfAbsent(id, ...)`, hold across the whole body, release in `finally`.
  `restart()` takes no lock of its own. `import java.util.concurrent.locks.ReentrantLock`
  added.
- **Step 1d — tests:** all 5 planned tests present, plus a 6th
  (`start_concurrentStartsDoNotDoubleBuild`) explicitly exercising the AD-5 lock —
  a superset, not a gap. Includes the private `runningGroups(svc)` reflection
  accessor the plan called for.

### Phase 2 — Staging deploy + verification
Status: out-of-scope for compliance (Releaser owns it). Verification steps in the
plan reference only surfaces that exist in this diff (`/start`, `/status`,
`/health`, the reclaim INFO line, `group_dead_seconds_total`,
`jvm.threads.live`) — the section is achievable against the current build.

## Four-trap verification (in code)

1. **Dead-seconds exact-once.** Reclaim routes through `teardownRuntimeMemory` →
   `stopAllBots(botMetrics)` → `creditGroupDeadSeconds` (credits `now -
   groupDeadSince`, then nulls the stamp — `BotGroupRuntime.java:308-322`). New
   runtime constructed fresh with `groupDeadSince=null`. No double-credit (second
   call no-ops on null stamp), no leaked-open window across reclaim→rebuild.
   Test: `start_reclaimCreditsGroupDeadSecondsExactlyOnce` (asserts counter >0 and
   old runtime stamp null). CONFIRMED.
2. **No thread/resource leak on reclaim.** `stopAllBots` shuts the bot executor
   (30 s grace → `shutdownNow`), the health monitor, and the logout scheduler
   (`BotGroupRuntime.java:259-305`), and calls `bot.cleanup()` per bot (owns the
   per-bot watchdog + graceful WS close). Reclaim RECOVERS the schedulers a DEAD
   group was leaking. Test: `start_reclaimShutsOldMonitorAndLogoutScheduler`
   (real schedulers, asserts both `isShutdown()`). CONFIRMED.
3. **Account reuse.** Reclaim falls through to the unchanged build path
   (`createBotsInParallel` → `createSingleBot` → `botFactory.createBot`, re-auth
   only). `BotGroupBehaviorService` has zero `registerUsers`/deposit calls; no
   DB-group recreation (reuses `findById(id)`). Test:
   `start_reclaimDoesNotRegisterOrDeposit` (verifies only `createBot` ×3 on the
   account-facing path and no registration/deposit log line). CONFIRMED — holds
   in code, the product owner's hard requirement is met.
4. **Contract preserved.** `handleBotGroupDeath`, `getHealth`, `getActualStatus`,
   and `monitorHealth` are untouched by the diff (they appear only as comment
   references and the guard's `getActualStatus()` read). The health monitor still
   leaves the DEAD runtime in the map; DEAD is surfaced from memory until an
   operator Start/Restart. Discriminator is `actualStatus == BotGroupStatus.ACTIVE`
   (AD-2). CONFIRMED.

## Secondary checks (a)–(d)

- **(a) Both DEAD-lingering sources reclaimed by the single discriminator.** The
  health-monitor death path (`handleBotGroupDeath` → `markAsDead`) and the
  zero-bot start path (`start`, ~`:411-442`) both leave `actualStatus=DEAD` in the
  map. The one guard `actualStatus != ACTIVE` reclaims both. CONFIRMED.
- **(b) Zero-bot re-death guard still fires on a genuinely-empty rebuild.** The
  `if (bots.isEmpty() && group.getBotCount() > 0)` block is intact and unchanged
  after the reclaim fall-through — a rebuild that yields 0/N bots re-marks DEAD,
  persists `targetStatus=DEAD`, and returns. Real failures are not hidden.
  CONFIRMED.
- **(c) `start()`→`startLocked()` extraction is sound.** This is a divergence from
  the plan's Step-1c "wrap the body inline" suggestion, but behaviorally
  equivalent: `start()` acquires the lock and delegates to `startLocked(id)` in a
  `try`/`finally unlock()`; the entire former body (including the reclaim guard and
  the `started`/`failure` finally-cleanup) lives in `startLocked` under the held
  lock. No early-return escapes the lock; `stop()` inlines the same lock pattern.
  Preferable to inlining (keeps the large method readable) and does not alter
  lock scope. This is a "Dev used an equivalent structure" case, not a plan defect.
  CONFIRMED equivalent and sound.
- **(d) Nothing in the plan silently skipped.** AD-1..AD-8 all honored; AD-6 (health
  monitor left unchanged) and AD-7/AD-8 (no endpoint/registration/deposit/zero-bot
  changes) verified by the untouched diff scope. The `monitorHealth`
  `!isGroupDead()` guard (Implementation Note) is untouched. CONFIRMED.

## Drift

None requiring action. One pre-authorized/benign structural difference:
`start()`→`startLocked()` extraction instead of an inline lock wrap (see (c)).
Behaviorally equivalent, sound, and within the spirit of Step 1c. No send-back.

## Out-of-scope changes

None. Diff is confined to `BotGroupBehaviorService.java` and the one test file.

## Amendments to the plan

None. No technical oversight in the plan was found — every assumption the plan made
about `stopAllBots`, `creditGroupDeadSeconds`, `markAsDead`, the constructor's
`actualStatus=ACTIVE` default, and the two DEAD-lingering sources was verified
accurate against the current code.
