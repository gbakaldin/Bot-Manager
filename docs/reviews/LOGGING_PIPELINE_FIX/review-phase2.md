# Code Review — LOGGING_PIPELINE_FIX (Phase 2)

Branch: `feat/logging-pipeline-fix`
Reviewed diff: `git diff 13e8f24..6630a29`
Files in diff:
- `src/main/java/com/vingame/bot/domain/bot/core/Bot.java` (+99)
- `src/test/java/com/vingame/bot/domain/bot/core/BotMdcWrapTest.java` (+397, new)

## Verdict

**APPROVE WITH NITS**

No blockers, no majors. All findings below are minor / nit-level and can be addressed in Phase 3 or deferred.

## Findings

### 1. [nit] `mdcSnapshot` captured as a mutable `HashMap`-like map; no defensive copy or unmodifiable wrap
`src/main/java/com/vingame/bot/domain/bot/core/Bot.java:130`

`MDC.getCopyOfContextMap()` returns the underlying adapter's copy (Log4j2's `Log4jMDCAdapter` returns a fresh `HashMap`), and the field is `protected volatile`. The four helpers then pass this reference straight into `MDC.setContextMap(mdcSnapshot)`. Most SLF4J adapters defensively copy on `setContextMap`, so in practice this is safe, but the contract here relies on adapter behaviour. A subclass that pokes into `mdcSnapshot` could mutate it under multiple helper invocations.

Suggested fix (optional): wrap the snapshot in `Map.copyOf(...)` (or `Collections.unmodifiableMap(...)`) at capture time. This also documents intent ("immutable after initialize"). Costs nothing.

### 2. [nit] Four near-identical helpers — consider extracting the common stash/apply/restore frame
`src/main/java/com/vingame/bot/domain/bot/core/Bot.java:399-471`

Each of `mdcWrap` / `mdcCall` / `mdcSupplier` / `mdcConsumer` duplicates the same 10-line frame. Could be folded into a single private `<T> T withMdc(Callable<T>)` that the four public adapters delegate into, or a `try-with-resources`–style `AutoCloseable` MDC scope. Not required — the duplication is small, the contract is identical, and the explicit shape makes each helper easy to read in isolation. Flagging only because the plan's Architecture Decision 7 mandates exactly one contract; centralising it would make future contract changes one-touch.

### 3. [nit] Helpers lack Javadoc on the `protected` API surface
`src/main/java/com/vingame/bot/domain/bot/core/Bot.java:401, 419, 437, 455`

The block comment at line 391-400 documents the contract for all four, which is reasonable. Per-helper Javadoc (one line: "Wraps `r` so it runs with the bot's MDC snapshot applied…") would help IDE hover-help for subclass authors in Phase 3. Optional.

### 4. [nit] Test file name and class diverge slightly from the helper set under test
`src/test/java/com/vingame/bot/domain/bot/core/BotMdcWrapTest.java:40`

Class is `BotMdcWrapTest` but covers all four helpers (`mdcWrap`, `mdcCall`, `mdcSupplier`, `mdcConsumer`). The `@DisplayName("Bot MDC wrap helpers")` mitigates this; the file name is fine. Pure style observation — no change needed.

### 5. [nit] `assertEmptyMdc` accepts null-or-empty; test doc-comment is good but matcher could be tightened later
`src/test/java/com/vingame/bot/domain/bot/core/BotMdcWrapTest.java:355-358`

Correctly permissive per the SLF4J/Log4j2 adapter contract (the comment at 350-354 calls this out explicitly). No issue. If the team standardises on Log4j2 forever, this could be tightened to `assertTrue(mdc == null || mdc.isEmpty())` → `assertTrue(mdc == null)` once the adapter behaviour is asserted elsewhere, but the current flexible form is the right call.

## Looks good — specifically verified

1. **Snapshot capture timing is correct.** `mdcSnapshot = MDC.getCopyOfContextMap()` at `Bot.java:130` sits inside the `try` block opened at line 106, after the full `BotMdc.set(...)` at 99-105, after `client.connect()` at 121, and before `BotMdc.clear()` in the `finally` at 134. All five keys (`botGroupId`, `botId`, `environmentId`, `gameType`, `botUserName`) are guaranteed populated at capture. If `client.connect()` (or any earlier step) throws, the snapshot is never assigned and `mdcSnapshot` remains `null` — exactly the "didn't reach steady state" semantics the plan requires.

2. **Helper contract matches Architecture Decision 7 byte-for-byte.** Each helper:
   - Calls `MDC.getCopyOfContextMap()` first → `stash`.
   - Calls `MDC.setContextMap(mdcSnapshot)` **only** when `mdcSnapshot != null` (correct — leaving outer MDC untouched on a pre-`initialize()` invocation is the right no-op).
   - Runs the wrapped action.
   - In `finally`: `setContextMap(stash)` if `stash != null` else `MDC.clear()`. This is the canonical "save/restore" pattern and is correctly re-entrant — an outer `mdcWrap` puts the snapshot in MDC; the inner `mdcWrap`'s `stash` captures that snapshot; inner restores it on exit; outer then restores its own original on its own exit.
   - Exception path: stash/restore lives in `finally`, so any exception from `r.run()` / `c.call()` / `s.get()` / `c.accept(t)` propagates with MDC correctly restored. Test cases at 134-157 (Runnable) and 219-241 (Callable, including a checked `IOException`) confirm this empirically.

3. **Field declaration matches the plan.** `protected volatile Map<String, String> mdcSnapshot;` at `Bot.java:60` — `volatile` ensures the post-`initialize()` write is visible to any thread that later runs a wrapped callback (Netty IO loop, virtual reconnect threads, schedulers). No public setter; the only assignment site is `Bot.initialize():130`. Subclasses can read but the field is not part of the public Bot API.

4. **Re-entrancy is sound by construction.** Stash-restore based on the live MDC (not the snapshot) means nested `mdcWrap`s correctly nest. The test in 92-110 (`restoresOuterMdcAfterRun`) covers the populated-outer-MDC case directly; the same proof applies inductively to nested wraps.

5. **Null-snapshot semantics are tested and correct.** `noOpWhenSnapshotIsNull` at 112-131 verifies that when `mdcSnapshot == null`, the outer MDC ("k"→"v") is visible inside the wrapped action and is unchanged after. This is the intended pre-`initialize()` behaviour.

6. **Exception propagation tested for both `Runnable` (unchecked) and `Callable` (checked).** Both restore the outer MDC on the abnormal-exit path. Worth noting: the `mdcSupplier` / `mdcConsumer` exception paths aren't explicitly tested, but they're structurally identical to the other two and the contract is enforced by the same `finally` block; not worth requesting an explicit test.

7. **Test isolation.** `@BeforeEach` and `@AfterEach` call `MDC.clear()`. `runOnFreshThread` uses a brand-new platform `Thread` per call (no executor reuse → no MDC bleed-through), with a 5-second timeout and a `join(1000)` cleanup. No `Thread.sleep`, no flaky timing dependencies.

8. **Imports are clean.** New imports (`org.slf4j.MDC`, `java.util.Map`, `java.util.concurrent.Callable`, `java.util.function.Consumer`) all used. No leftover/unused imports.

9. **Scope is exactly Phase 2.** No edits to `BettingMiniGameBot`, `OutputPrinter`, the `configureClient` callback registrations at `Bot.java:266-277`, the reconnect virtual-thread spawns at `Bot.java:287`/`301`, `log4j2.properties`, `log4j2-json-template.json`, `pom.xml`, or `BotMdc.java`. Confirmed by `git diff 13e8f24..6630a29 --stat` (two files only) and by reading `Bot.java:240-302` to verify Phase 3 territory is untouched.

10. **No concurrency hazards introduced.** Snapshot is captured once at end of `initialize()` (single-writer) and read via `volatile` from many threads (multi-reader). The helpers themselves hold no state — they're pure factories returning a fresh wrapped lambda per call. No `AtomicLong` / `AtomicBoolean` misuse, no broken `volatile` pairings.

## Notes

- Phase 3 will need to actually *wire* these helpers into the callback registrations and reconnect spawns (`Bot.java:267-275`, `Bot.java:287`, `Bot.java:301`). The current Phase 2 commit adds the helpers but does not yet apply them anywhere — `mvn compile` will report `mdcCall` / `mdcSupplier` / `mdcConsumer` as unused. That's expected and matches the phase split; no action here.

- Consider whether `mdcSnapshot` should also be re-captured on `restart()` / `runAuthThenWsLoop()`'s re-auth path. After re-auth, `tokens` and `client` change but the five MDC keys do not (group/index/env/game/userName are invariant for the lifetime of the bot), so the existing snapshot stays valid. Worth confirming in Phase 3 testing but not a Phase 2 concern.

- `BotMdc.set` tolerates a null `gameType` / `userName`, but `Bot.initialize()` passes `configuration.getGame().getName()` and `userName` — if either is somehow null at initialize time, the snapshot would be missing those keys. Not a Phase 2 regression (pre-existing), just calling it out.
