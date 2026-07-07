# Timed Activation — Recurring Time-of-Day Activation for Bot Groups

## Goal

Give a bot group a **recurring activation predicate** so it runs only while the
current wall-clock time falls inside a configured **time-of-day window**,
optionally restricted to a **set of days-of-week** (e.g. "weekends 18:00–24:00").
The predicate recurs forever — it is NOT a one-shot absolute window. A periodic
**reconciler** evaluates `active = predicate(now)` for each scheduled group and
drives the existing start/stop lifecycle so `actual → target` converges. The
evaluation and reconciler are written as a reusable unit so the later **Fleet**
abstraction (see `docs/plans/BETTING_INTELLIGENCE_ROADMAP.md`) can drive the same
activation without a rewrite. This plan covers **group-level activation only** —
no Fleet container is built here.

## Findings — Current State

- **Dormant absolute fields exist and are wired to nothing.** `BotGroup` carries
  `boolean timeBased`, `LocalDateTime timeFrom`, `LocalDateTime timeUntil`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/model/BotGroup.java:45-48`).
  They round-trip through the DTO
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/dto/BotGroupDTO.java:57-60`)
  and mapper
  (`.../mapper/BotGroupMapper.java:37-39, 75-77, 111-113`) but **no service reads
  them** (grep across `src/main` finds only model/DTO/mapper references). They are
  one-shot absolute `LocalDateTime`, the wrong shape for recurring windows. The
  only test coverage is `BotGroupMapperTest` (lines 48-50, 116-118, 204-205),
  which just asserts the round-trip.

- **Dual-status lifecycle is explicit, not a background loop.**
  `targetStatus` (`ACTIVE|STOPPED|DEAD`) is persisted on `BotGroup`
  (`BotGroup.java:104`); `actualStatus` lives on `BotGroupRuntime`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/runtime/BotGroupRuntime.java:63`).
  There is **no continuous actual→target reconcile loop**. The only convergence
  points are (a) `BotGroupBehaviorService.onStartup()` `@PostConstruct`, which
  starts every group with `targetStatus==ACTIVE`
  (`BotGroupBehaviorService.java:178-194`), and (b) the explicit
  `start()`/`stop()`/`restart()` REST paths
  (`BotGroupBehaviorService.java:204, 562, 685`). **Consequence for this plan:**
  the reconciler must itself call `start()`/`stop()` — those methods already
  persist `targetStatus` and manage the `runningGroups` map. "Set targetStatus and
  let the runtime reconcile" is realized by *calling start/stop*, which is the
  reconcile action.

- **`start()`/`stop()` are idempotent-ish and safe to call from a tick.**
  `start()` early-returns if the group is already in `runningGroups`
  (`BotGroupBehaviorService.java:206-209`); `stop()` early-returns if it is not
  (`:563-567`). Both persist status and audit timestamps. `isGroupRunning(id)`
  (`:956-959`) is the correct "is it up?" probe (checks runtime present AND
  `actualStatus==ACTIVE`).

- **Schedulers are hand-rolled virtual-thread `ScheduledExecutorService`s, not
  Spring `@Scheduled`.** There is **no `@EnableScheduling`** anywhere in the
  codebase. `BotGroupBehaviorService` owns a shared 4-thread `scheduler`
  (`:129, 157`) for scheduled restarts, plus per-group `healthMonitor`
  (`:1156-1172`, every 30 s) and `logoutScheduler`
  (`:1220-1262`) executors, all built with `Thread.ofVirtual().factory()`. The new
  tick must follow this pattern.

- **Health monitor only runs for groups that are up.** `startHealthMonitoring`
  is invoked inside `start()` (`:274`) and torn down in
  `BotGroupRuntime.stopAllBots` (`:279-281`). It marks a group DEAD when
  `dead/total ≥ 0.80` (`:1190-1192`, `handleBotGroupDeath` sets
  `targetStatus=DEAD` at `:1205`). A group that is *scheduled-inactive* is
  fully stopped and absent from `runningGroups`, so the health monitor is not
  running for it and cannot mark it DEAD — no conflict by construction.

- **Periodic-logout and scheduled-restart also only touch running groups.**
  `performPeriodicLogout` gates on `actualStatus==ACTIVE`
  (`:1270-1274`) and the scheduler is torn down with the runtime. Scheduled
  restart (`scheduleRestart`, `:715-734`) is a one-shot `scheduler.schedule` that
  calls `restart()` — independent of activation.

- **JSR-310 serialization already works.** `LocalDateTime` fields serialize
  through the DTO today, so `LocalTime` (→ `"HH:mm:ss"`) and `DayOfWeek`
  (→ enum name) will serialize with no extra config. MongoDB persists them via
  the standard Spring Data converters.

- **Config style.** Tunables are `@Value` on the service with
  `application.properties` defaults (e.g. `bot.watchdog.timeout.seconds`,
  `BotGroupBehaviorService.java:122`). Properties file:
  `/Users/gleb/IdeaProjects/Bot/src/main/resources/application.properties`.

- **Repository is a plain `MongoRepository`** with derived finders including
  `findByTargetStatus`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/repository/BotGroupRepository.java:15`).
  A `findByActivationMode` finder can be added the same way.

## Per-aspect readiness / mapping

| Aspect | Readiness | Notes |
|---|---|---|
| Recurring window model (`LocalTime` + `Set<DayOfWeek>`) | ready | New value object; JSR-310 already serializes. |
| Activation mode (`SCHEDULED / MANUAL_ON / MANUAL_OFF`) | ready | New enum on `BotGroup`, nullable = legacy. |
| Drop dormant `timeBased/timeFrom/timeUntil` | ready | Remove from model/DTO/mapper + `BotGroupMapperTest`. Mongo keeps stale fields harmlessly. |
| Reconciler tick source | ready | Mirror existing virtual-thread `ScheduledExecutorService`; no `@EnableScheduling` needed. |
| Drive lifecycle via `start()/stop()` | ready | Idempotent guards already present. |
| Health-monitor non-interference | ready | Inactive group is stopped → monitor not running. No code change. |
| Periodic-logout / scheduled-restart non-interference | ready | Both scoped to running groups. |
| Manual override coexistence | partial | Requires manual `/start`,`/stop` to flip mode on scheduled groups; small change to `start()/stop()` or controller. |
| Timezone reference | ready | New app-wide config property. |
| DEAD-group non-resurrection | ready | Reconciler skips DEAD; manual stop parks as `MANUAL_OFF`. |
| Reuse seam for Fleet | ready | Pure evaluator + interface-fed reconciler. |

## Architecture Decisions

**AD-1 — Condition model (bounded, typed).** A group gains one nullable value
object `ActivationWindow`:
```java
class ActivationWindow {
    LocalTime from;          // required when window present
    LocalTime to;            // required when window present
    Set<DayOfWeek> days;     // null or empty = every day
}
```
plus a nullable enum on `BotGroup`:
```java
enum ActivationMode { SCHEDULED, MANUAL_ON, MANUAL_OFF }
```
No cron, no rule engine, no OR. The day-of-week set is the single optional AND
restriction. `days == null || days.isEmpty()` means **all seven days**.

**AD-2 — Reconciler drives `start()/stop()` directly.** There is no background
actual→target loop in this codebase (Findings). The reconciler *is* the loop: each
tick it computes `desired = predicate(now)`, compares to `isGroupRunning(id)`, and
calls `start(id)` or `stop(id)`. Those methods already persist `targetStatus`. This
is restart-safe by construction — no edge bookkeeping, no "missed fire" state; a
process restart simply resumes ticking and re-derives desired state from `now`.

**AD-3 — Reconciler only acts on `activationMode == SCHEDULED` with a non-null
window.** `MANUAL_ON`, `MANUAL_OFF`, and `null` (legacy, non-timed groups) are
skipped. A group opts into scheduling by setting `activationMode=SCHEDULED` **and**
an `activationWindow`. Every existing group (mode `null`) behaves exactly as today.

**AD-4 — Manual override flips mode on scheduled groups.** When an operator hits
`POST /{id}/start` or `POST /{id}/stop` on a group whose `activationMode` is
`SCHEDULED` (or already manual), the endpoint sets the mode to `MANUAL_ON`
(start) / `MANUAL_OFF` (stop) before/after performing the action, so the next tick
does not undo it. Groups with `activationMode == null` are untouched — their
start/stop semantics are unchanged. To hand a group back to the schedule, PATCH
`activationMode=SCHEDULED`. This is the whole override contract: **manual actions
park the group; PATCH un-parks it.**

**AD-5 — Timezone: single app-wide configured `ZoneId`, default
`Asia/Ho_Chi_Minh`.** Property `bot.activation.zone`. Justification:
(a) time-of-day windows model *local player wall-clock* traffic, which for these
products is Vietnam time; (b) an **explicitly configured** zone is stable across
the 3 Docker instances (prod/loadtest/staging) regardless of container `TZ` —
server-local time is implicit and fragile in containers that default to UTC;
(c) a single business zone avoids per-group UI/validation bloat. Per-group or
per-environment zone is a deliberate future extension — the property is read in
one place (the evaluator) so widening it later is local. **Not implicit: reject
server-local time.**

**AD-6 — Inactive semantics: fully STOPPED (bots disconnect).** When the window is
closed the group is `stop()`-ed: WebSockets close, bots deregister. Justification:
(a) realism — real players are not connected at 04:00; (b) resource use at scale
and avoidance of the observed server-side subscriber-pruning limit for idle
connections; (c) it reuses the exact existing start/stop machinery — a
"connected-but-idle" mode would require a new bot pause state that does not exist
and would fight the watchdog and periodic-logout schedulers. Accepted cost:
auth/reconnect churn at the ~2 window boundaries per day per group — negligible
versus a continuous idle footprint.

**AD-7 — Window semantics (midnight crossing + validation).** With zone `Z`, let
`t = now.atZone(Z).toLocalTime()` and `d = now.atZone(Z).getDayOfWeek()`:
- **Non-wrapping** (`from < to`): active iff `from <= t < to` **and** the day
  gate holds for `d`.
- **Wrapping** (`from > to`, e.g. 22:00→02:00): active iff (`t >= from`
  **and** day gate holds for `d`) **OR** (`t < to` **and** day gate holds for
  `d.minus(1)`). The day-of-week set is anchored to the day the window **opened**
  — a Fri 22:00–02:00 window is active Sat 01:00 only if **Friday** is in the set.
- **Day gate**: `days` null/empty ⇒ always true; else `set.contains(day)`.
- **Validation (HTTP 400 on create/update)**: when `activationMode==SCHEDULED`,
  `activationWindow` is required and both `from` and `to` non-null; `from == to`
  is **rejected** (ambiguous zero-length vs all-day — operators wanting all-day
  use a `null` window with mode `MANUAL_ON`, or omit scheduling). Every
  `DayOfWeek` in `days` must be a valid enum value (Jackson enforces). Seconds
  precision is allowed but minute precision is the intended granularity.

**AD-8 — Tick source and cadence.** A new `@Component ActivationScheduler` owns a
single-thread virtual `ScheduledExecutorService` (mirroring
`startHealthMonitoring`), scheduled in `@PostConstruct` at fixed rate
`bot.activation.tick-seconds` (default **60**). The **first tick is aligned to the
next wall-clock minute boundary** (`:00`): compute the initial delay as the ms
until the next minute in the configured zone, then `scheduleAtFixedRate(initialDelay,
period)`. This makes an `18:00` window flip at ≈`18:00:0x` rather than a random
boot-relative offset, and keeps transition logs on clean minute marks. Minute
granularity bounds worst-case lateness at a window edge to one tick (≤60 s), which
is acceptable for time-of-day activation. **Group edits take effect on the next tick with no extra
wiring** because the reconciler re-reads each scheduled group from Mongo every tick
— the persisted window is the single source of truth.

**AD-9 — DEAD groups are never auto-resurrected.** The reconciler skips any group
whose current status is `DEAD` (persisted `targetStatus==DEAD`, or `actualStatus`
DEAD in a live runtime). DEAD is a terminal operator-attention signal
(`handleBotGroupDeath`); silently restarting it on the next tick would create a
crash-loop. Recovery path: operator issues `POST /{id}/stop` (which, per AD-4,
parks the scheduled group as `MANUAL_OFF`), investigates, then PATCHes
`activationMode=SCHEDULED` to rejoin the schedule.

**AD-10 — Startup ownership.** `onStartup()` is changed to **skip groups with
`activationMode==SCHEDULED`** — the first reconciler tick owns them, avoiding a
boot-time start→immediate-stop churn for a group whose window is currently closed.
Non-scheduled groups (`null` mode) auto-start on `targetStatus==ACTIVE` exactly as
today. `MANUAL_ON`/`MANUAL_OFF` groups are already governed by their persisted
`targetStatus`, so they resume correctly across a restart with no special casing.

**AD-11 — Reuse seam for Fleet.** Split into three pieces so Fleet reuses two of
them verbatim:
1. `ActivationWindow.isActiveAt(Instant now, ZoneId zone)` — a **pure function**,
   no Spring, the whole predicate (AD-7). Fleet reuses as-is.
2. `ActivationDecision reconcile(mode, window, running, now, zone)` — a pure
   decision function returning `START | STOP | NONE`, independent of whether the
   target is a group or a fleet.
3. `ActivationScheduler` — the shell that today loads scheduled `BotGroup`s and
   applies the decision via `behaviorService.start/stop`. When Fleet lands, it
   feeds fleets through the same #1/#2 without touching them. Do **not** inline
   the predicate into `BotGroupBehaviorService`.

**AD-12 — Supersede the dormant fields.** Delete `timeBased`, `timeFrom`,
`timeUntil` from `BotGroup`, `BotGroupDTO`, and all three `BotGroupMapper` methods,
and drop their assertions from `BotGroupMapperTest`. Mongo retains the stale
fields on old documents as harmless ignored data (no migration needed; Spring Data
ignores unmapped fields). This is a clean removal, not a rename.

## Plan

### Phase 1 — Domain model + dormant-field removal
- Add `ActivationMode` enum
  (`.../domain/botgroup/model/ActivationMode.java`): `SCHEDULED, MANUAL_ON,
  MANUAL_OFF`.
- Add `ActivationWindow`
  (`.../domain/botgroup/model/ActivationWindow.java`): fields `from`/`to`
  (`LocalTime`), `days` (`Set<DayOfWeek>`); Lombok `@Data`/`@Builder`.
  Implement the pure `boolean isActiveAt(Instant now, ZoneId zone)` per AD-7,
  plus a package-private `boolean dayGate(DayOfWeek d)`.
- On `BotGroup` (`.../model/BotGroup.java`): add `ActivationMode activationMode`
  and `ActivationWindow activationWindow`; **remove** `timeBased`, `timeFrom`,
  `timeUntil` (lines 45-48).
- On `BotGroupDTO`: add `activationMode` and `activationWindow`; **remove**
  `timeBased`, `timeFrom`, `timeUntil` (lines 57-60).
- Update `BotGroupMapper` `toDTO`/`toEntity`/`updateEntityFromDTO`: map the two new
  fields (PATCH = full-replace when non-null, mirroring `slotStrategyId`); delete
  the three dormant mappings.
- Update `BotGroupMapperTest` to drop dormant-field assertions and add the two new
  fields to the round-trip.
- Add `findByActivationMode(ActivationMode)` to `BotGroupRepository`.
- Unit-test `ActivationWindow.isActiveAt` directly: non-wrapping inside/outside,
  wrapping across midnight (both halves), empty-day-set = all days, day gate on the
  opening day for the wrapped post-midnight half, `from==to` handled by validation
  not the predicate.

### Phase 2 — Validation
- Add activation validation to the create/update path. Reuse the existing seam:
  extend `BotGroupConfigValidationService.validate(BotGroup)`
  (called from `BotGroupService.save` `:147` and `update` `:286`) with an
  activation check, OR add a focused validator. Rules per AD-7: if
  `activationMode==SCHEDULED` then `activationWindow` non-null with non-null
  `from`/`to`; `from!=to`. Throw `BadRequestException` (→ HTTP 400). Non-scheduled
  modes require no window.
- Unit-test the validator: SCHEDULED without window → 400; SCHEDULED with
  `from==to` → 400; SCHEDULED with valid window → ok; `null` mode with no window →
  ok (legacy).

### Phase 3 — Pure reconcile decision + scheduler shell
- Add `ActivationDecision` enum (`START, STOP, NONE`) and a pure
  `ActivationEvaluator.decide(ActivationMode mode, ActivationWindow window,
  boolean running, boolean dead, Instant now, ZoneId zone)` implementing AD-3,
  AD-9, AD-2 (skip if not SCHEDULED / null window / DEAD; else compare
  `isActiveAt` to `running`).
- Add `ActivationScheduler` `@Component`:
  - `@Value("${bot.activation.zone:Asia/Ho_Chi_Minh}")` → `ZoneId`.
  - `@Value("${bot.activation.tick-seconds:60}")`.
  - Single-thread virtual `ScheduledExecutorService` (name
    `activation-reconciler`), scheduled in `@PostConstruct` via
    `scheduleAtFixedRate(initialDelay, period, SECONDS)` where `initialDelay` is
    the ms/seconds until the next wall-clock minute boundary in the configured zone
    (AD-8 minute alignment), `period = bot.activation.tick-seconds`; shut down in
    `@PreDestroy`.
  - `reconcileAll()`: `botGroupRepository.findByActivationMode(SCHEDULED)` →
    for each, `boolean running = behaviorService.isGroupRunning(id)`,
    `boolean dead = group.getTargetStatus()==DEAD || actualStatus==DEAD`, then
    `switch (evaluator.decide(...))`: `START → behaviorService.start(id)`,
    `STOP → behaviorService.stop(id)`, `NONE → nothing`. Wrap each group in
    try/catch so one failure does not abort the tick; set group MDC
    (`BotMdc.setGroupContext`) around each, mirroring the existing schedulers.
  - Log at INFO only on an actual transition:
    `Activation reconcile: group {id} window {open|closed} → {START|STOP}`; log
    `NONE` at DEBUG to keep the healthy-system INFO volume bounded (consistent with
    CLAUDE.md logging guidance — group-level lifecycle at INFO, steady-state at
    DEBUG).
- Inject `BotGroupBehaviorService` and `BotGroupRepository` (or
  `BotGroupService`). Guard against the `@Lazy` cycle the same way
  `BotGroupService` already does if a cycle appears.
- Add properties to `application.properties`: `bot.activation.zone` and
  `bot.activation.tick-seconds` with the doc comments.

### Phase 4 — Manual-override integration + startup ownership
- **Manual override (AD-4):** in the controller `start`/`stop` handlers
  (`BotGroupController.java:142-154`) or in `BotGroupBehaviorService.start/stop`,
  after the action, if the group's `activationMode` is non-null (i.e. it is a
  scheduled-capable group), set it to `MANUAL_ON` (start) / `MANUAL_OFF` (stop) and
  persist. Groups with `activationMode==null` are left untouched — no behavior
  change for existing non-timed groups. Prefer implementing in the service so the
  auto-start path is unaffected (auto-start must NOT flip mode).
  - Important: `onStartup` auto-start and the reconciler's own `start()/stop()`
    calls must **not** flip the mode (that would defeat scheduling). Implement the
    flip at the controller layer (operator-initiated only), keeping
    `behaviorService.start/stop` mode-neutral. This is the cleanest split — the
    reconciler and startup call the service, the operator calls the controller.
- **Startup ownership (AD-10):** change `onStartup()`
  (`BotGroupBehaviorService.java:178-194`) to skip groups with
  `activationMode==SCHEDULED`; the first reconciler tick starts them if their
  window is open.
- Tests: reconciler decision table (unit, pure); controller test that `/stop` on a
  SCHEDULED group sets `MANUAL_OFF` and `/start` sets `MANUAL_ON`, while a
  `null`-mode group's mode stays null.

### Phase 5 — Docs
- Update `CLAUDE.md` REST table only if endpoints change (they do not — reuse
  existing start/stop; PATCH carries the new fields). Add a short note that
  `timeBased/timeFrom/timeUntil` were superseded by `activationMode` +
  `activationWindow`. (Docs-only; no code.)

## Implementation Notes / Concerns

- **`start()`/`stop()` are the reconcile primitives — do not reimplement.** They
  already guard double-start/double-stop and persist status; the reconciler just
  needs to call the right one based on `isGroupRunning`.
- **MDC around each group in the tick.** Follow `startHealthMonitoring`
  (`:1162-1171`): `BotMdc.setGroupContext(...)` in try / `BotMdc.clear()` in
  finally, per group, so transition logs carry `botGroupId`/`environmentId`.
- **Tick isolation.** One group's `start()` can take seconds (parallel bot auth).
  A single-thread reconciler serializes groups; a slow start delays other groups'
  evaluation within a tick. Acceptable at current scale (tens of groups, 60 s
  cadence). If it becomes a problem, widen to a small pool later — do **not**
  fan-out per-group threads now (keeps ordering and log clarity).
- **Boundary churn is expected, not a bug.** At a window edge a group transitions
  once; the next tick sees it running/stopped and does `NONE`. Verify no flapping
  by confirming the second tick logs `NONE`.
- **DEAD detection source.** A DEAD group may be either still in `runningGroups`
  with `actualStatus==DEAD` or already persisted `targetStatus==DEAD`. Check both
  (AD-9). `findByActivationMode(SCHEDULED)` returns it; the evaluator returns
  `NONE`.
- **Do not flip mode from the reconciler or auto-start.** Only operator-initiated
  controller calls flip to MANUAL (AD-4). A stray flip inside the service would
  make a group deactivate itself permanently on the first scheduled stop.
- **Jackson/Mongo.** `LocalTime` and `Set<DayOfWeek>` need no extra config; confirm
  the round-trip in `BotGroupMapperTest` and an integration create/read.
- **Scheduled-restart interaction (minor).** A one-shot `scheduleRestart` firing
  during a closed window will start the group; the next reconciler tick (≤60 s)
  stops it. This is a benign transient, called out in Open Items — not fixed here.

## Open Items

- **Fleet abstraction** — out of scope. This plan structures the evaluator +
  decision as reusable units (AD-11) but builds no Fleet container.
- **Per-group / per-environment timezone** — deferred (AD-5). Single app-wide zone
  now; the property is read in one place so widening is local.
- **UI** — not covered here; frontend adds window/mode inputs to the bot-group
  form and consumes the new DTO fields. Backend contract is this plan.
- **Scheduled-restart vs closed window** — benign transient (Implementation Notes);
  left as-is unless it proves annoying in practice.
- **"All day" ergonomics** — an all-day-every-day schedule is expressed today as
  `MANUAL_ON` (always up) rather than a `00:00–00:00` window (rejected by AD-7). If
  operators want a genuine all-day *scheduled* entry, add an explicit `ALWAYS`
  window flag later.

## Verification

Run on staging after deploy. Substitute `$H` for the bot-manager base URL
(e.g. `http://localhost:8085`) and `$ENV`/`$GAME` for a valid environment id and a
game id in that environment. The server zone is assumed `Asia/Ho_Chi_Minh`
(AD-5); compute "open now" / "closed now" windows in that zone.

**0. Universal smoke test.** App is up:
```bash
curl -s -o /dev/null -w '%{http_code}\n' "$H/actuator/health"
```
Expect `200`.

**1. Reconciler is scheduled (log).** After startup, within one tick interval:
```bash
# on the bot-manager host / container logs
grep -c "activation-reconciler" <logfile>     # thread present
```
Expect the reconciler thread to appear (≥1). Set the logger to DEBUG to see
per-tick `NONE` lines if needed:
`curl -s -XPOST "$H/actuator/loggers/com.vingame.bot" -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'`.

**2. Create a group whose window is OPEN now → it starts within one tick.**
Set `from`/`to` to straddle the current server-local time, empty `days`,
`activationMode=SCHEDULED`, `targetStatus` unset:
```bash
GID=$(curl -s -XPOST "$H/api/v1/bot-group/" -H 'Content-Type: application/json' -d '{
  "environmentId":"'"$ENV"'","namePrefix":"tact","password":"Test1234","gameId":"'"$GAME"'",
  "botCount":2,"minBet":100,"maxBet":100,"betIncrement":0,"maxTotalBetPerRound":1000,
  "minBetsPerRound":1,"maxBetsPerRound":1,
  "activationMode":"SCHEDULED",
  "activationWindow":{"from":"00:00:00","to":"23:59:00","days":[]}
}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
echo "$GID"
sleep 75   # > one 60s tick
curl -s "$H/api/v1/bot-group/$GID/status"
```
Expect JSON with `"actualStatus":"ACTIVE"` and `"targetStatus":"ACTIVE"`.
Expect a log line matching `Activation reconcile: group .* → START`.

**3. Create a group whose window is CLOSED now → it stays stopped.**
Use a 1-minute window in the recent past-and-future that does NOT include now
(e.g. `from`/`to` bracketing a time an hour from now):
```bash
GID2=$(curl -s -XPOST "$H/api/v1/bot-group/" -H 'Content-Type: application/json' -d '{
  "environmentId":"'"$ENV"'","namePrefix":"tinact","password":"Test1234","gameId":"'"$GAME"'",
  "botCount":2,"minBet":100,"maxBet":100,"betIncrement":0,"maxTotalBetPerRound":1000,
  "minBetsPerRound":1,"maxBetsPerRound":1,
  "activationMode":"SCHEDULED",
  "activationWindow":{"from":"03:00:00","to":"03:01:00","days":[]}
}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
sleep 75
curl -s "$H/api/v1/bot-group/$GID2/status"
```
(Adjust `03:00–03:01` so it excludes the current server-local minute.)
Expect `"actualStatus":"STOPPED"`. Expect **no** `→ START` reconcile line for
`$GID2`.

**4. Manual override parks a scheduled group (AD-4).** Stop the open group from
step 2:
```bash
curl -s -o /dev/null -w '%{http_code}\n' -XPOST "$H/api/v1/bot-group/$GID/stop"   # expect 200
curl -s "$H/api/v1/bot-group/$GID" | python3 -c 'import sys,json;print(json.load(sys.stdin)["activationMode"])'
sleep 75
curl -s "$H/api/v1/bot-group/$GID/status"
```
Expect `activationMode` == `MANUAL_OFF`, and after a full tick
`"actualStatus":"STOPPED"` — i.e. the reconciler did **not** restart it. Expect
no `→ START` line for `$GID` during the wait.

**5. Un-park returns it to the schedule.** PATCH the mode back to SCHEDULED:
```bash
curl -s -o /dev/null -w '%{http_code}\n' -XPATCH "$H/api/v1/bot-group/$GID" \
  -H 'Content-Type: application/json' -d '{"activationMode":"SCHEDULED"}'   # expect 200
sleep 75
curl -s "$H/api/v1/bot-group/$GID/status"
```
Expect `"actualStatus":"ACTIVE"` again (window still open) with a fresh
`→ START` reconcile line.

**6. Validation rejects a bad schedule (AD-7).**
```bash
curl -s -o /dev/null -w '%{http_code}\n' -XPOST "$H/api/v1/bot-group/" \
  -H 'Content-Type: application/json' -d '{
   "environmentId":"'"$ENV"'","namePrefix":"tbad","password":"Test1234","gameId":"'"$GAME"'",
   "botCount":1,"activationMode":"SCHEDULED"}'
```
Expect `400` (SCHEDULED with no window). Same expectation for a window with
`"from":"10:00:00","to":"10:00:00"`.

**7. Cleanup.**
```bash
curl -s -o /dev/null -w '%{http_code}\n' -XDELETE "$H/api/v1/bot-group/$GID"
curl -s -o /dev/null -w '%{http_code}\n' -XDELETE "$H/api/v1/bot-group/$GID2"
```
Expect `200` each.

**Midnight-crossing note:** a full wrap-around live test (e.g. `22:00–02:00`
verified at `01:30`) cannot be forced without waiting for real time or changing the
clock; it is covered by the Phase 1 unit tests of `ActivationWindow.isActiveAt`.
The staging checks above verify the reconciler wiring, manual override, startup
ownership, and validation end-to-end.
