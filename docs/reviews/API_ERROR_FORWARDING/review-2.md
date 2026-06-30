# Code Review (Pass 2) — API_ERROR_FORWARDING

Branch: `feat/api-error-forwarding`
Reviewed commit: `ef08f25` (`fix(api-error-forwarding): address reviewer findings`)
Reviewed diff: `git show ef08f25`

Scope: **only the fix-up commit**, not the full feature diff. Prior review at `docs/reviews/API_ERROR_FORWARDING/review.md` raised 1 security + 8 smells + 2 style; this pass confirms the five items the dev addressed (4 code + 1 doc-only) and does not re-flag the deferred items.

## Verdict

**PASS**

All five fixes land correctly. No new bugs or security issues introduced by the fix-up. Two minor, non-blocking observations recorded under **Notes**.

## Fix-by-fix confirmation

### Fix 1 — sanitised 500 fallback (security)

`src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java:131-176`

`handleIllegalState` and `handleAny` both build the response body from a single private constant `INTERNAL_ERROR_MSG = "Internal server error — see server logs"` (line 67). Neither arm passes `e.getMessage()`, `e.toString()`, `e.getClass().getName()`, or `e.getClass().getSimpleName()` to the `ErrorResponse` constructor — the class simple name is only used inside the SLF4J template for the server-side log line (`log.error("Handled {} from {}: {}", e.getClass().getSimpleName(), …, e.getMessage(), e)`), which never reaches the client. Sanitisation is airtight.

`msgForStatus` (line 234) is the only other body-msg path. For status ≥ 500 it returns the same constant unconditionally; for 4xx it forwards `ex.getMessage()`, which the Javadoc correctly notes is operator-safe for Spring's own servlet exceptions (they construct fixed-shape strings like `"Request method 'GET' is not supported"`).

The new `RestExceptionHandlerTest#anyException_doesNotLeakInfraHostnameInBody` and `illegalState_doesNotLeakInfraHostnameInBody` (lines 178-207) throw exceptions whose messages contain the literal `mongo.internal:27017` and assert the response body **does not** contain `mongo.internal` *and* **does** contain `Internal server error`. The double assertion (negative + positive) rules out a vacuous pass — if a regression switched the body back to `e.getMessage()`, the `not(containsString("mongo.internal"))` assertion would fail; conversely if the body went empty, the positive `containsString("Internal server error")` would catch it. Real test.

### Fix 2 — `ResponseEntityExceptionHandler` integration (smell)

`src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java:58, 145-155, 194-209`

I checked Spring 7.0.1's `ResponseEntityExceptionHandler` source (the project's compile-time dependency). The base class declares **one** `@ExceptionHandler` arm covering 20 servlet exception types and dispatches via internal `instanceof` chain to per-type protected hooks, all of which funnel through `handleExceptionInternal`. The override here will therefore reformat the body for every base-class arm, including ones we didn't explicitly override (`HttpRequestMethodNotSupported`, `HttpMediaTypeNotSupported`, `MissingServletRequestParameter`, `NoHandlerFoundException`, etc.).

Handler-collision check:

- `HttpMessageNotReadableException`: dev correctly converted the pre-existing `@ExceptionHandler(HttpMessageNotReadableException.class)` into an `@Override` of the base-class protected hook. No collision.
- `MethodArgumentTypeMismatchException`: the base class's bundled arm lists `TypeMismatchException` (parent type) but Spring's resolver picks the most specific handler, so our explicit `@ExceptionHandler(MethodArgumentTypeMismatchException.class)` arm still wins. Bare `TypeMismatchException` (raised from other sources, e.g. data-binding) would now go through the base class — that's a strict improvement over the prior behaviour where it would have fallen into our 500 bucket.
- `IllegalStateException` / `IllegalArgumentException` / `BadRequestException` / `UpstreamGatewayException` / `ResourceNotFoundException`: none of these are in the base-class arm, so no collision. They're routed through our `@ExceptionHandler`-style methods as before.

The tests `wrongMethod_returns405WithBody`, `unsupportedMediaType_returns415WithBody`, and `missingRequiredQueryParam_returns400WithBody` (lines 213-243) cover the three most common base-class arms and assert the preserved status + reformatted body shape.

### Fix 3 — `start()` exception propagation (smell)

`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:189-289`

- `started = true` is set inside the try block at line 248, **after** all the work that can throw and **before** the catch. A partial-success path therefore cannot leave the finally with `started=true` and a non-null `failure`.
- The catch declares `catch (Throwable t)` and rethrows the **original** `t` (`throw t;` on line 256). Under Java 7+ rethrow analysis with `t` effectively final, the compiler infers the throw set from the try-block contents. All thrown types in the try are `RuntimeException` subclasses (`BadRequestException`, `ResourceNotFoundException`, `UpstreamRegistrationException`, etc.), so `start(String)` correctly stays without a `throws` clause and the typed exception still propagates unchanged to `RestExceptionHandler`. Routing-by-type is preserved.
- `failure = t` is assigned **before** the rethrow, so the finally-block log line at lines 286-287 always sees a non-null `failure` on the failure path. The `failure != null ? failure.toString() : "(unknown)"` guard is defensive (the only path where `failure` is null is the success path where `started=true` and the log isn't reached) — fine.
- The new restart test `start_failureLogCarriesCauseTypeAndMessage` (lines 437-503 in `BotGroupBehaviorServiceRestartTest`) asserts the log line contains the cause class simple name (`"RuntimeException"`), the cause message (`"auth gateway returned 503"`), and that the captured `Throwable` on the `LogEvent` is the same instance as the thrown exception. Tight and non-vacuous.

### Fix 4 — cleanup catch attaches throwable (smell)

`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:278-279`

Trivial: `cleanupEx` is now passed as the trailing SLF4J argument so the stacktrace is rendered. Confirmed.

### Fix 5 — `UpstreamGatewayException` Javadoc (doc-only)

`src/main/java/com/vingame/bot/common/exception/UpstreamGatewayException.java:3-19`

The AD-2 "distinct types per subclass" claim is gone. New wording explicitly notes both current subclasses emit `"Game server error"` and that callers should keep the same discriminator unless coordinated with the frontend. Matches reality.

## Findings

None.

## Notes

- **`NoHandlerFoundException` → `type: "Bad request"` for 404.** `RestExceptionHandler.typeForStatus` (line 216) doesn't have a `case 404` arm, so the `default` branch returns `"Bad request"` for any 4xx that isn't 400/405/406/415. In practice Spring only raises `NoHandlerFoundException` when `spring.mvc.throw-exception-if-no-handler-found=true` is set (this app doesn't set it, so the request 404s pre-advice with a bodyless response). Worth a one-liner `case 404 -> "Not found";` next time someone touches the switch, but not a finding — current behaviour matches the pre-fix behaviour and no test covers it.
- **`handleExceptionInternal` always logs at WARN.** The new override unconditionally logs at WARN regardless of status. For a base-class arm that resolves to 500 (e.g. `ConversionNotSupportedException`, `HttpMessageNotWritableException`), an operator might prefer ERROR. Not a regression — the prior code would have caught these in the terminal `handleAny` at ERROR, which is the only behavioural change here. Pre-existing in the broader "log-level discipline" deferred smell from the original review; not re-flagging.
- Build: dev reports 431/431 green; no test reports on disk to spot-check, but nothing in the diff looks suspicious enough to warrant a local `mvn test` run.

---

Review verdict: PASS
Findings: 0 bug, 0 security, 0 smell, 0 style
docs/reviews/API_ERROR_FORWARDING/review-2.md written.
