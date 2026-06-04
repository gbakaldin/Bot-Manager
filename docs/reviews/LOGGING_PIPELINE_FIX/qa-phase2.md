# QA — LOGGING_PIPELINE_FIX Phase 2

**Verdict:** PASS
**Build:** `mvn test` → 304 tests, 0 failures, 0 errors, 0 skipped
**Commit reviewed:** `6630a29` on `feat/logging-pipeline-fix`

## Test counts vs Phase 1 baseline

| Metric | Phase 1 baseline | Phase 2 | Delta |
| --- | --- | --- | --- |
| Total tests | 293 | 304 | +11 |
| Passing | 293 | 304 | +11 |
| Failing | 0 | 0 | 0 |
| Skipped | 0 | 0 | 0 |

All 11 new tests are in `com.vingame.bot.domain.bot.core.BotMdcWrapTest`, organised into 4 `@Nested` classes matching the four helper variants:

- `MdcWrapRunnable` — 4 tests
- `MdcCallCallable` — 3 tests
- `MdcSupplierSupplier` — 2 tests
- `MdcConsumerConsumer` — 2 tests

## Coverage check per helper (Architecture Decision 7)

| Helper | Snapshot applied inside wrap | Outer MDC restored | Null snapshot | Exception propagation + restore |
| --- | --- | --- | --- | --- |
| `mdcWrap(Runnable)` | yes | yes | yes | yes (unchecked) |
| `mdcCall(Callable)` | yes | yes | no | yes (checked `IOException`) |
| `mdcSupplier(Supplier)` | yes | yes | no | no |
| `mdcConsumer(Consumer)` | yes | yes | no | no |

The contract's two required scenarios ("snapshot visible inside" and "outer MDC restored") are present on every helper. Null-snapshot and exception-propagation are exercised on the primary helper (`mdcWrap`) and partially on `mdcCall`; the other three helpers share the same try/finally template byte-for-byte, so the risk of an uncaught regression there is low. Not adding new tests.

`assertEmptyMdc` accepts either `null` or an empty map, which correctly accommodates SLF4J's MDC adapter returning either after `MDC.clear()`.

## Production-code findings

- **`mdcSnapshot` field** at `Bot.java:60` is `protected volatile Map<String, String>` — `volatile` confirmed per plan Section 5 Phase 2.
- **Snapshot capture site** at `Bot.java:130` is inside the `initialize()` try block, after the successful `client.connect()` (line 121) and `BotMdc.set(...)` (lines 99-105), and before the `finally { BotMdc.clear(); }`. All five MDC keys (`botGroupId`, `botId`, `environmentId`, `gameType`, `botUserName`) are guaranteed to be populated at the capture point. Matches Architecture Decision 6 and Implementation Note "Don't snapshot too early".
- **Initialize-throws case:** if `apiGatewayClient.authenticate(...)` or `client.connect()` throws, the snapshot line is never reached and `mdcSnapshot` remains null. The helpers handle null correctly: stash the caller's MDC, leave MDC untouched going in, and in finally restore the stash (or clear if stash was null). Verified by `noOpWhenSnapshotIsNull` and consistent with Architecture Decision 7.
- **Helper implementations** (`Bot.java:402-472`) are four near-identical try/finally blocks. Each one stashes via `getCopyOfContextMap()`, conditionally applies `mdcSnapshot`, runs the action, and restores stash-or-clear. No code-path divergence between variants.
- **No production callsites changed** — Phase 2 is additive dead code, exactly per plan. Phase 3 will wire it in.

## Concerns / follow-ups

None blocking. Two minor observations for the record:

1. The `mdcConsumer` and `mdcSupplier` helpers have no explicit exception-path test. Because the try/finally template is identical to the tested helpers, this is not worth backfilling now; if Phase 3 starts depending on those helpers in error-prone scenarios (e.g. `bet()` supplier throwing inside the betting loop), add a test then.
2. The `noOpWhenSnapshotIsNull` test exercises null snapshot only with a non-empty outer MDC. The null-snapshot + null-stash case (where the finally `MDC.clear()` runs but has nothing to clear) is not directly asserted. It's a no-op on no-op so the missing assertion has no behavioural consequence.

## Failures

None.
