# DEAD_GROUP_RESTART

> Sibling to `docs/plans/RESTART_LIFECYCLE_FIX.md` (the zoneName fix, now landed — its
> Architecture Decisions 5 & 6 and the zero-bot DEAD path it introduced are live in the
> code and are referenced below). This plan is a **distinct** bug in the same lifecycle
> area: a DEAD group cannot be restarted by the bare `start()` entry point. Kept separate
> because the zoneName plan is closed; this reconciles with it rather than reopening it.

Dated section: **2026-08-05**.

All code paths cited live under the `bot-app/` module (the repo was split into modules
after RESTART_LIFECYCLE_FIX was written — that plan's `src/main/...` paths are now
`bot-app/src/main/...`).

---

## Goal

Make a DEAD bot group restartable with a single UI click, at any time, by fixing the
`BotGroupBehaviorService.start(id)` entry point so it **reclaims** a lingering non-viable
(DEAD) in-memory runtime and rebuilds the group from scratch — reusing the persisted DB
group and the existing bot accounts/credentials (re-authenticate, never re-register, never
re-deposit, never recreate the DB group). Today the bare `start()` path is a silent no-op
on a DEAD group because the DEAD runtime is intentionally left in `runningGroups`; only
`restart()` (which calls `stop()` first) recovers. This plan makes `start()`, `restart()`,
`onStartup`, and the activation reconciler all converge on correct DEAD-recovery, with
exact-once dead-seconds accounting, full thread/resource teardown on reclaim, and a
concurrency guard against double-build.

---

## Findings — Current State

### The bug is confirmed exactly as diagnosed

- `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:225-230`
  — `start(id)` bails early on `runningGroups.containsKey(id)` with `WARN "Bot group {}
  is already running"`, **regardless of the runtime's actualStatus**:
  ```java
  public void start(String id) {
      if (runningGroups.containsKey(id)) {
          log.warn("Bot group {} is already running", id);
          return;
      }
  ```

- `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/infrastructure/runtime/BotGroupRuntime.java:212-218`
  — `markAsDead()` sets `actualStatus=DEAD` and stamps `groupDeadSince`, but **does not
  remove the runtime from any map** (it has no reference to `runningGroups`).

- `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:1439-1452`
  — `handleBotGroupDeath()` (the health-monitor DEAD path) calls `runtime.markAsDead()`
  and persists `targetStatus=DEAD`, but **leaves the runtime in `runningGroups`**. The
  health-monitor scheduler itself is **not** shut down here; `monitorHealth`
  (`:1418-1434`) keeps ticking every 30 s and is inert only because of the
  `!runtime.isGroupDead()` guard at `:1431`.

- Net effect: after a group dies, `runningGroups` still contains a DEAD runtime → a later
  bare `start(id)` hits the `containsKey` guard and no-ops. **The UI Start button
  (`POST /{id}/start` → `runWithManualOverride(..., MANUAL_ON, () -> behaviorService.start(id))`,
  `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java:143-147`)
  is therefore a no-op on a live-DEAD group.**

- `restart()` (`:838-863`) works because it calls `stop()` (`:715-750`) first, and
  `stop()` removes the runtime from `runningGroups` (`:741`) after tearing it down; the
  subsequent `start()` then sees no runtime and proceeds. **The `restart` endpoint already
  recovers a DEAD group correctly** — the gap is strictly the bare `start()` entry point.

### Two ways a DEAD runtime lingers in `runningGroups` (both reclaimed by this fix)

1. Health-monitor death: `handleBotGroupDeath` → `markAsDead`, runtime stays (`:1439-1452`).
2. Zero-bot start (RESTART_LIFECYCLE_FIX zero-bot path): `start()` reaches
   `:368-399`, calls `runtime.markAsDead()` + `runtime.stopAllBots(botMetrics)` +
   persists `targetStatus=DEAD`, and the comment at `:378-379` states the runtime is
   **intentionally NOT removed** from `runningGroups` so `getHealth`/`getStatus`/`stop`
   still surface the DEAD group.

Both leave `actualStatus=DEAD` in the map. A healthy running group has
`actualStatus=ACTIVE` (set in the `BotGroupRuntime` constructor at
`BotGroupRuntime.java:125`). So `actualStatus == ACTIVE` is the exact discriminator
between "genuinely running, no-op" and "lingering DEAD, reclaim".

### Dead-seconds accounting (the trap)

- `markAsDead()` stamps `groupDeadSince` once (idempotent — `:215-217`).
- `stopAllBots(BotMetrics)` (`BotGroupRuntime.java:259-305`) calls
  `creditGroupDeadSeconds(metrics)` **first** (`:266`), which credits
  `group_dead_seconds_total` with `now - groupDeadSince` and **clears the stamp**
  (`:314-321`). A second call with a null stamp is a no-op → no double-credit.
- `stop()` credits via `stopAllBots(botMetrics)` under group MDC (`:728-734`), then
  `evictGroup` + `runningGroups.remove` (`:738-741`).
- **Conclusion:** any reclaim path that routes teardown through
  `runtime.stopAllBots(botMetrics)` (under group MDC) credits the open DEAD window
  **exactly once** and closes it. The rebuilt runtime starts with a fresh
  `groupDeadSince=null`, so no window leaks open.

### Thread/resource teardown (the second trap)

`stopAllBots(BotMetrics)` is the single teardown primitive and it already:
- credits + clears the DEAD window (`:266`),
- calls `bot.cleanup()` per bot — the graceful WS close that suppresses the
  `onDisconnect` retry (`:269-275`),
- shuts the group's bot executor with a 30 s grace then `shutdownNow` (`:278-292`),
- `shutdownNow`s the **health monitor** (`:295-297`) and the **periodic-logout
  scheduler** (`:300-302`).

So reclaiming a DEAD group through `stopAllBots` also **recovers the health-monitor +
logout-scheduler threads that were leaking while the group sat DEAD** (see Findings above)
— a strict improvement given this session's platform-thread-leak work. Per-bot watchdogs
are owned by each `Bot` and are torn down inside `bot.cleanup()`.

### Account/credential reuse (the third trap)

- `createSingleBot` (`:597-694`) builds `BotCredentials` from
  `group.getNamePrefix() + botIndex` and `group.getPassword()` (`:599-609`) — i.e. the
  **existing** account identity, derived from the persisted group. It sets
  `zoneName(environment.resolveZoneName(game))` (`:683`) and calls
  `botFactory.createBot(...)` (`:690`).
- `BotFactory.createBot` (`BotFactory.java:94-196`) → `bot....initialize()` (`:190`),
  whose flow is `authenticate() → get tokens → clientFactory.newClient(tokens)`
  (class Javadoc `:37-47`). **No registration, no deposit.**
- Registration (`registerUsers`) exists **only** in
  `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java:164`,
  inside `save(...)` (group create/update), gated by `skipRegistration` (`:131,154`).
  A `grep` for `register`/`deposit` across `BotGroupBehaviorService.java` returns only two
  incidental comment hits (`:437`, `:509`) — **zero calls**. Confirmed: the start path
  re-authenticates existing accounts and never registers or provisions/deposits.

### targetStatus transition

On successful build, `start()` sets `group.setTargetStatus(ACTIVE)` and saves (`:408-411`).
So a successful reclaim+rebuild takes `targetStatus` DEAD → ACTIVE and the new runtime is
`actualStatus=ACTIVE`. The zero-bot DEAD guard (`:368-399`) still fires if the rebuild
genuinely yields 0 live bots, re-marking DEAD — the fix does not hide a real failure.

### Concurrency (the fourth trap)

`start()` is already racy today: `containsKey` (`:227`) then `runningGroups.put`
(`:280`) is a check-then-act with no lock. Two concurrent starts on a not-running group
can both build; the second `put` overwrites the first runtime in the map, **leaking the
first runtime's executor + monitor + logout threads**. The reclaim path widens this window
(it adds a teardown before the put). This must be closed with a per-group guard.

### Test harness available

- `/Users/gleb/IdeaProjects/Bot/bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceRestartTest.java`
  — `@ExtendWith(MockitoExtension)`, mocks `BotGroupService/EnvironmentService/GameService/
  BotFactory/BotMetrics/SessionAggregationService`, `@InjectMocks` the service, sets
  `@Value` fields by reflection (`:89-97`), has `stubBot(...)` and an in-memory
  `CapturingAppender` (`:515-531`). Ideal home for the reclaim tests.
- `/Users/gleb/IdeaProjects/Bot/bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceTest.java:253-272`
  — `shouldNoOpWhenAlreadyRunning()` constructs `new BotGroupRuntime("g-1", 0, "env-1")`
  (default `actualStatus=ACTIVE`) and asserts `findById`/`createBot` are never called.
  **This test pins the ACTIVE→no-op contract and must continue to pass** — the fix must
  only change behavior for `actualStatus != ACTIVE`.

---

## Per-aspect readiness / mapping

| Aspect | State | Notes |
|---|---|---|
| Bug location + repro | ready | `start()` guard `:227`; DEAD runtime lingers `:1439-1452` / `:368-399`. Confirmed. |
| Reclaim-on-DEAD in `start()` | ready | Change the guard: ACTIVE → no-op; non-ACTIVE (DEAD) → teardown + fall through. |
| Teardown primitive | ready | Reuse `runtime.stopAllBots(botMetrics)` + `evictGroup` + `remove` (identical to `stop()` body). |
| Dead-seconds exact-once | ready | Routed through `stopAllBots`→`creditGroupDeadSeconds` (credits once, clears stamp). |
| Thread teardown | ready | `stopAllBots` shuts executor + health monitor + logout scheduler; `bot.cleanup()` per bot. |
| Account reuse (no register/deposit) | ready | Start path has zero `registerUsers`/deposit calls; re-auth only. |
| targetStatus DEAD→ACTIVE | ready | `start()` sets ACTIVE on success (`:408-411`); zero-bot guard re-marks DEAD on real failure. |
| Concurrency guard | ready | Per-group lock serializing the reclaim-decision + build. Closes a pre-existing race too. |
| Unit/component test | ready | Extend `BotGroupBehaviorServiceRestartTest` (harness already present). |
| Staging DEAD inducement | partial | Fully-deterministic DEAD-inducement via API alone is environment-dependent; recipe + fallbacks in Verification. |
| UI change | out of scope | The existing Start button already maps to `start(id)`; no controller/UI change needed. |

---

## Architecture Decisions

1. **Adopt Option A (reclaim in `start()`), reject Option B (remove-on-death in the
   health monitor).** In `start()`, if `runningGroups` holds the id AND its
   `actualStatus == ACTIVE`, keep today's no-op. If it holds the id with any other status
   (DEAD), perform the full teardown and fall through to rebuild.
   **Why A over B:** Option B (removing the runtime from `runningGroups` in
   `handleBotGroupDeath`) breaks the documented contract that `getHealth`/`getStatus`/`stop`
   surface a DEAD group from memory (`getHealth` returns a STOPPED skeleton when the runtime
   is absent — `:896-907`; `getActualStatus` returns STOPPED — `:1379-1383`), and forces the
   health-monitor thread to also credit the dead-window + shut its own schedulers from
   inside its own tick (crediting a window on the very thread being shut down, plus
   perturbing the `groups_dead_currently`/`countGroupsDeadCurrently` observability that
   iterates `runningGroups` — `:1255-1261`). Option A confines the change to the single
   `start()` entry point, leaves the DEAD-surfacing semantics of health/status/stop
   **byte-for-byte unchanged**, and reuses the proven `stop()` teardown. Least blast radius.

2. **The discriminator is `actualStatus == ACTIVE`, not a bespoke "viable" predicate.**
   A genuinely running group (including one mid-reconnect but not yet DEAD) is ACTIVE; the
   only non-ACTIVE state a runtime can hold while still in `runningGroups` is DEAD. Using
   `actualStatus == ACTIVE` for the no-op branch keeps `shouldNoOpWhenAlreadyRunning`
   green and needs no new enum/flag.

3. **Reclaim routes teardown through `runtime.stopAllBots(botMetrics)` under group MDC —
   the exact `stop()` sequence.** Extract the three shared steps of `stop()`
   (`stopAllBots(botMetrics)` under `BotMdc.setGroupContext`, then
   `sessionAggregationService.evictGroup(id)`, then `runningGroups.remove(id)`) into a
   private helper `teardownRuntimeMemory(String id, BotGroupRuntime runtime)`. `stop()`
   calls it and then additionally persists `targetStatus=STOPPED`; the reclaim path calls
   it and then falls through to the normal build (no intermediate STOPPED DB write — the
   rebuild sets ACTIVE, or the zero-bot guard sets DEAD). One teardown implementation, no
   drift. (Dev may inline the three lines in the reclaim path instead of extracting, if
   leaving `stop()` untouched is preferred — behavior is identical either way; default is
   extract.)

4. **Dead-seconds are credited exactly once by the reclaim, via the same
   `creditGroupDeadSeconds` path.** No new accounting code. The reclaim's
   `stopAllBots(botMetrics)` credits the open window and clears `groupDeadSince`; the new
   runtime starts with `groupDeadSince=null`. This mirrors `restart()`→`stop()`, so bare
   `start()`-reclaim and `restart()` produce identical `group_dead_seconds_total` behavior.

5. **A per-group lock serializes the reclaim-decision + build.** Add
   `private final ConcurrentHashMap<String, ReentrantLock> groupLocks`. `start()` acquires
   `groupLocks.computeIfAbsent(id, k -> new ReentrantLock())`, holds it across the whole
   method body, and releases in `finally`. This makes the `containsKey`/reclaim/`put`
   sequence atomic: a second concurrent Start blocks, then observes the fresh ACTIVE
   runtime and no-ops — no double-build, no overwrite-leak. The lock map is bounded by the
   number of groups (tens) and is never cleaned (negligible). `stop()` acquires the same
   per-group lock so an operator Stop cannot race a Start's reclaim; `restart()` needs no
   lock of its own because its `stop()` and `start()` each acquire/release sequentially
   (non-nested → no reentrancy/deadlock concern). Holding the lock across the (I/O-bound,
   internally-parallel) bot build blocks a second concurrent admin request for the build
   duration — acceptable for an infrequent, admin-triggered action, and strictly safer
   than the racy status quo.

6. **The health-monitor DEAD path is left unchanged.** `handleBotGroupDeath` continues to
   `markAsDead` + persist DEAD + leave the runtime in the map. The DEAD runtime's idle
   health-monitor thread cost (pre-existing) is now *recovered on the next Start/restart*
   by the reclaim; proactively reaping idle DEAD runtimes is **out of scope** (a separate
   concern with its own observability implications).

7. **No new endpoint, no UI change, no DB schema change.** The existing `POST /{id}/start`
   already reaches `start(id)`; making `start(id)` reclaim is sufficient for "single-click
   restart of a DEAD group". `restart()` is unchanged and continues to work.

8. **Scope guard: this plan does not alter registration, deposit, or the zero-bot DEAD
   semantics.** Those are owned by RESTART_LIFECYCLE_FIX / API_ERROR_FORWARDING and remain
   as-is. The reclaim reuses them unchanged.

---

## Plan

Two phases. Phase 1 is the whole code change (independently buildable, unit-verifiable,
and satisfies the single-click requirement). Phase 2 is the staging deploy + verification.

### Phase 1 — Reclaim-on-DEAD in `start()` + per-group lock + tests

One Dev session. All in
`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java`
plus one test file.

**Step 1a — Extract the shared teardown helper (AD-3).** Add:
```java
/** Tear down a runtime's in-memory footprint: credit the open DEAD window (once),
 *  cleanup bots, shut executor + health monitor + logout scheduler, evict session
 *  state, drop from runningGroups. Shared by stop() and the start() reclaim path. */
private void teardownRuntimeMemory(String id, BotGroupRuntime runtime) {
    BotMdc.setGroupContext(runtime.getGroupId(), runtime.getEnvironmentId());
    try {
        runtime.stopAllBots(botMetrics);
    } finally {
        BotMdc.clear();
    }
    sessionAggregationService.evictGroup(id);
    runningGroups.remove(id);
}
```
Refactor `stop()` (`:715-750`) to call `teardownRuntimeMemory(id, runtime)` in place of
its inline `stopAllBots`+`evictGroup`+`remove` (lines `:728-741`), leaving its
`targetStatus=STOPPED` persistence + final log intact. Behavior-preserving for `stop()`.

**Step 1b — Reclaim guard in `start()` (AD-1, AD-2).** Replace the guard at `:227-230`:
```java
BotGroupRuntime existing = runningGroups.get(id);
if (existing != null) {
    if (existing.getActualStatus() == BotGroupStatus.ACTIVE) {
        log.warn("Bot group {} is already running", id);
        return;
    }
    // Lingering non-viable (DEAD) runtime from the health-monitor death path or the
    // zero-bot start path. Reclaim: full teardown (credits the open dead-window once,
    // shuts monitor + logout scheduler + executor, cleans up bots), then fall through
    // to rebuild from the persisted group + existing accounts. See DEAD_GROUP_RESTART.
    log.info("Bot group {} has a non-viable ({}) runtime — reclaiming before restart",
            id, existing.getActualStatus());
    teardownRuntimeMemory(id, existing);
}
```

**Step 1c — Per-group lock (AD-5).** Add the field:
```java
private final ConcurrentHashMap<String, ReentrantLock> groupLocks = new ConcurrentHashMap<>();
```
Wrap the body of `start(id)` (everything after resolving `id`) and the body of `stop(id)`
in:
```java
ReentrantLock lock = groupLocks.computeIfAbsent(id, k -> new ReentrantLock());
lock.lock();
try { /* existing body */ } finally { lock.unlock(); }
```
Do **not** add a lock inside `restart()` (AD-5 rationale). Import
`java.util.concurrent.locks.ReentrantLock`.

**Step 1d — Tests** in `BotGroupBehaviorServiceRestartTest.java` (harness already present):

1. `start_reclaimsAndRebuildsWhenRuntimeIsDead()` — pre-seed `runningGroups` (via the
   same reflection accessor pattern used in `BotGroupBehaviorServiceTest.runningGroups()`;
   add a small private accessor to this test) with a runtime whose `actualStatus=DEAD` and
   `groupDeadSince` stamped (call `runtime.markAsDead()`). Stub `botFactory.createBot` to
   return 3 mock bots. Call `service.start("g-1")`. Assert: `botFactory.createBot` invoked
   3 times (rebuild happened — NOT a no-op), the map now holds a **new** runtime with
   `actualStatus=ACTIVE`, and `botGroupService.save` persisted `targetStatus=ACTIVE`. This
   is the primary regression test — it fails on `main` (start no-ops on the DEAD runtime).
2. `start_isNoOpWhenRuntimeIsActive()` — pre-seed an ACTIVE runtime; assert
   `botFactory.createBot` is never called and the same runtime instance remains (pins
   AD-2 / mirrors `shouldNoOpWhenAlreadyRunning`).
3. `start_reclaimCreditsGroupDeadSecondsExactlyOnce()` — wire a real
   `SimpleMeterRegistry` + `BotMetrics` (pattern from
   `incBotCreationFailure_seriesIsTaggedWith...` at `:365-440`). Seed a DEAD runtime with
   `groupDeadSince` set ~in the past; `start()` with a working factory. Assert
   `group_dead_seconds_total` incremented once (>0) and `runtime.getGroupDeadSince()` on
   the *old* runtime is null after reclaim. Guards AD-4 against double/zero credit.
4. `start_reclaimShutsOldMonitorAndLogoutScheduler()` — seed a DEAD runtime, attach a real
   (or spy) `healthMonitor` + `logoutScheduler` via the setters; after `start()`, assert
   both are `isShutdown()` (guards AD-6/thread-leak trap: no orphaned schedulers).
5. `start_reclaimDoesNotRegisterOrDeposit()` — seed DEAD runtime, `start()` with working
   factory; verify (Mockito) that no registration collaborator is invoked. Since
   `BotGroupBehaviorService` has no registration collaborator at all, assert this
   structurally: the only account-facing call is `botFactory.createBot` (re-auth), and
   assert `botGroupService.save` is called for the ACTIVE flip but never a create/register
   API. (Belt-and-suspenders for AD-8.)

Run `JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home
mvn -q -pl bot-app test -Dtest=BotGroupBehaviorServiceRestartTest,BotGroupBehaviorServiceTest`.
Test #1 must fail before Step 1b and pass after. All existing tests (esp.
`shouldNoOpWhenAlreadyRunning`) stay green.

### Phase 2 — Staging deploy + verification

Build (`JAVA_HOME=...21... mvn clean install -DskipTests -Dmaven.javadoc.skip=true` then
the test suite), ship to Bot-1, run the `## Verification` steps below. Note the Bot-1
single-compose layout (MEMORY): the deploy also restarts Grafana/Prometheus/Loki, so the
smoke check must re-verify observability is up.

---

## Implementation Notes / Concerns

- **`stopAllBots` is safe to call on a zero-bot DEAD runtime.** The zero-bot DEAD path
  already calls it (`:386`); the reclaim of a health-monitor-DEAD runtime (which has N bots
  with dead WS) calls `bot.cleanup()` per bot — `cleanup()` sets `stopped=true` before
  closing, suppressing `onDisconnect` retry (see `stopAndLogout` Javadoc `:763-772`), so
  the reclaim manufactures **zero** false reconnect events/threads. This is the same
  guarantee that made the cascade-delete path leak-free.
- **Order matters: reclaim happens *before* `findById(id)`** in `start()` (the guard is at
  `:227`, ahead of the `findById` at `:232`). The reclaim only needs the runtime object
  (it carries `groupId`/`environmentId`), so this ordering is fine and keeps the reclaim
  cheap even if the group was concurrently deleted (then `findById` throws afterward and
  the existing `finally` at `:423-457` cleans the — already-removed — id as a no-op).
- **The `finally` cleanup at `:423-457` still holds** after reclaim: on a build failure it
  `runningGroups.remove(id)` (removes the *new* runtime) and tears it down. The old runtime
  was already torn down by the reclaim, so there is no double-teardown of the same runtime
  and no leak.
- **Do not remove the `!runtime.isGroupDead()` guard in `monitorHealth` (`:1431`).** It is
  what makes the (still-running, pre-reclaim) health-monitor tick idempotent while the
  group is DEAD. The reclaim's `stopAllBots` is what finally stops that scheduler.
- **Lock hold-time.** AD-5 holds the per-group lock across the parallel bot build (up to
  ~tens of seconds for a large group). This blocks a second concurrent admin request on the
  same group, which is the desired serialization. A lighter alternative (hold only through
  the `runningGroups.put` insert, then release) is possible but the reasoning about a
  partially-held lock during the `finally` cleanup is subtler; the whole-method hold is
  chosen for provable correctness. If request-thread starvation is ever observed, revisit.
- **No change to `getHealth`/`getStatus`/`stop` DEAD-surfacing.** Verified: a DEAD group
  still reports DEAD from memory right up until the moment an operator Starts/Restarts it,
  exactly as today (AD-1).
- **Reconciler/onStartup interplay.** `onStartup` runs with an empty `runningGroups`, so
  the reclaim branch is never hit at boot; the activation reconciler calling `start(id)` on
  a group that went DEAD at runtime now benefits from reclaim automatically. Neither path
  changes the manual-override mode flip (that lives only at the controller, `:143-176`).

---

## Open Items

- **Deterministic DEAD-inducement on staging** without host-shell access is
  environment-dependent (see Verification for the recipe + fallbacks). If the fleet has no
  naturally-DEAD group at deploy time, the Releaser induces one via the documented recipe;
  this is the one step that may need light operator involvement.
- **Out of scope:** proactively reaping idle DEAD runtimes on a timer (AD-6); Option B
  (remove-on-death) — explicitly rejected (AD-1); any UI/endpoint change (AD-7); changes to
  registration/deposit/zero-bot semantics (AD-8).
- **Residual (accepted):** if an operator Starts a genuinely-broken DEAD group (env truly
  unreachable), the reclaim rebuilds and the zero-bot guard re-marks it DEAD — correct
  behavior (does not hide the failure), but the operator sees "still DEAD" after the click.
  That is the env being broken, not the fix.

---

## Verification

Releaser runs these on Bot-1 (staging) after deploy. Substitute `<bot-1>` with the staging
host. Every step has an explicit expected result.

### Universal smoke (post-deploy, single-compose layout)

1. App up:
   ```
   until curl -sf https://<bot-1>/actuator/health | grep -q '"status":"UP"'; do sleep 2; done
   ```
   Expect: returns within 60 s.
2. Observability stack survived the co-located redeploy (MEMORY: Bot-1 single-compose):
   ```
   curl -sf http://<bot-1>:9090/-/healthy ; curl -sf http://<bot-1>:3000/api/health
   ```
   Expect: Prometheus `Healthy`, Grafana JSON `"database":"ok"`.

### Reclaim-on-DEAD end-to-end (the mandated test)

3. Find a currently-DEAD group, or induce one. First list groups and their runtime status:
   ```
   curl -sf https://<bot-1>/api/v1/bot-group/ | jq -r '.[] | .id'
   # for each id:
   curl -sf https://<bot-1>/api/v1/bot-group/<id>/status | jq '{id, targetStatus, actualStatus}'
   ```
   Expect: identify a group with `actualStatus == "DEAD"`. Record its `id` as `$G`, its
   `botCount`, and its `environmentId`.

   **If no DEAD group exists, induce one deterministically:**
   - Preferred (working env, proves successful recovery): pick a small healthy group on a
     known-good env (097/BOM), start it, then have the operator sever its game-server WS
     connectivity for ~2 health-monitor cycles (~70 s) so ≥80% of its bots go DEAD and the
     health monitor marks the **group** DEAD. Confirm with the `/status` call above showing
     `actualStatus=DEAD`, then restore connectivity before the reclaim step.
   - Fallback (no operator lever): create a throwaway 2-bot group whose environment
     authenticates (real auth gateway) but whose `webSocketMiniUrl` points at an
     unreachable host; start it and poll `/status` until `actualStatus=DEAD`. This proves
     the reclaim runs (no no-op) even if it cannot prove a *successful* rebuild; pair it
     with the preferred recipe when an operator is available. (The DNS-blocked TIP env from
     MEMORY is a candidate broken env, but confirm its groups actually reach `DEAD` rather
     than churning RECONNECTING before relying on it.)

4. Capture the pre-click thread count and the group's scheduler threads:
   ```
   curl -sf https://<bot-1>/actuator/metrics/jvm.threads.live | jq '.measurements[0].value'
   ```
   Expect: record the value as `$THREADS_BEFORE`. (Optionally capture a `jstack`/thread dump
   if the operator has JVM access, and count threads named `health-monitor-$G` and
   `logout-scheduler-$G` — expect exactly one lingering of each for the DEAD group.)

5. **Single click: Start the DEAD group once.**
   ```
   curl -sf -o /dev/null -w '%{http_code}\n' -X POST https://<bot-1>/api/v1/bot-group/$G/start
   ```
   Expect: HTTP `200`.

6. Confirm reclaim ran (not a no-op). Grep the app logs for the reclaim line and the
   absence of the old no-op line for `$G` in this window:
   ```
   # via Loki/Grafana or container logs, scoped to the last ~2 min and botGroupId=$G:
   #   expect a line matching:  Bot group $G has a non-viable (DEAD) runtime — reclaiming before restart
   #   expect NOT to see:       Bot group $G is already running
   ```
   Expect: the `reclaiming before restart` INFO line is present; the `is already running`
   WARN is absent for `$G`.

7. Confirm the rebuild re-authenticated **existing** accounts and did **not** register or
   provision new ones. In the same log window for `$G`:
   ```
   #   expect per-bot:  Successfully created bot <namePrefix><n> for environment <envId>   (auth path)
   #   expect ABSENT:   any "Skipping user registration" / "registerUsers" / registration-result line
   #   expect ABSENT:   any account-creation/registration API call for $G
   ```
   Expect: `Successfully created bot ...` lines for the group's existing usernames
   (`namePrefix` + index), and **zero** registration log lines in this window. (Registration
   only ever logs from `BotGroupService.save` at group create — it must not appear on a
   Start.) Auto-deposit during subsequent play is normal and out of scope for this negative
   check.

8. Confirm the group came back ACTIVE with its bots:
   ```
   sleep 60
   curl -sf https://<bot-1>/api/v1/bot-group/$G/status | jq '{targetStatus, actualStatus}'
   curl -sf https://<bot-1>/api/v1/bot-group/$G/health | jq '{totalBots, connectedBots}'
   ```
   Expect (working-env case): `targetStatus="ACTIVE"`, `actualStatus="ACTIVE"`,
   `totalBots == botCount`, `connectedBots >= floor(botCount * 0.8)`. (Broken-env fallback
   case: `actualStatus="DEAD"` again is acceptable — it proves the reclaim ran and did not
   hide the failure; the successful-recovery assertion is satisfied only by the working-env
   recipe.)

9. Confirm no thread/scheduler leak from the reclaim:
   ```
   curl -sf https://<bot-1>/actuator/metrics/jvm.threads.live | jq '.measurements[0].value'
   ```
   Expect: value is within `botCount + small constant` of `$THREADS_BEFORE` — i.e. it grew
   only by the rebuilt group's bot/scheduler threads, not by a leaked duplicate set. In a
   thread dump, expect exactly **one** `health-monitor-$G` and **one** `logout-scheduler-$G`
   thread (the old DEAD ones were shut by the reclaim, not orphaned).

10. Confirm dead-seconds credited exactly once (no leaked-open or double window):
    ```
    curl -sf https://<bot-1>/actuator/prometheus | grep '^group_dead_seconds_total'
    ```
    Expect: the counter reflects the closed DEAD window for `$G` and is not still climbing
    for `$G` after the group is ACTIVE (a climbing series would mean the window was left
    open). `groups_dead_currently` for `$G` should be back to not-counting-`$G`.

### Idempotency spot-check (AD-5)

11. Two rapid Start clicks on a now-ACTIVE group must not double-build or leak:
    ```
    curl -sf -o /dev/null -w '%{http_code}\n' -X POST https://<bot-1>/api/v1/bot-group/$G/start &
    curl -sf -o /dev/null -w '%{http_code}\n' -X POST https://<bot-1>/api/v1/bot-group/$G/start &
    wait
    curl -sf https://<bot-1>/api/v1/bot-group/$G/health | jq '{totalBots}'
    curl -sf https://<bot-1>/actuator/metrics/jvm.threads.live | jq '.measurements[0].value'
    ```
    Expect: both return `200`; `totalBots == botCount` (not doubled); thread count is stable
    (not a duplicate bot/scheduler set). Logs should show one `already running` no-op for the
    losing click, not a second rebuild.
</content>
</invoke>
