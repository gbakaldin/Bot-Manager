# Compliance — TAI_XIU_BOT

Branch: `feat/tai-xiu-bot`
Plan reviewed: `docs/plans/TAI_XIU_BOT.md` (at branch HEAD `88af136`)
Diff reviewed: `git diff main..feat/tai-xiu-bot`

## Verdict

PASS

The diff faithfully implements every phase of the plan, honors all Architecture
Decisions, and reflects every resolved Open Item in code. The full test suite is
green (875 tests, 0 failures, 0 errors) under the required JDK 21. All residual
deferrals (OI-5 updateBet, OI-6 aid, AD-10 md5, and the P_116/TIP-only
ProductCode placeholder) are documented honestly in both the plan and the code.

## Phase-by-phase

### Phase 1 — Extract CMD-derivation seams in `BettingMiniGameBot`
Status: implemented
- Protected seams added with `CODE + offset` defaults: `subscribeCmd()`,
  `updateBetCmd()`, `startGameCmd()`, `endGameCmd()`, `messageTypeRegistrations()`,
  `buildRequest(Game)`, plus the concrete-class accessor seams
  (`subscribeType`/`startGameType`/`startGameMd5Type`/`updateBetType`/`endGameType`).
- `botBehaviorScenario()`, `initializeSubclass()`, and the `onStart()` cmd list all
  rewritten to call the seams. Defaults reproduce the original inline expressions
  byte-for-byte.
- `offset` relaxed `private → protected`; `request` field generalized to the new
  `GameRequest` interface.
- Behavior-preservation contract met: the entire existing betting-mini suite stays
  green (`BettingMiniGameBotTipDispatchTest`, `RequestTest`, all
  `*GameMessageTypesTest`).
- Two seams beyond the literal AD-2 list were folded in here — `endGameSessionId()`
  (default `msg.getSessionId()`) and `balanceCreditFor()` (default `0`). Both
  defaults leave BettingMini unchanged and are required by AD-11/#3. This is a
  faithful extension of the seam pattern, not drift.
- The `updateBetClass != null` guard added to the scenario is behavior-neutral for
  BettingMini (which always returns a non-null updateBet class) and enables the
  Tai Xiu v1 omission (OI-5).

### Phase 2 — Tai Xiu message classes (per-product) + fixtures
Status: implemented
- `TaiXiuSubscribeMessage` models the **rich inbound subscribe RESPONSE**
  (`SubscribeResponse.js`): `tFB`→`getTimeForBetting()`, `tFBB`→`getTimeForDecision()`,
  plus `sid`/`gS`/`rmT`/`odE`/`iES`. Countdown source is `tFB` exactly as the
  resolved OI-5 note requires — not the bare `{"cmd":1005}` request.
- `TaiXiuStartGameMessage` (cmd 1002) exposes `sid` via `getSessionId()`.
- `TaiXiuEndGameMessage` (cmd 1004) implements `HasBotWinnings`, `HasBetTotals`,
  `HasJackpot` with the refund-aware formulas (see AD-11 below) and the dice
  `d1/d2/d3`; the `c` animation array is ignored.
- Fixtures present and derived from the captures: `subscribe.json`, `startGame.json`,
  `bet.json`, `endGame_fullRefund.json` (the captured `End.js` inner payload
  verbatim), `endGame_partialRefund.json`, `endGame_noRefund.json`. `updateBet.json`
  and `startGameMd5.json` correctly omitted (OI-5 / AD-10).
- All three EndGame fixtures satisfy the `GX = gR + G` invariant.
- `TaiXiuMessageDeserializationTest` (7 tests) pins the refund formulas. Green.

### Phase 3 — `TaiXiuMessageTypes` provider + per-product impl + resolver
Status: implemented
- `TaiXiuMessageTypes` interface with fixed `SUBSCRIBE_CMD=1005`,
  `START_GAME_CMD=1002`, `END_GAME_CMD=1004`, `BET_CMD=1000`; no `UPDATE_BET_CMD`
  (OI-5). Does NOT extend `GameMessageTypes` (no offset arithmetic). No-arg
  `getTypeRegistrations()` registers the three inbound types against literal CMD
  strings; bet (1000) is outbound-only and not registered.
- `MiniGameTaiXiuMessageTypes` returns the captured product's classes;
  `startGameMd5Type()`/`updateBetType()` return null (AD-10 / OI-5).
- `GameMessageTypesResolver.resolveTaiXiu(ProductCode)` mirrors
  `resolveBettingMini`: implemented product returns the impl, the rest throw the
  same "not yet implemented for product code" `IllegalArgumentException`.
- `TaiXiuMessageTypesTest` (14 tests) green.

### Phase 4 — `TaiXiuGameBot`
Status: implemented
- Subclasses `BettingMiniGameBot`; overrides only the CMD seams (1005/1002/1004,
  updateBet sentinel `-1`), `messageTypeRegistrations()`, the concrete-class
  accessors, `buildRequest()` (returns `TaiXiuRequest`), `endGameSessionId()`
  (reads `sidStore`, since the EndGame frame has no `sid` — #3), and
  `balanceCreditFor()` (returns `gR + winnings`).
- `TaiXiuRequest` emits bare fixed CMDs (subscribe 1005, bet 1000) reusing
  `Bet.BetData`'s `{cmd,aid,b,eid,sid}` shape (AD-12); a new `GameRequest`
  interface lets either request plug into the inherited scenario.
- **AD-11 refund-aware accounting verified end to end:** winnings = `G`
  (`winningsFor` → `Math.max(0, G)`), effective wagered = `gB − gR`
  (`betAmountFor`), balance net per round = `−b + gR + G` (`balanceCreditFor`).
- `TaiXiuGameBotDispatchTest` (5) and `TaiXiuGameBotSessionCorrelationTest` (3)
  green, covering full/partial/zero refund and the missing-`sid` correlation.

### Phase 5 — `BotFactory` wiring + zone resolution
Status: implemented
- `BotFactory` `case TAI_XIU` builds a `TaiXiuGameBot`, wires
  `resolveTaiXiu(env.getProductCode())` and the betting `strategyFactory`;
  `CARD_GAME, UP_DOWN` left throwing (AD-5).
- `Environment.resolveZoneName` adds `TAI_XIU` to the mini branch (AD-7 / OI-3).
- `EnvironmentZoneResolutionTest` updated (TAI_XIU moved to mini case);
  `BotFactoryTaiXiuWiringTest` confirms a TAI_XIU game yields a `TaiXiuGameBot`
  without throwing. Green.

### Phase 6 — Strategy listing + config validation
Status: implemented
- `StrategyController.list` returns the betting strategy list for `TAI_XIU`
  (AD-6 / OI-4); `StrategyControllerTest` updated.
- `TaiXiuConfigValidator` promoted from no-op to the STRICT-GRID rules via a new
  shared `BettingGridRules.validate(group)` helper; `BettingMiniConfigValidator`
  now delegates to the same helper (AD-8). The rule body was moved verbatim — no
  semantic change to BettingMini validation.
- `TaiXiuConfigValidatorTest` (11) green; `GameConfigValidatorFactory` boot
  completeness invariant still satisfied (full suite green).

### Phase 7 — Deterministic round-stream test
Status: implemented
- `TaiXiuGameBotStreamTest` drives a mixed full/partial/zero-refund sequence and
  asserts the refund-aware identity (balance = `start − Σb + Σgr + ΣG`,
  `bot_bet_amount_total = Σ(gB − gR)`), per-round bets, and the fixed CMDs
  (1005/1002/1004 subscribe matcher, bet 1000).
- Negative AD-9 assertion present: the `Game` is a Mockito spy seeded with a poison
  offset (`999_999`) and the operational stream never consults `getOffset()`; the
  fixed-CMD seam assertions would fail if offset leaked.

## Resolved-open-item confirmation in code

- **OI-3 (mini zone):** `Environment.resolveZoneName` treats TAI_XIU as mini.
  Confirmed.
- **OI-4 (betting strategies):** `StrategyController.list` and `BotFactory` both
  reuse the betting strategy family for TAI_XIU. Confirmed.
- **OI-7 (winnings = `G`):** `TaiXiuEndGameMessage.winningsFor` returns `G`
  directly (commit `8dee2c8` corrected an earlier `GX − gB`); `GX` is modeled but
  unused for winnings. Confirmed.
- **Countdown source (`tFB`):** `TaiXiuSubscribeMessage.getTimeForBetting()` returns
  `tFB` from the inbound subscribe response. Confirmed.

## Residual deferrals (documented honestly)

- **OI-5 (updateBet cmd unknown):** updateBet omitted in v1 — no registration, no
  fixture, `updateBetType()` null, `updateBetCmd()` sentinel `-1`. Documented in
  plan and code.
- **OI-6 (`aid` meaning):** `aid` defaults to `1` via the reused `Bet.BetData`.
  Documented.
- **AD-10 (md5):** no md5 StartGame captured; `startGameMd5Type()` returns null and
  is never dereferenced (registrations omit md5). A TAI_XIU game configured with
  `md5=true` on this product would NPE on `startGameMd5Type()`, but the plan
  explicitly scopes the captured product as non-md5; this is an honest, documented
  v1 limitation, not drift.
- **P_116/TIP ProductCode placeholder:** the captured frames do not pin a brand, so
  `resolveTaiXiu` wires the captured product to `P_116` as a stand-in. Both the plan
  and the resolver Javadoc flag this as provisional ("revisit once the captured
  brand is confirmed"). Honest deferral.

## Out-of-scope changes

None of concern. The diff also adds `docs/reviews/TAI_XIU_BOT/qa.md` (a sibling
review artifact, not production code) and introduces the `GameRequest` interface +
`Request implements GameRequest` change. `GameRequest` is a justified
implementation detail enabling AD-12's `buildRequest()` seam to return either
request shape; it does not alter BettingMini behavior and is covered by the green
betting-mini suite. Not flagged as drift.

## Drift

None. Where the implementation went beyond the literal plan text (the `GameRequest`
interface, the `endGameSessionId`/`balanceCreditFor` seams), the additions are
behavior-preserving for BettingMini and required to realize AD-11/AD-12/#3. These
are faithful elaborations of the plan, not deviations requiring a send-back.

## Amendments to the plan

None.
