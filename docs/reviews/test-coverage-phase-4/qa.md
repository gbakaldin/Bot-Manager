# QA — test-coverage-phase-4

**Verdict:** PASS
**Build:** mvn test → 202 tests, 0 failures, 0 errors (baseline was 149 tests, 0 failures)

## Tests added / updated

### Phase 4 — Service-layer gaps (new files)

- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceTest.java` — 22 tests across 8 nested classes
  - `StartValidationTests` (3): missing `environmentId`, missing `gameId`, already-running short-circuit. All three assert no `botFactory.createBot(...)` call and no leaked runtime entry.
  - `StartCreateBotFailureTests` (1): when every `botFactory.createBot(...)` throws, `createBotsInParallel` swallows the per-bot failures, so `start()` succeeds with zero bots, the runtime is retained, and `botGroupService.save(...)` is invoked with `targetStatus=ACTIVE`. (This is the literal behavior of the production code, not the optimistic spec.) Test cleans up the side-effect runtime by calling `runtime.stopAllBots()`.
  - `ScheduleRestartTests` (2): past time throws `IllegalArgumentException("Scheduled time must be in the future")` with no save; future time persists `scheduledRestartTime` via `ArgumentCaptor<BotGroup>`. Future time is set to `now + 10 years` so the scheduled task never fires during the test.
  - `GetHealthTests` (2): no-runtime → `STOPPED` skeleton DTO; mixed-state runtime → connected/reconnecting/dead/disconnected counts match the documented formula `disconnected = total - connected - reconnecting - dead`.
  - `GroupRunningTests` (3): no-runtime, ACTIVE runtime, DEAD runtime.
  - `StatusGetterTests` (3): `getActualStatus`/`getPlayingStatus` return `STOPPED`/`null` when no runtime, runtime values otherwise.
  - `PeriodicLogoutConfigTests` (5): env override beats global, env-null falls back to global, env enabled+interval works, global-false disables, empty bot list short-circuits. Invokes the private `startPeriodicLogoutScheduler(BotGroupRuntime, Environment)` via reflection (per the plan).
  - `MonitorHealthTests` (3): 4/5 dead at threshold 0.80 → `markAsDead()` + `save()` with `targetStatus=DEAD` + `lastFailureReason` set; 2/4 dead → no save; empty bot list → no save. Invokes the private `monitorHealth(BotGroupRuntime)` via reflection.
  - `@AfterEach` shuts down the `BotGroupBehaviorService` via its `@PreDestroy` hook to release scheduler/botCreationExecutor virtual-thread pools.
  - Shared `mockBot(status, connected)` helper uses `lenient()` because monitorHealth tests don't read the `getTotalBetsPlaced`/`getTotalBetAmount` stubs while getHealth tests do — the same helper covers both.

- `src/test/java/com/vingame/bot/domain/session/service/SessionHistoryServiceTest.java` — 12 tests across 10 nested classes
  - `findById` / `findBySessionId`: found case and `ResourceNotFoundException` not-found case.
  - `findAll`, `findByGameId`, `findByEnvironmentId`, `findByGameIdAndEnvironmentId`, `findJackpotSessions`: each verifies the repository is called and the returned list passes through.
  - `save`: delegates to `repository.save(...)`. Also pinned the (non-obvious) production contract that `SessionHistoryService.save()` does **not** auto-generate a UUID — unlike `GameService.save()` and `BotGroupService.save()` it is a thin delegate. Test name is `shouldNotGenerateId`.
  - `update`: maps DTO onto existing entity via `mapper.updateEntityFromDTO(...)` then saves; throws `ResourceNotFoundException` when not found.
  - `delete`: calls `repository.deleteById(...)`.

- `src/test/java/com/vingame/bot/domain/brand/service/BrandServiceTest.java` — 9 tests in 1 nested class
  - One test per `BrandCode` enum value (G0/G2/G3/G4/G5/GTH), each asserting the exact `containsExactly` list per the hard-coded map.
  - `shouldReturnNonNullResponse` and `shouldExposeAllSixBrandCodes` lock in the cardinality of the map.
  - `shouldReturnStableMapping` confirms the contract is idempotent.

### Phase 4 — Extended existing tests

- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupServiceTest.java` — added nested class `SaveSkipRegistrationTests` (4 tests)
  - `shouldGenerateIdAndSkipRegistration`: `save(newGroup, true)` generates an ID and skips registration. Verified via `verify(apiGatewayClient, never()).registerUsers(any(), any(), anyInt())` and `verify(clientRegistry, never()).getClients(anyString())`.
  - `shouldRegisterWhenSkipFalse`: `save(newGroup, false)` overload still registers (parallel sanity check to the existing `SaveNewGroupTests`).
  - `shouldNotRegisterOnExistingGroupWithSkipTrue`: with `skipRegistration=true` and a pre-set id, no new id is generated and no registration occurs.
  - `twoArgSaveDelegatesToSkipFalse`: confirms the two-arg `save(group)` is just `save(group, false)` — uses a fresh group with a registration result and asserts the gateway is called.
  - Added `anyInt` import to the existing imports block.

- `src/test/java/com/vingame/bot/infrastructure/runtime/BotGroupRuntimeTest.java` — added nested class `StopAllBotsTests` (4 tests)
  - `shouldCleanupAllBots`: every bot in `botInstances` gets `cleanup()` called.
  - `shouldShutDownExecutor`: the virtual-thread executor transitions from `isShutdown()=false` to `true`.
  - `shouldContinueOnCleanupError`: one bot's `cleanup()` throws → other bots still get cleaned up, exception not propagated, executor still shut down.
  - `shouldShutdownAuxiliarySchedulers`: if `healthMonitor` and `logoutScheduler` are present, they are also shut down.
  - This closes the T1 follow-up from `FOLLOWUPS.md`.

## Coverage of the diff

- `BotGroupBehaviorService.java` ← `BotGroupBehaviorServiceTest.java`
  - `start(String)`: environmentId guard, gameId guard, already-running short-circuit, zero-bots-when-all-creates-fail
  - `scheduleRestart(String, LocalDateTime)`: past-time rejection, future-time persistence
  - `getHealth(String)`: STOPPED skeleton DTO, mixed-status aggregation (connected/reconnecting/dead/disconnected)
  - `isGroupRunning(String)`, `getRunningBotCountForGroup(String)`: null-runtime, ACTIVE, DEAD
  - `getActualStatus(String)`, `getPlayingStatus(String)`: null-runtime defaults, runtime values
  - `startPeriodicLogoutScheduler(BotGroupRuntime, Environment)` (private, via reflection): env override semantics for `periodicLogoutEnabled`/`periodicLogoutIntervalMinutes`, empty-bot guard
  - `monitorHealth(BotGroupRuntime)` (private, via reflection): dead-threshold trigger + DEAD persistence; below-threshold no-op; empty-list no-op
  - **Not covered:** `performPeriodicLogout`, `handleBotGroupDeath` is reached transitively via `monitorHealth` but its DB-update exception branch is not separately exercised, `restart(String)` (just `stop` + `Thread.sleep` + `start`), `onStartup()` (Spring lifecycle, integration-test territory), the dead `Trade`/`TradingProcessor`/`ExternalClient` classes at lines 700–729 (out of scope per FOLLOWUPS P4).

- `SessionHistoryService.java` ← `SessionHistoryServiceTest.java` — full public API covered (10 methods).

- `BrandService.java` ← `BrandServiceTest.java` — full public API covered (1 method).

- `BotGroupService.save(BotGroup, boolean)` ← new `SaveSkipRegistrationTests` block in existing `BotGroupServiceTest`.

- `BotGroupRuntime.stopAllBots()` ← new `StopAllBotsTests` block in existing `BotGroupRuntimeTest` (closes T1).

## Gaps

- **`performPeriodicLogout(BotGroupRuntime)` not directly tested.** The plan included this as a target but the method blocks on `Thread.sleep(reconnectDelaySeconds * 1000L)` between `bot.logout()` and `bot.restart()`. Without a sleep seam (S2 in FOLLOWUPS), a unit test either waits ~5 seconds or has to mock `bot.logout()` to throw before the sleep — neither gives a clean assertion. Deferred until S2 is available.
- **`handleBotGroupDeath` DB-save exception path not directly tested.** The happy path is covered transitively by `monitorHealth` tests; the inner `try { botGroupService.save(...) } catch (Exception e) { log.error(...) }` is not separately exercised. Low risk: this is logging only.
- **Restart and onStartup not tested.** `restart()` is `stop` + `Thread.sleep(2000)` + `start`. Real coverage would need integration testing.
- **`SessionHistoryService.save` does NOT generate ids.** This is now pinned by `shouldNotGenerateId`. If we want it to align with `GameService.save()` and `BotGroupService.save()` (which both synthesize UUIDs), that's a small production-code change — see new follow-up below.

## Failures (if any)

None.

## Other observations worth flagging for the next phase

1. **`SessionHistoryService.save()` inconsistency.** It is a thin delegate that does not generate UUIDs for new entities, unlike `GameService.save()` and `BotGroupService.save()`. If MongoDB auto-id generation is relied on, this is fine — but it's an inconsistency worth flagging. Added to `FOLLOWUPS.md` as P6.

2. **`BotGroupBehaviorService.start()` swallowing per-bot failures.** When every single `botFactory.createBot(...)` call throws, `start()` completes "successfully" with zero bots, the runtime is registered as `ACTIVE`, and `targetStatus` is persisted as `ACTIVE`. The bot group then looks healthy from the database's perspective even though no bots are running. The dead-threshold mechanism doesn't trigger because `monitorHealth` returns early when `bots.isEmpty()`. This is a real correctness concern — a totally failed start looks identical to a successful start. Added to `FOLLOWUPS.md` as P7.

3. **`BotGroupRuntime.stopAllBots()` waits up to 30s** for executor termination. With virtual threads and mocked `Bot.cleanup()` this completes in milliseconds, but with real bots the call can block. Worth keeping in mind for any future integration test that ties multiple groups together.

4. **`monitorHealth` reflection access is brittle.** If the method signature changes (e.g. moves to take both a `BotGroupRuntime` and an `Environment`), the tests will break with a confusing `NoSuchMethodException`. A non-test mitigation is to make `monitorHealth` and `startPeriodicLogoutScheduler` package-private. Not done here — that's a production-code change.

5. **Six `Tests run: 0` roll-up lines from Maven Surefire** — same cosmetic issue as Phase 1+2. Final summary is correct: 202/0/0/0.
