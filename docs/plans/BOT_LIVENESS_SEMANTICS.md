# BOT_LIVENESS_SEMANTICS — what "DEAD" means for a bot

Dated: **2026-08-07**. Supersedes the reconnect-cap decisions of
`docs/plans/RESILIENCE_HARDENING.md` (P1, Decisions 2/3 — "absolute cycle cap → DEAD",
"re-auth failure → DEAD"). Those decisions were correct for the failure they were written
against (an unbounded *platform*-thread reconnect leak, 2026-06-30) but the leak itself was
fixed by virtual threads + the ws-parser 3.0.5 bump; the cap is now doing net harm. Sibling
to `docs/plans/DEAD_GROUP_RESTART.md` (a DEAD group must stay one-click restartable) and
`docs/plans/THREAD_LEAK.md`.

All code paths below are in the module tree created by `docs/plans/MODULE_DECOUPLING.md`
(`bot-api` / `bot-strategies` / `bot-messages` / `bot-engine` / `bot-app`). Library
citations are into the **read-only** dependency `com.vingame:websocket-parser-core:3.0.5`
(sources jar `~/.m2/repository/com/vingame/websocket-parser-core/3.0.5/websocket-parser-core-3.0.5-sources.jar`);
nothing in this plan changes the library.

---

## Goal

Redefine bot liveness so that **transport and environment failure are never fatal** and
**DEAD means exactly one thing: an unhandled exception escaped bot logic**. Concretely: a
bot whose environment is down for six hours must keep retrying at a bounded floor and come
back by itself when the server returns; a bot whose account was deleted must keep retrying
too (it costs one HTTP call per minute and is visible in metrics), while a bot that hit an
`IllegalStateException` in a message handler, a strategy, or a scheduler must be marked
DEAD with the throwable recorded as its failure reason instead of silently wedging. As a
precondition, close the fourth, unmodelled state — *not alive, not reconnecting, not dead* —
so that the invariant "`!alive` ⇒ `RECONNECTING` ∨ `DEAD`" is asserted by the orchestration
layer every 30 s rather than inferred from a disconnect event that may never fire.

---

## Findings — Current State

### 1. The two existing DEAD paths (both in `Bot.java`) are both connectivity paths

`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`

- `:34-44` — `BACKOFF_SECONDS = {5,10,30,60,60,60,60}` (285 s per cycle),
  `RECONNECT_CONFIRM_SECONDS = 3`, `MAX_RECONNECT_CYCLES = 10`.
- `:568-612` `runWsReconnectLoop(int startCycle)` — the only WS retry worker. At
  `:595-610`, once the backoff array is exhausted it increments `cycle`; at `:597-606`
  `cycle >= MAX_RECONNECT_CYCLES` ⇒ `transitionStatus(DEAD)`, `reconnecting.set(false)`,
  `closeClientQuietly()`.
- `:608` — at every cycle boundary **below** the cap it calls `performReauth()`.
- `:637-653` `performReauth()` — bare `catch (Exception)` ⇒ `log.error(... marking DEAD)`,
  `transitionStatus(DEAD)`, `reconnecting.set(false)`, `closeClientQuietly()`, `return false`.
- `:614-635` `runAuthThenWsLoop()` — the watchdog entry point; calls `performReauth()`
  **first** (`:620`) and returns immediately if it fails.

**Consequence (confirms the brief):** in a full-environment outage the auth gateway is
unreachable too, so the bot dies at the *first* cycle boundary — ~285 s — via
`performReauth`, and never reaches the 10-cycle cap. Deleting `MAX_RECONNECT_CYCLES` alone
changes nothing for the case that actually matters.

- `:508-524` `onWsDisconnected()` and `:527-543` `triggerFullReconnect(String)` — both
  guard `stopped || status == DEAD` **before** the `reconnecting` CAS, i.e. DEAD is
  terminal by design (the RESILIENCE_HARDENING P1 comments say so explicitly at `:509-513`
  and `:528-529`).
- `:445-463` `transitionStatus` — DEAD entry stamps `deadSince` and fires
  `metrics.incBotFailure()`; exit-from-DEAD credits `bot_dead_seconds_total`
  (`:470-477`). The exit branch is documented as defensive/unreachable.
- `:550-554` `normalizeReconnectReason` — the `reason` tag budget is
  `watchdog | ws-disconnect`, with `reauth-cycle` documented but never emitted.

### 2. The orphan-client leak — the mechanism behind the 220k-lines/30 min flood

Every close in `Bot` is guarded on `isOpen()`:

- `:261` `cleanup()` → `if (client != null && client.isOpen()) stop();`
- `:278` `restart()` → `if (client != null && client.isOpen()) client.close();`
- `:664` `closeClientQuietly()` → `if (c != null && c.isOpen())`
- `:676` `tryReconnectWs()` → `if (client != null && client.isOpen()) client.close();`
  then `this.client = clientFactory.newClient(...)`, `configureClient`, `connect()`,
  `beforeReconnect()`, `start()`.

`VingameWebSocketClient.isOpen()` is `isConnected.get() && channel != null && channel.isActive()`
(library `:752`), and `onConnectionClosed()` sets `isConnected=false` (library `:877`).
**So a client whose channel has died is never `isOpen()` and therefore never `close()`d.**
That matters because:

- The client's constructor already started its message-processor workers (library
  `:182-190`), and `Bot.start()` (`:802-807` → `onStart`) has already attached the
  OutputPrinter scenario **and** the `sendAsync(INFINITE)` betting/spin pipeline, each of
  which owns a `ScheduledExecutorService` (library `SendAsync` `:109-110`).
- `close()` is **idempotent, one-shot, and self-sufficient in 3.0.5** — it shuts scenarios,
  ping, channel, and the processor pool, and explicitly "runs even if the client never
  connected, so a never-connected client (e.g. a failed reconnect attempt) never leaks its
  scenario/ping/processing threads" (library `:494-556`).
- The orphan's `sendAsync` runnable only self-terminates on `!context.isActive()` (library
  `SendAsync` `:129-131`), and nothing sets the context inactive when the socket dies —
  only `scenario.shutdown()` (i.e. `client.close()`) does. Its `condition` and `supplier`
  close over the **live** `Bot` fields (`sidStore`, `gameState`, `remainingTime`), so the
  orphan keeps evaluating `canBet()` as true whenever the real bot would bet, calls
  `client.send(...)`, and the library logs `WARN "Client ws-<name>: Cannot send message, not
  connected"` (library `:587-590`) — roughly once per second per orphan during each bet
  window. The observed ~97 lines/s is consistent with ~100–140 orphan clients, not
  necessarily with 136 distinct wedged bots.

Today the cycle cap bounds the orphan count at ≈70 per bot; **removing the cap without
fixing the close guard makes it unbounded** (one orphan per failed attempt, forever). All
leaked threads are virtual (library `VirtualThreads`), so this is a heap + log-volume leak,
not an OS-thread leak — but it is exactly the Loki disk-fill vector from the 2026-06-30
outage.

### 3. Bot logic has no exception boundary, and the library turns a throw into a silent wedge

- The only `catch` in `BettingMiniGameBot` is `onStart` (`:921-928`), which logs and
  rethrows. `SlotMachineBot` `:414-420` is identical.
- **Message handlers** (`mdcConsumer(this::onStartGame)` etc., `BettingMiniGameBot`
  `:887-918`) run inside the library's `Processor.process`, which catches `Exception` and
  returns `ProcessingResult.error(e)` (library `Processor` `:35-45`). `PipelineStage`
  `:676-687` then does `context.setInactive(StopReason.ERROR, cause)` and rethrows
  (`propagateErrors` defaults to `true`, library `PipelineContext:389`), where the client's
  message-processor loop logs `ERROR "Client {}: Error processing message"` and continues
  (library `:231-233`). Net effect: **the bot's whole scenario pipeline is permanently
  inactive** (`Scenario.process` early-returns on `!isActive()`, library `:83-86`) while
  `BotStatus` stays `CONNECTION_AUTHENTICATED` and `isConnected()` stays `true`. Only the
  watchdog rescues it (`BettingMiniGameBot:369-375`), and only for betting/Tai Xiu — the
  slot bot has **no watchdog at all** (`SlotMachineBot:47`).
- **`sendAsync` condition/supplier** (`BettingMiniGameBot:911-916`, `SlotMachineBot:394-399`)
  run *outside* `Processor.process`, directly inside a `scheduleAtFixedRate` task (library
  `SendAsync:120-160`). A throw there — e.g. the deliberate `IllegalStateException` at
  `BettingMiniGameBot:738-741`, or any strategy RuntimeException — **silently cancels the
  periodic task forever**. The bot then stays connected, keeps receiving frames, keeps its
  watchdog fed, and never bets again. No status change, no log, no metric. This is the
  purest instance of "the code reached a broken state we didn't account for" and it is
  currently 100 % invisible.
- **Schedulers**: `startRemainingTimeCountDown` (`BettingMiniGameBot:339-353`,
  `scheduleAtFixedRate` — same silent-cancel exposure) and `scheduleWatchdog` /
  `onWatchdogExpired` (`:355-375`).
- **Reconnect workers**: `Thread.ofVirtual()...start(mdcWrap(this::runWsReconnectLoop))`
  (`Bot:523`, `:542`). If the loop body throws anywhere outside `tryReconnectWs`'s own
  `catch` (`:686-689`) — `transitionStatus`, a metrics call, `beforeReconnect` — the thread
  dies with `reconnecting` still `true`, and both re-entry points (`:515`, `:531`) then CAS-fail
  forever. Permanent wedge, no DEAD.
- **Group executor task**: `BotGroupRuntime.startBot` `:180-187` catches `Exception` from
  `bot.start()` and only logs.

### 4. Auth failure cannot be classified today

`ApiGatewayClient.authenticate` (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:123-163`)
wraps **any** `RuntimeException` from `AuthClient` into `UpstreamLoginException` with the
library's (acknowledged-misleading) message and no status code — `:150-162`. It already
increments `bot_login_total{outcome=failure}` at `:152`. The 2026-08-06 production
observation (401 IP-denied vs 504 account-not-found producing identical exceptions)
confirms there is no reliable discriminator at the catch site.

### 5. Group-level death

`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java`

- `:1476-1492` `startHealthMonitoring` — 30 s tick, virtual thread, group MDC, catches
  `Exception`.
- `:1497-1513` `monitorHealth` — counts `dead` / `reconnecting` / `playing(isConnected)`,
  writes `runtime.setConsecutiveFailures((int) dead)` (a snapshot, not a streak), logs a
  DEBUG summary, and trips `handleBotGroupDeath` when `dead/bots.size() >= deadBotGroupThreshold`
  (`:124`, `bot.group.dead.threshold=0.80`,
  `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/resources/application.properties:94`).
- `:1518-1531` `handleBotGroupDeath` — `runtime.markAsDead()` + persist
  `targetStatus=DEAD`, `lastFailureReason="Multiple bot disconnections detected"`.
- The other DEAD-group path is the zero-bot start guard at `:420-451`.
- `BotGroupRuntime.playingStatus` (`:71`) is initialised to `IDLE` at `:131` and **never
  written again anywhere in the codebase** — `BotGroupPlayingStatus` (PLAYING / IDLE /
  PENDING) is a dead field that `getHealth` faithfully surfaces (`:1012`).
- `BotGroupStatus` is `ACTIVE | STOPPED | DEAD`, and per DEAD_GROUP_RESTART a DEAD group
  must remain one-click restartable (`start()` now reclaims a DEAD runtime, `:276-290`).

### 6. Observability surface that keys on DEAD

- `BotMetrics` (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java`):
  `bot_failures_total` (`:55`, fired at `Bot:455`), `bot_reconnects_total{reason}` (`:56`,
  `:137-143`), `bot_dead_seconds_total` (`:64`, `:300`), `group_dead_seconds_total` (`:65`,
  `:342`), `bot_watchdog_expired_total` (`:62`), `bot_login_total{outcome}` (`:60`).
- `ObservabilityConfig` (`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/infrastructure/observability/ObservabilityConfig.java:63-86`):
  `bots_by_status{status}` — one gauge per `BotStatus` enum value, so **adding an enum
  constant automatically adds a series** — plus `bots_dead_currently` and
  `groups_dead_currently`.
- Health DTOs (production UI feature, per MEMORY):
  `BotGroupHealthDTO` (`connectedBots`/`reconnectingBots`/`deadBots`/`disconnectedBots`,
  built at `BotGroupBehaviorService:1002-1019`) and `BotHealthDTO`. Note
  `disconnectedBots = total - connected - reconnecting - dead` — **that bucket is precisely
  the zombie population**, and today nobody looks at it.
- Grafana: `bots.json:322` (`bots_by_status`), `:540` (`sum(rate(bot_failures_total[5m]))`),
  `:595` (`bot_reconnects_total`), plus per-env/per-game panels with `{environmentId=…}` /
  `{gameId=…}` selectors. All are `sum(rate(...))`-shaped, so **adding a label is safe**;
  no alert rules exist yet (`/Users/gleb/IdeaProjects/Bot/prometheus/prometheus.yml`).

### 7. Test coverage of the current semantics

`bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BotReconnectTest.java` —
`@Nested "absolute reconnect-cycle cap"` (`:214-364`:
`shouldGiveUpAndDieWhenWsNeverHoldsButReauthSucceeds`, `watchdogReentryCannotBypassCap`,
`shouldRecoverWithinCapWithoutDying`, `deadBotIsNotResurrectedAfterCap`) and
`@Nested "performReauth"` → `shouldMarkDeadOnFailure` (`:365-388`) pin exactly the
behaviour this plan inverts. `shouldExhaustBackoffThenReauth` (`:186-213`) pins the sleep
sequence. `BotDeadSecondsTest` drives `transitionStatus` by reflection, so it is
trigger-agnostic and survives. `BotGroupBehaviorServiceTest` `@Nested "monitorHealth -
dead-threshold trigger"` (`:1017-1083`) covers the group rule. Suite baseline per the
brief: ~1,497 tests across 5 modules, green (Dev must re-confirm the baseline before
touching anything).

---

## Per-aspect readiness / mapping

| Aspect | State | Notes |
|---|---|---|
| Remove reconnect budget (`MAX_RECONNECT_CYCLES`) | **ready** | One constant + the `:595-610` block. Trivial *after* Phase 1. |
| Indefinite retry at a 60 s floor | **ready** | `BACKOFF_SECONDS` already ends in 60 s; needs the array-clamp + jitter. |
| Re-auth failure non-terminal | **ready** | `performReauth` `:637-653`; both callers (`:608`, `:620`) need the fall-through restructured. |
| Permanent-vs-transient auth classification | **blocked (upstream)** | `ApiGatewayClient:150-162` has no status code; ws-parser `AuthClient` does not surface the envelope. Recommendation: **do not classify** (AD-4). |
| Orphan-client close on dead channel | **ready** | Drop the `isOpen()` guard at `Bot:261/278/664/676`; 3.0.5 `close()` is idempotent + self-sufficient. |
| Liveness sweep (zombie closure) | **partial** | Needs `Bot.isReconnecting()` + a loop heartbeat; sweep hosts cleanly in the existing 30 s `monitorHealth`. |
| Uncaught-exception boundary | **partial** | Every risky callsite already routes through an `mdc*` wrapper (`Bot:714-784`) — those are the composition points. Library also offers `Scenario.addStopListener` (library `:315`) for defence in depth. |
| Record throwable as failure reason | **ready** | New `Bot.lastFailure` field + additive `BotHealthDTO.lastFailureReason`. |
| Group-level death rule | **partial** | Keep `BotGroupStatus` at 3 values; re-key the DEAD trigger and finally *use* the dormant `playingStatus` field for "not playing". |
| Metrics blast radius | **ready** | All Grafana queries are `sum(rate())`-shaped; adding a `site` label / new counters is non-breaking. No alert rules to migrate. |
| Test rework | **ready** | Localised: one nested class in `BotReconnectTest`, one in `BotGroupBehaviorServiceTest`. |
| Slot bot has no watchdog | **out of scope** | Recorded in Open Items; the Phase 2 sweep partially compensates (transport only). |

---

## Architecture Decisions

**AD-1 — DEAD means "an unhandled `Throwable` escaped bot logic". Nothing else.**
No connectivity condition, no auth condition, no timeout, no cap may transition a bot to
`BotStatus.DEAD`. The single writer of `DEAD` after this plan is the fault boundary
(`Bot.onUnhandled`). `bot_failures_total` and `bot_dead_seconds_total` therefore change
meaning from "gave up reconnecting" to "code fault"; a drop to ~0 in production is the
expected outcome, not a regression.

**AD-2 — Transport/environment failure retries forever, at a bounded floor.**
`MAX_RECONNECT_CYCLES` is deleted. The backoff schedule stays `{5,10,30,60,60,60,60}` and
then **holds at 60 s indefinitely** (the array clamp at `Bot:577` already does this; the
cap block is what ended it). Add ±20 % jitter to every sleep so 100+ bots do not
re-stampede the gateway the second an environment returns. Backoff constants stay
`private static final` in `Bot` — **no new `application.properties` keys** (AD-9).

**AD-3 — Re-auth failure is non-terminal on every reconnect path.**
`performReauth()` no longer transitions to DEAD, no longer clears `reconnecting`, and no
longer closes the client. It returns `false`; the caller *continues the retry loop* rather
than returning. `runAuthThenWsLoop` on a failed initial re-auth falls through into
`runWsReconnectLoop` instead of giving up. Re-auth cadence is unchanged: at most once per
exhausted backoff sequence (≈ once per 285 s per bot), so a 6-hour outage costs ~76 login
attempts per bot.

**AD-4 — Do NOT classify permanent vs transient auth rejections. Retry everything.**
Reasoning, in order of weight: (1) it is the user's stated requirement — only unhandled
exceptions are fatal; (2) it is **not implementable correctly today** — `UpstreamLoginException`
carries no status code and production has shown 401 and 504 producing identical exceptions,
so any classifier would be a string-matching heuristic that mislabels a transient 504 as
"account deleted" and kills a bot during an outage — the exact failure mode we are removing;
(3) the cost of being wrong in the other direction is trivial: a genuinely deleted account
issues **one** login attempt per ~285 s, already counted by the existing
`bot_login_total{outcome="failure"}`, and shows up as a bot pinned in `RECONNECTING`
(visible in `bots_by_status`, the health DTO, and the new `bot_reconnect_seconds_total`);
(4) the correct fix belongs upstream — ws-parser's `AuthClient` surfacing the HTTP status /
upstream envelope — and can be adopted later without revisiting this plan. Escalation
instead of death: log `WARN` on each failure and `ERROR` once a bot passes 10 consecutive
failed re-auths (~48 min), with the count in the message.

**AD-5 — Liveness is asserted, never inferred. Invariant: for a non-stopped bot,
`!isConnected()` ⇒ `RECONNECTING` ∨ `DEAD`.**
Enforced every 30 s from `BotGroupBehaviorService.monitorHealth` (orchestration level,
where the user wants the handling), by a sweep that calls
`triggerFullReconnect("liveness-sweep")` on any bot violating it. Additionally, the
reconnect worker publishes a heartbeat (`lastReconnectAttemptAt`); if a bot is flagged
`reconnecting` but its heartbeat is older than 5 × the max backoff (300 s), the sweep
force-clears the flag and re-arms the loop — closing the "loop thread died, flag stuck
true" wedge. `bot_reconnects_total` gains the bounded reason value `liveness-sweep`
(budget becomes `watchdog | ws-disconnect | reauth-cycle | liveness-sweep`).

**AD-6 — Every client is closed unconditionally.**
All four `isOpen()`-guarded closes in `Bot` become plain null-guarded `close()` calls.
3.0.5's `close()` is idempotent, one-shot, and reclaims scenario/ping/processing resources
for never-connected and dead-channel clients alike. This is a **prerequisite** for AD-2:
unbounded retry with the current guard means unbounded orphan clients.

**AD-7 — DEAD stays terminal at the bot level; recovery is a group restart.**
No auto-revive. A code fault will reproduce on retry, and silently restarting through it
is how a bug becomes invisible. The existing DEAD guards (`Bot:514`, `:530`) stay. Group
`start`/`restart` rebuilds bots from scratch (DEAD_GROUP_RESTART), which is the one-click
recovery.

**AD-8 — The fault boundary is composed at each callsite, not hidden inside `mdcWrap`.**
Add a `guard*` family (`guardedRunnable/Consumer/Supplier(String site, …)`) alongside the
existing `mdc*` wrappers and compose `guardedX(site, mdcX(...))` — guard outermost so the
MDC `finally` still restores context before the handler records the fault. Sites are a
bounded set used as a metric label and as the recorded failure site:
`message-dispatch | bet-supplier | bet-condition | countdown | watchdog | reconnect-loop |
bot-start`. Behaviour on catch: record + `transitionStatus(DEAD)` + close client, then
**swallow** for `Runnable`/`Consumer` sites and **rethrow** for the `sendAsync` supplier
(the engine forbids a null message; the bot is DEAD either way and the cancelled task is
now the correct outcome). `Scenario.addStopListener` is registered as defence-in-depth to
catch `StopReason.ERROR` raised by any processor we did not wrap.

**AD-9 — Exactly one new configuration key, in `bot-app`.**
`bot.group.stall.seconds=300` (Phase 5). Everything else is a code constant. Rationale:
`BotConfiguration` plumbing for engine-side knobs is a bigger diff than the knobs are
worth, and the values are not per-environment.

**AD-10 — Group DEAD is re-keyed, not removed; "not playing" gets its own channel.**
`BotGroupStatus` keeps its three values (`ACTIVE | STOPPED | DEAD`) — no enum change, no
UI contract change, and DEAD stays restartable. `monitorHealth` keeps the
`dead/total >= bot.group.dead.threshold` trigger, which now means "≥80 % of the group hit
a code fault" — a genuine, rare, page-worthy condition. The *common* degradation ("the
group is up but not playing") is expressed by finally writing the dormant
`BotGroupRuntime.playingStatus`, computed each tick:
- **PLAYING** — group `roundsObserved` (max across bots, as `computeStats` already does)
  advanced within `bot.group.stall.seconds`;
- **PENDING** — not PLAYING **and** ≥1 bot is `RECONNECTING`/connecting → "trying to play,
  server unresponsive" (matches the enum's own javadoc);
- **IDLE** — not PLAYING **and** every bot is connected → connected but no round progress
  (game silent, pipeline wedged, or genuinely between rounds).
`lastFailureReason` on the group becomes informative: `"N/M bots faulted: <site>:
<Throwable class>: <message>"`.

**AD-11 — Additive-only changes to the production-facing health API.**
`BotHealthDTO` gains nullable `lastFailureReason` (String, `null` when healthy).
`BotGroupStatsDTO` gains nullable `lastRoundAt` (Instant) and `stalledSeconds` (Long).
No field is removed or re-typed; no enum gains or loses a constant. `deadBots` keeps its
name and now counts faulted bots; `disconnectedBots` should trend to 0 once AD-5 lands and
is the regression canary.

**AD-12 — Metric additions are additive and bounded.**
New: `bot_reconnect_seconds_total` (per-bot cumulative seconds spent in `RECONNECTING`,
credited exactly like `bot_dead_seconds_total` at `Bot:470-477`, same MDC tags);
`group_stalled_seconds_total` + gauge `groups_stalled_currently` (Phase 5). Changed:
`bot_failures_total` gains a bounded `site` label (AD-8) — verified safe against every
committed Grafana query, all of which are `sum(rate(...))` with env/game selectors. No new
per-bot-identity labels (the `BotMetrics:40-49` cardinality cap stands). No metric is
renamed or removed.

---

## Plan

Phases are ordered by **safety dependency**, and each is independently shippable and
verifiable. Phase 3 (the headline change) must not ship before Phases 1–2.

### Phase 1 — Stop leaking orphan clients (prerequisite for everything)

*Why first:* it is the mechanism behind the live log flood, and it is the guard rail that
makes unbounded retry safe.

1. `Bot.java:676` (`tryReconnectWs`) — replace `if (client != null && client.isOpen()) client.close();`
   with an unconditional null-guarded `close()`, wrapped in try/catch-and-DEBUG (mirror
   `closeClientQuietly`). Do the same at `:664` (`closeClientQuietly`), `:278` (`restart`)
   and `:261` (`cleanup` — call `stop()` whenever `client != null`).
2. In `tryReconnectWs`, close the **previous** client before overwriting `this.client`
   (hold it in a local first) so no reference is dropped un-closed, including on the
   exception path at `:686-689`: if `connect()`/`start()` throws, close the just-built
   client before returning `false`.
3. Add `BotOrphanClientCloseTest` (bot-engine): a mocked client with
   `isOpen()==false` must still receive exactly one `close()` on reconnect, on restart, and
   on cleanup; a second reconnect must not close the same instance twice (the library's
   one-shot guard makes double-close harmless, but assert our call count is 1 per client).
4. Confirm no existing test asserts "close not called when !isOpen" — `BotReconnectTest`
   `shouldNoOpWhenStopped` (`:95-110`) asserts `times(1)` close with `isOpen()==true`, so
   it is unaffected; re-run the module suite.

### Phase 2 — Close the zombie gap (assert liveness)

1. `Bot`: add `public boolean isReconnecting()` (reads the existing `AtomicBoolean` at
   `:149`) and `public Instant getLastReconnectAttemptAt()` backed by a new
   `volatile Instant lastReconnectAttemptAt`, stamped at the top of each iteration of
   `runWsReconnectLoop` and at entry to `runAuthThenWsLoop`.
2. `Bot`: wrap the bodies of `runWsReconnectLoop` / `runAuthThenWsLoop` in
   `try { … } finally { if (!<success path already cleared>) reconnecting.set(false); }`
   — precisely: clear the flag in a `finally` on **every** exit, and let the sweep re-arm
   if the bot is still not alive. This removes the "thread died with flag stuck" wedge
   without changing the happy path.
3. `BotGroupBehaviorService.monitorHealth` (`:1497-1513`): add `sweepLiveness(runtime)`
   before the threshold check. For each bot: skip if `isStopped()` or `status == DEAD`;
   if `!isConnected() && !isReconnecting()` → `bot.triggerFullReconnect("liveness-sweep")`;
   if `isReconnecting() && lastReconnectAttemptAt` older than 300 s → force-clear + re-arm
   via the same call. Log one `WARN` per re-armed bot with the MDC already set by
   `startHealthMonitoring` (`:1483`).
4. `Bot.normalizeReconnectReason` (`:550-554`): add the `liveness-sweep` branch (bounded
   set per AD-5). `triggerFullReconnect` needs no other change.
5. Tests: `BotLivenessSweepTest` (bot-app) — a runtime holding
   (a) connected bot, (b) disconnected+reconnecting bot, (c) disconnected+not-reconnecting
   bot, (d) DEAD bot, (e) stopped bot: assert `triggerFullReconnect` is invoked for (c)
   only; then a stale-heartbeat reconnecting bot is re-armed. Extend `BotReconnectTest`
   with "loop exit always clears the reconnecting flag".

### Phase 3 — Remove the reconnect budget; make re-auth non-terminal

1. Delete `MAX_RECONNECT_CYCLES` (`Bot:38-44`) and the entire cap block (`:595-606`).
   `runWsReconnectLoop(int startCycle)` loses its `cycle` bookkeeping and its `startCycle`
   parameter (keep the single-arg form); `runAuthThenWsLoop` (`:631-634`) calls the plain
   loop.
2. `sleep()` callsites in the loop take jittered delays: add
   `private long jitter(long millis)` returning `millis * (0.8 + 0.4 * rnd)` using a
   per-bot `java.util.Random` seeded like `BettingMiniGameBot:170` (do **not** reuse the
   strategy RNG — `RandomBehaviorStrategyTest` pins its consumption order).
3. `performReauth()` (`:637-653`): on exception → `WARN` (`ERROR` at ≥10 consecutive, using
   a new `int consecutiveReauthFailures` reset on success), **no** status transition, **no**
   `reconnecting.set(false)`, **no** `closeClientQuietly()`, return `false`. Add the javadoc
   contract "never terminal — see BOT_LIVENESS_SEMANTICS AD-3/AD-4".
4. Callers: `runWsReconnectLoop:608` → `performReauth();` and **continue looping**
   regardless of the result (reset `attempt` to 0 either way so the next round starts from
   the 5 s step... **no** — reset to the index of the 60 s floor so a long outage never
   re-enters the fast steps; pin this: after the first exhausted sequence, `attempt` stays
   clamped at `BACKOFF_SECONDS.length - 1`). `runAuthThenWsLoop:620` → on `false`, fall
   through to `runWsReconnectLoop()` rather than returning.
5. Metrics: add `BotMetrics.incBotReconnectSeconds(long)` +
   `BOT_RECONNECT_SECONDS_TOTAL`, and credit the window in `transitionStatus` exactly as
   `deadSince`/`creditDeadSeconds` does (`:445-477`) — stamp on entry to `RECONNECTING`,
   credit on any exit and in `cleanup()`.
6. Tests: rewrite `BotReconnectTest`'s `@Nested "absolute reconnect-cycle cap"` into
   `@Nested "unbounded reconnect"` — (a) WS never holds + re-auth always succeeds ⇒ after
   ≥30 simulated cycles the bot is **never** DEAD and is still `RECONNECTING`; (b) re-auth
   always throws ⇒ never DEAD, loop still running, `bot_login_total{outcome=failure}`
   climbing; (c) recovery after 20 failed cycles still returns to STARTED and clears the
   flag. Rewrite `performReauth → shouldMarkDeadOnFailure` into `shouldNotMarkDeadOnFailure`.
   Adjust `shouldExhaustBackoffThenReauth` for the clamp + jitter (assert sleep ∈ [48 s,
   72 s] rather than exactly 60 s).

### Phase 4 — Introduce the real DEAD path (fault boundary)

1. `Bot`: add `public record BotFault(String site, String type, String message, Instant at)`
   (or a small class) + `private volatile BotFault lastFailure` with a getter, and
   `protected void onUnhandled(String site, Throwable t)`:
   `log.error("Bot {}: unhandled exception at {} — marking DEAD", userName, site, t)`,
   set `lastFailure`, `transitionStatus(DEAD)`, `reconnecting.set(false)`,
   `closeClientQuietly()` (now unconditional per Phase 1).
2. `Bot`: add `guardedRunnable(site, Runnable)`, `guardedConsumer(site, Consumer<T>)`,
   `guardedSupplier(site, Supplier<T>, boolean rethrow)` catching `Throwable`.
3. Compose at every callsite:
   - `BettingMiniGameBot:887-918` — each `mdcConsumer(this::onX)` → `guardedConsumer("message-dispatch", mdcConsumer(...))`;
     `:911-916` supplier → `guardedSupplier("bet-supplier", mdcSupplier(bet()), true)`,
     condition → `guardedSupplier("bet-condition", mdcSupplier(betCondition()), false)`
     with `false` fallback.
   - `SlotMachineBot:389-401` — same treatment (`spin` / `spinCondition` / both handlers).
   - `BettingMiniGameBot:348` countdown task and `:362` watchdog task → `guardedRunnable("countdown"/"watchdog", mdcWrap(...))`.
   - `Bot:523`/`:542` reconnect thread bodies → `guardedRunnable("reconnect-loop", mdcWrap(...))`.
   - `BotGroupRuntime.startBot:180-187` — catch `Throwable`, route to a new
     `bot.recordStartFailure(t)` that calls `onUnhandled("bot-start", t)` instead of only
     logging.
4. Defence in depth: in `Bot.start()` / the subclasses' `onStart`, register
   `scenario.addStopListener(ev -> { if (ev.reason() == StopReason.ERROR) onUnhandled("message-dispatch", ev.error()); })`
   on the behaviour scenario before `addScenario`.
5. `BotMetrics.incBotFailure()` → `incBotFailure(String site)` with the bounded `site` tag
   (AD-12); update the single existing callsite in `transitionStatus` to pass
   `lastFailure != null ? lastFailure.site() : "unknown"`.
6. `BotHealthDTO` + `BotGroupBehaviorService.getHealth:988-1000` — surface
   `lastFailureReason` (`site + ": " + type + ": " + message`, truncated to 200 chars,
   `null` when no fault).
7. Tests: `BotFaultBoundaryTest` (bot-engine) — a bot whose message handler throws goes
   DEAD with the throwable recorded and the client closed; a throwing `sendAsync` condition
   goes DEAD and the condition returns `false` rather than propagating; a throwing supplier
   goes DEAD **and** rethrows; a throwing countdown task goes DEAD. Assert
   `bot_failures_total{site=…}` is incremented once.

### Phase 5 — Rework the group-level rule

1. `BotGroupRuntime`: add `volatile Instant lastRoundObservedAt`, `volatile long lastRoundsSeen`,
   `volatile Instant stalledSince`, and `creditStalledSeconds(BotMetrics)` mirroring
   `creditGroupDeadSeconds` (`:319-326`), called from `stopAllBots` (`:264-271`).
2. `BotGroupBehaviorService.monitorHealth`: compute `maxRounds` (same expression as
   `computeStats:1143-1146`); if it advanced, stamp `lastRoundObservedAt` and clear
   `stalledSince`; else if older than `bot.group.stall.seconds` stamp `stalledSince`.
   Then set `playingStatus` per AD-10, and log the existing DEBUG summary with the added
   `playing=<status>` field.
3. Keep the `dead/total >= deadBotGroupThreshold` trigger; change
   `handleBotGroupDeath`'s `lastFailureReason` (`:1526`) from
   `"Multiple bot disconnections detected"` to the AD-10 fault string (first fault sampled
   from the bots' `lastFailure`).
4. Add `@Value("${bot.group.stall.seconds:300}")` + the key in
   `bot-app/src/main/resources/application.properties` next to `bot.group.dead.threshold`
   (`:94`).
5. Metrics: `group_stalled_seconds_total` in `BotMetrics`; `groups_stalled_currently` gauge
   in `ObservabilityConfig` next to `groups_dead_currently` (`:82-86`), backed by a new
   `countGroupsStalledCurrently()` mirroring `:1334-1340`.
6. `BotGroupStatsDTO` + `computeStats:1131-1180`: add `lastRoundAt` and `stalledSeconds`.
7. Tests: extend `BotGroupBehaviorServiceTest`'s `MonitorHealthTests` — round progress ⇒
   PLAYING; no progress + a reconnecting bot ⇒ PENDING; no progress + all connected ⇒ IDLE
   and `stalledSince` stamped; DEAD threshold still trips with faulted bots and writes the
   new reason string.

### Phase 6 — Documentation, dashboards, alert hooks

1. `CLAUDE.md`: rewrite the "Known Bugs" entries — the AUTH race note stays; add
   "orphan client on dead channel" (fixed, Phase 1) and rewrite the server-side pruning
   note to reference the Phase 2 sweep. Add a short "Bot liveness semantics" subsection
   under Architecture stating AD-1/AD-2/AD-5 in three sentences.
2. Grafana: update the panel descriptions that say "dead" (`bots.json:540`,
   `per-game.json:669`, `per-environment.json:669-670`) to "faulted (unhandled exception)",
   and add one panel per dashboard for `bot_reconnect_seconds_total` and
   `groups_stalled_currently`.
3. `prometheus/prometheus.yml` sibling rules file (new, still no Alertmanager — rules only,
   per MEMORY "alerting is post-MVP"): `BotsFaulted` (`increase(bot_failures_total[15m]) > 0`),
   `GroupStalled` (`groups_stalled_currently > 0 for 10m`), `FleetReconnecting`
   (`sum(bots_by_status{status="RECONNECTING"}) / sum(bots_managed) > 0.5 for 15m`).

---

## The 136 staging zombies: removing the cap does NOT fix them

State this explicitly to whoever reads this plan next, because the two changes look alike
and are not:

- **The cap is a *death* policy.** Removing it (Phase 3) only means a bot that is *already
  in* a reconnect loop keeps looping instead of dying at ~285 s. It has no effect
  whatsoever on a bot that never entered the loop.
- The staging bots are in exactly that second state: no disconnect event was processed, the
  `reconnecting` CAS at `Bot:515` never flipped, no worker thread exists. There is nothing
  for a cap to bound. Shipping Phase 3 alone would leave all 136 spinning and would
  additionally make the orphan-client population unbounded — i.e. it would make the live
  incident **worse**.
- What actually remediates them is **Phase 1** (unconditional `close()` — kills the orphan
  `sendAsync` pipelines that emit the ~97 lines/s) plus **Phase 2** (the 30 s liveness
  sweep — re-arms any bot that is neither alive nor reconnecting nor dead). Phase 1+2 are
  sufficient on their own and can ship before Phase 3.
- **Immediate relief before any code ships** is operational, not architectural: restart the
  affected groups (`POST /api/v1/bot-group/{id}/restart`), which tears down every client via
  `stopAllBots` → `Bot.cleanup()`, and cap the Loki volume / Docker json-file rotation per
  `RESILIENCE_HARDENING` P0 (still open per MEMORY).
- Diagnostic note for whoever triages next time: the flood line is keyed by **client name**
  (`ws-<username>`), and a bot that reconnected successfully keeps its old orphan clients
  alive under the *same* name. So the count of distinct usernames in the flood is an upper
  bound on wedged bots, not a measurement of them. Discriminator: if a username appears in
  both the flood **and** in current `SessionAggregationService` session summaries, it is an
  orphan-client leak (Phase 1), not a wedged bot (Phase 2).

---

## Implementation Notes / Concerns

- **This is money-adjacent production code.** The reconnect path controls whether hundreds
  of bots hammer an auth gateway. Ship one phase at a time, verify on staging between
  phases, and never combine Phase 1 with Phase 3 in a single deploy.
- **Ordering is load-bearing.** Phase 3 before Phase 1 ⇒ unbounded orphan clients ⇒ heap
  growth + an ever-accelerating log flood. Phase 3 before Phase 2 ⇒ bots that fall into the
  unmodelled state stay there forever with no cap to eventually mark them DEAD (today the
  cap is, perversely, the *only* thing that eventually retires such a bot).
- **`scheduleAtFixedRate` silently swallows the task on throw.** This applies to the library's
  `SendAsync` scheduler *and* to our own countdown scheduler. It is the reason Phase 4 must
  wrap the condition/supplier, not just the message handlers — and the reason a "the bot
  stopped betting but looks healthy" report should be treated as a Phase-4 regression.
- **Guard/MDC composition order.** `guardedX(site, mdcX(...))`, never the reverse: the MDC
  wrapper's `finally` must run (restoring the caller's context) before the fault handler
  logs, otherwise the ERROR line loses `botGroupId`/`botId` and Promtail cannot label it.
- **`transitionStatus` is not atomic** (`Bot:445-463` is a plain read-modify-write on a
  volatile). The fault boundary can race a concurrent reconnect transition. Acceptable —
  the DEAD guards at `:514`/`:530` are re-checked by every subsequent entry point and the
  liveness sweep is idempotent — but do not add a *new* invariant that assumes atomicity.
- **`bot.getStatus() == DEAD` is now rare**, so `runtime.setConsecutiveFailures((int) dead)`
  (`:1505`) will read 0 nearly always. It was already a misnomer (a snapshot, not a streak)
  and is surfaced as `BotGroupHealthDTO.consecutiveFailures`. Leave it alone in this plan;
  renaming it is a separate DTO change.
- **Periodic logout interacts with the sweep.** `performPeriodicLogout`
  (`BotGroupBehaviorService:1585-1645`) calls `bot.logout()` (which closes the client) then
  sleeps `reconnect-delay-seconds` before `bot.restart()`. During that window the bot is
  not connected and not reconnecting — the sweep could fire a redundant
  `triggerFullReconnect`. Mitigation: the sweep runs every 30 s and the logout window is
  5 s by default, so the collision probability is low, but Dev **must** add a
  `volatile boolean logoutInProgress` (set/cleared around the logout+restart pair) checked
  by the sweep. Do not skip this — a double reconnect during logout leaks a client.
- **`Bot.restart()`** (`:276-286`) does not clear `reconnecting` and does not go through the
  loop; after Phase 1 it closes the old client unconditionally, which is the fix it needed.
- **Jitter RNG.** Do not reuse `BettingMiniGameBot.rng` — `RandomBehaviorStrategyTest` pins
  its consumption order and an extra `nextDouble()` would break strategy determinism tests.
- **Test determinism.** `BotReconnectTest` overrides `sleep()` to record instead of sleeping
  (`FastBot`, `:474-481`); jitter must therefore be applied *before* the `sleep()` call so
  the recorded value is assertable, and the tests must assert a range.
- **Build:** `JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q -DskipTests=false test`
  from the repo root; run the whole reactor, not just `bot-engine` — Phases 2 and 5 touch
  `bot-app`.

---

## Open Items

- **Upstream auth classification (deferred, not rejected).** When ws-parser's `AuthClient`
  surfaces the HTTP status/envelope, revisit AD-4: a *confirmed* 401/404 "account does not
  exist" could then downgrade the bot to a distinct non-DEAD terminal state
  (e.g. `RETIRED`) instead of retrying forever. Needs a ws-parser release; out of scope here.
- **`SlotMachineBot` has no watchdog** (`SlotMachineBot:47`). The Phase 2 sweep covers the
  transport dimension (channel closed) but not the silent-subscriber-pruning dimension
  (channel open, server stopped sending). A slot watchdog is a separate, small plan.
- **Server-side subscriber pruning** (CLAUDE.md "silent zombie") is a *different* bug from
  the transport zombie addressed here and is unchanged by this plan.
- **Alertmanager / messenger routing** stays post-MVP (MEMORY); Phase 6 only adds recording
  rules.
- **Out of scope:** any change to `BotGroupStatus`/`BotGroupPlayingStatus` enum constants;
  auto-revival of DEAD bots (AD-7); the `consecutiveFailures` rename; the ws-parser
  PING-before-AUTH fix (MEMORY, separate one-line library change); Loki retention / Docker
  log rotation (RESILIENCE_HARDENING P0, still open).

---

## Verification

Releaser runs these on Bot-1 (staging) after each phase's deploy. Substitute `<bot-1>` for
the staging host and `$G` for a running group id. Note the Bot-1 single-compose layout
(MEMORY): a bot redeploy also restarts Grafana/Prometheus/Loki, so the smoke check must
re-verify observability every time.

### Universal smoke (after every phase)

1. App up:
   ```
   until curl -sf https://<bot-1>/actuator/health | grep -q '"status":"UP"'; do sleep 2; done
   ```
   Expect: returns within 60 s.
2. Observability stack survived the co-located redeploy:
   ```
   curl -sf http://<bot-1>:9090/-/healthy; curl -sf http://<bot-1>:3000/api/health
   ```
   Expect: Prometheus prints `Prometheus Server is Healthy.`; Grafana JSON contains
   `"database":"ok"`.
3. A group is running and playing:
   ```
   curl -sf https://<bot-1>/api/v1/bot-group/$G/health | jq '{totalBots,connectedBots,deadBots,disconnectedBots}'
   ```
   Expect: `connectedBots >= floor(totalBots*0.8)`, `deadBots == 0`.

### Phase 1 — orphan clients closed (this is the live-incident fix)

4. Baseline the flood rate, then compare 30 min after deploy:
   ```
   # before deploy and again 30 min after, over the same 10-minute window:
   curl -sfG http://<bot-1>:3100/loki/api/v1/query \
     --data-urlencode 'query=sum(count_over_time({job="bot-manager"} |= "Cannot send message, not connected" [10m]))'
   ```
   Expect: post-deploy value **< 1 % of** the pre-deploy value, and ideally `0`. (Pre-deploy
   baseline is ~58,000 per 10 min at the observed 97 lines/s.)
5. Restart one group and confirm no orphan survives it:
   ```
   curl -sf -X POST https://<bot-1>/api/v1/bot-group/$G/restart -o /dev/null -w '%{http_code}\n'
   sleep 120
   curl -sfG http://<bot-1>:3100/loki/api/v1/query \
     --data-urlencode "query=sum(count_over_time({job=\"bot-manager\"} | botGroupId=\"$G\" |= \"Cannot send message, not connected\" [2m]))"
   ```
   Expect: HTTP `200` from the restart; the Loki count is `0`.
6. No thread/heap regression:
   ```
   curl -sf https://<bot-1>/actuator/metrics/jvm.threads.live | jq '.measurements[0].value'
   curl -sf https://<bot-1>/actuator/prometheus | grep '^jvm_memory_used_bytes{area="heap"'
   ```
   Expect: `jvm.threads.live` within ±10 % of the pre-deploy value; heap used not trending
   monotonically up across three samples 10 min apart.

### Phase 2 — liveness sweep

7. Confirm the sweep is live and the zombie bucket drains:
   ```
   curl -sf https://<bot-1>/api/v1/bot-group/$G/health | jq '.disconnectedBots'
   ```
   Expect: `0` on a healthy group; if non-zero, re-poll after 60 s (two sweep ticks) and
   expect it to have moved to `reconnectingBots` or `connectedBots` — it must not stay
   non-zero across three consecutive polls.
8. Induce the state deliberately: pick one bot, have the operator drop its socket
   server-side (or use the DNS-blocked/unreachable-env recipe from
   `DEAD_GROUP_RESTART.md` step 3), then within 60 s:
   ```
   curl -sf https://<bot-1>/actuator/prometheus | grep '^bot_reconnects_total' | grep 'liveness-sweep'
   ```
   Expect: a `bot_reconnects_total{reason="liveness-sweep",...}` series exists with value
   `>= 1`, and the corresponding log line matches
   `^.*Bot .*: full reconnect triggered — liveness-sweep`.
9. No sweep storm on a healthy fleet:
   ```
   curl -sf https://<bot-1>/actuator/prometheus | grep 'bot_reconnects_total.*liveness-sweep'
   sleep 600
   curl -sf https://<bot-1>/actuator/prometheus | grep 'bot_reconnects_total.*liveness-sweep'
   ```
   Expect: the value is unchanged over 10 min while `deadBots == 0` and
   `connectedBots == totalBots`.

### Phase 3 — unbounded retry, non-terminal re-auth

10. **The headline test.** Have the operator take one environment's game server (or its
    WS host) offline for **≥ 20 minutes** — comfortably past the old 285 s death point —
    then bring it back. During the outage:
    ```
    curl -sf https://<bot-1>/api/v1/bot-group/$G/health | jq '{deadBots,reconnectingBots,totalBots}'
    ```
    Expect at T+5 min, T+10 min and T+20 min: `deadBots == 0` and
    `reconnectingBots == totalBots`. A single non-zero `deadBots` reading fails the phase.
11. Self-healing on return, without operator action:
    ```
    # 3 minutes after the server is back:
    curl -sf https://<bot-1>/api/v1/bot-group/$G/health | jq '{connectedBots,totalBots,deadBots}'
    curl -sf https://<bot-1>/api/v1/bot-group/$G | jq '.stats.roundsSinceRestart'
    ```
    Expect: `connectedBots >= floor(totalBots*0.8)`, `deadBots == 0`, and
    `roundsSinceRestart` strictly greater than its value taken during the outage.
12. Outage duration is measurable:
    ```
    curl -sf https://<bot-1>/actuator/prometheus | grep '^bot_reconnect_seconds_total'
    ```
    Expect: the counter exists and increased by roughly `totalBots × outage_seconds`
    (±20 %) across the outage window.
13. Backoff floor + jitter (no stampede on recovery):
    ```
    curl -sfG http://<bot-1>:3100/loki/api/v1/query \
      --data-urlencode "query=sum(count_over_time({job=\"bot-manager\"} | botGroupId=\"$G\" |= \"re-authenticating\" [10m]))"
    ```
    Expect: during a steady-state outage, ≤ `ceil(totalBots × 600/285) × 1.2` re-auth lines
    per 10 min (i.e. bots are on the 285 s cadence, not hammering).
14. Re-auth failure never kills:
    ```
    curl -sf https://<bot-1>/actuator/prometheus | grep '^bot_login_total{.*outcome="failure"'
    curl -sf https://<bot-1>/api/v1/bot-group/$G/health | jq '.deadBots'
    ```
    Expect (with the auth gateway unreachable): the failure counter climbs while
    `deadBots == 0`. Expect a log line matching
    `^.*Bot .*: re-authentication failed \(attempt [0-9]+\) — retrying` at WARN, and no line
    matching `marking DEAD`.

### Phase 4 — the real DEAD path

15. Prove the boundary fires, using a bot that is guaranteed to fault. Preferred deterministic
    recipe: create a throwaway 1-bot group on a valid environment but with a `Game` whose
    `offset`/message registration is deliberately wrong so a handler throws on the first
    frame (Dev to confirm the exact fixture during implementation and hand it to the
    Releaser). Then:
    ```
    curl -sf https://<bot-1>/api/v1/bot-group/$T/health | jq '.bots[0] | {status,lastFailureReason}'
    ```
    Expect: `status == "DEAD"` and `lastFailureReason` a non-null string starting with a
    site name from the AD-8 set (e.g. `message-dispatch: ...`).
16. Metric + log:
    ```
    curl -sf https://<bot-1>/actuator/prometheus | grep '^bot_failures_total' | grep 'site='
    ```
    Expect: a series with a bounded `site` label and value `>= 1`; a log line matching
    `^.*Bot .*: unhandled exception at .* — marking DEAD` at ERROR with a stack trace.
17. Healthy fleet emits zero faults:
    ```
    curl -sf https://<bot-1>/actuator/prometheus | grep '^bots_dead_currently'
    ```
    Expect: `0` across all healthy groups for at least 30 min after deploy.
18. DEAD group is still one-click restartable (DEAD_GROUP_RESTART regression):
    ```
    curl -sf -X POST https://<bot-1>/api/v1/bot-group/$T/start -o /dev/null -w '%{http_code}\n'
    ```
    Expect: HTTP `200`, and the reclaim INFO line
    `Bot group $T has a non-viable (DEAD) runtime — reclaiming before restart`.

### Phase 5 — group stall channel

19. Playing status is populated (it was always `IDLE` before):
    ```
    curl -sf https://<bot-1>/api/v1/bot-group/$G/health | jq '{playingStatus, stats}'
    ```
    Expect: `playingStatus == "PLAYING"` on a healthy betting/Tai Xiu group, with
    `stats.lastRoundAt` within the last `bot.group.stall.seconds` and
    `stats.stalledSeconds == null`.
20. Stall is detected during the Phase 3 outage recipe (or by pausing the game server for
    > 5 min):
    ```
    curl -sf https://<bot-1>/api/v1/bot-group/$G/health | jq '.playingStatus'
    curl -sf https://<bot-1>/actuator/prometheus | grep '^groups_stalled_currently'
    ```
    Expect: `playingStatus == "PENDING"` (bots reconnecting) or `"IDLE"` (bots connected,
    no rounds) — **not** `"PLAYING"` — and `groups_stalled_currently >= 1`.
21. Stall clears and time is credited:
    ```
    # after the game server returns and one round completes:
    curl -sf https://<bot-1>/api/v1/bot-group/$G/health | jq '.playingStatus'
    curl -sf https://<bot-1>/actuator/prometheus | grep '^group_stalled_seconds_total'
    ```
    Expect: `playingStatus == "PLAYING"`, `groups_stalled_currently` back to `0`, and
    `group_stalled_seconds_total` increased once (not still climbing).
22. Group DEAD is not falsely triggered by an outage:
    ```
    curl -sf https://<bot-1>/api/v1/bot-group/$G/status | jq '{targetStatus,actualStatus}'
    ```
    Expect: `ACTIVE`/`ACTIVE` throughout the Phase 3 outage — the group must **not** go DEAD
    just because nobody could connect.

### Phase 6 — dashboards / rules

23. Rules loaded:
    ```
    curl -sf http://<bot-1>:9090/api/v1/rules | jq '.data.groups[].rules[].name'
    ```
    Expect: `BotsFaulted`, `GroupStalled`, `FleetReconnecting` present, all `health: "ok"`.
24. Dashboards render:
    ```
    curl -sf http://<bot-1>:3000/api/search?query=Bot | jq '.[].title'
    ```
    Expect: the three dashboards listed; open each and confirm the new
    `bot_reconnect_seconds_total` / `groups_stalled_currently` panels return data (not
    "No data") for a running group.
</content>
</invoke>
