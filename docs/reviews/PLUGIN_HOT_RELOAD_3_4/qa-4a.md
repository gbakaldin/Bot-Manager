# QA — PLUGIN_HOT_RELOAD_3_4 Phase 4a

**Verdict:** PASS
**Build:** `mvn -o verify` in a clean detached worktree at `bf75701`. Before QA: surefire **3264**
(148 / 90 / 261 / 1140 / 1625), failsafe 0, 0 failures, 0 errors. After QA's tests:
surefire **3269** (148 / 90 / 261 / 1140 / **1630**), failsafe 0, 0 failures, 0 errors.

Scope: `a0006c8..bf75701` (238bea8, 6371ea1, c3d83a4, f07328f, bf75701). All Maven work ran
in scratch worktrees (`qa-4a` at `bf75701`, `qa-3b` at `836f66f`). Neither the feature
worktree nor the main checkout was built.

## Dev's claims, checked

| Claim | Result |
|---|---|
| 3264 surefire (148/90/261/1140/1625), failsafe 0 | **Confirmed**, per module, from the surefire XML. |
| D-1: the boot registry lines are byte-identical to 3b, and only the `plugin runtime:` suffix differs | **Confirmed** by a real `java -jar` boot of both fat jars, with a console-only log4j2 (`-Dlog4j2.configurationFile`) and Mongo pointed at an unused port. `diff` of the four lines differs only on line 4: `…classloader=2047329716` → `…classloader=2047329716, source=classpath, jars=[]`. `registered 9`, `registered 2`, and the `MessageTypesRegistry` product sets are identical. |
| The strategy catalogue response is identical | **Confirmed.** Both jars were booted against a throwaway `mongod` on a scratch dbpath. `GET /api/v1/strategy/` md5 = `a72c40f56057cda5434b273ea36315ea` on both (the plan's P-0 value). `?gameType=SLOT` returns `[]` on both. The full `/actuator/prometheus` series sets, with values stripped, match apart from the disk path and the Mongo db/cluster ids. `plugin_classloaders_*` reads 1/1/0 `builtin` on both. |
| `bot.plugins.mode` rejects unknown values at startup | **Confirmed in a real boot**, through relaxed binding: `BOT_PLUGINS_MODE=isolatd java -jar …` exits 1 with `Unknown bot.plugins.mode 'isolatd' — expected one of [classpath]`, and `Started Starter` never appears. |
| Every production bot construction path gets the bundle's TypeFactory | **Confirmed.** `BotFactory.createBot` is the only `new {BettingMini,Slot,TaiXiu,Cashout,Crash}Bot` in `src/main`, and `.setPluginTypeFactory(plugins.typeFactory())` sits on the shared builder tail that every branch passes through. Restart and re-auth reuse the bot object, so they also reuse its `pluginTypeFactory` field. `onStart()` → `botBehaviorScenario()` → `newMessageMapper()` rebuilds the mapper from that field on every (re)connect. All four per-bot mapper sites go through `newMessageMapper()`. No other `new ObjectMapper()` remains in the bots (the only others are the static `ApiGatewayClient`/`GameMsClient` mappers and `HttpPrometheusQueryClient`, which are L-7 territory and untouched). Before QA, the wiring test covered only BETTING_MINI and CASHOUT. QA extended it to all five types (below). |
| `current()` is read once per operation | **Confirmed** for `BotFactory.createBot` (one read per bot, every registry taken from it; now pinned for all five branches), for `BotGroupConfigValidationService.validateStrategyKeys` (one read, and skipped when there is nothing to validate), and for `StrategyCatalog` (one read per call). **Caveat, same as review-4a's first smell:** `createSingleBot` stamps `pluginVersion` through `PluginVersionResolver`, which is a second `current()` read, separate from `createBot`'s. Inert in step 4 (one final bundle). Step 5 must stamp from the same `PluginRegistries` value. |
| The null-TypeFactory fallback is reachable only from fixtures | **Confirmed.** The `PluginRegistries` record constructor `requireNonNull`s `typeFactory`, and `PluginRegistries.build` takes it from `bundle.typeFactory()` (a final, eagerly-built field). `BotFactory` always calls `setPluginTypeFactory` with it. Only a bot built outside `BotFactory` (test fixtures) can hold null. |
| Dev's plan defect: V4a-2/V4-6 reference an "unexported" `bots_managed` | **The defect is real, but the diagnosis is wrong. See Finding 1.** |
| 18 mutation kills | Not re-run as a list (it is not in the commits). QA ran **10 independent mutations against Dev's tests**, all killed (table below), including L-10, L-8 and close-order. |

## Finding 1: `bots_managed` IS exported. The plan's grep cannot match it

`bots_managed` is registered by `ObservabilityConfig.botAggregateGauges` and is exported.
Both jars, booted locally, scrape:

```
bots_managed{application="bot-manager"} 0.0
```

`management.metrics.tags.application=bot-manager` puts a `{` straight after **every**
metric name. So the plan's `grep -E '…|bots_managed )'` (P-0, line 869) and
`grep -E '…|^bots_managed '` (V4a-2, line 932) require a space after the name, and they
can never match. release-d1 (lines 56 and 153) read the empty grep as "not exported", and
Dev inherited that reading. PLUGIN_HOT_RELOAD's own releases read it fine (release-phase2:
`bots_managed ... 155.0`; release-phase2d: "Sum = 155; bots_managed = 155") with
`grep -E '^bots_managed'`, which has no trailing space.

**Fix (plan text, Architect/Dev):** use `'^bots_managed[{ ]'` (or `'^bots_managed\{'`) in
P-0 and V4a-2. V4-6 (line 998) gives no command of its own, so the releaser will reuse
V4a-2's grep, and the fix carries over. The V4a-2 / V4-6 sum check then
works as designed, so it is not dead. Correct release-d1's notes 56/153 rather than carry
"not exported" forward. Not a code defect.

## Thread-safety of `PluginRuntime.current()`

- `current` is a `final` field. Any thread that obtains the `PluginRuntime` reference sees
  a fully constructed `PluginRegistries`: final-field semantics (JLS 17.5) cover the
  record's final components, and the registries' own state (`final Map registry`, filled
  in the constructor; `final Tables tables`). Spring's singleton registry adds a
  happens-before edge on top. There is no data race on publication.
- `unpublished` is `volatile`, written once by the close hook. The check-then-return in
  `current()` is not atomic, so a caller can pass the check just as `close()` runs. That is
  benign in step 4, where close runs only at JVM shutdown for the classpath bundle, and
  there it is a no-op that never fires the hook. It becomes relevant at step 6, together
  with review-4a's "callers do not handle `current()` throwing" smell.
- The bundle's private `LRUMap` is the same thread-safe cache class that
  `TypeFactory.defaultInstance()` uses, so sharing it across a bundle's bots is no weaker
  than sharing the default was.
- `unpublishHooks` is a `CopyOnWriteArrayList` and `closed` is an `AtomicBoolean`, so
  `close()` is idempotent under races.

**Nothing caches a registry outside the runtime.** The holders are `BotFactory`,
`StrategyCatalog`, `BotGroupConfigValidationService`, the `PluginVersionResolver` lambda
and `PluginClassLoaderMetrics.subject`, and each holds `PluginRuntime` and nothing more. No
`src/main` class injects a plugin contract directly (no `List<BettingStrategy>`,
`List<…MessageTypes>`, `ObjectProvider<…>` or `getBeansOfType`) outside
`infrastructure/plugin` and the three registries. **But the L-10 test could not prove that
one level down. See the next section.**

## Tests added / updated

- `bot-app/src/test/java/com/vingame/bot/RootContextHoldsNoPluginRefsTest.java`: **L-10
  strengthened** with a bounded deep scan (`reachForbidden`, max 4 hops). It follows
  lambdas and non-bean objects of ours, `Collection`, `Map`, arrays, `Optional` and
  `AtomicReference`. It never walks into another root bean or into `PluginRuntime`, and
  never reflects into a non-`com.vingame.bot` class. **Why:** the shipped test inspects only
  a bean's own fields, so a `Supplier` field whose lambda captured `current()` instead of
  the runtime passed the build. `PluginClassLoaderMetrics.subject` is exactly that shape
  today (correctly capturing the runtime), so it is the most likely regression site. A
  registry inside a memo map passed the same way.
- `bot-app/src/test/java/com/vingame/bot/domain/bot/service/BotFactoryPluginRegistriesWiringTest.java`:
  new `everyBranchIsWiredFromOneRegistriesValue`, parameterised over BETTING_MINI /
  TAI_XIU / SLOT / CASHOUT / CRASH (+5 tests). For each type it asserts exactly one
  `current()` read, `setPluginTypeFactory(plugins.typeFactory())`, and that branch's
  strategy factory / message types taken from the same `PluginRegistries` value.
  **Why:** TAI_XIU, SLOT and CRASH had no assertion for either property, and
  `BotFactorySlotWiringTest` stops at the auth boundary without checking which factory was
  wired.

### Mutation checks of QA's tests

| Mutation (production, reverted after) | Before QA | After QA |
|---|---|---|
| M1b `PluginClassLoaderMetrics`: lambda captures `pluginRuntime.current()` instead of the runtime | **survived** | killed: `…PluginClassLoaderMetrics.subject holds PluginRegistries (1 hop(s) down)` |
| M1c `StrategyCatalog`: `Map<String,Object>` memo holding `bettingStrategies()` | (not run) | killed: `…StrategyCatalog.memo holds BettingStrategyFactory (1 hop(s) down)` |
| M11 `BotFactory`: TAI_XIU / SLOT / CRASH branches each re-read `pluginRuntime.current()` | **survived** (22 `BotFactory*Test` green) | killed, params [2] [3] [5] |
| M12 `BotFactory`: TypeFactory set on the BETTING branch only | (not run) | killed, 5 tests (CASHOUT legacy + [2]..[5]) |
| M13 `BotFactory`: SLOT branch forgets `setSlotStrategyFactory` | (not run) | killed, param [3] |

Both QA tests pass unmutated (full build above).

### Mutation spot-checks of Dev's tests (all killed)

| # | Mutation | Killed by |
|---|---|---|
| M1 | `StrategyCatalog` gets an `Object` field caching `current().bettingStrategies()` (**L-10**) | `RootContextHoldsNoPluginRefsTest.onlyPluginRuntimeHoldsPluginReferences` |
| M2 | `CrashBot` goes back to a bare `new ObjectMapper()` (**L-8**) | `PerBotMapperTypeFactoryTest` [5] CRASH |
| M3 | `Bot.newMessageMapper` drops `setTypeFactory` (**L-8**) | `PerBotMapperTypeFactoryTest`, all 5 kinds |
| M4 | `PluginBundle.close`: loader closed before the context (**close order**) | `PluginBundleCloseTest`, 4 of 5 |
| M5 | `close` skips `TypeFactory.defaultInstance().clearCache()` | `PluginBundleCloseTest.closeRunsInTheD13Order` |
| M6 | `PluginRuntime.current()` ignores `unpublished` | `PluginBundleCloseTest.runtimeUnpublishesOnBundleClose` |
| M7 | `BotFactory` drops `.setPluginTypeFactory(...)` | `BotFactoryPluginRegistriesWiringTest`, both legacy tests |
| M8 | `PluginRuntimeConfiguration` accepts any mode as classpath | `PluginRuntimeConfigurationTest`, 2 tests |
| M9 | `createSingleBot` stops stamping `pluginVersion` (D-15) | `BotGroupBehaviorServiceTest$StrategyAssignmentIntegrationTests.startStampsThePluginVersion` |
| M10 | Boot line field order changed (`source=` before `classloader=`) | `PluginClassLoaderMetricsBundleTest.bootLineAppendsSourceAndJars` |

## Coverage of the diff

- `PluginRuntime` ← `PluginBundleCloseTest` (unpublish), `PluginRuntimeConfigurationTest`, the
  L-10 test (sole holder).
- `PluginRegistries` ← `PluginRegistriesBuildTest` (one bundle, D-10 rejection, non-null).
- `PluginBundle` ← `PluginBundleCloseTest` (D-13 order, idempotence, failure continuation,
  both caches).
- `ClasspathPluginBundle` ← `ClasspathPluginBundleTest` (D-12 zero-bean text, identity,
  prototype, injection order), `PluginBundleCloseTest` (no-op close).
- `PluginRuntimeConfiguration` ← `PluginRuntimeConfigurationTest`, plus QA's real-boot
  unknown-mode check.
- Registries (`BettingStrategyFactory`, `SlotStrategyFactory`, `MessageTypesRegistry`) ←
  their existing tests ported to bundles, `StrategyCatalogParityTest`,
  `ApplicationContextLoadsTest`, and QA's byte-identical boot-line diff.
- `Bot.newMessageMapper` and the four mapper sites ← `PerBotMapperTypeFactoryTest`.
- `BotFactory` ← `BotFactoryPluginRegistriesWiringTest` (now all five types) and the
  existing `BotFactory*WiringTest`s.
- `StrategyCatalog` / `BotGroupConfigValidationService` ← `StrategyCatalogTest`,
  `StrategyControllerTest`, `BotGroupConfigValidationServiceTest`,
  `BotGroupStrategyKey*Test`, and the md5-identical catalogue response.
- `PluginClassLoaderMetrics` (D-15) ← `PluginClassLoaderMetricsBundleTest`, plus the
  unchanged `PluginClassLoaderMetricsTest` (green).
- `BotGroupBehaviorService.createSingleBot` (`pluginVersion`) ← `BotGroupBehaviorServiceTest`.

## Gaps

- **Version stamp and wiring are two `current()` reads** (review-4a's first smell). They are
  not pinned and cannot be distinguished in step 4. Step 5 must close this before a second
  bundle exists.
- **The non-atomic `current()` check versus `close()`** is unexercised. It can only matter
  once a non-classpath bundle closes at runtime (step 6).
- **L-10's deep scan is bounded** (4 hops; containers and objects of ours only). A
  reference hidden inside a third-party object held by a root bean would still escape.
  That is acceptable for a build guard, and it is now documented on the test.
- **The 4b/4c L-rules (L-1..L-7, L-11..L-15)** are out of scope for 4a, as the plan says.
  Failsafe stays 0 until 4b, where it must become > 0 (D-20).

## Failures

None.
