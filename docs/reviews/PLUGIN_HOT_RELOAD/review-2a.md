# Code Review — PLUGIN_HOT_RELOAD Phase 2a

Branch: `feature/plugin-hot-reload-2a`
Reviewed diff: `git diff 4d3bca7..HEAD` (2 commits: `563a1c3`, `5ca4cc7`)

## Verdict

PASS

No `bug` and no `security` findings. Three smells and one style, all advisory.

**Safe to merge into `feature/plugin-hot-reload` and deploy** — with one hard
precondition, below, which is about the working tree and not about the diff.

### Precondition: the worktree is dirty and must not be committed as-is

`git status` in `.claude/worktrees/agent-a14133275b3cbd744` shows an uncommitted
modification to
`bot-strategies/src/main/java/com/vingame/bot/domain/bot/strategy/martingale/ParoliAggressive.java`:

```java
-@Component
+//
```

That is mutation-test dirt (another agent appears to have been running against
this tree while I reviewed — an earlier run of the same suite failed on a
different injected mutation, a trailing space in `@StrategyImpl("FIBONACCI_CAUTIOUS ")`,
which is no longer present). Committed, it would silently drop PAROLI_AGGRESSIVE
from the registry in production.

I verified the **committed** tree separately by exporting `git archive HEAD` into a
clean directory and building there:

- `mvn -o install -DskipTests` — BUILD SUCCESS.
- `mvn -o -pl bot-strategies test` — **118/118 green**, including all 7
  `StrategyCatalogParityTest` cases.
- `mvn -o -pl bot-app test -Dtest=ApplicationContextLoadsTest` — green, boot log
  reads `registered 9 strategies — [RANDOM, MARTINGALE_CLASSIC_AGGRESSIVE, ...]`
  and `registered 2 strategies — [FIXED, RANDOM]`.

So HEAD is sound. Only the uncommitted file is not.

## Findings

### [smell] Registry iteration order is now classpath-scan order, and that order leaks into an INFO line and two exception messages
`bot-strategies/src/main/java/com/vingame/bot/domain/bot/strategy/BettingStrategyFactory.java:88-89, 103-104`
`bot-strategies/src/main/java/com/vingame/bot/domain/bot/strategy/slot/SlotStrategyFactory.java:76-77, 92-93`

`EnumMap.keySet()` iterated in **enum declaration order** — deterministic, identical
on every machine, and identical to the order the enum reads in source.
`LinkedHashMap.keySet()` iterates in **insertion order**, which here is Spring's
component-scan order. For a classpath directory scan that is derived from filesystem
listing order and is not a contract; it can legitimately differ between an exploded
`target/classes` run and a jar run, and between filesystems.

Two observable consequences, both cosmetic, neither a correctness problem:

1. The startup INFO line's list is re-ordered. Measured on the clean HEAD build it is
   now `[RANDOM, MARTINGALE_CLASSIC_AGGRESSIVE, MARTINGALE_CLASSIC_CAUTIOUS,
   DALEMBERT_AGGRESSIVE, DALEMBERT_CAUTIOUS, FIBONACCI_AGGRESSIVE, FIBONACCI_CAUTIOUS,
   PAROLI_AGGRESSIVE, PAROLI_CAUTIOUS]`, where the enum's own order puts the two
   MARTINGALE_CLASSIC entries second/third and PAROLI before DALEMBERT. AD-23 says
   "same log lines"; strictly this one is not. The `registered {} strategies` count that
   verification P2-2 greps for is unaffected, so nothing operational breaks — but the
   releaser's differential smoke should be told to expect it rather than treat it as a
   regression.
2. The `— strategies present: [...]` tail of `create()`'s `IllegalArgumentException`
   is the string an operator pastes when a group fails to start. Having it come out in a
   different order in the IDE than on the box is a small but real diagnostic annoyance.

Fix shape (one line each, no behaviour change): render the *message* from a
`new TreeSet<>(registry.keySet())` in the three places above, and leave the map itself
insertion-ordered. Sorting at startup and at failure time costs nothing.

### [smell] The `registry` javadoc claims a stability property the platform does not provide, and points at the wrong AD
`bot-strategies/src/main/java/com/vingame/bot/domain/bot/strategy/BettingStrategyFactory.java:55-59`

> `LinkedHashMap` and not a sorted map: the insertion order is Spring's bean-discovery
> order, which is what AD-21's display ordering sorts *from*.

Two things are off. First, AD-21 specifies a **total order that does not consult the
registry's order at all**: built-ins sort into `StrategyId` declaration order, then any
non-built-in key alphabetically. Whatever order `registeredKeys()` hands Phase 2d, the
output is the same — so the registry is not "the base AD-21 sorts from", and Phase 2d
will not be weakened if this map were a `HashMap`. Second, "Spring's bean-discovery
order" is presented as if it were a stable base; per the finding above, it is not one.

The claim is harmless today precisely because nothing depends on it. It is worth
correcting anyway, because the comment reads as a licence for the next person to depend
on registry order — and that is a defect waiting to be written, especially once plugin
beans start arriving from a second classloader whose scan order is genuinely arbitrary
relative to the engine's. The accurate statement is narrower: `LinkedHashMap` keeps
iteration deterministic within a JVM run and avoids hash-order surprises in logs; any
consumer that needs a specific order must impose it itself.

The one-line twin on `SlotStrategyFactory.java:47` is fine as written.

### [smell] `@Deprecated` without `forRemoval = true` is the weakest possible form of the seam's expiry date
`bot-strategies/src/main/java/com/vingame/bot/domain/bot/strategy/BettingStrategyFactory.java:118`
`bot-strategies/src/main/java/com/vingame/bot/domain/bot/strategy/slot/SlotStrategyFactory.java:107`

The javadoc says these are removed in Phase 2b. `@Deprecated(since = "…", forRemoval = true)`
would make javac emit *removal* warnings — a distinct, louder diagnostic — at the two
call sites that still use them (`BettingMiniGameBot.java:178`, `SlotMachineBot.java:160`),
and would survive an IDE's "suppress deprecation" habit better than a bare
`@Deprecated`. Given the repo already carries a standing backlog item "Replace all
deprecated API usage, remove deprecated classes and methods", a plain `@Deprecated` in a
codebase with other plain `@Deprecated`s is easy to normalise into permanence. This
annotation is currently the only mechanical thing preventing that.

### [style] Doc drift: a forward-looking plan still names the retired method
`docs/plans/BOT_GROUP_CONFIG_VALIDATION.md:83` — "expose `registeredIds()`. This is the
exact shape to copy."

That method no longer exists, and Phase 2b's AD-15 is exactly the work that will read
this line. One-word fix to `registeredKeys()`. The other three hits
(`SLOT_MACHINE_BOT.md:626`, `MARTINGALE_STRATEGIES.md`) are historical verification
records of shipped phases and should be left alone. `docs/plans/` is not Dev's to edit
under this workflow, so flagging rather than requesting.

## Answers to the five questions put to this review

**1. The deprecated `create(StrategyId)` / `create(SlotStrategyId)` overloads — sensible
seam, or the coupling AD-12 exists to remove?**

Sensible seam, and it is not the coupling AD-12 targets. AD-12's target is *the registry
key*, and the registry is now genuinely `String`-keyed: a plugin can register `"FOO"`
with no enum constant anywhere, and `create("FOO")` resolves it. What the overload
preserves is an **adapter at the boundary**, whose entire body is `id.name()`. It cannot
re-introduce parent-classloader coupling into the registry because it never touches the
map's key type — it only narrows what *the engine* can ask for.

That narrowing is the honest caveat, and it should be stated plainly rather than sold as
a win: **after 2a the engine still cannot request a plugin key**, because
`BettingMiniGameBot.java:176` and `SlotMachineBot.java:156-158` hold `StrategyId` /
`SlotStrategyId`-typed values sourced from `BotConfiguration`, and those are what they
pass. Phase 2a therefore buys zero plugin capability on its own. The plan says exactly
this ("the enums are still the only keys anything passes"), so this is a correctly-scoped
sub-phase, not an overreach — but it means the overload is load-bearing until 2b lands
and must not be allowed to become furniture.

Will it still be there at step 5, when a plugin key with no enum constant arrives? Only
if 2b never ships. If it survives to step 5 it becomes an active hazard, because
`create(StrategyId)` will look like a legitimate second entry point while being provably
incapable of addressing half the registry. That is what the `forRemoval = true` smell
above is about — it is the cheapest available guard against the seam outliving its
phase.

**2. `EnumMap` → `LinkedHashMap<String, …>` — thread-safety, init order, and the four
behaviours.**

- **Thread-safety: unchanged, and unchanged is fine *for 2a*.** Neither map is
  thread-safe; both are written only inside `@PostConstruct init()` and never mutated
  afterwards. Safe publication to bot threads comes from Spring's singleton registry
  plus `Thread.start()` on the virtual threads that later call `create()`, exactly as
  before. No `volatile`, no lock, and none needed while the map is effectively immutable.
  See the forward-looking note below for why this stops being true at step 5.
- **Initialisation order: unchanged.** Same `@PostConstruct`, same
  `List<BettingStrategy>` constructor injection, same guarantee that the factory is
  fully initialised before any bean that depends on it is handed the reference.
- **Duplicate-key detection: identical for equal keys** (`Map.put` returning the previous
  value, `IllegalStateException` with both class names). One genuine semantic change,
  correctly identified and covered by Dev: keys that *differ* no longer collide, so a
  typo'd literal now registers a phantom key instead of tripping the duplicate guard.
  `StrategyCatalogParityTest.keysAreCleanLiterals` and
  `everyBettingBuiltinNameIsRegistered` are what catch that — and I saw both fire, with
  actionable messages, against a live `@StrategyImpl("FIBONACCI_CAUTIOUS ")` mutation
  that was in the tree during my first run.
- **Missing-annotation WARN: byte-identical**, SLF4J `{}` form, class name only, once per
  offending bean at startup. Compliant with the Logging Guidelines.
- **Both lookup-failure messages: the `+ id` operand renders identically, confirmed
  rather than assumed.** Neither `StrategyId` nor `SlotStrategyId` overrides
  `toString()`, so `"… for " + StrategyId.RANDOM` was always `"… for RANDOM"`, which is
  what `create("RANDOM")` now produces. I checked the enums for a `toString()` override
  and ran the concatenation to be sure. I also checked the null path, which is the one
  place this could have regressed: `EnumMap.get(null)` returns `null` (its `isValidKey`
  is null-safe) rather than throwing, so the old `create((StrategyId) null)` produced
  `"No BettingStrategy registered for null"`; the new `id == null ? null : id.name()`
  guard plus `LinkedHashMap.get(null)` produces the identical message. Verified
  empirically. Nothing in the diff introduces an NPE. The only part of these two messages
  that changed is the `strategies present:` tail's ordering — finding 1.

**3. Retiring `registeredIds()` rather than keeping it — agree?**

Agree, on both of Dev's grounds, and I verified the first. `grep -rn registeredIds`
across the repo returns **zero** production callers on `4d3bca7` — only tests (all
migrated in this diff) and prose in `docs/`. And an enum-returning view is not merely
redundant, it is actively wrong: its only possible implementation maps `key →
StrategyId.valueOf(key)`, which is a parent-classloader lookup performed on a
plugin-supplied string, and which throws `IllegalArgumentException` the first time a
plugin registers a key — from a method whose job is to *enumerate what is available*.
A registry introspection method that blows up in the presence of the thing the whole
feature exists to enable would be worse than not having it. Deleting it is right; a
`@Deprecated` enum-returning shim would have been the wrong call here even though the
same shim was the right call for `create`, because `create`'s adapter is total and this
one is partial.

**4. The eleven annotation literals — is the safety net proportionate to the lost type
check?**

Yes, and the evidence is unusually direct. The net is three layers:
`StrategyCatalogParityTest` (7 cases over a real `AnnotationConfigApplicationContext`
component scan), `ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated` (the
only place in the build where *Starter's* production scan runs), and
`MartingaleStrategyFactoryWiringTest.registeredKeysForPhase3`, which spells the nine
strings as literals rather than deriving them from the enum — the right instinct, since
these are the persisted BSON values.

While reviewing, two independent mutations were live in the working tree, and the net
caught both with messages that named the actual fault:
`@StrategyImpl("FIBONACCI_CAUTIOUS ")` (caught by `keysAreCleanLiterals` with
`expected: "FIBONACCI_CAUTIOUS" but was: "FIBONACCI_CAUTIOUS "`), and a removed
`@Component` on `ParoliAggressive` (caught by `everyBettingBuiltinNameIsRegistered` and
`scanDiscoversTheWholeCatalogue`). The second is the important one: **the enum type check
could never have caught a bean that stopped being a bean.** AD-13's claim that the test
is strictly stronger than what it replaces is not marketing; it is demonstrated.

Two residual gaps, neither blocking and both correctly out of 2a's scope: the parity
test only constrains keys that *match an enum name*, so a future non-built-in key has no
guard beyond `keysAreCleanLiterals`; and `assertThat(...).hasSize(9)` / `hasSize(2)` will
need a deliberate edit the first time a plugin key is in the fixture, which is a feature
rather than a bug at this stage.

**5. Nothing in a runtime path switches on the enums or uses them as a map key —
verified?**

Inside the two registries, yes: no `switch` on either enum exists anywhere in
`*/src/main/java` (grepped), and both registry maps are now `String`-keyed.

Outside them the answer is "not yet, and the plan says so". `StrategyAssignment.apportion`
(`bot-api/.../StrategyAssignment.java:87`) still builds
`Map<StrategyId, Double> coalesced` — an enum used as a map key on a live runtime path —
and `Bot.strategyId`, `BotConfiguration.strategyId` / `.slotStrategyId`,
`WeightedStrategy.strategyId`, `BotGroup.slotStrategyId`, `BotGroupDTO.slotStrategyId`,
`BotHealthDTO.strategyId` and `BotGroupBehaviorService`'s
`Map<String, StrategyId> strategyAssignment` are all still enum-typed. Every one of them
is an explicit line item in Phase 2b steps 1-3. So AD-12's "no runtime code path" is
satisfied *for the registries*, which is all Phase 2a claims, and the remainder is
scheduled rather than missed. `StrategyController` still lists `StrategyId.values()`,
which is correct until Phase 2d.

`BotGroupBehaviorService.strategyCounts` (`:832-836`) is worth noting as already
string-shaped — it merges into a `TreeMap<String, Integer>` via `strategyId.name()`, so
the tier-1 `strategy mix {…}` INFO line will not change wording in 2b either.

## Notes

**Things done well, worth keeping.**

- The `id == null ? null : id.name()` guard in both overloads. It looks like defensive
  noise and it is not: without it, `create((StrategyId) null)` would have turned a
  documented `IllegalArgumentException` into an NPE. That is the kind of one-character
  behaviour change a "behaviour-identical" phase is supposed to catch, and it was caught.
- `MartingaleStrategyFactoryWiringTest` deliberately keeping its `create(StrategyId)`
  calls, with a javadoc paragraph saying why: it pins that `create(id)` and
  `create(id.name())` resolve the same bean, which is the entire contract of the seam.
  Deleting that coverage in favour of the string API would have left the adapter
  untested.
- The comment on `registeredKeysForPhase3` explaining why the nine strings are spelled
  as literals rather than as `StrategyId.X.name()`. That distinction is what makes the
  test capable of noticing a rename, and it is exactly the sort of reasoning that
  evaporates from a codebase if it is not written down at the assertion.
- `StrategyCatalogParityTest`'s own javadoc naming what it does *not* cover (Boot's
  scan), which is what motivated the second commit adding the `ApplicationContextLoadsTest`
  case. Following your own gap analysis to a second test is the right instinct.

**Forward-looking, for whoever writes step 5 — not findings against this diff.**

- **The registry is a write-once map read without synchronisation.** That is correct
  today and will be wrong the moment a plugin can register at runtime. When step 5
  arrives, do not add a `register()` that mutates the `LinkedHashMap` in place: an
  unsafely-published `LinkedHashMap` can be read structurally corrupt (lost entries or
  worse) in a way the old `EnumMap`'s flat array could not. Swap the whole map behind a
  `volatile` field (build the new `LinkedHashMap`, then assign), or move to a concurrent
  map. Copy-on-write also preserves the insertion ordering the current javadoc cares
  about, so nothing is given up.
- **A duplicate key currently kills the application.** `init()` throws
  `IllegalStateException` from `@PostConstruct`, which fails context refresh. That is
  exactly right while every key is ours and a duplicate is a programming error. It is
  exactly wrong once a third-party plugin can supply a key that collides with a built-in
  — a bad plugin should be rejected, not take down the engine. Needs a policy decision
  before plugins register, not after.
- **Pre-existing, unrelated to this diff, noticed while checking question 5:**
  `StrategyAssignment.java:84-86` comments that "EnumMap preserves enum-declaration
  order which we later use as the secondary sort key", immediately above a
  `new LinkedHashMap<>()` whose tie-break the *next* comment correctly describes as
  insertion order. The first comment is already stale on `main`. Phase 2b touches this
  method; that is the moment to delete the stale sentence.
