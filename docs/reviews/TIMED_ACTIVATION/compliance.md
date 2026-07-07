# Compliance — TIMED_ACTIVATION

Branch: feat/timed-activation
Plan reviewed: `docs/plans/TIMED_ACTIVATION.md` (at commit 7341457)
Diff reviewed: `git diff 4bb89747..HEAD` (code commits 9593029..83f62da)

## Verdict

PASS

## Phase-by-phase

### Phase 1 — Domain model + dormant-field removal
Status: implemented
Notes:
- `ActivationMode` enum (`SCHEDULED, MANUAL_ON, MANUAL_OFF`) added at
  `src/main/java/com/vingame/bot/domain/botgroup/model/ActivationMode.java`.
- `ActivationWindow` (`from`/`to` `LocalTime`, `days` `Set<DayOfWeek>`, Lombok
  `@Data`/`@Builder`) with pure `isActiveAt(Instant, ZoneId)` and package-private
  `dayGate(DayOfWeek)`. AD-7 honored exactly: non-wrapping `from<=t<to` with day
  gate; wrapping (`from>to`) is pre-midnight anchored to today OR post-midnight
  anchored to the opening day (`d.minus(1)`); `from==to` returns false (deferred
  to validation). No Spring imports — AD-11 piece #1 satisfied.
- `BotGroup` and `BotGroupDTO`: `activationMode` + `activationWindow` added;
  `timeBased`/`timeFrom`/`timeUntil` removed (AD-12). Grep across `src/main` and
  `src/test` confirms zero remaining references to the dormant fields.
- `BotGroupMapper` all three methods (`toDTO`/`toEntity`/`updateEntityFromDTO`)
  updated; PATCH is full-replace-when-non-null, mirroring `slotStrategyId`.
- `findByActivationMode(ActivationMode)` added to `BotGroupRepository`.
- `BotGroupMapperTest` updated to the new fields; `ActivationWindowTest` covers
  non-wrapping, wrapping (both halves), empty-day-set, and opening-day gate.

### Phase 2 — Validation
Status: implemented
Notes:
- `ActivationRules.validate(BotGroup)` implements AD-7: SCHEDULED requires a
  non-null window with non-null `from`/`to` and `from != to`; non-scheduled/null
  modes pass. Violations accumulate into one `BadRequestException` (→ 400).
- Wired into the existing seam: `BotGroupConfigValidationService.validate` calls
  `ActivationRules.validate(group)` before game-type resolution, so it runs on
  both create (`save`) and update (post-merge) paths as the plan specified.
- `ActivationRulesTest` covers the four decision cases.

### Phase 3 — Pure reconcile decision + scheduler shell
Status: implemented
Notes:
- `ActivationDecision` (`START, STOP, NONE`) and `ActivationEvaluator.decide(...)`
  are pure, no Spring deps (AD-11 piece #2). Decision logic: not SCHEDULED or null
  window → NONE (AD-3); dead → NONE (AD-9); else compare `isActiveAt` to `running`
  (AD-2). Matches the spec.
- `ActivationScheduler` @Component: single-thread virtual `ScheduledExecutorService`
  named `activation-reconciler`; `@PostConstruct` schedules `scheduleAtFixedRate`
  with `initialDelay = millisUntilNextMinute()` in the configured zone (AD-8
  minute alignment) and period `tick-seconds*1000` ms; `@PreDestroy` shuts down.
  `@Value` zone default `Asia/Ho_Chi_Minh` (AD-5) and tick-seconds default 60.
- `reconcileAll` loads `findByActivationMode(SCHEDULED)`, wraps each group in
  try/catch with `BotMdc.setGroupContext`/`clear`, computes
  `running=isGroupRunning(id)` and `dead = targetStatus==DEAD || actualStatus==DEAD`,
  and drives `behaviorService.start/stop` per the decision. INFO on START/STOP
  transitions, DEBUG on NONE — consistent with CLAUDE.md logging guidance.
- `BotGroupBehaviorService` injected `@Lazy` to guard the cycle, as the plan
  allowed. Properties added to `application.properties` with doc comments.
- `ActivationEvaluatorTest` provides the decision table.

### Phase 4 — Manual-override integration + startup ownership
Status: implemented
Notes:
- Manual override lives ONLY at the controller (AD-4): `BotGroupController.start`
  and `.stop` call `applyManualOverride(id, MANUAL_ON|MANUAL_OFF)`, which no-ops
  for `activationMode == null` (legacy groups untouched) and otherwise calls
  `service.setActivationMode`. `setActivationMode` flips only `activationMode` +
  `updatedAt`, never `targetStatus`, and skips validation (correct — MANUAL modes
  carry no window requirement).
- Reconciler and `onStartup` call `behaviorService.start/stop` directly and never
  touch `activationMode` — invariant (1) holds.
- `onStartup` skips `activationMode == SCHEDULED` groups (AD-10), logging the skip;
  null/manual groups auto-start on `targetStatus==ACTIVE` as before.
- `BotGroupControllerTest` updated for the override behavior.

### Phase 5 — Docs
Status: implemented
Notes:
- CLAUDE.md backlog entry flipped to done with a description of the mechanism and
  the supersession note for the dormant fields.
- `docs/FE_INTEGRATION_...md` section 11 documents the DTO contract, window shape,
  midnight-crossing/day-anchor semantics, the `to:"00:00:00"` vs `24:00:00` and
  `from==to` gotchas, manual-override contract, and FE form actions.

## Key invariants

1. Reconciler/onStartup never flip `activationMode` — confirmed: the flip is
   isolated to `BotGroupController.applyManualOverride`; `setActivationMode`
   documents itself as controller-only; reconciler and `onStartup` call the
   mode-neutral `behaviorService.start/stop`. Holds.
2. Inactive = fully STOPPED via existing start/stop (AD-6) — the scheduler issues
   `behaviorService.stop(id)`, reusing the existing lifecycle. Holds.
3. DEAD groups never auto-resurrected (AD-9) — `decide` returns NONE when `dead`;
   `dead` is derived from persisted `targetStatus==DEAD` OR live
   `actualStatus==DEAD` (`getActualStatus` returns STOPPED for absent runtimes, so
   a DEAD group persisted as `targetStatus==DEAD` after stop is still caught).
   Holds.
4. Pure `isActiveAt`/`decide` carry no Spring deps — confirmed by import
   inspection; both live in `...model` with only `java.time`/JDK imports. Fleet
   can reuse them (AD-11). Holds.

## Out-of-scope changes

None. The diff touches only the files the plan named (model/DTO/mapper/repo,
validation seam, scheduler, controller/service integration, properties, docs,
tests). Fleet, per-group timezone, UI, and the scheduled-restart-vs-closed-window
transient are all correctly left in Open Items. Main sources compile cleanly
(`mvn -o compile`).

## Notes

- Organizational only (not a compliance defect, per review scope): the
  `ActivationScheduler` shell landed inside commit `138171d`
  ("feat(timed-activation): pure reconcile decision") rather than in its own
  commit. Content and correctness are unaffected.
