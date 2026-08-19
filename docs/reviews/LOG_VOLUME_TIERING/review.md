# Code Review — LOG_VOLUME_TIERING (re-review after remediation)

Branch: `feature/log-volume-tiering`
Reviewed diff: `git diff staging..feature/log-volume-tiering` (34 commits)
Remediation delta re-reviewed: `git diff 562488d..HEAD` (11 commits, 31 files)

## Verdict

PASS

All four `bug` findings from the first pass are genuinely fixed, and the fixes are
better than the fix shapes I proposed in three of the four cases. No `security`
findings. Build green (`mvn -o -DskipTests install`), and the affected suites run
green here: `ScopedDebugRegistryTest` (14), `ScopedDebugEscalatorTest` /
`GroupLifecycleAggregatorTest` / `SlotMachineBot*Test` (45), the `bot-app` logging
suite incl. `ScopedDebugFilterInstallationTest` + `PerBotInfoLogGuardTest` (26),
and `evidence-shim/selftest.py` (all checks passed).

Six `smell`s and one `style` remain, all of them consequences of the remediation
rather than survivors of the first pass. The heaviest is the third one in the
installer — the area you flagged as likely to produce another, and it did, in the
place you'd expect: `stop()` still does not order against the very sweeper
re-assert that was added to fix bug 3.

### Prior findings, disposition

| Prior finding | Status |
|---|---|
| [bug] escalation re-arms forever | **Closed** — cooldown measured from expiry, defaults 15/45, duty cycle asserted |
| [bug] `anyEnabled` lost update | **Closed** — five mutation sites under one monitor, reads still lock-free |
| [bug] listener never removed / `isInstalled()` not a fact | **Closed** — plus the identity-scoped removal defect Dev found alongside it |
| [bug] shim `next_deadline()` kills the scheduler | **Closed** — guard widened, deadlines coerced at three layers, `schedulerAlive` published |
| [smell] `flushAll()` sentinel timestamp | Closed — explicit `force` flag |
| [smell] deposit aggregate has no max age | Closed — `MAX_WINDOW_NANOS = 60s` |
| [smell] group lines tagged with one bot's MDC | Closed — `BotMdc.GROUP_LEVEL_KEYS` |
| [smell] slot below-spin-cost flips per tick | Closed — state defined on `minimumSpinCost()` |
| [smell] `Promoter.pending` unbounded | Closed — `MAX_PENDING = 64`, oldest-first, counted on `/health` |
| [smell] evidence age measures log age | Closed — `.promoted.json` sidecar |
| [smell] `FleetRollupLogger` writes a null MDC value | Closed — `setGroupContext` skips nulls per key |
| [smell] third log4j2 copy guarded by prose | **Withdrawn** — see Disputes |
| [smell] `LogLevelController` accepts any group id | **Deferral accepted, with one correction** — see Disputes |
| [style] FQN `java.util.List` in `SessionAggregationService` | Closed |
| [style] `ReconnectWindow.startMillis` off-lock | Closed — `volatile`, with the reason on the field |

## Findings

### [smell] `ScopedDebugInstaller.stop()` still does not order against the sweeper it starts, so the new re-assert can re-attach the filter *after* teardown
`bot-app/src/main/java/com/vingame/bot/infrastructure/logging/ScopedDebugInstaller.java:120-136, 211-229`

This is the third defect in this area, and it is created by the fix for the
second. `stop()` runs, in this order:

```java
if (sweeper != null) sweeper.shutdownNow();       // 1 — does NOT await termination
if (listener != null) context().removePropertyChangeListener(listener);   // 2
detachOwnFilter(context().getConfiguration());    // 3
registry.clear();                                 // 4
installed = false;                                // 5  <-- last
```

`shutdownNow()` interrupts but does not wait, and `sweepQuietly()` blocks on
nothing interruptible, so an in-flight sweep runs to completion concurrently with
steps 2–5. That sweep's first act is now the re-assert added for bug 3:

```java
if (installed && !isAttached()) { log.warn(...); install(); }
```

Interleave it between steps 3 and 5 and it reads `installed == true` (not cleared
until step 5) and `isAttached() == false` (just detached at step 3), so it calls
`install()` and **re-attaches the filter to the JVM-global `Configuration` after
`stop()` has removed it** — bound to a registry step 4 already `clear()`ed. The
new identity-scoped `detachOwnFilter` means the next live installer will not
remove it (correctly — it only removes its own), so it stays there for the life of
the JVM, pinning the dead installer, its filter and its registry. That is exactly
the lingering-filter outcome the `stop()` comment says it exists to make
impossible.

The narrower sub-case is worse in kind, if not in probability: if the straggler
runs *after* step 5, `install()` takes the `if (!installed)` branch and registers
a **second** `PropertyChangeListener` on the global context that nothing will ever
remove — the multiplying-listener shape the fix was written to close, reopened
through the same ordering hole. The same window exists for a listener already
executing when step 2 removes it.

Practically the cost is small: the orphaned filter always returns `NEUTRAL`
(cleared registry ⇒ `isAnyEnabled() == false`), so nothing misbehaves; the damage
is one leaked object graph per hit and a per-event volatile read per orphan. In
production there is one context and one shutdown, so this is near-unreachable; in
the test JVM, where contexts churn, it is the plausible one.

Fix shape, in order of value: (a) set `installed = false` **first** in `stop()`,
before removing the listener and detaching — that alone makes the sweeper's gate
fail; (b) add a terminal `stopped` flag that `install()` checks inside the
`installing` guard, so no post-teardown path can re-attach or re-register;
(c) `sweeper.shutdownNow()` followed by a short `awaitTermination`. (a) and (b)
are two lines and close it; (c) is belt-and-braces.

Worth stating as an invariant somewhere the next reader will hit it: *after
`stop()` returns, no code path may call `install()` on this instance.* Both bugs
in this class so far have been violations of an invariant that was described in
prose and enforced nowhere.

### [smell] A cap-refused escalation still burns the cooldown slot — and the fix quadrupled the penalty
`bot-engine/src/main/java/com/vingame/bot/infrastructure/observability/ScopedDebugEscalator.java:204-222`

`escalate()` claims the cooldown slot *before* it knows whether the registry will
grant a scope:

```java
lastEscalation.compute(botGroupId, (id, previous) -> { ... won.set(true); return now; });
if (!won.get()) return false;
enforceCap(lastEscalation);
Optional<Instant> expiry = registry.enable(botGroupId, ttl);
if (expiry.isEmpty()) { log.warn("...the registry is at its concurrent-scope cap of {}"); return false; }
```

On the refusal path `lastEscalation` keeps `now`, so a group that was refused
because `maxScopes` was momentarily full is suppressed for the full re-arm
interval — which this pass raised from 15 minutes to **60**. During the
fleet-wide incident that filled the cap (the scenario the cap exists for), slots
free up as the first wave's TTLs lapse, but the groups that were turned away in
the first minute cannot try again for an hour. The escalator ends up systematically
favouring whichever groups happened to arrive first.

Two smaller things on the same lines: `enforceCap` runs after a slot is claimed but
before the grant, so a refused escalation can also evict a real entry; and the
`log.warn` hard-codes "the registry is at its concurrent-scope cap of {}" as the
only explanation for `Optional.empty()`, which is wrong for the other two refusal
reasons — a blank group id, and a non-positive TTL from
`escalation.minutes<=0`. Someone who zeroes that property to disable escalation
(rather than using `escalation.enabled=false`) gets a WARN per trigger blaming the
cap.

Fix shape: roll the claim back on refusal (`lastEscalation.remove(botGroupId, now)`
when `expiry.isEmpty()`), and have the WARN report `registry.activeScopes().size()`
vs `getMaxScopes()` so the message can be falsified.

### [smell] `evictGroup` resets the quiet period, so a restart cycle can slip the 25% duty-cycle bound
`ScopedDebugEscalator.java:171-178`, `BotGroupBehaviorService` teardown paths

The new bound — "an unattended group cannot hold scoped DEBUG for more than
`ttl/(ttl+cooldown)` of any window" — rests entirely on `lastEscalation` being
sticky. `evictGroup` clears it, and it is called on every path that drops a group
from `runningGroups`, including `stop`, which `restart` routes through. A group
that is being restarted more often than once an hour (a scheduled restart, an
`ActivationScheduler` window flapping, a DEAD-runtime reclaim loop) therefore gets
its quiet period zeroed on each cycle and can re-escalate on the first watchdog
expiry after each start. Note the registry scope is *not* cleared by `evictGroup`,
so consecutive escalations merge-extend rather than replace: a group restarting
every ten minutes can hold a scope continuously.

The class javadoc states the bound unconditionally ("whatever it flaps"), and
`unattendedGroupCannotExceedItsDutyCycle` proves it only for a group that is never
evicted. This is not a large hole — restart cadences are usually much longer than
an hour — but it is the one input that makes the stated invariant false, and it is
untested.

Fix shape: either keep `lastEscalation` across eviction (it is a bounded map with
its own 2,000-entry cap, so retaining it costs nothing and the group id is stable
across restarts), or state in the javadoc that the bound holds per continuous run
and add the eviction case to the duty-cycle test.

### [smell] The evidence sidecar prune is racy against a concurrent promotion
`evidence-shim/shim.py:789-797` (`forget_promoted`), `:737-786` (`sweep`)

`sweep()` computes `keep` from an `evidence_files()` snapshot and then does:

```python
def forget_promoted(self, keep):
    surviving = {os.path.basename(path) for _, _, path in keep}
    stale = [name for name in self.promoted_at if name not in surviving]
```

`promote()` — and therefore `sweep()` — runs on both the webhook thread and the
scheduler thread, and nothing serialises them. If thread B hardlinks a new file
and stamps it after thread A took its `evidence_files()` snapshot, that name is
absent from A's `surviving` set and A deletes B's just-written record. The file
survives, but its age reverts to the mtime fallback — which is precisely the
"effective retention is `MAX_AGE - age_at_promotion`" behaviour the sidecar was
added to eliminate, silently and only for the file that raced. Alertmanager
redelivery during an incident (webhook) overlapping a deferred pass (scheduler) is
the ordinary way to produce that overlap.

Fix shape: prune on absence from the filesystem rather than absence from a stale
snapshot — `stale = [name for name in self.promoted_at if not os.path.exists(os.path.join(evidence_dir, name))]`.
That is correct under any interleaving and is the same cost.

### [smell] `/health` reports `schedulerAlive` but the only thing that reads `/health` cannot see it
`evidence-shim/shim.py:893-897`, `docker-compose.yml:306-312`

The stated purpose of the new field is "so a dead timer is visible not inferred"
(selftest name), and it is honest about the state. But `do_GET` answers **200
unconditionally**, and the sole automated consumer is the compose healthcheck,
which does `urlopen(...).read()` and inspects nothing:

```yaml
test: ["CMD", "python", "-c",
       "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8080/health',timeout=3).read()"]
```

So a dead scheduler still shows a healthy container under `restart: unless-stopped`,
and the field is only visible to someone who already suspected the problem and
curl'd the container by hand — which is the state the fix set out to leave behind.
(The paths that kill the thread are now closed, so this is defence in depth, not a
live gap.)

`viptalk-shim` is the precedent for "200 + honest body", and following it is
defensible — but that shim's `/health` describes a *remote* dependency it
deliberately does not probe, whereas this one describes a thread inside the same
process, which it can answer definitively. Fix shape: `503` when
`scheduler_alive() is False` (`None` — no thread wired, the selftest harness case —
stays 200), which makes the existing healthcheck total for free.

### [smell] `PerBotInfoLogGuardTest` is itself the kind of manual enumeration its own javadoc says comes up short
`bot-app/src/test/java/com/vingame/bot/infrastructure/logging/PerBotInfoLogGuardTest.java:54-72, 104-108`

The test is well built where it counts — `assertThat(path).exists()` with a "this
has moved, update the guard rather than deleting it" message, and a real
anti-vacuity test — and it is currently complete: `grep -rn "log\.info(" bot-*/src/main/java/com/vingame/bot/domain/bot/` returns nothing. Three things
will erode it:

1. **`PER_BOT_CLASSES` is a hand-maintained list**, which the class javadoc itself
   identifies as the failure mode ("that invariant has now been enumerated twice …
   and both enumerations came up short"). The Up Down bot on the Q3 roadmap will
   land in `domain/bot/core/` outside the list and inherit no guard. A glob over
   `**/domain/bot/core/*.java` plus an explicit allow-list would be
   self-maintaining and would fail *closed* on a new bot class.
2. **The scan is `line.contains("log.info(")` over raw source**, so a comment is
   indistinguishable from a call. These are exactly the files now carrying long
   comments *about* the demotions ("This was INFO under BETTING_STRATEGIES AD-14…"),
   and the first one that quotes the old call verbatim fails the build for no
   defect. Stripping `//`-prefixed content, or requiring the match not to follow a
   `//`, costs one line.
3. **The bypass is invisible**: `log.atInfo().log(...)`, a differently-named logger
   field, or a `LoggerFactory.getLogger` local all pass. Worth one sentence in the
   javadoc so a future reader does not over-trust it.

`theDemotedSitesStayDemoted` matching on message fragments will also fail on an
ordinary reword — but it fails with "…vanished from…, this guard is now proving
nothing", which is the right posture for a pin, so that one is deliberate and
fine.

### [style] The POST contract now returns an expiry that may not be the one asked for, and nothing says so
`bot-app/src/main/java/com/vingame/bot/domain/logging/controller/LogLevelController.java:70-105`,
`bot-app/src/main/java/com/vingame/bot/domain/logging/dto/ScopedDebugDTO.java`

`registry.enable` is now extend-never-shorten and returns the window *in force*.
That is the right call and it does not break anything (see Notes), but the
`@Operation` description still reads "for `minutes` minutes" and neither the
description nor `ScopedDebugDTO` mentions that a shorter `minutes` on a group that
already has a longer window returns 200 with the **longer** expiry. An operator
who POSTs `minutes=5` to shorten a 2 h window gets a 200 and a two-hour
`expiresAt` with no explanation, and the way to actually shorten it (DELETE, then
POST) is not discoverable from the endpoint docs. One clause in the `description`
and one on the DTO's `expiresAt` param.

## Disputes

**The escalator's cooldown maps — no dispute, and nothing to withdraw.** Confirmed:
`MAX_TRACKED_GROUPS = 2_000` at `ScopedDebugEscalator.java:64`, enforced by
`enforceCap` on both maps. My review never claimed otherwise — the only escalator
findings I filed were the re-arm bug and the `startMillis` style item, and my
`Promoter.pending` smell explicitly cited `ScopedDebugEscalator.MAX_TRACKED_GROUPS
= 2000` as one of the three Java precedents that made `pending`'s absence stand
out. The `volatile` fix landed and is correct, and the comment on the field
records *why* it is volatile, which is the part that survives.

**The third log4j2 copy — dispute withdrawn, Dev is right.**
`Log4j2TestConfigShapeTest` closes it, and closes it better than the fix shape I
proposed. I suggested comparing the `logger.app.*` block and the appender names;
it instead enumerates 23 `SHAPE_KEYS` covering the whole logger/appender graph
(including `appender.async.appenderRef.type`, the one the plan calls the most
likely silent failure), compares them with `containsExactlyEntriesOf`, and — the
part I would have missed — adds `everyShapeKeyIsActuallyDeclared`, because
`containsExactlyEntriesOf` is satisfied by two identically-*missing* keys, so a
typo in `SHAPE_KEYS` would have turned the class into a no-op. It also pins the
intended difference positively (`theOnlyDifferenceIsTheOutputPath`) rather than
merely excluding it. The one documented difference not in `SHAPE_KEYS` — the
shipped `appender.console.filter.threshold` (INFO) that the test config omits — is
correct to leave out: scoped DEBUG is promoted to the *rolling* appender that
feeds Loki, and the INFO-capped console is a deliberate Phase 0 decision, so no
dependent test's proof rests on it.

**The deferred group-id validation — deferral accepted, one of the three reasons
does not hold.**

- *"A Mongo existence check would 404 for a group just deleted or on another
  instance while its lines are still in flight."* **Holds** for the just-deleted
  case, which is real and is the case where you most want the scope to keep
  working. It does *not* hold for the other-instance case: the three instances run
  isolated data, and a group this instance does not run emits no lines carrying
  that MDC, so scoping it here consumes a slot and produces nothing — that half of
  the argument points toward validation, not away from it.
- *"A UUID gate would reject the short ids the registry contract uses."* **Does not
  hold.** `BotGroup.id` is a Mongo `@Id String` and in practice a UUID
  (`0c9a93cb-20d6-4f57-9dbc-5c315dcf52e2` in CLAUDE.md). The short ids are a test
  convention (`g1`, `group-under-investigation`); `ScopedDebugRegistry`'s actual
  contract is "non-null, non-blank", and nothing in production mints a short one.
  A format gate is possible; it is just not worth it on its own.
- *"The TTL reclaims junk scopes anyway."* **Holds** — `MAX_TTL` is 2 h and there
  is no permanent form.

Net ruling: **accept the deferral.** The residual is availability, not
correctness — an unauthenticated endpoint, 50 slots, a 2 h ceiling, so anything
inside the compose network can park all 50 on junk ids and starve both operator
enables and auto-escalation for two hours. That exposure is identical to the one I
already accepted as correctly folded into the Spring Security + Keycloak item, and
the first reason above is a genuine argument that a naive 404 would be worse than
the problem. One ask: record this reasoning on the Keycloak backlog entry rather
than leaving it only in a review thread — "authenticate this endpoint" and "stop
unvalidated input from accumulating server-side state" are the same fix, and the
second half is the part that gets forgotten.

## Notes

- **The `anyEnabled` fix is complete and the publication order is now strictly
  safer.** Five mutation sites, all under `mutation`: `enable` (:136-150),
  `disable` (:167-171), the lazy-expiry branch of `isEnabled` (:189-192), `sweepAt`
  (:217-226) and `clear` (:250-253). There is no sixth: `expiries` is `private
  final`, and the only other touches are read-only (`activeScopes`, and
  `sweepAt`'s pre-lock `isEmpty()` fast-out, whose worst case is a benign stale
  `true`). The lock-free read is still correct despite the order flipping from
  "flag then map" to "map then flag" — in fact more so: a reader that observes
  `anyEnabled == true` now happens-after the `merge`, so the documented "stale
  `true` costs one wasted lookup" is the only remaining race, and the stale `false`
  is gone by construction rather than by argument. The one new cost is that the
  lazy-expiry branch takes a monitor on the log hot path; it is bounded (only for a
  group whose scope has expired but not yet swept, and the first thread through
  removes the entry so the rest short-circuit on the `null` check), the critical
  section does no I/O and no logging, and nothing logs while holding `mutation`, so
  there is no lock-order inversion with log4j's internals. Fine as shipped, worth
  knowing it exists.
- **`enable()` returning the in-force expiry does not break either consumer.** The
  controller reports what it is told and the DTO is now accurate rather than
  aspirational. The escalator's arithmetic is untouched by it: the re-arm gate is
  `now - lastEscalation >= ttl + cooldown`, computed from the escalation timestamp
  and the configured `ttl`, never from the returned expiry — and since `merge` is
  max, the granted window is never *shorter* than `ttl`, so the gate is always at
  least as long as the scope it opened and the duty-cycle bound holds. The only
  visible effect is that the escalation INFO line can report an operator's longer
  expiry, which is the honest thing to print.
- **The duty-cycle arithmetic holds at the config extremes, and alternating trigger
  types cannot slip it.** All three triggers funnel through one `escalate()` keyed
  on `botGroupId` alone, so watchdog / reconnect-rate / dead-ratio share a single
  cooldown slot; there is no per-trigger key to alternate against. At the extremes:
  `cooldown-minutes=0` degenerates to the old 100% behaviour (which is now
  documented in `application.properties` as the thing not to do, in a comment that
  explains the failure rather than just forbidding it); `escalation.minutes<=0`
  fails closed, because `registry.enable` refuses a non-positive TTL — see the
  misleading WARN under the second smell. The 25% figure is asserted directly
  (`bound == 0.25`) rather than assumed, and the test drives four simulated hours
  on an injected clock, so it is deterministic.
- **No remaining path exits the shim's daemon thread.** Inside the loop only
  `tick()` and `next_deadline()` can raise, and both are now inside the guard;
  `stop.is_set()` and `wake.wait(timeout)` cannot, because `timeout` is provably a
  float in `[1.0, 30.0]` on every path (initialised to `30.0` before the `try`, so
  even a raising `next_deadline` leaves it valid). `_deadline` rejects `bool`
  before `float` — the `isinstance(True, int)` trap — and rejects NaN via
  `deadline == deadline`, which is the one that would otherwise poison `min()`
  silently rather than loudly. Defence is at three layers (`load_pending` drops,
  `next_deadline` re-coerces, `tick` degrades an unusable deadline to "due now"),
  and the selftest exercises all three including the smuggled-past-load case. The
  one theoretical hole left is `log()` raising inside the `except` block; that is
  stderr and not worth code.
- **On the 40k-round race probe: not flaky, and I would keep it — but bound it by
  wall clock, not by iteration count.** False-failure risk is close to zero, and
  for a structural reason rather than a lucky one: the assertion is an *invariant*
  ("the map holds a live scope, so the fast path must say so"), not a timing
  expectation, and after the fix it is true under every interleaving — including
  the one where the sweeper's pre-lock `isEmpty()` fast-out fires between the
  arming thread's internal `sweepAt` and its `merge`. The clock is simulated, both
  threads are joined every round, and only two threads are ever live, so there is
  no accumulation to trip a slow runner. The real cost is 80,000 platform-thread
  creations for ~3 s of build time, and the real weakness is the opposite of
  flakiness: the *detection power* is machine-calibrated ("40k is the count at
  which reverting the synchronization reproduces on this machine"), so on a
  single-vCPU CI runner the two threads may barely interleave and the probe can go
  quietly green against a reintroduced bug. That is a false-negative risk that
  grows silently. Consider `while (System.nanoTime() - start < 3_000_000_000L)`
  with a minimum round count — same budget, and the round count adapts to the box
  instead of being pinned to this one.
- **The slot fix is a no-op in the shipped configuration, which is the right kind
  of boring.** `SlotStrategyId.FIXED` is forced for every slot bot and
  `FixedBetStrategy.chooseBet` returns `allowedBetValues.get(0)`, so `cost ==
  floorCost` in production and the transition semantics are unchanged; the fix only
  bites for `RandomBetStrategy`, which is not selectable. One deliberate divergence
  worth knowing: `minimumSpinCost()` uses `Collections.min(values)` while the
  strategy uses `get(0)`. If the server ever stopped sorting ascending, the floor
  would sit *below* the bet actually chosen, and a bot between the two would
  silently stop spinning with no "pausing spins" line at all — the reverse of the
  oscillation this fixed. The javadoc explains the `min` choice; it does not
  mention that consequence. Also note `minimumSpinCost()`'s `numLines == 0` guard
  returns `Long.MAX_VALUE`, which would divide by zero in the ENTRY log line — it
  is unreachable because `spinCondition()` returns at line 326 first, but the two
  guards are now duplicated in a way that only stays safe by accident.
- **`GroupLifecycleAggregator`'s three fixes landed cleanly and the injected
  `nanoClock` is the non-obvious part.** Routing the *feed sites* through the same
  clock as `sweepOnce(long)` is what makes the max-window test mean anything — with
  `System.nanoTime()` at the feed sites, a test that advances the sweep clock also
  silently advances the idle deadline it is trying to hold open, and would pass for
  the wrong reason. The `@Autowired` no-arg constructor beside the test seam is the
  same shape `ScopedDebugEscalator` uses and is required by `SpringBeanConstructorTest`.
- **`BotMdc.GROUP_LEVEL_KEYS` is the right home for that list**, and having
  `GroupLifecycleAggregator` and `FleetRollupLogger` agree on one definition of
  "group-level" closes the drift between them rather than fixing one site. The
  per-key null skip in `setGroupContext` is a behaviour change beyond the reported
  finding (`environmentId` is now skipped when null too) — that is correct and
  matches the `product` precedent, but it is worth knowing it touched every caller
  of the 3-arg form, not just the fleet rollup.
