# QA — PLUGIN_HOT_RELOAD_3_4 Phase 4b

**Verdict:** PASS
**Build:** `mvn -o clean verify` in a clean detached worktree at `21eed9e`: surefire **3301**
(148 / 91 / 262 / 1157 / 1643), failsafe **28** (`bot-plugin-dist`, 8 IT classes), 0 failures,
0 errors, 0 skipped. QA added one IT (`a3a6865`), so the failsafe count is now **29**. The 29 were
re-run in the scratch worktree: 29 run, 0 failures.

Scope: `a62985d..21eed9e`, i.e. the review-4a fixes and 4b. All Maven work and every boot ran in
scratch worktrees (`qa-4b` at `21eed9e`, `qa-4a` at `bf75701`), which have since been removed.
The feature worktree and the main checkout were never built. Findings already recorded in
`compliance-4ab.md` (36ea3e7) and `review-4b.md` (874f8be) are referenced below, not repeated.

## Dev's claims, checked

| Claim | Result |
|---|---|
| 3301 surefire (148/91/262/1157/1643) + 28 failsafe | **Confirmed** per module from the report XML. The local gate passes: `plugins-dist/` holds exactly one directory, `20261007.122630`, with the two jars, and both manifests carry that `Bot-Plugin-Version`. `deptree`: `bot-api` count 2, `micrometer-(core\|registry)` count 0. `bot-plugin-dist`'s tree has no `bot-strategies`/`bot-messages`. |
| Failsafe runs each IT exactly once (execution id `default`) | **Confirmed.** The build log has one `failsafe:integration-test (default)` and one `failsafe:verify (default)`. There are 8 `Running …IT` lines for 8 classes, and 28 tests in the summary. |
| D-1, classpath mode: identical to 4a | **Confirmed by a real `java -jar` boot** of the 4a (`bf75701`) and 4b fat jars against a throwaway `mongod`, with a console log4j2 config. The four registry and `plugin runtime:` lines are **byte-identical and printed once each**, including `…classloader=2047329716, source=classpath, jars=[]`. The `GET /api/v1/strategy/` md5 is `a72c40f56057cda5434b273ea36315ea` on both. `?gameType=SLOT` returns `[]`. The Prometheus series sets (128 each) differ only in the Mongo `cluster_id`/`database` labels. `plugin_classloaders_*` reads 1/1/0 `builtin`. `bots_managed{…} 0.0` is exported. `grep -c 'plugin bundle'` is 0 (V4a-3). |
| V4b-1: the event-loop threads exist from boot | **Confirmed.** On an idle box, `jstack` shows 4 `multiThreadIoEventLoopGroup-2-{1..4}` threads on 4b and **0** on 4a. The plan's own `kill -QUIT` + `grep -oE '^"multiThreadIoEventLoopGroup-…"' \| sort -u \| wc -l` gives `4` on 4b. |
| Isolated mode on the 4b fat jar fails by design (L-3) | **Confirmed. It is the clean, intended failure.** `BOT_PLUGINS_MODE=isolated BOT_PLUGINS_DIR=…/plugins-dist` exits 1. There is exactly **one** loader ERROR: `plugin bundle …/20261007.122630 rejected: beans not defined by the plugin loader: [bomGameMessageTypes (… defined by org.springframework.boot.loader.launch.LaunchedClassLoader@…), …] — the plugin classes are also on the application classpath (a fat jar that still carries bot-strategies/bot-messages?) …`. Then Spring's `pluginRuntime` failure carries `no valid plugin bundle in <dir> or <builtin-dir> — every candidate was rejected`. No registry "initialized" line is printed, and nothing else fails first. |
| Isolated mode works end to end | **Confirmed on a plugin-free parent** (see below). |

## Isolated mode, end to end, on a plugin-free parent

To get a plugin-free parent, QA applied plan 4c step 1 in the scratch worktree only: the
`bot-app` plugin dependencies go from `runtime` to `test`, then `mvn -o package -pl bot-app -am -DskipTests`.
`unzip -l … | grep -cE 'bot-(strategies|messages)'` → `0`. The pom change was reverted, and
nothing was committed. Then the jar was booted with `BOT_PLUGINS_MODE=isolated BOT_PLUGINS_DIR=<copy of plugins-dist>`:

- **Boot succeeds.** `Started Starter` in 2.3 s. The three registry lines are
  **byte-identical** to classpath mode (`diff` empty), printed once, then
  `plugin runtime: version=20261007.122630, …, source=…/plugins-dist/20261007.122630,
  jars=[bot-messages-1.0.jar sha256=a4d1375d5d97, bot-strategies-1.0.jar sha256=c1017c86c29b]`.
  Both sha prefixes match `shasum -a 256` of the shipped jars, which is the V4-4 shape.
- `GET /api/v1/strategy/` md5 is `a72c40f56057cda5434b273ea36315ea`, and `?gameType=SLOT` returns `[]`. The C-3 check is green in isolated mode.
- `plugin_classloaders_{live,registered_total}{pluginVersion="20261007.122630"} 1`,
  `reclaimed_total 0`, no `builtin` series (V4-5 shape). The Prometheus series set equals
  classpath mode's once `pluginVersion` is normalised. 4 event-loop threads.
- **Strategy validation through the isolated registries:** `POST /api/v1/bot-group/` with
  `strategyMix: NOPE` → 400 `Unknown strategyId 'NOPE' … registered strategies: [9 keys]`.
- **The start path through `BotFactory`:** QA started an `existingGroup=true`
  `MARTINGALE_CLASSIC_CAUTIOUS` group on a P_116 environment whose gateway is
  `127.0.0.1:9`. It logged `strategy mix {MARTINGALE_CLASSIC_CAUTIOUS=2}`, and both bots then
  failed at login (`UpstreamLoginException`, `bot_creation_failures_total{reason="auth"} 2`),
  which is expected with no gateway. There was no `NoClassDefFoundError`/`ClassCastException`/`LinkageError`/`not wired`
  (C-5 grep). **Not exercised:** live socket play from an isolated bundle. There is no gateway
  locally, so D3/C-4 on staging is the first live proof (see Gaps).
- **`_`-prefixed directory and fallback (V4-9 drill, local):** the mount held only `_<V>` and the
  builtin dir held `<V>`. The boot succeeded with exactly one WARN, `plugin bundle: no valid bundle in
  …/mount — running the image's built-in bundle 20261007.122630 from …/builtin/20261007.122630`,
  and `source=…/builtin/…`. The `_` directory was not reported, and there was no ERROR.
- **Rejected newer candidate, older accepted:** the mount held the IT's duplicate-key fixture
  `20990105.000000` plus the shipped `<V>`. There was one ERROR, `…/20990105.000000 rejected: Duplicate
  @StrategyImpl(RANDOM) on …DuplicateRandomStrategy`, then the registry lines **once**, for the
  accepted bundle only (the review-4a log-duplication fix, observed in a real boot), and
  `source=…/mount2/<V>`.

No boot hung. Each one reached `Started Starter` or exited within about 5 s.

## D-13 deviation: does the scan find exactly the bundle's beans?

Yes. Candidates come from `URLClassLoader(jarUrls, null)`, which can see only bundle-jar entries,
and annotations are read through the plugin loader:

- **Nothing from the engine's split packages, or any engine package.** The ITs run with
  `bot-engine` on the parent classpath, and it carries 7 real `@Component`/`@Service`s under
  `com.vingame.bot.infrastructure.*`. If the scan saw the parent, those would be candidates.
  QA mutation M4 swapped the scanner's resource loader to the plugin loader. Every bundle was then
  rejected, and all 4 `PluginContextOriginIT` tests errored. So the restriction is load-bearing
  and the ITs catch its loss. (None of the 7 sit in `domain.bot.{message,strategy}`, so the split
  packages themselves contain no engine bean today. The guard is the base-package scan as a
  whole, which is the stronger condition.)
- **No bundle bean is missed.** The plugin sources hold 22 stereotype classes, and all 22 carry
  `@StrategyImpl`/`@SlotStrategyImpl`/`@MessageTypesImpl`. So the exact `CATALOGUE` equality in
  `IsolatedEquivalenceIT` (9 + 2 keys, every product → provider) accounts for every one of them.
  `PluginContextOriginIT`'s `>= 22` bound is loose, but nothing escapes it. A future
  non-registry plugin bean would only be covered by the origin check, not by a count.
- The reverse mistake, metadata read through the null-parent loader, finds 0 beans. The bundle
  is then rejected as empty, which is the failure D-13's amendment describes. compliance-4ab
  accepted it (Drift 4b).

## Mutation spot-checks of Dev's tests (7 of the 27, all killed)

| # | Mutation (reverted after) | Killed by |
|---|---|---|
| M1 | `IsolatedPluginBundle.closeContext()` does nothing (**unclosed context**) | `PluginBundleReclaimIT.closedBundleIsReclaimed`: "something still holds the plugin loader after close()" |
| M2 | `PlatformThreadPinIT.census` ignores the inherited ACC (**negative control can't see**) | `coldGroupStartedUnderAPluginFrameIsDetected`: the control fails, so the census is not vacuous |
| M3 | `GameMsClient` calls `ObjectMapperProvider.getDefault().registerSubtypes(…)` (**L-7**) | `PluginLoaderRulesGuardTest`: `objectMapperProviderOnlyFeedsOutputPrinter` + `registerSubtypesOnlyOnPerBotMappers` |
| M4 | Scan resource loader = plugin loader (**D-13**) | `PluginContextOriginIT`, all 4 (every candidate rejected) |
| M5 | `checkOrigin()` removed (**L-3 at load time**) | `IsolatedPluginBundleShadowingTest` |
| M6 | TCCL restored after `refresh()` but not in `finally` (**L-4**) | `PluginContextOriginIT.tcclRestoredAfterARefreshFailure` |
| M7 | `_`-prefixed directories no longer skipped (**D-11**) | `IsolatedBundleSelectionIT.disabledDirectoriesAreIgnored` |
| M8 | `NettyEventLoopConfig` drops `prestart()` (**L-11**) | `NettyPrestartTest`, both tests |

(M8 is an eighth check. QA did not re-run Dev's full list of 27, which is not in the commits.)

## Tests added / updated

- `bot-plugin-dist/src/test/java/com/vingame/bot/plugin/it/CatalogueLiteralParityIT.java`
  (`a3a6865`). **Why:** `ShippedBundle.CATALOGUE` (isolated mode) and
  `ApplicationContextLoadsTest.SHIPPED_CATALOGUE` (classpath mode) are two copies of one literal.
  compliance-4ab 4b-v accepted the duplication, but **nothing guarded it.** Each copy was
  asserted only against its own mode. So if isolated mode lost a provider and someone made the IT
  pass by editing `CATALOGUE`, both tests would stay green while the modes differ, and L-13 exists
  to catch exactly that. The IT reads the `bot-app` test source, concatenates the
  `SHIPPED_CATALOGUE` string parts, and requires equality.
  - Mutation-checked. The unmutated run passes. Drift in the `bot-app` copy (`+ 120=X`) and drift in
    the `ShippedBundle` copy (`slot=[FIXED]`) both fail it.
  - QA's first version used a lazy `=(.*?);` and failed on clean code, because the literal itself
    contains `"; "`. That was fixed before the commit, and the regex comment says why.

## Coverage of the diff

- `IsolatedPluginBundleLoader` ← `IsolatedBundleSelectionIT` (order, `_`/`.`, mismatch, dup key,
  builtin fallback, absent mount, nothing valid), plus QA's real boots (fallback WARN, dup-key ERROR
  then single registry lines, the L-3 rejection on the fat jar).
- `IsolatedPluginBundle` ← `PluginContextOriginIT` (L-3/L-4), `PluginTypeIdentityIT` (L-1),
  `IsolatedPluginBundleShadowingTest` (L-3 at load), `PluginBundleReclaimIT` (L-14),
  `PlatformThreadPinIT` (L-12), `PluginLoggingContextIT` (L-5).
- Plugin jars ← `PluginJarContentsIT` (L-2, manifest == directory, which also catches a stale
  `~/.m2` copy).
- `PluginRegistries.catalogue()` / `logInitialized()` ← `IsolatedEquivalenceIT`,
  `ApplicationContextLoadsTest.classpathModeCatalogueIsTheShippedOne`,
  `PluginRegistriesLoggingTest`, `MessageTypesRegistryStartupLogTest`, QA's `CatalogueLiteralParityIT`.
- `PluginRuntimeConfiguration` (isolated, `requireStarted`) ← `PluginRuntimeConfigurationTest`,
  `NettyPrestartTest`, plus QA's real isolated boots.
- `NettyEventLoopConfig.prestart` ← `NettyPrestartTest`, plus V4b-1 measured on a real boot.
- `ClientFactory` required group ← `ClientFactoryRequiresGroupTest`; `BotFactory` sets it from
  `bundle.isolated()` ← `BotFactoryPluginRegistriesWiringTest.sharedGroupIsRequiredForIsolatedBundles`.
- `GameMsClient` virtual thread ← `PluginLoaderRulesGuardTest` (L-11 engine half).
- Plugin-module L-6/L-11 ← `PluginThreadAndContextGuardTest` ×2.
- review-4a fixes (`PluginBundle` Throwable close, `PluginRuntime` closed-bundle refusal,
  `PluginUnpublishedException` → 503 / `shutdown`, derived `typeFactory`, version stamped in
  `BotFactory`) ← `PluginBundleCloseTest`, `RestExceptionHandlerTest`,
  `CreationFailureLocalClassificationTest`, `PluginRegistriesBuildTest`,
  `BotFactoryPluginRegistriesWiringTest`; review-4b confirms each.

## Gaps

- **Live socket play from an isolated bundle has not run anywhere.** Locally, the run reaches the
  login and stops there for lack of a gateway. The first live proof is D3's C-4 on staging, and
  L-15's Boot-parent reclaim is 4c's.
- **The review-4b smells are not re-tested here, by design:** jar handles left open by the
  `jar:` scan, the unreadable-mount boot failure, and the fatal-error swallowing on the
  rejection path. None affects 4b's classpath-mode D2. The jar-handle one needs an IT before
  step 6, as review-4b says.
- `PluginContextOriginIT` counts beans with a loose `>= 22` bound. It is sufficient today (see the
  D-13 section), but it is not an exact set.
- Dev's mutation list was spot-checked (7 of 27, plus 1 more), not re-run in full.

## Failures

None.
