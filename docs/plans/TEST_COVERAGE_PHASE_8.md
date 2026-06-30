# Test Coverage Phase 8

Date: 2026-06-16
Baseline: `mvn test` -> **445 tests, 0 failures** across 39 test files.
Prior phases (1-7) closed out the original `TEST_COVERAGE_ANALYSIS.md`. Four feature drops since then (`RESTART_LIFECYCLE_FIX`, `CLEANUP_SMALL`, `API_ERROR_FORWARDING`, `TIP_ENDGAME`) added their own tests inline. Phase 8 targets remaining structural gaps in the **infrastructure layer** (auth strategy selection, HTTP client behaviour, environment client registry) and login-request JSON shape — areas the original analysis flagged as "lower priority" that are now the largest untested production surface.

## 1. Goal

Add focused unit tests for the four infrastructure components that still have zero direct test coverage today: `AuthStrategyFactory`, `EnvironmentClientRegistry`, `ApiGatewayClient` (parseable behaviour only), and the three per-product `*LoginRequest` JSON shapes. These are the same classes blamed in two production incidents recorded in CLAUDE.md (Tip login JSON shape mismatch; periodic-logout defaults not propagating from `ProductCode.appId`). Lock down behaviour so future refactors do not silently regress wire-shape.

## 2. Findings - Current State

### Test inventory (39 files)

```
/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/
  common/exception/RestExceptionHandlerTest.java          (advice + classifier arms, 14 tests)
  domain/bot/core/
    BotTest.java                                          (16)
    BotReconnectTest.java                                 (7)
    BotReconnectMdcTest.java
    BotDeadSecondsTest.java
    BotMdcWrapTest.java
    BettingMiniGameBotTest.java                           (21)
    BettingMiniGameBotMdcTest.java
    BettingMiniGameBotTipDispatchTest.java                (TIP_ENDGAME onEndGame dispatch)
  domain/bot/message/
    GameMessageTypesResolverTest.java                     (12)
    g2/bom/BomGameMessageTypesTest.java                   (6)
    g2/b52/B52GameMessageTypesTest.java                   (5)
    g3/tip/TipGameMessageTypesTest.java                   (10 — fixtures + capability)
    g4/nohu/NohuGameMessageTypesTest.java                 (5)
    request/RequestTest.java                              (9)
  domain/bot/service/BotFactoryFailLoudTest.java          (RESTART_LIFECYCLE_FIX, 3)
  domain/bot/util/
    BettingMiniGameStateTest.java                         (6)
    OutputPrinterMdcTest.java
  domain/botgroup/
    controller/BotGroupControllerTest.java                (20+3 health)
    mapper/BotGroupMapperTest.java                        (7)
    service/BotGroupServiceTest.java                      (18 — + username cap arm)
    service/BotGroupBehaviorServiceTest.java              (22)
    service/BotGroupBehaviorServiceRestartTest.java       (RESTART_LIFECYCLE_FIX, 10)
  domain/brand/{controller,service}/                      (1 + 9)
  domain/environment/
    controller/EnvironmentControllerTest.java             (13)
    mapper/EnvironmentMapperTest.java                     (7)
    model/EnvironmentZoneResolutionTest.java              (RESTART_LIFECYCLE_FIX, 5)
    service/EnvironmentServiceTest.java                   (~22 — + headers + periodic-logout)
  domain/game/{controller,mapper,model,service}/          (16 + 8 + 4 + 13)
  domain/session/{controller,mapper,service}/             (10 + 8 + 12)
  infrastructure/observability/
    BotMetricsTest.java                                   (~25)
    BotMdcTagsMeterFilterTest.java
    ObservabilityConfigTest.java
  infrastructure/runtime/
    BotGroupRuntimeTest.java                              (11)
    BotGroupRuntimeDeadSecondsTest.java
```

### What the four post-Phase-7 feature drops covered

| Feature | Tests added | Files |
|---|---|---|
| `RESTART_LIFECYCLE_FIX` | 5 zone-resolver + 3 fail-loud + 10 restart (classifier arms, MDC tags, fail-loud-on-zero-bot restart, log assertion) | `EnvironmentZoneResolutionTest`, `BotFactoryFailLoudTest`, `BotGroupBehaviorServiceRestartTest` |
| `CLEANUP_SMALL` (username cap) | 4 username-length validation arms (at-cap, over-cap, no-cap product, skip-registration migration) | `BotGroupServiceTest.UsernameLengthValidationTests` |
| `API_ERROR_FORWARDING` | 14 advice mapping tests (404 / 400 / 502 with typed bodies) + 2 classifier arms (`BadRequestException` -> validation, `UpstreamLoginException` -> auth) + controller test migrations | `RestExceptionHandlerTest`, `BotGroupBehaviorServiceRestartTest`, plus reshaped controller tests |
| `TIP_ENDGAME` | 10 Tip Jackson fixtures + capability-interface tests + `onEndGame` dispatch integration; obsolete capability hooks deleted | `TipGameMessageTypesTest`, `BettingMiniGameBotTipDispatchTest`, refactored `BettingMiniGameBotTest` |

### What's still untested (post-feature-drop re-baseline)

Annotating the original `TEST_COVERAGE_ANALYSIS.md` §3:

- ~~`Game.getEffectiveBettingOptions`~~ — Phase 1
- ~~`BettingMiniGameState.from`~~ — Phase 1
- ~~`GameMessageTypesResolver`~~ — Phase 1
- ~~`Request.{subscribe,bet,chat,autoBet}`~~ — Phase 1
- ~~`BotGroupRuntime.{getNextBotForLogout,markAsDead,getRunningBotCount,stopAllBots}`~~ — Phase 1+4
- ~~All four MapStruct mappers~~ — Phase 2
- ~~`GameController`, `SessionHistoryController`, `BrandController`, `BotGroupController.getHealth`~~ — Phase 3
- ~~`BotGroupBehaviorService.{start,scheduleRestart,getHealth,monitorHealth,periodicLogout}`~~ — Phase 4 + restart phase
- ~~`SessionHistoryService`, `BrandService`, `BotGroupService.save(skipReg)`~~ — Phase 4
- ~~`Bot.{creditBalance,checkBalance,deposit,initialize,markConnectionAuthenticated,isConnected,isStopped}`~~ — Phase 5
- ~~`Bot.{triggerFullReconnect,runWsReconnectLoop,runAuthThenWsLoop,performReauth}`~~ — Phase 6
- ~~`BettingMiniGameBot.{shouldBet,resolveBetAmount,resolveNextEntryToBet,canBet,on*,beforeReconnect,onWatchdogExpired}`~~ — Phase 5+6
- ~~BOM / Nohu / B52 / Tip JSON polymorphism~~ — Phase 7 + TIP_ENDGAME

**Remaining gaps with concrete file references:**

1. **`AuthStrategyFactory`** — `src/main/java/com/vingame/bot/infrastructure/auth/AuthStrategyFactory.java:18-54`. 55 LOC, zero tests. Switch on `ProductCode` chooses one of four `AuthProfile` shapes (default / Tip / Bom / B52). A future product addition that forgets to wire its `*LoginRequest` will fall into the default branch and silently break login. `ApiGatewayClient.authenticate` (line 144) feeds `loginRequestFactory.apply(ctx)` straight into Jackson — wrong factory = wrong wire shape = upstream rejection with no compile error.

2. **`*LoginRequest` JSON wire shapes** — `domain/bot/auth/{TipLoginRequest,BomLoginRequest,B52LoginRequest}.java`. Each holds hard-coded brand-specific fields:
   - `TipLoginRequest`: `app_id="bc115116"`, `apVer="0.0.912"`, includes `ip`, `aff_id=""` (no underscore variant)
   - `BomLoginRequest`: `app_id="bc114097"`, `apVer="0.0.636"`
   - `B52LoginRequest`: `app_id="bc114098"`, `apVer="0.0.1197"`
   
   These were source of `TIP_LOGIN_JSON_SHAPE_DEPLOY` and `TIP_GWMS_PATHS_DEPLOY` incidents in 2026. Field rename / annotation drop (e.g. `@JsonProperty("app_id")`) silently changes wire shape. Zero round-trip serialization tests today.

3. **`EnvironmentClientRegistry`** — `src/main/java/com/vingame/bot/config/client/EnvironmentClientRegistry.java:69-159`. 160 LOC, zero tests. The `getClients(envId)` cache, the `removeClients` shutdown path, and the `createClients` factory (line 116) all untested. `createClients` does the `ProductCode.appId` ?: `Environment.appId` fallback at line 125-127 — flagged as a back-compat path in CLAUDE.md backlog ("Remove `Environment.appId` field"). A regression that drops the fallback would break every product whose `ProductCode.appId` is still null (P_066, P_103, P_105, P_114, P_118, P_119, P_222 per CLAUDE.md).

4. **`ApiGatewayClient` parseable behaviour** — `src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java`. 510 LOC, zero direct tests; only exercised transitively through `BotGroupServiceTest` mocks. Two methods have testable, mock-friendly logic that is not HTTP-bound:
   - `setDisplayNameWithRetry(username, sessionToken, maxRetries)` at line 384-406. Pure retry loop over `setDisplayName(...)`. Today no test asserts that the retry stops on first success, runs `maxRetries` times on conflict, or returns null on exhaustion. The class hierarchy that `setDisplayName` calls into is HTTP — but `setDisplayNameWithRetry` can be tested with a `Mockito.spy(ApiGatewayClient)` that stubs `setDisplayName` and `displayNameService.getRandomDisplayName`.
   - `authenticate(...)` failure path at line 149-162: catches `RuntimeException`, increments `metrics.incLogin(false)`, and rewraps as `UpstreamLoginException`. The success metric path (line 147) and the rewrap behaviour are uncovered. Testable via a mocked `AuthClient` — but `AuthClient` is constructed inline (`new AuthClient(ctx, factory)`), so a fake-server stub or a partial-mock seam would be required. **Out of scope for Phase 8** — see §8.

5. **`DisplayNameService`** — 86 LOC, zero tests. Loads `display_names.txt` from classpath at `@PostConstruct`. Three observable methods: `getRandomDisplayName`, `hasDisplayNames`, `getDisplayNameCount`. Tests would just need a test resource fixture or `ReflectionTestUtils.setField` to populate the internal list. Low complexity, low risk — included as a sweep item.

6. **`ProductCode.getUsernameMaxLength` direct enum coverage** — `src/main/java/com/vingame/bot/domain/brand/model/ProductCode.java:61-63`. The validation arm in `BotGroupService` tests it indirectly (Phase 4 added the four arms), but the per-enum cap declarations themselves are not asserted. A future commit that flips `P_116`'s cap from `12` to `null` (or vice versa for any other product) wouldn't fail any test. Sweep item.

## 3. Per-aspect readiness / mapping

| Aspect | Status | Notes |
|---|---|---|
| Bot core (`Bot`, `BettingMiniGameBot`) | ready | Phases 5+6 covered behaviour; TIP_ENDGAME added dispatch integration |
| JSON polymorphism (Bom / Nohu / B52 / Tip) | ready | Phase 7 + TIP_ENDGAME |
| Controllers (every endpoint) | ready | Phase 3 + advice tests post-API_ERROR_FORWARDING |
| Services (botgroup, environment, game, session, brand) | ready | Phases 1-4 + recent feature drops |
| Mappers (4 of 4) | ready | Phase 2 |
| Restart lifecycle | ready | RESTART_LIFECYCLE_FIX added 10 tests + zone-resolver + classifier |
| Observability (metrics, MDC filter) | ready | OBSERVABILITY phase |
| **`AuthStrategyFactory`** | **blocked-on-tests** | 55 LOC, zero tests; per-product switch arms uncovered |
| **`*LoginRequest` JSON shape** | **blocked-on-tests** | Brand-specific wire shape never asserted; source of 2 prior incidents |
| **`EnvironmentClientRegistry`** | **blocked-on-tests** | Cache + fallback path uncovered |
| **`ApiGatewayClient.setDisplayNameWithRetry`** | **blocked-on-tests** | Pure retry-loop logic; trivially testable |
| **`DisplayNameService`** | **blocked-on-tests** | 3 small methods; sweep coverage |
| **`ProductCode` per-enum table** | **blocked-on-tests** | `getUsernameMaxLength` / `getAppId` per enum |
| `ClientFactory.buildClient` | out of scope | Builder-style integration with `VingameWebSocketClient.builder()` — tests would assert library internals |
| `GameMsClient` HTTP body shape | out of scope | Same constraint as `ApiGatewayClient`'s HTTP path; no test seam |
| `Starter`, enums w/o behaviour, DTOs | not testable | Per original analysis §3 "Things we should NOT bother testing" |

## 4. Architecture Decisions

1. **No new test infrastructure.** Stay on JUnit 5 + Mockito + AssertJ + Jackson `ObjectMapper`. Do not introduce WireMock, Testcontainers, or `@SpringBootTest`. The original analysis prohibited it and nothing has changed.

2. **For `*LoginRequest` shape tests, assert via a real Jackson `ObjectMapper`** writing the request to a JSON `JsonNode`, not via getter inspection. This is the only assertion that catches `@JsonProperty` regressions (the wire shape is what the upstream gateway sees, not the Java field name). Each `*LoginRequest` test class owns its own `ObjectMapper` (no shared static); each test serializes a request constructed via the public constructor.

3. **For `AuthStrategyFactory`, assert two things per `ProductCode`:** (a) the returned `AuthProfile.loginPath()` / `registrationPath()` / `updateFullnamePath()` / `xToken()`, and (b) the type of the object returned by `loginRequestFactory.apply(mockAuthContext)`. This catches both the path/header arm and the factory selection arm. Use a parameterized test over `ProductCode.values()` plus explicit assertions for the four arms that differ.

4. **For `EnvironmentClientRegistry`, use `Mockito` + `ObjectProvider` mock.** No Spring context. Test `getClients(envId)` cache-or-create idempotence (calling twice returns the same `EnvironmentClients`), `removeClients` calls `shutdown`, and the `ProductCode.appId ?: Environment.appId` fallback at line 125-127 with both branches.

5. **For `ApiGatewayClient.setDisplayNameWithRetry`, use `Mockito.spy(ApiGatewayClient)`** with `doReturn(...).when(spy).setDisplayName(...)` to stub the HTTP call without touching `httpClient`. Initialize the spy via `init(...)` with a stub `AuthProfile` to satisfy `checkInitialized()`. Use `Mockito.spy(displayNameService)` to control `getRandomDisplayName` / `hasDisplayNames` deterministically.

6. **No tests for `ApiGatewayClient.authenticate`, `getBalance`, `registerUsers`, or the HTTP request-build internals.** Those exercise `HttpClient.send` / `AuthClient.authenticate` — both `final` library calls that can't be mocked cleanly without WireMock. Punt to a future integration-test pass.

7. **Sequencing:** Phase 8 ships as one batch of test files (no production-code edits). All items are mutually independent — they can be reviewed and committed in any order.

## 5. Plan

Each item below is one new test class (or extension of an existing one). Estimated LOC includes setup boilerplate (mocks, helpers, AssertJ chains). Reference style: parameterized `@ParameterizedTest` + `@EnumSource` where useful; `@Nested` groups when an item has more than 4-5 tests.

### Item 1 - `AuthStrategyFactoryTest` (new file)

- **File:** `src/test/java/com/vingame/bot/infrastructure/auth/AuthStrategyFactoryTest.java`
- **Production:** `src/main/java/com/vingame/bot/infrastructure/auth/AuthStrategyFactory.java`
- **Tests:**
  - `getAuthProfile_throwsWhenProductCodeIsNull` -> `IllegalArgumentException` with env name in message (line 21).
  - `getAuthProfile_pathsAndXTokenSameAcrossAllProductCodes` parameterized over `ProductCode.values()`: every product returns `loginPath="/gwms/v1/bot/login.aspx"`, `registrationPath="/gwms/v1/bot/register.aspx"`, `updateFullnamePath="/gwms/v1/bot/update-fullname.aspx"`, `xToken="58bc2820612d23c34fe43d0b2c6f7223"`. Catches regressions that change one product's paths.
  - `getAuthProfile_loginRequestFactoryReturnsDefaultLoginRequest_forStandardProducts` parameterized over `{P_066, P_103, P_105, P_114, P_118, P_119, P_222}`: apply factory with a fake `AuthContext`, assert the result is `instanceof DefaultLoginRequest` and field shape (username/appId/fingerprint propagated, no extra fields).
  - `getAuthProfile_loginRequestFactoryReturnsTipLoginRequest_forP116`: apply factory, assert `instanceof TipLoginRequest`, assert `ip` is populated from `botIp` (set via `ReflectionTestUtils.setField`).
  - `getAuthProfile_loginRequestFactoryReturnsBomLoginRequest_forP097`: apply factory, assert `instanceof BomLoginRequest`, assert `ip` populated.
  - `getAuthProfile_loginRequestFactoryReturnsB52LoginRequest_forP098`: apply factory, assert `instanceof B52LoginRequest`, assert `ip` populated.
- **LOC estimate:** ~150 (one test method per arm, plus a parameterized one).
- **Why it matters:** A new `ProductCode` enum constant added without a switch arm fails the enhanced `switch` at compile time today — but a future commit that adds a default branch (or moves to `if/else`) would silently route a new product to the wrong factory. The parameterized test pins the path/xToken arm; the explicit type assertions pin the factory selection.

### Item 2 - `LoginRequestSerializationTest` (new file)

- **File:** `src/test/java/com/vingame/bot/domain/bot/auth/LoginRequestSerializationTest.java`
- **Production:** `TipLoginRequest`, `BomLoginRequest`, `B52LoginRequest`
- **Tests** (one `@Nested` block per product, 3 tests each):
  - For Tip: serialize via `ObjectMapper.writeValueAsString` (or `valueToTree`) and assert:
    - `app_id` = `"bc115116"`
    - `apVer` = `"0.0.912"`
    - `version` = `"0.0.912"`
    - `aff_id` = `""`
    - `fg` = the fingerprint passed to constructor
    - `ip` = the ip passed to constructor
    - `username`, `password`, `os`, `device`, `browser` present with expected values (`os="OS X"`, `device="Computer"`, `browser="chrome"`)
    - No unexpected camelCase variants (`appId` / `appVer` / `affId` must NOT appear at top level)
  - Same for Bom: `app_id="bc114097"`, `apVer="0.0.636"`, `version="0.0.636"`.
  - Same for B52: `app_id="bc114098"`, `apVer="0.0.1197"`, `version="0.0.1197"`.
- **LOC estimate:** ~120 (one helper that pulls fields out of a `JsonNode`, three nested classes).
- **Why it matters:** `TIP_LOGIN_JSON_SHAPE_DEPLOY` (commit `ec968cba`) and `TIP_GWMS_PATHS_DEPLOY` were both wire-shape incidents. A `@JsonProperty("app_id")` drop or field rename today fails no test — it just breaks production auth for one product. These tests are the minimum guard.

### Item 3 - `EnvironmentClientRegistryTest` (new file)

- **File:** `src/test/java/com/vingame/bot/config/client/EnvironmentClientRegistryTest.java`
- **Production:** `src/main/java/com/vingame/bot/config/client/EnvironmentClientRegistry.java`
- **Tests:**
  - `getClients_cachesPerEnvironmentId`: two calls with same envId return the same `EnvironmentClients` instance (`isSameAs`); verify `environmentService.findById` invoked exactly once.
  - `getClients_createsSeparateClientsPerEnvironmentId`: two different envIds yield two different `EnvironmentClients`; `environmentService.findById` invoked twice.
  - `getClients_usesProductCodeAppIdWhenPresent`: env has both `ProductCode.appId="bc114097"` (P_097) and `Environment.appId="legacy"`. Assert that the `ApiGatewayClient` returned has `getAppId() == "bc114097"` (use `ArgumentCaptor` on `apiGatewayClient.init(...)`). Locks in the line 125-127 preference.
  - `getClients_fallsBackToEnvironmentAppIdWhenProductCodeAppIdIsNull`: env has `ProductCode=P_066` (appId null per ProductCode.java:9) and `Environment.appId="custom-id"`. Assert that `init(..., "custom-id", ...)` was called. Locks in the fallback path that CLAUDE.md backlog wants to remove "once all products are populated".
  - `removeClients_callsShutdownAndRemovesEntry`: pre-populate cache (via `getClients`), then `removeClients(envId)`. Verify `EnvironmentClients.shutdown()` invoked, and a subsequent `getClients(envId)` triggers a new `createClients` (i.e. `findById` called twice across the two `getClients` calls).
  - `clearAll_shutsDownAllAndEmptiesRegistry`: pre-populate two envs, call `clearAll()`. Verify both `shutdown()`s, and `registry.size() == 0`.
  - `size_reflectsCurrentCacheCount`: trivial assertion, useful as a sanity check.
- **LOC estimate:** ~180. Need mocks for `EnvironmentService`, `EventLoopGroup`, `ObjectProvider<ApiGatewayClient>`, `AuthStrategyFactory`.
- **Why it matters:** This component sits between every bot and every environment; a cache leak or fallback-removal regression takes the whole service down. Currently zero direct tests.

### Item 4 - `ApiGatewayClientSetDisplayNameWithRetryTest` (new file)

- **File:** `src/test/java/com/vingame/bot/infrastructure/client/ApiGatewayClientSetDisplayNameWithRetryTest.java`
- **Production:** `src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:384-406`
- **Tests:**
  - `setDisplayNameWithRetry_returnsNullWhenNoDisplayNamesAvailable`: stub `displayNameService.hasDisplayNames()=false`. Assert returns `null`, no calls to `setDisplayName(...)`.
  - `setDisplayNameWithRetry_returnsOnFirstSuccess`: stub `setDisplayName` to return `true` on first call. Assert returns the name, `setDisplayName` called once.
  - `setDisplayNameWithRetry_retriesOnConflict_succeedsOnSecondAttempt`: stub `setDisplayName` to return `false` then `true`. Assert returns the second name, `setDisplayName` called exactly twice (with different display names from the spy on `displayNameService.getRandomDisplayName()`).
  - `setDisplayNameWithRetry_returnsNullAfterMaxRetriesExhausted`: stub `setDisplayName` to always return `false`. With `maxRetries=3`, assert returns `null` and `setDisplayName` called exactly 3 times.
  - `setDisplayNameWithRetry_skipsAttemptWhenRandomNameIsNull`: stub `getRandomDisplayName` to return `null` once then a real name. Assert that the null attempt is skipped (no `setDisplayName` call for that iteration) but the loop continues for `maxRetries` attempts total.
- **LOC estimate:** ~140. Use `Mockito.spy(client)` after `init(...)` with a stub `AuthProfile`.
- **Why it matters:** Username collisions are common (the display-names file is shared across all bot groups). The retry loop is the only mitigation; a `break` accidentally moved out of the `if` would silently degrade to "first conflict = no name".

### Item 5 - `DisplayNameServiceTest` (new file)

- **File:** `src/test/java/com/vingame/bot/infrastructure/client/DisplayNameServiceTest.java`
- **Production:** `src/main/java/com/vingame/bot/infrastructure/client/DisplayNameService.java`
- **Tests:**
  - `hasDisplayNames_falseBeforeInit`: brand-new instance, before `init()`. Assert `false`, `getRandomDisplayName() == null`, `getDisplayNameCount() == 0`.
  - `init_loadsDisplayNamesFromClasspathResource`: invoke `init()` (production loads `display_names.txt` which exists in resources). Assert `hasDisplayNames() == true`, `getDisplayNameCount() > 0`.
  - `getRandomDisplayName_returnsOneOfTheLoadedNames`: after `init()`, call `getRandomDisplayName()` 10 times, assert each result is contained in the count-sized population (use reflection to access the internal list, or just assert non-null + non-empty for each call).
  - `init_handlesMissingResourceGracefully`: use `ReflectionTestUtils.setField` to clear the internal list, then construct a test fixture that points at a non-existent resource — verify no exception (production warns and continues). **If too brittle, skip this test and document.**
  - `loadDisplayNames_skipsBlankAndTrimsWhitespace`: write a tiny test that confirms blank lines and surrounding whitespace are filtered. Use a temporary file via `ReflectionTestUtils.invokeMethod` if accessible, otherwise assert against the production resource file's known cleanliness.
- **LOC estimate:** ~100. The trickiest part is testing `loadDisplayNames` with a custom resource — likely easiest to leave at the "load real file + assert non-empty" level and skip the file-content edge cases.
- **Why it matters:** Sweep coverage. The class is tiny but the failure mode (missing file -> all bots get no display name) is silent today.

### Item 6 - `ProductCodeTest` (new file)

- **File:** `src/test/java/com/vingame/bot/domain/brand/model/ProductCodeTest.java`
- **Production:** `src/main/java/com/vingame/bot/domain/brand/model/ProductCode.java`
- **Tests:**
  - `usernameMaxLength_isSetExpectedlyPerProduct` parameterized over all 10 enum values:
    - `P_116` -> `12`
    - all other 9 -> `null`
    
    Locks in the table from CLAUDE.md backlog so a future cap addition (or removal) must update the test.
  - `appId_isSetExpectedlyPerProduct` parameterized:
    - `P_097` -> `"bc114097"`, `P_098` -> `"bc114098"`, `P_116` -> `"bc115116"`
    - all other 7 -> `null` (per CLAUDE.md backlog "remaining: P_066, P_103, P_105, P_114, P_118, P_119, P_222")
  - `fromCode_resolvesByShortCode` parameterized over all 10: `ProductCode.fromCode(pc.getCode()) == pc`. Pins the `@JsonCreator` mapping.
  - `fromCode_throwsForUnknownCode`: `assertThatThrownBy(() -> ProductCode.fromCode("999")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("999")`.
  - `name_isHumanReadableLabel` parameterized: e.g. `P_116.getName() == "TIP"`, `P_097.getName() == "BOM"`. Sweep guard against accidental label edits.
- **LOC estimate:** ~110.
- **Why it matters:** This table is consumed by `AuthStrategyFactory`, `BotGroupService.validateUsernameLength`, and `EnvironmentClientRegistry.createClients`. Each of those three has a test that asserts on one row of the table; this test pins the table itself, decoupling them.

## 6. Implementation Notes / Concerns

1. **`AuthStrategyFactory.botIp` is `@Value("${bot.ip}")`-injected.** In the test, set it via `ReflectionTestUtils.setField(factory, "botIp", "1.2.3.4")` after construction. Without it, the Tip/Bom/B52 factory closures embed a null `ip` and the assertion fails confusingly. Make this explicit in setUp.

2. **`AuthContext` constructor surface.** `AuthStrategyFactory` test must build a valid `AuthContext` to invoke the captured `loginRequestFactory`. The constructor takes `(apiGateway, userName, password, appId, fingerprint, loginPath, xToken)` — check the websocket-parser library signature; today `ApiGatewayClient.authenticate` line 125-133 shows the exact shape.

3. **`EnvironmentClientRegistry`'s `ObjectProvider<ApiGatewayClient>`.** Mock it via `when(provider.getObject()).thenReturn(mockClient)`. Each `createClients` call invokes `provider.getObject()` exactly once.

4. **`ApiGatewayClient.setDisplayNameWithRetry` requires `init(...)` first** because of the `checkInitialized()` guard inside `setDisplayName`. Even though the test stubs `setDisplayName` directly via `Mockito.spy(client)`, the **first call** still passes through the spy and would re-enter the real method if not stubbed. Use `Mockito.doReturn(...).when(spy).setDisplayName(...)` (not `when(spy.setDisplayName(...)).thenReturn(...)`) to avoid actually invoking the real method.

5. **`DisplayNameService.init()` is `@PostConstruct`** — when constructing in a test, you must call `init()` manually (no Spring context). The class loads `src/main/resources/display_names.txt`; that resource is on the classpath at test time so the real file is loaded. Don't rely on a specific count in assertions — assert ">0" and "contains the result of getRandomDisplayName" rather than "== 5078".

6. **`ProductCode.fromCode` uses `pc.code.equals(code)` (case-sensitive on the short code)** — confirm via `ProductCode.fromCode("116") == ProductCode.P_116`. The parameterized test should use the literal `code` field, not `name()`.

7. **`*LoginRequest` Jackson behaviour: assert via `JsonNode`, not `Map`.** `ObjectMapper.valueToTree(req)` is the safest one-liner; `.has("app_id")` + `.get("app_id").asText()` reads exactly what the upstream gateway would see. Avoid `objectMapper.convertValue(req, Map.class)` — it loses ordering and may surface as `appId` instead of `app_id` depending on jackson behaviour.

8. **All six test files are independent and can be reviewed/merged in any order.** Item 6 (`ProductCodeTest`) doesn't depend on items 1 or 3 even though they touch the same enum.

## 7. Open Items

- **No production-code changes proposed.** All six items are pure test additions. If any item turns out to require a test seam (`protected` visibility on a private method, etc.), pause and surface — Phase 5/6 had several such cases that were resolved by changing production visibility; we should not repeat that pattern unannounced.
- **No JaCoCo / coverage tool added.** The current toolchain doesn't have one; the user's brief explicitly said not to add new infrastructure. Coverage is hand-tracked against the analysis docs.
- **HTTP-bound `ApiGatewayClient` methods (`authenticate`, `registerUsers`, `getBalance`, `setDisplayName`)** remain untested at the unit level. They need WireMock or `@SpringBootTest` with a test profile — explicitly deferred per Architecture Decision 6.
- **`ClientFactory.buildClient`** remains untested. The body is a long `VingameWebSocketClient.builder()` chain; testing it would be asserting library internals. Out of scope.
- **`GameMsClient`** remains untested. Same HTTP constraint as `ApiGatewayClient`. Out of scope.
- **`Bot.authenticate` / `logout` / `restart` / `tryReconnectWs` / `onWsDisconnected`** are still uncovered at the unit-test level (the QA for Phase 5-6 flagged this). Restart-specific behaviour was added in `BotGroupBehaviorServiceRestartTest`, but the `Bot.restart()` direct path is not unit-tested. Defer — it's a `client.close() / clientFactory.newClient / start()` chain that's mostly verified transitively.
- **`BettingMiniGameBot.botBehaviorScenario`** (the websocket-parser pipeline DSL) remains untested. The QA for Phase 5-6 documented this as out-of-scope-for-unit-tests; it needs a real client pipeline. Not addressed here.

## Verification

Phase 8 ships test code only. There is no on-server behaviour change to verify on staging. The Releaser's universal smoke test (build + start + `/actuator/health`) is sufficient.

```
$ JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home mvn test
# expect: Tests run: 445 + N_phase_8, Failures: 0, Errors: 0, Skipped: 0
#         BUILD SUCCESS
# where N_phase_8 ~= 35-50 across the six new test files
```

Pre-merge developer verification (run locally, not on the server):

1. `mvn test -Dtest=AuthStrategyFactoryTest` -> all green.
2. `mvn test -Dtest=LoginRequestSerializationTest` -> all green.
3. `mvn test -Dtest=EnvironmentClientRegistryTest` -> all green.
4. `mvn test -Dtest=ApiGatewayClientSetDisplayNameWithRetryTest` -> all green.
5. `mvn test -Dtest=DisplayNameServiceTest` -> all green.
6. `mvn test -Dtest=ProductCodeTest` -> all green.
7. Full `mvn test` -> 0 failures; baseline `Tests run` count grows by exactly the sum of the six items above (no flakiness, no skipped tests).
