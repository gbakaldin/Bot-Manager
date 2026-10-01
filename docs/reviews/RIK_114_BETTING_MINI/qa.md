# QA — RIK_114_BETTING_MINI — Phase 3 (the per-bet commit `13022`)

**Verdict:** PASS
**Build:** `mvn clean install` → **2265 tests, 0 failures, 0 errors** (bot-api 138,
bot-strategies 126, bot-messages 278, bot-engine 462, bot-app 1261). Baseline before my
tests: **2262 / 0 / 0**, reproducing the reported figure exactly; my class adds 3. None
of the three known flakes (`TaiXiu*Test$1` NoClassDefFound, the bot-api fork error,
`MetricsRateLimitInterceptorUnitTest.bucketMapStaysBounded`) appeared on the single run,
so no re-run was needed. The `ForkedBooter` stack traces in the output are intentional
error-path logging from bot-app tests (`intentional — captures only`, `auth gateway
returned 503`), not fork failures.

Reviewed against `docs/plans/RIK_114_BETTING_MINI.md` **Amendment A4 and AD-28…AD-34**.
Phases 1, 1b and 2 are deployed and were not re-QA'd — their verdicts are retained below.
Phase 3b (`13012`) was **not** required and is not assessed.

## Scope bounding

As in Phase 2, `git diff main..HEAD` shows none of this; the feature is uncommitted on
`feature/dead-group-auto-recovery`. Scope was bound by **mtime** to the 2026-09-17
17:47–18:13 cluster:

| file | state | Phase 3 content |
|---|---|---|
| `bot-messages/…/request/GameRequest.java` | modified vs HEAD | `default Optional<ActionRequestMessage> commit(long)` → empty (AD-28) |
| `bot-messages/…/request/RikStockCommit.java` | new | `{cmd, sId}` body, `@JsonProperty("sId")` + `@Getter(AccessLevel.NONE)` (AD-29/AD-30) |
| `bot-messages/…/request/RikStockRequest.java` | modified (untracked) | `commit(sid)` override → `RikStockCommit(cmdPrefix + 3022, …)` |
| `bot-messages/…/g3/rik/RikGameMessageTypes.java` | modified (untracked) | **javadoc only** — verified by stripping comments: no code line mentions `commit` or `3022` |
| `bot-engine/…/core/BettingMiniGameBot.java` | modified vs HEAD | `COMMIT_CODE`, `pendingCommit`, set in `bet()`, `afterBetSent`, `.onSent(…)` on the existing bet stage, `beforeReconnect` clear, `offset + COMMIT_CODE` in `onStart`'s cmd list, corrected threading comment |
| 6 test files (2 new, 4 widened) | — | see below |

### Dev's claims — each checked, all true

- **`Request.java` / `TaiXiuRequest.java` untouched.** mtime `2026-08-04 16:10:44` on
  both (and on `Bet.java`); `git diff HEAD --` on both is empty. AD-28 holds. Both
  `RequestTest` and `TaiXiuRequestTest` additionally pin `getDeclaringClass() ==
  GameRequest.class`, so a re-declared override returning empty would also be red.
- **The commit rides the existing bet stage, no second `sendAsync`.** One `.onSent(…)`
  on the `INFINITE` stage; no new `sendAsync`, no new timer, no new executor. Confirmed
  against the ws-parser 3.0.5 source (`SendAsync.processInternal`): `onSent.accept`
  runs on the stage's own scheduler thread immediately after `client.send(bet)` returns,
  and `interval > 0` means `scheduleAtFixedRate` — so AD-31's ordering claim and AD-32's
  "an escaping exception kills the loop" claim are both literally true of the library.
- **`stockRealScenarioSendsCommitAfterEveryBet` compiles the real
  `botBehaviorScenario()` and goes red without `.onSent(…)`, while the plan's step-7
  one-stage test does not.** Both halves confirmed by mutation (below).
- **`RikGameMessageTypes` — no code change.** Confirmed.
- **2262 / 0 / 0.** Reproduced.

## Tests added / updated

**Mine (new):**

- `bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotCommitLoopSurvivalTest.java` — 3 tests:
  - `poisonedCommitDoesNotKillTheBetLoop` — **the consequence AD-32 protects**, on the
    real `SendAsync` (`INFINITE`, 100 ms): the bot's real `request` is wrapped so
    `commit()` returns a body whose `serialize` throws on **every** tick; asserts ≥ 3
    bet frames still leave, ≥ 3 commits were built (the poison was reached), nothing
    `13022`/`sId`-shaped ever reached the wire, and `pendingCommit` is popped. Dev's
    `callbackNeverThrows` proves the callback returns; this proves the fixed-rate task
    is still alive afterwards. **Under mutation (try/catch removed) it fails with
    `Wanted at least 3 times, But was 1 time`** — exactly the silent-dead-bot-loop
    failure mode.
  - `staleCommitCannotOutliveReconnect` — the one interleaving where a parked commit is
    not consumed by the runnable that parked it: supplier parks `commit(A)`,
    `beforeReconnect` lands before `onSent`, the aborted tick's callback fires (sends
    nothing), a new round `B` runs one full tick. Asserts exactly two frames, bet `B`
    then commit `B`, and **the old sid appears on no frame**.
  - `txmd5RealScenarioNeverSendsACommit` — the negative on the **real**
    `botBehaviorScenario()` (Dev's `txmd5SendsBetOnly` uses a hand-built stage):
    `taixiuMd5Plugin` at offset 4000 through the subscribe and ≥ 2 bets; every frame is
    `7000` or `7002`, none contains `7022` or `sId`, bets carry `b` + `sid` and no `v`.

**Dev's (verified, not edited):**

- `bot-messages/…/request/RikStockCommitTest.java` (new, 6) — key set exactly
  `{cmd, sId}`; literal `"sId":3793247` + `doesNotContain("\"sid\"")` +
  `doesNotContain("\"SId\"")`; cmd derives from offset (13022 / 20022); envelope
  `["6", zone, plugin, body]`; not-a-bet (`no aid/eid/v/b/iAc`, `size() == 2`). All
  read the **serialized string**, none round-trips.
- `bot-messages/…/request/RikStockRequestTest.java` (+2) — `commit(sid)` present, a
  `RikStockCommit` at `offset + 3022` with zone/plugin; bet and commit carry the same
  session under `sid` vs `sId`.
- `bot-messages/…/request/RequestTest.java` (+1) and `TaiXiuRequestTest.java` (+3) —
  `commit(sid)` empty on the shared shapes (both TaiXiu constructor forms), and
  declared on `GameRequest`, not overridden.
- `bot-engine/…/core/BettingMiniGameBotCommitDispatchTest.java` (new, 6) — one-stage
  ordering on the real `SendAsync`; **real-scenario** bet-then-commit after every bet;
  txmd5 bet-only; BOM bet-only with `pendingCommit` empty; callback never throws;
  `beforeReconnect` clears the parked value.
- `bot-engine/…/core/BettingMiniGameBotRikRequestDispatchTest.java` (+1 assertion) —
  the seam-resolved request's `commit()` body is `{cmd, sId}` with `cmd == 13022`.

## Mutation results (each applied by hand, run, reverted; content re-verified with `cmp`)

| # | mutation | expected | observed |
|---|---|---|---|
| 1a | drop `@JsonProperty("sId")` from `CommitData` | commit key-set test red | **red — 7 tests** (5 `RikStockCommitTest`, 2 `RikStockRequestTest`). Note the actual wrong shape: with neither annotation nor getter Jackson sees **no session property at all** (`expected: 2 but was: 1` — key set `{cmd}`), not the `sid` fold. Red either way. |
| 1b | keep `@JsonProperty`, replace `@Getter(AccessLevel.NONE)` with `@Getter` (Lombok emits `getSId()`) | may stay green under this repo's `lombok.config` | **green, 12/12** — and that is `lombok.config` doing its job, not a gap: `javap -v` on the mutated class shows `getSId()` carrying `RuntimeVisibleAnnotations: JsonProperty(value="sId")`, copied by `copyableAnnotations`, so field and getter merge into one `sId` property. **Not a defect.** |
| 1b′ | same as 1b with the `copyableAnnotations` line disabled | red | **red** — body serializes as `{"cmd":13022,"sid":3793247,"sId":3793247}` (Amendment B1 row 2 exactly), caught by `doesNotContain("\"sid\"")` and by the key-set assertion. This is the proof that the literal-key + folded-key-absent pair is the right test design. |
| 2c | comment out `.onSent(mdcConsumer(afterBetSent(mapper)))` in `botBehaviorScenario()` | only the real-scenario test red | **red: `stockRealScenarioSendsCommitAfterEveryBet` only.** `stockSendsBetThenCommitInOrder` (the plan's step-7 shape, which wires its own `.onSent`) stays green — confirming Dev's claim that the plan's test could not catch this and the real-scenario test was needed. |
| 2b | disable the `STOCK_PLUGIN` row in `RikGameMessageTypes.requestFor` | stock tests red, nothing on the txmd5 side | **red: `stockRealScenario…`, `stockSendsBetThenCommitInOrder`, `stockBotBuildsTheStockRequest` — and nothing else.** `txmd5SendsBetOnly`, `otherProductSendsBetOnly`, `callbackNeverThrows`, `beforeReconnectClearsPendingCommit` all green: the allowlist protects the direction that matters. |
| 3 | remove the `try/catch` from `afterBetSent` | loop-survival test red | **red: `poisonedCommitDoesNotKillTheBetLoop` (`But was 1 time`) and Dev's `callbackNeverThrows`.** The stack trace under the mutation runs `SendAsync.lambda$processInternal$0` → `afterBetSent` → poison, i.e. the exception escapes the fixed-rate runnable, which is the thing AD-32 forbids. |

## Coverage of the diff

- `GameRequest.commit` default ← `RequestTest.commitIsAbsentOnTheSharedRequest`,
  `TaiXiuRequestTest.CommitTests` (3) — empty and inherited.
- `RikStockCommit` ← `RikStockCommitTest` (6, wire-level), `RikStockRequestTest` (2),
  `BettingMiniGameBotRikRequestDispatchTest.stockBotBuildsTheStockRequest`.
- `RikStockRequest.commit` ← `RikStockRequestTest.commitReturnsTheStockCommit`,
  `betAndCommitAgreeOnTheSession`.
- `BettingMiniGameBot.bet()` set / `afterBetSent` pop / `.onSent` wiring ←
  `BettingMiniGameBotCommitDispatchTest` (stock one-stage + real scenario; txmd5; BOM),
  `BettingMiniGameBotCommitLoopSurvivalTest.txmd5RealScenarioNeverSendsACommit`.
- `afterBetSent` never-throw ← `callbackNeverThrows` (returns normally) **+**
  `poisonedCommitDoesNotKillTheBetLoop` (the loop survives).
- `beforeReconnect` clear ← `beforeReconnectClearsPendingCommit` **+**
  `staleCommitCannotOutliveReconnect` (no stale sid on the wire).
- **`pendingCommit` lifecycle audit** (attack 4): exactly four references in the whole
  codebase — declaration, `bet()` set, `afterBetSent` `getAndSet`, `beforeReconnect`
  set-empty. Nothing in `TaiXiuGameBot` or elsewhere reads or writes it. Bet and
  commit are built from the **same `currentSid` local** in one supplier run, so a
  commit's `sId` can never disagree with the bet it follows; the only cross-thread
  writer is `beforeReconnect`, and a clear landing between set and pop yields *no*
  commit (harmless — the bet itself is on a dying socket), never a stale one. **No
  path to a stale-sid commit was found.**
- **No behaviour change on the other five products** (attack 5): per bet they now do
  `pendingCommit.set(Optional.empty())` (a singleton — no allocation), an `onSent`
  callback that returns at `isEmpty()`, and one MDC set/restore via `mdcConsumer`. No
  new frame (the `Request`/`TaiXiuRequest` `commit` is empty and pinned), no new timer
  (one stage, verified), no new log line at INFO or DEBUG — the two new log calls in the
  diff are `log.trace` (stock only, per bet) and `log.warn` (exception path only);
  `BettingMiniGameBot` still contains zero `log.info(` and `PerBotInfoLogGuardTest`
  ran green in the full build. The print-filter integer `offset + 3022` matches nothing
  on other products: CODE `3022` appears only in the RIK files, and Tai Xiu's fixed CMDs
  are all in the 1000–1105 range, so the inherited `0 + 3022` entry on `TaiXiuGameBot`
  is inert.

## Gaps

- **AD-33's `offset + COMMIT_CODE` print-filter entry in `onStart` is not pinned by a
  test.** `onStart` calls `onNewSession → checkBalance` against the gateway client and
  registers the `OutputPrinter` scenario through `getClient().addScenario`, so testing
  the cmd list needs a heavier fixture (log4j2 appender capture + a gateway stub) than
  a TRACE-only observability aid warrants. It is **self-verifying in V-19**: the plan
  says the TRACE window is unrunnable without it, so a wrong entry surfaces as "no
  `13022` frames in `detail.log`" on the first capture. Not a release blocker.
- **Nothing tests the server's reply to `13022`** — by design (OI-14: never seen;
  AD-7: nothing inbound is modelled). V-17/V-19 on the box are the only test.
- **The real-scenario tests are wall-clock based** (`Thread.sleep(1_100L)` against
  `waitFor(1_000L)`, then Mockito `timeout(5–6 s)` on the production 1 s bet interval).
  `WaitFor` *skips* early arrivals rather than blocking, so the sleep must exceed 1 s
  and does; a slow box only makes the sleep longer, never shorter. Dev's
  `stockRealScenarioSendsCommitAfterEveryBet` and my `txmd5RealScenario…` each ran
  green on 4 and 3 runs respectively (baseline + mutations + full build) and take ~3 s
  each. If either ever flakes under load, the fix is a larger `timeout`, not a
  loosened assertion.
- **Mutation 1a's actual failure shape differs from the plan's prediction.** AD-30 says
  dropping `@JsonProperty` yields `sid`; on a getter-less private field it yields *no*
  key. The prediction is right for the shape it was written about (a field with a
  getter, B1's table); on the shipped shape the annotation is what makes the private
  field visible at all. Both are red, so the test design is not affected — recorded so
  the next reader is not surprised.

## Housekeeping

- Every mutation was reverted by copying back a pre-mutation snapshot and verified with
  `cmp` (byte-identical) and `grep MUTATION` (0 hits). The `cp` bumped mtimes on four
  files; I restored `BettingMiniGameBot.java` (18:13:27), `RikStockCommit.java`
  (18:07:20) and `RikGameMessageTypes.java` (18:07:53) to their Dev timestamps, and set
  `lombok.config` (no content change; `git status` clean) to its last-commit date. If a
  later agent bounds by mtime, those four are **not** Phase 3 edits of mine.
- The only file I added is
  `bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotCommitLoopSurvivalTest.java`.
  No production code, no `pom.xml`, no other test was touched. Nothing committed.

## Failures (if any)

None.

---

# QA — RIK_114_BETTING_MINI — Phase 2 (the RIK stock bet body)

**Verdict:** PASS
**Build:** `mvn clean install` → **2243 tests, 0 failures, 0 errors** (bot-api 138,
bot-strategies 126, bot-messages 266, bot-engine 452, bot-app 1261). Baseline before my
tests: **2236 / 0 / 0**, which reproduces Dev's reported figure exactly (2213 + 23).

Reviewed against `docs/plans/RIK_114_BETTING_MINI.md` **Amendment A3 and AD-20…AD-27**.
Phases 1 and 1b are shipped and deployed and were **not** re-QA'd — the previous round's
verdict is retained below.

## Scope bounding

`git diff main..HEAD` shows none of this feature; the whole of RIK is uncommitted on
`feature/dead-group-auto-recovery`, which also carries unrelated modified files. Scope
was therefore bound by **mtime**. Phase 2 is the 2026-09-17 13:21–13:27 cluster:

| file | state |
|---|---|
| `bot-messages/…/request/GameRequestFactory.java` | new |
| `bot-messages/…/request/RikStockBet.java` | new |
| `bot-messages/…/request/RikStockRequest.java` | new |
| `bot-messages/…/g3/rik/RikGameMessageTypes.java` | modified (untracked file, Phase 1 origin) |
| `bot-engine/…/core/BettingMiniGameBot.java` | modified (+20 lines, 3 of them code) |
| 5 test files (4 new, `RequestTest` widened, `RikGameMessageTypesRoutingTest` +3 tests) | — |

Everything dated 2026-09-16 or earlier is Phase 1/1b and was left alone. The unrelated
working-tree changes (`Aviator.js`, `bc.js`, `deploy.sh`, `TaiXiuMessages/*`,
`docs/reviews/DEAD_GROUP_AUTO_RECOVERY/*`) were not touched and not assessed.

### Dev's claims about its own work — checked, all true

- **`Request.java` and `Bet.java` not touched at all.** Confirmed two ways: mtime is
  `2026-08-04 16:10:44` on both, and `git diff HEAD --` is empty for both. AD-25 holds.
- **`TaiXiuGameBot.buildRequest` untouched.** The file has a 2026-09-16 15:47 mtime
  (Phase 1b), but `git diff HEAD` on it is **empty** — content identical to the last
  commit, so nothing in Phase 2 moved it.
- **Allowlist of exactly `stockPlugin`, case-insensitive.** Confirmed in source and by
  mutation (below).
- **2236 tests / 0 failures.** Reproduced exactly.
- **The four test files Phase 2 forbids touching** (`MessageTypesCoverageTest`,
  `MessageTypesRegistryTest`, `ApplicationContextLoadsTest`,
  `MessageTypesRegistryStartupLogTest`) all carry pre-Phase-2 mtimes
  (09-15, 09-15, 09-16 12:54, 08-27). `RikGameMessageTypes` still carries exactly one
  `@MessageTypesImpl(gameType = BETTING_MINI, products = "114")`, so no bean was added
  and the boot line V-14 pins is unchanged.

**Note on the first build attempt.** `mvn clean install` failed once in **bot-api** with
`Unable to create test class 'BotManagerExceptionHierarchyTest'` — a surefire *fork*
error, not an assertion: the surefire report for that class records `Tests run: 5,
Failures: 0, Errors: 0`. It is a sibling of the documented `TaiXiu*Test$1`
`NoClassDefFoundError` flake (untouched pre-existing class, passes on re-run) rather than
the same one. Re-ran once; green. Recorded, not investigated, per instruction.

## Tests added / updated

7 new test methods, all deterministic — no network, no Mongo, no clock, no Spring context.

- `bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/RikStockBetCaptureFidelityTest.java`
  — **new, 7 tests.** Dev's `RikStockBetTest` pins the key set as a hand-typed literal,
  which is the right primary assertion but shares an author with the code: a mis-read of
  the capture would make test and code wrong together. This class derives its expectation
  from the committed evidence file
  (`bot-messages/src/test/resources/captures/rik-stockPlugin-13000.jsonl`) instead:
  - the capture really holds **8** outbound `cmd:13002` frames (guards against every
    assertion below being vacuous);
  - our serialized key set equals each captured frame's key set, **frame for frame**;
  - no captured outbound bet carries `b`, and every one carries `iAc` and not `iac` —
    AD-22's evidence asserted rather than quoted;
  - **rebuilding each captured frame from its own values reproduces it node-for-node**;
  - the **production-shaped mapper** produces the same wire keys. Every other assertion
    in this feature uses a bare `new ObjectMapper()`; `BettingMiniGameBot` does not — it
    adds `FAIL_ON_UNKNOWN_PROPERTIES=false` and `registerSubtypes(...)`. Neither affects
    naming, but that was an untested claim about naming, which is exactly the class of
    bug AD-23 is about;
  - the `iAc` field exposes no getter (see the measurement table in that test);
  - a stake above `Integer.MAX_VALUE` is not truncated on the wire (AD-3).

No existing test was weakened or deleted.

### Mutation-verified, not just green

The brief's warning is the right one: this phase's bug class is a frame that *looks*
correct and is silently ignored. Every assertion that matters was therefore checked by
breaking the code and confirming the test goes red. Each mutation was applied to the real
source, run through real surefire, then reverted (all three production files verified
byte-identical afterwards, no `MUTATION` marker left in the tree).

| # | mutation | wire result | killed by |
|---|---|---|---|
| M1 | drop `@JsonProperty` + `@Getter(NONE)` → bare Lombok `isIAc()` | `{"iac":true}` | `RikStockBetTest` 3F+1E, mine 4F |
| M2 | `@Getter(NONE)` + hand-rolled `getIAc()`, no annotation | `{"iac":true}` | `RikStockBetTest` 3F+1E, mine 4F |
| M3 | `@JsonProperty("iAc")` **+ Lombok's** `isIAc()` | `{"iAc":true}` — **correct**, see below | (only my structural test) |
| M3b | `@JsonProperty("iAc")` **+ hand-written** `isIAc()` | `{…,"iac":true,"iAc":true}` — **both keys** | `RikStockBetTest.keySetIsExact` + `.iAcIsNotMangled`, mine 4F |
| M4 | invert the allowlist into a denylist (`!taixiuMd5Plugin`) | txmd5 keeps `Request`, everything else gets the stock body | `RikGameMessageTypesRoutingTest.requestRoutingMatrix` |
| M5 | delete the 3-line `instanceof` branch in `buildRequest` | stock bot silently returns to staking on `b` | `BettingMiniGameBotRikRequestDispatchTest.stockBotBuildsTheStockRequest` |

**M3 did not behave as the brief predicted, and the reason matters.** The brief expected
`@JsonProperty("iAc")` + a Lombok getter to emit **both** `iac` and `iAc`. Measured on
this build it emits a single, correct `iAc`. The cause is the repo-root **`lombok.config`**:

```
config.stopBubbling = true
lombok.copyableAnnotations += com.fasterxml.jackson.annotation.JsonProperty
```

Lombok stamps the field's `@JsonProperty` onto the getter it generates, so Jackson merges
the two into one correctly-named property. Lombok cannot do that for a getter a **human**
writes — which is M3b, and M3b *does* produce the double-key frame the brief describes,
and **is** killed by Dev's own assertions. So the double-key hazard is real, the brief's
mechanism was one step off, and the shipped tests do cover the reachable form of it.

Two consequences, neither of which changes the verdict:

1. **The shipped code is correct under every variant** — with no getter at all, the
   annotated field is the single property regardless of `lombok.config`. Good.
2. **`RikStockBet`'s class javadoc states a wrong reason** — see Findings.

## Coverage of the diff

| production file | test file | what is covered |
|---|---|---|
| `GameRequestFactory.java` | `GameRequestFactoryCapabilityTest` (Dev) | over the **real component scan**: exactly one registered betting-mini provider implements the capability, and it is `RikGameMessageTypes`; the registry is non-empty (non-vacuity); per-game-resolved providers are covered too, so `RikZicZacGameMessageTypes` is pinned as *not* implementing it (AD-24) |
| `RikStockBet.java` | `RikStockBetTest` (Dev), `RikStockBetCaptureFidelityTest` (QA) | exact key set `{cmd,v,sid,aid,eid,iAc}`; no `b`; no `iac`; values; `cmd = offset + 3002` at two offsets; envelope element 0 is `"6"` (AD-26); capture-derived fidelity; production mapper; long stake |
| `RikStockRequest.java` | `RikStockRequestTest` (Dev) | `subscribe()` node-equal **and frame-equal** to `Request.subscribe()`; cmd `offset + 3000`; `bet()` returns `RikStockBet` carrying zone + plugin; standalone — `isNotInstanceOf(Request)` and its bet `isNotInstanceOf(Bet)` (AD-25) |
| `RikGameMessageTypes.requestFor` | `RikGameMessageTypesRoutingTest` (Dev) | the **full matrix in one place**: `stockPlugin`/`StockPlugin`/`STOCKPLUGIN` → `RikStockRequest`; `taixiuMd5Plugin`, `someOtherPlugin`, `"stock"`, `"stockPlugin "` (trailing space), `""`, `null` name and `null` Game → **exactly** `Request`; the two `pluginName` switches asserted disjoint |
| `BettingMiniGameBot.buildRequest` | `BettingMiniGameBotRikRequestDispatchTest` (Dev) | the seam on a **real bot** built the way `BotFactory` builds it: stock → `RikStockRequest` and its frame carries `v`; txmd5 → `Request` with `{cmd,aid,b,eid,sid}` at cmd 7002; un-captured 114 game, ziczac, a non-114 product (BOM) and a **null `messageTypes`** all → `Request` |
| `Request` / `Bet` (frozen) | `RequestTest.bettingMiniBetHasNoAutoBetFlag` (widened) | shared body still exactly `{cmd,aid,b,eid,sid}` and now additionally no `v`, no `iAc`, no `iac` |

The two specific proofs the brief asked for are both present and both mutation-confirmed:
**a denylist implementation fails** (M4 → `requestRoutingMatrix` red, and the un-captured
/ typo'd / empty / null rows are each asserted as `isExactlyInstanceOf(Request.class)`);
and **null `Game` and null `messageTypes` do not NPE** (`requestFor(null, …)` returns a
`Request`; `nullProviderFallsBack` builds a bot with no provider injected). Worth noting
the new branch is strictly *more* null-tolerant than the old code: the pre-existing
fallback dereferences `game.getPluginName()`, so a null `Game` would have NPE'd before
Phase 2 and now does not when the provider is the RIK one.

**txmd5 keeps the shared `Bet`** — the regression the brief called out — is pinned at
three independent levels: the provider (`requestRoutingMatrix`), the engine seam
(`txmd5BotKeepsTheSharedRequest`, which asserts the serialized body is
`{cmd,aid,b,eid,sid}` at cmd 7002, not merely the Java type), and the frozen shared body
(`RequestTest`).

## Findings

**F-1 (minor, Dev's to fix — documentation, not behaviour).** `RikStockBet`'s class
javadoc says:

> The generated getter is therefore suppressed with `@Getter(AccessLevel.NONE)` and the
> annotated private field is the single property — **leaving both in place risks the frame
> carrying `iAc` and `iac`**.

Measured on this build, leaving both in place (M3) yields a **single correct `iAc`**,
because the repo-root `lombok.config` copies `@JsonProperty` onto the generated getter.
The double-key frame needs a *hand-written* getter (M3b). The inner-field comment
(`isIAc()` would serialize a second key, `iac`) is wrong in the same way.

This is flagged rather than waved through because of this feature's own history: AD-5
shipped a confidently-wrong rationale in three places and Amendment A1 exists to remove
it. `@Getter(AccessLevel.NONE)` is still the **right** choice — it makes the body correct
without depending on a config file two directories up that nothing else in this feature
mentions — so the fix is to state that reason instead of the one that is there. I did not
edit `src/main`. The measurement table is recorded in
`RikStockBetCaptureFidelityTest.betDataExposesNoGetterForTheFlag` so it is not lost.

## Gaps

Things the diff changes that these tests do not cover, and why:

- **That a bet frame actually reaches the socket.** Every assertion stops at
  `request.bet(...)` and serializes the body; nothing drives
  `botBehaviorScenario()` through the ws-parser pipeline. That is integration-only and is
  exactly what V-12 measures on staging. Mitigated: `productionMapperProducesTheSameWireKeys`
  closes the one seam between the two (the mapper), and the sole remaining untested link
  is ws-parser's own `ActionRequestMessage.serialize`, which is a shipped library already
  carrying five other products' frames.
- **The whole AD-27 question — whether the bet body is the *only* reason stock reads
  zero.** No test can settle it; the plan already records it as Phase 3, gated on V-12.
  A PASS here means the frame now matches the capture, **not** that bets will settle.
- **The denomination rule (OI-8).** Nothing validates that a stake is one of
  1k/5k/…/50M before sending. Out of scope for Phase 2 (the plan handles it by pinning
  the staging group at `minBet = maxBet = 1000`), and it is a strategy-layer concern.
- **`ZicZacBet` / CODE-3012.** Deliberately out of scope (AD-24, AD-27). The ziczac row
  is pinned *as unchanged*, with a comment telling the next phase to flip it deliberately.
- **`lombok.config` is not pinned by any test.** It now demonstrably governs how
  `@JsonProperty`-on-a-Lombok-field serializes across the codebase. I did **not** add a
  guard: the file is tracked and clean (committed in `de833c6`), `@JsonProperty` on
  fields is pervasive in the pre-existing inbound message classes, and the Phase 2 body
  does not depend on it. Retrofitting a test for it would be out of this diff's scope —
  recorded here so the next person who needs it knows where the mechanism lives.
- **Concurrency / lifecycle.** `RikStockRequest` is immutable and `requestFor` is pure, so
  there is nothing thread-shaped to test; `RikGameMessageTypes` remains a stateless
  singleton and the routing test pins that the ziczac delegate is a shared instance.

## Failures (if any)

None. `mvn clean install` → **2243 tests, 0 failures, 0 errors, BUILD SUCCESS.**

The only red observed during this review was deliberate — the six mutations above, all
reverted. One infrastructure flake on the first run (bot-api surefire fork, see Scope
bounding); green on re-run and on the final build.

---

# QA — RIK_114_BETTING_MINI — Phase 1 / 1b (previous round, retained as the record)

**Verdict:** PASS
**Build:** `mvn clean test` → 2156 tests, 0 failures, 0 errors
(bot-api 138, bot-strategies 126, **bot-messages 203** (was 185), **bot-engine 437** (was
431), bot-app 1252 — unchanged count, one assertion strengthened.)

Reviewed against `docs/plans/RIK_114_BETTING_MINI.md` **Amendment A1 + the corrected
AD-5 first**, then the Phase 1b section; the Phase 1 section was read as a record, not as
instructions. Scope was limited to the RIK work: `g3/rik/` (7 production classes), the
three shipped RIK test classes, the 8 fixtures, the 2 captures, the two inventory edits,
and a light pass over `scripts/capture/`. The unrelated working-tree changes on
`feature/dead-group-auto-recovery` (Aviator.js, bc.js, deploy.sh, TaiXiuMessages/*,
docs/plans/*, docs/reviews/DEAD_GROUP_AUTO_RECOVERY/*) were not touched and not assessed.

### Tests added / updated

24 new test methods, all deterministic, no network, no Mongo, no clock dependence.

- `bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/RikEndGamePayoutSemanticsTest.java`
  — **new, 9 tests.** The money semantics the two captures *cannot* prove, because the
  capturing account was the only bettor in every round and therefore `obs[].v` ==
  `bs[].v` == `mbs[].b` in every real frame we hold. Covers: `wm` as a gross return on a
  real **losing** round (3000 staked → 735 returned, positive and below the stake); the
  winnings/stake ratio over the captured rounds staying in the RTP band and nowhere near
  the two failure signatures V-9 names; `betAmountFor` ignoring a room-wide `obs[].v` of
  900 000 000 against an own stake of 1000; an `obs`-only round reporting **zero** own
  stake rather than the room's; `mbs`-over-`wm` **precedence, never summation**; an empty
  `mbs` falling through rather than zeroing; `betCountFor` counting staked positions on
  both branches; `bc` never becoming the bet count; and money above `Integer.MAX_VALUE`
  surviving on every payout field (AD-3).
- `bot-messages/.../g3/rik/RikCrossGameShapeToleranceTest.java` — **new, 4 tests.** Each
  captured game's **real body** fed to the *other* game's CMD registration (only `cmd` is
  rewritten, because `cmd` is what selects the subtype). Asserts both halves: it parses,
  **and** it does not silently mis-bind — `mbs`/`obs` absent means null, the top-level
  `wm` does not pick up `mbs[].wm`, the `bs` fallback carries the crowd. Plus the floor an
  un-captured 114 game gets: `{cmd, sid}` only → usable `getSessionId()`, empty crowd,
  zero money, no throw.
- `bot-messages/.../g3/rik/RikProviderRegistrationTest.java` — **new, 4 tests.** Under a
  real component scan: `bettingMini("114")` resolves `RikGameMessageTypes` (class
  identity, which neither existing inventory test pins), `taiXiu("114")` still resolves
  `JackpotTaiXiuMessageTypes` (the documented "Tài/Xỉu under both game types" trap, both
  sides), every accessor non-null incl. `startGameMd5Type()` (a null there NPEs at
  `registerSubtypes` — Win79's shape), and CMDs derived from the offset at 10000 / 4000 /
  14000 with `md5` swapping exactly one registration.
- `bot-messages/.../g3/rik/RikFixtureProvenanceTest.java` — **updated, +1 test.** Added
  `everyFixtureOnDiskIsClaimedByExactlyOneCapture`: the class's own two fixture lists were
  the loophole — a fixture not on either list was bound to no capture at all. Now the
  directory is enumerated. The new loss fixture was added to `STOCK_FIXTURES`.
- `bot-messages/src/test/resources/messages/rik/endGame-loss.json` — **new fixture**, the
  verbatim body of captured round `sid 3793247` (`d1 -75`, stake 3000, `wm 735`) pulled
  out of `captures/rik-stockPlugin-13000.jsonl` by script, not by hand. It is the round
  that makes "wm is not profit" a fact rather than a comment, and the provenance test
  binds it.
- `bot-engine/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotRikDispatchTest.java`
  — **new, 6 tests.** The join nobody had for this product: real `RikEndGameMessage`
  instances through `BettingMiniGameBot.onEndGame` against a mocked `BotMetrics`, modelled
  on the existing `BettingMiniGameBotTipDispatchTest`. Asserts `incBotWinnings` /
  `incBetsPlaced` fire with the right numbers on both game shapes, that a losing round
  still increments winnings by 735, that a room-wide `obs[].v` can never reach
  `bot_bet_amount_total`, that a no-bet round increments nothing, that `incBotJackpot`
  **never** fires (AD-19), and that a null `BotMetrics` still reaches `PAYOUT`. Messages
  are constructor-built on purpose: copying the fixtures into `bot-engine` would create a
  second, provenance-unbound copy free to drift.
- `bot-app/src/test/java/com/vingame/bot/ApplicationContextLoadsTest.java` — **updated**,
  one assertion argument: `"114"` added to the betting-mini superset check. The plan says
  this edit is *not required* (superset by design) and that is true; it is also the only
  assertion in the build that would catch a provider reachable from `bot-messages`' bare
  package scan but **not** from `Starter`'s, which is the risk a *newly added* provider
  carries and an established one does not. One string, no exact-set duplication. Called
  out here as a deliberate deviation from the plan's table.

### Mutation-verified, not just green

Every high-value assertion was confirmed load-bearing by temporarily mutating the
production class and re-running. **All production files were restored byte-for-byte
(`git status` on `bot-messages/src/main` clean, `diff` against backups empty) and
`bot-messages` was re-installed from the restored source.**

| Mutation of `RikEndGameMessage` / `RikMainBetSummary` | Caught by |
|---|---|
| `betAmountFor` falls back to `obs[].v` when `mbs`/`bs` absent | `RikEndGamePayoutSemanticsTest.obsOnlyEndGameReportsZeroOwnStake` |
| `betAmountFor` **prefers** `obs[].v` | `BettingMiniGameBotRikDispatchTest.roomWideObsNeverReachesTheStakeCounter` |
| `winningsFor` returns `sum(mbs[].wm) + wm` | `…mbsWinsOverTopLevelWmAndTheTwoAreNeverSummed` |
| `winningsFor` nets the stake (`wm - b`, i.e. treats `wm` as profit) | 7 assertions across both new bot-messages classes |
| `mbs != null` instead of `!mbs.isEmpty()` | `…emptyMbsFallsThroughRatherThanZeroing` |
| `RikMainBetSummary.b` / `.wm` demoted to `int` | `…moneyFieldsDoNotTruncateAtIntegerMaxValue` |

`scripts/capture/infer_schema.py` was run against both committed captures and re-derives
the documented structure (including the `17000`/`18000` `outsideWindow` cmds §4b cites and
the `cH[].tst` long hazard AD-6 disposes of). Light touch only, as briefed — no tests were
written against the tooling.

### Coverage of the diff

| Production file | Test file(s) | What is covered |
|---|---|---|
| `g3/rik/RikEndGameMessage.java` | `RikGameMessageTypesTest`, `RikTaiXiuMd5GameShapeTest`, **`RikEndGamePayoutSemanticsTest`**, **`RikCrossGameShapeToleranceTest`**, **`BettingMiniGameBotRikDispatchTest`** | `getSessionId`; `crowdBets()` `obs`→`bs` fallback, empty and null; `winningsFor` on both sources, precedence, gross-return-not-profit, absence; `betAmountFor` own-stake-only with the room deliberately made distinguishable; `betCountFor` positions-not-clicks on both branches; the jackpot-marker omission; long-width; both shapes under each other's registration; metric dispatch end to end |
| `g3/rik/RikMainBetSummary.java` | `RikGameMessageTypesTest`, **`RikEndGamePayoutSemanticsTest`** | `b`/`wm`/`m` bound from the real frame; multi-entry summation; `long` width |
| `g3/rik/RikBetInfo.java` | `RikGameMessageTypesTest`, `RikTaiXiuMd5GameShapeTest`, **new tests** | serves `bs` and `obs`; `b` absent → 0; `eid` keyed `{0,1}` and `{1,2}`; never indexed by position; `v` above int range |
| `g3/rik/RikSubscribeMessage.java` | `RikGameMessageTypesTest`, `RikTaiXiuMd5GameShapeTest`, **`RikCrossGameShapeToleranceTest`** | `tFB`/`tFD` on both games; `sid`/`gS`/`tFP`/`rmT`/`mB`; crowd; unmodelled `cH`/`htr`/`bH`/`tP` tolerated, now also cross-game |
| `g3/rik/RikUpdateBetMessage.java` | `RikGameMessageTypesTest` | crowd from `bs`; `getGameState() == 0` (AD-13) |
| `g3/rik/RikStartGameMessage.java` / `RikStartGameMd5Message.java` | `RikGameMessageTypesTest`, `RikTaiXiuMd5GameShapeTest`, **`RikProviderRegistrationTest`** | `sid`; `md5=true` selects the Md5 class; stock's literal `"-"` preserved, txmd5's real 64-hex hash; md5 type non-null at registration |
| `g3/rik/RikGameMessageTypes.java` | **`RikProviderRegistrationTest`**, `MessageTypesRegistryTest`, `MessageTypesCoverageTest` | discovery under a real scan, class identity, TAI_XIU table untouched, CMDs derived from the offset at three offsets |
| `MessageTypesCoverageTest` / `MessageTypesRegistryTest` inventory edits | themselves + `ApplicationContextLoadsTest` | `"114"` off the not-yet list (asserted to actually resolve), on the exact set, and present under `Starter`'s own scan |
| `test/resources/captures/*.jsonl`, `messages/rik/*.json` | `RikFixtureProvenanceTest` | per-fixture binding by capture, 50/22 body counts, `7002` absence pinned, and now no unlisted fixture |

### Gaps

- **Phase 2 — the outbound bet body (`v` + `iAc`) is deliberately untested**, because it
  is deliberately unimplemented and gated on **V-6**. Nothing in the suite asserts what
  `Request.bet` emits for 114; the shipped behaviour is the shared `Bet` body (`b`). If
  V-6 shows the server ignores `b`, the group stakes nothing while looking healthy — the
  documented "confirmed staked reads 0" failure. **V-6 is the releaser's gate and no test
  can stand in for it.**
- **OI-4 — `v` vs `b` scope is still not separable by any test.** Both captures have a
  single bettor, so "room total including self" cannot be told from "others only".
  `crowdBets()` reads `v` as room-including-self (cross-product convention). My tests pin
  that `v` is never used as an *own* stake, which is the part that moves money; the
  remaining ambiguity only affects opt-in, currently-off crowd steering.
- **`ps` element type (AD-8)** — only tolerance is tested (a populated synthetic `ps`
  parses). Nothing asserts its meaning, correctly, since it was empty in all three
  captured rounds and its type is unknown.
- **Jackpot markers (AD-19)** — tested as *absent*, on purpose. `tJpV` read 0 in every
  sample and its meaning is inverted between product families, so there is nothing to
  assert beyond the omission, which is now pinned in two places.
- **`getMd5Hash()` has no production consumer** — the md5 path is proven to select the
  right class and carry the right string, but nothing downstream reads it, so no
  behavioural test exists or can exist.
- **Offsets 14000–18000 (OI-2)** — only the degradation floor is tested (a minimal unknown
  frame yields a usable EndGame). Payload fidelity for those games is unproven by
  construction; a capture is required before enabling one, per the plan.
- **Live verification (V-1…V-10) is out of unit-test reach** — that the server answers
  subscribe on 13000/7000, that `bot_messages_total{cmd:startGame}` moves, that RTP lands
  near 0.98. `BettingMiniGameBotRikDispatchTest` proves the app-side half of V-9 (the
  markers reach the counters with the right numbers); the server-side half is the
  releaser's.
- **`Game` record configuration is data, not code** — `gameType: BETTING_MINI` (not
  `TAI_XIU`) for `taixiuMd5Plugin`, `optionAffinities` keyed `{1,2}` vs `{0,1}`,
  `crowdCountSemantic: PLAYERS`. No test can enforce an operator's POST body; the trap is
  documented in `RikProviderRegistrationTest`'s javadoc and in V-10.

### Failures (if any)

None. Two failures occurred while writing these tests and both were defects in **my own
test code**, fixed before the final run and reported here for completeness: an offset/CMD
arithmetic slip (offset 17000 yields CMD 20000; §4b's `17000` is the CMD, i.e. offset
14000), and one Mockito raw-value-with-matcher misuse. No defect was found in the RIK
production code.

**Production code was not modified.** The mutation testing above was performed on
temporary copies that were restored and verified identical; `git status` shows no
modification under any `src/main`.
