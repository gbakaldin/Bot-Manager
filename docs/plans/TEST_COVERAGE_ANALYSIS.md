# Test Coverage Analysis

Date: 2026-06-02
Baseline: `mvn test` → **83 tests, 0 failures, 5 test files** against **109 production files**.

## TL;DR

- The 83 tests that exist **do pass** and the patterns used (`@WebMvcTest`, `MockitoExtension`, AssertJ) are appropriate.
- Coverage is narrow: only 4 services/controllers in `botgroup`, `environment`, and `game` get touched. **Everything in `domain/bot/`, `domain/session/`, `domain/brand/`, `infrastructure/`, all mappers, and the entire JSON-polymorphism layer is untested.**
- Several existing tests confirm incorrect controller behavior rather than catch it (see "Bugs the tests lock in" below). Fix those before adding more mock-mvc assertions in the same shape.
- This is **at least 3 sessions of work** if we want meaningful coverage of the bot lifecycle and message handling. Phases 1–3 (pure logic + mappers + remaining controllers) fit comfortably in one session. Phases 4–7 each need their own.

---

## 1. What we have

| File | Tests | Quality |
|------|-------|---------|
| `GameServiceTest` | 13 | Solid. Mongo filter assertions are key-only (no value check). |
| `EnvironmentServiceTest` | 15 | Same shape as Game; superficial filter assertions. |
| `BotGroupServiceTest` | 14 | Covers user-registration success/failure/partial. Missing: `save(group, skipRegistration)` overload, env lookup failures, save inside `update`. |
| `EnvironmentControllerTest` | 13 | `totalBotGroups/totalBots/runningBots` aggregation is mocked away — the actual logic isn't exercised. |
| `BotGroupControllerTest` | 20 | Covers most endpoints. **Missing entirely: `GET /{id}/health`.** |

**Common weakness in filter tests** — every `filter(...)` test does:
```java
assertThat(queryCaptor.getValue().toString()).contains("brandCode");
```
That asserts the *field* is present in the query string but never asserts the *value* is correct, the operator (`$eq` vs `$regex`), or that case-insensitive name lookups actually compile to a regex with the `i` flag. Refactoring the service to query the wrong value would still pass these tests.

## 2. Bugs the existing tests lock in

These are not test bugs — they're production bugs the tests assert as if they were the spec. They need a decision before we add more tests in the same shape.

1. **`IllegalArgumentException` → 404 Not Found** in `BotGroupController.delete`, `BotGroupController.filter`, `BotGroupController.save`, `BotGroupController.start/stop/restart/scheduleRestart`, `GameController.delete`, `EnvironmentController.delete`. This should be **400 Bad Request** for an invalid argument; 404 is for missing resources (and there's already a `ResourceNotFoundException` for that). The tests at e.g. `BotGroupControllerTest:415` and `EnvironmentControllerTest:411` assert the broken behavior.
2. **Dead code in `BotGroupBehaviorService.java`** (lines 700–729). Two leftover classes `TradingProcessor`/`ExternalClient`/`Trade` sit at the bottom of the file. Looks like scratch code that never got removed. Not test-related, but worth removing before we write tests against this file.
3. **Controller `catch (Exception)` swallowing** — every endpoint catches `Exception` and returns 500 with no body. Combined with `IllegalArgumentException → 404`, this hides real validation errors from clients.

Recommendation: triage these in a small fix-up commit before Phase 3. Otherwise the new controller tests will calcify the broken status codes.

## 3. Coverage map (what's untested)

### High-value pure logic (no I/O, easy to test)
- `Game.getEffectiveBettingOptions()` — fallback from `bettingOptions` to `[0..numberOfOptions)`.
- `BettingMiniGameState.from(int)` — `2 → BET`, everything else → `PAYOUT`.
- `GameMessageTypesResolver.resolve(ProductCode)` — `P_097`/`P_098` → BOM, `P_118` → Nohu, everything else throws.
- `Request.subscribe/bet/chat/autoBet` — verifies `cmdPrefix + 3000/3002/3008/3015` arithmetic and zone/plugin propagation.
- `BotGroupRuntime.getNextBotForLogout()` — round-robin via `AtomicInteger`; trivial but used by periodic logout.
- `BotGroupRuntime.markAsDead`/`isGroupDead`/`getRunningBotCount` — state transitions.

### Bot core (`domain/bot/core/`) — **completely untested**
- `Bot.creditBalance(amount)` — decrements `expectedCurrentBalance`, increments `totalBetsPlaced`, adds to `totalBetAmount`. Pure arithmetic, no mocks.
- `Bot.checkBalance()` — cache-invalidation rule (refetch when `|lastFetched - expected| > 1_000_000L`). One critical branch.
- `Bot.deposit()` — early-return when `lastFetchedBalance < 0`; success/failure callback paths. Mockable.
- `Bot.authenticate/logout/restart` — token refresh + status transitions.
- `Bot.triggerFullReconnect` / `runWsReconnectLoop` / `runAuthThenWsLoop` — backoff sequence `{5, 10, 30, 60, 60, 60, 60}` + re-auth fallback + DEAD transition. Concurrency-heavy; mock the clock or shorten via a test seam.
- `BettingMiniGameBot.shouldBet()` — `betSkipPercentage` (0%, 100%, 50%), `maxBetsPerRound` cap. Has `random` field — needs injection seam.
- `BettingMiniGameBot.resolveBetAmount()` — bounds + step math.
- `BettingMiniGameBot.resolveNextEntryToBet()` — must pick from `Game.getEffectiveBettingOptions()`.
- `BettingMiniGameBot.canBet()` — gate on session-id, BET phase, remaining time.
- `BettingMiniGameBot.onSubscribe/onStartGame/onUpdate/onEndGame` — state transitions, session-id updates, watchdog rescheduling.
- `BettingMiniGameBot.onWatchdogExpired` — fires `triggerFullReconnect` when no message in N seconds. The two known bugs in CLAUDE.md (AUTH race, server-side pruning) are exactly what this watchdog is supposed to mitigate — tests here protect the recovery path.
- `BettingMiniGameBot.beforeReconnect` — state reset on reconnect.

### Service layer
- **`BotGroupBehaviorService`** — none of these have tests:
  - `start()` guards: missing `environmentId` / `gameId` throws.
  - `start()` failed-startup cleanup path (runtime removed on exception).
  - `scheduleRestart(id, past_time)` rejects past timestamps.
  - `getHealth(id)` aggregation — connected/reconnecting/dead bot counts when group has no runtime vs running.
  - `monitorHealth` dead-threshold trigger at `deadBotGroupThreshold` (0.80 default).
  - `performPeriodicLogout` — skips when not ACTIVE, skips disconnected bots, calls `bot.logout()` then `bot.restart()`.
  - Periodic-logout config resolution — environment override wins over global `@Value`.
- **`SessionHistoryService`** — completely untested. CRUD + `findBySessionId`, `findByGameId`, `findByEnvironmentId`, `findJackpotSessions`, `findByGameIdAndEnvironmentId`.
- **`BrandService.getBrandProducts()`** — completely untested.
- **`BotGroupService.save(group, skipRegistration=true)`** — the overload exists in `BotGroupController` (existing-group import flag) but has no test.

### Controllers
- **`GameController`** — 7 endpoints, **zero tests**.
- **`SessionHistoryController`** — 5 endpoints, **zero tests**.
- **`BrandController`** — 1 endpoint, **zero tests**.
- **`BotGroupController.getHealth`** — not covered by `BotGroupControllerTest`.

### Mappers (MapStruct generated)
- `BotGroupMapper`, `EnvironmentMapper`, `GameMapper`, `SessionHistoryMapper` — `toDTO/toEntity/updateEntityFromDTO`. Worth round-trip tests because the generator can silently drop a field if the source/target shapes drift.

### Infrastructure
- `ApiGatewayClient.registerUsers` — has parallel logic + result aggregation. Testable with a mocked `HttpClient` or by extracting the response-parsing helper.
- `ApiGatewayClient.setDisplayNameWithRetry` — retries `maxRetries` times on conflicting names. Pure retry-loop logic, easy to test.
- `ApiGatewayClient.authenticate / getBalance` — HTTP-heavy; lower priority unless we extract response parsing.
- `DisplayNameService` — loads names from resource, `getRandomDisplayName`, `hasDisplayNames`.
- `ClientFactory` — pure config (builder-style setters). Low test value.
- `EnvironmentClientRegistry` — caching + thread-safety; can be tested with a fake registry source.
- `AuthStrategyFactory` — strategy selection by env type.

### JSON polymorphism (highest production-risk area)
The bot's correctness depends on `mapper.registerSubtypes(messageTypes.getTypeRegistrations(offset, isMd5))` mapping incoming `cmd` values to the right subclass.
- `BomGameMessageTypes`, `NohuGameMessageTypes`, `B52GameMessageTypes` — each returns subtype registrations for CMD = OFFSET + (3000/3001/3005/3005md5/3006).
- For each, write a **deserialization round-trip test**: given a sample JSON frame with `"cmd": offset+code`, the `ObjectMapper` should produce the expected subclass with expected fields.
- This catches the exact class of bug described in CLAUDE.md's "Server-Side Subscriber Pruning" and "AUTH race" sections — a silent type-mapping break would manifest as zombie bots in prod.

### Things we should NOT bother testing
- `Starter.java` (Spring Boot main) — covered by `@SpringBootTest` context loads.
- Enum classes (`BotStatus`, `BrandCode`, `ProductCode`, `EnvironmentType`, `GameType`) — values are static; no behavior.
- Plain DTOs/entities — Lombok-generated getters/setters.
- `SessionIdStore` — one-field wrapper around a `long`; tests would test Lombok.
- `BotMdc` (logging context) — wraps SLF4J's `MDC`; trivial.

---

## 4. Phased plan

Numbers in parentheses are rough test-count estimates. "Session" = one focused work session of ~2–3 hours.

### Phase 0 — Triage controller error-mapping bugs (small, prerequisite)
Decide whether `IllegalArgumentException` should map to 400 vs 404. Either fix the controllers and update the 2 existing assertions, or document the current behavior as deliberate. Do this **before** Phase 3 so new controller tests don't lock in the wrong contract. Also remove the dead trading classes at the bottom of `BotGroupBehaviorService.java`.

### Phase 1 — Pure-logic foundation (~25 tests, fits in a session)
- `GameTest` — `getEffectiveBettingOptions` (null / empty / populated).
- `BettingMiniGameStateTest` — `from(2)` vs `from(0|1|3|99)`.
- `GameMessageTypesResolverTest` — every `ProductCode` enum value, including the throwing branches.
- `RequestTest` — CMD arithmetic for `subscribe/bet/chat/autoBet`.
- `BotGroupRuntimeTest` — round-robin index, markAsDead, getRunningBotCount with completed/incomplete `Future`s.
- Strengthen existing filter tests: assert query values + regex flags, not just field names.

### Phase 2 — Mappers (~12 tests, fits in same session as Phase 1)
- One round-trip test per mapper: build full entity → `toDTO` → assert all relevant fields; `toEntity` → assert all relevant fields; `updateEntityFromDTO` → assert partial-update semantics (null fields don't overwrite).
- Use the real MapStruct beans (no mocks) — these are the actual generated implementations.

### Phase 3 — Fill in controller gaps (~25 tests, one session)
- `GameControllerTest` — mirror the `BotGroupControllerTest` pattern.
- `SessionHistoryControllerTest` — including the optional `gameId`/`environmentId` query-param branching.
- `BrandControllerTest` — small but trivially missing.
- Add `GetHealthTests` block to existing `BotGroupControllerTest`.

### Phase 4 — Service-layer gaps (~20 tests, one session)
- `BotGroupBehaviorServiceTest` — start/stop guards, schedule-restart validation, `getHealth` aggregation, periodic-logout config resolution, dead-threshold trigger. Mock `BotFactory` to return stub `Bot`s with controlled `BotStatus`.
- `SessionHistoryServiceTest` — full CRUD + filters.
- `BrandServiceTest` — brand → products mapping.
- Add `BotGroupServiceTest.save(group, skipRegistration=true)` overload tests.

### Phase 5 — Bot core unit tests (~25 tests, one session)
- `BotTest` (using a minimal test-subclass that exposes the protected hooks) — `creditBalance` arithmetic, `checkBalance` cache rule, `deposit` callback paths, status transitions.
- `BettingMiniGameBotTest` — `shouldBet` (0/50/100 skip pct, max-bets cap), `resolveBetAmount` (bounds + step), `resolveNextEntryToBet` (uses effective options), `canBet` predicate (all four gate combos), session-lifecycle handlers (`onSubscribe/onStartGame/onUpdate/onEndGame`), `beforeReconnect` reset.
- **Test seam needed**: inject `Random` into `BettingMiniGameBot` (currently `new Random()` is hard-coded — replace with a setter or constructor parameter that defaults to `new Random()`). Without this, all randomness-dependent tests have to be statistical/flaky.

### Phase 6 — Reconnect & watchdog (~12 tests, one session — concurrency-heavy)
- `Bot.triggerFullReconnect` — idempotent under concurrent calls (CAS guard).
- `runWsReconnectLoop` — full backoff sequence, success on attempt N, fallthrough to re-auth after exhaustion, DEAD transition on re-auth failure.
- `runAuthThenWsLoop` — re-auth succeeds → WS reconnects.
- `BettingMiniGameBot.onWatchdogExpired` — schedules reconnect after timeout, no-op after `cleanup`.
- **Test seam needed**: extract the sleep schedule into an injectable `Clock`/`Sleeper` so we don't actually wait 4:45 in a unit test. Otherwise these tests are unrunnable in CI.

### Phase 7 — JSON message polymorphism (~30 tests across products, one session)
- For each of BOM / Nohu / B52: capture one sample JSON frame per message type (subscribe, startGame, startGameMd5, updateBet, endGame) from production logs, write a round-trip test that:
  1. Registers subtypes via `messageTypes.getTypeRegistrations(offset, md5)`.
  2. Deserializes the JSON.
  3. Asserts the resulting class matches `messageTypes.subscribeType()` etc.
  4. Asserts critical fields (sessionId, gameState, balance, etc.) parse correctly.
- This is the single most valuable phase from a production-risk standpoint, because the JSON-polymorphism layer is what the AUTH race / subscriber-pruning bugs in CLAUDE.md depend on.

---

## 5. Cross-cutting needs

These aren't tests themselves but are blockers for some of the phases above:

1. **`Random` injection seam in `BettingMiniGameBot`** (Phase 5). Pass `Random` in via constructor or setter; default to `new Random()` so production behavior is unchanged.
2. **Sleep/Clock seam in `Bot.runWsReconnectLoop`** (Phase 6). Either extract `private void sleep(long)` into an overridable method, or take a `Sleeper` interface. Without this, Phase 6 tests can't run in reasonable time.
3. **Sample message JSON fixtures** (Phase 7). Capture from staging or write by hand; commit under `src/test/resources/messages/<product>/<type>.json`. A small effort but currently absent.

## 6. Time estimate honestly

| Phase | Estimate |
|---|---|
| Phase 0 (triage) | 30 min |
| Phase 1 (pure logic) | 1 session |
| Phase 2 (mappers) | shares session with Phase 1 |
| Phase 3 (controllers) | 1 session |
| Phase 4 (services) | 1 session |
| Phase 5 (bot core) | 1 session + Random seam |
| Phase 6 (reconnect) | 1 session + Sleeper seam |
| Phase 7 (JSON) | 1 session, plus fixture capture |
| **Total** | **5–6 focused sessions** |

A "one and done" QA pass is not realistic for this codebase. Recommend stopping after each phase, running `mvn test`, committing, and re-prioritizing — phases are independent and can be paused.
