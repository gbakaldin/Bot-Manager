# Compliance — GATEWAY_REQUEST_BUDGET (Phase 1)

Branch: `feature/gateway-request-budget`
Plan reviewed: `docs/plans/GATEWAY_REQUEST_BUDGET.md` (added by `7c7c3b1`, unmodified since — working tree clean for this file)
Diff reviewed: `git diff feature/dead-group-auto-recovery..feature/gateway-request-budget` (6 commits, `7c7c3b1`..`f3ddb28`, 49 files, +4789/−98)

Verified independently of the dirty working tree: a detached worktree at `f3ddb28` was built
with `mvn -o test-compile` (clean) and the phase's twelve test classes were run there
(58 + 5 classes, `BUILD SUCCESS`, 0 failures). No working-tree file was staged, stashed or
reverted.

## Verdict

**PASS**

Every one of Phase 1's nine numbered changes is implemented, all nine listed tests exist and
pass, and the phase's defining invariant holds: in `observe` **and** in `enforce` the budget
stamps and publishes and nothing else. The nine reported deviations are each either a faithful
reading of the plan's intent or a Spring/Java fact the plan did not have to state; none is a
divergence requiring code correction, and none rests on a falsifiable claim that the plan got
the codebase wrong, so no amendment is issued.

---

## Phase-by-phase

### Change 1 — `bot-api`: `RequestTier`, `GatewayRequestScope`, exception hierarchy (AD-3, AD-8, AD-11)
Status: **implemented**

`com.vingame.bot.common.gateway.RequestTier` declares `ESSENTIAL, PRIORITIZED, DEFAULT` in that
order with no numeric constants. `GatewayRequestScope(botGroupId, botId, BooleanSupplier
cancelled)` is a record with a compact constructor that null-coerces the predicate to
`NEVER_CANCELLED`, plus `forBot` / `registration` / `internal` factories, `isCancelled()` and
`describe()`. `GatewayBudgetException extends BotManagerException` (abstract, carries
`environmentId` + `getType()`) with the three subclasses AD-11 names:
`GatewayBudgetExhaustedException(tier, environmentId, retryAfter)`,
`GatewayCircuitOpenException(environmentId, cfRay, retryAfter)`,
`GatewayRequestCancelledException`. `TYPE` constants are the exact strings AD-11 specifies
("Gateway budget exhausted", "Gateway edge block").

`BotManagerException extends RuntimeException`, so from Phase 3 these propagate unwrapped
through `ApiGatewayClient`'s funnel — checked, and it matters (see Drift note D1).

### Change 2 — `bot-engine` `infrastructure.gateway` package (AD-4, AD-5, AD-20, AD-21)
Status: **implemented**

`GatewayBudget` carries all six methods the plan lists (`execute`, `tryExecute`, `count`,
`reserve`, `cancelScope`, `snapshot`) plus `run`, `runWsUpgrade` and `countsWsUpgrades` (see
deviation 2). `Reservation` and `Snapshot` are nested; `Snapshot.describeForRollup()` owns the
`gateway=W/cap queued=E/P/D circuit=closed` string in one place.

`SlidingWindowGatewayBudget`: `ArrayDeque<Long>` of `nanos` stamps under one `ReentrantLock`;
`prune` drops at `age >= window` (`oldest - cutoff <= 0`), which is the boundary AD-4 and the
window test both want. `execute` is exactly `admit(); call.call()` — no mode branch anywhere in
the class, which is why `enforce` cannot accidentally enforce. `LongSupplier nanos` is the
AD-21 clock seam; `System.currentTimeMillis` appears nowhere.

Meters, all `Tags.of("environmentId", …, "product", …)` explicitly and never via
`BotMetrics.mdcTags()`, pre-registered in the constructor: `gateway_budget_window_requests`
gauge, `gateway_budget_queue_depth{tier}` / `gateway_budget_reserved{tier}` gauges,
`gateway_budget_requests_total{tier,outcome}` (all 3 × 4 = 12 series at zero — the
`initGroupRecoverySeries` lesson applied correctly), `gateway_budget_wait{tier}` timer with the
five SLO buckets AD-20 names. `counter()` throws on an unregistered outcome rather than
growing the label set.

`GatewayBudgetSettings` is a record validating in its compact constructor: window positive,
`0 < hardCap < 1000`, ceilings complete for every tier, `DEFAULT <= PRIORITIZED <= ESSENTIAL <=
hardCap`, non-negative waits, one WARN above 900. It adds one rule AD-5 does not state —
`max-wait=0` (unbounded) is legal only for `ESSENTIAL` — which is a direct consequence of AD-5's
own comment on the `essential.max-wait=0` line and of AD-10's "never block a
message-processor thread". Correct tightening.

`GatewayBudgetMode.parse` throws on an unrecognised value rather than defaulting — right call
for a flag whose failure mode is believing you are protected.

`UnlimitedGatewayBudget` is the fixture pass-through, package-private behind
`GatewayBudget.UNLIMITED`, and deliberately reports `hardCap = 0` so it cannot be mistaken for a
configured budget in a snapshot.

`GatewayBudgetRegistry` is a `@Component`, `forEnvironment` is a `computeIfAbsent`, never
evicted; `snapshotAll()` is present as the plan asks. It adds `find`, `snapshotOrEmpty`, `size`
and `settings()`; `snapshotOrEmpty` is what keeps the rollup line from conjuring a budget (and
thirteen fresh series) as a side effect of being read — a real improvement over what the plan
implied.

### Change 3 — `bot-app`: `GatewayBudgetConfig`, properties, compose
Status: **implemented**

`GatewayBudgetConfig` `@Bean` binds all twelve `bot.gateway.budget.*` values with `@Value`
fallbacks matching AD-5 verbatim, tier keys lower-case for relaxed binding.
`application.properties` carries the AD-5 block with the numbers AD-5 specifies (mode=observe,
window=5m, hard-cap=900, 500/750/900, 30s/10m/0, registration 15m, count-ws-upgrades=true,
block-cooldown=15m). `docker-compose.yml` adds
`BOT_GATEWAY_BUDGET_MODE=${GATEWAY_BUDGET_MODE:-observe}` with the "set it in secrets.env,
never here" note AD-23 requires.

### Change 4 — registry wiring: `EnvironmentClientRegistry`, `EnvironmentClients`, `BotFactory`
Status: **implemented**

`EnvironmentClientRegistry.createClients` resolves `forEnvironment(environmentId, env.getName(),
productCode)` and passes it to both `ApiGatewayClient.init(...)` (new 4th parameter) and
`EnvironmentClients` (new field). `BotFactory.createBot` calls `.setGatewayBudget(
environmentClients.getGatewayBudget())` immediately after `setClients`, as AD-1 specifies —
so a bot's WS upgrades and its HTTP requests cannot land on different budgets.
`EnvironmentClientRegistryBudgetWiringTest` pins same-instance-per-env-id and identity between
client, holder and registry; `ApplicationContextLoadsTest` pins that
`EnvironmentClientRegistry`'s field is the same bean (reflection read).

### Change 5 — `ApiGatewayClient`: one funnel, `(tier, scope)` on every public method
Status: **implemented**

One `private HttpResponse<String> send(RequestTier, GatewayRequestScope, HttpRequest)`; it is the
only `httpClient.send(` in the class (guard test asserts `== 1`). `authenticate`, `getBalance`
and `deposit` all gained `(tier, scope)` and the old signatures are **removed**, not defaulted,
as AD-3 demands. `registerSingleUser` and `setDisplayName` route through the funnel as `DEFAULT`
with a registration scope. The `AuthClient` login is wrapped by the non-HTTP twin
`underBudget(tier, scope, Callable)`, which rethrows `IOException`/`InterruptedException`/
`RuntimeException` unwrapped — "the funnel must not change the exception a caller already
handles" is honoured.

### Change 6 — `Bot`: `connectUnderBudget` and tiered call sites
Status: **implemented**

All three `connect()` sites replaced: `initialize` ESSENTIAL, `restart()` DEFAULT,
`tryReconnectWs` PRIORITIZED. `performReauth` PRIORITIZED. `deposit` PRIORITIZED for both the
deposit and the confirming read. `checkBalance` is `lastFetchedBalance < 0 ? ESSENTIAL :
DEFAULT` — plain `execute`, with `tryExecute`/`balanceReadDeferred` correctly left to Phase 3.
`scope()` is built from `configuration` (null-safe) and `this::isStopped`, with the
Phase-2 second half documented rather than stubbed. `connectUnderBudget` delegates to
`runWsUpgrade`, so the `.connect()` count in `Bot` is exactly 1.

### Change 7 — `EnvironmentProbeScheduler`: `budget.count("ws-probe")`
Status: **implemented** (placement differs harmlessly)

The stamp is taken in `probeTarget`'s per-env loop, i.e. *after* `probe.probe(...)` returns
rather than "before each probe". `EnvironmentWsProbe.probe` is documented and coded to never
throw (every failure is classified into a `ProbeResult`), so no probe can escape the stamp and
`count()` never waits by design — the ordering has no observable consequence in this phase or
Phase 3. See deviation 7 for the multi-environment and `live_sibling` decisions.

### Change 8 — metrics, rollup line, alert rule, one Grafana panel
Status: **implemented**

Metrics as above. `FleetRollupLogger`'s env INFO line gained `, {}` carrying
`budget.describeForRollup()`, read through `snapshotOrEmpty` so an environment with no budget
still renders `gateway=0/900 queued=0/0/0 circuit=closed`. `prometheus/alerts.yml` adds
`GatewayBudgetNearCap` (`gateway_budget_window_requests > 800`, `for: 1m`, `severity: warning`,
`audience: internal`), read bare so the gauge's own labels are the alert's — exactly AD-20.
`AlertRulesAudienceTest` needs no new expectation because it iterates every rule generically
and the new rule declares a valid audience; `AlertRuleMetricsTest` was extended to materialise
the series in the real Prometheus exposition, which is the assertion that actually matters.
One Grafana panel, "Gateway request window vs ceilings (5 min)", with the window plus three
constant threshold series.

### Change 9 — `classifyCreationFailure` gains `"budget"`
Status: **implemented, inert as specified**

`cause instanceof GatewayBudgetException → "budget"`, tested **first** with the reason written
down (the later heuristic matches the substring "token"). The caller unwraps one level of
`CompletionException`, so the arm will fire when Phase 3 starts throwing — on every path
except the login one (Drift note D1).

---

## Tests

All nine of Phase 1's listed tests exist, and all pass at the branch tip:

| Plan test | Status |
|---|---|
| `RequestTierTest` (declaration order) | present — 5 tests, incl. "exactly three tiers" and scope null-tolerance |
| `GatewayBudgetSettingsTest` (monotonic, `>= 1000` rejected, `> 900` warns) | present — 11 tests; the `> 900` case asserts "legal, does not throw" rather than capturing the WARN |
| `SlidingWindowGatewayBudgetWindowTest` (fake clock) | present — 13 tests: stamp, exact-window expiry, shared window, `count()`, failed-call-still-stamped, unwrapped rethrow, zero wait, past-cap burst, WS flag both ways, `tryExecute`, reservation accounting, `snapshot()` shape, pre-registered counters, `UNLIMITED` |
| `GatewayCallSiteGuardTest` (AD-21) | present — 6 tests; comment/literal stripping, anti-vacuity assertions, `new AuthClient(` `== 1` |
| `ApiGatewayClientTierTest` (recording fake budget) | present — 6 tests, one per public method plus the fixture fallback |
| `BotGatewayTierTest` | present — 6 tests: initialize ESSENTIAL (login + upgrade), first read ESSENTIAL vs drift DEFAULT, deposit PRIORITIZED pair, `restart()` DEFAULT, reconnect PRIORITIZED, no-budget bot unchanged |
| `EnvironmentClientRegistryBudgetWiringTest` | present — 4 tests |
| `FleetRollupLoggerTest` (`gateway=`) | present — existing test extended plus a new one spending four requests and asserting `gateway=4/900` |
| `ApplicationContextLoadsTest` | present — bound bean equals `defaults()`, mode is OBSERVE, registry size 0, same registry instance as `EnvironmentClientRegistry` |

One test-scope helper not in the plan: `RecordingGatewayBudget` (the "recording fake
`GatewayBudget`" `ApiGatewayClientTierTest` is specified to use). Correct.

The `SlidingWindowGatewayBudgetWindowTest` case named for the plan's "cancelled-before-admission
is not stamped" bullet deliberately asserts the *opposite* — `aCancelledScopeIsStillAdmitted
InPhaseOne` — with the reason spelled out: with no queue there is nothing to be cancelled *out
of*, so a request that really was sent must really be counted. That is the honest reading of
AD-4 ("cancelled-**while-queued** requests do not count") and the test says in a comment that it
is the assertion Phase 3 must flip. Accepted as written rather than as listed.

---

## The phase's defining invariant: observe mode changes no behaviour

Holds, and holds under `mode=enforce` too.

- There is **no `settings.mode()` branch in `SlidingWindowGatewayBudget` at all** — only in
  `GatewayBudgetRegistry.logStartupPosture`'s WARN. `execute`/`run`/`tryExecute` are
  unconditionally "stamp, then run"; `runWsUpgrade` is "stamp if the flag says so, then run".
  An `enforce` box therefore cannot pace, queue or refuse anything, which is what Phase 1
  requires and what `observeModeAdmitsPastTheHardCap` (1,500 admitted against a cap of 900) and
  `theShippedDefaults`/`ApplicationContextLoadsTest` together pin.
- Exception semantics are preserved: `underBudget` rethrows `IOException`,
  `InterruptedException` and `RuntimeException` unwrapped, and only a checked exception the
  budget layer itself invented would become an `IllegalStateException` — impossible today.
- The new `catch (IOException | InterruptedException)` arm in `authenticate` is unreachable
  today (the library throws only unchecked) and is documented as a Phase-4 landing pad.
- Observable deltas, all intended by the plan: one INFO startup line, the `gateway=` fragment on
  the existing 5-minute env INFO line, the new meter families, one Prometheus rule, one Grafana
  panel, one DEBUG line per admitted request (MDC-tagged, detail track only, so it obeys the
  tier rule).
- Cost added on the hot path is one `ReentrantLock` acquisition, a deque append, a `Timer.record(0)`
  and a `Counter.increment()` per request, per environment. Not a behaviour change.

**One genuine behaviour change rides in the branch and it is not the budget's**: the RIK hunk in
`ApiGatewayClient` (see Provenance below) both accepts `EXISTED` as "name taken" and demotes the
already-taken log line from WARN to DEBUG. Recorded as permitted, not counted against the
invariant, but it is the one thing in this diff that a reader checking "observe changes nothing"
will trip over.

## Phase boundary

Respected. Nothing from Phase 2, 3 or 4 is present:

- No `StartAttemptRegistry`, `startAsync`/`restartAsync`, `ApplicationReadyEvent`,
  `startup-chain`, `STARTING`, `startAttempt`, `StartAttemptDTO`, 202. `BotGroupController` is
  untouched; `BotGroupBehaviorService`'s only changes are the `"budget"` classifier arm and two
  javadoc sentences (AD-2's "this bounds concurrency, not rate").
- No waiter queues, `admitWaiters`, per-tier admission arithmetic, `Retry-After` computation, or
  `RestExceptionHandler` arms. `reserve` is *not called* from any production path (the only
  `.reserve(` in `main` is `BetCoordinator`'s, unrelated).
- No `CloudflareBlockDetector`, no circuit state machine, no `reportEdgeBlock`, no clearance
  probe, no `StubGateway`. `GameMsClient` untouched; login still goes through the library.

The three deferred seams are seams, not dead weight:

| Seam | State | Verdict |
|---|---|---|
| `reserve()` | live and correct — `TrackedReservation` is idempotent, drives `gateway_budget_reserved{tier}`, tested; no production caller | seam. The gauge is real rather than a hardcoded zero, which is what makes Phase 3's reservation arithmetic observable the day it lands |
| `cancelScope()` | DEBUG-logging no-op with a comment explaining that the *call-site ordering* (Phase 2) must exist before the queues (Phase 3) | seam. Phase 2 can write `stop()`'s cancel-before-lock ordering against a real method |
| `Snapshot.circuitOpen` | `AtomicBoolean` never set; no setter exists | seam. It is read into the snapshot and rendered on the rollup line, so `circuit=closed` is a real field an operator can rely on being there from day one rather than a format change in Phase 4 |

## Verification section achievability (Phase 1: V0a–V0c, V1a–V1e)

Achievable against this diff; the greps match character-for-character.

- **V1a** greps `Gateway budget registry started \(mode=observe` and expects the line to carry
  `hard-cap=900, ceilings default=500 prioritized=750 essential=900`. The `@PostConstruct` line
  renders `Gateway budget registry started (mode=observe, window=PT5M, hard-cap=900, ceilings
  default=500 prioritized=750 essential=900, count-ws-upgrades=true)`. `describeCeilings()`
  walks the tiers in reverse declaration order explicitly (not map order) to produce that
  substring. ✓
- **V1b** — `gateway_budget_window_requests` and `gateway_budget_requests_total{tier,outcome}`
  exist with `environmentId` and `product` labels; a 50-bot start at `count-ws-upgrades=true`
  costs 50 logins + 50 upgrades + 50 first reads = 150, inside the stated 100–160. `/start` is
  still synchronous, so the expected `200` is right. ✓
- **V1c** — `gateway=<n>/900 queued=0/0/0 circuit=closed` is the exact output of
  `describeForRollup()` and is asserted in `FleetRollupLoggerTest`. ✓
- **V1d** — rule name `GatewayBudgetNearCap` present. ✓
- **V1e** — the timer is `gateway_budget_wait` with SLO buckets, exposed as
  `gateway_budget_wait_seconds_max`; `admit()` records `0` nanoseconds on every admission, so the
  max is 0 and `< 0.01` holds — and the series exists from the first request rather than only
  once something goes wrong, which is what makes the check meaningful. ✓
- **V0b** (Phase 1 form) — `Bot Manager startup complete` is unchanged; `onStartup` is still a
  `@PostConstruct`. ✓

---

## The nine reported deviations

**1. `@Autowired` on `GatewayBudgetRegistry` (two constructors).** Faithful, and mandatory.
AD-21 asks for the clock seam; a second constructor without `@Autowired` makes Spring look for a
no-arg constructor and fail refresh. The plan never said otherwise, so there is nothing to
amend. The javadoc naming the 2026-08-18 `VipTalkClient` crash-loop is the right place for it.

**2. `runWsUpgrade(...)` added to the interface.** Faithful reading of intent, and the better
design. AD-3's `count-ws-upgrades` knob and Open Item 1 exist precisely so the answer can be
flipped in one place; the alternative — a `countsWsUpgrades()` test at the three `connect()`
sites, or worse at `Bot.connectUnderBudget` — would put the policy in the engine. It also keeps
`Bot`'s `.connect()` count at 1, which the guard test needs. Note the Phase-3 consequence in D2.
`run` and `countsWsUpgrades()` are the same category (a `Runnable` twin for a call that throws
nothing checked, and the read the WS test needs).

**3. `GatewayBudgetSettings.defaults()` + the `ApplicationContextLoadsTest` equality
assertion.** Faithful and well-motivated: the plan really does put the shipped numbers in
`application.properties` *and* in `GatewayBudgetConfig`'s `@Value` fallbacks, and a box whose
real ceiling is not the one AD-5 reasoned about is a box that can still be blocked. Asserting
the **bound** bean against the constant is the only way to catch drift at build time. See D5 for
the Phase-5 cost this creates.

**4. WARN at startup when `mode=enforce` before Phase 3.** Faithful, and arguably required by
AD-23 read honestly. AD-23 says "Phase 1 ships observe-only"; an operator who sets
`GATEWAY_BUDGET_MODE=enforce` on a Phase-1 box gets no pacing, and the only alternative to a
WARN is finding out from a Cloudflare block page. It is one line per JVM, so tier-1 admissible.

**5. Validation throws from the record constructor, not from the `@Configuration`.** Not a
deviation at all: AD-5's prose says "`IllegalStateException` from the `@Configuration` that
builds `GatewayBudgetSettings`" and Phase 1's change item 2 says "Settings validated in the
constructor (AD-5)". The record constructor runs *inside* the `@Bean` method, so the exception
does come out of the `@Configuration` and does fail context refresh. Both statements are
satisfied by one implementation; nothing to amend.

**6. `setDisplayName`'s scope carries the username, not the prefix.** Faithful. The claim is
checkable and true: `setDisplayName(String username, String sessionToken, String displayName)`
and `setDisplayNameWithRetry(username, sessionToken, maxRetries)` are public and never receive
`userNamePrefix`; only `registerUsers`' lambda has it. Threading it in would mean changing two
public signatures for a value used solely in a DEBUG line — and the username is strictly more
useful there, since `update-fullname` failures are diagnosed per account. AD-3 itself only says
"`DEFAULT`: `registerSingleUser`, `setDisplayName`"; `(prefix)` is a parenthetical in the change
list. `GatewayRequestScope.registration`'s javadoc documents the split ("the username prefix at
`register.aspx`, the full username at `update-fullname.aspx`"), which is the right place for it.
No behavioural difference either way: a registration scope carries no `botGroupId` and so is not
cancellable by group regardless.

**7. The probe charges every environment on a shared socket URL, and nothing for
`live_sibling`.** Both correct, and better reasoned than the plan. The plan's inventory row #9
assumes one environment per probe; `buildTargets` de-duplicates by `webSocketMiniUrl`, so a
target really can carry several environments that may sit behind different API hosts — there is
no single "right" budget, and over-counting makes the window read fuller than it is, never
emptier, which is the safe direction for a cap. Not counting a `live_sibling` short-circuit is
simply true (AD-10 there means no request leaves the JVM), and AD-4's "probes count" is about
requests, not about ticks. Carries a small Phase-3 consequence, D4.

**8. No counter for `count(reason)`.** Faithful. AD-20's meter list has no per-`count` counter,
and `gateway_budget_requests_total`'s `outcome` label set is bounded to
`admitted|timeout|cancelled|circuit_open` — a probe was never *admitted*, so putting it there
would make the counter disagree with the window, which Implementation Note 2 forbids. Probe
volume is already covered by `env_ws_probe_total`. Correct to leave it at the window gauge plus
a DEBUG line.

**9. `GameMsClient` allow-listed in `GatewayCallSiteGuardTest`.** Faithful. AD-21's rule is "no
production class … constructs a `java.net.http.HttpClient` **pointed at a gateway**";
`GameMsClient` is the gamems host, and the plan's own Findings and Open Item 12 record it as
having no live callers. The allow-list entry carries that reason plus "if it is ever revived
against a gwms host it must be routed through the budget", which is exactly the note a future
reader needs. `HttpPrometheusQueryClient`/`VipTalkClient` are allow-listed on the same grounds
the plan states ("other hosts").

---

## Drift

None requiring correction. What follows is what **Phase 2 and Phase 3 now inherit that the plan
does not yet say** — recorded here, not fixed, because none of it is in Phase 1's scope and none
of it is wrong today.

**D1 — the highest-value one. `ApiGatewayClient.authenticate`'s existing `catch (RuntimeException
e)` arm will swallow the type AD-9 depends on.** `GatewayBudgetException extends
BotManagerException extends RuntimeException`, and `underBudget` correctly rethrows it
unwrapped — straight into:

```java
} catch (RuntimeException e) {
    metrics.incLogin(false);
    throw new UpstreamLoginException("Login failed for user '" + … + "': " + e.getMessage(), e);
}
```

From Phase 3 that means, on the **login** path only:
- `performReauth` receives an `UpstreamLoginException`, never a `GatewayBudgetException`, so
  AD-9's "a budget outcome must not mark the bot DEAD" can never fire;
- `classifyCreationFailure` takes the `UpstreamLoginException` arm and tags `"auth"` — precisely
  the outcome the new `"budget"` arm's own comment says must not happen ("would … make
  `EnvironmentLoginFailing` fire on our own throttling");
- `bot_login_total{outcome="failure"}` counts our own pacing as upstream login failures, and
  that counter is V4c's per-brand regression gate.

Phase 3 needs a `catch (GatewayBudgetException e) { throw e; }` arm ahead of the
`RuntimeException` arm (and must decide whether `incLogin(false)` should fire at all — a request
never sent is not a failed login). Phase 3's change list item 3 currently says only
"`performReauth` / `deposit` non-terminal handling of `GatewayBudgetException` (AD-9)", which
reads as a change in `Bot` and is not sufficient.

`getBalance` has a milder version: its `catch (RuntimeException e)` rethrows the type unwrapped
(good) but increments `bot_verifytoken_total{failure}` on the way past. `deposit` catches only
`IOException`/`InterruptedException`, so the type reaches `Bot.deposit` cleanly — the plan
already covers that site.

**D2 — Phase 3 must add enforcement to `run` and `runWsUpgrade`, not only to `execute` and
`tryExecute`.** The plan's Phase-3 list names "`tryExecute` semantics" and the three queues.
Every WebSocket upgrade in the fleet goes through `runWsUpgrade`; a Phase 3 that only queues
inside `execute` would stamp upgrades while never pacing or refusing them, and AD-8's
"cancellation must happen before `connect()` is entered" would have no hook.

**D3 — `count-ws-upgrades=false` currently bypasses the budget entirely, not just the stamp.**
`runWsUpgrade` runs the upgrade without calling `admit` at all when the flag is off. That is
right for counting, and it means that if Open Item 1 is answered "no, WS hosts are not behind the
rule", WS upgrades also become un-cancellable and un-refusable under enforce. Whether "not
counted" should imply "not budgeted" is a decision Phase 3 has to make explicitly; Open Item 1
only asks the counting half.

**D4 — under enforce, the probe's deliberate over-count consumes real ceiling on shared-URL
environments.** `W` is what admission compares against, so an environment that never sent the
probe still has its window charged. Bounded at roughly one stamp per environment per 60 s tick
(≈ 5 per window, ≈ 0.6% of the cap) and only while a DEAD group is a candidate, so it is
negligible — but it is a real coupling between environments that AD-1's "two environments on
different hosts must not throttle each other" does not anticipate.

**D5 — Phase 5's default flip is now a three-file edit gated by an equality assertion.**
`ApplicationContextLoadsTest` asserts `boundSettings.equals(GatewayBudgetSettings.defaults())`,
so flipping the compiled default to `enforce` (Phase 5 change 3) requires editing
`application.properties`, `GatewayBudgetConfig`'s `@Value` fallback **and** `defaults()`, or the
build fails. That is the assertion working as designed, and the same applies to answering Open
Item 5 by setting `essential.ceiling=850`. Worth writing into Phase 5's change list so the
Releaser is not surprised.

**D6 — smaller notes.** `EnvironmentProbeSchedulerTest` was wired with a real
`GatewayBudgetRegistry` and a comment saying "the point of interest is that it does so only when
a request actually went out", but no test asserts the stamp or the `live_sibling` exemption. Not
a plan requirement (the plan lists no probe test), so not a failure — but the comment promises
more than the test delivers, and a Phase-3 regression there would be silent.
`ApiGatewayClient` keeps a package-private three-argument `init(...)` fixture seam whose
misuse is caught only by a source assertion on the one production call site; Phase 4's stub
tests will want it, so it should stay, with that guard.

**D7 — what remains open, and in what form.** All five open items the task asks about are still
open; two are cheaper to answer now and one is slightly dearer:

1. *Do WS upgrades count?* Open, unchanged as a question (SA has not answered). Cheaper to act
   on: `runWsUpgrade` + `bot.gateway.budget.count-ws-upgrades` makes the flip one property, no
   rebuild — but see D3 for the second half of the decision that now comes with it.
2. *`STARTING` vs additive `startAttempt`.* Open, entirely untouched. Still a pre-Phase-2 UI
   contract decision.
3. *202 + JSON on `/start`/`/restart`.* Open, entirely untouched — `BotGroupController` is not in
   this diff and `/start` still returns 200 (which is what V1b relies on).
4. *Synchronous registration.* Open, unchanged. One thing ASYNC_REGISTRATION now inherits that
   Open Item 4 does not list: a registration scope deliberately carries **no `botGroupId`**, so
   registration is not cancellable via `cancelScope` and an async registration job will have to
   supply its own cancellation key. `bot.gateway.budget.registration.max-wait` is bound and
   validated but unused until Phase 3.
5. *`essential.ceiling` at the cap vs 850.* Open, unchanged as a question; shipped at 900 in
   properties, `@Value` fallback and `defaults()`, so answering it is now a three-place edit (D5).

## Out-of-scope changes

Only the RIK hunk below. Everything else in the diff maps to a numbered Phase 1 change, a listed
test, or a mechanical fixture update forced by a changed signature (`EnvironmentClients`'
constructor, `EnvironmentClientRegistryTest`, four `BotFactory*Test`s, six `Bot*Test`s,
`FleetRollupLoggerTest`, `EnvironmentProbeSchedulerTest`, `AlertRuleMetricsTest`,
`BalanceGaugeSemanticsTest`, `PerBotInitLogLevelTest`). No unrelated refactors, no drive-by
renames, no touched production file without a stated reason.

## Provenance note (not a compliance failure)

`ApiGatewayClient.isDisplayNameTaken(status)` — accepting `EXISTED` alongside `INVALID` so the
RIK/P_114 gateway's name collisions re-roll instead of aborting `setDisplayNameWithRetry`, plus
the WARN→DEBUG demotion of the "already taken" line — belongs to the in-flight RIK/ziczac
feature, not to this phase. It rides in because `ApiGatewayClient` had to be edited for the
funnel and the file was committed whole. Explicitly permitted; recorded so that whoever lands
the RIK work knows this branch already contains it and does not double-apply it.

Two commits on this branch (`831e311`, `f3ddb28`) exist solely to *remove* other inherited RIK
edits — `BotFactory`'s `.forGame(game)` call and a test assertion — whose declarations live in
the still-uncommitted `bot-api/GameMessageTypes` change, so the branch tip did not compile in
isolation even though the working tree built green. Verified fixed: a detached worktree at
`f3ddb28` compiles main and test sources cleanly with no access to the working tree. The
uncommitted RIK edits remain in the working tree, untouched by this review.

## Amendments to the plan

None. No deviation rested on a falsifiable claim that the plan misread the codebase or an
external system; the closest candidate (deviation 5) is a case where the plan's two statements
are both satisfied by one implementation. The five new constraints in the Drift section are
inheritances for Phases 2–5 to absorb into their own change lists, not corrections to decisions
already made.
