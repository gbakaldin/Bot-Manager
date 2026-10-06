# Code Review — PLUGIN_HOT_RELOAD_3_4, Phase 3b

Branch: feature/plugin-hot-reload-3-4
Reviewed diff: `git diff 5ce2d0e..HEAD`. Commits: `5713ec7` (review-3a advisory fixes),
`1688648` (D-6 scopes), `aefc485` (enforcer), `e2037d4` (D-7 manifest).

## Verdict

CHANGES_REQUESTED

One `bug`: the D-7 bundle version does not hold its own invariant. Two jars built in the
same reactor session can carry different `Bot-Plugin-Version` values, and I reproduced it.
Nothing reads the value yet, so nothing breaks today. But the fix is one line and has to
land before 4b's loader starts rejecting bundles whose jars disagree. Everything else is
advisory.

## Findings

### [bug] `Bot-Plugin-Version` goes stale on any build without `clean`, so the two jars of one bundle can disagree
`bot-strategies/pom.xml:86-98`, `bot-messages/pom.xml:91-103` (the `maven-jar-plugin`
blocks), and the claim in `pom.xml:49-56`

`maven-jar-plugin` 3.4.2 defaults `forceCreation` to `false`. When a jar's inputs (the
files under `target/classes`) are not newer than the existing jar, plexus-archiver treats
the archive as up to date and does not rebuild it. That means the manifest is not
regenerated either. The manifest is not one of the inputs that check looks at, so a new
`${maven.build.timestamp}` does not force a rebuild.

I reproduced this in the worktree with `mvn -o package -DskipTests -pl
bot-strategies,bot-messages` (no `clean`). No production files were changed. The jars were
rebuilt and consistent again at the end.

| Step | bot-strategies | bot-messages |
|---|---|---|
| Starting state (Dev's build) | `20261006.113708` | `20261006.113708` |
| Rebuild, nothing changed | `20261006.113708` | `20261006.113708` (both stale, but they still match) |
| `touch` one class under `bot-strategies/target/classes`, then rebuild | **`20261006.113846`** | **`20261006.113708`** |
| Rebuild with `-Dmaven.jar.forceCreation=true` | `20261006.113901` | `20261006.113901` |

The third row is the failure. It happens in **one reactor session**, and that is exactly the
case the root-pom comment says cannot happen ("bot-strategies and bot-messages built
together carry the same value"). All it takes is an ordinary edit to one plugin module
followed by `mvn package`, the command `CLAUDE.md` lists. The `Dockerfile` copies a
locally built `bot-app/target/Bot-1.0.jar`, so a non-clean local build is the normal path
to a deploy. `spring-boot:repackage` copies whatever plugin jars are in `target/` into
`BOOT-INF/lib`, including mismatched ones.

What it costs depends on the phase:
- **Today (3b):** nothing reads the value. The only consumer is the plan's local gate,
  which can show two different values and look like a Dev mistake.
- **From 4b/4c:** D-7 says a bundle whose jars disagree is invalid. The loader would reject
  it, and with D-8 (no built-ins left) that means no strategies and no message providers.
  Failing safe still means failing.
- **The silent variant is worse:** with no edits at all, both jars keep an old stamp. The
  "newer" check is a string comparison, so a genuinely new build looks the same as, or
  older than, the bundle already deployed.

**Fix:** add `<forceCreation>true</forceCreation>` to the `maven-jar-plugin`
`<configuration>` in both plugin poms. The last row of the table shows it works. The cost
is re-zipping two jars of about 30 KB and 130 KB on every build. If you would rather not
force it, the root-pom comment has to say that the invariant needs `clean`, and 4b's
`PluginJarContentsIT` or the bundle loader has to report the mismatch clearly. Forcing
creation is the better choice.

D-7 marks UTC as **UNVERIFIED**. It is now confirmed: the jar entries are stamped 15:37
local time (+0400) and the manifest says `.113708`. Record that in the plan.

### [smell] The enforcer is opt-in per module, so a new module or a dropped declaration silently escapes it
`pom.xml:114-146`, plus the three bare `<plugin>` declarations in `bot-api`, `bot-engine`
and `bot-app`

The rule is correct in the places it runs:
- **Patterns.** `g:a:*:*:scope`. In enforcer 3.5 a classifier that is not given matches
  any classifier, so a classifier or a `test-jar` type does not get around it.
- **`<optional>true</optional>`.** This leaves the resolved scope at `compile`, so the rule
  still catches it.
- **Transitive paths.** `searchTransitive` defaults to `true`. A transitive compile path
  into `bot-app` is caught, or is overridden by the direct `runtime` declaration, which
  wins Maven's scope mediation. Either way the plugin classes stay off `bot-app`'s main
  compile classpath.

The weak point is how it is switched on. The pluginManagement entry does nothing until a
module declares the plugin. So:
1. A new engine-side module, such as a future Up Down engine module or the Fleet work, gets
   no protection unless its author knows to add the declaration.
2. Removing one `<plugin>` block in `bot-engine` turns the guard off as quietly as widening
   the scope does. That is the edit the rule exists to stop.

`<scope>system</scope>` with a `systemPath` to a plugin jar also isn't banned. That one is
contrived, but adding `system` to the excludes costs nothing.

**Fix shape (advisory):** turn it around. Bind the execution in the root
`<build><plugins>` so it runs in every module, and set `<skip>true</skip>` in the two plugin
modules, where a ban on themselves is meaningless anyway. Then the default is "guarded".
Also add `com.mercury:bot-*:*:*:system` excludes. `-Denforcer.skip` is still an escape
hatch, which is unavoidable and fine.

### [style] bot-messages pom still describes the pre-3b module graph
`bot-messages/pom.xml:22-24` and `:61-68`

- The `spring-context` comment says the dependency "introduces no reverse edge in
  `bot-app -> bot-engine -> {bot-strategies, bot-messages} -> bot-api`". Since `1688648`,
  `bot-engine` has no compile edge to either plugin module. The graph is now
  `bot-app -> bot-engine -> bot-api <- {bot-strategies, bot-messages}`, with plugins at
  test/runtime scope above that. The point this comment exists to make, that the module
  picks up no engine edge, is now enforced by the build, so the comment can just say so.
- The `<description>` still ends "Depends only on bot-api and Jackson/Lombok". The pom now
  also declares `spring-context` and `websocket-parser-core`, all `provided`. The
  bot-strategies description says "bot-api (contract module) and framework libraries",
  which is accurate. Use the same wording here.

## Notes

**L-2 and the default `META-INF/maven/**` descriptor: recommendation.** As built, each
plugin jar contains `META-INF/maven/com.mercury/<artifactId>/{pom.xml,pom.properties}` plus
directory entries (`META-INF/`, `com/`, ...). L-2, read literally ("only
`com/vingame/bot/domain/bot/{message,strategy}/**` classes plus `META-INF/MANIFEST.MF`"),
fails on the descriptor.

Recommendation: **turn the descriptor off. Do not widen L-2.** Add
`<addMavenDescriptor>false</addMavenDescriptor>` to the same `<archive>` block D-7 already
owns in both plugin poms. Reasons:
- **It keeps L-2 an exact allowlist.** Every exemption weakens an allowlist meant to catch
  shaded code, and `META-INF/maven/**` is exactly the path shaded third-party jars bring
  with them (`META-INF/maven/<their-group>/...`). If L-2 allows that prefix, it has to
  match on our groupId and artifactId to still catch them, which is more fragile than
  having no exemption.
- **It removes a second, misleading version.** `pom.properties` carries `version=1.0`. D-7
  says outright that this carries no information and must not be used. If a jar holds only
  `Bot-Plugin-Version`, nobody can read the wrong one.
- **Nothing consumes it.** Spring Boot's repackage, `classpath.idx` and layering take
  coordinates from the Maven model, not from inner descriptors. The only things that lose
  out are SBOM scanners, and they don't need to identify our own internal artifacts.

Whichever way it goes, write `PluginJarContentsIT` over **file** entries (skip names ending
in `/`), or it fails on the directory entries every jar has. If someone does want the
descriptor kept, the fallback is to allow exactly
`META-INF/maven/com.mercury/<Bot-Plugin-Name>/pom.(xml|properties)` and nothing broader.

**Scope semantics check out.**
- **`runtime` in bot-app.** `spring-boot-maven-plugin` packages compile and runtime scope.
  The current `Bot-1.0.jar` holds all four `bot-*` jars in `BOOT-INF/lib`, and bot-app's
  test classpath (compile + runtime + provided + test) still sees the plugin classes, so
  `Starter`'s scan and `ApplicationContextLoadsTest` find the same beans. IntelliJ also
  puts `runtime` on the run configuration's classpath, so running `Starter` from the IDE
  is unaffected.
- **`test` in bot-engine.** This is not transitive, so nothing leaks up into bot-app.
- **Plugin modules `provided`.** This makes bot-api's own compile dependencies provided too,
  and none of them are transitive. Every consumer gets them directly from bot-api, so
  nothing goes missing.
- **Dropping `jakarta.annotation-api` from bot-strategies.** This is safe: nothing under
  either plugin module's `src/main` uses `jakarta.annotation` or `@PostConstruct`.

**Lombok `provided` is fine.** The processor runs from the root
`maven-compiler-plugin` `annotationProcessorPaths`, not from the classpath. The `provided`
dependency only puts the annotations on the compile classpath, which is all it needs to do.
Both plugin modules had lombok `provided` before 3b anyway.

**The timestamp property touches almost nothing else.**
- **Resource filtering.** The only filtering in the reactor is Spring Boot parent's:
  `application*.{properties,yml,yaml}`, `@`-delimited only (`useDefaultDelimiters` off),
  and only in bot-app. Those files contain no `@…@` tokens, so nothing gets substituted.
- **Format override.** Overriding `maven.build.timestamp.format` changes every
  `${maven.build.timestamp}` in the reactor, but `bot.plugin.version` is the only use.
  There is no `build-info` goal or git-commit-id plugin.
- **Reproducibility.** `project.build.outputTimestamp` is unset, so the build was never
  reproducible, and a per-session stamp doesn't make it worse. If reproducible builds
  arrive later, remember that `Bot-Plugin-Version` is deliberately non-reproducible and
  must not be pointed at `outputTimestamp`. If it were, two different builds could share a
  version.

**Stale javadoc from review-3a is fixed correctly.** I checked the `5713ec7` claims against
the tree. `MessageTypesRegistryTest` / `MessageTypesCoverageTest` are in bot-engine.
bot-messages `src/main` has no logger. All three registries are outside both of
`PerBotInfoLogGuardTest`'s banned trees, whose exemption list is empty. The
`GameRequestFactory` rewrite now rests on the capability argument alone, as review-3a
asked.

**For the author:** the enforcer negative control (set bot-app back to `compile`, see the
failure, revert) is the plan's required proof. Make sure it is in the 3b handoff. I did not
repeat it.
