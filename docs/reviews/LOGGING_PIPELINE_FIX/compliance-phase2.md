# Compliance — LOGGING_PIPELINE_FIX, Phase 2

Branch: `feat/logging-pipeline-fix`
Plan reviewed: `docs/plans/LOGGING_PIPELINE_FIX.md` (Phase 2, Section 5)
Diff reviewed: `git diff 13e8f24..6630a29`

## Verdict

COMPLIANT (PASS)

## Per-deliverable

| # | Deliverable | Status | Notes |
|---|---|---|---|
| 1 | `protected volatile Map<String, String> mdcSnapshot` field on `Bot` | PRESENT | `Bot.java:60`, declared `protected volatile`, javadoc explains intent. |
| 2 | Snapshot captured at end of `initialize()` try block, after `client.connect()` returns, before finally `BotMdc.clear()` | PRESENT | `Bot.java:130` — `this.mdcSnapshot = MDC.getCopyOfContextMap();` placed inside the try block, after `client.connect()` (line 121) and the success `log.info` (line 123), before the finally that clears MDC (line 134). |
| 3 | `protected Runnable mdcWrap(Runnable r)` — stash + set + run + restore-or-clear | PRESENT | `Bot.java:402-418`. Stash via `getCopyOfContextMap`, applies snapshot only if non-null, restores stash in finally or `MDC.clear()` if stash was null. Matches AD 7 contract exactly. |
| 4 | `protected <T> Callable<T> mdcCall(Callable<T> c)` | PRESENT | `Bot.java:420-436`. Same contract; returns `c.call()`. |
| 5 | `protected <T> Supplier<T> mdcSupplier(Supplier<T> s)` | PRESENT | `Bot.java:438-454`. |
| 6 | `protected <T> Consumer<T> mdcConsumer(Consumer<T> c)` | PRESENT | `Bot.java:456-472`. |
| 7 | New `BotMdcWrapTest` — subclass `Bot` with no-op abstract methods | PRESENT | `BotMdcWrapTest.TestBot` at lines 389-396. |
| 8 | Set a known snapshot map | PRESENT | `BotMdcWrapTest:42-48,55` — five-key SNAPSHOT matching the keys `BotMdc.set(...)` populates. |
| 9 | mdcWrap — fresh thread empty MDC: snapshot visible inside, cleared after | PRESENT | `appliesSnapshotAndClearsAfterOnEmptyMdcThread` (lines 73-89). |
| 10 | mdcWrap — fresh thread different MDC: snapshot inside, outer restored after | PRESENT | `restoresOuterMdcAfterRun` (lines 92-110). |
| 11 | Same two scenarios for `mdcCall` | PRESENT | `appliesSnapshotAndClearsAfterOnEmptyMdcThread` + `restoresOuterMdcAfterCall` (lines 167-217). |
| 12 | Same two scenarios for `mdcSupplier` | PRESENT | `appliesSnapshotAndClearsAfterOnEmptyMdcThread` + `restoresOuterMdcAfterGet` (lines 251-296). |
| 13 | Same two scenarios for `mdcConsumer` | PRESENT | `appliesSnapshotAndClearsAfterOnEmptyMdcThread` + `restoresOuterMdcAfterAccept` (lines 306-345). |

Extra (non-required, not scope creep): `noOpWhenSnapshotIsNull` and `propagatesExceptionAndRestoresMdc` for `mdcWrap`; `propagatesCheckedExceptionAndRestoresMdc` for `mdcCall`. Strengthens the AD 7 contract — acceptable.

## Architecture Decision coverage

| AD | Subject | Status | Evidence |
|---|---|---|---|
| AD 3 | Stay with SLF4J `MDC`; no `ScopedValue` | RESPECTED | All helpers use `org.slf4j.MDC` (`Bot.java:14` import, lines 404/422/440/458). No `ScopedValue` anywhere in the diff. |
| AD 6 | Snapshot captured ONCE at end of `initialize()` (just before finally clear). Field `protected volatile Map<String,String>`. Includes all five MDC keys via `MDC.getCopyOfContextMap()`. | RESPECTED | Field declared `protected volatile` at line 60. Snapshot taken at line 130, inside the try block, after the success path completes. `BotMdc.set(...)` at lines 99-105 populates all five keys (`botGroupId`, `botId`, `environmentId`, `gameType`, `botUserName`) before the snapshot. Single capture site — no other assignment to `mdcSnapshot` exists. |
| AD 7 | `mdcWrap`/`mdcCall` (+ `mdcSupplier`/`mdcConsumer`) live on `Bot`. Stash → `setContextMap(snapshot)` → run → restore stash (or clear if null) in finally. Re-entrant on threads with different MDC. | RESPECTED | All four helpers are `protected` instance methods on `Bot`. Stash/apply/run/restore pattern verbatim in each. Re-entrancy proven by `restoresOuter*` tests across all four helpers. Snapshot application guarded by `mdcSnapshot != null` (defensive, plan-compatible — does not violate the contract because tests do not require behaviour when snapshot is null, and the existing behaviour is documented in the helper javadoc at `Bot.java:393-400`). |

## Scope check

Files touched in `git diff 13e8f24..6630a29`:

- `src/main/java/com/vingame/bot/domain/bot/core/Bot.java` — expected.
- `src/test/java/com/vingame/bot/domain/bot/core/BotMdcWrapTest.java` — expected.

Files explicitly NOT touched (confirmed clean):

- `BettingMiniGameBot.java` — untouched. Phase 3 territory.
- `OutputPrinter.java` — untouched. Phase 3 territory.
- `log4j2.properties`, `log4j2-json-template.json` — untouched.
- `pom.xml` — untouched.
- `BotMdc.java` — untouched.
- WS callback registrations inside `Bot.java` (`configureClient` at lines 266-277, reconnect thread spawns at lines 287 and 301) — untouched. Phase 3 territory.

No scope creep.

## Plan amendments

None. Plan Phase 2 was technically correct and implementable as written; Dev's implementation follows every bullet. The `mdcSnapshot != null` guard inside the helpers is a small defensive addition (helpers no-op cleanly if invoked before `initialize()` completes), documented in the helper javadoc and consistent with AD 7's intent. It does not contradict the plan.

## Notes for the Releaser / next phase

- The plan's "snapshot non-null after `initialize()` returns successfully" criterion holds: the assignment at `Bot.java:130` runs on the only successful path through the try block, before any `return`.
- The helpers' `mdcSnapshot != null` guard means any Phase-3 callsite that wraps work scheduled BEFORE `initialize()` finishes will silently no-op for MDC. This is fine for the call sites Phase 3 will touch (all are post-initialize) but worth keeping in mind if any new pre-init callsites are added later.
