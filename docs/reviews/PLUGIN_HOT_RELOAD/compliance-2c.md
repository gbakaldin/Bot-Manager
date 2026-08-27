# Compliance — PLUGIN_HOT_RELOAD Phase 2c (message-types registry)

Branch: `feature/plugin-hot-reload-2c` @ `65672cb` (5 commits off `c3fac8a`)
Worktree: `/Users/gleb/IdeaProjects/Bot/.claude/worktrees/agent-a549f50085f102219`
Plan reviewed: `docs/plans/PLUGIN_HOT_RELOAD.md` (as of `c3fac8a`, amendments A1–A4 in force)
Diff reviewed: `git diff c3fac8a..65672cb` — 28 files, +1126 / −244
Governing decisions: AD-16 … AD-20, with AD-23 (behaviour identity) binding throughout.

## Verdict

**PLAN_AMENDED** — the diff is accepted **unchanged**. Two of the three under-specifications
Dev flagged are genuine plan defects and are recorded as **A6** and **A7** in
`docs/plans/PLUGIN_HOT_RELOAD.md`. The third is not a defect: Dev's premise for it is
factually wrong, but the artefact it produced is a harmless in-scope test and is accepted
with its rationale corrected.

Build gate, re-run independently in the worktree with
`JAVA_HOME=…/openjdk-21.0.2 mvn clean install`:

| Module | Tests |
|---|---|
| `bot-api` | 128 |
| `bot-strategies` | 122 |
| `bot-messages` | 145 |
| `bot-engine` | 426 |
| `bot-app` | 1094 |
| **Total** | **1915** — 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS` |

Baseline `1904` at `c3fac8a`; **+11**, matching Dev's handoff figure exactly. Reverse-edge
check green: `mvn -pl bot-api dependency:tree` and the same for `bot-messages` name none of
`bot-app` / `bot-engine` / `bot-strategies`.

## Phase-by-phase (Phase 2c steps 1–6)

### Step 1 — `bot-api`: `@MessageTypesImpl(GameType gameType, String[] products default {})`
Status: **implemented**
`bot-api/.../domain/bot/message/MessageTypesImpl.java`, `@Retention(RUNTIME)`,
`@Target(TYPE)`, members exactly as specified — `GameType gameType()` (mandatory) and
`String[] products() default {}`. Lives in `bot-api`, the non-reloadable contract module, as
AD-17 requires.

### Step 2 — `spring-context` on `bot-messages`; six providers annotated
Status: **implemented**
`bot-messages/pom.xml` gains `org.springframework:spring-context` (version-managed by the
Boot BOM to 6.2.0), with a comment naming AD-18 and asserting the no-reverse-edge property.
Verified independently: the resolved tree for `bot-messages` is `bot-api` + jackson + lombok
+ `spring-context` → `spring-beans`/`spring-core`/`spring-aop`/`spring-expression`, and no
sibling module. The six providers carry `@Component` + `@MessageTypesImpl` with exactly the
products the plan names: Bom `{"097","098"}`, Tip `"116"`, Nohu `"118"`, `SlotMessageTypesImpl`
`products = {}`, `MiniGameTaiXiuMessageTypes` `"116"`, `JackpotTaiXiuMessageTypes` `"114"`.

### Step 3 — `MessageTypesRegistry`, three typed lookups; resolver deleted
Status: **implemented**
`bot-messages/.../MessageTypesRegistry.java` is a `@Component` taking the three provider
lists by constructor, indexing betting-mini and Tai Xiu into separate
`LinkedHashMap<String, …>` tables and resolving one product-neutral slot provider. The three
lookups stay disjoint (`bettingMini(String)` / `slot()` / `taiXiu(String)`) — no `Object
resolve(...)` collapse, which the plan's Implementation Notes explicitly forbid.
`GameMessageTypesResolver.java` is deleted; no `src/main` reference to it survives outside
javadoc prose and the (out-of-scope, 2d-owned) `PLUGIN_PLAN.md`.

Registration is defensive in the house style: a `gameType` that disagrees with the contract
interface the bean was discovered under, a duplicate product claim, a non-empty `products`
on a SLOT provider, or an empty `products` on a non-SLOT provider each fail the context
refresh; a provider bean with no annotation is skipped with a WARN, the same posture as
`BettingStrategyFactory`. One tier-1 INFO line per JVM at construction, shaped like the
strategy factories' "registered N strategies".

Behaviour identity checks I ran against the deleted switch, all clean:
- Same providers for the same products — `{097,098,116,118}` betting-mini,
  `{114,116}` Tai Xiu, one slot — pinned by `MessageTypesRegistryTest.scanRegistersExactlyThePreviousSwitchArms`.
- `P_114` still has **no** betting-mini provider (it did not before), and `116` being claimed
  by both tables is not a collision because the key is `(gameType, product)`.
- **`B52GameMessageTypes` stays unannotated and unregistered**, which is correct: the old
  switch mapped `P_098 → BomGameMessageTypes` and never returned B52. Dev pinned the
  *absence* (`b52IsNotRegistered`) rather than leaving it to be "completed" later. Good call —
  annotating it would have been a wire-format change dressed as a finishing touch.
- Providers become shared singletons (AD-17). I re-verified statelessness rather than
  taking the plan's word: none of the six declares a single instance field.

### Step 4 — `BotFactory` injects the registry; three call sites
Status: **drifted — plan wrong, code right (A7)**
The three call sites become instance calls and `switch (game.getGameType())` stays, as
specified. The deviation is the added private `productKey(Environment)` helper; see **Drift**
below. Nothing else in `BotFactory` moved.

### Step 5 — tests
Status: **implemented, and broader than the step described**
`GameMessageTypesResolverTest` → `MessageTypesRegistryTest`, ported onto a **real
`AnnotationConfigApplicationContext` scan** rather than a hand-built list — the right call,
and the one that would have caught a lost `@Component`. Added: `MessageTypesCoverageTest`
(AD-19), `MessageTypesErrorTextTest` (AD-20), plus
`ApplicationContextLoadsTest.messageTypesRegistryIsFullyPopulated` /
`.botFactoryIsConstructableBySpring`, which pin discovery under `Starter`'s **own** scan.
That last pair is the load-bearing one for this sub-phase: it is the only place in the build
that proves the new `spring-context` dependency is sufficient for cross-jar component
scanning, and the only place a provider reachable from the bot-messages scan but not from
production's would be caught. The four other resolver consumers across `bot-messages`,
`bot-engine` and `bot-app` were ported faithfully — assertions preserved, only the call shape
changed.

### Step 6 — ship
Status: n/a at compliance time. Verification steps P2-6 and P2-7 are the release-time gates
for this sub-phase and both are achievable against this diff; P2-7 explicitly permits
"not exercisable" with the AD-20 build-time test as fallback evidence, and that test now
exists and pins both contract variants.

## Drift

Three deviations, all deliberate, all raised by Dev in handoff. Adjudicated:

**1. AD-20's quoted text vs. the two ranges it cites — PLAN WRONG. Amended as A6.**
AD-20 asked for "the same message as `:52-54` / `:98-100`" and quoted one string. At
`c3fac8a` those ranges held **different** strings: `:52-54` names `GameMessageTypes`,
`:98-100` names `TaiXiuMessageTypes`. Implementing the quoted text for both would make a Tai
Xiu miss report the wrong contract name — in the exact string verification P2-7 greps out of
`docker logs`. Dev parameterised the contract name on the shared `lookup(...)` and pinned
both variants as whole literals; the third operator-facing string,
`"ProductCode cannot be null"`, is preserved and pinned too, though AD-20 never named it.
There is no implementation that satisfies AD-20 as written without regressing one contract,
so this is a specification defect. AD-20 now carries all three strings in a table.

**2. `everyGameTypeIsClassified` — EXTENSION, accepted; its stated justification is wrong.**
Dev's premise was that `BotFactory`'s `switch (game.getGameType())` is enum-exhaustive today
and "would not be caught after 2c". The first half is true; the second is **false**. 2c does
not touch that switch, it is a switch *expression* over an enum with **no `default` arm**
(`BotFactory.java:160-190`, `case CARD_GAME, UP_DOWN -> throw …` is the last arm), so `javac`
still fails the build on a sixth `GameType` exactly as it did before. Nothing was lost and
nothing needed replacing.

What Dev actually added is a 12-line test asserting `{BETTING_MINI, SLOT, TAI_XIU} ∪
{CARD_GAME, UP_DOWN} == GameType.values()`. It costs nothing, changes no production
behaviour, and is consistent with AD-19's "readable inventory" shape, so it is accepted as an
in-scope extension rather than sent back. But it is **redundant with the compiler**, not a
replacement for lost coverage, and its javadoc ("forces the decision rather than defaulting
to silence") overstates what it buys. **Non-blocking follow-up for Dev:** correct that
javadoc so a later phase does not cite it as precedent for "the registry lost `GameType`
exhaustiveness" — it did not.

**3. `productKey(env)` vs. step 4's "Nothing else changes" — PLAN WRONG. Amended as A7.**
`Environment.productCode` (`Environment.java:45`) is a plain nullable Lombok field on a
`@Document`, with no `@NonNull` and no validation. Both old resolver methods opened with an
explicit null guard throwing `IllegalArgumentException("ProductCode cannot be null")`, with
a javadoc `@throws`. The literal transcription — `env.getProductCode().getCode()` — replaces a
handled, documented, product-naming exception with an NPE thrown from `BotFactory`. Step 4
and AD-23 therefore contradict each other for a null product, and step 4 is the more specific
instruction, so a faithful Dev following it ships the regression. The four-line null-forwarding
helper is the behaviour-preserving reading; `MessageTypesErrorTextTest.nullTextIsUnchanged`
pins it on both lookups. Step 4 amended to name it.

**Not amended, recorded only:** step 5's "port `GameMessageTypesResolverTest`" understated
the work — the resolver had four other test-side consumers across three modules including
`bot-engine`. I corrected the sentence while editing step 4 for A7 since it sits in the same
two lines, but it foreclosed nothing and no later phase re-derives from it; it does not get
its own amendment letter.

## Out-of-scope changes

**None.** The 28 touched files are all within Phase 2c's footprint. Specifically confirmed
untouched, as Phase 2d owns them:

- `bot-app/.../strategy/controller/StrategyController.java` — unchanged; still enumerates
  `StrategyId.values()` directly, so `GET /api/v1/strategy/` is byte-identical across this
  deploy and P2-3 passes as written.
- `PLUGIN_PLAN.md` — unchanged (AD-22 deletion is 2d's).
- `CLAUDE.md`, `deploy.sh`, `prometheus/`, `grafana/`, `logging/` — all unchanged.

Two `bot-api` files (`SlotMessageTypes`, `TaiXiuMessageTypes`) have one-line **javadoc**
edits swapping the resolver's name for the registry's. In scope: they are the same rename.

AD-16 confirmed honoured: `ProductCode` is untouched, still an enum, still carrying `appId` /
`usernameMaxLength` / `vipTalkRoomId`; only the message layer keys on the
`ProductCode.getCode()` string. Nothing de-enum-ed it, and `BotFactory` is the sole place the
enum is converted to the key.

## Amendments to the plan

Both land in a new `## Amendment — 2026-08-26 (Phase 2c review)` section at the bottom of
`docs/plans/PLUGIN_HOT_RELOAD.md`, with in-place markers on the two passages they correct,
in the A1–A5 style.

- **A6 — AD-20 cites two ranges holding different strings, and quotes only one.** AD-20 now
  tables all three preserved strings (both `not yet implemented` variants plus
  `"ProductCode cannot be null"`) and states that the contract name is a parameter of the
  lookup, not a constant. Rule left behind: when a decision says "byte-for-byte", quote every
  byte it governs.
- **A7 — Phase 2c step 4's "Nothing else changes" turns a documented exception into an NPE.**
  Step 4 now names the null-safe key extraction, records that `BotFactory`'s switch keeps its
  own compile-time exhaustiveness after 2c, and step 5 names the four other test-side
  consumers. Rule left behind: a change of parameter *type* moves null handling from callee to
  caller unless someone writes down where it went.

No Architecture Decision changes in substance; no code, no shipped behaviour, and no
verification threshold moved.

## Note for the next phase

The count of plan defects is now six (A1–A7, A2/A3 shipped together), against zero
implementation defects found by compliance in four sub-phases. The pattern is stable and
worth naming: every one of the six was a **claim about the repository or an external system**
embedded in a decision — a metric name, an alert's behaviour at boot, a test count, a map's
iteration order, the contents of two line ranges, the nullability of a field. None was a
design error. Phase 2d's AD-21 rests on exactly that kind of claim —
`StrategyId.values()` order being "a de-facto UI contract" — and A4 has already corrected
one half of it. Whoever reviews 2d should capture the live `GET /api/v1/strategy/` body
before the deploy rather than trusting the enum's declaration order to be what the UI
currently receives.
