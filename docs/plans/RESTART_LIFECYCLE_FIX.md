# RESTART_LIFECYCLE_FIX

## Goal

Bots that authenticate cleanly on initial auto-start fail on `POST /api/v1/bot-group/{id}/restart` with `ValidationException: Authentication configuration is required`, observed 2026-06-09 on group `0c9a93cb-20d6-4f57-9dbc-5c315dcf52e2` (18/18 success at 10:01:09, 18/18 failure at 10:02:55). Root cause confirmed by operator: `Environment.miniZoneName` is null whenever the client doesn't supply a custom value, and the production read sites in `BotFactory`, `EnvironmentClientRegistry`, and `BotGroupBehaviorService` consume it unconditionally — so a non-custom-zone environment can never produce a non-null `zoneName` for the WS builder.

Two things must happen:

1. Make `zoneName` always resolvable for any `(Environment, Game)` pair: when `Environment.customZone == false`, fall back to product-default zone names (`"MiniGame"` for mini games, `"Simms"` for card games). When `customZone == true`, use the per-environment custom values as before.
2. Make any future regression of the same shape **observable** — the per-bot creation error must surface in the response and in a metric, not just buried in logs, so the next occurrence does not require a Mongo dump to triage.

---

## Findings — Current State

### Where the exception originates

The exception text is **not** in this repository — it is thrown by `com.vingame.websocketparser.VingameWebSocketClient$Builder.build()` inside `websocket-parser-core` 2.3.10 (the version declared at `pom.xml` — `websocket-parser-core` 2.3.10). The exact source (decompiled / sources jar, `/Users/gleb/.m2/repository/com/vingame/websocket-parser-core/2.3.10/websocket-parser-core-2.3.10-sources.jar`, `VingameWebSocketClient.java:956-966`):

```java
boolean hasAuthMessage = authMessage != null;
boolean hasTokenAuth   = tokensProvider != null && zoneName != null && agentId != null;

if (!hasAuthMessage && !hasTokenAuth) {
    throw new ValidationException(
            "Authentication configuration is required. Either provide:\n"
                    + "  1. .authMessage(authMessage), OR\n"
                    + "  2. .tokensProvider(supplier).zoneName(zone).agentId(id)");
}
```

The only way to trip this is for at least one of `tokensProvider`, `zoneName`, `agentId` to be null at `Builder.build()` time. We never call `.authMessage(...)`.

### What we set on the builder

`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/client/ClientFactory.java:72-105` (`buildClient`) unconditionally sets all three:

| Field           | Setter                          | Source                                  | Null-safe? |
|-----------------|---------------------------------|-----------------------------------------|------------|
| `tokensProvider`| `.tokensProvider(() -> tokens)` | local param (closure)                   | Supplier is always non-null even if `tokens` is null |
| `agentId`       | `.agentId("1")`                 | string literal                          | Always non-null |
| `zoneName`      | `.zoneName(zoneName)`           | `this.zoneName` instance field          | Null if field is null |

`tokensProvider` and `agentId` are mechanically non-null. **`zoneName` is the only field that can produce this exception.**

### Where `zoneName` is sourced (the three production read sites)

`Environment.miniZoneName` is read unconditionally in exactly three places. `Environment.customZone` is **never consulted** anywhere in production code.

1. `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/config/client/EnvironmentClientRegistry.java:138`:
   ```java
   clientFactory.setZoneName(env.getMiniZoneName());
   ```
   This `ClientFactory` is cached on `EnvironmentClients` but is **not** the one used by `BotFactory` — `BotFactory` builds its own. Still must be fixed for consistency: any future code that reuses the cached factory would inherit the same bug.

2. `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java:96`:
   ```java
   freshClientFactory.setZoneName(env.getMiniZoneName());
   ```
   This is the production hot path — feeds the WS builder that throws.

3. `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:369`:
   ```java
   .zoneName(environment.getMiniZoneName())
   ```
   Populates `BotConfiguration.zoneName`, used downstream by the bot for auth-message construction.

All three currently ignore both `customZone` and the game type. None can produce a value for a non-custom-zone environment because `miniZoneName` is null in that case.

### Confirmed root cause (Phase 0 pre-confirmed by operator)

Confirmed by operator: `Environment.miniZoneName` is null in the Mongo record for the affected group's environment, because the frontend doesn't surface a `miniZoneName` field when `customZone=false`. The `Environment` entity has both `customZone` (boolean, `Environment.java:41`) and `cardZoneName` (`Environment.java:45`) but the production code reads neither. The fix is to honour `customZone` at the read sites and supply product-default zone names (`"MiniGame"` / `"Simms"`) when `customZone == false`. No further diagnostic investigation is required — Phase 0 from the prior plan revision is no longer needed.

### `GameType` enum

`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/model/GameType.java` defines five values: `BETTING_MINI`, `SLOT`, `TAI_XIU`, `CARD_GAME`, `UP_DOWN`. `BETTING_MINI` is the only mini-game type today; `BotFactory.createBot` (line 113-114) currently `throw`s `IllegalArgumentException` for the four others ("Game type not yet implemented"). For zone-resolution purposes:

- `BETTING_MINI` → mini zone → default `"MiniGame"`.
- `SLOT`, `TAI_XIU`, `CARD_GAME`, `UP_DOWN` → treated as card/other → default `"Simms"`.

We use `isMiniGame(game) == (game.getGameType() == BETTING_MINI)` as the single discriminator. If a future mini-game type is added (e.g., `BETTING_MINI_V2`), the discriminator updates in one place.

### Adjacent symptoms / blast radius

- The controller catches `Exception` and returns bare 500 (no body) at `BotGroupController.java:199-201`. Operators see "Internal Server Error" with no per-bot context. CLAUDE.md backlog item `API_ERROR_FORWARDING` already plans to fix the controller layer; we do **not** want to duplicate that effort here, but we do need at least a one-line server log per failed bot tagged with the actual cause.
- `createBotsInParallel` swallows per-bot errors (`BotGroupBehaviorService.java:312-324`). When 18/18 fail, `start()` still completes "successfully" with zero bots in the runtime and `targetStatus=ACTIVE`. The DB is now lying. The existing test `BotGroupBehaviorServiceTest$StartCreateBotFailureTests#shouldCompleteWithZeroBotsWhenAllCreateBotsFail` (line 213-251) explicitly documents this as intentional behavior. We need to revisit: a `start()` that produces zero live bots is not a successful start; the operator has no signal that the restart failed.

### Test coverage gap

`BotGroupBehaviorServiceTest.java` has 19 tests across start/stop/scheduleRestart/health/monitorHealth/periodicLogout. **There is no test for `restart()`** — no test exercises stop-then-start. There is also no test for the (Environment, Game) → zoneName resolution because the resolver does not exist yet.

---

## Per-aspect readiness / mapping

| Aspect | State | Notes |
|---|---|---|
| Root cause confirmed | ready | Operator-confirmed: null `miniZoneName` when `customZone=false`. No further diagnosis required. |
| Single resolver helper | ready | Add `Environment.resolveZoneName(Game)` (instance method on `Environment`) plus two `public static final` constants for the defaults. One symbol; three call sites consume it. |
| Fix surface — `BotFactory` | ready | One line change at `BotFactory.java:96`. |
| Fix surface — `EnvironmentClientRegistry` | ready | One line change at `EnvironmentClientRegistry.java:138`. Caller has `Environment` in scope; needs a `Game` — but the registry-cached `ClientFactory` is **shared across games** and is dead code for bot creation. See Implementation Notes for how to handle this without forcing a `Game` into the registry. |
| Fix surface — `BotGroupBehaviorService` | ready | One line change at `BotGroupBehaviorService.java:369`. Caller already has both `environment` and `game` in scope (lines around 350-368). |
| Restart-cycle integration test | ready | New test file `BotGroupBehaviorServiceRestartTest.java`. |
| Operator-visible error on per-bot failure | blocked | Controller catches `Exception` → 500 with no body. In-scope: log per-failure cause + bump a metric + throw on zero-bot restart. Full controller-layer fix is `API_ERROR_FORWARDING`, out of scope. |

---

## Architecture Decisions

1. **The fix is a zone-name resolver helper, not call-site validation.** A single helper resolves the effective `zoneName` from the (Environment, Game) pair. Rule:
   ```
   if (environment.isCustomZone()) {
       zoneName = isMiniGame(game) ? environment.getMiniZoneName() : environment.getCardZoneName();
   } else {
       zoneName = isMiniGame(game) ? "MiniGame" : "Simms";
   }
   ```
   where `isMiniGame(game) == (game.getGameType() == BETTING_MINI)`.

2. **Defaults live as constants on `Environment`; resolver lives as an instance method on `Environment`.** Rationale: `Environment` already owns the `customZone`/`miniZoneName`/`cardZoneName` fields. Adding `Environment.resolveZoneName(Game game)` keeps state and the resolver collocated; callers do `env.resolveZoneName(game)` with no extra utility class. Two new constants:
   ```java
   public static final String DEFAULT_MINI_ZONE_NAME = "MiniGame";
   public static final String DEFAULT_CARD_ZONE_NAME = "Simms";
   ```
   No new utility class, no service indirection. (If the codebase prefers utilities to entity methods, the equivalent symbol is `ZoneDefaults.resolve(Environment, Game)` — but `Environment` is the natural owner because the field/state is already there.)
   This **replaces** the prior revision's "validate non-null `miniZoneName` at the service boundary" approach. Defaults make the field always-resolvable; no boundary validation is required.

3. **All three call sites consume the same helper.** `BotFactory.createBot`, `BotGroupBehaviorService.createBot`, and `EnvironmentClientRegistry.createClients` all read `env.resolveZoneName(game)`. No duplicated branching, no read-then-coalesce idiom scattered around. The registry's `createClients` does not have a `Game` in scope (it caches a `ClientFactory` shared across games); see Implementation Notes for how the registry call site is handled — short version, stop setting `zoneName` on the cached factory at all, since it's only consumed downstream by per-bot factories that override the field anyway.

4. **`BotFactory.createBot` fails fast and loud per bot.** If `Game` is null, or the resolved `zoneName` is unexpectedly null (custom zone with explicitly-blank custom value), wrap and rethrow with a message that names the missing field AND the environment ID AND the bot username. The rethrow is what `createBotsInParallel` already catches; we just need it to be informative.

5. **Per-bot failure during `start` increments a counter and is logged at ERROR with full context.** Add `bot_creation_failures_total{environmentId,botGroupId,reason}` to `BotMetrics`, increment from `createBotsInParallel`'s catch block. `reason` is bounded: `validation | auth | unknown`. This is the observability hook that turns "next restart silently goes to zero bots" into a Prometheus alert.

6. **`restart()` reads back the runtime after `start()` and surfaces zero-bot failure.** If post-`start` the runtime contains zero bots while `botCount > 0`, throw `IllegalStateException("Restart produced 0/N bots; first failure: …")`. The controller already returns 500 on `Exception`; this turns silent failure into a 500-with-cause-in-logs that ops can grep. We do not redesign the controller (that is `API_ERROR_FORWARDING`'s job), but we do throw something actionable.

7. **No new abstractions.** No `EnvironmentSnapshotProvider`, no `BotFactoryV2`, no listener pattern, no new utility package. The fix is: one method + two constants on `Environment`, three one-line call-site changes, one new metric counter, one throw in `restart`. Total: ~40 LOC of production change.

8. **No websocket-parser library change.** The parser's validation is correct — `zoneName` is required. The bug is on our side.

---

## Plan

Phases are independent enough to ship one at a time. Phase 1 through Phase 3 land in one feature branch; Phase 4 is the staging deploy.

### Phase 1 — Add the tests

**Goal:** Lock in unit tests that fail on `main` and pass after Phase 2. No production code change in this phase.

Files:

- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/environment/model/EnvironmentZoneResolutionTest.java` (new) — tests the resolver in isolation, no Spring context.
- `/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceRestartTest.java` (new) — restart lifecycle tests with mocked factory/services.

Tests in `EnvironmentZoneResolutionTest`:

1. **`resolveZoneName_usesDefaultWhenCustomZoneFalse_mini`** — `customZone=false`, `miniZoneName=null`, `cardZoneName=null`, `Game.gameType=BETTING_MINI` → resolver returns `"MiniGame"`. Fails on `main` (method does not exist).
2. **`resolveZoneName_usesDefaultWhenCustomZoneFalse_card`** — `customZone=false`, game type `CARD_GAME` → returns `"Simms"`. Fails on `main`.
3. **`resolveZoneName_usesCustomWhenCustomZoneTrue_mini`** — `customZone=true`, `miniZoneName="MyMiniZone"`, game type `BETTING_MINI` → returns `"MyMiniZone"`. Fails on `main`.
4. **`resolveZoneName_usesCustomWhenCustomZoneTrue_card`** — `customZone=true`, `cardZoneName="MyCardZone"`, game type `CARD_GAME` → returns `"MyCardZone"`. Fails on `main`.
5. **`resolveZoneName_treatsSlotAndTaiXiuAndUpDownAsCard`** — `customZone=false`, three parameterised inputs (`SLOT`, `TAI_XIU`, `UP_DOWN`) → all return `"Simms"`. Fails on `main`. (Pins the discriminator behavior — if anyone later adds a new mini-game enum value, this test forces an explicit decision.)

Tests in `BotGroupBehaviorServiceRestartTest`:

6. **`restart_recreatesBotsAfterStop`** — full mock stack (`BotFactory`, `EnvironmentService`, `GameService`, `BotGroupService`). Set up a group with 3 bots; first `start` returns mock bots; verify `stop` is called; verify second `start` calls `botFactory.createBot` 3 more times. Asserts symmetry between initial start and post-restart start. Should pass on `main` already — establishes baseline.
7. **`restart_failsLoudlyWhenZeroBotsCreated`** — `botFactory.createBot(...)` throws on every call in the second `start` (simulating the bug). After Phase 2, `restart()` must throw `IllegalStateException` containing `0/3` and the first failure cause. Will fail on `main` (today `restart` silently completes). Will pass after Phase 2.

Run the tests; they should fail (except #6) on `main`. **Commit the failing tests** as a separate Phase-1 commit before any production change. This is the regression net.

### Phase 2 — Fix

Files:

- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/environment/model/Environment.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/config/client/EnvironmentClientRegistry.java`
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/infrastructure/observability/BotMetrics.java`

Changes:

1. **`Environment`** — add the resolver and the constants:
   ```java
   public static final String DEFAULT_MINI_ZONE_NAME = "MiniGame";
   public static final String DEFAULT_CARD_ZONE_NAME = "Simms";

   public String resolveZoneName(Game game) {
       boolean mini = game != null && game.getGameType() == GameType.BETTING_MINI;
       if (customZone) {
           return mini ? miniZoneName : cardZoneName;
       }
       return mini ? DEFAULT_MINI_ZONE_NAME : DEFAULT_CARD_ZONE_NAME;
   }
   ```
   Add imports for `Game` and `GameType`. Note: this introduces a dependency from `domain.environment.model` on `domain.game.model`. That's a one-direction dependency on enum + entity, both already shared across the codebase — no cycle risk.

2. **`BotFactory.createBot`** (`BotFactory.java:96`):
   - Replace `freshClientFactory.setZoneName(env.getMiniZoneName())` with `freshClientFactory.setZoneName(env.resolveZoneName(game))`.
   - After the call, defensively assert the result is non-null/non-blank. If blank, throw `IllegalStateException` naming environmentId, username, game type, customZone flag, and which custom field was missing (mini or card). This is Decision 4.

3. **`BotGroupBehaviorService.createBot`** (`BotGroupBehaviorService.java:369`):
   - Replace `.zoneName(environment.getMiniZoneName())` with `.zoneName(environment.resolveZoneName(game))`.

4. **`EnvironmentClientRegistry.createClients`** (`EnvironmentClientRegistry.java:138`):
   - The cached `ClientFactory` is shared across games for an environment, so it cannot pre-resolve a per-game zone. Two options:
     - **(a)** Stop setting `zoneName` on the cached factory at line 138 (delete the line). The cached factory's `zoneName` is dead — `BotFactory.createBot` constructs its own `ClientFactory` and never reads the registry's `zoneName`. Confirm by `grep -n "clientFactory.getZoneName\|clientFactory.zoneName"` (Phase 2 step 0 below).
     - **(b)** Leave a sentinel default (`DEFAULT_MINI_ZONE_NAME`) so the cached factory has *something* if any future code path reuses it.
   - **Pick (a)** unless Phase 2 step 0 finds an unexpected reader. Simpler, no dead default. If a reader is found, **switch to (b)** and document it inline. Either way, no `Game` parameter leaks into the registry.

5. **`BotGroupBehaviorService.createBotsInParallel`** (`BotGroupBehaviorService.java:278-327`):
   - In the catch block at line 315-318, log the exception cause with bot index AND env ID AND `e.getMessage()` at ERROR (currently logs just the message). Increment `botMetrics.incBotCreationFailure(reason)` with the reason derived from the exception type: `IllegalStateException`/`IllegalArgumentException` → `"validation"`, any auth exception → `"auth"`, else `"unknown"`.

6. **`BotGroupBehaviorService.restart`** (`BotGroupBehaviorService.java:422-434`):
   - After `start(id)` returns, look up `runningGroups.get(id)`. If `runtime.getBotInstances().size() == 0` and the group's `botCount > 0`, throw `IllegalStateException("Restart of group " + id + " produced 0/" + group.getBotCount() + " bots; check logs")`. The controller's existing `Exception` catch returns 500; this surfaces in logs and metrics. (Decision 6.)

7. **`BotMetrics`**:
   - Add `incBotCreationFailure(String reason)` backed by `Counter.builder("bot_creation_failures_total").tag("reason", reason)`. Tag set is bounded per Decision 5 — fits the existing label-budget pattern in `BotMetrics`.

**Phase 2 step 0 (pre-work):** Before deciding option (a) vs (b) for the registry, run:
```
grep -rn "clientFactory\.getZoneName\|getClientFactory().getZoneName\|environmentClients\.getClientFactory" /Users/gleb/IdeaProjects/Bot/src
```
If zero hits outside `EnvironmentClientRegistry` and `BotFactory`'s prototype usage, the cached factory's `zoneName` is dead — pick (a).

That's the entire fix. No new files in production code (just a method on an existing entity), three one-line call-site changes, one metric, one throw. ~40 LOC net.

### Phase 3 — Update existing tests + run full suite

- `BotGroupBehaviorServiceTest$StartCreateBotFailureTests#shouldCompleteWithZeroBotsWhenAllCreateBotsFail` (line 213-251) tests behavior that **changes** under Decision 6 when invoked via `restart`. After Phase 2 this remains true for **direct** `start` calls (no behavioral change there — backwards-compatible for first-start), but `restart` becomes stricter. Add a comment to the existing test clarifying it's about `start`, not `restart`.
- `BotFactoryTest` — if it exists, ensure the new `env.resolveZoneName(game)` path is exercised (mock `Environment` to return the expected zone). If it doesn't exist, the new Phase 1 unit test covers the resolver in isolation; `BotFactoryTest` is not strictly required for this fix.
- `EnvironmentServiceTest` — no changes (Decision 2 dropped the boundary validator).
- Run `mvn -q test` against the full suite. All green.

### Phase 4 — Staging deploy + verification

See `## Verification` below.

---

## Implementation Notes / Concerns

- **One-way dependency `domain.environment` → `domain.game`.** Adding `Environment.resolveZoneName(Game)` introduces a compile-time edge from the environment package to the game package. Both packages are leaf domain entities and the game package does not import environment, so there's no cycle. If this is undesirable on style grounds, the alternative is a static utility (`com.vingame.bot.domain.environment.util.ZoneResolver.resolve(Environment, Game)`) — same behavior, no entity dependency. Operator preference: pick whichever fits the codebase style during Phase 2 implementation. Default in this plan: instance method on `Environment`.
- **`Environment` is a Mongo `@Document`.** Adding a method with a `Game` parameter doesn't affect serialization — only `@Getter`-derived properties end up in Mongo. The constants are `static final` and are also ignored by the mapper. Verify by running the existing `EnvironmentMapperTest` (if present) post-change.
- **Cached registry factory.** The registry's cached `ClientFactory` has historically been a source of confusion (CLAUDE.md Known Bugs section mentions related issues). Phase 2 step 0 either confirms the cached `zoneName` is dead or surfaces a reader; either outcome is fine, but the decision must be explicit.
- **`BotGroupBehaviorService.createBot` already has the `Game` in scope.** Lines around 350-368 look up the `Game` via `gameService.findById(group.getGameId())` before building `BotConfiguration`. The fix at line 369 just swaps the field read — zero extra work to wire `game` in.
- **Frontend behavior.** Operator confirms the frontend already omits `miniZoneName` when `customZone=false`. This plan does **not** require frontend changes. Existing Mongo records with `customZone=false` and `null` zone-name fields will resolve correctly post-deploy. Existing records with `customZone=true` and non-null zone-name fields continue working as before. No data migration required.
- **`BotFactory` fail-loud only triggers on `customZone=true` with a blank custom field** — i.e., the operator explicitly set `customZone=true` but left `miniZoneName`/`cardZoneName` empty. That's a real misconfiguration (different from the bug we're fixing) and deserves a loud error. The default path (`customZone=false`) cannot produce null because the constants are non-null.
- **Backlog overlap.** This plan does NOT close out `API_ERROR_FORWARDING`. We address the minimum guardrail (per-failure log + metric + loud throw on zero bots). The controller exception advice and broader migration to `ProductCode.zoneName` remain owned by those items. Note that the CLAUDE.md backlog item "Frontend env-creation: `miniZoneName` required-field validation" can be **closed** by this fix — defaults make the field optional rather than required.
- **Bot-1 has 2 running groups at deploy time.** The deploy itself is a process restart, which exercises `@PostConstruct onStartup` → `start(id)` for each group with `targetStatus=ACTIVE`. **This is the same code path the fix touches.** If either group's env relies on defaults (i.e., `customZone=false` with null zone names — the exact buggy state today), the post-deploy auto-start must succeed where it would have failed pre-fix. This is the primary success signal.

---

## Open Items

- **Decision deferred to Dev during Phase 2 step 0:** Registry option (a) vs (b) — delete the dead `setZoneName` line or set a sentinel default. Both are correct; (a) is cleaner if there are no readers. Decide based on the grep result.
- **Decision deferred to Dev during Phase 2:** Resolver location — instance method on `Environment` (preferred, this plan's default) vs static utility in `domain.environment.util`. If the latter fits codebase style better, use it; behavior is identical.
- **Out of scope:** Frontend env-creation form changes, `API_ERROR_FORWARDING` controller refactor, migrating `zoneName` into `ProductCode` (existing backlog items).
- **Out of scope:** Reworking `EnvironmentClientRegistry` to expire entries — caching `apiGatewayClient` indefinitely is intentional and correct.

---

## Verification

The Releaser runs these on Bot-1 (staging) after the deploy lands. Each step has explicit pass criteria. **Pre-deploy capture is mandatory** — without it the post-deploy comparison is meaningless.

### Pre-deploy state capture (BEFORE Releaser ships the new image)

Substitute `<bot-1>` with the actual staging host, `<g1>` and `<g2>` with the two known running group IDs (operator will provide; if not, derive from step 1).

1. List all groups and identify which are running:
   ```
   curl -sf https://<bot-1>/api/v1/bot-group/
   ```
   Expected: HTTP 200, JSON array. Record (a) IDs of groups with `targetStatus=ACTIVE`, (b) each group's `botCount`, (c) each group's `environmentId`, (d) each group's `name`. There should be exactly 2 with `targetStatus=ACTIVE`.

2. For each running group, capture pre-deploy health:
   ```
   curl -sf https://<bot-1>/api/v1/bot-group/<g1>/health
   curl -sf https://<bot-1>/api/v1/bot-group/<g2>/health
   ```
   Expected: HTTP 200. Record `connectedBots` and `totalBots` for each. Save these JSON snapshots to release notes.

3. For each running group's env, capture the environment record:
   ```
   curl -sf https://<bot-1>/api/v1/environment/<envIdForG1>
   curl -sf https://<bot-1>/api/v1/environment/<envIdForG2>
   ```
   Expected: HTTP 200. Specifically record `customZone`, `miniZoneName`, `cardZoneName` for each. **Capture all three together** — the post-deploy expectation depends on which combination is in use:
   - If `customZone=false` (zone names may be null) → post-deploy resolver returns `"MiniGame"`/`"Simms"` defaults; auto-start must succeed.
   - If `customZone=true` and zone names are populated → post-deploy resolver returns the custom values; auto-start must succeed identically to today.
   - If `customZone=true` and the relevant zone name is null/blank → this is a misconfiguration the new fail-loud path will surface; do **not** abort the deploy, but flag for operator follow-up — the bug is in the env data, not the fix.

### Deploy

Standard image build + ship to Bot-1 pipeline. Process restart drops both running groups and triggers `onStartup` for each group with `targetStatus=ACTIVE`.

### Post-deploy verification

4. Wait for the application to come back up (Actuator health = UP):
   ```
   until curl -sf https://<bot-1>/actuator/health | grep -q '"status":"UP"'; do sleep 2; done
   ```
   Expected: returns within 60s. If it does not, fetch `kubectl logs` / journal and check for `ApplicationFailedEvent`.

5. Confirm both groups are present and `targetStatus=ACTIVE` (DB state survived restart):
   ```
   curl -sf https://<bot-1>/api/v1/bot-group/<g1> | jq '.targetStatus'
   curl -sf https://<bot-1>/api/v1/bot-group/<g2> | jq '.targetStatus'
   ```
   Expected: each returns `"ACTIVE"`.

6. Wait for auto-start to complete (max ~60s for 30-bot group):
   ```
   sleep 60
   ```

7. **Verify both groups recovered** (the primary success criterion):
   ```
   curl -sf https://<bot-1>/api/v1/bot-group/<g1>/health | jq '{actualStatus: .status, totalBots, connectedBots}'
   curl -sf https://<bot-1>/api/v1/bot-group/<g2>/health | jq '{actualStatus: .status, totalBots, connectedBots}'
   ```
   Expected for each: `actualStatus="ACTIVE"`, `totalBots == botCount captured pre-deploy`, `connectedBots >= floor(botCount * 0.8)` (allow 20% slack for in-flight auth/connect). Specifically `totalBots > 0` is the cliff-edge check — `totalBots == 0` with `targetStatus=ACTIVE` is the exact bug we're fixing and signals a regression. **A group whose env had `customZone=false` (the pre-fix-broken case) recovering successfully is the strongest positive signal.**

8. **Verify the new metric is present and zero on the happy path**:
   ```
   curl -sf https://<bot-1>/actuator/prometheus | grep '^bot_creation_failures_total'
   ```
   Expected: the metric series exists (registered via MeterBinder or first-failure registration). If `# HELP bot_creation_failures_total` is present with all series at `0.0`, that's correct. If series are non-zero, cross-check with logs — that's the per-bot creation failure surfaced by Decision 5.

9. **Exercise the restart path end-to-end on one group**:
   ```
   GROUP=<g1>; BOT_COUNT=$(curl -sf https://<bot-1>/api/v1/bot-group/$GROUP | jq -r '.botCount')
   curl -sf -X POST https://<bot-1>/api/v1/bot-group/$GROUP/restart
   sleep 60
   curl -sf https://<bot-1>/api/v1/bot-group/$GROUP/health | jq '{totalBots, connectedBots}'
   ```
   Expected: restart POST returns HTTP 200. After 60s, `totalBots == BOT_COUNT` and `connectedBots >= floor(BOT_COUNT * 0.8)`. **This is the direct regression test** — pre-fix this would 500 and produce zero bots; post-fix it must produce the same bot count as before.

10. **Verify `bot_creation_failures_total` is still zero after the restart** (Step 8 repeated):
    ```
    curl -sf https://<bot-1>/actuator/prometheus | grep '^bot_creation_failures_total'
    ```
    Expected: same as Step 8. If any series ticked up, the restart had per-bot failures — read the logs.

### If recovery fails (rollback decision tree)

If Step 7 shows `totalBots == 0` for either group, structured triage:

- Which group? `<g1>` or `<g2>` — record.
- Which bots? Pull `bot_creation_failures_total` from Step 8 broken down by `reason` tag.
- Which lifecycle stage? Grep server logs for the group ID:
  - `Failed to create bot` (post-Phase 2 log) → captures index + env ID + cause.
  - `Cannot create bot` → captures the `IllegalStateException` from Decision 4 (resolved zoneName was null/blank — the env has `customZone=true` with a missing custom value).
  - `Authentication configuration is required` → fix did not deploy / fix is incomplete.
- If the cause is the Decision-4 fail-loud message ("resolved zoneName is null/blank") for an env with `customZone=true`, the deploy is a partial success — the fix is doing exactly what Decision 4 says it should, but the env has a real misconfiguration that the operator must patch (set the missing custom zone name, or set `customZone=false` to use defaults). Not a rollback trigger.
- If the cause is anything else (auth error, NPE, unknown), **roll back** to the prior image and re-engage Architect with the captured logs + group/env IDs. The fix introduced a new regression.

If no on-server verification beyond the universal smoke test is feasible because both groups happen to not exist at deploy time (e.g., operator stopped them for unrelated reasons), Steps 1-3 and 7-10 reduce to "create a transient test group, start it, restart it, verify, delete it" — but at the time of writing, the 2 running groups are the canonical test surface.
