# TECH_DEBT_PASS_2

Branch: `chore/tech-debt-pass-2` (already checked out).

Build note: every `mvn` invocation in this plan MUST run with
`JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home`
(the shell default JDK is Java 8 and will not compile this project).

## Goal

A second, bounded tech-debt cleanup pass covering four IMPLEMENT items and one
REVIEW-ONLY item. The IMPLEMENT items remove dead deprecated API, delete a stale
TODO block, fill in three Swagger doc examples, and introduce a shallow typed
exception hierarchy — all **without changing any HTTP status the existing
controller/handler tests lock in**. The REVIEW-ONLY item produces a
recommendation table for `Bot.java` legacy methods; it is gated on user approval
and has no implementation phase here.

Ordering is trivial-first: the Starter TODO (Phase 1) and Swagger examples
(Phase 2) are quick and low-risk; the deprecated-API removal (Phase 3) and the
exception hierarchy (Phase 4) are the substantive changes. Each phase is one
commit and one `mvn test` gate.

## Findings — Current State

### Item 1 — deprecated API

`GameMsClient` (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/client/GameMsClient.java`) carries the following `@Deprecated` members:

| # | Member | Location | Kind |
|---|--------|----------|------|
| 1 | `GameMsClient()` no-arg ctor | `GameMsClient.java:50` | stateful ctor (hardcodes `http://gamems.dev:5007`) |
| 2 | `agencyToken` field | `GameMsClient.java:56` | stateful field |
| 3 | `agencyId` field | `GameMsClient.java:58` | stateful field |
| 4 | `uuid` field | `GameMsClient.java:60` | stateful field |
| 5 | `memberId` field | `GameMsClient.java:62` | stateful field |
| 6 | `deposit(long, Consumer)` | `GameMsClient.java:179` | stateful deposit |
| 7 | `fetchTokenDetails()` no-arg | `GameMsClient.java:234` | stateful, private |
| 8 | `setAgencyToken(String)` | `GameMsClient.java:283` | stateful mutator |

`ClientFactory` (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/client/ClientFactory.java`) carries:

| # | Member | Location | Kind |
|---|--------|----------|------|
| 9 | `newClient()` no-arg | `ClientFactory.java:58` | deprecated (uses shared mutable `tokens`) |

**Caller audit (production AND test, exhaustive):**

- **Stateless replacements are the only live path.** `new GameMsClient(gameMsUrl)`
  at `EnvironmentClientRegistry.java:132` is the sole constructor call in `src/`.
  `clientFactory.newClient(tokens, name)` is the sole `ClientFactory` client
  factory call used by `Bot.initialize()` (`Bot.java:236`), `Bot.restart()`
  (`Bot.java:280`), and `tryReconnectWs()`.
- **`GameMsClient` deposit path is genuinely dead.** `Bot.deposit()`
  (`Bot.java:315-339`) routes through `apiGatewayClient.deposit(userName, 1_000_000_000L)`
  (`Bot.java:324`) — the gwms bot-deposit endpoint (the game-spendable partition
  fix at main `fd01c89`). The `gameMsClient` field on `Bot` (`Bot.java:47`) is
  injected via `setClients(...)` and stored but **no method is ever invoked on
  it** anywhere in `src/main` or `src/test`. Verified: `grep` for
  `gameMsClient.deposit` / `.setAgencyToken` / `.fetchTokenDetails` returns zero
  hits outside `GameMsClient.java` itself. The "possible per-env fallback" note
  in project docs does **not** correspond to any live wiring.
- **The `bot.deposit()` calls in `BotTest.java` (lines 205, 221, 236, 393)** are
  `Bot.deposit()`, NOT `GameMsClient.deposit(...)`; they assert on
  `apiGatewayClient.deposit(...)` (`BotTest.java:207,218,234,390`). Not affected.
- **`newClient()` no-arg** (member 9) has zero callers in `src/main`/`src/test`;
  the only textual hits are its own declaration and its own log line.
- **`fetchTokenDetails()` no-arg** (member 7) is `private` and called only from
  the deprecated `deposit(long, Consumer)` (member 6) at `GameMsClient.java:180`.
  Removing member 6 removes member 7's only caller.
- **The stateless `deposit(String, long, Consumer)` (`GameMsClient.java:72`) and
  `fetchTokenDetails(String)` (`GameMsClient.java:137`) also have ZERO callers**
  today (the deposit path moved to `ApiGatewayClient`). They are NOT deprecated,
  so this pass leaves them in place (see Open Items / AD-1.4) — removing them is
  out of scope and would widen the change.

Conclusion: **all 9 deprecated members are removable with no caller migration
required.** The stateless ctor `GameMsClient(String)` and the `gameMsClient`
field/`setClients` wiring stay untouched.

### Item 2 — stale TODO in `Starter.java`

`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/Starter.java:15-19` is a
trailing block comment `/* TODO: IMPORTANT ... */` after the class body. It is
pure comment — no annotation, no code depends on it. User confirmed the
name-setting item is resolved.

### Item 3 — Swagger examples in `EnvironmentController`

`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/environment/controller/EnvironmentController.java`
(note: actual path is under `.../environment/controller/`, not the path in the
task brief). Three `@Parameter(... example = "N/A") //TODO: pending example`:

- `filter(...)` — `EnvironmentController.java:86` (body type `EnvironmentFilter`)
- `save(...)` — `EnvironmentController.java:107` (body type `EnvironmentDTO`)
- `update(...)` — `EnvironmentController.java:120` (body type `EnvironmentDTO`)

Relevant DTO/model shapes:
- `EnvironmentDTO` (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/environment/dto/EnvironmentDTO.java:19-61`):
  `name`, `type` (`EnvironmentType`), `brandCode` (`BrandCode`),
  `productCode` (`ProductCode`), `webSocketMiniUrl`, `webSocketCardUrl`,
  `hostUrl`, `apiGatewayUrl`, `appId`, `headers` (Map), `customZone`,
  `binaryFrame`, `miniZoneName`, `cardZoneName`, `encryptionKey`, `encryptionIv`,
  `alertOnLowBalance`, `useJwtAuth`, `periodicLogoutEnabled`,
  `periodicLogoutIntervalMinutes`. The four `total*/running*` fields are
  read-only response-side enrichers (`EnvironmentController.java:136-151`) and are
  omitted from request examples.
- `EnvironmentFilter` (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/environment/model/EnvironmentFilter.java:14-20`):
  `name`, `type`, `brandCode`, `productCode`.
- `EnvironmentType` enum: `DEVELOPMENT, STAGING, QA, PREPRODUCTION, PRODUCTION`
  (`EnvironmentType.java:5`).
- `BrandCode` enum: `G0, G2, G3, G4, G5, GTH` (`BrandCode.java`).
- `ProductCode` — **serialised as an OBJECT** (`@JsonFormat(shape = OBJECT)`,
  `ProductCode.java:5`) but **deserialised from the `code` string** via
  `@JsonCreator fromCode(String)` (`ProductCode.java:79-84`). So in a *request*
  body the correct value is the 3-digit code string, e.g. `"097"` (BOM) or
  `"116"` (TIP). The example JSON must use the string form.

### Item 4 — exception hierarchy

Existing exceptions in `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/`:
- `ResourceNotFoundException extends RuntimeException` → 404 bodyless
  (`RestExceptionHandler.java:71-78`).
- `BadRequestException extends RuntimeException` → 400 with body
  (`RestExceptionHandler.java:80-86`). Already used by service layer:
  `GameService.java:134`, `BotGroupService.java:244,275`.
- `UpstreamGatewayException` (abstract) `extends RuntimeException` → 502
  (`RestExceptionHandler.java:112-118`); leaves `UpstreamLoginException`,
  `UpstreamRegistrationException`, `UpstreamPrometheusException`.

Handler mapping today (`RestExceptionHandler.java:38-56`):
- `IllegalArgumentException` → 400 (fallback, `:88-94`)
- `IllegalStateException` → 500 sanitised (`:133-139`)
- `Exception` → 500 sanitised (`:204-210`)

Raw throws in `src/main` (excluding `common/exception`): 32 sites, mostly in
factories/validators/strategy config (`BotFactory`, `*StrategyFactory`,
`GameConfigValidatorFactory`, `StrategyAssignment`, `GameMessageTypesResolver`,
`ProductCode.fromCode`, `WeightedOptionPicker`, `BotMemory`, etc.). The only
service-layer raw throw reachable from an HTTP request that is a genuine
**client-correctable validation failure** is:
- `EnvironmentService.java:127` — `IllegalArgumentException` when WS headers lack
  `Host`/`Origin` on save/update. Currently maps to 400 via the
  `IllegalArgumentException` fallback.

`ValidationException` in the codebase is **websocket-parser's**
`com.vingame.websocketparser.exception.ValidationException` — it is *caught*
(`BotGroupBehaviorService.java:45,566`), not thrown by us. It is not part of our
hierarchy and is out of scope.

**Tests that lock in HTTP/exception behavior (must not regress):**
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/common/exception/RestExceptionHandlerTest.java`
  — asserts `IllegalArgumentException→400` (`:56-58`), `IllegalStateException→500`
  sanitised (`:96-104`, `:196-202`), `ResourceNotFoundException→404` bodyless
  (`:38-41`, `:167-175`), `BadRequestException→400` (`:46-49`), upstream→502,
  malformed body→400, 405/415/400 servlet mappings. **The `IllegalArgumentException→400`
  and `IllegalStateException→500` arms must stay** — they are directly asserted.
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/environment/controller/EnvironmentControllerTest.java`
  — `findById` injects `IllegalArgumentException("Invalid ID")` and expects
  **400 with body** (`:120-132`), injects `ResourceNotFoundException` → 404
  (`:107-116`), injects `RuntimeException` → 500 sanitised (`:134-152`). The
  `IllegalArgumentException→400` fallback is load-bearing for this test.
- No test in `src/test` uses `assertThrows(SomeSpecificException.class)` against
  our exception types (grep returned zero) — so migrating a raw throw to a typed
  subtype cannot break a type-level unit assertion, ONLY the HTTP-status
  assertions above matter.

### Item 5 — `Bot.java` legacy methods (findings feed the recommendation table below)

Method inventory and caller evidence in
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`:
- `restart()` (`:275-285`) — recreates client + `connect()` + `start()`. One
  live caller: `BotGroupBehaviorService.java:1551` (periodic-logout reconnect).
- `connectToSocket()` (`:293-295`) — one-line `client.connect()`. **Zero callers**
  anywhere in `src/main`/`src/test`.
- `authenticate()` (`:297-304`) — re-auth wrapper that swallows the exception.
  **Zero callers** (the live re-auth path is `performReauth()` at `:603`, which
  transitions status + marks DEAD on failure).
- `logout()` (`:306-313`) — calls `stop()`. One live caller:
  `BotGroupBehaviorService.java:1538` (periodic logout).
- Newer reconnect machinery (all live, mutually wired): `triggerFullReconnect`
  (`:493`), `runWsReconnectLoop` (`:522/:534`), `runAuthThenWsLoop` (`:580`),
  `performReauth` (`:603`), `tryReconnectWs` (`:640`), `closeClientQuietly`
  (`:628`).

## Per-aspect readiness / mapping

| Aspect | Readiness | Notes |
|--------|-----------|-------|
| Item 1: remove 9 deprecated members | ready | zero callers confirmed; stateless replacements already live |
| Item 2: delete Starter TODO | ready | trailing comment, no dependency |
| Item 3: Swagger examples (3) | ready | DTO/enum shapes known; `ProductCode` request form is the code string |
| Item 4: exception base type + BadRequest migration | ready | shallow reparent + one service throw; handler arms preserved |
| Item 4: broad raw-throw migration | partial (deferred) | 30+ factory/strategy throws are programming errors; left as-is behind IllegalArg/IllegalState fallbacks |
| Item 5: Bot.java legacy methods | review-only | recommendation table below; no code change without approval |

## Architecture Decisions

**AD-1 (Item 1 — deprecated removal).**
- **AD-1.1** Delete all 9 deprecated members listed in Findings. No caller
  migration is needed; every live path already uses the stateless replacement.
- **AD-1.2** Keep `GameMsClient(String gameMsUrl)` ctor, the `gameMsUrl` field,
  the stateless `deposit(String, long, Consumer)` and `fetchTokenDetails(String)`
  methods, and the `TokenDetails`/`DepositRequest`/`DepositRequestData` inner
  types. Do NOT delete these in this pass even though the two stateless methods
  currently have no callers — they are a real per-env deposit capability and are
  not deprecated. Their removal is a separate decision (Open Items).
- **AD-1.3** Keep the `gameMsClient` field on `Bot` (`Bot.java:47`) and the
  `setClients(...)` parameter. Removing it is a wider refactor touching `Bot`,
  `BotFactory`, `EnvironmentClients`, and ~40 test call sites; explicitly out of
  scope for this pass.
- **AD-1.4** After deletion, remove the now-unused `Consumer` import from
  `GameMsClient` ONLY if the stateless `deposit(String,...)` retained per AD-1.2
  no longer references it — it does still reference `Consumer<Boolean>`, so the
  import STAYS. (Confirm at edit time; do not leave an unused import.)

**AD-2 (Item 2).** Delete `Starter.java:14-19` (the blank line + block comment)
so the file ends cleanly after the closing brace of the class.

**AD-3 (Item 3 — Swagger examples).** Provide the exact JSON below. `ProductCode`
and enums use their request (string) forms. Examples are illustrative and use
staging-realistic BOM (`097`) / TIP (`116`) values.

- **filter** (`EnvironmentFilter`):
  ```json
  {"type":"STAGING","brandCode":"G2","productCode":"097"}
  ```
- **save** (`EnvironmentDTO` create):
  ```json
  {"name":"BOM Staging","type":"STAGING","brandCode":"G2","productCode":"097","webSocketMiniUrl":"wss://bom-sock.stgame.win/mini","hostUrl":"https://bom.stgame.win","apiGatewayUrl":"https://bomgw.stgame.win","headers":{"Origin":"https://bom.stgame.win"},"customZone":false,"binaryFrame":false,"miniZoneName":"MiniGame3","alertOnLowBalance":true,"useJwtAuth":false,"periodicLogoutEnabled":true,"periodicLogoutIntervalMinutes":60}
  ```
- **update** (`EnvironmentDTO` PATCH, partial — only non-null fields applied):
  ```json
  {"periodicLogoutEnabled":true,"periodicLogoutIntervalMinutes":30,"alertOnLowBalance":true}
  ```
Remove the three `//TODO: pending example` comments and replace `example = "N/A"`
with the strings above (JSON escaped as a Java string literal in the annotation).

**AD-4 (Item 4 — hierarchy shape). Shallow, one-level base.**
- **AD-4.1** Introduce `BotManagerException extends RuntimeException` (abstract)
  in `common/exception` as the common base for our own domain exceptions. It adds
  no new fields beyond `message`/`cause` (the `type` discriminator stays on
  `UpstreamGatewayException` only).
- **AD-4.2** Reparent the existing leaf/base exceptions onto it:
  `ResourceNotFoundException extends BotManagerException`,
  `BadRequestException extends BotManagerException`,
  `UpstreamGatewayException extends BotManagerException` (its subclasses are
  unchanged; they already extend `UpstreamGatewayException`).
- **AD-4.3** `RestExceptionHandler` is NOT restructured to catch the base type.
  The existing per-type arms (`ResourceNotFoundException→404`,
  `BadRequestException→400`, `UpstreamGatewayException→502`,
  `IllegalArgumentException→400`, `IllegalStateException→500`, `Exception→500`)
  all stay verbatim. Reparenting is transparent to Spring's most-specific-handler
  dispatch. **No handler behavior change → all `RestExceptionHandlerTest` and
  `EnvironmentControllerTest` assertions pass unchanged.**
- **AD-4.4** The `IllegalArgumentException→400` fallback arm
  (`RestExceptionHandler.java:88-94`) STAYS as the migration safety net —
  required by `EnvironmentControllerTest:120-132` and
  `RestExceptionHandlerTest:56-58`.
- **AD-4.5 Bounded migration in THIS pass — exactly ONE raw throw migrates:**
  `EnvironmentService.java:127` `IllegalArgumentException` (WS-header validation)
  → `BadRequestException`. It stays HTTP 400 (was 400 via fallback, now 400 via
  the typed arm) — no status change, cleaner intent. This is the only
  service-layer raw throw that is a genuine client-correctable validation and is
  reachable from an HTTP endpoint (`save`/`update`).
- **AD-4.6 Explicitly DEFERRED (stay raw):** all factory/validator/strategy
  `IllegalArgumentException`/`IllegalStateException` throws
  (`BotFactory`, `BettingStrategyFactory`, `SlotStrategyFactory`,
  `GameConfigValidatorFactory`, `StrategyAssignment`, `GameMessageTypesResolver`,
  `ProductCode.fromCode`, `WeightedOptionPicker`, `BotMemory`,
  `AffinityOptionPicker`, `MetricKey`, `Game`, `SlotMachineBot`,
  `BettingMiniGameBot`, `ApiGatewayClient.checkInitialized`,
  `ClientFactory`). These are programming-error / configuration-wiring guards, not
  user-facing request validation; forcing them into the domain hierarchy would
  balloon the change and risk flipping a currently-500 programming error into a
  client-blamed 400. They remain covered by the handler's IllegalArg/IllegalState
  fallback arms. Revisit in a future pass if any becomes request-reachable.
- **AD-4.7** No new HTTP status codes, no new handler arms, no `ErrorResponse`
  shape change.

## Plan

### Phase 1 — Delete stale TODO in `Starter.java` (Item 2)

Files:
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/Starter.java`

Steps:
1. Delete lines 14-19 (the blank line and the `/* TODO: IMPORTANT ... */` block)
   so the file ends at the class's closing brace (line 13).

Verify:
```
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q -DskipTests compile
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn test
```
Expect: compile success; full test suite green.

Commit: `chore(starter): remove resolved name-setting TODO block`.

### Phase 2 — Fill Swagger examples in `EnvironmentController` (Item 3)

Files:
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/environment/controller/EnvironmentController.java`

Steps:
1. `filter(...)` (`:86`): replace `example = "N/A"` with the filter JSON from
   AD-3 and delete the `//TODO: pending example` comment.
2. `save(...)` (`:107`): replace with the save JSON from AD-3; delete the TODO.
3. `update(...)` (`:120`): replace with the update JSON from AD-3; delete the TODO.
4. Keep `description = "..."` text on each `@Parameter` unchanged.

Verify:
```
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn test
```
Expect: green. (Optional local check: run the app and confirm
`GET /v3/api-docs` renders the new examples for the three endpoints — not
required for CI.)

Commit: `docs(environment-api): replace placeholder Swagger examples with realistic bodies`.

### Phase 3 — Remove deprecated API from `GameMsClient` and `ClientFactory` (Item 1)

Files:
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/client/GameMsClient.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/client/ClientFactory.java`

Steps:
1. In `GameMsClient`, delete members 1-8 from the Findings table: the no-arg
   ctor (`:49-52`), the four stateful fields (`:55-62`), the stateful
   `deposit(long, Consumer)` (`:172-225`), the private `fetchTokenDetails()`
   (`:227-274`), and `setAgencyToken(String)` (`:276-286`).
2. Keep the stateless ctor, `gameMsUrl`, stateless `deposit(String, long, Consumer)`,
   `fetchTokenDetails(String)`, and the inner types (AD-1.2). Verify the
   `Consumer` import is still referenced (it is — by the stateless deposit) and
   leave it; remove any import that genuinely goes unused after deletion.
3. In `ClientFactory`, delete `newClient()` no-arg (`:57-66`). Keep
   `newClient(TokensProvider, String)`, `buildClient(...)`, and all fields.
   Note: the `@Setter` on the class and the `tokens` field remain (fields are set
   elsewhere via `EnvironmentClientRegistry`); only the no-arg factory method is
   removed.

Verify:
```
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q -DskipTests compile
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn test
```
Expect: compile success (no unresolved references — confirms the audit), full
suite green. If compilation fails on a caller, STOP: the audit missed a site —
migrate it to the stateless equivalent before deleting.

Commit: `refactor(client): remove deprecated stateful GameMsClient/ClientFactory members`.

### Phase 4 — Introduce `BotManagerException` base + migrate one validation throw (Item 4)

Files:
- new `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/BotManagerException.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/ResourceNotFoundException.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/BadRequestException.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/UpstreamGatewayException.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/environment/service/EnvironmentService.java`

Steps:
1. Create `BotManagerException` (abstract, `extends RuntimeException`) with
   `(String message)` and `(String message, Throwable cause)` constructors and a
   class-level Javadoc stating it is the root of the app's domain exception
   hierarchy and that `RestExceptionHandler` maps concrete subtypes to statuses
   (AD-4.1).
2. Change `ResourceNotFoundException`, `BadRequestException`, and
   `UpstreamGatewayException` to `extends BotManagerException` (AD-4.2). Add a
   `(String, Throwable)` ctor to `ResourceNotFoundException` only if needed by
   the base's abstract-ness — it isn't abstract-forced, so keep
   `ResourceNotFoundException`'s single ctor unless a compile error demands the
   cause ctor.
3. In `EnvironmentService.validateAndMergeWsHeaders` (`:125-129`), change the
   `throw new IllegalArgumentException(...)` to
   `throw new BadRequestException(...)` with the same message (AD-4.5). Add the
   import.
4. Do NOT touch `RestExceptionHandler` (AD-4.3/AD-4.4). Do NOT migrate any other
   raw throw (AD-4.6).

Verify:
```
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn -q -DskipTests compile
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn test
```
Expect: green. Specifically `RestExceptionHandlerTest` (all arms) and
`EnvironmentControllerTest` (`IllegalArgumentException→400`,
`ResourceNotFoundException→404`, `RuntimeException→500`) still pass — the
reparenting is transparent and the WS-header throw was already 400.

Commit: `refactor(exceptions): add BotManagerException base; type WS-header validation as BadRequest`.

## Implementation Notes / Concerns

- **Phase 3 is the riskiest for a hidden caller.** The compile step IS the audit
  gate — a clean `mvn compile` after deletion is proof of zero callers. Trust the
  compiler over grep here.
- **`ProductCode` in Swagger examples must be the code STRING** (`"097"`), not
  the object form. Using the object form would produce a misleading example that
  a copy-paste user could not POST successfully (deserialisation goes through
  `fromCode(String)`).
- **`BadRequestException` already exists and is already 400-mapped** — Phase 4's
  migration of `EnvironmentService:127` does not change the observed status (400
  before via fallback, 400 after via typed arm). No client-visible change.
- **Do not delete the `IllegalArgumentException→400` handler arm.** It is asserted
  by two tests and remains the fallback for the deferred raw throws (AD-4.6).
- **Do not widen Item 4.** The temptation to sweep the 30+ factory throws into the
  hierarchy is exactly the balloon the brief warns against — most are 500-class
  programming errors and are not request-reachable.
- **`gameMsClient` field on `Bot` stays** (AD-1.3) — removing it is a separate,
  larger change touching many test fixtures.

## Open Items

- **Deferred (future pass):** migrate the deferred raw throws (AD-4.6) to typed
  exceptions if/when any becomes request-reachable; consider a
  `ConfigurationException`/`InvalidStateException` subtype under
  `BotManagerException` at that time.
- **Deferred:** removal of the unused stateless `GameMsClient.deposit(String,...)`
  / `fetchTokenDetails(String)` and the `gameMsClient` field + `setClients`
  parameter (AD-1.2/AD-1.3) — a dedicated dead-deposit-path cleanup, out of scope
  here.
- **Item 5 is gated on user approval** — see the recommendation section below; no
  implementation phase is planned until the user picks the actions.

## Verification

On-server verification for this change set is the **universal smoke test only** —
there is no new runtime behavior, endpoint, or metric to probe. All four
IMPLEMENT items are compile-time / documentation / dead-code changes whose
correctness is fully covered by `mvn test` (the exception-handler and
environment-controller suites lock in the HTTP contract).

Post-deploy staging checks:

1. App comes up healthy:
   ```
   curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health
   ```
   Expect: HTTP `200` and body status `UP`.
2. Swagger doc renders the new examples (documentation-change confirmation):
   ```
   curl -s http://localhost:8080/v3/api-docs | grep -c '"097"'
   ```
   Expect: count `>= 1` (the BOM `productCode` example string now appears in the
   OpenAPI JSON for the environment endpoints).
3. Environment API still serves 400 on a malformed WS-header create (exception
   migration is transparent):
   ```
   curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/api/v1/environment/ \
     -H 'Content-Type: application/json' \
     -d '{"name":"smoke","headers":{"foo":"bar"}}'
   ```
   Expect: HTTP `400` (WS headers missing `Host`/`Origin` → `BadRequestException`).
   Note: this exercises `validateAndMergeWsHeaders` only if the save path reaches
   it for the given brand/product; if the environment save short-circuits earlier
   for other reasons, treat this step as best-effort and rely on the unit suite,
   which asserts the 400 deterministically.

No new metrics, no group-health assertions, no log-line signatures introduced by
this pass.

## Item 5 — Bot.java legacy methods: recommendations (REVIEW-ONLY, needs user approval)

No production code is changed for Item 5 in this plan. The table below is a
recommendation the user must approve before any follow-up implementation is
scheduled. All caller evidence is from `src/main` + `src/test` grep and the
current reconnect architecture (`triggerFullReconnect`/`runAuthThenWsLoop`/
`runWsReconnectLoop`/`performReauth`/`tryReconnectWs`).

| Method (Bot.java) | Callers found | Status | Recommended action | Reasoning |
|-------------------|---------------|--------|--------------------|-----------|
| `connectToSocket()` (`:293-295`) | **none** (zero in `src/main`/`src/test`) | dead | **DELETE** | Thin `client.connect()` wrapper with no callers. `initialize()` (`:244`), `restart()` (`:283`), and `tryReconnectWs()` (`:648`) all call `client.connect()` directly. Nothing routes through this method. |
| `authenticate()` (`:297-304`) | **none** | dead / redundant | **DELETE** | Zero callers. Redundant with `performReauth()` (`:603`), which is the live re-auth path and is strictly better: it transitions `BotStatus`, marks `DEAD` on failure, and closes the client. `authenticate()` swallows the exception and leaves the bot in an inconsistent state — a latent footgun if a future caller picked it up. |
| `restart()` (`:275-285`) | 1: `BotGroupBehaviorService.java:1551` (periodic-logout reconnect) | live, partially redundant | **KEEP (this pass); consider MERGE later** | Still the reconnect step of the periodic-logout cycle after `logout()`. It overlaps conceptually with `triggerFullReconnect()` but differs: `restart()` is a synchronous, caller-driven rebuild (new client + connect + `start()`) with no backoff/cap; `triggerFullReconnect()` is the async, watchdog-driven, capped machinery. They are not drop-in equivalents. A future refactor could route periodic logout through the reconnect machinery, but that changes semantics (backoff, DEAD-cap) and needs its own plan — do NOT fold it in blindly. |
| `logout()` (`:306-313`) | 1: `BotGroupBehaviorService.java:1538` (periodic logout) | live | **KEEP** | Live, single well-defined caller. Semantically distinct from `stop()`/`cleanup()`: it is a graceful close that deliberately does NOT set `stopped`, so the subsequent `restart()` can bring the bot back (see `BotReconnectTest.java:130` note). Renaming for clarity is optional and cosmetic. |
| `stop()` (`:287-291`) | live: `cleanup()` (`:262`), `logout()` (`:308`) | live | **KEEP** | Core teardown primitive (`client.close()`). Not legacy. |
| `cleanup()` (`:257-273`) | live: `BotGroupRuntime.java:271`, overridden by `BettingMiniGameBot.java:584` | live | **KEEP** | Terminal teardown + dead-seconds accounting; heavily tested (`BotDeadSecondsTest`, `BotGroupRuntimeTest`). Not legacy. |
| `initialize()` (`:213-253`) | live: `BotFactory.java:190` | live | **KEEP** | Primary bring-up path (auth → client → connect). Not legacy. |

Summary recommendation: **delete `connectToSocket()` and `authenticate()`**
(both provably dead and, in `authenticate()`'s case, a footgun superseded by
`performReauth()`); **keep `restart()`/`logout()`/`stop()`/`cleanup()`/`initialize()`**.
A `restart()`→reconnect-machinery merge is a plausible future cleanup but is a
behavior change (backoff + DEAD-cap semantics) and must be planned separately,
not bundled into a tech-debt deletion pass.

If the user approves deleting `connectToSocket()` and `authenticate()`, that
becomes a small Phase 5 (one commit: delete both methods, `mvn test`) — but it is
NOT scheduled until approval.

---

Plan written: docs/plans/TECH_DEBT_PASS_2.md
Ready for user approval before Dev begins.
