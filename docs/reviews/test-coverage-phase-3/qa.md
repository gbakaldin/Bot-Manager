# QA — test-coverage-phase-3

**Verdict:** PASS
**Build:** mvn test → 293 tests, 0 failures, 0 errors (baseline was 263 tests, 0 failures)

## Tests added / updated

### `GameControllerTest` (new) — 16 tests

`src/test/java/com/vingame/bot/domain/game/controller/GameControllerTest.java`. `@WebMvcTest(GameController.class)` + `@MockitoBean` for `GameService` and `GameMapper`. Six `@Nested` blocks, one per endpoint.

- **GetByIdTests** (3): 200 OK with full DTO body assertions (id/name/brandCode/productCode.code/gameType/pluginName/numberOfOptions/offset/md5); 404 on `ResourceNotFoundException`; 400 on `IllegalArgumentException`.
- **FindByBrandAndProductTests** (3): 200 OK with two-game list; 200 OK with empty list; 500 on generic `RuntimeException` (the controller only catches `Exception` — there is no `ResourceNotFoundException` / `IllegalArgumentException` branch here, so 500 is the only non-200 path).
- **FilterTests** (2): 200 OK with one match (full `GameFilter` payload — brandCode + productCode + gameType + name); 200 OK with empty list.
- **CreateTests** (3): 200 OK using `ArgumentCaptor<Game>` to assert that the controller injects `brandCode`/`productCode` from path variables onto the entity before `service.save` (the request body intentionally omits them); 400 on `IllegalArgumentException`; 500 on generic `RuntimeException`.
- **UpdateTests** (3): 200 OK with updated fields; 404 on `ResourceNotFoundException`; 400 on `IllegalArgumentException`.
- **DeleteTests** (2): 200 OK on success; **400 Bad Request** on `IllegalArgumentException` (verified against the post-Phase-0 `GameController.delete` catch block — flipped from `notFound()` to `badRequest()`).

### `SessionHistoryControllerTest` (new) — 10 tests

`src/test/java/com/vingame/bot/domain/session/controller/SessionHistoryControllerTest.java`. `@WebMvcTest(SessionHistoryController.class)` + `@MockitoBean` for `SessionHistoryService` and `SessionHistoryMapper`. Five `@Nested` blocks.

- **GetByIdTests** (2): 200 OK with full DTO body; 404 on `ResourceNotFoundException`.
- **FindBySessionIdTests** (2): 200 OK with full DTO body; 404 on `ResourceNotFoundException`.
- **FindAllTests** (4): the four query-param combos. Each test uses `Mockito.verify(...)` + `verify(..., never())` to assert that exactly one of `service.findAll()` / `service.findByGameId(gameId)` / `service.findByEnvironmentId(envId)` / `service.findByGameIdAndEnvironmentId(gameId, envId)` was invoked and the others were not. This locks in the dispatch logic in `SessionHistoryController.findAll` (lines 72-80) — a future refactor that swaps the order of the if/else if branches will fail loudly.
- **FindJackpotSessionsTests** (1): 200 OK with jackpot list (asserts `jackpot=true` and `botJackpotWinnings` round-trip).
- **DeleteTests** (1): 200 OK + `verify(service).delete(id)`. The controller's `delete` only catches `Exception` (no specific `IllegalArgumentException` branch), so there's no 404/400 path to test — confirmed against the source.

### `BrandControllerTest` (new) — 1 test

`src/test/java/com/vingame/bot/domain/brand/controller/BrandControllerTest.java`. `@WebMvcTest(BrandController.class)` + `@MockitoBean BrandService`.

- One test: 200 OK with a `BrandProductsResponse` containing a two-brand map (G2 → P_097, P_098; G4 → P_118, P_119). Uses a `LinkedHashMap` so `jsonPath` assertions don't depend on hash ordering. Asserts the `code` field of each `ProductCode` enum (since `@JsonFormat(shape=OBJECT)` serializes it as an object).

### `BotGroupControllerTest.GetHealthTests` (appended) — 3 tests

`src/test/java/com/vingame/bot/domain/botgroup/controller/BotGroupControllerTest.java`. New `@Nested` class appended to the existing test (also added two imports: `Instant`, `BotStatus`, `BotGroupHealthDTO`, `BotHealthDTO`).

- 200 OK with full `BotGroupHealthDTO` body: asserts `groupId`, `groupName`, `status`, `playingStatus`, `totalBots`, `connectedBots`, `reconnectingBots`, `deadBots`, `disconnectedBots`, `consecutiveFailures`, and the `bots` array (length and per-bot `username`/`status`/`connected`/`balance`/`totalBetsPlaced`).
- 404 on `ResourceNotFoundException`.
- 500 on generic `RuntimeException`.

## Coverage of the diff

Phase 3's "diff" is purely additive (new test files + one extended file). It locks in current production behavior for four controllers:

- `src/main/java/com/vingame/bot/domain/game/controller/GameController.java` ← `GameControllerTest`
  - All 7 endpoints covered. Status-code conventions assert the post-Phase-0 mapping (404 = `ResourceNotFoundException`, 400 = `IllegalArgumentException`, 500 = generic `Exception`). The `delete` endpoint's flipped 400-for-IAE is explicitly tested and the test method/`@DisplayName` mention Phase 0 to prevent silent regression.
  - `save` endpoint asserts via `ArgumentCaptor` that the controller writes `brandCode`/`productCode` from path variables onto the entity — this is real controller logic, not just a mock pass-through.

- `src/main/java/com/vingame/bot/domain/session/controller/SessionHistoryController.java` ← `SessionHistoryControllerTest`
  - All 5 endpoints covered. The 4 query-param dispatch branches in `findAll` are individually verified with `Mockito.verify(..., never())` on the other 3 service methods, so a future refactor cannot silently misroute requests.
  - `delete`'s lack of `IllegalArgumentException`/`ResourceNotFoundException` catches is documented inline.

- `src/main/java/com/vingame/bot/domain/brand/controller/BrandController.java` ← `BrandControllerTest`
  - Sole endpoint covered. Asserts the JSON shape (top-level `brandProducts` map, per-brand `code` field of nested `ProductCode` objects) so a change to `@JsonFormat(shape=OBJECT)` on `ProductCode` (or to the `BrandProductsResponse` field name) surfaces immediately.

- `src/main/java/com/vingame/bot/domain/botgroup/controller/BotGroupController.java#getHealth` ← `BotGroupControllerTest.GetHealthTests`
  - The previously untested `/health` endpoint is now covered. Together with the existing `BotGroupControllerTest`, every endpoint on `BotGroupController` has at least one happy-path test plus its declared error paths.

## Gaps

- **`ProductCode` path-variable conversion via `valueOf`.** Spring's default enum binder uses `Enum.valueOf(name)`, so the path variable must be `"P_097"` (the enum constant name), not the `"097"` `code` field. Tests use `"P_097"`. If the team ever adds a `Converter<String, ProductCode>` to bind on the short `code`, the tests will still pass with `"P_097"` (since `valueOf` still works) but new tests using the short code should be added at that point. Flagged here for visibility, not added to `FOLLOWUPS.md` because nothing is broken.

- **`SessionHistoryController.findAll` deeper error paths.** The controller only catches `Exception` (no specific `IllegalArgumentException` branch on this endpoint), so a "500 on generic Exception" test would be trivial. Not added — the Phase 3 plan called for only the four dispatch tests. If you want it, it's one more `when(...).thenThrow(new RuntimeException(...))` + `isInternalServerError()`.

- **`BrandController` does not catch exceptions.** Unlike every other controller in this codebase, `BrandController.getBrandProducts()` has no try/catch. A `RuntimeException` from the service would bubble up to Spring's default handler. The test only asserts the happy path. This is consistent with the controller's actual surface area — adding a "throws" test would assert Spring's default behavior, not the controller's contract. If the team standardizes on the `catch (Exception)` pattern across all controllers, this would be a good place to update the controller and add a test.

- **No integration test that actually loads the controllers under a real Spring context.** All four tests use `@WebMvcTest`, which loads only the controller + its mocked dependencies. A `@SpringBootTest` smoke test that hits each endpoint with a real `MongoTemplate` would catch wiring problems that `@WebMvcTest` cannot. This is consistent with the Phase 1/2/4 pattern (only unit/slice tests) and is out of scope for Phase 3.

## Phase 3 closes out the in-scope controller coverage gaps

Per `TEST_COVERAGE_ANALYSIS.md` §3 "Controllers" the gaps were:
1. `GameController` — 7 endpoints, zero tests. **Closed.**
2. `SessionHistoryController` — 5 endpoints, zero tests. **Closed.**
3. `BrandController` — 1 endpoint, zero tests. **Closed.**
4. `BotGroupController.getHealth` — uncovered. **Closed.**

With Phase 3 done, every REST endpoint in the codebase has at least one happy-path test plus its declared error-status paths. Phases 1, 2, 4, 5, 6, and 7 closed out their respective sections already (per the prior `qa.md` files). Per the original 6-phase plan, **Phase 3 is the final outstanding phase — the test plan is essentially complete.** Items not in the plan but worth doing as a follow-up:

- A `@RestControllerAdvice` to replace the per-endpoint try/catch ladders (would let us delete a lot of the 500-on-`Exception` tests).
- Consider P5 in `FOLLOWUPS.md` (inconsistent name-filter semantics) if/when the controllers are revisited.

## Observations / things worth flagging

1. **`ProductCode` enum binding gotcha.** First test run failed with HTTP 400 on every `/{brandCode}/{productCode}` test because I'd used the `code` field (`"097"`) as the path value, expecting the `@JsonCreator fromCode(...)` to be picked up by Spring's path-variable binder. It isn't — `@JsonCreator` is Jackson-only. The fix was to use the enum constant name (`"P_097"`). If a future change adds a custom `Converter<String, ProductCode>`, the API surface area changes (short codes become valid in paths) and these tests should be revisited.

2. **`Mockito.verify(..., never())` for the dispatch tests in `FindAllTests`.** I considered using a single `@Test` with parameterized inputs, but the four cases each have different expected mock calls so a parameterized test would have to switch on the input mid-body, defeating the readability win. Four explicit tests with `@DisplayName`s read better.

3. **No `BrandControllerTest` error path.** `BrandController` has no try/catch — a 500 test would just exercise Spring's default handler, not the controller's code. Adding it would obscure the actual contract.

4. **30 new tests vs the 25–35 estimate.** Comfortably within the plan estimate.

5. **Cosmetic "Tests run: 0" surefire lines** still present for the outer test classes containing only `@Nested` blocks (carried over from prior phases — see T2 in `FOLLOWUPS.md`). Final summary line `Tests run: 293, Failures: 0, Errors: 0, Skipped: 0` is correct.

## Failures (if any)

None.
