# Code Review — PLUGIN_HOT_RELOAD (Phase 1)

Branch: `feature/plugin-hot-reload`
Reviewed diff: `git diff 254d56b..feature/plugin-hot-reload` (4 commits, 21 files)

## Verdict

CHANGES_REQUESTED

One `bug`, no `security`. The defect is in the **alert rule**, not in the Java: as
written `MetaspaceGrowth` fires for roughly the first 24 hours of every JVM's life,
including the deploy that ships it. That is the exact failure mode this phase exists to
avoid — an instrument that lies, in the direction that gets it muted. The application
code is behaviour-identical and I found nothing in it that would change runtime
behaviour for a bot.

Deployability: **the app is safe to deploy to staging as-is.** No new failure mode, no
class loaded from anywhere new, one INFO line per JVM, no hot path touched, no bot
lifecycle changed. Deploying with the alert rule unfixed is not dangerous, it is just
noisy — it will post a false `MetaspaceGrowth` into the VipTalk ops room about an hour
after the deploy and keep it active for a day, on staging and on prod alike. Fix the
`expr` before this reaches prod; that is a one-line change to `prometheus/alerts.yml`
and needs no rebuild.

## Findings

### [bug] `MetaspaceGrowth` fires on every restart, for ~23 h, including its own deploy
`prometheus/alerts.yml:382-390`

```yaml
- alert: MetaspaceGrowth
  expr: delta(jvm_memory_used_bytes{area="nonheap",id="Metaspace"}[24h]) > 52428800
  for: 1h
```

Two compounding problems, both of which point the same way:

1. **`delta()` extrapolates.** Like `rate()`, `delta()` scales the observed change up to
   the full range when the range is not fully covered by samples. At 2 h of uptime a
   real +70 MB of metaspace is reported as `70 MB * (24/2)` ≈ 840 MB.
2. **The 24 h window contains the boot ramp.** Even once the window *is* fully covered,
   `delta` is `value(now) - value(24 h ago)`, and for the first 24 h after a restart
   `value(24 h ago)` is the near-zero reading taken as the JVM started. A Spring Boot
   3.4 app with Mongo, Netty, Jackson, Micrometer, MapStruct and springdoc settles at
   ~90-140 MB of metaspace; the ramp alone is several times the 50 MiB threshold.

So the expression is continuously true from roughly T+1 h (when `for: 1h` is satisfied)
until T+24 h, on every deploy, every crash-restart and every OOM-kill. `audience:
internal` routes it through the default `viptalk` receiver into the ops room, alongside
`BotManagerRestarted` which is already telling the same story more accurately.

The consequence is worse than the noise. The rule's own comment says:

> If it fires in the first week, that is a PRE-EXISTING leak that must be understood
> before any child classloader is introduced — not a threshold problem.

Under this expression the first week's firings are the boot ramp, not a leak. An
operator who follows the comment chases a phantom; an operator who works that out mutes
the rule — and this is the *only* warning the container gets for a classloader leak,
because there is no `-XX:MaxMetaspaceSize`, so the real failure is a silent kernel
OOM-kill.

**Fix shape.** Gate the rule on the JVM having been up longer than the window, which
this same file already knows how to express (line 124 uses
`process_start_time_seconds{job="bot-manager"}`):

```yaml
expr: >
  delta(jvm_memory_used_bytes{area="nonheap",id="Metaspace"}[24h]) > 52428800
  and on(job) (time() - process_start_time_seconds{job="bot-manager"} > 86400)
```

Optionally also swap `delta` for `max_over_time(...[24h]) - min_over_time(...[24h])` to
drop the extrapolation entirely; the uptime gate is the load-bearing half. Whatever
lands, the same change is needed in the dashboard panel that mirrors the rule
(`grafana/provisioning/dashboards/plugin-runtime.json:167`, titled "MetaspaceGrowth's
expression"), or the panel stops matching the rule it claims to show. And the P1-10
7-day baseline the plan wants should be read from a JVM with >24 h of uptime or it will
bake the boot ramp into the tuned threshold.

### [smell] `register()` publishes the weak reference before the accounting it belongs to
`bot-app/src/main/java/com/vingame/bot/infrastructure/observability/PluginClassLoaderMetrics.java:158-162`

```java
tracked.add(new VersionedRef(loader, pluginVersion, collected));  // visible to drain()
registerMeters(pluginVersion);                                    // creates live[version]
live.get(pluginVersion).incrementAndGet();                        // ...then counts it
```

The reference is enqueueable, and visible to `drain()` on the sampler thread, one to
three statements before the version's `AtomicLong` exists and before it is incremented.
If a referent were collected inside that window, `drain()` would either NPE at
`live.get(versioned.pluginVersion).decrementAndGet()` (line 216, unguarded) — swallowed
by `drainQuietly`, losing the reclamation permanently and leaving `live` pinned one
above truth forever, i.e. **a permanent false leak reading** — or decrement to -1 before
the increment lands.

**This cannot fire in Phase 1** and I am not treating it as a Phase-1 defect: the only
caller is `start()` and the only argument is the application classloader, which is a GC
root. It matters because `register(String, ClassLoader)` is public *specifically* so
step 4's loader factory can call it with a loader that is meant to be collectible, and
the JIT is entitled to consider the caller's local dead the moment the `VersionedRef`
is constructed (the classic `Reference.reachabilityFence` hazard). Fix is free and
should land now rather than at step 4: register the meters, increment `live`, increment
`created_total`, and add to `tracked` **last**. While there, make `drain()` null-safe on
`live.get(...)` so an unaccounted version degrades to a skipped decrement rather than an
aborted pass that abandons the rest of the queue.

Everything else about the queue mechanics is right, and I checked the two ways it could
be wrong: **no double-counting** (a `Reference` is enqueued at most once, and
`tracked.remove` uses identity equality on a class with no `equals` override, so a
second pass over the same ref is a no-op), and **no silent miss from unreachable
references** (`tracked` holds the `VersionedRef`s strongly, which is the failure the
class javadoc names and the test pins).

### [smell] Micrometer registration runs inside a `ConcurrentHashMap.computeIfAbsent`
`PluginClassLoaderMetrics.java:172-190`

`registerMeters` builds one `Gauge` and two `Counter`s inside the mapping function for
`live`. Meter registration takes the registry's own locks and runs every registered
`MeterFilter`, so this is arbitrary third-party work inside a CHM mapping function —
the documented recursive-update / stall hazard. It is safe today because nothing in the
filter chain reads `live` (`BotMdcTagsMeterFilter` short-circuits on the name and never
calls back), but the invariant is invisible from here. Cheaper shape: `computeIfAbsent`
only to mint the `AtomicLong`, then register the meters outside the lambda — Micrometer
registration is idempotent for an identical id, so the duplicate-call race is harmless.

### [smell] `drainQuietly` drops the stack trace and can emit ERROR every 10 s
`PluginClassLoaderMetrics.java:196-201`

```java
} catch (Exception e) {
    log.error("Plugin-classloader sampling failed: {}", e.getMessage());
}
```

This is copied verbatim from `InfoGaugeRefresher.refreshQuietly`, so it is the house
idiom and I am not asking for a redesign — but two things are worth noting for a class
whose whole job is to be trustworthy. First, `e.getMessage()` without the throwable
gives an NPE-shaped failure a one-line message and no frame, in a class that will be
read by whoever is chasing a metaspace leak at the time. Second, a persistent failure
here produces 8,640 ERROR lines/day (rate is not bot-count-dependent, so it does not
violate the LOG_VOLUME_TIERING INFO rule, but ERROR is the "page-on this" tier) *while
the meters are frozen at their last values* — the gauge keeps reporting the last good
number, which reads as healthy. Suggest logging the throwable and throttling to
first-occurrence-plus-periodic, in the style `AsyncQueueMetrics` already uses for its
own WARN.

### [smell] `countBotsByPluginVersion` diverges from its siblings without saying so
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/service/BotGroupBehaviorService.java:1598-1610`

Every neighbouring counter (`countBotsByEnvAndStatus`, `countManagedBotsByEnv`,
`countOpenWsByEnv`) opens with `if (envId == null) continue;`. This one deliberately
does not, so a group with a null `environmentId` still contributes a row (with
`environmentId=""` after `nullSafe`). That is **correct** — it is what makes the
javadoc's `sum(bots_by_plugin_version) == bots_managed` invariant (verification P1-5)
actually hold, which the env-keyed siblings cannot claim. But the divergence is
invisible against three adjacent methods that all look the other way, and the obvious
"consistency fix" would silently break the invariant the metric is validated by. One
sentence in the javadoc naming the omission as deliberate would close it.

Otherwise the method is fine on the two axes I was asked about: no lock is taken or
needed (`runningGroups` is a `ConcurrentHashMap`, `botInstances` a
`CopyOnWriteArrayList`, exactly as the siblings assume), and it is not on a hot path —
it runs on the 10 s `InfoGaugeRefresher` virtual thread and costs one pass over the live
bot set, the same order as `countBotsByGameAndStatus` already does in the same tick.

## Notes

**Things I checked that are right, and would have been expensive to get wrong.**

- **AD-5 holds by construction, on every path I could find.** `pluginVersion` reaches
  metrics through exactly one family. Both MDC→tag channels use an explicit six-key
  list, not an MDC dump: `BotMetrics.mdcTags()` (`BotMetrics.java:104-113`, the
  pre-registration path that actually creates the series) and
  `BotMdcTagsMeterFilter.map()` (`:93-99`, the safety net). Adding the key to MDC
  therefore cannot reach a `bot_*` meter, and `bots_by_plugin_version` is additionally
  on the aggregate exclusion list so it cannot inherit the refresher thread's MDC.
  `PluginVersionCardinalityTest`'s second test asserts the strong property (no meter
  carries the tag), not just series-count equality, which is the right test to have
  written.
- **The Loki side does not gain a label either.** `log4j2-json-template.json` adds
  `pluginVersion` as a JSON *field*, and `promtail-config.yml`'s `labels:` stage lists
  only `level`, `botGroupId`, `environmentId`, `gameType` — so this adds no Loki stream
  cardinality and is queried with `| json`. Also worth recording: both `log4j2.properties`
  twins reference the template as `classpath:log4j2-json-template.json`, so the in-jar
  copy is authoritative and there is **no bind-mounted twin to drift** — unlike
  `log4j2.properties` itself.
- **The detector does not retain what it observes.** `VersionedRef` is `static` (no
  outer reference), holds only a `String` beside the weak referent; `tracked` holds
  weak refs; `live` holds `String`→`AtomicLong`; the meter ids hold tag strings. There
  is no strong path from this component to a registered `ClassLoader`, which is the one
  way this class could have failed silently at its own job.
- **Sampler lifecycle is clean.** `@PreDestroy stop()` calls `shutdownNow()`, the
  thread is virtual (hence daemon, so it cannot hold the JVM up even if destruction is
  skipped), and it is the same `Executors.newSingleThreadScheduledExecutor(
  Thread.ofVirtual()...factory())` shape `InfoGaugeRefresher` already ships. No leak,
  no reuse-after-stop path.
- **MultiGauge semantics are the established ones.** `register(rows, true)` matches all
  nine existing families; `overwrite=true` is what stops a persisting row freezing at
  its first value, and stale rows are dropped either way. No meter leak, no stranded
  series.
- **`Bot.initialize()`'s ordering is genuinely required, and the cleanup is total.**
  `BotMdc.setPluginVersion(...)` at `Bot.java:284` must precede the
  `MDC.getCopyOfContextMap()` at `:290`, because `configureClient()` registers callbacks
  the library can invoke on its own threads the moment `connect()` returns and those
  threads only ever see the snapshot. `getPluginVersion()` cannot NPE there —
  `configuration` is dereferenced four lines earlier. Cleanup: `initialize()`'s
  `finally` calls `BotMdc.clear()`, which this diff correctly extends to remove
  `PLUGIN_VERSION`; the `wrapWithMdc` family restores-or-clears; `BotGroupRuntime.startBot`
  clears in its own `finally`. Both `BotMdc.set(` call sites in main source were
  updated — there are only two.
- **AD-9 is respected literally.** One new `log.info` in the whole diff
  (`PluginClassLoaderMetrics:135`), fired once per JVM from `@PostConstruct`, in the
  same shape as `NettyEventLoopConfig`'s one-shot line. Nothing per-bot, per-round or
  per-message was added at any level.

**On the deliberate omission (focus area 6): `BotConfiguration.pluginVersion` is a
reasonable seam, not premature dead code.** It is an additive field on an immutable
`@Value @Builder` runtime object — not a `@Document`, not a MapStruct target, not
serialised anywhere — so a never-written field costs one reference and nothing else. It
mirrors the existing `productCode` / `resolveProductCode()` pair line for line, which is
the pattern a reader will already recognise. Crucially the field is *not* the thing that
ships: `resolvePluginVersion()` is, it is total (never null, empty string treated as
unset), it is exercised by every bot's MDC and every gauge row, and `BotPluginVersionTest`
pins both the unset and the explicit branch so step 4's builder wiring lands against a
tested contract. The alternative — inlining `PluginVersions.BUILTIN` at the two MDC
sites — would have to be unpicked at step 4 and would let the MDC tag and the gauge row
be resolved by two different expressions in the meantime, which is precisely the class of
divergence the `productCode` javadoc says was worth avoiding.

**One question for the author, not a finding.** `PluginClassLoaderMetrics.register()`'s
javadoc says registering the same loader twice double-counts "deliberately, because 'the
same version was loaded twice' is a fact worth seeing". Registering the same *loader*
twice under the same version is not that fact — it is a bookkeeping bug in the step-4
factory, and it would show as `live=2, created=2` with only one reclaim ever arriving,
i.e. indistinguishable from a real retention. Two distinct loaders sharing a version is
the fact worth seeing, and that already works. Worth reconsidering the wording (or
deduplicating on referent identity) before step 4 has a caller.
