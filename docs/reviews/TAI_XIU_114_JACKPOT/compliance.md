# Compliance — TAI_XIU_114_JACKPOT

Branch: feat/taixiu-114-jackpot
Plan reviewed: `docs/plans/TAI_XIU_114_JACKPOT.md`
Diff reviewed: `git diff main..feat/taixiu-114-jackpot`

## Verdict

PASS

## Phase-by-phase

### Phase 1 — Offset-aware CMDs on `TaiXiuMessageTypes` (AD-1, AD-4, AD-7)
Status: implemented

- `TaiXiuMessageTypes` keeps the four base literals renamed to `*_CMD_BASE`
  (1005/1002/1004/1000), adds `default int cmdOffset() { return 0; }`, and the four
  instance accessors `subscribeCmd()/startGameCmd()/endGameCmd()/betCmd() = base + cmdOffset()`
  exactly as AD-1 specifies. `getTypeRegistrations()` now registers against
  `String.valueOf(subscribeCmd()/startGameCmd()/endGameCmd())`.
- The three consumers are all refactored off the static constants:
  1. `TaiXiuGameBot.subscribeCmd()/startGameCmd()/endGameCmd()` delegate to
     `taiXiuMessageTypes.*Cmd()` (AD-7); `updateBetCmd()` retained as the `-1` sentinel.
  2. `TaiXiuRequest` takes the resolved `subscribeCmd`/`betCmd` ints at construction
     (AD-4); `buildRequest()` passes `provider.subscribeCmd()`/`provider.betCmd()`.
  3. `getTypeRegistrations()` itself derives from the instance accessors.
- 116 byte-for-byte unchanged: `Bet.java` is untouched (verified empty diff); the
  shared `Bet.BetData` is intact; the offset-0 path produces the same CMDs and bet body.
  Confirmed green by the pre-existing 116 suites (`TaiXiuMessageTypesTest`,
  `TaiXiuGameBotStreamTest`, `TaiXiuGameBotDispatchTest`,
  `TaiXiuMessageDeserializationTest`, `TaiXiuRequestTest`, single-entry-lock test).
  `TaiXiuMessageTypesTest` was updated to assert the 116 provider instance returns
  1005/1002/1004/1000 and `cmdOffset()==0`.

### Phase 2 — 114 provider impl `JackpotTaiXiuMessageTypes` (AD-5)
Status: implemented

- New `taixiu/JackpotTaiXiuMessageTypes implements TaiXiuMessageTypes`, overrides
  `cmdOffset()` → `100`, yielding 1105/1102/1104/1100. Returns the same concrete classes
  as `MiniGameTaiXiuMessageTypes` (`TaiXiuSubscribeMessage`/`TaiXiuStartGameMessage`/
  `TaiXiuEndGameMessage`); `startGameMd5Type()`/`updateBetType()` → `null`.
- `JackpotTaiXiuMessageTypesTest` asserts `cmdOffset()==100` and the registrations
  resolve to `"1105"/"1102"/"1104"`.

### Phase 3 — Outbound bet `a:false` (AD-2, AD-3)
Status: implemented

- New `TaiXiuBet extends ActionRequestMessage` with a nested `BetData extends Body`
  carrying `{cmd, aid:1, b, eid, sid, a}`. The shared `Bet.BetData` is NOT touched
  (verified). `a` is gated by the provider flag `emitsAutoBetFlag()` (default `false`;
  114 overrides to `true`), threaded through `TaiXiuRequest.emitAutoBetFlag`. 116 and
  every betting-mini product keep building the shared `Bet` with no `a`.
- `GameRequest.bet()` return type was widened from `Bet` to the common
  `ActionRequestMessage` supertype so a product can return a product-specific body. This
  is a covariant interface change confined to the request layer — it is the cleanest way
  to honor AD-2's "isolate `a` from the shared body" and is consistent with the plan's
  intent. Not a drift.
- `TaiXiuRequestTest` / `RequestTest` assert 116 bet = `{cmd:1000, aid:1, b, eid, sid}`
  (no `a`) and 114 bet = `{cmd:1100, aid:1, b, eid, sid, a:false}`.

### Phase 4 — 114 fixtures (`src/test/resources/messages/taixiu/jackpot/`)
Status: implemented

- `subscribe.json`, `startGame.json`, `endGame_fullRefund.json`,
  `endGame_partialRefund.json`, `endGame_noRefund.json`, `bet.json` all present.
- Verified against the live captures in `TaiXiuMessages/`:
  - `subscribe.json` is the inner body of `SubscribeResponse.js` (cmd 1105, `tFB:51000`,
    `sid:5966`, `gS:3`, `rmT:12460`, `odE:-1`, plus 114 extras `tJpV`, `htr`). It omits
    the non-bot-relevant `cH`/`gi`/`tP` arrays — consistent with the plan's "model only
    the inner body, bot-relevant fields" approach. Notably the capture has **no `tFBB`**;
    the fixture correctly omits it too.
  - `startGame.json` byte-matches `Start.js` (`gid:"102"`, `iES`, `cmd:1102`, `sid`; no `odE`).
  - `endGame_fullRefund.json` byte-matches `End.js` (`gB=gR=GX=500000, G=0`, `sd`, live
    `tJpV`, `jpV`, `iJp`).
  - `bet.json` byte-matches `Bet.js` (`{cmd:1100, b, aid:1, eid:1, sid, a:false}`).
  - `endGame_partialRefund.json` (`gB=500000, gR=200000, G=120000`) and
    `endGame_noRefund.json` (`gR=0, G=80000`) are the hand-derived AD-9 (b)/(c) cases.

### Phase 5 — Resolver wiring + provider/deserialization tests (AD-6)
Status: implemented

- `GameMessageTypesResolver.resolveTaiXiu` moves `P_114` out of the throwing arm into
  `case P_114 -> new JackpotTaiXiuMessageTypes();`; the remaining products still throw.
- Inbound classes are REUSED, not recreated — no new subscribe/start/end classes exist
  (confirmed: the only new message-layer files are `JackpotTaiXiuMessageTypes` and the
  outbound `TaiXiuBet`). `JackpotTaiXiuMessageTypesTest` round-trips every Phase-4
  inbound fixture through the provider registrations and asserts the refund formulas
  (full/partial/no-refund) and that an unimplemented product still throws.

### Phase 6 — 114 dispatch/stream test + 116 regression guard
Status: implemented

- `TaiXiuJackpotGameBotStreamTest` wires the bot via `resolveTaiXiu(P_114)`, asserts the
  outbound subscribe/bet use +100 CMDs (1105/1100), the inbound matcher consumed the
  +100 StartGame/EndGame (1102/1104), the bet body is a `TaiXiuBet` carrying `a:false`,
  and refund-aware balance/stake accounting holds (full-refund round contributes 0).
- Regression guard: a sibling 116 bot still uses 1005/1002/1004/1000 and emits a `Bet`
  with no `a`.

## Resolved design decisions — confirmed

- Reuse-not-recreate for inbound classes: confirmed. The 114 provider returns the same
  `TaiXiuSubscribeMessage`/`TaiXiuStartGameMessage`/`TaiXiuEndGameMessage`; no
  114-specific inbound class was added.
- `a` isolated from the shared bet body: confirmed. `Bet.java` is byte-for-byte
  unchanged; `a` lives only on `TaiXiuBet` and is gated by `emitsAutoBetFlag()`.
- +100 CMD offset on all four frames: confirmed against the captures and asserted
  end-to-end (1105/1102/1104/1100).

## Drift

None. (`GameRequest.bet()` return-type widening is in-scope and is the mechanism AD-2
requires; not drift.)

## Out-of-scope changes

None in production code. The diff also adds `docs/reviews/TAI_XIU_114_JACKPOT/qa.md`
(QA's deliverable) and extends `RequestTest` with an `AutoBetTests` group that pins the
116-no-`a` isolation — both supportive of the plan, not out-of-scope feature work.

## Dev-flagged item — 114 subscribe has no `tFBB` (late-bet cutoff disabled)

Judgment: faithful modeling; **no plan amendment warranted.**

- The live capture `TaiXiuMessages/SubscribeResponse.js` indeed contains no `tFBB`
  (verified). `TaiXiuSubscribeMessage.getTimeForDecision()` returns the `tFBB` field,
  which defaults to `0` when absent; `BettingMiniGameBot.onSubscribe` assigns that to
  `blockBetTime`, so the late-bet time cutoff (`remainingTime >= blockBetTime`) is
  effectively disabled for 114. This is exactly what the captured frame implies — it is
  faithful modeling, not a defect.
- The plan was **technically correct** on this point: line 88 already states "`tFBB`
  absent → defaults 0" and reuses `TaiXiuSubscribeMessage` deliberately. The behavioral
  consequence (cutoff disabled) is the natural, correct outcome of the plan's design, not
  a contradiction of any assumption Architect-1 made. PLAN_AMENDED requires a falsifiable
  technical mistake in the plan; there is none here. The dev additionally documented and
  tested the behavior (`JackpotTaiXiuMessageTypesTest` asserts `getTimeForDecision()==0`;
  `TaiXiuJackpotGameBotStreamTest` asserts `blockBetTime==0` and that the gate stays open
  at `remainingTime==0`; QA documented it as a known, intended characteristic).
- Optional, non-blocking suggestion for a future plan touch-up (not an amendment): the
  plan could spell out the behavioral consequence ("114 has no late-bet cutoff") under
  Implementation Notes for operator awareness. This is enrichment of a correct plan, so I
  am not editing the plan.

## Build verification

`JAVA_HOME=...openjdk-21.0.2 mvn -o test -Dtest='TaiXiu*,JackpotTaiXiuMessageTypesTest,RequestTest,TaiXiuRequestTest'`
→ BUILD SUCCESS, 94 tests, 0 failures/errors (full 116 suite + new 114 suites green).
