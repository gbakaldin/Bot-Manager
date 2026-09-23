# GATEWAY_REQUEST_BUDGET

> Companion to `docs/reviews/WIN79_119_PROD_ACCOUNTS/HANDOVER.md` and
> `cf-block-report.md` (the incident), `scripts/bulk-create-accounts.py:59-96` (the
> reference limiter, Python), `docs/plans/DEAD_GROUP_AUTO_RECOVERY.md` (the only paced
> launch path today, and the reconciler idiom reused here), `docs/plans/API_ERROR_FORWARDING.md`
> (the `{type, msg}` envelope this plan extends with two new statuses) and
> `docs/plans/LOG_VOLUME_TIERING.md` (every log line this plan adds obeys its tier rule).
>
> A later plan, **ASYNC_REGISTRATION** (not written yet), will turn `BotGroupService.save`'s
> registration into a persisted, budget-paced background job. This plan leaves registration
> synchronous and only gives it a typed rejection — see AD-19 and Open Item 4.

Dated section: **2026-09-22**.

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

Phases are ordered so each ships alone. Phase 2 (async start) is independent of the budget
and is deliberately **before** enforcement: enforcing on a fleet whose `/start` blocks the
HTTP thread would trade a Cloudflare block for a gateway timeout.

### Phase 1 — Facade in observe mode, every call site tagged, metrics and near-cap alert

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
