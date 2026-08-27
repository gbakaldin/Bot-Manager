# Compliance — PLUGIN_HOT_RELOAD (Phase 2 review fix pass)

Branch: `feature/plugin-hot-reload`
Plan reviewed: `docs/plans/PLUGIN_HOT_RELOAD.md` (at `7d8a08d`, incl. Amendments A1–A7)
Diff reviewed: `git diff 2e7cd1e..7d8a08d` — six commits, 17 files, +837/−141
Build re-run here: `JAVA_HOME=…/openjdk-21.0.2 mvn clean install` → **BUILD SUCCESS**,
five modules, **1984 tests, 0 failures, 0 errors** (api 138, strategies 124, messages 158,
engine 426, app 1138). Reverse-edge gate (`mvn -pl bot-api dependency:tree`) → no output.

## Verdict

**PLAN_AMENDED** — the diff is accepted unchanged; the plan gains A8 and A9 for two things
it should already have said and did not.

**Safe to deploy 2a+2b+2c to Bot-1 staging: yes.** No finding below blocks it. The one
behavioural change the fix pass introduces that did not exist at `cf5f164` (a missing
`@MessageTypesImpl` now fails context refresh) is proved unreachable against the shipped
bean set by four independent checks, listed under Risk 1.

## Phase-by-phase

This is not a plan phase; it is a fix pass over 2a+2b+2c. Classified per commit.

### `4b605d7` — registry wiring as one value, fail loud on a missing key (review-2c F1, F2)
Status: **implemented, drift-free**. Both are review findings, not plan steps; neither
touches an Architecture Decision. AD-17's three typed lookups, AD-20's three strings and
AD-19's coverage inventory are byte-identical to `cf5f164`.

### `b074432` — sorted key lists, `@StrategyImpl` off the target class (review-2a, F3)
Status: **implemented**, with one **accepted drift**: the two strategy factories' boot
lines and `create()` failure tails now render sorted rather than in discovery order. A4
documented the discovery-order sequence in verification P2-2; that sequence is now stale.
P2-2 passes as written (it asks for counts and set, not sequence). Recorded as **A9**.

### `899bb7b` — multi-bucket `StrategyAssignment` coverage (review-2b)
Status: **implemented**. Test-only plus one javadoc. Dev added the coverage rather than
walking back the javadoc — the stronger of the two options review-2b left open, and it is
what makes the doc true rather than merely quiet. The false `@DisplayName`
("multi-bucket testing requires a second StrategyId") is gone; its assertion is unchanged.

### `1001517` — B52/098 javadoc (review-2c F5)
Status: **implemented**. Documentation only. `B52GameMessageTypes` is still not a bean and
still claims no product — which is the behaviour-preserving choice AD-23 requires. The
javadoc now states the open 098 question instead of calling the class vestigial.

### `4715d20` — two stale comments, de-qualified inline names (review-2b)
Status: **implemented**, with one **miss** (below, Finding 1). Comments and tests only.

### `7d8a08d` — sorted registry startup line, F4 recorded in code
Status: **implemented**. F4's two step-5 constraints are recorded in the code comment Dev
could write; the plan entry Dev could not write is now **A8**.

### Out-of-scope changes
- `docs/plans/BOT_GROUP_CONFIG_VALIDATION.md` (+10/−4) — a different feature's plan doc.
  It described the strategy factories as `EnumMap`-keyed exposing `registeredIds()`, which
  Phase 2a/2b falsified. The edit is a marked historical correction ("Was `EnumMap` … until
  PLUGIN_HOT_RELOAD Phase 2a/2b"), not a redesign, and it corrects a claim this very
  feature broke. Accepted; no deploy surface.
- Nothing else. The six commits use explicit pathspecs; none of the pre-existing
  working-tree dirt (`TaiXiuMessages/*.js`, `deploy.sh`, `AVIATOR_BOT.md`,
  `VIPTALK_ALERTING_V2/release.md`) was committed.

---

## The four risk items, adjudicated

### Risk 1 — the new hard failure on a missing `@MessageTypesImpl`. **Confirmed safe.**

**First, a correction to the brief: this was applied to *one* registry, not three.** Only
`MessageTypesRegistry` throws. `BettingStrategyFactory` and `SlotStrategyFactory` keep
WARN-and-skip, deliberately and with the reason in a comment ("this class has no such
siblings to be inconsistent with"). What was applied to all three is the *annotation
lookup* change (Risk 2/3). The blast radius is therefore smaller than briefed.

`B52GameMessageTypes` verified independently: the file contains **no
`org.springframework` import at all** — no `@Component`, no `@MessageTypesImpl`, and no
meta-annotated stereotype. It cannot be a bean, so it cannot reach the throw.

**No other implementation anywhere can trip it**, on four independent grounds:

1. **Enumeration.** Repo-wide, main sources contain exactly seven implementations of the
   three provider contracts: `BomGameMessageTypes` (`{"097","098"}`), `TipGameMessageTypes`
   (`"116"`), `NohuGameMessageTypes` (`"118"`), `MiniGameTaiXiuMessageTypes` (`"116"`),
   `JackpotTaiXiuMessageTypes` (`"114"`), `SlotMessageTypesImpl` (`products = {}`) — all six
   carrying `@Component` **and** `@MessageTypesImpl` — plus `B52GameMessageTypes`, which
   carries neither.
2. **No second construction path.** There are eight `@Bean` methods in the whole repo
   (`OpenApiConfig`, `NettyEventLoopConfig`, `CorsConfig`, `ObservabilityConfig` ×2,
   `ScopedDebugConfig`); none produces a provider. No `@Import`, no registrar, no XML.
3. **Scan boundary.** `@SpringBootApplication` on `com.vingame.bot.Starter` with no
   `@ComponentScan` override, so the scan base is `com.vingame.bot` — occupied only by the
   five bot modules. The ws-parser jar lives under `com.vingame.websocketparser`.
4. **A real boot proves it.** `ApplicationContextLoadsTest` is a full `@SpringBootTest`
   that refreshes the production context and asserts
   `messageTypesRegistryIsFullyPopulated`. A refresh failure fails the test. It is green in
   the build I ran. This is the check that would catch a provider I failed to enumerate.

The throw is also **backstopped, not primary**: `MessageTypesCoverageTest` (AD-19) turns
red at build time for the affected product, mutation-verified in the test's own javadoc by
removing `@Component` from `TipGameMessageTypes`. The throw only fires on a deploy that
skipped the build.

**Judgement:** the failure mode it replaces — clean startup, then
`"GameMessageTypes not yet implemented for product code: 116"` for a brand live for months,
with the one explanatory WARN scrolled past — is materially worse to diagnose than a
refusal to start that names the class. Consistent with the three sibling hard branches that
read the same annotation. Within AD-17/AD-23 (no shipped behaviour changes, because the
condition is unreachable).

### Risk 2 — `AnnotationUtils.findAnnotation` widening the hierarchy. **Dev's reasoning holds, and is stronger than Dev claimed for the strategies.**

Dev's argument — a subclass registered beside its superclass is already a fatal duplicate,
so the widening cannot silently shadow a brand — is **sound as far as it goes**, and it is
pinned both ways (`subclassInheritsTheAnnotation`,
`subclassAlongsideItsSuperclassIsADuplicate`). The `UnannotatedBettingMini` fixture was
correctly re-based to `implements GameMessageTypes` rather than
`extends BomGameMessageTypes`; had it stayed a subclass it would have inherited 097/098 and
silently stopped testing its own name. That is the fixture break Dev reports, and Dev read
it correctly: it was the *test* that was wrong about what it tested, not the widening.

Two things Dev's argument does not cover, neither of which bites today:

- The duplicate-collision argument assumes the superclass is **also** a bean. A subclass of
  a provider whose superclass had lost `@Component` would inherit the claim with no
  duplicate. That requires someone to deliberately unregister a shipped provider, and the
  inherited claim would be the sane reading anyway. Not a hazard.
- For the **strategies** the question does not arise at all, which is a stronger result
  than the one Dev argued: every concrete strategy is a `final` class carrying its own
  `@StrategyImpl` (9 betting, 2 slot), and every base class in the chain
  (`MartingaleStrategySupport`, `ClassicMartingaleStrategy`, `DAlembertStrategy`,
  `FibonacciStrategy`, `ParoliStrategy`) is `abstract` and unannotated. `final` means no
  subclass can exist to inherit a key, so the widened lookup is provably a no-op there.

**Judgement: keep the widening.** Zero behaviour change against the shipped bean set, and
it is the reading the failure branch assumes. No AD governs how the annotation is read.

*One minor deviation, non-blocking:* review-2c F3 recommended
`AopProxyUtils.ultimateTargetClass`, Dev used `AopUtils.getTargetClass`. They differ only
for a **nested** proxy (proxy of a proxy), where `getTargetClass` unwraps one level.
Nothing is proxied at all today, so the difference is unobservable; worth knowing if AOP
ever arrives.

### Risk 3 — registering the target class, not just reading the annotation off it. **Holds.**

Dev's claim is that fixing only the lookup would newly admit a proxied bean and then fail
it at `getBean`. Checked, and it is right, for both factories:

- Before: `registry.put(id, bean.getClass())`. For a proxied bean that line is never
  reached — `getClass().getAnnotation(...)` returns `null` on `$ProxyN`, so the bean is
  WARN-skipped.
- Lookup-only fix: the annotation is now found, and `$ProxyN.class` (or the CGLIB
  subclass) goes into the registry. `create()` then calls `context.getBean(clazz)` with a
  class that is not the resolved type of any bean definition — `NoSuchBeanDefinitionException`
  on a bot thread at group start, where the WARN-skip at least produced a lookup failure
  naming the key.
- Shipped fix: `AopUtils.getTargetClass(bean).asSubclass(...)` is used for **both** the
  annotation and the registered value, so `getBean(targetClass)` resolves the prototype
  exactly as today.

Half the fix would indeed have been worse than none. Correctly coupled.

### Risk 4 — the `Tables` record. **Correct today; the step-5 edit really is one word.**

- **Every accessor reads the field exactly once into a local.** `bettingMini(...)` and
  `taiXiu(...)` pass `tables.bettingMini()` / `tables.taiXiu()` straight into `lookup`;
  `slot()` reads into a local `provider` and then tests and returns *that*, not a second
  read; `registeredBettingMiniProducts()`, `registeredTaiXiuProducts()` and
  `hasSlotProvider()` are single reads. The only multi-read site is the constructor's INFO
  line, which runs before publication and is therefore not a step-5 concern. So
  `private final Tables tables` → `private volatile Tables tables` is genuinely a one-word
  edit with no accessor changes.
- **Nothing can observe a torn read today.** `tables` is a `final` field assigned once in
  the constructor; the record's components are implicitly final; both maps are
  `Collections.unmodifiableMap` wrappers around fully-built `LinkedHashMap`s. Final-field
  freeze semantics safely publish the record *and* everything reachable through it to the
  bot-creation virtual threads that read it lock-free — the same guarantee the three
  separate `final` fields had, with the interleaved-publication window of a future
  three-field reload removed in advance.

---

## The three pushbacks, ruled on

**1. No `providers present:` tail on the lookup message. Dev is right; you were wrong.**
AD-20 pins that message byte-for-byte, the plan's own A6 re-states all three strings in a
table specifically so nobody edits one, `MessageTypesErrorTextTest` asserts them as whole
literals, and verification **P2-7 greps that exact string out of `docker logs`**. Adding a
tail is an AD-20 amendment with its own evidence, not a smell fix — which is precisely what
review-2c F6 itself said ("**Fix the sentence, not the message**"). Dev fixed the sentence:
the field javadoc and the class javadoc now both state that there is no such tail and why.
That is the whole of what F6 asked for. **No amendment issued**; if the tail is genuinely
wanted, it needs an AD-20 amendment, a new pinned literal, and a P2-7 update, and it should
be raised as its own decision.

**2. The `isBlank()` guard would be a dead no-op. Confirmed — Dev's claim checks out.**
In `validateStrategyKeys`, the betting arm is `key == null || !registered.contains(key)`
and the slot arm is `!registered.contains(slotStrategyId)`. `registered` is the live
registry key set — `[DALEMBERT_*, FIBONACCI_*, MARTINGALE_*, PAROLI_*, RANDOM]` and
`[FIXED, RANDOM]` — so `""` and `"   "` fail `contains` and already take the 400 branch,
which already quotes the key (`Unknown strategyId ''`). Adding `key.isBlank()` changes no
input's outcome and no message. It would only matter if a bean registered under a blank
key, which is a different defect with a different fix. Review-2b marked this **Advisory**
and explicitly did *not* want an `isBlank()` fallback in the bot; the real defect it named
was `create()` rendering an invisible key, and quoting is exactly that fix, pinned by
`blankKeyIsQuotedInTheMessage` and its slot twin.

**3. Multi-bucket coverage rather than walking back the javadoc. Right call.** Review-2b's
complaint was that the diff "edited the comment to describe coverage instead of adding the
coverage that had just become free". Adding it resolves the finding at its root and closes
three genuinely unexercised production paths (`assign`'s slicing loop, largest-remainder
leftover across distinct buckets, insertion-order tie-break). Spelling the fixture keys as
bare literals (`"ALPHA"`, `"BETA"`) rather than `StrategyId.X.name()` is the better choice
for the reason the test states: apportionment must not care whether a key names a built-in,
and a plugin-supplied key is the end state of this feature. Test-only; AD-23 untouched.

---

## Findings (neither blocks the deploy)

**Finding 1 — the one comment the "comments that outlived what they described" commit
missed.** `BotGroupConfigValidationService.validateStrategyKeys`' javadoc still reads:

> which is strictly better than what it replaces: Jackson only ever guarded the request
> body, whereas a group whose strategy bean vanished in a deploy used to fail at
> `BettingStrategyFactory.create` on a bot thread during group start.

That is the exact sentence **Amendment A5 measured and falsified** — `validate` is not on
the group-start path, so that failure is unchanged, and the post-merge read means an
unregistered persisted key now fails *any* PATCH. A5 supplied replacement wording for
AD-15 and the plan carries it; the code javadoc still carries the falsified claim, and
`4715d20` (which corrected two neighbouring comments in the same file, one of them eight
lines above this one) is where it would naturally have been fixed. Javadoc only — no
behaviour, no deploy risk, no gate. Fix it on the next pass through this file, ideally by
pasting A5's corrected paragraph.

**Finding 2 — `AopUtils.getTargetClass` vs the recommended `AopProxyUtils.ultimateTargetClass`.**
See Risk 2. Unobservable today. Note only.

## Amendments to the plan

Two entries appended to `docs/plans/PLUGIN_HOT_RELOAD.md` under
**`## Amendment — 2026-08-27 (Phase 2 review fix pass)`**. Neither changes an Architecture
Decision in substance, any code, or any shipped behaviour.

- **A8 — the registry key has no version dimension, and a duplicate key fails the whole
  context.** The plan entry Dev could not write. Records both halves of review-2c F4, and
  states that they apply verbatim to all three registries (the mechanism is now duplicated,
  so the collision policy must be decided once, before plugins can register). Kept separate
  from A5 rather than folded in: A5 is about a key that *disappears* across a cutover, A8 is
  about a key claimed *twice*, and step 5 needs an answer to both. Cross-referenced in both
  directions.
- **A9 — verification P2-2's bracketed order is sorted, not discovery order.** A4 quoted the
  discovery-order sequence; the fix pass sorts every operator-facing key list. P2-2 passes as
  written (it asks for counts and set), so only the illustration is stale — but the releaser
  is diffing boot logs in a few days and should not have to guess. Records the new strings
  for all three registries and re-states why this stays inside AD-23 on A4's own reasoning.
