# QA — PLUGIN_HOT_RELOAD Phase 2c (message-types registry)

Branch: `feature/plugin-hot-reload-2c` @ `65672cb` (5 commits off `c3fac8a`), evaluated
standalone against `c3fac8a`.
Worktree: `/Users/gleb/IdeaProjects/Bot/.claude/worktrees/agent-a549f50085f102219`.

**Verdict:** PASS
**Build (2c as handed off):** `mvn -q test` → **1915** tests, **0** failures, **0** errors, 0 skipped
**Build (with the QA tests added):** `mvn -q clean test` → **1931** tests, **0** failures, **0** errors, 0 skipped

| Module | `c3fac8a` | 2c @ `65672cb` | + QA | Δ (QA) |
|---|---|---|---|---|
| `bot-api` | 128 | 128 | 128 | — |
| `bot-strategies` | 122 | 122 | 122 | — |
| `bot-messages` | 136 | 145 (+9) | 155 | +10 |
| `bot-engine` | 426 | 426 | 426 | — |
| `bot-app` | 1092 | 1094 (+2) | 1100 | +6 |
| **Total** | **1904** | **1915** | **1931** | **+16** |

Dev's handoff figure of 1915 (+9 `bot-messages`, +2 `bot-app`) is **exact** — re-measured
by parsing every module's surefire XML, not taken on trust. Reverse-edge gate green:
`mvn -pl bot-api dependency:tree` and the same for `bot-messages` name none of
`bot-app` / `bot-engine` / `bot-strategies`.

All mutation work was done in `git archive HEAD | tar -x` sandboxes under the scratchpad.
The worktree never held a mutated source file; the only files it gained are the three test
files below.

---

## Tests added / updated

- `bot-messages/src/test/java/com/vingame/bot/domain/bot/message/MessageTypesRegistryValidationTest.java`
  — **10 tests**, the registry's six fail-loud branches, which the shipped 2c suite exercises
  only through providers that are all correct: `gameType` disagreeing with the contract
  interface (all three contracts), `products = {}` on a product-keyed provider, `products`
  declared on a SLOT provider, two providers claiming one product, two product-neutral slot
  providers, the WARN-and-skip path for a bean with no `@MessageTypesImpl`, and `slot()`
  with no provider. Also the converse — 116 in both tables is *not* a duplicate — so the
  duplicate check cannot be "fixed" by widening it.
- `bot-app/src/test/java/com/vingame/bot/infrastructure/logging/MessageTypesRegistryStartupLogTest.java`
  — **2 tests**, the tier-1 invariant on the one log line this phase adds: exactly one
  event, at **INFO**, per registry construction, and a count that does **not** scale with
  the number of providers (one provider and six both cost one line).
- `bot-app/src/test/java/com/vingame/bot/domain/bot/service/BotFactoryProductKeyTest.java`
  — **4 tests**, AD-20's text through the **call site** rather than the registry: a null
  `Environment.productCode` still yields `IllegalArgumentException("ProductCode cannot be
  null")` and not an NPE, on both the `BETTING_MINI` and `TAI_XIU` branches; and the two
  full "not yet implemented" literals as `createBot` actually raises them.
- `bot-app/src/test/java/com/vingame/bot/domain/bot/service/BotFactoryTaiXiuWiringTest.java`
  — javadoc only: the class comment still described the deleted
  `GameMessageTypesResolver.resolveTaiXiu(env.getProductCode())`. It was the one surviving
  reference to the removed class outside the stale `PLUGIN_PLAN.md` (which AD-22 deletes at
  2d).

---

## The five claims, re-verified

### 1. "Adding a new product is a pure addition" — CONFIRMED, and measured

A throwaway `Win79GameMessageTypes` was added in a **new package**
(`…bot.message.g5.win79`), annotated `@Component` +
`@MessageTypesImpl(gameType = BETTING_MINI, products = "119")`, reusing the TIP message
classes. Full reactor, twice:

| Pass | Result |
|---|---|
| Provider only, no test edits | `BUILD FAILURE` — **4** tests red, in **2** classes, from **2** editable lines |
| Provider + the two inventory lines | `BUILD SUCCESS` — **1915** tests, 0 failures, 0 errors |

`diff -rq --exclude=target` of the passing probe tree against a pristine `git archive HEAD`
extraction reports **exactly three paths**:

```
Only in probe/bot-messages/src/main/java/com/vingame/bot/domain/bot/message: g5
differ: bot-messages/src/test/.../MessageTypesCoverageTest.java
differ: bot-messages/src/test/.../MessageTypesRegistryTest.java
```

- one new `src/main` file — the provider itself;
- `MessageTypesCoverageTest`: delete the `"119",  // P_119 WIN79 …` inventory line;
- `MessageTypesRegistryTest`: widen `containsExactlyInAnyOrder("097","098","116","118")`.

**Zero other `src/main` files, in any module.** The property holds at HEAD. Note the shape
of the failure when only the provider is added: three of the four red tests come from the
single `MessageTypesCoverageTest` inventory line and one from `MessageTypesRegistryTest`'s
hardcoded set, so it is two edits, not four, and each failure message names the line to
change.

**One caveat worth writing down for the 119/Avatar work.** Both build-time guards scan
`com.vingame.bot.domain.bot.message`; `Starter` scans `com.vingame.bot`. A provider placed
*outside* the message package therefore works in production but reads as absent to
`MessageTypesCoverageTest`, whose failure text then says "register a provider" for a product
that has one. It fails loud, so nothing ships broken — but the message misdirects. Keeping
new providers under `…bot.message.*` avoids it entirely.

### 2. AD-19's coverage test genuinely fails — CONFIRMED, stronger than reported

All four mutations re-run in isolated sandboxes (`mvn test -pl bot-messages -am`):

| Mutation | `MessageTypesCoverageTest` | Elsewhere |
|---|---|---|
| Eleventh `ProductCode` `P_999` | **3 / 4 red** — `everyProductIsAccountedForPerGameType`, `inventoriesAreConsistentWithProductCode`, `scanDiscoversTheWholeCatalogue` | `bot-api` `ProductCodeTest` 2 red (pre-existing, unrelated to 2c) |
| Stale inventory claiming `"116"` unimplemented | **3 / 4 red** — same three, `inventoriesAreConsistentWithProductCode` naming "a registered product must not also be listed as not-yet-implemented" | — |
| Sixth `GameType` (`CRASH`) | **1 / 4 red** — `everyGameTypeIsClassified` | `bot-app` fails to **compile** (see 3) |
| `@Component` removed from `TipGameMessageTypes` | **3 / 4 red**, naming product 116 and quoting the exact lookup failure | `MessageTypesRegistryTest` 1 failure + 2 errors |

Dev reported 2/4 for the `P_999` and stale-inventory cases; the measured figure is **3/4**
in both. The extra red is `scanDiscoversTheWholeCatalogue`, whose expectation is *derived*
from the inventory (`ProductCode.values().length − inventory.size()`) rather than hardcoded
— so it moves with the inventory instead of being a third copy of it. The discrepancy is in
Dev's favour and changes nothing.

The `P_999` run initially looked like "coverage test does not fire", because `-am` halts the
reactor at `bot-api`'s own `ProductCodeTest`. Re-run with `-Dmaven.test.failure.ignore=true`
to reach `bot-messages`. Worth knowing: **adding a `ProductCode` already breaks the build at
`bot-api` today**, independently of 2c, so the coverage test is the message-layer-specific
half of that guard, not its only half.

### 3. `everyGameTypeIsClassified` — CONFIRMED red, but the stated rationale is wrong

Adding `CRASH` to `GameType` turns `everyGameTypeIsClassified` red with the intended
message. However, the handoff framing — "a sixth `GameType` breaks the build now and would
not after 2c" — is **false**. `BotFactory`'s `switch (game.getGameType())` is untouched by
2c and is still an exhaustive switch expression with no `default`; the same mutation was
compiled against `bot-app` and produced:

```
BotFactory.java:[160,19] the switch expression does not cover all possible input values
```

So `javac` still catches a sixth `GameType`, at `bot-app`. The test is genuinely useful
anyway — it is the only guard that fires inside `bot-messages`, which cannot see `bot-app`,
and it forces the *decision* (lookup vs. `GAME_TYPES_WITHOUT_A_LOOKUP`) rather than a
compile error that is satisfied by adding a throwing `case` arm. The test's own javadoc
states this correctly ("which selects a *bot class*, not a product implementation, and
therefore stays a switch"); only the handoff summary overstates it. Nothing to change in the
diff.

### 4. AD-20's error strings — CONFIRMED, all three pinned as literals

Byte-compared against `c3fac8a`'s `GameMessageTypesResolver` (`:52-54`, `:98-100`, and both
null guards). `MessageTypesErrorTextTest` pins all three with AssertJ `hasMessage` (exact
match, not `hasMessageContaining`):

- `GameMessageTypes not yet implemented for product code: 066. Please create a GameMessageTypes implementation for this product.`
- `TaiXiuMessageTypes not yet implemented for product code: 097. Please create a TaiXiuMessageTypes implementation for this product.`
- `ProductCode cannot be null` — asserted on **both** lookups.

A Tai Xiu miss reports `TaiXiuMessageTypes`, not the betting-mini name; the parameterised
`contract` argument is threaded from the two public lookups and both call sites are pinned.
This is the string verification step P2-7 greps verbatim.

**Gap closed by QA.** All of that was pinned only against a directly constructed registry.
The path P2-7 actually greps runs through `BotFactory.createBot`, and 2c inserted a
`ProductCode → String` dereference there (`productKey`). `BotFactoryProductKeyTest` now pins
both literals and both null cases through `createBot`, which is what makes the
"`env.getProductCode().getCode()` would have been an NPE" reasoning in `BotFactory`'s
javadoc a tested claim rather than a comment.

### 5. Ported coverage — CONFIRMED, nothing lost

`git grep -l GameMessageTypesResolver c3fac8a -- '*/src/test/*'` returns **six** files across
**three** modules; five have real call sites, and `bot-app`'s `BotFactoryTaiXiuWiringTest`
referenced it only from javadoc (now fixed):

| Pre-2c consumer | Ported to | Status |
|---|---|---|
| `bot-messages` `GameMessageTypesResolverTest` (12) | `MessageTypesRegistryTest` (12) | replaced, strictly stronger |
| `bot-messages` `SlotMessageTypesTest` | same file, `MessageTypesRegistry(…).slot()` | ported |
| `bot-messages` `TaiXiuMessageTypesTest` (incl. 8-product `@EnumSource`) | same file, `REGISTRY.taiXiu(code)` | ported, `@EnumSource` retained |
| `bot-messages` `taixiu/JackpotTaiXiuMessageTypesTest` (3 cases) | same file | ported |
| `bot-engine` `TaiXiuJackpotGameBotStreamTest` | same file, registry built directly | ported |
| `bot-app` `BotFactoryTaiXiuWiringTest` | javadoc only | **stale — fixed by QA** |

The one deletion that looks like lost coverage is the resolver test's six-product
`@EnumSource` for betting-mini misses. It is not lost: `MessageTypesCoverageTest.
everyProductIsAccountedForPerGameType` now iterates **all ten products × both product-keyed
game types** and asserts the exact contract-qualified message for every listed one — i.e.
20 assertions where there used to be 6, and the Tai Xiu side is exhaustive for the first
time. `bot-engine` holding at exactly 426 confirms no test was dropped there.

Two behaviour deltas that the port makes, both intended and now both tested:

- **Providers are singletons**, previously `new`-ed per bot. All six are verified stateless
  (no instance fields at all). `MessageTypesRegistryTest` pins that 097 and 098 return the
  *same* instance.
- **`slot()` can now throw** where `resolveSlot()` returned `new SlotMessageTypesImpl()`
  unconditionally. It is an `IllegalStateException`, deliberately *not* the AD-20
  `IllegalArgumentException`, so an operator can tell "deploy bug" from "unsupported brand".
  Untested as handed off; pinned by `MessageTypesRegistryValidationTest.MissingSlotProvider`.

---

## Logging Guidelines conformance

2c adds exactly one log line — `MessageTypesRegistry initialized: BETTING_MINI 4 products
[…], TAI_XIU 2 products […], SLOT provider SlotMessageTypesImpl` — emitted from the
constructor of a Spring singleton. It is **once per JVM**, tier-1 shaped (application
startup, group-level catalogue), and carries no per-bot, per-round or per-message rate. Same
shape and justification as `(Betting|Slot)StrategyFactory`'s "registered N strategies",
which `PerBotInfoLogGuardTest` exempts by name for exactly this reason. It is on track 1 and
therefore in Loki, which is right: it is how an operator confirms a brand's provider came up
after a deploy.

**It was not guarded, and that is a new hole this phase opens.** `PerBotInfoLogGuardTest`
scans `bot-engine/.../domain/bot/core` and `bot-strategies/.../domain/bot/strategy` only.
`bot-messages` had no Spring dependency and no logger before 2c; this is the commit that
gives it both, so it is also the commit after which a per-product or per-bot INFO line can
be added there with nothing failing. `MessageTypesRegistryStartupLogTest` closes it by
asserting the level **and** that the line count is independent of catalogue size (one
provider and six both cost one line — the rule as `CLAUDE.md` states it). Mutation-verified:
flipping `log.info` → `log.debug` in the registry turns it red (`expected: INFO`).

Extending `PerBotInfoLogGuardTest`'s `PER_BOT_DIRECTORIES` to `bot-messages` was considered
and rejected — the module is *not* per-bot as a whole, and a blanket ban there would have to
exempt the one line it is about.

---

## Coverage of the diff

| Production file | Covered by |
|---|---|
| `bot-api/.../message/MessageTypesImpl.java` | `MessageTypesRegistryTest.declaredGameTypeMatchesTheContract` (every discovered bean's `gameType` + non-blank, trimmed products); `MessageTypesRegistryValidationTest` (all malformed forms) |
| `bot-messages/.../message/MessageTypesRegistry.java` — happy path | `MessageTypesRegistryTest` (real component scan, all six providers, both tables, singleton identity, B52 exclusion); `ApplicationContextLoadsTest.messageTypesRegistryIsFullyPopulated` (under `Starter`'s own scan) |
| … — miss/null text | `MessageTypesErrorTextTest` (three literals); **QA** `BotFactoryProductKeyTest` (same literals through `createBot`) |
| … — fail-loud branches | **QA** `MessageTypesRegistryValidationTest` (6 branches, was 0) |
| … — startup INFO line | **QA** `MessageTypesRegistryStartupLogTest` (level + non-scaling count) |
| … — coverage/inventory | `MessageTypesCoverageTest` (4 tests, 4 mutations verified red) |
| six `@Component` + `@MessageTypesImpl` providers | `MessageTypesCoverageTest.scanDiscoversTheWholeCatalogue`, `MessageTypesRegistryTest.scanRegistersExactlyThePreviousSwitchArms`, `ApplicationContextLoadsTest`, `SpringBeanConstructorTest` (pre-existing, scans every stereotype class) |
| `B52GameMessageTypes` (deliberately unregistered) | `MessageTypesRegistryTest.b52IsNotRegistered` |
| `bot-app/.../BotFactory.java` (registry injection, `productKey`) | `BotFactory{FailLoud,Slot,TaiXiu}WiringTest`, `ApplicationContextLoadsTest.botFactoryIsConstructableBySpring`; **QA** `BotFactoryProductKeyTest` for the null/miss paths |
| `bot-messages/pom.xml` (`spring-context`) | `ApplicationContextLoadsTest.messageTypesRegistryIsFullyPopulated` — the only place proving the new dependency is enough for cross-jar scanning |
| `bot-engine/.../TaiXiuGameBot.java` (javadoc only) | n/a |

---

## Gaps

- **CGLIB / JDK proxying would silently un-register a provider.** `annotationOf` reads
  `provider.getClass().getAnnotation(MessageTypesImpl.class)`; `@MessageTypesImpl` is not
  `@Inherited`, so a proxied bean returns `null` and takes the WARN-and-skip path. Nothing
  proxies these beans today (no AOP, no `@Transactional`, no `@Async` anywhere near
  `com.vingame.bot.domain.bot.message`), and the failure is caught downstream by
  `MessageTypesCoverageTest` rather than reaching prod. Not tested — a test for it would be
  asserting Spring's proxying behaviour, not ours. If advice is ever applied to that package,
  `AopUtils.getTargetClass(bean)` / `AnnotationUtils.findAnnotation` is the one-line fix.
- **Step-5 concerns are out of scope here** and are the Reviewer's F1/F3/F4: the three
  `final` tables cannot be swapped atomically, and there is no `pluginVersion` dimension on
  the registry. Correct for 2c (AD-23 forbids behaviour change); flagged so step 5 does not
  rediscover them.
- **P2-6 and P2-7 remain staging-only.** No unit test can prove a real brand's frames parse
  with the provider the registry hands out — that is P2-6's "at least one StartGame
  session-entry **and** one EndGame results line". P2-7 needs a group on an unprovisioned
  brand, which staging may not have; the build-time evidence for it is
  `MessageTypesErrorTextTest` plus the new `BotFactoryProductKeyTest`.
- **The inventory is a manual artefact by design (AD-19).** Nothing prevents someone
  deleting a product's provider *and* adding it to the inventory in the same commit; the
  tests would be green and a brand would quietly stop working. That is the accepted price of
  turning a compile error into a readable list, and the release-time evidence for it is
  P2-6, not the build.

## Failures

None. `mvn -q clean test` → `BUILD SUCCESS`, 1931 tests, 0 failures, 0 errors, 0 skipped.
