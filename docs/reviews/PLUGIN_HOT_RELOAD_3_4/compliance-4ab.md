# Compliance — PLUGIN_HOT_RELOAD_3_4, Phases 4a + 4b

Branch: `feature/plugin-hot-reload-3-4` (worktree `.claude/worktrees/plugin-3-4`)
Plan reviewed: `docs/plans/PLUGIN_HOT_RELOAD_3_4.md` at `21eed9e` (amended in place by this review, in the same commit as this file)
Diff reviewed: `git diff a0006c8..21eed9e`. a0006c8 is the D1 release log. 3a and 3b passed `compliance-3ab.md` and are live on Bot-1.
Inputs: `review-4a.md` (PASS) and `qa-4a.md` (PASS). QA-4b and review-4b were running in parallel and were not read. Dev's 4b build log (`scratchpad/4b/final.log`) shows failsafe 28/28 green. All 8 ITs ran once in `bot-plugin-dist`, under execution `default`, and none was double-bound. Dev's dependency tree (`deptree.txt`) shows `bot-api` ×2 and `micrometer-(core|registry)` ×0. Not rebuilt here.

## Verdict

**PLAN_AMENDED**

The diff implements every 4a and 4b step, every decision, every L-rule due by 4b, and both local gates. I accept the whole diff. Four of Dev's deviations correct real technical mistakes in the plan, so the plan was edited in place, with one changelog line:

- the D-13 scan;
- the L-3 consequence;
- the L-13 frames;
- the D-15 stamp site.

Separately, two D2 verification steps (metaspace, V4b-1) could not be run as written or did not discriminate, so they were fixed too. I applied the docs patch (the `bots_managed` greps and the release-d1 correction) after checking it. Nothing in the code needs to go back to Dev.

**D2 must run `bot.plugins.mode=classpath`.** It does without any action: the default is classpath, and compose does not set the mode until 4c. D2 needs **no** host `plugins-dist` directory, no compose change, no Dockerfile change and no `deploy.sh` line.

## Phase-by-phase

### Phase 4a — `PluginRuntime` seam, classpath bundle, per-bundle registries
Status: **implemented**

| Step | Result |
|---|---|
| 1 `PluginBundle` / `PluginRegistries` / `PluginRuntime` in `bot-engine` `…infrastructure.plugin` | Present. `PluginRuntime.current` is a `final` field. `PluginRegistries.build` is the single D-10 point. `typeFactory()` is derived from the bundle instead of being a fifth component (plan text amended; see Drift 4a-ii). |
| 2 Registries drop `@Component`, `create` → `bundle.newInstance`, strings byte-identical, step-5 javadoc updated | Present. The diff changes no registry string literals, only comments. The `MessageTypesRegistry` javadoc now says the `volatile` plan is superseded and that `PluginRuntime` is the swap point. |
| 3 `ClasspathPluginBundle`: `builtin`, app loader, no-op close, D-12 text verbatim | Present (`NO_PLUGIN_BEANS`). |
| 4 `PluginRuntimeConfiguration`: mode switch, unknown fails, runtime + resolver beans, `BuiltinPluginVersionResolver` deleted | Present. |
| 5 Consumers read `current()` per call; `BotFactory` passes factories + `typeFactory` | Present in `BotFactory`, `BotGroupConfigValidationService` (one read per validation) and `StrategyCatalog`. |
| 5b Every per-bot mapper on the bundle's `TypeFactory`, default fallback for fixtures; L-8 tests; close order | Present, through `Bot.newMessageMapper()`. All four sites use it, and `TaiXiuGameBot` inherits it. `PerBotMapperTypeFactoryTest` and `PluginBundleCloseTest` exist. |
| 6 D-15 metrics + boot line + version stamp | Metrics: one registration of the accepted bundle's loader. The boot line keeps its prefix and appends `, source=…, jars=[…]`. The stamp moved to `BotFactory.createBot` (plan amended, Drift 4a-i). |
| 7 `ApplicationContextLoadsTest` via `PluginRuntime`; test helper | Present. The helper is `TestPluginRuntimes.of(...)` in test scope, not a production `PluginRuntime.of`, which follows the review-4a style note. |
| 8 L-10 `RootContextHoldsNoPluginRefsTest` | Present, and strengthened by QA to a 4-hop deep scan. |

Local gate: QA-4a measured 3,264 surefire before its own tests (above the 3b total), with `PluginClassLoaderMetricsTest` and the `InfoGauge*` tests unchanged and green, using the kept package-private seam.

### Phase 4b — isolated bundle loader, tested but not yet used
Status: **implemented** (with recorded corrections to D-13 and L-13)

| Step | Result |
|---|---|
| 1 `IsolatedPluginBundleLoader`, D-11/D-13, one ERROR per rejection, one WARN on builtin fallback, throw naming both dirs | Present. The text matches V4-4 / V4-9's greps (`plugin bundle <path> rejected:`, `running the image's built-in bundle V`). Invalid candidates are reported first and valid ones are tried in descending version order, which is D-11's selection semantics. A rejected candidate is closed, and a close failure is suppressed onto the rejection rather than replacing it. |
| 2 `isolated` reachable only via the property, default `classpath` | Present (`application.properties` and the `@Value` default). |
| 2b L-11: Netty pre-start, group injected, `ClientFactory` throws in isolated mode, `GameMsClient` virtual; `NettyPrestartTest`, `ClientFactoryRequiresGroupTest` | Present. Isolated mode also *checks* the pre-start (`requireStarted`). `BotFactory` sets `requireSharedEventLoopGroup = bundle.isolated()`. |
| 3 `bot-plugin-dist`: last module, test-only `bot-engine` dep, no plugin deps, log4j test deps, dependency `copy` into `target/plugins-dist/${bot.plugin.version}/`, failsafe with explicit executions + `--add-opens`, ITs against the shipped dir, five selection tests with fixtures under `target/` | Present. The 7 selection tests cover the five the plan requires plus "absent mount" and "no valid candidate". `21eed9e` empties `plugins-dist` at `prepare-package`, so a non-clean build cannot ship two bundles (or bake them in at 4c). That is a real defect, found by Dev and fixed in scope. |
| 4 L-6 / L-11 guards per plugin module; L-7 guard in `bot-engine` | Present: `PluginThreadAndContextGuardTest` ×2 and `PluginLoaderRulesGuardTest`. The L-7 guard covers `bot-engine` and `bot-app` main, and also asserts that it is not vacuous. |
| 5a `BootParentReclaimMain` in `bot-plugin-dist` test sources | Present. It prints `RECLAIMED` / `PINNED` and exits 0 only when both hold, as 4c step 7 expects. It uses no test-framework classes, so it can run from the fat jar plus `test-classes`. |
| 5 `.gitignore` + `.dockerignore`: `plugins-dist/` | Present. `/plugins-dist/` is anchored in `.gitignore`. The `.dockerignore` entry `plugins-dist` matches only the top-level directory, so 4c's `COPY bot-plugin-dist/target/plugins-dist/` stays possible. |

| L-rule | Test | Status |
|---|---|---|
| L-1 | `PluginTypeIdentityIT` (classpath is plugin-free, `file:` URLs, defining loaders, contracts from the parent) | ✓ |
| L-2 | `PluginJarContentsIT` | ✓ |
| L-3 | `PluginContextOriginIT`, **and enforced at load time** (Drift 4b-ii) | ✓ |
| L-4 | `PluginContextOriginIT` (no parent, no hook, TCCL restored after success and after a refresh failure) | ✓ |
| L-5 | `PluginLoggingContextIT` (context identity, level, `ScopedDebugFilter`) | ✓ |
| L-6 | per-module guard, stricter than the rule (Drift 4b-iv) | ✓ |
| L-7 | `PluginLoaderRulesGuardTest` | ✓ |
| L-8 | `PerBotMapperTypeFactoryTest`, `PluginBundleCloseTest` | ✓ (4a) |
| L-9 | the 4b gate's `dependency:tree` grep: 0 | ✓ |
| L-10 | `RootContextHoldsNoPluginRefsTest` | ✓ (4a) |
| L-11 | guards + `NettyPrestartTest` + `ClientFactoryRequiresGroupTest` | ✓ |
| L-12 | `PlatformThreadPinIT`, with the cold-group negative control | ✓ |
| L-13 | `IsolatedEquivalenceIT` + `ApplicationContextLoadsTest.classpathModeCatalogueIsTheShippedOne` | ✓ (plan amended, Drift 4b-iii) |
| L-14 | `PluginBundleReclaimIT`, negative control in a forked JVM | ✓ |
| L-15 | `BootParentReclaimMain` ships now; it is **run in 4c** | ✓ as planned |

No strict test (L-12, L-14) is weakened or `@Disabled`.

Local gate: failsafe > 0 (28), every listed IT present, deptree `bot-api` = 2, `micrometer-(core|registry)` = 0 (Dev's evidence; QA-4b counts independently).

## Drift

Dev reported each of these. My ruling follows each one.

**4a-i — `PluginBundle` is an abstract class with extra members** (`jars()`, `isolated()`, `typeFactory()`, `onUnpublish`, `isClosed`). **Accepted, no amendment.** The plan lists the minimum surface and does not say "interface". An abstract base is what lets `close()` *own* D-13's order as a template method, so no subclass can reorder it, and D-9 already says the bundle owns the `TypeFactory`. Every extra member has a consumer: the boot line, L-11 in `BotFactory`, and the unpublish behaviour of `PluginRuntime`.

**4a-ii — `Bot.newMessageMapper()`, and `PluginRegistries.typeFactory()` derived from the bundle.** **Accepted.** 5b requires every per-bot mapper to call `setTypeFactory`, and one helper is the least driftable way to get that (L-7's guard pins `registerSubtypes` to a `newMessageMapper()` mapper). Making `typeFactory` a derived accessor keeps the plan's API (`PluginRegistries.typeFactory()`) and removes a mis-pairing the record component allowed (review-4a). I amended 4a step 1's record listing so the plan and the code agree.

**4a-iii — kept test seams** (the package-private `PluginClassLoaderMetrics(MeterRegistry, PluginVersionResolver)`, the list-taking `MessageTypesRegistry` constructor). **Accepted.** The 4a gate requires `PluginClassLoaderMetricsTest` and `InfoGauge*` to pass *unchanged*. The first seam is what makes that possible, and Spring picks the `@Autowired` constructor. The second is the constructor production delegates to.

**4b-i (Dev #1) — D-13 scanning.** **Plan amended: a genuine API mistake.** D-13 made the resource-only `URLClassLoader(jarUrls, null)` the scanner's resource loader outright. Spring 6.2's `MergedAnnotationReadingVisitor.get` loads each annotation *type* through the metadata reader's class loader and returns `null` on `ClassNotFoundException | LinkageError`. I verified this in the `spring-core-6.2.0` sources. A null-parent loader cannot load `org.springframework.stereotype.Component`, so the scan finds zero candidates. The implementation keeps D-13's safety property, because candidates still come only from the resource-only loader. Only the annotation reading goes through the plugin loader. Any residue is caught by L-3, which is now enforced at load time.

**4b-ii (Dev #2) — L-3 enforced at load time ⇒ isolated mode cannot boot on the 4b fat jar.** **Plan amended (recorded consequence).** The check is right: with parent-first delegation, a bundle whose classes are also in `BOOT-INF/lib` would be accepted and would silently run the app's copies, i.e. "isolated" in name only. **4c step 1** (`bot-app` plugin modules `runtime` → `test`) is what removes the jars from the fat jar, so 4c is the cutover that makes isolated bootable. `bot-engine`'s test-scoped plugin dependencies are not transitive, so nothing else re-adds them. I wrote the consequence into the L-3 row: every fat jar from 3b to 4b, D2 included, must run `classpath`. D2 already does: the `application.properties` default is `classpath`, and `docker-compose.yml` is untouched by this diff (the `BOT_PLUGINS_MODE` line is 4c step 3). `CLAUDE.md` and `application.properties` both say "an isolated start before 4c fails, by design".

**4b-iii (Dev #3) — `ObjectProvider<EventLoopGroup>`.** **Accepted.** `getIfAvailable()` still resolves (and so pre-starts) the group before any bundle opens, whenever the bean exists. `NettyPrestartTest.pluginRuntimeIsBuiltAfterThePrestart` pins the ordering with `PluginRuntimeConfiguration` registered *before* `NettyEventLoopConfig`. Isolated mode refuses a missing or partly started group (`requireStarted`). The provider exists only so that web-slice contexts without Netty start in classpath mode. D-13's requirement, "ordering is a bean dependency, not a convention", holds in every mode that loads a bundle.

**4b-iv (Dev #4) — version stamped in `BotFactory.createBot`, not `createSingleBot`; `PluginVersionResolver` has no production consumer.** **Plan amended (D-15 and 4a step 6).** The plan contradicted itself here. D-9 requires one `current()` read per operation, with the bot wired from one value. D-15's "`createSingleBot` sets `.pluginVersion(resolver.currentVersion())`" is a second read, and once step 5 can swap bundles it can label a bot N while the bot runs N+1 (review-4a). The stamp now comes from `plugins.bundle().version()` on the same read. **On the resolver: keep it.** D-15 specifies the bean. It is AD-11's seam and costs nothing. Step 5's `BotHealthDTO.pluginVersion` is its natural reader. Removing it would be unrequested churn in a `bot-api` contract. The amended D-15 says it has no production consumer at step 4 and that a bot is **never** stamped from it.

**4b-v (Dev #5) — L-13 uses minimal `{"cmd":N}` frames, and the catalogue literal is duplicated.** **Plan amended (L-13 row).** "One recorded frame per provider" assumed a corpus that does not exist. The repo has recorded frames only for b52, bom, nohu, tip, taixiu and slot, under `bot-engine/src/test/resources/messages/`, and none for RIK, Win79, CASHOUT or CRASH. Recorded frames would also add no loader-sensitive coverage: Jackson builds a bean deserializer by resolving every property's type, nested types included, whatever the frame contains, so what loader identity can break is exercised by any frame that dispatches to the class. The implementation covers every registration of every provider, which is strictly more dispatch coverage than the plan asked for. **The duplicated literal** (`ShippedBundle.CATALOGUE` and `ApplicationContextLoadsTest.SHIPPED_CATALOGUE`) is acceptable: neither module can see the other's test classes without a test-jar dependency, and that dependency would put `bot-app` on `bot-plugin-dist`'s classpath. Each literal is checked against real data. **Advisory:** equivalence holds only while the two strings are equal, and nothing asserts that. A one-line source-equality guard would close it. Not a blocker.

**4b-vi (Dev #6) — the L-6 MDC rule is stricter** (only `MDC.put("literal", "literal")` passes unreviewed; `ThreadContext` writes and `MDC` map writes are banned; virtual threads are banned too). **Accepted.** A syntactic guard cannot prove that a variable is a `String`, so the literal-only form is a sound under-approximation of "only `String` values". Today's plugin code has no MDC writes. The plan explicitly allows banning virtual threads ("no plugin needs one").

**4b-vii (Dev #7) — failsafe execution id `default`.** **Accepted.** The `spring-boot-starter-parent:3.4.0` pluginManagement declares an unnamed failsafe execution (id `default`) with `integration-test` + `verify`. Reusing the id merges into it, so the goals are explicit in this module's `<build><plugins>`, as 4b step 3 requires, and they are not bound twice. Dev's log confirms one run per IT.

**4b-viii (Dev #8) — Dockerfile / compose deferred to 4c.** **Not a deviation; this is the plan.** 4b's steps touch neither file. The `Dockerfile` `COPY` is 4c step 2. The compose volume, `BOT_PLUGINS_MODE` and `JAVA_TOOL_OPTIONS` are 4c step 3. `MetaspaceNearCap` is 4c step 4. The diff leaves `Dockerfile`, `docker-compose.yml`, `prometheus/` and `deploy.sh` untouched.

**4b-ix (Dev #9) — duplicate registry log lines.** **Accepted.** `PluginRegistries.build` builds silently, and `PluginRuntimeConfiguration` calls `logInitialized()` once, for the accepted bundle only, in the fixed betting / slot / message-types order. A rejected isolated candidate therefore cannot leave a second "registered 9 strategies" line for C-2's diff to flag (review-4a note). In classpath mode the three lines are byte-identical to 3b (QA-4a's real-boot diff, plus `PluginRegistriesLoggingTest` and `MessageTypesRegistryStartupLogTest`).

## Out-of-scope changes

These come from review-4a follow-ups and the plan did not ask for them. **All are inert in D2.** `ClasspathPluginBundle.close()` is a no-op, so `PluginRuntime.current()` can never throw in classpath mode.

- `PluginUnpublishedException`, `RestExceptionHandler` → 503, and `BotGroupBehaviorService` classifying it as the bounded `bot_creation_failures_total` reason `shutdown`. The new label value can appear only at isolated-mode shutdown, so it is not a D-1 violation.
- `PluginRuntime` refuses an already-closed bundle; `current()` also checks `bundle.isClosed()`.
- `PluginBundle.close()` catches `Throwable` per step and rethrows a `VirtualMachineError` unwrapped.
- Cosmetic, for review-4b: the 503 handler was inserted *between* `handleIllegalState`'s existing javadoc and its method (`RestExceptionHandler.java:~225-247`). That javadoc now dangles above the new one.

`CLAUDE.md` gains one paragraph on the plugin registries. This is a doc update in the 3a step 9 style, and 4c step 5 still owns the operator-facing text.

## Amendments to the plan

All amendments were made in place, with one changelog line dated 2026-10-07 (compliance-4ab). There is no amendment chain.

1. **D-13 scanning:** candidates come from the resource-only loader, annotations are read through the plugin loader, and the bullet explains why (`MergedAnnotationReadingVisitor`).
2. **L-3 row:** the check is also enforced at load time. Consequence: isolated mode cannot start until 4c step 1, so D2 runs classpath.
3. **L-13 row:** `{"cmd":N}` per registration, one shared catalogue literal pinned in both modes, and the reason recorded frames are not required.
4. **D-15, 4a steps 1 and 6:** `BotFactory.createBot` stamps from its single read; the resolver is kept with no production consumer and must never stamp a bot; `typeFactory()` is derived.
5. **D2 Metaspace:** made executable. Capture `OLD_START` at P-0, then read the old JVM back from Prometheus (30 d retention) at the new JVM's uptime with `&time=`. This replaces "come back later at a comparable uptime".
6. **V4b-1:** the bare thread count did not discriminate on Bot-1. `onStartup` auto-starts every ACTIVE group within seconds (11 groups at D1), so a pre-4b JVM also shows 4 event-loop threads and "before starting any group" cannot be arranged. The check now also compares each event-loop thread's `elapsed=` with Tomcat's `http-nio-<port>-Acceptor`. Pre-started threads are older: the group bean is built before Tomcat starts. Lazily started threads are younger: the startup chain runs on `ApplicationReadyEvent`, which comes after Tomcat. The regex is port-agnostic, because compose maps 8080→8085.
7. **V4-11 step 1/3:** explicit `t0` / `t1` capture commands. The old text pointed at V4b-1's command, which no longer saves every thread name.

The docs patch from `scratchpad/4b/docfix.patch` was applied as given (P-0 / V4a-2 / V4-6 `'^bots_managed[{ ]'`, its changelog line, the release-d1 corrections). The only change was rewording V4a-2's paragraph, which the patch had left mid-sentence across a line break.

## D2 verification: executable against this build?

Yes, with the amendments above. Checked item by item:

- **P-0:** the `plugin runtime: version=[^,]*` capture still matches the old line. The `bots_managed` grep is fixed. Add `OLD_START` (now part of the metaspace bullet).
- **C-1, C-3, C-5, C-6:** unaffected.
- **C-2:** the three registry lines are byte-identical and printed once, in order. C-2 excludes the runtime line.
- **C-4:** unaffected.
- **V3-1:** the boot line still starts `plugin runtime: version=builtin`, and the meters read 1/1/0 `builtin` (QA-4a, real boot).
- **V4a-1:** the line now ends `, source=classpath, jars=[]`, so the grep returns 1.
- **V4a-2:** the stamp still yields `builtin`. The fixed grep matches `bots_managed{application=…}`.
- **V4a-3:** no string containing `plugin bundle` is logged in classpath mode. Those strings exist only on isolated, close and shutdown paths.
- **Metaspace:** executable as amended.
- **V4b-1:** executable as amended. The JVM's SIGQUIT dump goes to the container's stdout. PID 1 is `java` (exec-form `ENTRYPOINT`), and `-Xrs` is not set.
- **Host `plugins-dist`:** not needed. Classpath mode never reads `bot.plugins.dir`.
- **`docker compose ps -q bot-manager`:** used throughout.

"V4-0 at comparable uptime" is a **D3** check (before 4c). Its comparable-uptime caveat is already in the plan, and the same `&time=` technique applies there.
