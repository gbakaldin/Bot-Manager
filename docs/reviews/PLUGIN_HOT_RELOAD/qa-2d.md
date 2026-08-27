# QA — PLUGIN_HOT_RELOAD Phase 2d

**Verdict:** PASS
**Build:** `mvn -o clean install` → **1999 tests, 0 failures, 0 errors** (BUILD SUCCESS)

| Module | Baseline `99e0534` | Dev `f6f2228` | After QA |
|---|---|---|---|
| bot-api | 138 | 138 | 138 |
| bot-strategies | 124 | 124 | 124 |
| bot-messages | 158 | 158 | 158 |
| bot-engine | 426 | 426 | 426 |
| bot-app | 1138 | 1152 (+14) | **1153 (+15)** |
| **total** | **1984** | **1998** | **1999** |

Dev's reported count reproduces exactly: +14, all in bot-app
(`StrategyCatalogTest` 6, `StrategyCatalogResponseContractTest` 8). QA adds one
more (below). The plan's second gate also holds: `mvn -pl bot-api
dependency:tree | grep -E 'bot-app|bot-engine|bot-strategies|bot-messages'`
produces no output — no reverse edge.

---

## The claim that mattered most — both halves verified by mutation

Dev's claim: *`StrategyControllerTest` only pins a subset — a length assertion and
six `hasItem` matchers, every one of which passes against an arbitrarily shuffled
list — so it could not have caught a reshuffled UI picker.*

**Method.** All mutations applied to a `git archive` export of `f6f2228` under
`scratchpad/mut`, never to the checkout. Mutation **M1** deletes
`.sorted(DISPLAY_ORDER)` from `StrategyCatalog.bettingStrategies()`, which is the
exact regression AD-21 exists to prevent: the endpoint then serves the registry's
own bean-discovery order.

**M1, run against the whole 1998-test suite** (`mvn -o test
-Dmaven.test.failure.ignore=true`): **6 failures, in exactly the two new classes,
and nowhere else in the repository.**

```
StrategyCatalogTest$Builtins.builtinsAreSortedIntoDeclarationOrder
StrategyCatalogTest$NonBuiltins.nonBuiltinsSortAfterBuiltinsAlphabetically
StrategyCatalogResponseContractTest.idsAreInDeclarationOrder
StrategyCatalogResponseContractTest.noGameTypeBodyIsByteIdenticalToTheBaseline
StrategyCatalogResponseContractTest.bettingMiniBodyIsByteIdenticalToTheBaseline
StrategyCatalogResponseContractTest.taiXiuBodyIsByteIdenticalToTheBaseline
```

`StrategyControllerTest` ran **6/6 green** under that reshuffle. Half one of the
claim is confirmed: the old test is order-blind, and so is every other test in the
build. Without `StrategyCatalogResponseContractTest`, a reshuffled picker reaches
prod with a green build and is caught only by verification P2-3's before/after
`curl` diff — i.e. only if someone remembered the pre-deploy capture.

Half two — the new test failing on reshuffle, on a changed field set and on a
changed value — by mutation, not by reading:

| # | Mutation (production code only) | New contract test | `StrategyCatalogTest` | Old `StrategyControllerTest` |
|---|---|---|---|---|
| M1 | drop `.sorted(DISPLAY_ORDER)` — serve registry order | **FAIL** (4) | **FAIL** (2) | pass (6/6) |
| M2 | extra wire field on the DTO (`@JsonProperty("deprecated")`) | **FAIL** (`everyEntryCarriesTheFullFieldSet`) | pass | pass |
| M3a | non-built-in fallback description `""` → `null` | pass (unreachable via HTTP) | **FAIL** (`fallBackToTheKeyItself`) | pass |
| M3b | `describe()` stops joining the enum copy for built-ins | **FAIL** (4) | **FAIL** | **FAIL** (2) |
| M4 | copy edit: `FIBONACCI_CAUTIOUS` displayName → `"Fibonacci (Safe)"` | **pass — see Gap 1** | pass | pass |
| M5 | `SLOT` falls through to the betting list | **FAIL** (`slotBodyIsStillEmpty`) | pass | **FAIL** |
| M6 | `@Component` removed from `FibonacciCautious` (bean vanishes) | **FAIL** (5) | n/a | **FAIL** (4) |

Two notes on the measurements themselves:

- The order M1 exposes is `RANDOM, MARTINGALE_CLASSIC_AGGRESSIVE,
  MARTINGALE_CLASSIC_CAUTIOUS, DALEMBERT_AGGRESSIVE, DALEMBERT_CAUTIOUS,
  FIBONACCI_AGGRESSIVE, FIBONACCI_CAUTIOUS, PAROLI_AGGRESSIVE, PAROLI_CAUTIOUS` —
  Amendment A4's table exactly, so A4's *sequence* is confirmed live at 2d. Its
  *arithmetic* is not: see Gap 2.
- Maven's incremental compiler will keep a stale mutated `.class` when a mutation
  is reverted by restoring an older-mtime file and no other source in that module
  changed. One intermediate run (M6b) was contaminated that way and was re-run
  after `rm -rf bot-app/target` (M6c). Every result in the table above is from a
  run whose module was actually recompiled.

---

## The other requested checks

**Non-built-in fallback is empty string, not `null` — pinned.** M3a kills
`StrategyCatalogTest$NonBuiltins.fallBackToTheKeyItself`, which asserts both the
whole DTO (`new StrategyInfoDTO("ACME_GRID", "ACME_GRID", "")`) and, separately,
`description()).isNotNull()` with the reason on the assertion. The reasoning
(`"description":null` would render into the picker tooltip) is in the test, in
`StrategyCatalog`'s javadoc and in CLAUDE.md.

**Set semantics — a `StrategyId` whose bean vanished is absent, and the build
fails.** M6 (removing `@Component` from `FibonacciCautious`) fails
`StrategyCatalogParityTest` three times in `bot-strategies` — before `bot-app` is
even reached — with the intended operator-facing message: *"StrategyId.FIBONACCI_CAUTIOUS
has no bean claiming `@StrategyImpl("FIBONACCI_CAUTIOUS")` — either the bean is
missing or its literal is misspelled"*. Forced through to `bot-app` (M6c), the
endpoint serves 8 entries and the contract test fails on length, order and
byte-identity. Note the strongest set guard is actually
`ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated`, which asserts
`containsExactlyInAnyOrderElementsOf(StrategyId.values())` under the **production**
component scan — it pins the set in *both* directions, unlike the parity test,
which deliberately ignores keys with no enum constant.

**`?gameType=SLOT` still returns `[]`, with the reason.** Doubly pinned: M5 kills
both `slotBodyIsStillEmpty` (whose comment states why serving the two real slot
keys would compile, look like an improvement and still be wrong) and the
pre-existing `shouldReturnEmptyListForSlotGameType`. `StrategyCatalog` has no
`slotStrategies()` at all, and its javadoc says that is a contract decision, not
an omission.

**`RealStrategyRegistries` did not weaken `StrategyControllerTest`.**
`git diff 99e0534..f6f2228 -- .../StrategyControllerTest.java` touches only
imports, the class javadoc and the new `@TestConfiguration` — **no assertion line
is added, removed or edited**. The slice now resolves the real
`BettingStrategyFactory` / `SlotStrategyFactory` / `StrategyCatalog` over a real
component scan, so its `$.length() == StrategyId.values().length` assertion is now
a statement about the registry rather than about a stub; M6 confirms it fails when
the registry loses a bean, which it could not have done before.

**`PLUGIN_PLAN.md` is deleted** (582 lines, gone from the working tree and from
`git ls-files`). Surviving references, all deliberate or historical, none in code
or build config: `docs/plans/MODULE_DECOUPLING.md:17` (the note this phase rewrote
into the past tense), `CLAUDE.md:733` (the backlog entry, added this phase, saying
it was deleted and why), `docs/plans/PLUGIN_HOT_RELOAD.md` (its own Findings and
AD-22 — the record of the decision), and six `docs/reviews/**` verdicts from
earlier phases, which are dated records and must not be rewritten.

**CLAUDE.md's new "Plugin registries" section, checked against the code.** Every
factual claim verified: `@StrategyImpl("RANDOM")` on prototype beans (13
`@Scope("prototype")` in `bot-strategies`); `@SlotStrategyImpl("FIXED")`
(`SlotStrategyId.FIXED` exists); `@MessageTypesImpl(gameType, products)` with
`String[] products() default {}` and `BomGameMessageTypes` claiming
`{"097","098"}`; duplicate key fails context refresh in **all three** registries
(`BettingStrategyFactory:118`, `SlotStrategyFactory:80`, `MessageTypesRegistry:257`
and `:280`); zero `MongoCustomConversions` in `bot-app/src/main`;
`BotGroupBehaviorService` contains **no** reference to
`BotGroupConfigValidationService`, so A5's "validate is not on the group-start
path" is true; `slotStrategyId == null` → `SlotStrategyId.FIXED.name()` at
`SlotMachineBot:158` and `BotGroupBehaviorService:793`; operator-facing key lists
rendered through `TreeSet` in all three registries. One number is wrong — Gap 2.

---

## Tests added / updated (QA)

- `bot-app/src/test/java/com/vingame/bot/domain/bot/strategy/controller/StrategyCatalogResponseContractTest.java`
  — **+1 test**, `bodyMatchesTheCheckedInCopyFixture`: compares the parsed response
  tree against a checked-in fixture. Closes Gap 1 (see below). Kills M4, which
  nothing else in the build kills. Nothing existing was changed.
- `bot-app/src/test/resources/strategy/betting-strategy-catalogue.json` — the
  fixture: the nine entries as the endpoint serves them today, ids, labels,
  tooltips and order.
- `bot-app/src/test/java/com/vingame/bot/domain/bot/strategy/controller/StrategyControllerTest.java`
  — **javadoc correction only, no assertion touched**: the "pinning each one here
  means a future copy edit shows up as a test failure" claim is false (M4 proves
  it) and now says so, pointing at the fixture test.

## Coverage of the diff

| Production file | Test | What is covered |
|---|---|---|
| `StrategyCatalog.java` (new) | `StrategyCatalogTest` (6) | built-in copy join, declaration-order sort over a reversed registry, absent built-in, non-built-in fallback (`""` not `null`), non-built-ins sorted after built-ins alphabetically, empty registry |
| `StrategyCatalog.java` | `StrategyCatalogResponseContractTest` (9) | byte-identity vs the pre-2d expression for absent/`BETTING_MINI`/`TAI_XIU`, `SLOT` and `CARD_GAME` → `[]`, id order, wire field set, enum-vs-literal order cross-check, **copy fixture (QA)** |
| `StrategyCatalog.java` | `StrategyCatalogParityTest`, `ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated` | the key **set** the catalogue reads, under a bare scan and under the production scan |
| `StrategyController.java` | `StrategyControllerTest` (6, unchanged assertions, now over the real registries) | path, length, per-id presence, `SLOT`/`CARD_GAME` empty |
| `BotGroupConfigValidationService.java` | — | **javadoc only** in this diff; behaviour unchanged since 2b, still covered by `BotGroupStrategyKeyValidationTest` |
| `CLAUDE.md`, `MODULE_DECOUPLING.md`, `PLUGIN_PLAN.md` deletion | — | docs; checked by hand above |

## Gaps

1. **Display copy was not pinned anywhere — now closed, and worth knowing why it
   was open.** `preChangeBody()` serialises `StrategyId.values()` on *both* sides
   of the byte-identity comparison, so it is self-referential with respect to the
   enum's copy; `StrategyControllerTest`'s "locked-in strings" test reads the same
   getters it asserts against; its only string literal is `"Random"`. M4 (a
   one-word edit to `FIBONACCI_CAUTIOUS`'s displayName, i.e. a user-visible change
   to the picker) passed all 1998 tests. This is pre-existing, not introduced by
   2d, but AD-21's "response contract is preserved exactly" reads as covering it,
   so QA added the fixture test rather than filing it. The fixture is the
   build-time twin of verification P2-3.
2. **"six of nine positions" is arithmetically wrong; it is eight of nine.**
   Amendment A4's own table (reproduced live by M1) agrees with
   `StrategyId.values()` on `RANDOM` and differs in **all eight** remaining
   positions — `PAROLI_*` and `DALEMBERT_*` swap blocks as well as swapping
   cautious/aggressive within each. The figure is repeated in
   `PLUGIN_HOT_RELOAD.md` A4 and AD-21, in `StrategyCatalog`'s javadoc, in
   `BettingStrategyFactory`'s javadoc and in the new CLAUDE.md section. It
   *understates* the divergence, so no decision built on it changes; it is a doc
   correction for Dev, not a code defect.
3. **The non-built-in path can only be exercised with a mocked factory**, because
   no mechanism can register a non-enum key until step 5. Dev says so in
   `StrategyCatalogTest`'s javadoc and the mocking is justified there; the real
   registry is used everywhere the real registry can express the case.
4. **`ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated` will have to
   be relaxed at step 5.** `containsExactlyInAnyOrderElementsOf(StrategyId.values())`
   is exactly right while every key is a built-in and becomes wrong the first time
   a plugin registers one. Not a defect today — a note for whoever ships step 5.
5. **Not covered by any build:** that the frontend picker actually renders in list
   order, and the staging `GET /api/v1/strategy/` before/after diff (P2-3). Both
   are release-side.

## Failures

None. `mvn -o clean install` is green at 1999 tests: 138 / 124 / 158 / 426 / 1153.
