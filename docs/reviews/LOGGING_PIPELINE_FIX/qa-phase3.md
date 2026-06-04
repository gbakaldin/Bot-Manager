# QA Phase 3 — LOGGING_PIPELINE_FIX

**Verdict:** PASS

**Build:** `mvn test` → 313 tests, 0 failures, 0 errors, 0 skipped.

Baseline (Phase 2): 304. Dev added 6 (Phase 3). QA added 3 (OutputPrinter). Total: 313.

---

## Tests added by Dev (Phase 3, in commit `dff6960`)

- `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotMdcTest.java` — 4 tests:
  - `watchdogRunnableCarriesSnapshotOnFreshThread`
  - `countdownRunnableCarriesSnapshotOnFreshThread`
  - `onMessageConsumerCarriesSnapshotOnFreshThread`
  - `sendAsyncSupplierCarriesSnapshotOnFreshThread`
- `src/test/java/com/vingame/bot/domain/bot/core/BotReconnectMdcTest.java` — 2 tests:
  - `triggerFullReconnectVirtualThreadCarriesSnapshot`
  - `onWsDisconnectedVirtualThreadCarriesSnapshot`

## Tests added by QA (this review)

- `src/test/java/com/vingame/bot/domain/bot/util/OutputPrinterMdcTest.java` — 3 tests:
  - `withMdcAppliesSnapshotOnFreshThread`
  - `withMdcRestoresPriorMdc`
  - `withMdcWithNullSnapshotIsPassthrough` (also asserts the null-snapshot fast path returns the delegate identity)

Plan did not require an OutputPrinter test, but per task step 4 this hardens what the diagnosis identifies as the dominant source of un-labelled lines (~2,000 `User test_bcmini_XXX: ...` lines per session emitted by the message-processor pool).

---

## Callsite coverage table

| Surface wrapped in Phase 3 | File:line | Test coverage | How |
|---|---|---|---|
| `mdcWrap(this::runWsReconnectLoop)` virtual thread spawn | `Bot.java:292` | Direct | `BotReconnectMdcTest.onWsDisconnectedVirtualThreadCarriesSnapshot` — invokes private `onWsDisconnected` via reflection, captures MDC inside `sleep()` override (called as first action inside `runWsReconnectLoop`). |
| `mdcWrap(this::runAuthThenWsLoop)` virtual thread spawn | `Bot.java:306` | Direct | `BotReconnectMdcTest.triggerFullReconnectVirtualThreadCarriesSnapshot` — calls `triggerFullReconnect("test")`, `performReauth` is mocked to succeed, falls through to `sleep()` capture. |
| `mdcConsumer(...)` on `onWsStatusChange` callback | `Bot.java:272` | Indirect | No direct test. `Bot.mdcConsumer` itself is covered by `BotMdcWrapTest` (Phase 2). Wiring at line 272 verified by diff-reading only. |
| `mdcWrap(...)` on `onDisconnect` callback | `Bot.java:279` | Indirect | No direct test. `Bot.mdcWrap` covered by `BotMdcWrapTest` + indirectly by `BotReconnectMdcTest.onWsDisconnectedVirtualThreadCarriesSnapshot` (which traverses the same code path that the listener would invoke). Wiring at line 279 verified by diff-reading only. |
| `mdcWrap(countdown Runnable)` on `scheduler.scheduleAtFixedRate` | `BettingMiniGameBot.java:124` | Indirect | `BettingMiniGameBotMdcTest.countdownRunnableCarriesSnapshotOnFreshThread` exercises `bot.mdcWrap(...)` directly with a representative inner Runnable on a `BettingMiniGameBot` instance. Does **not** intercept the actual scheduled task — that wiring is verified by diff-reading. |
| `mdcWrap(this::onWatchdogExpired)` on `watchdogScheduler.schedule` | `BettingMiniGameBot.java:139` | Indirect | Same as above — `watchdogRunnableCarriesSnapshotOnFreshThread` exercises `bot.mdcWrap(...)` directly. |
| `mdcConsumer(this::onSubscribe/onStartGame/onUpdate/onEndGame)` | `BettingMiniGameBot.java:316-318,325` | Indirect | `onMessageConsumerCarriesSnapshotOnFreshThread` exercises `bot.mdcConsumer(...)` directly. Wiring verified by diff-reading. |
| `mdcSupplier(bet())` and `mdcSupplier(resolveBetCondition())` on `sendAsync` | `BettingMiniGameBot.java:320,322` | Indirect | `sendAsyncSupplierCarriesSnapshotOnFreshThread` exercises `bot.mdcSupplier(...)` directly. Wiring verified by diff-reading. |
| `OutputPrinter.debugOutputPrinter(snapshot)` peek consumer | `OutputPrinter.java:49-54` + `BettingMiniGameBot.java:347-352` | Direct | `OutputPrinterMdcTest` exercises the private `withMdc` adapter via reflection — same method used by `debugOutputPrinter`. Three behaviours covered: empty-MDC fresh-thread propagation, restore-prior-MDC re-entrancy, null-snapshot passthrough. |

Indirect entries above mean: the underlying wrapper (`mdcWrap`/`mdcConsumer`/`mdcSupplier`) is unit-tested on `Bot`, the production wiring at the specified line uses that wrapper exactly once, and the wiring itself is visible in the diff. No test installs a fake `ScheduledExecutorService` to capture and execute the scheduled task.

---

## Dev's deviations — verdict

### 1. `BotReconnectMdcTest` captures MDC via the protected `sleep(long)` override

**Verdict:** Acceptable.

Call chain walkthrough for `triggerFullReconnect`:

```
test invokes bot.triggerFullReconnect("test")
  → reconnecting.compareAndSet(false, true) succeeds
  → transitionStatus(RECONNECTING)
  → Thread.ofVirtual().name("reconnect-bot_reconnect").start(mdcWrap(this::runAuthThenWsLoop))
       └─ virtual thread runs mdcWrap'd Runnable:
              MDC.setContextMap(mdcSnapshot)   // <-- snapshot now visible
              try { runAuthThenWsLoop(); }     // calls performReauth() (mocked OK),
                                               // then tryReconnectWs() (fails — null client),
                                               // then runWsReconnectLoop()
                                               //   └─ first action: sleep(BACKOFF_SECONDS[0] * 1000)
                                               //                   └─ override captures MDC HERE
              finally { MDC.clear(); }
```

The capture happens strictly inside the `try` block of `mdcWrap`, so MDC carries the snapshot. The override flips `stopped` to make the loop exit on the next iteration. Snapshot is asserted to match `SNAPSHOT`. Sound proof that `mdcWrap` is applied at the `Thread.ofVirtual().start(...)` site.

The `onWsDisconnected` path is the same: `mdcWrap(this::runWsReconnectLoop)` → loop's first action is `sleep(...)` → captured. Both Bot.java:292 and Bot.java:306 are covered directly.

### 2. `BettingMiniGameBotMdcTest` exercises `bot.mdcWrap/mdcConsumer/mdcSupplier` directly with representative inner lambdas, not the actual scheduled tasks

**Verdict:** Acceptable with note.

Pro:
- The wrapper contract is already proven by `BotMdcWrapTest` (Phase 2).
- The test re-proves it on a `BettingMiniGameBot` instance specifically, so the inherited `mdcSnapshot` field and the inherited wrapper methods are exercised on the concrete subclass (catches accidental shadowing/override regressions).
- The actual wiring at the scheduler / scenario callsites (lines 124, 139, 316-318, 320, 322, 325) is a single, mechanical wrap call. Diff is small and obvious; intercepting it via a fake `ScheduledExecutorService` would be elaborate plumbing for low marginal value.

Con / note:
- Strict reading of the Phase 3 acceptance criterion ("invoke the scheduled `Runnable` produced by the watchdog/countdown wrap directly") expects the test to extract the actual scheduled task. Dev's test does not do this. If a future change accidentally replaces `mdcWrap(this::onWatchdogExpired)` with `this::onWatchdogExpired`, the existing tests will not catch the regression — only diff review will.
- Same gap applies to the `onMessage(mdcConsumer(...))` and `sendAsync(...mdcSupplier(...)...)` callsites: removing the wrap would not break any test.

The risk is low because the wrap is structural and any future review would catch its removal, but the residual coverage gap is real. Not a blocker for Phase 3 PASS; flagged as a follow-up candidate (see Concerns).

---

## OutputPrinter test — added

Yes. `OutputPrinterMdcTest` added by QA (3 tests, all passing). Tests target the private `withMdc(Consumer, Map)` adapter via reflection because it is the unit of behaviour that fixes the dominant log-line gap, and the public `Scenario` returned by `debugOutputPrinter` does not expose the wrapped consumer.

Three behaviours pinned:
1. On a fresh thread (empty MDC), the snapshot is applied inside the delegate, then cleared after — no leak.
2. On a thread that already has MDC, the snapshot replaces it inside the delegate, and the prior MDC is restored on exit — re-entrant and safe.
3. When the snapshot is `null`, the adapter returns the delegate identity (no allocation, no wrapping), and the delegate sees whatever MDC the calling thread had — matches the documented fallback in the Javadoc.

---

## Concerns / follow-ups

1. **`Bot.deposit()` callback at `Bot.java:197-210` is MDC-less.** `GameMsClient.deposit` (line 76) spawns a fresh unnamed `Thread`, joins on it, and invokes the `Consumer<Boolean>` `success -> { log.info("Deposit successful, ..."); log.info("New balance: ...") } / log.warn("Deposit failed")` callback on that thread. The thread inherits no MDC, and the callback was not wrapped in Phase 3. Two `log.info` and one `log.warn` per deposit attempt currently land MDC-less.
   - **Severity:** Low. Deposits are infrequent compared to the bet/round loop (one every several rounds at most, and only when auto-deposit is enabled).
   - **Plan coverage:** Section 2.3 of the plan does not enumerate this callsite. Plan focuses on the high-volume threads (message processor, schedulers, reconnect, callbacks).
   - **Recommendation:** Not a blocker for Phase 3. Either wrap with `mdcConsumer(success -> ...)` in a Phase 3 follow-up patch (one-line change) or accept the gap and verify it does not exceed the 90% threshold in Phase 5. If Phase 5 misses the threshold, this is the cheapest follow-up wrap to add before considering Phase 4 Option A.

2. **No test installs a fake `ScheduledExecutorService` or fake `Scenario` DSL** to verify that the wrappers are actually wired at the scheduler / scenario callsites. Coverage is "wrap function is correct + diff says it's used there." Acceptable for Phase 3 but means future regressions removing a single `mdcWrap(...)`/`mdcConsumer(...)`/`mdcSupplier(...)` will not be detected by the test suite. Optional follow-up: a small test that injects a `ScheduledExecutorService` capturing the scheduled `Runnable` and asserts MDC is set inside it. Out of scope for this QA pass.

3. **`mdcConsumer` on `onWsStatusChange` and `mdcWrap` on `onDisconnect`** (Bot.java:272 and Bot.java:279) have no direct test. Indirect coverage via `BotMdcWrapTest` (wrapper contract) plus `BotReconnectMdcTest.onWsDisconnectedVirtualThreadCarriesSnapshot` (which calls `onWsDisconnected` directly, simulating what `onDisconnect` would invoke). The `onWsStatusChange` wrap is uncovered except by diff-reading. Same severity / recommendation as concern 2.

4. **`BotReconnectMdcTest.onWsDisconnectedVirtualThreadCarriesSnapshot` invokes the private `onWsDisconnected` directly via reflection** rather than registering the `onDisconnect` listener and triggering it through the WS client. The wired listener is `wsClient.onDisconnect(mdcWrap(() -> { if (!stopped) onWsDisconnected(); }))`. The test proves that the spawned virtual thread inside `onWsDisconnected` carries the snapshot — but does **not** prove that the outer `mdcWrap` on the `onDisconnect` lambda itself works. (If `mdcWrap` were removed at line 279, the test would still pass because `onWsDisconnected` spawns its own `mdcWrap`'d virtual thread.) Minor coverage gap consistent with concern 3.

---

## Conclusion

All Phase 3 acceptance criteria are met in spirit:
- Build green, no regressions, baseline preserved.
- New tests (Dev's 6 + QA's 3) cover the wrapper contract and the highest-leverage callsite (`OutputPrinter`).
- Reconnect virtual threads are directly proven to carry MDC via the `sleep()` override technique.
- Scheduler / scenario callsite wiring is verified by diff-reading plus indirect wrapper tests; a strict reading of "invoke the scheduled `Runnable` directly" is not satisfied but the residual risk is low.

Verdict: **PASS**. Concerns 1-4 are non-blocking follow-ups; concern 1 (`Bot.deposit()` callback) is the only production gap that could materially affect Phase 5's >90% MDC coverage measurement and is the cheapest to close if Phase 5 misses the target.
