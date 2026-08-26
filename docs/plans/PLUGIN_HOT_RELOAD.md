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
| `plugin_classloaders_created_total` | counter | `pluginVersion` | loaders ever registered |
| `plugin_classloaders_reclaimed_total` | counter | `pluginVersion` | loaders observed collected via the `ReferenceQueue` |

**`live` is the leak detector and the reason it must be weak-reference-based**: after a
drop, `created − reclaimed` staying above zero *is* the retention. `jvm_classes_unloaded_classes_total`
moving is the JVM-side corroboration. At Phase 1 the registry holds exactly one
entry — the application classloader under `builtin` — so the readings are
`live{builtin}=1, created_total{builtin}=1, reclaimed_total{builtin}=0`. That is
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
  (`prometheus/alerts.yml:338`):
  `delta(jvm_memory_used_bytes{area="nonheap",id="Metaspace"}[24h]) > 52428800`,
  `for: 1h`, `severity: warning`, `audience: internal`. **This rule is safe to ship
  without a baseline precisely because the current system has no mechanism to grow
  metaspace** — classes are loaded at boot and never after, so the expected value is
  ~0 and any sustained 50 MiB/day is already anomalous. Comment it as a first guess, in
  the house style of `prometheus/alerts.yml:414-416`.
- **Deferred to step 6 — `PluginClassLoadersRetained`**:
  `sum(plugin_classloaders_live) > 2` `for: 2h`. It would be vacuous until two versions
  can coexist (the value is identically 1 until step 5), and a rule that can only be
  false teaches nobody anything. Expression recorded here so step 6 does not invent one
  under pressure.

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
the same `IllegalArgumentException` message as
`GameMessageTypesResolver.java:52-54` / `:98-100`
(`"GameMessageTypes not yet implemented for product code: 066. Please create a
GameMessageTypes implementation for this product."`). Pin with a test. This string is
what an operator greps when a new brand's group fails to start.

**AD-21. `StrategyController`'s response contract is preserved exactly.** Same path,
same DTO, same `id` strings, and **the same order** — the endpoint lists the registry
but sorts built-ins into `StrategyId` declaration order first, then any non-built-in
key alphabetically, so the UI picker does not reshuffle on deploy. Pin the order with a
test; `StrategyId.values()` order is a de-facto UI contract that no one wrote down.

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
   (insertion-ordered, so AD-21's ordering has a stable base), `create(String)`,
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
   `BotFactory.java:160, 168, 181` become instance calls. Nothing else changes —
   `switch (game.getGameType())` at `:156` stays (it selects a *bot class*, not a
   product implementation).
5. Tests: port `GameMessageTypesResolverTest` to the registry; add
   `MessageTypesCoverageTest` (AD-19) and an error-text test (AD-20).
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
`plugin_classloaders_live = 1`, `plugin_classloaders_created_total = 1`,
`plugin_classloaders_reclaimed_total = 0`. A missing series (rather than a zero) means
the eager registration did not run.

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
an **empty result vector** (`"result":[]`). A firing rule on the first day means the
threshold is wrong, not that there is a leak.

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
2**; anything lower means a bean lost its annotation key.

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
