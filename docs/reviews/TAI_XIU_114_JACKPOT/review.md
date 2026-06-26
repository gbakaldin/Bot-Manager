# Code Review — TAI_XIU_114_JACKPOT

Branch: feat/taixiu-114-jackpot
Reviewed diff: `git diff main..feat/taixiu-114-jackpot`

## Verdict

PASS

PASS = no `bug` or `security` findings; the smells/styles below are advisory.

## Findings

### [smell] Bet-body shape duplicated between `Bet.BetData` and `TaiXiuBet.BetData`
`src/main/java/com/vingame/bot/domain/bot/message/request/TaiXiuBet.java:43`

`TaiXiuBet.BetData` is a hand-copied clone of `Bet.BetData` (`aid=1`, `b`, `eid`,
`sid`) with one extra field (`a`). The copy is *deliberate* and well-justified in
the javadoc — adding `a` to the shared `Bet.BetData` would change the serialized
body of every betting-mini product, which the team explicitly wants to avoid
(byte-for-byte 116 stability). So the duplication is the right call given the
constraint. The residual smell is that the shared `{aid, b, eid, sid}` shape now
lives in two places: a future field change to the common shape (e.g. a new shared
key) must be made in both, with no compile-time link between them. This is
acceptable for a two-field, two-site duplication, but worth a comment cross-link
or, longer term, extracting a shared base `BetData` (with `aid/b/eid/sid`) that
`TaiXiuBet.BetData` extends to add `a`. Not blocking — flagging so the coupling is
a conscious choice rather than an accident. The `emitsAutoBetFlag()` provider seam
that selects between the two bodies is the correct place for this decision: it
keeps the body choice with the product definition (where the `cmdOffset()` and the
inbound classes also live) rather than scattering `instanceof`/offset checks into
the request builder.

### [smell] `@Setter` on `final` fields in `TaiXiuBet.BetData` (carried-over pattern, no-op)
`src/main/java/com/vingame/bot/domain/bot/message/request/TaiXiuBet.java:42`

`@Getter @Setter` annotate a class whose fields are all `final`; Lombok silently
generates no setters for `final` fields, so `@Setter` is dead/misleading here. This
is *not introduced* by this branch — it mirrors the identical pattern already in
`Bet.BetData` (`src/.../request/Bet.java:20`), so the new code is consistent with
the codebase. Flagging only because the new class faithfully copies a pre-existing
smell; if you ever clean up `Bet.BetData`, do the same here. No runtime effect.

### [style] Magic literal `100` for the CMD offset
`src/main/java/com/vingame/bot/domain/bot/message/taixiu/JackpotTaiXiuMessageTypes.java:50`

`cmdOffset()` returns the bare literal `100`. It is exhaustively documented in the
method javadoc and the class header (base 1005/1002/1004/1000 +100 →
1105/1102/1104/1100), and the value lives in exactly one place by design, so this
is purely cosmetic. If a third offset product ever appears, consider a named
constant (e.g. `JACKPOT_CMD_OFFSET`) for symmetry with the `*_CMD_BASE` constants
on the interface. Lowest priority.

## Notes

Several things this branch does *well*, worth calling out so they aren't
accidentally regressed later:

- **`GameRequest.bet()` widening to `ActionRequestMessage` does not leak.** The
  sole caller (`BettingMiniGameBot.java:587`) already assigns into a
  `Supplier<ActionRequestMessage>` (`BettingMiniGameBot.java:552`), so the widened
  return type matches the consumer exactly — nothing downstream depended on the
  concrete `Bet`. `MartingaleStrategySupport.java:214`'s `.bet()` is an unrelated
  record/strategy accessor, not this method. The abstraction is at the right level.

- **The covariant override on `Request.bet()` is correct and required no edit.**
  `Request` (`request/Request.java:23`) still declares `public Bet bet(...)`, which
  remains a valid covariant override of `GameRequest.bet(): ActionRequestMessage`
  because `Bet <: ActionRequestMessage`. Betting-mini callers that hold a concrete
  `Request` keep the narrower `Bet` return — no information lost where it's not
  needed, polymorphism available where it is. Clean.

- **The offset seam is clean.** `cmdOffset()` defaults to `0` with the four derived
  `*Cmd()` methods (`subscribeCmd/startGameCmd/endGameCmd/betCmd`) built as
  `*_CMD_BASE + cmdOffset()`. `getTypeRegistrations()` and all five
  `TaiXiuGameBot` CMD seams now read the derived methods, so P_116 is byte-for-byte
  unchanged at offset 0 and P_114 shifts uniformly. The `*_BASE` rename is fully
  applied — `grep` finds **zero** stale `SUBSCRIBE_CMD`/`START_GAME_CMD`/
  `END_GAME_CMD`/`BET_CMD` references for Tai Xiu in production or tests (the
  remaining hits are all `SlotMessageTypes`, an unrelated interface). The `*_BASE`
  naming reads clearly against the derived `*Cmd()` methods.

- **Inbound-class reuse across the two providers is safe.** `JackpotTaiXiuMessageTypes`
  returns the same concrete `TaiXiuSubscribeMessage`/`TaiXiuStartGameMessage`/
  `TaiXiuEndGameMessage` as `MiniGameTaiXiuMessageTypes`. The 114 frames carry extra
  keys (`gid`, `sd`, `tJpV`, `htr` items) but every reused class is annotated
  `@JsonIgnoreProperties(ignoreUnknown=true)` and the scenario mapper sets
  `FAIL_ON_UNKNOWN_PROPERTIES=false` (`BettingMiniGameBot.java:650`), so the extras
  are tolerated. The 114 `startGame.json` omits `odE`; the `@JsonCreator` defaults
  the primitive `double` to `0.0`, and `odE` is not consumed by the bot, so this is
  harmless. The 114 EndGame carries no `sid`, but Tai Xiu already correlates EndGame
  via `sidStore` (the `endGameSessionId` seam) rather than the payload, so the
  reuse holds for the jackpot variant too. The two providers are cleanly parallel —
  the only deltas are `cmdOffset()` and `emitsAutoBetFlag()`.

- **`TaiXiuBet` constructor arg-threading is correct.** `(cmd, zoneName, pluginName,
  bet, entryId, sessionId, autoBet)` → `new BetData(cmd, bet, entryId, sessionId,
  autoBet)` lines up positionally with `BetData(int cmd, long b, long eid, long sid,
  boolean a)`. Verified field-by-field against `TaiXiuRequestTest`'s serialization
  assertions (`cmd:1100, aid:1, b, eid, sid, a:false`).

- **Resolver exhaustiveness preserved.** P_114 is cleanly lifted out of the
  not-implemented group into its own `case`; the switch has no `default`, so the
  enum stays compiler-checked for completeness.

No concurrency, resource-leak, logging, null-safety, bootstrap-order, or
exception-handling concerns surfaced — this change is confined to the stateless
message/request layer (POJOs, providers, builders) with no threading, I/O, or
lifecycle touchpoints.
