# TAI_XIU_114_JACKPOT

## Goal

Add a **second Tai Xiu product** — **P_114 / RIK, plugin `taixiuJackpotPlugin`**
(the jackpot variant) — to the bot-manager. This extends the already-shipped
`TAI_XIU` feature (`docs/plans/TAI_XIU_BOT.md`), whose only product so far is
P_116/TIP on plugin `taixiuPlugin`. The 114 variant is behaviorally identical
(round-based: `subscribe → StartGame(sid) → BET phase with countdown →
EndGame(sid)`, refund-aware accounting, single-entry-per-round lock) and reuses
`TaiXiuGameBot` wholesale. Two structural facts make it more than a config-only
add: (1) the 114 CMDs are the 116 CMDs **+100** on all four frames, which breaks
the current design where the four CMDs are *static interface constants* on
`TaiXiuMessageTypes`; and (2) the 114 outbound bet carries one extra field
`a:false` that the shared bet body does not emit. The central change is to make
the four CMDs **per-provider / offset-aware** (a `cmdOffset()` seam on
`TaiXiuMessageTypes`, default `0`, 114 returns `100`), with **116 staying
byte-for-byte unchanged**.

## Findings — Current State

### The captured 114 frames (source of truth, 2026-06-26)
Live at repo subfolder `/Users/gleb/IdeaProjects/Bot/TaiXiuMessages/`
(`Subscribe.js`, `SubscribeResponse.js`, `Start.js`, `Bet.js`, `End.js`). **These
have REPLACED the earlier 116 captures in that folder — they are now the 114
frames.** Model only the inner `{…}` body object; the ws-parser handles the
`["6","MiniGame","taixiuJackpotPlugin",{…}]` (outbound) / `[5,{…}]` (inbound)
array envelope. Confirmed contents:

| Frame | File | cmd | Notable body fields |
|---|---|---|---|
| subscribe (out) | `Subscribe.js` | **1105** | `{cmd:1105}` only |
| subscribe resp (in) | `SubscribeResponse.js` | **1105** | `tFB:51000`, `sid:5966`, `gS:3`, `rmT:12460`, `tJpV`, `htr[]` with `sd`/`gid`, `odE:-1` |
| startGame (in) | `Start.js` | **1102** | `gid:"102"`, `iES`, `sid` — **no `odE`** |
| bet (out) | `Bet.js` | **1100** | `cmd, b, aid:1, eid:1, sid, ` **`a:false`** |
| endGame (in) | `End.js` | **1104** | `gB=gR=GX=500000, G=0` (full refund), `sd:[…]`, **live `tJpV`**, `jpV`, `iJp` |

The +100 offset is confirmed on **all four** frames (1005→1105, 1002→1102,
1004→1104, 1000→1100).

### CMDs are currently hardcoded as static interface constants — this is what breaks
`TaiXiuMessageTypes`
(`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/TaiXiuMessageTypes.java:35-38`)
declares the four CMDs as `static` interface fields:
```java
int SUBSCRIBE_CMD = 1005;
int START_GAME_CMD = 1002;
int END_GAME_CMD = 1004;
int BET_CMD = 1000;
```
`getTypeRegistrations()` (same file, lines 81-87) builds `NamedType`s from those
constants. There are exactly **three** consumers of the constants (verified via
grep across `src/main`):
1. `TaiXiuGameBot.subscribeCmd()/startGameCmd()/endGameCmd()`
   (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/TaiXiuGameBot.java:166,171,176`)
   — return the static constants directly.
2. `TaiXiuRequest.subscribe()` and `TaiXiuRequest.bet()`
   (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/request/TaiXiuRequest.java:42,55`)
   — read `TaiXiuMessageTypes.SUBSCRIBE_CMD` / `BET_CMD`.
3. `TaiXiuMessageTypes.getTypeRegistrations()` default method itself.

Because these are `static`, they cannot vary per product. The 114 provider needs
`+100` CMDs, so the CMDs must become **instance-resolved**: keep the four base
literals, add an instance `cmdOffset()` (default `0`), and derive
`subscribeCmd()/startGameCmd()/endGameCmd()/betCmd() = base + cmdOffset()`.

### Plugin name is already data-driven — no code change for that
`TaiXiuGameBot.buildRequest(Game)`
(`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/TaiXiuGameBot.java:231-233`)
builds `new TaiXiuRequest(game.getPluginName(), configuration.getZoneName())`. So
`taixiuJackpotPlugin` is purely the Game entity's `pluginName` (data/config), not
code. The ws-parser assembles the `taixiuJackpotPlugin` envelope from it.

### The CMD seams in `BettingMiniGameBot` already exist (from the 116 work)
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`
has the protected seams `subscribeCmd()/updateBetCmd()/startGameCmd()/endGameCmd()`
(lines 190-205), `messageTypeRegistrations()` (215), `buildRequest(Game)` (226),
the five `*Type()` accessors (239-259), `endGameSessionId()` (283), and
`balanceCreditFor()` (295). The scenario and `onStart` already call the seams
(BettingMiniGameBot.java:651,666,706-710). **The seam infrastructure this feature
relies on is already in place; only the CMD-source needs to become offset-aware.**

### Existing 116 message classes tolerate unknown fields
- `TaiXiuSubscribeMessage`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/taixiu/TaiXiuSubscribeMessage.java:42`)
  carries `@JsonIgnoreProperties(ignoreUnknown = true)`. Its `@JsonCreator` reads
  `cmd, tFB, tFBB, sid, gS, rmT, odE, iES` — every one present in 114's
  `SubscribeResponse.js` (`tFB:51000`, `sid`, `gS`, `rmT`, `odE:-1`; `tFBB`
  absent → defaults 0). `getTimeForBetting()` returns `tFB` (present). The 114
  extras (`tJpV`, `htr` with `sd`/`gid`) are ignored.
- `TaiXiuStartGameMessage`
  (`.../taixiu/TaiXiuStartGameMessage.java:30`) does **not** have
  `@JsonIgnoreProperties`, and its `@JsonCreator` reads `cmd, odE, iES, sid`. In
  114, `odE` is **absent** (defaults to 0.0 — harmless, `odE` is "modeled, not
  consumed in v1") and `gid:"102"` is **extra**. Unknown-field tolerance comes
  from the scenario mapper's `FAIL_ON_UNKNOWN_PROPERTIES=false`
  (BettingMiniGameBot.java:650) at runtime — but the **deserialization test
  harness** builds its own mapper with the same flag
  (`TaiXiuMessageDeserializationTest.java:38`). So `gid` deserializes without
  failing in both runtime and test. The only field the bot reads is `sid`
  (present). `getSessionId()` returns `sid`.
- `TaiXiuEndGameMessage`
  (`.../taixiu/TaiXiuEndGameMessage.java:61`) reads `cmd, d1, d2, d3, gB, gR, G,
  GX, cB, cBB, cR, CX, iJp, jpV` — all present in 114's `End.js`. The 114 extras
  `sd:[…]` and `tJpV` are not in the creator → ignored via the mapper flag. It
  implements `HasBotWinnings` (winnings = `G`), `HasBetTotals` (effective wagered
  = `gB − gR`), `HasJackpot` (reads `jpV`/`iJp`) — all semantics identical for
  114 (same refund fields, same `jpV`/`iJp`).

**Conclusion: the existing inbound classes are reusable for 114 as-is.** The
fields the bot reads are all present; the 114-only fields (`gid`, `sd`, `tJpV`,
`htr` items) are not bot-relevant for v1 and are tolerated. No 114-specific
inbound classes are needed.

### The outbound bet body has no place for `a`
`Bet.BetData`
(`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/request/Bet.java:20-37`)
is the shared betting-mini bet body, emitting exactly `{cmd, aid:1, b, eid, sid}`.
It is reused by Tai Xiu (`TaiXiuRequest.bet()` constructs a `Bet`). 114's bet adds
`a:false`. `Bet` is used by **every** betting-mini bet, so adding `a` there would
pollute all products.

### The 114 product already exists in the enum but throws in the resolver
- `ProductCode.P_114("114", "RIK", null, null)` — appId `null`, usernameMaxLength
  `null` (no username pre-flight cap).
- `GameMessageTypesResolver.resolveTaiXiu(ProductCode)`
  (`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypesResolver.java:86-98`)
  currently: `case P_116 -> new MiniGameTaiXiuMessageTypes()`; **P_114 is in the
  throwing arm** ("not yet implemented for product code").
- `resolveBettingMini` (same file, line 50) also lists P_114 in its throwing arm
  — not relevant here (Tai Xiu uses `resolveTaiXiu`).

### Existing test coverage to keep green
`TaiXiuMessageTypesTest` asserts the constants equal 1005/1002/1004/1000
(`/Users/gleb/IdeaProjects/Bot/src/test/java/com/vingame/bot/domain/bot/message/TaiXiuMessageTypesTest.java:37-40`),
`TaiXiuMessageDeserializationTest` registers the inbound types against
`"1005"/"1002"/"1004"`, `TaiXiuRequestTest`, `TaiXiuGameBotDispatchTest`,
`TaiXiuGameBotStreamTest`, `TaiXiuGameBotSingleEntryLockTest`. All must stay green
(the 116 provider must keep offset 0).

## Per-aspect readiness / mapping

| Aspect | Status | Notes |
|---|---|---|
| `ProductCode.P_114` enum value | **ready** | Present (`P_114("114","RIK",null,null)`). |
| Round behavior / countdown / watchdog / lock | **ready** | Fully inherited via `TaiXiuGameBot`; no change. |
| CMD seams in `BettingMiniGameBot` | **ready** | `subscribeCmd()` etc. already protected (from 116 work). |
| Per-provider CMD offset | **blocked → Phase 1** | CMDs are static constants today (AD-1). Must add `cmdOffset()` and instance-derived CMDs. |
| `TaiXiuRequest` cmd source | **partial** | Reads static `SUBSCRIBE_CMD`/`BET_CMD`; must become offset-aware (AD-1, AD-4). |
| Outbound bet `a` field | **partial → Phase 3** | Shared `Bet.BetData` has no `a`. Need Tai-Xiu bet body / conditional (AD-2). |
| Inbound message classes (subscribe/start/end) | **ready (reuse)** | Existing `taixiu/*` classes tolerate 114 deltas (AD-3). |
| 114 provider impl | **blocked → Phase 2** | New `TaiXiuMessageTypes` impl with `cmdOffset()=100` (AD-5). |
| `resolveTaiXiu` wiring | **partial → Phase 5** | Add `case P_114 -> new <114 provider>()` (AD-6). |
| Test fixtures (114) | **partial → Phase 4** | New `src/test/resources/messages/taixiu/jackpot/` from captures. |
| Config / data (Environment, Game) | **out of scope (note only)** | Operator sets `appId` on Env, `pluginName=taixiuJackpotPlugin` on Game. |

## Architecture Decisions

**AD-1. Make the four Tai Xiu CMDs offset-aware via a `cmdOffset()` seam on
`TaiXiuMessageTypes` — the central change.** Keep the four base literals as the
116 values, add an instance `default int cmdOffset() { return 0; }`, and expose
the four resolved CMDs as **instance** accessors derived from base + offset:
```java
int SUBSCRIBE_CMD_BASE  = 1005;
int START_GAME_CMD_BASE = 1002;
int END_GAME_CMD_BASE   = 1004;
int BET_CMD_BASE        = 1000;

default int cmdOffset()    { return 0; }            // 114 returns 100
default int subscribeCmd() { return SUBSCRIBE_CMD_BASE  + cmdOffset(); }
default int startGameCmd() { return START_GAME_CMD_BASE  + cmdOffset(); }
default int endGameCmd()   { return END_GAME_CMD_BASE    + cmdOffset(); }
default int betCmd()       { return BET_CMD_BASE         + cmdOffset(); }
```
`getTypeRegistrations()` registers each inbound class against
`String.valueOf(subscribeCmd())` / `startGameCmd()` / `endGameCmd()` (so the 114
provider registers against `"1105"/"1102"/"1104"`). Naming: rename the existing
`SUBSCRIBE_CMD` etc. to `*_CMD_BASE` to make "base, not final" explicit, OR keep
the names as the base and add the instance methods — Dev picks one and is
consistent. The existing `TaiXiuMessageTypesTest` assertions on the constants must
be updated to assert the **116 provider instance** returns 1005/1002/1004/1000 and
a fresh `cmdOffset()==0`. **116 (offset 0) is byte-for-byte unchanged** — this is
the contract verified by the existing 116 suite.

**AD-2. The outbound bet `a` field gets a Tai-Xiu-specific bet body — do NOT add
`a` to the shared `Bet.BetData`.** The shared `Bet.BetData` emits `{cmd, aid, b,
eid, sid}` and is used by every betting-mini bet; adding `a` there would change
every product's bet. Instead introduce a Tai-Xiu bet body that extends/wraps the
shared shape and adds `a:false` (a fixed boolean — likely an auto-bet flag). Two
acceptable shapes (Dev picks, both keep the change isolated to Tai Xiu):
- (preferred) a `TaiXiuBet` body class (a `Body` subclass) with fields `{cmd,
  aid:1, b, eid, sid, a:false}`, constructed by `TaiXiuRequest.bet()` — mirrors
  how `Bet.BetData` is a nested body; lives next to `TaiXiuRequest`.
- a subclass `Bet.BetData` adding `a` — rejected as messier (the `final`/nested
  shape resists subclassing cleanly).

`a` is emitted for **both** 116 and 114 only if 116 tolerates the extra field
(OI-1). Until that is confirmed, **scope `a` to the 114 path**: `TaiXiuRequest`
emits `a` only when the provider is the 114 provider (or when a flag on the
request says so). Simplest safe default: pass a boolean `emitAutoBetFlag` into
`TaiXiuRequest` (true for 114, false for 116) so 116's bet stays exactly
`{cmd:1000, aid, b, eid, sid}` and 114's adds `a:false`. **This keeps 116
byte-for-byte unchanged regardless of OI-1.**

**AD-3. Reuse the existing inbound message classes for 114 — no new
subscribe/start/end classes.** The bot-relevant fields are present in 114
(`tFB`/`sid` on subscribe response, `sid` on StartGame, `gB`/`gR`/`G`/`jpV`/`iJp`
on EndGame), and the classes tolerate unknowns (`TaiXiuSubscribeMessage` has
`@JsonIgnoreProperties(ignoreUnknown=true)`; StartGame/EndGame rely on
`FAIL_ON_UNKNOWN_PROPERTIES=false` in both the scenario mapper and the test
mapper). The 114-only fields (`gid`, `sd`, `tJpV`, `htr` items) are **not
bot-relevant for v1** and are ignored. 114's StartGame lacks `odE` → defaults to
0.0 (not consumed). The 114 provider returns the **same** concrete classes the
116 provider returns. (If a later requirement needs `gid`/`sd`/live `tJpV`, add
114-specific classes then — flagged OI-2.)

**AD-4. `TaiXiuRequest` becomes offset-aware by taking the resolved CMDs (or the
provider) instead of static constants.** Today `TaiXiuRequest` reads
`TaiXiuMessageTypes.SUBSCRIBE_CMD` / `BET_CMD` statically (lines 42, 55). Change
it to take the resolved subscribe/bet CMDs at construction (or take the
`TaiXiuMessageTypes` provider and call `subscribeCmd()`/`betCmd()`). The cleanest
is to pass the two ints in from `buildRequest()`:
`new TaiXiuRequest(pluginName, zoneName, provider.subscribeCmd(),
provider.betCmd(), emitAutoBetFlag)`. `TaiXiuGameBot.buildRequest()` already has
access to `taiXiuMessageTypes`, so it can read the resolved CMDs from the provider.

**AD-5. New 114 provider impl with `cmdOffset()=100`.** Create a
`TaiXiuMessageTypes` impl for the 114/jackpot product, e.g.
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/taixiu/JackpotTaiXiuMessageTypes.java`,
overriding `cmdOffset()` to return `100` and returning the **same** concrete
message classes as `MiniGameTaiXiuMessageTypes` (AD-3:
`TaiXiuSubscribeMessage`/`TaiXiuStartGameMessage`/`TaiXiuEndGameMessage`,
`startGameMd5Type()`/`updateBetType()` → `null`). Name `Jackpot…` (matches the
plugin); `Rik…` is acceptable but `Jackpot…` is clearer about what differs.

**AD-6. Wire `resolveTaiXiu`: `case P_114 -> new JackpotTaiXiuMessageTypes()`.**
Move `P_114` out of the throwing arm of `resolveTaiXiu`
(GameMessageTypesResolver.java:91-97) into its own case. Leave the rest throwing.
No change to `resolveBettingMini`.

**AD-7. `TaiXiuGameBot` reads its CMDs from the provider, not from static
constants.** Today `subscribeCmd()/startGameCmd()/endGameCmd()` return
`TaiXiuMessageTypes.SUBSCRIBE_CMD` etc. (static). Change them to delegate to the
**wired provider instance**: `taiXiuMessageTypes.subscribeCmd()` /
`startGameCmd()` / `endGameCmd()`. This is what makes the +100 offset flow through
the inbound matcher and the OutputPrinter cmd list. `updateBetCmd()` stays the
documented `-1` sentinel (still unused; OI-5 from the 116 plan). `buildRequest()`
passes the provider's resolved subscribe/bet CMDs (AD-4). **No other change to the
bot** — `endGameSessionId`, `balanceCreditFor`, the single-entry lock, and option
defaults are all product-neutral and reused.

**AD-8. md5 / updateBet unchanged.** 114 captured no md5 StartGame and no
updateBet frame, same as 116. `startGameMd5Type()`/`updateBetType()` return
`null`; the scenario skips those handlers. The fixed (now offset-derived)
`startGameCmd()` is shared by both StartGame variants.

**AD-9. Refund-aware accounting unchanged.** 114's EndGame has the same
`gB`/`gR`/`G`/`GX` semantics as 116 (the capture is again fully refunded:
`gB=gR=GX=500000`, `G=0`). `TaiXiuEndGameMessage`'s formulas (winnings = `G`,
effective wagered = `gB − gR`, balance credit `gR + G`) apply directly. The live
`tJpV` (jackpot pool) is **not** a per-bot win and is **not** read for accounting;
the per-round jackpot payout stays `iJp ? jpV : 0` via `HasJackpot`.

## Plan

> Maven builds require
> `JAVA_HOME=/Users/gleb/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home`
> (default JDK is Java 8 and will not compile). Prefix every `mvn` command with it.

### Phase 1 — Offset-aware CMDs on `TaiXiuMessageTypes` (refactor, 116 stays green)
Body-independent. Makes the four CMDs per-provider while keeping 116 at offset 0.

Modify
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/TaiXiuMessageTypes.java`:
- Keep the four base literals (rename to `*_CMD_BASE` or keep names — be
  consistent). Add `default int cmdOffset() { return 0; }` and the four instance
  accessors `subscribeCmd()/startGameCmd()/endGameCmd()/betCmd()` = base +
  `cmdOffset()` (AD-1).
- Rewrite `getTypeRegistrations()` to register against
  `String.valueOf(subscribeCmd())` / `startGameCmd()` / `endGameCmd()`.

Modify
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/core/TaiXiuGameBot.java`:
- `subscribeCmd()/startGameCmd()/endGameCmd()` (lines 165-177) delegate to
  `taiXiuMessageTypes.subscribeCmd()/startGameCmd()/endGameCmd()` (AD-7). Keep
  `updateBetCmd()` returning `-1`.

Modify
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/request/TaiXiuRequest.java`:
- Take the resolved subscribe/bet CMDs (or the provider) instead of the static
  constants (AD-4). `buildRequest()` in `TaiXiuGameBot` (line 231) passes them
  from `taiXiuMessageTypes`. (The `a`-field change lands in Phase 3; for Phase 1
  keep the bet body unchanged so 116 is untouched.)

Update tests that assert the static constants:
- `TaiXiuMessageTypesTest` (lines 37-40): assert the **116 provider instance**
  (`new MiniGameTaiXiuMessageTypes()`) returns `subscribeCmd()==1005`,
  `startGameCmd()==1002`, `endGameCmd()==1004`, `betCmd()==1000`, and
  `cmdOffset()==0`.

Verification:
- `JAVA_HOME=... mvn -q -o test` — the **entire existing Tai Xiu suite stays
  green** (`TaiXiuMessageTypesTest`, `TaiXiuMessageDeserializationTest`,
  `TaiXiuRequestTest`, `TaiXiuGameBotDispatchTest`, `TaiXiuGameBotStreamTest`,
  `TaiXiuGameBotSingleEntryLockTest`, plus all betting-mini tests). This proves
  the refactor changed nothing observable for 116.

### Phase 2 — 114 provider impl (`JackpotTaiXiuMessageTypes`)
Create
`/Users/gleb/IdeaProjects/Bot/src/main/java/com/vingame/bot/domain/bot/message/taixiu/JackpotTaiXiuMessageTypes.java`
(AD-5):
- `implements TaiXiuMessageTypes`, override `cmdOffset()` → `100`.
- `subscribeType()/startGameType()/endGameType()` return the **same** classes as
  `MiniGameTaiXiuMessageTypes` (`TaiXiuSubscribeMessage`/`TaiXiuStartGameMessage`/
  `TaiXiuEndGameMessage`, AD-3). `startGameMd5Type()`/`updateBetType()` → `null`.

Verification:
- `JAVA_HOME=... mvn -q -o test-compile`.
- Unit assertion (can fold into Phase 3 test): `new JackpotTaiXiuMessageTypes()`
  returns `cmdOffset()==100`, `subscribeCmd()==1105`, `startGameCmd()==1102`,
  `endGameCmd()==1104`, `betCmd()==1100`, and `getTypeRegistrations()` names are
  `"1105"/"1102"/"1104"`.

### Phase 3 — Outbound bet `a` field
Add `a:false` to the 114 bet without touching the shared `Bet.BetData` (AD-2).

- Create a Tai-Xiu bet body (e.g. `TaiXiuBet` / `TaiXiuBet.BetData`) next to
  `TaiXiuRequest` with `{cmd, aid:1, b, eid, sid, a}` where `a` is a fixed
  boolean (default `false`).
- `TaiXiuRequest` takes an `emitAutoBetFlag` boolean (true for 114, false for
  116). `bet()` builds the Tai-Xiu body with `a` when the flag is set; otherwise
  builds the existing shared `Bet` (116 bet stays exactly `{cmd, aid, b, eid,
  sid}`). `buildRequest()` in `TaiXiuGameBot` passes the flag derived from the
  provider (e.g. `taiXiuMessageTypes.cmdOffset() == 100`, or — cleaner — a
  `boolean emitsAutoBetFlag()` default `false` on `TaiXiuMessageTypes` that the
  114 provider overrides to `true`; prefer the explicit method over inspecting
  the offset).

Verification:
- `JAVA_HOME=... mvn -q -o test`.
- Extend `TaiXiuRequestTest`: a 116-configured request's bet serializes to
  `{cmd:1000, aid:1, b, eid, sid}` (**no `a`**); a 114-configured request's bet
  serializes to `{cmd:1100, aid:1, b, eid, sid, a:false}`. Subscribe for 114
  emits `cmd:1105`.

### Phase 4 — 114 fixtures
Add fixtures under `src/test/resources/messages/taixiu/jackpot/` (a subfolder so
the 114 frames sit beside the 116 ones), each the **inner body object** from the
captures (the test harness applies the envelope):
- `subscribe.json` — from `SubscribeResponse.js` (the inbound response, cmd 1105,
  with `tFB:51000`, `sid:5966`, `gS:3`, `rmT:12460`, `odE:-1`, plus 114 extras
  `tJpV`, `htr`). Not the bare `Subscribe.js` outbound `{cmd:1105}`.
- `startGame.json` — from `Start.js` (`{gid:"102", iES, cmd:1102, sid}` — **no
  `odE`**).
- `endGame_fullRefund.json` — from `End.js` as-is (`gB=gR=GX=500000, G=0`, with
  `sd`, live `tJpV`, `jpV`, `iJp`).
- `endGame_partialRefund.json` — hand-derived (`0 < gR < gB`, e.g. `gB=500000,
  gR=200000`, `G` non-zero) for AD-9 case (b).
- `endGame_noRefund.json` — hand-derived (`gR=0`, `G` win value) for AD-9 case (c).
- `bet.json` — from `Bet.js` (`{cmd:1100, b, aid:1, eid:1, sid, a:false}`).

Verification:
- `JAVA_HOME=... mvn -q -o test-compile` (fixtures present, valid JSON).

### Phase 5 — Resolver wiring + provider/deserialization tests
Modify `GameMessageTypesResolver.resolveTaiXiu`
(`.../message/GameMessageTypesResolver.java:91-97`): add
`case P_114 -> new JackpotTaiXiuMessageTypes();` (AD-6). Leave the rest throwing.

Create `JackpotTaiXiuMessageTypesTest` (mirror `TaiXiuMessageTypesTest` +
`TaiXiuMessageDeserializationTest`):
- `resolveTaiXiu(ProductCode.P_114)` returns a `JackpotTaiXiuMessageTypes`;
  `getTypeRegistrations()` registers the three inbound types against
  `"1105"/"1102"/"1104"`.
- Round-trip each Phase-4 inbound fixture through the provider's registrations:
  - `subscribe.json` → `TaiXiuSubscribeMessage`, `getTimeForBetting()==51000`,
    `getSid()==5966`.
  - `startGame.json` → `TaiXiuStartGameMessage`, `getSessionId()==<sid>` (assert
    `gid` did not break parsing; `odE` defaulted).
  - The three EndGame fixtures: full-refund → winnings 0, effective wagered 0;
    partial → winnings `G`, wagered `gB−gR`; no-refund → wagered `gB`, winnings
    `G` (the refund formulas hold identically for 114).
- Assert an unimplemented product (e.g. `P_066`) still throws the
  "not yet implemented" `IllegalArgumentException`.

Verification:
- `JAVA_HOME=... mvn -q -o test`.

### Phase 6 — 114 dispatch / stream test (offset + accounting end-to-end)
Create `TaiXiuJackpotGameBotStreamTest` (mirror `TaiXiuGameBotStreamTest`) wiring
the bot with `resolveTaiXiu(P_114)`:
- Drive `onSubscribe → onStartGame → betCondition()/bet() → onEndGame` over a
  sequence mixing the three Phase-4 EndGame fixtures.
- Assert the **outbound** subscribe/bet use the **+100** CMDs (`1105`/`1100`) and
  the inbound matcher matched the +100 StartGame/EndGame (`1102`/`1104`) — i.e.
  the bot consumed the 114 frames.
- Assert the bet body carries `a:false` (Phase 3).
- Assert refund-aware balance identity `start − Σb + Σ(gR) + Σ(G)` and
  `bot_bet_amount_total == Σ(gB − gR)`, including the captured full-refund round
  contributing `0` to both.
- **Regression guard:** a sibling assertion (or reuse the existing 116 stream
  test) confirms a `resolveTaiXiu(P_116)` bot still uses `1005/1002/1004/1000`
  and never emits `a`.

Verification:
- `JAVA_HOME=... mvn -q -o test` — all Tai Xiu (116 + 114) tests green; full suite
  green.

### Ordering note
Phase 1 (offset refactor) lands first and is validated solely by the existing 116
suite. Phases 2-4 are independent and compile standalone. Phase 5 depends on 2+4.
Phase 6 depends on 2+3+4+5. Phase 3 (`a` field) is independent of the resolver and
can land before or after Phase 2, but its test needs the 114 CMDs from Phase 1.

## Implementation Notes / Concerns

- **Phase 1 is the only change to shared/116 surface — keep it behavior-neutral.**
  The offset-0 path must produce identical CMDs and identical bet bodies as today.
  The acceptance signal is the existing 116 suite staying green. Do not touch any
  handler logic, accounting, or the single-entry lock.
- **The inbound matcher and the registration string must agree, both at +100.**
  `TaiXiuGameBot.subscribeCmd()` (matcher) and the provider's
  `getTypeRegistrations()` (subtype name) both derive from `cmdOffset()`, so they
  stay consistent. A mismatch silently means the 114 bot never matches its own
  inbound frames — the classic "wedged bot" failure. Phase 6 asserts the +100
  frames are actually consumed.
- **Reuse, don't fork, the inbound classes (AD-3).** Adding 114-specific
  subscribe/start/end classes would be dead weight — the 116 classes already
  tolerate every 114 delta. The only genuinely new outbound bit is `a:false`
  (Phase 3) and the offset (Phase 1).
- **`a` must not leak into betting-mini bets.** Keep the `a` field on a Tai-Xiu
  body only, gated by the provider flag, so 116's bet and every betting-mini bet
  stay byte-for-byte unchanged (AD-2). The safest default scopes `a` to 114 only;
  if 116 turns out to tolerate `a`, a follow-up can unify (OI-1).
- **114 StartGame has no `odE`; subscribe response `odE:-1`.** `odE` is modeled
  but not consumed (it has been "not consumed in v1" since the 116 work), so the
  default 0.0 / the `-1` value are harmless. Do not add logic on `odE`.
- **Live `tJpV` is the jackpot *pool*, not a per-bot win.** Do not feed `tJpV`
  into balance or `bot_winnings_total`. Per-round jackpot stays `iJp ? jpV : 0`.
- **Config is operator-side (next section), not code.** The plugin name
  `taixiuJackpotPlugin` flows from `game.getPluginName()`; `appId` must be set on
  the P_114 Environment because `ProductCode.P_114.appId` is `null`.
- **Logging levels** (CLAUDE.md): no new INFO lines; the bot reuses the inherited
  scenario/handlers and their existing levels + MDC.

## Open Items

- **OI-1 — `a` field requirement (blocking the unify decision, not the feature).**
  Does the 114 server *require* `a:false` on the bet, and does the 116 server
  *tolerate* an extra `a`? v1 emits `a:false` for 114 only (AD-2), which is safe
  regardless. If 116 tolerates `a`, a single Tai-Xiu bet body with `a:false` for
  both would be simpler — defer until confirmed on staging.
- **OI-2 — `gid` / `sd` relevance.** 114 adds `gid` (StartGame, subscribe `htr`
  items) and `sd` (EndGame, subscribe `htr` items). Treated as **not
  bot-relevant for v1** and ignored (AD-3). If a later requirement needs them
  (e.g. multi-game-id routing, per-round dice detail), add 114-specific classes
  then. Flag if this assumption is wrong.
- **OI-3 — is +100 the complete CMD story?** Verified on all four captured frames
  (subscribe/start/bet/end). `updateBet` was not captured for either product
  (same as 116, OI-5 there) so its offset is unconfirmed but unused. If other
  Tai Xiu CMDs surface (e.g. a balance/jackpot-tick frame the bot needs), confirm
  they also follow +100 before relying on `cmdOffset()` for them.
- **md5 variant** — no md5 StartGame captured for 114 (same as 116);
  `startGameMd5Type()` returns `null`. Add if a variant surfaces (AD-8).

## Config / non-code (operator action, not implemented here)

- P_114 = RIK, G3 platform; auth via the generic `DefaultLoginRequest` profile
  (already supported). `ProductCode.P_114.appId` is `null`, so the **Environment's
  `appId` must be set** for P_114. Username cap is `null` (no pre-flight check).
- Data to create on staging: a **P_114 Environment** (mini zone) with `appId`
  populated, and a **Tai Xiu Game** with `gameType=TAI_XIU` and
  `pluginName=taixiuJackpotPlugin`.

## Verification

These run on staging after deploy. The feature has on-server verification beyond
the universal smoke test.

1. **App boots; no resolver regression.**
   ```
   curl -s http://localhost:8080/actuator/health
   ```
   Expect HTTP 200, body `{"status":"UP"}`.

2. **A P_114 Environment (appId set) and a `taixiuJackpotPlugin` TAI_XIU Game can
   be created via REST.**
   ```
   curl -s -X POST http://localhost:8080/api/v1/game/ -H 'Content-Type: application/json' \
     -d '{"name":"TaiXiuJackpot114","gameType":"TAI_XIU","productCode":"P_114","pluginName":"taixiuJackpotPlugin"}'
   ```
   Expect HTTP 200/201 echoing `gameType:TAI_XIU`, `pluginName:taixiuJackpotPlugin`.
   A subsequent `GET /api/v1/game/{id}` returns the same.

3. **A P_114 Tai Xiu bot group passes validation, starts, and bots reach
   CONNECTION_AUTHENTICATED.** Create a small group (e.g. 2 bots) bound to the
   P_114 TAI_XIU game + Environment, then:
   ```
   curl -s -X POST http://localhost:8080/api/v1/bot-group/{id}/start
   curl -s http://localhost:8080/api/v1/bot-group/{id}/health
   ```
   Expect HTTP 200 and `BotGroupHealthDTO` showing bots in
   `CONNECTION_AUTHENTICATED` (proves the **+100** subscribe `1105` was sent and
   the `1105` subscribe response was matched — `onSubscribe` fired). In logs,
   expect a RECEIVED subscribe frame at cmd **1105** per bot before any bet.

4. **Rounds drive bets at the +100 CMDs with the `a` flag.** After a few rounds:
   ```
   curl -s 'http://localhost:8080/actuator/metrics/bot_bets_placed_total?tag=gameType:TAI_XIU'
   curl -s 'http://localhost:8080/actuator/metrics/bot_bet_amount_total?tag=gameType:TAI_XIU'
   ```
   Expect `bot_bets_placed_total{gameType=TAI_XIU} > 0` and
   `bot_bet_amount_total{gameType=TAI_XIU} > 0`. In logs, expect outbound bet
   frames at cmd **1100** carrying `"a":false`, and inbound StartGame/EndGame at
   cmd **1102 / 1104** for the P_114 bots.

5. **Refund-aware accounting holds for 114 (winnings counter populated).**
   ```
   curl -s 'http://localhost:8080/actuator/metrics/bot_winnings_total?tag=gameType:TAI_XIU'
   ```
   Expect a measurement `>= 0`. RTP (`bot_winnings_total / bot_bet_amount_total`)
   over a sustained window should be plausible (< 100%); a window dominated by
   heavily-refunded 114 rounds should still show a sane RTP because the
   denominator records effective stake `gB − gR`. Implausibly low RTP ⇒ suspect
   refund netting not applied to 114.

6. **No 116 regression, no wedged 114 bots.**
   ```
   grep -E "ERROR|marking DEAD" <app-log> | grep -iE "tai|xiu|114|jackpot"
   ```
   Expect no Tai Xiu ERROR/DEAD lines during a healthy 5-minute run. Spot-check an
   existing **P_116** Tai Xiu group still subscribes/bets at the unchanged
   `1005/1002/1004/1000` CMDs and its bet carries **no** `a` field (the Phase-1
   offset refactor and Phase-3 `a` change must not have regressed 116). Re-verify
   the co-located observability stack (Grafana/Prometheus/Loki) is still UP per
   the bot-1 single-compose smoke note.
