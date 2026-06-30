# Compliance — TEST_COVERAGE_PHASE_8

Branch: `feat/test-coverage-phase-8`
Plan reviewed: `docs/plans/TEST_COVERAGE_PHASE_8.md` (HEAD of branch)
Diff reviewed: `git diff 77c797c..feat/test-coverage-phase-8` — 6 new test files, 989 insertions, zero production-code touches, zero deletions.

## Verdict

PASS-with-notes

All six plan items shipped, in the exact paths the plan named, with tests that cover every assertion shape the plan enumerated. No production code was modified, honoring Open Items §7.1. Full `mvn test` is green at 543 tests (baseline 445 + 98 new — count expands beyond the plan's "35-50" estimate because parameterized tests inflate the per-class count via JUnit's argument expansion; assertion-shape counts match the plan).

The three flagged extensions are all legitimate defensive sweeps over branches the plan implicitly assumed but did not enumerate; none changes the plan's intent or contradicts an Architecture Decision. The one flagged anomaly (`DisplayNameService.init()` is additive, not idempotent) is a true production behavior the test correctly pins — though see the note in §"Notes" about the test's misleading name.

## Phase-by-phase

### Item 1 — AuthStrategyFactoryTest
Status: implemented
- File created at the exact path the plan named (`src/test/java/com/vingame/bot/infrastructure/auth/AuthStrategyFactoryTest.java`, 172 LOC).
- All six plan-enumerated assertions present:
  - `getAuthProfile_throwsWhenProductCodeIsNull` — env-name in message asserted.
  - `getAuthProfile_pathsAndXTokenSameAcrossAllProductCodes` — `@EnumSource(ProductCode.class)` over all 10 values, asserts the four constants from the plan (login path, registration path, update-fullname path, X-TOKEN).
  - `getAuthProfile_loginRequestFactoryReturnsDefaultLoginRequest_forStandardProducts` — parameterized over the exact 7 products the plan listed (`P_066, P_103, P_105, P_114, P_118, P_119, P_222`), asserts `DefaultLoginRequest` instance plus username/password/app_id/fg.
  - Three explicit per-product tests for `P_116 → TipLoginRequest`, `P_097 → BomLoginRequest`, `P_098 → B52LoginRequest`, each asserting `ip` is populated from `botIp` (set via `ReflectionTestUtils` per Architecture Decision §6.1).
- One extra: `getAuthProfile_factoryProducesFreshInstanceOnEachCall` asserts the factory closure does not cache. Not in the plan, but a reasonable invariant given the closures hold no state and the plan emphasized "factory selection" as a primary concern.

### Item 2 — LoginRequestSerializationTest
Status: implemented
- File at the exact path the plan named (162 LOC).
- One `@Nested` block per product (Tip / Bom / B52), each with 3 tests per the plan ("3 tests each"):
  - `*_serializesWithExpectedStaticFields` — `app_id`, `apVer`, `version`, `aff_id`, `os`, `device`, `browser` all asserted with the plan's literal values (`bc115116` / `0.0.912` for Tip; `bc114097` / `0.0.636` for Bom; `bc114098` / `0.0.1197` for B52).
  - `*_propagatesConstructorFields` — `username`, `password`, `fg`, `ip`.
  - `*_doesNotEmitCamelCaseVariants` — pins absence of `appId`, `affId`, `fingerprint` (the "no unexpected camelCase variants" guard from the plan, §5 Item 2).
- Uses `ObjectMapper.valueToTree` per Architecture Decision §4.7. Each test instantiates a fresh `ObjectMapper` in `@BeforeEach` (no shared static), matching Architecture Decision §4.2.

### Item 3 — EnvironmentClientRegistryTest
Status: implemented (with two non-load-bearing extensions)
- File at the exact path the plan named (245 LOC). Uses `@ExtendWith(MockitoExtension.class)` and mocks `EnvironmentService`, `EventLoopGroup`, `ObjectProvider<ApiGatewayClient>`, `AuthStrategyFactory` per Architecture Decision §4.4.
- All seven plan-enumerated tests present:
  - `getClients_cachesPerEnvironmentId` (isSameAs + findById once).
  - `getClients_createsSeparateClientsPerEnvironmentId` (two envs → two distinct clients, findById twice).
  - `getClients_usesProductCodeAppIdWhenPresent` — uses `ArgumentCaptor` on `apiGatewayClient.init(...)` to assert `bc114097` per the plan's exact recipe.
  - `getClients_fallsBackToEnvironmentAppIdWhenProductCodeAppIdIsNull` — uses `P_066` (the plan's named example), captures `"custom-env-appid"`.
  - `removeClients_callsShutdownAndRemovesEntry` — pre-populates, removes, then re-`getClients` to verify `findById` is invoked a second time.
  - `clearAll_shutsDownAllAndEmptiesRegistry`.
  - `size_reflectsCurrentCacheCount`.
- Two extras flagged by the dev:
  1. `getClients_fallsBackToEnvironmentAppIdWhenProductCodeIsNull` — covers the third branch of the line-125 short-circuit (`env.getProductCode() == null`), which the plan's two enumerated arms did not explicitly cover. The conditional `env.getProductCode() != null && env.getProductCode().getAppId() != null` has three meaningful paths (productCode null, productCode non-null + appId null, both non-null); enumerating all three is a legitimate completion of the branch table the plan named.
  2. `removeClients_onUnknownEnvIsNoOp` — verifies `removeClients("does-not-exist")` does not blow up. Defensive; not in the plan. Acceptable sweep.

### Item 4 — ApiGatewayClientSetDisplayNameWithRetryTest
Status: implemented (with one non-load-bearing extension)
- File at the exact path the plan named (129 LOC).
- Setup follows Architecture Decision §4.5 / §6.4: real `ApiGatewayClient` constructed with a `SimpleMeterRegistry`, initialized via `init(...)`, then wrapped in `Mockito.spy(real)`. Uses `doReturn(...).when(spy).setDisplayName(...)` (the non-actually-invoking form, per the plan note).
- All five plan-enumerated tests present:
  - `setDisplayNameWithRetry_returnsNullWhenNoDisplayNamesAvailable` — `verify(client, never()).setDisplayName(...)`.
  - `setDisplayNameWithRetry_returnsOnFirstSuccess` — `times(1)`.
  - `setDisplayNameWithRetry_retriesOnConflict_succeedsOnSecondAttempt` — `times(2)` with two different display names.
  - `setDisplayNameWithRetry_returnsNullAfterMaxRetriesExhausted` — `times(3)` with `maxRetries=3`.
  - `setDisplayNameWithRetry_skipsAttemptWhenRandomNameIsNull` — null then real name, asserts only one `setDisplayName` call.
- One extra flagged by the dev: `setDisplayNameWithRetry_zeroMaxRetriesReturnsNull`. Covers the loop-bound boundary case (`for (int attempt = 0; attempt < 0; ...)` never enters). Legitimate boundary sweep; not in the plan, no conflict with it.

### Item 5 — DisplayNameServiceTest
Status: implemented
- File at the exact path the plan named (145 LOC).
- Plan-enumerated tests are present, reshaped into three `@Nested` groups (EmptyState / Loaded / InternalState):
  - `hasDisplayNames_falseBeforeInit` — split into three small tests in `EmptyState` covering `hasDisplayNames=false`, `getRandomDisplayName=null`, `getDisplayNameCount=0` (the plan listed these as a single bullet with `&&`-joined assertions; splitting them is fine).
  - `init_loadsDisplayNamesFromClasspathResource` — `hasDisplayNames=true`, `getDisplayNameCount>0` per plan §5 Item 5.
  - `getRandomDisplayName_returnsLoadedName` and `getRandomDisplayName_returnsNonNullOverManyCalls` — covers the plan's "10 calls, non-null + non-empty" recipe; uses 20 iterations instead of 10. Adds an additional invariant that the result equals its own trim (pinning the "trim + skip blank" contract from `loadDisplayNames`).
  - `init_handlesMissingResourceGracefully` from the plan is omitted; this matches the plan's own escape clause ("**If too brittle, skip this test and document.**" §5 Item 5 bullet 4).
  - `loadDisplayNames_skipsBlankAndTrimsWhitespace` from the plan is replaced by an invariant on a single random sample (`name.equals(name.trim())`); the plan permitted "assert against the production resource file's known cleanliness" as a fallback.
- Two extras in `InternalState` group (`getRandomDisplayName_returnsSoleEntry`, `getRandomDisplayName_alwaysReturnsAMemberOfTheLoadedList`) inject synthetic data via `ReflectionTestUtils.getField` on the internal `displayNames` list. Reasonable for pinning the random-index path.

### Item 6 — ProductCodeTest
Status: implemented (with two non-load-bearing extensions)
- File at the exact path the plan named (136 LOC).
- All five plan-enumerated tests present:
  - `usernameMaxLength_isSetExpectedlyPerProduct` — `@MethodSource` over all 10 entries; `P_116 → 12`, all 9 others → `null`. Matches the plan's table.
  - `appId_isSetExpectedlyPerProduct` — `P_097 → "bc114097"`, `P_098 → "bc114098"`, `P_116 → "bc115116"`, other 7 → `null`. Matches the plan's table exactly.
  - `fromCode_resolvesByShortCode` — `@EnumSource(ProductCode.class)`, `assertSameAs`.
  - `fromCode_throwsForUnknownCode` — `IllegalArgumentException` with `"999"` in message.
  - `name_isHumanReadableLabel` — covers all 10 with their human-readable labels.
- Two extras flagged by the dev:
  1. `fromCode_isCaseSensitive` — asserts `fromCode("P_116")` throws (since `fromCode` matches on the numeric short code `"116"`, not the enum name). Legitimate guard, well-justified by the test comment.
  2. `enum_declaresExpectedProducts` — locks `ProductCode.values().length == 10` so a silent enum addition forces an update to every parameterized table above. Legitimate guard.

## Drift

None requiring a send-back.

## Out-of-scope changes

None. Diff is six test files; zero production-code touches, zero CI/build config changes, zero documentation changes. Honors plan Open Items §7.1.

## Notes

1. **Flagged extension — Item 3 extra test (`productCode == null` arm):** Legitimate. The line-125 short-circuit (`env.getProductCode() != null && env.getProductCode().getAppId() != null`) has three reachable paths; the plan named two and the dev added the third. No plan amendment needed.

2. **Flagged extension — Item 4 extra arm (`zero maxRetries`):** Legitimate boundary case for the `for` loop. No plan amendment needed.

3. **Flagged extension — Item 6 extra invariants (case-sensitive `fromCode` + enum size lock):** Legitimate guards. No plan amendment needed.

4. **Flagged anomaly — `DisplayNameService.init()` additive behavior:** Real. `init()` calls `loadDisplayNames()` which `displayNames.add(...)`s without clearing first (line 45 of `DisplayNameService.java`). Calling `init()` twice doubles the count. The test `init_doesNotDoubleLoadWhenCalledTwiceFromScratch` correctly pins this with `countAfterSecond == 2 * countAfterFirst`. **The test name is misleading** — it says "doesNotDoubleLoad" but asserts that it DOES double-load. Recommend renaming to `init_appendsOnEachCall` or `init_isNotIdempotent_doubleLoadingOnRepeatCall` in a follow-up, but the test itself is correct and not a plan deviation. The behavior may itself be a latent production bug (if `init()` were ever called twice via DI re-init), but that is outside Phase 8's scope — Phase 8 pins observable behavior, not fixes it.

5. **Test-count expansion:** Plan estimated "35-50 across the six new test files". Actual is 98 individual test executions reported by surefire. The inflation comes from `@ParameterizedTest` argument expansions (`AuthStrategyFactory` paths across 10 products = 10 executions, `ProductCode` enum tables × 10 products × 3 sources = 30, etc.). Counting distinct test METHODS, the total is roughly 47 methods, which lands inside the plan's estimate band.

## Amendments to the plan

None. All six items shipped substantively as planned; extensions are additive sweeps over branches the plan implicitly assumed, not deviations from any Architecture Decision or Open Item.
