# Compliance — PLUGIN_HOT_RELOAD Phase 2a

Branch: `feature/plugin-hot-reload-2a`
Plan reviewed: `docs/plans/PLUGIN_HOT_RELOAD.md` (at `4d3bca7`, the branch point)
Diff reviewed: `git diff 4d3bca7..feature/plugin-hot-reload-2a`
Commits reviewed: `563a1c3` (the string-keying), `5ca4cc7` (Boot-scan catalogue test) and
`c237781` (QA's test-only follow-up, landed mid-review — see below). **17 production files
touched, all in `bot-api` and `bot-strategies`**; everything else in the diff is tests and
review docs.
Build: `JAVA_HOME=…/openjdk-21.0.2 mvn clean install` at `5ca4cc7` → **BUILD SUCCESS**, all
five modules, **0 failures / 0 errors**, **1900 tests** (api 128, strategies 118, messages
136, engine 426, app 1092) — above the A1 gate of 1866. Re-run of `bot-strategies` after
`c237781` → **122 tests, 0 failures, 0 errors** (fleet total 1904), matching
`qa-2a.md`'s table. `mvn -pl bot-api dependency:tree` produces no
`bot-app|bot-engine|bot-strategies|bot-messages` line: the contract module still has no
reverse edge.

> Build note, not a finding: the first `mvn clean install` failed in `bot-app` with
> `package com.vingame.bot.domain.bot.core does not exist`. That is a shared-`~/.m2`
> race with another agent's concurrent build overwriting `bot-engine-1.0.jar`, not a
> property of this branch — an immediate rerun of the identical command is green. An
> isolated `-Dmaven.repo.local` cannot be used here: `com.vingame:websocket-parser-core`
> exists only in the user's local repository.

## Verdict

**PLAN_AMENDED** — the diff is accepted as a faithful implementation of Phase 2a. The
amendment (A4) corrects one factual claim inside Phase 2a step 3 that Dev flagged and that
measurement confirms; it changes no Architecture Decision, no code and no shipped
behaviour. **Ship 2a.**

## Phase-by-phase

Phase 2a is a five-step list, checked step by step.

### Step 1 — `@StrategyImpl` / `@SlotStrategyImpl` member becomes `String value()`
Status: **implemented**
Both annotations in `bot-api` now declare `String value()`. Javadoc on each records
AD-12/AD-13 and names `StrategyCatalogParityTest` as the replacement for the type check.
Retention/target unchanged.

### Step 2 — the nine + two impls take string literals equal to the enum names
Status: **implemented**
All eleven edited, each literal byte-equal to its enum constant name; the now-unused
`StrategyId` imports are dropped from the eight martingale classes. Verified against the
enums rather than by eye: `StrategyCatalogParityTest` asserts every `StrategyId` /
`SlotStrategyId` constant name is a registered key, and `keysAreCleanLiterals` pins the
`"RANDOM "` typo shape the plan's Implementation Notes call out. The Implementation Note
"ship the parity test in the *same commit* as the literals" is honoured — both are in
`563a1c3`.

### Step 3 — factories: `LinkedHashMap<String, …>`, `create(String)`, `registeredKeys()`, deprecated enum overloads
Status: **implemented** (with the plan's justification for `LinkedHashMap` corrected — see Drift)
- `EnumMap` → `LinkedHashMap<String, …>` in both factories.
- `create(String)` in both; `create(StrategyId)` / `create(SlotStrategyId)` retained,
  `@Deprecated`, delegating to `create(id.name())` with a null guard
  (`id == null ? null : id.name()`). The guard is not in the plan's wording and is
  correct: the pre-change `EnumMap.get(null)` returned `null` and produced
  `IllegalArgumentException("No BettingStrategy registered for null…")`; a bare
  `id.name()` would have turned that into an NPE. It preserves behaviour, which AD-23
  requires. Not drift.
- **No engine call site moved**, as the step requires:
  `BettingMiniGameBot:178` still passes `StrategyId effectiveId` and `SlotMachineBot:160`
  still passes `SlotStrategyId strategyId`, both through the deprecated overloads.
- Duplicate-key `IllegalStateException` and missing-annotation WARN are textually
  unchanged; only the `id` variable's type moved from enum to `String`.
- `registeredKeys()` returns `Collections.unmodifiableSet(registry.keySet())`, as
  `registeredIds()` did.

### Step 4 — new `StrategyCatalogParityTest` (AD-12)
Status: **implemented**
AD-12 asks for two properties and both are asserted: *(a)* every `StrategyId` /
`SlotStrategyId` constant name is a registered key
(`everyBettingBuiltinNameIsRegistered`, `everySlotBuiltinNameIsRegistered`); *(b)* every
registered key matching an enum name is claimed by **exactly one** bean
(`every*BuiltinKeyIsClaimedByExactlyOneBean`, which round-trips key → bean → annotation
and asserts no class is claimed twice). Discovery is a real component scan, not a
hand-built bean list, so it can also catch a bean that stopped being a bean — the
strengthening AD-13 claims for it. Plugin-supplied (non-built-in) keys are skipped by
design, matching AD-12's "every registered key **matching an enum name**".

### Step 5 — ship; behaviour identical
Status: **implemented, with one recorded and unavoidable exception**
No production code outside the two factories, two annotations and eleven impls changed.
The one observable difference is the ordering inside the once-per-JVM boot line
`BettingStrategyFactory initialized: registered 9 strategies — [...]`, which prints
`registry.keySet()`: `EnumMap` gave ordinal order, `LinkedHashMap` gives discovery order.
No string-keyed map can reproduce ordinal order without an `Enum.valueOf` per key, which is
the coupling AD-12 forbids — so this follows from the plan, not from Dev. It is inside
AD-23 (whose subject is behaviour: strategies assigned, messages parsed, HTTP responses)
and it is now written into verification P2-2 so a releaser does not read it as a
regression. `GET /api/v1/strategy/` is untouched at 2a because `StrategyController` still
enumerates the enum, so P2-3's `diff` passes as written.

## The three adjudicated items

### 1. "insertion-ordered, so AD-21's ordering has a stable base" — Dev is right; **amended (A4)**

Verified independently, not taken on report. The factories log `registry.keySet()` at
`@PostConstruct`; the order is identical under `StrategyCatalogParityTest`'s bare
`AnnotationConfigApplicationContext` scan and under `ApplicationContextLoadsTest`'s full
Spring Boot scan:

```
StrategyId.values()  RANDOM, MARTINGALE_CLASSIC_CAUTIOUS, MARTINGALE_CLASSIC_AGGRESSIVE,
                     PAROLI_CAUTIOUS, PAROLI_AGGRESSIVE, DALEMBERT_CAUTIOUS,
                     DALEMBERT_AGGRESSIVE, FIBONACCI_CAUTIOUS, FIBONACCI_AGGRESSIVE
registry insertion   RANDOM, MARTINGALE_CLASSIC_AGGRESSIVE, MARTINGALE_CLASSIC_CAUTIOUS,
                     DALEMBERT_AGGRESSIVE, DALEMBERT_CAUTIOUS, FIBONACCI_AGGRESSIVE,
                     FIBONACCI_CAUTIOUS, PAROLI_AGGRESSIVE, PAROLI_CAUTIOUS
```

Six of the eight non-`RANDOM` positions differ, in two independent ways: cautious/aggressive
is inverted inside every family, and the families themselves come out
Martingale/D'Alembert/Fibonacci/Paroli instead of Martingale/Paroli/D'Alembert/Fibonacci.
The order is Spring's classpath-scan order — alphabetical by **class file name within
package** (`RandomBehaviorStrategy` precedes the `martingale/` subdirectory because `R`
sorts before `m` in ASCII). It therefore tracks class names, which AD-12 explicitly makes
free to change, and not key names, which are persisted identities.

This is a plan defect on the A1/A2/A3 pattern: a falsifiable claim about the codebase that
is false, that no implementation of step 3 could have made true, and whose damage lands in
a later phase. AD-21 requires built-ins in `StrategyId` declaration order because that
order is a de-facto UI contract; a 2d implementer reading "stable base" as "already the
right order" ships a reshuffled strategy picker, and the only guard is `P2-3`'s
before/after `diff` at release time on staging, which needs a pre-deploy capture that may
not have been taken. Amended — see **Amendments to the plan** below.

Note the secondary point, which is the sharper one: `LinkedHashMap` is still the right
choice, but not for the reason the plan gives. Determinism (a stable boot log, a stable
future diagnostic) is worth having; "a base for AD-21's sort" is not a thing a sort needs.

### 2. `registeredIds()` retired rather than kept — faithful, no amendment

The plan names `registeredKeys()` and does not name `registeredIds()`, which is silence
rather than instruction. Three things resolve the silence in Dev's favour:

- **The step is explicit about which shims to keep** — "Keep **deprecated**
  `create(StrategyId)` / `create(SlotStrategyId)` overloads … so no engine call site moves
  in 2a" — and it names only `create`. An enumeration that lists one deprecated shim and
  not the other reads as deliberate.
- **The stated reason for those shims does not apply here.** They exist so engine call
  sites need not move; `registeredIds()` had **zero** call sites to protect. Confirmed at
  the branch point: `git grep registeredIds 4d3bca7 -- */src/main/*` returns only the two
  declarations. The plan's own Findings say the same ("not called from any production code
  path"), and AD-15 hands its future job to `registeredKeys()`.
- **Keeping it would violate AD-12.** A `Set<StrategyId>` view over a `Map<String, …>`
  needs `StrategyId.valueOf(key)`, which is the enum used as a runtime key — forbidden by
  AD-12 — and would throw `IllegalArgumentException` on the first plugin-supplied key,
  i.e. it would be a method that works only until the feature it belongs to exists.

All test callers were migrated; nothing in the repo references `registeredIds` today.

### 3. Step list versus what shipped — nothing skipped, nothing else deviated

Confirmed above, step by step. The out-of-scope surfaces Dev reports as untouched are
untouched — verified by `git diff 4d3bca7..HEAD` per path, not by report:
`BotConfiguration.java`, `WeightedStrategy.java`, `BotGroup.java` (`slotStrategyId`),
`BotGroupConfigValidationService.java`, `GameMessageTypesResolver.java`,
`StrategyController.java` (at
`bot-app/.../domain/bot/strategy/controller/`, not the path the task named) and
`PLUGIN_PLAN.md` — **all UNCHANGED**. Phases 2b, 2c and 2d have not been started.

## Drift

One item, and it is plan-side. Phase 2a step 3's parenthetical justification for
`LinkedHashMap` states a property the container does not have. The code is correct; the
sentence is not. Corrected by A4 rather than sent back, because there is no implementation
of the step that would make the sentence true and because leaving it would seed the same
error into Phase 2d.

No code-side drift found.

## Commit `c237781`, landed mid-review

QA committed to this branch while this review was being written. Re-checked rather than
assumed: it adds **four test methods** (`deprecatedEnumOverloadMatchesStringKey` and
`deprecatedEnumOverloadRejectsNull`, betting and slot) plus `qa-2a.md`, and touches **no
production file** — `git diff 4d3bca7..HEAD -- '*/src/main/*'` is still the same 17 files.
Both new tests target Phase 2a's own step 3: that the deprecated enum overload resolves the
same bean as the string key, and that its null guard keeps `create((StrategyId) null)` an
`IllegalArgumentException` rather than an NPE — which is the AD-23 behaviour-identity point
this review makes under step 3. In scope, and it strengthens exactly the seam 2b will
delete. The verdict is unchanged.

## Out-of-scope changes

One, additive and benign: `563a1c3`/`5ca4cc7` add a test method
`strategyRegistriesAreFullyPopulated` to the existing `bot-app` `ApplicationContextLoadsTest`,
asserting both registries come up with the full catalogue under the **production** Spring
Boot scan and that `create(...)` resolves. The plan's step 4 names only
`StrategyCatalogParityTest`; it does not forbid additional coverage, this test serves the
same AD-12 contract, it is the build-time twin of verification P2-2, and it touches no
production code. Recorded, not objected to.

## Amendments to the plan

`docs/plans/PLUGIN_HOT_RELOAD.md`, **Amendment — 2026-08-26 (Phase 2a review), A4**, in the
style of A1/A2/A3. What changed:

1. **Phase 2a step 3** — the parenthetical now says the insertion order is Spring's
   bean-discovery order and **not** `StrategyId.values()` order, marked as corrected by A4.
2. **AD-21** — one sentence stating that registry iteration order is never to be used as if
   it were display order, with the measured divergence, and that 2d sorts from
   `StrategyId.values()`.
3. **Verification P2-2** — the bracketed list reorders at 2a and that is expected; compare
   the count and the set, not the sequence; no HTTP response order changes at 2a.
4. **New `## Amendment — 2026-08-26 (Phase 2a review)` section** carrying A4 with the
   measurement table, why it is a plan defect rather than an implementation one, the
   consequences above, and the rule it leaves behind: *an ordering requirement is satisfied
   by a sort, never by a container's incidental order; if a plan step names a collection
   type because of the order it yields, print the order and check it.*

No Architecture Decision changed. No code changed. The Phase 2a diff is accepted as-is.
