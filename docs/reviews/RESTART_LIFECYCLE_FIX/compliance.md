# Compliance — RESTART_LIFECYCLE_FIX

Branch: `feat/restart-lifecycle-fix`
Plan reviewed: `docs/plans/RESTART_LIFECYCLE_FIX.md` (HEAD revision on branch)
Diff reviewed: `git diff main..feat/restart-lifecycle-fix` (commits `86611d8` Phase 1 tests, `bfa375d` Phase 2 fix)

## Verdict

PASS

---

## Phase-by-phase

### Phase 1 — Add the tests
Status: implemented
Notes:
- Phase 1 committed separately as `86611d8` *before* any production change, exactly as the plan requires ("Commit the failing tests as a separate Phase-1 commit before any production change. This is the regression net.").
- `EnvironmentZoneResolutionTest.java` covers all five planned cases: defaults for mini+false, defaults for card+false, custom for mini+true, custom for card+true, and the parameterised `SLOT`/`TAI_XIU`/`UP_DOWN` → `"Simms"` pin. Test #5 (the parameterised one) is faithful to the plan's wording.
- `BotGroupBehaviorServiceRestartTest.java` contains both planned tests: `restart_recreatesBotsAfterStop` (baseline symmetry — passes on main) and `restart_failsLoudlyWhenZeroBotsCreated` (the regression hook, must fail on main and pass after Phase 2). The assertion `.hasMessageContaining("0/3")` is exactly the contract the plan calls for.
- Targeted re-run on current branch: `EnvironmentZoneResolutionTest` 7 cases pass (4 explicit + 3 parameterised), `BotGroupBehaviorServiceRestartTest` 2 cases pass.

### Phase 2 — Fix
Status: implemented
Notes:
- **`Environment.resolveZoneName(Game)` + constants** at `src/main/java/com/vingame/bot/domain/environment/model/Environment.java`. Constants `DEFAULT_MINI_ZONE_NAME = "MiniGame"` and `DEFAULT_CARD_ZONE_NAME = "Simms"` declared as `public static final`. Resolver body matches the plan's pseudocode verbatim; null-`game` is treated as the card/default branch (plan-conformant), and `customZone=true` with a null custom field returns `null` so callers can surface the misconfig (matches Javadoc on the resolver).
- **`BotFactory.createBot`** at `BotFactory.java:96` — `freshClientFactory.setZoneName(env.getMiniZoneName())` swapped to `freshClientFactory.setZoneName(resolvedZoneName)`. Decision 4 fail-loud guard precedes the swap and throws `IllegalStateException` naming environmentId, username, gameType, customZone flag, miniZoneName, cardZoneName — fully informative, exactly as Decision 4 specifies.
- **`BotGroupBehaviorService.createBot`** at line 397 (was `:369` pre-diff) — `.zoneName(environment.getMiniZoneName())` swapped to `.zoneName(environment.resolveZoneName(game))`. `game` is already in scope at the call site.
- **`EnvironmentClientRegistry.createClients`** at line 131 area — `clientFactory.setZoneName(env.getMiniZoneName())` deleted (option (a) from the plan), with an inline comment block explaining why the cached factory must not carry a zoneName. See "Open implementation calls" below.
- **`createBotsInParallel`** catch block (lines 313–319 post-diff) — now unwraps `CompletionException` cause, logs with group ID + env ID + cause toString + stacktrace, and increments `botMetrics.incBotCreationFailure(reason)`. Reason classification is implemented as `classifyCreationFailure(Throwable)` with bounded labels (`validation | auth | unknown`). Auth detection is heuristic (class-name / message containing `auth`/`login`/`token`), which is a sensible execution of Decision 5's intent — the plan acknowledged the absence of a single auth-exception type and called for a bounded label set.
- **`restart()`** at lines 445–481 post-diff — after `start(id)`, re-reads `BotGroup` from `botGroupService.findById(id)` and `runtime` from `runningGroups.get(id)`, and throws `IllegalStateException("Restart of group %s produced %d/%d bots; check logs and %s metric for cause", id, alive, botCount, BotMetrics.BOT_CREATION_FAILURES_TOTAL)` if `alive == 0 && botCount > 0`. Message contains `0/N` as the Phase-1 test asserts. Decision 6 satisfied.
- **`BotMetrics`** — new constant `BOT_CREATION_FAILURES_TOTAL = "bot_creation_failures_total"` and method `incBotCreationFailure(String reason)` that calls `Counter.builder(...).tag("reason", reason).tags(mdcTags()).register(registry).increment()`. Matches Decision 5 verbatim — `{environmentId, botGroupId, reason}` shape comes via the MDC tags pattern used elsewhere in `BotMetrics`.

### Phase 3 — Update existing tests + run full suite
Status: implemented
Notes:
- `BotGroupBehaviorServiceTest$StartCreateBotFailureTests` received the documentation comment the plan specified, clarifying that the zero-bot-tolerance behavior covers direct `start` only and pointing at `BotGroupBehaviorServiceRestartTest` for the stricter `restart` contract. Test bodies unchanged.
- Full suite re-run locally: `Tests run: 390, Failures: 0, Errors: 0, Skipped: 0`. Matches the commit-message claim.
- `BotFactoryTest` not touched — the plan stated it was "not strictly required" because the resolver itself is covered by the new isolation test.
- `EnvironmentServiceTest` not touched — plan dropped the boundary validator from a prior revision; no change needed.

---

## Architecture Decision check

| Decision | Required | Implemented | Where |
|---|---|---|---|
| 1. Single zone-name resolver helper with `customZone` branch | yes | yes | `Environment.resolveZoneName(Game)` |
| 2. Defaults as `static final` on `Environment`, resolver as instance method | yes | yes | `Environment.DEFAULT_MINI_ZONE_NAME` / `DEFAULT_CARD_ZONE_NAME` + instance method |
| 3. All three call sites consume the helper, no duplicated branching | yes | yes | `BotFactory.java:96`, `BotGroupBehaviorService.java:397`, `EnvironmentClientRegistry.java:135` (deleted instead) |
| 4. `BotFactory.createBot` fails fast and loud per bot | yes | yes | `IllegalStateException` with username + envId + gameType + customZone + both custom fields |
| 5. Per-bot failure increments a counter + ERROR-logged with context | yes | yes | `bot_creation_failures_total{reason}` via `BotMetrics.incBotCreationFailure`; reason ∈ {validation, auth, unknown} |
| 6. `restart()` throws on zero-bot post-`start` | yes | yes | `IllegalStateException("Restart of group %s produced %d/%d bots…")` |
| 7. No new abstractions / utility classes | yes | yes | One method + two constants on existing entity; no new packages, no new interfaces |
| 8. No websocket-parser library change | yes | yes | pom.xml unchanged for `websocket-parser-core` |

---

## Open implementation calls (deferred to Dev in plan)

1. **Registry cached-factory `setZoneName` — option (a) delete vs (b) sentinel default.**
   - Plan said: "Pick (a) unless Phase 2 step 0 finds an unexpected reader."
   - Dev's commit message: "Grep confirmed no production reader of that field — per-bot ClientFactory is always constructed fresh in BotFactory and overrides it. Decision deferred to Dev: option (a) delete the dead line."
   - Independent grep verification during this review found zero readers of `ClientFactory.getZoneName()` in production code (only `BotConfiguration.getZoneName()` is read, which is a separate field downstream). Dev's grep result is reproducible.
   - The deletion is also documented inline at `EnvironmentClientRegistry.java` with a multi-line comment explaining why the cached factory has no zoneName, which exceeds the plan's bar for documentation.

2. **Resolver location — instance method on `Environment` vs static utility.**
   - Plan default: instance method on `Environment`. Plan explicitly authorized the alternative if it fit codebase style better.
   - Dev picked: instance method on `Environment` (the plan default).
   - This is consistent with codebase style: `Environment` already collocates state with builder methods, and other domain entities under `domain/*/model/` follow the same pattern. The one-way dependency `domain.environment` → `domain.game` is acceptable (game does not import environment — no cycle), as the plan's Implementation Notes already analyzed.

Both deferred decisions resolved correctly and the reasoning is documented either in the commit message or inline in the source.

---

## Drift

None of consequence. Minor observations:

- The fail-loud message in `BotFactory.createBot` is more verbose than the plan strictly required (the plan said "naming environmentId, username, game type, customZone flag, and which custom field was missing (mini or card)"; Dev includes both miniZoneName and cardZoneName regardless). This is over-delivery, not drift — it provides strictly more diagnostic info.
- `classifyCreationFailure` uses a heuristic on class-name + message substrings to detect auth failures. The plan said "any auth exception → `auth`" without specifying a type. Heuristic is bounded (only three reason labels), the cardinality budget is honored, and the fallback is `unknown`. This is a reasonable execution of Decision 5; not drift.
- The new ERROR log on per-bot failure now includes the stacktrace (`cause.toString(), cause` — Log4j passes the second arg as throwable). The plan called for "log the exception cause with bot index AND env ID AND e.getMessage() at ERROR". Including the stacktrace is over-delivery, not drift.

## Out-of-scope changes

None. The two restart-lifecycle commits touch exactly the files listed in the plan: 5 production files (`Environment`, `BotFactory`, `BotGroupBehaviorService`, `EnvironmentClientRegistry`, `BotMetrics`) and 3 test files (`EnvironmentZoneResolutionTest`, `BotGroupBehaviorServiceRestartTest`, `BotGroupBehaviorServiceTest`). Total +414 / -8 LOC, well within the "~40 LOC of production change" budget once tests and Javadoc are subtracted.

The branch `feat/restart-lifecycle-fix` carries earlier commits unrelated to RESTART_LIFECYCLE_FIX (observability, tip, periodic-logout defaults). Those are not within this review's scope and are not part of the two restart-lifecycle commits.

## Amendments to the plan

None required. Plan and diff align exactly; no genuine technical oversight in the plan was uncovered during review.
