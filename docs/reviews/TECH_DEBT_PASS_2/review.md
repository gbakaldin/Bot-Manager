# Code Review — TECH_DEBT_PASS_2

Branch: `chore/tech-debt-pass-2`
Reviewed diff: `git diff main..chore/tech-debt-pass-2`

## Verdict

PASS

No `bug` or `security` findings. One low-priority `smell` (advisory) noted below.

## Findings

### [smell] `ClientFactory.tokens` field is now write-only dead state
`src/main/java/com/vingame/bot/infrastructure/client/ClientFactory.java:25`

Removing the no-arg `newClient()` (its only reader) leaves the `private TokensProvider tokens`
field with no remaining reader anywhere in the class. The class is annotated `@Setter`, so
Lombok still generates a public `setTokens(...)`, but nothing consumes what it stores —
`newClient(TokensProvider, String)` takes tokens as a parameter and `buildClient` uses that
parameter, not the field. It is a leftover from the deleted shared-mutable-state path the
commit was specifically removing. This is exactly the kind of stateful sharing the deprecation
warned against, now reachable only as an inert setter. Suggested fix shape: drop the `tokens`
field. No caller sets it via the diff (confirmed: no `setTokens` references in `src/`), so
removal is safe. Left as advisory since it does not affect behavior.

## Notes

Verified against the task checklist:

1. **GameMsClient / ClientFactory dead-member removal.** No live path lost functionality.
   Confirmed via `grep` that the removed members have zero callers in `src/`: no-arg
   `newClient()`, `new GameMsClient()`, `GameMsClient.setAgencyToken`, and the stateful
   `GameMsClient.deposit(long, Consumer)`/`fetchTokenDetails()` are all unreferenced. The
   retained stateless surface is intact: `GameMsClient(String)` ctor,
   `deposit(String, long, Consumer)`, `fetchTokenDetails(String)`. Dev's note about the
   `Consumer` import is correct — it is still used by the stateless `deposit` signature
   (`GameMsClient.java:17` → used at `:51`). No now-unused imports remain; `mvn -o compile`
   succeeds cleanly.

2. **Starter.java.** Trivial stale-comment deletion, no behavioral effect.

3. **EnvironmentController Swagger examples.** Verified against the actual DTO/filter shapes.
   Every field in the `save` example exists on `EnvironmentDTO`; the `update` example uses a
   valid subset; the `filter` example fields (`type`, `brandCode`, `productCode`) all exist on
   `EnvironmentFilter`. The `productCode` string-form gotcha is handled correctly: `ProductCode`
   carries `@JsonFormat(shape = OBJECT)` (object on serialization) plus a single-String
   `@JsonCreator fromCode(String)` (bare string on deserialization), so request examples must
   use `"097"`, which both examples do. `brandCode:"G2"` and `type:"STAGING"` are plain-enum
   name deserialization — valid.

4. **Exception hierarchy.** Coherent. `BotManagerException` is `abstract` with `protected`
   `(String)` and `(String, Throwable)` ctors, extends `RuntimeException`, carries no extra
   state. Reparenting `ResourceNotFoundException`/`BadRequestException`/`UpstreamGatewayException`
   under it does not alter dispatch: `RestExceptionHandler` has per-concrete-type
   `@ExceptionHandler` arms and never catches `BotManagerException` directly, so
   most-specific-handler resolution is unchanged, and HTTP status mapping (404/400/502) is
   preserved. The one migrated throw (`EnvironmentService.validateAndMergeWsHeaders`,
   `IllegalArgumentException` → `BadRequestException`) is behavior-preserving at the HTTP
   boundary: both the old `handleIllegalArgument` and the new `handleBadRequest` return 400 with
   an `ErrorResponse("Bad request", message)`, and the message is untouched. No local catch site
   in the environment package intercepts `IllegalArgumentException`/`RuntimeException`, so the
   migrated throw is not swallowed. `EnvironmentServiceTest` was updated to assert
   `BadRequestException` on all three arms. The deliberately-bounded scope (one throw migrated,
   others deferred) is respected and not flagged.

5. **Bot.java dead-method deletion.** `connectToSocket()` and `authenticate()` are genuinely
   unreferenced — zero `connectToSocket` references in `src/`, and the surviving
   `apiGatewayClient.authenticate(credentials)` calls at `Bot.java:233` and `:594` are on the
   injected client (a different object), not the deleted `Bot.authenticate()`. The deleted
   method's `this.tokens = apiGatewayClient.authenticate(credentials)` assignment is preserved
   inline at both surviving call sites, so no sibling relied on the deleted wrapper. Note the
   deleted `Bot.authenticate()` swallowed auth failures (caught `Exception`, logged, continued);
   its removal is a net improvement since the surviving inline paths let the exception propagate.

Overall a clean, well-scoped tech-debt pass. The single smell is a leftover field from the
removal itself and is safe to drop in a follow-up.
