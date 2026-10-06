# Compliance — PLUGIN_HOT_RELOAD_3_4, Phases 3a + 3b (staging deploy D1)

Branch: feature/plugin-hot-reload-3-4 (worktree `.claude/worktrees/plugin-3-4`)
Plan reviewed: `docs/plans/PLUGIN_HOT_RELOAD_3_4.md` (at commit `8a159b7`, last touched before this review)
Diff reviewed: `git diff 30b426a..e2037d4`. `30b426a` is the `feature/aviator-bot` tip, which is
unchanged and already contains `main` @ `f6f5280` (D-18 holds).

## Verdict

PLAN_AMENDED

The diff faithfully implements every 3a and 3b step. **It is accepted as-is and is cleared for D1.**
The plan is amended in place, with one changelog line, for two technical defects. Both are in
**4b's** L-rules and gate, so neither affects D1. Separately, D-7's one UNVERIFIED claim is now
confirmed. See "Amendments to the plan".

Independent build: `git archive e2037d4` into a scratch dir, `mvn -o verify`, JDK 21.0.2.
**BUILD SUCCESS, 3226 tests, 0 failures/errors/skips** (api 148, strategies 90, messages 261,
engine 1114, app 1613). That equals QA-3a's 3a total exactly, so 3b changed the count by 0, as its
gate requires. There are 0 failsafe reports, which is expected before 4b.

## Phase-by-phase

### Phase 3a — boundary moves
Status: **implemented**

| Step | Evidence | Status |
|---|---|---|
| 1 Baseline (D-20) | 3216 at `30b426a` in a clean detached worktree, independently confirmed by QA-3a | ok |
| 2 D-17 snapshot | `a9df393`: `unmodifiableSet(new LinkedHashSet<>(…))`, not `Set.copyOf`. It landed *before* the move, so the move stays R100. Test mirrors the betting twin | ok |
| 3 Move A | `c003237`: 19 × R100, 0 content lines. Exactly the 17 `request/*` + 2 `slot/*` from the placement table. Tests stayed in `bot-messages` | ok |
| 4 Move B | `de87dec`: 3 × R100 (the three registries → `bot-engine`) | ok, see ruling (a) |
| 5 Move C | `1dd4d7b`: 14 × R100. Exactly step 5's list. No shared fixture needed moving: `TestStrategyFactories` is new code in `e855092` | ok |
| 6 D-5 | `HasRefund { long refundFor(String) }` in `bot-api`. `TaiXiuEndGameMessage` returns raw `gR`. `TaiXiuGameBot:224` is `instanceof HasRefund r ? r.refundFor(getUserName()) : 0L`. Javadoc `{@link}` fixed. Unit test added | ok |
| 7 D-4 | Both fallbacks throw the plan's exact strings (`"BettingStrategyFactory not wired — BotFactory always sets it"`, slot twin likewise). Shared helper `TestStrategyFactories` wired into 20 engine test files (plan: "~20") | ok |
| 8 Guard exemptions | Both factory exemptions dropped. The `theGuardIsNotVacuous` existence loop is kept | ok, see ruling (e) |
| 9 CLAUDE.md | Short paragraph under "Plugin registries": the registries live in `bot-engine`, FQNs unchanged | ok (two sentences against the plan's one, which is immaterial) |
| Gate | `git grep -w RandomBehaviorStrategy\|FixedBetStrategy\|TaiXiuEndGameMessage` over engine/app main gives 6 hits, **all comments**. The `bot-api` reverse edge is empty, since `bot-api/pom.xml` has no plugin dependency | ok |

**Class placement table, checked against the tree at HEAD:**
- `bot-messages/…/request/` holds only `RikStock{Request,Bet,Commit}` and `ZicZac{Request,Bet}`.
- `…/slot/` holds only `SlotMessageTypesImpl`.
- `taixiu/*` and `g2/g3/g4` stay plugin-side.
- `bot-strategies` holds exactly the 11 strategy beans plus `WeightedOptionPicker`, `AffinityOptionPicker`, `MartingaleStrategySupport` and `RiskProfile`.
- The registries are in `bot-engine`, and `HasRefund` is in `bot-api`.

The rule is now enforced by the compiler: 3b's scope change (`1688648`) needed **zero** source
edits, which proves 3a moved everything engine/app code names.

### Phase 3b — plugin modules become plugin artifacts
Status: **implemented**

| Step | Evidence | Status |
|---|---|---|
| 1 D-6 scopes | `bot-engine`: both plugin modules `test`. `bot-app`: both `runtime`. Plugin poms: `bot-api`, `spring-context`, Jackson (`jackson-annotations` in messages; databind arrives via `bot-api`), lombok, slf4j (strategies) all `provided`. The dead `jakarta.annotation-api` was removed in `5713ec7`. No plugin main dependency is left at compile or runtime scope | ok, see rulings (b), (c) |
| 2 Enforcer | Root `pluginManagement` holds a `bannedDependencies` rule over `bot-strategies`/`bot-messages` at `compile` and `provided`. It is bound in `bot-api`, `bot-engine` and `bot-app`. **Reproduced here**: setting `bot-app`'s `bot-strategies` dependency to `compile` fails `validate` with `com.mercury:bot-strategies:jar:1.0 <--- banned`, and setting `bot-engine`'s `bot-messages` dependency to `provided` fails the same way | ok |
| 3 D-7 manifest | Root `maven.build.timestamp.format=yyyyMMdd.HHmmss` and `bot.plugin.version=${maven.build.timestamp}`. Both plugin jars carry `Bot-Plugin-Version` and `Bot-Plugin-Name=${project.artifactId}`. `bot-api` and `bot-engine` manifests carry neither, which is correct | ok |
| 4 Nothing else | Dockerfile, compose and `deploy.sh` are untouched. The fat jar still bundles both jars | ok |
| Gate | `unzip -l bot-app/target/Bot-1.0.jar \| grep -cE 'BOOT-INF/lib/bot-(strategies\|messages)-1.0.jar'` gives **2**. Both manifests read `Bot-Plugin-Version: 20261006.113757` (identical) and match the regex. The test total is unchanged at 3226 | ok |

## Rulings on the deviations Dev reported

**(a) `de87dec` / `1dd4d7b` are red by design. Accepted. No squash required.**
- D-3 asks for one commit per move containing **only `git mv`**, and plan steps 4 and 5 list B and C as separate moves.
- The reds follow from that literal wording, and neither was avoidable without breaking "only `git mv`":
  - `bot-strategies` test-compile fails at B;
  - one guard test fails at C, until `bf61dc8`.
- What D-3 actually protects is rename detection across the stack's later edits. That is intact: every move is R100 with 0 content lines.
- Squashing B+C would also satisfy D-3 and would remove one non-compiling commit from bisect. It is the user's call, not a compliance requirement.
- It would rewrite every SHA from `de87dec` on, including the ones that `qa-3a.md`, `review-3a.md` and this file cite. If the user squashes, those citations go stale. For bisect, `git bisect skip de87dec` is enough.

**(b) slf4j is `provided` in `bot-strategies` only. Accepted.**
- D-6's purpose is that "a plugin jar never carries or pulls the contract layer". In other words, every non-test dependency of a plugin module is `provided`.
- `bot-messages` main has no `org.slf4j` / `@Slf4j` reference. Its only logger, `MessageTypesRegistry`, moved to the engine.
- Declaring a dependency that nothing uses would be noise.

**(c) `websocket-parser-core` is `provided` in `bot-messages`. Accepted.**
- It is used directly: 8 `ActionRequestMessage` + 11 `Body` imports in the RIK request bodies.
- It is a parent-resolved library under D-13 (parent-first delegation lists ws-parser explicitly). `provided` is the scope D-6's rule implies.
- It would also arrive provided-transitively through `bot-api`. The dependency tree shows this when `bot-api` is in the reactor. So the explicit declaration is hygiene ("used, declared"), not a widening.
- D-6's list reads as the rule applied to the dependencies known at the time, not as an allow-list.

**(d) L-2 vs `META-INF/maven/**`. Plan amended (relax L-2). No `addMavenDescriptor=false`.**
- Measured: each plugin jar holds, besides classes and the manifest, directory entries and `META-INF/maven/com.mercury/<artifactId>/pom.{xml,properties}`. This is maven-archiver's default.
- L-2 as written would fail on arrival in 4b. That makes it a real plan defect: it assumed a jar layout Maven does not produce.
- Relaxing is better than stripping. If L-2 admits exactly the module's *own* descriptor and fails on any other `META-INF/maven/**` path, the descriptor becomes a shading detector, because shaded dependencies bring their own descriptors. That is stronger than a jar with no descriptors.
- The descriptor does not affect the D-11/D-15 sha256 or version logic.
- The text now also says directory entries are ignored. Without that, L-2 would have failed on `com/` itself.

**(e) `PerBotInfoLogGuardTest.GROUP_LEVEL_EXEMPTIONS = List.of()`. Accepted.**
- Step 8 says to drop the two exemptions and "keep any assertion that the remaining exemptions exist". There are none left. The existence loop stays, vacuous over an empty list but ready for the next entry.
- The separate anti-vacuity `contains(...)` list over the scanned trees is intact.
- QA-3a mutation-checked both directions: a re-added stale path fails, and an added `log.info(` in a strategy fails.

## Out-of-scope changes

- None of substance.
- `5713ec7` applies review-3a's advisories. These are stale-comment fixes plus removal of the dead `jakarta.annotation-api` dependency. That removal sits squarely inside D-6's "plugin poms become the contract" intent.
- The spike report and plan doc commits (`8a159b7`) and the QA/review docs are expected.

## Advisory (non-blocking)

- The plugin poms' comment "no Micrometer at any scope (L-9)" is slightly too strong. `spring-context` 6.2 pulls in `micrometer-observation`/`-commons` at provided scope. The amended L-9 states this precisely. Fix the comment whenever those poms are next touched (4b).
- QA-3a's gap still stands: `bot-engine/…/domain/bot/strategy/` is outside the per-bot INFO guard's scan. It holds only the two once-per-process factory lines today.

## D1 verification — executable as written against this build?

Yes. Each item checked against the code and artifacts:

- **P-0 / C-3 md5 `a72c40f…`:** `StrategyId`, `StrategyCatalog` and every strategy class are byte-identical (moves are R100; strategies untouched), so the catalogue response cannot change.
- **C-2:** the `BettingStrategyFactory initialized`, `SlotStrategyFactory initialized` and `MessageTypesRegistry initialized` lines come from R100-moved classes whose FQNs, and therefore logger names and routing, are unchanged. The diff should be empty.
- **C-4:**
  - `entered session` is still emitted (`BettingSessionStrategy:32`, DEBUG, track 2; staging runs DEBUG).
  - The 119 CRASH group `13c2b858` is valid on this base.
- **C-5:**
  - the new `not wired` string matches exactly what D-4 throws;
  - the other matches of that phrase in `src/main` are javadoc only, so they never reach a log.
  - **Note for the releaser:** "0 *since the restart*" means count only `console.log` lines timestamped after the container's `StartedAt`. A bare `grep -c` over the live file also counts pre-restart lines from the same 2 h roll.
- **V3-0:**
  - reproduced here: `2`, plus two identical `Bot-Plugin-Version: 20261006.113757`;
  - the value is **UTC**, from a build at 15:37:57 +04;
  - both jars must come from one reactor build (a `-pl` rebuild of one module stamps a different value).
- **V3-1:**
  - `PluginClassLoaderMetrics` (`log.info("plugin runtime: version={}, classloader={}"`, metric names `plugin_classloaders_{live,registered_total,reclaimed_total}`) and `BuiltinPluginVersionResolver` are untouched by this diff;
  - expect `1` line with `version=builtin`, and `live=1 / registered_total=1 / reclaimed_total=0`, as stated.

## Amendments to the plan

Edited in place, with one line under a new `## Changelog`:

1. **D-7:** the "UTC (**UNVERIFIED**)" note is replaced by the measured confirmation (`20261006.113757` stamped at 15:37:57 +04).
2. **L-2 (D-14 table):**
   - "contains only classes + MANIFEST" becomes: every *file* entry is a plugin class, the manifest, or the module's own `META-INF/maven/com.mercury/<artifactId>/pom.{xml,properties}`;
   - directory entries are ignored;
   - any other `META-INF/maven/**` path fails as evidence of shading.
   - Original assumption: a jar holds only what the plan listed. Reality: maven-archiver's `addMavenDescriptor` defaults to true.
3. **L-9 (D-14 table) and the 4b local gate:**
   - Original assumption: the plugin modules would have no Micrometer artifact at all. Reality: `spring-context` 6.x, which D-6 mandates, depends on `micrometer-observation`. The old gate `grep -ciE micrometer` returns **4**, not 0.
   - The old gate's `mvn -q … dependency:tree` also prints **no tree at all**, because `-q` suppresses the INFO output. It returned 0 vacuously and would have hidden both this and any real `micrometer-core`.
   - The rule now targets `micrometer-core`/`micrometer-registry-*`, the artifacts that carry `MeterRegistry`. The gate drops `-q`, writes the tree to a file, asserts the tree was printed (`bot-api:jar` count 2), then greps it.
   - Verified here: the new gate gives 0, the old gate run without `-q` gives 4, and the old gate with `-q` prints 0 lines.
