# Compliance — API_ERROR_FORWARDING

Branch: `feat/api-error-forwarding`
Plan reviewed: `/Users/gleb/IdeaProjects/Bot/docs/plans/API_ERROR_FORWARDING.md`
Diff reviewed: `git diff main..feat/api-error-forwarding` (Phase A `f7508b5`, Phase B `25560a5`)

## Verdict

**PASS-with-notes** (plan-amended, no rework required)

Both phases shipped together implement the plan faithfully. Every Architecture Decision is honored: the `@RestControllerAdvice` is the single source of truth (AD-1), the four-class typed hierarchy lives under `com.vingame.bot.common.exception` with the documented status mappings (AD-2), upstream failures map to `502` (AD-3), `{type, msg}` is the single body envelope (AD-4), `404` stays bodyless (AD-5), stock JDK exceptions are mapped without forcing call-site migration (AD-6), `UpstreamLoginException` wraps the websocket-parser library best-effort (AD-7), `IllegalStateException` has the transitional 500-with-body mapping (AD-8), partial-registration semantics are untouched (AD-9), and the test deliverable shape (advice-level `RestExceptionHandlerTest` + per-controller MockMvc tests with `@Import(RestExceptionHandler.class)`) matches AD-10. The 6 drift points from the dev report are legitimate technical realities the plan could not have anticipated (RESTART_LIFECYCLE_FIX and CLEANUP_SMALL landed in between plan and execution); they are documented as plan amendments and do not warrant rework.

## Phase-by-phase

### Phase A — Introduce exception hierarchy + `@RestControllerAdvice`; migrate one exemplar
Status: **implemented** (with note)

- All four new exception classes exist under `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/common/exception/`:
  - `BadRequestException` (RuntimeException; constructor with/without cause).
  - `UpstreamGatewayException` (abstract, carries `type` discriminator).
  - `UpstreamRegistrationException` (`type = "Game server error"`).
  - `UpstreamLoginException` (`type = "Game server error"`).
- `RestExceptionHandler` is a `@RestControllerAdvice` mapping every row of the AD-6 table: `ResourceNotFoundException → 404` bodyless, `BadRequestException`/`IllegalArgumentException`/`MethodArgumentTypeMismatchException`/`HttpMessageNotReadableException → 400` with `{type:"Bad request", msg:…}`, `UpstreamGatewayException (incl. subclasses) → 502` with `{type:e.getType(), msg:…}`, `IllegalStateException → 500` with body (transitional per AD-8), terminal `Exception → 500` with null-safe message.
- `BotGroupService.save` throws `UpstreamRegistrationException` on `isCompleteFailure()` (line 139); Javadoc updated to `@throws UpstreamRegistrationException`.
- `RestExceptionHandlerTest` exists with 9 test methods covering each handled type via a tiny inner `TestController` stub, using `@Import({RestExceptionHandler.class, TestController.class})` for explicit advice wiring (the safe path called out in Implementation Notes #1).

Note: Phase A's "step 3" claim that the legacy controller catch ladder remains harmless dead code in the period between Phase A and Phase B is incorrect for this codebase — the old `catch (IllegalStateException) → 502` would no longer fire and the terminal `catch (Exception) → empty 500` would have re-emerged. Dev mitigated by shipping A and B as a single deploy unit. Captured as plan amendment #1.

### Phase B — Migrate all controllers; type the remaining throw sites
Status: **implemented**

- Controller `try/catch` blocks deleted to zero:
  - `BotGroupController`: 14 → 0.
  - `EnvironmentController`: 8 → 0.
  - `GameController`: 8 → 0.
  - `SessionHistoryController`: 6 → 0.
  - `BrandController`: 0 → 0 (had none — correctly skipped, plan n/a).
  - Total removed: 36, matching the plan's stated count.
- Service-layer throws retyped per plan:
  - `BotGroupBehaviorService.start` (lines 193, 201): `IllegalStateException` → `BadRequestException` for `environmentId == null` / `gameId == null` pre-flight checks.
  - `BotGroupBehaviorService.start` outer try/catch refactored to `try/finally` with `boolean started` flag (Phase B step 6) — original exception type propagates intact to advice; cleanup runs on every failure path.
  - `BotGroupBehaviorService.stop`: outer try/catch dropped, MDC wrapped in `try/finally`.
  - `BotGroupBehaviorService.scheduleRestart` (line 542): `IllegalArgumentException` → `BadRequestException` (`"Scheduled time must be in the future"`).
  - `ApiGatewayClient.authenticate` (line 159): catches the websocket-parser `RuntimeException` and rethrows as `UpstreamLoginException("Login failed for user '<u>': <library-msg>", cause)` while still calling `metrics.incLogin(false)`.
- `classifyCreationFailure` (introduced by RESTART_LIFECYCLE_FIX Phase 1, post-dates this plan) was extended with explicit `instanceof UpstreamLoginException → "auth"` and `instanceof BadRequestException → "validation"` arms so the bot-creation-failures Prometheus metric stays correctly labelled. Plan-consistent.
- Test wiring: every migrated `*ControllerTest` (BotGroup/Environment/Game/SessionHistory) declares `@Import(RestExceptionHandler.class)`. `BotGroupControllerTest` exercises the new envelope explicitly (`UpstreamRegistrationException → 502 + {type:"Game server error",…}`, `BadRequestException → 400 + body`, `ResourceNotFoundException → 404` bodyless, `UpstreamLoginException` via stubbed `behaviorService.start → 502`).
- `mvn test -Dtest='RestExceptionHandlerTest,BotGroupControllerTest,EnvironmentControllerTest,GameControllerTest,SessionHistoryControllerTest'` → 80 tests, 0 failures, 0 errors.

### Phase C — Partial-registration cohort handling
Status: **out-of-scope (deferred)** — per plan AD-9 and CLAUDE.md backlog. No changes expected; none observed. `BotGroupService.save:142-152` still logs the partial-success warning and proceeds, exactly as before.

## Drift

None requiring rework. All six dev-noted drift points are documented as compliance amendments on the plan itself:

1. Phase A step 3's "legacy catch ladder safely covers the new type" claim was incorrect in hindsight — mitigated by shipping A+B together.
2. `ApiGatewayClient.registerSingleUser` was renamed to `registerSingleUserDirectly` by `CLEANUP_SMALL` Phase 2A after the plan was written; plan references should be read as the renamed symbol.
3. `BotGroupBehaviorService.classifyCreationFailure` post-dates the plan; Phase B added typed-exception arms to it (plan-consistent extension).
4. "36 catch blocks across 4 controllers" verified exact: 14 + 8 + 8 + 6 = 36; Brand n/a.
5. `validateUsernameLength` was retyped from `IllegalArgumentException` to `BadRequestException` (AD-6 allowed either).
6. One `IllegalStateException` remains in `BotGroupBehaviorService.restart` (line 527, RESTART_LIFECYCLE_FIX Decision 6 fail-loud) — legitimate non-upstream use, advice maps it to 500 + body per AD-8.

## Out-of-scope changes

None introduced by the two commits under review. The branch diff vs `main` does carry unrelated changes (CLAUDE.md edits, OBSERVABILITY plan, RESTART_LIFECYCLE_FIX QA notes, Tip product additions, BotMetrics expansion, Stub.java, etc.) but those are from earlier commits already merged through other plans; they are outside this plan's scope and outside this verdict's scope.

## Amendments to the plan

Appended a `## Compliance Amendments — 2026-06-11` section at the end of `/Users/gleb/IdeaProjects/Bot/docs/plans/API_ERROR_FORWARDING.md` capturing the six drift findings above. Plan body unchanged.

Compliance verdict: PASS-with-notes
docs/reviews/API_ERROR_FORWARDING/compliance.md written.
docs/plans/API_ERROR_FORWARDING.md amended (Compliance Amendments section appended).
