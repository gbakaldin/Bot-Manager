# Plugin Hot Reload — Phases 1 & 2

## Goal

Build the first two steps of a drain-based plugin hot-reload capability, whose end
state is: **the application is never restarted for a product-implementation change,
and no bot group is ever stopped by a deployment** — groups migrate from plugin
version N to N+1 over time as their bots naturally recycle. Phase 1 ships the
instruments that will *detect* a classloader/metaspace leak, before Phase 4+ creates
the failure mode that can produce one. Phase 2 removes the actual barrier to
pluggability, which is **static resolution and enum-typed identity**, not dynamic
loading: product implementations (strategies, per-brand message providers) become
resolvable from string-keyed registries, still one version, still the same jar,
**behaviour identical**. Neither phase loads a class from anywhere new.

**Scope discipline.** This document plans **steps 1 and 2 only** of a seven-step
sequence. Steps 3–7 (separate jars, child classloader, two coexisting versions,
drain + classloader release, reload endpoint + forced cutover) are **out of scope**
and are named here only where a Phase 1/2 decision would otherwise foreclose them.

**Timing.** Only product 116 is in prod, and prod-116 is currently double-backed by
legacy JS bots from another environment hitting the same prod, so 116 cannot be left
botless. Those JS bots are being shut down soon. The restart-bearing steps (Phase 1's
deploy, Phase 2's deploys, and later step 4) must land while that safety net exists.

**Running in parallel, not planned here.** A throwaway spike proving that a child
`ClassLoader` is actually GC'd after the last strong reference is dropped. It gates
steps 4–6 and is explicitly not part of this plan. Phase 1's meters are chosen so
that the spike's finding is measurable in production, not only in the spike.

---

## Findings — Current State

### The module graph (unchanged by this plan)

`bot-app → bot-engine → {bot-strategies, bot-messages} → bot-api`, acyclic, no reverse
edge (`/Users/gleb/IdeaProjects/Bot/docs/plans/MODULE_DECOUPLING.md:149-151`).
`bot-api` is the non-reloadable contract module; `MODULE_DECOUPLING.md:17-20` records
that **`PLUGIN_PLAN.md` at the repo root is stale and proposes the inverse design**
(engine in the reloadable layer). Verified: `/Users/gleb/IdeaProjects/Bot/PLUGIN_PLAN.md:14-22`
places `bot-plugin-core` — containing `Bot`, `BettingMiniGameBot`, game state — in the
hot-reloadable layer. It is 582 lines and actively misleading.

### Observability — what already exists (the brief's premise is partly wrong)

**Metaspace and class-count series already exist and are already scraped.**
`bot-app/pom.xml:66-75` pulls `spring-boot-starter-actuator` +
`micrometer-registry-prometheus`; `JvmMetricsAutoConfiguration` (verified by
`javap` against `spring-boot-actuator-autoconfigure-3.4.0.jar`) registers
`JvmMemoryMetrics` **and** `ClassLoaderMetrics`. That yields, today, with no code
change:

| Series | What it says |
|---|---|
| `jvm_memory_used_bytes{area="nonheap",id="Metaspace"}` | metaspace bytes in use |
| `jvm_memory_used_bytes{area="nonheap",id="Compressed Class Space"}` | klass pointers |
| `jvm_classes_loaded_classes` | **classes** currently loaded |
| `jvm_classes_unloaded_classes_total` | classes unloaded since start |

`management.endpoints.web.exposure.include` already lists `prometheus`
(`bot-app/src/main/resources/application.properties:88`), and `jvm_` is on
`AlertRuleMetricsTest`'s `EXTERNAL_PREFIXES` allow-list
(`bot-app/src/test/java/com/vingame/bot/infrastructure/observability/AlertRuleMetricsTest.java:84`),
so a rule may already select them.

**What is genuinely missing is the count of classloader *instances*.** Micrometer has
no binder for it and the JVM exposes no MXBean for it — `jvm_classes_loaded_classes`
counts classes, not loaders. A retained-but-dead plugin classloader is exactly the
thing that shows up as "class count and metaspace never come down", and nothing in the
current scrape names the loader.

**Metaspace is unbounded.** `Dockerfile:16-19` runs
`java -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -jar Bot.jar`. There is no
`-XX:MaxMetaspaceSize`, so a classloader leak does **not** raise
`OutOfMemoryError: Metaspace` and therefore does **not** trigger
`ExitOnOutOfMemoryError`; it grows native RSS outside the heap cap until the kernel
OOM-kills the container. On a box that has already died once and taken Mongo with it
(2026-06-30), that is the failure shape to instrument for.

### Observability — the house conventions to follow

- **JVM-internals gauge precedent:** `bot-app/src/main/java/com/vingame/bot/infrastructure/logging/AsyncQueueMetrics.java`
  — `@Component`, 10 s virtual-thread sampler (`:159-162`), `Gauge.builder(...).strongReference(true)`
  (`:192-201`), counters **registered eagerly at zero** so an alert can be tested before
  the incident (`:203-211`), throttled WARN (`:268-286`), and an explicit note at
  `:113-116` that the meter names are **deliberately not `bot_`-prefixed** so
  `BotMdcTagsMeterFilter` cannot stamp them with the sampler thread's MDC.
- **MDC → metric tag:** `bot-engine/.../observability/BotMdcTagsMeterFilter.java:38-98`.
  It tags any `bot_*` meter with six MDC keys, minus an explicit
  `AGGREGATE_METER_NAMES` allow-list (`:47-74`).
- **Per-(group, dimension) counts as a MultiGauge:** `bot-app/.../observability/InfoGaugeRefresher.java`
  — 10 s refresher (`:75-93`), `MultiGauge` rows re-registered with `overwrite=true`
  every cycle (`:165-242`), `_join` and not `_info` because `_info` is a reserved
  Prometheus suffix (`:58-64`).
- **Cardinality claims are proved by an A/B fleet replay:** `bot-engine/src/test/.../ProductLabelCardinalityTest.java:37-80`.
- **Alert-rule/metric coupling:** `bot-app/src/test/.../RuntimeMetricsExposedToAlertsTest.java`
  boots the real actuator auto-configurations and asserts the exposition carries the
  exact names the rules spell. This is where a Spring-provided series gets pinned.
- **The JVM alert group** is `prometheus/alerts.yml:338` (`bot-manager-jvm`), holding
  `JvmThreadsHigh` (`:344`) and `LogQueueSaturated` (`:391`). House style: `labels:
  job: bot-manager / severity / audience: internal`, and thresholds are documented as
  first guesses to be tuned (`prometheus/alerts.yml:414-416`).
- **Log output is a closed key list on both tracks.** Track 1's JSON template
  (`bot-app/src/main/resources/log4j2-json-template.json`) enumerates five MDC keys;
  track 2's pattern (`logging/log4j2.properties:255`) renders `[%X{botGroupId}/%X{botId}/%X{gameType}]`.
  **Adding an MDC key therefore changes no log output unless a layout is also edited.**
- **MDC is set in exactly two places:** `bot-engine/.../core/Bot.java:271-280`
  (`initialize()`) and `bot-app/.../runtime/BotGroupRuntime.java:205-217` (`startBot`'s
  virtual-thread body). Cleared at `Bot.java:315` and `BotGroupRuntime.java:229`.
- **`PerBotInfoLogGuardTest`** bans `log.info(` outright in
  `bot-engine/.../domain/bot/core/**` and `bot-strategies/.../domain/bot/strategy/**`
  (`bot-app/src/test/.../PerBotInfoLogGuardTest.java:74-77`), exempting only the two
  strategy factories, whose single INFO fires once per **process**
  (`:83-94`). Also guards `ClientFactory.java` and `BotFactory.java` individually
  (`:79-81`).

### Phase 2 — the static-resolution barrier, verified

**`GameMessageTypesResolver` is a static class with three hardcoded product switches.**
`bot-messages/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypesResolver.java:32-103`:
`resolveBettingMini(ProductCode)` (`:41-56`), `resolveSlot()` (`:65-67`, product-neutral),
`resolveTaiXiu(ProductCode)` (`:87-102`). Return types are **deliberately disjoint**
(`GameMessageTypes` / `SlotMessageTypes` / `TaiXiuMessageTypes`, javadoc `:28-31`).
Unsupported products throw a specific operator-facing `IllegalArgumentException`
(`:52-54`, `:98-100`). Production call sites are three, all in
`bot-app/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java:160, 168, 181`.

**The provider impls are stateless class-literal tables** — verified on
`bot-messages/.../g2/bom/BomGameMessageTypes.java` (five methods, each `return X.class`).
Singleton sharing is therefore safe; today they are `new`-ed per bot.

**`bot-messages` has no `spring-context` dependency** (`bot-messages/pom.xml:25-51`:
`bot-api`, lombok, jackson-annotations, test only).

**`BettingStrategyFactory` is already 90% of a plugin registry.**
`bot-strategies/.../BettingStrategyFactory.java:41-102`: `@Component`, constructor takes
`List<BettingStrategy>`, `@PostConstruct init()` reads `@StrategyImpl` off each bean
(`:57-71`), `create(StrategyId)` returns a prototype instance via
`ApplicationContext.getBean(clazz)` (`:85-93`). `SlotStrategyFactory` is the identical
shape for slots (`bot-strategies/.../slot/SlotStrategyFactory.java:37-90`).

**The keys are enums in `bot-api`, and that is the whole blocker.**
`bot-api/.../strategy/StrategyId.java:16-49` (9 constants + displayName/description),
`bot-api/.../strategy/slot/SlotStrategyId.java:22-35` (2 constants),
`bot-api/.../strategy/StrategyImpl.java:19-21` (`StrategyId value()`),
`bot-api/.../brand/model/ProductCode.java:8-20` (10 constants, each carrying `code`,
`name`, `appId`, `usernameMaxLength`, `vipTalkRoomId`). The registries key
`EnumMap<StrategyId, …>` / `EnumMap<SlotStrategyId, …>`, so a plugin can replace an
existing strategy's *class* but cannot introduce a **new** key without an engine
release.

**Backward compatibility — the risk, and why it is smaller than it looks.**
`StrategyId`'s own javadoc warns that renaming a constant breaks persisted configs
(`StrategyId.java:6-9`). Persisted in two places:
`BotGroup.strategyMix : List<WeightedStrategy>` (`bot-app/.../botgroup/model/BotGroup.java:147`,
`WeightedStrategy` is `record WeightedStrategy(StrategyId strategyId, double weight)` —
`bot-api/.../strategy/WeightedStrategy.java:17`) and `BotGroup.slotStrategyId : SlotStrategyId`
(`BotGroup.java:160`). **There are no `MongoCustomConversions` / `@ReadingConverter` /
`@WritingConverter` anywhere in `bot-app`** (grep: zero hits), so Spring Data's default
enum handling applies: the BSON value is already the `name()` **string**. A `String`
field reads `"MARTINGALE_CLASSIC_CAUTIOUS"` back unchanged. The JSON wire shape is
likewise already the bare name.

**The one thing that is genuinely lost by going to strings is Jackson's implicit
rejection.** Today `{"strategyId":"NONSENSE"}` fails enum deserialization and returns
400. `BettingStrategyFactory.registeredIds()` (`:100-102`) is **not called from any
production code path** (grep over `bot-app/src/main`: zero hits) — validation today is
entirely a Jackson side effect. The natural home for a replacement is
`BotGroupConfigValidationService.validate(BotGroup)`
(`bot-app/.../botgroup/validation/BotGroupConfigValidationService.java:47`), which is
already called on **both** create and PATCH (`BotGroupService.java:148, 319`) and
already throws `BadRequestException`. `BotGroupMapper` is a MapStruct **interface** with
default methods and no injection (`bot-app/.../botgroup/mapper/BotGroupMapper.java:11-12`),
so validation cannot live there.

**`StrategyController` enumerates the enum directly:**
`bot-app/.../strategy/controller/StrategyController.java:34` —
`Arrays.stream(StrategyId.values()).map(StrategyInfoDTO::of)`. The DTO is
`record StrategyInfoDTO(String id, String displayName, String description)` and `id` is
already `id.name()` — a **string** on the wire
(`bot-app/.../strategy/dto/StrategyInfoDTO.java:19-27`).

**Consumers of the enum type** (all must move together in Phase 2b):
`BotConfiguration.strategyId` / `.slotStrategyId` (`bot-api/.../config/bot/BotConfiguration.java:92, 107`),
`Bot.strategyId` (`bot-engine/.../core/Bot.java:119`, assigned `:191`),
`BettingMiniGameBot.initializeSubclass` default (`:176-184`),
`SlotMachineBot` default (`:156-160`),
`BotHealthDTO.strategyId` (`bot-app/.../botgroup/dto/BotHealthDTO.java:31`),
`BotGroupBehaviorService.strategyCounts(Map<String, StrategyId>)` (`:831-837`) and
`effectiveStrategyMix` fallback `new WeightedStrategy(StrategyId.RANDOM, 1.0)` (`:843`),
`StrategyAssignment` (`bot-api/.../strategy/StrategyAssignment.java:66-182`),
and the 11 `@StrategyImpl` / `@SlotStrategyImpl` annotated classes in `bot-strategies`.

### Forward-compatibility facts confirmed for later steps (record only)

- **There is no process-wide Jackson subtype registry.** `ObjectMapper` is constructed
  **fresh per bot** — `bot-engine/.../core/BettingMiniGameBot.java:900-902` (`new ObjectMapper()`
  then `mapper.registerSubtypes(messageTypeRegistrations())`) and the identical shape at
  `SlotMachineBot.java:445-447`. This **contradicts the earlier assumption** that per-brand
  messages cannot live in a reloadable layer: two versions of a message class can coexist
  because no shared mapper caches either.
- **The one shared mapper is a cross-version collision point.**
  `ObjectMapperProvider.getDefault()` — a **static** from ws-parser — is used for the
  `OutputPrinter` pipeline context at `BettingMiniGameBot.java:969` and
  `SlotMachineBot.java:493`. Nothing registers subtypes on it today. **Registering
  plugin subtypes on it at step 4+ would collide across versions and would also pin the
  plugin classloader** via the static. Flag for the step-4 spike.
- **Classloader-retention hazards to name now, so Phase 1's meters can catch them:**
  the shared Netty `EventLoopGroup` (`ClientFactory.java:24, 84-96`; created once in
  `bot-app/.../config/NettyEventLoopConfig.java` and set at
  `EnvironmentClientRegistry.java:147`) whose IO threads outlive any group; MDC
  `ThreadLocal`s on pooled/IO threads (`BotMdc` is `ThreadLocal`-backed by design —
  `bot-api/.../common/logging/BotMdc.java:11-15`); Micrometer meters held strongly by the
  registry (`strongReference(true)` is used deliberately throughout); Spring singletons
  holding plugin-side refs (`BettingStrategyFactory.registry` holds `Class<?>` objects —
  a direct classloader pin).
- **The drain vehicle already exists and is proven in prod.** Periodic logout recycles
  one bot per group per hour, round-robin
  (`BotGroupBehaviorService.startPeriodicLogoutScheduler:1956-1990`,
  `performPeriodicLogout:2005`, config `bot.periodic-logout.*` at
  `application.properties:106-112`), alongside `Bot.restart` and the re-auth path.
  Groups on indefinite activation windows never recycle a *group*, so step 7 needs a
  forced cutover deadline regardless.

---

## Per-aspect readiness / mapping

| Aspect | Readiness | Notes |
|---|---|---|
| Metaspace bytes series | **ready — already exists** | `jvm_memory_used_bytes{area="nonheap",id="Metaspace"}`. Phase 1 pins it in a test, panels it, and alerts on growth. No code produces it. |
| Loaded/unloaded **class** counts | **ready — already exists** | `jvm_classes_loaded_classes`, `jvm_classes_unloaded_classes_total`. Same treatment. |
| Loaded **classloader** count | **blocked — must be built** | No Micrometer binder, no MXBean. Phase 1 ships `PluginClassLoaderMetrics` with weak-reference accounting. |
| Metaspace **bound** (`-XX:MaxMetaspaceSize`) | **partial — deliberately deferred** | Absent today; adding it is a JVM behaviour change and needs Phase 1's baseline to size. AD-6. |
| `pluginVersion` on bots | **ready** | New `BotConfiguration` field + `BotMdc` key; two MDC call sites. Constant `builtin`. |
| `pluginVersion` on groups | **ready** | `bots_by_plugin_version` MultiGauge rows on the existing `InfoGaugeRefresher`. |
| `pluginVersion` in Loki | **ready** | One field added to `log4j2-json-template.json` (track 1 only). |
| Alert rule for metaspace | **ready** | Ships in Phase 1 — the expected value today is flat, so no baseline is needed to call growth anomalous. AD-7. |
| Alert rule for retained classloaders | **partial — deferred to step 6** | Expression written down here; would be vacuous until two versions coexist. AD-7. |
| String-keyed strategy registries | **ready** | Factories already annotation-discovered; only the key type and `EnumMap` change. |
| String keys on the persisted path | **ready** | BSON is already `name()` strings; no converters exist; no migration script needed. |
| Replacing Jackson's implicit key validation | **ready** | `BotGroupConfigValidationService` is on both create and PATCH paths. |
| `StrategyController` contract preservation | **ready** | DTO `id` is already `String`; only the *source* of the list changes. |
| Message-types registry | **partial** | Requires `bot-messages` to gain `spring-context`, and loses the `switch`'s compile-time exhaustiveness — replaced by a test. AD-13, AD-15. |
| De-enum-ing `ProductCode` | **out of scope** | AD-16 — it is brand metadata consumed by auth/alerting/validation, not a plugin key. |
| `PLUGIN_PLAN.md` removal | **ready** | Docs-only; `MODULE_DECOUPLING.md:17-20` already supersedes it. |

---

## Architecture Decisions

### Phase 1 — observability baseline

**AD-1. Phase 1 creates no new failure mode and loads no class from anywhere new.**
Its entire purpose is that the meters predate the mechanism. The precedent is
non-negotiable: the 2026-07/08 native-thread leak was diagnosable **only** because
`jvm_threads_live_threads` predated it (`docs/plans/THREAD_LEAK.md:7, 76`). A leak
detector shipped in the same release as the leak proves nothing about the release.

**AD-2. The metaspace and class-count series are *adopted*, not built.** They already
exist (Findings). Phase 1's job for them is to (a) pin their exact names in
`RuntimeMetricsExposedToAlertsTest`, (b) panel them, (c) alert on growth, and (d)
record a 7-day baseline in the release report. Writing a custom metaspace gauge would
duplicate `JvmMemoryMetrics` and diverge from it.

**AD-3. Classloader accounting is our own, weak-reference-based, and keyed by
`pluginVersion`.** New `PluginClassLoaderMetrics` in
`bot-app/src/main/java/com/vingame/bot/infrastructure/observability/`, modelled
line-for-line on `AsyncQueueMetrics`. It publishes:

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `plugin_classloaders_live` | gauge | `pluginVersion` | registered loaders whose `WeakReference` has not been cleared |
| `plugin_classloaders_registered_total` | counter | `pluginVersion` | loaders ever registered — **corrected by Amendment A2**, this said `..._created_total`, which is unreachable from Prometheus |
| `plugin_classloaders_reclaimed_total` | counter | `pluginVersion` | loaders observed collected via the `ReferenceQueue` |

**`live` is the leak detector and the reason it must be weak-reference-based**: after a
drop, `registered − reclaimed` staying above zero *is* the retention. `jvm_classes_unloaded_classes_total`
moving is the JVM-side corroboration. At Phase 1 the registry holds exactly one
entry — the application classloader under `builtin` — so the readings are
`live{builtin}=1, registered_total{builtin}=1, reclaimed_total{builtin}=0`. That is
degenerate on purpose: the meter names, the panel, the tag, and the unit test all
exist and are proven before step 4 needs them.

**AD-4. Meter names are not `bot_`-prefixed, and `bots_by_plugin_version` goes on the
aggregate allow-list.** Same reasoning as `AsyncQueueMetrics.java:113-116`:
`BotMdcTagsMeterFilter` would otherwise stamp a JVM-wide fact with whatever MDC the
sampler thread inherited. `bots_by_plugin_version` does not literally match the
`bot_` prefix check, but it is added to `AGGREGATE_METER_NAMES` anyway, consistent
with `bots_managed` / `bots_by_status` which are there for the same belt-and-braces
reason.

**AD-5. `pluginVersion` is NOT added to `BotMdcTagsMeterFilter`'s tag list.** It goes on
MDC (for logs) and onto exactly **one** metric family (`bots_by_plugin_version`). Adding
it to the filter would put it on **every** `bot_*` series, which are already per-group;
during a step-5/6 drain that **doubles the cardinality of every bot counter** and leaves
a stale `v1`-labelled copy of each for Prometheus' full retention window. One join-style
gauge answers "which groups are on which version, and how far has the drain got"
without that. **The cardinality bound to carry forward:** `pluginVersion` is 1 value
today; steps 5–7 must hold it at **at most 2 concurrent values** (N and N+1), and
step 7's forced cutover deadline is the mechanism that enforces that — it is not a
nice-to-have, it is the cardinality bound.

**AD-6. Phase 1 does NOT add `-XX:MaxMetaspaceSize`.** It is a JVM behaviour change and
Phase 1 is behaviour-identical. It is also unsizable today: nobody has measured steady-state
metaspace on this app. **Phase 1's release report records the 7-day baseline, and the cap
lands with step 4** (the first release that can actually grow metaspace), sized at
baseline × 3 rounded up. Recording the *reason* matters as much as the number: with no
cap, a classloader leak never raises `OutOfMemoryError: Metaspace`, so
`-XX:+ExitOnOutOfMemoryError` (`Dockerfile:18`) never fires and the container is
OOM-killed by the kernel instead — silently, with no Java-side evidence.

**AD-7. One alert ships now; the second is written down now and ships at step 6.**
- **Ships in Phase 1 — `MetaspaceGrowth`**, in the existing `bot-manager-jvm` group
  (`prometheus/alerts.yml:338`). **Expression corrected by Amendment A3** — the
  ungated form originally written here fires for ~23 h after every restart:
  ```
  delta(jvm_memory_used_bytes{area="nonheap",id="Metaspace"}[24h]) > 52428800
    and on(job) (time() - process_start_time_seconds{job="bot-manager"} > 86400)
  ```
  `for: 1h`, `severity: warning`, `audience: internal`. **This rule is safe to ship
  without a baseline precisely because the current system has no mechanism to grow
  metaspace** — classes are loaded at boot and never after, so the expected value is
  ~0 and any sustained 50 MiB/day is already anomalous. That premise holds only
  *above* the boot ramp, which is what the uptime gate excludes; see A3. Comment it as
  a first guess, in the house style of `prometheus/alerts.yml:414-416`.
- **Deferred to step 6 — `PluginClassLoadersRetained`**:
  `sum(plugin_classloaders_live) > 2` `for: 2h`. It would be vacuous until two versions
  can coexist (the value is identically 1 until step 5), and a rule that can only be
  false teaches nobody anything. Expression recorded here so step 6 does not invent one
  under pressure. **It does not need A3's uptime gate** — `plugin_classloaders_live` is
  a gauge that starts at its steady-state value in the first millisecond of the JVM's
  life, not a 24 h delta over a boot ramp — but step 6 must ask A3's question of it
  anyway before shipping: *what does this expression read during the first hour after a
  restart?*

**AD-8. `pluginVersion` reaches Loki, and only track 1.** One field added to
`bot-app/src/main/resources/log4j2-json-template.json`. Track 2's `PatternLayout`
(`logging/log4j2.properties:255`) is **not** touched: it is the highest-volume track in
the system and the MDC keys it renders are a closed list, so leaving it alone is both
free and non-breaking for the `grep "\[<GROUP_ID>/"` recipes in `CLAUDE.md`. Track 1 is
tier-1/tier-2 only, so ~28 bytes/line is immaterial there.

**AD-9. Exactly one new INFO line, and it fires once per JVM.** A startup line
`plugin runtime: version=builtin, classloader=<identityHashCode>`, the same shape and
justification as `NettyEventLoopConfig`'s one-shot EventLoopGroup-identity line. Every
other Phase 1 signal is a metric. **No `log.info(` may be added to
`bot-engine/.../domain/bot/core/**` or `bot-strategies/.../domain/bot/strategy/**`** —
`PerBotInfoLogGuardTest` fails the build, and correctly so: `pluginVersion` is per-bot
and its INFO-tier representation is the group-level gauge, not a line.

**AD-10. `BotConfiguration.pluginVersion` is a runtime field, not a persisted one.**
`BotConfiguration` is built per bot at group start
(`BotGroupBehaviorService.createSingleBot`, ~`:790-810`) and is not a `@Document`.
No Mongo change, no migration. `BotHealthDTO` does **not** gain the field in Phase 1 —
that is an additive API change with no reader until a drain exists; it lands at step 5.

**AD-11. The value's single source of truth is `PluginVersions.BUILTIN` in `bot-api`**
(`com.vingame.bot.common.plugin.PluginVersions`), returned by a
`PluginVersionResolver` seam that Phase 1 implements as a constant. Step 4 changes that
one class; nothing else moves.

### Phase 2 — registry replaces static resolution

**AD-12. Registry keys are `String` everywhere. The enums survive, demoted to the
built-in catalogue.** `StrategyId` and `SlotStrategyId` are **retained** — they remain
the compile-time home of `displayName`/`description` for the eleven built-ins, and the
reference a parity test asserts against. What changes is that **no runtime code path may
switch on them or use them as a map key.** The rationale for keeping them rather than
deleting them: they are the only structured record of the eleven canonical key strings
and their UI copy, and deleting them would turn `StrategyId`'s "renaming a constant
breaks persisted configs" warning from a loud compile-time fact into folklore.
`StrategyCatalogParityTest` pins the contract: every `StrategyId`/`SlotStrategyId`
constant name is a registered key, and every registered key matching an enum name is
claimed by exactly one bean.

**AD-13. `@StrategyImpl` / `@SlotStrategyImpl` take a `String value()`; the eleven impls
use string literals.** Annotation members must be compile-time constants, so
`@StrategyImpl(StrategyId.RANDOM)` becomes `@StrategyImpl("RANDOM")`. Eleven one-line
mechanical edits. **Rejected alternative:** keeping `@StrategyImpl(StrategyId)` and adding
a second `@StrategyKey(String)` for plugins — zero churn, but two annotations forever and
two discovery paths in every factory. The parity test (AD-12) is what makes the literals
safe, and it is strictly stronger than the type check it replaces because it also catches
a *bean* that has gone missing, which the enum never could.

**AD-14. Existing persisted documents must resolve unchanged, and that is free.** The
BSON is already the `name()` string (Findings: no custom converters), so
`WeightedStrategy.strategyId : StrategyId → String` and
`BotGroup.slotStrategyId : SlotStrategyId → String` are read-compatible with every
document in Mongo today. **No migration script, no dual-read, no defaulting.**
`PersistedStrategyKeyCompatTest` pins it by mapping a literal pre-migration BSON
document through the Spring Data converter. The JSON wire shape is likewise unchanged
in both directions (the DTO already serialises the bare name).

**AD-15. The validation that Jackson was doing implicitly becomes explicit, at the same
HTTP status.** `BotGroupConfigValidationService.validate` rejects any `strategyMix`
entry or `slotStrategyId` whose key is not in the corresponding factory's
`registeredKeys()`, with a `BadRequestException` (400) naming the bad key and listing
the registered ones. This runs on **create and PATCH** (both already call `validate`),
which is strictly *better* coverage than today: Jackson only guards the request body,
whereas a group whose strategy bean disappeared in a deploy currently fails at
`BettingStrategyFactory.create` during group start, as an exception on a bot thread.
Pin the 400 with a controller test — this is the one place Phase 2 could silently
regress an API contract.

**AD-16. `ProductCode` stays an enum and is NOT de-enum-ed.** It is brand *metadata*,
not a plugin key: `appId` (auth gateway payloads), `usernameMaxLength` (pre-flight
validation), `vipTalkRoomId` (alert routing), `code`/`name`
(`bot-api/.../brand/model/ProductCode.java:8-20`). None of that is plugin-side. What
Phase 2 does instead is make the **message layer** key on the *string*
`ProductCode.getCode()` (`"116"`, `"097"`), which is what actually unblocks the real
case: five declared products (`P_066`, `P_103`, `P_105`, `P_119`, `P_222`) currently
throw "not yet implemented" from
`GameMessageTypesResolver.java:51-54`, and shipping a provider for one of them becomes
plugin-only work. **Adding an eleventh brand still requires an engine release**, and
that is consistent with the end-goal split ("engine changes are rare, targeted,
restart-allowed"). Recorded as an Open Item, not silently solved.

**AD-17. `MessageTypesRegistry` is a Spring `@Component` keyed on `(GameType,
productCode-string)`, preserving the three disjoint return types.** Three typed lookups
— `bettingMini(String product)`, `slot()`, `taiXiu(String product)` — because the
provider interfaces are genuinely different shapes and collapsing them to `Object`
would move the cast to every call site (the reason the current resolver is split, per
its javadoc at `:28-31`). Providers become beans carrying a new `bot-api` annotation
`@MessageTypesImpl(gameType = …, products = {"097","098"})`, with an empty `products`
meaning product-neutral (slot). `BomGameMessageTypes` legitimately claims two products
today and must keep doing so. **Providers become singletons** — safe, verified stateless
(Findings).

**AD-18. Discovery stays Spring bean discovery; `ServiceLoader` is rejected.** The
strategies already depend on Spring for per-bot **prototype** instances
(`BettingStrategyFactory.java:91-92`), which `ServiceLoader` cannot express, and running
two discovery models would be worse than either. `bot-messages` therefore gains a
`spring-context` dependency — a downward dependency on an external library, introducing
no reverse module edge. Step 4's child-classloader design uses a child
`AnnotationConfigApplicationContext` with `setClassLoader(pluginLoader)`, which is the
standard way to keep this mechanism working per-version.

**AD-19. The `switch`'s compile-time exhaustiveness is replaced by a test, not lost.**
Today adding an eleventh `ProductCode` constant breaks the build until someone handles
it in three switches. `MessageTypesCoverageTest` asserts that every `ProductCode` value,
for every `GameType`, either resolves a provider or appears on an explicit
`NOT_YET_IMPLEMENTED` list in the test — turning a compile error into a build failure
with the same protective effect and a readable inventory.

**AD-20. Operator-facing error text is preserved byte-for-byte.** A registry miss throws
the same `IllegalArgumentException` message the corresponding resolver method threw.
**Corrected by Amendment A6** — this originally cited `GameMessageTypesResolver.java:52-54`
/ `:98-100` and then quoted only the first of them, but those two ranges held **different**
strings, and the third operator-facing string in the same two methods was not named at all.
The three strings to preserve, all pinned as literals by a test:

| Where | String |
|---|---|
| `:52-54`, betting-mini miss | `"GameMessageTypes not yet implemented for product code: 066. Please create a GameMessageTypes implementation for this product."` |
| `:98-100`, Tai Xiu miss | `"TaiXiuMessageTypes not yet implemented for product code: 097. Please create a TaiXiuMessageTypes implementation for this product."` |
| `:42-44`, `:88-90`, null product | `"ProductCode cannot be null"` |

The contract name is therefore a **parameter** of the shared lookup, not a constant. These
strings are what an operator greps when a new brand's group fails to start, and
verification P2-7 greps the first of them verbatim.

**AD-21. `StrategyController`'s response contract is preserved exactly.** Same path,
same DTO, same `id` strings, and **the same order** — the endpoint lists the registry
but sorts built-ins into `StrategyId` declaration order first, then any non-built-in
key alphabetically, so the UI picker does not reshuffle on deploy. Pin the order with a
test; `StrategyId.values()` order is a de-facto UI contract that no one wrote down.
**The registry's own iteration order is not that order and must never be used as if it
were** — measured at Phase 2a it is alphabetical-by-class-within-package and differs from
`StrategyId.values()` in eight of nine positions — it agrees on `RANDOM` alone
(Amendment A4). Step 2d sorts explicitly,
from `StrategyId.values()`, over whatever order the registry happens to hand back.
**"Preserved exactly" governs the order and the per-entry values; "lists the registry"
governs the *set*, and the registry wins whenever the two disagree — see Amendment A10,
which also fixes the endpoint's expected body as a constant.**

**AD-22. `PLUGIN_PLAN.md` is deleted, not archived.** It proposes the inverse design
(`PLUGIN_PLAN.md:14-22` puts `Bot`/`BettingMiniGameBot` in the reloadable layer) and is
already formally superseded (`MODULE_DECOUPLING.md:17-20`). An archived copy in
`docs/` would still be found by grep and still mislead. Git history is the archive.

**AD-23. Phase 2 changes no behaviour.** Same strategies assigned, same messages parsed,
same bets placed, same HTTP responses, same log lines, same metric series. Any diff in
observable behaviour means a sub-phase overreached and must be reverted.

---

## Plan

Each sub-phase is independently buildable (`mvn clean install` green), boots, and is
independently deployable. Build with
`JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home`.

### Phase 1 — observability baseline (one Dev session)

1. **`bot-api`** — add `com.vingame.bot.common.plugin.PluginVersions` with
   `public static final String BUILTIN = "builtin"`, and a `PluginVersionResolver`
   interface with a single `String currentVersion()`. Add
   `BotMdc.PLUGIN_VERSION = "pluginVersion"` to
   `bot-api/.../common/logging/BotMdc.java` (constant + `MDC.remove` in `clear()`);
   add a `setPluginVersion(String)` helper rather than widening `set(...)`'s
   eight-argument signature. **Do not** add it to `GROUP_LEVEL_KEYS` — during a drain a
   group is mixed-version, so it is not a group-level fact.
2. **`bot-api`** — add `String pluginVersion` to `BotConfiguration` (defaults to
   `PluginVersions.BUILTIN` when null; not persisted, AD-10).
3. **`bot-engine`** — `Bot.getPluginVersion()` reading the configuration with the
   builtin default; call `BotMdc.setPluginVersion(...)` immediately after
   `BotMdc.set(...)` in `Bot.initialize()` (`Bot.java:271-280`), **before** the
   `mdcSnapshot` capture at `:282` so async callbacks inherit it.
4. **`bot-app`** — same one-line `setPluginVersion` call in `BotGroupRuntime.startBot`'s
   virtual-thread body (`:205-217`).
5. **`bot-app`** — new
   `infrastructure/observability/PluginClassLoaderMetrics.java`: `@Component`, a
   `register(String pluginVersion, ClassLoader)` API storing a
   `WeakReference` + `ReferenceQueue`, a 10 s virtual-thread sampler draining the queue
   and updating the three meters of AD-3, all three registered **eagerly at zero/one**
   so the series exist before an incident. `@PostConstruct` registers
   `getClass().getClassLoader()` under `PluginVersions.BUILTIN` and emits the single
   AD-9 INFO line.
6. **`bot-app`** — add a `bots_by_plugin_version` `MultiGauge` to `InfoGaugeRefresher`
   (register in `registerInfoGauges`, row-build in `refresh`, tags
   `{botGroupId, environmentId, product, pluginVersion}`, value = bot count), backed by
   a new `BotGroupBehaviorService.countBotsByPluginVersion()` in the same shape as
   `countBotsByEnvAndStatus()`. Add the name to
   `BotMdcTagsMeterFilter.AGGREGATE_METER_NAMES` (AD-4).
7. **Logging** — add a `pluginVersion` MDC field to
   `bot-app/src/main/resources/log4j2-json-template.json`. Leave both
   `log4j2.properties` twins untouched (`Log4j2TwinConfigTest` must stay green).
8. **`prometheus/alerts.yml`** — add `MetaspaceGrowth` to the `bot-manager-jvm` group
   (AD-7), with the first-guess-threshold comment.
9. **Grafana** — new `grafana/provisioning/dashboards/plugin-runtime.json` (the
   provider is a folder scan — `grafana/provisioning/dashboards/dashboards.yml:11-18`):
   panels for metaspace used, compressed class space, `jvm_classes_loaded_classes`,
   `rate(jvm_classes_unloaded_classes_total[1h])`, the three
   `plugin_classloaders_*`, and `bots_by_plugin_version` stacked by version.
10. **Tests**
    - Extend `RuntimeMetricsExposedToAlertsTest` to assert the exposition carries
      `jvm_memory_used_bytes` with `id="Metaspace"`, `jvm_classes_loaded_classes`, and
      `jvm_classes_unloaded_classes_total` — `AlertRuleMetricsTest` skips `jvm_*` via
      `EXTERNAL_PREFIXES` (`:84`), so this is the only place the new rule's metric gets
      pinned.
    - `PluginClassLoaderMetricsTest` — **this is the cheap version of the spike, and it
      belongs here regardless**: create a throwaway `URLClassLoader`, register it, drop
      the strong reference, `System.gc()` + drain with a bounded poll, assert
      `live` returns to the pre-registration value and `reclaimed_total` increments.
      Tolerate GC non-determinism with a timeout-and-`Assumptions` escape rather than a
      flaky hard assert.
    - `PluginVersionCardinalityTest` — an A/B fleet replay in the shape of
      `ProductLabelCardinalityTest`, asserting that `pluginVersion` adds **zero** series
      to the `bot_*` families (i.e. that AD-5 was actually implemented and the label did
      not leak into `BotMdcTagsMeterFilter`).
    - `PerBotInfoLogGuardTest` and `Log4j2TwinConfigTest` must pass **unchanged**.

### Phase 2a — string keys inside the strategy registries (one Dev session)

1. `bot-api`: `@StrategyImpl` / `@SlotStrategyImpl` member becomes `String value()`.
2. `bot-strategies`: the nine `@StrategyImpl` and two `@SlotStrategyImpl` classes take
   string literals equal to the enum names.
3. `BettingStrategyFactory` / `SlotStrategyFactory`: `EnumMap` → `LinkedHashMap<String, …>`
   (insertion-ordered — that order is Spring's **bean-discovery** order and is **not**
   `StrategyId.values()` order; AD-21's ordering is an explicit sort that does not build
   on it — **corrected by Amendment A4**, which supersedes the "so AD-21's ordering has a
   stable base" originally written here), `create(String)`,
   `registeredKeys()`. Keep **deprecated** `create(StrategyId)` / `create(SlotStrategyId)`
   overloads delegating to `create(id.name())` so no engine call site moves in 2a.
   Duplicate-key `IllegalStateException` and missing-annotation WARN behaviour unchanged.
4. New `StrategyCatalogParityTest` (AD-12).
5. Ship. Behaviour identical; the enums are still the only keys anything passes.

### Phase 2b — string keys on the persisted and wire paths (one Dev session)

1. `bot-api`: `WeightedStrategy(String strategyId, double weight)`;
   `BotConfiguration.strategyId` / `.slotStrategyId` → `String`; `StrategyAssignment`'s
   `List<StrategyId>` / `Map<String, StrategyId>` → `String`.
2. `bot-engine`: `Bot.strategyId` → `String`; `BettingMiniGameBot`'s
   `StrategyId.RANDOM` default (`:176`) → `StrategyId.RANDOM.name()`;
   `SlotMachineBot`'s `SlotStrategyId.FIXED` default (`:156-158`) likewise.
3. `bot-app`: `BotGroup.slotStrategyId`, `BotGroupDTO.slotStrategyId`,
   `BotHealthDTO.strategyId` → `String`; `BotGroupBehaviorService.strategyCounts`
   and `effectiveStrategyMix` (`:831-843`) updated.
4. Drop the deprecated overloads from 2a.
5. `BotGroupConfigValidationService.validate` gains the AD-15 key check (inject both
   factories).
6. Tests: `PersistedStrategyKeyCompatTest` (AD-14 — literal pre-migration BSON through
   the Spring Data converter); a `BotGroupController` test asserting
   `{"strategyId":"NONSENSE"}` is still **400** on both POST and PATCH; assert the
   `GET /{id}/health` and `GET /{id}` JSON bodies are byte-identical to the pre-change
   fixtures.
7. Ship. **This is the highest-risk sub-phase — deploy it alone.**

### Phase 2c — message-types registry (one Dev session)

1. `bot-api`: `@MessageTypesImpl(GameType gameType, String[] products default {})`.
2. `bot-messages/pom.xml`: add `spring-context` (AD-18). Annotate
   `BomGameMessageTypes` (`products = {"097","098"}`), `TipGameMessageTypes` (`"116"`),
   `NohuGameMessageTypes` (`"118"`), `SlotMessageTypesImpl` (`products = {}`),
   `MiniGameTaiXiuMessageTypes` (`"116"`), `JackpotTaiXiuMessageTypes` (`"114"`) with
   `@Component` + `@MessageTypesImpl`.
3. `bot-messages`: new `MessageTypesRegistry` `@Component` with the three typed lookups
   (AD-17), preserving AD-20's exception text; delete `GameMessageTypesResolver`.
4. `bot-app`: `BotFactory` injects the registry; the three call sites at
   `BotFactory.java:160, 168, 181` become instance calls, passing the product **through a
   null-safe key extraction** — `Environment.productCode` is a plain nullable field, so
   `env.getProductCode().getCode()` would convert the documented
   `IllegalArgumentException("ProductCode cannot be null")` into an NPE
   (**corrected by Amendment A7**; "Nothing else changes" originally read as forbidding
   the helper). `switch (game.getGameType())` at `:156` stays — it selects a *bot class*,
   not a product implementation, and being a switch **expression** over an enum with no
   `default` arm it keeps its own compile-time exhaustiveness (A7).
5. Tests: port `GameMessageTypesResolverTest` to the registry — and with it the **four
   other** test-side consumers of the resolver, across three modules
   (`SlotMessageTypesTest`, `TaiXiuMessageTypesTest`,
   `taixiu/JackpotTaiXiuMessageTypesTest` in `bot-messages`,
   `TaiXiuJackpotGameBotStreamTest` in `bot-engine`, plus the `BotFactory*` fixtures in
   `bot-app` that gain a constructor argument); add `MessageTypesCoverageTest` (AD-19)
   and an error-text test (AD-20).
6. Ship.

### Phase 2d — `StrategyController` + doc cleanup (one Dev session)

1. `StrategyController` lists the registries via a small `StrategyCatalog` that joins
   registered keys to display metadata (built-ins from the enums; a non-built-in key
   falls back to `key` as `displayName` and an empty description). Ordering per AD-21.
   Path, DTO shape and `id` strings unchanged.
2. Test pinning the exact response body for `gameType` absent / `BETTING_MINI` /
   `TAI_XIU` / `SLOT` against the pre-change baseline.
3. Delete `/Users/gleb/IdeaProjects/Bot/PLUGIN_PLAN.md` (AD-22).
4. Update `CLAUDE.md`: the Architecture backlog line "Spring Plugin Support Framework"
   gains a pointer to this plan; add a short "plugin registries" note to the
   Architecture section naming the string-key contract and the `StrategyId`-as-catalogue
   demotion.

---

## Implementation Notes / Concerns

- **The brief's premise about missing metrics is half wrong — do not build a metaspace
  gauge.** `jvm_memory_used_bytes{id="Metaspace"}` and `jvm_classes_loaded_classes`
  already ship. Only the *classloader instance* count is missing. A hand-rolled
  metaspace gauge would silently diverge from Micrometer's and split every dashboard.
- **`Bot.java` and every strategy class are `log.info`-banned.** `PerBotInfoLogGuardTest`
  scans **directories**, so a new class dropped into `bot-engine/.../domain/bot/core/`
  inherits the ban. If Phase 1 or 2 wants a group-scoped line from inside one of those
  files, move it to the group-level caller — the guard's own javadoc says exemptions are
  the wrong fix.
- **MDC ordering in `Bot.initialize` matters.** `mdcSnapshot = MDC.getCopyOfContextMap()`
  at `Bot.java:282` exists specifically because async callbacks can fire before the
  bot-creation thread gets further. `setPluginVersion` must precede it or the tag is
  missing from every callback-emitted line.
- **Weak references do not prove unloading; they prove *reachability*.** A cleared
  `WeakReference` means the loader was collected, which is what frees its metaspace —
  but `System.gc()` is a hint, so the unit test must tolerate a miss and the *production*
  gauge must be read as "≥ this many are retained", never "exactly this many are live".
  Same lower-bound discipline `AsyncQueueMetrics` documents at `:78-106`.
- **Do not rename the `com.vingame.bot` logger or the `com.vingame.websocketparser`
  logger** while editing the JSON template. Both are load-bearing for Spring's
  `setLogLevel` in-place mutation (`CLAUDE.md`, Logging Guidelines).
- **Phase 2a's string literals are the moment the enum stops being type-checked.** Ship
  `StrategyCatalogParityTest` in the *same commit* as the literals, not after — a
  half-hour window where a typo'd `@StrategyImpl("RANDOM ")` compiles and silently
  registers a phantom key is exactly the class of bug the enum was preventing.
- **Phase 2b's real hazard is a *round-trip*, not a read.** Reads are safe (BSON is
  already a string). The hazard is a PATCH that reads a group, maps to DTO, maps back,
  and saves — if any step drops or normalises the key, the group's persisted mix is
  rewritten. `BotGroupMapper`'s PATCH semantics
  (`BotGroupMapper.java:148-169`) full-replace `strategyMix` when supplied and keep it
  when null; verify both branches explicitly.
- **`slotStrategyId` null is meaningful.** `BotGroup.slotStrategyId == null` means
  "fall back to `FIXED` at bot-build time" (`BotGroup.java:151-160`,
  `SlotMachineBot.java:156-158`). A `String` field must preserve null as null — do not
  default it at the entity or DTO layer, only at the bot-build layer, exactly as today.
- **`bot-messages` gaining `spring-context` is the first Spring dependency in that
  module.** Confirm the fat jar and component scan still pick up the new `@Component`s:
  they keep the `com.vingame.bot.*` base package (`MODULE_DECOUPLING.md:173-175`), which
  is the only reason cross-module scanning works at all.
- **`BomGameMessageTypes` serves two products.** The registry key must be
  many-products-to-one-provider; an annotation with a single `product` member would
  silently drop `P_098`.
- **The three resolve methods are disjoint on purpose.** Do not "simplify" the registry
  to one `Object resolve(...)`; the javadoc at `GameMessageTypesResolver.java:28-31`
  explains why, and `BotFactory`'s three branches type-check against three different
  setters (`setMessageTypes` on two different bot classes plus `setTaiXiuMessageTypes`).
- **Deploying Phase 1 restarts Grafana/Prometheus/Loki too** — bot-manager and the
  observability stack share one Compose project on Bot-1. The smoke test must re-verify
  them, and the metaspace baseline collection starts only after that restart.
- **`logging/log4j2.properties` must reach the box** or bot-manager will not start.
  Unchanged by this plan, but it is the standing deploy hazard for this repo.

---

## Open Items

- **Out of scope, later steps:** separate plugin jars (step 3), child classloader
  (step 4), two coexisting versions (step 5), drain + classloader release (step 6),
  reload endpoint + forced cutover deadline (step 7). This plan only ships instruments
  and registries.
- **Running in parallel, not planned here:** the throwaway classloader-GC spike gating
  steps 4–6. `PluginClassLoaderMetricsTest` (Phase 1 step 10) is a cheap in-repo
  rehearsal of it, not a substitute.
- **Deferred with a named owner-phase:**
  - `-XX:MaxMetaspaceSize` — step 4, sized from Phase 1's 7-day baseline (AD-6).
  - `PluginClassLoadersRetained` alert — step 6, expression fixed in AD-7.
  - `BotHealthDTO.pluginVersion` — step 5 (AD-10).
  - `ObjectMapperProvider.getDefault()` static-mapper collision — step 4 spike input.
- **Explicitly not solved:** adding an **eleventh brand** from a plugin. `ProductCode`
  stays an enum (AD-16); a genuinely new product still needs an engine release, which is
  consistent with the end-goal split. Shipping a message provider for one of the five
  already-declared-but-unimplemented products (`P_066`, `P_103`, `P_105`, `P_119`,
  `P_222`) *does* become plugin-only work.
- **Needs a decision before step 5, not before Phase 1:** whether `pluginVersion` is
  assignable per bot group (an operator pins group X to v1) or only fleet-wide with
  natural drain. Phase 1's per-group gauge supports either; the drain mechanism does not
  yet exist to be constrained.
- **Not investigated:** whether the ws-parser library holds any static that would pin a
  plugin classloader. `ObjectMapperProvider.getDefault()` is one confirmed static; there
  may be others. Step-4 spike input.

---

## Verification

### Per-phase local gate (Dev, before handing off)

```bash
export JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home
mvn -q clean install
```
Expect `BUILD SUCCESS`, all five modules built, **zero test failures and zero errors**,
and a total test count **≥ 1866** (the pre-change baseline, measured at `254d56b` —
**corrected by Amendment A1 below**, which supersedes the 1485 originally written here;
each phase only adds tests). Record the exact count in the handoff.

```bash
mvn -q -pl bot-api dependency:tree | grep -E 'bot-app|bot-engine|bot-strategies|bot-messages'
```
Expect **no output** — the contract module must still have no reverse edge.

---

### Phase 1 — staging verification (Releaser, after deploy)

All commands run on the Bot-1 staging host. `BOT=http://localhost:8080`.

**P1-1 — the app is up and the stack came back with it.**
```bash
curl -sf $BOT/actuator/health
curl -sf http://localhost:9090/-/ready
curl -sf http://localhost:3000/api/health
```
Expect: HTTP 200 with `{"status":"UP"...}`; Prometheus `Prometheus Server is Ready`;
Grafana HTTP 200. (bot-manager and the observability stack share one Compose project —
a bot redeploy restarts all three.)

**P1-2 — the one-shot plugin-runtime INFO line is present.**
```bash
docker logs bot-manager 2>&1 | grep -c 'plugin runtime: version=builtin'
```
Expect: exactly **1**.

**P1-3 — the adopted JVM series are in the scrape under the names the alert uses.**
```bash
curl -sf $BOT/actuator/prometheus | grep -E '^jvm_memory_used_bytes\{.*id="Metaspace"'
curl -sf $BOT/actuator/prometheus | grep -E '^jvm_classes_(loaded_classes|unloaded_classes_total)'
```
Expect: one `Metaspace` sample with a value **> 0**; both class-count series present.

**P1-4 — the new classloader meters exist at their baseline values.**
```bash
curl -sf $BOT/actuator/prometheus | grep -E '^plugin_classloaders_'
```
Expect exactly three families, all tagged `pluginVersion="builtin"`:
`plugin_classloaders_live = 1`, `plugin_classloaders_registered_total = 1`,
`plugin_classloaders_reclaimed_total = 0`. A missing series (rather than a zero) means
the eager registration did not run. **`plugin_classloaders_registered_total` is corrected
by Amendment A2** — this step originally spelled it `..._created_total`, a name no scrape
can ever contain; if you see `plugin_classloaders_total` here, the rename was lost.

**P1-5 — every running bot is accounted for under `builtin`.** Start (or confirm
running) one bot group, wait ≥ 20 s for two refresher cycles, then:
```bash
curl -sf $BOT/actuator/prometheus | grep '^bots_by_plugin_version'
curl -sf $BOT/actuator/prometheus | grep '^bots_managed '
```
Expect: one `bots_by_plugin_version` row per running group, all with
`pluginVersion="builtin"`, and **the sum of their values equal to `bots_managed`**. A
shortfall means a bot's `pluginVersion` resolved to null.

**P1-6 — `pluginVersion` reached Loki, and only track 1.**
```bash
grep -m1 pluginVersion logs/console.log
grep -c pluginVersion logs/detail/detail.log
```
Expect: at least one JSON line in `console.log` containing `"pluginVersion":"builtin"`;
and **0** in `detail.log` (track 2's pattern was deliberately not changed — AD-8). In
Grafana, `{job="bot-manager"} | json | pluginVersion="builtin"` must return lines.

**P1-7 — no metric-cardinality regression on the bot counters.**
```bash
curl -sf $BOT/actuator/prometheus | grep -c '^bot_bets_placed_total'
curl -sf $BOT/actuator/prometheus | grep -c 'pluginVersion' 
```
Expect: the `bot_bets_placed_total` series count **unchanged from the pre-deploy
baseline** (record it before deploying), and **no `bot_*` line carries a
`pluginVersion` label** — the only metric families mentioning it are
`plugin_classloaders_*` and `bots_by_plugin_version` (AD-5).

**P1-8 — the new alert rule loaded and is not firing.**
```bash
curl -sf http://localhost:9090/api/v1/rules | grep -o 'MetaspaceGrowth'
curl -sf 'http://localhost:9090/api/v1/query?query=ALERTS%7Balertname%3D%22MetaspaceGrowth%22%7D'
```
Expect: `MetaspaceGrowth` present in the loaded rules, and the `ALERTS` query returning
an **empty result vector** (`"result":[]`). Since Amendment A3 the rule cannot evaluate at
all below 24 h of uptime, so on a freshly deployed box an empty vector is guaranteed and
proves only that the rule *loaded*. A firing rule after the box has been up a day means the
threshold is wrong, not that there is a leak. Confirm the gate is actually in the loaded
rule, not just in the file:
```bash
curl -sf http://localhost:9090/api/v1/rules | grep -o 'process_start_time_seconds[^"]*'
```

**P1-9 — the dashboard provisioned.**
```bash
curl -sf http://localhost:3000/api/search?query=plugin | head
```
Expect: a hit for the `plugin-runtime` dashboard (the provider rescans every 30 s).

**P1-10 — the baseline, recorded in the release report (this is a deliverable, not a
check).** After **7 days**:
```bash
curl -s 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes%7Barea%3D%22nonheap%22%2Cid%3D%22Metaspace%22%7D'
curl -s 'http://localhost:9090/api/v1/query?query=delta(jvm_memory_used_bytes%7Barea%3D%22nonheap%22%2Cid%3D%22Metaspace%22%7D%5B7d%5D)'
curl -s 'http://localhost:9090/api/v1/query?query=jvm_classes_loaded_classes'
```
Expect: a steady-state metaspace figure and a 7-day delta **near zero**. Write both
numbers into the release report — AD-6 sizes `-XX:MaxMetaspaceSize` from them at step 4,
and a non-flat 7-day delta *today* is a pre-existing leak that must be understood before
any child classloader is introduced.

---

### Phase 2 — staging verification (Releaser, after each sub-phase's deploy)

Phase 2 is **behaviour-identical** (AD-23), so verification is a differential smoke:
capture each artefact **before** deploying, compare after.

**P2-1 — health and stack, as P1-1.** Expect the same three 200s.

**P2-2 — the strategy registries came up with the full catalogue.**
```bash
docker logs bot-manager 2>&1 | grep -E '(Betting|Slot)StrategyFactory initialized'
```
Expect two lines: `BettingStrategyFactory initialized: registered 9 strategies — [...]`
and `SlotStrategyFactory initialized: registered 2 strategies — [...]`. **Counts 9 and
2**; anything lower means a bean lost its annotation key. **The order inside the brackets
changes at 2a and that is expected** (Amendment A4): the line prints the registry's key
set, which was `EnumMap` ordinal order before and is bean-discovery order after —
`[RANDOM, MARTINGALE_CLASSIC_AGGRESSIVE, MARTINGALE_CLASSIC_CAUTIOUS, DALEMBERT_*,
FIBONACCI_*, PAROLI_*]`. Compare the **counts and the set**, not the sequence. No HTTP
response order changes at 2a — `StrategyController` still enumerates the enum until 2d.

**P2-3 — `GET /api/v1/strategy/` is byte-identical (2a and 2d).**
```bash
# before deploy
curl -sf $BOT/api/v1/strategy/ > /tmp/strategy-before.json
curl -sf "$BOT/api/v1/strategy/?gameType=SLOT" > /tmp/strategy-slot-before.json
# after deploy
curl -sf $BOT/api/v1/strategy/ > /tmp/strategy-after.json
diff /tmp/strategy-before.json /tmp/strategy-after.json
```
Expect: `diff` produces **no output** — same nine entries, same ids, same displayNames,
same descriptions, **same order** (AD-21). `?gameType=SLOT` must still return `[]`.

**P2-4 — an existing group's persisted strategy mix survives a round trip (2b — the
critical check).** Pick a long-running staging group id `$GID`:
```bash
# before deploy
curl -sf $BOT/api/v1/bot-group/$GID > /tmp/group-before.json
# after deploy
curl -sf $BOT/api/v1/bot-group/$GID > /tmp/group-after.json
diff /tmp/group-before.json /tmp/group-after.json
```
Expect: **no output**. Then confirm Mongo itself is untouched:
```bash
docker exec mongo mongosh botmanager --quiet --eval \
  'db.botGroup.find({strategyMix:{$exists:true}},{strategyMix:1,slotStrategyId:1}).limit(5).toArray()'
```
Expect: `strategyId` values still the same uppercase names (e.g. `"RANDOM"`,
`"MARTINGALE_CLASSIC_CAUTIOUS"`) — **no rewrite, no nulls, no lowercase**.

**P2-5 — a bad key is still a 400, on both create and PATCH (2b).**
```bash
curl -s -o /dev/null -w '%{http_code}\n' -X PATCH $BOT/api/v1/bot-group/$GID \
  -H 'Content-Type: application/json' \
  -d '{"strategyMix":[{"strategyId":"NONSENSE","weight":1.0}]}'
```
Expect: **400**. Then re-run P2-4's `GET` and confirm the group is unchanged (the
rejection must not partially apply).

**P2-6 — a group actually starts and bets (2b/2c — the end-to-end proof).** On a
known-good `BETTING_MINI` group (avoid tx7 and any DNS-blocked TIP env):
```bash
curl -sf -X POST $BOT/api/v1/bot-group/$GID/start
sleep 120
curl -sf $BOT/api/v1/bot-group/$GID/health | head -c 400
grep -E 'StartGame session-entry|EndGame results' logs/detail/detail.log | tail -5
curl -sf $BOT/actuator/prometheus | grep -E '^bot_(bets_placed|messages)_total' | head
```
Expect: health shows bots in a connected/playing status; at least one StartGame
session-entry **and** one EndGame results line in track 2 (staging runs `BOT_LOG_LEVEL=DEBUG`);
and `bot_messages_total` **> 0** with `bot_bets_placed_total` present. This is the only
check that proves the message-types registry (2c) resolves the right provider —
a wrong provider parses nothing and the session lines never appear.

**P2-7 — the unsupported-product error text is unchanged (2c).** If a group exists on a
product with no provider (`P_066`/`P_103`/`P_105`/`P_119`/`P_222`), attempt to start it:
```bash
docker logs bot-manager 2>&1 | grep 'not yet implemented for product code'
```
Expect the message to read exactly
`GameMessageTypes not yet implemented for product code: <code>. Please create a
GameMessageTypes implementation for this product.` If no such group exists on staging,
record that this check was **not exercisable** rather than marking it passed — the
build-time test (AD-20) is the fallback evidence.

**P2-8 — no new alerts fired across the Phase 2 deploys.**
```bash
curl -sf 'http://localhost:9090/api/v1/query?query=ALERTS%7Balertstate%3D%22firing%22%7D'
```
Expect: the same firing set as before the deploy (typically empty). A newly firing
`EnvironmentDeadBotRatioHigh` or `GameNoRounds` after 2b/2c means a group stopped
resolving its strategy or its messages — roll back that sub-phase.

---

## Amendment — 2026-08-26

*Issued by the Compliance Architect during the Phase 1 review
(`docs/reviews/PLUGIN_HOT_RELOAD/compliance.md`). Nothing outside the one figure below
changes; every Architecture Decision stands as written.*

### A1 — the per-phase local gate's test baseline is 1866, not 1485

**What was wrong.** The "Per-phase local gate" said `≥ 1485 (the pre-change baseline)`.
That figure does not describe this repo at the commit the branch was cut from.

**Measured.** A detached worktree at `254d56b` — the branch point, i.e. the pre-change
state the gate names — built with
`JAVA_HOME=…/openjdk-21.0.2 mvn test`, counting `tests=` across every module's
surefire XML:

| Module | Tests at `254d56b` |
|---|---|
| `bot-api` | 125 |
| `bot-strategies` | 111 |
| `bot-messages` | 136 |
| `bot-engine` | 420 |
| `bot-app` | 1074 |
| **Total** | **1866** |

The same count on the Phase 1 branch head (`4cc152e`) is **1884**, i.e. Phase 1 adds
**18** tests. Both builds are `BUILD SUCCESS` with zero failures and zero errors.

**Why this is a plan defect and not an implementation one.** The number is a claim about
the repository, not about anything Dev could have implemented differently; no correct
implementation of Phase 1 would have produced 1485. And it is not cosmetic: `≥ 1485` is
the *gate* every sub-phase in this document hands off against, so as written it would
pass a build that had silently lost **381** existing tests. The gate is only as strong as
the baseline is true.

**What changed.** The figure in the Verification section, and nothing else. Re-measure the
baseline when it is next used against a different branch point rather than assuming 1866
carries forward.

---

## Amendment — 2026-08-26 (Phase 1 fix pass)

*Issued by Dev while fixing the two blocking defects in
`docs/reviews/PLUGIN_HOT_RELOAD/qa.md` (FAIL) and `docs/reviews/PLUGIN_HOT_RELOAD/review.md`
(CHANGES_REQUESTED). Both are **plan defects**: Compliance verified the implementation
matched this document, so the code was wrong because this document was. Fixing only the
code would leave step 6 to re-derive the same broken rule from AD-7.*

### A2 — the loaders-ever-registered counter is `plugin_classloaders_registered_total`

**What was wrong.** AD-3's meter table and verification P1-4 both named the counter
`plugin_classloaders_created_total`. That name is **unreachable from Prometheus**.
`_created` is a reserved suffix (the OpenMetrics created-timestamp series), so the client's
name sanitiser strips `_total`, then strips `_created`, and the counter exposition
re-appends `_total`. The meter therefore scrapes as `plugin_classloaders_total` — a name no
panel queries and no verification step greps. Its sibling
`plugin_classloaders_reclaimed_total` is untouched by the rule, which is what made this look
like a typo rather than a rule.

**Measured**, by probe against a real `PrometheusMeterRegistry` (QA's, then re-run
independently before choosing the replacement):

| Registered | Scraped |
|---|---|
| `plugin_classloaders_created_total` | `plugin_classloaders_total` |
| `plugin_classloaders_registered_total` | `plugin_classloaders_registered_total` |
| `plugin_classloaders_loaded_total` | `plugin_classloaders_loaded_total` |
| `plugin_classloaders_reclaimed_total` | `plugin_classloaders_reclaimed_total` |

**Corrected to `plugin_classloaders_registered_total`** — it round-trips intact, pairs with
`..._reclaimed_total`, and is semantically truer to `register(...)`. AD-3's table, AD-3's
prose (`registered − reclaimed`), and verification P1-4 are updated in place.

**Why the whole Phase 1 test suite missed it.** Every test that pinned these names did so
against a `SimpleMeterRegistry`, which applies no naming convention at all. The guard is
now `InfoGaugePrometheusScrapeTest`, which asserts against the real text exposition — the
same file that exists because `game_info` once scraped as bare `game`. Its
`doesNotContain("plugin_classloaders_total")` assertion is the durable half: it fails if
any future spelling of this family picks up a reserved suffix. **Any later rename of a
meter in this document must be probed against a real `PrometheusMeterRegistry` first.**

### A3 — `MetaspaceGrowth` needs an uptime gate, or it fires on every restart

**What was wrong.** AD-7 specified

```
delta(jvm_memory_used_bytes{area="nonheap",id="Metaspace"}[24h]) > 52428800   for: 1h
```

and justified it with "the current system has no mechanism to grow metaspace, so the
expected value is ~0". **That is true only above the boot ramp.** Metaspace is not flat
through a restart: it goes ~0 → ~90-140 MB as Spring Boot, Mongo, Netty, Jackson,
Micrometer, MapStruct and springdoc load — several times the 50 MiB threshold. Two effects
compound:

1. **`delta()` extrapolates** a partially-covered range exactly as `rate()` does. At 2 h of
   uptime a real +70 MB is reported as `70 MB × (24/2)` ≈ 840 MB.
2. **Even once the range is covered**, `delta` is `value(now) − value(24 h ago)`, and for the
   first 24 h after a restart that second term is the near-zero reading taken as the JVM
   started.

So the rule is continuously true from ~T+1 h (when `for: 1h` is satisfied) to T+24 h after
**every** restart: every deploy, every crash-restart, every OOM-kill — including the deploy
that ships it. `audience: internal` routes it into the VipTalk ops room, next to
`BotManagerRestarted`, which is already telling the same story accurately.

**Why this is worse than noise, and why it is a Phase 1 blocker rather than a tuning
follow-up.** The rule's own comment tells the operator that a firing in the first week is a
pre-existing leak to be understood before any child classloader is introduced. Ungated, the
first week's firings *are* the boot ramp. The operator either chases a phantom or mutes the
rule — and with no `-XX:MaxMetaspaceSize` (AD-6, deferred to step 4) this rule is the only
warning that shape of failure gets before a silent kernel OOM-kill. Phase 1's entire
deliverable is a leak detector; an instrument that lies in the direction that gets it muted
fails the phase on its own terms.

**Corrected expression** (shipped; the gate is the `process_start_time_seconds` idiom
already in this file at `prometheus/alerts.yml:124`):

```
delta(jvm_memory_used_bytes{area="nonheap",id="Metaspace"}[24h]) > 52428800
  and on(job) (time() - process_start_time_seconds{job="bot-manager"} > 86400)
```

`and on(job)` because both sides carry `job="bot-manager"` and there is exactly one
bot-manager target per Prometheus (`prometheus/prometheus.yml`). `86400` is the `[24h]`
window: the rule may only evaluate once its own range contains no boot ramp.

**`delta()` is kept** rather than swapped for `max_over_time(...) - min_over_time(...)`.
With the gate the range is always fully covered, so the extrapolation pathology is gone,
and `delta` stays *directional* — max-minus-min cannot be negative and would fire on a
spike that had already recovered, which is not what "metaspace is growing" means.

**Consequences elsewhere, all applied:**
- `grafana/provisioning/dashboards/plugin-runtime.json`'s "MetaspaceGrowth's expression"
  panel gains a second series carrying the gated expression, so the panel still shows what
  the rule evaluates. The raw series is kept alongside it deliberately: gating the only
  series would blank the panel for a day after every deploy, which reads as a broken panel.
- Verification P1-8 is amended — below 24 h of uptime an empty `ALERTS` vector is now
  guaranteed and proves only that the rule loaded, so P1-8 also greps the loaded rule for
  the gate.
- **Verification P1-10's 7-day baseline must be read from a JVM with >24 h of uptime**, or
  the boot ramp is baked into the threshold AD-6 sizes `-XX:MaxMetaspaceSize` from at
  step 4.

**Why this is a plan defect, not an implementation one.** Compliance verified the shipped
rule matched AD-7 byte-for-byte, so no implementation of Phase 1 that followed the plan
would have caught it. Fixing only `alerts.yml` would leave step 6 to derive
`PluginClassLoadersRetained` from the same unexamined premise. **The general rule this
leaves behind: before shipping any rule over a range vector, ask what it reads during the
first `<range>` after a restart.**

---

## Amendment — 2026-08-26 (Phase 2a review)

*Issued by the Compliance Architect during the Phase 2a review
(`docs/reviews/PLUGIN_HOT_RELOAD/compliance-2a.md`), on a claim Dev raised in handoff and
which measurement confirms. The Phase 2a diff is **accepted unchanged** — it implements the
step as written. What changes is one factual claim in the step's own parenthetical, because
Phase 2d would otherwise inherit it. Every Architecture Decision stands.*

### A4 — `LinkedHashMap` insertion order is bean-discovery order, not `StrategyId` order

**What was wrong.** Phase 2a step 3 justified `LinkedHashMap` as *"insertion-ordered, so
AD-21's ordering has a stable base."* The insertion order is real, but it is **not** a base
for AD-21's ordering, and calling it one invites the reading "the registry already comes
back in the right order, so 2d only has to append the non-built-ins."

**Measured** on the Phase 2a branch (`feature/plugin-hot-reload-2a`, `5ca4cc7`), from the
factory's own `@PostConstruct` INFO line, which prints `registry.keySet()`. The order is
identical under `StrategyCatalogParityTest`'s bare `AnnotationConfigApplicationContext`
scan and under `ApplicationContextLoadsTest`'s full Spring Boot scan:

| | Order |
|---|---|
| `StrategyId.values()` | RANDOM, MARTINGALE_CLASSIC_**CAUTIOUS**, MARTINGALE_CLASSIC_**AGGRESSIVE**, **PAROLI**_CAUTIOUS, PAROLI_AGGRESSIVE, **DALEMBERT**_CAUTIOUS, DALEMBERT_AGGRESSIVE, FIBONACCI_CAUTIOUS, FIBONACCI_AGGRESSIVE |
| Registry insertion | RANDOM, MARTINGALE_CLASSIC_**AGGRESSIVE**, MARTINGALE_CLASSIC_**CAUTIOUS**, **DALEMBERT**_AGGRESSIVE, DALEMBERT_CAUTIOUS, FIBONACCI_AGGRESSIVE, FIBONACCI_CAUTIOUS, **PAROLI**_AGGRESSIVE, PAROLI_CAUTIOUS |

They agree on RANDOM and differ in **all eight of the remaining positions** — `PAROLI_*`
and `DALEMBERT_*` swap as blocks *and* the cautious/aggressive pair swaps within each
block, so no position after the first survives. (Corrected during the Phase 2d fix pass:
this said "six of the remaining eight", which QA re-measured against the table directly
above. It *understates* the divergence, so nothing built on it changes.) The registry
order is Spring's classpath-scan order — alphabetical by **class file name within
package**, with `RandomBehaviorStrategy` ahead of the `martingale/` subdirectory because
`R` sorts before `m` in ASCII. So it tracks *class names*, not enum names: renaming
`ParoliCautious`, or moving a strategy to another package, silently reorders it, and
nothing in the build would notice. It is deterministic, but it is arbitrary and it is not
the persisted/UI identity of anything.

**Why this is a plan defect and not an implementation one.** Dev used `LinkedHashMap`
exactly as instructed; there is no implementation of step 3 that would make the sentence
true. The map choice is still right — a deterministic iteration order keeps the boot log
and any future diagnostic stable, which `HashMap` would not — but it is right for that
reason, not for the reason given. And the sentence is load-bearing in the direction of a
regression: AD-21 requires built-ins in `StrategyId` declaration order because that order
is a de-facto UI contract, and the one check that would catch a reshuffled picker is
release-time (`P2-3`'s before/after `diff`), on staging, and only if the pre-deploy capture
was taken.

**What changed.** The parenthetical in Phase 2a step 3; a sentence on AD-21 stating that
registry order is never to be used as if it were display order; and the note on
verification P2-2 below. **No Architecture Decision, no code, and no shipped behaviour.**

**Consequences elsewhere, all applied:**
- **AD-21 is unaffected in substance and is now explicit**: 2d sorts built-ins from
  `StrategyId.values()` and non-built-ins alphabetically, over whatever order the registry
  returns. `StrategyController` still enumerates the enum directly at 2a, so
  `GET /api/v1/strategy/` is byte-identical across this deploy and `P2-3` passes as written.
- **Verification P2-2 now says the bracketed list reorders at 2a and that this is
  expected.** It is the one observable difference this sub-phase produces, and it is
  unavoidable: `EnumMap` iterated in ordinal order, and no string-keyed map can reproduce
  that without an `Enum.valueOf` per key — which is exactly the coupling AD-12 forbids. It
  is inside AD-23's tolerance because AD-23's subject is behaviour ("same strategies
  assigned, same HTTP responses"), and a set printed in a different order in one
  once-per-JVM boot line is not that. Recorded rather than waved through, because "same log
  lines" is written down and a releaser diffing boot logs would otherwise flag it.
- **The general rule this leaves behind:** an ordering requirement is satisfied by a sort,
  never by a container's incidental order. If a plan step names a collection type *because
  of* the order it yields, print the order and check it.

---

## Amendment — 2026-08-26 (Phase 2b review)

*Issued by the Compliance Architect during the Phase 2b review
(`docs/reviews/PLUGIN_HOT_RELOAD/compliance-2b.md`). The Phase 2b diff is **accepted
unchanged** — it implements steps 1-6 as written, and the check AD-15 asks for is exactly
the check that shipped. What changes is one sentence of AD-15's **justification**, which
is falsifiable and false, and which steps 5-7 would otherwise inherit. **No Architecture
Decision changes in substance, no code changes, no shipped behaviour changes.***

### A5 — AD-15's coverage is not "strictly better"; it moves the guard onto persisted state

**What was wrong.** AD-15 says:

> This runs on **create and PATCH** (both already call `validate`), which is strictly
> *better* coverage than today: Jackson only guards the request body, whereas a group whose
> strategy bean disappeared in a deploy currently fails at `BettingStrategyFactory.create`
> during group start, as an exception on a bot thread.

Both halves of that comparison are wrong, in opposite directions.

**Measured**, against `feature/plugin-hot-reload` at `63c31c8`:

1. **Group start is not on the `validate` path, so the bot-thread failure the sentence
   promises to fix is unchanged.** `BotGroupConfigValidationService.validate` has exactly
   two production callers — `BotGroupService.save` (create only; guarded by
   `isNewGroup`, `BotGroupService.java:148`) and `BotGroupService.update`
   (`:319`). `BotGroupBehaviorService`'s start/restart path never calls it, and
   `update()` routes through `save(existing)`, which skips the create-only branch. A group
   whose strategy bean vanished still dies at `BettingStrategyFactory.create` on a bot
   thread at group start, exactly as before. The new check fires only on a **write**.
2. **`validate` is post-merge over the whole entity on PATCH**
   (`BotGroupService.update`: `mapper.updateEntityFromDTO(dto, existing)` **then**
   `configValidation.validate(existing)` — deliberately so, per TIMED_ACTIVATION AD-6, for
   cross-field rules). So the check reads the *persisted* `strategyMix` /
   `slotStrategyId`, not only what the caller submitted. **A group holding a key no bean
   claims therefore fails every PATCH, including one that touches neither strategy
   field** — renaming the group, adjusting `maxBet`, changing the activation window. Before
   Phase 2b those PATCHes succeeded, because Jackson only ever inspected the request body.
   That is a new failure mode, and it is not derivable from the sentence above.

**Why this is a plan defect and not an implementation one.** AD-15 names the seam
(`BotGroupConfigValidationService.validate`) and both of its call sites; that seam is
post-merge, and no implementation of AD-15 that used it could avoid either consequence.
Dev implemented the check as specified and its own test suite pins the benign half
(`BotGroupStrategyKeyValidationTest.Patch.unrelatedPatchStillPasses` — "the persisted mix
is re-validated and valid"). The defect is in the claim, not the code.

**How bad it actually is, so step 5 does not over-correct.** Bounded, and the shipped
behaviour is the right trade:
- **The group is recoverable through the API.** `strategyMix` is full-replace when
  supplied, so `PATCH {"strategyMix":[{"strategyId":"RANDOM","weight":1.0}]}` clears the
  fault and passes post-merge validation. A stale `slotStrategyId` is fixed by supplying a
  valid one (it cannot be set back to `null` — PATCH treats `null` as "keep" — but
  `"FIXED"` is equivalent to the null fallback).
- **Lifecycle is unaffected.** `start`, `stop`, `restart`, `schedule-restart` and `delete`
  are separate endpoints that do not validate; `setActivationMode` and every scheduler
  write go straight to the repository. `ActivationScheduler` and the health monitor cannot
  be broken by a stale key.
- **The 400 names the bad key and lists the registered ones**, sorted, so the operator can
  self-serve.

**Corrected wording for AD-15's last paragraph** (substance unchanged):

> This runs on **create and PATCH** (both already call `validate`). Note what that does and
> does not buy. `validate` is **not** on the group-start path, so a group whose strategy
> bean disappeared in a deploy still fails at `BettingStrategyFactory.create` on a bot
> thread — this phase does not change that. And because `validate` runs **post-merge over
> the whole entity** on PATCH, the check reads persisted state as well as the request body:
> a group holding an unregistered key fails *any* PATCH until its mix is replaced. That is
> accepted deliberately — the group stays startable, stoppable and deletable, and the 400
> names the key and lists the catalogue — but it is a new failure mode, not pure upside.

**Consequences for later steps, which is why this is recorded rather than waved through:**
- **Steps 5-7 are exactly when a registry shrinks.** A cutover to version N+1 that does not
  carry a strategy key version N served makes every group still holding that key
  un-PATCHable until an operator rewrites its mix. Step 7's forced-cutover design must
  either keep the union of both versions' keys registered for the drain window, or
  accept and document that groups mid-drain are read-only through PATCH.
- **If the group-start guard is wanted, it is a separate decision.** Adding
  `validate` (or a narrower key check) to the start path would turn a bot-thread exception
  into a clean 400/409 at the start endpoint, and would deliver what AD-15's original
  sentence claimed. It is **not** in Phase 2's scope and is not smuggled in here; name it
  at step 5 if the drain makes it necessary.
- **The general rule this leaves behind:** a validator that runs post-merge validates the
  document, not the request. Before adding a rule to one, ask what it does to a *persisted*
  document that already violates it — the answer is "every future write of that document
  fails", and that is a migration question, not a validation question.
## Amendment — 2026-08-26 (Phase 2c review)

*Issued by the Compliance Architect during the Phase 2c review
(`docs/reviews/PLUGIN_HOT_RELOAD/compliance-2c.md`), on two under-specifications Dev raised
in handoff and which measurement against `c3fac8a` confirms. The Phase 2c diff is **accepted
unchanged** — in both cases the code is right and this document was wrong. Every
Architecture Decision stands in substance; AD-20 gains the two strings it omitted.*

### A6 — AD-20 cites two ranges holding **different** strings, and quotes only one

**What was wrong.** AD-20 required a registry miss to throw "the same
`IllegalArgumentException` message as `GameMessageTypesResolver.java:52-54` / `:98-100`",
then quoted a single literal naming `GameMessageTypes`. Read literally that is
unimplementable-as-intended: the two cited ranges did not hold the same string.

**Measured** at `c3fac8a`, from
`bot-messages/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypesResolver.java`:

| Range | Method | String |
|---|---|---|
| `:52-54` | `resolveBettingMini` | `GameMessageTypes not yet implemented for product code: <code>. Please create a GameMessageTypes implementation for this product.` |
| `:98-100` | `resolveTaiXiu` | `TaiXiuMessageTypes not yet implemented for product code: <code>. Please create a TaiXiuMessageTypes implementation for this product.` |
| `:42-44`, `:88-90` | both | `ProductCode cannot be null` |

So the quoted text is the betting-mini variant only. Implementing it for **both** ranges —
the literal instruction — would make a Tai Xiu miss report `GameMessageTypes` to an operator
who is grepping for the contract that actually failed, and would do it in the one string
this AD exists to protect: verification **P2-7 greps `'not yet implemented for product
code'` out of `docker logs`** and expects the message to name the missing contract. The
third string, `"ProductCode cannot be null"`, is equally operator-facing and equally
survivable only by being written down; AD-20 did not mention it, and it was left to AD-23's
general "changes no behaviour" to carry.

**Why this is a plan defect and not an implementation one.** There is no implementation that
satisfies the quoted sentence for both ranges without regressing one of them. Dev
parameterised the contract name on a shared private `lookup(...)` and pinned all three
strings as whole literals in `MessageTypesErrorTextTest` — which is the only reading that
preserves behaviour, and it is what AD-23 requires.

**What changed.** AD-20 now lists all three strings in a table and states that the contract
name is a parameter of the lookup rather than a constant. **No code, no shipped behaviour,
no other Architecture Decision.**

**The general rule this leaves behind:** when a decision says "byte-for-byte", quote every
byte it governs. A single quoted example next to a plural citation reads as "these are the
same string" and will be implemented that way.

### A7 — Phase 2c step 4's "Nothing else changes" turns a documented exception into an NPE

**What was wrong.** Step 4 said the three `BotFactory` call sites "become instance calls.
Nothing else changes." The registry lookups take a `String`, so the literal transcription is
`messageTypesRegistry.bettingMini(env.getProductCode().getCode())`.

**Measured.** `Environment.productCode`
(`bot-app/src/main/java/com/vingame/bot/domain/environment/model/Environment.java:45`) is a
plain Lombok `@Getter/@Setter` field on a `@Document` — no `@NonNull`, no validation, and a
Mongo document written without it deserialises to `null`. Both pre-2c resolver methods
opened with an explicit `if (productCode == null) throw new
IllegalArgumentException("ProductCode cannot be null")`, i.e. the null case was **handled
and documented**, with its own javadoc `@throws`. The literal transcription replaces that
with a `NullPointerException` from `BotFactory` — a different type, a different message, and
no mention of the product code at all. That is precisely the silent contract change AD-23
forbids, produced by following the plan.

**Why this is a plan defect and not an implementation one.** Step 4 and AD-23 contradict each
other for a null product code, and step 4 is the more specific instruction, so a faithful Dev
reading it as written ships the NPE. Dev instead added a four-line private
`productKey(Environment)` that forwards the null, letting the registry's own guard produce
the original message; `MessageTypesErrorTextTest.nullTextIsUnchanged` pins it on both
lookups. That is the behaviour-preserving reading and it is accepted.

**What changed.** Step 4 now names the null-safe key extraction, and records the second
half of the same sentence that a review pass questioned: `switch (game.getGameType())` at
`:156` is a switch **expression** over an enum with no `default` arm, so `javac` still
fails the build on a sixth `GameType` after 2c exactly as before it. Nothing was lost there
and nothing had to replace it. Step 5's "port `GameMessageTypesResolverTest`" is also
corrected to name the four other test-side consumers across three modules that the resolver
had. **No code, no shipped behaviour, no Architecture Decision.**

**The general rule this leaves behind:** "the call site becomes an instance call, nothing
else changes" is only true when the old and new signatures accept the same domain. A change
of parameter *type* — here enum to `String` — moves the null handling from the callee to the
caller unless someone writes down where it went.

---

## Amendment — 2026-08-27 (Phase 2 review fix pass)

*Issued by the Compliance Architect while verifying the fix pass that answers
`review-2a.md` / `review-2b.md` / `review-2c.md`
(`docs/reviews/PLUGIN_HOT_RELOAD/compliance-fixpass.md`). The fix-pass diff is **accepted
unchanged**. Both entries below are things this document should already have said and does
not: A8 is a step-5 constraint the plan never recorded, A9 is one stale sequence in a
verification step. **No Architecture Decision changes in substance, no code changes, no
shipped behaviour changes.***

### A8 — the registry key has no version dimension, and a duplicate key fails the whole context

**What is missing.** Nothing in this document — not AD-12, not AD-17, not AD-21, not the
"Forward-compatibility facts confirmed for later steps" list, not the Open Items — records
either half of the following, which `review-2c.md` F4 raised against
`MessageTypesRegistry` and `review-2a.md` raised against `BettingStrategyFactory`. A5
covers the adjacent case (a registry that *shrinks* across a cutover leaves groups holding
a retired key un-PATCHable); this is the case where a registry *collides*, and it is not
the same problem.

**Measured** at `7d8a08d`, and true of all three registries:

1. **Two live plugin versions are not expressible under the current keys.** The
   message-types key is `(GameType, productCode)`
   (`MessageTypesRegistry.indexByProduct`); the strategy key is the bare string
   (`BettingStrategyFactory.init`, `SlotStrategyFactory.init`). None carries a version.
   Step 5's stated end state is v1 and v2 of a brand's plugin serving different groups at
   the same time — both versions' providers claim `"116"`, both versions' strategies claim
   `"RANDOM"`, and under these keys that is a duplicate.
2. **A duplicate key fails context refresh, i.e. it takes the whole engine down.** All
   three registries throw `IllegalStateException` from a `@Component`
   constructor / `@PostConstruct`. That is **right while every key is ours** — two built-in
   providers claiming `"116"` is a programming error and the build should not produce a
   startable artifact — and it becomes **wrong the moment a third-party plugin can collide
   with a built-in**, because a bad plugin should be rejected and logged, not stop the
   other nine brands from starting.

**What step 5 has to decide, once, for all three registries.** Either a version dimension
in the key, or one registry instance per plugin version in a child context with
`BotFactory` selecting on the group's pinned version — plus a **collision policy** for a
key a plugin does not own. The policy must be one policy: the mechanism is now duplicated
in a second registry, so deciding it twice is how the two drift. This is needed **before**
plugins can register, not after, which is why it is recorded here rather than left to be
rediscovered under time pressure.

**Why this is a plan entry and not a code change.** Neither half is a defect today —
singleton-in-the-root-context is the correct shape for one version, and the throw is the
correct posture for keys that are all ours. Dev recorded both in a comment on the
`registry.put` line in `MessageTypesRegistry` (the code that has to change) and could not
record them here. Both are now here as well; the comment stays, because that is where the
next person editing the key will read it.

**Related, and deliberately not merged into this entry:** A5's drain-window constraint
(step 7 must keep the union of both versions' *strategy* keys registered, or accept that
mid-drain groups are read-only through PATCH). A5 is about a key that disappears; A8 is
about a key claimed twice. Step 5 needs an answer to both.

### A9 — verification P2-2's bracketed order is sorted, not discovery order

**What was wrong.** A4 rewrote P2-2 to say the strategy-factory boot line prints the
registry's key set in bean-discovery order and quoted the expected sequence
`[RANDOM, MARTINGALE_CLASSIC_AGGRESSIVE, MARTINGALE_CLASSIC_CAUTIOUS, DALEMBERT_*,
FIBONACCI_*, PAROLI_*]`. The fix pass answers `review-2a.md`'s first finding — that
classpath-scan order leaks into one INFO line and two exception messages — by sorting
every operator-facing key list at render time. All three registries' boot lines and both
`create()` lookup-failure tails now render sorted; `MessageTypesRegistry`'s startup line
sorts its product lists for the same reason (`[097, 098, 116, 118]`, `[114, 116]`).

**Consequence for the releaser.** `BettingStrategyFactory initialized` now reads
`[DALEMBERT_AGGRESSIVE, DALEMBERT_CAUTIOUS, FIBONACCI_AGGRESSIVE, FIBONACCI_CAUTIOUS,
MARTINGALE_CLASSIC_AGGRESSIVE, MARTINGALE_CLASSIC_CAUTIOUS, PAROLI_AGGRESSIVE,
PAROLI_CAUTIOUS, RANDOM]` and `SlotStrategyFactory initialized` reads `[FIXED, RANDOM]`.
**P2-2 still passes exactly as written** — it asks for the counts (9 and 2) and the set,
and explicitly says not to compare the sequence. Only A4's illustrative sequence is stale.

**Still inside AD-23, for the reason A4 gave.** AD-23's subject is behaviour — same
strategies assigned, same HTTP responses — and a set printed in a different order in one
once-per-JVM boot line is not that. `GET /api/v1/strategy/` is untouched (it enumerates
`StrategyId` until 2d), so P2-3 is unaffected and AD-21's display order is unaffected: the
sort applies to diagnostics, never to the UI contract.

**The general rule this leaves behind:** an amendment that quotes an observed sequence
dates as fast as the sequence does. Quote the property being asserted (counts, set) and
keep the sequence as an illustration marked as one.

---

## Amendment — 2026-08-27 (Phase 2d review)

*Issued by the Compliance Architect during the Phase 2d review
(`docs/reviews/PLUGIN_HOT_RELOAD/compliance-2d.md`), on the first of three items Dev
raised in handoff. The Phase 2d diff is **accepted unchanged** — it implements steps 1-4
as written and its choice on the under-specified point is the correct one. **No
Architecture Decision changes in substance, no code changes, no shipped behaviour
changes.***

### A10 — AD-21 asserts two things that only coincide while the build forces them to; registry membership wins

**What was under-specified.** AD-21 opens with *"`StrategyController`'s response contract
is preserved exactly"* and, one clause later, *"the endpoint lists the registry"*. Before
Phase 2d the endpoint enumerated `StrategyId.values()`, so its response was the **enum**;
after 2d it is the **registry**. Those are two different statements about the *set* of
entries, and this document never says which one wins when they disagree. It says only how
to order the set, which is the question A4 had already forced into the open — the set
question was never asked.

They disagree in exactly one direction that matters: a `StrategyId` constant whose bean
has gone missing. Under "preserved exactly" it is still offered by the picker and then
rejected on POST by AD-15's key check; under "lists the registry" it is absent.

**Measured** at `9c61a4c`, against the shipped guards:

| Guard | Context it scans | What it asserts |
|---|---|---|
| `StrategyCatalogParityTest` (bot-strategies) | bare `AnnotationConfigApplicationContext` over `com.vingame.bot.domain.bot.strategy` | every enum name **is** a registered key; sizes are 9 and 2 |
| `ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated` (bot-app) | the **real** `@SpringBootTest` scan from `Starter` | `registeredKeys()` **equals** `StrategyId.values()` as a set, both directions, for both registries |

So the divergence is not shippable today: the second test is exact set equality under the
production scan and fails the build in either direction. Dev's handoff attributes the
guard to `StrategyCatalogParityTest`, which is the weaker of the two — it proves
enum ⊆ registry under a bare package scan, and its own javadoc says it cannot see a bean
that is reachable from that scan yet unreachable from `Starter`'s. **The load-bearing
guard is `ApplicationContextLoadsTest`.** Name it, because it is the one a future edit
could weaken without noticing what it was for.

**Ruled: the registry is the set; the enum is only the copy.** Three reasons, in
increasing order of how much they bind:

1. **The enum-as-set reading makes Phase 2 deliver nothing at this endpoint.** A
   plugin-supplied key is, by construction, one the enum does not declare. If the enum
   decided membership, the picker could never offer a plugin strategy, and AD-21's own
   "the endpoint lists the registry" would be decorative.
2. **It keeps the picker and the validator agreeing.** AD-15 rejects any key no bean
   claims, on create and PATCH. Offering a key in the picker that the very next POST
   answers with a 400 is a worse contract than not offering it.
3. **A5 already made the enum reading actively misleading.** A group holding an
   unregistered key fails *every* PATCH until its mix is replaced. Under the enum
   reading the picker would keep presenting that key as a valid choice while no write
   carrying it can succeed.

**Why this is a plan entry and not a code change.** There is no implementation that
satisfies both clauses of AD-21 in the divergent case, and nothing in this document
breaks the tie. Dev picked the clause that AD-21's own mechanism sentence names and that
the phase exists to enable, and pinned it with
`StrategyCatalogTest.unregisteredBuiltinIsNotListed`. That is the right answer; it was
just never written down as the answer.

**Consequences for later steps, which is the reason this is recorded rather than waved
through:**

- **Steps 5-7 are when this stops being hypothetical.** The build gate is a build gate: it
  constrains one artifact's built-ins. A5 already establishes that a cutover to version
  N+1 which does not carry a key version N served leaves groups holding that key
  un-PATCHable. Under A10 the same cutover *also silently removes that key from the
  picker* — at runtime, with no test able to fire. That is the correct behaviour and it is
  invisible, so step 7's forced-cutover design must decide whether the drain window keeps
  the union of both versions' keys registered (A5's option, which also fixes this) or
  whether a key vanishing from the UI mid-drain is announced some other way.
- **A missing bean is now visible only in the boot line.** Under the enum reading a lost
  strategy produced a 400 on use; under this one it produces a shorter list nobody counts.
  Verification P2-2's `registered 9 strategies` count is therefore the operator-facing
  signal for the whole endpoint, not just for the factory — treat a count below 9 as a UI
  regression as well as a wiring one.
- **Phase 2d step 1's "lists the registri*es*" (plural) does not extend to slots.** The
  slot registry has two keys and `SlotStrategyId` carries display copy for both, so
  joining it would compile and read like an improvement. AD-21 and verification P2-3 both
  require `?gameType=SLOT` to keep returning `[]`, and they govern; the plural is
  illustrative. The shipped code pins `"[]"` with that reasoning in a comment, which is
  the durable home for it. **Wording only — no substance changes here.**
- **The general rule this leaves behind:** when a decision says a response is "preserved
  exactly" *and* names a new source for it, say which one owns the **set**, which one owns
  the **order**, and which one owns the **values**. Those are three contracts, and only
  the last two were specified.

### A11 — verification P2-3's "before" body is a constant; pin it instead of relying on a capture

**What was wrong.** P2-3 verifies `GET /api/v1/strategy/` by capturing the body before the
deploy and diffing it after. That works, but it makes the *only* evidence for the one
endpoint this phase re-sources depend on an ephemeral `/tmp` file, and for Phase 2 it
already failed: `docs/reviews/PLUGIN_HOT_RELOAD/release-phase2.md` reports P2-3 as PASS
with a matching md5, but the preserved artefacts
(`phase2-capture-before.txt` / `-after.txt`) contain **no strategy body at all** — zero
occurrences of `displayName`. Only the md5 in the prose survived. The 2d deploy would
otherwise have inherited a "before" that does not exist on file.

**Measured.** The pre-Phase-2d body is fully determined by `StrategyId` and
`StrategyInfoDTO` — nine entries, no server state, no configuration. Serialising
`Arrays.stream(StrategyId.values()).map(StrategyInfoDTO::of).toList()` at `9c61a4c`
reproduces the release log's md5 exactly:

```
GET /api/v1/strategy/            1822 bytes   md5 a72c40f56057cda5434b273ea36315ea
GET /api/v1/strategy/?gameType=SLOT          "[]"
```

That the independently computed figure equals the one the releaser recorded off the box
is what makes it usable: the same constant is both the pre-2d and the post-2d expectation,
because AD-21 freezes the body and `StrategyCatalogResponseContractTest` asserts
byte-identity against that same expression through the slice's `ObjectMapper`.

**Corrected P2-3.** Still take the before/after capture — it is cheap and it catches a
serialisation difference the build cannot see — but check both sides against the constant
as well:

```bash
curl -sf $BOT/api/v1/strategy/ | md5sum      # expect a72c40f56057cda5434b273ea36315ea
curl -sf "$BOT/api/v1/strategy/?gameType=SLOT"   # expect []
```

A mismatch on the **before** capture means the box is not running what you think it is; a
mismatch on the **after** capture is an AD-21 regression and the sub-phase rolls back.
**Preserve the body itself** alongside the group captures — a capture referenced by a
release report and not committed is not evidence. Re-derive the constant if `StrategyId`'s
copy ever changes; it is a hash of the display text, not of the design.

**Why this is a plan entry.** P2-3 as written is not wrong, it is fragile in a way that
was demonstrated rather than theorised, and the replacement is a measurement this document
can carry. **No code, no shipped behaviour, no Architecture Decision.**
