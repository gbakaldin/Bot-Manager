# Code Review — TECH_DEBT_CLEANUP_2026_07

Branch: `chore/tech-debt-cleanup-2026-07`
Reviewed diff: `git diff main..chore/tech-debt-cleanup-2026-07`

Four commits: 410cb99 (EnvironmentMapper), d03b4c3 (filter contains-match),
dc8d7fd (SessionHistory UUID), be7e11f (start() marks DEAD on zero bots).

## Verdict

PASS

No `bug` or `security` findings. The two findings below are `smell`-level and
advisory — both live on the item-4 zero-bot DEAD path and describe a bounded,
operator-recoverable resource/metric consequence of returning early past the
`finally` cleanup, not a correctness defect. The DEAD-transition reuse itself is
correct and consistent with `handleBotGroupDeath`.

## Findings

### [smell] Health-monitor scheduler is left running on the zero-bot DEAD path
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:351,366-377`

`start()` calls `startHealthMonitoring(runtime)` (:351) *before* the new zero-bot
check (:366). Unlike `startPeriodicLogoutScheduler` — which early-returns on an
empty bot list (:1449-1452) and therefore creates no scheduler — `startHealthMonitoring`
(:1369) **unconditionally** creates a `ScheduledExecutorService`, registers it on
the runtime (`runtime.setHealthMonitor`), and schedules the 30s task.

On the zero-bot path the code then does `started = true; return;` (:375-376),
which bypasses the `finally` block (:395-424). That `finally` is the only place a
failed/partial start calls `runtime.stopAllBots(...)`, and `stopAllBots` (`BotGroupRuntime.java:295-297`)
is the only place the health monitor is shut down. Net effect: a live
health-monitor `ScheduledExecutorService` is left running against a DEAD, zero-bot
runtime.

Runtime impact is bounded, not critical: `monitorHealth` early-returns on empty
bots (`:1392`), so the scheduled task is a 30s no-op, and the leaked scheduler is
reclaimed when the operator eventually issues `/stop` (`stop()` calls
`stopAllBots`, since the DEAD runtime is intentionally left in `runningGroups`).
But a group the operator considers "not viable / dead" may never be explicitly
stopped, so the scheduler can linger indefinitely. The plan comment at
`BotGroupBehaviorService.java:361`/plan AD-2 asserts the schedulers "early-return
on empty and are harmless" — that is true for the periodic-logout scheduler but
**not** for the health monitor, which is created regardless.

Fix shape: either (a) move `startHealthMonitoring`/`startPeriodicLogoutScheduler`
to *after* the zero-bot check so neither is started on the DEAD path, or (b) on
the zero-bot branch, shut the health monitor down before returning
(`runtime.getHealthMonitor()` is already exposed and null-guarded in
`stopAllBots`). Option (a) is cleaner and also avoids the metric consequence below.

### [smell] `markAsDead()` opens a group-dead-seconds window that this path never credits
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:367`

`runtime.markAsDead()` (`BotGroupRuntime.java:212-218`) stamps `groupDeadSince`,
and that window is only credited to `group_dead_seconds_total` inside
`stopAllBots(metrics)` via `creditGroupDeadSeconds` (`BotGroupRuntime.java:266`).
Because the zero-bot branch returns with `started = true` and skips the `finally`
cleanup, `stopAllBots` is never called on this path — so the window stays open
until the operator eventually stops the group. When that stop finally happens,
the entire wall-clock interval the group sat idle-DEAD (possibly days) is credited
in one shot, inflating `group_dead_seconds_total` well beyond the real "outage"
that the metric is meant to capture.

This is the same root cause as the finding above (returning past the `finally`
that runs `stopAllBots`), and the same fix (a: start schedulers after the check;
or credit + shut down on the branch) resolves both. Note `handleBotGroupDeath`
(the sibling this path models itself on) does *not* have this problem: it marks a
still-running group DEAD and leaves it running until a normal `stop()`, where the
dead window reflects genuine downtime — whereas here the group is dead from second
zero with nothing to recover.

## Notes

- **Item 1 (EnvironmentMapper) — clean.** The primitive/boxed `useJwtAuth`
  asymmetry is handled exactly per the established idiom: `toDTO` reads the
  primitive getter (auto-boxes), `toEntity` uses
  `Optional.ofNullable(dto.getUseJwtAuth()).orElse(false)`, and
  `updateEntityFromDTO` uses `.orElse(entity.isUseJwtAuth())` — mirroring
  `alertOnLowBalance`/`customZone`/`binaryFrame`. The two boxed periodic-logout
  fields correctly carry `null` through in `toEntity` (no fabricated default) and
  preserve the existing value on partial update, which matches the
  `null = use global default` consumer contract in
  `startPeriodicLogoutScheduler` (:1435-1441). Tests cover all three methods
  including the partial-update "keep untouched" case.

- **Item 2 (filter contains-match) — clean, no injection concern.** Both call
  sites (`EnvironmentService.java:69`, `BotGroupService.java:100`) retain
  `Pattern.quote(...)`, so the user-supplied name is treated as a literal — no
  regex-injection surface (removing the `^`/`$` anchors does not change that).
  Sole consumers of `filter(...)` are the controllers returning the list to the
  UI; no internal caller depends on exact-match uniqueness, so the broadened
  match has no unintended breadth beyond the intended "filter box" UX. Now
  consistent with the already-contains `GameService`.

- **Item 6 (SessionHistory UUID) — clean.** Guard matches the sibling shape
  (`GameService`/`BotGroupService`: `id == null || id.isEmpty()`). It uses
  `isEmpty()` rather than `isBlank()`, so a whitespace-only id would pass through
  un-generated — but that is identical to both siblings, so it is idiom-consistent,
  not a deviation. No production writer exists today (forward-looking), so no
  read-back contract is at risk.

- **Item 4 mutation ordering is fine.** On the DEAD branch, `save(group)` is
  called after all setters, and `setLastStoppedAt(null)` is deliberate (the group
  started, it did not stop) — consistent with the ACTIVE branch. Reusing
  `markAsDead()` + `setTargetStatus(DEAD)` + `setLastFailureReason(...)` + `save`
  faithfully mirrors `handleBotGroupDeath` (:1411-1424); keeping the runtime in
  `runningGroups` is the right call so `getHealth`/`getStatus`/`stop` still see it.
  The decision not to throw (preserving `onStartup` per-group isolation) is sound.
