# SLOT_MACHINE_BOT

## Goal

Add a brand-new `SLOT` game type to the bot-manager. Unlike the existing
round-based `BettingMiniGameBot` (which reacts to a shared server clock:
subscribe → StartGame(sid) → BET phase with countdown → EndGame(sid)), a slot bot
is **request/response per spin**: it decides on its own cadence when to spin,
sends a spin (`cmd:1302`), and receives its own private result back immediately.
There is no shared round clock, no BET/PAYOUT phase, no countdown, no
StartGame/UpdateBet/EndGame broadcast, and no broadcast watchdog. v1 targets the
TIP product/env but the implementation is product-agnostic: the slot message
classes, CMDs, and protocol are identical across all brands, differentiated only
by `gid` (env-scoped game id). The number of winlines and the allowed bet
values are **sourced from the subscribe (`cmd:1300`) response at runtime**, not
from Game config. This closes the CLAUDE.md backlog item "Configurable betting
values per game" **in spirit** — but via server-sourced values, not config — so
that backlog note should be updated to reflect the server-driven approach.

## Findings — Current State

### Lifecycle & accounting base (`Bot`)
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/Bot.java`
  is the abstract base. Subclass contract: `initializeSubclass()` (Bot.java:177),
  `botBehaviorScenario()` (Bot.java:590), `onStart()` (Bot.java:611). Lifecycle
  (`initialize`, `start`, `restart`, `cleanup`, reconnect loop) is fully reusable
  as-is. `creditBalance(long)` (Bot.java:592) decrements `expectedCurrentBalance`
  and bumps the local `totalBetsPlaced`/`totalBetAmount` accumulators.
- Reconnect, MDC-wrap helpers (`mdcConsumer`/`mdcSupplier`/`mdcWrap`,
  Bot.java:516-586), and `beforeReconnect()` hook (Bot.java:495) are inherited
  unchanged. The library reconnect loop calls `start()` again, which calls
  `onStart()` — so the subscribe + spin scenario is re-registered on reconnect.
- `markConnectionAuthenticated()` (Bot.java:301) is how the bot leaves
  CONNECTING/AUTHENTICATING_CONNECTION and reaches CONNECTION_AUTHENTICATED; the
  betting bot calls it from `onSubscribe`. Slots must do the same on subscribe
  response.
- `lastRoundWinnings` (Bot.java:91) is the per-result winnings field surfaced on
  `BotHealthDTO`. Slots will write it per spin result.

### Round-based template (`BettingMiniGameBot`)
- `/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`.
  The `sendAsync` pattern (BettingMiniGameBot.java:511-516) is the exact pattern
  to adapt: `.messageSupplier(mdcSupplier(...)).mode(INFINITE).condition(mdcSupplier(...)).interval(ms, MILLISECONDS)`.
- The **pendingDecision park-and-pop pattern** (BettingMiniGameBot.java:107,
  453-471, 393-430) exists because the scenario engine throws if the supplier
  returns null (`SendAsign.processInternal:135` referenced in the javadoc). The
  condition computes `strategy.decide(ctx)` and parks an `Optional<BetDecision>`;
  the supplier pops it. Slots must reuse this exact pattern.
- Scenario construction (BettingMiniGameBot.java:486-519): builds a per-bot
  `ObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES=false`, calls
  `mapper.registerSubtypes(messageTypes.getTypeRegistrations(offset, md5))`, then
  `pipeline(...).waitFor(...).send(request::subscribe).waitForMessage(cmd(...).and(typeOf(RECEIVED))).onMessage(...)...sendAsync(...).onMessage(...).compile()`.
- `onEndGame` marker-interface dispatch (BettingMiniGameBot.java:267-320) is the
  accounting template: `instanceof HasBotWinnings` → `winningsFor` →
  `lastRoundWinnings`/`incBotWinnings`; `HasBetTotals` → `incBetsPlaced`. Slots
  reuse `HasBotWinnings`; bet totals are simpler (single `b` per spin).
- The betting bot's `watchdogScheduler`/`countdown` machinery
  (BettingMiniGameBot.java:74-79, 180-216) is round-clock-specific and is **NOT**
  carried into slots (Architecture Decision below).

### Message infrastructure
- `GameMessageTypes`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypes.java`)
  is hardcoded to the betting-mini lifecycle: `SUBSCRIBE_CODE=3000`,
  `UPDATE_BET_CODE=3002`, `START_GAME_CODE=3005`, `END_GAME_CODE=3006`
  (GameMessageTypes.java:18-21) and four typed accessors. `getTypeRegistrations(offset, md5)`
  (GameMessageTypes.java:56) computes CMD = CODE + offset.
- `GameMessageTypesResolver.resolve(ProductCode)`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypesResolver.java:21`)
  is keyed only on `ProductCode` and returns betting-mini providers
  (Bom/Tip/Nohu). It has no notion of GameType.
- `BettingMiniMessage`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/BettingMiniMessage.java:14-25`)
  is the Jackson polymorphic base: `@JsonTypeInfo(use=NAME, include=EXISTING_PROPERTY, property="cmd", visible=true)` extends
  `Body`. Concrete subtypes register against the string CMD value. Slots need an
  analogous `SlotMessage` base.
- Marker interfaces: `HasBotWinnings.winningsFor(userName)`, `HasJackpot`,
  `HasBetTotals` in `.../message/`. `TipEndGameMessage`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/g3/tip/TipEndGameMessage.java`)
  implements all three. The `@JsonCreator`/`@JsonProperty` + `@Getter/@Setter` +
  nested-`static`-class convention (TipSubscribeMessage.java:107-202) is the
  message-class style to follow.

### Array framing of the spin response — RESOLVED (no custom code needed)
- The captured spin response is array-framed `[5, {...}]`. The websocket-parser
  library handles this transparently:
  - `Qualifier.findCmdDirectPath` (extracted to
    `/tmp/wsp/com/vingame/websocketparser/scenario/matchers/Qualifier.java:325-329`)
    expects exactly `[type, {cmd:123, ...}]` and reads `rootNode.get(1).get("cmd")`.
  - `ActionResponseMessage.deserialize(mapper, node, dataClass)`
    (`.../message/response/ActionResponseMessage.java:128-146`) requires
    `node.isArray()`, reads `node.get(0)` as `MessageCategory.fromTypeNumber`
    and `node.get(1)` as the typed body.
  - `MessageCategory.ACTION_RESPONSE = 5` — the `5` prefix on the spin response
    is exactly the ACTION_RESPONSE category. So `[5, {"cmd":1302, ...}]` parses
    cleanly: category 5, body deserialized to the class registered against
    `"1302"`. **No unwrap shim is required** — this is the same code path the
    betting bot's inbound messages use today.

### Request builder
- `Request`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/request/Request.java`)
  is betting-mini-specific: every builder adds `cmdPrefix + 3000/3002/...`. It
  takes `(pluginName, zoneName, cmdPrefix)`. Slots use fixed cmds (no prefix
  arithmetic), so a separate `SlotRequest` is cleaner than overloading this.
- Outbound message classes (`Bet`, `SubscribeToLobbyMessage`) extend
  `ActionRequestMessage` and wrap a `Body` (extends `Body` from the lib, which
  carries `cmd`). `Bet.BetData` (Bet.java:20-37) shows the inner-Body pattern
  with `@Getter/@Setter` and final fields serialized to JSON.

### BotFactory wiring
- `BotFactory.createBot`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java:85-165`)
  resolves clients, zoneName, builds a fresh `ClientFactory`, resolves
  `GameMessageTypes` via `GameMessageTypesResolver.resolve(productCode)`
  (BotFactory.java:135), then `switch (game.getGameType())`. `case SLOT` currently
  throws "Game type not yet implemented" (BotFactory.java:147-148). The
  `BettingStrategyFactory` is already a constructor-injected field
  (BotFactory.java:65,71).

### Game entity & DTO/mapper
- `Game`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/model/Game.java`)
  has `gameId` (Integer, Game.java:38) — this is the natural home for `gid`.
  `optionAffinities`/`numberOfOptions`/`bettingOptions` (Game.java:51-90) are
  betting-mini concepts and do **not** map to slots. `offset`/`md5` are
  betting-mini-only too. **Slots need no new Game fields** beyond reusing
  `gameId` as `gid` (winline count and allowed bet values are server-sourced
  from the subscribe response — see AD-8/AD-11).
- `GameDTO`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/dto/GameDTO.java`)
  and `GameMapper`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/game/mapper/GameMapper.java`)
  are hand-written MapStruct `default` methods (not generated) — easy to extend.
  `@JsonInclude(NON_NULL)` on the DTO means new optional slot fields won't break
  betting-mini responses.

### Strategy framework
- `BettingStrategy.decide(BetContext) -> Optional<BetDecision>` +
  `onRoundEnd(RoundResult)`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/BettingStrategy.java`).
  `BetDecision` is a `record(int optionId, long amount)` — the `optionId` field
  is meaningless for slots (no option). `BetContext` is a record carrying
  `memory, behavior, game, currentBalance, currentRound, rng` — `currentRound`
  (`RoundState`) and `memory` (`BotMemory`) are round-based concepts that do not
  apply to slots.
- `BettingStrategyFactory`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/BettingStrategyFactory.java`)
  discovers `@StrategyImpl(StrategyId)`-annotated prototype beans at startup
  into an `EnumMap<StrategyId, Class>`. `StrategyId`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/strategy/StrategyId.java`)
  is a single enum currently holding only betting-mini strategies. `StrategyController`
  (`.../strategy/controller/StrategyController.java`) lists `StrategyId.values()`
  for the group-form picker — a flat enum would mix slot and betting strategies
  in one list.

### Test harness convention
- `BettingMiniGameBotTipDispatchTest`
  (`/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/bot/core/BettingMiniGameBotTipDispatchTest.java`)
  shows the deterministic pattern: build a bot with mocked clients/metrics,
  reflectively seed balance, deserialize a JSON fixture through the registered
  `ObjectMapper`, wrap it in `ActionResponseMessage<>(ACTION_RESPONSE, msg)`, and
  reflectively invoke the private `onX` handler, then verify mock interactions in
  order. This is the harness the slot tests mirror.

## Per-aspect readiness / mapping

| Aspect | Status | Notes |
|---|---|---|
| Array framing of `[5,{...}]` spin response | **ready** | Library `ActionResponseMessage.deserialize` + `Qualifier.findCmdDirectPath` already handle `[type,{cmd,...}]`. Category 5 = ACTION_RESPONSE. No shim. |
| `Bot` lifecycle / reconnect / MDC | **ready** | Inherited unchanged; reconnect calls `start()`→`onStart()` which re-registers the subscribe+spin scenario. |
| `sendAsync` cadence + pendingDecision park/pop | **ready** | Copy pattern from `BettingMiniGameBot`. |
| Balance accounting (`creditBalance`, `expectedCurrentBalance`) | **ready** | Reused; spin debit on send, winnings credit on result. |
| Winnings metric via `HasBotWinnings` | **ready** | `incBotWinnings`, `lastRoundWinnings` reused; `winningsFor` = `sum(wls[].crd)`. |
| `GameMessageTypes` for slot | **partial** | Need a slot-specific provider interface + resolver split on GameType. |
| Slot message classes (incl. `SlotSubscribeResponse` 1300) | **blocked-on-build** | Placeholders are comment-only; must become real Jackson types. Subscribe-response payload now captured (see AD-11). |
| `Game` slot config (`gid` only) | **partial** | `gameId` exists for `gid`; **no other slot fields** (winline count + bet values are server-sourced). DTO/mapper unchanged. |
| `Request`/outbound spin+subscribe | **partial** | New `SlotRequest` + `SlotSpin`/`SlotSubscribe` outbound classes. |
| `BotFactory` `case SLOT` | **blocked** | Currently throws; wire after the above. |
| Slot strategy family | **partial** | New parallel interface + enum + factory (see AD-9). `SlotBetContext` carries the server-sourced allowed bet-value set. |
| Subscribe (1300) response modeled + waited on | **ready (decided)** | `SlotSubscribeResponse` modeled; scenario waits on cmd 1300 before spinning (AD-11/AD-12). |
| Bet totals metric (`HasBetTotals`) | **ready** | Single `b` per spin → `incBetsPlaced(1, b)`. |
| Watchdog (broadcast) | **out of scope** | No broadcast clock; see AD-5. |

## Architecture Decisions

**AD-1. Fixed CMD constants, no offset arithmetic.** Slot CMDs are global:
`SUBSCRIBE = 1300`, `SPIN = 1302` (spin request and spin result share `1302`).
These are hardcoded constants on the slot message-types provider. The betting-mini
`CODE + offset` scheme (`GameMessageTypes.getTypeRegistrations(offset, md5)`) is
**not** used for slots; the slot provider's `getTypeRegistrations()` takes no
offset and registers against the literal cmd strings `"1300"` / `"1302"`.

**AD-2. `gid` lives on `Game.gameId`.** The existing `Game.gameId` (Integer)
field is the slot `gid`. No new field for the id itself. The slot bot reads
`configuration.getGame().getGameId()` and puts it on every subscribe/spin
message. `gid` is env-scoped (same numeric gid means different games in different
envs) — this is already true of `gameId` since `Game` is per-product/per-env.

**AD-3. Slot message classes are product-neutral, single copy.** Create one set
under a new package
`com.vingame.bot.domain.bot.message.slot` (not under `g3/tip` etc.). Replace the
three comment-only placeholders currently in
`com.vingame.bot.domain.bot.message.request.slot`. The placeholder package
(`...message.request.slot`) is deleted; the real outbound request classes live in
`...message.request` next to `Bet`/`SubscribeToLobbyMessage`, and the inbound
response classes live in `...message.slot`.

**AD-4. Slot `GameMessageTypes` is a separate interface; resolver splits on
GameType first, ProductCode second.** Introduce `SlotMessageTypes` (a new
interface, not extending the betting-mini `GameMessageTypes`) with its own fixed
cmd constants and two accessors: `subscribeResponseType()` and `spinResultType()`,
plus a no-arg `getTypeRegistrations()`. Refactor `GameMessageTypesResolver` to a
two-arg `resolve(GameType, ProductCode)`:
- `GameType.SLOT` → return the single product-neutral `SlotMessageTypesImpl`
  (ignores ProductCode).
- `GameType.BETTING_MINI` (and others currently) → existing ProductCode switch,
  returning a betting-mini `GameMessageTypes`.

Because the two providers have disjoint shapes, the resolver returns `Object` is
ugly; instead give each its own resolve method to keep types clean:
`resolveBettingMini(ProductCode)` (rename of the current `resolve`) and
`resolveSlot()` (no args, returns `SlotMessageTypes`). `BotFactory` calls the one
matching the `GameType` branch it is already in. This is the cleanest design: no
marker-supertype gymnastics, each call site is statically typed to the provider
it needs.

**AD-5. No watchdog, no countdown, no round state for slots.** There is no
broadcast clock to detect silence against — a slot bot that stops getting results
is a genuinely different failure mode (its own spin not answered), and the
existing WS-disconnect reconnect path (`Bot.onWsDisconnected`) already covers a
dead connection. v1 ships **no slot watchdog**. The "no spin result for N
seconds" detector is explicitly **Open Item** (deferred), not v1.

**AD-6. One-spin-in-flight: ENFORCE.** `canSpin()` returns false while a spin is
outstanding. The bot holds an `AtomicBoolean spinInFlight`. The `sendAsync`
condition gates on `!spinInFlight.get() && sufficientBalance`; the supplier sets
`spinInFlight=true` when it builds the spin; `onSpinResult` clears it. Rationale:
the captured protocol is strictly request→response per spin, and firing a second
spin before the first returns risks server-side rejection and breaks the
bet→result balance correlation. A spin that never gets a response would otherwise
wedge the bot — mitigated by reusing the WS reconnect path (a truly dead socket
fires `onWsDisconnected`, which calls `beforeReconnect` where we reset
`spinInFlight=false`). A *silent* unanswered spin on a live socket is the deferred
Open Item.

**AD-7. Accounting: debit on send, credit on result.** On spin send,
`creditBalance(b)` debits `b` (and bumps the local sent-counters, mirroring the
betting bot). On spin result, route through the same marker-interface dispatch as
`onEndGame`: `SlotSpinResultMessage implements HasBotWinnings` with
`winningsFor(userName) = sum(wls[].crd)` (winnings are not net — the `b` was
already debited; winnings are the gross credit back). Set `lastRoundWinnings`,
call `incBotWinnings(sum)` (guarded `> 0`), add `sum` back to
`expectedCurrentBalance`. Implement `HasBetTotals` too:
`betCountFor = 1`, `betAmountFor = b`, so the existing
`incBetsPlaced(1, b)` path feeds `bot_bets_placed_total`/`bot_bet_amount_total`
consistently with betting-mini. This makes RTP (`bot_winnings_total /
bot_bet_amount_total`) work for slots with zero new metric code.

**AD-8. v1 bets the full winline set every spin; winline count is server-sourced.**
The number of winlines comes from the subscribe (`cmd:1300`) response —
`ls.size()` (in the 1300 response, `ls` = the winline DEFINITIONS, a list of
`{lid, poss[]}`; 25 in the captured payload). It is **NOT** a Game-config field.
The spin request's `ls` field is the selected winline indices `[0..numLines-1]`,
built at spin time from the server-provided count. No winline-subset variation in
v1 (always all lines). If the subscribe response has not been received yet, the
bot does not spin (gated by `canSpin()` — see AD-12). Note the `ls`-name clash:
in the **1300 response** `ls` = winline definitions (`List<{lid, poss[]}>`,
count matters); in the **1302 spin request** `ls` = selected winline indices
(`int[]`). These are different fields on different message classes — no conflict,
but call it out so Dev does not conflate them.

**AD-9. Separate slot strategy family — parallel interface, not shared.** The
betting `BettingStrategy`/`BetContext`/`BetDecision`/`StrategyId` are saturated
with round/option concepts (`optionId`, `currentRound`, `memory`,
`onRoundEnd(RoundResult)`) that have no slot meaning. Forcing slots through them
would mean passing dummy `optionId=-1` and a fake `RoundState` — leaky and
fragile. Instead introduce a parallel, minimal family:
- `SlotStrategy` interface: `long chooseBet(SlotBetContext ctx)` (returns the bet
  amount from the **server-provided** allowed set; no skip — cadence/in-flight
  gating is the bot's job, the strategy only picks the amount). No `onResult` hook
  in v1 (both v1 strategies are stateless).
- `SlotBetContext` record: `(List<Long> allowedBetValues, int numLines, long currentBalance, Random rng)`.
  Both `allowedBetValues` (= `Js[].b` from the 1300 response) and `numLines`
  (= `ls.size()`) are server-sourced, captured by the bot in `onSubscribe`.
- `SlotStrategyId` enum: `FIXED` (always the smallest/first allowed value) and
  `RANDOM` (uniform pick over `allowedBetValues`).
- `@SlotStrategyImpl(SlotStrategyId)` annotation + `SlotStrategyFactory`
  (prototype-bean discovery, mirroring `BettingStrategyFactory`). `BotFactory`
  gets it injected and wires it onto the slot bot.

The two v1 strategies satisfy decision 8 of the brief: (a) `FIXED` = fixed
amount, (b) `RANDOM` = uniform-random from the allowed set. The lever is bet
**amount** from the discrete allowed set. `BotConfiguration.strategyId` is a
betting `StrategyId`; rather than overload it, the slot bot reads its slot
strategy from a new optional `BotConfiguration.slotStrategyId` field (null →
default `FIXED`). The group-form strategy picker (`StrategyController`) is
extended later/out of v1 scope — v1 assigns via the new field with a default.

**AD-10. Slot strategy assignment is out of v1 scope at the group-mix level.**
The fill-to-target `strategyMix` assignment in `BotGroupBehaviorService` is
betting-specific. v1 slot bots default to `SlotStrategyId.FIXED` unless
`BotConfiguration.slotStrategyId` is set. Wiring slot strategies into the group
strategy-mix UI is an Open Item.

**AD-11. The subscribe (`cmd:1300`) response IS modeled and waited on.** The
captured 1300 response is the source of both the winline count and the allowed
bet-value set. Model `SlotSubscribeResponse` from this payload:
```json
{ "as": false, "gid": 204,
  "eSC": {"iAc": false, "mEC": 500, "event": "HALLOWEEN", "eNPG": 50},
  "ae": false, "fss": [],
  "ls": [ {"lid":0,"poss":[1,1,1,1,1]}, ... {"lid":24,"poss":[0,2,0,2,0]} ],
  "Js": [ {"b":500,"gid":204,"J":3355245,"aid":1}, {"b":1000,...}, {"b":2000,...},
          {"b":5000,...}, {"b":10000,"gid":204,"J":50527500,"aid":1} ],
  "taxes": [ {"tax":0,"aid":1}, {"tax":0,"aid":2} ],
  "cmd": 1300, "lss": [] }
```
Model contract:
- `ls` = winline DEFINITIONS, `List<{lid:int, poss:int[]}>`. **`ls.size()` =
  number of winlines** (25 here). DIFFERENT from `ls` in the 1302 spin request
  (selected winline indices `int[]`) — see AD-8.
- `Js` = jackpot tiers keyed by bet amount; **`Js[].b` = the allowed bet-value
  set** ({500,1000,2000,5000,10000}). Model `Js` as `List<{b:long, gid:int,
  J:long, aid:int}>`.
- `eSC`, `taxes`, `as`, `ae`, `fss`, `lss`, and tier `J` may be modeled loosely
  or ignored for v1, but must deserialize without failing
  (`FAIL_ON_UNKNOWN_PROPERTIES` is already false).

**AD-12. The scenario waits for and parses the 1300 response before spinning.**
On `onSubscribe`, the bot stores `numLines = ls.size()` and `allowedBetValues =
Js[].b` (sorted ascending). `canSpin()` additionally gates on
"subscribe-response received" (i.e. `numLines > 0 && allowedBetValues != null`)
so the **first spin cannot fire before these server-sourced values are known**.
Scenario shape:
`send(subscribe)` → `waitForMessage(cmd 1300)` →
`onMessage(SlotSubscribeResponse, onSubscribe)` →
`sendAsync(spin, INFINITE, ~few-sec interval, condition=canSpin())` →
`onMessage(SlotSpinResultMessage, onSpinResult)`.

**AD-13. Balance gate is `chosenBet * numLines`.** A spin stakes the chosen bet
on each of the `numLines` winlines, so the per-spin cost is `chosenBet *
numLines` (e.g. `10000 * 25 = 250000`). `canSpin()` requires
`expectedCurrentBalance >= chosenBet * numLines`, with `numLines` taken from the
subscribe response. The per-spin debit `creditBalance(b)` debits the staked
amount `b` (each spin is one request→one response — see AD-14).

**AD-14. Free spins ignored for v1; one-spin-in-flight gate is sound.** The
server does NOT push unsolicited free-spin results — every spin is strictly one
request → one response. The `spinInFlight` AtomicBoolean gate (AD-6) is therefore
valid: no out-of-band result can arrive to confuse it. `b` is treated as the
staked amount for each spin for accounting. Free-spin / bonus-round handling is
deferred (Open Item).

## Plan

### Phase 1 — Slot message classes + Jackson registration
Goal: real, deserializable slot message types; the `[5,{...}]` fixture round-trips
through a registered `ObjectMapper`.

Create:
- `.../message/slot/SlotMessage.java` — abstract base mirroring
  `BettingMiniMessage` (`@JsonTypeInfo(use=NAME, include=EXISTING_PROPERTY,
  property="cmd", visible=true)`, extends lib `Body`, `protected SlotMessage(int cmd)`).
- `.../message/slot/SlotSubscribeResponse.java` — extends `SlotMessage`,
  `@JsonCreator` over the captured 1300 response (AD-11). Fields: `cmd` (1300),
  `gid` (int), `ls` (`List<WinlineDef>` where nested
  `WinlineDef{lid:int, poss:int[]}`), `Js` (`List<JackpotTier>` where nested
  `JackpotTier{b:long, gid:int, J:long, aid:int}`). Expose two derived accessors
  used by the bot: `numLines()` = `ls == null ? 0 : ls.size()` and
  `allowedBetValues()` = `Js` mapped to `b`, sorted ascending. `eSC`, `taxes`,
  `as`, `ae`, `fss`, `lss` are tolerated as unknowns (mapper config) or modeled
  loosely; not required for v1. Implements no marker interfaces.
- `.../message/slot/SlotSpinResultMessage.java` — extends `SlotMessage`,
  implements `HasBotWinnings` and `HasBetTotals`. Fields from the captured
  payload: `b` (long), `gid` (int), `sbs` (`List<Integer>`), `sid` (long), `bw`
  (boolean), `hFS` (boolean), `fss` (int), `wls` (`List<WinLine>`), and other
  observed scalars (`iJ, hMG, J, mX, as, mX` etc.) captured defensively as
  needed. Nested `static class WinLine` with `@JsonCreator` over
  `crd` (long), `lid` (int), `sbIds` (`List<Integer>`), `sbN` (String),
  `sbId` (int), `iJ`, `img`, `fs`. Implement:
  - `winningsFor(String) = wls == null ? 0 : sum(wls[].crd)` (sum total winnings).
  - `betAmountFor(String) = b`; `betCountFor(String) = 1`.
- Delete the three comment-only placeholder files under
  `.../message/request/slot/` and remove that empty package.

Add test fixtures:
- `src/test/resources/messages/slot/spinResult.json` — the captured `[5,{...}]`
  payload from the brief.
- `src/test/resources/messages/slot/subscribeResponse.json` — the captured 1300
  response from AD-11 (array-framed `[5,{...}]`), with the full 25-entry `ls` and
  5-entry `Js`.

Verification:
- `mvn -q -o test-compile` compiles.
- New unit test `SlotMessageDeserializationTest`: register
  `new SlotMessageTypesImpl().getTypeRegistrations()` (created in Phase 2; if
  Phase 1 lands first, register the two `NamedType`s inline), `mapper.readValue`
  the `spinResult.json` into `SlotMessage`, assert it is a
  `SlotSpinResultMessage`, assert `b==500`, `winningsFor("x")==6000`
  (1000+5000 from the brief's `wls`), `betAmountFor("x")==500`, `betCountFor==1`.
  Also deserialize `subscribeResponse.json` into `SlotMessage`, assert it is a
  `SlotSubscribeResponse`, `numLines()==25`, and
  `allowedBetValues()==[500,1000,2000,5000,10000]`.

### Phase 2 — Slot `GameMessageTypes` provider + resolver split
Goal: a typed, product-neutral provider and a GameType-aware resolver.

Create:
- `.../message/SlotMessageTypes.java` — interface with fixed cmd constants
  (`int SUBSCRIBE_CMD = 1300; int SPIN_CMD = 1302;`), accessors
  `Class<? extends SlotMessage> subscribeResponseType()` and
  `spinResultType()`, and a default
  `NamedType[] getTypeRegistrations()` registering both classes against
  `String.valueOf(SUBSCRIBE_CMD)` / `String.valueOf(SPIN_CMD)`.
- `.../message/slot/SlotMessageTypesImpl.java` — returns
  `SlotSubscribeResponse.class` / `SlotSpinResultMessage.class`.

Modify:
- `GameMessageTypesResolver`: rename current `resolve(ProductCode)` →
  `resolveBettingMini(ProductCode)`; add `static SlotMessageTypes resolveSlot()`
  returning `new SlotMessageTypesImpl()`. (Leave a thin deprecated `resolve`
  delegating to `resolveBettingMini` only if other callers exist — grep shows
  only `BotFactory.java:135` calls it, so just rename and update that call site
  in Phase 5.)

Verification:
- `mvn -q -o test-compile`.
- Unit test asserts `resolveSlot().getTypeRegistrations()` has length 2 with the
  `"1300"`/`"1302"` names, and the Phase 1 deserialization test now uses the
  provider's registrations.

### Phase 3 — `Game` slot config (`gid` only)
Goal: confirm a SLOT game persists with `gameType=SLOT` and `gameId` = `gid`.
**No new Game fields, no DTO/mapper changes** — winline count and allowed bet
values are server-sourced from the 1300 response (AD-8/AD-11), not config.

Work:
- `Game` (`.../game/model/Game.java`): **no code change**. Add a doc comment on
  `gameId` noting it carries the slot `gid` for SLOT games. (If a no-op edit is
  undesirable, this phase is effectively a verification-only confirmation that
  `gameType=SLOT` + `gameId` already round-trip through the existing DTO/mapper.)
- `GameDTO`/`GameMapper`: **no change**. The existing `gameId` and `gameType`
  fields already map.

This phase collapses to a near-no-op because decisions 2 and 3 moved winline
count and bet values out of config and onto the subscribe response. It exists
only to confirm the existing CRUD already supports a SLOT game shaped as
`{gameType:SLOT, gameId:<gid>, productCode, pluginName}`.

Verification:
- `mvn -q -o test-compile`.
- Mapper unit test (no new mapper code): round-trip a slot `GameDTO`
  (gameType=SLOT, gameId=204, productCode=P_116, pluginName="Tip") → entity →
  DTO; assert `gameType` and `gameId` preserved.

### Phase 4 — `SlotMachineBot` scenario + accounting
Goal: a working slot bot (subscribe → spin loop → result), minus strategy
(stubbed to a fixed amount) and minus factory wiring.

Modify `.../bot/core/SlotMachineBot.java` (fill the skeleton):
- Fields: `@Setter SlotMessageTypes messageTypes;` `SlotRequest request;`
  `int gid;`
  `volatile int numLines = 0;` (server-sourced in `onSubscribe`)
  `volatile List<Long> allowedBetValues;` (server-sourced in `onSubscribe`,
  null until 1300 received)
  `AtomicBoolean spinInFlight = new AtomicBoolean(false);`
  `AtomicReference<Optional<Long>> pendingBet = new AtomicReference<>(Optional.empty());`
  `Random rng;` `@Setter SlotStrategyFactory slotStrategyFactory;`
  `SlotStrategy strategy;` (Phase 6 wires the factory; Phase 4 defaults to a
  fixed-amount inline strategy so the bot is testable standalone).
  Note: `winlines`/`allowedBetValues` are **no longer read from Game** — they are
  populated from the subscribe response (AD-11/AD-12).
- `initializeSubclass()`: read `game.getGameId()` → `gid` (fail loud if null),
  build `SlotRequest`, seed `rng = new Random(userName.hashCode() ^ nanoTime())`,
  log an INFO init line (game, gid, strategy). Winline count and bet values are
  not known yet at init — they arrive with the 1300 response.
- `buildContext(tag, mapper)`: same as betting bot (PipelineContext with timeout,
  client, mapper, tag).
- `botBehaviorScenario()`: build per-bot `ObjectMapper`
  (`FAIL_ON_UNKNOWN_PROPERTIES=false`, `registerSubtypes(messageTypes.getTypeRegistrations())`),
  then:
  ```
  pipeline(buildContext("[Slot][" + name + "]", mapper))
    .waitFor(1_000L)
    .send(request::subscribe)
    .waitForMessage(cmd(SlotMessageTypes.SUBSCRIBE_CMD).and(typeOf(RECEIVED)))
    .onMessage(SlotSubscribeResponse.class, mdcConsumer(this::onSubscribe))
    .sendAsync(buildMessage()
        .messageSupplier(mdcSupplier(spin()))
        .mode(INFINITE)
        .condition(mdcSupplier(spinCondition()))
        .interval(resolveSpinInterval(), MILLISECONDS)
        .build())
    .onMessage(SlotSpinResultMessage.class, mdcConsumer(this::onSpinResult))
    .compile();
  ```
- `onSubscribe(ActionResponseMessage<? extends SlotSubscribeResponse>)`:
  `incBotMessage("subscribe")`; **capture server-sourced config**:
  `numLines = resp.numLines()` and `allowedBetValues = resp.allowedBetValues()`
  (fail loud / WARN + skip if either is empty — a 1300 with no `ls`/`Js` is a
  misconfigured game); then `markConnectionAuthenticated()`, `onNewSession()`
  (balance check + optional deposit — reuse the betting bot's `onNewSession`
  logic, copied; it depends only on `Bot` members).
- `spinCondition()` (Supplier<Boolean>): return false if the subscribe response
  has not been processed yet (`numLines == 0 || allowedBetValues == null`) — this
  gates the **first** spin on AD-12; return false if `spinInFlight.get()`; build
  `SlotBetContext(allowedBetValues, numLines, expectedCurrentBalance, rng)` and
  ask `strategy.chooseBet(ctx)`; require balance ≥ `chosenBet * numLines` (AD-13);
  park the amount in `pendingBet`; return true. (Mirror betting bot's park pattern
  so the supplier never returns null.)
- `spin()` (Supplier<ActionRequestMessage>): pop `pendingBet` (re-derive via
  strategy if cleared by a concurrent `beforeReconnect`, mirroring betting bot);
  set `spinInFlight=true`; `creditBalance(amount)` (debits the staked `b` for the
  spin); build the selected winline indices `ls = [0..numLines-1]`; build and
  return `request.spin(gid, amount, ls)`.
- `onSpinResult(ActionResponseMessage<? extends SlotSpinResultMessage>)`:
  `incBotMessage("spin")`; marker dispatch — `HasBotWinnings`
  → `winningsFor` → `lastRoundWinnings`, `incBotWinnings(w)` (guard `>0`), and
  `expectedCurrentBalance.addAndGet(+w)` (credit winnings back); `HasBetTotals`
  → `incBetsPlaced(1, b)`; finally `spinInFlight.set(false)`. Run extraction
  unconditionally, gate only metric emission on `metrics != null` (match betting
  bot).
- `beforeReconnect()`: `spinInFlight.set(false); pendingBet.set(Optional.empty());`
  Leave `numLines`/`allowedBetValues` as-is (they are re-set on the re-subscribe's
  1300 response and re-gate the first post-reconnect spin); resetting them is
  optional but harmless.
- `onStart()`: `onNewSession()`, register an `OutputPrinter.debugOutputPrinter`
  scenario for cmds `List.of(1300, 1302)` (signature at
  `.../bot/util/OutputPrinter.java:49`), then `addScenario(botBehaviorScenario())`.
- `resolveSpinInterval()`: return a few seconds (e.g. `3_000L`); v1 hardcoded.
- Stub `strategy` in Phase 4 with an inline fixed-amount picker (first allowed
  value) so the bot compiles and tests pass before Phase 6.

Add `.../message/request/SlotRequest.java`:
- Constructor `(String pluginName, String zoneName)` — no cmd prefix.
- `SlotSubscribe subscribe()` → builds `cmd=1300`, `gid`.
- `SlotSpin spin(int gid, long bet, List<Integer> ls)` → builds `cmd=1302`,
  `aid=1`, `b=bet`, `gid`, `ls`.

Add outbound classes `.../message/request/SlotSubscribe.java` and
`SlotSpin.java`, extending `ActionRequestMessage implements CmdAwareMessage`,
wrapping inner `Body` subclasses with the captured field names
(`SlotSpin.Data`: `aid=1`, `b`, `gid`, `ls`; `SlotSubscribe.Data`: `gid`).
Confirm the exact `ActionRequestMessage(zoneName, pluginName, Body)` constructor
(as `Bet`/`SubscribeToLobbyMessage` do).

Verification:
- `mvn -q -o test-compile`.
- `SlotMachineBotSubscribeTest`: deserialize `subscribeResponse.json`,
  reflectively invoke `onSubscribe`. Assert `numLines==25`,
  `allowedBetValues==[500,1000,2000,5000,10000]`, `markConnectionAuthenticated()`
  reached (status `CONNECTION_AUTHENTICATED`), and that `spinCondition()` returns
  false **before** `onSubscribe` and can return true after (given sufficient
  balance).
- `SlotMachineBotSpinAccountingTest` (mirror `BettingMiniGameBotTipDispatchTest`):
  construct bot with mocked clients/metrics, seed balance via reflection, invoke
  `onSubscribe` (to set `numLines`/`allowedBetValues`), then deserialize
  `spinResult.json` and reflectively invoke `onSpinResult`. Assert:
  `incBotMessage("spin")`, `incBotWinnings(6000)`, `incBetsPlaced(1, 500)`,
  `getLastRoundWinnings()==6000`, `expectedCurrentBalance` increased by 6000,
  `spinInFlight` reset to false. A second test with `wls=[]`/`bw=false` asserts
  no `incBotWinnings`, still `incBetsPlaced(1, 500)`.

### Phase 5 — `BotFactory` wiring
Goal: `case SLOT` builds a real bot.

Modify `BotFactory.createBot`:
- The `GameMessageTypes` resolution at BotFactory.java:135 is betting-mini-only;
  move it inside the betting branch (or guard it) so a SLOT game with a product
  that lacks a betting-mini provider doesn't throw. Concretely: in the
  `switch (game.getGameType())`, the `BETTING_MINI` branch calls
  `GameMessageTypesResolver.resolveBettingMini(env.getProductCode())`; the new
  `SLOT` branch calls `GameMessageTypesResolver.resolveSlot()`.
- Replace the `case SLOT` throw (BotFactory.java:147) with:
  ```
  case SLOT -> {
      SlotMachineBot slotBot = new SlotMachineBot();
      slotBot.setMessageTypes(GameMessageTypesResolver.resolveSlot());
      slotBot.setSlotStrategyFactory(slotStrategyFactory);
      yield slotBot;
  }
  ```
  Leave `TAI_XIU, CARD_GAME, UP_DOWN` throwing.
- Inject `SlotStrategyFactory slotStrategyFactory` into the `BotFactory`
  constructor (Phase 6 creates the bean; sequence Phase 6 before merging Phase 5,
  or land Phase 6's factory class first — see ordering note).

Verification:
- `mvn -q -o test`.
- App boots (`mvn -q -o spring-boot:run` smoke locally, or a Spring context test)
  with `SlotStrategyFactory` and `SlotMachineBot` resolvable. A `BotFactory` unit
  test (mock registry/env returning a SLOT game) asserts `createBot` returns a
  `SlotMachineBot` and does not throw.

### Phase 6 — Slot strategy family
Goal: `SlotStrategy` interface + `FIXED`/`RANDOM` implementations + factory.

Create under new package `.../bot/strategy/slot/`:
- `SlotStrategyId.java` enum: `FIXED`, `RANDOM` (with displayName/description,
  mirroring `StrategyId`).
- `SlotStrategy.java` interface: `long chooseBet(SlotBetContext ctx)`.
- `SlotBetContext.java` record: `(List<Long> allowedBetValues, int numLines, long currentBalance, Random rng)`.
  `allowedBetValues` and `numLines` are server-sourced (from the 1300 response),
  populated by the bot in `onSubscribe`.
- `@SlotStrategyImpl(SlotStrategyId)` annotation (mirror `StrategyImpl`).
- `SlotStrategyFactory.java` (`@Component`): discover `@SlotStrategyImpl` prototype
  beans into `EnumMap<SlotStrategyId, Class>`, `create(SlotStrategyId)` returns a
  fresh instance (mirror `BettingStrategyFactory`).
- `FixedBetStrategy.java` (`@Component @Scope("prototype") @SlotStrategyImpl(FIXED)`):
  returns the first (smallest) allowed value.
- `RandomBetStrategy.java` (`@Component @Scope("prototype") @SlotStrategyImpl(RANDOM)`):
  `allowedBetValues.get(ctx.rng().nextInt(allowedBetValues.size()))`.

Modify:
- `BotConfiguration`: add `SlotStrategyId slotStrategyId;` (nullable).
- `SlotMachineBot.initializeSubclass`: build `strategy` via
  `slotStrategyFactory.create(configuration.getSlotStrategyId() != null ?
  configuration.getSlotStrategyId() : SlotStrategyId.FIXED)`; if factory null
  (test seam), default to an inline `FixedBetStrategy` instance (mirror betting
  bot's RandomBehaviorStrategy fallback).
- Replace the Phase-4 inline stub with the real `strategy` field.

Verification:
- `mvn -q -o test`.
- `SlotStrategyFactoryTest`: context loads, `registeredIds()` == {FIXED, RANDOM},
  `create(RANDOM)` returns distinct instances. `RandomBetStrategyTest` with a
  seeded `Random` asserts deterministic pick from `[500,1000,10000]`.
  `FixedBetStrategyTest` asserts it always returns 500.

### Phase 7 — Deterministic spin-stream test
Goal: end-to-end-ish coverage mirroring the betting-mini stream tests, exercising
the condition→supplier→result loop deterministically.

Create `SlotMachineBotSpinStreamTest`:
- Build a bot with a seeded `Random` (inject via a package-private `setRandom`
  test seam on `SlotMachineBot`, mirroring `BettingMiniGameBot.setRandom`).
- First invoke `onSubscribe` with the captured `subscribeResponse.json` so
  `numLines` (25) and `allowedBetValues` ([500,1000,2000,5000,10000]) are set —
  also assert `spinCondition()` returns false before this step (AD-12 gate).
- Drive the loop by hand: call `spinCondition()` supplier → assert true and that
  `pendingBet` is parked; call `spin()` supplier → assert it returns a `SlotSpin`
  with the expected `b` (from the seeded strategy over the server-sourced bet set)
  and `ls == [0..24]` (the server-sourced winline count), and that
  `spinInFlight` is now true; assert `spinCondition()` now returns **false**
  (one-spin-in-flight, AD-6); feed a deserialized `spinResult.json` to
  `onSpinResult`; assert `spinInFlight` clears, balance debited by `b` then
  credited by `sum(wls.crd)`, `incBetsPlaced(1, b)` fired once.
- A multi-spin variant loops N times over a fixture stream and asserts the
  running balance equals `start - Σb + Σwinnings` and `bot_bets_placed`-equivalent
  local counter == N.

Verification:
- `mvn -q -o test` — all slot tests green; full suite still green (no betting-mini
  regression from the resolver rename).

### Ordering note
Phase 6 creates `SlotStrategyFactory`, which Phase 5's `BotFactory` constructor
depends on. Land Phase 6's *factory + interface* before (or together with) Phase 5
so the Spring context resolves. The remaining Phase 5 wiring (the `case SLOT`
branch) and Phase 6 strategy beans can otherwise merge independently. Phases 1–4
are independent and compile standalone (Phase 4 uses an inline strategy stub).

## Implementation Notes / Concerns

- **Array framing is already handled** — do not write an unwrap shim. The library
  parses `[5,{cmd:1302,...}]` via `ActionResponseMessage.deserialize` (array
  required, `get(0)`=category 5=ACTION_RESPONSE, `get(1)`=body) and matches cmd
  via `Qualifier.findCmdDirectPath` (`get(1).get("cmd")`). This is the identical
  path betting-mini uses. The only requirement is that `SlotSpinResultMessage` is
  registered against `"1302"` and the `cmd` field is present in the object — which
  it is.
- **The spin request and spin result share cmd 1302.** Inbound matching uses
  `cmd(1302).and(typeOf(RECEIVED))`; the bot's own outbound `SlotSpin` is `SENT`,
  so `typeOf(RECEIVED)` prevents the bot from matching its own echo. Mirror the
  betting bot's `typeOf(RECEIVED)` qualifier exactly.
- **pendingBet null-guard.** The scenario engine throws if the `sendAsync`
  supplier returns null (documented at BettingMiniGameBot.java:393-416). The
  park-and-pop with a re-derive fallback is mandatory, not optional. Copy the
  betting bot's structure verbatim, substituting the slot strategy.
- **One-spin-in-flight wedge risk.** If a spin is sent and no result ever returns
  on a live socket, `spinInFlight` stays true forever and the bot idles silently.
  v1 accepts this (AD-5/Open Item). It is NOT the same as a dead socket (which
  fires `onWsDisconnected` → reconnect → `beforeReconnect` resets the flag).
- **`gid`-routing with shared WS connection.** Each `SlotMachineBot` owns its own
  `VingameWebSocketClient` (`clientFactory.newClient(...)` per bot in
  `Bot.initialize`), so there is no multiplexing of multiple slot games over one
  socket within a single bot. `gid` is constant per bot (from its `Game`). If a
  future requirement runs multiple slot games on one connection, the
  `onSpinResult` handler would need to filter on `result.getGid() == this.gid` —
  add this guard defensively now (cheap) so a stray foreign-gid frame is ignored
  rather than mis-accounted.
- **Winnings are gross, not net.** `b` is debited at send; `sum(wls.crd)` is
  credited at result. Do not subtract `b` from winnings — that double-counts the
  debit. RTP = `bot_winnings_total / bot_bet_amount_total` only holds if winnings
  is gross.
- **`Game.gameId` is `Integer` (boxed).** A null `gameId` on a SLOT game is a
  misconfiguration — fail loud in `initializeSubclass`, do not NPE in the message
  builder.
- **Resolver rename touches one call site.** `grep` confirms only
  `BotFactory.java:135` calls `GameMessageTypesResolver.resolve`. Renaming to
  `resolveBettingMini` is safe; update that one site in Phase 5.
- **No round/memory/option concepts leak into slots.** `BetContext`/`BotMemory`/
  `RoundState`/`RoundResult` are deliberately untouched. The slot family is
  parallel and minimal (AD-9).
- **Logging levels.** Per CLAUDE.md: one INFO init line in `initializeSubclass`;
  per-spin decision and per-result detail at DEBUG; keep MDC via the inherited
  `mdcConsumer`/`mdcSupplier` wraps on every scenario callback.

## Open Items

- **Silent-unanswered-spin watchdog** (AD-5/AD-6): detecting a spin that gets no
  result on a still-open socket is deferred. Needs a per-spin timer that, on
  expiry, resets `spinInFlight` and/or triggers a reconnect. Out of v1.
- **Slot strategy in the group strategy-mix UI** (AD-10): `StrategyController`
  and the `BotGroup.strategyMix` fill-to-target assignment are betting-specific.
  v1 assigns slot strategy via the new `BotConfiguration.slotStrategyId` with a
  `FIXED` default; surfacing slot strategies in the picker is deferred.
- **Subscribe-response payload shape** — RESOLVED (AD-11). The `cmd:1300`
  response is captured and modeled (`SlotSubscribeResponse`). Winline count =
  `ls.size()`, allowed bet values = `Js[].b`. No longer an open item.
- **Free-spin / bonus rounds** — RESOLVED for v1 (AD-14). The server does NOT push
  unsolicited free-spin results; every spin is one request → one response, so the
  `spinInFlight` gate is sound and `b` is the staked amount each spin. Free-spin /
  bonus-round-specific behavior remains out of v1 scope (no special state machine);
  revisit only if a future capture shows server-driven, request-less results.
- **Balance gate** — RESOLVED (AD-13). `canSpin()` requires
  `expectedCurrentBalance >= chosenBet * numLines` (e.g. `10000 * 25 = 250000`),
  with `numLines` from the subscribe response. No separate `getMinBalance()` floor
  beyond the inherited auto-deposit path in `onNewSession`.

## Verification

These run on staging after deploy. The slot feature has on-server verification
beyond the universal smoke test.

1. **App boots with slot strategies registered.** After deploy:
   ```
   curl -s http://localhost:8080/actuator/health
   ```
   Expect HTTP 200, body `{"status":"UP"}`. Then check startup log for the slot
   strategy factory init line:
   ```
   grep "SlotStrategyFactory initialized" <app-log>
   ```
   Expect a line listing `[FIXED, RANDOM]` (count 2).

2. **A SLOT game can be created via REST.**
   ```
   curl -s -X POST http://localhost:8080/api/v1/game/ -H 'Content-Type: application/json' \
     -d '{"name":"SlotTipTest","gameType":"SLOT","productCode":"P_116","gameId":204,
          "pluginName":"Tip"}'
   ```
   Expect HTTP 200/201 with the persisted game echoing `gameType:SLOT` and
   `gameId:204`. A subsequent `GET /api/v1/game/{id}` returns the same. No
   `allowedBetValues`/`slotWinlines` fields exist — winline count and bet values
   come from the runtime subscribe response, not config.

3. **A slot bot group starts and bots reach CONNECTION_AUTHENTICATED.** Create a
   small bot group (e.g. 2 bots) bound to the SLOT game and TIP env, then:
   ```
   curl -s -X POST http://localhost:8080/api/v1/bot-group/{id}/start
   curl -s http://localhost:8080/api/v1/bot-group/{id}/health
   ```
   Expect HTTP 200 and `BotGroupHealthDTO` showing bots in
   `CONNECTION_AUTHENTICATED` (proves the 1300 subscribe response was received,
   `numLines`/`allowedBetValues` were captured, and `markConnectionAuthenticated()`
   fired — spins are gated on this per AD-12). In logs, expect a RECEIVED
   `cmd 1300` frame per slot bot before any `cmd 1302` spin.

4. **Spins are sent and results accounted.** After the group has run ~30s:
   ```
   curl -s 'http://localhost:8080/actuator/metrics/bot_bets_placed_total?tag=gameType:SLOT'
   curl -s 'http://localhost:8080/actuator/metrics/bot_bet_amount_total?tag=gameType:SLOT'
   ```
   Expect `bot_bets_placed_total{gameType=SLOT}` measurement value `> 0` and
   `bot_bet_amount_total{gameType=SLOT} > 0`. In logs:
   ```
   grep "cmd.*1302" <app-log> | head
   ```
   Expect both SENT spin frames and RECEIVED result frames for the slot bots.

5. **Winnings counter populated (RTP feeds).**
   ```
   curl -s 'http://localhost:8080/actuator/metrics/bot_winnings_total?tag=gameType:SLOT'
   ```
   Expect a measurement value `>= 0` (a series exists; `> 0` once any spin wins).
   Confirm `bot_winnings_total{gameType=SLOT} / bot_bet_amount_total{gameType=SLOT}`
   yields a plausible RTP (< 100% over a sustained window; a sustained > 100% is
   the RTP-anomaly diagnostic, not expected here).

6. **No log errors / no wedged bots.**
   ```
   grep -E "ERROR|marking DEAD" <app-log> | grep -i slot
   ```
   Expect no slot bot ERROR or DEAD lines during a healthy 5-minute run. Re-verify
   the co-located observability stack (Grafana/Prometheus/Loki) is still UP per
   the bot-1 single-compose smoke note.
