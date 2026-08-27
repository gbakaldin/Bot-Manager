# Compliance — PLUGIN_HOT_RELOAD Phase 2d

Branch: `feature/plugin-hot-reload`
Plan reviewed: `docs/plans/PLUGIN_HOT_RELOAD.md` (at `9c61a4c`, i.e. before this review's amendment)
Diff reviewed: `git diff 99e0534..9c61a4c` — the three Phase 2d commits
(`084f583`, `79924dc`, `9c61a4c`). `f6f2228` is the Phase 2a-2c release log and is
reviewed here only for the accuracy issue in the handoff.

## Verdict

**PLAN_AMENDED** — the diff is accepted unchanged. Two entries appended to the plan
(A10, A11); both record things this document should already have said. No code change is
requested. **Safe to deploy**, with one release-time instruction (see "Release-log
accuracy", below) that must be honoured *before* the 2d artifact reaches the box.

## Local gate

```
JAVA_HOME=…/openjdk-21.0.2  mvn -q clean install
```

`BUILD SUCCESS` (exit 0), all five modules built.

| Module | tests | failures | errors | skipped |
|---|---|---|---|---|
| bot-api | 138 | 0 | 0 | 0 |
| bot-strategies | 124 | 0 | 0 | 0 |
| bot-messages | 158 | 0 | 0 | 0 |
| bot-engine | 426 | 0 | 0 | 0 |
| bot-app | 1152 | 0 | 0 | 0 |
| **total** | **1998** | **0** | **0** | **0** |

**Matches Dev's reported 1998 exactly, per module.** ≥ 1866 (A1's baseline). The
delta from the Phase 2a-2c head is **+14**, which is exactly
`StrategyCatalogTest` (6) + `StrategyCatalogResponseContractTest` (8) — no test was lost
and none was quietly disabled.

`mvn -pl bot-api dependency:tree | grep -E 'bot-app|bot-engine|bot-strategies|bot-messages'`
→ **no output**. The contract module still has no reverse edge.

## Phase-by-phase (Phase 2d steps 1-4)

### Step 1 — `StrategyController` lists the registry via `StrategyCatalog`
Status: **implemented**

`StrategyCatalog` (`bot-app/.../domain/bot/strategy/catalog/StrategyCatalog.java`, new,
`@Component`) reads `BettingStrategyFactory.registeredKeys()`, sorts, and maps each key to
a `StrategyInfoDTO`. `StrategyController` takes it by constructor and the three
betting-family branches (`gameType` absent / `BETTING_MINI` / `TAI_XIU`) serve
`catalog.bettingStrategies()`. Path, DTO record, `id` strings unchanged.

- **Built-in copy** — `StrategyInfoDTO.of(builtin)`, i.e. the enum's own
  `displayName`/`description`, byte-for-byte what the endpoint served before.
- **Non-built-in fallback** — `new StrategyInfoDTO(key, key, "")`. Empty string, not
  `null`, matching step 1's "an empty description" and avoiding
  `"description":null` in the picker tooltip.
- **Ordering per AD-21** — `Comparator.comparingInt(builtinRank).thenComparing(naturalOrder())`,
  where `builtinRank` is `StrategyId.ordinal()` for a built-in and `Integer.MAX_VALUE`
  otherwise. Total and stable: built-in ranks are distinct so the tiebreak never engages
  among them, and all non-built-ins share the sentinel rank so they fall to alphabetical.
  The sort consults **nothing** about registry iteration order — A4's requirement, honoured
  literally, and restated in the class javadoc with the "do not simplify this by trusting a
  container's natural order" warning.

### Step 2 — test pinning the response for absent / `BETTING_MINI` / `TAI_XIU` / `SLOT`
Status: **implemented**, and stronger than the step asked for

`StrategyCatalogResponseContractTest` is a `@WebMvcTest` over the **real** registries
(component scan of `com.vingame.bot.domain.bot.strategy`, `@RestController` excluded — a
mocked factory would let every assertion pass against an empty production catalogue).

The baseline is **executable, not transcribed**: `preChangeBody()` evaluates the exact
expression the controller carried before this phase and serialises it with the slice's own
`ObjectMapper`, and three tests assert string equality of the live response against it.
That is the strongest available reading of "byte-identical", and it is better than the
pre-change-fixture comparison the step describes, because a fixture can be regenerated
from the new code by accident.

Also covered: `SLOT` and `CARD_GAME` still `"[]"`; the wire field set is exactly
`id, displayName, description` in that order, per entry; the declaration order is pinned
as **literals** with a separate test tying those literals back to `StrategyId.values()`
(so reordering the enum fails the build and forces the question "did you mean to reorder
the picker?"). `StrategyControllerTest`'s existing assertions are unchanged, which is the
point — it needed only the real registries to keep passing.

`StrategyCatalogTest` covers what the real registry cannot express yet: a plugin-supplied
key, and a registry whose iteration order is the **reverse** of the display order (so a
pass-through implementation cannot accidentally agree).

### Step 3 — delete `PLUGIN_PLAN.md` (AD-22)
Status: **implemented**

`git rm`'d at the repo root, 582 lines, no copy anywhere in the tree
(`find . -name 'PLUGIN_PLAN*'` → nothing outside `.git`). Deleted, not archived, exactly
as AD-22 requires.

`MODULE_DECOUPLING.md`'s warning block is **updated rather than removed** — it now reads
"was stale and superseded … and has since been deleted (`PLUGIN_HOT_RELOAD.md` AD-22; git
history is the archive)" and adds "if you find a copy, do not follow it; the live plan is
`PLUGIN_HOT_RELOAD.md`". That is the right call: the warning outlives the file it warns
about, because a stale checkout or a copy in someone's tree still needs it.

**Nothing else points at it as a live document.** Remaining mentions across the repo are:
`PLUGIN_HOT_RELOAD.md`'s own Findings/AD-22/step-3 (which is the document that deletes it,
describing the pre-change state), the corrected `MODULE_DECOUPLING.md` block, the new
CLAUDE.md line that says it was deleted and why, and historical `docs/reviews/*` entries
from this and three other features. No live pointer survives.

### Step 4 — CLAUDE.md
Status: **implemented**, generously

Both halves landed: the "Spring Plugin Support Framework" backlog line now points at the
plan, states steps 1-2 are done and 3-7 are not started and spike-gated, and records the
`PLUGIN_PLAN.md` deletion with the reason. And a new **"Plugin registries — product
implementations are resolved by `String` key"** section sits under Architecture.

**Factual check of the new section against the shipped code — every claim verified:**

| Claim | Verified |
|---|---|
| Three registries, keys as tabulated | `BettingStrategyFactory` / `SlotStrategyFactory` key on the bare string; `MessageTypesRegistry` on `(GameType, productCode)` |
| `@StrategyImpl("RANDOM")` on a **prototype** bean | `RandomBehaviorStrategy` carries `@Scope("prototype")` + `@StrategyImpl("RANDOM")` |
| `@SlotStrategyImpl("FIXED")` | `FixedBetStrategy`; sibling `RandomBetStrategy` is `"RANDOM"` |
| `@MessageTypesImpl(gameType=…, products={"097","098"})` | `BomGameMessageTypes` verbatim; five siblings consistent, `SlotMessageTypesImpl` is `products = {}` |
| No runtime path switches on the enums or uses them as a map key | every surviving `StrategyId` / `SlotStrategyId` reference in `*/src/main` is `.name()`, a javadoc `{@link}` or an unused DTO factory |
| No `MongoCustomConversions` / `@ReadingConverter` / `@WritingConverter` | zero hits in `src/main` anywhere; the only occurrence is inside `PersistedStrategyKeyCompatTest` |
| `slotStrategyId == null` still means fall back to `FIXED` at bot-build time | `BotGroupBehaviorService:791-793`; entity and DTO fields are plain nullable `String` |
| A duplicate key fails context refresh in **all three** registries | `IllegalStateException` in `BettingStrategyFactory.init`, `SlotStrategyFactory.init`, `MessageTypesRegistry` |
| Operator-facing key lists sorted at render time | `sortedKeys()` in both factories, used by the boot line and the `create()` failure tail |

**Its summary of A5 is accurate.** It carries both halves — `validate` is not on the
group-start path so the bot-thread failure is unchanged, and post-merge PATCH validation
means a group holding an unregistered key fails *any* PATCH — plus the three mitigations
(startable/stoppable/deletable, full-replace clears it, the 400 names the key and lists
the catalogue). The only nuance it drops is A5's note that a stale `slotStrategyId`
cannot be set back to `null` through PATCH but `"FIXED"` is equivalent to the null
fallback. Acceptable compression for a summary; the plan carries the detail.

Its A8 summary is likewise accurate (no version dimension in either key shape; the throw
is right while every key is ours and wrong once a plugin can collide).

**One gap worth a follow-up, not a blocker:** the section's AD-21 bullet describes the
order and the metadata fallback but does not mention the **set** change adjudicated below
— that a `StrategyId` with no registered bean is now absent from the picker rather than
offered. CLAUDE.md is the document future readers will trust, and A10 is now the canonical
statement. One sentence on the next pass through that file.

### Out-of-plan commit — `79924dc`, `BotGroupConfigValidationService` javadoc
Status: **out-of-scope but warranted**

Comment-only. It replaces AD-15's "strictly better coverage" claim, which A5 falsified,
with A5's corrected paragraph — including the group-start caveat and the post-merge PATCH
consequence. This is precisely the follow-up `compliance-fixpass.md` Finding 1 asked for
("fix it on the next pass through this file, ideally by pasting A5's corrected
paragraph"). No behaviour, no test change, no deploy risk. Accepted.

## The three items Dev raised

### 1. Set semantics: "preserved exactly" vs "lists the registry" — **Dev is right; recorded as A10**

This is the real one, and it is a plan defect, not a Dev choice to second-guess. AD-21
asserts both properties and never says which owns the **set**. Pre-2d the endpoint was the
enum; post-2d it is the registry; they differ exactly when a `StrategyId` constant has no
bean.

**Ruled in Dev's favour — the registry is the set.** Reasons, in the amendment:

1. The enum-as-set reading makes the phase deliver nothing at this endpoint: a
   plugin-supplied key is by construction not an enum constant, so it could never be
   offered, and AD-21's own "the endpoint lists the registry" would be decorative.
2. It keeps the picker and AD-15's validator agreeing. The alternative offers a key that
   the next POST answers with 400.
3. A5 makes the alternative actively misleading: a group holding an unregistered key
   already fails *every* PATCH, so presenting that key as a valid choice is worse than
   omitting it.

**One correction to Dev's supporting argument.** Dev credits
`StrategyCatalogParityTest` with failing the build "if the sets diverge for a well-formed
artifact". That test proves only enum ⊆ registry, under a bare
`AnnotationConfigApplicationContext` package scan — and its own javadoc says it cannot see
a bean reachable from that scan yet unreachable from `Starter`'s. The guard that actually
carries the claim is
`ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated`, which asserts exact set
equality in **both directions**, for both registries, under the real `@SpringBootTest`
scan. The conclusion stands and is in fact better supported than Dev argued; the named
test was the weaker one. A10 names the right one, because it is the test a future edit
could weaken without knowing what it was for.

**Cross-reference to `review-2d.md`, which reached the same design conclusion
independently — with one correction that matters for its fix shape.** The Reviewer's
first finding also flags `StrategyCatalogParityTest` as the wrong citation, proposes
`StrategyCatalogResponseContractTest` instead, and closes with *"neither test boots
`Starter`, so the residual signal is the `registered 9 strategies` boot line."* **That
last clause is false.** `ApplicationContextLoadsTest` is a `@SpringBootTest` over the real
`Starter` scan and asserts `registeredKeys()` equal to `StrategyId.values()` as a set,
both directions, for both registries — it is the build-time twin of P2-2 and it is
already shipped (Phase 2a). Whoever actions that finding should cite **it**, not settle
for the boot line, and must not weaken it. The Reviewer's second finding (a one-shot WARN
naming built-ins with no registered bean) is a good answer to A10's "silent at runtime"
consequence and is worth taking at step 5 if not before; it is advisory, not a gate, and
adding a logger to `StrategyCatalog` is out of scope for 2d.

### 2. Step 1's "lists the registri*es*" (plural) vs AD-21 for SLOT — **wording nit; Dev resolved it correctly**

Not a real ambiguity. Three separate places already decide it against the plural: AD-21
("response contract preserved exactly"), AD-23 (Phase 2 changes no behaviour), and
verification P2-3 in so many words — "`?gameType=SLOT` must still return `[]`". Unlike A6
and A7 — where the step-level instruction and the AD were irreconcilable and the plan
offered no tiebreak — here the plan resolves itself, so it does not rise to an amendment
of its own.

Dev's handling is the right one and better than a plan edit would have been: the
constraint is pinned as an executable assertion (`isEqualTo("[]")`) with the reasoning in
a comment on the test, and `StrategyCatalog`'s javadoc explains that the slot registry is
"not omitted because it is hard". That is where the next person to consider wiring slots
will actually be standing. Recorded as one bullet under A10's consequences so the plural
cannot be read as licence later; **step 1's text is unchanged.**

### 3. Declining `AopUtils.getTargetClass` → `AopProxyUtils.ultimateTargetClass` — **agree, decline stands**

Three independent reasons, any one sufficient:

- **It is not in this diff.** Phase 2d touches neither strategy factory. Changing them
  here would be an out-of-scope edit to a module the sub-phase does not otherwise open,
  which is the thing this review exists to catch in the other direction.
- **It was already adjudicated.** `compliance-fixpass.md` Risk 2 / Finding 2 examined the
  same substitution and landed on "unobservable today. Note only." Nothing has changed
  since; re-litigating a settled non-finding at the last sub-phase of a phase is churn.
- **It is a behavioural change with no coverage.** The two differ only for a nested proxy,
  where `ultimateTargetClass` unwraps all the way down and `getTargetClass` unwraps one
  level. Nothing is proxied at all today, so there is no test that could distinguish them
  — meaning the change would ship unverified, to fix a case that cannot occur, in the
  exact code path (`registry.put` of the class later passed to `getBean`) where getting it
  wrong produces a `NoSuchBeanDefinitionException` on a bot thread at group start.

The right home for it is step 4+, where plugin classloaders and possibly AOP arrive
together and the case becomes reachable and testable. It is already noted in
`compliance-fixpass.md`; no further action.

## AD-22 — verified

Deleted at the root, no archived copy, `MODULE_DECOUPLING.md`'s warning updated to say so
and to keep warning, no live pointer left anywhere. See step 3 above. **Satisfied.**

## AD-23 — satisfied, no carve-out needed

2d changes the endpoint's **provenance** (enum → registry) without changing its
**response**. Confirmed on all three axes AD-23 cares about:

- **Values** — `StrategyCatalogResponseContractTest` asserts the live body is string-equal
  to the pre-change expression serialised by the same `ObjectMapper`, for `gameType`
  absent, `BETTING_MINI` and `TAI_XIU`. Not a re-derivation: the baseline is the literal
  expression `StrategyController` used to carry.
- **Order** — pinned twice, once as literals and once by tying those literals to
  `StrategyId.values()`.
- **Set** — equal by construction for any artifact the build will produce
  (`ApplicationContextLoadsTest` asserts exact set equality under the production scan).

Independently reproduced outside the build: serialising the pre-change expression at
`9c61a4c` yields **1822 bytes, md5 `a72c40f56057cda5434b273ea36315ea`** — the same md5
the Phase 2a-2c release log recorded off the running staging box. The wire body is
therefore *provably* unchanged, not merely asserted to be.

**No carve-out is required for this endpoint.** AD-23's subject is observable behaviour,
and nothing observable moved. That said, the guarantee is now **conditional** in a way it
was not before — it holds because the build forces the registry and the enum to agree,
not because the endpoint reads a fixed list. That conditionality is what A10 records, and
it is the thing steps 5-7 inherit. It is the same shape as A4/A9's "for a well-formed
artifact" qualifier on the boot lines, one contract further in.

Two immaterial observations, neither an AD-23 breach:

- The `@Operation` description string changed (it now says "registered strategy key" and
  mentions "built-ins first in their canonical order"). `GET /v3/api-docs` therefore
  returns different bytes. AD-21 freezes the listing's path, DTO, ids and order — not the
  Swagger prose — and the new text is more accurate than the old. Noted, not a finding.
- `StrategyInfoDTO`'s javadoc still says `id` is "the bare enum name". Post-2d it is a
  registry key that *equals* the enum name for a built-in. Stale by one word; also,
  `StrategyInfoDTO.of(SlotStrategyId)` has no caller (and had none before 2d either).
  Both are pre-existing/cosmetic — a comment pass, not a gate.

## Phase 2 as a whole — consistent

Walked 2a → 2b → 2c → fix pass → 2d against every Phase 2 AD:

| AD | State |
|---|---|
| AD-12 string keys, enums demoted to catalogue | honoured; no runtime switch or map key on either enum survives in `src/main` |
| AD-13 string `value()` on both annotations | 11 impls on literals; parity test is the replacement type check |
| AD-14 persisted docs resolve unchanged | `PersistedStrategyKeyCompatTest`; release P2-4 confirmed on six real staging groups |
| AD-15 explicit key validation, same 400 | shipped; its javadoc now carries A5's corrected justification (`79924dc`) |
| AD-16 `ProductCode` stays an enum | untouched; message layer keys on `getCode()` strings |
| AD-17 `MessageTypesRegistry`, three typed lookups | shipped, disjoint return types preserved |
| AD-18 Spring discovery, `bot-messages` gains `spring-context` | shipped |
| AD-19 coverage test replaces switch exhaustiveness | `MessageTypesCoverageTest` |
| AD-20 error text byte-for-byte (all three strings, A6) | `MessageTypesErrorTextTest` |
| AD-21 response contract | **this sub-phase**; set semantics now settled by A10 |
| AD-22 `PLUGIN_PLAN.md` deleted | **this sub-phase**; verified |
| AD-23 no behaviour change | holds across all four sub-phases; the two known deviations are boot-line ordering (A4, A9), both explicitly inside AD-23's subject |

Amendments A4-A9 are all recorded in the plan, A5 and A8 are both cross-referenced from
the code that has to change, and the Open Items list is intact. **Phase 2 is internally
consistent and complete as planned.**

## Release-log accuracy — must be actioned before the 2d deploy

`release-phase2.md` reports P2-3 as PASS, with `diff` producing no output and md5
`a72c40f56057cda5434b273ea36315ea` on both sides. **The artefact is not on file.**
`phase2-capture-before.txt` and `phase2-capture-after.txt` contain only six
`GET /api/v1/bot-group/{id}` + `/health` pairs — **zero** occurrences of `displayName`.
The strategy bodies were captured to `/tmp` on the box and never preserved, so the
report's "raw bodies preserved (referenced by the diffs below)" over-claims for P2-3.

Two things follow:

1. **The claim itself checks out.** The recorded md5 is exactly what the pre-change
   expression serialises to (reproduced independently above, 1822 bytes). The 2a-2c
   verification was really done; only its evidence was not kept.
2. **P2-3's "before" artefact for 2d does not exist on file, and must be re-taken.** It is
   still obtainable — the deployed 2c build enumerates the enum, so the body is exactly
   the constant above — but it must be captured **immediately before the 2d deploy**,
   because the moment 2d lands the pre-change source is gone from the box.

**Releaser instruction:** capture `GET /api/v1/strategy/` and
`GET /api/v1/strategy/?gameType=SLOT` before deploying, **commit the bodies** alongside
the group captures, and check both sides against the constant:

```bash
curl -sf $BOT/api/v1/strategy/ | md5sum          # expect a72c40f56057cda5434b273ea36315ea
curl -sf "$BOT/api/v1/strategy/?gameType=SLOT"   # expect []
```

A mismatch on the *before* capture means the box is not running the build you think it is.
A mismatch on the *after* capture is an AD-21 regression: roll 2d back. This is now
written into the plan as **A11**, so P2-3 no longer depends on an ephemeral file.

## Out-of-scope changes

- `79924dc` — `BotGroupConfigValidationService` javadoc only. Sanctioned follow-up from
  `compliance-fixpass.md` Finding 1. Accepted (see above).
- `f6f2228` — the Phase 2a-2c release log and its two capture artefacts. Documentation of
  a completed deploy, not part of the 2d change set; reviewed only for the accuracy issue
  above.
- Nothing else. The diff touches four production/test files, two docs, and deletes one.
  No production code outside `bot-app`'s strategy listing was modified; both strategy
  factories, `MessageTypesRegistry`, `bot-engine` and `bot-api` are untouched by 2d.

## Amendments to the plan

Appended under **`## Amendment — 2026-08-27 (Phase 2d review)`**, plus one clarifying
sentence added inline to AD-21 pointing at A10. Neither entry changes an Architecture
Decision in substance, any code, or any shipped behaviour.

- **A10 — AD-21 asserts two things that only coincide while the build forces them to;
  registry membership wins.** Settles item 1. Names
  `ApplicationContextLoadsTest.strategyRegistriesAreFullyPopulated` as the guard that
  actually holds the two sets equal (rather than `StrategyCatalogParityTest`, which is
  weaker than Dev's handoff claims). Records the step-5-to-7 consequence: A5's shrinking
  registry now *also* silently drops a key from the picker, at runtime, where no test can
  fire — so the forced-cutover design must either keep the union of both versions' keys
  registered or announce the disappearance another way. Also records that a missing bean
  is now visible only in P2-2's `registered 9 strategies` count. Folds in item 2 as a
  one-bullet wording note; **step 1's text is left alone.**
- **A11 — verification P2-3's "before" body is a constant; pin it instead of relying on a
  capture.** Records the missing artefact as measured, gives the reproduced
  byte-count/md5, and rewrites P2-3 to check both sides against the constant and to
  preserve the body. Prompted by a demonstrated failure of the capture, not a theoretical
  one.

## Deploy

**Safe to deploy.** Behaviour-identical for any artifact this build produces; the one
observable surface it touches is proved byte-identical at build time and now has a
checkable constant on the box. Standing hazards for this repo are unchanged and
unaffected by 2d (`logging/log4j2.properties` must reach the box; bot-manager and the
observability stack share one Compose project, so the smoke test must re-verify
Grafana/Prometheus/Loki). Do the P2-3 pre-capture **first**.
