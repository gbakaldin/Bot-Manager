# QA — PLUGIN_HOT_RELOAD (Phase 1)

**Verdict:** FAIL
**Build:** `mvn clean install` → 1890 tests, 1 failure, 0 errors, 0 skipped

The single failure is a test I added, and it is red because of a **production
defect**, not a test defect: `plugin_classloaders_created_total` is unreachable
from Prometheus. Everything else in Phase 1 is sound, well covered and green.
One rename in `PluginClassLoaderMetrics` plus three artefacts flips this to PASS.

Branch: `feature/plugin-hot-reload` @ `25d42bf` (Dev's 3 code commits + compliance
amendment `ad061e3` + this QA commit).

---

## The blocking defect — `plugin_classloaders_created_total` never appears in a scrape

```
registered name : plugin_classloaders_created_total
scraped name    : plugin_classloaders_total
```

`_created` is a **reserved Prometheus suffix** (the OpenMetrics created-timestamp
series). The Prometheus Java client's name sanitiser strips `_total`, then strips
`_created`, and the counter exposition appends `_total` to what is left. Its sibling
`plugin_classloaders_reclaimed_total` round-trips intact, which is exactly what makes
this look like a typo rather than a rule.

Measured against a real `PrometheusMeterRegistry` (probe, then removed):

| registered | scraped |
|---|---|
| `plugin_classloaders_created_total` | `plugin_classloaders_total` |
| `plugin_classloaders_reclaimed_total` | `plugin_classloaders_reclaimed_total` |
| `plugin_classloaders_registered_total` | `plugin_classloaders_registered_total` |
| `plugin_classloaders_loaded_total` | `plugin_classloaders_loaded_total` |
| `x_created_total` | `x_total` |
| `x_info_total` | *(absent — routed to an InfoSnapshot)* |

**Consequences as shipped:**

1. `grafana/provisioning/dashboards/plugin-runtime.json`, panel *"Plugin
   classloaders created vs reclaimed"*, queries `plugin_classloaders_created_total`
   → **empty vector forever**. That is the panel whose whole purpose is showing
   `created − reclaimed` staying above zero, i.e. the retention. It renders as
   "no loader was ever created", not as a broken query.
2. **Verification P1-4 will fail on the box.** It greps `^plugin_classloaders_` and
   expects `plugin_classloaders_created_total = 1`; the releaser will find
   `plugin_classloaders_total` and have to decide on the spot whether that is a
   defect or a doc error.
3. `MetaspaceGrowth`'s description (`prometheus/alerts.yml:391`) tells the operator
   to "cross-check `plugin_classloaders_live` (created minus reclaimed…)" — the
   `created` half is not queryable.
4. AD-3's meter table in the plan is wrong about what production emits.

This is the same class of defect as the `_info` reserved-suffix bug that
`InfoGaugePrometheusScrapeTest` was originally opened for (`game_info` scraping as
bare `game`). It was invisible here because every existing Phase 1 test pins these
names against a `SimpleMeterRegistry`, which applies no naming convention at all.

**Suggested fix (Dev, not me — I did not touch `src/main`):** rename the constant to
`plugin_classloaders_registered_total` (verified to round-trip; also semantically
truer to `register(...)`), then update in lockstep:
`PluginClassLoaderMetrics.CREATED`, the Grafana panel expr + legend, the
`MetaspaceGrowth` description prose, AD-3's table, and verification P1-4.
`InfoGaugePrometheusScrapeTest.pluginRuntimeMeters_renderUnderTheNamesTheDashboardSpells`
goes green when the code and the dashboard agree **on a name the exposition can
carry** — do not "fix" it by asserting `plugin_classloaders_total`, which would
enshrine a name nobody wrote.

---

## Test-count baseline — Dev's numbers confirmed, the plan's 1485 was stale

Measured independently, in a throwaway worktree, aggregating every
`*/target/surefire-reports/TEST-*.xml`:

| Point | tests | failures | errors | skipped |
|---|---|---|---|---|
| `254d56b` (baseline) | **1866** | 0 | 0 | 0 |
| Dev's HEAD `4cc152e` | **1884** (+18) | 0 | 0 | 0 |
| + QA commit `25d42bf` | **1890** (+6) | **1** | 0 | 0 |

Per module at baseline → Dev's HEAD: bot-api 125→128, bot-app 1074→1083,
bot-engine 420→426, bot-messages 136 (unchanged), bot-strategies 111 (unchanged).
That is exactly the +18 Dev reported, in exactly the places the diff touches.

**Say it plainly: 1866 is right and the plan's `≥ 1485` gate was stale by 381
tests.** A gate that loose would have passed a branch that deleted a fifth of the
suite. The Compliance Architect has already corrected it in the plan
(Amendment A1, committed at `ad061e3`); no further doc change is needed for this,
but the 1485 figure should not be re-quoted from any older copy.

Also worth recording: **0 skipped** at every point above. On this machine
`System.gc()` reclaims, so the GC rehearsal really ran — see below for why that is
not something to rely on.

---

## Tests added / updated (this QA pass)

- `bot-app/src/test/java/com/vingame/bot/infrastructure/observability/PluginClassLoaderMetricsTest.java`
  — hardened `aDroppedClassLoaderIsReclaimed` with a **control `WeakReference`**, so
  the `Assumptions` escape can no longer swallow an accounting bug.
- `bot-app/src/test/java/com/vingame/bot/infrastructure/logging/PluginVersionLogFieldTest.java`
  *(new, 3 tests)* — AD-8: the JSON template resolves the `pluginVersion` MDC key,
  keeps every field it already had, parses as JSON at all, and track 2's pattern in
  **both** `log4j2.properties` twins still renders exactly three MDC keys.
- `bot-app/src/test/java/com/vingame/bot/infrastructure/observability/InfoGaugePrometheusScrapeTest.java`
  *(+1 test)* — the four new meters against the real text exposition. **Red; see the
  defect above.**
- `bot-app/src/test/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorServiceTest.java`
  *(+2 tests)* — `countBotsByPluginVersion`: a mixed-version group splitting into two
  rows, an unset field resolving to `builtin` rather than a second key, and
  `sum == getTotalManagedBots()` (verification P1-5's invariant, at build time).

---

## Findings on the four items I was asked to scrutinise

### 1. `PluginClassLoaderMetricsTest`'s GC-spike rehearsal — it *could* silently no-op, and did

**As Dev wrote it, the answer was yes, and the skip covered the exact regression the
test exists for.** Confirmed by mutation, not by reading:

```
// mutant: register() no longer keeps a strong ref to its own VersionedRef
-  tracked.add(new VersionedRef(loader, pluginVersion, collected));
+  new VersionedRef(loader, pluginVersion, collected);

result: Tests run: 4, Failures: 0, Errors: 0, Skipped: 1   → GREEN BUILD
```

A `WeakReference` that is itself unreachable is never enqueued. The queue stays
empty, `drain()` returns 0 forever, `reclaimed_total` never moves — and in
production that is a leak detector that reads flat under a real leak. From inside
the polling loop that is indistinguishable from "the JVM declined to collect", so
`assumeTrue(reclaimed)` turned the defect into a skip. The class's own javadoc names
this failure mode ("that failure looks exactly like 'no leaks'") and then let the
escape hatch hide it.

**What makes it a real gate**, and what I implemented: a **control
`WeakReference`** to the same loader, registered with *no* queue and held only by the
test frame. It is cleared by the same collection that should have enqueued ours, so
it distinguishes the two cases:

- control still holds the loader → the JVM genuinely did not collect → `Assumptions.abort`, skip is honest;
- control cleared but `drain()` saw nothing (after a bounded grace period for `ReferenceHandler`) → **hard failure**.

`refersTo(null)` rather than `get() == null`, because `get()` hands the referent back
out and can keep it alive another cycle. Re-running the same mutant now gives:

```
Tests run: 4, Failures: 1, Errors: 0, Skipped: 0
aDroppedClassLoaderIsReclaimed -> failure: "the JVM collected the throwaway
classloader but drain() never observed it..."
```

The remaining honest-skip window is unavoidable (`System.gc()` is a hint) and is
correctly scoped: it now only excuses the JVM, never the instrument. Note this test
still is not the spike — a real plugin loader is pinned by live classes, threads,
Micrometer meters and `ObjectMapperProvider.getDefault()`; a bare `URLClassLoader`
is pinned by nothing. The plan is right to keep the spike separate.

### 2. Metric-name consistency across artefacts — one break, both directions checked

Forward (code → artefacts) and reverse (artefacts → code):

| Registered in code | alerts.yml | plugin-runtime.json | Exposition | Verdict |
|---|---|---|---|---|
| `plugin_classloaders_live` | prose only | `plugin_classloaders_live` | `plugin_classloaders_live` | ✅ |
| `plugin_classloaders_created_total` | prose only | `plugin_classloaders_created_total` | **`plugin_classloaders_total`** | ❌ **defect** |
| `plugin_classloaders_reclaimed_total` | prose only | `plugin_classloaders_reclaimed_total` | same | ✅ |
| `bots_by_plugin_version` | — | `sum by (pluginVersion) (bots_by_plugin_version)` | same, with all four labels | ✅ |
| *(adopted)* `jvm_memory_used_bytes{area="nonheap",id="Metaspace"}` | `MetaspaceGrowth` expr | 2 panels | present, value > 0 | ✅ pinned by `RuntimeMetricsExposedToAlertsTest` |
| *(adopted)* `jvm_classes_loaded_classes` | prose | 1 panel | present | ✅ |
| *(adopted)* `jvm_classes_unloaded_classes_total` | prose | `rate(...[1h])` | present | ✅ |
| *(adopted)* `bots_managed` | — | cross-check panel | on the aggregate allow-list | ✅ |

Reverse direction is otherwise clean: no dashboard expr and no alert selector names
anything the app does not emit, and no new meter is orphaned. The `MetaspaceGrowth`
rule reads only `jvm_*`, so `AlertRuleMetricsTest`'s `EXTERNAL_PREFIXES` skips it —
Dev correctly identified `RuntimeMetricsExposedToAlertsTest` as the only place that
can pin it, and the two new tests there do pin the `id="Metaspace"` **selector** and
not just the metric name, which is the part that matters.

The dashboard's `uid` is `plugin-runtime`, so verification P1-9's
`?query=plugin` search will hit.

### 3. AD-5 enforcement — real, and I proved it by mutation

```
// mutant on BotMdcTagsMeterFilter.map()
+  addTagIfPresent(extra, BotMdc.PLUGIN_VERSION);

result: PluginVersionCardinalityTest.noBotSeriesCarriesTheTag -> FAILURE
        "[no meter in the registry may carry the pluginVersion tag]"
```

So AD-5 is genuinely enforced against the one-line change that would break it. Two
caveats worth recording, neither a defect:

- **The A/B fleet-replay test is not the gate.** `pluginVersionAddsNoSeriesAcrossAFleet`
  stayed **green** under the mutant, exactly as its sibling's comment predicts: one
  constant label value adds no cardinality today, so series counts still match. It
  would only go red on the first day of a real drain, years after signing this off.
  `noBotSeriesCarriesTheTag` is doing all the work; the replay is corroboration.
  Dev knew this and wrote the comment. Keep both, but do not delete the tag test as
  "redundant with the replay".
- **`twoVersionsInOneGroupWouldSplitEverySeries` cannot fail.** It stayed green under
  the mutant too, because Micrometer caches counter handles by the **pre-filter**
  `Meter.Id` (the caveat documented on `BotMdcTagsMeterFilter` itself) and `BotMetrics`
  builds its own tags from MDC without `pluginVersion` — so the second `incBotMessage`
  resolves to the cached counter and the filter is bypassed. The comment calling it
  "deliberately RED-if-it-ever-happens" overstates it: it pins that *`BotMetrics`*
  does not add the tag, which is worth pinning, but it is not the drain-cost
  demonstration it reads as. Documentation-grade, harmless.

### 4. `PerBotInfoLogGuardTest` / `Log4j2TwinConfigTest` — both green, AD-9 honoured

Exactly one `log.info(` is added anywhere in the diff:

```
PluginClassLoaderMetrics.start()  →  log.info("plugin runtime: version={}, classloader={}", …)
```

It lives in `bot-app/.../infrastructure/observability/`, which is outside both
`PER_BOT_DIRECTORIES` and `PER_BOT_FILES`, and it fires from `@PostConstruct` —
once per JVM, same shape as `NettyEventLoopConfig`'s EventLoopGroup-identity line.
Nothing per-bot landed at INFO: `Bot.getPluginVersion()` is a pure accessor with no
logging, and `BotGroupRuntime.startBot`'s addition is a single `BotMdc` call.
`Log4j2TwinConfigTest` is untouched by construction — the JSON template is loaded
via `classpath:` and has no `logging/` twin, and neither `log4j2.properties` was
edited. Both suites green in every run.

---

## Coverage of the diff

| Production file | Test | What is covered |
|---|---|---|
| `bot-api/.../common/logging/BotMdc.java` | `BotMdcTest` (+3) | key set, `clear()` removes it, null/blank skipped, not on `GROUP_LEVEL_KEYS` |
| `bot-api/.../common/plugin/PluginVersions.java` | used throughout | constant |
| `bot-api/.../common/plugin/PluginVersionResolver.java` | `PluginClassLoaderMetricsTest` (lambda impl) | seam |
| `bot-api/.../config/bot/BotConfiguration.java` (`resolvePluginVersion`) | `BotPluginVersionTest` (+3), `BotGroupBehaviorServiceTest` (QA) | null → `builtin`, `""` → `builtin`, explicit passthrough |
| `bot-engine/.../core/Bot.java` (`getPluginVersion`) | `BotPluginVersionTest` | never null; bot and config agree |
| `bot-engine/.../BotMdcTagsMeterFilter.java` | `PluginVersionCardinalityTest` (+3) | AD-5, mutation-verified |
| `bot-app/.../PluginClassLoaderMetrics.java` | `PluginClassLoaderMetricsTest` (4), `InfoGaugePrometheusScrapeTest` (QA) | 1/1/0 baseline, app loader stays live, reclamation, name prefix, **exposition names (red)** |
| `bot-app/.../BuiltinPluginVersionResolver.java` | — | trivial constant; see Gaps |
| `bot-app/.../BotGroupBehaviorService.countBotsByPluginVersion` | `BotGroupBehaviorServiceTest` (QA, +2) | mixed-version split, unset → `builtin`, `sum == bots_managed`, empty when idle |
| `bot-app/.../InfoGaugeRefresher.java` | `InfoGaugeRefresherTest` (+3), `InfoGaugePrometheusScrapeTest` (QA) | row labels, two-version split, MDC-leak exclusion, exposition |
| `bot-app/.../log4j2-json-template.json` | `PluginVersionLogFieldTest` (QA, new) | AD-8 both directions |
| `prometheus/alerts.yml` (`MetaspaceGrowth`) | `RuntimeMetricsExposedToAlertsTest` (+2), `AlertRuleMetricsTest` | metric + `id="Metaspace"` selector exposed, value > 0 |
| `grafana/.../plugin-runtime.json` | `InfoGaugePrometheusScrapeTest` (QA) | panel names vs exposition — **found the defect** |
| `bot-app/.../BotGroupRuntime.java` | — | see Gaps |

---

## Gaps

Named deliberately; none of these blocks the release once the rename lands.

- **`Bot.initialize()`'s MDC ordering is untested.** The plan calls it load-bearing —
  `setPluginVersion` must precede the `mdcSnapshot` capture or the tag is missing
  from every async-callback line, which for a bot that dies during connect is most
  of them. The diff has the call in the right place; nothing would notice if a
  future edit moved it three lines down. `BotProductLabelTest` /
  `BettingMiniGameBotMdcTest` are the existing shapes to mirror. Not added here
  because it needs a connect-path harness, and the failure mode is missing log
  detail rather than a wrong metric.
- **`BotGroupRuntime.startBot`'s `setPluginVersion` call is untested** for the same
  reason — it is inside a virtual-thread body that requires a started group.
  Covered indirectly by verification P1-6 on the box.
- **`BuiltinPluginVersionResolver` has no test of its own.** It is a one-line
  constant and is exercised transitively; a test would restate the implementation.
  It becomes worth testing at step 4, when it stops being a constant.
- **Nothing asserts the AD-9 INFO line's text.** P1-2 greps
  `plugin runtime: version=builtin` on the box; the string is not pinned in the
  build. Low value now, but if that grep becomes a smoke-test gate it should be.
- **`plugin_classloaders_*` under a *multi-version* registry is untested** — two
  concurrent `pluginVersion` values in `PluginClassLoaderMetrics` (as opposed to
  `bots_by_plugin_version`, which QA now covers). Correctly deferred: nothing can
  produce a second version until step 4.
- **The 7-day metaspace baseline (P1-10) is a deliverable, not a check**, and is
  outside the build entirely. It gates AD-6's `-XX:MaxMetaspaceSize` sizing at
  step 4; the release report must carry the two numbers.

---

## Failures

```
[ERROR] InfoGaugePrometheusScrapeTest.pluginRuntimeMeters_renderUnderTheNamesTheDashboardSpells:210

Expecting actual:
  "# HELP plugin_classloaders_total Plugin classloaders ever registered under this version
   # TYPE plugin_classloaders_total counter
   plugin_classloaders_total{pluginVersion="builtin"} 1.0
   ...
   # TYPE plugin_classloaders_reclaimed_total counter
   plugin_classloaders_reclaimed_total{pluginVersion="builtin"} 0.0"
to contain:
  "# TYPE plugin_classloaders_created_total counter"
```

One failure, one root cause, one rename. See the defect section at the top.
