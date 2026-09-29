# GATEWAY_REQUEST_BUDGET

> Companion to `docs/reviews/WIN79_119_PROD_ACCOUNTS/HANDOVER.md` and
> `cf-block-report.md` (the incident), `scripts/bulk-create-accounts.py:59-96` (the
> reference limiter, Python), `docs/plans/DEAD_GROUP_AUTO_RECOVERY.md` (the only paced
> launch path today, and the reconciler idiom reused here), `docs/plans/API_ERROR_FORWARDING.md`
> (the `{type, msg}` envelope this plan extends with two new statuses) and
> `docs/plans/LOG_VOLUME_TIERING.md` (every log line this plan adds obeys its tier rule).
>
> ~~A later plan, **ASYNC_REGISTRATION** (not written yet), will turn
> `BotGroupService.save`'s registration into a persisted, budget-paced background job.~~
> **Folded into this plan as Phase 4 on 2026-09-23 (user decision): there is to be no
> synchronous registration at all. See Amendment A2 / AD-19a.**

Dated section: **2026-09-22**, amended **2026-09-23** — **read Amendments A1-A9 at the bottom
first**: Phase 1 has shipped, Phases 2-5 are renumbered, and AD-15/AD-17/AD-19 are amended or
superseded.

---

## Goal

Make it impossible for one Bot Manager JVM to trip Cloudflare's **1,000 requests / 5 minutes
per source IP** rule on any gwms gateway, whatever the operator does, by routing every
outbound gateway request through one per-environment **sliding-window budget** with three
priority tiers (`ESSENTIAL > PRIORITIZED > DEFAULT`), a hard cap of 900, per-tier ceilings,
declared demand, cancellable queueing, and a **circuit breaker** that recognises the
Cloudflare block page and stops retrying into it. Because a paced group start of N bots
legitimately takes minutes, `POST /start` and `POST /restart` become **asynchronous (202 +
progress)** and app-restart launches **daisy-chain** instead of bursting. A breach today
presents as a whole-brand auth outage that retries keep alive (SA-confirmed 2026-09-17, block
still active after an hour); this is CEO policy, not a tuning preference.

---

## Findings — Current State

### The rule, and what a breach looks like from the app

- **1,000 requests / 5 min / source IP**, per gwms gateway host, at the Cloudflare edge. A
  breach answers **HTTP 403 with a Cloudflare HTML block page** — `server: cloudflare`,
  `cf-ray: <id>-HKG`, `content-type: text/html`, body `<!DOCTYPE html>… Sorry, you have been
  blocked` (`docs/reviews/WIN79_119_PROD_ACCOUNTS/cf-block-raw.txt:1-13`). Not a JSON
  envelope. The block hit at ~380 accounts × 3 requests ≈ 1,140 requests, and **had not
  aged out after a full hour** (`HANDOVER.md`, "BLOCKED ON").
- Once tripped, the edge refuses **every** request from the host for that gateway — register,
  login, re-auth, verifytoken, deposit — so a fleet on that brand cannot re-authenticate and
  every reconnect loop burns more requests into a wall that cannot answer.
- The gateway has **four gates** and three of them are HTTP 200 (`HANDOVER.md`, "Four gates");
  the Cloudflare block is the one real 403. Nothing in the app distinguishes it today.

### The app has no rate limiter; it has two concurrency limiters

- `ApiGatewayClient.registerUsers` bounds concurrency with
  `Semaphore(registrationParallelism)` (`bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:188`,
  `user.registration.parallelism=10`). `BotGroupBehaviorService.createBotsInParallel` bounds
  bot creation with `Semaphore(botCreationParallelism)`
  (`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:693`,
  `bot.creation.parallelism=10`). At ~100 ms per call that is ~100 req/s — the 5-minute rule
  is breached in **~10 s** of sustained fan-out. **Both stay** (AD-2): they bound how many
  sockets are open, the budget bounds how many requests a window admits.
- `onStartup` (`BotGroupBehaviorService.java:262-282`) iterates ACTIVE groups **serially**,
  but each `start(id)` bursts N logins + N WS upgrades + N balance reads. It is a
  `@PostConstruct`, so it runs **inside context refresh, before Tomcat is up**: every second
  a paced start takes is a second the app is unreachable and the container healthcheck
  (`docker-compose.yml:119-124`, `start_period: 60s`) is failing. Under pacing that is hours.
  It has to move off the startup thread regardless of the budget (AD-14).
- `DeadGroupRecoveryScheduler` is the only paced launch path (`max-per-tick=1`), and its
  per-group build is still the same unthrottled `startLocked`
  (`DeadGroupRecoveryScheduler.java:416` → `startForRecovery` → `startLocked`).
- `ActivationScheduler.reconcileGroup` calls `behaviorService.start(id)` **synchronously on
  its single reconciler thread** (`ActivationScheduler.java:139`). A paced 30-minute start
  would delay every other scheduled group's window transition by that much (AD-15).

### Inventory of every outbound gateway request — verified against the source

All HTTP goes through `bot-engine`'s `ApiGatewayClient`, one instance per environment
(`EnvironmentClientRegistry.createClients`, `bot-app/.../config/client/EnvironmentClientRegistry.java:116-159`,
keyed by `environmentId`, `getClients` at `:69`). WS upgrades go through
`VingameWebSocketClient.connect()`, called from exactly three sites in `Bot`. The user's
inventory was correct; two items needed sharpening (marked **corrected**).

| # | Request | Endpoint | Code path | Tier (AD-3) |
|---|---|---|---|---|
| 1 | Login | `POST <apiGateway>/gwms/v1/bot/login.aspx` (path from `AuthProfile.loginPath`) | `Bot.initialize` `Bot.java:306` → `ApiGatewayClient.authenticate` `:123-164` → ws-parser `AuthClient.authenticate` (`websocket-parser-core-3.0.5`, `AuthClient.java:106-180`). Reached from group `/start`, `/restart`, `onStartup`, `ActivationScheduler`, `startForRecovery`. | ESSENTIAL |
| 2 | Re-login | same | `Bot.performReauth` `Bot.java:782-800`, from `runWsReconnectLoop` after the 7-step `BACKOFF_SECONDS` (`:38`) exhausts (up to `MAX_RECONNECT_CYCLES=10`, `:47`) and immediately from `runAuthThenWsLoop` `:759` on watchdog timeout (`triggerFullReconnect` `:667`). | PRIORITIZED |
| 3 | Register | `POST /gwms/v1/bot/register.aspx` | `BotGroupService.save` `bot-app/.../BotGroupService.java:164` → `registerUsers` `:179-240` → `registerSingleUser` `:279-328` (`httpClient.send` `:310`). Skipped when `existingGroup=true` (`:153`). | DEFAULT |
| 4 | Set display name | `POST /gwms/v1/bot/update-fullname.aspx` | `setDisplayNameWithRetry(…, 5)` `:404-426` → `setDisplayName` `:338-378` (`send` `:355`); 1-5 per registered user, immediately after #3. | DEFAULT |
| 5 | Balance read | `GET /gwms/v1/verifytoken.aspx?token=&fg=` | `Bot.checkBalance` `:441-459` (`getBalance` `:483-526`, `send` `:500`), called from `BettingMiniGameBot.onNewSession` `:388` and `SlotMachineBot.onNewSession` `:175`. Fires only when local drift > 1% of the deposit (`BALANCE_SYNC_PERCENT_OF_DEPOSIT`, `:496`). **Corrected:** the first read is not "the first round" — it is **on the start path**: `onStart` `BettingMiniGameBot.java:1074-1076` calls `onNewSession()` *before* `addScenario` (`:1097-1104`), and `expectedCurrentBalance` is seeded at −100M (`Bot.java:139`), so the first read always fires, on the group executor's virtual thread, and if it throws the bot never installs its scenario (a silent zombie). All N first reads are submitted at once by the `startBot` loop (`BotGroupBehaviorService.java:560`) with **no semaphore** — the one truly unbounded burst in the start path. | ESSENTIAL for the first read (`lastFetchedBalance < 0`), DEFAULT after |
| 6 | Deposit | `POST /gwms/v1/bot/deposit.aspx` | `Bot.deposit` `:414-438` (`ApiGatewayClient.deposit` `:442-473`, `send` `:459`), when `autoDepositEnabled && balance < getMinBalance()` (`BettingMiniGameBot.java:391`). | PRIORITIZED |
| 7 | Post-deposit re-read | as #5 | inside `Bot.deposit` `:428`. **Stays** (user decision). | PRIORITIZED |
| 8 | WS handshake | `Environment.webSocketMiniUrl` | `client.connect()` at `Bot.java:317` (initialize), `:395` (`restart()`, periodic logout — reuses tokens, no re-login), `:857` (`tryReconnectWs`, ≤7 per reconnect cycle). | ESSENTIAL / DEFAULT / PRIORITIZED respectively |
| 9 | Anonymous WS probe | same host | `EnvironmentWsProbe.probe` (`bot-app/.../probe/EnvironmentWsProbe.java:150`), from `EnvironmentProbeScheduler` (60 s tick, only while a DEAD group is a recovery candidate). | counted, never queued (AD-3) |

Not in scope and confirmed: `VipTalkClient`, `HttpPrometheusQueryClient` are other hosts.
`GameMsClient.deposit` / `fetchTokenDetails` have **no live callers** — the only reference is
the constructor at `EnvironmentClientRegistry.java:132`. The ws-parser library does **not**
reconnect on its own (`VingameWebSocketClient.java:876-908` dispatches our listener and
nothing else), so there is no hidden WS call site.

### Things the design has to respect that were not in the brief

- **The login response is opaque to us today.** `AuthClient.authenticate` parses the body
  as JSON before anything else (`AuthClient.java:140`); a Cloudflare HTML page fails there
  with a `JsonParseException` that the library wraps as
  `WebSocketParserException("… Authentication failed due to: Unexpected character ('<' …")`
  (`:176-179`). Neither the status code nor the `server`/`cf-ray` headers survive. Block
  detection on the most exposed request therefore cannot be done reliably without owning
  the HTTP call (AD-12). API_ERROR_FORWARDING AD-7 already records the library message as
  "misleading" for the same reason.
- **`AuthClient` allocates a fresh `HttpClient` per instance** (`AuthClient.java:25`), and
  `ApiGatewayClient.authenticate` constructs a new `AuthClient` per login (`:145`). Every JDK
  `HttpClient` owns a `SelectorManager` platform thread, so a 3k-bot start briefly spawns
  ~3k platform threads. Owning the login call fixes this as a side effect.
- **`VingameWebSocketClient.connect()` swallows `InterruptedException`** (`:483-486`): it
  logs, restores the flag and returns normally with a half-built client. Cancellation of a
  queued WS upgrade must therefore happen **before** `connect()` is entered, never by
  interrupting it (AD-8).
- **`CompletableFuture.join()` is not interruptible.** `createBotsInParallel` joins its
  futures (`BotGroupBehaviorService.java:741`), so interrupting the start thread would not
  abort a paced start. Cancellation is a flag the budget queue checks, not an interrupt (AD-8).
- **The group lock is held for the whole build** (`start` `:292-306`, javadoc: "held across the
  whole (I/O-bound, internally parallel) build"). Today that is ~50 s; under pacing it is
  the whole paced duration, so `/stop` on a starting group would block until the start
  finished. `stop()` must cancel the in-flight start first (AD-8, AD-16).
- **`runningGroups.put(id, runtime)` happens before bot creation** (`:487`), and the runtime is
  born `ACTIVE` (`BotGroupRuntime.java:163`). So `isGroupRunning`/`getActualStatus` already
  report ACTIVE with zero bots during a build; the ActivationScheduler and
  `RecoveryCandidateSelector` already treat a building group as running. The async start
  does not change that (AD-16).
- **`WebSocketClientHandshakeException` carries the HTTP response** (Netty 4.1,
  `response()`), and `connect()` rethrows the handshake failure unchecked through
  `handshakeFuture().sync()` (`:446`). WS-upgrade block detection is possible, best-effort.
- **Periodic logout arms the reconnect loop as well as `restart()`.** `Bot.logout()` `:405`
  calls `stop()` without setting `stopped`; the library's `channelInactive` fires
  `onConnectionClosed` (`:1273-1277`) *before* `close()`'s `finally` clears `isConnected`
  (`:555`), so the disconnect listener runs and `onWsDisconnected` spawns a reconnect loop
  that races `restart()` five seconds later. Pre-existing, ≤ 1 per group per hour, costs one
  extra WS upgrade per cycle. Out of scope; Open Item 10.
- **`ApiGatewayClient.getBalance` sleeps 500 ms before every call** (`:486`). Harmless under
  the budget (the stamp is taken at admission), noted for the follow-up list.
- **Existing metric conventions:** env-scoped meters tag explicitly
  (`EnvironmentProbeScheduler.java:260-265`, `Tags.of("environmentId", …, "product", …)`),
  not through `BotMetrics.mdcTags()` (`BotMetrics.java:111-119`, which reads bot MDC);
  `BotMdcTagsMeterFilter` only touches `bot_`-prefixed names. Counters that alert rules read
  with `increase()` must be **pre-registered at zero** (CLAUDE.md, "Both of those rules only
  work because the counters are pre-registered at zero").
- **REST error contract:** `RestExceptionHandler` maps `UpstreamGatewayException` → 502
  `{type, msg}` (`bot-app/.../RestExceptionHandler.java:112-118`); there is no 429 or 503 arm.
  `BotGroupController.start/stop` return `200` with an empty body (`:143-155`), wrapped in
  `runWithManualOverride` (`:179-195`) which flips and restores `activationMode` around the
  action; `restart` at `:197-202`; `getStatus` builds `BotGroupStatusDTO{groupId, groupName,
  targetStatus, actualStatus, playingStatus}` (`:222-240`).
- **`websocket-parser-test` / `websocket-parser-simple-test` (3.0.5) offer no stub gateway.**
  Both are harnesses for driving *real* game servers (account pools, fixtures, Extent
  reports). The stub has to be ours (AD-22).

---

## Per-aspect readiness / mapping

| Aspect | State | Notes |
|---|---|---|
| Single funnel for HTTP requests | **partial** | Five `httpClient.send` sites in one class (`ApiGatewayClient.java:310,355,459,500` + the `AuthClient` call `:145`). One private `send(tier, scope, request)` funnel replaces them. |
| Single funnel for WS upgrades | **ready** | Three `connect()` sites in one class (`Bot.java:317,395,857`); one `connectUnderBudget(tier, client)`. |
| Per-environment keying | **ready** | `EnvironmentClientRegistry` is already the per-env registry; the budget registry mirrors it, keyed by `environmentId`. |
| Cancellable waits | **blocked → AD-8** | No cancellation primitive exists; `join()` is uninterruptible; `connect()` swallows interrupts. New `GatewayRequestScope` with a cancel predicate. |
| Non-blocking start | **blocked → Phase 2** | `start()` blocks the HTTP thread, `onStartup` blocks Tomcat, the activation tick blocks on one start. |
| Progress reporting | **partial** | `BotGroupStatusDTO`/`BotGroupHealthDTO` exist; no "starting n/total" concept. Additive nullable block (AD-17). |
| Cloudflare block detection | **blocked → AD-12** | Login response is opaque through `AuthClient`. In-repo HTTP calls expose status + headers. |
| Circuit-breaker probe | **ready** | Anonymous probe idiom from `EnvironmentWsProbe` (AD-2 there); here it is an anonymous `verifytoken` GET. |
| REST mapping of new outcomes | **ready** | Two new exception types + two `@ExceptionHandler` arms; Spring picks the most specific handler. |
| Metrics | **ready** | `MultiGauge`/explicit-tag idiom from `EnvironmentProbeScheduler`; Micrometer `Timer` with SLO buckets. |
| Alerting | **ready** | `prometheus/alerts.yml` rules with `audience:`; `AlertRulesAudienceTest` guards labels. |
| Deterministic tests | **ready** | Clock-injection precedent: `ScopedDebugRegistry(int, LongSupplier clock)` (`bot-api/.../ScopedDebugRegistry.java:95`). Source-guard precedent: `PerBotInfoLogGuardTest`, `BettingMiniLookupCallSiteGuardTest`. |
| Stub gateway | **blocked → AD-22** | Nothing usable in `~/.m2`; build a JDK `HttpServer` + Netty WS stub in test scope. |
| Module boundaries | **ready** | Budget lives in `bot-engine` (has `spring-context`, `micrometer-core`, `netty-all`, ws-parser). Tier enum + exceptions in `bot-api`. Wiring/config/REST in `bot-app`. No `Environment` (a bot-app `@Document`) reaches the engine — only its id, name and product string. |
| UI contract | **open** | 202 + JSON body on `/start` `/restart`; additive `startAttempt` block. Open Items 2-3. |

---

## Architecture Decisions

**AD-1 — One `GatewayBudget` per environment, in `bot-engine`, resolved from a
`GatewayBudgetRegistry` keyed exactly like `EnvironmentClientRegistry`.**
`GatewayBudgetRegistry.forEnvironment(environmentId, environmentName, productCode)` is a
`computeIfAbsent`; `EnvironmentClientRegistry.createClients` (`:116-159`) obtains it and hands
it to `ApiGatewayClient.init(...)` (new parameter) and to `EnvironmentClients` (new field).
`BotFactory.createBot` (`:202-216`) wires it into the bot via a fluent
`setGatewayBudget(...)`, after `setClients`. **One IP = one JVM** — there is no cross-process
coordination and none is designed for; if two instances ever share an egress IP that is a
deployment error, not a budget feature.
*Rejected:* one global budget. The rule is per gateway host, and two environments on
different hosts must not throttle each other.

**AD-2 — The budget bounds rate; the two `Semaphore`s keep bounding concurrency. Both stay.**
`bot.creation.parallelism` and `user.registration.parallelism` are untouched. Their javadocs
gain one sentence: raising them no longer raises the request rate. The `startBot` loop's
unbounded first-read burst (#5) is bounded by the budget alone — no third semaphore.

**AD-3 — `RequestTier { ESSENTIAL, PRIORITIZED, DEFAULT }` in `bot-api`, declared in that
order, assigned by intent at the call site.** No numeric constants in code; ordering is
`ordinal()`. The assignment is the inventory table above, verbatim:
- `ESSENTIAL`: `Bot.initialize` login + WS upgrade, and `checkBalance` when
  `lastFetchedBalance < 0` (the first read is on the start path — see Findings). Reached
  from every group-start path: `/start`, `/restart`, startup chain, activation, recovery.
- `PRIORITIZED`: `performReauth` login, `tryReconnectWs` upgrade, `Bot.deposit` and its
  post-deposit read.
- `DEFAULT`: `registerSingleUser`, `setDisplayName`, drift `checkBalance`, `Bot.restart()`
  upgrade (periodic logout).
- The anonymous WS probe (#9) and the circuit-breaker probe (AD-13) call
  `budget.count(reason)`: a stamp in the window, no admission, never waits.
`ApiGatewayClient.authenticate(credentials)` becomes `authenticate(credentials, tier, scope)`;
`getBalance(...)` and `deposit(...)` likewise gain `(tier, scope)`. The old signatures are
removed, not defaulted — a caller that does not say its tier is a bug the compiler should find.

**AD-4 — Sliding 5-minute window, counted at acquisition, hard cap 900, every tier obeys it.**
`SlidingWindowGatewayBudget` keeps a deque of monotonic acquisition stamps (the Python
`RateLimiter.acquire` shape, `scripts/bulk-create-accounts.py:80-96`). `W` = stamps younger
than `window`. A request is admitted iff its tier's rule holds **and** `W < hard-cap`.
Attempts count, completions do not; a request that fails after admission still cost the
edge a request. Probes count. Cancelled-while-queued requests do **not** count (they never
left the JVM).

**AD-5 — Per-tier ceilings from config, keyed by tier name, monotonic, validated at startup.**
```
bot.gateway.budget.mode=observe            # observe | enforce  (AD-23)
bot.gateway.budget.window=5m
bot.gateway.budget.hard-cap=900
bot.gateway.budget.tier.default.ceiling=500
bot.gateway.budget.tier.prioritized.ceiling=750
bot.gateway.budget.tier.essential.ceiling=900   # = hard-cap by default; Open Item 5
bot.gateway.budget.tier.default.max-wait=30s
bot.gateway.budget.tier.prioritized.max-wait=10m
bot.gateway.budget.tier.essential.max-wait=0    # 0 = unbounded (cancellable)
bot.gateway.budget.registration.max-wait=15m    # AD-19
bot.gateway.budget.count-ws-upgrades=true       # Open Item 1
bot.gateway.budget.block-cooldown=15m           # AD-13
```
Lower-case tier keys so `BOT_GATEWAY_BUDGET_TIER_DEFAULT_CEILING` binds through compose like
`BOT_RECOVERY_ENABLED` does. Startup fails (`IllegalStateException` from the `@Configuration`
that builds `GatewayBudgetSettings`) unless
`0 < default.ceiling <= prioritized.ceiling <= essential.ceiling <= hard-cap < 1000`; a
`hard-cap > 900` logs one WARN. Admission rules, with `R_E`/`R_P` the outstanding declared
demand of the tiers above (AD-7):
- `DEFAULT`:     `W < default.ceiling − R_E − R_P`
- `PRIORITIZED`: `W < prioritized.ceiling − R_E`
- `ESSENTIAL`:   `W < essential.ceiling`
- all tiers:     `W < hard-cap`, and the circuit is not open (AD-13).
The gap between ceilings *is* the reservation for the tiers above — no cadence prediction.
EWMA-based cadence estimation is explicitly **deferred** (Open Item 9).

**AD-6 — Strict priority across tiers, FIFO within a tier, one admission pass.**
Waiters sit in three `ArrayDeque`s under one `ReentrantLock`, each holding its own
`CompletableFuture<Void>` (no shared `Condition`, no thundering herd at 3k waiters). One
package-private `admitWaiters()` walks ESSENTIAL → PRIORITIZED → DEFAULT, admitting heads
while their rule holds. It runs on every acquire, release, cancel, reserve and circuit
transition, and from a wake-up scheduled at the **earliest stamp expiry** (one
single-thread virtual `ScheduledExecutorService` per budget). `execute(tier, scope, call)`
blocks the caller (virtual threads — parking is the intended cost); `tryExecute(tier,
scope, call, maxWait)` returns `Optional.empty()` if not admitted within `maxWait`
(`Duration.ZERO` = admit now or never).

**AD-7 — Declared demand: `reserve(tier, n, scope)` shrinks lower ceilings for the
reservation's lifetime, and is released in `finally`.**
`Reservation r = budget.reserve(ESSENTIAL, demand, groupScope)` at the top of
`createBotsInParallel`, where `demand = botCount × (2 + (count-ws-upgrades ? 1 : 0))`
(login + first read + upgrade). Acquisitions whose scope carries the same `botGroupId` and
tier consume it; `r.release()` in the `finally` of `startLocked` returns the remainder; a
safety TTL of `2 × window` retires a leaked reservation. `startForRecovery` inherits it
because it calls the same `startLocked`. The reservation is only ever *pre-emptive* — it
stops DEFAULT/PRIORITIZED from filling the window in the minutes before the ESSENTIAL flood
arrives; once `W` is at the ceilings the rules alone do the work.

**AD-8 — Every queued request is cancellable through its `GatewayRequestScope`, and
cancellation is a predicate, never a thread interrupt.**
`GatewayRequestScope(botGroupId, botId, BooleanSupplier cancelled)`; a bot's scope is
`() -> stopped || startCancelled(groupId)`. `budget.cancelScope(groupId)` completes every
matching waiter's future exceptionally with `GatewayRequestCancelledException` (extends
`GatewayBudgetException`, AD-11). `stop()` (`BotGroupBehaviorService.java:1001`) calls
`startAttempts.cancel(id)` + `budget.cancelScope(id)` **before** `lock.lock()`, so a paced
start unwinds (its per-bot futures fail fast, `startLocked`'s `finally` tears the runtime
down, the lock is released) and the stop proceeds. A thread interrupt reaching a waiter is
honoured too (the future wait is interruptible) and propagates as `InterruptedException`
with the flag restored. Rationale: `join()` is uninterruptible and `connect()` eats
interrupts (Findings), so an interrupt-based design would be a design that does not work.

**AD-9 — Budget and circuit outcomes are never terminal for a bot.**
`performReauth` (`Bot.java:782`) marks DEAD on *any* exception today. A
`GatewayBudgetException` there must instead count as a failed attempt and continue the
existing backoff loop (still capped by `MAX_RECONNECT_CYCLES`). `Bot.deposit` on a budget
exception skips this round (next round re-triggers because the balance is still below
minimum). `createSingleBot` failures with a budget exception are classified `"budget"` in
`classifyCreationFailure` (`:771-806`) — a **new bounded label value**, documented. Upstream
rejections keep their existing terminal semantics; a request the JVM chose not to send must
not be reported as one the gateway refused.

**AD-10 — Drift balance reads never park the bot's message pipeline.**
`checkBalance` with `lastFetchedBalance >= 0` uses `tryExecute(DEFAULT, scope, …,
Duration.ZERO)`; on `empty()` it returns the local estimate and sets
`balanceReadDeferred = true`. Before a deposit, if `balanceReadDeferred` and the local
figure is below minimum, `onNewSession` first does one **blocking PRIORITIZED** read (bounded
by that tier's max-wait) and deposits only if the fresh figure is still below minimum — a
deposit is money, and it must not be triggered by a figure the budget kept us from
refreshing. On budget exhaustion of that read: no deposit this round.

**AD-11 — Two new REST-visible outcomes, typed, in `bot-api`.**
`abstract GatewayBudgetException extends BotManagerException` with
`GatewayBudgetExhaustedException(tier, environmentId, Duration retryAfter)` → **429** with
`Retry-After` and body `{type: "Gateway budget exhausted", msg}`;
`GatewayCircuitOpenException(environmentId, cfRay, Duration retryAfter)` → **503** with
`Retry-After` and `{type: "Gateway edge block", msg}`;
`GatewayRequestCancelledException` → never reaches REST (only thrown into a cancelled start).
Two new arms in `RestExceptionHandler` beside `handleUpstream` (`:112`); they are not
`UpstreamGatewayException`s because 502 would say the gateway failed, and it did not — the
JVM declined. `Retry-After` is the seconds until the earliest stamp expiry (429) or until
the cooldown ends (503).

**AD-12 — Login moves in-repo so its response is observable; `AuthClient` stays for
`generateFingerprint()` only.**
`ApiGatewayClient.authenticate` builds the request exactly as `AuthClient.java:125-134`
does — `POST ctx.apiGateway() + ctx.loginPath()`, headers `Cache-Control: no-cache`,
`Content-Type: application/json`, `User-Agent: PostmanRuntime/7.15.2`, `X-TOKEN` when
present, body `loginRequestFactory.apply(ctx)` serialised with the same `ObjectMapper` the
client already uses for its debug line (`:137`; the library's `ObjectMapperProvider.getDefault()`
differs from `new ObjectMapper()` only in deserialisation leniency and `ALWAYS` inclusion,
which is Jackson's default) — and parses `data[0].token` / `session_id` / `token2` exactly
as `:140-172`. The wire shape is byte-identical and pinned by a test against the stub
(AD-22) for every `LoginRequest` implementation in `bot-engine/.../domain/bot/auth/`
(`Tip`, `Bom`, `B52`, `Rik`, `Win79`). Consequences: block detection works on the login
path; one shared `HttpClient` per environment instead of one per login; the
`UpstreamLoginException` message can finally carry the upstream envelope's `code`/`message`
(closing API_ERROR_FORWARDING AD-7's "best effort"). Ships in Phase 4, behind the same
verification as the block detector, so a regression on any brand's login is caught by the
per-product smoke step (V4c).

**AD-13 — Cloudflare block ⇒ per-environment circuit breaker: open for a cooldown, one
anonymous clearance probe per cooldown, nothing else leaves the JVM.**
`CloudflareBlockDetector.classify(status, headers, body)` returns `EDGE_BLOCK` iff
`status ∈ {403, 429}` **and** (`server` equals-ignore-case `cloudflare` **or** `cf-ray`
present) **and** (content-type is `text/html` **or** the body contains `cf-error-details`
or `Sorry, you have been blocked`). The `cf-ray` value is captured for the ERROR line and
the SA ticket. A second entry point classifies a `WebSocketClientHandshakeException` by its
`response()` (best-effort; a WS-side block therefore also opens the circuit). On
`EDGE_BLOCK`: state `CLOSED → OPEN(until = now + block-cooldown)`, `gateway_circuit_open{env}=1`,
`gateway_edge_blocks_total{env,endpoint}++`, one **ERROR** line
(`env <id> (<name>): Cloudflare edge block on <endpoint>, cf-ray <id> — circuit open for
<cooldown>`), and the detecting request throws `GatewayCircuitOpenException`. While OPEN,
`admitWaiters()` admits nothing; ESSENTIAL waiters park (their wait is unbounded and
cancellable), the other tiers time out per their max-wait. At `until`, state `HALF_OPEN`:
the budget's own scheduler issues **one** anonymous `GET
<apiGateway>/gwms/v1/verifytoken.aspx?token=probe&fg=probe` (counted, `count("circuit-probe")`);
any response that is not `EDGE_BLOCK` — including the gateway's own JSON error envelope —
closes the circuit (INFO line, gauge 0), a block re-opens it for another cooldown. Cooldown
is constant, not exponential: the observed block outlived an hour, so 15-minute probes cost
at most four requests per hour against a wall. `DeadGroupRecoveryScheduler.evaluateCandidate`
skips a group whose environment's circuit is open (outcome `circuit_open`, no budget charged),
because a recovery attempt into an open circuit only parks.
*Rejected:* letting the head-of-queue real request be the probe. It would burn a bot's login
to learn what an anonymous GET learns for free, and the bot would be the one to fail.

**AD-14 — App-restart launches daisy-chain on one virtual thread, after the context is up.**
`onStartup` moves from `@PostConstruct` to an `@EventListener(ApplicationReadyEvent)` that
logs `Bot Manager startup: N bot groups queued for daisy-chained start` and submits one
virtual thread (`startup-chain`) which calls `start(id)` for the same group set, in the same
order, **one at a time**; the existing `Bot Manager startup complete. {} bot groups running`
line moves to the end of the chain. Tomcat, actuator, the schedulers and the REST API are
therefore reachable from the first second of a restart, whatever the fleet size. The chain
is the CEO's "daisy-chain, never burst" literally; it also gives each group a complete start
rather than every group a partial one. Per-group isolation (`try/catch` per start) is kept.

> **AD-15 amended — the ack is `200`, not `202` (user decision, Open Item 3). Everything else
> in AD-15 stands, including "accepted, not finished". See Amendment A3. Original text
> follows.**

**AD-15 — `POST /{id}/start` and `POST /{id}/restart` answer `202 Accepted` with a
`BotGroupStatusDTO`; the work runs on a virtual thread; `ActivationScheduler` START and
`scheduleRestart` use the same async entry.**
Synchronous part (still in the HTTP thread): `findById` (404), the two `BadRequest` checks
`startLocked` does first (`:429-443`, no `environmentId` / no `gameId` → 400), then
`StartAttemptRegistry.begin(id, origin)` — `putIfAbsent`, so a second `/start` while one is in
flight returns 202 with the *same* attempt, never a second task. Then
`behaviorService.startAsync(id, origin)` submits `start(id)` (or `restart(id)`) to a virtual
thread. `runWithManualOverride`'s activation-mode flip stays synchronous and its
*restore-on-failure* moves into the task's failure path. `restart()`'s zero-bot
`IllegalStateException` (`:1221-1224`) no longer reaches HTTP; it is recorded as the attempt's
`lastError` (AD-17). Origins: `REST`, `STARTUP`, `SCHEDULE`, `RECOVERY`, `SCHEDULED_RESTART`.
`startForRecovery` stays **synchronous** — the recovery tick is designed to block for one
group start (DEAD_GROUP_AUTO_RECOVERY AD-9) and charges its outcome on return; it merely
opens an attempt so progress is visible.
*Rejected:* splitting `startLocked` into a sync admission half and an async build half. It is
more surgery on a 2,480-line class for a difference of milliseconds in when `/status` flips.

**AD-16 — A start in flight is "running" for every reconciler, and `stop()` wins.**
`isGroupRunning(id)` (`:1608`) returns true while a start attempt is open, in addition to its
runtime check. That keeps `ActivationScheduler` from deciding START twice and keeps
`RecoveryCandidateSelector` from selecting a group an operator is already restarting.
`stop()` cancels the attempt and the scope (AD-8) before taking the lock, so a STOP decision
or an operator `/stop` never waits out a paced start. `restart()`'s internal stop does the
same.

**AD-17 — Progress is an additive, nullable `startAttempt` block on `BotGroupStatusDTO` and
`BotGroupHealthDTO`; no enum value is added.**

> **Superseded by AD-17a — the user chose the enum (Open Item 2). `BotGroupStatus` gains
> three appended constants, the `startAttempt` block and `StartAttemptDTO` are dropped, and
> progress moves to named scalar fields. `StartAttemptRegistry` survives. See Amendment A1.**

**AD-17 (superseded text follows).**
`StartAttemptDTO { origin, phase: PENDING|BUILDING|STARTING_BOTS, botsUp, botsFailed,
botsTotal, startedAt, elapsedSeconds, lastError }`. `botsUp`/`botsFailed` are incremented in
the per-bot creation task (`createBotsInParallel`'s lambda, `:705-720`), looked up by group
id from the registry (null-safe for fixtures). The block is `null` when no start is in
flight; `lastError` of the most recent attempt is retained on the registry until the next
start or stop and surfaced on `/status` as `lastStartError`. `BotGroupStatus` keeps its three
values. Whether the UI wants a `STARTING` value instead is Open Item 2 — that is a contract
change and is not made here.

**AD-18 — Expected durations are documented as correct behaviour, in the plan, the javadoc
and the INFO line.**
At 900 / 5 min: a 3,000-bot group is 3,000 logins + 3,000 first reads (+ 3,000 upgrades if
counted) = 6,000-9,000 requests ≈ **33-50 minutes**. A 300-bot group is 600-900 ≈ **one
window**. That is the rule working, not a regression; the group-start INFO line says so:
`group <id> (<name>): start admitted — declared demand <n> requests, env window <W>/900,
estimated <m> min`. The rollup line (AD-20) shows the live window.

**AD-19 — Registration stays synchronous in this plan; it paces, and its complete failure
on budget is a 429.**

> **Superseded by AD-19a — there is to be no synchronous registration at all (user decision,
> Open Item 4). ASYNC_REGISTRATION is folded into this plan as Phase 4; the 429 stopgap is
> deleted. See Amendment A2.**

**AD-19 (superseded text follows).**
`registerSingleUser` and `setDisplayName` run as DEFAULT with an explicit wait override
`bot.gateway.budget.registration.max-wait` (15 m) so an admitted registration finishes rather
than half-finishes when a group start floods the window mid-way. `registerUsers` keeps its
per-user accounting; `BotGroupService.save` throws `GatewayBudgetExhaustedException` (429)
instead of `UpstreamRegistrationException` (502) when **every** user failed on the budget,
and keeps today's partial-success semantics otherwise (API_ERROR_FORWARDING AD-9). A
registration of N users costs 2N-6N requests and therefore takes ≈ `ceil(2N / 500)` windows
of wall-clock on the HTTP call. **This is the stopgap**; ASYNC_REGISTRATION owns persistence,
resumability and the 202 shape. Open Item 4 records what it must inherit.

**AD-20 — Observability follows the tier rule: nothing per request at INFO.**
Metrics, all tagged `{environmentId, product}` explicitly (never `mdcTags()`), pre-registered
at zero when a budget is created:
- `gateway_budget_window_requests` gauge — `W`.
- `gateway_budget_queue_depth{tier}` gauge; `gateway_budget_reserved{tier}` gauge.
- `gateway_budget_requests_total{tier, outcome=admitted|timeout|cancelled|circuit_open}` counter.
- `gateway_budget_wait` timer `{tier}` with SLO buckets 1 s, 10 s, 60 s, 300 s, 600 s.
- `gateway_circuit_open` gauge (0/1); `gateway_circuit_opened_total` counter;
  `gateway_edge_blocks_total{endpoint}` counter.
Logs: the per-env tier-2 rollup line (`FleetRollupLogger.java:168-178`) gains
`gateway=<W>/<cap> queued=<E>/<P>/<D> circuit=<closed|open>`; one **WARN** on the edge when a
tier first becomes throttled (`env <id>: <tier> tier throttled — window <W>/<cap>, queue
<n>`) and one INFO when it clears, per env per tier, with a 5-minute re-arm so a flapping
edge cannot spam; ERROR/INFO once per circuit open/close (AD-13); the group-start INFO line
(AD-18). Every admission, timeout and cancel is DEBUG, MDC-tagged. Alerts in
`prometheus/alerts.yml`: `GatewayBudgetNearCap` (`gateway_budget_window_requests > 800`,
`for: 1m`, warning, `audience: internal`) and `GatewayEdgeBlocked` (`gateway_circuit_open ==
1`, `for: 1m`, critical, `audience: product` — bots on that brand cannot re-auth). Neither
needs an Alertmanager route (they fall through to `viptalk`). Grafana: four panels on
`per-environment.json` (window vs the three ceilings, queue depth by tier, wait p95 by tier,
circuit state).

**AD-21 — Automated tests never touch a real gateway; every timing test is
clock-driven.**
`SlidingWindowGatewayBudget` takes a `LongSupplier nanos` (the `ScopedDebugRegistry` seam)
and a `ScheduledExecutorService` for its wake-ups; tests inject a manual clock and drive
`admitWaiters()` directly. `ApiGatewayClient` tests run against the in-process stub (AD-22)
on `127.0.0.1`. A source guard (`GatewayCallSiteGuardTest`, the `PerBotInfoLogGuardTest`
idiom) asserts: `httpClient.send(` appears exactly once in `ApiGatewayClient` (inside the
funnel), `new AuthClient(` appears nowhere in production code after Phase 4, `.connect()`
appears exactly once in `Bot` (inside `connectUnderBudget`), and no production class outside
`GatewayBudgetRegistry`/`EnvironmentWsProbe`/`ApiGatewayClient` constructs a
`java.net.http.HttpClient` pointed at a gateway. `ApplicationContextLoadsTest` gains an
assertion that the registry bean is the one `EnvironmentClientRegistry` uses.

**AD-22 — The stub gateway is ours, test-scope, in `bot-engine`, runnable stand-alone.**
`StubGateway` (`bot-engine/src/test/java/com/vingame/bot/infrastructure/client/stub/`): a JDK
`com.sun.net.httpserver.HttpServer` answering `login.aspx`, `register.aspx`,
`update-fullname.aspx`, `verifytoken.aspx`, `deposit.aspx` with the real envelope shapes
(the register response shape is already in `ApiGatewayClient.java:557-577`), plus a Netty
`WebSocketServerProtocolHandler` endpoint that completes the upgrade and answers the AUTH
frame with `[1,true,0,"stub","MiniGame",null]` (CLAUDE.md, "Token Naming Reference"). It
records every request with a timestamp, exposes `countInLastWindow()`, and can be switched
into **block mode** (returns the captured Cloudflare 403 page from `cf-block-raw.txt`,
headers included) to test the detector and the circuit. `StubGatewayMain` runs it on a port
for the manual escalation test (V5) against a locally running app with an `Environment`
record pointing at `http://127.0.0.1:<port>`. **No test, harness or verification step in
this plan is ever pointed at a gwms host.**

**AD-23 — Ships in `mode=observe`; `enforce` is switched per box by env var, then the
compiled default flips after a staging soak.**
In `observe` the facade counts, tags, logs and publishes everything and **never waits,
rejects or opens a circuit** (detection still logs the WARN it would have acted on). Same
pattern as `bot.recovery.enabled` (compose passes
`BOT_GATEWAY_BUDGET_MODE=${GATEWAY_BUDGET_MODE:-observe}`; set it in the uncommitted
`secrets.env`/`.env`, never in the compose file). Phase 1 ships observe-only; Phase 3 adds
enforcement behind the flag; Phase 5 flips the default once V3's numbers have been read on
staging for a week.

---

## Plan

> **Amended 2026-09-23 — Phase 1 has shipped; Phases 2-5 are renumbered and Phase 4 (async
> registration) is new. The authoritative phase list and the release gating are in
> Amendment A6/A7 at the bottom of this document. The sections below are the original text,
> kept for the record; where they disagree with A6, A6 wins.**

Phases are ordered so each ships alone. Phase 2 (async start) is independent of the budget
and is deliberately **before** enforcement: enforcing on a fleet whose `/start` blocks the
HTTP thread would trade a Cloudflare block for a gateway timeout.

### Phase 1 — Facade in observe mode, every call site tagged, metrics and near-cap alert

> **SHIPPED** — commits `7c7c3b1`..`b741d92` on `feature/gateway-request-budget`; QA, review
> and compliance all PASS (`docs/reviews/GATEWAY_REQUEST_BUDGET/`). Not deployed: nothing
> ships until Phase 5 is complete (A7).

**Changes**
1. `bot-api`: `RequestTier` enum (AD-3); `GatewayRequestScope` record (AD-8);
   `GatewayBudgetException` + the three subclasses (AD-11).
2. `bot-engine`, package `com.vingame.bot.infrastructure.gateway`:
   `GatewayBudget` (interface: `execute`, `tryExecute`, `count`, `reserve`, `cancelScope`,
   `snapshot()`), `GatewayBudgetSettings` (record), `SlidingWindowGatewayBudget` (window +
   stamps + metrics only in this phase; `execute` = stamp + run), `GatewayBudget.UNLIMITED`
   (pass-through for fixtures), `GatewayBudgetRegistry` (`@Component`, `forEnvironment`,
   `snapshotAll()` for the rollup). Settings validated in the constructor (AD-5).
3. `bot-app`: `GatewayBudgetConfig` (`@Configuration`, `@Value`-bound settings → registry
   bean); `application.properties` block from AD-5; compose line for `mode`.
4. `EnvironmentClientRegistry.createClients` (`:116-159`) → obtains the budget, passes it to
   `ApiGatewayClient.init` and `EnvironmentClients`. `BotFactory.createBot` (`:202-216`) →
   `.setGatewayBudget(environmentClients.getGatewayBudget())`.
5. `ApiGatewayClient`: one private `send(RequestTier, GatewayRequestScope, HttpRequest)`
   funnel; `authenticate/getBalance/deposit` gain `(tier, scope)`; `registerSingleUser` /
   `setDisplayName` route through the funnel as DEFAULT with a registration scope
   (`GatewayRequestScope.registration(prefix)`). The `AuthClient` call is wrapped by the
   funnel's non-HTTP twin `execute(tier, scope, Callable)` in this phase (login stays in the
   library until Phase 4).
6. `Bot`: `connectUnderBudget(RequestTier, VingameWebSocketClient)` replacing the three
   `connect()` calls at `:317` (ESSENTIAL), `:395` (DEFAULT), `:857` (PRIORITIZED);
   `performReauth` `:786` → PRIORITIZED; `deposit` `:414-438` → PRIORITIZED for both calls;
   `checkBalance` `:441` → ESSENTIAL when `lastFetchedBalance < 0`, else DEFAULT (plain
   `execute` in this phase — `tryExecute` semantics arrive with enforcement in Phase 3).
   `scope()` helper built from `configuration` + `this::isStopped`.
7. `EnvironmentProbeScheduler`: `budget.count("ws-probe")` before each probe.
8. Metrics from AD-20 (window gauge, requests_total, queue/reserved gauges at 0, wait
   timer); `FleetRollupLogger` env line extension; `GatewayBudgetNearCap` rule +
   `AlertRulesAudienceTest` expectation; one Grafana panel (window vs ceilings) on
   `per-environment.json`.
9. `classifyCreationFailure` gains the `"budget"` arm (inert until Phase 3).

**Tests**
- `RequestTierTest`: declaration order is `ESSENTIAL, PRIORITIZED, DEFAULT`.
- `GatewayBudgetSettingsTest`: monotonic validation, `hard-cap >= 1000` rejected, `> 900` warns.
- `SlidingWindowGatewayBudgetWindowTest` (fake clock): stamps expire at exactly `window`;
  `count()` stamps; cancelled-before-admission is not stamped; `snapshot()` shape.
- `GatewayCallSiteGuardTest` (AD-21, the `.connect()`/`send(`/`HttpClient` rules; the
  `new AuthClient(` rule is `== 1` until Phase 4).
- `ApiGatewayClientTierTest`: with a recording fake `GatewayBudget`, each public method
  submits the tier and scope its caller passed.
- `BotGatewayTierTest`: `initialize` submits `ESSENTIAL` for login, upgrade and first read;
  reconnect path submits `PRIORITIZED`; `restart()` submits `DEFAULT` (extend
  `BotReconnectTest` fixtures).
- `EnvironmentClientRegistryBudgetWiringTest`: same registry instance per env id.
- `FleetRollupLoggerTest`: env line carries `gateway=`.
- `ApplicationContextLoadsTest`: registry bean present; settings bound from properties.

### Phase 2 — Async start/restart, startup daisy-chain, progress

> **Amended — see A6 Phase 2 for the change list Dev implements (200 + DTO, `STARTING`, no
> `StartAttemptDTO`).**

**Changes**
1. `StartAttemptRegistry` (`bot-app`, `domain/botgroup/service`): `begin(id, origin)`
   (`putIfAbsent`), `progress(id)`, `cancel(id)`, `finish(id, error)`, `lastError(id)`;
   `StartAttempt` with `AtomicInteger botsUp/botsFailed`, `phase`, `startedAt`, a cancel flag.
2. `BotGroupBehaviorService`: `startAsync(id, origin)` / `restartAsync(id, origin)` (submit
   to a virtual thread; attempt opened synchronously; activation-mode restore in the task's
   failure path); `startLocked` opens/advances/finishes the attempt and increments
   `botsUp`/`botsFailed` in the creation lambda; `isGroupRunning` consults the registry
   (AD-16); `stop(id, …)` cancels the attempt before locking (the scope cancel lands in
   Phase 3 — in this phase cancellation only prevents further bots from being built, via the
   flag the creation lambda checks). `onStartup` → `ApplicationReadyEvent` + `startup-chain`
   thread (AD-14). `scheduleRestart` → `restartAsync`.
3. `ActivationScheduler.reconcileGroup` START → `startAsync(id, SCHEDULE)`; STOP unchanged.
4. `BotGroupController`: `/start` and `/restart` → 202 + `BotGroupStatusDTO` (AD-15);
   `getStatus` and `getHealth` carry `startAttempt` / `lastStartError` (AD-17);
   `StartAttemptDTO`.
5. `docs/process/AGENTIC_WORKFLOW.md` universal smoke: the "startup complete" grep becomes
   "queued for daisy-chained start" (the completion line can be an hour away). CLAUDE.md
   REST table rows for `/start` `/restart`.

**Tests**
- `StartAttemptRegistryTest`: `begin` is idempotent while open; `finish` retains `lastError`;
  `cancel` flips the flag.
- `BotGroupBehaviorServiceAsyncStartTest`: a start attempt is visible before the runtime
  exists; a second `/start` while in flight does not submit a second task; `stop` during a
  build cancels it and leaves no runtime; `isGroupRunning` is true while an attempt is open.
- `StartupChainTest`: groups start one at a time, in order, and a failing group does not
  stop the chain; the "queued" line precedes the first start; SCHEDULED groups are still
  skipped.
- `ActivationSchedulerAsyncStartTest`: a START decision does not block the tick; the next
  tick sees the group as running.
- `BotGroupControllerTest`: `/start` → 202 + body; `/restart` → 202; 404 and the two 400s
  are still synchronous; `runWithManualOverride` restores the mode on async failure.

### Phase 3 — Enforcement behind `mode=enforce`

> **Amended — the change list below is too narrow. See A6 Phase 3 for the nine items Dev
> implements, including the `authenticate` rewrap defect (A4) the compliance pass found.**

**Changes**
1. `SlidingWindowGatewayBudget`: the three waiter queues, `admitWaiters()`, per-tier
   ceilings and max-waits, wake-up scheduling at the earliest expiry (AD-6), `reserve` (AD-7),
   `cancelScope` (AD-8), `tryExecute` semantics, `Retry-After` computation. `mode=observe`
   keeps Phase 1 behaviour byte-for-byte.
2. `BotGroupBehaviorService.createBotsInParallel`: `reserve(ESSENTIAL, demand, groupScope)`
   with release in `startLocked`'s `finally`; `stop()` → `budget.cancelScope(id)` before
   locking (AD-16); the start INFO line (AD-18).
3. `Bot.checkBalance` → `tryExecute(DEFAULT, …, ZERO)` + `balanceReadDeferred`;
   `onNewSession` pre-deposit PRIORITIZED refresh (AD-10) in both `BettingMiniGameBot` and
   `SlotMachineBot`. `performReauth` / `deposit` non-terminal handling of
   `GatewayBudgetException` (AD-9).
4. `ApiGatewayClient.registerUsers`: registration wait override; `BotGroupService.save`:
   complete-failure-on-budget → `GatewayBudgetExhaustedException` (AD-19).
   `RestExceptionHandler`: the 429 and 503 arms with `Retry-After` (AD-11).
5. Throttle edge WARN/INFO lines (AD-20); queue-depth and wait panels on
   `per-environment.json`; `docker-compose.yml` passes `BOT_GATEWAY_BUDGET_MODE`.
6. Staging: set `GATEWAY_BUDGET_MODE=enforce` in `secrets.env`.

**Tests** (all fake-clock, no HTTP)
- `SlidingWindowGatewayBudgetAdmissionTest`: DEFAULT stops at 500, PRIORITIZED at 750,
  ESSENTIAL at 900; nothing passes 900 whatever the tier mix; strict priority (an ESSENTIAL
  arriving after 100 queued DEFAULTs is admitted first); FIFO within a tier (ticket order);
  a slot freed by expiry wakes exactly the right head.
- `SlidingWindowGatewayBudgetWaitTest`: DEFAULT times out at 30 s with
  `GatewayBudgetExhaustedException` carrying the right `retryAfter`; ESSENTIAL never times
  out; `tryExecute(ZERO)` returns empty without stamping.
- `SlidingWindowGatewayBudgetReservationTest`: `reserve(ESSENTIAL, 300)` shrinks DEFAULT to
  200 and PRIORITIZED to 450; consumption by matching scope; release returns the remainder;
  TTL retires a leaked reservation.
- `SlidingWindowGatewayBudgetCancellationTest`: `cancelScope` wakes only matching waiters
  with `GatewayRequestCancelledException`, nothing is stamped, other tiers' order is
  preserved; an interrupted waiter restores the flag.
- `SlidingWindowGatewayBudgetObserveModeTest`: in `observe` a 2,000-request burst is admitted
  instantly and only the gauges move.
- `BotBudgetOutcomeTest`: `performReauth` on `GatewayBudgetExhaustedException` stays
  RECONNECTING and continues the loop; `deposit` on budget exception increments nothing and
  leaves `expectedCurrentBalance` alone; `checkBalance` deferred path returns the cache and
  sets the flag; the pre-deposit refresh runs at PRIORITIZED and suppresses the deposit when
  the fresh figure is above minimum.
- `BotGroupBehaviorServiceReservationTest`: demand = `botCount × 3` with
  `count-ws-upgrades=true`, `× 2` without; released on success and on failure.
- `RestExceptionHandlerTest`: 429 + `Retry-After` + `{type: "Gateway budget exhausted"}`;
  503 + `Retry-After` + `{type: "Gateway edge block"}`.
- `BotGroupServiceRegistrationBudgetTest`: complete budget failure → 429; partial → today's
  partial semantics.

### Phase 4 — Cloudflare block detection, circuit breaker, login in-repo

> **Renumbered to Phase 5.** Async registration is the new Phase 4. See A6.

**Changes**
1. `CloudflareBlockDetector` (`bot-engine`, `infrastructure/gateway`) per AD-13, both entry
   points; fixture = `docs/reviews/WIN79_119_PROD_ACCOUNTS/cf-block-raw.txt` copied into test
   resources.
2. `SlidingWindowGatewayBudget`: circuit state machine, `reportEdgeBlock(endpoint, cfRay)`,
   half-open clearance probe on the budget's scheduler (the probe URL is
   `apiGateway + VERIFY_TOKEN_ENDPOINT` — the budget receives the gateway base URL at
   creation), `gateway_circuit_*` and `gateway_edge_blocks_total` meters, the ERROR/INFO
   lines. In `observe` mode detection logs a WARN and opens nothing.
3. `ApiGatewayClient`: the funnel classifies every response before parsing; login moves
   in-repo (AD-12), `AuthClient` import reduced to `generateFingerprint`; `UpstreamLoginException`
   message now carries the envelope `status`/`code`/`message` when the body is JSON.
   `Bot.connectUnderBudget` classifies `WebSocketClientHandshakeException` causes.
4. `DeadGroupRecoveryScheduler.evaluateCandidate`: skip on open circuit (AD-13).
5. `GatewayEdgeBlocked` rule + `AlertRulesAudienceTest`; circuit panel on the dashboard.
6. `StubGateway` + `StubGatewayMain` (AD-22) — needed here for the tests below.
7. `GatewayCallSiteGuardTest`: `new AuthClient(` rule becomes `== 0`.

**Tests**
- `CloudflareBlockDetectorTest`: the captured page is `EDGE_BLOCK`; a JSON 403 from the
  gateway is not; a 403 without `server: cloudflare`/`cf-ray` is not; a 429 with `cf-ray`
  is; `WebSocketClientHandshakeException` with a Cloudflare 403 `response()` is.
- `GatewayCircuitBreakerTest` (fake clock): block → OPEN, all tiers refused, ESSENTIAL
  parks, DEFAULT times out with 503-shaped exception; at cooldown exactly one probe; probe
  blocked → OPEN again; probe answered → CLOSED and waiters resume in priority order;
  `observe` mode never opens.
- `ApiGatewayClientLoginTest` against `StubGateway`: for each `LoginRequest` implementation
  the stub receives the same method, path, headers and body bytes that a recorded
  `AuthClient` request produced (record once with the library, in the test, on the stub —
  no network); tokens parsed identically; envelope errors surface in
  `UpstreamLoginException`; block page → `GatewayCircuitOpenException` and circuit open.
- `ApiGatewayClientBlockTest`: block page on `verifytoken`/`deposit`/`register`/
  `update-fullname` opens the circuit; the response body never reaches a log at INFO.
- `DeadGroupRecoverySchedulerCircuitTest`: open circuit → candidate skipped, no attempt
  charged, outcome tag `circuit_open`.
- `PerBotInfoLogGuardTest` / log-level tests: no new INFO in `Bot`/`ApiGatewayClient`.

### Phase 5 — Escalation harness, docs, default flip, prod

> **Renumbered to Phase 6, and the escalation harness moves to Phase 3** so the cap is proven
> before the single deployment. See A6.

**Changes**
1. `GatewayBudgetEscalationIT` (`bot-engine`, JUnit `@Tag("stub-gateway")`, excluded from the
   default surefire run, real clock, ~7 minutes): boots `StubGateway`, a real
   `SlidingWindowGatewayBudget` in `enforce`, and drives 910 / 930 / 950 / 970 / 990 / 1,010
   requests through `ApiGatewayClient` as mixed tiers; asserts on the **stub's own** sliding
   count (never above 900 at any instant), that DEFAULT admissions stop at 500, that the
   overflow is admitted only after the first stamps expire, and that a block-mode flip mid-run
   stops all traffic except one probe per cooldown.
2. CLAUDE.md: a "Gateway request budget" section under Architecture (tiers, config, modes,
   expected start durations, the never-against-a-real-gateway rule, the `bulk-create-accounts.py`
   caveat from Open Item 11).
3. `bot.gateway.budget.mode=enforce` compiled default after ≥ 7 days of staging in enforce
   with V3 read daily; compose default follows. Prod enable is a `GATEWAY_BUDGET_MODE`
   env change inside a ticketed `Prod-Bot` window (MEMORY: 12 h max), on a day with no
   group launches planned.

**Tests**
- The IT above (run by the Releaser on the laptop before Phase 5's deploy — V5).
- `ApplicationContextLoadsTest`: default mode is `enforce` after the flip.

---

## Implementation Notes / Concerns

1. **Where the wait happens matters.** `execute` parks the *caller's* thread. Group-start
   callers are virtual (`botCreationExecutor`, the group executor); reconnect loops are
   virtual (`Thread.ofVirtual().name("reconnect-…")`); the ws-parser message-processor
   threads that run `onEndGame → onNewSession` are the library's — that is why drift reads
   are `tryExecute(ZERO)` (AD-10) and never block there.
2. **Stamp on admission, not on completion, and not on failure of the wait.** A timed-out or
   cancelled waiter never stamped; a request that was admitted and then failed did. The
   `requests_total{outcome}` counter and the window must agree on this or the dashboard lies.
3. **`ReentrantLock` + `CompletableFuture` per waiter**, not `Condition.signalAll`. With
   3,000 ESSENTIAL waiters and ~3 admissions/s, a broadcast wakes 9,000 threads/s for
   nothing.
4. **Wake-up timer must be single-thread and idempotent.** Schedule at
   `oldestStamp + window`; on each `admitWaiters()` reschedule if the earliest expiry moved.
   Use `LongSupplier nanos` from the settings for testability; never `System.currentTimeMillis`.
5. **Reservation arithmetic can go negative** — floor every effective ceiling at 0. A
   reservation larger than the window (3k × 3 = 9k) simply zeroes the lower tiers until it
   is consumed or released; that is the intended strict priority. Whether PRIORITIZED should
   keep a trickle is Open Item 5, not a bug.
6. **`stop()` ordering is load-bearing:** cancel attempt → cancel scope → lock. Reverse it
   and a `/stop` waits out the start it is trying to stop. Add a comment that says so where
   the reclaim comment lives (`:394-415`).
7. **The ActivationScheduler's `running` read** is `isGroupRunning`, which becomes
   registry-aware (AD-16). `RecoveryCandidateSelector.java:82` reads the same method; both
   get the new semantics for free. Check `EnvironmentController.enrichWithBotGroupStats`
   (`:139-152`) still reads sensibly — it will count a starting group as running with
   `getRunningBotCountForGroup` = 0, which is honest.
8. **`runWithManualOverride` restore on async failure**: the controller's `catch` no longer
   sees the failure; pass a `Runnable onFailure` into `startAsync` or restore inside the
   registry's `finish(id, error)` callback. Test it (Phase 2).
9. **`restart()` under async**: `stop(id,false)` + `Thread.sleep(2000)` + `start(id)` +
   zero-bot check all run in the task; `statusBeforeRestart`/`restoreStatusAfterFailedRestart`
   (`:1233-1275`) keep working unchanged because they are inside `restart()`.
10. **The startup chain and `/start` can race on the same group** — the per-group lock plus
    `putIfAbsent` on the attempt make the second one a no-op. The chain must call
    `start(id)` through the attempt registry too (origin `STARTUP`) so `/status` shows it.
11. **Classifier before parser, on every funnel response.** The block page is HTML; today
    `mapper.readValue(responseBody, …)` at `:314` throws a `JsonParseException` that becomes
    "Failed to register …: Unexpected character ('<'…" — the exact symptom that was
    misdiagnosed for an hour on 2026-09-17. After Phase 4 the message names the block and
    the cf-ray.
12. **Keep the login body producer identical.** `loginRequestFactory.apply(ctx)` with
    `ctx = authContext.withFingerprint(fingerprint)` — the library builds the context *with
    the per-call fingerprint* (`AuthClient.java:113`); `ApiGatewayClient` already constructs
    `AuthContext` with `credentials.getFingerprint()` (`:126-134`), so the values agree, but
    the recorded-request test in Phase 4 is what proves it per brand.
13. **`DeadGroupRecoveryScheduler`'s tick may now block for the paced duration of one group
    start.** Its javadoc already says the tick blocks for one start (AD-9 there);
    `scheduleAtFixedRate` catches up with at most one immediate run. Nothing to change,
    one sentence to add to its javadoc.
14. **Metric cardinality:** `{environmentId, product}` × 3 tiers × 4 outcomes — tens of
    series per environment, fine. `endpoint` on `gateway_edge_blocks_total` is a bounded
    enum of the six paths, never a URL.
15. **Log tiering:** the only new INFO-rate lines are per group start, per env per 5 min,
    and edge-triggered throttle/circuit transitions with a re-arm. `PerBotInfoLogGuardTest`
    must stay green; `ApiGatewayClient.registerUsers`' two existing INFO lines (`:181,231`)
    are per registration, which is per operator action, and stay.
16. **`BotGroupControllerTest` and `BotGroupConfigValidationIT`** mock `behaviorService.start`;
    they need the `startAsync` seam and the 202 assertions (Phase 2).
17. **`ApiGatewayClient` is prototype-scoped and `init`ed after construction** (`:91-102`).
    The budget is an `init` parameter, not a constructor injection, so the bean definition
    is untouched and `checkInitialized()` covers it.
18. **The escalation IT takes real minutes.** It must be excluded from `mvn clean install`
    (surefire `excludedGroups=stub-gateway`) or the Releaser's build gains seven minutes and
    a flaky-on-a-slow-laptop test.

---

## Open Items

> **Amended 2026-09-23 — Open Items 2, 3, 4 and 5 are CLOSED by user decision and Item 1 is
> answered provisionally. See Amendment A9 for the closing state of every item and for what
> is still needed from the user. The list below is the original text.**

1. **Do WS upgrades count toward the Cloudflare rule?** The WS hosts
   (`…-sock.stgame.win`, `s009-ws-proxy-119.stgame.win`) may or may not sit behind the same
   rate-limit rule as the `/gwms/v1/*` API host. Default `count-ws-upgrades=true` is the
   conservative choice and costs up to a third of the window on a start; ask SA, flip the
   flag per finding. Related: does the rule count 4xx responses (probes) — assumed yes.
2. **`BotGroupStatus.STARTING` or the additive block?** AD-17 adds a nullable `startAttempt`
   block and keeps the enum. If the UI would rather branch on `actualStatus == STARTING`,
   that is a contract change to decide before Phase 2.
3. **`/start` and `/restart` return 202 with a JSON body instead of 200 with none.** Any UI
   code that treats a non-200 as failure, or parses an empty body, needs to know before
   Phase 2 ships.
4. **Registration stays synchronous (AD-19).** A 300-bot registration is ≥ 600 DEFAULT
   requests ≈ two windows ≈ 10 min on one HTTP call, and can be starved further by a group
   start. Is there a proxy timeout in front of `:8085` that would cut it? ASYNC_REGISTRATION
   must inherit: per-user progress persisted on the group, resumability, the same tier and
   the 202 shape from AD-15. Until then, create large groups in batches of ≤ 200 or with
   `existingGroup=true`.
5. **`essential.ceiling = hard-cap` (as specified) vs 850.** With ESSENTIAL at the cap, a
   3k-bot start starves PRIORITIZED for its whole duration: other groups on the same
   environment cannot re-auth or deposit for ~35 minutes (AD-9 makes that non-fatal — they
   retry — but reconnecting bots stay disconnected). Setting `essential.ceiling=850` leaves
   50 requests per window for the tiers below at the cost of ~6% on start duration. The knob
   exists; the default follows the user's call.
6. **Startup chain order.** `findByTargetStatus(ACTIVE)` is Mongo natural order. If prod
   groups should come up before staging-like ones, or a product first, that is a sort key
   to add to the chain.
7. **Cooldown length.** 15 minutes is a guess; the one observed block outlived an hour. Ask
   SA whether the block has a fixed duration; if it is "until manually cleared", the probe
   cadence could be 30-60 minutes.
8. **Prod enable window.** Phase 5's `enforce` flip on `Prod-Bot` needs a ticketed window
   and a day with no planned launches (MEMORY, "Prod-Bot access is ticket-gated").
9. **EWMA cadence estimation** — deferred by design. Reservations replace prediction for
   the shapes we have (start, recovery, registration); an EWMA would only matter if
   steady-state traffic ever approached the DEFAULT ceiling, which at 1% drift reads it
   does not.
10. **Periodic logout arms a spurious reconnect loop** (Findings). One extra WS upgrade and a
    close race per group per hour. File as `FOLLOWUPS.md` P13; the fix is `logout()`
    setting `stopped` semantics or suppressing the listener for the duration, and it is a
    BOT_LIVENESS_SEMANTICS matter, not a budget one.
11. **`scripts/bulk-create-accounts.py` is invisible to the app's budget.** If it is ever run
    from a host that also runs bot-manager against the same gateway, the two share the
    1,000 without knowing it. Rule: run it only from a host with no bot-manager on that
    brand, or set its `--limit` to the app's spare headroom. Goes into CLAUDE.md in Phase 5.
12. **Follow-ups to file:** `EnvironmentClientRegistry.removeClients` has no caller, so
    neither registry ever evicts (harmless, leaks one small object per deleted env);
    `getBalance`'s 500 ms sleep (`:486`) predates every reason it might have had;
    `GameMsClient` is dead code.

---

## Verification

Releaser runs these on Bot-1 (staging) after each phase's deploy. Bot-1's single-compose
layout means every bot-manager redeploy restarts Grafana/Prometheus/Loki too (MEMORY), so
the smoke check re-verifies them. **No step below sends anything to a gwms host that the
app would not have sent anyway; the escalation test is laptop + stub only.**

### Universal smoke (every phase)

**V0a — app up.**
```bash
until curl -sf http://<bot-1>:8085/actuator/health | grep -q '"status":"UP"'; do sleep 2; done
```
Expect: returns within 60 s. **From Phase 2 on this is true even with a large fleet**, because
startup no longer blocks on group starts.

**V0b — startup lines.**
```bash
docker logs bot-manager 2>&1 | grep -E "Started Starter|queued for daisy-chained start|startup complete"
```
Expect (Phase 1): `Started Starter` and `Bot Manager startup complete`.
Expect (Phase 2+): `Started Starter` and `Bot Manager startup: N bot groups queued for
daisy-chained start` within 60 s; `startup complete` follows when the chain ends (minutes on
staging). Update `docs/process/AGENTIC_WORKFLOW.md` accordingly in Phase 2.

**V0c — observability survived.**
```bash
curl -sf http://<bot-1>:9090/-/healthy && curl -sf http://<bot-1>:3000/api/health
```
Expect: `Prometheus Server is Healthy.` and `"database":"ok"`.

### Phase 1 — observe mode

> Runs at the single deployment like every other block; see A7 for the order.

**V1a — budget wired and in observe.**
```bash
docker logs bot-manager 2>&1 | grep -E "Gateway budget registry started \(mode=observe"
```
Expect: exactly one line, carrying `hard-cap=900, ceilings default=500 prioritized=750 essential=900`.

**V1b — every call site counts.** Start one ~50-bot group `<GID>` on environment `<ENV>`:
```bash
curl -s -X POST http://<bot-1>:8085/api/v1/bot-group/<GID>/start -o /dev/null -w '%{http_code}\n'
sleep 120
curl -s http://<bot-1>:8085/actuator/prometheus | grep -E '^gateway_budget_(window_requests|requests_total)\{[^}]*environmentId="<ENV>"'
```
Expect: `200` (Phase 1 still synchronous); `gateway_budget_window_requests{environmentId="<ENV>",…}`
between `100` and `160` (50 logins + 50 upgrades + 50 first reads + a few drift reads);
`gateway_budget_requests_total{…,tier="ESSENTIAL",outcome="admitted"} >= 150`;
`tier="DEFAULT"` present (may be 0); no series with `outcome="timeout"` above 0.

**V1c — the rollup carries the window.**
```bash
grep -h '"message":"env <ENV>' logs/console.log | tail -1
```
Expect: the line contains `gateway=<n>/900 queued=0/0/0 circuit=closed`.

**V1d — near-cap rule loaded.**
```bash
curl -s http://<bot-1>:9090/api/v1/rules | grep -o '"name":"GatewayBudgetNearCap"'
```
Expect: one match; `curl -s http://<bot-1>:9090/api/v1/alerts | grep -c GatewayBudgetNearCap`
→ `0`.

**V1e — nothing waited.**
```bash
curl -s http://<bot-1>:8085/actuator/prometheus | grep -E '^gateway_budget_wait_seconds_max\{' | awk '{print $2}' | sort -n | tail -1
```
Expect: `< 0.01` (observe mode never parks).

### Phase 2 — async start

> **Amended — `202` is now `200` and `startAttempt` is now `status: "STARTING"` plus
> `botsUp`/`botCount`. Corrected steps in A8.**

**V2a — 202 and progress.**
```bash
curl -s -X POST http://<bot-1>:8085/api/v1/bot-group/<GID>/start -w '\n%{http_code}\n'
curl -s http://<bot-1>:8085/api/v1/bot-group/<GID>/status
```
Expect: `202` with a JSON `BotGroupStatusDTO` whose `startAttempt.phase` is `PENDING` or
`BUILDING`; the second call shows `startAttempt.botsUp` rising toward `botsTotal`, then
`startAttempt: null` and `actualStatus: "ACTIVE"`.

**V2b — double start is one attempt.** Issue `/start` twice within a second; expect both
`202`, then `docker logs bot-manager 2>&1 | grep -c "start admitted.*<GID>"` → `1`.

**V2c — stop during a start.** `/start` a ≥ 100-bot group, wait 3 s, `/stop`:
expect the stop `200` within 5 s, `/status` shows `actualStatus: "STOPPED"`, and
`docker logs … | grep -E "start of group <GID> cancelled"` → one line.

**V2d — restart is async.** `/restart` → `202`; `/status` shows `startAttempt.origin: "REST"`.

**V2e — 404/400 stay synchronous.** `/start` on an unknown id → `404`; on a group with no
`gameId` → `400 {type:"Bad request"}`.

**V2f — the chain.** `docker compose restart bot-manager`, then V0b (Phase 2 form), then
`docker logs … | grep -E "Auto-starting bot group"` — expect the lines to appear one group
at a time, each after the previous group's `started successfully` line.

### Phase 3 — enforce on staging (`GATEWAY_BUDGET_MODE=enforce`)

**V3a — mode.** `docker logs bot-manager 2>&1 | grep -E "Gateway budget registry started \(mode=enforce"` → one line.

**V3b — realistic shape: two ~300-bot groups back to back on one environment.**
```bash
curl -s -X POST http://<bot-1>:8085/api/v1/bot-group/<G1>/start -o /dev/null -w '%{http_code}\n'
curl -s -X POST http://<bot-1>:8085/api/v1/bot-group/<G2>/start -o /dev/null -w '%{http_code}\n'
```
Expect: `202` twice. Then, after ≤ 20 minutes:
```bash
curl -s 'http://<bot-1>:9090/api/v1/query?query=max_over_time(gateway_budget_window_requests{environmentId="<ENV>"}[30m])'
curl -s 'http://<bot-1>:9090/api/v1/query?query=max_over_time(gateway_budget_queue_depth{environmentId="<ENV>",tier="ESSENTIAL"}[30m])'
curl -s http://<bot-1>:8085/api/v1/bot-group/<G1>/health | grep -o '"connectedBots":[0-9]*'
curl -s http://<bot-1>:8085/api/v1/bot-group/<G2>/health | grep -o '"connectedBots":[0-9]*'
```
Expect: window max **`<= 900`** (this is the assertion the whole plan exists for); ESSENTIAL
queue depth `> 0` at some point; both groups `connectedBots` ≥ 95% of `botCount`; combined
wall-clock ≈ `ceil((demand1 + demand2) / 900) × 5` minutes, matching the two "start admitted
… estimated <m> min" INFO lines.

**V3c — DEFAULT throttled, PRIORITIZED not fatal.** During V3b:
```bash
docker logs bot-manager 2>&1 | grep -E "DEFAULT tier throttled|tier throttle cleared"
curl -s 'http://<bot-1>:9090/api/v1/query?query=increase(bot_failures_total{environmentId="<ENV>"}[30m])'
```
Expect: one `throttled` WARN and one `cleared` INFO per tier that was throttled; no
`bot_failures_total` increase attributable to budget (cross-check
`gateway_budget_requests_total{outcome="timeout"}` — DEFAULT timeouts are expected,
PRIORITIZED timeouts should be `0` unless Open Item 5 is left at the cap and a reconnect
coincided).

**V3d — registration rejection shape.** Create a group of 5 bots while V3b is running
(DEFAULT is starved): expect either `200` within 15 minutes or
`429 {type:"Gateway budget exhausted"}` with a `Retry-After` header, never `502`.

**V3e — stop cancels queued work.** `/start` a 300-bot group, wait 10 s, `/stop`: expect
`200` within 5 s; `gateway_budget_requests_total{…,outcome="cancelled"}` increased; window
count did **not** jump by the cancelled amount.

**V3f — the rollup during a start.** `grep -h '"message":"env <ENV>' logs/console.log | tail -3`
→ lines show `gateway=8xx/900 queued=<n>/0/<m>` while queued, back to `queued=0/0/0` after.

### Phase 4 — block detection and circuit

> **These are Phase 5's steps now (renumbered).** New Phase 4 (registration) steps are in A8.

**V4a — detector present, circuit closed.**
```bash
curl -s http://<bot-1>:8085/actuator/prometheus | grep -E '^gateway_circuit_open\{' 
curl -s http://<bot-1>:9090/api/v1/rules | grep -o '"name":"GatewayEdgeBlocked"'
```
Expect: one `gateway_circuit_open{environmentId=…} 0` per environment with a budget; rule
present; `gateway_edge_blocks_total` series present at `0`.

**V4b — no synthetic traffic while closed.** Over 30 minutes,
`increase(gateway_budget_requests_total{outcome="admitted"}[30m])` on an idle environment
→ `0`, and `docker logs … | grep -c "circuit-probe"` → `0`.

**V4c — in-repo login works on every brand staging has.** For each product with a staging
environment (at least 097/BOM, 116/TIP, 114/RIK, 119/WIN79): start a 5-bot group and
```bash
curl -s http://<bot-1>:8085/actuator/prometheus | grep -E '^bot_login_total\{[^}]*outcome="success"[^}]*product="<P>"'
```
Expect: increases by 5 per product; `outcome="failure"` unchanged; `/health` shows 5
`connectedBots`. This is the regression gate for AD-12 — **if any brand fails here, Phase 4
does not proceed to prod.**

**V4d — block handling, laptop + stub only.** With `StubGatewayMain` running and a local app
whose `Environment` points at it: start a 20-bot group, flip the stub to block mode
(`curl -X POST http://127.0.0.1:<port>/__stub/block`), start another 20-bot group. Expect:
one `ERROR … Cloudflare edge block on login, cf-ray a3c7e4004acc850e-HKG — circuit open for
PT15M`; the stub's request log shows **zero** requests for the next 15 minutes except one
`verifytoken.aspx?token=probe`; after `curl -X POST …/__stub/unblock` and the next probe,
`INFO … circuit closed` and the second group completes. Record the stub's per-window max in
`release.md`.

### Phase 5 — escalation harness and default flip

> **V5 moves to Phase 3** (it gates the single deployment); **V5b/V5c stay with the default
> flip, now Phase 6.** See A8.

**V5 — escalation, laptop + stub only, before the deploy.**
```bash
export JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home
cd /Users/gleb/IdeaProjects/Bot && mvn -pl bot-engine -am test -Dgroups=stub-gateway -Dtest=GatewayBudgetEscalationIT
```
Expect: `BUILD SUCCESS`; the test's summary line prints, for each of 910, 930, 950, 970,
990, 1,010 submitted: `stub max-in-window <= 900`, `DEFAULT admitted <= 500`, `throttled
from <n>` with `n < 900`, and `overflow admitted after first expiry`. Paste the six lines
into `release.md`.

**V5b — default flipped.** After deploy with no `GATEWAY_BUDGET_MODE` in `secrets.env`:
`docker logs bot-manager 2>&1 | grep -E "Gateway budget registry started \(mode=enforce"` → one line.

**V5c — prod.** Same V3a + V4a on `Prod-Bot` inside the ticketed window, then V3b's window
query over the next scheduled launch (`TIP_PROD_TAIXIU_RAMP` uses 10 × 50-bot windows):
`max_over_time(gateway_budget_window_requests[24h]) <= 900` per environment.

---

## Amendment — 2026-09-23 (user decisions on Open Items 1-5; Phase 1 shipped)

Phase 1 is on `feature/gateway-request-budget` (`7c7c3b1`..`b741d92`, QA / review / compliance
all PASS) and is **not deployed** — see A7. The user has answered every open item; two answers
change the plan's shape rather than a parameter, so they are written here as new ADs rather
than edited into the originals.

Inputs read for this amendment: `docs/reviews/GATEWAY_REQUEST_BUDGET/compliance.md` (nine
deviations, all accepted; seven drift notes D1-D7 for later phases), `review.md` (F1-F12),
`qa.md` (2,196 tests green; gaps G1-G6).

---

### A1 — AD-17a: `BotGroupStatus` gains three appended constants; `startAttempt` is dropped

**Supersedes AD-17.** The user chose the enum over the additive block. The enum is the one
field a client reads for the whole lifecycle:

```
REGISTRATION_PENDING → REGISTRATION_FAILED        (Phase 4)
(null / STOPPED) → STARTING → ACTIVE | DEAD       (Phase 2)
```

**The constants are appended, never inserted.** `BotSortKey.STATUS`
(`bot-app/.../sort/BotSortKey.java:29`) sorts on `actualStatus()` as a `Comparable` enum, i.e.
on `ordinal()`. Appending keeps `ACTIVE(0) < STOPPED(1) < DEAD(2)` and therefore every existing
sort result byte-identical; inserting would silently reorder the `STATUS` sort for every
existing group. Final declaration order: `ACTIVE, STOPPED, DEAD, STARTING,
REGISTRATION_PENDING, REGISTRATION_FAILED`.

**Which field can hold which value — this is the load-bearing half of the decision.**

| Value | `BotGroup.targetStatus` (Mongo) | `BotGroupRuntime.actualStatus` (memory) | DTOs |
|---|---|---|---|
| `ACTIVE` / `STOPPED` / `DEAD` | yes (unchanged) | yes (unchanged) | yes |
| `STARTING` | **never** | **yes** | yes |
| `REGISTRATION_PENDING` / `REGISTRATION_FAILED` | **never** | **never** | **yes — derived only** |

- **Nothing new is ever persisted into `targetStatus`.** That is a rollback requirement, not
  tidiness: `targetStatus` is stored as the enum `name()` string with no
  `MongoCustomConversions` (CLAUDE.md, "The persisted and wire shapes did not change"), so a
  document holding `STARTING` read back by an **older** jar throws
  `ConversionFailedException` — and `findByTargetStatus(ACTIVE)` is on the `onStartup` path,
  so one poisoned document would fail the whole boot query. Rolling back to
  `vingame-bot:rollback-*` must stay a safe action at all times.
- **`STARTING` is in-memory only.** `BotGroupRuntime` is constructed `STARTING` (today
  `ACTIVE`, `BotGroupRuntime.java:164`) and flipped to `ACTIVE` at the end of a successful
  `startLocked`, next to the existing `group.setTargetStatus(ACTIVE)` (`:623`). A JVM restart
  loses it, which is correct: an interrupted start is not a state to resume.
- **The registration values are derived at the DTO boundary** from two new *additive document
  fields* — `registeredCount` (int) and `registrationState` (**String**: `PENDING`, `FAILED`,
  or absent = complete/legacy) — plus `registrationError` (String). New fields are invisible
  to an older jar (Mongo keeps unknown fields; the mapper ignores them), so a rollback with a
  registration in flight degrades to "the group looks created" rather than to a boot failure.
  A `String`, not an enum, for the same reason one level down.
- **On completion, `targetStatus` is left `null`** — byte-for-byte the state a synchronously
  registered group has had since day one. So the only new values a UI ever sees are the two
  registration ones, and only while registration is in flight or failed.

**Consumer audit** (every reader of `BotGroupStatus` in `bot-app/src/main`; there are no
readers in `bot-engine` or `bot-api`):

| Consumer | Effect of the three new constants | Action |
|---|---|---|
| `BotGroupBehaviorService.startLocked` reclaim guard `:403-415` | tests `== ACTIVE` ⇒ a `STARTING` runtime would be **torn down and rebuilt** | **must change**: treat `STARTING` like `ACTIVE` (keep, no-op). Unreachable in practice (the per-group lock serialises starts and the attempt registry rejects the second `/start`), which is exactly why it must be written down rather than relied on |
| `isGroupRunning` `:1608` | `== ACTIVE` ⇒ false while starting | **must change** to `ACTIVE || STARTING` — this is AD-16's mechanism, and `ActivationScheduler:130` and `RecoveryCandidateSelector:82` inherit it for free |
| `countOpenWsByEnvForActiveRuntimes` `:1915-1919` | skips non-`ACTIVE` ⇒ a starting group with open sockets stops counting as a live sibling | **leave**; costs at most one extra anonymous probe per tick (DEAD_GROUP_AUTO_RECOVERY AD-10) and never a wrong recovery decision |
| `performPeriodicLogout` `:2425` | gates on `== ACTIVE` | **leave** — the logout scheduler is not started until after the build anyway |
| `monitorHealth` / `handleBotGroupDeath` / `markAsDead` | DEAD-only logic | no change |
| `RecoveryEligibility.isCandidate` `:85,94,98` | branches on `ACTIVE` / `STOPPED` / `DEAD` only, with an else-ineligible default | no change — a `STARTING` runtime status is never passed (the selector maps to `DEAD`/`ACTIVE`/`null`), and `isGroupRunning` now covers starting groups |
| `RecoveryCandidateSelector:79-88` | derives runtime status from `listDeadRuntimeGroupIds` + `isGroupRunning` | no change beyond the `isGroupRunning` widening above |
| `BotSortKey.STATUS` / `SortComparators.compareNaLast` | ordinal sort | no change **given append-only** — pin it with a test |
| `findByTargetStatus` (`BotGroupRepository:16`, `BotGroupService:77`) | Mongo query on the persisted field | no change — nothing new is persisted there |
| `BotGroupDTO.targetStatus` `:142`, `BotGroupStatusDTO` `:23,26`, `BotGroupHealthDTO.status` `:21` | serialise the enum by `name()` | **UI contract change**: three new strings can appear. `BotGroupDTO.targetStatus` can still only carry the original three |
| Prometheus / Grafana | **no metric label anywhere carries `BotGroupStatus`** — `bots_by_env_status` / `bots_by_game_status` carry `BotStatus` (per-bot) and `groups_dead_by_env` is a count (`InfoGaugeRefresher.java:26-38`) | no change, no alert rule affected |
| `prometheus/alerts.yml` | mentions DEAD only in prose | no change |

**Where progress goes now that the status carries the state.** `StartAttemptRegistry` (Phase 2)
survives unchanged as the in-memory owner of `botsUp`/`botsFailed`, the origin and the
cancellation flag — what is dropped is only the DTO block. `BotGroupStatusDTO` gains four
nullable, explicitly named fields (no generic `progress`/`progressTotal` pair — the meaning
must not depend on the status):

- `botCount` (int) — the target, for both progress senses;
- `botsUp` (Integer) — bots built in the current start; non-null while `actualStatus ==
  STARTING` and retained until the next start;
- `registeredCount` (Integer) — accounts created; non-null once async registration has run;
- `lastError` (String) — the last start or registration failure, whichever is more recent.

`BotGroupHealthDTO` already carries `totalBots`/`connectedBots` and needs only the new `status`
value plus `registeredCount`. `BotGroupDTO` (`GET /{id}`, `POST /{envId}/filter`) gains
`registeredCount` so a list view can render "120/500" without a second call.

**Tests:** `BotGroupStatusAppendOnlyTest` (ordinal positions of the original three are 0/1/2,
the enum has exactly six constants); `BotGroupStatusPersistenceGuardTest` — a source guard in
the `PerBotInfoLogGuardTest` idiom asserting that no production call site passes `STARTING`,
`REGISTRATION_PENDING` or `REGISTRATION_FAILED` to `setTargetStatus`; a Mongo round-trip test
that a group persisted mid-start reads back with `targetStatus` in the original three.

---

### A2 — AD-19a: registration is an asynchronous, resumable job, and the job is the group

**Supersedes AD-19 in full.** The user's shape: the client asks for N bots with settings; the
server returns an ack carrying the group id, having created nothing but the record; the group
carries `REGISTRATION_PENDING`; a worker registers steadily; polling returns in-progress with
count/total. `ASYNC_REGISTRATION` is no longer a separate deferred plan — it is Phase 4 here.

The six inputs already worked out with the user, adopted or rejected with reasons:

1. **The group *is* the job — adopted.** Usernames are `namePrefix + index`
   (`ApiGatewayClient.registerSingleUser:281`), so the job's identity, its work list and its
   progress are all already in the group document; `registeredCount` versus `botCount` is the
   whole state. No job collection, no scheduler table, no id to correlate. **With one
   constraint made explicit: `registeredCount` is a high-water mark that only means "indices
   1..k are done" because the worker is serial and in-order** (A2.4). A parallel worker would
   make the same integer meaningless, which is a second reason the worker stays
   single-threaded.
2. **Resumability across a JVM restart via `onStartup` re-enqueue — adopted**, with the
   registration equivalent of the Phase 2 daisy-chain: an `ApplicationReadyEvent` listener
   enqueues every group whose `registrationState == PENDING`. Ordering: registration before
   the start chain, since a pending group cannot start anyway.
   *Caveat that has to be settled in code:* "a retry of index k is idempotent" is true of
   **our** state but is an assumption about the **gateway** — re-registering an existing
   username returns an error envelope, and its exact shape is not documented anywhere we
   control (the gwms gates are catalogued in `HANDOVER.md`, "Four gates", and three of them
   are HTTP 200). The worker must therefore treat "account already exists" as
   **success-equivalent** and needs the real status/code to do it. Open Item 13.
3. **Pacing from the DEFAULT tier, not a second rate limiter — adopted for `enforce`,
   with one derived fallback.** A single serial worker at ~100-300 ms per call is
   ~200-600 requests per 5-minute window *on its own*, i.e. it can breach the cap unaided when
   the budget is only observing. So: the worker acquires through
   `execute(DEFAULT, scope, …)` with `bot.gateway.budget.registration.max-wait`, and when
   `budget.snapshot().mode() != ENFORCE` it additionally sleeps
   `window / defaultCeiling` (300 s / 500 = **600 ms**) between users. That is not a new knob
   and not a second limiter — it is the same configured ceiling, applied by the only component
   that would otherwise be unpaced. The phase order (enforcement lands in Phase 3) means the
   fallback is a belt, not the mechanism.
4. **One single-threaded worker, `DeadGroupRecoveryScheduler`'s idiom — adopted.**
   `RegistrationWorker`: one virtual-thread `ScheduledExecutorService`, one group at a time,
   in-order indices, per-group isolation in a `try/catch`, MDC set from the group. It is
   **not** a rate limiter and must not grow one.
5. **Status rides `GET /{id}` and `/status`; no new status endpoint — adopted** (A1 puts the
   state in the enum and the counts beside it). **One new *action* endpoint is unavoidable**
   (A2.6) — that is an action, not a status read.
6. **Terminal-with-failures needs an explicit retry — adopted.** After
   `bot.registration.max-attempts-per-user` (default 3) consecutive failures on one index the
   worker **stops the group** (it does not skip ahead — skipping breaks the high-water-mark
   invariant), sets `registrationState=FAILED`, writes `registrationError`, and emits one
   ERROR. Nothing retries it silently. `POST /api/v1/bot-group/{id}/registration/retry` → 200
   + `BotGroupStatusDTO`, clears `FAILED` back to `PENDING` and re-enqueues from
   `registeredCount + 1`. **A Cloudflare circuit-open (Phase 5) or a budget timeout is not a
   failure** — the worker re-queues the group and waits, because those say "not now", not
   "this account cannot be created".
7. **`PATCH botCount` upward extends the target — adopted.** `BotGroupService.update` routes
   through `save` on the existing-group branch, which never registers (`:137-196`), so the
   extension is explicit: if the merged `botCount > registeredCount`, set
   `registrationState=PENDING` and enqueue. This is what makes "register additional bots" a
   product feature instead of a script. **Downward is allowed and never un-registers** —
   `registeredCount` is a fact about accounts that exist, so it may exceed `botCount`;
   progress renders as `min(registeredCount, botCount)/botCount`, and a group at
   `registeredCount >= botCount` is complete. That also gives the operator the clean exit from
   a half-failed 500-bot group: PATCH `botCount` down to what registered, and start it.
8. **`existingGroup=true` stays synchronous — adopted.** It makes no upstream call, so there
   is nothing to pace; it leaves `registrationState` absent and `targetStatus` null, exactly
   as today.

**The two calls the coordinator left open:**

- **A game-less "account factory" group — recommend a follow-up, not this feature.** The
  engine is closer than it looks (`validateGameEnvironmentMatch` early-returns on a null
  `gameId`, `filterSorted` already null-guards it, and `startLocked` already rejects a
  null `gameId` with a 400 — which is the right behaviour for a group that must never
  start), but `BotGroupDTO.gameId` is `@NotBlank(groups = OnCreate.class)` (`:44`), so
  enabling it is a **create-contract change**, and the real use case
  (`scripts/bulk-create-accounts.py`, the 500 `liengbot*` accounts for another team) also
  wants credential export, no deposits and no group lifecycle at all. Folding that in would
  widen this feature past the thing it is for. **Recommendation:** ship Phase 4 for
  game-bearing groups, and add one assertion that a group whose `gameId` is null would
  register and simply never start, so the follow-up is a DTO change and nothing else. Needs
  a user ruling either way (Open Item 14).
- **`/start` on a group that is still registering → `400`**, via the existing
  `BadRequestException` arm (no new status code, no new handler), with a message naming the
  counts and the way out:
  `Bot group <name> is still registering (120/500 accounts). Wait for REGISTRATION_PENDING to
  clear, or PATCH botCount down to 120 to start with the accounts that exist.`
  Same for `REGISTRATION_FAILED`, naming the retry endpoint. The guard sits in `startLocked`
  beside the two existing `BadRequestException` checks (`:429-443`) so **every** entry point
  inherits it — `/start`, `/restart`, the startup chain, `startForRecovery`. `ActivationScheduler`
  needs its own guard as well: it queries `findByActivationMode(SCHEDULED)`, not by status, so
  a SCHEDULED group created mid-registration would otherwise be handed to `startAsync` every
  minute and log a 400's worth of noise each time.

---

### A3 — AD-15a: the ack is `200` with a DTO, for `/start`, `/restart` and `POST /`

**Amends AD-15 and AD-19a.** The user's wording for the create ack was "server sends 200 and
some basic DTO"; that shape applies to the whole lifecycle so a client has one rule. All four
endpoints return **`200`** with a body:

| Endpoint | Body | Meaning |
|---|---|---|
| `POST /api/v1/bot-group/` | `BotGroupDTO` (as today) with `targetStatus` rendered `REGISTRATION_PENDING` and `registeredCount: 0` | the record exists, nothing upstream has been created |
| `POST /{id}/start`, `/{id}/restart` | `BotGroupStatusDTO` (`actualStatus: "STARTING"`, `botsUp`, `botCount`) | accepted, running on a virtual thread |
| `POST /{id}/registration/retry` | `BotGroupStatusDTO` | re-enqueued |

Consequence worth naming: **`/start` keeps returning `200`**, so release step **V1b**'s
`%{http_code}` assertion stays valid and no verification step has to be rewritten for this
decision. The change a client sees is that `200` now means *accepted*, not *finished* — which
is the whole point of Phase 2 and must be in the UI note.

---

### A4 — Phase 3 must fix `authenticate`'s rewrap, or AD-9 cannot work at all

Found by the compliance pass (D1) and adopted verbatim. `GatewayBudgetException` is a
`RuntimeException`, and `ApiGatewayClient.authenticate` has a pre-existing
`catch (RuntimeException e)` arm **outside** the budget funnel that rewraps everything as
`UpstreamLoginException` (`ApiGatewayClient.java:150-163`). From the moment Phase 3 starts
throwing, on the login path only:

- `performReauth` (`Bot.java:782-800`) sees `UpstreamLoginException`, never
  `GatewayBudgetException`, so **AD-9's "a budget outcome must not mark the bot DEAD" can
  never fire** — a paced re-auth kills the bot;
- `classifyCreationFailure` (`:771-806`) takes the `UpstreamLoginException` arm and tags
  `"auth"`, defeating the `"budget"` arm Phase 1 already shipped for exactly this;
- `bot_login_total{outcome="failure"}` counts our own throttling as upstream login failures —
  and that counter is the per-brand regression gate in V4c and the input to
  `EnvironmentLoginFailing`.

**Required in Phase 3:** a `catch (GatewayBudgetException e) { throw e; }` arm **ahead of** the
`RuntimeException` arm, and **no `metrics.incLogin(false)`** on that path — a request the JVM
declined to send is not a failed login. `getBalance` has the milder twin: it rethrows the type
unwrapped (good) but increments `bot_verify_token_total{outcome="failure"}` on the way past
(`:517-524`), which would make `EnvironmentAuthDown` fire on our own pacing; same treatment.
`deposit` catches only `IOException`/`InterruptedException`, so it is already clean.

**Test:** `ApiGatewayClientBudgetPassthroughTest` — for each of the three methods, a budget
that throws `GatewayBudgetExhaustedException` produces that exact type at the caller and moves
**no** `bot_login_total` / `bot_verify_token_total` series.

---

### A5 — the five smaller inheritances, folded into Phase 3

1. **Enforcement must reach `run` and `runWsUpgrade`, not only `execute`/`tryExecute`**
   (compliance D2). Every WS upgrade in the fleet goes through `runWsUpgrade`
   (`GatewayBudget.java:49`); queueing only inside `execute` would stamp upgrades while never
   pacing or refusing them, and AD-8's "cancellation must happen before `connect()` is
   entered" would have no hook at all.
2. **`count-ws-upgrades=false` currently bypasses the budget entirely, not just the stamp**
   (D3). Decision, so Dev does not have to guess: when the flag is off, `runWsUpgrade`
   **still checks cancellation and the circuit, and neither waits on nor consumes the
   window**. "Not counted by the edge" ⇒ "not paced by us"; it does not imply "un-cancellable"
   (a stopped group's queued upgrade must still die) or "sent into an open circuit" (the edge
   is refusing us regardless).
3. **The probe's over-count consumes real ceiling under enforce** (D4) — accepted at ≈5 stamps
   per window (≤0.6% of the cap, and only while a DEAD group is a recovery candidate) rather
   than re-keyed, because a shared `webSocketMiniUrl` genuinely maps to several environments
   and over-counting is the safe direction. Reviewer **F2** is adopted alongside it: `count`
   gains a WS-aware twin (`countWsUpgrade(reason)`) that consults the same
   `count-ws-upgrades` flag, so answering Open Item 1 "no" silences the probe's stamp too and
   the coupling shrinks to nothing. Phase 5's `count("circuit-probe")` is an HTTP GET and
   keeps the unconditional form.
4. **The Phase 6 default flip is a three-file edit** (D5): `application.properties`,
   `GatewayBudgetConfig`'s `@Value` fallback **and** `GatewayBudgetSettings.defaults()`, or
   `ApplicationContextLoadsTest.gatewayBudgetIsWiredAndObserveOnly`'s equality assertion fails
   the build. That is the assertion working; it is written into A6 Phase 6 so the Releaser is
   not surprised. The same applies to A9's `essential.ceiling=850` escape hatch.
5. **Two tripwires must be rewritten, not deleted, when enforcement lands** (QA G2, review F4):
   - `SlidingWindowGatewayBudgetWindowTest.aCancelledScopeIsStillAdmittedInPhaseOne` builds
     its budget from `defaults()`, i.e. `mode=OBSERVE`, and observe keeps Phase 1 behaviour
     byte-for-byte — so **it will not fail when Phase 3 lands** and the assertion it exists to
     force a look at would go unlooked-at. The one that bites is QA's
     `SlidingWindowGatewayBudgetObserveModeTest.aCancelledScopeIsAdmittedInEitherMode`, which
     builds in `ENFORCE`; under Phase 3 it must fail twice (unexpected
     `GatewayRequestCancelledException`, and `windowRequests() == 1` where it must be 0).
     Phase 3 rewrites both.
   - `GatewayBudgetRegistryTest.enforceModeWarnsUntilPhaseThree` pins the "enforce is set but
     NOTHING is being paced" WARN. Left in place after Phase 3 that WARN is a tier-1,
     Loki-visible, actively false statement about a production instance's posture. Phase 3
     deletes the WARN and rewrites the test to assert its absence.
6. Two cheap reviewer findings ride along because the plan's own Implementation Note 2 is
   otherwise contradicted: **F3** — `count()` increments no `requests_total` series, so the
   counter and the gauge provably cannot reconcile; add a fifth bounded outcome
   `outcome="counted"`, pre-registered at zero. **F5** — export
   `gateway_budget_ceiling{tier}` from `registerMeters` so the Grafana panel plots the
   *configured* ceilings instead of three literals, drop the dead `"ESSENTIAL ceiling"`
   field override that matches no target, and express `GatewayBudgetNearCap`'s `800` against
   the exported hard cap.

---

### A6 — the amended phase list

Phase 1 is shipped. The list below replaces the "Plan" section's phases 2-5.

**Phase 2 — async start/restart, `STARTING`, startup daisy-chain.** As the original Phase 2,
with three substitutions: `202` → **`200` + DTO** (A3); the `startAttempt` block and
`StartAttemptDTO` → **`BotGroupStatus.STARTING`** plus `botsUp` / `botCount` /
`registeredCount` / `lastError` on `BotGroupStatusDTO` (A1); and the A1 consumer-audit edits
(`startLocked`'s reclaim guard, `isGroupRunning`, `BotGroupRuntime`'s initial status).
`StartAttemptRegistry` stays. Tests as listed, plus `BotGroupStatusAppendOnlyTest`,
`BotGroupStatusPersistenceGuardTest` and a round-trip test that a mid-start group's persisted
`targetStatus` is one of the original three.

**Phase 3 — enforcement behind `mode=enforce`.** The original five items, **plus**: A4's
`authenticate`/`getBalance` passthrough arms; A5.1 (`run`/`runWsUpgrade` enforcement); A5.2
(the `count-ws-upgrades=false` semantics); A5.3 (`countWsUpgrade` twin); A5.5 (rewrite both
tripwires, delete the enforce WARN); A5.6 (`outcome="counted"`, `gateway_budget_ceiling{tier}`,
the dashboard/alert literals). **The `StubGateway` (AD-22) moves into this phase** — test scope
only, without the block-mode half — so `GatewayBudgetEscalationIT` can prove the cap here,
before the single deployment, instead of two phases later.

**Phase 4 — asynchronous registration (new, A2).**
1. `BotGroup`: `registeredCount` (int), `registrationState` (String), `registrationError`
   (String); `BotGroupRepository.findByRegistrationState("PENDING")`.
2. `BotGroupService.save`: the new-group branch **stops calling `registerUsers`**; it validates,
   persists with `registrationState=PENDING`, `registeredCount=0`, and returns. `skipRegistration`
   (`existingGroup=true`) persists with no registration state at all. `update` detects an
   upward `botCount` and re-enqueues (A2.7).
3. `RegistrationWorker` (`bot-app`, `domain/botgroup/service`): one virtual-thread scheduler,
   one group and one index at a time, from `registeredCount + 1`; per-index
   register + display-name through `ApiGatewayClient` at `DEFAULT` with the registration
   wait override; `registeredCount` persisted after each success; `max-attempts-per-user`
   then `FAILED` + ERROR; budget timeout / circuit-open re-queues instead of failing; the
   observe-mode 600 ms fallback (A2.3); `ApplicationReadyEvent` re-enqueue, ahead of the
   start chain.
4. `POST /{id}/registration/retry` (the one new endpoint) + `BotGroupController` wiring; the
   `startLocked` and `ActivationScheduler` guards (A2, "the two calls").
5. `ApiGatewayClient.registerUsers` is reduced to a single-user call
   (`registerOne(prefix, password, index)`); the `Semaphore` and the fan-out go with the
   bulk method, and with them QA's **G1** hang (`registrationParallelism` is `0` outside
   Spring). `user.registration.parallelism` becomes dead config — remove it and say so in
   CLAUDE.md.
6. Metrics: `registration_pending_groups` gauge, `registration_accounts_total{outcome=
   success|failed|exists}` counter (pre-registered at zero), and one alert —
   `RegistrationStalled` (`registration_failed_groups > 0`, `for: 15m`, warning,
   `audience: internal`), because a half-registered group is precisely the thing that gets
   forgotten. Nothing per account at INFO; one INFO line per group at enqueue and at
   completion (`group <id> (<name>): registration complete, <n>/<n> accounts`).

*Tests:* resume from `registeredCount` after a simulated restart; an "already exists" envelope
counts as success and advances the counter; `max-attempts-per-user` then `FAILED` with the
error persisted; a budget timeout re-queues and does **not** consume an attempt; retry clears
`FAILED` and resumes from `registeredCount + 1`; `PATCH botCount` up re-enqueues, down never
un-registers and completes the group; `/start` on a pending or failed group is a 400 naming the
counts; `ActivationScheduler` skips a registering SCHEDULED group; the worker is serial (two
groups never interleave); `existingGroup=true` makes no upstream call and leaves no
registration state.

**Phase 5 — Cloudflare detection, circuit breaker, login in-repo.** Unchanged from the original
Phase 4, minus the `StubGateway` (now Phase 3) and plus its block-mode half.

**Phase 6 — docs, default flip, prod.** The original Phase 5 minus the escalation IT (now
Phase 3), plus A5.4's three-file warning, plus the CLAUDE.md sections for async registration
and the `STARTING` status.

---

### A7 — release gating: one deployment, after Phase 5

The user wants a single deployment covering everything; nothing ships after Phase 2 or after
Phase 3 alone, and there is **no staging soak between them**.

- **The cut is after Phase 5**, not after Phase 3. Phase 4 is what the user's Open Item 4
  decision requires — shipping Phases 2-3 alone would deploy a tree in which registration is
  still synchronous, which is the thing they rejected. Phase 5 rides along because a box
  running `enforce` without the block detector retries into a Cloudflare block exactly as
  today.
- **The branch must be releasable as a whole at that point, and it is**: each phase leaves
  the tree green and internally consistent (Phase 1 already proved the pattern — observe mode
  changes nothing, in either mode), and no phase depends on a change deferred to a later one.
  The one ordering constraint that matters is that **Phase 3 precedes Phase 4**: a serial
  registration worker in a tree where the budget only observes can breach the cap on its own
  (A2.3), so the worker must never exist in a tree without enforcement.
- **Verification runs as one sequence on that single deploy**, in phase order: V0a-V0c, V1a-V1e,
  V2a-V2f, V3a-V3f, the new V4 block (A8), then V5's block-detection steps. `GatewayBudgetEscalationIT`
  (V5, renamed **V3g**) runs on the laptop **before** the deploy.
- **Phase 6 is a second, smaller deployment** by nature — flipping a compiled default is a
  rebuild — and is the only place a soak is still assumed (≥ 7 days of staging in `enforce`
  before the default flips, per AD-23).
- Staging runs `GATEWAY_BUDGET_MODE=enforce` in `secrets.env` from this deployment onward;
  prod stays `observe` until Phase 6.

---

### A8 — corrected and added verification steps

**V1b** is unchanged and still expects `200` (A3). **V2a-V2f** change only in their assertions:

- **V2a** — `POST /{id}/start` expects **`200`** with a `BotGroupStatusDTO` whose
  `actualStatus` is `"STARTING"`; the follow-up `GET /{id}/status` shows `botsUp` rising toward
  `botCount`, then `actualStatus: "ACTIVE"` with `botsUp == botCount`.
- **V2d** — `/restart` expects `200` and `actualStatus: "STARTING"`.
- **V2b, V2c, V2e, V2f** unchanged in substance; in V2b both calls expect `200`.

**New — V4 block (async registration).**

**V4a — create returns immediately and creates nothing upstream.**
```bash
time curl -s -X POST http://<bot-1>:8085/api/v1/bot-group/ -H 'Content-Type: application/json' \
  -d '{"name":"budget-reg-test","environmentId":"<ENV>","gameId":"<GAME>","namePrefix":"budregt","password":"a123Aa123","botCount":40,...}'
```
Expect: `200` in **under 2 s**, body carries an `id`, `targetStatus: "REGISTRATION_PENDING"`,
`registeredCount: 0`.

**V4b — progress is visible and monotonic.**
```bash
for i in 1 2 3 4 5; do curl -s http://<bot-1>:8085/api/v1/bot-group/<GID>/status \
  | grep -o '"registeredCount":[0-9]*'; sleep 30; done
```
Expect: five non-decreasing values, strictly increasing at least once, ending at `40`; the
status then reads `targetStatus: null` (registration complete) and `registeredCount: 40`.

**V4c — the worker is paced, not bursty.**
```bash
curl -s 'http://<bot-1>:9090/api/v1/query?query=max_over_time(gateway_budget_window_requests{environmentId="<ENV>"}[15m])'
```
Expect: `<= 900` throughout, and `<= 500` attributable to `tier="DEFAULT"` in any window
(`increase(gateway_budget_requests_total{tier="DEFAULT",outcome="admitted"}[5m]) <= 500`).

**V4d — `/start` on a registering group is a clean 400.** Create a 200-bot group and
immediately `POST /{id}/start`:
expect `400` with `{"type":"Bad request"}` and a `msg` containing `still registering` and the
`n/200` counts — **not** a 500, and no bot created.

**V4e — resume across a restart.** During a large registration, `docker compose restart
bot-manager`; then:
```bash
docker logs bot-manager 2>&1 | grep -E "registration: re-enqueued .* pending group"
curl -s http://<bot-1>:8085/api/v1/bot-group/<GID>/status | grep -o '"registeredCount":[0-9]*'
```
Expect: one re-enqueue line naming the group; `registeredCount` resumes from at least its
pre-restart value and continues to `botCount`; **no username is registered twice** — confirm
with `increase(registration_accounts_total{outcome="exists"}[1h])` staying at or below the
number of indices in flight at the restart (0 or 1 for a serial worker).

**V4f — extend by PATCH.** `PATCH /{id}` with `botCount: 45` on a completed group:
expect `200`, `targetStatus` back to `"REGISTRATION_PENDING"`, and `registeredCount` climbing
to `45`.

**V4g — failure is terminal and explicit.** (Only if a failure occurs naturally; do not
manufacture one against a real gateway.) Expect `targetStatus: "REGISTRATION_FAILED"`, a
non-null `lastError`, one ERROR line, `registration_failed_groups == 1`, and no further
requests for that group. `POST /{id}/registration/retry` → `200` and the count resumes.

**V3g (was V5)** — `GatewayBudgetEscalationIT`, laptop + stub, run before the deployment.
Unchanged in substance; it now sits in Phase 3.

**V5b / V5c** stay as written and belong to Phase 6.

---

### A9 — open items: closed, remaining, and what is needed before Dev starts Phase 2

**Closed by this amendment:**

| # | Item | Resolution |
|---|---|---|
| 2 | `STARTING` vs additive block | **Enum** (A1). Appended; `actualStatus`-only; nothing new persisted |
| 3 | `202` vs `200` | **`200` + DTO** on create, start, restart and retry (A3) |
| 4 | Synchronous registration | **Removed entirely**; async, resumable, group-as-job, Phase 4 (A2) |
| 5 | `essential.ceiling` | **Stays at the hard cap (900)**, as originally specified. `850` remains the documented one-line escape if PRIORITIZED starvation during a very large start bites — now a three-place edit (A5.4) |

**Provisionally answered:**

| # | Item | State |
|---|---|---|
| 1 | Do WS upgrades count? | **`count-ws-upgrades=true` stands** pending SA. The flip is one property thanks to Dev's `runWsUpgrade` seam, and A5.2/A5.3 now define the *other* half of that answer (not paced, still cancellable; the probe's stamp follows the same flag) |

**Still open, unchanged:** 6 (startup chain order), 7 (cooldown length — ask SA whether the
block has a fixed duration), 8 (prod enable window), 9 (EWMA, deferred), 10 (periodic-logout
spurious reconnect, `FOLLOWUPS.md` P13), 11 (`bulk-create-accounts.py` shares the IP budget),
12 (dead-code follow-ups).

**New open items from this amendment:**

- **13 — the "account already exists" envelope.** The worker's resumability depends on
  treating a re-registration of index `k` as success. The gwms register endpoint's exact
  `status`/`code` for an existing username is not documented anywhere we control and three of
  the four gates answer HTTP 200 (`HANDOVER.md`). Dev must capture it from one real
  registration of an existing staging account before Phase 4's classifier is written, and
  until then the worker must fail-closed (treat an unrecognised envelope as a failure, which
  costs an attempt and an operator retry, rather than silently skipping an index).
- **14 — game-less account-factory groups.** Recommended as a follow-up, not folded in (A2).
  Needs a ruling.
- **15 — QA G1's real fix.** `registrationParallelism` is `0` outside Spring, so
  `Semaphore(0).acquire()` hangs any non-Spring caller. Phase 4 deletes the bulk method and
  the trap with it; if Phase 4 slipped, this belongs in `FOLLOWUPS.md` as a
  `Math.max(1, …)` floor.
- **16 — the RIK hunk riding in this branch** (compliance Provenance, review F6, QA G3):
  `isDisplayNameTaken` now accepts `EXISTED` and the "already taken" line is demoted to DEBUG
  — a behaviour change **on every brand**, whose test
  (`ApiGatewayClientDisplayNameTakenStatusTest`) is untracked and therefore not in the 2,196.
  Phase 4 touches display-name handling directly, so decide before then: commit the RIK test
  alongside it, or restore both hunks to the pre-RIK base as `831e311`/`f3ddb28` did for
  their siblings. Either way it goes in the release notes.

**Needed from the user before Dev starts Phase 2:**

1. **Confirm the persistence split in A1** — `STARTING` never reaches Mongo; registration
   state is two additive document fields, not a new persisted enum value. This is the one
   decision that makes a rollback safe, and it is a deliberate narrowing of "use the enum".
2. **Confirm the completion state**: a fully registered group goes back to `targetStatus:
   null`, identical to every group created before this feature — so the UI only ever has to
   learn `STARTING`, `REGISTRATION_PENDING` and `REGISTRATION_FAILED`.
3. **Confirm the UI can tolerate three new `status` strings and a `200` that now means
   accepted rather than finished** (A3). The frontend is not in this repo, so this cannot be
   verified here.
4. **Rule on Open Item 14** (game-less account-factory groups — recommended as a follow-up).
5. **Approve the one new endpoint**, `POST /{id}/registration/retry` — the only addition to
   the REST surface in the whole feature.

---

## Amendment — 2026-09-24 (compliance pass on Phase 2; A10-A13)

Phase 2 is on `feature/gateway-request-budget` (`b2a6799`..`750fc91`). The compliance pass
accepted the diff; four items below are corrections to **this document**, not to the code —
three of them are places where the plan asked for something that cannot be done as written, and
one records who owns a change the plan assigned to Dev. Verified against the branch, not
inferred.

### A10 — AD-16 is wrong about `restart()`'s internal stop; cancellation is gated on `parkRuntimeless`

AD-16's last sentence — "`restart()`'s internal stop does the same" (cancels the attempt and
the scope) — **cannot be implemented**. Traced on the branch:

`restartAsync` → `submitLifecycle` → `startAttempts.begin(id, REST)` opens the attempt **before**
the virtual thread runs → `restart(id)` → `stop(id, false)`. An unconditional
`cancelStartInFlight` there cancels *the restart's own attempt*, so every one of
`createBotsInParallel`'s tasks returns `null`, the post-build `isCancelled` check unwinds the
half-built runtime, and the zero-bot guard then throws. **Every `/restart` would become a
`/stop` plus a spurious `lastError`.**

It is also unnecessary. `restartAsync`'s `putIfAbsent` refuses a restart while any start is in
flight, so the internal stop can never be the thing that has to call off *somebody else's*
start.

**Corrected:** cancellation is gated on `parkRuntimeless` — the flag that already distinguishes
a **statement of intent** (operator `/stop`, the activation reconciler's STOP, cascade delete
via `stopAndLogout`) from a **teardown step** (`restart`'s internal stop). That is the same
discriminator DEAD_GROUP_AUTO_RECOVERY AD-5 introduced for persisting `STOPPED`, and the two
now agree: a teardown step neither persists `STOPPED` nor cancels a start. Only two call sites
exist (`stop(id)` → `true`, `restart` → `false`), so the gate is exhaustive.

### A11 — V2b's grep can never match; the group id precedes `start admitted`

V2b says `grep -c "start admitted.*<GID>"` → `1`. AD-18's own line format puts the id **first**,
and the shipped line is `group <id> (<name>): start admitted — origin <O>, <n> bots`. The
pattern is therefore unmatchable on any input this feature produces (confirmed against the line
emitted in the test run). Dev kept AD-18's shape, which is the right call — it is the format the
rollup, the estimate and Phase 3's declared demand all extend.

**Corrected V2b:** issue `/start` twice within a second; expect both **`200`** (A3), then

```bash
docker logs bot-manager 2>&1 | grep -c "<GID>.*start admitted"      # → 1
docker logs bot-manager 2>&1 | grep -c "<GID>.*a start is already in flight"   # → 1
```

The second line is the positive evidence that the duplicate was refused rather than merely
absent, and it is what tells a `1` apart from a group that was never started.

### A12 — A1's Mongo round-trip test is replaced by a captor test plus the source guard

A1 asks for "a Mongo round-trip test that a group persisted mid-start reads back with
`targetStatus` in the original three". **There is no Mongo test infrastructure in this repo** —
no Testcontainers, no flapdoodle/embedded Mongo, no `@DataMongoTest` anywhere — so the step
implies adding a container or an embedded server to the build for one assertion.

It would also prove less than what shipped. The invariant is a **negative over all call sites**
("no document ever holds `STARTING`"), and no round-trip of one scenario can see the absence of
a write. The two shipped tests cover it from both sides:

- `BotGroupBehaviorServiceAsyncStartTest.startingIsNeverPersisted` — asserts the runtime reads
  `STARTING` mid-build while every captured `botGroupService.save(group)` carries only `ACTIVE`,
  i.e. the dynamic half, one layer above the driver that would have been stubbed anyway;
- `BotGroupStatusPersistenceGuardTest` — a source scan over every module's `src/main/java` for
  `setTargetStatus(` with any of the three appended constants, in the `PerBotInfoLogGuardTest`
  idiom, with a non-vacuity test. This is the half that a round-trip test cannot give.

**Corrected test expectation for Phase 2:** the captor test + the source guard, not a Mongo
round trip. If Phase 4 ever brings embedded Mongo in for the `registrationState` fields, a round
trip becomes cheap and can be added then — as a belt, not as the mechanism.

### A13 — two documentation edits are the main session's, not Dev's

Phase 2's change list item 5 and A6 assign a **CLAUDE.md** edit to Dev (the REST-table rows for
`/start` and `/restart`). A coding agent cannot self-authorise an edit to `CLAUDE.md` or to
anything under `.claude/`, whatever a plan or another agent's message says, so that item is
**owned by the main session** and is recorded as outstanding rather than as drift. Both edits
are **release-blocking** and neither is in the code:

1. **`CLAUDE.md`, BotGroupController table** — `/{id}/start` and `/{id}/restart` now answer
   `200` with a `BotGroupStatusDTO` **meaning accepted, not finished** (`actualStatus:
   "STARTING"`, `botsUp`, `botCount`, `lastError`); a `/restart` that ends with zero bots is
   `lastError` on `GET /{id}/status`, no longer a `500`. `BotGroupStatus` has three appended
   constants and `STARTING` is `actualStatus`-only.
2. **`.claude/agents/releaser.md:67`** — still greps `Started Starter|startup complete` and
   **stops the release if either is missing**. `startup complete` now arrives at the *end of the
   daisy-chain*, which is minutes today and up to an hour once starts are paced, so that smoke
   check fails on a healthy box. It must grep `queued for daisy-chained start`, as
   `docs/process/AGENTIC_WORKFLOW.md` already does since `3ee11ed`.

Until (2) is done the Releaser will abort a good deploy at the first smoke step.

---

## Amendment — 2026-09-29 (Phase 2 re-check; A1's factual errors reconciled; Open Items 1, 7 and 13 closed; A14-A20)

Phase 2's fix round is `7e118bf`..`d9be331` (six commits answering QA's Q1-Q6 and review's
R1-R13), plus the main session's `72bbb24`. Re-check verdict **PLAN_AMENDED / diff accepted** —
`docs/reviews/GATEWAY_REQUEST_BUDGET/compliance-phase2.md`, `## Re-check`. Build at `72bbb24` in
a detached worktree: **2,276 tests, 0 failures, 0 errors, 0 skipped** (bot-api 148,
bot-messages 167, bot-strategies 126, bot-engine 494, bot-app 1,341), including QA's previously
red `BotGroupStatusRollbackSafetyTest$WritePaths`.

Three of the items below correct claims **this document was asserting confidently and wrongly**;
two close open items the user has now answered; one orders what Phase 3 absorbs. Everything was
verified against the code or against the running library — where a claim is a claim about a
framework, the probe that established it is named, so the next reader can re-run it rather than
trust it.

---

### A14 — A1 is wrong in three places: the boot query, the exception type, and what the DTO accepted

A1 is now the text a future reader will trust, and it contains three falsehoods. Each was
checked independently for this amendment, not taken on Dev's or the reviewer's report.

**1. `findByTargetStatus(ACTIVE)` does not fail on a poisoned document.** A1 says "one poisoned
document would fail the whole boot query". It does not. Verified by issuing the repository method
against a recording `MongoOperations` (a `java.lang.reflect.Proxy` over the interface, a real
`MappingMongoConverter` over a real `MongoMappingContext`, no server): the derived method
resolves to

```
ExecutableFind.as(BotGroup) → FindWithQuery.matching(Query{targetStatus=ACTIVE}) → TerminatingFind.all()
```

and `QueryMapper.getMappedObject` renders the criterion as the BSON filter
`{"targetStatus": "ACTIVE"}` — a **`java.lang.String`**, not the enum. The match is therefore
server-side, a document holding `"STARTING"` is never returned, and `MappingMongoConverter.read`
never sees it. `onStartup` is not the blast radius, and no amount of poisoning stops the app
booting.

**What the blast radius actually is**, in two halves:

- **Older jar (the rollback case): every read that *does* convert the group.** `GET /{id}`
  (`findById`) and — far worse — `POST /{envId}/filter`, which is
  `mongoTemplate.find(query, BotGroup.class)` over the whole environment
  (`BotGroupService.java:96-105`). One poisoned document takes **the entire environment's list
  view** with it, not one row. That is the UI's primary screen.
- **Current jar: the group goes silently unmanaged.** It leaves `findByTargetStatus(ACTIVE)`, so
  `onStartup` never queues it; and it leaves `RecoveryEligibility`'s `ACTIVE`/`STOPPED`/`DEAD`
  branches, which default to ineligible, so auto-recovery never touches it either. Nothing is
  logged. That is precisely the failure shape DEAD_GROUP_AUTO_RECOVERY exists to remove, restored
  by one PATCH.

**2. The exception is `IllegalArgumentException`, not `ConversionFailedException`.** Measured:
reading a document whose `targetStatus` holds a name the enum does not declare throws

```
java.lang.IllegalArgumentException: No enum constant com.vingame.bot.domain.botgroup.model.BotGroupStatus.<name>
  at java.base/java.lang.Enum.valueOf(Enum.java:293)
  at org.springframework.data.mongodb.core.convert.MappingMongoConverter.getPotentiallyConvertedSimpleRead(...:1420)
```

QA measured the same thing through `BotGroupStatusRollbackSafetyTest`; this amendment reproduced
it independently. `BotGroupStatus`' javadoc and the guard test's javadoc were corrected by
`7e118bf`; A1's text is corrected here.

**3. And it is not a 500 — correcting the correction.** Both Dev and the reviewer describe the
consequence as "one poisoned group 500s the whole environment's list view". It does not.
`IllegalArgumentException` reaching a controller is caught by
`RestExceptionHandler.handleIllegalArgument` (`:94-100`) and answered as **HTTP 400
`{"type":"Bad request","msg":"No enum constant …"}`**. Spring Data does not wrap it
(`MongoExceptionTranslator` translates driver exceptions, not ours), and that handler arm has
existed since the module split (`92469f5`), so any plausible `vingame-bot:rollback-*` has it.

The list view therefore fails with a **client-error status that blames the caller** and leaks the
enum name — which is worse for diagnosis than a 500, because a 400 reads as "the UI sent
something wrong" and sends the investigation in the opposite direction from the truth ("a
document written by a newer jar is unreadable"). The severity of the invariant is unchanged; only
the symptom an operator will actually see is different, and it is more misleading than advertised.
The "500s the list" wording survives in three javadoc strings
(`BotGroupStatus`, `BotGroupStatusPersistenceGuardTest`, `BotGroupStatusRollbackSafetyTest`) and
in no code; fold the correction into Phase 3's documentation pass rather than spending a commit
on it. **This amendment is the authority.**

**4. A1's consumer audit claims a property that was false.** The row for
`BotGroupDTO.targetStatus` says it "can still only carry the original three". That was true of
what the API **renders** and false of what it **accepted** — `BotGroupMapper.toEntity` copied the
field through the Lombok builder (`:92`, not a `setTargetStatus(` call at all, so the source guard
could not see it) and `updateEntityFromDTO` copied it from a variable whose value was whatever a
client sent (`:181`, a call the guard *can* see and a value it cannot judge). `PATCH
{"targetStatus":"STARTING"}` was a `200` that persisted the one value A1 spends a page forbidding.
Found by QA (Q1) and the reviewer (R1) independently; the guard test was green throughout, which
is worse than no guard, because the guard is what the next reader trusts.

**How it was closed (`7e118bf`), and why this shape and not a validator.**

- `@JsonProperty(access = READ_ONLY)` on `BotGroupDTO.targetStatus` — rendered outbound, ignored
  inbound, asserted through a real `ObjectMapper` rather than by reading the annotation.
- Removed from **both** mapper write paths, so an in-process caller (a script, a future
  controller, a test fixture) cannot reach it either.
- `BotGroupStatusPersistenceGuardTest` gains a **value-level** assertion over
  `BotGroupStatus.values()` — every constant, in both mapper directions — which survives the next
  appended constant without an edit. The source scan stays for the "no new literal write
  anywhere" half, which no dynamic test can give.

**A validator would have been wrong, and this is the part that matters for A3 and Phase 4.** Dev's
argument is correct and is adopted: A3 has `POST /` render `targetStatus: "REGISTRATION_PENDING"`
from Phase 4 on, so a read-modify-write client hands that value straight back on its next PATCH. A
validator rejecting the appended constants would then answer **400 to every PATCH such a client
makes** — the identical trap CLAUDE.md already records for the strategy-key validation ("a group
holding an unregistered key fails *any* PATCH until its mix is replaced"). Ignoring the field
inbound is what makes A3 safe; rejecting it would have made A3 a bug.

So **A3 is unchanged and needs no amendment** — and it is now safe in a way it was not when it was
written. **Phase 4 inherits one hard constraint from this:** `registeredCount`,
`registrationState` and `registrationError` are additive *document* fields whose values are
system-managed exactly as `targetStatus` now is. `registeredCount` is a high-water mark that A2.1's
resumability depends on (`indices 1..k are done`); if it is writable from a request body, a client
can make the worker skip or re-register a block of accounts. Render it, never accept it — the same
`READ_ONLY` + no-mapper-write pair, pinned by the same value-level test.

---

### A15 — Open Item 1 CLOSED: the WS hosts are behind the same Cloudflare rule as the API hosts

User-confirmed. `count-ws-upgrades=true` is **fact**, not the conservative guess A9 left it as.
Five consequences, the third of which is the largest thing this answer changes:

1. **Declared demand is `botCount × 3`, unconditionally** (login + upgrade + first balance read).
   A6 Phase 3's `BotGroupBehaviorServiceReservationTest` keeps its `× 2` case as a *hypothetical*
   covering the kill switch; the flag survives as a kill switch, not as an open question.
2. **The recovery probe's stamp (A5.3) is on for good.** `countWsUpgrade(reason)` still consults
   the flag, so the coupling A5.3 removed stays removed, but the answer is now "counted".
3. **Bot reconnects are cap consumption, and they are the largest un-budgeted source we know
   of.** `Bot.tryReconnectWs` → `connectUnderBudget(PRIORITIZED, …)` → `runWsUpgrade`
   (`Bot.java:913,951`), so every watchdog reconnect is now known to be a request the Cloudflare
   rule counts. MEMORY's staging hot loop is **156k watchdog reconnects in 3 days from two
   groups** ≈ 52k/day ≈ **~180 per 5-minute window** — ~18% of the entire 1,000-request rule,
   spent by two sick groups that were never receiving a game message. That makes a reconnect
   storm a plausible **cause** of an edge block, not only a symptom of a sick fleet. Two things
   follow: A5.1's "enforcement must reach `run` and `runWsUpgrade`" is the load-bearing half of
   the feature rather than a completeness item, and enforcement is what converts a reconnect
   storm from a block risk into a paced queue — an argument for Phase 3 the plan does not make
   anywhere.
4. **The anonymous recovery probe reads a Cloudflare block page as *healthy*.** `EnvironmentWsProbe`
   classifies any completed response with `status < 500` as healthy (AD-2, `:229`), and a
   Cloudflare 403 block page is a completed, well-formed 403. So during a block the probe reports
   the environment as serving. AD-13's "`DeadGroupRecoveryScheduler.evaluateCandidate` skips a
   group whose environment's circuit is open" is therefore **not an optimisation — it is the only
   thing that stops auto-recovery from starting groups into a blocked edge for as long as the
   block lasts**, which A16 now says may be a day. Phase 5 must keep it, must not make it
   conditional on `bot.recovery.enabled`, and should suppress the probe scheduler itself while the
   circuit is open (its stamps buy no information, and it is now counted).
5. Open Item 10's periodic-logout spurious reconnect stops being purely a BOT_LIVENESS matter: one
   extra WS upgrade per group per hour is now a counted gateway request (see A19 for its number).

---

### A16 — Open Item 7 CLOSED, in the worst way: there is no tolerable block cooldown

User's answer: **possibly ~24 hours, possibly until someone clears it manually.** AD-13's
15-minute cooldown is therefore wrong **in kind, not in degree** — the circuit breaker cannot be
built on the premise that a block ages out, and a clearance probe cannot be the recovery
mechanism. What survives, what changes, and what an operator is owed:

1. **The structure survives; the vocabulary and the cadence do not.** AD-13 already never resumes
   traffic on a timer — `OPEN → HALF_OPEN` only issues **one probe**, and only a non-block
   response closes the circuit. That is the right shape for an indefinite block. But the property
   is named `bot.gateway.budget.block-cooldown` and the AD reasons about "the cooldown ends",
   which is the assumption itself written into the code. **Rename it
   `bot.gateway.budget.block-probe-interval`, default `60m`** (from 15m): with a ~24 h floor,
   15-minute probes buy nothing but 96 requests/day thrown at a wall that may well count them,
   while 60 minutes costs 24/day and detects a human-cleared block within an hour. Constant, not
   exponential, as before.
2. **While the circuit is OPEN, no tier parks.** This amends AD-13's "ESSENTIAL waiters park
   (their wait is unbounded and cancellable)". The rule the plan should have stated, and which
   `essential.max-wait=0` silently assumes, is: **an unbounded wait is admissible only where
   progress is guaranteed.** The sliding window guarantees it — stamps expire, so a 3,000-bot
   start is ~10 windows of monotonic progress. A block guarantees nothing; parking on it is
   parking a bot thread for up to a day. So an open circuit **refuses every tier** with
   `GatewayCircuitOpenException` (AD-11's 503 shape) rather than queueing any of them.
   Consequences, all of them wanted: a group start during a block ends `0/N` with a `lastError`
   that names the edge block instead of hanging; `classifyCreationFailure` tags it `"budget"`
   (AD-9, non-terminal); and **FOLLOWUPS P13's black hole is avoided** — a start that parks
   forever inside the budget is exactly how an attempt stays open for the life of the JVM.
   `GatewayCircuitBreakerTest`'s "ESSENTIAL parks" assertion becomes "ESSENTIAL is refused".
3. **`Retry-After` on the circuit's 503 means "when we will next *ask*", not "when it will
   work".** Keep the header (an HTTP client needs a number) set to the probe interval, and put the
   truth in the body `msg`: the block may require operator action and may outlive a day. A
   `Retry-After` that promises 15 minutes for a 24-hour outage is a lie the UI will repeat.
4. **Alerting must say the brand is down until a human acts.** `GatewayEdgeBlocked`
   (`gateway_circuit_open == 1`, `for: 1m`, critical, `audience: product`) stays as specified and
   still needs no Alertmanager route. Two additions: (a) its annotation must carry the
   operator-facing consequence and the action — *bots on this brand cannot log in, re-auth,
   deposit or reconnect; there is no automatic recovery inside ~24 h; raise an SA/back-office
   ticket quoting the `cf-ray`* — and (b) the root `repeat_interval: 4h`
   (`alertmanager/alertmanager.yml:46`) already re-notifies ~6 times across a 24 h block, which is
   the right cadence for something a human must clear, so **do not** add a shorter repeat. A
   second rule `GatewayEdgeBlockUncleared` (same expression, `for: 2h`, critical) is worth adding
   to separate "blocked" from "blocked and not going away"; it is the only signal that
   distinguishes a rate-limit blip from a rule change that needs escalating.
5. **No manual-close endpoint, deliberately.** With no timer to wait out, "SA says it is cleared"
   wants to be actionable immediately — but the circuit is in-memory, so `docker compose restart
   bot-manager` closes it now, and the probe closes it within `block-probe-interval` anyway. A
   `POST /…/circuit/{envId}/close` would be a second lifecycle surface for one rare event; A2 was
   right to hold the feature to one new endpoint. Written down here so nobody builds it.
6. **Prevention is the only defence — which is the user's own reason for prioritising this
   feature, and it changes two things the plan treats as minor.**
   - **(a) The budget is keyed per environment; the rule is counted per (egress IP × Cloudflare
     zone).** AD-1 rejected a global budget because "two environments on different hosts must not
     throttle each other", which is right and does not extend to two environments on the **same**
     host: two `Environment` documents pointing at the same `apiGateway` each get their own 900,
     i.e. 1,800 against a 1,000 cap, with neither budget able to see it. **Phase 3 pre-flight:**
     either key the budget by gateway host, or assert host uniqueness across `Environment`
     documents at startup and log one WARN naming the duplicates. Cheap; now worth a day of a
     brand's uptime.
   - **(b) Open Item 11 stops being a caveat.** `scripts/bulk-create-accounts.py` run from a host
     that also runs bot-manager against the same brand shares the 1,000 invisibly. One careless
     run can now cost that brand a day. It goes in CLAUDE.md as a rule, not a note (Phase 6).
   - The `essential.ceiling = hard-cap = 900` decision (Open Item 5) is **unchanged**, and its
     reason is stronger: the 100-request gap below 1,000 is the only margin for traffic the JVM
     cannot see, and it is the margin a day-long block is measured against. **Do not raise
     `hard-cap`.**

---

### A17 — A13's two documentation items and Open Item 13 are closed, outside Dev's diff

- **A13.1 done** (`8e4a1bc`). CLAUDE.md's BotGroupController table now says `/start` and
  `/restart` answer `200 + BotGroupStatusDTO` meaning *accepted*, names `STARTING` as
  `actualStatus`-only and never persisted, and points the caller at `GET /{id}/status`. Verified
  in the file, not in the commit message.
- **A13.2 done** (`72bbb24`, the main session, user-approved). `.claude/agents/releaser.md`'s
  universal smoke now greps `Started Starter|queued for daisy-chained start` and demotes
  `startup complete` to a later, **non-blocking chain-completion** check. The release-blocker A13
  flagged — the Releaser aborting a healthy deploy at its first smoke step — is gone.
- **Open Item 13 CLOSED** by `docs/reviews/GATEWAY_REQUEST_BUDGET/gwms-register-envelope.md`
  (captured live on 097/BOM staging, 2026-09-29). The existing-username envelope is
  `{"status":"EXISTED","code":409,…}` at **HTTP 200**. Three things Phase 4 must take from it, one
  of which A2 did not anticipate:
  1. classify on `status == "EXISTED"` (the body, never the HTTP status — both answers are 200);
  2. `EXISTED` is **endpoint-scoped**: from `update-fullname.aspx` it means "display name taken",
     which is what `isDisplayNameTaken` already accepts. The worker must interpret it against the
     endpoint it called;
  3. **a re-register returns no tokens** — no `session_id`, no `token`, no `token2`. So an index
     that registered but whose display name never landed cannot be finished from the re-register:
     the worker has to **log in** first, costing `register + login + update-fullname` = **3
     DEFAULT requests** on that index. A2.1's single `registeredCount` high-water mark cannot
     express "registered but not named", so **A6 Phase 4 items 1 and 3 are amended**: persist
     the two as distinct progress (`registeredCount` and `namedCount`, same monotonic
     in-order rule) so the common case does not pay for the resume case, and budget the resume
     path at 3 rather than 2.

---

### A18 — Phase 2 verification: what the fix round changed, and four steps to add

**V2a / V2d** are unchanged from A8, with one improvement worth knowing: `botsUp` is now
incremented **in the per-bot task** rather than in the index-ordered join loop (R8), so "rising
toward `botCount`" is honest when one bot is slow. Before the fix, one slow bot at index 5 pinned
the count at 4 however many later bots were up — i.e. the field lied in the *stuck* direction, on
the one path where 33-50 minutes is normal. **V2b** stays as corrected in A11 (the refusal line
is `Bot group <id> (<name>): start ignored — a start is already in flight (origin …, elapsed …)`,
so `grep -c "<GID>.*a start is already in flight"` matches). **V2c** and **V2f** stand.

Four steps to add, all cheap and all covering behaviour that only exists after this fix round:

**V2g — `/health` agrees with `/status` in the accepted-but-not-built window.** Immediately after
`POST /{GID}/start` on a ≥ 100-bot group, in one shell:
```bash
curl -s .../bot-group/<GID>/health | grep -o '"status":"[A-Z_]*"'
curl -s .../bot-group/<GID>/status | grep -o '"actualStatus":"[A-Z_]*"'
```
Expect both `STARTING`. Before R3's fix `/health` hard-coded `STOPPED` here, on the endpoint
MEMORY records as *the* public-facing UI health feature.

**V2h — an operator `/stop` during the daisy-chain is honoured.** With ≥ 2 ACTIVE groups,
`docker compose restart bot-manager`; while the chain is on the first group, `/stop` the second:
```bash
docker logs bot-manager 2>&1 | grep -c "its targetStatus is now STOPPED"   # → 1
```
and that group must never start. `STOPPED` is DEAD_GROUP_AUTO_RECOVERY's only opt-out, and before
R5's fix the chain overwrote it minutes later.

**V2i — a shutdown mid-chain is one INFO line, not N ERRORs.** `docker compose restart
bot-manager` while a chain is running, then on the *previous* container's logs:
```bash
docker logs bot-manager 2>&1 | grep -c "abandoning the daisy-chain"        # → 0 or 1
docker logs bot-manager 2>&1 | grep -c "Failed to auto-start bot group"    # → 0
```

**V2j — `lastError` is sanitised.** Point a throwaway `Environment` at `http://127.0.0.1:1`
(no traffic leaves the box), create a 2-bot group on it, `/start`, then read
`GET /{id}/status`. Expect `lastError` to be either the self-authored `Started 0/2 bots — all
bot creations failed` or `Internal server error — see server logs (<ClassName>)`, and to contain
**no** `com.`-prefixed package name, no hostname and no port. This is R2's security finding, on
an unauthenticated endpoint.

**V2k — a request body cannot set `targetStatus`.** The one-curl reproduction of the Phase-2
blocker, and it belongs in the release sequence:
```bash
curl -s -X PATCH .../bot-group/<GID> -H 'Content-Type: application/json' \
  -d '{"targetStatus":"STARTING"}' -o /dev/null -w '%{http_code}\n'   # → 200
curl -s .../bot-group/<GID> | grep -o '"targetStatus":[^,]*'          # → unchanged
```

---

### A19 — `FOLLOWUPS.md` P13 is taken; Open Item 10's reference is stale

Open Item 10 and A9 both tell the reader to file the periodic-logout spurious reconnect as
**P13**. `d9be331` used **P13** (a wedged build parks its group for the life of the JVM) and
**P14** (an unclean shutdown leaves an in-flight group's bots connected) for this phase's two
deferrals, and the periodic-logout item was never filed. It becomes **P15** when it is.

Both of this phase's deferrals are legitimately out of Phase 2's scope — neither a TTL on a start
attempt nor a shutdown drain appears anywhere in A6 Phase 2, and P13's obvious fix (a TTL that
drops a stuck attempt) re-opens the race `750fc91` closed, because dropping an open attempt
uncancels its build. **But P13's entry understates its reachability, and Phase 3 and Phase 5 both
need the correction:** it is described as what happens if a build "never returns", as though that
needed an exotic cause. The bot login does not go through our own `HttpClient` — it goes through
the library's `AuthClient`, which builds its request with **no `.timeout(...)`** on an
`HttpClient.newHttpClient()` with no connect timeout (`websocket-parser-core-3.0.5` sources,
`AuthClient.java:25,127-137`), while the four in-repo gateway calls all carry
`.timeout(Duration.ofSeconds(10))`. A stalled TCP connection on a login therefore parks a build
thread indefinitely **today**. So:

- **AD-12 (Phase 5, login moves in-repo) must give the login request the same 10 s timeout the
  other four calls have.** AD-12 does not currently mention a timeout, and since A7 ships Phases
  2-5 as one deployment, forgetting it means P13 stays reachable in the shipped artifact.
- Phase 3 must re-read P13 before giving `ESSENTIAL` an unbounded max-wait, as its own entry says
  — and A16.2 removes the other unbounded wait (an open circuit).

---

### A20 — what Phase 3 absorbs, ordered

This supersedes and orders the "What Phase 3 and Phase 4 inherit" list at the end of
`compliance-phase2.md` (the fifth item there — `/health` disagreeing with `/status` — is **closed**
by R3's fix and drops off). Items 1-4 are prerequisites for enforcement being *correct*; 5-8 are
correctness in the large; 9-12 must not be left behind.

1. **Bound every wait whose subject cannot make progress** (A16.2). An open circuit refuses all
   tiers; only the sliding window may be waited on unboundedly, because only it drains by
   construction. This is the difference between a paced fleet and FOLLOWUPS P13 becoming routine.
2. **A4's `authenticate` / `getBalance` rewrap.** Unchanged, and the stakes are higher than A4
   states: `/start` no longer has an HTTP response, so a budget outcome during a build is visible
   *only* through `classifyCreationFailure`'s tag, `bot_creation_failures_total` and the attempt's
   `lastError`. Leave the rewrap in place and a paced start is indistinguishable from a brand-wide
   auth outage. Cancelled bots return `null` and are counted `skipped`, deliberately outside both
   — A4 is about *paced* requests only.
3. **`cancelScope` must wake waiters, not merely mark them**, matched on
   `GatewayRequestScope.botGroupId` — the key `Bot.scope()` already supplies and
   `cancelStartInFlight` already passes. Today a cancelled build returns within one in-flight HTTP
   call per permit, so V2c's "stop 200 within 5 s" is comfortable; with waiter queues, a bot parked
   *inside* the budget cannot return until `cancelScope` wakes it, so V2c becomes a direct test of
   `cancelScope` and A5.1 becomes a **stop-latency** requirement, not only a pacing one.
4. **Resolve the environment for a cancel without a runtime, or place `reserve` after the runtime
   is published.** Unchanged by the fix round: `cancelStartInFlight` still reads the environment
   from `runningGroups`, which is published at `startLocked` before `createBotsInParallel`, so the
   lookup cannot miss *today*. AD-7's declared-demand reservation breaks that if it is taken at the
   top of `startLocked`: a `/stop` in that window leaves a reservation shrinking the lower ceilings
   with nothing to release it.
5. **A5.1, A5.2 and A5.3**, with `count-ws-upgrades=true` now fact (A15) and reconnects understood
   as cap consumption.
6. **The per-gateway-host keying pre-flight** (A16.6a) — one startup assertion or a key change.
7. **A5.5's two tripwire rewrites, plus the group-level cancellation half.** Both named tests are
   untouched by Phase 2 and still assert Phase 1 semantics, so A5.5 holds verbatim. What changed is
   the *population*: before Phase 2 the only cancelled scope in the fleet came from
   `Bot.isStopped()`; now every bot of a cancelled group start has one. The rewrite must assert a
   cancelled scope whose **bot is healthy**, or it pins only the case that was already true.
   `BotGatewayTierTest.theScopeCarriesBothHalvesOfCancellation` is the fixture to lift from.
8. **Budget outcomes already reach `lastError` correctly — keep them safe.**
   `GatewayBudgetException extends BotManagerException`
   (`bot-api/.../GatewayBudgetException.java:25`) and `ClientSafeMessage` forwards our own
   hierarchy verbatim, so a paced or refused start reports its tier and retry-after on
   `GET /{id}/status` with no extra work. Verified. That makes those messages **client-visible on
   an unauthenticated endpoint**: they may name a tier, a duration and an environment id, and must
   never name a host, a port or an upstream body.
9. **The MDC idiom.** AD-20's throttle WARN/INFO and the budget scheduler's lines run on threads
   that already carry a group scope. Use `BotMdc.snapshot()` / `restore()` inside any nested scope,
   never `clear()` (Q3) — a bare `clear()` in a nested `finally` is what untagged the only report
   an asynchronous failure makes.
10. **Alerting for enforce mode.** In `enforce`, `GatewayBudgetNearCap` (> 800) changes meaning
    from "about to be blocked" to "saturating our own cap", which is **normal** during a large
    start; nothing alerts on the shape that actually hurts, which is sustained queueing (a group
    that cannot finish starting). Add one rule on queue depth or wait p95, or accept that V3 is
    read by hand and say so.
11. **`StubGateway` (AD-22) lands here**, test scope, without the block-mode half, so
    `GatewayBudgetEscalationIT` (V3g) proves the cap on the laptop before the single deployment.
12. **A5.4's three-file flip warning** applies to A9's `essential.ceiling=850` escape hatch as
    much as to the Phase 6 mode flip.

And what Phase 3 must **not** absorb: registration machinery (Phase 4), Cloudflare detection and
the circuit (Phase 5) — A16 amends their *design*, not their phase.
