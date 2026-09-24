# Code Review — GATEWAY_REQUEST_BUDGET (Phase 1)

Branch: `feature/gateway-request-budget`
Reviewed diff: `git diff feature/dead-group-auto-recovery..HEAD` (6 commits, `7c7c3b1`..`f3ddb28`)

## Verdict

PASS

No `bug` and no `security` findings. The observe-mode invariant holds, the tier
assignment matches the plan's inventory table at every one of the nine call sites, the
sliding window is correctly synchronised, and the counters are pre-registered at zero.
Everything below is a smell or a style point; two of them (F1, F6/F7) are worth acting on
before the Releaser builds this.

## Findings

### [smell] F1 — The test that pins the whole phase's invariant is not on the branch
`bot-engine/src/test/java/com/vingame/bot/infrastructure/gateway/SlidingWindowGatewayBudgetObserveModeTest.java` (untracked)

`git status` shows this file as `??`. It is 14.8 KB, timestamped `12:17`, eleven minutes
after the last commit (`f3ddb28`, `12:06`), and its own javadoc names it correctly:

> *"Phase 1 cannot block, delay, refuse or reorder anything — in either mode. This is the
> single most important property of the whole phase … `mode=enforce` is included
> deliberately."*

It carries `@Timeout(30, SECONDS)`, drives 1,500 requests on a frozen clock through every
entry point, and covers exactly the two things this review was asked to weigh hardest:
that nothing parks, and that `mode=enforce` does not half-enforce. None of that is on the
branch. What *is* committed —
`SlidingWindowGatewayBudgetWindowTest.aBurstFarPastTheHardCapIsAdmittedInObserveMode` —
covers the burst for `execute` in `OBSERVE` only; it never constructs an `ENFORCE` budget
and has no timeout, so a future change that made an admission park would hang CI rather
than fail it.

The distinction matters because it is precisely the split `f3ddb28`'s own commit message
sets up: "the first is what the Releaser builds, the second is what QA and the reviewers
read". Here the two disagree, and the direction of the disagreement is that the Releaser
builds *without* the invariant test.

Fix shape: commit the file. It is Dev's own Phase-1 artefact, it references nothing from
the uncommitted RIK/Aviator work (it imports only `bot-api` gateway types, Micrometer's
`SimpleMeterRegistry` and JUnit), and it is the one file in the working tree that
unambiguously belongs to this branch rather than to someone else's.

### [smell] F2 — `count()` ignores `count-ws-upgrades`, so the anonymous WS probe is charged even when WS upgrades are declared not to count
`bot-engine/src/main/java/com/vingame/bot/infrastructure/gateway/SlidingWindowGatewayBudget.java:238-243`
`bot-app/src/main/java/com/vingame/bot/infrastructure/probe/EnvironmentProbeScheduler.java:267-284`

`runWsUpgrade` gates its stamp on `settings.countWsUpgrades()` — the correct answer to
Open Item 1, and the reason the flag lives in the budget rather than at three `connect()`
sites. But `EnvironmentWsProbe.probe` is *also* a WebSocket upgrade (against
`Environment.webSocketMiniUrl`, `EnvironmentWsProbe.java:150`), and it reaches the window
through `count("ws-probe")`, which never consults the flag.

So an operator who learns from SA that the WS hosts are *not* behind the
`/gwms/v1/*` rate-limit rule, and sets `bot.gateway.budget.count-ws-upgrades=false`
accordingly, stops charging ~N-per-group-start real WS upgrades and keeps charging the
probe's. From Phase 3 that is window room taken from real API requests for traffic the
edge does not count.

The magnitude is small — one stamp per environment per 60 s tick, and only while a DEAD
group is a recovery candidate, so ≤5 per window — which is why this is a smell and not a
bug. But it is the same decision, and the flag's whole justification is "one flag, in one
place, rather than a condition duplicated". Fix shape: either give `count` a
`boolean isWsUpgrade` (or a second method `countWsProbe(reason)`) that consults the same
setting, or state on `count`'s javadoc that probes are counted unconditionally and why.
Note that Phase 4's `count("circuit-probe")` is an HTTP GET, not a WS upgrade, so the two
callers of `count` genuinely differ and cannot share one rule silently.

### [smell] F3 — `count()` increments no `requests_total` series, so the counter and the window provably cannot reconcile
`SlidingWindowGatewayBudget.java:238-243`, against the class javadoc at `:40-45`

The class javadoc states the invariant as a requirement:

> *"The `gateway_budget_requests_total` counter and `gateway_budget_window_requests` gauge
> must agree on that, or the dashboard lies about the one number this feature exists to
> bound."*

`count()` stamps the window and increments nothing, so
`sum(gateway_budget_requests_total{environmentId=E})` is structurally below
`gateway_budget_window_requests{environmentId=E}` by the number of probes, permanently.
`admit()` is the only incrementer, and `OUTCOMES` has no value a probe could carry.

Today this is recoverable from `env_ws_probe_total` (the probe has its own counter) and
the discrepancy is ≤5/900. From Phase 4 it is not: the half-open clearance probe
(`count("circuit-probe")`) has no counter anywhere, and it is the one request an operator
will want to account for when reasoning about why a circuit stayed open. Fix shape: add a
fifth bounded outcome (`outcome="counted"`, pre-registered at zero like the rest) and
increment it from `count`, or narrow the javadoc claim to `outcome="admitted"` only and
say where probe stamps are accounted instead.

### [smell] F4 — The enforce-mode "not actually enforcing" WARN has no build-time expiry
`bot-engine/src/main/java/com/vingame/bot/infrastructure/gateway/GatewayBudgetRegistry.java:88-92`

The WARN is the right call — rejecting `mode=enforce` outright would crash-loop a box
whose operator set the variable pre-emptively, and silently accepting it is the exact
failure this feature exists to prevent. But nothing makes it go away. It is not in the
plan's Phase 3 change list (`GATEWAY_REQUEST_BUDGET.md:582-599`), there is no test
asserting its presence that Phase 3 would have to update, and no `TODO` a grep would find.

Left in place after Phase 3, an enforce box logs `NOTHING is being paced, queued or
refused` at WARN once per JVM while it is in fact pacing — a tier-1, Loki-visible,
actively false statement about the posture of a production instance, which is worse than
the silence it replaced. Fix shape: pin it with an assertion in the same test that asserts
the startup line (e.g. a `GatewayBudgetRegistryStartupTest` capturing the appender), so
Phase 3 cannot ship without touching it; or at minimum reference "remove in Phase 3" in
`GATEWAY_REQUEST_BUDGET.md`'s Phase 3 change list so the next Architect inherits it.

### [smell] F5 — The Grafana panel and the near-cap alert hard-code the ceilings, and one field override matches no target
`grafana/provisioning/dashboards/per-environment.json` (panel `id: 13`)
`prometheus/alerts.yml` (`GatewayBudgetNearCap`)

Two concrete problems in one place:

1. The panel declares a `byName: "ESSENTIAL ceiling"` override with a dotted line style,
   but there is **no target producing that series** — targets are `A` (the gauge),
   `B` = `900` "hard cap (900)", `C` = `750` "PRIORITIZED ceiling", `D` = `500`
   "DEFAULT ceiling". The panel description nonetheless promises "the app's hard cap and
   the **three** per-tier ceilings". It reads as correct today only because
   `essential.ceiling == hard-cap` by default; the moment Open Item 5 is answered with
   `essential.ceiling=850`, the ESSENTIAL ceiling is invisible on the one panel built to
   show it, and the leftover override still matches nothing.

2. `900` / `750` / `500` are Grafana literals and `800` is an alert literal.
   `GatewayBudgetSettings.defaults()` exists precisely so the *three* copies of these
   numbers (properties file, `@Value` fallbacks, `defaults()`) cannot drift, and
   `ApplicationContextLoadsTest.gatewayBudgetIsWiredAndObserveOnly` pins that equality.
   The dashboard and the alert are copies four and five, and nothing pins them. A box that
   relaxed-binds `BOT_GATEWAY_BUDGET_TIER_DEFAULT_CEILING` gets a dashboard whose
   reference lines are wrong, silently, on the panel it would consult during an incident.

Fix shape: drop the dead override (or add the missing `E: 900` target labelled
`"ESSENTIAL ceiling"`), and consider exporting the settings as a `gateway_budget_ceiling{tier=...}`
gauge from `registerMeters` so the panel plots the *configured* ceilings rather than three
literals. That also gives `GatewayBudgetNearCap` a way to express "800" as
`0.89 * hard-cap` instead of a magic number.

### [smell] F6 — An unrelated RIK behaviour change rides in the branch, and it is two changes, not one
`bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:481-484, 500-514`
(commit `888d890`)

The `isDisplayNameTaken` extraction is, on its own merits, a good change and a genuine
bug fix: `EXISTED` previously fell through to
`throw new RuntimeException("Failed to set display name: …")`, which propagates out of
`setDisplayName` and past `setDisplayNameWithRetry`'s loop (`:531-543`, which only re-rolls
on a `false` return), so the first collision on a RIK gateway aborted the retry budget
entirely. Extracting a named, `static`, unit-testable predicate with the incident in the
javadoc is the right shape, and the `||` is correctly ordered so `INVALID` behaviour is
byte-identical.

Two things to flag:

- **Provenance.** It is in commit `888d890` ("route every gateway call site through the
  budget"), i.e. it landed as a side effect of Dev having to edit the same file to insert
  the funnel — the same entanglement `831e311` and `f3ddb28` were written to undo, and
  those two commits set the precedent that such hunks get restored to the pre-RIK base and
  left in the working tree. This one was not. The Releaser needs to know that this branch
  changes display-name retry behaviour on every brand, not just RIK, and that the RIK
  author's own branch may now conflict on this method.
- **It is two behaviour changes.** The same hunk demotes
  `log.warn("Display name '{}' is already taken")` to
  `log.debug("… ({})", displayName, status)`. The demotion is defensible under
  CLAUDE.md's tier rule (the line's rate is per registration attempt, so WARN was wrong),
  and `setDisplayNameWithRetry` still logs ERROR after exhausting its five attempts — but
  it is undeclared, it is not what the commit message says, and it removes the only
  default-visible signal of a collision storm mid-registration. If it is intended, say so;
  if it is incidental, it should be a separate commit with the tier rationale.

### [smell] F7 — Untracked RIK test files were adapted to the new API, so this branch's shape now leaks into someone else's uncommitted feature
`bot-app/src/test/java/com/vingame/bot/domain/bot/service/BotFactoryForGameResolutionTest.java:153-158` (untracked)

That file is `??` in `git status` — it belongs to the in-flight RIK/ziczac work — and it
now reads:

```java
when(apiGatewayClient.authenticate(any(), any(), any())).thenThrow(new AuthSentinel());
when(clientRegistry.getClients("env-114")).thenReturn(new EnvironmentClients(
        env.getId(), apiGatewayClient, mock(GameMsClient.class),
        mock(ClientFactory.class), env, GatewayBudget.UNLIMITED));
```

Both the three-arg `authenticate` stub and the sixth `EnvironmentClients` constructor
argument are this branch's API. Keeping the working tree compiling was necessary and
nothing was staged, stashed or reverted, so the instruction was honoured — but the
consequence is the mirror image of what `831e311`/`f3ddb28` fixed: when the RIK feature is
committed, these gateway-shaped edits ride into *its* commit, and if this branch is
reverted or lands after RIK, that untracked file no longer compiles against either tree.

Not a code defect; a hand-off item. Fix shape: name the file (and the exact lines) in the
release notes so whoever lands RIK knows which of its edits are not its own, exactly as
`831e311`'s message did in the opposite direction.

### [smell] F8 — A dead `init` overload gained a parameter instead of being deleted
`bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:152-158`

`init(String apiGateway, String appId, Function<AuthContext, ? extends LoginRequest>, GatewayBudget)`
— the "backward-compatible overload" — has **no callers anywhere** in `src/main` or
`src/test` of any module. It was already dead before this branch; the change threads a
`GatewayBudget` through it, which makes it look live to a reader and gives future callers
a route that hard-codes `/user/login.aspx` and a null `xToken`.

The plan is explicit in the other direction (AD-3: *"The old signatures are removed, not
defaulted — a caller that does not say its tier is a bug the compiler should find"*), and
the same commit correctly narrowed the three-arg `init(…, AuthProfile)` to package-private
with a "not for production" javadoc, so the instinct is clearly there. Fix shape: delete
it. If it is being kept deliberately for some external caller, that belongs in its javadoc.

### [style] F9 — Dead local in `admit()`, and `reserve()` silently discards its `scope`
`SlidingWindowGatewayBudget.java:221-236`, `:247-254`

- `long waited = 0L;` is written, never assigned again, and passed to
  `waitTimers.get(tier).record(waited, NANOSECONDS)`. Recording a zero is deliberate and
  documented (it gives V1e a baseline series), but the variable is noise — `record(0L, …)`
  with the existing comment says the same thing.
- `reserve(tier, permits, scope)` logs `scope` and then drops it. AD-7 makes the scope's
  `botGroupId` the *consumption key* ("acquisitions whose scope carries the same
  `botGroupId` and tier consume it"), so the parameter that Phase 3's arithmetic hangs off
  is the one piece of state `TrackedReservation` does not retain. Since nothing calls
  `reserve` in Phase 1 this costs nothing today; it does mean the seam is thinner than it
  looks. Worth one line in the javadoc noting that the scope is recorded for the log only
  and that Phase 3 has to store it.

Both are justified-Phase-3-seam territory rather than speculative weight —
`reserve`'s live gauge accounting and `cancelScope`'s logged no-op both earn their place
(the gauge is real rather than a hardcoded zero; the call-site *ordering* `cancelScope`
protects is introduced in Phase 2, before the queues arrive in Phase 3, and that is
correctly explained in the method body). `Snapshot.circuitOpen` always-false is likewise
the right call: it keeps the rollup fragment's shape constant from day one, which is what
makes `V1c`'s grep stable across three phases.

### [style] F10 — `Snapshot`'s javadoc over-claims consistency
`GatewayBudget.java:186`

> *"Everything an operator-facing line or a gauge needs, taken as one consistent read."*

`SlidingWindowGatewayBudget.snapshot()` (`:283-296`) calls `windowRequests()` (which takes
and releases the lock), then reads three `AtomicInteger`s and one `AtomicBoolean` outside
it. There is no single consistent read, and from Phase 3 a snapshot can show a window
count from before an admission next to a queue depth from after it. Harmless for a 5-minute
rollup line; the claim is what should change, not the code.

### [style] F11 — Small documentation and hierarchy nits on the four new exceptions
`bot-api/src/main/java/com/vingame/bot/common/exception/`

The hierarchy is correct and correctly reasoned: `GatewayBudgetException extends
BotManagerException` (which `extends RuntimeException`, so it stays *unchecked* and
therefore passes cleanly through `ApiGatewayClient.underBudget`'s
`catch (… | RuntimeException e) { throw e; }` rather than being wrapped as the
`IllegalStateException` that the residual arm produces — I checked this specifically,
because a checked base would have silently broken AD-9 in Phase 3). Deliberately *not*
`UpstreamGatewayException` is the right call and the javadoc gives the reason. The four
types are also a defensible divergence from `EXCEPTION_HIERARCHY_MIGRATION` AD-1's "no new
subtypes": that pass's own criterion was "a new type is only justified when several throws
share a category needing a *distinct HTTP status*", and 429/503 are exactly that. None of
the four can surface in Phase 1 — nothing constructs them anywhere in `src/main`.

Nits:

- `GatewayBudgetExhaustedException.getRetryAfter()` javadoc reads *"Seconds until the
  earliest window slot frees up"* but the method returns a `Duration`. Same on
  `GatewayCircuitOpenException`.
- `GatewayBudgetException` has no `(environmentId, message, cause)` constructor. Phase 4's
  detector will want to attach the `WebSocketClientHandshakeException` (or the
  `JsonParseException` the block page currently produces) as a cause on
  `GatewayCircuitOpenException`; adding the protected constructor now is free.
- `GatewayRequestCancelledException`'s javadoc says "This never reaches REST", which is a
  statement about call sites rather than a property of the type. If it ever did, it would
  land on `RestExceptionHandler`'s terminal `Exception`→500 arm, not on a 4xx. Worth
  saying, since the sibling types document their status explicitly.

### [style] F12 — `prune`'s cutoff arithmetic is not `nanoTime`-overflow-safe
`SlidingWindowGatewayBudget.java:256-263`

`long cutoff = now - settings.window().toNanos();` then `oldest - cutoff <= 0`. The
*comparison* is written in the overflow-safe subtraction form (good, and the right
instinct), but computing `cutoff` first reintroduces the wrap it avoids: if `nanoTime()`
ever returned a value within one window of `Long.MIN_VALUE`, `cutoff` overflows and the
comparison inverts. HotSpot on Linux returns `CLOCK_MONOTONIC` since boot, so this cannot
happen on the deployment target, and the injected test clock starts at 0 — hence style, not
bug. The allocation-free form is `now - oldest >= windowNanos`, which needs no `cutoff` at
all.

## Notes

**What is right, and worth not regressing.**

- **The tier audit passes at all nine inventory rows.** Login `ESSENTIAL`
  (`Bot.java:337`); re-auth `PRIORITIZED` (`:841`); register `DEFAULT`
  (`ApiGatewayClient.java:421-425`); display name `DEFAULT` (`:470-474`); first balance
  read `ESSENTIAL` / drift `DEFAULT` (`Bot.java:496`); deposit and its confirming read
  `PRIORITIZED` (`:461-470`); the three WS upgrades `ESSENTIAL` / `DEFAULT` /
  `PRIORITIZED` (`:348`, `:429`, `:912`); probe `count("ws-probe")`. I verified the two
  that were easiest to get wrong. The first-read `ESSENTIAL` branch keys on
  `lastFetchedBalance < 0`, and the field really is seeded at `-1` (`Bot.java:144`) — had
  it been `0`, the start-path read would have been tagged `DEFAULT` and would be the first
  thing Phase 3 times out, producing exactly the silent-zombie failure the plan warns
  about. And `restart()`'s `DEFAULT` is safe because `bot.restart()` has exactly one
  caller in production, the periodic-logout path
  (`BotGroupBehaviorService.java:2484`) — it is not reachable from `/restart`, which goes
  through `stop` + fresh `initialize`.
- **No path reaches a gwms gateway around the facade.** Grepped every
  `httpClient.send(` / `HttpClient.newBuilder()` / `newHttpClient()` / `.connect()` in all
  five modules' `src/main`. The residue is `GameMsClient` (dead, different host),
  `VipTalkClient` and `HttpPrometheusQueryClient` (different hosts), and
  `EnvironmentWsProbe` (counted). `GatewayCallSiteGuardTest` is a genuinely good guard —
  it strips comments and literals before scanning, carries anti-vacuity assertions so it
  cannot pass on an empty read, and its allow-list has a written reason per entry. Note it
  catches the `::connect` method-reference spelling, which is what the new code actually
  uses.
- **The concurrency choice is right for this codebase.** `ReentrantLock` and not
  `synchronized`: on JDK 21 a `synchronized` block around blocking work pins the carrier
  thread, and this lock is taken by every gateway request of every bot on an environment.
  The critical section is `prune` + `addLast` with no I/O, no logging and no callbacks
  inside it, so nothing can park a carrier. `windowRequests()` prunes on read, so the
  scrape and the 5-minute rollup both see a current window rather than a stale one.
- **Counter pre-registration is done properly and for the documented reason.**
  `registerMeters` materialises all 12 `{tier, outcome}` counters plus the gauges at
  construction, and `counter()` throws rather than lazily creating one — which is the
  stronger form of the `initGroupRecoverySeries` lesson in CLAUDE.md, because it makes an
  unbounded label value a crash rather than a new series.
- **Metric and log conventions are followed.** Tags are explicit
  `{environmentId, product}` (never `BotMetrics.mdcTags()`), which is correct since these
  are published from the scrape thread and the probe scheduler; names are not
  `bot_`-prefixed so `BotMdcTagsMeterFilter` leaves them alone; the per-request line is
  DEBUG (track 2 only) and the only new INFO is one line per JVM at startup plus a
  fragment on the rollup line that was emitted anyway. `FleetRollupLogger` correctly uses
  `snapshotOrEmpty` rather than `forEnvironment`, so reading the rollup cannot conjure a
  budget and 43 fresh series as a side effect — and the zeroed fragment keeps the grep
  stable when no budget exists, which is the kind of detail that makes a release check
  work.
- **Two of Dev's departures from the plan are improvements.** Validating in
  `GatewayBudgetSettings`' compact constructor rather than in the `@Configuration` (AD-5
  says the latter) still fails context refresh, and additionally covers every test fixture
  and any future programmatic construction — the invariant travels with the value.
  `defaults()` as a single source pinned by `ApplicationContextLoadsTest`'s equality
  assertion is the right answer to three copies of the same numbers (see F5 for the two
  copies it does *not* cover). `setDisplayName`'s scope carrying the full username instead
  of the prefix is also better: the prefix is not a distinguishing identity at that call
  site, and the username is what an operator greps for a nameless account.
- **`runWsUpgrade` on the interface rather than a flag read at the call site is the right
  shape.** `Bot` has exactly one upgrade site, so the alternative would have been one
  `if (budget.countsWsUpgrades())` inside `connectUnderBudget` — mechanically equivalent
  but it puts an Open-Item-1 policy decision in the bot. The mild redundancy is that
  `countsWsUpgrades()` is still on the interface for tests and `UnlimitedGatewayBudget`,
  so there are two ways to ask the same question; harmless. F2 is the one place the
  single-decision-point property does not hold.
- **The probe's deliberate over-count is sound, and the `live_sibling` exclusion is
  exactly right** — nothing was sent, so nothing is stamped. Two details I checked and
  found correct: the stamp is taken *after* `probe.probe()` returns rather than before, so
  it lags by up to the probe timeout (irrelevant against a 5-minute window, and it means
  the stamp only exists if a request really happened); and `EnvRef.product()` is built from
  `getProductCode().getCode()` (`EnvironmentProbeScheduler.java:386`), the same expression
  `EnvironmentClientRegistry.createClients` uses (`:141`), so whichever of the two creates
  a budget first, the `product` label is identical. Had those disagreed, the
  `gateway_budget_*` series would have carried a label inconsistent with every `bot_*`
  series for the same environment, decided by a race.

**Module boundaries** are clean. `bot-api` gets `RequestTier`, `GatewayRequestScope` and
the four exceptions, all JDK-only. `bot-engine` gets the budget, settings, mode and
registry (Micrometer, Spring `@Component`). `bot-app` gets the `@Configuration`, the
properties and the wiring. Nothing from `bot-engine` leaks into `bot-api`, and no
`Environment` document reaches the engine — only its id, name and product string, as the
plan's readiness table requires. `GatewayBudgetMode` sitting in `bot-engine` while
`RequestTier` sits in `bot-api` is a mild asymmetry between two config-facing enums, but
it is what Phase 1 step 2 specifies and nothing depends on it being otherwise.

**One thing I looked for and did not find a problem with:** `stamps` is an
`ArrayDeque<Long>`, so each request allocates a boxed `Long`, and in observe mode the
deque is deliberately unbounded (the committed window test drives it to 1,500). I do not
think either is worth changing. The size is bounded by real request rate × 5 min — at the
known staging worst case (the 156k-reconnects-in-3-days hot loop, ≈0.6/s) that is ~180
entries, and even a pathological 1,000/s would be ~5 MB per environment, pruned on the
next stamp or scrape. A `long[]` ring buffer would be allocation-free but would have to be
sized above `hardCap` to stay correct in observe mode, which trades a clear invariant for
a negligible win. `checkBalance`'s per-round `scope()` allocation is in the same
category — one small record on a path that is about to make a 500 ms-sleep-plus-HTTP call.

**For the Releaser**, in one place: the branch carries two artefacts that are not Phase 1
— F6 (the RIK `EXISTED` / WARN-demotion hunk, which changes display-name retry behaviour
on *every* brand) and F7 (gateway-shaped edits inside an untracked RIK test) — and is
missing one that is (F1, the observe-mode invariant test). `V1e` in the plan
(`gateway_budget_wait_seconds_max < 0.01`) is the on-box substitute for F1 and should be
treated as load-bearing rather than incidental until that file is committed.
