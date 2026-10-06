# Spike: is a plugin `ClassLoader` actually collected? (gates steps 4-6)

**Date:** 2026-10-06 · **Status:** throwaway spike, not production code · **JDK:** OpenJDK 21.0.2 (G1), macOS
**Spike source:** `/private/tmp/claude-501/-Users-gleb-IdeaProjects-Bot/4adab1e7-37e2-40e3-a370-c9e86f0a886a/scratchpad/cl-spike/`
(`build.sh`, `run.sh <scenario>`, `harness/src/spike/Spike.java`, `hazards/src/spike/plugin/PluginHazards.java`).
It is in the session scratchpad, so it goes when the session does. The steps to rebuild it are below.

## Verdict: **GO with rules**

A child `URLClassLoader` holding real plugin code **is collected** once every strong
reference is gone. Over 1,000 load/use/unload cycles the spike unloaded 40,050 classes,
kept 0 loaders alive, and the loaded-class count stayed flat. Every pin the spike found
has a known root cause, and each one cleared once its mitigation was applied, except one.
**Registering subtypes on ws-parser's static `ObjectMapperProvider.getDefault()` pins the
loader for good and nothing can undo it.** That makes it a hard prohibition, not a cleanup
step.

Two pins the plan did not expect:
- **Jackson's static `TypeFactory.defaultInstance()` pins plugin types even through a
  *fresh per-bot* `ObjectMapper`.** The plan's forward-compat fact ("no process-wide
  Jackson subtype registry, two versions can coexist") is right about subtypes, but it is
  not enough for unloading.
- **On JDK 21, any platform thread *created* while plugin frames are on the stack pins
  the loader for as long as that thread lives.** Proven on Netty event-loop threads via
  `Thread.inheritedAccessControlContext`. This happens even when the thread context class
  loader (TCCL) is left alone.

## Setup (what "faithful" means here)

- **Parent loader** (app classpath): the real `bot-app` runtime classpath (157 jars) **minus**
  `bot-strategies`, `bot-messages` and `bot-app`. It carries bot-api, bot-engine,
  ws-parser 3.0.5, Spring 6.2.0, log4j2 2.24.1 + slf4j2 bridge, Micrometer 1.14.1 + the
  Prometheus registry, Netty 4.2.9, Jackson 2.15.2 and tomcat-embed. Including
  tomcat-embed matters because it makes log4j2 treat the app as a web app, as it does in
  prod.
- **Plugin jar** (child `URLClassLoader`): the real, unmodified compiled classes of
  `bot-strategies` + `bot-messages` (136 classes). The engine-side registries
  (`BettingStrategyFactory`, `SlotStrategyFactory`, `MessageTypesRegistry`) are left out.
  One spike class, `spike.plugin.PluginHazards`, is added to perform each hazard *from
  plugin code*.
- **Engine-shaped use:**
  - A child `AnnotationConfigApplicationContext` with parent = a context holding shared
    beans and `classLoader` = the plugin loader. It scans only the plugin jar.
  - Parent-side `Map<String, Class<? extends BettingStrategy>>` filled exactly like
    `BettingStrategyFactory.init`, including `AnnotationUtils.findAnnotation(...)`.
  - `ctx.getBean(class)` gives a prototype per bot. `decide(BetContext)` and
    `onRoundEnd(RoundResult)` run with real `BotMemory`, `BotBehaviorConfig` and `Game`.
  - `BomGameMessageTypes.getTypeRegistrations(2000,false)` goes into a fresh per-bot
    `ObjectMapper`, configured like `BettingMiniGameBot.java:1074-1076`. It deserializes a
    real StartGame body as `BettingMiniMessage.class`.
- **Detection:**
  - `WeakReference<ClassLoader>`, then up to 20 × (`System.gc()` + 150 ms).
  - Two controls: a load-and-drop loader (collected) and a `Class` held in a parent static
    (pinned). Both behaved as expected, so the harness can tell the two cases apart.
  - Each pin is shown two-phase: pinned, then exactly one thing removed, then collected.
    That proves the removed thing was the *only* pin.

## Per-scenario results (reproducible: `out/results.txt`)

| # | Scenario | Collected? | Pin root cause | Mitigation (verified) |
|---|---|---|---|---|
| — | control: load + drop / Class in parent static | Y / N | (by design) | — |
| 1 | Baseline: `new RandomBehaviorStrategy()`, decide/onRoundEnd, drop | **Y** | — | — |
| 2a | Child Spring ctx, strategies in parent `registry` map, ctx **closed** | **N** | `registry` holds `Class<?>`, and a class pins its loader | Remove the registry entries, then **Y** |
| 2b | Registry cleared, ctx **never closed**, then dropped | **N** | Spring's static `AnnotationUtils` caches. The parent's own `findAnnotation` call fills them. They are soft refs: cleared under `-XX:SoftRefLRUPolicyMSPerMB=0` but not by `System.gc()` | `ctx.close()`, whose `resetCommonCaches()` clears them (2a proves it). Dropping the ctx alone is not enough |
| 3a | Fresh per-bot `ObjectMapper` + plugin `registerSubtypes`, dropped | **N** | Static `TypeFactory.defaultInstance()._typeCache`: a **strong** LRU of 200 entries that every `new ObjectMapper()` shares | `TypeFactory.defaultInstance().clearCache()` on unload gives **Y** |
| 3b | Same, but the mapper gets a private type cache: `setTypeFactory(TypeFactory.defaultInstance().withCache(new LRUMap<>(16,200)))` | **Y** | — | Preferred: one `TypeFactory` per plugin version, shared by that version's bot mappers. (`withClassLoader()` is **not** a fix: it shares the cache) |
| 3c | `ObjectMapperProvider.getDefault().registerSubtypes(pluginTypes)` | **N, permanent** | The `SubtypeResolver` of the static `DEFAULT` has no unregister API | **None**: still pinned after TypeFactory clear + ser/deser flush + `ObjectMapperProvider.clearCache()`. **Never do this** |
| 3d | `getDefault()` only *serializes* a plugin `Body` (no registration) | **N** | `DEFAULT`'s `SerializerCache`/`DeserializerCache` + TypeFactory | TF clear + `flushCachedSerializers()` + `DeserializerCache.flushCachedDeserializers()` gives **Y**. The last one needs reflection, so treat it as "don't" |
| 4a | Plugin `ThreadLocal` value on a long-lived pooled platform thread | **N** | Thread's `ThreadLocalMap` value → plugin object → its class → the plugin's static `ThreadLocal` key. The cycle holds itself up | `TL.remove()` on that thread gives **Y** |
| 4b | Same, on a virtual thread that finishes | **Y** | — | — |
| 4c | Same, on a virtual thread still parked | **N** | Live thread's map | Thread finishes, then **Y** |
| 4d | Plugin `MDC.put` / `ThreadContext.put` on pooled, virtual and main threads, **never cleared** | **Y** | — (values are `String`s; the map is parent-owned) | — |
| 5a | `Gauge` on a plugin object with `strongReference(true)` (Prometheus registry, scraped) | **N** | Registry → gauge → object | `registry.remove(meter)` gives **Y** |
| 5b | `Gauge` on a **parent** object (weak), but the value function is a plugin lambda | **N** | The lambda is a hidden class defined in the plugin loader, and the gauge holds the function strongly. This happens even without `strongReference` | `registry.remove(meter)` gives **Y** |
| 5c | `Counter` with `String` tags only, **left registered** | **Y** | — | (remove anyway for hygiene; series leak ≠ loader leak) |
| 6 | slf4j `LoggerFactory.getLogger(PluginClass.class)` (= `@Slf4j`, incl. the real strategy's), log4j2 `LogManager.getLogger(...)`, params + throwable, async appender | **Y** | — | — |
| 7a | Plugin task on the shared Netty group that the **parent warmed** first, then idle | **Y** | — | — |
| 7b | Group **cold**, plugin call path triggers event-loop thread start, TCCL untouched | **N** | JDK 21 `Thread` captures `inheritedAccessControlContext` (`AccessController.getContext()`) at construction. Its `ProtectionDomain[]` includes the plugin's domain, which points to the plugin loader. Dumped from the live thread: `[AppClassLoader, URLClassLoader@…, AppClassLoader]` | Group shutdown (threads die) gives **Y**. In prod: **start every event-loop thread at boot**, before any plugin loads |
| 7c | Group cold, TCCL = plugin loader at first submit | **N** | Same as 7b, **plus** the threads inherit TCCL = plugin loader | Same. And never leave TCCL set to a plugin loader around engine calls |
| full | Everything above in one engine-shaped lifecycle, with all mitigations | **Y** | — | — |

## Metaspace loop (`-XX:MaxMetaspaceSize=64m -Xmx512m`)

Each iteration runs the full lifecycle: new loader, child ctx scan + refresh, 9 strategies
exercised, BOM mapper deserialize, MDC/TL/log/netty/gauge/counter hazards, then unload.
Samples are taken after 3× GC.

**Clean, 1,000 iterations** (`out/loop1000.txt`):

| iter | metaspace used | committed | loaded classes | total loaded | unloaded | loaders alive |
|---:|---:|---:|---:|---:|---:|---:|
| 0 | 11.4 MB | 11.6 MB | 3,094 | 3,094 | 0 | 0 |
| 100 | 18.5 MB | 21.9 MB | 4,062 | 8,067 | 4,005 | 0 |
| 300 | 19.3 MB | 23.5 MB | 4,073 | 16,088 | 12,015 | 0 |
| 600 | 19.9 MB | 23.9 MB | 4,076 | 28,106 | 24,030 | 0 |
| 1000 | 20.0 MB | 24.3 MB | 4,079 | 44,129 | 40,050 | 0 |

- **Loaded classes are flat** (+17 over 900 iterations, from parent-side JDK lambda forms).
- **Exactly 40 plugin classes are unloaded per cycle.**
- **Used metaspace levels off:** +0.6 MB over iterations 300→1000 and +0.07 MB over the
  last 300. That is arena/fragmentation settling, not a leak.

**Leaking, same loop plus `getDefault().registerSubtypes(...)` per iteration** (`out/loopleak.txt`):

| iter | metaspace used | loaded | unloaded | loaders alive |
|---:|---:|---:|---:|---:|
| 100 | 31.6 MB | 8,130 | 0 | 100 |
| 200 | 45.0 MB | 12,139 | 0 | 200 |
| 300 | 58.2 MB | 16,140 | 0 | 300 |
| ~310 | **`java.lang.OutOfMemoryError: Metaspace`** | | | |

- **Cost of one retained version: about 136 KB of metaspace** (about 3.4 KB per class ×
  the 40 classes actually loaded). If a version loads all 136 classes, about 0.45 MB.
- Phase 1's instruments (`plugin_classloaders_registered_total`, `MetaspaceGrowth`) would
  see this leak shape clearly: loaded classes climb with zero unloads.

### `-XX:MaxMetaspaceSize` starting point: **192m**

- The prod/staging baseline is about 83 MB. 192m leaves more than 100 MB of headroom,
  which is roughly 230–750 leaked plugin versions at the measured cost per version. At a
  few reloads a day, a genuine leak is caught by the `MetaspaceGrowth` /
  `PluginClassLoadersRetained` alerts years before the cap.
- **Do not size it tight.** The Dockerfile runs with `-XX:+ExitOnOutOfMemoryError`, so
  hitting the cap means a JVM restart and every group stops. That is exactly what this
  feature exists to avoid.
- The cap is a backstop against a *fast* pathological leak, such as classes being defined
  per bot or a reload loop. It is not the detector.
- Re-check the number against Phase 1's 7-day baseline (AD-6) before shipping step 4.
  This spike measured per-version cost, not the prod baseline.

## ws-parser 3.0.5 static / retention inventory (scenario 8)

Source: `~/.m2/repository/com/vingame/websocket-parser-core/3.0.5/websocket-parser-core-3.0.5-sources.jar`
(the version `pom.xml:47` pins). Paths below are relative to `com/vingame/websocketparser/`.

| Location | What | Can it hold plugin classes? |
|---|---|---|
| `ObjectMapperProvider.java:74` | `static final ObjectMapper DEFAULT` | **Yes, permanent** if subtypes are registered on it (3c). Until flushed if it (de)serializes a plugin type (3d) |
| `ObjectMapperProvider.java:68` | `static ConcurrentMap<MapperConfig,ObjectMapper> CACHE` (builder-made mappers, `strict()`/`lenient()`) | Same as DEFAULT for each cached mapper. `clearCache()` (`:151`) drops the map but **not** `DEFAULT` |
| `scenario/PipelineContext.java:107-108` | Context mapper falls back to `getDefault()` when none is given | Yes, if a pipeline built without a mapper deserializes plugin types. Today the only `getDefault()` context is the OutputPrinter one (`BettingMiniGameBot.java:1153`, `SlotMachineBot.java:503`), and `OutputPrinter` has no typed matchers, so it is **safe today** |
| `message/ProcessableMessage.java:92, :112` | Same `getDefault()` fallback for typed `as()/tryAs()` | Same as above |
| `message/response/ActionResponseMessage.java:53` (used `:80`, `:107`) | `static MAPPER = getDefault()`. `serialize()` writes `data` (a `Body`), and static `deserialize(node, dataClass)` takes a caller class | Yes, if a plugin `Body`/`dataClass` goes through these static paths. Bot does not call them today |
| `auth/AuthClient.java:22` (used `:115`) | `static MAPPER = getDefault()` serializes a caller-supplied login-request object | Yes, if per-brand login requests ever move into a plugin. Bot uses `ApiGatewayClient` today |
| `message/RawMessage.java:41`, `message/AuthMessage.java:15`, `message/PingMessageImpl.java:39` | `static MAPPER = getDefault()` | No: `readTree` or library-owned types only |
| `VingameWebSocketClient.java:400` | Creates its own `MultiThreadIoEventLoopGroup` when no shared group is given (platform threads, lazy start) | Yes, by the 7b mechanism if it is started from a plugin frame. It dies on `close()` (`:549`). Bot always passes the shared group |
| `VingameWebSocketClient.java:901` | `Thread.ofVirtual()` reconnect thread | Virtual threads capture no ACC (4b). Inherits TCCL from its creator |
| `concurrent/VirtualThreads.java:28-52` | All library executors are virtual | No |
| `VingameWebSocketClient.java:162` | `scenariosByTag` (per client, not static) holds scenarios, which hold plugin callbacks and mappers | Per client: released on `close()`. **This is the drain vehicle**: a v1 loader lives exactly as long as its last v1 bot's client |
| `scenario/PipelineContext.java:84`, `scenario/tracing/RecordingPipelineTracer.java:37` | Instance `ThreadLocal`s (`Long`, `MessageRecordBuilder`) | `PipelineContext`: no. Tracer: only while recording on a live thread |
| `message/ProcessableMessage.java:80` | Per-message `bodyCache` keyed by `Class` | Transient, per message |
| `VingameWebSocketClient.java:83`, `scenario/tracing/Slf4jPipelineTracer.java:33`, `scenario/processors/SendMode.java:25-26`, `backpressure/BackpressureConfig.java:64` | Static constants and logger | No |

The same pattern exists on the engine side and is worth naming:
- `ApiGatewayClient.java:68` and `GameMsClient.java:22` are static `ObjectMapper`s.
  `ApiGatewayClient.java:654` serializes `loginRequestFactory.apply(ctx)`, so per-brand
  login requests must stay engine-side or be converted to `JsonNode`/`Map` first.
- `OutputPrinter.java:22` is a static mapper too, but it only reads `Object.class`, so it
  is safe.

## Hard rules step 4 must enforce

1. **Never register plugin subtypes on `ObjectMapperProvider.getDefault()`, or on any
   static or process-lifetime mapper.** This includes `ObjectMapperProvider`'s `CACHE`
   mappers, and `ApiGatewayClient`/`GameMsClient`'s static mappers. It cannot be undone.
   Also never let one of them (de)serialize a plugin type. Plugin message types go only
   through per-bot mappers.
2. **Give each plugin version its own Jackson type cache.** Bot mappers of version N get
   `setTypeFactory(TypeFactory.defaultInstance().withCache(new LRUMap<>(16, 200)))`, one
   instance per version. As a belt-and-braces step, call
   `TypeFactory.defaultInstance().clearCache()` when a version unloads.
3. **Unload order for a version:** remove its keys from every parent-side registry
   (`BettingStrategyFactory.registry`, `SlotStrategyFactory`, `MessageTypesRegistry`, and
   any `Class<?>` / `NamedType` / instance holder), then **`close()` the child context**,
   then drop it, then `URLClassLoader.close()`. Never merely drop an unclosed context.
   Spring's soft caches then pin until there is memory pressure.
4. **Every meter that plugin code registers, or that references a plugin object or plugin
   lambda, must be `registry.remove()`d when that version unloads.** That includes
   `strongReference(true)` gauges and also weak gauges with plugin-defined value
   functions. Simplest rule: plugins never touch `MeterRegistry` directly. The engine owns
   the meters and plugins report via parent-typed callbacks or values.
5. **Start all of the shared Netty `EventLoopGroup`'s threads at boot, before any plugin
   loads.** Submit a no-op to every `EventExecutor` in `NettyEventLoopConfig`. On JDK 21
   any **platform** thread first constructed beneath a plugin frame pins that loader for
   its whole life. In general: plugins may not create platform threads or platform
   executors (virtual only), and engine pools must be pre-started (`prestartAllCoreThreads`)
   or virtual.
6. **Never set TCCL to a plugin loader around engine or library calls.** If it is set to
   bootstrap a plugin, restore it in `finally` on the same thread.
7. **Plugin `ThreadLocal`s are banned on engine-owned long-lived threads** (Netty IO,
   pooled schedulers). If one is used, it must be `remove()`d in `finally`. MDC with
   `String` values is fine and needs no clearing for GC purposes; BotMdc's hygiene rules
   still apply.
8. **A version's loader lives until its last bot's `VingameWebSocketClient` is closed**,
   because `scenariosByTag` holds the per-bot mappers and callbacks. Step 6's "retained"
   signal must account for that drain time before it alarms.

## Where the spike may not represent production

- **Parent loader shape.**
  - Prod runs `java -jar Bot.jar`, so the parent is Spring Boot's `LaunchedClassLoader`
    over nested jars, not a flat app classpath.
  - Boot's nested-jar URL handler caches `JarFile`s by URL. **Load plugin jars from plain
    `file:` URLs on disk, never `jar:nested:`.** Confirm that this cache does not pin
    anything in a real Boot run.
- **Parent context.** It is a small `GenericApplicationContext`, not the full Boot context
  (actuator, auto-config, Spring Data Mongo). A Mongo `MappingContext` caches a
  `PersistentEntity` per class, strongly, so plugin types must never be persisted or
  mapped by Mongo. Strategies and messages are not today.
- **No live sockets.** The per-bot mapper path was exercised by a direct `readValue` with
  the real `NamedType` registrations, not through a connected `VingameWebSocketClient`
  pipeline.
- **Logging config.** It is simplified (Async → File + `JsonTemplateLayout`). Prod's two
  tracks, `RollingFile` and the `Configuration`-level `ScopedDebugFilter` were not
  reproduced. They key on MDC `String`s, so no difference is expected.
- **JDK.** Run on OpenJDK 21.0.2 on macOS; prod is `eclipse-temurin:21-jre` on Linux. The
  7b ACC capture is JDK ≤ 23 behaviour (JEP 486 removes it in 24). Bot stays on 21, so
  rule 5 holds.
- **One version at a time.** Step 5's two coexisting versions were not run together. Each
  cycle used an independent loader, so coexistence should not change the result.
- **Metaspace numbers.** These are per version for about 40 actually-loaded classes, on a
  JVM with an 11 MB baseline. They say nothing about prod's absolute baseline.

## Reproduce

```bash
S=/private/tmp/claude-501/-Users-gleb-IdeaProjects-Bot/4adab1e7-37e2-40e3-a370-c9e86f0a886a/scratchpad/cl-spike
# parent-cp.txt = `mvn dependency:build-classpath -pl bot-app` minus bot-strategies/bot-messages/bot-app
# plugin-classes/ = bot-strategies + bot-messages jars unzipped, minus the three engine-side registries
$S/build.sh
$S/run.sh control|s1|s2|s3|s4|s5|s6|s7|full
JVM_OPTS="-XX:MaxMetaspaceSize=64m -Xmx512m" $S/run.sh loop 1000
JVM_OPTS="-XX:MaxMetaspaceSize=64m -Xmx512m" $S/run.sh loopleak 1000
```
