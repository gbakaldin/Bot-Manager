# RIK_114_BETTING_MINI — a BETTING_MINI message provider for P_114 / RIK

## Goal

Ship a `BETTING_MINI` `GameMessageTypes` provider for product **P_114 (RIK)** so a bot
group can be pointed at a 114 mini game at all. Today `"114"` sits in
`MessageTypesCoverageTest.BETTING_MINI_NOT_YET_IMPLEMENTED`
(`/Users/gleb/IdeaProjects/Bot/bot-messages/src/test/java/com/vingame/bot/domain/bot/message/MessageTypesCoverageTest.java:59-64`),
so `MessageTypesRegistry.bettingMini("114")` throws on the bot thread at group start and
114 has only a `TAI_XIU` provider (`JackpotTaiXiuMessageTypes`) plus the product-neutral
SLOT provider. The evidence is a live wire capture of RIK staging's **`stockPlugin`** — a
two-option stock up/down game at **offset 10000** — taken 2026-09-15
(`/Users/gleb/Downloads/stockPlugin-13000-capture.jsonl`, 50 frames, 95 s, 3 complete
rounds), joined on the same day by a second capture of **`taixiuMd5Plugin`** — a standard
two-option Tài/Xỉu game at **offset 4000** — which is what proves the provider against more
than one 114 game shape (`/Users/gleb/Downloads/taixiuMd5Plugin-7000-capture.jsonl`, 22
frames, 96 s, 2 rounds). The work is a pure addition in `bot-messages`: five message
classes, one provider, two inventory edits in tests, fixtures and a deserialization test.
**No existing product's message layer is touched.**

## Amendment A6 (2026-09-18) — V-17 answered by a server restart; Phase 3 and 3b WITHDRAWN

Stock began settling at **14:00–14:05Z on 2026-09-17** with **Phase 2 code and nothing else**
(Prometheus `bot_bets_placed_total{cd77131c…}`: 0 at 13:00, 6 at 14:05, then +18–30 per 5 min
unbroken; container up since 11:28Z, group since 13:01Z). That is the minute the user restarted
the ziczac game server-side — the restart cleared stock too. The stock plugin had been wedged
in a form that still emitted rounds but discarded every settlement, which nothing client-side
could distinguish from a client fault. Measured since: **5,602 confirmed positions, 16,909,000
staked, 16,499,024 won, RTP 0.976** — matching the §6 payout model (`× 0.98`) to within noise.

Consequences:

- **Phase 2 (`v` + `iAc`) was the complete client-side fix.** V-17 is answered: settles.
- **`13022` was never the gate.** AD-27's correlation (stock and ziczac send extra frames and
  fail; txmd5 sends none and settles) was a coincidence of *two server-side wedges on one day*
  — see `RIK_114_ZICZAC` Amendment A1. The legacy bot and the browser send `13022`; we settle
  without it.
- **Correction 2026-10-01 (user): Phase 3 (`13022`) is KEPT and has been deployed since 2026-09-18.**
  `13022` is not of unknown semantics: it is the game's **"commit to position"** command (user-stated).
  The 2026-09-18 ZicZac image was built from the tree that already carried it, Stock has sent it
  per bet ever since and still settles, and it was committed as-is in `2a7d803`. The paragraph
  below is historical; its "do not deploy" no longer applies. Phase 3b (`13012`) stays withdrawn.
- **Phase 3 (`13022`) and Phase 3b (`13012`) are withdrawn.** The Phase 3 code is implemented,
  reviewed (PASS / PASS / PLAN_AMENDED, 2267 tests) and **not deployed**; it stays in the
  working tree as reviewed. **Do not deploy it**: it adds a fire-and-forget frame of unknown
  semantics, per bet where the browser sends it once per round, to a game that now settles. It
  can be reverted or kept behind the seam at the user's discretion; either way `GameRequest.commit`
  defaulting to empty means no product sends it unless `RikStockRequest` is resolved.
- **Every elimination in AD-27 / A4 stands**; what was missing was a test for "the game is
  broken while still emitting rounds". The `rmT` field on the subscribe ack is that test
  (negative or absurd = wedged). Add it to any future "does this game settle?" checklist
  **before** touching the client.
- The coins probe group stays pinned at `min = max = 1000` as a settling canary; scale a
  separate group for anything that should look like a room.

## Amendment A4 (2026-09-17) — Phase 2 shipped and V-12 still read zero; AD-27 is amended, Phase 3 sends `13022`, not `13012`

Phase 2 is on the box and its wire capture (`docs/reviews/RIK_114_BETTING_MINI/release.md`)
shows the `v`+`iAc` body **accepted, attributed and echoed in-round** and then **discarded at
EndGame** (`mbs:[]`, `obs` zeroed, server balance untouched). The legacy Node.js stock bot on
`Staging-098` never sends `13012` and sends **`13022` after every bet**; the browser sends it
once per round; we send it never. Display names and denominations were eliminated as causes by
experiment the same day (`display-name-check.md`). So:

- **AD-27 is amended in place**: the rival hypothesis is the per-bet commit `13022`; the
  post-subscribe `13012` becomes **Phase 3b** (AD-34), gated on this phase's V-17, never sent in
  the same deploy.
- **Phase 3 is rewritten** on the shipped `GameRequestFactory` seam — `GameRequest.commit(sid)`
  (AD-28), `RikStockCommit` `{cmd, sId}` (AD-29), the `<lower><UPPER>` key rule as a decision
  (AD-30), the commit sent from the bet stage's `onSent` rather than a second `sendAsync` —
  because each `sendAsync` owns its own thread and the two-stage ordering would be a race
  (AD-31) — the parked value (AD-32) and TRACE-only observability through `OutputPrinter`'s
  cmd filter, which would otherwise hide the frame (AD-33).
- **Verification is V-16 … V-20**; the verdict is V-17's `bot_bets_placed_total` leaving zero on
  `$COINS`, judged by delta.
- New open items OI-11 (ziczac dead server-side; its `12012` question still open), OI-12 (the
  `EXISTED` display-name retry bug — cross-referenced, not planned here), OI-13 (the prod legacy
  stock bot is looping), OI-14 (the `13022` response has never been seen).

Phases 1, 1b and 2 are shipped and are not touched.

## Amendment A3 (2026-09-16) — V-6 is ANSWERED: it FAILED. Phase 2 ships, on `RIK_114_ZICZAC` AD-3 + AD-9

V-6 was run on Bot-1 staging against env `394301f4-6daf-4c55-a073-502a81c00731` with three
probe groups, and the verdict is not marginal:

| group | game | rounds | `bot_bets_placed_total` |
|---|---|---|---|
| `cd77131c-7087-4eff-9a62-b50e3e674c91` "Coins" | `stockPlugin`, offset 10000, game `8a4e3c49-b8eb-494c-89f2-27c05cc5e586` | **156 endGame** | **0.0** |
| `1134449f-f040-4e95-a1e5-a2a618195cc1` | `taixiuMd5Plugin`, offset 4000 | — | **32 confirmed bets** |

The bots demonstrably bet: `BotMemory.completeRound`
(`/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/bot/strategy/BotMemory.java:192`)
logs `staked=5000, payout=0` every round on the coins group — five sends of 1000 — while the
server-confirmed counter, which is `RikEndGameMessage.betCountFor` off `mbs`, stays at zero.
An empty `mbs` on 156 consecutive rounds is the server saying **no bet of ours was ever
registered**.

**The txmd5 pair is what makes this conclusive.** One product, one brand, one host, one
`Request`/`Bet` class, the same minutes — one game settles and one does not. That rules out
per-brand host whitelisting (the `CLAUDE.md` failure this most resembles), the ws-parser
envelope, the environment, the auth path and the credentials, all at once, without a single
extra experiment.

**A confounder was tested and eliminated.** `stockPlugin` accepts only the denominations
**1k, 5k, 10k, 50k, 100k, 500k, 1M, 5M, 10M, 50M**, and the group had been running
`minBet 1000 / maxBet 10000 / betIncrement 1000` — `RandomBehaviorStrategy:107-112` draws
uniformly over `min + k·inc`, so 7 of every 10 amounts were illegal. The group was pinned to
`min = max = 1000` (the exact value the real client sends), restarted, and confirmed live
(`staked=5000` = 5 × 1000). **`bot_bets_placed_total` stayed at exactly 0.** The denomination
rule is real — it is recorded as **OI-8** — and it explains none of the zero: with no `v`
field the server never examines an amount at all.

The frames, side by side. Ours was produced by calling `ActionRequestMessage.serialize(mapper)`
on a real `Request`, not read off the source:

```
real client  [6,"MiniGame","stockPlugin",{"cmd":13002,"v":1000,"sid":3793247,"aid":1,"eid":0,"iAc":true}]
ours        ["6","MiniGame","stockPlugin",{"cmd":13002,"aid":1,"b":1000,"eid":1,"sid":3796002}]
```

Consequences for this document:

- **Phase 2 ships**, and it ships as a branch of `RIK_114_ZICZAC.md` **AD-3** +
  **AD-9** — not as AD-10's `usesStockBetBody()`, which Amendment A2 already superseded and
  which is **not to be implemented or re-described**. The Phase 2 section below is the
  replacement in full; its decisions are **AD-20 … AD-27**.
- **AD-3 is already shipped and live in this build** —
  `GameMessageTypes.forGame(Game)`
  (`/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypes.java:75`),
  called at the single site
  `/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/bot/service/BotFactory.java:172`.
  Build on it; do not redesign it. **AD-9 has not shipped** — there is no
  `GameRequestFactory` in the tree — and it is this phase's deliverable.
- **`Request` and `Bet` are frozen** (AD-25). Five other products and P_114's own
  `taixiuMd5Plugin` bet through them and settle.
- The third probe group (ziczac) had its round feed die after 2 rounds. That is a separate
  problem, it is **out of scope here**, and it is why `ZicZacBet` does **not** ship in this
  phase (AD-24).

## Amendment A2 (2026-09-16) — AD-9 / AD-10 are SUPERSEDED before they shipped; see `RIK_114_ZICZAC.md`

A **third** 114 game was captured — `ziczacPlugin` (Plinko, offset 9000, subscribe 12000) —
and its real-client bet body is `{"cmd":12002,"b":…,"c":1,"sid":…,"aid":1}`: a per-ball stake
plus a **ball count**, and **no `eid` at all**. Stock sends `v`+`eid`+`iAc`. Two games, one
product, two bodies, so **the bet body is per-GAME, not per-product** — which is exactly the
limit AD-10's own javadoc anticipated ("the flag is product-wide while the divergence is
game-wide"). Consequences for *this* document:

- **Do not implement AD-10's `usesStockBetBody()`.** Phase 2 here is unbuilt, so this is a
  clean supersession: the replacement is `docs/plans/RIK_114_ZICZAC.md` **AD-3** (a game
  dimension via `GameMessageTypes.forGame(Game)`) plus **AD-9** (a `GameRequestFactory`
  capability on the resolved provider). If **V-6 fails**, Phase 2's *substance* is unchanged —
  a RIK bet body emitting the real client's frame verbatim — but it ships as a branch of that
  mechanism, not as a product-wide boolean. V-6 itself is unchanged and still the gate.
- **AD-11 / OI-2 are honoured, not weakened.** ziczac was captured before being enabled,
  which is what OI-2 requires. It does **not** reuse `RikEndGameMessage`: its own winnings
  field (`sum(mbs[].r)`) and the absent crowd get their own classes, so nothing in Phases 1/1b
  changes.
- **OI-6 is closed for ziczac only.** That game carries a non-zero, rising `tJpV` meter, so
  `HasJackpotPool` is wired on its EndGame. Stock's and txmd5's meters are still 0 and their
  classes are untouched.

Nothing else in this plan changes; Phases 1 and 1b remain the record of what is on disk.

## Amendment A1 (2026-09-15) — AD-5 was wrong, and it shipped wrong

**`mbs` is this connection's own bet, not the room's maximum.** The backend source carries
the constants

```java
public static final String MAIN_BET_ARRAY  = "mbs";
public static final String OTHER_BET_ARRAY = "obs";
```

— `mbs` = **MAIN** bet array (mine), `obs` = **OTHER** bets (the room). The original AD-5
reasoned from the capture alone: `mbs[].wm` was byte-identical to the room-wide `mW` on
13018, so it *could* be the room maximum. It was identical because **this account was the
only bettor in every captured round**, so "my win" and "the room's biggest win" were
trivially the same number. A coincidence of a single-bettor capture was read as evidence of
scope, and the resulting "do not wire this" rationale is now **in the shipped code**, stated
confidently, in three places (`RikMaxBetSummary`, `RikEndGameMessage`, and an inverted test
pin). A confidently-wrong rationale outlives the mistake, so Phase 1b removes it rather than
amending around it.

Three things arrived with the correction and are folded in below: the **payout model is now
closed-form and verified to the unit** (AD-15), a **second capture** of a standard 114
betting-mini game exists and widens what the provider is proven against (Findings §5,
AD-18), and that second game supplies a **top-level `wm`**, so `winningsFor()` has two
disjoint sources to handle (AD-16). `OI-1` is **closed**. Nothing about the outbound-bet
question (AD-9 / V-6) is touched by any of it.

## Findings — Current State

### 1. The stock capture, and exactly what it proves

Run through `scripts/capture/infer_schema.py`. Header: `plugin stockPlugin`, `zone
MiniGame`, `subscribeCmd 13000`, window `[13000, 13999]`, `buffered 93`, `exported 50`.

| dir | cmd | CODE (cmd − 10000) | occurrences | role |
|---|---|---|---|---|
| out | 13000 | 3000 | 1 | subscribe — body is `{"cmd":13000}`, byte-identical to what `Request.subscribe()` already sends |
| in | 13000 | 3000 | 1 | subscribe response — `tFB:21000`, `tFD:1000`, `tFP:11000`, `gS:2`, `sid`, `mB:500000000`, `rmT`, `bs[2]`, plus `cH`/`htr`/`bH` |
| out | 13012 | 3012 | 1 | bare `{"cmd":13012}` — sent by the real client right after subscribe; we do not send it |
| in/out | 13002 | 3002 | 10 / 10 | updateBet in (`bs` only), bet out |
| in | 13005 | 3005 | 3 | startGame — `{"cmd":13005,"sid":…,"md5":"-"}` |
| in | 13006 | 3006 | 3 | endGame — `obs`/`ps`/`bPl`/`mbs`/`d1..d3`/`sid` |
| in | 13007 | 3007 | 55 | bet broadcast — `bs` crowd + `ps[]` per-player `{uid,b[],m}` |
| in | 13018 | 3018 | 6 | winner announcement — `mW`, optional `uN`/`wLp` |
| out | 13022 | 3022 | 3 | `{"cmd":13022,"sId":…}` — note the capital **I**; sent once per round by the real client |

**The four CODEs the engine contracts on (3000/3002/3005/3006) are all present and all
carry the fields the engine reads.** `GameMessageTypes`
(`/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypes.java:18-21,56-64`)
registers exactly those four as `CODE + offset`; 13007/13012/13018/13022 have no slot in
that contract and are simply never deserialized.

Round cadence from the timestamps: startGame → endGame = **21 s** (matches `tFB`), endGame
→ next startGame = **11 s** (matches `tFP`). Two options only, `eid` ∈ {0, 1}.

**Two properties of the capture that bound what may be concluded from it:**

- **It is a *sampled* export, not a stream.** `_meta` reports `buffered: 93`, `exported:
  50`, and `totals` says 10 inbound and 10 outbound `13002` while only 8 of each are in
  the file (`save()` keeps ≤ 8 samples per distinct structure per cmd — `scripts/capture/README.md`).
  **Shapes are reliable; counts and per-round sums are not.**
- **There was exactly one bettor in every captured round, and it was us.** EndGame `obs`
  reads `bc:1` in all three rounds while we sent 3 bets in round 3793247 — so `bc` counts
  **distinct players, not bets** (this settles `Game.crowdCountSemantic = PLAYERS` for this
  game, config not code). The consequence is the important one: **no field in this capture
  can be shown to be "mine" rather than "the room's"**, because for this account the two
  were always equal.

### 2. Field semantics, and where they are unproven

- `bs[]` (subscribe 13000, updateBet 13002) = `{eid, bc, b, v}`. `b` grows 1000 → 2000 →
  3000 as we click and `v` tracks it exactly; the shape and names are identical to
  Bom/Tip/Win79 (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g4/win79/Win79BetInfo.java:23-27`), where `b` = own stake and `v` = crowd
  aggregate. **Inferred by cross-product convention, not proven here** (we were the only
  bettor).
- `obs[]` (endGame 13006) = `{eid, bc, v}` — the same crowd shape **minus `b`**, exactly as
  `BomEndGameMessage.BetInfo` (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g2/bom/BomEndGameMessage.java:103-117`) is the subscribe shape
  minus `b`. So EndGame carries no own-stake field on this product.
- `mbs[]` (endGame) = `{b, wm, m}`, one element. **This is the MAIN bet array — this
  connection's own bet** (backend constants `MAIN_BET_ARRAY = "mbs"` /
  `OTHER_BET_ARRAY = "obs"`; Amendment A1). Its `wm` (735 / 980 / 4704) is identical to the
  `mW` on the room-wide 13018 announcement for the same `sid` *only because this account was
  the only bettor in every captured round* — that coincidence is what the original AD-5
  mistook for evidence of scope. `wLp` = `round(wm/b × 100)` = `100 + d1` (§6). `m` equals
  `b` in **3/3** rounds and is therefore not a balance; its meaning is unknown and it is
  modelled without interpretation.
- `d1` is the round result (a signed percent, −75 / +60); `d2`/`d3` are constant 0.
  `bPl[10].bP` is the price series drawn on the chart.
- `ps` on 13006 was **empty in all three rounds even though we bet in all three**, so it is
  not simply Win79's per-player settlement list. Element type unknown.

### 3. The outbound bet frame does **not** match what we send

Real client, every bet, all three rounds:

```
{"cmd":13002,"v":1000,"sid":3793247,"aid":1,"eid":0,"iAc":true}
```

Our engine (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/Bet.java:20-37`,
reached via `Request.bet` `/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/Request.java:23-25`) emits:

```
{"cmd":13002,"aid":1,"b":1000,"eid":0,"sid":3793247}
```

The stake key is **`v`, not `b`**, and there is an extra **`iAc:true`**. Every other
betting-mini product uses `b` (Bom, B52, Tip, Nohu, Win79), and 114's *Tai Xiu* product
uses `b` plus `a:false`. This is a genuine per-game divergence and it is the highest-value
unknown in this plan: if the server reads only `v`, our bets are accepted-looking and stake
nothing — the "everything looks healthy, `confirmed staked` reads 0" failure already
documented twice in `CLAUDE.md`. It is also cheap to settle on staging (V-6 below).

### 4. Engine-side facts that constrain the design

- `BettingMiniGameBot.onSubscribe` reads only `getTimeForDecision()` / `getTimeForBetting()`
  (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:408-415`) → `tFD` / `tFB`. `onUpdate` guards `if (gameStateId >
  0)` (`:472`), so an UpdateBet with no `gS` is a safe no-op — the `Win79UpdateBetMessage`
  precedent (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g4/win79/Win79UpdateBetMessage.java:22-36`). Inbound 13002 has no `gS`.
- `onEndGame` dispatches on four marker interfaces — `HasBotWinnings` (`:502`),
  `HasJackpot`/`HasBetTotals` (`:518-527`), `HasCrowdBets` (`:580`) — each independently
  optional. The only non-optional accessor is `EndGameMessage.getSessionId()`, and `sid` is
  present on 13006.
- `mapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false)` (`:905`) plus
  `@JsonIgnoreProperties(ignoreUnknown = true)` on the Win79 classes is the established
  posture for unmodelled fields.
- `startGameMd5Type()` is dereferenced only when `Game.md5` is true (`:909`,
  `/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/domain/bot/message/GameMessageTypes.java:57`); `getMd5Hash()` has **no production consumer** (grep: only
  the four existing overrides and the abstract declaration).
- `buildRequest(Game)` (`:257-263`) has no product dimension for betting-mini. The
  per-product outbound precedent is `TaiXiuMessageTypes.emitsAutoBetFlag()` →
  `TaiXiuRequest`/`TaiXiuBet` (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/taixiu/JackpotTaiXiuMessageTypes.java:60-72`).
- `Game.gameId` is read **only** by `SlotMachineBot` (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/SlotMachineBot.java:131`); for
  betting-mini it is informational.
- Brand: `ProductCode.P_114("114","RIK",…)` is **G3** (`docs/reviews/BOTGROUP_GAME_MANAGEMENT/release.md:78`:
  `G3|P_114 → 394301f4-…`), the same brand letter as Tip — so the package is `g3/rik`,
  alongside `g3/tip`.
- Staging already has the env: **`394301f4-6daf-4c55-a073-502a81c00731` ("114 Staging",
  G3/P_114)**, a known-good live-round vehicle (`docs/reviews/AFFINITY_AWARE_PROPOSAL/release.md:76`).
  `resolveZoneName` returns the default `MiniGame` for a non-custom-zone env
  (`/Users/gleb/IdeaProjects/Bot/bot-app/src/main/java/com/vingame/bot/domain/environment/model/Environment.java:96-105`), which is the zone the capture shows.

### 4b. Three other 114 mini games were running during the capture

`_meta.outsideWindow` records cmds the window rejected, and among them:
`17000 / 17005 / 17006 / 17007` and `18000 / 18005 / 18006 / 18007 / 18020`, plus bare
`14000`, `15000`, `16000`. That is **the identical CODE layout** (3000/3005/3006/3007) at
offsets 14000–18000. So 114's other mini games share the CMD scheme; what is unproven is
their *payload* shapes.

### 5. The second capture — `taixiuMd5Plugin`, offset 4000, a standard betting-mini game

`/Users/gleb/Downloads/taixiuMd5Plugin-7000-capture.jsonl` — same box, same day, same
`MiniGame` zone, `_meta`: `buffered 67`, `exported 22`, `durationSeconds 96`, 2 complete
rounds. This is the game that turns "one game's shapes" into "two games' shapes", and it is
**not** stock-shaped.

| dir | cmd | CODE | role |
|---|---|---|---|
| out | 7000 | 3000 | subscribe — `{"cmd":7000}`, again exactly what `Request.subscribe()` sends |
| in | 7000 | 3000 | subscribe response — `tFB:50000`, `tFD:3000`, `tFP:10000`, `mB:500000000`, `gS:2`, `sid`, `bs[2]`, a real 64-hex `md5`, `tP[]` (top players: `{eid,b,dn}`), `tJpV`/`tJpv2`/`tFJp`/`tFJp2`, `cH`, `htr[200]` |
| in | 7005 | 3005 | startGame — `{"cmd":7005,"sid":…,"md5":"a2575db3…"}`, a **real hash** |
| in | 7006 | 3006 | endGame — `rs`, `bs[2]`, `wm` (**optional**), `d1`/`d2`/`d3` (dice), `iJp`, `md5`, `tJpV`, `tJpv2`, `sid` |
| in | 7007 | 3007 | bet broadcast — `bs` crowd plus an optional `tP:{rP:[],aP:[{eid,b,dn}]}` |
| in | 7017 | 3017 | a periodic state frame (`tFB`/`tFD`/`rmT`/`gS`/`sid`/`md5`) — also seen as 3017/5017/8017/12017 in the stock capture's `outsideWindow`, i.e. a cross-game frame |

What it establishes, and what it costs:

- **Own winnings arrive as a top-level `wm`** — the Tip/Win79 shape. Verified
  arithmetically: a 100 000 stake on `eid 1` in round `2473044` (dice **5-4-4 = 13**, Tài)
  paid `wm: 198000`. In the round the account did not bet (`2473045`), **`wm` is absent
  entirely** and `bs[].b` is absent too, so both must tolerate absence → `0`.
- **The two sources are disjoint.** `taixiuMd5Plugin` has no `mbs`; `stockPlugin` has no
  top-level `wm`. That is what makes AD-16's rule unambiguous rather than a precedence
  guess.
- **`bs` on EndGame carries the own-stake `b`** (100 000 on the option bet, absent on the
  round not bet) alongside the room's `v` — the Bom/Tip shape, and exactly the field the
  stock game's `obs` lacks. This is what makes AD-17 possible.
- **The md5 variant is real on 114.** `md5` on subscribe, on startGame and on endGame is a
  64-hex hash, and `rs` is the round's reveal string with the dice embedded as `{5-4-4}` /
  `{5-3-1}` — matching `d1`/`d2`/`d3` in both rounds. AD-12's hedge turned out to be the
  live case, not a hypothetical.
- **`eid` is `1` and `2` here, not `0` and `1`.** Option ids are per game; `optionAffinities`
  on the `Game` record must say `{"1":1,"2":1}` for this one. Nothing in the message layer
  cares — `CrowdOption` is keyed on whatever `eid` arrives.
- **There is no `7002` frame at all**, in a 2-round capture in which the account held a bet.
  So this game appears to carry no `3002` UpdateBet; the intra-round crowd rides `3007`,
  which the four-CODE contract has no slot for (AD-7). `RikUpdateBetMessage` is simply never
  constructed for it — not a defect, but it does mean the coordinator sees crowd only at
  Subscribe and EndGame on this game, where `stockPlugin` gives it a live intra-round feed.
- **Still no outbound bet frame from a standard 114 game.** The account's bet predates the
  capture window, so AD-9 / V-6 — the `v`-vs-`b` question — is **untouched** by this
  evidence.
- The shipped `RikEndGameMessage` was run against this capture's real 7006 body, registered
  at offset 4000 through the real provider with the production
  `FAIL_ON_UNKNOWN_PROPERTIES=false`: it parses, `sid`, dice and crowd are all correct (the
  AD-4 `bs` fallback is what carries it), and **`wm: 198000` is silently dropped** because
  nothing models it. That is the gap Phase 1b closes.

### 6. The payout model — closed-form, and verified to the unit

From the game's own rules screen: bet one of two doors **Lên (Up) / Xuống (Down)**, the
round ends on a percentage move, the payout scales with that percentage, and **the house
takes 2%**.

| sid | `d1` | own stake (`mbs.b`) | `wLp` | `mbs.wm` | `stake x (100+d1)/100 x 0.98` |
|---|---|---|---|---|---|
| 3793247 | −75 | 3000 | "25" | 735 | **735** |
| 3793248 | −75 | 4000 | "25" | 980 | **980** |
| 3793249 | +60 | 3000 | "160" | 4704 | **4704** |

Exact in all three rounds, and it settles several things at once:

- **`d1` is the percent move**, and `bPl[]` is its 10-point path — `bPl[9] == d1` in **3/3**
  rounds. `wLp == 100 + d1` in 3/3, so `wLp` carries no independent information (which
  disposes of the numeric-string hazard entirely — there is nothing in it worth parsing).
- **`wm` is a GROSS RETURN INCLUDING STAKE**, not a profit: 3000 → 735 and 4000 → 980 are
  *net losses* that still report a positive `wm`. This matches the house convention exactly —
  `TaiXiuEndGameMessage.winningsFor` is `GX − gR`, the gross return, with an explicit "do
  NOT compute `GX − gB`, that nets the stake" warning
  (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/taixiu/TaiXiuEndGameMessage.java:154-161`).
- **There is no win/lose.** Every bet returns something on this game, so `bot_winnings_total`
  will be non-zero from the first settled round — which makes V-9 a fast check rather than a
  wait for a lucky round.
- **`eid` only flips the sign**, because the down door is a short: the multiplier is
  `(100 + d1)%` for Up and `(100 − d1)%` for Down, then ×0.98. **Only Up (`eid 0`) was ever
  captured** — the inversion is the user's word plus the arithmetic fit, not observed
  traffic (OI-5).
- **The fee base differs between the two 114 games, so do not generalise one formula across
  the product.** `stockPlugin` takes 2% of the **gross return** (2% of profit would give
  795 / 1060 / 4764 — wrong in all three). `taixiuMd5Plugin` pays **1.98×** on a win, i.e.
  2% of the **profit** (the Vietnamese "1 ăn 0.98" quote); 2% of gross would be 196 000, and
  the observed figure is 198 000.
- **RTP anchor: ~0.98 on both games** — for stock if `d1` is roughly symmetric about zero,
  for Tài/Xỉu by construction. A sustained measured RTP meaningfully above 1.0 on either is
  the "RTP anomaly" health check firing, not a lucky streak.

### 7. What must change outside `bot-messages/src/main`

| File | Edit |
|---|---|
| `/Users/gleb/IdeaProjects/Bot/bot-messages/src/test/java/com/vingame/bot/domain/bot/message/MessageTypesCoverageTest.java:59-64` | delete the `"114"` line from `BETTING_MINI_NOT_YET_IMPLEMENTED` (mandatory — the test asserts "listed ⇒ still throws") |
| `/Users/gleb/IdeaProjects/Bot/bot-messages/src/test/java/com/vingame/bot/domain/bot/message/MessageTypesRegistryTest.java:170-175` | add `"114"` to `containsExactlyInAnyOrder("097","098","116","118","119")` (mandatory — exact-set pin) |
| `/Users/gleb/IdeaProjects/Bot/bot-app/src/test/java/com/vingame/bot/ApplicationContextLoadsTest.java:177-179` | **no edit required** — superset check (`contains(…)`), by design |
| `/Users/gleb/IdeaProjects/Bot/bot-app/src/test/java/com/vingame/bot/infrastructure/logging/MessageTypesRegistryStartupLogTest.java:135-136` | **no edit required** — it asserts against a hand-assembled 3-provider registry, not the scan |
| `EndGameMessageSessionIdTest`, `HasCrowdBetsTest` | **no edit required** — neither is exhaustive (Win79 is absent from both) |

## Per-aspect readiness / mapping

| Aspect | State | Notes |
|---|---|---|
| Subscribe (13000) → `tFB`/`tFD` | **ready** | 21000 / 1000, same names/roles as every other product |
| StartGame (13005) → `sid` | **ready** | minimal frame; `md5` is `"-"` on stock and a real 64-hex hash on `taixiuMd5Plugin` (§5) |
| UpdateBet (13002) → crowd | **ready** | no `gS`; Win79 precedent covers it exactly. `taixiuMd5Plugin` emits no 3002 at all — the handler simply never fires (§5) |
| EndGame (13006) → `sid` | **ready** | the one load-bearing accessor |
| EndGame → crowd (`HasCrowdBets`) | **ready** | from `obs` (stock, `b` = 0) or `bs` (txmd5, real `b`) — the AD-4 fallback, now exercised by a real second game |
| EndGame → own winnings (`HasBotWinnings`) | **ready — Phase 1b** | `mbs` is the *main* (own) bet array, and txmd5 carries a top-level `wm`; the two are disjoint (AD-5 corrected, AD-16). RTP becomes measurable on 114, anchored at ~0.98 |
| EndGame → `HasBetTotals` | **ready — Phase 1b** | own stake is `mbs[].b` (stock) or `bs[].b` (txmd5); never `obs[].v`, which is the room (AD-17). Count is positions-with-stake, the TaiXiu precedent |
| EndGame → jackpot | **partial — not wired** | stock has no jackpot field at all; txmd5 carries `iJp`/`tJpV`/`tJpv2` but `tJpV` was **0 in every sample** and there is no `jpV`. Fields modelled, markers not implemented (AD-19) |
| `ps` on EndGame | **blocked** | always empty; element type unknown (AD-8). Unchanged by the second capture — txmd5 has no `ps` |
| Outbound subscribe (3000) | **ready** | real client sends `{"cmd":13000}`; `Request.subscribe()` already matches |
| Outbound bet (3002) | **blocked → Phase 2** | key is `v` + `iAc`, ours is `b`; V-6 **failed** — 156 rounds, 0 confirmed bets (Amendment A3). Ships on the `GameRequestFactory` seam (AD-20..AD-23), not on AD-10 |
| Per-bet commit `13022` | **ready → Phase 3** | unsent by us; sent by the browser once per round and by the legacy Node stock bot after every bet; Phase 2's TRACE window showed bets accepted in-round and discarded at EndGame — the per-round shape this frame explains (AD-27 amended, AD-28..AD-33) |
| Post-subscribe `13012` | **deferred → Phase 3b** | sent by the browser, **never** by the legacy bot; opens only if V-17 still reads zero after `13022` (AD-34) |
| Bet denomination ladder (1k/5k/10k/…) | **blocked — no data model** | `minBet`/`maxBet`/`betIncrement` generate an arithmetic grid; a geometric ladder needs the `CLAUDE.md` "configurable betting values per game" item (OI-8). Worked around by pinning `min = max = 1000` |
| 13007 / 13012 / 13018 / 13022 | **out of scope** | no slot in the 4-CODE contract; not modelled (AD-7) |
| Provider claiming all of 114 | **partial** | unavoidable; now proven against **two** games at two offsets with two different EndGame shapes (AD-11) |
| Registry / inventories | **ready** | two test edits, both mechanical |

## Architecture Decisions

**AD-1 — Package and naming: `com.vingame.bot.domain.bot.message.g3.rik`, class prefix
`Rik`.** `P_114` is brand **G3** (same as Tip), and the existing prefixes are *brand*
prefixes (`Bom`, `Tip`, `Nohu`, `Win79`) because the provider key is a product, not a game.
`Stock*` would read as a game name and mislead the next reader into thinking a second 114
game gets its own provider — it cannot (AD-11).

**AD-2 — One provider, four registrations, offset from the `Game` record.**
`RikGameMessageTypes` implements `GameMessageTypes` with
`@MessageTypesImpl(gameType = BETTING_MINI, products = "114")`. It hardcodes **no** CMD:
`getTypeRegistrations(offset, md5)` derives 13000/13002/13005/13006 from the `Game.offset =
10000` record. A second 114 game at offset 17000 therefore binds correctly with no code
change.

**AD-3 — Every money-shaped field is `long`. Not a preference — arithmetic.** The capture's
`13007.ps[].m` is **1 759 134 614** = 82 % of `Integer.MAX_VALUE`, and the *same
environment* has already been observed carrying `tJpV = 1 846 444 000` (86 %) through this
app (`docs/reviews/JACKPOT_SCALE_AND_RAMP/release.md:74`). `bot.deposit.amount` is
1 000 000 000 and this game's `mB` is 500 000 000, so a bot that is auto-deposited twice
sits above `Integer.MAX_VALUE` by construction. `int` is reserved for `eid`, `bc`, `gS` and
`d1..d3`; `sid` is `long` like every other product.

**AD-4 — The EndGame crowd comes from `obs`, with a `bs` fallback, and `ownBet` is 0.**
`crowdBets()` maps `obs` → `CrowdOption(eid, v, 0, bc)`, the same shape and the same `0`
`BomEndGameMessage.crowdBets()` uses (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g2/bom/BomEndGameMessage.java:44-52`) because the entry has
no `b`. **The fallback is deliberate and is the one speculative line in the provider**: if
`obs` is null the mapper reads `bs` instead, so a different 114 mini game that carries the
Bom-shaped `bs` on EndGame still feeds the coordinator. Both fields share one entry record
(`RikBetInfo(eid, bc, b, v)`; `b` absent → 0), so the fallback costs one null check.

**AD-5 (corrected 2026-09-15) — `mbs` is this connection's OWN bet, so `HasBotWinnings`
and `HasBetTotals` ARE implemented.** *The original AD-5 said the opposite and Phase 1
shipped it; Amendment A1 is the record, Phase 1b is the fix, and the wrong rationale must be
deleted from the code rather than annotated — it reads as a considered decision and would
outlive the mistake.* The backend constants settle it: `MAIN_BET_ARRAY = "mbs"`,
`OTHER_BET_ARRAY = "obs"`. `mbs[].wm` matching the room-wide `mW` on 13018 was an artefact
of a capture with exactly one bettor in it, not evidence about scope.

So on EndGame:
- **own** stake and win: `mbs[].b` / `mbs[].wm` (stock), or `bs[].b` / top-level `wm`
  (txmd5) — see AD-16 and AD-17 for the two rules;
- **room**: `obs[].v` (stock) or `bs[].v` (txmd5) — what `crowdBets()` already reads,
  unchanged;
- `obs[].v` is still **never** an own-stake source. That half of the original reasoning was
  right and survives.

Consequence, reversed: **P_114 reports real `bot_winnings_total`, `bot_bets_placed_total`
and `bot_bet_amount_total`** — it does *not* join P_097 and P_118 in the flat-zero cohort,
and RTP is verifiable against the ~0.98 anchor of AD-15. `RikMaxBetSummary` is **misnamed**
(there is no "max" in it) and is renamed in Phase 1b.

**AD-6 — `cH`, `htr` and `bH` are not modelled.** The engine reads none of them, and the
Win79 provider already establishes "tolerate, do not model" for chat and history. This
disposes of the `cH[].tst` long hazard (1 789 389 408 635 — an epoch-millis value an `int`
truncates) by never creating the field, and of `bH`'s `List<List<Integer>>` likewise. The
subscribe **fixture keeps the real 50-entry `cH` and the nested `bH` verbatim**, so the
tolerance is a regression test rather than a claim. If either is ever modelled, `tst` is
`long`.

**AD-7 — 13007, 13012, 13018 and 13022 are not modelled, in any phase of this plan.**
`GameMessageTypes` registers exactly four CODEs; there is no contract slot for a fifth, and
inventing one is a bigger change than this feature. This is also the disposition of two
hazards: `13018.wLp` arrives as the all-numeric **string** `"160"` (Jackson would silently
coerce it into an `int` field and hide the fact), and `13018.uN`/`wLp` are only 50 %
present. **If a later feature models them: `wLp` is a `String`, `uN`/`wLp` are boxed, and
`ps[].m` is a `long`** — though §6 shows `wLp == 100 + d1`, so there is no information in it
worth the parse in the first place. Not sending 13012/13022 mirrored Win79, where the bot deliberately
does not replicate the real client's full frame set and still plays. *Narrowed 2026-09-17 by
AD-27 (amended): Phase 3 **sends** `13022` (AD-28/AD-29) and adds its CODE to `OutputPrinter`'s
TRACE filter (AD-33); nothing inbound is modelled for it, so the "not modelled" half of this
decision stands in full. `13012` is Phase 3b (AD-34).*

**AD-8 — EndGame `ps` is not modelled, and is not modelled as `List<Object>` either.** It
was empty in all three captured rounds *despite this account betting in all three*, so it
is not Win79's per-player settlement list and its element type is unknown. A typed model
would be a guess; `List<Object>` would hand the next reader a list of
`LinkedHashMap`s that looks usable and is not. It is left to
`FAIL_ON_UNKNOWN_PROPERTIES=false`. A test proves a **non-empty** `ps` still parses
(inline synthetic JSON in the test body, clearly labelled — never a fixture file, since
fixtures are real frames only).

**AD-9 — RESOLVED 2026-09-16: V-6 FAILED, so Phase 2 ships. The decision rule below is kept
as the record of how it was decided; the mechanism it names is superseded by AD-20 (see
Amendment A3 and AD-10's supersession in Amendment A2).**

**AD-9 — The outbound bet stays the shared `Bet` body in Phase 1; Phase 2 forks it, and
ships only if V-6 fails.** We do not fork a frame on speculation, and we do not ship a
group that stakes nothing either — so the decision rule is pinned and executable rather
than deferred: V-6 places a real bet and reads the inbound 13002 echo. `bs[].b > 0` for the
bot's own `eid` ⇒ the server accepted `b`, Phase 2 is **dropped** and the finding recorded.
`bs[].b == 0` (or no 13002 echo at all) ⇒ Phase 2 ships `RikBet` emitting the real client's
frame **verbatim** — `{"cmd","v","sid","aid","eid","iAc"}` — fidelity over hedging.

**AD-10 — Phase 2's seam is a `default` method on `GameMessageTypes`, not a new `Game`
field.** `default boolean usesStockBetBody() { return false; }`, read by
`BettingMiniGameBot.buildRequest` and passed to `Request`. This mirrors
`TaiXiuMessageTypes.emitsAutoBetFlag()` (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/taixiu/JackpotTaiXiuMessageTypes.java:60-72`) and leaves
all five other providers byte-for-byte unchanged. **Its known limit is written into its
javadoc**: the flag is product-wide while the divergence is game-wide, so if a second 114
mini game is ever enabled its bet frame must be captured first, and if it differs the flag
moves onto the `Game` record. A test pins that every other provider returns `false`.

**AD-11 — The provider claims all of 114, this is unavoidable, and it is safe for
*liveness* but not for *fidelity*. Stated without hedging: only stock-style games are
proven, and enabling any other 114 mini game requires its own capture first.**
`@MessageTypesImpl` has no game dimension — one provider per (gameType, product) — so
`"114"` is the only key expressible. What makes that tolerable:
- The four CMDs come from the game's own offset (AD-2), and §4b shows the other 114 mini
  games use the same CODE layout.
- The only accessor whose failure is *fatal* is `getSessionId()`, and `sid` is present on
  every captured product's EndGame.
- `tFB`/`tFD` are universal across Bom/Tip/Nohu/Win79/RIK; a game that lacked them would
  degrade to `blockBetTime = 0` and `timeForBetting = 0`, i.e. a bot that still runs.
- Every modelled field is optional; unknown fields are ignored; `crowdBets()` returns an
  empty list rather than throwing, and falls back to `bs` (AD-4).

So the failure mode for an un-captured 114 game is **degraded observability, not a crash or
a wrong bet** — with the single exception of the bet body if Phase 2 ships (AD-10). This is
recorded as OI-2 and is the reason V-7 exists.

*Amended 2026-09-15:* the claim is no longer single-game. `taixiuMd5Plugin` (offset 4000,
dice, md5, top-level `wm`, `bs`-not-`obs` on EndGame, no 3002 at all) is a **materially
different shape from `stockPlugin`, and the shipped classes parse it correctly** — verified
against the real 7006 body through the real provider (§5). Two games at two offsets with two
EndGame shapes is meaningfully stronger evidence for the product-wide claim than one was.
**The rule does not relax**: offsets 14000–18000 remain uncaptured, and OI-2 still requires a
capture before any of them is enabled. What changed is that the hedge in AD-4 stopped being
speculative — it is now the code path a real second game depends on.

**AD-12 — `startGameMd5Type()` returns a real class, not `null`.** Win79 returns `null` and
pins `Game.md5 = false` to avoid an NPE at registration (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g4/win79/Win79GameMessageTypes.java:40-52`).
RIK does not get that treatment for two reasons: the 13005 frame **does** carry an `md5`
field, and the provider is product-wide (AD-11), so a future 114 game may genuinely be md5.
`RikStartGameMd5Message` costs ~20 lines and turns an operator ticking the md5 box from an
NPE into a working parse. `getMd5Hash()` returns the raw string — **`"-"` is returned as
`"-"`, never normalised to `null`** ("this game publishes no hash" is a fact worth
preserving, and nothing in production reads the value anyway).

*Amended 2026-09-15: this is no longer a hedge.* `taixiuMd5Plugin` carries a real 64-hex
hash on subscribe, startGame **and** endGame, plus the reveal string `rs` with the dice
embedded (`{5-4-4}`). A 114 game created with `Game.md5 = true` is now a supported, captured
configuration rather than a defensive possibility — had this returned `null` like Win79's,
that game would NPE at registration.

**AD-13 — `getGameState()` returns `0` on UpdateBet.** The frame carries no `gS`;
`onUpdate`'s `> 0` guard (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java:472`) makes that a no-op rather than a
reset. Copy `Win79UpdateBetMessage`'s javadoc reasoning verbatim in spirit: **do not invent
a default**, since a non-zero return would drive the phase machine off a frame that carries
no phase. Subscribe's `gS:2` is modelled but unread (the bot enters BET on StartGame).

**AD-14 — The capture is committed as evidence at
`/Users/gleb/IdeaProjects/Bot/bot-messages/src/test/resources/captures/rik-stockPlugin-13000.jsonl`, and a test binds the
fixtures to it.** `RikFixtureProvenanceTest` parses the capture and asserts every
`messages/rik/*.json` fixture is *node-equal to a frame body in it*. That converts "these
fixtures are real frames" from a javadoc claim (which is what Win79 has) into something the
build enforces, and it is the only reason to carry the file in the repo at all. 14 KB, test
scope only — it never enters the app jar.

*Amended 2026-09-15:* **both** captures are committed, and the binding is per-fixture, not
"either file". `captures/rik-taixiuMd5Plugin-7000.jsonl` (16 KB, 22 frame bodies) joins the
stock capture, and the provenance test maps a fixture to its capture **by filename prefix**
(`txmd5-*` → the txmd5 capture, everything else → the stock capture). "Matches a frame in
one of the two files" would be a weaker assertion than the one this test exists to make.

**AD-15 — The payout model is recorded as a decision, because it is what makes an RTP
number falsifiable.** `stockPlugin`: `wm = stake × (100 + d1)/100 × 0.98` for Up, sign
inverted for Down, verified exact in 3/3 rounds. `taixiuMd5Plugin`: `wm = stake × 1.98` on a
win, verified in 1/1. **`wm` is a gross return including stake on both** — the same
convention `TaiXiuEndGameMessage` spells out — so `winningsFor()` returns it verbatim and
must never net the stake off it. The expected RTP is **~0.98 on both games**; that is the
anchor an operator compares against, and a sustained RTP above 1.0 means a defect, not luck.
**Do not fold the two into one formula**: the fee bases genuinely differ (2% of gross vs 2%
of profit), and a unified "×0.98" would be wrong by 1 % on Tài/Xỉu.

**AD-16 — `winningsFor()` reads `mbs` first, then the top-level `wm`; the sources are
disjoint, and the argument is ignored.** `sum(mbs[].wm)` when `mbs` is non-null and
non-empty, else `wm`. Never their sum: on a hypothetical game carrying both, they would be
the same money and adding them doubles it, whereas precedence is correct whether `wm` is the
total or `mbs` is the breakdown. `userName` is ignored because the frame is addressed to
this connection — the same reasoning Tip and Win79 record. An absent `wm` (txmd5's
didn't-bet round) is Jackson's `0`, and `onEndGame` guards on `w > 0`, so a no-bet round
increments nothing.

**AD-17 — `HasBetTotals` is implemented, from own-stake fields only, and the count is
positions-not-clicks.** `betAmountFor` = `sum(mbs[].b)` if `mbs` is populated, else
`sum(bs[].b)`; **never `obs[].v`** (the room). `betCountFor` = the number of those entries
with a stake `> 0` — the `TaiXiuEndGameMessage.betCountFor` precedent, which reports `1` for
a round with any effective stake because the frame carries no per-click count. The RIK
frames are the same: `bc` on this product counts **distinct players** (3 clicks → `bc:1`),
so it must not be used as a bet count the way Tip uses its own `bc`. The undercount is
documented on the method, and `HasBetTotals`' own javadoc already warns that the
server-confirmed count and the locally-counted sends legitimately diverge. This is worth
having because `bot_bet_amount_total` reading a flat zero while bots visibly stake is a
known, recurring staging pain point — and an amount is exactly what this frame does carry
accurately.

**AD-18 — The second capture is committed as evidence, same as the first.**
`/Users/gleb/IdeaProjects/Bot/bot-messages/src/test/resources/captures/rik-taixiuMd5Plugin-7000.jsonl`.
It is not optional decoration: it is the only proof that the AD-4 `bs` fallback, the AD-12
md5 class and the AD-16 `wm` branch correspond to a real 114 game rather than to a
hypothesis, and each of those three has a test that reads a fixture drawn from it.

**AD-19 — The jackpot markers are still NOT implemented, and `tJpV` is modelled without
being trusted.** txmd5 carries `iJp`, `tJpV` and `tJpv2`, but `tJpV` was **0 in every
sample** and there is no `jpV` anywhere, so "`tJpV` is the pool meter" would be an
assumption — and this is precisely the field whose meaning is *inverted* between Tip/Win79
and Bom/Nohu, a trap `Win79EndGameMessage`'s javadoc devotes a section to. The fields are
modelled (they are real and present), the markers are not. Cost of waiting: zero —
`jackpotScaleEnabled` is per-game opt-in and off, and the scaler reads a `0` pool as "not
observed" → neutral. Wiring it needs one capture showing a non-zero meter, and it is then a
two-line change (OI-6).

**AD-20 — The seam is `GameRequestFactory` (`RIK_114_ZICZAC` AD-9), an optional capability on
the `forGame`-resolved provider. `usesStockBetBody()` is never written.** One method, declared
in **bot-messages** because `GameRequest` lives there while `GameMessageTypes` lives in bot-api
and bot-messages → bot-api is the only legal direction:

```java
// bot-messages …/message/request/GameRequestFactory.java
public interface GameRequestFactory {
    GameRequest requestFor(Game game, String zoneName, int offset);
}
```
```java
// BettingMiniGameBot:257
protected GameRequest buildRequest(Game game) {
    if (messageTypes instanceof GameRequestFactory factory) {
        return factory.requestFor(game, configuration.getZoneName(), offset);
    }
    return new Request(game.getPluginName(), configuration.getZoneName(), offset);
}
```

An optional capability tested with `instanceof` is the pattern the EndGame markers
(`HasBotWinnings`, `HasBetTotals`, `HasJackpotPool`) already establish, so it introduces no new
idea. `messageTypes` is injected by `BotFactory` before `initializeSubclass` runs
(`BotFactory.java:172` → `BettingMiniGameBot.java:159`), the same ordering `TaiXiuGameBot`
already relies on. `TaiXiuGameBot.buildRequest` (`:235`) overrides the whole method and is
untouched. The signature is taken **verbatim** from `RIK_114_ZICZAC` AD-9 rather than
re-derived: it is already the recorded contract, and it is the same signature ziczac's body
needs (AD-24).

**AD-21 — The capability hangs on `RikGameMessageTypes` and dispatches on `pluginName` with an
allowlist of exactly `stockPlugin`. This is a second dispatch point on the same key as
`forGame`, deliberately, and it is the one trap in this phase.** `forGame` routes `ziczacPlugin`
away to `RikZicZacGameMessageTypes`, but **`stockPlugin` and `taixiuMd5Plugin` share one
provider instance** — that is AD-11, one provider per `(gameType, product)` — and they must
**not** share a bet body: txmd5 placed 32 server-confirmed bets through the shared `Bet` during
the same window stock placed zero (Amendment A3). So the game dimension has to be re-applied
below `forGame`, and `requestFor`'s `Game` argument is exactly where.

- **Allowlist, never a denylist.** `stockPlugin` → `RikStockRequest`; **everything else,
  including `null`, an unknown 114 game and any of the un-captured offsets 14000–18000** →
  `new Request(...)`, i.e. today's behaviour bit for bit. A denylist ("anything that is not
  txmd5") would silently hand the stock body to the next 114 game someone enables, which is
  precisely what OI-2 forbids.
- Both dispatch points read the **same `private static final` plugin-name constants**, and the
  routing test asserts the full matrix in one place (Phase 2 step 6). Two switches on one key
  in one class is a maintenance hazard; the mitigation is that a reader who changes one and not
  the other fails a test that names both.
- The alternative — a third provider reached from `forGame` — was rejected: it would duplicate
  or delegate five accessors that do not differ, add a second entry to
  `GameMessageTypesForGameContractTest`'s deliberately-single specialisation list, and change
  *which message classes* stock registers, which is far more surface than a bet body needs.

**AD-22 — `RikStockBet` transcribes the captured client frame verbatim and emits nothing else:
`{cmd, v, sid, aid, eid, iAc}`. No `b`.** Fidelity over hedging, the rule AD-9 set. All eight
exported outbound 13002 frames in
`/Users/gleb/IdeaProjects/Bot/bot-messages/src/test/resources/captures/rik-stockPlugin-13000.jsonl`
carry `iAc: true` and carry no `b`, across three rounds. Emitting `b` **and** `v` is explicitly
not this phase's shape (OI-3): it sends a frame no real client sends, and if the result then
works we learn nothing about which key was read. `aid` stays the literal `1`; `eid` is the
strategy's chosen option and is still meaningful here (stock has two doors, unlike ziczac).

**AD-23 — `iAc` must carry an explicit `@JsonProperty("iAc")`, and this is not a style
preference.** Lombok's `@Getter` on a field named `iAc` generates `isIAc()`, and Jackson's
default (non-`USE_STD_BEAN_NAMING`) mangling lowercases the leading run of capitals, so the
serialized key is **`iac`** — verified empirically against the project's Jackson (**2.15.2**,
not the 2.18.2 this paragraph used to claim — Amendment B1):

```
{"cmd":13002,"v":1000,"sid":3793247,"aid":1,"eid":0,"iac":true}   // WRONG, and silent
```

Nothing fails; the frame simply carries a key the server does not know, which is the same
failure mode this whole phase exists to end. The codebase already hit this class of bug once
and hand-wrote the getter around it —
`AutoBet.AutoBetData.getIsMini()`
(`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/AutoBet.java:32-34`)
exists because Lombok's `isMini()` would have serialized as `mini`. **That precedent does NOT
transfer to `iAc`, and reading it as a recipe walks straight back into the bug — see
Amendment B1 at the bottom of this document for the measured table and the shape that actually
works.** Pin the exact key set in a
test (`containsExactlyInAnyOrder("cmd","v","sid","aid","eid","iAc")`), not just the values.
The outbound mapper is a bare `new ObjectMapper()`
(`BettingMiniGameBot.java:904`) with no naming strategy, so there is no configuration that
rescues this.

**AD-24 — `ZicZacBet` does NOT ship in this phase; `RIK_114_ZICZAC` Phase 2 keeps it.** Building
one seam for two bodies is cheaper than two passes, and the seam **is** built once — here. What
is deferred is one branch plus two classes on top of it, and the reasons to defer them are
specific:

1. **It would be unverifiable in the same breath as a verifiable change.** This phase ships with
   a baseline that makes its own verdict unambiguous (V-11..V-14: the coins group pinned at
   `min = max = 1000`, any non-zero `bot_bets_placed_total` is proof). ziczac's round feed died
   after 2 rounds, so `ZicZacBet` would ship with **nothing a Releaser can run**. Mixing the two
   in one phase means a PASS on stock reads as a PASS on both, and the ziczac half would be
   unearned — which is how AD-5 shipped wrong (Amendment A1).
2. **Its prerequisite is somebody else's fix.** A dead round feed must be resolved before any
   ziczac bet body means anything; a phase here must not depend silently on that.
3. **The deferral is cheap and bounded.** Once this phase lands, `RIK_114_ZICZAC` Phase 2 loses
   its steps 1 and 5 (the interface and the `buildRequest` branch already exist) and shrinks to
   `ZicZacBet` + `ZicZacRequest` + `RikZicZacGameMessageTypes implements GameRequestFactory` +
   tests. Note that ziczac's body reaches the seam through `forGame`, **not** through AD-21's
   allowlist, so nothing about that branch changes.

`ZicZacBet` is **capture-proven / staging-unverified** and must be labelled that way when it
does ship. What would verify it: a ziczac round feed that survives more than two rounds, then
exactly the V-11..V-14 shape against a ziczac group — `bot_bets_placed_total > 0` and
`bot_bet_amount_total ≈ sends × per-ball stake` at `c = 1`. The interface signature above is
designed against **both** captured bodies, so ziczac is the second-implementor test for the
seam even though it does not ship: `ZicZacRequest` discards `entryId` and pins `c = 1`, which
`requestFor(Game, String, int) → GameRequest` accommodates without change.

**AD-25 — `Request` and `Bet` are frozen, and the freeze is pinned by a test.** Six products bet
through them and settle, P_114's own `taixiuMd5Plugin` among them. `RikStockRequest` is
therefore **standalone** (`implements GameRequest`), not a subclass of `Request` — and that is
also forced: `Request.bet` narrows the return type to the concrete `Bet`
(pinned by `RequestTest.overrideReturnsConcreteBet`), so an override returning
`RikStockBet` would not compile unless `RikStockBet extends Bet`, which would drag `b` back in.
`RikStockRequest.subscribe()` emits the same bare `{"cmd":offset+3000}` because the capture
shows the real client's subscribe is byte-identical to ours; `chat`/`autoBet` are not on the
`GameRequest` interface and have **no production call site**, so they are not carried over.
Extend `RequestTest.bettingMiniBetHasNoAutoBetFlag`
(`/Users/gleb/IdeaProjects/Bot/bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/RequestTest.java:122-137`)
to also assert the shared body has **no `v` and no `iAc`**.

**AD-26 — The envelope's element 0 stays the string `"6"`, and it is ruled out as a cause.** The
real client sends the number `6`; we send `"6"` because ws-parser writes
`String.valueOf(type.getTypeNumber())` (`ActionRequestMessage.serialize`, line 69 of the 3.0.5
sources). It is **not ours to change** — it would be a library release — and it cannot be the
fault, because `taixiuMd5Plugin` settles through the identical envelope. Recorded here so the
next reader does not re-discover it and spend a day on it.

**AD-27 (amended 2026-09-17) — There is a rival hypothesis for the zero; Phase 2's wire
capture confirmed it is live; and the frame it names is the per-round `13022`, not the
post-subscribe `13012`. Phase 3 sends `13022` alone. `13012` is a separately gated follow-up
(AD-34), never sent in the same deploy.**

*What the original AD-27 said, kept as the record.* The real client sends two frames we never
send, and their distribution across the three captures matched the failure exactly:

| game | client sends CODE 3012 after subscribe? | CODE 3022/3018 per round? | our bets settle? |
|---|---|---|---|
| `stockPlugin` | **yes** — bare `{"cmd":13012}` | yes — `{"cmd":13022,"sId":…}` ×3 | **no** |
| `ziczacPlugin` | **yes** — `{"cmd":12012,"iM":false}` | yes — `{"cmd":12018}` ×7 | feed dead, unknown |
| `taixiuMd5Plugin` | **no** (no `7012` in the capture at all) | no | **yes** |

"The bet body is wrong" and "a required frame is missing" predicted the same observation, so
the bet body was fixed first because it is necessary under either hypothesis, and the original
text made `13012` the Phase 3 target while parking `13022` as *"per-round rather than
per-session, so a weaker candidate for a bet gate."* **That last sentence is the part that
inverted.** Three pieces of evidence, none of them argument:

1. **The legacy Node.js stock bot never sends `13012` and sends `13022` after every bet.**
   `Staging-098:/home/sgame/bot/dev-rik-coins.js` (RIK staging) and `prd-rik-coins-v2.js`
   (live on prod since 2026-09-15) place a bet and, in the next statement, fire the commit —
   fire-and-forget, no response handler:
   ```js
   socket.send(JSON.stringify([6,"MiniGame","stockPlugin",{"cmd":13002,"v":my_betting,"sid":current_session,"aid":1,"eid":choise,"iAc":true}]));
   socket.send(JSON.stringify([6,"MiniGame","stockPlugin",{"cmd":13022,"sId":current_session}]));
   ```
   Its entire wire sequence is AUTH → `{cmd:13000}` → bet → `13022`, nothing else. The browser
   client (our capture) sends `13022` **once per round, after its last bet**. We send it
   **never**. It is the only frame both non-us clients send that we do not. The user, who knows
   this backend, describes `13022` as *"the commit-to-position cmd, unique for this game."*
2. **The failure is per-round, so a per-round frame is the stronger candidate, not the weaker.**
   Phase 2's release (`docs/reviews/RIK_114_BETTING_MINI/release.md`, "The decisive new
   evidence", a 108 s `OutputPrinter` TRACE window) shows every round's bets **accepted,
   attributed and broadcast** in the in-round `13002` echo —
   `"bs":[{"eid":1,"bc":2,"b":3000,"v":6000},…]` — and then **zeroed at EndGame**: `"mbs":[]`,
   `"obs":[{"eid":1,"bc":0,"v":0},…]`, server balance a pristine `1,000,000,000` after 130
   bets per bot. A per-session enter-room frame does not explain a per-round
   accept-then-discard. A per-round commit does exactly that.
3. **Two confounders were eliminated by experiment on 2026-09-17**
   (`docs/reviews/RIK_114_BETTING_MINI/display-name-check.md`). (a) *Display names*: exactly one
   bot per probe group was nameless (`rikcoin1`, `rikzzc1`, `riktxm2`, all three `SetName` →
   `EXISTED` at registration); all three were named by hand via
   `/gwms/v1/bot/update-fullname.aspx`, gateway-verified by login replay
   (`data[0].fullname` present) and by the bots' own `[VerifyToken]` lines after a `/restart`
   of all three groups. Coins: `bot_bets_placed_total` delta **0 over 24 EndGame rounds**,
   `confirmed staked: 0` on every line; TxMd5 control: +12 bets / +12 rounds, 1:1. (b)
   *Denominations*: the coins group has been pinned at `min = max = 1000` — a legal value, the
   one the real client sends — since Amendment A3; still 0.

**The honest limit, recorded before the experiment so it cannot be forgotten after it.** The
legacy bot **never reads settlement**: its `13006` handler ignores the payload, and `mbs`,
`wm` and `obs` appear nowhere in six versions of the file. So it narrows the candidates to
`13022` without proving that `13022` is sufficient. The browser client is the only client
*proven* to settle, and it sends **both** `13012` and `13022`. Phase 3 therefore tests `13022`
**alone** — one variable, the discipline that made Amendment A3 readable — and `13012` is an
explicit, separately gated step (AD-34) that opens only if V-17 still reads zero. **Do not
send both at once**: if the pair then worked, we would not know which frame was read, which is
OI-3's objection to the `b`+`v` hedge, applied to frames.

The table, updated with what the legacy bot adds:

| client | `13012` after subscribe | `13022` | settles? |
|---|---|---|---|
| browser (our capture) | yes, once | once per round, after the last bet | **yes** (proven, `mbs.wm` non-zero in 3/3 rounds) |
| legacy Node bot (`*-rik-coins*.js`) | **never** | **after every bet**, fire-and-forget | unknown — never reads settlement |
| ours, Phases 1-2 | never | never | **no** — 0 across ~6,500 rounds |
| ours, Phase 3 | never | after every bet (AD-29) | **the experiment** |

**AD-28 — The seam is one `default` method on `GameRequest`; `Request` and `TaiXiuRequest` are
not edited, and the default is pinned by test.**

```java
// bot-messages …/message/request/GameRequest.java
/** The per-bet commit frame, if this game requires one. Default: none. */
default Optional<ActionRequestMessage> commit(long sid) { return Optional.empty(); }
```

`RikStockRequest.commit(sid)` overrides it to return `Optional.of(new RikStockCommit(cmdPrefix
+ 3022, zoneName, pluginName, sid))`. Nothing new at the resolution level: the only way a bot
holds a `RikStockRequest` is `RikGameMessageTypes.requestFor` matching the AD-21 allowlist
(`RikGameMessageTypes.java:211-216`), so the commit is already scoped to `stockPlugin` and
**only** `stockPlugin` — `taixiuMd5Plugin`, ziczac, every un-captured 114 game and all five
other products hold a `Request`, inherit the default and never build a commit. The method is
on `GameRequest` rather than on `GameRequestFactory` because it is a *frame the request builds*,
like `bet`, not a *resolution the provider makes*. `Request.java` and `TaiXiuRequest.java` are
not touched (`git diff` on both must be empty after this phase), and `RequestTest` and
`TaiXiuRequestTest` each gain one test asserting `commit(sid).isEmpty()` — pinned, not merely
un-edited, because a well-meaning "let's commit everywhere" edit would compile.

**AD-29 — `RikStockCommit` transcribes the frame verbatim — exactly `{"cmd": offset+3022,
"sId": sid}` — and is sent after every bet, matching the legacy bot, not once per round.**
Body class `CommitData extends Body` with a single `long sId` field, `implements
CmdAwareMessage` like `RikStockBet`, envelope assembled by ws-parser from `zoneName` +
`pluginName` as for every other frame (element 0 stays `"6"`, AD-26). Per-bet rather than
once-per-round because: the bot never knows which bet is its last (the strategy decides
tick by tick); there is no bet-window-close hook in the scenario; and the legacy bot ran on
prod for two days with a commit after every bet and no server complaint. **Fallback, recorded
now:** if the `13022` response captured in V-19 indicates the server wants exactly one commit
per round (an error code, or a second commit rejected), the cheapest once-per-round
approximation is "commit on the first bet of each `sid` only" — keyed on a sid change in the
supplier — and that is the *next* experiment, not a design to build ahead of the evidence.

**AD-30 — Any wire key whose first two characters are `<lowercase><UPPERCASE>` is serialized
by an explicit `@JsonProperty` on a getter-less field, and its test asserts both the literal
key and the absence of the folded key. `sId` is the third such key in two days; this is the
rule, so the fourth is caught at design time.** `sId` has the identical shape to `iAc`:
Lombok's `@Getter` generates `getSId()`, Jackson de-mangles by lower-casing the **entire
leading uppercase run** (`SI` → `si`), and the wire key becomes **`sid`** — a key that appears
on the bet frame, so a reviewer reading the wire sees a plausible frame and nothing wrong. The
server would ignore it, silently, exactly Amendment B1's failure. Therefore `RikStockCommit
.CommitData.sId` carries `@JsonProperty("sId")` **and** `@Getter(AccessLevel.NONE)` — the only
shape correct under every `lombok.config` (B1's measured table: with the getter present, the
result depends on the repo-root `copyableAnnotations` line; with a hand-written getter it
double-keys regardless) — and `RikStockCommitTest` asserts `containsExactlyInAnyOrder("cmd",
"sId")` **and** `doesNotContain("\"sid\"")`. The general rule, for every future frame:

1. At **design time**, the plan names the key and its folded form (`iAc`→`iac`, `sId`→`sid`,
   ziczac's `iM`→`im`).
2. The field carries `@JsonProperty("<key>")` and `@Getter(AccessLevel.NONE)`; there is no
   getter-naming trick that works (B1 §1) and none is attempted.
3. The test asserts the **serialized** key set and `doesNotContain` the folded key; a
   round-trip test passes with the wrong key on both sides and proves nothing.
4. If the key also appears elsewhere in the protocol in its folded form (`sid` does), say so in
   the test's comment — that is what makes the failure invisible on the wire.

**AD-31 — The commit is sent from the bet stage's `onSent` callback, not from a second
`sendAsync` stage. Bet-before-commit ordering is then structural; under two stages it is a
race.** The brief asked for verification that "all `sendAsync` suppliers/conditions run on the
single scenario thread." **They do not.** In ws-parser 3.0.5 (`websocket-parser-core-3.0.5-sources.jar`,
`scenario/processors/SendAsync.java`):

- the constructor creates **one scheduler per `SendAsync` instance** —
  `this.scheduler = VirtualThreads.newScheduler("ws-send-async-")`, a
  `newSingleThreadScheduledExecutor` — so a second stage is a second thread, and the comment at
  `BettingMiniGameBot.java:933-934` ("sendAsync's supplier + condition run on a scenario-owned
  pool-N-thread-1") is true *per stage*, not per pipeline; Dev corrects that comment in the
  same edit;
- the runnable is: condition → `messageSupplier.get()` → `client.send(message.serialize(…))`
  → **`onSent.accept(new SentMessageContext(index, gate))`** — the callback runs on the same
  thread, immediately after the bet's `client.send` has returned;
- `interval == 0L` means `scheduler.schedule(runnable, delay)` — a **one-shot** whatever the
  mode says — so a second `INFINITE` stage must carry an interval, i.e. a second fixed-rate
  timer per bot for every product, or a build-time capability probe to avoid adding it;
- `VingameWebSocketClient.send(String)` (`:587-610`) is `channel.writeAndFlush(new
  TextWebSocketFrame(text))` — Netty orders writes from one thread — then feeds the SENT frame
  back through the registered scenarios, which is how `OutputPrinter` prints it.

So the shipped shape is `OutboundMessage.Builder.onSent(mdcConsumer(afterBetSent(mapper)))` on
the **existing** bet stage, where `afterBetSent` pops the parked commit (AD-32) and calls
`client.send(commit.serialize(mapper))` with the scenario's own mapper. Bet and commit leave on
the same thread, back to back, in that order, into an ordered channel — the legacy bot's two
consecutive `socket.send` calls, transliterated. Rejected alternatives: **a second `sendAsync`
stage** (ordering race between "supplier returned" and `client.send(bet)`; a second timer per
bot or a `request.commit(0L).isPresent()` probe at scenario build; 0–1000 ms of lag where the
legacy bot has microseconds); **a scheduled send from the bet supplier** on the bot's own
executor (all of the above plus a new executor to leak); **sending from inside the bet
supplier** (impossible — the supplier *returns* the bet; anything it sends leaves first).
`Bot.client` is `protected VingameWebSocketClient` (`Bot.java:94`) and `send(String)` is
public, so no library change is needed.

**AD-32 — One parked value, popped on send, cleared on reconnect; `onSent` must never throw.**
`private final AtomicReference<Optional<ActionRequestMessage>> pendingCommit`, the same shape
as `pendingDecision` (`BettingMiniGameBot.java:133`). The bet supplier (`bet()`, `:769-812`)
sets it to `request.commit(currentSid)` immediately before `return request.bet(…)` — for every
product but stock that is `Optional.empty()`, so the callback below is a no-op and no product
gains a frame, a timer or a log line. `afterBetSent` does `pendingCommit.getAndSet(Optional
.empty()).ifPresent(m -> client.send(m.serialize(mapper)))`. `beforeReconnect` (`:614-632`)
clears it alongside `pendingDecision`. There is deliberately **no `onEndGame` clear**: the
value is written and consumed inside one runnable on one thread, so it cannot survive to a
round boundary; a clear there would be cargo cult and would suggest to the next reader that it
can. **`afterBetSent` must be exception-free by construction**: an exception escaping a
`scheduleAtFixedRate` task cancels that task for good, i.e. the bot's bet loop dies silently
for the life of the connection. `client.send` already swallows everything internally; wrap the
`serialize` call in `try/catch (RuntimeException)` → `log.warn` — unreachable on a two-field
body, and if it ever fires persistently, that WARN *is* the signal.

**AD-33 — Observability is TRACE-only, via `OutputPrinter`'s cmd list; no new INFO/DEBUG line
and no new metric.** `OutputPrinter` filters by an explicit cmd list
(`OutputPrinter.java:24-31`, `Qualifier::cmd` OR-reduced) and `BettingMiniGameBot.onStart`
passes `[subscribeCmd, updateBetCmd, startGameCmd, endGameCmd]` (`:981-986`, consumed at `:990`) — so the 108 s
TRACE window `release.md` used **would show neither our `13022` nor any `13022` response**,
and V-19 would be unrunnable. `onStart` adds `offset + COMMIT_CODE` (`private static final int
COMMIT_CODE = 3022` in `BettingMiniGameBot`, commented as print-filter-only) to that list.
This is not "modelling 13022" in AD-7's sense — nothing deserializes it — it is one more
integer in a TRACE filter; on every other product no such frame exists and the entry matches
nothing. The callback also logs `Bot {}: sending commit sid={}` at **TRACE** — its rate is per
bet, so per `CLAUDE.md` it can be nothing higher. **No metric**: `bot_messages_total` counts
*inbound* handler hits and an outbound commit counter would muddle that; the verdict metric is
V-17's `bot_bets_placed_total`, which only the server can move.

**AD-34 — `13012` is Phase 3b, opens only if V-17 still reads zero after `13022`, and after
*that* the next step is a back-office question, not another frame.** Specified now so the
failure branch is a decision already made: `GameRequest` gains `default
Optional<ActionRequestMessage> enterGame() { return Optional.empty(); }`; `RikStockRequest`
returns the bare `{"cmd": offset+3012}`; `botBehaviorScenario` adds, immediately after
`.onMessage(subscribeClass, …)`, `if (request.enterGame().isPresent()) stage =
stage.send(() -> request.enterGame().get())` — `Send` defaults to `SendMode.ONCE` and fires
on the first message to reach the stage, which is the subscribe response, i.e. the position the
capture shows. `request` is built in `initializeSubclass` before the scenario, so the
`isPresent()` is a static per-bot decision, not a runtime probe. Phase 3b keeps `13022` in
place (the browser client sends both) and changes one thing. If V-17 reads zero after **both**,
the frame hypotheses are exhausted — envelope (AD-26), whitelisting (txmd5 control),
denomination (OI-8), display names and `13012`/`13022` all eliminated — and the question goes
to the RIK back-office/game team with the `release.md` capture attached. Record it; do not keep
guessing at bodies.


## Plan

### Phase 1 — the inbound message layer, the provider and the inventories

**SHIPPED 2026-09-15, and two of its decisions were wrong.** Read this phase as a record of
what is on disk, **not** as instructions to follow: the AD-5 marker omissions in step 6 and
the single-capture provenance in steps 1-2 are superseded by Phase 1b (Amendment A1). The
rest of it — the classes, the provider, the registry edits — stands.

One Dev session. Ships alone and is complete on its own: after it, a 114 betting-mini group
can be created, started, subscribed and can observe rounds.

1. **Copy the evidence in** (AD-14):
   `cp /Users/gleb/Downloads/stockPlugin-13000-capture.jsonl
   /Users/gleb/IdeaProjects/Bot/bot-messages/src/test/resources/captures/rik-stockPlugin-13000.jsonl`
   — byte-for-byte, `_meta` line included.
2. **Fixtures** under `/Users/gleb/IdeaProjects/Bot/bot-messages/src/test/resources/messages/rik/`, each the `body`
   object of a real frame, copied verbatim from the capture (no trimming — AD-6 wants the
   full `cH`/`bH`):
   - `subscribe.json` — inbound 13000
   - `startGame.json` — inbound 13005 (`{"cmd":13005,"sid":3793248,"md5":"-"}`)
   - `updateBet.json` — inbound 13002 (pick the `b:3000` sample, so a non-zero own-stake is
     asserted)
   - `endGame.json` — inbound 13006 for `sid:3793249` (the `mbs.wm = 4704` round, so the
     win is not confusable with the stake)
   There is deliberately **no `startGameMd5.json`**: 13005 always carries `md5`, so
   `startGame.json` is the md5 fixture too (the test says so).
3. **Message classes** in
   `/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/g3/rik/`, all
   `@JsonIgnoreProperties(ignoreUnknown = true)`, all `@JsonCreator`/`@JsonProperty`
   constructors (the module's house style), all money fields `long` (AD-3):
   - `RikBetInfo` — `record (int eid, int bc, long b, long v)`, the `Win79BetInfo` shape;
     serves `bs` **and** `obs` (AD-4).
   - `RikMaxBetSummary` — `record (long b, long wm, long m)` for `mbs`, javadoc'd per AD-5
     as ambiguous and metric-forbidden. **~~Both the name and that javadoc are wrong~~** —
     Phase 1b renames it `RikMainBetSummary` and wires it.
   - `RikSubscribeMessage extends SubscribeMessage implements HasCrowdBets` — `sid`, `gS`,
     `tFB`, `tFD`, `tFP`, `rmT`, `mB`, `wm`, `m`, `tTU`, `tSv`, `tLv`, `tLp`, `tSp`, `iab`,
     `bs`. `getTimeForBetting() → tFB`, `getTimeForDecision() → tFD`. **No `cH`/`htr`/`bH`**
     (AD-6).
   - `RikStartGameMessage extends StartGameMessage` — `sid`.
   - `RikStartGameMd5Message extends StartGameMd5Message` — `sid`, `md5`; `getMd5Hash()`
     returns the raw string (AD-12).
   - `RikUpdateBetMessage extends UpdateBetMessage implements HasCrowdBets` — `bs`;
     `getGameState() → 0` with the AD-13 javadoc.
   - `RikEndGameMessage extends EndGameMessage implements HasCrowdBets` — `sid`, `d1`,
     `d2`, `d3`, `obs`, `bs` (fallback, absent on this game), `mbs`. `getSessionId() → sid`.
     **Implements no other marker** (AD-5); the javadoc states why, naming the inflation
     factor. `ps` and `bPl` unmodelled (AD-8).
4. **`RikGameMessageTypes`** — `@Component`, `@MessageTypesImpl(gameType =
   GameType.BETTING_MINI, products = "114")`, five accessors. Javadoc carries AD-11 (what
   the capture proves and what it does not) and AD-2 (offset lives on the `Game`).
5. **Inventory edits** (§7): delete `"114"` from `BETTING_MINI_NOT_YET_IMPLEMENTED`; add
   `"114"` to `MessageTypesRegistryTest`'s exact set. Nothing else in `src/main` changes.
6. **Tests** — `/Users/gleb/IdeaProjects/Bot/bot-messages/src/test/java/com/vingame/bot/domain/bot/message/g3/rik/`:
   - `RikGameMessageTypesTest`, modelled on `Win79GameMessageTypesTest` (offset 10000,
     `md5=false`): each of the four fixtures deserializes to the right class through
     `BettingMiniMessage`; `tFB=21000`/`tFD=1000`; `sid` on StartGame and EndGame;
     `crowdBets()` has 2 entries with the `eid` set `{0,1}` **looked up by `eid`, never by
     index** (the capture's `bs` arrives as `[{eid:1},{eid:0}]`); `getGameState()` is 0;
     a fifth test registers with `md5=true` and asserts `RikStartGameMd5Message` with
     `getMd5Hash() == "-"`; a sixth asserts a **non-empty synthetic `ps`** still parses
     (AD-8); a seventh asserts the endGame implements **none** of `HasBotWinnings` /
     `HasBetTotals` / `HasJackpot` / `HasJackpotPool` — the AD-5 decision, pinned so a later
     "helpful" addition has to argue with a test. **~~This pin is wrong and Phase 1b inverts
     it~~** — `HasBotWinnings` and `HasBetTotals` belong there; only the two jackpot markers
     stay omitted (AD-19).
   - `RikFixtureProvenanceTest` (AD-14) — every `messages/rik/*.json` is node-equal to some
     frame body in `captures/rik-stockPlugin-13000.jsonl`.
7. `mvn clean install` green; `MessageTypesCoverageTest`, `MessageTypesRegistryTest` and
   `ApplicationContextLoadsTest` all pass without further edits.

### Phase 1b — correct the AD-5 code, wire the payout metrics, commit the second game

One Dev session, all inside `bot-messages`. Phase 1 is already on disk; this phase changes
**five shipped files** and adds evidence, fixtures and one test class. It is independently
shippable (it makes 114 report real payout metrics) and it does not touch Phase 2's subject
at all.

**1. Bring the second capture in** (AD-18), byte-for-byte including `_meta`:
```
cp /Users/gleb/Downloads/taixiuMd5Plugin-7000-capture.jsonl \
   /Users/gleb/IdeaProjects/Bot/bot-messages/src/test/resources/captures/rik-taixiuMd5Plugin-7000.jsonl
```

**2. `RikMaxBetSummary` → `RikMainBetSummary`** (rename the file, the record and both
references in `RikEndGameMessage`). The name is the first thing that has to go: there is no
"max" in this array. **Delete the entire `<h2>Do NOT wire this to a metric (AD-5)</h2>`
section** — it is confidently wrong and must not survive as a comment. The replacement
javadoc states: `mbs` = `MAIN_BET_ARRAY`, this connection's own bet (backend constants,
Amendment A1); `b` = own stake; `wm` = own **gross return including stake** (AD-15), not a
profit — 3000 → 735 is a net loss that still reports 735; `m` equals `b` in 3/3 rounds so it
is not a balance, meaning unknown, modelled without interpretation.

**3. `RikEndGameMessage` — the substantive change.**
- **Delete the `<h2>This class implements HasCrowdBets and NOTHING else (AD-5)</h2>` block
  in full**, including the "P_114 stock reports … as zero" paragraph, which is now the
  opposite of what the class does.
- Add fields: `wm` (`long`, top-level own gross return — absent on stock, present-when-won
  on txmd5) and, per AD-19, `iJp` (`boolean`), `tJpV`, `tJpv2` (`long`). Do **not** model
  `rs`, `md5`, `ps` or `bPl` (AD-6/AD-8 reasoning is unchanged; `rs` is the reveal string
  and nothing reads it).
- `implements HasCrowdBets, HasBotWinnings, HasBetTotals`. **Not** `HasJackpot`,
  **not** `HasJackpotPool` (AD-19 — keep that omission pinned and explain the `tJpV`
  inversion trap in one sentence).
- `winningsFor(String)` per AD-16: `mbs` non-empty → `sum(mbs[].wm)`, else `wm`. Argument
  ignored; javadoc says why.
- `betAmountFor(String)` per AD-17: `mbs` non-empty → `sum(mbs[].b)`, else `sum(bs[].b)`;
  **never `obs`**. `betCountFor(String)`: the number of those entries with stake `> 0`,
  javadoc'd as **positions, not clicks**, citing the `TaiXiuEndGameMessage.betCountFor`
  precedent and the fact that `bc` counts players on this product.
- `crowdBets()` is **unchanged** — `obs` first, `bs` fallback. The fallback now has a real
  second game behind it; say so and drop the word "speculative".

**4. `RikGameMessageTypes` javadoc.** Add the second capture under "What the evidence is"
(plugin, offset 4000, 22 frames, the shape deltas). Rewrite "What the capture does NOT
prove" to the amended AD-11: two games, two offsets, two EndGame shapes, rule unchanged for
offsets 14000-18000. Update `startGameMd5Type()`'s javadoc — the md5 path is **live**, not a
hedge (AD-12 amended). Leave the outbound-bet section exactly as it is; §5 changed nothing
about it.

**5. `RikBetInfo` javadoc — tighten, do not overclaim.** `b` = own stake is now supported by
arithmetic rather than by convention alone: txmd5's EndGame reports `b:100000` on the option
this account bet and pays `wm:198000` = 1.98 × 100 000. **`v` is still unresolved** — this
account was the only bettor in *both* captures, so `v == b` throughout and "room including
self" versus "others only" remains undecided (OI-4). Also note `b` is *absent*, not zero, on
a round the bot did not bet.

**6. Fixtures** under `.../resources/messages/rik/`, all verbatim frame bodies from the new
capture:
- `txmd5-subscribe.json` — 7000
- `txmd5-startGame.json` — 7005, the real 64-hex hash
- `txmd5-endGame.json` — 7006 `sid 2473044`: `wm:198000`, `bs[].b:100000`, dice 5-4-4
- `txmd5-endGame-noBet.json` — 7006 `sid 2473045`: **no `wm`, no `b`** — the absence case

**7. Tests.**
- `RikGameMessageTypesTest`: **invert the AD-5 pin.** The `endGameImplementsNoPayoutMarkers`
  test becomes `endGameExposesOwnWinningsAndStake` — `isInstanceOf(HasBotWinnings)`,
  `isInstanceOf(HasBetTotals)`, still `isNotInstanceOf(HasJackpot)` /
  `isNotInstanceOf(HasJackpotPool)` (AD-19) — with values off the real stock fixture:
  `winningsFor("anything") == 4704`, `betAmountFor == 3000`, `betCountFor == 1`. Its comment
  must carry the *corrected* reasoning (backend constants), not a struck-through version of
  the old one.
- New `RikTaiXiuMd5GameShapeTest` — the second game, registered at **offset 4000** through
  the real provider: subscribe → `tFB 50000`/`tFD 3000` and crowd keyed `{1, 2}`; startGame
  with `md5=true` → `RikStartGameMd5Message` carrying the 64-hex hash; endGame →
  `winningsFor == 198000`, `betAmountFor == 100000`, `betCountFor == 1`, crowd via the `bs`
  fallback; and the no-bet round → **`winningsFor == 0`, `betAmountFor == 0`,
  `betCountFor == 0`** (the absence case is the one that protects the metric).
- `RikFixtureProvenanceTest`: map each fixture to its capture **by filename prefix**
  (`txmd5-*` → the txmd5 capture, else stock), and add the twin intactness check — 22
  bodies, cmds contain `7000`, `7005`, `7006` and **do not contain `7002`** (the absent
  UpdateBet is itself a finding worth pinning).

**8.** `mvn clean install` green; no change to `MessageTypesCoverageTest` /
`MessageTypesRegistryTest` (Phase 1 already made those edits).

### Phase 2 — the RIK stock bet body (`v` + `iAc`), on the `GameRequestFactory` seam

**V-6 failed (Amendment A3), so this phase ships.** One Dev session. Everything is additive
except three lines in `BettingMiniGameBot` and one assertion widened in `RequestTest`; no
existing product's bet frame changes by a byte. It is independently shippable and its verdict
is unambiguous (V-11..V-15, with V-12 as the verdict). It supersedes the `usesStockBetBody()` text this section used to
carry — **do not implement AD-10**.

Decisions for this phase: **AD-20** (the seam), **AD-21** (where the dispatch lives),
**AD-22** (the frame, verbatim), **AD-23** (`@JsonProperty("iAc")`), **AD-24** (no `ZicZacBet`
here), **AD-25** (`Request`/`Bet` frozen), **AD-26** (the `"6"` envelope), **AD-27** (the rival
hypothesis and why it is Phase 3).

1. **`GameRequestFactory`** — new interface at
   `/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/GameRequestFactory.java`,
   exactly the AD-20 signature. Javadoc: why it lives in bot-messages and not on
   `GameMessageTypes` (module direction — `GameRequest` is a bot-messages type and bot-api
   cannot see it); why it is `instanceof`-optional rather than a `default` method (a default
   would put a bot-messages return type on a bot-api interface); and that it is **per game**
   only because the provider it hangs on is already `forGame`-resolved (AD-3).

2. **`RikStockBet`** at `.../message/request/RikStockBet.java` — an `ActionRequestMessage`
   whose `Body` emits **exactly** `{"cmd":<offset+3002>,"v":<stake>,"sid":<sid>,"aid":1,
   "eid":<option>,"iAc":true}`. Field order is irrelevant; the **key set is not**. Mirror
   `TaiXiuBet`'s shape (`@Getter`/`@Setter` static `BetData extends Body`, final fields) with
   one addition that is mandatory: **`@JsonProperty("iAc")` on the `iAc` field** (AD-23) —
   without it the key serializes as `iac`, silently. Javadoc: the stake key is `v` and there is
   **no `b`** (AD-22); `iAc` was `true` in 8/8 captured outbound frames and its meaning is
   unknown, so it is transcribed, not interpreted; the `"emit both b and v"` hedge is OI-3 and
   is deliberately not this.

3. **`RikStockRequest implements GameRequest`** at `.../message/request/RikStockRequest.java` —
   standalone, **not** a subclass of `Request` (AD-25, and the covariant-return pin makes
   subclassing impossible anyway). `subscribe()` returns
   `new SubscribeToLobbyMessage(zoneName, pluginName, new Body(offset + 3000))`, byte-identical
   to `Request.subscribe()` and to the captured client frame; `bet(amount, entryId, sid)`
   returns a `RikStockBet`. No `chat`, no `autoBet` — neither is on `GameRequest` and neither
   has a production call site.

4. **`RikGameMessageTypes implements GameRequestFactory`** (AD-21). Add a
   `private static final String STOCK_PLUGIN = "stockPlugin";` beside the existing
   `ZICZAC_PLUGIN` constant (`RikGameMessageTypes.java:112`) and implement:
   `requestFor(game, zoneName, offset)` → `RikStockRequest` when
   `STOCK_PLUGIN.equalsIgnoreCase(game.getPluginName())`, else `new Request(...)`. Match
   **case-insensitively** on the exact name, the same rule `forGame` uses. Javadoc must state
   the allowlist reasoning and, explicitly, that `taixiuMd5Plugin` keeps the shared `Bet`
   because it demonstrably settles with it. `RikZicZacGameMessageTypes` is **not** touched in
   this phase (AD-24), so a ziczac bot keeps falling through to `new Request(...)`, unchanged.

5. **`BettingMiniGameBot.buildRequest`** (`:257-263`) gets the three-line `instanceof` branch
   from AD-20, with a comment naming AD-20/AD-21 and the fact that the fallback is the
   pre-existing behaviour for every provider that does not implement the capability.
   `TaiXiuGameBot.buildRequest` (`:235`) is untouched.

6. **Tests.**
   - `RikStockBetTest` (bot-messages) — serialize the body with a bare `new ObjectMapper()` and
     assert the key set is **exactly** `{cmd, v, sid, aid, eid, iAc}`: `containsExactlyInAnyOrder`,
     plus an explicit `assertThat(json).doesNotContain("\"iac\"")` and
     `.doesNotContain("\"b\"")`. The `iac` assertion is the AD-23 regression and must name it in
     a comment — it is one missing annotation away from being reintroduced. Also assert
     `cmd == offset + 3002` for a non-zero offset (10000 → 13002) so the CMD derivation is
     pinned, and serialize the whole `ActionRequestMessage` once to record that element 0 is the
     string `"6"` (AD-26) rather than leaving it to be re-discovered.
   - `RikStockRequestTest` — `subscribe()` body cmd is `offset + 3000` and the frame is
     node-equal to what `Request.subscribe()` produces for the same inputs.
   - **Routing matrix**, one test, asserting every row through
     `RikGameMessageTypes.forGame(game)` and then `requestFor(...)` where the resolved provider
     supports it (AD-21): `stockPlugin` → `RikStockRequest`; `taixiuMd5Plugin` → `Request`;
     an un-captured 114 plugin name and a `null` name → `Request`; `ziczacPlugin` →
     `RikZicZacGameMessageTypes`, which does **not** implement `GameRequestFactory` in this
     phase, so `buildRequest` falls back to `Request`. Pin the ziczac row with a comment
     pointing at AD-24 so the next phase changes it deliberately.
   - **Capability exclusivity**, modelled on `GameMessageTypesForGameContractTest`'s real
     component scan (`bot-messages/src/test/java/.../GameMessageTypesForGameContractTest.java:70-77`):
     enumerate every registered betting-mini provider and assert **`RikGameMessageTypes` is the
     only one** that `instanceof GameRequestFactory`. A hand-written list of five would not
     cover the seventh provider the next brand brings.
   - **`Request`/`Bet` frozen** (AD-25): widen
     `RequestTest.bettingMiniBetHasNoAutoBetFlag` to assert the shared body still contains
     exactly `{cmd, aid, b, eid, sid}` **and no `v`, no `iAc`**.
   - **Engine-level**: a `BettingMiniGameBot` test that a bot whose `messageTypes` is the
     stock-resolved provider builds a `RikStockRequest`, and that a bot on any other provider
     builds a `Request` — the `instanceof` branch asserted at the seam, not only at the factory.
     **Name it `BettingMiniGameBotRikRequestDispatchTest`**: `BettingMiniGameBotRikDispatchTest`
     and `BettingMiniGameBotZicZacDispatchTest` already exist in
     `/Users/gleb/IdeaProjects/Bot/bot-engine/src/test/java/com/vingame/bot/domain/bot/core/`
     and are about EndGame *payout-marker* dispatch, which is a different thing.

7. `mvn clean install` green. **No edit to `MessageTypesCoverageTest`,
   `MessageTypesRegistryTest`, `ApplicationContextLoadsTest` or
   `MessageTypesRegistryStartupLogTest`** — no product or bean is added, and the boot line must
   still read `BETTING_MINI 6 products [097, 098, 114, 116, 118, 119]`. If one of them needs a
   change, the design has drifted from AD-20/AD-21.

### Phase 3 — send the per-bet commit `13022` on `stockPlugin` — **V-12 read zero, so this ships**

**Rewritten 2026-09-17** (AD-27 amended). The previous Phase 3 sent the post-subscribe `13012`;
that is now Phase 3b (AD-34) and opens only if this phase's V-17 still reads zero. Phases 1, 1b
and 2 are shipped and deployed and are not touched. One Dev session. Everything is additive
except: one `default` method on `GameRequest`, one `override` on `RikStockRequest`, and inside
`BettingMiniGameBot` one field, one `onSent` callback, one line in `bet()`, one line in
`beforeReconnect`, one integer in `onStart`'s cmd list and one corrected comment. **No product
other than P_114 `stockPlugin` gains a frame, a timer or a log line** — the test in step 7
proves it on the bot, not only on the request.

Decisions for this phase: **AD-27 (amended)** (why `13022`, why alone), **AD-28** (the seam),
**AD-29** (the frame and the per-bet cadence), **AD-30** (the `sId` trap, as a rule), **AD-31**
(`onSent`, not a second `sendAsync`), **AD-32** (the parked value and the never-throw rule),
**AD-33** (TRACE-only observability), **AD-34** (`13012` as the separately gated 3b).

**Pre-flight, before writing a line.** Run V-16 (the pre-deploy baseline) on the box, or have
the Releaser do it: `$COINS bot_bets_placed_total` must still be `0` and `$TXMD5`'s must be
climbing. If coins is already non-zero, stop — the premise changed under this plan.

1. **`GameRequest.commit(long)`** — add the AD-28 `default` method to
   `/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/GameRequest.java`.
   Javadoc: what a commit is (a per-bet frame some games require after the bet — RIK stock's
   `13022`), that the default is "none" and that `Request`/`TaiXiuRequest` deliberately inherit
   it; point at AD-27/AD-28. Import `java.util.Optional`. **Do not edit `Request.java` or
   `TaiXiuRequest.java`** — `git diff` on both must be empty at the end of the session.

2. **`RikStockCommit`** at
   `/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/RikStockCommit.java`
   — `extends ActionRequestMessage implements CmdAwareMessage`, mirroring `RikStockBet`'s
   layout: constructor `(int cmd, String zoneName, String pluginName, long sessionId)`, static
   `CommitData extends Body` holding **one** field:
   ```java
   @Getter(AccessLevel.NONE)
   @JsonProperty("sId")
   private final long sId;
   ```
   Body serializes to exactly `{"cmd":<offset+3022>,"sId":<sid>}`. Javadoc carries AD-29 (the
   legacy bot's frame, verbatim, and why per-bet) and AD-30 (the fold: `getSId()` → `sid`, a
   key that is *also* the bet frame's session key, so a wrong frame reads as a plausible one).
   Do **not** add a class-level `@Getter`/`@Setter` on `CommitData` — with a single field there
   is nothing for them to do and the field-level `NONE` would be the only thing standing
   between the annotation and Amendment B1's row 2.

3. **`RikStockRequest.commit(long sid)`** in
   `/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/RikStockRequest.java`
   — `@Override`, returns `Optional.of(new RikStockCommit(cmdPrefix + 3022, zoneName,
   pluginName, sid))`. Javadoc: reached only through the AD-21 allowlist; the browser sends one
   per round, the legacy bot one per bet, we follow the legacy bot (AD-29). Update the class
   javadoc's "Identical to `Request` except for the one frame that differs" — it is now two
   frames.

4. **`BettingMiniGameBot`**
   (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/domain/bot/core/BettingMiniGameBot.java`),
   in this order:
   - **Field** beside `pendingDecision` (`:133`): `private final
     AtomicReference<Optional<ActionRequestMessage>> pendingCommit = new
     AtomicReference<>(Optional.empty());` with an AD-32 comment.
   - **`bet()` supplier** (`:769-812`): immediately before `return request.bet(amount, optionId,
     currentSid);` add `pendingCommit.set(request.commit(currentSid));`. That is the whole
     change to the supplier. For every non-stock request this parks `Optional.empty()`.
   - **New `Consumer<SentMessageContext> afterBetSent(ObjectMapper mapper)`**, package-private
     (a test seam, like `setRandom`), javadoc'd with AD-31/AD-32:
     ```java
     return ctx -> {
         Optional<ActionRequestMessage> commit = pendingCommit.getAndSet(Optional.empty());
         if (commit.isEmpty()) return;
         try {
             String frame = commit.get().serialize(mapper);
             log.trace("Bot {}: sending commit sid={}", getUserName(), sidStore.get());
             client.send(frame);
         } catch (RuntimeException e) {
             // An escaping exception cancels the fixed-rate bet task for the life of the
             // connection (ScheduledExecutorService semantics) — never let one out.
             log.warn("Bot {}: commit frame not sent: {}", getUserName(), e.toString());
         }
     };
     ```
     `client.send` swallows its own exceptions (`VingameWebSocketClient.java:587-610`), so the
     `catch` guards only `serialize`. `client` is `Bot`'s protected field; do not add an
     accessor.
   - **`botBehaviorScenario()`** (`:962-967`): add `.onSent(mdcConsumer(afterBetSent(mapper)))`
     to the existing `buildMessage()` chain, where `mapper` is the local built at `:924`. No
     new stage. Correct the comment at `:933-934`: each `sendAsync` stage owns its own
     single-thread scheduler (`ws-send-async-*`); this pipeline has exactly one such stage and
     its condition, supplier **and `onSent`** run on that one thread, in that order.
   - **`beforeReconnect()`** (`:614-632`): `pendingCommit.set(Optional.empty());` beside the
     `pendingDecision` clear. **No change to `onEndGame`** (AD-32 says why).
   - **`onStart()`** (`:981-986`): add `offset + COMMIT_CODE` to `cmdList`, with `private
     static final int COMMIT_CODE = 3022;` declared near the top of the class and commented
     *"print-filter only — AD-33; nothing deserializes this CODE (AD-7)"*. `TaiXiuGameBot` does
     not override `onStart`, so it inherits a harmless `0 + 3022` entry; say so in the comment.

5. **`RikGameMessageTypes` javadoc** — in the outbound section that describes `RikStockRequest`,
   add one paragraph: the stock request also emits the per-bet `13022` commit (AD-27 amended,
   AD-29); `taixiuMd5Plugin` does not, and settles without it. No code change in this class.

6. **Tests — bot-messages**
   (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/test/java/com/vingame/bot/domain/bot/message/request/`):
   - **`RikStockCommitTest`** (new). Serialize the body with a bare `new ObjectMapper()`:
     key set `containsExactlyInAnyOrder("cmd", "sId")`; `cmd == 13022` for offset 10000 and
     `== 17022` for 17000 (the derivation, not the constant); `sId` equals the sid passed;
     `assertThat(json).contains("\"sId\"")` **and** `.doesNotContain("\"sid\"")` — the AD-30
     regression, named in a comment as the third `<lower><UPPER>` key and pointing at B1's
     table. Serialize the whole message once and assert element 0 is `"6"`, element 1 the zone,
     element 2 the plugin (the transposition trap `BettingMiniGameBotRikRequestDispatchTest.
     stockEnvelopeCarriesZoneThenPlugin` exists for). A final test asserts the body carries **no
     `aid`, no `eid`, no `v`** — the commit is not a bet with a different cmd.
   - **`RikStockRequestTest`** — add `commit(sid)` is present, is a `RikStockCommit`, carries
     `cmd == offset + 3022`, zone and plugin; and that `bet(…, sid)` and `commit(sid)` for the
     same `sid` serialize the session under **different keys** (`sid` vs `sId`) with the **same
     value** — the one assertion that ties the pair together.
   - **`RequestTest`** — new test `commitIsAbsentOnTheSharedRequest`: `new Request("BauCua",
     "MiniGame", 2000).commit(123L)` is `Optional.empty()`. Keep
     `bettingMiniBetHasNoAutoBetFlag` (`:130` ff.) untouched; it still pins `Bet`.
   - **`TaiXiuRequestTest`** — the same for both constructor shapes (`emitAutoBetFlag` true
     and false): `commit(sid)` is empty.
   - **`GameRequestFactoryCapabilityTest`** — no edit expected. If it needs one, a bean or a
     capability moved, and the design drifted.

7. **Tests — bot-engine**
   (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/test/java/com/vingame/bot/domain/bot/core/`),
   one new class **`BettingMiniGameBotCommitDispatchTest`**, built the way
   `BettingMiniGameBotRikRequestDispatchTest.requestOf` builds a bot (per-game-resolved provider
   via `forGame`, then `initializeSubclass`) plus `seedClient(bot,
   mock(VingameWebSocketClient.class))` from `BettingMiniGameBotStartGameMd5GuardTest:121`, and
   driving the private seams the way `TaiXiuGameBotStreamTest` does (`onSubscribe` → `onStartGame`
   with a sid → `setRemainingTime` → `betCondition` → `bet` supplier). Four tests:
   - **stock → bet then commit, same sid, in that order — on the real `SendAsync` path.**
     Build a one-stage pipeline with the bot's real supplier and callback, so the ordering
     claim is proven against the library rather than against our own reading of it:
     ```java
     Scenario s = pipeline(ctx).sendAsync(buildMessage()
             .messageSupplier(betSupplier).mode(ONCE).onSent(bot.afterBetSent(mapper)).build()).compile();
     s.process(new RawMessage(MessageType.RECEIVED, "[5,{\"cmd\":13005}]")); // any message reaches the stage
     InOrder inOrder = inOrder(client);
     inOrder.verify(client, timeout(2_000)).send(argThat(f -> f.contains("\"cmd\":13002") && f.contains("\"sid\":" + SID)));
     inOrder.verify(client, timeout(2_000)).send(argThat(f -> f.contains("\"cmd\":13022") && f.contains("\"sId\":" + SID)));
     verify(client, times(2)).send(anyString());
     ```
     where `ctx` is `PipelineContext.buildContext().client(client).objectMapper(mapper)…build()`
     and `betSupplier` is the bot's `bet()` obtained by reflection (the house pattern —
     `BettingMiniGameBotPendingDecisionRaceTest:234`). The `betCondition` must have parked a
     decision first. Shut the scenario down in `@AfterEach`.
   - **txmd5 → bet only.** Same drive on a `taixiuMd5Plugin` bot at offset 4000: exactly one
     `send`, containing `"cmd":7002`; `afterBetSent` invoked directly with
     `new SentMessageContext(0, null)` afterwards produces **no further** `send`.
   - **a non-114 provider → bet only.** `BomGameMessageTypes`, `BauCua`, offset 2000: one
     `send`, `"cmd":5002`, and the parked `pendingCommit` (read by reflection) is
     `Optional.empty()` after the supplier ran.
   - **the callback never throws.** A `RikStockCommit` whose `serialize` throws (spy or a
     mapper that fails) → `afterBetSent` returns normally and `client.send` is never called.
   Also **widen `BettingMiniGameBotRikRequestDispatchTest.stockBotBuildsTheStockRequest`** by
   one assertion: `request.commit(3793247L)` is present and its body key set is exactly
   `{cmd, sId}` — so the seam test and the request test cannot drift apart.

8. **Mutation guidance — run these by hand once, then delete the mutation.** (a) Remove
   `@JsonProperty("sId")` from `CommitData`: `RikStockCommitTest`'s key-set test **must go red**
   (the key becomes `sid`). If it stays green, the test is asserting a round-trip and must be
   rewritten. (b) Remove the `STOCK_PLUGIN` row from `RikGameMessageTypes.requestFor`: the
   stock bot-level test goes red (no commit) — expected; **also** confirm no test goes red on
   the txmd5 side, because that is the direction the allowlist protects. (c) Comment out the
   `.onSent(…)` line: the ordering test must go red with exactly one `send`. Each of the three
   pins a different layer and none of them may be satisfied by another.

9. `mvn clean install` green. **No edit to `MessageTypesCoverageTest`, `MessageTypesRegistryTest`,
   `ApplicationContextLoadsTest`, `MessageTypesRegistryStartupLogTest`, `PerBotInfoLogGuardTest`
   or `Log4j2TwinConfigTest`** — no bean, no product, no INFO line and no logging config
   changes. The boot line still reads `BETTING_MINI 6 products [097, 098, 114, 116, 118, 119]`.

**Phase 3b — `13012`, gated on V-17.** Not to be started unless V-17 reads zero after this
phase has been on the box for the V-17 window. Fully specified in AD-34; its steps are:
`GameRequest.enterGame()` default empty (+ pins on `Request`/`TaiXiuRequest`),
`RikStockRequest.enterGame()` → bare `{"cmd": offset+3012}` (a `RikStockEnterGame` body with
no fields beyond `cmd` — nothing to fold, but assert the key set is exactly `{cmd}` anyway),
the conditional `.send(…)` stage after the subscribe `onMessage`, and a bot-level test that a
stock bot's scenario sends `13012` once after the subscribe response and a txmd5 bot's never
does. It keeps `13022` in place. Its verification is V-16 … V-20 re-run unchanged; a still-zero
V-17 after 3b closes the frame hypotheses (AD-34, last paragraph).

## Implementation Notes / Concerns

- **Do not index `bs`/`obs` by position.** The subscribe frame arrives as `[{eid:1},
  {eid:0}]`. Map by `eid`; `CrowdOption` is keyed on it anyway.
- **`b` absent on `obs` is `0`, and `0` is also a legitimate stake.** Nothing downstream
  distinguishes them, which is fine because `ownBet` is unused by the v1 coordinator, but do
  not later build a "did this bot bet?" check on it.
- **`bc` is players, not bets** (three bets, `bc:1`). Set `crowdCountSemantic = PLAYERS` on
  the `Game` record. It is observability-only, so this cannot corrupt steering.
- **Jackson would happily coerce `"160"` into an `int`.** That is exactly why AD-7 records
  the `String` requirement now rather than when someone models 13018.
- **The aggregated-session sample line prints an object identity, not JSON.** The Win79
  release captured `…Win79StartGameMessage@4e705d6e` in the log
  (`docs/reviews/WIN79_119_ENABLEMENT/release.md:505`) because Lombok `@Getter/@Setter` adds
  no `toString`. So modelling extra fields buys nothing for logging — do not model `bPl` or
  `ps` "so we can see them in the log". They will not be in the log.
- **`Game.gameId` is informational here** (`SlotMachineBot` is its only reader). Set it to
  13000 for the operator's sake; `offset = 10000` is the field that matters.
- **The registry's boot line is the releaser's smoke string** and it is sorted
  (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/MessageTypesRegistry.java:119-123`), so `114` lands between `098` and `116`: expect
  `BETTING_MINI 6 products [097, 098, 114, 116, 118, 119]`.
- **A duplicate `products = "114"` fails context refresh**, not just a test
  (`/Users/gleb/IdeaProjects/Bot/bot-messages/src/main/java/com/vingame/bot/domain/bot/message/MessageTypesRegistry.java:254-261`) — relevant only if someone copies the provider.
- **`taixiuMd5Plugin` on 114 is a `BETTING_MINI` game, NOT `GameType.TAI_XIU`.** The brand
  runs a Tài/Xỉu-themed game under *both* game types and they share nothing: this one is
  CODE+offset (`7000/7005/7006` at offset 4000) and resolves `RikGameMessageTypes`, while
  `taixiuJackpotPlugin` is the fixed-CMD product (`1105/1102/1104/1100`) served by
  `JackpotTaiXiuMessageTypes`. Creating this game with `gameType: TAI_XIU` resolves the
  wrong provider, subscribes on the wrong CMD and never sees a round. The name is the trap;
  the plugin name and the CMDs are the tell.
- **`wm` is a gross return including the stake** (AD-15) on both 114 games and in
  `TaiXiuEndGameMessage` — so `bot_winnings_total / bot_bet_amount_total` on a 114 group
  reads ~0.98, not ~-0.02. Do not "fix" the ratio by netting the stake off `winningsFor`;
  `TaiXiuEndGameMessage:154-156` already carries that warning for the same reason.
- **The two 114 games take their 2% on different bases** — gross return on stock, profit on
  txmd5. There is no single product-wide multiplier and writing one down would be wrong by
  1% on one of them.
- **`taixiuMd5Plugin` emits no `3002` at all**, so `RikUpdateBetMessage` never gets built for
  it and the coordinator's crowd view on that game is Subscribe + EndGame only. Nothing to
  fix; do not go looking for a missing handler.
- **A round the bot did not bet omits `wm` and `b` entirely** rather than sending zeros —
  which is why the `txmd5-endGame-noBet.json` fixture exists. Jackson's `0` plus
  `onEndGame`'s `w > 0` guard makes that a no-op, but it is the case that would silently
  corrupt `bot_bet_amount_total` if someone later reached for `obs`/`v` instead.
- **Do not delete or "tidy" any existing product's classes.** Per `CLAUDE.md`, a game can be
  enabled on a brand at any moment and staging absence proves nothing.
**Phase 2 specifically:**

- **`@JsonProperty("iAc")` is not optional and its absence is silent** (AD-23). Lombok's
  `isIAc()` serializes as `iac` under Jackson's default naming — verified against the project's
  **2.15.2** — so the frame ships looking right and the server ignores it, reproducing the exact
  bug this phase fixes. **It is also not sufficient on its own**: `@JsonProperty("iAc")` *plus*
  the generated getter emits **both** keys, and the `AutoBet.AutoBetData.getIsMini()` trick does
  not transfer. The shipped shape is `@JsonProperty("iAc")` + `@Getter(AccessLevel.NONE)` —
  Amendment B1.
- **Two dispatch points now key on `pluginName` inside `RikGameMessageTypes`** — `forGame`
  (which routes ziczac away) and `requestFor` (which routes stock to its own body). They exist
  because stock and txmd5 **share one provider by design** (AD-11) and must not share a bet
  body. Change one without the other and the routing matrix test names both.
- **Use an allowlist in `requestFor`, never "everything except txmd5"** (AD-21). A denylist
  hands the stock body to the next un-captured 114 game someone enables, which OI-2 forbids.
- **`taixiuMd5Plugin` is the regression that matters.** It settles today through the shared
  `Bet`; if V-14 shows its counters flat after this deploy, the allowlist is inverted. Check
  that before anything else.
- **Do not expect `bot_bets_placed_total` to match the send count.** `betCountFor` counts
  positions with a stake, not clicks (`RikEndGameMessage.java:258-266`) — 5 sends across 2
  options is 1–2 counted bets. `bot_bet_amount_total` is the exact one.
- **`mB = 500 000 000` is stock's per-bet max**, and the denomination ladder (OI-8) is the
  tighter constraint in practice. Keep the verification group at `min = max = 1000`.
- **`RikStockRequest` cannot subclass `Request`.** `Request.bet` narrows the return type to the
  concrete `Bet`, and `RequestTest.overrideReturnsConcreteBet` pins that covariance, so an
  override returning `RikStockBet` would not compile without `RikStockBet extends Bet` — which
  would drag `b` back into the body.
- **Nothing in Phase 2 adds a Spring bean**, so the registry boot line, the coverage tests and
  `ApplicationContextLoadsTest` must all be unchanged. If one of them needs an edit, the design
  drifted.

**Phase 3 specifically:**

- **`@JsonProperty("sId")` + `@Getter(AccessLevel.NONE)` — both, on the field, no getter
  anywhere** (AD-30). `sId` folds to `sid`, which is *also* the bet frame's session key, so a
  wrong commit looks like a plausible frame on the wire and the server ignores it silently. The
  key-set test plus `doesNotContain("\"sid\"")` is the only thing that catches it; a round-trip
  test would pass.
- **Do not add a second `sendAsync` stage** (AD-31). Each `SendAsync` owns its own
  single-thread scheduler (`SendAsync.java`, `VirtualThreads.newScheduler`), so two stages are
  two threads and the bet/commit order is a race. `onSent` on the existing bet stage runs after
  `client.send(bet)` returns, on the same thread. The `:933-934` comment that says "the scenario
  thread" is per-stage and must be corrected in the same edit or the next reader repeats this.
- **`afterBetSent` must not throw** (AD-32). An exception out of a `scheduleAtFixedRate` task
  cancels the task; here that task *is* the bot's bet loop. `bet()`'s existing
  `IllegalStateException` is the one deliberate kill switch in that runnable; do not add a
  second by accident.
- **No `onEndGame` clear of `pendingCommit`.** It is written and consumed inside one runnable
  on one thread; the only external clear is `beforeReconnect`, for symmetry with
  `pendingDecision`. An EndGame clear would imply the value can outlive the runnable, which it
  cannot.
- **`OutputPrinter` is a cmd filter, not a firehose** (AD-33). Without `offset + 3022` in
  `onStart`'s `cmdList`, V-19 shows no commit in either direction even at TRACE — the same
  window `release.md` ran would have read "commit not sent". Add the integer, and nothing else
  about `OutputPrinter`.
- **The commit logs at TRACE and nothing higher.** Its rate is per bet; `PerBotInfoLogGuardTest`
  will fail a `log.info` in this class regardless, but a `log.debug` would pass the guard and
  still be wrong under the tiering rule.
- **`Request.java` and `TaiXiuRequest.java` end the session with an empty `git diff`.** The seam
  is a `default` method; the pins are new tests in `RequestTest`/`TaiXiuRequestTest`, not
  edits to the classes under test.
- **13012 and 13022 never ship in the same deploy.** If both went out and V-17 turned positive,
  nobody would know which one did it, and the browser client's two-frame habit would become a
  permanent superstition in the codebase (AD-27 amended, AD-34).
- **The `13022` response shape is unknown.** No client we have read handles it; V-19 is the
  first time anyone will look. Model nothing inbound for it — record the verbatim frame in
  `release.md` and decide then (AD-7's inbound disposition is unchanged).
- **A legacy stock bot exists on prod and is not ours.** Anyone reading prod `stockPlugin`
  behaviour should know `prd-rik-coins-v2.js` on `Staging-098:/home/sgame/bot/` has been in an
  `auth → 13000 → close 1006` loop since 11:29 +07 on 2026-09-17 (OI-13). Its traffic is not
  evidence about our frames.

- **This is a working-tree feature branch; nothing is committed or pushed.** The user
  controls what lands.

## Open Items

- **OI-1 — CLOSED 2026-09-15.** "Is `mbs` mine or the room's?" was answered by the backend
  field constants (`MAIN_BET_ARRAY = "mbs"`, `OTHER_BET_ARRAY = "obs"`) without needing the
  two-account capture this item specified. It is **mine**; `HasBotWinnings` and
  `HasBetTotals` ship in Phase 1b. See Amendment A1 and AD-5 (corrected).
- **OI-2 — no other 114 mini game may be enabled on this evidence.** Offsets 14000–18000
  were live during the capture (§4b) and share the CODE layout, but not one payload was
  captured. Enabling one means: capture → re-run `infer_schema.py` → confirm the four
  frames against `RikSubscribeMessage`/`RikEndGameMessage` → only then create the `Game`.
  If the shapes differ materially, the correct move is **not** a second provider (impossible
  under one product key) but widening the RIK classes, which is what AD-4's `bs` fallback
  starts.
- **OI-3 — the "emit both `b` and `v`" hedge.** Still not planned, and now explicitly **not**
  the next step if Phase 2's V-12 reads zero: Phase 3 (send `13022`, AD-27 amended) is, because
  it tests a difference *both* other clients actually have. Emitting both keys sends a frame no real
  client sends, and if it then worked we would not know which key was read.
- **OI-4 — `v` is still not separated from `b`.** This account was the only bettor in
  *both* captures, so `v == b` in every sample and "room total including self" cannot be
  told from "others only". `crowdBets()` reads `v` as the room total including self, which
  is the cross-product convention and what the coordinator expects; one round with a second
  player in it settles it. Low stakes: a wrong reading would make crowd-aware steering
  double-count our own stake, and crowd-awareness is opt-in and off.
- **OI-5 — no Down (`eid 1`) bet was ever captured on stock.** The sign inversion in AD-15
  (`(100 − d1)%` for the short side) is the user's description plus an exact arithmetic fit
  on three Up bets, not observed traffic. Nothing in the code depends on it — the message
  layer never computes a payout — so this is a documentation caveat, not a risk.
- **OI-6 — the jackpot markers on txmd5** (`HasJackpotPool` from `tJpV`, `HasJackpot` from
  whatever carries a per-user payout). Blocked on one capture showing a **non-zero** meter;
  `tJpV` was 0 in every sample and there is no `jpV` field at all. Two lines once the
  evidence exists (AD-19) — and `tJpV`'s meaning is inverted between product families, so
  guessing it is the one thing not to do.
- **OI-7 — `rs`, the provably-fair reveal string**, is unmodelled. It carries the dice as
  `{5-4-4}`, duplicating `d1`/`d2`/`d3`, so there is nothing in it the engine needs; it
  would only matter if someone wanted bot-side md5 verification, which nobody has asked
  for.
- **OI-8 — `stockPlugin` accepts only a fixed denomination ladder, and the data model has no
  way to express it. Recorded here; NOT solved here.** The legal individual bet values are
  **1k, 5k, 10k, 50k, 100k, 500k, 1M, 5M, 10M, 50M** — a 1-5-10 ladder per decade.
  `BotBehaviorConfig`'s `minBet` / `maxBet` / `betIncrement`
  (`/Users/gleb/IdeaProjects/Bot/bot-api/src/main/java/com/vingame/bot/config/bot/BotBehaviorConfig.java:26`)
  generate an **arithmetic grid** — `RandomBehaviorStrategy:107-112` draws uniformly over
  `min + k·betIncrement`, and every Martingale variant aligns to the same grid — so an
  arithmetic progression can hit **at most two** values of a geometric ladder
  (`min=5000, max=10000, inc=5000` → `{5k, 10k}`). There is no configuration of the three
  fields that lets a group bet across the ladder. The workaround is in place and is why V-11's
  baseline is readable: the coins group is pinned at `min = max = 1000`. The real fix is the
  already-open backlog item **"Configurable betting values per game — define allowed bet
  amounts (e.g. `[100, 500, 1000]`) instead of arbitrary values to pass server validation"**
  under *Priority (Internal Testing Feedback)* in `CLAUDE.md`; this finding is the first
  concrete, measured instance of it and belongs on that item, not in this plan. Note that a
  rejected amount is **silent** — it looks exactly like the failure this plan fixes — so
  whoever picks the backlog item should make rejection observable, not only preventable.
- **OI-9 — `ZicZacBet` is deferred to `RIK_114_ZICZAC.md` Phase 2** (AD-24). The seam it needs
  ships here, so that phase loses its steps 1 and 5 and becomes `ZicZacBet` +
  `ZicZacRequest` + `RikZicZacGameMessageTypes implements GameRequestFactory` + tests. It is
  **capture-proven / staging-unverified** and must be labelled so: ziczac's round feed died
  after 2 rounds during the 2026-09-16 probe, so nothing on the box can currently exercise it.
  Unblocked by a ziczac round feed that survives more than two rounds; verified by the
  V-11..V-14 shape against a ziczac group (`bot_bets_placed_total > 0`, and
  `bot_bet_amount_total ≈ sends × per-ball stake` at `c = 1`).
- **OI-10 — the `grep '"cmd":<CODE>"' detail.log` verification step is broken in both RIK plans
  and is not fixed in `RIK_114_ZICZAC.md`.** Outbound frames are `OutputPrinter` **TRACE** and
  inbound frames are ws-parser **DEBUG** behind `WSPARSER_LOG_LEVEL`, which defaults to `INFO`
  (see the note on V-6). `RIK_114_ZICZAC.md` V-6 prescribes exactly that grep for
  `"cmd":12002` and will produce nothing; it needs the same metrics-and-`confirmed staked`
  treatment V-12/V-13 use. Flagged rather than edited, because that document is not this
  phase's to change.
- **OI-11 — ziczac is out of this plan's scope and is now known to be dead server-side.** Its
  round engine is frozen on `sid 1995721` with `rmT: -77029518` (−21.4 h) since ~14:15Z on
  2026-09-16; the probe group `88d46075…` is stopped; the user will have the game restarted.
  Record honestly what that does **not** settle: the ziczac round feed died at ~13:57Z, about
  **40 rounds before** the freeze, so the `12012` question for ziczac (does it need its
  post-subscribe frame to receive rounds at all?) is **still open** — it simply cannot be tested
  until the game is back. `RIK_114_ZICZAC.md` owns it; nothing here depends on it (AD-24).
- **OI-12 — the `EXISTED` display-name retry bug. Cross-referenced, not planned here.**
  `ApiGatewayClient.setDisplayName`
  (`/Users/gleb/IdeaProjects/Bot/bot-engine/src/main/java/com/vingame/bot/infrastructure/client/ApiGatewayClient.java:362-372`)
  returns `false` only for `INVALID`; every other non-`OK` status — and this gateway answers
  **`EXISTED`** for a taken name — is thrown as a `RuntimeException`, which escapes
  `setDisplayNameWithRetry`'s loop (`:388-410`) on the first iteration. The 5-attempt retry has
  therefore **never run a second attempt** on RIK, and ~50 % of new bots are nameless (one per
  probe group in `display-name-check.md`: `rikcoin1`, `rikzzc1`, `riktxm2`). Eliminated as a
  cause of the stock zero by experiment (AD-27 amended, item 3); still a real bug — a nameless
  bot is visibly a bot. Fix is one `|| "EXISTED".equals(status)` plus a test with the real
  response body; it belongs to its own small change, not to this plan.
- **OI-13 — the live prod legacy stock bot is looping.** `prd-rik-coins-v2.js` on prod has been
  in an `auth → {cmd:13000} → close 1006` loop since 11:29 +07 on 2026-09-17. Not ours, not
  this plan's — but anyone reading prod `stockPlugin` traffic or server-side stock metrics for
  evidence about *our* frames should know that the only other stock client is currently
  producing no rounds either.
- **OI-14 — the `13022` response, if any, has never been observed.** The browser capture is
  sampled and exported no inbound `13022`; the legacy bot has no handler. V-19 is the first
  look. Nothing inbound is modelled for it (AD-7); the frame is recorded verbatim in
  `release.md` and any once-per-round decision (AD-29's fallback) is made on that evidence.
- **Out of scope:** 13007/13012/13018/13022 *inbound* modelling (AD-7 — unchanged; Phase 3 *sends* 13022 and models no response to it, AD-33/OI-14); `13012` in this phase (Phase 3b, AD-34); any change to
  Bom/B52/Tip/Nohu/Win79 or to the Tai Xiu layer; any `Game`-schema change; the ambiguous
  `d1`/`bPl` semantics beyond AD-15 (which of `eid` 0/1 is "up" is not needed to place a
  bet, and the message layer never computes a payout); bot-side md5/`rs` verification
  (OI-7).
- **Depends on nothing external.** The env (`394301f4-…`) and the RIK auth path already
  exist and are exercised by the live Tai Xiu groups.

## Verification

Run on staging (Bot-1) after deploy. This feature **has** on-server verification beyond the
universal smoke test. `BASE=http://localhost:8080`, `ENV=394301f4-6daf-4c55-a073-502a81c00731`.

**Which steps apply to which phase.** **V-1 … V-10 are Phase 1 / 1b and have already been run**
— they are kept as the record of how the provider was proven, and **V-6 is the one that failed**
(Amendment A3). A Releaser deploying **Phase 2** runs **V-1** (the app is up), then
**V-11 … V-15** in the *Phase 2 verification* subsection below, and nothing else. V-11 must be
taken **before** `compose down`. A Releaser deploying **Phase 3** runs **V-1**, then
**V-16 … V-20** in the *Phase 3 verification* subsection, and nothing else; V-16 must be taken
**before** `compose down`. Phase 3b, if it ever ships, re-runs V-16 … V-20 unchanged.

**V-1 — the app is up.**
```
curl -s -o /dev/null -w '%{http_code}\n' $BASE/actuator/health
```
Expect **200**, and `curl -s $BASE/actuator/health | jq -r .status` → `UP`.

**V-2 — the registry picked the provider up (the decisive build/deploy check).**
```
docker logs bot-java-bot-manager-1 2>&1 | grep "MessageTypesRegistry initialized"
```
Expect **exactly one** line, containing
`BETTING_MINI 6 products [097, 098, 114, 116, 118, 119]` and
`TAI_XIU 3 products [114, 116, 119]` unchanged. The pre-deploy baseline is the same line
with `BETTING_MINI 5 products [097, 098, 116, 118, 119]` — capture it before `compose down`
so this is a before/after and not a tautology.

**V-3 — a 114 stock `Game` can be created.**
```
curl -s -X POST "$BASE/api/v1/game/G3/P_114/$ENV" -H 'Content-Type: application/json' -d '{
  "name":"RIK Stock (stockPlugin)","gameType":"BETTING_MINI","pluginName":"stockPlugin",
  "offset":10000,"gameId":13000,"md5":false,
  "optionAffinities":{"0":1,"1":1},"crowdCountSemantic":"PLAYERS"}'
```
Expect HTTP **200/201** with `offset:10000`, `pluginName:"stockPlugin"`, `md5:false` and a
two-entry `optionAffinities`. Record the returned `id` as `$GAME`.

**V-4 — a 2-bot group on that game starts and authenticates.** Create a 2-bot group bound
to `$GAME` and `$ENV` (same body shape as the existing P_114 groups), then:
```
curl -s -X POST $BASE/api/v1/bot-group/$GROUP/start
sleep 60
curl -s $BASE/api/v1/bot-group/$GROUP/health | jq '{status,bots:[.bots[].status]}'
```
Expect HTTP **200** on start and, within 60 s, **both bots `CONNECTION_AUTHENTICATED`** and
group status `ACTIVE`. This proves `MessageTypesRegistry.bettingMini("114")` resolved
(pre-change it threw before authentication) **and** that subscribe cmd 13000 was answered —
`markConnectionAuthenticated()` is called from `onSubscribe`.

**V-5 — StartGame and EndGame actually deserialize into the RIK classes.** After ~3 rounds
(≈ 100 s):
```
curl -s "$BASE/actuator/metrics/bot_messages_total?tag=botGroupId:$GROUP&tag=cmd:startGame" | jq '.measurements[0].value'
curl -s "$BASE/actuator/metrics/bot_messages_total?tag=botGroupId:$GROUP&tag=cmd:endGame"   | jq '.measurements[0].value'
```
Expect **both > 0** (target ≥ 2 each after 3 round-lengths). These counters increment inside
`onStartGame`/`onEndGame`, which only fire when the polymorphic subtype resolved — an
unregistered CMD would leave them at 0 with the WS connection still healthy. Also confirm no
new errors:
```
docker logs bot-java-bot-manager-1 2>&1 | grep -E "ERROR|Exception" | tail -20
```
Expect **no line mentioning 114, RIK, stockPlugin, or `InvalidTypeIdException`**.

**V-6 — ANSWERED 2026-09-16: FAILED. Kept as the historical record; do not re-run it.** The
gate question was "does the server accept our `b`?" and the answer is no — 156 endGame rounds
on the coins group with `bot_bets_placed_total` flat at `0.0` while `taixiuMd5Plugin` confirmed
32 bets through the identical `Request`/`Bet` class on the same box in the same window. The
evidence, the eliminated denomination confounder and the two frames side by side are in
**Amendment A3**. Phase 2 ships; its verification is **V-11..V-14** below.

**Note the shell step this section used to prescribe does not work, and neither does the
identical one in `RIK_114_ZICZAC.md` V-6.**
```
grep -h '"cmd":13002' /home/sgame/bot-java/logs/detail/detail.log   # produces nothing
```
Two independent reasons, both structural: **outbound** frames are printed only by our
`OutputPrinter` at **TRACE** (`OutputPrinter.java:65` — `log.trace("User {}: {}", …)`) and the
box runs `BOT_LOG_LEVEL=DEBUG`; **inbound** frames are printed by ws-parser's
`VingameWebSocketClient` `log.debug("Client {}: Received text message: {}")`, which sits under
`com.vingame.websocketparser`, whose level is `WSPARSER_LOG_LEVEL` and **defaults to `INFO`**.
Raising either is not a per-group action — scoped per-group DEBUG explicitly cannot reach TRACE
(`CLAUDE.md`), and a global `/actuator/loggers` flip to TRACE is a ~5 GB/hour, fleet-wide
decision. **Use the metrics and the aggregated DEBUG session line instead** (V-12/V-13); the
exact serialized frame is pinned in the build by `RikStockBetTest`, which is where that
assertion belongs.

**V-7 — no *un-captured* 114 game was enabled by this deploy (AD-11 / OI-2).**
```
curl -s "$BASE/api/v1/game/G3/P_114/$ENV" | jq '[.[] | select(.gameType=="BETTING_MINI") | {name,pluginName,offset}]'
```
Expect **only the games this plan sanctions**: the `stockPlugin` record from V-3, plus the
`taixiuMd5Plugin` record if V-10 was run. Anything else — in particular anything at offset
14000/15000/16000/17000/18000 — means an un-captured 114 mini game was pointed at this
provider, which OI-2 forbids until it has been captured.

**V-8 — no regression for the other five products.** The registry line in V-2 already pins
the set; additionally spot-check one running non-114 group:
```
curl -s $BASE/api/v1/bot-group/$OTHER_GROUP/health | jq '{status, connected:[.bots[].status]|map(select(.=="CONNECTION_AUTHENTICATED"))|length}'
```
Expect unchanged status and connected count versus the pre-deploy baseline. And because
bot-manager and the observability stack share one compose project on Bot-1, re-verify the
stack came back: `docker compose ps` → **all containers `Up`**, plus `GET :3000/api/health`
→ **200**, `GET :9090/-/healthy` → **200**, `GET :3100/ready` → **`ready`**.

**V-9 — Phase 1b only: the payout metrics are non-zero and RTP lands near 0.98.** This is
the check the original AD-5 made impossible. After ~10 settled rounds:
```
G="tag=botGroupId:$GROUP"
curl -s "$BASE/actuator/metrics/bot_bet_amount_total?$G" | jq '.measurements[0].value'
curl -s "$BASE/actuator/metrics/bot_bets_placed_total?$G" | jq '.measurements[0].value'
curl -s "$BASE/actuator/metrics/bot_winnings_total?$G"    | jq '.measurements[0].value'
```
Expect **all three > 0** — on `stockPlugin` every bet returns something (AD-15), so winnings
appear from the first settled round rather than on a lucky one. Then the anchor:
`bot_winnings_total / bot_bet_amount_total` should sit **near 0.98** and in any case
**below 1.0** over a sustained window. Three readings and what they mean:
- **exactly 0 winnings with a non-zero stake** ⇒ `winningsFor` is not firing — check that
  the EndGame really deserialized (V-5) before suspecting the server.
- **ratio ≈ 1.98 or ≈ 0.02** ⇒ someone netted or un-netted the stake; `wm` is a gross
  return (AD-15) and `winningsFor` must return it verbatim.
- **ratio sustained above 1.0** ⇒ the RTP-anomaly condition from `CLAUDE.md`'s health
  diagnostics table. Real, and worth escalating rather than celebrating.

**V-10 — optional, recommended: the second captured game, which exercises the md5 path and
the top-level `wm`.** `taixiuMd5Plugin` is captured (§5), so OI-2 permits it.
```
curl -s -X POST "$BASE/api/v1/game/G3/P_114/$ENV" -H 'Content-Type: application/json' -d '{
  "name":"RIK TaiXiu MD5 (taixiuMd5Plugin)","gameType":"BETTING_MINI","pluginName":"taixiuMd5Plugin",
  "offset":4000,"gameId":7000,"md5":true,
  "optionAffinities":{"1":1,"2":1},"crowdCountSemantic":"PLAYERS"}'
```
**`gameType` is `BETTING_MINI`, not `TAI_XIU`** — the 114 brand runs a Tài/Xỉu game under
both game types and picking the wrong one resolves `JackpotTaiXiuMessageTypes` and subscribes
on CMD 1105, which this game never answers. Note `md5:true` (a real hash, unlike stock) and
`optionAffinities` keyed **`1`/`2`**, not `0`/`1`. Start a 2-bot group on it and expect, as
in V-5, `bot_messages_total{cmd:startGame}` and `{cmd:endGame}` **> 0** — which additionally
proves `RikStartGameMd5Message` is what `md5:true` selected — and, as in V-9,
`bot_winnings_total` **> 0** on a won round (this game does have losing rounds, so allow a
few minutes) sourced from the top-level `wm` rather than from `mbs`.

---

### Phase 2 verification — V-11 … V-15

These are the Releaser's steps for the RIK stock bet body. They use the **existing** staging
groups, which were left in place as the baseline — **do not create new ones, and do not change
their bet configuration**, which is what makes the verdict readable.

```
BASE=http://localhost:8080
ENV=394301f4-6daf-4c55-a073-502a81c00731
COINS=cd77131c-7087-4eff-9a62-b50e3e674c91      # stockPlugin, game 8a4e3c49-b8eb-494c-89f2-27c05cc5e586
TXMD5=1134449f-f040-4e95-a1e5-a2a618195cc1      # taixiuMd5Plugin — the control
```

The coins group is pinned at **`minBet = maxBet = 1000`**, the exact denomination the real
client sends, so every send is a legal amount and **any non-zero `bot_bets_placed_total` after
this deploy is unambiguous** (OI-8: the denomination rule is real but was proven not to be the
cause).

**V-11 — pre-deploy baseline. Take it before `compose down`, or V-12 is a tautology.**
```
for G in $COINS $TXMD5; do
  echo -n "$G bets="; curl -s "$BASE/actuator/metrics/bot_bets_placed_total?tag=botGroupId:$G" | jq -r '.measurements[0].value'
  echo -n "$G amount="; curl -s "$BASE/actuator/metrics/bot_bet_amount_total?tag=botGroupId:$G" | jq -r '.measurements[0].value'
  echo -n "$G win="; curl -s "$BASE/actuator/metrics/bot_winnings_total?tag=botGroupId:$G" | jq -r '.measurements[0].value'
done
```
Expect **`$COINS bets=0`** (that is the defect) and **`$TXMD5 bets` > 0**. Record all six
numbers. If `$COINS bets` is already non-zero, **stop** — something changed underneath this
plan and Amendment A3's premise needs re-checking before anything is deployed.

**V-12 — THE GATE: the coins group's server-confirmed bets leave zero.** After the deploy,
start (or confirm running) `$COINS` and wait **≥ 10 settled rounds** — a stock round is
`21 s` betting + `11 s` payout ≈ **32 s**, so ~6 minutes:
```
curl -s $BASE/api/v1/bot-group/$COINS/health | jq '{status, bots:[.bots[].status]}'
sleep 400
curl -s "$BASE/actuator/metrics/bot_bets_placed_total?tag=botGroupId:$COINS" | jq -r '.measurements[0].value'
```
Expect group `ACTIVE`, bots `CONNECTION_AUTHENTICATED`, and
**`bot_bets_placed_total` > 0 and strictly greater than the V-11 baseline of 0**. That single
number is the whole phase's verdict: the counter is incremented from
`HasBetTotals.betCountFor` off the EndGame's own `mbs`
(`BettingMiniGameBot.java:523-527`), so a non-zero value is **the server telling us it
registered our bet** — nothing bot-side can fake it.

**Read the magnitude correctly.** `RikEndGameMessage.betCountFor` counts **positions with a
stake, not clicks** (`RikEndGameMessage.java:258-266`): the coins group sends 5 bets of 1000
per round across 2 options, so expect roughly **1–2 per round**, not 5. A count far below the
send count is **expected and documented**, not a partial failure.

**V-13 — the amount and the payout agree with what we sent.**
```
G="tag=botGroupId:$COINS"
curl -s "$BASE/actuator/metrics/bot_bet_amount_total?$G" | jq -r '.measurements[0].value'
curl -s "$BASE/actuator/metrics/bot_winnings_total?$G"   | jq -r '.measurements[0].value'
```
Expect:
- **`bot_bet_amount_total` > 0 and ≈ 5000 × settled rounds × bots** — this is the *exact*
  metric, unlike the count (AD-17), so a large systematic gap means some sends are still being
  dropped (denomination, cap, or the bet window).
- **`bot_winnings_total` > 0 from the first settled round.** On `stockPlugin` every bet returns
  something (AD-15), so a zero here with a non-zero stake is a real defect, not an unlucky run.
- **`bot_winnings_total / bot_bet_amount_total` near 0.98 and below 1.0** over a sustained
  window (AD-15). ≈1.98 or ≈0.02 means someone netted or re-grossed the stake; sustained above
  1.0 is the `CLAUDE.md` RTP-anomaly condition and is escalated, not celebrated.

Cross-check against the log, which is the reading that made the failure visible in the first
place — the aggregated session line is `log.debug`
(`SessionAggregationService.java:471-473`) and staging runs `BOT_LOG_LEVEL=DEBUG`, so it is in
**track 2**:
```
grep -h "session .* ended" /home/sgame/bot-java/logs/detail/detail.log | grep "$COINS" | tail -5
grep -h "BotMemory.completeRound" /home/sgame/bot-java/logs/detail/detail.log | tail -5
```
Expect `confirmed staked:` to be **non-zero and to track `total staked:`** on the same line.
Before this phase the pair read `total staked: 5000 … confirmed staked: 0`; that divergence
going away is the fix, stated in one line. `BotMemory.completeRound`'s `payout=` should also
stop being uniformly `0`.

**V-14 — the control group and the other five products are untouched (AD-21/AD-25).**
```
curl -s "$BASE/actuator/metrics/bot_bets_placed_total?tag=botGroupId:$TXMD5" | jq -r '.measurements[0].value'
curl -s "$BASE/actuator/metrics/bot_bet_amount_total?tag=botGroupId:$TXMD5" | jq -r '.measurements[0].value'
docker logs bot-java-bot-manager-1 2>&1 | grep "MessageTypesRegistry initialized"
# $OTHER_GROUP = any running group on a product that is not 114 (097/098/116/118/119)
curl -s $BASE/api/v1/bot-group/$OTHER_GROUP/health | jq '{status, connected:[.bots[].status]|map(select(.=="CONNECTION_AUTHENTICATED"))|length}'
```
Expect both `$TXMD5` counters **still climbing** versus the V-11 baseline — this is the check
that AD-21's allowlist did not hand the stock body to the game that shares its provider, and it
is the single most important regression in this phase. Expect the registry line **unchanged**:
`BETTING_MINI 6 products [097, 098, 114, 116, 118, 119]` (no bean was added). Expect one
non-114 group's status and connected count unchanged.

**V-15 — the observability stack came back.** bot-manager and the observability stack share one
compose project on Bot-1: `docker compose ps` → **all containers `Up`**, `GET :3000/api/health`
→ **200**, `GET :9090/-/healthy` → **200**, `GET :3100/ready` → **`ready`**.

**If V-12 still reads zero.** Do not start editing the frame. In order: confirm the build
actually shipped (`RikStockBetTest` is in the test report and the deployed jar is the new one);
confirm the coins group resolved the new request (`V-14`'s registry line plus the routing test
— if `taixiuMd5Plugin` *also* went to zero, the allowlist is inverted); then **Phase 3**, which
is already specified and gated on exactly this reading (AD-27 amended — it sends `13022`, not
`13012`). The envelope (AD-26), per-brand
whitelisting (ruled out by `$TXMD5` on the same host and brand) and the denomination (OI-8,
pinned at a legal 1000) are all already eliminated — re-opening any of them is re-doing work
Amendment A3 finished.


**Cleanup.** Phase 1's V-4 and V-10 groups were throwaways:
`POST /api/v1/bot-group/$GROUP/stop` (which sets `targetStatus=STOPPED`, so dead-group auto-recovery
will never pick them up), then delete the groups and their `Game` records unless the user wants
the games kept.

**`$COINS` and `$TXMD5` are NOT throwaways — leave them alone.** `$COINS`
(`cd77131c-7087-4eff-9a62-b50e3e674c91`) is pinned at `minBet = maxBet = 1000` **as the Phase 2
verification baseline** and `$TXMD5` (`1134449f-f040-4e95-a1e5-a2a618195cc1`) is its control.
Stopping either, deleting either, or changing `$COINS`' bet configuration destroys the only
before/after this phase has. If V-12 passes and the user wants the coins group widened back to a
realistic spread, do it **after** the verdict is recorded — and honour OI-8 when choosing the
values, or the group starts silently discarding 7 bets in 10 again.

---

### Phase 3 verification — V-16 … V-20

The Releaser's steps for the `13022` commit. Same baseline, same two groups, same rule: **use the
existing groups, create nothing, change no bet configuration.**

```
BASE=http://localhost:8080
ENV=394301f4-6daf-4c55-a073-502a81c00731
COINS=cd77131c-7087-4eff-9a62-b50e3e674c91      # stockPlugin, pinned minBet = maxBet = 1000
TXMD5=1134449f-f040-4e95-a1e5-a2a618195cc1      # taixiuMd5Plugin — the 1:1 control
LOGS=/home/sgame/bot-java/logs
```

The coins group has read `bot_bets_placed_total = 0` across ~6,500 rounds and every experiment
since Amendment A3 — Phase 2's body, the display-name fix, the denomination pin. All actuator
counters are **JVM-lifetime**; judge every step by **delta**, never by absolute value.

**V-16 — pre-deploy baseline. Take it before `compose down`.**
```
for G in $COINS $TXMD5; do
  for M in bot_bets_placed_total bot_bet_amount_total bot_winnings_total; do
    echo -n "$G $M="; curl -s "$BASE/actuator/metrics/$M?tag=botGroupId:$G" | jq -r '.measurements[0].value'
  done
  echo -n "$G endGame="; curl -s "$BASE/actuator/metrics/bot_messages_total?tag=botGroupId:$G&tag=cmd:endGame" | jq -r '.measurements[0].value'
done
```
Expect **`$COINS bot_bets_placed_total=0`** and **`$TXMD5 bot_bets_placed_total > 0`**. Record all
eight numbers. If `$COINS` is already non-zero, **stop** and re-check Amendment A3's premise
before deploying anything.

**V-17 — THE GATE: the coins group's server-confirmed bets leave zero.** After the deploy, confirm
`$COINS` is running, then wait **≥ 10 settled rounds** (a stock round is ≈ 32 s, so ~6 min):
```
curl -s $BASE/api/v1/bot-group/$COINS/health | jq '{status, bots:[.bots[].status]}'
sleep 400
curl -s "$BASE/actuator/metrics/bot_bets_placed_total?tag=botGroupId:$COINS" | jq -r '.measurements[0].value'
curl -s "$BASE/actuator/metrics/bot_messages_total?tag=botGroupId:$COINS&tag=cmd:endGame" | jq -r '.measurements[0].value'
```
Expect group `ACTIVE`, both bots `CONNECTION_AUTHENTICATED`, `endGame` **≥ V-16 + 10** (the
window really covered rounds), and **`bot_bets_placed_total > 0`** — strictly above the V-16
value of 0. That number is the phase's verdict: it is incremented from `HasBetTotals.betCountFor`
off the EndGame's own `mbs` (`BettingMiniGameBot.java:523-527`, `RikEndGameMessage.java:258-266`),
so only the server can move it. Read the magnitude as in V-12: positions-with-stake, **1–2 per
round**, not 5.

**V-18 — the amount, the payout and the log agree.**
```
G="tag=botGroupId:$COINS"
curl -s "$BASE/actuator/metrics/bot_bet_amount_total?$G" | jq -r '.measurements[0].value'
curl -s "$BASE/actuator/metrics/bot_winnings_total?$G"   | jq -r '.measurements[0].value'
grep -h "session .* ended" $LOGS/detail/detail.log | grep "$COINS" | tail -5
curl -s $BASE/api/v1/bot-group/$COINS/health | jq '[.bots[] | {userName, lastFetchedBalance}]'
```
Expect `bot_bet_amount_total` **> 0 and ≈ 5000 × settled rounds × 2 bots** since V-16;
`bot_winnings_total` **> 0 from the first settled round** (stock has no losing round, AD-15);
their ratio **near 0.98, below 1.0**; the aggregated session line reading `confirmed staked:`
**non-zero and tracking `total staked:`** where it read `total staked: 10000 … confirmed staked:
0` before; and `lastFetchedBalance` **drifting off `1,000,000,000`** for at least one coins bot
(it re-reads on 1 % drift, so a spread, not a jump).

**V-19 — TRACE window: one bet → commit pair verbatim, and the server's `13022` answer if any.**
`OutputPrinter` frames are TRACE and the box runs DEBUG, so `grep '"cmd":13022' detail.log`
finds nothing until this is done (OI-10). Same bounded procedure `release.md` used: **raise,
capture, reset, ≤ 120 s** — this is a global logger flip, so keep it short:
```
curl -s -X POST $BASE/actuator/loggers/com.vingame.bot.domain.bot.util.OutputPrinter \
     -H 'Content-Type: application/json' -d '{"configuredLevel":"TRACE"}'
sleep 90
curl -s -X POST $BASE/actuator/loggers/com.vingame.bot.domain.bot.util.OutputPrinter \
     -H 'Content-Type: application/json' -d '{"configuredLevel":null}'
grep -h "User rikcoin1" $LOGS/detail/detail.log | grep -E '"cmd":1300[26]|"cmd":13022' | tail -40
```
Expect, per bet, **two consecutive `[SENT]` lines from the same bot in this order**:
```
[SENT] ["6","MiniGame","stockPlugin",{"cmd":13002,"v":1000,"sid":<S>,"aid":1,"eid":<e>,"iAc":true}]
[SENT] ["6","MiniGame","stockPlugin",{"cmd":13022,"sId":<S>}]
```
with the **same `<S>`**, the second key spelled **`sId`** (a `"sid"` here is the AD-30 fold and a
build defect — stop and fix before reading anything else). Then record verbatim: any
`[RECEIVED] … "cmd":13022 …` line (the server's answer — shape unknown, it has never been seen),
and the next `[RECEIVED] … "cmd":13006 …` for `<S>` with its `mbs` **non-empty**. Paste both
into `release.md`. **If there is no `13022` response at all, that is a finding, not a failure**
— the legacy bot never handled one either. If the response carries an error and a second commit
in the same round is what draws it, AD-29's once-per-round fallback is the next experiment.

**V-20 — the control and the other five products are untouched; the stack came back.**
```
curl -s "$BASE/actuator/metrics/bot_bets_placed_total?tag=botGroupId:$TXMD5" | jq -r '.measurements[0].value'
curl -s "$BASE/actuator/metrics/bot_messages_total?tag=botGroupId:$TXMD5&tag=cmd:endGame" | jq -r '.measurements[0].value'
docker logs bot-java-bot-manager-1 2>&1 | grep "MessageTypesRegistry initialized"
grep -h "User riktxm" $LOGS/detail/detail.log | grep -c '"cmd":7022'      # during the V-19 window
grep -h "User riktxm" $LOGS/detail/detail.log | grep -c '"cmd":13022'
# $OTHER_GROUP = any running group on 097/098/116/118/119
curl -s $BASE/api/v1/bot-group/$OTHER_GROUP/health | jq '{status, connected:[.bots[].status]|map(select(.=="CONNECTION_AUTHENTICATED"))|length}'
docker compose ps; curl -s -o /dev/null -w '%{http_code}\n' :3000/api/health; curl -s -o /dev/null -w '%{http_code}\n' :9090/-/healthy; curl -s :3100/ready
```
Expect `$TXMD5` bets **still climbing 1:1 with its `endGame`** versus V-16; **both grep counts
`0`** (no commit of any cmd left a txmd5 bot — the AD-21 allowlist held); the registry line
**unchanged** (`BETTING_MINI 6 products [097, 098, 114, 116, 118, 119]`); one non-114 group's
status and connected count unchanged; all containers `Up`, `200`, `200`, `ready`.

**The two outcomes, decided now.**
- **V-17 > 0 — Phase 3 is done.** Record in `release.md` that `13022` alone was sufficient and
  therefore **`13012` was not needed**: the browser sends it, the legacy bot never did, and the
  server settled without it. Phase 3b does not open. Close OI-3's "next step" sentence. Then,
  and only then, the user may widen `$COINS`' bet spread — honouring OI-8's ladder.
- **V-17 still 0 — Phase 3b opens (AD-34).** First, in order: the build shipped
  (`RikStockCommitTest` in the test report, new jar on the box); V-19 shows the pair on the wire
  with `sId` spelled right; `$TXMD5` still settles (else the allowlist inverted). Only then
  `13012`. And after *that*, if V-17 is still zero, the question leaves this codebase — RIK
  back-office/game team, with `release.md`'s captures attached — and no further frame is
  guessed at.

**Cleanup.** None. `$COINS` and `$TXMD5` stay exactly as they are until the verdict is written.

---

## Amendment B1 (2026-09-17) — AD-23's cited precedent is wrong, and the Jackson version is 2.15.2

Raised at Phase 2 compliance. Two corrections, one of which is load-bearing for anyone who
writes another RIK frame; neither changes a shipped decision, a phase boundary or a
verification step.

### 1. `AutoBet.AutoBetData.getIsMini()` is NOT the precedent for emitting `iAc`

AD-23 is right that `@JsonProperty("iAc")` is mandatory and that its absence is silent. What it
gets wrong is *why the codebase's existing workaround works*, and therefore what a reader should
copy. AD-23 says the codebase "hand-wrote the getter around it" and points at
`getIsMini()` — which reads as "hand-writing the getter is how you fix this". **It is not.**
Measured against the Jackson actually on this module's classpath:

```
                                             repo lombok.config     WITHOUT copyableAnnotations
@JsonProperty + @Getter(NONE)  (SHIPPED)     {"iAc":true}           {"iAc":true}
@JsonProperty + Lombok's isIAc()             {"iAc":true}           {"iac":true,"iAc":true}
@JsonProperty + HAND-WRITTEN isIAc()         {"iac":true,"iAc":true}  {"iac":true,"iAc":true}
Lombok's isIAc() only, no annotation         {"iac":true}           {"iac":true}
getIAc()          (the AutoBet trick)        {"iac":true}           {"iac":true}

isMini()                                     {"mini":true}
getIsMini()       (the real AutoBet)         {"isMini":true}   <- works, for a reason that does not generalise
```

**The second column is why row 1 ships.** The repo root carries a *tracked* `lombok.config`
with `lombok.copyableAnnotations += com.fasterxml.jackson.annotation.JsonProperty`, so Lombok
stamps the annotation onto the getter it generates and Jackson merges the two accessors into
one property. That is what makes row 2 correct today — and it is a two-line file two
directories up from the class that depends on it. Row 1 is the only row correct under **every**
configuration, which is the whole reason to suppress the getter. Note row 3: Lombok cannot copy
an annotation onto a getter it did not generate, so a *hand-written* `isIAc()` produces the
double key whatever the config says.

Jackson de-mangles a getter name by lower-casing the **entire leading uppercase run**.
`getIsMini` → `IsMini` → the run is the single `I`, followed by lowercase `s`, so exactly one
character folds and `isMini` survives. `getIAc` → `IAc` → the run is `IA`, **both** characters
fold, and the key collapses to `iac`. `AutoBet` works by accident of its field name, not by a
transferable technique. **No getter-naming trick can produce `iAc`; only an explicit
`@JsonProperty` can.** Suppressing the generated getter is then belt-and-braces rather than
strictly required *under this repo's current `lombok.config`* — see the second column above.

Consequences recorded here rather than left implicit:

- **Phase 2's step 2 as written was fragile, not broken.** "Mirror `TaiXiuBet`'s shape
  (`@Getter`/`@Setter` static `BetData`) with one addition: `@JsonProperty("iAc")`" happens to
  emit the correct single `iAc` today, but only because of `lombok.config`'s
  `copyableAnnotations` line; delete that line and the same source emits
  `{"iac":true,"iAc":true}`, a frame no real client sends. The implementation added
  `@Getter(AccessLevel.NONE)` on the field, which is the **approved deviation** and the shape
  that does not depend on a file outside the module. `RikStockBet`'s javadoc states the full reasoning and `RikStockBetTest` pins it with
  `doesNotContain("\"iac\"")` alongside the exact key set.
- **This applies to every future RIK frame**, and the next one is already known: ziczac's body
  carries `c` (the ball count), and `RIK_114_ZICZAC` Phase 2 will meet the same class of
  question. Anyone emitting a camelCase key whose first two characters are `<lower><UPPER>`
  should assume the mangling bites and assert the serialized key, not the round-trip.
  *Promoted to a decision the same day: **AD-30**, after `sId` became the third such key.*

### 2. The Jackson version is 2.15.2, not 2.18.2

AD-23 and the Phase 2 implementation note both cite "the project's Jackson 2.18.2".
`mvn -pl bot-messages dependency:tree` resolves
`com.fasterxml.jackson.core:jackson-databind:2.15.2`. The observed behaviour is identical on
both — the leading-uppercase-run fold is long-standing and unchanged — so **nothing else in
this plan or in the shipped code depends on the difference**. Corrected so the next person who
re-runs the empirical check against the real classpath is not left wondering which claim to
trust.

### What did NOT change

No architecture decision is reversed. AD-20 … AD-27 stand as written, the allowlist of AD-21
stands, `Request`/`Bet` stay frozen (AD-25), `ZicZacBet` stays deferred (AD-24), Phase 3 stays
gated on V-12 (AD-27), and **V-11 … V-15 are unchanged and remain the Releaser's steps**.

*Later the same day, Amendment A4 amended AD-27 (Phase 3 sends `13022`, not `13012`) and added
AD-28 … AD-34 and V-16 … V-20. Nothing in this amendment is affected by that.*

## Amendment A5 (2026-09-17) — Phase 3 step 7 could not satisfy step 8(c); the test shape that does is recorded

Raised at Phase 3 compliance. One falsifiable error in the plan's own test specification, one
arithmetic slip, and one clarification recorded so it is not re-litigated. **No architecture
decision changes**: AD-28 … AD-34 stand as written, and the shipped code is accepted as-is.

### 1. Step 7's ordering test cannot see the production `.onSent(...)` line

Step 8(c) said: *"Comment out the `.onSent(…)` line: the ordering test must go red with exactly
one `send`."* The ordering test step 7 specified is a **hand-built one-stage pipeline** —
`pipeline(ctx).sendAsync(buildMessage().messageSupplier(betSupplier).mode(ONCE).onSent(bot.afterBetSent(mapper))…)`
— which passes the callback to *its own* `buildMessage()`. It never calls
`bot.botBehaviorScenario()`, so the production line at `BettingMiniGameBot.java:1034`
(`.onSent(mdcConsumer(afterBetSent(mapper)))`) is not on its code path, and commenting that line
out leaves the test **green**. As written, 8(c) was unsatisfiable by the test it named. The
step-7 test is still the right proof of **AD-31's library claim** — that `SendAsync` runs
`onSent` on the same thread immediately after `client.send` returns, so bet-before-commit is
structural — it just proves nothing about whether the scenario *wires* the callback in.

**Step 7 is amended** to require both tests in `BettingMiniGameBotCommitDispatchTest`:

- `stockSendsBetThenCommitInOrder` — the one-stage pipeline exactly as step 7 specified. Proves
  the library ordering. **Expected to stay green under mutation 8(c).**
- `stockRealScenarioSendsCommitAfterEveryBet` — compiles the bot's **real**
  `botBehaviorScenario()`, waits out the leading `waitFor(1_000L)`, feeds one RECEIVED frame
  matching `cmd(subscribeCmd())` so the `Send`/`waitForMessage` stages pass and the `INFINITE`
  bet stage arms, captures every `client.send`, and asserts: the first frame is the subscribe,
  the first `13002` precedes the first `13022`, **every `13002` is immediately followed by a
  `13022` carrying the same `sid` under `sId`**, and `commits ∈ [bets − 1, bets]` (the last bet
  may be captured before its commit if `shutdown` races it). This is the test 8(c) reds.

**Step 8(c) is amended** to read: *Comment out the `.onSent(…)` line in `botBehaviorScenario()`:
`stockRealScenarioSendsCommitAfterEveryBet` must go red (no `13022` is ever sent);
`stockSendsBetThenCommitInOrder` stays green, and that is expected — it pins the library, not the
wiring. If the real-scenario test stays green, it is not exercising the production scenario.*

### 2. Step 6's derivation example: `17000 + 3022 = 20022`, not `17022`

Step 6 asked `RikStockCommitTest` to assert `cmd == 17022` for offset 17000. That is the
offset's leading digits pasted over the CODE, not a sum; the point of the assertion is that the
CMD is *derived*, so the correct value is **20022**. `RikStockCommitTest.cmdDerivesFromOffset`
asserts `20022` and is right. Corrected here so nobody "fixes" the test to match the plan.

### 3. The `Request` / `TaiXiuRequest` freeze check is checkable exactly as written

Dev reported the freeze as "not literally checkable because `main` has no `bot-messages/`
module" and pinned by session-start hash instead. That premise is wrong, and the plan's wording
needs no change: AD-28 and step 1 say **`git diff` on both must be empty**, which compares the
working tree to the index/HEAD — and both files **are tracked at HEAD** under
`bot-messages/src/main/java/com/vingame/bot/domain/bot/message/request/`. At Phase 3 compliance:
`git diff HEAD -- Request.java TaiXiuRequest.java` is empty; both mtimes are `2026-08-04`; and
each file's sha256 equals its copy at the pre-split path on `main`
(`src/main/java/…/message/request/Request.java` → `b053cd3a…`, `TaiXiuRequest.java` →
`0081d16e…`). The hash pin Dev added is harmless belt-and-braces. The plan never said
"byte-identical to `main`"; that phrase is used only of subscribe frames. **No wording change.**

### What did NOT change

AD-27 (amended) … AD-34 stand. `13012` remains Phase 3b, gated on V-17. V-16 … V-20 are unchanged
and remain the Releaser's steps. The plan's step 8(a) and 8(b) mutations were observed being
exercised by hand against the working tree during compliance (`@JsonProperty("sId")` removed;
the `STOCK_PLUGIN` allowlist row disabled) and the tree was restored, hash-verified, before the
compliance build — recorded in `docs/reviews/RIK_114_BETTING_MINI/compliance.md`.
