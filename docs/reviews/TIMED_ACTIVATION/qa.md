# QA — TIMED_ACTIVATION

**Verdict:** PASS
**Build:** `mvn clean install` → 1283 tests, 0 failures, 0 errors (was 1270 on the diff; +13 QA-added)

## Tests added / updated

- `src/test/java/com/vingame/bot/domain/botgroup/service/ActivationSchedulerTest.java` — NEW. 7 tests
  covering the previously-untested reconciler shell: decision→lifecycle wiring
  (open+not-running → `start()`; closed+running → `stop()`; converged → no-op),
  DEAD detection from **both** the persisted `targetStatus` and the live runtime
  `actualStatus` (AD-9, neither start nor stop), per-group error isolation within a
  tick (one group throwing does not abort the pass; the next group is still
  reconciled), and the empty-scheduled-set no-op. Windows are built relative to the
  real clock (±1h) so open/closed is deterministic and non-flaky at any wall-clock
  time including midnight.
- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupServiceTest.java` — +2 tests
  (`SetActivationModeTests`): `setActivationMode` flips the mode, re-stamps
  `updatedAt`, persists, and leaves `targetStatus` untouched (AD-4); missing group
  → `ResourceNotFoundException` with no save.
- `src/test/java/com/vingame/bot/domain/botgroup/validation/BotGroupConfigValidationServiceTest.java` — +2 tests:
  the `ActivationRules` seam runs first in `validate()` — a SCHEDULED group with no
  window is a 400 **before** gameId resolution (game/validator path never reached);
  a valid SCHEDULED window passes the seam and proceeds to the game-type validator.
- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceTest.java` — +2 tests
  (`OnStartupOwnershipTests`): startup ownership (AD-10) — a SCHEDULED group is
  skipped (`start()` never entered, so `findById` is never called for it; not added
  to `runningGroups`), while a legacy null-mode group still auto-starts (`start()`
  entered → `findById` called).

## Coverage of the diff

- `model/ActivationWindow.java` (`isActiveAt`, `dayGate`) ← `ActivationWindowTest` (dev-authored):
  non-wrapping inside/edges/outside, midnight-wrapping both halves + exclusive close
  edge, empty/null days = all days, wrapping day-gate anchored to the **opening** day
  (all four quadrants), from==to never active. Thorough; no additions needed.
- `model/ActivationEvaluator.java` (`decide`) ← `ActivationEvaluatorTest` (dev-authored):
  full decision table — SCHEDULED START/STOP/NONE vs running, DEAD → NONE,
  MANUAL_ON/MANUAL_OFF/null skipped, SCHEDULED+null-window → NONE. Complete.
- `validation/ActivationRules.java` ← `ActivationRulesTest` (dev) + `BotGroupConfigValidationServiceTest`
  (QA-added seam wiring): SCHEDULED without window / null from / null to / from==to →
  400; MANUAL_ON/MANUAL_OFF/null → ok; and the seam is invoked from the service
  validate() path ahead of the game lookup.
- `mapper/BotGroupMapper.java` + `dto/BotGroupDTO.java` + `model/BotGroup.java`
  ← `BotGroupMapperTest` (dev-updated): `activationMode`/`activationWindow` round-trip
  through `toDTO`/`toEntity`, PATCH full-replace-when-non-null and keep-when-null,
  null-defaults. The dormant `timeBased/timeFrom/timeUntil` removal is enforced by
  compilation — those fields no longer exist on model/DTO/mapper, and the test file
  no longer references them (would not compile otherwise).
- `service/ActivationScheduler.java` ← `ActivationSchedulerTest` (QA-added): reconcile
  wiring, DEAD sourcing, error isolation (see above). The `@PostConstruct` minute-alignment
  / thread scheduling and `millisUntilNextMinute` timing are exercised at runtime only
  (see Gaps).
- `service/BotGroupService.java` (`setActivationMode`) ← `BotGroupServiceTest` (QA-added).
- `service/BotGroupBehaviorService.java` (`onStartup` SCHEDULED skip) ← `BotGroupBehaviorServiceTest` (QA-added).
- `controller/BotGroupController.java` (`applyManualOverride` on start/stop) ←
  `BotGroupControllerTest` (dev-updated): manual start parks SCHEDULED → MANUAL_ON,
  manual stop parks SCHEDULED → MANUAL_OFF, null-mode groups untouched
  (`setActivationMode` never called). Complete.
- `repository/BotGroupRepository.java` (`findByActivationMode`) ← exercised via the
  `ActivationSchedulerTest` mock; a derived Mongo finder needs no dedicated unit test.

## Gaps

- **Reconciler tick scheduling / minute-alignment (`@PostConstruct start()`,
  `millisUntilNextMinute`, `scheduleAtFixedRate`).** Not unit-tested — this is
  wall-clock-timing and thread-lifecycle glue; asserting it deterministically would
  require injecting a clock the production code does not expose. Covered by the
  plan's staging Verification step 1 (reconciler thread present) and steps 2–5
  (open/closed/park/un-park end-to-end within a tick). The pure decision it drives is
  fully unit-covered.
- **Mongo persistence of `LocalTime` + `Set<DayOfWeek>` (real serialization
  round-trip).** Covered at the mapper/DTO shape level; the actual Spring
  Data/Mongo document round-trip is integration-only (no Testcontainers in this
  module's unit suite) and is exercised by staging Verification steps 2–3.
- **onStartup ownership** is asserted at the skip/enter boundary (findById
  called-or-not); the full downstream auto-start of a legacy group is not driven to
  completion (it fails the env guard by design in the unit context). The reconciler
  taking ownership of the skipped group is covered by `ActivationSchedulerTest` +
  staging.

## Failures

None.

## Notes

- Confirmed the known cosmetic commit-staging slip: `ActivationScheduler.java` and the
  two `bot.activation.*` properties landed in commit `138171d` ("pure reconcile
  decision", Phase 3) rather than a dedicated scheduler-shell commit. This is
  organizational only — the code is correct, wired, and tested — and per the QA brief
  is noted, not failed.
- One intermediate strict-stubbing miss in the QA-added `deadPersistedNeverStarts`
  (the persisted-DEAD `||` short-circuits `getActualStatus`, making that stub
  unnecessary) was fixed before commit; final suite is clean.
