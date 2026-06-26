# QA — TAI_XIU_114_JACKPOT

**Verdict:** PASS
**Build:** `mvn -o test` → 941 tests, 0 failures, 0 errors (was 938 on `main`; +3 added here)

## Scope

QA of the P_114 / RIK `taixiuJackpotPlugin` Tai Xiu jackpot variant on branch
`feat/taixiu-114-jackpot` against `docs/plans/TAI_XIU_114_JACKPOT.md`, diff
`main..feat/taixiu-114-jackpot`. Focus on the risky surfaces: 116 regression
from the offset refactor + `GameRequest.bet()` widening, `a`-field isolation,
end-to-end offset correctness, class-reuse against real fixtures, the
`tFBB`-absent behavior, and refund-aware accounting.

## Tests added / updated

- `src/test/java/com/vingame/bot/domain/bot/message/request/RequestTest.java`
  — added two betting-mini regression tests for concerns #1/#2:
  - `overrideReturnsConcreteBet` — the betting-mini `Request.bet()` covariant
    override still narrows the widened `GameRequest.bet()` (`ActionRequestMessage`)
    back to the concrete shared `Bet` (compile-time + runtime proof).
  - `bettingMiniBetHasNoAutoBetFlag` — a betting-mini bet serializes with NO
    `a` key and to exactly `{cmd, aid, b, eid, sid}` (the shared `Bet.BetData`
    is byte-for-byte unchanged; `a` does not leak into non-Tai-Xiu products).
- `src/test/java/com/vingame/bot/domain/bot/core/TaiXiuJackpotGameBotStreamTest.java`
  — added one behavioral test for concern #5:
  - `p114NoTfbbMeansZeroLateBetCutoff` — drives the real `onSubscribe` with the
    114 subscribe fixture (no `tFBB`), asserts `blockBetTime==0`, and that the
    bet gate (`remainingTime >= blockBetTime`) stays open even at
    `remainingTime==0`. Pins the observed behavior as intentional, not a defect.

## Coverage of the diff

The Dev-authored tests already cover the feature thoroughly; the items below map
each risky production file to the test(s) that pin it. New QA tests are flagged
[QA].

- `TaiXiuMessageTypes.java` (offset seam, `cmdOffset()`, `subscribeCmd()/…/betCmd()`,
  `emitsAutoBetFlag()`, offset-aware `getTypeRegistrations()`)
  ← `TaiXiuMessageTypesTest` (P_116 stays offset 0 → 1005/1002/1004/1000;
  default offset 0; a stub offset-100 provider shifts all four cmds + the
  registration names; bet never registered) and `JackpotTaiXiuMessageTypesTest`
  (P_114 provider → 1105/1102/1104/1100, registrations keyed `1105/1102/1104`).
- `JackpotTaiXiuMessageTypes.java` (cmdOffset=100, emitsAutoBetFlag=true, reused
  inbound classes, md5/updateBet null)
  ← `JackpotTaiXiuMessageTypesTest` (provider shape, reuse of the 116 inbound
  classes, all six fixtures round-trip).
- `GameMessageTypesResolver.resolveTaiXiu` (P_114 case added)
  ← `JackpotTaiXiuMessageTypesTest` (`resolveTaiXiu(P_114)` → jackpot provider;
  `resolveTaiXiu(P_116)` still offset-0 + `emitsAutoBetFlag()==false`;
  `resolveTaiXiu(P_066)` still throws "not yet implemented") and
  `TaiXiuMessageTypesTest` (the parameterized "unimplemented" set, now correctly
  excluding P_114).
- `GameRequest.java` (return type widened to `ActionRequestMessage`)
  ← `RequestTest.overrideReturnsConcreteBet` [QA] (covariant override intact) and
  `TaiXiuRequestTest` (`bet()` returns `Bet` for 116, `TaiXiuBet` for 114).
- `TaiXiuBet.java` (new 114 body with `a`)
  ← `TaiXiuRequestTest` (114 bet → `TaiXiuBet`, serializes `{cmd:1100, aid:1, b,
  eid, sid, a:false}`) and `TaiXiuJackpotGameBotStreamTest` (the bot emits a
  `TaiXiuBet` carrying `a:false` with the tracked sid each round).
- `TaiXiuRequest.java` (injected cmds + `emitAutoBetFlag` gate)
  ← `TaiXiuRequestTest` (116: shared `Bet`, no `a`; 114: `TaiXiuBet`, `a:false`).
- `TaiXiuGameBot.java` (provider-resolved cmd seams, `buildRequest` passes
  provider cmds + flag)
  ← `TaiXiuJackpotGameBotStreamTest` (P_114 bot: `subscribeCmd/startGameCmd/
  endGameCmd` == 1105/1102/1104, consumes the 114 inbound frames, bet at 1100;
  P_116 regression bot: 1005/1002/1004, shared `Bet`, no `a`) plus the existing
  116 `TaiXiuGameBotStreamTest`/`Dispatch`/`SingleEntryLock` suites (all green).
- betting-mini `Request.bet()` (shared `Bet`, no `a`)
  ← `RequestTest.bettingMiniBetHasNoAutoBetFlag` [QA].

### Verification of the six focus concerns

1. **116 regression / `bet()` widening — PASS.** P_116 provider stays offset 0
   (1005/1002/1004/1000), the betting-mini bet and the 116 Tai Xiu bet both
   serialize with no `a` (asserted on serialized output, not types). The
   covariant `Request.bet()` override still returns the shared `Bet` — pinned at
   compile and runtime. Existing 116 suites remain green.
2. **`a`-field isolation — PASS.** `a:false` appears only on the 114 path
   (`emitsAutoBetFlag()==true` → `TaiXiuBet`). 116 and the betting-mini path
   assert no `a` key in serialized JSON. The shared `Bet.BetData` is untouched.
3. **Offset correctness end-to-end — PASS.** 114 registrations bind
   1105/1102/1104; the bot's seams resolve to 1105/1102/1104 and it consumes the
   114 StartGame(1102)/EndGame(1104) frames, subscribes at 1105, bets at 1100.
   The 116 regression bot in the same test class shows no cross-contamination.
4. **Reuse against real fixtures — PASS.** The six 114 fixtures (capture-derived
   subscribe/start/full-refund end + bet, plus hand-derived partial/zero refund
   ends) round-trip into the reused `TaiXiuSubscribeMessage`/`TaiXiuStartGameMessage`/
   `TaiXiuEndGameMessage` with all bot-relevant fields correct (tFB=51000,
   sid=5966/5971, gB/gR/G accounting, jpV/iJp, dice). Unknown-field tolerance is
   genuinely exercised against the **resolved provider's** registrations with
   `FAIL_ON_UNKNOWN_PROPERTIES=false` — the same flag the production scenario
   mapper uses (`BettingMiniGameBot.java:650`); verified that `gid` is an
   undeclared field on `TaiXiuStartGameMessage` (no `@JsonIgnoreProperties`
   there), so the tolerance is real and not silently absorbed by a setter.
5. **`tFBB`-absent behavior — PASS (modeled, documented, NOT a defect).** 114's
   subscribe lacks `tFBB`, so `getTimeForDecision()==0` → `blockBetTime==0` →
   the late-bet time cutoff is effectively disabled (gate `remainingTime >= 0`
   is always satisfiable). Pinned both at deserialization
   (`JackpotTaiXiuMessageTypesTest`) and at the bot level via the real
   `onSubscribe` path (`p114NoTfbbMeansZeroLateBetCutoff`). See product-decision
   note below.
6. **Refund-aware accounting — PASS.** Full/partial/zero refund fixtures yield
   winnings=G, effective stake=gB−gR, balance net=−b+gR+G; the stream test pins
   the running balance identity `start − Σb + Σrefund + Σwinnings` and
   `Σ bot_bet_amount_total == Σ(gB − gR)`, with the captured full-refund round
   contributing 0 to both. Live `tJpV` (jackpot pool) is not fed into balance or
   winnings; per-round jackpot stays `iJp ? jpV : 0`.

## Gaps

- **On-server / integration verification** (plan `## Verification` items 1–6:
  app boot, REST create of a P_114 Game/Environment, bots reaching
  CONNECTION_AUTHENTICATED, live cmd 1105/1100/1102/1104 frames, RTP, no
  wedged/116-regressed bots) is staging-only and out of scope for unit/component
  QA. The unit suite gives high confidence those will pass: the +100 subscribe
  matcher and registration both derive from `cmdOffset()` and are pinned
  consistent, which is the classic "wedged bot" failure mode and is covered.
- **`updateBet` offset (+100?)** is unverified because no updateBet frame was
  captured for either product (`updateBetType()==null`, OI-3/OI-5). Not exercised
  by the bot in v1; no test possible without a capture. Carried as an open item.
- **md5 StartGame variant** — none captured for 114 (`startGameMd5Type()==null`),
  same as 116. Not testable until a capture surfaces.

## Product-decision item (not a defect)

- **114 late-bet cutoff is disabled because `tFBB` is absent.** With
  `blockBetTime==0`, the bot will place a bet at any remaining time as long as
  the round is in BET phase and a session is tracked — there is no late-bet
  guard window for 114, unlike 116 (which carries a real `tFBB`). This faithfully
  models the captured 114 subscribe frame and is pinned by
  `p114NoTfbbMeansZeroLateBetCutoff`. If the 114 server rejects bets placed too
  close to the round end, the bot would emit a doomed bet rather than skip it —
  worth confirming against the 114 server on staging (plan OI / Verification #4).
  Flagging for product/ops awareness; not a code defect against the plan, which
  explicitly chooses to model this state faithfully (AD-9 / OI handling).

## Failures (if any)

None.
