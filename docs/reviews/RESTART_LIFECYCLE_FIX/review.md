# Code Review — RESTART_LIFECYCLE_FIX

Branch: `feat/observability-metrics` (carrying the RESTART_LIFECYCLE_FIX commits `86611d8` + `bfa375d` on top of an existing observability feature branch)
Reviewed diff: `git diff 86611d8^..bfa375d` — the two RESTART_LIFECYCLE_FIX commits only (the rest of the branch is out of scope per the user's request).

## Verdict

CHANGES_REQUESTED

One `bug` finding on the new metric (it silently emits unlabeled series, defeating Decision 5's per-group cardinality goal). One `smell` on the restart() throw leaving the DB in `targetStatus=ACTIVE`. One `smell` on the auth/validation classifier overlap. Remaining items are advisory.

## Findings

### [bug] `bot_creation_failures_total` is emitted without `botGroupId`/`environmentId`/`gameType` tags
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:322`
`src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java:146-152`

`BotMetrics.incBotCreationFailure(reason)` pulls per-bot identity from MDC via `mdcTags()` (same as every other `bot_*` counter). The Javadoc at `BotMetrics.java:66` explicitly promises "Same MDC-driven per-bot tag shape as the rest."

The call site at `BotGroupBehaviorService.java:322` is inside the result-collection loop of `createBotsInParallel` (lines 311-325). That loop runs on the **caller** thread of `createBotsInParallel` — i.e. the `start(id)` thread — not on the per-bot virtual thread that ran the lambda at lines 287-302. The MDC `BotMdc.setGroupContext(...)` at line 288 is set inside the lambda and cleared in its `finally` at line 300, so by the time the catch block at line 315 runs on the caller thread, MDC is empty.

Result: the counter is registered with only `{reason=…}`. The series cannot be sliced by group/env/gameType in Prometheus, which is exactly the alerting capability Decision 5 of the plan claims this counter provides ("turns 'next restart silently goes to zero bots' into a Prometheus alert" — but the alert can't tell *which* group). The log line at lines 319-321 carries the group/env, but the metric does not.

Fix shape: set `BotMdc.setGroupContext(group.getId(), group.getEnvironmentId())` on the caller thread for the duration of the collection loop (with a matching `BotMdc.clear()` in a `finally`), mirroring the pattern already used at lines 251-256 in the `start()` catch block and 422-427 in `stop()`. Alternatively, pass the group/env IDs explicitly into `incBotCreationFailure` and tag them on the counter inside `BotMetrics`. Either fix verifies via a `BotMdcTagsMeterFilter`-style assertion in `BotGroupBehaviorServiceTest`.

### [smell] `restart()` throws after `start()` has already persisted `targetStatus=ACTIVE` with a zero-bot runtime
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:469-482`

`start(id)` at line 469 persists `targetStatus=ACTIVE` (line 233 of the file) before returning. The post-start zero-bot check at lines 478-481 then throws `IllegalStateException`. The controller at `BotGroupController.java:199-201` catches `Exception` and returns HTTP 500 with no body. After this path:
- DB: `targetStatus=ACTIVE`, `lastStartedAt` updated, `lastStoppedAt=null`.
- Runtime: `runningGroups.get(id)` contains a runtime with zero bots (or null).
- Health endpoint will report 0 bots with `targetStatus=ACTIVE` — the exact "DB is lying" state the plan's Findings section called out (`docs/plans/RESTART_LIFECYCLE_FIX.md:86`).

The fix makes the bug *visible* (via the exception + log + metric) but doesn't *resolve* it: the operator still has to manually stop the group to clear the inconsistent runtime/DB state. The plan's Architecture Decision 6 explicitly accepts this ("The controller already returns 500 on Exception; this turns silent failure into a 500-with-cause-in-logs"), so this is by design — but it's worth raising because (a) the next health-monitor sweep will read this state and mark the group DEAD on the runtime side, possibly racing with the exception path; (b) a follow-up POST `/restart` from the operator will hit the "already running" early return at line 180 because `runningGroups.containsKey(id)` is still true.

Fix shape: either clean up the runtime entry + persist `targetStatus=DEAD` (or `STOPPED`) before throwing, or document the post-throw state explicitly in the Javadoc at lines 447-457 so the operator knows the recovery procedure is "POST /stop, then POST /start" rather than "retry /restart". The latter is simpler.

### [smell] Reason classifier silently swallows websocket-parser's `ValidationException` into the "auth" bucket
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:340-355`

The classifier checks `IllegalStateException || IllegalArgumentException` for "validation", then falls through to a substring search for "auth"/"login"/"token" on class name and message. `com.vingame.websocketparser.exception.ValidationException` (the original symptom of this bug pre-fix) extends `WebSocketParserException`, not `IllegalStateException` — so its message `"Authentication configuration is required..."` lands in the "auth" bucket because `message.contains("auth")` is true after `toLowerCase()`.

This is misleading. A builder-validation failure (missing config) is semantically `validation`, not `auth` (which should mean "the credentials were wrong"). For the current fix this is unreachable — `BotFactory` now throws `IllegalStateException` before the WS builder validates — but the classifier remains brittle:
1. Any new exception type with "token" or "auth" in its message (e.g. a server returning "TokenInvalid" vs "TokenExpired" — both currently "auth") cannot be distinguished.
2. A future regression that introduces a different `ValidationException` path would silently mislabel as "auth", obscuring the actual failure family.

Fix shape: either import `com.vingame.websocketparser.exception.ValidationException` and add a fourth `instanceof` arm classifying it as "validation", or invert the order (substring check first, then `instanceof`) to make the priority explicit. Even better, declare the contract on `BotMetrics.BOT_CREATION_FAILURES_TOTAL` for what each reason *means* so future contributors don't have to reverse-engineer it from the classifier.

### [smell] Fail-loud message in `BotFactory.createBot` NPEs on `game.getGameType()` when game is null
`src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java:103-114`

The resolver `Environment.resolveZoneName(Game)` is documented and tested to be null-safe on its `game` argument — it returns `cardZoneName` / `DEFAULT_CARD_ZONE_NAME` when `game==null`. But the `IllegalStateException` formatter at line 110 calls `game.getGameType()` unconditionally. In the `customZone=true, game=null, cardZoneName=null` path, the resolver returns null → the if-branch is taken → the format call NPEs while constructing the error message, masking the real misconfiguration.

This is a degenerate input (game-null at this depth is implausible — `gameService.findById` would have thrown earlier), so impact is low. But the comment at line 100 ("Only reachable when customZone=true with a blank custom field") understates the reachable conditions. Either guard the format call (`game != null ? game.getGameType() : null`), or remove the false reassurance from the comment.

Note: `BotFactory.java:87` (`game.getName()`) already has the same latent NPE — pre-existing, not introduced by this fix.

### [style] Trailing-throwable logging at line 319 uses `cause.toString()` as a placeholder argument
`src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:319-321`

```java
log.error("Failed to create bot {}/{} for group {} (env {}): {}",
        i + 1, botCount, group.getId(), group.getEnvironmentId(),
        cause.toString(), cause);
```

The 5th `{}` consumes `cause.toString()` (e.g. `java.lang.RuntimeException: ...`), and the 6th argument is the throwable for the stack trace — SLF4J infers it as the bound throwable when arg count > placeholder count. This is correct, but `cause.toString()` is redundant — the existing codebase convention (`BotGroupRuntime.java:141`, `:234`, `:249`; `GameMsClient.java:110`; `BotGroupBehaviorService.java:241`) is `log.error("...{}", arg, e)` where `e.getMessage()` (not `e.toString()`) is the placeholder value, or the throwable is the only trailing arg.

Two equivalent idiomatic forms:
- `log.error("Failed to create bot {}/{} for group {} (env {})", i + 1, botCount, group.getId(), group.getEnvironmentId(), cause);` — stack trace carries the class+message, no need to spell it out twice.
- `log.error("Failed to create bot {}/{} for group {} (env {}): {}", i + 1, botCount, group.getId(), group.getEnvironmentId(), cause.getMessage(), cause);` — matches line 241 / 442 style.

Cosmetic, but worth aligning with the rest of the file.

### [style] Two-step `String.format` of long multi-line message in `IllegalStateException`
`src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java:103-114`

`String.format` with 6 `%s` placeholders and a multi-line concatenated template is harder to scan than a `String.join` or a text block. Adjacent throws in the codebase use one-line messages (`BotGroupBehaviorService.java:190-193`, `:198-201`). Not a correctness issue — just less consistent with the surrounding style.

### [style] `Environment` constants live in the entity class — orientation comment is in plan, not in code
`src/main/java/com/vingame/bot/domain/environment/model/Environment.java:25-37`

Decision 2 of the plan ("defaults live on `Environment`") is reasonable, but the entity-vs-utility tension is real: a Mongo `@Document` carrying business-logic constants is unusual in this codebase (`ProductCode`, `BrandCode` etc. live in `domain.brand.model` as enums; everything else either is or wraps an entity). The Javadoc says "RESTART_LIFECYCLE_FIX Architecture Decision 2" without spelling out why the constants belong here. A one-line "default product zone, used when `customZone=false`" would help the next reader who hasn't read the plan. Not a correctness issue.

## Notes

- The resolver `Environment.resolveZoneName(Game)` is genuinely null-safe on `game`, well-tested (`EnvironmentZoneResolutionTest` covers all four quadrants + the SLOT/TAI_XIU/UP_DOWN parameterised case), and the discriminator-in-one-place property (Decision 1) holds — anyone adding a new mini-game type updates the resolver and the parameterised test in one place.
- The dead-line deletion in `EnvironmentClientRegistry` (`setZoneName` at the old line 138) was verified safe: `grep` across `src/main` and `src/test` confirms `ClientFactory.setZoneName` is only invoked by `BotFactory` post-fix, and `ClientFactory.getZoneName` has no readers in this repo. The inline comment at lines 134-141 is excellent — future contributors won't reinstate the line by reflex.
- One-direction package dependency `domain.environment` → `domain.game` introduced by importing `Game` + `GameType` into `Environment`. Verified no reverse edge (`grep "domain.environment" src/main/java/.../domain/game/` returns nothing). No cycle.
- `BotGroupBehaviorServiceRestartTest` covers both behaviors well: `restart_recreatesBotsAfterStop` proves symmetry (the symptom of the original bug — bots not being recreated — is now regression-locked), and `restart_failsLoudlyWhenZeroBotsCreated` asserts the new throw with the `0/3` substring. Mock setup is clean (`lenient()` only where needed, no over-stubbing).
- The cached `EnvironmentClients.environment` reference in the registry is not refreshed when an admin updates the env in Mongo — a pre-existing concern, not introduced by this fix. Worth flagging because the resolver's correctness depends on the env reference being current. If an operator flips `customZone=false → true` on a live env without restarting the bot manager, the resolver still sees the stale `customZone=false` and uses defaults instead of the newly-populated custom field. Not in scope for this review.
- The plan's "Open Items" left the resolver location (entity method vs. static utility) deferred to Dev — Dev picked the entity method, which is fine. No conflict with codebase style.
- Suggest a Releaser/QA verification step that explicitly inspects the Prometheus output after a synthetic failed restart, e.g. `curl /actuator/prometheus | grep bot_creation_failures_total`, and confirms whether `botGroupId` / `environmentId` labels are present. That single check would have caught the [bug] finding above before Releaser.
