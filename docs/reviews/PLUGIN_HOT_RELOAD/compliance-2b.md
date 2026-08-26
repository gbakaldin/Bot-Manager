# Compliance — PLUGIN_HOT_RELOAD Phase 2b

Branch: `feature/plugin-hot-reload`
Plan reviewed: `docs/plans/PLUGIN_HOT_RELOAD.md` (at commit `855a86f`, i.e. as amended by A1–A4)
Diff reviewed: `git diff c3fac8a..63c31c8` (`.claude/worktrees/` excluded — Phase 2c, concurrent, out of scope)

Commits:

| SHA | Subject |
|---|---|
| `d06403d` | feat(strategies): carry strategy identity as a String, including in Mongo (steps 1–4) |
| `63c31c8` | feat(botgroup): reject an unknown strategy key with a 400, explicitly (step 5) |

## Verdict

**PLAN_AMENDED** — the diff is accepted unchanged. One amendment (**A5**) corrects a
falsifiable and false claim in **AD-15's justification**. AD-15's substance, AD-14 in
full, and every other Architecture Decision stand as written.

**Dev's "no plan defect this time" report is correct about AD-14 and incorrect about
AD-15.** AD-14 was verified against reality, not against the diff, and holds. AD-15's
*check* is right; its comparative claim ("strictly better coverage") is wrong in both
directions, and steps 5–7 would have inherited it.

## Build gate

`JAVA_HOME=…/openjdk-21.0.2 mvn clean install` → exit 0, all five modules, **zero failures,
zero errors, zero skipped**. Single run; no repository race observed.

| Module | `c3fac8a` | HEAD | Δ |
|---|---|---|---|
| bot-api | 128 | 128 | 0 |
| bot-strategies | 122 | 120 | **−2** |
| bot-messages | 136 | 136 | 0 |
| bot-engine | 426 | 426 | 0 |
| bot-app | 1092 | 1118 | **+26** |
| **Total** | **1904** | **1928** | **+24** |

Matches Dev's reported 1928 exactly, and is ≥ the A1 gate of 1866. The −2 in
bot-strategies is accounted for: `deprecatedEnumOverloadMatchesStringKey` and
`deprecatedEnumOverloadRejectsNull` tested the deleted overload's delegation; the
null-contract half was retargeted at `create(String)` and survives. The +26 is
`PersistedStrategyKeyCompatTest` (12) + `BotGroupStrategyKeyValidationTest` (12) +
`BotGroupConfigValidationServiceTest` (2).

`mvn -pl bot-api dependency:tree | grep -E 'bot-app|bot-engine|bot-strategies|bot-messages'`
→ **no output**. No reverse module edge.

## Untouched-file confirmation (as requested)

`git diff c3fac8a..HEAD --name-only` contains **zero** entries for
`GameMessageTypesResolver*`, `StrategyController*`, `PLUGIN_PLAN.md`, and
`docs/plans/PLUGIN_HOT_RELOAD.md` (the plan is touched only by this review's A5 commit).
Zero paths under `.claude/`. The diff is 42 files, all `.java`, in the five modules.
Working-tree noise (`deploy.sh`, `docs/plans/AVIATOR_BOT.md`, `TaiXiuMessages/*.js`,
`docs/reviews/VIPTALK_ALERTING_V2/release.md`) is pre-existing, uncommitted, and untouched
by this review.

## Phase-by-phase

### Step 1 — `bot-api` retypes
Status: **implemented**
`WeightedStrategy(String strategyId, double weight)`; `BotConfiguration.strategyId` /
`.slotStrategyId` → `String`; `StrategyAssignment`'s `List<StrategyId>` /
`Map<String, StrategyId>` → `String` (`ApportionmentResult`, `apportion`, `assign`).
All four sites the plan names, no more.

### Step 2 — `bot-engine` retypes and defaults
Status: **implemented**
`Bot.strategyId` → `String`. `BettingMiniGameBot:176` default → `StrategyId.RANDOM.name()`,
`SlotMachineBot:156-158` → `SlotStrategyId.FIXED.name()`. Spelled off the enum rather than
as a bare literal, which is what AD-12 (enum survives as the compile-time catalogue) asks
for. The slot fallback stays in `initializeSubclass` and is not hoisted — the plan's
`slotStrategyId`-null Implementation Note is honoured, and
`PersistedStrategyKeyCompatTest.absentSlotStrategyIdStaysNull` pins it.

### Step 3 — `bot-app` retypes
Status: **implemented**
`BotGroup.slotStrategyId`, `BotGroupDTO.slotStrategyId`, `BotHealthDTO.strategyId` →
`String`. `BotGroupBehaviorService.strategyCounts(Map<String,String>)` — the `.name()` call
moved into the key, so the emitted `Bot group <id>: strategy mix {…}` string is unchanged.
`effectiveStrategyMix`'s fallback is `new WeightedStrategy(StrategyId.RANDOM.name(), 1.0)`.
`createSingleBot`'s defensive `getOrDefault` likewise.

### Step 4 — drop the deprecated 2a overloads
Status: **implemented, and in the right sub-phase**
Adjudication item 4: the plan calls for this **here**, not later. Phase 2b step 4 reads
"Drop the deprecated overloads from 2a", and Phase 2a step 3's own text scopes them as
"Keep **deprecated** `create(StrategyId)` / `create(SlotStrategyId)` overloads … so no
engine call site moves in 2a". Both Javadoc `@deprecated` tags on the branch named Phase 2b
as the removal point. `create(StrategyId)` and `create(SlotStrategyId)` are gone; no caller
remains (build is green with no deprecation usage).

### Step 5 — AD-15 key validation
Status: **implemented (see Drift — the plan's justification for it is wrong)**
`BotGroupConfigValidationService` injects both factories and runs `validateStrategyKeys`
**first** in `validate`, so a body with two faults reports the strategy fault — matching the
Jackson-era ordering, which is the right reading of AD-15's "at the same HTTP status".
`BadRequestException` → 400 via `RestExceptionHandler`. Null/empty short-circuit before
either registry is consulted, which is load-bearing (`strategyMix` is null on every
pre-BETTING_STRATEGIES group; `slotStrategyId` is null on every non-SLOT group) and is
pinned by two new `BotGroupConfigValidationServiceTest` cases. The 400 body sorts the
catalogue rather than emitting bean-discovery order — a correct application of A4.

### Step 6 — tests
Status: **implemented, with one substituted verification (accepted) — see Drift item 1**

## AD-14 — verified against reality, not against the diff

AD-14's premise is a claim about this repository, and it is **true**:

- `grep -rn "MongoCustomConversions\|ReadingConverter\|WritingConverter"` over
  `bot-app/src/main`, `bot-api/src/main`, `bot-engine/src/main` → **zero hits**. Spring
  Data's default enum handling therefore applied, and the BSON was already `name()`.
- `PersistedStrategyKeyCompatTest` proves it the right way: a **real**
  `MappingMongoConverter` over a real `MongoMappingContext`, not a mocked repository —
  the lesson A2 left behind, applied. It reads a literal pre-migration document
  (captured from the enum-typed tree at `c3fac8a`), asserts the write side emits bare
  strings with no `_class` hint on the mix elements, and demonstrates the general fact
  (`EnumTypedHolder` vs `StringTypedHolder` write identical BSON) rather than asserting it.
- No production code queries or indexes `strategyMix.strategyId` / `slotStrategyId`; the
  only consumers are the mapper, the validator and the behaviour service, all retyped.

**AD-14 needs no amendment.** "No migration script, no dual-read, no defaulting" is
accurate, and the JSON wire shape is unchanged in both directions.

## Drift

### 1. Step 6's "byte-identical fixtures" was substituted — accepted, with a recorded residual gap

The plan asks for "assert the `GET /{id}/health` and `GET /{id}` JSON bodies are
byte-identical to the pre-change fixtures". Dev instead asserts, in
`PersistedStrategyKeyCompatTest.JsonWire`, that
`writeValueAsString(StrategyId.RANDOM) == writeValueAsString("RANDOM") == "\"RANDOM\""`,
plus DTO-level body assertions on `BotGroupDTO` and `BotHealthDTO` and a legacy
request-body deserialisation case.

**Adjudication: a deviation in form that faithfully serves the intent, and is stronger on
the axis that matters.** A recorded fixture pins one value; the token-equivalence proof
covers every value of both enums, which is the actual risk this retype carries. The
collateral coverage a fixture would add — other DTO fields, JSON field order — is coverage
against regressions this diff cannot produce: no field was added, removed or reordered,
only three field *types* changed in place. Both PATCH branches the plan's Implementation
Note demands ("full-replace when supplied, keep when null") are explicitly verified, for
both `strategyMix` and `slotStrategyId`, in `BotGroupMapperTest`.

**Residual gap, for the releaser.** No build-time test exercises the actual `GET /{id}` or
`GET /{id}/health` controller responses through the Spring-configured `ObjectMapper`; the
new tests use a bare `new ObjectMapper()`. The endpoint-level byte-identity claim therefore
rests entirely on **verification P2-4's before/after `diff`**, which is only evidence if
the *pre-deploy* capture is taken. Take it.

Not a send-back: the plan itself puts the endpoint-level byte comparison at release time
(P2-4), and a hand-written build-time "pre-change fixture" would be a weaker copy of it.

### 2. Two stale plan-adjacent comments corrected in code — both corrections are accurate, and no AD needed amending

Adjudication item 2. Verified both against the source, not the commit message:

- **`BettingStrategyFactory`.** At `c3fac8a` the class javadoc said the registry "is
  insertion-ordered so that `registeredKeys()` has a stable base to sort from" and the
  field javadoc said the insertion order "is what AD-21's display ordering sorts *from*".
  Amendment A4 measured that order as Spring's classpath-scan order (alphabetical by class
  file name within package), differing from `StrategyId.values()` in six of nine positions.
  The replacement text keeps `LinkedHashMap` for the reason A4 endorses (determinism within
  a JVM run, for a stable boot log and lookup-failure tail) and states explicitly that no
  consumer may treat it as a display order. **Accurate.** `SlotStrategyFactory` carried no
  equivalent claim and correctly gained none.
- **`StrategyAssignment.apportion`.** The comment said "EnumMap preserves enum-declaration
  order which we later use as the secondary sort key" — sitting above
  `new LinkedHashMap<>()`, and wrong twice over: the container was never an `EnumMap` on
  this branch's history, and the tie-break is `Integer.compare(a, b)` over the index into
  `coalesced.keySet()`, i.e. the caller's mix order post-coalesce. The replacement says
  exactly that. **Accurate**, and it agrees with the surrounding comment at `:95-99` and the
  `@return` javadoc, which already said "matches the user-supplied `strategyMix` order
  (after coalescing)".

**No AD required amending as a result.** A4 already carries the corrected sentence into
AD-21 and into Phase 2a step 3; these are code comments catching up to an amendment that
had already been made. Fixing them in the same commit as the retype is the right call —
the `StrategyAssignment` one would otherwise have been silently carried forward under a new
type.

### 3. AD-15's justification is wrong — **this is the amendment (A5)**

Adjudication item 3, and the one place Dev's "no defect" report does not survive.

AD-15 claims the new check is "strictly *better* coverage than today", on the grounds that
"a group whose strategy bean disappeared in a deploy currently fails at
`BettingStrategyFactory.create` during group start, as an exception on a bot thread".
Measured on the branch, **both halves are false**:

1. **`validate` is not on the group-start path.** It has exactly two production callers:
   `BotGroupService.save` (create only — guarded by `isNewGroup`, `:148`) and
   `BotGroupService.update` (`:319`). `update()` then routes through `save(existing)`, which
   skips the create-only branch. Start/restart/stop/delete/`setActivationMode` and every
   scheduler write bypass it. So the bot-thread failure AD-15 promises to move to the API
   boundary is **unchanged** by this phase; the new check fires only on a write.
2. **`validate` is post-merge over the whole entity on PATCH** (`update`:
   `mapper.updateEntityFromDTO(dto, existing)` then `configValidation.validate(existing)`,
   deliberately, per TIMED_ACTIVATION AD-6). It therefore reads *persisted* state. A group
   holding a key no bean claims now **fails every PATCH**, including one that touches
   neither strategy field. Before 2b those succeeded, because Jackson only inspected the
   request body. That is a new failure mode and it is not derivable from AD-15's text.

**Is it within what AD-15 specifies?** The *implementation* is — AD-15 names the seam and
both call sites, the seam is post-merge, and no implementation using it avoids either
consequence. Dev's own test suite even pins the benign half
(`Patch.unrelatedPatchStillPasses`, "the persisted mix is re-validated and valid"), so the
re-validation was understood. What is **beyond** AD-15 and needed writing down is the
consequence. Hence a plan amendment, not a send-back: there is no correct implementation of
AD-15 that would have made the sentence true.

**Severity is bounded, and the shipped behaviour is the right trade** — the group stays
startable, stoppable and deletable; the fault is repairable by a `strategyMix` PATCH
(full-replace, so the post-merge entity is clean); the 400 names the key and lists the
sorted catalogue. It is recorded because **steps 5–7 are precisely when a registry
shrinks**: a cutover that drops a key version N served makes every group still holding it
read-only through PATCH for the drain window. A5 states that constraint and explicitly
declines to smuggle a group-start guard into Phase 2.

## Out-of-scope changes

**None.** Every file in the diff is named by Phase 2b steps 1–6 or is a test that had to
move with a retyped signature. Specifically checked and clean:

- `GameMessageTypesResolver`, `StrategyController`, `PLUGIN_PLAN.md` — untouched, as
  required (2c/2d work).
- `BotMdcTagsMeterFilter`, `InfoGaugeRefresher`, `PluginClassLoaderMetrics`,
  `prometheus/alerts.yml`, the Grafana dashboard, both `log4j2.properties` twins and the
  JSON template — untouched. Phase 1 is not disturbed.
- No `log.info(` added anywhere; `PerBotInfoLogGuardTest` and `Log4j2TwinConfigTest` pass
  unchanged.

**One observation, not a finding.** `/v3/api-docs` will now describe
`BotGroupDTO.slotStrategyId` as `type: string` rather than as an enum with an
`enum: [FIXED, RANDOM]` list — springdoc infers it, there is no `@Schema` on the field.
That is an inherent and intended consequence of AD-12 (the key set is no longer closed at
compile time), it is not a data-contract change, and the UI's strategy picker is fed by
`GET /api/v1/strategy/`, not by the OpenAPI document. Recorded so a releaser diffing
`/v3/api-docs` does not treat it as a regression. AD-23's subject is behaviour, and this is
not it.

## Item 5 — the never-executing `BotGroupConfigValidationIT`

Dev's finding is **confirmed and correct**, and correctly handled.

`grep -rn "failsafe\|surefire" --include=pom.xml` over the whole repo → **zero hits**. No
plugin is configured, so surefire's default includes apply (`**/Test*.java`,
`**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java`). `BotGroupConfigValidationIT` is the
repo's only `*IT` file and matches none of them: compiled, never run. Its absence from the
surefire XML confirms it.

**Do the plan's verification steps depend on anything that silently never runs? No.**

- Phase 2b step 6's three deliverables all live in executing `*Test` classes:
  `PersistedStrategyKeyCompatTest`, `BotGroupStrategyKeyValidationTest`, and the DTO wire
  assertions. AD-15's "pin the 400 with a controller test" is satisfied by a `@WebMvcTest`
  that runs.
- The A1 test-count gate is unaffected: the IT contributed nothing to the 1904 baseline
  either, so the counts are consistent on both sides.
- No Phase 1 or Phase 2a guard lives in an `*IT`.

Dev updated the IT to keep compiling (it must, or the build breaks) and annotated it with
the finding. That is the right minimum. **Out of 2b's scope to fix, and it should not have
been fixed here** — wiring failsafe or renaming the class would newly execute a Spring slice
that has never run in CI, which is a change of unknown blast radius riding on the highest-risk
sub-phase in this plan.

**Carried forward as a repo-level item**, not a Phase 2b defect: the IT's own subject
(end-to-end config validation across game types) is currently unguarded, and the next person
to add an `*IT` will get the same silent no-op. Worth a one-line failsafe declaration in a
standalone change.

## Amendments to the plan

**A5 — "AD-15's coverage is not 'strictly better'; it moves the guard onto persisted
state"**, appended to `docs/plans/PLUGIN_HOT_RELOAD.md` in the A1–A4 style.

What changed: **one paragraph of AD-15's justification**, replaced with wording that states
(a) `validate` is not on the group-start path so the bot-thread failure is unchanged, and
(b) post-merge PATCH validation means a group holding an unregistered key fails any PATCH
until its mix is replaced — accepted deliberately, but a new failure mode rather than pure
upside. Plus the forward constraint on steps 5–7 (a drain that shrinks the registry makes
mid-drain groups read-only through PATCH unless both versions' keys stay registered), an
explicit statement that adding a start-path guard is a separate decision not taken here,
and the general rule that a post-merge validator validates the document, not the request.

**AD-15's substance is unchanged**: same check, same two call sites, same `BadRequestException`,
same 400. **No code, no shipped behaviour, and no other Architecture Decision changes.**

## Handoff notes for the releaser

Phase 2b is the sub-phase the plan says to **deploy alone**. Two things this review
depends on:

1. **P2-4's pre-deploy capture is mandatory**, not optional — it is the only evidence for
   the endpoint-level byte-identity claim (see Drift item 1). Capture `GET /{id}` for a
   long-running group *before* the deploy.
2. **P2-5 has a second half**: after the 400, re-run the `GET` and confirm the group is
   unchanged. The build-time test asserts `repository.save` is never called on a rejected
   request, but only the staging check proves it end to end.

Also worth one probe while there, given A5: pick a staging group and confirm an unrelated
PATCH (e.g. renaming it) still returns 200 — i.e. that no staging group is carrying a
strategy key the current build does not register.
