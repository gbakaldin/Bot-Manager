# QA — MODULE_DECOUPLING

**Verdict:** PASS
**Build:** `mvn clean install` → 1485 tests, 0 failures, 0 errors, 0 skipped (reactor: 5 modules, BUILD SUCCESS)

This is a pure structural refactor (single module → 5-module reactor via `git mv`
renames, no production logic change). QA scope was **test-parity + build
soundness**, not new feature coverage. No new tests were written — the pre-split
suite is the coverage, and the job was to prove the move preserved it. Findings
below establish that it did.

## Test-count reconciliation (primary)

Per-module surefire totals summed to the pre-split baseline of **1485**:

| Module | tests | failures | errors | skipped |
|---|---|---|---|---|
| bot-api | 98 | 0 | 0 | 0 |
| bot-strategies | 111 | 0 | 0 | 0 |
| bot-messages | 136 | 0 | 0 | 0 |
| bot-engine | 339 | 0 | 0 | 0 |
| bot-app | 801 | 0 | 0 | 0 |
| **Reactor total** | **1485** | **0** | **0** | **0** |

Corroborating parity evidence (main @ base vs `refactor/module-decoupling`):

- `*Test.java` files: 149 on both; total `.java` test files (incl. 1 helper): 150 on both.
- Set of test-class basenames is **identical** (`diff` empty) — no test file
  dropped or left behind in the old `src/test` tree.
- `@Test` annotations: 1394 on both. `@ParameterizedTest`: 16 on both. (The 16
  parameterized classes expand to the delta between 1394+16 declarations and the
  1485 executed cases — consistent, nothing lost.)
- **No `@Disabled`** anywhere on either branch (0 on both).
- **No surefire/skip/include/exclude config** in any POM (`grep` clean) — nothing
  can be silently excluded by build config.
- Every module that received test classes compiled and executed them (all 5 have
  populated `surefire-reports`); no module has tests that failed to compile for a
  missing test-scoped dependency (would have failed the build).

## Build reproducibility

- `mvn clean install` (JAVA_HOME openjdk-21.0.2) green end-to-end; reactor order
  bot-api → bot-strategies → bot-messages → bot-engine → bot-app.
- Exactly one bootable fat jar: `bot-app/target/Bot-1.0.jar` (59 MB), manifest
  `Start-Class: com.vingame.bot.Starter`, `Main-Class: ...JarLauncher`.
- `find . -name 'Bot-*.jar' -path '*/target/*'` returns **only** that jar — no
  stray `Bot-1.0.jar` under any other module.

## Dependency-direction integrity

Declared intra-project edges match the plan exactly, strictly downward:

- bot-api → (none)
- bot-strategies → bot-api
- bot-messages → bot-api
- bot-engine → bot-api, bot-strategies, bot-messages
- bot-app → bot-api, bot-strategies, bot-messages, bot-engine

`mvn -pl bot-api dependency:tree | grep` for any sibling → empty (contract module
depends on nothing). No lower module imports an upper package.

## Test-only changes introduced by the refactor

- **`StrategyDecisionLogLevelTest`** (in bot-app) — CWD→repo-root path fix
  (`resolveSource`) walks parents to the repo root then probes each module's main
  source root. Correctly follows strategy sources that moved to `bot-strategies`
  while the test itself stays in bot-app. Falls back to the legacy single-module
  layout. Assertion semantics unchanged (same phrase/level checks). Test executes
  and passes. Not a coverage weakening.
- **`MetricKeyDashboardParityTest`** (in bot-app) — `repoRootRelative` walks
  parents until the repo-root Grafana dashboards resolve, independent of the
  Surefire module CWD. Assertions unchanged; passes.
- **`/messages` fixture relocation** — the 34 message JSON fixtures now live in
  **both** `bot-engine/src/test/resources/messages` and
  `bot-messages/src/test/resources/messages`. This is correct, not a stale dupe:
  test resources are per-module classpath, and **both** modules have consuming
  tests (13 bot-engine core-bot tests, 9 bot-messages deserialization tests). Each
  consumer resolves fixtures from its own module — exactly one owner per consumer.
  Old root `src/test/resources/messages` copy is gone (no third stale copy in
  bot-app/bot-api/bot-strategies). All 68 relocated fixture files are
  **byte-identical** to the main baseline (sha check, 0 mismatches).
  `display_names.txt` (production resource) is a single copy in `bot-engine`,
  co-located with its only consumer `DisplayNameService`.

## Coverage of the diff

- 246 `src/main` `.java` renames are R100 (pure moves, **zero content edits** —
  `git diff --diff-filter=M` on main java is empty). Production resources
  (`application.properties`, `log4j2.properties`, `log4j2-json-template.json`,
  `namechangeref.js`) relocated to bot-app as R100; `display_names.txt` to
  bot-engine. No behavior surface to test beyond what the existing 1485 already
  exercise, all of which pass in their new module homes.

## Optional dependency-direction guard — recommendation: do NOT add

Not worth adding for this refactor. The Maven **reactor already hard-fails on any
cyclic module edge** — a reverse edge (e.g. `bot-engine → bot-app`) is a build
error, not a silent regression. That, plus the plan's existing
`mvn -pl bot-api dependency:tree` gate, already prevents reintroducing a reverse
edge. A dedicated ArchUnit/pom-parsing guard would require new test infra/deps for
a pure structural refactor — over-engineering. If the team later wants a
finer-grained *package*-level rule (beyond module cycles Maven already catches),
that belongs in a separate hardening pass, not this one.

## Gaps

- **Runtime boot smoke not executed by QA.** The plan's Verification includes
  `java -jar bot-app/target/Bot-1.0.jar` (expect `Started Starter`) and the
  staging behavior smoke (actuator health, strategy-registry list, live round,
  Prometheus `bot_*` metric). These require a running MongoDB / reachable game
  env and are the **Releaser's** on-server gate. Cross-module Spring
  `@ComponentScan` + Jackson subtype registration are exercised indirectly by the
  801 bot-app tests (which boot `Starter` as `@SpringBootConfiguration` — visible
  in the surefire log) and the bot-engine/bot-messages message-parsing tests, all
  green; but the full-round e2e is deferred to staging by design.

## Failures

None.
