# Compliance — RIK_114_ZICZAC

Branch: `feature/dead-group-auto-recovery` (working tree; nothing committed — the plan says so itself)
Plan reviewed: `docs/plans/RIK_114_ZICZAC.md` (working-tree copy, mtime 2026-09-16 15:13:04)
Also read: `docs/plans/RIK_114_BETTING_MINI.md` `## Amendment A2` (mtime 15:13:20)
Diff reviewed: the working tree — `git diff` for the two tracked production files plus the
untracked ziczac set. **Not** `git diff main..HEAD`: this feature is uncommitted, and
`main..HEAD` would show only the unrelated DEAD_GROUP_AUTO_RECOVERY commits.

## Verdict

**PASS**

Build: `mvn clean install` under JDK 21 — **BUILD SUCCESS**, `bot-api 138 / bot-strategies 126 /
bot-messages 222 / bot-engine 437 / bot-app 1252 = 2175 tests, 0 failures, 0 errors, 0 skipped`.
Exactly the stated baseline. The +17 over `RIK_114_BETTING_MINI`'s shipped 2158 lands entirely in
bot-messages (7 + 6 + 3 new, +1 on the rewritten provenance test); every other module's count is
unmoved, which is itself the AD-2 regression claim.

## How the change set was bounded

The ziczac session is cleanly separable by mtime: the `RIK_114_BETTING_MINI` Phase 1b work ran
12:34–13:03 and the ziczac work ran 15:13–15:22. `find -newermt "2026-09-16 15:10"` returns
**exactly 20 files**, all of them in the plan's target list:

| | files |
|---|---|
| production (tracked) | `bot-api/…/GameMessageTypes.java`, `bot-app/…/BotFactory.java` |
| production (untracked dir) | `RikGameMessageTypes.java` (+`forGame` only), `RikZicZac{BallResult,EndGameMessage,UpdateBetMessage,GameMessageTypes}.java` |
| tests | `RikFixtureProvenanceTest`, `RikGameMessageTypesRoutingTest`, `RikZicZacGameShapeTest`, `RikZicZacCaptureArithmeticTest` |
| evidence | `captures/rik-ziczacPlugin-12000.jsonl`, 6 × `messages/rik/ziczac-*.json` |
| docs | `RIK_114_ZICZAC.md`, `RIK_114_BETTING_MINI.md` (Amendment A2) |

Nothing else in the repo was touched in that window. No production code outside the two seams and
the one `forGame` override.

## Phase-by-phase

### Phase 1 — the ziczac message layer and the game dimension
Status: **implemented**

Step by step against the plan's eight numbered items:

1. **Evidence** — `bot-messages/src/test/resources/captures/rik-ziczacPlugin-12000.jsonl` is
   **`cmp`-identical to `/Users/gleb/Downloads/ziczacPlugin-12000-capture.jsonl`**, `_meta` header
   included. 74 lines = 1 `_meta` + **73 bodies**, matching `_meta.exported: 73`. cmds present:
   12000/12002/12005/12006/12007/12017/12018/12019.
2. **Fixtures** — all six present, and I re-derived each one from the capture independently of the
   provenance test: `ziczac-{subscribe,startGame,updateBet,endGame,endGame-loss,endGame-noBet}.json`
   are **JSON-node-equal to a real frame body**. Values match the plan's spec exactly —
   `1995089`: 20 balls, `sum(b)=1 000 000`, `sum(r)=1 405 000`, `tJpV=684 300`; `1995087`:
   `sum(b)=10 700 000`, `sum(r)=6 420 000`, `tJpV=513 800`; `1995085`: `mbs:[]`, **no `p` key**,
   `tJpV=204 400`. `ziczac-subscribe.json` keeps `cH` (36 chat lines), `htr` and the 17-entry
   `odds` table verbatim; `ziczac-updateBet.json` is the 7-entry `eid 0..6 / b:60000` sample.
3. **Message classes** — `RikZicZacBallResult` is `record (long b, long r, double odd)`;
   `RikZicZacUpdateBetMessage extends UpdateBetMessage` with `getGameState() → 0` and **no marker**;
   `RikZicZacEndGameMessage extends EndGameMessage implements HasBotWinnings, HasBetTotals,
   HasJackpotPool` with fields `sid`, `mbs`, `tJpV`, `iJp` and the four accessors as specified. All
   three `@JsonIgnoreProperties(ignoreUnknown = true)`.
4. **`RikZicZacGameMessageTypes`** — plain class, **zero annotations** (pinned by
   `ziczacProviderIsNotABean`, which asserts `getAnnotations()` is empty, not merely that
   `@MessageTypesImpl` is absent). Subscribe / StartGame / StartGameMd5 return the existing `Rik*`
   classes (AD-4); UpdateBet and EndGame return the two new ones.
5. **The seam** — `default GameMessageTypes forGame(Game game) { return this; }` on
   `GameMessageTypes`, with the registry-key rationale, the `PLUGIN_HOT_RELOAD` A8 pointer, the
   implementer contract (case-insensitive, null-tolerant, stateless) and the one-call-site warning
   all in the javadoc. `RikGameMessageTypes` overrides it against a `private static final
   RikZicZacGameMessageTypes ZICZAC`, `equalsIgnoreCase`, null-guarded on both `game` and
   `pluginName`.
6. **Wiring** — `BotFactory:172` is `messageTypesRegistry.bettingMini(productKey(env)).forGame(game)`.
   SLOT and TAI_XIU branches untouched.
7. **Tests** — every assertion the plan enumerated is present and passing (details under
   *Verification achievability* below).
8. **No edit to the four inventory tests.** `MessageTypesCoverageTest`,
   `MessageTypesRegistryTest` and `ApplicationContextLoadsTest` *are* modified in the working tree,
   but at 12:38–13:00 — they are `RIK_114_BETTING_MINI`'s edits (adding `"114"` to the
   betting-mini product set), already covered by that feature's three verdicts, and their diffs
   contain nothing ziczac-related. `MessageTypesRegistryStartupLogTest` is untouched entirely. The
   boot line still reads `BETTING_MINI 6 products [097, 098, 114, 116, 118, 119]`. **The design did
   not drift from AD-3** — this was the falsifiable test step 8 set up, and it passes.

### Phase 2 — the ziczac bet body (`b` + `c`)
Status: **not started — compliant, and correctly scoped**

Nothing from Phase 2 leaked in. `GameRequestFactory`, `ZicZacBet` and `ZicZacRequest` do not exist;
`BettingMiniGameBot.buildRequest` is unmodified (it is not in the 15:10+ file set); no provider
implements a request capability. The plan states Phase 1 "ships alone and changes nothing for any
existing group", and it does.

**Is the deferral documented where a reader would find it?** Yes, in the two places that matter:

- `RikZicZacGameMessageTypes`' javadoc carries a dedicated section, *"The outbound bet body is NOT
  supplied here yet"*, which names the real client's frame, names the shared `Bet`'s divergence,
  points at `docs/plans/RIK_114_ZICZAC.md` Phase 2, states that **whether the server reads a
  missing `c` as 0 balls or 1 is unknown**, and ends with **"Do not run a ziczac group for metrics
  before Phase 2."** That is the file anyone enabling this game opens first.
- The plan's `## Verification` opens with an explicit **Phase split** paragraph and repeats
  `(Phase 2)` in the V-6 and V-7 headings, with "Do not read a zero in V-7 as a Phase 1 defect if
  Phase 2 is not deployed."

One gap, non-blocking and not the Dev's to close: `CLAUDE.md` has **no RIK / P_114 section at all**,
so there is nowhere in it for this caveat to be stale. That is consistent with `RIK_114_BETTING_MINI`,
which also added none. I would put a single CLAUDE.md entry covering all three 114 games after
Phase 2 ships, not before — a half-game documented in the top-level guide is worse than none.

## The specific claims I was asked to falsify

**AD-1 — `GameType.BETTING_MINI`, and containment that is real rather than asserted.** Held. The
game type is BETTING_MINI everywhere (fixtures' `Game.builder()`, the plan's V-3 body). The three
containment claims are structural, not rhetorical:

- *inert crowd* — `BettingMiniGameBot:483` and `:580` both gate on `msg instanceof HasCrowdBets`.
  Neither ziczac class implements it, so `BetCoordinator.observeCrowd` is **unreachable** for this
  game. Not "we pass an empty list"; the branch does not execute.
- *degenerate `optionAffinities`* — `Game.getEffectiveOptionAffinities()` throws on empty, so
  `{"0": 1}` is mandatory rather than stylistic, and with one key every picker is a constant
  function. Stated in the provider javadoc under "Game facts an operator needs" and in V-3.
- *discarded `optionId`* — this one is genuinely **Phase 2's** property (`ZicZacBet` has no `eid`
  field). In Phase 1 the generic `Bet` still carries `eid`, and the plan says so in Implementation
  Notes. Not a gap; correctly sequenced.

**AD-3 — resolution parameter, not registry key; A8 deferral recorded; exactly one call site.**
Held on all three. The `forGame` javadoc spells out why the key is not widened *and* names
`PLUGIN_HOT_RELOAD` Amendment A8 step 5 as the owner of that decision, with the reason ("entangled
with the version axis"). `grep` over all four modules' `src/main` finds `bettingMini(` outside the
registry itself at **exactly one** line — `BotFactory:172` — and `.forGame(` at exactly that same
line. The registry inventory, boot line and coverage tests are provably unperturbed (step 8 above).

**The `p.wm` refusal.** Held, and the harder path is genuinely taken. `winningsFor` is
`mbs.stream().mapToLong(RikZicZacBallResult::r).sum()`. There is **no `wm` field, and no `p` field,
anywhere in the ziczac classes** — `grep` finds the string `wm` exactly once across all four, inside
a javadoc `{@link}` to the *other* game's class. So the naive implementation is not merely unused,
it is unrepresentable. The reasoning survives in the source in two places: a full section of
`RikZicZacEndGameMessage`'s class javadoc naming the `uid`/`u`/`dn` tell, the `MAIN_BET_ARRAY`
constant pair, and explicitly that reading `p.wm` "would replay that mistake on a different field
one plan later"; and a whole test method,
`theTwoOwnReturnCandidatesAgreeEverywhere`, whose `as(...)` message reads "the source we read (sum
of mbs[].r) must equal the source we deliberately do NOT read (p.wm)". I verified the underlying
fact independently: **6/6 betting rounds agree, 2/2 no-bet rounds carry no `p` at all.**

**`odd` is `double`.** Held. The record declares `double odd`. Three layers pin it: the shape test
asserts `odd() == 0.3d → r == 15_000` and `odd() == 1.2d → r == 60_000`; the capture test asserts
`r == b × odd` across **all 81 balls** (I re-derived: 81 balls, **0 mismatches**); and the capture
test asserts `fractionalOdds == 54`, which is a direct tripwire on the truncation. A `long` field
would fail all three loudly rather than silently — which is the whole point of AD-12.

**`HasJackpotPool` for ziczac only.** Held. `RikZicZacEndGameMessage implements ... HasJackpotPool`;
`RikEndGameMessage` (stock/txmd5) implements `HasCrowdBets, HasBotWinnings, HasBetTotals` and
explicitly **not** the two jackpot markers, with the omission pinned. The ladder is asserted as an
exact list, `containsExactly(200_200, 204_400, 204_400, 406_800, 513_800, 674_300, 684_300,
689_300)`, plus `isSorted()` and last > first. I re-derived it from the capture and it matches
(the plan's seven-value prose elides the one repeat at 204 400). `iJp` is `false` in 8/8, asserted
in the same loop, and `HasJackpot` is absent and pinned absent. Consumption is gated on
`jackpotScaler != null`, i.e. on `Game.jackpotScaleEnabled`, so the downside really is bounded at
zero.

**The three shipped 114 classes are byte-for-byte unchanged.** Held, to the strongest standard
available for an uncommitted tree. `RikBetInfo` (12:37:01), `RikMainBetSummary` (13:01:27) and
`RikEndGameMessage` (13:03:05) all predate the 15:13 start of the ziczac session, and the
`-newermt 15:10` listing excludes them. The nine pre-existing fixtures (18:25 on 09-15 and 12:34
on 09-16) and five of the six pre-existing RIK test classes are likewise untouched. The two files
in that directory that *did* change are the two the plan authorises: `RikGameMessageTypes`
(`forGame` + javadoc, AD-3 step 5 — its five accessors are unchanged) and `RikFixtureProvenanceTest`
(AD-14). The three PASS verdicts and the 2158 baseline remain valid statements about the same
artifacts, and the +17 test delta confined to bot-messages corroborates it from the other side.

**The open items are still open.** Verified individually against the source, not just the javadoc:

- **OI-1 (`mbs[].p`)** — the record has three components, `b`/`r`/`odd`. No `p`. A dedicated
  javadoc section says why, including the 19-entries/17-distinct evidence.
- **OI-2 (stack vs. append)** — `betAmountFor`'s javadoc states the accessor is *model-independent*
  and that this is why it does not depend on the answer. Nothing in the code picks a side. The
  `betCountFor` javadoc separately handles the `c > 1` case as a hypothetical.
- **OI-3 (max ball count)** — the only `20` in the code is `hasSize(20)` on one named fixture, which
  is a fact about that round, not a cap. No bound is asserted or enforced anywhere.
- **OI-4 (`obs`)** — unmodelled; the class javadoc records `obs: []` in 8/8 *including the 6 we bet
  in*, and repeats the user's report that it carries payouts when populated, tagged OI-4.
- **OI-5 (discharge)** — `iJp` is modelled but deliberately unwired, with the "folded into
  `mbs[].r` or reported separately" question written out in both the class javadoc and the marker-pin
  test's comment.

Nothing silently resolves any of them, and each unknown is preserved in a committed capture.

## Verification achievability

Every step of `## Verification` references something that now exists.

- **V-1** universal. **V-2** is the load-bearing one and is achievable *and* pre-confirmed by the
  build: no new bean, boot line unchanged. **V-3**'s body creates a BETTING_MINI/9000/`ziczacPlugin`
  game with `{"0":1}` affinities — all supported fields. **V-4/V-5** depend only on Phase 1's
  parsing, which the fixtures exercise at the same offset and `md5` setting production will use.
- **V-6/V-7** are correctly marked Phase 2 and are *not* achievable against this diff — the plan
  says so in its own phase-split paragraph, so this is compliance, not a gap.
- **V-8/V-9** unchanged and achievable.

One thing a releaser should carry forward: V-5's "no line mentioning ziczac, 114, RIK or
`InvalidTypeIdException`" is the real Phase 1 pass/fail, and V-7's zero must **not** be escalated
while Phase 2 is unshipped.

## Dev's three judgement calls — adjudicated

**1. "every other provider's default `forGame` returns `this`" — is AD-3's blast radius correctly
described? — Dev is right; the plan's count is loose prose, the implementation is correct.**

I enumerated the providers. `GameMessageTypes` is implemented by **six** classes — `Bom`, `B52`,
`Win79`, `Nohu`, `Tip`, `Rik` — of which `B52` carries no `@MessageTypesImpl` and is therefore not a
registered bean; plus the new non-bean `RikZicZac`. `TaiXiuMessageTypes` (3 impls) and
`SlotMessageTypes` (1 impl) are **separate interfaces that do not declare `forGame` at all**, so
"their default `forGame` returns `this`" is not a statement that can be written, let alone tested.
Dev's test covers the five other `GameMessageTypes` implementations — the complete set — and
including the unregistered `B52` is a small bonus, since it would inherit the seam the moment it is
registered.

AD-3's "all eight providers" appears in the argument *against* the rejected alternative (widening
the registry key), where the count is over registered beans the registry would have to re-key: 5
betting-mini + 3 TAI_XIU = 8, with the product-neutral SLOT provider and the unregistered B52 not
counted. Read that way it is defensible; read as "eight things with a `forGame`" it is simply wrong.
Either way it is a rhetorical count inside a rejected alternative and **decides nothing about the
shipped code**. Not drift, not worth an amendment. Recorded here so the next reader does not go
hunting for three missing overrides.

**2. `bs` left unmodelled on `RikZicZacUpdateBetMessage` — correct, and it is the plan's own
preferred option.**

The plan's Phase 1 step 3 offers the choice explicitly and states its preference in the same breath:
"Model `bs` only if Dev wants it as documentation — nothing reads it, and AD-13's posture says leave
it out." Dev took AD-13. That is not a judgement call the Dev had to defend at all; it is the
default the plan named. It is also the better of the two: the class exists *to remove a marker*, so
a field nothing reads would be pure deserialization liability against zero benefit, and the shape is
already preserved twice over — in the capture and in `ziczac-updateBet.json`, both of which the
provenance test now binds together. The javadoc says exactly this and cites the `ps`/`bPl`
precedent on `RikEndGameMessage`. Approved.

**3. The added un-prefixed-fixture pin — the right fix, and it repairs a real hole in AD-14's own
wording.**

AD-14 asks for "a classpath directory listing, keeping the prefix mapping rule (`txmd5-*` → txmd5,
`ziczac-*` → ziczac, **else stock**) and **failing loudly on a fixture matching no prefix rule**."
Those two clauses contradict each other: with `else stock` as a total fallback, the set of fixtures
"matching no prefix rule" is **empty**, so the fail-loudly requirement is vacuous as written. A
fourth game's `foo-endGame.json` would be silently verified against *stock's* frames and would
simply fail with a confusing "not a verbatim frame body from rik-stockPlugin-13000.jsonl", or — far
worse — would accidentally pass if it happened to be node-equal to a stock frame.

Dev's fix pins the un-prefixed set to the five known stock fixture names, with an `as(...)` message
that tells the next author what to do ("a new game's fixtures must be named `<game>-*.json` and
given a prefix rule"). That is the minimum change that makes AD-14's stated intent true, and it
preserves AD-2: the alternative — renaming the five stock fixtures to `stock-*` and dropping the
fallback — would have churned shipped fixtures and the shipped test that reads them.

The objection worth stating is that this reintroduces a hand-maintained list of five, which is the
shape AD-14 set out to remove. But the **polarity is inverted**, and that is the whole difference:
the old lists were **fail-open** — forget to add a file and it went unchecked, which is precisely how
six ziczac fixtures could have slipped in unverified — whereas the new list is **fail-closed** —
forget to add a file and the build goes red. A fail-closed inventory of a *closed* legacy set is not
the defect the AD was aimed at. Approved as written; I would not ask for it to be changed.

## Drift

None.

## Out-of-scope changes

None attributable to this feature. The working tree carries unrelated pre-existing modifications
(`Aviator.js`, `bc.js`, `deploy.sh`, `TaiXiuMessages/*`, the DEAD_GROUP_AUTO_RECOVERY and
PLUGIN_HOT_RELOAD docs, `scripts/`), plus the whole uncommitted `RIK_114_BETTING_MINI` feature.
All predate the 15:13 ziczac session and were excluded from review by scope.

Two observations that are **not** drift and need no action from Dev:

- `RIK_114_BETTING_MINI.md` gained `## Amendment A2` at its head, ahead of A1. The plan asked for
  "a one-paragraph pointer"; what landed is a three-bullet section. It is correctly marked as an
  amendment, is additive, states its date, and does exactly what was asked — stop anyone
  implementing the superseded AD-10. Longer than specified, better than specified.
- AD-2's phrase "the 3 RIK test classes" undercounts: there were **six** pre-existing RIK test
  classes. The substance is unaffected and is what actually shipped — only `RikFixtureProvenanceTest`
  was edited, and AD-14 authorises exactly that edit.

## Amendments to the plan

None. Both imprecisions found (AD-3's "eight providers", AD-2's "3 RIK test classes") and the one
internal contradiction (AD-14's vacuous fail-loudly clause) are wording, not wrong claims about how
the codebase or the wire behaves, and in every case the shipped code implements the AD's evident
intent. `PLAN_AMENDED` is reserved for a real technical mistake; none is present. The
contradiction in AD-14 is recorded above and, more durably, in the comment on the assertion Dev
added — which is where the next person to touch that test will actually read it.

---

# Compliance — RIK_114_ZICZAC — Phase 2

Branch: `feature/dead-group-auto-recovery` (working tree; nothing committed — by design, Phase 1 included)
Plan reviewed: `docs/plans/RIK_114_ZICZAC.md` (working-tree copy, mtime 2026-09-18 14:06:22).
**Binding spec: `## Amendment A1` → "What Phase 2 now is — smaller than written", items 1–6**,
which supersedes the original `### Phase 2` where they differ. Dev's record:
`### Phase 2 — implementation record (2026-09-18)` under A1.
Diff reviewed: the working tree, bounded by mtime (below). **Not** `git diff main..HEAD`.

## Verdict

**PLAN_AMENDED** — on one parenthetical only. The diff is accepted as-is; nothing goes back to Dev.

A1 item 1 says "mirror `RikStockBet`'s shape (`@Getter(AccessLevel.NONE)` fields)". That
parenthetical is false about `RikStockBet` and, followed literally, yields a bet body of
`{"cmd":12002}` (reproduced — see *Amendments* below). Dev's deviation is the only reading that
produces the body item 1 itself specifies. Amendment appended to the plan as
`## Amendment — 2026-09-18 (Compliance, Phase 2)`.

Build: `mvn clean install` under JDK 21 — **BUILD SUCCESS**, `bot-api 138 / bot-strategies 126 /
bot-messages 308 / bot-engine 468 / bot-app 1261 = 2301, 0 failures, 0 errors, 0 skipped`.
That is **15 more than Dev's 2286**, and the 15 are not Dev's: `ZicZacBetCaptureFidelityTest` (9,
mtime 14:10:04) and `RikZicZacGameMessageTypesRequestForTest` (6, 14:10:36) were written *after*
Dev's 14:06 record while this pass was running — QA's, by their own javadoc. 308 − 15 = 293 =
Dev's bot-messages figure, and Dev's +19 over the 2267 baseline is exactly
`ZicZacBetTest` 8 + `ZicZacRequestTest` 6 + `BettingMiniGameBotZicZacRequestDispatchTest` 4 +
the `GameRequestFactoryCapabilityTest` +1. Dev's claim is corroborated by subtraction. The two
QA files are outside this review's scope and were not evaluated.

## How the change set was bounded

`find -newermt "2026-09-18 00:00"` (excluding `target/`, `.git`, `.idea`) returns 14 files. Five
predate the session and are unrelated (`scripts/bulk-create-accounts.py`, the WIN79 handover, the
`RIK_114_BETTING_MINI` plan + session state — all 13:26–13:38). The Phase 2 window is
**13:58:16 – 14:06:22** and contains **exactly nine files**, all of them in the brief:

| | files |
|---|---|
| production (new) | `request/ZicZacBet.java` (13:58:16), `request/ZicZacRequest.java` (13:58:31) |
| production (changed) | `g3/rik/RikZicZacGameMessageTypes.java` (13:58:54) |
| tests (new) | `ZicZacBetTest`, `ZicZacRequestTest`, `bot-engine/…/BettingMiniGameBotZicZacRequestDispatchTest` |
| tests (changed) | `GameRequestFactoryCapabilityTest`, `g3/rik/RikGameMessageTypesRoutingTest`, `bot-engine/…/BettingMiniGameBotRikRequestDispatchTest` |
| docs | `docs/plans/RIK_114_ZICZAC.md` (the implementation record) |

**Every file the brief forbade is untouched**, by mtime: `MessageTypesCoverageTest` and
`MessageTypesRegistryTest` (09-15 18:27), `ApplicationContextLoadsTest` (09-16 12:54),
`MessageTypesRegistryStartupLogTest` (08-27), `Request` and `Bet` (08-04), `RikStockBet` (09-17
13:49), `RikStockRequest` (09-17 17:55), `GameRequestFactory` (09-17 13:49),
`RikGameMessageTypes` including `requestFor` (09-17 18:38), `BettingMiniGameBot` (09-17 18:42),
`BotFactory` (09-16 15:47). Nothing from the superseded original `### Phase 2` was re-created:
`GameRequestFactory` and the `buildRequest` `instanceof` branch are the RIK_114_BETTING_MINI
Phase 2 artefacts, unmodified.

## Phase-by-phase (A1 items 1–6)

### Item 1 — `ZicZacBet`
Status: **drifted → plan wrong → implemented** (see *Amendments*)

Body is exactly `{cmd, b, c, sid, aid}`: `b` from the constructor, `c` a `final int = 1`, `aid`
a `final int = 1`, `sid` from the constructor, `cmd` via `Body`. No `eid`, `v` or `iAc` field
exists on the class — unrepresentable, not merely unset. `extends ActionRequestMessage implements
CmdAwareMessage` with a nested `BetData extends Body`, the same skeleton as `Bet` and
`RikStockBet`. The javadoc carries every clause item 1 asks for: per-ball `b` and pinned `c`
(AD-10), `entryId` discarded and the echo's `eid` being the server's slot index (AD-11), and a
dedicated section stating that the `<lower><UPPER>` trap cannot bite because every key is
lowercase — that `@JsonProperty` here would be noise, and that this is *no* argument for removing
it from `RikStockBet` / `RikStockCommit`. The one deviation is the Lombok shape, adjudicated
below.

### Item 2 — `ZicZacRequest implements GameRequest`
Status: **implemented**

Standalone `@AllArgsConstructor` class over `(pluginName, zoneName, cmdPrefix)`; not a `Request`
subclass (pinned: `isStandalone` asserts `isNotInstanceOf(Request)` and `(RikStockRequest)`, and
that `bet()` is neither a `Bet` nor a `RikStockBet`). `subscribe()` is
`new SubscribeToLobbyMessage(zoneName, pluginName, new Body(cmdPrefix + 3000))` — the same
expression as `Request.subscribe()` — and `subscribeIsIdenticalToTheSharedRequest` pins it by
**`serialize(MAPPER)` string equality**, exactly the `RikStockRequestTest` method the plan named.
`bet()` returns `ZicZacBet` (covariant narrowing, like `RikStockRequest`). **`commit()` is not
declared** — `commitIsAbsent` asserts both the empty `Optional` and, reflectively, that no
`commit` method is declared on the class, so the inheritance is pinned rather than the result.

### Item 3 — `RikZicZacGameMessageTypes implements GameRequestFactory`
Status: **implemented**

`requestFor(game, zoneName, offset)` → `new ZicZacRequest(game.getPluginName(), zoneName,
offset)`, unconditional — no allowlist, with a javadoc section explaining why that is not an
omission (the provider is only reachable through `forGame` matching `ziczacPlugin`, so the game
dimension is already applied). The "outbound bet body is NOT supplied here yet" section from
Phase 1 is replaced by "IS supplied here (Phase 2)". The class still carries **zero annotations**
(the `MessageTypesImpl` import is javadoc-only), so `ziczacProviderIsNotABean` holds and the boot
line is unperturbed. The five accessors are unchanged from Phase 1.
`GameRequestFactoryCapabilityTest.CAPABLE_PROVIDERS` widened from `{RikGameMessageTypes}` to
`{RikGameMessageTypes, RikZicZacGameMessageTypes}` — deliberately, with `ziczacImplementsIt`
keeping it non-vacuous and `resolvedProvidersAreAlsoCovered` (formerly `…ExceptRik`) walking
every registered provider × six plugin names to prove nothing else answers `true`.

### Item 4 — `entryId` discarded (AD-11)
Status: **implemented**

`ZicZacRequest.bet` never reads `entryId`. `entryIdIsDiscarded` serializes with `0`, `1` and `7`
and asserts byte-identical output. At bot level `ziczacDiscardsTheStrategyOption` covers the same
through the real scenario.

### Item 5 — tests
Status: **implemented**

- `ZicZacBetTest` (8): key set `containsExactlyInAnyOrder("cmd","b","c","sid","aid")`; no
  `"eid"`/`"v"`/`iAc` substring; `c` is an integer `1` across a range of stakes; values match
  the captured frame; stake untouched; **every key all-lowercase and no `@JsonProperty` on any
  field** (this is the test that turns the amended item-1 guidance into a build failure if
  someone "fixes" it); `cmd` derives from offset; envelope `[…, "MiniGame", "ziczacPlugin", body]`.
- `ZicZacRequestTest` (6): as under item 2.
- `BettingMiniGameBotZicZacRequestDispatchTest` (4) — **on the real `botBehaviorScenario()`**, as
  item 5 requires: ziczac emits `ZicZacBet` frames and `verify(client, never()).send(… "12022" ||
  "\"sId\"")`; stock control still sends `RikStockBet` `{cmd,v,sid,aid,eid,iAc}` followed by
  `{"cmd":13022,"sId":…}`; txmd5 control still sends the shared `Bet` `{cmd,aid,b,eid,sid}` alone
  with no `7022`. All three legs the plan named.
- The three AD-24 placeholders flipped as recorded: `ziczacKeepsTheSharedRequestForNow` →
  `ziczacResolvesToItsOwnRequest` plus a ziczac row in `requestRoutingMatrix` (`isExactlyInstanceOf
  (ZicZacRequest)`, including the `"ZicZacPlugin"` case variant); `ziczacBotKeepsTheSharedRequest`
  → `ziczacBotBuildsTheZicZacRequest`.

### Item 6 — `mvn clean install` green
Status: **implemented** — see *Verdict* for the count reconciliation.

## Drift

**One item, adjudicated as plan error, not code error.** A1 item 1's parenthetical
"(`@Getter(AccessLevel.NONE)` fields)". Dev shipped class-level `@Getter` and no field
annotations, and flagged the deviation. I checked both halves of Dev's claim independently:

1. `RikStockBet.BetData` is `@Getter @Setter` at class level; `@Getter(AccessLevel.NONE)` appears
   on **one** field, `iAc`, together with `@JsonProperty("iAc")`. The plan's description of the
   class it says to mirror is simply wrong.
2. Counterfactual, run against the project's Jackson under JDK 21: a `Body` subclass with private,
   getter-less, unannotated `b`/`c`/`sid`/`aid` serializes to `{"cmd":12002}`; the same class with
   getters serializes to `{"cmd":12002,"b":60000,"c":1,"sid":1995084,"aid":1}`. No mapper in
   `bot-*/src/main` widens field visibility. So the plan's parenthetical and the plan's specified
   body are mutually exclusive.

That is a falsifiable, false claim about the codebase whose literal execution defeats the phase's
purpose — the `PLAN_AMENDED` bar, not "Dev preferred X". Dev's choice is not merely acceptable, it
is the only correct one, and it is the one the *rest* of item 1 already presupposes.

**Not drift:** `ZicZacBet implements CmdAwareMessage` is not in A1's text but is what `Bet` and
`RikStockBet` both do — part of "mirror the shape". `ZicZacRequest.bet` narrows its return type
to `ZicZacBet`, as `RikStockRequest.bet` does to `RikStockBet`.

## Out-of-scope changes

None attributable to Phase 2. The nine-file window is exactly the brief's list. The tree's
unrelated dirt (`Aviator.js`, `bc.js`, `deploy.sh`, `TaiXiuMessages/*`, the DEAD_GROUP /
VIPTALK / AVIATOR docs, the uncommitted `RIK_114_BETTING_MINI` feature, and now QA's two 14:10
test files) was left alone and not reviewed.

**Stale prose, recorded and not actioned** (Dev was forbidden the file): `RikGameMessageTypes.
requestFor`'s javadoc still says the ziczac provider "does **not** implement `GameRequestFactory`
in this phase (AD-24) … Its own body ships with `RIK_114_ZICZAC` Phase 2". It is self-dated, so
a reader can tell it is history, and it has no behaviour. Written into the amendment as a
next-touch cleanup so it is not lost.

## Verification achievability (A1 "Verification for Phase 2", V-Z1..V-Z5)

All five reference things that now exist. V-Z1/V-Z2 read `bot_bets_placed_total` /
`bot_winnings_total`, fed through `HasBetTotals` / `HasBotWinnings` on `RikZicZacEndGameMessage`
(Phase 1); V-Z3's expected outbound `12002` with `b`, `c:1` and no `eid` is exactly what
`ZicZacBet` now emits; V-Z4's stock/txmd5 controls are the two legs the dispatch test pins at
build time; V-Z5 is a server observation. One caveat for the releaser, not a gap: V-Z1 counts
*server-confirmed balls* from `mbs`, so it depends on the bet being **settled**, not merely
sent — a flat zero there with V-Z3 showing correct outbound frames points at the server, not at
this diff.

## Amendments to the plan

`## Amendment — 2026-09-18 (Compliance, Phase 2)` appended to `docs/plans/RIK_114_ZICZAC.md`.
It (a) corrects A1 item 1's parenthetical with the per-key rule — class-level `@Getter` always;
`@Getter(AccessLevel.NONE)` + `@JsonProperty` together, only for a `<lower><UPPER>` key — citing
`RikStockBet`'s real shape and the `{"cmd":12002}` reproduction; and (b) records the stale
`requestFor` javadoc as a next-touch cleanup. Nothing else in the plan was changed.
