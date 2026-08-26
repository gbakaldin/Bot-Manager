# Compliance — PLUGIN_HOT_RELOAD (Phase 1)

Branch: `feature/plugin-hot-reload` (4 commits off `254d56b`, head `4cc152e`)
Plan reviewed: `docs/plans/PLUGIN_HOT_RELOAD.md` (as landed at `da2a959`)
Diff reviewed: `git diff 254d56b..feature/plugin-hot-reload`
Scope: **Phase 1 only** (AD-1 … AD-11). Phase 2 (2a–2d) is out of scope and must not
have been started.

## Verdict

**PLAN_AMENDED**

The Phase 1 diff implements all ten plan steps and honours AD-1 through AD-11 as
written; no code change is required. One amendment was made to the plan — the
per-phase local gate's test baseline was factually wrong (1485 stated, 1866
measured). See *Amendments to the plan*.

## Phase-by-phase

Phase 1 is a single phase of ten numbered steps; each is classified below.

### Step 1 — `bot-api`: `PluginVersions`, `PluginVersionResolver`, `BotMdc.PLUGIN_VERSION`
Status: **implemented**
`PluginVersions.BUILTIN = "builtin"` and the single-method `PluginVersionResolver`
land in `com.vingame.bot.common.plugin`, as specified. `BotMdc` gains the
`PLUGIN_VERSION` constant, a separate `setPluginVersion(String)` helper (rather than a
ninth argument on `set(...)`, exactly as the step asks), and `MDC.remove(PLUGIN_VERSION)`
in `clear()`. It is **not** on `GROUP_LEVEL_KEYS`, and `BotMdcTest` pins that by
assertion rather than by comment.

### Step 2 — `BotConfiguration.pluginVersion`
Status: **implemented**
Field added, with `resolvePluginVersion()` supplying `PluginVersions.BUILTIN` for null
*and* for empty — the same shape as the neighbouring `resolveProductCode()`. Not
persisted (see AD-10 below). See *Adjudication 1* for the "never written" question.

### Step 3 — `bot-engine`: `Bot.getPluginVersion()` and the MDC call site
Status: **implemented**
`Bot.getPluginVersion()` delegates to `BotConfiguration.resolvePluginVersion()`.
`BotMdc.setPluginVersion(getPluginVersion())` is at `Bot.java:281`, i.e. immediately
after the `BotMdc.set(...)` block and **before** the `mdcSnapshot =
MDC.getCopyOfContextMap()` capture — the ordering the plan calls load-bearing, and the
inline comment says why.

### Step 4 — `bot-app`: `BotGroupRuntime.startBot`
Status: **implemented**
One line, `BotMdc.setPluginVersion(config.resolvePluginVersion())`, inside the
virtual-thread body after the `BotMdc.set(...)` call. Resolves through the same method
as `Bot`, so the two sites cannot disagree.

### Step 5 — `PluginClassLoaderMetrics`
Status: **implemented**
`@Component` in `bot-app/.../infrastructure/observability/`, modelled on
`AsyncQueueMetrics`: `register(String, ClassLoader)` storing a `VersionedRef extends
WeakReference<ClassLoader>` against a `ReferenceQueue`, a 10 s virtual-thread scheduled
sampler draining it, `@PreDestroy` shutdown, all three meters registered eagerly and the
strong `tracked` set that keeps the weak references themselves reachable (without it the
queue never enqueues and `reclaimed_total` never moves — the failure mode is called out
in the javadoc). `@PostConstruct` registers `getClass().getClassLoader()` and emits the
AD-9 line. It resolves the version through the `PluginVersionResolver` bean rather than
naming `PluginVersions.BUILTIN` directly, which is the seam the plan wired here.

### Step 6 — `bots_by_plugin_version`
Status: **implemented**
`MultiGauge` registered in `InfoGaugeRefresher.registerInfoGauges`, rows rebuilt in
`refresh` with `overwrite=true`, tags `{botGroupId, environmentId, product,
pluginVersion}`, value = bot count. Backed by the new
`BotGroupBehaviorService.countBotsByPluginVersion()`, written in the same shape as
`countManagedBotsByEnv()` — and, unlike that method, with **no skip branch**, so
`sum(bots_by_plugin_version)` is arithmetically equal to `getTotalManagedBots()`
(`bots_managed`). Verification P1-5 is therefore achievable and meaningful. The name is
on `BotMdcTagsMeterFilter.AGGREGATE_METER_NAMES` (AD-4).

### Step 7 — Loki / track 1 only
Status: **implemented**
One `pluginVersion` MDC field added to `bot-app/src/main/resources/log4j2-json-template.json`.
Neither `log4j2.properties` twin is in the diff (`git diff --name-only` over the branch
returns no `log4j2.properties`), so `Log4j2TwinConfigTest` is untouched and track 2's
`PatternLayout` still renders the same closed key list. Verification P1-6's expectation
of **0** hits in `detail.log` holds.

### Step 8 — `MetaspaceGrowth`
Status: **implemented** — verified byte-for-byte against AD-7, see below.

### Step 9 — Grafana `plugin-runtime.json`
Status: **implemented**
`grafana/provisioning/dashboards/plugin-runtime.json` (uid `plugin-runtime`, title
"Plugin runtime"), in the folder the provider scans. Every panel the step names is
present: metaspace used + compressed class space (one panel, two series),
`jvm_classes_loaded_classes`, `rate(jvm_classes_unloaded_classes_total[1h])`, all three
`plugin_classloaders_*` (live; created vs reclaimed), and `bots_by_plugin_version`
summed by version. Two additions beyond the step: a text panel explaining the dashboard,
and a panel graphing `MetaspaceGrowth`'s own `delta(...)` expression plus a
`bots_managed` cross-check series on the last panel. Both are within the step's intent
(the delta panel is the alert's expression; the `bots_managed` overlay is verification
P1-5 rendered), not scope creep.

### Step 10 — Tests
Status: **implemented**
- `RuntimeMetricsExposedToAlertsTest` extended with two cases pinning
  `jvm_memory_used_bytes` *including the `area="nonheap"` and `id="Metaspace"` label
  values* — the right thing to pin, since the rule selects on them — plus both class-count
  series, each with a non-zero value assertion.
- `PluginClassLoaderMetricsTest` — four cases: the degenerate 1/1/0 baseline (which is
  literally verification P1-4), the negative case that the application classloader is
  *not* reclaimed under GC pressure, the drop-and-reclaim spike rehearsal with a 10 s
  bounded poll and an `Assumptions.assumeTrue` escape exactly as the step prescribes, and
  a name-prefix guard.
- `PluginVersionCardinalityTest` — an A/B fleet replay in `ProductLabelCardinalityTest`'s
  shape and module, asserting equal `bot_*` series counts with and without the MDC key,
  *plus* the stronger direct assertion that no meter carries the tag at all (the count
  alone would also pass if the label were applied uniformly).
- `PerBotInfoLogGuardTest` and `Log4j2TwinConfigTest` are not in the diff and the build
  is green, so they pass unchanged.

**Build gate, re-run independently by this review:**
`JAVA_HOME=…/openjdk-21.0.2 mvn clean install` → `BUILD SUCCESS`, all five modules,
**0 failures / 0 errors**, **1884 tests**. `mvn -q -pl bot-api dependency:tree | grep -E
'bot-app|bot-engine|bot-strategies|bot-messages'` → no output; the contract module still
has no reverse edge.

## Architecture Decisions — checked against the code

| AD | Verdict | Evidence |
|---|---|---|
| **AD-1** — no new failure mode, no class loaded from anywhere new, behaviour identical | **honoured** | Nothing in the diff loads a class. `PluginClassLoaderMetrics.register` only *wraps* an already-loaded loader in a `WeakReference`; the only `URLClassLoader` in the branch is inside the test. `Dockerfile`, all `pom.xml`s, `docker-compose*`, and both `log4j2.properties` twins are absent from `git diff --name-only`. Additions to running behaviour are: one MDC key (which track 2 does not render and track 1 renders as one extra JSON field), one MultiGauge, three JVM-scope meters, one 10 s virtual-thread sampler, one startup line. No existing code path changes its result. |
| **AD-2** — metaspace/class-count series *adopted*, not rebuilt | **honoured** | `PluginClassLoaderMetrics` publishes only `plugin_classloaders_{live,created_total,reclaimed_total}`. There is no hand-rolled metaspace or class-count gauge anywhere in the diff; the dashboard and the alert both read Micrometer's `jvm_*` names, and the only new code touching them is the *test* that pins their exposition. Nothing duplicates or shadows them. |
| **AD-3** — weak-reference accounting, keyed as specified | **honoured** | `VersionedRef extends WeakReference<ClassLoader>` + `ReferenceQueue`; all three meters carry exactly the `pluginVersion` tag, with the meanings the AD's table gives. `live` is `created − reclaimed` maintained by queue drain, i.e. reachability, not bookkeeping. The javadoc restates the lower-bound reading discipline the plan's Implementation Notes demand. `PhantomReference` is imported only to be named in the javadoc explaining why it was rejected — harmless, but it is an otherwise-unused import. |
| **AD-4** — meter names not `bot_`-prefixed; `bots_by_plugin_version` on the allow-list | **honoured** | The three names begin `plugin_`, pinned by a test. `"bots_by_plugin_version"` added to `AGGREGATE_METER_NAMES`, with the belt-and-braces reasoning the AD gives (it does not match the `bot_` prefix check, since `BotMdcTagsMeterFilter.PREFIX_BOT` is `"bot_"` and the name starts `bots`). |
| **AD-5** — `pluginVersion` **NOT** on `BotMdcTagsMeterFilter`'s tag list | **honoured — the distinction holds exactly** | `BotMdcTagsMeterFilter.map()` still calls `addTagIfPresent` for six keys and only six: `BOT_GROUP_ID`, `ENVIRONMENT_ID`, `PRODUCT`, `GAME_TYPE`, `GAME_ID`, `GAME_NAME`. `PLUGIN_VERSION` is absent. The **only** edit to that class is one entry in `AGGREGATE_METER_NAMES`, which is an *exclusion* set consulted before the prefix check (`if (AGGREGATE_METER_NAMES.contains(name)) return id;`) — it makes the filter do strictly *less*, never more. Dev's description is accurate, and the two things are what AD-5 and AD-4 respectively ask for: AD-5 forbids the tag list, AD-4 requires the allow-list entry. `PluginVersionCardinalityTest` locks both directions. |
| **AD-6** — no `-XX:MaxMetaspaceSize` in Phase 1 | **honoured** | `Dockerfile` is not in the branch diff at all; `JAVA_OPTS`/entrypoint are unchanged. Confirmed by `git diff 254d56b..HEAD --name-only`. The alert comment records *why* the cap is deferred and where it lands (step 4), which is the part AD-6 says matters as much as the number. |
| **AD-7** — one alert ships, the second is written down and deferred | **honoured — matches byte-for-byte** | Shipped in the `bot-manager-jvm` group (between `JvmThreadsHigh` and `LogQueueSaturated`): `expr: delta(jvm_memory_used_bytes{area="nonheap",id="Metaspace"}[24h]) > 52428800`; `for: 1h`; labels `job: bot-manager`, `severity: warning`, `audience: internal`. Expression, window, threshold, `for`, and both AD-named labels are identical to AD-7, with `job: bot-manager` per house style. Commented as a first guess in the style of `alerts.yml:414-416`. `PluginClassLoadersRetained` is written into that comment as `sum(plugin_classloaders_live) > 2 for: 2h` and explicitly deferred to step 6 — not shipped. |
| **AD-8** — `pluginVersion` reaches Loki, track 1 only | **honoured** | One field in `log4j2-json-template.json`; `logging/log4j2.properties` and the in-jar twin untouched. |
| **AD-9** — exactly one new INFO line, once per JVM | **honoured — verified against the source** | `git diff 254d56b..HEAD -- "*.java" \| grep '^+.*log\.info'` returns **exactly one** hit: `PluginClassLoaderMetrics.start()`'s `log.info("plugin runtime: version={}, classloader={}", version, System.identityHashCode(loader))`. It sits in a `@PostConstruct` on a singleton `@Component`, so it fires once per JVM, and the message is the string verification P1-2 greps for with an expected count of 1. It is in `bot-app/.../infrastructure/observability/`, outside both directories `PerBotInfoLogGuardTest` scans — so the guard passing is a consequence of the placement being right, not the reason to believe it. No `log.info` was added to `bot-engine/.../domain/bot/core/**` or `bot-strategies/.../domain/bot/strategy/**`; the only edit to `Bot.java` is one `setPluginVersion` call and one accessor. |
| **AD-10** — `pluginVersion` is runtime state, cannot reach Mongo | **honoured** | `BotConfiguration` is a Lombok `@Value @Builder` with no `org.springframework.data` import, no `@Document`, no `@Id`, no `@Field` (grep: zero hits). It is referenced from exactly three production classes — `BotFactory`, `BotGroupRuntime`, `BotGroupBehaviorService` — and by no `@Document`-annotated type, so it is not reachable as a nested property of any persisted entity either. No repository, mapper or DTO in the diff. `BotHealthDTO` correctly does **not** gain the field. |
| **AD-11** — `PluginVersions.BUILTIN` is the single source | **honoured** | `grep -rn '"builtin"'` across all five modules' `src/main` returns exactly one occurrence in code — the constant's own declaration. The three other hits are javadoc prose in `BotMdc`, `InfoGaugeRefresher` and `Bot`. Both readers reach the value through it: `BuiltinPluginVersionResolver` returns `PluginVersions.BUILTIN`, and `BotConfiguration.resolvePluginVersion()` defaults to it. No literal duplicates it. |

## Phase 2 — confirmed not started

`PLUGIN_PLAN.md` is still at the repo root (AD-22 untouched);
`GameMessageTypesResolver.java` still exists; `@StrategyImpl` still declares
`StrategyId value()`; `BettingStrategyFactory` still uses `EnumMap`; `bot-messages/pom.xml`
still has no `spring-context`. No Phase 2 file appears in the branch diff.

## Drift

None requiring code changes. Two items were flagged by Dev for adjudication; both are
resolved below, one in Dev's favour on the plan's own text and one by amending the plan.

### Adjudication 1 — `BotConfiguration.pluginVersion` is never written in Phase 1

**Faithful to the plan as written. No deviation, no amendment.**

The plan's Phase 1 step list is exhaustive and it never asks for the resolver to be
threaded into the bot-build path. Step 2 asks only for the field, and specifies its
semantics as *"defaults to `PluginVersions.BUILTIN` when null"* — a default that only has
a job to do precisely because nothing sets the field. Step 3 says `Bot.getPluginVersion()`
reads *"the configuration with the builtin default"*, which is the same instruction from
the other end. Step 5 is the only place the resolver is wired, and it wires it into
`PluginClassLoaderMetrics`. AD-10 reinforces it by describing `BotConfiguration` as built
per bot at group start and the field as carrying no reader until step 5. So the code Dev
wrote is what the plan describes; a version that injected `PluginVersionResolver` into
`BotGroupBehaviorService.createSingleBot` would have been the addition, not the baseline.

It is also observably correct today: with one classloader the constant and the resolver
return the same string, every bot labels `builtin`, and
`sum(bots_by_plugin_version) == bots_managed` holds by construction.

**Forward note for step 4/5, not a Phase 1 defect.** `resolvePluginVersion()`'s null
default becomes a silent trap the moment the answer can vary: an unset field will keep
reporting `builtin` for bots actually built from N+1, and the symptom is a drain that
never appears to progress. Dev has documented exactly this on the field ("Step 4 … is
where the builder starts setting it"). Step 4's plan should carry it as an explicit step
rather than relying on that javadoc.

### Adjudication 2 — the test baseline

**Dev is right; the plan is wrong; the plan has been amended.**

Independently measured, not taken from the handoff. A detached worktree at `254d56b`
built with `mvn test` and counted across every module's surefire XML gives **1866**
(`bot-api` 125, `bot-strategies` 111, `bot-messages` 136, `bot-engine` 420, `bot-app`
1074). The branch head gives **1884**, so Phase 1 adds **18** tests. Both builds are
green. The plan's `≥ 1485` is not a description of this repository at the branch point.

This is a plan defect and not an implementation one on the asymmetric test: it is a
falsifiable claim about the codebase that measurement refutes, and there is no
implementation of Phase 1 that would have made 1485 true. It is also not cosmetic — the
figure is the hand-off gate for *every* sub-phase in the document, and at 1485 it would
green-light a build that had lost 381 existing tests.

## Out-of-scope changes

None material. Two small things worth naming, neither warranting a send-back:

- `docs/plans/PLUGIN_HOT_RELOAD.md` (879 lines) lands in commit `da2a959` on this branch.
  That is Architect-1's deliverable riding the same branch, not Dev drift.
- The Grafana dashboard carries a text panel and two series (the `delta(...)` panel, the
  `bots_managed` overlay) beyond the literal panel list in step 9. Both render things the
  plan asks operators to look at — the alert's own expression and verification P1-5's sum
  check — so they are within the step's intent.

`PluginClassLoaderMetrics` imports `java.lang.ref.PhantomReference` solely to reference it
from a javadoc `{@link}` explaining why it was rejected. Legal, and the explanation is
worth having; flagged for the Reviewer as a style call, not a compliance issue.

## Amendments to the plan

One amendment, appended to `docs/plans/PLUGIN_HOT_RELOAD.md` as
`## Amendment — 2026-08-26`, plus a marked correction of the single figure in the
Verification section that it supersedes.

**A1 — the per-phase local gate's test baseline is 1866, not 1485.** The measurement, the
per-module breakdown, the commit it was taken at, and the reason a wrong baseline is a
real defect (it would pass a build missing 381 tests) are recorded in the amendment. No
Architecture Decision, no plan step, and no verification step other than that one number
was touched.
