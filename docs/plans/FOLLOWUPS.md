# Follow-ups surfaced during test-coverage expansion

Last updated: 2026-06-02 (after QA Phase 4)

This is a running list of production bugs and code seams identified while expanding the test suite. Items are grouped by category and tagged with severity. Each item points to the file/line where it was first observed and, where relevant, the test or doc that surfaced it.

## Production bugs

### P1 — `EnvironmentMapper` silently drops three fields
- **File:** `src/main/java/com/vingame/bot/domain/environment/mapper/EnvironmentMapper.java`
- **Fields not mapped:** `useJwtAuth`, `periodicLogoutEnabled`, `periodicLogoutIntervalMinutes`
- **Affected methods:** `toDTO`, `toEntity`, `updateEntityFromDTO` — all three omit these fields even though they exist on both `Environment` (entity) and `EnvironmentDTO`.
- **Impact:** Saving or updating an environment via the REST API silently fails to persist these three fields. `useJwtAuth` drives auth strategy selection; the two periodic-logout fields drive the env-level override of the global logout schedule (see `BotGroupBehaviorService.startPeriodicLogoutScheduler`).
- **Surfaced by:** `EnvironmentMapperTest` (Phase 2). QA held off on asserting the missing fields since fixing the bug is a production-code change.
- **Action:** Add the three mappings in the MapStruct interface. After the fix, extend `EnvironmentMapperTest` to assert all 20 fields (currently 17).

### P2 — Controllers map `IllegalArgumentException` to 404, not 400
- **Files:**
  - `src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java` (`delete`, `filter`, `save`, `start`, `stop`, `restart`, `scheduleRestart`)
  - `src/main/java/com/vingame/bot/domain/game/controller/GameController.java` (`delete`)
  - `src/main/java/com/vingame/bot/domain/environment/controller/EnvironmentController.java` (`delete`)
- **Behavior:** Every `catch (IllegalArgumentException)` returns `ResponseEntity.notFound().build()` (HTTP 404). Standard HTTP semantics say invalid arguments should be 400 Bad Request; 404 is for missing resources (already handled by `ResourceNotFoundException` → 404).
- **Impact:** Clients can't distinguish a malformed request from a missing entity. Adds noise to monitoring dashboards.
- **Surfaced by:** Code review during `TEST_COVERAGE_ANALYSIS.md` Phase 0 triage. The two existing assertions at `BotGroupControllerTest:415` and `EnvironmentControllerTest:411` lock in the current (wrong) behavior.
- **Action:** Decide whether 400 is correct; if yes, change the controllers AND update the two existing test assertions. This is a prerequisite for Phase 3 (the remaining controller tests).

**RESOLVED:** Phase 0 dev pass — flipped every `IllegalArgumentException` → `notFound()` to `badRequest()` in `BotGroupController` (filter, save, delete, start, stop, restart, scheduleRestart), `GameController` (delete), and `EnvironmentController` (filter, save, delete). Updated six existing test assertions (one in `EnvironmentControllerTest`, five in `BotGroupControllerTest` covering Delete/Start/Stop/Restart/ScheduleRestart) from `isNotFound()` to `isBadRequest()` with renamed methods and `@DisplayName`s. `ResourceNotFoundException` → 404 mappings left untouched.

### P3 — `catch (Exception)` swallows errors and returns bare 500
- **Files:** every controller endpoint listed in P2, plus all GET endpoints in the same controllers.
- **Behavior:** `catch (Exception e) { return ResponseEntity.internalServerError().build(); }` — no body, no logging beyond the default, no problem detail.
- **Impact:** Clients see opaque 500s. Combined with P2, validation failures and genuine bugs are indistinguishable.
- **Action:** Replace with a `@RestControllerAdvice` exception handler that emits RFC 7807 problem details and logs the underlying exception. Lower priority than P2 but worth bundling with the same change.

### P4 — Dead scratch code in `BotGroupBehaviorService.java`
- **File:** `src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java` lines 700–729
- **What:** Three classes — `Trade`, `TradingProcessor`, `ExternalClient` — sit at the bottom of the file with no usages, no `public` modifiers, and no relation to the bot domain. The comment block above them describes a generic trading-processor problem.
- **Impact:** Confuses readers; will show up in any future refactor of this file.
- **Action:** Delete. Trivial.

**RESOLVED:** Phase 0 dev pass — removed lines 700–729 (Trade/TradingProcessor/ExternalClient and the preceding comment block).

### P5 — Inconsistent `name` filter semantics across services
- **Files:**
  - `src/main/java/com/vingame/bot/domain/game/service/GameService.java` — `Criteria.where("name").regex(Pattern.quote(name), "i")` — **unanchored** (contains match)
  - `src/main/java/com/vingame/bot/domain/environment/service/EnvironmentService.java` — `Criteria.where("name").regex("^" + Pattern.quote(name) + "$", "i")` — **anchored** (exact match, case-insensitive)
  - `src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupService.java` — same anchored form as Environment
- **Impact:** Filter UX is inconsistent. Filtering games by partial name works; filtering environments or bot groups requires the full name.
- **Surfaced by:** Phase 1 filter strengthening (`GameServiceTest`/`EnvironmentServiceTest`/`BotGroupServiceTest`).
- **Action:** Pick one semantic (probably "contains" everywhere — matches what a user types in a filter box) and align. After the change, update the strengthened filter tests to assert the new shape.

### P6 — `SessionHistoryService.save()` does not generate UUIDs (inconsistent with siblings)
- **File:** `src/main/java/com/vingame/bot/domain/session/service/SessionHistoryService.java`
- **Behavior:** `save(SessionHistory)` is a thin delegate to `repository.save(session)`. It does not auto-generate a UUID when the entity's id is null/empty.
- **Inconsistency:** `GameService.save()` and `BotGroupService.save()` both call `UUID.randomUUID().toString()` for new entities. `SessionHistoryService` does not.
- **Impact:** Depends on whether MongoDB's id auto-generation is relied on. If so, current behavior is fine. If callers expect a populated id post-save (as they would with `GameService`/`BotGroupService`), they will get a different shape — surprising.
- **Surfaced by:** Phase 4 `SessionHistoryServiceTest.SaveTests.shouldNotGenerateId`, which pins the current behavior so a future change is visible in the test diff.
- **Action:** Decide whether to align with the other services (generate UUID for null/empty ids) or document the divergence. If aligning, also remove `shouldNotGenerateId` and add UUID-generation assertions.

### P7 — `BotGroupBehaviorService.start()` reports success with zero bots
- **File:** `src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java`
- **Behavior:** `createBotsInParallel` catches exceptions per-bot and just logs them. If every single `botFactory.createBot(...)` call throws (e.g. auth server down), `bots` is empty and `start()` completes without throwing. The runtime is registered as ACTIVE, `targetStatus` is persisted as ACTIVE, and the group looks healthy from the database side even though zero bots are running.
- **Why the dead-threshold doesn't catch this:** `monitorHealth` returns early when `bots.isEmpty()` (see line ~556). So a totally failed start is indistinguishable from a successful one from the persisted state.
- **Impact:** A misconfigured/unreachable environment leads to a "healthy" bot group with no bots actually playing. No alert is raised.
- **Surfaced by:** Phase 4 `BotGroupBehaviorServiceTest.StartCreateBotFailureTests.shouldCompleteWithZeroBotsWhenAllCreateBotsFail`, which locks in the current (questionable) behavior.
- **Action:** Either (a) propagate the failure when zero bots were successfully created, OR (b) mark the group as DEAD immediately in that case, OR (c) raise the `monitorHealth` empty-list case to a DEAD transition after some grace period. After fixing, update the test to assert the new behavior.

## Code seams needed for future test phases

### S1 — Inject `Random` into `BettingMiniGameBot` (Phase 5 blocker)
- **File:** `src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`
- **Current:** `private final Random random = new Random();`
- **Why:** `shouldBet()`, `resolveBetAmount()`, and `resolveNextEntryToBet()` all depend on `random.nextInt(...)`. Without a seam, tests for these methods are statistical/flaky.
- **Suggested change:** Constructor or setter parameter, defaulting to `new Random()`. Pure additive; no production behavior change.
- **RESOLVED:** Phase 5+6 QA pass (mvn test green at 246 tests). Dev added package-private `setRandom(Random)` setter; `BettingMiniGameBotTest` exercises `shouldBet`/`resolveBetAmount`/`resolveNextEntryToBet` with deterministic mocked `Random`.

### S2 — Inject a `Sleeper`/`Clock` into `Bot.runWsReconnectLoop` (Phase 6 blocker)
- **File:** `src/main/java/com/vingame/bot/domain/bot/core/Bot.java`
- **Current:** `private void sleep(long millis) { Thread.sleep(millis); ... }` plus a hard-coded `BACKOFF_SECONDS = {5, 10, 30, 60, 60, 60, 60}` array.
- **Why:** Testing the full backoff sequence with real sleeps takes ~4:45 per test run. Need an injectable `Sleeper` interface (or an overridable `protected void sleep(...)`) so tests can fast-forward.
- **Suggested change:** Make `sleep(long)` `protected`, OR extract a `Sleeper` field with a default that calls `Thread.sleep`.
- **RESOLVED:** Phase 5+6 QA pass (mvn test green at 246 tests). Dev changed `sleep(long)` from `private` to `protected`; `BotReconnectTest` uses a `FastBot` subclass that overrides `sleep` to record durations and return immediately, allowing the full 7-step backoff sequence to be asserted in milliseconds.

### S3 — Make Bot/BettingMiniGameBot internal hooks package-private for test access
- **Files:**
  - `src/main/java/com/vingame/bot/domain/bot/core/Bot.java` — `onWsDisconnected`, `runWsReconnectLoop`, `runAuthThenWsLoop`, `performReauth`, `tryReconnectWs`
  - `src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java` — `onSubscribe`, `onStartGame`, `onUpdate`, `onEndGame`, `onWatchdogExpired`, `canBet`, `resolveNextEntryToBet`, `scheduleWatchdog`
- **Why:** All of these are currently `private`. Phase 5+6 tests invoke them via reflection (e.g. `Method m = BettingMiniGameBot.class.getDeclaredMethod("onSubscribe", ActionResponseMessage.class)`). If a method is renamed or its signature changes, the tests fail with `NoSuchMethodException` rather than a compile error — confusing for future contributors.
- **Suggested change:** Drop `private` to package-private (default visibility) on the methods listed above. They are not part of the public API contract; this is a test-seam-only change with no production behavior impact.
- **Surfaced by:** Phase 5+6 QA pass.
- **Severity:** Test infrastructure (S).

### S4 — Inject scheduler factory into `BettingMiniGameBot` for deterministic watchdog tests
- **File:** `src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`
- **Current:** `this.watchdogScheduler = Executors.newSingleThreadScheduledExecutor(...)` is created inside `initializeSubclass()`. Watchdog tests therefore have to use real wall-clock time (configure `watchdogTimeoutSeconds=1`, wait up to 3 seconds for a `CountDownLatch`).
- **Why:** Real-time watchdog tests are slightly slow (~1s each) and theoretically flaky on heavily loaded CI machines.
- **Suggested change:** Add a `setWatchdogScheduler(ScheduledExecutorService)` package-private setter mirroring `setRandom(Random)`. Tests can then inject a `ScheduledExecutorService` that fires tasks immediately or under their control.
- **Surfaced by:** Phase 6 QA pass — current watchdog tests pass reliably with real timers but are noticeably slower than the rest.
- **Severity:** Test infrastructure (S).

### S5 — Sample message JSON fixtures (Phase 7 prerequisite)
- **What:** Need one sample JSON frame per `(product, message-type)` combination: subscribe / startGame / startGameMd5 / updateBet / endGame, across BOM (P_097, P_098), Nohu (P_118), and B52.
- **Source:** Capture from staging logs, or hand-write from existing message classes in `src/main/java/com/vingame/bot/domain/bot/message/g2/{bom,b52}/` and `g4/nohu/`.
- **Destination:** `src/test/resources/messages/<product>/<type>.json`.
- **RESOLVED:** Phase 7 QA pass (mvn test green at 263 tests). User hand-crafted all 15 fixtures (3 products × 5 message types) under `src/test/resources/messages/{bom,nohu,b52}/`. Distinct offsets chosen per product (BOM=2000 → cmd 5000s, Nohu=4000 → cmd 7000s, B52=6000 → cmd 9000s) so the cross-product polymorphism guard test can verify a BOM-only mapper rejects a Nohu fixture with `InvalidTypeIdException`. Tests added: `BomGameMessageTypesTest` (6), `NohuGameMessageTypesTest` (5), `B52GameMessageTypesTest` (5), plus one CMD-arithmetic regression check in `GameMessageTypesResolverTest`.

## Test-quality follow-ups

### T1 — `BotGroupRuntime.stopAllBots` not covered
- **File:** `src/main/java/com/vingame/bot/infrastructure/runtime/BotGroupRuntime.java`
- **Why deferred:** Calls into `Bot.cleanup()` on real bot instances; without a `Bot` test seam, would need either mocked `Bot`s with `cleanup()` stubbed (cheap, OK to do in Phase 4) or a Spring context (overkill).
- **Action:** Either add a single test with mocked `Bot`s when we next touch `BotGroupRuntimeTest`, or fold into Phase 5 alongside the `Bot` work.
- **RESOLVED:** Phase 4 — `BotGroupRuntimeTest.StopAllBotsTests` (4 tests) covers cleanup propagation, executor shutdown, exception isolation, and auxiliary-scheduler shutdown.

### T2 — Six "Tests run: 0" lines in surefire output
- **What:** Maven Surefire emits a roll-up line per outer test class when all tests live inside `@Nested` classes. These look like failures at a glance but are not.
- **Impact:** Cosmetic; can confuse log scanners.
- **Action:** Either disable the roll-up via Surefire config, or accept and document. Low priority.

## How to use this doc

- New issues surfaced by future QA phases should be appended here in the appropriate section with the same shape (file path, what, impact, action).
- When an item is fixed, leave it here with a `**RESOLVED:** <commit/PR>` note rather than deleting — useful as audit trail.
- Severity P1 = active production bug; P2/P3 = correctness/UX; S = test infrastructure; T = test-quality cleanup.
