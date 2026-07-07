# Code Review — TIMED_ACTIVATION

Branch: feat/timed-activation
Reviewed diff: `git diff 4bb89747..HEAD` (code commits `9593029..83f62da`; `7341457` is planning docs)

## Verdict

PASS

PASS = no `bug` or `security` findings; the smells/styles below are advisory.

## Findings

### [smell] Manual-override flip is action-then-persist, which leaves a TOCTOU race with the reconciler
`src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java:145-181`

`start`/`stop` perform the lifecycle action first and only then flip the mode:

```java
behaviorService.stop(id);
applyManualOverride(id, ActivationMode.MANUAL_OFF);
```

`applyManualOverride` runs on the request thread; the reconciler runs on its own
`activation-reconciler` thread. Between `behaviorService.stop(id)` (which removes
the group from `runningGroups` and persists `targetStatus=STOPPED`) and
`service.setActivationMode(id, MANUAL_OFF)`, a reconciler tick can fire, still
see `activationMode==SCHEDULED`, evaluate the window as open, observe
`running==false`, and decide `START` — restarting the group the operator just
stopped. The subsequent `setActivationMode(MANUAL_OFF)` then parks it, so the
end state is a group that is *running* but flagged `MANUAL_OFF`, contradicting
the operator's stop and never reconverged (the reconciler now skips it because
it is no longer `SCHEDULED`). Symmetric hazard on `/start` (a tick can `STOP`
between the start action and the `MANUAL_ON` flip).

The window is small (~ms against a 60 s cadence) so this is low-probability, not
a correctness-in-the-common-case bug — hence a smell, not a bug. It is worth
noting because persisting the mode **before** the action would close it: with
`setActivationMode(MANUAL_OFF)` first, an interleaved tick sees a non-`SCHEDULED`
mode and returns `NONE`, so the action cannot be undone. AD-4 explicitly permits
"before/after" ordering; persist-first is the strictly safer choice here. (Note
the introspective inconsistency: the `setActivationMode` javadoc and the
`applyManualOverride` javadoc both describe the flip as protecting "the next tick",
but action-then-persist does not protect the tick that races the current call.)

### [smell] New concurrent caller of `start()`/`stop()` exposes the pre-existing check-then-act in `start()`
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:220-275`

`start()` guards with `runningGroups.containsKey(id)` at :222 and only `put`s the
runtime at :275 after a multi-second parallel bot-auth build. This
check-then-act is not atomic. Before this feature the only callers were
`onStartup` (single-threaded at boot) and the controller. The reconciler now
adds a genuinely concurrent caller on a separate thread, so an operator `/start`
racing a reconciler `START` for the same group can both pass the `containsKey`
gate and both build+`put`, double-creating bots and leaking the first runtime.
The code being raced is pre-existing and outside this diff, so I am not tagging
it a bug against this branch — but the feature materially raises the odds of
hitting it. If tightened later, the shape is a `runningGroups.putIfAbsent` /
`compute` reservation (or a per-group lock) rather than the containsKey/put pair.
The single-thread reconciler cannot race *itself* (`scheduleAtFixedRate` never
overlaps the same task), so this is purely reconciler-vs-controller.

### [smell] `applyManualOverride` reads the group twice
`src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java:175-181`

The helper calls `service.findById(id)` to inspect `activationMode`, then
`service.setActivationMode(id, manualMode)` which itself calls `findById(id)`
again and does a read-modify-write `save`. Two Mongo reads for one logical flip,
and the RMW in `setActivationMode` is a lost-update against a concurrent PATCH.
Both are negligible at current scale; folding the null-mode check into
`setActivationMode` (return early if the loaded group's mode is null) would drop
one read and keep the decision on a single loaded document.

### [smell] `@Lazy` on `BotGroupBehaviorService` has no demonstrated cycle to break
`src/main/java/com/vingame/bot/domain/botgroup/service/ActivationScheduler.java:57`

Nothing in the graph depends on `ActivationScheduler`, so its injection of
`BotGroupBehaviorService` cannot itself close a cycle. The plan (Phase 3) says
"guard against the `@Lazy` cycle the same way `BotGroupService` already does *if
a cycle appears*." `@Lazy` here is harmless-but-speculative; if it is load-bearing
it would be good to note which edge it breaks, otherwise it can be dropped. Not a
defect — flagging only so the annotation is not cargo-culted forward.

## Notes

- **`ActivationWindow.isActiveAt` is correct** for every case in the focus list.
  Non-wrapping uses `!t.isBefore(from) && t.isBefore(to)` = `from <= t < to`
  (inclusive-from, exclusive-to) as specified. The wrapping branch anchors the
  post-midnight half to the opening day via `dayGate(d.minus(1))` — verified for
  a Fri 22:00–02:00 window: active Fri 23:00 (`dayGate(FRI)`), active Sat 01:00
  (`dayGate(FRI)` through `d.minus(1)`), closed Sat 22:00 (`dayGate(SAT)` false),
  and closed at exactly `to` (02:00) since `t.isBefore(to)` is exclusive. `from==to`
  falls through to `return false`. Day-gate null/empty handling is correct.
- **Null-safety on the predicate** rests on validation + the evaluator's
  `window == null` short-circuit. A `SCHEDULED` group with null `from`/`to`
  cannot be persisted: `ActivationRules.validate` runs on both `save` (:148) and
  the post-merge `update` (:310), so PATCHing `activationMode=SCHEDULED` onto a
  windowless group is rejected 400. `setActivationMode` (the only path that skips
  validation) writes only `MANUAL_ON`/`MANUAL_OFF`, never `SCHEDULED`, so it
  cannot smuggle an invalid scheduled group past the checks. The chain holds.
- **DEAD detection** checks both sources (`targetStatus==DEAD ||
  getActualStatus(id)==DEAD`, :131-132); `getActualStatus` safely returns
  `STOPPED` for an absent runtime. A DEAD `SCHEDULED` group is still returned by
  `findByActivationMode` and correctly resolves to `NONE` each tick (DEBUG only),
  never resurrected — matches AD-9. The reconciler never writes `activationMode`,
  so it cannot self-park (AD-4 honored).
- **Minute alignment** (`millisUntilNextMinute`) is correct and never returns 0
  (on an exact boundary it targets the next minute, a full period out). Note the
  alignment only anchors the *first* tick; with a non-default `tick-seconds` that
  is not a divisor of 60 the subsequent ticks drift off minute marks — expected
  and documented (AD-8), not a defect.
- **`ZoneId` binding** is bound as `String` + `ZoneId.of(zone)` in the
  constructor, matching the focus note; an invalid zone fails fast at bean
  construction (loud startup failure), which is the right behavior.
- **`onStartup` change (AD-10)** is a clean early-`return` skip for
  `activationMode==SCHEDULED` inside the existing `forEach`; non-scheduled and
  null-mode groups are wholly unchanged.
- **Dormant-field removal (AD-12)** is clean: `grep` across `src/` finds zero
  residual `timeBased`/`timeFrom`/`timeUntil` references in model, DTO, or
  mapper; `LocalDateTime` imports remain valid (still used by
  `scheduledRestartTime`/`lastStartedAt`/`lastStoppedAt`). No dangling refs.
- **Logging levels** conform to CLAUDE.md: transitions (`→ START`/`→ STOP`) and
  reconciler start/shutdown at INFO (group-level lifecycle), steady-state `NONE`
  at DEBUG, per-group errors at ERROR with the cause attached. MDC is set/cleared
  per group in try/finally, mirroring the health/logout schedulers. No token or
  secret logging in the diff.
- **`@PreDestroy` shutdown** uses `shutdownNow()` with a null-guard; consistent
  with the codebase's other hand-rolled schedulers.
- **Minor (already-known):** the scheduler shell landed in commit `138171d`
  ("pure reconcile decision") rather than its own Phase 3 scheduler commit — a
  cosmetic commit-staging slip, no code impact.

Review verdict: PASS
Findings: 0 bug, 0 security, 4 smell, 0 style
docs/reviews/TIMED_ACTIVATION/review.md written.
