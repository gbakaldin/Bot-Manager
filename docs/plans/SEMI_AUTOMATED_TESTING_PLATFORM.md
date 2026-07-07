# Semi-Automated Testing Platform — Design & Approach

> Status: **Design of record** (strategic). Captures the architecture and phasing
> agreed in design discussion. This is a multi-repo effort; only a slice of it lands
> in this backend repo. Treat the *contracts* and *deployment* sections as binding
> and the *phasing* as the working sequence.

## 1. Problem

Manual QA (product-side, large team) and auto QA (our team, 3 people) must *both*
run before code is prod-ready, and it takes days-to-weeks. The game frontend is
Cocos2d (canvas, low automated testability), so manual QA can't easily be replaced
wholesale — but a large, untested corpus of games plus a 3-person auto-QA team means
regression is the bottleneck. Manual QA today simulates actions by clicking through
the game client; auto QA writes scripted tests in IntelliJ.

## 2. Goal

A **semi-automated testing platform**: QA authors WebSocket-level test scenarios
**once** (visually, no coding), shares them like Postman collections, and runs them —
plain, tweaked, or parameterized with lists of values. One person's test becomes
everyone's. Over time this builds a regression corpus runnable in hours from one
device instead of days across ten people.

Scope is deliberately **Pareto, not total**: the visual path covers "almost
everything," not literally everything. Cases the visual builder can't express stay as
hand-scripted tests authored by auto QA, registered into the **same corpus and
results dashboard** (unified escape hatch). Chasing the long tail into a
GUI-that-is-a-programming-language is an explicit non-goal.

**Business signal:** backend-team auto regression all-green **+** product-side manual
regression all-green (same Mongo, different tag) = release-ready. This is a reporting
correlation, not an enforced gate (for now).

## 3. System topology

Four repos, one shared spine.

| Repo | Role | Deploys? |
|---|---|---|
| **websocket-parser** | Pure library. The testing DSL + runtime primitives live here. | No — it's a lib |
| **bot-manager** (this repo) | Bot orchestration. ×3 by bot-fleet isolation. | Yes |
| **frontend** (Compose Multiplatform) | Authoring + live report UI; graduating into a general back-office app. | Yes (web + desktop) |
| **regression** | Server-side / scheduled test execution. | Yes (1 env, maybe dedicated later) |

Everything produces results that flow **through a backend into Mongo** and are
rendered in the frontend. Interconnection is a feature (one flow, one pane), *provided
the contract seams are disciplined* (§7).

**Frontend targeting:** continues to target web + desktop. The testing feature is
**desktop-JVM-only** — execution needs the JVM `websocket-parser`. Web shows a stub +
download prompt (via `expect`/`actual`), never touching the JVM-only logic. Authoring
and execution are both desktop; web does not have the feature.

**Why execution is client-side (for now):** we lack capacity for server-side runs from
potentially hundreds of QA concurrently, while also managing tens of thousands of
bots. Tests run locally on the QA's desktop. Server-side execution arrives only with a
dedicated regression env.

## 4. Wrap the harness, not core

`websocket-parser` has two layers:

- **core** — a fluent, lambda-dense pipeline DSL (`Scenario.pipeline(ctx).filter(...)
  .waitForMessage(...).onMessage(...).sendAsync(...).compile()`). ~24 public methods
  take a `Supplier`/`Consumer`/`Function`/`Predicate`/builder-callback. "Send message
  X" is always a `Supplier<ActionRequestMessage>` that *constructs a subclass* — no
  name/registry indirection. Hardest thing to serialize.
- **test harness** (`websocket-parser-test`) — orchestration on top of core.
  Connect/auth/accounts/timeouts/reconnect become **config** (no lambdas).
  **`GameBlocks` subclasses** (e.g. `TaiXiuBlocks`) expose named, scalar-parameterized
  domain steps: `joinGame()`, `placeBet(10_000L)`, `awaitGameEnd()`. This *is* the
  step catalog / message-template registry — the `Supplier` is hidden inside the block.

**Decision: wrap the harness at block-composition altitude.** Rationale:

- Connect/auth/orchestration are free (config).
- `GameBlocks` solves the "send message X" registry problem — a block is `name +
  scalar/ValueExpr args`.
- Fits the org: auto QA author blocks in Java; product QA compose them visually.
- Serializable AST shrinks to **~5–6 node families** instead of ~15+.
- The block ceiling *is* the Pareto scope we already accepted.

**Core stays a Java escape hatch.** Do **not** expose the raw pipeline
(`filter/map/peek/then`, raw `Qualifier(Predicate)`, `send(Supplier)`,
`SendMode.UNTIL(pred)`) in the GUI v1. Pipeline shapes no block can express are added
by auto QA as new blocks in Java. This boundary keeps the message-`Supplier` problem
entirely out of the serialized surface.

## 5. Serialization-native primitives (we own the library)

We control `websocket-parser`, so instead of translating over a fixed lambda DSL from
outside, we push **data-only primitives into the library**. The frontend AST
deserializes directly into them; the "interpreter" shrinks to "deserialize + wire."

**Core rule — closed under data:** every serializable primitive's fields are
scalar / enum / path-string / nested-matcher / `ValueExpr`. **Never** a
`Predicate`/`Function`/`Supplier`. The moment a primitive needs arbitrary logic, that's
the boundary — it stays a Java block, not a visual node.

### 5.1 Matchers (new, first-class, `Qualifier`/`Criterion`-shaped)

- **`FieldMatcher`** — existence at a path (`$.a.b[3].c`). Absence via existing `.not()`.
- **`ShapeMatcher`** — partial structural match. `mode: SUBSET | EXACT`; default SUBSET
  (EXACT is brittle — offer, don't default).
- **`ValueMatcher`** — `{path, op, operand}` where `op` is a **bounded enum**
  (`eq/ne/lt/lte/gt/gte/in/range/contains/regex/exists/typeIs`) and `operand` is a
  `ValueExpr` (compares a path to a literal, a captured variable, or another path
  sample). This is the `$eq/$lt/$gt` idea as a library primitive.
- Composition: `and`/`or`/`not` (already in core).

**Consequence — one boolean tree for match + wait + verify.** A verification becomes
"evaluate this matcher against the target message, record pass/fail to the report."
This removes the separate AssertJ-typed-getter assertion track on the visual path — the
same matcher tree serves `filter`, `waitForMessage`, and assertions.

### 5.2 ValueExpr (unified value sum type)

`ValueExpr = Literal | VarRef | Generator | PathSample | Comparison`. Any param slot
accepts any `ValueExpr`. `waitFor(500)`, `waitFor($randomDelay)`, and a
`Random.nextInt(1000,3000)` equivalent are the same schema with a different `ValueExpr`
inside. Kills most combinatorial explosion.

### 5.3 Variables (generic Scope/Bindings)

Today "variables" = mutable fields on a **typed** context POJO (`TaiXiuGameContext`),
mutated inside lambdas — not visually authorable. Introduce a library-level generic
named-slot `Scope`/`Bindings` (the harness's `ConditionContext.put/get` map prototypes
it) plus a `capture(path → varName)` step. `ValueExpr` resolves against it. QA-facing
blocks read/write named slots. **This is the seam that decides the runtime** — go
generic store, not typed context, for the visual path.

### 5.4 Overloads → discriminated unions

`waitFor` (5 overloads) and `onMessage` (3) become tagged variants
(`{op:"waitFor", mode:"condition|duration|durationUnit|future|gate"}`). The rest are
trivial int-vs-`TimeUnit` pairs collapsed to one canonical form. `waitFor(Supplier)`,
`waitFor(future)`, `waitFor(gate)` are runtime-object variants — kept out of the visual
surface or expressed via `Scope`.

### 5.5 Catalog manifest (one registry, three uses)

`websocket-parser` already uses Jackson polymorphism (`@JsonTypeInfo`, CODE/OFFSET/CMD).
Make the serializable primitives polymorphic Jackson types the same way. **One type
registry then drives three things**: (de)serialization, the validation schema, *and*
the frontend catalog manifest (derived from registered subtypes + field-metadata
annotations, served by the backend). Adding a matcher/step = registering one subtype.
Dropdowns render from the manifest; the backend validates writes against it.

## 6. Module split (contract vs. runtime)

Every shared lib splits into a **`-model`** (pure data + validation, everyone) and a
**`-runtime`/`-rendering`** (heavy, JVM/UI only). **Java throughout** the shared libs.

| Artifact | Contains | Consumers | Lang |
|---|---|---|---|
| `scenario-model` | AST/DTOs, JSON Schema, validation, `ValueExpr`, versioning stamp; `TestProgressEvent` (polymorphic), `RunState`, pure `RunReducer` | backend, regression, frontend | Java |
| `scenario-runtime` | AST → harness execution, `ValueExpr` evaluator, `Scope` impl, matcher/verification executor, `TestWatcher` + aggregator + built-in watchers | frontend (desktop), regression | Java |
| `report-model` | report data structure (stored/queried/aggregated) | backend, frontend | Java |
| `report-rendering` | Compose UI for reports | frontend | Kotlin |

- **Backend depends on `-model` only.** It validates on write and can run `RunReducer`
  to persist reports without pulling `websocket-parser` execution or netty.
- `scenario-runtime` matches `websocket-parser`'s language (Java) → zero interop
  friction wrapping the lib. `-model` is Java (backend is the other consumer, Java).
- **Validation lives in `scenario-model`** → backend write-time and frontend build-time
  validation are literally the same code. No drift.
- **Versioning:** `websocket-parser` version = schema version. Keep `scenario-model`
  strictly additive; stamp every scenario with the model version; migrators (when a
  break is unavoidable) live in `scenario-model`.

## 7. The three governed contracts

In a system this coupled, coordination cost concentrates in a few seams. Govern these
three as **versioned APIs** (additive, deprecation windows); everything else is internal
to one repo and cheap to change.

1. **`websocket-parser` serializable-primitive + event contract** (`scenario-model`,
   `report-model`, `TestProgressEvent`/`RunState`). Everyone links it. Library version
   = schema version.
2. **Backend Mongo storage schema** (scenarios, reports, event logs). The contract
   between the three *producers* and the one *consumer* (frontend). The integration hub.
3. **Per-game catalog** (blocks/matchers per game). The interface the **10 product
   teams** plug into. Keep it the *only* thing they depend on.

Stakeholders touched: backend team, auto-QA subteam, management, back-office team, 10
product teams.

## 8. Execution presentation — one event stream, many watchers

Two execution hosts (**local desktop** now; **server/regression** later), one
presentation model. Do **not** couple presentation to a transport.

### 8.1 `TestWatcher` (the pivot)

```java
// scenario-runtime — the runtime depends ONLY on this
public interface TestWatcher {
    void onEvent(TestProgressEvent event);   // must return fast, must not throw
}
```

The runtime never knows about Compose, StateFlow, or STOMP. `TestProgressEvent` is a
pure-Java polymorphic DTO in `scenario-model`.

### 8.2 Taps already exist

- **`PipelineTracer`** (pluggable, has Slf4j/Recording/Composite impls) → step / matched-message events.
- **`TrackedAssertions.attachLiveSink(Consumer<AssertionRecord>)`** → assertion events (already a live sink).
- **`Scenario.addStopListener`** → terminal status. **`captureMessages(Qualifier)`** → matched-message capture. **`runLive(...)`** → live run mode.

A `WatcherAggregator` (one per run) registers as these seams, normalizes into one
ordered stream (monotonic `seq` + timestamp + account/scenario id + **stable step id**),
and fans out via a `CompositeWatcher`.

### 8.3 Everything is a watcher; report = fold(events)

```
CompositeWatcher → [ StateFlowWatcher (now, local),
                     ReportReducerWatcher (report = fold(events)),
                     ConsoleWatcher (IntelliJ/CI),
                     StompWatcher (later, remote) ]
```

The stored report is the **terminal reduction of the event stream** — one source of
truth (events), two consumers (live UI, persisted report). Optionally persist the event
log for **replay** of a completed run with the same renderer.

### 8.4 Compose adapter — StateFlow holds folded `RunState`, not the latest event

```kotlin
// frontend (Kotlin) — the ONLY module that knows StateFlow exists
class StateFlowWatcher : TestWatcher {
    private val _state = MutableStateFlow(RunState.empty())
    val state: StateFlow<RunState> = _state.asStateFlow()
    override fun onEvent(event: TestProgressEvent) {
        _state.update { RunReducer.reduce(it, event) }   // shared pure reducer
    }
}
```

`RunState` (account→scenario→step→assertion tree + status) and `RunReducer.reduce` are
**pure Java in `scenario-model`**. `MutableStateFlow<TestProgressEvent>` would hold only
the latest event and lose the trail — the StateFlow holds the **fold**. Because the
reducer is pure and shared, **local (StateFlow) and remote (STOMP) render identically**:
local folds in-process; remote folds received events with the same reducer.

### 8.5 `TestWatcher` contract rules

Accounts run on parallel virtual threads, so: (1) non-blocking — a slow UI/wire must
never stall a test; (2) non-throwing — the aggregator wraps each dispatch in try/catch
(same discipline as "contain per-session flush throws"); (3) ordered via `seq`;
(4) never-drop for `AssertionEvaluated`/`*Finished` (only skeletal/heavy-payload events
are droppable under volume).

### 8.6 STOMP (later)

Server/regression → client only. Use STOMP heartbeats for liveness. Stream a **skeletal**
event stream always; fetch **heavy payloads** (full matched frames, audit trail) lazily
or gated by verbosity (mirrors the INFO-skeleton vs TRACE-frames logging philosophy).
Per-run topics (`/topic/runs/{runId}`). Watch-scoping folds into future Keycloak.

## 9. Deployment — separate test service

**Decision: deploy the test backend as a separate service**, not folded into
bot-manager. (`websocket-parser` is a pure lib and never deploys; the real fork is
*test-service vs. this bot-manager backend*.)

**Deciding factor — deployment-cardinality mismatch.** bot-manager is ×3 for bot-fleet
isolation (prod / loadtest / other) — an axis testing doesn't share. Testing is ×1,
maybe its own env later. Co-locating forces either dead test code into the prod/loadtest
instances or a feature-flag-off-in-2-of-3 config-drift smell. Reinforced by history:
co-location coupling already caused outages (single-Compose-project blast radius; a
reconnect leak crashing the JVM). QA "abusing" the test service must not share a process
or resource budget with the managers orchestrating the real bot fleet.

**"Separate" must mean, or it becomes a distributed monolith:**

1. **Own deployment unit** — not just another container in the same Compose project
   (that recreates the coupling that burned us).
2. **Shared Mongo instance, disjoint collection ownership** — test-service owns
   scenarios/reports/events; bot-manager owns bots/groups; **games/envs stay
   bot-manager-owned, test-service reads them read-only** (or via bot-manager's API).
   Two services never write the same collections.
3. **Share the contract via the library** (`scenario-model` etc.), never via the
   database schema.

**Not yet: API gateway / service-discovery facade.** Two internal services + a frontend
that already talks to multiple backend URLs (cross-env aggregation polls several today)
doesn't need one. The gateway earns its place with unified auth — **pair it with the
planned Spring Security + Keycloak work**, not now. Keep the decisions separate:
*separate service* = yes today; *gateway* = later, with auth.

**Frontend as back office:** the frontend graduates from bots-only into a dedicated
back-office app. Treat scenario-testing as a **bounded feature slice** with its own model
boundary — don't entangle it with the bots domain. First test of that internal seam.

## 10. Phased approach (sequence of record)

Optimized for multi-team, multi-repo delivery: light up an end-to-end thread early so
every team has a concrete integration target, and validate the reporting-tool tie-in up
front. First deliverable = a single runnable test surfacing as a live FE report.

1. **Runnable Java test in the new runtime lib.** Establish module topology
   (`scenario-model` / `scenario-runtime` split, internal-repo publishing, both build
   tools consuming it) and run a pure-Java harness test from the new lib. Deliverable is
   the **boundary + a representative fixture**, not just "a test runs."
2. **Enrich the harness with the `TestWatcher`.** Define `TestProgressEvent` / `RunState`
   / pure `RunReducer` in `scenario-model`; wire the aggregator from the existing taps;
   stand up `ConsoleWatcher` + `ReportReducerWatcher` to validate the event model against
   a cheap consumer before Compose.
3. **Compose flow watcher + live render.** `StateFlowWatcher` → `StateFlow<RunState>`;
   render live progress/logs/matched-messages/assertions/status. **Natural ship
   milestone** — a better local runner for auto QA before any visual DSL exists.
4. **The DSL — describe test + configure env** (account pools, reconnect, ObjectMapper,
   etc.). Bulk of the project. Env config is the easy, all-scalar part; the
   matcher/`ValueExpr`/variable/catalog work is the hard 80%. **Exit criterion:
   round-trip parity** — a stored JSON scenario runs and produces an identical result
   **and identical event stream** as its hand-written Java twin. Build DSL→execution and
   validate against the reference corpus *before* frontend rendering.
5. **Frontend displays a described test**; highlight **errored** and **fully-validated**
   steps (live per-step highlighting of many concurrent steps risks flicker — a UX
   choice, not a data limit; the data supports per-step status via stable step ids).
6. **Frontend authoring** — parse UI state into the DSL. Hardest UX; pure frontend once
   the DSL + validation + catalog exist. Inverse of Phase 5 over the same AST↔UI map;
   reuses the `scenario-model` validator for build-time checks.

## 11. Cross-cutting requirements (decide early, ordering-independent)

- **Stable step / source ids** on every execution event, from Phase 2. Even before a DSL
  exists (step = Java block; later = AST node), a stable id lets the live report
  correlate assertions/matched-messages to their step without an event-schema migration
  when the DSL arrives. Costs one field now; saves a migration later.
- **The data-only primitives (§5) must exist before Phase 4 can express tests as data.**
  Where exactly they land within the sequence is flexible, but the reference corpus that
  Phase 4 round-trips must be authored in the primitives' vocabulary (not
  AssertJ-typed-getter `verify` / typed contexts), or parity is impossible.
- **Round-trip parity as a standing CI test** once achieved in Phase 4 — run each golden
  scenario both ways (Java twin vs stored JSON), assert identical event streams. Stops
  the DSL and library from silently drifting as primitives are added.
- **Reference corpus is the spine** — Phases 1→6 validate against it. It is
  simultaneously test fixtures, round-trip oracle, and "does the DSL cover real needs"
  checklist. Grow it as primitives are added.

## 12. Open decisions / risks

- **Generic vs. typed context** (§5.3) — committing to a generic `Scope` is what makes
  the visual DSL real rather than a toy; it means QA-facing blocks are written against
  named slots. Confirm before Phase 4.
- **JSONPath library** — JVM-only execution frees us from KMP constraints; adopt a real
  JVM JSONPath lib rather than hand-rolling. Borrow JSONPath/Mongo-operator *semantics*
  (edge cases already litigated) but **store our own typed AST**, not Mongo syntax
  (explicit tagged nodes round-trip to the visual builder cleanly).
- **Event-log persistence** — persist for replay, or fold-to-report only? At minimum
  `report = reduce(events)` so live and stored views can't diverge.
- **Games/envs ownership** — read-only shared collection vs. bot-manager API. Pick per
  the disjoint-ownership rule (§9).
