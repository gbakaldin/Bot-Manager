# Compliance — TECH_DEBT_PASS_2

Branch: `chore/tech-debt-pass-2`
Plan reviewed: `docs/plans/TECH_DEBT_PASS_2.md` (at commit 3a2f23c)
Diff reviewed: `git diff main..chore/tech-debt-pass-2` (5 commits: 82ceb16, fd1a18e, 6a0fd54, 19d7fe1, 3a2f23c)

## Verdict

PASS

## Phase-by-phase

### Phase 1 — Delete stale TODO in `Starter.java` (Item 2)
Status: implemented
Notes: Commit 82ceb16 removes exactly the trailing `/* TODO: IMPORTANT ... */`
block and the preceding blank line; the file now ends cleanly at the class
closing brace. Nothing else in `Starter.java` touched. Matches AD-2.

### Phase 2 — Swagger examples in `EnvironmentController` (Item 3)
Status: implemented
Notes: Commit fd1a18e replaces the three `example = "N/A"` placeholders and
deletes the `//TODO: pending example` comments on `filter`, `save`, `update`.
Each JSON example is byte-for-byte the AD-3 string. `productCode` is the code
STRING form (`"097"`) in all three — matches the `@JsonCreator fromCode(String)`
request-deserialisation contract per Findings/AD-3. `description = "..."` text
preserved on each `@Parameter`. No other controller members changed.

### Phase 3 — Remove deprecated `GameMsClient`/`ClientFactory` members (Item 1)
Status: implemented
Notes: Commit 6a0fd54 removes exactly the 8 `@Deprecated` GameMsClient members
(no-arg ctor, `agencyToken`/`agencyId`/`uuid`/`memberId` fields,
`deposit(long, Consumer)`, private `fetchTokenDetails()`, `setAgencyToken(String)`)
and the `ClientFactory` no-arg `newClient()`. Verified:
- Zero `@Deprecated` members remain in either file.
- KEPT (per AD-1.2): `GameMsClient(String)` ctor, stateless
  `deposit(String, long, Consumer)`, `fetchTokenDetails(String)`,
  `newClient(TokensProvider, String)`, and the inner types — all present.
- `Consumer` import STAYS (still referenced by stateless deposit) — matches
  AD-1.4.
- No non-deprecated member removed.
- No stray references to any deleted member anywhere in `src/`.
- `mvn -DskipTests compile` succeeds — the audit gate (clean compile = zero
  callers) is satisfied.

### Phase 4 — `BotManagerException` base + one throw migration (Item 4)
Status: implemented
Notes: Commit 19d7fe1:
- New `BotManagerException` is `abstract extends RuntimeException` with
  `(String)` and `(String, Throwable)` protected ctors and the class-level
  Javadoc stating it is the domain-exception root and that
  `RestExceptionHandler` maps concrete subtypes (AD-4.1).
- `ResourceNotFoundException`, `BadRequestException`, `UpstreamGatewayException`
  reparented onto `BotManagerException` (AD-4.2); subclasses of
  `UpstreamGatewayException` unchanged.
- EXACTLY ONE throw migrated: `EnvironmentService.validateAndMergeWsHeaders`
  `IllegalArgumentException` → `BadRequestException`, same message, import added
  (AD-4.5). HTTP 400 preserved (was 400 via IllegalArg fallback, now 400 via the
  typed `BadRequestException` arm).
- `RestExceptionHandler` NOT modified (confirmed: zero diff on that file) —
  AD-4.3/AD-4.4 honored; the `IllegalArgumentException→400` fallback arm stays.
- The 30+ deferred factory/strategy raw throws (AD-4.6) left untouched — intended.
- `RestExceptionHandlerTest` (20 tests), `EnvironmentControllerTest`, and
  `EnvironmentServiceTest` all green (58/58) — the HTTP contract holds.

### Item 5 — `Bot.java` legacy methods (delete approved pair)
Status: implemented
Notes: Commit 3a2f23c deletes EXACTLY `connectToSocket()` and `authenticate()`
from `Bot.java` — the user-approved dead pair. `restart()`, `logout()`,
`stop()`, `cleanup()`, `initialize()` are untouched. No `restart()`→
reconnect-machinery merge attempted (correctly deferred per the recommendation
table). No caller of either deleted method exists anywhere in `src/` (the two
`.authenticate()` grep hits are `AuthClient.authenticate()` / a doc comment,
not `Bot.authenticate()`).

## Dev-flagged deviation — `EnvironmentServiceTest` assertion updates

Assessment: **necessary consequence of the approved AD-4.5 throw-migration, not
scope creep.**

The three assertions in `EnvironmentServiceTest` (Save-path bad-header cases)
changed `isInstanceOf(IllegalArgumentException.class)` →
`isInstanceOf(BadRequestException.class)`. These are unit tests asserting on the
exception thrown *directly* by `service.save()`, so once AD-4.5 changed the
thrown type they had to track the new type or they would fail to compile-match
the runtime type. The changes are behavior-preserving:
- Same three assertions still verify the throw occurs, the message contains
  `Host`/`Origin`, and `repository.save(...)` is never called.
- Only the asserted exception type moved to `BadRequestException` — exactly the
  type AD-4.5 mandated. No HTTP status change (still 400), no observable
  behavior change. `BadRequestException extends BotManagerException extends
  RuntimeException`, so the new instance-of assertion is correct and strictly
  narrower.

This is the minimal edit required to keep the suite green under the approved
migration — the correct implementation of AD-4.5 *entails* it. Dev handled it
correctly and flagged it transparently.

Note on the plan's Findings (documentation-only gap, non-blocking): the Findings
"Tests that lock in HTTP/exception behavior" bullet stated *"No test in `src/test`
uses `assertThrows(SomeSpecificException.class)` against our exception types (grep
returned zero)."* That grep was too narrow — these three service-layer unit tests
assert the type via AssertJ's `assertThatThrownBy(...).isInstanceOf(...)`, not
`assertThrows(...)`, so they were not surfaced by the Findings scan. This does
**not** invalidate the plan's approach (the migration was correct and possible,
the tests assert the same behavior via the new type) and is not a plan-level
technical mistake that would force a re-plan — hence PASS rather than
PLAN_AMENDED. Recorded here for the record; no plan edit issued.

## Out-of-scope changes

None. The diff touches only the files named in the plan's phase file lists, plus
the three `EnvironmentServiceTest` assertions justified above. No production
code, build files, or other planning docs were altered beyond scope.

## Drift

None material. See the Findings documentation-gap note above (non-blocking).
