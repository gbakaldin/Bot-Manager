# QA — API_ERROR_FORWARDING

**Verdict:** PASS
**Build:** `mvn test` → 425 tests, 0 failures, 0 errors

## Scope reviewed

Dev's two commits on `feat/api-error-forwarding`:
- `f7508b5` — Phase A: typed exception hierarchy + `RestExceptionHandler` advice
- `25560a5` — Phase B: deleted try/catch ladders across all 4 controllers, retyped service throws

Pre-existing test count after Dev's work: 416/416. After QA additions: 425/425.

## Tests added / updated

- `src/test/java/com/vingame/bot/common/exception/RestExceptionHandlerTest.java`
  - `malformedBody_returns400WithCannedMessage` — covers `HttpMessageNotReadableException` mapping (the advice handler that Dev wrote but did not exercise via MockMvc).
  - `nullMessageException_returns500WithClassSimpleName` — covers the AD-6 null-message branch in the terminal `handleAny` fallback (`e.getMessage()` may be null; without this arm the body would carry `"msg":null`).
  - `notFound_hasNoBody_strict` — strengthens the bodyless-404 contract from `content().string("")` to `content().bytes(new byte[0])` so a future drift toward `{type, msg}` on 404 surfaces immediately.
  - `upstreamGateway_typePropagatesFromSubclass` — uses an anonymous subclass of the abstract `UpstreamGatewayException` to assert `getType()` propagates verbatim into the response body, not a hardcoded string in the handler.

- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceRestartTest.java`
  - `start_classifiesBadRequestExceptionAsValidationReason` — covers the new `BadRequestException → "validation"` arm in `classifyCreationFailure`. Uses a message containing `"auth"` to confirm the type-based arm wins over the message-substring fallback.
  - `start_classifiesUpstreamLoginExceptionAsAuthReason` — covers the new `UpstreamLoginException → "auth"` arm. Uses a message lacking auth/login/token keywords so the type-based arm is the only path that resolves to `"auth"`.

- `src/test/java/com/vingame/bot/domain/environment/controller/EnvironmentControllerTest.java`
  - Promoted `shouldReturnBadRequestWhenIdIsMalformed` from status-only to status + `{type, msg}` body so the test cannot pass vacuously without the advice firing.
  - Added `shouldReturnInternalServerErrorWithBody` — confirms the terminal handler produces a structured body through this controller.

- `src/test/java/com/vingame/bot/domain/game/controller/GameControllerTest.java`
  - Promoted `shouldReturnInternalServerErrorWhenServiceThrows` and `shouldReturnBadRequestWhenSaveThrowsIllegalArgument` from status-only to status + body. Without `@Import(RestExceptionHandler.class)` the body assertions would fail, locking in advice wiring for this controller.

- `src/test/java/com/vingame/bot/domain/session/controller/SessionHistoryControllerTest.java`
  - Added `AdviceIntegrationTests` nested class with two tests — one for 500-with-body and one for 400-with-body — so this controller has at least one positive test proving the advice fires for its error paths.

## Coverage of the diff

- `src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java` ← `RestExceptionHandlerTest.java` (all 7 mapping arms covered: `ResourceNotFoundException`, `BadRequestException`, `IllegalArgumentException`, `MethodArgumentTypeMismatchException`, `HttpMessageNotReadableException`, `UpstreamGatewayException` (base + leaf), `IllegalStateException`, terminal `Exception` with both populated and null messages).
- `src/main/java/com/vingame/bot/common/exception/{BadRequestException,UpstreamGatewayException,UpstreamRegistrationException,UpstreamLoginException}.java` ← `RestExceptionHandlerTest.java` (all four exercised end-to-end via the advice).
- `src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java` ← `BotGroupControllerTest.java` (8 nested groups + advice-firing assertions on `UpstreamRegistrationException`, `BadRequestException`, `UpstreamLoginException`, terminal `RuntimeException`).
- `src/main/java/com/vingame/bot/domain/environment/controller/EnvironmentController.java` ← `EnvironmentControllerTest.java` (advice-firing covered on 400, 404, 500).
- `src/main/java/com/vingame/bot/domain/game/controller/GameController.java` ← `GameControllerTest.java` (advice-firing covered on 400, 404, 500).
- `src/main/java/com/vingame/bot/domain/session/controller/SessionHistoryController.java` ← `SessionHistoryControllerTest.java` (advice-firing covered on 400, 500 after this QA pass).
- `src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java` `classifyCreationFailure` ← `BotGroupBehaviorServiceRestartTest.java` (both new arms + existing arms now have type-based positive tests).
- `src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java` `save` throw → `UpstreamRegistrationException` ← `BotGroupServiceTest.java` (existing `shouldSaveOnPartialSuccess` peer + the `UpstreamRegistrationException` assertion at line 278).
- `src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java` `authenticate` wraps library `RuntimeException` as `UpstreamLoginException`: not directly tested but its downstream classification arm is (`start_classifiesUpstreamLoginExceptionAsAuthReason`).

## Focus area findings

1. **Advice coverage**: all four typed exceptions (`BadRequestException`, `UpstreamGatewayException`, `UpstreamRegistrationException`, `UpstreamLoginException`) have positive tests for status + body. The abstract base `UpstreamGatewayException` is also tested via the new anonymous-subclass test to confirm `getType()` flows through, which protects any future leaf class (e.g. a hypothetical `UpstreamBalanceException`).

2. **Generic Spring exceptions**: the advice explicitly handles `MethodArgumentTypeMismatchException` and `HttpMessageNotReadableException` (both now exercised by tests). `MissingServletRequestParameterException` and `NoHandlerFoundException` are NOT in the advice — they fall through to Spring's default machinery, which serves a bodyless 400 (missing param) and the default `BasicErrorController` `/error` for 404-no-handler. This is consistent with AD-5 (404 stays bodyless) and acceptable for this plan — flagged here so the Releaser is aware. If consistency is desired later, add handlers and a 4th body shape; today's behavior is not a regression.

3. **Per-controller advice firing**: before this QA pass, only `BotGroupControllerTest` had multiple body-asserting tests. `GameControllerTest`, `SessionHistoryControllerTest`, and `EnvironmentControllerTest` each had at most one body assertion (the rest used `status().isBadRequest()` etc. without a body check). Those tests would have continued to pass even if `@Import(RestExceptionHandler.class)` were accidentally removed (Spring's default error handling also returns 400 for `IllegalArgumentException` via the `ResponseStatusExceptionResolver`). This QA pass adds body-asserting tests to each so the advice wiring is locked in per controller.

4. **`classifyCreationFailure` new arms**: Dev added the new arms but did NOT add tests for them. Existing tests cover `IllegalStateException → "validation"` and the message-substring `auth → "auth"` path, but neither exercises the type-based `BadRequestException` or `UpstreamLoginException` checks at the top of the method. Both arms are now tested with adversarial messages (the validation test uses a message containing `"auth"`; the auth test uses a message lacking the substring keywords) so the type-based check is the only thing that can produce the expected reason.

5. **Behavioral regression check**: no test uses `.body(any())` or comparable vacuous matchers. The tests that previously asserted empty 4xx/5xx are gone — replaced by structured-body assertions on `$.type` and `$.msg`. The QA pass strengthens this further by replacing the few remaining status-only assertions with body-asserting variants where the advice is the active path.

## Additional notes for the Releaser

- `RestExceptionHandlerTest$TestController` lives only in `src/test/` and uses `@Import` on a static inner class. This is a `@WebMvcTest` slice pattern that the Spring docs endorse but it does pull in a real `DispatcherServlet`. No production controller depends on this class.
- The `BotGroupController.save` happy-path test `shouldReturnOkWithCreatedBotGroup` does not assert any new structured-error behavior, but the dedicated 502/400/502 tests do. The 502-on-`UpstreamRegistrationException` test continues to assert the literal Vietnamese substring `Tên đăng nhập không được nhiều hơn 12 ký tự` — that's the Tip incident's smoking gun and the contract the operator-facing UI depends on.
- The `BotGroupBehaviorServiceTest.shouldThrowWhenEnvironmentIdNull` / `shouldThrowWhenGameIdNull` tests already assert the new `BadRequestException` type for the start-time validation guards — no QA additions needed there.

## Gaps

- `ApiGatewayClient.authenticate`'s wrap of the library `RuntimeException` as `UpstreamLoginException` is exercised transitively (via `classifyCreationFailure`) but lacks a direct unit test. Adding one would require mocking the websocket-parser library, which has no clean seam today. Deferring to a follow-up that touches that file directly.
- `MissingServletRequestParameterException` and `NoHandlerFoundException` mappings — see Focus #2 above. Not in scope of this plan; falls back to Spring defaults.
- Phase A's stated "MDC propagation through the advice's log.error" (advice runs on request thread with SLF4J MDC) is not asserted by any test. Low priority — MDC propagation is a Spring contract, not a Bot-Manager invariant.

## Failures

None.
