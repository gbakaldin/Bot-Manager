# DEAD_GROUP_AUTO_RECOVERY

> Sibling to `docs/plans/DEAD_GROUP_RESTART.md` (landed 2026-08-05, commit `aa09538`
> "fix(botgroup): reclaim a DEAD runtime on bare start(), not just restart()"), which made
> a DEAD group **manually** restartable through the bare `start()` entry point. That plan
> is closed and its reclaim path is the mechanism this plan reuses verbatim — nothing here
> re-opens it and nothing here invents a second recovery path.
>
> Also reconciles with `docs/plans/TIMED_ACTIVATION.md` (its AD-9 says the activation
> reconciler never auto-resurrects a DEAD group — that stays true; see AD-6 below) and
> `docs/plans/RESILIENCE_HARDENING.md` (the per-bot reconnect budget whose exhaustion is
> what produced the incident).

Dated section: **2026-09-07**.

---

## Goal

Close the gap where a *transient* upstream outage longer than the per-bot reconnect budget
(~51 minutes) converts into an *indefinite* outage that only a human can end. When the
game-server origin behind an environment comes back, bot groups that died during the
outage must restart themselves — reusing the existing accounts through the already-shipped
`start()` reclaim path (re-authenticate, never re-register, never re-deposit, never
recreate the DB group), gated on positive evidence that the environment is actually
serving again, bounded by an explicit attempt budget with backoff, staggered so a recovery
storm cannot re-kill a just-recovered origin, and observable enough that an operator can
tell a clean self-heal from a flap.

---

## Findings — Current State

### The incident, reconstructed against the code

TIP/116 staging, env `ad4e7948-fe24-4ef3-bd73-81f8956a94f0`, groups
`40fa3749-8c36-4cd6-9943-f86e5ed287be` (Xoc Dia, 20 bots) and
`2bf237bd-d106-43b5-a218-dd1807faa3ab` (Slot 120, 20 bots). The observed numbers match the
shipped constants exactly, which is worth stating because it means the incident needs no
new hypothesis:

- `/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java:38`
  — `BACKOFF_SECONDS = {5, 10, 30, 60, 60, 60, 60}`, 285 s per cycle.
- `Bot.java:47` — `MAX_RECONNECT_CYCLES = 10`.
- `Bot.java:40` — `RECONNECT_CONFIRM_SECONDS = 3` after every attempt that connects.
- ⇒ **70 handshake attempts per bot** (7 × 10) over ≈ 51 minutes before
  `runWsReconnectLoop` (`Bot.java:735-750`) gives up and calls
  `transitionStatus(BotStatus.DEAD)`.
- 70 × 40 bots = **2800 handshakes** — the exact count observed — over
  `14:55:08 → 15:48:26` = 53 min. The budget was consumed as designed; every attempt was
  refused `502 Bad Gateway` by the Cloudflare edge because the origin was down.
- At ≥ 80% of bots DEAD (`bot.group.dead.threshold=0.80`,
  `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/resources/application.properties:165`),
  `monitorHealth` (`BotGroupBehaviorService.java:1961-1985`) called
  `handleBotGroupDeath` (`:1990-2003`), which marked the runtime DEAD and persisted
  `targetStatus=DEAD` (`:1997`).
- **Nothing in the codebase retries after that point.** `ActivationEvaluator.decide`
  returns `NONE` for a dead target
  (`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/model/ActivationEvaluator.java:41-43`),
  `onStartup` only auto-starts `targetStatus == ACTIVE`
  (`BotGroupBehaviorService.java:241`), and the health monitor's only action on a dead
  group is the `!runtime.isGroupDead()` guard that makes it inert (`:1978`, `:1982`). Hence
  three days.
- The manual `/restart` that ended it worked in ~60 s because the reclaim path from
  `DEAD_GROUP_RESTART` is in the deployed build (`BotGroupBehaviorService.java:293-306`).
  **The recovery mechanism already exists and is proven; only the trigger is missing.**

### Every bot's WebSocket URI is `Environment.webSocketMiniUrl`, whatever the game type

`BotFactory.createBot` builds a per-bot `ClientFactory` and sets
`URI.create(env.getWebSocketMiniUrl())` unconditionally at
`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java:146`;
`EnvironmentClientRegistry.java:137` does the same for the cached factory.
`Environment.webSocketCardUrl`
(`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/environment/model/Environment.java:51`)
is never used to build a client — only `resolveZoneName` (`:97-106`) branches on game
type, and it branches on the *zone name*, not the URL. So a single probe target per
environment is not a simplification, it is the truth: **`webSocketMiniUrl` is the URL that
was 502 for all 40 bots across two different game types.**

### `targetStatus` does distinguish "died" from "deliberately stopped" — but not completely

There are exactly four writers of `targetStatus`:

| Site | Value | Meaning |
|---|---|---|
| `BotGroupBehaviorService.java:513` (`startLocked`, success) | `ACTIVE` | running |
| `BotGroupBehaviorService.java:877` (`stop`) | `STOPPED` | **operator parked it** |
| `BotGroupBehaviorService.java:1997` (`handleBotGroupDeath`) | `DEAD` | died in flight |
| `BotGroupBehaviorService.java:495` (zero-bot start guard) | `DEAD` | started 0/N bots |
| plus `BotGroupMapper.java:181` — PATCHable through the DTO | any | operator/UI override |

So `DEAD` already carries "was running, then died", and `STOPPED` already carries "an
operator turned this off". The claim that admin intent is unrecoverable is only *half*
true: what is lost is the distinction between "died and nobody has looked yet" and "died,
an operator looked, and decided to leave it down" — because there is no way to express the
second.

**And the natural way to express it is broken today.** `stop()` early-returns when there is
no in-memory runtime (`BotGroupBehaviorService.java:861-864`: `runtime == null` → `WARN
"Bot group {} is not running"` → `return`) **without persisting `STOPPED`**. A group that
died and then survived an app restart has `targetStatus=DEAD` in Mongo and no runtime, so
`POST /stop` on it is a no-op that leaves it `DEAD`. Closing that is a prerequisite for
using `STOPPED` as the opt-out (Phase 2).

### After an app restart a DEAD group is invisible to every dead-group signal

`countDeadGroupsByEnv` (`BotGroupBehaviorService.java:1665-1675`), `countGroupsDeadCurrently`,
`getActualStatus` (`:1921-1926`) and `getHealth` all read `runningGroups`, which
`onStartup` never repopulates for a group whose `targetStatus` is `DEAD` (`:238-258` starts
only `ACTIVE` groups). Consequence: `groups_dead_by_env` reports **0** and
`EnvironmentGroupDead` does **not** fire for a group that died before the last deploy — the
worst case, because that group has been down the longest. A recovery reconciler driven off
the in-memory map would inherit the same blindness; one driven off the **persisted**
`targetStatus` does not. See AD-4.

### The reclaim path already handles every teardown the recovery needs

`startLocked` → reclaim guard (`:293-306`) → `teardownRuntimeMemory` (`:897-925`) →
`runtime.stopAllBots(botMetrics)`, which credits `group_dead_seconds_total` exactly once,
calls `bot.cleanup()` per bot (sets `stopped=true` *before* closing, so no false
`onDisconnect` reconnect is manufactured), shuts the group executor, the **health monitor**
and the **periodic-logout scheduler**, and per-bot watchdogs die inside `cleanup()`. The
per-group `ReentrantLock` (`:183`, taken by `start()` at `:275-276` and `stop()` at `:858-859`)
serialises all of it against operator actions. **The recovery trigger therefore needs no
new teardown, no new locking, and no new lifecycle code — only an entry point that decides
*when* to call the existing one.**

### Existing signals the recovery must not fight

- `ActivationScheduler`
  (`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/ActivationScheduler.java:107-151`)
  — 60 s virtual-thread reconciler, queries `findByActivationMode(SCHEDULED)` only, per-group
  MDC + per-group try/catch, and explicitly resolves `NONE` for a dead group.
- `ScopedDebugEscalator` already arms per-group DEBUG on the way *into* death (first
  watchdog expiry, reconnect burst, dead-ratio crossing half the threshold), with a
  25% duty-cycle bound. The forensic payload for the death itself already exists in
  track 2.
- `prometheus/alerts.yml:213-222` — `EnvironmentGroupDead`, `groups_dead_by_env > 0`,
  `for: 5m`, `audience: product`. Its description currently asserts *"A DEAD group does not
  recover on its own"*, which this feature falsifies.
- `prometheus/alerts.yml:145-155` — `EnvironmentSocketDown`, the alert that *did* fire
  during the incident window and is the human-facing signal for the underlying cause.
- `AlertRulesAudienceTest` parses `alerts.yml` and fails the build if any alerting rule
  omits `labels.audience`, or if an `audience: both` rule has no `public_summary`.

---

## Per-aspect readiness / mapping

| Aspect | State | Notes |
|---|---|---|
| Rebuild mechanism (reclaim + re-auth, no register/deposit) | **ready** | `startLocked` `:284-306`, `teardownRuntimeMemory` `:897`. Zero new lifecycle code. |
| Serialisation vs operator actions | **ready** | Per-group `ReentrantLock` `:183` already wraps `start()`/`stop()`. |
| Reconciler idiom (virtual thread, MDC, per-group isolation) | **ready** | Copy `ActivationScheduler:107-151`. |
| Persisted trigger source | **ready** | `BotGroupRepository.findByTargetStatus(DEAD)` already exists. |
| Env health evidence | **partial** | No probe exists anywhere in the repo (`grep -i probe` finds only a javadoc). New component; JDK `HttpClient` WebSocket is already the house HTTP idiom (`ApiGatewayClient.java:25,84`). |
| Probe target ambiguity | **ready** | `webSocketMiniUrl` is the only WS URL any bot uses (`BotFactory.java:146`). |
| Opt-out ("leave it dead") | **blocked → Phase 2** | `stop()` no-ops without a runtime (`:861-864`), so a post-restart DEAD group cannot be parked `STOPPED`. Small fix. |
| Attempt budget / backoff state | **ready** | In-memory map in the new component; nothing persisted (AD-11). |
| Group-scoped counters | **ready** | `group_dead_seconds_total` (`BotMetrics.java:75,415`) is the exact precedent: non-`bot_`-prefixed, tagged from `mdcTags()`, untouched by `BotMdcTagsMeterFilter`. |
| Env-scoped probe gauges | **ready** | `MultiGauge` idiom from `InfoGaugeRefresher.java:159`. |
| Alert interaction | **ready** | `EnvironmentGroupDead` resolves by itself once `groups_dead_by_env` drops; two new rules cover flap + exhaustion. |
| Watchdog / periodic-logout races | **ready** | Both are shut by `stopAllBots`; both are already inert on a DEAD runtime (`:1978`, `:2063`). |
| UI change | **out of scope** | No new endpoint, no new persisted field, no DTO change. |

---

## Architecture Decisions

**AD-1 — Recovery reuses `startLocked`, through one narrow new entry point on
`BotGroupBehaviorService`, and adds no second lifecycle path.**
Add `boolean startForRecovery(String id)`: take the same per-group `ReentrantLock`, re-read
the persisted group *inside* the lock, re-assert eligibility (AD-3), call the existing
private `startLocked(id)`, and return whether the group came up
(`runningGroups.get(id) != null && actualStatus == ACTIVE && bot count > 0`). The re-read
inside the lock is the whole point: it closes the window where an operator issues `/stop`
between the reconciler's decision and its action. `ReentrantLock` is reentrant, so nesting
under any future caller is safe.
*Rejected:* having the reconciler call the public `start(id)`. That skips the re-assert and
would let a stale decision restart a group an operator had just stopped.

**AD-2 — The health evidence is an anonymous WebSocket upgrade probe against
`Environment.webSocketMiniUrl`, and "healthy" means the origin answered.**
Success = an HTTP 101 handshake, **or** any completed HTTP response with status `< 500`.
Failure = 5xx, connect timeout, connection reset, DNS failure, TLS failure.
- *Why probe rather than watch other groups:* in this incident **all 40 bots on the
  environment died**, across two game types and two groups. There was no healthy witness to
  watch. A passive-only trigger would not have fired at all.
- *Why `< 500` counts as healthy:* the fault class being detected is "origin process is
  gone and the edge is synthesising a gateway error". A well-formed 400/401/403 proves a
  server is parsing our request — which is exactly the transition we are waiting for.
  Anonymous probes on `useJwtAuth` environments may legitimately be refused at 4xx, and an
  environment that could never return 101 to an unauthenticated upgrade would otherwise be
  **permanently ineligible for recovery with no visible reason**. Being slightly permissive
  is safe because a wrong decision costs one bounded, budgeted recovery attempt (AD-8) that
  re-authenticates existing accounts and touches no money.
- The probe is **anonymous**: no bot account, no token, no register, no deposit. It aborts
  the socket immediately on 101.

**AD-3 — Recovery eligibility is a single pure predicate, evaluated against the persisted
group plus live runtime state.** A group is a *recovery candidate* iff **all** hold:
1. `bot.recovery.enabled` is true;
2. persisted `targetStatus == DEAD`, **or** an in-memory runtime exists with
   `isGroupDead()` (covers the case where `handleBotGroupDeath`'s DB save threw — its
   `catch` at `:2000-2002` swallows it) — **but a persisted `targetStatus == STOPPED`
   vetoes unconditionally, whichever disjunct holds (AD-5)**;
3. **not** (an in-memory runtime exists with `actualStatus == ACTIVE`) — a running group is
   not dead no matter what the DB says;
4. `activationMode != MANUAL_OFF`;
5. if `activationMode == SCHEDULED`: `activationWindow != null` and
   `activationWindow.isActiveAt(now, zone)` — do not resurrect a group into a closed window;
6. `botCount > 0`;
7. the group's attempt budget is not exhausted and its backoff deadline has passed.
Implemented as a pure class `RecoveryEligibility` (no Spring, no I/O), mirroring
`ActivationEvaluator`, so it is exhaustively unit-testable and is shared by the Phase 1
probe selector and the Phase 3 reconciler.

> **Amendment (compliance ruling, folded in at the fix).** Condition 2's `STOPPED` veto
> was originally only implied — the disjunction asks "not DEAD", never "not STOPPED", so
> a persisted `STOPPED` row that still owned a lingering DEAD runtime satisfied it
> through the second disjunct, and `startForRecovery` re-asserts the same predicate so
> nothing downstream caught it. A lingering DEAD runtime is the **ordinary** post-death
> state (`handleBotGroupDeath` and `startLocked`'s zero-bot guard both leave it in
> `runningGroups` on purpose), and the pair is reachable through
> `PATCH {"targetStatus":"STOPPED"}` and — with no PATCH at all — through a lost Mongo
> write in the zero-bot guard, which marks the runtime DEAD, keeps it in the map and
> only then saves. AD-5 is the only operator opt-out from a feature that autonomously
> starts money-spending bots, so it is enforced by an explicit veto rather than by a
> whole-program reachability argument that any future change to `stop()`, to the mapper
> or a new bulk status endpoint would invalidate silently. AD-5's own wording is
> unchanged, and the disjunct still covers what it was there for (persisted `ACTIVE` +
> runtime DEAD).

**AD-4 — The reconciler is driven by the persisted `targetStatus`, not by
`runningGroups`.** Query `botGroupRepository.findByTargetStatus(DEAD)` (already exists),
then union in any in-memory DEAD runtimes for condition 2 above. This is what makes
recovery work for a group that died *before* the current JVM started — the case with the
longest downtime and, per the Findings, the case that today is invisible to
`groups_dead_by_env` and to `EnvironmentGroupDead` entirely.

**AD-5 — `STOPPED` is the opt-out. No new persisted field, per group or per environment.**
- "Do not auto-recover this group" is expressed by `POST /{id}/stop` → `targetStatus=STOPPED`
  → fails eligibility condition 2 forever. Phase 2 makes that work for a runtime-less DEAD
  group, which is the one shape where it does not work today.
- "Do not auto-recover anything on this instance" is the global property
  `bot.recovery.enabled`.
- "Planned maintenance on one environment" is `POST /stop` on that environment's groups —
  which an operator does anyway before maintenance, and which is already the honest
  statement of intent.
*Rejected:* `BotGroup.autoRecoveryEnabled` and `Environment.autoRecoveryEnabled`. Each costs
a model field + DTO field + null-means-inherit merge semantics + validation + UI, to express
"keep this group running but never recover it if it dies", which is not a requirement anyone
has. Schema surface is not free and this feature does not need any.

**AD-6 — Default ON, shipped OFF, flipped in two steps.** `bot.recovery.enabled` ships
`false` in Phase 3 (code lands inert), is enabled on staging via compose in Phase 4, and
the compiled default flips to `true` in Phase 5 after a soak.
*Why the eventual default is ON:* the failure it removes is three days of a fleet that is
provably restorable in 60 seconds. The failure it can introduce is a bounded number of
re-auth attempts against an environment for a group that was **already not betting** — no
new accounts, no deposits, no money, and on failure the group lands back in exactly the
DEAD state it was already in. Recovery cannot make revenue worse than DEAD; not recovering
demonstrably can.

**AD-7 — A new `DeadGroupRecoveryScheduler`, not an extension of `ActivationScheduler`.**
Different query (`findByTargetStatus(DEAD)` vs `findByActivationMode(SCHEDULED)`), different
cadence and backoff state, and a blocking tick (AD-9) that must not delay activation
reconciliation. `ActivationEvaluator` stays pure and Fleet-reusable, and TIMED_ACTIVATION
AD-9 stays literally true — the *activation* reconciler still never resurrects a DEAD group.
Recovery is owned by exactly one component, so the two can never both act on the same group;
the recovery component honours activation semantics through eligibility conditions 4 and 5.

**AD-8 — Attempt budget: 6 attempts per death episode, with backoff, then hand to a human.**
`bot.recovery.max-attempts=6`, `bot.recovery.backoff-minutes=2,5,15,30,60,60`; attempt *i*
waits `backoff[min(i, len-1)]` after the previous *failed* attempt. The budget is spent
**only** on attempts, and attempts happen only when the probe says healthy — so an
environment that is down for six hours consumes **zero** budget and simply waits. What the
budget bounds is the pathological case "the environment answers but the group still will not
come up", which is ~2 h of retries before `group_recovery_exhausted_total` fires and the
group is left alone until an operator acts (any manual `/start`, `/restart` or a successful
recovery resets it). This is the group-granularity analogue of `MAX_RECONNECT_CYCLES` and
exists to prevent recreating, at group scale, the 11,662-reconnect hot loop already observed
on 097.

**AD-9 — Staggering is structural: one single-threaded reconciler, at most one recovery
attempt per tick.** `bot.recovery.max-per-tick=1` on a 60 s tick means ten dead groups
recover over ten minutes with no rate limiter, no semaphore and no thread pool. The tick
blocks for the duration of one group start — that is the serialisation mechanism, not a
defect. Candidate selection is **earliest-due first** (by backoff deadline, tie-broken by
how long the group has been dead), which rotates fairly and cannot let one permanently
failing group starve the others. The first tick is offset by a random
`bot.recovery.jitter-seconds` (default 0-30 s) so three app instances do not align.
The **probe** runs on its own separate scheduler (Phase 1) and never blocks.

**AD-10 — A live sibling short-circuits the probe.** If any ACTIVE runtime on the same
environment currently has ≥ 1 connected bot (`countOpenWsByEnv`,
`BotGroupBehaviorService.java:1632`), the environment counts as healthy without probing.
Same URL, stronger evidence, zero network cost — and it makes the common case (one group of
several died) recover on the next tick.

**AD-11 — Recovery state is in-memory only.** `ConcurrentHashMap<groupId, RecoveryState>`
holding attempt count, next-due instant and last-success instant; the probe holds
`ConcurrentHashMap<wsUrl, ProbeState>` with the consecutive-healthy streak. A JVM restart
resets both: the group gets a fresh 6-attempt budget, which is correct — a restart is new
information and the operator has, by definition, just touched the box. Nothing about
recovery belongs in Mongo.

**AD-12 — A recovery attempt requires `bot.recovery.probe.healthy-streak=2` consecutive
healthy probes** (i.e. ≥ 60 s of sustained health at the default cadence). One 200 during a
flapping origin is not evidence, and the cost of waiting one extra tick is negligible next
to a wasted attempt from the budget.

**AD-13 — Counters are group-scoped and MDC-tagged, mirroring
`group_dead_seconds_total`.** New in `BotMetrics`:
`group_recovery_attempts_total{outcome=success|failed|error}` and
`group_recovery_exhausted_total`, both `.tags(mdcTags())` under group MDC, both **not**
`bot_`-prefixed so `BotMdcTagsMeterFilter` leaves them alone — exactly the shape of
`GROUP_DEAD_SECONDS_TOTAL` (`BotMetrics.java:75,415`). The probe's own meters are
environment-scoped and are registered directly on the `MeterRegistry` with explicit
`{environmentId, product, outcome}` tags — **never** from MDC, because the probe thread's
MDC belongs to whatever ran on it last.

**AD-14 — Logging: everything this feature emits is tier-1 admissible.** Its rate is a
function of *incidents*, not of bot count, round rate or message rate. Concretely:
one INFO per recovery attempt, one per outcome, one per probe **state transition** (every
individual probe result is DEBUG), and one ERROR on budget exhaustion. Nothing per-bot; the
per-bot rebuild lines are already DEBUG inside `startLocked`. `PerBotInfoLogGuardTest` is
unaffected because none of the new lines live in a per-bot class.

**AD-15 — `EnvironmentGroupDead` keeps `for: 5m` and gains a corrected description; flapping
is made visible by two new rules.** A recovery inside 5 minutes silently prevents the page —
which is the point. A recovery *after* 5 minutes fires the alert and then resolves it, which
is the correct record of "it broke and healed". What must not be hidden is repetition, so
Phase 4 adds `EnvironmentGroupRecoveryFlapping` (≥ 3 successful recoveries in 6 h) and
`EnvironmentGroupRecoveryExhausted` (any exhaustion, critical — a human is definitely
required). Both `audience: product`, per `AlertRulesAudienceTest`.

---

## Plan

Five phases. Each is one Dev session, builds and ships on its own, and is independently
verifiable. Phases 1-3 are inert in production behaviour (`bot.recovery.enabled=false`);
the behaviour change lands at Phase 4 on staging only, and at Phase 5 everywhere.

### Phase 1 — Environment WebSocket reachability probe, observe-only

No group is ever restarted in this phase. The purpose is to land the probe, prove it
classifies the two states correctly on the real staging environments, and get its metrics
into Prometheus before anything depends on them.

New files, all under
`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/`:

1. **`domain/botgroup/model/RecoveryEligibility.java`** — the pure predicate of AD-3,
   modelled on `ActivationEvaluator` (static, no Spring, no I/O). Signature roughly
   `static boolean isCandidate(BotGroupStatus persistedTarget, ActivationMode mode,
   ActivationWindow window, int botCount, BotGroupStatus runtimeStatus /* nullable */,
   boolean runtimeGroupDead, Instant now, ZoneId zone)`. Conditions 1 and 7 (the global flag
   and the attempt budget) are **not** in here — they belong to the scheduler; this class
   answers only "is this group in a shape that recovery may act on".
2. **`infrastructure/probe/EnvironmentWsProbe.java`** — one method,
   `ProbeResult probe(String wsUrl, Map<String,String> headers)`, using
   `java.net.http.HttpClient.newWebSocketBuilder()`:
   - shared `HttpClient` built once with
     `.connectTimeout(Duration.ofSeconds(${bot.recovery.probe.timeout-seconds:5}))`
     and `.followRedirects(NEVER)`;
   - `buildAsync(URI.create(wsUrl), noopListener).get(timeout, SECONDS)`; on success record
     `OPEN` + latency and call `webSocket.abort()` immediately;
   - classify failures from the `ExecutionException` cause:
     `WebSocketHandshakeException` → read `getResponse().statusCode()` → `HTTP_4XX`
     (< 500) or `HTTP_5XX`; `HttpConnectTimeoutException`/`TimeoutException` → `TIMEOUT`;
     `SSLException` → `TLS_ERROR`; `ConnectException`/`UnresolvedAddressException` →
     `CONNECT_ERROR`; anything else → `ERROR`;
   - **skip restricted headers** (`Host`, `Connection`, `Upgrade`, `Content-Length`,
     `Sec-WebSocket-*`): `WebSocket.Builder.header` throws `IllegalArgumentException` on
     them. Log the skipped names once per URL at DEBUG.
   - `ProbeResult.healthy()` = `OPEN || HTTP_4XX` (AD-2).
3. **`infrastructure/probe/EnvironmentProbeScheduler.java`** — single-thread virtual-thread
   scheduler named `env-ws-probe`, tick `${bot.recovery.probe.tick-seconds:60}`, following
   `ActivationScheduler:68-101` for `@PostConstruct`/`@PreDestroy` and per-item
   try/catch + MDC. Each tick:
   - load `botGroupRepository.findByTargetStatus(DEAD)` and union the in-memory DEAD
     runtimes (new tiny accessor `Collection<String> listDeadRuntimeGroupIds()` on
     `BotGroupBehaviorService`, next to `countDeadGroupsByEnv` at `:1665`);
   - keep the groups for which `RecoveryEligibility.isCandidate(...)` is true; resolve their
     environments; **de-duplicate by `webSocketMiniUrl`**;
   - for each such URL: if AD-10's live-sibling short-circuit holds, record `healthy` without
     a network call; otherwise probe;
   - maintain the consecutive-healthy streak per URL; expose
     `boolean isHealthy(String envId)` (streak ≥ `healthy-streak`) for Phase 3;
   - **if there are no candidates, do nothing at all** — zero probe traffic in the healthy
     steady state, which is the normal state.
   - Metrics, registered directly on the `MeterRegistry` with explicit tags (AD-13):
     `env_ws_probe_total{environmentId, product, outcome}` counter and a `MultiGauge`
     `env_ws_probe_healthy{environmentId, product}` (1/0), rows re-registered each tick
     (`InfoGaugeRefresher.java:159` is the idiom).
   - Logging (AD-14): every probe result at DEBUG; **INFO only on a transition** —
     `env <id> (<name>): ws probe healthy (<outcome> in <n>ms) — <k> dead group(s) eligible`
     and its unhealthy counterpart.
4. **Config** in `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/resources/application.properties`,
   after the `bot.group.dead.threshold` block (`:165`): `bot.recovery.enabled=false`,
   `bot.recovery.probe.tick-seconds=60`, `bot.recovery.probe.timeout-seconds=5`,
   `bot.recovery.probe.healthy-streak=2`. The probe scheduler runs whenever there are
   candidates, **independently of `bot.recovery.enabled`** — observing is always safe and is
   what makes Phase 1 verifiable on its own.
5. **Tests**: `RecoveryEligibilityTest` (exhaustive over the predicate — mirror
   `ActivationEvaluatorTest`), `EnvironmentWsProbeClassificationTest` (feed synthetic
   exceptions/statuses into the classifier; no network), `EnvironmentProbeSchedulerTest`
   (Mockito, mirror `ActivationSchedulerTest`: candidate selection, URL de-dup, streak
   arithmetic, transition-only INFO, zero probes when there are no candidates).

**Phase 1 verification:** steps V1-V5 below.

### Phase 2 — `stop()` parks a runtime-less DEAD group as `STOPPED`

Small, self-contained, and a prerequisite for AD-5's opt-out.

In `BotGroupBehaviorService.stop(String)` (`:854-885`), replace the bare early return at
`:861-864`. Under the same per-group lock, when `runtime == null`: load the persisted group;
if `targetStatus != STOPPED`, set `STOPPED` + `lastStoppedAt` and save, logging
`Bot group {} has no runtime — persisting STOPPED (was {})` at INFO; if it is already
`STOPPED`, keep today's WARN + return. Nothing else changes: `stopAndLogout` (`:950`) and
`restart` (`:1009`) are untouched, and the behaviour for a group **with** a runtime is
byte-for-byte identical.

> **Amended — "`restart` is untouched" does not survive AD-5. See Amendment A1 at the
> bottom of this document.**

Tests in `BotGroupBehaviorServiceRestartTest`: `stop_persistsStoppedWhenNoRuntimeAndDead()`,
`stop_isNoOpWhenNoRuntimeAndAlreadyStopped()`, plus assert the existing with-runtime path is
unchanged.

**Phase 2 verification:** step V6.

### Phase 3 — The recovery reconciler (inert: `bot.recovery.enabled=false`)

1. **`BotGroupBehaviorService.startForRecovery(String id)`** (AD-1) — placed next to
   `start()` at `:268`. Takes `groupLocks.computeIfAbsent(id, …)`, re-reads the group,
   re-asserts `RecoveryEligibility.isCandidate(...)` (returning `false` immediately if an
   operator changed things underneath), calls `startLocked(id)`, and returns
   `runningGroups.get(id) != null && actualStatus == ACTIVE && bot count > 0`. Catches
   nothing — the caller isolates per group.
2. **`domain/botgroup/service/DeadGroupRecoveryScheduler.java`** (AD-7, AD-9) — single-thread
   virtual-thread scheduler `dead-group-recovery`, tick
   `${bot.recovery.tick-seconds:60}`, first tick offset by
   `${bot.recovery.jitter-seconds:30}` × random. Per tick:
   - return immediately unless `bot.recovery.enabled`;
   - build the candidate list exactly as the probe scheduler does (share the selector);
   - drop candidates whose environment is not `probeScheduler.isHealthy(envId)`;
   - drop candidates whose `RecoveryState` is exhausted or not yet due;
   - sort **earliest-due first**, take `bot.recovery.max-per-tick` (default 1);
   - for each: set group MDC, log
     `group <id> (<name>): auto-recovery attempt <n>/<max> — env <envId> healthy for <k>
     probes, dead since <t>, reason "<lastFailureReason>"` at INFO, call
     `startForRecovery`, then:
     - **success** → INFO `group <id> (<name>): auto-recovery succeeded — <n>/<m> bots up in
       <s>s`; `group_recovery_attempts_total{outcome="success"}`; record `lastSuccess` and
       schedule the budget reset for `+bot.recovery.settle-minutes`;
     - **failure** → WARN `group <id> (<name>): auto-recovery attempt <n>/<max> failed
       (<detail>) — next attempt in <b>m`; `outcome="failed"`; advance backoff;
     - **exception** → same as failure but `outcome="error"` and log at ERROR with the
       stack trace (per-group try/catch, exactly like `ActivationScheduler:118-124`);
     - **budget exhausted** → ERROR `group <id> (<name>): auto-recovery exhausted after
       <max> attempts — operator action required (POST /api/v1/bot-group/<id>/restart)`;
       `group_recovery_exhausted_total`; stop attempting until reset.
   - Budget reset: on a success that has held ACTIVE for `settle-minutes` at the next tick,
     or on any observation that `targetStatus` became `ACTIVE`/`STOPPED` by another actor.
   - Evict `RecoveryState` for groups that are no longer candidates so the map cannot grow.
3. **`BotMetrics`** — add `GROUP_RECOVERY_ATTEMPTS_TOTAL` /
   `GROUP_RECOVERY_EXHAUSTED_TOTAL` constants plus
   `incGroupRecoveryAttempt(String outcome)` / `incGroupRecoveryExhausted()`, copying the
   `.tags(mdcTags())` shape of the `GROUP_DEAD_SECONDS_TOTAL` builder at `BotMetrics.java:415`.
4. **Config**: `bot.recovery.tick-seconds=60`, `bot.recovery.max-attempts=6`,
   `bot.recovery.backoff-minutes=2,5,15,30,60,60`, `bot.recovery.max-per-tick=1`,
   `bot.recovery.settle-minutes=10`, `bot.recovery.jitter-seconds=30`. Bind the backoff as
   `@Value("${bot.recovery.backoff-minutes}") int[]`.
5. **Tests**: `DeadGroupRecoverySchedulerTest` (Mockito; mirror `ActivationSchedulerTest`) —
   at minimum: candidate filtered out when the env is unhealthy; at most `max-per-tick`
   attempts per tick; backoff advances only on failure; budget exhaustion emits the ERROR +
   counter exactly once and then stops attempting; a `STOPPED` group is never attempted; a
   `MANUAL_OFF` group is never attempted; a `SCHEDULED` group with a closed window is never
   attempted; earliest-due ordering rotates rather than starving. Plus
   `BotGroupBehaviorServiceRestartTest.startForRecovery_reAssertsEligibilityUnderLock()` and
   `startForRecovery_neverRegistersOrDeposits()` (structural, mirroring
   DEAD_GROUP_RESTART's test 5).

**Phase 3 verification:** step V7 (the flag is off, so the assertion is that *nothing*
happens and the boot lines are present).

### Phase 4 — Alerting, docs, and staging enable

1. `/Users/gleb/IdeaProjects/Bot/prometheus/alerts.yml`, group `bot-manager-environment`:
   - **Correct** the `EnvironmentGroupDead` description (`:222`) — it must no longer claim a
     DEAD group never recovers; state instead that auto-recovery will attempt it when the
     environment probe reports healthy, and that a still-firing alert after ~10 minutes means
     recovery is failing or the environment is still down. Leave `expr` and `for: 5m` alone
     (AD-15).
   - **Add** `EnvironmentGroupRecoveryExhausted`:
     `increase(group_recovery_exhausted_total[15m]) > 0`, `severity: critical`,
     `audience: product`.
   - **Add** `EnvironmentGroupRecoveryFlapping`:
     `increase(group_recovery_attempts_total{outcome="success"}[6h]) >= 3`, `for: 5m`,
     `severity: warning`, `audience: product`.
   - Both rules need `labels.audience` or `AlertRulesAudienceTest` fails the build; neither
     is `audience: both`, so neither needs `public_summary`.
2. `/Users/gleb/IdeaProjects/Bot/docker-compose.yml` — add
   `- BOT_RECOVERY_ENABLED=${BOT_RECOVERY_ENABLED:-false}` to the `bot-manager` service
   environment block (next to `BOT_LOG_LEVEL` at `:69`); Spring maps it to
   `bot.recovery.enabled`. Set `BOT_RECOVERY_ENABLED=true` in staging's `secrets.env`/`.env`
   only (the uncommitted merge; never commit `deploy.sh`).
3. `/Users/gleb/IdeaProjects/Bot/CLAUDE.md` — a short subsection under Architecture
   describing the recovery trigger, the `STOPPED`-is-the-opt-out rule, and the budget; and
   fix the Backlog line that still frames DEAD-group restart as purely manual.

**Phase 4 verification:** steps V8-V11 (the end-to-end recovery, the opt-out, and the
exhaustion path), run on staging with the flag on.

### Phase 5 — Flip the compiled default, and prod

After ≥ 72 h of staging soak with no unexplained recovery:
1. `application.properties`: `bot.recovery.enabled=true`; compose keeps
   `${BOT_RECOVERY_ENABLED:-true}` so the kill switch survives.
2. Review `group_recovery_attempts_total` over the soak window; if any environment shows
   attempts that never succeed, do **not** promote — that is the AD-2 permissiveness
   showing, and the answer is to tighten the probe predicate for that environment, not to
   raise the budget.
3. Deploy to `Prod-Bot` in the next ticketed window (MEMORY: prod access is ticket-gated,
   12 h max; the releaser must be told explicitly it is targeting prod, since it hardcodes
   Bot-1).

**Phase 5 verification:** step V12.

---

## Implementation Notes / Concerns

- **The probe must never touch a bot account.** No `ApiGatewayClient`, no `BotCredentials`,
  no `TokensProvider`. If review finds any auth object reachable from the probe, that is a
  defect, not an optimisation.
- **Do not build the probe on `VingameWebSocketClient`.** It requires a `TokensProvider`,
  sends AUTH, and — per MEMORY's PING-before-AUTH finding — has its own connect-time
  behaviour that is precisely what we are *not* trying to measure. The JDK
  `HttpClient.newWebSocketBuilder()` is dependency-free and is already the house HTTP idiom.
- **Restricted headers will throw.** `WebSocket.Builder.header("Host", …)` raises
  `IllegalArgumentException`; a probe that blindly forwards `Environment.headers` will fail
  100% of the time on any environment that sets one, and will look exactly like an outage.
  Filter, do not catch-and-ignore.
- **`bot.recovery.enabled=false` must gate the *reconciler*, not the probe.** Phase 1 is
  only verifiable if the probe runs while the recovery is off.
- **The reconciler tick blocks for the duration of one group start.** For a 2000-bot group
  that can be tens of seconds. This is AD-9's serialisation mechanism, but it means the tick
  is not a real-time loop — do not add work to it that needs to run on time. That is why the
  probe has its own scheduler.
- **Race: `handleBotGroupDeath` does not take the group lock.** It runs on the health-monitor
  thread and can persist `targetStatus=DEAD` *after* a concurrent recovery has persisted
  `ACTIVE`, leaving DB `DEAD` + runtime `ACTIVE`. Eligibility condition 3 (AD-3) is what
  stops the next tick spuriously restarting a healthy group; do not drop it as redundant.
  The DB self-corrects at the next `stop`/`start`/death.
  **Amended — this note covered only the ACTIVE variant of the race, and the STOPPED
  variant does not self-correct. `handleBotGroupDeath` now takes the lock; see
  Amendment A2 at the bottom of this document.**
- **`bot.group.dead.threshold` is 0.80, so a group can sit at 79% DEAD forever** and is
  *not* a recovery candidate. That is unchanged, pre-existing, and out of scope here —
  `EnvironmentDeadBotRatioHigh` is the signal for it.
- **A recovery attempt may cost N auth calls to a gateway that is still sick.** With
  `max-per-tick=1` and the backoff, the worst case is one group's worth of authentications
  per environment per few minutes — far below the fan-out of an operator-clicked `/restart`,
  which is the status quo and is unthrottled.
- **`lastFailureReason` is not cleared on a successful recovery** (`startLocked` never
  clears it). Leave it: it is the audit trail of the last death and it is what the recovery
  INFO line quotes. It also cheaply distinguishes the two DEAD shapes — `"Multiple bot
  disconnections detected"` (health monitor, `:1998`) vs `"Started 0/N bots …"` (zero-bot
  guard, `:496`).
- **`groupLocks` is still never cleaned** (pre-existing, `BotGroupBehaviorService.java:183`).
  Recovery adds no entries beyond the ones `start()`/`stop()` would create. Unchanged; not
  this plan's problem.
- **`EnvironmentGroupDead` will now often resolve on its own.** Anyone reading VipTalk needs
  to know that a firing-then-resolving pair may mean "self-healed", which is why Phase 4
  corrects the description text rather than only adding rules.
- **Loki cannot see the probe's DEBUG lines** (LOG_VOLUME_TIERING Phase 4: DEBUG never
  reaches track 1). Per-probe detail lives in `logs/detail/detail.log` on the box; the
  INFO transition lines are what Grafana gets. Write the transition lines so they stand
  alone.

---

## Open Items

- **Arming scoped DEBUG on a recovery attempt** — deliberately not done. `ScopedDebugEscalator`
  already arms on the way *into* death, its `escalate` is private, and adding a second
  trigger would eat into the 25% duty cycle that plan's `ScopedDebugEscalatorTest` pins.
  Revisit only if a recovery failure turns out to be undiagnosable from track 2.
- **VipTalk notification on recovery** — out of scope. Alert delivery is Alertmanager's job
  (VIPTALK_ALERTING AD: never from app call sites); the `EnvironmentGroupDead` resolve
  notification already tells the product room.
- **Per-environment / per-group opt-out fields** — explicitly rejected (AD-5). If a real
  operational need appears that `STOPPED` cannot express, revisit with that need stated.
- **Persisting the attempt budget** — rejected (AD-11). A JVM restart granting a fresh budget
  is acceptable and arguably correct.
- **Recovering a group whose environment record itself is broken** (bad `webSocketMiniUrl`,
  deleted environment) — the probe fails, so no attempt is ever made, and the group stays
  DEAD with `env_ws_probe_total{outcome="..."}` explaining why. No special handling.
- **Widening AD-2's healthy predicate** if Phase 1's staging measurement shows an environment
  that answers healthy 101s only to authenticated upgrades — the `< 500` clause already
  covers the expected shape; if a *5xx* is observed on a healthy environment, that is a new
  fact and needs a decision, not a config change.
- **Frontend surfacing of "recovery in progress"** — out of scope; `GET /{id}/status` already
  reports `targetStatus`/`actualStatus` and flips to `ACTIVE` on success.

---

## Verification

Releaser runs these on Bot-1 (staging) after each phase's deploy. Substitute `<bot-1>` with
the staging host. Note the Bot-1 single-compose layout (MEMORY): every bot-manager redeploy
also restarts Grafana/Prometheus/Loki, so the smoke check must re-verify them.

### Universal smoke (every phase)

**V0a — app up.**
```bash
until curl -sf https://<bot-1>/actuator/health | grep -q '"status":"UP"'; do sleep 2; done
```
Expect: returns within 60 s.

**V0b — observability survived the co-located redeploy.**
```bash
curl -sf http://<bot-1>:9090/-/healthy && curl -sf http://<bot-1>:3000/api/health
```
Expect: Prometheus prints `Prometheus Server is Healthy.`; Grafana JSON contains
`"database":"ok"`.

### Phase 1 — probe, observe-only

**V1 — the probe scheduler booted.**
```bash
docker logs bot-manager 2>&1 | grep -c "Environment ws probe scheduler started"
```
Expect: `1`.

**V2 — with no DEAD group, the probe emits nothing.** Confirm there is currently no
candidate, then confirm silence:
```bash
curl -sf http://<bot-1>:9090/api/v1/query --data-urlencode 'query=sum(groups_dead_by_env)' \
  | jq -r '.data.result[0].value[1] // "0"'
curl -sf https://<bot-1>/actuator/prometheus | grep -c '^env_ws_probe_total' || true
```
Expect: dead-group sum `0`, and `env_ws_probe_total` line count `0` (no series until there
is a candidate — AD: zero probe traffic in the healthy steady state).

**V3 — manufacture a candidate on a healthy environment.** Pick a **legacy** group (one
whose `activationMode` is `null`; confirm with `GET /{id}` first, because `/stop` on a
scheduled-capable group parks it `MANUAL_OFF` and would make it ineligible). Record its id
as `$G` and its environment as `$E`.
```bash
curl -sf https://<bot-1>/api/v1/bot-group/$G | jq '{activationMode, targetStatus, botCount, environmentId}'
curl -sf -X POST https://<bot-1>/api/v1/bot-group/$G/stop -o /dev/null -w '%{http_code}\n'
curl -sf -X PATCH https://<bot-1>/api/v1/bot-group/$G \
  -H 'Content-Type: application/json' -d '{"targetStatus":"DEAD"}' | jq -r .targetStatus
```
Expect: `activationMode` is `null`; stop returns `200`; PATCH echoes `"DEAD"`.

**V4 — the probe now runs and reports the environment healthy.** Within 2 probe ticks
(≤ 150 s):
```bash
curl -sf https://<bot-1>/actuator/prometheus | grep '^env_ws_probe_total'
curl -sf https://<bot-1>/actuator/prometheus | grep '^env_ws_probe_healthy'
```
Expect: an `env_ws_probe_total{environmentId="$E",...,outcome="..."}` series with value
`> 0`, and `env_ws_probe_healthy{environmentId="$E",...} 1.0`.
Expect a log line matching `^.*env .* ws probe healthy` in `docker logs bot-manager`.

**The healthy `outcome` set is three values, not two.** Alongside `open` and `http_4xx`,
AD-10's live-sibling short-circuit reports `live_sibling` — it concludes the environment is
healthy *without opening a socket*, so labelling it `open` would claim a probe that never
ran. An earlier draft of this step listed only `open`/`http_4xx` and would therefore have
been satisfied by a path it did not name. If you want to force a real socket probe, pick a
`$G` on an environment where **no other group is running**; otherwise accept `live_sibling`
as a healthy outcome here.

**V5 — nothing was restarted (observe-only).**
```bash
curl -sf https://<bot-1>/api/v1/bot-group/$G/status | jq '{targetStatus, actualStatus}'
curl -sf https://<bot-1>/actuator/prometheus | grep -c '^group_recovery_attempts_total' || true
```
Expect: still `{"targetStatus":"DEAD","actualStatus":"STOPPED"}`; recovery counter series
count `0` (the metric does not exist until Phase 3). Then restore: `POST /$G/start`,
expect `200` and `actualStatus=ACTIVE`.

### Phase 2 — opt-out primitive

**V6 — `/stop` parks a runtime-less DEAD group.** With `$G` stopped and PATCHed to `DEAD` as
in V3 (so there is no runtime):
```bash
curl -sf -X POST https://<bot-1>/api/v1/bot-group/$G/stop -o /dev/null -w '%{http_code}\n'
curl -sf https://<bot-1>/api/v1/bot-group/$G/status | jq -r .targetStatus
```
Expect: `200`, then `"STOPPED"`. Expect a log line matching
`Bot group $G has no runtime — persisting STOPPED`. Repeat the same `/stop`: expect `200`
and a `Bot group $G is not running` WARN (idempotent, no second write).

### Phase 3 — reconciler present but disabled

**V7 — the reconciler booted and is inert.**
```bash
docker logs bot-manager 2>&1 | grep "Dead-group recovery scheduler"
```
Expect one line reporting `enabled=false`. Re-run V3 to create a candidate, wait 3 ticks
(≥ 180 s), then:
```bash
curl -sf https://<bot-1>/api/v1/bot-group/$G/status | jq -r .targetStatus
docker logs bot-manager 2>&1 | grep -c "auto-recovery attempt" || true
```
Expect: still `"DEAD"`, and attempt-line count `0`. Restore with `POST /$G/start`.

### Phase 4 — end to end, with `BOT_RECOVERY_ENABLED=true`

**V8 — happy path: a DEAD group recovers itself.** Using the legacy group `$G` on the
healthy TIP env:
```bash
curl -sf -X POST https://<bot-1>/api/v1/bot-group/$G/stop -o /dev/null -w '%{http_code}\n'
curl -sf -X PATCH https://<bot-1>/api/v1/bot-group/$G \
  -H 'Content-Type: application/json' -d '{"targetStatus":"DEAD"}' -o /dev/null -w '%{http_code}\n'
# poll for up to 5 minutes:
for i in $(seq 1 60); do
  curl -sf https://<bot-1>/api/v1/bot-group/$G/status | jq -c '{targetStatus, actualStatus}'
  sleep 5
done
```
Expect: within 5 minutes (2 probe ticks for the streak + 1 recovery tick + build time) the
status becomes `{"targetStatus":"ACTIVE","actualStatus":"ACTIVE"}`.
Expect log lines matching, in order:
`^.*group $G .*: auto-recovery attempt 1/6` and
`^.*group $G .*: auto-recovery succeeded — [1-9][0-9]*/[0-9]+ bots up`.
```bash
curl -sf https://<bot-1>/actuator/prometheus \
  | grep 'group_recovery_attempts_total.*outcome="success".*botGroupId="'$G'"'
```
Expect: value `1.0`, carrying `botGroupId`, `environmentId` and `product` tags.

**V9 — no accounts were created and no deposits were made.**
```bash
docker logs bot-manager --since 10m 2>&1 | grep -Ei "register|deposit" | grep "$G" || echo NONE
```
Expect: `NONE`, or only auto-deposit lines if the group has `autoDepositEnabled=true`
(check with `GET /{id}`) — and in that case exactly the same lines a manual `/restart`
produces. Expect **no** user-registration lines: registration lives only in
`BotGroupService.save` and is unreachable from any start path.

**V10 — opt-out: a STOPPED group is never auto-started.**
```bash
curl -sf -X POST https://<bot-1>/api/v1/bot-group/$G/stop -o /dev/null -w '%{http_code}\n'
sleep 360
curl -sf https://<bot-1>/api/v1/bot-group/$G/status | jq -c '{targetStatus, actualStatus}'
docker logs bot-manager --since 7m 2>&1 | grep -c "group $G .*auto-recovery attempt" || true
```
Expect: `{"targetStatus":"STOPPED","actualStatus":"STOPPED"}` after 6 minutes (≥ 6 ticks),
and attempt-line count `0`.

**V11 — exhaustion path: an unrecoverable group gives up and says so.** Create a throwaway
group on the same environment with `existingGroup=true` and a `namePrefix` that does not
correspond to any real account (so every bot fails auth and the zero-bot guard marks it
DEAD), start it, and watch:
```bash
# after the group goes DEAD, over the next ~2 hours:
docker logs bot-manager 2>&1 | grep -E "group <throwaway> .*auto-recovery (attempt|exhausted)"
curl -sf https://<bot-1>/actuator/prometheus | grep 'group_recovery_exhausted_total'
```
Expect: exactly 6 `auto-recovery attempt n/6` lines with the documented gaps —
**five gaps, not six: 2, 5, 15, 30, 60 minutes ≈ 1 h 52 m.** Six attempts have five
intervals between them, and the table's sixth `60` is computed after attempt 6 but never
elapses, because exhaustion fires at that attempt. (`backoff[min(n-1, len-1)]` is correct
and the clamp is unit-tested; it was this step's expectation that was off by one.) Each
attempt is followed by a `failed` WARN, then exactly one line
matching `auto-recovery exhausted after 6 attempts — operator action required`, and
`group_recovery_exhausted_total{botGroupId="<throwaway>"} 1.0`. Expect **no** seventh
attempt over the following 30 minutes. Delete the throwaway group afterwards.
*(Short form if 2 h is not available in the window: temporarily set
`bot.recovery.backoff-minutes=1,1,1,1,1,1` and `bot.recovery.max-attempts=3` via
`BOT_RECOVERY_BACKOFF_MINUTES` / `BOT_RECOVERY_MAX_ATTEMPTS` in `.env` + `docker compose
restart bot-manager`, assert the same shape in ~4 minutes, then restore the defaults and
restart again.)*

**V11b — the alert rules load.**
```bash
curl -sf http://<bot-1>:9090/api/v1/rules \
  | jq -r '.data.groups[].rules[].name' | grep -E 'EnvironmentGroupRecovery'
curl -sf http://<bot-1>:9090/api/v1/rules | jq -r '.data.groups[].rules[] | select(.name=="EnvironmentGroupDead") | .annotations.description'
```
Expect: both `EnvironmentGroupRecoveryExhausted` and `EnvironmentGroupRecoveryFlapping` are
listed; the `EnvironmentGroupDead` description no longer contains the string
`does not recover on its own`.

### Phase 5 — default-on

**V12 — the compiled default is live without an env override.** Remove
`BOT_RECOVERY_ENABLED` from `.env`, `docker compose up -d bot-manager`, then:
```bash
docker logs bot-manager 2>&1 | grep "Dead-group recovery scheduler"
```
Expect one line reporting `enabled=true`. Re-run **V8** once to confirm end-to-end recovery
still works with the flag unset. On prod, run V0a, V0b, V1 and this step only — do **not**
manufacture a DEAD group on a live prod fleet.

---

## Amendment — 2026-09-07 (compliance re-verification)

Two statements in this plan were falsified by the shipped branch, and in both cases the
plan was wrong rather than the code. Recorded here so the releaser and Phase 4 read
something true; the plan text above is left in place with a pointer, not rewritten.

Neither amendment touches AD-5, AD-3's conditions, the attempt budget, the probe
predicate, or any verification step. `stopAndLogout` really is untouched, and the
`stop()` behaviour V6 checks is exactly as Phase 2 specified.

### A1 — Phase 2's "`restart` (`:1009`) is untouched" cannot hold once AD-5 exists

Phase 2 makes `stop()` persist `STOPPED` for a runtime-less group and AD-5 makes
`STOPPED` a **permanent** opt-out. `restart()` is `stop()` then `start()`. So, with
`restart` left untouched, a `/restart` whose start half throws leaves the group parked
`STOPPED` where it used to be left `DEAD` — permanently opted out of auto-recovery,
absent from `findByTargetStatus(DEAD)` and from every dead-group signal that reads the
persisted status, with nothing logged to say so.

That is not a hypothetical corner. Phase 3's exhaustion ERROR tells the operator to run
`POST /api/v1/bot-group/<id>/restart`, and a start failure is the *likely* outcome for a
group whose environment is still sick — so the plan as written disabled recovery on
precisely the button it tells operators to press. The plan asserted an invariant
("nothing else changes") without evaluating it against the meaning AD-5 had just given
`STOPPED`.

**Shipped instead** (`8cccef0`), and accepted:

- `stop(String)` keeps its Phase 2 behaviour and is still the operator entry point. A
  private `stop(String, boolean parkRuntimeless)` overload exists so that
- `restart()`'s internal stop passes `false` and does **not** park a runtime-less group —
  a teardown step is not a statement of intent. That restores pre-Phase-2 behaviour for
  that one caller.
- `restart()` reads the persisted `targetStatus` before its stop, and if the start half
  throws it restores that value — but only when the status is `STOPPED` now and was not
  before. A group the operator had genuinely parked stays parked; an `ACTIVE`, or the
  zero-bot guard's `DEAD`, is left alone because the start path wrote those on purpose.

Net effect on `restart` relative to `main`: unchanged, plus a restore on the failure
path. A failed restart is a no-op on persisted intent, which is what it always looked
like.

### A2 — "the DB self-corrects at the next `stop`/`start`/death" is false for the STOPPED variant

The Implementation Note on `handleBotGroupDeath`'s missing lock reasoned about one
ordering only — a death write landing after a recovery's `ACTIVE` — and correctly said
AD-3 condition 3 covers it. The other ordering is not covered by anything: a health-monitor
tick sitting between its own `findById` and its `save` when an operator's `/stop` lands
leaves Mongo at `DEAD` with **no runtime**. That is a fully eligible recovery candidate for
a group an operator just stopped; it is the one AD-5 hole reachable with no `PATCH` at all;
and no eligibility predicate can see it, because from the predicate's point of view it is
indistinguishable from an ordinary death. It does not self-correct — nothing writes that
group again until a human does.

The race is pre-existing. Only its *consequence* is new, and the new consequence is
autonomous recovery restarting money-spending bots against operator intent, so it is closed
rather than documented.

**Shipped instead** (`f4a436b`), and accepted:

- `handleBotGroupDeath` takes the per-group lock with **`tryLock(2, SECONDS)`, never a bare
  `lock()`**. `stop()` holds that lock while `stopAllBots` awaits the bot executor for up to
  30 s and only then calls `healthMonitor.shutdownNow()`, so an uninterruptible block would
  park the monitor thread behind its own shutdown for that window.
- Failing to acquire is not a lost update. The only other holders of this lock are `start`,
  `startForRecovery` and `stop`, all of which write the group's status themselves; the
  runtime is left untouched, and the 30 s monitor tick re-evaluates. The one non-writing
  holder is `startLocked`'s "already running" no-op, which costs one tick of delay.
- Under the lock the runtime is re-checked for identity against `runningGroups`. That also
  closes an unrelated pre-existing metrics bug: `stopAllBots` credits and closes the
  `groupDeadSince` window *first*, so a straggler tick calling `markAsDead()` afterwards
  re-opened a window nothing would ever credit again.

`runningGroups.put` happens at `BotGroupBehaviorService:473`, long before
`startHealthMonitoring` at `:603` and its 10 s initial delay, so the identity check can
never spuriously reject a live runtime in production.

### Rulings on two judgement calls Dev raised explicitly

- **A successful recovery still charges the attempt budget.** Accepted, and it is what this
  plan already says: AD-8 spends the budget "only on attempts", a success is an attempt, and
  Phase 3's success branch schedules the reset for `+settle-minutes` rather than granting it
  at once. Refunding would make the budget unbounded for exactly the flap it bounds. The
  cost is that the budget can be spent by a success, so the hand-off (one ERROR + one
  `group_recovery_exhausted_total`) is emitted from the exhausted-skip branch as well as
  from `recordFailure` (`4236614`) — it must not be possible to exhaust silently.
- **`handleBotGroupDeath`'s `tryLock`.** Accepted; see A2.
