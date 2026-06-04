# Code Review — LOGGING_PIPELINE_FIX Phase 3

Branch: `feat/logging-pipeline-fix`
Reviewed diff: `git diff 6630a29..dff6960` (Phase 3 on top of Phase 2).
Scope: five files — `Bot.java`, `BettingMiniGameBot.java`, `OutputPrinter.java`,
`BettingMiniGameBotMdcTest.java`, `BotReconnectMdcTest.java`. No scope creep into
`BotMdc.java`, `pom.xml`, log4j config, `docker-compose.yml`, or the library — confirmed.

## Verdict

APPROVE WITH NITS

No bugs, no security issues, no correctness misses on any of the 13 enumerated
callsites. Findings below are all minor / observational. Full test suite (313)
green; new Phase 3 tests (6) green in ~1s locally.

---

## Wrap-correctness table

Each callsite Dev wrapped, with the **value handed to the executor / DSL** verified
against the wrap.

| # | Callsite | File:Line | Wrap shape | OK? |
|---|---|---|---|---|
| 1 | `Thread.ofVirtual().start(... runWsReconnectLoop)` | `Bot.java:292` | `mdcWrap(this::runWsReconnectLoop)` — wrapped Runnable handed to `start()` | Yes |
| 2 | `Thread.ofVirtual().start(... runAuthThenWsLoop)` | `Bot.java:306` | `mdcWrap(this::runAuthThenWsLoop)` — same | Yes |
| 3 | `onWsStatusChange(...)` lambda | `Bot.java:272-278` | `mdcConsumer(wsStatus -> { ... })` — wrapped Consumer handed to library | Yes — see caveat in Finding 2 |
| 4 | `onDisconnect(...)` lambda | `Bot.java:279-281` | `mdcWrap(() -> { ... })` — wrapped Runnable handed to library | Yes |
| 5 | `scheduleAtFixedRate` countdown | `BettingMiniGameBot.java:124-128` | `scheduleAtFixedRate(mdcWrap(() -> { ... }), ...)` | Yes |
| 6 | `schedule` watchdog | `BettingMiniGameBot.java:138-142` | `schedule(mdcWrap(this::onWatchdogExpired), ...)` | Yes |
| 7 | `.onMessage(subscribeClass, onSubscribe)` | `BettingMiniGameBot.java:316` | `mdcConsumer(this::onSubscribe)` handed to DSL | Yes |
| 8 | `.onMessage(startGameClass, onStartGame)` | `BettingMiniGameBot.java:317` | `mdcConsumer(this::onStartGame)` handed to DSL | Yes |
| 9 | `.onMessage(updateBetClass, onUpdate)` | `BettingMiniGameBot.java:318` | `mdcConsumer(this::onUpdate)` handed to DSL | Yes |
| 10 | `messageSupplier(bet())` | `BettingMiniGameBot.java:320` | `mdcSupplier(bet())` — wrapped Supplier handed to builder | Yes |
| 11 | `condition(resolveBetCondition())` | `BettingMiniGameBot.java:322` | `mdcSupplier(resolveBetCondition())` | Yes |
| 12 | `.onMessage(endGameClass, onEndGame)` | `BettingMiniGameBot.java:325` | `mdcConsumer(this::onEndGame)` handed to DSL | Yes |
| 13 | `OutputPrinter.debugOutputPrinter` consumer | `OutputPrinter.java:52-53` via `BettingMiniGameBot.java:347-352` | Inlined `withMdc(printer, snapshot)` wrap, snapshot threaded in as 4th arg | Yes |

No callsite wraps the *outer* lambda when the *inner* work is what runs on the
foreign thread — every wrap is the value handed to the executor/DSL/library
boundary. The DSL chain in `botBehaviorScenario()` correctly passes each
`mdcConsumer(...)` / `mdcSupplier(...)` directly without an extra lambda capture.

Single callsite for `debugOutputPrinter` in the repo (`grep -rn debugOutputPrinter src/`):
`BettingMiniGameBot.java:347` — no orphan callsite left after the signature change.

---

## Findings

### 1. [minor] `Bot.deposit()` callback runs on a fresh `new Thread(...)` with no MDC

`src/main/java/com/vingame/bot/domain/bot/core/Bot.java:197-210`
`src/main/java/com/vingame/bot/infrastructure/client/GameMsClient.java:72-120`

`gameMsClient.deposit(agencyToken, amount, success -> { ... })` is a `Consumer<Boolean>`
that the implementation invokes from a freshly-spawned `new Thread(...)` inside
`GameMsClient.deposit` (line 76, 104, 109). That thread has empty MDC. The callback
body contains three `log.info` / `log.warn` calls (`"Bot {}: Deposit successful…"`,
`"Bot {}: New balance: {}"`, `"Bot {}: Deposit failed"`) which will emit without
`botGroupId` etc.

This callsite is not in the plan's Section 3 readiness table and not in the 13
callsites Dev was asked to wrap, so it's strictly out of scope for this phase.
Worth recording because:
- Phase 5's >90% acceptance criterion may or may not hit it depending on how often
  auto-deposit fires during the 5-minute reproduction.
- The fix is trivial when ready: `gameMsClient.deposit(token, amount, mdcConsumer(success -> { ... }))`.

Fix shape: one-line wrap in `Bot.deposit()` line 197, OR add to Phase 4's scope.
Flagging for the Architect / Phase 5 measurement step.

### 2. [minor] Snapshot/capture race in `configureClient` invoked from `Bot.initialize()`

`src/main/java/com/vingame/bot/domain/bot/core/Bot.java:115` and `Bot.java:130`

`initialize()` calls `configureClient(client)` at line 115, then `client.connect()`
at line 121, then captures `mdcSnapshot = MDC.getCopyOfContextMap()` at line 130.
The `onWsStatusChange` callback installed at line 272 reads `mdcSnapshot` *at
invocation time* (the helper reads the field, not the value at wrap time) — which
is the correct contract — but if the Netty IO thread fires `CONNECTED` /
`AUTHENTICATING_WS` between `client.connect()` and the snapshot assignment, the
helper will see `mdcSnapshot == null` and per its own contract leave MDC alone.
Result: the very first `transitionStatus()` log line for those statuses can be
MDC-less. After the snapshot is set, every subsequent invocation is correct.

In practice this is a single-frame window per bot lifecycle. Won't move the needle
on the >90% acceptance criterion but worth knowing if Phase 5's `jq` thread-name
breakdown shows a handful of MDC-less lines on `multiThreadIoEventLoopGroup-2-N`
with status-transition messages.

Fix shape (optional): hoist the snapshot capture to before `client.connect()`
(but still after `BotMdc.set(...)` populated all five keys). Trivial reorder of
lines 121 and 130.

### 3. [nit] `BettingMiniGameBotMdcTest` does not actually exercise the scheduler

`src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotMdcTest.java:96-105`
(and the symmetric countdown / consumer / supplier tests)

Each test asserts the wrap mechanism by invoking `bot.mdcWrap(...)` directly with a
representative inner `Runnable` and running it on a fresh `new Thread(...)`. The
plan literally said "invoke the scheduled `Runnable` produced by the watchdog /
countdown wrap directly," but extracting the actual `Runnable` handed to
`ScheduledExecutorService.schedule(...)` from outside is awkward (it's a private
local) — Dev's compromise is reasonable. The proof would be tighter if the test
intercepted the scheduler (e.g., via a `ScheduledExecutorService` test seam or a
spy that captures the `Runnable` argument), but the indirection is small and the
helpers are now well-covered by both this test and the Phase 2 `BotMdcWrapTest`.

Documented in the test class header — acceptable. Note only.

### 4. [nit] `BotReconnectMdcTest.onWsDisconnectedVirtualThreadCarriesSnapshot` depends on the inner reconnect path reaching `sleep(...)`

`src/test/java/com/vingame/bot/domain/bot/core/BotReconnectMdcTest.java:111-128`
(and the `triggerFullReconnect` variant at lines 95-108)

The test sets `bot.client = wsClient` (a Mockito mock of `VingameWebSocketClient`),
then invokes `onWsDisconnected` via reflection. The reconnect virtual thread runs
`runWsReconnectLoop`, whose first line is `sleep(BACKOFF_SECONDS[0] * 1000)` — that
hits the overridden `sleep(long)` which captures MDC, flips `stopped=true`, and
returns immediately. Works because `sleep` is reached before any code path that
would NPE.

Caveat: a future refactor that does work *before* the first `sleep(...)` could
silently break the capture (e.g., if `runWsReconnectLoop` started with a log line
that NPE-ed on a missing collaborator, the future would never complete and the
test would block for 5s and time out instead of producing a useful failure
message). Today the path is clean. Low-risk note.

No flake risk observed locally (both tests run in ~1s). No wallclock dependency
because `sleep` is a no-op override.

### 5. [nit] `OutputPrinter.withMdc` duplicates the wrap contract instead of routing through `Bot`

`src/main/java/com/vingame/bot/domain/bot/util/OutputPrinter.java:62-80`

The inlined `withMdc` mirrors `Bot.mdcConsumer`'s stash / set-if-non-null / restore-or-clear
pattern. The Javadoc explicitly calls this out and points to Architecture
Decision 7. Keeping `OutputPrinter` free of a `Bot` reference is the right call
(it's used in unit-printer contexts and as a generic scenario utility). Duplication
is two helpers in two files — accept.

If future code adds a third copy of the same pattern, consider extracting into a
small `MdcSupport` utility. Not actionable now.

### 6. [nit] `OutputPrinter.withMdc` treats empty snapshot identically to a populated one

`src/main/java/com/vingame/bot/domain/bot/util/OutputPrinter.java:64-67`

`if (snapshot == null) return delegate;` — but if `snapshot` is non-null but empty
(e.g., a test calls `Map.of()`), the wrap calls `MDC.setContextMap(emptyMap)` which
will clear any caller MDC for the duration of the consumer body. In production
this can't happen because the snapshot comes from `MDC.getCopyOfContextMap()`
after `BotMdc.set(...)` populated five keys, so it's never empty. Mentioned for
completeness; no action needed.

---

## Looks good — what I specifically verified

- Each of the 13 callsites passes the **wrapped** value to the executor/DSL/library,
  not an invocation, and not an outer-lambda-around-an-inner-invocation.
- `mdcConsumer` on `onWsStatusChange` preserves the consumer's behaviour for every
  status (the `default -> {}` branch is preserved, the switch is untouched).
- `OutputPrinter` signature change has exactly one production callsite, updated in
  the same commit (`grep -rn debugOutputPrinter src/`).
- The inlined `withMdc` matches the same contract shape as `Bot.mdcConsumer`:
  stash → set-if-snapshot-non-null → run → restore-or-clear. Null-safety identical.
- No double-wraps in the diff (e.g., `mdcWrap(mdcWrap(...))`).
- Phase 3 stays inside the five expected files. No edits to `BotMdc.java`, `pom.xml`,
  log4j config, `docker-compose.yml`, `Dockerfile`, `deploy.sh`, or any library
  source.
- `mvn test`: 313 tests, 0 failures, 0 errors. Targeted run of the two new test
  classes: 6 tests pass in ~1.1s (no flake, no wallclock).
- `BotReconnectMdcTest.CapturingBot.sleep(long)` is a proper override of the
  protected `Bot.sleep(long)` and the override path executes inside the
  `mdcWrap(this::runWsReconnectLoop)` / `mdcWrap(this::runAuthThenWsLoop)` lambda
  body — so the MDC the test captures is genuinely the snapshot applied by the
  wrap, not whatever the parent thread had.
- The `mdcSnapshot` field is `volatile` and read inside the wrap lambdas at
  invocation time (not value-captured at wrap time), so registering `mdcConsumer`
  / `mdcWrap` in `configureClient` *before* the snapshot is assigned at line 130
  is still correct in steady state.

## Notes

- Finding 1 (`Bot.deposit` callback) is the only place I'd ask the Architect to
  decide between "fold into Phase 3" (one extra line — `success -> { ... }` becomes
  `mdcConsumer(success -> { ... })`) and "park for Phase 4 if Phase 5 measurement
  surfaces it." Either is defensible; deferring is consistent with the plan's
  stated scope.
- Finding 2 (snapshot ordering) is a clean one-line reorder if anyone wants to be
  thorough; ignore otherwise.
- The plan's Section 6 already calls out "Don't snapshot too early" — the existing
  capture point (line 130) is *late enough* that all five keys are present; it just
  happens to be *late enough* that one connect-completion frame can race ahead.
  Trade-off either way.
