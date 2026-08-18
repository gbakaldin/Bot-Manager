# Code Review — MODULE_DECOUPLING

Branch: `refactor/module-decoupling`
Reviewed diff: `git diff main..refactor/module-decoupling` (base `8a5ee1a`)

## Verdict

PASS (advisory smells only — no `bug`, no `security`)

Rationale: the split is mechanically sound. `mvn clean install` builds all five
modules green, produces exactly one bootable fat jar
(`bot-app/target/Bot-1.0.jar`, 61 MB, no stray `Bot-1.0.jar` under any other
module `target/`), the dependency graph is strictly downward
(`bot-api` has zero upward edges; `bot-engine` does not depend on `bot-app`),
and every production `.java` is a 100%-similarity `git mv`. The only two source
files with a content diff are test-only path-resolution helpers. No production
method body, annotation, wiring, endpoint, property, or wire format changed.
The edge-driven placement deviations all check out as genuinely compile-forced
(see Notes), not shortcuts.

## Findings

### [smell] `bot-api` drags the entire Mongo driver stack onto `bot-strategies` and `bot-messages`
`bot-api/pom.xml:42-45`

Because `Game` stays a `@Document`, `bot-api` declares `spring-data-mongodb` at
**compile** scope. That is transitive, so the two sibling modules that have
nothing to do with persistence inherit the whole stack. Verified via
`mvn -pl bot-strategies dependency:tree`:

```
com.mercury:bot-api:compile
  spring-data-mongodb:4.4.0:compile
    spring-tx:6.2.0:compile
    spring-data-commons:3.4.0:compile
    mongodb-driver-core:5.2.1:compile
      bson:5.2.1:compile
```

`bot-strategies` (pure betting math) and `bot-messages` (Jackson VOs) now compile
and ship against `mongodb-driver-core` + `bson` + `spring-tx`. It compiles and
the plan explicitly accepted it (Decision #10), so this is not a blocker — but it
is the leakiest seam in the split and the one place worth a follow-up. The clean
fix is the deferred pure-POJO `Game` extraction: move the persistence
`@Document`/`@Id` mapping into an app-side entity and keep a driver-free `Game`
record in `bot-api`. Until then, every downstream module carries a database
driver it never calls.

### [smell] Message JSON fixtures duplicated across `bot-engine` and `bot-messages`
`bot-engine/src/test/resources/messages/**`, `bot-messages/src/test/resources/messages/**`

The 34 fixture files that lived once under the old single module now exist twice
— 34 under `bot-engine` and 34 under `bot-messages` (68 total). `bot-messages`
got fresh copies in Phase 4; `bot-engine` received the originals via `git mv` in
the Phase-5 test commit. Maven modules cannot share `src/test/resources`, so
some duplication is unavoidable, but two independent copies of the same wire
fixtures invite silent drift: a server format change updated in one module's copy
but not the other would leave one module's parsing tests green against stale
input. If these fixtures are load-bearing for both message-parsing and
engine-round tests, consider a tiny shared `*-test-fixtures` artifact (or
`build-helper` pointing both modules at one directory) rather than two hand-kept
copies.

### [smell] Split package `com.vingame.bot.infrastructure.observability` across two modules
`bot-engine/.../observability/*` (16 files) + `bot-app/.../observability/*` (5 files)

Cycle #2 forced the observability package to straddle `bot-engine`
(`BotMetrics`, `SessionAggregationService`, the `*SessionStrategy` family,
`BotMdcTagsMeterFilter`) and `bot-app` (`ObservabilityConfig`,
`InfoGaugeRefresher`). One package name now spans two jars. On the flat fat-jar
classpath this is harmless and it is exactly what the plan called for, but it is
a genuine seam wart: it forecloses any future JPMS/module-path move and can
confuse split-package-sensitive tooling. Not worth churn now; flag it so nobody
"fixes" it by inventing a package rename that breaks the single component scan.

### [smell] `.dockerignore` globs are stale after the source move
`.dockerignore:7-8`

```
src/main/resources/namechangeref.js
src/main/resources/application-loadtest.properties
```

Both files moved to `bot-app/src/main/resources/...` in this refactor, so these
two ignore rules no longer match anything. Impact is low because the `Dockerfile`
only `COPY`s the built jar (not the source tree), so build-context contents don't
reach the image — but the intent of these rules (keep the load-test properties
and the name-ref script out of the Docker context) is now silently broken, and
`application-loadtest.properties` is baked into the fat jar regardless. Decision
#11 declared `.dockerignore` "unchanged"; that was the one place where the
mechanical move should have carried a path update. Repoint to
`bot-app/src/main/resources/...` (or drop the now-inert lines).

### [style] Cross-module source-reading test relies on repo-root walking
`bot-app/src/test/java/com/vingame/bot/domain/bot/strategy/StrategyDecisionLogLevelTest.java:170`

`StrategyDecisionLogLevelTest` lives in `bot-app` but asserts log phrases by
reading production `.java` files as text out of `bot-strategies`/`bot-engine`.
The new `resolveSource(...)` walks parents until it finds `bot-app/pom.xml`, then
probes each module root. This is a reasonable, self-healing solution to a
genuine problem (Surefire's CWD is the module dir, the sources are elsewhere),
and the legacy single-module fallback is a nice touch. The residual fragility is
inherent to a test that greps source text rather than asserting on behavior:
it silently no-ops (walk finds nothing → fallback path → `AssertionError "cannot
read source"`) if a class is moved to a not-yet-listed module. Same pattern, same
caveat, in `MetricKeyDashboardParityTest.repoRootRelative`. Acceptable as-is;
noted so the next module carve remembers to extend the module list.

## Notes

**Edge-driven placement deviations — all validated as compile-forced, not shortcuts:**

- `WeightedStrategy` in `bot-api` (plan Phase 3 listed it under "concrete
  strategies" for `bot-strategies`): it is actually a
  `record(StrategyId, double)` value object referenced by `StrategyAssignment`,
  which lives in `bot-api`. Co-location is required; the `{@link
  BettingStrategyFactory}` in its Javadoc is a doc link only, not a compile edge.
  Correct call.
- `CrowdCountSemantic` in `bot-api`: referenced by `Game` (`bot-api`). Forced.
- `SlotMessage` / `StrategyId` in `bot-api`: base contract referenced by the
  message/strategy interfaces. Forced.
- `BotMdcTagsMeterFilter` landed in `bot-engine` (plan said keep it in
  `bot-app`). This is fine code-quality-wise and arguably cleaner: the class
  only depends on `BotMdc` (`bot-api`) + micrometer, both present in
  `bot-engine`. Its registration is unchanged — `ObservabilityConfig` (still in
  `bot-app`, which depends on `bot-engine`) constructs it as a `@Bean`
  (`ObservabilityConfig.java:32`), so the order-sensitive `MeterFilter`
  registration still happens in the same place. No behavior change.
- `AuthProfile` down in `bot-engine`, `AuthStrategyFactory` up in `bot-app`:
  clean inversion — the engine's client consumes an `AuthProfile` passed in,
  while `AuthStrategyFactory` (which imports the app-owned `Environment`) builds
  it in the app. The green build proves there is no upward edge from the engine
  to the factory.

**POM plumbing is correct.** Annotation-processor paths (mapstruct-processor +
lombok 1.18.34 + lombok-mapstruct-binding 0.2.0) are hoisted once into parent
`<pluginManagement>` (`pom.xml:110-126`) and every child activates
`maven-compiler-plugin` bare, so all inherit them — Lombok `@Builder`/getters and
MapStruct both resolve across modules (build confirms). Versions are centralized
in parent `<dependencyManagement>`; no child re-declares a version, so no drift.
`spring-boot-maven-plugin` `repackage` + the `log4j-core` `requiresUnpack` are
owned by `bot-app` alone (`bot-app/pom.xml:128-139`), and `lombok.config` stays
at the repo root so its `copyableAnnotations += @JsonProperty` bubbles into every
module. `bot-messages` correctly relies on `jackson-databind` transitively via
`bot-api` and declares only `jackson-annotations` directly — no redundancy.

**No accidental production behavior change found.** Every `src/main` file is an
`R100` rename. No root-level `src/` residue remains, and there are no duplicated
production class basenames across modules (no class was copied-and-left-behind).
`application.properties` and all resources moved as pure renames.
