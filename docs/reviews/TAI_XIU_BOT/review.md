# Code Review — TAI_XIU_BOT

Branch: feat/tai-xiu-bot
Reviewed diff: `git diff main..feat/tai-xiu-bot`

## Verdict

PASS

No `bug` or `security` findings. The findings below are all advisory `smell`/`style`
items. The Phase-1 seam refactor is behavior-preserving, the refund-aware accounting
is correct against the documented formulas, and the request/provider abstractions are
clean and free of duplication.

## Findings

### [smell] `md5=true` Tai Xiu config would NPE the scenario at build time
`src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:625,639`

`startGameClass = game.isMd5() ? startGameMd5Type() : startGameType();` is consumed
by `.onMessage(startGameClass, ...)` with no null guard, unlike `updateBetClass`
(which got an explicit `if (updateBetClass != null)` guard at line 645). For Tai Xiu,
`MiniGameTaiXiuMessageTypes.startGameMd5Type()` returns `null` (no md5 variant
captured). If a `TAI_XIU` `Game` is ever persisted with `md5=true`, `startGameClass`
is `null` and the scenario builder is handed a null class — almost certainly an NPE
or silent non-registration at `botBehaviorScenario()`/`onStart()`.

This is not reachable today via the happy path (the captured product is non-md5 and
nobody sets the flag), so it is a latent trap rather than a live bug — hence `smell`,
not `bug`. Two clean fix shapes: (a) mirror the `updateBet` treatment and null-guard
`startGameClass` (fall back to `startGameType()` when the md5 type is null), or
(b) have `TaiXiuConfigValidator`/`BettingGridRules` reject `md5=true` for game types
whose provider has no md5 type. Option (a) is the smaller, more general fix and keeps
the asymmetry with the already-guarded `updateBet` from being a surprise.

### [smell] `resolveTaiXiu` hard-wires the captured product to `P_116` on an unverified assumption
`src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypesResolver.java` (`resolveTaiXiu`)

The Javadoc is admirably honest: "The captured frames do not pin a brand
`ProductCode`; the impl is wired to `P_116` ... Revisit once the captured brand is
confirmed." This means a `TAI_XIU` game on any of the other nine product codes throws
"not yet implemented", and a `TAI_XIU` game on `P_116` silently gets the
`MiniGame`/`taixiuPlugin` classes even if that product's real Tai Xiu wire format
differs. The behavior is intentional and documented, but the binding rests on an
unconfirmed assumption (OI-1/OI-6 are still open in the plan). Worth a follow-up
ticket so the `P_116` guess does not quietly become load-bearing. No code change
required for this review.

### [smell] `balanceCreditFor` net-credit identity silently depends on `b == gB`
`src/main/java/com/vingame/bot/domain/bot/core/TaiXiuGameBot.java:157-164`

Local balance is debited by the bot's own chosen `amount` in `bet()`
(`creditBalance(amount)` → `expectedCurrentBalance -= amount`), then credited back at
end by `gR + winnings`. The documented net identity `−b + gR + G` only holds when the
amount the bot debited (`b`) equals the server-reported gold bet total `gB`. For the
single-bet-per-round model that is true, but nothing in the code enforces or asserts
it — if a round ever places multiple bets or the server reports a `gB` that differs
from the locally tracked stake, the running balance drifts (the metric path is
unaffected because it uses `gB − gR` directly). This is a correctness assumption worth
a one-line comment at the credit site, or an eventual reconciliation against
`checkBalance()`. Not a live bug for the current single-bet flow.

### [style] Single-uppercase-letter field names `G`, `GX`, `CX`
`src/main/java/com/vingame/bot/domain/bot/message/taixiu/TaiXiuEndGameMessage.java:72-79`

Fields `G`, `GX`, `CX` (and the lower-second-char `gB`/`gR`/`cB`/`cBB`/`cR`) violate
standard Java field naming and produce the slightly unusual Lombok getters
`getG()`/`getGX()`/`getGR()`. This is dictated by the JSON wire format and is fully
isolated to one message class with thorough Javadoc, so it is acceptable — flagging
only because it is a deliberate deviation from the surrounding style. No change needed;
the alternative (renamed fields + `@JsonProperty`) would arguably be less faithful to
the captured payload.

## Notes

- The Phase-1 seam extraction in `BettingMiniGameBot` is well done. The six CMD-
  derivation seams plus the two EndGame seams (`endGameSessionId`, `balanceCreditFor`)
  all have defaults that reproduce the prior inline expressions byte-for-byte
  (`SUBSCRIBE_CODE + offset`, `new Request(pluginName, zoneName, offset)`,
  `msg.getSessionId()`, `0L`). The `balanceCreditFor` credit is gated on
  `balanceCredit != 0L`, so BettingMini's `expectedCurrentBalance` is provably
  untouched. Encapsulation is preserved: only `offset` was widened (`private` →
  `protected`) and it carries a comment stating the fixed-CMD subclass never reads it;
  the handlers stayed `private`.

- `GameRequest` is a tidy two-method abstraction that lets `Request` and
  `TaiXiuRequest` plug into the inherited scenario interchangeably, and `TaiXiuRequest`
  correctly reuses `Bet`/`Bet.BetData` rather than duplicating the bet body — exactly
  the reuse the plan called for.

- The `-1` sentinel for `updateBetCmd()` is handled safely: it is only ever fed to the
  OutputPrinter cmd list (cosmetic), never registered as a subtype and never matched,
  because `updateBetType()` returns `null` and the scenario's `if (updateBetClass !=
  null)` guard skips the handler. No accidental dispatch, no NPE on that path. The
  null `updateBetType()`/`startGameMd5Type()` accessors are correct for the
  updateBet/non-md5 omission (the md5 path is the separate `smell` above).

- The refund-aware accounting matches the plan and the captured fixtures: winnings =
  `G` directly (not `GX − gB`), effective wagered = `gB − gR`, both floored at 0, and
  `betCountFor` returns 1 only when effective stake > 0. The deserialization test
  explicitly asserts `winnings != max(0, GX − gB)` for the partial-refund fixture,
  pinning the OI-7 correction.

- `TaiXiuEndGameMessage.getSessionId()` returning `0` is safe because the bot routes
  EndGame correlation through the overridden `endGameSessionId()` seam
  (`sidStore.get()`), which is still populated from the StartGame the bot tracked.

- The `BettingGridRules` extraction is a clean dedup — `BettingMiniConfigValidator` and
  `TaiXiuConfigValidator` are now thin `supportedType()` + delegation, the rule list
  lives in exactly one place, and the boot completeness invariant is preserved.

- No logging-guideline issues: no token logging in the diff, all log calls use the
  SLF4J `{}` form, and the new bot reuses the inherited MDC-wrapped scenario so per-bot
  lines stay tagged. No resource-leak or new-executor concerns — `TaiXiuGameBot` adds
  no schedulers/clients of its own.
