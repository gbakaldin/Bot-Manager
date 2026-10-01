# Compliance — RIK_114_BETTING_MINI

Branch: `feature/dead-group-auto-recovery` (HEAD `2b21247`)
Plan reviewed: `docs/plans/RIK_114_BETTING_MINI.md` — working-tree file (mtime `2026-09-17 17:40`),
read in full: Amendment A4, AD-7 (narrowed), AD-25, amended AD-27, AD-28 … AD-34, Phase 3 steps
1–9, Phase 3b, V-16 … V-20, Amendment B1.
Diff reviewed: **working-tree state, not `git diff main..HEAD`.** The feature is uncommitted and
`main` predates the `bot-messages` split, so `git diff main..HEAD` shows none of it. Scope was
bound by **mtime**: Phase 3 is the set written `2026-09-17 17:47 – 18:13`, disjoint from the
Phase 2 set (`13:21 – 13:27`) and the Phase 1/1b/ziczac set (`09-15 18:27` – `09-16 15:52`).
Build: `mvn clean install` run **independently** on the shipped tree — `BUILD SUCCESS`,
**2262 tests, 0 failures, 0 errors** (bot-api 138, bot-messages 126, bot-strategies 278,
bot-engine 459, bot-app 1261). The five Phase 3 production files were sha256-pinned before the
build and re-verified `OK` after it. `MetricsRateLimitInterceptorUnitTest` passed 9/9 first time;
Dev's one first-pass failure in it is consistent with a timing flake in a pre-existing test, and
that class is not in Phase 3's scope.

**A note on the working tree during this review.** Plan step 8's by-hand mutations were being
exercised concurrently: at 18:19 `RikStockCommit.CommitData` carried `// MUTATION: @JsonProperty
removed` (8a), and at 18:22 `RikGameMessageTypes.requestFor` carried `if (false && … ) // MUTATION`
(8b) with a Maven fork active. My first build collided with that run (`Truncated class file` in
the bot-engine surefire fork). I waited for 90 s of no Maven process and no `MUTATION` marker,
confirmed every hash matched the pre-mutation state, and re-ran. The verdict below is on the
restored tree. Nothing in this review edited production code or tests.

**Phase 3 is the subject of this review.** Phases 1, 1b and 2 are shipped and deployed; their
verdicts are retained verbatim below.

## Verdict

PLAN_AMENDED

The **code is accepted as-is.** Phase 3 implements amended AD-27 and AD-28 … AD-33 step for step,
Phase 3b is not started, and there is no drift I would send back. The amendment is to the
**plan's test specification**, for a falsifiable error: the ordering test step 7 specified cannot
observe the production `.onSent(...)` line, so step 8(c) as written could not be satisfied by it.
Dev's second test is the shape that works, and the plan now says so (Amendment A5). Two smaller
corrections ride along; see *Amendments to the plan*.

## Phase-by-phase

### Phase 1 / 1b / 2 — shipped, re-confirmed untouched
Status: implemented (verdicts of 2026-09-16 and 2026-09-17, retained)

No Phase 1/1b/2 file changed in the Phase 3 window except the two the plan names: `RikStockRequest`
(step 3) and `RikGameMessageTypes` (step 5, javadoc only — the `requestFor` allowlist body is
unchanged, confirmed after the 8(b) mutation was reverted). `GameRequestFactory.java` (13:49),
`RikStockBet.java` (13:49) and `GameRequestFactoryCapabilityTest` (13:25) are untouched — the
plan's "if it needs an edit, the design drifted" tripwire did not fire.

### Phase 3 — send the per-bet commit `13022` on `stockPlugin`
Status: **implemented**

Step by step against the plan's numbering:

1. **`GameRequest.commit(long)`** — `default Optional<ActionRequestMessage> commit(long sid) {
   return Optional.empty(); }`, `java.util.Optional` imported, javadoc says what a commit is, that
   the default is none, that `Request`/`TaiXiuRequest` deliberately inherit it, and why it is on
   `GameRequest` and not `GameRequestFactory` (a frame the request builds). Cites AD-27/AD-28.
   **`Request.java` and `TaiXiuRequest.java` are untouched**: mtime `2026-08-04`, `git diff HEAD`
   empty on both, sha256 equal to `main`'s pre-split copies.
2. **`RikStockCommit`** — `extends ActionRequestMessage implements CmdAwareMessage`, constructor
   `(int cmd, String zoneName, String pluginName, long sessionId)`, `static class CommitData
   extends Body` with exactly one field, `@Getter(AccessLevel.NONE) @JsonProperty("sId") private
   final long sId`. **No class-level `@Getter`/`@Setter`** on `CommitData`, as the step required.
   Javadoc carries AD-29 (legacy bot's two consecutive `socket.send`, per-bet, the once-per-round
   fallback deferred to V-19 evidence) and AD-30 (`getSId()` → `sid`, the key that also appears
   on the bet frame). See *AD-30* below for the reasoning check.
3. **`RikStockRequest.commit(long)`** — `@Override`, returns `Optional.of(new
   RikStockCommit(cmdPrefix + 3022, zoneName, pluginName, sid))`. Javadoc: reached only through
   the AD-21 allowlist; browser once per round, legacy bot per bet, we follow the legacy bot.
   Class javadoc updated from "the one frame that differs" to "the two frames that differ".
4. **`BettingMiniGameBot`** — all seven edits, in the plan's order:
   - `pendingCommit` field at `:152`, `AtomicReference<Optional<ActionRequestMessage>>`, beside
     `pendingDecision`, AD-32 comment including the deliberate absence of an `onEndGame` clear.
   - `bet()` supplier `:833`: `pendingCommit.set(request.commit(currentSid));` immediately before
     `return request.bet(amount, optionId, currentSid);`. Nothing else in the supplier changed.
   - `afterBetSent(ObjectMapper)` `:859-874`: package-private, `getAndSet(Optional.empty())`,
     early return on empty, `serialize` inside `try/catch (RuntimeException)` → `log.warn`,
     `log.trace("Bot {}: sending commit sid={}", …)`, `client.send(frame)`. Matches the plan's
     snippet. No accessor added for `client`.
   - `botBehaviorScenario()` `:1034`: `.onSent(mdcConsumer(afterBetSent(mapper)))` on the
     **existing** bet stage, `mapper` being the local built at `:987`. **No new stage.** The
     `:997-1000` comment now says one scheduler per `SendAsync` *stage*, one stage in this
     pipeline, and that condition, supplier and `onSent` run on that thread in that order.
   - `beforeReconnect()` `:651`: `pendingCommit.set(Optional.empty());` beside the
     `pendingDecision` clear. `onEndGame` untouched.
   - `onStart()` `:1054-1060`: `offset + COMMIT_CODE` appended to `cmdList`;
     `private static final int COMMIT_CODE = 3022;` at `:80` with the "print-filter only — AD-33;
     nothing deserializes this CODE (AD-7)" comment; the `onStart` comment records that
     `TaiXiuGameBot` inherits a harmless `0 + 3022` entry (confirmed: `TaiXiuGameBot` does not
     override `onStart`).
5. **`RikGameMessageTypes` javadoc** — one paragraph at `:111-118` in the outbound section:
   stock also emits the per-bet `13022` (AD-27 amended, AD-29) via `GameRequest.commit` (AD-28),
   sent from the bet stage's `onSent`; `taixiuMd5Plugin` does not and settles without it. **No
   code change** in the class.
6. **Tests — bot-messages** — all present and green:
   `RikStockCommitTest` (6): exact key set `{cmd, sId}`; `contains("\"sId\":…")` **and**
   `doesNotContain("\"sid\"")` with the third-`<lower><UPPER>`-key comment pointing at B1's table;
   `cmd` derived for two offsets; `sId` equals the sid passed; envelope `["6", zone, plugin, …]`;
   no `aid`/`eid`/`v`/`b`/`iAc` and `size() == 2`. `RikStockRequestTest` +2: `commit` present, a
   `RikStockCommit`, `cmd == offset + 3022`, zone and plugin; bet and commit carry the **same sid
   under different keys** (`sid` vs `sId`). `RequestTest.commitIsAbsentOnTheSharedRequest`: empty,
   **and** declared on `GameRequest` (inherited, not re-declared — stronger than asked).
   `TaiXiuRequestTest$CommitTests` (3): both constructor shapes empty, plus the same
   declaring-class pin. `bettingMiniBetHasNoAutoBetFlag` untouched.
   `GameRequestFactoryCapabilityTest` untouched (4/4 green).
7. **Tests — bot-engine** — `BettingMiniGameBotCommitDispatchTest` (6, green): the four the plan
   asked for — one-stage real-`SendAsync` ordering (`InOrder` bet `13002`/`sid` then commit
   `13022`/`sId`, exactly two sends, slot consumed); txmd5 bet-only (`7002`, one send, a direct
   `afterBetSent` afterwards sends nothing); non-114 BOM BauCua bet-only (`5002`, `pendingCommit`
   empty by reflection, callback sends nothing); never-throws (a `RikStockCommit` mock whose
   `serialize` throws → returns normally, no `send`, slot cleared) — **plus two Dev added**:
   `stockRealScenarioSendsCommitAfterEveryBet` (the real `botBehaviorScenario()`, see
   *Drift* / *Amendments*) and `beforeReconnectClearsPendingCommit`. Bot construction follows
   `BettingMiniGameBotRikRequestDispatchTest.requestOf` (per-game `forGame` →
   `initializeSubclass`) with `seedClient`, and the private seams are driven by reflection as
   `TaiXiuGameBotStreamTest` does. Scenarios and executors are shut down in `@AfterEach`.
   `BettingMiniGameBotRikRequestDispatchTest.stockBotBuildsTheStockRequest` is widened by the
   requested assertion: `request.commit(3793247L)` present, body key set exactly `{cmd, sId}`,
   `cmd == 13022`.
   One shape difference, accepted: the BOM test drives `bet()` and `afterBetSent` directly rather
   than through a `SendAsync` stage. The three claims the plan wanted (`5002`, empty
   `pendingCommit`, no further send) are all asserted; the library path is already proven by the
   stock and txmd5 tests.
8. **Mutations** — observed being run by hand against the working tree during this review (8a at
   18:19, 8b at 18:22) and reverted. 8(c) is the subject of the amendment: it cannot red the
   step-7 test, and does red Dev's real-scenario test (deductively: that test asserts a `13022`
   frame reaches `client.send`, and `afterBetSent` has no caller other than the `.onSent` line).
9. **`mvn clean install` green**, independently confirmed. None of `MessageTypesCoverageTest`,
   `MessageTypesRegistryTest`, `ApplicationContextLoadsTest`, `MessageTypesRegistryStartupLogTest`,
   `PerBotInfoLogGuardTest`, `Log4j2TwinConfigTest` was edited (mtimes `08-18` … `09-16`). The
   full-scan boot line in the build log still reads `BETTING_MINI 6 products [097, 098, 114, 116,
   118, 119]`.

### Phase 3b — `13012`
Status: **not started — correct.** No `enterGame` on `GameRequest`, no `RikStockEnterGame`, no
`.send(…)` stage after the subscribe `onMessage`, no `3012`/`13012` anywhere in the Phase 3 files.
`13022` is the only new frame. AD-34's gate (V-17 reads zero after this deploy) has not been
evaluated and may not be pre-empted.

## The five points flagged for special attention

- **AD-31 — `onSent`, not a second `sendAsync`.** Confirmed. `botBehaviorScenario()` has exactly
  one `.sendAsync(` and the commit hangs off its `.onSent(mdcConsumer(afterBetSent(mapper)))`.
  No second stage, no timer, no `request.commit(0L).isPresent()` probe at scenario build. The
  corrected `:997-1000` comment is present.
- **AD-30 — `sId` reasoning.** `@JsonProperty("sId")` + `@Getter(AccessLevel.NONE)`, and the
  javadoc gives the **corrected** B1 reasoning: "the only one correct under **every** Lombok
  configuration … whatever the repo-root `lombok.config` says", pointing at `RikStockBet`'s
  measured table, with the no-class-level-`@Getter` point tied to B1's row 2. It does **not** say
  "otherwise two keys" — under the repo's `copyableAnnotations` line the two-key row is the
  *hand-written* getter, not Lombok's; the javadoc gets that right.
- **AD-33 — `offset+3022` in the print filter; nothing inbound modelled.** `onStart` `cmdList`
  carries `offset + COMMIT_CODE`; `OutputPrinter.filter` is `Qualifier::cmd` OR-reduced over the
  list, so the outbound `[SENT]` and any `[RECEIVED]` `13022` will print at TRACE. No new
  message class, no `messageTypeRegistrations` change, no `HasX` marker: **AD-7's "not
  modelled" half stands.** No new INFO/DEBUG line (`afterBetSent` logs at TRACE and WARN only)
  and no new metric.
- **`Request`/`TaiXiuRequest` frozen, pinned by test.** Untouched (above) and pinned:
  `RequestTest.commitIsAbsentOnTheSharedRequest`, `TaiXiuRequestTest$CommitTests` ×3, both
  asserting the method's declaring class is `GameRequest`.
- **Phase 3b not started; no both-frames sending.** Confirmed (above).

## Drift

Two deviations Dev reported, judged:

1. **Step 7 test shape vs step 8(c) — the plan was wrong; PLAN_AMENDED.** The plan's ordering
   test hands `bot.afterBetSent(mapper)` to a pipeline it builds itself, so it is independent of
   whether `botBehaviorScenario()` wires the callback. Mutation 8(c) therefore leaves it green;
   the plan's claim that it "must go red with exactly one `send`" is false as a matter of what
   the code executes. Dev kept the one-stage test (it is the right proof of AD-31's *library*
   claim) and added `stockRealScenarioSendsCommitAfterEveryBet`, which compiles the real
   scenario and asserts every `13002` is immediately followed by a `13022` with the same session
   under `sId`. That is a correct implementation of what 8(c) *meant*; the plan now specifies
   both tests and rewords 8(c) (Amendment A5 §1).
2. **"Byte-identical to `main`" / hash pinning — the premise was wrong; no amendment.** The plan
   says `git diff` on `Request.java`/`TaiXiuRequest.java` must be empty, and it is checkable
   exactly so: both are **tracked at HEAD** under `bot-messages/`, `git diff HEAD` is empty, and
   as it happens both are also sha256-identical to `main`'s copies at the pre-split path. The
   plan never uses "byte-identical" of these files. Dev's session-start hash pin is harmless
   extra; the wording stays. Recorded in Amendment A5 §3 so it is not re-raised.

No code drift.

## Out-of-scope changes

None in the Phase 3 window. `beforeReconnectClearsPendingCommit` and the declaring-class pins are
additive tests inside the phase's own subject. The unrelated modified files on the branch
(`Aviator.js`, `bc.js`, `deploy.sh`, `TaiXiuMessages/*`, DEAD_GROUP_AUTO_RECOVERY docs) predate
this phase and are not its concern.

## Verification section — achievable

V-16 … V-20 reference only things that now exist or already existed: the two groups and their
metric names (unchanged), `grep '"cmd":13022'` on `detail.log` (the frame now exists and, by
AD-33, prints under TRACE), the `sId` spelling check (pinned at build time by
`RikStockCommitTest.sIdIsNotFolded`), the txmd5 `7022`/`13022` zero-count (pinned by
`txmd5SendsBetOnly`), and the registry boot line (unchanged, seen in the build log). The
pre-flight — V-16 on the box before deploy — is the Releaser's, not checkable here.

## Amendments to the plan

`## Amendment A5 (2026-09-17)` appended to `docs/plans/RIK_114_BETTING_MINI.md`:

1. **Step 7 / step 8(c)** — records that the one-stage ordering test cannot observe the
   production `.onSent(...)` line, names `stockRealScenarioSendsCommitAfterEveryBet` as the test
   that does and specifies its shape, and rewords 8(c) so the one-stage test staying green under
   that mutation is the expected outcome.
2. **Step 6 arithmetic** — `17000 + 3022` is `20022`, not the plan's `17022`; the implementation
   asserts `20022` and is correct.
3. **Freeze check wording** — records that `git diff` on the two frozen files is checkable as
   written (tracked at HEAD; also identical to `main`'s pre-split copies) and that no wording
   change is made.

No architecture decision was altered. AD-27 (amended) … AD-34 and V-16 … V-20 stand.

---

# Retained verbatim: the Phase 2 verdict of 2026-09-17

(original header of the Phase 2 verdict follows)

Branch: `feature/dead-group-auto-recovery` (HEAD `2b21247`)
Plan reviewed: `docs/plans/RIK_114_BETTING_MINI.md` — **untracked working-tree file**, read in
full (Amendments A1/A2/A3, Findings, AD-1 … AD-27, all phases, all V-steps, OI-1 … OI-10).
Diff reviewed: **working-tree state, not `git diff main..HEAD`.** The feature is uncommitted and
`git diff main..HEAD` shows none of it; the branch also carries unrelated modified files
(`Aviator.js`, `bc.js`, `deploy.sh`, `TaiXiuMessages/*`, DEAD_GROUP_AUTO_RECOVERY docs). Scope was
bound by **mtime**: Phase 2 is the set written `2026-09-17 13:21 – 13:27`, which is disjoint from
the Phase 1 / 1b / ziczac set (`2026-09-15 18:27` – `2026-09-16 15:52`).
Build: `mvn clean install` **green** — `BUILD SUCCESS`, bot-app `Tests run: 1261, Failures: 0,
Errors: 0`. All six Phase 2 test classes ran: `RikStockBetTest` 6, `RikStockRequestTest` 4,
`RikGameMessageTypesRoutingTest` 9, `GameRequestFactoryCapabilityTest` 4,
`BettingMiniGameBotRikRequestDispatchTest` 6, and `RequestTest$BetTests.bettingMiniBetHasNoAutoBetFlag`.

**Phase 2 is the subject of this review.** Phases 1 and 1b were passed on 2026-09-16 and are
shipped and deployed to staging; that verdict is retained verbatim at the bottom of this file.

## Verdict

PLAN_AMENDED

The **code is accepted as-is** — Phase 2 implements AD-20 … AD-27 faithfully, step for step,
with no drift I would send back. The amendment is to the **plan**, for a falsifiable technical
error in AD-23 that the implementation was right to work around, and which would have sent the
next implementor into the same trap. See *Amendments to the plan*.

## Phase-by-phase

### Phase 1 — inbound message layer, provider, inventories
Status: implemented (verdict of 2026-09-16, retained; re-confirmed untouched)

Nothing in the Phase 2 window touched `RikBetInfo`, `RikSubscribeMessage`, `RikStartGameMessage`,
`RikStartGameMd5Message`, `RikUpdateBetMessage`, `RikEndGameMessage`, `RikMainBetSummary`, the
captures or the fixtures. `MessageTypesCoverageTest` and `MessageTypesRegistryTest` last changed
`2026-09-15 18:27`, i.e. in Phase 1.

### Phase 1b — AD-5 correction, payout metrics, second capture
Status: implemented (verdict of 2026-09-16, retained; re-confirmed untouched)

### Phase 2 — the RIK stock bet body (`v` + `iAc`) on the `GameRequestFactory` seam
Status: implemented

Step by step against the plan's own numbering:

1. **`GameRequestFactory`** — `bot-messages/.../message/request/GameRequestFactory.java`, signature
   `GameRequest requestFor(Game game, String zoneName, int offset)`, **verbatim** from AD-20 /
   `RIK_114_ZICZAC` AD-9. Javadoc carries all three things the step asked for: the module-direction
   argument (bot-messages → bot-api is the only legal direction, so a `default` method on
   `GameMessageTypes` would put a bot-messages return type on a bot-api interface), the
   `instanceof`-optional rationale citing the EndGame-marker precedent, and the per-game framing.
2. **`RikStockBet`** — emits exactly `{cmd, v, sid, aid, eid, iAc}`. No `b`. `aid` is the literal
   `1`; `v` is the stake; `iAc` is a constant `true`. AD-22 satisfied, and the OI-3 both-keys hedge
   is explicitly named and rejected in the javadoc.
3. **`RikStockRequest implements GameRequest`** — standalone, **not** a `Request` subclass (AD-25),
   with both reasons recorded (the freeze, and the covariant-return pin that makes subclassing
   impossible). `subscribe()` returns the same bare `{"cmd":offset + 3000}`; no `chat`, no
   `autoBet`. `RikStockRequestTest.subscribeIsIdenticalToTheSharedRequest` asserts the whole
   serialized frame equals `Request.subscribe()`'s, which is the assertion that matters here.
4. **`RikGameMessageTypes implements GameMessageTypes, GameRequestFactory`** — `STOCK_PLUGIN`
   added beside the existing `ZICZAC_PLUGIN` as a `private static final`, matched
   `equalsIgnoreCase`, the same rule `forGame` uses. **`RikZicZacGameMessageTypes` is untouched.**
5. **`BettingMiniGameBot.buildRequest`** — the three-line `instanceof` branch, exactly AD-20's
   snippet, with a javadoc block naming AD-20/AD-21 and stating that the fallback is pre-existing
   behaviour. `TaiXiuGameBot.buildRequest` is untouched (not in the Phase 2 mtime window).
6. **Tests** — all six required items exist, and the one naming instruction was followed
   (`BettingMiniGameBotRikRequestDispatchTest`, distinct from the pre-existing
   `BettingMiniGameBotRikDispatchTest` / `BettingMiniGameBotZicZacDispatchTest`, which are about
   inbound payout-marker dispatch).
7. **No inventory edit.** `MessageTypesCoverageTest`, `MessageTypesRegistryTest`,
   `ApplicationContextLoadsTest` and `MessageTypesRegistryStartupLogTest` are all outside the
   Phase 2 window (the last is untouched since 2026-08-27). No Spring bean was added, so the boot
   line V-14 pins — `BETTING_MINI 6 products [097, 098, 114, 116, 118, 119]` — is unchanged.
   The plan's own test of the design ("if one of them needs a change, the design has drifted")
   passes.

### Phase 3 — send `13012`
Status: correctly not started

`grep -rn "3012\|postSubscribe"` over `bot-api`, `bot-messages`, `bot-engine`, `bot-app` sources
returns **only javadoc prose** — `RikGameMessageTypes` and `RikSubscribeMessage` describing the
rival hypothesis, plus an unrelated pre-existing Win79 comment. No `postSubscribe` seam exists, no
`13012` body is built, and `botBehaviorScenario` gained no stage. The gate held: Phase 3 is gated
on V-12 exactly as Phase 2 was gated on V-6.

## The three points flagged for special attention

**AD-21 — allowlist, not denylist. Correct.**
`requestFor` is `if (game != null && STOCK_PLUGIN.equalsIgnoreCase(game.getPluginName())) → new
RikStockRequest(...)`, `else → new Request(...)`. That is an allowlist of exactly one. Everything
else — `taixiuMd5Plugin`, an un-captured 14000–18000 game, an unknown name, `""`, a trailing-space
`"stockPlugin "`, a `null` plugin name and a `null` `Game` — takes the shared `Request`, and every
one of those rows is asserted in `RikGameMessageTypesRoutingTest.requestRoutingMatrix` with
`isExactlyInstanceOf(Request.class)`. The txmd5 row carries a comment naming it as *the* regression
and pointing at V-14. The javadoc states the shared-instance reason (AD-11) and why a denylist
would violate OI-2. The two `pluginName` switches are additionally pinned as **disjoint** by
`theTwoDispatchPointsAreDisjoint`, which is the mitigation AD-21 asked for.

**AD-25 — `Request`/`Bet` frozen, and pinned by test rather than merely unedited. Correct, both.**
Neither `Request.java` nor `Bet.java` appears in `git status` or in any mtime window — they are
byte-identical to `main`. And the freeze is *asserted*: `RequestTest.bettingMiniBetHasNoAutoBetFlag`
was widened to `doesNotContain("\"v\"")`, `doesNotContain("\"iAc\"")`, `doesNotContain("\"iac\"")`
on top of the pre-existing exact-key-set check, and its `@DisplayName` was rewritten to
`betting-mini bet is FROZEN at {cmd, aid, b, eid, sid} — no 'a', no 'v', no 'iAc'`. Beyond that,
`GameRequestFactoryCapabilityTest` boots the **real component scan** and asserts
`RikGameMessageTypes` is the *only* registered provider implementing the capability — including
for per-game-resolved providers — so the other five products cannot acquire a forked frame by
accident, which a hand-written list of five would not have covered.

**`ZicZacBet` excluded and Phase 3 not started. Both confirmed.**
`grep -rln "ZicZacBet\|ZicZacRequest"` over all module sources returns **nothing**.
`RikZicZacGameMessageTypes` does not implement `GameRequestFactory`, and that negative is pinned
twice — `RikGameMessageTypesRoutingTest.ziczacKeepsTheSharedRequestForNow`
(`isNotInstanceOf(GameRequestFactory.class)`) and
`BettingMiniGameBotRikRequestDispatchTest.ziczacBotKeepsTheSharedRequest` — each with a comment
telling the next phase to flip it deliberately, which is what AD-24 asked for.

## Drift

**One deviation, pre-approved by the user, recorded here rather than flagged.**

`RikStockBet.BetData.iAc` ships as `@Getter(AccessLevel.NONE) @JsonProperty("iAc") private final
boolean iAc = true;`. The plan's Phase 2 step 2 says to "mirror `TaiXiuBet`'s shape
(`@Getter`/`@Setter` static `BetData`) with one addition that is mandatory: `@JsonProperty("iAc")`
on the `iAc` field" — i.e. it does not mention suppressing the generated getter. Followed
literally, that produces `{"iac":true,"iAc":true}`: both properties serialize. The implementation's
extra `@Getter(AccessLevel.NONE)` is **necessary for the frame to be correct**, not a stylistic
choice, and it is the shape the user authorised. It is also documented at length in the class
javadoc and pinned by `RikStockBetTest.iAcIsNotMangled`
(`contains("\"iAc\":true")` + `doesNotContain("\"iac\"")` + `doesNotContain("\"IAc\"")`).

I re-derived the table independently against the jar on this module's classpath before accepting
it:

```
jackson = 2.15.2
isIAc()                                          -> {"iac":true}
getIAc()          (the AutoBet trick)            -> {"iac":true}
@JsonProperty("iAc") + Lombok isIAc()            -> {"iac":true,"iAc":true}
@JsonProperty("iAc") + @Getter(AccessLevel.NONE) -> {"iAc":true}   <- shipped
isMini()                                         -> {"mini":true}
getIsMini()       (the real AutoBet)             -> {"isMini":true}
```

No other drift. Nothing else in the Phase 2 window deviates from the plan.

## Out-of-scope changes

None in the Phase 2 window. The eight files written `2026-09-17 13:21 – 13:27` are exactly the
Phase 2 deliverables (five new, `RikGameMessageTypes` extended, `BettingMiniGameBot.buildRequest`
extended, `RequestTest` widened).

The branch carries unrelated working-tree changes — `Aviator.js`, `bc.js`, `deploy.sh`, deleted
`TaiXiuMessages/*`, the DEAD_GROUP_AUTO_RECOVERY docs, `docs/reviews/WIN79_119_PROD_ACCOUNTS/`,
`scripts/` — none of which are this feature's and none of which the Phase 2 window touched. As in
the Phase 1 verdict: **the RIK work must be staged selectively**, and the branch choice is the
user's call.

## Verification section — achievable

V-11 … V-15 are the Releaser's steps and every referenced thing exists.

- V-11's baseline uses the two existing staging groups (`$COINS` / `$TXMD5`) and the three
  `bot_bet*` / `bot_winnings_total` metrics, all pre-existing.
- V-12's verdict metric `bot_bets_placed_total` is fed from `HasBetTotals.betCountFor` off
  `RikEndGameMessage`, which Phase 1b shipped and this phase did not touch. The plan's
  "read the magnitude correctly" note (positions, not clicks) still matches
  `RikEndGameMessage.betCountFor`.
- V-14's registry boot string is unchanged by this phase, as step 7 requires and as the untouched
  inventory tests confirm.
- The "if V-12 still reads zero" branch names `RikStockBetTest` (exists, 6 tests, green) and the
  routing test (exists, 9 tests, green).
- V-6's warning that the `grep '"cmd":13002' detail.log` step does not work is retained and is
  correct; V-12/V-13 use metrics and the aggregated DEBUG line instead. OI-10 records the same
  defect in `RIK_114_ZICZAC.md`, unfixed there by design.

## Amendments to the plan

`docs/plans/RIK_114_BETTING_MINI.md` gains **`## Amendment B1 (2026-09-17)`** at the bottom, plus
two clearly-marked in-place pointers (in AD-23 and in the Phase 2 implementation note) so the
corrected text is reachable from where the error is. Nothing was rewritten or removed.

**1. AD-23's cited precedent is technically wrong.** AD-23 says the codebase "hand-wrote the getter
around it — `AutoBet.AutoBetData.getIsMini()`", which reads as *hand-writing the getter is how you
fix this*. Measured, `getIAc()` serializes as `iac` — the trap, not the fix. Jackson folds the
**entire leading uppercase run**: `getIsMini` → `IsMini` has a run of one (`I` followed by
lowercase `s`), so `isMini` survives; `getIAc` → `IAc` has a run of two, and both fold.
`AutoBet` works by accident of its field name and the technique does not generalise. The amendment
states that, gives the measured table, records that step 2 as written was insufficient, names
`@Getter(AccessLevel.NONE)` as the approved and correct shape, and warns that ziczac's `c` field
and any future RIK frame meet the same question. This is a falsifiable claim about what the API
does, verified above — not a preference — which is why it is a plan amendment rather than a
send-back.

**2. The Jackson version is 2.15.2, not 2.18.2.** `mvn -pl bot-messages dependency:tree -Dincludes=
com.fasterxml.jackson.core:jackson-databind` resolves `2.15.2`. Behaviour is identical on both, so
**nothing else in the plan or the code depends on it**; corrected in AD-23 and in the Phase 2
implementation note so a reader re-running the empirical check is not left doubting which claim to
trust.

**What the amendment does not change:** no architecture decision is reversed, no phase boundary
moves, and V-11 … V-15 are untouched.

*Not committed — per the plan's own note and the user's standing rule, the user controls what
lands.*

---
---

# Retained verbatim: the Phases 1 & 1b verdict of 2026-09-16


Branch: `feature/dead-group-auto-recovery` (HEAD `2b21247`)
Plan reviewed: `docs/plans/RIK_114_BETTING_MINI.md` — **untracked working-tree file**, read in full
including `## Amendment A1` and the corrected AD-5 before the code.
Diff reviewed: working tree vs `HEAD` — the untracked
`bot-messages/src/{main,test}/.../g3/rik/`, `src/test/resources/{captures,messages/rik}/`, plus
`git diff` of `MessageTypesCoverageTest` / `MessageTypesRegistryTest`. Nothing RIK-related is
committed, which is what the plan's own Implementation Note says to expect ("this is a
working-tree feature branch; nothing is committed or pushed").
Build: `mvn -o clean install` green — bot-api 138, bot-engine 126, **bot-messages 185**,
bot-integration 431, **bot-app 1252**; exactly the stated baseline. The three RIK test classes
ran (10 + 5 + 3).

## Verdict

PASS

Phase 1 (as superseded by Phase 1b) and Phase 1b are implemented faithfully. Phase 2 is
correctly absent and its V-6 gate is documented in the provider's own javadoc, not only in the
plan. No plan amendment was made and none was warranted.

## Phase-by-phase

### Phase 1 — inbound message layer, provider, inventories (read as a RECORD, superseded in part)
Status: implemented

- Package/prefix per AD-1: `com.vingame.bot.domain.bot.message.g3.rik`, `Rik*`.
- `RikGameMessageTypes` is `@Component @MessageTypesImpl(gameType = BETTING_MINI, products = "114")`
  and hardcodes **no** CMD — all four come from `getTypeRegistrations(offset, md5)` (AD-2). The
  second capture at offset 4000 is what proves it, and the txmd5 test registers at 4000 through
  the same provider.
- AD-3 honoured: every money field is `long` (`b`, `v`, `wm`, `m`, `mB`, `tJpV`, `tJpv2`, `sid`);
  `int` only for `eid`, `bc`, `gS`, `d1..d3`.
- AD-4: `crowdBets()` maps `obs` with a `bs` fallback through one shared `RikBetInfo`;
  `obs` entries have no `b`, so `ownBet` is 0, the Bom shape.
- AD-6: `cH` / `htr` / `bH` are not modelled, and `subscribe.json` keeps the real 50-entry `cH`
  and nested `bH` verbatim, so the tolerance is a regression test.
- AD-7: 13007 / 13012 / 13018 / 13022 modelled nowhere; the `wLp` string hazard is recorded in
  javadoc only.
- AD-8: `ps` unmodelled, and `endGameToleratesNonEmptyPs` uses **inline synthetic** JSON, not a
  fixture file — exactly as specified.
- AD-12: `startGameMd5Type()` returns a real class; `getMd5Hash()` returns `"-"` verbatim, pinned.
- AD-13: `RikUpdateBetMessage.getGameState()` returns 0 with the "do not invent a default"
  reasoning.
- Inventory edits are exactly the two §7 rows: `"114"` deleted from
  `BETTING_MINI_NOT_YET_IMPLEMENTED`, `"114"` added to the exact-set pin. No stale `114` comment
  survives anywhere in `MessageTypesCoverageTest`. The three "no edit required" rows were
  verified to still pass unchanged.
- Fixtures are the four frames the plan names, including `endGame.json` = `sid 3793249`
  (`mbs.wm = 4704`, not confusable with the stake) and `updateBet.json` = the `b:3000` sample.

Superseded parts (correctly): `RikMaxBetSummary` no longer exists, and the inverted marker pin is
gone. Nothing was reported against the Phase-1 record where Phase 1b supersedes it.

### Phase 1b — correct the AD-5 code, wire the payout metrics, commit the second game
Status: implemented

1. **Second capture committed** — `captures/rik-taixiuMd5Plugin-7000.jsonl` is **byte-identical**
   to `/Users/gleb/Downloads/taixiuMd5Plugin-7000-capture.jsonl` (md5 `2754d16a…`), `_meta` line
   included. The stock capture is likewise byte-identical (`77ba7a1d…`). AD-14 / AD-18 met.
2. **`RikMaxBetSummary` → `RikMainBetSummary`** — file, record and both references renamed. The
   `<h2>Do NOT wire this to a metric (AD-5)</h2>` section is **deleted, not annotated**; a grep
   across `bot-messages` for "do not wire this" / "NOTHING else" / "room maximum" / the old class
   name returns nothing. The replacement javadoc carries the backend constants, the
   one-bettor-capture explanation, `wm` = gross return including stake, and `m` modelled without
   interpretation.
3. **`RikEndGameMessage`** — `implements HasCrowdBets, HasBotWinnings, HasBetTotals`; `wm`, `iJp`,
   `tJpV`, `tJpv2` added; `rs`, `md5`, `ps`, `bPl` still unmodelled; the old "NOTHING else" block
   is gone and `crowdBets()` is unchanged with "speculative" dropped in favour of "a code path a
   real game depends on".
4. **Provider javadoc** — both captures described with their shape deltas, AD-11 rewritten to the
   two-games/two-offsets form with the rule explicitly *not* relaxed for 14000-18000,
   `startGameMd5Type()` javadoc says the md5 path is live rather than a hedge, outbound-bet
   section left as-is.
5. **`RikBetInfo` javadoc** — `b` supported by the 1.98x arithmetic, `b` absent-not-zero on a
   no-bet round, `v` explicitly still unresolved (OI-4), `bc` = players.
6. **Four `txmd5-*` fixtures** present, all verbatim frame bodies, including the
   `txmd5-endGame-noBet.json` absence case.
7. **Tests** — the AD-5 pin is inverted to `endGameExposesOwnWinningsAndStake` with the *corrected*
   reasoning (backend constants) and the real values 4704 / 3000 / 1; `RikTaiXiuMd5GameShapeTest`
   covers 50000/3000, crowd `{1,2}`, the 64-hex hash, 198000 / 100000 / 1, the `bs` fallback and
   the 0/0/0 no-bet round; `RikFixtureProvenanceTest` routes per fixture to its own capture and
   pins the twin intactness (22 bodies, contains 7000/7005/7006, **does not contain 7002**).

**AD-17 verified specifically.** `betAmountFor` = `sum(mbs[].b)` → else `sum(bs[].b)` → else 0;
`obs` is never a source on any path. `betCountFor` counts entries with stake `> 0` on the same
two branches, javadoc'd as positions-not-clicks with the `TaiXiuEndGameMessage` precedent and the
`bc`-counts-players warning. `winningsFor` is **precedence, never summation**: `mbs` non-empty →
`sum(mbs[].wm)`, otherwise the top-level `wm`; the "they would be the same money" rationale is on
the method. Returned verbatim — nothing nets the stake off.

**AD-19 verified specifically.** `iJp` / `tJpV` / `tJpv2` are modelled fields on
`RikEndGameMessage`; the class implements **neither** `HasJackpot` **nor** `HasJackpotPool`, and
`endGameExposesOwnWinningsAndStake` asserts both `isNotInstanceOf` so a later "helpful" addition
has to argue with a test. The `tJpV` cross-family inversion trap is stated in one sentence.

**Fee bases are not unified.** No arithmetic constant exists anywhere in `src/main` — grep for
`0.98` / `1.98` returns javadoc only. The stock formula (`stake x (100 + d1)/100 x 0.98`, 2% of
gross, exact in 3/3) lives on `RikMainBetSummary`; the txmd5 `1.98x` (2% of profit) lives on
`RikBetInfo` and is restated in `RikTaiXiuMd5GameShapeTest` as "unlike stock's 2% of gross". The
only product-wide number stated is the ~0.98 **RTP anchor**, which AD-15 does assert for both
games. I re-derived all four figures from the committed captures: 735 / 980 / 4704 / 198000 all
check out.

**V-10's trap is recorded where a configurer would see it.** `RikGameMessageTypes`' class javadoc
carries "**This game is `BETTING_MINI`, not `GameType.TAI_XIU`**", naming `taixiuJackpotPlugin`,
the fixed CMDs 1105/1102/1104/1100 and the consequence ("subscribes on a CMD the game never
answers"). The same warning is in the plan's Implementation Notes and in V-10 itself.

### Phase 2 — RIK bet body (`v` + `iAc`)
Status: correctly not implemented (gated on V-6, which has not been run)

Confirmed absent: no `usesStockBetBody` anywhere in `bot-api`/`bot-messages`/`bot-engine`/`bot-app`,
no `RikBet.java`, `Request` / `Bet` / `BettingMiniGameBot` untouched (`git diff --stat` over those
modules is empty apart from the two test files). The only `iAc` occurrences in the whole source
tree are the three javadoc lines in `RikGameMessageTypes` that describe the open question.

The gating is documented in code, not only in the plan: the provider's
"**The outbound bet frame is an open question**" section states the divergence, names V-6 and its
file, gives both decision arms, and spells out the operational consequence of shipping before the
answer ("a 114 group may observe rounds correctly and still stake nothing — the 'everything looks
healthy, confirmed staked reads 0' shape"). That is the right place for it.

## Open items — still accurately open

- **OI-4 (`v` vs `b` unseparated)** — open, and stated as open in `RikBetInfo`'s javadoc with the
  reason (single bettor in *both* captures) and the reading actually used. Nothing in the code
  asserts the other reading.
- **OI-5 (no Down bet captured)** — open. Nothing computes a payout, so nothing depends on the
  sign inversion, and `RikEndGameMessage` explicitly declines to decode which `eid` is "up". See
  the one observation below about phrasing.
- **OI-6 (jackpot)** — open; fields modelled, markers absent, omission test-pinned (AD-19).
- **OI-7 (`rs` unmodelled)** — open; `rs` is named as deliberately unmodelled and its redundancy
  with `d1`/`d2`/`d3` recorded.
- **OI-1** remains closed and the code carries the corrected rationale, not a struck-through one.
- **OI-2** — the "capture before enabling another 114 game" rule is in the provider javadoc in
  full, including what to do if shapes differ (widen these classes, not a second provider).

## Verification section — achievable

Every referent exists: `POST` and `GET /api/v1/game/{brandCode}/{productCode}/{envId}` are both on
`GameController`; `Game.crowdCountSemantic` / `CrowdCountSemantic.PLAYERS`, `optionAffinities`,
`pluginName`, `offset`, `gameId`, `md5` are all real fields; `bot_messages_total`,
`bot_bet_amount_total`, `bot_bets_placed_total`, `bot_winnings_total` are all live metric names.
V-2's expected smoke string is produced by `MessageTypesRegistry`'s sorted boot line and is now
pinned by the edited exact-set test, so `BETTING_MINI 6 products [097, 098, 114, 116, 118, 119]`
is what a deploy will print. V-6 and V-9/V-10 are on-server steps that cannot be run here and are
correctly left to the releaser.

## Out-of-scope changes

None inside the feature's own surface — `src/main` outside `g3/rik` is untouched and no other
product's message layer was modified or "tidied".

Two context notes for whoever lands this, neither attributable to this feature:

- The working tree also carries unrelated pre-existing modifications (`Aviator.js`, `bc.js`,
  `deploy.sh`, `TaiXiuMessages/*`, the AVIATOR/DEAD_GROUP docs) and untracked tooling
  (`scripts/capture/`, `scripts/bulk-create-accounts.py`). `scripts/capture/infer_schema.py` and
  its README are *referenced by* the plan as existing tooling and predate Phase 1; they are not
  part of this deliverable. The RIK work must be staged selectively.
- The RIK work sits on `feature/dead-group-auto-recovery` rather than its own branch. The plan
  anticipates the uncommitted state; the branch choice is the user's call.

## Observations (non-blocking, recorded rather than sent back)

1. **`RikFixtureProvenanceTest` enumerates fixtures from two hardcoded lists** rather than from
   the `messages/rik/` directory. All eight fixtures that exist today are in those lists, so
   AD-14's "every `messages/rik/*.json` is node-equal to a frame body in its capture" is true of
   the current tree and the prefix routing AD-18 asks for is honoured in effect. The trade is
   deliberate-looking and cuts both ways (an allowlist catches a *deleted* fixture, which a glob
   would not; a glob catches an *added* one, which the allowlist does not). Worth knowing when a
   third 114 game's fixtures are added: they must be added to the list, or they are unbound.
2. **`RikMainBetSummary`'s stock formula is written unqualified** — "`stockPlugin` pays
   `stake x (100 + d1)/100 x 0.98` — exact in 3/3 captured rounds" — where all three captured
   rounds were Up (`eid 0`) bets. AD-15 states the Down side as `(100 - d1)%`, and OI-5 records
   that it was never observed. Nothing in the code computes a payout, so this is phrasing only,
   but the "3/3" is doing work the sentence does not fully scope.
