# TAI_XIU_BOT

## Goal

Add a `TAI_XIU` game type to the bot-manager. Tai Xiu is a round-based betting
game **behaviorally identical** to the existing `BettingMiniGameBot`: shared
server clock, `subscribe → StartGame(sid) → BET phase with countdown →
EndGame(sid)`, session tracking, watchdog. The only genuinely new work is the
**message layer**: like BettingMini, Tai Xiu's concrete message *bodies* vary
per environment (product), but unlike BettingMini the **CMD is a fixed constant**
across every environment because Tai Xiu is always a single game instance (so
there is no per-env OFFSET to add). Tai Xiu therefore sits structurally between
BettingMini (per-product bodies, CMD = CODE + offset) and SLOT (single fixed CMD,
product-neutral body): **per-product bodies + fixed CMD**. The deliverable is a
`TaiXiuGameBot` that reuses `BettingMiniGameBot`'s round behavior wholesale and a
`TaiXiuMessageTypes` provider that registers per-product message classes against
fixed CMD constants. One genuinely Tai-Xiu-specific accounting rule is folded in:
Tai Xiu is a **balanced 2-entry game** that refunds the Tai/Xiu stake imbalance to
the latest bettors at round end, so balance and bet-amount/RTP metrics must net out
the refund or a fully-refunded round looks like a total loss (AD-11).

## Findings — Current State

### Captured payloads — source of truth (2026-06-24)
- Real frames for the **`MiniGame` / `taixiuPlugin`** product live at repo root
  `/Users/gleb/IdeaProjects/Bot/TaiXiuMessages/` (`Subscribe.js`, `Start.js`,
  `Bet.js`, `End.js`). Dev derives all Phase-2 fixtures from these.
- **Envelope.** Outbound: `["6", "MiniGame", "taixiuPlugin", {payloadWithCmd}]`.
  Inbound: `[5, {payloadWithCmd}]`. The leading `"6"` is the outbound action code
  string; `5` is the inbound action response code. Plugin/extension name is
  `taixiuPlugin`, zone/room name is `MiniGame`.
- **CMD integers are FIXED literals** (single-instance game), confirming the
  fixed-CMD design (AD-3). Resolved map (resolves OI-2):
  | Frame | Direction | cmd |
  |---|---|---|
  | subscribe | outbound | **1005** |
  | bet | outbound | **1000** |
  | startGame | inbound | **1002** |
  | endGame | inbound | **1004** |
  **No `updateBet` frame was captured** — its cmd is unknown (residual OI-5).
- **Bet payload** (`Bet.js`): `{"cmd":1000,"b":500000,"sid":2670572,"aid":1,"eid":1}`.
  `b` = bet amount, `sid` = session id, `eid` = entry id (Tai Xiu has exactly **2
  entries**: Tai vs Xiu), `aid` = uncertain (likely account/area id — unconfirmed,
  OI-6). These `eid`/`aid` fields are NOT present in the betting-mini bet, so a
  dedicated bet body is needed (see AD-11 / OI-2 resolution).
- **StartGame payload** (`Start.js`): `{"odE":3.99,"iES":true,"cmd":1002,"sid":2670572}`.
  `sid` = session id, `odE` = odds/payout multiplier (inferred), `iES` = boolean
  flag (inferred). No explicit `timeForBetting` field captured — the countdown
  source for Tai Xiu must be confirmed (folded into Phase 2 / OI-5).
- **EndGame payload** (`End.js`): dice `d1`/`d2`/`d3` (1+6+6=13 → Tai), the `c`
  array is dice animation vectors (ignore for accounting), and the
  **refund-aware accounting fields** `gB`/`gR`/`GX`/`cB`/`cBB`/`cR`/`CX` — see
  AD-11 below. This is the central new domain nuance.

### There is NO reusable legacy Tai Xiu code (brief assumption corrected)
- The brief anticipated a legacy `TaiXiuSevenBot` and `g2`/`g4` Tai Xiu message
  classes. **None exist.** `grep` across `src/main` and `src/test` finds
  "TaiXiuSeven"/"8000" **only in doc comments** as an illustrative offset example
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypes.java:12-13,52`).
  `domain/bot/implementation/` does not exist. The `g2`/`g4` packages hold
  `bom`/`b52` (g2) and `nohu` (g4) betting-mini classes only — no Tai Xiu.
- `GameType.TAI_XIU` **already exists** in the enum
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/model/GameType.java:6`),
  displayName `"Tài Xỉu"`.
- `BotFactory` currently rejects it:
  `case TAI_XIU, CARD_GAME, UP_DOWN -> throw new IllegalArgumentException("Game type not yet implemented...")`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java:161-162`).
- A permissive no-op `TaiXiuConfigValidator` already exists
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/validation/TaiXiuConfigValidator.java`).
  It is registered so the validator-factory boot completeness check passes today.

### Behavioral template (`BettingMiniGameBot`) — reuse wholesale
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`.
  Everything Tai Xiu needs is here: game-state/phase (`gameState`,
  `BettingMiniGameState`), countdown (`startRemainingTimeCountDown`,
  BettingMiniGameBot.java:180), watchdog (`scheduleWatchdog`,
  BettingMiniGameBot.java:196), the `subscribe → onSubscribe → onStartGame →
  onUpdate → sendAsync(bet) → onEndGame` scenario (BettingMiniGameBot.java:486-519),
  the pendingDecision park-and-pop (BettingMiniGameBot.java:107,393-471), and
  `onEndGame` marker-interface accounting (BettingMiniGameBot.java:267-320).
- **The single coupling to BettingMini's CMD scheme** is the message-types call
  and the cmd matcher:
  - `messageTypes.getTypeRegistrations(offset, game.isMd5())` (BettingMiniGameBot.java:492)
  - `cmd(GameMessageTypes.SUBSCRIBE_CODE + offset)` (BettingMiniGameBot.java:507)
  - `onStart()`'s OutputPrinter cmd list `CODE + offset` (BettingMiniGameBot.java:530-535)
  - `this.offset = game.getOffset()` (BettingMiniGameBot.java:124) and
    `new Request(pluginName, zoneName, offset)` (BettingMiniGameBot.java:127).
  These four spots are the only ones a fixed-CMD variant must override.

### Message infrastructure
- `GameMessageTypes`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypes.java`)
  has fixed CODE constants (`SUBSCRIBE_CODE=3000`, `UPDATE_BET_CODE=3002`,
  `START_GAME_CODE=3005`, `END_GAME_CODE=3006`), five typed accessors, and the
  CMD = `CODE + offset` registration (GameMessageTypes.java:56-64).
- `GameMessageTypesResolver`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypesResolver.java`)
  already demonstrates the GameType-first split from the SLOT work:
  `resolveBettingMini(ProductCode)` (line 34) keys per product;
  `resolveSlot()` (line 58) is product-neutral.
- The polymorphic base `BettingMiniMessage`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/BettingMiniMessage.java`)
  uses `@JsonTypeInfo(use=NAME, include=EXISTING_PROPERTY, property="cmd",
  visible=true)` extends lib `Body`. The abstract subtype contracts
  `SubscribeMessage`/`StartGameMessage`/`EndGameMessage`/`UpdateBetMessage`/
  `StartGameMd5Message` expose the normalized accessors the bot needs
  (`getTimeForBetting`, `getSessionId`, etc.). SLOT mirrored this with
  `SlotMessage` (`.../message/slot/SlotMessage.java`).
- Per-product variation is exactly the `Tip/Bom/Nohu/B52` pattern: one provider
  class per product (e.g. `TipGameMessageTypes`,
  `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/g3/tip/TipGameMessageTypes.java`)
  returning that product's concrete message classes. Concrete classes use the
  `@JsonCreator`/`@JsonProperty` + `@Getter/@Setter` + nested-static-class style
  (`TipSubscribeMessage`,
  `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/g3/tip/TipSubscribeMessage.java`).

### Request builder
- `Request`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/request/Request.java`)
  is `(pluginName, zoneName, cmdPrefix)` and adds `cmdPrefix + 3000/3002/...` on
  every outbound. `cmdPrefix=0` would emit bare CMDs, BUT the captured Tai Xiu bet
  carries Tai-Xiu-specific `eid`/`aid` fields that `Request.bet` does not emit, so
  **a dedicated `TaiXiuRequest` is required** (AD-12 — supersedes the earlier
  "reuse with cmdPrefix=0" idea now that OI-2 is resolved).

### BotFactory wiring
- `BotFactory.createBot` (BotFactory.java:143-163) switches on `game.getGameType()`,
  resolving message types per-branch: `BETTING_MINI` →
  `resolveBettingMini(productCode)` + `setStrategyFactory`; `SLOT` →
  `resolveSlot()` + `setSlotStrategyFactory`. `BettingStrategyFactory` and
  `SlotStrategyFactory` are both already constructor-injected fields
  (BotFactory.java:66-67).

### Strategy framework
- Tai Xiu is round-based with the same BET/option semantics as BettingMini, so it
  **reuses the betting `BettingStrategy`/`BetContext`/`BetDecision`/`StrategyId`
  family** (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/`).
  This is the opposite of SLOT (which needed a parallel family because slots have
  no rounds/options). The `option` and `RoundState` concepts apply directly.
- `StrategyController.list(gameType)`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/controller/StrategyController.java:30-35`)
  currently returns the full betting list **only** for `BETTING_MINI`/null and an
  empty list for everything else including `TAI_XIU`. If Tai Xiu reuses betting
  strategies, this must list them for `TAI_XIU` too.

### Config validation
- `GameConfigValidator` framework: factory keys on `supportedType()`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/botgroup/validation/GameConfigValidatorFactory.java`),
  boot-asserts every `GameType` has exactly one validator. `BettingMiniConfigValidator`
  (`.../validation/BettingMiniConfigValidator.java`) holds the STRICT-GRID numeric
  rules. `TaiXiuConfigValidator` is currently a permissive no-op subclass of
  `NoOpConfigValidator`.

### Environment / zone resolution
- `Environment.resolveZoneName(game)`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/environment/model/Environment.java:95-102`)
  treats only `BETTING_MINI` and `SLOT` as "mini" (mini zone); **`TAI_XIU`
  currently falls into the card/`Simms` default branch**
  (confirmed by `EnvironmentZoneResolutionTest.java:107-109`). If Tai Xiu runs on
  the mini WS zone like BettingMini/SLOT, this resolver and its test must add
  `TAI_XIU` to the mini branch (see AD-7).

### Test harness convention
- `BettingMiniGameBotTipDispatchTest`
  (`/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTipDispatchTest.java`)
  and the per-product `*GameMessageTypesTest` (e.g.
  `.../message/g3/tip/TipGameMessageTypesTest.java`) are the templates: register
  the provider's `getTypeRegistrations(...)`, deserialize a JSON fixture from
  `src/test/resources/messages/<product>/`, wrap in
  `ActionResponseMessage<>(ACTION_RESPONSE, msg)`, reflectively invoke the private
  `onX` handler, verify mock interactions. SLOT mirrored this under
  `src/test/resources/messages/slot/`.

## Per-aspect readiness / mapping

| Aspect | Status | Notes |
|---|---|---|
| `GameType.TAI_XIU` enum value | **ready** | Already present (GameType.java:6). |
| Round behavior (state/phase/countdown/watchdog/accounting) | **ready** | Inherited from `BettingMiniGameBot` (AD-1). |
| `subscribe→start→bet→end` scenario | **ready** | Inherited; only the CMD-derivation hooks change (AD-2). |
| Fixed-CMD message registration (per-env body) | **partial** | New `TaiXiuMessageTypes` interface + per-product impls; `getTypeRegistrations()` takes no offset (AD-3 — the novel part). CMDs resolved: 1005/1002/1004 + bet 1000. |
| Per-product Tai Xiu message classes | **ready (captured)** | Payloads + CMD ints resolved for `MiniGame`/`taixiuPlugin` (OI-1/OI-2 done). Structure mirrors `Tip*Message`; fixtures derive from `TaiXiuMessages/`. |
| Refund-aware endgame accounting | **partial (new)** | Tai Xiu nets out refund `gR` in balance + bet-amount metric (AD-11). Lives on the Tai Xiu `EndGameMessage` subtype via `HasBotWinnings`/`HasBetTotals` (see `ENDGAME_METRICS.md`). |
| Tai Xiu bet request (`eid`/`aid`) | **partial (new)** | Dedicated `TaiXiuRequest` needed; bet carries fixed cmd 1000 + `eid`/`aid` (AD-12). |
| Resolver entry for Tai Xiu | **partial** | Add `resolveTaiXiu(ProductCode)` to `GameMessageTypesResolver` (AD-4). |
| `BotFactory` `case TAI_XIU` | **blocked** | Currently throws; wire after message + bot land (AD-5). |
| Strategy family | **ready (reuse)** | Reuses betting `BettingStrategy`/`StrategyId` (AD-6). `StrategyController` extended to list for `TAI_XIU`. |
| Zone resolution | **partial** | `resolveZoneName` must treat `TAI_XIU` as mini (AD-7) — confirm WS zone (OI-3). |
| Config validation | **partial** | Promote `TaiXiuConfigValidator` from no-op to the BettingMini grid rules, or keep no-op (AD-8). |
| `Game` config fields | **ready** | Reuse `gameId`/`pluginName`; `offset` is unused for Tai Xiu (CMD fixed) — no new fields (AD-9). |
| Test fixtures | **partial** | New `src/test/resources/messages/taixiu/<product>/` fixtures (Phase 7). |

## Architecture Decisions

**AD-1. `TaiXiuGameBot extends BettingMiniGameBot` — inherit round behavior, do
not duplicate.** Tai Xiu is round-based with identical phase/countdown/watchdog/
accounting semantics. The new bot subclasses `BettingMiniGameBot` and overrides
**only** the CMD-derivation surface, reusing every inherited handler
(`onSubscribe`, `onStartGame`, `onUpdate`, `onEndGame`, `beforeReconnect`,
`cleanup`, the pendingDecision park/pop, the strategy wiring). This is the
single biggest reuse win and the reason Tai Xiu is far smaller than SLOT was.

> To make this clean, a small refactor of `BettingMiniGameBot` is required so the
> CMD-derivation surface is overridable. See AD-2.

**AD-2. Extract the CMD-derivation surface in `BettingMiniGameBot` into protected
seams.** Today `BettingMiniGameBot` hardcodes `CODE + offset` in four places
(findings above). Refactor these into protected, overridable methods on
`BettingMiniGameBot` whose default behavior is the existing `CODE + offset`:
- `protected int subscribeCmd()` → default `SUBSCRIBE_CODE + offset`
- `protected int updateBetCmd()`, `protected int startGameCmd()`,
  `protected int endGameCmd()` → defaults `CODE + offset`
- `protected NamedType[] typeRegistrations(ObjectMapper-irrelevant)` — i.e. wrap
  the `messageTypes.getTypeRegistrations(offset, md5)` call in a protected method
  `protected NamedType[] messageTypeRegistrations()`.
- `protected Request buildRequest(Game game)` → default
  `new Request(game.getPluginName(), zoneName, offset)`.

The betting-mini scenario, `onStart` cmd-list, and `botBehaviorScenario` are
rewritten to call these seams instead of inlining `CODE + offset`. **Behavior for
BettingMini is byte-for-byte unchanged** (the defaults reproduce the current
expressions). `TaiXiuGameBot` overrides the four cmd seams to return the fixed
CMD constants, `messageTypeRegistrations()` to call the no-offset Tai Xiu
provider, and `buildRequest()` to build a fixed-CMD request. This refactor is its
own phase (Phase 1) and is verified by the **existing** betting-mini test suite
staying green.

**AD-3. Fixed-CMD message provider with per-product bodies — the novel part.**
Introduce `TaiXiuMessageTypes` (a new interface, NOT extending
`GameMessageTypes`) with **fixed CMD constants** and the same five typed
accessors as `GameMessageTypes`, plus a no-arg `getTypeRegistrations()` that
registers each product's concrete class against the literal fixed CMD string. The
shape is the hybrid: it has the **per-product accessor set of `GameMessageTypes`**
(because bodies differ per product) combined with the **no-offset, fixed-cmd
`getTypeRegistrations()` of `SlotMessageTypes`** (because the CMD is constant).
Concrete constants (**resolved from captures, OI-2**):
```
int SUBSCRIBE_CMD  = 1005;   // outbound, no offset added
int START_GAME_CMD = 1002;   // inbound
int END_GAME_CMD   = 1004;   // inbound
int BET_CMD        = 1000;   // outbound
int UPDATE_BET_CMD = <unknown — no frame captured, OI-5>;
```
`getTypeRegistrations()` registers `subscribeType()`→`"1005"`,
`startGameType()`→`"1002"`, `endGameType()`→`"1004"`. The bet (`1000`) is an
outbound-only body built by the request layer (AD-11), not registered as an
inbound polymorphic subtype. `updateBet` registration is **omitted in v1** until
its cmd is captured (OI-5) — analogous to
`SlotMessageTypes.getTypeRegistrations()` but with four (or five with md5)
entries and per-product classes from the provider impl.

**AD-4. One Tai Xiu provider impl per product, resolved by a new
`resolveTaiXiu(ProductCode)`.** Mirror `resolveBettingMini`: a switch over
`ProductCode` returning the product's `TaiXiuMessageTypes` impl. **v1 implements
only the captured `MiniGame`/`taixiuPlugin` product** (OI-1 resolved for this
product); unimplemented products throw the same
"not yet implemented for product code" `IllegalArgumentException` the betting-mini
resolver uses. The Tai Xiu concrete classes live in the product package alongside
the betting-mini ones (e.g. `.../message/g3/tip/TaiXiuTipSubscribeMessage.java`),
or a dedicated `.../message/taixiu/<product>/` subpackage — Dev picks one and is
consistent; the per-product `g3/tip` colocation is preferred for discoverability.

**AD-5. `BotFactory` `case TAI_XIU` builds a `TaiXiuGameBot` wired with the
betting strategy factory.** Split `TAI_XIU` out of the throwing arm:
```
case TAI_XIU -> {
    TaiXiuGameBot bot = new TaiXiuGameBot();
    bot.setTaiXiuMessageTypes(GameMessageTypesResolver.resolveTaiXiu(env.getProductCode()));
    bot.setStrategyFactory(strategyFactory);   // reuses the betting strategy factory (AD-6)
    yield bot;
}
```
Leave `CARD_GAME, UP_DOWN` throwing. No new factory injection is needed —
`strategyFactory` is already a field.

**AD-6. Tai Xiu reuses the betting strategy family — no parallel slot-style
family.** Because Tai Xiu has rounds and bettable options exactly like
BettingMini, `BettingStrategy`/`BetContext`/`BetDecision`/`StrategyId` apply
directly. The inherited `initializeSubclass` strategy wiring
(`strategyFactory.create(strategyId)`) is reused unchanged. `StrategyController.list`
is extended so `gameType=TAI_XIU` returns the betting strategy list (today it
returns empty). **(Confirm this with the user — see Open Item OI-4; this is the
one decision worth an explicit nod, though round-based reuse is the strong
default.)**

**AD-7. Tai Xiu uses the mini WS zone.** Add `TAI_XIU` to the "mini" branch of
`Environment.resolveZoneName` so it resolves the mini zone like BETTING_MINI/SLOT.
Update `EnvironmentZoneResolutionTest` accordingly (it currently asserts TAI_XIU →
card/Simms). **Pending OI-3 (confirm the actual WS zone for Tai Xiu).** If Tai Xiu
is genuinely on a different zone, introduce a dedicated branch instead.

**AD-8. Config validation: reuse the BettingMini grid rules for Tai Xiu.** Since
Tai Xiu shares the same `minBet/maxBet/betIncrement/...` group config and betting
semantics, the `TaiXiuConfigValidator` is promoted from a no-op to the same
STRICT-GRID rule set as `BettingMiniConfigValidator`. Implement by extracting the
rule body from `BettingMiniConfigValidator` into a shared package-private helper
(e.g. `BettingGridRules.validate(group)`) that both validators call, keeping each
validator a thin `supportedType()` + delegation. This avoids duplicating the
rule list and keeps the boot completeness invariant satisfied. (If the user
prefers Tai Xiu stay permissive until staging, keep it a no-op — AD-8 alt; the
plan defaults to reuse because Tai Xiu *can* start a bot once this lands, unlike
the still-throwing CARD_GAME/UP_DOWN, so a no-op would let a bad config through to
a runnable bot.)

**AD-9. No new `Game` fields.** Tai Xiu reuses `gameId` (subscribe channel id, if
needed) and `pluginName` (SmartFox extension). `offset` is **unused** for Tai Xiu
because the CMD is fixed — `TaiXiuGameBot` never reads `game.getOffset()`. Option
config is the same `optionAffinities`/legacy fields BettingMini uses, consumed by
the inherited strategy path. No DTO/mapper changes.

**AD-10. md5 variant handling.** BettingMini has a `startGameMd5Type()` path
gated on `game.isMd5()`. Tai Xiu carries the same `md5` capability through the
inherited `messageTypeRegistrations()`/`startGameCmd()` seams. If a captured Tai
Xiu product is non-md5 only, `startGameMd5Type()` may return the same class as
`startGameType()` (matching how some betting-mini providers handle it) — Dev
decides per captured payload. The fixed `START_GAME_CMD` is shared by both
variants (md5 only changes the body, not the cmd) — consistent with AD-3.

**AD-11. Refund-aware accounting — Tai Xiu balance and metrics MUST net out the
refund (NEW, central amendment).** Tai Xiu is a **balanced 2-entry game**: at
round end the server refunds the stake imbalance between Tai and Xiu to the latest
bettors. A bet can therefore be partially or fully refunded **independently of
win/loss**. In the captured End frame `gB=500000`, `GX=500000`, `gR=500000` →
nothing actually rode on the outcome (100% refunded, win money = 0). If accounting
treats this naïvely (debit 500k at bet time, no credit-back at end) the round looks
like a 500k wager with a total loss, badly skewing balance and RTP.

Field reading (gold currency = `g*`/`G`, coin currency = `c*`/`C`; **confirmed by
user 2026-06-24, OI-7 resolved**):
| Field | Meaning | Sample |
|---|---|---|
| `gB` | gold game **bet** total | 500000 |
| `gR` | gold **refund** | 500000 |
| `G`  | gold **win money** (winnings — direct field) | 0 |
| `GX` | gold **exchange** (gross amount credited back = `gR + G`) | 500000 |
| `cB`/`cBB`/`cR`/`CX`/`C` | coin-currency counterparts | 0 |

**Confirmed formulas (user 2026-06-24 — use the `G` field directly, do NOT compute
`GX − gB`):**
- **winnings** = `G` (the win-money field) — `0` in the capture
- **money lost / effective wagered** = `gB − gR` — `0` in the capture (fully refunded)
- Sanity: `GX = gR + G` (gross credited back), so `−b + GX` and `−b + gR + G` are
  equivalent balance identities; prefer the explicit `gR + G` form so winnings and
  refund stay separable for metrics.

Required behavior, owned by the Tai Xiu `EndGameMessage` subtype via the
**marker-interface model from `docs/plans/ENDGAME_METRICS.md`** (`HasBotWinnings`,
`HasBetTotals`) — NOT new bot getters:
- **Local balance.** The bot debits the full bet `b` at bet time (inherited
  `creditBalance` path, like BettingMini/SLOT — see commit `9eea006` for the SLOT
  precedent that records *total* stake for the debit). At `onEndGame` the bot must
  **credit back `gR` (refund) plus winnings** so the running balance reflects
  reality. Net balance effect of a round = `−b + gR + winnings`.
- **`HasBetTotals` (bet-amount metric / `bot_bet_amount_total`).** Must report
  **effective wagered = `gB − gR`**, not gross `gB`. This mirrors the SLOT fix in
  `9eea006` where the metric was corrected to record true stake. A fully-refunded
  round contributes **0** to `bot_bet_amount_total`.
- **`HasBotWinnings` (`bot_winnings_total`).** Reports `winnings` = the **`G`
  field** directly (gold win money). Do not compute `GX − gB`.
- **RTP.** RTP = `bot_winnings_total / bot_bet_amount_total` over a window; because
  the denominator uses effective stake, a fully-refunded round neither inflates nor
  deflates RTP. The extraction code **must not divide by zero** for a single
  fully-refunded round — RTP is a dashboard-side ratio over aggregated counters, so
  the per-round contribution is simply `+0 / +0`; no in-bot division occurs. Note
  this explicitly so Dev does not introduce a per-round RTP getter that divides.

The Tai Xiu `EndGameMessage` subtype is the single place these formulas live.
Phase 4 (endgame accounting) and Phase 7 (stream) must carry fixtures for: (a)
**fully-refunded** round (`gB=gR`, win=0 — the captured `End.js`), (b) **partial
refund** (`0 < gR < gB`), (c) **zero refund** (`gR=0`). See Phase 4 / Phase 7.

**AD-12. A dedicated `TaiXiuRequest` is required — but for the CMD values, NOT the
body shape. Reuse the existing `Bet.BetData` field set.** Resolves the OI-2
outbound question. The captured bet is `{"cmd":1000,"b":...,"sid":...,"aid":1,"eid":1}`.

**Important correction (verified in code 2026-06-24):** the betting-mini bet body
ALREADY matches this shape. `Bet.BetData`
(`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/request/Bet.java`)
extends `Body` and emits exactly `{cmd, aid:1, b, eid, sid}` — i.e. `eid`/`aid` are
already present (`aid` is hardcoded to `1`). So the body is NOT the reason a new
request is needed.

The real reason: `Request`'s constructor takes a single `cmdPrefix` and derives
`subscribe = cmdPrefix+3000`, `bet = cmdPrefix+3002` (a fixed +2 gap). Tai Xiu
needs `subscribe=1005`, `bet=1000` (a −5 gap), which no single `cmdPrefix` can
produce. Therefore `buildRequest()` (AD-2 seam) returns a Tai-Xiu request that
emits the **bare fixed CMDs** (`1005` subscribe, `1000` bet) with explicit per-cmd
values instead of a single prefix.

Implementation guidance for Dev:
- The body classes are **body-only** (`extends Body` / `ActionRequestMessage`); the
  `["6","MiniGame","taixiuPlugin",{…}]` array envelope is assembled by the
  ws-parser from `zoneName`+`pluginName`+`Body` — do NOT model the array.
- **Reuse `Bet.BetData`'s field shape** (`cmd, aid, b, eid, sid`) rather than
  inventing a new bet body — it is already correct for Tai Xiu. The only change vs
  betting-mini is passing the fixed `cmd=1000` instead of `cmdPrefix+3002`.
- `eid` is the chosen entry (Tai vs Xiu = exactly 2 options) from the inherited
  strategy decision (`resolveNextEntryToBet`); `aid` stays `1` pending OI-6.
- Mirror `SlotRequest` for the overall request structure / how it carries explicit
  CMDs without a prefix scheme.

**AD-13. Single-entry-per-round lock + 1-based entry ids (user 2026-06-24).** Two
Tai-Xiu-specific betting rules confirmed by the user:
- **Entry ids are 1-based: Tài = `eid 1`, Xỉu = `eid 2`** (NOT 0-based). The
  existing Tai Xiu default option set is `optionAffinities {1:1, 2:1}` (from the
  "default to 2 options" change), which already yields `{1,2}` — keep it; make the
  mapping explicit (named constants / doc) and ensure NO 0-based assumption leaks
  into entry selection. (This also resolves the earlier eid-mapping uncertainty.)
- **One entry per round.** A bot may bet only ONE side per round. Once it has
  placed a bet on Tài this round it may only *increase* its Tài bet (and likewise
  for Xỉu) — it must NOT bet the other entry until the next round. The lock is set
  by the first bet of a round and **resets each round** (on sessionId/StartGame
  change). Implementation: the strategy's chosen `optionId` flows through
  `strategy.decide(buildBetContext())` at two private call sites in
  `BettingMiniGameBot` (`betCondition()` and the bet supplier). Add a protected
  seam (default = identity, BettingMini unchanged — BettingMini still allows
  multi-entry) that both call sites route through, e.g.
  `protected Optional<BetDecision> decideBet(BetContext ctx)`. `TaiXiuGameBot`
  overrides it: call `super`/`strategy.decide`, then if the current round
  (`memory.getCurrentRound()`) already has a recorded bet, **remap the decision's
  `optionId` to the already-bet entry**; otherwise pass through. Derive the locked
  entry from round memory (not a separate field) so it resets automatically per
  round and survives the park/re-derive race. Tài/Xỉu amounts still come from the
  strategy (so martingale-style increases on the locked side work).

## Plan

> Maven builds require
> `JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home`
> (the default JDK is Java 8 and will not compile). Prefix every `mvn` command
> with it.

### Phase 1 — Extract CMD-derivation seams in `BettingMiniGameBot` (refactor, no behavior change)
Goal: make the four `CODE + offset` couplings overridable so a fixed-CMD subclass
is possible, with **zero behavior change** for BettingMini.

Modify `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`:
- Add protected seams with `CODE + offset` defaults: `subscribeCmd()`,
  `updateBetCmd()`, `startGameCmd()`, `endGameCmd()`,
  `messageTypeRegistrations()` (wraps `messageTypes.getTypeRegistrations(offset, game.isMd5())`),
  and `buildRequest(Game)` (wraps `new Request(pluginName, zoneName, offset)`).
- Rewrite `botBehaviorScenario()` (BettingMiniGameBot.java:486-519) to call the
  seams: `mapper.registerSubtypes(messageTypeRegistrations())`,
  `cmd(subscribeCmd()).and(typeOf(RECEIVED))`. Keep the concrete `onMessage` class
  lookups going through `messageTypes.subscribeType()` etc. (those are reused via
  AD-3's parallel accessor set — see Phase 3 for how the subclass overrides them).
- Rewrite `initializeSubclass()` (BettingMiniGameBot.java:122-167) so the
  `request` assignment uses `buildRequest(game)` and the rest stays.
- Rewrite the `onStart()` cmd list (BettingMiniGameBot.java:530-535) to use the
  four cmd seams.
- Make `offset`, `messageTypes`, and the `onSubscribe/onStartGame/onUpdate/onEndGame`
  handlers visible to a subclass (relax `private` → `protected` only where the
  subclass needs them; the handlers can stay private if the subclass only
  overrides cmd seams and reuses the same `messageTypes` accessor shape).

Verification:
- `JAVA_HOME=... mvn -q -o test` — the **entire existing betting-mini suite stays
  green** (`BettingMiniGameBotTipDispatchTest`, `RequestTest`, all
  `*GameMessageTypesTest`). This is the contract that the refactor changed nothing
  observable.

### Phase 2 — Tai Xiu message classes (per-product) + fixtures
Goal: real, deserializable Tai Xiu message classes for the captured
`MiniGame`/`taixiuPlugin` product (OI-1 resolved).

> **Source of truth:** the four files in `/Users/gleb/IdeaProjects/Bot/TaiXiuMessages/`
> (`Subscribe.js`/`Start.js`/`Bet.js`/`End.js`). Copy/derive every fixture from
> them; do not invent fields. The `cmd` literals are 1005/1002/1000/1004 (AD-3).
> The `EndGameMessage` subtype must expose the refund-aware fields
> `gB`/`gR`/`GX`/`cB`/`cBB`/`cR`/`CX` and the dice `d1`/`d2`/`d3` (AD-11); the `c`
> animation array is ignored.

For the captured product:
- Create concrete classes mirroring the betting-mini set, extending the **same**
  abstract bases (`SubscribeMessage`, `StartGameMessage`, `StartGameMd5Message`,
  `UpdateBetMessage`, `EndGameMessage`) so they satisfy the inherited handlers and
  marker interfaces. The `EndGameMessage` subtype implements **`HasBotWinnings`
  (winnings = the `G` field)** and **`HasBetTotals` (effective wagered = `gB − gR`)**
  per AD-11 — these are the refund-aware formulas, the central nuance of this
  amendment. `HasJackpot` only if the payload supports it (the capture has
  `iJp:false`, `jpV:0` — jackpot fields exist; wire `HasJackpot` reading `jpV`/`iJp`).
  Style: `@JsonCreator`/`@JsonProperty` + `@Getter/@Setter` + nested static
  classes, exactly like `TipSubscribeMessage`. Location: `.../message/taixiu/` (the
  captured product is `taixiuPlugin`, not a g3/tip product, so a dedicated
  `message/taixiu/` package is the natural home — AD-4).
- The `cmd` field on each class is the **fixed** Tai Xiu CMD (AD-3, e.g. `1004` on
  the EndGame class), not a per-env offset sum.

Add fixtures under `src/test/resources/messages/taixiu/`, each holding the
**inner payload object** (the `[5, {...}]` / `["6","MiniGame","taixiuPlugin",{...}]`
wrapping is applied by the test harness as it is for betting-mini):
- `subscribe.json` — from `Subscribe.js` (`{"cmd":1005}`)
- `startGame.json` — from `Start.js` (`{"odE":3.99,"iES":true,"cmd":1002,"sid":...}`)
- `endGame_fullRefund.json` — from `End.js` as-is (`gB=gR=500000`, win=0)
- `endGame_partialRefund.json` — hand-derived (`0 < gR < gB`, e.g. `gB=500000,
  gR=200000`, set `G` to a chosen non-zero win) for AD-11 case (b)
- `endGame_noRefund.json` — hand-derived (`gR=0`, with a `G` win value) for AD-11 case (c)
- `bet.json` — from `Bet.js` (`{"cmd":1000,"b":500000,"sid":...,"aid":1,"eid":1}`),
  used by the request/builder test (AD-12)
- `updateBet.json` — **omit** until the updateBet cmd is captured (OI-5)
- `startGameMd5.json` — only if an md5 variant is later captured (AD-10)

Verification:
- `JAVA_HOME=... mvn -q -o test-compile`.
- `TaiXiu<Product>MessageDeserializationTest` (mirror `TipGameMessageTypesTest`):
  register the classes against the fixed CMD strings (1005/1002/1004),
  `mapper.readValue` each fixture into its abstract base, assert concrete type and
  the normalized accessors (`getSessionId`, the countdown source). For the three
  EndGame fixtures assert the **refund-aware** outputs (AD-11): full-refund →
  winnings 0, effective wagered 0; partial → winnings = `G`, wagered = `gB−gR`;
  no-refund → wagered = `gB`, winnings = `G`. This is the test that pins the refund formulas.

### Phase 3 — `TaiXiuMessageTypes` provider interface + per-product impls + resolver
Goal: a fixed-CMD provider with per-product bodies and a resolver entry.

Create:
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/TaiXiuMessageTypes.java`
  — interface with fixed `SUBSCRIBE_CMD=1005`, `START_GAME_CMD=1002`,
  `END_GAME_CMD=1004`, `BET_CMD=1000` constants (AD-3). `UPDATE_BET_CMD` is
  **omitted in v1** (uncaptured, OI-5). Typed accessors for the inbound subtypes
  (`subscribeType/startGameType/startGameMd5Type/endGameType`, returning the shared
  abstract bases) plus a default no-arg `getTypeRegistrations()` registering each
  inbound type against its literal fixed CMD string (`"1005"`/`"1002"`/`"1004"`).
  The bet (1000) is outbound-only and is **not** registered here (AD-12).
- The captured-product impl, e.g. `.../message/taixiu/MiniGameTaiXiuMessageTypes.java`,
  returning that product's concrete Tai Xiu classes (mirrors `TipGameMessageTypes`).

Modify `GameMessageTypesResolver`
(`.../message/GameMessageTypesResolver.java`): add
`public static TaiXiuMessageTypes resolveTaiXiu(ProductCode productCode)` — a
switch over `ProductCode` returning the impl for captured products, throwing the
"not yet implemented for product code" message for the rest (mirror
`resolveBettingMini`).

Verification:
- `JAVA_HOME=... mvn -q -o test`.
- `TaiXiuMessageTypesTest`: `resolveTaiXiu(<captured product>).getTypeRegistrations()`
  registers the three inbound types against names `"1005"`/`"1002"`/`"1004"` (no
  updateBet, no offset); round-trip each Phase-2 inbound fixture through the
  provider's registrations. Assert an unimplemented product throws the
  "not yet implemented" `IllegalArgumentException`.

### Phase 4 — `TaiXiuGameBot`
Goal: a working Tai Xiu bot that reuses all `BettingMiniGameBot` behavior and only
overrides the CMD seams.

Create `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/TaiXiuGameBot.java`
extending `BettingMiniGameBot`:
- Field `@Setter private TaiXiuMessageTypes taiXiuMessageTypes;`
- Override `subscribeCmd()/startGameCmd()/endGameCmd()` to return the fixed
  `TaiXiuMessageTypes` constants (1005/1002/1004). `updateBetCmd()` has no captured
  value (OI-5); since updateBet is omitted in v1, leave it inheriting the default
  (it is never matched because the scenario does not register an updateBet subtype)
  or override to a sentinel that is documented as unused — Dev picks, but the
  inbound matcher must not register 3002.
- Override `messageTypeRegistrations()` to return
  `taiXiuMessageTypes.getTypeRegistrations()` (subscribe/start/end only).
- Override the concrete-class accessors the scenario uses
  (`subscribeType/startGameType/startGameMd5Type/endGameType`) to delegate to
  `taiXiuMessageTypes` instead of the inherited `messageTypes` (betting) field.
  `updateBetType` is unused in v1 (OI-5). (Cleanest if Phase 1 routes those accessor reads through a
  protected seam too — fold that into Phase 1's seam extraction so this override
  is trivial.)
- Override `buildRequest(Game)` to build a **`TaiXiuRequest`** (AD-12), not the
  betting-mini `Request`: it emits the bare fixed CMDs (subscribe `1005`, bet
  `1000`) and the bet body carries `eid` (chosen Tai/Xiu entry from the inherited
  strategy decision) and `aid` (default `1`, OI-6). The betting-mini
  `Request` cannot emit `eid`/`aid`, so reuse-with-`cmdPrefix=0` is rejected.
  Mirror `SlotRequest` for structure. Create
  `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/request/TaiXiuRequest.java`.
- Override the INFO init log line so it reads "TaiXiuGameBot initialized..."
  (optional; the inherited line is acceptable if it logs the game name).
- **Refund-aware accounting (AD-11).** The inherited `onEndGame` already does
  marker-interface dispatch (`HasBotWinnings`/`HasBetTotals`, per
  `docs/plans/ENDGAME_METRICS.md`). Because the Tai Xiu `EndGameMessage` subtype
  (Phase 2) implements those interfaces with the **refund-netting formulas**
  (winnings = `G`, effective wagered `gB−gR`), the bot needs **no new accounting
  code** — but it MUST credit the refund back to local balance at end. The
  inherited `onEndGame` does NOT echo winnings onto balance (BettingMini only
  debits at bet time and reconciles via `checkBalance()`); Phase 4 therefore added
  a `balanceCreditFor` seam (default `0`, BettingMini unchanged) that Tai Xiu
  overrides to return **`gR + G`** (refund + win money). `HasBetTotals` reports
  `gB − gR` for the **metric** path — keep balance-credit and bet-amount-metric
  semantics distinct. Net balance per round must equal `−b + gR + G`. Do **not**
  introduce a per-round RTP division.
- Everything else (handlers, scenario, strategy wiring, watchdog) is inherited
  unchanged.

Verification:
- `JAVA_HOME=... mvn -q -o test-compile`.
- `TaiXiuGameBotDispatchTest` (mirror `BettingMiniGameBotTipDispatchTest`):
  construct the bot with mocked clients/metrics, wire `taiXiuMessageTypes`,
  reflectively seed balance, then drive `onEndGame` over **all three** Phase-2
  EndGame fixtures and assert (AD-11):
  - **Full refund** (`End.js`, `gB=gR=500000`): `incBotWinnings(0)`,
    `incBetsPlaced`/bet-amount effective `0`, and net balance change `0`
    (`−b + gR + 0`, with `b=500000` debited earlier) — proves a fully-refunded
    round does NOT look like a 500k loss.
  - **Partial refund**: winnings `G`, bet-amount `gB−gR`, balance change
    `−b + gR + G`.
  - **Zero refund**: bet-amount `gB`, normal win/loss accounting.
  Assert no division-by-zero path is exercised for the full-refund case.
  A second assertion deserializes `subscribe.json` and invokes `onSubscribe`,
  asserting `markConnectionAuthenticated()` and timing capture.

### Phase 5 — `BotFactory` wiring + zone resolution
Goal: `case TAI_XIU` builds a real bot; the env resolves the correct zone.

Modify:
- `BotFactory.createBot` (BotFactory.java:161): split `TAI_XIU` into its own arm
  per AD-5; leave `CARD_GAME, UP_DOWN` throwing.
- `Environment.resolveZoneName` (Environment.java:95-102): add `TAI_XIU` to the
  mini branch (AD-7 / OI-3).

Verification:
- `JAVA_HOME=... mvn -q -o test`.
- Update `EnvironmentZoneResolutionTest` (remove `TAI_XIU` from the card-default
  parametrized case, add it to the mini case).
- `BotFactory` unit test: a mocked registry/env returning a `TAI_XIU` game yields
  a `TaiXiuGameBot` and does not throw.
- App boots (Spring context test or local `spring-boot:run`) with the bot,
  provider, and resolver resolvable.

### Phase 6 — Strategy listing + config validation
Goal: Tai Xiu is selectable in the strategy picker and its group config is
validated.

Modify:
- `StrategyController.list` (StrategyController.java:30): include `TAI_XIU` in the
  branch that returns the betting strategy list (AD-6).
- Promote `TaiXiuConfigValidator` from `NoOpConfigValidator` to the BettingMini
  grid rules (AD-8): extract `BettingMiniConfigValidator`'s rule body into a
  shared package-private helper and have both validators delegate to it.

Verification:
- `JAVA_HOME=... mvn -q -o test`.
- `StrategyControllerTest`: `GET /api/v1/strategy/?gameType=TAI_XIU` now returns
  the full betting strategy list (update the existing assertion that expects
  empty).
- `TaiXiuConfigValidatorTest`: a config BettingMini would reject is now rejected
  for TAI_XIU too (mirror `BettingMiniConfigValidatorTest` boundary cases). The
  `GameConfigValidatorFactory` boot completeness test still passes.

### Phase 7 — Deterministic round-stream test
Goal: end-to-end-ish coverage of the inherited bet→result loop driven by Tai Xiu
fixtures.

Create `TaiXiuGameBotStreamTest` (mirror the betting-mini stream test):
- Build the bot with a seeded `Random` (reuse the inherited `setRandom` seam),
  wire `taiXiuMessageTypes`.
- Drive `onSubscribe → onStartGame → betCondition()/bet() → onEndGame` over a
  sequence mixing the three Phase-2 EndGame fixtures (full/partial/zero refund);
  assert the running balance equals
  `start − Σb + Σrefund(gR) + Σwinnings(G)` (the **refund-aware** identity,
  AD-11), `bot_bet_amount_total` equals `Σ(gB−gR)` (effective stake, NOT gross),
  `incBetsPlaced` fired per round, and the cmd matcher used the **fixed** CMDs
  (1005/1002/1004, no offset). Include the captured full-refund round in the
  sequence and assert it added exactly `0` to both winnings and effective wagered.
  A negative assertion confirms the bot does **not** read `game.getOffset()` (e.g.
  set offset to a poison value and assert it is never used).

Verification:
- `JAVA_HOME=... mvn -q -o test` — all Tai Xiu tests green; full suite green (no
  betting-mini regression from the Phase-1 seam refactor).

### Ordering note
Phase 1 (the seam refactor) must land first and independently — it touches shared
`BettingMiniGameBot` and is validated solely by the existing suite. Phases 2–4 are
independent of factory wiring and compile standalone. Phase 5 depends on Phases
3–4. Phase 6 is independent of 2–5 except the strategy-listing change is only
meaningful once the bot can run. Phase 7 depends on Phase 4.

## Implementation Notes / Concerns

- **The Phase-1 refactor is the riskiest change** because it touches production
  `BettingMiniGameBot`. Keep the seam defaults byte-for-byte equivalent to the
  current inline expressions; the only acceptance signal is the existing
  betting-mini suite staying green. Do not change any handler logic in Phase 1.
- **Fixed CMD vs offset — do not let offset leak into Tai Xiu.** The whole point
  of AD-3 is that Tai Xiu never adds an offset. `TaiXiuGameBot` must not call
  `game.getOffset()` anywhere (Phase 7 asserts this). The inbound matcher and the
  registration strings must both use the bare fixed constants, and they must
  agree (a mismatch silently means the bot never matches its own inbound frames —
  the same class of bug as the SLOT `typeOf(RECEIVED)` note).
- **`typeOf(RECEIVED)` on inbound matchers.** If Tai Xiu's outbound and inbound
  share a CMD (as the spin request/result do in SLOT), the inbound matcher must
  carry `.and(typeOf(RECEIVED))` so the bot does not match its own echo. The
  inherited betting scenario already does this; preserve it through the seam.
- **Per-product bodies, shared abstract bases.** Tai Xiu concrete classes MUST
  extend the existing `SubscribeMessage`/`StartGameMessage`/`EndGameMessage`/
  `UpdateBetMessage` bases so the inherited handlers' normalized accessors
  (`getTimeForBetting`, `getSessionId`) and the marker interfaces work without new
  handler code. This is what makes the bot a thin subclass.
- **`FAIL_ON_UNKNOWN_PROPERTIES=false`** is already set by the inherited
  `botBehaviorScenario`'s mapper config — Tai Xiu fixtures may carry extra fields
  without failing, matching the betting-mini and slot conventions.
- **Outbound CMDs are bare fixed literals + `eid`/`aid` — a `TaiXiuRequest` is
  mandatory (AD-12).** Captures confirm subscribe `1005` / bet `1000` with the bet
  carrying `eid` (Tai vs Xiu) and `aid`. The betting-mini `Request` cannot emit
  those fields, so `cmdPrefix=0` reuse is out. Keep this isolated to
  `buildRequest()` + the new `TaiXiuRequest` (mirror `SlotRequest`); nothing else
  in the bot changes.
- **Refund-aware accounting is the highest-value correctness item (AD-11).** A
  fully-refunded round (the captured `End.js`) must net to zero stake and zero
  win, not a 500k loss. The formulas (winnings = `G` field, effective wagered
  `gB−gR`) live on the Tai Xiu `EndGameMessage` subtype via the
  `HasBotWinnings`/`HasBetTotals` markers (`docs/plans/ENDGAME_METRICS.md`), and
  the balance must credit `gR + G` back at end. Tests MUST cover full/partial/zero
  refund. Formulas confirmed by user 2026-06-24 (OI-7 resolved).
- **Tai Xiu lives in its own package, not g3/tip.** The captured product is
  `MiniGame`/`taixiuPlugin`, distinct from the betting-mini products. Put concrete
  classes under `.../message/taixiu/` and fixtures under
  `src/test/resources/messages/taixiu/`.
- **Zone resolution test currently asserts the opposite.** `EnvironmentZoneResolutionTest`
  pins `TAI_XIU` → card/Simms today. AD-7 flips that; the test update is part of
  Phase 5, not an afterthought.
- **Logging levels** (CLAUDE.md): one INFO init line; per-round decision and
  per-message detail at DEBUG; MDC preserved via the inherited `mdcConsumer`/
  `mdcSupplier` wraps (no new wrapping needed since the bot reuses the inherited
  scenario).

## Open Items

- **OI-1 — RESOLVED (2026-06-24): captured payloads in hand.** Real frames for
  the `MiniGame`/`taixiuPlugin` product are at `/Users/gleb/IdeaProjects/Bot/TaiXiuMessages/`
  (subscribe/start/bet/end). Phase 2 proceeds for this product; remaining products
  throw "not yet implemented" in `resolveTaiXiu` (AD-4).
- **OI-2 — RESOLVED (2026-06-24): CMDs are fixed literals.** subscribe **1005**,
  bet **1000** (outbound); startGame **1002**, endGame **1004** (inbound). The bet
  carries Tai-Xiu-specific `eid`/`aid`, so a dedicated `TaiXiuRequest` is required
  (AD-12), not betting-mini `Request` reuse.
- **OI-5 (residual, non-blocking): `updateBet` cmd unknown.** No updateBet frame
  was captured. BettingMini uses updateBet (3002) to track the pot mid-round, but
  Tai Xiu's bot reacts primarily to start/end, so updateBet is **optional for v1**
  and is omitted from `TaiXiuMessageTypes` registrations. Capture and add it later
  if mid-round pot tracking is needed. **Countdown source RESOLVED (user
  2026-06-24):** the inbound subscribe response (`SubscribeResponse.js`, cmd 1005)
  carries all round-timing fields — `tFB`=50000 (timeForBetting), `tFBB`=3000,
  `tFP`=19000, `rmT` (remaining), `gS` (game state). The bot drives its countdown
  from `tFB` exactly like BettingMini; no `Start.js` time field is needed.
- **OI-6 (residual, non-blocking): `aid` meaning.** The bet's `aid` (sample `1`)
  is uncertain — likely an account/area id. v1 defaults `aid=1`. Confirm with the
  user; if it is bot/account-specific it must be sourced per bot.
- **OI-7 — RESOLVED (user 2026-06-24):** winnings = the **`G`** field directly
  (gold win money); money lost / effective wagered = **`gB − gR`**. Do NOT compute
  `GX − gB` (that only coincidentally equals `G` in the fully-refunded sample;
  `GX = gR + G`). Phase 4 accounting + fixtures must be corrected to use `G`. The
  coin-currency `c*`/`C*` fields are 0 for gold-betting bots and are out of scope
  for v1 (gold only).
- **OI-3 — RESOLVED (2026-06-24, user):** Tai Xiu runs on the **mini WS zone**
  (like BettingMini/SLOT). AD-7's `resolveZoneName` change to treat `TAI_XIU` as
  mini is confirmed; proceed with Phase 5 as planned.
- **OI-4 — RESOLVED (2026-06-24, user):** Tai Xiu **reuses the betting strategy
  family** (Martingale/Paroli/etc.) per AD-6, and `StrategyController.list` returns
  them for `TAI_XIU`. Proceed with Phase 6 as planned.
- **md5 per product** (AD-10): whether each captured Tai Xiu product has a
  distinct md5 StartGame variant. Resolved per captured payload in Phase 2.
- **Multi-product rollout** (out of v1 scope): v1 implements the captured
  product(s) only; remaining products throw "not yet implemented" in
  `resolveTaiXiu`, matching the betting-mini resolver's current state.

## Verification

These run on staging after deploy. The Tai Xiu feature has on-server
verification beyond the universal smoke test.

1. **App boots with the Tai Xiu validator and strategy listing wired.** After
   deploy:
   ```
   curl -s http://localhost:8080/actuator/health
   ```
   Expect HTTP 200, body `{"status":"UP"}`. Confirm the validator factory boot
   line still lists all five game types:
   ```
   grep "GameConfigValidatorFactory initialized" <app-log>
   ```
   Expect a line whose key set includes `TAI_XIU` (count 5).

2. **Tai Xiu strategies are listed.**
   ```
   curl -s 'http://localhost:8080/api/v1/strategy/?gameType=TAI_XIU'
   ```
   Expect HTTP 200 and a **non-empty** JSON array equal to the
   `gameType=BETTING_MINI` response (the betting strategy ids).

3. **A TAI_XIU game can be created via REST.**
   ```
   curl -s -X POST http://localhost:8080/api/v1/game/ -H 'Content-Type: application/json' \
     -d '{"name":"TaiXiuMiniGameTest","gameType":"TAI_XIU","productCode":"<captured product code>","gameId":<gid>,"pluginName":"taixiuPlugin"}'
   ```
   (`pluginName` is `taixiuPlugin`, zone `MiniGame`, per the captures.)
   Expect HTTP 200/201 echoing `gameType:TAI_XIU`. A subsequent
   `GET /api/v1/game/{id}` returns the same.

4. **A Tai Xiu bot group passes config validation and starts; bots reach
   CONNECTION_AUTHENTICATED.** Create a small group (e.g. 2 bots) bound to the
   TAI_XIU game with valid grid config, then:
   ```
   curl -s -X POST http://localhost:8080/api/v1/bot-group/{id}/start
   curl -s http://localhost:8080/api/v1/bot-group/{id}/health
   ```
   Expect HTTP 200 and `BotGroupHealthDTO` showing bots in
   `CONNECTION_AUTHENTICATED` (proves the fixed-CMD subscribe response was matched
   and `onSubscribe` fired). In logs, expect a RECEIVED subscribe frame at the
   **fixed** Tai Xiu CMD per bot before any bet. Also create a group with an
   invalid grid config and assert the create/update returns HTTP 400 (Tai Xiu
   validation is now active, AD-8).

5. **Rounds drive bets and end-game accounting.** After the group runs through a
   few rounds:
   ```
   curl -s 'http://localhost:8080/actuator/metrics/bot_bets_placed_total?tag=gameType:TAI_XIU'
   curl -s 'http://localhost:8080/actuator/metrics/bot_bet_amount_total?tag=gameType:TAI_XIU'
   ```
   Expect `bot_bets_placed_total{gameType=TAI_XIU} > 0` and
   `bot_bet_amount_total{gameType=TAI_XIU} > 0`. In logs, expect StartGame/EndGame
   frames at the fixed Tai Xiu CMDs for the bots.

6. **Winnings counter populated (RTP feeds).**
   ```
   curl -s 'http://localhost:8080/actuator/metrics/bot_winnings_total?tag=gameType:TAI_XIU'
   ```
   Expect a measurement value `>= 0` (series exists; `> 0` once any round wins).
   `bot_winnings_total{gameType=TAI_XIU} / bot_bet_amount_total{gameType=TAI_XIU}`
   should yield a plausible RTP (< 100% over a sustained window). **Refund-aware
   sanity check (AD-11):** because `bot_bet_amount_total` records *effective* stake
   (`gB−gR`), a window dominated by heavily-refunded rounds should still show a
   sane RTP, NOT a near-0% RTP that a gross-stake denominator would produce. If RTP
   looks implausibly low, suspect the refund netting is not applied.

7. **No regression in BettingMini and no wedged Tai Xiu bots.**
   ```
   grep -E "ERROR|marking DEAD" <app-log> | grep -iE "tai|xiu"
   ```
   Expect no Tai Xiu ERROR/DEAD lines during a healthy 5-minute run. Spot-check an
   existing BettingMini group still bets normally (the Phase-1 seam refactor must
   not have regressed it). Re-verify the co-located observability stack
   (Grafana/Prometheus/Loki) is still UP per the bot-1 single-compose smoke note.
