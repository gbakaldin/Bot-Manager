# Compliance — MODULE_DECOUPLING

Branch: `refactor/module-decoupling` (HEAD aa5f43d)
Plan reviewed: `docs/plans/MODULE_DECOUPLING.md`
Diff reviewed: `git diff main..refactor/module-decoupling` (base `8a5ee1a`)

## Verdict

PLAN_AMENDED

The branch faithfully implements the plan's *intent* — a static parent-aggregator +
5-module split `bot-app → bot-engine → {bot-strategies, bot-messages} → bot-api`, one
runnable fat jar, no behavior change. All reported deviations from the plan's literal
file lists are sound (correct seams). Two of them, however, expose genuine structural
oversights in the plan's Phase 5 step list (reverse-edge constraints the plan
under-specified against its own Findings): the plan is amended to record them. No code
change is required; the implementation already has the correct shape.

## Dependency-graph verification (the core question)

- **Acyclic, exactly as intended.** POM edges: `bot-api`→(none),
  `bot-strategies`→`bot-api`, `bot-messages`→`bot-api`,
  `bot-engine`→`{bot-api,bot-strategies,bot-messages}`, `bot-app`→ all four.
- **No `bot-engine → bot-app` reverse edge.** A full FQCN import-resolver over every
  `import com.vingame.bot.*` in all main sources of the four lower modules, resolved
  against the module that actually defines each class, found **zero** cross-module
  violations. `bot-api dependency:tree` grep for any `bot-*` sibling: empty (Verification
  gate #4 passes).
- **Build is green and produces exactly one fat jar.** `mvn clean install` BUILD
  SUCCESS; `bot-app/target/Bot-1.0.jar` (59M) is the only `Bot-1.0.jar`; the four lower
  modules emit thin library jars (32K–149K).
- **No behavior change.** Every main-source `.java` in the diff is a pure move
  (0/0 numstat rename). The single content-bearing test edit
  (`StrategyDecisionLogLevelTest` path-resolution helper) is test-only and required by
  the layout. Decision #12 (no method/endpoint/wire change) holds.

## Phase-by-phase

### Phase 1 — Parent aggregator + `bot-app` child
Status: implemented
Root `pom.xml` → `<packaging>pom</packaging>` with the 5 modules; annotation-processor
paths + dependency versions hoisted to parent `pluginManagement`/`dependencyManagement`;
`bot-app` owns `spring-boot-maven-plugin` (repackage), `<finalName>Bot-1.0</finalName>`,
and the log4j-core `requiresUnpack`; `lombok.config` kept at repo root; `Dockerfile`
COPY → `bot-app/target/Bot-1.0.jar` (the one deploy-artifact line). `Starter` in bot-app.

### Phase 2 — Extract `bot-api`
Status: implemented (with sound, reported refinements)
Cycle #1 broken exactly as Decision #2 mandates: `config.bot` VOs + `StrategyId`/
`SlotStrategyId` co-located in `bot-api`. Coordination correctly split — only
`CrowdOption` in `bot-api`; `BetCoordinator`/`JackpotScaler`/`ReservationOutcome`/
`RoundBudget` in `bot-engine`. Deviations validated below (all sound).

### Phase 3 — Extract `bot-strategies`
Status: implemented
Concrete strategies + `@Component` factories moved; `StrategyController` stayed up.
`StrategyDecisionLogLevelTest` kept in `bot-app` — sound (cross-module structural test;
matches the plan's "test spanning layers stays in the highest module" note).

### Phase 4 — Extract `bot-messages`
Status: implemented
Concrete brand messages + `request/*` + `GameMessageTypesResolver` moved; base
interfaces/`Has*`/abstract bases stayed in `bot-api`. `g0/g5/gth` were empty in the
source tree and are correctly absent everywhere (no silent loss). Fixtures reconciled
in Phase 5 (below).

### Phase 5 — Carve `bot-engine`
Status: implemented (drove the two plan amendments)
`core/*`, `client/*`+`dto`, `auth` POJOs, `coordination` (minus `CrowdOption`), and the
engine-facing observability set moved down; `ObservabilityConfig`/`InfoGaugeRefresher`,
`BotFactory`, `BotGroupRuntime`, `BotGroupBehaviorService`, `config/client`, controllers,
CRUD domains, mappers, repositories, `Starter` stayed up. Fixture duplication reconciled
(stale `bot-app` copy dropped).

## Deviations from the plan's letter — all validated

| # | Deviation | Judgement |
|---|---|---|
| P2 | `WeightedStrategy` → `bot-api` (plan Phase 3 listed it as a concrete strategy) | **Sound / compile-forced.** It is a `record(StrategyId, weight)` DTO referenced by `StrategyAssignment` (a bot-api contract DTO). Genuinely contract; mislabeled in the plan. |
| P2 | `SlotMessage` abstract base → `bot-api` | **Sound.** Decision #2 places "abstract base messages" in bot-api; `SlotMessage` is one. Concrete slot messages consume it downward. |
| P2 | `CrowdCountSemantic` → `bot-api` (`game/model`) | **Sound.** Enum that is part of the `Game`/crowd contract. Contract, not app/engine concern. |
| P2 | `config.bot` VOs + `StrategyId`/`SlotStrategyId` → `bot-api` | **Matches plan.** This is the Decision #2 cycle-#1 break verbatim. |
| P2 | `RestExceptionHandler` kept in `bot-app` (plan said move all `common/exception`) | **Sound.** It is `@RestControllerAdvice` (web layer) — covered by the plan's "do not move REST controllers down." Exception *types* correctly moved to bot-api. |
| P3 | `StrategyDecisionLogLevelTest` kept in `bot-app` | **Sound.** Cross-module structural test; matches test-placement note. |
| P4 | `/messages` fixtures duplicated; `g0/g5/gth` empty | **Sound.** Empty dirs correctly omitted; fixture handling resolved in P5. |
| P5 | `AuthStrategyFactory` stays in `bot-app`; only `AuthProfile` moves down | **Sound / compile-forced → plan amended.** Factory imports app-owned `environment.model.Environment`; moving it down = reverse edge. |
| P5 | `BotMdcTagsMeterFilter` moved DOWN to `bot-engine` (plan kept it up) | **Sound / compile-forced → plan amended.** Engine's `BotMetrics` references it; keeping it up = reverse edge. Filter only depends on `bot-api`'s `BotMdc`. |
| P5 | `infrastructure/client/prometheus/*` stays in `bot-app` | **Sound.** Consumed only by app `domain/metrics/*` (health/RTP), not the engine; app concern, not engine core. Preserves "engine stays core." |
| P5 | Fixture duplication "reconciled to one owner each" | **Sound; wording imprecise.** `bot-app` stale copy dropped; identical trees remain in `bot-messages` *and* `bot-engine` because both modules' tests consume them and Maven test resources are module-scoped (not transitive). Necessary, not accidental duplication. |

## Drift / plan amendments

Two Phase 5 instructions were structurally impossible as written; the dev correctly
followed the constraint. Amended in `docs/plans/MODULE_DECOUPLING.md` (`## Amendment —
2026-08-04`):

1. **`AuthStrategyFactory`** — Phase 5 step 2 said move all `infrastructure/auth/*`
   down, but the factory imports app-owned `Environment` (a bot-app CRUD `@Document`).
   Falsifiable and verified: the import exists; `Environment.java` lives only in
   `bot-app`. Moving the factory to `bot-engine` forces `bot-engine → bot-app`.
   Correct seam (implemented): `AuthProfile` + `domain/bot/auth/*` down,
   `AuthStrategyFactory` stays up.
2. **`BotMdcTagsMeterFilter`** — Decision #5 / Phase 5 step 3 kept it in `bot-app`, but
   `BotMetrics` (which the plan itself moves into `bot-engine`) references it from engine
   main sources. Falsifiable and verified: `bot-engine/.../BotMetrics.java` references
   `BotMdcTagsMeterFilter`; the filter's only `com.vingame` import is `bot-api`'s
   `BotMdc`. Keeping it up forces `bot-engine → bot-app`. Correct seam (implemented):
   filter moves down; only `ObservabilityConfig`/`InfoGaugeRefresher` stay up.

Both are "structural constraint that makes the planned approach impossible" — the
PLAN_AMENDED bar, not preference. Everything else the dev diverged on is a sound seam
consistent with plan intent and was reported.

## Out-of-scope changes

None. No hot-reload / PF4J / dynamic-classloading / reload-endpoint work leaked in
(Open Items stay out of scope). No package renames (Decision #6 held — every module
keeps `com.vingame.bot.*`). No REST controllers moved down. No production method
bodies, endpoints, or wire formats changed.

## Silently skipped / undocumented?

None found. `g0/g5/gth` absences are legitimate (empty source dirs). The
fixture "one owner each" phrasing is loose but the outcome (each module owns the
fixtures its tests consume; stale bot-app copy removed) is correct and reported. Both
plan-diverging engine moves were reported as "compile-edge-forced" and are exactly that.
