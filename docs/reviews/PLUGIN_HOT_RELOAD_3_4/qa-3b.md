# QA — PLUGIN_HOT_RELOAD_3_4 Phase 3b

**Verdict:** PASS. Every 3b gate is met on a clean build. F1 below is a real D-7 defect, found independently by review-3b. It should be fixed before 4b, and the fix is two lines.
**Build:** `mvn -B clean verify` at `e2037d4` → **3226 tests, 0 failures, 0 errors, 0 skipped** (surefire). Failsafe produced 0 reports, which is expected before 4b.

Range: `5ce2d0e..e2037d4` (`5713ec7`, `1688648`, `aefc485`, `e2037d4`). `e799d21` and `dcac1d0` landed while QA was running. Both are docs only (review-3b, compliance-3ab, a plan changelog). Worktree `.claude/worktrees/plugin-3-4`, JDK 21.0.2. All the experiments below ran in throwaway detached worktrees in the session scratchpad, and those worktrees have been removed.

## Dev's claims, checked rather than trusted

| Claim | How checked | Result |
|---|---|---|
| Surefire total unchanged: 3226 (148 / 90 / 261 / 1114 / 1613) | Ran `clean verify` at HEAD and summed the `TEST-*.xml` files per module | **Confirmed exactly**: api 148, strategies 90, messages 261, engine 1114, app 1613. 0 failures, 0 errors, 0 skipped |
| Failsafe 0 | Looked for `*/target/failsafe-reports` | None exist. Expected until 4b |
| Fat jar `BOOT-INF/lib` is the same 126 entries as at 3a | Built `5ce2d0e` in its own detached worktree with `clean package -DskipTests`. Ran `unzip -l` on both jars, sorted the lists and diffed them | **Identical**: 126 = 126, 0 diff lines. Both plugin jars are still present. That is plan gate 1, and the expected value is 2 |
| Nothing the app needs at runtime arrives only through a plugin module | `dependency:tree -Dscope=runtime -pl bot-app -am` | In the app's runtime tree, `bot-strategies:runtime` and `bot-messages:runtime` are **leaves**. Every library in `BOOT-INF/lib` comes in through api, engine or app. `jakarta.annotation-api`, which `5713ec7` dropped from strategies, still arrives via `bot-engine` (compile) |
| App boots, and the registries are the same as at 3a | Booted both fat jars (`java -jar`, console-only log4j config, unreachable Mongo) | `Started Starter` on HEAD. The four registry lines (9 betting strategies, 2 slot strategies, MessageTypesRegistry BETTING_MINI 6 / TAI_XIU 3 / SLOT / CASHOUT 1 / CRASH 1, 7 validators) are **byte-identical** between the 3a jar and the 3b jar. No `ClassNotFound` or `NoClassDefFound` |
| Compiler rejects plugin classes in engine/app main | Added probe classes to `src/main`, then `compile -pl <m> -am` | **All rejected** with `cannot find symbol`. bot-engine: an import (`strategy.slot.FixedBetStrategy`), the **same package with no import** (`domain.bot.strategy` → `RandomBehaviorStrategy`; engine's `BettingStrategyFactory` really does live in that package), and a split package for a message (`message.taixiu` → `TaiXiuStartGameMessage`). bot-app: the same three shapes. (Reflection by string, e.g. `Class.forName`, is not a compile-time reference, so the compiler cannot catch it. 3a's grep covered it, and runtime scope deliberately keeps the classes loadable until 4c.) |
| Enforcer rejects direct compile/provided widening | Edited one scope at a time, then ran `validate` | **Rejected**: app strategies→compile, app strategies→provided, app messages with the scope removed (= compile), engine strategies→compile, engine messages→provided. All fail in `bot-app` / `bot-engine` with "banned via the exclude/include list". **Allowed**, as intended: app messages→test (the 4c target) |
| Enforcer rejects transitive widening | Added a scratch reactor module `bot-qabridge` with `bot-strategies` at compile scope | **T1** bot-app keeps the direct `runtime` and adds `bot-qabridge` (compile): **rejected** (`on project bot-app`). The resolved scope here is still `runtime` by nearest-wins, so the rule is *stricter* than the classpath, because it walks the unmediated graph. That is the safe direction. **T2** bot-app drops the direct dependency and gets it only through the bridge: **rejected**. **T3** bot-engine keeps `test` and adds the bridge: **rejected** |
| `Bot-Plugin-Version` (UTC `yyyyMMdd.HHmmss`) + `Bot-Plugin-Name` only on the two plugin jars | `unzip -p … MANIFEST.MF` on all five module jars, the fat jar's own manifest, and the copies nested in `BOOT-INF/lib` | strategies and messages both have `Bot-Plugin-Version: 20261006.113901` and their own `Bot-Plugin-Name`. api, engine, app and the fat-jar manifest have neither. The nested copies match. **UTC confirmed**: the build started 15:38 local (+04) and the stamp reads 11:39 |
| Plugin poms' `provided` set is complete, and nothing was lost in Dev's re-applied edit | Read the committed poms against D-6 and Phase 3b step 1, ran `dependency:tree -pl bot-strategies,bot-messages` (not `-q`), and listed the jar contents | strategies: bot-api, lombok, spring-context, slf4j-api, all `provided`. messages: bot-api, lombok, jackson-annotations, websocket-parser-core, spring-context, all `provided`. Everything else is `test`. **No compile or runtime dependency remains** in either module, so each runtime tree is a bare leaf. Jackson in strategies and slf4j in messages come provided-transitively through bot-api. Neither module uses them directly, and both compile. Micrometer core/registry: 0 hits. Jar contents: `com/vingame/**`, the manifest and the module's own `META-INF/maven` descriptor only, with no shaded code. The committed D-6 shape matches the plan, and I found nothing dropped |
| `5713ec7` is non-behavioural | Read the diff | Javadoc/comment changes plus removal of the dead `jakarta.annotation-api` dependency from strategies. It still compiles, and its 90 tests pass |
| Nothing else uses `maven.build.timestamp` (the format is now set globally) | `grep` for `build-info` / `BuildProperties` / `outputTimestamp` / `maven.build.timestamp` | No other consumer |

## F1 — `Bot-Plugin-Version` goes stale on non-clean builds (corroborates review-3b [bug])

I reproduced this independently in a scratch worktree with `package -pl bot-api,bot-strategies,bot-messages`:

| Step | strategies | messages |
|---|---|---|
| `clean package` | `…114619` | `…114619` |
| `package` again, nothing changed | `…114619` (stale, not this session's value) | `…114619` (stale) |
| touch one messages source, `package` | `…114619` | `…114633`: **the bundle disagrees with itself** |
| the same with `<forceCreation>true</forceCreation>` added to both plugin jar configurations | `…114708` | `…114708`: agree, and fresh |

D-7's "one value per reactor session, so both jars agree" holds only under `clean`, because maven-jar-plugin's up-to-date check skips re-archiving a module whose classes did not change. Today this is harmless: nothing reads the manifest until step 4, and classpath mode reports `builtin`. Once a bundle loader validates "every jar carries the same value", a routine non-clean local build would produce an invalid bundle, or a stale "newer" comparison. **Fix (verified above):** add `<forceCreation>true</forceCreation>` to the `maven-jar-plugin` configuration in `bot-strategies/pom.xml` and `bot-messages/pom.xml`. Land it before 4b. QA did not change production poms.

## Note — build timestamp and reproducibility / Docker caching

The timestamp makes the two plugin jars differ byte-for-byte on every clean build. **It does not change the deploy's caching behaviour.** The Dockerfile `COPY`s the whole `Bot-1.0.jar` as one layer, and that jar was already non-reproducible: no `project.build.outputTimestamp` is set, so zip entry times change on every build. That layer was therefore already rebuilt every time, and the other layers (base image, apt, user and mkdir) come before it and are unaffected. `deploy.sh` runs no Maven at all. If reproducible builds are ever adopted (`outputTimestamp`), this manifest attribute is the one thing that will defeat them, and it does so by design. The same is true of any future layered-jar split: the two plugin jars will always invalidate their own layer.

## Process note

Another agent (review-3b, judging by its table, whose `113901` value matches my build) appears to have run Maven in this same worktree while my `clean verify` was in flight. My counts are exact, and every report had 0 failures, so nothing visibly collided. Still, concurrent builds sharing one `target/` directory can make each other's evidence non-attributable. Prefer one builder per worktree.

## Tests added / updated

None. Everything 3b introduces is enforced by the build itself, and each guard was exercised above in both directions: compiler scopes (6 probes), enforcer (5 direct, 3 transitive, 2 allowed). The test-count gate is unchanged by design. A unit test cannot cheaply pin F1: it is a build-plugin configuration issue, and the 4b `PluginJarContentsIT` / dist-assembly gates are the natural place to assert "both jars carry the same, fresh `Bot-Plugin-Version`". I recommend that assertion for 4b.

## Coverage of the diff

- `bot-engine/pom.xml` (test scope) ← compiler probes (import, same-package, split-package message) and enforcer experiments. The engine's 1114 tests still see real strategies and providers
- `bot-app/pom.xml` (runtime scope) ← compiler probes, enforcer experiments, the `BOOT-INF/lib` diff, a fat-jar boot, `ApplicationContextLoadsTest` (Starter scan, green)
- `bot-api/pom.xml` (enforcer bound) ← no executable test. bot-api cannot declare a plugin module because the reactor rejects the cycle first (per Dev, consistent with the module graph), so the rule there is defence in depth only
- `pom.xml` (enforcer pluginManagement, D-7 properties) ← the above, plus a manifest inspection
- `bot-strategies/pom.xml`, `bot-messages/pom.xml` (provided set, manifest) ← `dependency:tree`, jar listing, manifests, F1 experiment
- `5713ec7` java/test files ← comment-only. Covered by the unchanged test totals

## Gaps

- **F1** (above). Open; must be fixed before 4b.
- **The enforcer does not stop `bot-engine` taking a plugin module at `runtime`.** Verified: engine messages→runtime passes `validate`. The compiler still rejects plugin classes in engine main, so D-2 holds. But a runtime-scoped engine dependency propagates to the app transitively. When 4c turns bot-app's direct dependency into `test`, it would silently put the plugin jars back into `BOOT-INF/lib`. D-6 asks only for compile/provided, so this is within the plan. Worth adding `runtime` to the banned scopes **for bot-engine only** (bot-app needs runtime until 4c), or covering it with a 4c `BOOT-INF/lib` gate.
- `Class.forName("…strategy.X")`-style references from engine/app main are invisible to both the compiler and the enforcer. That is by design under runtime scope, and 3a's whole-word grep found none.
