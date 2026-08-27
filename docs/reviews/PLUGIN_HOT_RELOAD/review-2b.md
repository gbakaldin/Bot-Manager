# Code Review — PLUGIN_HOT_RELOAD Phase 2b

Branch: `feature/plugin-hot-reload`
Reviewed diff: `git diff c3fac8a..HEAD` (commits `d06403d`, `63c31c8`)

Scope: code quality only. Plan compliance is Architect-2's, test coverage is QA's,
deploy mechanics are the Releaser's. The concurrent 2c worktree under
`.claude/worktrees/` was not read.

Build: `mvn -o test` over the whole reactor — **BUILD SUCCESS**, 0 failures,
0 errors (JDK 21.0.2).

## Verdict

PASS

No `bug` and no `security` finding. Four `smell`s and two `style`s, all advisory.

The persisted-identity change itself is correct. I checked the three things that
could actually lose money here and all three hold:

- **Read compatibility.** No `MongoCustomConversions` / `@ReadingConverter` /
  `@WritingConverter` exists in `bot-app` (grep: zero hits), so Spring Data's
  default enum handling was already writing `name()`. `PersistedStrategyKeyCompatTest`
  proves it the right way — a real `MappingMongoConverter` over a **literal**
  pre-migration BSON document, plus a direct enum-holder vs string-holder
  equivalence demonstration. That is a genuinely foreign document, not a
  round-trip of the code under test.
- **Round-trip, which the plan names as the real hazard.** `BotGroupMapper`'s two
  PATCH branches (`:157-162` full-replace, `:168` keep-on-null) are unchanged and
  both are pinned in `BotGroupMapperTest$StrategyMixTests` /
  `$SlotStrategyIdTests`. Nothing normalises, upper-cases or trims the key on any
  leg. `mixElementsCarryNoTypeHint` additionally pins that no `_class` hint
  appears on the sub-documents, which is the silent shape change that would only
  bite on a rollback.
- **Null stays null.** `BotGroup.slotStrategyId == null` is not defaulted at the
  entity, DTO, mapper or validation layer; the fallback stays at
  `SlotMachineBot.java:156-158`. Pinned by `absentSlotStrategyIdStaysNull` and
  `nullSlotStrategyIdIsNotWrittenAsBlank`.

### Focus area 1 — the three deliberate consequences

**(a) The new PATCH failure mode. Dev's "strictly better" claim survives, with one
correction to the framing.** I traced every operator action on a group whose
strategy bean has vanished:

| Action | Path | Validates? |
|---|---|---|
| `POST /{id}/stop` | `runWithManualOverride` → `behaviorService.stop` | **no** |
| `POST /{id}/start` | ditto → `behaviorService.start` | **no** |
| `POST /{id}/restart`, `/schedule-restart` | `behaviorService.*` | **no** |
| `DELETE /{id}` | `BotGroupService.delete:334` | **no** |
| manual activation-mode flip | `setActivationMode:308` (explicitly documented as not re-validating) | **no** |
| system writes (DEAD/STOPPED marking) | `BotGroupService.save` with a non-null id — `configValidation.validate` is inside the `if (isNewGroup)` branch at `:140-148` only | **no** |
| `PATCH /{id}` | `BotGroupService.update:319` | **yes** |

So the scenario in the brief — "an operator trying to *stop* a group that has
become unvalidatable" — does not occur. Stop, start, restart and delete are all
reachable. **Only PATCH is blocked, and PATCH is also the in-band repair**:
`strategyMix` is full-replace, so `PATCH {"strategyMix":[{"strategyId":"RANDOM","weight":1.0}]}`
clears the condition in one call, and the 400 body names the bad key *and* lists
the registered ones (sorted, so the list is stable across machines — a good
touch). `unrelatedPatchStillPasses` pins that a rename is fine when the persisted
mix is valid, and `knownKeyAccepted` pins the repair.

The one genuinely un-repairable corner is narrow and harmless: a persisted
`slotStrategyId` that is invalid cannot be PATCHed back to `null` (null means
"keep existing"), only to another valid key. It is also inert —
`BotGroupBehaviorService.java:783-796` ignores `group.slotStrategyId` entirely and
hardcodes `FIXED` for SLOT bots — so the field is validated but never read. That
asymmetry predates 2b (Jackson rejected the same values) and I would not change it
now; noting it so nobody is surprised.

**(b) The OpenAPI surface change is a non-issue here.** Nothing in this repo
generates from the schema — no `openapi-generator` / `swagger-codegen` plugin, no
`*IT`/test asserting `/v3/api-docs`, and no frontend source in the tree. And the
strategy picker is not schema-driven by design:
`StrategyController.list`'s own `@Operation` description says *"The frontend uses
this to populate the strategy picker on the bot-group form"*
(`StrategyController.java:27-28`). `GET /api/v1/strategy/` still returns the full
`StrategyId` catalogue with `displayName`/`description` and is untouched by 2b.
Losing the Swagger dropdown on `slotStrategyId` / `strategyMix[].strategyId`
costs a human filling the form by hand in Swagger UI, nothing else. Phase 2d
folding the registries into that endpoint is the right place to close it.

**(c) Validation ordering is right, but the comment justifying it overstates.**
See `[smell] The "which error wins" comment is only true within validate()`.

### Focus area 2 — the deleted overloads

Clean. `grep` over all five modules for `create(StrategyId` / `create(SlotStrategyId`
returns zero hits outside javadoc prose, and the whole reactor test-compiles, so
there is no dangling caller and no accidental re-binding (`create(null)` is now
unambiguous, which is why the old `(StrategyId) null` cast disappears).

The two engine call sites moved for real rather than being papered over:
`BettingMiniGameBot.java:180` and `SlotMachineBot.java:156-158` both changed the
*local variable's type* to `String` and spell the default `StrategyId.RANDOM.name()`
/ `SlotStrategyId.FIXED.name()` — reading the key off the catalogue enum rather
than a loose `"RANDOM"` literal. That is the right choice: a rename of the constant
still breaks a compile *and* `StrategyCatalogParityTest`, instead of silently
producing an unregistered key. Same discipline applied at
`BotGroupBehaviorService.java:771, 793, 846`.

The 2a tests that pinned the overloads were folded, not deleted:
`deprecatedEnumOverloadRejectsNull` became `nullKeyRejectedWithIllegalArgumentException`
and still asserts AD-23's "null is an `IllegalArgumentException`, not an NPE"
property, now against `create(String)` alone. `deprecatedEnumOverloadMatchesStringKey`
was dropped, correctly — there is no longer a second overload for it to compare.

### Focus area 3 — loss of enum type safety, and paths that skip validation

The AD-15 check plus `StrategyCatalogParityTest` is proportionate. Note what
actually replaced the compiler here: the compiler never validated the *persisted*
value anyway — it validated the Java type, and Jackson validated the wire. What is
genuinely gone is Jackson's wire check, and `BotGroupConfigValidationService`
restores it at the same status on a strictly larger surface (create **and** PATCH,
where Jackson only ever saw the body). `BotGroupStrategyKeyValidationTest` runs
the check against the **real** registries via a component scan rather than a
stubbed key set, which is what makes it worth anything.

Paths that reach the registry without passing validation, exhaustively:

1. **Group created before 2b** — values are enum constant names by construction,
   all of which are registered keys (`catalogueIsReal` asserts the two sets are
   equal). Safe.
2. **Direct Mongo write / a bean removed by a future deploy** — unvalidated.
   `BettingStrategyFactory.create` throws `IllegalArgumentException` naming the
   key and listing what is present; `createBotsInParallel:633-642` catches it
   per bot, logs ERROR, increments `bot_creation_failures_total{reason="validation"}`
   (`classifyCreationFailure:668-671` maps `IllegalArgumentException` there), and
   the group ends up DEAD with `0/N bots`. Identical to pre-2b behaviour for a
   vanished bean, and loud.
3. **`ActivationScheduler` and the `@PostConstruct` auto-start**
   (`BotGroupBehaviorService.onStartup:237`) — both go to `start()` directly, so
   they land in case 2. Unchanged by this diff.

**Bootstrap order is fine.** `BotGroupConfigValidationService` now depends on both
factories, whose `@PostConstruct init()` therefore completes before injection. No
cycle is introduced (the factories depend only on `ApplicationContext` and
`List<BettingStrategy>`), and `ApplicationContextLoadsTest` boots the real context
and passes.

One thing worth recording as an *improvement* nobody claimed: pre-2b a corrupt
persisted value would fail Spring Data's enum conversion **at read time**, which
makes the whole document unloadable — a 500 on `GET /{id}` and, worse, a poisoned
`POST /{envId}/filter` for the entire environment. Post-2b it reads back fine and
fails at one controlled point. That is a real robustness gain from the retype.

### Focus area 4 — null and legacy documents

Verified end to end for a document with no `strategyMix` and no `slotStrategyId`:

- **Loads** — `absentSlotStrategyIdStaysNull` reads `{"_id":"grp-2","name":"n"}`
  through the real converter; both fields come back `null`.
- **Validates** — the `mix != null && !mix.isEmpty()` / `slotStrategyId != null`
  short-circuits run before either registry is touched;
  `absentStrategyFieldsSkipTheRegistries` asserts `registeredKeys()` is never
  called, using **unstubbed** mocks so the test would NPE if the guard were
  dropped. That is the right instrument for this claim.
- **Starts** — `effectiveStrategyMix` (`BotGroupBehaviorService.java:844-848`)
  returns `[(StrategyId.RANDOM.name(), 1.0)]`, which is a registered key, so
  `assign` → `create` resolves.

The empty-list case is also handled and correctly *not* rejected in the validator
(`emptyStrategyMixSkipsTheRegistry`) — the mapper already owns "PATCH must not
supply an empty mix", and an empty list on create means "fall back to the
default".

## Findings

### [smell] The "which error wins" comment is only true within `validate()`
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/validation/BotGroupConfigValidationService.java:62-66`

> `// Keeping it first preserves which error a body with two faults reports`

It does not, on the create path. `BotGroupController.save:112` is annotated
`@Validated(OnCreate.class)`, and `BotGroupDTO` carries `@NotBlank` on
`environmentId` / `namePrefix` / `password` / `gameId` and `@Positive` on
`botCount` (`BotGroupDTO.java:35-47`). Bean validation runs **after** Jackson but
**before** the controller body, whereas the enum rejection this replaces happened
**during** deserialization. So a create body with a blank `namePrefix` *and*
`strategyId:"NONSENSE"` used to report the enum fault and now reports
`namePrefix must not be blank`.

Nothing breaks — both are 400, and no client parses which of two faults comes
first — so this is comment accuracy, not behaviour. But the comment is the
justification for the statement's position in the method, and it is stated more
strongly than the code can deliver. Fix shape: reword to "first *within*
`validate()`, so a group with both a bad strategy key and a bad activation window
reports the strategy key — matching the relative order Jackson used to impose",
and drop the claim about the request body as a whole. The PATCH path (no
`@Validated`) does behave as the comment says.

### [smell] `BotGroupMapper`'s slotStrategyId comment still calls the field an enum
`bot-app/src/main/java/com/vingame/bot/domain/botgroup/mapper/BotGroupMapper.java:163-167`

> `// No empty-value case to guard — it is a single nullable enum, and null is a valid "fall back to FIXED" state.`

After this diff it is a single nullable **`String`**, and "no empty-value case to
guard" is now literally false: `""` is representable and reaches
`entity.setSlotStrategyId("")` through the `Optional.ofNullable(...)` at `:168`,
because `""` is non-null. It is caught one frame later by the AD-15 check (`""`
is not a registered key → 400 naming `''`), so there is no defect — but the
comment now asserts an invariant the type no longer provides, and the next reader
of this line is exactly the person who might drop that check.

Every other javadoc touched by the diff was updated carefully; this one was
missed because `BotGroupMapper` is otherwise untouched. Fix shape: one-line edit
saying the empty-string case exists and is owned by
`BotGroupConfigValidationService`.

### [smell] `StrategyAssignment`'s javadoc now claims coverage the tests do not have
`bot-api/src/main/java/com/vingame/bot/domain/bot/strategy/StrategyAssignment.java:56-59`

The diff rewrites `ApportionmentResult`'s javadoc from

> `so StrategyAssignmentTest can drive the apportionment math directly with mixes of distinct enum entries (the full assign call requires a StrategyId per strategy which limits distinct-bucket testing in v1 with only one enum value)`

to

> `so StrategyAssignmentTest can drive the apportionment math directly with mixes of distinct entries.`

Deleting the excuse is right — 2b makes any two string literals a second bucket,
and the excuse was stale even before that (`StrategyId` has had nine constants
since BETTING_STRATEGIES). But the tests were not taken up on it. Every call in
`StrategyAssignmentTest` and `StrategyAssignmentApportionmentMathTest` still feeds
a single key (`StrategyId.RANDOM.name()`, mechanically `.name()`-suffixed), so
`ids` is always a one-element list and three production behaviours remain
unexercised in the shape the doc now advertises: `assign`'s multi-bucket slicing
loop (`:179-185`), largest-remainder leftover distribution *across distinct*
buckets (`:114-124`), and the insertion-order tie-break the diff's own new comment
at `:83-84` documents. `StrategyAssignmentApportionmentMathTest` still carries a
`@DisplayName` reading *"multi-bucket testing requires a second StrategyId"*,
which after this diff is simply untrue.

No behaviour changed and nothing is at risk today — flagging it because the diff
edited the comment to describe coverage instead of adding the coverage that had
just become free, and because the false `@DisplayName` will mislead the next
person who looks for that gap. **Flagged for QA**, whose call the coverage
decision is.

### [smell] The `!= null` strategy-key guards do not cover the blank string
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:180`,
`bot-engine/src/main/java/com/vingame/bot/domain/bot/core/SlotMachineBot.java:156-158`

`strategyId != null ? strategyId : StrategyId.RANDOM.name()` was total when the
type was an enum. With a `String` the empty and whitespace values are newly
representable, pass the guard, and reach `create("")`, whose message renders as
`No BettingStrategy registered for  — strategies present: [...]` — a lookup error
with an invisible key.

Unreachable through the API (the AD-15 check rejects `""` as an unknown key with
a clear `Unknown strategyId ''` 400), so this is only the direct-Mongo-write /
hand-edited-document path, and failing loudly there is the correct behaviour per
BETTING_STRATEGIES AD-12. I would **not** add an `isBlank()` fallback in the bot —
that would silently convert a corrupt config into a RANDOM bot. If anything is
worth doing it is one line in `validateStrategyKeys` treating blank explicitly
(`key == null || key.isBlank() || !registered.contains(key)`) so the 400 message
reads better; the runtime behaviour is fine as is. Advisory.

### [style] Fully-qualified names inline where the surrounding files import
`bot-app/src/test/java/com/vingame/bot/domain/botgroup/controller/BotGroupConfigValidationIT.java:100-107`,
`bot-app/src/test/java/com/vingame/bot/domain/botgroup/controller/BotGroupStrategyKeyValidationTest.java:181, 194, 197, 212`,
`bot-app/src/test/java/com/vingame/bot/domain/botgroup/validation/BotGroupConfigValidationServiceTest.java:163`

`BotGroupConfigValidationIT`'s new `RealStrategyRegistries` spells four annotations
fully qualified (`@org.springframework.boot.test.context.TestConfiguration`,
`@org.springframework.context.annotation.ComponentScan`, …) inside a file with a
normal import block; `BotGroupStrategyKeyValidationTest` — which contains the
identical class with proper imports — uses
`com.vingame.bot.domain.bot.strategy.WeightedStrategy`, `java.util.Arrays` and
`org.hamcrest.Matchers` inline. Same pattern with `java.util.List.of()` in
`BotGroupConfigValidationServiceTest`. Cosmetic, but the two `RealStrategyRegistries`
copies read as different code when they are the same class.

### [style] Import order
`bot-api/src/test/java/com/vingame/bot/domain/bot/strategy/StrategyAssignmentTest.java:9-11`

`java.util.LinkedHashMap` replaced `java.util.EnumMap` in place, so it now sits
before `java.util.HashSet`. One-line reorder.

## Notes

**Good patterns worth naming.**

- `PersistedStrategyKeyCompatTest` is the right instrument for the risk, and its
  javadoc records the *provenance* of the BSON literals (captured from the
  enum-typed tree at `c3fac8a`). That is what makes the literal trustworthy rather
  than a guess, and it is reusable evidence for 2c and step 5.
- The `EnumAndStringAreTheSameBson` nested class demonstrates AD-14's underlying
  claim directly instead of asserting the conclusion — the difference between a
  test that would survive the assumption being wrong and one that would not.
- `BotGroupStrategyKeyValidationTest` uses the real component-scanned registries.
  A mocked `registeredKeys()` would have let the whole feature pass with an empty
  catalogue; the javadoc says so explicitly.
- Dev's honest note that **`BotGroupConfigValidationIT` never executes** (no
  failsafe plugin; surefire's default includes are `*Test`/`Test*`/`*Tests`/
  `*TestCase`) and the decision to put the guard in a class that *does* run, while
  still keeping the `IT` correct. That is the right call and the right disclosure.
  Worth someone's backlog entry to wire failsafe or rename it — out of scope here.
- `sorted()` on the 400's key list. Small, but it means two boxes and two
  packagings produce the same error body, which matters the day an operator pastes
  one into a ticket.

**Question for the author.** `validateStrategyKeys` runs on every PATCH including
system-adjacent ones. `BotGroupService.update` is the only caller that can be hit
concurrently with `BettingStrategyFactory.init()`? No — `init()` is
`@PostConstruct` and the web layer is not serving yet, so no. Nothing to do;
recording that I checked it because the plugin end-state (step 7's reload
endpoint) *will* mutate `registry` while requests are in flight, and
`LinkedHashMap` is not safe for that. Not a 2b problem; a step-7 one, and the
`registry` field's javadoc would be the place to say so when it arrives.

**Out-of-diff security observation, deliberately not a finding.**
`BotGroupService.java:161-162` logs
`"Registering {} users with prefix '{}' and password '{}'"` at **INFO** — the bot
group's plaintext password, on track 1, which is the only track promtail ships to
Loki, with a 14 d / 720 h retention. This is untouched by 2b and does not affect
this verdict, but the surrounding code was read closely for this review and the
house rule against logging credential material (`CLAUDE.md`, Logging Guidelines;
cf. the ws-parser agency-token containment work in AD-30) applies to it. Suggest
an independent ticket.

**Logging.** The diff adds no log statements. The two touched `log.debug` lines in
`BotGroupBehaviorService` (`:781`, `:795`) keep their SLF4J `{}` form, their level
and their MDC, and `PerBotInfoLogGuardTest` passes. No token or credential is
logged by anything in this diff.

**Safe to deploy to staging alongside 2a?** Yes. 2a and 2b are one continuous
change to the same seam and 2b is the half that makes 2a's string registries
actually reachable — shipping 2a without it leaves a deprecated overload in the
tree, which the 2a review already flagged as the step-5 hazard. They belong in the
same deploy. The plan's "deploy 2b alone" instruction is about **not** bundling it
with 2c, and I agree with that for a different reason than the plan gives: 2b is
the only sub-phase whose blast radius includes documents that already exist, so it
wants a window where a `GET /api/v1/bot-group/{id}` and one group start are the
only variables. 2c was not reviewed here and none of its declared surface
(`bot-messages`, `BotFactory`'s three resolver call sites) overlaps the files in
this diff, but that is an observation about file paths, not a merge clearance.

Post-deploy, the two cheapest confirmations are the plan's own P2-4 (round-trip an
incumbent group: `GET`, `PATCH` one unrelated field, `GET` again, and diff the
`strategyMix`) and P2-5 (`{"strategyId":"NONSENSE"}` on both `POST` and `PATCH`
still 400). If `bot_creation_failures_total{reason="validation"}` moves at all
after this deploy, a persisted key stopped resolving and the failure is in case 2
above — read the ERROR at `BotGroupBehaviorService:637`, it names the key and
lists the registered set.
