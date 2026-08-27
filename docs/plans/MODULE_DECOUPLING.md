# Module Decoupling — Static Maven Multi-Module Split

## Goal

Convert the single-module Bot Manager Maven project into a **parent aggregator +
child modules** so the bot **engine** (lifecycle, WS/auth/balance plumbing) is
compiled behind a stable **contract module** and separated from strategies,
messages, and the Spring Boot application. This is a **mechanical, static split
only**: it produces the **same single runnable Spring Boot fat jar** (same
`Starter`, same Docker image, same `docker-compose` artifact), with **no behavior
change, no API change, no runtime change**. The payoff is a clean, enforced
dependency boundary (`bot-api`) that makes the codebase legible and *permits* a
future drain-based hot-reload effort — but this plan **does not build** hot-reload,
dynamic classloading, PF4J, or any reload endpoint. Those remain a separate,
spike-gated, out-of-scope effort.

> `PLUGIN_PLAN.md` at the repo root was **STALE and superseded** by this document,
> and has since been **deleted** (`docs/plans/PLUGIN_HOT_RELOAD.md` AD-22; git
> history is the archive). It predated the Docker/observability stack and proposed
> hot-reloadable *game/message plugins* with the **engine placed in a reloadable
> layer** — the inverse of the correct design. Engine is stable core. If you find a
> copy, do not follow it; the live plan is `PLUGIN_HOT_RELOAD.md`.

---

## Findings — Current State

Single Maven module: root `pom.xml` inherits `spring-boot-starter-parent:3.4.0`,
Java 21, one `<build>` with `spring-boot-maven-plugin` (repackage) +
`maven-compiler-plugin` carrying the MapStruct + Lombok + `lombok-mapstruct-binding`
annotation-processor paths (`/Users/gleb/IdeaProjects/Bot/pom.xml:40-65`). All
source under one base package `com.vingame.bot.*`
(`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/Starter.java`).
150 test source files exist and are real JUnit tests (not `main()` tests).

### Package dependency graph (verified by import inspection)

Layer, low → high (arrows = "compiles against"):

- **Pure leaves (no `com.vingame.bot` deps, or only ws-parser):**
  - `domain/bot/coordination` — `CrowdOption`, `BetCoordinator`, `JackpotScaler`,
    `ReservationOutcome`, `RoundBudget`. Zero `com.vingame` imports. The one
    apparent `message` reference in `JackpotScaler.java:10` is a Javadoc
    `{@link}` only — **not** a compile dependency (verified). Clean leaf.
  - `domain/bot/util` — `GameState`, `BettingMiniGameState`, `SessionIdStore`,
    `OutputPrinter`. Only ws-parser imports.
  - `common/logging/BotMdc` (slf4j only), `common/exception`.
  - `domain/brand/model` — `ProductCode`, `BrandCode` (Jackson only).
- **`domain/game/model/Game`** — a Mongo `@Document` (`Game.java` imports
  `org.springframework.data.mongodb...@Document` + `@Id`) → `brand.model`.
  Referenced directly by strategy, engine, and the config value objects. Because
  it is a concrete `@Document`, any module holding it needs `spring-data-mongodb`
  on its compile classpath (annotations only; runtime mapping stays in the app).
- **`domain/bot/strategy`** interfaces/enums/DTOs — `BettingStrategy` (interface),
  `SlotStrategy` (interface), `StrategyId`/`SlotStrategyId` (enums), `StrategyImpl`
  (annotation), `BetContext`, `BetDecision`, `RoundResult`, `RoundState`,
  `BotMemory`, `SlotBetContext`, `StrategyAssignment`. Import `config.bot.BotBehaviorConfig`,
  `util`, `game.model`.
- **`config/bot`** value objects — `BotConfiguration`, `BotBehaviorConfig`,
  `BotCredentials`. `BotConfiguration.java:3-4` imports `strategy.StrategyId` +
  `strategy.slot.SlotStrategyId`. Shared by strategy, engine, `infrastructure.client`.
- **`domain/bot/message`** base contract — `GameMessageTypes`/`SlotMessageTypes`/
  `TaiXiuMessageTypes` interfaces, `Has*` interfaces (`HasCrowdBets` →
  `coordination.CrowdOption`), abstract base messages (`BettingMiniMessage`,
  `EndGameMessage`, `StartGameMessage`, `StartGameMd5Message`, `SubscribeMessage`,
  `UpdateBetMessage`). Concrete per-brand impls under `g2/{bom,b52}`, `g3/tip/*`,
  `g4/nohu`, `slot`, `taixiu`, plus `request/*` builders and
  `GameMessageTypesResolver` (maps `ProductCode` → concrete `GameMessageTypes`).
- **`domain/bot/strategy`** concrete impls — `martingale/*`, `slot/*` concretes,
  `RandomBehaviorStrategy`, `WeightedStrategy`, `WeightedOptionPicker`.
  `BettingStrategyFactory` and `SlotStrategyFactory` are `@Component` classes that
  discover `@StrategyImpl`-annotated strategies (component/classpath scanning).
- **`infrastructure/client`** — `ClientFactory`, `ApiGatewayClient`, `GameMsClient`,
  `dto/*`. Imports `config.bot.BotCredentials`, `infrastructure.auth.AuthProfile`,
  `infrastructure.observability.BotMetrics`, `common.exception`.
- **`infrastructure/auth`** — `AuthProfile`, `AuthStrategyFactory` → `domain.bot.auth`
  login requests, `brand.model.ProductCode`, `environment.model.Environment`.
- **`infrastructure/observability`** — split personality (see cycle below).
- **`domain/bot/core`** (the engine) — `Bot`, `BettingMiniGameBot`, `SlotMachineBot`,
  `TaiXiuGameBot`, `BotStatus`. Import `infrastructure.client`,
  engine-facing `infrastructure.observability`, `coordination`, `strategy`
  (incl. concrete `RandomBehaviorStrategy` + factories), `message` (base + `request`
  + concrete `slot.*`), `config.bot`, `game.model`, `common.logging`.
- **`bot-app` residue** — `BotFactory` (`domain/bot/service`), `BotGroupBehaviorService`,
  `BotGroupRuntime` (`infrastructure/runtime`), all controllers, all CRUD domains
  (`botgroup`, `environment`, `game`, `session`, `metrics`, `brand`), mappers,
  repositories, `config/client`, `Starter`.

### Reverse dependencies on the engine (`domain/bot/core`)

Only five files import `domain.bot.core.*`, **all in the app layer**:
`domain/bot/service/BotFactory.java`, `domain/botgroup/dto/BotHealthDTO.java`,
`domain/botgroup/service/BotGroupBehaviorService.java`,
`infrastructure/observability/ObservabilityConfig.java`,
`infrastructure/runtime/BotGroupRuntime.java`. The engine therefore has **no
incoming edge from any would-be-shared module** — it slots cleanly under `bot-app`.

### Cycles found (must be broken by the split)

1. **`config.bot` ↔ `strategy`.** `BotConfiguration` imports the `StrategyId`/
   `SlotStrategyId` enums; strategy DTOs (`BetContext`, `RandomBehaviorStrategy`,
   `MartingaleStrategySupport`) import `BotBehaviorConfig`. **Break:** put the
   `StrategyId`/`SlotStrategyId` enums, the strategy interfaces/DTOs, **and** the
   `config.bot` value objects all in `bot-api` (same module ⇒ no cross-module
   edge). Concrete strategies then depend only downward on `bot-api`.
2. **`engine` ↔ `observability`.** `Bot`/`BettingMiniGameBot`/`SlotMachineBot`
   import `BotMetrics` + `SessionAggregationService` + the `*SessionStrategy`
   interfaces; but `ObservabilityConfig` and `InfoGaugeRefresher` import
   `core.BotStatus` / `botgroup.service.BotGroupBehaviorService`. **Break:** the
   observability package splits — engine-facing classes (`BotMetrics`,
   `SessionAggregationService`, `SessionAggregationStrategy`, `BettingSessionStrategy`,
   `SlotSessionStrategy`, `SessionAccumulator`, `SessionContext`) go **down into
   `bot-engine`**; the two Spring-wiring classes that reach back up
   (`ObservabilityConfig`, `InfoGaugeRefresher`) plus `BotMdcTagsMeterFilter` stay
   in **`bot-app`**. Verified: only those two files carry the upward import.
3. **`message` ↔ `coordination`** — **not a real cycle** (Javadoc `{@link}` only).
   `CrowdOption` goes to `bot-api`; `message` compiles against it downward.

### Deploy chain

- `Dockerfile` COPYs `target/Bot-1.0.jar` → `Bot.jar`
  (`/Users/gleb/IdeaProjects/Bot/Dockerfile:10`).
- `docker-compose.yml` `bot-manager` service uses a pre-built `image:
  vingame-bot:latest` (no `build:` block) (`docker-compose.yml:28-29`).
- `startup.sh` runs `mvn package -DskipTests` then `docker compose up --build`.
- `deploy.sh` only writes `.env` + `docker compose up -d` — jar-path agnostic.
- After the split the bootable jar moves to `bot-app/target/`. Minimal change:
  **one line** in `Dockerfile` (COPY path) — see Phase 1.

---

## Per-aspect readiness / mapping

| Aspect | Readiness | Notes |
|---|---|---|
| `bot-api` extraction (interfaces/enums/base msgs/util/models/config VOs) | **ready** | Self-contained once `StrategyId`+config VOs co-locate here; breaks cycle #1. |
| `strategies` extraction (concrete strategies + `@Component` factories) | **ready** | Already interface+annotation based; depends only on `bot-api`. |
| `messages` extraction (concrete brand msgs + `request` + resolver) | **ready** | One module (not per-brand) for the first pass. Jackson subtypes are compile-referenced by the resolver ⇒ ship in the jar. |
| `bot-engine` extraction (`core` + `client` + `auth` + coord + eng-observability) | **partial** | Requires splitting the `observability` package (cycle #2) and confirming `@ComponentScan` still reaches strategy `@Component`s across modules. |
| Same single fat jar / Docker image | **ready** | `bot-app` owns `spring-boot-maven-plugin:repackage`; `finalName=Bot-1.0`. |
| Dockerfile / deploy scripts | **partial** | One-line `Dockerfile` COPY-path change; `deploy.sh`/`startup.sh` unchanged. |
| Lombok + MapStruct per module | **partial** | Processor paths must move to parent `<pluginManagement>`; common breakage point. |
| Spring component scan across modules | **ready** | All modules keep base package `com.vingame.bot.*` ⇒ one context scans all. |
| Mongo `@Document` in a shared module | **ready** | Shared module gains `spring-data-mongodb` (annotations); mapping stays in app. |
| Tests | **ready** | Each test moves with its production class into the owning module. |

---

## Architecture Decisions

1. **Five modules, strict downward dependency:**
   `bot-app → bot-engine → {bot-strategies, bot-messages} → bot-api`.
   `bot-strategies` and `bot-messages` are siblings (no edge between them).
2. **`bot-api` is the contract module** and holds: strategy interfaces
   (`BettingStrategy`, `SlotStrategy`), the `@StrategyImpl` annotation,
   `StrategyId`/`SlotStrategyId` enums, strategy DTOs (`BetContext`, `BetDecision`,
   `RoundResult`, `RoundState`, `BotMemory`, `SlotBetContext`, `StrategyAssignment`);
   message contract (`GameMessageTypes`/`SlotMessageTypes`/`TaiXiuMessageTypes`
   interfaces, `Has*` interfaces, abstract base messages); `coordination.CrowdOption`;
   `domain/bot/util/*`; the config value objects (`BotConfiguration`,
   `BotBehaviorConfig`, `BotCredentials`); shared models `Game`/`GameType`,
   `brand.model` (`ProductCode`, `BrandCode`); `common/logging`, `common/exception`.
   This co-location deliberately breaks cycle #1.
3. **No factory-interface extraction in this pass.** `BettingStrategyFactory`/
   `SlotStrategyFactory` are concrete `@Component`s and stay in `bot-strategies`;
   the engine depends on `bot-strategies` and calls them directly. Extracting a
   factory *interface* into `bot-api` is a **future** hot-reload seam, explicitly
   out of scope here. (`BettingStrategy`/`SlotStrategy` interfaces already exist and
   do move to `bot-api`.)
4. **`bot-messages` is ONE module** for the first pass (all brands together), not
   per-brand. Per-brand splitting is a later, lower-value refinement.
5. **The observability package splits** per cycle #2: engine-facing classes to
   `bot-engine`, `ObservabilityConfig`/`InfoGaugeRefresher`/`BotMdcTagsMeterFilter`
   to `bot-app`.
6. **Every module keeps base package `com.vingame.bot.*`.** No package renames.
   This preserves the single `@SpringBootApplication` component scan and all
   Jackson/Spring reflection with zero config change.
7. **`bot-app` is the only bootable module.** It owns `spring-boot-maven-plugin`
   with the `repackage` goal and `<finalName>Bot-1.0</finalName>` so the artifact
   is `bot-app/target/Bot-1.0.jar`.
8. **Annotation processors are declared once** in the parent POM
   `<build><pluginManagement>` (MapStruct processor + Lombok + `lombok-mapstruct-binding`),
   inherited by every child that activates `maven-compiler-plugin`. Lombok is used
   everywhere; MapStruct mappers live only in `bot-app`, but the binding is harmless
   in every module.
9. **Dependency versions are managed in the parent** (`<dependencyManagement>`:
   ws-parser 3.0.5, netty, jackson pins, springdoc, mapstruct); each child declares
   only the artifacts it compiles against, without versions.
10. **`Game` stays a Mongo `@Document` in `bot-api`.** Extracting a pure-POJO
    domain model is a behavior-adjacent refactor and is **out of scope**; the
    shared module simply gains `spring-data-mongodb` on its compile classpath.
11. **One-line `Dockerfile` change** (COPY path → `bot-app/target/Bot-1.0.jar`) is
    the only deploy artifact touched. `docker-compose.yml`, `deploy.sh`,
    `startup.sh`, `.dockerignore` are unchanged.
12. **No behavior/API/runtime change.** Any diff to a method body, endpoint,
    property, or wire format means the phase overreached and must be reverted.

---

## Plan

Each phase is independently buildable (`mvn clean install` green), boots `Starter`,
and is revertible. The strategy: **first collapse everything into one child module**
to prove the multi-module + fat-jar + Docker mechanics with zero class moves, then
**carve modules out of `bot-app` one at a time**, `bot-api` first (it breaks the
cycles that the later carves depend on).

### Phase 1 — Parent aggregator + single `bot-app` child (prove the mechanics)

Pure relocation, no class moves between packages.
1. Convert root `pom.xml` to `<packaging>pom</packaging>`, keep
   `spring-boot-starter-parent` as *its* parent, add `<modules><module>bot-app</module></modules>`.
   Hoist `<dependencyManagement>` (ws-parser, netty, jackson, springdoc, mapstruct
   versions) and `<build><pluginManagement>` (compiler plugin with the three
   annotation-processor paths) into the parent.
2. Create `bot-app/pom.xml` (`<parent>` = the aggregator, `artifactId=bot-app`),
   move `src/` into `bot-app/src/`. `bot-app` declares all current dependencies and
   owns `spring-boot-maven-plugin` with `repackage` + `<finalName>Bot-1.0</finalName>`.
3. `Dockerfile:10` COPY path → `bot-app/target/Bot-1.0.jar`. (Only deploy change.)
4. Verify (below). Everything still lives in one module; only the build shape changed.

### Phase 2 — Extract `bot-api` (breaks cycle #1)

1. Create `bot-api/pom.xml` (deps: lombok provided, jackson-annotations/databind,
   `spring-data-mongodb` for `Game`, ws-parser, slf4j).
2. Move into `bot-api` (keeping packages): strategy interfaces + `@StrategyImpl` +
   `StrategyId`/`SlotStrategyId` + strategy DTOs; message interfaces + `Has*` +
   abstract base messages; `coordination.CrowdOption`; `domain/bot/util/*`;
   `config/bot/{BotConfiguration,BotBehaviorConfig,BotCredentials}`;
   `domain/game/model/{Game,GameType}`; `domain/brand/model/{ProductCode,BrandCode}`;
   `common/logging`; `common/exception`. Move each class's test with it.
3. `bot-app` adds `<dependency>bot-api</dependency>`.
4. Verify. Confirm cycle #1 is gone: `bot-api` has no dependency on `bot-app`.

### Phase 3 — Extract `bot-strategies`

1. Create `bot-strategies/pom.xml` (deps: `bot-api`, lombok, spring-context for
   `@Component`).
2. Move concrete strategies: `strategy/{RandomBehaviorStrategy,WeightedStrategy,
   WeightedOptionPicker,BettingStrategyFactory}`, `strategy/martingale/*`,
   `strategy/slot/*` concretes + `SlotStrategyFactory`. Leave `StrategyController`
   in `bot-app` (it is a REST controller). Move matching tests.
3. `bot-app` adds `<dependency>bot-strategies</dependency>`.
4. Verify — pay special attention to the `@StrategyImpl` component-scan smoke
   (all strategies still discovered, see Verification).

### Phase 4 — Extract `bot-messages`

1. Create `bot-messages/pom.xml` (deps: `bot-api`, jackson, ws-parser, lombok).
2. Move all concrete messages under `message/{g0,g2,g3,g4,g5,gth,slot,taixiu}`,
   `message/request/*`, `GameMessageTypesResolver`, and the concrete
   `*GameMessageTypes` impls. Base interfaces already left in `bot-api` (Phase 2).
   Move matching tests.
3. `bot-app` adds `<dependency>bot-messages</dependency>`.
4. Verify — assert the Jackson subtype-registration path still works end to end
   (`GameMessageTypesResolver` → `getTypeRegistrations` → `registerSubtypes`), i.e.
   a bot actually parses `StartGame`/`EndGame` and bets (see Verification).

### Phase 5 — Carve `bot-engine` from `bot-app`

1. Create `bot-engine/pom.xml` (deps: `bot-api`, `bot-strategies`, `bot-messages`,
   ws-parser, netty, micrometer, spring-context, lombok).
2. Move into `bot-engine`: `domain/bot/core/*`; `infrastructure/client/*` (+ `dto`);
   `infrastructure/auth/*` and `domain/bot/auth/*`; `domain/bot/coordination/*`
   (except `CrowdOption`, already in `bot-api`); and the engine-facing observability
   classes `BotMetrics`, `SessionAggregationService`, `SessionAggregationStrategy`,
   `BettingSessionStrategy`, `SlotSessionStrategy`, `SessionAccumulator`,
   `SessionContext`. Move matching tests.
3. Leave in `bot-app`: `ObservabilityConfig`, `InfoGaugeRefresher`,
   `BotMdcTagsMeterFilter`, `BotFactory`, `BotGroupRuntime`, `BotGroupBehaviorService`,
   `config/client/*`, all controllers, all CRUD domains, mappers, repositories,
   `Starter`.
4. `bot-app` adds `<dependency>bot-engine</dependency>` (and gets api/strategies/
   messages transitively).
5. Verify — full smoke; this is the largest carve.

---

## Implementation Notes / Concerns

- **Annotation-processor breakage is the #1 multi-module risk.** If Lombok
  getters/`@Builder` methods "don't exist" at compile in a child, the processor
  paths are missing from that child's compiler config. Fix in parent
  `pluginManagement`, not per module. `lombok.config` at repo root must remain
  visible to every module (Lombok walks up until `config.stopBubbling=true`) — keep
  it at the repo root so all modules inherit `copyableAnnotations += @JsonProperty`;
  do **not** move it into `bot-app`.
- **Cross-module component scan.** `bot-strategies`' `@Component` factories and
  every `@StrategyImpl` strategy are in the same base package and same fat jar, so
  the single `@SpringBootApplication` scan finds them. This ONLY holds because
  Decision #6 keeps `com.vingame.bot.*` everywhere. If a strategy silently stops
  being registered, the cause is a package rename, not the module boundary.
- **Jackson subtype registration.** `mapper.registerSubtypes(messageTypes.
  getTypeRegistrations(offset, md5))` in `BotFactory` explicitly references the
  concrete message types via `GameMessageTypesResolver`, so they are compile-reachable
  and Maven ships them — no classpath-scan/tree-shake hazard. Still smoke a real
  round each phase after Phase 4.
- **Mongo entity in a library module.** `Game`/`ProductCode` move to `bot-api`
  while their repositories/mappers stay in `bot-app`. Spring Data's mapping context
  and `@EnableMongoRepositories` live in `bot-app` and scan the classpath, so the
  entity's physical module is irrelevant at runtime. No `@EntityScan`-style change
  is expected; if Mongo can't map `Game`, check that `bot-app` still scans
  `com.vingame.bot`.
- **`requiresUnpack` log4j-core config** (`pom.xml:31-37`) must move with the
  `spring-boot-maven-plugin` into `bot-app`, or JSON logging layout breaks in the
  fat jar.
- **Do not move REST controllers down.** `StrategyController`, metrics/session/
  game controllers stay in `bot-app` even when their sibling domain classes move —
  they are the web layer.
- **Test placement.** Moving a test but not its class (or vice-versa) yields a
  compile error in the wrong module. Move them together; if a test spans layers
  (e.g. `BotGroupRuntimeTest`), it stays in the highest module involved (`bot-app`).
- **IDE/`.idea`** will need a reimport after Phase 1; not a build concern.

---

## Open Items

- **Out of scope (future, spike-gated):** PF4J, dynamic classloading, runtime
  plugin loading, reload endpoint, drain-based hot-reload, per-brand message
  submodules, factory-interface extraction, pure-POJO `Game` model. This plan only
  designs boundaries that *permit* them later.
- **Deploy:** confirm with the operator how `vingame-bot:latest` is actually built
  (compose has no `build:` block; `startup.sh`'s `--build` is a no-op for it). The
  one-line `Dockerfile` COPY change assumes an external `docker build`; if a
  `build:` block is added later it must point context at repo root with the updated
  COPY path.
- **`spring-boot-starter-parent` inheritance chain:** the aggregator inherits from
  it and children inherit from the aggregator — verify dependency management flows
  two levels down as expected during Phase 1 (standard, but confirm on first build).

---

## Verification

This is a **pure structural refactor with no behavior change**, so on-server
verification is the **universal smoke test proving behavior is unchanged**, run
after each phase locally and once on staging after the final deploy.

### Per-phase local gate (run after every phase)

1. Build the whole reactor:
   ```
   JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
     mvn -q clean install
   ```
   Expect: `BUILD SUCCESS`, all modules built, no compile/annotation-processor errors.
2. Confirm the single bootable fat jar exists and is the only one:
   ```
   ls -l bot-app/target/Bot-1.0.jar
   ```
   Expect: file present, tens of MB (fat jar), and no `Bot-1.0.jar` under any other
   module's `target/`.
3. Boot the app:
   ```
   java -jar bot-app/target/Bot-1.0.jar
   ```
   Expect: log line `Started Starter in ...` and no `BeanCreationException` /
   `NoSuchBeanDefinitionException` / `UnsatisfiedDependencyException`.
4. (Phase 2+) Assert no dependency inversion — the contract module must not depend
   on the app:
   ```
   mvn -q -pl bot-api dependency:tree | grep -E 'bot-app|bot-engine|bot-strategies|bot-messages'
   ```
   Expect: **no output** (empty).

### Staging smoke (Releaser, after deploy of the final artifact)

Run these against the staging Bot-1 instance (main port 8080). Behavior must be
**identical** to the pre-split baseline.

1. Actuator health:
   ```
   curl -sf http://localhost:8080/actuator/health
   ```
   Expect: HTTP 200, body `{"status":"UP"...}`.
2. Observability stack still up (single-compose reminder — bot redeploy restarts
   Grafana/Prometheus/Loki):
   ```
   curl -sf http://localhost:9090/-/ready ; curl -sf http://localhost:3000/api/health
   ```
   Expect: Prometheus `Prometheus Server is Ready` and Grafana HTTP 200.
3. Strategy registry intact (proves cross-module `@Component`/`@StrategyImpl` scan):
   ```
   curl -sf http://localhost:8080/api/v1/bot-group/../strategy    # StrategyController list endpoint
   ```
   Expect: HTTP 200 and the full strategy list (same count as baseline: martingale
   family + random + weighted + slot strategies). An empty/short list means a
   strategy module class was not scanned.
4. End-to-end round on a known-good game (use a `BETTING_MINI`/`BauCua` group on a
   reachable env — avoid tx7 and the TIP DNS-blocked env): create/start a small bot
   group via `POST /api/v1/bot-group` + `POST /api/v1/bot-group/{id}/start`, then:
   - Expect bots to authenticate (no `WARN Cannot send message, not connected`
     storm beyond the known race), and
   - Expect the per-round INFO session summaries from `SessionAggregationService`
     to appear:
     ```
     grep -E 'StartGame session-entry|EndGame results' logs/*.log | tail
     ```
     Expect: at least one StartGame session-entry and one EndGame results line per
     active round — proving the Jackson message path, strategy decisions, and
     betting all still work across the module boundaries.
5. Prometheus bot metric is being produced:
   ```
   curl -sf http://localhost:8080/actuator/prometheus | grep -E '^bot_' | head
   ```
   Expect: at least one `bot_*` metric sample with a value > 0 after bots have run
   a round.

If steps 1–5 pass, the split preserved behavior. Any failure isolates to the last
phase's moved classes.

---

## Amendment — 2026-08-04 (Compliance, refactor/module-decoupling)

Recording two Phase 5 reverse-edge constraints the original step list under-specified.
Both were flagged obliquely in Findings but the Phase 5 *instructions* contradicted
them; the implementation correctly followed the constraint, not the letter. These are
genuine technical oversights in the plan, not implementation drift.

1. **`AuthStrategyFactory` cannot move to `bot-engine`.** Phase 5 step 2 said "move
   `infrastructure/auth/*` down." But `AuthStrategyFactory` imports
   `domain.environment.model.Environment`, which is an app-owned CRUD `@Document` that
   stays in `bot-app` (never listed for a downward move). Moving the factory into
   `bot-engine` would create a `bot-engine → bot-app` reverse edge — the exact cycle the
   split exists to prevent. Findings (~line 74) already noted this `Environment`
   dependency but Phase 5 did not reconcile it. **Correct seam:** only `AuthProfile`
   (and `domain/bot/auth/*` login-request POJOs) move to `bot-engine`;
   `AuthStrategyFactory` stays in `bot-app`. This is the implemented shape.

2. **`BotMdcTagsMeterFilter` must move DOWN to `bot-engine`, not stay in `bot-app`.**
   Decision #5 and Phase 5 step 3 kept it in `bot-app`. But `BotMetrics` — an
   engine-facing observability class the plan explicitly moves *into* `bot-engine`
   (cycle #2 break, Phase 5 step 2) — references `BotMdcTagsMeterFilter` from engine
   main sources. Keeping the filter in `bot-app` would force `bot-engine → bot-app`.
   The filter's only `com.vingame` dependency is `common.logging.BotMdc` (in `bot-api`),
   so it sits cleanly in `bot-engine`. **Correction to Decision #5:** engine-facing
   observability → `bot-engine` now includes `BotMdcTagsMeterFilter`; only
   `ObservabilityConfig` and `InfoGaugeRefresher` (which import app-layer
   `core.BotStatus` / `botgroup.service.*`) stay in `bot-app`.

Two further Phase 5 refinements are consistent with plan *intent* and need no
correction, only recording: `infrastructure/client/prometheus/*` stays in `bot-app`
(consumed solely by the app `domain/metrics/*` health/RTP layer, not by the engine's
WS/auth/balance plumbing — it is an app concern, not engine core, so Phase 5's literal
"`infrastructure/client/*` down" correctly excludes it); and `RestExceptionHandler`
stays in `bot-app` while the exception *types* move to `bot-api` (it is a
`@RestControllerAdvice` web-layer class, covered by the plan's "do not move REST
controllers down" note — so Phase 2's "move all of `common/exception`" means the
contract types, not the web advice).
