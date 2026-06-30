# API_ERROR_FORWARDING — coherent error-handling strategy across the REST API

Owner: gleb · Target: replace the per-controller `catch (Exception e) → internalServerError().build()` patchwork with a typed exception hierarchy and a single `@RestControllerAdvice` so upstream game/auth server messages reach the operator instead of being discarded.

## Goal

Surface meaningful failure information through the Bot Manager REST API. Today every controller wraps every public method in a try/catch ladder whose terminal arm returns an empty `500` (or `400`), discarding the underlying message. When the Tip auth gateway rejects 30 user registrations with `"Tên đăng nhập không được nhiều hơn 12 ký tự"`, the operator sees an empty body and has to SSH to staging and grep logs to find the actual cause. We replace the patchwork with a small typed exception hierarchy thrown from the service layer and a single `@RestControllerAdvice` that maps those types to RFC-aligned status codes with a `{type, msg}` JSON body. The seam shipped in commit `fc5dc15` (the one-off `BotGroupController.save` → `502 + ErrorResponse`) becomes the universal pattern; the existing legacy try/catch ladders disappear with the migration.

---

## Findings — Current State

### Controllers and their error shapes (verified at HEAD `f436223`)

All five controllers under `src/main/java/com/vingame/bot/**/controller/`:

| Controller | File | Error pattern |
|---|---|---|
| `BotGroupController` | `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java` | Per-method try/catch ladder. **One method** (`save`, lines 105-124) returns `502 + ErrorResponse` on `IllegalStateException` — the only non-empty error body in the codebase. Every other method returns empty `400` / `404` / `500`. |
| `EnvironmentController` | `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/environment/controller/EnvironmentController.java` | Per-method try/catch ladder. Returns empty `400` / `404` / `500`. |
| `GameController` | `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/controller/GameController.java` | Per-method try/catch ladder. Returns empty `400` / `404` / `500`. Two methods (`/types`, `/{brandCode}/{productCode}` create) have no try/catch on the read path. |
| `BrandController` | `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/brand/controller/BrandController.java` | No try/catch — one method (`/products`) returns 200 unconditionally. |
| `SessionHistoryController` | `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/session/controller/SessionHistoryController.java` | Per-method try/catch ladder. Returns empty `404` / `500`. |

**Total of catch blocks across the five controllers: 36** (`grep -c "catch (" src/main/java/com/vingame/bot/**/controller/*.java` per file: BotGroup=14, Environment=8, Game=8, Brand=0, SessionHistory=6).

### Existing common infrastructure

- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/dto/ErrorResponse.java` — `record ErrorResponse(String type, String msg)`. Just shipped in `fc5dc15`. Already public and reusable.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/ResourceNotFoundException.java` — sole existing typed exception. Subclass of `RuntimeException`. Thrown in service layer (`BotGroupService.findById:42`, `EnvironmentService`, `GameService`, `SessionHistoryService`), caught per-controller and mapped to `404`.
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/logging/BotMdc.java` — MDC keys (`BOT_GROUP_ID`, `ENVIRONMENT_ID`, `BOT_USER_NAME`). Useful for log correlation if the advice writes the exception with MDC set, but irrelevant for the response shape itself.
- **No `@ControllerAdvice` / `@RestControllerAdvice` / `@ExceptionHandler` in the codebase.** Confirmed by `grep -rn "ControllerAdvice\|ExceptionHandler\|RestControllerAdvice" src/main/java` returning 0 matches.

### Service-layer exception sources

Verified via `grep -rn "throw new IllegalStateException\|throw new IllegalArgumentException\|throw new ResourceNotFoundException\|throw new RuntimeException" src/main/java`:

| Throw site | Type today | Cause | Operator-visible info? |
|---|---|---|---|
| `BotGroupService.save:127` | `IllegalStateException` | Upstream auth gateway rejected all `count` user registrations (e.g. username too long). Message contains the upstream string. | **Yes** (already mapped to `502 + ErrorResponse` in `fc5dc15`). |
| `BotGroupService.save:130-135` (partial) | **none — log.warn only** | Some user registrations failed; bot group created anyway with bots that will silently fail login later. | **No.** This is the 18/30 incident. |
| `BotGroupBehaviorService.start:189-193, 197-201` | `IllegalStateException` | Bot group missing `environmentId` / `gameId`. Pure configuration bug, not upstream. | Yes (message has the field name). |
| `BotGroupBehaviorService.start:263` | `RuntimeException` wrap | Any failure during start (auth, env load, game load). Wraps cause as `getMessage()`. | Partial — the wrapped message survives but the type is lost. |
| `BotGroupBehaviorService.scheduleRestart:445` | `IllegalArgumentException` | Scheduled time in the past. Pure client validation. | Yes. |
| `BotGroupBehaviorService.stop:415` | `RuntimeException` wrap | Cleanup failure during stop. | Partial. |
| `ApiGatewayClient.authenticate:148-153` | rethrows `RuntimeException` from the `websocket-parser` library | Login failed against gateway (e.g. `Tài khoản không tồn tại` → "No data in response" string). The library wraps the upstream `{status, code, message}` envelope into a vague `RuntimeException("No data in response")`. | **No.** The library's message is misleading; the actual gateway envelope is in the HTTP response body that the library logged but didn't expose. |
| `ApiGatewayClient.registerSingleUser:358` | `RuntimeException` | Upstream registration envelope said `ERROR`. The `IllegalStateException` thrown by `BotGroupService.save:127` is the aggregation of these. | Yes (carried up via `registrationResult.getErrors()`). |
| `ApiGatewayClient.setDisplayName:416` | `RuntimeException` | Display name set failed. | Partial. |
| `ApiGatewayClient.getBalance:496` | `RuntimeException` wrap | Balance fetch failed. Called from runtime, not API. | N/A — runtime path, never reaches a controller. |
| `GameMsClient.*` (5 sites) | `RuntimeException` wrap | All called from runtime / scheduled tasks. | N/A — runtime path. |
| `ResourceNotFoundException` throws | `ResourceNotFoundException` | `findById` returns Optional empty. | Yes — already returns `404`. |
| `IllegalArgumentException` throws (e.g. `ProductCode.fromCode:43`, `AuthStrategyFactory:21`, `GameMessageTypesResolver:23,32`, `BotFactory:114`) | `IllegalArgumentException` | Mostly enum parsing / config validation. | Yes (message specific). |

**Upstream-error envelope structure** (from `UserRegistrationResponse.java`):
```
{ "status": "ERROR", "code": 400, "message": "Username already exists" }
```
`UserRegistrationResponse.isSuccess()` checks `"OK".equalsIgnoreCase(status) || code == 200`. The `message` is already extracted by `ApiGatewayClient.registerSingleUser:354-358` and bundled into `UserRegistrationResult.errors`.

### Login-side gap

`ApiGatewayClient.authenticate:121-154` calls into the `websocket-parser` library which throws bare `RuntimeException("No data in response")` when the upstream auth gateway responds with a non-empty `{status, code, message}` error envelope. The library logs the upstream JSON to its own log file but does not surface it in the exception. **This is the 12-bots-silent-login-fail half of the 2026-06-09 incident** and is **partially out of scope** (we cannot fix the library here) but in scope to the extent that the bot-side `authenticate` call could parse the library's exception message and re-throw a typed `UpstreamLoginException` with whatever the library *did* expose. See AD-7 for the decision.

### Test fixtures

- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/botgroup/controller/BotGroupControllerTest.java:333-361` — the one shipped MockMvc test that asserts `502 + {type:"Game server error", msg:contains("Tên đăng nhập không được nhiều hơn 12 ký tự")}`. Pattern to clone for the rest.
- Other `*ControllerTest.java` files (Environment, Game, SessionHistory, Brand) use `@WebMvcTest` + `MockitoBean` for the service layer. The advice will be picked up automatically by `@WebMvcTest` since it is in the same `ApplicationContext` slice (Spring Boot auto-detects `@RestControllerAdvice` beans).

### Frontend / UI consumption

The UI is in a separate repo (`app/` is untracked workspace junk). For the purposes of this plan: every error path that the UI currently sees from the API is **either an empty body** (legacy `.build()`) **or already the new `{type, msg}` body** (the one `BotGroupController.save` 502 path). There is no legacy body shape to preserve except `404` which has always been bodyless. **Backward-compat constraint:** `404` responses MUST stay bodyless (or carry the new `{type, msg}` envelope — UI must tolerate both). All other non-2xx responses gain the `{type, msg}` body. The `200` happy-path bodies are unchanged.

---

## Per-aspect readiness / mapping

| Aspect | Status | Notes |
|---|---|---|
| `ErrorResponse(type, msg)` record exists and is reusable | **ready** | Shipped in `fc5dc15`. No change. |
| `ResourceNotFoundException` exists | **ready** | Stays; advice maps to `404`. |
| `@RestControllerAdvice` global handler | **not started** | Phase A creates it. No `@ControllerAdvice` anywhere in src. |
| Typed exception hierarchy (`UpstreamGatewayException`, `UpstreamRegistrationException`, `UpstreamLoginException`, `BadRequestException`) | **not started** | Phase A creates it. Backlog already mentions "Add clearer Exception system with proper hierarchy". |
| `BotGroupService.save:127` throws typed `UpstreamRegistrationException` instead of `IllegalStateException` | **ready** | One-line change in Phase A. The `502 + ErrorResponse` shape stays via the advice instead of the controller's per-method catch. |
| `BotGroupController.save` legacy catch ladder removed | **partial** | Currently the only controller method using the new pattern in-band. Phase B removes its try/catch (advice covers it). |
| Remaining BotGroup controller methods migrated | **partial** | Phase B. 5 catch ladders to delete. |
| `EnvironmentController` migrated | **not started** | Phase B. 6 catch ladders. |
| `GameController` migrated | **not started** | Phase B. 6 catch ladders. |
| `SessionHistoryController` migrated | **not started** | Phase B. 5 catch ladders. |
| `BrandController` migrated | **n/a** | Has no catch ladders today; nothing to remove. Advice still covers it if a future endpoint throws. |
| `BotGroupService.save` partial-registration handling | **out of scope this plan** | Deferred to follow-up (see Open Items). The current behaviour (log.warn + proceed) is preserved — Phase A does NOT change it. Phase C is a placeholder plan call-out; if and when prioritised, it gets its own dedicated plan. |
| `ApiGatewayClient.authenticate` typed exception (`UpstreamLoginException`) | **partial** | Phase B re-throws the library's `RuntimeException` as `UpstreamLoginException` with the library's message preserved. Best-effort given the library is opaque. |
| Test coverage for advice mapping | **not started** | Phase A ships a dedicated `RestExceptionHandlerTest` (uses a tiny test controller); per-controller tests in Phase B cover the migrated endpoints. |

---

## Architecture Decisions

### AD-1. Mapping lives in `@RestControllerAdvice`, not in per-controller try/catch

Single source of truth. Controllers contain no try/catch; they call services and return `ResponseEntity.ok(...)`. The advice owns the entire exception → HTTP status + body mapping table. Trade-off accepted: a one-off, endpoint-specific error response (which doesn't exist today and is unlikely to be needed) would require either a controller-local catch (still legal — advice runs last) or a new typed exception in the hierarchy. The latter is the encouraged path.

Why not "both": dual code paths invite drift — the only reason there's a per-controller catch today is historical, not principled. The shipped `fc5dc15` BotGroup 502 catch becomes redundant once the advice is wired and is deleted in Phase B step 1.

### AD-2. Typed exception hierarchy — four new types under `com.vingame.bot.common.exception`

| Exception | Maps to | Semantics | When thrown |
|---|---|---|---|
| `ResourceNotFoundException` *(existing — unchanged)* | `404 Not Found` (body or empty — see AD-5) | Requested resource does not exist | `service.findById` Optional-empty |
| `BadRequestException` *(new)* | `400 Bad Request` + `{type, msg}` | Client-side input invalid; not a system bug | Future: input validation. **For now, only used by the advice to wrap `MethodArgumentTypeMismatchException` etc. (see AD-6).** Service code still throws stock `IllegalArgumentException` for cases like "scheduled time in the past" — the advice maps `IllegalArgumentException` to `400` too (AD-6). |
| `UpstreamGatewayException` *(new, abstract base)* | `502 Bad Gateway` + `{type, msg}` | Generic "an upstream system failed" | Direct throw rare; subclasses preferred |
| `UpstreamRegistrationException extends UpstreamGatewayException` *(new)* | `502 Bad Gateway` + `{type:"Game server error", msg:<...>}` | Auth gateway rejected user registration (complete failure case from `BotGroupService.save`) | `BotGroupService.save:127` (replacing today's `IllegalStateException`) |
| `UpstreamLoginException extends UpstreamGatewayException` *(new)* | `502 Bad Gateway` + `{type:"Game server error", msg:<...>}` | Auth gateway rejected login (the 12-bot silent-fail case) | `ApiGatewayClient.authenticate` — wraps the library's bare `RuntimeException` with its message preserved |

All four new classes extend `RuntimeException`. No checked exceptions; matches existing codebase conventions.

**Why type the gateway failures distinctly:** they all map to `502` today, but typing them lets a future panel/log query filter "registration failed" vs "login failed" without grep-ing the `msg` string. Type names show up in stacktraces too.

**`BotGroupBehaviorService.start:189-193` ("BotGroup has no environmentId set") and `:197-201` ("has no gameId set"):** these are pre-flight validation failures, not upstream failures. They become `BadRequestException` (or stay as `IllegalStateException` and let the advice map them; see AD-6). Decision: convert to `BadRequestException` in Phase B step 3 so the type carries the semantic.

### AD-3. `502 Bad Gateway`, not `422 Unprocessable Entity`, for upstream failures

`502` says "I depended on an upstream system and that system gave me a bad answer." `422` says "the request you sent is syntactically valid but semantically invalid." For the Tip incident, the request *was* semantically valid from the operator's perspective — they didn't know the auth gateway has a 12-char username cap. From the Bot Manager's perspective, the failure is "the auth gateway said no." That is the textbook `502` case.

Reserve `422` for a future case where Bot Manager has its own server-side validation rule that the request violates (e.g. "bot count > max"); none today.

### AD-4. Response body envelope is `{type, msg}` for every non-2xx with a body

Single shape. `ErrorResponse(String type, String msg)` is the only error body. Multi-line / structured errors (e.g. partial registration with N error strings) get a single `\n`-joined `msg`. Reasons:
1. The shipped `fc5dc15` shape is `{type, msg}` and the UI can already render it.
2. RFC 7807 `application/problem+json` is the alternative but adds `title` / `status` / `detail` / `instance` for marginal benefit; UI doesn't consume them.
3. Keeping the shape narrow reduces serialisation surface — `record ErrorResponse(String type, String msg)` deserialises trivially in any client.

If we ever need structured error context (field-level validation errors, lists of upstream rows), extend with a third optional field `Map<String, Object> details` rather than introducing a parallel envelope.

### AD-5. `404 Not Found` keeps its bodyless contract for `ResourceNotFoundException`

Per the brief: `404` "is a working contract; don't accidentally rewrite that one's body." The advice handler for `ResourceNotFoundException` returns `ResponseEntity.notFound().build()` — no body. Every other typed exception returns a body.

**Rationale:** the resource genuinely doesn't exist; there's nothing to forward. The UI today branches on `.status === 404` without reading the body and that pattern stays valid.

(If the user later decides 404 should carry `{type:"Not found", msg:"BotGroup 'abc' not found"}` for consistency, that's a one-line change to the advice handler. Not done now.)

### AD-6. Stock JDK exceptions get mapped too — don't require migration of every throw site

Service code throws stock `IllegalArgumentException` in many places (`GameMessageTypesResolver:23,32`, `ProductCode:43`, `BotGroupBehaviorService.scheduleRestart:445`, etc.). Migrating every throw site to `BadRequestException` is a tedious change with weak benefit. Instead the advice maps:

| Exception class caught | Status | Body |
|---|---|---|
| `ResourceNotFoundException` | `404` | none (AD-5) |
| `BadRequestException` | `400` | `{type:"Bad request", msg:e.getMessage()}` |
| `IllegalArgumentException` | `400` | `{type:"Bad request", msg:e.getMessage()}` |
| `UpstreamGatewayException` (incl. subclasses) | `502` | `{type:<from-subclass>, msg:e.getMessage()}` — `getMessage()` may be multi-line |
| `IllegalStateException` | `500` | `{type:"Internal error", msg:e.getMessage()}` — temporary catch-all until all `IllegalStateException` callsites are reviewed; see AD-8 |
| Spring `MethodArgumentTypeMismatchException` | `400` | `{type:"Bad request", msg:"Invalid value for parameter '" + e.getName() + "': '" + e.getValue() + "'"}` |
| Spring `HttpMessageNotReadableException` | `400` | `{type:"Bad request", msg:"Malformed request body"}` |
| `Exception` (terminal fallback) | `500` | `{type:"Internal error", msg:e.getMessage()}` |

**Each handler `log.error(e.getMessage(), e)` before returning** so the stacktrace reaches the log even when the message is short. MDC propagation is automatic — the advice runs on the request thread which already has SLF4J MDC populated.

### AD-7. Login-side typed exception is best-effort given the opaque library

`ApiGatewayClient.authenticate` re-throws the library's bare `RuntimeException` as `UpstreamLoginException(e.getMessage(), e)`. The library's message (`"No data in response"`) is preserved verbatim — it's misleading but it's all we have without parsing the library's log output. The Bot Manager log already shows the raw HTTP response body courtesy of the library (`[Login] response: ...` line at `:144`), so an operator who *does* SSH can find the upstream `{status, code, message}` there.

**This is explicitly a half-measure.** The full fix is to teach the websocket-parser library to throw a typed exception carrying the upstream envelope. That's the "Don't plan changes to the websocket-parser library" out-of-scope clause from the brief. We type the bot-side exception so a future library upgrade can populate `msg` properly without touching the controller layer.

### AD-8. `IllegalStateException` is a transitional 500 mapping — not a permanent one

The advice maps unmapped `IllegalStateException` to `500 Internal Server Error` with a body. This is intentionally lenient: most `IllegalStateException` in the codebase today are programming errors (`ApiGatewayClient:111` "not initialized", `ClientFactory:60` "TokensProvider must be set"). A `500` with the message reaches the operator who can file a bug. Once Phase B re-throws the meaningful `IllegalStateException` cases as typed (`BotGroupService.save` → `UpstreamRegistrationException`, `BotGroupBehaviorService.start` config-validation → `BadRequestException`), the remaining `IllegalStateException` throws should genuinely be "this shouldn't happen" bugs that deserve `500`.

### AD-9. Partial-registration semantics are NOT changed in this plan

The 18/30 incident — where `BotGroupService.save` log-warns and proceeds when some user registrations fail — is **out of scope for this plan**. Reasons:
1. The user explicitly hasn't decided whether partial success should be `200`, `207 Multi-Status`, or a hard fail. Picking now risks rework.
2. The fix entails a behavioural change (potentially refusing to create the group, or returning a body that the UI must learn to render) that is bigger than the error-forwarding redesign.
3. The error-forwarding redesign delivers value on its own — the complete-failure case already covers most of the operator's pain (the 30/30 incident).

The partial-success path stays at "log.warn + proceed + 200" exactly as it is today. See Open Items for the follow-up.

### AD-10. Tests required: per-handler unit test + per-migrated-controller MockMvc test

- **`RestExceptionHandlerTest`** (new) — `@WebMvcTest` with a tiny `@RestController` stub that throws each typed exception on demand; asserts the advice returns the expected status + body. One file, ~7 test methods.
- **Existing `*ControllerTest`** files — update to mock the new typed exceptions where applicable (e.g. `BotGroupControllerTest.shouldReturnBadGatewayWhenRegistrationFails` keeps working unchanged; the assertion is on `502 + {type:"Game server error", msg:contains(...)}` and the advice produces exactly that). New per-controller tests added in Phase B verify the legacy catch ladder removal hasn't regressed any contract — at minimum one test per controller asserting `404` for `ResourceNotFoundException` (bodyless) and one asserting `400` for `IllegalArgumentException` with the new body.
- **No arch test / linter** for "every controller must not have try/catch." Convention + code review is sufficient; the migration is small (4 controllers, 25 catch ladders) and the advice's presence makes the per-controller catch obviously redundant.

---

## Plan

Two phases that compile and pass tests independently. Phase A introduces the advice and one exemplar migration; Phase B migrates the remaining controllers and updates the upstream-throw sites.

A potential Phase C (partial-registration handling) is **deferred** — see Open Items.

### Phase A — Introduce exception hierarchy + `@RestControllerAdvice`; migrate one exemplar (BotGroupController.save throw site only)

**Goal:** ship the advice infrastructure with one production throw site re-typed, so the advice is exercised in production end-to-end and the new shipped pattern (`fc5dc15`) becomes redundant in the controller (but is **not removed yet** — that's Phase B). Old controller catch ladders all continue to function untouched; the advice is a safety net that catches anything they let through. Independence-of-phase property holds.

**Steps:**

1. Create the exception classes under `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/`:
   - `BadRequestException.java` — `public class BadRequestException extends RuntimeException { public BadRequestException(String message) { super(message); } }`.
   - `UpstreamGatewayException.java` — `public abstract class UpstreamGatewayException extends RuntimeException { protected UpstreamGatewayException(String type, String message) { super(message); this.type = type; } public final String getType() { ... } }`. Carries the `type` string that the advice will copy into the `ErrorResponse`. Abstract — direct throws disallowed; force callers to pick a subclass.
   - `UpstreamRegistrationException.java` — `extends UpstreamGatewayException`. Constructor `(String message)` calls `super("Game server error", message)` — `type` matches what `fc5dc15` already sends.
   - `UpstreamLoginException.java` — `extends UpstreamGatewayException`. Constructor `(String message, Throwable cause)` calls `super("Game server error", message)` then `initCause(cause)`. Same `type` string as Registration; UI doesn't distinguish.

2. Create `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java`:
   - `@RestControllerAdvice`, `@Slf4j`.
   - Method per row of the AD-6 table. Each method `@ExceptionHandler(<class>.class)`, returns `ResponseEntity<?>`. Each method `log.error("Handled {} from {}: {}", e.getClass().getSimpleName(), request.getRequestURI(), e.getMessage(), e);` (inject `HttpServletRequest` as method parameter).
   - `ResourceNotFoundException` → `ResponseEntity.notFound().build()` (bodyless, AD-5).
   - `UpstreamGatewayException` (covers both subclasses) → `ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getType(), e.getMessage()))`.
   - `BadRequestException` / `IllegalArgumentException` / `MethodArgumentTypeMismatchException` / `HttpMessageNotReadableException` → `ResponseEntity.badRequest().body(new ErrorResponse("Bad request", <msg>))`.
   - `IllegalStateException` → `ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("Internal error", e.getMessage()))`. Comment that this is transitional (AD-8).
   - `Exception` terminal → same as `IllegalStateException` but type `"Internal error"`, msg `e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()` (some exceptions have null messages).

3. Modify `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java:120-128`. Replace the `IllegalStateException` throw with `UpstreamRegistrationException`:
   ```java
   if (registrationResult.isCompleteFailure()) {
       String errorMsg = String.format(
           "Failed to register any users for bot group '%s'. Errors: %s",
           botGroup.getName(),
           String.join("; ", registrationResult.getErrors())
       );
       log.error(errorMsg);
       throw new UpstreamRegistrationException(errorMsg);
   }
   ```
   The advice maps this to `502 + {type:"Game server error", msg:<errorMsg>}` — bit-for-bit identical to what `fc5dc15`'s controller catch produces today. **No controller change yet** — the per-method catch in `BotGroupController.save` continues to handle the old `IllegalStateException` type (now unused) which is harmless dead code until Phase B step 1.

   Update the Javadoc on `BotGroupService.save` (line 96 area): `@throws UpstreamRegistrationException` instead of `IllegalStateException`.

4. **Tests:**
   - **New file** `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/common/exception/RestExceptionHandlerTest.java`. Pattern: `@WebMvcTest(controllers = TestController.class)` with a `@Configuration` static inner class importing `RestExceptionHandler`, plus a tiny test-only `@RestController` exposing one endpoint per exception type. Asserts each mapping table row from AD-6. ~7 test methods. **Note:** Spring Boot's `@WebMvcTest` does NOT auto-detect arbitrary `@RestControllerAdvice` beans without it being inside a `@Component`-scanned package — confirm by either annotating with `@Import(RestExceptionHandler.class)` on the test class or by trusting the auto-detection (the advice is in `com.vingame.bot.common.exception` which is under the `com.vingame.bot` base package, so component scan picks it up by default). Default to `@Import(RestExceptionHandler.class)` for safety; remove if test passes without it.
   - Update `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupServiceTest.java` — the test that asserts `IllegalStateException` is thrown on `isCompleteFailure() == true` becomes asserting `UpstreamRegistrationException`.
   - **No change** to `BotGroupControllerTest.shouldReturnBadGatewayWhenRegistrationFails` — it stubs `service.save` to throw `IllegalStateException` today; that test still passes because the controller's old `catch (IllegalStateException e)` block still produces the same `502 + ErrorResponse`. Phase B step 5 updates the stub to throw `UpstreamRegistrationException` directly.

**Verification at end of Phase A:**

```bash
# Build + tests green
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
  mvn -q clean test

# New files exist
ls /Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/
# Expect: BadRequestException.java  RestExceptionHandler.java  ResourceNotFoundException.java  UpstreamGatewayException.java  UpstreamLoginException.java  UpstreamRegistrationException.java

# Advice is wired
grep -rn "@RestControllerAdvice" /Users/gleb/IdeaProjects/Bot/src/main/java
# Expect: 1 match in RestExceptionHandler.java

# BotGroupService.save throws the typed exception
grep -n "throw new UpstreamRegistrationException\|throw new IllegalStateException" /Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java
# Expect: 1 line for UpstreamRegistrationException; the prior IllegalStateException throw at :127 is gone.

# Legacy controller catch ladder still in place (not yet removed)
grep -c "catch (" /Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java
# Expect: 14 (unchanged from HEAD)
```

**Independence:** depends on nothing. Unblocks Phase B.

---

### Phase B — Migrate all controllers (remove catch ladders); type the remaining throw sites

**Goal:** delete the per-controller try/catch infrastructure now that the advice covers it; convert remaining valuable `IllegalStateException` throws to typed exceptions; add `UpstreamLoginException` wrapping at the ApiGatewayClient layer.

**Steps:**

1. **`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java`** — delete every try/catch wrapping a service call. Each method shrinks to the `return ResponseEntity.ok(...)` line. Examples:

   `findById` (line 50-63) becomes:
   ```java
   @GetMapping("/{id}")
   public ResponseEntity<BotGroupDTO> findById(@PathVariable ... String id) {
       BotGroup botGroup = service.findById(id);
       return ResponseEntity.ok(mapper.toDTO(botGroup));
   }
   ```
   `save` (line 105-124) becomes:
   ```java
   @PostMapping("/")
   public ResponseEntity<BotGroupDTO> save(@RequestBody BotGroupDTO botGroupDTO) {
       BotGroup botGroup = mapper.toEntity(botGroupDTO);
       boolean skipRegistration = Boolean.TRUE.equals(botGroupDTO.getExistingGroup());
       BotGroup saved = service.save(botGroup, skipRegistration);
       return ResponseEntity.ok(mapper.toDTO(saved));
   }
   ```
   (Notice the return type tightens from `ResponseEntity<?>` back to `ResponseEntity<BotGroupDTO>` — error bodies travel through the advice, not through the controller's return type.)

   Remove the now-unused imports: `HttpStatus`, `ErrorResponse` (still referenced by the test fixture — keep there).

2. **`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/environment/controller/EnvironmentController.java`** — same treatment. 8 try/catch blocks removed. `enrichWithBotGroupStats` is unchanged (no exceptions thrown).

3. **`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/controller/GameController.java`** — same. 8 try/catch blocks removed.

4. **`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/session/controller/SessionHistoryController.java`** — same. 6 try/catch blocks removed.

5. **`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/brand/controller/BrandController.java`** — already clean; no edit.

6. **`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java`** — typed-exception cleanup for the throw sites where it matters:
   - Line 190-193 (`environmentId == null` validation): replace `IllegalStateException` with `BadRequestException`. Same for line 197-201 (`gameId == null`).
   - Line 263 (the `RuntimeException` wrap inside the outer try/catch of `start`): leave alone. This is a runtime failure during start (e.g. auth gateway dropped mid-creation); the underlying cause may already be a typed exception, and double-wrapping it would lose the type. **Refactor:** delete the outer try/catch in `start`, let the underlying exception propagate to the advice. The cleanup block (`runningGroups.remove(id)` + `stopAllBots`) moves into a `try/finally` on the outer body, with the cleanup running on every exception path but not catching anything:
     ```java
     boolean started = false;
     try {
         // ... start logic ...
         started = true;
     } finally {
         if (!started) {
             BotGroupRuntime failedRuntime = runningGroups.remove(id);
             if (failedRuntime != null) { ... } // existing cleanup
         }
     }
     ```
   - Line 415 (`RuntimeException` wrap in `stop`): same treatment — convert to `try/finally` or leave the cleanup inline; the exception propagates raw to the advice.

7. **`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java`** — wrap the library exception in `authenticate` (lines 142-153):
   ```java
   try {
       TokensProvider tokens = new AuthClient(ctx, loginRequestFactory).authenticate();
       log.info("[Login] response: agencyToken={} | authToken={} | jwtToken={}",
               tokens.getAgencyToken(), tokens.getAuthToken(), tokens.getJwtToken());
       metrics.incLogin(true);
       return tokens;
   } catch (RuntimeException e) {
       metrics.incLogin(false);
       throw new UpstreamLoginException(
               "Login failed for user '" + credentials.getUsername() + "': " + e.getMessage(),
               e);
   }
   ```
   This call is reached from `Bot.initialize:133` which is called from `BotFactory.createBot:125` which is called from `BotGroupBehaviorService.createSingleBot:374`, all on the bot-creation virtual thread. The exception bubbles up through `createBotsInParallel`'s per-future `.join()` at line 313, which currently catches and logs (line 315-318) so the start *succeeds* with fewer bots — **that's the 12-bot silent-fail incident.** Two options:
   - **(a) Surface to caller** — change `createBotsInParallel` to throw `UpstreamLoginException` aggregating failures, propagated to `start`, propagated to the advice, returned as `502`. Hard fail = bot group not created.
   - **(b) Leave as warning** — keep the catch-and-log, the exception is typed but never reaches the controller. Login failures continue to silently reduce the bot count.

   **Decision: (b) for this plan.** Reasoning: option (a) is the same shape as the partial-registration decision (deferred — AD-9 / Open Items). Aligning the two means making one architectural decision later for both. For now `UpstreamLoginException` exists, the per-bot start path logs it (typed instead of bare `RuntimeException`), and if a future plan decides start should hard-fail on N login failures, it converts the warning into a `throw new UpstreamLoginException("...N bots failed login: ...")` at `createBotsInParallel:322` and the advice handles it.

8. **Tests:**
   - Each migrated `*ControllerTest` is updated:
     - Remove the now-obsolete tests that asserted *empty* `400` / `500` bodies (they would fail under the advice because the body is now `{type, msg}`). Examples in `BotGroupControllerTest`: `shouldReturnInternalServerErrorWhenStartFails` (line 482-492), `shouldReturnBadRequestWhenIllegalArgument` (the various copies). Either delete or rewrite to assert the new `{type, msg}` body.
     - Add at minimum one test per migrated controller asserting the new envelope on a typed exception (e.g. `EnvironmentControllerTest.shouldReturn404WithBodylessResponseWhenNotFound` and `…shouldReturn400WithBodyWhenIllegalArgument`).
   - `BotGroupControllerTest.shouldReturnBadGatewayWhenRegistrationFails` (line 332-361) is updated: the stub `when(service.save(...)).thenThrow(new IllegalStateException("..."))` becomes `…thenThrow(new UpstreamRegistrationException("..."))`. Assertions unchanged.
   - New `BotGroupBehaviorServiceTest` cases: throwing `BadRequestException` on missing `environmentId` / `gameId`. (Existing tests asserting `IllegalStateException` are updated to assert `BadRequestException`.)
   - Add a single integration-shaped test in `BotGroupControllerTest` that throws an `UpstreamLoginException` from a stubbed `behaviorService.start` and asserts `502 + {type:"Game server error", msg:contains("Login failed")}`.

9. **Documentation:** strike "Add clearer Exception system with proper hierarchy" from the Code Quality backlog in `/Users/gleb/IdeaProjects/Bot/CLAUDE.md`. **Do not edit CLAUDE.md as part of this plan execution** — defer to a follow-up doc-housekeeping commit. (The plan itself doesn't edit CLAUDE.md; the implementer can do so at PR time.)

**Verification at end of Phase B:**

```bash
# Build + tests green
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
  mvn -q clean test

# Controllers have no catch blocks left (except possibly Brand which had none)
for f in src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java \
         src/main/java/com/vingame/bot/domain/environment/controller/EnvironmentController.java \
         src/main/java/com/vingame/bot/domain/game/controller/GameController.java \
         src/main/java/com/vingame/bot/domain/session/controller/SessionHistoryController.java; do
  echo "$f: $(grep -c 'catch (' $f) catch blocks"
done
# Expect: all four print "0 catch blocks"

# Typed exceptions thrown from the service layer
grep -rn "UpstreamRegistrationException\|UpstreamLoginException\|BadRequestException" /Users/gleb/IdeaProjects/Bot/src/main/java
# Expect (at minimum):
#   common/exception/{UpstreamRegistrationException,UpstreamLoginException,BadRequestException}.java (definitions)
#   domain/botgroup/service/BotGroupService.java: throws UpstreamRegistrationException
#   domain/botgroup/service/BotGroupBehaviorService.java: throws BadRequestException (x2 for env/game id)
#   infrastructure/client/ApiGatewayClient.java: throws UpstreamLoginException (authenticate)

# No legacy IllegalStateException for upstream-failure intent
grep -n "throw new IllegalStateException" /Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java
# Expect: 0 matches (was 1 at line 127)
```

**Independence:** depends on Phase A. Independent of any deferred Phase C.

---

## Implementation Notes / Concerns

1. **`@WebMvcTest` advice picking up.** Spring Boot's `@WebMvcTest` slice by default includes `@ControllerAdvice` beans IF they're in the component-scan path. Our advice lives in `com.vingame.bot.common.exception` which is under the `com.vingame.bot` base package — auto-detection should work. If a test fails because the advice isn't picking up, add `@Import(RestExceptionHandler.class)` to the test class. **Do not skip this verification** — silently bypassing the advice in tests would let production errors look different from test errors.

2. **Exception ordering in `@RestControllerAdvice`.** Spring picks the most specific `@ExceptionHandler` for a given thrown type. `UpstreamRegistrationException extends UpstreamGatewayException extends RuntimeException extends Exception`. A handler on `UpstreamGatewayException` catches both subclasses; we don't need separate handlers per subclass. The advice's `type` value comes from the subclass via `getType()`. Verified the abstract base carries the type field, not the leaf — so this works.

3. **`HttpServletRequest` parameter on advice methods.** Spring injects this automatically — useful for `log.error("Handled {} from {}: {}", ...)`. Don't try to inject `WebRequest` (different abstraction); use the servlet request.

4. **`record ErrorResponse` and `null` fields.** Records always serialise all fields. If `e.getMessage()` is `null` (some `RuntimeException`s have no message), the JSON will be `{"type":"...", "msg":null}`. Frontend should tolerate this. The terminal `Exception` handler falls back to `e.getClass().getSimpleName()` to avoid the null.

5. **Logging behaviour change.** Today, each per-controller `catch (Exception e)` doesn't log (just returns 500). After Phase B, the advice's terminal handler logs every uncaught exception. This is a desirable change — it surfaces the same data the operator currently has to grep for — but it does increase log volume on broken endpoints. No `application.properties` change needed.

6. **The `BotGroupBehaviorService.start` cleanup refactor (Phase B step 6) is the only non-trivial code change.** Re-read it carefully: the existing outer `try` catches `Exception` and does cleanup + rewrap. Converting to `try/finally` with a `started` boolean preserves cleanup behaviour but lets the original exception propagate untouched. The advice then maps it (`UpstreamLoginException` → 502, `BadRequestException` → 400, anything else → 500). Confirm in tests that the cleanup still fires on every failure path (a new test asserting `runningGroups.remove(id)` called when start throws would be defensive).

7. **`Bot.initialize` callers that catch `Exception`.** `createBotsInParallel:312-319` catches per-future. After Phase B step 7, the exception types reaching that catch are `UpstreamLoginException` (typed) plus whatever else. The catch is unchanged (still logs and continues), but per the decision in step 7, the typed exception is logged with its type name visible in the stacktrace — already an improvement.

8. **MDC behaviour through the advice.** The request thread has SLF4J MDC populated by upstream Spring filters (request id, etc.); the advice's `log.error` inherits this. Bot-side MDC (`BotMdc.BOT_USER_NAME`, etc.) is only set inside virtual threads that aren't the request thread — those don't propagate. Acceptable.

9. **`BotGroupController.save` Javadoc / Operation description** (line 100-103) currently says "Returns the freshly created bot group..." — after Phase B, document the `502` outcome too:
   ```
   @ApiResponse(responseCode = "502", description = "Upstream auth gateway rejected user registration; body: {type, msg}")
   ```
   The other controllers get similar updates. **OpenAPI annotation churn is real** — 4 controllers × 5 endpoints × 2 new statuses = ~40 annotations. Defer most to taste / leave them out and let the advice's behaviour be documented in a shared `## Errors` page in `README.md` (not part of this plan).

10. **Backward compatibility checklist before deploy:** the UI currently expects empty bodies on all non-201 / non-200 responses. After Phase B, every non-2xx response (except `404`) carries `{type, msg}`. Any UI code that does `if (response.body)` on the error path will now see a body. Confirm with the UI team before Phase B ships, OR ship Phase A only (which leaves controller catches in place, so behavior is bit-identical to today; only the `502 + ErrorResponse` from `save` is in play — already shipped in `fc5dc15`, UI already tolerates it).

---

## Open Items

1. **Partial-registration semantics — what should `POST /api/v1/bot-group/` return when N of M user registrations fail?** Options:
   - `200 + {warnings: [...]}` — current behaviour, plus structured warnings. Requires extending `ErrorResponse` or introducing a new success-with-warnings envelope.
   - `207 Multi-Status` — RFC-compliant for partial-success. UI must learn a new status.
   - `502 + ErrorResponse` — treat partial as failure; refuse to create the group. Cleanest semantics; requires `BotGroupService.save` to roll back the partial Mongo write (currently writes the group regardless).
   - User has not committed. **Deferred to a dedicated plan (`PARTIAL_REGISTRATION_HANDLING.md`).** Out of scope here.

2. **Login-side hard-fail vs soft-warn during `createBotsInParallel`.** Currently soft-warn (logs, continues, group started with fewer bots). Tied to (1) — same kind of "do we surface this as a user error or let it through?" question. **Deferred.**

3. **`@ApiResponse` OpenAPI annotation pass.** Plan does not require updating every endpoint's `@ApiResponse` to document the new `502` / `400` body shapes. Leaving as a low-priority follow-up. Swagger UI today shows only the 200 response on most endpoints.

4. **`websocket-parser` library typed exceptions.** Out of scope (explicit). When the library learns to throw a typed exception with the upstream `{status, code, message}` payload, the `UpstreamLoginException` constructor can populate `msg` from those fields instead of the library's misleading "No data in response" string. Track separately.

5. **Pre-flight username-length validation (`Tip ≤ 12 chars`).** Out of scope (explicit, already on CLAUDE.md backlog). The current plan ensures the operator *sees* the gateway's error; the follow-up prevents the API from sending requests known to fail.

---

## Verification

End-to-end checks for the Releaser to run on staging after Phase B deploys (Phase A only changes service-layer types and adds an advice; the only operator-visible change before Phase B is that the `BotGroupController.save` 502 path goes through the advice instead of the controller catch, with bit-identical output — no separate verification needed for Phase A beyond `mvn test` green).

All commands assume a fresh staging deploy and a clean MongoDB. Use the existing `env-1` environment from staging seed data; substitute its real ID if different.

### Step 1 — `ResourceNotFoundException` still maps to bodyless `404`

```bash
curl -i -s -X GET http://staging:8080/api/v1/bot-group/this-id-does-not-exist
```
**Expect:** `HTTP/1.1 404 Not Found`, `Content-Length: 0` (or absent body), no JSON body.

### Step 2 — `IllegalArgumentException` (now `BadRequestException`) maps to `400` with body

```bash
# Scheduling a restart in the past
curl -i -s -X POST -H 'Content-Type: application/json' \
  -d '"2020-01-01T00:00:00"' \
  http://staging:8080/api/v1/bot-group/some-running-group-id/schedule-restart
```
**Expect:** `HTTP/1.1 400 Bad Request`, body `{"type":"Bad request","msg":"Scheduled time must be in the future"}`.

### Step 3 — Upstream registration failure forwards the gateway message (the original Tip incident)

```bash
# Pick a name prefix that exceeds the Tip 12-char cap, e.g. "dem0bc116bot" → "dem0bc116bot1"
# is 13 chars after appending index 1.
curl -i -s -X POST -H 'Content-Type: application/json' \
  -d '{
    "name":"E2E Bad Username",
    "namePrefix":"dem0bc116bot",
    "password":"a123123A",
    "gameId":"<a-real-tip-game-id>",
    "botCount":1,
    "environmentId":"<a-real-tip-env-id>"
  }' \
  http://staging:8080/api/v1/bot-group/
```
**Expect:** `HTTP/1.1 502 Bad Gateway`, body `{"type":"Game server error","msg":"<text containing the upstream Vietnamese message, e.g. 'Tên đăng nhập không được nhiều hơn 12 ký tự'>"}`. The `msg` must literally contain that substring (or the equivalent text the gateway returns for the prefix used).

### Step 4 — Missing `environmentId` produces typed `400`

```bash
curl -i -s -X POST -H 'Content-Type: application/json' \
  -d '{"name":"Group missing env","gameId":"<a-real-game-id>"}' \
  http://staging:8080/api/v1/bot-group/<created-group-id-without-env>/start
```
(Two-step: create the group first, then `/start`.)
**Expect:** `HTTP/1.1 400 Bad Request`, body `{"type":"Bad request","msg":"BotGroup ... has no environmentId set. Please assign an environment before starting the bot group."}`.

### Step 5 — Server logs carry the advice-emitted stacktrace

```bash
# SSH to staging
ssh staging
docker logs --since 5m bot-manager 2>&1 | grep -E "Handled (UpstreamRegistrationException|BadRequestException|ResourceNotFoundException)"
```
**Expect:** at least one log line per exception type triggered above, matching the pattern `Handled <ExceptionClass> from <URI>: <message>`. The full stacktrace appears in the following lines (logger writes the throwable).

### Step 6 — Existing happy-path endpoints unchanged

```bash
curl -i -s http://staging:8080/api/v1/bot-group/ | head -1
curl -i -s http://staging:8080/api/v1/environment/ | head -1
curl -i -s http://staging:8080/api/v1/game/types | head -1
```
**Expect:** all three return `HTTP/1.1 200 OK` with JSON arrays. No regression on the success path.

### Step 7 — Universal smoke test

```bash
curl -i -s http://staging:8080/actuator/health
```
**Expect:** `HTTP/1.1 200 OK`, body contains `"status":"UP"`.

---

## Compliance Amendments — 2026-06-11

Appended by Architect-2 at sign-off. The original plan body above is unchanged; these notes record where reality diverged from the plan and why the diff is still compliant.

1. **Phase A step 3's "legacy catch ladder still in place" wording is misleading in hindsight.** The plan's step 3 claimed `BotGroupController.save`'s existing `catch (IllegalStateException e) → 502 + ErrorResponse` ladder would still handle the (now unused) old type and remain harmless dead code until Phase B. In practice, once the service throws `UpstreamRegistrationException` instead, no `IllegalStateException` ever reaches the controller's catch — so the controller's terminal `catch (Exception e) → empty 500` would have produced an empty 500 on the new type unless the advice was wired. Shipping Phase A and Phase B together (commits `f7508b5` then `25560a5` on the same branch, deployed as a unit) sidestepped the regression window. Future readers: do not interpret Phase A as safely deployable in isolation against this codebase.

2. **`ApiGatewayClient.registerSingleUser` was renamed to `registerSingleUserDirectly`** by the separate `CLEANUP_SMALL` Phase 2A commit (`3482874`, "drop unreachable non-xToken branches"), which landed on `main` after this plan was written. The plan's references at `Findings → ApiGatewayClient.registerSingleUser:358` should be read as the renamed symbol. No behaviour change.

3. **`BotGroupBehaviorService.classifyCreationFailure` did not exist when this plan was written** — it was introduced by `RESTART_LIFECYCLE_FIX` Phase 1 (Decision 5, bounded label tags for `bot_creation_failures_total`). Phase B of this plan added explicit `instanceof BadRequestException` and `instanceof UpstreamLoginException` arms to that classifier so the new typed exceptions land in the correct `validation` / `auth` bucket without depending on the message-substring fallback. This is plan-consistent but unanticipated in the Phase B step list.

4. **Plan's "36 catch blocks across 4 controllers" claim** — verified: 4 of the 5 controllers had ladders (BotGroup 14, Environment 8, Game 8, SessionHistory 6 = 36; Brand had none and is correctly listed as n/a). All 4 are now at 0 catch blocks; Brand untouched. The plan body already documented Brand as n/a, but the "4 controllers" phrasing elsewhere is unambiguous.

5. **`validateUsernameLength` (added by `CLEANUP_SMALL` Phase 2B, commit `954f079`) was retyped from `IllegalArgumentException` to `BadRequestException`** as part of Phase B. AD-6 declared either acceptable (both map to 400). Choosing `BadRequestException` keeps the throw-site semantic explicit and matches the rest of the API_ERROR_FORWARDING typed-exception convention; the message text is unchanged so the response body is bit-identical to what `IllegalArgumentException` would have produced.

6. **One `IllegalStateException` remains in `BotGroupBehaviorService` (line 527, "Restart produced 0/N bots")** by design — it is the RESTART_LIFECYCLE_FIX Decision 6 fail-loud signal, not an upstream/validation case, and the AD-8 transitional `IllegalStateException → 500 + body` mapping is its intended destination. The plan's Phase B verification check "no legacy IllegalStateException for upstream-failure intent" passes for `BotGroupService` (the only call site this plan was targeting); this one is a legitimate non-upstream use that arrived after the plan was written.
