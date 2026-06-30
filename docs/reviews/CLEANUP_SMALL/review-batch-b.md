# Code Review — CLEANUP_SMALL (Batch B: username length pre-flight)

Branch: `feat/cleanup-small`
Reviewed diff: `git show 954f079` (3 files: `ProductCode.java`, `BotGroupService.java`, `BotGroupServiceTest.java`)

## Verdict

PASS

No `bug` or `security` findings. Two `smell`-level notes below for the author's consideration.

## Findings

### [smell] Crafted error message is discarded by the controller

`src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java:113-114`

The whole point of the brief is that the new exception "carries product code, prefix, count, computed max length, and the cap" so the operator gets actionable feedback in the HTTP response. The thrown message is good — it interpolates all five tokens and even hints at the remediation ("Shorten the prefix or reduce the bot count.").

But the controller's `save` handler swallows it:

```java
} catch (IllegalArgumentException e) {
    return ResponseEntity.badRequest().build();
}
```

`.build()` produces a 400 with **no body**. The carefully composed string never leaves the JVM — clients see a bare status line and have to read server logs to find out what went wrong. Compare with the sibling branch a few lines down, which wraps `IllegalStateException` in an `ErrorResponse`:

```java
return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(new ErrorResponse("Game server error", e.getMessage()));
```

The simplest fix is the same shape:

```java
return ResponseEntity.badRequest()
        .body(new ErrorResponse("Invalid bot group", e.getMessage()));
```

Note: this is **pre-existing controller behaviour**, not regressed by the commit, and applies to *every* `IllegalArgumentException` thrown by the service layer. But since the brief explicitly calls out a "clean 400" with the descriptive payload, it's worth flagging here so the loop closes properly. CLAUDE.md's "API_ERROR_FORWARDING" backlog item already plans to fix the broader pattern via `@RestControllerAdvice` — this finding falls into that bucket.

### [smell] Duplicate `findById` round-trip on the happy path

`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java:181` and `src/main/java/com/vingame/bot/config/client/EnvironmentClientRegistry.java:120`

`validateUsernameLength` calls `environmentService.findById(envId)`, and immediately after, `clientRegistry.getClients(envId)` calls `environmentService.findById(envId)` again inside `createClients` (only on first registry use, which is the common case when creating a new group against a fresh env).

Two Mongo round-trips per bot-group create where one would do. Cheap as far as smells go, but unnecessary. Options:

1. Look the `Environment` up once in `save()` and pass it into both `validateUsernameLength(group, env)` and a new `getClients(env)` overload.
2. Add a package-private `getCachedEnvironment(envId)` on `EnvironmentClientRegistry` and have validation consult that.

Not worth blocking on; raising for awareness so it doesn't get baked in.

## Notes

**Cap location (`ProductCode` vs `AuthProfile`).** Right call. `AuthProfile` is per-auth-flow (paths, x-token, login factory) — a username cap is not flow-state, it's a brand-static invariant just like `appId`. `ProductCode` already owns that kind of data, so the new field is colocated with its closest siblings. No better home.

**Cap value (12, inclusive).** The CLAUDE.md backlog entry codifies the formula as `namePrefix.length() + String.valueOf(botCount).length() > cap`. The implementation matches: `maxLength > cap` (strict greater-than), so a 12-char username is accepted and a 13-char one is rejected. The test (`shouldAcceptWhenWithinCap` uses prefix=`authtest` (8) + count=`9999` (4 digits) = 12) confirms inclusive treatment of the boundary. Nothing in the existing codebase contradicts 12 as the limit — CLAUDE.md is the only prior reference and it agrees.

**Length math (`String.valueOf(botCount).length()`).** Verified against `ApiGatewayClient.registerUsers` (line 186-188): `for (int i = 1; i <= count; i++) { final String username = userNamePrefix + userIndex; }`. The longest generated username is `prefix + count` (1-based loop, inclusive). `String.valueOf(botCount).length()` is the correct expression — using `botCount - 1` would be wrong because the loop goes up to and including `count`.

**Migration-path skip (`skipRegistration=true`).** Correct. The migration path persists bot rows referring to pre-existing gateway accounts; the cap is enforced by the gateway at registration time, and on the migration path the gateway is never contacted. Whatever usernames are being migrated have already passed the gateway's own check (or the migration source is wrong, which is out of scope for this validator). Mongo has no length constraint of its own. Skipping is the right behaviour and the test `shouldSkipValidationOnMigrationPath` asserts `environmentService.findById` is never invoked — good defensive assertion.

**Error message coverage.** `shouldRejectWhenExceedsCap` asserts the message contains "P_116", "authtestws", "999", "13", and "12" — that's the product code, prefix, count, computed max length, and cap respectively. All five tokens per the brief. ✓

**Existing test stub updates.** The six pre-existing tests in `RegistrationTests` / `SaveTests` that hit the save path each got one new line: `when(environmentService.findById("env-1")).thenReturn(envWithoutCap())`. The helper returns `Environment.builder().productCode(P_097).build()` and P_097 has `usernameMaxLength=null`, so validation no-ops. None of those tests previously asserted "validation does NOT run" — they exercise registration outcomes (success / fail / partial), which the cap-less env doesn't disturb. No behavioral change being masked.

**Edge cases handled correctly.**
- `productCode == null` → no-op (defensive — `Environment.productCode` is nullable today).
- `cap == null` → no-op (the common case for 9 of 10 products).
- `prefix == null` → treated as length 0 (defensive; in practice the entity should reject this earlier, but the guard is cheap).
- `botCount == 0` → `String.valueOf(0).length() == 1`, so a zero-count group passes any cap ≥ prefix length + 1. Fine.

**Out-of-scope but noticed.** `BotGroupService.update` does not run the validator. If an operator later PATCHes `botCount` or `namePrefix` upward, they can land in a state where the persisted group's longest username would now exceed the cap. Updates don't re-trigger gateway registration so the bots still work, but future bot-creation logic depending on the prefix could surprise. Not a finding — updating those fields on an existing group is unusual and the brief doesn't ask for it.
