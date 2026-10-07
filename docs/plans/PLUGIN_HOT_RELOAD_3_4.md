# Plugin Hot Reload — Steps 3 & 4

Citation convention: `…` inside a path stands for `src/main/java/com/vingame/bot` (or
`src/test/java/com/vingame/bot` under `src/test`). Line numbers are for `main` @ `f6f5280`
unless marked **[av]**, which means `feature/aviator-bot` @ `30b426a`. Anything not
measured is marked **UNVERIFIED**.

## Goal

The end state is unchanged from `/Users/gleb/IdeaProjects/Bot/docs/plans/PLUGIN_HOT_RELOAD.md`:
the app is never restarted for a product-implementation change, and no deployment stops a
bot group. Groups move from plugin version N to N+1 as their bots recycle. Steps 1-2
shipped string-keyed registries inside one jar. This plan covers the next two steps, and
both of them restart the app:

- **Step 3** moves the product implementations (betting/slot strategies, per-brand
  message-type providers) into separately built plugin jars. Engine and app code can no
  longer compile against those jars. At runtime the jars still come from the app
  classpath, so behaviour is identical.
- **Step 4** loads those jars in a child classloader created for one bundle version, with
  one version live. The jars arrive through a read-only bind mount. The plan also adds a
  `-XX:MaxMetaspaceSize` cap, and the Phase-1 meters start reporting the real loader.

Steps 5-7 (two coexisting versions, drain + classloader release, reload endpoint +
forced cutover) are out of scope. This plan names them only where a decision here could
foreclose them.

---

## Findings — Current State

### Branch state (measured 2026-10-06)

| Branch | vs `main` | On origin | Notes |
|---|---|---|---|
| `feature/gateway-request-budget` (checkout) | identical (0/0) | yes | its Phase 6 is scheduled 2026-10-09, so `main` will move |
| `feature/bot-provisioning` | +17, unmerged | yes | ancestor of both below |
| `feature/cashout-bot` | +37, unmerged | **no** | contains provisioning |
| `feature/aviator-bot` | +61, unmerged | **no** | contains cashout + provisioning, so it is the **union**, and it is what Bot-1 staging runs (deployed 2026-10-06) |

The three feature branches form a linear stack, `main → provisioning → cashout → aviator`.
They cannot land "in either order" without a rebase. What this plan has to tolerate is
the stack landing on `main` before or after the step-3/4 branch. D-18 covers this.

What the stack adds that this plan touches **[av]**: `GameType.CASHOUT`, `GameType.CRASH`
(`/Users/gleb/IdeaProjects/Bot/bot-api/…/domain/game/model/GameType.java`; `main` already
has `UP_DOWN`, which is unimplemented and throws in `BotFactory`); the contracts
`CashoutMessageTypes`, `CrashMessageTypes`, `cashout/*`, `crash/*`, `request/CashoutRequest`
and `request/CrashRequest`, **all in `bot-api`**; the providers
`g4/win79/cashout/*` and `g4/win79/crash/*` in `bot-messages`; two more
`MessageTypesRegistry` tables (`cashout(String)`, `crash(String)`, `MessageTypesRegistry.java:100-119`
**[av]**); and `CashoutBot`, `CrashBot`, `core/cashout/*`, `core/crash/*` and
`core/support/ReconnectLadder` in `bot-engine`. The new code already follows the pattern
this plan needs: contracts live in `bot-api`, implementations in `bot-messages`.

### Inherited constraints (digest of PLUGIN_HOT_RELOAD AD-1..23, A1-A11)

- **AD-5:** `pluginVersion` is carried by exactly two metric families,
  `plugin_classloaders_*` and `bots_by_plugin_version`. It is never a tag on `bot_*`. At
  most 2 concurrent values.
- **AD-12 / A10:** `StrategyId` / `SlotStrategyId` are display catalogue only. The
  registry decides the *set*, `StrategyId` order decides the *order*, and the enum
  supplies the *values*. `GET /api/v1/strategy/` md5 is `a72c40f56057cda5434b273ea36315ea`
  (A11; `StrategyId` has had no code change since, only a javadoc commit `d3b94bc`).
- **AD-15 / A5:** key validation runs post-merge on create and PATCH only, never on start.
- **AD-20 / A6:** the three registry-miss strings are frozen byte-for-byte.
- **A4 / A9:** registry iteration order is scan order and is never a display order.
  Operator-facing key lists are sorted when rendered.
- **A8:** keys have no version dimension, and a duplicate key fails context refresh. This
  plan resolves it in D-9/D-10.
- **A1:** count the test baseline at the branch point, in a clean worktree.

### The engine→plugin boundary, measured on the union [av]

Engine and app code (`bot-engine`, `bot-app`, `bot-api` main) reference concrete
`bot-strategies` / `bot-messages` classes in only these places. Found by a whole-word grep
over every class simple name in both modules, excluding comments. The grep was needed
because an import scan missed site 3, which is an inline FQN:

| # | Site | Reference |
|---|---|---|
| 1 | `/Users/gleb/IdeaProjects/Bot/bot-engine/…/domain/bot/core/BettingMiniGameBot.java:217` | `new RandomBehaviorStrategy()`, a test-seam fallback when `strategyFactory == null` |
| 2 | `/Users/gleb/IdeaProjects/Bot/bot-engine/…/domain/bot/core/SlotMachineBot.java:161` | `new FixedBetStrategy()`, the same seam |
| 3 | `/Users/gleb/IdeaProjects/Bot/bot-engine/…/domain/bot/core/TaiXiuGameBot.java:223` | `instanceof com.vingame.bot.domain.bot.message.taixiu.TaiXiuEndGameMessage` (refund credit) |
| 4 | the three registries | `BettingStrategyFactory`, `SlotStrategyFactory` (used by `BotFactory`, `BotGroupConfigValidationService:40-46`, `StrategyCatalog:106-108`, both bots) and `MessageTypesRegistry` (`BotFactory:74-87`) |
| 5 | product-neutral request frames | `GameRequest`, `GameRequestFactory` (`BettingMiniGameBot.java:303`), `Request` (`:306`), `TaiXiuRequest` (`TaiXiuGameBot.java:187`), `SlotRequest` (`SlotMachineBot.java:145`) |
| 6 | slot messages | `SlotSpinResultMessage`, `SlotSubscribeResponse` (`SlotMachineBot.java:10-11`, `.onMessage(X.class…)`) |

The reverse direction matters as well. RIK providers implement `GameRequestFactory`, and
`RikGameMessageTypes` imports `Request`. Win79 cashout/crash classes import
`CmdAwareMessage` and `SubscribeToLobbyMessage`. So the request frames in row 5, and
everything they reach (`Bet`, `BetEntryInfo`, `Chat`, `AutoBet`, `Fetch*`, `TaiXiuBet`,
`SlotSpin`, `SlotSubscribe`, `SubscribeToLobbyMessage`, `CmdAwareMessage`), are used by
**both** layers. They have to live below both.

Plugin classes use no root Spring beans. Strategies have no constructor injection, and
the providers are stateless class-literal tables. A **parentless** child context is
therefore possible.

**Split packages.** `domain.bot.message`, `.message.request`, `.message.slot`,
`domain.bot.strategy` and `.strategy.slot` already exist in both `bot-api` and a plugin
module. Under one loader this is harmless. Across loaders it breaks package-private
access (a runtime package is the pair (loader, name)), and a child-context component scan
of a shared package also sees the parent's classes. A heuristic grep of the `bot-api`
classes in those packages found only public interface members, plus one package-private
static, `StrategyAssignment.apportion`, which plugins do not call. **UNVERIFIED as
exhaustive.** The proof is L-1 (D-14).

### Registries

- `/Users/gleb/IdeaProjects/Bot/bot-strategies/…/domain/bot/strategy/slot/SlotStrategyFactory.java:113-115`
  returns `Collections.unmodifiableSet(registry.keySet())`, which is a **live view**. Its
  betting twin was fixed to a `LinkedHashSet` snapshot
  (`BettingStrategyFactory.java:187-189`, whose javadoc `:159-186` explains why
  `Set.copyOf` is wrong: iteration order is unspecified and salted per JVM run).
- All three registries throw `IllegalStateException` on a duplicate key while the context
  refreshes (`BettingStrategyFactory.java:121-126`, `SlotStrategyFactory.java:77-82`,
  `MessageTypesRegistry.java:254-261`). `BettingStrategyFactory` / `SlotStrategyFactory`
  log a WARN and skip a bean with no annotation. `MessageTypesRegistry` hard-fails it.
- `MessageTypesRegistry` already keeps its wiring in one immutable `Tables` record
  (`:95-100`). Its javadoc expects step 5 to make that a `volatile` field (D-9
  supersedes this).

### Runtime / observability seams from Phase 1

- `PluginVersionResolver` is implemented only by
  `/Users/gleb/IdeaProjects/Bot/bot-app/…/infrastructure/plugin/BuiltinPluginVersionResolver.java:18-23`,
  which returns `"builtin"`.
- `/Users/gleb/IdeaProjects/Bot/bot-app/…/infrastructure/observability/PluginClassLoaderMetrics.java:147-163`
  registers `getClass().getClassLoader()`, i.e. the **app** loader, and logs
  `plugin runtime: version=…, classloader=…` once. `register(...)` at `:187` is public for
  step 4, and its javadoc forbids registering the same loader twice.
- `BotConfiguration.pluginVersion` (`/Users/gleb/IdeaProjects/Bot/bot-api/…/config/bot/BotConfiguration.java:141`)
  is never set. `resolvePluginVersion()` (`:190-194`) falls back to `builtin`. MDC and
  `bots_by_plugin_version` both read it (`Bot.java:343, 410`, `BotGroupRuntime.java:236`).

### Jackson and other shared state (classloader-retention inputs)

- Every bot type builds a **fresh** `ObjectMapper` and registers its provider's subtypes
  on it alone: `BettingMiniGameBot.java:1074-1076`, `SlotMachineBot.java:456-458`,
  `CashoutBot.java:527-529` **[av]**, `CrashBot.java:621-623` **[av]**.
- **A fresh mapper is not enough to unload a version** (spike 3a). Every
  `new ObjectMapper()` shares the static `TypeFactory.defaultInstance()` cache, which is a
  strong 200-entry LRU, so plugin types stay pinned after the bot's mapper is gone. A
  private cache per mapper (`withCache(new LRUMap<>(16,200))`) clears the pin (spike 3b).
  The fresh-mapper fact protects subtype *coexistence* only.
- The same four classes also hand the **static** ws-parser `ObjectMapperProvider.getDefault()`
  to the OutputPrinter pipeline (`BettingMiniGameBot.java:1153`, `SlotMachineBot.java:503`,
  `CashoutBot.java:583` **[av]**, `CrashBot.java:686` **[av]**). It is **safe today**:
  nothing registers subtypes on it, and `OutputPrinter` has no typed matchers (spike
  scenario 8). Registering plugin subtypes on any static mapper is a **permanent,
  unrecoverable** pin (spike 3c). Serializing a plugin type through one pins the loader
  until its caches are flushed by reflection (spike 3d).
- Other process-lifetime mappers that must never see a plugin type:
  - ws-parser's `ObjectMapperProvider` `CACHE` (`strict()` / `lenient()`);
  - ws-parser's static `ActionResponseMessage.serialize/deserialize`;
  - ws-parser's `AuthClient.MAPPER`;
  - `/Users/gleb/IdeaProjects/Bot/bot-engine/…/infrastructure/client/ApiGatewayClient.java:68`.
    It serializes `loginRequestFactory.apply(ctx)` at `:654`, so per-brand
    `LoginRequest`s must stay engine-side;
  - `GameMsClient.java:22`.
- **Threads (spike 7b/7c).**
  - On JDK 21, a **platform** thread constructed while plugin frames are on the stack
    captures `inheritedAccessControlContext` and pins the plugin loader for its whole life.
    This happens regardless of TCCL.
  - The shared Netty group (`/Users/gleb/IdeaProjects/Bot/bot-app/…/config/NettyEventLoopConfig.java:37-47`,
    4 threads by default) starts its threads **lazily**, so the first bot started after a
    bundle load can create them under a plugin frame.
  - `ClientFactory.java:84-98` sets the shared group on every client, and only WARNs on
    null. A null group would reach ws-parser's private-group fallback at
    `VingameWebSocketClient.java:400`, which has the same pin.
  - Engine executors are all virtual (`Thread.ofVirtual()` factories) except
    `GameMsClient.java:55`, which creates a short-lived platform `new Thread` per deposit.
- **Drain lifetime (spike scenario 8):** a version's loader lives until its last bot's
  `VingameWebSocketClient` is closed, because `scenariosByTag` holds per-bot mappers and
  callbacks.

### Packaging and deploy

- `/Users/gleb/IdeaProjects/Bot/Dockerfile:10` copies only `bot-app/target/Bot-1.0.jar`.
  `:16-19` runs `java -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -jar Bot.jar`.
  There is no metaspace cap.
- `/Users/gleb/IdeaProjects/Bot/docker-compose.yml:123-130` holds the bot-manager
  volumes: `./logs`, and `./logging/log4j2.properties:…:ro`, which is the read-only
  bind-mount precedent. The container runs as `${HOST_UID}:${HOST_GID}`, not as the
  image's `botmanager` user.
- `bot-app` depends on `bot-strategies` / `bot-messages` at compile scope
  (`bot-app/pom.xml:23,28`). So does `bot-engine` (`bot-engine/pom.xml:35,40`). The fat
  jar bundles both.
- `deploy.sh` is uncommitted and owned by the user. It runs `mkdir -p logs prometheus …`
  and then `docker compose up -d`. The releaser ships `bot.tar` to
  `Bot-1:/home/sgame/bot-java` over sftp.
- There is no failsafe execution anywhere. Failsafe appears only in the parent's
  pluginManagement, so `*IT` classes **never run**, and `BotGroupConfigValidationIT` is
  the known instance.

### Metaspace baseline (the input to the cap)

The 7-day reading (P1-10) was **never delivered**. Each deploy reset it. What exists:

| Reading | Value | Source |
|---|---|---|
| pre-Phase-1 JVM, 6.07 d uptime | 87,460,032 B; `delta[6d]` = **+920,090 B** (~0.15 MB/day) | `docs/reviews/PLUGIN_HOT_RELOAD/release.md:195-198, 439-446` |
| Phase 2 start, 7 min | 84,834,512 B, 17,105 classes | `release-phase2.md:483-486` |
| pre-2d JVM, 1 h 44 m | 87,749,568 B (83.7 MiB), 17,165 classes | `release-phase2d.md:519` |

Steady state is about 84 MiB at roughly 17k classes, with no measurable drift. That build
predates 119, Aviator, Cashout and the gateway budget, so today's figure is higher by an
**UNVERIFIED** amount.

### Spike result

`/Users/gleb/IdeaProjects/Bot/docs/reviews/PLUGIN_HOT_RELOAD/spike-classloader-gc.md`
returned **GO with rules**. A child `URLClassLoader` holding the real plugin classes is
collected. Over 1,000 cycles there were 40 classes unloaded per cycle, 0 loaders retained,
and metaspace stayed flat. Each version that leaks costs about 0.14–0.45 MB of metaspace.

Its eight hard rules are folded into D-9, D-13, D-14 and D-16. See "Spike rules → where
they land".

Caveats of the spike:
- the parent was a flat classpath, not Boot's `LaunchedClassLoader`;
- there were no live sockets;
- only one version was loaded at a time;
- it ran on macOS OpenJDK 21.0.2, not Linux Temurin.

L-15 and V4-11 close what can be closed before step 6.

---

## Class placement — which side

**Rule (D-2):** a class is on the engine side if engine or app code names it, or if it is
a contract or annotation that a plugin implements or uses. Engine-side classes live in
`bot-api` when plugins also need them, and in `bot-engine` otherwise. A class is on the
plugin side if it is reached **only** through a registry lookup.

| Class / package | Side | Why |
|---|---|---|
| `bot-api` today (contracts, `Has*`, `StrategyId`, `*Impl` annotations, `cashout/*`, `crash/*`, `CashoutRequest`, `CrashRequest`) | engine (`bot-api`) | already contracts |
| `request/{GameRequest, GameRequestFactory, CmdAwareMessage, Request, Bet, BetEntryInfo, Chat, AutoBet, SubscribeToLobbyMessage, FetchAllPlayers, FetchBetHistory, FetchSessionDetail, TaiXiuRequest, TaiXiuBet, SlotRequest, SlotSpin, SlotSubscribe}` | **moves → `bot-api`** | named by the engine (row 5) and used by plugins |
| `slot/{SlotSpinResultMessage, SlotSubscribeResponse}` | **moves → `bot-api`** | named by `SlotMachineBot` (row 6) |
| `BettingStrategyFactory`, `SlotStrategyFactory`, `MessageTypesRegistry` | **moves → `bot-engine`** | registries are engine (row 4) |
| new `HasRefund` capability | **new in `bot-api`** | replaces row 3 |
| `CashoutBot`, `CrashBot`, `core/cashout/*`, `core/crash/*` (incl. `CrashStakes`, `CashoutBehavior`) | engine | game-type logic. There is no strategy registry for it, so it is **not reloadable** (Open Items) |
| All 11 strategy beans + `WeightedOptionPicker`, `martingale/{AffinityOptionPicker, MartingaleStrategySupport, RiskProfile, *Strategy}` | **plugin** (`bot-strategies`) | reached only via `create(key)` |
| `g2/bom`, `g2/b52`, `g3/rik`, `g3/tip`, `g4/nohu`, `g4/win79` (incl. `cashout/`, `crash/`) | **plugin** (`bot-messages`) | per-brand, reached via registry. `B52GameMessageTypes` is unregistered but kept (never delete game impls) |
| `request/{RikStockRequest, RikStockBet, RikStockCommit, ZicZacRequest, ZicZacBet}` | **plugin** | per-brand, reached via `GameRequestFactory` from RIK providers |
| `taixiu/*` (3 providers + `TaiXiu{Start,End,Subscribe}Message`) | **plugin** | reached via `TaiXiuMessageTypes` once row 3 is fixed |
| `slot/SlotMessageTypesImpl` | **plugin** | product-neutral, but a registry is fed by exactly one source (D-8). It returns `bot-api` classes |

**New `GameType`s stay engine and app work.** A new `GameType` needs a bot class, a
`BotFactory` switch arm and usually a new contract interface plus registry table, as
CASHOUT and CRASH did. Only strategies and per-brand providers for an existing `GameType`
are reloadable.

---

## Per-aspect readiness

| Aspect | Readiness | Notes |
|---|---|---|
| Contracts below both layers | partial | 19 product-neutral classes must move to `bot-api` (3a) |
| Engine free of concrete plugin refs | partial | 3 sites (3a) |
| Separate plugin artifacts | ready | modules exist. Only scopes and the manifest change (3b) |
| Compile-time boundary enforcement | ready | Maven scopes + enforcer (3b) |
| Registries fed from a bundle, not the root context | partial | they inject `List<…>` from root today (4a) |
| Child loader + parentless child context | blocked → 4b | new code. Split packages require an origin-filtered scan |
| Version identity | partial | Maven `version` is `1.0` everywhere and useless. Needs a manifest attribute (3b) |
| Docker delivery | partial | needs a mount, an image-baked fallback, and a `deploy.sh` line (4c) |
| Metaspace cap | partial | sized from stale data plus a mandatory pre-deploy reading (4c) |
| Phase-1 meters on the real loader | ready | `register(version, loader)` is already public |
| Classloader *release* | out of scope | step 6. 4b ships the rehearsal test only |
| Spike verdict | ready | GO with rules. The rules land in 4a (TypeFactory ownership), 4b (thread rules, guards, ITs) and 4c (Boot-parent / Linux check) |
| Per-bundle Jackson type cache | partial | 4 bot classes build mappers with the shared default `TypeFactory` (4a) |
| Netty threads started before any plugin loads | blocked → 4b | the group starts lazily today |

---

## Architecture Decisions

**D-1. Steps 3 and 4 change no behaviour.** Same strategies, messages, bets, HTTP
responses and `bot_*` series. The only permitted observable differences:
- (a) fields appended to the end of the `plugin runtime:` boot line;
- (b) new one-shot lines from the bundle loader;
- (c) from 4c, the `pluginVersion` label value changes from `builtin` to the bundle
  version;
- (d) from 4c, `jvm_memory_max_bytes{id="Metaspace"}` is finite;
- (e) the new `MetaspaceNearCap` rule;
- (f) from 4b, the Netty event-loop threads exist from boot rather than from the first
  client.

**D-2. Side assignment follows the rule in "Class placement"**, not a file list. A class
that lands later (from the stack or from new brand work) falls on the right side by the
rule. 3b turns the rule into a compiler error.

**D-3. Module names and FQNs stay.** The plugin bundle is `{bot-strategies, bot-messages}`.
Every move in 3a keeps its FQN and is its own commit containing **only `git mv`**. That
gives zero import churn, and 100% rename similarity, so `git merge` follows the file
across the stack's later edits (the stack modifies `MessageTypesRegistry` and adds
providers). The cost is split packages, which D-13 and L-1/L-3 handle explicitly.
**Rejected:** re-packaging the registries. It costs import churn in about 20 files and
does not remove the need for an origin-filtered scan (`bot-api` and plugins share
packages anyway).

**D-4. The engine has no fallback to a concrete plugin class.** Sites 1-2 become
`IllegalStateException("BettingStrategyFactory not wired — BotFactory always sets it")`,
and the slot twin likewise. Production always wires the factory
(`BotFactory.java:177, 185, 196` **[av]**). The 20 engine/app test files that construct
a bot without wiring a factory (measured **[av]**) get one from a shared test helper.
**Rejected:** a reflective `Class.forName` fallback, which couples by string, and an
engine-side copy of `RandomBehaviorStrategy`, which duplicates logic.

**D-5. The engine tests a capability, not a class.** Add
`bot-api HasRefund { long refundFor(String userName); }`, following the `Has*` /
`winningsFor(userName)` convention. `TaiXiuEndGameMessage` implements it by returning `gR`
(its own javadoc at `taixiu/TaiXiuEndGameMessage.java:49-51` confirms the values are
per-recipient). Site 3 becomes `instanceof HasRefund r ? r.refundFor(userName) : 0`.
Behaviour is identical: today a non-TX end message yields refund 0 too.

**D-6. The boundary is enforced by the build.**
- `bot-engine`: the plugin modules go to `test` scope.
- `bot-app`: `runtime` scope in 3b. That keeps them in `BOOT-INF/lib` and on the test
  classpath. 4c changes it to `test`.
- `maven-enforcer-plugin` `bannedDependencies` in `bot-api`, `bot-engine` and `bot-app`
  bans `com.mercury:bot-strategies` / `com.mercury:bot-messages` at `compile` and
  `provided`.
- The plugin modules declare `bot-api`, `spring-context`, Jackson and slf4j as
  `provided`, so a plugin jar never carries or pulls the contract layer.

**D-7. Bundle version.** Each plugin jar's manifest carries
`Bot-Plugin-Version: ${bot.plugin.version}`, where the root pom defines
`bot.plugin.version = ${maven.build.timestamp}` with format `yyyyMMdd.HHmmss`. That is one
value per reactor session, so both jars agree when they are built together. Maven's build
timestamp is UTC (confirmed at 3b: a build at 15:37:57 +04 stamped `20261006.113757`). A bundle is valid only
if every jar in it carries the same value, and that value is the bundle's version. In
classpath mode the version stays `builtin`. The format sorts lexicographically, so "newer"
is a string comparison.

**D-8. One bundle, one loader, one version, one source.** Bundles are never merged and
keys are never layered from two places. With every provider and strategy in the bundle,
there are no engine "built-ins" left for a plugin to collide with.

**D-9 (resolves A8's first half). Registries are per-bundle objects. Keys get no version
dimension.**
- `BettingStrategyFactory`, `SlotStrategyFactory` and `MessageTypesRegistry` stop being
  `@Component`s. They become immutable objects built from one `PluginBundle`, together, as
  one `PluginRegistries` record.
- **The bundle also owns one Jackson `TypeFactory`**:
  `TypeFactory.defaultInstance().withCache(new LRUMap<>(16, 200))`, exposed as
  `PluginRegistries.typeFactory()`. `BotFactory` hands it to each bot, and every per-bot
  mapper calls `setTypeFactory(it)` (spike rule 2). It is used in classpath mode too, so
  the behaviour is identical: same resolution, private cache. `withClassLoader()` is
  **not** a substitute, because it shares the cache.
- `PluginRuntime` is the **only** root-held reference. Its `current()` returns that record.
- Root consumers (`BotFactory`, `BotGroupConfigValidationService`, `StrategyCatalog`) call
  `pluginRuntime.current()` per operation and do not cache registries in fields.
- A bot receives its bundle's factories from `BotFactory` and keeps them for life, which
  is exactly the drain semantics step 5 needs: a bot built on N keeps creating N
  strategies until it is recycled through `BotFactory`.
- Step 5 adds version selection to `PluginRuntime` and touches no consumer.
- `MessageTypesRegistry`'s "step 5 makes `tables` volatile" javadoc is superseded: the
  swap point is `PluginRuntime`, and registries never mutate. Dev updates that comment.

**D-10 (resolves A8's second half). Collision policy, one for all three registries:**
- a bundle is accepted or rejected **as a whole**;
- any registry misconfiguration rejects it. That covers a duplicate key, a missing
  `@MessageTypesImpl`, a gameType/contract mismatch, and a product-keyed provider with no
  products. The strategy factories' WARN-and-skip for a bean with no annotation **stays a
  WARN** (unchanged behaviour);
- **classpath mode:** rejection is a context-refresh failure, as today. That is what
  keeps a broken build from producing a startable artifact;
- **isolated mode:** a rejected candidate is logged at ERROR and the next candidate is
  tried (D-11). Boot fails only when no candidate is valid.

A bad mounted bundle therefore never takes down the other brands, and this holds whenever
the image's own bundle is valid.

**D-11. Delivery layout and selection.**
- Layout is `<dir>/<anything>/*.jar`. Each subdirectory is one candidate bundle.
  Subdirectories whose names start with `_` or `.` are ignored, which is the operator's
  rollback/disable switch.
- Candidates, in order:
  1. every bundle in `bot.plugins.dir` (default `/app/plugins`, compose
     `./plugins-dist:/app/plugins:ro`), version descending;
  2. every bundle in `bot.plugins.builtin-dir` (default `/app/plugins-builtin`, baked
     into the image by the Dockerfile), version descending.
- The first valid candidate wins.
- The app never writes to either directory.
- Versioned subdirectories keep two versions side by side for step 5, because both carry
  `bot-messages-1.0.jar`.
- Falling back to the baked bundle logs one WARN naming the mounted directory and the
  version actually running.

**D-12. Mode switch.**
- `bot.plugins.mode` is `classpath | isolated`. The code / `application.properties`
  default is `classpath`, which is what every test context uses.
- Compose sets `BOT_PLUGINS_MODE=${BOT_PLUGINS_MODE:-isolated}` (relaxed binding, the
  `BOT_RECOVERY_ENABLED` precedent).
- Classpath mode that discovers **zero** plugin beans fails startup with
  `"no plugin beans on the classpath — set bot.plugins.mode=isolated (see CLAUDE.md)"`.
  This covers running the 4c fat jar outside compose.
- **Do not** set `isolated` in `application.properties`, or every `@SpringBootTest` in
  `bot-app` breaks.

**D-13. Loader and child context.**
- Loader: `new URLClassLoader("plugin-" + version, jarUrls, PluginBundle.class.getClassLoader())`,
  standard parent-first delegation, so contracts, Spring, Jackson, ws-parser, slf4j and
  log4j2 resolve to the parent's `Class` objects.
- Child context: an `AnnotationConfigApplicationContext` with **no parent** and
  `setClassLoader(loader)`.
- Scanning: base package `com.vingame.bot`, matching `Starter`'s scan. That avoids the
  known trap where a guard scanning `…domain.bot.message` disagrees with `Starter`. The
  scanner's resource loader is a resource-only `URLClassLoader(jarUrls, null)`, so it
  **can only see bundle-jar entries**. Without that restriction, a scan of a split package
  would also instantiate parent `@Component`s.
- The loader uses plain `file:` jar URLs from the on-disk directory, never `jar:nested:`.
  Boot's nested-jar handler caches `JarFile`s by URL.
- TCCL is set to the plugin loader only for the duration of `refresh()`, and restored in
  `finally` on the same thread. No engine or library call runs while it is set (spike
  rule 6).
- No `registerShutdownHook()`. `PluginRuntime` closes the context on `@PreDestroy`.
- **`PluginBundle.close()` order (spike rule 3), fixed now so step 6 inherits it:**
  1. unpublish the bundle's registries;
  2. `close()` the child context. Its `resetCommonCaches()` clears Spring's soft
     annotation caches, and merely dropping an unclosed context does not;
  3. `typeFactory.clearCache()` plus `TypeFactory.defaultInstance().clearCache()`;
  4. `URLClassLoader.close()`.
- At step 4, `close()` runs only for rejected candidates and at JVM shutdown. Step 6
  adds removing the bundle from `PluginRuntime`, and `registry.remove()` of any meter
  tied to the version (none exist while L-9 holds).
- **No bundle loads until the shared Netty group is fully started.**
  `NettyEventLoopConfig` submits a no-op to every `EventExecutor` and waits for it.
  `PluginRuntimeConfiguration` injects the group, so the ordering is a bean dependency,
  not a convention (spike rule 5).

**D-14. Loader rules (L-rules).** Each rule has a test. "S*n*" cites the spike's hard
rule / scenario. ITs live in `bot-plugin-dist` (no plugin classes on its classpath) unless
another module is named.

| Rule | Test |
|---|---|
| L-1 Every contract type a plugin references resolves to the parent's `Class`. Every plugin class's defining loader is the plugin loader, i.e. not shadowed by a copy on the parent classpath. Every class in the jars loads and links. Every loader URL is `file:` | `PluginTypeIdentityIT` |
| L-2 Every *file* entry in a plugin jar is a `com/vingame/bot/domain/bot/{message,strategy}/**` class, `META-INF/MANIFEST.MF`, or that module's own Maven descriptor `META-INF/maven/com.mercury/<artifactId>/pom.{xml,properties}` (maven-jar-plugin adds it by default; directory entries are ignored). Any other `META-INF/maven/**` path is the fingerprint of shaded code and fails. No shaded third-party code | `PluginJarContentsIT` |
| L-3 Every child bean definition's class is defined by the plugin loader. No engine `@Component` exists in the child | `PluginContextOriginIT` |
| L-4 The child context has no parent and no shutdown hook. The loading thread's TCCL equals its prior value after load, both after success and after a rejected candidate (S6) | `PluginContextOriginIT` |
| L-5 A plugin logger binds to the app's `LoggerContext`: a level set on `com.vingame.bot` applies, and `ScopedDebugFilter` admits a scoped plugin line. The spike (S6) proved logging does not *pin*. It did not prove context *identity*, which this test does | `PluginLoggingContextIT` |
| L-6 Plugin code defines no `ThreadLocal` / `InheritableThreadLocal`, and puts only `String` values into MDC (S7, scenario 4a) | source guard, each plugin module |
| L-7 **No static or process-lifetime mapper ever touches a plugin type** (S1, scenarios 3c/3d). Forbidden: `registerSubtypes` on `ObjectMapperProvider.getDefault()` or its `strict()`/`lenient()` `CACHE` mappers; a typed matcher on the OutputPrinter (`getDefault()`) context; `ActionResponseMessage.serialize/deserialize` with a plugin type; plugin objects reaching `ApiGatewayClient`/`GameMsClient`'s static mappers or ws-parser `AuthClient` (so `LoginRequest`s stay engine-side) | source guard (`bot-engine` tests) over `bot-engine`/`bot-app` main |
| L-8 Every per-bot mapper (`BettingMiniGameBot`, `SlotMachineBot`, `TaiXiuGameBot` via inheritance, `CashoutBot`, `CrashBot` **[av]**) uses the bundle's `TypeFactory`, never the shared default. `PluginBundle.close()` clears both caches (S2, scenarios 3a/3b) | `PerBotMapperTypeFactoryTest` (`bot-engine`), `PluginBundleCloseTest` (`bot-engine`) |
| L-9 Plugins never touch `MeterRegistry`: the plugin modules have no `micrometer-core` (or `micrometer-registry-*`) dependency at any scope. `micrometer-observation` / `-commons` arrive provided-transitively through `spring-context` 6.x (D-6), carry no `MeterRegistry`, and are allowed. Engine-side review rule: no gauge whose value object or value function is plugin-defined, including a lambda written in plugin code (S4, scenarios 5a/5b) | `dependency:tree` check in the local gate |
| L-10 Among root beans, only `PluginRuntime` holds a `PluginBundle` / `PluginRegistries` / registry / `TypeFactory`-of-a-bundle reference (S3) | `RootContextHoldsNoPluginRefsTest` (`bot-app`): reflect over every `com.vingame.bot` bean's declared fields |
| L-11 **Thread rules (S5).** Plugin code creates no thread or executor (`Executors`, `new Thread`, `Thread.of*`; virtual-only would be allowed, but no plugin needs one). Every `EventExecutor` of the shared group has a live thread before `PluginRuntime` is constructed. In isolated mode the shared group is mandatory: `ClientFactory`'s null branch (`:97-98`) becomes an `IllegalStateException`, so `VingameWebSocketClient.java:400`'s private group is unreachable. `GameMsClient.java:55` becomes a virtual thread | source guard, each plugin module; `NettyPrestartTest` (`bot-app`: live `multiThreadIoEventLoopGroup-*` thread count = `websocket.eventloop.threads` when `PluginRuntime` is created); `ClientFactoryRequiresGroupTest` (`bot-engine`) |
| L-12 **Platform-thread census.** After L-13's exercise, no live platform thread's `inheritedAccessControlContext` contains a `ProtectionDomain` of the plugin loader, and no platform thread's TCCL is the plugin loader. A **negative control** proves the test can fail: start a *cold* `MultiThreadIoEventLoopGroup` from a plugin-frame callback, expect a detection, then shut it down. Failsafe `argLine` needs `--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.security=ALL-UNNAMED` | `PlatformThreadPinIT` |
| L-13 The two modes are equivalent: isolated registries expose the same strategy key sets (9 / 2) and the same `MessageTypesRegistry` product sets as classpath mode. One recorded frame per provider deserializes to the same type through a per-bot mapper built as the bots build it (bundle `TypeFactory`) | `IsolatedEquivalenceIT` |
| L-14 **Reclaim rehearsal.** Load the shipped bundle, run L-13 plus 9 strategies × `decide`/`onRoundEnd`, drop every reference, `close()` in D-13's order, then up to 20 × (`System.gc()` + 150 ms). The loader's `WeakReference` must clear. **Negative control:** the same flow plus `getDefault().registerSubtypes(...)` must stay pinned (spike 3c). The control runs **in a forked JVM**, because that pin is permanent | `PluginBundleReclaimIT` |
| L-15 **Boot parent + Linux.** L-14's flow, run with the parent as Spring Boot's real `LaunchedClassLoader` over the 4c fat jar, inside `eclipse-temurin:21-jre`. Covers the spike's flat-classpath and macOS caveats | `BootParentReclaimMain` (in `bot-plugin-dist` test sources), run by the 4c local gate (Plan 4c step 7). Best effort; see there |

L-12, L-14 and L-15 are **strict**. Step 4 never releases a loader, so these tests are the
proof that step 4 adds no new pin. If one fails for a reason the spike does not explain,
report it. Dev must not weaken it, and `@Disabled` is allowed only with the user's sign-off
recorded in the handoff.

**D-15. Metrics and version plumbing.**
- `PluginClassLoaderMetrics` registers `bundle.classLoader()` under `bundle.version()`,
  **exactly once**, for the accepted bundle only. Rejected loaders are closed, not
  registered.
- Classpath mode reproduces today's readings exactly (app loader, `builtin`, 1/1/0).
- The boot line keeps its prefix (`plugin runtime: version=`; P1-2 greps it) and appends
  `, source=<classpath|dir>, jars=[<name> sha256=<first 12 hex>, …]`.
- `BuiltinPluginVersionResolver` is deleted. The `PluginVersionResolver` bean returns
  `bundle.version()`.
- `BotGroupBehaviorService.createSingleBot` sets `.pluginVersion(resolver.currentVersion())`.
  This is the "step 4 starts setting it" that AD-10 deferred, and it keeps
  `bots_by_plugin_version` consistent with `plugin_classloaders_live`.

**D-16. Metaspace cap: `-XX:MaxMetaspaceSize=320m`.**
- It goes in compose as
  `JAVA_TOOL_OPTIONS=-XX:MaxMetaspaceSize=${BOT_METASPACE_MAX:-320m}`, not in the
  Dockerfile. That makes it box-tunable with a `docker compose up -d`, and the JVM prints
  `Picked up JAVA_TOOL_OPTIONS: …` once at start, which gives the releaser a verifiable
  line.
- **Formula:** cap = max(192 MiB, 3 × steady-state metaspace, rounded up to a multiple
  of 64 MiB).
- **Reconciled with the spike's 192m.** The spike agrees the cap is a backstop, not the
  detector, and warns not to size it tight. Because of `-XX:+ExitOnOutOfMemoryError`,
  hitting the cap restarts the JVM and stops **every** group, which is the outcome this
  feature exists to prevent.
  - **The spike's own number supports the larger cap.** It defines 192m as about 83 MB
    of baseline plus 100+ MB of headroom. That baseline is August's (the 83.7 MiB
    measured before 2d, below). The tree has grown since, by an unmeasured amount.
  - At 3 × about 84 MiB, the formula lands at 256m. Shipping 320m covers a steady state
    up to **106 MiB** without re-sizing.
  - **Leak detection does not depend on the cap.** At 0.14–0.45 MB per retained version,
    192m and 320m both take hundreds of leaked versions to reach, and the alerts fire
    long before either: `MetaspaceGrowth`, `MetaspaceNearCap`, and step 6's
    `PluginClassLoadersRetained`.
  - **The larger cap costs little.** There is no container memory limit in compose, and
    metaspace is committed only when used.
  - So choosing 192m would buy no earlier detection, and it would bring a
    restart-trigger closer for a baseline nobody has measured since August.
- **The pre-deploy reading decides.** V4-0 reads today's steady state before the deploy.
  If it exceeds 106 MiB, the releaser stops and Dev re-applies the formula.
- **What changes:** with the cap, a metaspace leak raises `OutOfMemoryError: Metaspace`,
  `ExitOnOutOfMemoryError` (`Dockerfile:18`) fires, and the container restarts with
  Java-side evidence in `docker logs`. Without the cap, the kernel OOM-kills the container
  silently.
- **New rule `MetaspaceNearCap`** in `bot-manager-jvm` (`prometheus/alerts.yml:669`):
  `jvm_memory_used_bytes{area="nonheap",id="Metaspace"} / jvm_memory_max_bytes{area="nonheap",id="Metaspace"} > 0.8`,
  `for: 15m`, warning, internal, commented as a first guess.
- **A3's question** ("what does it read in the first hour after a restart?"): about
  85/320 ≈ 0.27, since this is an instant ratio with no range. Before the cap, the max is
  `-1`, the ratio is negative, and the rule can never fire, so it is safe to ship ahead of
  the flag.
- `RuntimeMetricsExposedToAlertsTest` pins `jvm_memory_max_bytes`.
- **UNVERIFIED:** HotSpot 21's interaction between `MaxMetaspaceSize` and the default
  1 GiB `CompressedClassSpaceSize`. Dev checks
  `java -XX:MaxMetaspaceSize=320m -XX:+PrintFlagsFinal -version` in `eclipse-temurin:21-jre`
  for a startup warning and records the effective `CompressedClassSpaceSize`.

**D-17. `SlotStrategyFactory.registeredKeys()` returns a snapshot.** Use
`Collections.unmodifiableSet(new LinkedHashSet<>(…))`, **not** `Set.copyOf`. This lands
in 3a, before anything can mutate a registry. In practice D-9 makes registries immutable,
but the betting twin's rationale (a request thread iterating a view) applies regardless.

**D-18. Branch base and merge order.**
- Cut `feature/plugin-hot-reload-3-4` from the **tip of `feature/aviator-bot`**. It is the
  union, and it is what Bot-1 runs. A 3/4 build without the stack would remove Aviator,
  Cashout and provisioning from staging. **Never deploy a 3/4 build that lacks the stack.**
- If the stack reaches `main` first, rebase onto `main` (a content no-op for the stack's
  commits).
- If the user wants 3/4 on `main` without the stack, the pure-move commits (D-3) are what
  let the stack merge afterwards: rename detection carries its edits to
  `MessageTypesRegistry` into `bot-engine`, and its new providers land in `bot-messages`,
  which is plugin-side by the rule.
- After each rebase, re-run the grep that produced the boundary table above. The compiler
  catches the rest from 3b onwards.

**D-19. Deploy grouping.** Every phase is independently buildable and deployable. To
spend fewer restarts, the recommended staging deploys are:
- **D1** after 3b (covers 3a+3b);
- **D2** after 4b (4a+4b, behaviour-identical, classpath mode);
- **D3** after 4c.

**Prod gets one promotion**, after D3 has run on staging for ≥ 24 h, in one ticket
window. The releaser must be briefed explicitly for `Prod-Bot`. Prod also needs
`plugins-dist/` and the `deploy.sh` line.

**D-20. Test gate.** Before writing code, measure the baseline in a **clean detached
worktree at the branch point**, not in the dirty tree. Each phase's gate counts
surefire **and** failsafe reports. From 4b on, the failsafe count must be **> 0** (the
`*IT`-never-runs trap). Moves in 3a must keep the total **exactly equal** before new tests
are added.

---

## Plan

Build with `JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home`.
Brief every Dev to push back on a wrong decision rather than implement it faithfully.
Phase 1/2 found seven specification defects this way.

### Phase 3a — boundary moves (one Dev session)

1. Measure the baseline (D-20). Record it in the handoff.
2. **D-17:** `SlotStrategyFactory.registeredKeys()` → `LinkedHashSet` snapshot. Mirror the
   betting twin's test (order equals discovery order; the returned set is unmodifiable and
   independent of the registry).
3. **Pure-move commit A** (`git mv` only, D-3): the 17 `request/*` classes and 2
   `slot/*` classes from "Class placement" → `bot-api/src/main/java/…` (same packages).
   Tests of those classes **stay** in `bot-messages`, which depends on `bot-api`.
4. **Pure-move commit B:** `BettingStrategyFactory`, `SlotStrategyFactory` and
   `MessageTypesRegistry` → `bot-engine/src/main/java/…` (same packages). `bot-engine`
   keeps its compile dependency on the plugin modules until 3b.
5. **Pure-move commit C:** the registry-dependent tests →
   `bot-engine/src/test/java/…` (same packages). On **[av]** they are
   `GameMessageTypesForGameContractTest`, `GameRequestFactoryCapabilityTest`,
   `MessageTypesCoverageTest`, `MessageTypesErrorTextTest`, `MessageTypesRegistryTest`,
   `MessageTypesRegistryValidationTest`, `SlotMessageTypesTest`, `TaiXiuMessageTypesTest`,
   `RikProviderRegistrationTest`, `JackpotTaiXiuMessageTypesTest`,
   `BettingStrategyFactoryTest`, `MartingaleStrategyFactoryWiringTest`,
   `StrategyCatalogParityTest` and `SlotStrategyFactoryTest`. A test that a plugin module
   cannot compile after the move goes too. Move any shared test fixture with them.
6. **D-5:** add `HasRefund` and implement it on `TaiXiuEndGameMessage`. Rewrite
   `TaiXiuGameBot.java:223` and fix its javadoc `{@link}`. Add a unit test: refund is
   credited for a TX end message and 0 for any other.
7. **D-4:** replace the fallbacks at `BettingMiniGameBot.java:217` and
   `SlotMachineBot.java:161` with the `IllegalStateException`. Add an engine test helper
   that builds real factories, and wire it into the ~20 affected test files.
8. `PerBotInfoLogGuardTest.java:91-94`: drop the two factory exemptions. The files left
   `bot-strategies`, and their new `bot-engine/…/domain/bot/strategy/` directory is not
   banned. Keep any assertion that the remaining exemptions exist.
9. CLAUDE.md "Plugin registries": the registries live in `bot-engine`. One sentence.

**Local gate:** build green. The test total equals the baseline plus only the new tests
from steps 2, 6 and 7. The `bot-api` reverse-edge check from the old plan is empty. Then:
```bash
git grep -n -w -E "RandomBehaviorStrategy|FixedBetStrategy|TaiXiuEndGameMessage" -- bot-engine/src/main bot-app/src/main
```
Expect only comment lines.

### Phase 3b — plugin modules become plugin artifacts (one short Dev session)

1. **D-6 scopes.** `bot-engine`: plugin modules → `test`. `bot-app`: → `runtime`. Plugin
   poms: `bot-api`, `spring-context`, Jackson, slf4j, lombok → `provided`. The compiler
   now enforces D-2. Fix anything it flags by applying the rule, not by restoring a
   dependency.
2. **Enforcer** `bannedDependencies` in `bot-api`, `bot-engine` and `bot-app` (D-6).
   Prove it fails: temporarily set `bot-app`'s scope back to `compile`, observe the
   failure, revert. Record this in the handoff.
3. **D-7 manifest:** root-pom property, plus `maven-jar-plugin` `manifestEntries` in both
   plugin modules (`Bot-Plugin-Version`, plus `Bot-Plugin-Name` = artifactId).
4. Nothing else. The fat jar still contains both jars, and the Dockerfile is untouched.

**Local gate:** build green. The test total is unchanged from 3a. Then:
```bash
unzip -l bot-app/target/Bot-1.0.jar | grep -cE 'BOOT-INF/lib/bot-(strategies|messages)-1.0.jar'   # expect 2
for j in bot-strategies bot-messages; do unzip -p $j/target/$j-1.0.jar META-INF/MANIFEST.MF | grep Bot-Plugin-Version; done
```
Expect two identical values matching `^Bot-Plugin-Version: [0-9]{8}\.[0-9]{6}`.

### Phase 4a — `PluginRuntime` seam, classpath bundle, per-bundle registries (one Dev session)

1. `bot-engine`, package `com.vingame.bot.infrastructure.plugin`:
   - `PluginBundle`: `version()`, `source()`, `classLoader()`, `<T> List<T> beansOfType(Class<T>)`,
     `<T> T newInstance(Class<T>)`, `close()`;
   - `PluginRegistries` record `{bundle, bettingStrategies, slotStrategies, messageTypes, typeFactory}`
     with a static `build(PluginBundle)`, which is the single D-10 validation point;
     `typeFactory` = `TypeFactory.defaultInstance().withCache(new LRUMap<>(16, 200))` (D-9);
   - `PluginRuntime` with `current()`. The field is `final` in step 4.
2. Registries: drop `@Component`. Each one is constructed from a `PluginBundle`, and
   `create(key)` uses `bundle.newInstance(clazz)`, i.e. a prototype `getBean` on the
   **bundle's** context. Every message string and log line stays byte-identical (AD-20,
   A9). Update `MessageTypesRegistry`'s step-5 javadoc (D-9).
3. `ClasspathPluginBundle` wraps the root `ApplicationContext`: version `builtin`, the app
   loader, and `close()` as a no-op. It fails fast on zero plugin beans (D-12).
4. `bot-app` `PluginRuntimeConfiguration`: builds the bundle for `bot.plugins.mode` (only
   `classpath` exists in 4a; an unknown value fails startup), the `PluginRuntime` bean,
   and the `PluginVersionResolver` bean. Delete `BuiltinPluginVersionResolver`.
5. Consumers (`BotFactory`, `BotGroupConfigValidationService`, `StrategyCatalog`) inject
   `PluginRuntime` and read `current()` per call (D-9). `BotFactory` passes the current
   registries' factories **and `typeFactory`** to each bot.
5b. Every per-bot mapper calls `mapper.setTypeFactory(pluginTypeFactory)`:
    - `BettingMiniGameBot.java:1074`, which `TaiXiuGameBot` inherits;
    - `SlotMachineBot.java:456`;
    - `CashoutBot.java:527` **[av]**;
    - `CrashBot.java:621` **[av]**.
    With no type factory wired, fall back to the default. That path is tests only, because
    `BotFactory` always wires it. Add **L-8** `PerBotMapperTypeFactoryTest`.
    `PluginBundle.close()` follows D-13's order, with `PluginBundleCloseTest`.
6. **D-15:** `PluginClassLoaderMetrics` takes `PluginRuntime`, registers
   `current().bundle()`'s loader and version once, and appends the boot-line fields.
   `createSingleBot` sets `pluginVersion`.
7. `ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated` (`:134`) reads via
   `PluginRuntime`. Add a test helper `PluginRuntime.of(PluginRegistries)` for the
   `BotFactory*` tests.
8. **L-10** `RootContextHoldsNoPluginRefsTest`.

**Local gate:** build green, total ≥ the 3b total, `PluginClassLoaderMetricsTest` and
`InfoGauge*` tests unchanged and green.

### Phase 4b — isolated bundle loader, tested but not yet used (one Dev session)

1. `bot-engine` `IsolatedPluginBundleLoader` implements D-11 / D-13. Each rejected
   candidate gets one ERROR line, `plugin bundle <path> rejected: <reason>`. A fallback to
   the builtin directory gets one WARN. No candidate at all throws with both directories
   named.
2. `PluginRuntimeConfiguration` accepts `isolated`. It is reachable only through the
   property, and the default stays `classpath` (D-12).
2b. **Thread rules (L-11):**
    - `NettyEventLoopConfig.eventLoopGroup()` pre-starts every executor: submit a no-op
      to each `EventExecutor`, then `syncUninterruptibly()`;
    - `PluginRuntimeConfiguration` injects the `EventLoopGroup`;
    - `ClientFactory`'s null-group branch throws in isolated mode;
    - `GameMsClient.java:55` uses `Thread.ofVirtual()`.
    Add `NettyPrestartTest` and `ClientFactoryRequiresGroupTest`.
3. **New module `bot-plugin-dist`**, listed last in the root `<modules>`, no main code:
   - test-scope dependency on `bot-engine` and **no** dependency on the plugin modules,
     so plugin classes are absent from its classpath. That is the whole point of the
     module;
   - log4j-core + `log4j-slf4j2-impl` as test dependencies;
   - `maven-dependency-plugin:copy` the two plugin jars into
     `target/plugins-dist/${bot.plugin.version}/` at `package`. Dev confirms that reactor
     resolution works under `mvn clean install`. If it does not, copy from
     `../bot-{strategies,messages}/target/` and record why;
   - **failsafe declared in this module's `<build><plugins>` with explicit
     `integration-test` + `verify` executions**, not just in pluginManagement;
   - failsafe `argLine` carries the two `--add-opens` flags L-12 needs. The L-14
     negative control runs in a forked JVM;
   - tests L-1..L-5 and L-12..L-14 run against exactly `target/plugins-dist/`, i.e. what
     ships, plus selection tests:
     - greatest valid version wins;
     - an `_`-prefixed directory is ignored;
     - mismatched jar versions are rejected;
     - a duplicate-key bundle is rejected and the next candidate is used;
     - the builtin directory is used when the mount is empty.
     These selection tests build their fixture directories under `target/`.
4. **L-6, L-11** source guards in each plugin module's tests. **L-7** source guard in
   `bot-engine` tests.
5a. `BootParentReclaimMain` (L-15) goes in `bot-plugin-dist` test sources now. It is run
    from 4c, because only the 4c fat jar is plugin-free.
5. `.gitignore` + `.dockerignore`: `plugins-dist/` (the host-side directory name).

**Local gate:** build green. Failsafe report count > 0, and every IT listed above is
present in `bot-plugin-dist/target/failsafe-reports/`. Then:
```bash
mvn -pl bot-strategies,bot-messages dependency:tree > deptree.txt     # NOT -q: -q suppresses the tree and the grep passes vacuously
grep -c 'com.mercury:bot-api:jar' deptree.txt                                                                  # expect 2 (the tree was printed)
grep -ciE 'micrometer-(core|registry)' deptree.txt                                                             # L-9: expect 0
ls bot-plugin-dist/target/plugins-dist/*/                                                                      # expect exactly the two jars
```

### Phase 4c — cutover (one Dev session, small code delta)

1. `bot-app`: plugin modules `runtime` → `test`. The fat jar no longer contains them.
2. `Dockerfile`: `COPY bot-plugin-dist/target/plugins-dist/ /app/plugins-builtin/` (files
   must be world-readable, because the container runs as `HOST_UID`, not `botmanager`).
3. `docker-compose.yml` bot-manager:
   - volume `./plugins-dist:/app/plugins:ro` (commented in the style of the log4j2
     mount);
   - env `BOT_PLUGINS_MODE=${BOT_PLUGINS_MODE:-isolated}`;
   - env `JAVA_TOOL_OPTIONS=-XX:MaxMetaspaceSize=${BOT_METASPACE_MAX:-320m}` (D-16).
4. `prometheus/alerts.yml`: `MetaspaceNearCap` (D-16).
   `RuntimeMetricsExposedToAlertsTest`: pin `jvm_memory_max_bytes`.
5. CLAUDE.md, short:
   - the plugin bundle is the two jars, loaded from `/app/plugins` with fallback to the
     image;
   - local run needs `BOT_PLUGINS_MODE=isolated BOT_PLUGINS_DIR=../bot-plugin-dist/target/plugins-dist`;
   - deploy hazard: `plugins-dist/` must reach the box (fallback, not failure);
   - rollback = the previous image tag.
6. **User-owned, not Dev:** `deploy.sh` needs `plugins-dist` added to its `mkdir -p`
   line, on Bot-1 and on Prod-Bot. Without it, compose creates a root-owned empty
   directory, which is safe because of the fallback but masks a missed ship.
7. **L-15, the spike's caveat gap (best effort).** Run `BootParentReclaimMain` with
   Spring Boot's real launcher as the parent, on Linux Temurin:
   ```bash
   docker run --rm -v "$PWD":/w -w /w eclipse-temurin:21-jre java \
     -Dloader.path=bot-plugin-dist/target/test-classes \
     -Dloader.main=com.vingame.bot.plugin.it.BootParentReclaimMain \
     -cp bot-app/target/Bot-1.0.jar org.springframework.boot.loader.launch.PropertiesLauncher \
     bot-plugin-dist/target/plugins-dist
   ```
   Expect the exit code to be 0 and the output to read `RECLAIMED` for the clean flow and
   `PINNED` for the negative control.
   - If `PropertiesLauncher` cannot be driven this way against the fat jar, record why in
     the handoff. The caveat (Boot parent, Linux JDK) then stays open and gates step 6,
     not 4c (Open Items).

**Rollback** is the previous image. It ignores `BOT_PLUGINS_MODE` and the mount, and
runs comfortably under the 320 MiB cap (about 85 MiB in use).

**Local gate:** build green. Then:
```bash
unzip -l bot-app/target/Bot-1.0.jar | grep -cE 'bot-(strategies|messages)'   # expect 0
docker run --rm eclipse-temurin:21-jre java -XX:MaxMetaspaceSize=320m -XX:+PrintFlagsFinal -version 2>&1 \
  | grep -E 'MaxMetaspaceSize|CompressedClassSpaceSize|warning'                 # record; expect no warning
```

---

## Spike rules → where they land

The spike's eight hard rules, with "S" = the spike's rule number:

| Spike rule | Here | Step-4 shape (now) | Deferred to |
|---|---|---|---|
| S1 no static/process-lifetime mapper touches a plugin type (`getDefault()`, `CACHE`, `ActionResponseMessage`, `AuthClient`, `ApiGatewayClient`/`GameMsClient`) | L-7 | source guard. `LoginRequest`s stay engine-side | — |
| S2 per-version Jackson type cache + `clearCache()` on unload | D-9, D-13, L-8 | bundle-owned `TypeFactory`, wired into every per-bot mapper (4a) | — |
| S3 unload order: registry keys → `close()` ctx → loader `close()` | D-13 | `PluginBundle.close()` order fixed. Registries are per-bundle (D-9), so "removing keys" = unpublishing the bundle | step 6: removal from `PluginRuntime` |
| S4 remove every meter tied to a version, weak gauges with plugin lambdas included | L-9 | plugins have no `MeterRegistry` access, so no such meter can exist | step 6 if a plugin ever reports metrics, via parent-typed callbacks |
| S5 Netty threads started at boot; plugins never create platform threads; engine pools pre-started or virtual | D-13, L-11, L-12 | pre-start + bean ordering, mandatory shared group, `GameMsClient` → virtual, census IT with a negative control (4b) | — |
| S6 TCCL restored in `finally`, never set around engine calls | D-13, L-4 | restored in `finally` around `refresh()` | — |
| S7 no plugin `ThreadLocal` on engine threads | L-6 | source guard | — |
| S8 a loader lives until its last bot's `VingameWebSocketClient` closes | Open Items | — | step 6's `PluginClassLoadersRetained` must allow for drain time |

Log4j2 context identity is unchanged by the spike (it proved no pin, not identity). L-5
tests it. If L-5 fails, the contingency is
`-Dlog4j2.contextSelector=org.apache.logging.log4j.core.selector.BasicContextSelector` via
`JAVA_TOOL_OPTIONS`; that it is safe to use is **UNVERIFIED** (no async loggers are
configured).

---

## Implementation Notes / Concerns

- **The import scan missed a real reference.** Site 3 is an inline FQN. Trust the 3b
  compiler, not grep. That is why 3b removes the dependency rather than adding a lint.
- **Engine test-scope dependencies are fine. `bot-plugin-dist` must not have them.**
  Parent-first delegation means a plugin class that is also on the test classpath is
  silently loaded by the parent, and "isolation" then passes vacuously. L-1's
  defining-loader assertion is what catches it.
- **`maven.build.timestamp` changes every build.** If `bot-plugin-dist` runs in a
  different session from the plugin jars, the directory name and the manifest version can
  differ. The loader uses the manifest value, and the directory name is cosmetic (D-11).
- **Do not log per bot from the loader.** Loader output is one-shot (tier 1).
  `PerBotInfoLogGuardTest` still does not scan `bot-messages`. That is a known gap and is
  not widened here.
- **`StrategyCatalog`'s missing-built-in WARN** reads `pluginRuntime.current()` once. It
  still fires once per JVM.
- **The `MessageTypesRegistry initialized:` line differs between `main` and the union**
  (CASHOUT/CRASH segments appear **[av]**). Verification compares before against after on
  the same box, never against a literal.
- **Bot-1 restarts take Grafana, Prometheus and Loki with them.** The smoke checks
  re-verify the stack. `logging/log4j2.properties` must still reach the box.
- **`bot-api` drags Mongo onto plugins** (`@Document Game`). This is harmless because it
  is `provided`. Noted only so nobody "fixes" it by bundling.

---

## Open Items

- **Step 5 inherits:**
  - per-group version pinning (per group, or fleet-wide natural drain);
  - A5 (a registry shrinking across versions makes holders un-PATCHable; union or
    read-only);
  - an API-compatibility marker in the manifest (at step 4 the mounted and baked bundles
    come from one build, so they are compatible by construction);
  - `BotHealthDTO.pluginVersion`;
  - the `PluginClassLoadersRetained` alert (AD-7, step 6).
- **Not reloadable, by design:** Cashout/Crash betting behaviour (`CashoutBehavior`,
  `CrashStakes`) is engine code with no strategy contract. Making it reloadable needs a
  per-GameType strategy contract, which is a separate plan.
- **Confirm with the user before the prod promotion:** is prod-116 still double-backed by
  the legacy JS bots? That safety net was the original rationale for timing these
  restarts.
- **For step 6 (spike S8):** a version's loader lives until its last bot's
  `VingameWebSocketClient` closes, because `scenariosByTag` holds the per-bot mappers and
  callbacks. So "retained" is only meaningful after the drain completes, and
  `PluginClassLoadersRetained` must account for drain time. Not designed here.
- **Gates step 6, not step 4:**
  - L-15, if it could not be run in 4c. A live-socket reclaim has never been exercised
    (the spike used a direct `readValue`), so step 6's first staging reload is the first
    live proof.
  - Netty-internal lazily started platform threads beyond the event-loop group
    (`GlobalEventExecutor`, `ObjectCleaner`, resolvers) are **UNVERIFIED**. V4-11 lists
    platform threads on staging.
- **Standing rules outside the L-tests:** per-brand `LoginRequest`s stay engine-side
  (`ApiGatewayClient.java:654` serializes them with a static mapper), and plugin types are
  never persisted or mapped by Mongo (`MappingContext` caches entities per class,
  strongly). Neither holds plugin types today.
- **The 7-day metaspace baseline (P1-10) is formally abandoned.** V4-0 plus the cap
  formula replace it, and `MetaspaceNearCap` is the continuous check.
- **Unchanged, unrelated:** `BotGroupConfigValidationIT` never runs, and
  `BotGroup.slotStrategyId` has no effect on any bot.

---

## Verification

All commands run on the target host (Bot-1: `/home/sgame/bot-java`) unless marked
**local**. `BOT=http://localhost:8080`. **Preserve every capture** under
`docs/reviews/PLUGIN_HOT_RELOAD_3_4/` (A11: a capture that is referenced but not kept is
not evidence).

**The container is resolved by compose service, never by name.** Compose names it
`<project>-bot-manager-1` (on Bot-1 `bot-java-bot-manager-1`), so a literal
`docker logs bot-manager` matches nothing (release-d1 note 3). Every command below uses
`"$(docker compose ps -q bot-manager)"`, which works on Bot-1 and Prod-Bot alike. Run it
from the compose project directory, and inline it in each command rather than caching it
in a variable: the container ID changes on every recreate, so a value captured at P-0
names the old container after the deploy.

### P-0 — before every deploy (D1, D2, D3)

```bash
docker logs "$(docker compose ps -q bot-manager)" 2>&1 | grep -oE '(Betting|Slot)StrategyFactory initialized.*|MessageTypesRegistry initialized.*|plugin runtime: version=[^,]*' > boot-before.txt
curl -sf $BOT/api/v1/strategy/ | md5sum                       # expect a72c40f56057cda5434b273ea36315ea
curl -sf "$BOT/api/v1/strategy/?gameType=SLOT"                # expect []
curl -sf $BOT/actuator/prometheus | grep -E '^(plugin_classloaders_|bots_by_plugin_version|bots_managed )' > metrics-before.txt
curl -s 'http://localhost:9090/api/v1/query?query=ALERTS%7Balertstate%3D%22firing%22%7D' > alerts-before.json
```
A wrong md5 on the **before** capture means the box is not running what you think it
is. Stop.

### Common checks — after every deploy (C-1..C-6)

- **C-1:** `curl -sf $BOT/actuator/health`, then `curl -sf http://localhost:9090/-/ready`,
  then `curl -sf http://localhost:3000/api/health`. Expect `{"status":"UP"…}`,
  `Prometheus Server is Ready`, and Grafana HTTP 200.
- **C-2:**
  ```bash
  docker logs "$(docker compose ps -q bot-manager)" 2>&1 | grep -oE '(Betting|Slot)StrategyFactory initialized.*|MessageTypesRegistry initialized.*' > boot-after.txt
  diff <(grep -v 'plugin runtime' boot-before.txt) boot-after.txt
  ```
  Expect **no output**: `registered 9 strategies`, `registered 2 strategies`, and the
  same product sets.
- **C-3:** the strategy md5 is `a72c40f56057cda5434b273ea36315ea`, and the SLOT query
  returns `[]`.
- **C-4, end to end:**
  - On one running group per game type present on the box (at least one BETTING_MINI and
    one TAI_XIU; on the union also the 119 CRASH group `13c2b858`, and a CASHOUT group if
    one is running; never tx7), start it or confirm it is running.
  - Wait 120 s, then:
    ```bash
    curl -sf $BOT/actuator/prometheus | grep -E '^bot_messages_total' | head
    grep -E 'entered session' logs/detail/detail.log | tail -5
    ```
  - Expect `bot_messages_total` > 0 and rising across two reads 30 s apart, and recent
    `BotGroup <game>/<id> entered session <sid>` lines.
- **C-5:**
  ```bash
  grep -cE 'NoClassDefFoundError|ClassNotFoundException|LinkageError|IllegalAccessError|ClassCastException|not wired' logs/console.log
  ```
  Expect **0** since the restart.
- **C-6:** the firing-alerts query returns the same set as `alerts-before.json` (normally
  empty). A newly firing `EnvironmentDeadBotRatioHigh` or `GameNoRounds` means a group
  stopped resolving its strategy or messages. **Roll back.**

### D1 — after 3b (covers 3a + 3b)

- **V3-0 (local, before shipping):** the 3b gate's `unzip` checks. Expect `2` plugin
  jars in the fat jar and identical `Bot-Plugin-Version` values.
- **C-1..C-6.**
- **V3-1:**
  ```bash
  docker logs "$(docker compose ps -q bot-manager)" 2>&1 | grep -c 'plugin runtime: version=builtin'
  curl -sf $BOT/actuator/prometheus | grep -E '^plugin_classloaders_'
  ```
  Expect `1`, and `live=1`, `registered_total=1`, `reclaimed_total=0`, all with
  `pluginVersion="builtin"`. That is unchanged.

### D2 — after 4b (covers 4a + 4b; classpath mode, behaviour-identical)

- **C-1..C-6** and **V3-1.**
- **V4a-1:**
  ```bash
  docker logs "$(docker compose ps -q bot-manager)" 2>&1 | grep 'plugin runtime: version=builtin' | grep -c 'source=classpath'
  ```
  Expect `1`.
- **V4a-2:** group running, wait ≥ 20 s:
  ```bash
  curl -sf $BOT/actuator/prometheus | grep -E '^bots_by_plugin_version|^bots_managed '
  ```
  Expect every row `pluginVersion="builtin"`, and their sum equal to `bots_managed`. This
  shows `createSingleBot` now sets the field without changing its value.
- **V4a-3:** `docker logs "$(docker compose ps -q bot-manager)" 2>&1 | grep -c 'plugin bundle'`. Expect **0**: the
  isolated loader does not run in classpath mode.
- **Metaspace (informational, the D3 input).** Record `jvm_memory_used_bytes{id="Metaspace"}`
  and uptime at P-0 and again after the deploy at a **comparable uptime** (release-d1's
  note: its 5 h before against 12 min after was not like for like). 4a/4b add a handful of
  engine classes and no plugin loader, so the two should agree within normal warm-up drift.
- **V4b-1, Netty pre-start (L-11, spike S5).** Within 90 s of start, and before
  starting any group:
  ```bash
  docker kill --signal=QUIT "$(docker compose ps -q bot-manager)" && sleep 2
  docker logs --since 1m "$(docker compose ps -q bot-manager)" 2>&1 | grep -oE '^"multiThreadIoEventLoopGroup-[0-9]+-[0-9]+"' | sort -u | wc -l
  ```
  - SIGQUIT makes the JVM print a platform-thread dump to stdout. It does not stop the
    process.
  - Expect the count to equal `websocket.eventloop.threads` (default **4**).
  - Before 4b, the count would be 0 on an idle box.

### D3 — after 4c

- **V4-0 (before the deploy, mandatory).** Uptime must be > 24 h:
  ```bash
  curl -s 'http://localhost:9090/api/v1/query?query=time()-process_start_time_seconds%7Bjob%3D%22bot-manager%22%7D'
  curl -s 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes%7Barea%3D%22nonheap%22%2Cid%3D%22Metaspace%22%7D'
  ```
  Expect uptime > 86400 and Metaspace ≤ **111,149,056 B** (106 MiB). Compare a metaspace
  reading only with one taken at a similar uptime: the JVM keeps loading classes lazily for
  hours (release-d1: 84.3 MB at 35 s and 87.2 MB at 12 min on the new JVM, against
  93.5 MB on the previous one at 5 h), so a fresh reading against an old one is not like
  for like. If it is higher,
  **stop**: D-16's formula needs a larger cap. Write both numbers into the release report.
  If uptime is < 24 h, record the reading as provisional and proceed only if it is ≤
  95 MiB (headroom for warm-up).
- **V4-1 (local, before shipping):**
  ```bash
  unzip -l bot-app/target/Bot-1.0.jar | grep -cE 'bot-(strategies|messages)'          # expect 0
  ls bot-plugin-dist/target/plugins-dist/                                              # expect one dir V
  sha256sum bot-plugin-dist/target/plugins-dist/*/*.jar | tee plugins-sha.txt
  ```
  Then copy `bot-plugin-dist/target/plugins-dist/V` to the host as `plugins-dist/V`, by
  sftp alongside `bot.tar` and the updated `docker-compose.yml`. Confirm the user has
  added `plugins-dist` to `deploy.sh`'s `mkdir -p`.
- **C-1..C-6.**
- **V4-2:** `docker logs "$(docker compose ps -q bot-manager)" 2>&1 | grep -c 'Picked up JAVA_TOOL_OPTIONS: -XX:MaxMetaspaceSize=320m'`.
  Expect `1`.
- **V4-3:**
  ```bash
  curl -sf $BOT/actuator/prometheus | grep -E '^jvm_memory_max_bytes\{.*id="Metaspace"'
  ```
  Expect the value `3.3554432E8`.
- **V4-4:** `docker logs "$(docker compose ps -q bot-manager)" 2>&1 | grep 'plugin runtime: version='`. Expect
  exactly one line with:
  - `version=V`, matching `^[0-9]{8}\.[0-9]{6}$` and equal to the directory name shipped;
  - `source=/app/plugins/V`;
  - two `jars=` entries whose sha256 prefixes match `plugins-sha.txt`.
  Also `docker logs "$(docker compose ps -q bot-manager)" 2>&1 | grep -cE 'plugin bundle .* rejected|running the image.s built-in bundle'`,
  which must be `0`.
- **V4-5:**
  ```bash
  curl -sf $BOT/actuator/prometheus | grep -E '^plugin_classloaders_'
  ```
  Expect `live{pluginVersion="V"}=1`, `registered_total=1`, `reclaimed_total=0`, and
  `grep -c 'pluginVersion="builtin"'` over the same scrape returns **0**.
- **V4-6:** group running, wait ≥ 20 s. `bots_by_plugin_version` rows are all
  `pluginVersion="V"`, and their sum equals `bots_managed`.
- **V4-7:** `grep -m1 '"pluginVersion":"V"' logs/console.log` matches. In Grafana,
  `{job="bot-manager"} | json | pluginVersion="V"` returns lines.
- **V4-8:**
  ```bash
  curl -sf http://localhost:9090/api/v1/rules | grep -o 'MetaspaceNearCap'
  curl -s 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes%7Bid%3D%22Metaspace%22%7D%2Fjvm_memory_max_bytes%7Bid%3D%22Metaspace%22%7D'
  ```
  Expect the rule to be present, and a ratio < 0.5.
- **V4-9, fallback drill (staging only, never prod).**
  1. `mv plugins-dist/V plugins-dist/_V && docker compose restart bot-manager`. After
     about 90 s, expect one WARN containing `running the image's built-in bundle V` and a
     boot line with `source=/app/plugins-builtin/V`. C-2 still passes.
  2. Then `mv plugins-dist/_V plugins-dist/V && docker compose restart bot-manager`.
     Expect `source=/app/plugins/V` again.
- **V4-11, platform-thread census (spike S5; staging only).**
  1. Take a SIGQUIT dump as in V4b-1 right after boot, and save the thread *names*.
  2. Start the C-4 groups and let them play for 10 min.
  3. Take a second dump.
  4. Compare the name families (strip numeric suffixes) with
     `diff <(sed -E 's/[-#][0-9]+//g' t0 | sort -u) <(sed -E 's/[-#][0-9]+//g' t1 | sort -u)`.
  Expect **no new long-lived platform-thread family**. The event-loop count must be
  unchanged, and there must be no second `multiThreadIoEventLoopGroup-N-*` family, which
  would mean a private group (`VingameWebSocketClient.java:400`) was created. Any new
  family is a finding: name it in the release report. It is a candidate pin for step 6,
  not a 4c rollback unless C-5/C-6 also fail.
- **V4-10:** after 24 h, before any prod promotion, re-read V4-5 (unchanged), V4-8
  (ratio flat within ±5 MiB of the first reading at equal uptime), and C-6 (no new
  alerts).

Plan written: docs/plans/PLUGIN_HOT_RELOAD_3_4.md
Ready for user approval before Dev begins.

## Changelog

- 2026-10-06, compliance-3ab: D-7 UTC confirmed; L-2 admits the module's own `META-INF/maven` descriptor (maven-jar-plugin default) and treats any other as shading; L-9 and its 4b gate target `micrometer-core`/`-registry`, because `spring-context` 6.x pulls `micrometer-observation` transitively and the old `grep micrometer` returns 4, not 0 (and its `mvn -q` printed no tree at all, so it passed vacuously).
