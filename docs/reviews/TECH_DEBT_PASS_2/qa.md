# QA — TECH_DEBT_PASS_2

**Verdict:** PASS
**Build:** mvn test → 1485 tests, 0 failures, 0 errors (baseline before my tests: 1478; +7 added)

Java 21 build:
`JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn test`

## Tests added / updated

- `src/test/java/com/vingame/bot/common/exception/BotManagerExceptionHierarchyTest.java` (NEW, 5 tests)
  — locks Item 4 reparenting (AD-4.1/AD-4.2): `BotManagerException` is abstract & a
  `RuntimeException`; `ResourceNotFoundException`, `BadRequestException`,
  `UpstreamGatewayException` (+ `UpstreamLoginException`/`UpstreamRegistrationException`
  leaves) all `isAssignableFrom` the base and remain unchecked; a concrete subtype is
  catchable as the base with message preserved (`instanceof`/catch regression net).
- `src/test/java/com/vingame/bot/domain/environment/controller/EnvironmentControllerTest.java`
  (UPDATED, +2 tests) — end-to-end regressions that the migrated `BadRequestException`
  (WS-header validation) maps to HTTP 400 with `{type:'Bad request', msg}` through the
  controller + `RestExceptionHandler` on both `POST /` (save) and `PATCH /{id}` (update).
  This is the typed arm, not the `IllegalArgumentException` fallback.
- `src/test/java/com/vingame/bot/domain/environment/service/EnvironmentServiceTest.java`
  (UPDATED, +1 assertion) — the null-headers save case now also asserts the thrown
  exception `isInstanceOf(BotManagerException.class)`, locking the reparenting at the
  unit level (a revert to `extends RuntimeException` fails here).

Dev-authored test edits reviewed and confirmed adequate: the three
`EnvironmentServiceTest` cases (`shouldRejectNullHeaders`, `shouldRejectMissingHost`,
`shouldRejectMissingOrigin`) now assert `BadRequestException` AND preserve both the
`verify(repository, never()).save(...)` never-save guard and the `Host`/`Origin`
message-contains checks.

## Coverage of the diff

- **Item 4 — exception hierarchy** ← `BotManagerExceptionHierarchyTest` (new base +
  reparenting), `EnvironmentServiceTest` (throw is `BadRequestException` +
  `BotManagerException`, never-save, message), `EnvironmentControllerTest` (400 end-to-end
  via save & update), `RestExceptionHandlerTest` (unchanged, all arms still green —
  crucially the `IllegalArgumentException→400` fallback at `/__test/illegal-argument` and
  the `EnvironmentController` `IllegalArgumentException→400` cases for `findById`/`delete`
  remain the regression net for the un-migrated raw throws per AD-4.6).
  - Confirmed: the migrated throw in `EnvironmentService.validateAndMergeWsHeaders` is now
    `BadRequestException` and still 400 (was 400 via `IllegalArgumentException` fallback,
    now 400 via the typed `BadRequestException` arm) — no client-visible status change.
  - Confirmed: reparenting is transparent to Spring's most-specific-handler dispatch;
    no `RestExceptionHandler` arm changed, all 20 handler tests pass unchanged.
- **Item 1 — deprecated GameMsClient/ClientFactory removal** ← compile-gated. `mvn test`
  compiles all test sources; a clean build proves no test (or prod) referenced any of the
  8 removed `GameMsClient` members or the `ClientFactory` no-arg `newClient()`. Safety here
  is compile-gated, not assertion-gated (these were pure deletions of zero-caller members).
- **Item 5 — dead `Bot.connectToSocket()` / `authenticate()` removal** ← compile-gated,
  same rationale. `BotTest`/`BotReconnectTest` and the rest of the bot suite compile and
  pass, confirming nothing referenced the deleted methods. Live reconnect/re-auth paths
  (`performReauth`, `tryReconnectWs`, `runWsReconnectLoop`, `restart`, `logout`) are
  untouched and their existing tests stay green.
- **Item 2 — Starter TODO deletion** & **Item 3 — Swagger examples** ← doc/comment-only;
  compile + existing `EnvironmentControllerTest` staying green is sufficient. Not
  over-invested per brief. (The OpenAPI JSON rendering of the `"097"` example is a
  runtime/smoke concern, left to the Releaser's staging step 2, not unit-testable here.)

## Gaps

- **Items 1 & 5 are compile-gated, not assertion-gated.** There is no positive test that
  asserts "member X no longer exists" — the guarantee is the green compile of prod + test
  sources. This is the correct and only meaningful guarantee for pure dead-code deletion;
  noted explicitly as requested.
- **Swagger example content (Item 3)** is not asserted by any unit test (no test parses
  `/v3/api-docs`). Verifying the `"097"` string appears in the rendered OpenAPI is deferred
  to the staging smoke step in the plan's Verification section (documentation-only change).
- **End-to-end WS-header 400 through a real (unmocked) `EnvironmentService`** is not
  exercised — the `EnvironmentControllerTest` is a `@WebMvcTest` with a mocked service, so
  the controller tests stub `service.save`/`update` to throw `BadRequestException` directly.
  The real `validateAndMergeWsHeaders` throw is covered at the unit level in
  `EnvironmentServiceTest`. The two layers together (unit: real throw is
  `BadRequestException`; web: `BadRequestException` → 400 body) fully pin the contract; a
  full-context integration test would be redundant and was not added.

## Failures (if any)

None. Full suite: `Tests run: 1485, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS.
