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

---

# Code Review — LOG_VOLUME_TIERING Phase 4

Branch: `feature/log-volume-tiering`
Reviewed diff: `git diff 0014a4a..68cc9dd` (4 commits: `a268929` 4a, `e97dcb2` 4b,
`118a10b` 4c, `68cc9dd` docs)

Phases 0–3 were passed in the section above and are not re-litigated. This section
covers only the two-track split, the promtail positions volume, and the shim's
two-track logic.

## Verdict

CHANGES_REQUESTED

Two `bug` findings, both in `evidence-shim/shim.py`'s `sweep()`, both reproduced
here against the shipped code. One `security` finding, which is a false claim in
AD-30 rather than a new exposure. The rest are advisory.

The routing graph itself — the part I was asked to weigh first — **is correct**. I
tried to break it four ways (see Notes) and could not. The failures in this feature
are not in the appender graph; they are in the byte accounting of the process that
is supposed to preserve what the graph produces.

Verified green here: `bot-app` logging suite (39 tests, `AsyncQueuePolicyTest`,
`Log4j2TwinConfigTest`, `Log4j2TestConfigShapeTest`, `EvidenceRetentionEscapeTest`,
`LogRetentionPipelineTest`, `LoggingComposeWiringTest`, `LoggingLevelOverrideTest`,
`EvidenceShimSelfTestRunnerTest`) and `evidence-shim/selftest.py`.

## Findings

### [bug] The evidence byte guard counts one inode once per name, so every incident inflates its own footprint by a whole detail file — and "freeing" a duplicate name frees nothing

`evidence-shim/shim.py:869` (`evidence_files`), `evidence-shim/shim.py:951`
(`total = sum(size for entries in keep.values() ...)`)

`evidence_files()` returns one `(mtime, size, path)` tuple per *directory entry* and
`sweep()` sums `st_size` across them. Evidence entries are hardlinks, and AD-16
guarantees that **at least one inode per track carries two names on every incident**:
the module docstring says so itself — "pass 3 only ever adds a second name for it".
Pass 1 pins the live file as `detail-live-<slug>-<ts>.log`; pass 3, at the rollover
boundary + 120 s, pins the *same inode* under its now-rolled name
`detail-<date>.log`. `link()`'s numbered-sibling path can add a third.

Reproduced against the shipped code — one 1000-byte inode under two names in
`evidence/`:

```
B) one 1000-byte inode, two names -> guard counts: 2000 bytes
```

Runtime consequence at the scale the plan targets. At 20k bots a 2 h detail archive
is ~3.41 GB. One incident really pins three distinct detail inodes ≈ **10.2 GB** —
comfortably inside the 12 GB cap, which is exactly what AD-28 sized the raise for.
But at pass 3 the live inode has grown into a full archive and is counted under
*both* of its names, so the guard sees **~13.6 GB** and starts evicting. The plan
says only 30k bots overshoots the cap (`AD-28`, and the `EVIDENCE_MAX_BYTES` comment
in `docker-compose.yml`); with this defect, **20k overshoots too**, and the shim
sheds real forensic evidence to relieve pressure that does not exist.

It then compounds: the file the guard picks may itself be one of the duplicate
names, in which case `os.unlink` removes a directory entry, the inode survives under
its sibling name, **zero blocks are reclaimed** — and `sweep()` nevertheless
decrements `total` by the full `st_size` and reports it in `freedBytes`. The guard
believes it succeeded, the next hourly sweep finds the same pressure, and the second
pass evicts something that is not a duplicate. So under sustained pressure the
directory must lose roughly twice as many names as the cap requires before it
settles.

The `sweep()` docstring anticipates the *wrong* over-count — "a promoted file that is
still live is counted in full even though its blocks are shared with the original" —
which is the safe direction, because those blocks really are unreclaimable while
log4j2 holds them. Two evidence names for one evidence inode is a different thing and
is not the safe direction.

Fix shape: key the accounting on the inode, not the name. In `evidence_files()`
return `st_dev`/`st_ino` alongside; in `sweep()`, group entries by `(st_dev, st_ino)`,
count each inode's size **once**, and let the group's promotion time be the oldest of
its names. Eviction then unlinks *all* names of the chosen inode (which is what
actually frees the blocks) and decrements `total` once. `forget_promoted` needs no
change. Add a selftest with two names on one inode — the existing
`test_the_byte_guard_evicts_detail_before_aggregates` uses one file per track and
cannot see this.

### [bug] Within a single pass the eviction order falls through to *size ascending*, so the guard deletes the incident's live tail first — the opposite of what the plan, compose and CLAUDE.md all say it does

`evidence-shim/shim.py:948` (`keep[True].sort()`), with
`evidence-shim/shim.py:772` (`remember_promoted`)

`remember_promoted` computes `now = time.time()` **once per pass** and
`setdefault`s it onto every name linked in that pass. So all of a pass's files carry
an identical promotion stamp. `keep[detail]` holds `(promoted, size, path)` and is
sorted with a bare `.sort()`; on the identical first element the tuple comparison
falls through to `size`, ascending, and `pop(0)` takes the **smallest** file.

Among the three detail files a pass pins, the smallest is normally the live-file link
— it is mid-window, so a fraction of a full archive. That is the post-incident tail:
the single most valuable file in the set, and the only one that cannot be
reconstructed, because once log4j2 sweeps its rolled sibling at 12 h the bytes are
gone for good.

Reproduced against the shipped code — three detail files promoted in one pass
(500 B, 500 B, 200 B), cap 1100:

```
sweep removed 1 file(s) (200 bytes): ['detail-live-x.log']
survivors: ['detail-A.log', 'detail-B.log']
```

It evicted the newest, smallest, most valuable file, and freed 200 bytes where
evicting the oldest archive would have freed 500 — so it also does the *least*
useful work per unlink, guaranteeing more unlinks on the next pass.

Three separate places state the opposite behaviour: `docker-compose.yml` ("one
incident can still exceed this and partially evict **its own oldest** detail file"),
the plan's AD-28 ("then **oldest-promotion first** within each class"), and
`CLAUDE.md` (same wording). The code has no defined order for the equal-promotion
case at all; it inherited one from the tuple layout.

Fix shape: make the intra-class order explicit and content-aged. Sort on
`(promoted, mtime, path)` — for equal promotion stamps that orders by the log's own
last-write time, which puts the older archives before the live tail and matches every
document. Better still, combine with the inode-grouping fix above and sort groups by
`(oldest_promotion, oldest_mtime)`. Extend
`test_the_byte_guard_evicts_detail_before_aggregates` with a three-file, one-pass
case asserting the *live* link is the last detail file standing.

### [security] AD-30's "after Phase 4 it exists in exactly one place ... never in Loki, Grafana or `docker logs`" is false for up to 30 days after the deploy, and Phase 4b deliberately re-ingests more of it

`docs/plans/LOG_VOLUME_TIERING.md` AD-30; `promtail-config.yml:6`;
`docker-compose.yml` (`promtail-positions` volume); `CLAUDE.md` (evidence-shim
section, "the detail track is the only place it exists")

AD-30 correctly identifies that ws-parser writes `User <bot>: Agency token: <n>-<id>`
at INFO, and Phase 4 genuinely stops *new* token material reaching Loki. The claim
that it therefore exists in exactly one place is the part that is wrong, in two ways
that compound:

1. `loki/loki-config.yaml` keeps `retention_period: 720h`, and AD-29 explicitly
   changes nothing there. Every ws-parser INFO line ingested before this deploy —
   including the token material — stays queryable in Grafana for up to **30 days
   after** Phase 4 lands. Nothing in the plan, the config or CLAUDE.md says so; all
   three read as though the containment is effective on deploy.
2. Phase 4b makes it worse before it makes it better, knowingly and without noting
   this consequence. `promtail-config.yml`'s comment says "Expect ONE final
   re-ingest on the deploy that lands this: there are no saved positions yet." The
   files re-read from byte 0 on that deploy are the ~12 retained **pre-Phase-4**
   `console-*.log` archives, which are full of ws-parser INFO. So the deploy that
   introduces AD-30's containment simultaneously pushes up to 14 days of token
   material back into Loki with a **fresh** 720 h clock on it.

No new party gains access — same Loki, same Grafana viewers — which is why this is
mid-ranked rather than top. What makes it worth a finding is that a *stated security
property* is false: someone reading AD-30 or CLAUDE.md could reasonably widen Grafana
access, or skip a purge, on the strength of it.

Fix shape, cheapest first: (a) amend AD-30 and the CLAUDE.md bullet to state the
exposure explicitly with its end date ("no *new* token material reaches Loki; already
ingested lines persist under the 720 h horizon until <date>"); (b) sequence the 4b
positions volume so the one-time re-ingest is either accepted with that date pushed
out 30 days, or avoided by seeding `positions.yaml` at the current tail on first
start; (c) if the material is judged worth removing, Loki's compactor
`deletion_mode: filter-and-delete` plus a delete request against
`{job="bot-manager"} |= "Agency token"` is a one-shot job. Option (a) alone closes
the finding.

### [smell] Track 2 drops at every level under saturation and *nothing observes it* — 3 status lines, then one per 5 minutes, on stderr only

`logging/log4j2.properties:299` and
`bot-app/src/main/resources/log4j2.properties:285`
(`appender.asyncdetail.blocking = false`)

I was asked to assess `blocking = false` honestly. The reasoning behind the choice is
sound and I would not change it: `log4j2.discardThreshold` is JVM-wide, AD-4's policy
genuinely cannot express "discard track 2's INFO but not track 1's", and blocking a
virtual thread carrying a bot on a full 16k queue is worse than losing a forensic
line. The 16384 buffer is also right — 8.6 s of backlog at 1,893 lines/s, against a
consumer that only has to run `PatternLayout` and a buffered write (~470 KB/s), so it
covers any realistic GC pause or rollover stall. (Note the task brief said 8192; that
is track 1's. Track 2 is 16384.)

What is not right is the observability of the failure. The config comment and AD-25(4)
describe the drop as coming "with only a status-logger note", which is more generous
than what actually happens. `AsyncAppender.append` on the non-blocking path calls
`error(...)`, which goes to `DefaultErrorHandler.acquirePermit()`
(log4j-core, verified in the sources jar): `MAX_EXCEPTION_COUNT = 3`, then one
message per `EXCEPTION_INTERVAL_NANOS = 5 min`. So a sustained drop storm produces
**three lines, then one line every five minutes**, each of them:

- carrying no count of events lost, and no level breakdown;
- written to `System.err`, therefore into the docker `json-file` log capped at
  50m × 5 — **not** into `console.log`, **not** into Loki, **not** into the detail
  file it is reporting on;
- invisible to Alertmanager, which is metric-driven.

Concretely: during the overload incident that saturates the queue — the exact
incident the wire-level tier exists to explain — track 2 can be silently 80% lossy at
every level, including the ws-parser WARN/ERROR that would name the cause, and the
only record is a handful of unlabelled stderr lines rotating out of `docker logs`. An
engineer reading `detail.log` afterwards has no way to know they are reading a file
with holes in it. `blocking = false` is the right call; "best-effort" is only an
acceptable contract when you can tell whether the effort succeeded.

Fix shape (small, and the plumbing already exists — `BotMetrics`/Micrometer/Prometheus
are wired): `AsyncAppender.getQueueRemainingCapacity()` is public. Register a
`Gauge` per async wrapper at startup —
`log4j2_async_queue_remaining{appender="AsyncDetail"|"AsyncRolling"}` — resolved from
`LoggerContext.getConfiguration().getAppender(name)`. One `prometheus/alerts.yml`
rule at "remaining < 10% for 5 m" turns an invisible lossy tier into a page, and it
covers track 1's *blocking* pathology (bot threads parked) with the same gauge.

### [smell] `immediateFlush = false` is bounded in bytes, not in seconds — and the bound is an unpinned log4j2 default

`logging/log4j2.properties:239` / `bot-app/src/main/resources/log4j2.properties:225`

The mechanism is correct and I would keep it: the `AsyncAppender` dispatcher sets
`endOfBatch` when it drains the queue, `AbstractOutputStreamAppender.append` flushes
on `endOfBatch`, and rollover closes and flushes the file — so pass 3 always reads a
fully flushed archive, and steady-state currency is milliseconds as claimed.

The exposure is stated imprecisely in a way that matters. The comment says the risk is
"a partial trailing buffer lost on SIGKILL", which reads as "the last few
milliseconds". The real bound is **the last ≤ 8 KB**, from `RollingFileAppender`'s
default `bufferedIo = true` / `bufferSize = 8192`. At 250 B/line that is ~32 lines,
and how much *time* those 32 lines span is inversely proportional to how quiet the
JVM is:

| Rate | 8 KB of buffer ≈ |
|---|---|
| 20k bots, healthy (1,893 lines/s) | ~17 ms — genuinely nothing |
| staging, 155 bots (14.7 lines/s) | ~2.2 s |
| a JVM in a death spiral, bot threads blocked, log rate collapsing | **minutes** |

The third row is the one that matters, because an unclean kill is precisely what
AD-21's boot retro-promotion triggers on, and the 2026-06-30 precedent (native thread
exhaustion) is exactly a collapsing-rate death. The shim promotes a file whose final
8 KB — the quietest, most diagnostic part — never reached the disk. Nothing is
*wrong* here, but the trade is being made against a mis-stated bound.

Also: `appender.detail.bufferSize` is not declared and not in
`Log4j2TestConfigShapeTest.SHAPE_KEYS`, so the loss bound is a log4j2 default nobody
chose and no test pins. Fix shape: state the bound as "the last ≤ 8 KB, not the last
few ms" in the comment, declare `appender.detail.bufferSize = 8192` explicitly so it
is a decision, and add it to `SHAPE_KEYS`.

### [smell] Every Phase 4 guard asserts *text in a properties file*; nothing asserts the resulting routing at runtime, including for the one logger whose disappearance costs the whole feature

`bot-app/src/test/java/com/vingame/bot/infrastructure/logging/AsyncQueuePolicyTest.java`,
`.../Log4j2TestConfigShapeTest.java`, `.../EvidenceRetentionEscapeTest.java`

This answers the drift question directly, and the news is mostly good.
`Log4j2TwinConfigTest` compares the two shipped copies **line by line below the
header fence** — I confirmed the bodies are byte-identical — so the two prod copies
genuinely cannot diverge; that is prevention, not detection. The third copy is pinned
key-by-key by `SHAPE_KEYS`, and `everyShapeKeyIsActuallyDeclared` guards the
enumeration against its own typos, which is the right second-order guard.

The gap is dimensional rather than per-key: all fourteen new assertions are
`Properties.getProperty(...).isEqualTo("...")`. Not one of them starts a
`LoggerContext` and asks where an event actually goes. That matters most for exactly
the hazard the code warns about three times, in three files, in prose:

> Spring Boot's `setLogLevel` mutates a LoggerConfig that exists by exact name in
> place; against a name that does not exist it creates a fresh one with **NO
> appenders**, which would silently delete the detail track.

`LoggingLevelOverrideTest` proves that property for `com.vingame.bot`. **Nothing
proves it for `com.vingame.websocketparser`** — and since Phase 4, that logger is the
one whose silent replacement costs the entire forensic tier, while
`com.vingame.bot`'s would merely lose one of three. The same applies to the new
`LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER` relaxed binding: `LoggingComposeWiringTest`
asserts the string is in `docker-compose.yml`, and `LoggingLevelOverrideTest$EnvVarRelaxedBinding`
covers the `..._BOT` variable only. The four-segment variable is untested against
Spring's binder.

Fix shape, ~20 lines: a nested `@SpringBootTest(properties =
"logging.level.com.vingame.websocketparser=WARN")` class that resolves
`LoggerContext.getConfiguration().getLoggerConfig("com.vingame.websocketparser.client.X")`
and asserts (a) `getName()` is `com.vingame.websocketparser`, not `""` or
`com.vingame`, (b) `isAdditive()` is false, (c) `getAppenders().keySet()` is exactly
`[AsyncDetail]` — i.e. it contains neither `AsyncRolling` nor `ConsoleAppender`. That
single assertion covers the misroute, the appender-less-LoggerConfig hazard and the
relaxed binding at once, and it is the one thing the current suite cannot catch.

**Partially superseded while this review was being written.** An untracked
`bot-app/src/test/java/com/vingame/bot/infrastructure/logging/ShippedLog4j2ConfigRoutingTest.java`
appeared in the working tree (not in the reviewed diff, not from me — presumably a
concurrent pipeline agent). It builds a real `LoggerContext` from the *shipped*
properties and asserts the routing behaviourally: ws-parser reaching track 2 only,
DEBUG never reaching track 1 with the app logger at DEBUG, an app INFO line in both
files, the layouts, and both `DeleteAction`s' anchoring. That is the dimension this
finding is about and it closes most of it. What it does **not** cover, and what
should still be added, is the Spring half: `logging.level.com.vingame.websocketparser`
mutating that LoggerConfig **in place** (the appender-less-replacement hazard the
comments warn about three times), and the four-segment
`LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER` relaxed binding. The finding stands
against the reviewed diff; treat it as ~70% answered if that file lands.

### [smell] The plan's aggregate disk ceiling (32 GB) contradicts the caveat the config file itself carries (37 GB), and the reconciled figure crosses the 50%-of-free-disk line this feature applies to Loki

`docs/plans/LOG_VOLUME_TIERING.md:1317-1324`;
`logging/log4j2.properties:247-254`

The per-track arithmetic is right, and I checked it rather than taking it:
0.0947 lines/s/bot × 20,000 = 1,894 lines/s ✓; × 250 B = 473 KB/s ✓; 10 GB ÷ that =
5.87 h ✓ ("5.9 h"); at 30,000 → 710 KB/s and 3.91 h ✓ ("3.9 h"). Two remarks in the
plan's favour: log4j2's `FileSize` parses `10GB` as 1024³-based (10.74e9), so the
real coverage is ~7% better than the decimal figures quoted — the projections err
conservative. And the shim-window question resolves cleanly: at 20k the cap retains
3 archives (3 × 3.41 = 10.2e9 < 10.74e9, 4 would not fit), which covers "newest two
plus live" with a file to spare; at 30k it retains exactly 2 (2 × 5.11 = 10.2e9 fits,
3 does not), so the window is satisfied with **zero margin** — one archive is deleted
at every rollover and any upward drift in line size silently halves the window. The
plan says this ("3.9 h holds only ~1.95 archives ... the *window* is shorter than the
design intends") and gates it on P4-6, which is the right call.

What does not reconcile is the total. The plan's budget line reads "track 1 `10GB` +
track 2 `10GB` + evidence `12GB` = **32 GB nominal ceiling, ~48% of free disk**". But
`log4j2.properties` — correctly — carries the caveat that `ifAccumulatedFileSize`
counts archives only, because the `detail-*.log` glob excludes the live file:
"**Budget 15 GB, not 10**, at 30k bots". Track 1 has the same glob property but is
INFO-only and immaterial. Reconciled: 10 + 15 + 12 = **37 GB, ~55% of the 66.96 GiB
free measured on Bot-1** — i.e. above the "abort if the projection exceeds 50% of
available disk" threshold that P0-9 applies to Loki in this same plan. Compounding
context the plan already flags twice (lines 1189, 1698): `Prod-Bot`'s disk has never
been measured at all, and Phase 4 raises the nominal ceiling from ~15 GB to ~37 GB on
the box that died of ENOSPC.

Mitigating, and worth stating in the same place: evidence entries are hardlinks, so
their bytes are double-counted against `logs/` for as long as log4j2 still names the
same inodes — real peak usage is materially below 37 GB. The problem is that two
documents give two different ceilings and neither mentions the other's reasoning.
Fix shape: one reconciled ceiling, in the plan, deriving 37 GB, saying which part is
double-counted, and either re-stating the disk gate or noting explicitly that 55%
was accepted.

## Notes

- **The routing graph is correct, and I could not break it.** Four attempts, for the
  record, since this was the review's first question:
  1. *Can ws-parser reach track 1?* Only by losing its LoggerConfig. The package root
     is real — I unzipped `websocket-parser-core-3.0.5.jar`: 94 classes, **a single
     root `com/vingame/websocketparser`**, so `additivity = false` on that one name
     captures the whole library with nothing outside it. (The comment says "twelve
     subpackages"; there are 13 paths / 7 direct children. Immaterial.) An
     `/actuator/loggers` call against an *intermediate* name like `com.vingame`
     creates an appender-less, additive LoggerConfig — but both leaf configs still
     exist by exact name, so resolution is unchanged. No leak.
  2. *Can DEBUG reach track 1?* The `ThresholdFilter` sits on the async wrapper, and
     `AppenderControl.callAppender0` calls `appender.isFiltered()` before `append()`,
     so Phase 2's Configuration-level `ACCEPT` — which beats the *level* gate in
     `Logger.PrivateConfig.filter` — still hits it. `onMatch = NEUTRAL` means it also
     grants no bypass to anything downstream. No leak.
  3. *Can a line be silently dropped that should have been captured?* Root and
     `logger.app` reference all three appenders and `logger.app` is `additivity =
     false`, so there is no double-write and no orphan level band. Every level from
     TRACE up has a defined home.
  4. *Can the two `Delete` blocks reach each other's files?* `Files.walkFileTree` with
     `maxDepth = 1` visits `basePath` and its direct entries without descending, so
     track 1's sweeper at `/app/logs` cannot see `detail/*.log` (depth 2) or
     `evidence/*`, and track 2's at `/app/logs/detail` cannot climb out. The globs
     are a second, independent guard. Correct on both counts.
- **The remaining Phase 4 shim questions all check out.** A missing `logs/detail/` is
  a genuine no-op, not a wedged pass: `candidates()` returns `[]` for a
  non-existent directory without logging, `selection()`'s `os.path.isfile(live)` is
  false, the track contributes nothing, and `detailDirPresent` on `/health` is what
  distinguishes "absent" from "empty". The legacy `liveName` → `liveNames` upgrade in
  `_sane_entry` is careful: the legacy aggregate name is preserved via `setdefault`
  (never overwritten), the generated detail name is stamped from the stored
  `firstSeenAt` so it is stable rather than restart-dependent, and `liveName` is
  popped so the shapes cannot coexist. One residual: an entry with a *missing*
  `firstSeenAt` gets a load-time stamp, so a crash-looping shim would mint a new
  detail live-name per restart — each one another name on the same inode, which is
  fuel for the first finding. `record()` always writes `firstSeenAt`, so this needs a
  hand-edited file to reach.
- **Concurrent sweeps are safe, which surprised me.** `sweep()` runs outside `self.lock`
  and `ThreadingHTTPServer` can run two promotions at once, but both sweeps sort
  deterministically and evict the same prefix, and `total -= size` is unconditional
  so a failed `unlink` cannot loop. A sweep whose snapshot predates another's unlinks
  still accounted for those bytes, so it converges rather than over-evicting. Worth
  knowing it holds by construction and not by design — the inode-grouping fix in the
  first finding must preserve that property.
- **Losing ws-parser WARN/ERROR from Loki is a real change and the mitigation happens
  to already exist.** AD-23/AD-27 justify the `additivity = false` cut on INFO volume
  and note only that `docker logs` loses the `AUTH [...]` flood. It also removes
  `WARN VingameWebSocketClient - Cannot send message, not connected` — the tell for
  two of the three entries in CLAUDE.md's own Known Bugs — from Grafana, `docker
  logs` and every log-based search. I checked whether anything depended on it: no
  Loki ruler rule, no Grafana panel and no `deploy.sh` smoke grep references
  ws-parser, and the *numeric* signal is metric-based and untouched
  (`bot_reconnects_total`, `bot_ws_event{event=disconnected}`, and
  `ScopedDebugEscalator`'s reconnect-burst arming). So the design is coherent: the
  number stays in Prometheus, the narrative moves to the box. It is worth one line in
  AD-23 saying that on purpose, because right now the reader has to go and check.
- **`Log4j2TwinConfigTest` is the strongest guard in this feature and deserves the
  credit.** A full-body comparison below the header fence makes the two-copy hazard
  *structurally* impossible rather than merely detectable, which is the distinction
  the review question was reaching for. My smell above is about the third copy and
  about the text-vs-runtime dimension, not about this.
- **Phase 4b is clean.** The positions volume, the `!= "/tmp"` assertion, and the
  paired path/mount check in `LogRetentionPipelineTest.promtailPositionsSurviveARestart`
  are exactly right — pinning the two halves together is what stops "the path moved
  but nothing mounts it", which would look identical to the bug being fixed. Keeping
  the now-inert `{level=~"DEBUG|TRACE"}` `retention_stream` as a documented tripwire
  is a good instinct and costs nothing.

---

# Code Review — LOG_VOLUME_TIERING Phase 4 (re-review after remediation)

Branch: `feature/log-volume-tiering`
Reviewed diff: `git diff 2a088c3..HEAD` (5 commits: `588b664` shim, `e03029f`
queue metrics, `f8bfd17` alert-rule test, `74b0455` config/doc corrections,
`3718a45` AD-30 + Phase 4b steps)

Scope as briefed: are the two `bug`s and the `security` finding genuinely fixed,
is the new production code sound, and was anything loosened that should not have
been. The appender routing cleared in the previous section is not re-litigated.

## Verdict

PASS

All three blocking findings are fixed, and fixed in the shape that was asked for
rather than papered over. I re-derived both shim defects against the new code,
mutation-tested the dev's two claims myself (both reproduce: reverting the
tie-break to `size` reddens 2 cases, keying the accounting on path reddens 3),
and ran an AD-16-shaped scenario the selftest does not cover. The
`AlertRuleMetricsTest` exemption is correct and is not masking a routing
requirement — it exempts exactly one rule today and that rule has no product
dimension to lose.

Five `smell`s and one `style` below, all advisory, four of them about
`AsyncQueueMetrics` — which is new production code written in a remediation pass
and is the only part of this diff nobody had reviewed.

Verified green here: `bot-app` logging + alert + observability suites (243 tests,
including the new `AsyncQueueMetricsTest`, `ShippedLog4j2ConfigRoutingTest`,
`AlertRuleMetricsTest`, `AlertRulesAudienceTest`, `AlertmanagerRoutingTest`),
`evidence-shim/selftest.py`, plus two mutants of `shim.py` and a scratch
rendering of the real Prometheus exposition for the new meters.

## Prior findings, disposition

| Prior finding | Disposition |
|---|---|
| [bug] byte guard counts one inode once per name | **Fixed.** `inode_groups()`, per-`(st_dev, st_ino)` accounting, eviction unlinks every name, `total` decrements once, `/health` uses the same unit. |
| [bug] intra-pass eviction falls through to size-ascending | **Fixed.** `_eviction_order = (promoted, mtime, paths[0])`, explicit and total. |
| [security] AD-30's "exists in exactly one place" is false for 30 days | **Fixed.** Claim corrected in the plan, the Open Item, `CLAUDE.md` and the promtail comment, always with the end date; mitigation is executable pre-deploy steps 0a–0c. |
| [smell] track 2 drops and nothing observes it | **Addressed** by `AsyncQueueMetrics` + `LogQueueSaturated`. Four new smells below are about *that* code, not a re-raise of this one. |
| [smell] `immediateFlush=false` bound stated as time, not bytes | **Fixed.** Bound restated as "the last ≤ 8 KB" with the rate table, and `appender.detail.bufferSize = 8192` is now declared and asserted by `AsyncQueuePolicyTest`. (Not added to `SHAPE_KEYS`; that is fine — the shipped file is the one that matters and it is pinned.) |
| [smell] guards assert text, not runtime routing | **Mostly fixed** by `ShippedLog4j2ConfigRoutingTest` (now committed at `dfc0bd1`). The Spring half remains — see the deferred ruling. |
| [smell] 32 GB vs 37 GB disk ceiling | **Fixed.** One reconciled table on AD-26 (~34 GB, ~51% of measured free), the 50% gate re-stated and explicitly deferred to P4-6. |

### Verification detail on the two bugs

**Ordering is genuinely total.** `paths` is the sorted name list of one inode and
every directory entry belongs to exactly one group, so `paths[0]` is unique
across groups; the key can never fall through to an undefined comparison.

**`mtime` really does put closed archives ahead of the live link**, in the
deployed configuration, for the reason the docstring gives and for two it does
not:

- Rollover is `FileRenameAction` → `Files.move(ATOMIC_MOVE)` (log4j-core 2.24.1),
  so a rolled archive keeps the mtime of its last write *before* the boundary,
  which is strictly older than any subsequent write to the new live file. The
  stream-copy fallback that would reset mtime only triggers on a cross-device
  move, and `logs/detail/` is one directory on one filesystem.
- Hardlinks share the inode, so both names of a group necessarily report the same
  mtime; `min()` over them is a no-op rather than a trap.
- `link()` has no copy fallback at all (EXDEV is an error, AD-14), so the shim
  never mints a fresh mtime of its own.

The one case I looked for and could not make bite: a boot retro-promotion where
the "live" file is a freshly created, nearly empty `detail.log` and the archive
holds the pre-crash tail. There the archive *is* the older mtime and goes first —
but evicting the empty live link frees zero bytes, so the guard would evict the
archive on the very next iteration anyway. The new order loses one file where the
old order lost two. Correct in that case too, by a different argument than the
docstring gives.

**The concurrency property survives grouping**, and the deliberate preservation is
right. Two sweeps race: both list, both group, both sort by a now-*total* key, so
both evict the same prefix; `total -= group.size` is unconditional so a losing
sweep cannot loop; `_unlink_group` credits `freed` if *any* name went and swallows
`FileNotFoundError` without a WARN, which is the ordinary outcome of the race and
not a fault. A partially-unlinked group (one name gone, one `EPERM`) leaves the
survivor as its own group next pass, so it still converges. Demoting the
already-removed case from WARN to silence is a real loosening but a bounded one:
the only other producer of that state would be something else deleting out of
`evidence/`, which AD-18 and `EvidenceRetentionEscapeTest` already forbid.

**Scenario I ran that the selftest does not** (two older archives plus the live
inode under both of its AD-16 names, cap forcing one eviction): groups collapse to
three, `bytes` reports 2900 not 3800, the oldest archive is the only thing removed,
and both names of the live inode survive. That is the shape the plan, compose and
`CLAUDE.md` all promise.

**AD-30.** 0a's escape is real: promtail matches `__path__` with doublestar
semantics where `*` does not cross a separator, so `/logs/pre-phase4/console-*.log`
is outside `/logs/*.log` — the same mechanism `logs/evidence/` and `logs/detail/`
already rely on, and the same reason track 1's `Delete` (`basePath /app/logs`,
`maxDepth = 1`) cannot see it either. The plan says so *and* says the consequence
("nothing will ever sweep it") with a remedy, which is the part that would
otherwise become the next finding. Steps read as executable by someone who has not
seen this thread, with one framing wobble noted in the `style` finding below.

## Findings

### [smell] The `errorRef` half of "an exact drop count is unreachable" is not true as written — and the real reason is better than the stated one

`bot-app/src/main/java/com/vingame/bot/infrastructure/logging/AsyncQueueMetrics.java:42-51`,
and the same wording in `e03029f`'s commit message.

I was asked to verify this reasoning, so I checked both halves against
log4j-core 2.24.1 sources.

The `setHandler` half is **exactly right**: `AbstractAppender.setHandler` logs
`"The handler cannot be changed once the appender is started"` and returns, so a
custom `ErrorHandler` cannot be installed post-start, and it does not even throw —
an attempt would fail silently. Good call, correctly stated.

The `errorRef` half is wrong. `errorRef` is a plain `@PluginBuilderAttribute` on
`AsyncAppender.Builder` that names an **appender already in the same
configuration**; `AsyncAppender.start()` resolves it with
`config.getAppenders().get(errorRef)` and `append()` calls
`logToErrorAppenderIfNecessary(false, memento)` on the drop path. Nothing about
that needs a registered plugin — `appender.asyncdetail.errorRef = <name>` in the
properties file, pointing at any stock appender, works today and would *preserve*
the dropped events rather than merely counting them. `annotationProcessorPaths`
only forecloses a **custom** appender class as the target, which the sentence does
not say.

The honest reason to reject `errorRef` here is stronger than the stated one and is
missing from the record: `errorAppender.callAppender(logEvent)` runs
**synchronously on the calling bot thread**, so at drop-storm rates it reintroduces
precisely the blocking pathology `blocking = false` exists to avoid — and pointing
it at `AsyncRolling` instead would push the wire-level flood back into track 1,
Loki and Grafana, undoing the whole feature. That is a decisive argument; the
plugin-registration one is not, and a future engineer who checks it will conclude
the constraint was imagined and go looking for a rewrite that does not exist.

Fix shape: two sentences in the class javadoc — keep the `setHandler` finding
verbatim, replace the `errorRef` clause with "a stock `errorRef` target is
available but appends on the caller's thread, and any target big enough to hold
the flood is either track 1 or a new unbounded file."

### [smell] `for: 5m` on an instantaneous gauge can miss the drop storm entirely, and the absence of the signal reads as "nothing was lost"

`prometheus/alerts.yml` (`LogQueueSaturated`),
`AsyncQueueMetrics.java:42-51`, `CLAUDE.md` (two-track section).

The class doc is honest that this measures saturation rather than drops and that
"a sub-sample burst can slip between two reads". The alert is a level weaker than
that caveat admits, and the operator-facing text does not carry the caveat at all.

`log4j2_async_queue_remaining` is read at *scrape* time (the 10 s sampler drives
the counters and the WARN, not the gauge), and `for: 5m` requires the ratio to be
under 0.1 at **every** evaluation in the window. A queue that oscillates — fills
on a GC pause or an fsync stall, drains in a few hundred ms, refills — drops
thousands of events per cycle while being observed below 10% at perhaps one scrape
in ten. `LogQueueSaturated` never fires, `full_samples_total` stays at 0, and the
`CLAUDE.md` bullet's instruction ("if that alert fires ... treat `detail.log` as
incomplete") invites the converse reading: it did not fire, so the file is whole.
That is the same class of mistake as the AD-30 claim this pass just corrected — a
stated property that holds in one direction being read in both.

To be clear about what I am *not* saying: the counters and the alert are a large
improvement on three stderr lines, `getQueueCapacity()` returns the configured
constant so the ratio is exact, and the `-1`/`-1` case yields 1.0 and cannot
false-fire. The gap is only in what a *quiet* signal licenses.

Fix shape, one line each: say in `CLAUDE.md` and the class doc that these are
*lower bounds* — a quiet `full_samples_total` does not prove a lossless window —
and consider `for: 1m` or an `increase(log4j2_async_queue_full_samples_total[15m]) > 0`
sibling rule, which fires on evidence of loss rather than on continuous
saturation.

### [smell] The sampler reports a full logging queue by logging, so a correlated stall freezes the counters exactly when they matter

`AsyncQueueMetrics.java:200-244` (`record` → `warnIfDue` → `log.warn`).

`AsyncQueueMetrics` lives in `com.vingame.bot.*`, so its WARN routes to
`logger.app` → `AsyncRolling`, which ships `blocking = true`. The common cause of
a saturated `AsyncDetail` is not track 2 being special — it is the disk stalling,
which fills **both** queues. In that case the sampler virtual thread parks on
track 1's queue put inside the WARN, `scheduleAtFixedRate` cannot re-enter, and
`pressure_samples_total` / `full_samples_total` stop advancing for the duration of
the worst part of the incident. The gauges keep working (the scrape thread reads
the queue directly), so the primary signal survives; the running totals that
`AsyncQueueMetricsTest` calls "the record" do not.

The ordering inside `record` is already right — both counters increment before
`warnIfDue` — so nothing is lost for the sample in hand, only for every sample the
block prevents.

Fix shape: hand the WARN to a one-shot task (`Thread.ofVirtual().start(...)`) so a
blocked log call cannot stall the schedule, or state in the javadoc that the
counters are known to freeze under joint saturation and the gauges are the signal
of record there.

### [smell] "The appender is gone" is documented as the interesting fact and is the one fact nothing alerts on

`AsyncQueueMetrics.java:53-56`, `prometheus/alerts.yml` (`LogQueueSaturated`).

The `-1` sentinel is a good design and the test pins it. But the rule is
`remaining / capacity < 0.1`, and a vanished appender reports `-1 / -1 = 1` — the
healthiest possible reading. `sample()` also `continue`s on an unresolvable
appender, so no counter moves either.

That is a gap aimed at this feature's own top hazard. Three files in this diff
warn in prose that Spring's `setLogLevel` against a name that does not exist
creates an appender-less `LoggerConfig` and would "silently delete the detail
track"; the nearest metric to that failure is the one that now reads as perfect
health. `ShippedLog4j2ConfigRoutingTest` covers it at build time, which is where
it is cheapest — but the runtime signal that would catch a *deploy-time*
divergence (wrong config bind-mounted, in-jar fallback active) is one comparison
away and not made.

Fix shape: a sibling rule, `log4j2_async_queue_capacity < 0 for: 10m`, `audience:
internal`, summary "the {{ $labels.appender }} appender is not in the running
log4j2 configuration". It costs three lines and it is the only thing here that
would catch the misconfigured-deploy case.

### [smell] `track()` is the wrong shape for a test seam — the codebase already has the right one, in the same layer

`AsyncQueueMetrics.java:141` (`public void track(List<String>)`),
`AsyncQueueMetrics.java:265` (`public static List<String> asyncAppenderNames()`).

`asyncAppenderNames()` is fine public: a pure static query with no state, no
side effect, and a plausible non-test caller.

`track()` is not. It mutates a Spring singleton's `tracked` field and registers
meters, it has exactly one production caller (`start()`), and its javadoc says
"Public for tests only". The only reason it is public is that
`AlertRuleMetricsTest` lives in `...infrastructure.observability` while this class
lives in `...infrastructure.logging`. The codebase's own precedent for this exact
problem is `InfoGaugeRefresher`, one package over, which keeps
`registerInfoGauges` package-private and puts its tests beside it — and this class
is otherwise a faithful copy of `InfoGaugeRefresher` down to the virtual-thread
scheduler and the `refreshQuietly`/`sampleQuietly` idiom, so the deviation is
conspicuous.

Related nit in the same method's blast radius: `record()` does
`pressureSamples.get(appender).incrementAndGet()`, which NPEs for a name that was
never registered. That is unreachable today (`sample()` only iterates `tracked`,
which `track()` sets *after* registering), but "unreachable today" is doing more
work now that the entry point is public.

Fix shape, pick one: (a) a `public static void register(MeterRegistry, List<String>)`
that both `start()` and the fixture call, leaving `tracked` and `track` private —
this is the smallest change and removes the mutable-state seam entirely; (b) keep
`track` package-private and move the two lines of meter registration the alert
fixture needs into a test helper in `...infrastructure.logging`. Either way, make
`record()` tolerate an unknown name (`computeIfAbsent`) rather than depending on
call order.

### [style] Three residual documentation inconsistencies left by this pass

1. `docs/plans/LOG_VOLUME_TIERING.md` AD-31 and P0-9 still instruct the reader to
   "**expect one final re-ingest** on the deploy that lands this" and to baseline
   "after its one final re-ingest has settled". Step 0a now *prevents* that
   re-ingest, and AD-30's correction says so. A releaser who reads AD-31 first may
   conclude the re-ingest is expected and skip 0a — which is the one step that has
   to happen.
2. `logging/log4j2.properties` puts the live detail file at "~1.2 GB at 20k bots"
   while `docker-compose.yml`, in this same commit, says "~1.5–2.3 GB at 20–30k".
   On Amendment B1's corrected rate (0.0429 × 20,000 × 250 B × 7200 s) the figure
   is ~1.54 GB, so compose is right and the properties file is not. Small, but two
   files disagreeing about one quantity is exactly the defect the 32-vs-37 GB
   finding was.
3. Phase 4b step 0a says the moved archives are "still readable, still
   **hardlink-promotable**". The shim's `candidates()` lists one directory
   non-recursively per track, so nothing under `logs/pre-phase4/` will ever be
   promoted by the shim again — a human can `ln` by hand, which is a different
   claim. Also cosmetic: the banner says steps 0a–0c run "BEFORE `docker compose
   up`" but 0b *is* `docker compose up` and 0c is after it.

## Deferred items — rulings

**1. The `forget_promoted` prune race — deferral accepted, but its blast radius
grew and that should be recorded.**

The race is unchanged and still narrow: `sweep()` builds `surviving` from a
snapshot listing, so a promotion that lands between the listing and
`forget_promoted` has its sidecar stamp pruned, and `promotion_time` then falls
back to mtime.

What changed is the consequence. `inode_groups()` takes `min()` of the group's
promotion stamps, so **one** pruned entry now ages out the **whole inode** — and
eviction unlinks every name, which really does free the blocks. Before this fix
the same pruning aged out one name, and unlinking it freed nothing because the
sibling name held the inode (the very defect that was just repaired). So the fix
correctly removed a bug and, as a side effect, removed the accident that was
masking this one.

It still needs a concurrent promotion *and* a group whose own mtime predates its
track's cutoff (3 d detail / 14 d aggregate), which in practice means a
retro-promotion of stale archives during an alert burst. Advisory is the right
call. But it is now the highest-value advisory in this feature and the fix is one
line — prune only names that were present in the scan, e.g. pass the scanned
basenames alongside `surviving` and take the difference. Please log it in the
plan's deferred/follow-up list rather than leaving it in a review section.

**2. The Spring-side runtime routing gap for `logging.level.com.vingame.websocketparser`
— deferral accepted, no reservation.**

`ShippedLog4j2ConfigRoutingTest` closed the half that mattered: the routing is now
asserted behaviourally against the shipped file, and the exact-name
`com.vingame.websocketparser` `LoggerConfig` is pinned by that test *and* by
`Log4j2TwinConfigTest`'s full-body comparison. What remains — Spring's in-place
`setLogLevel` mutation of that specific logger, and the four-segment
`LOGGING_LEVEL_COM_VINGAME_WEBSOCKETPARSER` relaxed binding — is a test addition
behind which there is no known code defect, and the hazard it guards (a
`LoggerConfig` that does not exist by exact name) is now impossible without a
change that two other tests would fail first. Deferring is correct. Worth ~20
lines whenever someone is next in `LoggingLevelOverrideTest`.

## Notes

- **The mutation evidence checks out.** I re-ran both of the dev's claims on
  copies of the tree: reverting `_eviction_order`'s second element to `size`
  reddens exactly the two assertions in
  `test_same_pass_eviction_takes_the_oldest_file_not_the_smallest`; keying
  `inode_groups` on `path` instead of `(st_dev, st_ino)` reddens exactly the three
  in `test_the_byte_guard_counts_an_inode_once_however_many_names_it_has`. The
  tests fail for the right reasons, not incidentally.
- **The `AlertRuleMetricsTest` exemption is correct, and I checked it against the
  data rather than the prose.** Parsing `alerts.yml` the way the test does: exactly
  **one** rule is exempted (`LogQueueSaturated`, reading `log4j2_async_queue_*`,
  which carry no `product`/`environmentId` dimension to lose), and **eight**
  product-routed rules are still fully checked — `checked >= 5` leaves three rules
  of slack. `AlertRouter.route()` confirms the semantics: `INTERNAL` → `toOpsRoom`,
  which never consults `alert.product()`. And the `Rule.audience()` default of
  `internal` cannot smuggle an unlabelled rule past the check, because
  `AlertRulesAudienceTest.everyRuleDeclaresAnAudience` fails the build on a missing
  `audience` label. The exemption is the AD, not a loosening. One optional
  tightening if you want the guard to be self-defending rather than
  cross-test-defended: for an exempt rule, assert the *converse* — that its metrics
  carry no `product`/`environmentId` labels in the exposition — so a
  product-scoped rule mislabelled `internal` still fails.
- **`LogQueueSaturated` does not disturb the routing the earlier phases were careful
  about.** It matches no child route in `alertmanager.yml`, so it falls through to
  the default `viptalk` receiver → `INTERNAL` → ops room, and
  `AlertmanagerRoutingTest.everyAlertRuleStillReachesVipTalk` covers it
  automatically (it enumerates `alerts.yml` rather than a hard-coded list — that
  design paid off here). Its `job: bot-manager` label also makes it correctly
  inhibitable by `BotManagerDown`'s `equal: ['job']`. The `=`-only limitation of
  `matches` is not exercised: no regex matcher sits between this rule and its
  receiver.
- **The meter names render correctly, which was not free.** Micrometer 1.14's
  `PrometheusNamingConvention` no longer appends `_total` itself (the new
  Prometheus client does, and rejects a name that already carries it), so
  `*_samples_total` was worth checking. I rendered the real exposition against the
  live `LoggerContext`: `log4j2_async_queue_remaining`,
  `_capacity`, `_pressure_samples_total`, `_full_samples_total`, all tagged
  `appender="AsyncDetail"|"AsyncRolling"`, no `_total_total`. The non-`bot_` prefix
  rationale is also correct — `BotMdcTagsMeterFilter.map` returns early for any name
  that does not start with `bot_`, and `AlertRouter.ALERT_DISPATCH_TOTAL` sets the
  same precedent.
- **`getQueueCapacity()` is a constant, which makes the ratio alert exact.**
  `AsyncAppender.getQueueCapacity()` returns the configured `queueSize`, not
  `size() + remainingCapacity()`, so `remaining / capacity` has no sampling skew in
  the denominator. Worth knowing before anyone "simplifies" the capacity gauge into
  a hard-coded number.
- **The lifecycle of the new component is right.** Virtual-thread single-thread
  scheduled executor, `shutdownNow()` in `@PreDestroy`, resolve-from-live-config on
  every read (so Phase 2's `updateLoggers()` — which keeps the same `Configuration`
  and appender instances — is a no-op for it, and a genuine `reconfigure()` that
  replaces them is handled rather than caching a stopped queue). That last choice
  is the one that would have produced defect number four in this area and it was
  made correctly. The only reconfiguration case not handled is an appender *added*
  after startup, which never gets a gauge; not worth code, worth knowing.
- **`/health` now mixes units on purpose and says so.** `evidenceFiles` counts
  names, `evidenceBytes` counts blocks, so an operator dividing one by the other
  gets a meaningless average. The code comment states it; the plan's P4-9 step,
  which is what the releaser actually reads, does not. One clause there would
  close it. Nothing machine-readable consumes these fields, so there is no
  compatibility issue.
