# Code Review — PLUGIN_HOT_RELOAD_3_4, Phase 4b

Branch: feature/plugin-hot-reload-3-4
Reviewed diff: `git diff a62985d..21eed9e` (the review-4a fixes f28f59c..5f4ed76, then 4b: 6be856e, cb64414, cba9568, 4655d58, 21eed9e). The only commit after the range, 36ea3e7, is docs-only.

## Verdict

PASS

No `bug` or `security` findings. The isolated loader does what D-13 requires: the parent is right, the URLs are `file:`, TCCL is restored on every path, the child context has no parent, and every rejected candidate is closed. Netty is pre-started before any bundle loads. The scan deviation from D-13 is sound. The smells below are mostly **latent**: they bite at step 5 or 6 (reload, swap), or only at JVM shutdown. One of them, the cached `JarFile` handle, I reproduced.

### Review-4a findings: all fixed, none papered over

| 4a finding | Status |
|---|---|
| Version read twice per bot | **Fixed.** `BotFactory.createBot` stamps it with `withPluginVersion(plugins.bundle().version())` from the same `current()` value it wires the bot from. The resolver is gone from `BotGroupBehaviorService`. `@With` copies `startCancelled`, so AD-8 wiring survives. |
| Rejection reason lost when close throws | **Fixed** in all three places: `closeAfterRejection` (classpath), `IsolatedPluginBundleLoader.tryLoad`, and `IsolatedPluginBundle.release`. Close failures are attached as suppressed. |
| `runStep` stopped on an `Error` | **Fixed.** It catches `Throwable` and every step runs. The first `VirtualMachineError` is rethrown unwrapped with the other failures suppressed on it; there is no self-suppression because the fatal error is kept in `all` and skipped by identity. Anything else is wrapped in an ISE. The javadoc now matches the code. |
| Runtime over a closed bundle | **Fixed.** The constructor rejects a closed bundle, and `current()` also checks `bundle.isClosed()`, which covers both blind spots of the hook. |
| `typeFactory` duplicated | **Fixed.** It is now a derived accessor, so a mismatched pairing cannot be expressed. |
| Callers of `current()` after close | **Mostly fixed.** `PluginUnpublishedException` is answered with a 503 (WARN, no stack trace), is counted under reason `shutdown`, and is checked ahead of the ISE arm in `classifyCreationFailure`. The TOCTOU half that 4a named (`bundle.newInstance` on a closed context) is not covered; see smell 3. |
| D-12 message names a rejected mode | Resolved: `isolated` now exists. |
| `PluginRuntime.of` | Removed. |
| (4a note) registry INFO lines printed once per candidate | Fixed. Each registry has a `logInitialized()`, called once, for the accepted bundle only. |

## Findings

### [smell] The candidate scan leaves every bundle jar open for the life of the JVM
`bot-engine/src/main/java/com/vingame/bot/infrastructure/plugin/IsolatedPluginBundle.java:182-194`

Both class loaders hand out `file:` URLs, but the scan does not read through them. `PathMatchingResourcePatternResolver` turns the `classpath*:` lookup into `jar:file:…!/com/vingame/bot/` URLs, and both it and `UrlResource.getInputStream` open those through a `JarURLConnection`. Spring 6.2 deliberately leaves `useCaches` at its JVM default (`true`) for `JarURLConnection` (`ResourceUtils.useCachesIfNecessary`, and `closeJarFile = !jarCon.getUseCaches()`). The JDK's `JarFileFactory` therefore caches one `JarFile` per jar URL. Neither `resourcesOnly.close()` nor the plugin loader's `close()` ever closes it.

I reproduced this on JDK 21.0.2 with spring-core 6.2.0, using a null-parent `URLClassLoader`, a `classpath*:` scan and a read of each resource. After the loader was closed, `lsof` still showed the jar open.

What it costs:
- **Step 4:** one file descriptor per jar per candidate opened, rejected candidates included. Bounded, and harmless.
- **Step 6 (reload):** descriptors grow with every reload. Deleting an old bundle directory frees no disk until the JVM restarts.
- **Worse:** a bundle rebuilt **at the same path** is scanned through the *stale* cached `JarFile`. The candidate list then describes the old content while the new loader defines the new one.
- Under `java -jar`, Boot 3.2+ registers its own `jar:` handler, which keeps its own URL-keyed cache. That is the "confirm Boot's nested-jar cache does not pin anything" caveat from the spike, reached by a different route. L-15 (`BootParentReclaimMain`) checks loader reclaim, not file handles.

None of this pins the classloader (`JarFile` holds no loader reference), which is why `PluginBundleReclaimIT` passes.

Fix shape: build the candidate set without `jar:` URL connections. The simplest version enumerates `*.class` entries under `com/vingame/bot/` from the `JarFile`s that `inspect` already opens (inside try-with-resources). It then reads each entry's bytes into a `ByteArrayResource` (or a `SimpleMetadataReaderFactory` over those bytes) for the metadata reader. An alternative is a `ResourcePatternResolver` wrapper whose `UrlResource`s call `setUseCaches(false)`. Whichever is chosen, add an IT that asserts no descriptor stays open on the bundle's jars after `close()`, before step 6 depends on this path.

### [smell] Fatal VM errors are swallowed on the rejection and release paths, against `PluginBundle`'s own rule
`IsolatedPluginBundleLoader.java:124-131`, `IsolatedPluginBundle.java:230-242`, `PluginRuntimeConfiguration.java:493-499`

`PluginBundle.close()` now promises that "a fatal VM condition must not be disguised as a close failure" and rethrows a `VirtualMachineError` unwrapped. Every caller that closes a rejected bundle then catches `RuntimeException | Error` and attaches the throwable as suppressed:

- `tryLoad` does this and moves on to the next candidate, so after an `OutOfMemoryError` during close the loader keeps opening and refreshing contexts.
- `release` swallows an `Error` from `context.close()`.

Boot most likely dies on the next allocation anyway, so this is advisory. The two halves still contradict each other.

Fix shape: catch `RuntimeException | LinkageError` (or `Error` minus `VirtualMachineError`) on these three paths. If a `VirtualMachineError` arrives, add the rejection to it as suppressed and rethrow it.

### [smell] `newInstance` / `beansOfType` on a closed bundle throw a plain Spring ISE, which is classified "validation" and logged at ERROR
`IsolatedPluginBundle.java:155-163`

This is the TOCTOU half of review-4a finding 6. A bot can pass `current()` and then reach `BettingStrategyFactory.create` → `bundle.newInstance` after the bundle has closed. `AnnotationConfigApplicationContext.getBean` then throws `IllegalStateException("…has been closed already")`. That is not a `PluginUnpublishedException`, so:
- `classifyCreationFailure` labels it `validation`;
- `createBotsInParallel` logs it at ERROR with a stack trace;
- `Bot.restart` on a running bot fails the same way.

At step 4 this happens only at shutdown, and only for the roughly `bot.creation.parallelism` tasks that are past the semaphore, because `shutdownNow` interrupts the rest. So it is bounded. Step 5's swap makes it routine.

Fix shape: in `PluginBundle` (or both overrides), check `isClosed()` first and throw `new PluginUnpublishedException(version())`. Every path then gets the 503 / `shutdown` / WARN treatment that `current()` already gets.

### [smell] The source guards are narrower than the rules they cite
`bot-engine/src/test/java/.../PluginLoaderRulesGuardTest.java`, `bot-strategies|bot-messages/src/test/java/.../PluginThreadAndContextGuardTest.java`

The guards are honest about being "syntactic tripwires". They fail closed on renames, and they will catch the obvious regressions. That makes them fit for purpose against accidents. Nobody should mistake them for enforcement of S5/S7 in full. The gaps worth closing cheaply:

- **Plugin guard (L-11):**
  - It misses the `CompletableFuture` *instance* async methods (`thenApplyAsync`, `whenCompleteAsync`, `delayedExecutor`, …). These start common-pool workers on the submitting thread, which is exactly the mechanism its own javadoc names.
  - It misses `Arrays.parallel*`.
  - It misses `HttpClient.new*`. That creates a platform `SelectorManager` thread at construction, plus a lazy cached platform worker pool.
  - It misses `Cleaner.create()` (platform daemon thread), `extends Thread`, and Netty `FastThreadLocal`.
  - Its comment stripper does not handle char literals or text blocks; the engine copy does. A `'"'` hides the rest of its line. A text block containing `/*` switches block-comment mode on across lines.
  - The two plugin-module copies are byte-identical apart from the module name, which invites drift.
- **Engine guard (L-11 engine half, S5):**
  - It forbids only `new Thread(` / `Thread.ofPlatform(`, and only in `bot-engine`.
  - `Executors.newSingleThreadScheduledExecutor()` / `newCachedThreadPool()` without a virtual factory, which are platform threads started lazily, pass it. Today every engine executor does pass `Thread.ofVirtual()`, which I checked by hand, but nothing keeps it that way.
  - S5's "engine pools must be pre-started or virtual" is also not met by the three engine `HttpClient`s (`ApiGatewayClient:181`, `GameMsClient:23`, `GatewayBudgetRegistry:293`). They use the JDK's default executor, a lazily grown platform pool. Today no plugin frame can reach an HTTP call; strategies call back only into `BetContext` / `BotMemory` / `Random`. Idle workers die after about 60 s, so a pin would be transient anyway. Giving them `.executor(Executors.newVirtualThreadPerTaskExecutor())` would close it outright.
- **L-7:** `import static …ObjectMapperProvider.getDefault;` followed by a bare `getDefault()` bypasses `ObjectMapperProvider\s*\.`, because import lines are skipped. `staticMapper` misses a mapper assigned in a static block or on the next line, `JsonMapper`, and static `ObjectReader` / `ObjectWriter`s.

Fix shape: add the patterns above, flag `import static` of `ObjectMapperProvider` members, and extend the engine thread guard to `bot-app` and to `Executors.new*` calls that do not carry `ofVirtual` on the same statement. If the plugin-module guard has to live in two modules, put it in a shared test-jar.

### [smell] Version ordering trusts the format; an unreadable mount fails boot instead of falling back
`IsolatedPluginBundleLoader.java:155-179`

- Candidates are ordered by `String` comparison. That is correct for D-7's `yyyyMMdd.HHmmss`, but the loader never checks the format. A hand-stamped `Bot-Plugin-Version` such as `hotfix-1`, `v2` or `builtin` sorts above every timestamp build (`'b'`, `'h'`, `'v'` > `'2'`) and wins every later boot until someone removes it. Reject versions that do not match `\d{8}\.\d{6}` in `inspect`, with a message that names D-7.
- `Files.list(dir)` throwing (for example `AccessDeniedException` on the mount) escapes `load()` as an ISE and fails startup, even when a valid built-in bundle exists. D-11 makes the built-in directory the fallback precisely for "the mount is unusable". Treat an unlistable directory like a rejected candidate: one ERROR, then continue to `builtinDir`. A per-bundle listing failure is already handled that way (`inspect` → `Candidate.invalid`).

### [smell] Split packages: package-private access works in classpath mode and fails only in isolated mode
`bot-strategies` shares `domain.bot.strategy` and `.strategy.slot` with `bot-api` / `bot-engine`; `bot-messages` shares `.message.request` and `.message.slot` with `bot-api`.

Under the isolated loader those are two runtime packages. A plugin class that touches a package-private member of an engine class in the same package name compiles and runs in classpath mode. In isolated mode it throws `IllegalAccessError` the first time the call executes, on a bot thread, not at load. Candidates exist today: `RoundState`'s package-private setters and `StrategyAssignment.apportion`. Nothing in the plugin modules uses them now; I grepped. Nothing prevents it either, and neither the equivalence ITs nor L-3 would notice until that code path ran.

Fix shape: either a small guard asserting that no plugin-module source references a non-public member of a same-named engine package (bytecode-level is easiest: scan the plugin jars for `INVOKE*` / `GET*` against package-private owners), or move the plugin classes out of the shared package names. The guard is the cheaper of the two.

### [smell] `IsolatedPluginBundle.context()` hands the child context to anyone holding the bundle
`IsolatedPluginBundle.java:165-168`

It is a public production accessor, documented as "tests read it for L-3 / L-4". It exposes `getBean` on plugin beans past the registries and the D-10 validation. It is also the easiest way for a future root bean to keep a strong reference to a plugin context, which `RootContextHoldsNoPluginRefsTest` (direct fields only) would not see. Make it package-private and give the ITs a narrow accessor, for example `beanDefinitionOrigins()`. The ITs are in another module, so a test-only static helper in the same package of a test-jar would also do.

### [style] The new 503 handler took over the javadoc of the ISE handler below it
`bot-app/src/main/java/com/vingame/bot/common/exception/RestExceptionHandler.java:218-247`

The new javadoc was inserted between the existing AD-8 "Transitional handler" javadoc and `handleIllegalState`. The AD-8 comment is now a dangling doc comment above another javadoc, and `handleIllegalState` has none. Move the new block, with its method, above the AD-8 javadoc. Also, "Must stay declared alongside the ISE arm" is not why the subtype wins: `ExceptionDepthComparator` picks the closest match wherever the handler is declared in the advice. Reword it, or drop the sentence.

### [style] `PluginVersionResolver` bean has no production consumer left
`bot-app/src/main/java/com/vingame/bot/infrastructure/plugin/PluginRuntimeConfiguration.java:120-127`

`BotGroupBehaviorService` was its only production reader, and that use is gone. What remains is the test-seam constructor of `PluginClassLoaderMetrics` and tests. Its lambda now throws `PluginUnpublishedException` after close for nobody. Either remove the bean (and keep the interface for the seam), or note in its javadoc that it is kept for step 5's drain.

### [style] Rejection messages could say which failure it was
`IsolatedPluginBundleLoader.java:92-94`, `IsolatedPluginBundle.java:222-226`

L-3's rejection is clean and actionable: one ERROR per candidate, with a message that says the plugin classes are also on the application classpath and names the fat jar. That is the right result for an isolated start on the 4b jar. The final exception, though, reads "every candidate was rejected (see the ERROR lines above), or there were none". On a 4b image with no `/app/plugins-builtin`, the operator cannot tell from it which case applies. Include the counts, for example `0 candidates found in X, 0 in Y` versus `2 rejected`. When every rejection was the L-3 origin check, add one clause saying isolated mode needs the 4c image. The L-3 ERROR also logs a full stack trace for what is an expected, fully explained rejection; the message alone would do.

## Notes

**Classloader correctness: the parts that are right.**
- **Parent and URLs.** The parent is `PluginBundle.class.getClassLoader()` with parent-first delegation, so contracts, Spring, Jackson, ws-parser and slf4j are the application's `Class` objects. The URLs are `toAbsolutePath().toUri().toURL()` → `file:`.
- **TCCL.** It is set only around `refresh()`, restored in `finally` on the same thread, and never set around engine calls (S6). Scan, close and prototype `getBean` run under the caller's TCCL, which is correct: bean class resolution uses the factory's `beanClassLoader` (= plugin loader, via `prepareBeanFactory`), not the TCCL.
- **Child context.** It has no parent and no shutdown hook. `setClassLoader` runs before scan, so `ScannedGenericBeanDefinition` class names resolve through the plugin loader at refresh.
- **Every path out of `open` releases** the context and loader: zero beans found, a failed refresh, and a failed origin check. `context.close()` after a failed refresh is a no-op (`active` already false), so double-close is harmless. A candidate that fails `inspect` never creates a loader. One rejected by `build` is closed. One rejected inside `open` is released there.

**The D-13 scan deviation is sound.** The candidate set comes from the null-parent `resourcesOnly` loader, so only bundle jar entries are candidates. Annotations are read through `CachingMetadataReaderFactory(pluginLoader)`. To the specific questions:
- **Can it pick up engine classes from split packages?** Not through the scan: `resourcesOnly.getResources("com/vingame/bot/")` sees bootstrap plus bundle jars only. A bundle jar that *contains* an engine class (shaded) would be a candidate, would load parent-first, and would then be rejected by `checkOrigin`. The other way in is a plugin `@Configuration` with `@ComponentScan` / `@Import`: `ConfigurationClassPostProcessor` uses the context's resource loader, which is the parent-first plugin loader, so it would scan the whole classpath. L-3 would catch that too. Today neither plugin module has any `@Configuration`, `@ComponentScan`, `@Import`, `@Bean`, `@Autowired` or `@Value`, which I grepped.
- **Can it load a class twice?** No. `resourcesOnly` never defines a class; ASM reads bytes. The plugin loader defines a class only when the parent cannot find it. Annotation types resolved during the scan go through the plugin loader and are defined once. `CandidateComponentsIndexLoader`'s static soft cache is keyed by `resourcesOnly`, which has a null parent, so it cannot pin the plugin loader.
- **Caveat.** The scan relies on the bundle jars having directory entries. `getResources("com/vingame/bot/")` returns nothing for a jar built without them, and the bundle is then rejected as "no plugin beans". Maven's jar plugin writes them. A jar repacked with `zip -D` would not have them.

**What L-3's load-time check covers.** It checks that every non-infrastructure **bean** class is defined by the plugin loader. That is enough to reject the 4b fat jar, where everything is shadowed. It would not notice a partial overlap in which a non-bean plugin class (a message POJO, a `Body`) is also on the parent while the beans are not. `PluginTypeIdentityIT` covers that for the shipped bundle at build time. Neither check runs against an arbitrary mounted bundle at runtime.

**Netty pre-start ordering is genuinely enforced at creation time.** `eventLoopGroup.getIfAvailable()` is evaluated as an argument before `buildRuntime`, and `buildRuntime` calls `requireStarted` before the loader opens anything. The check reads live threads rather than asking the executors, so it cannot start a lazy executor itself. Two things to know:
- `ObjectProvider` resolution inside the factory method does **not** register a Spring dependent-bean edge, so destroy order between `pluginRuntime` and `eventLoopGroup` is unspecified. That is harmless at shutdown. It does mean the `NettyEventLoopConfig` javadoc's "a bean dependency rather than a convention" is true for creation order only.
- `ClientFactory`'s new `requireSharedEventLoopGroup` throw is unreachable in production, because `BotFactory`'s group is a required constructor argument. It is the right belt-and-braces.

**Spike rules S1-S8 against 4b.**
- S1: guarded (L-7, with the gaps in smell 4).
- S2, S3: done in 4a, and reclaim is proven by L-14.
- S4: not code-guarded in this diff. Compliance tracks it as the L-9 dependency gate on `micrometer-core`.
- S5: Netty is pre-started and checked; `GameMsClient` is virtual. JDK `HttpClient` pools and unguarded `Executors.new*` remain (smell 4).
- S6: met.
- S7: plugin `ThreadLocal`s guarded.
- S8: step 6.
- The spike's "confirm Boot's jar cache in a real Boot run" stays open until L-15 runs in 4c, and it should be extended to file handles (smell 1).

**Trust boundary.** Whoever can write `./plugins-dist` on the host runs code in a JVM that holds agency tokens. That is inherent to the design, and the mount is `:ro` inside the container. The SHA-256 on the boot line is evidence after the fact, not a gate. Nothing verifies it against a manifest or allowlist. That is worth stating in the 4c operator docs. It is not a finding against this diff.

**Out of scope but seen.** `GameMsClient.deposit` logs the full agency token at DEBUG and ERROR (`"Deposit failed for agency token {}", agencyToken`). It also swallows the interrupt status in both catch blocks. Both are pre-existing; the diff only changed the thread construction. The token logging contradicts the codebase's own truncation convention and should go on the follow-ups list.

**Test seams in production.**
- Package-private `buildRuntime` and `IsolatedPluginBundleLoader.candidates` / `sha256`: fine.
- Public static `NettyEventLoopConfig.executorCount` / `startedExecutorCount`: fine, production uses them across packages.
- `PluginRegistries.catalogue()` is public and test-only. It is harmless, read-only, and documented as such.
- `IsolatedPluginBundle.context()` is the only one with teeth (smell 7).

**`bot-plugin-dist`.**
- It correctly takes no dependency on the plugin modules at any scope; the jars arrive by `dependency:copy`.
- The `prepare-package` clean together with `forceCreation` in both plugin poms closes the stale-bundle and stale-manifest pair.
- Failsafe is bound explicitly with id `default`.
- `ReclaimRehearsal`'s 20 × (`gc` + 150 ms) bound matches the plan.
- The negative controls (a cold Netty group started inside `decide()`; subtypes registered on `getDefault()` in a forked JVM) are what make the positive results meaningful.
