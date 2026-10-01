# QA — RIK_114_ZICZAC (Phase 1)

**Verdict:** PASS
**Build:** `mvn clean test` → **2213 tests, 0 failures, 0 errors**
(bot-api 138 · bot-strategies 126 · bot-messages 242 · bot-engine 446 · bot-app 1261)

Baseline before this QA pass was **2175** (bot-messages 222, bot-app 1252), reproduced
green on this tree before any test was added. This pass adds **38** tests across 5 new
classes; no existing test was edited, and no production file was changed.

```
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home
```

---

## Tests added

| File | Module | Tests | Covers |
|---|---|---|---|
| `bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikZicZacEndGameSemanticsTest.java` | messages | 15 | the money-path claims the single-bettor capture **cannot** test: `winningsFor` vs `p.wm` when they disagree, `odd`'s declared type, absent/empty/zero `mbs`, the centre bucket, long-width money, the exact marker set |
| `bot-messages/src/test/java/com/vingame/bot/domain/bot/message/GameMessageTypesForGameContractTest.java` | messages | 5 | `forGame` as a contract over **every provider the real component scan finds**, not a hand-written list of five |
| `bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotZicZacDispatchTest.java` | engine | 9 | the ziczac EndGame's numbers actually reaching `bot_winnings_total` / `bot_bets_placed_total` / `bot_bet_amount_total` / `JackpotScaler`, and no crowd ever being published |
| `bot-app/src/test/java/com/vingame/bot/domain/bot/service/BotFactoryForGameResolutionTest.java` | app | 7 | the **call site**: BETTING_MINI calls `forGame` with the bot's own `Game`; SLOT and TAI_XIU never do; ordering vs. the product-key throw |
| `bot-app/src/test/java/com/vingame/bot/domain/bot/service/BettingMiniLookupCallSiteGuardTest.java` | app | 2 | a source guard that there is **exactly one** `bettingMini(...)` call site in production and that it chains `.forGame(game)` |

Nothing under `src/main/` was touched, `pom.xml` is unchanged, and nothing was committed.

---

## Coverage of the diff

### `bot-api/.../message/GameMessageTypes.java` — the new `default forGame(Game)`

This was flagged as the highest-risk surface and it is: a default method on a bot-api
interface implemented by six betting-mini providers and invoked for **every** betting-mini
bot of every product. Covered three ways, deliberately overlapping:

- **Per-provider identity, over the real scan** —
  `GameMessageTypesForGameContractTest.everyProviderKeepsTheDefaultExceptTheOneSpecialisation`
  boots the same `AnnotationConfigApplicationContext` scan `MessageTypesRegistryTest` uses,
  enumerates `registeredBettingMiniProducts()`, and asserts that for **11 plugin names**
  (including `null`, `""`, `"ziczacPlugin "`, `"ZICZACPLUGIN"`) every provider returns the
  **identical instance** the registry answered with, with exactly one allowed exception
  (`RikGameMessageTypes` × `ziczacPlugin`, case-insensitively). Bom/Tip/Nohu/Win79/B52 are
  covered without being named, so the *next* brand's provider is covered on the day it is
  registered rather than the day someone remembers to extend a list. The diff's own
  `RikGameMessageTypesRoutingTest` already names the five; this is the superset.
- **Null-safety** — `nullGameIsToleratedEverywhere` (`forGame(null)` on every registered
  provider) plus the ziczac-specific `forGame(game(null))`. Both matter because the call
  is on a bot thread inside `createBot`, where an NPE is a dead bot with a stack trace
  pointing at the factory.
- **Identity/allocation** — `resolutionIsStableAcrossCalls` pins that `forGame` hands back
  a shared instance rather than allocating per bot (it is called once per bot; a `new`
  here is 3 000 objects on a 3 000-bot fleet).
- **Inventory** — `resolutionDoesNotRegisterAnything` re-asserts the product sets after
  resolving, i.e. the boot line `BETTING_MINI 6 products [097, 098, 114, 116, 118, 119]`
  that plan **V-2** greps after deploy is untouched by a resolution happening. Observed
  verbatim in the test run's own log line.

### `bot-app/.../service/BotFactory.java` — the one call site

`BotFactoryForGameResolutionTest` drives the real `createBot` against a real
`MessageTypesRegistry` holding a **Mockito spy of the real `RikGameMessageTypes`** (a
stub would make the assertion about the stub), stopping deterministically at the auth
boundary — the idiom `BotFactorySlotWiringTest` established.

- BETTING_MINI: `verify(rik).forGame(captured)` and `assertThat(captured).isSameAs(ziczac)`
  — the bot's **own** `Game`, since routing is decided on `pluginName`.
- `forGameRunsBeforeAuthentication` throws from `forGame` itself and asserts that sentinel
  arrives instead of the auth one, pinning that resolution happens *inside* the type
  switch — so no `BettingMiniGameBot` ever exists holding an unresolved provider.
- **SLOT** and **TAI_XIU**: `verify(rik, never()).forGame(any())`. This is the
  "products that have nothing to do with 114" guard: a stray betting-mini lookup in
  either branch would throw outright for any product with a slot or tai-xiu provider and
  no betting-mini one (066/103/105/222).
- `otherProductsAreUnaffected`: a 097 `BauCua` bot never reaches the 114 provider, and
  Bom's inherited default is the identity even for the `ziczacPlugin` name.
- `nullProductCodeStillFailsAtTheLookup`: `bettingMini(productKey(env)).forGame(game)`
  evaluates the lookup first, so the operator-facing
  `"ProductCode cannot be null"` (pinned by the pre-existing `BotFactoryProductKeyTest`)
  is unchanged by this feature.

`BettingMiniLookupCallSiteGuardTest` then closes the one hole the plan's own
Implementation Notes concede — *"the routing test pins the behaviour, not the call site,
so reviewers should watch for the second call site"*. Watching is not a guard. It scans
all five modules' `src/main/java` and requires exactly one non-comment invocation of
`.bettingMini(<arg>)`, in `BotFactory`, chaining `.forGame(game)`. (The zero-arg
`tables.bettingMini()` record accessor inside the registry is excluded by the pattern, not
by an exemption list.) Idiom precedent: `PerBotInfoLogGuardTest`.

### `bot-messages/.../g3/rik/RikZicZac*.java`

| Production file | Test | What is covered |
|---|---|---|
| `RikZicZacBallResult` | `RikZicZacEndGameSemanticsTest` | `odd` is **declared `double`** (reflective, by name); `b`/`r` are `long`; fractional and integral wire forms both parse; `r == b × odd` recomputed **off the deserialized record** |
| `RikZicZacEndGameMessage` | `RikZicZacEndGameSemanticsTest`, `BettingMiniGameBotZicZacDispatchTest`, (diff's) `RikZicZacGameShapeTest` | `winningsFor = sum(mbs[].r)` **and not `p.wm`**; `betAmountFor = sum(mbs[].b)`; `betCountFor` = balls with `b > 0`; `jackpotPool = tJpV`; null/empty `mbs`; exact marker set; no int overflow |
| `RikZicZacUpdateBetMessage` | `RikZicZacEndGameSemanticsTest.updateBetImplementsNoMarker`, (diff's) shape test | implements **no interface at all**, while `RikUpdateBetMessage` still carries `HasCrowdBets` — so the divergence is real and not both classes drifting together |
| `RikZicZacGameMessageTypes` | (diff's) `RikGameMessageTypesRoutingTest`, contract test | reached only via `forGame`; carries **no annotations**; five class answers |
| `RikGameMessageTypes.forGame` | contract test + routing test + factory test | case-insensitive exact-name match, null-safe, identity for everything else |

### The five points called out as money-path

1. **`odd` must be `double`.** Pinned by name in
   `ballResultComponentTypesArePinned`, and by value in
   `fractionalOddSurvivesDeserialization` / `integralOddFormIsAccepted`.
   **Mutation-verified:** narrowing the record component to `long` produces
   3 named failures, the first reading
   *"odd MUST be double: the bucket table holds 1.2 and 0.3, and Jackson's
   ACCEPT_FLOAT_AS_INT truncates them SILENTLY on an integral field"*. The two
   value tests are written with an explicit `(double)` cast so the file still
   **compiles** under the narrowing — otherwise the build breaks on a varargs type
   error and the developer never sees the message that explains what they did.
2. **`winningsFor == sum(mbs[].r)`, not `p.wm`.** The capture cannot discriminate (they
   agree 6/6 because there was one bettor — exactly the coincidence AD-5 exists to
   resist), so `winningsIgnoresPWmWhenTheyDisagree` feeds a synthetic frame carrying
   `p.wm = 999 999 999` against own balls returning `75 000` and asserts `75 000`. This is
   the **only** assertion in the build that can fail if someone "simplifies" the accessor
   onto `p.wm`. It also asserts `p`/`obs`/`jps` are absent as fields, so there is no
   accessor to reach them by accident.
3. **`betAmountFor == sum(mbs[].b)`, `betCountFor` counts `b > 0`, and `r == 0` is
   legitimate.** `centreBucketBallIsCountedNotSkipped` (three balls, two at `odd:0`, all
   counted and all staked), `allCentreRoundReportsZeroWinningsAgainstARealStake` (a real
   stake with zero winnings — the shape that looks exactly like the "bets never settle"
   failure and is not it), `zeroStakeEntryIsNotCounted` (the `b == 0` filter applies to the
   **count only**, never to the money), and the engine-level
   `allCentreRoundStakesWithoutWinning`.
4. **`jackpotPool == tJpV`.** Value, long-width, and absent-key-is-`0` in the message test;
   the rise 200 200 → 689 300 is already pinned by the diff's
   `RikZicZacCaptureArithmeticTest.theJackpotMeterRises`; and
   `jackpotPoolFeedsTheScaler` proves the meter actually reaches `JackpotScaler.observePool`
   and moves the factor off neutral — the first 114 game for which that is possible.
5. **The no-bet round yields 0/0/0 without NPE.** Three ways: `mbs: []` (diff's shape
   test), `mbs` **absent from the frame** (`absentMbsIsNotAnNpe` — not observed in the
   capture and the shape that NPEs an unguarded `stream()`), and `mbs = null` through the
   constructor. Plus `nullMbsIsSurvivable` at the engine level, where the NPE would kill
   the round handler on a bot thread.

### Engine wiring (new coverage, not in the diff)

The diff proves `winningsFor` returns the right number; nothing proved the number reaches
the counter. Those are different claims, and the gap between them is precisely where
"everything looks healthy and the dashboard says zero" lives — the failure
`CLAUDE.md` records as misdiagnosed as a wallet-partition bug twice.
`BettingMiniGameBotZicZacDispatchTest` closes it: `incBotWinnings(1_405_000)` +
`incBetsPlaced(20, 1_000_000)` in order for the captured winning round;
`incBotWinnings(6_420_000)` + `incBetsPlaced(10, 10_700_000)` for the captured **losing**
round (a gross return on a net loss — netting the stake off would emit nothing at all
under the `w > 0` guard); `never incBotJackpot`; `never observeCrowd` while
`onRoundComplete` still fires; and `null` metrics still reaching `PAYOUT`. The bot is
built with the degenerate `optionAffinities = {0: 1}` the plan's AD-11 mandates, so
`initializeSubclass` is exercised against a real ziczac-shaped `Game`.

---

## Mutation checks (the tests were proven to fail before being trusted)

Every mutation was applied to a backed-up copy and **restored byte-for-byte**; the
final verdict build ran against the restored tree.

| Mutation | Result |
|---|---|
| `RikZicZacBallResult.odd`: `double` → `long` | 3 named failures in `RikZicZacEndGameSemanticsTest`, message names AD-12 |
| `BotFactory`: drop `.forGame(game)` | 3 failures in `BotFactoryForGameResolutionTest` + 1 in `BettingMiniLookupCallSiteGuardTest` |
| `RikGameMessageTypes.forGame`: `equalsIgnoreCase` + null guard → `equals` | 3 failures + 2 NPE errors across the routing and contract tests |
| add a second, unresolved `bettingMini("114")` call site in `bot-engine` | `BettingMiniLookupCallSiteGuardTest` fails with both call sites printed |

---

## Prior RIK betting-mini work: verified untouched

The task required confirming the already-PASSed `RIK_114_BETTING_MINI` artifacts are
genuinely unchanged. They are untracked in git, so this was checked by content and mtime
against the ziczac session window (which begins 15:15 — the capture copy):

- **7 shipped 114 message classes** (`RikBetInfo`, `RikEndGameMessage`,
  `RikMainBetSummary`, `RikSubscribeMessage`, `RikUpdateBetMessage`,
  `RikStartGameMessage`, `RikStartGameMd5Message`): all mtimes ≤ 13:03, **zero**
  occurrences of "ziczac" in any of them.
- **5 shipped 114 test classes** (`RikGameMessageTypesTest`,
  `RikEndGamePayoutSemanticsTest`, `RikCrossGameShapeToleranceTest`,
  `RikTaiXiuMd5GameShapeTest`, `RikProviderRegistrationTest`): all ≤ 13:00, zero "ziczac".
- **9 stock/txmd5 fixtures + 2 captures**: all ≤ 12:45, MD5s recorded in this pass.
- The only shipped files edited in the ziczac window are the two the plan authorises:
  `RikGameMessageTypes.java` (the `forGame` override + javadoc, AD-3 step 5) and
  `RikFixtureProvenanceTest.java` (the hand-list → directory-listing generalisation,
  AD-14). AD-2's "not one shipped 114 class is edited" is loose wording that AD-2's own
  next sentence corrects ("the only shipped-code edits are three additive seams and one
  provenance-test generalisation") — no contradiction, but worth reading in that order.
- The three test-inventory edits visible in `git diff` (`MessageTypesCoverageTest`,
  `MessageTypesRegistryTest`, `ApplicationContextLoadsTest`) all add the string `"114"`
  and belong to the **earlier** feature, not to ziczac. Plan step 8's "no edit to those
  files" holds for this phase: ziczac adds no product and the boot line is unchanged.

---

## Gaps

1. **Phase 2 is deliberately untested and unimplemented.** No `GameRequestFactory`, no
   `ZicZacBet`. Per the brief, out of scope. The operational consequence is the plan's
   own and is worth repeating to the releaser: on Phase 1 alone the bots send the generic
   `Bet` body (`eid`, no `c`), whether the server reads a missing `c` as 0 or 1 balls is
   **unknown**, and at 0 balls every ziczac metric reads 0 while the engine's local
   balance drifts down. **Do not create a ziczac `Game` for metrics before Phase 2 ships.**
   V-1..V-5 are Phase 1's ceiling; a zero in V-7 is not a Phase 1 defect.
2. **`setMessageTypes(<the resolved provider>)` is not asserted from the bot's side.**
   `BettingMiniGameBot.messageTypes` is `@Setter`-only with no getter and the bot is
   constructed inside `createBot`, so no test can hold the instance without driving
   `initialize()` through a real Netty connect. What *is* proven: `forGame` is called with
   the right `Game`, inside the switch, before authentication (sentinel ordering); the
   returned instance is the ziczac provider (routing test); and a bot holding the ziczac
   EndGame class emits the right counters (engine test). The single uncovered link is a
   one-expression assignment on the line the source guard pins.
3. **No end-to-end deserialize-and-dispatch test** (fixture JSON → `BettingMiniGameBot`
   scenario → counters) in one test. The chain is covered in two halves that meet at
   `RikZicZacEndGameMessage`. A single test would have to stand up the ws-parser scenario
   machinery; the existing Tip/RIK dispatch tests draw the same line.
4. **`mbs[].p`, `p{}`, `obs`, `odds`, `jps` are unmodelled by design (AD-13)** — covered
   only as "tolerated", which is all that is assertable. Tolerance *is* asserted: the
   fixtures and my synthetic frames all carry them and parse.
5. **OI-4/OI-5 remain unfalsifiable from this evidence.** No populated `obs` and no
   jackpot discharge exist in the capture, so `HasJackpot`'s absence is pinned as a
   decision (`markerSetIsExact`), not validated against a hit.
6. **Staging verification is unchanged and still required.** V-2 (registry boot line),
   V-4/V-5 (subscribe + rounds) are the real Phase 1 gates; the build cannot prove the
   server answers CMD 12000.

---

## Observations (not defects, not blocking)

**O-1 — `RikZicZacCaptureArithmeticTest`'s javadoc over-claims, and the gap is now
covered.** Its class comment says the `r == b × odd` check *"is what fails loudly if
`odd` is ever narrowed to an integral type"*. It cannot: it reads
`ball.path("odd").asDouble()` off the raw `JsonNode` and never touches
`RikZicZacBallResult`. **Verified by mutation** — with `odd` narrowed to `long` that
class stays **green, 3/3**. (`RikZicZacGameShapeTest` does go red, with a bare
`expected: 0.3 but was 1` on one ball.) My `ballResultComponentTypesArePinned` +
`fractionalOddSurvivesDeserialization` now make the claim true by name. Suggested
follow-up for Dev: correct that javadoc sentence to point at the semantics test. No
production change implied.

**O-2 — the committed capture is shape-deduped on the inbound side too, which the plan
states only for outbound.** Plan §1 says *"the outbound frames in this file are
shape-deduped, 8 of 25"*. `_meta.totals` versus the exported bodies shows the same is
true of **inbound** `12002` (8 of 25) and `12007` (16 of 122); the tooling's documented
rule (`scripts/capture/README.md`) is 8 samples per distinct shape per cmd for inbound,
and this export predates the outbound-dedup fix.
**Nothing load-bearing is affected:** `in/12006` is **8 exported of 8 observed**, so the
arithmetic test's "8 rounds / 81 balls / 6 betting rounds" is a **census, not a sample**,
and `12000`/`12005`/`12019` are likewise complete. What *is* on thinner evidence than the
prose suggests is §3/§6's stack-vs-append reading of the 12002 echo growth, which rests
on 8 surviving frames of 25 — and that is already `OI-2`, explicitly unresolved, with
nothing in Phase 1 depending on it. Worth a one-line correction in §1 if the plan is
revised.

**O-3 — one non-reproducible `mvn clean test` failure, unrelated to this diff.** The
first full clean run failed in bot-engine with 9 errors, all
`NoClassDefFound com/vingame/bot/domain/bot/core/TaiXiu*Test$1` across 5 **pre-existing**
TaiXiu test classes that this diff does not touch. It did not reproduce: **7 subsequent
clean runs** (3 full-reactor, 4 bot-engine-only) were green on the identical tree, and
the class files (`…Test$1.class`) are present on disk. Recorded so the releaser
recognises it rather than investigates it: **if `mvn clean test` reports
`NoClassDefFound …Test$1`, re-run** — the failing classes are untouched by this feature
and the same command passes on the same sources.

---

## Failures

None. Final verdict build:

```
[INFO] Bot - API (contract module) ......... Tests run: 138,  Failures: 0, Errors: 0
[INFO] Bot - Strategies ................... Tests run: 126,  Failures: 0, Errors: 0
[INFO] Bot - Messages ..................... Tests run: 242,  Failures: 0, Errors: 0
[INFO] Bot - Engine ....................... Tests run: 446,  Failures: 0, Errors: 0
[INFO] Bot - Application .................. Tests run: 1261, Failures: 0, Errors: 0
[INFO] BUILD SUCCESS
```

---

# QA — RIK_114_ZICZAC (Phase 2, per Amendment A1)

**Verdict:** PASS
**Build:** `mvn clean install` → **2301 tests, 0 failures, 0 errors**
(bot-api 138 · bot-strategies 126 · bot-messages 308 · bot-engine 468 · bot-app 1261)

Dev's Phase 2 baseline was **2286** (bot-messages 293). This pass adds **15** tests in 2
new classes, both in bot-messages; no existing test was edited, no production file was
changed, `pom.xml` is untouched, and nothing was staged or committed (the feature is
working-tree-only by design).

```
JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home
```

## Tests added

| File | Tests | Covers |
|---|---|---|
| `bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/ZicZacBetCaptureFidelityTest.java` | 9 | `ZicZacBet` and `ZicZacRequest.subscribe()` against the **committed capture** (`captures/rik-ziczacPlugin-12000.jsonl`), not a literal; the **production-shaped mapper** (ziczac provider's subtype registrations at 9000); the capture-side evidence for `c==1` / no `eid` / no `12022`, asserted rather than quoted; long-width `b` |
| `bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikZicZacGameMessageTypesRequestForTest.java` | 6 | `RikZicZacGameMessageTypes.requestFor` on its own: zone (`MiniGame3`), plugin spelling from the `Game`, and offset all reach the wire; the explicit `offset` argument wins over `Game.offset`; the same three land through the real `forGame` path |

## Coverage of the diff

Dev's own tests are the primary coverage and I read all of them before adding anything.
What was already pinned, and by what:

| Claim (task brief) | Dev's test | Verified how |
|---|---|---|
| body is exactly `{cmd,b,c,sid,aid}`, `c == 1`, no `eid` | `ZicZacBetTest.keySetIsExact` / `noEidNoStockKeys` / `ballCountIsPinnedToOne`; bot-level `ziczacRealScenarioSendsZicZacBetAndNoCommit` reads every frame off the mocked socket | serialized JSON key set, never a getter |
| `ZicZacRequest.subscribe()` byte-identical to `Request.subscribe()` | `ZicZacRequestTest.subscribeIsIdenticalToTheSharedRequest` — node-equal body **and** `serialize()` string-equal | full frame |
| ziczac emits no commit | `ZicZacRequestTest.commitIsAbsent` (empty, and `commit` not a declared method); bot-level: only `12000`/`12002` ever leave, `never().send(12022 \| "sId")`, `pendingCommit` empty | real `botBehaviorScenario()` |
| stock byte-for-byte unchanged (`RikStockBet` + `13022`) | `stockStillSendsStockBetAndCommit` (real scenario: key set `{cmd,v,sid,aid,eid,iAc}`, no `c`, commit immediately after bet); pre-existing `RikStockBetTest` / `RikStockBetCaptureFidelityTest` / `RikStockCommitTest` untouched and green | |
| txmd5 byte-for-byte unchanged (shared `Bet`) | `txmd5StillSendsTheSharedBetAlone` (`isExactlyInstanceOf(Bet.class)`, keys `{cmd,aid,b,eid,sid}`, no `c`, no `7022`); pre-existing `RequestTest` untouched | |
| seam reached only via `forGame`, no allowlist on the ziczac provider | `RikGameMessageTypesRoutingTest.ziczacProviderIsNotABean` (no annotations), `theTwoSwitchesAreDisjoint`, `ziczacResolvesToItsOwnRequest`; `GameRequestFactoryCapabilityTest` widened to exactly `{RikGameMessageTypes, RikZicZacGameMessageTypes}` over the real scan, with `ziczacImplementsIt` keeping the widening non-vacuous | |
| `entryId` discarded | `ZicZacRequestTest.entryIdIsDiscarded` (0/1/7 → same body); bot-level `ziczacDiscardsTheStrategyOption` (option 0 vs 1 through the real strategy → identical frames) | |

The widening of `GameRequestFactoryCapabilityTest` is the one deliberate flip and it is
correct as written: `onlyRikImplementsTheCapability` still holds over *registered*
providers (the ziczac provider is not a bean), and `resolvedProvidersAreAlsoCovered`
runs the per-game matrix over every registered product × six plugin names, so the only
objects allowed to answer `true` are the two named 114 classes.

### What my two classes add

**`ZicZacBetCaptureFidelityTest`** — the same gap `RikStockBetCaptureFidelityTest` closed
for stock (RIK_114_BETTING_MINI AD-22/23): `ZicZacBetTest` pins a literal copied from the
capture, and the literal and the code share an author. This class reads the 8 exported
outbound `12002` bodies (and the one `12000`) from the jsonl and asserts:
- our key set equals every captured frame's; rebuilding each captured frame from its own
  `cmd`/`b`/`sid` reproduces it node-for-node, **including the `1 060 000` stake** (the
  test fails if the export ever loses that shape);
- the whole envelope through `ZicZacRequest.bet()` matches captured `zone`/`plugin`/body;
- `subscribe()` matches the captured client's `out 12000` (closing the triangle with dev's
  `Request.subscribe()` comparison);
- every captured bet has `c:1`, `aid:1`, no `eid`/`v`/`iAc` — the evidence behind
  AD-10/AD-11, asserted;
- the capture's outbound cmd set is exactly `{12000, 12002, 12012, 12018}` and contains
  **no `12022`** — the evidence behind "ziczac has no commit". The `12012`/`12018` frames
  are recorded, not modelled (A1 §2: feed holds on subscribe alone);
- the **production mapper** (`FAIL_ON_UNKNOWN_PROPERTIES=false` +
  `registerSubtypes(forGame(ziczac).getTypeRegistrations(9000,false))`) produces the same
  keys and a node-equal frame — no other test serializes a ziczac body through the
  registrations a ziczac bot actually installs;
- `b` is a `long` on the wire (5 000 000 000 not truncated) and `c` stays 1 whatever `b` is.

**`RikZicZacGameMessageTypesRequestForTest`** — every other test crosses `requestFor` with
`("MiniGame", 9000)`, which is exactly the pair a hardcoded
`new ZicZacRequest(plugin, "MiniGame", 9000)` would pass, so that "simplification" would
survive the whole existing suite. This class uses zone `MiniGame3`, plugin spelling
`ZicZacPlugin`, offsets 5000/9000/10000, and asserts each reaches both the subscribe and the
bet envelope; it also pins that the explicit `offset` argument (what
`BettingMiniGameBot.buildRequest` passes from its own field) is the one used, and re-runs
the assertions through the real `RikGameMessageTypes().forGame(game)` path.

## Gaps

1. **No mutation checks this pass.** Phase 1 QA proved its tests red by mutating production
   files on a backed-up copy; the Phase 2 brief says *do not touch production code*, which I
   read as forbidding even a restored mutation. Both new classes are sensitive by
   construction (they read expectations off the capture file / off the serialized frame),
   but that is an argument, not a measurement.
2. **The `12012 {"iM":false}` and `12018 {}` frames are asserted absent from our output and
   present in the capture, nothing more.** A1 §2 measured that the feed holds without them;
   the build cannot re-measure that. V-Z5 (no re-freeze after correct-body bets) remains a
   staging gate.
3. **Settlement is unprovable from the build.** V-Z1 (`bot_bets_placed_total` climbing,
   `confirmed staked > 0`) and V-Z2 (RTP 0.93–0.98 over ≥ 200 rounds) are the real Phase 2
   gates. The build proves the frame is byte-shaped like the real client's; only the
   server proves it settles.
4. **`RikGameMessageTypes.requestFor`'s javadoc is stale** — it still says the ziczac
   provider "does not implement `GameRequestFactory` in this phase (AD-24)". Dev recorded
   this; prose only, no behaviour, no test can pin prose. Not blocking.
5. **Bot-level timing.** `BettingMiniGameBotZicZacRequestDispatchTest` (dev's) relies on
   `Thread.sleep(1_100L)` for the pipeline's opening `waitFor(1_000L)` and a 5 s Mockito
   `timeout`. It passed on every run here (3 bot-engine runs). Same idiom as the existing
   `BettingMiniGameBotCommitDispatchTest`; recorded so a slow CI box is recognised rather
   than investigated.

## Observations (not defects, not blocking)

**O-4 — concurrent builds on the shared working tree.** My first `mvn clean install`
failed in bot-strategies with `NoSuchFileException: bot-api/target/bot-api-1.0.jar`
during classpath scanning. Cause: a second `mvn clean install` (PID 94831, started
14:11:35) from another agent on the same tree ran `clean` on `bot-api/target` nine
seconds before my bot-strategies tests scanned it. Not a code failure; a rerun after that
process exited was fully green. This is the same family as Phase 1's O-3
(`NoClassDefFound …Test$1`) and very likely its explanation too: **if a clean build fails
with a missing jar/class from a module this feature does not touch, check `ps` for a
second Maven before investigating.**

## Failures

None. Final verdict build (after the concurrent build had exited, no other Maven running):

```
[INFO] Bot - API (contract module) ......... Tests run: 138,  Failures: 0, Errors: 0
[INFO] Bot - Strategies ................... Tests run: 126,  Failures: 0, Errors: 0
[INFO] Bot - Messages ..................... Tests run: 308,  Failures: 0, Errors: 0
[INFO] Bot - Engine ....................... Tests run: 468,  Failures: 0, Errors: 0
[INFO] Bot - Application .................. Tests run: 1261, Failures: 0, Errors: 0
[INFO] BUILD SUCCESS
```
