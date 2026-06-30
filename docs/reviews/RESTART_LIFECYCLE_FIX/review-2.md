# Code Review (Pass 2) — RESTART_LIFECYCLE_FIX

Branch: `feat/restart-lifecycle-fix`
Reviewed diff: `git show d824aff592c0b437e66702d7b66c90b210dd5631`

Targeted second pass of the fix-up commit addressing the 1 bug + 3 smells raised in `review.md`. The 3 style nits were intentionally deferred and are not re-flagged here.

## Verdict

PASS

No `bug` or `security` findings in the fix-up. One residual `smell` (inaccurate Javadoc claim about the `/restart` retry path) is advisory and does not block.

## Prior-finding resolution checklist

### [bug] `bot_creation_failures_total` missing MDC tags — RESOLVED

`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:319-338`

- `BotMdc.setGroupContext(group.getId(), group.getEnvironmentId())` is set on the caller thread immediately before the result-collection loop.
- The whole `for (int i = 0; i < futures.size(); i++)` loop body, including the catch arm that calls `botMetrics.incBotCreationFailure(...)`, runs inside the `try`.
- The matching `BotMdc.clear()` is in `finally`, so MDC is cleared on every exit path (loop completes normally, loop body throws something unexpected, `futures.get(i).join()` throws something not caught by the inner `catch (Exception e)`).
- `start()` does not set MDC on the caller thread itself, so this `clear()` is not clobbering a caller-supplied MDC.
- Pattern mirrors the existing `start()` cleanup catch (lines 245-262) and `stop()` (lines 440-450), so the codebase convention is consistent.

Regression test `incBotCreationFailure_seriesIsTaggedWithBotGroupIdAndEnvironmentId` at `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceRestartTest.java:303-377`:

- Wires a real `SimpleMeterRegistry` + `BotMdcTagsMeterFilter` + `BotMetrics` through a fresh `BotGroupBehaviorService` (constructor matches signature at line 118).
- Stubs `botFactory.createBot` to throw `RuntimeException("network unreachable")` — exercises the exact catch arm at line 325-333.
- Asserts the tagged series exists (`reason=unknown`, `botGroupId=group-tagged`, `environmentId=env-tagged`) AND `count == 2.0` (botCount=2, both failed).
- Asserts no untagged sibling series exists by stream-filtering for a `reason=unknown` counter whose tag set lacks `botGroupId`. This is the precise shape that would be present if the MDC were not set on the caller thread, so the assertion has real discriminating power.

### [smell] `game==null` NPE in fail-loud format — RESOLVED

`src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java:114`

The `String.format` now uses `game != null ? game.getGameType() : null`. The format call itself can no longer NPE.

Note for the record (already acknowledged in the original review): `BotFactory.java:87` still does `game.getName()` unconditionally, so a null `game` would NPE at the entry-level debug log before reaching the IllegalStateException formatter. That pre-existing latent NPE was explicitly noted as out of scope in the original review and remains so. The fix-up's guard is internally consistent with its stated defensive intent and does not introduce any new issue.

### [smell] `restart()` post-throw recovery procedure undocumented — RESOLVED (with one inaccurate detail; see Findings)

`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:479-490`

The added Javadoc accurately describes:
- DB state at throw: `targetStatus=ACTIVE` (line 234-237 sets this before the post-start verification block fires).
- Runtime state at throw: zero-bot runtime is in `runningGroups`.
- Visibility hooks: exception, ERROR log, `bot_creation_failures_total` metric.
- Recommended recovery: POST `/stop` (works — removes the inconsistent runtime, persists `targetStatus=STOPPED`), then POST `/start` (works — passes the `containsKey` early-return because `/stop` removed the entry).

One claim in the Javadoc is factually wrong; see the residual finding below.

### [smell] Reason classifier `ValidationException` → "auth" — RESOLVED

`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:357-366`

- Explicit `instanceof ValidationException` arm at line 364, classifying as `"validation"`.
- Ordering is correct: first `IllegalStateException || IllegalArgumentException` (line 354), then `ValidationException` (line 364), then substring fallback (lines 370-375). A websocket-parser `ValidationException` whose message contains "auth" can no longer leak into the substring fallback.
- Import at line 22 is `com.vingame.websocketparser.exception.ValidationException` — the websocket-parser type, not `jakarta.validation.ValidationException` or any Spring variant.
- The stale comment in the substring block ("websocket-parser throws ValidationException (caught above)") was updated to reflect the new structure.

## Findings

### [smell] Javadoc claim about `/restart` retry short-circuiting is factually incorrect

`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:486-490`

The new Javadoc says:

> Retrying POST `/restart` directly will short-circuit at the "already running" early-return in `start(String)` because `runningGroups.containsKey(id)` is still true.

This is not what happens at runtime. `restart(id)` (line 492) unconditionally calls `stop(id)` first. `stop(id)` (line 433) reads the runtime (the zero-bot one), calls `stopAllBots` (no-op on an empty list), removes the entry from `runningGroups`, and persists `targetStatus=STOPPED`. Only then does `restart` sleep and call `start(id)` — and by that point `runningGroups.containsKey(id)` is **false**, so the "already running" early-return at line 181 is not hit. A retried `/restart` would actually succeed (modulo whatever made the first attempt produce zero bots).

The recommendation that "operator should POST /stop then POST /start" is still defensible — it's a cleaner separation of intent and lets the operator inspect state between steps — but the *reason* given for it in the Javadoc is wrong. Either (a) drop the "short-circuit" sentence and reframe the recommendation as "preferred, because it lets you confirm STOPPED before retrying", or (b) keep the recommendation but delete the inaccurate justification.

Low priority — this is a docstring, not behaviour — but the original smell was specifically asking for an *accurate* recovery doc, and this part isn't.

## Notes

- The regression test's defense-in-depth assertion (untagged-series count is zero) is a nice touch — without it, if some other meter filter happened to also tag the counter, the positive assertion alone wouldn't catch a partial regression.
- The classifier comment update (removing the now-stale "caught above" reference and replacing with a clearer rationale for the substring fallback) is appreciated.
- No new bugs introduced. No previously-missed smells flagged (per the "don't expand scope" instruction).

Review verdict: PASS
Findings: 0 bug, 0 security, 1 smell, 0 style
docs/reviews/RESTART_LIFECYCLE_FIX/review-2.md written.
