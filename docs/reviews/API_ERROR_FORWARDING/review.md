# Code Review — API_ERROR_FORWARDING

Branch: `feat/api-error-forwarding`
Reviewed diff: `git diff f7508b5~1..25560a5` (the two commits in scope — `f7508b5` Phase A, `25560a5` Phase B)

## Verdict

CHANGES_REQUESTED

One smell-class regression in Spring's default 4xx mapping (advice's terminal `Exception` handler swallows `MissingServletRequestParameterException` / `HttpRequestMethodNotSupportedException` / `HttpMediaTypeNotSupportedException` and reroutes them from 400/405/415 to 500) plus one security-adjacent issue (raw `e.getMessage()` from the terminal fallback can leak internal details when the upstream is a DB driver / Mongo / Netty exception). The remaining findings are smells / styles.

## Findings

### [smell] Terminal `Exception` handler overrides Spring's default 4xx mappings
`src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java:117`

`@ExceptionHandler(Exception.class)` is the catch-all in the advice. Because Spring picks the most-specific handler, this handler now intercepts a family of Spring servlet exceptions that previously got first-class 4xx mappings from Spring's default error machinery:

- `MissingServletRequestParameterException` → was 400, now 500.
- `HttpRequestMethodNotSupportedException` → was 405, now 500.
- `HttpMediaTypeNotSupportedException` / `HttpMediaTypeNotAcceptableException` → was 415 / 406, now 500.
- `MissingPathVariableException`, `ServletRequestBindingException` (parent of the above), etc.

Today no controller endpoint uses `@RequestParam(required=true)` so the first item is unobserved, but the method/media-type cases are reachable by any client that hits e.g. `GET /api/v1/bot-group/{id}/start` (only `POST` is mapped) — the operator will see a `500 Internal error` instead of `405 Method not allowed`.

Two reasonable fixes:
1. Have `RestExceptionHandler` extend `ResponseEntityExceptionHandler` (preserves Spring's defaults and lets you override the body shape) — recommended.
2. Add explicit `@ExceptionHandler` arms for the Spring servlet exceptions you care about (`HttpRequestMethodNotSupportedException` → 405, `HttpMediaTypeNotSupportedException` → 415).

The plan didn't call this out and AD-6's mapping table only lists the two Spring exceptions you do handle (`MethodArgumentTypeMismatchException`, `HttpMessageNotReadableException`); the rest now silently degrade.

### [security] Raw `e.getMessage()` in the 500 fallback leaks internal infrastructure details
`src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java:121`

`handleAny` returns `new ErrorResponse("Internal error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())`. For unexpected runtime failures, `e.getMessage()` regularly contains data the operator-facing UI should not show:

- `MongoSocketException` / `MongoTimeoutException` exposes the Mongo cluster host/port (`Timed out after 30000 ms while waiting for a server that matches WritableServerSelector. Client view of cluster state is {type=UNKNOWN, servers=[{address=mongo.internal:27017, ...}]}`).
- `org.springframework.beans.factory.BeanCreationException` / `BeanInstantiationException` leaks class names and the wiring failure.
- `IOException`/`UnknownHostException` from the JDK `HttpClient` (the `ApiGatewayClient.authenticate` path now wraps these into `UpstreamLoginException`, but every other client path doesn't — `GameMsClient`, `BotMetrics`, anything that bubbles into a controller) carries hostnames, ports, sometimes auth headers.
- `IllegalStateException` from `ApiGatewayClient.checkInitialized` carries the literal `"ApiGatewayClient not initialized..."` — class names back to the operator.

This is the same class of leak the security checklist flags as "stack-trace leak via response body." The advice already logs the full exception server-side, so there's no operator data lost by sanitising the response body.

Suggested fix: in `handleAny` (and to a lesser extent `handleIllegalState`), don't forward `e.getMessage()` verbatim. Return a fixed `msg` ("Internal server error — see server logs"), keep the existing `log.error(..., e)` so the operator can correlate via the request URI and stacktrace. Reserve verbatim-forwarding for the *typed* exceptions where the message is known to be operator-safe (`UpstreamRegistrationException`, `UpstreamLoginException`, `BadRequestException`, `IllegalArgumentException`).

### [smell] Per-bot failure log in `start()`'s finally drops the underlying cause
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:271`

The Phase B refactor swapped the outer `catch (Exception e)` for `try/finally` + `boolean started`. Correct for exception-type preservation, but the failure log went from:

```java
log.error("Failed to start bot group {}: {}", group.getName(), e.getMessage(), e);
```

to:

```java
log.error("Failed to start bot group {}", group.getName());
```

The exception type, message, and stacktrace are no longer attached to this log line. Operators grepping for `"Failed to start bot group"` in Loki used to see the root cause inline; now they have to correlate timestamps with the Rest advice's `Handled <Type> from /api/v1/bot-group/{id}/start: <msg>` log on a different logger. The advice logging covers the end-to-end story for HTTP entry points, but `start()` is also called from `restart()`, `scheduleRestart()`, and `onStartup()` (auto-start) where there's no advice in the path — auto-start failures lose all detail except the group name.

Fix shape: keep the typed propagation but capture the in-flight exception into a Throwable variable for the log. Pattern:
```java
Throwable failure = null;
try { ... started = true; }
catch (Throwable t) { failure = t; throw t; }
finally {
    if (!started) {
        ...
        log.error("Failed to start bot group {}: {}", group.getName(),
                  failure != null ? failure.toString() : "(unknown)", failure);
    }
}
```

### [smell] Inconsistent log-level discipline between the four `handleBadRequest`/`handleIllegalArgument` arms and the rest
`src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java:48-82`

`BadRequestException`, `IllegalArgumentException`, `MethodArgumentTypeMismatchException`, `HttpMessageNotReadableException` all log at `WARN`. `UpstreamGatewayException` and `IllegalStateException` log at `ERROR` with the full stacktrace. `ResourceNotFoundException` logs at `INFO`. That's a sensible level ladder, but two of the WARN arms (`BadRequestException`, `IllegalArgumentException`) pass `e.getMessage()` as the message-placeholder argument — they do NOT pass the throwable. The other two (`MethodArgumentTypeMismatchException`, `HttpMessageNotReadableException`) do not pass the throwable either. The `UpstreamGatewayException` and `IllegalStateException` arms DO pass the throwable.

For client-induced 400s this is intentional (no stacktrace pollution from caller mistakes). Worth a one-line comment so a future contributor doesn't "fix" the inconsistency by adding `e` to the WARN arms. Or, conversely, drop the throwable from the ERROR arms whose message already carries everything needed (e.g. `UpstreamLoginException`'s wrapped cause is mostly noise from the websocket-parser internals). Either direction works; the current state reads like an oversight.

### [smell] `IllegalStateException` arm is documented as "transitional" but no follow-up is queued
`src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java:97-110`

The Javadoc says "future commits should re-type the meaningful ones." Plan AD-8 echoes the same. No follow-up plan / item / TODO references this. The transitional state will rot — at some point an operator hits a "this is a bug, file it" `IllegalStateException` and gets `500 + {type:"Internal error", msg:"<cryptic message about not initialized">}` which is acceptable for an internal error but, per the security finding above, leaks class state. Either (a) re-type the remaining `IllegalStateException` callsites the plan flagged (it lists `ApiGatewayClient:111`, `ClientFactory:60` and `BotGroupBehaviorService.restart:527`) or (b) drop the dedicated handler and let them fall through to `handleAny` — there's no semantic difference today.

### [smell] `UpstreamGatewayException`'s `type` discriminator collapses both subclasses to the same string
`src/main/java/com/vingame/bot/common/exception/UpstreamRegistrationException.java:13`,
`src/main/java/com/vingame/bot/common/exception/UpstreamLoginException.java:15`

Both `UpstreamRegistrationException` and `UpstreamLoginException` pass `"Game server error"` as the `type` to the base. The plan (AD-2) explicitly motivated typing the gateway failures distinctly so "a future panel/log query [can] filter 'registration failed' vs 'login failed' without grep-ing the `msg` string." With both subclasses emitting the same `type`, the response body cannot distinguish them — the only remaining signal is the (multi-line, language-localised) `msg`. This isn't *wrong*, but it walks back the rationale in AD-2.

Two acceptable fixes:
1. Set distinct `type` strings (`"Registration error"`, `"Login error"`) — matches the plan intent and lets the UI render different copy.
2. Keep `"Game server error"` for both but accept the regression vs. AD-2 and remove the rationale claim from the Javadoc.

If the frontend has already locked on `"Game server error"`, option (2) is the lower-risk path; this finding then becomes a no-op for shipping, just an alignment between code and plan-doc.

### [smell] Pre-flight 400 and upstream 502 fire for the same root cause (username length)
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java:120,139,184`

`validateUsernameLength` throws `BadRequestException` (400) when `prefix+botCount > ProductCode.getUsernameMaxLength()`. The same "username too long" error can also come back from the gateway as `UpstreamRegistrationException` (502) when the environment's `productCode` is null, or the product code has no `usernameMaxLength` configured (today: 7 of the 10 products per CLAUDE.md). The frontend will see different status codes (400 vs. 502) and different `type` strings (`"Bad request"` vs. `"Game server error"`) for what is, from the operator's perspective, the same underlying constraint.

Not a bug — the plan accepts the inconsistency implicitly via AD-3 (upstream-rejected = 502, owned-by-us = 400). Flagged because the UX implication wasn't explicitly traced in the plan and the divergence may surprise UI authors.

### [smell] `classifyCreationFailure` cardinality is bounded but the order-of-arms is fragile
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:361-393`

Three labels total (`auth`, `validation`, `unknown`) — cardinality budget intact. The new `UpstreamLoginException` arm is placed *before* the `BadRequestException` / `IllegalStateException` / `IllegalArgumentException` group. The order is correct *now* (`UpstreamLoginException` extends `RuntimeException`, not any of the BadRequest types, so no overlap), but the explanatory comment under the `ValidationException` arm (lines 376-379) warns that future regressions could mis-label. The `UpstreamLoginException` arm has no such comment — if someone later changes the hierarchy and makes `UpstreamLoginException` extend `BadRequestException` (or similar), it would silently land in the `validation` bucket since `instanceof` chains both. Worth a one-line comment.

### [smell] Cleanup catch in `start()`'s finally swallows the stacktrace
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:266-269`

```java
} catch (Exception cleanupEx) {
    log.error("Error cleaning up bots after failed start of group {}: {}",
            group.getName(), cleanupEx.getMessage());
}
```

Logs the message but not the throwable. If `failedRuntime.stopAllBots(botMetrics)` is itself failing (e.g. an executor was never created, or a thread interruption fired during cleanup), the operator only sees a one-line message with no stacktrace. Add `cleanupEx` as the last arg so SLF4J attaches the trace.

### [style] `ApiGatewayClient.registerSingleUser` still uses untyped `RuntimeException`
`src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:272,319`

Per the user's prompt, this was intentionally left untyped (the exception is consumed inside `registerUsers`'s parallel-fan-out and aggregated into `UserRegistrationResult.errors`, never escaping to a controller). Consistent with the plan. Flagged as `style` only because someone reading the file in isolation can't easily tell — a one-line comment on each throw site ("Caught and aggregated inside registerUsers; never reaches the REST layer") would prevent a future commit from "helpfully" retyping it.

### [style] `ErrorResponse` field names `type` / `msg` are non-standard
`src/main/java/com/vingame/bot/common/dto/ErrorResponse.java:8`

Most public API conventions (RFC 7807, OpenAPI examples, Spring's default `DefaultErrorAttributes`) use `error` / `message` or `title` / `detail`. `type` / `msg` is custom to this app. Plan AD-4 acknowledged this was a continuation of the shipped `fc5dc15` shape, so consistency wins. Flagged only if the frontend hasn't already locked on the names — once it has, this is the right call and the finding is a no-op.

## Notes

- Phase B regression check requested in the brief (point 9) — confirmed clean. `grep -c "catch (" controllers` returns 0 for all four (`BotGroupController`, `EnvironmentController`, `GameController`, `SessionHistoryController`). No legacy `catch (IllegalStateException)` shadows the advice in `BotGroupController.save`.
- MDC scope (point 7) — `BotGroupBehaviorService.stop` and the `start()` cleanup block correctly bracket `BotMdc.setGroupContext` with try/finally + `BotMdc.clear`. The result-collection MDC scope in `createBotsInParallel` (lines 327-346) is also intact. No regression vs. Phase 1's MDC fix.
- The `UpstreamGatewayException` base is correctly abstract — leaf classes can't be bypassed. Good API surface.
- Test coverage for the advice is thorough: `RestExceptionHandlerTest` exercises every handler arm through real MVC machinery; each `*ControllerTest` adds at least one advice-routed case (`@Import(RestExceptionHandler.class)` on all four). The `BotGroupControllerTest.shouldReturnBadGatewayWhenUpstreamLogin` test pins the new `UpstreamLoginException` → 502 path end-to-end.
- The 36-catch-blocks deletion target was met exactly: BotGroup 14 + Environment 8 + Game 8 + SessionHistory 6 = 36 → 0.
- `ApiGatewayClient.authenticate` wrapper is well-scoped: `catch (RuntimeException)` rather than `catch (Exception)`, preserving the contract that `metrics.incLogin(false)` only runs for runtime failures; checked exceptions can't reach here from the `AuthClient` API.
