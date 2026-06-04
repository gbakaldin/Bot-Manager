# QA — test-coverage-phase-1-2

**Verdict:** PASS
**Build:** mvn test → 149 tests, 0 failures, 0 errors (baseline was 83 tests, 0 failures)

## Tests added / updated

### Phase 1 — Pure logic (new files)

- `src/test/java/com/vingame/bot/domain/game/model/GameTest.java` — 4 tests
  - `getEffectiveBettingOptions` fallback when `bettingOptions` is null
  - `getEffectiveBettingOptions` fallback when `bettingOptions` is empty
  - `getEffectiveBettingOptions` returns configured list when populated
  - `getEffectiveBettingOptions` returns empty list when `numberOfOptions = 0` and `bettingOptions` is null (extra edge case)

- `src/test/java/com/vingame/bot/domain/bot/util/BettingMiniGameStateTest.java` — 6 tests
  - `from(2) -> BET`
  - Parameterized `from(0|1|3|99|-1) -> PAYOUT`

- `src/test/java/com/vingame/bot/domain/bot/message/GameMessageTypesResolverTest.java` — 11 tests
  - `P_097 -> BomGameMessageTypes`
  - `P_098 -> BomGameMessageTypes`
  - `P_118 -> NohuGameMessageTypes`
  - `null -> IllegalArgumentException("ProductCode cannot be null")`
  - Parameterized over `P_066, P_103, P_105, P_114, P_116, P_119, P_222` — each throws `IllegalArgumentException` containing the product code value

- `src/test/java/com/vingame/bot/domain/bot/message/request/RequestTest.java` — 9 tests across 4 nested classes
  - `subscribe()`: cmd = cmdPrefix + 3000, zoneName/pluginName propagate
  - `bet(amount, entryId, sid)`: cmd = cmdPrefix + 3002, zoneName/pluginName propagate, amount/entryId/sid land on the produced `Bet.BetData`
  - `chat(msg)`: cmd = cmdPrefix + 3008, zoneName/pluginName propagate, message lands on `Chat.ChatData.getMgs()`
  - `autoBet(isMini, betEntries)`: cmd = cmdPrefix + 3015, zoneName/pluginName/isMini propagate, entries map -> List<BetEntryInfo> with correct `eid`/`v` values
  - Note: `ActionRequestMessage.zoneName` and `pluginName` are private with no accessor in the library jar. Tests use a tiny reflection helper to read them — this is the only safe option without a serialization round-trip.

- `src/test/java/com/vingame/bot/infrastructure/runtime/BotGroupRuntimeTest.java` — 7 tests across 4 nested classes
  - Constructor initializes `ACTIVE`/`IDLE`/0 failures and empty lists
  - `getNextBotForLogout()` on empty list → null
  - `getNextBotForLogout()` on one bot → always returns that bot
  - `getNextBotForLogout()` on 3 bots, 7 calls → cycles 0,1,2,0,1,2,0
  - `markAsDead()` flips `isGroupDead()` and `actualStatus == DEAD`
  - `getRunningBotCount()` returns 0 when no bots started
  - `getRunningBotCount()` counts only futures with `isDone() == false`
  - `getRunningBotCount()` returns total when all running
  - Reflection is used to inject `botInstances`/`botFutures` directly since building real `Bot`s requires far too many collaborators.
  - Each test shuts down its `runtime.getExecutor()` in a `finally` block so virtual threads do not leak between cases.

### Phase 1 — Strengthened existing filter tests (modifications)

- `src/test/java/com/vingame/bot/domain/game/service/GameServiceTest.java` — filter tests now assert exact values and regex shape
  - `brandCode` / `productCode` / `gameType` filters: assert `query.getQueryObject().get("<field>") == <enum value>`
  - `name` filter: assert criterion is a `java.util.regex.Pattern` with `CASE_INSENSITIVE` flag set and pattern equal to `Pattern.quote("taixiu")` (matches the `Criteria.where("name").regex(Pattern.quote(...), "i")` shape used by `GameService.filter`)
  - Existing string-contains assertions kept; stronger assertions added alongside.

- `src/test/java/com/vingame/bot/domain/environment/service/EnvironmentServiceTest.java` — same strengthening
  - `type` / `brandCode` filters: exact value
  - `name` filter: `Pattern` with `CASE_INSENSITIVE`, pattern equal to `"^" + Pattern.quote("staging env") + "$"` (anchored, matching `EnvironmentService`'s actual shape)
  - Multi-criteria test: assert both exact values

- `src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupServiceTest.java` — same strengthening
  - `environmentId` / `gameId` filters: exact value
  - `name` filter: `Pattern` with `CASE_INSENSITIVE`, anchored regex

### Phase 2 — Mappers (new files)

- `src/test/java/com/vingame/bot/domain/botgroup/mapper/BotGroupMapperTest.java` — 7 tests
  - `toDTO`: round-trips all 23 mapped fields
  - `toDTO(null) -> null`
  - `toEntity`: round-trips all 23 mapped fields
  - `toEntity(null) -> null`
  - `toEntity` default-coercion: primitives default when DTO fields are null
  - `updateEntityFromDTO`: partial-update contract — only `name` provided in DTO, all other 16 fields on entity remain unchanged
  - `updateEntityFromDTO(null, entity)` is a no-op

- `src/test/java/com/vingame/bot/domain/environment/mapper/EnvironmentMapperTest.java` — 7 tests
  - Same shape as BotGroupMapper. Covers all 17 fields the mapper actually copies.
  - **Caveat (not a test bug; a real gap in production code):** `EnvironmentMapper` does **not** map `useJwtAuth`, `periodicLogoutEnabled`, or `periodicLogoutIntervalMinutes`, even though these fields exist on both `Environment` (entity) and `EnvironmentDTO`. Tests do not assert on these fields. See "Gaps" below.

- `src/test/java/com/vingame/bot/domain/game/mapper/GameMapperTest.java` — 8 tests
  - `toDTO` / `toEntity` round-trip all 12 fields including `bettingOptions` list and `md5` boolean
  - `toEntity` default-coercion for `numberOfOptions`/`md5`
  - `updateEntityFromDTO` partial update: only `name` set, all other 11 fields preserved
  - Note: `GameMapper.updateEntityFromDTO` does not call `setId(...)` — id is intentionally not mutable through an update, which the test exercises.

- `src/test/java/com/vingame/bot/domain/session/mapper/SessionHistoryMapperTest.java` — 8 tests
  - Round-trip for all 17 fields including `Instant` timestamps and `Double` RTP values
  - Default-coercion test for nullable primitives
  - Partial-update test: only `gameName` set, all other 15 fields preserved

## Coverage of the diff

This task is not based on a diff — it's a targeted coverage expansion against pre-existing production code identified in `docs/plans/TEST_COVERAGE_ANALYSIS.md`. Mapping of test → production:

- `Game.java` ← `GameTest.java` — `getEffectiveBettingOptions()` (null/empty fallback, configured list)
- `BettingMiniGameState.java` ← `BettingMiniGameStateTest.java` — `from(int)` enum mapping
- `GameMessageTypesResolver.java` ← `GameMessageTypesResolverTest.java` — full enum coverage (3 implemented codes, 7 unimplemented codes, null guard)
- `Request.java` ← `RequestTest.java` — `subscribe/bet/chat/autoBet` cmd arithmetic + field propagation
- `BotGroupRuntime.java` ← `BotGroupRuntimeTest.java` — constructor state, `getNextBotForLogout` round-robin, `markAsDead`/`isGroupDead`, `getRunningBotCount`
- `BotGroupMapper.java` ← `BotGroupMapperTest.java` — full mapper contract
- `EnvironmentMapper.java` ← `EnvironmentMapperTest.java` — full mapper contract for the 17 mapped fields
- `GameMapper.java` ← `GameMapperTest.java` — full mapper contract
- `SessionHistoryMapper.java` ← `SessionHistoryMapperTest.java` — full mapper contract

The strengthened filter assertions in `GameServiceTest`/`EnvironmentServiceTest`/`BotGroupServiceTest` now lock in the exact MongoDB criteria contract (`Criteria.where(field).is(value)` for equality, `Criteria.where("name").regex(Pattern.quote(input), "i")` or anchored variant for case-insensitive name matching). A refactor that swaps `is` for `regex` (or drops the `i` flag, or removes the anchors) would now fail these tests.

## Gaps

Things the diff (i.e. this coverage pass) does **not** cover and why:

- **`EnvironmentMapper` missing-field gap is NOT a test gap — it's a production bug.** The mapper does not copy `useJwtAuth`, `periodicLogoutEnabled`, or `periodicLogoutIntervalMinutes`, even though these fields exist on both entity and DTO. Saving/updating an environment through this mapper silently drops these three fields. **This is in scope for Dev to fix in a separate change.** I did not add tests asserting these fields are mapped because doing so would require a production-code change (out of scope for QA per the agentic workflow rules) and would currently fail.

- **`Bot` core (`Bot.java`, `BettingMiniGameBot.java`) — not covered**, as expected. The analysis document defers this to Phase 5 because it requires a `Random` injection seam and additional test setup. Skipped here per Phase 1+2 scope.

- **Reconnect / watchdog logic — not covered.** Phase 6 work; requires a `Sleeper`/`Clock` seam in `Bot.runWsReconnectLoop`. Skipped per scope.

- **JSON message polymorphism (`BomGameMessageTypes`, `NohuGameMessageTypes` deserialization round-trips) — not covered.** Phase 7 work; requires sample JSON fixtures. Skipped per scope.

- **`BotGroupBehaviorService`, `SessionHistoryService`, `BrandService` — service tests not added.** Phase 4 work. Skipped per scope.

- **`GameController`, `SessionHistoryController`, `BrandController` — no tests.** Phase 3 work, and explicitly deferred until the `IllegalArgumentException -> 404` Phase-0 triage is done (the analysis doc flags this as a prerequisite). Skipped per scope.

- **`BotGroupRuntime.stopAllBots` — not covered.** Calls into `Bot.cleanup()` on real bot instances and shuts down the virtual-thread executor; without a `Bot` test seam this would either need mocked `Bot`s with `cleanup()` stubbed (cheap) or a Spring context (overkill). The five existing tests cover the read-only state of the runtime. Worth adding in Phase 5 alongside the `Bot` work.

## Failures (if any)

None. `mvn test` reports `Tests run: 149, Failures: 0, Errors: 0, Skipped: 0`.

## Other observations worth flagging for the next phase

1. **`EnvironmentMapper` is missing three fields** (`useJwtAuth`, `periodicLogoutEnabled`, `periodicLogoutIntervalMinutes`). Saving via REST API will not persist these. Dev should add the missing lines to `toDTO`, `toEntity`, and `updateEntityFromDTO`. After the fix, `EnvironmentMapperTest` should be expanded to assert these fields.

2. **`Pattern.quote("taixiu")` in `GameService.filter()` and `Pattern.quote(...)` in `EnvironmentService.filter()` / `BotGroupService.filter()` use different anchoring semantics:** Game uses an unanchored `regex(Pattern.quote(name), "i")` ("contains" match, despite the test comment claiming "case-insensitive, contains"), while Environment and BotGroup use `^...$` anchoring (exact match, case-insensitive). The DisplayName on `GameServiceTest.shouldFilterByNameContains` claims "contains" semantics — that matches the code, but Environment/BotGroup are exact matches, not contains. This inconsistency is worth a follow-up: is exact-match the intent for environments/groups but contains for games?

3. **Six `Tests run: 0` summary lines** appear in the `mvn test` output because Maven Surefire prints a roll-up line per outer class when nested `@Nested` classes hold all the tests. These are not real failures — total still shows 149/0/0/0.
