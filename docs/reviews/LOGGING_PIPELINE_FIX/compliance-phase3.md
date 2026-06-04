# Compliance — LOGGING_PIPELINE_FIX (Phase 3)

Branch: `feat/logging-pipeline-fix`
Plan reviewed: `docs/plans/LOGGING_PIPELINE_FIX.md`
Diff reviewed: `git diff 6630a29..dff6960`

## Verdict

**COMPLIANT**

## Per-deliverable table

| File / site | Plan deliverable | Status |
|---|---|---|
| `Bot.java` L292 | `Thread.ofVirtual()...start(mdcWrap(this::runWsReconnectLoop))` | PRESENT |
| `Bot.java` L306 | `Thread.ofVirtual()...start(mdcWrap(this::runAuthThenWsLoop))` | PRESENT |
| `Bot.java` L272-278 | `onWsStatusChange(mdcConsumer(...))` | PRESENT |
| `Bot.java` L279-281 | `onDisconnect(mdcWrap(...))` | PRESENT |
| `BettingMiniGameBot.java` L139 | watchdog `schedule(mdcWrap(this::onWatchdogExpired), ...)` | PRESENT |
| `BettingMiniGameBot.java` L121 | countdown `scheduleAtFixedRate(mdcWrap(...))` | PRESENT |
| `BettingMiniGameBot.java` L316-318, 325 | `onMessage` callbacks wrapped with `mdcConsumer` (subscribe, startGame, update, endGame) | PRESENT |
| `BettingMiniGameBot.java` L320, 322 | `sendAsync` `bet()` wrapped with `mdcSupplier`; `resolveBetCondition()` wrapped with `mdcSupplier` | PRESENT |
| `BettingMiniGameBot.java` L347-352 | `OutputPrinter.debugOutputPrinter(..., mdcSnapshot)` callsite updated | PRESENT |
| `OutputPrinter.java` | `debugOutputPrinter` accepts `Map<String, String>`, wraps inner `Consumer<String>` with snapshot | PRESENT |
| `BettingMiniGameBotMdcTest.java` | New test exercising scheduler-wrap path | PRESENT (DEVIATED — see below) |
| `BotReconnectMdcTest.java` | New test exercising `triggerFullReconnect` / equivalent | PRESENT (DEVIATED — see below) |

## AD coverage

- **AD 4 — RESPECTED.** No edits to `VingameWebSocketClient` or `PipelineStage`. The two library imports in repo files (`Bot.java` line 9, `OutputPrinter.java` line 7) are existing references to library types, not modifications. Scope is strictly bot-side wrap at in-repo callsites.
- **AD 5 — RESPECTED.** `OutputPrinter.debugOutputPrinter` was modified in-place; no parallel printer was introduced inside `BettingMiniGameBot`. The MDC application lives in a private `withMdc` helper inside `OutputPrinter`, so `OutputPrinter` does not require a `Bot` reference.
- **AD 7 — RESPECTED.** All wraps follow the snapshot / try / `setContextMap(stash)` or `clear()` contract established in Phase 2. `OutputPrinter.withMdc` mirrors the same shape (stash, set, try, restore-or-clear).

## Scope check

Five files touched, matching the expected set:
- `src/main/java/com/vingame/bot/domain/bot/core/Bot.java`
- `src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`
- `src/main/java/com/vingame/bot/domain/bot/util/OutputPrinter.java`
- `src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotMdcTest.java`
- `src/test/java/com/vingame/bot/domain/bot/core/BotReconnectMdcTest.java`

No scope creep. `debugOutputPrinter` has exactly one callsite (`BettingMiniGameBot.onStart`), so no other updates were required.

## Deviation verdict

**1. `BotReconnectMdcTest` overrides `protected sleep(long)` instead of stubbing `runWsReconnectLoop` / `runAuthThenWsLoop`. — APPROVED.**

`runWsReconnectLoop` and `runAuthThenWsLoop` are `private`, so they cannot be overridden in a subclass. The override-`sleep` approach is a valid alternative that still proves the wrap is in place at the spawn site: the captured MDC inside `sleep` is observed from a virtual thread launched as `Thread.ofVirtual().start(mdcWrap(this::runXxx))`. If the wrap were missing or wrong, the captured map would be `null`/empty rather than the snapshot. The plan's wording was overoptimistic about overriding a private method; this is worth a small clarifying note in the plan.

**2. `BettingMiniGameBotMdcTest` calls `bot.mdcWrap(...)` directly with a representative inner Runnable. — APPROVED.**

`ScheduledExecutorService.schedule` / `scheduleAtFixedRate` do not expose the wrapped task once enqueued, and the schedulers in `BettingMiniGameBot` are private fields wired internally. Extracting the actual scheduler-bound `Runnable` would require either (a) substituting the scheduler via reflection before initialization (fragile, and would re-test the JDK scheduler) or (b) reading the queue contents reflectively (brittle across JDK versions). Calling `bot.mdcWrap(...)` directly exercises the same wrap mechanism against the same `mdcSnapshot` field, which is the only thing under test. Acceptable, and matches `Bot.mdcWrap`'s contract. Worth a small clarifying note in the plan.

Both deviations are reasonable adaptations to private-access / API-shape constraints. Neither rises to the level of "the plan was technically impossible" — the wraps are demonstrably in place and the tests prove the propagation semantics. Send-back to Dev would be inappropriate. Since the plan's suggested test strategies were technically infeasible as written, a small clarifying amendment is warranted but does not change the verdict for Phase 3.

## Plan amendments

None required for Phase 3 verdict (COMPLIANT). The two test-strategy wording overshoots are minor and can be addressed by Architect-1 in the next planning cycle if desired; they do not affect what shipped. No edit to `docs/plans/LOGGING_PIPELINE_FIX.md` made.
