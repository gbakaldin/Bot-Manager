# Code Review — PLUGIN_HOT_RELOAD_3_4, Phase 4a

Branch: feature/plugin-hot-reload-3-4
Reviewed diff: `git diff a0006c8..bf75701` (Phase 4a commits only: 238bea8, 6371ea1, c3d83a4, f07328f, bf75701)

## Verdict

PASS

No `bug` or `security` findings. In classpath mode `ClasspathPluginBundle.close()` does nothing, so
every failure path in the new close and unpublish code is unreachable in 4a, and the runtime
behaviour matches the old `@Component` registries. Five of the smells below are **latent**: they
are correct today only because the classpath bundle is inert. Each becomes a real defect the
moment 4b's isolated bundle exists. They should be fixed before 4b or as part of it, not later.

## Findings

### [smell] Plugin version is read twice per bot. Stamp and wiring can come from different bundles
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:2091`,
`bot-app/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java:158`

`createSingleBot` stamps `BotConfiguration.pluginVersion` from
`pluginVersionResolver.currentVersion()`, which is one `PluginRuntime.current()` read. Then
`BotFactory.createBot` makes a **second** `current()` read and takes the strategy factory, the
message types and the `TypeFactory` from that one. The comment at `BotFactory:156-157` says "never
a second current() for the same bot", and `PluginRegistries`' javadoc says the
mixed-version window is "unexpressible". Across these two classes neither statement holds. Once
step 5 can swap the bundle between the two reads, a bot is labelled N
(`pluginVersion` MDC, `bots_by_plugin_version`) while it runs N+1's code. That is the
"mixed wiring that leaves no trace in the logs" the design exists to prevent. Here it shows up
in the very metric that step 5's drain will rely on.

In 4a the field is `final` and holds one bundle, so the two reads cannot disagree.

Fix shape: stamp the version from the same value the bot is wired from. Do it in `createBot` from
`plugins.bundle().version()`, either by setting it on the configuration there or by passing the
`PluginRegistries` value into `createBot`. Then remove the resolver from
`BotGroupBehaviorService`. Keep the `startCancelled` wiring in `createSingleBot`. Its
before-`initialize()` constraint does not apply to the version stamp, which only has to be in
place before `initialize()` sets the MDC, and `createBot` already does that.

### [smell] A failing `bundle.close()` on the rejection path hides the rejection reason
`bot-app/src/main/java/com/vingame/bot/infrastructure/plugin/PluginRuntimeConfiguration.java:54-58`

```java
} catch (RuntimeException rejected) {
    bundle.close();
    throw rejected;
}
```

If `close()` throws, its `IllegalStateException` propagates and `rejected` is lost. `rejected` is
the D-10 reason ("duplicate key '116'", "missing @MessageTypesImpl", and so on), which is the one
thing the operator needs. `PluginBundle.close()` is written specifically to rethrow its first step
failure, so for an isolated bundle with a context or loader that will not close, this is
reachable. It is a no-op for the classpath bundle today.

Fix shape: `try { bundle.close(); } catch (RuntimeException e) { rejected.addSuppressed(e); } throw rejected;`.
4b's isolated loader will need the same treatment wherever it closes a rejected candidate and
falls back.

### [smell] `close()` keeps going after an `Exception` but not after an `Error`, and `closed` is already latched
`bot-engine/src/main/java/com/vingame/bot/infrastructure/plugin/PluginBundle.java:129-133, 169`

The class javadoc promises that "Each step runs even if an earlier one threw". `runStep` catches
`Exception` only. Step 2 closes a child context whose destroy callbacks run plugin code. A
`NoClassDefFoundError` or `LinkageError` there is the typical failure, and it aborts steps 3-4.
The type caches are not cleared and the `URLClassLoader` is never closed. Because
`closed.compareAndSet(false, true)` ran first, a retry is impossible, so the loader leaks for the
life of the JVM. That is exactly the outcome the D-13 ordering is meant to rule out.

Fix shape: catch `Throwable` in `runStep`, or `Exception | LinkageError` if you want
`VirtualMachineError` to escape. Record it as you already do, and rethrow an `Error` unchanged at
the end if it was the first failure. Either way the code and the javadoc must say the same thing.

### [smell] `PluginRuntime` over an already-closed bundle publishes it forever
`bot-engine/src/main/java/com/vingame/bot/infrastructure/plugin/PluginRuntime.java:33-36`

The constructor registers its unpublish hook with `onUnpublish`. If the bundle is already closed,
the hook list has already been drained (`close()` CASes `closed` and iterates once). The hook
never runs, `unpublished` stays false, and `current()` hands out registries whose loader is gone.
There is also a narrower race: a hook added while `close()` iterates the `CopyOnWriteArrayList` is
missed, because the iteration works on a snapshot. Nothing in 4a can construct a runtime over a
closed bundle. Step 5's swap is where this would first become reachable.

Fix shape: in the constructor, after registering, `if (bundle.isClosed()) unpublished = true;`
(or reject with an ISE). Alternatively, make `current()` check `current.bundle().isClosed()`
directly and drop the hook. The hook only mirrors a flag the bundle already has.

### [smell] `PluginRegistries.typeFactory` duplicates `bundle.typeFactory()` and can disagree with it
`bot-engine/src/main/java/com/vingame/bot/infrastructure/plugin/PluginRegistries.java:36, 69`

`build` always passes `bundle.typeFactory()`, but the canonical constructor is public and accepts
any `TypeFactory`. A hand-built record can pair bundle A's registries with B's type cache, or with
`TypeFactory.defaultInstance()`. Then the D-9 guarantee that plugin types are cached only in
their own bundle's private cache depends on the caller. Tests currently build these by hand.

Fix shape: drop the component and expose `typeFactory()` as an accessor that returns
`bundle.typeFactory()`. Or validate `typeFactory == bundle.typeFactory()` in the compact
constructor.

### [smell] Callers do not handle `current()` throwing after close
`bot-engine/src/main/java/com/vingame/bot/infrastructure/plugin/PluginRuntime.java:51-56`

Once the bundle is unpublished, `current()` throws an `IllegalStateException`. Three callers are
affected:

- `createSingleBot`/`createBot`, on a bot-creation virtual thread. That counts as a per-bot
  creation failure and can push a starting group toward DEAD during shutdown.
- `StrategyCatalog.bettingStrategies()`, which surfaces as a 500 on `GET /api/v1/strategy/`.
- The `PluginVersionResolver` lambda.

The read also has an unavoidable TOCTOU: a caller can get the registries and then lose the bundle
mid-`createBot`. Bots built earlier keep their factories (intended, drain semantics), and
`BettingStrategyFactory.create` → `bundle.newInstance` on a closed child context would also
throw.

All of this is unreachable in 4a. In 4b it is reachable only at JVM shutdown, because Spring
destroys dependents (`BotFactory`, `BotGroupBehaviorService`) before the `PluginRuntime` bean.
That holds only if `BotGroupBehaviorService`'s shutdown actually stops bot threads synchronously
before returning. This is noted so 4b decides it deliberately. A specific exception type
(`PluginUnpublishedException`), which `createBotsInParallel` would treat as "shutting down"
rather than as a bot failure, is the obvious shape.

### [style] D-12's error tells the operator to set a mode that 4a rejects
`bot-engine/src/main/java/com/vingame/bot/infrastructure/plugin/ClasspathPluginBundle.java:43-44`

`"no plugin beans on the classpath — set bot.plugins.mode=isolated (see CLAUDE.md)"`. In 4a,
`isolated` fails startup with `Unknown bot.plugins.mode 'isolated' — expected one of [classpath]`.
The text is D-12's verbatim and becomes correct in 4b, so this is advisory. It only matters if 4a
ships to a box on its own.

### [style] `PluginRuntime.of` is a public production API that only tests use
`bot-engine/src/main/java/com/vingame/bot/infrastructure/plugin/PluginRuntime.java:43`

It is identical to the public constructor. That is harmless, but it is a second public way to
build the one root-held reference, and its javadoc calls it a test helper. `TestPluginRuntimes`
already lives in test scope and could call the constructor directly. Advisory.

## Notes

**Concurrency of `PluginRuntime`.** Publication is sound. `current` is `final`, so the
`PluginRegistries` and everything reachable from it are safely published through the Spring
singleton. `unpublished` is `volatile`. The registries themselves are immutable after
construction: `registry` maps are written only in the constructors, and
`registeredKeys()` snapshots. The remaining concerns are the latent hook and TOCTOU items above.

**Jackson coverage is complete for the bot paths.** I traced every path from a bot to Jackson
through the ws-parser 3.0.5 sources:

- Incoming typed decode: `Scenario` → `new ProcessableMessage(raw, context.getObjectMapper(), …)`
  → `Qualifier.is(clazz)` uses `msg.getObjectMapper()`, and `PipelineStage.onMessage` uses
  `context.getObjectMapper()` → `ActionResponseMessage.deserialize(mapper, …)` → `mapper.readValue`.
  All of these use the per-bot mapper.
- Outgoing: `Send`/`SendAsync` call `message.serialize(context.getObjectMapper())`. The direct
  sends (`BettingMiniGameBot:938`, `CashoutBot:317`, `CrashBot:473`) serialize with the
  `newMessageMapper()`-built mapper that `bindCashoutChannel`/`bindSendChannel` capture.
- `TaiXiuGameBot` inherits `BettingMiniGameBot.botBehaviorScenario()`.
- What still uses `ObjectMapperProvider.getDefault()` or a static mapper never touches a plugin
  type. The `OutputPrinter` scenarios build their context on `getDefault()`, but they filter on
  raw `cmd` and `readValue(…, Object.class)`. `RawMessage.readTree` works on the tree only.
  `AuthMessage`/`PingMessageImpl` handle library types. `AuthClient`, `ApiGatewayClient` and
  `GameMsClient` serialize the login and deposit DTOs, which live in `bot-engine`
  (`domain/bot/auth/*LoginRequest`), not in a plugin module.
- Caveat for step 6, not a 4a defect: if a plugin type ever reaches one of those static mappers,
  clearing the type caches in close step 3 is not enough. An `ObjectMapper`'s own
  serializer/deserializer caches key on the class and are not cleared by
  `TypeFactory.clearCache()`. Today none does.

**Root-reference rule (L-10).** No root bean holds a registry, bundle or type factory:

- `PluginClassLoaderMetrics` captures `PluginRuntime` in a `Supplier` and builds its `Subject`
  (which strongly holds the loader) only as a local in `start()`.
- The `PluginVersionResolver` bean is a lambda over `PluginRuntime`.
- `BotGroupBehaviorService` reaches bundles transitively, through running bots →
  `BettingStrategyFactory.bundle`. That is the documented bot exception.
- One limit of `RootContextHoldsNoPluginRefsTest`: it inspects each singleton's direct fields
  only. A root bean holding a holder object that holds a registry would pass. Acceptable for
  now, but worth knowing before treating the test as proof for step 6.

**Bean ordering and boot lines.** The three registry INFO lines are now emitted in a fixed order
(betting, slot, message types) inside the `pluginRuntime` `@Bean`, regardless of which consumer
Spring builds first. The `plugin runtime:` line now always follows them, because
`PluginClassLoaderMetrics` depends on `PluginRuntime`. The C-2 grep is order-sensitive only
across the three registry lines, and it excludes the runtime line, so this is fine.

For 4b, note that `BettingStrategyFactory`'s "initialized" INFO line is logged from the
constructor, before `MessageTypesRegistry` has validated. A candidate bundle rejected by its
message types will still have printed `BettingStrategyFactory initialized: registered N
strategies`. If the fallback bundle is then accepted, the boot log contains two such lines, and
C-2's diff will flag it. Either defer the per-registry lines until `build` succeeds, or prefix
them with the bundle version.

**`getBeanProvider(type).orderedStream()` vs the old `List<T>` injection.** Both sort with the
order comparator and filter to autowire candidates. Prototype strategies are instantiated once
each during `build`, exactly as the list injection did. `getBeanNamesForType` in the D-12 check
instantiates nothing. A plugin bean that ever injects `PluginRuntime` (or anything depending on
it) would now be a circular reference at refresh. That is a reasonable constraint, but it is
undocumented.

**Startup failure modes for `bot.plugins.mode`.** An unknown value, an empty value or `isolated`
in 4a fails context refresh with `Unknown bot.plugins.mode '<raw>' — expected one of [classpath]`.
The raw value is quoted, so blanks are visible. Case and surrounding whitespace are normalised.
`BOT_PLUGINS_MODE` relaxed-binds into the `@Value`. This is the right shape, and it does not fall
back silently.

**Test seams left in production classes.**

- The package-private `PluginClassLoaderMetrics(MeterRegistry, PluginVersionResolver)` is
  acceptable. Spring picks the `@Autowired` constructor, and the seam reproduces what a classpath
  bundle reports.
- The list-taking `MessageTypesRegistry` constructor is also acceptable, because it is the one
  production delegates to.
- Package-private `PLUGIN_CONTRACTS` / `NO_PLUGIN_BEANS` are fine.
- Only `PluginRuntime.of` is gratuitous (style finding above).

**`Bot.newMessageMapper()` null tolerance.** A production bot built without a type factory
silently falls back to the shared default factory, which brings back the spike-3a pin. Every
production path goes through `BotFactory`'s common fluent chain, which sets it for all five bot
types, so this is fine today. A future bot-construction path that bypasses `BotFactory` would
regress silently. `PerBotMapperTypeFactoryTest` is what guards it.

**Good patterns.**

- `validateStrategyKeys` reads `current()` once and checks both keys against the same value.
- The early return keeps a no-strategy PATCH from touching the runtime at all.
- The `Subject` record keeps the loader out of `PluginClassLoaderMetrics`' fields.
- The type cache is built with the non-deprecated `withCache(LookupCache)` overload and the
  same 16/200 bounds as Jackson's default, so resolution is not perturbed.
