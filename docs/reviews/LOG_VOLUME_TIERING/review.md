# Code Review — LOG_VOLUME_TIERING

Branch: `feature/log-volume-tiering`
Reviewed diff: `git diff staging..feature/log-volume-tiering` (21 commits, 51 files)

## Verdict

CHANGES_REQUESTED

Four `bug` findings. No `security` findings. The concurrency-critical hot path
(`ScopedDebugRegistry.isAnyEnabled()` → `ScopedDebugFilter.decide`) is otherwise
correctly built — no allocation, no MDC copy, no map access when idle — and the
AD deviations (Configuration-level filter attach, `blocking = true`, per-group
rollup line) are the right calls and are correctly reasoned in the code.

The bugs cluster on one theme: **the mechanisms that are supposed to bound this
feature's own cost (the escalation cooldown, the fast-path flag, the installer's
attachment) each have a hole that fails in the direction of "silently wrong",
and two of them are the exact failure modes their own javadoc says they prevent.**

## Findings

### [bug] Auto-escalation re-arms forever: a persistently sick group holds scoped DEBUG open permanently
`bot-engine/src/main/java/com/vingame/bot/infrastructure/observability/ScopedDebugEscalator.java:78-87`,
`bot-app/src/main/resources/application.properties` (`escalation.minutes=15`, `escalation.cooldown-minutes=15`)

The escalation TTL and the escalation cooldown default to **the same 15 minutes**,
so the cooldown expires at the same instant the scope does. The class javadoc
claims the opposite:

> "One escalation per group per cooldown (default 15 min, the same as the TTL), so
> a flapping group expiring a watchdog every three minutes holds DEBUG open for
> its 15 minutes and then goes quiet rather than re-arming forever."

It does not go quiet. Concretely, with `bot.group.dead.threshold=0.80`:

1. A group settles at `dead/total = 0.5` — deteriorating but under the death
   threshold, so `handleBotGroupDeath` never fires and the group is never
   evicted. `monitorHealth` calls `onGroupHealth` **every 30 s**, and 0.5 sits
   inside the `[0.40, 0.80)` escalation band on every one of those ticks.
2. `t=0`: escalate. Scope granted to `t=15:00`, cooldown to `t=15:00`.
3. `t=15:00`: the registry sweeper expires the scope. At `t=15:00+30s` the next
   health tick finds the cooldown lapsed and re-escalates. Scope to `t=30:00`.
4. Repeat indefinitely. Duty cycle ≈ 96%, unattended, for as long as the group
   stays half-broken.

The watchdog trigger has the same shape and a more likely fuse: `CLAUDE.md`
documents server-side subscriber pruning that leaves bots silently zombied
(watchdog expiry every `bot.watchdog.timeout.seconds`, default 180 s) and ~230
reconnects/bot/day on TIP prod at only 5 bots (against
`reconnect-threshold=5` per 5 min). Any of those keeps re-arming past every
cooldown lapse. With `max-scopes=50`, up to 50 groups can be pinned at DEBUG
continuously, with nobody having typed a command — which is a large fraction of
the fleet-wide DEBUG this feature exists to make impossible, on the box that
ENOSPC'd on 2026-06-30.

`ScopedDebugEscalatorTest` ("a flapping group cannot re-arm inside the cooldown,
and can after it") asserts the re-arm at `t > cooldown` is *successful*, so the
test encodes the behaviour rather than catching it.

Fix shape: make the cooldown a *quiet period measured from scope expiry*, not
from the last escalation — i.e. require `now - lastEscalation >= ttl + cooldown` —
or add a per-group escalation budget (e.g. at most N escalations per rolling
hour, then stop and WARN once). Either way the invariant to state and test is
"an unattended group cannot be at DEBUG for more than X% of any hour", which is
the property the javadoc already claims.

### [bug] `anyEnabled` lost-update race can leave scoped DEBUG silently inert
`bot-api/src/main/java/com/vingame/bot/common/logging/ScopedDebugRegistry.java:111, 131, 151, 182`

The javadoc asserts:

> "A stale `true` costs one wasted map lookup until the next sweep; a stale
> `false` cannot happen, because it is set to `true` before the entry is published."

A stale `false` can happen. `disable`, `sweepAt` and the lazy-expiry branch of
`isEnabled` all perform a non-atomic read-modify-write —
`expiries.remove(...)` then `anyEnabled = !expiries.isEmpty()` — and the write
is not ordered against a concurrent `enable`. Interleaving (sweeper thread A,
REST/escalator thread B):

```
A: expiries.remove("g1")            // map now empty
A: reads expiries.isEmpty() -> true // about to assign false
B: anyEnabled = true
B: expiries.merge("g2", ...)        // map now holds g2
A: anyEnabled = false               // <-- lost update
```

The map holds `g2` but the fast path reads `false`, so `ScopedDebugFilter.decide`
returns `NEUTRAL` for every event and **nothing is ever promoted for g2 for its
entire TTL**. Nothing self-heals it: `isEnabled` short-circuits on `anyEnabled`,
the 30 s sweep re-derives `!isEmpty()` only if it finds an *expired* entry (it
returns early at `expiries.isEmpty()` — which is false here, so it does reassign
`true`... but only if some entry is expired; a single non-expired entry leaves
the flag untouched). Meanwhile `GET /api/v1/logging/debug` reports the scope as
active, because `activeScopes()` reads the map, not the flag.

This window is small but it is opened by exactly the traffic pattern the feature
is designed for: the sweeper firing every 30 s while an operator or the
auto-escalator arms a scope during an incident. The symptom — "I enabled it, the
API says it's on, no DEBUG lines appear" — is unfalsifiable from the outside and
lands mid-incident.

Fix shape: serialize the flag against the map. Either compute the flag under a
short `synchronized` block in all four mutation sites, or drop the boolean and
have `isAnyEnabled()` read a `volatile int` size maintained atomically alongside
the map, or re-assert `anyEnabled = true` after `merge` **and** re-check after
each removal (`if (!expiries.isEmpty()) anyEnabled = true;` rather than an
unconditional assignment). The last is the smallest change and closes the
lost-update in the direction that is safe (a stale `true` is already documented
as harmless).

### [bug] The log4j2 property-change listener is never removed, and `isInstalled()` does not check attachment
`bot-app/src/main/java/com/vingame/bot/infrastructure/logging/ScopedDebugInstaller.java:104-159`

Two coupled defects on the install path.

**(a) The listener outlives the bean.** `install()` registers a listener on the
**JVM-global** `LoggerContext` (`LogManager.getContext(false)`), and `stop()`
removes the filter and shuts the sweeper down but never calls
`removePropertyChangeListener`. The lambda captures `this`, which captures the
filter and the registry. Worse, `stop()` sets `installed = false`, so if the
stale listener ever fires, `install()` takes the `if (!installed)` branch again
and **registers a second listener** — listeners multiply across
create/destroy cycles.

Consequences, in ascending order of how much they matter:

- Every stale installer's `install()` starts with `removeExistingFilters(...)`,
  which removes *any* `ScopedDebugFilter` — including the live one belonging to
  the current Spring context — and attaches its own, which is bound to a
  registry that `stop()` already `clear()`ed. The live context then has
  `installed == true`, `isAttached() == false`, and a filter that will never
  promote anything. `POST /api/v1/logging/debug/{id}` answers 200 and changes
  nothing, which is the failure the class javadoc names as the reason the
  "armed" INFO line exists.
- In the test suite (Surefire reuses the fork, and several new tests spin
  Spring contexts with different `logging.level.*` / `logging.config`
  properties), this is a cross-context interference source and a plausible
  future flake.
- In production a single context makes it latent — no `monitorInterval` is set
  in either `log4j2.properties`, so nothing reconfigures spontaneously. It
  becomes live the moment anything triggers a `reconfigure()` (a `logging.config`
  change, an actuator-driven restart, devtools).

**(b) `isInstalled()` returns a flag, not a fact.** It returns the `installed`
boolean, which is only ever set by a successful `install()` and cleared by
`stop()`. `isAttached()` — which asks the real question — exists and is used
only by the listener. `ScopedDebugStatusDTO.enabled` documents itself as
"master switch AND the filter actually being attached", which is not what it
carries. The `installing` CAS guard compounds this: a genuine concurrent
re-install request is *silently dropped* (returns without retrying), and there
is no periodic re-check, so a lost re-install is permanent detachment that
`/api/v1/logging/debug` reports as healthy.

Fix shape: keep a field for the registered listener and remove it in `stop()`;
make `isInstalled()` return `installed && isAttached()`; and have the existing
30 s sweeper re-assert attachment (`if (!isAttached()) install();`) so a dropped
re-install self-heals within one sweep instead of never.

### [bug] `evidence-shim`: an exception in `next_deadline()` silently kills the scheduler thread
`evidence-shim/shim.py:745-754`

```python
while not stop.is_set():
    try:
        promoter.tick()
    except Exception as error:  # noqa: BLE001 - a bad pass must not kill the timer
        log("ERROR scheduled pass failed: %s: %s" % (type(error).__name__, error))
    timeout = max(1.0, min(30.0, promoter.next_deadline() - time.time()))
    promoter.wake.wait(timeout)
```

`tick()` is guarded; `next_deadline()` — one line later, outside the `try` — is
not. It calls `min()` over the values pulled straight out of `self.pending`, and
`load_pending()` accepts any dict that merely has a truthy `liveName`:

```python
self.pending = {key: entry for key, entry in loaded.items()
                if isinstance(entry, dict) and entry.get("liveName")}
```

A `.pending.json` whose `deferredAt`/`tailAt` is anything other than a number —
a hand-edit on the box, a schema change between shim versions, a file written by
a future version and read by a rolled-back one — makes `min()` raise
`TypeError: '<' not supported between 'str' and 'float'`. The scheduler is a
`daemon=True` thread with no supervision, so it dies, the traceback goes to
stderr among the container logs, and the process **keeps serving HTTP and keeps
returning 200**. From then on:

- pass 2 (+5 min) and pass 3 (rollover + 120 s) never run for any incident — the
  post-incident tail, which is the whole point of AD-16, is never pinned;
- the hourly sweep never runs, so `logs/evidence/` grows without bound, which is
  precisely the "the fix for unbounded growth is itself unbounded growth" that
  AD-19 exists to prevent, on the box that died of a full disk;
- `/health` cannot report it — it shows `pending` entries with
  `deferredInSeconds` drifting ever more negative, and the healthcheck still
  passes.

The same class of gap: `tick()` itself compares `now >= entry["deferredAt"]`, so
a bad entry also poisons every subsequent tick even when the exception *is*
caught, because the loop aborts on the first bad key each time.

Fix shape: move `next_deadline()` inside the `try` (with a sane fallback
timeout), coerce and validate deadlines to `float` in `load_pending()` and drop
entries that fail, and — cheapest of all — have `serve()`/`/health` report
`scheduler_alive = thread.is_alive()` so a dead timer is visible instead of
inferred.

### [smell] `GroupLifecycleAggregator.flushAll()` can flush nothing on shutdown
`bot-engine/src/main/java/com/vingame/bot/infrastructure/observability/GroupLifecycleAggregator.java:269-272`

`flushAll()` fakes "everything is idle" by passing `Long.MAX_VALUE / 2` as
`nowNanos`, and `drainIdle` then computes `nowNanos - pending.lastTouchNanos`.
`System.nanoTime()`'s origin is unspecified and may be an arbitrary (including
large negative) value; the JDK javadoc is explicit that only *differences between
two nanoTime readings* are meaningful. With a negative origin, e.g.
`lastTouchNanos = -9e18`, the subtraction overflows to a negative number, the
`< IDLE_FLUSH_NANOS` guard passes, and every pending entry is **skipped** — the
shutdown flush silently emits nothing, which is the exact opposite of the stated
intent ("a group that came up seconds before a stop still deserves its one
line"). Linux/macOS `CLOCK_MONOTONIC` makes this unlikely in practice, which is
why it is a smell rather than a bug. Fix shape: give `drainIdle` an explicit
`force` flag instead of a sentinel timestamp.

### [smell] The deposit aggregate has an idle deadline but no maximum age
`GroupLifecycleAggregator.java:210-224, 248-266`

`recordAutoDeposit` `touch()`es the entry on every contribution and the entry is
only emitted once it has been quiet for `IDLE_FLUSH_NANOS` (5 s). A large group
whose bots trickle below `minBalance` more often than every 5 s — which is the
normal shape for a group being drained by an unlucky run — never goes quiet, so
the INFO summary line is deferred indefinitely and `amount` accumulates across
what an operator would read as several separate deposit rounds. The
`initializations` counter is immune because `expectInitialized` gives it a
completion condition; the deposits counter has none by design ("a deposit round
is inherently open-ended"), which is exactly why it needs a hard ceiling. Fix
shape: emit when `now - firstTouch > MAX_WINDOW` (say 60 s) regardless of
idleness, the same belt-and-braces `SessionAggregationService` applies with its
TTL sweep behind the grace clock.

### [smell] Group-level aggregate lines are tagged with one arbitrary bot's MDC
`GroupLifecycleAggregator.java:188-190, 217-219, 292-309`

`mdcSnapshot = MDC.getCopyOfContextMap()` captures the *whole* context map of
the first contributing bot, which per `BotMdc.set` includes `botId` and
`botUserName` alongside the group/env/product keys the javadoc names. The
emitted line is a group-scoped fact ("47/47 bots initialized") permanently
attributed to bot `0`. In Loki, `{botGroupId="x"} |= "bots initialized"` is
right, but a drill-in filtered on a specific `botId` will surface a line about
all 47 bots, and the JSON document carries a `botUserName` that had nothing to
do with 46 of them. Fix shape: snapshot only the group-level keys
(`botGroupId`, `environmentId`, `product`, `gameType`) rather than the whole
map — the same set `BotMdc.setGroupContext` writes, which is what
`FleetRollupLogger` already does correctly.

### [smell] The slot below-spin-cost transition can still flip on every tick
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/SlotMachineBot.java:318-334`

The `belowSpinCost` state is defined against `chooseBet() * numLines`, and
`chooseBet()` is re-derived from the strategy on **every** tick. With a
randomising strategy over `allowedBetValues` (e.g. `RandomBetStrategy`) and a
balance sitting between `min(allowedBetValues) * numLines` and
`max(allowedBetValues) * numLines`, consecutive ticks alternate above/below the
gate, and the CAS pair emits a "pausing spins" **and** a "resuming spins" line
per oscillation — up to 2 lines per 3 s, i.e. *worse* than the ~1,200 lines/hour
the change was written to eliminate. The state being tracked is "below the cost
of the bet I happened to roll this tick", not "below the cost of a spin". Fix
shape: define the gate on `min(allowedBetValues) * numLines` (the bot genuinely
cannot spin at all below that) and keep `chosenBet` only for the message text,
or add a hysteresis/minimum-dwell before a transition may be logged again.

### [smell] `Promoter.pending` is the one unbounded collection in the feature
`evidence-shim/shim.py:276, 423-454`

Every Java map added by this branch carries an explicit cap and a documented
anti-leak story (`ScopedDebugRegistry.DEFAULT_MAX_SCOPES = 50`,
`GroupLifecycleAggregator.MAX_GROUPS = 2000`,
`ScopedDebugEscalator.MAX_TRACKED_GROUPS = 2000`). `self.pending` has none. Each
distinct `groupLabels` tuple creates an entry that lives until both its passes
fire, is re-serialised into `.pending.json` on every webhook (O(n) write per
POST), and mints a distinct `console-live-<slug>-<ts>.log` hardlink in
`evidence/`. Alertmanager's grouping keeps this tiny in normal operation and the
container publishes no host port, so this is not reachable from outside the
compose network — but a label explosion during a bad deploy (say `gameId` leaking
into `group_by`) is a plausible in-network way to get thousands of pending
entries and thousands of evidence links, and the sweep bounds the bytes, not the
inodes or the dict. Fix shape: cap `pending` at a small N (oldest-first
eviction, mirroring `enforceCap`) and log once when the cap bites.

### [smell] The evidence age guard measures log age, not incident age
`evidence-shim/shim.py:578-616`

`sweep()` compares `stat.st_mtime` against `now - max_age_days * 86400`. A
hardlink shares the inode, so a promoted file's mtime is the *log's* last-write
time, not the promotion time. log4j2's own `Delete` keeps rolled files for 7
days, so the older of the "newest two" can already be up to ~7 days old at
promotion and is swept ~7 days later — the effective evidence retention is
`EVIDENCE_MAX_AGE_DAYS - age_at_promotion`, not the 14 days that
`secrets.env.example` and the module docstring advertise. (The live file is
unaffected: its mtime keeps advancing while log4j2 writes to that inode.) Fix
shape: either record the promotion time in a sidecar/state file and sweep on
that, or state the semantics honestly in the docstring and in
`secrets.env.example` — "at least 7 days" is a very different promise from
"14 days".

### [smell] `FleetRollupLogger` writes a null MDC value
`bot-app/src/main/java/com/vingame/bot/infrastructure/observability/FleetRollupLogger.java:164`

`BotMdc.setGroupContext(null, envId, product)` unconditionally does
`MDC.put(BOT_GROUP_ID, null)` (the null-guards in `setGroupContext` cover only
`product`). Log4j2's context map tolerates it, but the environment rollup line
then carries an explicit `botGroupId: null` field in the JSON document and an
empty `[/…]` in the console pattern, where "the key is absent" is what is meant.
It also means the value depends on which `ThreadContextMap` implementation is
active. Fix shape: have `setGroupContext` skip a null `botGroupId` the way it
already skips a null `product`, or add a group-less overload.

### [smell] The twin-sync test covers two of the three log4j2 copies; the third is guarded by prose
`bot-app/src/test/java/com/vingame/bot/infrastructure/logging/Log4j2TwinConfigTest.java`,
`bot-app/src/test/resources/log4j2-test.properties:26-31`

`Log4j2TwinConfigTest` is genuinely good — exact line-by-line body comparison
plus two anti-vacuity assertions, so it is real insurance and not reassurance,
*for the two files it compares*. The third copy is a different matter. Its
header says:

> "KEEP THE LOGGER SHAPE IDENTICAL to the shipped files: same logger name, same
> additivity, same two appender NAMES … If the shape here drifts from
> production, that test starts proving nothing."

Nothing enforces that. `LoggingLevelOverrideTest` asserts the shape holds *in the
test config*, which is the same file — so a drifting edit to
`log4j2-test.properties` would be self-consistently green and would quietly
invalidate AD-7's verification. This is the standing drift hazard the plan calls
out, half-closed. Fix shape: extend `Log4j2TwinConfigTest` with a third
assertion that the `logger.app.*` block and the two appender *names* in
`log4j2-test.properties` match the shipped body, while explicitly permitting the
`fileName`/`filePattern`/`bufferSize`/`strategy.max` differences and the absent
`ThresholdFilter`. That turns two documented intentional differences into an
allow-list and everything else into a build failure.

### [smell] `LogLevelController` accepts any group id, so typos consume scope slots
`bot-app/src/main/java/com/vingame/bot/domain/logging/controller/LogLevelController.java:76-105`

`botGroupId` is passed straight to `registry.enable` with no existence check
against `BotGroupService`. A mistyped or stale id returns 200 with an expiry up
to 2 hours out and occupies one of the 50 scope slots for its full TTL — and
when the cap is full, `ScopedDebugEscalator.escalate` refuses and only WARNs, so
junk entries can starve real auto-escalation. The endpoint is unauthenticated,
consistent with the documented posture for `/api/v1/metrics/**` and
`/api/v1/alerts/**` and correctly deferred to the Keycloak item — but unlike
those two, this one accumulates server-side state from unvalidated input, which
is worth a line of validation now rather than after the auth work. Fix shape:
404 on an unknown group id (`ResourceNotFoundException` is already imported for
the DELETE path), or at minimum reject ids that do not look like the UUIDs the
rest of the API uses.

### [style] Fully-qualified `java.util.List` / `java.util.ArrayList` in a file that imports its collections
`bot-engine/src/main/java/com/vingame/bot/infrastructure/observability/SessionAggregationService.java:501-518`

`public java.util.List<GroupRollup> drainRollup()` and
`new java.util.ArrayList<>(...)` in a file whose header already imports `Map`,
`ConcurrentHashMap`, `LongAdder` and friends. Inconsistent with the file and with
every other new class on the branch. Add the imports.

### [style] `ReconnectWindow.startMillis` is read outside its own synchronization
`ScopedDebugEscalator.java:207, 220-224, 227-247`

`ReconnectWindow` synchronizes `record()` and `reset()`, then `timestampOf()`
reads `window.startMillis` from the cap-enforcement path on another thread with
no synchronization and no `volatile`. It is only used to order entries for
eviction, so a stale read costs nothing, but it is an inconsistency inside a
class that otherwise takes the lock. Either mark the field `volatile` or add a
synchronized accessor.

## Notes

- **The AD deviations are the right calls and are documented where a reader will
  hit them.** The `Configuration`-vs-`LoggerConfig` filter attach point is the
  standout: `Logger.PrivateConfig.filter` really does read `config.getFilter()`
  before the level gate, and a LoggerConfig-attached filter really would have
  installed cleanly, reported healthy and done nothing. Catching that during
  implementation rather than in production, and then reconstructing the lost
  blast-radius limit with `SCOPED_LOGGER_PREFIX`, is the kind of correction that
  is worth more than the feature. Same for `blocking = true` — the AD-3 snippet
  would have silently discarded ERROR events on a full queue.
- **Overriding every `Filter` overload in `ScopedDebugFilter` is correct and
  non-obvious.** `AbstractFilter` defaults each shape to `NEUTRAL`
  independently, so a partial override is an inert filter that passes any test
  that happens to use a covered arity. The comment saying so is exactly the
  comment that class needed.
- **Teardown coverage checks out.** `evictGroup` is called on all three paths
  that remove a group from `runningGroups` (failed start at :539, `stop` at
  :885, cascade delete at :950), and `restart` routes through `stop`, so there
  is no path that drops a runtime while leaving aggregator or escalator state
  behind. `handleBotGroupDeath` deliberately does not evict, which is right —
  the group stays in `runningGroups` and `monitorHealth` guards on
  `isGroupDead()`. The one residue is that a group armed just before death keeps
  its scope until TTL, which is desirable.
- **`evidence-shim/selftest.py` runs green here** (`python3 selftest.py`, all
  checks passed) and the `EvidenceShimSelfTestRunnerTest` wrapper correctly
  mirrors the `viptalk-shim` precedent — including the "must contain `all checks
  passed`" anti-vacuity assertion, which is what stops a suite that exits early
  from looking like a pass. Structure, config posture (`_number` falling back
  loudly rather than crash-looping under `restart: unless-stopped`), failure
  posture (502 so Alertmanager's retry stays meaningful) and logging all follow
  `viptalk-shim`. The divergences worth naming are the two above (unbounded
  `pending`, unguarded `next_deadline`), not the shape.
- **The Alertmanager routing is right, and it is right for the non-obvious
  reason.** Every `evidence` route carries `continue: true` and has a trailing
  `viptalk` sibling, so the promotion receiver cannot consume the alert it is
  meant to accompany — the same routing-semantics bug VIPTALK_ALERTING_V2 AD-V9
  hit, avoided one phase later and pinned by `AlertmanagerRoutingTest`.
- **Question for the author (not a finding):** `loki-config.yaml` raises the
  default `retention_period` from 168 h to 720 h at the same time as the volume
  reduction lands. The 30-day INFO retention is only affordable if Phase 1's
  demotions deliver the projected reduction — and this is the same volume that
  ENOSPC'd the box on 2026-06-30. Is there a verification step that measures
  actual INFO ingest for a day at the new default *before* the 720 h horizon can
  accumulate, or should the retention raise be staged behind that measurement?
  Note also that any promtail stream without a `level` label (nothing today, but
  a second scrape job would do it) inherits 720 h silently.
