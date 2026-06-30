# Code Review — CLEANUP_SMALL

Branch: feat/cleanup-small
Reviewed diff: `git diff main..feat/cleanup-small` (single commit `2d71941`)

## Verdict

PASS

PASS = no `bug` or `security` findings, smells/styles are advisory.

The dead-branch verification is airtight for the live call path. The simplification is safe in production today.

## Findings

### [smell] Inconsistent xToken-null defensive coding across the class
`src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:137,253,352`

The dead-branch argument ("all `AuthProfile` values produced for live environments have a non-null `xToken`, therefore `xToken != null` is always true at runtime") is correct — verified by reading `AuthStrategyFactory.getAuthProfile`, which is the only production producer of `AuthProfile`. The switch is exhaustive over `ProductCode` (all 10 enum values), and every case hardcodes `"58bc2820612d23c34fe43d0b2c6f7223"`.

If that argument is the rationale for collapsing the branch in `registerSingleUser`, the same argument applies to the other three null-checks left in the file:
- `authenticate` (line 137): `xToken != null ? xToken : "(none)"` — still defensive.
- `registerSingleUserWithDisplayName` (line 253): `if (xToken == null) { verifyToken(...) }` — entire `verifyToken` branch is now structurally unreachable.
- `setDisplayName` (line 352): `if (xToken != null) { ... } else { ... }` — same as the branch just deleted from `registerSingleUser`, still present here.

After this commit, the class is half-cleaned: one method assumes `xToken` is always non-null, three others still branch on it. Pick one model. Either delete all four checks (and `verifyToken` along with them — it has no other caller), or revert this commit. The mixed state is the worst of both worlds: a future reader has to re-derive the invariant to know which checks are live and which are vestigial.

### [smell] Legacy `init(apiGateway, appId, loginRequestFactory)` overload is now actively misleading
`src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:102-107`

Confirming the dev's out-of-scope flag: the overload at line 103 is uncalled. `grep` for `\.init(` across `src/main` and `src/test` returns exactly one production hit (`EnvironmentClientRegistry.java:129`), which uses the 3-arg `AuthProfile` form. No test exercises it either.

This overload constructs an `AuthProfile` with `xToken = null`. After this commit, if anyone were to wire it up, `registerSingleUser` would call `requestBuilder.header(SESSION_TOKEN_HEADER, xToken)` with a null value, which throws NPE inside JDK `HttpRequest.Builder.header` before the request is ever sent. So the overload's contract ("backward-compatible, no X-TOKEN") is now silently broken. It is still safe today only because nothing calls it.

Not a bug right now — flagging as a smell because the comment on line 102 (`Backward-compatible overload — uses standard user endpoints and no X-TOKEN.`) is no longer truthful, and the overload should be deleted in the next batch.

### [style] Log line behavior change for an "unreachable" code path
`src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:313-314`

Pre-fix: `xToken != null ? xToken : "(none)"`. Post-fix: passes `xToken` directly. If the unreachable-via-overload path were ever exercised, the log would emit `X-TOKEN: null` (SLF4J formats null as the string `"null"`) instead of the previous `"(none)"`. Cosmetic, consistent with the dev's "this branch can't happen" stance, but worth noting in case the assumption ever breaks — `null` in a log field is a confusing signal vs. an explicit sentinel.

## Notes

- Cross-checked `AuthStrategyFactory` (`src/main/java/com/vingame/bot/infrastructure/auth/AuthStrategyFactory.java:24-53`): switch covers all 10 `ProductCode` values, every branch returns an `AuthProfile` with a literal non-null xToken. Since the switch is exhaustive over an enum, no default case is needed and javac will fail-fast if a new `ProductCode` is added without a corresponding `AuthProfile`. This is the right kind of compile-time guard for the invariant being relied on here.
- Searched all `new AuthProfile(...)` call sites: 4 in `AuthStrategyFactory` (all non-null xToken) plus 1 in the dead legacy `init` overload (null xToken). No other producers exist. Verification is complete.
- `fingerprint` local in `registerSingleUser` (line 294) is still used at line 336 in the returned `RegistrationResult` — not orphaned by the deletion.
- `Stub.java` deletion from working tree confirmed (`ls src/main/java/com/vingame/bot/common/` returns only `dto`, `exception`, `logging`). Since it was untracked, there's nothing to review in the diff.
- Pre-existing concern, not introduced here: `[Register] POST {} | X-TOKEN: {}` (line 313) and the equivalent in `authenticate` (line 137) and `setDisplayName` (line 362) log the full xToken in plain text. The CLAUDE.md guidance says full tokens should not be logged. The xToken is a static brand-shared secret (not a per-user token), so the leak surface is bounded, but it still violates the convention. Out of scope for this commit — flagging only because the line was touched.
- Tests reported 401/401 green. Not verified independently (QA's job), just noted.

Review verdict: PASS
Findings: 0 bug, 0 security, 2 smell, 1 style
