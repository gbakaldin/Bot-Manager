# QA — PLUGIN_HOT_RELOAD_3_4 Phase 3a

**Verdict:** PASS
**Build:** `mvn -o clean test` at `5f4f633` (code tip; `0f142e9` adds only `review-3a.md`) → **3226 tests, 0 failures, 0 errors, 0 skipped** (surefire; failsafe 0 reports, expected before 4b)

Range reviewed: `30b426a..5f4f633`. Worktree `.claude/worktrees/plugin-3-4`. JDK 21.0.2.

## Dev's claims, checked rather than trusted

| Claim | How checked | Result |
|---|---|---|
| Baseline 3216 (api 148 / strategies 126 / messages 352 / engine 977 / app 1613) | Clean detached worktree at `30b426a`, `mvn -o test`, counted `TEST-*.xml` | **Confirmed exactly** |
| After 3226 (148 / 90 / 261 / 1114 / 1613) | `mvn -o clean test` at HEAD, same counter. No stale report files | **Confirmed exactly** |
| +10 = new tests only (D-20) | 2 (D-17 `SlotStrategyFactoryTest`) + 4 (`TaiXiuGameBotRefundCapabilityTest`) + 4 (`StrategyFactoryRequiredTest`) = 10. Module deltas: strategies −36, messages −91, engine +127 moved +10 new | **Confirmed**. The moves kept the total exactly equal |
| Moves A/B/C are pure `git mv` | `git show -M --name-status` + count of `+`/`-` content lines per commit | `c003237` 19×R100, `de87dec` 3×R100, `1dd4d7b` 14×R100. **0 content lines changed in each.** The file sets match the plan's "Class placement" table (17 request + 2 slot) and step 5's list of 14 tests exactly |
| D-17 snapshot test fails against a live view | Mutation: `unmodifiableSet(registry.keySet())` | **Killed** (`registeredKeysIsASnapshot:168`). Extra mutation, a sorted `TreeSet` copy: **killed** (`snapshotKeepsItsOldGuarantees`, `lookupFailureTailIsSortedAndKeyIsQuoted`) |
| D-5 `HasRefund` keeps exact `getGR()` semantics | Read: `refundFor` returns the `long gR` field unfloored, which is what Lombok's `getGR()` returns. No subclass of `TaiXiuEndGameMessage`, and it is the only `HasRefund` implementor. Mutation, engine `refund → 0L`: **killed** by 7 tests across 4 classes, including the pre-existing `TaiXiuGameBotDispatchTest` and both stream tests. Mutation, `refundFor` returns `GX`: **killed** by 5 tests, including pre-existing ones | **Confirmed** |
| D-4 fallbacks throw | Mutation: both fallbacks restored (`new RandomBehaviorStrategy()` / `new FixedBetStrategy()`) | **Killed**: 3/4 `StrategyFactoryRequiredTest` cases (betting, slot, TaiXiu by inheritance) |
| The 20 rewired engine tests select the same strategy classes | grep of every `.strategyId(` / `.slotStrategyId(` in `bot-engine/src/test`: betting fixtures are all `RANDOM` or null, which give `RandomBehaviorStrategy` as before. Slot fixtures are null (→ `FIXED`) apart from `SlotMachineBotSpinStreamTest`. In that class the `FIXED` cases are unchanged. The `RANDOM` case now gets `RandomBetStrategy` from the factory instead of the old `FixedBetStrategy`, but it overwrites the strategy with its own `RandomBetStrategy` by reflection before it asserts anything, so its assertions see the same class as before. `TestStrategyFactories`' scan base contains no test-scoped `@Component` (the `@StrategyImpl` fakes are not components, and the annotation is not meta-`@Component`) | **Confirmed**. One behavioural nuance, documented by Dev |
| `PerBotInfoLogGuardTest` exemption list empty, guard not vacuous | Mutation: `log.info(` added to `RandomBehaviorStrategy` and `CrashBot` → `perBotClassesHaveNoInfoLogging` **fails, naming both**. Mutation: a non-existent exemption re-added → `theGuardIsNotVacuous:260` **fails**. The `contains(...)` anti-vacuity list (bot cores + strategies) is intact | **Confirmed** |
| Plan grep gate: only comment lines | Ran the plan's `git grep -w` | 6 hits, **all comments** |

Broader boundary check, beyond the plan's three-name grep: I grepped every one of the 94 simple class names under `bot-strategies/src/main` + `bot-messages/src/main` with `-w` across `bot-engine/src/main` and `bot-app/src/main`, dropping comment lines. **Zero hits.** That covers same-package (split-package) references that need no import, plus string literals such as `Class.forName`. The `bot-api` reverse edge is enforced by the compiler already: `bot-api/pom.xml` has no plugin dependency.

## Red-by-design commits `de87dec` and `1dd4d7b`

Both reproduced in a scratch worktree:
- `de87dec`: main compiles, and `bot-strategies` **test-compile fails** (`StrategyCatalogParityTest`, `MartingaleStrategyFactoryWiringTest`, …: cannot find symbol).
- `1dd4d7b`: builds, 3218 tests, **1 failure**: `PerBotInfoLogGuardTest.theGuardIsNotVacuous:260` (stale exemption path). It goes green at `bf61dc8`.

Assessment:
- **`de87dec` + `1dd4d7b` should be squashed** into one pure-move commit before this branch is shared. The squash is still 17×R100 with zero content lines, so D-3's purpose (rename similarity, so the stack's later edits to `MessageTypesRegistry` follow the file) is fully preserved. It also removes a commit where the build does not even test-compile, which is the worst kind for `git bisect`. The plan listing B and C as separate steps is a sequencing note. Nothing depends on them being separate commits. The branch is unpushed, so this costs nothing.
- **The single-test red at `1dd4d7b` → `bf61dc8` is inherent and acceptable.** It cannot be made green on either side while keeping the move pure. If the exemptions are dropped before the move, the factories' startup INFO lines become guarded and `perBotClassesHaveNoInfoLogging` fails. If they are kept after the move, the existence check fails. The only fix is to fold `bf61dc8` into the move commit, which breaks the literal "only `git mv`" rule (though not R100 on the moved files). Keeping it documented, as Dev did, is fine. Folding it in is equally fine if the user prefers a bisect-clean history.
- QA has not rewritten history. That is Dev's or the user's call.

## Tests added / updated

None. Every behaviour 3a introduces is already pinned, and each pin was mutation-killed above:
- D-17: `SlotStrategyFactoryTest` (2 new)
- D-5: `TaiXiuGameBotRefundCapabilityTest` plus the pre-existing refund stream/dispatch tests
- D-4: `StrategyFactoryRequiredTest`
- Registry discovery after the move: `ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated` (real `Starter` scan, exact set equality, and `MessageTypesRegistry`) is green at HEAD
- Logging guard: `PerBotInfoLogGuardTest`

I deliberately did **not** add a source-scan test asserting that engine/app main names no plugin class. 3b step 1 moves the plugin modules to `test`/`runtime` scope, so the compiler enforces this for every reference, including same-package ones, and step 2 adds the enforcer. A source scan written now would be a weaker duplicate with a one-phase lifetime. Until 3b lands, the boundary is held by the grep above (clean) and nothing else.

## Coverage of the diff

- `bot-engine/.../strategy/slot/SlotStrategyFactory.java` (D-17) ← `SlotStrategyFactoryTest` (snapshot independence, unmodifiable, discovery order)
- `bot-api/.../message/HasRefund.java`, `bot-messages/.../taixiu/TaiXiuEndGameMessage.java#refundFor`, `bot-engine/.../core/TaiXiuGameBot.java#balanceCreditFor` (D-5) ← `TaiXiuGameBotRefundCapabilityTest`, `TaiXiuGameBotDispatchTest`, `TaiXiuGameBotStreamTest`, `TaiXiuJackpotGameBotStreamTest`
- `bot-engine/.../core/BettingMiniGameBot.java`, `SlotMachineBot.java` (D-4) ← `StrategyFactoryRequiredTest`, plus the 20 rewired fixtures through `TestStrategyFactories`
- 36 pure moves (A/B/C) ← unchanged tests in their new modules. Count preserved exactly
- `PerBotInfoLogGuardTest` edit ← self (mutation-checked)
- `CLAUDE.md` ← n/a (one accurate paragraph)

## Gaps

- **Engine→plugin boundary is unguarded until 3b.** Clean by grep today. 3b's scope change plus the enforcer is the guard (see above).
- **`bot-engine/.../domain/bot/strategy/` is outside `PerBotInfoLogGuardTest`'s scan.** Today it holds only the two factories, whose INFO line fires once per process, so this is correct. If per-bot engine-side strategy support code ever lands there, it would not be guarded. Noting it for whoever adds the first such class; no action for 3a.
- **`refundFor` "unfloored" is not pinned against a negative `gR`.** No test distinguishes `return gR` from `Math.max(0, gR)`. The wire value is never negative, and the old code did not floor either, so this is an equivalent mutant in practice. Not worth a test.
- Deploy-side checks (C-1..C-6, D1) are post-3b and out of scope here.

## Failures

None at HEAD. The only reds are the two documented intermediate commits above.
