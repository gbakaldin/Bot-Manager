# QA — PLUGIN_HOT_RELOAD Phase 2b (string keys on the persisted and wire paths)

**Verdict:** PASS
**Build:** `mvn test` → **1939 tests, 0 failures, 0 errors, 0 skipped** (BUILD SUCCESS)

| Module | Baseline `c3fac8a` | Dev `63c31c8` | After QA |
|---|---|---|---|
| bot-api | 128 | 128 | 128 |
| bot-strategies | 122 | **120 (−2)** | 120 |
| bot-messages | 136 | 136 | 136 |
| bot-engine | 426 | 426 | 426 |
| bot-app | 1092 | 1118 (+26) | **1129 (+11)** |
| **total** | **1904** | **1928 (+24)** | **1939** |

Both figures re-measured on this machine, not taken from a handoff: a detached
`git archive` of `c3fac8a` and of `63c31c8` into separate temp trees, `mvn test` in
each, `tests=` summed across every module's surefire XML. **The baseline is 1904 with
the split 128 / 122 / 136 / 426 / 1092**, i.e. `qa-2a.md`'s table, and Dev's `1928` and
its per-module attribution (+26 bot-app, −2 bot-strategies, zero elsewhere) reconcile
exactly.

`skipped=0` in every module in every run, so nothing is silently aborting through the
`Assumptions` escapes that several logging/observability tests carry.

## The −2 in bot-strategies is genuine obsolescence, not coverage loss

The two deleted methods are `BettingStrategyFactoryTest.deprecatedEnumOverloadMatchesStringKey`
and its slot twin. Their entire subject — `create(StrategyId)` / `create(SlotStrategyId)` —
is deleted by this diff, per plan step 2b-4. A test asserting that `create(enum)` and
`create(enum.name())` resolve the same bean has nothing left to assert once one of the two
overloads does not exist; it could not even be compiled.

**The null-contract siblings were retargeted, not dropped.** `deprecatedEnumOverloadRejectsNull`
became `nullKeyRejectedWithIllegalArgumentException` in both files, now driving
`create(null)` against the surviving `create(String)`. The property it guards is unchanged
and is the one that matters: `create(null)` must be an `IllegalArgumentException` whose
message contains `"null"`, **not** an NPE. That is preserved because `LinkedHashMap.get(null)`
is null-tolerant exactly as the pre-2a `EnumMap.get(null)` was — the same reasoning the
Phase 2a guard encoded, now attached to the method that survives. Both assertions run and
pass.

Everything else in the two factory test files, and all of `MartingaleStrategyFactoryWiringTest`,
is a mechanical `create(X)` → `create(X.name())` rewrite. Count-neutral, and the keys are
still sourced from the enum rather than from bare literals, so AD-12's "enum survives as
the compile-time catalogue" stays load-bearing there.

## 1. The Mongo round-trip proof is non-vacuous — verified two ways

`PersistedStrategyKeyCompatTest` is the whole evidence for AD-14's "no migration script"
claim, so it was checked against the two ways such a test goes hollow.

**Provenance of the fixture literals.** Independently re-captured, not taken on trust: a
standalone probe built the same `MappingMongoConverter` / `MongoMappingContext` /
empty-`MongoCustomConversions` wiring against the **pre-change** tree at `c3fac8a`, where
both fields are still enum-typed, and wrote an enum-typed `BotGroup`. It emitted

```
"strategyMix": [{"strategyId": "RANDOM", "weight": 1.0},
                {"strategyId": "MARTINGALE_CLASSIC_CAUTIOUS", "weight": 2.5}],
"slotStrategyId": "FIXED"
```

with `slotStrategyId` a `java.lang.String`, `strategyMix` elements as bare sub-documents
whose key set is exactly `[strategyId, weight]`, and no `_class` hint on the elements —
byte-for-byte what the test's `PRE_MIGRATION_BSON` literal and its `writesTheSameBson` /
`mixElementsCarryNoTypeHint` assertions say. The same probe read the test's literal
document back through the **enum-typed** converter and got `RANDOM` /
`MARTINGALE_CLASSIC_CAUTIOUS` / `FIXED`, so the fixture is a document the old build both
writes and reads. The javadoc's provenance claim is accurate.

**Mutation.** `@Field("slotStrategyKey")` added to `BotGroup.slotStrategyId`, applied in a
`git archive` export (never in the checkout):

```
[ERROR] Tests run: 12, Failures: 3, Errors: 0
  Read.legacyDocumentReadsBackVerbatim   expected: "FIXED" but was: null
  Read.keysAreTheEnumConstantNames       expected: "FIXED" but was: null
  Write.writesTheSameBson                Expecting actual not to be null
```

Exactly the 3-of-12 Dev reported. The test cannot pass against a changed document shape.
It also uses a real converter rather than a mocked repository, which is the specific
failure mode plan Amendment A2 was created by.

## 2. AD-15's validation does replace what Jackson was doing — with four narrowings

**Clean 400 on both verbs, nothing persisted.** `BotGroupStrategyKeyValidationTest` asserts
`400` (not 500) on POST and PATCH for a bad `strategyId` and a bad `slotStrategyId`, and
`verify(repository, never()).save(...)` on each. Mutating `validateStrategyKeys` to
`if (true) return;` turns **7 of its 12** red, all `expected:<400> but was:<200>` — the
test is not vacuous.

**The registries are the real ones.** `Catalogue.catalogueIsReal` asserts
`registeredKeys()` equals every `StrategyId` / `SlotStrategyId` constant name, and the
`@ComponentScan` over `com.vingame.bot.domain.bot.strategy` is a real scan. Proved by
mutation rather than by reading: typing `@StrategyImpl("FIBONACCI_AGGRESSIVE")` to
`"FIBONACCI_AGGRESIVE"` in production turns `catalogueIsReal` red **and**
`Patch.knownKeyAccepted` red with `expected:<200> but was:<400>`. A stubbed
`registeredKeys()` could not produce that.

**Does it reject the same input set?** Probed against the actual pre-change deserializer at
`c3fac8a`. One correction to the handoff first: **this application resolves
jackson-databind 2.15.2, not 2.18.1** (`jackson-core` 2.15.2, `jackson-annotations` 2.20 —
a mixed set; Spring Boot 3.4.0's managed version is 2.18.1 and something downgrades
databind). Dev's probe was labelled 2.18; the results below are from the version the build
actually uses.

| body fragment | enum-typed (`c3fac8a`) | String-typed (2b) |
|---|---|---|
| `"slotStrategyId":""` | 400 — cannot coerce empty String | 400 |
| `"slotStrategyId":"   "` | 400 — trimmed, then empty | 400 |
| `"strategyId":"random"` | 400 — case-sensitive | 400 |
| `"strategyId":"RANDOM "` | 400 — no trimming | 400 |
| `"slotStrategyId":"NONSENSE"` | 400 | 400 |
| `"slotStrategyId":null` | 200 | 200 |
| `"slotStrategyId":0` | **200** — ordinal binds to `FIXED` | **400** |
| `"slotStrategyId":99` | 400 — index out of range | 400 |
| `"strategyId":0` | **200** — ordinal binds to `RANDOM` | **400** |
| `"strategyId":null` | **200** — persisted as a null key | **400** |
| `strategyId` omitted | **200** — persisted as a null key | **400** |

So Dev's claim holds for `""`, `"   "` and `"fixed"` — those were already 400 and still are.
**The four rows Dev's probe missed are the number and null cases**, where the check rejects
a *superset* of what Jackson did. Under AD-23 ("Phase 2 changes no behaviour") these are
technically behaviour changes, 200 → 400.

**QA's judgement: ship them.** Enum-by-ordinal binding is a Jackson accident no client can
be relying on — the DTO has always *emitted* names, so no round-trip through this API
produces an ordinal, and there is no UI path that would. A null key was only ever accepted
into persistence in order to throw later on a bot thread inside
`BettingStrategyFactory.create`, which is precisely the failure AD-15 exists to move
forward. They are now pinned by `BotGroupStrategyKeyCoercionTest` so the narrowing is a
recorded decision rather than something rediscovered from a support ticket, and the
releaser should not be surprised if a hand-written smoke `curl` using an ordinal starts
returning 400.

**One operational consequence that is new and worth naming.** The check runs on the
**post-merge** group, so a group whose *persisted* key stops being registered — a strategy
bean removed in a deploy, or, in later plugin steps, a plugin unloaded — can no longer be
PATCHed **for any field**: renaming it, re-pointing it at another game, or adjusting its
bet bounds all come back 400. The pre-2b behaviour let those edits through and failed at
group start instead. It is recoverable rather than a brick — a PATCH that supplies a valid
`strategyMix` in the same request succeeds — and both halves are now pinned by
`BotGroupStrategyKeyCoercionTest.PersistedKeyNoLongerRegistered`. Not a 2b blocker; it is
the direct consequence of the plan's own "runs on create and PATCH" instruction. Recorded
because nothing else in the suite or the plan states it, and Steps 5–7 will make
"a key the registry no longer claims" a routine condition rather than a deploy accident.

## 3. `BotGroupConfigValidationIT` has never executed — confirmed independently

Three independent confirmations:

1. **Configuration.** The effective POM carries `maven-failsafe-plugin` only inside
   `<pluginManagement>` (inherited from `spring-boot-starter-parent`); it is never bound
   in `<build><plugins>` in any module. `mvn verify` would not run it either. Surefire is
   3.5.2 with a bare `default-test` execution and no `<includes>`, so the defaults apply:
   `**/Test*.java`, `**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java`. `…ValidationIT`
   matches none of them.
2. **Empirically.** `bot-app/target/test-classes/…/BotGroupConfigValidationIT.class` and
   its six nested-class files are present after a full build; there is **no**
   `…BotGroupConfigValidationIT.xml` in `bot-app/target/surefire-reports/`. Confirmed on
   both the baseline and the HEAD build.
3. **Arithmetic.** Adding its 15 test methods would have moved the module count, and the
   count is stable at 1092/1118/1129 across runs.

**Scale: 637 lines, 15 `@Test` methods across six `@Nested` layers, 53
`andExpect` / `assertThat` / `verify` statements — none of which have ever run**, since the
file was written. It is the repository's only `*IT` file, and it is the only production
test code in the repo that is silently unexecuted: the one other non-`*Test` file under
`src/test`, `AsyncQueueMeterFixture`, contains zero `@Test` methods and is a helper by
design; there are zero `@Disabled` annotations anywhere.

Pre-existing and correctly out of 2b's scope. Dev did the right thing: the class was kept
*compiling and correct* (it gains the same `RealStrategyRegistries` inner config the new
test uses, which it needs now that `BotGroupConfigValidationService` takes two more
constructor args) and the fact was written into its javadoc rather than quietly worked
around. **Recommendation for a separate change:** rename it to
`BotGroupConfigValidationTest` (it is a `@WebMvcTest` slice with no external dependency —
it does not need failsafe) and expect to fix whatever 15 never-run tests turn up.

## Tests added / updated (this QA pass)

- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/controller/BotGroupStrategyKeyCoercionTest.java`
  — 11 tests, new. Covers the input classes `BotGroupStrategyKeyValidationTest` does not:
  - `Blanks` (5) — `""`, `"   "` and `"RANDOM "` as `strategyMix` keys, `""` and `"   "`
    as `slotStrategyId`. All 400, all matching the pre-change disposition. The
    `slotStrategyId` cases also pin that `""` is **not** conflated with null, which is the
    one way the AD-14 "null stays meaningful" contract could be broken from the API side.
  - `NarrowedInputs` (4) — the ordinal and null-key rows of the table above, asserted at
    their **current** 400 with the pre-change 200 recorded in the comment.
  - `PersistedKeyNoLongerRegistered` (2) — the post-merge PATCH trap and its escape hatch.

  The class wires the same real `@ComponentScan`ed registries as Dev's test; a stubbed
  catalogue would make every one of its 400 assertions pass for the wrong reason.

## Coverage of the diff

| Production file | Test | What is covered |
|---|---|---|
| `bot-api/…/WeightedStrategy.java` | `PersistedStrategyKeyCompatTest` (12) | BSON read/write both directions, JSON both directions, no `_class` hint, enum-vs-String BSON equivalence |
| `bot-app/…/model/BotGroup.java` (`slotStrategyId`) | same | legacy read, null-stays-null, write shape; mutation-verified |
| `bot-app/…/validation/BotGroupConfigValidationService.java` | `BotGroupStrategyKeyValidationTest` (12), `BotGroupStrategyKeyCoercionTest` (11), `BotGroupConfigValidationServiceTest` (+2) | 400 on POST + PATCH, nothing persisted, catalogue in the body, known keys accepted, null/empty short-circuit never consults a registry, blank/ordinal/null-key boundary |
| `bot-api/…/BotConfiguration.java` (both fields) | `BotGroupBehaviorServiceTest` (retyped) | assignment flows a `String` through to `BotConfiguration`; SLOT override to `FIXED` still fires |
| `bot-api/…/StrategyAssignment.java` | `StrategyAssignmentTest`, `StrategyAssignmentApportionmentMathTest` (retyped) | apportionment, coalescing, largest-remainder tie-break. Tie-break is index-based and never consulted an enum ordinal, so the retype is semantically inert — the pre-change comment claiming `EnumMap` ordinal order was already stale (the map was already a `LinkedHashMap`) |
| `bot-app/…/BotGroupMapper` PATCH semantics | `BotGroupMapperTest` (retyped) | **both** branches of Implementation Note 10 — full-replace when supplied, keep when null — for `strategyMix` and `slotStrategyId`, plus empty-mix rejection |
| `bot-engine/…/BettingMiniGameBot`, `SlotMachineBot` defaults | `BettingMiniGameBot*Test`, `SlotMachineBotSpinStreamTest`, `BotGroupBehaviorServiceTest` | `StrategyId.RANDOM.name()` / `SlotStrategyId.FIXED.name()` defaults |
| `bot-strategies/…/BettingStrategyFactory`, `SlotStrategyFactory` (overload removal) | `BettingStrategyFactoryTest`, `SlotStrategyFactoryTest`, `MartingaleStrategyFactoryWiringTest`, `StrategyCatalogParityTest` | prototype scoping, unknown key, **null key → `IllegalArgumentException` not NPE**, full catalogue parity against a real scan |
| `bot-app/…/BotHealthDTO.strategyId` | `PersistedStrategyKeyCompatTest.JsonWire`, `BotGroupBehaviorServiceTest` | bare name on the wire; `null` renders as `null` |

No production code was changed by QA. No existing test was weakened.

## Gaps

- **The plan's "byte-identical JSON body" check (step 2b-6) is met at DTO level, not at
  controller level.** There is no fixture asserting the whole `GET /{id}` or
  `GET /{id}/health` response body against a captured pre-change string. What exists is
  stronger per-field (`slotStrategyId`, `strategyMix`, `BotHealthDTO.strategyId` all
  asserted as bare names, plus the enum-token/String-token equivalence proved rather than
  assumed) and the retype cannot affect any other field. Release verification P2-4's
  before/after `diff` on a real group is the end-to-end form of this and should still be
  run — it is cheap and it is the only check that sees a real document.
- **No Testcontainers/real-Mongo round trip.** `MappingMongoConverter` is the class
  `MongoTemplate` delegates document mapping to, so a server adds codec-level coverage
  only. Deliberate: P2-4's `mongosh` check on staging covers the real store.
- **The four narrowed input classes are pinned but not agreed.** If anyone objects to
  ordinals or null keys becoming 400, `BotGroupStrategyKeyCoercionTest.NarrowedInputs` is
  the single place to change. Flagged for the Reviewer rather than decided by QA.
- **jackson-databind is 2.15.2 while Spring Boot 3.4.0 manages 2.18.1.** Out of scope for
  2b and it does not affect this verdict — every claim above was measured on the version
  the build actually resolves — but a downgraded databind alongside `jackson-annotations`
  2.20 is a supply-chain oddity worth someone's attention.
- **`BotGroupConfigValidationIT`'s 15 tests / 53 assertions remain unexecuted** after this
  phase, by design. See section 3.

## Failures

None. `mvn test` → 1939 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS across all
five modules.

All mutation testing was performed in `git archive HEAD | tar -x -C <tmpdir>` exports; every
mutated file was byte-compared back to `git show HEAD:<path>` afterwards and the checkout was
never mutated.
