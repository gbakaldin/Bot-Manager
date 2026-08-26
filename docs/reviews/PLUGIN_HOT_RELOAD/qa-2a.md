# QA — PLUGIN_HOT_RELOAD Phase 2a (string keys inside the strategy registries)

**Verdict:** PASS
**Build:** `mvn test` → **1904 tests, 0 failures, 0 errors, 0 skipped** (BUILD SUCCESS)

| Module | Baseline `4d3bca7` | Dev `5ca4cc7` | After QA |
|---|---|---|---|
| bot-api | 128 | 128 | 128 |
| bot-strategies | 111 | 118 (+7) | **122 (+4)** |
| bot-messages | 136 | 136 | 136 |
| bot-engine | 426 | 426 | 426 |
| bot-app | 1091 | 1092 (+1) | 1092 |
| **total** | **1892** | **1900** | **1904** |

Dev's reported deltas reconcile exactly: `StrategyCatalogParityTest` contributes 7 test
methods in bot-strategies, `ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated`
contributes 1 in bot-app, and no test method was deleted anywhere (the only test-side
rename, `registeredIdsForPhase3` → `registeredKeysForPhase3`, is count-neutral). The
deltas land in exactly the two modules the diff adds tests to; bot-api, bot-messages and
bot-engine are untouched by the diff on both the main and test side and their counts are
unchanged.

## Tests added / updated (this QA pass)

- `bot-strategies/src/test/java/com/vingame/bot/domain/bot/strategy/BettingStrategyFactoryTest.java`
  — `deprecatedEnumOverloadMatchesStringKey`, `deprecatedEnumOverloadRejectsNull`
- `bot-strategies/src/test/java/com/vingame/bot/domain/bot/strategy/slot/SlotStrategyFactoryTest.java`
  — the same two, slot side

Both close the same gap: the deprecated `create(StrategyId)` / `create(SlotStrategyId)`
overloads are the *only* thing production calls (`BettingMiniGameBot:178`,
`SlotMachineBot:160`), and until now nothing asserted that their one line of new logic —
`create(id == null ? null : id.name())` — behaves. Specifically:

- **Delegation identity.** `MartingaleStrategyFactoryWiringTest` exercises only the enum
  overload; `StrategyCatalogParityTest` exercises only the string one. Neither compares
  them, though the wiring test's new javadoc claims it "pins that `create(id)` and
  `create(id.name())` resolve the same bean". It does not. Now something does.
- **Null.** Pre-2a the registry was an `EnumMap`, and `EnumMap.get(null)` returns null, so
  `create((StrategyId) null)` threw `IllegalArgumentException`. The `id == null` guard is
  the only thing preserving that under AD-23; without it the same input becomes an NPE.
  Verified red-capable by deleting the guard (mutation 4 below).

## Verification of Dev's claims — done independently

### 1. The eleven string literals match the enum constant names

Extracted both enums' constants and every `@StrategyImpl` / `@SlotStrategyImpl` literal in
`src/main` and compared:

| Registry | Enum constants | Literals in `src/main` |
|---|---|---|
| betting | RANDOM, MARTINGALE_CLASSIC_CAUTIOUS, MARTINGALE_CLASSIC_AGGRESSIVE, PAROLI_CAUTIOUS, PAROLI_AGGRESSIVE, DALEMBERT_CAUTIOUS, DALEMBERT_AGGRESSIVE, FIBONACCI_CAUTIOUS, FIBONACCI_AGGRESSIVE | identical, 9/9 |
| slot | FIXED, RANDOM | identical, 2/2 |

**Would the parity test catch each one, not just Dev's sample?** Yes, structurally:
`everyBettingBuiltinNameIsRegistered` / `everySlotBuiltinNameIsRegistered` loop
`StrategyId.values()` / `SlotStrategyId.values()` and assert containment per constant, so
a typo on *any* of the eleven fails with a named message. Confirmed empirically on two
literals Dev did **not** mutate (below).

### 2. The two failure modes, re-run here, plus two more

All mutations applied to `src/main`, run, then reverted (`git checkout --`); the tree was
clean afterwards.

| # | Mutation | Result |
|---|---|---|
| 1 | `@StrategyImpl("FIBONACCI_CAUTIOUS")` → `"FIBONACCI_CAUTIOUS "` | **7 red**: `StrategyCatalogParityTest` 3/7 (incl. `everyBettingBuiltinNameIsRegistered` naming `StrategyId.FIBONACCI_CAUTIOUS`), `MartingaleStrategyFactoryWiringTest` 4 |
| 2 | `@Component` removed from `ParoliAggressive` | **3 red, all in `StrategyCatalogParityTest`** — the hand-built sibling tests stay green, which is the point |
| 2b | same mutation, run against bot-app | `ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated` **red** under the production `@SpringBootApplication` scan |
| 3 | `@SlotStrategyImpl("FIXED")` → `"FIXEDX"` | **4 red**: parity test 2, `SlotStrategyFactoryTest` 2 |
| 4 | null guard dropped from both deprecated overloads | **2 red** — the tests this QA pass added |
| 5 | `create(id.name().toLowerCase())` in the betting overload | **3 red** incl. `deprecatedEnumOverloadMatchesStringKey` (non-vacuity check) |

Mutation 2 is the one that matters for the Phase-1 hazard the task flags. It is
*invisible* to `BettingStrategyFactoryTest` / `MartingaleStrategyFactoryWiringTest` /
`SlotStrategyFactoryTest`, because those hand the factory `List.of(new ParoliAggressive())`
— the instance itself, so "is it still a bean?" is unaskable. Only the two scanning tests
see it. That is a genuine fix for the `SimpleMeterRegistry` class of defect, not a
restatement of it.

Mutation 2b is the one I most wanted to see and it holds: `ApplicationContextLoadsTest` is
a plain `@SpringBootTest` with no explicit classes, so it resolves `Starter` via
`@SpringBootConfiguration` search and runs the real scan with auto-configuration on top —
not a package scan. A strategy reachable from `com.vingame.bot.domain.bot.strategy` but
unreachable from `Starter` would still slip past `StrategyCatalogParityTest`; it would not
slip past this one.

### 3. `registeredIds()` → `registeredKeys()` — rename is complete

No caller of `registeredIds()` survives anywhere in `src/main` or `src/test` in any module
(repo-wide grep). The only remaining occurrences of the old name are in two historical
review documents (`docs/reviews/MARTINGALE_STRATEGIES/qa.md`,
`.../compliance.md`) describing a test method that has itself been renamed — stale prose in
a signed-off record, not a dangling reference. It was not, and should not be, kept as a
deprecated alias.

### 4. Behaviour identity (AD-23)

- **No `src/main` file outside bot-api and bot-strategies is touched.** `bot-engine` and
  `bot-app` production code is byte-identical, so bot construction, strategy assignment
  (`BotGroupBehaviorService`), the persisted mix and the DTOs are untouched. Phase 2a
  genuinely moves no call site.
- **`StrategyController` does not consult either factory** — it maps `StrategyId.values()`
  directly. Verification P2-3 (`GET /api/v1/strategy/` byte-identical) is therefore
  trivially satisfied in 2a, and `StrategyControllerTest` stayed green unmodified.
- **The only observable change is the one Dev names.** `create` semantics, the
  duplicate-key `IllegalStateException`, the missing-annotation WARN-and-skip, and the
  unknown-key `IllegalArgumentException` are all unchanged; the `EnumMap` →
  `LinkedHashMap` swap re-orders `registry.keySet()` from enum-ordinal to Spring discovery
  order, which surfaces in exactly two places: the once-per-JVM
  `(Betting|Slot)StrategyFactory initialized: registered N strategies — [...]` INFO line
  and the `strategies present: [...]` tail of the lookup exception. The **counts** in that
  line — which is what verification P2-2 greps — are unchanged, and are now also asserted
  at build time (`scanDiscoversTheWholeCatalogue`: 9 and 2).
- **Logging Guidelines:** the init line is once per JVM at application startup, i.e. tier-1
  INFO, and it is unchanged in level and wording. No new log site is introduced by the
  diff, and no per-bot/per-round line moves. `PerBotInfoLogGuardTest` is green.

### 5. Deprecated-overload coverage, both directions

- **String path is not left unexercised.** `StrategyCatalogParityTest` calls
  `create(String)` for all 9 betting and both slot keys; `ApplicationContextLoadsTest` does
  it for two more under the production scan.
- **Enum path is not left unexercised.** `MartingaleStrategyFactoryWiringTest`
  `everyStrategyIdResolvesEndToEnd` calls `create(StrategyId)` for all nine and asserts the
  concrete class; `SlotStrategyFactoryTest` and `BettingStrategyFactoryTest` use it too.
- **The join between them is what was missing**, and is what this pass added.

## Coverage of the diff

| Production file | Test | What is covered |
|---|---|---|
| `bot-api .../StrategyImpl.java`, `SlotStrategyImpl.java` (`String value()`) | `StrategyCatalogParityTest` (all 7) | the literal↔enum-name contract the compiler no longer checks; annotation read back off the resolved bean |
| `bot-strategies` — 9 `@StrategyImpl` + 2 `@SlotStrategyImpl` literals | `StrategyCatalogParityTest.every*BuiltinNameIsRegistered`, `keysAreCleanLiterals`, `scanDiscoversTheWholeCatalogue`; `MartingaleStrategyFactoryWiringTest`, `SlotStrategyFactoryTest` | typo, whitespace, missing bean, missing `@Component`, duplicate claim |
| `BettingStrategyFactory` / `SlotStrategyFactory` `registry` → `LinkedHashMap<String,…>`, `create(String)`, `registeredKeys()` | `BettingStrategyFactoryTest`, `SlotStrategyFactoryTest`, `MartingaleStrategyFactoryWiringTest`, `StrategyCatalogParityTest` | registration, prototype freshness, unknown key, duplicate key, unannotated skip |
| deprecated `create(StrategyId)` / `create(SlotStrategyId)` | **`deprecatedEnumOverloadMatchesStringKey`, `deprecatedEnumOverloadRejectsNull`** (new) + the enum-path tests above | delegation lands on the same bean; null stays `IllegalArgumentException` |
| the whole change under the production scan | `ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated` | both registries fully populated *and* resolvable from `Starter`'s context |

## Gaps

- **`StrategyCatalogParityTest`'s scan is not `Starter`'s scan**, as its own javadoc says.
  `ApplicationContextLoadsTest` covers that, and mutation 2b proves it does — but note the
  asymmetry: the bot-app test asserts *set equality against the enums*, so it catches a
  missing or extra bean, while the fine-grained "which bean claims which key, exactly once"
  assertions live only in the package-scanned test. That is the right split (the bot-app
  context is expensive), but a future plugin-supplied strategy will be invisible to the
  bot-app assertion's `containsExactlyInAnyOrder` and will in fact **break** it — worth
  knowing before Phase 2b/step 3 rather than discovering it then.
- **The null branch of both deprecated overloads is dead in production.** Both call sites
  default before calling (`strategyId != null ? … : StrategyId.RANDOM` /
  `SlotStrategyId.FIXED`). The new tests pin it as an AD-23 behaviour-identity fact, not
  because a null can arrive today.
- **`bot-api` gains no test** for the annotation signature change. There is nothing to test
  there in isolation — an annotation member with no runtime behaviour of its own — and the
  contract it now carries is asserted where it is consumed, in bot-strategies.
- **Discovery order itself is unasserted.** Deliberate: it is Spring's, it is not a
  contract, and AD-21's UI ordering (Phase 2d) sorts built-ins into `StrategyId`
  declaration order rather than reading it. Asserting it would pin an implementation
  detail and would fail on an unrelated bean-name change.
- **Deferred to later sub-phases, as planned:** persisted-BSON round trip
  (`PersistedStrategyKeyCompatTest`, 2b), the `strategyMix` key validation at the API
  boundary (2b, AD-15), and the `StrategyController` response-body pin against a
  non-built-in key (2d). None are reachable in 2a because no persisted or wire type has
  changed yet.

## Failures (if any)

None. `mvn test` is BUILD SUCCESS across all five modules: 1904 run, 0 failures, 0 errors,
0 skipped.
