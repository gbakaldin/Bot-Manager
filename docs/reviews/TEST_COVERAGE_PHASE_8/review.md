# Code Review — TEST_COVERAGE_PHASE_8

Branch: `feat/test-coverage-phase-8`
Reviewed diff: `git diff 77c797c..feat/test-coverage-phase-8` (the 6 test-only commits at the tip; upstream commits already merged/reviewed elsewhere are skipped).

## Verdict

PASS

No `bug` or `security` findings. Two `smell` and one `style` finding worth surfacing; the additive-`init()` anomaly raised by the dev is not a production bug (see Notes).

## Findings

### [smell] DisplayName label contradicts what the test asserts
`src/test/java/com/vingame/bot/infrastructure/client/DisplayNameServiceTest.java:90-103`

The test method `init_doesNotDoubleLoadWhenCalledTwiceFromScratch` is annotated:

```java
@DisplayName("init() is idempotent — calling twice does not duplicate entries when underlying list is reset")
```

but the body asserts the exact opposite:

```java
assertThat(countAfterSecond).isEqualTo(2 * countAfterFirst);
```

The intent (per the inline comment) is to pin the *non-idempotent* additive behavior so a future "guard against double-init" change fails here. That intent is correct and worth pinning — but the human-readable `@DisplayName` will appear in JUnit reports as "init() is idempotent" when in fact the test certifies non-idempotence. A future reader scanning report output will be misled in the exact direction the comment is trying to prevent.

Fix: rename the method and `@DisplayName` to truthfully describe the pinned contract, e.g. `init_isAdditive_calledTwiceDoublesEntries` / `"init() is additive — calling twice doubles the list (regression pin)"`.

### [smell] `ProductCodeTest.fromCode_isCaseSensitive` does not test case sensitivity
`src/test/java/com/vingame/bot/domain/brand/model/ProductCodeTest.java:118-126`

The method is named `fromCode_isCaseSensitive` and is described as such, but the assertion passes the enum-constant name `"P_116"` to `fromCode(...)` — `fromCode` looks up by the short numeric `code` field (`"116"`), so `"P_116"` is rejected as an *unknown* code, not as a case variant. The test acknowledges this in a comment ("the short codes are numeric so case doesn't actually vary") but then keeps the misleading name. As written, this duplicates the assertion in `fromCode_throwsForUnknownCode` rather than exercising any case-sensitivity contract.

Fix: either drop this test (it's already covered) or rename it to reflect what is actually being pinned, e.g. `fromCode_doesNotAcceptEnumConstantName`.

### [style] AuthStrategyFactoryTest uses `Mockito.mock(...)` for spy creation in one place but `spy(...)` elsewhere — minor inconsistency
`src/test/java/com/vingame/bot/config/client/EnvironmentClientRegistryTest.java:243-245`

`mockApiGateway()` calls `org.mockito.Mockito.mock(ApiGatewayClient.class)` with a fully-qualified static reference while the rest of the file imports static `Mockito.times`, `Mockito.never`, etc. The fully-qualified form here is unnecessary — adding `import static org.mockito.Mockito.mock;` and calling `mock(ApiGatewayClient.class)` matches the established style of every other test file in this codebase.

Cosmetic only; flag because the file otherwise follows the static-import convention.

## Notes

**On the dev-flagged anomaly (`DisplayNameService.init()` additive, not idempotent):**

The dev is correct that `init()` calls `loadDisplayNames()` which appends to a never-cleared `ArrayList`. In production this is theoretical because Spring fires `@PostConstruct` exactly once per bean instance. I would *not* file a separate task to fix the production behavior:

- The class is a `@Service` (singleton); `init()` runs once.
- No code path outside `@PostConstruct` invokes `init()`.
- Pinning the current behavior in tests is the right call.

The only action needed is the rename in the first finding above so the `@DisplayName` doesn't say the opposite of what the test does. If the team ever wants idempotence as a real contract, a `displayNames.clear()` at the top of `loadDisplayNames()` is a one-liner and the test rename becomes obvious at that point.

**Things this diff does well:**

- `LoginRequestSerializationTest` asserts on the `JsonNode` view, not getter inspection — this catches the exact class of regression that caused TIP_LOGIN_JSON_SHAPE_DEPLOY. The "does not emit camelCase variants" sub-tests are a nice negative-space pin.
- `EnvironmentClientRegistryTest` exercises both branches of the `ProductCode.getAppId() ?: Environment.getAppId()` fallback at L125-127, and the `productCode == null` third branch — exactly the failure mode the CLAUDE.md backlog flags as ready to clean up.
- `AuthStrategyFactoryTest.getAuthProfile_factoryProducesFreshInstanceOnEachCall` pins instance freshness on each `loginRequestFactory.apply(...)` call — defends against a future `Suppliers.memoize`-style "optimization" silently sharing mutable LoginRequest state across bots.
- `setDisplayNameWithRetry` retry-loop coverage hits all four arms: no-names short-circuit, first-success short-circuit, conflict-then-success, exhaustion-returns-null, plus the `getRandomDisplayName() == null` mid-loop branch and `maxRetries=0` no-call edge.
- No reliance on log-message strings; no exact internal-class-name assertions that would fail on benign refactor.
- AssertJ chaining, `@Nested` grouping, and `@DisplayName` usage all match the established patterns in `BomGameMessageTypesTest` and `BotGroupRuntimeTest`.

**Unhappy-path coverage:**

- `ProductCodeTest`: covers unknown-code throw, case-sensitivity, and enum-size lock.
- `AuthStrategyFactoryTest`: covers `productCode == null` (throws with env name in the message).
- `EnvironmentClientRegistryTest`: covers `removeClients` on unknown envId no-op, cache eviction, and `clearAll`.
- `ApiGatewayClientSetDisplayNameWithRetryTest`: covers all five non-happy arms (no names, max-retries exhausted, null mid-loop, zero retries, conflict-then-recover).
- `DisplayNameServiceTest`: covers empty-state-before-init for all three accessors. The IO-error branch in `loadDisplayNames` is not exercised — would require a `getResourceAsStream` stub or a corrupt fixture — but skipping it is reasonable given the dev's stated scope.

No findings on concurrency, resource lifecycle, logging conventions, null safety, or exception handling — none of the test code introduces any of those concerns.
